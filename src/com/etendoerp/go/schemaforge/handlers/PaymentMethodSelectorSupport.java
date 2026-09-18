/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.handlers;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.PaymentRegistrationService;

/**
 * ETP-5238 — shared Payment Method SELECTOR filtering, extracted from the ETP-5183 contacts
 * implementation ({@code BusinessPartnerHandler}) so the same "no Financial Account linkage
 * required" behaviour can be wired into the 5 document header handlers (sales quotation, sales
 * order, sales invoice, purchase order, purchase invoice) without duplicating the query.
 *
 * <p>Classic (and every other window sharing these columns — sales invoice, sales order,
 * payment-in, simple G/L journal, the Classic Business Partner tabs themselves) resolves the
 * Payment Method selector through an {@code AD_Val_Rule}
 * ({@code FIN_PaymentMethodsWithAccountIsSOTrxControl}) that also requires the payment method to
 * be linked to at least one active Financial Account. On the Schema Forge windows wired to this
 * class that account-linkage requirement is wrong: the Payment Method a document (or a Business
 * Partner's preference) declares is configured independently of which Financial Account will end
 * up settling it. This class queries {@link FIN_PaymentMethod} directly — active + the
 * direction's own {@code Payin_Allow}/{@code Payout_Allow} flag only — with no
 * {@code FIN_FinAcc_PaymentMethod} join.
 *
 * <p><b>Direction resolution</b> is the one behavioural difference versus the original contacts
 * implementation, which derived direction purely from the field name (customer entity uses
 * {@code paymentMethod}, vendor/creditor entity uses {@code pOPaymentMethod}). The 5 document
 * windows all address the SAME field name ({@code paymentMethod} / {@code FIN_Paymentmethod_ID})
 * for both sales (pay-in) and purchase (pay-out) documents, so field name alone is not enough —
 * but the ambiguity is per-<b>window</b>, not per-field: {@code contacts}' Business Partner window
 * is not a transaction window at all, so a blind window-based fallback there would misclassify its
 * already-unambiguous {@code paymentMethod}/{@code pOPaymentMethod} field-name pair. Each caller
 * therefore declares its own fallback policy via {@link DirectionFallback}, passed into
 * {@link #handleIfPaymentMethodSelector(NeoContext, DirectionFallback)}. Resolution order:
 * <ol>
 *   <li>A direction signal read straight off the current {@link HttpServletRequest} — the sales
 *       vs. purchase indicator the 5 document windows declare as required selector context
 *       ({@code IsSOTrx}, {@code windowCategory}-sourced), or the {@code FIN_ISRECEIPT}/
 *       {@code IsReceipt}/{@code isReceipt} aliases the contacts window sends. {@code Y} → PAY_IN,
 *       {@code N} → PAY_OUT. Matched case-insensitively against the request's own parameter names.
 *       This wins regardless of the caller's declared fallback — it is the most specific signal
 *       available.</li>
 *   <li>Falls back to the caller's declared {@link DirectionFallback} only when no such request
 *       parameter is present: {@link DirectionFallback#FIELD_NAME} keeps today's contacts
 *       behaviour ({@code pOPaymentMethod}/{@code PO_Paymentmethod_ID} → PAY_OUT, everything else
 *       → PAY_IN); {@link DirectionFallback#WINDOW} walks {@code ctx.getAdTab().getWindow()
 *       .isSalesTransaction()} instead, for the 5 document windows that share one field name for
 *       both directions.</li>
 *   <li><b>Fails closed</b> when neither resolves a direction (e.g. {@code WINDOW} fallback with a
 *       null tab/window, or a null {@code isSalesTransaction()}) — never silently defaults to
 *       PAY_IN. See {@link #handleIfPaymentMethodSelector(NeoContext, DirectionFallback)}.</li>
 * </ol>
 *
 * <p>Not a {@code NeoHandler} — this is a plain support class invoked from the top of each
 * caller's own {@code handle()}. {@link #handleIfPaymentMethodSelector(NeoContext,
 * DirectionFallback)} returns {@code null} whenever the request isn't its case (not a SELECTOR
 * request, or a field this class doesn't own) so callers can unconditionally check the result and
 * fall through to their own logic unchanged — but returns an explicit error response (never
 * {@code null}) when the request IS its case and direction resolution fails closed, so a
 * misconfigured window surfaces as a visible 422 instead of silently serving the wrong list.
 */
public final class PaymentMethodSelectorSupport {

  private static final Logger log = LogManager.getLogger(PaymentMethodSelectorSupport.class);

