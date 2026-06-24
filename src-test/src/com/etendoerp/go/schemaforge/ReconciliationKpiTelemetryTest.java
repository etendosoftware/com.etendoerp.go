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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for bank reconciliation KPI telemetry payloads.
 */
public class ReconciliationKpiTelemetryTest {

  @Test
  public void emitBankMatchAttemptedUsesSafeAggregateProperties() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);

    try (MockedStatic<NeoTelemetryService> telemetry = mockStatic(NeoTelemetryService.class)) {
      telemetry.when(NeoTelemetryService::logging).thenReturn(service);

      ReconciliationKpiTelemetry.emitBankMatchAttempted(8, 3, 4);

      verify(service).emit(eq(NeoTelemetryEvents.BACKEND_BANK_MATCH_ATTEMPTED),
          argThat(properties -> hasProperties(properties, "auto-match", 8, 3, 4)));
    }
  }

  @Test
  public void emitReconciliationMatchEvaluatedUsesSafeAggregateProperties() {
    NeoTelemetryService service = mock(NeoTelemetryService.class);

    try (MockedStatic<NeoTelemetryService> telemetry = mockStatic(NeoTelemetryService.class)) {
      telemetry.when(NeoTelemetryService::logging).thenReturn(service);

      ReconciliationKpiTelemetry.emitReconciliationMatchEvaluated(5, 5, 4);

      verify(service).emit(eq(NeoTelemetryEvents.BACKEND_BANK_RECONCILIATION_MATCH_EVALUATED),
          argThat(properties -> hasProperties(properties, "apply-suggestions", 5, 5, 4)));
    }
  }

  private static boolean hasProperties(Map<String, ?> properties, String flow, int total,
      int count, int correctCount) {
    assertEquals("neo", properties.get("source"));
    assertEquals("bank_reconciliation_match_rate", properties.get("kpiId"));
    assertEquals("bank-reconciliation", properties.get("module"));
    assertEquals("bankStatementLine", properties.get("entityType"));
    assertEquals(flow, properties.get("flow"));
    assertEquals(total, properties.get("total"));
    assertEquals(count, properties.get("count"));
    assertEquals(correctCount, properties.get("correctCount"));
    return true;
  }
}
