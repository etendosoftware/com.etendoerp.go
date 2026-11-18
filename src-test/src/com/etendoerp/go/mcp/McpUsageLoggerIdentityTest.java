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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * ETP-6228: the Mixpanel projection of an MCP row runs on the writer thread, which has no session
 * and never touches {@code OBDal}, so its identity is captured on the caller's thread.
 *
 * @covers com.etendoerp.go.mcp.McpUsageLogger
 */
class McpUsageLoggerIdentityTest {

  @AfterEach
  void tearDown() {
    McpUsageLogger.setTelemetryService(null);
  }

  private static MockedStatic<OBPropertiesProvider> properties(String mixpanelEnabled) {
    MockedStatic<OBPropertiesProvider> provider = Mockito.mockStatic(OBPropertiesProvider.class);
    OBPropertiesProvider instance = mock(OBPropertiesProvider.class);
    Properties props = new Properties();
    if (mixpanelEnabled != null) {
      props.setProperty("mcp.telemetry.mixpanel.enabled", mixpanelEnabled);
    }
    when(instance.getOpenbravoProperties()).thenReturn(props);
    provider.when(OBPropertiesProvider::getInstance).thenReturn(instance);
    return provider;
  }

  @Test
  void capturesThroughTheServiceUsingTheRowsClientAndUser() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    NeoTelemetryIdentity identity = new NeoTelemetryIdentity("U1", "C1", "OWNER", "demo");
    when(service.captureIdentity("C1", "U1")).thenReturn(identity);
    McpUsageLogger.setTelemetryService(service);

    try (MockedStatic<OBPropertiesProvider> provider = properties(null)) {
      assertSame(identity, McpUsageLogger.captureIdentity("C1", "U1"));
    }
  }

  @Test
  void makesNoLookupWhenTheProjectionIsOff() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    McpUsageLogger.setTelemetryService(service);

    try (MockedStatic<OBPropertiesProvider> provider = properties("false")) {
      assertSame(NeoTelemetryIdentity.NONE, McpUsageLogger.captureIdentity("C1", "U1"));
    }
    verify(service, never()).captureIdentity(Mockito.any(), Mockito.any());
  }

  @Test
  void aFailedCaptureNeverEscalates() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);
    when(service.captureIdentity("C1", "U1")).thenThrow(new IllegalStateException("db down"));
    McpUsageLogger.setTelemetryService(service);

    try (MockedStatic<OBPropertiesProvider> provider = properties(null)) {
      assertSame(NeoTelemetryIdentity.NONE, McpUsageLogger.captureIdentity("C1", "U1"));
    }
  }
}
