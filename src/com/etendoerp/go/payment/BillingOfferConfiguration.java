/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.IOException;

import org.codehaus.jettison.json.JSONException;

/** Provider-neutral commercial offer projected from the exact Stripe checkout price. */
public final class BillingOfferConfiguration {
  private BillingOfferConfiguration() {
  }

  /** Retrieves the live configured offer. Provider/configuration failures are deliberately fatal. */
  public static Offer current() throws IOException, JSONException {
    return current(new StripePriceService());
  }

  /** Retrieves the live configured offer through the servlet's provider adapter. */
  public static Offer current(StripePriceService stripePriceService)
      throws IOException, JSONException {
    StripePriceService.Price price = stripePriceService.retrieveConfiguredPrice();
    return new Offer(price.getId(), price.getAmountMinor(), price.getCurrency(), price.getInterval());
  }

  /** Immutable commercial offer returned to billing consumers. */
  public static final class Offer {
    private final String priceId;
    private final long amountMinor;
    private final String currency;
    private final String interval;

    Offer(String priceId, long amountMinor, String currency, String interval) {
      this.priceId = priceId;
      this.amountMinor = amountMinor;
      this.currency = currency;
      this.interval = interval;
    }

    public String getPriceId() {
      return priceId;
    }

    public long getAmountMinor() {
      return amountMinor;
    }

    public String getCurrency() {
      return currency;
    }

    public String getInterval() {
      return interval;
    }
  }
}
