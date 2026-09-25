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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Enum helpers for the report specs whose handler declares named actions (ETP-5468), used by
 * {@link ToolRegistry} to add them to the {@code neo_schema} and {@code neo_action} spec enums.
 *
 * <p>Extracted from {@link ToolRegistry} so that class stays under the Sonar per-class
 * method-count limit (java:S1448). Behavior is unchanged; it logs under the {@code ToolRegistry}
 * category as before.</p>
 */
final class ToolRegistryActionSpecs {

  private static final Logger log = LogManager.getLogger(ToolRegistry.class);

  private ToolRegistryActionSpecs() {
    // utility class — no instances
  }

  /**
   * Whether a report spec serves named actions through {@code neo_action} (ETP-5468): its handler
   * declares {@code NeoHandler#actionContracts()} and the role passes the same report-spec gate the
   * UI does. Independent of {@link NeoReportCallability}: such a spec is not a report generator
   * (IMP-19 keeps its {@code generate_*} tool retired) but it does have an action surface.
   */
  static boolean isActionReportSpec(SFSpec spec) {
    try {
      return "R".equals(spec.getSpecType())
          && NeoAccessUtils.hasReportSpecAccess(spec, "GET")
          && NeoActionContract.resolve(spec).isPresent();
    } catch (Exception e) {
      log.warn("Could not probe the action contracts of spec '{}': {}", spec.getName(),
          e.getMessage());
      return false;
    }
  }

  /**
   * The window-spec enum extended with the action-serving report specs (ETP-5468), re-sorted.
   * Returns {@code windowSpecs} itself when there are none, so the enum is unchanged.
   */
  static List<String> withActionSpecs(List<String> windowSpecs,
      List<String> actionReportSpecs) {
    if (actionReportSpecs == null || actionReportSpecs.isEmpty()) {
      return windowSpecs;
    }
    List<String> merged = new ArrayList<>(windowSpecs);
    merged.addAll(actionReportSpecs);
    Collections.sort(merged);
    return merged;
  }
}
