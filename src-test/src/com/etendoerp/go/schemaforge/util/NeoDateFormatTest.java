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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoDateFormat} — the canonical NEO date format (ETP-4793 / IMP-16).
 *
 * <p>These tests pin a data-integrity contract, not a formatting preference. The DAL parses
 * date strings with a <b>lenient</b> {@code SimpleDateFormat}, so a {@code dd-MM-yyyy} value is
 * not rejected but reinterpreted: {@code "06-08-2026"} was persisted as year <b>0012</b> and
 * {@code "24-06-2026"} as {@code 0029-12-17}. Both of those exact values were found in real
 * rows on {@code etendo-go-local} (see {@code docs/mcp-evaluation/imps/IMP-16.md} §3.6 in the
 * schema_forge repo), which is why they appear here verbatim as regression cases rather than as
 * illustrative examples.
 *
 * <p>The other half of the contract is what must <b>not</b> happen: an input this class does not
 * recognise has to yield {@code null} so every caller passes the original value through
 * untouched. A canonicalizer that guesses is more dangerous than the lenient parser it replaces,
 * so the rejection cases below are as load-bearing as the conversion ones.
 */
class NeoDateFormatTest {

  /**
   * Reset the memoized UI pattern before each test.
   *
   * <p>{@link NeoDateFormat#getUiDatePattern()} caches in a static field, and these tests run in
   * a JVM shared with tests that mock {@code OBPropertiesProvider}. Without this the suite's
   * result would depend on execution order.
   */
  @BeforeEach
  void resetPatternCache() throws ReflectiveOperationException {
    Field cache = NeoDateFormat.class.getDeclaredField("cachedUiDatePattern");
    cache.setAccessible(true);
    cache.set(null, null);
  }

  @Nested
  @DisplayName("the shapes that corrupted real rows")
  class RegressionCases {

    @Test
    @DisplayName("'06-08-2026' — persisted as year 0012 — becomes 2026-08-06")
    void repairsTheYear0012Case() {
      assertEquals("2026-08-06", NeoDateFormat.toCanonical("06-08-2026", false));
    }

    @Test
    @DisplayName("'24-06-2026' — persisted as 0029-12-17 — becomes 2026-06-24")
    void repairsTheYear0029Case() {
      assertEquals("2026-06-24", NeoDateFormat.toCanonical("24-06-2026", false));
    }

    @Test
    @DisplayName("a raw Postgres timestamp keeps only the calendar day for a date property")
    void acceptsPostgresTimestamp() {
      assertEquals("2026-08-06",
          NeoDateFormat.toCanonical("2026-08-06 18:55:31.567837+00", false));
    }
  }

  @Nested
  @DisplayName("already-canonical values")
  class Idempotence {

    @Test
    @DisplayName("an ISO date is returned unchanged")
    void isoDateUnchanged() {
      assertEquals("2026-08-06", NeoDateFormat.toCanonical("2026-08-06", false));
    }

    @Test
    @DisplayName("an ISO datetime is returned unchanged")
    void isoDatetimeUnchanged() {
      assertEquals("2026-08-06T14:30:00", NeoDateFormat.toCanonical("2026-08-06T14:30:00", true));
    }

    @Test
    @DisplayName("ISO is tried before the UI pattern, so a canonical value is never re-read")
    void isoWinsOverUiPattern() {
      // 06-08 and 08-06 are both valid day/month pairs; if the UI pattern were tried first this
      // would come back as the 8th of June.
      assertEquals("2026-08-06", NeoDateFormat.toCanonical("2026-08-06", false));
    }

    @Test
    @DisplayName("isCanonical distinguishes the two shapes")
    void isCanonicalContract() {
      assertTrue(NeoDateFormat.isCanonical("2026-08-06", false));
      assertFalse(NeoDateFormat.isCanonical("06-08-2026", false));
      // a bare date is not canonical for a datetime property: it is completed to midnight
      assertFalse(NeoDateFormat.isCanonical("2026-08-06", true));
      assertFalse(NeoDateFormat.isCanonical(null, false));
    }
  }

  @Nested
  @DisplayName("the time half")
  class TimeHalf {

    @Test
    @DisplayName("a datetime property with no time half is completed to midnight")
    void completesToMidnight() {
      assertEquals("2026-08-06T00:00:00", NeoDateFormat.toCanonical("2026-08-06", true));
      assertEquals("2026-08-06T00:00:00", NeoDateFormat.toCanonical("06-08-2026", true));
    }

    @Test
    @DisplayName("a real time is preserved for a datetime property")
    void preservesTimeForDatetime() {
      assertEquals("2026-08-06T18:55:31",
          NeoDateFormat.toCanonical("2026-08-06 18:55:31.567837+00", true));
    }

    @Test
    @DisplayName("HH:mm is completed to HH:mm:ss")
    void completesSeconds() {
      assertEquals("2026-08-06T14:30:00", NeoDateFormat.toCanonical("2026-08-06T14:30", true));
    }

    @Test
    @DisplayName("a time is dropped only when the property itself is date-only")
    void dropsTimeOnlyForDateOnly() {
      assertEquals("2026-08-06", NeoDateFormat.toCanonical("06-08-2026 14:30:00", false));
    }
  }

  @Nested
  @DisplayName("rejection — the caller must keep the original value")
  class Rejections {

    @Test
    @DisplayName("null and blank")
    void nullAndBlank() {
      assertNull(NeoDateFormat.toCanonical(null, false));
      assertNull(NeoDateFormat.toCanonical("", false));
      assertNull(NeoDateFormat.toCanonical("   ", false));
    }

    @Test
    @DisplayName("a different separator is not assumed to be the UI pattern")
    void differentSeparator() {
      assertNull(NeoDateFormat.toCanonical("06/08/2026", false));
    }

    @Test
    @DisplayName("non-dates and truncated dates")
    void notADate() {
      assertNull(NeoDateFormat.toCanonical("not-a-date", false));
      assertNull(NeoDateFormat.toCanonical("2026-08", false));
      assertNull(NeoDateFormat.toCanonical("Y", false));
    }

    @Test
    @DisplayName("strict resolution: an impossible day is rejected, never slid to a valid one")
    void strictResolution() {
      assertNull(NeoDateFormat.toCanonical("2026-13-40", false));
      // smart resolution would silently return February 28th — that is the same class of bug as
      // the lenient core parser this class exists to stop.
      assertNull(NeoDateFormat.toCanonical("2026-02-30", false));
      assertNull(NeoDateFormat.toCanonical("30-02-2026", false));
    }

    @Test
    @DisplayName("an offset with no time half is refused rather than guessed")
    void offsetWithoutTime() {
      assertNull(NeoDateFormat.toCanonical("2026-08-06+02:00", false));
    }
  }

  @Test
  @DisplayName("the UI pattern falls back to dd-MM-yyyy when dateFormat.java is unreadable")
  void uiPatternFallback() {
    // No Openbravo.properties is available in a plain unit test, so the lookup fails and the
    // documented default must apply — this is the pattern the whole conversion depends on.
    assertEquals("dd-MM-yyyy", NeoDateFormat.getUiDatePattern());
  }
}
