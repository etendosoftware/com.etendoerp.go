/* Etendo License. */
package com.etendoerp.go.payment;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * Validates and deduplicates checkout events before a durable provisioning handler is invoked.
 *
 * <p>Three checks, always in this order: the signature (nothing unverified may reach the store),
 * the payload shape (an event without a usable id cannot be deduplicated), and finally the claim.
 * The processor owns no dedup state of its own — whatever the {@link EventStore} answers is the
 * verdict, which is what lets the store be swapped without touching this class. Production wires
 * {@link BillingEventStore}, so the claim is a database unique constraint and survives a restart.
 */
public class CheckoutWebhookProcessor {
  /** Atomic persistence boundary used to deduplicate webhook event ids. */
  public interface EventStore {
    /**
     * Claims an event id for processing, returning false when it was already processed.
     * @param eventId provider event identifier
     * @return true when the event was newly claimed
     */
    boolean claim(String eventId);

    /**
     * Claims an event id for processing, recording what was received alongside the claim.
     *
     * <p>This is the form the processor calls. The default discards the extra context and
     * delegates to {@link #claim(String)}, so a store that only cares about the id (a test
     * double, a lambda) keeps working unchanged; a durable store overrides it to persist the
     * audit columns in the same write as the claim.
     *
     * @param eventId provider event identifier, already trimmed and non-blank
     * @param eventType provider event type, e.g. {@code checkout.session.completed}
     * @param requestId the {@code metadata.request_id} correlation, or null when absent
     * @param summary allow-listed payload summary (see {@link BillingEventStore#summarize}),
     *     or null
     * @return true when the event was newly claimed
     */
    default boolean claim(String eventId, String eventType, String requestId, String summary) {
      return claim(eventId);
    }
  }

  /** Outcome returned after signature, payload, and idempotency validation. */
  public enum Result { ACCEPTED, DUPLICATE, INVALID_SIGNATURE, INVALID_PAYLOAD }

  /**
   * Result of {@link #evaluate} together with what the caller needs to act on it.
   *
   * <p>{@link #eventId()} and {@link #event()} are non-null only for {@link Result#ACCEPTED} and
   * {@link Result#DUPLICATE}: those are the outcomes where the payload was verified and parsed.
   * An {@link Result#INVALID_SIGNATURE} payload is never parsed at all, and an
   * {@link Result#INVALID_PAYLOAD} one has nothing a caller should act on.
   */
  public static final class Acceptance {
    private final Result result;
    private final String eventId;
    private final JSONObject event;

    private Acceptance(Result result, String eventId, JSONObject event) {
      this.result = result;
      this.eventId = eventId;
      this.event = event;
    }

    private static Acceptance refused(Result result) {
      return new Acceptance(result, null, null);
    }

    /** @return the validation and idempotency outcome */
    public Result result() {
      return result;
    }

    /** @return the trimmed event id, or null unless the result is ACCEPTED or DUPLICATE */
    public String eventId() {
      return eventId;
    }

    /** @return the parsed event, or null unless the result is ACCEPTED or DUPLICATE */
    public JSONObject event() {
      return event;
    }
  }

  private final EventStore eventStore;
  private final long toleranceSeconds;

  /**
   * Creates a processor with an event store and accepted signature age.
   * @param eventStore atomic event-claiming store
   * @param toleranceSeconds maximum accepted signature age
   */
  public CheckoutWebhookProcessor(EventStore eventStore, long toleranceSeconds) {
    this.eventStore = eventStore;
    this.toleranceSeconds = toleranceSeconds;
  }

  /**
   * Validates and claims one webhook event.
   * @param payload raw provider webhook payload
   * @param signature provider signature header
   * @param secret webhook signing secret
   * @param nowSeconds current epoch time in seconds
   * @return validation and idempotency outcome
   */
  public Result accept(String payload, String signature, String secret, long nowSeconds) {
    return evaluate(payload, signature, secret, nowSeconds).result();
  }

  /**
   * Validates and claims one webhook event, returning the parsed event alongside the outcome.
   *
   * <p>Same checks and same order as {@link #accept}; the difference is that a caller acting on
   * an accepted event gets the parsed JSON and the id it was claimed under, instead of parsing
   * the payload a second time.
   *
   * @param payload raw provider webhook payload
   * @param signature provider signature header
   * @param secret webhook signing secret
   * @param nowSeconds current epoch time in seconds
   * @return the outcome plus, for ACCEPTED and DUPLICATE, the event id and parsed event
   */
  public Acceptance evaluate(String payload, String signature, String secret, long nowSeconds) {
    if (!CheckoutWebhookVerifier.verify(payload, signature, secret, nowSeconds, toleranceSeconds)) {
      return Acceptance.refused(Result.INVALID_SIGNATURE);
    }
    JSONObject event;
    String id;
    try {
      event = new JSONObject(payload);
      id = event.optString("id", "").trim();
      if (id.isEmpty() || !event.has("type")) {
        return Acceptance.refused(Result.INVALID_PAYLOAD);
      }
    } catch (Exception e) {
      return Acceptance.refused(Result.INVALID_PAYLOAD);
    }
    boolean claimed = eventStore.claim(id, event.optString("type", "").trim(),
        correlationRequestId(event), BillingEventStore.summarize(event));
    return new Acceptance(claimed ? Result.ACCEPTED : Result.DUPLICATE, id, event);
  }

  /**
   * Reads {@code data.object.metadata.request_id}, the server-generated correlation id a hosted
   * checkout session carries back on every event about it.
   *
   * @param event parsed provider event
   * @return the trimmed request id, or null when the event does not carry one
   */
  static String correlationRequestId(JSONObject event) {
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    JSONObject metadata = object == null ? null : object.optJSONObject("metadata");
    return metadata == null ? null : StringUtils.trimToNull(metadata.optString("request_id", ""));
  }
}
