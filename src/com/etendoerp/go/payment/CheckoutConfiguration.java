/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import com.etendoerp.go.common.GoRuntimeProperties;

/** External configuration for the hosted checkout provider. No secret has a repository default. */
public final class CheckoutConfiguration {
  private CheckoutConfiguration() {
  }

  /**
   * Returns the provider secret key from server-side configuration.
   * @return configured provider secret key, or an empty string
   */
  public static String secretKey() {
    return GoRuntimeProperties.readValue("etendo.go.checkout.secret.key", "ETGO_CHECKOUT_SECRET_KEY", "");
  }

  /**
   * Returns the webhook signature secret from server-side configuration.
   * @return configured webhook secret, or an empty string
   */
  public static String webhookSecret() {
    return GoRuntimeProperties.readValue("etendo.go.checkout.webhook.secret", "ETGO_CHECKOUT_WEBHOOK_SECRET", "");
  }

  /**
   * Returns the server-selected checkout price identifier.
   * @return configured price identifier, or an empty string
   */
  public static String priceId() {
    return GoRuntimeProperties.readValue("etendo.go.checkout.price.id", "ETGO_CHECKOUT_PRICE_ID", "");
  }

  /**
   * Returns the normalized checkout mode, defaulting to subscription.
   * @return {@code payment} or {@code subscription}
   */
  public static String mode() {
    String value = GoRuntimeProperties.readValue("etendo.go.checkout.mode", "ETGO_CHECKOUT_MODE", "subscription");
    return "payment".equalsIgnoreCase(value) ? "payment" : "subscription";
  }

  /**
   * Returns the configured provider API base URL.
   * @return provider API base URL
   */
  public static String apiBaseUrl() {
    return GoRuntimeProperties.readValue("etendo.go.checkout.api.base.url", "ETGO_CHECKOUT_API_BASE_URL", "https://api.stripe.com");
  }

  /**
   * Returns whether all mandatory checkout settings are present.
   * @return true when checkout can be used
   */
  public static boolean isConfigured() {
    return !secretKey().trim().isEmpty() && !priceId().trim().isEmpty() && !webhookSecret().trim().isEmpty();
  }
}
