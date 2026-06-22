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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
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
          + "   AND ft.fin_financial_account_id = ?";

  private static final String CANDIDATES_ORDER =
      " ORDER BY ft.statementdate ASC, ft.line ASC";

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
    String dateFrom = filters != null ? filters.get(PARAM_DATE_FROM) : null;
    String dateTo = filters != null ? filters.get(PARAM_DATE_TO) : null;
    String q = filters != null ? filters.get(PARAM_Q) : null;

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
          row.put("partnerName", StringUtils.trimToEmpty(rs.getString("partner_name")));
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
    try {
      OBContext.setAdminMode(true);
      return buildCandidates(accountId, lineId, docType);
    } catch (Exception e) {
      log.error("Error building candidates for account {}", accountId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildCandidates(String accountId, String lineId, String docType) throws Exception {
    Set<String> suggestedIds = suggestedTransactionIds(accountId, lineId);
    // 1:N: if the selected line amount equals the sum of a signal group (same logic the automatch
    // uses), pre-mark ALL of its operations as suggested — not only a single 1:1 standard match.
    if (StringUtils.isNotBlank(lineId)) {
      FIN_BankStatementLine selectedLine = loadLine(lineId);
      if (selectedLine != null) {
        for (FIN_FinaccTransaction t : AutoMatchSupport.findSignalGroup(
            accountId, selectedLine, new HashSet<>(), TOLERANCE)) {
          suggestedIds.add(t.getId());
        }
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
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put("partnerName", StringUtils.trimToEmpty(rs.getString("partner_name")));
          row.put(KEY_AMOUNT, amount);
          // Pending balance equals the transaction amount for now (partial
          // allocations against invoices are a follow-up).
          row.put("pendingBalance", amount);
          row.put(KEY_STATUS, STATUS_PENDING);
          row.put("suggested", suggestedIds.contains(id));
          candidates.put(row);
        }
      }
    }
    JSONObject data = new JSONObject();
    data.put(ACTION_CANDIDATES, candidates);
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

    if (StringUtils.isBlank(accountId) || StringUtils.isBlank(statementLineId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "financialAccountId and statementLineId are required");
    }
    if (operationIds.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "At least one operation is required");
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

    NeoResponse opError = validateOperations(operationIds, accountId, line);
    if (opError != null) {
      return opError;
    }

    return compose(account, line, operationIds);
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
    BigDecimal diff = lineAmount.subtract(opSum);
    if (diff.abs().compareTo(TOLERANCE) > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "The sum of the selected operations (" + opSum.toPlainString()
              + ") does not match the statement line amount (" + lineAmount.toPlainString()
              + "). Difference: " + diff.toPlainString());
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
}
