/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * Unit tests for the failure-reason accumulation added to {@link ReconciliationHandlerSupport}.
 *
 * <p>The removal helpers deliberately swallow a per-unit exception so one failure does not abort
 * the batch (Core's removal utilities commit mid-flow, so aborting would only leave the rest of the
 * batch unprocessed on top of the failure). That behaviour is correct and is NOT what these tests
 * question — {@code ReconciliationHandler} already re-checks each transaction's real post-state and
 * reports it. What was missing is the CAUSE: the swallowed exception only ever reached the server
 * log, so the response could say WHICH transactions failed but never WHY, and the UI fell back to a
 * generic message. These tests pin the accumulator that carries the reason out, and pin that
 * swallowing still does not abort the batch.
 *
 * <p>{@code OBMessageUtils.messageBD} and {@code OBMessageUtils.translateError} are mocked
 * statically because the real ones resolve the text against the AD_Message table through a live
 * {@code DalConnectionProvider}.
 *
 * <p>They also cover {@link ReconciliationHandlerSupport#unpostBeforeUndo}, the open-range
 * accounting reset every removal path now runs first. Un-reconciling a POSTED document failed with
 * {@code @PeriodClosedForUnPosting@} on an environment where every period was open, because
 * {@code com.etendoerp.payment.removal}'s {@code Utilities.unPostReconciliation} resets accounting
 * over the RECONCILIATION's own date while Core dates a reconciliation's {@code Fact_Acct} rows
 * with the TRANSACTION's accounting date. When those differ — a statement line reconciled on a
 * later day, the normal case — the range matches nothing and {@code ResetAccounting} falls into a
 * catch-all {@code throw} that performs no period check at all. {@code ResetAccounting} is mocked
 * statically here for the same reason as {@code OBMessageUtils}: the real one walks the database.
 *
 * <p>The reset is a session hazard as well as an accounting one, and that shapes the tests around
 * it. {@code ResetAccounting.delete} issues native SQL and flushes/clears the Hibernate session, so
 * anything loaded before it is detached afterwards. Running it per-document from INSIDE the removal
 * loop — the arrangement that reached the live environment — detached the instances that loop was
 * holding and produced {@code OBInterceptor: ... is detected as not new but it does not have a
 * current state in the database} followed by a {@code NonUniqueObjectException} on
 * {@code FIN_Finacc_Transaction}. So {@code unpostBeforeUndo} takes an ID, re-reads the record after
 * the reset before flipping {@code Posted}, and runs as its own pass over EVERY affected document
 * before any removal starts. The ordering and re-fetch tests below pin that arrangement; they are
 * what a regression back to mid-loop unposting fails.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReconciliationHandlerSupportTest {

  private static final String REASON_PERIOD_CLOSED =
      "The accounting period is closed and the document cannot be unposted";
  private static final String REASON_OTHER = "Some other Core guard refused the removal";

  /** The Etendo message key Core embeds when the period of the document being unposted is closed. */
  private static final String PERIOD_CLOSED_KEY = "PeriodClosedForUnPosting";

  /**
   * The es_ES AD_Message text for {@link #PERIOD_CLOSED_KEY}, verbatim from the database. Kept in
   * Spanish on purpose: the product is used in Spanish by real clients and the whole point of
   * {@code userFacingReason} is that THIS sentence — and nothing else — reaches the toast. An
   * English fixture could not distinguish the dictionary text from Core's English wrapper prose,
   * which is precisely the defect under test.
   */
  private static final String PERIOD_CLOSED_TRANSLATED =
      "Periodo Cerrado. No se puede descontabilizar un documento en un periodo cerrado";

  /**
   * The untranslated English prose Core wraps each cause in, concatenated with no separator at all
   * — copied verbatim from the live server log.
   */
  private static final String CORE_WRAPPER_PROSE =
      "Error when removing the transaction from reconciliation."
          + "Error when reactivating reconciliation";

  /** The raw exception message Core actually threw, exactly as it reached the handler. */
  private static final String RAW_CORE_CHAIN = CORE_WRAPPER_PROSE + "@" + PERIOD_CLOSED_KEY + "@";

  /**
   * What translating {@link #RAW_CORE_CHAIN} as a WHOLE produces: the placeholder is resolved but
   * the English prose in front of it survives. This is the string the previous implementation put
   * in the toast, and the one every assertion below must reject.
   */
  private static final String WHOLE_STRING_TRANSLATION =
      CORE_WRAPPER_PROSE + PERIOD_CLOSED_TRANSLATED;

  /** Identifiers the open-range reset is scoped by; asserted literally, never through a matcher. */
  private static final String CLIENT_ID = "CLI-1";
  private static final String ORG_ID = "ORG-1";
  private static final String REC_TABLE_ID = "TBL-REC";

  /** {@code FIN_Reconciliation.posted} for a document Core has already posted. */
  private static final String POSTED_YES = "Y";

  /**
   * The OPEN date range {@code unpostBeforeUndo} must pass — both ends empty. Handing the
   * reconciliation's own date here instead is the defect being compensated, so every assertion
   * below compares against THIS constant rather than accepting {@code anyString()}.
   */
  private static final String OPEN_RANGE = "";

  private ReconciliationHandler handler;

  @Before
  public void setUp() {
    handler = spy(new ReconciliationHandler());
  }

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** A transaction mock carrying an id (the id is what every map/set below is keyed by). */
  private FIN_FinaccTransaction txnWithId(String id) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    when(t.getId()).thenReturn(id);
    return t;
  }

  /** A reconciliation grouping exactly the given transactions (each wired back to it). */
  private FIN_Reconciliation recWith(String recId, FIN_FinaccTransaction... txns) {
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn(recId);
    List<FIN_FinaccTransaction> list = new ArrayList<>();
    for (FIN_FinaccTransaction t : txns) {
      when(t.getReconciliation()).thenReturn(rec);
      list.add(t);
    }
    when(rec.getFINFinaccTransactionList()).thenReturn(list);
    return rec;
  }

  /**
   * A reconciliation that also carries what {@code unpostBeforeUndo} reads: the posted flag and the
   * client / organization / table the reset is scoped by.
   *
   * <p>Built on top of {@link #recWith}, so the transactions handed in are wired back to it exactly
   * as in the failure-reason tests.
   */
  private FIN_Reconciliation postedRec(String recId, String posted, FIN_FinaccTransaction... txns) {
    FIN_Reconciliation rec = recWith(recId, txns);
    when(rec.getPosted()).thenReturn(posted);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(rec.getClient()).thenReturn(client);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(rec.getOrganization()).thenReturn(org);
    Entity entity = mock(Entity.class);
    when(entity.getTableId()).thenReturn(REC_TABLE_ID);
    when(rec.getEntity()).thenReturn(entity);
    return rec;
  }

  /**
   * Verifies the ONE reset the fix is about: this document only, over an open date range.
   *
   * <p>The {@code times(1)} pass over the same static mock closes the other half — a regression
   * that kept the open-range reset but ALSO issued a second, date-narrowed one would satisfy the
   * argument-exact verification on its own.
   */
  private void verifyOpenRangeReset(MockedStatic<ResetAccounting> ra, String recId) {
    ra.verify(() -> ResetAccounting.delete(eq(CLIENT_ID), eq(ORG_ID), eq(REC_TABLE_ID), eq(recId),
        eq(OPEN_RANGE), eq(OPEN_RANGE)));
    ra.verify(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
        anyString(), anyString()), times(1));
  }

  /**
   * {@link #verifyOpenRangeReset} for a batch: one open-range reset per id and NOT ONE MORE.
   *
   * <p>The global {@code times(recIds.length)} pass is what makes this an assertion about the
   * unposting PASS rather than about each document in isolation — a regression that unposted a
   * document again from inside the removal loop would satisfy the per-id verifications and fail
   * here.
   */
  private void verifyOpenRangeResetForEach(MockedStatic<ResetAccounting> ra, String... recIds) {
    for (String recId : recIds) {
      ra.verify(() -> ResetAccounting.delete(eq(CLIENT_ID), eq(ORG_ID), eq(REC_TABLE_ID), eq(recId),
          eq(OPEN_RANGE), eq(OPEN_RANGE)));
    }
    ra.verify(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
        anyString(), anyString()), times(recIds.length));
  }

  /**
   * Appends {@code "reset:<recordId>"} to {@code calls} for every accounting reset.
   *
   * <p>The ordering tests need the real interleaving of a STATIC seam ({@code ResetAccounting}) with
   * an INSTANCE seam ({@code handler.undoReconciliation}) or another static one
   * ({@code ReconciliationRemovalUtil}); no single {@code InOrder} can span those, so the sequence is
   * recorded by hand.
   */
  private void recordResets(MockedStatic<ResetAccounting> ra, List<String> calls) {
    ra.when(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
        anyString(), anyString())).thenAnswer(inv -> {
          calls.add("reset:" + inv.<String>getArgument(3));
          return new HashMap<String, Integer>();
        });
  }

  /**
   * Stubs {@code OBMessageUtils.translateError} to echo {@code message} back, so what lands in the
   * accumulator is exactly the translated text the production code asked for. This is the FALLBACK
   * path only — a raw message carrying an {@code @KEY@} placeholder is resolved through
   * {@link #stubMessageBd} instead.
   */
  private void stubTranslateError(MockedStatic<OBMessageUtils> msgMock, String message) {
    OBError translated = mock(OBError.class);
    when(translated.getMessage()).thenReturn(message);
    msgMock.when(() -> OBMessageUtils.translateError(anyString())).thenReturn(translated);
  }

  /**
   * Stubs the AD_Message dictionary lookup for one key. This — not {@code translateError} — is what
   * {@code userFacingReason} consults when the raw message carries an {@code @KEY@} placeholder, so
   * every test whose simulated Core failure embeds a key must stub it here.
   */
  private void stubMessageBd(MockedStatic<OBMessageUtils> msgMock, String key, String text) {
    msgMock.when(() -> OBMessageUtils.messageBD(key)).thenReturn(text);
  }

  // ── firstFailureReason ──────────────────────────────────────────────────────

  /**
   * The reason returned belongs to the first FAILED id that has one — the second here, since the
   * first failed id has no recorded reason at all.
   */
  @Test
  public void testFirstFailureReasonReturnsTheFirstFailedIdThatHasOne() {
    Map<String, String> reasons = new LinkedHashMap<>();
    reasons.put("T2", REASON_PERIOD_CLOSED);

    assertEquals(REASON_PERIOD_CLOSED,
        ReconciliationHandlerSupport.firstFailureReason(Arrays.asList("T1", "T2"), reasons));
  }

  /**
   * The discriminating case for iterating {@code failedIds} rather than the reason map: a helper
   * recorded a reason for T1, but Core then freed T1 anyway, so only T2 is reported as failed.
   * Quoting T1's reason would explain a failure that did not happen — so T2's reason must win even
   * though T1's was recorded FIRST (a map-order iteration would return T1's).
   */
  @Test
  public void testFirstFailureReasonIgnoresAReasonRecordedForATransactionThatDidNotFail() {
    Map<String, String> reasons = new LinkedHashMap<>();
    reasons.put("T1", REASON_OTHER);          // recorded, but T1 ended up freed → not in failedIds
    reasons.put("T2", REASON_PERIOD_CLOSED);

    String reason = ReconciliationHandlerSupport.firstFailureReason(
        Collections.singletonList("T2"), reasons);

    assertEquals(REASON_PERIOD_CLOSED, reason);
    assertFalse("must not quote the reason of a transaction that was not reported as failed",
        REASON_OTHER.equals(reason));
  }

  /** Nothing failed for a reason anyone recorded → no message to show. */
  @Test
  public void testFirstFailureReasonReturnsNullOnAnEmptyReasonMap() {
    assertNull(ReconciliationHandlerSupport.firstFailureReason(
        Arrays.asList("T1", "T2"), new LinkedHashMap<>()));
  }

  /** Nothing failed at all (the whole batch went through) → no message, whatever was recorded. */
  @Test
  public void testFirstFailureReasonReturnsNullWhenNoIdFailed() {
    Map<String, String> reasons = new LinkedHashMap<>();
    reasons.put("T1", REASON_PERIOD_CLOSED);

    assertNull(ReconciliationHandlerSupport.firstFailureReason(
        Collections.<String>emptyList(), reasons));
  }

  /**
   * A blank reason is not a reason: {@code recordFailure} stores {@code trimToEmpty(...)}, so an
   * exception with no usable message leaves an empty string behind. That must read as "no reason
   * available", never as an empty message the caller would then attach to the response.
   */
  @Test
  public void testFirstFailureReasonReturnsNullWhenEveryRecordedReasonIsBlank() {
    Map<String, String> reasons = new LinkedHashMap<>();
    reasons.put("T1", "");
    reasons.put("T2", "   ");

    assertNull(ReconciliationHandlerSupport.firstFailureReason(
        Arrays.asList("T1", "T2"), reasons));
  }

  // ── undoWholeReconciliation (via removeSelectedFromReconciliations) ──────────

  /**
   * The whole-document undo is ONE Core call for the entire reconciliation, so its failure applies
   * to every transaction the caller asked about — not just to whichever one happens to be first.
   * A per-transaction attribution here would leave the other ids reported as failed with no reason,
   * and {@code firstFailureReason} would then return null for a batch whose cause is perfectly
   * well known.
   *
   * <p>The simulated failure carries the REAL raw message Core throws ({@link #RAW_CORE_CHAIN}), so
   * this also pins that what each transaction ends up holding is the dictionary sentence alone —
   * the whole-string translation is stubbed too, and must not be what lands in the map.
   */
  @Test
  public void testUndoFailureRecordsTheSameReasonForEveryTransactionOfTheReconciliation()
      throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_FinaccTransaction t3 = txnWithId("T3");
    FIN_Reconciliation rec = recWith("rec-1", t1, t2, t3);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    // Whole selection → coversReconciliation is true → the undo branch.
    List<FIN_FinaccTransaction> selForRec = Arrays.asList(t1, t2, t3);
    doThrow(new OBException(RAW_CORE_CHAIN)).when(handler)
        .undoReconciliation(any(), any(), any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", selForRec);
    Map<String, String> failureReasons = new LinkedHashMap<>();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      // Every selected transaction is re-loaded by id before it is handed to Core (the unposting
      // pass flushes/clears the session), so the reloads must yield the mocks the assertions below
      // are keyed on — an unstubbed reload would silently drop them from the batch.
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      when(dal.get(FIN_FinaccTransaction.class, "T3")).thenReturn(t3);
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      // Wired so that a regression to translating the whole chain would be observable here rather
      // than silently yielding null.
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);
    }

    // Every transaction of the failed document carries the cause, not just one of them.
    assertEquals(3, failureReasons.size());
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T1"));
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T2"));
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T3"));
    // Whichever id the caller reports first, the reason is available for it.
    assertEquals(PERIOD_CLOSED_TRANSLATED,
        ReconciliationHandlerSupport.firstFailureReason(Arrays.asList("T3"), failureReasons));
  }

  /** A successful undo records nothing — an empty accumulator is what "no failure" looks like. */
  @Test
  public void testSuccessfulUndoRecordsNoReason() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_Reconciliation rec = recWith("rec-1", t1);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doNothing().when(handler).undoReconciliation(any(), any(), any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Collections.singletonList(t1));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
      // Re-loaded by id before the hand-off to Core — see the unposting pass.
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      // Neither branch of userFacingReason is even reached when nothing failed.
      msgMock.verify(() -> OBMessageUtils.translateError(anyString()), never());
      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }

    assertTrue(failureReasons.isEmpty());
  }

  // ── detachSelected ──────────────────────────────────────────────────────────

  /**
   * The subset path attempts every selected id independently. When Core throws on ONE of them, the
   * reason is attributed to that id alone (the other two really were detached, so claiming a reason
   * for them would be a lie the caller would then quote), and — the pre-existing no-abort contract
   * this must not regress — the ids after the failing one are still attempted.
   */
  @Test
  public void testDetachSelectedRecordsTheReasonForTheFailingIdOnlyAndKeepsGoing() {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2"); // Core refuses this one
    FIN_FinaccTransaction t3 = txnWithId("T3");
    recWith("rec-1", t1, t2, t3);
    doReturn(false).when(handler).isAutoCreated(any());
    Map<String, String> failureReasons = new LinkedHashMap<>();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      when(dal.get(FIN_FinaccTransaction.class, "T3")).thenReturn(t3);
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenAnswer(inv -> {
            if (inv.getArgument(0) == t2) {
              throw new OBException(RAW_CORE_CHAIN);
            }
            return true;
          });

      ReconciliationHandlerSupport.detachSelected(
          handler, Arrays.asList(t1, t2, t3), failureReasons);

      // No-abort contract: T3 (queued AFTER the failing T2) was still handed to Core.
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(eq(t1)));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(eq(t2)));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(eq(t3)));
    }

    assertEquals(1, failureReasons.size());
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T2"));
    assertNull(failureReasons.get("T1"));
    assertNull(failureReasons.get("T3"));
  }

  /**
   * The accumulator is keyed by transaction id, so a batch whose ids fail for DIFFERENT causes
   * keeps both — {@code firstFailureReason} then picks by the caller's failed-id order rather than
   * by whichever exception happened to be thrown first.
   */
  @Test
  public void testDetachSelectedKeepsAPerTransactionReasonForEachDistinctCause() {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    recWith("rec-1", t1, t2);
    doReturn(false).when(handler).isAutoCreated(any());
    Map<String, String> failureReasons = new LinkedHashMap<>();
    // One translation per distinct cause, wired up front (rather than built inside the answer) so
    // no mock is created while a static mock is mid-invocation.
    OBError closedPeriod = mock(OBError.class);
    when(closedPeriod.getMessage()).thenReturn(REASON_PERIOD_CLOSED);
    OBError other = mock(OBError.class);
    when(other.getMessage()).thenReturn(REASON_OTHER);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      // Each raw message translates to its own text, so each id keeps ITS own cause.
      msgMock.when(() -> OBMessageUtils.translateError(REASON_PERIOD_CLOSED))
          .thenReturn(closedPeriod);
      msgMock.when(() -> OBMessageUtils.translateError(REASON_OTHER)).thenReturn(other);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenAnswer(inv -> {
            throw new OBException(inv.getArgument(0) == t1 ? REASON_PERIOD_CLOSED : REASON_OTHER);
          });

      ReconciliationHandlerSupport.detachSelected(
          handler, Arrays.asList(t1, t2), failureReasons);
    }

    assertEquals(2, failureReasons.size());
    assertEquals(REASON_PERIOD_CLOSED, failureReasons.get("T1"));
    assertEquals(REASON_OTHER, failureReasons.get("T2"));
    // The caller's failed-id order decides, not the recording order.
    assertEquals(REASON_OTHER,
        ReconciliationHandlerSupport.firstFailureReason(Arrays.asList("T2", "T1"), failureReasons));
  }

  /**
   * An exception with no message must not manufacture an empty "reason". The blank lands in the
   * map (the id IS recorded as having been attempted) but {@code firstFailureReason} skips it, so
   * the caller attaches no message rather than an empty one.
   *
   * <p>This is also the null-safety pin for {@code userFacingReason}: {@code recordFailure}
   * normalises {@code getMessage() == null} to {@code ""} before handing it over, so the regex
   * matcher never receives {@code null}. An {@code OBException} with no message at all is the exact
   * shape that would otherwise blow up there, and it must produce no crash and nothing usable.
   */
  @Test
  public void testDetachSelectedBlankExceptionMessageYieldsNoUsableReason() {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    recWith("rec-1", t1);
    doReturn(false).when(handler).isAutoCreated(any());
    Map<String, String> failureReasons = new LinkedHashMap<>();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      stubTranslateError(msgMock, null); // translating "" yields nothing usable
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenAnswer(inv -> {
            throw new OBException();
          });

      ReconciliationHandlerSupport.detachSelected(
          handler, Collections.singletonList(t1), failureReasons);

      // No placeholder in a blank message → nothing to look up in the dictionary.
      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }

    assertEquals("", failureReasons.get("T1"));
    assertNull(ReconciliationHandlerSupport.firstFailureReason(
        Collections.singletonList("T1"), failureReasons));
  }

  /** A clean subset detach records nothing and never asks for a translation. */
  @Test
  public void testDetachSelectedSuccessRecordsNoReason() {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    recWith("rec-1", t1, t2);
    doReturn(false).when(handler).isAutoCreated(any());
    Map<String, String> failureReasons = new LinkedHashMap<>();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil = mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
      when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenReturn(true);

      ReconciliationHandlerSupport.detachSelected(
          handler, Arrays.asList(t1, t2), failureReasons);

      // Neither branch of userFacingReason is even reached when nothing failed.
      msgMock.verify(() -> OBMessageUtils.translateError(anyString()), never());
      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }

    assertTrue(failureReasons.isEmpty());
    assertNull(ReconciliationHandlerSupport.firstFailureReason(
        Arrays.asList("T1", "T2"), failureReasons));
  }

  // ── userFacingReason ────────────────────────────────────────────────────────
  // Core wraps each cause in untranslated English prose and concatenates the chain with NO
  // separator, so translating the raw message as a whole leaves English fragments glued in front of
  // the Spanish sentence the user is meant to read. userFacingReason resolves the LAST @KEY@
  // placeholder instead — the innermost cause is the specific one — and only falls back to the
  // whole-string translation when there is nothing better to show.

  /**
   * The regression test for the reported defect, built from the raw message copied out of the live
   * server log: the result is the dictionary sentence and ONLY the dictionary sentence. Every
   * fragment of Core's English prose must be gone.
   *
   * <p>The whole-string translation is stubbed to what the previous implementation would have
   * returned, so this test is red against it by construction rather than by omission.
   */
  @Test
  public void testUserFacingReasonKeepsOnlyTheDictionarySentenceOfTheRealCoreChain() {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);

      String reason = ReconciliationHandlerSupport.userFacingReason(RAW_CORE_CHAIN);

      assertEquals(PERIOD_CLOSED_TRANSLATED, reason);
      assertFalse("Core's English wrapper prose must not survive into the toast",
          reason.contains("Error when removing the transaction from reconciliation"));
      assertFalse("Core's English wrapper prose must not survive into the toast",
          reason.contains("Error when reactivating reconciliation"));
      assertNotEquals("translating the raw chain as a whole is exactly the bug",
          WHOLE_STRING_TRANSLATION, reason);
    }
  }

  /**
   * With several placeholders in the chain the LAST one wins: the outermost wrapper names the
   * generic operation that failed, the innermost names the actual cause, and only the latter tells
   * the user anything they can act on.
   */
  @Test
  public void testUserFacingReasonResolvesTheLastPlaceholderNotTheFirst() {
    String outerText = "The transaction could not be removed";
    String raw = "Wrapper@RemoveTransactionError@ deeper cause@" + PERIOD_CLOSED_KEY + "@";

    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, "RemoveTransactionError", outerText);
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, "unused whole-string translation");

      String reason = ReconciliationHandlerSupport.userFacingReason(raw);

      assertEquals(PERIOD_CLOSED_TRANSLATED, reason);
      assertNotEquals("the outer, generic cause must not shadow the specific one", outerText,
          reason);
    }
  }

  /**
   * A message with no placeholder at all (a plain Java error, a database message) has nothing to
   * extract, so it is translated whole exactly as before — the fallback is not a degraded path, it
   * is the correct handling for that shape.
   */
  @Test
  public void testUserFacingReasonFallsBackToTranslateErrorWithoutAPlaceholder() {
    String raw = "java.lang.IllegalStateException: the session was already closed";

    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubTranslateError(msgMock, REASON_OTHER);

      assertEquals(REASON_OTHER, ReconciliationHandlerSupport.userFacingReason(raw));
      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }
  }

  /**
   * A key that resolves to blank carries no information, so the whole-string translation — English
   * prose and all — is still better than an empty toast description.
   */
  @Test
  public void testUserFacingReasonFallsBackToTranslateErrorWhenTheDictionaryReturnsBlank() {
    String raw = "Wrapper prose@SomeUndefinedKey@";

    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, "SomeUndefinedKey", "   ");
      stubTranslateError(msgMock, REASON_OTHER);

      assertEquals(REASON_OTHER, ReconciliationHandlerSupport.userFacingReason(raw));
    }
  }

  /**
   * {@code messageBD} echoes the key back when it is not in AD_Message. Showing a raw identifier
   * like {@code MissingFromDictionary} in a toast is worse than useless, so that counts as "not
   * found" and falls through to the whole-string translation.
   */
  @Test
  public void testUserFacingReasonFallsBackToTranslateErrorWhenMessageBdEchoesTheKey() {
    String key = "MissingFromDictionary";

    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubMessageBd(msgMock, key, key); // the not-found behaviour of the real messageBD
      stubTranslateError(msgMock, REASON_OTHER);

      String reason = ReconciliationHandlerSupport.userFacingReason("Wrapper prose@" + key + "@");

      assertEquals(REASON_OTHER, reason);
      assertNotEquals("a bare message key must never reach the user", key, reason);
    }
  }

  /**
   * An empty raw message — what {@code recordFailure} passes when the exception carried none — has
   * no placeholder and translates to nothing usable. It must not crash, and it must not invent a
   * reason: {@code recordFailure} then stores {@code ""}, which {@code firstFailureReason} reads as
   * "no reason available".
   */
  @Test
  public void testUserFacingReasonOnAnEmptyMessageYieldsNothingUsable() {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubTranslateError(msgMock, null);

      assertNull(ReconciliationHandlerSupport.userFacingReason(""));
      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }
  }

  /**
   * A stray {@code @} that is not a well-formed placeholder (an email address, an annotation name
   * in a stack trace) must not be mistaken for one — the message is translated whole.
   */
  @Test
  public void testUserFacingReasonIgnoresAStrayAtSignThatIsNotAPlaceholder() {
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      stubTranslateError(msgMock, REASON_OTHER);

      assertEquals(REASON_OTHER,
          ReconciliationHandlerSupport.userFacingReason("Rejected by admin@example.com"));
      msgMock.verify(() -> OBMessageUtils.messageBD(anyString()), never());
    }
  }

  // -- unpostBeforeUndo --------------------------------------------------------
  // Un-reconciling a POSTED document reported "@PeriodClosedForUnPosting@" on an environment whose
  // periods were ALL open. The cause is a date mismatch, not a period: the removal module resets
  // accounting over the RECONCILIATION's date, Core dates the document's Fact_Acct rows with the
  // TRANSACTION's date, and when they differ the range matches nothing -- zero deleted, zero
  // updated -- so ResetAccounting takes its catch-all throw, a branch that performs NO period check
  // whatsoever. Unposting first over an OPEN range (what Classic's own unpost button does) leaves
  // the document with no entries, so that narrow reset becomes a harmless no-op; a genuinely closed
  // period still fails, and now the failure it reports is the one that actually looked for entries.
  //
  // The helper takes an ID rather than an entity, and re-reads the record after the reset, because
  // ResetAccounting runs native SQL and flushes/clears the Hibernate session. Handing it a loaded
  // instance -- and then saving THAT instance -- is what produced, on the live environment:
  //   OBInterceptor WARN: FIN_Reconciliation(...) is detected as not new but it does not have a
  //   current state in the database
  //   org.hibernate.NonUniqueObjectException: A different object with the same identifier value was
  //   already associated with the session : [FIN_Finacc_Transaction#...]
  // Every test below is therefore keyed on the id, and the re-read is asserted explicitly.

  /**
   * The load-bearing assertion of the fix: the reset is scoped to this one document (client,
   * organization, table, record) and its date range is OPEN -- both ends empty.
   *
   * <p>The empty strings are asserted literally rather than through {@code anyString()} on purpose.
   * Passing the reconciliation's own date there is precisely the defect being compensated, so a
   * matcher that accepted any date would let the regression straight back in.
   */
  @Test
  public void testUnpostBeforeUndoResetsThePostedDocumentOverAnOpenDateRange() {
    FIN_Reconciliation rec = postedRec("rec-1", POSTED_YES);
    // Built before the static mock is installed, as everywhere else in this class.
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.unpostBeforeUndo("rec-1");

      verifyOpenRangeReset(ra, "rec-1");
    }
  }

  /**
   * A document that was never posted has no accounting entries to remove, so the reset is skipped
   * entirely and the posted flag is left alone.
   *
   * <p>The reconciliation is deliberately BARE -- {@code client} / {@code organization} /
   * {@code entity} are not stubbed -- so a regression that reached the reset would fail here with
   * an NPE even before the verifications below could run. The single {@code get} also pins that the
   * post-reset re-read is not performed when there was no reset.
   */
  @Test
  public void testUnpostBeforeUndoDoesNothingWhenTheDocumentIsNotPosted() {
    FIN_Reconciliation rec = recWith("rec-1");
    when(rec.getPosted()).thenReturn("N");
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.unpostBeforeUndo("rec-1");

      ra.verifyNoInteractions();
    }
    verify(rec, never()).setPosted(any());
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
    verify(dal, times(1)).get(FIN_Reconciliation.class, "rec-1");
  }

  /**
   * A null posted flag is the same "nothing to unpost" case as {@code "N"} and must be read as such
   * rather than dereferenced -- the guard compares the constant against the getter, not the other
   * way round, so this cannot NPE.
   */
  @Test
  public void testUnpostBeforeUndoDoesNothingWhenThePostedFlagIsNull() {
    FIN_Reconciliation rec = recWith("rec-1");
    when(rec.getPosted()).thenReturn(null);
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.unpostBeforeUndo("rec-1");

      ra.verifyNoInteractions();
    }
    verify(rec, never()).setPosted(any());
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
  }

  /**
   * A blank id is a no-op that never even reaches the DAL. {@code removeSelectedFromReconciliations}
   * iterates a map whose keys are ids, and {@code ReconciliationHandler.reactivate} passes
   * {@code rec.getId()}; neither can be trusted to be non-blank in every code path, and looking up
   * {@code null} would throw inside Hibernate rather than do nothing.
   */
  @Test
  public void testUnpostBeforeUndoIsANoOpForABlankId() {
    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      ReconciliationHandlerSupport.unpostBeforeUndo(null);
      ReconciliationHandlerSupport.unpostBeforeUndo("");
      ReconciliationHandlerSupport.unpostBeforeUndo("   ");

      ra.verifyNoInteractions();
      obDal.verifyNoInteractions();
    }
  }

  /**
   * The id resolves to nothing -- the document was already deleted by an earlier unit of the same
   * batch, which Core's mid-flow commits make possible. Nothing to unpost, and nothing to save:
   * dereferencing the null is the only wrong answer here.
   */
  @Test
  public void testUnpostBeforeUndoIsANoOpWhenTheReconciliationNoLongerExists() {
    // Unstubbed: get(...) answers null for every id, which is what a deleted record looks like.
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.unpostBeforeUndo("rec-gone");

      ra.verifyNoInteractions();
    }
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
  }

  /**
   * After a successful reset the document is flagged unposted and that change is persisted -- and
   * in that order, since flushing before the flag is set would leave the database claiming the
   * document is still posted while its entries are gone.
   */
  @Test
  public void testUnpostBeforeUndoFlagsTheDocumentUnpostedAndPersistsIt() {
    FIN_Reconciliation rec = postedRec("rec-1", POSTED_YES);
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.unpostBeforeUndo("rec-1");

      verifyOpenRangeReset(ra, "rec-1");
    }

    InOrder order = Mockito.inOrder(rec, dal);
    order.verify(rec).setPosted("N");
    order.verify(dal).save(rec);
    order.verify(dal).flush();
  }

  /**
   * The regression test for the OBInterceptor warning that reached the live environment:
   * {@code FIN_Reconciliation(...) is detected as not new but it does not have a current state in
   * the database}.
   *
   * <p>{@code ResetAccounting.delete} runs native SQL and flushes/clears the Hibernate session, so
   * the instance loaded BEFORE it is detached by the time the posted flag is flipped. Saving that
   * detached copy is exactly what produced the warning -- and, once Core reloaded its own copy, the
   * {@code NonUniqueObjectException} on the transactions hanging off it.
   *
   * <p>The fixture models the session honestly: two DISTINCT instances of the same record answer
   * two successive reads of the same id, the way a real {@code OBDal.get} does across a flush/clear.
   * The assertion is not merely "something was saved" but WHICH instance was: the fresh one, never
   * the pre-reset one. A regression that dropped the re-read would still save an object with the
   * right id and would still pass a plain {@code verify(dal).save(any())}.
   */
  @Test
  public void testUnpostBeforeUndoFlagsTheReReadInstanceNeverTheOneLoadedBeforeTheReset() {
    FIN_Reconciliation preReset = postedRec("rec-1", POSTED_YES);
    FIN_Reconciliation fresh = postedRec("rec-1", POSTED_YES);
    OBDal dal = mock(OBDal.class);
    // First read (before the reset) yields the instance the session then detaches; every read after
    // it yields the copy Hibernate re-materialises.
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(preReset, fresh);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.unpostBeforeUndo("rec-1");

      verifyOpenRangeReset(ra, "rec-1");
    }

    assertNotSame("the fixture must model two distinct instances of the same record", preReset,
        fresh);
    // The record IS re-read after the reset -- two reads of the same id, not one.
    verify(dal, times(2)).get(FIN_Reconciliation.class, "rec-1");
    verify(fresh).setPosted("N");
    verify(dal).save(fresh);
    verify(dal).flush();
    // …and the detached pre-reset instance is never written back.
    verify(preReset, never()).setPosted(any());
    verify(dal, never()).save(preReset);
  }

  /**
   * The whole point of resetting with an open range is that the failure it reports is TRUE, so that
   * failure must reach the caller. A genuinely closed period still has to stop the un-reconcile:
   * swallowing the exception here would turn a real accounting guard into a silent no-op and let
   * the undo proceed against a document whose entries are still there.
   *
   * <p>The flag is also left untouched -- claiming {@code posted = "N"} for a document whose
   * entries were NOT removed would be worse than the original defect -- and the re-read never runs,
   * since there was no successful reset to recover the session from.
   */
  @Test
  public void testUnpostBeforeUndoPropagatesAResetFailureInsteadOfSwallowingIt() {
    FIN_Reconciliation rec = postedRec("rec-1", POSTED_YES);
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      ra.when(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
          anyString(), anyString())).thenThrow(new OBException(RAW_CORE_CHAIN));

      try {
        ReconciliationHandlerSupport.unpostBeforeUndo("rec-1");
        fail("a failing reset must propagate so a genuinely closed period still stops the undo");
      } catch (OBException expected) {
        assertEquals(RAW_CORE_CHAIN, expected.getMessage());
      }
    }

    verify(rec, never()).setPosted(any());
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
    verify(dal, times(1)).get(FIN_Reconciliation.class, "rec-1");
  }

  // -- the unposting PASS runs before the removal pass --------------------------
  // The unposting used to happen inside the removal loop, one document at a time, immediately
  // before that document was handed to Core. That is the arrangement that reached the live
  // environment and broke: ResetAccounting flushes/clears the session, so the reconciliation and
  // transaction instances the loop had already captured were detached from that point on. It now
  // runs as its OWN earlier pass over every affected reconciliation, and the removal pass re-loads
  // everything by id. The tests below assert that arrangement directly -- a regression back to
  // unposting inside the loop produces the interleaving "unpost, remove, unpost, remove", which is
  // exactly what they reject.

  /**
   * Whole-document path, two reconciliations: EVERY unpost happens before ANY undo.
   *
   * <p>This is the load-bearing ordering property of the new arrangement, and the one a regression
   * to mid-loop unposting breaks. A single-reconciliation test cannot see it: with one document,
   * "unpost then remove" holds under BOTH arrangements. With two, the old code produces
   * {@code reset:rec-1, undo:rec-1, reset:rec-2, undo:rec-2} -- and it is that second reset, issued
   * after Core already churned the session for rec-1, that detaches what the loop is holding.
   *
   * <p>The recorder captures the real interleaving because one seam is a static mock and the other
   * an instance mock, which no single {@code InOrder} can span.
   */
  @Test
  public void testEveryUnpostRunsBeforeAnyUndoAcrossReconciliations() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec1 = postedRec("rec-1", POSTED_YES, t1);
    FIN_Reconciliation rec2 = postedRec("rec-2", POSTED_YES, t2);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    List<String> calls = new ArrayList<>();
    doAnswer(inv -> {
      calls.add("undo:" + inv.<FIN_Reconciliation>getArgument(1).getId());
      return null;
    }).when(handler).undoReconciliation(any(), any(), any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec1);
    recById.put("rec-2", rec2);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Collections.singletonList(t1));
    selectedByRec.put("rec-2", Collections.singletonList(t2));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec1);
    when(dal.get(FIN_Reconciliation.class, "rec-2")).thenReturn(rec2);
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
    when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      recordResets(ra, calls);

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      verifyOpenRangeResetForEach(ra, "rec-1", "rec-2");
    }

    assertEquals(Arrays.asList("reset:rec-1", "reset:rec-2", "undo:rec-1", "undo:rec-2"), calls);
    assertTrue(failureReasons.isEmpty());
  }

  /**
   * Subset path, two reconciliations: the same "every unpost first" property.
   *
   * <p>{@code detachSelected} no longer unposts on its own -- detaching one transaction reactivates
   * and reprocesses the whole reconciliation, so running the reset inside that loop would detach the
   * transaction instance the loop had just loaded. The reset now belongs to the caller's earlier
   * pass, and this pins that it still lands before the FIRST detach, not merely before its own.
   */
  @Test
  public void testEveryUnpostRunsBeforeAnyDetachAcrossReconciliations() {
    FIN_FinaccTransaction sel1 = txnWithId("T1");
    FIN_FinaccTransaction keep1 = txnWithId("K1"); // not selected -> rec-1 stays partially covered
    FIN_FinaccTransaction sel2 = txnWithId("T2");
    FIN_FinaccTransaction keep2 = txnWithId("K2"); // not selected -> rec-2 stays partially covered
    FIN_Reconciliation rec1 = postedRec("rec-1", POSTED_YES, sel1, keep1);
    FIN_Reconciliation rec2 = postedRec("rec-2", POSTED_YES, sel2, keep2);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(false).when(handler).isAutoCreated(any());
    List<String> calls = new ArrayList<>();

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec1);
    recById.put("rec-2", rec2);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Collections.singletonList(sel1));
    selectedByRec.put("rec-2", Collections.singletonList(sel2));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec1);
    when(dal.get(FIN_Reconciliation.class, "rec-2")).thenReturn(rec2);
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(sel1);
    when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(sel2);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      recordResets(ra, calls);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenAnswer(inv -> {
            calls.add("detach:" + inv.<FIN_FinaccTransaction>getArgument(0).getId());
            return true;
          });

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      verifyOpenRangeResetForEach(ra, "rec-1", "rec-2");
      // Only the SELECTED transactions are detached; the others keep their reconciliation.
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(keep1), never());
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(keep2), never());
    }

    assertEquals(Arrays.asList("reset:rec-1", "reset:rec-2", "detach:T1", "detach:T2"), calls);
    assertTrue(failureReasons.isEmpty());
    verify(handler, never()).isAutoCreated(keep1);
  }

  /**
   * The direct guard against the arrangement that broke production: {@code detachSelected} must not
   * unpost anything itself. It is called from inside the removal pass, after the session has already
   * been churned once; a reset issued from here would detach the transaction the loop just loaded on
   * the line above and reintroduce the {@code NonUniqueObjectException}.
   *
   * <p>The reconciliation is POSTED, so a helper that still unposted would issue a reset and fail
   * this immediately rather than pass vacuously.
   */
  @Test
  public void testDetachSelectedDoesNotUnpostOnItsOwn() {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    postedRec("rec-1", POSTED_YES, t1);
    doReturn(false).when(handler).isAutoCreated(any());
    Map<String, String> failureReasons = new LinkedHashMap<>();
    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenReturn(true);

      ReconciliationHandlerSupport.detachSelected(
          handler, Collections.singletonList(t1), failureReasons);

      ra.verifyNoInteractions();
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t1));
    }

    assertTrue(failureReasons.isEmpty());
  }

  // -- a failed unpost is skipped, not retried through the removal ---------------

  /**
   * The reset is not allowed to break the reporting it enables. When it fails -- the case a REAL
   * closed period now produces -- the whole-document path still catches it, and every transaction
   * the caller asked about carries the dictionary sentence, exactly as when Core's undo was the one
   * that threw.
   *
   * <p>{@code undoReconciliation} is never reached, which is the point: the document still has its
   * entries, so undoing it would only fail again, less informatively -- and its message would
   * OVERWRITE the accurate reason already recorded here.
   */
  @Test
  public void testUndoWholeReconciliationRecordsAReasonWhenTheResetItselfFails() throws Exception {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2");
    FIN_Reconciliation rec = postedRec("rec-1", POSTED_YES, t1, t2);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Arrays.asList(t1, t2));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
    // The failed unpost still reports per TRANSACTION, and those are re-loaded by id like every
    // other hand-off to Core.
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
    when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);
      ra.when(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
          anyString(), anyString())).thenThrow(new OBException(RAW_CORE_CHAIN));

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);
    }

    assertEquals(2, failureReasons.size());
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T1"));
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T2"));
    assertEquals(PERIOD_CLOSED_TRANSLATED, ReconciliationHandlerSupport.firstFailureReason(
        Arrays.asList("T1", "T2"), failureReasons));
    verify(handler, never()).undoReconciliation(any(), any(), any());
  }

  /**
   * A reconciliation whose unpost throws is SKIPPED by the removal pass, while a healthy one in the
   * same batch still completes.
   *
   * <p>Two things are pinned here, and both regress independently. First the no-abort contract: one
   * document refusing to unpost must not stop the others, because Core commits mid-flow and
   * abandoning the batch only leaves more work undone. Second the skip itself: the removal for the
   * failed document is not even attempted -- neither {@code undoReconciliation} nor
   * {@code removeTransactionFromReconciliation}. Attempting it would fail too, and its generic
   * "could not remove the transaction" message would OVERWRITE the accurate closed-period reason
   * this pass already recorded, which is the message the user is shown.
   */
  @Test
  public void testAReconciliationWhoseUnpostFailsIsSkippedWhileTheRestOfTheBatchCompletes()
      throws Exception {
    FIN_FinaccTransaction good = txnWithId("T-GOOD");
    FIN_FinaccTransaction bad = txnWithId("T-BAD");
    FIN_Reconciliation healthy = postedRec("rec-ok", POSTED_YES, good);
    FIN_Reconciliation refuses = postedRec("rec-bad", POSTED_YES, bad);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doNothing().when(handler).undoReconciliation(any(), any(), any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-bad", refuses); // queued FIRST: the healthy one must still be reached
    recById.put("rec-ok", healthy);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-bad", Collections.singletonList(bad));
    selectedByRec.put("rec-ok", Collections.singletonList(good));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-ok")).thenReturn(healthy);
    when(dal.get(FIN_Reconciliation.class, "rec-bad")).thenReturn(refuses);
    when(dal.get(FIN_FinaccTransaction.class, "T-GOOD")).thenReturn(good);
    when(dal.get(FIN_FinaccTransaction.class, "T-BAD")).thenReturn(bad);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);
      ra.when(() -> ResetAccounting.delete(anyString(), anyString(), anyString(), anyString(),
          anyString(), anyString())).thenAnswer(inv -> {
            if ("rec-bad".equals(inv.<String>getArgument(3))) {
              throw new OBException(RAW_CORE_CHAIN);
            }
            return new HashMap<String, Integer>();
          });

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      // The skipped document is not pushed through the subset path either.
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()), never());
      // Both documents were still ATTEMPTED in the unposting pass -- the first failure did not
      // abandon the batch.
      verifyOpenRangeResetForEach(ra, "rec-bad", "rec-ok");
    }

    // The failed document carries the accurate cause…
    assertEquals(1, failureReasons.size());
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T-BAD"));
    assertNull(failureReasons.get("T-GOOD"));
    // …and is never handed to the removal, while the healthy one is undone normally.
    verify(handler, never()).undoReconciliation(any(), eq(refuses), any());
    verify(handler, times(1)).undoReconciliation(any(), eq(healthy), any());
  }

  // -- Core is handed re-fetched instances, never the grouping-time ones ---------

  /**
   * Whole-document path: the reconciliation handed to {@code undoReconciliation}, and the
   * transaction list taken off it, are the ones re-read AFTER the unposting pass -- never the
   * instances {@code groupSelectedByReconciliation} captured before it.
   *
   * <p>The fixture makes the difference observable the only way a mock can: two distinct instances
   * carrying the same id, with the DAL answering the fresh one. Under the old arrangement the
   * grouping-time instance was carried straight into the Core call, which in a real session is the
   * detached copy that triggers {@code NonUniqueObjectException} on
   * {@code FIN_Finacc_Transaction#...}.
   */
  @Test
  public void testUndoReceivesTheRefetchedInstancesNotTheGroupingTimeOnes() throws Exception {
    FIN_FinaccTransaction staleTxn = txnWithId("T1");
    FIN_FinaccTransaction freshTxn = txnWithId("T1");
    FIN_Reconciliation staleRec = postedRec("rec-1", POSTED_YES, staleTxn);
    FIN_Reconciliation freshRec = postedRec("rec-1", POSTED_YES, freshTxn);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doNothing().when(handler).undoReconciliation(any(), any(), any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", staleRec);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Collections.singletonList(staleTxn));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(freshRec);
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(freshTxn);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      verifyOpenRangeReset(ra, "rec-1");
    }

    assertNotSame("the fixture must model two distinct instances of the same record", staleRec,
        freshRec);
    assertNotSame("the fixture must model two distinct instances of the same transaction", staleTxn,
        freshTxn);
    verify(handler).undoReconciliation(any(), eq(freshRec),
        eq(Collections.singletonList(freshTxn)));
    verify(handler, never()).undoReconciliation(any(), eq(staleRec), any());
    assertTrue(failureReasons.isEmpty());
  }

  /**
   * Subset path: the transaction Core is asked to detach is the re-read instance, not the one
   * {@code selectedByRec} was built from. {@code detachSelected} snapshots ids and re-fetches, but
   * the caller must ALSO re-fetch before that -- the {@code coversReconciliation} decision and the
   * {@code isAutoCreated} read both happen on the instances it passes in.
   */
  @Test
  public void testDetachReceivesTheRefetchedTransactionsNotTheGroupingTimeOnes() {
    FIN_FinaccTransaction staleSel = txnWithId("T1");
    FIN_FinaccTransaction freshSel = txnWithId("T1");
    FIN_FinaccTransaction keep = txnWithId("K1"); // not selected -> subset, so the detach path
    FIN_Reconciliation rec = postedRec("rec-1", POSTED_YES, freshSel, keep);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(false).when(handler).isAutoCreated(any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Collections.singletonList(staleSel));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec);
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(freshSel);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenReturn(true);

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      verifyOpenRangeReset(ra, "rec-1");
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(freshSel));
      recUtil.verify(
          () -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(staleSel), never());
    }

    assertNotSame("the fixture must model two distinct instances of the same transaction", staleSel,
        freshSel);
    verify(handler).isAutoCreated(freshSel);
    verify(handler, never()).isAutoCreated(staleSel);
    assertTrue(failureReasons.isEmpty());
  }

  /**
   * The pre-existing no-abort contract at TRANSACTION granularity, unchanged by the new pass: a
   * detach that Core refuses records that id's reason and leaves the others alone -- both the one
   * queued before it and, crucially, the one queued after.
   *
   * <p>Each transaction gets its OWN reconciliation, which is also the real shape of a
   * multi-reconciliation selection on the same account.
   */
  @Test
  public void testRemovalKeepsGoingWhenOneReconciliationsDetachFails() {
    FIN_FinaccTransaction t1 = txnWithId("T1");
    FIN_FinaccTransaction t2 = txnWithId("T2"); // Core refuses this one
    FIN_FinaccTransaction t3 = txnWithId("T3");
    FIN_FinaccTransaction keep1 = txnWithId("K1");
    FIN_FinaccTransaction keep2 = txnWithId("K2");
    FIN_FinaccTransaction keep3 = txnWithId("K3");
    FIN_Reconciliation rec1 = postedRec("rec-1", POSTED_YES, t1, keep1);
    FIN_Reconciliation rec2 = postedRec("rec-2", POSTED_YES, t2, keep2);
    FIN_Reconciliation rec3 = postedRec("rec-3", POSTED_YES, t3, keep3);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(false).when(handler).isAutoCreated(any());

    Map<String, FIN_Reconciliation> recById = new LinkedHashMap<>();
    recById.put("rec-1", rec1);
    recById.put("rec-2", rec2);
    recById.put("rec-3", rec3);
    Map<String, List<FIN_FinaccTransaction>> selectedByRec = new LinkedHashMap<>();
    selectedByRec.put("rec-1", Collections.singletonList(t1));
    selectedByRec.put("rec-2", Collections.singletonList(t2));
    selectedByRec.put("rec-3", Collections.singletonList(t3));
    Map<String, String> failureReasons = new LinkedHashMap<>();

    OBDal dal = mock(OBDal.class);
    when(dal.get(FIN_Reconciliation.class, "rec-1")).thenReturn(rec1);
    when(dal.get(FIN_Reconciliation.class, "rec-2")).thenReturn(rec2);
    when(dal.get(FIN_Reconciliation.class, "rec-3")).thenReturn(rec3);
    when(dal.get(FIN_FinaccTransaction.class, "T1")).thenReturn(t1);
    when(dal.get(FIN_FinaccTransaction.class, "T2")).thenReturn(t2);
    when(dal.get(FIN_FinaccTransaction.class, "T3")).thenReturn(t3);

    try (MockedStatic<ResetAccounting> ra = mockStatic(ResetAccounting.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ReconciliationRemovalUtil> recUtil =
            mockStatic(ReconciliationRemovalUtil.class);
        MockedStatic<PaymentRemovalUtil> payUtil = mockStatic(PaymentRemovalUtil.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubMessageBd(msgMock, PERIOD_CLOSED_KEY, PERIOD_CLOSED_TRANSLATED);
      stubTranslateError(msgMock, WHOLE_STRING_TRANSLATION);
      recUtil.when(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(any()))
          .thenAnswer(inv -> {
            if (inv.getArgument(0) == t2) {
              throw new OBException(RAW_CORE_CHAIN);
            }
            return true;
          });

      ReconciliationHandlerSupport.removeSelectedFromReconciliations(
          handler, account, recById, selectedByRec, failureReasons);

      // No-abort contract: T3 was queued AFTER the failing T2 and was still handed to Core.
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t1));
      recUtil.verify(() -> ReconciliationRemovalUtil.removeTransactionFromReconciliation(t3));
      // Every document was unposted up front, including the one whose detach later failed.
      verifyOpenRangeResetForEach(ra, "rec-1", "rec-2", "rec-3");
    }

    assertEquals(1, failureReasons.size());
    assertEquals(PERIOD_CLOSED_TRANSLATED, failureReasons.get("T2"));
    assertNull(failureReasons.get("T1"));
    assertNull(failureReasons.get("T3"));
  }
}
