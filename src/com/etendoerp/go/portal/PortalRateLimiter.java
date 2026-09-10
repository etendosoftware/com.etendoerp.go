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

package com.etendoerp.go.portal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

/**
 * Fixed-window request counter for the portal's token-validating endpoint.
 *
 * <p><b>Defence in depth, not the defence.</b> A 256-bit token is already computationally
 * infeasible to guess; this exists so that trying is also cheap to absorb, and so a broken client
 * looping on an invalid link cannot turn into a database load problem. It is deliberately coarse:
 * it counts requests per caller per window and refuses past a ceiling.
 *
 * <p><b>Single-node only, on the same basis as {@code NeoImageUploadTickets}.</b> Counters live in
 * this JVM's heap, so each node enforces the ceiling independently and a restart forgives everyone.
 * That is acceptable for a defence-in-depth guard whose real protection is elsewhere. If Etendo GO
 * ever runs multi-node <em>and</em> this becomes the actual protection for something, it has to move
 * to shared state.
 *
 * <p>Bounded by construction: the map is cleared wholesale once it exceeds {@link #MAX_TRACKED_KEYS}
 * distinct callers, so a rotating-source flood cannot grow it without limit. Clearing rather than
 * evicting selectively forgives some callers early — acceptable, and far better than the
 * alternative of the guard itself becoming the memory problem.
 */
final class PortalRateLimiter {

  /** Window length each counter covers. */
  static final Duration WINDOW = Duration.ofMinutes(1);
  /** Requests one caller may make per {@link #WINDOW}. */
  static final int MAX_REQUESTS_PER_WINDOW = 30;
  /** Distinct callers tracked before the map is cleared wholesale. */
  static final int MAX_TRACKED_KEYS = 10_000;

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final Clock clock;

  PortalRateLimiter() {
    this(Clock.systemUTC());
  }

  /**
   * @param clock time source, injectable so a test can cross a window boundary for real rather than
   *     only exercising the ceiling
   */
  PortalRateLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * Registers one request and reports whether it is within the ceiling.
   *
   * @param key the caller identity, typically the remote address
   * @return {@code true} when the request may proceed, {@code false} when the caller is over the
   *     ceiling for the current window
   */
  boolean tryAcquire(String key) {
    String normalizedKey = StringUtils.defaultIfBlank(key, "unknown");
    if (windows.size() > MAX_TRACKED_KEYS) {
      windows.clear();
    }
    long currentWindow = clock.millis() / WINDOW.toMillis();
    Window updated = windows.compute(normalizedKey, (ignored, existing) ->
        existing == null || existing.window != currentWindow
            ? new Window(currentWindow, 1)
            : new Window(currentWindow, existing.count + 1));
    return updated.count <= MAX_REQUESTS_PER_WINDOW;
  }

  /** One caller's counter for one window. Immutable so {@code compute} stays atomic. */
  private static final class Window {
    private final long window;
    private final int count;

    private Window(long window, int count) {
      this.window = window;
      this.count = count;
    }
  }
}
