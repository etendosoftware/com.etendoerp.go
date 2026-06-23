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

import static com.etendoerp.go.schemaforge.BankStatementsSupport.descriptionExpr;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.advpaymentmngt.utility.APRM_MatchingUtility;
import org.openbravo.advpaymentmngt.utility.FIN_MatchedTransaction;
import org.openbravo.advpaymentmngt.utility.FIN_MatchingTransaction;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * NeoHandler that powers the manual bank-reconciliation split panel introduced by
 * the Bank Reconciliation module (T6). It is registered against the {@code header}
 * entity of the {@code bank-reconciliation} W spec via
 * {@code ETGO_SF_ENTITY.Java_Qualifier = "bankReconciliation"}.
 *
 * <p>It exposes three custom action routes and falls through to the generic CRUD
 * service for any other request (so the W spec's list/get of FIN_Reconciliation
 * keeps working):
 *
 * <table border="1">
 *   <caption>Routes</caption>
 *   <tr><th>Method + action</th><th>Behaviour</th></tr>
 *   <tr>
 *     <td>{@code GET ?action=pendingLines&accountId=X}</td>
 *     <td>Unmatched bank-statement lines (panel left). Optional filters
 *         {@code status}, {@code dateFrom}, {@code dateTo}, {@code q}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET ?action=candidates&accountId=X&lineId=Y}</td>
 *     <td>Available finacc transactions (panel right). The ones the standard
 *         matching DAO suggests for the selected line are flagged
 *         {@code suggested:true}. Optional {@code docType} filter.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GET ?action=autoMatch&accountId=X}</td>
 *     <td>Preview: runs standard algorithm (pasada 1) + rule engine (pasada 2) over
 *         all pending lines. Returns grouped suggestions without mutating any data.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST action=reconcileGroup}</td>
 *     <td>Composes the standard Etendo reconciliation services for a 1:N manual
 *         match. Body: {@code { financialAccountId, statementLineId, operationIds:[...] }}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST action=applySuggestions}</td>
 *     <td>Commits the accepted groups from {@code autoMatch}. Creates GL-item
 *         payments when required by a rule and reconciles. Body: {@code
 *         { financialAccountId, groups:[{ statementLineId, operationIds:[], createPayment? }] }}.</td>
 *   </tr>
 * </table>
 *
 * <p>This handler NEVER reimplements reconciliation logic. {@code reconcileGroup}
 * composes {@link APRM_MatchingUtility#addNewDraftReconciliation},
 * {@link APRM_MatchingUtility#matchBankStatementLine(FIN_BankStatementLine, java.util.List, FIN_Reconciliation, String, boolean)}
 * and {@link APRM_MatchingUtility#processReconciliation}.
 *
 * <p>Signed amounts: a bank-statement line amount is {@code cramount - dramount};
 * a finacc transaction amount is {@code depositamt - paymentamt}.
 */
@Named("bankReconciliation")
public class ReconciliationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ReconciliationHandler.class);

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";

  private static final String PARAM_ACTION = "action";
  private static final String PARAM_ACCOUNT_ID = "accountId";
  private static final String PARAM_LINE_ID = "lineId";
  private static final String PARAM_DOC_TYPE = "docType";
  private static final String PARAM_KIND = "kind";
  private static final String KIND_INVOICES = "invoices";
  private static final String PARAM_DATE_FROM = "dateFrom";
  private static final String PARAM_DATE_TO = "dateTo";
  private static final String PARAM_Q = "q";

  private static final String ACTION_PENDING_LINES = "pendingLines";
  private static final String ACTION_CANDIDATES = "candidates";
  private static final String ACTION_RECONCILE_GROUP = "reconcileGroup";
  private static final String ACTION_AUTO_MATCH = "autoMatch";
  private static final String ACTION_APPLY_SUGGESTIONS = "applySuggestions";

  /** Match level recorded on the reconciliation lines produced by this handler. */
  private static final String MATCH_LEVEL_MANUAL = "MANUALMATCH";
  /** Action code for {@link APRM_MatchingUtility#processReconciliation} (P = process). */
  private static final String PROCESS_ACTION = "P";
  /** GL-item transaction types: BP Deposit (Cobro / money in) and BP Withdrawal (Pago / money out). */
  private static final String TRX_TYPE_DEPOSIT = "BPD";
  private static final String TRX_TYPE_WITHDRAWAL = "BPW";
  /** Tolerance applied when comparing the line amount to the sum of operations. */
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  /** JSON keys reused across rows — extracted to satisfy Sonar S1192. */
  private static final String MSG_MISSING_PARAM = "Missing required parameter: ";
  private static final String MSG_ACCOUNT_NOT_FOUND = "Financial account not found: ";
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_ID = "id";
  private static final String KEY_DATE = "date";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_STATUS = "status";
  private static final String KEY_DOCUMENT_NO = "documentNo";
  private static final String KEY_PARTNER_NAME = "partnerName";
  private static final String KEY_PENDING_BALANCE = "pendingBalance";
  private static final String KEY_SUGGESTED = "suggested";
  private static final String COL_PARTNER_NAME = "partner_name";
  private static final String KEY_COUNTS = "counts";
  private static final String CNT_RECEIPTS = "receipts";
  private static final String CNT_PAYMENTS = "payments";
  private static final String CNT_SALES_INVOICES = "salesInvoices";
  private static final String CNT_PURCHASE_INVOICES = "purchaseInvoices";
  private static final String KEY_GROUPS = "groups";
  private static final String KEY_IS_NEW = "isNew";
  private static final String STATUS_PENDING = "pending";
  private static final String MSG_INTERNAL_SERVER_ERROR = "Internal Server Error";

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  /**
   * Pending bank-statement lines (panel left): unmatched lines of the account,
   * scoped to the current client and the accessible organization tree. Optional
   * filters bind extra parameters (date range, free-text search).
   */
  private static final String PENDING_LINES_SQL =
      "SELECT bsl.fin_bankstatementline_id,"
          + "       bsl.datetrx,"
          + "       %s AS description,"
          + "       COALESCE(bp.name, NULLIF(bsl.bpartnername, ''), '') AS partner_name,"
          + "       COALESCE(bsl.referenceno, '') AS reference_no,"
          + "       CASE WHEN bsl.fin_finacc_transaction_id IS NULL THEN 'pending' ELSE 'reconciled' END AS line_status,"
          + "       COALESCE(bsl.em_etgo_match_group_id, '') AS match_group_id,"
          + "       COALESCE(bsl.cramount, 0) - COALESCE(bsl.dramount, 0) AS amount"
          + "  FROM fin_bankstatementline bsl"
          + "  JOIN fin_bankstatement bs ON bs.fin_bankstatement_id = bsl.fin_bankstatement_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = bsl.c_bpartner_id"
          + " WHERE bsl.isactive = 'Y'"
          + "   AND bs.isactive = 'Y'"
          // Draft statements (processed = 'N') are not reconcilable yet, so their
          // lines must not show in the reconciliation left panel.
          + "   AND bs.processed = 'Y'"
          + "   AND bs.fin_financial_account_id = ?"
          + "   AND bs.ad_client_id = ?"
          + "   AND bs.ad_org_id = ANY (?)";

  /** Status filter codes accepted by {@code pendingLines}. */
  private static final String STATUS_RECONCILED = "reconciled";

  /** Module extension column holding the 1:N reconciliation group id (option B). */
  private static final String COL_MATCH_GROUP = "EM_ETGO_Match_Group_ID";

  private static final String PENDING_LINES_ORDER =
      " ORDER BY bsl.datetrx ASC, bsl.line ASC";

  /**
   * Available reconciliation candidates (panel right): processed finacc
   * transactions of the account not yet reconciled. Joins FIN_Payment +
   * C_BPartner for display info. Mirrors the availability predicate used by the
   * standard Etendo matching DAO (unreconciled, processed, status &lt;&gt; 'RPPC').
   */
  private static final String CANDIDATES_SQL =
      "SELECT ft.fin_finacc_transaction_id,"
          + "       ft.statementdate,"
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       COALESCE(bp.name, '') AS partner_name,"
          + "       COALESCE(ft.depositamt, 0) - COALESCE(ft.paymentamt, 0) AS amount,"
          + "       COALESCE(fp.isreceipt, 'N') AS is_receipt"
          + "  FROM fin_finacc_transaction ft"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = COALESCE(ft.c_bpartner_id, fp.c_bpartner_id)"
          + " WHERE ft.fin_reconciliation_id IS NULL"
          + "   AND ft.processed = 'Y'"
          + "   AND ft.status <> 'RPPC'"
          + "   AND ft.fin_financial_account_id = ?"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate >= ?)"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate <= ?)";

  private static final String CANDIDATES_ORDER =
      " ORDER BY ft.statementdate ASC, ft.line ASC";

  /**
   * Movements already linked to a reconciled statement line (panel right, read-only). Returns the
   * line's own transaction (1:1) plus every transaction of its 1:N match group, so the merged
   * reconciled line shows exactly the movements it groups — and nothing else. {@code lineId} is
   * bound twice (the line itself, and the group sub-query).
   */
  private static final String LINKED_TXNS_SQL =
      "SELECT ft.fin_finacc_transaction_id,"
          + "       ft.statementdate,"
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       COALESCE(bp.name, '') AS partner_name,"
          + "       COALESCE(ft.depositamt, 0) - COALESCE(ft.paymentamt, 0) AS amount"
          + "  FROM fin_bankstatementline bsl"
          + "  JOIN fin_finacc_transaction ft ON ft.fin_finacc_transaction_id = bsl.fin_finacc_transaction_id"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = COALESCE(ft.c_bpartner_id, fp.c_bpartner_id)"
          + " WHERE bsl.fin_finacc_transaction_id IS NOT NULL"
          + "   AND ( bsl.fin_bankstatementline_id = ?"
          + "         OR ( COALESCE(bsl.em_etgo_match_group_id, '') <> ''"
          + "              AND bsl.em_etgo_match_group_id ="
          + "                  (SELECT em_etgo_match_group_id FROM fin_bankstatementline"
          + "                    WHERE fin_bankstatementline_id = ?) ) )"
          + " ORDER BY ft.statementdate ASC";

  /**
   * Unpaid invoice installments (panel right, "invoices" mode). One row per
   * {@code FIN_PaymentSchedule} with a positive outstanding (= SUM of its pending
   * {@code FIN_PaymentScheduleDetail} rows, i.e. those not yet linked to a payment detail). Filtered
   * by document direction ({@code issotrx}) so it lists sales invoices for an inflow line or
   * purchase invoices for an outflow line. Bind order: issotrx, clientId, org-array.
   */
  private static final String INVOICE_CANDIDATES_SQL =
      "SELECT ps.fin_payment_schedule_id,"
          + "       inv.c_invoice_id,"
          + "       COALESCE(inv.documentno, '') AS documentno,"
          + "       inv.dateinvoiced AS invoicedate,"
          + "       COALESCE(bp.name, '') AS partner_name,"
          + "       SUM(psd.amount) AS outstanding"
          + "  FROM fin_payment_scheduledetail psd"
          + "  JOIN fin_payment_schedule ps ON ps.fin_payment_schedule_id = psd.fin_payment_schedule_invoice"
          + "  JOIN c_invoice inv ON inv.c_invoice_id = ps.c_invoice_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = inv.c_bpartner_id"
          + " WHERE psd.fin_payment_detail_id IS NULL"
          + "   AND inv.docstatus = 'CO'"
          + "   AND inv.issotrx = ?"
          + "   AND inv.ad_client_id = ?"
          + "   AND inv.ad_org_id = ANY (?)"
          + "   AND (CAST(? AS date) IS NULL OR inv.dateinvoiced >= ?)"
          + "   AND (CAST(? AS date) IS NULL OR inv.dateinvoiced <= ?)"
          + " GROUP BY ps.fin_payment_schedule_id, inv.c_invoice_id, inv.documentno,"
          + "          inv.dateinvoiced, bp.name"
          + " HAVING SUM(psd.amount) > 0"
          + " ORDER BY inv.dateinvoiced ASC, inv.documentno ASC";

  /** Per-isreceipt count of reconcilable transactions of the account (for the type selector). */
  private static final String TXN_COUNTS_SQL =
      "SELECT COALESCE(fp.isreceipt, '') AS is_receipt, COUNT(*) AS cnt"
          + "  FROM fin_finacc_transaction ft"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + " WHERE ft.fin_reconciliation_id IS NULL"
          + "   AND ft.processed = 'Y'"
          + "   AND ft.status <> 'RPPC'"
          + "   AND ft.fin_financial_account_id = ?"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate >= ?)"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate <= ?)"
          + " GROUP BY fp.isreceipt";

  /** Per-issotrx count of unpaid invoice installments (for the type selector). */
  private static final String INVOICE_COUNTS_SQL =
      "SELECT t.issotrx, COUNT(*) AS cnt FROM ("
          + "  SELECT ps.fin_payment_schedule_id, inv.issotrx"
          + "    FROM fin_payment_scheduledetail psd"
          + "    JOIN fin_payment_schedule ps ON ps.fin_payment_schedule_id = psd.fin_payment_schedule_invoice"
          + "    JOIN c_invoice inv ON inv.c_invoice_id = ps.c_invoice_id"
          + "   WHERE psd.fin_payment_detail_id IS NULL"
          + "     AND inv.docstatus = 'CO'"
          + "     AND inv.ad_client_id = ?"
          + "     AND inv.ad_org_id = ANY (?)"
          + "     AND (CAST(? AS date) IS NULL OR inv.dateinvoiced >= ?)"
          + "     AND (CAST(? AS date) IS NULL OR inv.dateinvoiced <= ?)"
          + "   GROUP BY ps.fin_payment_schedule_id, inv.issotrx"
          + "   HAVING SUM(psd.amount) > 0"
          + " ) t GROUP BY t.issotrx";

  @Override
  public NeoResponse handle(NeoContext context) {
    String method = context.getHttpMethod();
    Map<String, String> qp = context.getQueryParams();
    String action = qp != null ? qp.get(PARAM_ACTION) : null;

    if (METHOD_GET.equals(method) && ACTION_PENDING_LINES.equals(action)) {
      return handlePendingLines(context);
    }
    if (METHOD_GET.equals(method) && ACTION_CANDIDATES.equals(action)) {
      return handleCandidates(context);
    }
    if (METHOD_GET.equals(method) && ACTION_AUTO_MATCH.equals(action)) {
      return handleAutoMatch(context);
    }
    if (METHOD_POST.equals(method) && ACTION_RECONCILE_GROUP.equals(action)) {
      return handleReconcileGroup(context);
    }
    if (METHOD_POST.equals(method) && ACTION_APPLY_SUGGESTIONS.equals(action)) {
      return handleApplySuggestions(context);
    }
    // Any other request (generic list / getById of the W spec) flows through.
    return null;
  }

  // ---------------------------------------------------------------------------
  // GET pendingLines
  // ---------------------------------------------------------------------------

  private NeoResponse handlePendingLines(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_MISSING_PARAM + PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      Set<String> orgs = accessibleOrgs(OBContext.getOBContext().getCurrentOrganization().getId());
      return buildPendingLines(accountId, clientId, orgs, qp);
    } catch (Exception e) {
      log.error("Error building pendingLines for account {}", accountId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildPendingLines(String accountId, String clientId, Set<String> orgs,
      Map<String, String> filters) throws Exception {
    Map<String, String> filterMap = filters != null ? filters : Collections.emptyMap();
    String dateFrom = filterMap.get(PARAM_DATE_FROM);
    String dateTo = filterMap.get(PARAM_DATE_TO);
    String q = filterMap.get(PARAM_Q);

    // The status parameter is no longer used to filter at SQL level: every line is returned with
    // its computed `state` and the per-state `counts`, and the frontend filters client-side. This
    // keeps the matching engine running once per panel load instead of once per filter change.
    String descExpr = descriptionExpr();
    StringBuilder sql = new StringBuilder(String.format(PENDING_LINES_SQL, descExpr));
    if (StringUtils.isNotBlank(dateFrom)) {
      sql.append(" AND bsl.datetrx >= ?");
    }
    if (StringUtils.isNotBlank(dateTo)) {
      sql.append(" AND bsl.datetrx <= ?");
    }
    if (StringUtils.isNotBlank(q)) {
      // Search the SAME unified description (standard + C43) shown in the column.
      sql.append(" AND LOWER(").append(descExpr).append(") LIKE ?");
    }
    sql.append(PENDING_LINES_ORDER);

    FIN_FinancialAccount account = loadAccount(accountId);
    JSONArray rawLines = new JSONArray();
    // Connection is managed by the DAL's Hibernate Session; don't close it.
    Connection conn = OBDal.getInstance().getConnection();
    List<MatchRuleEngine.Rule> rules = loadRules(conn, accountId);
    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
      int idx = 1;
      ps.setString(idx++, accountId);
      ps.setString(idx++, clientId);
      ps.setArray(idx++, conn.createArrayOf("varchar", orgs.toArray(new String[0])));
      if (StringUtils.isNotBlank(dateFrom)) {
        ps.setDate(idx++, Date.valueOf(dateFrom));
      }
      if (StringUtils.isNotBlank(dateTo)) {
        ps.setDate(idx++, Date.valueOf(dateTo));
      }
      if (StringUtils.isNotBlank(q)) {
        ps.setString(idx++, "%" + q.toLowerCase() + "%");
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal amount = nullSafe(rs.getBigDecimal(KEY_AMOUNT));
          String lineId = rs.getString("fin_bankstatementline_id");
          boolean reconciled = STATUS_RECONCILED
              .equalsIgnoreCase(StringUtils.trimToEmpty(rs.getString("line_status")));
          String state = reconciled ? STATUS_RECONCILED
              : AutoMatchSupport.classifyPendingLine(account, lineId, rules);

          JSONObject row = new JSONObject();
          row.put(KEY_ID, lineId);
          row.put(KEY_DATE, formatDate(rs.getTimestamp("datetrx")));
          row.put("description", StringUtils.trimToEmpty(rs.getString("description")));
          row.put(KEY_PARTNER_NAME, StringUtils.trimToEmpty(rs.getString(COL_PARTNER_NAME)));
          row.put("referenceNo", StringUtils.trimToEmpty(rs.getString("reference_no")));
          // Coarse status kept for backward compatibility (pending|reconciled).
          row.put(KEY_STATUS, reconciled ? STATUS_RECONCILED : STATUS_PENDING);
          // Fine-grained state for the left-panel filter (pending|suggested|byRule|difference|reconciled).
          row.put("state", state);
          // 1:N group id (option B): sub-lines of the same reconcile group share this value.
          row.put("matchGroupId", StringUtils.trimToEmpty(rs.getString("match_group_id")));
          row.put(KEY_AMOUNT, amount);
          rawLines.put(row);
        }
      }
    }

    // Collapse the split sub-lines of a 1:N reconciliation into a single line, so a group shows as
    // one reconciled entry (same as the imported-statements view) instead of N separate sub-lines.
    JSONArray lines = BankStatementsSupport.mergeMatchGroups(rawLines);
    BigDecimal total = BigDecimal.ZERO;
    Map<String, Integer> counts = AutoMatchSupport.newCounts();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject row = lines.getJSONObject(i);
      total = total.add(nullSafe(new BigDecimal(row.optString(KEY_AMOUNT, "0"))));
      String state = row.optString("state", STATUS_PENDING);
      counts.put("all", counts.get("all") + 1);
      counts.put(state, counts.getOrDefault(state, 0) + 1);
    }
    JSONObject countsJson = new JSONObject();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      countsJson.put(entry.getKey(), entry.getValue());
    }
    JSONObject data = new JSONObject();
    data.put("lines", lines);
    data.put("total", total);
    data.put("counts", countsJson);
    return envelope(data);
  }


  // ---------------------------------------------------------------------------
  // GET candidates
  // ---------------------------------------------------------------------------

  private NeoResponse handleCandidates(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_MISSING_PARAM + PARAM_ACCOUNT_ID);
    }
    String lineId = qp != null ? qp.get(PARAM_LINE_ID) : null;
    String docType = qp != null ? qp.get(PARAM_DOC_TYPE) : null;
    String kind = qp != null ? qp.get(PARAM_KIND) : null;
    String dateFrom = qp != null ? qp.get(PARAM_DATE_FROM) : null;
    String dateTo = qp != null ? qp.get(PARAM_DATE_TO) : null;
    try {
      OBContext.setAdminMode(true);
      if (KIND_INVOICES.equalsIgnoreCase(kind)) {
        return buildInvoiceCandidates(accountId, lineId, docType, dateFrom, dateTo);
      }
      return buildCandidates(accountId, lineId, docType, dateFrom, dateTo);
    } catch (Exception e) {
      log.error("Error building candidates for account {}", accountId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildCandidates(String accountId, String lineId, String docType,
      String dateFrom, String dateTo) throws Exception {
    FIN_BankStatementLine selectedLine =
        StringUtils.isNotBlank(lineId) ? loadLine(lineId) : null;
    // A reconciled line is read-only: return ONLY the movement(s) already linked to it (its 1:1
    // transaction, or every transaction of its 1:N match group) — never the unreconciled pool.
    if (selectedLine != null && selectedLine.getFinancialAccountTransaction() != null) {
      return buildLinkedTransactions(lineId);
    }

    Set<String> suggestedIds = suggestedTransactionIds(accountId, lineId);
    // 1:N: if the selected line amount equals the sum of a signal group (same logic the automatch
    // uses), pre-mark ALL of its operations as suggested — not only a single 1:1 standard match.
    if (selectedLine != null) {
      for (FIN_FinaccTransaction t : AutoMatchSupport.findSignalGroup(
          accountId, selectedLine, new HashSet<>(), TOLERANCE)) {
        suggestedIds.add(t.getId());
      }
    }

    JSONArray candidates = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    StringBuilder sql = new StringBuilder(CANDIDATES_SQL);
    boolean filterDocType = StringUtils.isNotBlank(docType);
    if (filterDocType) {
      // docType maps to the payment direction: receipts (collections) vs payments.
      sql.append(" AND fp.isreceipt = ?");
    }
    sql.append(CANDIDATES_ORDER);
    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
      int idx = 1;
      ps.setString(idx++, accountId);
      idx = bindDateRange(ps, idx, dateFrom, dateTo);
      if (filterDocType) {
        ps.setString(idx++, docTypeToIsReceipt(docType));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String id = rs.getString("fin_finacc_transaction_id");
          BigDecimal amount = nullSafe(rs.getBigDecimal(KEY_AMOUNT));
          JSONObject row = new JSONObject();
          row.put(KEY_ID, id);
          row.put(KEY_DATE, formatDate(rs.getTimestamp("statementdate")));
          row.put(KEY_DOCUMENT_NO, StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put(KEY_PARTNER_NAME, StringUtils.trimToEmpty(rs.getString(COL_PARTNER_NAME)));
          row.put(KEY_AMOUNT, amount);
          // Pending balance equals the transaction amount for now (partial
          // allocations against invoices are a follow-up).
          row.put(KEY_PENDING_BALANCE, amount);
          row.put(KEY_STATUS, STATUS_PENDING);
          row.put(KEY_SUGGESTED, suggestedIds.contains(id));
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
    data.put(KEY_COUNTS, candidateCounts(accountId, dateFrom, dateTo));
    return envelope(data);
  }

  /**
   * Read-only "linked movements" list for a reconciled line: its 1:1 transaction, or every
   * transaction of its 1:N match group. Same row shape as {@link #buildCandidates}, flagged
   * {@code linked} with a reconciled status so the UI renders the panel read-only.
   */
  private NeoResponse buildLinkedTransactions(String lineId) throws Exception {
    JSONArray candidates = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(LINKED_TXNS_SQL)) {
      ps.setString(1, lineId);
      ps.setString(2, lineId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal amount = nullSafe(rs.getBigDecimal(KEY_AMOUNT));
          JSONObject row = new JSONObject();
          row.put(KEY_ID, rs.getString("fin_finacc_transaction_id"));
          row.put(KEY_DATE, formatDate(rs.getTimestamp("statementdate")));
          row.put(KEY_DOCUMENT_NO, StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put(KEY_PARTNER_NAME, StringUtils.trimToEmpty(rs.getString(COL_PARTNER_NAME)));
          row.put(KEY_AMOUNT, amount);
          row.put(KEY_PENDING_BALANCE, amount);
          row.put(KEY_STATUS, STATUS_RECONCILED);
          row.put(KEY_SUGGESTED, false);
          row.put("linked", true);
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
    return envelope(data);
  }

  /**
   * Unpaid-invoice candidates for the selected line ("invoices" mode of the right panel). The
   * line's flow direction (sign of cramount-dramount) selects sales invoices (inflow → receipts)
   * or purchase invoices (outflow → payments); the candidate {@code amount} carries the line's
   * sign so the panel's sign filter and the reconcile guard treat it like a transaction. Each row
   * also carries {@code kind:"invoice"}, {@code invoiceId}, {@code scheduleId} and {@code isReceipt}
   * for the "create payment" reconcile path.
   */
  NeoResponse buildInvoiceCandidates(String accountId, String lineId, String docType,
      String dateFrom, String dateTo) throws Exception {
    JSONArray candidates = new JSONArray();
    FIN_FinancialAccount account = loadAccount(accountId);
    // Direction: the UI's transaction-type selector passes docType (receipts → sales/Y,
    // payments → purchase/N). Fall back to the selected line's sign when no docType is given.
    Boolean receipt;
    if (StringUtils.isNotBlank(docType)) {
      receipt = "Y".equals(docTypeToIsReceipt(docType));
    } else {
      FIN_BankStatementLine line = StringUtils.isNotBlank(lineId) ? loadLine(lineId) : null;
      int sign = line != null
          ? nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount())).signum() : 0;
      receipt = sign == 0 ? null : sign > 0;
    }
    if (account == null || receipt == null) {
      JSONObject empty = new JSONObject();
      empty.put(ACTION_CANDIDATES, candidates);
      empty.put(KEY_COUNTS, candidateCounts(accountId, dateFrom, dateTo));
      return envelope(empty);
    }
    boolean isReceipt = receipt;
    String issotrx = isReceipt ? "Y" : "N";
    OrganizationStructureProvider osp = OBContext.getOBContext()
        .getOrganizationStructureProvider(account.getClient().getId());
    Set<String> orgs = osp.getNaturalTree(account.getOrganization().getId());

    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(INVOICE_CANDIDATES_SQL)) {
      int idx = 1;
      ps.setString(idx++, issotrx);
      ps.setString(idx++, account.getClient().getId());
      ps.setArray(idx++, conn.createArrayOf("varchar", orgs.toArray(new String[0])));
      bindDateRange(ps, idx, dateFrom, dateTo);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal outstanding = nullSafe(rs.getBigDecimal("outstanding"));
          BigDecimal signed = isReceipt ? outstanding : outstanding.negate();
          JSONObject row = new JSONObject();
          row.put(KEY_ID, rs.getString("fin_payment_schedule_id"));
          row.put(KEY_DATE, formatDate(rs.getTimestamp("invoicedate")));
          row.put(KEY_DOCUMENT_NO, StringUtils.trimToEmpty(rs.getString("documentno")));
          row.put(KEY_PARTNER_NAME, StringUtils.trimToEmpty(rs.getString(COL_PARTNER_NAME)));
          row.put(KEY_AMOUNT, signed);
          row.put(KEY_PENDING_BALANCE, signed);
          row.put(KEY_STATUS, STATUS_PENDING);
          row.put(KEY_SUGGESTED, false);
          row.put("kind", "invoice");
          row.put("invoiceId", rs.getString("c_invoice_id"));
          row.put("scheduleId", rs.getString("fin_payment_schedule_id"));
          row.put("isReceipt", isReceipt);
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
    data.put(KEY_COUNTS, candidateCounts(accountId, dateFrom, dateTo));
    return envelope(data);
  }

  /**
   * Per-type counts for the right-panel "Tipo de transacción" selector: reconcilable transactions
   * split by receipt/payment, plus unpaid sales/purchase invoice installments (account org tree).
   */
  private JSONObject candidateCounts(String accountId, String dateFrom, String dateTo) {
    JSONObject counts = new JSONObject();
    try {
      counts.put(CNT_RECEIPTS, 0);
      counts.put(CNT_PAYMENTS, 0);
      counts.put(CNT_SALES_INVOICES, 0);
      counts.put(CNT_PURCHASE_INVOICES, 0);
      FIN_FinancialAccount account = loadAccount(accountId);
      if (account == null) {
        return counts;
      }
      computeCandidateCounts(counts, account, dateFrom, dateTo);
    } catch (Exception e) {
      // Counts are decorative; never fail the candidates response over them.
      log.debug("Could not compute candidate counts for {}: {}", accountId, e.getMessage());
    }
    return counts;
  }

  private void computeCandidateCounts(JSONObject counts, FIN_FinancialAccount account,
      String dateFrom, String dateTo) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(TXN_COUNTS_SQL)) {
      ps.setString(1, account.getId());
      bindDateRange(ps, 2, dateFrom, dateTo);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String receipt = rs.getString("is_receipt");
          if ("Y".equals(receipt)) {
            counts.put(CNT_RECEIPTS, rs.getInt("cnt"));
          } else if ("N".equals(receipt)) {
            counts.put(CNT_PAYMENTS, rs.getInt("cnt"));
          }
        }
      }
    }
    OrganizationStructureProvider osp = OBContext.getOBContext()
        .getOrganizationStructureProvider(account.getClient().getId());
    Set<String> orgs = osp.getNaturalTree(account.getOrganization().getId());
    try (PreparedStatement ps = conn.prepareStatement(INVOICE_COUNTS_SQL)) {
      ps.setString(1, account.getClient().getId());
      ps.setArray(2, conn.createArrayOf("varchar", orgs.toArray(new String[0])));
      bindDateRange(ps, 3, dateFrom, dateTo);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String issotrx = rs.getString("issotrx");
          if ("Y".equals(issotrx)) {
            counts.put(CNT_SALES_INVOICES, rs.getInt("cnt"));
          } else if ("N".equals(issotrx)) {
            counts.put(CNT_PURCHASE_INVOICES, rs.getInt("cnt"));
          }
        }
      }
    }
  }

  /**
   * Returns the id of the finacc transaction the <b>standard Etendo matching algorithm</b>
   * suggests for the selected bank-statement line, or an empty set when no line is selected or
   * the algorithm finds no match. Delegates to the account's configured
   * {@link FIN_MatchingTransaction} exactly as Classic does (amount + date / reference / business
   * partner per the algorithm's own flags), so it returns at most ONE best match — never every
   * same-amount transaction. The Classic algorithm is used as-is; no criteria are relaxed here.
   */
  Set<String> suggestedTransactionIds(String accountId, String lineId) {
    Set<String> ids = new HashSet<>();
    if (StringUtils.isBlank(lineId)) {
      return ids;
    }
    FIN_BankStatementLine line = OBDal.getInstance().get(FIN_BankStatementLine.class, lineId);
    if (line == null) {
      return ids;
    }
    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null || account.getMatchingAlgorithm() == null
        || StringUtils.isBlank(account.getMatchingAlgorithm().getJavaClassName())) {
      return ids;
    }
    try {
      FIN_MatchingTransaction matcher =
          new FIN_MatchingTransaction(account.getMatchingAlgorithm().getJavaClassName());
      FIN_MatchedTransaction matched = matcher.match(line, new ArrayList<>());
      if (matched != null && matched.getTransaction() != null
          && !FIN_MatchedTransaction.NOMATCH.equals(matched.getMatchLevel())) {
        ids.add(matched.getTransaction().getId());
      }
    } catch (Exception e) {
      log.debug("Standard matching algorithm failed for line {}: {}", lineId, e.getMessage());
    }
    return ids;
  }

  /** Maps the UI docType filter to the FIN_Payment.isreceipt flag. */
  private static String docTypeToIsReceipt(String docType) {
    // Collections / sales invoices arrive as receipts ('Y'); payments as 'N'.
    return "payments".equalsIgnoreCase(docType) ? "N" : "Y";
  }

  // ---------------------------------------------------------------------------
  // POST reconcileGroup
  // ---------------------------------------------------------------------------

  private NeoResponse handleReconcileGroup(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Request body is required");
    }
    try {
      OBContext.setAdminMode(true);
      return reconcileGroup(body);
    } catch (OBException e) {
      log.warn("reconcileGroup business error: {}", e.getMessage());
      doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("reconcileGroup failed", e);
      doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse reconcileGroup(JSONObject body) throws Exception {
    String accountId = body.optString("financialAccountId", null);
    String statementLineId = body.optString("statementLineId", null);
    List<String> operationIds = readOperationIds(body);
    JSONArray invoiceSpecs = body.optJSONArray("invoices");
    boolean hasInvoices = invoiceSpecs != null && invoiceSpecs.length() > 0;

    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(statementLineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "financialAccountId and statementLineId are required");
    }
    if (operationIds.isEmpty() && !hasInvoices) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "At least one operation or invoice is required");
    }

    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_ACCOUNT_NOT_FOUND + accountId);
    }

    FIN_BankStatementLine line = loadLine(statementLineId);
    if (line == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Statement line not found: " + statementLineId);
    }
    if (!belongsToAccount(line, accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Statement line does not belong to the financial account");
    }
    if (line.getFinancialAccountTransaction() != null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Statement line is already reconciled");
    }

    // Pay each selected unpaid invoice (creates payment + auto-creates its transaction); the new
    // transaction ids join operationIds so the standard reconcile below matches them to the line.
    if (hasInvoices) {
      NeoResponse invError = createInvoicePayments(account, line, invoiceSpecs, operationIds);
      if (invError != null) {
        return invError;
      }
    }

    NeoResponse opError = validateOperations(operationIds, accountId, line);
    if (opError != null) {
      return opError;
    }

    return compose(account, line, operationIds);
  }

  /**
   * Creates one payment per selected unpaid invoice, distributing the statement line amount across
   * them (capped at each installment's outstanding; the last may be partial). Each payment is
   * processed via {@link PaymentRegistrationService#registerPaymentCore} — which auto-creates the
   * finacc transaction (BPD/BPW) — and the resulting transaction id is appended to
   * {@code operationIds} for the standard reconcile. Rejects when the invoices cannot cover the
   * line amount.
   *
   * @return {@code null} when every payment was created, or a {@link NeoResponse} error
   */
  private NeoResponse createInvoicePayments(FIN_FinancialAccount account,
      FIN_BankStatementLine line, JSONArray invoiceSpecs, List<String> operationIds)
      throws Exception {
    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    boolean isReceipt = lineAmount.signum() >= 0;
    BigDecimal remaining = lineAmount.abs();
    for (int i = 0; i < invoiceSpecs.length(); i++) {
      if (remaining.compareTo(TOLERANCE) <= 0) {
        break;
      }
      JSONObject spec = invoiceSpecs.getJSONObject(i);
      String invoiceId = spec.optString("invoiceId", null);
      String scheduleId = spec.optString("scheduleId", null);
      if (StringUtils.isBlank(invoiceId) || StringUtils.isBlank(scheduleId)) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "invoiceId and scheduleId are required for each invoice");
      }
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      FIN_PaymentSchedule schedule = OBDal.getInstance().get(FIN_PaymentSchedule.class, scheduleId);
      if (invoice == null || schedule == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            "Invoice or payment schedule not found: " + invoiceId);
      }
      BigDecimal outstanding = nullSafe(schedule.getOutstandingAmount()).abs();
      BigDecimal allocate = remaining.min(outstanding);
      if (allocate.compareTo(TOLERANCE) <= 0) {
        continue;
      }
      FIN_Payment payment = PaymentRegistrationService.registerPaymentCore(
          invoice, schedule, allocate, line.getTransactionDate(), account, isReceipt);
      List<FIN_FinaccTransaction> txns = payment.getFINFinaccTransactionList();
      if (txns.isEmpty()) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Payment did not produce a transaction: " + payment.getId());
      }
      operationIds.add(txns.get(0).getId());
      remaining = remaining.subtract(allocate);
    }
    if (remaining.compareTo(TOLERANCE) > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected invoices do not cover the statement line amount. Remaining: "
              + remaining.toPlainString());
    }
    return null;
  }

  /**
   * Validates the selected operations: each must exist, belong to the account
   * and not be reconciled yet; their signed amounts must sum to the line amount
   * within {@link #TOLERANCE}.
   *
   * @return {@code null} when valid, or the {@link NeoResponse} carrying the error
   */
  private NeoResponse validateOperations(List<String> operationIds, String accountId,
      FIN_BankStatementLine line) {
    BigDecimal opSum = BigDecimal.ZERO;
    for (String opId : operationIds) {
      FIN_FinaccTransaction trx = loadTransaction(opId);
      if (trx == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Operation not found: " + opId);
      }
      if (trx.getAccount() == null || !accountId.equals(trx.getAccount().getId())) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Operation does not belong to the financial account: " + opId);
      }
      if (trx.getReconciliation() != null) {
        return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
            "Operation is already reconciled: " + opId);
      }
      opSum = opSum.add(signedAmount(trx));
    }
    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    int lineSign = lineAmount.signum();
    // Operations may match PART of the line — Etendo's matchBankStatementLine splits the line and
    // leaves a remainder line (e.g. a 500 line matched to 300 reconciles 300 and leaves 200
    // pending). They must NOT exceed the line amount, nor run in the opposite direction
    // (over-reconciliation is not supported).
    boolean sameDirection = opSum.signum() == 0 || lineSign == 0 || opSum.signum() == lineSign;
    boolean withinLine = opSum.abs().compareTo(lineAmount.abs().add(TOLERANCE)) <= 0;
    if (!sameDirection || !withinLine) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The selected operations (" + opSum.toPlainString()
              + ") exceed the statement line amount (" + lineAmount.toPlainString()
              + "). Operations can match part of the line but not exceed it.");
    }
    return null;
  }

  /**
   * Composes the standard Etendo reconciliation services for a 1:N manual match.
   * Never reimplements the matching logic.
   */
  private NeoResponse compose(FIN_FinancialAccount account, FIN_BankStatementLine line,
      List<String> operationIds) throws Exception {
    FIN_Reconciliation rec = addNewDraftReconciliation(account);
    // 1:N grouping (option B): tag the original line with a fresh match-group id BEFORE the
    // match so the split sub-lines inherit it (DalUtil.copy copies all EM_ properties). The
    // UI re-groups the resulting sub-lines by this id. Only needed when more than one operation
    // is linked (a single operation produces no split worth grouping).
    if (operationIds.size() > 1) {
      tagMatchGroup(line);
    }
    matchBankStatementLine(line, operationIds, rec);
    OBError result = processReconciliation(rec);
    if (result != null && "Error".equalsIgnoreCase(result.getType())) {
      doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, result.getMessage());
    }

    JSONObject data = new JSONObject();
    data.put("reconciliationId", rec.getId());
    JSONArray lineIds = new JSONArray();
    lineIds.put(line.getId());
    data.put("lineIds", lineIds);
    data.put("updatedBalance", nullSafe(rec.getEndingBalance()));
    return NeoResponse.createdWithData(data);
  }

  private static List<String> readOperationIds(JSONObject body) throws JSONException {
    List<String> ids = new ArrayList<>();
    JSONArray arr = body.optJSONArray("operationIds");
    if (arr == null) {
      return ids;
    }
    for (int i = 0; i < arr.length(); i++) {
      String id = arr.optString(i, null);
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static boolean belongsToAccount(FIN_BankStatementLine line, String accountId) {
    return line.getBankStatement() != null
        && line.getBankStatement().getAccount() != null
        && accountId.equals(line.getBankStatement().getAccount().getId());
  }

  private static BigDecimal signedAmount(FIN_FinaccTransaction trx) {
    return nullSafe(trx.getDepositAmount()).subtract(nullSafe(trx.getPaymentAmount()));
  }

  // ---------------------------------------------------------------------------
  // GET autoMatch (preview — does not mutate any data)
  // ---------------------------------------------------------------------------

  private NeoResponse handleAutoMatch(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_MISSING_PARAM + PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      return buildAutoMatch(accountId);
    } catch (Exception e) {
      log.error("Error building autoMatch for account {}", accountId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildAutoMatch(String accountId) throws Exception {
    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_ACCOUNT_NOT_FOUND + accountId);
    }

    Connection conn = OBDal.getInstance().getConnection();
    List<MatchRuleEngine.Rule> rules = loadRules(conn, accountId);

    // Collect all pending lines for this account.
    List<FIN_BankStatementLine> pendingLines = loadPendingLines(accountId);

    JSONArray groups = new JSONArray();
    Set<String> usedTxnIds = new HashSet<>();
    int opsToLink = 0;
    int willCreate = 0;

    for (FIN_BankStatementLine line : pendingLines) {
      // Pass 1 (1:1): standard algorithm — uses lazy evaluation so findSignalGroup is not called
      // when a 1:1 match is already found, avoiding an unnecessary DB query.
      Set<String> suggested = suggestedTransactionIds(accountId, line.getId());
      suggested.removeAll(usedTxnIds);
      FIN_FinaccTransaction txn1to1 = suggested.isEmpty() ? null : loadTransaction(suggested.iterator().next());
      if (txn1to1 != null) {
        usedTxnIds.add(txn1to1.getId());
        groups.put(AutoMatchSupport.buildStandardGroup(line, txn1to1, FIN_MatchedTransaction.STRONG));
        opsToLink++;
      } else {
        int[] delta = matchFallback(accountId, line, usedTxnIds, rules, groups);
        opsToLink += delta[0];
        willCreate += delta[1];
      }
    }

    JSONObject kpis = new JSONObject();
    kpis.put(ACTION_PENDING_LINES, pendingLines.size());
    kpis.put("groupsFound", groups.length());
    kpis.put("opsToLink", opsToLink);
    kpis.put("willCreate", willCreate);

    JSONObject data = new JSONObject();
    data.put("account", accountId);
    data.put("kpis", kpis);
    data.put(KEY_GROUPS, groups);
    return envelope(data);
  }


  // ---------------------------------------------------------------------------
  // POST applySuggestions (commit — creates payments + reconciles)
  // ---------------------------------------------------------------------------

  private NeoResponse handleApplySuggestions(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Request body is required");
    }
    try {
      OBContext.setAdminMode(true);
      return applySuggestions(body);
    } catch (OBException e) {
      log.warn("applySuggestions business error: {}", e.getMessage());
      doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("applySuggestions failed", e);
      doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse applySuggestions(JSONObject body) throws Exception {
    String accountId = body.optString("financialAccountId", null);
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "financialAccountId is required");
    }
    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_ACCOUNT_NOT_FOUND + accountId);
    }

    JSONArray groupsJson = body.optJSONArray(KEY_GROUPS);
    if (groupsJson == null || groupsJson.length() == 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "groups array is required");
    }

    JSONArray results = new JSONArray();
    for (int i = 0; i < groupsJson.length(); i++) {
      JSONObject groupEntry = groupsJson.optJSONObject(i);
      if (groupEntry != null) {
        results.put(applyGroup(account, groupEntry).getBody());
      }
    }

    JSONObject data = new JSONObject();
    data.put("applied", results.length());
    data.put("results", results);
    return NeoResponse.createdWithData(data);
  }

  private NeoResponse applyGroup(FIN_FinancialAccount account, JSONObject groupEntry)
      throws Exception {
    String statementLineId = groupEntry.optString("statementLineId", null);
    if (StringUtils.isBlank(statementLineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "statementLineId is required");
    }

    FIN_BankStatementLine line = loadLine(statementLineId);
    if (line == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Statement line not found: " + statementLineId);
    }
    if (line.getFinancialAccountTransaction() != null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Statement line is already reconciled: " + statementLineId);
    }

    List<String> operationIds = readOperationIds(groupEntry);

    // When a rule group requires creating a new transaction, do that first.
    JSONObject createPaymentSpec = groupEntry.optJSONObject("createPayment");
    if (createPaymentSpec != null && StringUtils.isNotBlank(createPaymentSpec.optString("glItemId", null))) {
      String newTxnId = createTransactionForRule(account, line, createPaymentSpec);
      if (StringUtils.isNotBlank(newTxnId)) {
        operationIds = new ArrayList<>(operationIds);
        operationIds.add(newTxnId);
        // Increment the rule's matchCount.
        String ruleId = createPaymentSpec.optString("ruleId", null);
        if (StringUtils.isNotBlank(ruleId)) {
          AutoMatchSupport.incrementMatchCount(ruleId);
        }
      }
    }

    if (operationIds.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "At least one operation is required for line: " + statementLineId);
    }

    // Operations (including any just-created rule transaction) may match part of the line but must
    // not EXCEED it — the same over-reconciliation guard the manual reconcileGroup path applies.
    NeoResponse opError = validateOperations(operationIds, account.getId(), line);
    if (opError != null) {
      return opError;
    }

    return compose(account, line, operationIds);
  }

  /**
   * Creates a GL-item financial-account transaction (Cobro {@code BPD} / Pago {@code BPW}) for a
   * rule-origin group and returns its id. The transaction carries the rule's accounting concept
   * (GL item) directly in its own GL Item field — no payment is created. Mirrors the New Movement
   * wizard ({@link FinancialAccountTransactionsHandler}) and processes the transaction so it is
   * reconcilable.
   */
  String createTransactionForRule(FIN_FinancialAccount account, FIN_BankStatementLine line,
      JSONObject spec) throws Exception {
    String glItemId = spec.optString("glItemId", null);
    String bpartnerId = spec.optString("bpartnerId", null);
    String amtStr = spec.optString(KEY_AMOUNT, null);
    BigDecimal amount = StringUtils.isNotBlank(amtStr) ? new BigDecimal(amtStr) : BigDecimal.ZERO;
    if (amount.compareTo(BigDecimal.ZERO) == 0) {
      amount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    }

    GLItem glItem = OBDal.getInstance().get(GLItem.class, glItemId);
    if (glItem == null) {
      throw new OBException("GL item not found: " + glItemId);
    }

    // Use the statement line's organization (always concrete) — the account org may be the
    // generic '*' org, which cannot own documents/transactions.
    Organization org = line.getOrganization() != null
        ? line.getOrganization() : account.getOrganization();
    BigDecimal absAmount = amount.abs();
    boolean isDeposit = amount.signum() >= 0;

    FIN_FinaccTransaction trx = OBProvider.getInstance().get(FIN_FinaccTransaction.class);
    trx.setClient(account.getClient());
    trx.setOrganization(org);
    trx.setActive(true);
    trx.setAccount(account);
    trx.setCurrency(account.getCurrency());
    // Cobro (BPD) when money comes in, Pago (BPW) when money goes out — carries the GL item.
    trx.setTransactionType(isDeposit ? TRX_TYPE_DEPOSIT : TRX_TYPE_WITHDRAWAL);
    trx.setTransactionDate(line.getTransactionDate());
    trx.setDateAcct(line.getTransactionDate());
    trx.setDescription(StringUtils.trimToEmpty(line.getDescription()));
    trx.setLineNo(AutoMatchSupport.nextTransactionLineNo(account.getId()));
    trx.setDepositAmount(isDeposit ? absAmount : BigDecimal.ZERO);
    trx.setPaymentAmount(isDeposit ? BigDecimal.ZERO : absAmount);
    trx.setStatus(isDeposit ? "RPAE" : "RPAP");
    trx.setGLItem(glItem);
    if (StringUtils.isNotBlank(bpartnerId)) {
      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpartnerId);
      if (bp != null) {
        trx.setBusinessPartner(bp);
      }
    }
    OBDal.getInstance().save(trx);
    OBDal.getInstance().flush();

    // Process the transaction (sets processed = Y) so it can be reconciled.
    FIN_TransactionProcess.doTransactionProcess(PROCESS_ACTION, trx);
    OBDal.getInstance().flush();
    return trx.getId();
  }

  /**
   * Passes 1b (1:N signal grouping) and 2 (rule engine) — evaluated only when 1:1 did not match.
   *
   * @return int[2] where [0] = opsToLink increment, [1] = willCreate increment
   */
  private int[] matchFallback(String accountId, FIN_BankStatementLine line,
      Set<String> usedTxnIds, List<MatchRuleEngine.Rule> rules, JSONArray groups)
      throws org.codehaus.jettison.json.JSONException {
    List<FIN_FinaccTransaction> signalGroup =
        AutoMatchSupport.findSignalGroup(accountId, line, usedTxnIds, TOLERANCE);
    if (!signalGroup.isEmpty()) {
      signalGroup.forEach(t -> usedTxnIds.add(t.getId()));
      groups.put(AutoMatchSupport.buildMultiGroup(line, signalGroup));
      return new int[]{signalGroup.size(), 0};
    }
    MatchRuleEngine.MatchResult ruleResult = MatchRuleEngine.evaluate(
        StringUtils.trimToEmpty(line.getDescription()),
        StringUtils.trimToEmpty(line.getReferenceNo()),
        StringUtils.trimToEmpty(line.getBpartnername()), rules);
    if (ruleResult.isMatched()) {
      JSONObject ruleGroup = AutoMatchSupport.buildRuleGroup(
          line, ruleResult.primary, ruleResult.alternatives);
      groups.put(ruleGroup);
      return Boolean.TRUE.equals(ruleGroup.opt(KEY_IS_NEW))
          ? new int[]{0, 1} : new int[]{1, 0};
    }
    return new int[]{0, 0};
  }

  // ---------------------------------------------------------------------------
  // Seams (package-private to allow unit tests to stub the DAL / Classic layer)
  // ---------------------------------------------------------------------------

  Set<String> accessibleOrgs(String orgId) {
    return new OrganizationStructureProvider().getChildTree(orgId, true);
  }

  FIN_FinancialAccount loadAccount(String accountId) {
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
  }

  FIN_BankStatementLine loadLine(String lineId) {
    return OBDal.getInstance().get(FIN_BankStatementLine.class, lineId);
  }

  FIN_FinaccTransaction loadTransaction(String transactionId) {
    return OBDal.getInstance().get(FIN_FinaccTransaction.class, transactionId);
  }

  FIN_Reconciliation addNewDraftReconciliation(FIN_FinancialAccount account) {
    return APRM_MatchingUtility.addNewDraftReconciliation(account);
  }

  void matchBankStatementLine(FIN_BankStatementLine line, List<String> operationIds,
      FIN_Reconciliation rec) {
    APRM_MatchingUtility.matchBankStatementLine(line, operationIds, rec, MATCH_LEVEL_MANUAL, true);
  }

  OBError processReconciliation(FIN_Reconciliation rec) throws Exception {
    return APRM_MatchingUtility.processReconciliation(PROCESS_ACTION, rec);
  }

  void doRollbackAndClose() {
    OBDal.getInstance().rollbackAndClose();
  }

  /**
   * Tags the bank-statement line with a fresh match-group id on the
   * {@code EM_ETGO_Match_Group_ID} extension column. Resolves the DAL property by column name at
   * runtime (no dependency on the generated entity accessor) and degrades gracefully when the
   * model has not yet loaded the column. Must run BEFORE the line is split so the clones inherit
   * the value. Package-private for testability.
   */
  void tagMatchGroup(FIN_BankStatementLine line) {
    try {
      org.openbravo.base.model.Entity entity = org.openbravo.base.model.ModelProvider.getInstance()
          .getEntity(FIN_BankStatementLine.ENTITY_NAME);
      org.openbravo.base.model.Property prop =
          entity.getPropertyByColumnName(COL_MATCH_GROUP, false);
      if (prop != null) {
        line.set(prop.getName(), org.openbravo.erpCommon.utility.SequenceIdData.getUUID());
        OBDal.getInstance().save(line);
        OBDal.getInstance().flush();
      } else {
        log.warn("Column {} not yet in the model; skipping match-group tag", COL_MATCH_GROUP);
      }
    } catch (Exception e) {
      log.warn("Could not tag match group on line {}", line.getId(), e);
    }
  }

  /**
   * Loads unreconciled bank-statement lines for the given account.
   * Uses the same criteria as the {@code pendingLines} action: active lines with no linked
   * transaction, regardless of the bank-statement processed flag (which would exclude C43-imported
   * or manually-entered statements that are still in draft status).
   * Package-private for testability.
   */
  @SuppressWarnings("unchecked")
  List<FIN_BankStatementLine> loadPendingLines(String accountId) {
    String hql = "select bsl from FIN_BankStatementLine as bsl"
        + "  join bsl.bankStatement as bs"
        + " where bs.account.id = :accountId"
        + "   and bsl.financialAccountTransaction is null"
        + "   and bsl.active = true"
        + "   and bs.active = true"
        + " order by bsl.transactionDate asc, bsl.lineNo asc";
    return OBDal.getInstance().getSession()
        .createQuery(hql, FIN_BankStatementLine.class)
        .setParameter(PARAM_ACCOUNT_ID, accountId)
        .list();
  }

  /**
   * Loads active matching rules from the DB. Package-private for testability.
   */
  List<MatchRuleEngine.Rule> loadRules(Connection conn, String accountId) throws Exception {
    return MatchRuleEngine.loadRules(conn, accountId);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static NeoResponse envelope(JSONObject data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put(KEY_DATA, data);
    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(wrapper);
  }

  private static String formatDate(java.sql.Timestamp ts) {
    return ts == null ? "" : ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  static BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Binds the four parameters of the two optional date-range clauses
   * ({@code (CAST(? AS date) IS NULL OR col >= ?)} and the {@code <=} twin): dateFrom, dateFrom,
   * dateTo, dateTo. Blank bounds are bound as SQL NULL, which makes the clause a no-op.
   *
   * @return the next free parameter index
   */
  private static int bindDateRange(PreparedStatement ps, int idx, String dateFrom, String dateTo)
      throws SQLException {
    setDateOrNull(ps, idx++, dateFrom);
    setDateOrNull(ps, idx++, dateFrom);
    setDateOrNull(ps, idx++, dateTo);
    setDateOrNull(ps, idx++, dateTo);
    return idx;
  }

  private static void setDateOrNull(PreparedStatement ps, int idx, String date) throws SQLException {
    if (StringUtils.isBlank(date)) {
      ps.setNull(idx, Types.DATE);
    } else {
      ps.setDate(idx, Date.valueOf(date));
    }
  }
}
