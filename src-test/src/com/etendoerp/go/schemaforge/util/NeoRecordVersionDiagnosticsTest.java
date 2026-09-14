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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.ENTITY;
import static com.etendoerp.go.schemaforge.util.NeoRecordVersionFixtures.RECORD_ID;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * The zone-offset diagnostic and the route helper added to {@link NeoRecordVersion} by ETP-5255.
 *
 * <p>Split into its own top-level class rather than a {@code @Nested} group for the reason given
 * in {@link NeoRecordVersionFixtures}: this Sonar version does not recognise nested test classes
 * and flags the outer one as having no tests (java:S2187, blocker).
 *
 * <h2>What these tests can and cannot pin</h2>
 *
 * The offsetless-token guard is a <b>pure diagnostic</b>: it writes one ERROR line and then lets
 * the comparison run exactly as it did before ETP-5255. That is the contract worth defending, and
 * it is what most of this class asserts — an offsetless token must still be COMPARED, never
 * refused outright and never silently skipped.
 *
 * <p>The corollary is that the guard's own verdict — whether it classified a given token as
 * carrying a zone — changes no return value and is observable ONLY in the log. This project has no
 * log-capturing appender (see {@code src-test/resources/log4j2-test.xml}, which wires a Console
 * appender and nothing else), so nothing here asserts the level or the presence of a line. The
 * date-half trap documented on {@code hasZoneOffset} is therefore covered here only to the extent
 * that it is behavioural; see {@link #dateSeparatorsAreNotAnOffset()}.
 *
 * <h2>Every timestamp here is built in an explicit zone</h2>
 *
 * Deliberately NOT via {@link NeoRecordVersionFixtures#instant} (host-local): the whole subject of
 * these tests is which zone an offsetless token is read in, so a fixture that follows the host's
 * zone would pass everywhere and prove nothing. Core's XSD repair appends {@code +0000} to a token
 * with no offset, so such a token is compared AS UTC — and the stored values below say UTC
 * outright, which is what makes these assertions fail on a server whose zone is not UTC if that
 * behaviour ever changes.
 */
class NeoRecordVersionDiagnosticsTest {

  /** A token with no zone at all — the shape {@code NeoDateFormat.toCanonical} emits. */
  private static final String OFFSETLESS = "2026-08-28T12:30:15";

  private MockedStatic<OBDal> obDalMock;
  private OBDal obDal;

  @BeforeEach
  void setUp() {
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  /** Stubs the DAL to return a traceable record whose stored {@code updated} is {@code stored}. */
  private void storedUpdatedIs(Date stored) {
    NeoRecordVersionFixtures.TraceableRecord traceable =
        mock(NeoRecordVersionFixtures.TraceableRecord.class);
    when(traceable.getUpdated()).thenReturn(stored);
    when(obDal.get(anyString(), any())).thenReturn(traceable);
  }

  /** A fixed instant, stated in an explicit zone rather than the host's. */
  private static Date at(String zoneId, int month, int day, int hour, int minute, int second) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(zoneId));
    calendar.clear();
    calendar.set(2026, month, day, hour, minute, second);
    return calendar.getTime();
  }

  private static Date plusSeconds(Date value, int seconds) {
    return new Date(value.getTime() + seconds * 1000L);
  }

  // ---------------------------------------------------------------------------------------------
  // routeOf
  // ---------------------------------------------------------------------------------------------

  /**
   * The rendered shape is asserted literally, not by regex. Two call sites (NeoCrudHandler and
   * McpToolRouter) feed this, and the point of sharing it is that a log query matches one shape;
   * a test that tolerated variations would let the shapes drift apart again.
   */
  @Test
  @DisplayName("routeOf renders `METHOD /spec/entity/record`")
  void routeOfRendersTheDocumentedShape() {
    assertEquals("PUT /contacts/customer/1000042",
        NeoRecordVersion.routeOf("PUT", "contacts", "customer", "1000042"));
  }

  @Test
  @DisplayName("routeOf keeps the spec entity, not the DAL entity, in the third position")
  void routeOfRendersMcpStyleRoutes() {
    assertEquals("neo_update /sales-order/lines/ABC123",
        NeoRecordVersion.routeOf("neo_update", "sales-order", "lines", "ABC123"));
  }

  /**
   * A route is only ever a log fragment, so a missing component must degrade to a readable line
   * rather than take down the write it was describing. {@code String.format} renders a null as the
   * four characters {@code null}, which is exactly the wanted outcome: visibly wrong, not fatal.
   */
  @Test
  @DisplayName("routeOf does not throw on a null component")
  void routeOfToleratesNullComponents() {
    assertEquals("null /null/null/null", NeoRecordVersion.routeOf(null, null, null, null));
    assertEquals("PUT /contacts/customer/null",
        NeoRecordVersion.routeOf("PUT", "contacts", "customer", null));
  }

  // ---------------------------------------------------------------------------------------------
  // The offsetless token is still compared — the guard is a diagnostic, not a behaviour change
  // ---------------------------------------------------------------------------------------------

  /**
   * The single most important assertion in this class. ETP-5255 added an ERROR line for a token
   * that names no zone; it deliberately did NOT add a refusal. A token whose UTC reading matches
   * the row must still answer "not stale" and let the write through — if the guard were ever
   * turned into a rejection, this fails.
   */
  @Test
  @DisplayName("an offsetless token that matches the row is still not stale")
  void offsetlessMatchingTokenIsNotStale() {
    storedUpdatedIs(at("UTC", Calendar.AUGUST, 28, 12, 30, 15));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS));
  }

  /**
   * The other half of the same contract: the guard must not SKIP the comparison either. A token
   * with no zone whose UTC reading differs from the row is a real mismatch and must still be
   * refused — a "return false early once we know the token is suspect" would look like a
   * safety measure and would in fact disable optimistic locking for every caller that lost its
   * offset, which per the class javadoc is the common case.
   */
  @Test
  @DisplayName("an offsetless token that does not match the row is still stale")
  void offsetlessMismatchingTokenIsStale() {
    storedUpdatedIs(plusSeconds(at("UTC", Calendar.AUGUST, 28, 12, 30, 15), 1));
    assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS));
  }

  /**
   * The date half of an ISO datetime carries {@code -} separators, and {@code hasZoneOffset}
   * examines only the part after the {@code T} for exactly that reason. If someone later
   * simplifies it to scan the whole string, {@code 2026-09-10T12:00:00} starts being classified
   * as "has a zone" and the ERROR line for the single most common offsetless shape stops being
   * written.
   *
   * <p><b>Honest limit:</b> that misclassification changes no return value — the guard only logs —
   * so this test does NOT catch it. What it does pin is the behaviour underneath: a token whose
   * only {@code -} characters are date separators is offsetless, and is therefore compared AS UTC.
   * Catching the misclassification itself needs a log-capturing appender, which this project does
   * not have.
   */
  @Test
  @DisplayName("the date half is not an offset — such a token is compared as UTC")
  void dateSeparatorsAreNotAnOffset() {
    storedUpdatedIs(at("UTC", Calendar.SEPTEMBER, 10, 12, 0, 0));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, "2026-09-10T12:00:00"));

    storedUpdatedIs(plusSeconds(at("UTC", Calendar.SEPTEMBER, 10, 12, 0, 0), 1));
    assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, "2026-09-10T12:00:00"));
  }

  // ---------------------------------------------------------------------------------------------
  // The zone shapes
  // ---------------------------------------------------------------------------------------------

  /**
   * The two shapes that state a NON-UTC offset and survive core's repair-then-parse: the canonical
   * XSD one, and the already-compact one (which survives only because the repair appends a second
   * {@code +0000} that {@code SimpleDateFormat} then ignores as trailing text — the first offset
   * field in the string wins). Both must be read at the stated offset, NOT at the host's zone and
   * NOT as UTC.
   */
  @ParameterizedTest
  @ValueSource(strings = { "2026-08-28T12:30:15+02:00", "2026-08-28T12:30:15+0200" })
  @DisplayName("a stated offset is honoured, not replaced by the server's zone")
  void statedOffsetIsHonoured(String clientValue) {
    storedUpdatedIs(at("GMT+02:00", Calendar.AUGUST, 28, 12, 30, 15));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));

    storedUpdatedIs(plusSeconds(at("GMT+02:00", Calendar.AUGUST, 28, 12, 30, 15), 1));
    assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));
  }

  /**
   * The regression ETP-5255 actually fixed, and the one that matters more than the log levels.
   *
   * <p>Before {@code normalizeZoneDesignator}, a {@code Z}-suffixed token — an ordinary ISO instant
   * and what any JavaScript or MCP client writes — was repaired to {@code ...15Z+0000}, which
   * core's format cannot parse. It therefore fell into the unparseable guard and the concurrency
   * check DID NOT RUN: every such write went through unchecked, whether or not the row had moved.
   *
   * <p>Both halves are asserted, and the stale half is the one that was broken: it used to answer
   * {@code false}. {@code +00:00} rides along as the control — the three must not merely each be
   * right, they must be the SAME; see {@link #zuluDecidesWhatExplicitUtcDecides()}.
   */
  @ParameterizedTest
  @ValueSource(strings = { "2026-08-28T12:30:15Z", "2026-08-28T12:30:15z",
      "2026-08-28T12:30:15+00:00" })
  @DisplayName("a Zulu token is compared as UTC, not skipped")
  void zuluTokenIsCompared(String clientValue) {
    storedUpdatedIs(at("UTC", Calendar.AUGUST, 28, 12, 30, 15));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));

    storedUpdatedIs(plusSeconds(at("UTC", Calendar.AUGUST, 28, 12, 30, 15), 1));
    assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));
  }

  /**
   * The equivalence stated directly, rather than left to two independent assertions that happen to
   * agree: whatever the comparison decides for {@code +00:00} it must decide for {@code Z} and
   * {@code z}, on the same row. Run over both rows, so the shared verdict is {@code true} in one
   * pass — an equivalence whose two sides are both {@code false} would also hold with the check
   * disabled outright, which is precisely the state this ticket found.
   */
  @Test
  @DisplayName("Z and z decide exactly what +00:00 decides")
  void zuluDecidesWhatExplicitUtcDecides() {
    Date stored = at("UTC", Calendar.AUGUST, 28, 12, 30, 15);
    for (Date row : new Date[] { stored, plusSeconds(stored, 1) }) {
      storedUpdatedIs(row);
      boolean explicit = NeoRecordVersion.isStale(ENTITY, RECORD_ID, "2026-08-28T12:30:15+00:00");
      storedUpdatedIs(row);
      assertEquals(explicit, NeoRecordVersion.isStale(ENTITY, RECORD_ID, "2026-08-28T12:30:15Z"));
      storedUpdatedIs(row);
      assertEquals(explicit, NeoRecordVersion.isStale(ENTITY, RECORD_ID, "2026-08-28T12:30:15z"));
    }
  }

  /**
   * Shapes core's repair-then-parse still cannot consume, left unfixed by ETP-5255 on purpose:
   * widening what counts as a valid token belongs to the contract's owner, not to a logging ticket.
   * All must answer "not stale" WITHOUT throwing.
   *
   * <ul>
   *   <li>{@code +02} — an hour-only offset, which XSD {@code dateTime} does not allow. A
   *       malformed token, so the unparseable guard is the right place for it.</li>
   *   <li>{@code 2026-09-10} — no time component at all, so {@code hasZoneOffset} reaches it with
   *       no {@code T} in the string, where an implementation assuming a time part would index out
   *       of bounds.</li>
   *   <li>{@code 2026-09-10Z} — a date-only value carrying a {@code Z}. Pins that
   *       {@code normalizeZoneDesignator} leaves it ALONE: rewriting the {@code Z} here would
   *       manufacture {@code 2026-09-10+00:00}, which still does not parse, and would move the
   *       reported failure away from the malformed value that caused it.</li>
   *   <li>the millisecond shapes — {@code new Date().toISOString()} in JavaScript, i.e. what a
   *       hand-rolled JS or MCP client emits. {@code yyyy-MM-dd'T'HH:mm:ssZZZZZ} carries no
   *       millisecond field, so these parse neither with a zone nor without one, before or after
   *       this change. Pinned as the CURRENT answer, not a desirable one: core's own comparison
   *       zeroes milliseconds anyway, so discarding them would cost nothing semantically.</li>
   * </ul>
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "2026-08-28T12:30:15+02",
      "2026-09-10",
      "2026-09-10Z",
      "2026-08-28T12:30:15.123Z",
      "2026-08-28T12:30:15.123+02:00",
      "2026-08-28T12:30:15.123" })
  @DisplayName("a token core's parser cannot consume is not a conflict and does not throw")
  void unconsumableTokensAreNotStale(String clientValue) {
    storedUpdatedIs(at("UTC", Calendar.AUGUST, 28, 12, 30, 15));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, clientValue));
  }

  // ---------------------------------------------------------------------------------------------
  // Overload parity
  // ---------------------------------------------------------------------------------------------

  /**
   * The three-argument form is kept for the fifteen call sites that do not have a route to hand,
   * and it must be a pure delegation: the route reaches log lines only. Asserted for both verdicts
   * so a wiring mistake that inverted or short-circuited one overload cannot pass.
   */
  @Test
  @DisplayName("the three-argument overload matches the four-argument one with a null route")
  void threeArgOverloadMatchesNullRoute() {
    Date stored = at("UTC", Calendar.AUGUST, 28, 12, 30, 15);

    storedUpdatedIs(stored);
    assertEquals(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS, null),
        NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS));

    storedUpdatedIs(plusSeconds(stored, 1));
    assertEquals(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS, null),
        NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS));
    assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS));
  }

  /**
   * A supplied route must not alter the verdict either — it is diagnostic payload, and a blank one
   * is explicitly accepted (it degrades to a stand-in string inside the log line). Covers the
   * blank branch of the private {@code route} helper through the only door it has.
   */
  @ParameterizedTest
  @ValueSource(strings = { "PUT /contacts/customer/1000042", "", "   " })
  @DisplayName("the verdict is independent of the route argument")
  void verdictIsIndependentOfTheRoute(String requestPath) {
    storedUpdatedIs(at("UTC", Calendar.AUGUST, 28, 12, 30, 15));
    assertFalse(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS, requestPath));

    storedUpdatedIs(plusSeconds(at("UTC", Calendar.AUGUST, 28, 12, 30, 15), 1));
    assertTrue(NeoRecordVersion.isStale(ENTITY, RECORD_ID, OFFSETLESS, requestPath));
  }
}
