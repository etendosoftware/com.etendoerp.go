/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.payment.TenantEnvironmentLifecycleService.LifecycleTarget;
import com.etendoerp.go.rest.EtendoGoJwtServlet;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Contract tests for correlating subscription webhooks to the row or tenant they belong to.
 *
 * <p>ETP-5047 — the servlet resolves a {@link LifecycleTarget} first: the open
 * {@code ETGO_SUBSCRIPTION} row carrying the event's Stripe subscription id
 * ({@code targetForSubscription}); failing that, the tenant through the checkout request that bought
 * the subscription, then the Stripe customer ({@code targetForTenant}). The event is evaluated
 * against the target's stored state and written with {@code applySubscriptionEvent}, which also
 * records the ordering watermark.
 *
 * <p>The webhook runs its lifecycle work under {@code SystemContext}; {@link OBContext} is mocked
 * statically for every spec so that runs with no DAL.
 */
public class SubscriptionLifecycleCorrelationTest {

  private static final String WEBHOOK_SECRET_PROPERTY = "etendo.go.checkout.webhook.secret";
  private static final String WEBHOOK_SECRET = "whsec_etp5443_correlation_test";
  private static final Instant PERIOD_END = Instant.ofEpochSecond(1793750400L);
  private static final long CREATED = 1793800000L;

  private MockedStatic<OBContext> obContext;

  @Before
  public void noDalContext() {
    // SystemContext.run installs and restores a context; with no DAL a real OBContext would NPE.
    obContext = mockStatic(OBContext.class);
  }

  @After
  public void clearWebhookSecret() {
    System.clearProperty(WEBHOOK_SECRET_PROPERTY);
    obContext.close();
  }

  // ===================== resolution: row first, then checkout request, then customer ==========

  @Test
  public void theOpenRowCarryingTheSubscriptionIdWinsWithoutAnyCheckoutLookup() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    LifecycleTarget row = rowTarget("client-row", SubscriptionLifecycleApplier.StoredState.NONE);
    when(lifecycle.targetForSubscription("sub-row")).thenReturn(row);
    when(lifecycle.applySubscriptionEvent(same(row), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-row",
        "invoice.payment_failed", paymentFailedEventCreated("sub-row", "cus-row", CREATED));

    verifyNoInteractions(requestStore);
    verify(lifecycle, never()).targetForTenant(anyString(), any());
    SubscriptionEventOutcome outcome = appliedOutcome(lifecycle, row, Instant.ofEpochSecond(CREATED));
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(PERIOD_END, outcome.dueAt());
    verify(eventStore).markApplied("evt-row");
  }

  @Test
  public void knownSubscriptionThroughItsCheckoutRequestIsAppliedToThatTenant() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-1");
    when(requestStore.findByStripeSubscription("sub-known")).thenReturn(purchase);
    LifecycleTarget target = preferenceTarget("client-1");
    when(lifecycle.targetForTenant("client-1", "sub-known")).thenReturn(target);
    when(lifecycle.applySubscriptionEvent(same(target), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-known",
        "invoice.payment_failed", paymentFailedEvent("sub-known", "cus-known"));

    verify(lifecycle).targetForSubscription("sub-known");
    verify(requestStore, never()).findByStripeCustomer(anyString());
    SubscriptionEventOutcome outcome = appliedOutcome(lifecycle, target, null);
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(PERIOD_END, outcome.dueAt());
    verify(eventStore).markApplied("evt-known");
    verify(eventStore, never()).markIgnored(anyString(), anyString());
  }

