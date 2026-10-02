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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * Funds transfers between financial accounts as declared actions of {@code financial-account/
 * account} (ETP-5558): what the Movements tab's *Transferir* ({@code FundsTransferModal}) lets a
 * person do.
 *
 * <p>The write is handed to the SPA's {@code financial-account-transactions} endpoint as {@code
 * ?action=transfer} with the body the modal sends — through {@link
 * FinancialAccountTransactionsEndpoint}, so its customization is resolved and run as REST runs it.
 * The transfer itself stays Classic's ({@code FundsTransferActionHandler.createTransfer}): a
 * withdrawal in the source, a deposit in the destination, optional bank fees, all processed. What
 * this class adds is what the modal settles before it calls:</p>
 * <ul>
 *   <li>the destination must be an active account the tenant can read, other than the source
 *       (the modal's dropdown lists only those);</li>
 *   <li>a G/L item is required and the amount must be above zero;</li>
 *   <li>between two currencies the rate is the system rate for the day, as the modal prefills it,
 *       unless the caller sends one; with neither, the transfer is refused (the modal's Confirmar
 *       stays disabled);</li>
 *   <li>the date is today, as the modal books it — it offers no other;</li>
 *   <li>bank fees are not negative, and the description fits its 255 characters.</li>
 * </ul>
 *
 * <p>A transfer cannot be deleted afterwards, in the UI or here: both legs are referenced by each
 * other, and the movements endpoint refuses to delete either (409). It is undone the way a person
 * undoes it, with a transfer back.</p>
 */
class FinancialAccountTransferActions {

  private static final Logger log = LogManager.getLogger(FinancialAccountTransferActions.class);

  static final String DESTINATIONS_ACTION = "transferDestinations";
  static final String TRANSFER_ACTION = "transferFunds";

  static final String P_DESTINATION = "destinationAccountId";
  static final String P_AMOUNT = "amount";
  static final String P_GL_ITEM_ID = "glItemId";
  static final String P_CONVERSION_RATE = "conversionRate";
  static final String P_DESCRIPTION = "description";
  static final String P_FEE_FROM = "bankFeeFrom";
  static final String P_FEE_TO = "bankFeeTo";

  private static final String KEY_CURRENCY = "currency";

  /** The movements endpoint. A seam so unit tests see the exact body it receives. */
  private final Function<NeoContext, NeoResponse> transactionsEndpoint;
  /** The rate the modal prefills ({@code validate-exchange-rate}). A seam for unit tests. */
  private final RateLookup rates;
  /** "Today", as the modal's {@code todayCalendarISO}. A seam for unit tests. */
  private final java.util.function.Supplier<LocalDate> today;

  /**
   * The system rate from one currency to another on a day, for an organization, or {@code null}.
   * The organization is the SOURCE account's: the lookup is organization-sensitive (an
   * organization's own rates are eligible next to organization 0's), and the money leaves from it.
   */
  @FunctionalInterface
  interface RateLookup {
    Double rate(String fromCurrencyId, String toCurrencyId, LocalDate date, String orgId)
        throws Exception;
  }

  FinancialAccountTransferActions() {
    this(FinancialAccountTransactionsEndpoint::call,
        NeoExchangeRateService::rate, LocalDate::now);
  }

  FinancialAccountTransferActions(Function<NeoContext, NeoResponse> transactionsEndpoint,
      RateLookup rates, java.util.function.Supplier<LocalDate> today) {
    this.transactionsEndpoint = transactionsEndpoint;
    this.rates = rates;
    this.today = today;
  }

  /** @return whether {@code action} is one of the transfer actions */
  static boolean serves(String action) {
    return action != null && actionContracts().containsKey(action);
  }

  /**
   * The transfer actions, declared for agents. {@code id} is always the SOURCE account.
   *
   * @return the contracts, in presentation order
   */
  static Map<String, NeoActionContract> actionContracts() {
    String idDescription = "the id of the financial account the money leaves (the source)";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(DESTINATIONS_ACTION, NeoActionContract.read(DESTINATIONS_ACTION,
        "Lists the accounts money can be transferred to from this one, as the transfer form's "
            + "destination list: id, name, currency, sameCurrency and, between two currencies, "
            + "today's conversionRate from this account's currency (null when the system has "
            + "none: then transferFunds needs one).")
        .withIdDescription(idDescription));
    contracts.put(TRANSFER_ACTION, NeoActionContract.write(TRANSFER_ACTION,
        "Transfers money from this account to another of the company's accounts, exactly as the "
            + "account's Transferir form does: a withdrawal is booked in this account and a "
            + "deposit in the destination (plus optional bank fees), both processed, dated today "
            + "(the form offers no other date). A transfer cannot be deleted afterwards — undo it "
            + "with a transfer back. Returns the transfer.",
        Param.required(P_DESTINATION, NeoActionContract.TYPE_STRING,
            "Id of the account the money arrives in (transferDestinations lists them)."),
        Param.required(P_AMOUNT, NeoActionContract.TYPE_NUMBER,
            "Amount leaving this account, above zero, in this account's currency."),
        Param.required(P_GL_ITEM_ID, NeoActionContract.TYPE_STRING,
            "Id of the G/L item (concept): the reason for the transfer, which decides the "
                + "account it is booked to. If the user did not say it, ask them instead of "
                + "guessing. movementGlItems finds it by part of its name or account code."),
        Param.optional(P_CONVERSION_RATE, NeoActionContract.TYPE_NUMBER,
            "Rate from this account's currency to the destination's, above zero. Only between "
                + "two currencies (ignored otherwise). Default: today's system rate "
                + "(transferDestinations shows it); without one it is required."),
        Param.optional(P_DESCRIPTION, NeoActionContract.TYPE_STRING,
            "Description of both movements, at most 255 characters. Default: 'Funds Transfer "
                + "Transaction'."),
        Param.optional(P_FEE_FROM, NeoActionContract.TYPE_NUMBER,
            "Bank fee charged by this account's bank, not negative. Default: none."),
        Param.optional(P_FEE_TO, NeoActionContract.TYPE_NUMBER,
            "Bank fee charged by the destination's bank, not negative. Default: none."))
        .withIdDescription(idDescription));
    return contracts;
  }

  /**
   * Serve one transfer action.
   *
   * @param context the ACTION context; {@code recordId} is the source account
   * @return the answer, or {@code null} when {@code context} is not a transfer action
   */
  NeoResponse handle(NeoContext context) {
    String action = context.getFieldName();
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType()) || !serves(action)) {
      return null;
    }
    if (TRANSFER_ACTION.equals(action) && !"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    try {
      OBContext.setAdminMode(true);
      FIN_FinancialAccount source =
          TenantOwnership.loadOwned(FIN_FinancialAccount.class, context.getRecordId());
      if (source == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Financial account not found");
      }
      return DESTINATIONS_ACTION.equals(action) ? destinations(source)
          : transfer(source, params, context.isMcpOrigin());
    } catch (Exception e) {
      log.error("Transfer action {} failed on account {}", action, context.getRecordId(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── reads ──────────────────────────────────────────────────────────────

  private NeoResponse destinations(FIN_FinancialAccount source) throws Exception {
    JSONArray items = new JSONArray();
    for (FIN_FinancialAccount candidate : candidates(source)) {
      if (!isDestination(source, candidate)) {
        continue;
      }
      boolean sameCurrency = sameCurrency(source, candidate);
      JSONObject item = new JSONObject();
      item.put("id", candidate.getId());
      item.put("name", candidate.getName());
      item.put(KEY_CURRENCY, isoOf(candidate));
      item.put("sameCurrency", sameCurrency);
      if (!sameCurrency) {
        Double rate = rates.rate(currencyId(source), currencyId(candidate), today.get(),
            orgIdOf(source));
        item.put(P_CONVERSION_RATE, rate != null ? rate : JSONObject.NULL);
      }
      items.put(item);
    }
    JSONObject data = new JSONObject();
    data.put("sourceAccountId", source.getId());
    data.put(KEY_CURRENCY, isoOf(source));
    data.put("items", items);
    return NeoResponse.ok(envelope(data));
  }

  /** The client's active accounts; who of them is a destination is {@link #isDestination}. */
  List<FIN_FinancialAccount> candidates(FIN_FinancialAccount source) {
    OBCriteria<FIN_FinancialAccount> criteria =
        OBDal.getInstance().createCriteria(FIN_FinancialAccount.class);
    criteria.add(Restrictions.eq(FIN_FinancialAccount.PROPERTY_CLIENT, source.getClient()));
    criteria.add(Restrictions.eq(FIN_FinancialAccount.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(FIN_FinancialAccount.PROPERTY_NAME, true);
    return criteria.list();
  }

  /**
   * The modal's destination list (active, not the source) restricted to what the transfer
   * itself accepts: an account the tenant can read, in the source's organization tree.
   */
  private boolean isDestination(FIN_FinancialAccount source, FIN_FinancialAccount candidate) {
    return candidate != null && !StringUtils.equals(candidate.getId(), source.getId())
        && !Boolean.FALSE.equals(candidate.isActive())
        && TenantOwnership.isVisibleToCurrentTenant(candidate)
        && inSourceTree(source, candidate);
  }

  /** The transfer's own organization rule ({@code sameOrgScope}). A seam for unit tests. */
  boolean inSourceTree(FIN_FinancialAccount source, FIN_FinancialAccount candidate) {
    return new FinancialAccountTransactionsHandler().sameOrgScope(source, candidate);
  }

  // ── write ──────────────────────────────────────────────────────────────

  private NeoResponse transfer(FIN_FinancialAccount source, JSONObject params, boolean mcp)
      throws Exception {
    String destinationId = StringUtils.trimToNull(params.optString(P_DESTINATION, null));
    FIN_FinancialAccount destination =
        TenantOwnership.loadOwned(FIN_FinancialAccount.class, destinationId);
    if (destination == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Destination account not found: " + destinationId);
    }
    if (StringUtils.equals(destination.getId(), source.getId())) {
      return unprocessable("The destination must be another account.", P_DESTINATION);
    }
    if (Boolean.FALSE.equals(destination.isActive())) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "The destination account is archived; money cannot be transferred to it.");
    }
    BigDecimal amount = number(params, P_AMOUNT);
    if (amount == null || amount.signum() <= 0) {
      return unprocessable("amount must be greater than zero.", P_AMOUNT);
    }
    String glItemId = StringUtils.trimToNull(params.optString(P_GL_ITEM_ID, null));
    if (glItemId == null) {
      return unprocessable("glItemId is required: a transfer is booked against a G/L item "
          + "(movementGlItems lists them).", P_GL_ITEM_ID);
    }
    if (TenantOwnership.loadOwned(GLItem.class, glItemId) == null) {
      return unprocessable(P_GL_ITEM_ID + " '" + glItemId + "' was not found.", P_GL_ITEM_ID);
    }
    String description = params.optString(P_DESCRIPTION, "");
    if (description.length() > FinancialAccountMovementActions.DESCRIPTION_MAX_LENGTH) {
      return unprocessable("description has " + description.length() + " characters; at most "
          + FinancialAccountMovementActions.DESCRIPTION_MAX_LENGTH + " are allowed.",
          P_DESCRIPTION);
    }
    BigDecimal feeFrom = number(params, P_FEE_FROM);
    BigDecimal feeTo = number(params, P_FEE_TO);
    for (Object[] fee : new Object[][] { { P_FEE_FROM, feeFrom }, { P_FEE_TO, feeTo } }) {
      BigDecimal value = (BigDecimal) fee[1];
      if (params.has((String) fee[0]) && (value == null || value.signum() < 0)) {
        return unprocessable(fee[0] + " must be zero or more.", (String) fee[0]);
      }
    }
    LocalDate date = today.get();
    BigDecimal rate = null;
    if (!sameCurrency(source, destination)) {
      rate = params.has(P_CONVERSION_RATE) ? number(params, P_CONVERSION_RATE) : systemRate(
          source, destination, date);
      if (rate == null || rate.signum() <= 0) {
        return unprocessable(params.has(P_CONVERSION_RATE)
            ? "conversionRate must be greater than zero."
            : "There is no conversion rate from " + isoOf(source) + " to "
                + isoOf(destination) + " for " + date + "; send conversionRate.",
            P_CONVERSION_RATE);
      }
    }

    // The body FundsTransferModal.handleConfirm sends.
    boolean withFee = signum(feeFrom) > 0 || signum(feeTo) > 0;
    JSONObject body = new JSONObject();
    body.put("sourceAccountId", source.getId());
    body.put(P_DESTINATION, destination.getId());
    body.put(P_AMOUNT, amount.toPlainString());
    body.put("transferDate", date.toString());
    body.put(P_DESCRIPTION, description);
    body.put("bankFee", withFee);
    body.put(P_GL_ITEM_ID, glItemId);
    if (rate != null) {
      body.put(P_CONVERSION_RATE, rate.toPlainString());
    }
    if (withFee) {
      body.put(P_FEE_FROM, orZero(feeFrom).toPlainString());
      body.put(P_FEE_TO, orZero(feeTo).toPlainString());
    }
    NeoResponse result = transactionsEndpoint.apply(NeoContext.builder()
        .specName(FinancialAccountTransactionsEndpoint.SPEC)
        .entityName(FinancialAccountTransactionsEndpoint.SPEC)
        .httpMethod("POST")
        .queryParams(Map.of("action", "transfer"))
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .mcpOrigin(mcp)
        .obContext(OBContext.getOBContext())
        .build());
    if (result == null || result.getHttpStatus() < 200 || result.getHttpStatus() >= 300) {
      return result;
    }
    JSONObject data = new JSONObject();
    data.put("transferred", true);
    data.put("sourceAccountId", source.getId());
    data.put(P_DESTINATION, destination.getId());
    data.put(P_AMOUNT, amount);
    data.put("date", date.toString());
    data.put(P_CONVERSION_RATE, rate != null ? rate : BigDecimal.ONE);
    data.put("amountReceived", received(destination, rate != null ? amount.multiply(rate) : amount));
    data.put("hint", "listMovements on either account shows the two movements (transferTxnId "
        + "links them). A transfer is not deleted: undo it with a transfer back.");
    return NeoResponse.createdWithData(data);
  }

  /**
   * What arrives, rounded to the destination currency's precision as the movement stores it: a
   * rate like 1/1.17 otherwise answered 10.0000017 for the 10.00 the database holds.
   */
  static BigDecimal received(FIN_FinancialAccount destination, BigDecimal raw) {
    Long precision = destination.getCurrency() == null ? null
        : destination.getCurrency().getStandardPrecision();
    return precision == null ? raw
        : raw.setScale(precision.intValue(), java.math.RoundingMode.HALF_UP);
  }

  private BigDecimal systemRate(FIN_FinancialAccount from, FIN_FinancialAccount to,
      LocalDate date) throws Exception {
    Double rate = rates.rate(currencyId(from), currencyId(to), date, orgIdOf(from));
    return rate == null ? null : BigDecimal.valueOf(rate);
  }

  private static String orgIdOf(FIN_FinancialAccount account) {
    return account.getOrganization() == null ? null : account.getOrganization().getId();
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private static boolean sameCurrency(FIN_FinancialAccount a, FIN_FinancialAccount b) {
    return StringUtils.equals(currencyId(a), currencyId(b));
  }

  private static String currencyId(FIN_FinancialAccount account) {
    return account.getCurrency() == null ? null : account.getCurrency().getId();
  }

  private static String isoOf(FIN_FinancialAccount account) {
    return account.getCurrency() == null ? null : account.getCurrency().getISOCode();
  }

  private static BigDecimal number(JSONObject params, String key) {
    Object raw = params.opt(key);
    if (raw == null || raw == JSONObject.NULL || StringUtils.isBlank(String.valueOf(raw))) {
      return null;
    }
    try {
      return new BigDecimal(String.valueOf(raw).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int signum(BigDecimal value) {
    return value == null ? 0 : value.signum();
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static JSONObject envelope(JSONObject data) throws JSONException {
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoResponse unprocessable(String message, String field) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("message", message);
    error.put("status", NeoActionContract.SC_UNPROCESSABLE);
    error.put("field", field);
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
        new JSONObject().put("error", error));
  }
}
