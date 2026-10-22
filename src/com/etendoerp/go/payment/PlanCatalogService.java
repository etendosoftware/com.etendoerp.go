/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;

/**
 * Reads the Subscription Plan Catalog ({@code ETGO_PLAN}) — the rows that replaced the single
 * deployment property {@code etendo.go.checkout.price.id} as the answer to "what can be bought".
 *
 * <p>Rows live at {@code AD_CLIENT_ID = '0'} and are read while serving an account-level request
 * whose {@link OBContext} belongs to some tenant, so every query disables the readable-client and
 * readable-organization filters and pins the row by search key instead. Same reasoning as
 * {@link SubscriptionService} and {@link TenantPlanService}.
 */
public class PlanCatalogService {

  /**
   * Search key of the grandfathered plan, created by the module script
   * {@code EnsureLegacyPlanScript} (which carries a literal copy of this key — keep them equal).
   * It carries no provider price of its own; it is sold only through the legacy price fallback
   * (see {@link #isLegacyFallbackActive()}).
   */
  public static final String LEGACY_PLAN_KEY = "legacy-productive";

  private static final String PARAM_SEARCH_KEY = "searchKey";
  private static final String PARAM_PLAN_ID = "planId";

  /**
   * Reads the configured legacy fallback price id ({@code etendo.go.checkout.price.id} /
   * {@code ETGO_CHECKOUT_PRICE_ID}). Package-visible so a test can pin the configuration without
   * touching system properties.
   */
  Supplier<String> configuredFallbackPriceId = CheckoutConfiguration::priceId;

