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
 *
 * <p>ETP-5047 — an applied outcome also carries what the row needs beyond the access state: the
 * provider billing period ({@link SubscriptionEventOutcome#withPeriod}, from the subscription or
 * its first item on {@code customer.subscription.*}, from the invoice lines on
 * {@code invoice.paid}) and whether the subscription is terminated
 * ({@link SubscriptionEventOutcome#closing}, on {@code customer.subscription.deleted} and on an
 * update to Stripe's terminal {@code canceled}).
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
        return invoicePaid(object);
      case INVOICE_PAYMENT_FAILED:
        return pastDue(epochSeconds(object, "period_end"));
      case SUBSCRIPTION_DELETED:
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null)
            .withPeriod(subscriptionPeriodBoundary(object, CURRENT_PERIOD_START),
                subscriptionPeriodBoundary(object, CURRENT_PERIOD_END))
            .closing(subscriptionEndedAt(object));
      case SUBSCRIPTION_UPDATED:
        return fromSubscriptionStatus(object, current)
            .withPeriod(subscriptionPeriodBoundary(object, CURRENT_PERIOD_START),
                subscriptionPeriodBoundary(object, CURRENT_PERIOD_END));
      default:
        return SubscriptionEventOutcome.ignore("unhandled event type");
    }
  }

  /**
   * A paid invoice makes the subscription current and reports the period it paid for.
   *
   * <p>ETP-5047 — the period comes from the invoice <em>lines</em>, not from the invoice's own
   * {@code period_start}/{@code period_end}: on a subscription invoice those look back one period
   * (the usage period that ended when the invoice was cut), while each line's {@code period} is the
   * service period of its price. The line reaching furthest is the new period; a proration line
   * never reaches past it.
   */
  private SubscriptionEventOutcome invoicePaid(JSONObject invoice) {
    SubscriptionEventOutcome current = SubscriptionEventOutcome.apply(
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null);
    BillingPeriod period = invoiceServicePeriod(invoice);
    return period == null ? current : current.withPeriod(period.start(), period.end());
  }

  /**
   * The service period an invoice bills: the {@code period} of its line reaching furthest.
   *
   * <p>Not the invoice's own {@code period_start}/{@code period_end}: on a subscription invoice
   * those look back one period (the usage period that ended when the invoice was cut). Shared with
   * {@link WebhookPayloadSummary}, so the ledger records the same period the row is given.
   *
   * @param invoice the invoice object ({@code data.object} of an {@code invoice.*} event)
   * @return the period, or null when no line carries one
   */
  static BillingPeriod invoiceServicePeriod(JSONObject invoice) {
    JSONObject lines = invoice == null ? null : invoice.optJSONObject("lines");
    JSONArray data = lines == null ? null : lines.optJSONArray("data");
    if (data == null) {
      return null;
    }
    Instant start = null;
    Instant end = null;
    for (int i = 0; i < data.length(); i++) {
      JSONObject line = data.optJSONObject(i);
      JSONObject period = line == null ? null : line.optJSONObject("period");
      Instant lineEnd = period == null ? null : epochSeconds(period, "end");
      if (lineEnd != null && (end == null || lineEnd.isAfter(end))) {
        end = lineEnd;
        start = epochSeconds(period, "start");
      }
    }
    return end == null ? null : new BillingPeriod(start, end);
  }

  /** A provider billing period; {@code start} may be null when the provider omitted it. */
  static final class BillingPeriod {
    private final Instant start;
    private final Instant end;

    BillingPeriod(Instant start, Instant end) {
      this.start = start;
      this.end = end;
    }

    Instant start() {
      return start;
    }

    Instant end() {
      return end;
    }
  }

  /**
   * When the provider ended a terminated subscription: {@code ended_at}, else
   * {@code canceled_at}, else null (the row is then closed at the time it is written).
   */
  static Instant subscriptionEndedAt(JSONObject subscription) {
    Instant endedAt = epochSeconds(subscription, "ended_at");
    return endedAt != null ? endedAt : epochSeconds(subscription, "canceled_at");
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
      return optText(object, "id");
    }
    String direct = optText(object, "subscription");
    if (StringUtils.isNotEmpty(direct)) {
      return direct;
    }
    JSONObject parent = object.optJSONObject("parent");
    JSONObject details = parent == null ? null : parent.optJSONObject("subscription_details");
    return details == null ? "" : optText(details, "subscription");
  }

  /**
   * Reads a String field the way Stripe actually sends it, never as the literal word "null".
   *
   * <p>An invoice not tied to a subscription reports {@code subscription} as JSON {@code null},
   * and jettison's {@code JSONObject.optString} stringifies that null marker into the literal
   * text {@code "null"} instead of falling back to the default. Left unguarded, {@link
   * #subscriptionIdOf} would return "null" as if it were a real subscription id. Every nullable
   * Stripe field in this class must be read through here, not through a bare {@code optString}.
   */
  private static String optText(JSONObject json, String key) {
    if (json == null || !json.has(key) || json.isNull(key)) {
      return "";
    }
    return StringUtils.trimToEmpty(json.optString(key, ""));
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
        // Terminal at the provider: a canceled Stripe subscription cannot be reactivated, so the
        // row closes here too, not only on customer.subscription.deleted (ETP-5047).
        return SubscriptionEventOutcome.apply(
            EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null)
            .closing(subscriptionEndedAt(object));
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
