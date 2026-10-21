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
  private ReconciliationRunLock runLock;
  private ReconciliationRunLock.Held heldLock;

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
    runLock = mock(ReconciliationRunLock.class);
    heldLock = mock(ReconciliationRunLock.Held.class);
    when(runLock.tryAcquire()).thenReturn(heldLock);
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
    process.runLock = runLock;
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

  /**
   * Review F3: a scheduled request with Dry run = Y and the flag off fires every hour for nothing
   * too. The alert is about the schedule, so the Dry run parameter does not silence it.
   */
  @Test
  public void aDryRunScheduleFiringWithTheFlagOffStillAlertsOnceADay() {
    firedByTheScheduler("ADMIN-SCHEDULED-REQUEST", "S");
    params.put("dryrun", "Y");
    when(eventStore.recordReconciliation(eq("reconcile:schedule-flag-off:2026-10-21"),
        anyString(), isNull(), anyString(), anyString(), anyString())).thenReturn(true);

    expectFailure();

    verify(internalAlerts, times(1)).sendAfterTransaction(any());
    verify(service, never()).run(anyBoolean());
  }

  /**
   * Review F4: a second run while one holds the lock (a menu launch over the schedule) does no
   * work and says so as a warning, never a false "closed by an earlier run" error.
   */
  @Test
  public void aSecondConcurrentRunDoesNothingAndSaysSoAsAWarning() throws Exception {
    when(runLock.tryAcquire()).thenReturn(null);

    process(false).doExecute(bundle);

    verify(service, never()).run(anyBoolean());
    OBError result = resultOf();
    assertEquals("Warning", result.getType());
    assertEquals("A billing reconciliation is already running; nothing was done.",
        result.getMessage());
    verify(processLogger).logln("A billing reconciliation is already running; nothing was done.");
  }

  @Test
  public void theRunLockIsReleasedAfterARunEvenWhenTheRunFails() throws Exception {
    when(service.run(false)).thenReturn(new ReconciliationResult(false));
    process(false).doExecute(bundle);
    verify(heldLock, times(1)).close();

    when(service.run(false)).thenThrow(new IllegalStateException("no database"));
    expectFailure();
    verify(heldLock, times(2)).close();
  }

  /**
   * AC5: a run is readable without the database or the logs. The popup lists the affected items
   * one per line (HTML), and the run log Process Monitor shows carries the same lines.
   */
  @Test
  public void theAffectedItemsReachThePopupAndTheProcessMonitorLog() throws Exception {
    ReconciliationResult run = new ReconciliationResult(false);
    run.increment(Counter.CHECKOUTS_EXPIRED);
    run.item("req 1a2b3c4d", "Acme S.L.", "close abandoned checkout",
        "the provider session expired");
    when(service.run(false)).thenReturn(run);

    process(false).doExecute(bundle);

    String line = "req 1a2b3c4d (Acme S.L.): close abandoned checkout — the provider session "
        + "expired";
    verify(processLogger).logln(run.toString());
    verify(processLogger).logln(line);
    OBError result = resultOf();
    assertEquals("Success", result.getType());
    assertEquals(run.toString() + "<br/>" + line, result.getMessage());
  }

  /**
   * The same run through a real {@link ProcessBundle} and {@link ProcessLogger}, as the Classic
   * menu launch builds them, and the real run lock failing open (no dedicated connection): the
   * summary and the capped item lines land in the run log Process Monitor shows, and the popup
   * text is escaped HTML with one {@code <br/>} per line.
   */
  @Test
  public void aRealProcessBundleGetsTheSummaryAndTheCappedItemLines() throws Exception {
    ReconciliationResult run = new ReconciliationResult(false);
    run.increment(Counter.CHECKOUTS_EXPIRED);
    run.item("req 00000000", "<b>Acme & Co</b>", "close abandoned checkout",
        "the provider session expired");
    for (int i = 1; i < ReconciliationResult.MAX_ITEM_LINES + 5; i++) {
      run.item(String.format("req %08d", i), "Tenant " + i, "close abandoned checkout", null);
    }
    when(service.run(false)).thenReturn(run);
    // A menu launch: the direct channel, no process request.
    ProcessBundle realBundle = new ProcessBundle("ETGO_BillingReconciliation",
        new org.openbravo.base.secureApp.VariablesSecureApp("100", "0", "0"));
    realBundle.setLog(new ProcessLogger(null));
    BillingReconciliationProcess process = process(false);
    process.runLock = new ReconciliationRunLock(() -> null);

    process.doExecute(realBundle);

    String log = realBundle.getLog();
    assertTrue(log, log.contains(" - " + run.toString() + "\n"));
    assertTrue(log, log.contains(" - req 00000000 (<b>Acme & Co</b>): close abandoned checkout"
        + " — the provider session expired\n"));
    assertTrue(log, log.contains(" - req 00000019 (Tenant 19): close abandoned checkout\n"));
    assertFalse("Only 20 item lines are listed", log.contains("req 00000020"));
    assertTrue(log, log.contains(" - +5 more\n"));
    assertEquals("The summary plus 20 items plus the rest", 22, log.split("\n").length);
    OBError popup = (OBError) realBundle.getResult();
    assertEquals("Success", popup.getType());
    String message = popup.getMessage();
    assertTrue(message, message.contains("<br/>req 00000000 (&lt;b&gt;Acme &amp; Co&lt;/b&gt;)"));
    assertFalse("No raw markup from a name reaches the popup", message.contains("<b>"));
    assertTrue(message, message.endsWith("<br/>+5 more"));
    assertEquals(21, message.split("<br/>", -1).length - 1);
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
