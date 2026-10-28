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

package com.etendoerp.go.hubspot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.payment.StripeJson;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;

/**
 * Which Stripe billing events HubSpot hears about, and what it is told.
 *
 * <ul>
 *   <li>{@code invoice.paid}: a payment, every one of them, a zero total included.</li>
 *   <li>{@code invoice.payment_failed}: a failed charge; each attempt Stripe makes is one.</li>
 *   <li>{@code customer.subscription.updated}: only when a cancellation is scheduled or
 *       withdrawn. The date reported is when the owner asked, not when the period ends.</li>
 *   <li>{@code customer.subscription.deleted}: the subscription ended.</li>
 * </ul>
 *
 * <p>Nothing here reads the database: the event names the purchase it belongs to through the
 * {@code request_id} Etendo put in the subscription's metadata at checkout, and the subscription
 * id, and {@link HubSpotOutbox#stripeEvent} finds the account with them.
 */
final class StripeSubscriptionEvents {

  static final String FIELD_SUBSCRIPTION = "subscriptionId";
  static final String FIELD_AT = "at";
  static final String FIELD_AMOUNT = "amount";
  static final String FIELD_CURRENCY = "currency";
  static final String FIELD_INTERVAL = "interval";

  static final String MONTHLY = "monthly";
  static final String ANNUAL = "annual";

  private static final String CANCEL_AT_PERIOD_END = "cancel_at_period_end";
  private static final String CANCEL_AT = "cancel_at";
  private static final String CANCELED_AT = "canceled_at";
  private static final String METADATA = "metadata";

  /** A billing period this long or longer is a year; any shorter one a month. */
  private static final Duration YEARLY_PERIOD = Duration.ofDays(300);

  private StripeSubscriptionEvents() {
  }

  /**
   * One Stripe event as an outbox row.
   *
   * @param eventType      the outbox event type
   * @param eventKey       what keeps two rows of that type apart, so a redelivery adds nothing
   * @param occurredAt     when Stripe recorded the event, which orders the subscription's events
   * @param requestId      the checkout request that bought the subscription, when the event names
   *                       it
   * @param subscriptionId the Stripe subscription
   * @param payload        what the sender needs, as JSON text
   */
  record Translated(String eventType, String eventKey, Instant occurredAt, String requestId,
      String subscriptionId, String payload) {
  }

  /**
   * Translates a Stripe event.
   *
   * @param type  the Stripe event type
   * @param event the full event
   * @return the outbox row, or empty when HubSpot does not hear about this event
   */
  static Optional<Translated> translate(String type, JSONObject event) {
    JSONObject data = event == null ? null : event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    if (object == null) {
      return Optional.empty();
    }
    String subscriptionId = SubscriptionLifecycleApplier.subscriptionIdOf(type, object);
    if (StringUtils.isBlank(subscriptionId)) {
      return Optional.empty();
    }
    Instant created = SubscriptionLifecycleApplier.eventCreatedAt(event);
    String eventId = StripeJson.text(event, "id");
    try {
      switch (StringUtils.trimToEmpty(type)) {
        case "invoice.paid":
          return Optional.of(new Translated(HubSpotOutboxStore.PAYMENT,
              StringUtils.defaultIfBlank(StripeJson.text(object, "id"), eventId), created,
              invoiceRequestId(object), subscriptionId, paymentPayload(object, subscriptionId,
                  created)));
        case "invoice.payment_failed":
          return Optional.of(new Translated(HubSpotOutboxStore.PAYMENT_FAILED, eventId, created,
              invoiceRequestId(object), subscriptionId, payload(subscriptionId, created)));
        case "customer.subscription.updated":
          return cancellationChange(eventId, created, object,
              data.optJSONObject("previous_attributes"), subscriptionId);
        case "customer.subscription.deleted":
          return Optional.of(new Translated(HubSpotOutboxStore.CANCELED, subscriptionId, created,
              metadataRequestId(object), subscriptionId, payload(subscriptionId,
                  firstOf(epochSeconds(object, CANCELED_AT), epochSeconds(object, "ended_at"),
                      created))));
        default:
          return Optional.empty();
      }
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the HubSpot event of a Stripe " + type, e);
    }
  }

  /**
   * A cancellation scheduled or withdrawn. Stripe reports either through
   * {@code cancel_at_period_end} or through {@code cancel_at}; {@code previous_attributes} holds
   * what changed, so any other update (a renewal, a status change) reports nothing.
   */
  private static Optional<Translated> cancellationChange(String eventId, Instant created,
      JSONObject subscription, JSONObject previous, String subscriptionId)
      throws JSONException {
    if (previous == null || !(previous.has(CANCEL_AT_PERIOD_END) || previous.has(CANCEL_AT))) {
      return Optional.empty();
    }
    boolean scheduledNow = cancellationScheduled(subscription, subscription);
    boolean scheduledBefore = cancellationScheduled(previous, subscription);
    if (scheduledNow == scheduledBefore) {
      return Optional.empty();
    }
    if (scheduledNow) {
      // canceled_at is when the owner asked, even when the cancellation waits for the period end.
      return Optional.of(new Translated(HubSpotOutboxStore.CANCEL_REQUESTED, eventId, created,
          metadataRequestId(subscription), subscriptionId, payload(subscriptionId,
              firstOf(epochSeconds(subscription, CANCELED_AT), created))));
    }
    return Optional.of(new Translated(HubSpotOutboxStore.CANCEL_REVERTED, eventId, created,
        metadataRequestId(subscription), subscriptionId, payload(subscriptionId, created)));
  }

