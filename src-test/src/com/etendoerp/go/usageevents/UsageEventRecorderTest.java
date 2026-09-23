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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Unit specs for {@link UsageEventRecorder} (ETP-5462): the opt-out flag, the D4 rejection of
 * unknown types and invalid sources, and the "never throws" contract.
 *
 * <p>{@code Openbravo.properties} is mocked (same isolation as {@code DemoDataTransferFlagTest}), and
 * the writer is replaced through {@link UsageEventRecorder#setWriter} — by a Mockito mock when the
 * spec is "this never reaches the writer", by a real writer over a capturing sink when it is "this
 * does reach the sink". The production writer is never started: {@link #restoreWriter()} puts back
 * whatever was there, and no spec records a valid event without a writer injected.</p>
 *
 * <p>The rejection throttle is a static with a real clock and no test seam, so "ERROR only on the
 * first rejection inside the interval" is made deterministic by re-arming it (resetting its
 * last-logged instant) through reflection before the specs that assert on the ERROR line.</p>
 */
class UsageEventRecorderTest {

  private MockedStatic<OBPropertiesProvider> propertiesStatic;
  private final Properties openbravoProperties = new Properties();
  private UsageEventWriter mockWriter;

  @BeforeEach
  void isolate() {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(openbravoProperties);
    propertiesStatic = mockStatic(OBPropertiesProvider.class);
    propertiesStatic.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    mockWriter = mock(UsageEventWriter.class);
    UsageEventRecorder.setWriter(mockWriter);
  }

  @AfterEach
  void restoreWriter() {
    UsageEventRecorder.setWriter(null);
    propertiesStatic.close();
  }

  private static UsageEvent known() {
    return UsageEvent.builder().eventType(UsageEventTypes.AI_SUPPORT_MESSAGE).build();
  }

  private static void rearmRejectionLog() throws ReflectiveOperationException {
    Field rejected = UsageEventRecorder.class.getDeclaredField("rejected");
    rejected.setAccessible(true);
    Field lastLogAt = LogThrottle.class.getDeclaredField("lastLogAt");
    lastLogAt.setAccessible(true);
    ((AtomicLong) lastLogAt.get(rejected.get(null))).set(0L);
  }

  // ── opt-out flag ──────────────────────────────────────────────────────

  @Test
  void aNullEventIsIgnored() {
    assertDoesNotThrow(() -> UsageEventRecorder.submit(null));
    verify(mockWriter, never()).offer(any());
  }

  @ParameterizedTest
  @ValueSource(strings = { "false", "FALSE", " FALSE ", "False" })
  void anExplicitFalseTurnsRecordingOff(String value) {
    openbravoProperties.setProperty(UsageEventRecorder.PROP_ENABLED, value);
    assertFalse(UsageEventRecorder.isEnabled());
    UsageEventRecorder.submit(known());
    verify(mockWriter, never()).offer(any());
  }

  @ParameterizedTest
  @ValueSource(strings = { "true", "", "  ", "no", "0", "off", "falsey" })
  void anythingButFalseKeepsItOn(String value) {
    openbravoProperties.setProperty(UsageEventRecorder.PROP_ENABLED, value);
    assertTrue(UsageEventRecorder.isEnabled());
  }

  @Test
  void anAbsentFlagMeansEnabledAndTheEventIsOffered() {
    UsageEvent event = known();
    UsageEventRecorder.submit(event);
    verify(mockWriter).offer(event);
  }

  @Test
  void anUnreadableFlagMeansEnabled() {
    propertiesStatic.when(OBPropertiesProvider::getInstance)
        .thenThrow(new IllegalStateException("not initialised"));
    assertTrue(UsageEventRecorder.isEnabled());
    UsageEvent event = known();
    UsageEventRecorder.submit(event);
    verify(mockWriter).offer(event);
  }

  @Test
  void missingPropertiesMeanEnabled() {
    OBPropertiesProvider empty = mock(OBPropertiesProvider.class);
    when(empty.getOpenbravoProperties()).thenReturn(null);
    propertiesStatic.when(OBPropertiesProvider::getInstance).thenReturn(empty);
    assertTrue(UsageEventRecorder.isEnabled());
  }

  @Test
  void aWriterThatThrowsNeverReachesTheCaller() {
    when(mockWriter.offer(any())).thenThrow(new IllegalStateException("boom"));
    assertDoesNotThrow(() -> UsageEventRecorder.submit(known()));
  }

  // ── D4 rejection ──────────────────────────────────────────────────────

  @ParameterizedTest
  @ValueSource(strings = { "ai.agent.messages", "UNKNOWN", "" })
  void anUnknownTypeIsDroppedAndCounted(String type) {
    long before = UsageEventRecorder.getRejectedEvents();
    UsageEventRecorder.submit(UsageEvent.builder().eventType(type).build());
    verify(mockWriter, never()).offer(any());
    assertEquals(before + 1, UsageEventRecorder.getRejectedEvents());
  }

  @Test
  void aNullTypeIsDroppedAndCounted() {
    long before = UsageEventRecorder.getRejectedEvents();
    UsageEventRecorder.submit(UsageEvent.builder().build());
    verify(mockWriter, never()).offer(any());
    assertEquals(before + 1, UsageEventRecorder.getRejectedEvents());
  }

  @ParameterizedTest
  @ValueSource(strings = { "browser", "UI", "" })
  void anInvalidSourceIsDroppedAndCounted(String source) {
    long before = UsageEventRecorder.getRejectedEvents();
    UsageEventRecorder.submit(UsageEvent.builder()
        .eventType(UsageEventTypes.AI_AGENT_MESSAGE).source(source).build());
    verify(mockWriter, never()).offer(any());
    assertEquals(before + 1, UsageEventRecorder.getRejectedEvents());
  }

