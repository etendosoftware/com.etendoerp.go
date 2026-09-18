/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * The commercial state of a set of tenants, read once and then answered from memory.
 *
 * <p>It exists because the environment list needs each tenant's plan twice — once to sort paid
 * tenants first, once to render each row — and the obvious implementation asks the database inside
 * a {@link java.util.Comparator}, which is O(n log n) queries for a single page.
 *
 * <p><b>Construct one per request and pass it as a parameter. Never a static field, never a
 * {@link ThreadLocal}.</b> Tomcat pools request threads, so a per-thread cache would serve one
 * account's plan data to whatever request lands on that thread next — a tenancy leak that is
 * invisible under single-user testing and appears only under concurrency. This module has already
 * been bitten by exactly this class of thread-reuse bug: see the {@code build.gradle} note on
 * {@code OBContext.adminModeStack}, a static {@code ThreadLocal} whose unbalanced state was
 * charged to whichever test class ran next on a reused Gradle worker thread. The failure mode
 * here is the same mechanism with customer data instead of test state.
 *
 * <p>Immutable: the map is copied and wrapped on construction, so passing an instance down a call
 * chain cannot change what an earlier frame already read.
 */
public final class EnvironmentPlanCache {

  private static final EnvironmentPlanCache EMPTY =
      new EnvironmentPlanCache(Collections.emptyMap());

  /** What a tenant with no open subscription looks like to every caller. */
  private static final PlanView NO_SUBSCRIPTION =
      new PlanView(null, null, TenantPlanService.PLAN_FREE);

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — "productive but unsubscribed": a tenant the
   * R37 backfill has not reached yet, which still carries the retired {@code ETGO_TenantPlan}
   * preference.
   *
   * <p>{@code planKey} and {@code status} stay null on purpose. There is genuinely no catalog row
   * and no subscription behind this tenant; inventing a key such as {@code "legacy-productive"}
   * would claim a row that does not exist and would make the gap invisible to anyone reading the
   * payload. The coarse {@code plan} is what live clients branch on, and that is what the fallback
   * restores. Delete with {@link TenantPlanPreferenceFallback} in Phase F.
   */
  private static final PlanView PRODUCTIVE_WITHOUT_SUBSCRIPTION =
      new PlanView(null, null, TenantPlanService.PLAN_PRODUCTIVE);

  private final Map<String, PlanView> byClientId;

  private EnvironmentPlanCache(Map<String, PlanView> byClientId) {
    this.byClientId = Collections.unmodifiableMap(byClientId);
  }

  /**
   * Builds a cache from the open subscriptions of a set of tenants.
   *
   * @param openSubscriptions open subscription rows keyed by tenant id, as returned by
   *     {@link SubscriptionService#findOpenForClients(java.util.Collection)}; may be null or empty
   * @return an immutable view over those rows
   */
  public static EnvironmentPlanCache of(Map<String, Subscription> openSubscriptions) {
    Map<String, PlanView> views = viewsOf(openSubscriptions);
    return views.isEmpty() ? EMPTY : new EnvironmentPlanCache(views);
  }

  private static Map<String, PlanView> viewsOf(Map<String, Subscription> openSubscriptions) {
    Map<String, PlanView> views = new LinkedHashMap<>();
    if (openSubscriptions == null || openSubscriptions.isEmpty()) {
      return views;
    }
    for (Map.Entry<String, Subscription> entry : openSubscriptions.entrySet()) {
      if (StringUtils.isNotBlank(entry.getKey()) && entry.getValue() != null) {
        views.put(entry.getKey(), viewOf(entry.getValue()));
      }
    }
    return views;
  }