  private static final String SELECTOR_FIELD_PAYMENT_METHOD = "paymentMethod";
  private static final String SELECTOR_COLUMN_PAYMENT_METHOD = "FIN_Paymentmethod_ID";
  private static final String SELECTOR_FIELD_PO_PAYMENT_METHOD = "pOPaymentMethod";
  private static final String SELECTOR_COLUMN_PO_PAYMENT_METHOD = "PO_Paymentmethod_ID";

  private static final String SELECTOR_PARAM_SEARCH = "q";
  private static final String SELECTOR_PARAM_LIMIT = "limit";
  private static final String SELECTOR_PARAM_OFFSET = "offset";
  private static final int SELECTOR_DEFAULT_LIMIT = 20;
  private static final int SELECTOR_MAX_LIMIT = 100;

  private static final String SELECTOR_FIELD_ID = "id";
  private static final String SELECTOR_FIELD_LABEL = "label";
  private static final String SELECTOR_FIELD_ITEMS = "items";
  private static final String SELECTOR_FIELD_COLUMNS = "columns";
  private static final String SELECTOR_FIELD_TOTAL_COUNT = "totalCount";
  private static final String SELECTOR_FIELD_HAS_MORE = "hasMore";

  // Direction-signal request parameter names. Matched case-insensitively against the actual
  // request parameter names — see findParamCaseInsensitive(). IS_SO_TRX is checked first: it is
  // what the 5 document windows send (context.required: [{param: "IsSOTrx", source:
  // "windowCategory"}]); the FIN_ISRECEIPT-family names are the contacts-window aliases and are
  // only consulted when IsSOTrx is absent.
  private static final String PARAM_IS_SO_TRX = "IsSOTrx";
  private static final String PARAM_FIN_IS_RECEIPT = "FIN_ISRECEIPT";
  private static final String PARAM_IS_RECEIPT = "IsReceipt";
  private static final String DIRECTION_VALUE_YES = "Y";
  private static final String DIRECTION_VALUE_NO = "N";

  /** Pay-in (customer/sales) vs. pay-out (vendor/creditor/purchase) direction. */
  private enum PaymentMethodDirection {
    PAY_IN,
    PAY_OUT
  }

  /**
   * The direction-resolution fallback a caller declares when no request-level direction signal
   * ({@code IsSOTrx}/{@code FIN_ISRECEIPT}/{@code IsReceipt}) is present. The ambiguity this
   * resolves is per-<b>window</b>, not per-field — see the class javadoc.
   */
  public enum DirectionFallback {
    /**
     * Derive direction from the SELECTOR field name alone: {@code pOPaymentMethod}/
     * {@code PO_Paymentmethod_ID} → PAY_OUT, everything else → PAY_IN. Correct only when the
     * caller's own field names already distinguish the two directions (the {@code contacts}
     * window's customer vs. vendor/creditor Payment Method fields).
     */
    FIELD_NAME,
    /**
     * Derive direction from {@code ctx.getAdTab().getWindow().isSalesTransaction()}. Correct
     * only for a real transaction window — required by the 5 document windows, which all share
     * one field name ({@code paymentMethod}/{@code FIN_Paymentmethod_ID}) for both directions.
     */
    WINDOW
  }

  private PaymentMethodSelectorSupport() {
  }

  /**
   * Entry point: resolves and serves the Payment Method SELECTOR request for {@code ctx} when
   * applicable. Returns {@code null} whenever this isn't its case — not a SELECTOR request, or a
   * field this class doesn't own — so the caller falls through to its own default handling
   * unchanged. When the request IS its case but the direction cannot be resolved (request signal
   * absent AND the declared {@code fallback} also fails to resolve one), this fails closed with an
   * explicit error response rather than guessing PAY_IN.
   *
   * @param ctx the current NEO request context
   * @param fallback the direction-resolution policy this caller declares for when no
   *     request-level direction signal is present — see {@link DirectionFallback}
   * @return the selector response, an error response when direction resolution fails closed, or
   *     {@code null} when this class does not handle the request
   */
  public static NeoResponse handleIfPaymentMethodSelector(NeoContext ctx, DirectionFallback fallback) {
    if (ctx == null || ctx.getEndpointType() != NeoEndpointType.SELECTOR) {
      return null;
    }
    if (!isPaymentMethodField(ctx.getFieldName())) {
      return null;
    }
    PaymentMethodDirection direction = resolveDirection(ctx, fallback);
    if (direction == null) {
      log.warn("PaymentMethodSelectorSupport: could not resolve pay-in/pay-out direction for "
          + "field '{}' (fallback={}, spec={}) — refusing to guess", ctx.getFieldName(), fallback,
          ctx.getSpecName());
      return NeoResponse.error(422, "Unable to determine payment method direction");
    }
    try {
      return queryPaymentMethods(direction);
    } catch (Exception e) {
      log.error("PaymentMethodSelectorSupport: error querying payment method selector ({}): {}",
          direction, e.getMessage(), e);
      return NeoResponse.error(500, "Error querying payment methods");
    }
  }

