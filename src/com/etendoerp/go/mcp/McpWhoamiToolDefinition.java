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

package com.etendoerp.go.mcp;

import static com.etendoerp.go.mcp.McpJsonSchema.booleanProp;
import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The definition of {@code etendo_whoami} (ETP-6061, gap G17), kept out of {@link ToolRegistry}
 * (java:S1448). Its runtime is {@link McpWhoamiTool}.
 */
final class McpWhoamiToolDefinition {

  private McpWhoamiToolDefinition() {
  }

  /**
   * Builds the tool definition.
   *
   * @return the definition, with {@code includePermissions} as its only argument
   */
  static McpToolDefinition definition() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpWhoamiTool.PARAM_INCLUDE_PERMISSIONS, booleanProp(
        "Optional, default false. true also lists the application windows and reports your role "
            + "can reach, grouped by menu category, each with its access (full or read-only) and "
            + "the spec names to use with the other tools (specs for a window, spec for a "
            + "report; an empty specs means the window is not in this catalog). Anything not "
            + "listed is not reachable with this role. Use it to tell whether a refusal comes "
            + "from the role before trying anything else."));
    return new McpToolDefinition(
        McpConstants.TOOL_NEO_WHOAMI,
        "Who this connection is: the user, the role (and whether it is an administrator), the "
            + "client, the organization and the default warehouse every other tool runs under, "
            + "plus what the role and token can do (showAccountingFields, the granted scopes), "
            + "today's server date and time zone, the organization's currency and the user's "
            + "language. Changes nothing and is cheap: call it first, and again whenever a call "
            + "is refused unexpectedly. Each connection can use a different role, so never "
            + "assume the role of a previous session.",
        buildObjectSchema(props, List.of()));
  }
}
