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

import java.util.Collection;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.usage.DailyCount;

/**
 * ETP-5051 — how the daily values of a period combine into one consumed quantity,
 * {@code ETGO_BILLING_RESOURCE.AGGREGATION} ({@code open-and-notable-topics.md} §5.6).
 *
 * <p>A property of what is measured, not of the quota: a flow (posted invoices) adds its days, a
 * stock (active users, a daily snapshot) must not — five users every day for thirty days is five
 * users, not 150. An omitted day counts as zero ({@code UsageResourceCounter}), so {@link #MAX} and
 * {@link #LAST} stay right over a period that began before the measurement was enabled.
 */
public enum UsageAggregation {
  /** Adds the days: a flow. */
  SUM("sum"),
  /** The highest day: a stock, peak semantics. */
  MAX("max"),
  /** The most recent day that has a stored value: a stock, current-level semantics. */
  LAST("last");

  private final String value;

  UsageAggregation(String value) {
    this.value = value;
  }

  /** @return the stored value, as the check constraint spells it */
  public String value() {
    return value;
  }

  /**
   * Reads the stored column; null, blank or unknown means {@link #SUM}, the column default.
   *
   * @param raw the stored {@code AGGREGATION}
   * @return the aggregation to apply
   */
  public static UsageAggregation fromColumn(String raw) {
    String normalised = StringUtils.trimToEmpty(raw);
    for (UsageAggregation aggregation : values()) {
      if (aggregation.value.equalsIgnoreCase(normalised)) {
        return aggregation;
      }
    }
    return SUM;
  }

  /**
   * Combines daily values. Absent days are zero, so an empty input is zero for every aggregation.
   * {@link #LAST} takes the value of the latest {@link DailyCount#getDay() day}; when the same day
   * appears more than once the later entry wins.
   *
   * @param days the daily values of one tenant and one resource over the period
   * @return the combined quantity
   */
  public long combine(Collection<DailyCount> days) {
    if (days == null || days.isEmpty()) {
      return 0L;
    }
    switch (this) {
      case MAX:
        return days.stream().mapToLong(DailyCount::getQuantity).max().orElse(0L);
      case LAST:
        return last(days);
      case SUM:
      default:
        return days.stream().mapToLong(DailyCount::getQuantity).sum();
    }
  }

  private static long last(Collection<DailyCount> days) {
    Date latest = null;
    long value = 0L;
    for (DailyCount day : days) {
      Date current = day.getDay();
      if (latest == null || !current.before(latest)) {
        latest = current;
        value = day.getQuantity();
      }
    }
    return value;
  }
}
