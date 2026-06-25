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

import java.util.LinkedHashMap;
import java.util.Map;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Emits aggregate KPI telemetry for bank reconciliation matching.
 */
final class ReconciliationKpiTelemetry {

  private static final String KPI_ID = "bank_reconciliation_match_rate";
  private static final String MODULE = "bank-reconciliation";
  private static final String ENTITY_TYPE = "bankStatementLine";
  private static final String FLOW_AUTO_MATCH = "auto-match";
  private static final String FLOW_APPLY_SUGGESTIONS = "apply-suggestions";
  private static final String KEY_COUNT = "count";
  private static final String KEY_CORRECT_COUNT = "correctCount";
  private static final String KEY_TOTAL = "total";

  private ReconciliationKpiTelemetry() {
  }

  static void emitBankMatchAttempted(int totalLines, int groupsFound, int operationsToLink) {
    Map<String, Object> properties = baseProperties(FLOW_AUTO_MATCH);
    properties.put(KEY_TOTAL, totalLines);
    properties.put(KEY_COUNT, groupsFound);
    properties.put(KEY_CORRECT_COUNT, operationsToLink);
    NeoTelemetryService.runtime().emit(NeoTelemetryEvents.BACKEND_BANK_MATCH_ATTEMPTED,
        properties);
  }

  static void emitReconciliationMatchEvaluated(
      int totalGroups, int attemptedGroups, int successfulGroups) {
    Map<String, Object> properties = baseProperties(FLOW_APPLY_SUGGESTIONS);
    properties.put(KEY_TOTAL, totalGroups);
    properties.put(KEY_COUNT, attemptedGroups);
    properties.put(KEY_CORRECT_COUNT, successfulGroups);
    NeoTelemetryService.runtime().emit(
        NeoTelemetryEvents.BACKEND_BANK_RECONCILIATION_MATCH_EVALUATED, properties);
  }

  private static Map<String, Object> baseProperties(String flow) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("source", "neo");
    properties.put("kpiId", KPI_ID);
    properties.put("module", MODULE);
    properties.put("entityType", ENTITY_TYPE);
    properties.put("flow", flow);
    return properties;
  }
}
