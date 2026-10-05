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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * ETP-5472 write-safety and outcome tests around the reconcile entry points:
 *
 * <ul>
 *   <li>B1 — {@code runPostAction} rolls back any RETURNED status &gt;= 400 (a returned error used
 *       to commit), and {@code reconcileGroup} refuses a bad client-supplied operation id BEFORE any
 *       invoice is paid;</li>
 *   <li>B2 — the 201 of {@code compose} says whether the line is closed ({@code partial},
 *       {@code pendingAmount}, {@code remainderLineId});</li>
 *   <li>B3 — {@code candidates} for the reconciled head of a partial group lists its remainder and
 *       says so.</li>
 * </ul>
 *
 * <p>{@link #routedHandler} is a plain mock for the dispatch-envelope tests; {@link #handler} is a
 * spy on a real handler whose DAL seams are stubbed, for the business-method tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationRefusedWriteTest {

  private static final String ACC_ID = "acc-1";
  private static final String LINE_ID = "line-head";
  private static final String REM_ID = "line-rem";
  private static final String OP_ID = "trx-op";
  private static final String UNKNOWN_OP_ID = "trx-unknown";
  private static final String REC_ID = "rec-1";
  private static final String GROUP_ID = "grp-1";
  private static final String MATCH_GROUP_PROPERTY = "matchGroupId";
  private static final String KEY_FINANCIAL_ACCOUNT_ID = "financialAccountId";
  private static final String KEY_STATEMENT_LINE_ID = "statementLineId";
  private static final String KEY_OPERATION_IDS = "operationIds";
  private static final String KEY_ERROR = "error";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_REMAINDER = ReconciliationDifferenceSupport.KEY_REMAINDER_LINE_ID;
  private static final String HUNDRED = "100.00";
  private static final String FORTY = "40.00";
  private static final String FREE_OP_ID = "trx-free";
  private static final String DRAFT_DOC_NO = "REC-DRAFT-9";

  @Mock
  private ReconciliationHandler routedHandler;
  @Spy
  private ReconciliationHandler handler;
  @Mock
  private NeoContext context;
  @Mock
  private FIN_FinancialAccount account;
  @Mock
  private FIN_BankStatement statement;
  @Mock
  private FIN_BankStatementLine line;
  @Mock
  private FIN_BankStatementLine remainder;
  @Mock
  private FIN_FinaccTransaction operation;
  @Mock
  private FIN_FinaccTransaction reconciledOperation;
  @Mock
  private FIN_FinaccTransaction freeOperation;
  @Mock
  private FIN_Reconciliation reconciliation;
  @Mock
  private OBDal dal;
  @Mock
  private ModelProvider modelProvider;
  @Mock
  private Entity lineEntity;
  @Mock
  private Property matchGroupProperty;

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<ModelProvider> model;

  @BeforeEach
  void setUp() {
    obContext = Mockito.mockStatic(OBContext.class);
    obDal = Mockito.mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    model = Mockito.mockStatic(ModelProvider.class);
    model.when(ModelProvider::getInstance).thenReturn(modelProvider);
    when(modelProvider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(lineEntity);
    when(lineEntity.getPropertyByColumnName(ReactivationSupport.COL_MATCH_GROUP, false))
        .thenReturn(matchGroupProperty);
    when(matchGroupProperty.getName()).thenReturn(MATCH_GROUP_PROPERTY);

    doNothing().when(handler).doRollbackAndClose();
    when(account.getId()).thenReturn(ACC_ID);
    when(statement.getAccount()).thenReturn(account);
    when(statement.isProcessed()).thenReturn(Boolean.TRUE);
    stubRow(line, LINE_ID, HUNDRED);
    stubRow(remainder, REM_ID, FORTY);

    when(operation.getAccount()).thenReturn(account);
    when(operation.getDepositAmount()).thenReturn(new BigDecimal("60.00"));
    when(operation.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(reconciledOperation.getAccount()).thenReturn(account);
    when(reconciledOperation.getReconciliation()).thenReturn(reconciliation);
    when(reconciliation.getId()).thenReturn(REC_ID);
    when(reconciliation.getEndingBalance()).thenReturn(new BigDecimal("500.00"));
  }

  @AfterEach
  void tearDown() {
    obContext.close();
    obDal.close();
    model.close();
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private void stubRow(FIN_BankStatementLine row, String id, String credit) {
    when(row.getId()).thenReturn(id);
    when(row.getBankStatement()).thenReturn(statement);
    when(row.getCramount()).thenReturn(new BigDecimal(credit));
    when(row.getDramount()).thenReturn(BigDecimal.ZERO);
    when(row.isActive()).thenReturn(true);
  }

  /** {@link #line} and {@link #remainder} share a match group; only the remainder is unmatched. */
  private void partialGroup() {
    when(line.get(MATCH_GROUP_PROPERTY)).thenReturn(GROUP_ID);
    when(remainder.get(MATCH_GROUP_PROPERTY)).thenReturn(GROUP_ID);
    when(line.getFinancialAccountTransaction()).thenReturn(operation);
    List<FIN_BankStatementLine> rows = new ArrayList<>(List.of(line, remainder));
    doReturn(rows).when(handler).loadMatchGroupLines(statement, GROUP_ID);
    when(routedHandler.loadMatchGroupLines(statement, GROUP_ID)).thenReturn(rows);
  }

  private static JSONObject reconcileBody(String... opIds) throws Exception {
    return reconcileBody(true, opIds);
  }

  private static JSONObject reconcileBody(boolean withInvoice, String... opIds) throws Exception {
    JSONArray ops = new JSONArray();
    for (String id : opIds) {
      ops.put(id);
    }
    JSONObject body = new JSONObject()
        .put(KEY_FINANCIAL_ACCOUNT_ID, ACC_ID)
        .put(KEY_STATEMENT_LINE_ID, LINE_ID)
        .put(KEY_OPERATION_IDS, ops);
    if (withInvoice) {
      body.put(ReconciliationHandler.KIND_INVOICES, new JSONArray().put(
          new JSONObject().put("invoiceId", "inv-1").put("scheduleId", "sch-1")));
    }
    return body;
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject(KEY_ERROR).getString(KEY_MESSAGE);
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private void stubCompose() throws Exception {
    OBError success = new OBError();
    success.setType("Success");
    doReturn(reconciliation).when(handler).addNewDraftReconciliation(account);
    doNothing().when(handler).matchInto(any(), anyList(), any());
    doReturn(success).when(handler).processReconciliation(reconciliation);
    doReturn(operation).when(handler).loadTransaction(OP_ID);
  }

  // ── B1: runPostAction rolls back a returned error ───────────────────────────

  @Test
  void testRunPostActionRollsBackAReturned4xx() throws Exception {
    JSONObject body = new JSONObject().put(KEY_FINANCIAL_ACCOUNT_ID, ACC_ID);
    when(context.getRequestBody()).thenReturn(body);
    NeoResponse refused = NeoResponse.error(400, "Operation not found: " + UNKNOWN_OP_ID);
    when(routedHandler.reconcileGroup(body)).thenReturn(refused);

    NeoResponse response = ReconciliationHandlerSupport.handleReconcileGroup(routedHandler, context);

    assertSame(refused, response);
    verify(routedHandler).doRollbackAndClose();
  }

  @Test
  void testRunPostActionRollsBackAReturnedConflict() throws Exception {
    JSONObject body = new JSONObject().put(KEY_FINANCIAL_ACCOUNT_ID, ACC_ID);
    when(context.getRequestBody()).thenReturn(body);
    when(routedHandler.reactivate(body)).thenReturn(NeoResponse.error(409, "conflict"));

    assertEquals(409,
        ReconciliationHandlerSupport.handleReactivate(routedHandler, context).getHttpStatus());
    verify(routedHandler).doRollbackAndClose();
  }

  @Test
  void testRunPostActionKeepsA2xx() throws Exception {
    JSONObject body = new JSONObject().put(KEY_FINANCIAL_ACCOUNT_ID, ACC_ID);
    when(context.getRequestBody()).thenReturn(body);
    NeoResponse created = NeoResponse.createdWithData(new JSONObject());
    when(routedHandler.reconcileGroup(body)).thenReturn(created);
    when(routedHandler.reactivateSelected(body))
        .thenReturn(ReconciliationSupport.envelope(new JSONObject()));

    assertSame(created, ReconciliationHandlerSupport.handleReconcileGroup(routedHandler, context));
    assertEquals(200, ReconciliationHandlerSupport
        .handleReactivateSelected(routedHandler, context).getHttpStatus());
    verify(routedHandler, never()).doRollbackAndClose();
  }

  // ── B1: reconcileGroup checks operation ids before paying invoices ──────────

  @Test
  void testReconcileGroupRefusesAnUnknownOperationBeforePayingInvoices() throws Exception {
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(null).when(handler).loadTransaction(UNKNOWN_OP_ID);

    NeoResponse response;
    try (MockedStatic<ReconciliationPaymentService> payments =
        Mockito.mockStatic(ReconciliationPaymentService.class);
        MockedStatic<ReconciliationWriteoffSupport> writeoff =
            Mockito.mockStatic(ReconciliationWriteoffSupport.class, Mockito.CALLS_REAL_METHODS)) {
      response = handler.reconcileGroup(reconcileBody(UNKNOWN_OP_ID));

      writeoff.verify(() -> ReconciliationWriteoffSupport.payInvoicesFromBody(any(), any(), any(),
          any(), anyList(), any()), never());
      writeoff.verify(() -> ReconciliationWriteoffSupport.payInvoices(any(), any(), any(),
          anyList(), any(), any(), anyBoolean()), never());
      payments.verifyNoInteractions();
    }

    assertEquals(400, response.getHttpStatus());
    assertEquals("Operation not found: " + UNKNOWN_OP_ID, errorMessage(response));
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).processReconciliation(any());
    verifyNoInteractions(dal);
  }

  @Test
  void testReconcileGroupRefusesAReconciledOperationBeforePayingInvoices() throws Exception {
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(reconciledOperation).when(handler).loadTransaction(OP_ID);

    NeoResponse response;
    try (MockedStatic<ReconciliationPaymentService> payments =
        Mockito.mockStatic(ReconciliationPaymentService.class)) {
      response = handler.reconcileGroup(reconcileBody(OP_ID));
      payments.verifyNoInteractions();
    }

    assertEquals(409, response.getHttpStatus());
    assertEquals("Operation is already reconciled: " + OP_ID, errorMessage(response));
    verify(handler, never()).addNewDraftReconciliation(any());
  }

  @Test
  void testReconcileGroupThroughTheRouteRollsBackTheRefusal() throws Exception {
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(null).when(handler).loadTransaction(UNKNOWN_OP_ID);
    when(context.getRequestBody()).thenReturn(reconcileBody(UNKNOWN_OP_ID));

    NeoResponse response = ReconciliationHandlerSupport.handleReconcileGroup(handler, context);

    assertEquals(400, response.getHttpStatus());
    verify(handler).doRollbackAndClose();
  }

  // ── B2: compose reports whether the line is complete ────────────────────────

  @Test
  void testComposeReportsAPartialMatchFromTheRemainderRow() throws Exception {
    stubCompose();
    partialGroup();
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response =
        ReconciliationFlowSupport.compose(handler, account, line, List.of(OP_ID));

    assertEquals(201, response.getHttpStatus());
    JSONObject data = dataOf(response);
    assertEquals(REC_ID, data.getString("reconciliationId"));
    assertTrue(data.getBoolean(ReconciliationLineTargetSupport.KEY_PARTIAL));
    // The pending remainder row Core's split left: a 40.00 credit, positive like the line.
    assertEquals(0, new BigDecimal(FORTY).compareTo(pendingAmountOf(data)));
    assertEquals(REM_ID, data.getString(KEY_REMAINDER));
  }

  @Test
  void testComposeReportsAPartialDebitMatchWithANegativePendingAmount() throws Exception {
    stubCompose();
    when(line.getCramount()).thenReturn(BigDecimal.ZERO);
    when(line.getDramount()).thenReturn(new BigDecimal(HUNDRED));
    when(remainder.getCramount()).thenReturn(BigDecimal.ZERO);
    when(remainder.getDramount()).thenReturn(new BigDecimal("25.00"));
    when(operation.getDepositAmount()).thenReturn(BigDecimal.ZERO);
    when(operation.getPaymentAmount()).thenReturn(new BigDecimal("75.00"));
    partialGroup();
    doReturn(line).when(handler).loadLine(LINE_ID);

    JSONObject data = dataOf(ReconciliationFlowSupport.compose(handler, account, line,
        List.of(OP_ID)));

    assertTrue(data.getBoolean(ReconciliationLineTargetSupport.KEY_PARTIAL));
    assertEquals(0, new BigDecimal("-25.00").compareTo(pendingAmountOf(data)));
    assertEquals(REM_ID, data.getString(KEY_REMAINDER));
  }

  @Test
  void testComposeReportsACompleteMatch() throws Exception {
    stubCompose();
    when(operation.getDepositAmount()).thenReturn(new BigDecimal(HUNDRED));
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response =
        ReconciliationFlowSupport.compose(handler, account, line, List.of(OP_ID));

    assertEquals(201, response.getHttpStatus());
    assertCompleteOutcome(dataOf(response));
    // Read back from Core's state after processing, never predicted from the request.
    verify(handler).loadLine(LINE_ID);
  }

  /**
   * W1 regression: operations that overshoot the line by less than the tolerance
   * {@code validateOperations} accepts (100.01 against 100.00) close the line. The old prediction
   * (line minus operations = -0.01) reported {@code partial:true} with no remainder to continue.
   */
  @Test
  void testReconcileGroupWithinToleranceOverpayIsComplete() throws Exception {
    stubCompose();
    when(operation.getDepositAmount()).thenReturn(new BigDecimal("100.01"));
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.reconcileGroup(reconcileBody(false, OP_ID));

    assertEquals(201, response.getHttpStatus());
    assertCompleteOutcome(dataOf(response));
    verify(handler).processReconciliation(reconciliation);
  }

  // ── S4 / W2: applySuggestions and draft-held lines ──────────────────────────

  @Test
  void testApplySuggestionsRejectsTheSameLineTwiceBeforeAnyWrite() throws Exception {
    when(operation.getDepositAmount()).thenReturn(new BigDecimal(HUNDRED));
    doReturn(operation).when(handler).loadTransaction(OP_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    List<ReconciliationHandler.PreparedGroup> prepared = new ArrayList<>();
    JSONArray results = new JSONArray();

    ReconciliationFlowSupport.prepareAllGroups(handler, account,
        new JSONArray().put(group(LINE_ID, OP_ID)).put(group(LINE_ID, OP_ID)), prepared, results);

    assertEquals(1, prepared.size());
    assertSame(line, prepared.get(0).line);
    assertEquals(1, results.length());
    assertDuplicateRejection(results.getJSONObject(0), LINE_ID);
    verifyNoWriteSeam();
  }

  @Test
  void testApplySuggestionsRejectsAPartialHeadAfterItsRemainder() throws Exception {
    partialGroup();
    when(reconciliation.isProcessed()).thenReturn(Boolean.TRUE);
    when(operation.getReconciliation()).thenReturn(reconciliation);
    when(freeOperation.getAccount()).thenReturn(account);
    when(freeOperation.getDepositAmount()).thenReturn(new BigDecimal(FORTY));
    when(freeOperation.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    doReturn(freeOperation).when(handler).loadTransaction(FREE_OP_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(remainder).when(handler).loadLine(REM_ID);
    List<ReconciliationHandler.PreparedGroup> prepared = new ArrayList<>();
    JSONArray results = new JSONArray();

    // The remainder first, then its group head: the head is redirected to the SAME remainder.
    ReconciliationFlowSupport.prepareAllGroups(handler, account, new JSONArray()
        .put(group(REM_ID, FREE_OP_ID)).put(group(LINE_ID, FREE_OP_ID)), prepared, results);

    assertEquals(1, prepared.size());
    assertSame(remainder, prepared.get(0).line);
    assertEquals(1, results.length());
    assertDuplicateRejection(results.getJSONObject(0), LINE_ID);
    verifyNoWriteSeam();
  }

  @Test
  void testApplySuggestionsRejectsTheRemainderAfterItsPartialHead() throws Exception {
    partialGroup();
    when(reconciliation.isProcessed()).thenReturn(Boolean.TRUE);
    when(operation.getReconciliation()).thenReturn(reconciliation);
    when(freeOperation.getAccount()).thenReturn(account);
    when(freeOperation.getDepositAmount()).thenReturn(new BigDecimal(FORTY));
    when(freeOperation.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    doReturn(freeOperation).when(handler).loadTransaction(FREE_OP_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);
    doReturn(remainder).when(handler).loadLine(REM_ID);
    List<ReconciliationHandler.PreparedGroup> prepared = new ArrayList<>();
    JSONArray results = new JSONArray();

    ReconciliationFlowSupport.prepareAllGroups(handler, account, new JSONArray()
        .put(group(LINE_ID, FREE_OP_ID)).put(group(REM_ID, FREE_OP_ID)), prepared, results);

    assertEquals(1, prepared.size());
    assertSame(remainder, prepared.get(0).line);
    assertDuplicateRejection(results.getJSONObject(0), REM_ID);
    verifyNoWriteSeam();
  }

  @Test
  void testReconcileGroupRefusesADraftHeldLineBeforeAnyWrite() throws Exception {
    when(reconciliation.isProcessed()).thenReturn(Boolean.FALSE);
    when(reconciliation.getDocumentNo()).thenReturn(DRAFT_DOC_NO);
    when(operation.getReconciliation()).thenReturn(reconciliation);
    when(line.getFinancialAccountTransaction()).thenReturn(operation);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response;
    try (MockedStatic<ReconciliationPaymentService> payments =
        Mockito.mockStatic(ReconciliationPaymentService.class)) {
      response = handler.reconcileGroup(reconcileBody(true, OP_ID));
      payments.verifyNoInteractions();
    }

    assertEquals(409, response.getHttpStatus());
    assertEquals(ReconciliationLineTargetSupport.draftHoldsLineMessage(DRAFT_DOC_NO),
        errorMessage(response));
    verify(line, never()).setFinancialAccountTransaction(any());
    verify(line, never()).setMatchingtype(any());
    verify(line, never()).setMatchedDocument(any());
    verify(operation, never()).setReconciliation(any());
    verify(operation, never()).setStatus(any());
    verify(reconciliation, never()).getFINFinaccTransactionList();
    verify(dal, never()).remove(any());
    verifyNoInteractions(dal);
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).normalizeReactivatedMatchGroup(any());
  }

  private static JSONObject group(String lineId, String opId) throws Exception {
    return new JSONObject().put(KEY_STATEMENT_LINE_ID, lineId)
        .put(KEY_OPERATION_IDS, new JSONArray().put(opId));
  }

  private static void assertDuplicateRejection(JSONObject failure, String requestedId)
      throws Exception {
    assertEquals(409, failure.getJSONObject(KEY_ERROR).getInt("status"));
    assertEquals(ReconciliationHandler.MSG_LINE_ALREADY_RECONCILED + ": " + requestedId,
        failure.getJSONObject(KEY_ERROR).getString(KEY_MESSAGE));
    assertEquals(requestedId, failure.getString(KEY_STATEMENT_LINE_ID));
  }

  /** Preparing a batch never matches, heals or persists anything. */
  private void verifyNoWriteSeam() throws Exception {
    verify(handler, never()).matchBankStatementLine(any(), any(), any());
    verify(handler, never()).addNewDraftReconciliation(any());
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).normalizeReactivatedMatchGroup(any());
    verify(line, never()).setFinancialAccountTransaction(any());
    verifyNoInteractions(dal);
  }

  private static BigDecimal pendingAmountOf(JSONObject data) throws Exception {
    return new BigDecimal(data.getString(ReconciliationLineTargetSupport.KEY_PENDING_AMOUNT));
  }

  private static void assertCompleteOutcome(JSONObject data) throws Exception {
    assertFalse(data.getBoolean(ReconciliationLineTargetSupport.KEY_PARTIAL));
    assertEquals(0, BigDecimal.ZERO.compareTo(pendingAmountOf(data)));
    assertFalse(data.has(KEY_REMAINDER));
  }

  // ── B3: candidates on a partial head list the remainder ─────────────────────

  @Test
  void testCandidatesOnAPartialHeadAreBuiltForTheRemainder() throws Exception {
    partialGroup();
    when(reconciliation.isProcessed()).thenReturn(Boolean.TRUE);
    when(operation.getReconciliation()).thenReturn(reconciliation);
    Map<String, String> qp = new HashMap<>();
    qp.put(ReconciliationHandler.PARAM_ACCOUNT_ID, ACC_ID);
    qp.put(ReconciliationHandler.PARAM_LINE_ID, LINE_ID);
    when(context.getQueryParams()).thenReturn(qp);
    when(routedHandler.loadLine(LINE_ID)).thenReturn(line);
    when(routedHandler.buildCandidates(eq(ACC_ID), eq(REM_ID), isNull(), isNull(), isNull()))
        .thenReturn(ReconciliationSupport.envelope(
            new JSONObject().put("candidates", new JSONArray())));

    NeoResponse response = ReconciliationHandlerSupport.handleCandidates(routedHandler, context);

    assertEquals(200, response.getHttpStatus());
    assertEquals(REM_ID, dataOf(response).getString(KEY_REMAINDER));
    verify(routedHandler, never()).buildCandidates(eq(ACC_ID), eq(LINE_ID), any(), any(), any());
    verifyNoInteractions(dal);
  }

  @Test
  void testCandidatesOnAPendingLineAreNotRedirected() throws Exception {
    Map<String, String> qp = new HashMap<>();
    qp.put(ReconciliationHandler.PARAM_ACCOUNT_ID, ACC_ID);
    qp.put(ReconciliationHandler.PARAM_LINE_ID, LINE_ID);
    qp.put(ReconciliationHandler.PARAM_KIND, ReconciliationHandler.KIND_INVOICES);
    when(context.getQueryParams()).thenReturn(qp);
    when(routedHandler.loadLine(LINE_ID)).thenReturn(line);
    when(routedHandler.buildInvoiceCandidates(eq(ACC_ID), eq(LINE_ID), any(), any(), any()))
        .thenReturn(ReconciliationSupport.envelope(new JSONObject()));

    NeoResponse response = ReconciliationHandlerSupport.handleCandidates(routedHandler, context);

    assertEquals(200, response.getHttpStatus());
    assertFalse(dataOf(response).has(KEY_REMAINDER));
  }
}
