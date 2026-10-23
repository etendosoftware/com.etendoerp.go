/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.hubspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.hubspot.HubSpotEventSender.Outcome;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.OpenDemo;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PaidCheckout;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * One pass of the HubSpot sync: which events it detects, in which order it sends, and how it
 * retries and gives up.
 *
 * @covers com.etendoerp.go.hubspot.HubSpotSyncService
 */
class HubSpotSyncServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  private static final Instant READY = Instant.parse("2026-10-01T12:00:00Z");
  private static final HubSpotConfiguration ENABLED = new HubSpotConfiguration("t",
      "https://hubspot.test", "pipe", "trial", "active", "won", "lost");

  private final HubSpotOutboxStore store = mock(HubSpotOutboxStore.class);
  private final DemoTrials trials = mock(DemoTrials.class);
  private final HubSpotEventSender sender = mock(HubSpotEventSender.class);
  private final HubSpotSyncService service = new HubSpotSyncService(ENABLED, store, trials,
      sender);

  private static PendingEvent pending(String id, String account, String type, int attempts,
      Instant nextAttemptAt) {
    return new PendingEvent(id, account, type, "C-" + account, null, attempts, nextAttemptAt,
        account + "@empresa.es", "Name");
  }

  @Test
  void withoutATokenNothingIsReadOrSent() {
    HubSpotSyncService disabled = new HubSpotSyncService(new HubSpotConfiguration(null,
        "https://hubspot.test", "pipe", "trial", "active", "won", "lost"), store, trials, sender);

    HubSpotSyncService.Result result = disabled.run(NOW);

    assertTrue(result.isDisabled());
    assertEquals("no HubSpot token configured; nothing sent", result.toString());
    verifyNoInteractions(store, trials, sender);
  }

  @Test
  void aFirstEntryAndAnExpiredTrialAreDetected() {
    when(store.findOpenDemos()).thenReturn(List.of(new OpenDemo("A", "CA", READY, false)));
    when(store.hasEnteredEnvironment("CA")).thenReturn(true);
    when(store.findPaidCheckout("A", READY)).thenReturn(Optional.empty());
    when(trials.find("CA", NOW)).thenReturn(Optional.of(new DemoTrials.Trial(READY,
        READY.plus(Duration.ofDays(14)), true)));
    when(store.enqueue(anyString(), anyString(), anyString(), isNull())).thenReturn(true);

    HubSpotSyncService.Result result = service.run(NOW);

    verify(store).enqueue("A", HubSpotOutboxStore.DEMO_ENTERED, "CA", null);
    verify(store).enqueue("A", HubSpotOutboxStore.DEMO_EXPIRED, "CA", null);
    assertEquals(2, result.getDetected());
  }

  @Test
  void aPaymentIsDetectedWithItsPlanAndEndsTheTrialCheck() {
    when(store.findOpenDemos()).thenReturn(List.of(new OpenDemo("A", "CA", READY, true)));
    when(store.findPaidCheckout("A", READY)).thenReturn(Optional.of(new PaidCheckout("pro")));
    when(store.enqueue(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

    service.run(NOW);

    verify(store).enqueue("A", HubSpotOutboxStore.PAID, "CA", "{\"plan\":\"pro\"}");
    verify(store, never()).hasEnteredEnvironment(anyString());
    verifyNoInteractions(trials);
  }

  @Test
  void aTrialStillRunningIsNotReported() {
    when(store.findOpenDemos()).thenReturn(List.of(new OpenDemo("A", "CA", READY, true)));
    when(store.findPaidCheckout("A", READY)).thenReturn(Optional.empty());
    when(trials.find("CA", NOW)).thenReturn(Optional.of(new DemoTrials.Trial(READY,
        READY.plus(Duration.ofDays(30)), false)));

    assertEquals(0, service.run(NOW).getDetected());
    verify(store, never()).enqueue(anyString(), anyString(), anyString(), any());
  }

  @Test
  void aDemoThatCannotBeInspectedDoesNotStopThePass() {
    when(store.findOpenDemos()).thenReturn(List.of(new OpenDemo("A", "CA", READY, false),
        new OpenDemo("B", "CB", READY, false)));
    when(store.hasEnteredEnvironment("CA")).thenThrow(new IllegalStateException("boom"));
    when(store.hasEnteredEnvironment("CB")).thenReturn(true);
    when(store.findPaidCheckout(anyString(), any())).thenReturn(Optional.empty());
    when(trials.find(anyString(), any())).thenReturn(Optional.empty());
    when(store.enqueue(anyString(), anyString(), anyString(), isNull())).thenReturn(true);

    service.run(NOW);

    verify(store).rollback();
    verify(store).enqueue("B", HubSpotOutboxStore.DEMO_ENTERED, "CB", null);
  }

  @Test
  void eachOutcomeIsRecordedAndCommitted() {
    PendingEvent sent = pending("1", "A", HubSpotOutboxStore.DEMO_READY, 0, null);
    PendingEvent refused = pending("2", "B", HubSpotOutboxStore.ACCOUNT_CREATED, 0, null);
    PendingEvent skipped = pending("3", "C", HubSpotOutboxStore.PAID, 0, null);
    when(store.findPending(HubSpotSyncService.BATCH_SIZE))
        .thenReturn(List.of(sent, refused, skipped));
    when(sender.send(sent, NOW)).thenReturn(Outcome.sent("deal-1"));
    when(sender.send(refused, NOW)).thenReturn(Outcome.failed("HTTP 400"));
    when(sender.send(skipped, NOW)).thenReturn(Outcome.skipped("no deal"));

    HubSpotSyncService.Result result = service.run(NOW);

    verify(store).markSent("1", "deal-1");
    verify(store).markFailed("2", 1, "HTTP 400");
    verify(store).markSkipped("3", "no deal");
    assertEquals("0 event(s) detected, 1 sent, 0 to retry, 1 failed, 1 skipped",
        result.toString());
  }

  @Test
  void aRetryHoldsBackTheLaterEventsOfThatAccountOnly() {
    PendingEvent demo = pending("1", "A", HubSpotOutboxStore.DEMO_READY, 0, null);
    PendingEvent paid = pending("2", "A", HubSpotOutboxStore.PAID, 0, null);
    PendingEvent other = pending("3", "B", HubSpotOutboxStore.ACCOUNT_CREATED, 0, null);
    when(store.findPending(HubSpotSyncService.BATCH_SIZE)).thenReturn(List.of(demo, paid, other));
    when(sender.send(demo, NOW)).thenReturn(Outcome.retry("HTTP 503"));
    when(sender.send(other, NOW)).thenReturn(Outcome.sent("contact-2"));

    service.run(NOW);

    verify(store).markRetry("1", 1, NOW.plus(Duration.ofMinutes(1)), "HTTP 503");
    verify(sender, never()).send(eq(paid), any());
    verify(store).markSent("3", "contact-2");
  }

  @Test
  void anEventNotDueYetHoldsBackItsAccount() {
    PendingEvent waiting = pending("1", "A", HubSpotOutboxStore.DEMO_READY, 3,
        NOW.plusSeconds(60));
    PendingEvent later = pending("2", "A", HubSpotOutboxStore.DEMO_ENTERED, 0, null);
    when(store.findPending(HubSpotSyncService.BATCH_SIZE)).thenReturn(List.of(waiting, later));

    service.run(NOW);

    verifyNoInteractions(sender);
  }

  @Test
  void aDeferredEventSpendsNoAttempt() {
    PendingEvent deferred = pending("1", "A", HubSpotOutboxStore.PAID, 0, null);
    when(store.findPending(HubSpotSyncService.BATCH_SIZE)).thenReturn(List.of(deferred));
    when(sender.send(deferred, NOW)).thenReturn(Outcome.deferred());

    service.run(NOW);

    verify(store, never()).markRetry(anyString(), anyInt(), any(), any());
    verify(store, never()).markFailed(anyString(), anyInt(), any());
  }

  @Test
  void theLastAttemptGivesUp() {
    PendingEvent tired = pending("1", "A", HubSpotOutboxStore.DEMO_READY,
        HubSpotSyncService.MAX_ATTEMPTS - 1, null);
    when(store.findPending(HubSpotSyncService.BATCH_SIZE)).thenReturn(List.of(tired));
    when(sender.send(tired, NOW)).thenReturn(Outcome.retry("HTTP 503"));

    assertEquals(1, service.run(NOW).getFailed());
    verify(store).markFailed("1", HubSpotSyncService.MAX_ATTEMPTS, "HTTP 503");
  }

  @Test
  void anUnexpectedErrorWhileSendingIsRetried() {
    PendingEvent event = pending("1", "A", HubSpotOutboxStore.ACCOUNT_CREATED, 0, null);
    when(store.findPending(HubSpotSyncService.BATCH_SIZE)).thenReturn(List.of(event));
    when(sender.send(event, NOW)).thenThrow(new IllegalStateException("db gone"));

    service.run(NOW);

    verify(store).markRetry(eq("1"), eq(1), any(),
        eq("java.lang.IllegalStateException: db gone"));
  }

  @Test
  void theDelayDoublesUpToSixHours() {
    assertEquals(Duration.ofMinutes(1), HubSpotSyncService.delayAfter(1));
    assertEquals(Duration.ofMinutes(2), HubSpotSyncService.delayAfter(2));
    assertEquals(Duration.ofMinutes(256), HubSpotSyncService.delayAfter(9));
    assertEquals(Duration.ofMinutes(360), HubSpotSyncService.delayAfter(10));
    assertEquals(Duration.ofMinutes(360), HubSpotSyncService.delayAfter(40));
  }

  @Test
  void aPaymentWithoutPlanCarriesNoPayload() {
    assertEquals(null, HubSpotSyncService.planPayload(null));
  }
}
