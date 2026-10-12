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

import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The behaviour hints each tool declares in {@code tools/list} (MCP 2025-03-26 tool annotations).
 *
 * <p>Every tool gets all four hints explicitly. The spec defaults are {@code readOnlyHint=false},
 * <b>{@code destructiveHint=true}</b>, {@code idempotentHint=false}, {@code openWorldHint=true},
 * so annotating only the readers would leave {@code create} reported as destructive.
 * {@code openWorldHint} is {@code false} everywhere: every tool stays inside the ERP.</p>
 *
 * <table>
 *   <caption>Classification (ETP-5639)</caption>
 *   <tr><th>Tools</th><th>readOnly</th><th>destructive</th><th>idempotent</th></tr>
 *   <tr><td>{@link #READ_ONLY} and every {@code generate_*} report</td><td>true</td><td>false</td>
 *       <td>true</td></tr>
 *   <tr><td>{@link #ADDITIVE}</td><td>false</td><td>false</td><td>false</td></tr>
 *   <tr><td>{@link #DELETE}</td><td>false</td><td>true</td><td>true</td></tr>
 *   <tr><td>{@link #MODIFYING} and every process tool (the default)</td><td>false</td>
 *       <td>true</td><td>false</td></tr>
 * </table>
 *
 * <p>Fixed in code, with no {@code MCP_CONFIG} override: annotations are per tool, and the shared
 * {@code etendo_update} / {@code etendo_batch} / {@code etendo_action} serve every entity, so a per-entity
 * setting could not reach them. Anything unclassified — a process tool, or a new tool nobody
 * placed — falls to the conservative {@code MODIFYING} answer; {@code McpToolAnnotationsTest} pins
 * the explicit sets so a new fixed tool has to take a position.</p>
 */
final class McpToolAnnotations {

  /** Tools that only read: list, get, schema, discovery, lookups, docs and report previews. */
  static final Set<String> READ_ONLY = Set.of(
      McpConstants.TOOL_NEO_LIST,
      McpConstants.TOOL_NEO_GET,
      McpConstants.TOOL_NEO_SCHEMA,
      McpConstants.TOOL_NEO_DISCOVER,
      McpConstants.TOOL_NEO_SELECTORS,
      McpConstants.TOOL_NEO_DEFAULTS,
      McpConstants.TOOL_DOCS,
      McpConstants.TOOL_NEO_WIDGET,
      McpConstants.TOOL_NEO_VECTOR_SEARCH,
      McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD);

  /** Tools that add something new and never change or remove what exists. */
  static final Set<String> ADDITIVE = Set.of(
      McpConstants.TOOL_NEO_CREATE,
      McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD,
      McpConstants.TOOL_NEO_UPLOAD_IMAGE,
      McpConstants.TOOL_NEO_FEEDBACK);

  /** The removal tool: destructive, and deleting the same record twice changes nothing more. */
  static final Set<String> DELETE = Set.of(McpConstants.TOOL_NEO_DELETE);

  /** Fixed tools that can change existing data; process tools are treated the same way. */
  static final Set<String> MODIFYING = Set.of(
      McpConstants.TOOL_NEO_UPDATE,
      McpConstants.TOOL_NEO_BATCH,
      McpConstants.TOOL_NEO_ACTION,
      McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN);

  private McpToolAnnotations() {
  }

  /**
   * The {@code annotations} object for one tool.
   *
   * @param toolName the tool's name as {@code tools/list} publishes it
   * @return the four hints, never {@code null}
   * @throws JSONException if the object cannot be built
   */
  static JSONObject of(String toolName) throws JSONException {
    if (isReadOnly(toolName)) {
      return hints(true, false, true);
    }
    if (ADDITIVE.contains(toolName)) {
      return hints(false, false, false);
    }
    return hints(false, true, DELETE.contains(toolName));
  }

  static boolean isReadOnly(String toolName) {
    return toolName != null && (READ_ONLY.contains(toolName)
        || toolName.startsWith(McpConstants.GENERATE_PREFIX));
  }

  private static JSONObject hints(boolean readOnly, boolean destructive, boolean idempotent)
      throws JSONException {
    JSONObject annotations = new JSONObject();
    annotations.put("readOnlyHint", readOnly);
    annotations.put("destructiveHint", destructive);
    annotations.put("idempotentHint", idempotent);
    annotations.put("openWorldHint", false);
    return annotations;
  }
}
