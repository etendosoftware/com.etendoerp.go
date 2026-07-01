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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

/**
 * Placeholder for future NEO-native report wiring.
 *
 * <p>Jasper runtime execution was removed per ETP-4255: Etendo Go / NEO Headless / MCP
 * must never execute Jasper, JRXML, classic AD_Process report execution, or
 * {@code ReportingUtils.exportJR}. Report generation is now NEO-native-handlers-only —
 * a report spec is callable only when it is backed by a {@code NeoHandler} CDI bean
 * (matched by {@code ETGO_SF_ENTITY.Java_Qualifier}), which returns report data as JSON.
 * Report specs without such a handler are surfaced as non-callable with a stable
 * {@code not_configured_for_report_generation} status by
 * {@link com.etendoerp.go.schemaforge.util.NeoReportCallability}.</p>
 *
 * <p>Jasper/JRXML metadata is preserved only as offline migration input/provenance in
 * Schema Forge tooling; it never implies runtime report execution by Etendo Go.
 * jsreport integration is out of scope for ETP-4255.</p>
 *
 * <p>This class is intentionally an empty documented shell to host future NEO-native
 * report wiring without reintroducing any Jasper runtime path.</p>
 */
public final class NeoReportService {

  private NeoReportService() {
  }
}
