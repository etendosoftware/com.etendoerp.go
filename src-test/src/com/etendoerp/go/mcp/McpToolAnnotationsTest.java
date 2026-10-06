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
  private static final List<String> FIXED_TOOLS = List.of("neo_discover", "neo_list", "neo_get",
      "neo_create", "neo_update", "neo_delete", "neo_selectors", "neo_defaults", "neo_schema",
      "neo_batch", "neo_action", "neo_widget", "neo_vector_search", "neo_upload_image",
      "neo_request_image_upload", "neo_get_image_upload", "neo_generate_amortization_plan",
      "neo_feedback", "docs");

  @Test
  void readOnlySetIsPinned() {
    assertEquals(Set.of("neo_list", "neo_get", "neo_schema", "neo_discover", "neo_selectors",
        "neo_defaults", "docs", "neo_widget", "neo_vector_search", "neo_get_image_upload"),
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
  }

  @Test
  void hintsFollowTheClassification() throws Exception {
    assertHints("neo_list", true, false, true);
    assertHints("generate_balance_sheet", true, false, true);
    assertHints("neo_create", false, false, false);
    assertHints("neo_feedback", false, false, false);
    assertHints("neo_delete", false, true, true);
    assertHints("neo_update", false, true, false);
    assertHints("neo_batch", false, true, false);
    assertHints("neo_generate_amortization_plan", false, true, false);
    assertHints("complete_order", false, true, false);
  }

  @Test
  void toolsListEntryCarriesTheFourHints() throws Exception {
    McpToolDefinition tool = new McpToolDefinition("neo_get", "Get a record",
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