  @Test
  void aNullSourceIsDropped() {
    UsageEventRecorder.submit(UsageEvent.builder()
        .eventType(UsageEventTypes.AI_AGENT_MESSAGE).source(null).build());
    verify(mockWriter, never()).offer(any());
  }

  @Test
  void rejectionsLogAtErrorOnlyOncePerInterval() throws Exception {
    rearmRejectionLog();
    try (LogCapture logs = LogCapture.of(UsageEventRecorder.class)) {
      UsageEventRecorder.submit(UsageEvent.builder().eventType("first.bad").build());
      UsageEventRecorder.submit(UsageEvent.builder().eventType("second.bad").build());
      UsageEventRecorder.submit(UsageEvent.builder()
          .eventType(UsageEventTypes.AI_AGENT_MESSAGE).source("nope").build());

      List<String> errors = logs.messages(Level.ERROR);
      assertEquals(1, errors.size(), errors.toString());
      assertTrue(errors.get(0).contains("unknown event type"), errors.get(0));
      assertTrue(errors.get(0).contains("'first.bad'"), errors.get(0));
    }
  }

  @Test
  void anInvalidSourceIsReportedAsSuch() throws Exception {
    rearmRejectionLog();
    try (LogCapture logs = LogCapture.of(UsageEventRecorder.class)) {
      UsageEventRecorder.submit(UsageEvent.builder()
          .eventType(UsageEventTypes.AI_AGENT_MESSAGE).source("browser").build());
      String error = logs.messages(Level.ERROR).get(0);
      assertTrue(error.contains("invalid source"), error);
      assertTrue(error.contains("'browser'"), error);
    }
  }

  @Test
  void lineBreaksInARejectedTypeCannotForgeLogLines() throws Exception {
    rearmRejectionLog();
    try (LogCapture logs = LogCapture.of(UsageEventRecorder.class)) {
      UsageEventRecorder.submit(UsageEvent.builder()
          .eventType("evil\r\nFAKE ERROR line\tx").build());
      String error = logs.messages(Level.ERROR).get(0);
      assertFalse(error.contains("\r") || error.contains("\n") || error.contains("\t"), error);
      assertTrue(error.contains("evil__FAKE ERROR line_x"), error);
    }
  }

  @Test
  void printableStripsBreaksAndClips() {
    assertNull(UsageEventRecorder.printable(null));
    assertEquals("a_b_c_d", UsageEventRecorder.printable("a\rb\nc\td"));
    String clipped = UsageEventRecorder.printable("x".repeat(500));
    assertEquals(80, clipped.length());
    assertTrue(clipped.endsWith("..."));
  }

  // ── reaching the sink ─────────────────────────────────────────────────

  @Test
  void everyKnownTypeReachesTheSinkAndRejectedOnesNever() throws Exception {
    BlockingQueue<UsageEvent> seen = new LinkedBlockingQueue<>();
    UsageEventWriter writer = UsageEventWriter.start(100, batch -> {
      seen.addAll(batch);
      return batch.size();
    }, System::currentTimeMillis);
    UsageEventRecorder.setWriter(writer);
    try {
      UsageEventRecorder.submit(UsageEvent.builder().eventType("not.a.type").build());
      for (String type : UsageEventTypes.all()) {
        for (String source : List.of(UsageEvent.SOURCE_BACKEND, UsageEvent.SOURCE_UI,
            UsageEvent.SOURCE_AI_BFF, UsageEvent.SOURCE_MCP)) {
          UsageEventRecorder.submit(UsageEvent.builder().eventType(type).source(source).build());
        }
      }
      int expected = UsageEventTypes.all().size() * 4;
      for (int i = 0; i < expected; i++) {
        UsageEvent event = seen.poll(10, TimeUnit.SECONDS);
        // The queue is FIFO: had the rejected event been enqueued, it would arrive first.
        assertTrue(event != null && UsageEventTypes.isKnown(event.eventType()), "got " + event);
      }
      assertNull(seen.poll(), "nothing else reached the sink");
      assertEquals(0L, UsageEventRecorder.getDroppedRows());
    } finally {
      writer.shutdown(5_000L);
    }
  }

  // ── shutdown ──────────────────────────────────────────────────────────

  @Test
  void shutdownStopsTheInjectedWriter() {
    UsageEventRecorder.shutdown();
    verify(mockWriter).shutdown();
  }

  @Test
  void shutdownNeverThrows() {
    doThrow(new IllegalStateException("boom")).when(mockWriter).shutdown();
    assertDoesNotThrow(UsageEventRecorder::shutdown);
  }

  @Test
  void shutdownWithoutAWriterIsANoOp() {
    UsageEventRecorder.setWriter(null);
    assertDoesNotThrow(UsageEventRecorder::shutdown);
    assertEquals(0L, UsageEventRecorder.getDroppedRows());
  }

  @Test
  void eventsRecordedAfterShutdownAreCountedAsLost() {
    UsageEventWriter writer = UsageEventWriter.start(100, List::size, System::currentTimeMillis);
    UsageEventRecorder.setWriter(writer);
    UsageEventRecorder.shutdown();

    UsageEventRecorder.submit(known());

    assertEquals(1L, UsageEventRecorder.getDroppedRows());
  }

  @Test
  void droppedRowsAreTheWritersCount() {
    when(mockWriter.getDroppedRows()).thenReturn(7L);
    assertEquals(7L, UsageEventRecorder.getDroppedRows());
  }
}
