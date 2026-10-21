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

import org.codehaus.jettison.json.JSONObject;
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

  // ── Review F5: once per pause episode ─────────────────────────────────────────────────────

  @Test
  public void aResumedSubscriptionEndsItsOpenEpisodeSoTheNextPauseAlertsAgain() throws Exception {
    when(store.exists(EVENT_ID)).thenReturn(true);
    when(store.renameReconciliationMarker(eq(EVENT_ID), anyString(), anyString()))
        .thenReturn(true);

    int ended = alert.endResolved(SUB, new JSONObject("{\"status\":\"active\"}"));

    assertEquals(1, ended);
    ArgumentCaptor<String> keptAs = ArgumentCaptor.forClass(String.class);
    verify(store).renameReconciliationMarker(eq(EVENT_ID), keptAs.capture(), anyString());
    assertTrue(keptAs.getValue(), keptAs.getValue().startsWith(EVENT_ID + ":ended:"));
  }

  @Test
  public void aStillPausedSubscriptionKeepsItsEpisodeOpenAndStaysSilent() throws Exception {
    when(store.exists(anyString())).thenReturn(true);

    int ended = alert.endResolved(SUB, new JSONObject(
        "{\"status\":\"paused\",\"pause_collection\":{\"behavior\":\"void\"}}"));

    assertEquals(0, ended);
    verify(store, never()).renameReconciliationMarker(anyString(), anyString(), anyString());
  }

  @Test
  public void clearedPauseCollectionEndsThePausedCollectionEpisode() throws Exception {
    String collectionId = PausedSubscriptionAlert.pauseCollectionEventIdOf(SUB);
    when(store.exists(collectionId)).thenReturn(true);
    when(store.renameReconciliationMarker(eq(collectionId), anyString(), anyString()))
        .thenReturn(true);

    assertEquals(1, alert.endResolved(SUB, new JSONObject(
        "{\"status\":\"active\",\"pause_collection\":null}")));
    assertEquals("reconcile:pause-collection:" + SUB, collectionId);
  }

  /** A webhook must not fail (and be redelivered in a loop) over a marker that will not rename. */
  @Test
  public void aFailedRenameIsBestEffortForTheWebhookAndEndsNothing() throws Exception {
    when(store.exists(EVENT_ID)).thenReturn(true);
    when(store.renameReconciliationMarker(eq(EVENT_ID), anyString(), anyString()))
        .thenThrow(new IllegalStateException("db gone"));
    JSONObject active = new JSONObject("{\"status\":\"active\"}");

    assertEquals(0, alert.endResolvedBestEffort(SUB, active));
    try {
      alert.endResolved(SUB, active);
      org.junit.Assert.fail("Outside the webhook a failed rename still surfaces");
    } catch (IllegalStateException expected) {
      assertEquals("db gone", expected.getMessage());
    }
  }

  @Test
  public void noOpenEpisodeMeansNoWrite() throws Exception {
    assertEquals(0, alert.endResolved(SUB, new JSONObject("{\"status\":\"active\"}")));
    verify(store, never()).renameReconciliationMarker(anyString(), anyString(), anyString());
  }

  /** The whole cycle: pause alerts, a re-read in the same pause is silent, resume, pause alerts. */
  @Test
  public void aSecondPauseAfterAResumeAlertsAgainButAReRunWithinOnePauseDoesNot() throws Exception {
    java.util.Set<String> rows = new java.util.HashSet<>();
    when(store.exists(anyString())).thenAnswer(call -> rows.contains(call.getArgument(0)));
    when(store.recordReconciliation(anyString(), anyString(), any(), anyString(), anyString(),
        anyString())).thenAnswer(call -> rows.add(call.getArgument(0)));
    when(store.renameReconciliationMarker(anyString(), anyString(), anyString()))
        .thenAnswer(call -> rows.remove(call.getArgument(0, String.class))
            && rows.add(call.getArgument(1, String.class)));

    assertTrue(alert.raiseOnce("[P]", SUB, null, null, "webhook"));
    assertFalse("Same pause: silent", alert.raiseOnce("[P]", SUB, null, null, "reconciliation"));
    alert.endResolved(SUB, new JSONObject("{\"status\":\"active\"}"));
    assertTrue("A new pause alerts again", alert.raiseOnce("[P]", SUB, null, null, "webhook"));

    verify(internalAlerts, org.mockito.Mockito.times(2)).sendAfterTransaction(any());
  }

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
