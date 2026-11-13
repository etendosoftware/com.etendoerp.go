/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.math.BigDecimal;
import java.time.Instant;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * ETP-5053 — what a plan change would cost, read before the customer confirms it.
 *
 * <p>An upgrade ({@link PlanChangeTiming#IMMEDIATE}) carries the amount the saved payment method
 * is charged today and the payment method itself, previewed by the provider at
 * {@link #prorationDate()}; sending that same proration date with the change makes the charge
 * equal to the previewed amount. A downgrade ({@link PlanChangeTiming#PERIOD_END}) charges
 * nothing today: it carries only the date it takes effect.
 */
public final class PlanChangePreview {

  /**
   * What an upgrade charges today: the amount in major units with its currency, the proration
   * instant it was computed at, and the card it goes on (brand and last four digits, each null when
   * unknown).
   */
  public record Charge(BigDecimal amountDue, String currency, Long prorationDate, String cardBrand,
      String cardLast4) {
  }

  private final PlanChangeTiming timing;
  private final Plan targetPlan;
  private final Instant effectiveAt;
  private final Charge charge;

  PlanChangePreview(PlanChangeTiming timing, Plan targetPlan, Instant effectiveAt, Charge charge) {
    this.timing = timing;
    this.targetPlan = targetPlan;
    this.effectiveAt = effectiveAt;
    this.charge = charge;
  }

  /** @return when the change would apply */
  public PlanChangeTiming timing() {
    return timing;
  }

  /** @return the plan asked for */
  public Plan targetPlan() {
    return targetPlan;
  }

  /** @return now for an upgrade, the current period end for a downgrade */
  public Instant effectiveAt() {
    return effectiveAt;
  }

  /** @return the amount charged today in major units; null for a downgrade */
  public BigDecimal amountDue() {
    return charge == null ? null : charge.amountDue();
  }

  /** @return the ISO currency code of {@link #amountDue()}, upper case; null for a downgrade */
  public String currency() {
    return charge == null ? null : charge.currency();
  }

  /** @return the proration instant (epoch seconds) the amount was computed at; null for a downgrade */
  public Long prorationDate() {
    return charge == null ? null : charge.prorationDate();
  }

  /** @return the brand of the card that will be charged ({@code visa}, ...); null when unknown */
  public String cardBrand() {
    return charge == null ? null : charge.cardBrand();
  }

  /** @return the last four digits of that card; null when unknown */
  public String cardLast4() {
    return charge == null ? null : charge.cardLast4();
  }
}
