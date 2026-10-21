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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import com.etendoerp.go.payment.BillingEventStore;

/**
 * Specs for {@link ReconciliationAudit}: the deterministic event ids, and that a dry run writes no
 * billing-event row while still reading which ones exist.
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
}
