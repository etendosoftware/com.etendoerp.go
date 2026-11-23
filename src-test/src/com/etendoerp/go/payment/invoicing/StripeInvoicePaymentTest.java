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

package com.etendoerp.go.payment.invoicing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * What a Stripe {@code invoice.paid} event records as a payment to invoice (ETP-6322), in the
 * current and the older Stripe API shapes.
 *
 * @covers com.etendoerp.go.payment.invoicing.StripeInvoicePayment
 */
class StripeInvoicePaymentTest {

  private static final long PAID_AT = 1_792_000_000L;
  private static final long START = 1_791_000_000L;
  private static final long END = 1_793_000_000L;

  /** A current-API invoice: subscription and price under {@code parent} and {@code pricing}. */
  static JSONObject currentInvoice(String reason, long amountPaid) throws Exception {
    return new JSONObject("{\"id\":\"in_1\",\"number\":\"ABC-0001\",\"customer\":\"cus_1\","
        + "\"customer_email\":\"owner@example.com\",\"currency\":\"eur\","
        + "\"amount_paid\":" + amountPaid + ",\"subtotal\":" + amountPaid + ",\"total\":"
        + amountPaid + ",\"total_taxes\":[],\"billing_reason\":\"" + reason + "\","
        + "\"status_transitions\":{\"paid_at\":" + PAID_AT + "},"
        + "\"parent\":{\"subscription_details\":{\"subscription\":\"sub_1\","
        + "\"metadata\":{\"request_id\":\"req-1\"}}},"
        + "\"lines\":{\"data\":[{\"description\":\"1 x Pro (at 450.00 / year)\",\"quantity\":1,"
        + "\"amount\":" + amountPaid + ",\"pricing\":{\"price_details\":{\"price\":\"price_pro\"}},"
        + "\"period\":{\"start\":" + START + ",\"end\":" + END + "}}]}}");
  }

  static JSONObject event(String type, JSONObject invoice) throws Exception {
    return new JSONObject().put("id", "evt_1").put("type", type).put("created", PAID_AT + 5)
        .put("data", new JSONObject().put("object", invoice));
  }

  @Test
  void aPaidInvoiceIsAPaymentWithItsAmountsLinesAndPeriod() throws Exception {
    StripeInvoicePayment payment = StripeInvoicePayment.from("invoice.paid",
        event("invoice.paid", currentInvoice("subscription_create", 45_000))).orElseThrow();

    assertEquals("in_1", payment.invoiceId());
    assertEquals("ABC-0001", payment.invoiceNumber());
    assertEquals("evt_1", payment.eventId());
    assertEquals("sub_1", payment.subscriptionId());
    assertEquals("cus_1", payment.customerId());
    assertEquals("owner@example.com", payment.customerEmail());
    assertEquals("req-1", payment.requestId());
    assertEquals("EUR", payment.currency());
    assertEquals(0, new BigDecimal("450.00").compareTo(payment.amountPaid()));
    assertEquals(0, BigDecimal.ZERO.compareTo(payment.taxAmount()));
    assertEquals(StripeInvoicePayment.TYPE_NEW, payment.paymentType());
    assertEquals(Instant.ofEpochSecond(PAID_AT), payment.paidAt());
    assertEquals(Instant.ofEpochSecond(START), payment.periodStart());
    assertEquals(Instant.ofEpochSecond(END), payment.periodEnd());
    JSONObject line = payment.lines().getJSONObject(0);
    assertEquals("price_pro", line.getString("priceId"));
    assertEquals("450.00", line.getString("amount"));
    assertEquals(Instant.ofEpochSecond(START).toString(), line.getString("periodStart"));
  }

  @Test
  void anInvoiceThatCollectedNothingIsNoPayment() throws Exception {
    assertTrue(StripeInvoicePayment.from("invoice.paid",
        event("invoice.paid", currentInvoice("subscription_create", 0))).isEmpty());
  }

  @Test
  void anInvoiceThatDoesNotSayWhatItCollectedIsNoPayment() throws Exception {
    JSONObject invoice = currentInvoice("subscription_cycle", 2_500);
    invoice.remove("amount_paid");

    assertTrue(StripeInvoicePayment.from("invoice.paid", event("invoice.paid", invoice))
        .isEmpty());
  }

