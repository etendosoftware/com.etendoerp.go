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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * ETP-6228: the bounce-pair events are sent from a background thread with no session, so the
 * identity is captured through the service on the calling thread and handed to the sender.
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceKpiTelemetry
 */
class BankBounceKpiTelemetryIdentityTest {

  private static final String CLIENT_ID = "C1";

  @AfterEach
  void tearDown() {
    BankBounceKpiTelemetry.setTelemetryService(null);
  }

  @Test
  void theIdentityCapturedOnTheCallingThreadReachesTheSenderThread() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    BankBounceKpiTelemetry.setTelemetryService(service);
    NeoTelemetryIdentity identity = new NeoTelemetryIdentity("U1", CLIENT_ID, "OWNER", "productive");
    AtomicReference<Thread> capturedOn = new AtomicReference<>();
    AtomicReference<Thread> emittedOn = new AtomicReference<>();
    AtomicReference<NeoTelemetryIdentity> seen = new AtomicReference<>();
    when(service.captureIdentity(CLIENT_ID, null)).thenAnswer(invocation -> {
      capturedOn.set(Thread.currentThread());
      return identity;
    });
    doAnswer(invocation -> {
      emittedOn.set(Thread.currentThread());
      seen.set(NeoTelemetryIdentity.handedOff());
      return null;
    }).when(service).emit(eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED), anyMap(),
        eq(CLIENT_ID));

    BankBounceKpiTelemetry.sendNow(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED,
        new HashMap<String, Object>(), CLIENT_ID);

    verify(service, timeout(2000)).emit(eq(NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED),
        anyMap(), eq(CLIENT_ID));
    assertSame(Thread.currentThread(), capturedOn.get());
    assertNotSame(Thread.currentThread(), emittedOn.get());
    assertSame(identity, seen.get());
  }
}
