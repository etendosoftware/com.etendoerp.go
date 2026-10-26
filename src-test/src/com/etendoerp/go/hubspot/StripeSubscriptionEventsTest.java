/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.hubspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.hubspot.StripeSubscriptionEvents.Translated;

/**
 * Which Stripe billing events reach HubSpot, keyed so a redelivery adds nothing, and the dates
 * they carry: a cancellation is dated when it was asked for, not when it takes effect.
 *
 * @covers com.etendoerp.go.hubspot.StripeSubscriptionEvents
 */
class StripeSubscriptionEventsTest {

  private static final long CREATED = 1_792_000_000L;
  private static final long DAY = 86_400L;

  private static JSONObject event(String id, JSONObject object, JSONObject previous)
      throws Exception {
    JSONObject data = new JSONObject().put("object", object);
    if (previous != null) {
      data.put("previous_attributes", previous);
    }
    return new JSONObject().put("id", id).put("created", CREATED).put("data", data);
  }

  private static JSONObject invoice(JSONObject line) throws Exception {
    return new JSONObject()
        .put("id", "in_1")
        .put("amount_paid", 2200)
        .put("currency", "eur")
        .put("status_transitions", new JSONObject().put("paid_at", CREATED - 60))
        .put("parent", new JSONObject().put("subscription_details", new JSONObject()
            .put("subscription", "sub_1")
            .put("metadata", new JSONObject().put("request_id", "req-1"))))
        .put("lines", new JSONObject().put("data", new JSONArray()
            .put(line)));
  }

  private static JSONObject subscription(boolean cancelAtPeriodEnd) throws Exception {
    return new JSONObject()
        .put("id", "sub_1")
        .put("cancel_at_period_end", cancelAtPeriodEnd)
        .put("canceled_at", cancelAtPeriodEnd ? CREATED - 30 : JSONObject.NULL)
        .put("metadata", new JSONObject().put("request_id", "req-1"));
  }

  private static JSONObject payload(Translated translated) throws Exception {
    return new JSONObject(translated.payload());
  }

  @Test
  void aPaidInvoiceIsAPaymentKeyedByTheInvoice() throws Exception {
    JSONObject line = new JSONObject().put("price", new JSONObject()
        .put("recurring", new JSONObject().put("interval", "year")));

    Translated paid = StripeSubscriptionEvents.translate("invoice.paid",
        event("evt_1", invoice(line), null)).orElseThrow();

    assertEquals(HubSpotOutboxStore.PAYMENT, paid.eventType());
    assertEquals("in_1", paid.eventKey());
    assertEquals("req-1", paid.requestId());
    assertEquals("sub_1", paid.subscriptionId());
    assertEquals(Instant.ofEpochSecond(CREATED), paid.occurredAt());
    JSONObject payload = payload(paid);
    assertEquals("22.00", payload.getString("amount"));
    assertEquals("EUR", payload.getString("currency"));
    assertEquals("annual", payload.getString("interval"));
    assertEquals(Instant.ofEpochSecond(CREATED - 60).toString(), payload.getString("at"));
  }

  @Test
  void withoutADeclaredIntervalTheBilledPeriodDecides() throws Exception {
    JSONObject month = new JSONObject().put("period", new JSONObject()
        .put("start", CREATED).put("end", CREATED + 31 * DAY));
    JSONObject year = new JSONObject().put("period", new JSONObject()
        .put("start", CREATED).put("end", CREATED + 365 * DAY));

    assertEquals("monthly", StripeSubscriptionEvents.interval(invoice(month)));
    assertEquals("annual", StripeSubscriptionEvents.interval(invoice(year)));
    assertNull(StripeSubscriptionEvents.interval(invoice(new JSONObject())));
  }

  @Test
  void aZeroInvoiceFromACouponIsStillAPayment() throws Exception {
    JSONObject free = invoice(new JSONObject()).put("amount_paid", 0);

    Translated paid = StripeSubscriptionEvents.translate("invoice.paid",
        event("evt_1", free, null)).orElseThrow();

    assertEquals("0.00", payload(paid).getString("amount"));
  }

  @Test
  void eachFailedChargeIsItsOwnEvent() throws Exception {
    Translated failed = StripeSubscriptionEvents.translate("invoice.payment_failed",
        event("evt_9", invoice(new JSONObject()), null)).orElseThrow();

    assertEquals(HubSpotOutboxStore.PAYMENT_FAILED, failed.eventType());
    assertEquals("evt_9", failed.eventKey(), "every attempt Stripe makes is reported");
    assertEquals(Instant.ofEpochSecond(CREATED).toString(), payload(failed).getString("at"));
  }

  @Test
  void schedulingACancellationIsDatedWhenTheOwnerAsked() throws Exception {
    Translated requested = StripeSubscriptionEvents.translate("customer.subscription.updated",
        event("evt_2", subscription(true), new JSONObject().put("cancel_at_period_end", false)))
        .orElseThrow();

    assertEquals(HubSpotOutboxStore.CANCEL_REQUESTED, requested.eventType());
    assertEquals("req-1", requested.requestId());
    assertEquals(Instant.ofEpochSecond(CREATED - 30).toString(),
        payload(requested).getString("at"));
  }

  @Test
  void aCancellationDateSetThroughCancelAtCountsToo() throws Exception {
    JSONObject subscription = subscription(false).put("cancel_at", CREATED + 30 * DAY);

    Optional<Translated> requested = StripeSubscriptionEvents.translate(
        "customer.subscription.updated",
        event("evt_3", subscription, new JSONObject().put("cancel_at", JSONObject.NULL)));

    assertEquals(HubSpotOutboxStore.CANCEL_REQUESTED, requested.orElseThrow().eventType());
  }

  @Test
  void withdrawingTheCancellationIsReported() throws Exception {
    Translated reverted = StripeSubscriptionEvents.translate("customer.subscription.updated",
        event("evt_4", subscription(false), new JSONObject().put("cancel_at_period_end", true)))
        .orElseThrow();

    assertEquals(HubSpotOutboxStore.CANCEL_REVERTED, reverted.eventType());
  }

  @Test
  void anyOtherSubscriptionUpdateIsNotReported() throws Exception {
    assertFalse(StripeSubscriptionEvents.translate("customer.subscription.updated",
        event("evt_5", subscription(false), new JSONObject().put("status", "incomplete")))
        .isPresent());
    assertFalse(StripeSubscriptionEvents.translate("customer.subscription.updated",
        event("evt_6", subscription(false), null)).isPresent());
  }

  @Test
  void anEndedSubscriptionIsReportedOnce() throws Exception {
    Translated canceled = StripeSubscriptionEvents.translate("customer.subscription.deleted",
        event("evt_7", subscription(true), null)).orElseThrow();

    assertEquals(HubSpotOutboxStore.CANCELED, canceled.eventType());
    assertEquals("sub_1", canceled.eventKey());
    assertEquals(Instant.ofEpochSecond(CREATED - 30).toString(),
        payload(canceled).getString("at"));
  }

  @Test
  void eventsWithoutASubscriptionOrOfOtherKindsAreIgnored() throws Exception {
    assertFalse(StripeSubscriptionEvents.translate("invoice.paid",
        event("evt_8", new JSONObject().put("id", "in_2"), null)).isPresent());
    assertFalse(StripeSubscriptionEvents.translate("checkout.session.completed",
        event("evt_8", subscription(false), null)).isPresent());
    assertFalse(StripeSubscriptionEvents.translate("invoice.paid", new JSONObject())
        .isPresent());
    assertTrue(StripeSubscriptionEvents.translate("invoice.paid", null).isEmpty());
  }
}
