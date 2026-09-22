/* Etendo License. */
package com.etendoerp.go.payment;

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * Maps a Stripe subscription or invoice event to a target access state.
 *
 * <p>Deliberately pure: no database, no network, no clock. The grace anchor and the
 * never-block-without-a-due-date rule are the two decisions in this task that can lock a paying
 * customer out of their own data, so they live where a unit test can pin them.
 */
public class SubscriptionLifecycleApplier {

  static final String INVOICE_PAID = "invoice.paid";
  static final String INVOICE_PAYMENT_FAILED = "invoice.payment_failed";
  static final String SUBSCRIPTION_UPDATED = "customer.subscription.updated";
  static final String SUBSCRIPTION_DELETED = "customer.subscription.deleted";

  private static final String MISSING_PERIOD_END = "missing period end";

  /**
   * @param type Stripe event type
   * @param event the full event envelope
   * @return the transition to apply, or an ignore carrying the audit reason
   */
  public SubscriptionEventOutcome evaluate(String type, JSONObject event) {
    JSONObject data = event == null ? null : event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    if (object == null) {
      return SubscriptionEventOutcome.ignore("missing event object");
    }
    switch (StringUtils.trimToEmpty(type)) {
      case INVOICE_PAID:
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);
      case INVOICE_PAYMENT_FAILED:
        return pastDue(object);
      case SUBSCRIPTION_DELETED:
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null);
      case SUBSCRIPTION_UPDATED:
        return fromSubscriptionStatus(object);
      default:
        return SubscriptionEventOutcome.ignore("unhandled event type");
    }
  }

  /**
   * Reads the subscription's own status.
   *
   * <p>{@code cancel_at_period_end} is deliberately not consulted: a scheduled cancellation keeps
   * the subscription active, and access must continue until Stripe sends the delete at the period
   * boundary. The flag is display-only and read live by the Subscription page.
   */
  private SubscriptionEventOutcome fromSubscriptionStatus(JSONObject object) {
    String status = StringUtils.trimToEmpty(object.optString("status", ""));
    switch (status) {
      case "active":
      case "trialing":
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);
      case "past_due":
      case "unpaid":
        return pastDue(object);
      case "canceled":
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null);
      default:
        return SubscriptionEventOutcome.ignore("unhandled subscription status");
    }
  }

  /**
   * Builds the overdue transition, anchored on the end of the period the customer already paid for.
   *
   * <p>Without that anchor the policy grants no grace at all, so an event that cannot supply one is
   * ignored rather than applied half-way.
   */
  private SubscriptionEventOutcome pastDue(JSONObject object) {
    Instant periodEnd = epochSeconds(object, "period_end");
    if (periodEnd == null) {
      periodEnd = epochSeconds(object, "current_period_end");
    }
    if (periodEnd == null) {
      return SubscriptionEventOutcome.ignore(MISSING_PERIOD_END);
    }
    return SubscriptionEventOutcome.apply(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, periodEnd);
  }

  private Instant epochSeconds(JSONObject object, String field) {
    long seconds = object.optLong(field, 0L);
    return seconds > 0L ? Instant.ofEpochSecond(seconds) : null;
  }
}