  /** Reads each field from {@code values} when it holds it, else from {@code current}. */
  private static boolean cancellationScheduled(JSONObject values, JSONObject current) {
    JSONObject atPeriodEnd = values.has(CANCEL_AT_PERIOD_END) ? values : current;
    JSONObject at = values.has(CANCEL_AT) ? values : current;
    return atPeriodEnd.optBoolean(CANCEL_AT_PERIOD_END, false) || epochSeconds(at, CANCEL_AT) != null;
  }

  private static String paymentPayload(JSONObject invoice, String subscriptionId, Instant created)
      throws JSONException {
    JSONObject transitions = invoice.optJSONObject("status_transitions");
    Instant paidAt = transitions == null ? null : epochSeconds(transitions, "paid_at");
    JSONObject payload = new JSONObject(payload(subscriptionId, firstOf(paidAt, created)));
    // Self-serve plans are sold in two-decimal currencies, so the minor units are cents.
    if (invoice.has("amount_paid")) {
      payload.put(FIELD_AMOUNT, BigDecimal.valueOf(invoice.optLong("amount_paid", 0L))
          .movePointLeft(2).toPlainString());
    }
    String currency = StripeJson.text(invoice, FIELD_CURRENCY);
    if (StringUtils.isNotBlank(currency)) {
      payload.put(FIELD_CURRENCY, currency.toUpperCase());
    }
    String interval = interval(invoice);
    if (interval != null) {
      payload.put(FIELD_INTERVAL, interval);
    }
    return payload.toString();
  }

  /**
   * Monthly or annual. The line's price says it on older API versions; newer ones only name the
   * price, so the length of the billed period decides.
   */
  static String interval(JSONObject invoice) {
    JSONObject lines = invoice.optJSONObject("lines");
    JSONArray data = lines == null ? null : lines.optJSONArray("data");
    if (data == null) {
      return null;
    }
    for (int i = 0; i < data.length(); i++) {
      JSONObject line = data.optJSONObject(i);
      String declared = line == null ? null : declaredInterval(line);
      if (declared != null) {
        return declared;
      }
    }
    for (int i = 0; i < data.length(); i++) {
      JSONObject line = data.optJSONObject(i);
      String billed = line == null ? null : billedInterval(line);
      if (billed != null) {
        return billed;
      }
    }
    return null;
  }

  private static String billedInterval(JSONObject line) {
    JSONObject period = line.optJSONObject("period");
    Instant start = period == null ? null : epochSeconds(period, "start");
    Instant end = period == null ? null : epochSeconds(period, "end");
    if (start == null || end == null || !end.isAfter(start)) {
      return null;
    }
    return Duration.between(start, end).compareTo(YEARLY_PERIOD) >= 0 ? ANNUAL : MONTHLY;
  }

  private static String declaredInterval(JSONObject line) {
    JSONObject price = line.optJSONObject("price");
    JSONObject recurring = price == null ? null : price.optJSONObject("recurring");
    JSONObject declaring = recurring != null ? recurring : line.optJSONObject("plan");
    String interval = declaring == null ? null : StripeJson.text(declaring, FIELD_INTERVAL);
    if ("year".equals(interval)) {
      return ANNUAL;
    }
    return "month".equals(interval) ? MONTHLY : null;
  }

  /** The request id the subscription carries, which an invoice copies under its parent. */
  private static String invoiceRequestId(JSONObject invoice) {
    JSONObject parent = invoice.optJSONObject("parent");
    JSONObject details = parent == null ? invoice.optJSONObject("subscription_details")
        : parent.optJSONObject("subscription_details");
    return details == null ? null : metadataRequestId(details);
  }

  private static String metadataRequestId(JSONObject object) {
    JSONObject metadata = object.optJSONObject(METADATA);
    return metadata == null ? null : StringUtils.trimToNull(StripeJson.text(metadata, "request_id"));
  }

  private static String payload(String subscriptionId, Instant at) throws JSONException {
    JSONObject payload = new JSONObject().put(FIELD_SUBSCRIPTION, subscriptionId);
    if (at != null) {
      payload.put(FIELD_AT, at.toString());
    }
    return payload.toString();
  }

  private static Instant firstOf(Instant... instants) {
    for (Instant instant : instants) {
      if (instant != null) {
        return instant;
      }
    }
    return null;
  }

  private static Instant epochSeconds(JSONObject object, String field) {
    long seconds = object.optLong(field, 0L);
    return seconds > 0L ? Instant.ofEpochSecond(seconds) : null;
  }
}
