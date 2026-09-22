/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

public class SubscriptionLifecycleApplierTest {

  private final SubscriptionLifecycleApplier applier = new SubscriptionLifecycleApplier();

  private static JSONObject event(String json) throws Exception {
    return new JSONObject(json);
  }

  @Test
  public void invoicePaidClearsTheDueDate() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid",
        event("{\"data\":{\"object\":{\"subscription\":\"sub_1\"}}}"));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, outcome.status());
    assertEquals(null, outcome.dueAt());
  }

  @Test
  public void paymentFailedAnchorsOnThePaidPeriodEnd() throws Exception {
    // period_end 2026-10-31T00:00:00Z = 1793750400
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.payment_failed",
        event("{\"data\":{\"object\":{\"subscription\":\"sub_1\",\"period_end\":1793750400}}}"));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(Instant.ofEpochSecond(1793750400L), outcome.dueAt());
  }

  @Test
  public void paymentFailedWithoutAPeriodEndIsIgnoredRatherThanBlocking() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.payment_failed",
        event("{\"data\":{\"object\":{\"subscription\":\"sub_1\"}}}"));
    assertTrue(outcome.isIgnored());
    assertEquals("missing period end", outcome.reason());
  }

  @Test
  public void cancelAtPeriodEndDoesNotChangeTheStatus() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        event("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"active\","
            + "\"cancel_at_period_end\":true}}}"));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, outcome.status());
  }

  @Test
  public void subscriptionDeletedExpires() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.deleted",
        event("{\"data\":{\"object\":{\"id\":\"sub_1\"}}}"));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, outcome.status());
  }

  @Test
  public void anUnknownSubscriptionStatusIsIgnored() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        event("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"incomplete\"}}}"));
    assertTrue(outcome.isIgnored());
  }
}
