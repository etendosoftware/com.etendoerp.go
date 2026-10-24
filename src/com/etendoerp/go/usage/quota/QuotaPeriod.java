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

import java.util.Date;
import java.util.Objects;

import com.etendoerp.go.usage.UsageDayRange;

/**
 * ETP-5051 — the consumption window of a quota: the subscription's current billing period
 * ({@code ETGO_SUBSCRIPTION.CURRENT_PERIOD_START/END}), never the calendar month
 * ({@code open-and-notable-topics.md} §5.5).
 *
 * <p><b>Day alignment.</b> Usage is stored per day, so the window counted is the days from the
 * period's start day up to, not including, its end day: {@code [startOfDay(start),
 * startOfDay(end))}. Consecutive periods share their boundary day exactly once (it belongs to the
 * period that starts on it), and the live count uses the same bounds as the stored aggregate, so
 * the two sources answer about the same days.
 */
public final class QuotaPeriod {

  private final Date start;
  private final Date end;

  private QuotaPeriod(Date start, Date end) {
    this.start = new Date(start.getTime());
    this.end = new Date(end.getTime());
  }

  /**
   * The period in effect at {@code now}, or null when the subscription has no current period —
   * {@code open-and-notable-topics.md} §5.9. There is none when either bound is missing (a row
   * whose provider read failed, an R37 backfill, the pre-ETP-5047 shape with only an end), when
   * the bounds are inverted, when the row is closed (a canceled subscription keeps its last period
   * but no current one), or when {@code now} lies outside {@code [start, end)} (a renewal not yet
   * recorded).
   *
   * @param start {@code CURRENT_PERIOD_START}
   * @param end {@code CURRENT_PERIOD_END}
   * @param closed whether the subscription row is closed ({@code END_DATE} set)
   * @param now the evaluation instant
   * @return the current period, or null
   */
  public static QuotaPeriod current(Date start, Date end, boolean closed, Date now) {
    if (closed || start == null || end == null || now == null || !start.before(end)) {
      return null;
    }
    if (now.before(start) || !now.before(end)) {
      return null;
    }
    return new QuotaPeriod(start, end);
  }

  /** @return the exact period start; a defensive copy */
  public Date getStart() {
    return new Date(start.getTime());
  }

  /** @return the exact period end (exclusive); a defensive copy */
  public Date getEnd() {
    return new Date(end.getTime());
  }

  /** @return the first counted day, inclusive: the start of the period's start day */
  public Date getWindowFrom() {
    return UsageDayRange.startOfDay(start);
  }

  /** @return the end of the counted days, exclusive: the start of the period's end day */
  public Date getWindowTo() {
    return UsageDayRange.startOfDay(end);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof QuotaPeriod)) {
      return false;
    }
    QuotaPeriod that = (QuotaPeriod) other;
    return start.equals(that.start) && end.equals(that.end);
  }

  @Override
  public int hashCode() {
    return Objects.hash(start, end);
  }

  @Override
  public String toString() {
    return "QuotaPeriod[" + start.toInstant() + " .. " + end.toInstant() + ')';
  }
}
