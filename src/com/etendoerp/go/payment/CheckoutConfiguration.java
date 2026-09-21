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
   * Returns how long to wait for the provider to accept a connection.
   *
   * @return connect timeout in milliseconds
   */
  public static int connectTimeoutMs() {
    return GoRuntimeProperties.readInt("etendo.go.checkout.connect.timeout.ms",
        "ETGO_CHECKOUT_CONNECT_TIMEOUT_MS", 10000);
  }

  /**
   * Returns how long to wait for the provider to answer once connected.
   *
   * @return read timeout in milliseconds
   */
  public static int readTimeoutMs() {
    return GoRuntimeProperties.readInt("etendo.go.checkout.read.timeout.ms",
        "ETGO_CHECKOUT_READ_TIMEOUT_MS", 20000);
  }

  /**
   * Returns whether all mandatory checkout settings are present.
   *
   * <p>This used to also require a configured price id, so a true answer additionally proved that
   * <em>a purchasable thing existed</em>. That guarantee has moved: what is purchasable now comes
   * from the Subscription Plan Catalog — a plan row carrying a non-null provider price id —
   * and there is
   * deliberately no configured fallback price, because a fallback is a price nobody reviewed,
   * selected exactly when the intended configuration is missing. The two conditions map onto the
   * same {@code CHECKOUT_NOT_CONFIGURED} response for that reason: from the caller's side
   * "checkout has no credentials" and "there is nothing to sell" are the same unavailability.
   *
   * @return true when checkout can be used
   */
  public static boolean isConfigured() {
    return !secretKey().trim().isEmpty() && !webhookSecret().trim().isEmpty();
  }
}
