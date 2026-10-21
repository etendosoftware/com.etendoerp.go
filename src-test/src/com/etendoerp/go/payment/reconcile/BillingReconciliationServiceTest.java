/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.CheckoutRequestWrites;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.rest.PaidProvisioningService;
import com.etendoerp.go.rest.PaidProvisioningService.Outcome;
import com.etendoerp.go.rest.PaidProvisioningService.Status;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Specs for {@link BillingReconciliationService}: the checkout phases (recovery, provisioning
 * repair, no-session-id review), the stuck-event sweep, the dry run and the idempotent re-run.
 * Phase 4 (subscriptions) is {@link SubscriptionReconciler}'s and is mocked here; its own specs
 * live in {@code SubscriptionReconcilerTest}.
 *
 * <p>Everything the service decides is visible at its collaborators — the checkout store, the
 * checkout writes, the billing-event store and the paid provisioning service — plus its
 * {@code [BILLING-RECONCILE]} log lines, so the database stays mocked: the HQL that selects the
 * candidate rows is answered per phase, and the time windows are decided against a fixed clock.
 *
 * @covers com.etendoerp.go.payment.reconcile.BillingReconciliationService
 */
public class BillingReconciliationServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
  private static final String REQ = "req-1";
  private static final String SESSION = "cs_test_session1";

  private final List<CheckoutRequest> createdRows = new ArrayList<>();
  private final List<CheckoutRequest> paidRows = new ArrayList<>();
  private final List<CheckoutRequest> creatingRows = new ArrayList<>();
  private final List<BillingEventStore.StaleEvent> staleEvents = new ArrayList<>();

  private StripeApiClient stripe;
  private CheckoutRequestStore checkoutStore;
  private BillingEventStore eventStore;
  private PaidProvisioningService provisioning;
  private SubscriptionReconciler subscriptions;
  private CheckoutRequestWrites checkoutWrites;
  private ReconciliationSettings settings = ReconciliationSettings.defaults();
  private TestLogCapture auditLog;

  @Before
  public void setUp() {
    stripe = mock(StripeApiClient.class);
    checkoutStore = mock(CheckoutRequestStore.class);
    eventStore = mock(BillingEventStore.class);
    provisioning = mock(PaidProvisioningService.class);
    subscriptions = mock(SubscriptionReconciler.class);
    checkoutWrites = mock(CheckoutRequestWrites.class);
    when(eventStore.findStaleReceived(any(), anyInt())).thenReturn(staleEvents);
    when(eventStore.recordReconciliation(anyString(), anyString(), any(), anyString(), any(),
        any())).thenReturn(true);
    auditLog = TestLogCapture.attachTo(ReconciliationAudit.class, Level.INFO);
  }

  @After
  public void tearDown() {
    auditLog.detach();
  }

  // ── harness ───────────────────────────────────────────────────────────────────────────────

  private ReconciliationResult run(boolean dryRun) {
    OBDal dal = mock(OBDal.class);
    when(dal.createQuery(eq(CheckoutRequest.class), anyString())).thenAnswer(call -> {
      String hql = call.getArgument(1, String.class);
      List<CheckoutRequest> rows = hql.contains(":created") ? createdRows
          : hql.contains(":paid") ? paidRows : hql.contains(":creating") ? creatingRows
              : List.of();
      @SuppressWarnings("unchecked")
      OBQuery<CheckoutRequest> query = mock(OBQuery.class, RETURNS_SELF);
      when(query.list()).thenReturn(new ArrayList<>(rows));
      return query;
    });
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      BillingReconciliationService service = new BillingReconciliationService(settings, stripe,
          checkoutStore, eventStore, provisioning, subscriptions,
          Clock.fixed(NOW, ZoneOffset.UTC));
      service.checkoutWrites = checkoutWrites;
      return service.run(dryRun);
    }
  }

  private static Date ago(long amount, TimeUnit unit) {
    return Date.from(NOW.minusMillis(unit.toMillis(amount)));
  }

  private CheckoutRequest row(String requestId, String sessionId, Date createdAt, String derived) {
    CheckoutRequest request = mock(CheckoutRequest.class);
    when(request.getRequest()).thenReturn(requestId);
    when(request.getStripeSession()).thenReturn(sessionId);
    when(request.getCreatedAt()).thenReturn(createdAt);
    when(checkoutStore.deriveProvisioningStatus(request)).thenReturn(derived);
    when(checkoutStore.isProvisioningRetryAllowed(request)).thenReturn(true);
    return request;
  }

  private CheckoutRequest paidRow(String requestId, Date paidAt, long attempts, String derived) {
    CheckoutRequest request = row(requestId, SESSION, ago(2, TimeUnit.HOURS), derived);
    when(request.getPaidAt()).thenReturn(paidAt);
    when(request.getProvisioningAttempts()).thenReturn(attempts);
    return request;
  }

  private void session(String sessionId, String status, String paymentStatus, String requestId)
      throws Exception {
    when(stripe.get("/v1/checkout/sessions/" + sessionId)).thenReturn(new StripeResponse(200,
        "{\"id\":\"" + sessionId + "\",\"status\":\"" + status + "\",\"payment_status\":\""
            + paymentStatus + "\",\"customer\":\"cus_1\",\"subscription\":\"sub_1\","
            + "\"metadata\":{\"request_id\":\"" + requestId + "\"}}"));
  }

  private void verifyRow(String eventId, String type, String result) {
    verify(eventStore).recordReconciliation(eq(eventId), eq(type), eq(REQ), eq(result), any(),
        any());
  }

  private void verifyNoRows() {
    verify(eventStore, never()).recordReconciliation(anyString(), anyString(), any(), any(),
        any(), any());
  }

  private void verifyNothingProvisionedOrWritten() {
    verify(checkoutStore, never()).recordPaid(anyString(), any(), any());
    verify(checkoutWrites, never()).recordExpired(anyString());
    verify(provisioning, never()).provision(anyString(), any());
    verify(eventStore, never()).markIgnored(anyString(), anyString());
    verifyNoRows();
  }

  private List<String> warnings() {
    return auditLog.messagesAt(Level.WARN);
  }

  // ── Phase 1 + 2: a paid checkout with no tenant is recovered and provisioned ────────────────

  @Test
  public void aPaidSessionTheWebhookLostIsRecordedThenProvisionedAuditedAndAlerted()
      throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "paid", REQ);
    // recordPaid moved it to PAID; it was paid a minute ago, inside the browser's grace, but a
    // payment recovered in this very run is provisioned at once.
    paidRows.add(paidRow(REQ, ago(1, TimeUnit.MINUTES), 0L, "paid"));
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 1L));

    ReconciliationResult result = run(false);

    verify(checkoutStore).recordPaid(REQ, "cus_1", "sub_1");
    verifyRow("reconcile:payment-recovered:" + REQ, "reconciliation.payment-recovered",
        BillingEventStore.RESULT_APPLIED);
    verify(provisioning).provision(eq(REQ), any());
    verifyRow("reconcile:provisioned:" + REQ, "reconciliation.provisioned",
        BillingEventStore.RESULT_APPLIED);
    assertEquals(1, result.get(Counter.PAYMENTS_RECOVERED));
    assertEquals(1, result.get(Counter.PROVISIONED));
    assertTrue(warnings().toString(), warnings().stream().anyMatch(line -> line.startsWith(
        "[BILLING-RECONCILE] Provisioned a paid purchase with no environment: request "
            + REQ)));
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  public void aNoPaymentRequiredSessionIsRecoveredToo() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "no_payment_required", REQ);

    ReconciliationResult result = run(false);

    verify(checkoutStore).recordPaid(REQ, "cus_1", "sub_1");
    assertEquals(1, result.get(Counter.PAYMENTS_RECOVERED));
  }

  @Test
  public void aSessionNamingAnotherRequestIsAnErrorAndNothingIsWritten() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "paid", "someone-else");

    ReconciliationResult result = run(false);

    verifyNothingProvisionedOrWritten();
    assertEquals(List.of("checkout " + REQ + ": its provider session names another request"),
        result.getErrors());
  }

  @Test
  public void aFailedProviderReadIsCountedAndChangesNothing() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    when(stripe.get("/v1/checkout/sessions/" + SESSION)).thenReturn(new StripeResponse(503, ""));

    ReconciliationResult result = run(false);

    verifyNothingProvisionedOrWritten();
    assertEquals(1, result.get(Counter.PROVIDER_ERRORS));
  }

  @Test
  public void aPaidRowInsideTheGraceIsLeftToTheBrowser() {
    paidRows.add(paidRow(REQ, ago(5, TimeUnit.MINUTES), 0L, "paid"));

    run(false);

    verify(provisioning, never()).provision(anyString(), any());
    verifyNoRows();
  }

  @Test
  public void aPaidRowPastTheGraceIsProvisionedUnderTheClaim() {
    paidRows.add(paidRow(REQ, ago(20, TimeUnit.MINUTES), 0L, "paid"));
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 1L));

    ReconciliationResult result = run(false);

    verify(provisioning).provision(eq(REQ), any());
    assertEquals(1, result.get(Counter.PROVISIONED));
  }

  // ── Phase 1: abandoned checkouts ─────────────────────────────────────────────────────────

  @Test
  public void anExpiredSessionPastTheAbandonedWindowClosesTheRow() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(49, TimeUnit.HOURS), "pending"));
    session(SESSION, "expired", "unpaid", REQ);
    when(checkoutWrites.recordExpired(REQ)).thenReturn(true);

    ReconciliationResult result = run(false);

    verify(checkoutWrites).recordExpired(REQ);
    verifyRow("reconcile:checkout-expired:" + REQ, "reconciliation.checkout-expired",
        BillingEventStore.RESULT_APPLIED);
    assertEquals(1, result.get(Counter.CHECKOUTS_EXPIRED));
  }

  @Test
  public void anExpiredSessionInsideTheAbandonedWindowIsLeftAlone() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(47, TimeUnit.HOURS), "pending"));
    session(SESSION, "expired", "unpaid", REQ);

    run(false);

    verifyNothingProvisionedOrWritten();
  }

  @Test
  public void theFortyEightHourMinimumHoldsEvenWhenALowerWindowIsConfigured() throws Exception {
    settings = new ReconciliationSettings(60, 50, 12, 15, 60, 0.25d, 5);
    createdRows.add(row(REQ, SESSION, ago(13, TimeUnit.HOURS), "pending"));
    session(SESSION, "expired", "unpaid", REQ);

    run(false);

    verify(checkoutWrites, never()).recordExpired(anyString());
  }

  @Test
  public void anOpenSessionLeavesTheRowUntouchedHoweverOldItIs() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(30, TimeUnit.DAYS), "pending"));
    session(SESSION, "open", "unpaid", REQ);

    ReconciliationResult result = run(false);

    verifyNothingProvisionedOrWritten();
    assertEquals(1, result.get(Counter.CHECKOUTS_CHECKED));
  }

  @Test
  public void aPaymentThatRacedTheCloseWinsAndNothingIsAudited() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(49, TimeUnit.HOURS), "pending"));
    session(SESSION, "expired", "unpaid", REQ);
    when(checkoutWrites.recordExpired(REQ)).thenReturn(false);

    ReconciliationResult result = run(false);

    verifyNoRows();
    assertEquals(0, result.get(Counter.CHECKOUTS_EXPIRED));
  }

  // ── Phase 2: the attempt cap and the non-retryable failures ──────────────────────────────

  @Test
  public void afterFiveAttemptsTheRowGoesToReviewAndIsNotRetried() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 5L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));

    ReconciliationResult result = run(false);

    verify(provisioning, never()).provision(anyString(), any());
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(eventStore).recordReconciliation(eq("reconcile:needs-review:" + REQ),
        eq("reconciliation.needs-review"), eq(REQ), eq(BillingEventStore.RESULT_FAILED),
        reason.capture(), any());
    assertTrue(reason.getValue(), reason.getValue().contains("ATTEMPTS_EXHAUSTED"));
    assertEquals(1, result.get(Counter.NEEDS_REVIEW));
  }

  @Test
  public void aRowAlreadyInReviewIsCountedAsAwaitingAndNeitherRetriedNorReAlerted() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 5L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(eventStore.exists("reconcile:needs-review:" + REQ)).thenReturn(true);

    ReconciliationResult result = run(false);

    verify(provisioning, never()).provision(anyString(), any());
    verifyNoRows();
    assertEquals(1, result.get(Counter.AWAITING_REVIEW));
    assertTrue(warnings().toString(), warnings().isEmpty());
  }

  @Test
  public void fourAttemptsAreStillRetried() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 4L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 5L));

    run(false);

    verify(provisioning).provision(eq(REQ), any());
  }

  @Test
  public void aUniqueViolationGoesStraightToReview() {
    for (String reason : new String[] {
        "ERROR: duplicate key value violates unique constraint \"ad_client_name\"",
        "SQLSTATE 23505 on AD_CLIENT" }) {
      paidRows.clear();
      eventStore = mock(BillingEventStore.class);
      when(eventStore.findStaleReceived(any(), anyInt())).thenReturn(staleEvents);
      CheckoutRequest request = paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
          CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED);
      when(request.getFailureReason()).thenReturn(reason);
      paidRows.add(request);

      run(false);

      verify(provisioning, never()).provision(anyString(), any());
      ArgumentCaptor<String> why = ArgumentCaptor.forClass(String.class);
      verify(eventStore).recordReconciliation(eq("reconcile:needs-review:" + REQ), anyString(),
          eq(REQ), eq(BillingEventStore.RESULT_FAILED), why.capture(), any());
      assertTrue(why.getValue(), why.getValue().contains("DETERMINISTIC_FAILURE"));
    }
  }

  @Test
  public void aFailureTheStoreSaysCannotBeRetriedGoesToReview() {
    CheckoutRequest request = paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED);
    when(checkoutStore.isProvisioningRetryAllowed(request)).thenReturn(false);
    paidRows.add(request);

    ReconciliationResult result = run(false);

    verify(provisioning, never()).provision(anyString(), any());
    verifyRow("reconcile:needs-review:" + REQ, "reconciliation.needs-review",
        BillingEventStore.RESULT_FAILED);
    assertEquals(1, result.get(Counter.NEEDS_REVIEW));
  }

  @Test
  public void aRetryableFailureIsRetried() {
    CheckoutRequest request = paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED);
    when(request.getFailureReason()).thenReturn("TIMEOUT: dataset load timed out");
    paidRows.add(request);
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.FAILED, "TIMEOUT", "timed out", 2L));

    ReconciliationResult result = run(false);

    verify(provisioning).provision(eq(REQ), any());
    verifyRow("reconcile:provisioning-failed:" + REQ + ":2", "reconciliation.provisioning-failed",
        BillingEventStore.RESULT_FAILED);
    assertEquals(1, result.get(Counter.PROVISIONING_FAILED));
  }

  @Test
  public void anAttemptHoldingALiveLeaseIsLeftAlone() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 1L, "provisioning"));

    run(false);

    verify(provisioning, never()).provision(anyString(), any());
    verifyNoRows();
  }

  @Test
  public void aRefusedClaimRecordsNothing() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.NOT_CLAIMED, null, "Purchase is provisioning", null));

    ReconciliationResult result = run(false);

    verifyNoRows();
    assertEquals(0, result.repairs());
  }

  @Test
  public void anOutcomeNeedingReviewIsRecordedForReview() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(provisioning.provision(eq(REQ), any())).thenReturn(
        new Outcome(Status.NEEDS_REVIEW, "UNKNOWN_CURRENCY", "No currency with ISO code XXX",
            null));

    ReconciliationResult result = run(false);

    verifyRow("reconcile:needs-review:" + REQ, "reconciliation.needs-review",
        BillingEventStore.RESULT_FAILED);
    assertEquals(1, result.get(Counter.NEEDS_REVIEW));
  }

  // ── Phase 3: a checkout with no session id is never closed ───────────────────────────────

  @Test
  public void aCheckoutWithoutASessionIdIsAlertedOnceAndNeverClosed() {
    creatingRows.add(row(REQ, null, ago(3, TimeUnit.DAYS), "pending"));

    ReconciliationResult first = run(false);

    ArgumentCaptor<String> why = ArgumentCaptor.forClass(String.class);
    verify(eventStore).recordReconciliation(eq("reconcile:needs-review:" + REQ),
        eq("reconciliation.needs-review"), eq(REQ), eq(BillingEventStore.RESULT_FAILED),
        why.capture(), any());
    assertTrue(why.getValue(), why.getValue().contains("NO_SESSION_ID"));
    assertEquals(1, first.get(Counter.NEEDS_REVIEW));
    assertEquals(1, warnings().size());
    verify(checkoutWrites, never()).recordExpired(anyString());

    // Second run: the deterministic id is found, so it is awaiting review and stays silent.
    eventStore = mock(BillingEventStore.class);
    when(eventStore.findStaleReceived(any(), anyInt())).thenReturn(staleEvents);
    when(eventStore.exists("reconcile:needs-review:" + REQ)).thenReturn(true);

    ReconciliationResult second = run(false);

    verifyNoRows();
    assertEquals(1, second.get(Counter.AWAITING_REVIEW));
    assertEquals(0, second.get(Counter.NEEDS_REVIEW));
    assertEquals("No second alert", 1, warnings().size());
    verify(checkoutWrites, never()).recordExpired(anyString());
  }

  // ── Phase 5: stuck RECEIVED events ───────────────────────────────────────────────────────

  @Test
  public void aStuckEventIsMarkedIgnoredAsSupersededNotReplayed() {
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        REQ));

    ReconciliationResult result = run(false);

    ArgumentCaptor<Date> before = ArgumentCaptor.forClass(Date.class);
    verify(eventStore).findStaleReceived(before.capture(), eq(50));
    assertEquals(ago(60, TimeUnit.MINUTES), before.getValue());
    verify(eventStore).markIgnored(eq("evt_1"),
        startsWith("Superseded by billing reconciliation " + result.getRunId()));
    verify(eventStore, never()).markApplied(anyString());
    verify(checkoutStore, never()).recordPaid(anyString(), any(), any());
    assertEquals(1, result.get(Counter.STALE_EVENTS_SWEPT));
  }

  @Test
  public void aTrippedBreakerKeepsSubscriptionEventsOutOfTheSweep() {
    staleEvents.add(new BillingEventStore.StaleEvent("evt_sub", "customer.subscription.updated",
        null));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_inv", "invoice.payment_failed", null));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_chk", "checkout.session.completed",
        REQ));
    doAnswer(call -> {
      call.getArgument(0, ReconciliationResult.class).tripBreaker("key mode mismatch");
      return null;
    }).when(subscriptions).run(any(), any());

    ReconciliationResult result = run(false);

    verify(eventStore, never()).markIgnored(eq("evt_sub"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_inv"), anyString());
    verify(eventStore).markIgnored(eq("evt_chk"), anyString());
    assertEquals(1, result.get(Counter.STALE_EVENTS_SWEPT));
    assertTrue(result.hasFailures());
  }

  @Test
  public void withoutABreakerSubscriptionEventsAreSweptToo() {
    staleEvents.add(new BillingEventStore.StaleEvent("evt_sub", "customer.subscription.updated",
        null));

    run(false);

    verify(eventStore).markIgnored(eq("evt_sub"), anyString());
  }

  // ── Dry run ──────────────────────────────────────────────────────────────────────────────

  @Test
  public void aDryRunCountsEverythingAndWritesClaimsAndProvisionsNothing() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "paid", REQ);
    createdRows.add(row("req-expired", "cs_test_old", ago(3, TimeUnit.DAYS), "pending"));
    session("cs_test_old", "expired", "unpaid", "req-expired");
    paidRows.add(paidRow("req-stalled", ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    creatingRows.add(row("req-nosession", null, ago(3, TimeUnit.DAYS), "pending"));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        null));

    ReconciliationResult result = run(true);

    verifyNothingProvisionedOrWritten();
    assertEquals(1, result.get(Counter.PAYMENTS_RECOVERED));
    assertEquals(1, result.get(Counter.CHECKOUTS_EXPIRED));
    assertEquals("the recovered payment and the stalled row", 2,
        result.get(Counter.PROVISIONED));
    assertEquals(1, result.get(Counter.NEEDS_REVIEW));
    assertEquals(1, result.get(Counter.STALE_EVENTS_SWEPT));
    String summary = result.toString();
    assertTrue(summary, summary.startsWith("Dry run "));
    assertTrue(summary, summary.contains("Dry run: nothing was written"));
    verify(subscriptions).run(eq(result), any());
  }

  // ── The idempotent re-run ────────────────────────────────────────────────────────────────

  @Test
  public void aConsistentDatasetGetsZeroWritesAndZeroAlerts() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "open", "unpaid", REQ);
    paidRows.add(paidRow("req-live", ago(2, TimeUnit.HOURS), 1L, "provisioning"));
    creatingRows.add(row("req-nosession", null, ago(3, TimeUnit.DAYS), "pending"));
    when(eventStore.exists("reconcile:needs-review:req-nosession")).thenReturn(true);

    ReconciliationResult result = run(false);

    verifyNothingProvisionedOrWritten();
    assertEquals(0, result.repairs());
    assertTrue(warnings().toString(), warnings().isEmpty());
    assertTrue("No repair line either", auditLog.messagesAt(Level.INFO).isEmpty());
    assertTrue(result.toString(),
        result.toString().endsWith("Everything is consistent; nothing was changed."));
  }

  // ── One failing phase does not stop the run ─────────────────────────────────────────────

  @Test
  public void aFailingPhaseIsRecordedAndTheNextPhasesStillRun() {
    doThrow(new IllegalStateException("db gone")).when(subscriptions).run(any(), any());
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        null));

    ReconciliationResult result = run(false);

    assertEquals(List.of("subscriptions failed: IllegalStateException"), result.getErrors());
    verify(eventStore).markIgnored(eq("evt_1"), anyString());
  }
}
