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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Enforces MCP tool authorization at execution time.
 */
final class McpAuthorizationService {

  private static final String SCOPE_ALL = "neo:*";
  private static final String SCOPE_READ = "neo:read";
  private static final String SCOPE_WRITE = "neo:write";
  private static final String SCOPE_PROCESS = "neo:process";
  private static final String SCOPE_REPORT = "neo:report";

  private McpAuthorizationService() {
  }

  static Set<String> parseScopes(String scopes) {
    if (scopes == null || scopes.trim().isEmpty()) {
      return Collections.emptySet();
    }
    return new HashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
  }

  static void authorizeToolCall(String toolName, Set<String> scopes) {
    String requiredScope = requiredScopeFor(toolName);
    if (hasScope(scopes, requiredScope)) {
      return;
    }
    throw new SecurityException(
        "MCP tool '" + toolName + "' requires scope '" + requiredScope + "'");
  }

  private static String requiredScopeFor(String toolName) {
    if (toolName == null || toolName.trim().isEmpty()) {
      throw new SecurityException("MCP tool name is required");
    }
    switch (toolName) {
      case "neo_discover":
      case "neo_list":
      case "neo_get":
      case "neo_selectors":
      case "neo_defaults":
      case "neo_schema":
        return SCOPE_READ;
      case "neo_create":
      case "neo_update":
      case "neo_delete":
        return SCOPE_WRITE;
      default:
        return toolName.startsWith(McpConstants.GENERATE_PREFIX)
            ? SCOPE_REPORT
            : SCOPE_PROCESS;
    }
  }

  private static boolean hasScope(Set<String> scopes, String requiredScope) {
    return scopes != null
        && (scopes.contains(SCOPE_ALL) || scopes.contains(requiredScope));
  }
}
