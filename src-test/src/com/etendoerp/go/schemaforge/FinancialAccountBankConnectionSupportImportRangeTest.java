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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

import org.junit.Test;

/**
 * Unit tests for {@link FinancialAccountBankConnectionSupport#isImportRangeInvalid}, the ETP-5104
 * guard that rejects an inverted PSD2 import date range at the bridge with a 400 instead of letting
 * it be stored silently and blow up much later, during synchronization, inside the PSD2 module
 * ({@code SaltEdgeConnectionHelper.validateDateRange} re-wrapped as
 * {@code PSD2_ErrorRetrievingRransactionsForTheAccount}).
 *
 * Dates are built from fixed calendar days (or fixed epoch millis) so the assertions never depend on
 * the wall clock or on the host time zone. Day-based values are constructed exactly as
 * {@code FinancialAccountBankConnectionSupport.parseDate} builds them, at UTC start of day.
 */
public class FinancialAccountBankConnectionSupportImportRangeTest {

  private static Date day(int year, int month, int dayOfMonth) {
    return Date.from(LocalDate.of(year, month, dayOfMonth).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  @Test
  public void importFromAfterImportToIsInvalid() {
    assertTrue(FinancialAccountBankConnectionSupport.isImportRangeInvalid(day(2026, 3, 31), day(2026, 3, 1)));
  }

  @Test
  public void importFromBeforeImportToIsValid() {
    assertFalse(FinancialAccountBankConnectionSupport.isImportRangeInvalid(day(2026, 3, 1), day(2026, 3, 31)));
  }

  @Test
  public void sameInstantRangeIsValid() {
    // Mirrors the PSD2 module's own strict "compareTo(...) > 0" test: a single-day range is allowed.
    assertFalse(FinancialAccountBankConnectionSupport.isImportRangeInvalid(day(2026, 3, 15), day(2026, 3, 15)));
  }

  @Test
  public void nullImportFromIsValid() {
    // A missing lower bound means "no limit", not an inverted range.
    assertFalse(FinancialAccountBankConnectionSupport.isImportRangeInvalid(null, day(2026, 3, 1)));
  }

  @Test
  public void nullImportToIsValid() {
    // A missing upper bound means "no limit", not an inverted range.
    assertFalse(FinancialAccountBankConnectionSupport.isImportRangeInvalid(day(2026, 3, 1), null));
  }

  @Test
  public void bothBoundsNullAreValid() {
    assertFalse(FinancialAccountBankConnectionSupport.isImportRangeInvalid(null, null));
  }

  @Test
  public void oneMillisecondInversionIsInvalid() {
    // Proves the comparison keeps millisecond precision and is not truncated to the calendar day.
    Date importTo = new Date(1_772_323_200_000L);
    Date importFrom = new Date(importTo.getTime() + 1L);

    assertTrue(FinancialAccountBankConnectionSupport.isImportRangeInvalid(importFrom, importTo));
  }
}
