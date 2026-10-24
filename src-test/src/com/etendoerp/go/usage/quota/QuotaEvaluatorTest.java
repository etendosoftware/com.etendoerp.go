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

package com.etendoerp.go.usage.quota;

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.OTHER_CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.local;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.midnight;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.quota;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.tenant;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.tenantWithPeriod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.usage.quota.QuotaEvaluator.Scope;
import com.etendoerp.go.usage.quota.QuotaTestFixtures.MutableClock;

/**
 * The quota evaluator: which quotas are measured, over which window, and what it decides.
 *
 * <p>{@link ConsumptionReader} is the only place a consumption query runs, so a mock reader that
 * is never called is the proof that no query ran. The lookup and the clock are injected too, so
 * nothing here needs a database.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaEvaluator
 * @covers com.etendoerp.go.usage.quota.QuotaEvaluation
 * @covers com.etendoerp.go.usage.quota.TenantQuotas
 * @covers com.etendoerp.go.usage.quota.QuotaLock
 */
@SuppressWarnings("java:S2187")
class QuotaEvaluatorTest {

  private QuotaLookup lookup;
  private ConsumptionReader reader;
  private MutableClock clock;
  private QuotaEvaluator evaluator;

  @BeforeEach
  void setUp() {
    lookup = mock(QuotaLookup.class);
    reader = mock(ConsumptionReader.class);
    clock = new MutableClock(NOW);
    evaluator = new QuotaEvaluator(lookup, reader, clock);
  }

  private QuotaEvaluation only(List<QuotaEvaluation> evaluations) {
    assertEquals(1, evaluations.size(), String.valueOf(evaluations));
    return evaluations.get(0);
  }

  /**
   * REPRO (ETP-5051 re-review W2): on an isolated worker whose caller stopped waiting (interrupted
   * by {@code future.cancel(true)}), the evaluation stops before its next consumption query.
   */
  @Test
  void anInterruptedEvaluationStopsBeforeItsNextConsumptionQuery() {
    QuotaDefinition first = quota("DOCS", "block", 100L, 80L).build();
    QuotaDefinition second = quota("USERS", "block", 100L, 80L).build();
    when(reader.read(eq(first), eq(CLIENT), any())).thenAnswer(invocation -> {
      Thread.currentThread().interrupt();
      return 1L;
    });
    try {
      TenantQuotas tenant = tenant(CLIENT, first, second);
      assertThrows(CancellationException.class,
          () -> evaluator.evaluate(tenant, Scope.ACCOUNT));

      verify(reader, never()).read(eq(second), anyString(), any());
    } finally {
      Thread.interrupted();
    }
  }

  @Nested
  @DisplayName("mode x consumption matrix, carried numbers included")
  class Matrix {

    @ParameterizedTest(name = "{0}, {1} included, threshold {2}, consumed {3} -> {4}")
    @CsvSource(nullValues = "NULL", value = {
        // mode, included, threshold, consumed, verdict, measured
        "off,    100,  80,    50,  NULL,  false",
        "off,    100,  80,   150,  NULL,  false",
        "warn,   100,  80,    50,  ALLOW, true",
        "warn,   100,  80,    80,  WARN,  true",
        "warn,   100,  80,   100,  WARN,  true",
        "warn,   100,  80,   150,  WARN,  true",
        "warn,   100,  NULL,  80,  ALLOW, true",
        "warn,   100,  NULL, 100,  WARN,  true",
        "block,  100,  80,    50,  ALLOW, true",
        "block,  100,  80,    80,  WARN,  true",
        "block,  100,  80,   100,  DENY,  true",
        "block,  100,  80,   150,  DENY,  true",
        "block,  100,  NULL,  99,  ALLOW, true",
        "block,  100,  NULL, 100,  DENY,  true",
        "block,    0,  80,     0,  DENY,  true",
        "warn,     0,  NULL,   0,  WARN,  true",
    })
    void verdictAndNumbers(String mode, long included, Long threshold, long consumed,
        QuotaVerdict verdict, boolean measured) {
      QuotaDefinition definition = quota("DOCS", mode, included, threshold).build();
      when(reader.read(eq(definition), eq(CLIENT), any())).thenReturn(consumed);
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, definition));

