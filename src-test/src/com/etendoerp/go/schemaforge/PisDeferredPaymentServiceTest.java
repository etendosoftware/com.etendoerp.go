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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Covers the decision logic {@link PisDeferredPaymentService} adds for ETP-4895 — which Salt Edge
 * statuses produce a payment, how a rejected transfer is distinguished, and how the request
 * snapshot and the per-attempt bank reference are built.
 *
 * <p>These are the pieces that decide whether a payment is created at all, so they are exercised
 * directly rather than through the DAL-heavy entry points. Everything under test is pure: no
 * database, no Salt Edge. The private methods are reached by reflection, which is the only way to
 * pin this logic without widening the class's API purely for testing.
 */
class PisDeferredPaymentServiceTest {

  private static Object invokePrivate(String name, Class<?>[] types, Object... args)
      throws Exception {
    Method m = PisDeferredPaymentService.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  private static boolean requiresPayment(String status) throws Exception {
    return (boolean) invokePrivate("requiresPayment", new Class<?>[]{ String.class }, status);
  }

  @Test
  @DisplayName("is a static utility: every entry point is a hook, so there is nothing to instantiate")
  void isNotInstantiable() throws Exception {
    // Also what keeps this outer class a test class in its own right rather than a bare container
    // of @Nested suites, which Sonar reads as a test class with no tests (S2187).
    Constructor<PisDeferredPaymentService> ctor =
        PisDeferredPaymentService.class.getDeclaredConstructor();
    assertTrue(Modifier.isPrivate(ctor.getModifiers()));
  }

  @Nested
  @DisplayName("which statuses produce a payment")
  class RequiresPayment {

    @Test
    @DisplayName("a payment appears once the bank commits to the transfer")
    void committedStatusesProduceAPayment() throws Exception {
      assertTrue(requiresPayment("authorized"));
      assertTrue(requiresPayment("executed"));
      assertTrue(requiresPayment("settled"));
    }

    @Test
    @DisplayName("a rejected transfer produces no payment at all")
    void rejectedProducesNothing() throws Exception {
      // The money never moved, so recording the attempt would leave a row to clean up for
      // something that never happened. The user is told and simply tries again.
      assertFalse(requiresPayment("failed"));
      assertFalse(requiresPayment("FAILED"));
    }

    @Test
    @DisplayName("no payment exists while the transfer is still in flight")
    void intermediateStatusesProduceNothing() throws Exception {
      // Up to this point the user may still abandon the bank window, so nothing must be left
      // behind on the invoice. 'initiated_info_required' is the status that caused the original
      // bug: it is a perfectly normal in-flight state, not a failure.
      assertFalse(requiresPayment("requested"));
      assertFalse(requiresPayment("initiated"));
      assertFalse(requiresPayment("initiated_info_required"));
      assertFalse(requiresPayment("authorizing"));
    }

    @Test
    @DisplayName("an unknown status is treated as still in flight, never as resolved")
    void unknownStatusProducesNothing() throws Exception {
      // Defaulting an unrecognized status to "resolved" would register a payment for a transfer
      // that may never happen. Erring towards "not yet" is the safe direction.
      assertFalse(requiresPayment("some_future_saltedge_status"));
      assertFalse(requiresPayment(""));
      assertFalse(requiresPayment(null));
    }

    @Test
    @DisplayName("status matching ignores case")
    void statusMatchingIsCaseInsensitive() throws Exception {
      assertTrue(requiresPayment("AUTHORIZED"));
      assertTrue(requiresPayment("Executed"));
    }
  }

  @Nested
  @DisplayName("a transfer the bank refuses after committing to it")
  class RejectedAfterCommit {

    /** Drives reconcile() with OBDal stubbed, which is all markPaymentAsFailed touches. */
    private void reconcileWith(PisPayment pisPayment) {
      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        OBDal instance = mock(OBDal.class);
        dal.when(OBDal::getInstance).thenReturn(instance);
        PisDeferredPaymentService.reconcile(pisPayment);
      }
    }

    @Test
    @DisplayName("flags the payment as ETGOERR instead of leaving it reading as in progress")
    void rejectionAfterAuthorizationFlagsThePayment() {
      // The rejection arrives once a payment already exists — created at 'authorized'. Without
      // this the payment stays in PPM and every surface keeps showing "Pago en progreso" for a
      // transfer the bank has definitively refused (ETP-4895).
      PisPayment pisPayment = mock(PisPayment.class);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(pisPayment.getStatus()).thenReturn("failed");
      when(pisPayment.getPayment()).thenReturn(payment);
      when(payment.getStatus()).thenReturn("PPM");
      // Processed and untouched since the transfer was rejected — the rejection still describes it.
      // A payment that is not processed has been reactivated, and isStaleAttempt skips it.
      when(payment.isProcessed()).thenReturn(true);

      reconcileWith(pisPayment);

      verify(payment).setStatus("ETGOERR");
    }

    @Test
    @DisplayName("does not reactivate it: the retry reuses this very payment")
    void doesNotReactivateTheFlaggedPayment() {
      // Keeping it processed keeps it holding the invoice's installment and any credit it
      // consumed, which is what lets the retry reuse it rather than register a second payment.
      PisPayment pisPayment = mock(PisPayment.class);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(pisPayment.getStatus()).thenReturn("failed");
      when(pisPayment.getPayment()).thenReturn(payment);
      when(payment.getStatus()).thenReturn("PPM");
      // Processed, so the flag really is applied here — otherwise this would pass for the wrong
      // reason, with isStaleAttempt skipping the payment before anything touched it.
      when(payment.isProcessed()).thenReturn(true);

      reconcileWith(pisPayment);

      verify(payment, never()).setProcessed(any(Boolean.class));
    }

    @Test
    @DisplayName("a rejection before the bank committed still creates nothing")
    void rejectionBeforeAuthorizationCreatesNothing() {
      // The other rejection: no payment was ever created, so there is nothing to flag. Reported
      // in the modal instead, with the form left ready to try again.
      PisPayment pisPayment = mock(PisPayment.class);
      when(pisPayment.getStatus()).thenReturn("failed");
      when(pisPayment.getPayment()).thenReturn(null);

      assertFalse(PisDeferredPaymentService.reconcile(pisPayment));
    }

    @Test
    @DisplayName("re-running on an already flagged payment changes nothing")
    void flaggingIsIdempotent() {
      // Several paths reconcile the same row — the SPA poll and the periodic sweep — so a second
      // pass must not rewrite the status or emit a second save.
      PisPayment pisPayment = mock(PisPayment.class);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(pisPayment.getStatus()).thenReturn("failed");
      when(pisPayment.getPayment()).thenReturn(payment);
      when(payment.getStatus()).thenReturn("ETGOERR");

      reconcileWith(pisPayment);

      verify(payment, never()).setStatus(any(String.class));
    }
  }

