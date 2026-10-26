/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Refuses to COMPLETE (documentAction=CO) a rectificativa invoice whose completion would push
 * the total of ALL completed rectificativas against the same origin invoice past that origin's
 * own total (ETP-5745 CP-9).
 *
 * <p><b>Why.</b> Nothing enforced this before: FV1000167 (363 €) could be — and was, in the CP-9
 * repro — rectified by two separately completed rectificativas totaling -726 €, double the
 * original. A rectificativa corrects a SUBSET (up to the whole) of its origin's total; nothing
 * about the document type stops the user from creating and completing as many as they like.
 *
 * <p><b>Comparison is on absolute value</b>: a rectificativa total is conventionally negative
 * (a credit against the original), but {@code ETGO_SF_ENTITY}/{@code ad_callouts} do not enforce
 * the sign, and the ticket's own repro invoice is itself a positive "under-invoiced correction"
 * case (see {@code AbstractInvoiceHeaderHandler#enrichInvoiceSubtype} javadoc) — so the guard
 * must not assume a sign and instead compares magnitudes.
 *
 * <p><b>Fails OPEN on a read error</b>, mirroring {@link InvoiceReactivationGuard}: this guard's
 * own failure to read the invoice totals must never block a legitimate completion.
 *
 * <p><b>Multi-currency (QA finding, ETP-5745 CP-9 follow-up).</b> {@code RectifiableInvoiceUtils}'
 * "eligible source invoices" query filters by organization and business partner, never by
 * currency, and nothing else constrains a rectificativa to its origin's currency — so in a
 * multi-currency org a rectificativa can legitimately be booked in a different currency than its
 * origin. Every rectificativa total is converted to the ORIGIN's currency (the unit the comparison
 * is actually made in) before summing, reusing the exact general-rate lookup pattern
 * {@code PaymentCurrencyConverter#generalRate} already established for this kind of problem:
 * {@link FinancialUtils#getConversionRate}, as of the rectificativa's OWN invoice date (not the
 * origin's — each rectificativa converts at the rate in effect when IT was issued), scoped to its
 * own org/client. A rectificativa's document-level rate ({@code C_Conversion_Rate_Doc}) is
 * deliberately NOT consulted here: that table records an invoice's rate to a PAYMENT/account
 * currency, not to another invoice's currency, so it has no natural meaning for this comparison.
 * Same currency → no lookup, multiply by 1. <b>No rate configured for the pair/date → fails OPEN
 * for that one rectificativa</b> (logged, excluded from the sum, the rest of the check proceeds
 * normally): consistent with every other failure mode in this guard, an unconvertible amount must
 * not block a legitimate completion — the alternative (hard-blocking on a missing FX rate) would
 * make a configuration gap in Application Dictionary degrade into a frozen AP/AR process.
 *
 * <p><b>Placement.</b> Invoice-specific, not shared NEO code: wired from
 * {@link AbstractInvoiceHeaderHandler#completeInvoiceIfNeeded}, immediately before
 * {@link InvoiceCompletionService#completeInvoice} — the same spot
 * {@code validateLineQtyBeforeComplete} used to occupy. Applies identically to sales and
 * purchase invoices: {@code C_Invoice}/{@code C_Invoice_Reverse} are shared tables and nothing
 * here branches on a spec or table name.
 */
final class RectificationTotalGuard {

  private static final Logger log = LogManager.getLogger(RectificationTotalGuard.class);

  private static final String STATUS_COMPLETED = "CO";

  /** Stable identity sent in messageKeys — a plain string, not an AD_MESSAGE (ETP-5692 precedent). */
  static final String MSG_RECTIFICATION_EXCEEDS_ORIGINAL = "ETGO_InvoiceRectificationExceedsOriginal";

  private RectificationTotalGuard() {
  }

  /**
   * Refuses the completion when it would push the rectified total past the origin's total for
   * ANY origin this invoice rectifies; otherwise returns {@code null} so the normal completion
   * flow runs unchanged.
   *
   * @param context the current request
   * @return a 422 naming the origin invoice and the two totals, or {@code null} to let the
   *     completion proceed
   */
  static NeoResponse checkCompletion(NeoContext context) {
    if (!InvoiceCalloutHelper.isInvoiceCompleteAction(context)) {
      return null;
    }
    String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      for (String originId : originInvoiceIds(invoiceId)) {
        Invoice origin = OBDal.getInstance().get(Invoice.class, originId);
        BigDecimal originTotal = absTotal(origin);
        if (origin == null || originTotal == null) {
          continue;
        }
        BigDecimal rectifiedTotal = sumCompletedRectifications(originId, invoiceId, origin.getCurrency());
        if (rectifiedTotal.compareTo(originTotal) > 0) {
          return reject(originId, originTotal, rectifiedTotal);
        }
      }
      return null;
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not evaluate the rectification total guard for invoice {}: {}", invoiceId,
          e.getMessage());
      return null;
    }
  }

  /** Every ORIGIN invoice this invoice rectifies (active {@code C_Invoice_Reverse} links). */
  @SuppressWarnings("java:S2077")
  private static List<String> originInvoiceIds(String invoiceId) {
    List<String> origins = new ArrayList<>();
    String sql = "SELECT DISTINCT reversed_c_invoice_id FROM c_invoice_reverse "
        + "WHERE c_invoice_id = ? AND isactive = 'Y'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          origins.add(rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve origin invoices for {}: {}", invoiceId, e.getMessage());
    }
    return origins;
  }

  /** The absolute grand total of {@code invoice}, or {@code null} when it has none. */
  private static BigDecimal absTotal(Invoice invoice) {
    if (invoice == null || invoice.getGrandTotalAmount() == null) {
      return null;
    }
    return invoice.getGrandTotalAmount().abs();
  }

  /**
   * The ids of every rectificativa linked to {@code originId} that IS ALREADY completed, OR is
   * {@code excludeOrIncludeInvoiceId} itself (the one about to become completed by this very
   * request, still in whatever status it currently holds).
   */
  @SuppressWarnings("java:S2077")
  private static List<String> completedRectificationIds(String originId, String excludeOrIncludeInvoiceId) {
    List<String> ids = new ArrayList<>();
    String sql = "SELECT inv.c_invoice_id "
        + "FROM c_invoice_reverse r "
        + "JOIN c_invoice inv ON inv.c_invoice_id = r.c_invoice_id "
        + "WHERE r.reversed_c_invoice_id = ? AND r.isactive = 'Y' "
        + "AND (inv.docstatus = ? OR inv.c_invoice_id = ?)";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, originId);
      ps.setString(2, STATUS_COMPLETED);
      ps.setString(3, excludeOrIncludeInvoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.warn("Could not list completed rectifications for origin {}: {}", originId, e.getMessage());
    }
    return ids;
  }

  /**
   * The sum of {@code ABS(GrandTotal)} of every rectificativa linked to {@code originId} that IS
   * ALREADY completed, OR is {@code excludeOrIncludeInvoiceId} itself — each one converted to
   * {@code originCurrency} first when its own currency differs (see the class javadoc's
   * "Multi-currency" section). A rectificativa whose amount cannot be converted (no rate for its
   * currency pair/date) is skipped — excluded from the sum, not treated as a blocking failure —
   * consistent with this guard's fail-open stance everywhere else.
   *
   * <p>One {@code OBDal.get} per rectification id (N+1 vs. the single aggregate SQL this replaced)
   * — needed now that currency/date/org/client must be read per row for conversion. Accepted: this
   * runs on document completion, bounded by the small set of rectificativas against one origin,
   * never a list/report path.
   */
  private static BigDecimal sumCompletedRectifications(String originId, String excludeOrIncludeInvoiceId,
      Currency originCurrency) {
    BigDecimal sum = BigDecimal.ZERO;
    for (String id : completedRectificationIds(originId, excludeOrIncludeInvoiceId)) {
      Invoice rectification = OBDal.getInstance().get(Invoice.class, id);
      BigDecimal abs = absTotal(rectification);
      if (abs == null) {
        continue;
      }
      BigDecimal converted = convertToCurrency(abs, rectification, originCurrency);
      if (converted == null) {
        log.warn("Skipping rectification {} from the ETP-5745 over-rectification sum against "
            + "origin {}: no conversion rate {} -> {} for its invoice date", id, originId,
            rectification.getCurrency() != null ? rectification.getCurrency().getISOCode() : null,
            originCurrency != null ? originCurrency.getISOCode() : null);
        continue;
      }
      sum = sum.add(converted);
    }
    return sum;
  }

  /**
   * {@code amount} (already {@code rectification}'s own absolute total) expressed in
   * {@code targetCurrency}, or {@code null} when it cannot be converted — missing currency/date on
   * either side, or no {@code C_Conversion_Rate} configured for the pair as of
   * {@code rectification}'s own invoice date. Same currency on both sides short-circuits with no
   * lookup. Mirrors the general-rate half of {@code PaymentCurrencyConverter#resolveInvoiceRate}
   * (document-level {@code ConversionRateDoc} is deliberately NOT consulted — see the class
   * javadoc).
   */
  private static BigDecimal convertToCurrency(BigDecimal amount, Invoice rectification,
      Currency targetCurrency) {
    if (rectification == null || targetCurrency == null) {
      return null;
    }
    Currency sourceCurrency = rectification.getCurrency();
    if (sourceCurrency == null) {
      return null;
    }
    if (sourceCurrency.getId().equals(targetCurrency.getId())) {
      return amount;
    }
    if (rectification.getInvoiceDate() == null || rectification.getOrganization() == null
        || rectification.getClient() == null) {
      return null;
    }
    ConversionRate rate = FinancialUtils.getConversionRate(rectification.getInvoiceDate(),
        sourceCurrency, targetCurrency, rectification.getOrganization(), rectification.getClient());
    if (rate == null) {
      return null;
    }
    BigDecimal multiplyRate = rate.getMultipleRateBy();
    if (multiplyRate == null || multiplyRate.signum() == 0) {
      return null;
    }
    return amount.multiply(multiplyRate);
  }

  /**
   * The 422 refusal, in the same flat {@code success:false}/{@code message}/{@code messageKeys}/
   * {@code messageParams} shape {@link InvoiceReactivationGuard} answers with.
   */
  private static NeoResponse reject(String originId, BigDecimal originTotal, BigDecimal rectifiedTotal)
      throws JSONException {
    String message = "This rectification cannot be completed: the total already rectified against "
        + "the original invoice (" + rectifiedTotal + ") would exceed its own total (" + originTotal
        + ").";

    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", message);
    body.put("messageKeys", new JSONArray(List.of(MSG_RECTIFICATION_EXCEEDS_ORIGINAL)));
    JSONObject messageParams = new JSONObject();
    messageParams.put("originInvoiceId", originId);
    messageParams.put("originTotal", originTotal);
    messageParams.put("rectifiedTotal", rectifiedTotal);
    body.put("messageParams", messageParams);
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body);
  }
}