  @Test
  public void unknownSubscriptionFallsBackToKnownCustomer() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(requestStore.findByStripeSubscription("sub-missing")).thenReturn(null);
    CheckoutRequest purchase = purchaseWithClient("client-fallback");
    when(requestStore.findByStripeCustomer("cus-known")).thenReturn(purchase);
    LifecycleTarget target = preferenceTarget("client-fallback");
    when(lifecycle.targetForTenant("client-fallback", "sub-missing")).thenReturn(target);
    when(lifecycle.applySubscriptionEvent(same(target), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-fallback",
        "invoice.payment_failed", paymentFailedEvent("sub-missing", "cus-known"));

    InOrder order = inOrder(lifecycle, requestStore);
    order.verify(lifecycle).targetForSubscription("sub-missing");
    order.verify(requestStore).findByStripeSubscription("sub-missing");
    order.verify(requestStore).findByStripeCustomer("cus-known");
    order.verify(lifecycle).targetForTenant("client-fallback", "sub-missing");
    assertEquals(PERIOD_END, appliedOutcome(lifecycle, target, null).dueAt());
    verify(eventStore).markApplied("evt-fallback");
  }

  @Test
  public void aTenantWithNoRowIsWrittenThroughItsPreferenceTarget() throws Exception {
    // The preference route is chosen by the lifecycle service; the servlet just hands the target
    // back, with the event's created instant for the ETGO_SubscriptionEventAt watermark.
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-pref");
    when(requestStore.findByStripeSubscription("sub-pref")).thenReturn(purchase);
    LifecycleTarget target = preferenceTarget("client-pref");
    when(lifecycle.targetForTenant("client-pref", "sub-pref")).thenReturn(target);
    when(lifecycle.applySubscriptionEvent(same(target), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-pref",
        "invoice.payment_failed", paymentFailedEventCreated("sub-pref", "cus-pref", CREATED));

    assertNull(target.subscription());
    appliedOutcome(lifecycle, target, Instant.ofEpochSecond(CREATED));
    verify(eventStore).markApplied("evt-pref");
  }

  @Test
  public void unknownSubscriptionAndCustomerAreIgnoredWithoutStatusPersistence() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(requestStore.findByStripeSubscription("sub-unknown")).thenReturn(null);
    when(requestStore.findByStripeCustomer("cus-unknown")).thenReturn(null);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-unresolved",
        "invoice.payment_failed", paymentFailedEvent("sub-unknown", "cus-unknown"));