  @Nested
  @DisplayName("what an invoice reports about its payments")
  class InvoiceTransferState {

    @Test
    @DisplayName("an empty page asks the database nothing")
    void emptyPageSkipsTheQuery() {
      assertTrue(PisDeferredPaymentService.transferStateByInvoice(null).isEmpty());
      assertTrue(PisDeferredPaymentService.transferStateByInvoice(List.of()).isEmpty());
    }

    @Test
    @DisplayName("the two readings an invoice can carry are the payments' own states")
    void statesMirrorThePaymentBadges() {
      // Pinned so the invoice badge and the payment badge can never drift into disagreeing about
      // the same fact — the defect this whole enrichment exists to remove.
      assertEquals("error", PisDeferredPaymentService.INVOICE_TRANSFER_ERROR);
      assertEquals("inProgress", PisDeferredPaymentService.INVOICE_TRANSFER_IN_PROGRESS);
    }
  }

  @Nested
  @DisplayName("who owns a payment's lifecycle")
  class LifecycleLock {

    @Test
    @DisplayName("a live bank transfer owns its payment: no reactivate, no delete")
    void liveTransferLocksThePayment() {
      // Reactivating or deleting behind the bank's back would leave Salt Edge holding an order for a
      // payment that no longer exists — and once executed, money that moved with nothing recording
      // it. Locked for every state the transfer can still be in (ETP-4895).
      assertTrue(PisDeferredPaymentService.isLifecycleLockedByTransfer("PPM", true));
      assertTrue(PisDeferredPaymentService.isLifecycleLockedByTransfer("PWNC", true));
      assertTrue(PisDeferredPaymentService.isLifecycleLockedByTransfer("RPAP", true));
    }

