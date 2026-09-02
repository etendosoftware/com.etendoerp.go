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
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.attachOptional;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.belongsToAccount;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.bindDateRange;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.docTypeToIsReceipt;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.envelope;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.formatDate;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.readOperationIds;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.signedAmount;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;
import com.etendoerp.payment.removal.util.TransactionRemovalUtil;
import com.etendoerp.payment.removal.util.Utilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.dao.TransactionsDao;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.advpaymentmngt.utility.APRM_MatchingUtility;
import org.openbravo.advpaymentmngt.utility.FIN_MatchedTransaction;
import org.openbravo.advpaymentmngt.utility.FIN_MatchingTransaction;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.project.Project;

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

  /**
   * Header-level accounting dimensions per financial account, memoized for the lifetime of this
   * handler. {@code NeoHandler} beans are {@code @Dependent} (one instance per request), so an
   * {@code applySuggestions} batch resolves the configuration once instead of once per line.
   */
  private final Map<String, Set<String>> headerDimensionsByAccount = new HashMap<>();

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";

  private static final String PARAM_ACTION = "action";
  static final String PARAM_ACCOUNT_ID = "accountId";
  static final String PARAM_LINE_ID = "lineId";
  static final String PARAM_DOC_TYPE = "docType";
  static final String PARAM_KIND = "kind";
  static final String KIND_INVOICES = "invoices";
  static final String PARAM_DATE_FROM = "dateFrom";
  static final String PARAM_DATE_TO = "dateTo";
  private static final String PARAM_Q = "q";

  private static final String ACTION_PENDING_LINES = "pendingLines";
  private static final String ACTION_CANDIDATES = "candidates";
  private static final String ACTION_RECONCILE_GROUP = "reconcileGroup";
  private static final String ACTION_AUTO_MATCH = "autoMatch";
  private static final String ACTION_APPLY_SUGGESTIONS = "applySuggestions";
  private static final String ACTION_REACTIVATE = "reactivate";
  private static final String ACTION_REMOVE_OPERATION = "removeOperation";
  private static final String ACTION_REACTIVATE_SELECTED = "reactivateSelected";
  private static final String ACTION_RECONCILE_DIFFERENCE = "reconcileDifference";

  /** One route of the {@code ?action=} dispatcher. */
  @FunctionalInterface
  private interface ActionRoute {
    NeoResponse apply(ReconciliationHandler handler, NeoContext context);
  }

  /**
   * {@code "<httpMethod> <action>"} → its route, so {@link #handle(NeoContext)} stays a single lookup
   * however many actions this handler grows (Sonar java:S3776). Unknown keys fall through to the
   * generic spec handling, exactly as the previous if-chain's final {@code return null} did.
   */
  // Map.ofEntries, not Map.of: the latter caps at 10 key/value pairs and this map is at 9, so the
  // next action added would fail to compile with a message ("no suitable method found for of") that
  // does not mention the real cause.
  private static final Map<String, ActionRoute> ROUTES = Map.ofEntries(
      Map.entry(METHOD_GET + " " + ACTION_PENDING_LINES,
          ReconciliationHandlerSupport::handlePendingLines),
      Map.entry(METHOD_GET + " " + ACTION_CANDIDATES,
          ReconciliationHandlerSupport::handleCandidates),
      Map.entry(METHOD_GET + " " + ACTION_AUTO_MATCH,
          ReconciliationHandlerSupport::handleAutoMatch),
      Map.entry(METHOD_POST + " " + ACTION_RECONCILE_GROUP,
          ReconciliationHandlerSupport::handleReconcileGroup),
      Map.entry(METHOD_POST + " " + ACTION_APPLY_SUGGESTIONS,
          ReconciliationHandlerSupport::handleApplySuggestions),
      Map.entry(METHOD_POST + " " + ACTION_REACTIVATE,
          ReconciliationHandlerSupport::handleReactivate),
      Map.entry(METHOD_POST + " " + ACTION_REMOVE_OPERATION,
          ReconciliationHandlerSupport::handleRemoveOperation),
      Map.entry(METHOD_POST + " " + ACTION_REACTIVATE_SELECTED,
          ReconciliationHandlerSupport::handleReactivateSelected),
      Map.entry(METHOD_POST + " " + ACTION_RECONCILE_DIFFERENCE,
          ReconciliationHandlerSupport::handleReconcileDifference));

  /** Match level recorded on the reconciliation lines produced by this handler. */
  private static final String MATCH_LEVEL_MANUAL = "MANUALMATCH";
  /** Action code for {@link APRM_MatchingUtility#processReconciliation} (P = process). */
  private static final String PROCESS_ACTION = "P";
  /** GL-item transaction types: BP Deposit (Cobro / money in) and BP Withdrawal (Pago / money out). */
  private static final String TRX_TYPE_DEPOSIT = "BPD";
  private static final String TRX_TYPE_WITHDRAWAL = "BPW";
  /** Tolerance applied when comparing the line amount to the sum of operations. */
  static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  /**
   * JSON keys / messages reused across rows — extracted to satisfy Sonar S1192. Some are
   * package-private (not {@code private}) so the sibling {@link ReconciliationHandlerSupport}
   * helpers extracted from this handler can reuse the exact same keys/messages.
   */
  static final String MSG_MISSING_PARAM = "Missing required parameter: ";
  static final String MSG_ACCOUNT_NOT_FOUND = "Financial account not found: ";
  static final String MSG_BODY_REQUIRED = "Request body is required";
  static final String MSG_STATEMENT_LINE_NOT_FOUND = "Statement line not found: ";
  static final String KEY_FINANCIAL_ACCOUNT_ID = "financialAccountId";
  static final String KEY_STATEMENT_LINE_ID = "statementLineId";
  static final String KEY_TRANSACTION_ID = "transactionId";
  static final String KEY_ID = "id";
  private static final String KEY_DATE = "date";
  static final String KEY_AMOUNT = "amount";
  static final String KEY_STATUS = "status";
  private static final String KEY_DOCUMENT_NO = "documentNo";
  private static final String KEY_PARTNER_NAME = "partnerName";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_PENDING_BALANCE = "pendingBalance";
  private static final String KEY_SUGGESTED = "suggested";
  /** Candidate matched only within the account's amount/date tolerance — drives the red badge. */
  private static final String KEY_NEAR_MATCH = "nearMatch";
  /** Why an un-reconcile / reactivate could not complete — shown verbatim by the client. */
  static final String KEY_FAILURE_REASON = "failureReason";
  private static final String COL_PARTNER_NAME = "partner_name";
  static final String KEY_COUNTS = "counts";
  static final String KEY_TOTAL = "total";
  private static final String SQL_VARCHAR = "varchar";
  private static final String KEY_GROUPS = "groups";
  static final String STATUS_PENDING = "pending";
  static final String MSG_INTERNAL_SERVER_ERROR = "Internal Server Error";
  /** Reused error message + JSON key — extracted to satisfy Sonar S1192 (each appears 3×). */
  static final String MSG_LINE_NOT_IN_ACCOUNT =
      "Statement line does not belong to the financial account";
  /** Shared with {@link ReconciliationDifferenceSupport}, which hoists this very guard. */
  static final String MSG_LINE_ALREADY_RECONCILED = "Statement line is already reconciled";
  static final String KEY_UPDATED_BALANCE = "updatedBalance";

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
          // A line counts as reconciled only when its transaction belongs to a PROCESSED
          // reconciliation. "Reactivar" (Core's plain reactivate) sets the reconciliation back to
          // draft WITHOUT touching the line→transaction or transaction→reconciliation links, so such
          // a line is functionally un-confirmed and must return to the pending pool — where its own
          // transactions come back pre-selected and confirming just processes that same draft.
          + "       CASE WHEN bsl.fin_finacc_transaction_id IS NULL"
          + "                 OR COALESCE(rec.processed, 'N') = 'N' THEN 'pending'"
          + "            ELSE 'reconciled' END AS line_status,"
          + "       CASE WHEN COALESCE(rec.processed, 'N') = 'N'"
          + "            THEN COALESCE(rec.fin_reconciliation_id, '') ELSE '' END"
          + "                                   AS draft_reconciliation_id,"
          + "       COALESCE(bsl.em_etgo_match_group_id, '') AS match_group_id,"
          + "       COALESCE(bsl.cramount, 0) AS cramount,"
          + "       COALESCE(bsl.dramount, 0) AS dramount,"
          + "       COALESCE(bsl.em_etgo_pending_amount, 0) AS em_etgo_pending_amount,"
          + "       COALESCE(bsl.cramount, 0) - COALESCE(bsl.dramount, 0) AS amount,"
          + "       bsl.fin_finacc_transaction_id,"
          // Linked transaction columns (aliased exactly as BankStatementsSupport.buildLineTxns reads
          // them) so the reconciliation-tab line carries the same txns[] contract as the
          // imported-statements view — the right-panel "conciliado" block renders/unlinks them.
          + "       COALESCE(fp.documentno, '') AS txn_documentno,"
          + "       ft.statementdate            AS txn_date,"
          + "       COALESCE(tbp.name, pbp.name, '') AS txn_contact,"
          + "       COALESCE(ft.description, fp.description, '') AS txn_description,"
          + "       ft.trxtype                  AS txn_trxtype,"
          + "       ft.status                   AS txn_status,"
          + "       CASE WHEN ft.trxtype = 'BPD' THEN ft.depositamt ELSE -ft.paymentamt END AS txn_amount,"
          + "       ft.fin_payment_id           AS txn_payment_id,"
          + "       fp.isreceipt                AS txn_payment_isreceipt,"
          + "       COALESCE(ft.em_etgo_auto_created, 'N') AS txn_auto_created"
          + "  FROM fin_bankstatementline bsl"
          + "  JOIN fin_bankstatement bs ON bs.fin_bankstatement_id = bsl.fin_bankstatement_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = bsl.c_bpartner_id"
          + "  LEFT JOIN fin_finacc_transaction ft ON ft.fin_finacc_transaction_id = bsl.fin_finacc_transaction_id"
          + "  LEFT JOIN fin_reconciliation rec ON rec.fin_reconciliation_id = ft.fin_reconciliation_id"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner tbp ON tbp.c_bpartner_id = ft.c_bpartner_id"
          + "  LEFT JOIN c_bpartner pbp ON pbp.c_bpartner_id = fp.c_bpartner_id"
          + " WHERE bsl.isactive = 'Y'"
          + "   AND bs.isactive = 'Y'"
          // Draft statements (processed = 'N') are not reconcilable yet, so their
          // lines must not show in the reconciliation left panel.
          + "   AND bs.processed = 'Y'"
          + "   AND bs.fin_financial_account_id = ?"
          + "   AND bs.ad_client_id = ?"
          + "   AND bs.ad_org_id = ANY (?)";

  /** Status filter codes accepted by {@code pendingLines}. */
  static final String STATUS_RECONCILED = "reconciled";

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
          + "       COALESCE(ft.description, '') AS description,"
          + "       COALESCE(ft.depositamt, 0) - COALESCE(ft.paymentamt, 0) AS amount,"
          // Funds transfers / bank fees are GL-item transactions with no FIN_Payment, so
          // fp.isreceipt is NULL. Derive the direction from the transaction amount instead
          // (deposit >= payment → collection) so payment-less transactions still get bucketed.
          + "       COALESCE(fp.isreceipt,"
          + "                CASE WHEN COALESCE(ft.depositamt, 0) >= COALESCE(ft.paymentamt, 0)"
          + "                     THEN 'Y' ELSE 'N' END) AS is_receipt"
          + "  FROM fin_finacc_transaction ft"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = COALESCE(ft.c_bpartner_id, fp.c_bpartner_id)"
          + " WHERE ft.fin_reconciliation_id IS NULL"
          + "   AND ft.status <> 'RPPC'"
          + "   AND ft.processed = 'Y'"
          + "   AND ft.fin_financial_account_id = ?"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate >= ?)"
          + "   AND (CAST(? AS date) IS NULL OR ft.statementdate <= ?)";

  private static final String CANDIDATES_ORDER =
      " ORDER BY ft.statementdate ASC, ft.line ASC";

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
          + "       inv.c_currency_id AS currency_id,"
          + "       COALESCE(cur.iso_code, '') AS currency_iso,"
          + "       SUM(psd.amount) AS outstanding"
          + "  FROM fin_payment_scheduledetail psd"
          + "  JOIN fin_payment_schedule ps ON ps.fin_payment_schedule_id = psd.fin_payment_schedule_invoice"
          + "  JOIN c_invoice inv ON inv.c_invoice_id = ps.c_invoice_id"
          + "  LEFT JOIN c_currency cur ON cur.c_currency_id = inv.c_currency_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = inv.c_bpartner_id"
          + " WHERE psd.fin_payment_detail_id IS NULL"
          + "   AND inv.docstatus = 'CO'"
          + "   AND inv.issotrx = ?"
          + "   AND inv.ad_client_id = ?"
          + "   AND inv.ad_org_id = ANY (?)"
          + "   AND (CAST(? AS date) IS NULL OR inv.dateinvoiced >= ?)"
          + "   AND (CAST(? AS date) IS NULL OR inv.dateinvoiced <= ?)"
          + " GROUP BY ps.fin_payment_schedule_id, inv.c_invoice_id, inv.documentno,"
          + "          inv.dateinvoiced, bp.name, inv.c_currency_id, cur.iso_code"
          + " HAVING SUM(psd.amount) > 0"
          + " ORDER BY inv.dateinvoiced ASC, inv.documentno ASC";

  @Override
  public NeoResponse handle(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String action = qp != null ? qp.get(PARAM_ACTION) : null;
    // "<method> <action>" → its route. A single map lookup instead of one branch per action keeps
    // this dispatcher flat as actions keep being added (Sonar java:S3776).
    ActionRoute route = ROUTES.get(context.getHttpMethod() + " " + action);
    // Any other request (generic list / getById of the W spec) flows through.
    return route != null ? route.apply(this, context) : null;
  }

  // ---------------------------------------------------------------------------
  // GET pendingLines
  // ---------------------------------------------------------------------------

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
    BigDecimal[] tols = loadTolerances(accountId);
    int pendingDateTolDays = tols[0].intValue();
    BigDecimal pendingAmtTolPct = tols[1];
    JSONArray rawLines = new JSONArray();
    // Connection is managed by the DAL's Hibernate Session; don't close it.
    Connection conn = OBDal.getInstance().getConnection();
    List<MatchRuleEngine.Rule> rules = loadRules(conn, accountId);
    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
      int idx = 1;
      ps.setString(idx++, accountId);
      ps.setString(idx++, clientId);
      ps.setArray(idx++, conn.createArrayOf(SQL_VARCHAR, orgs.toArray(new String[0])));
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
          BigDecimal credit = nullSafe(rs.getBigDecimal("cramount"));
          BigDecimal debit = nullSafe(rs.getBigDecimal("dramount"));
          BigDecimal amount = nullSafe(rs.getBigDecimal(KEY_AMOUNT));
          String lineId = rs.getString("fin_bankstatementline_id");
          boolean matched = STATUS_RECONCILED
              .equalsIgnoreCase(StringUtils.trimToEmpty(rs.getString("line_status")));

          JSONObject row = new JSONObject();
          row.put(KEY_ID, lineId);
          row.put(KEY_DATE, formatDate(rs.getTimestamp("datetrx")));
          row.put(KEY_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(KEY_DESCRIPTION)));
          row.put(KEY_PARTNER_NAME, StringUtils.trimToEmpty(rs.getString(COL_PARTNER_NAME)));
          row.put("referenceNo", StringUtils.trimToEmpty(rs.getString("reference_no")));
          row.put(KEY_AMOUNT, amount);
          // Same per-row shape as BankStatementsSupport.mapLineRow so mergeMatchGroups derives the
          // group's reconcileStatus/pendingAmount/txns identically to the imported-statements view.
          // The physical row's pending amount comes from the persisted EM_ETGO_Pending_Amount column
          // (maintained by BankStatementLinePendingAmountHandler). `state` is NOT computed here — it
          // is derived per merged (logical) line in the post-merge loop below.
          row.put("in", credit);
          row.put("out", debit);
          String draftRecId =
              StringUtils.trimToEmpty(rs.getString("draft_reconciliation_id"));
          row.put("matched", matched);
          // EM_ETGO_Pending_Amount is 0 while a transaction is linked, but a "Reactivar"-ed line
          // keeps that link even though nothing is confirmed — so report its FULL amount as pending
          // (progress 0 %, no bar) instead of the stored 0 (which would read as 100 % reconciled).
          row.put("pendingAmount", StringUtils.isNotBlank(draftRecId) ? amount
              : nullSafe(rs.getBigDecimal("em_etgo_pending_amount")));
          row.put("reconcileStatus", matched ? "RECONCILED" : "PENDING");
          // Non-empty only while this line's transaction hangs off a DRAFT reconciliation, i.e. the
          // line was "Reactivar"-ed: it shows as pending, but its own transactions are already
          // matched to it, so the candidates panel pre-selects them and confirming re-processes this
          // very reconciliation instead of composing a new one.
          row.put("draftReconciliationId", draftRecId);
          // 1:N group id (option B): sub-lines of the same reconcile group share this value.
          row.put("matchGroupId", StringUtils.trimToEmpty(rs.getString("match_group_id")));
          row.put("txns", BankStatementsSupport.buildLineTxns(rs, matched));
          rawLines.put(row);
        }
      }
    }

    // Collapse the split sub-lines of a 1:N reconciliation into a single line, so a group shows as
    // one reconciled entry (same as the imported-statements view) instead of N separate sub-lines.
    JSONArray lines = BankStatementsSupport.mergeMatchGroups(rawLines);
    JSONObject data = ReconciliationHandlerSupport.summarizePendingLines(
        lines, account, rules, pendingDateTolDays, pendingAmtTolPct);
    // How many reconciliations of this account are currently in draft. Core allows only ONE editable
    // reconciliation per account, so a non-zero value means a "Reactivar" will first CONFIRM that
    // draft — the UI warns about it up front, in the confirm dialog, instead of after the fact.
    // Deliberately not derived from `lines` (those are date/status filtered, so an off-screen draft
    // would be missed).
    data.put("draftReconciliationCount", ReactivationSupport.draftCount(account));
    return envelope(data);
  }


  // ---------------------------------------------------------------------------
  // GET candidates
  // ---------------------------------------------------------------------------

  NeoResponse buildCandidates(String accountId, String lineId, String docType,
      String dateFrom, String dateTo) throws Exception {
    FIN_BankStatementLine selectedLine =
        StringUtils.isNotBlank(lineId) ? loadLine(lineId) : null;
    // A reconciled line is read-only: return ONLY the movement(s) already linked to it (its 1:1
    // transaction, or every transaction of its 1:N match group) — never the unreconciled pool.
    if (selectedLine != null && selectedLine.getFinancialAccountTransaction() != null) {
      return CandidatesSupport.buildLinkedTransactions(lineId);
    }

    BigDecimal[] candidateTols = loadTolerances(accountId);
    int candidateDateTolDays = candidateTols[0].intValue();
    BigDecimal candidateAmtTolPct = candidateTols[1];

    Set<String> suggestedIds = suggestedTransactionIds(accountId, lineId, candidateDateTolDays);
    // ETP-4965: ids that matched only WITHIN TOLERANCE. Tracked apart from suggestedIds so the row
    // can carry the red "with difference" badge instead of the blue "with suggestion" — the
    // deviation is the point of the row and has to be visible before the user reconciles.
    Set<String> nearMatchIds = new HashSet<>();
    // 1:N: if the selected line amount equals the sum of a signal group (same logic the automatch
    // uses), pre-mark ALL of its operations as suggested — not only a single 1:1 standard match.
    if (selectedLine != null) {
      BigDecimal lineTarget = nullSafe(selectedLine.getCramount())
          .subtract(nullSafe(selectedLine.getDramount()));
      BigDecimal candidateAmtTol =
          AutoMatchSupport.signalGroupTolerance(lineTarget, candidateAmtTolPct);
      for (FIN_FinaccTransaction t : AutoMatchSupport.findSignalGroup(
          accountId, selectedLine, new HashSet<>(), candidateAmtTol, candidateDateTolDays)) {
        suggestedIds.add(t.getId());
      }
      // ETP-4965: same precedence as classifyPendingLine and the automatch preview — an exact 1:1
      // or a signal group wins, and the near match is only offered when neither produced anything.
      // Fresh accumulators: this is a single-line view, with no earlier line to have claimed one.
      if (suggestedIds.isEmpty()) {
        FIN_FinaccTransaction nearMatch = NearMatchSupport.findNearMatch(
            accountId, selectedLine, new HashSet<>(), new ArrayList<>(),
            NearMatchSupport.differenceTolerance(lineTarget, candidateAmtTolPct),
            candidateDateTolDays);
        if (nearMatch != null) {
          nearMatchIds.add(nearMatch.getId());
        }
      }
    }

    JSONArray candidates = new JSONArray();
    Connection conn = OBDal.getInstance().getConnection();
    StringBuilder sql = new StringBuilder(CANDIDATES_SQL);
    boolean filterDocType = StringUtils.isNotBlank(docType);
    if (filterDocType) {
      // docType maps to the payment direction: receipts (collections) vs payments.
      // Mirror the SELECT's derivation so payment-less transactions (transfers, bank
      // fees) are matched by their amount direction instead of being dropped on NULL.
      sql.append(" AND COALESCE(fp.isreceipt,"
          + " CASE WHEN COALESCE(ft.depositamt, 0) >= COALESCE(ft.paymentamt, 0)"
          + " THEN 'Y' ELSE 'N' END) = ?");
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
          row.put(KEY_DESCRIPTION, StringUtils.trimToEmpty(rs.getString(KEY_DESCRIPTION)));
          row.put(KEY_AMOUNT, amount);
          // Pending balance equals the transaction amount for now (partial
          // allocations against invoices are a follow-up).
          row.put(KEY_PENDING_BALANCE, amount);
          row.put(KEY_STATUS, STATUS_PENDING);
          row.put(KEY_SUGGESTED, suggestedIds.contains(id) || nearMatchIds.contains(id));
          row.put(KEY_NEAR_MATCH, nearMatchIds.contains(id));
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
    data.put(KEY_COUNTS, CandidatesSupport.candidateCounts(accountId, dateFrom, dateTo));
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
    Boolean receipt = ReconciliationHandlerSupport.resolveInvoiceDirection(this, docType, lineId);
    if (account == null || receipt == null) {
      JSONObject empty = new JSONObject();
      empty.put(ACTION_CANDIDATES, candidates);
      empty.put(KEY_COUNTS, CandidatesSupport.candidateCounts(accountId, dateFrom, dateTo));
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
      ps.setArray(idx++, conn.createArrayOf(SQL_VARCHAR, orgs.toArray(new String[0])));
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
          // Invoice currency so the UI can flag documents in a currency other than the account's
          // (multi-currency reconciliation) — see ReconciliationSplitPanel currency badge.
          String candidateCurrencyIso = StringUtils.trimToEmpty(rs.getString("currency_iso"));
          row.put("currency", candidateCurrencyIso);
          row.put("currencyId", rs.getString("currency_id"));
          // Foreign-currency invoice: also emit its equivalent in the account currency (the rate
          // used when actually reconciling) so the panel can show a EUR-style total alongside the
          // foreign amount. A missing rate is not fatal — the row simply keeps only the foreign
          // amount and the panel falls back to showing that alone.
          if (account.getCurrency() != null
              && !account.getCurrency().getISOCode().equals(candidateCurrencyIso)) {
            ReconciliationHandlerSupport.appendAccountEquivalent(
                row, rs.getString("c_invoice_id"), account, signed);
          }
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
    data.put(KEY_COUNTS, CandidatesSupport.candidateCounts(accountId, dateFrom, dateTo));
    return envelope(data);
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
    return suggestedTransactionIds(accountId, lineId, AutoMatchSupport.DEFAULT_DATE_TOL_DAYS);
  }

  Set<String> suggestedTransactionIds(String accountId, String lineId, int dateTolDays) {
    return suggestedTransactionIds(accountId, lineId, dateTolDays, new ArrayList<>());
  }

  /**
   * Same as above, but honoring {@code excluded} — transactions the caller already consumed for an
   * earlier line in the same run, so this line's suggestion does not collide with one already
   * claimed. See {@link AutoMatchSupport#standardMatch} for why this matters: without it, N pending
   * lines of the identical amount all get offered the SAME transaction by Core, and every line past
   * the first ends up with no 1:1 suggestion at all.
   */
  Set<String> suggestedTransactionIds(String accountId, String lineId, int dateTolDays,
      List<FIN_FinaccTransaction> excluded) {
    Set<String> ids = new HashSet<>();
    if (StringUtils.isBlank(lineId)) {
      return ids;
    }
    FIN_BankStatementLine line = OBDal.getInstance().get(FIN_BankStatementLine.class, lineId);
    if (line == null) {
      return ids;
    }
    FIN_FinancialAccount account = loadAccount(accountId);
    FIN_MatchedTransaction matched =
        AutoMatchSupport.standardMatch(account, line, dateTolDays, excluded);
    if (matched != null) {
      ids.add(matched.getTransaction().getId());
    }
    return ids;
  }

  /**
   * "Reactivar" — the lighter alternative behind the same "Desconciliar (N)" split button. Where
   * {@link #removeOperation(JSONObject)} always detaches/deletes with no further intent, this one is
   * framed as "put these back so they can be matched again" — but with a shared-header batch (see
   * {@link #applySuggestions}) there is no reconciliation-wide DRAFT state left to hand back to the
   * user any more: reactivating one line would leave every OTHER line sharing that header pending
   * too. So it runs the exact same detach-and-reprocess mechanics as {@code removeOperation}
   * ({@link ReconciliationHandlerSupport#removeSelectedFromReconciliations}) — reactivate the
   * reconciliation, remove just the selected transactions, re-confirm it — and the freed
   * transactions simply return to the normal candidate pool (no special pre-selection).
   *
   * <p>Auto-created movements in the selection are still fully deleted (same {@code
   * com.etendoerp.payment.removal} utilities as {@code removeOperation}) — a payment that only
   * existed to back this reconciliation has nothing worth preserving.
   */
  NeoResponse reactivateSelected(JSONObject body) throws Exception {
    String accountId = body.optString(KEY_FINANCIAL_ACCOUNT_ID, null);
    String statementLineId = body.optString(KEY_STATEMENT_LINE_ID, null);
    List<String> transactionIds = ReconciliationHandlerSupport.readTransactionIds(body);
    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(statementLineId)
        || transactionIds.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "financialAccountId, statementLineId and at least one transactionId are required");
    }

    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_ACCOUNT_NOT_FOUND + accountId);
    }
    FIN_BankStatementLine line = loadLine(statementLineId);
    if (line == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          MSG_STATEMENT_LINE_NOT_FOUND + statementLineId);
    }
    if (!belongsToAccount(line, accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_LINE_NOT_IN_ACCOUNT);
    }

    Map<String, FIN_Reconciliation> recById = new java.util.LinkedHashMap<>();
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new java.util.LinkedHashMap<>();
    NeoResponse groupError = ReconciliationHandlerSupport.groupSelectedByReconciliation(
        this, accountId, transactionIds, recById, selectedByRec);
    if (groupError != null) {
      return groupError;
    }
    NeoResponse periodError = ReconciliationHandlerSupport.guardOpenPeriods(this, recById.values());
    if (periodError != null) {
      return periodError;
    }
    // Why each failure happened, keyed by transaction id. The removal helpers swallow their
    // exceptions so one failure does not abort the batch; without this accumulator the reason
    // reached the server log only, and the client could say WHICH transactions failed but never WHY.
    java.util.Map<String, String> failureReasons = new java.util.LinkedHashMap<>();
    ReconciliationHandlerSupport.removeSelectedFromReconciliations(
        this, account, recById, selectedByRec, failureReasons);

    // Core's own removal utilities commit mid-flow, so re-check the ACTUAL post-state of every
    // requested transaction — same rationale as removeOperation.
    List<String> doneIds = new ArrayList<>();
    List<String> failedIds = new ArrayList<>();
    for (String id : transactionIds) {
      FIN_FinaccTransaction trx = loadTransaction(id);
      if (trx == null || trx.getReconciliation() == null) {
        doneIds.add(id);
      } else {
        failedIds.add(id);
      }
    }

    // Collapse split sub-lines back into a single pending line when the whole group is now
    // unmatched — same cleanup removeOperation performs.
    normalizeReactivatedMatchGroup(line);

    BigDecimal updatedBalance = ReactivationSupport.currentBalance(account);
    JSONObject data = new JSONObject();
    data.put("reactivated", failedIds.isEmpty());
    data.put(KEY_STATEMENT_LINE_ID, statementLineId);
    data.put("transactionIds", new JSONArray(doneIds));
    data.put("failedTransactionIds", new JSONArray(failedIds));
    // The cause of the first failure, translated. The commonest by far is a closed accounting
    // period, which the user can act on — but only if it is actually shown, so it travels with the
    // 200 rather than staying in the log. Failures within one request share a cause in practice
    // (one closed period, one Core guard), so a single message beats a per-transaction list the
    // client has no room to render.
    String failureReason = ReconciliationHandlerSupport.firstFailureReason(failedIds, failureReasons);
    if (StringUtils.isNotBlank(failureReason)) {
      data.put(KEY_FAILURE_REASON, failureReason);
    }
    data.put(KEY_UPDATED_BALANCE, updatedBalance);
    return envelope(data);
  }

  // ---------------------------------------------------------------------------
  // POST reconcileGroup
  // ---------------------------------------------------------------------------

  /**
   * Reconciles one statement line against the selected operations and/or invoices.
   *
   * <p><b>ETP-4965 — the difference is funded before composing.</b> When the operations fall short
   * of the line by a gap within the account's amount tolerance,
   * {@link ReconciliationDifferenceSupport#applyInlineDifference} creates the compensating GL-item
   * movement and joins it to the selection, so the sum matches the line exactly and Core leaves both
   * split halves reconciled instead of a pending remainder nothing can settle.
   *
   * <p><b>Why that helper may roll back.</b> The guards run in the only order the data allows, and
   * that order is not fully safe on its own: {@code payInvoices} above WRITES payments and
   * transactions, and the gap is not knowable until it has. So by the time a missing GL Item
   * Difference is detected on the invoice path, writes are already pending — and a returned
   * {@code NeoResponse.error} commits rather than rolls back (see
   * {@link ReconciliationDifferenceSupport}'s header javadoc). The helper therefore rolls back
   * explicitly before returning that 400, the same way {@link ReconciliationFlowSupport#compose}
   * does when Core rejects the reconciliation.
   */
  NeoResponse reconcileGroup(JSONObject body) throws Exception {
    String accountId = body.optString(KEY_FINANCIAL_ACCOUNT_ID, null);
    String statementLineId = body.optString(KEY_STATEMENT_LINE_ID, null);
    List<String> operationIds = readOperationIds(body);
    JSONArray invoiceSpecs = body.optJSONArray(KIND_INVOICES);
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
          MSG_STATEMENT_LINE_NOT_FOUND + statementLineId);
    }
    if (!belongsToAccount(line, accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_LINE_NOT_IN_ACCOUNT);
    }
    if (line.getFinancialAccountTransaction() != null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, MSG_LINE_ALREADY_RECONCILED);
    }

    // Pay each selected unpaid invoice (creates payment + auto-creates its transaction); the new
    // transaction ids join operationIds so the standard reconcile below matches them to the line.
    // paymentMethodId is the single method chosen in the reconciliation modal, applied to every
    // invoice payment created here — an already-existing transaction (operationIds) keeps its own.
    if (hasInvoices) {
      String paymentMethodId = body.optString("paymentMethodId", null);
      // ETP-4797: opt-in, off by default. Writes off the shortfall when the line settles the
      // invoice for less than its outstanding amount, so the invoice is fully paid instead of
      // keeping a residual balance. The UI only offers it for a single selected invoice.
      boolean writeoffDifference = body.optBoolean("writeoffDifference", false);
      NeoResponse payError = ReconciliationWriteoffSupport.payInvoices(
          account, line, invoiceSpecs, operationIds, TOLERANCE, paymentMethodId,
          writeoffDifference);
      if (payError != null) {
        return payError;
      }
    }

    NeoResponse opError = ReconciliationFlowSupport.validateOperations(
        operationIds, accountId, line, this::loadTransaction, TOLERANCE);
    if (opError != null) {
      return opError;
    }

    // ETP-4965: fund a within-tolerance shortfall with a GL-item movement BEFORE composing, so the
    // operations sum exactly to the line and Core leaves every split half reconciled instead of a
    // dangling pending remainder. Mutates operationIds on success.
    operationIds = new ArrayList<>(operationIds);
    NeoResponse diffError = ReconciliationDifferenceSupport.applyInlineDifference(
        this, account, line, operationIds, body, hasInvoices);
    if (diffError != null) {
      return diffError;
    }

    return ReconciliationFlowSupport.compose(this, account, line, operationIds);
  }

  /**
   * Reuses the financial account's open draft reconciliation (Core allows exactly one), or creates
   * a fresh one — the same lookup Classic's {@code MatchStatementActionHandler} does before
   * matching. Lets a whole automatch batch ({@link #applySuggestions}) share ONE
   * {@code FIN_Reconciliation} header across every accepted group, instead of a document per
   * statement line.
   */
  FIN_Reconciliation getOrCreateDraftReconciliation(FIN_FinancialAccount account) {
    FIN_Reconciliation draft = TransactionsDao.getLastReconciliation(account, "N");
    return draft != null ? draft : addNewDraftReconciliation(account);
  }

  /**
   * Tags the line's match group (when the match will split it) and runs Core's standard
   * {@code matchBankStatementLine} for a single statement line into {@code rec}. Does not create or
   * process the reconciliation — the caller decides whether {@code rec} is fresh (one manual match,
   * see {@link ReconciliationFlowSupport#compose}) or shared across a whole automatch batch, and
   * processes it once after every group has been matched in.
   */
  void matchInto(FIN_BankStatementLine line, List<String> operationIds,
      FIN_Reconciliation rec) {
    // Grouping (option B): tag the original line with a fresh match-group id BEFORE the match so
    // the split sub-lines inherit it (DalUtil.copy copies all EM_ properties). The UI re-groups
    // the resulting sub-lines by this id. Only needed when the match will actually split the
    // line — see willSplitLine. If the line already carries a group id (we're reconciling the
    // pending remainder of an EXISTING partial group), reuse it so the new match stays in the same
    // group instead of fragmenting into a fresh one (ETP-4502 iteration 5).
    if (willSplitLine(line, operationIds)
        && StringUtils.isBlank(ReactivationSupport.readMatchGroupId(line))) {
      tagMatchGroup(line);
    }
    matchBankStatementLine(line, operationIds, rec);
  }

  // ---------------------------------------------------------------------------
  // GET autoMatch (preview — does not mutate any data)
  // ---------------------------------------------------------------------------

  NeoResponse buildAutoMatch(String accountId) throws Exception {
    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_ACCOUNT_NOT_FOUND + accountId);
    }

    Connection conn = OBDal.getInstance().getConnection();
    List<MatchRuleEngine.Rule> rules = loadRules(conn, accountId);

    BigDecimal[] autoTols = loadTolerances(accountId);
    int autoDateTolDays = autoTols[0].intValue();
    BigDecimal autoAmtTolPct = autoTols[1];

    // Collect all pending lines for this account.
    List<FIN_BankStatementLine> pendingLines = loadPendingLines(accountId);

    JSONArray groups = new JSONArray();
    Set<String> usedTxnIds = new HashSet<>();
    // Fed into the standard algorithm for every subsequent line — mirrors Classic's own
    // runAutoMatchingAlgorithm accumulator, so N pending lines of the same amount each get their
    // own suggestion in one run instead of all colliding on the same Core-picked transaction.
    List<FIN_FinaccTransaction> excludedTxns = new ArrayList<>();
    int opsToLink = 0;
    int willCreate = 0;

    for (FIN_BankStatementLine line : pendingLines) {
      // Pass 1 (1:1): standard algorithm — uses lazy evaluation so findSignalGroup is not called
      // when a 1:1 match is already found, avoiding an unnecessary DB query.
      Set<String> suggested =
          suggestedTransactionIds(accountId, line.getId(), autoDateTolDays, excludedTxns);
      suggested.removeAll(usedTxnIds);
      FIN_FinaccTransaction txn1to1 = suggested.isEmpty() ? null : loadTransaction(suggested.iterator().next());
      if (txn1to1 != null) {
        usedTxnIds.add(txn1to1.getId());
        excludedTxns.add(txn1to1);
        groups.put(AutoMatchSupport.buildStandardGroup(line, txn1to1, FIN_MatchedTransaction.STRONG));
        opsToLink++;
      } else {
        int[] delta = AutoMatchSupport.matchFallback(accountId, line, usedTxnIds, excludedTxns,
            rules, groups, autoDateTolDays, autoAmtTolPct);
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
    ReconciliationKpiTelemetry.emitBankMatchAttempted(
        pendingLines.size(), groups.length(), opsToLink);
    return envelope(data);
  }

  // ---------------------------------------------------------------------------
  // POST applySuggestions (commit — creates payments + reconciles)
  // ---------------------------------------------------------------------------

  /**
   * Holds one {@code prepareGroup} success: the resolved line + the final operation ids to match.
   * Package-visible (not private) so {@link ReconciliationHandlerSupport#matchAndProcessBatch} can
   * read {@code line}/{@code operationIds} directly.
   */
  static final class PreparedGroup {
    final FIN_BankStatementLine line;
    final List<String> operationIds;

    PreparedGroup(FIN_BankStatementLine line, List<String> operationIds) {
      this.line = line;
      this.operationIds = operationIds;
    }
  }

  /**
   * Commits every accepted automatch group in ONE {@code FIN_Reconciliation} document — Core's own
   * "one reconciliation per statement" model, instead of a header per statement line. Two passes:
   * <ol>
   *   <li>{@link ReconciliationFlowSupport#prepareGroup} validates every group (line exists, not
   *       already reconciled, invoice payments created, operations within the line amount) — an
   *       invalid group is reported in {@code results[]} without ever touching the shared
   *       reconciliation;</li>
   *   <li>every group that passed validation is matched into ONE {@link #getOrCreateDraftReconciliation}
   *       result via {@link #matchInto}, then that single document is processed once.</li>
   * </ol>
   * Not atomic across groups: Core's matching services commit mid-flow, so a failure matching group
   * <em>k</em> does not roll back groups {@code 1..k-1} already matched into the same document — it
   * is reported in {@code results[]} and the rest of the batch still proceeds.
   */
  NeoResponse applySuggestions(JSONObject body) throws Exception {
    String accountId = body.optString(KEY_FINANCIAL_ACCOUNT_ID, null);
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
    List<PreparedGroup> prepared = new ArrayList<>();
    for (int i = 0; i < groupsJson.length(); i++) {
      JSONObject groupEntry = groupsJson.optJSONObject(i);
      if (groupEntry == null) {
        continue;
      }
      NeoResponse prepError = ReconciliationFlowSupport.prepareGroup(
          this, account, groupEntry, prepared);
      if (prepError != null) {
        results.put(prepError.getBody());
      }
    }

    // Matching every prepared group into the shared reconciliation and processing it once lives in
    // ReconciliationHandlerSupport — extracted so this method's cognitive complexity stays under the
    // Sonar limit (java:S3776); behavior is unchanged, every seam still runs on THIS handler instance.
    int[] successfulGroups = {0};
    if (!prepared.isEmpty()) {
      NeoResponse batchError = ReconciliationHandlerSupport.matchAndProcessBatch(
          this, account, prepared, results, successfulGroups);
      if (batchError != null) {
        return batchError;
      }
    }

    JSONObject data = new JSONObject();
    data.put("applied", results.length());
    data.put("results", results);
    ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(
        groupsJson.length(), results.length(), successfulGroups[0]);
    return NeoResponse.createdWithData(data);
  }

  // ---------------------------------------------------------------------------
  // POST reactivate (undo a reconciliation for a single statement line)
  // ---------------------------------------------------------------------------

  /**
   * Reactivates (undoes) the reconciliation for a single statement line. Delegates ALL reactivation
   * logic to the {@code com.etendoerp.payment.removal} module — this handler never reimplements it.
   * Body: {@code { financialAccountId, statementLineId }}.
   *
   * <p>A shared-header batch (see {@link #applySuggestions}) can hold transactions from OTHER
   * statement lines too, so this is scoped to just {@code line}'s own transactions — itself plus any
   * ETGO match-group siblings ({@link #transactionsOfLineIn}) — never the whole reconciliation.
   *
   * <p>Sequence:
   * <ol>
   *   <li>validate inputs + load account/line + ownership check;</li>
   *   <li>resolve the line's transaction and its reconciliation (409 when the line is not
   *       reconciled);</li>
   *   <li>accounting-period guard via
   *       {@link Utilities#checkPeriod(String, String, String, java.util.Date)} on the
   *       reconciliation's accounting date (409 when the period is closed);</li>
   *   <li>resolve this line's own transactions within that reconciliation. When they are ALL of the
   *       reconciliation's transactions, undo it as a unit via {@link #undoReconciliation} (deletes
   *       the document); otherwise detach just those via {@link ReconciliationHandlerSupport#detachSelected}
   *       so the reconciliation is kept, reprocessed, with the OTHER lines' transactions still
   *       reconciled.</li>
   * </ol>
   */
  NeoResponse reactivate(JSONObject body) throws Exception {
    String accountId = body.optString(KEY_FINANCIAL_ACCOUNT_ID, null);
    String statementLineId = body.optString(KEY_STATEMENT_LINE_ID, null);
    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(statementLineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "financialAccountId and statementLineId are required");
    }

    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_ACCOUNT_NOT_FOUND + accountId);
    }
    FIN_BankStatementLine line = loadLine(statementLineId);
    if (line == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          MSG_STATEMENT_LINE_NOT_FOUND + statementLineId);
    }
    if (!belongsToAccount(line, accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_LINE_NOT_IN_ACCOUNT);
    }

    FIN_FinaccTransaction trx = line.getFinancialAccountTransaction();
    if (trx == null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Statement line is not reconciled");
    }
    FIN_Reconciliation rec = trx.getReconciliation();
    if (rec == null) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Statement line transaction is not linked to a reconciliation");
    }

    // Accounting-period guard: refuse to undo into a closed period. checkPeriod throws an
    // OBException (mapped to 409 below) when the period of the reconciliation date is closed.
    java.util.Date acctDate = rec.getTransactionDate();
    try {
      checkPeriod(rec.getClient().getId(), rec.getOrganization().getId(),
          rec.getEntity().getTableId(), acctDate);
    } catch (OBException e) {
      log.warn("reactivate blocked by closed period for reconciliation {}: {}", rec.getId(),
          e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "The accounting period is closed and the reconciliation cannot be reactivated: "
              + e.getMessage());
    }

    // Unpost BEFORE anything is captured: ResetAccounting runs native SQL and clears the session, so
    // `rec`, `line` and `matched` would all be detached if this ran later. Everything below is
    // re-read afterwards for that reason.
    ReconciliationHandlerSupport.unpostBeforeUndo(rec.getId());
    rec = OBDal.getInstance().get(FIN_Reconciliation.class, rec.getId());
    line = loadLine(statementLineId);

    List<FIN_FinaccTransaction> matched = transactionsOfLineIn(line, rec);
    if (ReconciliationHandlerSupport.coversReconciliation(rec, matched)) {
      // Unlike the two batch endpoints, this one lets the failure propagate: runPostAction turns it
      // into an error response, so the caller already learns something went wrong.
      undoReconciliation(account, rec, matched);
    } else {
      // The accumulator is intentionally discarded HERE and only here. detachSelected swallows its
      // per-transaction failures by design, and this endpoint — unlike removeOperation and
      // reactivateSelected — never re-checks the post-state and always answers {reactivated:true}.
      // Reporting a reason would therefore mean also giving it a failedTransactionIds contract it
      // does not have, which is a product change, not a compile fix. Pre-existing gap, deliberately
      // left as-is; see the un-reconcile section of docs/generated-custom-windows/financial-account.md.
      ReconciliationHandlerSupport.detachSelected(this, matched, new java.util.LinkedHashMap<>());
    }
    normalizeReactivatedMatchGroup(line);

    BigDecimal updatedBalance = ReactivationSupport.currentBalance(account);
    JSONObject data = new JSONObject();
    data.put("reactivated", true);
    data.put(KEY_STATEMENT_LINE_ID, statementLineId);
    data.put(KEY_UPDATED_BALANCE, updatedBalance);
    return envelope(data);
  }

  /**
   * Every transaction currently backing {@code line} — itself plus any ETGO match-group siblings —
   * that belongs to {@code rec}. A shared-header batch (see {@link #applySuggestions}) can hold
   * transactions from OTHER statement lines too, so this scopes a whole-line undo/reactivate to just
   * the ones the clicked line owns, never the reconciliation's full transaction list.
   */
  List<FIN_FinaccTransaction> transactionsOfLineIn(FIN_BankStatementLine line,
      FIN_Reconciliation rec) {
    List<FIN_FinaccTransaction> result = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    ReconciliationHandlerSupport.addTransactionOwnedByRec(line, rec, result, seenIds);
    String groupId = ReactivationSupport.readMatchGroupId(line);
    if (StringUtils.isNotBlank(groupId) && line.getBankStatement() != null) {
      for (FIN_BankStatementLine sibling : loadMatchGroupLines(line.getBankStatement(), groupId)) {
        ReconciliationHandlerSupport.addTransactionOwnedByRec(sibling, rec, result, seenIds);
      }
    }
    return result;
  }

  /**
   * Un-reconciles one OR MORE operations ("desvincular") from a statement line's reconciliation,
   * leaving any non-selected operations reconciled. Body:
   * {@code { financialAccountId, statementLineId, transactionIds: [...] }} (a single
   * {@code transactionId} is also accepted for the per-row button).
   *
   * <p>Branch on whether the selection covers the WHOLE reconciliation:
   * <ul>
   *   <li><b>All operations selected</b> (or the reconciliation's only one): undoing them equals a
   *       whole-line reactivate, so it delegates to {@link #undoReconciliation} +
   *       {@link #normalizeReactivatedMatchGroup} — deletes the reconciliation, reverses the
   *       auto-created payments (invoices back to unpaid) and collapses the split sub-lines into one
   *       pending line.</li>
   *   <li><b>A subset</b>: for each selected transaction,
   *       {@link ReconciliationRemovalUtil#removeTransactionFromReconciliation} detaches it and
   *       re-processes the reconciliation so the rest stay reconciled (the document is kept); an
   *       auto-created payment is then reversed via {@link PaymentRemovalUtil#reactivateAndRemove}
   *       (or {@link TransactionRemovalUtil#reactivateAndRemove} for a rule transaction), a
   *       pre-existing transaction is only un-reconciled.</li>
   * </ul>
   * Each freed sub-line keeps its match-group id and amount; its {@code EM_ETGO_Pending_Amount} is
   * re-set by {@link BankStatementLinePendingAmountHandler}, so {@code mergeMatchGroups} folds it
   * back into the line's remaining on the next {@code pendingLines} load. See ETP-4502 iteration 5.
   */
  NeoResponse removeOperation(JSONObject body) throws Exception {
    String accountId = body.optString(KEY_FINANCIAL_ACCOUNT_ID, null);
    String statementLineId = body.optString(KEY_STATEMENT_LINE_ID, null);
    List<String> transactionIds = ReconciliationHandlerSupport.readTransactionIds(body);
    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(statementLineId)
        || transactionIds.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "financialAccountId, statementLineId and at least one transactionId are required");
    }

    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_ACCOUNT_NOT_FOUND + accountId);
    }
    FIN_BankStatementLine line = loadLine(statementLineId);
    if (line == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          MSG_STATEMENT_LINE_NOT_FOUND + statementLineId);
    }
    if (!belongsToAccount(line, accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_LINE_NOT_IN_ACCOUNT);
    }

    // Resolve + validate every selected transaction and GROUP them by their reconciliation, guard
    // against closed accounting periods, then perform the per-reconciliation removal. Each step is
    // delegated to ReconciliationHandlerSupport (extracted to keep this method's cognitive
    // complexity and this class's method count under the Sonar limits); the DAL/period seams are
    // invoked back through this handler instance so the unit-test spies keep intercepting them. A
    // line reconciled in several steps can span MORE THAN ONE reconciliation sharing the same match
    // group, so each is handled independently.
    java.util.Map<String, FIN_Reconciliation> recById = new java.util.LinkedHashMap<>();
    java.util.Map<String, List<FIN_FinaccTransaction>> selectedByRec = new java.util.LinkedHashMap<>();
    NeoResponse groupError = ReconciliationHandlerSupport.groupSelectedByReconciliation(
        this, accountId, transactionIds, recById, selectedByRec);
    if (groupError != null) {
      return groupError;
    }
    NeoResponse periodError = ReconciliationHandlerSupport.guardOpenPeriods(this, recById.values());
    if (periodError != null) {
      return periodError;
    }
    // Why each failure happened, keyed by transaction id. The removal helpers swallow their
    // exceptions so one failure does not abort the batch; without this accumulator the reason
    // reached the server log only, and the client could say WHICH transactions failed but never WHY.
    java.util.Map<String, String> failureReasons = new java.util.LinkedHashMap<>();
    ReconciliationHandlerSupport.removeSelectedFromReconciliations(
        this, account, recById, selectedByRec, failureReasons);

    // Core's own removal utilities commit mid-flow (SessionHandler#commitAndStart), so a failure
    // partway through the batch does not roll back what already persisted, and
    // removeSelectedFromReconciliations no longer aborts on one failure (see its javadoc). Re-check
    // each requested transaction's ACTUAL state — whether it is still linked to a reconciliation —
    // rather than trusting "no exception was thrown", so the response never claims total failure
    // when part of the batch genuinely went through.
    List<String> removedIds = new ArrayList<>();
    List<String> failedIds = new ArrayList<>();
    for (String id : transactionIds) {
      FIN_FinaccTransaction trx = loadTransaction(id);
      if (trx == null || trx.getReconciliation() == null) {
        removedIds.add(id);
      } else {
        failedIds.add(id);
      }
    }

    // Collapse the split sub-lines back into a single pending line if the whole group is now
    // unmatched (no-ops when some sub-lines are still reconciled).
    normalizeReactivatedMatchGroup(line);

    BigDecimal updatedBalance = ReactivationSupport.currentBalance(account);
    JSONObject data = new JSONObject();
    data.put("removed", failedIds.isEmpty());
    data.put(KEY_STATEMENT_LINE_ID, statementLineId);
    data.put("transactionIds", new JSONArray(removedIds));
    data.put("failedTransactionIds", new JSONArray(failedIds));
    // The cause of the first failure, translated. The commonest by far is a closed accounting
    // period, which the user can act on — but only if it is actually shown, so it travels with the
    // 200 rather than staying in the log. Failures within one request share a cause in practice
    // (one closed period, one Core guard), so a single message beats a per-transaction list the
    // client has no room to render.
    String failureReason = ReconciliationHandlerSupport.firstFailureReason(failedIds, failureReasons);
    if (StringUtils.isNotBlank(failureReason)) {
      data.put(KEY_FAILURE_REASON, failureReason);
    }
    data.put(KEY_UPDATED_BALANCE, updatedBalance);
    return envelope(data);
  }

  /**
   * Undoes the reconciliation as a UNIT, then cleans up the bank-statement match and the
   * auto-created movements. Doing it per-transaction (an earlier approach) re-processed the
   * reconciliation once per matched transaction; in a 1:N group that left the transactions in mixed
   * states (one {@code RDNC}, the next {@code PWNC}) because each re-process changed the status the
   * next removal keyed off. Instead:
   * <ol>
   *   <li>process the account's draft reconciliations first (Etendo only lets you reactivate the
   *       latest completed one — ordering pre-step);</li>
   *   <li>{@link ReconciliationRemovalUtil#reactivateAndRemoveReconciliation(FIN_Reconciliation)} —
   *       one {@code processReconciliation("R")} pass returns EVERY transaction to its
   *       pre-reconciliation "not cleared" state by direction (inflow → {@code RDNC}, outflow →
   *       {@code PWNC}) consistently, and deletes the reconciliation;</li>
   *   <li>{@link ReactivationSupport#unmatchBankStatementLine(FIN_FinaccTransaction)} for each
   *       transaction — clears the line's {@code financialAccountTransaction}, the step the module's
   *       reconciliation-level undo skips (it detaches the transaction but leaves the statement line
   *       pointing at it, so the line would still read "reconciled");</li>
   *   <li>delete the auto-created movements: invoice payments via
   *       {@link PaymentRemovalUtil#reactivateAndRemove(FIN_Payment)} (restoring the invoice), rule
   *       transactions via {@link TransactionRemovalUtil#reactivateAndRemove(String)}.</li>
   * </ol>
   * Pre-existing (manually matched) transactions are kept — un-reconciled, un-matched, and their
   * "not cleared" status restored by direction via
   * {@link ReactivationSupport#restoreNotClearedStatus(FIN_FinaccTransaction)}.
   * Package-private test seam.
   */
  void undoReconciliation(FIN_FinancialAccount account, FIN_Reconciliation rec,
      List<FIN_FinaccTransaction> matched) throws Exception {
    List<FIN_Reconciliation> drafts = ReconciliationRemovalUtil.getDraftReconciliation(account);
    ReconciliationRemovalUtil.processAllReconciliationInDraft(drafts);
    ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(rec);
    for (FIN_FinaccTransaction t : matched) {
      ReactivationSupport.unmatchBankStatementLine(t);
    }
    for (FIN_FinaccTransaction t : matched) {
      ReconciliationHandlerSupport.reverseMatchedTransaction(this, t);
    }
  }

  /**
   * Reverses one matched transaction's auto-created movement (or restores its "not cleared" status)
   * as the last cleanup step of undoing a reconciliation. Catches and logs its own failure instead
   * of letting it abort {@link #undoReconciliation}'s loop: Core's reversal utilities ({@link
   * PaymentRemovalUtil#reactivateAndRemove}) commit mid-flow, so a failure on transaction K does not
   * roll back transactions 1..K-1 that already committed — aborting here would only leave K+1..N
   * unprocessed too, compounding the inconsistency instead of limiting it. The reconciliation itself
   * is already undone by the time this runs (see {@code reactivateAndRemoveReconciliation} above),
   * so a failed reversal here is a rare, logged, individually-recoverable leftover, not a half-undone
   * reconciliation.
   */

  /**
   * After a group reconciliation is reactivated, its split sub-lines remain physically duplicated
   * in FIN_BankStatementLine while the UI only re-groups them visually. That leaves the matching
   * engine operating on one residual sub-line amount (for example 25.30) instead of the original
   * unsplit amount (50.60). When the line carries ETGO's {@code matchGroupId}, collapse every
   * unmatched sibling in that same bank statement back into a single physical row and clear the
   * marker so the line returns to the normal pending pool.
   *
   * <p>Safety rules:
   * <ul>
   *   <li>Only ETGO-tagged groups are normalized (never plain duplicate line numbers).</li>
   *   <li>All siblings must be in the same bank statement and still unmatched.</li>
   *   <li>The selected line is the anchor; its metadata is preserved, only the amounts are summed.</li>
   * </ul>
   */
  FIN_BankStatementLine normalizeReactivatedMatchGroup(FIN_BankStatementLine line) throws Exception {
    if (line == null || line.getBankStatement() == null) {
      return line;
    }
    String groupId = ReactivationSupport.readMatchGroupId(line);
    if (StringUtils.isBlank(groupId)) {
      return line;
    }

    List<FIN_BankStatementLine> siblings = loadMatchGroupLines(line.getBankStatement(), groupId);
    if (siblings.isEmpty()) {
      return line;
    }

    FIN_BankStatementLine anchor = ReactivationSupport.anchorOf(line, siblings);

    if (siblings.size() == 1) {
      ReactivationSupport.clearMatchGroupId(anchor);
      OBDal.getInstance().save(anchor);
      OBDal.getInstance().flush();
      return anchor;
    }

    if (!ReactivationSupport.canCollapse(line, siblings)) {
      return line;
    }

    ReactivationSupport.collapseSiblings(anchor, siblings);
    return anchor;
  }

  /** Loads every ETGO-tagged sibling of the same bank-statement match group. */
  List<FIN_BankStatementLine> loadMatchGroupLines(FIN_BankStatement statement, String groupId) {
    org.openbravo.base.model.Property prop =
        ReactivationSupport.extensionProperty(FIN_BankStatementLine.ENTITY_NAME,
            ReactivationSupport.COL_MATCH_GROUP);
    if (statement == null || StringUtils.isBlank(groupId) || prop == null) {
      return Collections.emptyList();
    }
    OBCriteria<FIN_BankStatementLine> c =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    c.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_BANKSTATEMENT, statement));
    c.add(Restrictions.eq(prop.getName(), groupId));
    c.addOrder(Order.asc(FIN_BankStatementLine.PROPERTY_LINENO));
    c.addOrder(Order.asc(FIN_BankStatementLine.PROPERTY_CREATIONDATE));
    c.addOrder(Order.asc(FIN_BankStatementLine.PROPERTY_ID));
    @SuppressWarnings("unchecked")
    List<FIN_BankStatementLine> rows = c.list();
    return rows;
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
    // An explicit spec description wins, so a difference posting can say WHY the row exists (the
    // way the cash close does) instead of only echoing the statement line's own text.
    String specDescription = StringUtils.trimToNull(spec.optString(KEY_DESCRIPTION, null));
    trx.setDescription(specDescription != null ? specDescription
        : StringUtils.trimToEmpty(line.getDescription()));
    trx.setLineNo(AutoMatchSupport.nextTransactionLineNo(account.getId()));
    trx.setDepositAmount(isDeposit ? absAmount : BigDecimal.ZERO);
    trx.setPaymentAmount(isDeposit ? BigDecimal.ZERO : absAmount);
    trx.setStatus(isDeposit ? "RPAE" : "RPAP");
    trx.setGLItem(glItem);
    attachOptional(bpartnerId, BusinessPartner.class, trx::setBusinessPartner);
    AccountingDimensionsSupport.applyRuleDimensions(trx, spec,
        () -> headerDimensionsOf(account.getId()));
    // Rule-origin transaction is auto-created — flag it so the reactivate flow deletes it.
    ReactivationSupport.markAutoCreated(trx);
    OBDal.getInstance().save(trx);
    OBDal.getInstance().flush();

    // Process the transaction (sets processed = Y) so it can be reconciled.
    FIN_TransactionProcess.doTransactionProcess(PROCESS_ACTION, trx);
    OBDal.getInstance().flush();
    return trx.getId();
  }

  /**
   * The account's active header dimensions, memoized per handler instance. Fails <b>open</b> on a
   * configuration-lookup error: a tenant whose accounting setup cannot be read keeps the previous
   * behaviour for the concept and the business partner, and simply gets no dimensions assigned,
   * rather than having the whole reconciliation fail.
   */
  Set<String> headerDimensionsOf(String accountId) {
    return headerDimensionsByAccount.computeIfAbsent(accountId, id -> {
      try {
        return AccountingDimensionsSupport.activeHeaderDimensionsForAccount(id,
            AccountingDimensionsSupport.DOCBASETYPE_FAT);
      } catch (Exception e) {
        log.warn("Could not resolve active accounting dimensions for account {}", id, e);
        return Collections.emptySet();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Seams (package-private to allow unit tests to stub the DAL / Classic layer)
  // ---------------------------------------------------------------------------

  FIN_FinancialAccount loadAccount(String accountId) {
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
  }

  /**
   * Loads per-account reconciliation tolerances via JDBC (DAL getters unavailable until entity
   * regeneration). Returns [dateTolDays, amtTolPct] with safe defaults (3 days, 0%).
   */
  BigDecimal[] loadTolerances(String accountId) {
    String sql = "SELECT COALESCE(em_etgo_date_tolerance, 3),"
        + " COALESCE(em_etgo_amount_tolerance, 0)"
        + " FROM fin_financial_account"
        + " WHERE fin_financial_account_id = ?"; // NOSONAR java:S2077
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, accountId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          BigDecimal amtTol = rs.getBigDecimal(2);
          return new BigDecimal[]{
              BigDecimal.valueOf(rs.getInt(1)),
              amtTol != null ? amtTol : BigDecimal.ZERO
          };
        }
      }
    } catch (Exception e) {
      log.warn("Could not load tolerances for account {}", accountId, e);
    }
    return new BigDecimal[]{BigDecimal.valueOf(AutoMatchSupport.DEFAULT_DATE_TOL_DAYS),
        BigDecimal.ZERO};
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

  /**
   * Accounting-period guard, delegated to {@code com.etendoerp.payment.removal}'s
   * {@link Utilities#checkPeriod(String, String, String, java.util.Date)}: throws an
   * {@link OBException} when
   * the period of {@code date} is closed for the given client/org/table. Package-private test seam.
   */
  void checkPeriod(String clientId, String orgId, String tableId, java.util.Date date) {
    Utilities.checkPeriod(clientId, orgId, tableId, date);
  }

  void doRollbackAndClose() {
    OBDal.getInstance().rollbackAndClose();
  }

  /**
   * Whether matching {@code operationIds} against {@code line} will make Core's
   * {@code APRM_MatchingUtility} clone the line into a reconciled portion plus a new pending
   * remainder. Two independent triggers:
   * <ul>
   *   <li>More than one operation: Core's list overload chains through them one at a time,
   *       reassigning the working line to each split's remainder — with N &gt; 1 operations at
   *       least one split always happens, even when their amounts sum exactly to the line
   *       (e.g. line=150 matched to 100 + 50 still splits once, on the first operation).</li>
   *   <li>Exactly one operation whose amount does not exactly equal the line amount: a single
   *       partial invoice/transaction match (e.g. line=100 matched to a 53.24 invoice) also
   *       causes a split — this is the case a plain {@code operationIds.size() > 1} check used
   *       to miss, leaving the pending remainder as an ungrouped, seemingly-separate line.</li>
   * </ul>
   * An empty {@code operationIds} (e.g. an invoice selection that settled nothing) never splits.
   *
   * @param line         the statement line about to be matched
   * @param operationIds the transaction ids about to be matched against it (pre-existing and/or
   *                     invoice-derived)
   * @return {@code true} if Core is expected to split {@code line} for this match
   */
  boolean willSplitLine(FIN_BankStatementLine line, List<String> operationIds) {
    if (operationIds.isEmpty()) {
      return false;
    }
    if (operationIds.size() > 1) {
      return true;
    }
    BigDecimal lineAmount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    FIN_FinaccTransaction trx = loadTransaction(operationIds.get(0));
    BigDecimal opAmount = trx == null ? BigDecimal.ZERO : signedAmount(trx);
    return lineAmount.abs().compareTo(opAmount.abs()) != 0;
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
      org.openbravo.base.model.Property prop = ReactivationSupport.extensionProperty(
          FIN_BankStatementLine.ENTITY_NAME, ReactivationSupport.COL_MATCH_GROUP);
      if (prop != null) {
        line.set(prop.getName(), org.openbravo.erpCommon.utility.SequenceIdData.getUUID());
        OBDal.getInstance().save(line);
        OBDal.getInstance().flush();
      } else {
        log.warn("Column {} not yet in the model; skipping match-group tag",
            ReactivationSupport.COL_MATCH_GROUP);
      }
    } catch (Exception e) {
      log.warn("Could not tag match group on line {}", line.getId(), e);
    }
  }

  /**
   * True when the transaction carries the {@code EM_ETGO_Auto_Created} flag set to {@code true}.
   * Resolves the property by column name and degrades to {@code false} when the column is not in
   * the model yet. Package-private for testability.
   */
  boolean isAutoCreated(FIN_FinaccTransaction trx) {
    try {
      org.openbravo.base.model.Property prop = ReactivationSupport.extensionProperty(
          FIN_FinaccTransaction.ENTITY_NAME, ReactivationSupport.COL_AUTO_CREATED);
      if (prop == null) {
        return false;
      }
      Object value = trx.get(prop.getName());
      return Boolean.TRUE.equals(value);
    } catch (Exception e) {
      log.debug("Could not read auto-created flag on transaction {}: {}", trx.getId(),
          e.getMessage());
      return false;
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

}
