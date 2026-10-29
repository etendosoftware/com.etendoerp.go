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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.payment.TestLogCapture;

/**
 * The lag check of the stored usage: a throttled {@code [USAGE-LAG]} WARN when the last successful
 * aggregation run is older than twice the schedule's cadence, or when it never ran.
 *
 * @covers com.etendoerp.go.usage.UsageFreshness
 * @covers com.etendoerp.go.usage.UsageScheduleSettings
 */
@SuppressWarnings("java:S2187")
class UsageFreshnessTest {

  private static final long MINUTE = 60_000L;
  /** A two-hour cadence, so twice the cadence is four hours. */
  private static final long THRESHOLD = 4 * 60 * MINUTE;
  private static final long NOW = 1_800_000_000_000L;

  @Nested
  @DisplayName("checkLag")
  class CheckLag {

    private final AtomicLong clock = new AtomicLong(NOW);
    private final AtomicReference<Date> lastRun = new AtomicReference<>();
    private final AtomicInteger reads = new AtomicInteger();
    private UsageFreshness freshness;
    private TestLogCapture logs;

    @BeforeEach
    void setUp() {
      freshness = new UsageFreshness(clock::get, () -> {
        reads.incrementAndGet();
        return lastRun.get();
      }, () -> THRESHOLD);
      logs = TestLogCapture.attachTo(UsageFreshness.class, Level.WARN);
    }

    @AfterEach
    void tearDown() {
      logs.detach();
    }

    private List<String> warnings() {
      return logs.messagesAt(Level.WARN);
    }

    @Test
    void aRunOlderThanTwiceTheCadenceIsLateAndWarned() {
      lastRun.set(new Date(NOW - THRESHOLD - 1));

      assertTrue(freshness.checkLag());

      assertEquals(1, warnings().size());
      assertTrue(warnings().get(0).startsWith("[USAGE-LAG]"), warnings().get(0));
      assertTrue(warnings().get(0).contains("every 120 min"), warnings().get(0));
    }

    @Test
    void aFreshRunReportsNothing() {
      lastRun.set(new Date(NOW - 30 * MINUTE));

      assertFalse(freshness.checkLag());
      assertTrue(warnings().isEmpty());
    }

    @Test
    void exactlyTwiceTheCadenceIsNotYetLate() {
      lastRun.set(new Date(NOW - THRESHOLD));

      assertFalse(freshness.checkLag());
      assertTrue(warnings().isEmpty());
    }

    @Test
    void aJobThatNeverRanIsLateAndSaysSo() {
      lastRun.set(null);

      assertTrue(freshness.checkLag());

      assertEquals(1, warnings().size());
      assertTrue(warnings().get(0).contains("finished never"), warnings().get(0));
    }

    @Test
    void theWarnIsThrottledToOnceAnHour() {
      lastRun.set(null);

      assertTrue(freshness.checkLag());
      clock.addAndGet(59 * MINUTE);
      assertTrue(freshness.checkLag(), "still late, just not logged again");
      assertEquals(1, warnings().size());

      clock.addAndGet(MINUTE);
      freshness.checkLag();
      assertEquals(2, warnings().size());
    }

    @Test
    void theLastRunIsReadAtMostOnceAMinute() {
      lastRun.set(new Date(NOW));

      freshness.checkLag();
      clock.addAndGet(MINUTE - 1);
      freshness.checkLag();
      assertEquals(1, reads.get());

      clock.addAndGet(1);
      freshness.checkLag();
      assertEquals(2, reads.get());
    }

    @Test
    void aFailedReadIsNotReportedAsLate() {
      UsageFreshness failing = new UsageFreshness(clock::get, () -> {
        throw new IllegalStateException("run log unavailable");
      }, () -> THRESHOLD);

      assertFalse(failing.checkLag());
      assertTrue(warnings().isEmpty());
    }

    /**
     * REPRO (ETP-5051 re-review W4): a failed read may have waited the whole isolated-read timeout.
     * It is cached for the minute like a successful one, so a broken run log costs one attempt a
     * minute, not one per consumption read on the write path.
     */
    @Test
    void aFailedReadIsRetriedAtMostOnceAMinute() {
      AtomicInteger attempts = new AtomicInteger();
      UsageFreshness failing = new UsageFreshness(clock::get, () -> {
        attempts.incrementAndGet();
        throw new IllegalStateException("isolated read timed out");
      }, () -> THRESHOLD);

      assertFalse(failing.checkLag());
      clock.addAndGet(MINUTE - 1);
      assertFalse(failing.checkLag());
      assertEquals(1, attempts.get());

      clock.addAndGet(1);
      assertFalse(failing.checkLag());
      assertEquals(2, attempts.get());
    }
  }

  @Nested
  @DisplayName("the cadence the threshold derives from")
  class Cadence {

    private MockedStatic<OBPropertiesProvider> properties;

    @BeforeEach
    void noOpenbravoProperties() {
      OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
      when(provider.getOpenbravoProperties()).thenReturn(new Properties());
      properties = mockStatic(OBPropertiesProvider.class);
      properties.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    }

    @AfterEach
    void clear() {
      System.clearProperty(UsageScheduleSettings.INTERVAL_MINUTES_PROPERTY);
      properties.close();
    }

    @Test
    void theDefaultCadenceIsTwoHoursSoTheThresholdIsFour() {
      System.clearProperty(UsageScheduleSettings.INTERVAL_MINUTES_PROPERTY);

      assertEquals(120, UsageScheduleSettings.intervalMinutes());
      assertEquals(4 * 60 * MINUTE, UsageScheduleSettings.lagThresholdMs());
    }

    @Test
    void aConfiguredCadenceSetsTheThreshold() {
      System.setProperty(UsageScheduleSettings.INTERVAL_MINUTES_PROPERTY, "30");

      assertEquals(30, UsageScheduleSettings.intervalMinutes());
      assertEquals(60 * MINUTE, UsageScheduleSettings.lagThresholdMs());
    }

    @Test
    void aCadenceBelowFifteenMinutesIsRaisedToFifteen() {
      System.setProperty(UsageScheduleSettings.INTERVAL_MINUTES_PROPERTY, "5");

      assertEquals(15, UsageScheduleSettings.intervalMinutes());
      assertEquals(30 * MINUTE, UsageScheduleSettings.lagThresholdMs());
    }

    @Test
    void anUnreadableCadenceFallsBackToTheDefault() {
      System.setProperty(UsageScheduleSettings.INTERVAL_MINUTES_PROPERTY, "two hours");

      assertEquals(UsageScheduleSettings.DEFAULT_INTERVAL_MINUTES,
          UsageScheduleSettings.intervalMinutes());
    }
  }
}
