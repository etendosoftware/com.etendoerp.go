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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openbravo.base.exception.OBException;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessLogger;

import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.startup.BillingReconciliationScheduleStartup;

/**
 * Specs for {@link BillingReconciliationProcess}, the Classic entry point.
 *
 * <ul>
 *   <li>The {@code billing-reconciliation-schedule} flag gates only a run of the fixed request the
 *       startup plants; a run from the menu (or from an operator's own Process Request) is never
 *       gated.</li>
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

  @Before
  public void setUp() {
    service = mock(BillingReconciliationService.class);
    bundle = mock(ProcessBundle.class);
    processLogger = mock(ProcessLogger.class);
    params = new HashMap<>();
    flagReads = new AtomicInteger();
    when(bundle.getParams()).thenReturn(params);
    when(bundle.getLogger()).thenReturn(processLogger);
  }

  private BillingReconciliationProcess process(boolean flagOn) {
    return new BillingReconciliationProcess(() -> service) {
      @Override
      boolean scheduleEnabled() {
        flagReads.incrementAndGet();
        return flagOn;
      }
    };
  }

  /** Runs the process expecting it to throw, as Process Monitor needs to record ERROR. */
  private OBException expectFailure() {
    try {
      process(false).doExecute(bundle);
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

  @Test
  public void aScheduledRunDoesNoWorkWhileTheFlagIsOff() throws Exception {
    when(bundle.getProcessRequestId()).thenReturn(BillingReconciliationScheduleStartup.REQUEST_ID);

    process(false).doExecute(bundle);

    verify(service, never()).run(anyBoolean());
    OBError result = resultOf();
    assertEquals("Success", result.getType());
    assertTrue(result.getMessage(), result.getMessage().contains("flag is off"));
  }

  @Test
  public void aScheduledRunWorksWhileTheFlagIsOn() throws Exception {
    when(bundle.getProcessRequestId()).thenReturn(BillingReconciliationScheduleStartup.REQUEST_ID);
    when(service.run(false)).thenReturn(new ReconciliationResult(false));

    process(true).doExecute(bundle);

    verify(service).run(false);
    assertEquals("Success", resultOf().getType());
  }

  @Test
  public void aManualRunIsNeverGatedByTheFlag() throws Exception {
    when(bundle.getProcessRequestId()).thenReturn("SOME-OPERATOR-REQUEST");
    when(service.run(false)).thenReturn(new ReconciliationResult(false));

    process(false).doExecute(bundle);

    verify(service).run(false);
    assertEquals("The flag is not even read for a manual run", 0, flagReads.get());
    assertEquals("Success", resultOf().getType());
  }

  @Test
  public void aMenuRunWithNoRequestIdIsNeverGated() throws Exception {
    when(bundle.getProcessRequestId()).thenReturn(null);
    when(service.run(false)).thenReturn(new ReconciliationResult(false));

    process(false).doExecute(bundle);

    verify(service).run(false);
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
