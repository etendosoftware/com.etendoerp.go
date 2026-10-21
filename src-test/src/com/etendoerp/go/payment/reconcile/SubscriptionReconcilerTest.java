/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CheckoutConfiguration;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;
import com.etendoerp.go.payment.LifecycleTargetFixtures;
import com.etendoerp.go.payment.PausedSubscriptionAlert;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.StripeTransportException;
import com.etendoerp.go.payment.SubscriptionEventOutcome;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier.StoredState;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService.LifecycleTarget;
import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Specs for {@link SubscriptionReconciler}, phase 4 of the billing reconciliation: the real Stripe
 * status of every locally active subscription, on the row route and on the preference route.
 *
 * <p>The status mapping is the webhook's own {@link SubscriptionLifecycleApplier}, used for real
 * here, so these specs also pin that the job and the webhook read a status the same way. The
 * lifecycle service, the billing-event store, the paused alert and the provider are mocked; the
 * candidate queries are answered per entity.
 *
 * <ul>
 *   <li>{@code canceled}, {@code incomplete_expired} and a 404 (absent) close; {@code active} and
 *       {@code trialing} are left alone; {@code past_due} is the grace logic's.</li>
 *   <li>A consistent subscription costs no write — not even a period or {@code LAST_EVENT_AT}
 *       refresh.</li>
 *   <li>Absent closes sit behind the circuit breaker: share of 404s, and test/live key mode.</li>
 *   <li>{@code paused} is alert-only, once; a subscription without our {@code request_id} is
 *       foreign and never touched.</li>
 *   <li>The preference route resolves through {@code targetForTenant} with the subscription id
 *       the creating request recorded.</li>
 * </ul>
 *
 * @covers com.etendoerp.go.payment.reconcile.SubscriptionReconciler
 */
