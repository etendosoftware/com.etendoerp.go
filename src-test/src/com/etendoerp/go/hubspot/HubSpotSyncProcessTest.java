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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;

import com.etendoerp.go.hubspot.HubSpotEventSender.Outcome;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * The "HubSpot Sync" background process: what a pass leaves on the process monitor.
 *
 * @covers com.etendoerp.go.hubspot.HubSpotSyncProcess
 */
class HubSpotSyncProcessTest {

  private static final HubSpotConfiguration ENABLED = new HubSpotConfiguration("t",
      "https://hubspot.test", "p", "t", "a", "w", "l");

  private static OBError run(HubSpotSyncService service) {
    ProcessBundle bundle = mock(ProcessBundle.class, Mockito.CALLS_REAL_METHODS);
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      new HubSpotSyncProcess() {
        @Override
        HubSpotSyncService newService() {
          return service;
        }
      }.doExecute(bundle);
    }
    return (OBError) bundle.getResult();
  }

  @Test
  void aPassWithoutTokenSaysSo() {
    HubSpotSyncService disabled = new HubSpotSyncService(new HubSpotConfiguration(null,
        "https://hubspot.test", "p", "t", "a", "w", "l"), mock(HubSpotOutboxStore.class),
        mock(DemoTrials.class), mock(HubSpotEventSender.class));

    OBError result = run(disabled);

    assertEquals("Success", result.getType());
    assertEquals("HubSpot Sync", result.getTitle());
    assertEquals("no HubSpot token configured; nothing sent", result.getMessage());
  }

  @Test
  void aRefusedEventMakesThePassAWarning() {
    HubSpotOutboxStore store = mock(HubSpotOutboxStore.class);
    HubSpotEventSender sender = mock(HubSpotEventSender.class);
    PendingEvent event = new PendingEvent("1", "A", HubSpotOutboxStore.ACCOUNT_CREATED, null,
        null, 0, null, "a@b.es", "A");
    when(store.findPending(HubSpotSyncService.BATCH_SIZE)).thenReturn(List.of(event));
    when(sender.send(any(), any())).thenReturn(Outcome.failed("HTTP 400"));

    OBError result = run(new HubSpotSyncService(ENABLED, store, mock(DemoTrials.class), sender));

    assertEquals("Warning", result.getType());
    assertEquals("0 event(s) detected, 0 sent, 0 to retry, 1 failed, 0 skipped",
        result.getMessage());
  }

  @Test
  void aPassThatBreaksIsReportedAsAnError() {
    HubSpotOutboxStore store = mock(HubSpotOutboxStore.class);
    when(store.findOpenDemos()).thenThrow(new IllegalStateException("database down"));

    OBError result = run(new HubSpotSyncService(ENABLED, store, mock(DemoTrials.class),
        mock(HubSpotEventSender.class)));

    assertEquals("Error", result.getType());
    assertEquals("database down", result.getMessage());
  }
}
