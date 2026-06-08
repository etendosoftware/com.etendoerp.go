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
import static org.junit.Assert.assertSame;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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

  @Test
  public void formatDateRendersIsoUtc() {
    Timestamp ts = Timestamp.from(Instant.parse("2026-06-04T10:00:00Z"));
    assertEquals("2026-06-04T10:00:00Z", BankStatementsSupport.formatDate(ts));
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
  public void parseIsoDateParsesValidInstant() {
    Date expected = Date.from(Instant.parse("2026-06-04T00:00:00Z"));
    assertEquals(expected, BankStatementsSupport.parseIsoDate("2026-06-04T00:00:00Z", new Date(0L)));
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