    verify(eventStore).markIgnored("evt-unresolved", "unresolved subscription");
    verify(lifecycle, never()).targetForTenant(anyString(), any());
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    verify(eventStore, never()).markApplied(anyString());
  }

  @Test
  public void purchaseWithoutCreatedClientIsIgnoredWithoutStatusPersistence() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getCreatedClient()).thenReturn(null);
    when(requestStore.findByStripeSubscription("sub-no-client")).thenReturn(purchase);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-no-client",
        "invoice.payment_failed", paymentFailedEvent("sub-no-client", "cus-no-client"));

    verify(eventStore).markIgnored("evt-no-client", "unresolved subscription");
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    verify(eventStore, never()).markApplied(anyString());
  }

  // ===================== ignored targets carry their audit reason ==============================

  @Test
  public void anEventForAnotherSubscriptionIsIgnoredWithThatReason() throws Exception {
    assertIgnoredTarget("event for another subscription");
  }

  @Test
  public void aLateEventOfAClosedSubscriptionIsIgnoredWithThatReason() throws Exception {
    assertIgnoredTarget("subscription closed");
  }

  @Test
  public void anEventWithNoOpenRowToWriteIsIgnoredWithThatReason() throws Exception {
    assertIgnoredTarget("no open subscription row");
  }

  private void assertIgnoredTarget(String reason) throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    SubscriptionLifecycleApplier applier = mock(SubscriptionLifecycleApplier.class);
    when(applier.evaluate(eq("invoice.payment_failed"), any(JSONObject.class))).thenReturn(
        SubscriptionEventOutcome.apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
            PERIOD_END));
    CheckoutRequest purchase = purchaseWithClient("client-ignored");
    when(requestStore.findByStripeSubscription("sub-ignored")).thenReturn(purchase);
    when(lifecycle.targetForTenant("client-ignored", "sub-ignored"))
        .thenReturn(LifecycleTarget.ignored("client-ignored", reason));

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    setField(servlet, "subscriptionLifecycleApplier", applier);
    invokeLifecycle(servlet, "evt-ignored", "invoice.payment_failed",
        paymentFailedEvent("sub-ignored", "cus-ignored"));

    verify(eventStore).markIgnored("evt-ignored", reason);
    // Not even evaluated against a state: an ignored target has none worth deciding against.
    verify(applier, never()).evaluate(anyString(), any(JSONObject.class), any());
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    verify(eventStore, never()).markApplied(anyString());
  }

  @Test
  public void applierIgnoreIsRecordedVerbatimWithoutLookupOrStatusPersistence() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    SubscriptionLifecycleApplier applier = mock(SubscriptionLifecycleApplier.class);
    String reason = "missing period end";
    when(applier.evaluate(eq("invoice.payment_failed"), any(JSONObject.class)))
        .thenReturn(SubscriptionEventOutcome.ignore(reason));

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    setField(servlet, "subscriptionLifecycleApplier", applier);
    invokeLifecycle(servlet, "evt-applier-ignore", "invoice.payment_failed",
        paymentFailedEvent("sub-ignore", "cus-ignore"));

    verify(eventStore).markIgnored("evt-applier-ignore", reason);
    verifyNoInteractions(requestStore, lifecycle);
    verify(eventStore, never()).markApplied(anyString());
  }

  @Test
  public void duplicateEventIdThroughWebhookHandlerAppliesOnlyOnce() throws Exception {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    LifecycleTarget row = rowTarget("client-replay", SubscriptionLifecycleApplier.StoredState.NONE);
    when(lifecycle.targetForSubscription("sub-replay")).thenReturn(row);
    when(lifecycle.applySubscriptionEvent(same(row), any(), any())).thenReturn(true);

    AtomicInteger claims = new AtomicInteger();
    CheckoutWebhookProcessor processor = new CheckoutWebhookProcessor(
        eventId -> claims.incrementAndGet() == 1, 300);
    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    setField(servlet, "checkoutWebhookProcessor", processor);
    String payload = webhookPayload("evt-replay", "invoice.payment_failed", "sub-replay",
        "cus-replay");

    ResponseCapture first = deliver(servlet, payload);
    ResponseCapture second = deliver(servlet, payload);

    assertEquals(200, first.status);
    assertEquals(200, second.status);
    assertEquals(2, claims.get());
    verify(lifecycle).applySubscriptionEvent(same(row), any(), any());
    verify(eventStore).markApplied("evt-replay");
  }

  // ===================== ETP-5443 REVIEW fixes, on the ETP-5047 target =====================

  /** API 2025-03-31 and later: the invoice names its subscription only under parent. */
  @Test
  public void invoiceWithSubscriptionOnlyUnderParentResolvesThroughIt() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-parent");
    when(requestStore.findByStripeSubscription("sub-parent")).thenReturn(purchase);
    LifecycleTarget target = preferenceTarget("client-parent");
    when(lifecycle.targetForTenant("client-parent", "sub-parent")).thenReturn(target);
    when(lifecycle.applySubscriptionEvent(same(target), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-parent",
        "invoice.payment_failed", new JSONObject(
        "{\"created\":" + CREATED + ",\"data\":{\"object\":{\"customer\":\"cus-parent\","
            + "\"period_end\":1793750400,\"parent\":{\"subscription_details\":{"
            + "\"subscription\":\"sub-parent\"}}}}}"));

    verify(lifecycle).targetForSubscription("sub-parent");
    verify(requestStore).findByStripeSubscription("sub-parent");
    verify(requestStore, never()).findByStripeCustomer(anyString());
    assertEquals(PERIOD_END,
        appliedOutcome(lifecycle, target, Instant.ofEpochSecond(CREATED)).dueAt());
    verify(eventStore).markApplied("evt-parent");
  }

  @Test
  public void eventOlderThanTheTargetsWatermarkIsIgnoredAsStaleWithoutWriting() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.targetForSubscription("sub-stale")).thenReturn(rowTarget("client-stale",
        new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null,
            Instant.ofEpochSecond(CREATED + 60L))));

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-stale",
        "invoice.payment_failed", paymentFailedEventCreated("sub-stale", "cus-stale", CREATED));

    verify(eventStore).markIgnored("evt-stale", "stale event");
    verify(lifecycle, never()).applySubscriptionEvent(any(), any(), any());
    verify(lifecycle, never()).recordSubscriptionEventAt(anyString(), any());
    verify(eventStore, never()).markApplied(anyString());
  }

  /** The stored authoritative due date wins over the subscription payload's period. */
  @Test
  public void subscriptionUpdateKeepsTheStoredPastDueDueDateAndCarriesThePeriod()
      throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    Instant stored = PERIOD_END.minusSeconds(86400L);
    LifecycleTarget row = rowTarget("client-keep", new SubscriptionLifecycleApplier.StoredState(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, stored, null));
    when(lifecycle.targetForSubscription("sub-keep")).thenReturn(row);
    when(lifecycle.applySubscriptionEvent(same(row), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-keep",
        "customer.subscription.updated", new JSONObject(
        "{\"created\":" + CREATED + ",\"data\":{\"object\":{\"id\":\"sub-keep\","
            + "\"status\":\"past_due\",\"current_period_start\":1793750400,"
            + "\"current_period_end\":1796428800}}}"));

    SubscriptionEventOutcome outcome = appliedOutcome(lifecycle, row, Instant.ofEpochSecond(CREATED));
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(stored, outcome.dueAt());
    // ETP-5047 — the provider billing period rides along for CURRENT_PERIOD_START/END.
    assertEquals(Instant.ofEpochSecond(1793750400L), outcome.periodStart());
    assertEquals(Instant.ofEpochSecond(1796428800L), outcome.periodEnd());
    verify(eventStore).markApplied("evt-keep");
  }

  @Test
  public void appliedEventHandsItsCreatedInstantToTheWriteBeforeMarkingApplied() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    LifecycleTarget row = rowTarget("client-order", SubscriptionLifecycleApplier.StoredState.NONE);
    when(lifecycle.targetForSubscription("sub-order")).thenReturn(row);
    when(lifecycle.applySubscriptionEvent(same(row), any(), any())).thenReturn(true);

    invokeLifecycle(servlet(requestStore, eventStore, lifecycle), "evt-order",
        "invoice.payment_failed", paymentFailedEventCreated("sub-order", "cus-order", CREATED));

    InOrder order = inOrder(lifecycle, eventStore);
    order.verify(lifecycle).applySubscriptionEvent(same(row), any(),
        eq(Instant.ofEpochSecond(CREATED)));
    order.verify(eventStore).markApplied("evt-order");
    // The watermark is part of applySubscriptionEvent now; the servlet never writes it itself.
    verify(lifecycle, never()).recordSubscriptionEventAt(anyString(), any());
  }

  @Test
  public void failedStatusWriteRollsBackThenMarksFailed() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    LifecycleTarget row = rowTarget("client-fail", SubscriptionLifecycleApplier.StoredState.NONE);
    when(lifecycle.targetForSubscription("sub-fail")).thenReturn(row);
    when(lifecycle.applySubscriptionEvent(same(row), any(), any())).thenReturn(false);
    OBDal dal = mock(OBDal.class);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      invokeLifecycle(servlet, "evt-fail", "invoice.payment_failed",
          paymentFailedEventCreated("sub-fail", "cus-fail", CREATED));
    }

    InOrder order = inOrder(dal, eventStore);
    order.verify(dal).rollbackAndClose();
    order.verify(eventStore).markFailed("evt-fail", "Could not store the subscription projection");
    verify(eventStore, never()).markApplied(anyString());
    verify(lifecycle, never()).recordSubscriptionEventAt(anyString(), any());
  }

  @Test
  public void watermarkWriteFailureThroughWebhookAnswers500AndMarksFailed() throws Exception {
    // On the preference route applySubscriptionEvent lets a failed watermark write propagate
    // (TenantEnvironmentLifecycleServiceSubscriptionRowTest pins that); here the webhook must turn
    // it into a 500 so the provider redelivers, after rolling the session back.
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-throw");
    when(requestStore.findByStripeSubscription("sub-throw")).thenReturn(purchase);
    LifecycleTarget target = preferenceTarget("client-throw");
    when(lifecycle.targetForTenant("client-throw", "sub-throw")).thenReturn(target);
    when(lifecycle.applySubscriptionEvent(same(target), any(), any())).thenThrow(
        new IllegalStateException("Client not found while recording a subscription event"));
    OBDal dal = mock(OBDal.class);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    setField(servlet, "checkoutWebhookProcessor", new CheckoutWebhookProcessor(eventId -> true,
        300));
    String payload = "{\"id\":\"evt-throw\",\"type\":\"invoice.payment_failed\","
        + "\"created\":" + CREATED + ",\"data\":{\"object\":{\"subscription\":\"sub-throw\","
        + "\"customer\":\"cus-throw\",\"period_end\":1793750400}}}";
    ResponseCapture capture;
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      capture = deliver(servlet, payload);
    }

    assertEquals(500, capture.status);
    InOrder order = inOrder(dal, eventStore);
    order.verify(dal).rollbackAndClose();
    order.verify(eventStore).markFailed(eq("evt-throw"),
        startsWith("Handler failed while applying the event: java.lang.IllegalStateException"));
    verify(eventStore, never()).markApplied(anyString());
  }

  /**
   * The lifecycle event runs under an explicit System context (the webhook is matched before the
   * authentication chain and has none), and that context must be taken away again even when the
   * write throws. A System context left on the request thread would make whatever runs next on it
   * silently privileged.
   */
  @Test
  public void aThrowingLifecycleWriteStillLeavesTheWebhookThreadWithoutAContext() throws Exception {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    LifecycleTarget row = rowTarget("client-ctx", SubscriptionLifecycleApplier.StoredState.NONE);
    when(lifecycle.targetForSubscription("sub-ctx")).thenReturn(row);
    when(lifecycle.applySubscriptionEvent(same(row), any(), any()))
        .thenThrow(new IllegalStateException("subscription row write failed"));
    OBDal dal = mock(OBDal.class);
    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    setField(servlet, "checkoutWebhookProcessor", new CheckoutWebhookProcessor(eventId -> true,
        300));
    String payload = "{\"id\":\"evt-ctx\",\"type\":\"invoice.payment_failed\","
        + "\"created\":" + CREATED + ",\"data\":{\"object\":{\"subscription\":\"sub-ctx\","
        + "\"customer\":\"cus-ctx\",\"period_end\":1793750400}}}";

    // The thread's context, as OBContext would hold it: none on arrival.
    AtomicReference<OBContext> threadContext = new AtomicReference<>();
    OBContext systemContext = mock(OBContext.class);
    List<String> calls = new ArrayList<>();
    obContext.when(OBContext::getOBContext).thenAnswer(invocation -> threadContext.get());
    obContext.when(() -> OBContext.setOBContext("0", "0", "0", "0"))
        .thenAnswer(invocation -> {
          calls.add("system");
          threadContext.set(systemContext);
          return null;
        });
    obContext.when(() -> OBContext.setOBContext(org.mockito.ArgumentMatchers.<OBContext>any()))
        .thenAnswer(invocation -> {
          OBContext restored = invocation.getArgument(0);
          calls.add(restored == null ? "restore(null)" : "restore(context)");
          threadContext.set(restored);
          return null;
        });
    obContext.when(OBContext::restorePreviousMode)
        .thenAnswer(invocation -> calls.add("restorePreviousMode"));
    ResponseCapture capture;
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      capture = deliver(servlet, payload);
    }

    assertEquals(500, capture.status);
    verify(eventStore).markFailed(eq("evt-ctx"),
        startsWith("Handler failed while applying the event: java.lang.IllegalStateException"));
    verify(eventStore, never()).markApplied(anyString());
    assertTrue("the lifecycle must have run as System: " + calls, calls.contains("system"));
    assertEquals("admin mode is left, then the (absent) caller context restored",
        List.of("restorePreviousMode", "restore(null)"),
        calls.subList(calls.size() - 2, calls.size()));
    assertNull("no System context may survive the failure on the webhook thread",
        threadContext.get());
  }

  // ===================== charge.dispute.created: alert only (ETP-5047) =====================

  @Test
  public void aDisputeIsRecordedAppliedWithAWarnAndTouchesNoSubscription() throws Exception {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    setField(servlet, "checkoutWebhookProcessor", new CheckoutWebhookProcessor(eventId -> true,
        300));
    String payload = "{\"id\":\"evt-dispute\",\"type\":\"charge.dispute.created\","
        + "\"created\":" + CREATED + ",\"data\":{\"object\":{\"id\":\"dp_123\","
        + "\"charge\":\"ch_456\",\"payment_intent\":{\"id\":\"pi_789\"},\"amount\":4900,"
        + "\"currency\":\"eur\",\"reason\":\"fraudulent\",\"customer\":\"cus-dispute\","
        + "\"evidence\":{\"customer_email_address\":\"victim@example.com\"}}}}";

    TestLogCapture warnings = TestLogCapture.attachTo(EtendoGoJwtServlet.class, Level.WARN);
    ResponseCapture capture;
    try {
      capture = deliver(servlet, payload);
    } finally {
      warnings.detach();
    }

    assertEquals(200, capture.status);
    verify(eventStore).markApplied("evt-dispute");
    verify(eventStore, never()).markIgnored(anyString(), anyString());
    verifyNoInteractions(lifecycle, requestStore);
    List<String> alerts = warnings.messagesAt(Level.WARN);
    assertEquals(alerts.toString(), 1, alerts.size());
    String alert = alerts.get(0);
    for (String expected : List.of("evt-dispute", "charge.dispute.created", "dp_123", "ch_456",
        "pi_789", "4900", "eur", "fraudulent")) {
      assertTrue("the alert names " + expected + ": " + alert, alert.contains(expected));
    }
    // Ids, amount and reason only: never customer personal data.
    assertTrue(alert, !alert.contains("victim@example.com"));
  }

  @Test
  public void aDisputeWithMissingFieldsStillAlertsWithPlaceholders() throws Exception {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    EtendoGoJwtServlet servlet = servlet(mock(CheckoutRequestStore.class), eventStore, lifecycle);
    setField(servlet, "checkoutWebhookProcessor", new CheckoutWebhookProcessor(eventId -> true,
        300));
    String payload = "{\"id\":\"evt-dispute-bare\",\"type\":\"charge.dispute.created\","
        + "\"created\":" + CREATED + ",\"data\":{\"object\":{\"id\":\"dp_bare\","
        + "\"payment_intent\":null}}}";

    TestLogCapture warnings = TestLogCapture.attachTo(EtendoGoJwtServlet.class, Level.WARN);
    ResponseCapture capture;
    try {
      capture = deliver(servlet, payload);
    } finally {
      warnings.detach();
    }

    assertEquals(200, capture.status);
    verify(eventStore).markApplied("evt-dispute-bare");
    verifyNoInteractions(lifecycle);
    String alert = warnings.messagesAt(Level.WARN).get(0);
    assertTrue(alert, alert.contains("dp_bare"));
    // A JSON null reads as "-", never the literal word "null".
    assertTrue(alert, !alert.contains("null"));
  }

  // ===================== fixtures =====================

  /** Asserts one applySubscriptionEvent call on {@code target} and returns the outcome it got. */
  private static SubscriptionEventOutcome appliedOutcome(TenantEnvironmentLifecycleService lifecycle,
      LifecycleTarget target, Instant eventAt) {
    ArgumentCaptor<SubscriptionEventOutcome> outcome =
        ArgumentCaptor.forClass(SubscriptionEventOutcome.class);
    if (eventAt == null) {
      verify(lifecycle).applySubscriptionEvent(same(target), outcome.capture(), isNull());
    } else {
      verify(lifecycle).applySubscriptionEvent(same(target), outcome.capture(), eq(eventAt));
    }
    assertTrue(outcome.getValue().isApplied());
    return outcome.getValue();
  }

  private static LifecycleTarget rowTarget(String clientId,
      SubscriptionLifecycleApplier.StoredState state) {
    LifecycleTarget target = LifecycleTarget.row(clientId, mock(Subscription.class), state);
    assertSame(state, target.storedState());
    return target;
  }

  private static LifecycleTarget preferenceTarget(String clientId) {
    return LifecycleTarget.preferences(clientId, SubscriptionLifecycleApplier.StoredState.NONE);
  }

  private static JSONObject paymentFailedEventCreated(String subscriptionId, String customerId,
      long created) throws Exception {
    JSONObject event = paymentFailedEvent(subscriptionId, customerId);
    event.put("created", created);
    return event;
  }

  private static EtendoGoJwtServlet servlet(CheckoutRequestStore requestStore,
      BillingEventStore eventStore, TenantEnvironmentLifecycleService lifecycle) throws Exception {
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
    setField(servlet, "checkoutRequestStore", requestStore);
    setField(servlet, "billingEventStore", eventStore);
    setField(servlet, "tenantEnvironmentLifecycleService", lifecycle);
    return servlet;
  }

  private static CheckoutRequest purchaseWithClient(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getCreatedClient()).thenReturn(client);
    return purchase;
  }

  private static JSONObject paymentFailedEvent(String subscriptionId, String customerId)
      throws Exception {
    return new JSONObject("{\"data\":{\"object\":{"
        + "\"subscription\":\"" + subscriptionId + "\","
        + "\"customer\":\"" + customerId + "\","
        + "\"period_end\":1793750400}}}");
  }

  private static void invokeLifecycle(EtendoGoJwtServlet servlet, String eventId, String type,
      JSONObject event) throws Exception {
    Method method = EtendoGoJwtServlet.class.getDeclaredMethod("applySubscriptionLifecycle",
        String.class, String.class, JSONObject.class);
    method.setAccessible(true);
    method.invoke(servlet, eventId, type, event);
  }

  private static void setField(EtendoGoJwtServlet servlet, String name, Object value)
      throws Exception {
    Field field = EtendoGoJwtServlet.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(servlet, value);
  }

  private static String webhookPayload(String eventId, String type, String subscriptionId,
      String customerId) {
    return "{\"id\":\"" + eventId + "\",\"type\":\"" + type
        + "\",\"data\":{\"object\":{\"subscription\":\"" + subscriptionId
        + "\",\"customer\":\"" + customerId + "\",\"period_end\":1793750400}}}";
  }

  private static ResponseCapture deliver(EtendoGoJwtServlet servlet, String payload)
      throws Exception {
    long timestamp = Instant.now().getEpochSecond();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn("/checkout/webhook");
    when(request.getContentType()).thenReturn("application/json");
    when(request.getHeader("Stripe-Signature")).thenReturn(sign(payload, timestamp));
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(payload)));
    ResponseCapture capture = mockResponse();
    servlet.doPost(request, capture.response);
    return capture;
  }

  private static String sign(String payload, long timestamp) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte value : digest) {
      hex.append(String.format("%02x", value));
    }
    return "t=" + timestamp + ",v1=" + hex;
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response);
    doAnswer(invocation -> {
      capture.status = invocation.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(invocation -> null).when(response).setContentType(anyString());
    doAnswer(invocation -> null).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  private static final class ResponseCapture {
    private final HttpServletResponse response;
    private int status;

    private ResponseCapture(HttpServletResponse response) {
      this.response = response;
    }
  }
}
