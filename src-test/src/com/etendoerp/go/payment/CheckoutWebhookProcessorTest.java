/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Test;

/**
 * Specs for the webhook gate: signature, payload shape, and the event claim that makes delivery
 * idempotent.
 *
 * <p>Stripe retries a webhook until it gets a 2xx, and it retries on its own schedule after
 * network failures and deploys, so <strong>a duplicate delivery is ordinary traffic, not an
 * anomaly</strong>. The claim is what stops the second copy of a payment event from being applied
 * twice. Everything here is about the order in which the three checks run and what each one
 * refuses, because a mistake in that order is silent: the endpoint answers 200 either way.
 *
 * <p>Note on durability: production wires {@link BillingEventStore}, so the claim is the
 * {@code ETGO_BILLING_EVENT} unique constraint and survives a restart (ETP-5045). These specs
 * still pin the claim <em>protocol</em> through a recording double — which store implementation
 * satisfies it is deliberately left open by the interface, and the durable store's own behaviour
 * (duplicate counting, FAILED re-claim, restart survival) is covered by its integration test.
 */
public class CheckoutWebhookProcessorTest {

  private static final String SECRET = "whsec_test";
  private static final long TIMESTAMP = 1700000000L;
  private static final long TOLERANCE = 300;

  private static final String COMPLETED_EVENT =
      "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\"}";

  /** Records every id the processor tried to claim, so "was the store consulted" is assertable. */
  private static final class RecordingEventStore implements CheckoutWebhookProcessor.EventStore {
    private final Set<String> claimed = new HashSet<>();
    private final List<String> attempts = new ArrayList<>();

    @Override
    public boolean claim(String eventId) {
      attempts.add(eventId);
      return claimed.add(eventId);
    }
  }

  /** Signs a payload the way Stripe does: HMAC-SHA256 over "<timestamp>.<payload>". */
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

  private static CheckoutWebhookProcessor processorFor(
      CheckoutWebhookProcessor.EventStore eventStore) {
    return new CheckoutWebhookProcessor(eventStore, TOLERANCE);
  }

  // --- The claim: first delivery wins, every redelivery is dismissed ---

