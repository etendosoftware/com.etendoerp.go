/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5047 — reads the current billing period of a Stripe subscription, for a subscription row
 * that has just been opened.
 *
 * <p><b>Why it exists.</b> On a new purchase the first {@code invoice.paid} and
 * {@code customer.subscription.updated} webhooks arrive seconds after payment, before onboarding
 * has created the tenant and opened its row. They cannot be resolved to a row, are recorded as
 * ignored and acknowledged, and Stripe never redelivers them. Without this read a new tenant's row
 * carries no {@code CURRENT_PERIOD_START/END} until its first renewal, about a month later — and
 * the quotas and period usage (ETP-5051) read that period.
 *
 * <p><b>Best-effort by contract: it never throws.</b> Every failure — no secret key, a transport
 * failure (timeout, DNS, TLS, 5xx, 429), a 4xx, a body without a whole and ordered period, or
 * anything unexpected — answers {@code null}, and this reader logs one WARN for it naming the
 * tenant and the Stripe subscription id; for a transport failure, a 5xx or a 429 the transport
 * layer ({@link HttpUrlConnectionStripeApiClient}) may log its own WARN as well. The row then
 * stays as it was opened, and the first lifecycle event fills the period. This reader's log
 * carries no customer data and never the secret: the provider's error <em>code</em> is logged,
 * not its message.
 *
 * <p>The period is read with {@link SubscriptionLifecycleApplier#subscriptionPeriodBoundary}, the
 * same parser the {@code customer.subscription.*} webhooks use, so both shapes are understood: the
 * boundaries at the top level, or on the first subscription item from API 2025-03-31.
 *
 * <p>Uses its own short timeouts, not the checkout ones: nothing about the onboarding depends on
 * this answer, so a slow provider must cost seconds, not the full checkout budget.
 */
class StripeSubscriptionPeriodReader {

  private static final Logger log = LogManager.getLogger(StripeSubscriptionPeriodReader.class);

  /** Short on purpose: the period is an enrichment, never worth a long wait. */
  static final int CONNECT_TIMEOUT_MS = 3_000;
  static final int READ_TIMEOUT_MS = 5_000;

  private static final String SUBSCRIPTIONS_PATH = "/v1/subscriptions/";

  private final StripeApiClient stripeApiClient;
  private final BooleanSupplier secretKeyConfigured;

  /** The production reader: plain HTTP with short timeouts, against the configured provider. */
  StripeSubscriptionPeriodReader() {
    this(new HttpUrlConnectionStripeApiClient(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS),
        () -> StringUtils.isNotBlank(CheckoutConfiguration.secretKey()));
  }

  /**
   * A reader over an injected provider client, for tests.
   *
   * @param stripeApiClient the provider client to read through
   * @param secretKeyConfigured whether a provider secret key is configured
   */
  StripeSubscriptionPeriodReader(StripeApiClient stripeApiClient,
      BooleanSupplier secretKeyConfigured) {
    this.stripeApiClient = stripeApiClient;
    this.secretKeyConfigured = secretKeyConfigured;
  }

  /**
   * Reads the subscription's current billing period from the provider.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant, used only in the log line
   * @param stripeSubscriptionId {@code sub_...}; blank reads nothing and logs nothing
   * @return the whole, ordered billing period, or null on any failure
   */
  SubscriptionLifecycleApplier.BillingPeriod read(String environmentClientId,
      String stripeSubscriptionId) {
    String subscriptionId = StringUtils.trimToNull(stripeSubscriptionId);
    if (subscriptionId == null) {
      return null;
    }
    try {
      return readOrExplain(environmentClientId, subscriptionId);
    } catch (StripeTransportException e) {
      warn(environmentClientId, subscriptionId, "the payment provider did not answer ("
          + e.getMessage() + ")");
    } catch (RuntimeException e) { // NOSONAR - best effort: no failure may reach the onboarding
      warn(environmentClientId, subscriptionId, "the read failed unexpectedly ("
          + e.getClass().getSimpleName() + ")");
    }
    return null;
  }

  private SubscriptionLifecycleApplier.BillingPeriod readOrExplain(String environmentClientId,
      String subscriptionId) throws StripeTransportException {
    if (!secretKeyConfigured.getAsBoolean()) {
      warn(environmentClientId, subscriptionId, "no payment provider secret key is configured");
      return null;
    }
    StripeResponse response = stripeApiClient.get(
        SUBSCRIPTIONS_PATH + URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8));
    if (!response.isSuccess()) {
      warn(environmentClientId, subscriptionId, "the payment provider answered "
          + response.status() + " (" + Objects.toString(response.errorCode(), "no code")
          + ")");
      return null;
    }
    JSONObject subscription = response.json();
    Instant start = SubscriptionLifecycleApplier.subscriptionPeriodBoundary(subscription,
        SubscriptionLifecycleApplier.CURRENT_PERIOD_START);
    Instant end = SubscriptionLifecycleApplier.subscriptionPeriodBoundary(subscription,
        SubscriptionLifecycleApplier.CURRENT_PERIOD_END);
    if (start == null || end == null) {
      warn(environmentClientId, subscriptionId, "the answer carries no whole billing period");
      return null;
    }
    if (end.isBefore(start)) {
      // ETGO_SUB_PERIOD_CHK (END >= START) would refuse it; a reversed period is no period.
      warn(environmentClientId, subscriptionId, "the answer carries a period ending before it "
          + "starts");
      return null;
    }
    return new SubscriptionLifecycleApplier.BillingPeriod(start, end);
  }

  private static void warn(String environmentClientId, String subscriptionId, String reason) {
    log.warn("Could not read the billing period of Stripe subscription {} for client {}: {}. "
        + "The subscription row stays without a period until its next lifecycle event",
        subscriptionId, environmentClientId, reason);
  }
}
