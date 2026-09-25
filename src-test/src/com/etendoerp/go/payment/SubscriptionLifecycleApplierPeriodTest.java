/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * ETP-5047 — what an applied lifecycle outcome carries beyond the access state: the provider
 * billing period (for {@code CURRENT_PERIOD_START/END}) and whether the subscription is terminated
 * (closing its row, {@code END_DATE}).
 *
 * <ul>
 *   <li>{@code invoice.paid} reads the period from the invoice <em>lines</em> — the line reaching
 *       furthest — never from the invoice's own {@code period_*}, which looks back one period.</li>
 *   <li>{@code customer.subscription.*} reads it from the subscription or its first item.</li>
 *   <li>A half or reversed period is never carried: it would overwrite a whole one.</li>
 *   <li>{@code customer.subscription.deleted} and an update to {@code canceled} close the row at
 *       {@code ended_at}, else {@code canceled_at}, else "now" (null).</li>
 * </ul>
 */
public class SubscriptionLifecycleApplierPeriodTest {

  private static final long MONTH = 30L * 24 * 3600;
  /** 2026-11-04T00:00:00Z. */
  private static final long NOV = 1793750400L;
  private static final long DEC = NOV + MONTH;
  private static final long JAN = DEC + MONTH;

  private final SubscriptionLifecycleApplier applier = new SubscriptionLifecycleApplier();

  private static Instant at(long epochSeconds) {
    return Instant.ofEpochSecond(epochSeconds);
  }

  // ===================== invoice.paid: period from the lines =====================

