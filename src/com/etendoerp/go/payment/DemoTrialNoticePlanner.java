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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Decides which trial notice, if any, a demo environment's owner is owed right now (ETP-5453).
 *
 * <p>Pure: no database, no clock. {@link EnvironmentAccessPolicy} stays the single source of truth
 * for when a trial ends, so the email can never announce an expiry the access gate disagrees
 * with.</p>
 *
 * <p>Rules, in order:</p>
 * <ul>
 *   <li>Only a demo with a trial start is notified. A demo associated with a productive environment
 *   never is: a demo is never converted, paying creates a new productive environment, and the
 *   policy cuts the old demo the moment that happens. Telling someone who just paid that their
 *   trial "ended" is the one message this must never send.</li>
 *   <li>An ended trial owes the expiry notice, but only during the first
 *   {@value #EXPIRED_NOTICE_WINDOW_DAYS} days after it ended. That bounds the daily job's work on
 *   old demos and keeps the first run after a deploy from mailing every demo that expired
 *   months ago.</li>
 *   <li>A running trial owes the reminder of the smallest threshold its remaining days fit in. With
 *   thresholds 3 and 1, a trial with 2 days left still owes {@code reminder-3}: a missed run is
 *   caught up the next day instead of being skipped.</li>
 * </ul>
 */
public final class DemoTrialNoticePlanner {

  private static final Logger log = LogManager.getLogger(DemoTrialNoticePlanner.class);

  public static final String REMINDER_DAYS_PROPERTY = "etendo.go.demo.trial.reminder.days";
  public static final String REMINDER_DAYS_ENV = "ETGO_DEMO_TRIAL_REMINDER_DAYS";
  static final List<Integer> DEFAULT_REMINDER_DAYS = List.of(1, 3);
  /** Days after the end of a trial during which the expiry notice may still go out. */
  static final int EXPIRED_NOTICE_WINDOW_DAYS = 3;

  private final EnvironmentAccessPolicy policy = new EnvironmentAccessPolicy();
  private final List<Integer> reminderDays;

  /**
   * Creates a planner with explicit reminder thresholds.
   *
   * @param reminderDays thresholds in days left, in any order; non-positive values are ignored
   */
  public DemoTrialNoticePlanner(List<Integer> reminderDays) {
    TreeSet<Integer> sorted = new TreeSet<>();
    if (reminderDays != null) {
      for (Integer days : reminderDays) {
        if (days != null && days > 0) {
          sorted.add(days);
        }
      }
    }
    this.reminderDays = Collections.unmodifiableList(new ArrayList<>(sorted));
  }

  /**
   * Creates a planner with the thresholds configured for this instance.
   *
   * @return the configured planner
   */
  public static DemoTrialNoticePlanner fromConfiguration() {
    return new DemoTrialNoticePlanner(parseReminderDays(
        GoRuntimeProperties.readValue(REMINDER_DAYS_PROPERTY, REMINDER_DAYS_ENV, null)));
  }

  /**
   * Parses a comma-separated threshold list such as {@code 3,1}.
   *
   * <p>A blank value, or one with no usable number, falls back to the defaults rather than to no
   * reminders at all: a typo in a property must not silently stop every reminder.</p>
   *
   * @param raw the configured value, may be {@code null}
   * @return the thresholds, ascending
   */
  static List<Integer> parseReminderDays(String raw) {
    if (StringUtils.isBlank(raw)) {
      return DEFAULT_REMINDER_DAYS;
    }
    TreeSet<Integer> parsed = new TreeSet<>();
    for (String part : raw.split(",")) {
      try {
        int days = Integer.parseInt(part.trim());
        if (days > 0) {
          parsed.add(days);
        }
      } catch (NumberFormatException e) {
        log.warn("Ignoring invalid demo trial reminder threshold '{}'", part);
      }
    }
    if (parsed.isEmpty()) {
      log.warn("No valid demo trial reminder threshold in '{}'; using {}", raw,
          DEFAULT_REMINDER_DAYS);
      return DEFAULT_REMINDER_DAYS;
    }
    return new ArrayList<>(parsed);
  }

  /** @return the reminder thresholds, ascending */
  public List<Integer> getReminderDays() {
    return reminderDays;
  }

  /**
   * Returns the notice owed for an environment at an instant.
   *
   * @param snapshot the environment's lifecycle state; {@code null} for an unmanaged tenant
   * @param now the evaluation instant
   * @param configuration the configured trial and grace periods
   * @return the owed notice, or empty when nothing is owed
   */
  public Optional<DemoTrialNotice> plan(TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot,
      Instant now, EnvironmentAccessPolicy.Configuration configuration) {
    if (snapshot == null
        || snapshot.getType() != EnvironmentAccessPolicy.EnvironmentType.DEMO
        || snapshot.isAssociatedWithProductive()
        || snapshot.getTrialStartedAt() == null) {
      return Optional.empty();
    }
    EnvironmentAccessPolicy.Environment environment = snapshot.toPolicyEnvironment();
    Instant trialEndsAt = policy.trialExpiresAt(environment, configuration);
    EnvironmentAccessPolicy.Decision decision = policy.evaluate(environment, true,
        snapshot.getSubscriptionStatus(), now, configuration);

    if (decision == EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED) {
      if (!now.isBefore(trialEndsAt.plus(EXPIRED_NOTICE_WINDOW_DAYS, ChronoUnit.DAYS))) {
        return Optional.empty();
      }
      return Optional.of(DemoTrialNotice.expired(snapshot.getTrialStartedAt(), trialEndsAt));
    }
    // Still allowed past the end of the trial means something else grants access; there is no
    // trial left to remind anybody about.
    if (decision != EnvironmentAccessPolicy.Decision.ALLOWED || !now.isBefore(trialEndsAt)) {
      return Optional.empty();
    }
    long remainingDays = policy.remainingTrialDays(environment, now, configuration);
    for (int threshold : reminderDays) {
      if (remainingDays <= threshold) {
        return Optional.of(DemoTrialNotice.reminder(threshold, remainingDays,
            snapshot.getTrialStartedAt(), trialEndsAt));
      }
    }
    return Optional.empty();
  }
}
