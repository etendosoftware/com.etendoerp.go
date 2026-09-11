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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openbravo.base.model.Property;

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
   * The ETP-5100 shape, shared by the outbound-rendering tests: a business timestamp written late
   * in the evening, the hour at which the old UTC formatter rolled the calendar day forward under
   * a negative offset. Kept as a civil literal — {@link java.sql.Timestamp#valueOf} reads it in
   * the default zone — so every expectation built from it is timezone-independent.
   */
  private static final String CIVIL_LITERAL = "2026-09-01 21:43:02";
  private static final String CIVIL_DATETIME = "2026-09-01T21:43:02";
  private static final String CIVIL_DATE = "2026-09-01";

  /** Fixed offsets, deliberately: no DST rules to make a reading depend on the date. */
  private static final ZoneId MINUS_3 = ZoneId.of("-03:00");
  private static final ZoneId PLUS_5 = ZoneId.of("+05:00");

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

    @Test
    @DisplayName("a time half this class cannot account for is refused, not silently midnight")
    void unparseableTimeHalf() {
      assertNull(NeoDateFormat.toCanonical("2026-08-06T banana", true));
      assertNull(NeoDateFormat.toCanonical("2026-08-06T14:30:00 (CEST)", true));
    }
  }

  /**
   * The offset rule, which is a correctness boundary rather than a formatting choice.
   *
   * <p>A value carrying a <b>non-zero</b> offset already reaches the DAL correctly:
   * {@code JsonUtils.convertFromXSDToJavaFormat} rewrites {@code +02:00} into {@code +0200} and
   * the datetime parser honours it. The canonical form has nowhere to put an offset, so
   * normalizing such a value would shift the instant by two hours — the canonicalizer would
   * become the source of the same silent reinterpretation it was written to remove. A zero
   * offset is a different case: an offset-less canonical value is read as UTC by that same
   * method (it appends {@code +0000}), so dropping {@code Z} / {@code +00:00} is an identity.
   */
  @Nested
  @DisplayName("zone offsets")
  class ZoneOffsets {

    @Test
    @DisplayName("a zero offset is dropped — Z, +00 and +00:00 all mean UTC")
    void zeroOffsetDropped() {
      assertEquals("2026-08-06T14:30:00", NeoDateFormat.toCanonical("2026-08-06T14:30:00Z", true));
      assertEquals("2026-08-06T14:30:00",
          NeoDateFormat.toCanonical("2026-08-06T14:30:00.000Z", true));
      assertEquals("2026-08-06T14:30:00",
          NeoDateFormat.toCanonical("2026-08-06T14:30:00+00:00", true));
      assertEquals("2026-08-06T18:55:31",
          NeoDateFormat.toCanonical("2026-08-06 18:55:31.567837+00", true));
    }

    @Test
    @DisplayName("a non-zero offset is refused so the caller keeps the correct instant")
    void nonZeroOffsetRefused() {
      assertNull(NeoDateFormat.toCanonical("2026-08-06T14:30:00+02:00", true));
      assertNull(NeoDateFormat.toCanonical("2026-08-06T14:30:00-03:00", true));
      assertNull(NeoDateFormat.toCanonical("2026-08-06T14:30:00-0300", true));
    }

    @Test
    @DisplayName("for a date-only property an offset cannot move the day, so it is ignored")
    void offsetIrrelevantForDateOnly() {
      // The DAL's date parser reads only the yyyy-MM-dd prefix here, so there is no instant to
      // shift and nothing to protect: the calendar day is the whole value.
      assertEquals("2026-08-06", NeoDateFormat.toCanonical("2026-08-06T14:30:00+02:00", false));
    }
  }

  /**
   * Eligibility. Etendo has five date-ish domain types and only two of them denote a calendar
   * date this class can speak about; all five are backed by {@code java.util.Date}, so a gate
   * written on the Java type would silently include the other three. That is not a hypothetical:
   * for a time-of-day property {@code JsonToDataConverter} keeps only the part after the
   * {@code T}, so a value rewritten to {@code yyyy-MM-dd} would lose the only half it reads.
   */
  @Nested
  @DisplayName("canonicalShapeFor — which properties are eligible at all")
  class Eligibility {

    private Property propertyWith(String predicate) {
      Property prop = org.mockito.Mockito.mock(Property.class);
      switch (predicate) {
        case "date":
          org.mockito.Mockito.when(prop.isDate()).thenReturn(true);
          break;
        case "datetime":
          org.mockito.Mockito.when(prop.isDatetime()).thenReturn(true);
          break;
        case "timestamp":
          org.mockito.Mockito.when(prop.isTime()).thenReturn(true);
          break;
        case "absoluteTime":
          org.mockito.Mockito.when(prop.isAbsoluteTime()).thenReturn(true);
          break;
        case "absoluteDateTime":
          org.mockito.Mockito.when(prop.isAbsoluteDateTime()).thenReturn(true);
          break;
        default:
          break;
      }
      return prop;
    }

    @Test
    @DisplayName("a date property asks for the date-only shape")
    void dateProperty() {
      assertEquals(Optional.of(Boolean.FALSE), NeoDateFormat.canonicalShapeFor(propertyWith("date")));
    }

    @Test
    @DisplayName("a datetime property asks for the datetime shape")
    void datetimeProperty() {
      assertEquals(Optional.of(Boolean.TRUE), NeoDateFormat.canonicalShapeFor(propertyWith("datetime")));
    }

    @Test
    @DisplayName("the three remaining date-ish domain types are excluded")
    void otherDomainTypesExcluded() {
      assertEquals(Optional.empty(), NeoDateFormat.canonicalShapeFor(propertyWith("timestamp")));
      assertEquals(Optional.empty(), NeoDateFormat.canonicalShapeFor(propertyWith("absoluteTime")));
      assertEquals(Optional.empty(), NeoDateFormat.canonicalShapeFor(propertyWith("absoluteDateTime")));
    }

    @Test
    @DisplayName("a non-date property and null are excluded")
    void nonDateExcluded() {
      assertEquals(Optional.empty(), NeoDateFormat.canonicalShapeFor(propertyWith("none")));
      assertEquals(Optional.empty(), NeoDateFormat.canonicalShapeFor(null));
    }
  }

  /**
   * ETP-4793 / IMP-24 phase 2. {@code toCanonical} returns {@code null} for two unrelated reasons,
   * and phase 1 could treat them alike because both ended in the same harmless pass-through. Phase 2
   * cannot: it turns a refusal into a 422. The offset-datetime family is refused <b>because it is
   * already correct</b> — the DAL parses it, the canonical form merely has nowhere to put the offset
   * — so rejecting it would break a working call rather than fix a broken one. Every test below is
   * therefore about one question: can this classifier be trusted to tell "wrong" from "not our
   * business", given that a false positive is a new error on valid input?
   */
  @Nested
  @DisplayName("isOffsetDateTime — refused-but-valid vs genuinely unusable")
  class OffsetClassifier {

    @Test
    @DisplayName("the ISO datetime with a non-zero offset — the one that must not be rejected")
    void nonZeroOffsetIsRecognized() {
      assertTrue(NeoDateFormat.isOffsetDateTime("2026-08-06T14:30:00+02:00"));
      assertTrue(NeoDateFormat.isOffsetDateTime("2026-08-06T14:30:00-05:00"));
      assertTrue(NeoDateFormat.isOffsetDateTime("2026-08-06T14:30-05:00"));
    }

    /**
     * The space separator is accepted on purpose. Only the {@code T} form is documented as reaching
     * the DAL intact, but the two mistakes are not symmetric: a wrongly passed-through value keeps
     * the behaviour that has been running all along, while a wrongly rejected one is a brand-new 422
     * on a call that used to work.
     */
    @Test
    @DisplayName("the space separator counts too — the safe direction when unsure")
    void spaceSeparatorIsRecognized() {
      assertTrue(NeoDateFormat.isOffsetDateTime("2026-08-06 14:30:00+02:00"));
    }

    /**
     * A zero offset never reaches this question, because {@code toCanonical} converts it. Answering
     * {@code true} anyway would be harmless in the current call path and wrong as a contract, so it
     * is pinned: the classifier speaks only for values that were actually refused.
     */
    @Test
    @DisplayName("a zero offset is not in the family — toCanonical converts those")
    void zeroOffsetIsNotRecognized() {
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-08-06T14:30:00Z"));
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-08-06T14:30:00+00:00"));
    }

    @Test
    @DisplayName("the genuinely unusable shapes are not shielded")
    void unusableShapesAreNotRecognized() {
      assertFalse(NeoDateFormat.isOffsetDateTime("06/08/2026"));
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-13-40"));
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-08"));
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-08-06T banana"));
      assertFalse(NeoDateFormat.isOffsetDateTime("banana"));
      assertFalse(NeoDateFormat.isOffsetDateTime(""));
      assertFalse(NeoDateFormat.isOffsetDateTime(null));
    }

    /**
     * A UI-pattern date half is excluded even when the offset half is well formed. The DAL's XSD
     * parser reads the date half as ISO, so {@code 06-08-2026T14:30:00+02:00} is not a value it
     * handles correctly — shielding it from rejection would leave the year-0012 class of corruption
     * reachable through the one branch that skips the repair.
     */
    @Test
    @DisplayName("a dd-MM-yyyy date half is not shielded by a valid offset")
    void uiPatternDateHalfIsNotRecognized() {
      assertFalse(NeoDateFormat.isOffsetDateTime("06-08-2026T14:30:00+02:00"));
    }

    @Test
    @DisplayName("a date with no time half is never in the family")
    void dateOnlyIsNotRecognized() {
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-08-06"));
      assertFalse(NeoDateFormat.isOffsetDateTime("2026-08-06+02:00"));
    }
  }

  /**
   * ETP-4793 / IMP-24, the ambiguity gate. {@code toCanonical} treats {@code "03-04-2026"} and
   * {@code "20-09-2026"} alike: both parse under the strict UI pattern, so both come back
   * non-null. That is correct for {@code toCanonical}'s own contract — it only promises "this is
   * <i>a</i> valid date" — but it is exactly the gap phase 2 left open: {@code "03-04-2026"} is
   * also a valid date under {@code MM-dd-yyyy} (4 March), while {@code "20-09-2026"} is not (there
   * is no month 20), so only the first is a genuine coin flip. {@link
   * NeoDateFormat#isAmbiguousUiDate} is the predicate that tells them apart; {@link
   * NeoDateFormat#ambiguousReadings} names the two candidate dates so the rejection can quote them.
   */
  @Nested
  @DisplayName("isAmbiguousUiDate / ambiguousReadings — the coin-flip predicate")
  class AmbiguityClassifier {

    @Test
    @DisplayName("day and month both <=12 and different — the genuine coin flip")
    void ambiguousValueIsRecognized() {
      assertTrue(NeoDateFormat.isAmbiguousUiDate("03-04-2026"));
    }

    @Test
    @DisplayName("day > 12 — no month-first reading exists, so this is not ambiguous")
    void dayOver12IsNotAmbiguous() {
      // this is the case IMP-24's own report called out as most likely to be broken by a naive fix
      assertFalse(NeoDateFormat.isAmbiguousUiDate("20-09-2026"));
    }

    @Test
    @DisplayName("equal day and month denote the same date under either reading")
    void equalDayAndMonthIsNotAmbiguous() {
      assertFalse(NeoDateFormat.isAmbiguousUiDate("03-03-2026"));
    }

    @Test
    @DisplayName("an ISO value is never ambiguous — the ISO branch is untouched by this gate")
    void isoValueIsNotAmbiguous() {
      assertFalse(NeoDateFormat.isAmbiguousUiDate("2026-03-04"));
      assertFalse(NeoDateFormat.isAmbiguousUiDate("2026-08-06 18:55:31.567837+00"));
    }

    @Test
    @DisplayName("a value neither parser can read is not ambiguous, just unreadable")
    void unparseableValueIsNotAmbiguous() {
      assertFalse(NeoDateFormat.isAmbiguousUiDate("06/08/2026"));
      assertFalse(NeoDateFormat.isAmbiguousUiDate("not-a-date"));
    }

    @Test
    @DisplayName("null and blank are not ambiguous")
    void nullAndBlankAreNotAmbiguous() {
      assertFalse(NeoDateFormat.isAmbiguousUiDate(null));
      assertFalse(NeoDateFormat.isAmbiguousUiDate(""));
      assertFalse(NeoDateFormat.isAmbiguousUiDate("   "));
    }

    @Test
    @DisplayName("ambiguousReadings names both candidate dates for a genuine coin flip")
    void readingsNameBothCandidates() {
      String[] readings = NeoDateFormat.ambiguousReadings("03-04-2026");
      assertEquals("2026-04-03", readings[0]);
      assertEquals("2026-03-04", readings[1]);
    }

    @Test
    @DisplayName("ambiguousReadings is an empty array for anything that is not ambiguous")
    void readingsIsEmptyWhenNotAmbiguous() {
      assertEquals(0, NeoDateFormat.ambiguousReadings("20-09-2026").length);
      assertEquals(0, NeoDateFormat.ambiguousReadings("2026-03-04").length);
      assertEquals(0, NeoDateFormat.ambiguousReadings("06/08/2026").length);
      assertEquals(0, NeoDateFormat.ambiguousReadings(null).length);
    }
  }

  @Nested
  @DisplayName("canonicalPattern — the pattern quoted back in an error")
  class CanonicalPattern {

    @Test
    @DisplayName("it quotes the same constants the conversion produces")
    void matchesTheConstants() {
      assertEquals(NeoDateFormat.ISO_DATE, NeoDateFormat.canonicalPattern(false));
      assertEquals(NeoDateFormat.ISO_DATETIME, NeoDateFormat.canonicalPattern(true));
    }
  }

  /**
   * ETP-5100 — the outbound direction, and the one bug in this area that reached real users.
   *
   * <p>Five hand-rolled formatters rendered a business timestamp through
   * {@code DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)}.
   * A business date is a <b>civil</b> value stored at the server's local wall clock, so reading it
   * back as an instant in UTC re-expresses it elsewhere: a row written at 21:43 under a negative
   * offset went out as the NEXT calendar day, and the React range filter — which reads the
   * {@code yyyy-MM-dd} prefix — dropped it. Funds transfers made after 21:00 in UTC-3 simply
   * vanished from the movements list.
   *
   * <p>Every test below supplies a <b>civil</b> input ({@link Timestamp#valueOf}, which reads the
   * literal in the default zone) and asserts the matching civil output, so input and expectation
   * move together in whatever zone the runner is in. That pairing is the whole point: an
   * instant-in / UTC-string-out test was timezone-independent only because the old formatter
   * forced UTC, and merely dropping the {@code Z} from such an expectation would encode the
   * runner's timezone into the suite — a worse bug than the one being fixed.
   * {@link ZoneIndependence} pins that property directly by running one case under two zones.
   */
  @Nested
  @DisplayName("toWireDateTime / toWireDate — the outbound business timestamp")
  class WireRendering {

    @Test
    @DisplayName("null in, null out — callers keep their own empty-vs-null convention")
    void nullYieldsNull() {
      assertNull(NeoDateFormat.toWireDateTime(null));
      assertNull(NeoDateFormat.toWireDate(null));
    }

    @Test
    @DisplayName("a civil timestamp round-trips verbatim — the ETP-5100 regression")
    void civilTimestampRoundTrips() {
      // Under the old UTC formatter this exact value went out as "2026-09-02T00:43:02Z" on a
      // UTC-3 server: the next calendar day, and the row disappeared from the filtered list.
      assertEquals(CIVIL_DATETIME, NeoDateFormat.toWireDateTime(Timestamp.valueOf(CIVIL_LITERAL)));
    }

    @Test
    @DisplayName("the output carries no trailing Z and matches the declared ISO_DATETIME shape")
    void outputMatchesTheDeclaredDatetimeShape() {
      String out = NeoDateFormat.toWireDateTime(Timestamp.valueOf(CIVIL_LITERAL));
      assertFalse(out.endsWith("Z"), "a zone-less civil value must not assert UTC");
      // Parsed back with the class's own declared pattern, so the test cannot drift from the
      // constant the contract is written in terms of.
      assertEquals(LocalDateTime.of(2026, 9, 1, 21, 43, 2),
          LocalDateTime.parse(out, DateTimeFormatter.ofPattern(NeoDateFormat.ISO_DATETIME)));
    }

    @Test
    @DisplayName("toWireDate drops the time half and matches the declared ISO_DATE shape")
    void wireDateDropsTheTimeHalf() {
      String out = NeoDateFormat.toWireDate(Timestamp.valueOf(CIVIL_LITERAL));
      assertEquals(CIVIL_DATE, out);
      assertFalse(out.endsWith("Z"), "a zone-less civil value must not assert UTC");
      assertEquals(LocalDate.of(2026, 9, 1),
          LocalDate.parse(out, DateTimeFormatter.ofPattern(NeoDateFormat.ISO_DATE)));
    }

    /**
     * A {@link java.sql.Date} argument must work. The implementation uses {@code getTime()} rather
     * than {@code toInstant()} precisely for this: {@code java.sql.Date.toInstant()} is specified
     * to throw, so the obvious implementation would blow up on an argument the callers really do
     * pass — {@code CashCloseSupport.formatIsoDate} is declared over {@code java.util.Date} and is
     * fed DAL date columns.
     */
    @Test
    @DisplayName("a java.sql.Date argument works — its toInstant() would have thrown")
    void acceptsJavaSqlDate() {
      java.sql.Date sqlDate = java.sql.Date.valueOf(CIVIL_DATE);

      assertThrows(UnsupportedOperationException.class, sqlDate::toInstant,
          "the trap this implementation sidesteps by using getTime()");

      assertEquals(CIVIL_DATE, NeoDateFormat.toWireDate(sqlDate));
      // Only the day is pinned for the datetime rendering: java.sql.Date.valueOf anchors to local
      // midnight, and a midnight that a DST gap skips would legitimately render as 01:00:00.
      assertTrue(NeoDateFormat.toWireDateTime(sqlDate).startsWith(CIVIL_DATE + "T"));
    }
  }

  /**
   * The property that would have caught the original bug, and that also fails a naive "just drop
   * the {@code Z}" fix: a civil value renders identically whatever the server's zone is, while an
   * instant does not. Both tests set the default zone explicitly and restore it in a
   * {@code finally}, so they are themselves independent of the runner's zone.
   */
  @Nested
  @DisplayName("zone independence")
  class ZoneIndependence {

    private String renderCivilIn(ZoneId zone) {
      TimeZone.setDefault(TimeZone.getTimeZone(zone));
      // Built INSIDE the zone: Timestamp.valueOf reads the literal in the default zone, so this is
      // the same civil value each time and a different instant each time.
      return NeoDateFormat.toWireDateTime(Timestamp.valueOf(CIVIL_LITERAL));
    }

    @Test
    @DisplayName("the same civil value renders identically under UTC-3 and UTC+5")
    void civilValueIsZoneIndependent() {
      TimeZone original = TimeZone.getDefault();
      try {
        assertEquals(CIVIL_DATETIME, renderCivilIn(MINUS_3));
        assertEquals(CIVIL_DATETIME, renderCivilIn(PLUS_5));
      } finally {
        TimeZone.setDefault(original);
      }
    }

    /**
     * The dual, pinning the arithmetic of the bug itself: {@code 2026-09-02T00:43:02Z} is the
     * instant the old UTC formatter emitted for a row written at 21:43 local on a UTC-3 server.
     * Rendering an instant is zone-dependent by design, which is exactly why a test that pairs an
     * instant input with a zone-less expectation encodes the runner's timezone.
     */
    @Test
    @DisplayName("the same instant does not — which is why the tests above supply civil values")
    void instantIsNotZoneIndependent() {
      TimeZone original = TimeZone.getDefault();
      try {
        Date instant = Date.from(Instant.parse("2026-09-02T00:43:02Z"));

        TimeZone.setDefault(TimeZone.getTimeZone(MINUS_3));
        String minus3 = NeoDateFormat.toWireDateTime(instant);

        TimeZone.setDefault(TimeZone.getTimeZone(PLUS_5));
        String plus5 = NeoDateFormat.toWireDateTime(instant);

        assertEquals(CIVIL_DATETIME, minus3);
        assertEquals("2026-09-02T05:43:02", plus5);
        assertNotEquals(minus3, plus5);
      } finally {
        TimeZone.setDefault(original);
      }
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
