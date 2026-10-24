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
}