    @Test
    @DisplayName("a rejected transfer hands the payment back")
    void rejectedTransferUnlocksThePayment() {
      // ETGOERR means the bank refused it: no money moved and nothing is in flight, so the payment
      // is the user's to retry or discard. This is the whole exception to the rule.
      assertFalse(PisDeferredPaymentService.isLifecycleLockedByTransfer("ETGOERR", true));
    }

    @Test
    @DisplayName("a payment that never went through PIS is never locked")
    void nonPisPaymentsAreNeverLocked() {
      // What keeps cash and manual transfers behaving exactly as before.
      assertFalse(PisDeferredPaymentService.isLifecycleLockedByTransfer("PPM", false));
      assertFalse(PisDeferredPaymentService.isLifecycleLockedByTransfer("PWNC", false));
      assertFalse(PisDeferredPaymentService.isLifecycleLockedByTransfer(null, false));
    }

    @Test
    @DisplayName("an empty batch asks the database nothing")
    void emptyBatchSkipsTheQuery() {
      assertTrue(PisDeferredPaymentService.paymentsWithBankTransfer(null).isEmpty());
      assertTrue(PisDeferredPaymentService.paymentsWithBankTransfer(List.of()).isEmpty());
    }
  }

  @Nested
  @DisplayName("reconciling when a screen is opened")
  class ReconcileOnRead {

    @Test
    @DisplayName("flags a transfer the bank refused after the payment modal had closed")
    void resolvesWhatThePollNeverSaw() {
      // reconcile()'s only other caller is the SPA poll, which stops the moment the modal closes.
      // A rejection arriving later is recorded by the PSD2 refresh, which knows nothing about our
      // payment — so without this hook the payment sits in PPM forever, reading as in progress.
      FIN_Payment payment = mock(FIN_Payment.class);
      PisPayment attempt = mock(PisPayment.class);
      when(attempt.getStatus()).thenReturn("failed");
      when(attempt.getPayment()).thenReturn(payment);
      when(payment.getStatus()).thenReturn("PPM");
      // Processed, and untouched since the transfer was rejected: the rejection still describes it.
      when(payment.isProcessed()).thenReturn(true);
      when(payment.getUpdated()).thenReturn(new Date(1000L));
      when(attempt.getLastStatusAt()).thenReturn(new Date(2000L));

      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        OBDal instance = mock(OBDal.class);
        @SuppressWarnings("unchecked")
        OBCriteria<PisPayment> crit = mock(OBCriteria.class);
        dal.when(OBDal::getInstance).thenReturn(instance);
        doReturn(crit).when(instance).createCriteria(PisPayment.class);
        when(crit.add(any())).thenReturn(crit);
        doReturn(List.of(attempt)).when(crit).list();

        PisDeferredPaymentService.reconcileAttemptsFor(payment);
      }

      verify(payment).setStatus("ETGOERR");
    }

    @Test
    @DisplayName("a payment with no bank transfer behind it is left alone")
    void ignoresNonPisPayments() {
      PisDeferredPaymentService.reconcileAttemptsFor(null);
      // No exception, no DB access: the vast majority of payments never went through PIS.
    }

