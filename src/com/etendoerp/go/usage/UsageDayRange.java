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

package com.etendoerp.go.usage;

import java.util.Calendar;
import java.util.Date;

/**
 * Day arithmetic for the settling window, kept in one place so the definition of "a day" is
 * not re-derived at each call site.
 *
 * <p>Days are local-calendar days truncated to midnight, and every range is half-open —
 * {@code [start, end)} — so a row dated exactly midnight belongs to the later day and no row
 * is counted twice across adjacent days.
 */
public final class UsageDayRange {

  private UsageDayRange() {
  }

  /**
   * Truncates an instant to local midnight.
   *
   * @param moment the instant to truncate
   * @return the given instant truncated to local midnight
   */
  public static Date startOfDay(Date moment) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(moment);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTime();
  }

  /**
   * Advances to the following day.
   *
   * @param day the day to advance from
   * @return midnight of the day after the given day; the exclusive end of that day
   */
  public static Date nextDay(Date day) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(startOfDay(day));
    cal.add(Calendar.DAY_OF_MONTH, 1);
    return cal.getTime();
  }

  /**
   * Steps back a whole number of calendar days.
   *
   * @param day the day to step back from
   * @param days how many days to subtract
   * @return the day {@code days} before the given day, truncated to midnight
   */
  public static Date minusDays(Date day, int days) {
    Calendar cal = Calendar.getInstance();
    cal.setTime(startOfDay(day));
    cal.add(Calendar.DAY_OF_MONTH, -days);
    return cal.getTime();
  }

  /**
   * Whether a day has left the settling window and may never be recomputed again.
   *
   * <p>A day is final once strictly more than {@code settlingWindowDays} have elapsed since
   * it, measured in whole calendar days from {@code today}.
   *
   * @param day the day under test
   * @param today start of the current day
   * @param settlingWindowDays how many days a tenant's figures stay open
   * @return true when the day has left the settling window
   */
  public static boolean isFinal(Date day, Date today, int settlingWindowDays) {
    return startOfDay(day).before(minusDays(today, settlingWindowDays));
  }
}
