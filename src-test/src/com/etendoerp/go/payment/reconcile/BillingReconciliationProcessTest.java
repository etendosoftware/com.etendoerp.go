/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openbravo.base.exception.OBException;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessLogger;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.schemaforge.email.InternalAlertEvent;
import com.etendoerp.go.schemaforge.email.InternalAlertService;
import com.etendoerp.go.startup.BillingReconciliationScheduleStartup;

/**
 * Specs for {@link BillingReconciliationProcess}, the Classic entry point.
 *
 * <ul>
 *   <li>The {@code billing-reconciliation-schedule} flag gates every run the Process Scheduler
 *       fires for a recurring request (timing {@code S}), the startup's or an administrator's; a
 *       run from the menu, or an immediate Process Request run, is never gated. A gated run does
 *       no work and ends as an ERROR, so Process Monitor shows it.</li>
 *   <li>The optional Dry run parameter reaches the service, whatever the case of its key.</li>
 *   <li>A run with errors or a tripped breaker sets an Error result <em>and</em> throws, so
 *       Process Monitor records ERROR.</li>
 * </ul>
 *
 * @covers com.etendoerp.go.payment.reconcile.BillingReconciliationProcess
 */
public class BillingReconciliationProcessTest {

  private BillingReconciliationService service;
  private ProcessBundle bundle;
  private ProcessLogger processLogger;
  private Map<String, Object> params;
  private AtomicInteger flagReads;
  /** The timing option each process request answers, by id. */
  private Map<String, String> timings;
  private BillingEventStore eventStore;
  private InternalAlertService internalAlerts;

  @Before
  public void setUp() {
    service = mock(BillingReconciliationService.class);
    bundle = mock(ProcessBundle.class);
    processLogger = mock(ProcessLogger.class);
    params = new HashMap<>();
    flagReads = new AtomicInteger();
    timings = new HashMap<>();
    eventStore = mock(BillingEventStore.class);
    internalAlerts = mock(InternalAlertService.class);
    when(bundle.getParams()).thenReturn(params);
    when(bundle.getLogger()).thenReturn(processLogger);
  }

  private BillingReconciliationProcess process(boolean flagOn) {
    BillingReconciliationProcess process = new BillingReconciliationProcess(() -> service) {
      @Override
      boolean scheduleEnabled() {
        flagReads.incrementAndGet();
        return flagOn;
      }

      @Override
      String timingOf(String requestId) {
        return timings.get(requestId);
      }
    };
    process.eventStore = eventStore;
    process.internalAlerts = internalAlerts;
    process.clock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
    return process;
  }

  /** A run the Process Scheduler fires for request {@code requestId} with {@code timing}. */
  private void firedByTheScheduler(String requestId, String timing) {
    when(bundle.getChannel()).thenReturn(ProcessBundle.Channel.SCHEDULED);
    when(bundle.getProcessRequestId()).thenReturn(requestId);
    timings.put(requestId, timing);
  }

  /** Runs the process expecting it to throw, as Process Monitor needs to record ERROR. */
  private OBException expectFailure() {
    return expectFailure(false);
  }

  private OBException expectFailure(boolean flagOn) {
    try {
      process(flagOn).doExecute(bundle);
    } catch (OBException expected) {
      return expected;
    } catch (Exception other) {
      throw new AssertionError("Expected an OBException, got " + other, other);
    }
    fail("A failed run must throw");
    return null;
  }

  private OBError resultOf() {
    ArgumentCaptor<OBError> result = ArgumentCaptor.forClass(OBError.class);
    verify(bundle).setResult(result.capture());
    return result.getValue();
  }

  /**
   * A scheduled run while the flag is off does no work and ends as an ERROR, so a request firing
   * for nothing every hour is visible in Process Monitor rather than a silent success.
   */
  @Test
  public void aScheduledRunWhileTheFlagIsOffDoesNoWorkAndFails() {
    firedByTheScheduler(BillingReconciliationScheduleStartup.REQUEST_ID, "S");
    TestLogCapture warnings = TestLogCapture.attachTo(BillingReconciliationProcess.class,
        Level.WARN);
    try {
      expectFailure();

      assertTrue(warnings.messagesAt(Level.WARN).toString(), warnings.messagesAt(Level.WARN)
          .stream().anyMatch(line -> line.startsWith("[BILLING-RECONCILE] Scheduled run of "
              + "process request " + BillingReconciliationScheduleStartup.REQUEST_ID)));
    } finally {
      warnings.detach();
    }
    verify(service, never()).run(anyBoolean());
    OBError result = resultOf();
    assertEquals("Error", result.getType());
    assertEquals("Billing Reconciliation Schedule Is Off", result.getTitle());
    assertTrue(result.getMessage(), result.getMessage().contains(
        "billing-reconciliation-schedule flag is off, so this scheduled request did nothing"));
    assertTrue(result.getMessage(), result.getMessage().contains("unschedule"));
  }

