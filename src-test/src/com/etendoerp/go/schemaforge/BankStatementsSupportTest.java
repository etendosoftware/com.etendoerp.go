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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.junit.Test;

/**
 * Unit tests for {@link BankStatementsSupport} — the stateless helpers extracted
 * from {@link BankStatementsHandler}. All pure, no mocks required.
 */
public class BankStatementsSupportTest {

  // ── deriveStatementStatus ────────────────────────────────────────────────

  @Test
  public void deriveStatementStatusReturnsPendingForEmptyStatement() {
    assertEquals("PENDING", BankStatementsSupport.deriveStatementStatus(0, 0));
  }

  @Test
  public void deriveStatementStatusReturnsPendingWhenNoMatches() {
    assertEquals("PENDING", BankStatementsSupport.deriveStatementStatus(10, 0));
  }

  @Test
  public void deriveStatementStatusReturnsPartialWhenSomeMatched() {
    assertEquals("PARTIAL", BankStatementsSupport.deriveStatementStatus(10, 4));
  }

  @Test
  public void deriveStatementStatusReturnsReconciledWhenAllMatched() {
    assertEquals("RECONCILED", BankStatementsSupport.deriveStatementStatus(10, 10));
  }

  @Test
  public void deriveStatementStatusReturnsDraftWhenNotProcessed() {
    // Unprocessed statements are drafts regardless of matching state.
    assertEquals("DRAFT", BankStatementsSupport.deriveStatementStatus(false, 0, 0));
    assertEquals("DRAFT", BankStatementsSupport.deriveStatementStatus(false, 10, 10));
  }

  @Test
  public void deriveStatementStatusWithProcessedDelegatesToMatchingState() {
    assertEquals("PENDING", BankStatementsSupport.deriveStatementStatus(true, 10, 0));
    assertEquals("PARTIAL", BankStatementsSupport.deriveStatementStatus(true, 10, 4));
    assertEquals("RECONCILED", BankStatementsSupport.deriveStatementStatus(true, 10, 10));
  }

  // ── nullSafeBigDecimal ───────────────────────────────────────────────────

  @Test
  public void nullSafeBigDecimalReturnsZeroForNull() {
    assertSame(BigDecimal.ZERO, BankStatementsSupport.nullSafeBigDecimal(null));
  }

  @Test
  public void nullSafeBigDecimalReturnsValueWhenNotNull() {
    BigDecimal v = new BigDecimal("12.34");
    assertSame(v, BankStatementsSupport.nullSafeBigDecimal(v));
  }

  // ── formatDate ───────────────────────────────────────────────────────────

  @Test
  public void formatDateReturnsEmptyForNull() {
    assertEquals("", BankStatementsSupport.formatDate(null));
  }

  /**
   * A business timestamp renders as the canonical wire datetime in the server's own zone —
   * no trailing {@code Z} (ETP-5100).
   *
   * <p>The input is built as a CIVIL value ({@link Timestamp#valueOf}, which reads the literal
   * in the default zone), not from a UTC {@code Instant}. That is what makes the assertion
   * timezone-independent: input and expectation denote the same wall-clock reading in whatever
   * zone the runner happens to be in. Asserting a UTC-rendered string against an instant — as
   * this test used to — passed in a UTC CI and failed in UTC-3.
   */
  @Test
  public void formatDateRendersCanonicalWireDatetimeInTheServerZone() {
    Timestamp ts = Timestamp.valueOf("2026-06-04 10:00:00");
    assertEquals("2026-06-04T10:00:00", BankStatementsSupport.formatDate(ts));
  }

  // ── parseIsoDate ─────────────────────────────────────────────────────────

  @Test
  public void parseIsoDateFallsBackOnBlank() {
    Date fallback = new Date(0L);
    assertSame(fallback, BankStatementsSupport.parseIsoDate("", fallback));
    assertSame(fallback, BankStatementsSupport.parseIsoDate(null, fallback));
  }

  @Test
  public void parseIsoDateFallsBackOnInvalid() {
    Date fallback = new Date(0L);
    assertSame(fallback, BankStatementsSupport.parseIsoDate("not-a-date", fallback));
  }

