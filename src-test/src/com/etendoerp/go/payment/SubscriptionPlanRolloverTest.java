/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5053 — an applied plan change is detected from the item price alone, the target plan is
 * resolved by that price and never guessed, and the quota year follows the ticket's rule.
 *
 * @covers com.etendoerp.go.payment.SubscriptionPlanRollover
 */
class SubscriptionPlanRolloverTest {

  private static final Instant PERIOD_START = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant PERIOD_END = Instant.parse("2026-12-01T00:00:00Z");
  private static final Date ROW_START = new Date(1_700_000_000_000L);
  private static final Date INHERITED_ANCHOR = new Date(1_690_000_000_000L);

  private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
  private final PlanCatalogService planCatalogService = mock(PlanCatalogService.class);
  private final SubscriptionPlanRollover rollover =
      new SubscriptionPlanRollover(subscriptionService, planCatalogService);

  private Plan core;
  private Plan pro;
  private Plan annual;
  private Subscription row;
  private Subscription successor;

  private static Plan plan(String id, String key, String interval, String priceId) {
    Plan plan = mock(Plan.class);
    when(plan.getId()).thenReturn(id);
    when(plan.getSearchKey()).thenReturn(key);
    when(plan.getBillingInterval()).thenReturn(interval);
    when(plan.getProviderPriceID()).thenReturn(priceId);
    when(plan.getDisplayPrice()).thenReturn(new BigDecimal("10"));
    when(plan.getCurrencyCode()).thenReturn("EUR");
    return plan;
  }

  private static SubscriptionEventOutcome current(String priceId) {
    return SubscriptionEventOutcome.apply(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)
        .withPeriod(PERIOD_START, PERIOD_END).withProviderPrice(priceId);
  }

  @BeforeEach
  void setUp() {
    core = plan("CORE", "core-monthly", "month", "price_core");
    pro = plan("PRO", "pro-monthly", "month", "price_pro");
    annual = plan("PRO_Y", "pro-yearly", "year", "price_pro_y");
    row = mock(Subscription.class);
    when(row.getId()).thenReturn("ROW");
    when(row.getPlan()).thenReturn(core);
    when(row.getProviderPriceID()).thenReturn("price_core");
    when(row.getStartDate()).thenReturn(ROW_START);
    successor = mock(Subscription.class);
    when(successor.getId()).thenReturn("SUCCESSOR");
    when(subscriptionService.rollToPlan(eq(row), any(), anyString(), any()))
        .thenReturn(successor);
  }

  @Test
  void anEventWithoutAPriceComparesNothing() {
    assertSame(row, rollover.rollIfPriceChanged(row, SubscriptionEventOutcome
        .apply(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)));
    assertSame(row, rollover.rollIfPriceChanged(row, null));
    verify(planCatalogService, never()).findByProviderPrice(any());
    verify(subscriptionService, never()).rollToPlan(any(), any(), any(), any());
  }

  @Test
  void theSamePriceIsNoChange() {
    assertSame(row, rollover.rollIfPriceChanged(row, current("price_core")));
    verify(planCatalogService, never()).findByProviderPrice(any());
  }

  @Test
  void aRowWithNoStoredPriceComparesNothing() {
    when(row.getProviderPriceID()).thenReturn(null);

    assertSame(row, rollover.rollIfPriceChanged(row, current("price_pro")));
    verify(subscriptionService, never()).rollToPlan(any(), any(), any(), any());
  }

  @Test
  void aClosedRowIsNeverRolledOver() {
    when(row.getEndDate()).thenReturn(new Date());

    assertSame(row, rollover.rollIfPriceChanged(row, current("price_pro")));
    verify(subscriptionService, never()).rollToPlan(any(), any(), any(), any());
  }

  @Test
  void aPriceNoPlanCarriesIsParkedNotGuessed() {
    when(planCatalogService.findByProviderPrice("price_mystery")).thenReturn(Optional.empty());

    assertSame(row, rollover.rollIfPriceChanged(row, current("price_mystery")));

    verify(subscriptionService, never()).rollToPlan(any(), any(), any(), any());
    verify(row, never()).setProviderPriceID(any());
    verify(row, never()).setPlan(any());
  }

  @Test
  void aNewPriceOfTheSamePlanUpdatesTheSnapshotAndRollsNothing() {
    Plan coreRepriced = plan("CORE", "core-monthly", "month", "price_core_v2");
    when(coreRepriced.getDisplayPrice()).thenReturn(new BigDecimal("12"));
    when(planCatalogService.findByProviderPrice("price_core_v2"))
        .thenReturn(Optional.of(coreRepriced));

    assertSame(row, rollover.rollIfPriceChanged(row, current("price_core_v2")));

    verify(row).setProviderPriceID("price_core_v2");
    verify(row).setSnapshotAmount(new BigDecimal("12"));
    verify(row).setSnapshotCurrency("EUR");
    verify(subscriptionService, never()).rollToPlan(any(), any(), any(), any());
  }

  @Test
  void anUpgradeWithinTheIntervalRollsOverAndKeepsTheQuotaYearAnchor() {
    when(row.getQuotaYearStart()).thenReturn(INHERITED_ANCHOR);
    when(planCatalogService.findByProviderPrice("price_pro")).thenReturn(Optional.of(pro));

    Subscription result = rollover.rollIfPriceChanged(row, current("price_pro"));

    assertSame(successor, result);
    // The predecessor's anchor, so usage already counted this year carries over (never reset).
    verify(subscriptionService).rollToPlan(row, pro, "price_pro", INHERITED_ANCHOR);
  }

  @Test
  void aRowWithoutAnAnchorHandsItsStartDateDown() {
    when(planCatalogService.findByProviderPrice("price_pro")).thenReturn(Optional.of(pro));

    rollover.rollIfPriceChanged(row, current("price_pro"));

    verify(subscriptionService).rollToPlan(row, pro, "price_pro", ROW_START);
  }

  @Test
  void aBillingIntervalChangeStartsTheQuotaYearOnTheNewProviderPeriod() {
    when(row.getQuotaYearStart()).thenReturn(INHERITED_ANCHOR);
    when(planCatalogService.findByProviderPrice("price_pro_y")).thenReturn(Optional.of(annual));

    rollover.rollIfPriceChanged(row, current("price_pro_y"));

    // Monthly → annual: Stripe restarted the cycle on the change date, and the quota year follows.
    verify(subscriptionService).rollToPlan(row, annual, "price_pro_y", Date.from(PERIOD_START));
  }

  @Test
  void anIntervalChangeWithoutAReportedPeriodAnchorsOnNow() {
    when(planCatalogService.findByProviderPrice("price_pro_y")).thenReturn(Optional.of(annual));
    Date before = new Date();

    Date anchor = SubscriptionPlanRollover.quotaYearAnchorFor(row, core, annual,
        SubscriptionEventOutcome.apply(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)
            .withProviderPrice("price_pro_y"));

    assertEquals(true, !anchor.before(before) && !anchor.after(new Date()), "now: " + anchor);
  }

  @Test
  void aRowWithoutAPlanInheritsItsAnchorWhateverTheTargetInterval() {
    when(row.getPlan()).thenReturn(null);
    when(row.getQuotaYearStart()).thenReturn(INHERITED_ANCHOR);
    when(planCatalogService.findByProviderPrice("price_pro_y")).thenReturn(Optional.of(annual));

    rollover.rollIfPriceChanged(row, current("price_pro_y"));

    verify(subscriptionService).rollToPlan(row, annual, "price_pro_y", INHERITED_ANCHOR);
  }
}
