/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Plan;

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

  private static final String PARAM_SEARCH_KEY = "searchKey";

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
   *   <li>a real, active row with no provider price — the grandfathered {@code legacy-productive}
   *       plan — → {@code 503 CHECKOUT_NOT_CONFIGURED}, because the plan catalog is fine and the
   *       <em>deployment</em> is not: an operator has not yet attached a Stripe price id, which is
   *       environment-specific and cannot ship as sourcedata.</li>
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
   * Returns whether a Subscription Plan Catalog row can actually be charged for.
   *
   * <p>A plan with no {@code PROVIDER_PRICE_ID} is not broken — it is the grandfathered plan that
   * predates provider billing and exists so backfilled tenants have something to point at. It
   * simply cannot start a checkout.
   *
   * @param plan a plan catalog row, may be null
   * @return true when the row carries a provider price id
   */
  public boolean hasProviderPrice(Plan plan) {
    return plan != null && StringUtils.isNotBlank(plan.getProviderPriceID());
  }
}
