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
package com.etendoerp.go.security;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Fixed-window rate limit for the unauthenticated CSP collector: a per-client and a global budget
 * per window. The per-client table is bounded, so a flood of distinct addresses cannot grow memory;
 * when it is full only the global budget applies to new clients.
 */
final class CspReportRateLimiter {

  static final int MAX_TRACKED_CLIENTS = 10_000;

  private final int perClientLimit;
  private final int globalLimit;
  private final long windowMillis;
  private final LongSupplier clock;
  private final Map<String, Integer> perClient = new HashMap<>();
  private long windowStart;
  private int globalCount;

  CspReportRateLimiter(int perClientLimit, int globalLimit, long windowMillis, LongSupplier clock) {
    this.perClientLimit = perClientLimit;
    this.globalLimit = globalLimit;
    this.windowMillis = windowMillis;
    this.clock = clock;
    this.windowStart = clock.getAsLong();
  }

  /** @return true when the request is within budget (and consumes it) */
  synchronized boolean tryAcquire(String client) {
    long now = clock.getAsLong();
    if (now - windowStart >= windowMillis) {
      windowStart = now;
      globalCount = 0;
      perClient.clear();
    }
    if (globalCount >= globalLimit) {
      return false;
    }
    Integer used = perClient.get(client);
    if (used == null && perClient.size() < MAX_TRACKED_CLIENTS) {
      used = 0;
    }
    if (used != null) {
      if (used >= perClientLimit) {
        return false;
      }
      perClient.put(client, used + 1);
    }
    globalCount++;
    return true;
  }
}
