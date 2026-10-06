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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Tool annotations (MCP 2025-03-26): every tool declares all four hints, and the read-only set is
 * pinned so a new tool must take an explicit position instead of inheriting a default.
 *
 * @covers com.etendoerp.go.mcp.McpToolAnnotations
 * @covers com.etendoerp.go.mcp.McpServlet
 */
class McpToolAnnotationsTest {

  /** Every fixed tool ToolRegistry publishes; keep in step with McpToolTitlesTest.FIXED_TOOLS. */
  private static final List<String> FIXED_TOOLS = List.of("etendo_discover", "etendo_list", "etendo_get",
      "etendo_create", "etendo_update", "etendo_delete", "etendo_selectors", "etendo_defaults", "etendo_schema",
      "etendo_batch", "etendo_action", "etendo_widget", "etendo_vector_search", "etendo_upload_image",
      "etendo_request_image_upload", "etendo_get_image_upload", "etendo_generate_amortization_plan",
      "etendo_feedback", "docs");

  @Test
  void readOnlySetIsPinned() {
    assertEquals(Set.of("etendo_list", "etendo_get", "etendo_schema", "etendo_discover", "etendo_selectors",
        "etendo_defaults", "docs", "etendo_widget", "etendo_vector_search", "etendo_get_image_upload"),
        McpToolAnnotations.READ_ONLY);
  }

  @Test
  void everyFixedToolTakesAnExplicitPosition() {
    Set<String> classified = new HashSet<>();
    for (Set<String> group : List.of(McpToolAnnotations.READ_ONLY, McpToolAnnotations.ADDITIVE,
        McpToolAnnotations.DELETE, McpToolAnnotations.MODIFYING)) {
      for (String tool : group) {
        assertTrue(classified.add(tool), tool + " is classified twice");
      }
    }
    for (String tool : FIXED_TOOLS) {
      assertTrue(classified.contains(tool), tool + " has no explicit annotation class");
    }
    for (String tool : McpConstants.TOOLS_RENAMED_FROM_NEO) {
      assertTrue(classified.contains(tool), tool + " has no explicit annotation class");
    }
  }

  @Test
  void hintsFollowTheClassification() throws Exception {
    assertHints("etendo_list", true, false, true);
    assertHints("generate_balance_sheet", true, false, true);
    assertHints("etendo_create", false, false, false);
    assertHints("etendo_feedback", false, false, false);
    assertHints("etendo_delete", false, true, true);
    assertHints("etendo_update", false, true, false);
    assertHints("etendo_batch", false, true, false);
    assertHints("etendo_generate_amortization_plan", false, true, false);
    assertHints("complete_order", false, true, false);
  }

  @Test
  void toolsListEntryCarriesTheFourHints() throws Exception {
    McpToolDefinition tool = new McpToolDefinition("etendo_get", "Get a record",
        Map.of("type", "object"));

    JSONObject entry = new McpServlet().describeTool(tool, "en_US");

    JSONObject annotations = entry.getJSONObject("annotations");
    assertEquals(4, annotations.length());
    assertTrue(annotations.getBoolean("readOnlyHint"));
    assertFalse(annotations.getBoolean("openWorldHint"));
  }

  private static void assertHints(String tool, boolean readOnly, boolean destructive,
      boolean idempotent) throws Exception {
    JSONObject hints = McpToolAnnotations.of(tool);
    assertEquals(readOnly, hints.getBoolean("readOnlyHint"), tool);
    assertEquals(destructive, hints.getBoolean("destructiveHint"), tool);
    assertEquals(idempotent, hints.getBoolean("idempotentHint"), tool);
    assertFalse(hints.getBoolean("openWorldHint"), tool);
  }
}
