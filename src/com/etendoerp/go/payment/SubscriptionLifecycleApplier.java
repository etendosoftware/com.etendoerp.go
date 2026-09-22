/* Etendo License. */
package com.etendoerp.go.payment;

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Maps a Stripe subscription or invoice event to a target access state.
 *
 * <p>Deliberately pure: no database, no network, no clock. The grace anchor and the
 * never-block-without-a-due-date rule are the two decisions in this task that can lock a paying
 * customer out of their own data, so they live where a unit test can pin them. The stored
 * projection is passed in rather than read, for the same reason.
 *
 * <p>The Stripe API version is not pinned, so every provider field that moved between versions is
 * read in both shapes: the subscription period at the top level or on its first item (API
 * 2025-03-31 and later), and the invoice's subscription directly or under
 * {@code parent.subscription_details}.
 */
public class SubscriptionLifecycleApplier {

  static final String INVOICE_PAID = "invoice.paid";
  static final String INVOICE_PAYMENT_FAILED = "invoice.payment_failed";
  static final String SUBSCRIPTION_UPDATED = "customer.subscription.updated";
  static final String SUBSCRIPTION_DELETED = "customer.subscription.deleted";
  static final String SUBSCRIPTION_EVENT_PREFIX = "customer.subscription.";

  static final String CURRENT_PERIOD_START = "current_period_start";
  static final String CURRENT_PERIOD_END = "current_period_end";

  private static final String MISSING_PERIOD_END = "missing period end";
  private static final String STALE_EVENT = "stale event";

  /**
   * Evaluates an event against an empty stored projection.
   *
   * @param type Stripe event type
   * @param event the full event envelope
   * @return the transition to apply, or an ignore carrying the audit reason
   */
  public SubscriptionEventOutcome evaluate(String type, JSONObject event) {
    return evaluate(type, event, StoredState.NONE);
  }

