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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;

/**
 * Unit tests for {@link ReconciliationLineTargetSupport} (ETP-5472): which statement line a
 * reconcile request really operates on (plain / partial-group head / fully reconciled / stuck), the
 * stuck-line heal, the read-only candidates redirect and the {@code partial} outcome of a match.
 *
 * <p>The handler is a plain mock — every DAL seam the support class needs goes through it. {@link
 * OBDal} and {@link ModelProvider} (the match-group id is an extension column read through the
 * model, see {@code ReactivationSupport.readMatchGroupId}) are mocked statically for every test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationLineTargetSupportTest {

  private static final String ACC_ID = "acc-1";
  private static final String OTHER_ACC_ID = "acc-2";
  private static final String LINE_ID = "line-head";
  private static final String REM_ID = "line-rem";
  private static final String SIBLING_ID = "line-sibling";
  private static final String GROUP_ID = "grp-1";
  private static final String MATCH_GROUP_PROPERTY = "matchGroupId";
  private static final String TRX_ID = "trx-own";
  private static final String SIBLING_TRX_ID = "trx-sibling";
  private static final String FOREIGN_TRX_ID = "trx-foreign";
  private static final String DRAFT_ID = "rec-draft";
  private static final String DRAFT_DOC_NO = "REC-1000";
  private static final String CONFLICT = ReconciliationHandler.MSG_LINE_ALREADY_RECONCILED;
  private static final String KEY_ERROR = "error";
  private static final String STATUS_CLEARED = "RPPC";
  private static final String DRAFT_HOLDS_MESSAGE =
      ReconciliationLineTargetSupport.draftHoldsLineMessage(DRAFT_DOC_NO);
  private static final String HUNDRED = "100.00";
  private static final String MINUS_FORTY = "-40.00";
  private static final String FORTY = "40.00";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_REMAINDER = ReconciliationDifferenceSupport.KEY_REMAINDER_LINE_ID;

  @Mock
  private ReconciliationHandler handler;
  @Mock
  private FIN_FinancialAccount account;
  @Mock
  private FIN_FinancialAccount otherAccount;
  @Mock
  private FIN_BankStatement statement;
  @Mock
  private FIN_BankStatementLine line;
  @Mock
  private FIN_BankStatementLine remainder;
  @Mock
  private FIN_BankStatementLine sibling;
  @Mock
  private FIN_FinaccTransaction trx;
  @Mock
  private FIN_FinaccTransaction siblingTrx;
  @Mock
  private FIN_FinaccTransaction foreignTrx;
  @Mock
  private FIN_Payment payment;
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

  private MockedStatic<OBDal> obDal;
  private MockedStatic<ModelProvider> model;

  @BeforeEach
  void setUp() {
    obDal = Mockito.mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    model = Mockito.mockStatic(ModelProvider.class);
    model.when(ModelProvider::getInstance).thenReturn(modelProvider);
    when(modelProvider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(lineEntity);
    when(lineEntity.getPropertyByColumnName(ReactivationSupport.COL_MATCH_GROUP, false))
        .thenReturn(matchGroupProperty);
    when(matchGroupProperty.getName()).thenReturn(MATCH_GROUP_PROPERTY);

    when(account.getId()).thenReturn(ACC_ID);
    when(otherAccount.getId()).thenReturn(OTHER_ACC_ID);
    when(statement.getAccount()).thenReturn(account);
    when(statement.isProcessed()).thenReturn(Boolean.TRUE);

    stubRow(line, LINE_ID, HUNDRED, trx);
    stubRow(remainder, REM_ID, FORTY, null);
    stubRow(sibling, SIBLING_ID, "10.00", siblingTrx);

    when(trx.getId()).thenReturn(TRX_ID);
    when(trx.getFinPayment()).thenReturn(payment);
    when(trx.getDepositAmount()).thenReturn(new BigDecimal(HUNDRED));
    when(trx.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(siblingTrx.getId()).thenReturn(SIBLING_TRX_ID);
    when(siblingTrx.getDepositAmount()).thenReturn(new BigDecimal("10.00"));
    when(siblingTrx.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(foreignTrx.getId()).thenReturn(FOREIGN_TRX_ID);

    when(reconciliation.getId()).thenReturn(DRAFT_ID);
    when(reconciliation.getDocumentNo()).thenReturn(DRAFT_DOC_NO);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
    model.close();
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** A credit row of {@link #statement}, carrying {@code matched} as its transaction (or none). */
  private void stubRow(FIN_BankStatementLine row, String id, String credit,
      FIN_FinaccTransaction matched) {
    when(row.getId()).thenReturn(id);
    when(row.getBankStatement()).thenReturn(statement);
    when(row.getCramount()).thenReturn(new BigDecimal(credit));
    when(row.getDramount()).thenReturn(BigDecimal.ZERO);
    when(row.isActive()).thenReturn(true);
    when(row.getFinancialAccountTransaction()).thenReturn(matched);
  }

  /** Puts {@code rows} in one match group, the first one being the requested {@link #line}. */
  private void groupOf(FIN_BankStatementLine... rows) {
    for (FIN_BankStatementLine row : rows) {
      when(row.get(MATCH_GROUP_PROPERTY)).thenReturn(GROUP_ID);
    }
    List<FIN_BankStatementLine> list = new ArrayList<>(List.of(rows));
    when(handler.loadMatchGroupLines(statement, GROUP_ID)).thenReturn(list);
  }

  private void processed() {
    when(reconciliation.isProcessed()).thenReturn(Boolean.TRUE);
    when(trx.getReconciliation()).thenReturn(reconciliation);
  }

  /** The line's transaction sits in an unprocessed draft that holds {@code held}. */
  private List<FIN_FinaccTransaction> onDraftHolding(FIN_FinaccTransaction... held) {
    when(reconciliation.isProcessed()).thenReturn(Boolean.FALSE);
    when(trx.getReconciliation()).thenReturn(reconciliation);
    List<FIN_FinaccTransaction> list = new ArrayList<>(List.of(held));
    when(reconciliation.getFINFinaccTransactionList()).thenReturn(list);
    return list;
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject(KEY_ERROR).getString(KEY_MESSAGE);
  }

  private static JSONObject dataOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private void assertLineDetached(FIN_BankStatementLine row) {
    verify(row).setFinancialAccountTransaction(null);
    verify(row).setMatchingtype(null);
    verify(row).setMatchedDocument(null);
    verify(dal).save(row);
  }

  /** Nothing was written: no DAL call, no setter on the line or its transaction. */
  private void assertNoWrite() throws Exception {
    verifyNoInteractions(dal);
    verify(line, never()).setFinancialAccountTransaction(any());
    verify(line, never()).setMatchingtype(any());
    verify(line, never()).setMatchedDocument(any());
    verify(trx, never()).setReconciliation(any());
    verify(trx, never()).setStatus(any());
    verify(reconciliation, never()).getFINFinaccTransactionList();
    verify(handler, never()).normalizeReactivatedMatchGroup(any());
  }

  // ── resolveForMatch ─────────────────────────────────────────────────────────

  @Test
  void testResolveForMatchReturnsThePendingLineUnchanged() throws Exception {
    when(line.getFinancialAccountTransaction()).thenReturn(null);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertSame(line, target.line());
    assertNull(target.error());
    verifyNoInteractions(handler, dal);
  }

  @Test
  void testResolveForMatchRedirectsAPartialHeadToItsRemainder() throws Exception {
    processed();
    groupOf(line, remainder);
    when(handler.loadLine(REM_ID)).thenReturn(remainder);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertNull(target.error());
    assertSame(remainder, target.line());
    // A redirect is read-only: nothing is healed or saved.
    verifyNoInteractions(dal);
    verify(line, never()).setFinancialAccountTransaction(any());
  }

  @Test
  void testResolveForMatchRefusesAFullyReconciledLineWith409() throws Exception {
    processed();
    groupOf(line);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertNull(target.line());
    assertEquals(409, target.error().getHttpStatus());
    assertEquals(CONFLICT, errorMessage(target.error()));
    assertFalse(target.error().getBody().has(KEY_REMAINDER));
    verifyNoInteractions(dal);
  }

  @Test
  void testResolveForMatchRefusesALineWithoutGroupWith409() throws Exception {
    processed();

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT + ": " + LINE_ID);

    assertEquals(409, target.error().getHttpStatus());
    assertEquals(CONFLICT + ": " + LINE_ID, errorMessage(target.error()));
    verify(handler, never()).loadLine(any());
  }

  @Test
  void testResolveForMatchRefusalNamesTheRemainderItCouldNotLoad() throws Exception {
    processed();
    groupOf(line, remainder);
    when(handler.loadLine(REM_ID)).thenReturn(null);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertEquals(409, target.error().getHttpStatus());
    assertEquals(CONFLICT, errorMessage(target.error()));
    assertEquals(REM_ID, target.error().getBody().getString(KEY_REMAINDER));
  }

  @Test
  void testAlreadyReconciledCarriesTheSuppliedRemainderId() throws Exception {
    NeoResponse withId = ReconciliationDifferenceSupport.alreadyReconciled(CONFLICT, REM_ID);
    NeoResponse withoutId = ReconciliationDifferenceSupport.alreadyReconciled(CONFLICT, " ");

    assertEquals(409, withId.getHttpStatus());
    assertEquals(CONFLICT, errorMessage(withId));
    assertEquals(409, withId.getBody().getJSONObject(KEY_ERROR).getInt("status"));
    assertEquals(REM_ID, withId.getBody().getString(KEY_REMAINDER));
    assertFalse(withoutId.getBody().has(KEY_REMAINDER));
  }

  @Test
  void testResolveForMatchRefusesAStuckLineOnADraftStatement() throws Exception {
    when(trx.getReconciliation()).thenReturn(null);
    when(statement.isProcessed()).thenReturn(Boolean.FALSE);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertEquals(409, target.error().getHttpStatus());
    assertEquals(ReconciliationHandler.MSG_LINE_ON_DRAFT_STATEMENT, errorMessage(target.error()));
    verifyNoInteractions(dal);
    verify(line, never()).setFinancialAccountTransaction(any());
  }

  @Test
  void testResolveForMatchHealsAStuckLineWithoutReconciliation() throws Exception {
    when(trx.getReconciliation()).thenReturn(null);
    when(handler.normalizeReactivatedMatchGroup(line)).thenReturn(line);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertNull(target.error());
    assertSame(line, target.line());
    assertLineDetached(line);
    verify(dal).flush();
    verify(handler).normalizeReactivatedMatchGroup(line);
    // No draft involved: the transaction itself is not touched, and nothing is deleted.
    verify(trx, never()).setReconciliation(any());
    verify(dal, never()).remove(any());
  }

  @Test
  void testResolveForMatchReturnsTheCollapsedLineAfterTheHeal() throws Exception {
    when(trx.getReconciliation()).thenReturn(null);
    when(handler.normalizeReactivatedMatchGroup(line)).thenReturn(remainder);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertSame(remainder, target.line());
  }

  @Test
  void testResolveForMatchRefusesALineHeldByItsOwnDraftBeforeAnyWrite() throws Exception {
    List<FIN_FinaccTransaction> held = onDraftHolding(trx);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertNull(target.line());
    assertEquals(409, target.error().getHttpStatus());
    assertEquals(DRAFT_HOLDS_MESSAGE, errorMessage(target.error()));
    assertNoWrite();
    // The draft is unconfirmed work: never emptied, never removed.
    assertEquals(1, held.size());
  }

  @Test
  void testResolveForMatchRefusesALineHeldByAForeignDraftBeforeAnyWrite() throws Exception {
    List<FIN_FinaccTransaction> held = onDraftHolding(trx, foreignTrx);

    ReconciliationLineTargetSupport.Target target =
        ReconciliationLineTargetSupport.resolveForMatch(handler, line, CONFLICT);

    assertNull(target.line());
    assertEquals(409, target.error().getHttpStatus());
    assertEquals(DRAFT_HOLDS_MESSAGE, errorMessage(target.error()));
    assertNoWrite();
    assertEquals(2, held.size());
  }

  @Test
  void testDraftHoldsLineMessageKeepsItsWireText() {
    assertEquals("Reconciliation " + DRAFT_DOC_NO + " is an unconfirmed draft that already holds "
        + "this line. Review it before reconciling the line again.", DRAFT_HOLDS_MESSAGE);
  }

  // ── healStuckLine ───────────────────────────────────────────────────────────

  @Test
  void testHealStuckLineIsANoOpWithoutTransaction() {
    when(line.getFinancialAccountTransaction()).thenReturn(null);

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));
    verifyNoInteractions(dal);
  }

  @Test
  void testHealStuckLineFreesTheSiblingsStuckTheSameWay() {
    when(trx.getReconciliation()).thenReturn(null);
    when(siblingTrx.getReconciliation()).thenReturn(null);
    groupOf(line, sibling);

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    assertLineDetached(line);
    assertLineDetached(sibling);
  }

  @Test
  void testHealStuckLineLeavesASiblingOfAConfirmedReconciliationAlone() {
    when(trx.getReconciliation()).thenReturn(null);
    when(reconciliation.isProcessed()).thenReturn(Boolean.TRUE);
    when(siblingTrx.getReconciliation()).thenReturn(reconciliation);
    groupOf(line, sibling);

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    assertLineDetached(line);
    verify(sibling, never()).setFinancialAccountTransaction(any());
    verify(siblingTrx, never()).setReconciliation(any());
  }

  @Test
  void testHealStuckLineLeavesASiblingHeldInADraftAlone() {
    when(trx.getReconciliation()).thenReturn(null);
    when(reconciliation.isProcessed()).thenReturn(Boolean.FALSE);
    when(siblingTrx.getReconciliation()).thenReturn(reconciliation);
    groupOf(line, sibling);

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    assertLineDetached(line);
    verify(sibling, never()).setFinancialAccountTransaction(any());
    verify(sibling, never()).setMatchingtype(any());
    verify(sibling, never()).setMatchedDocument(any());
    verify(dal, never()).save(sibling);
    verify(siblingTrx, never()).setReconciliation(any());
    verify(siblingTrx, never()).setStatus(any());
    verify(dal, never()).remove(any());
  }

  @Test
  void testHealStuckLineRefusesADraftHeldLine() throws Exception {
    when(siblingTrx.getReconciliation()).thenReturn(reconciliation);
    onDraftHolding(trx, siblingTrx);
    groupOf(line, sibling);

    NeoResponse refusal = ReconciliationLineTargetSupport.healStuckLine(handler, line);

    assertEquals(409, refusal.getHttpStatus());
    assertEquals(DRAFT_HOLDS_MESSAGE, errorMessage(refusal));
    assertNoWrite();
    verify(sibling, never()).setFinancialAccountTransaction(any());
  }

  @Test
  void testHealStuckLineRestoresAClearedTransactionToNotCleared() {
    when(trx.getReconciliation()).thenReturn(null);
    when(trx.getStatus()).thenReturn(STATUS_CLEARED);

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    assertLineDetached(line);
    // Inflow (deposit 100) → "deposited, not cleared", so the candidates query can list it again.
    verify(trx).setStatus("RDNC");
    verify(dal).save(trx);
  }

  @Test
  void testHealStuckLineRestoresAClearedOutflowToPaymentNotCleared() {
    when(trx.getReconciliation()).thenReturn(null);
    when(trx.getStatus()).thenReturn(STATUS_CLEARED);
    when(trx.getDepositAmount()).thenReturn(BigDecimal.ZERO);
    when(trx.getPaymentAmount()).thenReturn(new BigDecimal("30.00"));

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    verify(trx).setStatus("PWNC");
  }

  @Test
  void testHealStuckLineKeepsANotClearedStatus() {
    when(trx.getReconciliation()).thenReturn(null);
    when(trx.getStatus()).thenReturn("PWNC");

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    verify(trx, never()).setStatus(any());
    verify(dal, never()).save(trx);
  }

  @Test
  void testHealStuckLineNeverDeletesTheTransactionOrItsPayment() {
    when(trx.getReconciliation()).thenReturn(null);
    when(trx.getStatus()).thenReturn(STATUS_CLEARED);

    assertNull(ReconciliationLineTargetSupport.healStuckLine(handler, line));

    assertLineDetached(line);
    verify(dal, never()).remove(any());
    verify(trx, never()).setFinPayment(any());
    verify(trx, never()).setActive(any());
    verify(trx, never()).setReconciliation(any());
  }

  // ── reactivateStuckLine ─────────────────────────────────────────────────────

  @Test
  void testReactivateStuckLineAnswers200Healed() throws Exception {
    when(trx.getReconciliation()).thenReturn(null);
    NeoResponse response;
    try (MockedStatic<ReconciliationRemovalUtil> removal =
        Mockito.mockStatic(ReconciliationRemovalUtil.class)) {
      removal.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(account))
          .thenReturn(Collections.emptyList());
      response = ReconciliationLineTargetSupport.reactivateStuckLine(handler, account, line);
    }

    assertEquals(200, response.getHttpStatus());
    JSONObject data = dataOf(response);
    assertTrue(data.getBoolean("reactivated"));
    assertTrue(data.getBoolean(ReconciliationLineTargetSupport.KEY_HEALED));
    assertEquals(LINE_ID, data.getString(ReconciliationHandler.KEY_STATEMENT_LINE_ID));
    assertEquals(0, BigDecimal.ZERO.compareTo(
        new BigDecimal(data.getString(ReconciliationHandler.KEY_UPDATED_BALANCE))));
    assertLineDetached(line);
    verify(handler).normalizeReactivatedMatchGroup(line);
    verify(dal, never()).remove(trx);
  }

  @Test
  void testReactivateStuckLinePassesTheDraftHoldsLineRefusalThrough() throws Exception {
    onDraftHolding(trx, foreignTrx);

    NeoResponse response =
        ReconciliationLineTargetSupport.reactivateStuckLine(handler, account, line);

    assertEquals(409, response.getHttpStatus());
    assertEquals(DRAFT_HOLDS_MESSAGE, errorMessage(response));
    assertNoWrite();
  }

  // ── candidateLineId / withRedirect ──────────────────────────────────────────

  @Test
  void testCandidateLineIdRedirectsAPartialHeadToItsRemainder() {
    processed();
    groupOf(line, remainder);
    when(handler.loadLine(LINE_ID)).thenReturn(line);

    assertEquals(REM_ID, ReconciliationLineTargetSupport.candidateLineId(handler, ACC_ID, LINE_ID));
    verifyNoInteractions(dal);
  }

  @Test
  void testCandidateLineIdKeepsAFullyReconciledLine() {
    processed();
    groupOf(line);
    when(handler.loadLine(LINE_ID)).thenReturn(line);

    assertEquals(LINE_ID, ReconciliationLineTargetSupport.candidateLineId(handler, ACC_ID, LINE_ID));
  }

  @Test
  void testCandidateLineIdNeverHealsAStuckLine() {
    when(trx.getReconciliation()).thenReturn(null);
    when(handler.loadLine(LINE_ID)).thenReturn(line);

    assertEquals(LINE_ID, ReconciliationLineTargetSupport.candidateLineId(handler, ACC_ID, LINE_ID));
    verifyNoInteractions(dal);
    verify(line, never()).setFinancialAccountTransaction(any());
  }

  @Test
  void testCandidateLineIdIgnoresALineOfAnotherAccount() {
    processed();
    groupOf(line, remainder);
    when(handler.loadLine(LINE_ID)).thenReturn(line);

    assertEquals(LINE_ID,
        ReconciliationLineTargetSupport.candidateLineId(handler, OTHER_ACC_ID, LINE_ID));
    verify(handler, never()).loadMatchGroupLines(any(), any());
  }

  @Test
  void testCandidateLineIdPassesABlankOrUnknownLineThrough() {
    when(handler.loadLine(LINE_ID)).thenReturn(null);

    assertNull(ReconciliationLineTargetSupport.candidateLineId(handler, ACC_ID, null));
    assertEquals(LINE_ID, ReconciliationLineTargetSupport.candidateLineId(handler, ACC_ID, LINE_ID));
  }

  @Test
  void testWithRedirectAddsTheRemainderLineId() throws Exception {
    NeoResponse listed = ReconciliationSupport.envelope(new JSONObject().put("candidates", 0));

    NeoResponse result = ReconciliationLineTargetSupport.withRedirect(listed, LINE_ID, REM_ID);

    assertSame(listed, result);
    assertEquals(REM_ID, dataOf(result).getString(KEY_REMAINDER));
  }

  @Test
  void testWithRedirectIsANoOpWithoutRedirectOrData() throws Exception {
    NeoResponse listed = ReconciliationSupport.envelope(new JSONObject());
    NeoResponse error = NeoResponse.error(500, ReconciliationHandler.MSG_INTERNAL_SERVER_ERROR);

    ReconciliationLineTargetSupport.withRedirect(listed, LINE_ID, LINE_ID);
    NeoResponse errorResult = ReconciliationLineTargetSupport.withRedirect(error, LINE_ID, REM_ID);

    assertFalse(dataOf(listed).has(KEY_REMAINDER));
    assertSame(error, errorResult);
    assertFalse(errorResult.getBody().has(KEY_REMAINDER));
    assertNull(ReconciliationLineTargetSupport.withRedirect(null, LINE_ID, REM_ID));
  }

  // ── putMatchOutcome ─────────────────────────────────────────────────────────

  @Test
  void testPutMatchOutcomeReportsAPartialMatchFromTheRemainderRow() throws Exception {
    groupOf(line, remainder);
    when(handler.loadLine(LINE_ID)).thenReturn(line);
    JSONObject data = new JSONObject();

    ReconciliationLineTargetSupport.putMatchOutcome(handler, data, LINE_ID);

    assertTrue(data.getBoolean(ReconciliationLineTargetSupport.KEY_PARTIAL));
    // The remainder row's own signed amount (a 40.00 credit), not a prediction from the request.
    assertEquals(0, new BigDecimal(FORTY).compareTo(pendingAmountOf(data)));
    assertEquals(REM_ID, data.getString(KEY_REMAINDER));
  }

  @Test
  void testPutMatchOutcomeSignsADebitRemainderNegative() throws Exception {
    when(remainder.getCramount()).thenReturn(BigDecimal.ZERO);
    when(remainder.getDramount()).thenReturn(new BigDecimal(FORTY));
    groupOf(line, remainder);
    when(handler.loadLine(LINE_ID)).thenReturn(line);
    JSONObject data = new JSONObject();

    ReconciliationLineTargetSupport.putMatchOutcome(handler, data, LINE_ID);

    assertTrue(data.getBoolean(ReconciliationLineTargetSupport.KEY_PARTIAL));
    assertEquals(0, new BigDecimal(MINUS_FORTY).compareTo(pendingAmountOf(data)));
  }

  @Test
  void testPutMatchOutcomeReportsACompleteMatch() throws Exception {
    groupOf(line);
    when(handler.loadLine(LINE_ID)).thenReturn(line);
    JSONObject data = new JSONObject();

    ReconciliationLineTargetSupport.putMatchOutcome(handler, data, LINE_ID);

    assertCompleteOutcome(data);
    verify(handler).loadLine(LINE_ID);
  }

  @Test
  void testPutMatchOutcomeIsCompleteWhenNoRemainderRowExists() throws Exception {
    // No match group at all (the match did not split the line).
    when(handler.loadLine(LINE_ID)).thenReturn(line);
    JSONObject data = new JSONObject();

    ReconciliationLineTargetSupport.putMatchOutcome(handler, data, LINE_ID);

    assertCompleteOutcome(data);
  }

  @Test
  void testPutMatchOutcomeIsCompleteWhenTheLineCannotBeReRead() throws Exception {
    when(handler.loadLine(LINE_ID)).thenReturn(null);
    JSONObject data = new JSONObject();

    ReconciliationLineTargetSupport.putMatchOutcome(handler, data, LINE_ID);

    assertCompleteOutcome(data);
  }

  private static BigDecimal pendingAmountOf(JSONObject data) throws Exception {
    return new BigDecimal(data.getString(ReconciliationLineTargetSupport.KEY_PENDING_AMOUNT));
  }

  private static void assertCompleteOutcome(JSONObject data) throws Exception {
    assertFalse(data.getBoolean(ReconciliationLineTargetSupport.KEY_PARTIAL));
    assertEquals(0, BigDecimal.ZERO.compareTo(pendingAmountOf(data)));
    assertFalse(data.has(KEY_REMAINDER));
  }

  @Test
  void testPendingRemainderIdIsNullWithoutLineOrGroup() {
    assertNull(ReconciliationLineTargetSupport.pendingRemainderId(handler, null));
    assertNull(ReconciliationLineTargetSupport.pendingRemainderId(handler, line));
    groupOf(line, remainder);
    assertNotNull(ReconciliationLineTargetSupport.pendingRemainderId(handler, line));
  }
}
