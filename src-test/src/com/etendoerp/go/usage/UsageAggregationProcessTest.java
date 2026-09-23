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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
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
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class UsageAggregationProcessTest {

  private static Date midnight(int year, int month, int dayOfMonth) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth, 0, 0, 0);
    return cal.getTime();
  }

  /**
   * Runs doExecute with NO display date format configured, which is the plain unit-test case:
   * {@code configuredDateFormat()} finds no properties and returns null, so only ISO is accepted.
   */
  private static Outcome execute(Map<String, Object> params) throws Exception {
    return execute(params, properties -> {
      // Left deliberately unstubbed: getInstance() then answers null, configuredDateFormat()
      // catches the NPE and returns null. Stubbing the absence explicitly, rather than relying on
      // whatever Openbravo.properties happens to be on the test classpath, is what keeps the ISO
      // specs below deterministic on any developer machine.
    });
  }

  /** Runs doExecute on an instance whose {@code dateFormat.java} is the given pattern. */
  private static Outcome executeWithDisplayFormat(Map<String, Object> params, String pattern)
      throws Exception {
    return execute(params, properties -> {
      OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
      Properties configured = new Properties();
      configured.setProperty("dateFormat.java", pattern);
      properties.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      when(provider.getOpenbravoProperties()).thenReturn(configured);
    });
  }

  /** Runs doExecute on an instance whose properties cannot be read at all. */
  private static Outcome executeWithoutProperties(Map<String, Object> params) throws Exception {
    return execute(params, properties -> properties.when(OBPropertiesProvider::getInstance)
        .thenThrow(new IllegalStateException("properties are not available")));
  }

  /** Runs doExecute over a clean service result, against stubbed platform statics. */
  private static Outcome execute(Map<String, Object> params,
      Consumer<MockedStatic<OBPropertiesProvider>> propertiesStub) throws Exception {
    return execute(params, propertiesStub, new UsageAggregationResult());
  }

  /**
   * Runs doExecute against stubbed platform statics and an intercepted service that reports the
   * given result.
   *
   * <p>The {@code OBException} a failing run throws is CAUGHT here and handed back on the
   * {@link Outcome} rather than allowed to escape, because both halves of a failure now have to
   * be asserted together: the result set on the bundle (what the interactive popup shows) and the
   * throw (what makes {@code ProcessMonitor} record the scheduled run as an error). A helper that
   * let the throw escape could only ever assert one of them.
   */
  private static Outcome execute(Map<String, Object> params,
      Consumer<MockedStatic<OBPropertiesProvider>> propertiesStub,
      UsageAggregationResult serviceResult) throws Exception {
    ProcessBundle bundle = mock(ProcessBundle.class);
    when(bundle.getParams()).thenReturn(params);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OBPropertiesProvider> properties = mockStatic(OBPropertiesProvider.class);
        MockedConstruction<UsageAggregationService> construction =
            mockConstruction(UsageAggregationService.class, (service, context) -> {
              when(service.runForSettlingWindow()).thenReturn(serviceResult);
              when(service.run(any(), any())).thenReturn(serviceResult);
            })) {

      propertiesStub.accept(properties);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      OBException thrown = null;
      try {
        new UsageAggregationProcess().doExecute(bundle);
      } catch (OBException e) {
        thrown = e;
      }

      ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
      verify(bundle).setResult(result.capture());

      UsageAggregationService service =
          construction.constructed().isEmpty() ? null : construction.constructed().get(0);
      return new Outcome((OBError) result.getValue(), service, thrown, obDal);
    }
  }

  /** Runs doExecute over a settling-window run whose service reports the given result. */
  private static Outcome executeSettlingWindowReporting(UsageAggregationResult serviceResult)
      throws Exception {
    return execute(new LinkedHashMap<>(), properties -> {
    }, serviceResult);
  }

  /** What one doExecute did: what it reported, what it threw, and which run it requested. */
  private static final class Outcome {
    private final OBError error;
    private final UsageAggregationService service;
    private final OBException thrown;
    private final OBDal obDal;

    private Outcome(OBError error, UsageAggregationService service, OBException thrown,
        OBDal obDal) {
      this.error = error;
      this.service = service;
      this.thrown = thrown;
      this.obDal = obDal;
    }

    /**
     * A failure owes BOTH surfaces, so both are asserted every time.
     *
     * <p>The interactive popup reads the result the process set; a scheduled run ignores it
     * entirely and records ERROR only when an exception propagates out of {@code DefaultJob}
     * ({@code ProcessMonitor} line 174). A run that only set an error result was therefore
     * recorded as a SUCCESS in {@code AD_PROCESS_RUN} — a silent failure of the nightly job,
     * which is the one place nobody is watching. Asserting only the result, as this class used
     * to, would pass against exactly that.
     */
    void assertFailedWith(String fragment) {
      assertAll(() -> assertNotNull(error, "nothing was reported to the bundle at all"),
          () -> assertEquals("Error", error.getType(),
              "expected a failure result, was: " + error.getMessage()),
          () -> assertTrue(error.getMessage().contains(fragment),
              "message should explain the problem, was: " + error.getMessage()),
          () -> assertNotNull(thrown,
              "a failing run must also THROW, or the scheduled run is recorded as a success"),
          () -> assertEquals(error.getMessage(), thrown.getMessage(),
              "the thrown message is what the interactive launcher renders, so it must carry"
                  + " the same reason as the result"));
    }

    void assertSucceeded() {
      assertAll(
          () -> assertNull(thrown, "a successful run must return normally, not throw"),
          () -> assertEquals("Success", error.getType(),
              "expected a success result, was: " + error.getMessage()));
    }
  }

  /** How many times {@code needle} appears in {@code haystack}. */
  private static int occurrencesOf(String needle, String haystack) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
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
     * THE REGRESSION TEST, and it is about the NIGHTLY run, not the popup.
     *
     * <p>A partial run used to report Warning (and, before that, Success) and return normally.
     * That is invisible where it matters most: {@code ProcessMonitor} sets
     * {@code AD_PROCESS_RUN.STATUS} to ERROR only when an exception propagates out of
     * {@code DefaultJob} — {@code bundle.setResult} is not consulted for status at all. So a
     * night on which every resource failed was recorded as a successful run, and the only
     * evidence was a log line nobody reads. The process must therefore BOTH report and throw.
     *
     * <p>The counts are pinned too, not just the type. "How many resource-days already
     * committed" is the operator's signal for how much a re-run has left to do, and each
     * resource-day commits on its own, so the successful ones really are written.
     */
    @Test
    void aPartialRunIsReportedAsAnErrorAndThrowsSoTheScheduledRunIsNotRecordedAsASuccess()
        throws Exception {
      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addResource();
      withFailure.addFailure("BROKEN_RESOURCE", "could not resolve property: osted of: Invoice");

      Outcome outcome = executeSettlingWindowReporting(withFailure);

      assertAll(
          () -> assertEquals("Error", outcome.error.getType(),
              "a partial run is not a success and not a warning: " + outcome.error.getMessage()),
          () -> assertNotNull(outcome.thrown,
              "without the throw, ProcessMonitor records the nightly run as a success"),
          () -> assertTrue(outcome.error.getMessage().contains("BROKEN_RESOURCE"),
              "names the resource the operator has to fix: " + outcome.error.getMessage()),
          () -> assertTrue(
              outcome.error.getMessage().contains("could not resolve property: osted"),
              "and carries its reason: " + outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("1 succeeded"),
              "and says how much of the run is already written: " + outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("re-running"),
              "and that re-running is safe: " + outcome.error.getMessage()));
    }

    /**
     * The thrown message is not a second-class citizen: the generated interactive launcher
     * renders {@code ex.getMessage()} through {@code Utility.translateError} when a process
     * throws, so a throw carrying a bare "error" would leave the user with the same silence the
     * fix was for, while still recording ERROR for the scheduler.
     */
    @Test
    void theThrownMessageCarriesTheSameDetailAsTheReportedOne() throws Exception {
      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addResource();
      withFailure.addFailure("BROKEN_RESOURCE", "boom");

      Outcome outcome = executeSettlingWindowReporting(withFailure);

      assertAll(() -> assertNotNull(outcome.thrown),
          () -> assertEquals(outcome.error.getMessage(), outcome.thrown.getMessage()),
          () -> assertTrue(outcome.thrown.getMessage().contains("BROKEN_RESOURCE"),
              outcome.thrown.getMessage()),
          () -> assertTrue(outcome.thrown.getMessage().contains("1 succeeded"),
              outcome.thrown.getMessage()));
    }

    /**
     * Every failed resource is named. A message that stopped at the first one would send the
     * operator round the loop once per broken row, each time discovering another.
     */
    @Test
    void everyFailedResourceIsNamedWithItsOwnReason() throws Exception {
      UsageAggregationResult withFailures = new UsageAggregationResult();
      withFailures.addDay();
      withFailures.addResource();
      withFailures.addResource();
      withFailures.addFailure("FIRST_BROKEN", "no counter deployed");
      withFailures.addFailure("SECOND_BROKEN", "connection closed");

      Outcome outcome = executeSettlingWindowReporting(withFailures);

      assertAll(
          () -> assertTrue(outcome.error.getMessage().contains("FIRST_BROKEN"),
              outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("SECOND_BROKEN"),
              outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("no counter deployed"),
              outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("connection closed"),
              outcome.error.getMessage()));
    }

    /**
     * One resource failing on four days of a six-day window is ONE thing to fix, so it is named
     * once, with the first reason it gave. Repeating it per day would bury the second broken
     * resource under a wall of identical lines — and the first reason is the one that explains
     * the rest.
     *
     * <p>The resource-day COUNT still counts every occurrence: the operator needs to know four
     * days are missing even though there is only one row to repair. Both halves are asserted
     * because collapsing the count along with the name would be the easy wrong fix.
     */
    @Test
    void aResourceFailingOnSeveralDaysIsNamedOnceWithItsFirstReason() throws Exception {
      UsageAggregationResult repeated = new UsageAggregationResult();
      for (int day = 0; day < 4; day++) {
        repeated.addDay();
        repeated.addResource();
        repeated.addFailure("FLAKY_RESOURCE", "reason from day " + day);
      }

      Outcome outcome = executeSettlingWindowReporting(repeated);
      String message = outcome.error.getMessage();

      assertAll(
          () -> assertEquals(2, occurrencesOf("FLAKY_RESOURCE", message),
              "expected exactly two mentions -- once in the failed-resource list and once"
                  + " against its reason -- not one pair per day: " + message),
          () -> assertTrue(message.contains("reason from day 0"),
              "the first reason is the one that explains it: " + message),
          () -> assertFalse(message.contains("reason from day 3"),
              "later repeats of the same resource add nothing: " + message),
          () -> assertTrue(message.contains("4 failed"),
              "but all four resource-days are still counted as lost: " + message));
    }

    /**
     * A clean run returns normally and reports Success — the other half of the regression. A
     * process that threw on every run would record ERROR for the scheduler just as reliably, and
     * be just as useless.
     */
    @Test
    void aCleanRunReturnsNormallyAndReportsSuccess() throws Exception {
      UsageAggregationResult clean = new UsageAggregationResult();
      clean.addDay();
      clean.addResource();
      clean.addRows(7);

      Outcome outcome = executeSettlingWindowReporting(clean);

      assertAll(() -> assertNull(outcome.thrown, "a clean run must not throw"),
          () -> assertEquals("Success", outcome.error.getType()),
          () -> assertTrue(outcome.error.getMessage().contains("1 succeeded"),
              outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("7 usage row(s)"),
              outcome.error.getMessage()));
    }

    /**
     * A partial run has already flushed and committed the resource-days that worked, so it must
     * NOT roll back on its way out. Rolling back here would discard exactly the work the message
     * tells the operator is already written.
     */
    @Test
    void aPartialRunKeepsTheWorkThatSucceeded() throws Exception {
      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addResource();
      withFailure.addFailure("BROKEN_RESOURCE", "boom");

      Outcome outcome = executeSettlingWindowReporting(withFailure);

      assertAll(() -> verify(outcome.obDal).flush(),
          () -> verify(outcome.obDal, never()).rollbackAndClose());
    }

    /**
     * The wording this process COMPOSES carries no at-sign.
     *
     * <p>Openbravo parses '@' as its message-parameter delimiter, so a message containing one can
     * reach the user blank — the trap that already cost us the undeployed-qualifier message in
     * {@code UsageResourceValidator}. This failure message is on the same road: the interactive
     * launcher renders it through {@code Utility.translateError}.
     *
     * <p><b>This is a guarantee about our own text only, and deliberately not about the whole
     * message.</b> Two of its parts are values we do not own — the resource search keys, which an
     * administrator types into the catalog, and the failure reasons, which are exception messages
     * from anywhere (a Hibernate error quoting HQL, a CDI error naming an annotation). Asserting
     * that the composed result never contains '@' would be asserting something we cannot hold,
     * and the test would fail on a legitimate input rather than on a regression. So the fixture
     * supplies at-sign-free values, and what is pinned is that nobody introduces an at-sign into
     * the template around them. The dynamic halves are a known edge, recorded here rather than
     * silently assumed away.
     */
    @Test
    void theComposedFailureWordingContainsNoAtSign() throws Exception {
      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addResource();
      withFailure.addFailure("BROKEN_RESOURCE", "no counter is deployed for that qualifier");

      Outcome outcome = executeSettlingWindowReporting(withFailure);

      assertFalse(outcome.error.getMessage().contains("@"),
          "Openbravo parses '@' as a message parameter, so an at-sign in our own wording can"
              + " reach the user blank: " + outcome.error.getMessage());
    }

    /**
     * THE ASSERTION WE COULD NOT HOLD BEFORE, and can now.
     *
     * <p>Both dynamic halves of this message are values we do not own — the search key is typed
     * into the catalog by an administrator, the reason is an exception message from anywhere —
     * so until they were guarded, "the message contains no at-sign" was a promise the code could
     * not keep and a test asserting it would have failed on a legitimate input rather than on a
     * regression. Now that {@code UsageMessages.atSafe} guards both, the fixture can be
     * adversarial: every dynamic slot is fed a value that genuinely carries an at-sign.
     *
     * <p>The substituted forms are asserted as well as the absence of the character, because the
     * point is not to delete the operator's information. {@code BILLING(at)ACME} is still
     * recognisably their resource; a message that had merely dropped the at-sign would name a
     * resource called {@code BILLINGACME} that does not exist.
     */
    @Test
    void atSignsInTheSearchKeyAndTheReasonAreNeutralisedRatherThanLost() throws Exception {
      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addResource();
      withFailure.addFailure("BILLING@ACME",
          "No UsageResourceCounter deployed with @Named(\"active@users\")");

      Outcome outcome = executeSettlingWindowReporting(withFailure);
      String message = outcome.error.getMessage();

      assertAll(
          () -> assertFalse(message.contains("@"),
              "an at-sign anywhere can blank the whole message: " + message),
          () -> assertTrue(message.contains("BILLING(at)ACME"),
              "the resource must still be identifiable: " + message),
          () -> assertTrue(message.contains("(at)Named"),
              "and so must the reason: " + message),
          () -> assertFalse(message.contains("BILLINGACME"),
              "stripping instead of replacing would name a resource that does not exist: "
                  + message));
    }

    /**
     * The same guard on the thrown message, which is a different string in the user's eyes: the
     * interactive launcher renders {@code ex.getMessage()} through {@code Utility.translateError}
     * — the very method that parses at-signs — so a guard applied only to the reported result
     * would leave the thrown one exposed on the path that actually reaches the popup.
     */
    @Test
    void theThrownMessageIsAtSafeToo() throws Exception {
      UsageAggregationResult withFailure = new UsageAggregationResult();
      withFailure.addDay();
      withFailure.addResource();
      withFailure.addResource();
      withFailure.addFailure("BILLING@ACME", "boom @ 02:00");

      Outcome outcome = executeSettlingWindowReporting(withFailure);

      assertAll(() -> assertNotNull(outcome.thrown),
          () -> assertFalse(outcome.thrown.getMessage().contains("@"),
              outcome.thrown.getMessage()),
          () -> assertTrue(outcome.thrown.getMessage().contains("BILLING(at)ACME"),
              outcome.thrown.getMessage()));
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

        // The rejection now throws as well as reporting, so the throw is absorbed here; that it
        // happens at all is asserted by assertFailedWith on every rejection spec above.
        assertThrows(OBException.class, () -> new UsageAggregationProcess().doExecute(bundle));

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

        // Throwing is the point of the failing path; the finally block must still restore.
        assertThrows(OBException.class, () -> new UsageAggregationProcess().doExecute(bundle));

        obContext.verify(() -> OBContext.setAdminMode(false));
        obContext.verify(OBContext::restorePreviousMode);
      }
    }
  }

  /**
   * THE REGRESSION SUITE for a backfill that could not be launched from its own parameter window.
   *
   * <p>The window sends dates in the instance's display format ({@code dateFormat.java}; on the
   * instance where this surfaced, {@code dd-MM-yyyy}), and the process parsed a hardcoded
   * {@code yyyy-MM-dd}. So every launch from the UI died with {@code Unparseable date:
   * "08-09-2010"} and shadow mode — reviewing a past month before anyone is billed, the whole
   * reason the backfill exists — was unreachable by the only route an operator has.
   *
   * <p>Both accepted formats are asserted through the BOUNDS the service was asked to run over,
   * never through "it did not throw". That is the only assertion that distinguishes a correct
   * parse from a lenient one, and a lenient parse is the specific trap here: see
   * {@link #theDisplayFormatAttemptIsStrictSoAnIsoDateIsNotRolledYearsForward}.
   */
  @Nested
  @DisplayName("the date formats the window and a scheduled request send")
  class DateFormats {

    private static final String SPANISH_DISPLAY_FORMAT = "dd-MM-yyyy";

    /**
     * THE REGRESSION TEST. {@code 08-09-2010} in a {@code dd-MM-yyyy} instance is 8 September,
     * and the bounds prove it: read as {@code MM-dd} it would be 9 August, and a test that only
     * checked "the backfill ran" would accept either.
     */
    @Test
    void aDisplayFormatDateIsParsedTheWayTheWindowMeantIt() throws Exception {
      Outcome outcome = executeWithDisplayFormat(
          params("DateFrom", "08-09-2010", "DateTo", "08-09-2010"), SPANISH_DISPLAY_FORMAT);

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2010, 9, 8), midnight(2010, 9, 8));
    }

    /** A full display-format range, to pin that both parameters go through the same parse. */
    @Test
    void aDisplayFormatRangeBackfillsExactlyThatRange() throws Exception {
      Outcome outcome = executeWithDisplayFormat(
          params("DateFrom", "01-12-2011", "DateTo", "31-12-2011"), SPANISH_DISPLAY_FORMAT);

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2011, 12, 1), midnight(2011, 12, 31));
    }

    /**
     * The documented contract must survive the display format taking precedence: a scheduled
     * request's JSON parameters carry ISO, and this class's javadoc promises ISO is accepted.
     * Fixing the window by breaking the scheduler would simply move the outage.
     */
    @Test
    void anIsoDateStillParsesWhenADisplayFormatIsConfigured() throws Exception {
      Outcome outcome = executeWithDisplayFormat(
          params("DateFrom", "2011-01-01", "DateTo", "2011-01-31"), SPANISH_DISPLAY_FORMAT);

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2011, 1, 1), midnight(2011, 1, 31));
    }

    /**
     * THE TRAP, and the reason {@code OBDateUtils.getDate} was not used. That helper parses
     * leniently, and a lenient {@code dd-MM-yyyy} parse of {@code 2011-01-01} does NOT fail: it
     * takes day 2011 and rolls it forward into a date years away. The backfill would then cover a
     * range nobody asked for and report success — no exception, no warning, wrong data.
     *
     * <p>So the assertion is not "it parsed" but "it parsed to 1 January 2011 and specifically NOT
     * to what a lenient parse would have produced". The lenient value is computed here rather than
     * hardcoded, so the test states the property instead of a magic date, and would still be
     * meaningful if the rolled-forward arithmetic were different from what anyone expected.
     */
    @Test
    void theDisplayFormatAttemptIsStrictSoAnIsoDateIsNotRolledYearsForward() throws Exception {
      SimpleDateFormat lenientDisplayFormat = new SimpleDateFormat(SPANISH_DISPLAY_FORMAT);
      lenientDisplayFormat.setLenient(true);
      Date whatALenientParseWouldGive =
          UsageDayRange.startOfDay(lenientDisplayFormat.parse("2011-01-01"));

      Outcome outcome = executeWithDisplayFormat(
          params("DateFrom", "2011-01-01", "DateTo", "2011-01-31"), SPANISH_DISPLAY_FORMAT);

      ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
      ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
      verify(outcome.service).run(from.capture(), to.capture());

      assertAll(
          () -> assertEquals(midnight(2011, 1, 1), from.getValue(),
              "the ISO attempt must be the one that wins"),
          () -> assertNotEquals(whatALenientParseWouldGive, from.getValue(),
              "a lenient dd-MM-yyyy parse would silently backfill from "
                  + whatALenientParseWouldGive + " and report success"));
    }

    /**
     * A date that exists in neither format is rejected, and the message names BOTH accepted
     * formats — the operator cannot otherwise tell which of the two the instance expects, and
     * the whole defect was that the window's own format was not one of them.
     *
     * <p>{@code 31-02-2011} is the interesting member of the set: it matches the display format's
     * SHAPE and is still impossible, so it is rejected only because the parse is strict.
     */
    @ParameterizedTest(name = "date = {0}")
    @ValueSource(strings = { "2011-13-01", "31-02-2011", "garbage", "08/09/2010", "2011-01" })
    void aDateInNeitherFormatIsRejectedAndTheMessageNamesBothFormats(String text)
        throws Exception {
      Outcome outcome = executeWithDisplayFormat(params("DateFrom", text, "DateTo", text),
          SPANISH_DISPLAY_FORMAT);

      assertAll(() -> assertEquals("Error", outcome.error.getType(), outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains(SPANISH_DISPLAY_FORMAT),
              "names the window's format: " + outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains("yyyy-MM-dd"),
              "and the ISO one: " + outcome.error.getMessage()),
          () -> assertTrue(outcome.error.getMessage().contains(text),
              "and the text it could not read: " + outcome.error.getMessage()));
    }

    /**
     * The date text is free text the user types, so it is as exposed as any catalog field: a
     * parameter of {@code 08@09@2010} used to quote the at-signs straight back into the popup
     * that was supposed to explain the mistake. Replaced, not stripped — telling someone
     * {@code 08092010} is not a date, when they typed something else entirely, explains nothing.
     */
    @Test
    void anAtSignInTheDateTextIsNeutralisedRatherThanLost() throws Exception {
      Outcome outcome = executeWithDisplayFormat(
          params("DateFrom", "08@09@2010", "DateTo", "08@09@2010"), SPANISH_DISPLAY_FORMAT);
      String message = outcome.error.getMessage();

      assertAll(() -> assertEquals("Error", outcome.error.getType(), message),
          () -> assertFalse(message.contains("@"),
              "an at-sign here can blank the very message that explains the mistake: " + message),
          () -> assertTrue(message.contains("08(at)09(at)2010"),
              "the text they typed must stay recognisable: " + message),
          () -> assertFalse(message.contains("08092010"),
              "stripping would quote back something they never typed: " + message));
    }

    /**
     * The pairing rule is checked after parsing, so a valid display-format date supplied on its
     * own must still be refused rather than parsed and then half-used. Confirms the existing
     * behaviour survived the new parse path, on the path that had never been exercised with a
     * display-format value.
     */
    @Test
    void aDisplayFormatFromWithoutAToIsStillRejected() throws Exception {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("DateFrom", "08-09-2010");

      executeWithDisplayFormat(params, SPANISH_DISPLAY_FORMAT).assertFailedWith("Supply both");
    }

    /**
     * When the properties cannot be read at all — a unit test, an early-boot call — the
     * configured format resolves to null and must simply be skipped, leaving ISO working. A null
     * pattern handed to {@code SimpleDateFormat} would instead throw
     * {@code NullPointerException}, turning a missing optional format into a failed backfill.
     */
    @Test
    void isoStillParsesWhenThePropertiesAreUnavailable() throws Exception {
      Outcome outcome =
          executeWithoutProperties(params("DateFrom", "2011-01-01", "DateTo", "2011-01-31"));

      outcome.assertSucceeded();
      verify(outcome.service).run(midnight(2011, 1, 1), midnight(2011, 1, 31));
    }

    /** And a display-format date is then genuinely unreadable, rather than silently misread. */
    @Test
    void aDisplayFormatDateIsRejectedWhenThePropertiesAreUnavailable() throws Exception {
      Outcome outcome =
          executeWithoutProperties(params("DateFrom", "08-09-2010", "DateTo", "08-09-2010"));

      assertEquals("Error", outcome.error.getType(), outcome.error.getMessage());
    }
  }
}
