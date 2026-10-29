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

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.midnight;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.etendoerp.go.usage.DailyCount;

/**
 * How a period's daily usage rows combine into one consumption: {@code sum} for a flow,
 * {@code max} for a peak, {@code last} for the current level of a stock.
 *
 * @covers com.etendoerp.go.usage.quota.UsageAggregation
 */
class UsageAggregationTest {

  /** Deliberately out of day order, so LAST cannot pass by reading the last element. */
  private static final List<DailyCount> DAYS = Arrays.asList(
      new DailyCount(CLIENT, midnight(2026, 10, 19), 4L),
      new DailyCount(CLIENT, midnight(2026, 10, 17), 10L),
      new DailyCount(CLIENT, midnight(2026, 10, 21), 3L),
      new DailyCount(CLIENT, midnight(2026, 10, 18), 7L));

  @Test
  void sumAddsTheDays() {
    assertEquals(24L, UsageAggregation.SUM.combine(DAYS));
  }

  @Test
  void maxTakesThePeakDay() {
    assertEquals(10L, UsageAggregation.MAX.combine(DAYS));
  }

  @Test
  void lastTakesTheLatestDayWhateverTheOrder() {
    assertEquals(3L, UsageAggregation.LAST.combine(DAYS));
  }

  @Test
  void lastPrefersTheLaterEntryForTheSameDay() {
    List<DailyCount> days = Arrays.asList(
        new DailyCount(CLIENT, midnight(2026, 10, 21), 3L),
        new DailyCount(CLIENT, midnight(2026, 10, 21), 9L),
        new DailyCount(CLIENT, midnight(2026, 10, 20), 1L));

    assertEquals(9L, UsageAggregation.LAST.combine(days));
  }

  @ParameterizedTest
  @EnumSource(UsageAggregation.class)
  void noDaysIsZero(UsageAggregation aggregation) {
    assertEquals(0L, aggregation.combine(Collections.emptyList()));
    assertEquals(0L, aggregation.combine(null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "max", "MAX", " Max " })
  void theStoredColumnIsReadCaseInsensitively(String raw) {
    assertEquals(UsageAggregation.MAX, UsageAggregation.fromColumn(raw));
  }

  @Test
  void lastIsReadFromItsColumn() {
    assertEquals(UsageAggregation.LAST, UsageAggregation.fromColumn("last"));
    assertEquals("last", UsageAggregation.LAST.value());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "  ", "avg" })
  void blankOrUnknownReadsAsTheSumDefault(String raw) {
    assertEquals(UsageAggregation.SUM, UsageAggregation.fromColumn(raw));
  }
}
