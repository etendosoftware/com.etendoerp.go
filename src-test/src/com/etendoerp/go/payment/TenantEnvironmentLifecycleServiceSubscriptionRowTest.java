/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Specs for where a Stripe lifecycle outcome lands and is read back from (ETP-5046, ETP-5047).
 *
 * <p><b>Two stores, chosen per tenant.</b> A tenant with an {@code ETGO_SUBSCRIPTION} row has its
 * status, grace anchor ({@code GRACE_ANCHOR}), billing period and ordering watermark
 * ({@code LAST_EVENT_AT}) written onto that row and read back from it; a tenant the R37 backfill
 * has not reached keeps the {@code ETGO_SubscriptionStatus} / {@code ETGO_SubscriptionDueAt} /
 * {@code ETGO_SubscriptionEventAt} preferences. Writing to one store and reading from the other is
 * the regression these specs exist for: it is silent, and it makes a paying tenant's access
 * decision disagree with the plan catalog.
 *
 * <p>ETP-5047 — the row route reads <em>no</em> preference: the watermark moved onto the row, so a
 * stale preference can neither block nor unblock an event for a tenant that has a row. A closed
 * (canceled) row still answers for the tenant, so it never falls through to the preferences.
 *
 * <p>A real {@link SubscriptionService} runs against a mocked DAL whose preference and
 * subscription queries — and subscription saves — fail unless admin mode is active, the way
 * {@code OBDal} behaves for a role that cannot read {@code AD_Preference} / {@code ETGO_SUBSCRIPTION}
 * (same technique as {@link TenantEnvironmentLifecycleServiceAdminModeTest}, ETP-5488).
 *
 * @covers com.etendoerp.go.payment.TenantEnvironmentLifecycleService
 */