public class SubscriptionReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
  private static final String LIST_PATH =
      "/v1/subscriptions?status=all&expand%5B%5D=data.latest_invoice&limit=100";
  private static final String DIRECT_SUFFIX = "?expand%5B%5D=latest_invoice";
  /** The {@code ended_at} every terminated fixture carries: the provider instant of a close. */
  private static final Instant ENDED = Instant.ofEpochSecond(1_792_000_000L);
  private static final String CLIENT = "CLIENT-1";
  private static final StoredState CURRENT = new StoredState(SubscriptionStatus.CURRENT, null,
      Instant.parse("2026-10-01T00:00:00Z"));

  private final List<Subscription> rows = new ArrayList<>();
  private final List<CheckoutRequest> provisionedPurchases = new ArrayList<>();
  /** What the list sweep returns, by id; an id missing here is read directly. */
  private final Map<String, String> listed = new LinkedHashMap<>();

  private StripeApiClient stripe;
  private CheckoutRequestStore checkoutStore;
  private TenantEnvironmentLifecycleService lifecycle;
  private PausedSubscriptionAlert pausedAlert;
  private BillingEventStore eventStore;
  private ReconciliationSettings settings = ReconciliationSettings.defaults();
  private String secretKey = "sk_test_key";
  /** When set, the answer of the list endpoint instead of the page built from {@link #listed}. */
  private StripeResponse listAnswer;
  private TestLogCapture auditLog;

  @Before
  public void setUp() throws Exception {
    stripe = mock(StripeApiClient.class);
    checkoutStore = mock(CheckoutRequestStore.class);
    lifecycle = mock(TenantEnvironmentLifecycleService.class);
    pausedAlert = mock(PausedSubscriptionAlert.class);
    eventStore = mock(BillingEventStore.class);
    when(eventStore.recordReconciliation(anyString(), anyString(), any(), anyString(), any(),
        any())).thenReturn(true);
    when(lifecycle.applySubscriptionEvent(any(), any(), any())).thenReturn(true);
    when(pausedAlert.raiseOnce(anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(true);
    auditLog = TestLogCapture.attachTo(ReconciliationAudit.class, Level.INFO);
  }

  @After
  public void tearDown() {
    auditLog.detach();
  }

  // ── harness ───────────────────────────────────────────────────────────────────────────────

  private ReconciliationResult run(boolean dryRun) throws Exception {
    StringBuilder data = new StringBuilder();
    for (String json : listed.values()) {
      data.append(data.length() == 0 ? "" : ",").append(json);
    }
    when(stripe.get(LIST_PATH)).thenReturn(listAnswer != null ? listAnswer
        : new StripeResponse(200, "{\"has_more\":false,\"data\":[" + data + "]}"));
    OBDal dal = mock(OBDal.class);
    when(dal.createQuery(eq(Subscription.class), anyString()))
        .thenAnswer(call -> query(rows));
    when(dal.createQuery(eq(CheckoutRequest.class), anyString()))
        .thenAnswer(call -> query(provisionedPurchases));
    ReconciliationResult result = new ReconciliationResult(dryRun);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<CheckoutConfiguration> configuration =
            mockStatic(CheckoutConfiguration.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      configuration.when(CheckoutConfiguration::secretKey).thenReturn(secretKey);
      new SubscriptionReconciler(settings, stripe, checkoutStore,
          new SubscriptionLifecycleApplier(), lifecycle, pausedAlert,
          Clock.fixed(NOW, ZoneOffset.UTC))
          .run(result, new ReconciliationAudit(eventStore, dryRun));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static <T extends BaseOBObject> OBQuery<T> query(List<T> list) {
    OBQuery<T> query = mock(OBQuery.class, RETURNS_SELF);
    when(query.list()).thenReturn(new ArrayList<>(list));
    return query;
  }

  /** A locally active row naming {@code subId}, bought by {@code req} in {@code session}. */
  private Subscription activeRow(String subId, String req, String session, StoredState stored) {
    Subscription row = mock(Subscription.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    when(row.getStripeSubscription()).thenReturn(subId);
    when(row.getEnvironmentClient()).thenReturn(client);
    when(row.getProviderPriceID()).thenReturn("price_1");
    when(row.getSnapshotCurrency()).thenReturn("EUR");
    when(row.getSnapshotAmount()).thenReturn(java.math.BigDecimal.TEN);
    rows.add(row);
    CheckoutRequest purchase = purchase(req, session, subId, null);
    when(checkoutStore.findByStripeSubscription(subId)).thenReturn(purchase);
    when(lifecycle.targetForSubscription(subId))
        .thenReturn(LifecycleTargetFixtures.row(CLIENT, row, stored));
    return row;
  }

  private static CheckoutRequest purchase(String req, String session, String subId,
      String createdClientId) {
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getRequest()).thenReturn(req);
    when(purchase.getStripeSession()).thenReturn(session);
    when(purchase.getStripeSubscription()).thenReturn(subId);
    if (createdClientId != null) {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(createdClientId);
      when(purchase.getCreatedClient()).thenReturn(client);
    }
    return purchase;
  }

  private static String subscription(String id, String status, boolean ours) {
    return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"status\":\"" + status + "\","
        + "\"ended_at\":1792000000"
        + (ours ? ",\"metadata\":{\"request_id\":\"req-" + id + "\"}" : ",\"metadata\":{}")
        + "}";
  }

  private void list(String id, String status) {
    listed.put(id, subscription(id, status, true));
  }

  private void absent(String id) throws Exception {
    when(stripe.get(direct(id))).thenReturn(new StripeResponse(404,
        "{\"error\":{\"code\":\"resource_missing\"}}"));
  }

  private static String direct(String id) {
    return "/v1/subscriptions/" + id + DIRECT_SUFFIX;
  }

  /**
   * The outcome applied to {@code target}, checking the watermark it was stored with: the
   * provider's instant, never this server's clock (null for an absent subscription).
   */
  private SubscriptionEventOutcome appliedOutcome(LifecycleTarget target, Instant eventAt) {
    ArgumentCaptor<SubscriptionEventOutcome> outcome =
        ArgumentCaptor.forClass(SubscriptionEventOutcome.class);
    verify(lifecycle).applySubscriptionEvent(eq(target), outcome.capture(), eq(eventAt));
    return outcome.getValue();
  }

  private void verifyNothingApplied() {
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    verify(eventStore, never()).recordReconciliation(anyString(), anyString(), any(), any(),
        any(), any());
  }

  // ── Statuses that close ──────────────────────────────────────────────────────────────────

  @Test
  public void aCanceledSubscriptionClosesTheRowAndIsAudited() throws Exception {
    activeRow("sub_c", "req-sub_c", "cs_test_c", CURRENT);
    list("sub_c", "canceled");

    ReconciliationResult result = run(false);

    SubscriptionEventOutcome outcome =
        appliedOutcome(lifecycle.targetForSubscription("sub_c"), ENDED);
    assertEquals(SubscriptionStatus.EXPIRED, outcome.status());
    assertTrue(outcome.closesSubscription());
    verify(eventStore).recordReconciliation(eq("reconcile:subscription-closed:sub_c"),
        eq("reconciliation.subscription-closed"), eq("req-sub_c"),
        eq(BillingEventStore.RESULT_APPLIED), any(), any());
    assertEquals(1, result.get(Counter.SUBSCRIPTIONS_CLOSED));
    assertEquals(1, result.get(Counter.SUBSCRIPTIONS_CHECKED));
  }

  @Test
  public void anIncompleteExpiredSubscriptionClosesTheRow() throws Exception {
    activeRow("sub_ie", "req-sub_ie", "cs_test_ie", CURRENT);
    list("sub_ie", "incomplete_expired");

    ReconciliationResult result = run(false);

    SubscriptionEventOutcome outcome =
        appliedOutcome(lifecycle.targetForSubscription("sub_ie"), ENDED);
    assertEquals(SubscriptionStatus.EXPIRED, outcome.status());
    assertTrue(outcome.closesSubscription());
    assertEquals(1, result.get(Counter.SUBSCRIPTIONS_CLOSED));
  }

  @Test
  public void aStoreFailureIsAnErrorAndNoAuditRowIsWritten() throws Exception {
    activeRow("sub_c", "req-sub_c", "cs_test_c", CURRENT);
    list("sub_c", "canceled");
    when(lifecycle.applySubscriptionEvent(any(), any(), any())).thenReturn(false);

    ReconciliationResult result = run(false);

    verify(eventStore, never()).recordReconciliation(anyString(), anyString(), any(), any(),
        any(), any());
    assertEquals(List.of("subscription sub_c: the correction could not be stored"),
        result.getErrors());
    assertEquals(0, result.get(Counter.SUBSCRIPTIONS_CLOSED));
  }

  // ── Statuses left alone, and the no-op diff ─────────────────────────────────────────────

  @Test
  public void activeAndTrialingAreLeftAloneWithNoPeriodOrWatermarkRefresh() throws Exception {
    activeRow("sub_a", "req-sub_a", "cs_test_a", CURRENT);
    list("sub_a", "active");
    activeRow("sub_t", "req-sub_t", "cs_test_t", CURRENT);
    list("sub_t", "trialing");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertEquals(2, result.get(Counter.SUBSCRIPTIONS_CHECKED));
    assertEquals(0, result.repairs());
    assertTrue(auditLog.messagesAt(Level.WARN).isEmpty());
    assertTrue(auditLog.messagesAt(Level.INFO).isEmpty());
  }

  @Test
  public void aRowWithNoStoredStatusYetReadsAsCurrentAndIsNotRewritten() throws Exception {
    activeRow("sub_n", "req-sub_n", "cs_test_n", StoredState.NONE);
    list("sub_n", "active");

    run(false);

    verifyNothingApplied();
  }

  @Test
  public void pastDueIsLeftToTheGraceLogic() throws Exception {
    activeRow("sub_p", "req-sub_p", "cs_test_p", CURRENT);
    list("sub_p", "past_due");
    activeRow("sub_u", "req-sub_u", "cs_test_u", CURRENT);
    list("sub_u", "unpaid");
    activeRow("sub_i", "req-sub_i", "cs_test_i", CURRENT);
    list("sub_i", "incomplete");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertEquals(3, result.get(Counter.SUBSCRIPTIONS_CHECKED));
  }

  @Test
  public void aLocallyPastDueRowThatStripeSaysIsActiveIsSetBackToCurrent() throws Exception {
    activeRow("sub_r", "req-sub_r", "cs_test_r",
        new StoredState(SubscriptionStatus.PAST_DUE, NOW.minusSeconds(86400), null));
    list("sub_r", "active");

    ReconciliationResult result = run(false);

    assertEquals(SubscriptionStatus.CURRENT,
        appliedOutcome(lifecycle.targetForSubscription("sub_r"), null).status());
    verify(eventStore).recordReconciliation(
        eq("reconcile:subscription-current:sub_r:" + result.getRunId()),
        eq("reconciliation.subscription-current"), eq("req-sub_r"),
        eq(BillingEventStore.RESULT_APPLIED), any(), any());
    assertEquals(1, result.get(Counter.SUBSCRIPTIONS_CURRENT));
  }

  // ── Foreign subscriptions ────────────────────────────────────────────────────────────────

  @Test
  public void aSubscriptionWithoutOurRequestIdIsForeignAndNeverTouched() throws Exception {
    activeRow("sub_f", "req-sub_f", "cs_test_f", CURRENT);
    listed.put("sub_f", subscription("sub_f", "canceled", false));

    run(false);

    verifyNothingApplied();
    verify(pausedAlert, never()).raiseOnce(anyString(), anyString(), any(), any(), anyString());
  }

  @Test
  public void subscriptionsNoLocalRowNamesAreIgnoredByTheSweep() throws Exception {
    activeRow("sub_a", "req-sub_a", "cs_test_a", CURRENT);
    list("sub_a", "active");
    list("sub_other", "canceled");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    verify(lifecycle, never()).targetForSubscription("sub_other");
    assertEquals(1, result.get(Counter.SUBSCRIPTIONS_CHECKED));
  }

  // ── Absent subscriptions and the circuit breaker ────────────────────────────────────────

  @Test
  public void anAbsentSubscriptionIsClosedWhenTheBreakerHolds() throws Exception {
    activeRow("sub_gone", "req-sub_gone", "cs_test_gone", CURRENT);
    for (int i = 0; i < 3; i++) {
      activeRow("sub_ok" + i, "req-sub_ok" + i, "cs_test_ok" + i, CURRENT);
      list("sub_ok" + i, "active");
    }
    absent("sub_gone");

    ReconciliationResult result = run(false);

    SubscriptionEventOutcome outcome =
        appliedOutcome(lifecycle.targetForSubscription("sub_gone"), null);
    assertEquals(SubscriptionStatus.EXPIRED, outcome.status());
    assertTrue(outcome.closesSubscription());
    verify(eventStore).recordReconciliation(eq("reconcile:subscription-closed:sub_gone"),
        anyString(), eq("req-sub_gone"), eq(BillingEventStore.RESULT_APPLIED), any(), any());
    assertEquals(1, result.get(Counter.ABSENT_SUBSCRIPTIONS));
    assertNull(result.getBreakerReason());
  }

  @Test
  public void tooManyAbsentSubscriptionsTripTheBreakerAndNothingIsClosed() throws Exception {
    // 2 of 4 absent: more than max(1, floor(0.25 * 4)) = 1.
    activeRow("sub_g1", "req-g1", "cs_test_g1", CURRENT);
    activeRow("sub_g2", "req-g2", "cs_test_g2", CURRENT);
    activeRow("sub_ok1", "req-ok1", "cs_test_ok1", CURRENT);
    activeRow("sub_ok2", "req-ok2", "cs_test_ok2", CURRENT);
    list("sub_ok1", "active");
    list("sub_ok2", "active");
    absent("sub_g1");
    absent("sub_g2");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertNotNull(result.getBreakerReason());
    assertEquals("2 of 4 subscriptions are absent, more than the 1 allowed",
        result.getBreakerReason());
    assertTrue(result.hasFailures());
    assertEquals(2, result.get(Counter.ABSENT_SUBSCRIPTIONS));
    assertTrue(auditLog.messagesAt(Level.WARN).toString(), auditLog.messagesAt(Level.WARN)
        .stream().anyMatch(line -> line.startsWith(
            "[BILLING-RECONCILE] Absent-subscription breaker tripped")));
  }

  @Test
  public void theThresholdIsConfigurable() throws Exception {
    settings = new ReconciliationSettings(60, 50, 48, 15, 60, 0.5d, 5);
    activeRow("sub_g1", "req-g1", "cs_test_g1", CURRENT);
    activeRow("sub_g2", "req-g2", "cs_test_g2", CURRENT);
    activeRow("sub_ok1", "req-ok1", "cs_test_ok1", CURRENT);
    activeRow("sub_ok2", "req-ok2", "cs_test_ok2", CURRENT);
    list("sub_ok1", "active");
    list("sub_ok2", "active");
    absent("sub_g1");
    absent("sub_g2");

    ReconciliationResult result = run(false);

    assertNull("2 of 4 is within a 0.5 share", result.getBreakerReason());
    assertEquals(2, result.get(Counter.SUBSCRIPTIONS_CLOSED));
  }

  @Test
  public void aLiveKeyOverTestDataTripsTheBreakerEvenForOneAbsent() throws Exception {
    secretKey = "sk_live_key";
    activeRow("sub_gone", "req-gone", "cs_test_gone", CURRENT);
    for (int i = 0; i < 9; i++) {
      activeRow("sub_ok" + i, "req-ok" + i, "cs_test_ok" + i, CURRENT);
      list("sub_ok" + i, "active");
    }
    absent("sub_gone");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertEquals("the configured key is live but subscription sub_gone was bought in test mode",
        result.getBreakerReason());
  }

  @Test
  public void aTestKeyOverLiveDataTripsTheBreakerToo() throws Exception {
    activeRow("sub_gone", "req-gone", "cs_live_gone", CURRENT);
    absent("sub_gone");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertTrue(result.getBreakerReason(), result.getBreakerReason().contains("live mode"));
  }

  @Test
  public void aFailedReadIsAProviderErrorNeverAnAbsence() throws Exception {
    activeRow("sub_x", "req-x", "cs_test_x", CURRENT);
    when(stripe.get(direct("sub_x"))).thenReturn(new StripeResponse(500, ""));
    activeRow("sub_y", "req-y", "cs_test_y", CURRENT);
    when(stripe.get(direct("sub_y")))
        .thenThrow(new StripeTransportException("timeout"));

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertEquals(2, result.get(Counter.PROVIDER_ERRORS));
    assertEquals(0, result.get(Counter.ABSENT_SUBSCRIPTIONS));
    assertNull(result.getBreakerReason());
  }

  @Test
  public void aFailedSweepFallsBackToDirectReadsAndClosesNothingItDidNotSee() throws Exception {
    activeRow("sub_a", "req-a", "cs_test_a", CURRENT);
    when(stripe.get(direct("sub_a"))).thenReturn(
        new StripeResponse(200, subscription("sub_a", "active", true)));
    listAnswer = new StripeResponse(500, "{\"error\":{\"code\":\"api_error\"}}");

    ReconciliationResult result = run(false);

    verify(stripe).get(direct("sub_a"));
    verifyNothingApplied();
    assertEquals(1, result.get(Counter.SUBSCRIPTIONS_CHECKED));
    assertEquals(0, result.get(Counter.ABSENT_SUBSCRIPTIONS));
  }

  @Test
  public void keyAndSessionModesAreReadFromTheirPrefixes() {
    assertEquals("live", SubscriptionReconciler.modeOfKey("sk_live_1"));
    assertEquals("live", SubscriptionReconciler.modeOfKey("rk_live_1"));
    assertEquals("test", SubscriptionReconciler.modeOfKey(" sk_test_1 "));
    assertEquals("test", SubscriptionReconciler.modeOfKey("rk_test_1"));
    assertNull(SubscriptionReconciler.modeOfKey("pk_live_1"));
    assertNull(SubscriptionReconciler.modeOfKey(null));
    assertEquals("live", SubscriptionReconciler.modeOfSession("cs_live_1"));
    assertEquals("test", SubscriptionReconciler.modeOfSession("cs_test_1"));
    assertNull(SubscriptionReconciler.modeOfSession(null));
  }

  // ── paused: alert-only, once ────────────────────────────────────────────────────────────

  @Test
  public void aPausedSubscriptionRaisesTheSharedAlertAndChangesNothing() throws Exception {
    activeRow("sub_pz", "req-sub_pz", "cs_test_pz", CURRENT);
    list("sub_pz", "paused");

    ReconciliationResult result = run(false);

    verify(pausedAlert).raiseOnce(ReconciliationAudit.LOG_PREFIX, "sub_pz", CLIENT, "req-sub_pz",
        "reconciliation");
    verifyNothingApplied();
    assertEquals(1, result.get(Counter.PAUSED_ALERTS));
  }

  @Test
  public void aPausedSubscriptionAlreadyAlertedBySomeoneIsSilent() throws Exception {
    activeRow("sub_pz", "req-sub_pz", "cs_test_pz", CURRENT);
    list("sub_pz", "paused");
    when(eventStore.exists(PausedSubscriptionAlert.eventIdOf("sub_pz"))).thenReturn(true);

    ReconciliationResult result = run(false);

    verify(pausedAlert, never()).raiseOnce(anyString(), anyString(), any(), any(), anyString());
    assertEquals(0, result.get(Counter.PAUSED_ALERTS));
    assertEquals(0, result.repairs());
  }

  @Test
  public void thePausedAlertIdIsTheOneTheReconcilerChecks() {
    assertEquals(ReconciliationAudit.eventId(PausedSubscriptionAlert.KIND, "sub_pz"),
        PausedSubscriptionAlert.eventIdOf("sub_pz"));
  }

  @Test
  public void aDryRunCountsAPausedAlertWithoutRaisingIt() throws Exception {
    activeRow("sub_pz", "req-sub_pz", "cs_test_pz", CURRENT);
    list("sub_pz", "paused");

    ReconciliationResult result = run(true);

    verify(pausedAlert, never()).raiseOnce(anyString(), anyString(), any(), any(), anyString());
    assertEquals(1, result.get(Counter.PAUSED_ALERTS));
  }

  @Test
  public void pauseCollectionOnAnActiveSubscriptionIsAlertedOnceOnly() throws Exception {
    activeRow("sub_pc", "req-sub_pc", "cs_test_pc", CURRENT);
    listed.put("sub_pc", "{\"id\":\"sub_pc\",\"status\":\"active\",\"metadata\":{\"request_id\":"
        + "\"req-sub_pc\"},\"pause_collection\":{\"behavior\":\"void\"}}");

    ReconciliationResult first = run(false);

    verify(eventStore).recordReconciliation(eq("reconcile:pause-collection:sub_pc"),
        eq("reconciliation.pause-collection"), eq("req-sub_pc"),
        eq(BillingEventStore.RESULT_IGNORED), any(), any());
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    assertEquals(1, first.get(Counter.PAUSE_COLLECTION_ALERTS));

    when(eventStore.exists("reconcile:pause-collection:sub_pc")).thenReturn(true);
    ReconciliationResult second = run(false);

    assertEquals(0, second.get(Counter.PAUSE_COLLECTION_ALERTS));
  }

  // ── The preference route (ETP-5046-TRANSITIONAL-FALLBACK) ───────────────────────────────

  @Test
  public void thePreferenceRouteResolvesTheTenantTheCreatingRequestNames() throws Exception {
    CheckoutRequest bought = purchase("req-pref", "cs_test_pref", "sub_pref", "CLIENT-PREF");
    provisionedPurchases.add(bought);
    LifecycleTarget onPreferences = LifecycleTargetFixtures.preferences("CLIENT-PREF", CURRENT);
    when(lifecycle.targetForTenant("CLIENT-PREF", "sub_pref")).thenReturn(onPreferences);
    list("sub_pref", "canceled");

    ReconciliationResult result = run(false);

    assertEquals(SubscriptionStatus.EXPIRED, appliedOutcome(onPreferences, ENDED).status());
    verify(lifecycle, never()).targetForSubscription(anyString());
    verify(stripe, never()).get(startsWith("/v1/customers"));
    verify(eventStore).recordReconciliation(eq("reconcile:subscription-closed:sub_pref"),
        anyString(), eq("req-pref"), eq(BillingEventStore.RESULT_APPLIED), any(), any());
    assertEquals(1, result.get(Counter.PREFERENCE_ROUTE_CHECKED));
    assertEquals(1, result.get(Counter.PREFERENCE_ROUTE_CORRECTED));
  }

  @Test
  public void aPreferenceTenantAlreadyExpiredIsNotACandidate() throws Exception {
    provisionedPurchases.add(purchase("req-pref", "cs_test_pref", "sub_pref", "CLIENT-PREF"));
    when(lifecycle.targetForTenant("CLIENT-PREF", "sub_pref")).thenReturn(
        LifecycleTargetFixtures.preferences("CLIENT-PREF",
            new StoredState(SubscriptionStatus.EXPIRED, null, null)));
    list("sub_pref", "canceled");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertEquals(0, result.get(Counter.PREFERENCE_ROUTE_CHECKED));
  }

  @Test
  public void anIgnoredPreferenceTargetIsNotACandidate() throws Exception {
    provisionedPurchases.add(purchase("req-pref", "cs_test_pref", "sub_pref", "CLIENT-PREF"));
    when(lifecycle.targetForTenant("CLIENT-PREF", "sub_pref"))
        .thenReturn(LifecycleTargetFixtures.ignored("CLIENT-PREF", "names another subscription"));
    list("sub_pref", "canceled");

    run(false);

    verifyNothingApplied();
  }

  /**
   * A tenant re-subscribed after a cancellation (ETP-5742) has two provisioned purchases, and the
   * preference projection holds no subscription id to tell them apart. Only the latest is read:
   * the canceled one would otherwise expire the tenant while its new subscription keeps billing.
   */
  @Test
  public void aPurchaseSupersededByALaterOneOfTheSameTenantIsNeverRead() throws Exception {
    // The candidate query orders a tenant's purchases newest first; P2 is the re-subscription.
    provisionedPurchases.add(purchase("req-p2", "cs_test_p2", "sub_B", "CLIENT-PREF"));
    provisionedPurchases.add(purchase("req-p1", "cs_test_p1", "sub_A", "CLIENT-PREF"));
    LifecycleTarget onPreferences = LifecycleTargetFixtures.preferences("CLIENT-PREF", CURRENT);
    when(lifecycle.targetForTenant(eq("CLIENT-PREF"), anyString())).thenReturn(onPreferences);
    list("sub_B", "active");
    list("sub_A", "canceled");

    ReconciliationResult result = run(false);

    verify(lifecycle, never()).targetForTenant("CLIENT-PREF", "sub_A");
    verifyNothingApplied();
    assertEquals(1, result.get(Counter.PREFERENCE_ROUTE_CHECKED));
  }

  @Test
  public void thePreferenceCandidatesAreReadNewestPurchaseFirst() throws Exception {
    provisionedPurchases.add(purchase("req-p", "cs_test_p", "sub_p", "CLIENT-PREF"));
    when(lifecycle.targetForTenant("CLIENT-PREF", "sub_p")).thenReturn(
        LifecycleTargetFixtures.preferences("CLIENT-PREF", CURRENT));
    list("sub_p", "active");
    OBDal dal = mock(OBDal.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(dal.createQuery(eq(Subscription.class), anyString())).thenAnswer(call -> query(rows));
    when(dal.createQuery(eq(CheckoutRequest.class), hql.capture()))
        .thenAnswer(call -> query(provisionedPurchases));
    when(stripe.get(LIST_PATH)).thenReturn(new StripeResponse(200,
        "{\"has_more\":false,\"data\":[" + listed.get("sub_p") + "]}"));
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<CheckoutConfiguration> configuration =
            mockStatic(CheckoutConfiguration.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      new SubscriptionReconciler(settings, stripe, checkoutStore,
          new SubscriptionLifecycleApplier(), lifecycle, pausedAlert,
          Clock.fixed(NOW, ZoneOffset.UTC))
          .run(new ReconciliationResult(false), new ReconciliationAudit(eventStore, false));
    }

    assertTrue(hql.getValue(), hql.getValue().endsWith(
        "order by coalesce(cr.paidAt, cr.creationDate) desc, cr.creationDate desc"));
  }

  /**
   * Defence in depth for the case above: a close is ordered at the subscription's own end on the
   * provider's clock, so a tenant whose watermark moved past it (a re-subscription paid later) is
   * not expired by it, exactly as the webhook would drop the late event.
   */
  @Test
  public void aCloseOlderThanTheStoredWatermarkIsStaleAndChangesNothing() throws Exception {
    provisionedPurchases.add(purchase("req-pref", "cs_test_pref", "sub_pref", "CLIENT-PREF"));
    when(lifecycle.targetForTenant("CLIENT-PREF", "sub_pref")).thenReturn(
        LifecycleTargetFixtures.preferences("CLIENT-PREF", new StoredState(
            SubscriptionStatus.CURRENT, null, ENDED.plusSeconds(3600))));
    list("sub_pref", "canceled");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertEquals(0, result.get(Counter.SUBSCRIPTIONS_CLOSED));
  }

  @Test
  public void aSubscriptionPaidAfterItsFailedRenewalIsSetCurrentAtThePaymentInstant()
      throws Exception {
    Instant failedAt = Instant.ofEpochSecond(1_792_100_000L);
    Instant paidAt = Instant.ofEpochSecond(1_792_200_000L);
    activeRow("sub_r", "req-sub_r", "cs_test_r",
        new StoredState(SubscriptionStatus.PAST_DUE, NOW.minusSeconds(86400), failedAt));
    listed.put("sub_r", "{\"id\":\"sub_r\",\"status\":\"active\","
        + "\"current_period_start\":1792000000,"
        + "\"latest_invoice\":{\"id\":\"in_1\",\"status_transitions\":{\"paid_at\":"
        + paidAt.getEpochSecond() + "}},"
        + "\"metadata\":{\"request_id\":\"req-sub_r\"}}");

    run(false);

    assertEquals(SubscriptionStatus.CURRENT,
        appliedOutcome(lifecycle.targetForSubscription("sub_r"), paidAt).status());
  }

  @Test
  public void aCompleteSweepThatListsNoneOfOurSubscriptionsTripsTheBreaker() throws Exception {
    // One local subscription, a same-mode key, and an account that lists only foreign ones: the
    // floor of one allowed absence must not let that key close the only tenant.
    activeRow("sub_only", "req-only", "cs_test_only", CURRENT);
    listed.put("sub_foreign", subscription("sub_foreign", "active", false));
    absent("sub_only");

    ReconciliationResult result = run(false);

    verifyNothingApplied();
    assertNotNull(result.getBreakerReason());
    assertTrue(result.getBreakerReason(),
        result.getBreakerReason().contains("returned none of the local subscriptions"));
  }

  @Test
  public void aCloseAlreadyRecordedIsAnErrorForReviewNotASilentSkip() throws Exception {
    activeRow("sub_c", "req-sub_c", "cs_test_c", CURRENT);
    list("sub_c", "canceled");
    when(eventStore.recordReconciliation(eq("reconcile:subscription-closed:sub_c"), anyString(),
        any(), anyString(), any(), any())).thenReturn(false);

    ReconciliationResult result = run(false);

    assertEquals(0, result.get(Counter.SUBSCRIPTIONS_CLOSED));
    assertEquals(1, result.getErrors().size());
    assertTrue(result.getErrors().get(0),
        result.getErrors().get(0).contains("reconcile:subscription-closed:sub_c"));
    assertTrue(auditLog.messagesAt(Level.WARN).toString(), auditLog.messagesAt(Level.WARN)
        .stream().anyMatch(line -> line.contains("needs review")));
  }

  // ── Dry run ──────────────────────────────────────────────────────────────────────────────

  @Test
  public void aDryRunCountsTheCloseButAppliesAndWritesNothing() throws Exception {
    activeRow("sub_c", "req-sub_c", "cs_test_c", CURRENT);
    list("sub_c", "canceled");
    activeRow("sub_gone", "req-gone", "cs_test_gone", CURRENT);
    for (int i = 0; i < 3; i++) {
      activeRow("sub_ok" + i, "req-ok" + i, "cs_test_ok" + i, CURRENT);
      list("sub_ok" + i, "active");
    }
    absent("sub_gone");

    ReconciliationResult result = run(true);

    verifyNothingApplied();
    assertEquals(2, result.get(Counter.SUBSCRIPTIONS_CLOSED));
  }

  @Test
  public void noCandidatesMeansNoProviderCallAtAll() throws Exception {
    ReconciliationResult result = run(false);

    verify(stripe, never()).get(anyString());
    assertEquals(0, result.get(Counter.SUBSCRIPTIONS_CHECKED));
  }
}
