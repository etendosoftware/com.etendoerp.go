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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * ETP-5051 — "log this at most once per interval, per key". The per-tenant counterpart of
 * {@code usageevents.LogThrottle}, which throttles one global line: a WARN about one tenant must
 * not silence the same WARN about another.
 *
 * <p>The map grows by one entry per key ever seen; keys are tenant ids (or a single constant), so
 * it stays bounded by the number of tenants.
 */
public final class KeyedLogThrottle {

  private final long intervalMs;
  private final LongSupplier clock;
  private final Map<String, Long> lastLoggedAt = new ConcurrentHashMap<>();

  /**
   * @param intervalMs the minimum time between two lines for the same key
   * @param clock the time source, in milliseconds
   */
  public KeyedLogThrottle(long intervalMs, LongSupplier clock) {
    this.intervalMs = intervalMs;
    this.clock = clock;
  }

  /**
   * @param key what the line is about
   * @return {@code true} when the caller should log now; concurrent callers for the same key get
   *     one {@code true} between them
   */
  public boolean shouldLog(String key) {
    String safeKey = key == null ? "" : key;
    long now = clock.getAsLong();
    boolean[] due = new boolean[1];
    lastLoggedAt.compute(safeKey, (k, last) -> {
      if (last == null || now - last >= intervalMs) {
        due[0] = true;
        return now;
      }
      return last;
    });
    return due[0];
  }
}
