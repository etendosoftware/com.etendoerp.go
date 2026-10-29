/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage.quota;

/**
 * ETP-5051 — the evaluator's answer for one quota, with the numbers behind it. One object per
 * quota, rendered as the contract's {@code QuotaItem} by {@link QuotaItemJson}.
 *
 * <p>{@link #getConsumed()} is null when nothing was measured (no current period, or a quota in mode
 * {@code off} while enforcing); {@link #getVerdict()} is null when nothing was evaluated (mode
 * {@code off}, or no current period).
 */
public final class QuotaEvaluation {

  private final QuotaDefinition quota;
  private final String planInterval;
  private final QuotaPeriod period;
  private final Long consumed;
  private final QuotaVerdict verdict;

  /**
   * Creates one evaluation.
   *
   * @param quota the quota evaluated
   * @param planInterval the plan's billing interval, or null
   * @param period the current period, or null when there is none
   * @param consumed the consumption over the period, or null when not measured
   * @param verdict the verdict, or null when not evaluated
   */
  public QuotaEvaluation(QuotaDefinition quota, String planInterval, QuotaPeriod period,
      Long consumed, QuotaVerdict verdict) {
    this.quota = quota;
    this.planInterval = planInterval;
    this.period = period;
    this.consumed = consumed;
    this.verdict = verdict;
  }

  public QuotaDefinition getQuota() {
    return quota;
  }

  public String getPlanInterval() {
    return planInterval;
  }

  /** @return the current period, or null */
  public QuotaPeriod getPeriod() {
    return period;
  }

  /** @return the consumption, or null when not measured */
  public Long getConsumed() {
    return consumed;
  }

  /** @return the verdict, or null when not evaluated */
  public QuotaVerdict getVerdict() {
    return verdict;
  }

  /** @return whether the verdict is WARN or DENY: something to report */
  public boolean isReportable() {
    return verdict == QuotaVerdict.WARN || verdict == QuotaVerdict.DENY;
  }

  @Override
  public String toString() {
    return "QuotaEvaluation[" + quota.getResourceKey() + ' ' + consumed + '/'
        + quota.getIncludedQty() + ' ' + quota.getEnforcementMode() + " -> " + verdict + ']';
  }
}