  private static boolean isPaymentMethodField(String fieldName) {
    if (StringUtils.isBlank(fieldName)) {
      return false;
    }
    return SELECTOR_FIELD_PAYMENT_METHOD.equalsIgnoreCase(fieldName)
        || SELECTOR_COLUMN_PAYMENT_METHOD.equalsIgnoreCase(fieldName)
        || SELECTOR_FIELD_PO_PAYMENT_METHOD.equalsIgnoreCase(fieldName)
        || SELECTOR_COLUMN_PO_PAYMENT_METHOD.equalsIgnoreCase(fieldName);
  }

  /**
   * Resolves the pay-in/pay-out direction: a request-level direction signal wins when present
   * (see class javadoc for the priority order), otherwise falls back to the caller's declared
   * {@link DirectionFallback}. Returns {@code null} when neither resolves a direction — the
   * caller must fail closed rather than guess.
   */
  private static PaymentMethodDirection resolveDirection(NeoContext ctx, DirectionFallback fallback) {
    PaymentMethodDirection fromRequest = resolveDirectionFromRequest(currentSelectorRequest());
    if (fromRequest != null) {
      return fromRequest;
    }
    if (fallback == DirectionFallback.WINDOW) {
      return resolveDirectionFromWindow(ctx);
    }
    return resolveDirectionFromFieldName(ctx.getFieldName());
  }

  private static PaymentMethodDirection resolveDirectionFromRequest(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String raw = findParamCaseInsensitive(request, PARAM_IS_SO_TRX);
    if (raw == null) {
      raw = findParamCaseInsensitive(request, PARAM_FIN_IS_RECEIPT, PARAM_IS_RECEIPT);
    }
    return toDirection(raw);
  }

  private static PaymentMethodDirection toDirection(String rawValue) {
    if (DIRECTION_VALUE_YES.equalsIgnoreCase(rawValue)) {
      return PaymentMethodDirection.PAY_IN;
    }
    if (DIRECTION_VALUE_NO.equalsIgnoreCase(rawValue)) {
      return PaymentMethodDirection.PAY_OUT;
    }
    return null;
  }

  private static PaymentMethodDirection resolveDirectionFromFieldName(String fieldName) {
    if (SELECTOR_FIELD_PO_PAYMENT_METHOD.equalsIgnoreCase(fieldName)
        || SELECTOR_COLUMN_PO_PAYMENT_METHOD.equalsIgnoreCase(fieldName)) {
      return PaymentMethodDirection.PAY_OUT;
    }
    return PaymentMethodDirection.PAY_IN;
  }

  /**
   * Derives direction from the AD Window's own sales/purchase flag — {@code
   * ctx.getAdTab().getWindow().isSalesTransaction()} — mirroring the null-safety and try/catch
   * style of {@code SelectorContextResolver.resolveIsSOTrxFromWindow} (not reused directly: that
   * method is {@code private} there, and introducing a new cross-package dependency between
   * {@code schemaforge} and {@code schemaforge.handlers} was already flagged in review). Returns
   * {@code null} — never a guessed direction — when the tab, window, or the flag itself is
   * unavailable, so the caller fails closed instead of defaulting to PAY_IN.
   */
  private static PaymentMethodDirection resolveDirectionFromWindow(NeoContext ctx) {
    try {
      Tab tab = ctx != null ? ctx.getAdTab() : null;
      if (tab == null) {
        return null;
      }
      Window window = tab.getWindow();
      if (window == null) {
        return null;
      }
      Boolean isSalesTransaction = window.isSalesTransaction();
      if (isSalesTransaction == null) {
        return null;
      }
      return isSalesTransaction ? PaymentMethodDirection.PAY_IN : PaymentMethodDirection.PAY_OUT;
    } catch (Exception e) {
      log.debug("PaymentMethodSelectorSupport: could not resolve direction from window: {}",
          e.getMessage());
      return null;
    }
  }

