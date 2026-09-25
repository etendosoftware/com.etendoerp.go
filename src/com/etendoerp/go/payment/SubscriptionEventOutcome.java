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

  private SubscriptionEventOutcome(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt, String reason) {
    this(status, dueAt, reason, null, null, false, null);
  }

  private SubscriptionEventOutcome(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt, String reason, Instant periodStart, Instant periodEnd,
      boolean closesSubscription, Instant endedAt) {
    this.status = status;
    this.dueAt = dueAt;
    this.reason = reason;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.closesSubscription = closesSubscription;
    this.endedAt = endedAt;
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
        endedAt);
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
    return new SubscriptionEventOutcome(status, dueAt, reason, periodStart, periodEnd, true, ended);
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

  /** Start of the provider billing period this event reports, or null when it reports none. */
  public Instant periodStart() {
    return periodStart;
  }

  /** End of the provider billing period this event reports, or null when it reports none. */
  public Instant periodEnd() {
    return periodEnd;
  }

  /** Whether the event terminates the subscription, closing its row. */
  public boolean closesSubscription() {
    return closesSubscription;
  }

  /** When the provider ended the subscription; null when unknown (the row closes "now"). */
  public Instant endedAt() {
    return endedAt;
  }
}
