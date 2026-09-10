/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
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
 * <p>Note on durability: the {@link CheckoutWebhookProcessor.EventStore} passed in by production
 * today is still process-memory, so the claim recorded here does not survive a restart. That is
 * the {@code ETGO_BILLING_EVENT} follow-up, and it is why these specs pin the claim
 * <em>protocol</em> — which store implementation satisfies it is deliberately left open by the
 * interface.
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
}
