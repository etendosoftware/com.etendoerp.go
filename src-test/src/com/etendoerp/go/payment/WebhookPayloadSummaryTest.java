/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Specs for {@link WebhookPayloadSummary}, the allow-listed excerpt of a provider event stored in
 * {@code ETGO_BILLING_EVENT}.
 *
 * <p>ETP-5047 — {@code period_start}/{@code period_end} are kept for {@code invoice.*} events
 * only, so the ledger keeps a history of billed periods; for any other event type they stay out,
 * like every key not on the list.
 */
public class WebhookPayloadSummaryTest {

  private static final long START = 1793750400L;
  private static final long END = 1796428800L;

  private static JSONObject event(String type, String object) throws Exception {
    return new JSONObject("{\"id\":\"evt_1\"" + (type == null ? "" : ",\"type\":\"" + type + "\"")
        + ",\"data\":{\"object\":" + object + "}}");
  }

  private static JSONObject summary(JSONObject event) throws Exception {
    return new JSONObject(WebhookPayloadSummary.summarize(event));
  }

  private static final String INVOICE = "{\"id\":\"in_1\",\"customer\":\"cus_1\","
      + "\"subscription\":\"sub_1\",\"period_start\":" + START + ",\"period_end\":" + END
      + ",\"lines\":{\"data\":[{\"period\":{\"start\":1,\"end\":2}}]},"
      + "\"customer_email\":\"someone@example.com\"}";

  @Test
  public void invoicePaidKeepsTheInvoicePeriod() throws Exception {
    JSONObject summary = summary(event("invoice.paid", INVOICE));

    assertEquals(START, summary.getLong("period_start"));
    assertEquals(END, summary.getLong("period_end"));
    assertEquals("in_1", summary.getString("id"));
    assertEquals("sub_1", summary.getString("subscription"));
  }

  @Test
  public void invoicePaymentFailedKeepsTheInvoicePeriodToo() throws Exception {
    JSONObject summary = summary(event("invoice.payment_failed", INVOICE));

    assertEquals(START, summary.getLong("period_start"));
    assertEquals(END, summary.getLong("period_end"));
  }

  @Test
  public void theInvoiceLinesAndPersonalDataNeverReachTheSummary() throws Exception {
    JSONObject summary = summary(event("invoice.paid", INVOICE));

    assertFalse(summary.has("lines"));
    assertFalse(summary.has("customer_email"));
  }

  @Test
  public void aSubscriptionEventDropsThePeriodKeys() throws Exception {
    JSONObject summary = summary(event("customer.subscription.updated",
        "{\"id\":\"sub_1\",\"customer\":\"cus_1\",\"period_start\":" + START
            + ",\"period_end\":" + END + "}"));

    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("period_end"));
    assertEquals("sub_1", summary.getString("id"));
  }

  @Test
  public void aCheckoutEventDropsThePeriodKeys() throws Exception {
    JSONObject summary = summary(event("checkout.session.completed",
        "{\"id\":\"cs_1\",\"period_start\":" + START + ",\"period_end\":" + END
            + ",\"metadata\":{\"request_id\":\"req-1\"}}"));

    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("period_end"));
    assertEquals("req-1", summary.getJSONObject("metadata").getString("request_id"));
  }

  @Test
  public void anEventWithNoTypeDropsThePeriodKeys() throws Exception {
    JSONObject summary = summary(event(null, "{\"id\":\"x_1\",\"period_start\":" + START + "}"));

    assertFalse(summary.has("period_start"));
  }

  @Test
  public void aTypeMerelyContainingInvoiceIsNotAnInvoiceEvent() throws Exception {
    // The prefix is "invoice." — e.g. an invoiceitem.* event is not an invoice.
    JSONObject summary = summary(event("invoiceitem.created",
        "{\"id\":\"ii_1\",\"period_start\":" + START + "}"));

    assertFalse(summary.has("period_start"));
  }

  @Test
  public void anInvoiceWithoutAPeriodSimplyOmitsIt() throws Exception {
    JSONObject summary = summary(event("invoice.paid", "{\"id\":\"in_2\",\"period_end\":null}"));

    assertTrue(summary.has("id"));
    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("period_end"));
  }

  @Test
  public void nothingAllowListedYieldsNull() throws Exception {
    assertNull(WebhookPayloadSummary.summarize(event("charge.dispute.created",
        "{\"reason\":\"fraudulent\"}")));
    assertNull(WebhookPayloadSummary.summarize(null));
  }
}
