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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * The KPI event of a confirmed inbox document: {@code adjusted} and {@code account_id} only (never a
 * record identifier), sent on a background thread through a seam, and a failing send never reaches
 * the request.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxTelemetry
 */
class InvoiceInboxTelemetryTest {

  @AfterEach
  void restoreTheRuntimeService() throws Exception {
    awaitSenderIdle();
    InvoiceInboxTelemetry.setTelemetryService(null);
  }

  /** Waits until the static single-thread sender has nothing queued or running (events drained). */
  private static void awaitSenderIdle() throws Exception {
    java.lang.reflect.Field sender = InvoiceInboxTelemetry.class.getDeclaredField("SENDER");
    sender.setAccessible(true);
    java.util.concurrent.ThreadPoolExecutor executor =
        (java.util.concurrent.ThreadPoolExecutor) sender.get(null);
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while ((!executor.getQueue().isEmpty() || executor.getActiveCount() > 0)
        && System.nanoTime() < deadline) {
      Thread.yield();
    }
  }

  @Test
  @DisplayName("the properties are the adjusted flag and the account; no account, no account_id")
  void theProperties() {
    Map<String, Object> withAccount = InvoiceInboxTelemetry.properties(true, "CLIENT-1");
    Map<String, Object> withoutAccount = InvoiceInboxTelemetry.properties(false, null);

    assertEquals(Map.of("account_id", "CLIENT-1", "adjusted", true), withAccount);
    assertEquals(Map.of("adjusted", false), withoutAccount);
  }

  @Test
  @DisplayName("documentConfirmed emits the confirmed event once with adjusted and the tenant")
  @SuppressWarnings("unchecked")
  void emitsOnce() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    InvoiceInboxTelemetry.setTelemetryService(service);

    InvoiceInboxTelemetry.documentConfirmed(true, "CLIENT-1");

    ArgumentCaptor<Map<String, ?>> properties = ArgumentCaptor.forClass(Map.class);
    verify(service, timeout(5000)).emit(eq(NeoTelemetryEvents.BACKEND_INVOICE_INBOX_DOCUMENT_CONFIRMED),
        properties.capture(), eq("CLIENT-1"));
    assertEquals(Map.of("account_id", "CLIENT-1", "adjusted", true), properties.getValue());
    assertFalse(properties.getValue().containsKey("id"));
    assertEquals("backend_invoice_inbox_document_confirmed",
        NeoTelemetryEvents.BACKEND_INVOICE_INBOX_DOCUMENT_CONFIRMED);
  }

  @Test
  @DisplayName("a failing send does not throw into the caller and the next event is still sent")
  @SuppressWarnings("unchecked")
  void aFailingSendIsSwallowed() throws Exception {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    CountDownLatch first = new CountDownLatch(1);
    doThrow(new IllegalStateException("sink down")).doAnswer(inv -> {
      first.countDown();
      return null;
    }).when(service).emit(any(), any(Map.class), any());
    InvoiceInboxTelemetry.setTelemetryService(service);

    InvoiceInboxTelemetry.documentConfirmed(false, "CLIENT-1");
    InvoiceInboxTelemetry.documentConfirmed(false, "CLIENT-1");

    assertTrue(first.await(5, TimeUnit.SECONDS), "the sender survived the failure");
  }

  @Test
  @DisplayName("without the test seam the runtime service is built once (Mixpanel off) and the event is sent without throwing")
  @SuppressWarnings("unchecked")
  void theRuntimeServiceIsUsedWhenThereIsNoSeam() throws Exception {
    String enabledKey = "etendo.go.mixpanel.enabled";
    String previous = System.getProperty(enabledKey);
    System.setProperty(enabledKey, "false");
    try {
      InvoiceInboxTelemetry.setTelemetryService(null);
      java.lang.reflect.Field field = InvoiceInboxTelemetry.class
          .getDeclaredField("TELEMETRY_SERVICE");
      field.setAccessible(true);
      java.util.concurrent.atomic.AtomicReference<NeoTelemetryService> holder =
          (java.util.concurrent.atomic.AtomicReference<NeoTelemetryService>) field.get(null);

      InvoiceInboxTelemetry.documentConfirmed(false, "CLIENT-1");

      // wait for the sender to go idle instead of for the holder: if runtime() is slow the wait
      // is long enough, and if it throws the sender swallows it and the holder stays null
      awaitSenderIdle();
      NeoTelemetryService built = holder.get();
      InvoiceInboxTelemetry.documentConfirmed(true, null);
      awaitSenderIdle();
      assertEquals(built, holder.get(), "when it is built, it is built once");
    } finally {
      if (previous == null) {
        System.clearProperty(enabledKey);
      } else {
        System.setProperty(enabledKey, previous);
      }
    }
  }

  @Test
  @DisplayName("when the queue is full the event is dropped and the caller is not told")
  @SuppressWarnings("unchecked")
  void aFullQueueDropsTheEvent() throws Exception {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    CountDownLatch release = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(inv -> {
      release.await(10, TimeUnit.SECONDS);
      return null;
    }).when(service).emit(any(), any(Map.class), any());
    InvoiceInboxTelemetry.setTelemetryService(service);
    try {
      // one event blocks the single sender, the next 1000 fill the queue, the rest is dropped
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        for (int i = 0; i < 1100; i++) {
          InvoiceInboxTelemetry.documentConfirmed(false, "CLIENT-1");
        }
      });
    } finally {
      release.countDown();
      // the queued events drain into this test's mock before the seam is reset
      awaitSenderIdle();
    }
  }
}
