/* Etendo License. */
package com.etendoerp.go.payment;

/**
 * ETP-5051 (O-1b) — the provider's {@code cancel_at_period_end} as a lifecycle event reports it:
 * scheduled, not scheduled, or not reported at all. The third state is what keeps an event that
 * does not carry the flag (an invoice event, a subscription object without the field) from
 * overwriting the stored {@code ETGO_SUBSCRIPTION.CANCEL_AT_PERIOD_END}.
 *
 * <p>Display-only: it feeds the environment list's {@code renewalAt} and never an access, grace
 * or status decision.
 */
public enum CancelAtPeriodEnd {
  /** The event does not report the flag: the stored value is left alone. */
  NOT_REPORTED,
  /** The provider will cancel the subscription at the end of the current period. */
  SET,
  /** The provider will renew the subscription at the end of the current period. */
  CLEARED;

  /**
   * The reported state of a known flag value.
   *
   * @param cancel the provider flag
   * @return {@link #SET} or {@link #CLEARED}
   */
  public static CancelAtPeriodEnd of(boolean cancel) {
    return cancel ? SET : CLEARED;
  }

  /**
   * Whether the event reported the flag at all.
   *
   * @return false only for {@link #NOT_REPORTED}
   */
  public boolean isReported() {
    return this != NOT_REPORTED;
  }

  /**
   * The flag value to store, meaningful only when {@link #isReported()}.
   *
   * @return true for {@link #SET}
   */
  public boolean isSet() {
    return this == SET;
  }

  /**
   * Whether writing this state would change nothing: it is not reported, or it equals the stored
   * flag ({@code null}, a row read before its default was applied, never matches).
   *
   * @param stored the row's {@code CANCEL_AT_PERIOD_END}, may be null
   * @return true when there is nothing to write
   */
  public boolean leavesUnchanged(Boolean stored) {
    return !isReported() || Boolean.valueOf(isSet()).equals(stored);
  }
}
