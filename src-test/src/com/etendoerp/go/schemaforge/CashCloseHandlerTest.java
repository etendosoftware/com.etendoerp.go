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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * Mockito-driven unit tests for {@link CashCloseHandler} and the pure parts of
 * {@link CashCloseSupport} (ETP-4795).
 *
 * <p>Strategy mirrors {@link ReconciliationHandlerTest}: spy the handler and stub its
 * package-private DAL / Classic seams ({@code loadAccount}, {@code findDraft},
 * {@code findLastProcessed}, {@code createDraft}, {@code removeDraft}, {@code checkPeriod},
 * {@code doRollbackAndClose}) so the routing and validation paths run without a database.
 *
 * <p>The deeper write paths (linking transactions, posting the difference, pushing forward
 * post-dated movements) go through Core statics that a unit test cannot meaningfully fake; their
 * risky arithmetic and date guards are therefore extracted as pure helpers on
 * {@link CashCloseSupport} and asserted directly here. The frontend mirrors the same arithmetic
 * in {@code cashCloseMath.js}, which has its own test suite.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Routing: unknown method/action → null passthrough; known actions dispatch.</li>
 *   <li>Guards: missing accountId / statementDate → 400; unknown account → 400; a BANK account
 *       rejected → 400 (cash close must never touch a statement-backed reconciliation).</li>
 *   <li>discardDraft: removes an existing draft; no-ops (still 200) when there is none.</li>
 *   <li>Pure arithmetic: difference sign, the half-cent balanced threshold.</li>
 *   <li>Pure date guards: before the last close, past tomorrow, null-safety.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CashCloseHandlerTest {

  private static final String ACC_ID = "acc-cash-1";
  private static final String CLOSE_DATE = "2026-08-05";

  private CashCloseHandler handler;

  @Before
  public void setUp() {
    handler = spy(new CashCloseHandler());
    doNothing().when(handler).doRollbackAndClose();
    // Default: nothing linked to the draft. The seam is queried (never read off the entity's own
    // one-to-many list — see CashCloseHandler#linkedTransactions), so it has to be stubbed for the
    // no-database paths; the tests that care about cleared movements override it.
    doReturn(Collections.emptyList()).when(handler).linkedTransactions(any());
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── Routing ───────────────────────────────────────────────────────────────

  /** An unrecognised action must fall through to the generic spec handling, i.e. return null. */
  @Test
  public void testUnknownActionFallsThrough() {
    assertNull(handler.handle(context("GET", "somethingElse", null)));
    assertNull(handler.handle(context("POST", "somethingElse", new JSONObject())));
  }

  /** The HTTP method is part of the route key: `pending` is GET-only. */
  @Test
  public void testWrongMethodForActionFallsThrough() {
    assertNull(handler.handle(context("POST", "pending", new JSONObject())));
  }

  /** No query params at all (generic CRUD request) must also fall through. */
  @Test
  public void testNoActionFallsThrough() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").queryParams(null).build();
    assertNull(handler.handle(ctx));
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Test
  public void testPendingWithoutAccountIdReturns400() {
    NeoResponse res = handler.handle(context("GET", "pending", null));
    assertNotNull(res);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
  }

  @Test
  public void testPostWithoutBodyReturns400() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .queryParams(Collections.singletonMap("action", "confirm"))
        .requestBody(null)
        .build();
    NeoResponse res = handler.handle(ctx);
    assertNotNull(res);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
  }

  @Test
  public void testSaveDraftWithoutAccountIdReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("statementDate", CLOSE_DATE);

    NeoResponse res = handler.saveDraft(body);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
  }

  @Test
  public void testSaveDraftWithoutStatementDateReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("accountId", ACC_ID);

    NeoResponse res = handler.saveDraft(body);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
  }

  @Test
  public void testUnknownAccountReturns400() throws Exception {
    doReturn(null).when(handler).loadAccount(ACC_ID);

    NeoResponse res = handler.saveDraft(payload(ACC_ID, CLOSE_DATE, "100.00"));

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
  }

  /**
   * The hard type gate: a bank account must never reach the cash-close flow. Core keeps
   * statement-backed and cash-only reconciliations mutually exclusive per document, so letting a
   * bank account through here would let the two mix.
   */
  @Test
  public void testBankAccountIsRejected() throws Exception {
    doReturn(account("B")).when(handler).loadAccount(ACC_ID);

    NeoResponse res = handler.saveDraft(payload(ACC_ID, CLOSE_DATE, "100.00"));

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
    verify(handler, never()).createDraft(any(), any(), any());
  }

  /** A card account is rejected for the same reason as a bank one. */
  @Test
  public void testCardAccountIsRejected() throws Exception {
    doReturn(account("CA")).when(handler).loadAccount(ACC_ID);

    NeoResponse res = handler.confirm(payload(ACC_ID, CLOSE_DATE, "100.00"));

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
    verify(handler, never()).createDraft(any(), any(), any());
  }

  // ── discardDraft ──────────────────────────────────────────────────────────

  @Test
  public void testDiscardDraftRemovesTheExistingDraft() throws Exception {
    FIN_FinancialAccount acc = account("C");
    FIN_Reconciliation draft = mock(FIN_Reconciliation.class);
    doReturn(acc).when(handler).loadAccount(ACC_ID);
    doReturn(draft).when(handler).findDraft(acc);
    doNothing().when(handler).removeDraft(draft);

    JSONObject body = new JSONObject();
    body.put("accountId", ACC_ID);
    NeoResponse res = handler.discardDraft(body);

    assertEquals(HttpServletResponse.SC_OK, res.getHttpStatus());
    verify(handler).removeDraft(draft);
  }

  /** Discarding with nothing to discard is a no-op, not an error. */
  @Test
  public void testDiscardDraftWithNoDraftIsANoOp() throws Exception {
    FIN_FinancialAccount acc = account("C");
    doReturn(acc).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).findDraft(acc);

    JSONObject body = new JSONObject();
    body.put("accountId", ACC_ID);
    NeoResponse res = handler.discardDraft(body);

    assertEquals(HttpServletResponse.SC_OK, res.getHttpStatus());
    verify(handler, never()).removeDraft(any());
  }

  @Test
  public void testDiscardDraftWithoutAccountIdReturns400() throws Exception {
    NeoResponse res = handler.discardDraft(new JSONObject());
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
  }

  // ── Closed-period guard on the already-linked lines ───────────────────────

  /**
   * A movement dated BEFORE the close keeps its own accounting date (only post-dated ones get
   * pushed forward), so it can sit in a period that is already closed even when the close date's
   * own period is open. Confirming must be refused, naming the offending movement — Classic's
   * {@code @APRM_PeriodNotAvailableClearedItem@} case.
   */
  @Test
  public void testConfirmIsBlockedWhenALinkedLineIsInAClosedPeriod() throws Exception {
    FIN_FinancialAccount acc = cashAccountWithOrgAndClient();
    FIN_Reconciliation draft = draftEndingOn(pastDate());
    doReturn(null).when(handler).findLastProcessed(acc);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn("1000381 - Transportes Vega").when(handler).findLineInClosedPeriod(draft);

    NeoResponse res = CashCloseSupport.confirmDraft(handler, acc, draft,
        BigDecimal.ZERO, BigDecimal.ZERO);

    assertEquals(HttpServletResponse.SC_CONFLICT, res.getHttpStatus());
    assertTrue("the message must name the blocking movement",
        res.getBody().getJSONObject("error").getString("message").contains("Transportes Vega"));
  }

  /** With every linked line postable, the guard lets the close through and it completes. */
  @Test
  public void testConfirmPassesThePeriodGuardWhenNoLineIsBlocked() throws Exception {
    FIN_FinancialAccount acc = cashAccountWithOrgAndClient();
    FIN_Reconciliation draft = draftEndingOn(pastDate());
    doReturn(null).when(handler).findLastProcessed(acc);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(null).when(handler).findLineInClosedPeriod(draft);

    // A balanced close (opening 0, no cleared lines, declared 0) posts no difference transaction,
    // so the only Core touch left is the final save of the completed document.
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse res = CashCloseSupport.confirmDraft(handler, acc, draft,
          BigDecimal.ZERO, BigDecimal.ZERO);

      assertEquals(HttpServletResponse.SC_OK, res.getHttpStatus());
      verify(handler).findLineInClosedPeriod(draft);
      verify(draft).setDocumentStatus("CO");
      verify(draft).setProcessed(true);
    }
  }

  /** The close-date period check runs BEFORE the per-line one, and short-circuits it. */
  @Test
  public void testClosedCloseDatePeriodShortCircuitsTheLineCheck() throws Exception {
    FIN_FinancialAccount acc = cashAccountWithOrgAndClient();
    FIN_Reconciliation draft = draftEndingOn(pastDate());
    doReturn(null).when(handler).findLastProcessed(acc);
    doThrow(new OBException("@PeriodNotAvailable@"))
        .when(handler).checkPeriod(any(), any(), any(), any());

    NeoResponse res = CashCloseSupport.confirmDraft(handler, acc, draft,
        BigDecimal.ZERO, BigDecimal.ZERO);

    assertEquals(HttpServletResponse.SC_CONFLICT, res.getHttpStatus());
    verify(handler, never()).findLineInClosedPeriod(any());
  }

  // ── Where the cleared movements are read from ──────────────────────────────

  /**
   * REGRESSION — the first close of a cash account.
   *
   * <p>A draft created earlier in the same request never reports its linked movements through the
   * entity ({@code getFINFinaccTransactionList()} stays empty: the FK lives on the transaction, the
   * owning side), so reading the cleared net off the entity made a perfectly balanced close look
   * like a full-amount discrepancy — rejected for a missing GL Item Difference it did not need, or,
   * with one configured, posted as a spurious adjustment for the whole counted amount. Confirming
   * only worked from the SECOND attempt on, once the draft came back from the database.</p>
   *
   * <p>Here the entity list is empty and the seam reports a 200,00 deposit. Declaring exactly
   * 200,00 against an opening of 0 must therefore balance, and complete without any GL Item
   * Difference on the account.</p>
   */
  @Test
  public void testClearedNetIsReadFromTheLinkedTransactionsNotTheEntityList() throws Exception {
    FIN_FinancialAccount acc = cashAccountWithOrgAndClient();
    Date closeDate = pastDate();
    FIN_Reconciliation draft = draftEndingOn(closeDate);
    doReturn(null).when(handler).findLastProcessed(acc);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(null).when(handler).findLineInClosedPeriod(draft);
    when(draft.getFINFinaccTransactionList()).thenReturn(Collections.emptyList());
    doReturn(Collections.singletonList(deposit("200.00", closeDate)))
        .when(handler).linkedTransactions(draft);
    // No accounting concept for differences — the account in the reported repro had none.
    when(acc.getAprmGlitemDiff()).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse res = CashCloseSupport.confirmDraft(handler, acc, draft,
          BigDecimal.ZERO, new BigDecimal("200.00"));

      assertEquals(HttpServletResponse.SC_OK, res.getHttpStatus());
      verify(draft).setDocumentStatus("CO");
      verify(draft).setProcessed(true);
    }
  }

  /**
   * The same close declared 100,00 short is genuinely unbalanced, so the missing-concept guard must
   * still fire — the fix above must not have turned it into a no-op.
   */
  @Test
  public void testAGenuineDifferenceWithoutAConceptIsStillRejected() throws Exception {
    FIN_FinancialAccount acc = cashAccountWithOrgAndClient();
    Date closeDate = pastDate();
    FIN_Reconciliation draft = draftEndingOn(closeDate);
    doReturn(null).when(handler).findLastProcessed(acc);
    doNothing().when(handler).checkPeriod(any(), any(), any(), any());
    doReturn(null).when(handler).findLineInClosedPeriod(draft);
    doReturn(Collections.singletonList(deposit("200.00", closeDate)))
        .when(handler).linkedTransactions(draft);
    when(acc.getAprmGlitemDiff()).thenReturn(null);

    NeoResponse res = CashCloseSupport.confirmDraft(handler, acc, draft,
        BigDecimal.ZERO, new BigDecimal("100.00"));

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, res.getHttpStatus());
    verify(draft, never()).setDocumentStatus("CO");
  }

  // ── Pure close arithmetic ─────────────────────────────────────────────────

  /** The design-handoff figures: opening 19,00 + net 325,66 vs. a declared 182,61. */
  @Test
  public void testDifferenceMatchesTheDesignFigures() {
    BigDecimal diff = CashCloseSupport.difference(
        new BigDecimal("19.00"), new BigDecimal("325.66"), new BigDecimal("182.61"));
    assertEquals(0, new BigDecimal("-162.05").compareTo(diff));
  }

  /** A surplus is POSITIVE, so the backend posts a deposit (BPD). */
  @Test
  public void testSurplusIsPositive() {
    BigDecimal diff = CashCloseSupport.difference(
        new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("175.00"));
    assertEquals(1, diff.signum());
    assertEquals(0, new BigDecimal("25.00").compareTo(diff));
  }

  /** A shortage is NEGATIVE, so the backend posts a withdrawal (BPW). */
  @Test
  public void testShortageIsNegative() {
    BigDecimal diff = CashCloseSupport.difference(
        new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("120.00"));
    assertEquals(-1, diff.signum());
  }

  @Test
  public void testDifferenceIsNullSafe() {
    assertEquals(0, BigDecimal.ZERO.compareTo(CashCloseSupport.difference(null, null, null)));
  }

  @Test
  public void testBalancedThreshold() {
    assertTrue(CashCloseSupport.isBalanced(BigDecimal.ZERO));
    assertTrue(CashCloseSupport.isBalanced(new BigDecimal("0.004")));
    assertTrue(CashCloseSupport.isBalanced(new BigDecimal("-0.004")));
    // Exactly the tolerance is NOT balanced — the comparison is strictly less-than.
    assertFalse(CashCloseSupport.isBalanced(new BigDecimal("0.005")));
    assertFalse(CashCloseSupport.isBalanced(new BigDecimal("-0.01")));
    assertFalse(CashCloseSupport.isBalanced(new BigDecimal("-162.05")));
  }

  // ── Pure date guards ──────────────────────────────────────────────────────

  @Test
  public void testIsBeforeLastCloseRejectsBackdating() {
    FIN_Reconciliation last = mock(FIN_Reconciliation.class);
    when(last.getEndingDate()).thenReturn(day(2026, Calendar.AUGUST, 5));

    assertTrue(CashCloseSupport.isBeforeLastClose(day(2026, Calendar.AUGUST, 4), last));
    assertFalse(CashCloseSupport.isBeforeLastClose(day(2026, Calendar.AUGUST, 5), last));
    assertFalse(CashCloseSupport.isBeforeLastClose(day(2026, Calendar.AUGUST, 6), last));
  }

  /** With no previous close, any date is acceptable — the first close of a brand-new drawer. */
  @Test
  public void testIsBeforeLastCloseIsFalseWithoutAPreviousClose() {
    assertFalse(CashCloseSupport.isBeforeLastClose(day(2020, Calendar.JANUARY, 1), null));

    FIN_Reconciliation last = mock(FIN_Reconciliation.class);
    when(last.getEndingDate()).thenReturn(null);
    assertFalse(CashCloseSupport.isBeforeLastClose(day(2020, Calendar.JANUARY, 1), last));
  }

  /**
   * The cut-off is tomorrow at midnight, exactly as Classic's {@code @APRM_ReconcileInFutureOrPast@}
   * guard. The real flow always feeds a date-only value ({@code java.sql.Date.valueOf("yyyy-MM-dd")}),
   * so these are midnight dates too.
   */
  @Test
  public void testIsInFutureAllowsTodayAndTomorrowButNotBeyond() {
    assertFalse("today must be allowed", CashCloseSupport.isInFuture(midnightDaysFromNow(0)));
    assertFalse("tomorrow must be allowed (timezone slack)",
        CashCloseSupport.isInFuture(midnightDaysFromNow(1)));
    assertTrue("the day after tomorrow must be rejected",
        CashCloseSupport.isInFuture(midnightDaysFromNow(2)));
    assertFalse("a past date is not 'in the future'",
        CashCloseSupport.isInFuture(midnightDaysFromNow(-30)));
  }

  /**
   * A same-day close is still allowed even when the timestamp carries the current time — the
   * cut-off sits at tomorrow-midnight, comfortably after any moment of today.
   */
  @Test
  public void testIsInFutureAllowsTodayWithATimeComponent() {
    assertFalse(CashCloseSupport.isInFuture(new Date()));
  }

  @Test
  public void testIsInFutureIsNullSafe() {
    assertFalse(CashCloseSupport.isInFuture(null));
  }

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private static NeoContext context(String method, String action, JSONObject body) {
    Map<String, String> qp = new HashMap<>();
    qp.put("action", action);
    return NeoContext.builder()
        .httpMethod(method)
        .queryParams(qp)
        .requestBody(body)
        .build();
  }

  private static FIN_FinancialAccount account(String type) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getId()).thenReturn(ACC_ID);
    when(acc.getType()).thenReturn(type);
    return acc;
  }

  /** A cash account carrying the client/org the period guard reads. */
  private static FIN_FinancialAccount cashAccountWithOrgAndClient() {
    FIN_FinancialAccount acc = account("C");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");
    when(acc.getClient()).thenReturn(client);
    when(acc.getOrganization()).thenReturn(org);
    return acc;
  }

  /**
   * A draft ending on {@code endingDate}. What it has linked is decided by the {@code
   * linkedTransactions} seam (empty by default, see {@link #setUp()}), not by this mock — so by
   * default {@code clearedNet} is 0 and the flow never touches the Core statics behind the
   * difference transaction.
   */
  private static FIN_Reconciliation draftEndingOn(Date endingDate) {
    FIN_Reconciliation draft = mock(FIN_Reconciliation.class);
    when(draft.getId()).thenReturn("rec-1");
    when(draft.getEndingDate()).thenReturn(endingDate);
    when(draft.getDocumentNo()).thenReturn("1000001");
    when(draft.getEndingBalance()).thenReturn(BigDecimal.ZERO);
    // draft.getEntity().getTableId() is evaluated as an argument of the period guard, so it must
    // resolve even when the guard itself is stubbed out.
    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn("table-1");
    when(draft.getEntity()).thenReturn(entity);
    return draft;
  }

  /**
   * A cleared inflow of {@code amount} dated on {@code transactionDate}. The date matters: the
   * confirm flow compares it against the close date to decide whether to push it forward.
   */
  private static FIN_FinaccTransaction deposit(String amount, Date transactionDate) {
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("trx-1");
    when(trx.getDepositAmount()).thenReturn(new BigDecimal(amount));
    when(trx.getPaymentAmount()).thenReturn(BigDecimal.ZERO);
    when(trx.getTransactionDate()).thenReturn(transactionDate);
    return trx;
  }

  /** Yesterday at midnight — safely inside every date guard. */
  private static Date pastDate() {
    return midnightDaysFromNow(-1);
  }

  private static JSONObject payload(String accountId, String statementDate, String declared)
      throws Exception {
    JSONObject body = new JSONObject();
    body.put("accountId", accountId);
    body.put("statementDate", statementDate);
    body.put("declaredBalance", declared);
    body.put("movementIds", new JSONArray());
    return body;
  }

  private static Date day(int year, int month, int dayOfMonth) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month, dayOfMonth);
    return cal.getTime();
  }

  /** Midnight, {@code days} away from today — the shape the real flow always produces. */
  private static Date midnightDaysFromNow(int days) {
    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.DATE, days);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTime();
  }
}
