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
import org.openbravo.advpaymentmngt.dao.MatchTransactionDao;
import org.openbravo.advpaymentmngt.utility.APRM_MatchingUtility;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
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
 *     <td>{@code POST action=reconcileGroup}</td>
 *     <td>Composes the standard Etendo reconciliation services for a 1:N manual
 *         match. Body: {@code { financialAccountId, statementLineId, operationIds:[...] }}.</td>
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
  private static final String PARAM_STATUS = "status";
  private static final String PARAM_DATE_FROM = "dateFrom";
  private static final String PARAM_DATE_TO = "dateTo";
  private static final String PARAM_Q = "q";

  private static final String ACTION_PENDING_LINES = "pendingLines";
  private static final String ACTION_CANDIDATES = "candidates";
  private static final String ACTION_RECONCILE_GROUP = "reconcileGroup";

  /** Match level recorded on the reconciliation lines produced by this handler. */
  private static final String MATCH_LEVEL_MANUAL = "MANUALMATCH";
  /** Action code for {@link APRM_MatchingUtility#processReconciliation} (P = process). */
  private static final String PROCESS_ACTION = "P";
  /** Tolerance applied when comparing the line amount to the sum of operations. */
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  /** JSON keys reused across rows — extracted to satisfy Sonar S1192. */
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_ID = "id";
  private static final String KEY_DATE = "date";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_STATUS = "status";
  private static final String STATUS_PENDING = "pending";

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  /**
   * Pending bank-statement lines (panel left): unmatched lines of the account,
   * scoped to the current client and the accessible organization tree. Optional
   * filters bind extra parameters (date range, free-text search).
   */
  private static final String PENDING_LINES_SQL =
      "SELECT bsl.fin_bankstatementline_id,"
          + "       bsl.transactiondate,"
          + "       COALESCE(bsl.description, '') AS description,"
          + "       COALESCE(bsl.cramount, 0) - COALESCE(bsl.dramount, 0) AS amount"
          + "  FROM fin_bankstatementline bsl"
          + "  JOIN fin_bankstatement bs ON bs.fin_bankstatement_id = bsl.fin_bankstatement_id"
          + " WHERE bsl.fin_finacc_transaction_id IS NULL"
          + "   AND bsl.isactive = 'Y'"
          + "   AND bs.isactive = 'Y'"
          + "   AND bs.fin_financial_account_id = ?"
          + "   AND bs.ad_client_id = ?"
          + "   AND bs.ad_org_id = ANY (?)";

  private static final String PENDING_LINES_ORDER =
      " ORDER BY bsl.transactiondate ASC, bsl.line ASC";

  /**
   * Available reconciliation candidates (panel right): processed finacc
   * transactions of the account not yet reconciled. Joins FIN_Payment +
   * C_BPartner for display info. Mirrors the availability predicate used by
   * {@link MatchTransactionDao#getMatchingFinancialTransaction}.
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
    if (METHOD_POST.equals(method) && ACTION_RECONCILE_GROUP.equals(action)) {
      return handleReconcileGroup(context);
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
          "Missing required parameter: " + PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      Set<String> orgs = accessibleOrgs(OBContext.getOBContext().getCurrentOrganization().getId());
      return buildPendingLines(accountId, clientId, orgs, qp);
    } catch (Exception e) {
      log.error("Error building pendingLines for account {}", accountId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildPendingLines(String accountId, String clientId, Set<String> orgs,
      Map<String, String> filters) throws Exception {
    String status = filters != null ? filters.get(PARAM_STATUS) : null;
    String dateFrom = filters != null ? filters.get(PARAM_DATE_FROM) : null;
    String dateTo = filters != null ? filters.get(PARAM_DATE_TO) : null;
    String q = filters != null ? filters.get(PARAM_Q) : null;

    StringBuilder sql = new StringBuilder(PENDING_LINES_SQL);
    if (StringUtils.isNotBlank(dateFrom)) {
      sql.append(" AND bsl.transactiondate >= ?");
    }
    if (StringUtils.isNotBlank(dateTo)) {
      sql.append(" AND bsl.transactiondate <= ?");
    }
    if (StringUtils.isNotBlank(q)) {
      sql.append(" AND LOWER(bsl.description) LIKE ?");
    }
    sql.append(PENDING_LINES_ORDER);

    JSONArray lines = new JSONArray();
    BigDecimal total = BigDecimal.ZERO;
    Connection conn = OBDal.getInstance().getConnection();
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
          JSONObject row = new JSONObject();
          row.put(KEY_ID, rs.getString("fin_bankstatementline_id"));
          row.put(KEY_DATE, formatDate(rs.getTimestamp("transactiondate")));
          row.put("description", StringUtils.trimToEmpty(rs.getString("description")));
          // T6 only lists pending lines; the status column is reserved for the
          // reconciled/suggested/by-rule states added by later tasks.
          row.put(KEY_STATUS, STATUS_PENDING);
          row.put(KEY_AMOUNT, amount);
          lines.put(row);
          total = total.add(amount);
        }
      }
    }
    // The status filter is accepted but reserved: T6 only ever returns pending
    // lines, so a non-pending status simply yields the same set.
    if (StringUtils.isNotBlank(status) && !STATUS_PENDING.equalsIgnoreCase(status)) {
      log.debug("pendingLines status filter '{}' ignored in T6 (only pending lines exist)", status);
    }
    JSONObject data = new JSONObject();
    data.put("lines", lines);
    data.put("total", total);
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
          "Missing required parameter: " + PARAM_ACCOUNT_ID);
    }
    String lineId = qp != null ? qp.get(PARAM_LINE_ID) : null;
    String docType = qp != null ? qp.get(PARAM_DOC_TYPE) : null;
    try {
      OBContext.setAdminMode(true);
      return buildCandidates(accountId, lineId, docType);
    } catch (Exception e) {
      log.error("Error building candidates for account {}", accountId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  NeoResponse buildCandidates(String accountId, String lineId, String docType) throws Exception {
    Set<String> suggestedIds = suggestedTransactionIds(accountId, lineId);

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
    data.put("candidates", candidates);
    return envelope(data);
  }

  /**
   * Returns the ids of the finacc transactions the standard matching algorithm
   * suggests for the selected bank-statement line, or an empty set when no line
   * is selected. Composes {@link MatchTransactionDao#getMatchingFinancialTransaction}
   * — the same algorithm Classic uses — over the line's amount, date and reference.
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
    BigDecimal amount = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    String reference = StringUtils.trimToEmpty(line.getReferenceNo());
    List<FIN_FinaccTransaction> matches = MatchTransactionDao.getMatchingFinancialTransaction(
        accountId, line.getTransactionDate(), reference, amount, new ArrayList<>());
    for (FIN_FinaccTransaction match : matches) {
      ids.add(match.getId());
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
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
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
          "Financial account not found: " + accountId);
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
