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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code MCP_CONFIG.fields.userRequired} override of an etendo_schema field descriptor
 * (ETP-5791).
 *
 * <p>Extracted from {@link McpSchemaFieldBuilder} (Sonar S1448 — "too many methods"), which calls
 * it for every descriptor it builds.</p>
 */
final class McpUserRequiredOverride {

  private McpUserRequiredOverride() {
  }

  /**
   * Replaces the derived {@code userRequired} with the {@code MCP_CONFIG.fields.userRequired}
   * override when one is configured; a no-op otherwise, and always for a button, which carries no
   * payload value to require. Runs after {@code McpSchemaFieldBuilder.addVisibility}, so it
   * overrides the structural rule, and before
   * {@link McpSchemaFieldBuilder#applyPreconditionRequirements}, so an explicit precondition can
   * still force the flag on. The raw {@code required} (AD NOT NULL) is left untouched.
   *
   * @param fieldObj the built descriptor, mutated in place
   * @param override the configured flag, or {@code null} when none is configured
   * @throws JSONException if the flag cannot be written into the descriptor
   */
  static void apply(JSONObject fieldObj, Boolean override) throws JSONException {
    if (override == null
        || McpSchemaFieldBuilder.TYPE_BUTTON.equals(fieldObj.optString("type", null))) {
      return;
    }
    fieldObj.put(McpSchemaFieldBuilder.KEY_USER_REQUIRED, override.booleanValue());
  }
}
