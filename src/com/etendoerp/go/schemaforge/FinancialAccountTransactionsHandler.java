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

import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.attachOptional;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.bpartnerRoleFilter;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.buildPaymentLabel;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.daysUntil;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.formatDmy;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.optBigDecimal;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.parseDate;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.resolveConversionRate;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.statusClassicLabel;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.trxTypeClassicLabel;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.actionHandler.FundsTransferActionHandler;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
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
  private static final String ACTION_CREATE_PAYMENT = "create-payment";
  private static final String ACTION_TRANSFER = "transfer";
  private static final String ACTION_BP_LOOKUP = "bpartner-lookup";
  private static final String ACTION_GL_LOOKUP = "glitem-lookup";
  private static final String ACTION_DIM_VALUES = "dimension-values";
  private static final String ACTION_OUTSTANDING = "outstanding-invoices";
  /** Default description applied to the funds-transfer transactions when none is given. */
  private static final String DEFAULT_TRANSFER_DESCRIPTION = "Funds Transfer Transaction";
  /** Reused error message (Sonar S1192 — appears across create / create-payment / transfer). */
  private static final String MSG_BODY_REQUIRED = "Request body is required";
  private static final int LOOKUP_LIMIT = 25;
  /** Document base type of finacc transactions — used to resolve header dimensions. */
  private static final String DOCBASETYPE_FAT = "FAT";
  /** AD reference backing FIN_Finacc_Transaction.Trxtype (core list: BPD/BPW/BF). */
  private static final String TRXTYPE_REFERENCE_ID = "4EFC9773F30B4ACE97D225BD13CFF8CB";
  private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
      .withZone(ZoneOffset.UTC);

  /** JSON keys reused across rows and totals — extracted to satisfy Sonar S1192. */
  private static final String KEY_BALANCE = "balance";
  private static final String KEY_RESPONSE = "response";
  private static final String FIELD_TRX_TYPE = "trxType";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_DEPOSIT_AMOUNT = "depositAmount";
  private static final String FIELD_AMOUNT = "amount";

  /** Accounting-dimension UI keys, reused across marshalling, mapping and ordering. */
  private static final String DIM_ORGANIZATION = "organization";
  private static final String DIM_BPARTNER = "bpartner";
  private static final String DIM_PROJECT = "project";
  private static final String DIM_COSTCENTER = "costcenter";
  private static final String DIM_PRODUCT = "product";
  private static final String DIM_ACTIVITY = "activity";
  private static final String DIM_CAMPAIGN = "campaign";
  private static final String DIM_SALESREGION = "salesregion";
  private static final String DIM_USER1 = "user1";
  private static final String DIM_USER2 = "user2";

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
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       ft.fin_payment_id AS payment_id,"
          + "       fp.isreceipt AS payment_isreceipt,"
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
      return handleGet(action, context);
    }
    if (METHOD_POST.equals(method)) {
      return handlePost(action, context);
    }
    return NeoResponse.error(405, "Method not allowed.");
  }

  /** Routes the read-only {@code GET} actions; defaults to the transactions list. */
  private NeoResponse handleGet(String action, NeoContext context) {
    if (ACTION_BP_LOOKUP.equals(action)) return handleBpartnerLookup(context);
    if (ACTION_GL_LOOKUP.equals(action)) return handleGlItemLookup(context);
    if (ACTION_DIM_VALUES.equals(action)) return handleDimensionValues(context);
    if (ACTION_OUTSTANDING.equals(action)) return handleOutstandingInvoices(context);
    return handleList(context);
  }

  /** Routes the mutating {@code POST} actions. */
  private NeoResponse handlePost(String action, NeoContext context) {
    if (ACTION_CREATE.equals(action)) return handleCreate(context);
    if (ACTION_CREATE_PAYMENT.equals(action)) return handleCreatePayment(context);
    if (ACTION_TRANSFER.equals(action)) return handleTransfer(context);
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
          String status = StringUtils.trimToEmpty(rs.getString("status"));
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
          row.put("currencyIso", StringUtils.trimToEmpty(rs.getString("currency_iso")));
          // Pre-derived fields consumed by the generic CSV export (export=csv) so it
          // stays a dumb serializer: Classic-style type/status labels, the deposit
          // /withdrawal split (raw depositamt/paymentamt columns), the synthetic
          // "Payment" label, and the processed flag. Column order/labels live in the
          // Movements tab (MOVEMENT_CSV_COLUMNS in index.jsx).
          row.put("transactionTypeLabel", trxTypeClassicLabel(trxType));
          row.put(FIELD_DEPOSIT_AMOUNT, nullSafeBigDecimal(rs.getBigDecimal("deposit_amt")));
          row.put("withdrawalAmount", nullSafeBigDecimal(rs.getBigDecimal("payment_amt")));
          row.put("statusLabel", statusClassicLabel(status));
          row.put("processed", !"RPAP".equals(status) && !"RPAE".equals(status));
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

  /** Active accounting elements (dimensions) of the client's chart of accounts. */
  private static final String ENABLED_DIM_SQL =
      "SELECT DISTINCT e.elementtype"
          + "  FROM c_acctschema_element e"
          + "  JOIN c_acctschema s ON s.c_acctschema_id = e.c_acctschema_id"
          + " WHERE s.isactive = 'Y' AND e.isactive = 'Y'"
          + "   AND s.ad_client_id = (SELECT ad_client_id FROM fin_financial_account"
          + "                          WHERE fin_financial_account_id = ?)";

  /** AcctSchema element type → UI dimension key (AC/PR are not navigable dimensions). */
  private static final Map<String, String> DIM_BY_ELEMENT = Map.of(
      "OO", DIM_ORGANIZATION, "BP", DIM_BPARTNER, "PJ", DIM_PROJECT,
      "CC", DIM_COSTCENTER, "AY", DIM_ACTIVITY, "MC", DIM_CAMPAIGN,
      "SR", DIM_SALESREGION, "U1", DIM_USER1, "U2", DIM_USER2);

  /** Stable display order for the "more info" dimension panel. */
  private static final List<String> DIM_ORDER = List.of(
      DIM_ORGANIZATION, DIM_BPARTNER, DIM_PROJECT, DIM_COSTCENTER,
      DIM_ACTIVITY, DIM_CAMPAIGN, DIM_SALESREGION, DIM_USER1, DIM_USER2);

  /**
   * Returns the dimension keys enabled in the client's chart of accounts, in a
   * stable display order. The UI renders the "more info" panel from this list.
   */
  /** Navigable accounting dimensions active in the client's chart of accounts. */
  Set<String> loadActiveDimensionSet(String accountId) throws Exception {
    Set<String> enabled = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(ENABLED_DIM_SQL)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String key = DIM_BY_ELEMENT.get(StringUtils.trimToEmpty(rs.getString("elementtype")));
          if (key != null) enabled.add(key);
        }
      }
    }
    return enabled;
  }

  JSONArray loadEnabledDimensions(String accountId) throws Exception {
    Set<String> enabled = loadActiveDimensionSet(accountId);
    JSONArray arr = new JSONArray();
    for (String key : DIM_ORDER) {
      if (enabled.contains(key)) arr.put(key);
    }
    return arr;
  }

  /**
   * Dimensions explicitly hidden from the finacc transaction header (docbasetype
   * FAT) via {@code ad_client_acctdimension.show_in_header = 'N'}. Header
   * dimensions default to visible when there is no override row (matching
   * Classic), so we compute the header set as "active dimensions minus the ones
   * explicitly hidden here" rather than only the rows flagged to show.
   */
  private static final String HEADER_DIM_HIDDEN_SQL =
      "SELECT DISTINCT d.dimension"
          + "  FROM ad_client_acctdimension d"
          + " WHERE d.isactive = 'Y' AND d.show_in_header = 'N' AND d.docbasetype = ?"
          + "   AND d.ad_client_id = (SELECT ad_client_id FROM fin_financial_account"
          + "                          WHERE fin_financial_account_id = ?)";

  JSONArray loadHeaderDimensions(String accountId) throws Exception {
    Set<String> active = loadActiveDimensionSet(accountId);
    Set<String> hidden = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(HEADER_DIM_HIDDEN_SQL)) {
      ps.setString(1, DOCBASETYPE_FAT);
      ps.setString(2, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String key = DIM_BY_ELEMENT.get(StringUtils.trimToEmpty(rs.getString("dimension")));
          if (key != null) hidden.add(key);
        }
      }
    }
    JSONArray arr = new JSONArray();
    for (String key : DIM_ORDER) {
      if (active.contains(key) && !hidden.contains(key)) {
        arr.put(key);
      }
    }
    return arr;
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
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
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
      // Log full stack trace server-side; never echo e.getMessage() back —
      // that can leak DB constraint names or other internal details to the
      // client.
      log.error("Error creating financial account transaction", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not create the movement. Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Handles {@code POST ?action=create-payment} — creates and processes a FIN_Payment
   * replicating Classic's "Add Payment" (the processing auto-creates the finacc transaction).
   * Delegates the business logic to {@link AddPaymentService}.
   */
  private NeoResponse handleCreatePayment(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try {
      OBContext.setAdminMode(true);
      return AddPaymentService.doAddPayment(body);
    } catch (org.openbravo.base.exception.OBException e) {
      log.warn("Add payment failed: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Error creating payment", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not register the payment. Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Handles {@code POST ?action=transfer} — transfers funds between two financial accounts of the
   * organization. Validates the request and delegates ALL the transaction creation to Etendo
   * Classic's {@link FundsTransferActionHandler#createTransfer}: this handler never reimplements the
   * paired-transaction / conversion-rate / processing logic.
   */
  private NeoResponse handleTransfer(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try {
      OBContext.setAdminMode(true);
      return transfer(body);
    } catch (org.openbravo.base.exception.OBException e) {
      log.warn("Funds transfer business error: {}", e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Funds transfer failed", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Could not transfer the funds. Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
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
    if (amount.compareTo(availableBalance(source)) > 0) {
      return NeoResponse.error(400, "Amount exceeds the available balance of the source account");
    }

    GLItem glItem = null;
    String glItemId = body.optString("glItemId", null);
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
    Date transferDate = parseDate(body.optString("transferDate", null), null);

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

  /** Current available balance of the account (the guard rejects transfers above it). */
  BigDecimal availableBalance(FIN_FinancialAccount account) {
    return nullSafeBigDecimal(account.getCurrentBalance());
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
    String trxType = body.optString(FIELD_TRX_TYPE, null);
    String description = body.optString(FIELD_DESCRIPTION, "");
    BigDecimal depositAmount = nullSafeBigDecimal(optBigDecimal(body, FIELD_DEPOSIT_AMOUNT));
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
   * Handles {@code GET ?action=bpartner-lookup&q=...&role=customer|vendor} —
   * fuzzy search over {@code c_bpartner.name}, scoped to the current client +
   * system records. When {@code role=customer} only customers are returned;
   * when {@code role=vendor} only vendors; otherwise all active bpartners.
   */
  private NeoResponse handleBpartnerLookup(NeoContext context) {
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    String role = context.getQueryParams() != null ? context.getQueryParams().getOrDefault("role", "") : "";
    return runLookup(
        "SELECT c_bpartner_id AS id, name FROM c_bpartner"
            + " WHERE isactive='Y' AND ad_client_id IN (?, ?)"
            + bpartnerRoleFilter(role)
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

  /** Dimension key → {table, id column} for the dimension-values lookup. */
  private static final Map<String, String[]> DIM_VALUE_TABLE = Map.of(
      DIM_ORGANIZATION, new String[] { "ad_org", "ad_org_id" },
      DIM_BPARTNER, new String[] { "c_bpartner", "c_bpartner_id" },
      DIM_PROJECT, new String[] { "c_project", "c_project_id" },
      DIM_COSTCENTER, new String[] { "c_costcenter", "c_costcenter_id" },
      DIM_ACTIVITY, new String[] { "c_activity", "c_activity_id" },
      DIM_CAMPAIGN, new String[] { "c_campaign", "c_campaign_id" },
      DIM_SALESREGION, new String[] { "c_salesregion", "c_salesregion_id" },
      DIM_USER1, new String[] { DIM_USER1, "user1_id" },
      DIM_USER2, new String[] { DIM_USER2, "user2_id" });

  /**
   * Handles {@code GET ?action=dimension-values&dimension=<key>&q=...} — returns
   * the selectable values for an accounting dimension (organizations, projects,
   * cost centers, …), scoped to the current client + system records. The table
   * and id column come from a fixed whitelist, never from user input.
   */
  private NeoResponse handleDimensionValues(NeoContext context) {
    String dim = context.getQueryParams() != null ? context.getQueryParams().get("dimension") : null;
    String[] meta = dim != null ? DIM_VALUE_TABLE.get(dim) : null;
    if (meta == null) {
      return NeoResponse.error(400, "Unknown or unsupported dimension: " + dim);
    }
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    String sql = "SELECT " + meta[1] + " AS id, name FROM " + meta[0]
        + " WHERE isactive = 'Y' AND " + meta[1] + " <> '0' AND ad_client_id IN (?, ?)"
        + "   AND LOWER(name) LIKE ?"
        + " ORDER BY name ASC"
        + " LIMIT 200";
    return runLookup(sql, q, "values");
  }

  /**
   * Outstanding (unpaid) invoice payment-schedule details for a business
   * partner. The {@code amount} of a {@code FIN_Payment_ScheduleDetail} whose
   * {@code fin_payment_detail_id} is NULL is the amount still pending payment;
   * those rows are exactly what Classic's "Add Payment" grid shows. We expose
   * the same triplet of amounts: invoiced ({@code c_invoice.grandtotal}),
   * expected ({@code fin_payment_schedule.amount}) and outstanding
   * ({@code fin_payment_scheduledetail.amount}).
   */
  private static final String OUTSTANDING_INVOICES_SQL =
      "SELECT psd.fin_payment_scheduledetail_id AS id,"
          + "       i.documentno AS doc_no,"
          + "       COALESCE(i.description, '') AS descr,"
          + "       bp.name AS bpartner,"
          + "       i.dateinvoiced AS invoice_date,"
          + "       ps.duedate AS due_date,"
          + "       COALESCE(pm.name, '') AS payment_method,"
          + "       COALESCE(proj.name, '') AS project,"
          + "       COALESCE(o.documentno, '') AS order_no,"
          + "       cur.iso_code AS currency_iso,"
          + "       i.grandtotal AS invoiced_amount,"
          + "       ps.amount AS expected_amount,"
          + "       psd.amount AS outstanding_amount"
          + "  FROM fin_payment_scheduledetail psd"
          + "  JOIN fin_payment_schedule ps ON ps.fin_payment_schedule_id = psd.fin_payment_schedule_invoice"
          + "  JOIN c_invoice i ON i.c_invoice_id = ps.c_invoice_id"
          + "  JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id"
          + "  JOIN c_currency cur ON cur.c_currency_id = i.c_currency_id"
          + "  LEFT JOIN fin_paymentmethod pm ON pm.fin_paymentmethod_id = COALESCE(ps.fin_paymentmethod_id, i.fin_paymentmethod_id)"
          + "  LEFT JOIN c_project proj ON proj.c_project_id = i.c_project_id"
          + "  LEFT JOIN c_order o ON o.c_order_id = i.c_order_id"
          + " WHERE psd.fin_payment_detail_id IS NULL"
          + "   AND psd.isactive = 'Y'"
          + "   AND i.docstatus = 'CO'"
          + "   AND i.issotrx = ?"
          + "   AND i.ad_client_id IN (?, ?)";

  /** Optional clause: scope to a single business partner when one is given. */
  private static final String OUTSTANDING_INVOICES_BP_CLAUSE = "   AND i.c_bpartner_id = ?";
  private static final String OUTSTANDING_INVOICES_TAIL =
      " ORDER BY ps.duedate ASC, i.documentno ASC LIMIT 500";

  /**
   * Handles {@code GET ?action=outstanding-invoices&bpartnerId=...&doc=in|out} —
   * returns the unpaid invoices scoped by direction ({@code doc=in} → sales /
   * cobro, {@code doc=out} → purchase / pago). When {@code bpartnerId} is blank
   * the invoices of ALL business partners are returned (so the user can allocate
   * a payment to any contact); when given, they are scoped to that partner.
   */
  private NeoResponse handleOutstandingInvoices(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String bpartnerId = qp != null ? qp.get("bpartnerId") : null;
    boolean hasBp = StringUtils.isNotBlank(bpartnerId);
    String doc = qp != null ? qp.getOrDefault("doc", "in") : "in";
    String isSotrx = "out".equals(doc) ? "N" : "Y";
    String sql = OUTSTANDING_INVOICES_SQL
        + (hasBp ? OUTSTANDING_INVOICES_BP_CLAUSE : "")
        + OUTSTANDING_INVOICES_TAIL;
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      LocalDate today = LocalDate.now();
      JSONArray arr = new JSONArray();
      try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
        ps.setString(1, isSotrx);
        ps.setString(2, "0");
        ps.setString(3, clientId);
        if (hasBp) ps.setString(4, bpartnerId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            arr.put(marshalOutstandingInvoice(rs, today));
          }
        }
      }
      JSONObject data = new JSONObject();
      data.put("invoices", arr);
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject envelope = new JSONObject();
      envelope.put(KEY_RESPONSE, responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Outstanding invoices lookup failed for bpartner {}", bpartnerId, e);
      return NeoResponse.error(500, "Outstanding invoices lookup failed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Maps one outstanding-invoice row to the JSON shape the payment UI expects. */
  private JSONObject marshalOutstandingInvoice(ResultSet rs, LocalDate today) throws Exception {
    java.sql.Date invoiceDate = rs.getDate("invoice_date");
    java.sql.Date dueDate = rs.getDate("due_date");
    JSONObject row = new JSONObject();
    row.put("id", rs.getString("id"));
    row.put("no", StringUtils.trimToEmpty(rs.getString("doc_no")));
    row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString("descr")));
    row.put("bp", StringUtils.trimToEmpty(rs.getString(DIM_BPARTNER)));
    row.put("fecha", formatDmy(invoiceDate));
    row.put("venc", formatDmy(dueDate));
    row.put("dias", daysUntil(dueDate, today));
    row.put("metodo", StringUtils.trimToEmpty(rs.getString("payment_method")));
    row.put("proyecto", StringUtils.trimToEmpty(rs.getString(DIM_PROJECT)));
    row.put("orderNo", StringUtils.trimToEmpty(rs.getString("order_no")));
    row.put("cc", "");
    row.put("mon", StringUtils.trimToEmpty(rs.getString("currency_iso")));
    row.put("total", nullSafeBigDecimal(rs.getBigDecimal("invoiced_amount")));
    row.put("expected", nullSafeBigDecimal(rs.getBigDecimal("expected_amount")));
    row.put("pend", nullSafeBigDecimal(rs.getBigDecimal("outstanding_amount")));
    return row;
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
      envelope.put(KEY_RESPONSE, responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Lookup failed for query '{}'", q, e);
      return NeoResponse.error(500, "Lookup failed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
