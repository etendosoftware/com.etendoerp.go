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

package com.etendoerp.go.payment;

import java.time.Instant;

/**
 * One notice owed to the owner of a demo environment about the end of its trial (ETP-5453).
 *
 * <p>Produced by {@link DemoTrialNoticePlanner} and delivered through the email contract named
 * {@link #CONTRACT_NAME}. A notice is identified by its trial start and its {@link #milestone()}:
 * that pair is what the delivery is de-duplicated on, so a re-run never repeats a notice while a
 * trial restarted with a new start instant may be notified again.</p>
 */
public final class DemoTrialNotice {

  /** Name of the transactional email contract that delivers these notices. */
  public static final String CONTRACT_NAME = "demo-trial-notice";

  /** What the notice tells the owner. */
  public enum Kind {
    /** The trial ends in a few days. */
    REMINDER,
    /** The trial has ended and access is paused. */
    EXPIRED
  }

  private final Kind kind;
  private final int thresholdDays;
  private final long remainingDays;
  private final Instant trialStartedAt;
  private final Instant trialEndsAt;

  private DemoTrialNotice(Kind kind, int thresholdDays, long remainingDays, Instant trialStartedAt,
      Instant trialEndsAt) {
    this.kind = kind;
    this.thresholdDays = thresholdDays;
    this.remainingDays = remainingDays;
    this.trialStartedAt = trialStartedAt;
    this.trialEndsAt = trialEndsAt;
  }

  /**
   * Creates a reminder for a trial that is still running.
   *
   * @param thresholdDays the configured threshold this reminder belongs to, such as 3
   * @param remainingDays whole days left, rounded up as the environment picker shows them
   * @param trialStartedAt the trial start instant
   * @param trialEndsAt the trial end instant
   * @return the reminder
   */
  public static DemoTrialNotice reminder(int thresholdDays, long remainingDays,
      Instant trialStartedAt, Instant trialEndsAt) {
    return new DemoTrialNotice(Kind.REMINDER, thresholdDays, remainingDays, trialStartedAt,
        trialEndsAt);
  }

  /**
   * Creates the notice for a trial that has ended.
   *
   * @param trialStartedAt the trial start instant
   * @param trialEndsAt the trial end instant
   * @return the expiry notice
   */
  public static DemoTrialNotice expired(Instant trialStartedAt, Instant trialEndsAt) {
    return new DemoTrialNotice(Kind.EXPIRED, 0, 0, trialStartedAt, trialEndsAt);
  }

  public Kind getKind() {
    return kind;
  }

  /** @return the reminder threshold in days; {@code 0} for an expiry notice */
  public int getThresholdDays() {
    return thresholdDays;
  }

  /** @return whole days left in the trial; {@code 0} for an expiry notice */
  public long getRemainingDays() {
    return remainingDays;
  }

  public Instant getTrialStartedAt() {
    return trialStartedAt;
  }

  public Instant getTrialEndsAt() {
    return trialEndsAt;
  }

  /**
   * Stable name of the notice within one trial, such as {@code reminder-3} or {@code expired}.
   *
   * <p>A reminder is named after its threshold, not after the days actually left: when a run is
   * missed, the next one still finds the same reminder owed and must recognise it as already sent
   * if it was.</p>
   *
   * @return the milestone name
   */
  public String milestone() {
    return kind == Kind.EXPIRED ? "expired" : "reminder-" + thresholdDays;
  }
}
