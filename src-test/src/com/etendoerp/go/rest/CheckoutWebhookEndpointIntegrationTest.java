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

package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.CheckoutWebhookProcessor;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-5045 — the webhook endpoint end to end: a signed HTTP delivery in, an answer on the wire and
 * a committed audit row out.
 *
 * <p><b>Why this class exists.</b> {@code CheckoutWebhookProcessorTest} pins the claim protocol,
 * {@code BillingEventStoreIntegrationTest} pins the durable idempotency gate and
 * {@code CheckoutRequestStoreIntegrationTest} pins the payment record — but until now nothing
 * exercised {@code handleCheckoutWebhook}, the method that sequences all three and decides the
 * status code. That seam carried the entire "a paid customer gets provisioned" promise and was
 * verified only by replaying Stripe events by hand
 * ({@code schema_forge/docs/stripe-local-testing.md}). A regression in it — the wrong branch
 * marking a row {@code APPLIED}, a duplicate applying twice, a failure answered 200 — would pass
 * every other suite green.
 *
 * <p><b>What is real and what is not.</b> The stores, the processor and the database are real: the
 * assertions below read committed rows with native SQL. Only the HTTP envelope is a double, built
 * with Mockito exactly as the sibling {@code EtendoGoJwtServletTest} does. The module's
 * {@code StubHttpServletRequest} is package-private to {@code com.etendoerp.go.schemaforge} and
 * hardcodes a NEO request URI, and {@code SyntheticHttpServletRequest} models parameters and
 * session attributes but carries no request body — neither can deliver a signed payload to
 * {@code doPost}, so the local Mockito idiom is the harness used here.
 *
 * <p>The signature is genuine HMAC-SHA256 over {@code "<timestamp>.<payload>"}, the same
 * construction Stripe uses and the same helper shape as {@code CheckoutWebhookProcessorTest}. The
 * signing secret is injected through the system property {@code CheckoutConfiguration} reads
 * first, so this class never depends on how the machine is configured.
 *
 * <p><b>Cleanup is explicit, not a rollback</b>, for the same reason as the store specs: every
 * write path commits. Fixtures carry the {@code etp5045-whk-} / {@code evt_etp5045whk_} markers
 * and are deleted in {@link #cleanUp()} in FK order. The environment's own live Stripe replay rows
 * are never matched by those markers and are left alone.
 */
public class CheckoutWebhookEndpointIntegrationTest extends OBBaseTest {

  /** The property {@code CheckoutConfiguration.webhookSecret()} consults before anything else. */
  private static final String WEBHOOK_SECRET_PROPERTY = "etendo.go.checkout.webhook.secret";
  private static final String SECRET = "whsec_etp5045_endpoint_test";

  private static final String EVENT_MARKER = "evt_etp5045whk_";
  private static final String REQUEST_MARKER = "etp5045-whk-";

  private static final String ZERO = "0";
  private static final String WEBHOOK_PATH = "/checkout/webhook";

  private static final String COMPLETED = "checkout.session.completed";
  private static final String ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";
  private static final String INVOICE_PAID = "invoice.paid";
  // ETP-5443 added invoice.paid to SUBSCRIPTION_EVENT_TYPES, so it no longer proves the
  // "unhandled event type" branch — this must stay a type neither CHECKOUT_PAID_EVENT_TYPES nor
  // SUBSCRIPTION_EVENT_TYPES (EtendoGoJwtServlet) ever claims, or the fix silently invalidates it.
  private static final String GENUINELY_UNHANDLED = "customer.created";

  private static final String APPLIED = "APPLIED";
  private static final String IGNORED = "IGNORED";
  private static final String FAILED = "FAILED";

  private static final String STATUS_CREATED = "CREATED";
  private static final String STATUS_PAID = "PAID";

  private static final String ENVIRONMENT = "ETP-5045 Webhook Environment";

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
  private final CheckoutRequestStore fixtureStore = new CheckoutRequestStore();

  @Before
  public void useAKnownWebhookSecret() {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, SECRET);
  }

  /**
   * Same shape as the store integration tests: the stores install the system context and never
   * restore the caller's, so the thread context is cleared rather than unwound.
   */
  @After
  public void cleanUp() {
    try {
      System.clearProperty(WEBHOOK_SECRET_PROPERTY);
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 1 — an accepted payment event is applied, once, and moves the checkout request
  // ---------------------------------------------------------------------------------------------

  /**
   * The happy path of the whole subsystem, asserted on both sides of the boundary at once: the
   * provider gets the acknowledgement it needs to stop retrying, and the two tables that the
   * paywall and the operator read afterwards say the payment happened.
   */
  @Test
  public void testACompletedCheckoutIsAppliedAndAdvancesTheRequestToPaid() throws Exception {
    String email = newEmail("completed");
    String requestId = createRequest(email);
    String requestPk = rawRequestPk(requestId);
    String eventId = newEventId();

    ResponseCapture response = deliver(paidEvent(eventId, COMPLETED, requestId, email));

    assertEquals(200, response.status);
    assertTrue("The provider must be acknowledged, or it keeps retrying a payment already applied",
        new JSONObject(response.body()).getBoolean("received"));
    assertEquals(APPLIED, rawEvent(eventId, "EVENT_RESULT"));
    assertNotNull(rawEventTimestamp(eventId, "PROCESSED_AT"));
    assertNull("An applied event has nothing to explain", rawEvent(eventId, "FAILURE_REASON"));
    assertEquals("The audit row must point at the request it paid for", requestPk,
        rawEvent(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertEquals(STATUS_PAID, rawRequest(requestId, "CHECKOUT_STATUS"));
    assertNotNull("PAID_AT is what the paywall staleness thresholds are measured against",
        rawRequestTimestamp(requestId, "PAID_AT"));
    assertEquals("The join keys for every later subscription event arrive only on this one event",
        "cus_" + requestId, rawRequest(requestId, "STRIPE_CUSTOMER_ID"));
    assertEquals("sub_" + requestId, rawRequest(requestId, "STRIPE_SUBSCRIPTION_ID"));
  }

  /**
   * Delayed-notification payment methods confirm through
   * {@code checkout.session.async_payment_succeeded} instead. It is a payment like any other and
   * must travel exactly the same path — a type list that forgot it would leave those customers
   * charged and unprovisioned.
   */
  @Test
  public void testTheAsyncPaymentVariantIsAppliedOnTheSameFooting() throws Exception {
    String email = newEmail("async");
    String requestId = createRequest(email);
    String eventId = newEventId();

    ResponseCapture response = deliver(paidEvent(eventId, ASYNC_SUCCEEDED, requestId, email));

    assertEquals(200, response.status);
    assertTrue(new JSONObject(response.body()).getBoolean("received"));
    assertEquals(APPLIED, rawEvent(eventId, "EVENT_RESULT"));
    assertEquals(ASYNC_SUCCEEDED, rawEvent(eventId, "EVENT_TYPE"));
    assertEquals(STATUS_PAID, rawRequest(requestId, "CHECKOUT_STATUS"));
  }

  /**
   * <b>The headline acceptance criterion of ETP-5045, at the layer that actually faces Stripe.</b>
   *
   * <p>Stripe retries until it gets a 2xx and then keeps retrying on its own schedule after
   * network failures and deploys, so the same event id arrives repeatedly as a matter of course.
   * Both deliveries must be acknowledged — a non-2xx would make it retry harder — while the
   * payment is recorded exactly once. Before ETP-5045 the dedup state was a static map, so a
   * redelivery after a restart was applied a second time; the assertion that there is still only
   * one row, with the duplicate counted on it and {@code PAID_AT} unmoved, is what pins the fix.
   */
  @Test
  public void testARedeliveredEventIsAcknowledgedTwiceButAppliedOnce() throws Exception {
    String email = newEmail("redelivery");
    String requestId = createRequest(email);
    String eventId = newEventId();
    String payload = paidEvent(eventId, COMPLETED, requestId, email);

    // Counts how often the handler actually ran. Without this the spec would still pass if a
    // duplicate fell through into applyCheckoutEvent: the store's own monotonicity would keep
    // PAID_AT and the status intact, hiding the fact that the handler was invoked a second time.
    // That hiding is only safe while every handler stays idempotent, which is not a property the
    // wire contract may rely on — the claim is what must stop the second run.
    CountingCheckoutRequestStore counting =
        new CountingCheckoutRequestStore(servlet.checkoutRequestStore);
    servlet.checkoutRequestStore = counting;

    ResponseCapture first = deliver(payload);
    assertEquals(200, first.status);
    assertTrue(new JSONObject(first.body()).getBoolean("received"));
    Timestamp paidAt = rawRequestTimestamp(requestId, "PAID_AT");
    Timestamp processedAt = rawEventTimestamp(eventId, "PROCESSED_AT");
    assertNotNull("Sanity: the first delivery must have applied the payment", paidAt);

    ResponseCapture second = deliver(payload);

    assertEquals("A duplicate must still be acknowledged, or the provider retries harder", 200,
        second.status);
    assertTrue(new JSONObject(second.body()).getBoolean("received"));
    assertEquals("A redelivery must never add a second audit row", 1L, rawEventCount(eventId));
    assertEquals("The redelivery must be counted on the row that won the claim", 1L,
        rawEventDuplicateCount(eventId));
    assertEquals(APPLIED, rawEvent(eventId, "EVENT_RESULT"));
    assertEquals("The result of the first delivery must stand", processedAt,
        rawEventTimestamp(eventId, "PROCESSED_AT"));
    assertEquals("The payment must be recorded once, not once per delivery", paidAt,
        rawRequestTimestamp(requestId, "PAID_AT"));
    assertEquals(STATUS_PAID, rawRequest(requestId, "CHECKOUT_STATUS"));
    assertEquals("The handler must not run at all on a duplicate — the claim is what stops it, "
        + "not the idempotence of whatever the handler happens to call", 1, counting.calls);
  }

  /** Delegates to a real store while counting how often the webhook handler recorded a payment. */
  private static final class CountingCheckoutRequestStore extends CheckoutRequestStore {
    private final CheckoutRequestStore delegate;
    private int calls;

    CountingCheckoutRequestStore(CheckoutRequestStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean recordPaid(String requestId, String customer, String subscription) {
      calls++;
      return delegate.recordPaid(requestId, customer, subscription);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — acknowledged but deliberately not acted on
  // ---------------------------------------------------------------------------------------------

  /**
   * The branch added by the ETP-5045 review. An event whose correlation id names no checkout
   * request is ordinary traffic in a shared Stripe test account, but nothing can be recorded for
   * it — so the row must say {@code IGNORED}, not {@code APPLIED}. Marking it applied would put a
   * lie in the one place an operator goes to find out whether a payment landed, and would hide a
   * genuinely mis-routed webhook.
   */
  @Test
  public void testAnEventNamingAnUnknownCheckoutRequestIsIgnoredRatherThanApplied()
      throws Exception {
    String email = newEmail("unknown-request");
    String realRequestId = createRequest(email);
    String unknownRequestId = REQUEST_MARKER + "never-issued-" + UUID.randomUUID();
    String eventId = newEventId();

    ResponseCapture response = deliver(paidEvent(eventId, COMPLETED, unknownRequestId, email));

    assertEquals("Nothing can be retried into existence, so the delivery is still acknowledged",
        200, response.status);
    assertTrue(new JSONObject(response.body()).getBoolean("received"));
    assertEquals("Nothing was recorded, so the row must not claim otherwise", IGNORED,
        rawEvent(eventId, "EVENT_RESULT"));
    assertEquals("unknown checkout request", rawEvent(eventId, "FAILURE_REASON"));
    assertNull("There is no request to link to", rawEvent(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertEquals("The correlation id is still recorded, so the mis-route is diagnosable",
        unknownRequestId, rawEvent(eventId, "REQUEST_ID"));
    assertEquals("A real request of the same account must not be touched", STATUS_CREATED,
        rawRequest(realRequestId, "CHECKOUT_STATUS"));
    assertNull(rawRequestTimestamp(realRequestId, "PAID_AT"));
  }

  /**
   * Stripe delivers every event type the endpoint is subscribed to, not only the two that confirm
   * a payment. The rest are acknowledged and annotated, so the audit trail says why nothing
   * happened instead of leaving a row stuck at {@code RECEIVED}.
   *
   * <p>Must use a type {@code applyCheckoutEvent} claims in neither {@code CHECKOUT_PAID_EVENT_TYPES}
   * nor {@code SUBSCRIPTION_EVENT_TYPES} — {@code invoice.paid} used to be that type, but ETP-5443
   * added subscription-lifecycle handling for it, so it now falls into
   * {@code testAnInvoicePaidEventThatCannotBeCorrelatedIsIgnoredAsUnresolvedSubscription}'s branch
   * ("unresolved subscription") instead of this one.
   */
  @Test
  public void testAnUnhandledEventTypeIsAcknowledgedAndAnnotated() throws Exception {
    String email = newEmail("unhandled");
    String requestId = createRequest(email);
    String eventId = newEventId();

    ResponseCapture response = deliver(paidEvent(eventId, GENUINELY_UNHANDLED, requestId, email));

    assertEquals(200, response.status);
    assertEquals(IGNORED, rawEvent(eventId, "EVENT_RESULT"));
    assertEquals("unhandled event type", rawEvent(eventId, "FAILURE_REASON"));
    assertEquals("An unhandled type must not confirm a payment", STATUS_CREATED,
        rawRequest(requestId, "CHECKOUT_STATUS"));
  }

  /**
   * ETP-5443 — {@code invoice.paid} is now a subscription-lifecycle type ({@code
   * SUBSCRIPTION_EVENT_TYPES}), so it is evaluated by {@code applySubscriptionLifecycle} rather
   * than falling straight into "unhandled event type". An invoice whose subscription and customer
   * match no stored checkout request (nothing was ever paid, so {@code STRIPE_SUBSCRIPTION}/
   * {@code STRIPE_CUSTOMER} are unset on every row) cannot be resolved to an environment, so it
   * must be acknowledged and ignored with its own, more specific reason — never silently applied,
   * and never mistaken for the generic "unhandled event type" case above.
   */
  @Test
  public void testAnInvoicePaidEventThatCannotBeCorrelatedIsIgnoredAsUnresolvedSubscription()
      throws Exception {
    String email = newEmail("unresolved-subscription");
    String realRequestId = createRequest(email);
    String uncorrelatedId = REQUEST_MARKER + UUID.randomUUID();
    String eventId = newEventId();

    ResponseCapture response = deliver(paidEvent(eventId, INVOICE_PAID, uncorrelatedId, email));

    assertEquals(200, response.status);
    assertTrue(new JSONObject(response.body()).getBoolean("received"));
    assertEquals(IGNORED, rawEvent(eventId, "EVENT_RESULT"));
    assertEquals("unresolved subscription", rawEvent(eventId, "FAILURE_REASON"));
    assertEquals("A real request of the same account must not be touched", STATUS_CREATED,
        rawRequest(realRequestId, "CHECKOUT_STATUS"));
  }

  /**
   * One of the two deliberate wire-contract changes of ETP-5045: a payment-type event with no
   * usable correlation used to be answered 400. By that point the claim has already committed, so
   * a 400 left a recorded event the provider stops retrying with nothing on the row to explain
   * it. It is now 200 plus an annotated {@code IGNORED} row.
   *
   * <p>All three shapes are the same case: no {@code data.object} at all, a blank
   * {@code request_id}, and a blank {@code account_email} — the handler needs both halves of the
   * correlation before it may record a payment against an account.
   */
  @Test
  public void testAPaidEventWithoutUsableCorrelationIsIgnoredRatherThanRefused() throws Exception {
    String email = newEmail("no-correlation");
    String noDataObject = newEventId();
    String blankRequestId = newEventId();
    String blankEmail = newEventId();

    ResponseCapture bare = deliver(("{\"id\":\"" + noDataObject + "\",\"type\":\""
        + COMPLETED + "\"}"));
    ResponseCapture noRequest = deliver(paidEvent(blankRequestId, COMPLETED, "   ", email));
    ResponseCapture noEmail = deliver(paidEvent(blankEmail, COMPLETED,
        REQUEST_MARKER + UUID.randomUUID(), "  "));

    for (ResponseCapture response : new ResponseCapture[] { bare, noRequest, noEmail }) {
      assertEquals("The claim already committed, so refusing it would lose the event", 200,
          response.status);
      assertTrue(new JSONObject(response.body()).getBoolean("received"));
    }
    for (String eventId : new String[] { noDataObject, blankRequestId, blankEmail }) {
      assertEquals("every shape must be recorded: " + eventId, 1L, rawEventCount(eventId));
      assertEquals(IGNORED, rawEvent(eventId, "EVENT_RESULT"));
      assertEquals("missing correlation metadata", rawEvent(eventId, "FAILURE_REASON"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 3 — refused before anything is recorded
  // ---------------------------------------------------------------------------------------------

  /**
   * The security property of the endpoint, asserted where it matters: it is public and
   * unauthenticated, so the signature is the only thing standing between an attacker and the
   * billing tables. An unverified payload must not reach the store at all — if it could claim an
   * event id, anyone able to guess one could burn it and make Stripe's genuine, correctly signed
   * delivery arrive as a duplicate, suppressing a real payment with no authentication whatsoever.
   *
   * <p>400 rather than 500 is deliberate: the provider does not retry a 400, and no retry of an
   * unsigned request could ever succeed.
   */
  @Test
  public void testAnUnsignedOrForgedDeliveryIsRefusedWithoutWritingAnything() throws Exception {
    String email = newEmail("forged");
    String requestId = createRequest(email);
    long now = Instant.now().getEpochSecond();

    String noSignature = newEventId();
    ResponseCapture missing = deliverWithSignature(paidEvent(noSignature, COMPLETED, requestId,
        email), null);

    String badSignature = newEventId();
    ResponseCapture forged = deliverWithSignature(paidEvent(badSignature, COMPLETED, requestId,
        email), "t=" + now + ",v1=deadbeef");

    String otherSecret = newEventId();
    String otherBody = paidEvent(otherSecret, COMPLETED, requestId, email);
    ResponseCapture wrongSecret = deliverWithSignature(otherBody,
        sign(otherBody, now, "whsec_someone_elses"));

    String tamperedId = newEventId();
    String signedBody = paidEvent(tamperedId, COMPLETED, requestId, email);
    String validSignature = sign(signedBody, now, SECRET);
    ResponseCapture tampered = deliverWithSignature(
        signedBody.replace(tamperedId, tamperedId + "_forged"), validSignature);

    String stale = newEventId();
    String staleBody = paidEvent(stale, COMPLETED, requestId, email);
    ResponseCapture expired = deliverWithSignature(staleBody,
        sign(staleBody, now - 3600, SECRET));

    for (ResponseCapture response : new ResponseCapture[] { missing, forged, wrongSecret, tampered,
        expired }) {
      assertEquals(400, response.status);
      assertEquals("INVALID_CHECKOUT_SIGNATURE",
          new JSONObject(response.body()).getJSONObject("error").getString("code"));
    }
    for (String eventId : new String[] { noSignature, badSignature, otherSecret, tamperedId,
        tamperedId + "_forged", stale }) {
      assertEquals("an unverified payload must never reach the store: " + eventId, 0L,
          rawEventCount(eventId));
    }
    assertEquals("and no payment may be recorded", STATUS_CREATED,
        rawRequest(requestId, "CHECKOUT_STATUS"));
  }

  /**
   * A correctly signed body still has to be a usable event. Without an id there is nothing to
   * deduplicate on, so accepting it would make the event replayable forever; without a type
   * nothing can be decided about it. Both are 400 — a retry cannot fix either — and neither may
   * leave a row.
   *
   * <p>A body that is not JSON at all is included because the endpoint is public: a malformed
   * payload must be an ordinary outcome, not a stack trace escaping to the container.
   */
  @Test
  public void testAnUnusablePayloadIsRefusedWithoutWritingAnything() throws Exception {
    long before = rawTotalEventCount();
    String noId = "{\"type\":\"" + COMPLETED + "\"}";
    String blankId = "{\"id\":\"   \",\"type\":\"" + COMPLETED + "\"}";
    String noType = "{\"id\":\"" + newEventId() + "\"}";
    String notJson = "not json at all";

    for (String body : new String[] { noId, blankId, noType, notJson }) {
      ResponseCapture response = deliver((body));

      assertEquals("expected 400 for " + body, 400, response.status);
      assertEquals("INVALID_CHECKOUT_PAYLOAD",
          new JSONObject(response.body()).getJSONObject("error").getString("code"));
    }
    assertEquals("an unusable payload must not have been recorded", before, rawTotalEventCount());
  }

  // ---------------------------------------------------------------------------------------------
  // Group 4 — a handler failure is retryable, and the retry applies the payment exactly once
  // ---------------------------------------------------------------------------------------------

  /**
   * The other deliberate wire-contract change, and the loop that makes a transient failure
   * self-healing.
   *
   * <p>A {@link RuntimeException} while applying the event used to escape to the container as an
   * HTML 500. It is now a structured 500 with the row marked {@code FAILED}: the status is what
   * makes the provider redeliver, and {@code FAILED} is what makes that redelivery a fresh claim
   * rather than a dismissed duplicate. Answering 200 here would be the worst outcome available —
   * the provider stops retrying a payment that was never applied.
   *
   * <p><b>How the failure is provoked.</b> The servlet's {@code checkoutRequestStore} is
   * package-private, which is the seam this test uses: it is replaced with a subclass whose
   * {@code recordPaid} throws, exactly as a database or provisioning fault would. No production
   * code is modified, and the real store is restored before the redelivery so the second half of
   * the spec runs against the genuine path.
   */
  @Test
  public void testAHandlerFailureIsRetryableAndTheRetryAppliesThePaymentOnce() throws Exception {
    String email = newEmail("handler-failure");
    String requestId = createRequest(email);
    String eventId = newEventId();
    String payload = paidEvent(eventId, COMPLETED, requestId, email);

    CheckoutRequestStore realStore = servlet.checkoutRequestStore;
    servlet.checkoutRequestStore = new CheckoutRequestStore() {
      @Override
      public boolean recordPaid(String id, String customer, String subscription) {
        throw new IllegalStateException("provisioning backend is unreachable");
      }
    };
    ResponseCapture failure;
    try {
      failure = deliver(payload);
    } finally {
      servlet.checkoutRequestStore = realStore;
    }

    assertEquals("Only a 5xx makes the provider redeliver the event", 500, failure.status);
    assertEquals("CHECKOUT_WEBHOOK_FAILED",
        new JSONObject(failure.body()).getJSONObject("error").getString("code"));
    assertEquals(FAILED, rawEvent(eventId, "EVENT_RESULT"));
    String reason = (String) rawEvent(eventId, "FAILURE_REASON");
    assertNotNull(reason);
    assertTrue("The reason must name the fault class: " + reason,
        reason.contains("IllegalStateException"));
    assertTrue("FAILURE_REASON must stay operationally safe — no provider message, which can "
        + "quote payload fragments: " + reason, !reason.contains("unreachable"));
    assertEquals("The payment must not have been recorded", STATUS_CREATED,
        rawRequest(requestId, "CHECKOUT_STATUS"));

    // Stripe's own retry, against the real store this time.
    ResponseCapture retry = deliver(payload);

    assertEquals(200, retry.status);
    assertTrue(new JSONObject(retry.body()).getBoolean("received"));
    assertEquals("A FAILED row must be re-claimed by the retry, not dismissed as a duplicate",
        APPLIED, rawEvent(eventId, "EVENT_RESULT"));
    assertNull("The re-claim clears the previous attempt's annotation",
        rawEvent(eventId, "FAILURE_REASON"));
    assertEquals("A re-claim is not a duplicate", 0L, rawEventDuplicateCount(eventId));
    assertEquals("Still one row per event id", 1L, rawEventCount(eventId));
    assertEquals(STATUS_PAID, rawRequest(requestId, "CHECKOUT_STATUS"));

    // And the self-healed event is now terminal: a third delivery changes nothing.
    ResponseCapture third = deliver(payload);
    assertEquals(200, third.status);
    assertEquals(APPLIED, rawEvent(eventId, "EVENT_RESULT"));
    assertEquals(1L, rawEventDuplicateCount(eventId));
  }

  /**
   * A failure <em>before</em> the claim commits is the other half of the same rule, and it is a
   * different code path: the servlet wraps {@code evaluate()} itself in a try/catch, because at
   * that point nothing has been recorded and there is no row to annotate. The only honest answer
   * is a 500 that makes the provider redeliver an event this side did not keep.
   *
   * <p>Both alternative shapes are silent failures: answering 200 would acknowledge an event that
   * was never recorded, and letting the exception escape would hand the container's HTML error
   * page to a provider that parses JSON.
   *
   * <p>Provoked by replacing the servlet's processor with one built over a store whose
   * {@code claim} throws — the same package-private seam the handler-failure spec uses, and again
   * without touching production code.
   */
  @Test
  public void testAFailureWhileClaimingIsA500WithNothingRecorded() throws Exception {
    String email = newEmail("claim-failure");
    String requestId = createRequest(email);
    String eventId = newEventId();
    long before = rawTotalEventCount();

    CheckoutWebhookProcessor realProcessor = servlet.checkoutWebhookProcessor;
    servlet.checkoutWebhookProcessor = new CheckoutWebhookProcessor(
        (CheckoutWebhookProcessor.EventStore) id -> {
          throw new IllegalStateException("the billing event table is unreachable");
        }, 300);
    ResponseCapture response;
    try {
      response = deliver(paidEvent(eventId, COMPLETED, requestId, email));
    } finally {
      servlet.checkoutWebhookProcessor = realProcessor;
    }

    assertEquals("A claim that failed must make the provider redeliver", 500, response.status);
    assertEquals("CHECKOUT_WEBHOOK_FAILED",
        new JSONObject(response.body()).getJSONObject("error").getString("code"));
    assertEquals("nothing may have been recorded", before, rawTotalEventCount());
    assertEquals(0L, rawEventCount(eventId));
    assertEquals("and no payment may be confirmed", STATUS_CREATED,
        rawRequest(requestId, "CHECKOUT_STATUS"));
  }

  // ---------------------------------------------------------------------------------------------
  // Delivery harness
  // ---------------------------------------------------------------------------------------------

  /**
   * Delivers a payload with a valid signature for the current instant.
   *
   * @param payload the raw body
   * @return what the servlet wrote
   */
  private ResponseCapture deliver(String payload) throws Exception {
    return deliverWithSignature(payload, sign(payload, Instant.now().getEpochSecond(), SECRET));
  }

  /**
   * Delivers one POST to {@code /checkout/webhook} through the servlet's real routing.
   *
   * @param payload raw request body
   * @param signature value of the {@code Stripe-Signature} header, or null to send none
   * @return what the servlet wrote
   */
  private ResponseCapture deliverWithSignature(String payload, String signature) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(WEBHOOK_PATH);
    when(request.getMethod()).thenReturn("POST");
    when(request.getContentType()).thenReturn("application/json");
    when(request.getHeader("Stripe-Signature")).thenReturn(signature);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(payload)));
    ResponseCapture capture = mockResponse();

    servlet.doPost(request, capture.response);

    return capture;
  }

  /**
   * Signs a payload the way Stripe does: HMAC-SHA256 over {@code "<timestamp>.<payload>"}.
   *
   * @param payload the raw body
   * @param timestamp epoch seconds carried inside the signed bytes
   * @param secret signing secret
   * @return the {@code Stripe-Signature} header value
   */
  private static String sign(String payload, long timestamp, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] bytes = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : bytes) {
      hex.append(String.format("%02x", b));
    }
    return "t=" + timestamp + ",v1=" + hex;
  }

  /**
   * Builds a provider event carrying the correlation a hosted checkout session sends back.
   *
   * @param eventId provider event id
   * @param type provider event type
   * @param requestId the {@code metadata.request_id} correlation
   * @param email the {@code metadata.account_email} the handler also requires
   * @return the raw JSON body
   */
  private String paidEvent(String eventId, String type, String requestId, String email) {
    return "{"
        + "\"id\":\"" + eventId + "\","
        + "\"type\":\"" + type + "\","
        + "\"data\":{\"object\":{"
        + "  \"id\":\"cs_" + eventId + "\","
        + "  \"customer\":\"cus_" + requestId + "\","
        + "  \"subscription\":\"sub_" + requestId + "\","
        + "  \"payment_status\":\"paid\","
        + "  \"metadata\":{\"request_id\":\"" + requestId + "\","
        + "               \"account_email\":\"" + email + "\"}"
        + "}}}";
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  private String newEventId() {
    return EVENT_MARKER + UUID.randomUUID().toString().replace("-", "");
  }

  private String newEmail(String label) {
    return REQUEST_MARKER + label + "-" + UUID.randomUUID().toString().replace("-", "")
        + "@example.test";
  }

  /**
   * Creates an account and a checkout request driven to {@code CREATED} — the state a request is
   * in when the browser has been sent to the provider and the webhook is what happens next.
   *
   * @param email the account e-mail, which is also the correlation the handler checks
   * @return the correlation id
   */
  private String createRequest(String email) {
    String accountId = createAccount(email);
    String requestId = REQUEST_MARKER + UUID.randomUUID().toString().replace("-", "");
    // No plan: this fixture exercises webhook correlation, which does not read one.
    fixtureStore.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(null, false, false, false, null));
    fixtureStore.recordSessionCreated(requestId, "cs_" + requestId);
    return requestId;
  }

  /**
   * Creates the {@code ETGO_ACCOUNT} row a checkout request needs, at client and organization
   * {@code 0} to match the store's own rows.
   *
   * @param email account e-mail
   * @return the new {@code ETGO_ACCOUNT_ID}
   */
  private String createAccount(String email) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Account account = OBProvider.getInstance().get(Account.class);
      account.setNewOBObject(true);
      account.setClient(OBDal.getInstance().get(Client.class, ZERO));
      account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
      account.setActive(true);
      account.setEmail(email);
      account.setName("ETP-5045 webhook endpoint fixture");
      account.setStatus("active");
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      return account.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers and cleanup (native SQL — never the first-level cache)
  // ---------------------------------------------------------------------------------------------

  private Object rawEvent(String eventId, String column) {
    return rawColumn("ETGO_BILLING_EVENT", "EVENT_ID", eventId, column);
  }

  private Timestamp rawEventTimestamp(String eventId, String column) {
    return (Timestamp) rawEvent(eventId, column);
  }

  private long rawEventDuplicateCount(String eventId) {
    return ((Number) rawEvent(eventId, "DUPLICATE_COUNT")).longValue();
  }

  private Object rawRequest(String requestId, String column) {
    return rawColumn("ETGO_CHECKOUT_REQUEST", "REQUEST_ID", requestId, column);
  }

  private Timestamp rawRequestTimestamp(String requestId, String column) {
    return (Timestamp) rawRequest(requestId, column);
  }

  private String rawRequestPk(String requestId) {
    return (String) rawRequest(requestId, "ETGO_CHECKOUT_REQUEST_ID");
  }

  /**
   * Reads one committed column straight from the database.
   *
   * @param table table name; always a literal from this class
   * @param keyColumn the natural-key column to match on; always a literal from this class
   * @param key the value to match
   * @param column the column to read; always a literal from this class
   * @return the raw value, or null when there is no such row
   */
  @SuppressWarnings("rawtypes")
  private Object rawColumn(String table, String keyColumn, String key, String column) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT " + column + " FROM " + table
              + " WHERE " + keyColumn + " = :key");
      query.setParameter("key", key);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** @return how many billing event rows carry this id — the unique constraint says at most one */
  @SuppressWarnings("rawtypes")
  private long rawEventCount(String eventId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT COUNT(*) FROM ETGO_BILLING_EVENT WHERE EVENT_ID = :eventId");
      query.setParameter("eventId", eventId);
      return ((Number) query.uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** @return the number of rows in the whole billing event table */
  @SuppressWarnings("rawtypes")
  private long rawTotalEventCount() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT COUNT(*) FROM ETGO_BILLING_EVENT");
      return ((Number) query.uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Removes everything this class committed, by marker rather than by collected id so rows left
   * behind by a test that died mid-way are cleaned up too. Events first (they carry the FK to the
   * request), then requests (FK to the account), then accounts. The environment's own live Stripe
   * replay rows carry none of these markers and are never matched.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery deleteEvents = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_BILLING_EVENT WHERE EVENT_ID LIKE :marker");
      deleteEvents.setParameter("marker", EVENT_MARKER + "%");
      deleteEvents.executeUpdate();

      NativeQuery deleteRequests = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker");
      deleteRequests.setParameter("marker", REQUEST_MARKER + "%");
      deleteRequests.executeUpdate();

      NativeQuery deleteAccounts = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE :marker");
      deleteAccounts.setParameter("marker", REQUEST_MARKER + "%");
      deleteAccounts.executeUpdate();

      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Captures what the servlet wrote, the same shape {@code EtendoGoJwtServletTest} uses. */
  private static ResponseCapture mockResponse() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(inv -> null).when(response).setContentType(anyString());
    doAnswer(inv -> null).when(response).setCharacterEncoding(anyString());
    try {
      when(response.getWriter()).thenReturn(writer);
    } catch (Exception e) {
      throw new IllegalStateException("mocking getWriter cannot fail", e);
    }
    return capture;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