  /**
   * Builds a cache for a known set of tenants, applying the transitional preference fallback to
   * the ones that have no open subscription.
   *
   * <p><b>TRANSITIONAL — ETP-5046-TRANSITIONAL-FALLBACK.</b> This overload exists so the bulk path
   * and {@link TenantPlanService#resolvePlan} agree about the same tenant. If only one of them
   * fell back, the environment list and the single-tenant resolution would disagree, which is
   * worse than the bug being worked around. In Phase F this overload collapses back into
   * {@link #of(Map)} and {@code allClientIds} is dropped.
   *
   * <p><b>One extra query, and only when it is needed.</b> The fallback is asked once, for exactly
   * the ids that had no open subscription; when every tenant has one, no query is issued at all.
   * Looping {@link TenantPlanService#resolvePlan} here would reintroduce the N+1 this class exists
   * to remove.
   *
   * @param allClientIds every tenant the caller is about to render; may be null or empty
   * @param openSubscriptions open subscription rows keyed by tenant id, as returned by
   *     {@link SubscriptionService#findOpenForClients(java.util.Collection)}; may be null or empty
   * @return an immutable view over those rows plus the transitional fallback
   */
  public static EnvironmentPlanCache of(Collection<String> allClientIds,
      Map<String, Subscription> openSubscriptions) {
    return of(allClientIds, openSubscriptions, new TenantPlanPreferenceFallback());
  }

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — seam that lets a spec substitute the fallback
   * and count the queries it issues. Delete with the rest of the fallback in Phase F.
   *
   * @param allClientIds every tenant the caller is about to render; may be null or empty
   * @param openSubscriptions open subscription rows keyed by tenant id; may be null or empty
   * @param fallback the preference fallback to consult for tenants with no open subscription
   * @return an immutable view over those rows plus the transitional fallback
   */
  static EnvironmentPlanCache of(Collection<String> allClientIds,
      Map<String, Subscription> openSubscriptions, TenantPlanPreferenceFallback fallback) {
    Map<String, PlanView> views = viewsOf(openSubscriptions);
    Set<String> withoutSubscription = new LinkedHashSet<>();
    if (allClientIds != null) {
      for (String clientId : allClientIds) {
        if (StringUtils.isNotBlank(clientId) && !views.containsKey(StringUtils.trim(clientId))) {
          withoutSubscription.add(StringUtils.trim(clientId));
        }
      }
    }
    if (!withoutSubscription.isEmpty()) {
      for (String clientId : fallback.productiveAmong(withoutSubscription)) {
        views.put(clientId, PRODUCTIVE_WITHOUT_SUBSCRIPTION);
      }
    }
    return views.isEmpty() ? EMPTY : new EnvironmentPlanCache(views);
  }

  /**
   * Returns the cache every tenant reads back as free from.
   *
   * @return the shared empty cache
   */
  public static EnvironmentPlanCache empty() {
    return EMPTY;
  }

  private static PlanView viewOf(Subscription subscription) {
    Plan plan = subscription.getPlan();
    String status = StringUtils.trimToNull(subscription.getSubscriptionStatus());
    return new PlanView(plan == null ? null : StringUtils.trimToNull(plan.getSearchKey()), status,
        TenantPlanService.legacyPlanForStatus(status));
  }

  /**
   * Returns what is known about a tenant, never null.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @return the tenant's plan view, or the no-subscription view when it has none
   */
  public PlanView viewFor(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return NO_SUBSCRIPTION;
    }
    return byClientId.getOrDefault(clientId, NO_SUBSCRIPTION);
  }

  /**
   * Returns whether the tenant is on a paid plan.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @return true when the tenant has an open subscription in a paying status
   */
  public boolean isProductive(String clientId) {
    return TenantPlanService.PLAN_PRODUCTIVE.equals(viewFor(clientId).legacyPlan());
  }

  /**
   * Returns the catalog key of the tenant's plan.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @return the plan's {@code VALUE}, or null when the tenant has no subscription
   */
  public String planKey(String clientId) {
    return viewFor(clientId).planKey();
  }

  /**
   * Returns the tenant's subscription status.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @return {@code active}, {@code past_due} or {@code canceled}, or null when it has no
   *     subscription
   */
  public String status(String clientId) {
    return viewFor(clientId).status();
  }

  /**
   * One tenant's commercial state.
   *
   * <p>{@code legacyPlan} is the {@code "free" | "productive"} vocabulary the environment list has
   * answered with since ETP-4686 and that live clients still branch on; {@code planKey} and
   * {@code status} are the finer-grained facts added by ETP-5046. The coarse value is derived from
   * the status rather than stored separately, so the two can never disagree.
   *
   * <p>One combination is deliberately reachable only through the transitional fallback
   * (ETP-5046-TRANSITIONAL-FALLBACK): {@code legacyPlan = "productive"} with a null
   * {@code planKey} and a null {@code status} — "productive but unsubscribed", a tenant the R37
   * backfill has not reached yet. It is expressible precisely because the three fields are
   * independent; nulls there state truthfully that no catalog row and no subscription exist.
   */
  public static final class PlanView {

    private final String planKey;
    private final String status;
    private final String legacyPlan;

    PlanView(String planKey, String status, String legacyPlan) {
      this.planKey = planKey;
      this.status = status;
      this.legacyPlan = legacyPlan;
    }

    /**
     * Returns the catalog key of the plan.
     *
     * @return the plan's {@code VALUE}, or null when there is no subscription
     */
    public String planKey() {
      return planKey;
    }

    /**
     * Returns the subscription status.
     *
     * @return the status, or null when there is no subscription
     */
    public String status() {
      return status;
    }

    /**
     * Returns the coarse plan the environment list has always reported.
     *
     * @return {@value TenantPlanService#PLAN_FREE} or {@value TenantPlanService#PLAN_PRODUCTIVE},
     *     never null
     */
    public String legacyPlan() {
      return legacyPlan;
    }
  }
}
