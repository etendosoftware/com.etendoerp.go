/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Unit specs for {@link EnvironmentPlanCache}.
 *
 * <p>The cache exists so the environment list resolves every tenant's plan from one query instead
 * of one per comparison, and it is passed as a parameter rather than held per thread. Both
 * properties are asserted here: the value semantics (what a tenant with no subscription looks
 * like, that the coarse plan cannot disagree with the status) and immutability, which is what
 * makes handing the same instance to several frames safe.
 */
class EnvironmentPlanCacheTest {

  private static final String PAID_CLIENT = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String FREE_CLIENT = "9F5511B92BD0465FA678F75278FA9C3A";
  private static final String LEGACY_CLIENT = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String PLAN_KEY = "productive-monthly";

  private static Subscription subscriptionOn(String planKey, String status) {
    Plan plan = mock(Plan.class);
    when(plan.getSearchKey()).thenReturn(planKey);
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscription.getSubscriptionStatus()).thenReturn(status);
    return subscription;
  }

  private static EnvironmentPlanCache cacheWith(String clientId, Subscription subscription) {
    Map<String, Subscription> rows = new LinkedHashMap<>();
    rows.put(clientId, subscription);
    return EnvironmentPlanCache.of(rows);
  }

  @Test
  void reportsThePlanKeyAndStatusOfASubscribedTenant() {
    EnvironmentPlanCache cache =
        cacheWith(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_ACTIVE));

    assertTrue(cache.isProductive(PAID_CLIENT));
    assertEquals(PLAN_KEY, cache.planKey(PAID_CLIENT));
    assertEquals(SubscriptionService.STATUS_ACTIVE, cache.status(PAID_CLIENT));
  }

  @Test
  void keepsAPastDueTenantProductiveWhileStripeIsStillRetrying() {
    // An invoice has failed but the subscription is live and the provider is still retrying.
    // Cutting access off mid-dunning punishes a customer whose card merely expired.
    EnvironmentPlanCache cache =
        cacheWith(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_PAST_DUE));

    assertTrue(cache.isProductive(PAID_CLIENT));
    assertEquals(SubscriptionService.STATUS_PAST_DUE, cache.status(PAID_CLIENT));
  }

  @Test
  void reportsACanceledSubscriptionAsFreeWhileStillNamingItsPlan() {
    EnvironmentPlanCache cache =
        cacheWith(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_CANCELED));

    assertFalse(cache.isProductive(PAID_CLIENT));
    assertEquals(TenantPlanService.PLAN_FREE, cache.viewFor(PAID_CLIENT).legacyPlan());
    // The row still exists and still names a plan; only the entitlement is gone.
    assertEquals(PLAN_KEY, cache.planKey(PAID_CLIENT));
    assertEquals(SubscriptionService.STATUS_CANCELED, cache.status(PAID_CLIENT));
  }

  @Test
  void answersFreeWithNoPlanKeyForATenantItKnowsNothingAbout() {
    EnvironmentPlanCache cache =
        cacheWith(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_ACTIVE));

    assertFalse(cache.isProductive(FREE_CLIENT));
    assertEquals(TenantPlanService.PLAN_FREE, cache.viewFor(FREE_CLIENT).legacyPlan());
    // A plan KEY names a plan catalog row, and a free tenant has none. Reporting "free" here would
    // invent an entry nothing could ever look up.
    assertNull(cache.planKey(FREE_CLIENT));
    assertNull(cache.status(FREE_CLIENT));
  }

  @Test
  void neverReturnsNullForAnUnknownOrBlankTenant() {
    EnvironmentPlanCache cache = EnvironmentPlanCache.empty();

    assertNotNull(cache.viewFor(null));
    assertNotNull(cache.viewFor("   "));
    assertEquals(TenantPlanService.PLAN_FREE, cache.viewFor(null).legacyPlan());
  }

  @Test
  void toleratesAnEmptyOrNullQueryResult() {
    assertFalse(EnvironmentPlanCache.of(null).isProductive(PAID_CLIENT));
    assertFalse(EnvironmentPlanCache.of(Map.of()).isProductive(PAID_CLIENT));
  }

  @Test
  void survivesAMutationOfTheMapItWasBuiltFrom() {
    // The cache is handed down a call chain; a later frame must not be able to change what an
    // earlier one already read.
    Map<String, Subscription> rows = new LinkedHashMap<>();
    rows.put(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_ACTIVE));
    EnvironmentPlanCache cache = EnvironmentPlanCache.of(rows);

    rows.clear();

    assertTrue(cache.isProductive(PAID_CLIENT));
  }

  // ---------------------------------------------------------------------------------------------
  // TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — delete this whole section in Phase F together
  // with TenantPlanPreferenceFallback, markProductive and PREFERENCE_ATTRIBUTE.
  //
  // These specs exist because the bulk path and TenantPlanService#resolvePlan must agree about the
  // same tenant. If only one of them fell back, the environment list and the single-tenant
  // resolution would disagree, which is worse than the bug being worked around.
  // ---------------------------------------------------------------------------------------------

  @Test
  void answersProductiveWithNoPlanFactsForATenantTheBackfillHasNotReached() {
    TenantPlanPreferenceFallback fallback = mock(TenantPlanPreferenceFallback.class);
    when(fallback.productiveAmong(Set.of(LEGACY_CLIENT, FREE_CLIENT)))
        .thenReturn(Set.of(LEGACY_CLIENT));
    Map<String, Subscription> open = new LinkedHashMap<>();
    open.put(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_ACTIVE));

    EnvironmentPlanCache cache = EnvironmentPlanCache.of(
        List.of(PAID_CLIENT, LEGACY_CLIENT, FREE_CLIENT), open, fallback);

    // The subscribed tenant is unaffected and keeps its finer-grained facts.
    assertTrue(cache.isProductive(PAID_CLIENT));
    assertEquals(PLAN_KEY, cache.planKey(PAID_CLIENT));
    assertEquals(SubscriptionService.STATUS_ACTIVE, cache.status(PAID_CLIENT));
    // The tenant the backfill has not reached reads back productive — the whole point — but with
    // planKey and status null. There is genuinely no plan catalog row and no subscription; inventing a
    // key such as "legacy-productive" would claim a row that does not exist and would make the gap
    // invisible to anyone reading the payload.
    assertTrue(cache.isProductive(LEGACY_CLIENT));
    assertEquals(TenantPlanService.PLAN_PRODUCTIVE, cache.viewFor(LEGACY_CLIENT).legacyPlan());
    assertNull(cache.planKey(LEGACY_CLIENT));
    assertNull(cache.status(LEGACY_CLIENT));
    // A genuinely free tenant is still free.
    assertFalse(cache.isProductive(FREE_CLIENT));
    assertNull(cache.planKey(FREE_CLIENT));
    assertNull(cache.status(FREE_CLIENT));
  }

  @Test
  void asksTheFallbackExactlyOnceForOnlyTheTenantsWithNoSubscription() {
    // Looping resolvePlan here would reintroduce the N+1 this cache exists to remove. One extra
    // query, carrying only the missing ids.
    TenantPlanPreferenceFallback fallback = mock(TenantPlanPreferenceFallback.class);
    when(fallback.productiveAmong(Set.of(LEGACY_CLIENT, FREE_CLIENT))).thenReturn(Set.of());
    Map<String, Subscription> open = new LinkedHashMap<>();
    open.put(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_ACTIVE));

    EnvironmentPlanCache.of(List.of(PAID_CLIENT, LEGACY_CLIENT, FREE_CLIENT), open, fallback);

    verify(fallback).productiveAmong(new LinkedHashSet<>(List.of(LEGACY_CLIENT, FREE_CLIENT)));
    verifyNoMoreInteractions(fallback);
  }

  @Test
  void asksTheFallbackNothingWhenEveryTenantAlreadyHasASubscription() {
    // No missing ids means no query at all — the state the whole system returns to once the R37
    // backfill has run, and the state in which this fallback becomes safe to delete.
    TenantPlanPreferenceFallback fallback = mock(TenantPlanPreferenceFallback.class);
    Map<String, Subscription> open = new LinkedHashMap<>();
    open.put(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_ACTIVE));
    open.put(FREE_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_CANCELED));

    EnvironmentPlanCache cache =
        EnvironmentPlanCache.of(List.of(PAID_CLIENT, FREE_CLIENT), open, fallback);

    assertTrue(cache.isProductive(PAID_CLIENT));
    assertFalse(cache.isProductive(FREE_CLIENT));
    verifyNoInteractions(fallback);
  }

  @Test
  void asksTheFallbackNothingWhenThereAreNoTenantsAtAll() {
    TenantPlanPreferenceFallback fallback = mock(TenantPlanPreferenceFallback.class);

    assertFalse(EnvironmentPlanCache.of(List.of(), Map.of(), fallback).isProductive(PAID_CLIENT));
    assertFalse(EnvironmentPlanCache.of(null, null, fallback).isProductive(PAID_CLIENT));

    verifyNoInteractions(fallback);
  }

  @Test
  void neverLetsTheFallbackOverrideATenantThatHasAnOpenSubscription() {
    // An open row is the new source of truth. A canceled subscription must stay canceled even
    // though the tenant still carries the retired preference from before it churned.
    TenantPlanPreferenceFallback fallback = mock(TenantPlanPreferenceFallback.class);
    when(fallback.productiveAmong(Set.of(PAID_CLIENT))).thenReturn(Set.of(PAID_CLIENT));
    Map<String, Subscription> open = new LinkedHashMap<>();
    open.put(PAID_CLIENT, subscriptionOn(PLAN_KEY, SubscriptionService.STATUS_CANCELED));

    EnvironmentPlanCache cache = EnvironmentPlanCache.of(List.of(PAID_CLIENT), open, fallback);

    assertFalse(cache.isProductive(PAID_CLIENT));
    verifyNoInteractions(fallback);
  }
}
