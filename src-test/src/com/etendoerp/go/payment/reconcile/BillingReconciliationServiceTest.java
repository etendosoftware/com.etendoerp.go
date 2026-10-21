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
import com.etendoerp.go.rest.PaidReactivationService;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.email.InternalAlertEvent;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

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
  /** Requests with an open review row: the candidate queries leave them out. */
  private final java.util.Set<String> parked = new java.util.HashSet<>();
  /** Stuck events a run marked IGNORED: the page query no longer returns them. */
  private final java.util.Set<String> superseded = new java.util.HashSet<>();
  /** The HQL of every candidate query the run issued, in order. */
  private final List<String> candidateQueries = new ArrayList<>();
  /** Checkout requests a single-request lookup (the stuck-event sweep) finds, by id. */
  private final java.util.Map<String, CheckoutRequest> requestsById = new java.util.HashMap<>();

  private StripeApiClient stripe;
  private CheckoutRequestStore checkoutStore;
  private BillingEventStore eventStore;
  private PaidProvisioningService provisioning;
  private SubscriptionReconciler subscriptions;
  private CheckoutRequestWrites checkoutWrites;
  private PaidReactivationService reactivations;
  private InternalAlertService internalAlerts;
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
    reactivations = mock(PaidReactivationService.class);
    when(checkoutWrites.recordPaidPending(anyString(), any(), any())).thenReturn(true);
    internalAlerts = mock(InternalAlertService.class);
    when(eventStore.recordReconciliation(anyString(), anyString(), any(), anyString(), any(),
        any())).thenReturn(true);
    doAnswer(call -> superseded.add(call.getArgument(0, String.class))).when(eventStore)
        .markIgnored(anyString(), anyString());
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
      if (hql.contains("cr.request = :requestId")) {
        return lookupQuery();
      }
      candidateQueries.add(hql);
      List<CheckoutRequest> rows = new ArrayList<>(hql.contains(":created") ? createdRows
          : hql.contains(":paid") ? paidRows : hql.contains(":creating") ? creatingRows
              : List.of());
      if (hql.contains(ReviewQueue.NOT_PARKED)) {
        rows.removeIf(request -> parked.contains(request.getRequest()));
      }
      @SuppressWarnings("unchecked")
      OBQuery<CheckoutRequest> query = mock(OBQuery.class, RETURNS_SELF);
      when(query.list()).thenReturn(rows);
      return query;
    });
    for (String requestId : parked) {
      when(eventStore.hasOpenReconciliation(ReviewQueue.EVENT_TYPE, requestId)).thenReturn(true);
    }
    when(eventStore.countOpenReconciliations(ReviewQueue.EVENT_TYPE)).thenReturn(parked.size());
    when(eventStore.findStaleReceived(any(), any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
      java.util.Collection<?> types = call.getArgument(1, java.util.Collection.class);
      int offset = call.getArgument(3, Integer.class);
      int max = call.getArgument(4, Integer.class);
      List<BillingEventStore.StaleEvent> page = new ArrayList<>();
      for (BillingEventStore.StaleEvent event : staleEvents) {
        // A superseded event is IGNORED, so it leaves the query, as it does in the database.
        if (types.contains(event.eventType()) && !superseded.contains(event.eventId())) {
          page.add(event);
        }
      }
      return offset >= page.size() ? List.of()
          : new ArrayList<>(page.subList(offset, Math.min(page.size(), offset + max)));
    });
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      BillingReconciliationService service = new BillingReconciliationService(settings, stripe,
          checkoutStore, eventStore, provisioning, subscriptions,
          Clock.fixed(NOW, ZoneOffset.UTC));
      service.checkoutWrites = checkoutWrites;
      service.reactivations = reactivations;
      service.internalAlerts = internalAlerts;
      return service.run(dryRun);
    }
  }

  /** A lookup by request id, answered from {@link #requestsById} by the bound parameter. */
  @SuppressWarnings("unchecked")
  private OBQuery<CheckoutRequest> lookupQuery() {
    OBQuery<CheckoutRequest> query = mock(OBQuery.class, RETURNS_SELF);
    String[] bound = new String[1];
    when(query.setNamedParameter(eq("requestId"), any())).thenAnswer(call -> {
      bound[0] = call.getArgument(1, String.class);
      return query;
    });
    when(query.list()).thenAnswer(call -> requestsById.containsKey(bound[0])
        ? List.of(requestsById.get(bound[0])) : List.of());
    return query;
  }

  /** A request the stuck-event sweep can look up, in {@code status}. */
  private CheckoutRequest knownRequest(String requestId, String status) {
    CheckoutRequest request = row(requestId, SESSION, ago(2, TimeUnit.HOURS), "pending");
    when(request.getCheckoutRequestStatus()).thenReturn(status);
    requestsById.put(requestId, request);
    return request;
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
    verify(checkoutWrites, never()).recordPaidPending(anyString(), any(), any());
    verify(checkoutWrites, never()).recordExpiredPending(anyString());
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
    // recordPaidPending moved it to PAID; it was paid a minute ago, inside the browser's grace, but a
    // payment recovered in this very run is provisioned at once.
    paidRows.add(paidRow(REQ, ago(1, TimeUnit.MINUTES), 0L, "paid"));
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 1L));

    ReconciliationResult result = run(false);

    verify(checkoutWrites).recordPaidPending(REQ, "cus_1", "sub_1");
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

  /**
   * Review F1: the recovered payment is left pending and committed by its audit row's insert, so
   * a failed insert rolls the payment back too. The run says so as an error, never silently.
   */
  @Test
  public void aRecoveredPaymentWhoseAuditRowFailsIsRolledBackAndIsAnError() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "paid", REQ);
    when(eventStore.recordReconciliation(eq("reconcile:payment-recovered:" + REQ), anyString(),
        any(), anyString(), any(), any())).thenReturn(false);

    ReconciliationResult result = run(false);

    verify(checkoutWrites).recordPaidPending(REQ, "cus_1", "sub_1");
    verify(checkoutStore, never()).recordPaid(anyString(), any(), any());
    assertEquals(0, result.get(Counter.PAYMENTS_RECOVERED));
    assertEquals(List.of("audit row reconcile:payment-recovered:" + REQ + " could not be "
        + "written; the repair was rolled back with it and is retried"), result.getErrors());
    verify(provisioning, never()).provision(anyString(), any());
  }

  @Test
  public void aPaymentForARequestThatVanishedIsAnErrorWithNoAuditRow() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "paid", REQ);
    when(checkoutWrites.recordPaidPending(anyString(), any(), any())).thenReturn(false);

    ReconciliationResult result = run(false);

    verifyNoRows();
    assertEquals(0, result.get(Counter.PAYMENTS_RECOVERED));
    assertEquals(1, result.getErrors().size());
    assertTrue(result.getErrors().get(0), result.getErrors().get(0).contains("vanished"));
  }

  @Test
  public void anAbandonedCloseWhoseAuditRowFailsIsRolledBackAndIsAnError() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(3, TimeUnit.DAYS), "pending"));
    session(SESSION, "expired", "unpaid", REQ);
    when(checkoutWrites.recordExpiredPending(REQ)).thenReturn(true);
    when(eventStore.recordReconciliation(eq("reconcile:checkout-expired:" + REQ), anyString(),
        any(), anyString(), any(), any())).thenReturn(false);

    ReconciliationResult result = run(false);

    assertEquals(0, result.get(Counter.CHECKOUTS_EXPIRED));
    assertTrue(result.getErrors().toString(), result.getErrors().get(0).contains(
        "rolled back with it"));
  }

  @Test
  public void aProvisioningWhoseAuditRowFailsStandsAndTheRunSaysSo() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 0L, "paid"));
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 1L));
    when(eventStore.recordReconciliation(eq("reconcile:provisioned:" + REQ), anyString(),
        any(), anyString(), any(), any())).thenReturn(false);

    ReconciliationResult result = run(false);

    assertTrue(result.getErrors().toString(), result.getErrors().get(0).contains(
        "the repair stands without its audit row"));
  }

  @Test
  public void eachRepairIsListedWithTheCompanyName() throws Exception {
    CheckoutRequest abandoned = row(REQ, SESSION, ago(3, TimeUnit.DAYS), "pending");
    when(abandoned.getClientName()).thenReturn("Acme S.L.");
    createdRows.add(abandoned);
    session(SESSION, "expired", "unpaid", REQ);
    when(checkoutWrites.recordExpiredPending(REQ)).thenReturn(true);

    ReconciliationResult result = run(false);

    assertEquals(List.of("req " + REQ + " (Acme S.L.): close abandoned checkout — the provider "
        + "session expired"), result.itemLines());
  }

  @Test
  public void aNoPaymentRequiredSessionIsRecoveredToo() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "no_payment_required", REQ);

    ReconciliationResult result = run(false);

    verify(checkoutWrites).recordPaidPending(REQ, "cus_1", "sub_1");
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
    // W3: a run that could not read the provider is never reported consistent.
    assertTrue(result.hasFailures());
    assertTrue(result.toString(), !result.toString().contains("Everything is consistent"));
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

  /** A re-subscription (ETP-5742): a request naming its canceled environment from the start. */
  private CheckoutRequest reactivationRow(CheckoutRequest request) {
    org.openbravo.model.ad.system.Client tenant = mock(org.openbravo.model.ad.system.Client.class);
    when(tenant.getId()).thenReturn("TENANT-1");
    when(request.getCreatedClient()).thenReturn(tenant);
    when(request.getStripeSubscription()).thenReturn("sub_new");
    return request;
  }

  /**
   * A re-subscription whose payment webhook was lost: recording the payment alone would leave the
   * customer paid and blocked. The run applies it through the webhook's own path, ordered at the
   * new subscription's start on the provider's clock.
   */
  @Test
  public void aLostReSubscriptionPaymentIsRecordedAndThenApplied() throws Exception {
    createdRows.add(reactivationRow(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending")));
    session(SESSION, "complete", "paid", REQ);
    paidRows.add(reactivationRow(paidRow(REQ, ago(1, TimeUnit.MINUTES), 0L, "paid")));
    when(stripe.get("/v1/subscriptions/sub_new?expand%5B%5D=latest_invoice")).thenReturn(new StripeResponse(200,
        "{\"id\":\"sub_new\",\"status\":\"active\",\"current_period_start\":1792000000}"));
    when(reactivations.applyIfPending(eq(REQ), any())).thenReturn(true);

    ReconciliationResult result = run(false);

    verify(checkoutWrites).recordPaidPending(REQ, "cus_1", "sub_1");
    verify(reactivations).applyIfPending(REQ, Instant.ofEpochSecond(1_792_000_000L));
    verify(provisioning, never()).provision(anyString(), any());
    verifyRow("reconcile:reactivation-applied:" + REQ, "reconciliation.reactivation-applied",
        BillingEventStore.RESULT_APPLIED);
    assertEquals(1, result.get(Counter.REACTIVATIONS_APPLIED));
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  public void aReSubscriptionThatCannotBeAppliedFailsTheRunInsteadOfReportingItRecovered()
      throws Exception {
    paidRows.add(reactivationRow(paidRow(REQ, ago(2, TimeUnit.HOURS), 0L, "paid")));
    when(stripe.get("/v1/subscriptions/sub_new?expand%5B%5D=latest_invoice")).thenReturn(new StripeResponse(200,
        "{\"id\":\"sub_new\",\"status\":\"active\"}"));
    when(reactivations.applyIfPending(eq(REQ), any()))
        .thenThrow(new IllegalStateException("could not mark productive"));

    ReconciliationResult result = run(false);

    assertEquals(0, result.get(Counter.REACTIVATIONS_APPLIED));
    assertTrue(result.hasFailures());
    assertTrue(result.toString(), !result.toString().contains("Everything is consistent"));
  }

  @Test
  public void aReSubscriptionInsideTheGraceIsLeftToItsWebhook() throws Exception {
    paidRows.add(reactivationRow(paidRow(REQ, ago(5, TimeUnit.MINUTES), 0L, "paid")));

    run(false);

    verify(reactivations, never()).applyIfPending(anyString(), any());
    verify(stripe, never()).get(anyString());
  }

  /** A row paid before PAID_AT was recorded is provisioned once its creation leaves the grace. */
  @Test
  public void aPaidRowWithoutAPaidAtFallsBackToItsCreation() {
    CheckoutRequest legacy = paidRow(REQ, null, 0L, "paid");
    when(legacy.getCreationDate()).thenReturn(ago(2, TimeUnit.HOURS));
    paidRows.add(legacy);
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
    when(checkoutWrites.recordExpiredPending(REQ)).thenReturn(true);

    ReconciliationResult result = run(false);

    verify(checkoutWrites).recordExpiredPending(REQ);
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

    verify(checkoutWrites, never()).recordExpiredPending(anyString());
  }

  @Test
  public void anOpenSessionLeavesTheRowUntouchedHoweverOldItIs() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(30, TimeUnit.DAYS), "pending"));
    session(SESSION, "open", "unpaid", REQ);

    ReconciliationResult result = run(false);

    verifyNothingProvisionedOrWritten();
    assertEquals(1, result.get(Counter.CHECKOUTS_CHECKED));
  }

  /**
   * G5: a session that completed with an asynchronous payment still pending ({@code complete},
   * {@code unpaid}) is not a payment yet: nothing is recorded, audited or provisioned.
   */
  @Test
  public void aCompleteSessionWhosePaymentIsStillPendingWritesNothing() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "complete", "unpaid", REQ);

    ReconciliationResult result = run(false);

    verifyNothingProvisionedOrWritten();
    verify(eventStore, never()).touchReconciliation(anyString(), anyString(), any(), any());
    assertEquals(0, result.get(Counter.PAYMENTS_RECOVERED));
    assertEquals(0, result.get(Counter.PROVISIONED));
    assertEquals(1, result.get(Counter.CHECKOUTS_CHECKED));
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  public void aPaymentThatRacedTheCloseWinsAndNothingIsAudited() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(49, TimeUnit.HOURS), "pending"));
    session(SESSION, "expired", "unpaid", REQ);
    when(checkoutWrites.recordExpiredPending(REQ)).thenReturn(false);

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
    parked.add(REQ);

    ReconciliationResult result = run(false);

    verify(provisioning, never()).provision(anyString(), any());
    verifyNoRows();
    assertEquals(1, result.get(Counter.AWAITING_REVIEW));
    assertTrue(warnings().toString(), warnings().isEmpty());
  }

  /**
   * A parked row and a row whose provider read fails on every run must not fill the fixed-size
   * batch: the candidate queries leave out what is parked and what failed within the back-off.
   */
  @Test
  public void theCandidateQueriesLeaveOutParkedAndBackingOffRows() {
    run(false);

    assertEquals(3, candidateQueries.size());
    String recovery = candidateQueries.get(0);
    String provisioningRepair = candidateQueries.get(1);
    String sessionless = candidateQueries.get(2);
    assertTrue(recovery, recovery.contains("rl.eventType = :retryType"));
    assertTrue(provisioningRepair, provisioningRepair.contains(ReviewQueue.NOT_PARKED));
    assertTrue(provisioningRepair, provisioningRepair.contains("rl.eventType = :retryType"));
    assertTrue(sessionless, sessionless.contains(ReviewQueue.NOT_PARKED));
  }

  @Test
  public void aFailedSessionReadBacksTheRowOffInsteadOfRetryingItEveryRun() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    when(stripe.get("/v1/checkout/sessions/" + SESSION)).thenReturn(new StripeResponse(503, ""));

    run(false);

    verify(eventStore).touchReconciliation(eq("reconcile:retry-later:" + REQ),
        eq("reconciliation.retry-later"), eq(REQ), anyString());
  }

  @Test
  public void aDryRunWritesNoBackOffMarker() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    when(stripe.get("/v1/checkout/sessions/" + SESSION)).thenReturn(new StripeResponse(503, ""));

    run(true);

    verify(eventStore, never()).touchReconciliation(anyString(), anyString(), any(), any());
  }

  /** An item that throws is backed off and rolled back; the next item still runs. */
  @Test
  public void aProvisioningItemThatThrowsIsBackedOffAndTheNextOneStillRuns() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    paidRows.add(paidRow("req-2", ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(provisioning.provision(eq(REQ), any())).thenThrow(new IllegalStateException("boom"));
    when(provisioning.provision(eq("req-2"), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 2L));

    ReconciliationResult result = run(false);

    verify(eventStore).touchReconciliation(eq("reconcile:retry-later:" + REQ),
        eq("reconciliation.retry-later"), eq(REQ), org.mockito.ArgumentMatchers.contains(
            "IllegalStateException"));
    verify(eventStore, never()).touchReconciliation(eq("reconcile:retry-later:req-2"),
        anyString(), anyString(), anyString());
    verify(provisioning).provision(eq("req-2"), any());
    assertEquals(1, result.get(Counter.PROVISIONED));
    assertEquals(List.of("provisioning of " + REQ + ": IllegalStateException"),
        result.getErrors());
    assertTrue("The phase did not finish cleanly",
        !result.isComplete(ReconciliationResult.Phase.PROVISIONING_REPAIR));
  }

  @Test
  public void aBackOffMarkerThatCannotBeWrittenDoesNotStopTheRun() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    paidRows.add(paidRow("req-2", ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(provisioning.provision(eq(REQ), any())).thenThrow(new IllegalStateException("boom"));
    doThrow(new IllegalStateException("db gone")).when(eventStore)
        .touchReconciliation(anyString(), anyString(), anyString(), anyString());
    when(provisioning.provision(eq("req-2"), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 2L));

    ReconciliationResult result = run(false);

    verify(provisioning).provision(eq("req-2"), any());
    assertEquals(1, result.get(Counter.PROVISIONED));
  }

  /** A dry run that meets a failing item writes no back-off marker and goes on with the next. */
  @Test
  public void aDryRunWritesNoBackOffMarkerForAFailingProvisioningItem() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 5L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    paidRows.add(paidRow("req-2", ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    // Parking REQ for review reads the store, which fails.
    when(eventStore.hasOpenReconciliation(ReviewQueue.EVENT_TYPE, REQ))
        .thenThrow(new IllegalStateException("boom"));

    ReconciliationResult result = run(true);

    verify(eventStore, never()).touchReconciliation(anyString(), anyString(), any(), any());
    assertEquals(List.of("provisioning of " + REQ + ": IllegalStateException"),
        result.getErrors());
    assertEquals("req-2 is still counted", 1, result.get(Counter.PROVISIONED));
  }

  /** Released after five attempts: the budget counts again from the attempts it was parked at. */
  @Test
  public void aReleasedPurchaseGetsItsAttemptBudgetBack() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 6L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(eventStore.latestReconciliationSummary(ReviewQueue.EVENT_TYPE, REQ,
        BillingEventStore.RESULT_IGNORED)).thenReturn("{\"code\":\"ATTEMPTS_EXHAUSTED\","
            + "\"attempts\":\"5\"}");
    when(provisioning.provision(eq(REQ), any()))
        .thenReturn(new Outcome(Status.PROVISIONED, null, "Environment created", 7L));

    run(false);

    verify(provisioning).provision(eq(REQ), any());
  }

  @Test
  public void aReleasedPurchaseThatSpendsItsBudgetAgainIsParkedUnderANewRow() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 10L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(eventStore.latestReconciliationSummary(ReviewQueue.EVENT_TYPE, REQ,
        BillingEventStore.RESULT_IGNORED)).thenReturn("{\"attempts\":\"5\"}");
    when(eventStore.countReconciliations(ReviewQueue.EVENT_TYPE, REQ)).thenReturn(1);

    run(false);

    verify(provisioning, never()).provision(anyString(), any());
    verify(eventStore).recordReconciliation(eq("reconcile:needs-review:" + REQ + ":2"),
        eq("reconciliation.needs-review"), eq(REQ), eq(BillingEventStore.RESULT_FAILED), any(),
        eq("{\"code\":\"ATTEMPTS_EXHAUSTED\",\"attempts\":\"10\"}"));
  }

  /** S5: a purchase parked for review reaches a person, once. */
  @Test
  public void aNewReviewSendsOneInternalAlertAndADryRunNone() {
    creatingRows.add(row(REQ, null, ago(3, TimeUnit.DAYS), "pending"));

    run(true);
    verify(internalAlerts, never()).sendAfterTransaction(any());

    run(false);
    ArgumentCaptor<InternalAlertEvent> alert = ArgumentCaptor.forClass(InternalAlertEvent.class);
    verify(internalAlerts).sendAfterTransaction(alert.capture());
    assertEquals("billing-reconcile-needs-review", alert.getValue().getEvent());
    assertEquals(REQ, alert.getValue().getAttemptId());
    assertEquals("NO_SESSION_ID", alert.getValue().getStage());
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

  /** G1, second half: a paywall that does not confirm the payment parks it as NOT_PAID. */
  @Test
  public void aNotPaidOutcomeParksThePurchaseWithCodeNotPaid() {
    paidRows.add(paidRow(REQ, ago(2, TimeUnit.HOURS), 1L,
        CheckoutRequestStore.DERIVED_STATUS_STALLED));
    when(provisioning.provision(eq(REQ), any())).thenReturn(new Outcome(Status.NOT_PAID, null,
        "The paywall does not confirm this purchase as paid", null));

    ReconciliationResult result = run(false);

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(eventStore).recordReconciliation(eq("reconcile:needs-review:" + REQ),
        eq("reconciliation.needs-review"), eq(REQ), eq(BillingEventStore.RESULT_FAILED),
        reason.capture(), eq("{\"code\":\"NOT_PAID\",\"attempts\":\"1\"}"));
    assertTrue(reason.getValue(), reason.getValue().startsWith("Needs manual review (NOT_PAID): "
        + "The paywall does not confirm this purchase as paid"));
    assertEquals(1, result.get(Counter.NEEDS_REVIEW));
    assertEquals(0, result.get(Counter.PROVISIONED));
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
    verify(checkoutWrites, never()).recordExpiredPending(anyString());

    // Second run: the open review row keeps it out of the batch; it is counted as awaiting and
    // stays silent.
    eventStore = mock(BillingEventStore.class);
    parked.add(REQ);

    ReconciliationResult second = run(false);

    verifyNoRows();
    assertEquals(1, second.get(Counter.AWAITING_REVIEW));
    assertEquals(0, second.get(Counter.NEEDS_REVIEW));
    assertEquals("No second alert", 1, warnings().size());
    verify(checkoutWrites, never()).recordExpiredPending(anyString());
  }

  // ── Phase 5: stuck RECEIVED events ───────────────────────────────────────────────────────

  @Test
  public void aStuckPaymentEventWhoseRequestIsPaidIsSupersededNotReplayed() {
    knownRequest(REQ, "PROVISIONED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        REQ));

    ReconciliationResult result = run(false);

    ArgumentCaptor<Date> before = ArgumentCaptor.forClass(Date.class);
    verify(eventStore).findStaleReceived(before.capture(), any(),
        eq(BillingReconciliationService.STALE_REVIEW_ID_PREFIX), eq(0), eq(50));
    assertEquals(ago(60, TimeUnit.MINUTES), before.getValue());
    verify(eventStore).markIgnored(eq("evt_1"),
        startsWith("Superseded by billing reconciliation " + result.getRunId()));
    verify(eventStore, never()).markApplied(anyString());
    verify(checkoutWrites, never()).recordPaidPending(anyString(), any(), any());
    assertEquals(1, result.get(Counter.STALE_EVENTS_SWEPT));
  }

  /**
   * Four stuck payment events, two pages of two: e1 and e4 belong to provisioned requests and are
   * superseded, e2 and e3 to requests the run has not reached, so they are left.
   */
  private void fourStuckEventsOverTwoPages() {
    settings = new ReconciliationSettings(60, 2, 48, 15, 60, 0.25d, 5);
    knownRequest("req-e1", "PROVISIONED");
    knownRequest("req-e2", "CREATED");
    knownRequest("req-e3", "CREATED");
    knownRequest("req-e4", "PROVISIONED");
    for (String n : new String[] { "1", "2", "3", "4" }) {
      staleEvents.add(new BillingEventStore.StaleEvent("evt_e" + n, "checkout.session.completed",
          "req-e" + n));
    }
  }

  /** A superseded event leaves the query, so the next page starts that many rows earlier. */
  @Test
  public void theStuckEventSweepPagesPastTheEventsItLeaves() {
    fourStuckEventsOverTwoPages();

    ReconciliationResult result = run(false);

    verify(eventStore).findStaleReceived(any(), any(), any(), eq(0), eq(2));
    verify(eventStore).findStaleReceived(any(), any(), any(), eq(1), eq(2));
    verify(eventStore).markIgnored(eq("evt_e1"), anyString());
    verify(eventStore).markIgnored(eq("evt_e4"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_e2"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_e3"), anyString());
    assertEquals(2, result.get(Counter.STALE_EVENTS_SWEPT));
  }

  /**
   * In a dry run nothing leaves the query, so the offset moves by the whole page: every event is
   * seen once, none twice, and nothing is written.
   */
  @Test
  public void aDryRunPagesTheStuckEventsByWholePagesAndWritesNothing() {
    fourStuckEventsOverTwoPages();

    ReconciliationResult result = run(true);

    verify(eventStore).findStaleReceived(any(), any(), any(), eq(0), eq(2));
    verify(eventStore).findStaleReceived(any(), any(), any(), eq(2), eq(2));
    verify(eventStore, never()).findStaleReceived(any(), any(), any(), eq(1), anyInt());
    verify(eventStore, never()).markIgnored(anyString(), anyString());
    assertEquals("e1 and e4, each once", 2, result.get(Counter.STALE_EVENTS_SWEPT));
  }

  @Test
  public void anAlertOnlyEventIsNeverSwept() {
    staleEvents.add(new BillingEventStore.StaleEvent("evt_dp", "charge.dispute.created", null));

    run(false);

    ArgumentCaptor<java.util.Collection<String>> types = typesSwept();
    assertTrue(types.getValue().toString(), !types.getValue().contains("charge.dispute.created"));
    verify(eventStore, never()).markIgnored(eq("evt_dp"), anyString());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private ArgumentCaptor<java.util.Collection<String>> typesSwept() {
    ArgumentCaptor<java.util.Collection<String>> types =
        (ArgumentCaptor) ArgumentCaptor.forClass(java.util.Collection.class);
    verify(eventStore).findStaleReceived(any(), types.capture(), any(), anyInt(), anyInt());
    return types;
  }

  @Test
  public void aTrippedBreakerKeepsSubscriptionEventsOutOfTheSweep() {
    knownRequest(REQ, "PROVISIONED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_sub", "customer.subscription.updated",
        null, "{\"id\":\"sub_1\"}"));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_inv", "invoice.payment_failed", null));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_chk", "checkout.session.completed",
        REQ));
    doAnswer(call -> {
      ReconciliationResult running = call.getArgument(0, ReconciliationResult.class);
      running.noteSubscriptionReread("sub_1", "canceled");
      running.tripBreaker("key mode mismatch");
      running.markIncomplete(ReconciliationResult.Phase.SUBSCRIPTIONS, "breaker");
      return null;
    }).when(subscriptions).run(any(), any());

    ReconciliationResult result = run(false);

    verify(eventStore, never()).markIgnored(eq("evt_sub"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_inv"), anyString());
    verify(eventStore).markIgnored(eq("evt_chk"), anyString());
    assertEquals(1, result.get(Counter.STALE_EVENTS_SWEPT));
    assertEquals("Left for a later run, not parked", 0, result.get(Counter.STALE_EVENTS_PARKED));
    assertTrue(result.hasFailures());
  }

  @Test
  public void aSubscriptionEventIsSupersededOnlyWhenItsSubscriptionWasReRead() {
    staleEvents.add(new BillingEventStore.StaleEvent("evt_read", "customer.subscription.updated",
        null, "{\"id\":\"sub_1\"}"));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_unread", "invoice.paid", null,
        "{\"subscription\":\"sub_other\"}"));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_due", "invoice.payment_failed", null,
        "{\"subscription\":\"sub_due\"}"));
    doAnswer(call -> {
      ReconciliationResult running = call.getArgument(0, ReconciliationResult.class);
      running.noteSubscriptionReread("sub_1", "active");
      running.noteSubscriptionReread("sub_due", "past_due");
      return null;
    }).when(subscriptions).run(any(), any());

    ReconciliationResult result = run(false);

    verify(eventStore).markIgnored(eq("evt_read"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_unread"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_due"), anyString());
    // Not re-read, or past_due (left to the webhook): parked for a person, once.
    verify(eventStore).recordReconciliation(eq("reconcile:stale-event-review:evt_unread"),
        eq("reconciliation.stale-event-review"), any(), eq(BillingEventStore.RESULT_FAILED),
        any(), any());
    verify(eventStore).recordReconciliation(eq("reconcile:stale-event-review:evt_due"),
        anyString(), any(), anyString(), any(), any());
    assertEquals(1, result.get(Counter.STALE_EVENTS_SWEPT));
    assertEquals(2, result.get(Counter.STALE_EVENTS_PARKED));
  }

  /** W2: a phase-1 provider error means payments were not all re-read; none is superseded. */
  @Test
  public void aPaymentEventIsNotSupersededWhenCheckoutRecoveryCouldNotReadTheProvider()
      throws Exception {
    createdRows.add(row("req-other", "cs_test_other", ago(20, TimeUnit.MINUTES), "pending"));
    when(stripe.get("/v1/checkout/sessions/cs_test_other"))
        .thenReturn(new StripeResponse(503, ""));
    knownRequest(REQ, "PROVISIONED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        REQ));

    ReconciliationResult result = run(false);

    verify(eventStore, never()).markIgnored(anyString(), anyString());
    assertTrue(typesSwept().getValue().isEmpty() || !typesSwept().getValue()
        .contains("checkout.session.completed"));
    assertTrue(result.hasFailures());
  }

  @Test
  public void aPaymentEventTheProviderContradictsIsParkedForReview() throws Exception {
    createdRows.add(row(REQ, SESSION, ago(20, TimeUnit.MINUTES), "pending"));
    session(SESSION, "open", "unpaid", REQ);
    knownRequest(REQ, "CREATED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        REQ));

    ReconciliationResult result = run(false);

    verify(eventStore, never()).markIgnored(anyString(), anyString());
    verify(eventStore).recordReconciliation(eq("reconcile:stale-event-review:evt_1"),
        anyString(), eq(REQ), eq(BillingEventStore.RESULT_FAILED), any(), any());
    assertEquals(1, result.get(Counter.STALE_EVENTS_PARKED));
  }

  /** A payment event whose request is CREATING or EXPIRED will never resolve: parked once. */
  @Test
  public void aPaymentEventWhoseRequestIsCreatingOrExpiredIsParkedNotLeftForever() {
    knownRequest("req-creating", "CREATING");
    knownRequest("req-expired", "EXPIRED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_creating",
        "checkout.session.completed", "req-creating"));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_expired",
        "checkout.session.completed", "req-expired"));

    ReconciliationResult result = run(false);

    verify(eventStore, never()).markIgnored(anyString(), anyString());
    verify(eventStore).recordReconciliation(eq("reconcile:stale-event-review:evt_creating"),
        anyString(), eq("req-creating"), eq(BillingEventStore.RESULT_FAILED), any(), any());
    verify(eventStore).recordReconciliation(eq("reconcile:stale-event-review:evt_expired"),
        anyString(), eq("req-expired"), eq(BillingEventStore.RESULT_FAILED), any(), any());
    assertEquals(2, result.get(Counter.STALE_EVENTS_PARKED));
  }

  @Test
  public void aPaymentEventForARequestNotYetReachedIsLeftForALaterRun() {
    knownRequest(REQ, "CREATED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        REQ));

    ReconciliationResult result = run(false);

    verify(eventStore, never()).markIgnored(anyString(), anyString());
    assertEquals(0, result.get(Counter.STALE_EVENTS_SWEPT));
    assertEquals(0, result.get(Counter.STALE_EVENTS_PARKED));
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
    assertEquals("an event naming no request is parked, not superseded", 1,
        result.get(Counter.STALE_EVENTS_PARKED));
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
    parked.add("req-nosession");

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
    knownRequest(REQ, "PROVISIONED");
    staleEvents.add(new BillingEventStore.StaleEvent("evt_1", "checkout.session.completed",
        REQ));
    staleEvents.add(new BillingEventStore.StaleEvent("evt_sub", "customer.subscription.deleted",
        null, "{\"id\":\"sub_1\"}"));

    ReconciliationResult result = run(false);

    assertEquals(List.of("subscriptions failed: IllegalStateException"), result.getErrors());
    // The sweep still runs, but only for the family whose state was re-read: the subscriptions
    // phase threw, so its events are neither superseded nor parked.
    verify(eventStore).markIgnored(eq("evt_1"), anyString());
    verify(eventStore, never()).markIgnored(eq("evt_sub"), anyString());
    assertTrue(typesSwept().getValue().toString(),
        !typesSwept().getValue().contains("customer.subscription.deleted"));
    assertTrue(result.hasFailures());
  }
}
