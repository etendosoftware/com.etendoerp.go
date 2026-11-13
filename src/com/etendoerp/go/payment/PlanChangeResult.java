/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.time.Instant;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * ETP-5053 — what a plan change request did: applied now ({@link PlanChangeTiming#IMMEDIATE}),
 * scheduled for the end of the current billing period ({@link PlanChangeTiming#PERIOD_END}), or
 * an upgrade waiting for its payment ({@link #paymentRequired()}).
 */
public final class PlanChangeResult {

  private final PlanChangeTiming timing;
  private final Plan targetPlan;
  private final Instant effectiveAt;
  private final boolean alreadyPending;
  private final boolean paymentRequired;
  private final String paymentUrl;
  private final boolean pendingCancelled;

  PlanChangeResult(PlanChangeTiming timing, Plan targetPlan, Instant effectiveAt,
      boolean alreadyPending) {
    this(timing, targetPlan, effectiveAt, alreadyPending, false, null);
  }

  PlanChangeResult(PlanChangeTiming timing, Plan targetPlan, Instant effectiveAt,
      boolean alreadyPending, boolean paymentRequired, String paymentUrl) {
    this(timing, targetPlan, effectiveAt, alreadyPending, paymentRequired, paymentUrl, false);
  }

  PlanChangeResult(PlanChangeTiming timing, Plan targetPlan, Instant effectiveAt,
      boolean alreadyPending, boolean paymentRequired, String paymentUrl,
      boolean pendingCancelled) {
    this.timing = timing;
    this.targetPlan = targetPlan;
    this.effectiveAt = effectiveAt;
    this.alreadyPending = alreadyPending;
    this.paymentRequired = paymentRequired;
    this.paymentUrl = paymentUrl;
    this.pendingCancelled = pendingCancelled;
  }

  /** @return how the change applies */
  public PlanChangeTiming timing() {
    return timing;
  }

  /** @return the plan the subscription is now on, or moves to at {@link #effectiveAt()} */
  public Plan targetPlan() {
    return targetPlan;
  }

  /** @return when the change takes effect: now for an immediate change, the period end otherwise */
  public Instant effectiveAt() {
    return effectiveAt;
  }

  /** @return true when the same downgrade was already pending and nothing was asked of the provider */
  public boolean alreadyPending() {
    return alreadyPending;
  }

  /**
   * @return true when the upgrade is not applied yet: the saved payment method could not pay its
   *     invoice (declined, or the bank asks the customer to authenticate). The plan changes as soon
   *     as the invoice is paid; unpaid, the provider drops the change after about 23 hours.
   */
  public boolean paymentRequired() {
    return paymentRequired;
  }

  /** @return the provider's hosted page where the customer pays or authenticates; may be null */
  public String paymentUrl() {
    return paymentUrl;
  }

  /**
   * @return true when the request kept the current plan: the scheduled downgrade was cancelled and
   *     {@link #targetPlan()} is the plan the subscription stays on
   */
  public boolean pendingCancelled() {
    return pendingCancelled;
  }
}
