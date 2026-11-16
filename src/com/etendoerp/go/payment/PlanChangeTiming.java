/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

/**
 * ETP-5053 — when an allowed plan change applies: the {@code TIMING} column of
 * {@code ETGO_PLAN_CHANGE}, spelled as the {@code ETGO_PLNCHG_TIMING_CHK} constraint and the
 * {@code ETGO_PlanChangeTiming} list store it.
 *
 * <p>The direction of a change is whatever the configuration row says. Nothing in the module
 * infers it from the two plans' prices or names, so a mixed change (a cheaper annual plan from a
 * dearer monthly one, say) is settled explicitly by the operator who writes the row.
 */
public enum PlanChangeTiming {
  /** Applied now: an upgrade, or a monthly-to-annual change. The billing date does not move. */
  IMMEDIATE("immediate"),
  /** Applied at the end of the current billing period: a downgrade, scheduled at the provider. */
  PERIOD_END("period_end");

  private final String value;

  PlanChangeTiming(String value) {
    this.value = value;
  }

  /** @return the stored spelling */
  public String value() {
    return value;
  }

  /**
   * Parses a stored value.
   *
   * @param stored the {@code TIMING} column, may be null
   * @return the timing, or empty for null, blank or an unknown spelling
   */
  public static Optional<PlanChangeTiming> of(String stored) {
    String wanted = StringUtils.trimToEmpty(stored);
    for (PlanChangeTiming timing : values()) {
      if (timing.value.equals(wanted)) {
        return Optional.of(timing);
      }
    }
    return Optional.empty();
  }
}
