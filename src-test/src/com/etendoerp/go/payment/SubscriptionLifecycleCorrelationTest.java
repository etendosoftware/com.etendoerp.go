/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.rest.EtendoGoJwtServlet;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/** Contract tests for correlating subscription webhooks to the owned environment. */
public class SubscriptionLifecycleCorrelationTest {

  private static final String WEBHOOK_SECRET_PROPERTY = "etendo.go.checkout.webhook.secret";
  private static final String WEBHOOK_SECRET = "whsec_etp5443_correlation_test";
  private static final Instant PERIOD_END = Instant.ofEpochSecond(1793750400L);

  @After
  public void clearWebhookSecret() {
    System.clearProperty(WEBHOOK_SECRET_PROPERTY);
  }

  @Test
  public void knownSubscriptionUpdatesStatusAndMarksEventApplied() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-1");
    when(requestStore.findByStripeSubscription("sub-known")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-1",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(true);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-known", "invoice.payment_failed",
        paymentFailedEvent("sub-known", "cus-known"));

    verify(lifecycle).updateSubscriptionStatus("client-1",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END);
    verify(eventStore).markApplied("evt-known");
    verify(eventStore, never()).markIgnored(anyString(), anyString());
  }

  @Test
  public void unknownSubscriptionFallsBackToKnownCustomer() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-fallback");
    when(requestStore.findByStripeSubscription("sub-missing")).thenReturn(null);
    when(requestStore.findByStripeCustomer("cus-known")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-fallback",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(true);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-fallback", "invoice.payment_failed",
        paymentFailedEvent("sub-missing", "cus-known"));

    verify(requestStore).findByStripeSubscription("sub-missing");
    verify(requestStore).findByStripeCustomer("cus-known");
    verify(lifecycle).updateSubscriptionStatus("client-fallback",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END);
    verify(eventStore).markApplied("evt-fallback");
  }

  @Test
  public void unknownSubscriptionAndCustomerAreIgnoredWithoutStatusPersistence() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(requestStore.findByStripeSubscription("sub-unknown")).thenReturn(null);
    when(requestStore.findByStripeCustomer("cus-unknown")).thenReturn(null);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-unresolved", "invoice.payment_failed",
        paymentFailedEvent("sub-unknown", "cus-unknown"));

    verify(eventStore).markIgnored("evt-unresolved", "unresolved subscription");
    verify(lifecycle, never()).updateSubscriptionStatus(anyString(), any(), any());
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

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-no-client", "invoice.payment_failed",
        paymentFailedEvent("sub-no-client", "cus-no-client"));

    verify(eventStore).markIgnored("evt-no-client", "unresolved subscription");
    verify(lifecycle, never()).updateSubscriptionStatus(anyString(), any(), any());
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
    verify(requestStore, never()).findByStripeSubscription(anyString());
    verify(requestStore, never()).findByStripeCustomer(anyString());
    verify(lifecycle, never()).updateSubscriptionStatus(anyString(), any(), any());
    verify(eventStore, never()).markApplied(anyString());
  }

  @Test
  public void duplicateEventIdThroughWebhookHandlerAppliesOnlyOnce() throws Exception {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-replay");
    when(requestStore.findByStripeSubscription("sub-replay")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-replay",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(true);

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
    verify(lifecycle).updateSubscriptionStatus("client-replay",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END);
    verify(eventStore).markApplied("evt-replay");
  }

  // ===================== ETP-5443 REVIEW fixes =====================

  private static final long CREATED = 1793800000L;

  /** API 2025-03-31 and later: the invoice names its subscription only under parent. */
  @Test
  public void invoiceWithSubscriptionOnlyUnderParentResolvesThroughIt() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-parent");
    when(requestStore.findByStripeSubscription("sub-parent")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-parent",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(true);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-parent", "invoice.payment_failed", new JSONObject(
        "{\"created\":" + CREATED + ",\"data\":{\"object\":{\"customer\":\"cus-parent\","
            + "\"period_end\":1793750400,\"parent\":{\"subscription_details\":{"
            + "\"subscription\":\"sub-parent\"}}}}}"));

    verify(requestStore).findByStripeSubscription("sub-parent");
    verify(requestStore, never()).findByStripeCustomer(anyString());
    verify(lifecycle).updateSubscriptionStatus("client-parent",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END);
    verify(eventStore).markApplied("evt-parent");
  }

  @Test
  public void eventOlderThanTheStoredOneIsIgnoredAsStaleWithoutWriting() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-stale");
    when(requestStore.findByStripeSubscription("sub-stale")).thenReturn(purchase);
    when(lifecycle.readSubscriptionState("client-stale")).thenReturn(
        new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null,
            Instant.ofEpochSecond(CREATED + 60L)));

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-stale", "invoice.payment_failed",
        paymentFailedEventCreated("sub-stale", "cus-stale", CREATED));

    verify(eventStore).markIgnored("evt-stale", "stale event");
    verify(lifecycle, never()).updateSubscriptionStatus(anyString(), any(), any());
    verify(lifecycle, never()).recordSubscriptionEventAt(anyString(), any());
    verify(eventStore, never()).markApplied(anyString());
  }

  /** The stored authoritative due date wins over the subscription payload's period. */
  @Test
  public void subscriptionUpdateKeepsTheStoredPastDueDueDate() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    Instant stored = PERIOD_END.minusSeconds(86400L);
    CheckoutRequest purchase = purchaseWithClient("client-keep");
    when(requestStore.findByStripeSubscription("sub-keep")).thenReturn(purchase);
    when(lifecycle.readSubscriptionState("client-keep")).thenReturn(
        new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, stored, null));
    when(lifecycle.updateSubscriptionStatus("client-keep",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, stored)).thenReturn(true);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-keep", "customer.subscription.updated", new JSONObject(
        "{\"created\":" + CREATED + ",\"data\":{\"object\":{\"id\":\"sub-keep\","
            + "\"status\":\"past_due\",\"current_period_start\":1793750400,"
            + "\"current_period_end\":1796428800}}}"));

    verify(lifecycle).updateSubscriptionStatus("client-keep",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, stored);
    verify(eventStore).markApplied("evt-keep");
  }

  @Test
  public void appliedEventRecordsItsCreatedInstantBeforeMarkingApplied() throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-order");
    when(requestStore.findByStripeSubscription("sub-order")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-order",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(true);

    EtendoGoJwtServlet servlet = servlet(requestStore, eventStore, lifecycle);
    invokeLifecycle(servlet, "evt-order", "invoice.payment_failed",
        paymentFailedEventCreated("sub-order", "cus-order", CREATED));

    InOrder order = inOrder(lifecycle, eventStore);
    order.verify(lifecycle).updateSubscriptionStatus("client-order",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END);
    order.verify(lifecycle).recordSubscriptionEventAt("client-order",
        Instant.ofEpochSecond(CREATED));
    order.verify(eventStore).markApplied("evt-order");
  }

  @Test
  public void failedStatusWriteRollsBackThenMarksFailedWithoutRecordingTheEvent()
      throws Exception {
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-fail");
    when(requestStore.findByStripeSubscription("sub-fail")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-fail",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(false);
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
  public void eventInstantWriteFailureThroughWebhookAnswers500AndMarksFailed() throws Exception {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, WEBHOOK_SECRET);
    CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    BillingEventStore eventStore = mock(BillingEventStore.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    CheckoutRequest purchase = purchaseWithClient("client-throw");
    when(requestStore.findByStripeSubscription("sub-throw")).thenReturn(purchase);
    when(lifecycle.updateSubscriptionStatus("client-throw",
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, PERIOD_END)).thenReturn(true);
    doThrow(new IllegalStateException("Client not found while recording a subscription event"))
        .when(lifecycle).recordSubscriptionEventAt("client-throw", Instant.ofEpochSecond(CREATED));
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