  @Test
  public void parseIsoDateAnchorsToServerMidnightOnTheSameCalendarDay() {
    // The frontend sends UTC midnight for the day the user picked (see ManualStatementModal.jsx's
    // toIsoUtc). The stored value must be midnight of that SAME calendar day in the server's own
    // timezone — not the verbatim UTC instant — so it survives round-tripping through a
    // `timestamp without time zone` column without shifting to the previous day on a
    // UTC-negative server (the ETP-4502 bug this guards against).
    Date expected = Date.from(LocalDate.of(2026, 6, 4).atStartOfDay(ZoneId.systemDefault()).toInstant());
    assertEquals(expected, BankStatementsSupport.parseIsoDate("2026-06-04T00:00:00Z", new Date(0L)));
  }

  @Test
  public void parseIsoDateUsesTheUtcCalendarDayEvenWithANonMidnightInstant() {
    // A non-midnight instant still anchors to whatever calendar day it falls on in UTC — the
    // frontend always sends midnight, but the parser's contract is defined by the UTC date, not
    // the time-of-day component.
    Date expected = Date.from(LocalDate.of(2026, 6, 4).atStartOfDay(ZoneId.systemDefault()).toInstant());
    assertEquals(expected, BankStatementsSupport.parseIsoDate("2026-06-04T23:59:59Z", new Date(0L)));
  }

  /**
   * A zone-LESS ISO datetime round-trips (ETP-5100).
   *
   * <p>Since the outbound formatters stopped appending {@code Z}, {@code 2026-06-04T10:00:00} is
   * the shape NEO itself emits — and the frontend echoes it straight back. {@code Instant.parse}
   * rejects it for want of an offset, so without the {@code yyyy-MM-dd}-prefix fallback the value
   * would silently collapse to the fallback. That fallback is {@code new Date()} at both
   * {@code BankStatementsHandler} call sites, i.e. it would substitute TODAY for the statement's
   * real day and look like nothing went wrong.
   */
  @Test
  public void parseIsoDateAcceptsAZonelessIsoDatetime() {
    Date expected = Date.from(LocalDate.of(2026, 6, 4).atStartOfDay(ZoneId.systemDefault()).toInstant());
    Date fallback = new Date(0L);
    Date actual = BankStatementsSupport.parseIsoDate("2026-06-04T10:00:00", fallback);
    assertNotSame("the zone-less shape must round-trip, not collapse to the fallback",
        fallback, actual);
    assertEquals(expected, actual);
  }

  /** A bare {@code yyyy-MM-dd} is the whole datum and resolves to local start-of-day. */
  @Test
  public void parseIsoDateAcceptsABareCalendarDay() {
    Date expected = Date.from(LocalDate.of(2026, 6, 4).atStartOfDay(ZoneId.systemDefault()).toInstant());
    Date fallback = new Date(0L);
    Date actual = BankStatementsSupport.parseIsoDate("2026-06-04", fallback);
    assertNotSame("a bare calendar day must round-trip, not collapse to the fallback",
        fallback, actual);
    assertEquals(expected, actual);
  }

  /**
   * The tolerance is a prefix fallback, not a licence to guess: input with no readable
   * {@code yyyy-MM-dd} prefix still returns the fallback rather than an invented day.
   */
  @Test
  public void parseIsoDateStillFallsBackWhenThereIsNoCalendarDayPrefix() {
    Date fallback = new Date(0L);
    assertSame(fallback, BankStatementsSupport.parseIsoDate("04-06-2026", fallback));
    assertSame(fallback, BankStatementsSupport.parseIsoDate("2026-06", fallback));
    assertSame(fallback, BankStatementsSupport.parseIsoDate("2026-13-40T10:00:00", fallback));
    assertSame(fallback, BankStatementsSupport.parseIsoDate("banana-time", fallback));
  }

  // ── parseAmount ──────────────────────────────────────────────────────────

  @Test
  public void parseAmountReturnsZeroForBlankOrInvalid() {
    assertEquals(0, BigDecimal.ZERO.compareTo(BankStatementsSupport.parseAmount(null)));
    assertEquals(0, BigDecimal.ZERO.compareTo(BankStatementsSupport.parseAmount("")));
    assertEquals(0, BigDecimal.ZERO.compareTo(BankStatementsSupport.parseAmount("abc")));
  }

  @Test
  public void parseAmountParsesAndTrims() {
    assertEquals(0, new BigDecimal("12.34").compareTo(BankStatementsSupport.parseAmount("  12.34 ")));
  }

  // ── truncate ─────────────────────────────────────────────────────────────

  @Test
  public void truncateLeavesShortStringUntouched() {
    assertEquals("abc", BankStatementsSupport.truncate("abc", 5));
  }

  @Test
  public void truncateCutsLongString() {
    assertEquals("abc", BankStatementsSupport.truncate("abcdef", 3));
  }
}
