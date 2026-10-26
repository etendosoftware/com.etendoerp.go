/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.IOException;

import org.codehaus.jettison.json.JSONException;

/**
 * Provider-neutral commercial offer, projected from the exact Stripe price checkout charges for
 * the plan catalog's default plan.
 *
 * <p>Since ETP-5049 the offer follows the Subscription Plan Catalog
 * ({@link PlanCatalogService#findDefaultPurchasablePlan()}): while the legacy price fallback is
 * active it quotes the configured legacy price, exactly what a checkout that names no plan charges;
 * once a priced plan retires the fallback it quotes the first purchasable plan at that plan's own
 * provider price. Before, it always quoted {@code etendo.go.checkout.price.id}, so after the
 * cutover it disagreed with the charged price and then answered 503 once the property was removed.
 *
 * <p>The offer carries no provider price id: the browser names plans, never prices.
 */
public final class BillingOfferConfiguration {
  private BillingOfferConfiguration() {
  }

  /**
   * Retrieves the live offer for the plan catalog's default plan. Provider and configuration
   * failures are deliberately fatal: an offer whose price nobody could verify is never shown.
   *
   * @param planCatalogService plan catalog used to pick the default plan
   * @param stripePriceService adapter used to retrieve and validate the plan's Stripe Price
   * @return the current offer derived from the price checkout charges for that plan
   * @throws IllegalStateException when nothing is for sale, or checkout is not configured
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public static Offer current(PlanCatalogService planCatalogService,
      StripePriceService stripePriceService) throws IOException, JSONException {
    PlanCatalogService.DefaultPlan defaultPlan = planCatalogService.findDefaultPurchasablePlan()
        .orElseThrow(() -> new IllegalStateException("No purchasable plan to quote"));
    StripePriceService.Price price = defaultPlan.isLegacyFallback()
        ? stripePriceService.retrieveConfiguredPrice()
        : stripePriceService.retrievePrice(defaultPlan.plan().getProviderPriceID());
    return new Offer(defaultPlan.plan().getSearchKey(), price.getAmountMinor(),
        price.getCurrency(), price.getInterval());
  }

  /** Immutable commercial offer returned to billing consumers. */
  public static final class Offer {
    private final String planKey;
    private final long amountMinor;
    private final String currency;
    private final String interval;

    Offer(String planKey, long amountMinor, String currency, String interval) {
      this.planKey = planKey;
      this.amountMinor = amountMinor;
      this.currency = currency;
      this.interval = interval;
    }

    /**
     * Returns the plan catalog key of the quoted plan.
     *
     * @return the key a checkout for the quoted plan names
     */
    public String getPlanKey() {
      return planKey;
    }

    /**
     * Returns the quoted amount in the currency's minor units.
     *
     * @return the amount as Stripe reports it, e.g. 2900 for 29.00 EUR
     */
    public long getAmountMinor() {
      return amountMinor;
    }

    /**
     * Returns the quoted currency.
     *
     * @return the ISO 4217 currency code, upper case
     */
    public String getCurrency() {
      return currency;
    }

    /**
     * Returns the quoted billing interval.
     *
     * @return the Stripe price interval ({@code month}, {@code year}, ...) or {@code once}
     */
    public String getInterval() {
      return interval;
    }
  }
}
