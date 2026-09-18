/* Etendo License. */
package com.etendoerp.go.payment;

import com.etendoerp.go.common.GoRuntimeProperties;

/** Provider-neutral commercial offer exposed to account billing screens. */
public final class BillingOfferConfiguration {

  public static final String AMOUNT_PROPERTY = "etendo.go.billing.offer.amount.minor";
  public static final String AMOUNT_ENV = "ETGO_BILLING_OFFER_AMOUNT_MINOR";
  public static final String CURRENCY_PROPERTY = "etendo.go.billing.offer.currency";
  public static final String CURRENCY_ENV = "ETGO_BILLING_OFFER_CURRENCY";
  public static final String INTERVAL_PROPERTY = "etendo.go.billing.offer.interval";
  public static final String INTERVAL_ENV = "ETGO_BILLING_OFFER_INTERVAL";
  public static final long DEFAULT_AMOUNT_MINOR = 4900L;
  public static final String DEFAULT_CURRENCY = "EUR";
  public static final String DEFAULT_INTERVAL = "month";

  private BillingOfferConfiguration() {
  }

  /** Returns the configured offer, applying safe defaults for missing or invalid values. */
  public static Offer current() {
    long amount = readAmount();
    String currency = normalized(GoRuntimeProperties.readValue(CURRENCY_PROPERTY, CURRENCY_ENV,
        DEFAULT_CURRENCY), DEFAULT_CURRENCY);
    String interval = normalized(GoRuntimeProperties.readValue(INTERVAL_PROPERTY, INTERVAL_ENV,
        DEFAULT_INTERVAL), DEFAULT_INTERVAL);
    return new Offer(amount, currency, interval);
  }

  private static long readAmount() {
    String value = GoRuntimeProperties.readValue(AMOUNT_PROPERTY, AMOUNT_ENV,
        String.valueOf(DEFAULT_AMOUNT_MINOR));
    try {
      long amount = Long.parseLong(value);
      return amount >= 0 ? amount : DEFAULT_AMOUNT_MINOR;
    } catch (NumberFormatException e) {
      return DEFAULT_AMOUNT_MINOR;
    }
  }

  private static String normalized(String value, String fallback) {
    String result = value == null ? "" : value.trim();
    return result.isEmpty() ? fallback : result;
  }

  /** Immutable commercial offer value returned to billing consumers. */
  public static final class Offer {
    private final long amountMinor;
    private final String currency;
    private final String interval;

    Offer(long amountMinor, String currency, String interval) {
      this.amountMinor = amountMinor;
      this.currency = currency;
      this.interval = interval;
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
