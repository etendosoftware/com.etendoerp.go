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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.WidgetAccessPolicy;

/**
 * ETP-5850: a widget the role may not see answers the dashboard an empty list flagged
 * {@code restricted}. Through the MCP that empty list read as "no data in the period", so
 * {@code etendo_widget} answers the role's 403 instead; an empty list of a granted widget is still
 * "no data" and passes through.
 *
 * @covers com.etendoerp.go.mcp.McpWidgetHandler
 */
class McpWidgetRestrictedTest {

  private static JSONObject widgetBody(boolean restricted) throws Exception {
    JSONObject response = new JSONObject().put("data", new JSONArray()).put("count", 0);
    if (restricted) {
      response.put(WidgetAccessPolicy.KEY_RESTRICTED, true);
    }
    return new JSONObject().put("response", response);
  }

  @Test
  @DisplayName("a denied widget is refused with 403, naming the widget, not answered empty")
  void deniedWidgetIsForbidden() throws Exception {
    JSONObject refusal = McpWidgetHandler.refusalIfRestricted(widgetBody(true), "revenue-trend");

    assertEquals(McpConstants.STATUS_FORBIDDEN, refusal.getInt(McpConstants.KEY_STATUS));
    assertEquals(McpConstants.ERROR_FORBIDDEN, refusal.getString(McpConstants.KEY_ERROR));
    assertTrue(refusal.getString(McpConstants.KEY_DETAIL).contains("revenue-trend"));
    assertEquals(McpConstants.TOOL_NEO_WIDGET, refusal.getString("tool"));
  }

  @Test
  @DisplayName("an empty widget the role may see is still 'no data', not a refusal")
  void grantedEmptyWidgetPassesThrough() throws Exception {
    assertNull(McpWidgetHandler.refusalIfRestricted(widgetBody(false), "revenue-trend"));
  }
}