public class TenantEnvironmentLifecycleServiceSubscriptionRowTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String STRIPE_SUBSCRIPTION = "sub_current";
  private static final Instant ANCHOR = Instant.parse("2026-10-31T00:00:00Z");
  private static final Instant EVENT_AT = Instant.parse("2026-11-01T12:30:00Z");
  private static final Instant PERIOD_START = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant PERIOD_END = Instant.parse("2026-12-01T00:00:00Z");
  private static final Instant ENDED_AT = Instant.parse("2026-11-15T09:00:00Z");

  private final TenantPlanService tenantPlanService = mock(TenantPlanService.class);
  private final TenantEnvironmentLifecycleService service =
      new TenantEnvironmentLifecycleService(tenantPlanService, new SubscriptionService());

  /**
   * The production path of an outcome resolved through the tenant rather than a row match (the
   * webhook's checkout-request fallback): {@code targetForTenant}, then
   * {@code applySubscriptionEvent}, with a status-only outcome and no watermark.
   */
  private boolean storeForTenant(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt) {
    return service.applySubscriptionEvent(service.targetForTenant(CLIENT_ID, null),
        SubscriptionEventOutcome.apply(status, dueAt), null);
  }

  /** The stored state an event for the tenant is decided against, as the webhook reads it. */
  private SubscriptionLifecycleApplier.StoredState storedStateOfTenant() {
    return service.targetForTenant(CLIENT_ID, null).storedState();
  }

  // ===================== write routing (tenant level) =====================

  @Test
  public void aTenantWithARowGetsCurrentWrittenAsActiveOnTheRowAndNoPreference() {
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR);

    assertTrue(fixture.run(() -> storeForTenant(
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
    // null clears the anchor, exactly as it clears the preference projection's due date.
    verify(fixture.row).setGraceAnchor(null);
    // ETP-5047 — the billing period is not the anchor's column any more, and a status-only
    // outcome carries no period: it must be left alone.
    verify(fixture.row, never()).setCurrentPeriodEnd(any());
    verify(fixture.row, never()).setCurrentPeriodStart(any());
    assertTrue("the row route must not also write the preference projection",
        fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTenantWithARowGetsPastDueAndItsGraceAnchorOnTheRow() {
    Fixture fixture = new Fixture().withOpenRow("active", null);

    assertTrue(fixture.run(() -> storeForTenant(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_PAST_DUE);
    verify(fixture.row).setGraceAnchor(Date.from(ANCHOR));
    verify(fixture.row, never()).setCurrentPeriodEnd(any());
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTenantWithARowGetsExpiredAsCanceledWithoutClosingTheRow() {
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR);

    assertTrue(fixture.run(() -> storeForTenant(
        EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_CANCELED);
    // A status-only outcome routed through the tenant never closes a row: only a terminating
    // webhook outcome does (ETP-5047).
    verify(fixture.row, never()).setEndDate(any());
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTenantWithoutARowKeepsThePreferenceRoute() {
    Fixture fixture = new Fixture().asWebhook();

    assertTrue(fixture.run(() -> storeForTenant(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)));

    assertEquals(List.of(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE,
        TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE),
        fixture.savedPreferenceAttributes);
    assertEquals(List.of("PAST_DUE", ANCHOR.toString()), fixture.savedPreferenceValues);
    verify(fixture.dal, never()).save(any(Subscription.class));
  }

  @Test
  public void aTenantWhoseOnlyRowIsClosedStoresNothingAtTheTenantLevel() {
    // ETP-5047 — a canceled tenant's closed row must not be revived by a tenant-level write, and
    // the preference projection is only for a tenant that never had a row.
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT).asWebhook();

    assertFalse(fixture.run(() -> storeForTenant(
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)));

    verify(fixture.dal, never()).save(any());
    verify(fixture.closedRow, never()).setSubscriptionStatus(anyString());
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  // ===================== targetForSubscription / targetForTenant =====================

  @Test
  public void targetForSubscriptionResolvesTheOpenRowCarryingTheIdAndReadsItsStateFromTheRow() {
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR)
        .withStripeSubscription(STRIPE_SUBSCRIPTION).withLastEventAt(EVENT_AT)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            "2020-01-01T00:00:00Z");

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForSubscription(STRIPE_SUBSCRIPTION));

    assertFalse(target.isIgnored());
    assertSame(fixture.row, target.subscription());
    assertEquals(CLIENT_ID, target.clientId());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, target.storedState().status());
    assertEquals(ANCHOR, target.storedState().dueAt());
    assertEquals(EVENT_AT, target.storedState().lastEventAt());
    assertTrue("the row route reads no preference", fixture.queriedPreferenceAttributes.isEmpty());
  }

  @Test
  public void targetForSubscriptionFindsNothingForAnotherIdOrABlankOne() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    assertNull(fixture.run(() -> service.targetForSubscription("sub_other")));
    assertNull(fixture.run(() -> service.targetForSubscription("  ")));
    assertNull(fixture.run(() -> service.targetForSubscription(null)));
  }

  @Test
  public void targetForSubscriptionIgnoresAClosedRowCarryingTheId() {
    // STRIPE_SUBSCRIPTION_ID is not unique: only the open row answers.
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    assertNull(fixture.run(() -> service.targetForSubscription(STRIPE_SUBSCRIPTION)));
  }

  @Test
  public void targetForTenantWithNoRowIsThePreferenceProjectionWithItsWatermark() {
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString())
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, STRIPE_SUBSCRIPTION));

    assertFalse(target.isIgnored());
    assertNull("the preference route has no row", target.subscription());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, target.storedState().status());
    assertEquals(ANCHOR, target.storedState().dueAt());
    assertEquals(EVENT_AT, target.storedState().lastEventAt());
  }

  @Test
  public void targetForTenantTakesAnOpenRowThatNamesNoSubscription() {
    // A backfilled row may carry no Stripe id.
    Fixture fixture = new Fixture().withOpenRow("active", null);

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, STRIPE_SUBSCRIPTION));

    assertFalse(target.isIgnored());
    assertSame(fixture.row, target.subscription());
  }

  @Test
  public void targetForTenantTakesAnOpenRowThatNamesThisSubscription() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, STRIPE_SUBSCRIPTION));

    assertSame(fixture.row, target.subscription());
  }

  @Test
  public void targetForTenantIgnoresAnEventForAnotherSubscription() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, "sub_replaced"));

    assertTrue(target.isIgnored());
    assertEquals("event for another subscription", target.ignoreReason());
    assertNull(target.subscription());
    assertSame(SubscriptionLifecycleApplier.StoredState.NONE, target.storedState());
  }

  @Test
  public void targetForTenantIgnoresALateEventOfTheClosedSubscription() {
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, STRIPE_SUBSCRIPTION));

    assertTrue(target.isIgnored());
    assertEquals("subscription closed", target.ignoreReason());
  }

  @Test
  public void targetForTenantIgnoresAnEventWithNoIdWhenTheOnlyRowIsClosed() {
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, null));

    assertEquals("subscription closed", target.ignoreReason());
  }

  @Test
  public void targetForTenantIgnoresTheFirstEventOfANewPurchaseBeforeItsRowIsOpened() {
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForTenant(CLIENT_ID, "sub_new_purchase"));

    assertTrue(target.isIgnored());
    assertEquals("no open subscription row", target.ignoreReason());
  }

  // ===================== applySubscriptionEvent =====================

  @Test
  public void aRowTargetGetsStatusAnchorPeriodAndWatermarkOnTheRowAndNoPreference() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)
        .withPeriod(PERIOD_START, PERIOD_END);

    boolean stored = fixture.run(() -> service.applySubscriptionEvent(
        service.targetForSubscription(STRIPE_SUBSCRIPTION), outcome, EVENT_AT));

    assertTrue(stored);
    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_PAST_DUE);
    verify(fixture.row).setGraceAnchor(Date.from(ANCHOR));
    verify(fixture.row).setCurrentPeriodStart(Date.from(PERIOD_START));
    verify(fixture.row).setCurrentPeriodEnd(Date.from(PERIOD_END));
    verify(fixture.row).setLastEventAt(Date.from(EVENT_AT));
    verify(fixture.dal).save(fixture.row);
    assertTrue("the row route writes no preference, not even the watermark",
        fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTerminatingOutcomeClosesTheRowTarget() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null).closing(ENDED_AT);

    assertTrue(fixture.run(() -> service.applySubscriptionEvent(
        service.targetForSubscription(STRIPE_SUBSCRIPTION), outcome, EVENT_AT)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_CANCELED);
    verify(fixture.row).setEndDate(Date.from(ENDED_AT));
  }

  @Test
  public void aPreferenceTargetWritesStatusDueAtAndTheEventAtWatermark() {
    Fixture fixture = new Fixture().asWebhook();
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)
        .withPeriod(PERIOD_START, PERIOD_END);

    assertTrue(fixture.run(() -> service.applySubscriptionEvent(
        service.targetForTenant(CLIENT_ID, STRIPE_SUBSCRIPTION), outcome, EVENT_AT)));

    // The preference projection never knew the billing period; only the three lifecycle values.
    assertEquals(List.of(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE,
        TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
        TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE),
        fixture.savedPreferenceAttributes);
    assertEquals(List.of("PAST_DUE", ANCHOR.toString(), EVENT_AT.toString()),
        fixture.savedPreferenceValues);
    verify(fixture.dal, never()).save(any(Subscription.class));
  }

  @Test
  public void aFailedWatermarkWriteOnThePreferenceRoutePropagatesSoTheWebhookAnswers500() {
    // The status write succeeds; the watermark's own Client lookup then comes back empty. It sits
    // outside the status write's catch on purpose: the webhook must roll back and the provider
    // redeliver, instead of committing a status with no watermark.
    Fixture fixture = new Fixture().asWebhook().clientVanishesAfterFirstLookup();
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR);

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> fixture.run(() -> service.applySubscriptionEvent(
            service.targetForTenant(CLIENT_ID, null), outcome, EVENT_AT)));

    assertEquals("Client not found while recording a subscription event", thrown.getMessage());
  }

  @Test
  public void aFailedRowWriteAnswersFalseSoTheWebhookMarksTheEventFailed() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION).failingSubscriptionSave();
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR);

    assertFalse(fixture.run(() -> service.applySubscriptionEvent(
        service.targetForSubscription(STRIPE_SUBSCRIPTION), outcome, EVENT_AT)));
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aFailedPreferenceStatusWriteAnswersFalseAndWritesNoWatermark() {
    Fixture fixture = new Fixture().asWebhook().withNoClient();
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR);

    assertFalse(fixture.run(() -> service.applySubscriptionEvent(
        service.targetForTenant(CLIENT_ID, null), outcome, EVENT_AT)));
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void anIgnoredTargetOrOutcomeOrANullOneStoresNothing() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION);
    SubscriptionEventOutcome applied = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);

    fixture.run(() -> {
      TenantEnvironmentLifecycleService.LifecycleTarget row =
          service.targetForSubscription(STRIPE_SUBSCRIPTION);
      assertFalse(service.applySubscriptionEvent(null, applied, EVENT_AT));
      assertFalse(service.applySubscriptionEvent(
          TenantEnvironmentLifecycleService.LifecycleTarget.ignored(CLIENT_ID, "subscription closed"),
          applied, EVENT_AT));
      assertFalse(service.applySubscriptionEvent(row, null, EVENT_AT));
      assertFalse(service.applySubscriptionEvent(row,
          SubscriptionEventOutcome.ignore("stale event"), EVENT_AT));
      return null;
    });

    verify(fixture.dal, never()).save(any());
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  // ===================== read routing =====================

  @Test
  public void aTenantWithARowIsReadBackFromTheRowNotFromStalePreferences() {
    // The preferences still hold what the tenant had before it moved onto a row. They must lose —
    // the watermark included, which lives in LAST_EVENT_AT since ETP-5047.
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR).withLastEventAt(EVENT_AT)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            "2020-01-01T00:00:00Z")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            "2020-01-01T00:00:00Z");

    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(this::storedStateOfTenant);

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, state.status());
    assertEquals(ANCHOR, state.dueAt());
    assertEquals(EVENT_AT, state.lastEventAt());
    assertTrue("the row route reads no preference", fixture.queriedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aRowWithNoWatermarkFallsBackToThePreferenceWatermark() {
    // Review fix W1 — a row written before LAST_EVENT_AT existed has its watermark in the
    // ETGO_SubscriptionEventAt preference. Reading the column alone would let the first
    // out-of-order event after the deploy pass as fresh, so a null column falls back, read-only.
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());

    assertEquals(EVENT_AT,
        fixture.run(this::storedStateOfTenant).lastEventAt());
  }

  @Test
  public void anOlderEventIsStaleAgainstThePreferenceWatermarkOfARowWithoutOne()
      throws Exception {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());

    TenantEnvironmentLifecycleService.LifecycleTarget target =
        fixture.run(() -> service.targetForSubscription(STRIPE_SUBSCRIPTION));
    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate(
        SubscriptionLifecycleApplier.INVOICE_PAYMENT_FAILED,
        paymentFailed(EVENT_AT.minusSeconds(60L)), target.storedState());

    assertEquals(EVENT_AT, target.storedState().lastEventAt());
    assertTrue(outcome.isIgnored());
    assertEquals("stale event", outcome.reason());
  }

  @Test
  public void aRowWithAWatermarkNeverReadsThePreference() {
    Fixture fixture = new Fixture().withOpenRow("active", null).withLastEventAt(EVENT_AT)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            "2020-01-01T00:00:00Z");

    assertEquals(EVENT_AT,
        fixture.run(this::storedStateOfTenant).lastEventAt());
    assertFalse(fixture.queriedPreferenceAttributes.contains(
        TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE));
  }

  @Test
  public void theWatermarkFallbackIsReadOnlyTheWriteGoesToTheRow() {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withStripeSubscription(STRIPE_SUBSCRIPTION)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());
    Instant newer = EVENT_AT.plusSeconds(60L);
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR);

    assertTrue(fixture.run(() -> service.applySubscriptionEvent(
        service.targetForSubscription(STRIPE_SUBSCRIPTION), outcome, newer)));

    verify(fixture.row).setLastEventAt(Date.from(newer));
    assertTrue("the row route never writes the preference watermark",
        fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aPastDueRowInThePreSplitShapeReadsItsAnchorFromThePeriodEnd() {
    // Anchor in CURRENT_PERIOD_END, no start, no GRACE_ANCHOR: what the old lifecycle write and the
    // pre-ETP-5047 R37 left. Without the fallback it would read as zero grace — blocked on deploy.
    Fixture fixture = new Fixture().withLegacyOpenRow("past_due", ANCHOR);

    assertEquals(ANCHOR, fixture.run(this::storedStateOfTenant).dueAt());
  }

  @Test
  public void anActiveRowWithABillingPeriodHasNoAnchor() {
    // The billing period is not a grace anchor: only past_due falls back to it.
    Fixture fixture = new Fixture().withLegacyOpenRow("active", PERIOD_END);

    assertNull(fixture.run(this::storedStateOfTenant).dueAt());
  }

  @Test
  public void aCanceledRowIsReadBackAsExpiredWithAClearedAnchor() {
    Fixture fixture = new Fixture().withOpenRow("canceled", null);

    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(this::storedStateOfTenant);

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, state.status());
    assertNull(state.dueAt());
  }

  @Test
  public void aTenantWithoutARowIsReadBackFromThePreferences() {
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString())
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());

    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(this::storedStateOfTenant);

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, state.status());
    assertEquals(ANCHOR, state.dueAt());
    // The preference route still reads its watermark from ETGO_SubscriptionEventAt.
    assertEquals(EVENT_AT, state.lastEventAt());
  }

  @Test
  public void theProductiveSnapshotReadsTheRowTheWebhookWrote() {
    // The row alone makes the tenant productive: no plan lookup is stubbed (ETP-5047).
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT");

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> service.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, snapshot.getType());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        snapshot.getSubscriptionStatus());
    assertEquals(ANCHOR, snapshot.getRenewalDueAt());
    assertNoSubscriptionPreferenceRead(fixture);
  }

  @Test
  public void theProductiveSnapshotReadsThePreSplitAnchorThroughTheFallback() {
    Fixture fixture = new Fixture().withLegacyOpenRow("past_due", ANCHOR);

    assertEquals(ANCHOR, fixture.run(() -> service.resolve(CLIENT_ID)).getRenewalDueAt());
  }

  @Test
  public void theProductiveSnapshotOfATenantWithoutARowReadsThePreferences() {
    // No row: resolve asks the preference fallback directly (resolvePlanWithoutSubscription).
    when(tenantPlanService.resolvePlanWithoutSubscription(CLIENT_ID))
        .thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString());

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> service.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        snapshot.getSubscriptionStatus());
    assertEquals(ANCHOR, snapshot.getRenewalDueAt());
  }

  // ===================== a canceled (closed) row =====================

  @Test
  public void aClosedCanceledRowResolvesExpiredAndIsRefusedWithoutAnyPreferenceFallback() {
    // ETP-5047 — the canceled tenant keeps its productive marker; its plan now reads free (the
    // closed row answers canceled). Its subscription preferences still say CURRENT — the stale
    // pre-row projection — and must not resurrect it, nor may LEGACY_ENTITLEMENT.
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT)
        .withPreference(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE, "PRODUCTIVE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT");

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> service.resolve(CLIENT_ID));
    EnvironmentAccessPolicy.Decision decision =
        fixture.run(() -> service.evaluateAccess(CLIENT_ID, true, EVENT_AT));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, snapshot.getType());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED,
        snapshot.getSubscriptionStatus());
    assertNull(snapshot.getRenewalDueAt());
    assertEquals(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED, decision);
    assertNoSubscriptionPreferenceRead(fixture);
  }

  @Test
  public void aClosedRowWhoseStatusStillSaysActiveReadsExpired() {
    // A closed row is history, not entitlement: effectiveStatusOf answers canceled whatever
    // STATUS still says.
    Fixture fixture = new Fixture().withClosedRow("active", ENDED_AT)
        .withPreference(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE, "PRODUCTIVE");

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED,
        fixture.run(() -> service.resolve(CLIENT_ID)).getSubscriptionStatus());
  }

  // ===================== review fix B1: any row makes the tenant productive =====================

  private static final String LEGACY_ACTIVATION = "2026-01-01T00:00:00Z";

  /** A client id no other spec used: the missing-marker WARN is remembered per tenant and JVM. */
  private static String freshClientId() {
    return "B1-" + java.util.UUID.randomUUID();
  }

  /** A plan answer of "free" that the row must override without either lookup being asked. */
  private void givenEveryPlanLookupSaysFree() {
    when(tenantPlanService.resolvePlan(anyString())).thenReturn(TenantPlanService.PLAN_FREE);
    when(tenantPlanService.resolvePlanWithoutSubscription(anyString()))
        .thenReturn(TenantPlanService.PLAN_FREE);
  }

  private void assertNeverEnteredTheDemoPath(Fixture fixture) {
    verify(tenantPlanService, never()).resolvePlan(anyString());
    verify(tenantPlanService, never()).resolvePlanWithoutSubscription(anyString());
    assertFalse("the demo trial start must not even be read",
        fixture.queriedPreferenceAttributes.contains(
            TenantEnvironmentLifecycleService.DEMO_TRIAL_STARTED_ATTRIBUTE));
    assertFalse("ensureLegacyTransitionStart must never be reached",
        fixture.queriedPreferenceAttributes.contains(
            TenantEnvironmentLifecycleService.LEGACY_TRANSITION_STARTED_ATTRIBUTE));
    assertTrue("no preference may be written", fixture.savedPreferenceAttributes.isEmpty());
    verify(fixture.dal, never()).save(any());
  }

  private <T> T withLegacyActivation(java.util.function.Supplier<T> body) {
    // With an activation instant configured, a tenant falling into the legacy path WOULD be given
    // a transition start: the specs below prove that path is never reached.
    System.setProperty(TenantEnvironmentLifecycleService.LEGACY_TRANSITION_ACTIVATION_PROPERTY,
        LEGACY_ACTIVATION);
    try {
      return body.get();
    } finally {
      System.clearProperty(TenantEnvironmentLifecycleService.LEGACY_TRANSITION_ACTIVATION_PROPERTY);
    }
  }

  @Test
  public void aClosedCanceledRowWithoutTheMarkerIsRefusedAsSubscriptionRequired() {
    String clientId = freshClientId();
    givenEveryPlanLookupSaysFree();
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT);

    EnvironmentAccessPolicy.Decision decision = withLegacyActivation(
        () -> fixture.run(() -> service.evaluateAccess(clientId, true, EVENT_AT)));

    assertEquals(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED, decision);
    assertNeverEnteredTheDemoPath(fixture);
  }

  @Test
  public void anOpenCanceledRowAsR37WritesItWithoutTheMarkerIsRefusedAsSubscriptionRequired() {
    // R37 maps NONE / EXPIRED to an OPEN 'canceled' row, and the tenant may carry neither the
    // ETGO_EnvironmentType marker nor (retired by R37 itself) ETGO_TenantPlan.
    String clientId = freshClientId();
    givenEveryPlanLookupSaysFree();
    Fixture fixture = new Fixture().withOpenRow("canceled", null);

    EnvironmentAccessPolicy.Decision decision = withLegacyActivation(
        () -> fixture.run(() -> service.evaluateAccess(clientId, true, EVENT_AT)));

    assertEquals(EnvironmentAccessPolicy.Decision.SUBSCRIPTION_REQUIRED, decision);
    assertNeverEnteredTheDemoPath(fixture);
  }

  /**
   * ETP-5047 review W1 — the System pseudo-client {@code "0"} is never a tenant. Asked about it
   * (a wildcard MCP token used to reach the guard raw), the no-row path would treat it as a free
   * tenant: with a legacy activation instant configured, {@code ensureLegacyTransitionStart}
   * wrote a transition start ON SYSTEM, turning {@code "0"} into a demo whose trial later lapsed
   * and refused every wildcard caller. The service answers "no metadata" for it and writes nothing.
   */
  @Test
  public void theSystemClientIsNeverEvaluatedNorWritten() {
    givenEveryPlanLookupSaysFree();
    Fixture fixture = new Fixture();

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = withLegacyActivation(
        () -> fixture.run(() -> service.resolve("0")));
    EnvironmentAccessPolicy.Decision decision = withLegacyActivation(
        () -> fixture.run(() -> service.evaluateAccess("0", true, EVENT_AT)));
    boolean demo = fixture.run(() -> service.markDemoReady("0", EVENT_AT));
    boolean productive = fixture.run(() -> service.markProductive("0"));

    assertNull(snapshot);
    assertNull("no decision, so every entry point allows", decision);
    assertFalse(demo);
    assertFalse(productive);
    assertTrue("no lifecycle preference may be written for System: "
        + fixture.savedPreferenceAttributes, fixture.savedPreferenceAttributes.isEmpty());
    verify(fixture.dal, never()).save(any());
  }

  @Test
  public void theDemoPathIsReachedForATenantWithNoRowAndNoMarker() {
    // Control for the two specs above: without a row the same tenant does take the demo path,
    // so "never read DEMO_TRIAL_STARTED" there is not vacuous.
    String clientId = freshClientId();
    givenEveryPlanLookupSaysFree();
    Fixture fixture = new Fixture();

    fixture.run(() -> service.resolve(clientId));

    assertTrue(fixture.queriedPreferenceAttributes.contains(
        TenantEnvironmentLifecycleService.DEMO_TRIAL_STARTED_ATTRIBUTE));
  }

  @Test
  public void aTenantWithARowNeverReadsTheMarkerNorIsReportedForMissingIt() {
    // ETP-5047 — the row decides, so the ETGO_EnvironmentType marker is read only on the no-row
    // fallback; a tenant with a row but no marker is the normal pre-marker state, not a warning.
    String clientId = freshClientId();
    Fixture fixture = new Fixture().withClosedRow("canceled", ENDED_AT);
    TestLogCapture warnings =
        TestLogCapture.attachTo(TenantEnvironmentLifecycleService.class, Level.WARN);
    try {
      fixture.run(() -> service.resolve(clientId));
    } finally {
      warnings.detach();
    }

    assertFalse(fixture.queriedPreferenceAttributes.contains(
        TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE));
    for (String line : warnings.messagesAt(Level.WARN)) {
      assertFalse(line, line.contains(clientId));
    }
  }

  @Test
  public void anActiveRowWithoutTheMarkerIsAllowed() {
    String clientId = freshClientId();
    givenEveryPlanLookupSaysFree();
    Fixture fixture = new Fixture().withOpenRow("active", null);

    EnvironmentAccessPolicy.Decision decision = withLegacyActivation(
        () -> fixture.run(() -> service.evaluateAccess(clientId, true, EVENT_AT)));

    assertEquals(EnvironmentAccessPolicy.Decision.ALLOWED, decision);
    assertNeverEnteredTheDemoPath(fixture);
  }

  @Test
  public void aTenantWithNoRowButTheProductiveMarkerStillReadsThePreferenceSnapshot() {
    String clientId = freshClientId();
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE,
            TenantEnvironmentLifecycleService.TYPE_PRODUCTIVE)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString());

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> service.resolve(clientId));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, snapshot.getType());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        snapshot.getSubscriptionStatus());
    assertEquals(ANCHOR, snapshot.getRenewalDueAt());
  }

  // ===================== follow-up W1': a tenant with no row skips the second row lookup ========

  /** A lifecycle service over a spied SubscriptionService, to count the row lookups. */
  private static final class Counted {
    final TenantPlanService plans = mock(TenantPlanService.class);
    final SubscriptionService subscriptions = org.mockito.Mockito.spy(new SubscriptionService());
    final TenantEnvironmentLifecycleService lifecycle =
        new TenantEnvironmentLifecycleService(plans, subscriptions);
  }

  @Test
  public void noRowAndTheProductiveMarkerReadsThePreferenceSnapshotWithoutAPlanLookup() {
    Counted counted = new Counted();
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.ENVIRONMENT_TYPE_ATTRIBUTE,
            TenantEnvironmentLifecycleService.TYPE_PRODUCTIVE)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT");

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> counted.lifecycle.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, snapshot.getType());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT,
        snapshot.getSubscriptionStatus());
    // The marker short-circuits: the fallback is not asked at all.
    verify(counted.plans, never()).resolvePlanWithoutSubscription(anyString());
    verify(counted.plans, never()).resolvePlan(anyString());
    verify(counted.subscriptions, org.mockito.Mockito.times(1)).findLatest(CLIENT_ID);
  }

  @Test
  public void noRowNoMarkerButAProductiveFallbackReadsThePreferenceSnapshot() {
    Counted counted = new Counted();
    when(counted.plans.resolvePlanWithoutSubscription(CLIENT_ID))
        .thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString());

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> counted.lifecycle.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, snapshot.getType());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        snapshot.getSubscriptionStatus());
    assertEquals(ANCHOR, snapshot.getRenewalDueAt());
    verify(counted.plans, org.mockito.Mockito.times(1)).resolvePlanWithoutSubscription(CLIENT_ID);
    verify(counted.plans, never()).resolvePlan(anyString());
    verify(counted.subscriptions, org.mockito.Mockito.times(1)).findLatest(CLIENT_ID);
  }

  @Test
  public void noRowNoMarkerFreeAndNoTrialStartReachesTheLegacyTransition() {
    // The plan is known to be free once the fallback said so: the legacy transition is reached
    // without a second plan lookup, and (activation configured) records its start.
    Counted counted = new Counted();
    when(counted.plans.resolvePlanWithoutSubscription(CLIENT_ID))
        .thenReturn(TenantPlanService.PLAN_FREE);
    Fixture fixture = new Fixture();

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = withLegacyActivation(
        () -> fixture.run(() -> counted.lifecycle.resolve(CLIENT_ID)));

    assertTrue(fixture.queriedPreferenceAttributes.contains(
        TenantEnvironmentLifecycleService.LEGACY_TRANSITION_STARTED_ATTRIBUTE));
    assertEquals(List.of(TenantEnvironmentLifecycleService.LEGACY_TRANSITION_STARTED_ATTRIBUTE),
        fixture.savedPreferenceAttributes);
    assertEquals(List.of(LEGACY_ACTIVATION), fixture.savedPreferenceValues);
    assertEquals(EnvironmentAccessPolicy.EnvironmentType.DEMO, snapshot.getType());
    assertEquals(Instant.parse(LEGACY_ACTIVATION), snapshot.getTrialStartedAt());
    verify(counted.plans, org.mockito.Mockito.times(1)).resolvePlanWithoutSubscription(CLIENT_ID);
    verify(counted.plans, never()).resolvePlan(anyString());
    verify(counted.subscriptions, org.mockito.Mockito.times(1)).findLatest(CLIENT_ID);
  }

  @Test
  public void noRowAndADemoTrialStartIsADemoWithOneRowLookupAndNoPlanLookupBeyondTheFallback() {
    Counted counted = new Counted();
    when(counted.plans.resolvePlanWithoutSubscription(CLIENT_ID))
        .thenReturn(TenantPlanService.PLAN_FREE);
    Instant trialStart = EVENT_AT.minusSeconds(3600L);
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.DEMO_TRIAL_STARTED_ATTRIBUTE,
            trialStart.toString());

    TenantEnvironmentLifecycleService.EnvironmentSnapshot first =
        fixture.run(() -> counted.lifecycle.resolve(CLIENT_ID));
    TenantEnvironmentLifecycleService.EnvironmentSnapshot second =
        fixture.run(() -> counted.lifecycle.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.DEMO, first.getType());
    assertEquals(trialStart, second.getTrialStartedAt());
    assertFalse("a trial already started needs no legacy transition",
        fixture.queriedPreferenceAttributes.contains(
            TenantEnvironmentLifecycleService.LEGACY_TRANSITION_STARTED_ATTRIBUTE));
    // Exactly one row lookup per resolve, and never the row-repeating resolvePlan.
    verify(counted.subscriptions, org.mockito.Mockito.times(2)).findLatest(CLIENT_ID);
    verify(counted.plans, org.mockito.Mockito.times(2)).resolvePlanWithoutSubscription(CLIENT_ID);
    verify(counted.plans, never()).resolvePlan(anyString());
  }

  // ===================== ordering watermark on the row route =====================

  @Test
  public void anOlderEventIsStillRejectedAsStaleOnceTheTenantHasARow() throws Exception {
    Fixture fixture = new Fixture().withOpenRow("active", null).withLastEventAt(EVENT_AT);
    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(this::storedStateOfTenant);

    // A late payment_failed created before the invoice.paid already applied.
    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate(
        SubscriptionLifecycleApplier.INVOICE_PAYMENT_FAILED,
        paymentFailed(EVENT_AT.minusSeconds(60L)), state);

    assertTrue(outcome.isIgnored());
    assertEquals("stale event", outcome.reason());
  }

  @Test
  public void aStalePreferenceWatermarkNoLongerBlocksAnEventOnTheRowRoute() throws Exception {
    // Before ETP-5047 the watermark was a preference on both routes. Now the row's own
    // LAST_EVENT_AT decides; the preference of a tenant that has a row is dead data.
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withLastEventAt(EVENT_AT.minusSeconds(3600L))
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());
    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(this::storedStateOfTenant);

    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate(
        SubscriptionLifecycleApplier.INVOICE_PAYMENT_FAILED,
        paymentFailed(EVENT_AT.minusSeconds(60L)), state);

    assertTrue(outcome.isApplied());
  }

  @Test
  public void aNewerEventIsAppliedOnTheRowRoute() throws Exception {
    Fixture fixture = new Fixture().withOpenRow("active", null).withLastEventAt(EVENT_AT);
    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(this::storedStateOfTenant);

    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate(
        SubscriptionLifecycleApplier.INVOICE_PAYMENT_FAILED,
        paymentFailed(EVENT_AT.plusSeconds(60L)), state);

    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
  }

  @Test
  public void recordSubscriptionEventAtWritesThePreferenceWatermark() {
    // The preference route's watermark writer (ETP-5046-TRANSITIONAL-FALLBACK).
    Fixture fixture = new Fixture().asWebhook();

    fixture.run(() -> {
      service.recordSubscriptionEventAt(CLIENT_ID, EVENT_AT);
      return null;
    });

    assertEquals(List.of(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE),
        fixture.savedPreferenceAttributes);
  }

  // ===================== admin mode =====================

  @Test
  public void aNonAdminCallerStillReadsBothRoutes() {
    // The Fixture's DAL refuses every preference/subscription query outside admin mode, so any
    // of these reads leaking out of admin mode would come back empty (or throw) here.
    Fixture withRow = new Fixture().withOpenRow("past_due", ANCHOR);
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        withRow.run(this::storedStateOfTenant).status());

    Fixture withoutRow = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "EXPIRED");
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED,
        withoutRow.run(this::storedStateOfTenant).status());
  }

  @Test
  public void aNonAdminCallerStillGetsTheProductiveSnapshotOnBothRoutes() {
    // The row route needs no plan; the no-row route asks the preference fallback directly.
    when(tenantPlanService.resolvePlanWithoutSubscription(CLIENT_ID))
        .thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    Fixture withRow = new Fixture().withOpenRow("canceled", null);
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED,
        withRow.run(() -> service.resolve(CLIENT_ID)).getSubscriptionStatus());

    Fixture withoutRow = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT");
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT,
        withoutRow.run(() -> service.resolve(CLIENT_ID)).getSubscriptionStatus());
  }

  @Test
  public void theRowRouteWritesInAdminMode() {
    // The subscription row lives at client 0; saving it outside admin mode is refused by the DAL
    // (the Fixture throws), which applySubscriptionEvent would swallow into a false.
    Fixture fixture = new Fixture().withOpenRow("active", null);

    assertTrue(fixture.run(() -> storeForTenant(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)));

    verify(fixture.dal).save(fixture.row);
  }

  // ===================== fixture =====================

  private static void assertNoSubscriptionPreferenceRead(Fixture fixture) {
    for (String attribute : List.of(
        TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE,
        TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
        TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE)) {
      assertFalse("a tenant with a row must not read " + attribute,
          fixture.queriedPreferenceAttributes.contains(attribute));
    }
  }

  private static JSONObject paymentFailed(Instant createdAt) throws Exception {
    return new JSONObject().put("created", createdAt.getEpochSecond()).put("data",
        new JSONObject().put("object", new JSONObject().put("id", "in_1")
            .put("subscription", "sub_1").put("period_end", ANCHOR.getEpochSecond())));
  }

  /**
   * A DAL holding at most one open and one closed subscription row and some lifecycle
   * preferences, which refuses every read (and every subscription save) made outside admin mode.
   * Subscription queries are answered by their HQL: the open-row lookups ({@code END_DATE IS
   * NULL}), the lookup by Stripe subscription id (open rows only), and the latest-closed lookup.
   */
  private static final class Fixture {
    final OBDal dal = mock(OBDal.class);
    final Map<String, String> preferences = new HashMap<>();
    final List<String> savedPreferenceAttributes = new ArrayList<>();
    final List<String> savedPreferenceValues = new ArrayList<>();
    final List<String> queriedPreferenceAttributes = new ArrayList<>();
    final AtomicInteger adminDepth = new AtomicInteger();
    final Client client = mock(Client.class);
    final Client systemClient = mock(Client.class);
    Subscription row;
    Subscription closedRow;
    boolean systemCaller;
    boolean clientVanishes;
    boolean noClient;
    boolean failSubscriptionSave;

    Fixture() {
      when(client.getId()).thenReturn(CLIENT_ID);
      when(systemClient.getId()).thenReturn("0");
    }

    /**
     * The webhook path: it reaches the lifecycle service holding the system context
     * {@code SystemContext} installed, so its writes are not refused whatever the admin-mode state.
     */
    Fixture asWebhook() {
      systemCaller = true;
      return this;
    }

    /** An open row whose grace anchor is in {@code GRACE_ANCHOR} (the ETP-5047 shape). */
    Fixture withOpenRow(String status, Instant graceAnchor) {
      row = rowMock(status);
      when(row.getGraceAnchor()).thenReturn(graceAnchor == null ? null : Date.from(graceAnchor));
      return this;
    }

    /**
     * An open row in the pre-ETP-5047 shape: no {@code GRACE_ANCHOR}, no period start, the value
     * in {@code CURRENT_PERIOD_END}.
     */
    Fixture withLegacyOpenRow(String status, Instant periodEnd) {
      row = rowMock(status);
      when(row.getCurrentPeriodEnd()).thenReturn(Date.from(periodEnd));
      return this;
    }

    Fixture withClosedRow(String status, Instant endDate) {
      closedRow = rowMock(status);
      when(closedRow.getEndDate()).thenReturn(Date.from(endDate));
      return this;
    }

    Fixture withStripeSubscription(String stripeSubscriptionId) {
      Subscription target = row != null ? row : closedRow;
      when(target.getStripeSubscription()).thenReturn(stripeSubscriptionId);
      return this;
    }

    Fixture withLastEventAt(Instant lastEventAt) {
      when(row.getLastEventAt()).thenReturn(Date.from(lastEventAt));
      return this;
    }

    Fixture withPreference(String attribute, String value) {
      preferences.put(attribute, value);
      return this;
    }

    /** The first {@code Client} lookup finds the tenant; every later one finds nothing. */
    Fixture clientVanishesAfterFirstLookup() {
      clientVanishes = true;
      return this;
    }

    Fixture withNoClient() {
      noClient = true;
      return this;
    }

    Fixture failingSubscriptionSave() {
      failSubscriptionSave = true;
      return this;
    }

    private Subscription rowMock(String status) {
      Subscription subscription = mock(Subscription.class);
      when(subscription.getSubscriptionStatus()).thenReturn(status);
      when(subscription.getEnvironmentClient()).thenReturn(client);
      return subscription;
    }

    @SuppressWarnings("unchecked")
    <T> T run(java.util.function.Supplier<T> body) {
      OBQuery<Preference> preferenceQuery = mock(OBQuery.class);
      AtomicReference<String> attribute = new AtomicReference<>();
      doAnswer(invocation -> {
        if ("attribute".equals(invocation.getArgument(0))) {
          attribute.set(invocation.getArgument(1));
          queriedPreferenceAttributes.add(invocation.getArgument(1));
        }
        return preferenceQuery;
      }).when(preferenceQuery).setNamedParameter(anyString(), any());
      // Built up front: stubbing a mock from inside another mock's answer is not reliable.
      Map<String, Preference> stored = new HashMap<>();
      for (Map.Entry<String, String> entry : preferences.entrySet()) {
        Preference preference = mock(Preference.class);
        when(preference.getSearchKey()).thenReturn(entry.getValue());
        doAnswer(set -> {
          savedPreferenceAttributes.add(entry.getKey());
          savedPreferenceValues.add(set.getArgument(0));
          return null;
        }).when(preference).setSearchKey(anyString());
        stored.put(entry.getKey(), preference);
      }
      when(preferenceQuery.uniqueResult()).thenAnswer(invocation -> stored.get(attribute.get()));

      OBQuery<Subscription> openQuery = mock(OBQuery.class);
      when(openQuery.uniqueResult()).thenAnswer(invocation -> row);
      OBQuery<Subscription> closedQuery = mock(OBQuery.class);
      when(closedQuery.uniqueResult()).thenAnswer(invocation -> closedRow);
      OBQuery<Subscription> byStripeQuery = mock(OBQuery.class);
      AtomicReference<Object> stripeId = new AtomicReference<>();
      doAnswer(invocation -> {
        if ("stripeSubscriptionId".equals(invocation.getArgument(0))) {
          stripeId.set(invocation.getArgument(1));
        }
        return byStripeQuery;
      }).when(byStripeQuery).setNamedParameter(anyString(), any());
      when(byStripeQuery.uniqueResult()).thenAnswer(invocation -> row != null
          && stripeId.get() != null && stripeId.get().equals(row.getStripeSubscription())
          ? row : null);

      when(dal.createQuery(eq(Preference.class), anyString())).thenAnswer(invocation -> {
        requireAdmin("ADPreference");
        return preferenceQuery;
      });
      when(dal.createQuery(eq(Subscription.class), anyString())).thenAnswer(invocation -> {
        requireAdmin("ETGO_Subscription");
        String hql = invocation.getArgument(1);
        if (hql.contains("is not null")) {
          return closedQuery;
        }
        if (hql.contains("." + Subscription.PROPERTY_STRIPESUBSCRIPTION + " =")) {
          return byStripeQuery;
        }
        return openQuery;
      });
      // The System pseudo-client exists in every database: a lifecycle write aimed at it would
      // find it, so only the service's own guard can keep "0" from being written.
      when(dal.get(Client.class, "0")).thenReturn(systemClient);
      AtomicInteger clientLookups = new AtomicInteger();
      when(dal.get(Client.class, CLIENT_ID)).thenAnswer(invocation -> {
        if (noClient || (clientVanishes && clientLookups.getAndIncrement() > 0)) {
          return null;
        }
        return client;
      });
      doAnswer(invocation -> {
        Object saved = invocation.getArgument(0);
        if (saved instanceof Subscription) {
          requireAdmin("ETGO_Subscription");
          if (failSubscriptionSave) {
            throw new IllegalStateException("ETGO_SUBSCRIPTION write failed");
          }
        }
        return null;
      }).when(dal).save(any());
      OBProvider provider = mock(OBProvider.class);
      List<Preference> created = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        Preference preference = mock(Preference.class);
        doAnswer(set -> {
          savedPreferenceAttributes.add(set.getArgument(0));
          return null;
        }).when(preference).setAttribute(anyString());
        doAnswer(set -> {
          savedPreferenceValues.add(set.getArgument(0));
          return null;
        }).when(preference).setSearchKey(anyString());
        created.add(preference);
      }
      AtomicInteger nextCreated = new AtomicInteger();
      when(provider.get(Preference.class))
          .thenAnswer(invocation -> created.get(nextCreated.getAndIncrement()));

      try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<OBProvider> providerStatic = mockStatic(OBProvider.class);
          MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
        dalStatic.when(OBDal::getInstance).thenReturn(dal);
        providerStatic.when(OBProvider::getInstance).thenReturn(provider);
        context.when(OBContext::setAdminMode).thenAnswer(invocation -> adminDepth.incrementAndGet());
        context.when(() -> OBContext.setAdminMode(anyBoolean()))
            .thenAnswer(invocation -> adminDepth.incrementAndGet());
        context.when(OBContext::restorePreviousMode)
            .thenAnswer(invocation -> adminDepth.decrementAndGet());
        T result = body.get();
        assertEquals("admin mode must be balanced", 0, adminDepth.get());
        return result;
      }
    }

    private void requireAdmin(String entity) {
      if (!systemCaller && adminDepth.get() == 0) {
        throw new OBSecurityException("Entity " + entity + " is not readable by the user U1");
      }
    }
  }

  @Test
  public void theFixtureReallyRefusesNonAdminReads() {
    // Guard against a fixture that silently lets everything through, which would make every
    // admin-mode assertion above vacuous.
    Fixture fixture = new Fixture();
    boolean refused = fixture.run(() -> {
      try {
        OBDal.getInstance().createQuery(Preference.class, "as pref");
        return false;
      } catch (OBSecurityException expected) {
        return true;
      }
    });
    assertTrue(refused);
    assertFalse(fixture.savedPreferenceAttributes.contains("anything"));
  }
}
