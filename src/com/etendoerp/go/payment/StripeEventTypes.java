/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.List;

/**
 * The Stripe event types the checkout webhook acts on, by what it does with them. One list, read
 * by the webhook (which applies them) and by the billing reconciliation (which decides from it
 * which stuck events a re-read of the provider state may supersede), so the two cannot drift.
 */
public final class StripeEventTypes {

  /** A completed payment: recorded on its checkout request. */
  public static final List<String> CHECKOUT_PAID = List.of("checkout.session.completed",
      "checkout.session.async_payment_succeeded");

  /** A subscription lifecycle change: applied to the tenant's subscription state. */
  public static final List<String> SUBSCRIPTION_LIFECYCLE = List.of("invoice.payment_failed",
      "invoice.paid", "customer.subscription.updated", "customer.subscription.deleted");

  /**
   * ETP-5047 — events that raise an operator alert and change nothing (a dispute the provider is
   * already arbitrating).
   */
  public static final List<String> ALERT_ONLY = List.of("charge.dispute.created");

  /**
   * ETP-5048 — Stripe's dedicated event for a subscription that entered status {@code paused}:
   * alert-only, see {@link PausedSubscriptionAlert}.
   */
  public static final String SUBSCRIPTION_PAUSED = "customer.subscription.paused";

  private StripeEventTypes() {
  }
}
