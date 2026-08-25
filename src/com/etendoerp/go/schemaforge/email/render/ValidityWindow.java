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



package com.etendoerp.go.schemaforge.email.render;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How long a link in an email stays valid (ETP-5003).
 *
 * <p>Every email that carries an expiring link states the window in its copy, and the number has to
 * come from the record that governs it. A literal in the copy is how the invitation email came to
 * promise 24 hours for a token that lives seven days.</p>
 */
public final class ValidityWindow {

  private static final long MINUTES_PER_DAY = 1440;
  private static final long MINUTES_PER_HOUR = 60;
  private static final long SECONDS_PER_MINUTE = 60;

  /** The unit an email states its validity window in. */
  public enum Unit {
    /** Short-lived links, such as a password reset. */
    MINUTES,
    /** Day-scale links, such as email verification. */
    HOURS,
    /** Long-lived links, such as an invitation. */
    DAYS
  }

  /**
   * Whole units from now until an expiry instant, rounded up.
   *
   * @param unit the unit the email's copy is written in
   * @param now the reference instant
   * @param expiresAt the expiry instant, may be {@code null}
   * @return whole units remaining, never below one
   */
  public static long until(Unit unit, Instant now, Instant expiresAt) {
    switch (unit) {
      case HOURS:
        return hoursUntil(now, expiresAt);
      case DAYS:
        return daysUntil(now, expiresAt);
      default:
        return minutesUntil(now, expiresAt);
    }
  }

  /**
   * Whole hours from now until an expiry instant, rounded up.
   *
   * @param now the reference instant
   * @param expiresAt the expiry instant, may be {@code null}
   * @return whole hours remaining, never below one
   */
  public static long hoursUntil(Instant now, Instant expiresAt) {
    if (expiresAt == null) {
      return 1;
    }
    long minutes = ChronoUnit.MINUTES.between(now, expiresAt);
    return Math.max(1, (long) Math.ceil(minutes / (double) MINUTES_PER_HOUR));
  }

  private ValidityWindow() {
  }

  /**
   * Whole minutes from now until an expiry instant, rounded up.
   *
   * <p>Short-lived links state their window in minutes: the password-reset token lives half an
   * hour, and "valid for 1 day" would be both wrong and reassuring in the wrong direction.</p>
   *
   * @param now the reference instant
   * @param expiresAt the expiry instant, may be {@code null}
   * @return whole minutes remaining, never below one
   */
  public static long minutesUntil(Instant now, Instant expiresAt) {
    if (expiresAt == null) {
      return 1;
    }
    long seconds = ChronoUnit.SECONDS.between(now, expiresAt);
    return Math.max(1, (long) Math.ceil(seconds / (double) SECONDS_PER_MINUTE));
  }

  /**
   * Whole days from now until an expiry instant, rounded up.
   *
   * <p>Rounded rather than truncated on purpose: an expiry is stamped when the record is created
   * and read milliseconds later, so the exact difference is 6.999... days and truncation would
   * describe a seven-day link as valid for six.</p>
   *
   * @param now the reference instant
   * @param expiresAt the expiry instant, may be {@code null}
   * @return whole days remaining, never below one
   */
  public static long daysUntil(Instant now, Instant expiresAt) {
    if (expiresAt == null) {
      return 1;
    }
    long minutes = ChronoUnit.MINUTES.between(now, expiresAt);
    return Math.max(1, (long) Math.ceil(minutes / (double) MINUTES_PER_DAY));
  }
}
