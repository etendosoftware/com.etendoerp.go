/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.time.Instant;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * ETP-5053 — what a plan change request did: applied now ({@link PlanChangeTiming#IMMEDIATE}) or
 * scheduled for the end of the current billing period ({@link PlanChangeTiming#PERIOD_END}).
 */
public final class PlanChangeResult {

  private final PlanChangeTiming timing;
  private final Plan targetPlan;
  private final Instant effectiveAt;
  private final boolean alreadyPending;

  PlanChangeResult(PlanChangeTiming timing, Plan targetPlan, Instant effectiveAt,
      boolean alreadyPending) {
    this.timing = timing;
    this.targetPlan = targetPlan;
    this.effectiveAt = effectiveAt;
    this.alreadyPending = alreadyPending;
  }

  /** @return how the change applied */
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
}