  @Test
  public void invoicePaidTakesThePeriodOfTheLineReachingFurthest() throws Exception {
    // A proration line (NOV..DEC) and the renewal line (DEC..JAN), listed proration last. The
    // invoice's own period_* looks back one period (NOV..DEC) and must not be used.
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid", new JSONObject(
        "{\"data\":{\"object\":{\"subscription\":\"sub_1\",\"period_start\":" + NOV
            + ",\"period_end\":" + DEC + ",\"lines\":{\"data\":["
            + "{\"period\":{\"start\":" + DEC + ",\"end\":" + JAN + "}},"
            + "{\"period\":{\"start\":" + NOV + ",\"end\":" + DEC + "}}]}}}}"));

    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, outcome.status());
    assertNull("a paid invoice clears the grace anchor", outcome.dueAt());
    assertEquals(at(DEC), outcome.periodStart());
    assertEquals(at(JAN), outcome.periodEnd());
    assertFalse(outcome.closesSubscription());
  }

  @Test
  public void invoicePaidWithNoLinesCarriesNoPeriodEvenWhenTheInvoiceHasOne() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid", new JSONObject(
        "{\"data\":{\"object\":{\"subscription\":\"sub_1\",\"period_start\":" + NOV
            + ",\"period_end\":" + DEC + "}}}"));

    assertTrue(outcome.isApplied());
    assertNull(outcome.periodStart());
    assertNull(outcome.periodEnd());
  }

  @Test
  public void invoicePaidSkipsLinesWithoutAPeriod() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid", new JSONObject(
        "{\"data\":{\"object\":{\"subscription\":\"sub_1\",\"lines\":{\"data\":["
            + "{\"description\":\"tax\"},null,{\"period\":{\"start\":" + DEC + ",\"end\":" + JAN
            + "}}]}}}}"));

    assertEquals(at(DEC), outcome.periodStart());
    assertEquals(at(JAN), outcome.periodEnd());
  }

  @Test
  public void invoicePaidWhoseFurthestLineHasNoStartCarriesNoPeriod() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("invoice.paid", new JSONObject(
        "{\"data\":{\"object\":{\"subscription\":\"sub_1\",\"lines\":{\"data\":["
            + "{\"period\":{\"end\":" + JAN + "}}]}}}}"));

    assertTrue(outcome.isApplied());
    assertNull(outcome.periodStart());
    assertNull(outcome.periodEnd());
  }

  // ===================== customer.subscription.updated: period carried =====================

  @Test
  public void subscriptionUpdatedCarriesTheTopLevelPeriod() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"active\","
            + "\"current_period_start\":" + DEC + ",\"current_period_end\":" + JAN + "}}}"));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, outcome.status());
    assertEquals(at(DEC), outcome.periodStart());
    assertEquals(at(JAN), outcome.periodEnd());
    assertFalse(outcome.closesSubscription());
  }

  @Test
  public void subscriptionUpdatedReadsThePeriodFromTheFirstItem() throws Exception {
    // API 2025-03-31+ / billing_mode flexible: the period lives on the items.
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"active\","
            + "\"items\":{\"data\":[{\"current_period_start\":" + DEC
            + ",\"current_period_end\":" + JAN + "}]}}}}"));

    assertEquals(at(DEC), outcome.periodStart());
    assertEquals(at(JAN), outcome.periodEnd());
  }

  @Test
  public void aSubscriptionWithHalfAPeriodCarriesNone() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"active\","
            + "\"current_period_end\":" + JAN + "}}}"));

    assertTrue(outcome.isApplied());
    assertNull(outcome.periodStart());
    assertNull(outcome.periodEnd());
  }

  // ===================== termination =====================

  @Test
  public void subscriptionDeletedClosesAtEndedAtAndCarriesItsPeriod() throws Exception {
    long endedAt = DEC + 3600L;
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.deleted",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"canceled\","
            + "\"ended_at\":" + endedAt + ",\"canceled_at\":" + NOV
            + ",\"current_period_start\":" + NOV + ",\"current_period_end\":" + DEC + "}}}"));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, outcome.status());
    assertTrue(outcome.closesSubscription());
    assertEquals("ended_at wins over canceled_at", at(endedAt), outcome.endedAt());
    assertEquals(at(NOV), outcome.periodStart());
    assertEquals(at(DEC), outcome.periodEnd());
  }

  @Test
  public void subscriptionDeletedWithoutEndedAtClosesAtCanceledAt() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.deleted",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"canceled_at\":" + NOV + "}}}"));

    assertTrue(outcome.closesSubscription());
    assertEquals(at(NOV), outcome.endedAt());
  }

  @Test
  public void subscriptionDeletedWithNoInstantClosesNow() throws Exception {
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.deleted",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\"}}}"));

    assertTrue(outcome.closesSubscription());
    assertNull("null means 'now' at write time", outcome.endedAt());
  }

  @Test
  public void anUpdateToCanceledClosesTheRowTooAndKeepsThePeriod() throws Exception {
    // Terminal at Stripe: a canceled subscription cannot be revived, so the update closes the
    // row without waiting for customer.subscription.deleted.
    SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
        new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"canceled\","
            + "\"canceled_at\":" + NOV + ",\"current_period_start\":" + NOV
            + ",\"current_period_end\":" + DEC + "}}}"));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, outcome.status());
    assertTrue(outcome.closesSubscription());
    assertEquals(at(NOV), outcome.endedAt());
    assertEquals(at(NOV), outcome.periodStart());
    assertEquals(at(DEC), outcome.periodEnd());
  }

  @Test
  public void anUpdateToAnyLiveStatusDoesNotClose() throws Exception {
    for (String status : new String[] { "active", "trialing" }) {
      SubscriptionEventOutcome outcome = applier.evaluate("customer.subscription.updated",
          new JSONObject("{\"data\":{\"object\":{\"id\":\"sub_1\",\"status\":\"" + status
              + "\",\"canceled_at\":" + NOV + "}}}"));
      assertFalse(status, outcome.closesSubscription());
    }
  }

  @Test
  public void subscriptionEndedAtPrefersEndedAtThenCanceledAt() throws Exception {
    assertEquals(at(DEC), SubscriptionLifecycleApplier.subscriptionEndedAt(
        new JSONObject("{\"ended_at\":" + DEC + ",\"canceled_at\":" + NOV + "}")));
    assertEquals(at(NOV), SubscriptionLifecycleApplier.subscriptionEndedAt(
        new JSONObject("{\"canceled_at\":" + NOV + "}")));
    assertNull(SubscriptionLifecycleApplier.subscriptionEndedAt(new JSONObject("{}")));
  }

  // ===================== SubscriptionEventOutcome.withPeriod / closing =====================

  @Test
  public void withPeriodCarriesOnlyAWholeOrderedPeriod() {
    SubscriptionEventOutcome current =
        SubscriptionEventOutcome.apply(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);

    assertSame("no start", current, current.withPeriod(null, at(JAN)));
    assertSame("no end", current, current.withPeriod(at(DEC), null));
    assertSame("reversed", current, current.withPeriod(at(JAN), at(DEC)));

    SubscriptionEventOutcome instant = current.withPeriod(at(DEC), at(DEC));
    assertEquals("a zero-length period is ordered", at(DEC), instant.periodEnd());

    SubscriptionEventOutcome whole = current.withPeriod(at(DEC), at(JAN));
    assertEquals(at(DEC), whole.periodStart());
    assertEquals(at(JAN), whole.periodEnd());
    assertEquals(current.status(), whole.status());
  }

  @Test
  public void anIgnoredOutcomeTakesNeitherAPeriodNorAClose() {
    SubscriptionEventOutcome ignored = SubscriptionEventOutcome.ignore("stale event");

    assertSame(ignored, ignored.withPeriod(at(DEC), at(JAN)));
    assertSame(ignored, ignored.closing(at(DEC)));
    assertFalse(ignored.closesSubscription());
  }

  @Test
  public void closingKeepsTheStatusAnchorAndPeriod() {
    SubscriptionEventOutcome closed = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, at(NOV))
        .withPeriod(at(NOV), at(DEC)).closing(at(DEC));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, closed.status());
    assertEquals(at(NOV), closed.dueAt());
    assertEquals(at(NOV), closed.periodStart());
    assertEquals(at(DEC), closed.periodEnd());
    assertTrue(closed.closesSubscription());
    assertEquals(at(DEC), closed.endedAt());
  }
}
