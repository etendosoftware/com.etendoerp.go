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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * Mockito-driven unit tests for {@link ReconciliationHandler} (T6).
 *
 * <p>The handler exposes three custom action routes
 * ({@code pendingLines}, {@code candidates}, {@code reconcileGroup}) and falls
 * through to the generic CRUD for any other request. Strategy: spy the handler
 * and stub the package-private DAL / Classic seams ({@code loadAccount},
 * {@code loadLine}, {@code loadTransaction}, {@code addNewDraftReconciliation},
 * {@code matchBankStatementLine}, {@code processReconciliation},
 * {@code accessibleOrgs}, {@code doRollbackAndClose}) so every path runs without
 * a database or a live OBContext. SQL-bound paths mock {@link OBDal} statically
 * and drive a fake {@link ResultSet}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Routing: unknown method/action → null passthrough.</li>
 *   <li>pendingLines: empty + non-empty result; param binding (account, client,
 *       org array, optional date/text filters).</li>
 *   <li>candidates: suggested flag from the DAO; docType filter; no lineId → no
 *       suggestions.</li>
 *   <li>reconcileGroup: 1:1 + 1:N happy paths; wrong-account 400; sum-mismatch
 *       400; already-reconciled 409.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationHandlerTest {

  private static final String ACC_ID = "acc-1";
  private static final String OTHER_ACC = "acc-2";
  private static final String LINE_ID = "line-1";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";

  private ReconciliationHandler handler;

  @Before
  public void setUp() {
    handler = spy(new ReconciliationHandler());
    doNothing().when(handler).doRollbackAndClose();
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── routing ────────────────────────────────────────────────────────────────

  private NeoContext getContext(String action, Map<String, String> extraParams) {
    Map<String, String> qp = new HashMap<>();
    if (action != null) {
      qp.put("action", action);
    }
    if (extraParams != null) {
      qp.putAll(extraParams);
    }
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("GET");
    when(context.getQueryParams()).thenReturn(qp);
    return context;
  }

  /** A request with no recognised action falls through to generic CRUD (null). */
  @Test
  public void testHandleUnknownActionReturnsNull() {
    assertNull(handler.handle(getContext("somethingElse", null)));
  }

  /** A POST without the reconcileGroup action falls through (null). */
  @Test
  public void testHandlePostWithoutActionReturnsNull() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getQueryParams()).thenReturn(Collections.emptyMap());
    assertNull(handler.handle(context));
  }

  // ── pendingLines ─────────────────────────────────────────────────────────

  private void stubConnection(OBDal dal, PreparedStatement ps, ResultSet rs) throws Exception {
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(conn.createArrayOf(anyString(), any())).thenReturn(null);
    when(ps.executeQuery()).thenReturn(rs);
  }

  /** pendingLines with no rows returns an empty list and a zero total. */
  @Test
  public void testPendingLinesEmpty() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getJSONArray("lines").length());
      assertEquals(0, new BigDecimal(data.getString("total")).compareTo(BigDecimal.ZERO));
      // account + client + org array are always bound (3 params, no filters).
      verify(ps).setString(1, ACC_ID);
      verify(ps).setString(2, CLIENT_ID);
    }
  }

  /** pendingLines with two rows returns both and sums their amounts. */
  @Test
  public void testPendingLinesNonEmptySumsTotal() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("l1", "l2");
    when(rs.getTimestamp("datetrx")).thenReturn(null);
    when(rs.getString("description")).thenReturn("DESC1", "DESC2");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("100.00"), new BigDecimal("19.51"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(2, data.getJSONArray("lines").length());
      assertEquals(0,
          new BigDecimal("119.51").compareTo(new BigDecimal(data.getString("total"))));
      assertEquals("pending", data.getJSONArray("lines").getJSONObject(0).getString("status"));
    }
  }

  /** Optional dateFrom/dateTo/q filters bind extra params after the base three. */
  @Test
  public void testPendingLinesBindsOptionalFilters() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    Map<String, String> filters = new HashMap<>();
    filters.put("dateFrom", "2026-01-01");
    filters.put("dateTo", "2026-12-31");
    filters.put("q", "DHL");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      handler.buildPendingLines(ACC_ID, CLIENT_ID, new HashSet<>(Arrays.asList(ORG_ID)), filters);

      // base: account(1), client(2); date range binds positions 4 and 5; text 6.
      verify(ps).setDate(eq(4), any());
      verify(ps).setDate(eq(5), any());
      verify(ps).setString(6, "%dhl%");
    }
  }

  // ── candidates ─────────────────────────────────────────────────────────────

  /** candidates marks the transactions the DAO suggests for the selected line. */
  @Test
  public void testCandidatesMarksSuggested() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("t1", "t2");
    when(rs.getTimestamp("statementdate")).thenReturn(null);
    when(rs.getString("document_no")).thenReturn("PAY-1", "PAY-2");
    when(rs.getString("partner_name")).thenReturn("DHL", "ACME");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("50.00"), new BigDecimal("75.00"));

    // t1 is suggested, t2 is not.
    doReturn(new HashSet<>(Arrays.asList("t1"))).when(handler).suggestedTransactionIds(ACC_ID, LINE_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      NeoResponse response = handler.buildCandidates(ACC_ID, LINE_ID, null);

      JSONArray candidates =
          response.getBody().getJSONObject("response").getJSONObject("data").getJSONArray("candidates");
      assertEquals(2, candidates.length());
      assertTrue(candidates.getJSONObject(0).getBoolean("suggested"));
      assertFalse(candidates.getJSONObject(1).getBoolean("suggested"));
      assertEquals("pending", candidates.getJSONObject(0).getString("status"));
    }
  }

  /** A docType filter binds the isreceipt flag as an extra SQL parameter. */
  @Test
  public void testCandidatesDocTypeFilterBindsIsReceipt() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);
    doReturn(new HashSet<String>()).when(handler).suggestedTransactionIds(ACC_ID, LINE_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      handler.buildCandidates(ACC_ID, LINE_ID, "payments");

      // account(1) then the docType flag(2) = 'N' for payments.
      verify(ps).setString(1, ACC_ID);
      verify(ps).setString(2, "N");
    }
  }

  /** Without a lineId no transactions are flagged suggested. */
  @Test
  public void testSuggestedTransactionIdsNoLineReturnsEmpty() {
    assertTrue(handler.suggestedTransactionIds(ACC_ID, null).isEmpty());
    assertTrue(handler.suggestedTransactionIds(ACC_ID, "").isEmpty());
  }

  /**
   * When the account has no matching algorithm configured, the standard-algorithm path is skipped
   * and no suggestion is produced (graceful, no crash) — the Classic algorithm is never bypassed
   * with relaxed criteria.
   */
  @Test
  public void testSuggestedTransactionIdsNoAlgorithmReturnsEmpty() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getMatchingAlgorithm()).thenReturn(null);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_BankStatementLine.class, LINE_ID)).thenReturn(line);

      Set<String> ids = handler.suggestedTransactionIds(ACC_ID, LINE_ID);
      assertTrue(ids.isEmpty());
    }
  }

  // ── reconcileGroup ─────────────────────────────────────────────────────────

  private FIN_BankStatementLine lineFor(String accountId, BigDecimal credit, BigDecimal debit,
      FIN_FinaccTransaction matched) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    FIN_BankStatement bs = mock(FIN_BankStatement.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(accountId);
    when(bs.getAccount()).thenReturn(acc);
    when(line.getBankStatement()).thenReturn(bs);
    when(line.getCramount()).thenReturn(credit);
    when(line.getDramount()).thenReturn(debit);
    when(line.getFinancialAccountTransaction()).thenReturn(matched);
    return line;
  }

  private FIN_FinaccTransaction trxFor(String accountId, BigDecimal deposit, BigDecimal payment,
      FIN_Reconciliation reconciliation) {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(accountId);
    when(trx.getAccount()).thenReturn(acc);
    when(trx.getDepositAmount()).thenReturn(deposit);
    when(trx.getPaymentAmount()).thenReturn(payment);
    when(trx.getReconciliation()).thenReturn(reconciliation);
    return trx;
  }

  private JSONObject reconcileBody(String accountId, String lineId, String... opIds)
      throws Exception {
    JSONArray ops = new JSONArray();
    for (String id : opIds) {
      ops.put(id);
    }
    return new JSONObject()
        .put("financialAccountId", accountId)
        .put("statementLineId", lineId)
        .put("operationIds", ops);
  }

  private void stubReconciliationCompose(FIN_Reconciliation rec, String type) throws Exception {
    doReturn(rec).when(handler).addNewDraftReconciliation(any());
    doNothing().when(handler).matchBankStatementLine(any(), any(), any());
    OBError result = mock(OBError.class);
    when(result.getType()).thenReturn(type);
    when(result.getMessage()).thenReturn("msg");
    doReturn(result).when(handler).processReconciliation(rec);
  }

  /** A 1:1 match whose amounts agree composes the services and returns 201. */
  @Test
  public void testReconcileGroupHappy1to1() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-1");
    when(rec.getEndingBalance()).thenReturn(new BigDecimal("100.00"));

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("rec-1", data.getString("reconciliationId"));
    assertEquals(1, data.getJSONArray("lineIds").length());
    verify(handler).matchBankStatementLine(eq(line), any(), eq(rec));
    // A single operation produces no split worth grouping → no match-group tag.
    verify(handler, never()).tagMatchGroup(any());
  }

  /** A 1:N match whose operations sum exactly to the line amount returns 201. */
  @Test
  public void testReconcileGroupHappy1toN() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("150.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t1 = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction t2 = trxFor(ACC_ID, new BigDecimal("50.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-2");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(t1).when(handler).loadTransaction("t1");
    doReturn(t2).when(handler).loadTransaction("t2");
    doNothing().when(handler).tagMatchGroup(any());
    stubReconciliationCompose(rec, "Success");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1", "t2"));

    assertEquals(201, response.getHttpStatus());
    // 1:N reconcile tags the original line so the split sub-lines inherit the group id.
    verify(handler).tagMatchGroup(line);
  }

  /** An operation that belongs to another account is rejected with a 400. */
  @Test
  public void testReconcileGroupWrongAccountReturns400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(OTHER_ACC, new BigDecimal("100.00"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /** When the operations do not sum to the line amount the request is a 400. */
  @Test
  public void testReconcileGroupSumMismatchReturns400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    // Operation is 91.69 → diff 8.31, well beyond the 0.01 tolerance.
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("91.69"), BigDecimal.ZERO, null);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message").contains("8.31"));
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  /** An operation already linked to a reconciliation is rejected with a 409. */
  @Test
  public void testReconcileGroupAlreadyReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation existing = mock(FIN_Reconciliation.class);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, existing);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(409, response.getHttpStatus());
  }

  /** A statement line that is already reconciled is rejected with a 409. */
  @Test
  public void testReconcileGroupLineAlreadyReconciledReturns409() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_FinaccTransaction alreadyMatched = mock(FIN_FinaccTransaction.class);
    FIN_BankStatementLine line =
        lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, alreadyMatched);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(409, response.getHttpStatus());
  }

  /** An unknown statement line yields a 404. */
  @Test
  public void testReconcileGroupMissingLineReturns404() throws Exception {
    doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(404, response.getHttpStatus());
  }

  /** A line belonging to a different account is rejected with a 400. */
  @Test
  public void testReconcileGroupLineWrongAccountReturns400() throws Exception {
    FIN_BankStatementLine line = lineFor(OTHER_ACC, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    doReturn(mock(FIN_FinancialAccount.class)).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
  }

  /** An empty operationIds list is rejected with a 400 before any lookup. */
  @Test
  public void testReconcileGroupEmptyOperationsReturns400() throws Exception {
    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID));
    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  /** A processReconciliation error rolls back and surfaces a 400 with the message. */
  @Test
  public void testReconcileGroupProcessErrorRollsBackTo400() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_FinaccTransaction trx = trxFor(ACC_ID, new BigDecimal("100.00"), BigDecimal.ZERO, null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(trx).when(handler).loadTransaction("t1");
    stubReconciliationCompose(rec, "Error");

    NeoResponse response = handler.reconcileGroup(reconcileBody(ACC_ID, LINE_ID, "t1"));

    assertEquals(400, response.getHttpStatus());
    verify(handler).doRollbackAndClose();
  }

  // ── applySuggestions ─────────────────────────────────────────────────────────

  /**
   * A rule-origin group carrying a createPayment spec materializes the GL-item transaction via
   * {@code createTransactionForRule} and reconciles the resulting transaction against the line.
   */
  @Test
  public void testApplySuggestionsCreatesTransactionForRuleGroup() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = lineFor(ACC_ID, BigDecimal.ZERO, new BigDecimal("12.50"), null);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn("rec-9");

    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn("T-NEW").when(handler).createTransactionForRule(eq(account), eq(line), any());
    stubReconciliationCompose(rec, "Success");

    JSONObject createPayment = new JSONObject()
        .put("glItemId", "GL-1").put("ruleId", "R1").put("amount", "-12.50");
    JSONObject group = new JSONObject()
        .put("statementLineId", LINE_ID)
        .put("operationIds", new JSONArray())
        .put("createPayment", createPayment);
    JSONObject body = new JSONObject()
        .put("financialAccountId", ACC_ID)
        .put("groups", new JSONArray().put(group));

    NeoResponse response = handler.applySuggestions(body);

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(eq(account), eq(line), any());
    // The created transaction id is the one reconciled against the line.
    verify(handler).matchBankStatementLine(eq(line), argThat(ops -> ops.contains("T-NEW")), eq(rec));
  }

  // ── nullSafe helper ──────────────────────────────────────────────────────────

  /** nullSafe maps null to zero and keeps a present value. */
  @Test
  public void testNullSafe() {
    assertEquals(0, ReconciliationHandler.nullSafe(null).compareTo(BigDecimal.ZERO));
    assertEquals(0, ReconciliationHandler.nullSafe(new BigDecimal("5")).compareTo(new BigDecimal("5")));
  }

  // ── buildPendingLines: state + counts (T7) ────────────────────────────────────

  /**
   * Each line row must include a {@code state} field and the response must include a {@code counts}
   * object with per-state tallies. Two pending lines → counts.pending == 2, counts.all == 2.
   */
  @Test
  public void testBuildPendingLinesIncludesStateAndCounts() throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("l1", "l2");
    when(rs.getTimestamp("datetrx")).thenReturn(null);
    when(rs.getString("description")).thenReturn("DESC1", "DESC2");
    when(rs.getBigDecimal("amount")).thenReturn(new BigDecimal("100.00"), new BigDecimal("50.00"));
    // Not reconciled → state is driven by classifyPendingLine (no algorithm → no rule → pending).
    when(rs.getString("line_status")).thenReturn("PENDING", "PENDING");
    when(rs.getString("partner_name")).thenReturn("", "");
    when(rs.getString("reference_no")).thenReturn("", "");
    when(rs.getString("match_group_id")).thenReturn("", "");

    // classifyPendingLine calls OBDal.get for the line, then checks the account's algorithm.
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getMatchingAlgorithm()).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubConnection(dal, ps, rs);

      // loadAccount is a seam on the spy — return our mock account.
      doReturn(account).when(handler).loadAccount(ACC_ID);

      // classifyPendingLine(account, lineId, rules) calls OBDal.get for the line.
      FIN_BankStatementLine line1 = mock(FIN_BankStatementLine.class);
      FIN_BankStatementLine line2 = mock(FIN_BankStatementLine.class);
      when(dal.get(FIN_BankStatementLine.class, "l1")).thenReturn(line1);
      when(dal.get(FIN_BankStatementLine.class, "l2")).thenReturn(line2);
      when(line1.getDescription()).thenReturn("DESC1");
      when(line1.getReferenceNo()).thenReturn("");
      when(line1.getBpartnername()).thenReturn("");
      when(line2.getDescription()).thenReturn("DESC2");
      when(line2.getReferenceNo()).thenReturn("");
      when(line2.getBpartnername()).thenReturn("");

      // loadRules — stub the connection to return empty ResultSet for the rules query.
      Connection conn2 = mock(Connection.class);
      PreparedStatement rulePs = mock(PreparedStatement.class);
      ResultSet ruleRs = mock(ResultSet.class);
      when(ruleRs.next()).thenReturn(false);
      when(rulePs.executeQuery()).thenReturn(ruleRs);
      when(conn2.prepareStatement(anyString())).thenReturn(ps, rulePs);
      when(conn2.createArrayOf(anyString(), any())).thenReturn(null);
      when(dal.getConnection()).thenReturn(conn2);

      NeoResponse response = handler.buildPendingLines(ACC_ID, CLIENT_ID,
          new HashSet<>(Arrays.asList(ORG_ID)), Collections.emptyMap());

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");

      // Every row must carry a "state" field.
      JSONArray lines = data.getJSONArray("lines");
      assertEquals(2, lines.length());
      assertTrue(lines.getJSONObject(0).has("state"));
      assertTrue(lines.getJSONObject(1).has("state"));

      // counts object must be present with at least "all" and "pending" tallies.
      JSONObject counts = data.getJSONObject("counts");
      assertEquals(2, counts.getInt("all"));
      assertEquals(2, counts.getInt("pending"));
    }
  }

  // ── createTransactionForRule (T7) ─────────────────────────────────────────────

  /**
   * A positive (deposit) amount → the transaction type must be BPD (Cobro). The handler should
   * set depositAmount = abs(amount) and paymentAmount = 0.
   */
  @Test
  public void testCreateTransactionForRulePositiveAmountUsesBPD() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    Organization org = mock(Organization.class);
    when(line.getOrganization()).thenReturn(org);
    when(line.getDescription()).thenReturn("Bank fee");
    when(line.getTransactionDate()).thenReturn(null);
    when(line.getCramount()).thenReturn(new BigDecimal("100.00"));
    when(line.getDramount()).thenReturn(BigDecimal.ZERO);

    GLItem glItem = mock(GLItem.class);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("TRX-NEW-1");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<FIN_TransactionProcess> trxProcess =
            mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, "GL-001")).thenReturn(glItem);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

      // AutoMatchSupport.nextTransactionLineNo uses OBDal.getConnection().
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(10L);

      trxProcess.when(() ->
          FIN_TransactionProcess.doTransactionProcess(anyString(), eq(trx)))
          .thenAnswer(inv -> null);

      JSONObject spec = new JSONObject()
          .put("glItemId", "GL-001")
          .put("bpartnerId", "")
          .put("amount", "100.00");

      String txnId = handler.createTransactionForRule(account, line, spec);

      assertEquals("TRX-NEW-1", txnId);
      // Verify the transaction was configured as a deposit (BPD).
      verify(trx).setTransactionType("BPD");
      verify(trx).setDepositAmount(new BigDecimal("100.00"));
      verify(trx).setPaymentAmount(BigDecimal.ZERO);
    }
  }

  /**
   * A negative amount → the transaction type must be BPW (Pago). The handler should set
   * paymentAmount = abs(amount) and depositAmount = 0.
   */
  @Test
  public void testCreateTransactionForRuleNegativeAmountUsesBPW() throws Exception {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    Organization org = mock(Organization.class);
    when(line.getOrganization()).thenReturn(org);
    when(line.getDescription()).thenReturn("Fee payment");
    when(line.getTransactionDate()).thenReturn(null);
    when(line.getCramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDramount()).thenReturn(new BigDecimal("50.00"));

    GLItem glItem = mock(GLItem.class);
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("TRX-NEW-2");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<FIN_TransactionProcess> trxProcess =
            mockStatic(FIN_TransactionProcess.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, "GL-002")).thenReturn(glItem);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinaccTransaction.class)).thenReturn(trx);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getLong(1)).thenReturn(10L);

      trxProcess.when(() ->
          FIN_TransactionProcess.doTransactionProcess(anyString(), eq(trx)))
          .thenAnswer(inv -> null);

      JSONObject spec = new JSONObject()
          .put("glItemId", "GL-002")
          .put("bpartnerId", "")
          .put("amount", "-50.00");

      String txnId = handler.createTransactionForRule(account, line, spec);

      assertEquals("TRX-NEW-2", txnId);
      // Negative amount → withdrawal (BPW).
      verify(trx).setTransactionType("BPW");
      verify(trx).setPaymentAmount(new BigDecimal("50.00"));
      verify(trx).setDepositAmount(BigDecimal.ZERO);
    }
  }
}
