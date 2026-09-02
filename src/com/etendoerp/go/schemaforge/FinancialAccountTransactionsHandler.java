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

import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.buildPaymentLabel;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.optBigDecimal;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.parseLocalDate;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.resolveConversionRate;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.setOptionalRef;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.statusClassicLabel;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.trxTypeClassicLabel;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.actionHandler.FundsTransferActionHandler;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.project.Project;

import com.etendoerp.go.schemaforge.util.NeoDateFormat;

import com.etendoerp.payment.removal.util.TransactionRemovalUtil;

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
  private static final String ACTION_UPDATE = "update";
  private static final String ACTION_CREATE_PAYMENT = "create-payment";
  private static final String ACTION_TRANSFER = "transfer";
  private static final String ACTION_PROCESS = "process";
  private static final String ACTION_REACTIVATE = "reactivate";
  private static final String ACTION_DELETE = "delete";
  private static final String ACTION_BP_LOOKUP = "bpartner-lookup";
  private static final String ACTION_GL_LOOKUP = "glitem-lookup";
  private static final String ACTION_DIM_VALUES = "dimension-values";
  private static final String ACTION_OUTSTANDING = "outstanding-invoices";
  /** Default description applied to the funds-transfer transactions when none is given. */
  private static final String DEFAULT_TRANSFER_DESCRIPTION = "Funds Transfer Transaction";
  /** Reused messages / body-field keys (Sonar S1192 — each appears 3+ times). */
  private static final String MSG_TRANSACTION_NOT_FOUND = "Transaction not found";
  /**
   * Business rejection for {@code ?action=delete} on a funds-transfer leg (ETP-5085). Kept in
   * ENGLISH and byte-for-byte in sync with the frontend's {@code BACKEND_ERROR_MAP} key
   * {@code backendError.transferMovementNotDeletable} (lib/backendErrors.js), which is what
   * translates it — same convention as every other literal this module returns.
   */
  private static final String MSG_TRANSFER_NOT_DELETABLE =
      "Movements generated by a funds transfer cannot be deleted.";
  private static final String FIELD_PROCESS = "process";
  private static final String KEY_STATUS = "status";
  private static final String FIELD_GL_ITEM_ID = "glItemId";
  private static final String FIELD_BPARTNER_ID = "bpartnerId";
  private static final String FIELD_PROJECT_ID = "projectId";
  private static final String FIELD_COSTCENTER_ID = "costcenterId";
  private static final String FIELD_PRODUCT_ID = "productId";
  /** AD reference backing FIN_Finacc_Transaction.Trxtype (core list: BPD/BPW/BF). */
  private static final String TRXTYPE_REFERENCE_ID = "4EFC9773F30B4ACE97D225BD13CFF8CB";
  /** JSON keys reused across rows and totals — extracted to satisfy Sonar S1192. */
  private static final String KEY_BALANCE = "balance";
  private static final String KEY_RESPONSE = "response";
  private static final String FIELD_TRX_TYPE = "trxType";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_DEPOSIT_AMOUNT = "depositAmount";
  private static final String FIELD_AMOUNT = "amount";

  /**
   * Accounting-dimension UI keys, reused across marshalling, mapping and ordering. Aliases of
   * {@link AccountingDimensionsSupport}, which owns the canonical set and the element mapping.
   */
  static final String DIM_ORGANIZATION = AccountingDimensionsSupport.DIM_ORGANIZATION;
  static final String DIM_BPARTNER = AccountingDimensionsSupport.DIM_BPARTNER;
  static final String DIM_PROJECT = AccountingDimensionsSupport.DIM_PROJECT;
  static final String DIM_COSTCENTER = AccountingDimensionsSupport.DIM_COSTCENTER;
  static final String DIM_PRODUCT = AccountingDimensionsSupport.DIM_PRODUCT;
  static final String DIM_ACTIVITY = AccountingDimensionsSupport.DIM_ACTIVITY;
  static final String DIM_CAMPAIGN = AccountingDimensionsSupport.DIM_CAMPAIGN;
  static final String DIM_SALESREGION = AccountingDimensionsSupport.DIM_SALESREGION;
  static final String DIM_USER1 = AccountingDimensionsSupport.DIM_USER1;
  static final String DIM_USER2 = AccountingDimensionsSupport.DIM_USER2;

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
          + "       ft.depositamt AS deposit_amt,"
          + "       ft.paymentamt AS payment_amt,"
          + "       COALESCE(ft.description, fp.description, '') AS description,"
          + "       ft.posted,"
          + "       ft.processed AS processed_flag,"
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       ft.fin_payment_id AS payment_id,"
          + "       fp.isreceipt AS payment_isreceipt,"
          + "       ft.c_glitem_id AS gl_item_id,"
          + "       ft.c_bpartner_id AS bpartner_id,"
          + "       ft.c_project_id AS project_id,"
          + "       ft.c_costcenter_id AS costcenter_id,"
          + "       ft.m_product_id AS product_id,"
          + "       COALESCE(tbp.name, pbp.name, '') AS contact,"
          + "       COALESCE(gl.name, '') AS gl_item,"
          + "       COALESCE(dimorg.name, '')  AS dim_organization,"
          + "       COALESCE(dimbp.name, '')   AS dim_bpartner,"
          + "       COALESCE(dimproj.name, '') AS dim_project,"
          + "       COALESCE(dimcc.name, '')   AS dim_costcenter,"
          + "       COALESCE(dimprod.name, '') AS dim_product,"
          + "       COALESCE(dimact.name, '')  AS dim_activity,"
          + "       COALESCE(dimcamp.name, '') AS dim_campaign,"
          + "       COALESCE(dimsr.name, '')   AS dim_salesregion,"
          + "       COALESCE(dimu1.name, '')   AS dim_user1,"
          + "       COALESCE(dimu2.name, '')   AS dim_user2,"
          // Funds-transfer counterpart. Classic only links destination -> source
          // (em_aprm_finacc_trans_origin); em_etgo_finacc_trans_dest adds the mirror half, so
          // COALESCE resolves BOTH directions with a single join: the BPW source row resolves
          // through _dest, the BPD destination row through _origin.
          + "       COALESCE(ft.em_etgo_finacc_trans_dest, ft.em_aprm_finacc_trans_origin) AS transfer_txn_id,"
          + "       CASE WHEN ft.em_etgo_finacc_trans_dest IS NOT NULL THEN 'out' "
          + "            WHEN ft.em_aprm_finacc_trans_origin IS NOT NULL THEN 'in' END AS transfer_direction,"
          + "       COALESCE(tfa.fin_financial_account_id, '') AS transfer_account_id,"
          + "       COALESCE(tfa.name, '') AS transfer_account_name,"
          + "       cur.iso_code AS currency_iso,"
          + "       (fa.currentbalance"
          + "         - SUM(CASE WHEN ft.trxtype = 'BPD' THEN ft.depositamt ELSE -ft.paymentamt END)"
          // Ordered by the DAY, not the raw timestamp — see the ORDER BY at the end of this
          // query. The running balance must walk the rows in exactly the display order, or
          // the Saldo column stops matching the sequence it is shown against.
          + "             OVER (ORDER BY TO_CHAR(ft.statementdate, 'YYYY-MM-DD') ASC, ft.line ASC"
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
          + "  LEFT JOIN ad_org dimorg ON dimorg.ad_org_id = ft.ad_org_id"
          + "  LEFT JOIN c_bpartner dimbp ON dimbp.c_bpartner_id = ft.c_bpartner_id"
          + "  LEFT JOIN c_project dimproj ON dimproj.c_project_id = ft.c_project_id"
          + "  LEFT JOIN c_costcenter dimcc ON dimcc.c_costcenter_id = ft.c_costcenter_id"
          + "  LEFT JOIN m_product dimprod ON dimprod.m_product_id = ft.m_product_id"
          + "  LEFT JOIN c_activity dimact ON dimact.c_activity_id = ft.c_activity_id"
          + "  LEFT JOIN c_campaign dimcamp ON dimcamp.c_campaign_id = ft.c_campaign_id"
          + "  LEFT JOIN c_salesregion dimsr ON dimsr.c_salesregion_id = ft.c_salesregion_id"
          + "  LEFT JOIN c_elementvalue dimu1 ON dimu1.c_elementvalue_id = ft.user1_id"
          + "  LEFT JOIN c_elementvalue dimu2 ON dimu2.c_elementvalue_id = ft.user2_id"
          + "  LEFT JOIN fin_finacc_transaction tft"
          + "         ON tft.fin_finacc_transaction_id"
          + "            = COALESCE(ft.em_etgo_finacc_trans_dest, ft.em_aprm_finacc_trans_origin)"
          + "  LEFT JOIN fin_financial_account tfa"
          + "         ON tfa.fin_financial_account_id = tft.fin_financial_account_id"
          + " WHERE ft.fin_financial_account_id = ?"
          + "   AND ft.isactive = 'Y'"
          // Order by the CALENDAR DAY, never the raw timestamp: statementdate is declared
          // `Date` in the AD, so any time-of-day in it is noise, not a datum. Sorting on the
          // raw value let rows that happen to carry a wall-clock time (funds transfers before
          // ETP-5100 stamped Classic's now()) float above movements created LATER the same day
          // at 00:00 — the newest row was not on top. Truncating here fixes the rows already
          // stored that way too, with no data migration. TO_CHAR rather than a cast because
          // it truncates identically on PostgreSQL and Oracle (Oracle DATE keeps seconds), and
          // 'YYYY-MM-DD' sorts lexicographically the same as chronologically.
          + " ORDER BY TO_CHAR(ft.statementdate, 'YYYY-MM-DD') DESC, ft.line DESC";

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
      return handleGet(action, context);
    }
    if (METHOD_POST.equals(method)) {
      return handlePost(action, context);
    }
    return NeoResponse.error(405, "Method not allowed.");
  }

  /** Routes the read-only {@code GET} actions; defaults to the transactions list. */
  private NeoResponse handleGet(String action, NeoContext context) {
    if (ACTION_BP_LOOKUP.equals(action)) return FinancialAccountTransactionsLookups.bpartnerLookup(context);
    if (ACTION_GL_LOOKUP.equals(action)) return FinancialAccountTransactionsLookups.glItemLookup(context);
    if (ACTION_DIM_VALUES.equals(action)) return FinancialAccountTransactionsLookups.dimensionValues(context);
    if (ACTION_OUTSTANDING.equals(action)) return FinancialAccountTransactionsLookups.outstandingInvoices(context);
    return handleList(context);
  }

  /**
   * Routes the mutating {@code POST} actions. Posting the accounting (contabilizar/descontabilizar)
   * is NOT handled here — it goes through the financial-account spec's document-posting action.
   */
  private NeoResponse handlePost(String action, NeoContext context) {
    if (ACTION_CREATE.equals(action)) return handleCreate(context);
    if (ACTION_UPDATE.equals(action)) return handleUpdate(context);
    if (ACTION_CREATE_PAYMENT.equals(action)) return handleCreatePayment(context);
    if (ACTION_TRANSFER.equals(action)) return handleTransfer(context);
    if (ACTION_PROCESS.equals(action)) return handleProcess(context);
    if (ACTION_REACTIVATE.equals(action)) return handleReactivate(context);
    if (ACTION_DELETE.equals(action)) return handleDelete(context);
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
    data.put("enabledDimensions", loadEnabledDimensions(accountId));
    // Dimensions to show in the New Movement header — mirrors Classic's finacc
    // transaction form (ad_client_acctdimension, docbasetype FAT, show_in_header).
    data.put("headerDimensions", loadHeaderDimensions(accountId));
    // Transaction types (BPD/BPW/BF) from the AD reference list — not hardcoded.
    data.put("trxTypes", loadTrxTypes());
    // Payment methods configured for this financial account (FIN_Finacc_PaymentMethod).
    data.put("paymentMethods", loadPaymentMethods(accountId));
    // The account's organization — used by the New Movement wizard to default
    // the Organization dimension to the current context.
    data.put("accountOrgId", loadAccountOrgId(accountId));

    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject envelope = new JSONObject();
    envelope.put(KEY_RESPONSE, responseData);
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
          Timestamp dateTs = rs.getTimestamp("statementdate");
          String status = StringUtils.trimToEmpty(rs.getString(KEY_STATUS));
          String trxType = StringUtils.trimToEmpty(rs.getString("trxtype"));
          BigDecimal amount = nullSafeBigDecimal(rs.getBigDecimal(FIELD_AMOUNT));
          String documentNo = StringUtils.trimToEmpty(rs.getString("document_no"));
          String contact = StringUtils.trimToEmpty(rs.getString("contact"));
          row.put("id", rs.getString("fin_finacc_transaction_id"));
          row.put("date", formatDate(dateTs));
          row.put("paymentStatus", status);
          row.put(FIELD_TRX_TYPE, trxType);
          row.put(FIELD_AMOUNT, amount);
          row.put(KEY_BALANCE, nullSafeBigDecimal(rs.getBigDecimal(KEY_BALANCE)));
          row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(FIELD_DESCRIPTION)));
          row.put("posted", StringUtils.trimToEmpty(rs.getString("posted")));
          row.put("documentNo", documentNo);
          // Payment link: id + whether it's a received (IN) or made (OUT) payment,
          // so the UI can navigate to the payment-in / payment-out window.
          row.put("paymentId", StringUtils.trimToEmpty(rs.getString("payment_id")));
          row.put("paymentIsReceipt", StringUtils.trimToEmpty(rs.getString("payment_isreceipt")));
          row.put("contact", contact);
          row.put("glItem", StringUtils.trimToEmpty(rs.getString("gl_item")));
          // FK ids so the edit modal can prefill its {id,name} selectors.
          row.put(FIELD_GL_ITEM_ID, StringUtils.trimToEmpty(rs.getString("gl_item_id")));
          row.put(FIELD_BPARTNER_ID, StringUtils.trimToEmpty(rs.getString("bpartner_id")));
          row.put(FIELD_PROJECT_ID, StringUtils.trimToEmpty(rs.getString("project_id")));
          row.put(FIELD_COSTCENTER_ID, StringUtils.trimToEmpty(rs.getString("costcenter_id")));
          row.put(FIELD_PRODUCT_ID, StringUtils.trimToEmpty(rs.getString("product_id")));
          row.put("currencyIso", StringUtils.trimToEmpty(rs.getString("currency_iso")));
          // Funds-transfer counterpart link: id + account, so the UI can navigate to the paired
          // transaction in the other financial account. `transferDirection` is 'out' on the
          // source (BPW) leg and 'in' on the destination (BPD) leg, which is what picks the label.
          row.put("transferTxnId", StringUtils.trimToEmpty(rs.getString("transfer_txn_id")));
          row.put("transferDirection", StringUtils.trimToEmpty(rs.getString("transfer_direction")));
          row.put("transferAccountId", StringUtils.trimToEmpty(rs.getString("transfer_account_id")));
          row.put("transferAccountName", StringUtils.trimToEmpty(rs.getString("transfer_account_name")));
          // Pre-derived fields consumed by the generic CSV export (export=csv) so it
          // stays a dumb serializer: Classic-style type/status labels, the deposit
          // /withdrawal split (raw depositamt/paymentamt columns), the synthetic
          // "Payment" label, and the processed flag. Column order/labels live in the
          // Movements tab (MOVEMENT_CSV_COLUMNS in index.jsx).
          row.put("transactionTypeLabel", trxTypeClassicLabel(trxType));
          row.put(FIELD_DEPOSIT_AMOUNT, nullSafeBigDecimal(rs.getBigDecimal("deposit_amt")));
          row.put("withdrawalAmount", nullSafeBigDecimal(rs.getBigDecimal("payment_amt")));
          row.put("statusLabel", statusClassicLabel(status));
          // "processed" reflects the actual DB flag (NOT derived from the status code): a
          // reactivated transaction keeps status RPR/PPM but processed='N', i.e. it is a Draft
          // again. The UI drives the Borrador state and the Editar/Procesar row actions from this.
          row.put("processed", "Y".equals(StringUtils.trimToEmpty(rs.getString("processed_flag"))));
          row.put("paymentLabel", buildPaymentLabel(documentNo, dateTs, contact, amount));
          // Accounting dimensions for the expandable "more info" panel. All are
          // marshalled; the UI shows only the ones enabled in the chart of
          // accounts (see enabledDimensions in the payload).
          JSONObject dims = new JSONObject();
          dims.put(DIM_ORGANIZATION, StringUtils.trimToEmpty(rs.getString("dim_organization")));
          dims.put(DIM_BPARTNER, StringUtils.trimToEmpty(rs.getString("dim_bpartner")));
          dims.put(DIM_PROJECT, StringUtils.trimToEmpty(rs.getString("dim_project")));
          dims.put(DIM_COSTCENTER, StringUtils.trimToEmpty(rs.getString("dim_costcenter")));
          dims.put(DIM_PRODUCT, StringUtils.trimToEmpty(rs.getString("dim_product")));
          dims.put(DIM_ACTIVITY, StringUtils.trimToEmpty(rs.getString("dim_activity")));
          dims.put(DIM_CAMPAIGN, StringUtils.trimToEmpty(rs.getString("dim_campaign")));
          dims.put(DIM_SALESREGION, StringUtils.trimToEmpty(rs.getString("dim_salesregion")));
          dims.put(DIM_USER1, StringUtils.trimToEmpty(rs.getString("dim_user1")));
          dims.put(DIM_USER2, StringUtils.trimToEmpty(rs.getString("dim_user2")));
          row.put("dimensions", dims);
          arr.put(row);
        }
      }
    }
    return arr;
  }

  /**
   * Navigable accounting dimensions active in the client's chart of accounts (the "Ledger
   * Configuration" screen's per-dimension switches, {@code C_AcctSchema_Element.IsActive}) — the
   * single source of truth for both {@code enabledDimensions} (informational) and
   * {@code headerDimensions} (what the New/Edit Movement UI and the automatch rule engine may
   * actually set — see {@link #loadHeaderDimensions}). ETP-5101 QA direction: a
   * {@code FIN_Finacc_Transaction} must be governed by the exact same flat, per-tenant switch
   * every other GO window uses, not a document-type-scoped override — see
   * {@link AccountingDimensionsSupport}'s class javadoc for the fuller history.
   */
  Set<String> loadActiveDimensionSet(String accountId) throws Exception {
    return AccountingDimensionsSupport.flatActiveDimensionsForAccount(accountId);
  }

  JSONArray loadEnabledDimensions(String accountId) throws Exception {
    return AccountingDimensionsSupport.toOrderedArray(loadActiveDimensionSet(accountId));
  }

  /**
   * Dimensions the New/Edit Movement UI and the automatch rule engine may set on a
   * {@code FIN_Finacc_Transaction}. Kept as its own method/JSON key ({@code headerDimensions})
   * for wire-compatibility with the existing frontend contract, but — per
   * {@link #loadActiveDimensionSet} — it is now exactly {@link #loadEnabledDimensions}: no
   * separate, document-type-scoped source.
   */
  JSONArray loadHeaderDimensions(String accountId) throws Exception {
    return loadEnabledDimensions(accountId);
  }

  /** Active transaction types (BPD/BPW/BF) from the AD reference list, localized. */
  private static final String TRXTYPE_SQL =
      "SELECT l.value, COALESCE(t.name, l.name) AS label"
          + "  FROM ad_ref_list l"
          + "  LEFT JOIN ad_ref_list_trl t ON t.ad_ref_list_id = l.ad_ref_list_id AND t.ad_language = ?"
          + " WHERE l.ad_reference_id = ? AND l.isactive = 'Y'"
          + " ORDER BY l.value";

  JSONArray loadTrxTypes() throws Exception {
    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(TRXTYPE_SQL)) {
      ps.setString(1, lang);
      ps.setString(2, TRXTYPE_REFERENCE_ID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject o = new JSONObject();
          o.put("value", StringUtils.trimToEmpty(rs.getString("value")));
          o.put("label", StringUtils.trimToEmpty(rs.getString("label")));
          arr.put(o);
        }
      }
    }
    return arr;
  }

  /**
   * Payment methods configured for the financial account, with their allowed
   * directions (payin/payout). The UI uses this to filter by Cobro/Pago.
   */
  JSONArray loadPaymentMethods(String accountId) throws Exception {
    JSONArray arr = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT pm.fin_paymentmethod_id AS id, pm.name,"
            + "       fpm.payin_allow, fpm.payout_allow, fpm.isdefault"
            + "  FROM fin_finacc_paymentmethod fpm"
            + "  JOIN fin_paymentmethod pm ON pm.fin_paymentmethod_id = fpm.fin_paymentmethod_id"
            + " WHERE fpm.fin_financial_account_id = ?"
            + "   AND fpm.isactive = 'Y' AND pm.isactive = 'Y'"
            + " ORDER BY fpm.isdefault DESC, pm.name ASC")) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject o = new JSONObject();
          o.put("id", StringUtils.trimToEmpty(rs.getString("id")));
          o.put("name", StringUtils.trimToEmpty(rs.getString("name")));
          o.put("payinAllow", "Y".equals(rs.getString("payin_allow")));
          o.put("payoutAllow", "Y".equals(rs.getString("payout_allow")));
          o.put("isDefault", "Y".equals(rs.getString("isdefault")));
          arr.put(o);
        }
      }
    }
    return arr;
  }

  /** The organization that owns the financial account (movement default context). */
  String loadAccountOrgId(String accountId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ad_org_id FROM fin_financial_account WHERE fin_financial_account_id = ?")) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? StringUtils.trimToEmpty(rs.getString(1)) : "";
      }
    }
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

  /**
   * Canonical NEO wire datetime in the server's own zone. Formatting this through UTC is what
   * hid every movement created after 21:00 local under a negative offset — see
   * {@link NeoDateFormat#toWireDateTime} (ETP-5100).
   */
  private String formatDate(Timestamp ts) {
    String formatted = NeoDateFormat.toWireDateTime(ts);
    return formatted == null ? "" : formatted;
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
    return FinancialAccountTransactionsSupport.runMutation(body, ACTION_CREATE,
        "Could not create the movement. Please check logs for details.", () -> {
          NeoResponse validationError = validateCreateBody(body);
          if (validationError != null) return validationError;

          String accountId = body.optString(PARAM_ACCOUNT_ID, null);
          FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
          if (account == null) return NeoResponse.error(400, "Financial account not found: " + accountId);

          Currency currency = FinancialAccountTransactionsSupport.resolveCurrency(body, account.getCurrency());
          if (currency == null) return NeoResponse.error(400, "Currency not found");

          FIN_FinaccTransaction trx = buildTransaction(body, account, currency);
          OBDal.getInstance().save(trx);
          OBDal.getInstance().flush();

          // Confirmar in the modal creates and processes in one atomic call — moving the movement
          // from Borrador to Procesado — whereas Guardar leaves it as a Draft.
          if (body.optBoolean(FIELD_PROCESS, false)) {
            FIN_TransactionProcess.doTransactionProcess("P", trx);
            OBDal.getInstance().flush();
          }

          JSONObject result = new JSONObject();
          result.put("id", trx.getId());
          result.put(FIELD_TRX_TYPE, trx.getTransactionType());
          result.put(KEY_STATUS, trx.getStatus());
          return NeoResponse.createdWithData(result);
        });
  }

  /**
   * Handles {@code POST ?action=update} — edits an existing DRAFT transaction. Rejects processed
   * transactions (their dimensions are locked; the user must reactivate first). Optionally processes
   * it afterwards when {@code process:true} (edit + confirm in one call).
   */
  private NeoResponse handleUpdate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return FinancialAccountTransactionsSupport.runMutation(body, ACTION_UPDATE,
        "Could not update the movement. Please check logs for details.", () -> {
          FIN_FinaccTransaction trx = loadTransactionFromBody(body);
          if (trx == null) return NeoResponse.error(404, MSG_TRANSACTION_NOT_FOUND);
          // A posted (contabilizado) transaction is fully locked — the user must reactivate first.
          if ("Y".equals(trx.getPosted())) {
            return NeoResponse.error(400, "A posted transaction cannot be edited; reactivate it first.");
          }

          boolean processed = Boolean.TRUE.equals(trx.isProcessed());
          NeoResponse editError = applyUpdateEdits(trx, body, processed);
          if (editError != null) return editError;
          OBDal.getInstance().save(trx);
          OBDal.getInstance().flush();

          // Confirm (process) is only available while the transaction is still Draft.
          if (!processed && body.optBoolean(FIELD_PROCESS, false)) {
            FIN_TransactionProcess.doTransactionProcess("P", trx);
            OBDal.getInstance().flush();
          }
          return lifecycleOk(trx);
        });
  }

  /**
   * Applies the editable fields to an updated transaction according to its state, returning a 400
   * {@link NeoResponse} on a resolution failure or {@code null} on success. A Processed (not posted)
   * transaction only accepts the "safe" fields — G/L item, accounting dimensions, description and
   * dates (amount / direction / status stay locked, as they already impacted the balance); a Draft
   * accepts the full editable set (including currency and amounts).
   */
  private NeoResponse applyUpdateEdits(FIN_FinaccTransaction trx, JSONObject body, boolean processed) {
    if (processed) {
      applyEditableDimensions(trx, body);
      return null;
    }
    Currency currency = FinancialAccountTransactionsSupport.resolveCurrency(body, trx.getCurrency());
    if (currency == null) return NeoResponse.error(400, "Currency not found");
    applyEditableFields(trx, body, currency);
    return null;
  }

  /**
   * Handles {@code POST ?action=create-payment} — creates and processes a FIN_Payment
   * replicating Classic's "Add Payment" (the processing auto-creates the finacc transaction).
   * Delegates the business logic to {@link AddPaymentService}.
   */
  private NeoResponse handleCreatePayment(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return FinancialAccountTransactionsSupport.runMutation(body, "add payment",
        "Could not register the payment. Please check logs for details.", () -> AddPaymentService.doAddPayment(body));
  }

  /**
   * Loads the {@link FIN_FinaccTransaction} referenced by the request body's {@code id} field,
   * or {@code null} when the id is blank / unknown. Shared by the process, reactivate and delete
   * lifecycle actions.
   */
  private FIN_FinaccTransaction loadTransactionFromBody(JSONObject body) {
    String id = body.optString("id", null);
    return StringUtils.isBlank(id) ? null : OBDal.getInstance().get(FIN_FinaccTransaction.class, id);
  }

  /**
   * Success envelope shared by the lifecycle actions. Wraps the result in the standard
   * {@code {"response":{"data": ...}}} shape the front hooks read (see useCreateMovement.js).
   */
  private static NeoResponse lifecycleOk(FIN_FinaccTransaction trx) throws Exception {
    JSONObject result = new JSONObject();
    result.put("success", true);
    if (trx != null) {
      result.put("id", trx.getId());
      result.put(KEY_STATUS, trx.getStatus());
    }
    JSONObject responseData = new JSONObject();
    responseData.put("data", result);
    JSONObject envelope = new JSONObject();
    envelope.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(envelope);
  }

  /**
   * Handles {@code POST ?action=process} — confirms a Draft transaction (Borrador → Procesado)
   * by delegating to Etendo Classic's {@link FIN_TransactionProcess#doTransactionProcess} with the
   * {@code "P"} (process) action. Never reimplements the processing logic.
   */
  private NeoResponse handleProcess(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return FinancialAccountTransactionsSupport.runMutation(body, ACTION_PROCESS,
        "Could not process the movement. Please check logs for details.", () -> {
          FIN_FinaccTransaction trx = loadTransactionFromBody(body);
          if (trx == null) return NeoResponse.error(404, MSG_TRANSACTION_NOT_FOUND);
          FIN_TransactionProcess.doTransactionProcess("P", trx);
          OBDal.getInstance().flush();
          return lifecycleOk(trx);
        });
  }

  /**
   * Handles {@code POST ?action=reactivate} — reactivates a Processed transaction (Procesado →
   * Borrador), undoing posting and reconciliation in reverse order via the payment-removal module's
   * {@link TransactionRemovalUtil#reactivate}. Never reimplements that logic.
   *
   * <p>One cleanup {@link ReconciliationHandler} already performs for its own un-reconcile actions
   * is missing on this path: when the transaction was matched to a bank-statement line that Core
   * physically split for a 1:N match, {@code TransactionRemovalUtil.reactivate} only clears the
   * line's transaction link ({@code
   * ReconciliationRemovalUtil.removeTransactionFromReconciliation}), never re-collapsing its
   * ETGO-tagged split siblings — so the line stays fragmented into sub-amounts that no longer match
   * anything the bank actually sent. The statement line is captured BEFORE reactivating (the detach
   * clears the transaction→line pointer), then {@link ReconciliationHandler#normalizeReactivatedMatchGroup}
   * is reused — a plain instantiation, no CDI wiring needed, same as
   * {@link ReconciliationHandlerSupport}'s own composition with this handler.
   */
  private NeoResponse handleReactivate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return FinancialAccountTransactionsSupport.runMutation(body, ACTION_REACTIVATE,
        "Could not reactivate the movement. Please check logs for details.", () -> {
          FIN_FinaccTransaction trx = loadTransactionFromBody(body);
          if (trx == null) return NeoResponse.error(404, MSG_TRANSACTION_NOT_FOUND);
          FIN_BankStatementLine line = FinancialAccountTransactionsSupport.linkedBankStatementLine(trx);
          TransactionRemovalUtil.reactivate(trx);
          OBDal.getInstance().flush();
          if (line != null) {
            new ReconciliationHandler().normalizeReactivatedMatchGroup(line);
          }
          trx = OBDal.getInstance().get(FIN_FinaccTransaction.class, trx.getId());
          return lifecycleOk(trx);
        });
  }

  /**
   * Handles {@code POST ?action=delete} — deletes a transaction. A Draft (not processed) is removed
   * directly; a Processed transaction is reactivated and removed via the payment-removal module
   * ({@link TransactionRemovalUtil#reactivateAndRemove}), undoing posting/reconciliation first.
   *
   * <p>A leg of a funds transfer is rejected up-front with a 409 and a readable message
   * ({@link FinancialAccountTransactionsSupport#isTransferCounterpart}): its counterpart references
   * it through a RESTRICT self-FK, so the removal could only ever fail — and it failed as an opaque
   * HTTP 500, because the JDBC constraint violation raised at flush time is not an
   * {@code OBException} (ETP-5085). Deletion of a transfer is not allowed by design; the movements
   * kebab hides the action for those rows, and this guard is the server-side enforcement for the
   * bulk path, the REST API and MCP.
   */
  private NeoResponse handleDelete(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return FinancialAccountTransactionsSupport.runMutation(body, ACTION_DELETE,
        "Could not delete the movement. Please check logs for details.", () -> {
          FIN_FinaccTransaction trx = loadTransactionFromBody(body);
          if (trx == null) return NeoResponse.error(404, MSG_TRANSACTION_NOT_FOUND);
          // 409, mirroring FinancialAccountHandler.deleteAccount's own "cannot delete" rejection.
          if (FinancialAccountTransactionsSupport.isTransferCounterpart(trx)) {
            return NeoResponse.error(409, MSG_TRANSFER_NOT_DELETABLE);
          }
          if (Boolean.TRUE.equals(trx.isProcessed())) {
            TransactionRemovalUtil.reactivateAndRemove(trx.getId());
          } else {
            OBDal.getInstance().remove(trx);
            OBDal.getInstance().flush();
          }
          return lifecycleOk(null);
        });
  }

  /**
   * Handles {@code POST ?action=transfer} — transfers funds between two financial accounts of the
   * organization. Validates the request and delegates ALL the transaction creation to Etendo
   * Classic's {@link FundsTransferActionHandler#createTransfer}: this handler never reimplements the
   * paired-transaction / conversion-rate / processing logic.
   */
  private NeoResponse handleTransfer(NeoContext context) {
    JSONObject body = context.getRequestBody();
    return FinancialAccountTransactionsSupport.runMutation(body, ACTION_TRANSFER,
        "Could not transfer the funds. Please check logs for details.", () -> transfer(body));
  }

  /**
   * Validates inputs and runs the funds transfer. On confirm Classic creates two atomic
   * transactions — a withdrawal ({@code BPW}) in the source account and a deposit ({@code BPD}) in
   * the destination — plus optional bank-fee ({@code BF}) expenses on the source and/or destination;
   * all left Pending (PWNC / RDNC) until reconciled. Body:
   * {@code { sourceAccountId, destinationAccountId, amount, glItemId?, transferDate?, conversionRate?,
   * bankFee?, bankFeeFrom?, bankFeeTo?, description? }}.
   *
   * @return {@code null}-free {@link NeoResponse}: 400 on a validation failure, 404 on a missing
   *     account, or 201 with {@code transferred:true} on success.
   */
  NeoResponse transfer(JSONObject body) throws Exception {
    String sourceId = body.optString("sourceAccountId", null);
    String destId = body.optString("destinationAccountId", null);
    if (StringUtils.isBlank(sourceId) || StringUtils.isBlank(destId)) {
      return NeoResponse.error(400, "sourceAccountId and destinationAccountId are required");
    }
    if (sourceId.equals(destId)) {
      return NeoResponse.error(400, "Source and destination accounts must be different");
    }
    BigDecimal amount = nullSafeBigDecimal(optBigDecimal(body, FIELD_AMOUNT));
    if (amount.signum() <= 0) {
      return NeoResponse.error(400, "Amount must be greater than zero");
    }
    FIN_FinancialAccount source = loadAccount(sourceId);
    FIN_FinancialAccount dest = loadAccount(destId);
    if (source == null || dest == null) {
      return NeoResponse.error(404, "Source or destination account not found");
    }
    if (!sameOrgScope(source, dest)) {
      return NeoResponse.error(400,
          "Source and destination accounts must belong to the same organization tree");
    }
    // No balance guard on purpose: Etendo Classic never blocks a funds transfer on the source's
    // available balance (it allows overdrawing the account), so we match that behaviour here.

    GLItem glItem = null;
    String glItemId = body.optString(FIELD_GL_ITEM_ID, null);
    if (StringUtils.isNotBlank(glItemId)) {
      glItem = OBDal.getInstance().get(GLItem.class, glItemId);
    }
    BigDecimal conversionRate = resolveConversionRate(source, dest, optBigDecimal(body, "conversionRate"));
    // Bank fee mirrors Classic: an optional fee on the source bank AND on the destination bank.
    boolean withFee = body.optBoolean("bankFee", false);
    BigDecimal bankFeeFrom = withFee ? nullSafeBigDecimal(optBigDecimal(body, "bankFeeFrom")) : BigDecimal.ZERO;
    BigDecimal bankFeeTo = withFee ? nullSafeBigDecimal(optBigDecimal(body, "bankFeeTo")) : BigDecimal.ZERO;
    String description = body.optString(FIELD_DESCRIPTION, null);
    if (StringUtils.isBlank(description)) description = DEFAULT_TRANSFER_DESCRIPTION;
    Date transferDate = parseLocalDate(body.optString("transferDate", null), null);

    doTransfer(transferDate, source, dest, glItem, amount, conversionRate, bankFeeFrom,
        bankFeeTo, description);

    JSONObject data = new JSONObject();
    data.put("transferred", true);
    data.put("sourceAccountId", sourceId);
    data.put("destinationAccountId", destId);
    return NeoResponse.createdWithData(data);
  }

  // ── transfer seams (package-private so unit tests can stub the DAL / Classic layer) ──

  FIN_FinancialAccount loadAccount(String accountId) {
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
  }

  /** True when both accounts share a client and the destination org is in the source's natural tree. */
  boolean sameOrgScope(FIN_FinancialAccount source, FIN_FinancialAccount dest) {
    if (!source.getClient().getId().equals(dest.getClient().getId())) {
      return false;
    }
    return orgNaturalTree(source.getClient().getId(), source.getOrganization().getId())
        .contains(dest.getOrganization().getId());
  }

  Set<String> orgNaturalTree(String clientId, String orgId) {
    return OBContext.getOBContext().getOrganizationStructureProvider(clientId).getNaturalTree(orgId);
  }

  /** Delegates to Etendo Classic's funds-transfer flow (9-arg overload). Package-private test seam. */
  void doTransfer(Date date, FIN_FinancialAccount from, FIN_FinancialAccount to, GLItem glItem,
      BigDecimal amount, BigDecimal conversionRate, BigDecimal bankFeeFrom, BigDecimal bankFeeTo,
      String description) {
    FundsTransferActionHandler.createTransfer(date, from, to, glItem, amount, conversionRate,
        bankFeeFrom, bankFeeTo, description);
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
    BigDecimal deposit = nullSafeBigDecimal(optBigDecimal(body, FIELD_DEPOSIT_AMOUNT));
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
    FIN_FinaccTransaction trx = OBProvider.getInstance().get(FIN_FinaccTransaction.class);
    trx.setClient(account.getClient());
    trx.setOrganization(account.getOrganization());
    trx.setActive(true);
    trx.setAccount(account);
    trx.setLineNo(nextLineNo(account));
    applyEditableFields(trx, body, currency);
    return trx;
  }

  /**
   * Applies the user-editable fields (type, dates, amounts, description, G/L item, business
   * partner and accounting dimensions) to a Draft transaction. Shared by create and update.
   * For BPD/BPW only one amount column is editable in Classic; status follows the convention
   * (any deposit → {@code RPAE}, otherwise → {@code RPAP}). References use {@link
   * FinancialAccountTransactionsSupport#setOptionalRef} so an edit can also clear them.
   */
  private void applyEditableFields(FIN_FinaccTransaction trx, JSONObject body, Currency currency) {
    String trxType = body.optString(FIELD_TRX_TYPE, trx.getTransactionType());
    BigDecimal depositAmount = nullSafeBigDecimal(optBigDecimal(body, FIELD_DEPOSIT_AMOUNT));
    BigDecimal paymentAmount = nullSafeBigDecimal(optBigDecimal(body, "paymentAmount"));
    // Date-only fields: parse to LOCAL start-of-day so the stored calendar day is not shifted
    // back by the JDBC driver in negative-offset timezones (see parseLocalDate).
    Date fallbackDate = trx.getTransactionDate() != null ? trx.getTransactionDate() : new Date();
    Date transactionDate = parseLocalDate(body.optString("transactionDate", null), fallbackDate);
    Date accountingDate = parseLocalDate(body.optString("accountingDate", null), transactionDate);

    trx.setCurrency(currency);
    trx.setTransactionType(trxType);
    trx.setTransactionDate(transactionDate);
    trx.setDateAcct(accountingDate);
    trx.setDescription(body.optString(FIELD_DESCRIPTION, ""));
    trx.setDepositAmount(depositAmount);
    trx.setPaymentAmount(paymentAmount);
    trx.setStatus(depositAmount.signum() > 0 ? "RPAE" : "RPAP");
    trx.setProcessed(false);

    setOptionalRef(body, FIELD_BPARTNER_ID, BusinessPartner.class, trx::setBusinessPartner);
    setOptionalRef(body, FIELD_GL_ITEM_ID, GLItem.class, trx::setGLItem);
    // Accounting dimensions — only the ones enabled in the chart of accounts are ever sent by
    // the UI (see headerDimensions in the list payload).
    setOptionalRef(body, FIELD_PROJECT_ID, Project.class, trx::setProject);
    setOptionalRef(body, FIELD_COSTCENTER_ID, Costcenter.class, trx::setCostCenter);
    setOptionalRef(body, FIELD_PRODUCT_ID, Product.class, trx::setProduct);
  }

  /**
   * Applies only the fields that stay editable once a transaction is Processed (but not yet
   * posted): description, dates, G/L item and accounting dimensions. Amount, direction
   * (deposit/withdrawal), currency and status are intentionally left untouched — they are locked
   * because they already impacted the account balance.
   */
  private void applyEditableDimensions(FIN_FinaccTransaction trx, JSONObject body) {
    trx.setDescription(body.optString(FIELD_DESCRIPTION, trx.getDescription()));
    trx.setTransactionDate(parseLocalDate(body.optString("transactionDate", null), trx.getTransactionDate()));
    trx.setDateAcct(parseLocalDate(body.optString("accountingDate", null), trx.getDateAcct()));
    setOptionalRef(body, FIELD_BPARTNER_ID, BusinessPartner.class, trx::setBusinessPartner);
    setOptionalRef(body, FIELD_GL_ITEM_ID, GLItem.class, trx::setGLItem);
    setOptionalRef(body, FIELD_PROJECT_ID, Project.class, trx::setProject);
    setOptionalRef(body, FIELD_COSTCENTER_ID, Costcenter.class, trx::setCostCenter);
    setOptionalRef(body, FIELD_PRODUCT_ID, Product.class, trx::setProduct);
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

}
