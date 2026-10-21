/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.schemaforge.email.InternalAlertEvent;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

/**
 * Specs for {@link PausedSubscriptionAlert}, the one review alert for a Stripe subscription in
 * status {@code paused}, shared by the {@code customer.subscription.updated} webhook, the
 * {@code customer.subscription.paused} event and the billing reconciliation.
 *
 * <p>The property guarded: the alert fires <b>exactly once per subscription</b>, whichever path
 * notices first, because every path checks and writes the same deterministic billing-event id,
 * {@code reconcile:subscription-paused:<sub>}. Only the call that writes the row alerts.
 *
 * @covers com.etendoerp.go.payment.PausedSubscriptionAlert
 */
public class PausedSubscriptionAlertTest {

  private static final String SUB = "sub_paused_1";
  private static final String EVENT_ID = "reconcile:subscription-paused:" + SUB;

  private final BillingEventStore store = mock(BillingEventStore.class);
  private final InternalAlertService internalAlerts = mock(InternalAlertService.class);
  private final PausedSubscriptionAlert alert = new PausedSubscriptionAlert(store, internalAlerts);

  @Test
  public void theEventIdIsTheSharedDeterministicOne() {
    assertEquals(EVENT_ID, PausedSubscriptionAlert.eventIdOf(SUB));
    assertEquals(EVENT_ID, PausedSubscriptionAlert.eventIdOf("  " + SUB + " "));
  }

  @Test
  public void theFirstCallWritesTheRowAndSendsOneInternalAlert() {
    when(store.recordReconciliation(eq(EVENT_ID), anyString(), any(), anyString(), anyString(),
        anyString())).thenReturn(true);

    assertTrue(alert.raiseOnce("[BILLING-ALERT]", SUB, "CLIENT-1", "req-1", "webhook"));

    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(store).recordReconciliation(eq(EVENT_ID), eq("reconciliation.subscription-paused"),
        eq("req-1"), eq(BillingEventStore.RESULT_IGNORED), anyString(), summary.capture());
    assertEquals("{\"subscription\":\"" + SUB + "\",\"source\":\"webhook\"}", summary.getValue());
    ArgumentCaptor<InternalAlertEvent> event = ArgumentCaptor.forClass(InternalAlertEvent.class);
    verify(internalAlerts).sendAfterTransaction(event.capture());
    assertEquals("subscription-paused-review", event.getValue().getEvent());
    assertEquals(SUB, event.getValue().getAttemptId());
    assertEquals("CLIENT-1", event.getValue().getClientId());
    assertEquals("REVIEW_REQUIRED", event.getValue().getFailureCategory());
  }

  @Test
  public void aSecondPathFindsTheRowAndStaysSilent() {
    when(store.exists(EVENT_ID)).thenReturn(true);

    assertFalse(alert.raiseOnce("[BILLING-RECONCILE]", SUB, "CLIENT-1", "req-1",
        "reconciliation"));

    verify(store, never()).recordReconciliation(anyString(), anyString(), any(), any(), any(),
        any());
    verifyNoInteractions(internalAlerts);
  }

  @Test
  public void losingTheInsertRaceSendsNoAlert() {
    when(store.recordReconciliation(anyString(), anyString(), any(), anyString(), anyString(),
        anyString())).thenReturn(false);

    assertFalse(alert.raiseOnce("[BILLING-ALERT]", SUB, null, null, "webhook"));

    verifyNoInteractions(internalAlerts);
  }

  @Test
  public void aBlankSubscriptionIdRaisesNothing() {
    assertFalse(alert.raiseOnce("[BILLING-ALERT]", "  ", "CLIENT-1", "req-1", "webhook"));

    verifyNoInteractions(store, internalAlerts);
  }

  @Test
  public void aFailedInternalAlertDoesNotUndoTheRaisedAlert() {
    when(store.recordReconciliation(anyString(), anyString(), any(), anyString(), anyString(),
        anyString())).thenReturn(true);
    doThrow(new IllegalStateException("mail down")).when(internalAlerts)
        .sendAfterTransaction(any());

    assertTrue(alert.raiseOnce("[BILLING-ALERT]", SUB, "CLIENT-1", null, "webhook"));
  }
}
