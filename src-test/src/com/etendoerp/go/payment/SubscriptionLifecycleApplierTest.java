/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
    // period_end 2026-11-04T00:00:00Z = 1793750400
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

  // ===================== Past-due subscriptions anchor on the paid period =====================

  /** 2026-11-04T00:00:00Z: end of the paid period, start of the unpaid one. */
  private static final long PAID_PERIOD_END = 1793750400L;
  /** 2026-12-05T00:00:00Z: end of the new, unpaid period Stripe advanced to at renewal. */
  private static final long UNPAID_PERIOD_END = 1796428800L;
  private static final long CREATED = 1793800000L;

  private static JSONObject pastDueSubscription(String status) throws Exception {
    // A realistic renewal failure: Stripe has already advanced the period, so start < end and
    // only the start is the end of what the customer paid for.
    return event("{\"created\":" + CREATED + ",\"data\":{\"object\":{\"id\":\"sub_1\","
        + "\"status\":\"" + status + "\",\"current_period_start\":" + PAID_PERIOD_END
        + ",\"current_period_end\":" + UNPAID_PERIOD_END + "}}}");
  }

  @Test
  public void pastDueSubscriptionAnchorsOnThePeriodStartNotTheAdvancedEnd() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  @Test
  public void unpaidSubscriptionAnchorsOnThePeriodStartToo() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("unpaid"));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  /** API 2025-03-31 and later moved the period onto the subscription item. */
  @Test
  public void pastDueSubscriptionReadsThePeriodStartFromTheFirstItem() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        event("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"past_due\","
            + "\"items\":{\"data\":[{\"current_period_start\":" + PAID_PERIOD_END
            + ",\"current_period_end\":" + UNPAID_PERIOD_END + "}]}}}}"));
    assertTrue(outcome.isApplied());
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  /** Only the advanced end is present: using it would grant a whole unpaid period of grace. */
  @Test
  public void pastDueSubscriptionWithoutAPeriodStartIsIgnored() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        event("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"past_due\","
            + "\"current_period_end\":" + UNPAID_PERIOD_END + ",\"items\":{\"data\":[{"
            + "\"current_period_end\":" + UNPAID_PERIOD_END + "}]}}}}"));
    assertTrue(outcome.isIgnored());
    assertEquals("missing period end", outcome.reason());
  }

  @Test
  public void pastDueSubscriptionWithEmptyItemsIsIgnored() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        event("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"past_due\","
            + "\"items\":{\"data\":[]}}}}"));
    assertTrue(outcome.isIgnored());
    assertEquals("missing period end", outcome.reason());
  }

  /** The due date stored by invoice.payment_failed is authoritative and survives the update. */
  @Test
  public void storedPastDueDueDateIsKeptOverThePayloadPeriod() throws Exception {
    Instant authoritative = Instant.ofEpochSecond(PAID_PERIOD_END - 86400L);
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, authoritative, null));
    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
    assertEquals(authoritative, outcome.dueAt());
  }

  @Test
  public void storedPastDueWithoutADueDateFallsBackToThePeriodStart() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, null, null));
    assertTrue(outcome.isApplied());
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  /** A due date left over under another status is not authoritative for this overdue spell. */
  @Test
  public void storedDueDateUnderCurrentStatusIsNotReused() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT,
            Instant.ofEpochSecond(PAID_PERIOD_END - 86400L), null));
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  @Test
  public void paymentFailedWithOnlyCurrentPeriodEndIsIgnored() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.payment_failed",
        event("{\"data\":{\"object\":{\"subscription\":\"sub_1\",\"current_period_end\":"
            + UNPAID_PERIOD_END + "}}}"));
    assertTrue(outcome.isIgnored());
    assertEquals("missing period end", outcome.reason());
  }

  // ===================== Out-of-order delivery =====================

  private static SubscriptionLifecycleApplier.StoredState lastAppliedAt(long epochSeconds) {
    return new SubscriptionLifecycleApplier.StoredState(
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null,
        Instant.ofEpochSecond(epochSeconds));
  }

  @Test
  public void eventCreatedBeforeTheLastAppliedOneIsIgnoredAsStale() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), lastAppliedAt(CREATED + 1L));
    assertTrue(outcome.isIgnored());
    assertEquals("stale event", outcome.reason());
  }

  /** Staleness is decided before the type, so even a stale payment confirmation is dropped. */
  @Test
  public void staleInvoicePaidIsIgnoredToo() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid",
        event("{\"created\":" + CREATED + ",\"data\":{\"object\":{}}}"),
        lastAppliedAt(CREATED + 60L));
    assertTrue(outcome.isIgnored());
    assertEquals("stale event", outcome.reason());
  }

  @Test
  public void eventCreatedAtTheSameInstantIsApplied() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), lastAppliedAt(CREATED));
    assertTrue(outcome.isApplied());
  }

  @Test
  public void eventCreatedAfterTheLastAppliedOneIsApplied() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), lastAppliedAt(CREATED - 1L));
    assertTrue(outcome.isApplied());
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  @Test
  public void eventWithoutCreatedIsNeverStale() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid",
        event("{\"data\":{\"object\":{}}}"), lastAppliedAt(CREATED));
    assertTrue(outcome.isApplied());
  }

  @Test
  public void storedStateWithoutALastEventNeverMakesAnEventStale() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid",
        event("{\"created\":1,\"data\":{\"object\":{}}}"),
        new SubscriptionLifecycleApplier.StoredState(
            EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, null, null));
    assertTrue(outcome.isApplied());
  }

  @Test
  public void nullStoredStateIsTreatedAsEmpty() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        pastDueSubscription("past_due"), null);
    assertTrue(outcome.isApplied());
    assertEquals(Instant.ofEpochSecond(PAID_PERIOD_END), outcome.dueAt());
  }

  @Test
  public void emptyStoredStateHasNoFields() {
    assertNull(SubscriptionLifecycleApplier.StoredState.NONE.status());
    assertNull(SubscriptionLifecycleApplier.StoredState.NONE.dueAt());
    assertNull(SubscriptionLifecycleApplier.StoredState.NONE.lastEventAt());
  }

  // ===================== Subscription id and event instant =====================

  @Test
  public void subscriptionEventIsKeyedByItsObjectId() throws Exception {
    assertEquals("sub_1", SubscriptionLifecycleApplier.subscriptionIdOf(
        "customer.subscription.updated",
        new JSONObject("{\"id\":\" sub_1 \",\"subscription\":\"sub_other\"}")));
    assertEquals("sub_2", SubscriptionLifecycleApplier.subscriptionIdOf(
        "customer.subscription.deleted", new JSONObject("{\"id\":\"sub_2\"}")));
  }

  @Test
  public void invoiceEventIsKeyedByItsTopLevelSubscription() throws Exception {
    assertEquals("sub_top", SubscriptionLifecycleApplier.subscriptionIdOf("invoice.paid",
        new JSONObject("{\"id\":\"in_1\",\"subscription\":\"sub_top\",\"parent\":{"
            + "\"subscription_details\":{\"subscription\":\"sub_parent\"}}}")));
  }

  /** API 2025-03-31 and later: the invoice names its subscription only under parent. */
  @Test
  public void invoiceEventFallsBackToParentSubscriptionDetails() throws Exception {
    assertEquals("sub_parent", SubscriptionLifecycleApplier.subscriptionIdOf(
        "invoice.payment_failed", new JSONObject("{\"id\":\"in_1\",\"parent\":{"
            + "\"subscription_details\":{\"subscription\":\"sub_parent\"}}}")));
  }

  @Test
  public void eventNamingNoSubscriptionYieldsAnEmptyId() throws Exception {
    assertEquals("", SubscriptionLifecycleApplier.subscriptionIdOf("invoice.paid",
        new JSONObject("{\"id\":\"in_1\"}")));
    assertEquals("", SubscriptionLifecycleApplier.subscriptionIdOf("invoice.paid",
        new JSONObject("{\"id\":\"in_1\",\"parent\":{}}")));
    assertEquals("", SubscriptionLifecycleApplier.subscriptionIdOf("invoice.paid", null));
  }

  /**
   * An invoice not tied to any subscription reports {@code subscription} as JSON null, not an
   * absent key; that must not be read back as the literal id "null".
   */
  @Test
  public void invoiceWithANullSubscriptionIsNotTreatedAsTheLiteralIdNull() throws Exception {
    assertEquals("", SubscriptionLifecycleApplier.subscriptionIdOf("invoice.paid",
        new JSONObject("{\"id\":\"in_1\",\"subscription\":null}")));
    assertEquals("", SubscriptionLifecycleApplier.subscriptionIdOf("invoice.payment_failed",
        new JSONObject("{\"id\":\"in_1\",\"subscription\":null,\"parent\":{"
            + "\"subscription_details\":{\"subscription\":null}}}")));
  }

  @Test
  public void eventCreatedAtReadsTheEnvelopeCreatedField() throws Exception {
    assertEquals(Instant.ofEpochSecond(CREATED), SubscriptionLifecycleApplier.eventCreatedAt(
        event("{\"created\":" + CREATED + "}")));
    assertNull(SubscriptionLifecycleApplier.eventCreatedAt(event("{\"id\":\"evt_1\"}")));
    assertNull(SubscriptionLifecycleApplier.eventCreatedAt(null));
  }
}
