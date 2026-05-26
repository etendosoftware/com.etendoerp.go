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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

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
@ApplicationScoped
@Named("financial-account-transactions")
public class FinancialAccountTransactionsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountTransactionsHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String PARAM_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
      .withZone(ZoneOffset.UTC);

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
          + " WHERE ft.fin_financial_account_id = ?"
          + "   AND ft.isactive = 'Y'"
          + " ORDER BY ft.statementdate DESC, ft.line DESC";

  private static final String TOTALS_SQL =
      "SELECT fa.currentbalance,"
          + "       cur.iso_code,"
          + "       COALESCE(SUM(CASE WHEN ft.trxtype = 'BPD'"
          + "                          AND ft.statementdate >= NOW() - INTERVAL '30 days'"
          + "                         THEN ft.depositamt ELSE 0 END), 0) AS inflows_30d,"
          + "       COALESCE(SUM(CASE WHEN ft.trxtype = 'BPW'"
          + "                          AND ft.statementdate >= NOW() - INTERVAL '30 days'"
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
    if (!METHOD_GET.equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed. Use GET.");
    }

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
          row.put("trxType", StringUtils.trimToEmpty(rs.getString("trxtype")));
          row.put("amount", nullSafeBigDecimal(rs.getBigDecimal("amount")));
          row.put("balance", nullSafeBigDecimal(rs.getBigDecimal("balance")));
          row.put("description", StringUtils.trimToEmpty(rs.getString("description")));
          row.put("posted", StringUtils.trimToEmpty(rs.getString("posted")));
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put("contact", StringUtils.trimToEmpty(rs.getString("contact")));
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
    try (PreparedStatement ps = conn.prepareStatement(TOTALS_SQL)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          totals.put("balance", nullSafeBigDecimal(rs.getBigDecimal("currentbalance")));
          totals.put("currency", StringUtils.trimToEmpty(rs.getString("iso_code")));
          totals.put("inflows", nullSafeBigDecimal(rs.getBigDecimal("inflows_30d")));
          totals.put("outflows", nullSafeBigDecimal(rs.getBigDecimal("outflows_30d")));
        } else {
          totals.put("balance", BigDecimal.ZERO);
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
}
