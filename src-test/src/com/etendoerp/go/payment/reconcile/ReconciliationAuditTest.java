/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.logging.log4j.Level;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.schemaforge.email.InternalAlertEvent;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

/**
 * Specs for {@link ReconciliationAudit}: the deterministic event ids, and that a dry run writes no
 * billing-event row while still reading which ones exist, and that an internal review alert is
 * best effort: a dry run sends none, an id that is not an operational token is sent as
 * {@code unknown}, and nothing it does can throw.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReconciliationAudit
 */
public class ReconciliationAuditTest {

  private final BillingEventStore store = mock(BillingEventStore.class);

  @Test
  public void eventIdsAreDeterministicAndPrefixed() {
    assertEquals("reconcile:checkout-expired:req-1",
        ReconciliationAudit.eventId("checkout-expired", "req-1"));
    assertEquals("reconcile:provisioning-failed:req-1:3",
        ReconciliationAudit.eventId("provisioning-failed", "req-1", "3"));
    assertEquals(ReconciliationAudit.eventId("needs-review", "req-1"),
        ReconciliationAudit.eventId("needs-review", "req-1"));
  }

  @Test
  public void aDryRunWritesNoRow() {
    ReconciliationAudit audit = new ReconciliationAudit(store, true);

    assertFalse(audit.write("reconcile:x:1", "x", "req-1", BillingEventStore.RESULT_APPLIED,
        "why", "{}"));

    verify(store, never()).recordReconciliation(anyString(), anyString(), any(), any(), any(),
        any());
    assertTrue(audit.isDryRun());
  }

  @Test
  public void aRealRunWritesAReconciliationTypedRowAndReportsWhetherItWasWritten() {
    when(store.recordReconciliation("reconcile:x:1", "reconciliation.x", "req-1",
        BillingEventStore.RESULT_APPLIED, "why", "{}")).thenReturn(true);
    ReconciliationAudit audit = new ReconciliationAudit(store, false);

    assertTrue(audit.write("reconcile:x:1", "x", "req-1", BillingEventStore.RESULT_APPLIED,
        "why", "{}"));
    assertFalse("An existing row answers false",
        audit.write("reconcile:x:2", "x", "req-1", BillingEventStore.RESULT_APPLIED, "why",
            "{}"));
  }

  @Test
  public void aDryRunStillReadsWhatIsAlreadyRecorded() {
    when(store.exists("reconcile:needs-review:req-1")).thenReturn(true);
    ReconciliationAudit audit = new ReconciliationAudit(store, true);

    assertTrue(audit.alreadyRecorded("reconcile:needs-review:req-1"));
    assertFalse(audit.alreadyRecorded("reconcile:needs-review:req-2"));
  }

  // ── internalAlert — best effort, operational tokens only ─────────────────────────────────

  private final InternalAlertService internalAlerts = mock(InternalAlertService.class);

  private InternalAlertEvent sentAlert() {
    ArgumentCaptor<InternalAlertEvent> alert = ArgumentCaptor.forClass(InternalAlertEvent.class);
    verify(internalAlerts).sendAfterTransaction(alert.capture());
    return alert.getValue();
  }

  @Test
  public void anInternalAlertCarriesItsTokens() {
    new ReconciliationAudit(store, false, internalAlerts).internalAlert(
        "billing-reconcile-needs-review", "req-1", "NO_SESSION_ID", "CLIENT1");

    InternalAlertEvent alert = sentAlert();
    assertEquals("billing-reconcile-needs-review", alert.getEvent());
    assertEquals(InternalAlertEvent.Status.ERROR, alert.getStatus());
    assertEquals("req-1", alert.getAttemptId());
    assertEquals("NO_SESSION_ID", alert.getStage());
    assertEquals("CLIENT1", alert.getClientId());
    assertEquals("REVIEW_REQUIRED", alert.getFailureCategory());
  }

  @Test
  public void anIdThatIsNotATokenIsSentAsUnknownAndAnInvalidClientIsLeftOut() {
    new ReconciliationAudit(store, false, internalAlerts).internalAlert(
        "billing-reconcile-stale-event", "evt with spaces", null, "not a client!");

    InternalAlertEvent alert = sentAlert();
    assertEquals("unknown", alert.getAttemptId());
    assertEquals("unknown", alert.getStage());
    assertEquals(null, alert.getClientId());
  }

  @Test
  public void anInvalidEventTokenIsLoggedAndNeverThrows() {
    TestLogCapture log = TestLogCapture.attachTo(ReconciliationAudit.class, Level.WARN);
    try {
      // The event name is not mapped to "unknown": the alert cannot be built, so it is dropped.
      new ReconciliationAudit(store, false, internalAlerts).internalAlert("not a token", "req-1",
          "CODE", null);

      verifyNoInteractions(internalAlerts);
      assertTrue(log.messagesAt(Level.WARN).toString(), log.messagesAt(Level.WARN).stream()
          .anyMatch(line -> line.startsWith("[BILLING-RECONCILE] Internal alert not a token for "
              + "req-1 could not be sent (IllegalArgumentException)")));
    } finally {
      log.detach();
    }
  }

  @Test
  public void aFailingAlertChannelNeverThrows() {
    doThrow(new IllegalStateException("mail down")).when(internalAlerts)
        .sendAfterTransaction(any());

    // The contract is that nothing escapes: a throw here fails the test.
    new ReconciliationAudit(store, false, internalAlerts).internalAlert(
        "billing-reconcile-needs-review", "req-1", "CODE", null);

    verify(internalAlerts).sendAfterTransaction(any());
  }

  @Test
  public void aDryRunOrAnAuditWithoutAChannelSendsNothing() {
    new ReconciliationAudit(store, true, internalAlerts).internalAlert(
        "billing-reconcile-needs-review", "req-1", "CODE", null);
    // The contract is that a missing channel is not an error: a throw here fails the test.
    new ReconciliationAudit(store, false).internalAlert("billing-reconcile-needs-review", "req-1",
        "CODE", null);

    verifyNoInteractions(internalAlerts);
  }
}
