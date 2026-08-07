/* Etendo License. */
package com.etendoerp.go.payment;

import org.codehaus.jettison.json.JSONObject;

/** Validates and deduplicates checkout events before a durable provisioning handler is invoked. */
public class CheckoutWebhookProcessor {
  public interface EventStore {
    /** Atomically records an event id. Returns false when it was already processed. */
    boolean claim(String eventId);
  }

  public enum Result { ACCEPTED, DUPLICATE, INVALID_SIGNATURE, INVALID_PAYLOAD }

  private final EventStore eventStore;
  private final long toleranceSeconds;

  public CheckoutWebhookProcessor(EventStore eventStore, long toleranceSeconds) {
    this.eventStore = eventStore;
    this.toleranceSeconds = toleranceSeconds;
  }

  public Result accept(String payload, String signature, String secret, long nowSeconds) {
    if (!CheckoutWebhookVerifier.verify(payload, signature, secret, nowSeconds, toleranceSeconds)) {
      return Result.INVALID_SIGNATURE;
    }
    try {
      JSONObject event = new JSONObject(payload);
      String id = event.optString("id", "").trim();
      if (id.isEmpty() || !event.has("type")) return Result.INVALID_PAYLOAD;
      return eventStore.claim(id) ? Result.ACCEPTED : Result.DUPLICATE;
    } catch (Exception e) {
      return Result.INVALID_PAYLOAD;
    }
  }
}
