/* Etendo License. */
package com.etendoerp.go.payment;

import java.time.Instant;

/**
 * The result of interpreting one Stripe lifecycle event, decided without touching the database.
 *
 * <p>An ignored outcome carries the reason verbatim into {@code ETGO_BILLING_EVENT}, so an event
 * that changed nothing is still findable afterwards.
 */
public final class SubscriptionEventOutcome {

  private final EnvironmentAccessPolicy.SubscriptionStatus status;
  private final Instant dueAt;
  private final String reason;
  private final Instant periodStart;
  private final Instant periodEnd;
  private final boolean closesSubscription;
  private final Instant endedAt;
  private final CancelAtPeriodEnd cancelAtPeriodEnd;
  private final String providerPriceId;

  private SubscriptionEventOutcome(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt, String reason) {
    this(status, dueAt, reason, null, null, false, null, CancelAtPeriodEnd.NOT_REPORTED, null);
  }

  private SubscriptionEventOutcome(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt, String reason, Instant periodStart, Instant periodEnd,
      boolean closesSubscription, Instant endedAt, CancelAtPeriodEnd cancelAtPeriodEnd,
      String providerPriceId) {
    this.status = status;
    this.dueAt = dueAt;
    this.reason = reason;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.closesSubscription = closesSubscription;
    this.endedAt = endedAt;
    this.cancelAtPeriodEnd = cancelAtPeriodEnd;
    this.providerPriceId = providerPriceId;
  }

  /** The event maps to a state transition. */
  public static SubscriptionEventOutcome apply(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt) {
    return new SubscriptionEventOutcome(status, dueAt, null);
  }

  /** The event is understood but changes nothing; {@code reason} is recorded for audit. */
  public static SubscriptionEventOutcome ignore(String reason) {
    return new SubscriptionEventOutcome(null, null, reason);
  }

  /**
   * ETP-5047 — the same transition, also carrying the provider's billing period. A period is
   * kept only when both ends are known and ordered; otherwise the outcome is returned unchanged,
   * so a half period never overwrites a whole one.
   *
   * @param start start of the billing period, may be null
   * @param end end of the billing period, may be null
   * @return an outcome carrying the period, or this one
   */
  public SubscriptionEventOutcome withPeriod(Instant start, Instant end) {
    if (isIgnored() || start == null || end == null || end.isBefore(start)) {
      return this;
    }
    return new SubscriptionEventOutcome(status, dueAt, reason, start, end, closesSubscription,
        endedAt, cancelAtPeriodEnd, providerPriceId);
  }

  /**
   * ETP-5047 — the same transition, also closing the subscription row ({@code END_DATE}): the
   * provider subscription is terminated and cannot be revived, so a later purchase opens a new
   * row.
   *
   * @param ended when the provider ended the subscription; null means "now"
   * @return an outcome that closes the row
   */
  public SubscriptionEventOutcome closing(Instant ended) {
    if (isIgnored()) {
      return this;
    }
    return new SubscriptionEventOutcome(status, dueAt, reason, periodStart, periodEnd, true, ended,
        cancelAtPeriodEnd, providerPriceId);
  }

  /**
   * ETP-5051 (O-1b) — the same transition, also carrying the provider's
   * {@code cancel_at_period_end}. Display-only: it never changes the status, the grace anchor or
   * the close, and an ignored outcome stays ignored (nothing is written for it).
   *
   * @param cancel the provider flag; {@link CancelAtPeriodEnd#NOT_REPORTED} (or null) when the
   *     event does not report it, which leaves the stored value alone
   * @return an outcome carrying the flag, or this one
   */
  public SubscriptionEventOutcome withCancelAtPeriodEnd(CancelAtPeriodEnd cancel) {
    if (isIgnored() || cancel == null || !cancel.isReported()) {
      return this;
    }
    return new SubscriptionEventOutcome(status, dueAt, reason, periodStart, periodEnd,
        closesSubscription, endedAt, cancel, providerPriceId);
  }

  /**
   * ETP-5053 — the same transition, also carrying the Stripe price the subscription item is on.
   * The lifecycle write compares it with the row's {@code PROVIDER_PRICE_ID}: a price that moved
   * to another plan's price is an applied plan change, and the row is rolled over to a successor
   * on that plan before the transition is written. An ignored outcome stays ignored, and a blank
   * price is "not reported", which compares nothing.
   *
   * @param priceId the {@code price_...} of the first subscription item, may be null
   * @return an outcome carrying the price, or this one
   */
  public SubscriptionEventOutcome withProviderPrice(String priceId) {
    if (isIgnored() || priceId == null || priceId.isBlank()) {
      return this;
    }
    return new SubscriptionEventOutcome(status, dueAt, reason, periodStart, periodEnd,
        closesSubscription, endedAt, cancelAtPeriodEnd, priceId.trim());
  }

  public boolean isApplied() {
    return status != null;
  }

  public boolean isIgnored() {
    return status == null;
  }

  public EnvironmentAccessPolicy.SubscriptionStatus status() {
    return status;
  }

  /** Grace anchor. Null means "clear the stored due date", never "block immediately". */
  public Instant dueAt() {
    return dueAt;
  }

  public String reason() {
    return reason;
  }

  /**
   * Start of the provider billing period this event reports.
   *
   * @return the period start, or null when the event reports none
   */
  public Instant periodStart() {
    return periodStart;
  }

  /**
   * End of the provider billing period this event reports.
   *
   * @return the period end, or null when the event reports none
   */
  public Instant periodEnd() {
    return periodEnd;
  }

  /**
   * Whether the event terminates the subscription, closing its row.
   *
   * @return true when applying the event closes the subscription row
   */
  public boolean closesSubscription() {
    return closesSubscription;
  }

  /**
   * When the provider ended the subscription.
   *
   * @return the end instant, or null when unknown (the row then closes "now")
   */
  public Instant endedAt() {
    return endedAt;
  }

  /**
   * Whether the provider will cancel the subscription at the end of the current period.
   *
   * @return the provider flag; {@link CancelAtPeriodEnd#NOT_REPORTED} when the event does not
   *     report it, never null
   */
  public CancelAtPeriodEnd cancelAtPeriodEnd() {
    return cancelAtPeriodEnd;
  }

  /**
   * ETP-5053 — the Stripe price the subscription item is on, as the event reports it.
   *
   * @return the price id, or null when the event does not report one
   */
  public String providerPriceId() {
    return providerPriceId;
  }
}
