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

import org.openbravo.base.exception.OBSecurityException;

import com.etendoerp.go.oauth2.ApiScopes;

/**
 * Enforces MCP tool authorization at execution time. Legacy browser JWT sessions
 * receive the broad MCP scope set only after JWT validation; role/window access
 * remains the authoritative operation-level permission check.
 */
final class McpAuthorizationService {

  private static final String SCOPE_READ = ApiScopes.READ;
  private static final String SCOPE_WRITE = ApiScopes.WRITE;
  private static final String SCOPE_PROCESS = ApiScopes.PROCESS;
  private static final String SCOPE_REPORT = ApiScopes.REPORT;

  private McpAuthorizationService() {
  }

  static Set<String> parseScopes(String scopes) {
    if (scopes == null || scopes.trim().isEmpty()) {
      return Collections.emptySet();
    }
    return new HashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
  }

  static void authorizeResourceRead(Set<String> scopes) {
    if (hasScope(scopes, SCOPE_READ)) {
      return;
    }
    throw new OBSecurityException(
        "MCP resources require scope '" + SCOPE_READ + "'");
  }


  static void authorizeToolCall(String toolName, Set<String> scopes) {
    String requiredScope = requiredScopeFor(toolName);
    if (hasScope(scopes, requiredScope)) {
      return;
    }
    throw new OBSecurityException(
        "MCP tool '" + toolName + "' requires scope '" + requiredScope + "'");
  }

  private static String requiredScopeFor(String toolName) {
    if (toolName == null || toolName.trim().isEmpty()) {
      throw new OBSecurityException("MCP tool name is required");
    }
    switch (toolName) {
      case "etendo_discover":
      case "etendo_list":
      case "etendo_get":
      case "etendo_selectors":
      case "etendo_defaults":
      case "etendo_schema":
      case "docs":
      case McpConstants.TOOL_NEO_WIDGET:
      case McpConstants.TOOL_NEO_VECTOR_SEARCH:
      // B3: read-tier on purpose. Reporting friction writes no business data, and gating it behind
      // etendo:write would silence exactly the read-only sessions most likely to get lost.
      case McpConstants.TOOL_NEO_FEEDBACK:
        return SCOPE_READ;
      case "etendo_create":
      case "etendo_update":
      case "etendo_delete":
      case "etendo_action":
      // ETP-5184: all three image-upload tools are write-tier. The two upload tools create an
      // AD_Image row; the status lookup is bundled with them deliberately — it is a step of the
      // write flow and has nothing to offer a read-only session.
      case McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD:
      case McpConstants.TOOL_NEO_UPLOAD_IMAGE:
      case McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD:
        return SCOPE_WRITE;
      default:
        return toolName.startsWith(McpConstants.GENERATE_PREFIX)
            ? SCOPE_REPORT
            : SCOPE_PROCESS;
    }
  }

  private static boolean hasScope(Set<String> scopes, String requiredScope) {
    return ApiScopes.grants(scopes, requiredScope);
  }
}
