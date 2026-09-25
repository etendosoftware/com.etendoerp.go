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
 * <p>ETP-5047 — for {@code invoice.*} events only, the summary records the service period the
 * invoice bills as {@code service_period_start}/{@code service_period_end} (epoch seconds), taken
 * from the invoice LINES exactly as the subscription row is given it
 * ({@link SubscriptionLifecycleApplier#invoiceServicePeriod}): the line reaching furthest. The
 * invoice's own {@code period_start}/{@code period_end}, which look back one period, are never
 * kept; neither is anything for a non-invoice event.
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

  /** Proration line (START..START+600) listed after the renewal line (LINE_START..LINE_END). */
  private static final long LINE_START = END;
  private static final long LINE_END = END + 30L * 24 * 3600;

  private static final String INVOICE = "{\"id\":\"in_1\",\"customer\":\"cus_1\","
      + "\"subscription\":\"sub_1\",\"period_start\":" + START + ",\"period_end\":" + END
      + ",\"lines\":{\"data\":[{\"period\":{\"start\":" + LINE_START + ",\"end\":" + LINE_END
      + "}},{\"period\":{\"start\":" + START + ",\"end\":" + (START + 600) + "}}]},"
      + "\"customer_email\":\"someone@example.com\"}";

  @Test
  public void invoicePaidKeepsTheServicePeriodOfTheFurthestReachingLine() throws Exception {
    JSONObject summary = summary(event("invoice.paid", INVOICE));

    assertEquals(LINE_START, summary.getLong("service_period_start"));
    assertEquals(LINE_END, summary.getLong("service_period_end"));
    assertEquals("in_1", summary.getString("id"));
    assertEquals("sub_1", summary.getString("subscription"));
  }

  @Test
  public void theInvoiceLevelPeriodIsNeverKept() throws Exception {
    // It looks back one period on a subscription invoice: keeping it would record the wrong one.
    JSONObject summary = summary(event("invoice.paid", INVOICE));

    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("period_end"));
  }

  @Test
  public void invoicePaymentFailedKeepsTheServicePeriodToo() throws Exception {
    JSONObject summary = summary(event("invoice.payment_failed", INVOICE));

    assertEquals(LINE_START, summary.getLong("service_period_start"));
    assertEquals(LINE_END, summary.getLong("service_period_end"));
    assertFalse(summary.has("period_start"));
  }

  @Test
  public void anInvoiceWithoutLinesRecordsNeitherKey() throws Exception {
    JSONObject summary = summary(event("invoice.paid", "{\"id\":\"in_2\",\"period_start\":"
        + START + ",\"period_end\":" + END + "}"));

    assertFalse(summary.has("service_period_start"));
    assertFalse(summary.has("service_period_end"));
    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("period_end"));
  }

  @Test
  public void aLineWithoutAStartRecordsOnlyTheEnd() throws Exception {
    JSONObject summary = summary(event("invoice.paid",
        "{\"id\":\"in_3\",\"lines\":{\"data\":[{\"period\":{\"end\":" + LINE_END + "}}]}}"));

    assertFalse(summary.has("service_period_start"));
    assertEquals(LINE_END, summary.getLong("service_period_end"));
  }

  @Test
  public void theLedgerAndTheRowRecordTheSamePeriodForTheSameInvoice() throws Exception {
    JSONObject paid = event("invoice.paid", INVOICE);

    JSONObject summary = summary(paid);
    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate("invoice.paid",
        paid);

    assertEquals(outcome.periodStart().getEpochSecond(), summary.getLong("service_period_start"));
    assertEquals(outcome.periodEnd().getEpochSecond(), summary.getLong("service_period_end"));
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
            + ",\"period_end\":" + END + ",\"lines\":{\"data\":[{\"period\":{\"start\":"
            + LINE_START + ",\"end\":" + LINE_END + "}}]}}"));

    assertFalse(summary.has("service_period_start"));
    assertFalse(summary.has("service_period_end"));
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
    JSONObject summary = summary(event(null, "{\"id\":\"x_1\",\"period_start\":" + START
        + ",\"lines\":{\"data\":[{\"period\":{\"start\":" + LINE_START + ",\"end\":" + LINE_END
        + "}}]}}"));

    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("service_period_end"));
  }

  @Test
  public void aTypeMerelyContainingInvoiceIsNotAnInvoiceEvent() throws Exception {
    // The prefix is "invoice." — e.g. an invoiceitem.* event is not an invoice.
    JSONObject summary = summary(event("invoiceitem.created",
        "{\"id\":\"ii_1\",\"period_start\":" + START + ",\"lines\":{\"data\":[{\"period\":"
            + "{\"start\":" + LINE_START + ",\"end\":" + LINE_END + "}}]}}"));

    assertFalse(summary.has("period_start"));
    assertFalse(summary.has("service_period_end"));
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
