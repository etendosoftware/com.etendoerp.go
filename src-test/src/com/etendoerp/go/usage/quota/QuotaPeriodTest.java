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

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.local;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.midnight;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;

import org.junit.jupiter.api.Test;

/**
 * The current billing period of a subscription: half-open {@code [start, end)}, absent when the
 * row has none, and counted over whole local days from the start day to the end day (exclusive).
 * And the yearly quota window: the subscription year, from an anniversary of the start date to
 * the next, that contains the evaluation instant.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaPeriod
 */
class QuotaPeriodTest {

  @Test
  void aPeriodIsCurrentFromItsStartInclusive() {
    assertNotNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, false, PERIOD_START));
    assertNotNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW));
  }

  @Test
  void aPeriodIsNoLongerCurrentAtItsEndExclusive() {
    assertNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, false, PERIOD_END));
    assertNotNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, false,
        new Date(PERIOD_END.getTime() - 1L)));
  }

  @Test
  void nowBeforeTheStartHasNoCurrentPeriod() {
    assertNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, false,
        new Date(PERIOD_START.getTime() - 1L)));
  }

  @Test
  void missingOrInvertedBoundsHaveNoCurrentPeriod() {
    assertNull(QuotaPeriod.current(null, PERIOD_END, false, NOW));
    assertNull(QuotaPeriod.current(PERIOD_START, null, false, NOW));
    assertNull(QuotaPeriod.current(PERIOD_END, PERIOD_START, false, NOW));
    assertNull(QuotaPeriod.current(PERIOD_START, PERIOD_START, false, PERIOD_START));
    assertNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, false, null));
  }

  @Test
  void aClosedRowHasNoCurrentPeriodEvenInsideItsBounds() {
    assertNull(QuotaPeriod.current(PERIOD_START, PERIOD_END, true, NOW));
  }

  @Test
  void theCountedWindowRunsFromTheStartDayToTheEndDayNotTheCalendarMonth() {
    QuotaPeriod period = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);

    // 09:30 on the 17th: the window opens at local midnight of the 17th, not on the 1st.
    assertEquals(midnight(2026, 10, 17), period.getWindowFrom());
    // ...and closes at midnight of the end day, exclusive, so the 17th of November is not counted.
    assertEquals(midnight(2026, 11, 17), period.getWindowTo());
    assertEquals(PERIOD_START, period.getStart());
    assertEquals(PERIOD_END, period.getEnd());
  }

  @Test
  void theBoundsAreDefensiveCopies() {
    Date start = new Date(PERIOD_START.getTime());
    QuotaPeriod period = QuotaPeriod.current(start, PERIOD_END, false, NOW);

    start.setTime(0L);
    period.getStart().setTime(0L);
    period.getEnd().setTime(0L);

    assertEquals(PERIOD_START, period.getStart());
    assertEquals(PERIOD_END, period.getEnd());
  }

  @Test
  void twoPeriodsWithTheSameBoundsAreEqual() {
    QuotaPeriod one = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);
    QuotaPeriod two = QuotaPeriod.current(PERIOD_START, PERIOD_END, false,
        local(2026, 11, 1, 0, 0));
    QuotaPeriod other = QuotaPeriod.current(PERIOD_START, local(2026, 11, 18, 9, 30), false, NOW);

    assertEquals(one, two);
    assertEquals(one.hashCode(), two.hashCode());
    assertNotEquals(one, other);
    assertNotEquals(one, "not a period");
    // Written half-open, like the period itself.
    assertEquals("QuotaPeriod[" + PERIOD_START.toInstant() + " .. " + PERIOD_END.toInstant()
        + ')', one.toString());
  }

  // ─── yearContaining: the subscription year (ETP-5051) ──────────────────────

  /** The subscription start: 15 March 2025, 10:00 local. */
  private static final Date ANCHOR = local(2025, 3, 15, 10, 0);

  private static void assertYear(Date expectedStart, Date expectedEnd, QuotaPeriod year) {
    assertNotNull(year);
    assertEquals(expectedStart, year.getStart());
    assertEquals(expectedEnd, year.getEnd());
  }

  @Test
  void theFirstYearStartsAtTheSubscriptionStart() {
    assertYear(ANCHOR, local(2026, 3, 15, 10, 0), QuotaPeriod.yearContaining(ANCHOR, ANCHOR));
    assertYear(ANCHOR, local(2026, 3, 15, 10, 0),
        QuotaPeriod.yearContaining(ANCHOR, local(2025, 12, 31, 23, 59)));
  }

  @Test
  void beforeThisCalendarYearsAnniversaryTheYearStartedLastYear() {
    assertYear(ANCHOR, local(2026, 3, 15, 10, 0),
        QuotaPeriod.yearContaining(ANCHOR, local(2026, 2, 1, 12, 0)));
  }

  @Test
  void afterThisCalendarYearsAnniversaryTheYearStartedThisYear() {
    assertYear(local(2026, 3, 15, 10, 0), local(2027, 3, 15, 10, 0),
        QuotaPeriod.yearContaining(ANCHOR, local(2026, 5, 1, 12, 0)));
  }

  @Test
  void theAnniversaryInstantOpensTheNextYearAndTheInstantBeforeItClosesThePrevious() {
    Date anniversary = local(2026, 3, 15, 10, 0);

    assertYear(anniversary, local(2027, 3, 15, 10, 0),
        QuotaPeriod.yearContaining(ANCHOR, anniversary));
    assertYear(ANCHOR, anniversary,
        QuotaPeriod.yearContaining(ANCHOR, new Date(anniversary.getTime() - 1L)));
  }

  @Test
  void theYearIsCountedFromTheAnniversaryDayToTheNextOneExclusive() {
    QuotaPeriod year = QuotaPeriod.yearContaining(ANCHOR, local(2026, 5, 1, 12, 0));

    assertEquals(midnight(2026, 3, 15), year.getWindowFrom());
    assertEquals(midnight(2027, 3, 15), year.getWindowTo());
  }

  @Test
  void aLeapDayAnchorHasItsAnniversaryOnTheTwentyEighthOfANonLeapYear() {
    Date leapDay = local(2024, 2, 29, 12, 0);

    // Past the 2025 anniversary: the year runs 28 Feb 2025 to 28 Feb 2026.
    assertYear(local(2025, 2, 28, 12, 0), local(2026, 2, 28, 12, 0),
        QuotaPeriod.yearContaining(leapDay, local(2025, 3, 1, 12, 0)));
    // In a leap year the anniversary is the 29th again.
    assertYear(local(2028, 2, 29, 12, 0), local(2029, 2, 28, 12, 0),
        QuotaPeriod.yearContaining(leapDay, local(2028, 3, 1, 12, 0)));
  }

  @Test
  void theFirstYearOfALeapDayAnchorStartsAtTheAnchorNotOnTheClampedAnniversary() {
    Date leapDay = local(2024, 2, 29, 12, 0);

    // Before the 2025 anniversary (28 Feb 12:00): still the first year, which starts at the anchor.
    assertYear(leapDay, local(2025, 2, 28, 12, 0),
        QuotaPeriod.yearContaining(leapDay, local(2025, 2, 28, 11, 0)));
  }

  @Test
  void theYearBeforeALeapAnniversaryEndsOnTheTwentyNinthAndContainsNow() {
    Date leapDay = local(2024, 2, 29, 12, 0);

    // 28 Feb 2028 13:00 is past the 2027 anniversary and before the 2028 one (29 Feb 12:00).
    assertYear(local(2027, 2, 28, 12, 0), local(2028, 2, 29, 12, 0),
        QuotaPeriod.yearContaining(leapDay, local(2028, 2, 28, 13, 0)));
  }

  @Test
  void noAnchorNoInstantOrAnInstantBeforeTheStartHasNoYear() {
    assertNull(QuotaPeriod.yearContaining(null, NOW));
    assertNull(QuotaPeriod.yearContaining(ANCHOR, null));
    assertNull(QuotaPeriod.yearContaining(ANCHOR, new Date(ANCHOR.getTime() - 1L)));
  }
}