  /**
   * Evaluates an event against the environment's stored projection.
   *
   * <p>Stripe does not guarantee delivery order, and a {@code FAILED} row is retried later, so an
   * event created before the last applied one is ignored as stale: a late {@code past_due} must
   * not overwrite the {@code CURRENT} a newer {@code invoice.paid} already stored.
   *
   * @param type Stripe event type
   * @param event the full event envelope
   * @param stored the environment's current projection; null is treated as empty
   * @return the transition to apply, or an ignore carrying the audit reason
   */
  public SubscriptionEventOutcome evaluate(String type, JSONObject event, StoredState stored) {
    StoredState current = stored == null ? StoredState.NONE : stored;
    JSONObject object = eventObject(event);
    if (object == null) {
      return SubscriptionEventOutcome.ignore("missing event object");
    }
    Instant createdAt = eventCreatedAt(event);
    if (createdAt != null && current.lastEventAt() != null
        && createdAt.isBefore(current.lastEventAt())) {
      return SubscriptionEventOutcome.ignore(STALE_EVENT);
    }
    switch (StringUtils.trimToEmpty(type)) {
      case INVOICE_PAID:
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);
      case INVOICE_PAYMENT_FAILED:
        return pastDue(epochSeconds(object, "period_end"));
      case SUBSCRIPTION_DELETED:
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null);
      case SUBSCRIPTION_UPDATED:
        return fromSubscriptionStatus(object, current);
      default:
        return SubscriptionEventOutcome.ignore("unhandled event type");
    }
  }

  /**
   * The Stripe subscription an event belongs to.
   *
   * <p>Subscription events are keyed by their own object id; invoice events carry the id either
   * top-level or, from API 2025-03-31, under {@code parent.subscription_details}.
   *
   * @param type Stripe event type
   * @param object the event's {@code data.object}
   * @return the subscription id, or an empty string when the event names none
   */
  public static String subscriptionIdOf(String type, JSONObject object) {
    if (object == null) {
      return "";
    }
    if (StringUtils.trimToEmpty(type).startsWith(SUBSCRIPTION_EVENT_PREFIX)) {
      return StringUtils.trimToEmpty(object.optString("id", ""));
    }
    String direct = StringUtils.trimToEmpty(object.optString("subscription", ""));
    if (StringUtils.isNotEmpty(direct)) {
      return direct;
    }
    JSONObject parent = object.optJSONObject("parent");
    JSONObject details = parent == null ? null : parent.optJSONObject("subscription_details");
    return details == null ? "" : StringUtils.trimToEmpty(details.optString("subscription", ""));
  }

  /**
   * The event's own creation instant ({@code created}), used to order out-of-order deliveries.
   *
   * @param event the full event envelope
   * @return the creation instant, or null when the envelope carries none
   */
  public static Instant eventCreatedAt(JSONObject event) {
    return event == null ? null : epochSeconds(event, "created");
  }

  /**
   * Reads a subscription period boundary from the top level or, from API 2025-03-31, from the
   * first subscription item.
   */
  static Instant subscriptionPeriodBoundary(JSONObject subscription, String field) {
    Instant topLevel = epochSeconds(subscription, field);
    if (topLevel != null) {
      return topLevel;
    }
    JSONObject items = subscription.optJSONObject("items");
    JSONArray data = items == null ? null : items.optJSONArray("data");
    JSONObject first = data == null ? null : data.optJSONObject(0);
    return first == null ? null : epochSeconds(first, field);
  }

  /**
   * Reads the subscription's own status.
   *
   * <p>{@code cancel_at_period_end} is deliberately not consulted: a scheduled cancellation keeps
   * the subscription active, and access must continue until Stripe sends the delete at the period
   * boundary. The flag is display-only and read live by the Subscription page.
   */
  private SubscriptionEventOutcome fromSubscriptionStatus(JSONObject object, StoredState current) {
    String status = StringUtils.trimToEmpty(object.optString("status", ""));
    switch (status) {
      case "active":
      case "trialing":
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);
      case "past_due":
      case "unpaid":
        return subscriptionPastDue(object, current);
      case "canceled":
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null);
      default:
        return SubscriptionEventOutcome.ignore("unhandled subscription status");
    }
  }

  /**
   * Overdue transition from a subscription object.
   *
   * <p>Stripe advances the period at renewal even when the charge fails, so on a past-due
   * subscription {@code current_period_end} is the end of the new, unpaid period; the end of the
   * paid one is {@code current_period_start}. A due date already stored for {@code PAST_DUE} came
   * from {@code invoice.payment_failed}, which is authoritative, and is kept.
   */
  private SubscriptionEventOutcome subscriptionPastDue(JSONObject object, StoredState current) {
    if (current.status() == EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE
        && current.dueAt() != null) {
      return SubscriptionEventOutcome.apply(
          EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, current.dueAt());
    }
    return pastDue(subscriptionPeriodBoundary(object, CURRENT_PERIOD_START));
  }

  /**
   * Builds the overdue transition, anchored on the end of the period the customer already paid for.
   *
   * <p>Without that anchor the policy grants no grace at all, so an event that cannot supply one is
   * ignored rather than applied half-way.
   */
  private SubscriptionEventOutcome pastDue(Instant paidPeriodEnd) {
    if (paidPeriodEnd == null) {
      return SubscriptionEventOutcome.ignore(MISSING_PERIOD_END);
    }
    return SubscriptionEventOutcome.apply(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, paidPeriodEnd);
  }

  private static JSONObject eventObject(JSONObject event) {
    JSONObject data = event == null ? null : event.optJSONObject("data");
    return data == null ? null : data.optJSONObject("object");
  }

  private static Instant epochSeconds(JSONObject object, String field) {
    long seconds = object.optLong(field, 0L);
    return seconds > 0L ? Instant.ofEpochSecond(seconds) : null;
  }

  /** The stored subscription projection of one environment, as the applier needs to see it. */
  public static final class StoredState {
    /** No projection stored yet. */
    public static final StoredState NONE = new StoredState(null, null, null);

    private final EnvironmentAccessPolicy.SubscriptionStatus status;
    private final Instant dueAt;
    private final Instant lastEventAt;

    /**
     * @param status stored subscription status, or null
     * @param dueAt stored grace anchor, or null
     * @param lastEventAt {@code created} of the last applied lifecycle event, or null
     */
    public StoredState(EnvironmentAccessPolicy.SubscriptionStatus status, Instant dueAt,
        Instant lastEventAt) {
      this.status = status;
      this.dueAt = dueAt;
      this.lastEventAt = lastEventAt;
    }

    public EnvironmentAccessPolicy.SubscriptionStatus status() {
      return status;
    }

    public Instant dueAt() {
      return dueAt;
    }

    public Instant lastEventAt() {
      return lastEventAt;
    }
  }
}
