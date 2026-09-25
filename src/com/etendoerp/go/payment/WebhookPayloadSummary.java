/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The allow-list that decides what of a provider webhook payload may ever be written down.
 *
 * <p>Pure JSON, no DAL and no store: {@link CheckoutWebhookProcessor} builds the summary for
 * whichever {@link CheckoutWebhookProcessor.EventStore} is wired underneath it, so the generic
 * seam must not have to reach into the concrete {@link BillingEventStore} to do it. A test double
 * that receives a summary therefore receives one built by this helper, not by the production
 * store.
 *
 * <p>It lives on its own rather than on the processor because the policy it encodes — no card
 * data, no full payload, ever — is an invariant of the whole payment subsystem (ETP-5045, PRD
 * §14.3), not a detail of how one webhook is dispatched.
 */
public final class WebhookPayloadSummary {
  private static final Logger log = LogManager.getLogger();

  /** Width of {@code ETGO_BILLING_EVENT.PAYLOAD_SUMMARY}; the summary is abbreviated to it. */
  public static final int MAX_LENGTH = 2000;

  /** The only {@code data.object} keys that may reach a summary. */
  private static final List<String> ALLOWED_KEYS = Arrays.asList("id", "customer",
      "subscription", "livemode", "payment_status", "amount_total", "currency", "mode");

  /**
   * ETP-5047 — extra {@code data.object} keys kept for {@code invoice.*} events only: the invoice
   * period, so {@code ETGO_BILLING_EVENT} keeps a history of billed periods that the one period
   * slot on {@code ETGO_SUBSCRIPTION} overwrites at each renewal (open-and-notable-topics §5.11).
   * Epoch seconds, as Stripe sends them. On a subscription invoice Stripe's invoice-level period
   * looks back one period (it is the usage period that ended when the invoice was cut); the service
   * period of each price is on the invoice lines, which a summary never keeps.
   */
  private static final List<String> INVOICE_KEYS = Arrays.asList("period_start", "period_end");

  private static final String INVOICE_EVENT_PREFIX = "invoice.";

  private WebhookPayloadSummary() {
  }

  /**
   * Builds the allow-listed summary of one provider event.
   *
   * <p>Keeps {@code data.object.{id, customer, subscription, livemode, payment_status,
   * amount_total, currency, mode}}, {@code data.object.{period_start, period_end}} for
   * {@code invoice.*} events only, and {@code data.object.metadata.request_id}, nothing else. A
   * value that is itself an object (an expanded {@code customer}) contributes only its {@code id};
   * arrays are dropped. The raw body, card data, {@code payment_method_details} and anything not
   * named above never reach the result. It is abbreviated to {@link #MAX_LENGTH}, so a
   * pathological input can produce a truncated, non-parseable summary — it is an operator aid,
   * not a data source.
   *
   * @param event parsed provider event
   * @return compact JSON text, or null when nothing allow-listed is present
   */
  public static String summarize(JSONObject event) {
    if (event == null) {
      return null;
    }
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    if (object == null) {
      return null;
    }
    try {
      JSONObject summary = new JSONObject();
      putAllowed(summary, object, ALLOWED_KEYS);
      if (StringUtils.startsWith(event.optString("type", ""), INVOICE_EVENT_PREFIX)) {
        putAllowed(summary, object, INVOICE_KEYS);
      }
      JSONObject metadata = object.optJSONObject("metadata");
      String requestId = metadata == null ? null
          : StringUtils.trimToNull(metadata.optString("request_id", ""));
      if (requestId != null) {
        summary.put("metadata", new JSONObject().put("request_id", requestId));
      }
      return summary.length() == 0 ? null
          : StringUtils.abbreviate(summary.toString(), MAX_LENGTH);
    } catch (JSONException e) {
      log.warn("Could not summarize a webhook payload", e);
      return null;
    }
  }

  private static void putAllowed(JSONObject summary, JSONObject object, List<String> keys)
      throws JSONException {
    for (String key : keys) {
      Object value = scalarOrId(object.opt(key));
      if (value != null) {
        summary.put(key, value);
      }
    }
  }

  /**
   * Reduces an allow-listed value to something safe to store: a scalar as-is, an object as its
   * {@code id}, anything else (arrays, null, JSON null) as nothing.
   */
  private static Object scalarOrId(Object value) {
    if (value == null || JSONObject.NULL.equals(value) || value instanceof JSONArray) {
      return null;
    }
    if (value instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) value).optString("id", ""));
    }
    return value;
  }
}
