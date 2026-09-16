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

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;

/**
 * Unit specs for {@link UsageAggregationProcess} (ETP-5050).
 *
 * <p><b>How the private {@code readDate} is exercised, and why.</b> Through the public
 * {@code doExecute}, not by reflection. {@code readDate} has no observable behaviour of its own —
 * what matters is which of the two runs the process then performs (a backfill over the requested
 * range, or an ordinary settling-window run), and that decision is only visible at the
 * {@code doExecute} level. The shipped bug below is precisely a case where {@code readDate}
 * "worked" (it returned, without error) while the process did the wrong thing, so a reflective
 * test asserting a returned {@code Date} would have been weaker than the one-line fix it was
 * meant to guard. Reflection would also have tested a name, not a contract: renaming or inlining
 * the helper is a refactor, and should not break the spec.
 *
 * <p>Driving {@code doExecute} costs three test seams, all of them platform statics rather than
 * logic: {@code OBContext} (admin mode), {@code OBDal} (flush/rollback) and the construction of
 * {@link UsageAggregationService}, which the process news up directly. The service is intercepted
 * with {@code mockConstruction} so the assertion can be about WHICH run was requested and with
 * WHAT bounds, without a database.
 *
 * <p><b>The regression this class exists for.</b> A parameter key of {@code datefrom} — the real
 * AD column name, which is what reaches {@code getParams()} depending on how the process is
 * invoked — must be found. The shipped code looked up {@code "DateFrom"} and then
 * {@code "dateFrom"}, matched neither, read null, and turned a requested backfill into an ordinary
 * settling-window run: no error, a plausible-looking success message, and the month the operator
 * asked to review simply never computed. The lowercase case is therefore asserted explicitly, in
 * both the "which method was called" and the "with which bounds" senses.
 */
class UsageAggregationProcessTest {

