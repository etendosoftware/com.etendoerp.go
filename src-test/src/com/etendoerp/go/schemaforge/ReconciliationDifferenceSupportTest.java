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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Mockito-driven unit tests for {@link ReconciliationDifferenceSupport} — posting the unreconciled
 * remainder of a partially reconciled bank-statement line to an accounting concept (GL item).
 *
 * <p>Two layers are covered:
 * <ul>
 *   <li><b>Pure helpers</b> ({@code differenceLimit}, {@code withinTolerance},
 *       {@code isNegligible}, {@code summarizeGroup}, {@code signedLineAmount},
 *       {@code effectiveGlItemId}, {@code differenceSpec}, {@code reconcileGroupBody}) — asserted
 *       directly, no statics.</li>
 *   <li><b>Orchestration</b> ({@code reconcileDifference}) — the handler is spied and every DAL /
 *       Classic seam it reaches through ({@code loadAccount}, {@code loadLine},
 *       {@code loadMatchGroupLines}, {@code loadTolerances}, {@code createTransactionForRule},
 *       {@code reconcileGroup}, {@code doRollbackAndClose}) is stubbed, so the whole validation
 *       order runs without a database.</li>
 * </ul>
 *
 * <p>Two statics must be mocked for the orchestration tests: {@link OBDal} (the {@code FOR UPDATE
 * NOWAIT} row lock taken before anything else, plus the GL-item existence check) and
 * {@link ModelProvider} (the match-group id lives on an extension column resolved through the
 * model — see {@code ReactivationSupport.readMatchGroupId}).
 *
 * <p>The heart of the suite is the "no write before a rejected request" invariant: a returned
 * {@code NeoResponse.error(...)} COMMITS, so every failing scenario asserts BOTH the status and
 * {@code never()} on {@code createTransactionForRule} / {@code reconcileGroup}.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationDifferenceSupportTest {

  private static final String ACC_ID = "acc-1";
  private static final String OTHER_ACC = "acc-2";
  private static final String GROUP_ID = "grp-1";
  private static final String HEAD_ID = "line-head";
  private static final String REM_ID = "line-rem";
  private static final String REM_ID_2 = "line-rem-2";
  private static final String GL_ACCOUNT_DEFAULT = "GL-ACCOUNT-DEFAULT";
  private static final String GL_PAYLOAD = "GL-PAYLOAD";
  private static final String TRX_ID = "TRX-DIFF-1";
  private static final String MATCH_GROUP_PROPERTY = "matchGroupId";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_GL_ITEM_ID = "glItemId";
  private static final String KEY_ERROR = "error";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_OPERATION_IDS = "operationIds";
  private static final String PCT_FIVE = "5";

  private ReconciliationHandler handler;
  private FIN_FinancialAccount account;
  private FIN_BankStatement statement;
  private Connection connection;

  @Before
  public void setUp() {
    handler = spy(new ReconciliationHandler());
    doNothing().when(handler).doRollbackAndClose();
    // loadTolerances uses a raw JDBC connection unavailable in unit tests. Default: 3 days and a
    // 5 % amount tolerance, i.e. the difference action is ENABLED unless a test says otherwise.
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), new BigDecimal(PCT_FIVE)})
        .when(handler).loadTolerances(any());

    account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    GLItem configured = mock(GLItem.class);
    when(configured.getId()).thenReturn(GL_ACCOUNT_DEFAULT);
    when(account.getAprmGlitemDiff()).thenReturn(configured);

    statement = mock(FIN_BankStatement.class);
    when(statement.getAccount()).thenReturn(account);
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static BigDecimal bd(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  /**
   * A statement-line row of the match group. {@code credit}/{@code debit} are the raw
   * {@code cramount}/{@code dramount}; {@code matched} decides whether the row already carries a
   * finacc transaction (i.e. whether it counts as pending).
   */
  private FIN_BankStatementLine row(String id, String credit, String debit, boolean matched) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getBankStatement()).thenReturn(statement);
    when(line.getCramount()).thenReturn(bd(credit));
    when(line.getDramount()).thenReturn(bd(debit));
    when(line.isActive()).thenReturn(true);
    when(line.get(MATCH_GROUP_PROPERTY)).thenReturn(GROUP_ID);
    when(line.getFinancialAccountTransaction())
        .thenReturn(matched ? mock(FIN_FinaccTransaction.class) : null);
    return line;
  }

  /** Resolves the {@code EM_ETGO_Match_Group_ID} extension property through a mocked model. */
  private MockedStatic<ModelProvider> mockMatchGroupProperty() {
    MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class);
    ModelProvider provider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    mp.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(entity);
    when(entity.getPropertyByColumnName(eq("EM_ETGO_Match_Group_ID"), eq(false))).thenReturn(prop);
    when(prop.getName()).thenReturn(MATCH_GROUP_PROPERTY);
    return mp;
  }

  private JSONObject body(String accountId, String lineId) throws Exception {
    JSONObject json = new JSONObject();
    if (accountId != null) {
      json.put("financialAccountId", accountId);
    }
    if (lineId != null) {
      json.put("statementLineId", lineId);
    }
    return json;
  }

  /** Extra per-test stubbing performed inside the {@link OBDal} static mock. */
  @FunctionalInterface
  private interface DalSetup {
    void apply(OBDal dal) throws Exception;
  }

  /**
   * Runs {@code reconcileDifference} with both required statics mocked and a working row lock
   * (Connection → PreparedStatement → ResultSet), letting the caller refine the {@link OBDal} mock.
   */
  private NeoResponse runAction(JSONObject requestBody, DalSetup extra) throws Exception {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> mp = mockMatchGroupProperty()) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      connection = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(connection);
      when(connection.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      if (extra != null) {
        extra.apply(dal);
      }
      return ReconciliationDifferenceSupport.reconcileDifference(handler, requestBody);
    }
  }

  private NeoResponse runAction(JSONObject requestBody) throws Exception {
    return runAction(requestBody, null);
  }

  /**
   * {@link #runAction(JSONObject, DalSetup)} for the scenarios that actually reach the write.
   *
   * <p>Since ETP-4965 the difference movement is created WITH a description, so
   * {@code reconcileDifference} resolves one through the AD_Message dictionary on its way to
   * {@code createTransactionForRule}. The real {@code messageBD} dereferences an {@code OBContext}
   * that is null under plain Mockito, so every path reaching that far has to stub the lookup —
   * exactly the treatment the {@code applyInlineDifference} tests already give it.
   *
   * @param requestBody the action payload
   * @param extra extra stubbing on the mocked {@link OBDal}, or {@code null}
   * @return the action's response
   * @throws Exception if the mocked interaction fails
   */
  private NeoResponse runActionReachingTheWrite(JSONObject requestBody, DalSetup extra)
      throws Exception {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);
      return runAction(requestBody, extra);
    }
  }

  private NeoResponse runActionReachingTheWrite(JSONObject requestBody) throws Exception {
    return runActionReachingTheWrite(requestBody, null);
  }

  /**
   * Wires the standard PARTIAL group — one matched head plus one pending remainder — and stubs the
   * write seams so a happy path can complete.
   */
  private void stubPartialGroup(String headCredit, String headDebit,
      String remCredit, String remDebit) throws Exception {
    FIN_BankStatementLine head = row(HEAD_ID, headCredit, headDebit, true);
    FIN_BankStatementLine remainder = row(REM_ID, remCredit, remDebit, false);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(remainder).when(handler).loadLine(REM_ID);
    doReturn(head).when(handler).loadLine(HEAD_ID);
    doReturn(Arrays.asList(head, remainder))
        .when(handler).loadMatchGroupLines(statement, GROUP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());
    doReturn(NeoResponse.createdWithData(new JSONObject().put("reconciliationId", "rec-1")))
        .when(handler).reconcileGroup(any());
  }

  private void assertNoWrite() throws Exception {
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).reconcileGroup(any());
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject(KEY_ERROR).getString(KEY_MESSAGE);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Pure helpers
  // ═══════════════════════════════════════════════════════════════════════════

  /** The limit is a percentage of the ORIGINAL line amount, rounded to two decimals. */
  @Test
  public void testDifferenceLimitIsPercentageOfLineTotal() {
    assertEquals(new BigDecimal("0.63"),
        ReconciliationDifferenceSupport.differenceLimit(bd("12.50"), bd(PCT_FIVE)));
    assertEquals(new BigDecimal("625.00"),
        ReconciliationDifferenceSupport.differenceLimit(bd("12500"), bd(PCT_FIVE)));
  }

  /** A negative line total yields the same (absolute) limit as its positive twin. */
  @Test
  public void testDifferenceLimitUsesAbsoluteLineTotal() {
    assertEquals(new BigDecimal("0.63"),
        ReconciliationDifferenceSupport.differenceLimit(bd("-12.50"), bd(PCT_FIVE)));
  }

  /**
   * A zero / negative / unset percentage disables the action (limit 0) — the deliberate divergence
   * from {@code AutoMatchSupport.signalGroupTolerance}, which reads the same column as "one cent".
   */
  @Test
  public void testDifferenceLimitZeroWhenPercentageUnsetOrNonPositive() {
    assertEquals(0,
        ReconciliationDifferenceSupport.differenceLimit(bd("12.50"), BigDecimal.ZERO).signum());
    assertEquals(0,
        ReconciliationDifferenceSupport.differenceLimit(bd("12.50"), null).signum());
    assertEquals(0,
        ReconciliationDifferenceSupport.differenceLimit(bd("12.50"), bd("-5")).signum());
  }

  /** A null line total contributes nothing, so the limit collapses to zero. */
  @Test
  public void testDifferenceLimitZeroWhenLineTotalNull() {
    assertEquals(0,
        ReconciliationDifferenceSupport.differenceLimit(null, bd(PCT_FIVE)).signum());
  }

  /** The tolerance check is inclusive on the limit and sign-blind. */
  @Test
  public void testWithinToleranceBoundary() {
    assertTrue(ReconciliationDifferenceSupport.withinTolerance(bd("0.63"), bd("0.63")));
    assertFalse(ReconciliationDifferenceSupport.withinTolerance(bd("0.64"), bd("0.63")));
    assertTrue(ReconciliationDifferenceSupport.withinTolerance(bd("-0.50"), bd("0.63")));
    // A null remainder is zero, which is within any non-negative limit.
    assertTrue(ReconciliationDifferenceSupport.withinTolerance(null, BigDecimal.ZERO));
  }

  /** Below half a cent (exclusive) there is nothing worth posting. */
  @Test
  public void testIsNegligible() {
    assertTrue(ReconciliationDifferenceSupport.isNegligible(BigDecimal.ZERO));
    assertTrue(ReconciliationDifferenceSupport.isNegligible(bd("0.004")));
    assertTrue(ReconciliationDifferenceSupport.isNegligible(bd("-0.004")));
    assertTrue(ReconciliationDifferenceSupport.isNegligible(null));
    assertFalse(ReconciliationDifferenceSupport.isNegligible(bd("0.005")));
    assertFalse(ReconciliationDifferenceSupport.isNegligible(bd("-0.01")));
  }

  /** {@code cramount - dramount}, with nulls read as zero. */
  @Test
  public void testSignedLineAmount() {
    assertEquals(0, ReconciliationDifferenceSupport
        .signedLineAmount(row(REM_ID, "0.50", null, false)).compareTo(bd("0.50")));
    assertEquals(0, ReconciliationDifferenceSupport
        .signedLineAmount(row(REM_ID, null, "0.50", false)).compareTo(bd("-0.50")));
  }

  /** An inflow group: the total is the pre-split logical amount, the remainder the target row. */
  @Test
  public void testSummarizeGroupInflow() {
    FIN_BankStatementLine head = row(HEAD_ID, "12.00", null, true);
    FIN_BankStatementLine remainder = row(REM_ID, "0.50", null, false);

    ReconciliationDifferenceSupport.GroupSnapshot snap = ReconciliationDifferenceSupport
        .summarizeGroup(Arrays.asList(head, remainder), REM_ID);

    assertEquals(0, snap.groupTotal().compareTo(bd("12.50")));
    assertEquals(0, snap.remainder().compareTo(bd("0.50")));
    assertEquals(1, snap.pendingCount());
    assertEquals(REM_ID, snap.remainderLineId());
  }

  /** An outflow group keeps the sign: both the total and the remainder are negative. */
  @Test
  public void testSummarizeGroupOutflow() {
    FIN_BankStatementLine head = row(HEAD_ID, null, "12.00", true);
    FIN_BankStatementLine remainder = row(REM_ID, null, "0.50", false);

    ReconciliationDifferenceSupport.GroupSnapshot snap = ReconciliationDifferenceSupport
        .summarizeGroup(Arrays.asList(head, remainder), REM_ID);

    assertEquals(0, snap.groupTotal().compareTo(bd("-12.50")));
    assertEquals(0, snap.remainder().compareTo(bd("-0.50")));
    assertEquals(1, snap.pendingCount());
    assertEquals(REM_ID, snap.remainderLineId());
  }

  /** Two unmatched rows (a reactivated line) report pendingCount 2 and the FIRST pending id. */
  @Test
  public void testSummarizeGroupCountsEveryPendingRow() {
    FIN_BankStatementLine first = row(REM_ID, "0.30", null, false);
    FIN_BankStatementLine second = row(REM_ID_2, "0.20", null, false);

    ReconciliationDifferenceSupport.GroupSnapshot snap = ReconciliationDifferenceSupport
        .summarizeGroup(Arrays.asList(first, second), REM_ID_2);

    assertEquals(2, snap.pendingCount());
    assertEquals(REM_ID, snap.remainderLineId());
    assertEquals(0, snap.remainder().compareTo(bd("0.20")));
  }

  /** A single-row group has nothing reconciled: the total IS the remainder. */
  @Test
  public void testSummarizeGroupSingleRow() {
    FIN_BankStatementLine only = row(REM_ID, "12.50", null, false);

    ReconciliationDifferenceSupport.GroupSnapshot snap = ReconciliationDifferenceSupport
        .summarizeGroup(Collections.singletonList(only), REM_ID);

    assertEquals(0, snap.groupTotal().compareTo(snap.remainder()));
    assertEquals(0, snap.groupTotal().compareTo(bd("12.50")));
  }

  /**
   * An INACTIVE sibling is skipped entirely: counting it would inflate groupTotal and thereby
   * loosen the percentage tolerance gate.
   */
  @Test
  public void testSummarizeGroupSkipsInactiveSibling() {
    FIN_BankStatementLine head = row(HEAD_ID, "12.00", null, true);
    FIN_BankStatementLine remainder = row(REM_ID, "0.50", null, false);
    FIN_BankStatementLine ghost = row("line-ghost", "500.00", null, false);
    when(ghost.isActive()).thenReturn(false);

    ReconciliationDifferenceSupport.GroupSnapshot snap = ReconciliationDifferenceSupport
        .summarizeGroup(Arrays.asList(head, remainder, ghost), REM_ID);

    assertEquals(0, snap.groupTotal().compareTo(bd("12.50")));
    assertEquals(1, snap.pendingCount());
    assertEquals(REM_ID, snap.remainderLineId());
  }

  /** Null cramount/dramount are read as zero, and a null entry in the list is skipped. */
  @Test
  public void testSummarizeGroupNullAmountsAndNullEntry() {
    FIN_BankStatementLine head = row(HEAD_ID, "12.50", null, true);
    FIN_BankStatementLine blank = row(REM_ID, null, null, false);

    ReconciliationDifferenceSupport.GroupSnapshot snap = ReconciliationDifferenceSupport
        .summarizeGroup(Arrays.asList(head, null, blank), REM_ID);

    assertEquals(0, snap.groupTotal().compareTo(bd("12.50")));
    assertEquals(0, snap.remainder().signum());
    assertEquals(1, snap.pendingCount());
  }

  /** A null sibling list degrades to zeros rather than throwing. */
  @Test
  public void testSummarizeGroupNullList() {
    ReconciliationDifferenceSupport.GroupSnapshot snap =
        ReconciliationDifferenceSupport.summarizeGroup(null, REM_ID);

    assertEquals(0, snap.groupTotal().signum());
    assertEquals(0, snap.remainder().signum());
    assertEquals(0, snap.pendingCount());
    assertNull(snap.remainderLineId());
  }

  /** An explicit request GL item wins over the account default. */
  @Test
  public void testEffectiveGlItemIdPayloadWins() {
    assertEquals(GL_PAYLOAD,
        ReconciliationDifferenceSupport.effectiveGlItemId(GL_PAYLOAD, account));
  }

  /** With no request GL item the account's configured difference concept is used. */
  @Test
  public void testEffectiveGlItemIdFallsBackToAccount() {
    assertEquals(GL_ACCOUNT_DEFAULT,
        ReconciliationDifferenceSupport.effectiveGlItemId(null, account));
  }

  /** A blank request GL item is treated as absent, not as an override. */
  @Test
  public void testEffectiveGlItemIdBlankPayloadFallsBack() {
    assertEquals(GL_ACCOUNT_DEFAULT,
        ReconciliationDifferenceSupport.effectiveGlItemId("   ", account));
  }

  /** Neither source configured → null, which the caller turns into a 400. */
  @Test
  public void testEffectiveGlItemIdBothAbsent() {
    FIN_FinancialAccount bare = mock(FIN_FinancialAccount.class);
    when(bare.getAprmGlitemDiff()).thenReturn(null);
    assertNull(ReconciliationDifferenceSupport.effectiveGlItemId(null, bare));
    assertNull(ReconciliationDifferenceSupport.effectiveGlItemId(null, null));
  }

  /**
   * The spec never carries a "0" amount — {@code createTransactionForRule} reads a zero as "not
   * supplied" and substitutes the WHOLE line amount.
   *
   * @throws Exception if building the spec fails
   */
  @Test
  public void testDifferenceSpecNeverEmitsZeroAmount() throws Exception {
    JSONObject spec = ReconciliationDifferenceSupport
        .differenceSpec(GL_PAYLOAD, bd("0.50"), null);

    assertEquals(GL_PAYLOAD, spec.getString(KEY_GL_ITEM_ID));
    assertEquals("0.50", spec.getString(KEY_AMOUNT));
    assertFalse("0".equals(spec.getString(KEY_AMOUNT)));
    assertFalse(spec.has("description"));
  }

  /**
   * A negative remainder is emitted signed, so the adjustment becomes a withdrawal (BPW).
   *
   * @throws Exception if building the spec fails
   */
  @Test
  public void testDifferenceSpecKeepsNegativeSign() throws Exception {
    JSONObject spec = ReconciliationDifferenceSupport
        .differenceSpec(GL_PAYLOAD, bd("-0.50"), null);

    assertEquals("-0.50", spec.getString(KEY_AMOUNT));
  }

  /**
   * A description is carried only when it has content.
   *
   * @throws Exception if building the spec fails
   */
  @Test
  public void testDifferenceSpecCarriesDescriptionOnlyWhenPresent() throws Exception {
    assertEquals("Bank fee", ReconciliationDifferenceSupport
        .differenceSpec(GL_PAYLOAD, bd("0.50"), "Bank fee").getString("description"));
    assertFalse(ReconciliationDifferenceSupport
        .differenceSpec(GL_PAYLOAD, bd("0.50"), "   ").has("description"));
  }

  /**
   * The synthesized reconcile body is a strict 1:1 match: exactly one operation and none of the
   * invoice / write-off keys that would change what reconcileGroup does.
   *
   * @throws Exception if building the body fails
   */
  @Test
  public void testReconcileGroupBodyIsSingleOperation() throws Exception {
    JSONObject built = ReconciliationDifferenceSupport
        .reconcileGroupBody(ACC_ID, REM_ID, TRX_ID);

    assertEquals(ACC_ID, built.getString("financialAccountId"));
    assertEquals(REM_ID, built.getString("statementLineId"));
    JSONArray ops = built.getJSONArray(KEY_OPERATION_IDS);
    assertEquals(1, ops.length());
    assertEquals(TRX_ID, ops.getString(0));
    assertFalse(built.has("invoices"));
    assertFalse(built.has("writeoffDifference"));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Orchestration — reconcileDifference
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * A missing accountId / lineId is rejected before the row lock is even attempted.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testMissingIdentifiersReturns400() throws Exception {
    NeoResponse response = runAction(body(ACC_ID, null));

    assertEquals(400, response.getHttpStatus());
    assertNoWrite();
    verify(handler, never()).loadAccount(anyString());
  }

  /**
   * The row lock is taken FIRST: when it cannot be acquired the request is a 409 and nothing —
   * not even the account read — happens.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testLockFailureReturns409() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response = runAction(body(ACC_ID, REM_ID),
        dal -> when(connection.prepareStatement(anyString()))
            .thenThrow(new SQLException("could not obtain lock")));

    assertEquals(409, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("Another reconciliation is already in progress"));
    assertNoWrite();
    verify(handler, never()).loadAccount(anyString());
  }

  /**
   * An unknown financial account is a 400 with no write.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testUnknownAccountReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount(ACC_ID);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertNoWrite();
  }

  /**
   * An unknown statement line is a 404 with no write.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testUnknownLineReturns404() throws Exception {
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadLine(REM_ID);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(404, response.getHttpStatus());
    assertNoWrite();
  }

  /**
   * A line belonging to another account is a 400 with no write.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testLineOfAnotherAccountReturns400() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);
    doReturn(account).when(handler).loadAccount(OTHER_ACC);

    NeoResponse response = runAction(body(OTHER_ACC, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertEquals(ReconciliationHandler.MSG_LINE_NOT_IN_ACCOUNT, errorMessage(response));
    assertNoWrite();
  }

  /**
   * AC 1 — a remainder above the configured tolerance is refused, and BOTH the adjustment and the
   * reconciliation are skipped (a returned error commits, so any write before it would persist).
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testRemainderAboveToleranceReturns400AndWritesNothing() throws Exception {
    // 13.50 total at 5 % → limit 0.68, remainder 1.50.
    stubPartialGroup("12.00", null, "1.50", null);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    String message = errorMessage(response);
    assertTrue(message.contains("exceeds the tolerance"));
    assertTrue("the message must spell out the configured percentage", message.contains("5%"));
    assertTrue("the message must spell out the resulting limit", message.contains("0.68"));
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).reconcileGroup(any());
  }

  /**
   * AC 2 — no accounting concept in the payload and none configured on the account: 400 naming the
   * accounting concept, nothing written.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testNoGlItemAnywhereReturns400() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);
    when(account.getAprmGlitemDiff()).thenReturn(null);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("accounting concept"));
    assertNoWrite();
  }

  /**
   * AC 3 — a payload glItemId that does not resolve to a GL item is a 400, even though the account
   * has a usable default (a bad explicit id must never silently fall back).
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testUnresolvedPayloadGlItemReturns400() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response = runAction(body(ACC_ID, REM_ID).put(KEY_GL_ITEM_ID, GL_PAYLOAD),
        dal -> when(dal.get(GLItem.class, GL_PAYLOAD)).thenReturn(null));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("GL item not found"));
    assertNoWrite();
  }

  /**
   * AC 4 — an outflow remainder reaches {@code createTransactionForRule} SIGNED, so the adjustment
   * is booked as a withdrawal rather than a deposit.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testNegativeRemainderPostsSignedAmount() throws Exception {
    stubPartialGroup(null, "12.00", null, "0.50");

    NeoResponse response = runActionReachingTheWrite(body(ACC_ID, REM_ID));

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(eq(account), any(),
        argThat(spec -> "-0.50".equals(spec.optString(KEY_AMOUNT))));
  }

  /**
   * AC 5 — a line that already carries a transaction (typically the merged group HEAD) is a 409
   * that echoes the remainder id back, and nothing is written.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testAlreadyReconciledLineReturns409WithRemainderId() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response = runAction(body(ACC_ID, HEAD_ID));

    assertEquals(409, response.getHttpStatus());
    assertEquals(ReconciliationHandler.MSG_LINE_ALREADY_RECONCILED, errorMessage(response));
    assertEquals(REM_ID, response.getBody().getString("remainderLineId"));
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    verify(handler, never()).reconcileGroup(any());
  }

  /**
   * AC 6 — a client-sent amount is IGNORED. With the tolerance percentage at 0 the action is inert,
   * so an "amount": "999" in the body cannot buy its way past the gate.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testClientSentAmountIsIgnored() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), BigDecimal.ZERO})
        .when(handler).loadTolerances(any());

    NeoResponse response = runAction(body(ACC_ID, REM_ID).put(KEY_AMOUNT, "999"));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("exceeds the tolerance"));
    // The server never echoes the client figure: the message quotes the recomputed 0.50.
    assertTrue(errorMessage(response).contains("0.50"));
    assertFalse(errorMessage(response).contains("999"));
    assertNoWrite();
  }

  /**
   * AC 7 — a line with no match group was never split, so there is no remainder to close: 400.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testLineWithoutMatchGroupReturns400() throws Exception {
    FIN_BankStatementLine lonely = row(REM_ID, "12.50", null, false);
    when(lonely.get(MATCH_GROUP_PROPERTY)).thenReturn(null);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(lonely).when(handler).loadLine(REM_ID);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("nothing reconciled against it yet"));
    assertNoWrite();
    verify(handler, never()).loadMatchGroupLines(any(), any());
  }

  /**
   * AC 8 — a reactivated group with two pending portions is ambiguous: 409, nothing written.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testTwoPendingRowsReturns409() throws Exception {
    FIN_BankStatementLine head = row(HEAD_ID, "12.00", null, true);
    FIN_BankStatementLine first = row(REM_ID, "0.30", null, false);
    FIN_BankStatementLine second = row(REM_ID_2, "0.20", null, false);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(first).when(handler).loadLine(REM_ID);
    doReturn(Arrays.asList(head, first, second))
        .when(handler).loadMatchGroupLines(statement, GROUP_ID);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(409, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("more than one pending portion"));
    assertNoWrite();
  }

  /**
   * AC 9 — a group whose only row is the target has nothing reconciled yet, so the tolerance
   * denominator would collapse onto the numerator: 400, nothing written.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testNothingReconciledYetReturns400() throws Exception {
    FIN_BankStatementLine only = row(REM_ID, "12.50", null, false);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(only).when(handler).loadLine(REM_ID);
    doReturn(Collections.singletonList(only))
        .when(handler).loadMatchGroupLines(statement, GROUP_ID);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("nothing reconciled against it yet"));
    assertNoWrite();
  }

  /**
   * AC 10 — a negligible remainder is refused rather than posted as a zero (which
   * {@code createTransactionForRule} would expand into the WHOLE line amount).
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testNegligibleRemainderReturns400() throws Exception {
    stubPartialGroup("12.50", null, "0.004", null);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("no pending difference"));
    assertNoWrite();
  }

  /**
   * AC 11 — happy path: the body handed to {@code reconcileGroup} targets the REMAINDER row and
   * lists exactly the one adjustment transaction just created.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testHappyPathReconcilesRemainderWithTheNewTransaction() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response = runAction(body(ACC_ID, REM_ID).put("description", "Bank fee"));

    assertEquals(201, response.getHttpStatus());

    ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
    verify(handler).reconcileGroup(captor.capture());
    JSONObject sent = captor.getValue();
    assertEquals(ACC_ID, sent.getString("financialAccountId"));
    assertEquals(REM_ID, sent.getString("statementLineId"));
    JSONArray ops = sent.getJSONArray(KEY_OPERATION_IDS);
    assertEquals(1, ops.length());
    assertEquals(TRX_ID, ops.getString(0));

    // The spec carries the recomputed remainder, the account's concept and the given description.
    verify(handler).createTransactionForRule(eq(account), any(), argThat(spec ->
        "0.50".equals(spec.optString(KEY_AMOUNT))
            && GL_ACCOUNT_DEFAULT.equals(spec.optString(KEY_GL_ITEM_ID))
            && "Bank fee".equals(spec.optString("description"))));

    // The envelope is enriched with the adjustment's own ids.
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals(TRX_ID, data.getString("transactionId"));
    assertEquals("0.50", data.getString("differenceAmount"));
    assertEquals(GL_ACCOUNT_DEFAULT, data.getString(KEY_GL_ITEM_ID));
    verify(handler, never()).doRollbackAndClose();
  }

  /**
   * A resolvable payload GL item overrides the account default on the created adjustment.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testPayloadGlItemOverridesAccountDefault() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response = runActionReachingTheWrite(
        body(ACC_ID, REM_ID).put(KEY_GL_ITEM_ID, GL_PAYLOAD),
        dal -> when(dal.get(GLItem.class, GL_PAYLOAD)).thenReturn(mock(GLItem.class)));

    assertEquals(201, response.getHttpStatus());
    verify(handler).createTransactionForRule(eq(account), any(),
        argThat(spec -> GL_PAYLOAD.equals(spec.optString(KEY_GL_ITEM_ID))));
  }

  /**
   * AC 12 — a delegated failure rolls back, so the adjustment transaction cannot survive the
   * rejected request, and the delegated status propagates verbatim.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testDelegatedErrorRollsBackAndPropagates() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);
    doReturn(NeoResponse.error(400, "amounts do not match"))
        .when(handler).reconcileGroup(any());

    NeoResponse response = runActionReachingTheWrite(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertEquals("amounts do not match", errorMessage(response));
    verify(handler).doRollbackAndClose();
  }

  /**
   * A null delegated response is treated as a failure too: rollback plus a 500.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testNullDelegatedResponseRollsBackAndReturns500() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);
    doReturn(null).when(handler).reconcileGroup(any());

    NeoResponse response = runActionReachingTheWrite(body(ACC_ID, REM_ID));

    assertEquals(500, response.getHttpStatus());
    verify(handler).doRollbackAndClose();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Routing
  // ═══════════════════════════════════════════════════════════════════════════

  private NeoContext postContext(JSONObject requestBody) {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "reconcileDifference");
    when(context.getQueryParams()).thenReturn(qp);
    when(context.getRequestBody()).thenReturn(requestBody);
    return context;
  }

  /** AC 13 — a POST reconcileDifference with no body returns a 400 (body required). */
  @Test
  public void testHandleReconcileDifferenceNoBodyReturns400() throws Exception {
    NeoResponse response = handler.handle(postContext(null));

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(ReconciliationHandler.MSG_BODY_REQUIRED, errorMessage(response));
    assertNoWrite();
  }

  /** AC 13 — reconcileDifference is POST-only: a GET falls through to the generic CRUD (null). */
  @Test
  public void testHandleReconcileDifferenceOnGetFallsThrough() {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("GET");
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "reconcileDifference");
    when(context.getQueryParams()).thenReturn(qp);

    assertNull(handler.handle(context));
  }

  /**
   * The route is wired end to end: a POST with a body reaches the support class (here failing on
   * its first validation, which proves dispatch happened rather than falling through to null).
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testHandleReconcileDifferenceRoutesToTheSupportClass() throws Exception {
    NeoContext context = postContext(new JSONObject());

    NeoResponse response;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obContext.when(() -> OBContext.setAdminMode(true)).thenAnswer(inv -> null);
      response = handler.handle(context);
    }

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals("financialAccountId and statementLineId are required", errorMessage(response));
    assertNoWrite();
  }

  /**
   * A tagged line whose group query comes back EMPTY (typically {@code EM_ETGO_Match_Group_ID} not
   * being in the model) yields pendingCount 0. That is "the match group is unidentifiable", NOT
   * "reactivated with several pending portions" — so it must answer 400 {@code MSG_NOT_PARTIAL},
   * never the 409 the {@code pendingCount != 1} form of this guard used to produce.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testEmptyMatchGroupReturns400NotPartial() throws Exception {
    FIN_BankStatementLine target = row(REM_ID, "12.50", null, false);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(target).when(handler).loadLine(REM_ID);
    List<FIN_BankStatementLine> empty = Collections.emptyList();
    doReturn(empty).when(handler).loadMatchGroupLines(statement, GROUP_ID);

    NeoResponse response = runAction(body(ACC_ID, REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("nothing reconciled against it yet"));
    // Guards against the old 409 wording coming back for this case.
    assertFalse(errorMessage(response).contains("more than one pending portion"));
    assertNoWrite();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 — applyInlineDifference: posting a within-tolerance gap during the
  // ordinary "Conciliar", with no extra step for the user.
  //
  // Called from BOTH reconcileGroup and prepareGroup, right after validateOperations and before
  // the line is matched. It returns null to mean "carry on" and a NeoResponse to mean "stop and
  // return this verbatim".
  //
  //   gap = signedLineAmount(line) − Σ signedAmount(operations)
  //
  //   |gap| < 0.005              → null, nothing posted  (the date-only deviation case)
  //   gap sign opposite the line → null, over-coverage stays validateOperations' business
  //   tolerance null / |gap| >   → null, the existing partial-split behaviour is untouched
  //
  // A null tolerance (0%) disables POSTING only. Detection is governed by the date tolerance too
  // and lives in NearMatchSupport.findNearMatch; a date-only difference reaches this helper with a
  // zero gap and is waved through without any concept at all.
  //   within tolerance, no concept → 400 GL_ITEM_REQUIRED, NO write
  //   within tolerance, concept   → one transaction, its id appended to operationIds, null
  // ═══════════════════════════════════════════════════════════════════════════

  private static final String LINE_ID = "line-inline";
  private static final String OP_ID = "op-1";
  private static final String CODE_GL_ITEM_REQUIRED = "GL_ITEM_REQUIRED";
  private static final String KEY_CODE = "code";
  private static final String KEY_DIFFERENCE_AMOUNT = "differenceAmount";

  /** A plain unreconciled statement line of this account (no match group involved). */
  private FIN_BankStatementLine inlineLine(String credit, String debit) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(LINE_ID);
    when(line.getBankStatement()).thenReturn(statement);
    when(line.getCramount()).thenReturn(bd(credit));
    when(line.getDramount()).thenReturn(bd(debit));
    when(line.isActive()).thenReturn(true);
    when(line.getFinancialAccountTransaction()).thenReturn(null);
    return line;
  }

  /** A processed, unreconciled transaction of this account. */
  private FIN_FinaccTransaction opTxn(String id, String deposit, String payment) {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn(id);
    when(trx.getAccount()).thenReturn(account);
    when(trx.getDepositAmount()).thenReturn(bd(deposit));
    when(trx.getPaymentAmount()).thenReturn(bd(payment));
    when(trx.getReconciliation()).thenReturn(null);
    return trx;
  }

  /** Strips the account's configured difference concept, i.e. "no GL item anywhere". */
  private void withoutConfiguredGlItem() {
    when(account.getAprmGlitemDiff()).thenReturn(null);
  }

  /** The single JSONObject handed to {@code createTransactionForRule}, captured. */
  private JSONObject capturedSpec() throws Exception {
    ArgumentCaptor<JSONObject> spec = ArgumentCaptor.forClass(JSONObject.class);
    verify(handler).createTransactionForRule(eq(account), any(), spec.capture());
    return spec.getValue();
  }

  /**
   * The reference case: a 27.00 line reconciled against a 26.62 movement leaves a 0.38 gap, inside
   * the account's 5% tolerance (1.35). One transaction is created for exactly that gap, against the
   * account's configured concept, and its id joins {@code operationIds} so the caller matches BOTH
   * movements into the line — which is what leaves the line RECONCILED instead of split and stuck.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceWithinTolerancePostsTheGap() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());
    List<String> operationIds = new ArrayList<>(Collections.singletonList(OP_ID));

    // The spec now carries a resolved description (see defaultDifferenceDescription below), so
    // every test that reaches the write has to stub the AD_Message lookup it goes through.
    NeoResponse response;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);
      response = ReconciliationDifferenceSupport.applyInlineDifference(
          handler, account, line, operationIds, new JSONObject(), false);
    }

    assertNull("a posted difference lets the caller proceed", response);
    JSONObject spec = capturedSpec();
    assertEquals(GL_ACCOUNT_DEFAULT, spec.getString(KEY_GL_ITEM_ID));
    assertEquals(0, new BigDecimal("0.38").compareTo(bd(spec.getString(KEY_AMOUNT))));
    assertEquals(Arrays.asList(OP_ID, TRX_ID), operationIds);
  }

  /**
   * <b>The sign test — an automatic accounting entry posted backwards is the worst outcome this
   * feature can produce.</b> The gap keeps the LINE's own sign, because that is what
   * {@code createTransactionForRule} reads to derive Cobro (BPD, deposit) vs Pago (BPW, payment).
   * An inflow line short by 0.38 must post a POSITIVE 0.38.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceInflowGapKeepsPositiveSign() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());

    // Reaching the write means reaching the dictionary — see defaultDifferenceDescription below.
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);
      assertNull(ReconciliationDifferenceSupport.applyInlineDifference(handler, account, line,
          new ArrayList<>(Collections.singletonList(OP_ID)), new JSONObject(), false));
    }

    BigDecimal posted = bd(capturedSpec().getString(KEY_AMOUNT));
    assertEquals("an inflow shortfall must post a DEPOSIT (positive)", 1, posted.signum());
    assertEquals(0, new BigDecimal("0.38").compareTo(posted));
  }

  /**
   * The outflow twin of the test above: a −27.00 payment line settled by a −26.62 movement is short
   * by −0.38 and must post a NEGATIVE amount, so the derived transaction is a Pago (BPW). Reading
   * the abs() value here would silently invert the accounting entry.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceOutflowGapKeepsNegativeSign() throws Exception {
    FIN_BankStatementLine line = inlineLine("0.00", "27.00");
    doReturn(opTxn(OP_ID, "0.00", "26.62")).when(handler).loadTransaction(OP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());

    // Reaching the write means reaching the dictionary — see defaultDifferenceDescription below.
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);
      assertNull(ReconciliationDifferenceSupport.applyInlineDifference(handler, account, line,
          new ArrayList<>(Collections.singletonList(OP_ID)), new JSONObject(), false));
    }

    BigDecimal posted = bd(capturedSpec().getString(KEY_AMOUNT));
    assertEquals("an outflow shortfall must post a WITHDRAWAL (negative)", -1, posted.signum());
    assertEquals(0, new BigDecimal("-0.38").compareTo(posted));
  }

  /**
   * A gap within tolerance with NO concept available — neither in the body nor on the account — is
   * a 400 carrying the machine-readable {@code GL_ITEM_REQUIRED} code and the amount, so the client
   * can open its concept picker and retry. Nothing is written: a returned error COMMITS, so the
   * check has to come before the write, not after it.
   *
   * <p>The body shape follows the {@code alreadyReconciled} precedent in the same class: the error
   * text stays nested under {@code error.message} and the code/amount ride alongside at the top
   * level.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceWithoutGlItemReturns400AndWritesNothing() throws Exception {
    withoutConfiguredGlItem();
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    List<String> operationIds = new ArrayList<>(Collections.singletonList(OP_ID));

    NeoResponse response = ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, new JSONObject(), false);

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(CODE_GL_ITEM_REQUIRED, response.getBody().getString(KEY_CODE));
    assertEquals(0, new BigDecimal("0.38")
        .compareTo(bd(response.getBody().getString(KEY_DIFFERENCE_AMOUNT))));
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    assertEquals("operationIds must be untouched when nothing was posted",
        Collections.singletonList(OP_ID), operationIds);
  }

  /**
   * A concept supplied in the body wins over the account's own — the same
   * {@code effectiveGlItemId} precedence the standalone difference action already uses. This is the
   * retry the client performs after a {@code GL_ITEM_REQUIRED}.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceUsesTheBodyGlItemOverTheAccountDefault() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());

    // OBDal is mocked (with the requested concept resolving) so this test passes whether or not
    // the implementation adds the same client-supplied-id existence check `checkGlItem` performs.
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, GL_PAYLOAD)).thenReturn(mock(GLItem.class));
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);

      assertNull(ReconciliationDifferenceSupport.applyInlineDifference(handler, account, line,
          new ArrayList<>(Collections.singletonList(OP_ID)),
          new JSONObject().put(KEY_GL_ITEM_ID, GL_PAYLOAD), false));
    }

    assertEquals(GL_PAYLOAD, capturedSpec().getString(KEY_GL_ITEM_ID));
  }

  /**
   * <b>The date-only deviation.</b> The amounts balance exactly, so the gap is zero: the line
   * reconciles the ordinary way, no transaction is created and the account needs NO configured
   * concept. Only an AMOUNT deviation is ever posted; a date deviation affects classification and
   * the automatch proposal, never the accounting.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceNegligibleGapPostsNothing() throws Exception {
    withoutConfiguredGlItem();
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "27.00", "0.00")).when(handler).loadTransaction(OP_ID);
    List<String> operationIds = new ArrayList<>(Collections.singletonList(OP_ID));

    assertNull(ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, new JSONObject(), false));

    verify(handler, never()).createTransactionForRule(any(), any(), any());
    assertEquals(Collections.singletonList(OP_ID), operationIds);
  }

  /**
   * A gap of 7.00 on a 27.00 line is 25.9%, far outside the 5% tolerance (1.35). The existing
   * partial-split behaviour is untouched: no posting, no error — the caller reconciles what it can
   * and Core leaves a pending remainder, exactly as before this ticket.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceOutsideTolerancePostsNothing() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "20.00", "0.00")).when(handler).loadTransaction(OP_ID);
    List<String> operationIds = new ArrayList<>(Collections.singletonList(OP_ID));

    assertNull(ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, new JSONObject(), false));

    verify(handler, never()).createTransactionForRule(any(), any(), any());
    assertEquals(Collections.singletonList(OP_ID), operationIds);
  }

  /**
   * A 0% amount tolerance means the inline POSTING is disabled, not "one cent of slack" — the
   * deliberate divergence between {@code differenceTolerance} and {@code signalGroupTolerance}. A
   * 0.38 gap that would post at 5% posts nothing at 0%.
   *
   * <p>Note what this does and does not say. Posting stays gated on a non-null
   * {@code NearMatchSupport.differenceTolerance}, so 0% still guarantees no unconfigured account
   * ever gets an automatic accounting entry. DETECTION is a separate dimension and is NOT gated the
   * same way — see the date-only case below, plus
   * {@code NearMatchSupportTest#testZeroAmountToleranceStillDetectsADateOnlyDeviation} (the search
   * keeps running at 0%) and
   * {@code AutoMatchSupportTest#testZeroAmountToleranceStillDetectsADateOnlyDeviation} (the line is
   * classified as a difference anyway).
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceZeroToleranceDisablesPosting() throws Exception {
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), BigDecimal.ZERO})
        .when(handler).loadTolerances(any());
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);

    assertNull(ReconciliationDifferenceSupport.applyInlineDifference(handler, account, line,
        new ArrayList<>(Collections.singletonList(OP_ID)), new JSONObject(), false));

    verify(handler, never()).createTransactionForRule(any(), any(), any());
  }

  /**
   * <b>Detection is not posting — the 0% account, reconciled.</b> The canonical §5.2 case reaches
   * this helper: a 100.00 line of the 28th matched against a 100.00 movement of the 26th, on an
   * account at 0% amount tolerance with no difference concept configured at all. The date deviation
   * is what made the line show up as "Con diferencia"; the AMOUNTS balance, so the gap is zero and
   * this helper stays inert — no {@code GL_ITEM_REQUIRED}, no transaction, no accounting entry.
   *
   * <p>That is the whole point of letting {@code NearMatchSupport.findNearMatch} keep searching at
   * 0%: the proposal
   * a date-only deviation produces can always be applied, because there is nothing to post. If this
   * ever returned a 400 instead, every account on the instance (all of them ship with a 3-day date
   * tolerance) would start refusing perfectly fundable reconciliations.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceZeroToleranceStillReconcilesADateOnlyDifference()
      throws Exception {
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), BigDecimal.ZERO})
        .when(handler).loadTolerances(any());
    withoutConfiguredGlItem();
    FIN_BankStatementLine line = inlineLine("100.00", "0.00");
    doReturn(opTxn(OP_ID, "100.00", "0.00")).when(handler).loadTransaction(OP_ID);
    List<String> operationIds = new ArrayList<>(Collections.singletonList(OP_ID));

    NeoResponse response = ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, new JSONObject(), false);

    assertNull("a date-only difference has nothing to post, so nothing may block it", response);
    verify(handler, never()).createTransactionForRule(any(), any(), any());
    assertEquals("operationIds must be untouched when nothing was posted",
        Collections.singletonList(OP_ID), operationIds);
  }

  /**
   * Over-coverage (the movement is BIGGER than the statement line) is explicitly out of scope: the
   * gap runs opposite to the line's sign, so this helper stays inert and the existing
   * {@code validateOperations} rejection — which runs first in both callers — remains the only
   * answer. Posting a "negative difference" here would silently invent money.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceOverCoverageIsOutOfScope() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "27.20", "0.00")).when(handler).loadTransaction(OP_ID);
    List<String> operationIds = new ArrayList<>(Collections.singletonList(OP_ID));

    assertNull(ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, new JSONObject(), false));

    verify(handler, never()).createTransactionForRule(any(), any(), any());
    assertEquals(Collections.singletonList(OP_ID), operationIds);
  }

  /**
   * <b>The invoice path must roll back before it answers 400.</b> {@code payInvoices} runs BEFORE
   * the gap is even computed, so by the time the missing concept is discovered a payment and its
   * transaction are already written — and a returned {@code NeoResponse.error(...)} COMMITS them
   * (see the class javadoc). {@code doRollbackAndClose()} therefore has to be called before
   * returning, or a rejected request leaves a stray payment behind.
   *
   * <p>The caller signals this with {@code rollbackOnReject}: {@code reconcileGroup} passes its own
   * {@code hasInvoices}, so the rollback happens exactly when there IS uncommitted upstream work.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceRollsBackTheInvoicePathBeforeThe400() throws Exception {
    withoutConfiguredGlItem();
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    // The operation is the transaction payInvoices just auto-created for the invoice payment.
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    JSONObject invoiceBody = new JSONObject().put("invoices",
        new JSONArray().put(new JSONObject().put("invoiceId", "INV-1").put("scheduleId", "PS-1")));

    NeoResponse response = ReconciliationDifferenceSupport.applyInlineDifference(handler, account,
        line, new ArrayList<>(Collections.singletonList(OP_ID)), invoiceBody, true);

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(CODE_GL_ITEM_REQUIRED, response.getBody().getString(KEY_CODE));
    verify(handler).doRollbackAndClose();
    verify(handler, never()).createTransactionForRule(any(), any(), any());
  }

  /**
   * The complement of the test above. {@code rollbackOnReject == false} is BOTH the plain
   * operations path (nothing written yet, so a rollback would gratuitously discard unrelated work
   * already flushed in the same DAL transaction) AND — crucially — the automatch batch, where a
   * per-group rejection must never roll back: sibling groups have already been prepared and closing
   * the session would break the rest of the loop.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceDoesNotRollBackWhenTheCallerHasNothingToUndo()
      throws Exception {
    withoutConfiguredGlItem();
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);

    NeoResponse response = ReconciliationDifferenceSupport.applyInlineDifference(handler, account,
        line, new ArrayList<>(Collections.singletonList(OP_ID)), new JSONObject(), false);

    assertEquals(400, response.getHttpStatus());
    verify(handler, never()).doRollbackAndClose();
  }

  /**
   * An empty (or zero-sum) selection is not a "difference" to post. Without this guard the gap would
   * equal the WHOLE statement line, and a tolerance of 100% or more would authorise posting an
   * entire line to the difference concept — the same reasoning behind {@code reconcileDifference}'s
   * own "nothing reconciled against it yet" rejection.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceIgnoresAnEmptySelection() throws Exception {
    doReturn(new BigDecimal[]{BigDecimal.valueOf(3), new BigDecimal("100")})
        .when(handler).loadTolerances(any());
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    List<String> operationIds = new ArrayList<>();

    assertNull(ReconciliationDifferenceSupport.applyInlineDifference(
        handler, account, line, operationIds, new JSONObject(), false));

    verify(handler, never()).createTransactionForRule(any(), any(), any());
    assertTrue(operationIds.isEmpty());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ETP-4965 — defaultDifferenceDescription: the auto-created movement must be
  // recognisable in the Movements list.
  //
  // createTransactionForRule falls back to the STATEMENT LINE's own description when the spec
  // carries none, and an imported line very often has none — which is how the difference movement
  // landed in the list as a bare amount with description = '' (confirmed in the database).
  //
  //   requested non-blank              → returned verbatim (the manual ETP-4796 flow always wins)
  //   dictionary message installed     → the translated text, in the user's language
  //   messageBD echoes the key back    → the GL item's own name. This is the LIVE behaviour today:
  //                                      ETGO_ReconciliationDifference is not installed yet, and
  //                                      the raw key must never reach the user.
  //   messageBD blank                  → the GL item's own name
  //   no usable text and no GL item    → null, and differenceSpec then omits the key entirely
  //
  // BOTH callers must go through it: applyInlineDifference (the ordinary "Conciliar") AND
  // reconcileDifference (the standalone ETP-4796 banner action, whose modal leaves the description
  // field optional). Wiring it into one of the two leaves the defect alive on the other.
  // ═══════════════════════════════════════════════════════════════════════════

  /** The AD_Message search key the description is resolved from. */
  private static final String DIFFERENCE_MESSAGE_KEY = "ETGO_ReconciliationDifference";
  private static final String DICTIONARY_TEXT = "Reconciliation difference";
  private static final String GL_ITEM_NAME = "Reconciliation differences";
  private static final String MANUAL_DESCRIPTION = "Bank fee agreed with the customer";
  private static final String KEY_DESCRIPTION = "description";
  private static final String UNKNOWN_GL_ITEM = "GL-DELETED";

  /** Stubs the AD_Message dictionary lookup for the difference-description key. */
  private void stubDifferenceMessage(MockedStatic<OBMessageUtils> msgMock, String text) {
    msgMock.when(() -> OBMessageUtils.messageBD(DIFFERENCE_MESSAGE_KEY)).thenReturn(text);
  }

  /** Points the mocked {@link OBDal} at a GL item named {@code name} for {@code id}. */
  private void stubGlItemNamed(MockedStatic<OBDal> obDal, String id, String name) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    stubGlItemNamed(dal, id, name);
  }

  /**
   * The same, against an {@link OBDal} instance mock that is already bound — which is what
   * {@link #runAction(JSONObject, DalSetup)} hands to its {@link DalSetup}, since that helper owns
   * the static mock itself.
   */
  private void stubGlItemNamed(OBDal dal, String id, String name) {
    GLItem item = mock(GLItem.class);
    when(item.getName()).thenReturn(name);
    when(dal.get(GLItem.class, id)).thenReturn(item);
  }

  /**
   * A description the caller asked for wins outright — the manual difference flow lets the user
   * type one and it must survive untouched. The dictionary is not even consulted, so a future
   * translated default can never overwrite what the user wrote.
   */
  @Test
  public void testDefaultDifferenceDescriptionKeepsTheRequestedText() {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      // Wired so that a regression to "the default always wins" is observable here rather than
      // silently yielding the same string.
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);

      assertEquals(MANUAL_DESCRIPTION, ReconciliationDifferenceSupport
          .defaultDifferenceDescription(MANUAL_DESCRIPTION, GL_ACCOUNT_DEFAULT));

      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
      obDal.verify(OBDal::getInstance, never());
    }
  }

  /**
   * With nothing requested — null, empty, or whitespace, since a trimmed-to-nothing string is not a
   * description — the installed dictionary message is used, so the text arrives in the user's
   * language instead of a hardcoded English constant. The GL item is not read at all.
   */
  @Test
  public void testDefaultDifferenceDescriptionUsesTheInstalledDictionaryText() {
    for (String requested : new String[] { null, "", "   " }) {
      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        stubDifferenceMessage(msgMock, DICTIONARY_TEXT);

        assertEquals("a blank requested description must fall through to the dictionary",
            DICTIONARY_TEXT, ReconciliationDifferenceSupport
                .defaultDifferenceDescription(requested, GL_ACCOUNT_DEFAULT));

        obDal.verify(OBDal::getInstance, never());
      }
    }
  }

  /**
   * <b>The branch that keeps a raw dictionary key out of the user's Movements list.</b> The real
   * {@code messageBD} echoes the search key back when the message is not installed — which is
   * precisely the state of the instance today, since {@code ETGO_ReconciliationDifference} has not
   * been added to the dictionary yet. Returning that echo would put the literal string
   * {@code ETGO_ReconciliationDifference} on an accounting movement, so the value has to degrade to
   * the accounting concept's own name, which is meaningful and already localized.
   */
  @Test
  public void testDefaultDifferenceDescriptionNeverReturnsTheEchoedMessageKey() {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubDifferenceMessage(msgMock, DIFFERENCE_MESSAGE_KEY);
      stubGlItemNamed(obDal, GL_ACCOUNT_DEFAULT, GL_ITEM_NAME);

      String description = ReconciliationDifferenceSupport
          .defaultDifferenceDescription(null, GL_ACCOUNT_DEFAULT);

      assertEquals(GL_ITEM_NAME, description);
      assertNotEquals("a raw message key must never reach a user-visible description",
          DIFFERENCE_MESSAGE_KEY, description);
    }
  }

  /**
   * The other way the dictionary can come back unusable: a message that exists but is empty, and
   * {@code messageBD}'s own {@code ""} default. Both are treated exactly like the echoed key.
   */
  @Test
  public void testDefaultDifferenceDescriptionFallsBackToTheGlItemWhenTheMessageIsBlank() {
    for (String dictionaryText : new String[] { null, "", "   " }) {
      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        stubDifferenceMessage(msgMock, dictionaryText);
        stubGlItemNamed(obDal, GL_ACCOUNT_DEFAULT, GL_ITEM_NAME);

        assertEquals("a blank dictionary answer must degrade to the concept name",
            GL_ITEM_NAME, ReconciliationDifferenceSupport
                .defaultDifferenceDescription(null, GL_ACCOUNT_DEFAULT));
      }
    }
  }

  /**
   * No usable dictionary text AND no concept id: there is nothing to describe the movement with, so
   * the helper answers {@code null} and {@code differenceSpec} omits the key rather than emitting a
   * blank one. The DAL is never touched — looking up a blank id would be a pointless query and, in
   * the reconcileDifference path, is reached before the GL-item guard has run.
   */
  @Test
  public void testDefaultDifferenceDescriptionIsNullWithoutADictionaryTextOrAGlItem() {
    for (String glItemId : new String[] { null, "", "   " }) {
      try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        stubDifferenceMessage(msgMock, DIFFERENCE_MESSAGE_KEY);

        assertNull(ReconciliationDifferenceSupport.defaultDifferenceDescription(null, glItemId));

        obDal.verify(OBDal::getInstance, never());
      }
    }
  }

  /** A concept id that resolves to nothing degrades to {@code null} instead of throwing. */
  @Test
  public void testDefaultDifferenceDescriptionIsNullWhenTheGlItemDoesNotResolve() {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubDifferenceMessage(msgMock, DIFFERENCE_MESSAGE_KEY);
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(GLItem.class, UNKNOWN_GL_ITEM)).thenReturn(null);

      assertNull(ReconciliationDifferenceSupport
          .defaultDifferenceDescription(null, UNKNOWN_GL_ITEM));
    }
  }

  /**
   * <b>The regression test for the reported defect.</b> The reference scenario, end to end through
   * {@code applyInlineDifference}: an IMPORTED statement line with no description of its own, and a
   * body that carries no description either (the ordinary "Conciliar", where the user is never
   * asked for one), plus the account's configured concept. The spec handed to
   * {@code createTransactionForRule} must still carry a non-blank description, because that
   * builder's only other source is the line's own — the empty one — and the movement then shows up
   * in the Movements list as a bare amount, indistinguishable from a real operation.
   *
   * <p>The dictionary is stubbed to echo the key back, i.e. the instance's ACTUAL state today, so
   * this asserts the behaviour that ships rather than the one that will ship once the message is
   * installed.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceAlwaysDescribesTheMovementItCreates() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    // The premise of the defect: the imported line has no text of its own to inherit.
    when(line.getDescription()).thenReturn(null);
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());

    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubDifferenceMessage(msgMock, DIFFERENCE_MESSAGE_KEY);
      stubGlItemNamed(obDal, GL_ACCOUNT_DEFAULT, GL_ITEM_NAME);

      assertNull(ReconciliationDifferenceSupport.applyInlineDifference(handler, account, line,
          new ArrayList<>(Collections.singletonList(OP_ID)), new JSONObject(), false));
    }

    // optString yields "" when the key is absent, which is exactly the shipped defect: no
    // description key at all in the spec.
    String description = capturedSpec().optString(KEY_DESCRIPTION);
    assertNotNull(description);
    assertFalse("the auto-created difference movement must never reach the Movements list "
        + "without a description", description.trim().isEmpty());
    assertEquals(GL_ITEM_NAME, description);
    assertNotEquals(DIFFERENCE_MESSAGE_KEY, description);
  }

  /**
   * The complement of the test above, through the same entry point: a description supplied in the
   * request body is what the movement is created with. Neither the dictionary nor the concept name
   * may override it.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testApplyInlineDifferenceKeepsADescriptionSuppliedInTheBody() throws Exception {
    FIN_BankStatementLine line = inlineLine("27.00", "0.00");
    doReturn(opTxn(OP_ID, "26.62", "0.00")).when(handler).loadTransaction(OP_ID);
    doReturn(TRX_ID).when(handler).createTransactionForRule(any(), any(), any());

    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);

      assertNull(ReconciliationDifferenceSupport.applyInlineDifference(handler, account, line,
          new ArrayList<>(Collections.singletonList(OP_ID)),
          new JSONObject().put(KEY_DESCRIPTION, MANUAL_DESCRIPTION), false));

      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }

    assertEquals(MANUAL_DESCRIPTION, capturedSpec().getString(KEY_DESCRIPTION));
  }

  /**
   * <b>The same defect, through the OTHER entry point.</b> {@code reconcileDifference} is the
   * standalone manual action behind the ETP-4796 difference banner, and its modal's description
   * field is OPTIONAL. Leaving it blank used to hand {@code createTransactionForRule} a spec with
   * no description at all — the builder then fell back to the statement line's own, which an
   * imported line does not have, producing exactly the nameless movement
   * {@code applyInlineDifference} was fixed for. Wiring the default into only one of the two
   * callers fixes the defect on only one of the two flows.
   *
   * <p>The dictionary is stubbed to echo the key back, i.e. the instance's ACTUAL state today
   * ({@code ETGO_ReconciliationDifference} is not installed), so the assertion lands on the
   * GL-item-name fallback and also proves the raw key never reaches a user-visible field.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testReconcileDifferenceAlwaysDescribesTheMovementItCreates() throws Exception {
    // A partially reconciled imported line: 12.00 already matched, 0.50 pending, and no description
    // of its own for createTransactionForRule to inherit.
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubDifferenceMessage(msgMock, DIFFERENCE_MESSAGE_KEY);
      // The body deliberately carries NO description — the modal's field is optional.
      response = runAction(body(ACC_ID, REM_ID),
          dal -> stubGlItemNamed(dal, GL_ACCOUNT_DEFAULT, GL_ITEM_NAME));
    }

    assertEquals(201, response.getHttpStatus());

    // optString yields "" when the key is absent, which is exactly the shipped defect: no
    // description key at all in the spec.
    String description = capturedSpec().optString(KEY_DESCRIPTION);
    assertNotNull(description);
    assertFalse("the manual difference action must never create a movement without a description",
        description.trim().isEmpty());
    assertEquals(GL_ITEM_NAME, description);
    assertNotEquals("a raw message key must never reach a user-visible description",
        DIFFERENCE_MESSAGE_KEY, description);
  }

  /**
   * The complement of the test above, through the same entry point: a description typed into the
   * manual modal reaches the movement verbatim, and the dictionary is not consulted at all — so a
   * future translated default can never overwrite what the user wrote.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  public void testReconcileDifferenceKeepsADescriptionSuppliedInTheBody() throws Exception {
    stubPartialGroup("12.00", null, "0.50", null);

    NeoResponse response;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      // Wired so a regression to "the default always wins" is observable here rather than silently
      // yielding an equally plausible string.
      stubDifferenceMessage(msgMock, DICTIONARY_TEXT);

      response = runAction(body(ACC_ID, REM_ID).put(KEY_DESCRIPTION, MANUAL_DESCRIPTION));

      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }

    assertEquals(201, response.getHttpStatus());
    assertEquals(MANUAL_DESCRIPTION, capturedSpec().getString(KEY_DESCRIPTION));
  }
}
