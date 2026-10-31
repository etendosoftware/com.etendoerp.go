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

package com.etendoerp.go.usage.quota;

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.local;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.quota;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Snapshotting the catalog: a quota row and its billing resource become a detached
 * {@link QuotaDefinition}, and a subscription row with its quotas a {@link TenantQuotas}. The
 * descriptor is copied whole, so a resource added to the catalog later needs no code to be read.
 * With a subscription start (the anchor) a tenant's quotas count over the subscription year,
 * whatever the billing interval, while a billing period must still be in effect.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaDefinition
 * @covers com.etendoerp.go.usage.quota.ConsumptionSource
 * @covers com.etendoerp.go.usage.quota.TenantQuotas
 */
class QuotaDefinitionTest {

  private static PlanQuota row(BillingResource resource, String mode, String source) {
    PlanQuota quota = mock(PlanQuota.class);
    when(quota.getBillingResource()).thenReturn(resource);
    when(quota.getIncludedQty()).thenReturn(25L);
    when(quota.getEnforcementMode()).thenReturn(mode);
    when(quota.getWarningThreshold()).thenReturn(90L);
    when(quota.getConsumptionSource()).thenReturn(source);
    return quota;
  }

  private static BillingResource signedContracts() {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getId()).thenReturn("RES-SIGNED");
    when(resource.getSearchKey()).thenReturn("SIGNED_CONTRACTS");
    when(resource.getName()).thenReturn("Signed contracts");
    when(resource.getUnitLabel()).thenReturn("contracts");
    when(resource.getCountingMode()).thenReturn("D");
    when(resource.getCountedEntity()).thenReturn("SomeFutureEntity");
    when(resource.getDateProperty()).thenReturn("signedAt");
    when(resource.getHQLRestriction()).thenReturn("e.signed = true");
    when(resource.getStrategyQualifier()).thenReturn(null);
    when(resource.getAggregation()).thenReturn("max");
    return resource;
  }

  @Test
  void aCatalogRowIsCopiedWhole() {
    QuotaDefinition definition = QuotaDefinition.of(row(signedContracts(), "Block", "live"));

    assertEquals("RES-SIGNED", definition.getResourceId());
    assertEquals("SIGNED_CONTRACTS", definition.getResourceKey());
    assertEquals("Signed contracts", definition.getResourceName());
    assertEquals("contracts", definition.getUnitLabel());
    assertEquals("D", definition.getCountingMode());
    assertEquals("SomeFutureEntity", definition.getCountedEntity());
    assertEquals("signedAt", definition.getDateProperty());
    assertEquals("e.signed = true", definition.getHqlRestriction());
    assertNull(definition.getStrategyQualifier());
    assertEquals(UsageAggregation.MAX, definition.getAggregation());
    assertEquals(Long.valueOf(25L), definition.getIncludedQty());
    assertEquals("block", definition.getEnforcementMode());
    assertEquals(Long.valueOf(90L), definition.getWarningThreshold());
    assertEquals(ConsumptionSource.LIVE, definition.getSource());
  }

  @Test
  void aRowWithoutItsResourceIsTolerated() {
    QuotaDefinition definition = QuotaDefinition.of(row(null, null, null));

    assertNull(definition.getResourceId());
    assertNull(definition.getResourceKey());
    assertEquals(UsageAggregation.SUM, definition.getAggregation());
    assertEquals("off", definition.getEnforcementMode());
    assertEquals(ConsumptionSource.AGGREGATE, definition.getSource());
  }

  @Test
  void theBuilderDefaultsToSumAggregateAndOff() {
    QuotaDefinition definition = QuotaDefinition.builder().build();

    assertEquals(UsageAggregation.SUM, definition.getAggregation());
    assertEquals(ConsumptionSource.AGGREGATE, definition.getSource());
    assertEquals("off", definition.getEnforcementMode());
    assertNull(definition.getIncludedQty());
  }

  @ParameterizedTest
  @ValueSource(strings = { "live", "LIVE", " Live " })
  void liveIsReadCaseInsensitively(String raw) {
    assertEquals(ConsumptionSource.LIVE, ConsumptionSource.fromColumn(raw));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "aggregate", "  ", "realtime" })
  void everythingElseReadsAsTheStoredAggregate(String raw) {
    assertEquals(ConsumptionSource.AGGREGATE, ConsumptionSource.fromColumn(raw));
  }

  @Test
  void theSourceValuesAreTheStoredSpelling() {
    assertEquals("aggregate", ConsumptionSource.AGGREGATE.value());
    assertEquals("live", ConsumptionSource.LIVE.value());
  }

  @Test
  void noSubscriptionRowIsATenantWithoutQuotas() {
    TenantQuotas tenant = TenantQuotas.of(CLIENT, null, List.of(row(signedContracts(), "block",
        null)));

    assertTrue(tenant.getQuotas().isEmpty());
    assertNull(tenant.getPlanInterval());
    assertNull(tenant.currentPeriod(NOW));
    assertEquals(CLIENT, tenant.getClientId());
  }

  @Test
  void aSubscriptionRowCarriesItsPlanIntervalPeriodAndQuotas() {
    Plan plan = mock(Plan.class);
    when(plan.getBillingInterval()).thenReturn("year");
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);

    TenantQuotas tenant = TenantQuotas.of(CLIENT, subscription,
        List.of(row(signedContracts(), "block", null)));

    assertEquals("year", tenant.getPlanInterval());
    assertFalse(tenant.isClosed());
    assertEquals(1, tenant.getQuotas().size());
    assertEquals("SIGNED_CONTRACTS", tenant.getQuotas().get(0).getResourceKey());
    assertEquals(PERIOD_START, tenant.currentPeriod(NOW).getStart());
    assertThrows(UnsupportedOperationException.class, () -> tenant.getQuotas().clear());
  }

  @Test
  void theQuotaYearIsAnchoredOnTheQuotaYearStartWhenTheRowCarriesOne() {
    // ETP-5053 — a successor row opened by a plan change inherits its predecessor's anchor, so
    // the year (and the usage counted in it) does not restart with the row.
    Subscription subscription = mock(Subscription.class);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);
    when(subscription.getStartDate()).thenReturn(NOW);
    java.util.Date inherited = java.util.Date.from(NOW.toInstant().minusSeconds(200L * 86_400L));
    when(subscription.getQuotaYearStart()).thenReturn(inherited);

    assertEquals(inherited, TenantQuotas.quotaYearAnchorOf(subscription));
    TenantQuotas tenant = TenantQuotas.of(CLIENT, subscription, null);
    assertEquals(TenantQuotas.QUOTA_WINDOW, tenant.getPlanInterval());
    assertEquals(QuotaPeriod.yearContaining(inherited, NOW), tenant.currentPeriod(NOW));
  }

  @Test
  void aRowWithoutAQuotaYearStartIsAnchoredOnItsStartDate() {
    Subscription subscription = mock(Subscription.class);
    when(subscription.getStartDate()).thenReturn(PERIOD_START);

    assertEquals(PERIOD_START, TenantQuotas.quotaYearAnchorOf(subscription));
  }

  @Test
  void aQuotaYearStartAfterTheStartDateIsClampedToTheStartDate() {
    Subscription subscription = mock(Subscription.class);
    when(subscription.getStartDate()).thenReturn(PERIOD_START);
    when(subscription.getQuotaYearStart()).thenReturn(java.util.Date.from(
        PERIOD_START.toInstant().plusSeconds(60L)));

    assertEquals(PERIOD_START, TenantQuotas.quotaYearAnchorOf(subscription));
  }

  @Test
  void anEndDateMakesTheRowClosedWithNoCurrentPeriod() {
    Subscription subscription = mock(Subscription.class);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);
    when(subscription.getEndDate()).thenReturn(NOW);

    TenantQuotas tenant = TenantQuotas.of(CLIENT, subscription, null);

    assertTrue(tenant.isClosed());
    assertNull(tenant.currentPeriod(NOW));
    assertNull(tenant.getPlanInterval(), "a row without a plan has no interval");
    assertTrue(tenant.getQuotas().isEmpty());
  }

  @Test
  void withQuotasKeepsTheTenantIntervalAndPeriodAndSwapsOnlyTheRows() {
    TenantQuotas period = new TenantQuotas(CLIENT, "year", PERIOD_START, PERIOD_END, false,
        List.of(quota("OLD", "block", 10L, 80L).build()));

    TenantQuotas swapped = period.withQuotas(List.of(quota("NEW", "warn", 20L, 90L).build()));

    assertEquals(CLIENT, swapped.getClientId());
    assertEquals("year", swapped.getPlanInterval());
    assertFalse(swapped.isClosed());
    assertEquals(PERIOD_START, swapped.currentPeriod(NOW).getStart());
    assertEquals(PERIOD_END, swapped.currentPeriod(NOW).getEnd());
    assertEquals(1, swapped.getQuotas().size());
    assertEquals("NEW", swapped.getQuotas().get(0).getResourceKey());
    assertEquals("OLD", period.getQuotas().get(0).getResourceKey(), "the original is unchanged");
    assertThrows(UnsupportedOperationException.class, () -> swapped.getQuotas().clear());
  }

  @Test
  void withQuotasKeepsAClosedRowClosedAndReadsNullAsNoRows() {
    TenantQuotas closed = new TenantQuotas(CLIENT, "month", PERIOD_START, PERIOD_END, true, null);

    TenantQuotas swapped = closed.withQuotas(null);
    TenantQuotas withRows = closed.withQuotas(List.of(quota("DOCS", "block", 10L, 80L).build()));

    assertTrue(swapped.getQuotas().isEmpty());
    assertTrue(withRows.isClosed());
    assertNull(withRows.currentPeriod(NOW), "a closed row still has no current period");
    assertEquals("month", withRows.getPlanInterval());
    assertEquals(CLIENT, withRows.getClientId());
  }

  // ─── the yearly quota window (ETP-5051) ─────────────────────────────────────

  /** A subscription started on 3 March 2026, billed monthly: NOW is in its first year. */
  private static final Date ANCHOR = local(2026, 3, 3, 8, 0);

  private static TenantQuotas anchored(Date periodStart, Date periodEnd, boolean closed) {
    return new TenantQuotas(CLIENT, "month", periodStart, periodEnd, closed, ANCHOR,
        List.of(quota("DOCS", "block", 10L, 80L).build()));
  }

  @Test
  void anAnchoredTenantReportsTheYearlyWindowWhateverItsBillingInterval() {
    assertEquals(TenantQuotas.QUOTA_WINDOW, anchored(PERIOD_START, PERIOD_END, false)
        .getPlanInterval());
    assertEquals("year", TenantQuotas.QUOTA_WINDOW);
    assertEquals("month", new TenantQuotas(CLIENT, "month", PERIOD_START, PERIOD_END, false,
        null, null).getPlanInterval(), "without an anchor the billing interval is reported");
  }

  @Test
  void anAnchoredTenantCountsOverTheSubscriptionYearNotTheBillingPeriod() {
    QuotaPeriod period = anchored(PERIOD_START, PERIOD_END, false).currentPeriod(NOW);

    assertEquals(ANCHOR, period.getStart());
    assertEquals(local(2027, 3, 3, 8, 0), period.getEnd());
  }

  @Test
  void withoutAnAnchorTheBillingPeriodIsTheWindow() {
    QuotaPeriod period = new TenantQuotas(CLIENT, "month", PERIOD_START, PERIOD_END, false, null,
        null).currentPeriod(NOW);

    assertEquals(PERIOD_START, period.getStart());
    assertEquals(PERIOD_END, period.getEnd());
  }

  @Test
  void anAnchoredTenantWithNoBillingPeriodInEffectHasNoWindow() {
    assertNull(anchored(null, null, false).currentPeriod(NOW), "periodless row");
    assertNull(anchored(PERIOD_START, PERIOD_END, true).currentPeriod(NOW), "closed row");
    assertNull(anchored(PERIOD_START, PERIOD_END, false).currentPeriod(PERIOD_END),
        "the billing period has ended (a renewal not yet recorded)");
  }

  @Test
  void anAnchorAfterNowFallsBackToTheBillingPeriod() {
    TenantQuotas future = new TenantQuotas(CLIENT, "month", PERIOD_START, PERIOD_END, false,
        local(2027, 1, 1, 0, 0), null);

    assertEquals(PERIOD_START, future.currentPeriod(NOW).getStart());
    assertEquals("year", future.getPlanInterval());
  }

  @Test
  void withQuotasKeepsTheAnchor() {
    TenantQuotas swapped = anchored(PERIOD_START, PERIOD_END, false)
        .withQuotas(List.of(quota("NEW", "warn", 20L, 90L).build()));

    assertEquals("year", swapped.getPlanInterval());
    assertEquals(ANCHOR, swapped.currentPeriod(NOW).getStart());
    assertEquals("NEW", swapped.getQuotas().get(0).getResourceKey());
  }

  @Test
  void theAnchorIsADefensiveCopy() {
    Date anchor = new Date(ANCHOR.getTime());
    TenantQuotas tenant = new TenantQuotas(CLIENT, "month", PERIOD_START, PERIOD_END, false,
        anchor, null);

    anchor.setTime(0L);

    assertEquals(ANCHOR, tenant.currentPeriod(NOW).getStart());
  }

  @Test
  void aSubscriptionRowGivesItsStartDateAsTheAnchor() {
    Plan plan = mock(Plan.class);
    when(plan.getBillingInterval()).thenReturn("month");
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscription.getStartDate()).thenReturn(ANCHOR);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);

    TenantQuotas tenant = TenantQuotas.of(CLIENT, subscription, null);

    assertEquals("year", tenant.getPlanInterval());
    assertEquals(ANCHOR, tenant.currentPeriod(NOW).getStart());
    assertEquals(local(2027, 3, 3, 8, 0), tenant.currentPeriod(NOW).getEnd());
  }
}