  /**
   * Looks up a request parameter by name, matching case-insensitively against the request's own
   * parameter names (not just the candidate's casing) since the actual caller may send any
   * casing variant. Returns the first non-blank value found among {@code candidateNames}, tried
   * in order.
   */
  private static String findParamCaseInsensitive(HttpServletRequest request, String... candidateNames) {
    Map<String, String[]> parameterMap = request.getParameterMap();
    if (parameterMap == null || parameterMap.isEmpty()) {
      return null;
    }
    for (String candidate : candidateNames) {
      for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
        if (!candidate.equalsIgnoreCase(entry.getKey())) {
          continue;
        }
        String[] values = entry.getValue();
        String value = (values != null && values.length > 0) ? StringUtils.trimToNull(values[0]) : null;
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  /**
   * Queries {@code FIN_PaymentMethod} directly — active + the direction's own allow flag only, no
   * {@code FIN_FinAcc_PaymentMethod} join — and returns the same
   * {@code {items, columns, totalCount, hasMore}} envelope the generic selector produces.
   */
  private static NeoResponse queryPaymentMethods(PaymentMethodDirection direction) throws Exception {
    HttpServletRequest request = currentSelectorRequest();
    String search = request != null
        ? StringUtils.trimToNull(request.getParameter(SELECTOR_PARAM_SEARCH)) : null;
    int limit = parseSelectorIntParam(request, SELECTOR_PARAM_LIMIT, SELECTOR_DEFAULT_LIMIT, 1,
        SELECTOR_MAX_LIMIT);
    int offset = parseSelectorIntParam(request, SELECTOR_PARAM_OFFSET, 0, 0, Integer.MAX_VALUE);

    try {
      OBContext.setAdminMode();

      int totalCount = buildPaymentMethodCriteria(direction, search).count();

      OBCriteria<FIN_PaymentMethod> dataCriteria = buildPaymentMethodCriteria(direction, search);
      dataCriteria.addOrderBy(FIN_PaymentMethod.PROPERTY_NAME, true);
      dataCriteria.setMaxResults(limit);
      dataCriteria.setFirstResult(offset);
      List<FIN_PaymentMethod> rows = dataCriteria.list();

      JSONArray items = new JSONArray();
      for (FIN_PaymentMethod paymentMethod : rows) {
        JSONObject item = new JSONObject();
        item.put(SELECTOR_FIELD_ID, paymentMethod.getId());
        item.put(SELECTOR_FIELD_LABEL, paymentMethod.getName());
        items.put(item);
      }

      JSONObject result = new JSONObject();
      result.put(SELECTOR_FIELD_ITEMS, items);
      result.put(SELECTOR_FIELD_COLUMNS, new JSONArray());
      result.put(SELECTOR_FIELD_TOTAL_COUNT, totalCount);
      result.put(SELECTOR_FIELD_HAS_MORE, offset + limit < totalCount);
      return NeoResponse.ok(result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static OBCriteria<FIN_PaymentMethod> buildPaymentMethodCriteria(
      PaymentMethodDirection direction, String search) {
    OBCriteria<FIN_PaymentMethod> criteria = OBDal.getInstance().createCriteria(FIN_PaymentMethod.class);
    criteria.add(Restrictions.eq(FIN_PaymentMethod.PROPERTY_ACTIVE, true));
    // ETP-5238: direction→property mapping lives in ONE place — PaymentRegistrationService, also
    // used by the payment-modal method list (handleListPaymentMethods) — so this selector and
    // that list can never diverge on which flag means which direction.
    boolean isReceipt = direction == PaymentMethodDirection.PAY_IN;
    criteria.add(Restrictions.eq(PaymentRegistrationService.paymentMethodAllowProperty(isReceipt),
        true));
    if (StringUtils.isNotBlank(search)) {
      criteria.add(Restrictions.ilike(FIN_PaymentMethod.PROPERTY_NAME, search, MatchMode.ANYWHERE));
    }
    return criteria;
  }

  /**
   * The current request, resolved from Openbravo's request-scoped {@link RequestContext} rather
   * than threaded through {@link NeoContext} — the SELECTOR sub-endpoint's hook context does not
   * carry query params (only CRUD does).
   */
  private static HttpServletRequest currentSelectorRequest() {
    return RequestContext.get() != null ? RequestContext.get().getRequest() : null;
  }

  private static int parseSelectorIntParam(HttpServletRequest request, String name,
      int defaultValue, int min, int max) {
    if (request == null) {
      return defaultValue;
    }
    String raw = StringUtils.trimToNull(request.getParameter(name));
    if (raw == null) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(raw);
      if (value < min) {
        return min;
      }
      return Math.min(value, max);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