    @Test
    @DisplayName("a screen still opens when reconciliation fails")
    void neverThrows() {
      // Swallowing is deliberate: this runs on a read path, and a reconciliation problem must not
      // take the invoice or the payment window down with it.
      FIN_Payment payment = mock(FIN_Payment.class);
      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        dal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));
        PisDeferredPaymentService.reconcileAttemptsFor(payment);
      }
    }
  }

  @Nested
  @DisplayName("a rejection stops describing the payment once either has moved on")
  class StaleAttempts {

    /** Mocks OBDal so the newer-attempt probe returns {@code newer}. */
    private boolean isSuperseded(PisPayment attempt, List<PisPayment> newer) {
      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        OBDal instance = mock(OBDal.class);
        @SuppressWarnings("unchecked")
        OBCriteria<PisPayment> crit = mock(OBCriteria.class);
        dal.when(OBDal::getInstance).thenReturn(instance);
        doReturn(crit).when(instance).createCriteria(PisPayment.class);
        when(crit.add(any())).thenReturn(crit);
        doReturn(newer).when(crit).list();
        return PisDeferredPaymentService.isSupersededByNewerAttempt(attempt);
      }
    }

    private PisPayment rejectedAttempt(FIN_Payment payment) {
      PisPayment attempt = mock(PisPayment.class);
      when(attempt.getStatus()).thenReturn("failed");
      when(attempt.getPayment()).thenReturn(payment);
      when(attempt.getCreationDate()).thenReturn(new Date());
      return attempt;
    }

    @Test
    @DisplayName("a rejected attempt with a newer one behind it no longer speaks for the payment")
    void newerAttemptWins() {
      PisPayment attempt = rejectedAttempt(mock(FIN_Payment.class));
      assertTrue(isSuperseded(attempt, List.of(mock(PisPayment.class))));
    }

    @Test
    @DisplayName("the latest attempt is the one that flags the payment")
    void lastAttemptIsNotSuperseded() {
      PisPayment attempt = rejectedAttempt(mock(FIN_Payment.class));
      assertFalse(isSuperseded(attempt, List.of()));
    }

    @Test
    @DisplayName("an attempt that never produced a payment has nothing to supersede")
    void noPaymentIsNeverSuperseded() {
      PisPayment attempt = mock(PisPayment.class);
      when(attempt.getPayment()).thenReturn(null);
      // No OBDal mock on purpose: resolving this must not reach the database at all.
      assertFalse(PisDeferredPaymentService.isSupersededByNewerAttempt(attempt));
    }

    @Test
    @DisplayName("without a creation date the attempts cannot be ordered, so none is suppressed")
    void undatedAttemptIsNotSuperseded() {
      PisPayment attempt = mock(PisPayment.class);
      when(attempt.getPayment()).thenReturn(mock(FIN_Payment.class));
      when(attempt.getCreationDate()).thenReturn(null);
      assertFalse(PisDeferredPaymentService.isSupersededByNewerAttempt(attempt));
    }

    @Test
    @DisplayName("opening a screen does not undo a retry that is already in flight")
    void doesNotReflagWhileARetryRuns() {
      // The regression this guard exists for: retryReusingPayment puts the payment back in PPM and
      // leaves the rejected row as the audit trail, but reconcileAttemptsFor walks EVERY attempt —
      // so the old failure flagged the payment as errored again on the next window load.
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.getStatus()).thenReturn("PPM");
      PisPayment rejected = rejectedAttempt(payment);
      PisPayment inFlight = mock(PisPayment.class);
      when(inFlight.getStatus()).thenReturn("requested");

      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        OBDal instance = mock(OBDal.class);
        @SuppressWarnings("unchecked")
        OBCriteria<PisPayment> attempts = mock(OBCriteria.class);
        @SuppressWarnings("unchecked")
        OBCriteria<PisPayment> probe = mock(OBCriteria.class);
        dal.when(OBDal::getInstance).thenReturn(instance);
        // First criteria: the attempts of the payment. Second: the newer-attempt probe.
        doReturn(attempts).doReturn(probe).when(instance).createCriteria(PisPayment.class);
        when(attempts.add(any())).thenReturn(attempts);
        when(probe.add(any())).thenReturn(probe);
        doReturn(List.of(rejected, inFlight)).when(attempts).list();
        doReturn(List.of(inFlight)).when(probe).list();

        PisDeferredPaymentService.reconcileAttemptsFor(payment);
      }

      verify(payment, never()).setStatus("ETGOERR");
    }

    /** Builds a payment/attempt pair and returns whether the rejection still applies. */
    private boolean isStale(boolean processed, long paymentUpdatedMs, long attemptStatusMs) {
      FIN_Payment payment = mock(FIN_Payment.class);
      when(payment.isProcessed()).thenReturn(processed);
      when(payment.getUpdated()).thenReturn(new Date(paymentUpdatedMs));
      PisPayment attempt = mock(PisPayment.class);
      when(attempt.getPayment()).thenReturn(payment);
      when(attempt.getCreationDate()).thenReturn(new Date(attemptStatusMs));
      when(attempt.getLastStatusAt()).thenReturn(new Date(attemptStatusMs));
      try (MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        OBDal instance = mock(OBDal.class);
        @SuppressWarnings("unchecked")
        OBCriteria<PisPayment> crit = mock(OBCriteria.class);
        dal.when(OBDal::getInstance).thenReturn(instance);
        doReturn(crit).when(instance).createCriteria(PisPayment.class);
        when(crit.add(any())).thenReturn(crit);
        doReturn(List.of()).when(crit).list();
        return PisDeferredPaymentService.isStaleAttempt(attempt, payment);
      }
    }

    @Test
    @DisplayName("a payment the user took back to draft is not a payment whose transfer failed")
    void reactivatedPaymentIsNotReflagged() {
      // The regression the reactivate flow hit: reactivating cleared `processed`, and the next
      // window load walked the attempts and dragged the payment back to ETGOERR — leaving it half
      // draft and half errored, with the invoice still reading as paid.
      assertTrue(isStale(false, 2000L, 1000L));
    }

    @Test
    @DisplayName("a payment confirmed again after the rejection is no longer described by it")
    void paymentTouchedAfterTheAttemptIsNotReflagged() {
      // Reactivated and confirmed again — possibly by another method entirely. Whatever the payment
      // is now, this rejection did not produce it.
      assertTrue(isStale(true, 2000L, 1000L));
    }

    @Test
    @DisplayName("an untouched processed payment still takes the flag")
    void untouchedPaymentIsFlagged() {
      assertFalse(isStale(true, 1000L, 2000L));
    }
  }

  @Nested
  @DisplayName("failed vs settled classification")
  class StatusClassification {

    private boolean isFailed(String status) throws Exception {
      return (boolean) invokePrivate("isFailedStatus", new Class<?>[]{ String.class }, status);
    }

    private boolean isSettled(String status) throws Exception {
      return (boolean) invokePrivate("isSettledStatus", new Class<?>[]{ String.class }, status);
    }

    @Test
    @DisplayName("only 'failed' counts as a rejection")
    void onlyFailedIsAFailure() throws Exception {
      assertTrue(isFailed("failed"));
      assertTrue(isFailed("FAILED"));
      assertFalse(isFailed("authorized"));
      assertFalse(isFailed("initiated_info_required"));
      assertFalse(isFailed(null));
    }

    @Test
    @DisplayName("only executed/settled book the bank transaction")
    void onlySettledStatusesBookTheTransaction() throws Exception {
      // 'authorized' creates the payment but the money has not landed, so no financial
      // transaction may be created for it yet.
      assertTrue(isSettled("executed"));
      assertTrue(isSettled("settled"));
      assertFalse(isSettled("authorized"));
      assertFalse(isSettled("failed"));
    }
  }

  @Nested
  @DisplayName("per-attempt bank reference")
  class EndToEndId {

    /** Runs the real nextEndToEndId with the DAL stubbed to report {@code existingAttempts}. */
    @SuppressWarnings("unchecked")
    private String buildReference(String documentNo, int existingAttempts) throws Exception {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn(documentNo);
      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal obDal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(obDal);
        OBCriteria<PisPayment> crit = mock(OBCriteria.class);
        when(obDal.createCriteria(PisPayment.class)).thenReturn(crit);
        when(crit.add(any())).thenReturn(crit);
        when(crit.count()).thenReturn(existingAttempts);
        return (String) invokePrivate("nextEndToEndId", new Class<?>[]{ Invoice.class }, invoice);
      }
    }

    @Test
    @DisplayName("a retry never reuses the previous reference")
    void retryGetsADistinctReference() throws Exception {
      // End-to-end ids must be unique per debtor account: resubmitting the same one risks a
      // silent rejection at the bank or a false "already processed" match.
      String first = buildReference("10000236", 0);
      String second = buildReference("10000236", 1);
      assertEquals("10000236-1", first);
      assertEquals("10000236-2", second);
    }

    @Test
    @DisplayName("a long document number is truncated to the 35-char limit")
    void longDocumentNumberIsTruncated() throws Exception {
      // GenerateBankPayment rejects anything longer, so the suffix must survive truncation.
      String reference = buildReference("A".repeat(60), 0);
      assertEquals(35, reference.length());
      assertTrue(reference.endsWith("-1"));
    }
  }

  @Nested
  @DisplayName("request snapshot")
  class Intent {

    @Test
    @DisplayName("keeps the invoice, the direction and the original request verbatim")
    void snapshotCarriesWhatTheReplayNeeds() throws Exception {
      // The snapshot is what rebuilds the payment minutes later, when neither the modal nor the
      // HTTP request exist any more. Losing any of it means not knowing what to pay.
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("INV-1");
      JSONObject body = new JSONObject();
      body.put("scheduleId", "SCH-1");
      body.put("actual_payment", "24.20");
      body.put("fin_paymentmethod_id", "PM-1");

      JSONObject intent = (JSONObject) invokePrivate("buildIntent",
          new Class<?>[]{ Invoice.class, JSONObject.class, boolean.class }, invoice, body, false);

      assertEquals("INV-1", intent.getString("invoiceId"));
      assertFalse(intent.getBoolean("isReceipt"));
      JSONObject stored = intent.getJSONObject("body");
      assertEquals("SCH-1", stored.getString("scheduleId"));
      assertEquals("24.20", stored.getString("actual_payment"));
      assertEquals("PM-1", stored.getString("fin_paymentmethod_id"));
    }

    @Test
    @DisplayName("keeps the receipt direction for a sales invoice")
    void snapshotKeepsDirection() throws Exception {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("INV-2");

      JSONObject intent = (JSONObject) invokePrivate("buildIntent",
          new Class<?>[]{ Invoice.class, JSONObject.class, boolean.class },
          invoice, new JSONObject(), true);

      assertTrue(intent.getBoolean("isReceipt"));
    }
  }

  @Nested
  @DisplayName("reading the replayed payment id")
  class ExtractPaymentId {

    private String extract(NeoResponse response) throws Exception {
      return (String) invokePrivate("extractPaymentId", new Class<?>[]{ NeoResponse.class },
          response);
    }

    @Test
    @DisplayName("digs the id out of the response envelope")
    void readsTheIdFromTheEnvelope() throws Exception {
      JSONObject data = new JSONObject();
      data.put("id", "PAY-1");
      JSONObject inner = new JSONObject();
      inner.put("data", data);
      JSONObject body = new JSONObject();
      body.put("response", inner);

      assertEquals("PAY-1", extract(new NeoResponse(201, body)));
    }

    @Test
    @DisplayName("returns null instead of throwing on an unexpected envelope")
    void toleratesAMalformedEnvelope() throws Exception {
      // A null id is handled by the caller as "the replay produced nothing", which is logged and
      // retried later — far better than an exception aborting the status refresh.
      assertNull(extract(null));
      assertNull(extract(new NeoResponse(500, null)));
      assertNull(extract(new NeoResponse(200, new JSONObject())));
    }
  }
}