  /**
   * A schedule firing hourly with the flag off means no reconciliation at all, and Process Monitor
   * is only seen by someone who opens it: the first such run of the UTC day sends one internal
   * alert, the later runs that day stay silent (the breaker's pattern).
   */
  @Test
  public void theFirstFlagOffScheduledRunOfTheDayAlertsOnceAndTheNextOneDoesNot() {
    firedByTheScheduler("ADMIN-SCHEDULED-REQUEST", "S");
    when(eventStore.recordReconciliation(eq("reconcile:schedule-flag-off:2026-10-21"),
        eq("reconciliation.schedule-flag-off"), isNull(), eq(BillingEventStore.RESULT_IGNORED),
        anyString(), anyString())).thenReturn(true, false);

    expectFailure();
    expectFailure();

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(eventStore, times(2)).recordReconciliation(
        eq("reconcile:schedule-flag-off:2026-10-21"), anyString(), isNull(), anyString(),
        reason.capture(), anyString());
    assertTrue(reason.getValue(), reason.getValue().contains("no reconciliation is happening"));
    ArgumentCaptor<InternalAlertEvent> alert = ArgumentCaptor.forClass(InternalAlertEvent.class);
    verify(internalAlerts, times(1)).sendAfterTransaction(alert.capture());
    assertEquals("billing-reconcile-schedule-flag-off", alert.getValue().getEvent());
    assertEquals("turn-flag-on-or-unschedule-request", alert.getValue().getStage());
    verify(service, never()).run(anyBoolean());
  }

  @Test
  public void aFlagOffAlertThatCannotBeRecordedStillEndsTheRunAsAnError() {
    firedByTheScheduler("ADMIN-SCHEDULED-REQUEST", "S");
    when(eventStore.recordReconciliation(anyString(), anyString(), any(), anyString(), any(),
        any())).thenThrow(new IllegalStateException("db gone"));

    expectFailure();

    verify(internalAlerts, never()).sendAfterTransaction(any());
    assertEquals("Error", resultOf().getType());
  }

  /** The gate is not tied to the startup's fixed id: an administrator's schedule obeys it too. */
  @Test
  public void aScheduleAnAdministratorCreatedIsGatedByTheFlagToo() {
    firedByTheScheduler("ADMIN-SCHEDULED-REQUEST", "S");

    expectFailure();

    verify(service, never()).run(anyBoolean());
    assertEquals("Error", resultOf().getType());
  }

  @Test
  public void aScheduledRunWorksWhileTheFlagIsOn() throws Exception {
    firedByTheScheduler("ADMIN-SCHEDULED-REQUEST", "S");
    when(service.run(false)).thenReturn(new ReconciliationResult(false));

    process(true).doExecute(bundle);

    verify(service).run(false);
    assertEquals("Success", resultOf().getType());
  }

  @Test
  public void anImmediateProcessRequestRunIsNeverGatedByTheFlag() throws Exception {
    // ProcessBundle.request puts every scheduler-run bundle on the SCHEDULED channel; the timing
    // option is what tells a one-off run from a schedule.
    firedByTheScheduler("SOME-OPERATOR-REQUEST", "I");
    when(service.run(false)).thenReturn(new ReconciliationResult(false));

    process(false).doExecute(bundle);

    verify(service).run(false);
    assertEquals("The flag is not even read for a manual run", 0, flagReads.get());
    assertEquals("Success", resultOf().getType());
  }

  @Test
  public void aMenuRunIsNeverGated() throws Exception {
    when(bundle.getChannel()).thenReturn(ProcessBundle.Channel.DIRECT);
    when(bundle.getProcessRequestId()).thenReturn(null);
    when(service.run(false)).thenReturn(new ReconciliationResult(false));

    process(false).doExecute(bundle);

    verify(service).run(false);
    assertEquals(0, flagReads.get());
  }

  @Test
  public void theDryRunParameterReachesTheServiceAndTheSummarySaysDryRun() throws Exception {
    params.put("DryRun", "Y");
    ReconciliationResult dry = new ReconciliationResult(true);
    dry.increment(Counter.PAYMENTS_RECOVERED);
    when(service.run(true)).thenReturn(dry);

    process(false).doExecute(bundle);

    verify(service).run(true);
    OBError result = resultOf();
    assertEquals("Success", result.getType());
    assertTrue(result.getMessage(), result.getMessage().startsWith("Dry run "));
    assertTrue(result.getMessage(), result.getMessage().contains("Dry run: nothing was written"));
    verify(processLogger).logln(result.getMessage());
  }

  @Test
  public void theDryRunParameterIsReadLeniently() {
    assertFalse(BillingReconciliationProcess.readDryRun(bundle));
    params.put("dryrun", Boolean.TRUE);
    assertTrue(BillingReconciliationProcess.readDryRun(bundle));
    params.put("dryrun", "true");
    assertTrue(BillingReconciliationProcess.readDryRun(bundle));
    params.put("dryrun", " yes ");
    assertTrue(BillingReconciliationProcess.readDryRun(bundle));
    params.put("dryrun", "N");
    assertFalse(BillingReconciliationProcess.readDryRun(bundle));
    when(bundle.getParams()).thenReturn(null);
    assertFalse(BillingReconciliationProcess.readDryRun(bundle));
  }

  @Test
  public void aRunWithATrippedBreakerSetsAnErrorResultAndThrows() {
    ReconciliationResult tripped = new ReconciliationResult(false);
    tripped.tripBreaker("3 of 4 subscriptions are absent");
    when(service.run(false)).thenReturn(tripped);

    expectFailure();

    OBError result = resultOf();
    assertEquals("Error", result.getType());
    assertEquals("Billing Reconciliation Finished With Errors", result.getTitle());
    assertTrue(result.getMessage(), result.getMessage().contains("breaker tripped"));
  }

  @Test
  public void aServiceFailureSetsAnErrorResultAndThrows() {
    when(service.run(false)).thenThrow(new IllegalStateException("no database"));

    OBException thrown = expectFailure();

    assertEquals("no database", thrown.getMessage());
    OBError result = resultOf();
    assertEquals("Error", result.getType());
    assertEquals("Billing Reconciliation Failed", result.getTitle());
  }
}