  @Test
  void otherEventsAreNoPayment() throws Exception {
    assertTrue(StripeInvoicePayment.from("invoice.payment_failed",
        event("invoice.payment_failed", currentInvoice("subscription_cycle", 2_500))).isEmpty());
    assertTrue(StripeInvoicePayment.from("invoice.paid", null).isEmpty());
    assertTrue(StripeInvoicePayment.from("invoice.paid", new JSONObject()).isEmpty());
  }

  @Test
  void theBillingReasonDecidesThePaymentType() {
    assertEquals(StripeInvoicePayment.TYPE_NEW,
        StripeInvoicePayment.paymentTypeOf("subscription_create"));
    assertEquals(StripeInvoicePayment.TYPE_RENEWAL,
        StripeInvoicePayment.paymentTypeOf("subscription_cycle"));
    assertEquals(StripeInvoicePayment.TYPE_PLAN_CHANGE,
        StripeInvoicePayment.paymentTypeOf("subscription_update"));
    assertEquals(StripeInvoicePayment.TYPE_OTHER, StripeInvoicePayment.paymentTypeOf("manual"));
    assertEquals(StripeInvoicePayment.TYPE_OTHER, StripeInvoicePayment.paymentTypeOf(null));
  }

  /** Older API versions: the subscription and price at top level, {@code tax} not a list. */
  @Test
  void theOlderApiShapeIsReadToo() throws Exception {
    JSONObject invoice = new JSONObject("{\"id\":\"in_2\",\"currency\":\"eur\","
        + "\"amount_paid\":2500,\"tax\":525,\"subscription\":\"sub_2\","
        + "\"billing_reason\":\"subscription_cycle\","
        + "\"subscription_details\":{\"metadata\":{\"request_id\":\"req-2\"}},"
        + "\"lines\":{\"data\":[{\"amount\":2500,\"price\":{\"id\":\"price_core\"},"
        + "\"period\":{\"start\":" + START + ",\"end\":" + END + "}}]}}");

    StripeInvoicePayment payment = StripeInvoicePayment.from("invoice.paid",
        event("invoice.paid", invoice)).orElseThrow();

    assertEquals("sub_2", payment.subscriptionId());
    assertEquals("req-2", payment.requestId());
    assertEquals(0, new BigDecimal("5.25").compareTo(payment.taxAmount()));
    assertEquals(StripeInvoicePayment.TYPE_RENEWAL, payment.paymentType());
    assertEquals("price_core", payment.lines().getJSONObject(0).getString("priceId"));
    assertNull(payment.invoiceNumber());
    assertEquals(Instant.ofEpochSecond(PAID_AT + 5), payment.paidAt(),
        "the event's creation stands in for a missing paid_at");
  }

  @Test
  void aZeroDecimalCurrencyKeepsItsUnits() throws Exception {
    JSONObject invoice = currentInvoice("subscription_create", 5_000).put("currency", "jpy");

    StripeInvoicePayment payment = StripeInvoicePayment.from("invoice.paid",
        event("invoice.paid", invoice)).orElseThrow();

    assertEquals(0, new BigDecimal("5000").compareTo(payment.amountPaid()));
  }

  /** The period paid for spans every line, not the invoice's own period fields. */
  @Test
  void thePeriodSpansEveryLine() throws Exception {
    JSONObject invoice = currentInvoice("subscription_update", 1_000);
    invoice.getJSONObject("lines").getJSONArray("data").put(new JSONObject(
        "{\"amount\":-500,\"period\":{\"start\":" + (START - 100) + ",\"end\":" + (END + 100)
            + "}}"));

    StripeInvoicePayment payment = StripeInvoicePayment.from("invoice.paid",
        event("invoice.paid", invoice)).orElseThrow();

    assertEquals(Instant.ofEpochSecond(START - 100), payment.periodStart());
    assertEquals(Instant.ofEpochSecond(END + 100), payment.periodEnd());
    assertEquals(2, payment.lines().length());
  }
}