      QuotaEvaluation evaluation = only(evaluator.evaluateTenant(CLIENT, Scope.ENFORCEMENT));

      assertEquals(verdict, evaluation.getVerdict());
      assertEquals(measured ? Long.valueOf(consumed) : null, evaluation.getConsumed());
      assertSame(definition, evaluation.getQuota());
      assertEquals(Long.valueOf(included), evaluation.getQuota().getIncludedQty());
      assertEquals(threshold, evaluation.getQuota().getWarningThreshold());
      assertEquals("month", evaluation.getPlanInterval());
      assertEquals(PERIOD_START, evaluation.getPeriod().getStart());
      assertEquals(PERIOD_END, evaluation.getPeriod().getEnd());
      assertEquals(verdict == QuotaVerdict.WARN || verdict == QuotaVerdict.DENY,
          evaluation.isReportable());
      verify(reader, times(measured ? 1 : 0)).read(any(), anyString(), any());
    }

    @Test
    void aTenantWithNoQuotaRowIsUnlimitedAndEvaluatesNothing() {
      when(lookup.forTenant(CLIENT)).thenReturn(TenantQuotas.none(CLIENT));

      assertTrue(evaluator.evaluateTenant(CLIENT, Scope.ENFORCEMENT).isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void evaluationsKeepTheCatalogOrder() {
      QuotaDefinition first = quota("A", "block", 10L, null).build();
      QuotaDefinition second = quota("B", "warn", 10L, null).build();
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT, first, second));

      List<QuotaEvaluation> evaluations = evaluator.evaluateTenant(CLIENT, Scope.ENFORCEMENT);

      assertEquals("A", evaluations.get(0).getQuota().getResourceKey());
      assertEquals("B", evaluations.get(1).getQuota().getResourceKey());
    }
  }

  @Nested
  @DisplayName("no quota rows means zero consumption queries")
  class NoConsumptionQuery {

    @Test
    void noSubscriptionRow() {
      when(lookup.forTenant(CLIENT)).thenReturn(TenantQuotas.none(CLIENT));

      assertTrue(evaluator.evaluateTenant(CLIENT, Scope.ACCOUNT).isEmpty());
      assertNull(evaluator.writeCheckPeriod(TenantQuotas.none(CLIENT)));
      verifyNoInteractions(reader);
    }

    @Test
    void aPlanWhoseRowsAreAllInactiveListsNone() {
      // The lookup lists ACTIVE rows only; a plan whose rows are all inactive arrives empty.
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT));

      assertTrue(evaluator.evaluateTenant(CLIENT, Scope.ENFORCEMENT).isEmpty());
      assertTrue(evaluator.evaluateTenant(CLIENT, Scope.ACCOUNT).isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void aModeOffRowAtAnEnforcementPoint() {
      when(lookup.forTenant(CLIENT)).thenReturn(tenant(CLIENT,
          quota("DOCS", "off", 100L, 80L).build(), quota("USERS", " OFF ", 3L, null).build()));

      List<QuotaEvaluation> evaluations = evaluator.evaluateTenant(CLIENT, Scope.ENFORCEMENT);

      assertEquals(2, evaluations.size());
      for (QuotaEvaluation evaluation : evaluations) {
        assertNull(evaluation.getConsumed());
        assertNull(evaluation.getVerdict());
        assertFalse(evaluation.isReportable());
      }
      verifyNoInteractions(reader);
    }

    @Test
    void aBlankTenantIsNotEvenLookedUp() {
      assertTrue(evaluator.evaluateTenant(" ", Scope.ENFORCEMENT).isEmpty());
      assertTrue(evaluator.evaluateTenant(null, Scope.ENFORCEMENT).isEmpty());
      verifyNoInteractions(lookup, reader);
    }

    @Test
    void aNullTenantEvaluatesNothing() {
      assertTrue(evaluator.evaluate(null, Scope.ACCOUNT).isEmpty());
      verifyNoInteractions(reader);
    }
  }

  @Nested
  @DisplayName("the account scope measures mode off too")
  class AccountScope {

    @Test
    void aModeOffRowIsMeasuredWithANullVerdict() {
      QuotaDefinition off = quota("USERS", "off", 3L, 2L).build();
      when(reader.read(eq(off), eq(CLIENT), any())).thenReturn(7L);

      QuotaEvaluation evaluation = only(evaluator.evaluate(tenant(CLIENT, off), Scope.ACCOUNT));

      assertEquals(Long.valueOf(7L), evaluation.getConsumed());
      assertNull(evaluation.getVerdict());
      assertFalse(evaluation.isReportable());
    }

    @Test
    void anEnforcedRowGetsItsVerdictToo() {
      QuotaDefinition block = quota("DOCS", "block", 10L, null).build();
      when(reader.read(eq(block), eq(CLIENT), any())).thenReturn(10L);

      QuotaEvaluation evaluation = only(evaluator.evaluate(tenant(CLIENT, block), Scope.ACCOUNT));

      assertEquals(QuotaVerdict.DENY, evaluation.getVerdict());
    }
  }

  @Nested
  @DisplayName("the consumption window is the subscription period")
  class SubscriptionPeriod {

    private QuotaPeriod measuredPeriod(TenantQuotas tenant) {
      ArgumentCaptor<QuotaPeriod> period = ArgumentCaptor.forClass(QuotaPeriod.class);
      evaluator.evaluate(tenant, Scope.ENFORCEMENT);
      verify(reader).read(any(), eq(tenant.getClientId()), period.capture());
      return period.getValue();
    }

    @Test
    void theReaderIsGivenTheSeventeenthToTheSeventeenthNotTheCalendarMonth() {
      QuotaPeriod period = measuredPeriod(tenant(CLIENT, quota("DOCS", "block", 10L, null)
          .build()));

      assertEquals(PERIOD_START, period.getStart());
      assertEquals(PERIOD_END, period.getEnd());
      assertEquals(midnight(2026, 10, 17), period.getWindowFrom());
      assertEquals(midnight(2026, 11, 17), period.getWindowTo());
    }

    @Test
    void theStartInstantIsInsideThePeriod() {
      clock.set(PERIOD_START);

      QuotaPeriod period = measuredPeriod(tenant(CLIENT, quota("DOCS", "block", 10L, null)
          .build()));

      assertEquals(PERIOD_START, period.getStart());
    }

    @Test
    void theEndInstantIsAlreadyOutsideIt() {
      clock.set(PERIOD_END);

      QuotaEvaluation evaluation = only(evaluator.evaluate(
          tenant(CLIENT, quota("DOCS", "block", 10L, null).build()), Scope.ENFORCEMENT));

      assertNull(evaluation.getPeriod());
      assertNull(evaluation.getConsumed());
      verifyNoInteractions(reader);
    }
  }

  @Nested
  @DisplayName("no current period: ALLOW, nothing measured, one throttled WARN")
  class NoCurrentPeriod {

    private TestLogCapture logs;

    @BeforeEach
    void attach() {
      QuotaEvaluator.resetPeriodlessWarnings();
      logs = TestLogCapture.attachTo(QuotaEvaluator.class, Level.WARN);
    }

    @AfterEach
    void detach() {
      logs.detach();
    }

    private void assertAllowedUnmeasured(TenantQuotas tenant) {
      QuotaEvaluation evaluation = only(evaluator.evaluate(tenant, Scope.ENFORCEMENT));
      assertNull(evaluation.getPeriod());
      assertNull(evaluation.getConsumed());
      assertNull(evaluation.getVerdict());
      assertFalse(evaluation.isReportable());
      assertNull(evaluator.writeCheckPeriod(tenant), "no period: the create is not checked");
      verifyNoInteractions(reader);
    }

    private QuotaDefinition overQuota() {
      return quota("DOCS", "block", 0L, null).build();
    }

    @Test
    void aPeriodlessRow() {
      assertAllowedUnmeasured(tenantWithPeriod(CLIENT, null, null, false, overQuota()));
    }

    @Test
    void aRowWithOnlyAnEnd() {
      assertAllowedUnmeasured(tenantWithPeriod(CLIENT, null, PERIOD_END, false, overQuota()));
    }

    @Test
    void aClosedRow() {
      assertAllowedUnmeasured(tenantWithPeriod(CLIENT, PERIOD_START, PERIOD_END, true,
          overQuota()));
    }

    @Test
    void nowBeforeThePeriod() {
      clock.set(local(2026, 10, 1, 0, 0));
      assertAllowedUnmeasured(tenant(CLIENT, overQuota()));
    }

    @Test
    void nowAfterThePeriodARenewalNotYetRecorded() {
      clock.set(local(2026, 11, 20, 0, 0));
      assertAllowedUnmeasured(tenant(CLIENT, overQuota()));
    }

    @Test
    void exactlyOneWarnPerTenantWhileThrottled() {
      TenantQuotas periodless = tenantWithPeriod(CLIENT, null, null, false, overQuota());

      evaluator.evaluate(periodless, Scope.ENFORCEMENT);
      evaluator.evaluate(periodless, Scope.ACCOUNT);
      evaluator.writeCheckPeriod(periodless);

      List<String> warnings = logs.messagesAt(Level.WARN);
      assertEquals(1, warnings.size(), String.valueOf(warnings));
      assertTrue(warnings.get(0).startsWith("[QUOTA] Tenant " + CLIENT), warnings.get(0));
    }

    /**
     * REPRO (ETP-5051 QA BUG-1): the environment list, environment entry and the write guard each
     * hold their own evaluator. The WARN is still one line per tenant per hour across all of them.
     */
    @Test
    void exactlyOneWarnPerTenantAcrossEvaluators() {
      TenantQuotas periodless = tenantWithPeriod(CLIENT, null, null, false, overQuota());
      QuotaEvaluator report = new QuotaEvaluator(lookup, reader, clock);
      QuotaEvaluator writeCheck = new QuotaEvaluator(lookup, reader, clock);

      evaluator.evaluate(periodless, Scope.ENFORCEMENT);
      report.evaluate(periodless, Scope.ACCOUNT);
      writeCheck.writeCheckPeriod(periodless);

      assertEquals(1, logs.messagesAt(Level.WARN).size(),
          String.valueOf(logs.messagesAt(Level.WARN)));
    }

    @Test
    void anotherTenantGetsItsOwnWarn() {
      evaluator.evaluate(tenantWithPeriod(CLIENT, null, null, false, overQuota()),
          Scope.ENFORCEMENT);
      evaluator.evaluate(tenantWithPeriod(OTHER_CLIENT, null, null, false, overQuota()),
          Scope.ENFORCEMENT);

      List<String> warnings = logs.messagesAt(Level.WARN);
      assertEquals(2, warnings.size(), String.valueOf(warnings));
      assertTrue(warnings.get(1).contains(OTHER_CLIENT), warnings.get(1));
    }

    @Test
    void theWarnRepeatsOnceTheHourHasPassed() {
      TenantQuotas periodless = tenantWithPeriod(CLIENT, null, null, false, overQuota());
      evaluator.evaluate(periodless, Scope.ENFORCEMENT);
      clock.advanceMillis(3_599_999L);
      evaluator.evaluate(periodless, Scope.ENFORCEMENT);
      assertEquals(1, logs.messagesAt(Level.WARN).size());

      clock.advanceMillis(1L);
      evaluator.evaluate(periodless, Scope.ENFORCEMENT);

      assertEquals(2, logs.messagesAt(Level.WARN).size());
    }

    @Test
    void aClosedRowIsNeverWarnedAbout() {
      TenantQuotas closed = tenantWithPeriod(CLIENT, PERIOD_START, PERIOD_END, true, overQuota());

      evaluator.evaluate(closed, Scope.ENFORCEMENT);
      evaluator.writeCheckPeriod(closed);

      assertTrue(logs.messagesAt(Level.WARN).isEmpty(), String.valueOf(logs.messagesAt(Level.WARN)));
    }

    @Test
    void aTenantWithNoQuotaIsNotWarnedAbout() {
      evaluator.evaluate(tenantWithPeriod(CLIENT, null, null, false), Scope.ENFORCEMENT);

      assertTrue(logs.messagesAt(Level.WARN).isEmpty());
    }
  }

  @Nested
  @DisplayName("the write path compares the consumption before the create")
  class WritePath {

    private final QuotaPeriod period = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);
    private final List<String> created = List.of("ROW-1");

    private Optional<QuotaEvaluation> refusal(QuotaEvaluator on, QuotaDefinition quota,
        long chargedEarlier) {
      return on.refusalForCreate(tenant(CLIENT, quota), quota, period, created, chargedEarlier);
    }

    private void before(QuotaDefinition quota, long consumed) {
      when(reader.readBeforeCreate(eq(quota), eq(CLIENT), any(), eq(created)))
          .thenReturn(consumed);
    }

    /**
     * ETP-5051 re-review W2: the comparison is on the consumption before the create (the created
     * rows left out of a live count; the aggregate never has them), for either source the same:
     * refused when it plus the new row exceeds the included quantity.
     */
    @ParameterizedTest(name = "{0} {1} before, {2} included -> refused {3}")
    @CsvSource(value = {
        "LIVE,        99,  100,  false",
        "LIVE,       100,  100,  true",
        "LIVE,       150,  100,  true",
        "LIVE,         0,  100,  false",
        "LIVE,         0,    0,  true",
        "AGGREGATE,   99,  100,  false",
        "AGGREGATE,  100,  100,  true",
        "AGGREGATE,    0,    0,  true",
    })
    void theConsumptionBeforeTheCreatePlusOneIsCompared(ConsumptionSource source, long before,
        long included, boolean refused) {
      QuotaDefinition definition = quota("DOCS", "block", included, 80L).source(source).build();
      before(definition, before);

      Optional<QuotaEvaluation> refusal = refusal(evaluator, definition, 0L);

      assertEquals(refused, refusal.isPresent(), String.valueOf(refusal));
      if (refused) {
        QuotaEvaluation evaluation = refusal.get();
        assertEquals(QuotaVerdict.DENY, evaluation.getVerdict());
        assertEquals(Long.valueOf(before), evaluation.getConsumed());
        assertSame(definition, evaluation.getQuota());
        assertEquals(PERIOD_START, evaluation.getPeriod().getStart());
        assertEquals("month", evaluation.getPlanInterval());
      }
      verify(reader, never()).read(any(), anyString(), any());
    }

    /**
     * ETP-5051 re-review W2: the period's last, partial day is no longer special-cased — the row's
     * own date decides (the matcher), and the count before the create is the same at any hour.
     */
    @Test
    void theLastPartialDayIsNotSpecialCased() {
      QuotaDefinition definition = quota("DOCS", "block", 100L, null)
          .source(ConsumptionSource.LIVE).build();
      before(definition, 99L);

      clock.set(local(2026, 11, 17, 8, 0));
      assertTrue(refusal(evaluator, definition, 0L).isEmpty());
      clock.set(local(2026, 11, 16, 23, 59));
      assertTrue(refusal(evaluator, definition, 0L).isEmpty());
    }

    @Test
    void rowsAlreadyChargedInTheSameCreateCount() {
      QuotaDefinition definition = quota("DOCS", "block", 10L, null).build();
      before(definition, 8L);

      assertTrue(refusal(evaluator, definition, 1L).isEmpty());
      Optional<QuotaEvaluation> second = refusal(evaluator, definition, 2L);
      assertTrue(second.isPresent());
      assertEquals(Long.valueOf(10L), second.get().getConsumed());
    }

    @Test
    void reachingTheWarningThresholdDoesNotRefuse() {
      QuotaDefinition definition = quota("DOCS", "block", 100L, 80L).build();
      before(definition, 95L);

      assertTrue(refusal(evaluator, definition, 0L).isEmpty());
    }

    @Test
    void warnAndOffModeQuotasNeverRefuseAndAreNotMeasured() {
      assertTrue(refusal(evaluator, quota("DOCS", "warn", 0L, null).build(), 0L).isEmpty());
      assertTrue(refusal(evaluator, quota("DOCS", "off", 0L, null).build(), 0L).isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void missingInputsRefuseNothingAndMeasureNothing() {
      QuotaDefinition docs = quota("DOCS", "block", 0L, null).build();

      assertTrue(evaluator.refusalForCreate(null, docs, period, created, 0L).isEmpty());
      assertTrue(evaluator.refusalForCreate(tenant(CLIENT, docs), null, period, created, 0L)
          .isEmpty());
      assertTrue(evaluator.refusalForCreate(tenant(CLIENT, docs), docs, null, created, 0L)
          .isEmpty());
      verifyNoInteractions(reader);
    }

    @Test
    void theCurrentPeriodOfTheWriteCheck() {
      assertEquals(period, evaluator.writeCheckPeriod(tenant(CLIENT)));
      assertNull(evaluator.writeCheckPeriod(null));
    }
  }

  /**
   * ETP-5051 review W4: a live block check serialises concurrent creates of one tenant and
   * resource with the advisory lock, taken right before the live count; nothing else takes it.
   */
  @Nested
  @DisplayName("the write check locks only a live block quota")
  class WriteLock {

    private final QuotaPeriod period = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);
    private QuotaLock lock;
    private QuotaEvaluator locking;

    @BeforeEach
    void setUpLock() {
      lock = mock(QuotaLock.class);
      locking = new QuotaEvaluator(lookup, reader, clock, lock);
    }

    private void check(QuotaDefinition quota) {
      locking.refusalForCreate(tenant(CLIENT, quota), quota, period, List.of("ROW-1"), 0L);
    }

    @Test
    void aLiveBlockQuotaTakesTheTenantResourceLockBeforeTheCount() {
      QuotaDefinition definition = quota("DOCS", "block", 100L, null)
          .source(ConsumptionSource.LIVE).build();
      when(reader.readBeforeCreate(eq(definition), eq(CLIENT), any(), any())).thenReturn(1L);

      check(definition);

      org.mockito.InOrder order = org.mockito.Mockito.inOrder(lock, reader);
      order.verify(lock).acquire(CLIENT, "RES-DOCS");
      order.verify(reader).readBeforeCreate(eq(definition), eq(CLIENT), any(), any());
    }

    @Test
    void theStoredAggregateTakesNoLock() {
      check(quota("DOCS", "block", 100L, null).source(ConsumptionSource.AGGREGATE).build());

      verifyNoInteractions(lock);
    }

    @Test
    void aNonBlockQuotaTakesNoLock() {
      check(quota("DOCS", "warn", 100L, null).source(ConsumptionSource.LIVE).build());

      verifyNoInteractions(lock);
    }

    @Test
    void theLockKeyIsStableAndDistinctPerTenantAndResource() {
      long key = QuotaLock.key(CLIENT, "RES-DOCS");
      assertEquals(key, QuotaLock.key(CLIENT, "RES-DOCS"));
      assertTrue(key != QuotaLock.key(CLIENT, "RES-USERS"));
      assertTrue(key != QuotaLock.key("OTHER-CLIENT", "RES-DOCS"));
    }
  }

  @Test
  void anEvaluationDescribesItself() {
    QuotaEvaluation evaluation = new QuotaEvaluation(quota("DOCS", "block", 10L, null).build(),
        "month", null, 12L, QuotaVerdict.DENY);
    assertEquals("QuotaEvaluation[DOCS 12/10 block -> DENY]", evaluation.toString());
  }

  @Test
  void tenantQuotasCopiesItsInputs() {
    Date start = new Date(PERIOD_START.getTime());
    java.util.List<QuotaDefinition> rows = new java.util.ArrayList<>();
    rows.add(quota("DOCS", "block", 10L, null).build());
    TenantQuotas tenant = new TenantQuotas(CLIENT, "  ", start, PERIOD_END, false, rows);

    start.setTime(0L);
    rows.clear();

    assertEquals(1, tenant.getQuotas().size());
    assertEquals(PERIOD_START, tenant.currentPeriod(NOW).getStart());
    assertNull(tenant.getPlanInterval(), "a blank interval reads as none");
  }
}