  /**
   * Finds the Subscription Plan Catalog row a checkout may be started from.
   *
   * <p><b>What this method decides, and what it deliberately does not.</b> It answers "is there an
   * active plan catalog row under this key" and nothing else. Whether that row carries a provider price
   * — and is therefore sellable at all — is checked by the caller, because the two conditions must
   * produce different answers to the buyer:
   *
   * <ul>
   *   <li>no such key, or the key names an inactive row → {@code 400 PLAN_NOT_AVAILABLE}, and
   *       deliberately the <em>same</em> answer for both, so the endpoint never confirms which
   *       keys exist;</li>
   *   <li>a real, active row with no provider price → {@code 503 CHECKOUT_NOT_CONFIGURED},
   *       because the plan catalog is fine and the <em>deployment</em> is not: an operator has not
   *       yet attached a Stripe price id, which is environment-specific and cannot ship as
   *       sourcedata. The grandfathered {@link #LEGACY_PLAN_KEY} plan is the exception, handled by
   *       the caller: it is sold only through the legacy price fallback, and answers
   *       {@code 400 PLAN_NOT_AVAILABLE} while that fallback is inactive.</li>
   * </ul>
   *
   * <p>Folding the price check in here would collapse those two into one and lose that
   * distinction, so the caller keeps it. {@link #hasProviderPrice(Plan)} is the shared predicate,
   * so no call site hand-rolls the blank check.
   *
   * @param planKey the {@code VALUE} of the plan the browser asked for
   * @return the active plan catalog row, or {@link Optional#empty()} when there is none
   */
  public Optional<Plan> findPurchasablePlan(String planKey) {
    if (StringUtils.isBlank(planKey)) {
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<Plan> query = OBDal.getInstance().createQuery(Plan.class,
          "as plan where plan." + Plan.PROPERTY_SEARCHKEY + " = :" + PARAM_SEARCH_KEY
              + " and plan." + Plan.PROPERTY_ACTIVE + " = true");
      query.setNamedParameter(PARAM_SEARCH_KEY, StringUtils.trimToEmpty(planKey));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return Optional.ofNullable(query.uniqueResult());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Lists every Subscription Plan Catalog row a checkout may be started from, cheapest first.
   *
   * <p>This is the read-only half of {@link #findPurchasablePlan(String)}: that one answers "is
   * this key usable", this one answers "what is usable at all", which is what a client needs
   * before it can name a key. The two questions are asked by different endpoints and neither
   * discloses anything the other does not — a caller could learn the same set by probing keys,
   * except that it cannot, which is exactly why the list has to exist.
   *
   * <p>The provider-price condition is applied in Java rather than folded into the HQL, so the
   * single predicate {@link #hasProviderPrice(Plan)} decides "sellable" for every call site. A
   * second, hand-rolled blank check in a query string is how the two definitions drift apart.
   *
   * @return the active rows carrying a provider price id, never null
   */
  public List<Plan> listPurchasablePlans() {
    OBContext.setAdminMode(true);
    try {
      OBQuery<Plan> query = OBDal.getInstance().createQuery(Plan.class,
          "as plan where plan." + Plan.PROPERTY_ACTIVE + " = true"
              + " order by plan." + Plan.PROPERTY_DISPLAYPRICE
              + ", plan." + Plan.PROPERTY_NAME);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      List<Plan> purchasable = new ArrayList<>();
      for (Plan plan : query.list()) {
        if (hasProviderPrice(plan)) {
          purchasable.add(plan);
        }
      }
      return purchasable;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Lists the active quota rows of a plan, ordered by resource search key (ETP-5049).
   *
   * <p>Read for display only — what a plan includes, never an enforcement decision. An empty list
   * is meaningful: a plan with no quota rows is <b>unlimited</b> ({@code
   * open-and-notable-topics.md} §5.1), so a caller must not render it as "nothing included".
   *
   * @param plan the plan catalog row, may be null
   * @return the plan's active quota rows, never null; empty for a null plan
   */
  public List<PlanQuota> listActiveQuotas(Plan plan) {
    if (plan == null) {
      return new ArrayList<>();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<PlanQuota> query = OBDal.getInstance().createQuery(PlanQuota.class,
          "as quota where quota." + PlanQuota.PROPERTY_PLAN + ".id = :" + PARAM_PLAN_ID
              + " and quota." + PlanQuota.PROPERTY_ACTIVE + " = true"
              + " order by quota." + PlanQuota.PROPERTY_BILLINGRESOURCE + ".searchKey");
      query.setNamedParameter(PARAM_PLAN_ID, plan.getId());
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      return query.list();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Returns the default plan — the one the billing offer ({@code GET /sws/go/billing/offers})
   * quotes (ETP-5049).
   *
   * <p>While the legacy price fallback is active this is the grandfathered plan, sold at the
   * configured legacy price — exactly what checkout charges for a request that names no plan.
   * Otherwise it is the first row of {@link #listPurchasablePlans()} (cheapest first): the first
   * plan {@code GET /sws/go/plans} lists and the one the upgrade page selects by default, whose
   * key checkout then charges at the plan's own provider price. (A checkout that names no plan
   * once the fallback is retired is refused, not defaulted — this is the default of the
   * <em>list</em>, not of a blank key.) Both branches go through the same predicates checkout
   * decides on, so the offer can never quote a plan checkout would refuse.
   *
   * <p><b>It matches the first {@code GET /sws/go/plans} entry only while that plan can be
   * quoted.</b> {@code /plans} leaves out a plan it cannot quote — the grandfathered plan when
   * Stripe cannot price the configured legacy id, a catalog row with no display price or currency —
   * and lists the next one. The offer does not skip ahead: it quotes this plan live from Stripe and
   * answers {@code 503 BILLING_OFFER_UNAVAILABLE} when Stripe cannot price it.
   *
   * @return the default plan and whether it is sold through the legacy fallback price; empty when
   *     nothing is for sale
   */
  public Optional<DefaultPlan> findDefaultPurchasablePlan() {
    Optional<Plan> legacy = findLegacyFallbackPlan();
    if (legacy.isPresent()) {
      return Optional.of(new DefaultPlan(legacy.get(), true));
    }
    List<Plan> purchasable = listPurchasablePlans();
    return purchasable.isEmpty() ? Optional.empty()
        : Optional.of(new DefaultPlan(purchasable.get(0), false));
  }

  /**
   * Returns whether the <b>legacy price fallback</b> is active.
   *
   * <p>The one predicate both the plan list ({@code GET /sws/go/plans}) and checkout decide on, so
   * the two can never disagree about whether the fallback exists. It is true iff:
   * <ul>
   *   <li>a legacy price id is configured ({@link CheckoutConfiguration#priceId()} is non-blank),
   *       AND</li>
   *   <li>no active plan catalog row carries a provider price id
   *       ({@link #listPurchasablePlans()} is empty).</li>
   * </ul>
   *
   * <p>While it holds, checkout keeps selling at the configured price under the grandfathered
   * {@link #LEGACY_PLAN_KEY} plan, exactly as it did before the plan catalog existed. That removes
   * the deploy-ordering requirement: a deployment with no priced plan yet still sells, and a module
   * deployed ahead of the app-shell still answers a request that names no plan. The first priced
   * plan an operator creates retires the fallback on the next request, with no redeploy and no
   * property change.
   *
   * @return true when checkout should sell the configured legacy price
   */
  public boolean isLegacyFallbackActive() {
    return StringUtils.isNotBlank(configuredFallbackPriceId.get())
        && listPurchasablePlans().isEmpty();
  }

  /**
   * Returns the grandfathered plan to sell under the legacy price fallback.
   *
   * @return the active {@link #LEGACY_PLAN_KEY} row while {@link #isLegacyFallbackActive()} holds;
   *     empty when the fallback is inactive or the row is missing or inactive
   */
  public Optional<Plan> findLegacyFallbackPlan() {
    if (!isLegacyFallbackActive()) {
      return Optional.empty();
    }
    return findPurchasablePlan(LEGACY_PLAN_KEY);
  }

  /**
   * Returns whether a requested key asks for the grandfathered plan — either by name or by naming
   * no plan at all, which is how a client that predates the plan catalog asks for "the" product.
   *
   * @param planKey the key the browser sent, may be null or blank
   * @return true for a blank key or {@link #LEGACY_PLAN_KEY}
   */
  public static boolean namesLegacyPlan(String planKey) {
    return StringUtils.isBlank(planKey) || LEGACY_PLAN_KEY.equals(StringUtils.trim(planKey));
  }

  /**
   * Returns whether a Subscription Plan Catalog row can actually be charged for.
   *
   * <p>A plan with no {@code PROVIDER_PRICE_ID} is not broken — it is the grandfathered plan that
   * predates provider billing and exists so backfilled tenants have something to point at. It
   * cannot start a checkout on its own price; it is sold only through the legacy price fallback.
   *
   * @param plan a plan catalog row, may be null
   * @return true when the row carries a provider price id
   */
  public boolean hasProviderPrice(Plan plan) {
    return plan != null && StringUtils.isNotBlank(plan.getProviderPriceID());
  }

  /** The plan {@link #findDefaultPurchasablePlan()} chose, and how it is priced. */
  public static final class DefaultPlan {
    private final Plan plan;
    private final boolean legacyFallback;

    public DefaultPlan(Plan plan, boolean legacyFallback) {
      this.plan = plan;
      this.legacyFallback = legacyFallback;
    }

    /** @return the plan catalog row, never null */
    public Plan plan() {
      return plan;
    }

    /**
     * @return true when the plan is sold at the configured legacy price
     *     ({@link CheckoutConfiguration#priceId()}), false when at its own provider price
     */
    public boolean isLegacyFallback() {
      return legacyFallback;
    }
  }
}
