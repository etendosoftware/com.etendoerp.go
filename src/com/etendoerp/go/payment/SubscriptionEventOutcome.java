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

  private SubscriptionEventOutcome(EnvironmentAccessPolicy.SubscriptionStatus status,
      Instant dueAt, String reason) {
    this.status = status;
    this.dueAt = dueAt;
    this.reason = reason;
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
}
