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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Counts occurrences of one kind of loss and says when the next log line is due: the first
 * occurrence immediately, then at most one per interval. Under sustained overload an unthrottled
 * warning would become the noisiest line in the log; a silent one would hide the loss. This keeps
 * both the count exact and the log readable.
 */
final class LogThrottle {

  private final long intervalMs;
  private final LongSupplier clock;
  private final AtomicLong count = new AtomicLong();
  /** Epoch millis of the last line; 0 until the first one. */
  private final AtomicLong lastLogAt = new AtomicLong();

  /**
   * @param intervalMs minimum gap between two log lines
   * @param clock      epoch-millis source; a test seam, {@code System::currentTimeMillis} otherwise
   */
  LogThrottle(long intervalMs, LongSupplier clock) {
    this.intervalMs = intervalMs;
    this.clock = clock;
  }

  /**
   * Count {@code n} occurrences.
   *
   * @return true when the caller should log now; exactly one of several concurrent callers wins
   */
  boolean note(long n) {
    count.addAndGet(n);
    long now = clock.getAsLong();
    long last = lastLogAt.get();
    boolean due = (last == 0L) || (now - last >= intervalMs);
    // compareAndSet so concurrent occurrences produce one line, not one per thread.
    return due && lastLogAt.compareAndSet(last, now);
  }

  /** Count without logging, e.g. for a loss that is reported by its own line. */
  void add(long n) {
    count.addAndGet(n);
  }

  /** @return every occurrence counted since this throttle was created */
  long total() {
    return count.get();
  }
}
