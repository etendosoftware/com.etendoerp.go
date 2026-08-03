/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpActionsView} — the pure re-shaper behind
 * {@code neo_schema({view:"actions"})} (IMP-6). No DAL/model access, so these run without a live
 * instance.
 */
@DisplayName("McpActionsView")
class McpActionsViewTest {

  private static JSONObject field(String name, String type) throws JSONException {
    JSONObject field = new JSONObject();
    field.put("name", name);
    field.put("type", type);
    return field;
  }

  private static JSONObject buttonField(String name, String processName) throws JSONException {
    JSONObject field = field(name, "button");
    field.put("invokeVia", "neo_action");
    field.put("action", name);
    field.put("processType", "OBUIAPP");
    field.put("processName", processName);
    field.put("processId", "ABC123");
    return field;
  }

  /** A mixed schema fields array: two plain columns and two buttons. */
  private static JSONArray sampleFields() throws JSONException {
    JSONArray fields = new JSONArray();
    fields.put(field("documentNo", "string"));
    fields.put(buttonField("completeAction", "Complete"));
    fields.put(field("grandTotal", "number"));
    fields.put(buttonField("cancelAction", "Cancel Document"));
    return fields;
  }

  @Nested
  @DisplayName("isActionsView")
  class Predicate {

    @Test
    @DisplayName("only \"actions\" (case-insensitive) requests the actions-only projection")
    void isActionsView() {
      assertTrue(McpActionsView.isActionsView("actions"));
      assertTrue(McpActionsView.isActionsView("ACTIONS"));
      assertFalse(McpActionsView.isActionsView("full"));
      assertFalse(McpActionsView.isActionsView(null));
    }
  }

  @Nested
  @DisplayName("apply")
  class Apply {

    @Test
    @DisplayName("filters the field array down to type:\"button\" entries, preserving order")
    void filtersButtonFields() throws JSONException {
      JSONArray actions = McpActionsView.apply(sampleFields());

      assertEquals(2, actions.length());
      assertEquals("completeAction", actions.getJSONObject(0).getString("name"));
      assertEquals("cancelAction", actions.getJSONObject(1).getString("name"));
    }

    @Test
    @DisplayName("a fields array with no buttons yields an empty actions array")
    void noButtons() throws JSONException {
      JSONArray fields = new JSONArray();
      fields.put(field("documentNo", "string"));
      fields.put(field("grandTotal", "number"));

      assertEquals(0, McpActionsView.apply(fields).length());
    }

    @Test
    @DisplayName("a null fields array yields an empty (never null) actions array")
    void nullFields() throws JSONException {
      assertEquals(0, McpActionsView.apply(null).length());
    }
  }

  @Nested
  @DisplayName("buildResponse")
  class BuildResponse {

    @Test
    @DisplayName("shapes {spec, entity, actions, actionCount}, dropping the full field dump")
    void shapesResponse() throws JSONException {
      JSONObject response = McpActionsView.buildResponse("sales-order", "header", sampleFields());

      assertEquals("sales-order", response.getString("spec"));
      assertEquals("header", response.getString("entity"));
      assertEquals(2, response.getInt("actionCount"));
      assertEquals(2, response.getJSONArray(McpActionsView.KEY_ACTIONS).length());
      assertFalse(response.has("fields"));
    }
  }
}
