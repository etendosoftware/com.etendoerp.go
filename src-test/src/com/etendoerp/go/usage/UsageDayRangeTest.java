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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit specs for {@link UsageDayRange} (ETP-5050).
 *
 * <p>Pure local-calendar arithmetic over static methods: no database and no mock, so these run
 * in the plain test JVM.
 *
 * <p>Two contracts are load-bearing and are what the assertions below pin down.
 *
 * <p><b>Finality.</b> A day that has left the settling window is never recomputed again, so the
 * boundary decides whether a value can still be revised. The rule is
 * {@code startOfDay(day) < today - window}: the day exactly {@code today - window} is still
 * <em>open</em>, and only {@code today - window - 1} is final. Getting that off by one in the
 * permissive direction would freeze a day that was still supposed to change (a reported value
 * silently stuck); in the strict direction it would recompute forever. The tests therefore
 * always assert the pair — the last open day AND the first final day — because asserting only
 * one side passes under an off-by-one.
 *
 * <p><b>Half-open ranges.</b> A day is {@code [startOfDay(d), nextDay(d))}. A row timestamped
 * exactly midnight must belong to the LATER day and to exactly one day, or adjacent days would
 * double-count it. The test states that as an identity between the two functions rather than as
 * a millisecond difference, so it still holds across a DST transition, where a calendar day is
 * not 24 hours.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class UsageDayRangeTest {

  private static Date at(int year, int month, int dayOfMonth, int hour, int minute, int second,
      int millis) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth, hour, minute, second);
    cal.set(Calendar.MILLISECOND, millis);
    return cal.getTime();
  }

  private static Date midnight(int year, int month, int dayOfMonth) {
    return at(year, month, dayOfMonth, 0, 0, 0, 0);
  }

  @Nested
  @DisplayName("isFinal")
  class IsFinal {

    /**
     * The boundary itself. {@code today - window} is the OLDEST day still inside the window, so
     * it must not be final; one day earlier must be. Asserted together for each window so an
     * off-by-one in either direction fails.
     */
    @ParameterizedTest(name = "window = {0} day(s)")
    @ValueSource(ints = { 0, 1, 5 })
    void theDayExactlyAtTheWindowEdgeIsStillOpenAndTheOneBeforeItIsFinal(int window) {
      Date today = midnight(2026, 6, 15);
      Date edge = UsageDayRange.minusDays(today, window);
      Date beforeEdge = UsageDayRange.minusDays(today, window + 1);

      assertAll(
          () -> assertFalse(UsageDayRange.isFinal(edge, today, window),
              "the day exactly today-window is the oldest day still inside the window"),
          () -> assertTrue(UsageDayRange.isFinal(beforeEdge, today, window),
              "the day today-window-1 has left the window and may never be recomputed"));
    }

    @ParameterizedTest(name = "window = {0} day(s)")
    @ValueSource(ints = { 0, 1, 5 })
    void todayIsNeverFinal(int window) {
      Date today = midnight(2026, 6, 15);
      assertFalse(UsageDayRange.isFinal(today, today, window));
    }

    @ParameterizedTest(name = "window = {0} day(s)")
    @ValueSource(ints = { 0, 1, 5 })
    void aFutureDayIsNeverFinal(int window) {
      Date today = midnight(2026, 6, 15);
      assertFalse(UsageDayRange.isFinal(midnight(2026, 6, 16), today, window));
    }

    /**
     * Finality is decided on the calendar day, not on the instant: a day handed in with a time
     * component must classify exactly as its midnight does, or the answer would depend on what
     * time of day the job happened to read the row.
     */
    @Test
    void theTimeOfDayCarriedByTheArgumentsDoesNotChangeTheAnswer() {
      Date todayNoon = at(2026, 6, 15, 12, 30, 0, 0);
      Date edgeLateEvening = at(2026, 6, 10, 23, 59, 59, 999);
      Date beforeEdgeLateEvening = at(2026, 6, 9, 23, 59, 59, 999);

      assertAll(() -> assertFalse(UsageDayRange.isFinal(edgeLateEvening, todayNoon, 5)),
          () -> assertTrue(UsageDayRange.isFinal(beforeEdgeLateEvening, todayNoon, 5)));
    }

    /**
     * A zero window means only today is open. Spelled out separately from the parameterized case
     * because it is the configuration a tenant would use to freeze usage as soon as the day ends,
     * and it is the one where an off-by-one would make every day final.
     */
    @Test
    void aZeroWindowLeavesOnlyTodayOpen() {
      Date today = midnight(2026, 6, 15);
      assertAll(() -> assertFalse(UsageDayRange.isFinal(today, today, 0)),
          () -> assertTrue(UsageDayRange.isFinal(midnight(2026, 6, 14), today, 0)));
    }
  }

  @Nested
  @DisplayName("startOfDay")
  class StartOfDay {

    @Test
    void truncatesEveryTimeComponentToMidnight() {
      assertEquals(midnight(2026, 6, 15),
          UsageDayRange.startOfDay(at(2026, 6, 15, 23, 59, 59, 999)));
    }

    @Test
    void isIdempotent() {
      Date once = UsageDayRange.startOfDay(at(2026, 6, 15, 8, 15, 30, 250));
      assertEquals(once, UsageDayRange.startOfDay(once));
    }

    @Test
    void doesNotMutateItsArgument() {
      Date moment = at(2026, 6, 15, 8, 15, 30, 250);
      long before = moment.getTime();
      UsageDayRange.startOfDay(moment);
      assertEquals(before, moment.getTime(), "Date is mutable; the helper must not modify it");
    }
  }

  @Nested
  @DisplayName("nextDay and minusDays")
  class DayArithmetic {

    @Test
    void nextDayIsMidnightOfTheFollowingCalendarDay() {
      assertEquals(midnight(2026, 6, 16),
          UsageDayRange.nextDay(at(2026, 6, 15, 17, 45, 0, 0)));
    }

    @Test
    void nextDayCrossesAMonthBoundary() {
      assertEquals(midnight(2026, 7, 1), UsageDayRange.nextDay(midnight(2026, 6, 30)));
    }

    @Test
    void minusDaysCrossesAMonthBoundaryAndTruncates() {
      assertEquals(midnight(2026, 6, 28),
          UsageDayRange.minusDays(at(2026, 7, 2, 9, 0, 0, 0), 4));
    }

    @Test
    void minusZeroIsJustTheTruncatedDay() {
      assertEquals(midnight(2026, 6, 15),
          UsageDayRange.minusDays(at(2026, 6, 15, 9, 0, 0, 0), 0));
    }
  }

  @Nested
  @DisplayName("half-open ranges")
  class HalfOpenRanges {

    /**
     * The exclusive end of one day IS the inclusive start of the next. Stated as an identity
     * between {@code nextDay} and {@code startOfDay} rather than as "+86400000 ms", so it holds
     * on a DST day too, where the calendar day is 23 or 25 hours long.
     */
    @Test
    void aRowAtExactlyMidnightBelongsToTheLaterDay() {
      Date day = midnight(2026, 6, 15);
      Date boundary = UsageDayRange.nextDay(day);

      assertAll(
          () -> assertEquals(UsageDayRange.startOfDay(boundary), boundary,
              "the boundary instant is itself the start of the later day"),
          () -> assertFalse(boundary.before(UsageDayRange.startOfDay(boundary)),
              "so a row at the boundary is inside [start, end) of the LATER day"),
          () -> assertFalse(boundary.before(UsageDayRange.nextDay(day)),
              "and outside [start, end) of the earlier day, which ends exclusively here"));
    }

    /**
     * Adjacent days must abut exactly: no gap (a row would be counted by nobody) and no overlap
     * (a row would be counted twice, inflating a billed figure).
     */
    @Test
    void consecutiveDaysAbutWithoutGapOrOverlap() {
      Date day = midnight(2026, 6, 15);
      Date next = UsageDayRange.nextDay(day);
      assertEquals(next, UsageDayRange.startOfDay(next));
      assertEquals(day, UsageDayRange.minusDays(next, 1));
    }
  }
}
