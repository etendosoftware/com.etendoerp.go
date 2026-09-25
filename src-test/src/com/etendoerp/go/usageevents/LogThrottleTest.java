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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Unit specs for {@link LogThrottle} (ETP-5462). The clock is injected, so "at most one line per
 * interval" is asserted exactly, without sleeping.
 */
class LogThrottleTest {

  private static final long INTERVAL = 60_000L;
  private static final long T0 = 1_000_000L;

  @Test
  void firstOccurrenceIsDueThenOnlyOncePerInterval() {
    AtomicLong clock = new AtomicLong(T0);
    LogThrottle throttle = new LogThrottle(INTERVAL, clock::get);

    assertTrue(throttle.note(1), "first occurrence must log immediately");
    assertFalse(throttle.note(1));
    clock.addAndGet(INTERVAL - 1);
    assertFalse(throttle.note(1), "one millisecond before the interval is still throttled");
    clock.addAndGet(1);
    assertTrue(throttle.note(1), "exactly one interval later a new line is due");
    assertFalse(throttle.note(1));
  }

  @Test
  void countIsExactWhetherOrNotALineIsDue() {
    LogThrottle throttle = new LogThrottle(INTERVAL, () -> T0);
    throttle.note(3);
    throttle.note(2);
    throttle.add(5);
    assertEquals(10L, throttle.total());
  }

  @Test
  void addNeverConsumesTheNextLine() {
    LogThrottle throttle = new LogThrottle(INTERVAL, () -> T0);
    throttle.add(7);
    assertTrue(throttle.note(1), "add() must not count as a logged line");
    assertEquals(8L, throttle.total());
  }

  @Test
  void concurrentCallersProduceExactlyOneLine() throws Exception {
    LogThrottle throttle = new LogThrottle(INTERVAL, () -> T0);
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(pool.submit(() -> {
          go.await();
          return throttle.note(1);
        }));
      }
      go.countDown();
      int winners = 0;
      for (Future<Boolean> result : results) {
        if (result.get(10, TimeUnit.SECONDS)) {
          winners++;
        }
      }
      assertEquals(1, winners);
      assertEquals(threads, throttle.total());
    } finally {
      pool.shutdownNow();
    }
  }
}