  @Test
  public void duplicateEventIsClaimedOnlyOnce() throws Exception {
    RecordingEventStore eventStore = new RecordingEventStore();
    CheckoutWebhookProcessor processor = processorFor(eventStore);
    String signature = sign(COMPLETED_EVENT, TIMESTAMP, SECRET);

    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED,
        processor.accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP));
    assertEquals(CheckoutWebhookProcessor.Result.DUPLICATE,
        processor.accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP));
  }

  @Test
  public void everyFurtherRedeliveryStaysDuplicateRatherThanFlippingBack() throws Exception {
    // Stripe keeps retrying for days. A claim that only held for the second delivery would let
    // the third one apply the payment again, which is the bug the claim exists to prevent.
    RecordingEventStore eventStore = new RecordingEventStore();
    CheckoutWebhookProcessor processor = processorFor(eventStore);
    String signature = sign(COMPLETED_EVENT, TIMESTAMP, SECRET);

    processor.accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP);
    for (int delivery = 2; delivery <= 5; delivery++) {
      assertEquals("delivery " + delivery + " must still be dismissed as a duplicate",
          CheckoutWebhookProcessor.Result.DUPLICATE,
          processor.accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP));
    }
  }

  @Test
  public void deduplicationIsPerEventIdNotGlobal() throws Exception {
    // A claimed event must not suppress an unrelated one. Getting this wrong would drop the
    // second genuine payment of the day and leave a charged account unprovisioned.
    RecordingEventStore eventStore = new RecordingEventStore();
    CheckoutWebhookProcessor processor = processorFor(eventStore);

    String other = "{\"id\":\"evt_2\",\"type\":\"checkout.session.completed\"}";
    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED, processor.accept(COMPLETED_EVENT,
        sign(COMPLETED_EVENT, TIMESTAMP, SECRET), SECRET, TIMESTAMP));
    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED,
        processor.accept(other, sign(other, TIMESTAMP, SECRET), SECRET, TIMESTAMP));
  }

  @Test
  public void theAsyncPaymentVariantIsClaimedOnTheSameFooting() throws Exception {
    // Delayed-notification methods arrive as async_payment_succeeded. It is a payment event like
    // any other and must go through the same claim, or a retry of it applies twice.
    String async = "{\"id\":\"evt_3\",\"type\":\"checkout.session.async_payment_succeeded\"}";
    CheckoutWebhookProcessor processor = processorFor(new RecordingEventStore());
    String signature = sign(async, TIMESTAMP, SECRET);

    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED,
        processor.accept(async, signature, SECRET, TIMESTAMP));
    assertEquals(CheckoutWebhookProcessor.Result.DUPLICATE,
        processor.accept(async, signature, SECRET, TIMESTAMP));
  }

  // --- Nothing unverified may reach the event store ---

  @Test
  public void anInvalidSignatureNeverReachesTheEventStore() throws Exception {
    // The security property, and the reason the order of the three checks matters: if the claim
    // ran first, anyone who could guess an event id could burn it with an unsigned request and
    // make Stripe's genuine, correctly signed delivery arrive as a duplicate. That would suppress
    // a real payment with no authentication at all.
    RecordingEventStore eventStore = new RecordingEventStore();
    CheckoutWebhookProcessor processor = processorFor(eventStore);

    assertEquals(CheckoutWebhookProcessor.Result.INVALID_SIGNATURE,
        processor.accept(COMPLETED_EVENT, "t=" + TIMESTAMP + ",v1=deadbeef", SECRET, TIMESTAMP));
    assertTrue("an unverified payload must not be able to claim an event id",
        eventStore.attempts.isEmpty());
  }

  @Test
  public void aTamperedPayloadIsRejectedEvenWithAnOtherwiseValidSignature() throws Exception {
    // The signature covers the body, so editing the body after signing must invalidate it.
    String signature = sign(COMPLETED_EVENT, TIMESTAMP, SECRET);
    String tampered = COMPLETED_EVENT.replace("evt_1", "evt_forged");
    RecordingEventStore eventStore = new RecordingEventStore();

    assertEquals(CheckoutWebhookProcessor.Result.INVALID_SIGNATURE,
        processorFor(eventStore).accept(tampered, signature, SECRET, TIMESTAMP));
    assertTrue(eventStore.attempts.isEmpty());
  }

  @Test
  public void aSignatureOlderThanTheToleranceWindowIsRejected() throws Exception {
    // The timestamp is inside the signed bytes, so an old capture cannot be re-signed. Bounding
    // its age is what stops an intercepted delivery being replayed later.
    String signature = sign(COMPLETED_EVENT, TIMESTAMP, SECRET);
    RecordingEventStore eventStore = new RecordingEventStore();

    long wellPastTheWindow = TIMESTAMP + TOLERANCE + 1;
    assertEquals(CheckoutWebhookProcessor.Result.INVALID_SIGNATURE,
        processorFor(eventStore).accept(COMPLETED_EVENT, signature, SECRET, wellPastTheWindow));
    assertTrue(eventStore.attempts.isEmpty());
  }

  @Test
  public void aSignatureFromAnotherSecretIsRejected() throws Exception {
    String signature = sign(COMPLETED_EVENT, TIMESTAMP, "whsec_someone_elses");
    RecordingEventStore eventStore = new RecordingEventStore();

    assertEquals(CheckoutWebhookProcessor.Result.INVALID_SIGNATURE,
        processorFor(eventStore).accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP));
    assertTrue(eventStore.attempts.isEmpty());
  }

  // --- A correctly signed payload still has to be a usable event ---

  @Test
  public void anEventWithoutAUsableIdIsRefusedWithoutClaimingAnything() throws Exception {
    // There is nothing to deduplicate on, so accepting it would make the event replayable
    // forever. Blank and absent are the same case: optString yields "" for both.
    for (String payload : new String[] {
        "{\"type\":\"checkout.session.completed\"}",
        "{\"id\":\"\",\"type\":\"checkout.session.completed\"}",
        "{\"id\":\"   \",\"type\":\"checkout.session.completed\"}" }) {
      RecordingEventStore eventStore = new RecordingEventStore();

      assertEquals("expected INVALID_PAYLOAD for " + payload,
          CheckoutWebhookProcessor.Result.INVALID_PAYLOAD,
          processorFor(eventStore).accept(payload, sign(payload, TIMESTAMP, SECRET), SECRET,
              TIMESTAMP));
      assertTrue("an unidentifiable event must not claim anything",
          eventStore.attempts.isEmpty());
    }
  }

  @Test
  public void anEventWithoutATypeIsRefused() throws Exception {
    String payload = "{\"id\":\"evt_4\"}";
    RecordingEventStore eventStore = new RecordingEventStore();

    assertEquals(CheckoutWebhookProcessor.Result.INVALID_PAYLOAD,
        processorFor(eventStore).accept(payload, sign(payload, TIMESTAMP, SECRET), SECRET,
            TIMESTAMP));
    assertTrue(eventStore.attempts.isEmpty());
  }

  @Test
  public void aBodyThatIsNotJsonIsRefusedRatherThanThrowing() throws Exception {
    // The endpoint is public and unauthenticated, so a malformed body must be an ordinary
    // outcome rather than a stack trace escaping to the servlet.
    String payload = "not json at all";
    RecordingEventStore eventStore = new RecordingEventStore();

    assertEquals(CheckoutWebhookProcessor.Result.INVALID_PAYLOAD,
        processorFor(eventStore).accept(payload, sign(payload, TIMESTAMP, SECRET), SECRET,
            TIMESTAMP));
    assertTrue(eventStore.attempts.isEmpty());
  }

  // --- The store's answer is what decides, not the processor ---

  @Test
  public void aStoreThatRefusesTheClaimYieldsDuplicate() {
    // The processor owns no dedup state of its own: whatever the store says is the verdict. This
    // is what lets the in-memory store be swapped for a durable one without touching this class.
    CheckoutWebhookProcessor alwaysClaimed = new CheckoutWebhookProcessor(eventId -> false,
        TOLERANCE);
    CheckoutWebhookProcessor neverClaimed = new CheckoutWebhookProcessor(eventId -> true,
        TOLERANCE);

    try {
      String signature = sign(COMPLETED_EVENT, TIMESTAMP, SECRET);
      assertEquals(CheckoutWebhookProcessor.Result.DUPLICATE,
          alwaysClaimed.accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP));
      assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED,
          neverClaimed.accept(COMPLETED_EVENT, signature, SECRET, TIMESTAMP));
    } catch (Exception e) {
      throw new IllegalStateException("signing the fixture payload failed", e);
    }
  }

  @Test
  public void theClaimedIdIsTheEventIdAndIsTrimmed() throws Exception {
    // The id is the deduplication key, so whitespace around it would make the same event claim
    // two different keys and defeat the whole mechanism.
    String payload = "{\"id\":\"  evt_5  \",\"type\":\"checkout.session.completed\"}";
    RecordingEventStore eventStore = new RecordingEventStore();

    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED,
        processorFor(eventStore).accept(payload, sign(payload, TIMESTAMP, SECRET), SECRET,
            TIMESTAMP));
    assertEquals(1, eventStore.attempts.size());
    assertEquals("evt_5", eventStore.attempts.get(0));
  }

  // --- ETP-5045: the rich claim and evaluate() ---

  /** One recorded rich claim: everything the processor handed to the store alongside the id. */
  private static final class RichClaim {
    private final String eventId;
    private final String eventType;
    private final String requestId;
    private final String summary;

    private RichClaim(String eventId, String eventType, String requestId, String summary) {
      this.eventId = eventId;
      this.eventType = eventType;
      this.requestId = requestId;
      this.summary = summary;
    }
  }

  /**
   * Records the rich claim the processor makes, so "what did the durable store receive" is
   * assertable. The id-only form is still implemented (the interface requires it) but the
   * processor must never fall back to it — {@link #idOnlyAttempts} pins that.
   */
  private static final class RichRecordingEventStore
      implements CheckoutWebhookProcessor.EventStore {
    private final Set<String> claimed = new HashSet<>();
    private final List<RichClaim> claims = new ArrayList<>();
    private final List<String> idOnlyAttempts = new ArrayList<>();

    @Override
    public boolean claim(String eventId) {
      idOnlyAttempts.add(eventId);
      return claimed.add(eventId);
    }

    @Override
    public boolean claim(String eventId, String eventType, String requestId, String summary) {
      claims.add(new RichClaim(eventId, eventType, requestId, summary));
      return claimed.add(eventId);
    }
  }

  private static final String RICH_EVENT = "{"
      + "\"id\":\"evt_rich\",\"type\":\"  checkout.session.completed  \","
      + "\"data\":{\"object\":{"
      + "  \"id\":\"cs_rich\",\"customer\":\"cus_rich\",\"payment_status\":\"paid\","
      + "  \"payment_method_details\":{\"card\":{\"last4\":\"4242\"}},"
      + "  \"metadata\":{\"request_id\":\"  req_rich  \"}"
      + "}}}";

  @Test
  public void theRichClaimCarriesTheTrimmedTypeTheCorrelationIdAndASafeSummary()
      throws Exception {
    // The durable store persists the audit columns in the same write as the claim, so what the
    // processor hands over is what the operator later sees: the type (trimmed, like the id), the
    // correlation id from data.object.metadata, and the allow-listed summary — never the body.
    RichRecordingEventStore eventStore = new RichRecordingEventStore();

    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED, processorFor(eventStore)
        .accept(RICH_EVENT, sign(RICH_EVENT, TIMESTAMP, SECRET), SECRET, TIMESTAMP));

    assertEquals(1, eventStore.claims.size());
    RichClaim claim = eventStore.claims.get(0);
    assertEquals("evt_rich", claim.eventId);
    assertEquals("the event type is trimmed like the id", "checkout.session.completed",
        claim.eventType);
    assertEquals("the correlation id is read from data.object.metadata and trimmed", "req_rich",
        claim.requestId);
    assertNotNull("a payload with a data.object yields a summary", claim.summary);
    assertTrue(claim.summary.contains("cs_rich"));
    assertFalse("card data must never be handed to the store: " + claim.summary,
        claim.summary.contains("card") || claim.summary.contains("4242"));
    assertTrue("the processor must use the rich form, never fall back to the id-only one",
        eventStore.idOnlyAttempts.isEmpty());
  }

  @Test
  public void theRichClaimPassesNullsWhenTheEventCarriesNoDataObject() throws Exception {
    // A bare event (the fixture the other specs use) has no correlation and nothing to summarize;
    // the store must receive null rather than an empty string or an empty object.
    RichRecordingEventStore eventStore = new RichRecordingEventStore();

    processorFor(eventStore).accept(COMPLETED_EVENT, sign(COMPLETED_EVENT, TIMESTAMP, SECRET),
        SECRET, TIMESTAMP);

    assertEquals(1, eventStore.claims.size());
    RichClaim claim = eventStore.claims.get(0);
    assertEquals("evt_1", claim.eventId);
    assertEquals("checkout.session.completed", claim.eventType);
    assertNull(claim.requestId);
    assertNull(claim.summary);
  }

  @Test
  public void evaluateExposesTheEventOnlyOnceItWasVerifiedAndParsed() throws Exception {
    // The servlet acts on evaluate().event() instead of parsing the body a second time. It may
    // only ever see a payload whose signature verified and whose shape was usable: ACCEPTED and
    // DUPLICATE (the caller still needs the id to answer Stripe). A refused delivery exposes
    // nothing, so nothing unverified can leak into the handler by accident.
    RichRecordingEventStore eventStore = new RichRecordingEventStore();
    CheckoutWebhookProcessor processor = processorFor(eventStore);
    String signature = sign(RICH_EVENT, TIMESTAMP, SECRET);

    CheckoutWebhookProcessor.Acceptance accepted = processor.evaluate(RICH_EVENT, signature,
        SECRET, TIMESTAMP);
    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED, accepted.result());
    assertEquals("evt_rich", accepted.eventId());
    assertNotNull(accepted.event());
    assertEquals("cs_rich", accepted.event().getJSONObject("data").getJSONObject("object")
        .getString("id"));

    CheckoutWebhookProcessor.Acceptance duplicate = processor.evaluate(RICH_EVENT, signature,
        SECRET, TIMESTAMP);
    assertEquals(CheckoutWebhookProcessor.Result.DUPLICATE, duplicate.result());
    assertEquals("a duplicate still names the id it was dismissed under", "evt_rich",
        duplicate.eventId());
    assertNotNull(duplicate.event());

    CheckoutWebhookProcessor.Acceptance badSignature = processor.evaluate(RICH_EVENT,
        "t=" + TIMESTAMP + ",v1=deadbeef", SECRET, TIMESTAMP);
    assertEquals(CheckoutWebhookProcessor.Result.INVALID_SIGNATURE, badSignature.result());
    assertNull("an unverified payload is never parsed", badSignature.eventId());
    assertNull(badSignature.event());

    String noType = "{\"id\":\"evt_no_type\"}";
    CheckoutWebhookProcessor.Acceptance badPayload = processor.evaluate(noType,
        sign(noType, TIMESTAMP, SECRET), SECRET, TIMESTAMP);
    assertEquals(CheckoutWebhookProcessor.Result.INVALID_PAYLOAD, badPayload.result());
    assertNull("an unusable payload has nothing a caller should act on", badPayload.eventId());
    assertNull(badPayload.event());

    String notJson = "not json at all";
    CheckoutWebhookProcessor.Acceptance notParsable = processor.evaluate(notJson,
        sign(notJson, TIMESTAMP, SECRET), SECRET, TIMESTAMP);
    assertEquals(CheckoutWebhookProcessor.Result.INVALID_PAYLOAD, notParsable.result());
    assertNull(notParsable.eventId());
    assertNull(notParsable.event());
  }

  @Test
  public void acceptAndEvaluateAgreeOnEveryFixture() throws Exception {
    // accept() is evaluate().result() by construction; this pins that no fixture — valid, replayed,
    // forged, tampered, stale, malformed — can make the two paths disagree, so the servlet's move
    // from accept() to evaluate() changed nothing about which deliveries get through.
    String validSignature = sign(COMPLETED_EVENT, TIMESTAMP, SECRET);
    String other = "{\"id\":\"evt_2\",\"type\":\"checkout.session.completed\"}";
    String noId = "{\"type\":\"checkout.session.completed\"}";
    String noType = "{\"id\":\"evt_4\"}";
    String notJson = "not json at all";
    String padded = "{\"id\":\"  evt_5  \",\"type\":\"checkout.session.completed\"}";

    // Each fixture is {payload, signature, nowSeconds}; the sequence is replayed against two
    // independent stores so the claim state evolves identically on both paths.
    Object[][] deliveries = new Object[][] {
        { COMPLETED_EVENT, validSignature, TIMESTAMP },
        { COMPLETED_EVENT, validSignature, TIMESTAMP },
        { other, sign(other, TIMESTAMP, SECRET), TIMESTAMP },
        { RICH_EVENT, sign(RICH_EVENT, TIMESTAMP, SECRET), TIMESTAMP },
        { RICH_EVENT, sign(RICH_EVENT, TIMESTAMP, SECRET), TIMESTAMP },
        { COMPLETED_EVENT, "t=" + TIMESTAMP + ",v1=deadbeef", TIMESTAMP },
        { COMPLETED_EVENT.replace("evt_1", "evt_forged"), validSignature, TIMESTAMP },
        { COMPLETED_EVENT, validSignature, TIMESTAMP + TOLERANCE + 1 },
        { COMPLETED_EVENT, sign(COMPLETED_EVENT, TIMESTAMP, "whsec_someone_elses"), TIMESTAMP },
        { noId, sign(noId, TIMESTAMP, SECRET), TIMESTAMP },
        { noType, sign(noType, TIMESTAMP, SECRET), TIMESTAMP },
        { notJson, sign(notJson, TIMESTAMP, SECRET), TIMESTAMP },
        { padded, sign(padded, TIMESTAMP, SECRET), TIMESTAMP },
        { padded, sign(padded, TIMESTAMP, SECRET), TIMESTAMP },
    };

    CheckoutWebhookProcessor viaAccept = processorFor(new RichRecordingEventStore());
    CheckoutWebhookProcessor viaEvaluate = processorFor(new RichRecordingEventStore());
    Set<CheckoutWebhookProcessor.Result> seen = new HashSet<>();
    for (int i = 0; i < deliveries.length; i++) {
      String payload = (String) deliveries[i][0];
      String signature = (String) deliveries[i][1];
      long now = (Long) deliveries[i][2];

      CheckoutWebhookProcessor.Result accepted = viaAccept.accept(payload, signature, SECRET, now);
      CheckoutWebhookProcessor.Acceptance evaluated = viaEvaluate.evaluate(payload, signature,
          SECRET, now);

      assertEquals("delivery " + i + " must be judged the same by accept() and evaluate()",
          accepted, evaluated.result());
      boolean verified = accepted == CheckoutWebhookProcessor.Result.ACCEPTED
          || accepted == CheckoutWebhookProcessor.Result.DUPLICATE;
      assertEquals("delivery " + i + ": the event is exposed exactly when it was verified",
          verified, evaluated.event() != null);
      assertEquals("delivery " + i + ": the id is exposed exactly when it was verified",
          verified, evaluated.eventId() != null);
      seen.add(accepted);
    }
    assertEquals("the fixture set must exercise every outcome", 4, seen.size());
  }
}