  private static Date midnight(int year, int month, int dayOfMonth) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth, 0, 0, 0);
    return cal.getTime();
  }

  /** Runs doExecute against stubbed platform statics and an intercepted service. */
  private static Outcome execute(Map<String, Object> params) throws Exception {
    ProcessBundle bundle = mock(ProcessBundle.class);
    when(bundle.getParams()).thenReturn(params);

    OBDal obDal = mock(OBDal.class);
    UsageAggregationResult serviceResult = new UsageAggregationResult();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedConstruction<UsageAggregationService> construction =
            mockConstruction(UsageAggregationService.class, (service, context) -> {
              when(service.runForSettlingWindow()).thenReturn(serviceResult);
              when(service.run(any(), any())).thenReturn(serviceResult);
            })) {

      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      new UsageAggregationProcess().doExecute(bundle);

      ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
      verify(bundle).setResult(result.capture());

      UsageAggregationService service =
          construction.constructed().isEmpty() ? null : construction.constructed().get(0);
      return new Outcome((OBError) result.getValue(), service);
    }
  }

  /** What one doExecute did: the message it reported and the service run it requested. */
  private static final class Outcome {
    private final OBError error;
    private final UsageAggregationService service;

    private Outcome(OBError error, UsageAggregationService service) {
      this.error = error;
      this.service = service;
    }

    void assertFailedWith(String fragment) {
      assertAll(() -> assertEquals("Error", error.getType(), "expected a failure result"),
          () -> assertTrue(error.getMessage().contains(fragment),
              "message should explain the problem, was: " + error.getMessage()));
    }

    void assertSucceeded() {
      assertEquals("Success", error.getType(),
          "expected a success result, was: " + error.getMessage());
    }
  }

  private static Map<String, Object> params(String fromKey, Object fromValue, String toKey,
      Object toValue) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(fromKey, fromValue);
    params.put(toKey, toValue);
    return params;
  }

  @Nested
  @DisplayName("parameter name matching")
  class ParameterNameMatching {

    /**
     * THE REGRESSION TEST. {@code datefrom}/{@code dateto} is the AD column name and is what a
     * scheduled request hands over. Matching case-sensitively read null and silently downgraded
     * the backfill to a settling-window run — a failure with no error anywhere.
     */
    @Test
    void anAllLowercaseParameterKeyIsFoundAndBackfillsTheRequestedRange() throws Exception {
      Outcome outcome =
          execute(params("datefrom", "2026-03-01", "dateto", "2026-03-31"));

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2026, 3, 1), midnight(2026, 3, 31));
      verify(outcome.service, never()).runForSettlingWindow();
    }

    /**
     * Every spelling the platform can produce resolves to the same backfill. Asserting the bounds
     * as well as the method matters: a lookup that found the key but dropped the value would call
     * {@code run} with nulls and still "pass" a call-only assertion.
     */
    @ParameterizedTest(name = "keys: {0} / {1}")
    @CsvSource({ "DateFrom,DateTo", "datefrom,dateto", "dateFrom,dateTo", "DATEFROM,DATETO",
        "DateFROM,dateTO" })
    void theLookupIsCaseInsensitive(String fromKey, String toKey) throws Exception {
      Outcome outcome = execute(params(fromKey, "2026-03-01", toKey, "2026-03-31"));

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2026, 3, 1), midnight(2026, 3, 31));
    }

    /** A parameter map is not ordered; the match must not depend on which key comes first. */
    @Test
    void findsTheKeysAmongUnrelatedParameters() throws Exception {
      Map<String, Object> params = new HashMap<>();
      params.put("Command", "DEFAULT");
      params.put("dateto", "2026-03-31");
      params.put("AD_Process_ID", "ABC");
      params.put("datefrom", "2026-03-01");

      Outcome outcome = execute(params);

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2026, 3, 1), midnight(2026, 3, 31));
    }
  }

  @Nested
  @DisplayName("parameter values")
  class ParameterValues {

    /** The parameter window hands over a real Date, which must be truncated to its day. */
    @Test
    void aDateValuedParameterIsTruncatedToTheStartOfItsDay() throws Exception {
      Calendar cal = Calendar.getInstance();
      cal.clear();
      cal.set(2026, Calendar.MARCH, 1, 17, 45, 12);
      Date fromWithTime = cal.getTime();
      cal.clear();
      cal.set(2026, Calendar.MARCH, 31, 23, 59, 59);
      Date toWithTime = cal.getTime();

      Outcome outcome = execute(params("DateFrom", fromWithTime, "DateTo", toWithTime));

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2026, 3, 1), midnight(2026, 3, 31));
    }

    /**
     * A blank string is "not supplied", not "supplied and unparseable": a parameter window that
     * submits empty strings for untouched fields must produce an ordinary settling-window run,
     * not a parse error.
     */
    @Test
    void blankStringParametersAreTreatedAsAbsent() throws Exception {
      Outcome outcome = execute(params("DateFrom", "   ", "DateTo", ""));

      outcome.assertSucceeded();
      verify(outcome.service).runForSettlingWindow();
      verify(outcome.service, never()).run(any(), any());
    }

    @Test
    void anUnparseableDateIsReportedAsAFailure() throws Exception {
      Outcome outcome = execute(params("DateFrom", "01/03/2026", "DateTo", "31/03/2026"));
      assertEquals("Error", outcome.error.getType());
    }

    /**
     * Non-lenient parsing: {@code 2026-02-30} does not exist and must be rejected rather than
     * rolled forward to March, which would backfill a range the operator never asked for.
     */
    @Test
    void anImpossibleCalendarDateIsRejectedRatherThanRolledForward() throws Exception {
      Outcome outcome = execute(params("DateFrom", "2026-02-30", "DateTo", "2026-03-31"));
      assertEquals("Error", outcome.error.getType());
    }
  }

  @Nested
  @DisplayName("range validation")
  class RangeValidation {

    /**
     * Half a range is ambiguous — "from here to today"? "that one day"? — so it is refused rather
     * than guessed at, because guessing would produce a silently different backfill.
     */
    @Test
    void aFromWithoutAToIsRejected() throws Exception {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("DateFrom", "2026-03-01");

      execute(params).assertFailedWith("Supply both");
    }

    @Test
    void aToWithoutAFromIsRejected() throws Exception {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("DateTo", "2026-03-31");

      execute(params).assertFailedWith("Supply both");
    }

    @Test
    void anInvertedRangeIsRejected() throws Exception {
      execute(params("DateFrom", "2026-03-31", "DateTo", "2026-03-01"))
          .assertFailedWith("must not be after");
    }

    /**
     * A failure message must not read as "nothing happened". Each resource-day commits on its
     * own, so a run that dies partway has already written every day it completed. An operator
     * who believes the run was fully rolled back would go looking for missing data that is
     * already there — and the actual remedy, re-running the same range, is safe precisely
     * because every day is idempotent. Both facts belong in the message, so both are asserted.
     */
    @Test
    void aFailureMessageSaysThatCompletedDaysAreWrittenAndRerunningIsSafe() throws Exception {
      Outcome outcome = execute(params("DateFrom", "2026-03-31", "DateTo", "2026-03-01"));

      assertAll(
          () -> assertTrue(outcome.error.getMessage().contains("already written"),
              outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("re-running"),
              outcome.error.getMessage()));
    }

    /** A single-day backfill is a legitimate range, not an inverted one. */
    @Test
    void aSingleDayRangeIsAccepted() throws Exception {
      Outcome outcome = execute(params("DateFrom", "2026-03-01", "DateTo", "2026-03-01"));

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2026, 3, 1), midnight(2026, 3, 1));
    }

    /**
     * Neither parameter is the nightly schedule's own invocation: it must take the
     * settling-window path, and must NOT be confused with a zero-length backfill.
     */
    @Test
    void noParametersAtAllRunsTheSettlingWindow() throws Exception {
      Outcome outcome = execute(new LinkedHashMap<>());

      outcome.assertSucceeded();
      verify(outcome.service).runForSettlingWindow();
      verify(outcome.service, never()).run(any(), any());
    }

    @Test
    void nullValuedParametersRunTheSettlingWindow() throws Exception {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("DateFrom", null);
      params.put("DateTo", null);

      Outcome outcome = execute(params);

      outcome.assertSucceeded();
      verify(outcome.service).runForSettlingWindow();
    }
  }

  @Nested
  @DisplayName("the result reported to the operator")
  class ResultReporting {

    /**
     * A run in which some resource failed reports Warning, not Success. The distinction is what
     * tells an operator that the numbers they are about to look at are incomplete — the run does
     * not abort on one bad resource, so nothing else would say so.
     */
    @Test
    void aRunWithAFailedResourceIsReportedAsAWarning() throws Exception {
      ProcessBundle bundle = mock(ProcessBundle.class);
      when(bundle.getParams()).thenReturn(new LinkedHashMap<>());
      OBDal obDal = mock(OBDal.class);

      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addFailure();

      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
          MockedConstruction<UsageAggregationService> construction =
              mockConstruction(UsageAggregationService.class,
                  (service, context) -> when(service.runForSettlingWindow())
                      .thenReturn(withFailure))) {
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        new UsageAggregationProcess().doExecute(bundle);

        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(bundle).setResult(result.capture());
        OBError reported = (OBError) result.getValue();

        assertAll(() -> assertEquals("Warning", reported.getType()),
            () -> assertTrue(reported.getMessage().contains("failed"), reported.getMessage()));
      }
    }

    /**
     * A failure must leave nothing half-written. The process owns the transaction boundary, so
     * the rollback is asserted rather than assumed.
     */
    @Test
    void aRejectedRangeRollsBackAndDoesNotFlush() throws Exception {
      ProcessBundle bundle = mock(ProcessBundle.class);
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("DateFrom", "2026-03-31");
      params.put("DateTo", "2026-03-01");
      when(bundle.getParams()).thenReturn(params);

      OBDal obDal = mock(OBDal.class);
      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        new UsageAggregationProcess().doExecute(bundle);

        assertAll(() -> verify(obDal).rollbackAndClose(),
            () -> verify(obDal, never()).flush());
      }
    }

    /**
     * Admin mode is entered because the run reads across tenants, and it must be restored on
     * every path — including the failing one, or the caller's context would be left elevated.
     */
    @Test
    void adminModeIsRestoredEvenWhenTheRunFails() throws Exception {
      ProcessBundle bundle = mock(ProcessBundle.class);
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("DateFrom", "2026-03-01");
      when(bundle.getParams()).thenReturn(params);

      OBDal obDal = mock(OBDal.class);
      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

        new UsageAggregationProcess().doExecute(bundle);

        obContext.verify(() -> OBContext.setAdminMode(false));
        obContext.verify(OBContext::restorePreviousMode);
      }
    }
  }
}
