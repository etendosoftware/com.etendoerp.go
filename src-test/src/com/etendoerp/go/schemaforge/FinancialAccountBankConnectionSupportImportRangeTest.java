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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

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
 *
 * <p>Also covers the import-settings validation shared by {@code link}, {@code createAndLink} and
 * {@code import-settings} ({@code requireImportSettings}, {@code parseImportDate},
 * {@code requireImportFromDate}, {@code requireStatementGrouping}, {@code ImportSettings}): a
 * missing or malformed import-from date and a missing or unknown statement grouping are business
 * errors ({@link OBException}, mapped to 400 by the handler) carrying the exact English texts the
 * SPA maps by literal match.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountBankConnectionSupport
 * @covers com.etendoerp.go.schemaforge.BankImportSettingsRules
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

  // ── import settings validation (link / createAndLink / import-settings) ──

  /** The four statement groupings the PSD2 module understands: per day, week, month, sync. */
  private static final List<String> VALID_GROUPINGS = List.of("1BD", "1BW", "1BM", "1BE");

  /**
   * A {@code link} / {@code createAndLink} body carrying only the two import settings.
   *
   * @param importFromDate the {@code importFromDate} value, or {@code null} to omit the key
   * @param grouping       the {@code statementGrouping} value, or {@code null} to omit the key
   * @return the body
   */
  private static JSONObject settingsBody(String importFromDate, String grouping) throws Exception {
    JSONObject body = new JSONObject();
    if (importFromDate != null) {
      body.put(BankImportSettingsRules.KEY_IMPORT_FROM_DATE, importFromDate);
    }
    if (grouping != null) {
      body.put(BankImportSettingsRules.KEY_STATEMENT_GROUPING, grouping);
    }
    return body;
  }

  /**
   * Runs {@code requireImportSettings} on {@code body}, which must be rejected.
   *
   * @param body the request body
   * @return the text of the business error
   */
  private static String rejectionOf(JSONObject body) {
    return businessErrorOf(() -> BankImportSettingsRules.requireImportSettings(body));
  }

  /**
   * Runs {@code call}, which must fail with a business error ({@link OBException}, the 400 of the
   * handler). Written by hand because JUnit 4.12, which the suite may run on, lacks the JUnit 4.13
   * assertion for it.
   *
   * @param call the call expected to throw
   * @return the text of the business error
   */
  private static String businessErrorOf(Runnable call) {
    try {
      call.run();
    } catch (OBException e) {
      return e.getMessage();
    }
    fail("expected an OBException");
    return null;
  }

  /** A complete body yields the day as UTC midnight and the grouping code unchanged. */
  @Test
  public void requireImportSettingsReturnsTheValidatedPair() throws Exception {
    BankImportSettingsRules.ImportSettings settings =
        BankImportSettingsRules.requireImportSettings(
            settingsBody("2026-01-15", "1BW"));

    assertEquals(day(2026, 1, 15), settings.getImportFrom());
    assertEquals("1BW", settings.getStatementGrouping());
  }

  /** Each of the four PSD2 grouping codes is accepted as is. */
  @Test
  public void requireImportSettingsAcceptsEveryPsd2Grouping() throws Exception {
    for (String grouping : VALID_GROUPINGS) {
      assertEquals(grouping, BankImportSettingsRules
          .requireImportSettings(settingsBody("2026-01-01", grouping)).getStatementGrouping());
    }
  }

  /** The accepted set is exactly the PSD2 module's four codes, read from its constants. */
  @Test
  public void statementGroupingsAreThePsd2Codes() {
    assertEquals(VALID_GROUPINGS, BankImportSettingsRules.STATEMENT_GROUPINGS);
  }

  /** No import-from date at all: required. */
  @Test
  public void requireImportSettingsRejectsAMissingImportFromDate() throws Exception {
    assertEquals(BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED,
        rejectionOf(settingsBody(null, "1BD")));
  }

  /** A blank import-from date is the same as a missing one, not a malformed one. */
  @Test
  public void requireImportSettingsRejectsABlankImportFromDateAsRequired() throws Exception {
    assertEquals(BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED,
        rejectionOf(settingsBody("   ", "1BD")));
  }

  /** A JSON null import-from date is the same as a missing one. */
  @Test
  public void requireImportSettingsRejectsANullImportFromDateAsRequired() throws Exception {
    JSONObject body = settingsBody(null, "1BD")
        .put(BankImportSettingsRules.KEY_IMPORT_FROM_DATE, JSONObject.NULL);
    assertEquals(BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED,
        rejectionOf(body));
  }

  /** A value that is not a {@code yyyy-MM-dd} day is a business error, not a parse exception. */
  @Test
  public void requireImportSettingsRejectsAMalformedImportFromDate() throws Exception {
    for (String malformed : List.of("15/01/2026", "2026-13-01", "2026-02-30", "yesterday")) {
      assertEquals(malformed, BankImportSettingsRules.MSG_IMPORT_FROM_DATE_INVALID,
          rejectionOf(settingsBody(malformed, "1BD")));
    }
  }

  /** No grouping at all: required. */
  @Test
  public void requireImportSettingsRejectsAMissingGrouping() throws Exception {
    assertEquals(BankImportSettingsRules.MSG_STATEMENT_GROUPING_REQUIRED,
        rejectionOf(settingsBody("2026-01-01", null)));
  }

  /** A blank grouping is the same as a missing one. */
  @Test
  public void requireImportSettingsRejectsABlankGroupingAsRequired() throws Exception {
    assertEquals(BankImportSettingsRules.MSG_STATEMENT_GROUPING_REQUIRED,
        rejectionOf(settingsBody("2026-01-01", "  ")));
  }

  /**
   * A grouping outside the four codes is refused. Before, any string was stored and only failed,
   * silently, on the next synchronization. {@code MONTHLY} is the value an older test sent.
   */
  @Test
  public void requireImportSettingsRejectsAnUnknownGrouping() throws Exception {
    for (String unknown : List.of("MONTHLY", "1BY", "BD")) {
      assertEquals(unknown, BankImportSettingsRules.MSG_STATEMENT_GROUPING_INVALID,
          rejectionOf(settingsBody("2026-01-01", unknown)));
    }
  }

  /** The date is checked before the grouping: a body wrong on both answers the date error. */
  @Test
  public void requireImportSettingsReportsTheDateFirst() throws Exception {
    assertEquals(BankImportSettingsRules.MSG_IMPORT_FROM_DATE_INVALID,
        rejectionOf(settingsBody("not-a-date", "MONTHLY")));
  }

  /** A valid day parses to UTC midnight, like {@code parseDate}. */
  @Test
  public void parseImportDateParsesAValidDay() {
    assertEquals(day(2026, 3, 31),
        BankImportSettingsRules.parseImportDate("2026-03-31", "unused"));
  }

  /** A blank value means "not sent": null, and the caller decides whether that is allowed. */
  @Test
  public void parseImportDateReturnsNullForABlankValue() {
    assertNull(BankImportSettingsRules.parseImportDate(null, "unused"));
    assertNull(BankImportSettingsRules.parseImportDate("  ", "unused"));
  }

  /** A malformed value throws a business error with the caller's own text (from vs to). */
  @Test
  public void parseImportDateThrowsTheGivenMessageForAMalformedValue() {
    assertEquals(BankImportSettingsRules.MSG_IMPORT_TO_DATE_INVALID,
        businessErrorOf(() -> BankImportSettingsRules.parseImportDate("2026-1-1",
            BankImportSettingsRules.MSG_IMPORT_TO_DATE_INVALID)));
  }

  /** A present import-from day is returned unchanged. */
  @Test
  public void requireImportFromDateReturnsAPresentDay() {
    Date from = day(2026, 1, 1);
    assertSame(from, BankImportSettingsRules.requireImportFromDate(from));
  }

  /** A null import-from day is refused. */
  @Test
  public void requireImportFromDateRejectsNull() {
    assertEquals(BankImportSettingsRules.MSG_IMPORT_FROM_DATE_REQUIRED,
        businessErrorOf(() -> BankImportSettingsRules.requireImportFromDate(null)));
  }

  /** Each valid code passes; null, blank and unknown codes are refused with their own text. */
  @Test
  public void requireStatementGroupingAcceptsOnlyThePsd2Codes() {
    for (String grouping : VALID_GROUPINGS) {
      assertEquals(grouping,
          BankImportSettingsRules.requireStatementGrouping(grouping));
    }
    for (String blank : new String[] {null, "", "  "}) {
      assertEquals(String.valueOf(blank),
          BankImportSettingsRules.MSG_STATEMENT_GROUPING_REQUIRED,
          businessErrorOf(
              () -> BankImportSettingsRules.requireStatementGrouping(blank)));
    }
    assertEquals(BankImportSettingsRules.MSG_STATEMENT_GROUPING_INVALID,
        businessErrorOf(
            () -> BankImportSettingsRules.requireStatementGrouping("WEEKLY")));
  }

  /** {@code applyTo} writes both settings on the account and nothing else (no save, no flush). */
  @Test
  public void importSettingsApplyToWritesBothSettings() {
    FIN_FinancialAccount finAcc = mock(FIN_FinancialAccount.class);
    Date from = day(2026, 2, 1);

    new BankImportSettingsRules.ImportSettings(from, "1BE").applyTo(finAcc);

    verify(finAcc).setPSD2ImportFromDate(from);
    verify(finAcc).setPSD2StatementFrequency("1BE");
    verifyNoMoreInteractions(finAcc);
  }
}
