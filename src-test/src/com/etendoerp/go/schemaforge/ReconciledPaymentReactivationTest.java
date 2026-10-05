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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Collections;

import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

/**
 * ETP-5547 unit tests for {@link ReconciledPaymentReactivation}, the two halves that bracket the
 * delegated Reactivate of a reconciled ({@code RPPC}) payment.
 *
 * <p>{@code prepare}: only an {@code RDNC}/{@code PWNC} paid level needs the payment moved off
 * {@code RPPC} before the reactivation; for {@code RPR}/{@code PPM} and {@code RPPC} Core already
 * restores the invoice's paid amounts exactly once, so touching the status there would make it
 * restore twice (or never). {@code finish}: after a successful reactivation the statement line is
 * cleaned under a savepoint, a failure there is contained (rolled back, session cleared, never
 * propagated), and a line that got matched to something else in the meantime is not touched.
 *
 * <p>Status changes go through raw JDBC ({@code writeStatus}: {@code UPDATE fin_payment} under a
 * savepoint) and only then onto the entity, so the tests assert the UPDATE, not {@code OBDal.save}.
 * {@code finish} flushes first and lets a flush failure propagate (it means the reactivation
 * itself did not persist); {@code finishAfterFailure} is the exception-path counterpart that
 * never throws.
 *
 * <p>Only {@link OBDal}, {@link OBContext} and {@link FIN_Utility} are mocked statically, and
 * {@link ReconciliationHandler} by construction — the class under test runs for real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciledPaymentReactivationTest {

  private static final String PAYMENT_ID = "pay-5547";
  private static final String LINE_ID = "bsl-5547";
  private static final String RPPC = "RPPC";
  private static final String RDNC = "RDNC";
  private static final String PWNC = "PWNC";
  private static final String NORMALIZE_METHOD = "normalizeReactivatedMatchGroup";
  private static final String USER_ID = "user-5547";
  private static final String FIN_PAYMENT_UPDATE = "UPDATE fin_payment";

  @Mock
  private OBDal dal;
  @Mock
  private OBContext obContext;
  @Mock
  private User user;
  @Mock
  private PreparedStatement statusPs;
  @Mock
  private Session session;
  @Mock
  private Connection conn;
  @Mock
  private Savepoint savepoint;
  @Mock
  private FIN_Payment payment;
  @Mock
  private FIN_FinaccTransaction trx;
  @Mock
  private FIN_FinaccTransaction otherTrx;
  @Mock
  private FIN_BankStatementLine line;
  @Mock(answer = Answers.RETURNS_SELF)
  private OBCriteria<FIN_FinaccTransaction> trxCriteria;

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<FIN_Utility> utilityStatic;

  @BeforeEach
  void setUp() throws Exception {
    dalStatic = mockStatic(OBDal.class);
    contextStatic = mockStatic(OBContext.class);
    utilityStatic = mockStatic(FIN_Utility.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getUser()).thenReturn(user);
    when(user.getId()).thenReturn(USER_ID);
    // writeStatus: raw JDBC UPDATE of FIN_Payment.status, under a savepoint.
    when(conn.prepareStatement(anyString())).thenReturn(statusPs);

    when(dal.getSession()).thenReturn(session);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.setSavepoint()).thenReturn(savepoint);
    when(conn.setSavepoint(anyString())).thenReturn(savepoint);

    when(payment.getId()).thenReturn(PAYMENT_ID);
    when(dal.get(FIN_Payment.class, PAYMENT_ID)).thenReturn(payment);

    when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(trxCriteria);
    when(trx.getFINBankStatementLineList()).thenReturn(Collections.singletonList(line));
    when(line.getId()).thenReturn(LINE_ID);
    when(dal.get(FIN_BankStatementLine.class, LINE_ID)).thenReturn(line);
  }

  @AfterEach
  void tearDown() {
    utilityStatic.close();
    contextStatic.close();
    dalStatic.close();
  }

  private void givenReconciled(boolean reconciled) {
    when(trxCriteria.uniqueResult()).thenReturn(reconciled ? trx : null);
    when(trxCriteria.list()).thenReturn(
        reconciled ? Collections.singletonList(trx) : Collections.emptyList());
  }

  private void givenPaidLevel(String paidStatus) {
    utilityStatic.when(() -> FIN_Utility.invoicePaymentStatus(payment)).thenReturn(paidStatus);
  }

  private void verifyStatusWritten(String status) throws SQLException {
    verify(conn).prepareStatement(contains(FIN_PAYMENT_UPDATE));
    verify(statusPs).setString(1, status);
    verify(statusPs).setString(3, PAYMENT_ID);
    verify(statusPs).executeUpdate();
    verify(payment).setStatus(status);
  }

  private static boolean normalizedAnything(MockedConstruction<ReconciliationHandler> handlers) {
    return handlers.constructed().stream()
        .flatMap(h -> Mockito.mockingDetails(h).getInvocations().stream())
        .anyMatch(inv -> NORMALIZE_METHOD.equals(inv.getMethod().getName()));
  }

  // ── prepare ─────────────────────────────────────────────────────────────────

  /**
   * RPR/PPM (seq 40) and RPPC (seq 60): Core restores the invoice's paid amounts on its own,
   * exactly once. Moving the status here would break that, so prepare must leave it alone — while
   * still capturing the matched statement line for finish.
   */
  @ParameterizedTest
  @ValueSource(strings = { "RPR", "PPM", RPPC })
  void testPrepareLeavesStatusUntouchedWhenCoreRestoresOnItsOwn(String paidStatus) throws Exception {
    when(payment.getStatus()).thenReturn(RPPC);
    givenReconciled(true);
    givenPaidLevel(paidStatus);

    ReconciledPaymentReactivation.Prepared prepared = ReconciledPaymentReactivation.prepare(PAYMENT_ID);

    assertNotNull(prepared);
    assertNull(prepared.appliedStatus());
    assertEquals(LINE_ID, prepared.statementLineId());
    verify(payment, never()).setStatus(anyString());
    verify(conn, never()).prepareStatement(anyString());
    verify(dal, never()).save(payment);
  }

  /** RDNC/PWNC (seq 50): neither Core step matches from RPPC, so prepare moves the payment first. */
  @ParameterizedTest
  @ValueSource(strings = { RDNC, PWNC })
  void testPrepareMovesReconciledPaymentToNotClearedPaidLevel(String paidStatus) throws Exception {
    when(payment.getStatus()).thenReturn(RPPC);
    givenReconciled(true);
    givenPaidLevel(paidStatus);

    ReconciledPaymentReactivation.Prepared prepared = ReconciledPaymentReactivation.prepare(PAYMENT_ID);

    assertNotNull(prepared);
    assertEquals(paidStatus, prepared.appliedStatus());
    verifyStatusWritten(paidStatus);
    verify(conn, never()).rollback(any(Savepoint.class));
  }

  /**
   * The status UPDATE fails: rolled back to its savepoint (the request transaction stays usable),
   * the entity keeps its old status, and prepare reports "nothing to do".
   */
  @Test
  void testPrepareRollsBackStatusWriteAndReturnsNullWhenUpdateFails() throws Exception {
    when(payment.getStatus()).thenReturn(RPPC);
    givenReconciled(true);
    givenPaidLevel(PWNC);
    when(statusPs.executeUpdate()).thenThrow(new SQLException("status update failed"));

    assertNull(assertDoesNotThrow(() -> ReconciledPaymentReactivation.prepare(PAYMENT_ID)));

    verify(conn).rollback(savepoint);
    verify(conn, never()).rollback();
    verify(payment, never()).setStatus(anyString());
  }

  @Test
  void testPrepareReturnsNullForNonReconciledPayment() {
    when(payment.getStatus()).thenReturn(PWNC);
    givenReconciled(false);
    givenPaidLevel(PWNC);

    assertNull(ReconciledPaymentReactivation.prepare(PAYMENT_ID));
    verify(payment, never()).setStatus(anyString());
  }

  @Test
  void testPrepareReturnsNullForMissingOrBlankPayment() {
    when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

    assertNull(ReconciledPaymentReactivation.prepare("missing"));
    assertNull(ReconciledPaymentReactivation.prepare(""));
    assertNull(ReconciledPaymentReactivation.prepare(null));
  }

  @Test
  void testPrepareNeverThrows() {
    when(dal.get(FIN_Payment.class, PAYMENT_ID)).thenThrow(new IllegalStateException("DB down"));

    assertNull(assertDoesNotThrow(() -> ReconciledPaymentReactivation.prepare(PAYMENT_ID)));
  }

  // ── finish ──────────────────────────────────────────────────────────────────

  @Test
  void testFinishWithNullIsNoOp() {
    try (MockedConstruction<ReconciliationHandler> handlers =
        mockConstruction(ReconciliationHandler.class)) {
      assertDoesNotThrow(() -> ReconciledPaymentReactivation.finish(null));

      verifyNoInteractions(dal);
      assertTrue(handlers.constructed().isEmpty());
    }
  }

  /**
   * W4: finish flushes the reactivation first and does NOT swallow a failure there — it means the
   * reactivation itself did not persist, and the caller must not answer success.
   */
  @Test
  void testFinishPropagatesWhenInitialFlushFails() {
    doThrow(new IllegalStateException("flush failed")).when(dal).flush();

    try (MockedConstruction<ReconciliationHandler> handlers =
        mockConstruction(ReconciliationHandler.class)) {
      assertThrows(IllegalStateException.class, () -> ReconciledPaymentReactivation.finish(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, null)));

      assertFalse(normalizedAnything(handlers));
    }
  }

  /** Reactivation went through (payment no longer processed): the unmatched line is cleaned. */
  @Test
  void testFinishCleansUnmatchedStatementLineAfterReactivation() throws Exception {
    when(payment.isProcessed()).thenReturn(false);
    when(line.getFinancialAccountTransaction()).thenReturn(null);

    try (MockedConstruction<ReconciliationHandler> handlers =
        mockConstruction(ReconciliationHandler.class)) {
      ReconciledPaymentReactivation.finish(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, null));

      verify(line).setMatchingtype(null);
      verify(line).setMatchedDocument(null);
      assertFalse(handlers.constructed().isEmpty());
      verify(handlers.constructed().get(0)).normalizeReactivatedMatchGroup(line);
      verify(conn, never()).rollback(any(Savepoint.class));
    }
  }

  /** The line was matched to another transaction in the meantime: not ours to touch. */
  @Test
  void testFinishLeavesReMatchedStatementLineAlone() throws Exception {
    when(payment.isProcessed()).thenReturn(false);
    when(line.getFinancialAccountTransaction()).thenReturn(otherTrx);

    try (MockedConstruction<ReconciliationHandler> handlers =
        mockConstruction(ReconciliationHandler.class)) {
      ReconciledPaymentReactivation.finish(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, null));

      verify(line, never()).setMatchingtype(any());
      verify(line, never()).setMatchedDocument(any());
      verify(dal, never()).save(line);
      assertFalse(normalizedAnything(handlers));
      verify(conn, never()).rollback(any(Savepoint.class));
    }
  }

  /**
   * A failure while normalizing is contained: rolled back to the clean-up's own savepoint (so the
   * reactivation's writes survive), the session cleared of the half-done state, and nothing
   * propagates out of finish — the reactivation outcome is already decided.
   */
  @Test
  void testFinishContainsNormalizationFailureBySavepointRollback() throws Exception {
    when(payment.isProcessed()).thenReturn(false);
    when(line.getFinancialAccountTransaction()).thenReturn(null);

    try (MockedConstruction<ReconciliationHandler> handlers = mockConstruction(
        ReconciliationHandler.class,
        (mock, context) -> when(mock.normalizeReactivatedMatchGroup(any()))
            .thenThrow(new IllegalStateException("collapse failed")))) {
      assertDoesNotThrow(() -> ReconciledPaymentReactivation.finish(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, null)));

      verify(conn).rollback(savepoint);
      verify(conn, never()).rollback();
      verify(session).clear();
    }
  }

  /** Same containment when calling the clean-up directly. */
  @Test
  void testCleanUpStatementLineRollsBackToSavepointWhenNormalizationFails() throws Exception {
    when(line.getFinancialAccountTransaction()).thenReturn(null);

    try (MockedConstruction<ReconciliationHandler> handlers = mockConstruction(
        ReconciliationHandler.class,
        (mock, context) -> when(mock.normalizeReactivatedMatchGroup(any()))
            .thenThrow(new IllegalStateException("collapse failed")))) {
      assertDoesNotThrow(() -> ReconciledPaymentReactivation.cleanUpStatementLine(LINE_ID));

      verify(conn).rollback(savepoint);
      verify(session).clear();
    }
  }

  /** Even a failing rollback must not escape finish. */
  @Test
  void testFinishDoesNotPropagateWhenSavepointRollbackFails() throws Exception {
    when(payment.isProcessed()).thenReturn(false);
    when(line.getFinancialAccountTransaction()).thenReturn(null);
    doThrow(new SQLException("rollback failed")).when(conn).rollback(any(Savepoint.class));

    try (MockedConstruction<ReconciliationHandler> handlers = mockConstruction(
        ReconciliationHandler.class,
        (mock, context) -> when(mock.normalizeReactivatedMatchGroup(any()))
            .thenThrow(new IllegalStateException("collapse failed")))) {
      assertDoesNotThrow(() -> ReconciledPaymentReactivation.finish(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, null)));
    }
  }

  /**
   * Reactivation did not go through (payment still processed and still reconciled): the status
   * prepare applied is undone, and the statement line is not touched.
   */
  @Test
  void testFinishRevertsAppliedStatusWhenReactivationDidNotGoThrough() throws Exception {
    when(payment.isProcessed()).thenReturn(true);
    when(payment.getStatus()).thenReturn(PWNC);
    givenReconciled(true);

    try (MockedConstruction<ReconciliationHandler> handlers =
        mockConstruction(ReconciliationHandler.class)) {
      ReconciledPaymentReactivation.finish(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, PWNC));

      verifyStatusWritten(RPPC);
      verify(line, never()).setMatchingtype(any());
      assertFalse(normalizedAnything(handlers));
    }
  }

  /** Nothing was applied by prepare: a failed reactivation leaves the status as it is. */
  @Test
  void testFinishLeavesStatusAloneWhenNothingWasApplied() {
    when(payment.isProcessed()).thenReturn(true);
    when(payment.getStatus()).thenReturn(RPPC);
    givenReconciled(true);

    ReconciledPaymentReactivation.finish(
        new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, null));

    verify(payment, never()).setStatus(anyString());
  }

  // ── finishAfterFailure ──────────────────────────────────────────────────────

  @Test
  void testFinishAfterFailureWithNullIsNoOp() {
    assertDoesNotThrow(() -> ReconciledPaymentReactivation.finishAfterFailure(null));

    verifyNoInteractions(dal);
  }

  /**
   * The delegated reactivation threw: the aborted transaction is rolled back, then the status
   * prepare applied is written back to RPPC, and the statement line is never cleaned.
   */
  @Test
  void testFinishAfterFailureRollsBackAndRevertsAppliedStatus() throws Exception {
    when(payment.isProcessed()).thenReturn(true);
    when(payment.getStatus()).thenReturn(PWNC);
    givenReconciled(true);

    try (MockedConstruction<ReconciliationHandler> handlers =
        mockConstruction(ReconciliationHandler.class)) {
      assertDoesNotThrow(() -> ReconciledPaymentReactivation.finishAfterFailure(
          new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, PWNC)));

      verifyStatusWritten(RPPC);
      // The aborted transaction must be discarded BEFORE the compensating UPDATE runs, or the
      // UPDATE would execute on (and be lost with) the aborted transaction.
      InOrder order = Mockito.inOrder(dal, statusPs);
      order.verify(dal).rollbackAndClose();
      order.verify(statusPs).executeUpdate();
      verify(line, never()).setMatchingtype(any());
      assertFalse(normalizedAnything(handlers));
    }
  }

  /** A failing rollbackAndClose is swallowed and the status revert still runs. */
  @Test
  void testFinishAfterFailureStillRevertsWhenRollbackAndCloseFails() throws Exception {
    doThrow(new IllegalStateException("rollback failed")).when(dal).rollbackAndClose();
    when(payment.isProcessed()).thenReturn(true);
    when(payment.getStatus()).thenReturn(PWNC);
    givenReconciled(true);

    assertDoesNotThrow(() -> ReconciledPaymentReactivation.finishAfterFailure(
        new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, PWNC)));

    verifyStatusWritten(RPPC);
  }

  /** Never throws, even when the status revert itself fails. */
  @Test
  void testFinishAfterFailureNeverThrowsWhenRevertFails() throws Exception {
    when(payment.isProcessed()).thenReturn(true);
    when(payment.getStatus()).thenReturn(PWNC);
    givenReconciled(true);
    when(statusPs.executeUpdate()).thenThrow(new SQLException("status update failed"));

    assertDoesNotThrow(() -> ReconciledPaymentReactivation.finishAfterFailure(
        new ReconciledPaymentReactivation.Prepared(PAYMENT_ID, LINE_ID, PWNC)));

    verify(conn).rollback(savepoint);
    verify(payment, never()).setStatus(anyString());
  }
}
