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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.Test;

/**
 * Unit tests for {@link PortalRateLimiter}: the per-key ceiling per one-minute window, the
 * {@code /me} and {@code /renew} default of 30 and the configurable ceiling the data reads use.
 *
 * @covers com.etendoerp.go.portal.PortalRateLimiter
 */
public class PortalRateLimiterTest {

  private static final long WINDOW_START = 1_000L * PortalRateLimiter.WINDOW.toMillis();

  @Test
  public void eachConstructorHonoursItsCeilingPerKeyAndWindow() {
    Clock clock = mock(Clock.class);
    when(clock.millis()).thenReturn(WINDOW_START);
    // Columns: limiter, its ceiling.
    Object[][] cases = {
        { new PortalRateLimiter(clock), PortalRateLimiter.MAX_REQUESTS_PER_WINDOW },
        { new PortalRateLimiter(clock, 3), 3 },
        { new PortalRateLimiter(clock, PortalRateLimiter.MAX_DATA_REQUESTS_PER_WINDOW),
            PortalRateLimiter.MAX_DATA_REQUESTS_PER_WINDOW },
    };
    assertEquals(30, PortalRateLimiter.MAX_REQUESTS_PER_WINDOW);
    for (Object[] c : cases) {
      PortalRateLimiter limiter = (PortalRateLimiter) c[0];
      int ceiling = (Integer) c[1];

      assertEquals("ceiling " + ceiling, ceiling, acceptedOf(limiter, "203.0.113.7", ceiling + 5));
      // Another caller has its own count.
      assertEquals(ceiling, acceptedOf(limiter, "198.51.100.9", ceiling + 1));
    }
  }

  @Test
  public void aNewWindowStartsTheCountAgain() {
    Clock clock = mock(Clock.class);
    when(clock.millis()).thenReturn(WINDOW_START);
    PortalRateLimiter limiter = new PortalRateLimiter(clock, 2);
    assertEquals(2, acceptedOf(limiter, "203.0.113.7", 4));

    when(clock.millis()).thenReturn(WINDOW_START + PortalRateLimiter.WINDOW.toMillis());

    assertEquals(2, acceptedOf(limiter, "203.0.113.7", 4));
  }

  private static int acceptedOf(PortalRateLimiter limiter, String key, int attempts) {
    int accepted = 0;
    for (int attempt = 0; attempt < attempts; attempt++) {
      if (limiter.tryAcquire(key)) {
        accepted++;
      }
    }
    return accepted;
  }
}
