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

  private final PlanChangeTiming timing;
  private final Plan targetPlan;
  private final Instant effectiveAt;
  private final BigDecimal amountDue;
  private final String currency;
  private final Long prorationDate;
  private final String cardBrand;
  private final String cardLast4;

  PlanChangePreview(PlanChangeTiming timing, Plan targetPlan, Instant effectiveAt,
      BigDecimal amountDue, String currency, Long prorationDate, String cardBrand,
      String cardLast4) {
    this.timing = timing;
    this.targetPlan = targetPlan;
    this.effectiveAt = effectiveAt;
    this.amountDue = amountDue;
    this.currency = currency;
    this.prorationDate = prorationDate;
    this.cardBrand = cardBrand;
    this.cardLast4 = cardLast4;
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
    return amountDue;
  }

  /** @return the ISO currency code of {@link #amountDue()}, upper case; null for a downgrade */
  public String currency() {
    return currency;
  }

  /** @return the proration instant (epoch seconds) the amount was computed at; null for a downgrade */
  public Long prorationDate() {
    return prorationDate;
  }

  /** @return the brand of the card that will be charged ({@code visa}, ...); null when unknown */
  public String cardBrand() {
    return cardBrand;
  }

  /** @return the last four digits of that card; null when unknown */
  public String cardLast4() {
    return cardLast4;
  }
}
