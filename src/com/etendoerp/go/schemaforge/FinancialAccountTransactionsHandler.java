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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * NeoHandler that powers the financial account transactions list introduced by ETP-4098.
 * Returns the movements (FIN_Finacc_Transaction) for a single financial account
 * together with totals for the AccountSummaryStrip.
 *
 * <p>URL: {@code GET /sws/neo/financial-account-transactions?FIN_Financial_Account_ID={id}}
 *
 * <p>Response shape:
 * <pre>
 * {
 *   "response": {
 *     "data": {
 *       "transactions": [
 *         {
 *           "id": "...",
 *           "date": "2026-05-06T00:00:00Z",
 *           "documentNo": "PAY-001",
 *           "contact": "DHL Technologies SL",
 *           "description": "Invoice No.: ...",
 *           "paymentStatus": "RPPC",
 *           "trxType": "BPD",
 *           "amount": 12450.00,
 *           "balance": 211841.01,
 *           "currencyIso": "EUR",
 *           "posted": "Y"
 *         }
 *       ],
 *       "totals": {
 *         "balance": 211841.01,
 *         "inflows": 47820.00,
 *         "outflows": 22398.82,
 *         "currency": "EUR"
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>trxType values: {@code BPD} (Bank Payment Deposit = incoming, amount > 0),
 * {@code BPW} (Bank Payment Withdrawal = outgoing, amount < 0).
 *
 * <p>The {@code balance} column is the running account balance after each transaction,
 * computed as {@code currentBalance - SUM(subsequent transactions)}.
 */
@Named("financial-account-transactions")
public class FinancialAccountTransactionsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountTransactionsHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String PARAM_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final String PARAM_ACTION = "action";
  private static final String ACTION_CREATE = "create";
  private static final String ACTION_BP_LOOKUP = "bpartner-lookup";
  private static final String ACTION_GL_LOOKUP = "glitem-lookup";
  private static final int LOOKUP_LIMIT = 25;
  private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
      .withZone(ZoneOffset.UTC);

  /** JSON keys reused across rows and totals — extracted to satisfy Sonar S1192. */
  private static final String KEY_BALANCE = "balance";
  private static final String FIELD_TRX_TYPE = "trxType";
  private static final String FIELD_DESCRIPTION = "description";

  /** Rolling window for inflow/outflow KPIs, in days. */
  private static final int KPI_WINDOW_DAYS = 30;

  /**
   * Returns transactions (newest first) plus KPI totals for the requested account.
   * The running {@code balance} per row is anchored to {@code FIN_Financial_Account.currentbalance}.
   */
  private static final String TRANSACTIONS_SQL =
      "SELECT ft.fin_finacc_transaction_id,"
          + "       ft.statementdate,"
          + "       ft.status,"
          + "       ft.trxtype,"
          + "       CASE WHEN ft.trxtype = 'BPD' THEN ft.depositamt ELSE -ft.paymentamt END AS amount,"
          + "       COALESCE(ft.description, fp.description, '') AS description,"
          + "       ft.posted,"
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       COALESCE(tbp.name, pbp.name, '') AS contact,"
          + "       COALESCE(gl.name, '') AS gl_item,"
          + "       cur.iso_code AS currency_iso,"
          + "       (fa.currentbalance"
          + "         - SUM(CASE WHEN ft.trxtype = 'BPD' THEN ft.depositamt ELSE -ft.paymentamt END)"
          + "             OVER (ORDER BY ft.statementdate ASC, ft.line ASC"
          + "                   ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)"
          + "         + (CASE WHEN ft.trxtype = 'BPD' THEN ft.depositamt ELSE -ft.paymentamt END)"
          + "       ) AS balance"
          + "  FROM fin_finacc_transaction ft"
          + "  JOIN fin_financial_account fa ON fa.fin_financial_account_id = ft.fin_financial_account_id"
          + "  JOIN c_currency cur ON cur.c_currency_id = ft.c_currency_id"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner tbp ON tbp.c_bpartner_id = ft.c_bpartner_id"
          + "  LEFT JOIN c_bpartner pbp ON pbp.c_bpartner_id = fp.c_bpartner_id"
          + "  LEFT JOIN c_glitem gl ON gl.c_glitem_id = ft.c_glitem_id"
          + " WHERE ft.fin_financial_account_id = ?"
          + "   AND ft.isactive = 'Y'"
          + " ORDER BY ft.statementdate DESC, ft.line DESC";

  // The cutoff timestamp (NOW - KPI_WINDOW_DAYS) is computed in Java and bound
  // twice as the first two parameters so the query stays portable across
  // PostgreSQL and Oracle (no NOW()/INTERVAL vendor syntax).
  private static final String TOTALS_SQL =
      "SELECT fa.currentbalance,"
          + "       cur.iso_code,"
          + "       COALESCE(SUM(CASE WHEN ft.trxtype = 'BPD'"
          + "                          AND ft.statementdate >= ?"
          + "                         THEN ft.depositamt ELSE 0 END), 0) AS inflows_30d,"
          + "       COALESCE(SUM(CASE WHEN ft.trxtype = 'BPW'"
          + "                          AND ft.statementdate >= ?"
          + "                         THEN ft.paymentamt ELSE 0 END), 0) AS outflows_30d"
          + "  FROM fin_financial_account fa"
          + "  JOIN c_currency cur ON cur.c_currency_id = fa.c_currency_id"
          + "  LEFT JOIN fin_finacc_transaction ft"
          + "         ON ft.fin_financial_account_id = fa.fin_financial_account_id"
          + "        AND ft.isactive = 'Y'"
          + " WHERE fa.fin_financial_account_id = ?"
          + " GROUP BY fa.currentbalance, cur.iso_code";

  @Override
  public NeoResponse handle(NeoContext context) {
    String method = context.getHttpMethod();
    String action = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_ACTION)
        : null;

    if (METHOD_GET.equals(method)) {
      if (ACTION_BP_LOOKUP.equals(action)) return handleBpartnerLookup(context);
      if (ACTION_GL_LOOKUP.equals(action)) return handleGlItemLookup(context);
      return handleList(context);
    }
    if (METHOD_POST.equals(method) && ACTION_CREATE.equals(action)) {
      return handleCreate(context);
    }
    return NeoResponse.error(405, "Method not allowed.");
  }

  private NeoResponse handleList(NeoContext context) {
    String accountId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_ACCOUNT_ID)
        : null;
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(400, "Missing required parameter: " + PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      return buildPayload(accountId);
    } catch (Exception e) {
      log.error("Error building financial-account-transactions payload for account {}", accountId, e);
      return NeoResponse.error(500, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildPayload(String accountId) throws Exception {
    JSONArray transactions = loadTransactions(accountId);
    JSONObject totals = loadTotals(accountId);

    JSONObject data = new JSONObject();
    data.put("transactions", transactions);
    data.put("totals", totals);

    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject envelope = new JSONObject();
    envelope.put("response", responseData);
    return NeoResponse.ok(envelope);
  }

  JSONArray loadTransactions(String accountId) throws Exception {
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(TRANSACTIONS_SQL)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject row = new JSONObject();
          row.put("id", rs.getString("fin_finacc_transaction_id"));
          row.put("date", formatDate(rs.getTimestamp("statementdate")));
          row.put("paymentStatus", StringUtils.trimToEmpty(rs.getString("status")));
          row.put(FIELD_TRX_TYPE, StringUtils.trimToEmpty(rs.getString("trxtype")));
          row.put("amount", nullSafeBigDecimal(rs.getBigDecimal("amount")));
          row.put(KEY_BALANCE, nullSafeBigDecimal(rs.getBigDecimal(KEY_BALANCE)));
          row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(FIELD_DESCRIPTION)));
          row.put("posted", StringUtils.trimToEmpty(rs.getString("posted")));
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put("contact", StringUtils.trimToEmpty(rs.getString("contact")));
          row.put("glItem", StringUtils.trimToEmpty(rs.getString("gl_item")));
          row.put("currencyIso", StringUtils.trimToEmpty(rs.getString("currency_iso")));
          arr.put(row);
        }
      }
    }
    return arr;
  }

  JSONObject loadTotals(String accountId) throws Exception {
    JSONObject totals = new JSONObject();
    Connection conn = OBDal.getInstance().getConnection();
    Timestamp cutoff = Timestamp.from(Instant.now().minus(KPI_WINDOW_DAYS, ChronoUnit.DAYS));
    try (PreparedStatement ps = conn.prepareStatement(TOTALS_SQL)) {
      ps.setTimestamp(1, cutoff);
      ps.setTimestamp(2, cutoff);
      ps.setString(3, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          totals.put(KEY_BALANCE, nullSafeBigDecimal(rs.getBigDecimal("currentbalance")));
          totals.put("currency", StringUtils.trimToEmpty(rs.getString("iso_code")));
          totals.put("inflows", nullSafeBigDecimal(rs.getBigDecimal("inflows_30d")));
          totals.put("outflows", nullSafeBigDecimal(rs.getBigDecimal("outflows_30d")));
        } else {
          totals.put(KEY_BALANCE, BigDecimal.ZERO);
          totals.put("currency", "EUR");
          totals.put("inflows", BigDecimal.ZERO);
          totals.put("outflows", BigDecimal.ZERO);
        }
      }
    }
    return totals;
  }

  private String formatDate(Timestamp ts) {
    if (ts == null) return "";
    return ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  static BigDecimal nullSafeBigDecimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Handles {@code POST ?action=create} — inserts a new {@code FIN_FinaccTransaction}
   * row for the requested account. Validation is delegated to
   * {@link #validateCreateBody(JSONObject)}; the actual entity assembly to
   * {@link #buildTransaction(JSONObject, FIN_FinancialAccount, Currency)}.
   */
  private NeoResponse handleCreate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, "Request body is required");
    try {
      OBContext.setAdminMode(true);

      NeoResponse validationError = validateCreateBody(body);
      if (validationError != null) return validationError;

      String accountId = body.optString(PARAM_ACCOUNT_ID, null);
      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
      if (account == null) return NeoResponse.error(400, "Financial account not found: " + accountId);

      String currencyId = body.optString("currencyId", null);
      Currency currency = StringUtils.isBlank(currencyId)
          ? account.getCurrency()
          : OBDal.getInstance().get(Currency.class, currencyId);
      if (currency == null) return NeoResponse.error(400, "Currency not found: " + currencyId);

      FIN_FinaccTransaction trx = buildTransaction(body, account, currency);
      OBDal.getInstance().save(trx);
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", trx.getId());
      result.put(FIELD_TRX_TYPE, trx.getTransactionType());
      result.put("status", trx.getStatus());
      return NeoResponse.createdWithData(result);

    } catch (Exception e) {
      log.error("Error creating financial account transaction", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Create failed: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Validates the body of {@code POST ?action=create}. Returns {@code null}
   * when everything is fine, or a {@link NeoResponse} carrying the appropriate
   * 400 response when not. Extracted so {@link #handleCreate} stays under
   * Sonar's cognitive-complexity threshold.
   */
  private static NeoResponse validateCreateBody(JSONObject body) {
    if (StringUtils.isBlank(body.optString(PARAM_ACCOUNT_ID, null))) {
      return NeoResponse.error(400, "Missing FIN_Financial_Account_ID");
    }
    String trxType = body.optString(FIELD_TRX_TYPE, null);
    if (!"BPD".equals(trxType) && !"BPW".equals(trxType) && !"BF".equals(trxType)) {
      return NeoResponse.error(400, "Invalid trxType. Must be 'BPD' (deposit), 'BPW' (withdrawal) or 'BF' (bank fee).");
    }
    BigDecimal deposit = nullSafeBigDecimal(optBigDecimal(body, "depositAmount"));
    BigDecimal payment = nullSafeBigDecimal(optBigDecimal(body, "paymentAmount"));
    if (deposit.signum() < 0 || payment.signum() < 0) {
      return NeoResponse.error(400, "Amounts must be non-negative");
    }
    if (deposit.signum() == 0 && payment.signum() == 0) {
      return NeoResponse.error(400, "At least one amount must be > 0");
    }
    return null;
  }

  /**
   * Maps a validated request body to a fresh {@link FIN_FinaccTransaction}.
   * Optional FK references (business partner, G/L item) are looked up only
   * when their id is non-blank.
   */
  private FIN_FinaccTransaction buildTransaction(JSONObject body,
                                                 FIN_FinancialAccount account,
                                                 Currency currency) {
    String trxType = body.optString(FIELD_TRX_TYPE, null);
    String description = body.optString(FIELD_DESCRIPTION, "");
    BigDecimal depositAmount = nullSafeBigDecimal(optBigDecimal(body, "depositAmount"));
    BigDecimal paymentAmount = nullSafeBigDecimal(optBigDecimal(body, "paymentAmount"));
    Date transactionDate = parseDate(body.optString("transactionDate", null), new Date());
    Date accountingDate = parseDate(body.optString("accountingDate", null), transactionDate);

    FIN_FinaccTransaction trx = OBProvider.getInstance().get(FIN_FinaccTransaction.class);
    trx.setClient(account.getClient());
    trx.setOrganization(account.getOrganization());
    trx.setActive(true);
    trx.setAccount(account);
    trx.setCurrency(currency);
    trx.setTransactionType(trxType);
    trx.setTransactionDate(transactionDate);
    trx.setDateAcct(accountingDate);
    trx.setDescription(description);
    trx.setLineNo(nextLineNo(account));

    // For BPD/BPW only one column is editable in Classic; for BF both are.
    // Status follows the convention: any deposit → RPAE, otherwise → RPAP.
    trx.setDepositAmount(depositAmount);
    trx.setPaymentAmount(paymentAmount);
    trx.setStatus(depositAmount.signum() > 0 ? "RPAE" : "RPAP");

    attachOptional(body.optString("bpartnerId", null), BusinessPartner.class, trx::setBusinessPartner);
    attachOptional(body.optString("glItemId", null), GLItem.class, trx::setGLItem);
    return trx;
  }

  private static <T extends org.openbravo.base.structure.BaseOBObject> void attachOptional(
      String id, Class<T> entityClass, java.util.function.Consumer<T> setter) {
    if (StringUtils.isBlank(id)) return;
    T ref = OBDal.getInstance().get(entityClass, id);
    if (ref != null) setter.accept(ref);
  }

  private static BigDecimal optBigDecimal(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) return null;
    try {
      return new BigDecimal(body.getString(key));
    } catch (Exception e) {
      try {
        return BigDecimal.valueOf(body.getDouble(key));
      } catch (Exception ex) {
        return null;
      }
    }
  }

  private static Date parseDate(String iso, Date fallback) {
    if (StringUtils.isBlank(iso)) return fallback;
    try {
      return Date.from(Instant.parse(iso));
    } catch (Exception e) {
      return fallback;
    }
  }

  long nextLineNo(FIN_FinancialAccount account) {
    String sql = "SELECT COALESCE(MAX(line), 0) + 10 AS next_line"
        + "  FROM fin_finacc_transaction"
        + " WHERE fin_financial_account_id = ?";
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, account.getId());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong("next_line");
      }
    } catch (Exception e) {
      log.warn("Failed to compute next line for account {}, defaulting to 10", account.getId(), e);
    }
    return 10L;
  }

  /**
   * Handles {@code GET ?action=bpartner-lookup&q=...} — fuzzy search over
   * {@code c_bpartner.name}, scoped to the current client + system records.
   */
  private NeoResponse handleBpartnerLookup(NeoContext context) {
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    return runLookup(
        "SELECT c_bpartner_id AS id, name FROM c_bpartner"
            + " WHERE isactive='Y' AND ad_client_id IN (?, ?)"
            + "   AND LOWER(name) LIKE ?"
            + " ORDER BY name ASC"
            + " LIMIT " + LOOKUP_LIMIT,
        q, "bpartners");
  }

  private NeoResponse handleGlItemLookup(NeoContext context) {
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    return runLookup(
        "SELECT c_glitem_id AS id, name FROM c_glitem"
            + " WHERE isactive='Y' AND ad_client_id IN (?, ?)"
            + "   AND LOWER(name) LIKE ?"
            + " ORDER BY name ASC"
            + " LIMIT " + LOOKUP_LIMIT,
        q, "glItems");
  }

  private NeoResponse runLookup(String sql, String q, String resultKey) {
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String pattern = "%" + (q == null ? "" : q.toLowerCase()) + "%";
      JSONArray arr = new JSONArray();
      try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
        ps.setString(1, "0");
        ps.setString(2, clientId);
        ps.setString(3, pattern);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = new JSONObject();
            row.put("id", rs.getString("id"));
            row.put("name", StringUtils.trimToEmpty(rs.getString("name")));
            arr.put(row);
          }
        }
      }
      JSONObject data = new JSONObject();
      data.put(resultKey, arr);
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject envelope = new JSONObject();
      envelope.put("response", responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Lookup failed for query '{}'", q, e);
      return NeoResponse.error(500, "Lookup failed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
