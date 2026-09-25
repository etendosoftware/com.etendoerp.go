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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link McpActionsView} — the pure re-shaper behind
 * {@code neo_schema({view:"actions"})} (IMP-6). No DAL/model access, so these run without a live
 * instance.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
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

    /**
     * IMP-21: the catalog lists every button the window has, but only the invokable ones carry
     * {@code invokeVia}. {@code invokableCount} is what tells an agent the split without walking
     * the array — on sales-invoice it reads 5 of 22.
     */
    @Test
    @DisplayName("invokableCount counts only the actions carrying invokeVia")
    void countsInvokableActions() throws JSONException {
      JSONArray fields = sampleFields();
      JSONObject discarded = buttonField("calculatePromotions", "Calculate Promotions");
      discarded.remove("invokeVia");
      discarded.put("invokable", false);
      discarded.put("notInvokableReason", "discarded: not part of the curated agent surface");
      fields.put(discarded);

      JSONObject response = McpActionsView.buildResponse("sales-invoice", "header", fields);

      assertEquals(3, response.getInt("actionCount"));
      assertEquals(2, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }

    /** A catalog where nothing is callable reports 0, not a missing key. */
    @Test
    @DisplayName("invokableCount is 0 when no action is callable")
    void countsZeroWhenNothingInvokable() throws JSONException {
      JSONArray fields = new JSONArray();
      JSONObject blocked = buttonField("createLinesFrom", "");
      blocked.remove("invokeVia");
      blocked.put("invokable", false);
      fields.put(blocked);

      JSONObject response = McpActionsView.buildResponse("sales-invoice", "header", fields);

      assertEquals(1, response.getInt("actionCount"));
      assertEquals(0, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }
  }

  /**
   * ETP-5447: the handler's declared named actions are appended to the button catalog as
   * {@code source:"handler"} entries, counted, and shadow an AD button of the same name.
   */
  @Nested
  @DisplayName("buildResponse with declared actions (ETP-5447)")
  class DeclaredActions {

    private static final String SPEC = "financial-account";
    private static final String ENTITY = "account";
    private static final String CREATE = "createStatement";
    private static final String LIST = "listStatements";
    private static final String KEY_NAME = "name";
    private static final String KEY_ACTION = "action";
    private static final String KEY_ACTION_COUNT = "actionCount";
    private static final String KEY_PARAMETERS = "parameters";
    private static final String KEY_LINES = "lines";
    private static final String KEY_TYPE = "type";
    private static final String KEY_INVOKE_VIA = "invokeVia";
    private static final String NEO_ACTION = "neo_action";

    private NeoActionContract createContract() {
      return NeoActionContract.builder(CREATE)
          .description("Creates a statement")
          .param(NeoReportParam.required(KEY_NAME, NeoReportParam.TYPE_STRING, "Name"))
          .param(NeoReportParam.required(KEY_LINES, NeoReportParam.TYPE_ARRAY, "Lines"))
          .param(NeoReportParam.optional("notes", NeoReportParam.TYPE_STRING, "Notes"))
          .build();
    }

    private NeoActionContract listContract() {
      return NeoActionContract.builder(LIST)
          .method(NeoActionContract.METHOD_GET)
          .readOnly(true)
          .build();
    }

    private JSONObject entryNamed(JSONArray actions, String name) throws JSONException {
      for (int i = 0; i < actions.length(); i++) {
        JSONObject entry = actions.getJSONObject(i);
        if (name.equals(entry.optString(KEY_NAME, null))) {
          return entry;
        }
      }
      return null;
    }

    @Test
    void testDeclaredEntriesComeAfterButtonsInDeclarationOrder() throws JSONException {
      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, sampleFields(),
          List.of(createContract(), listContract()));

      JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);
      assertEquals(4, actions.length());
      assertEquals("completeAction", actions.getJSONObject(0).getString(KEY_NAME));
      assertEquals("cancelAction", actions.getJSONObject(1).getString(KEY_NAME));
      assertEquals(CREATE, actions.getJSONObject(2).getString(KEY_NAME));
      assertEquals(LIST, actions.getJSONObject(3).getString(KEY_NAME));
    }

    @Test
    void testDeclaredEntryCarriesSourceMethodReadOnlyDescriptionAndInvokeVia()
        throws JSONException {
      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, new JSONArray(),
          List.of(createContract(), listContract()));
      JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);

      JSONObject create = actions.getJSONObject(0);
      assertEquals(CREATE, create.getString(KEY_NAME));
      assertEquals(CREATE, create.getString(KEY_ACTION));
      assertEquals(McpActionsView.SOURCE_HANDLER, create.getString(McpActionsView.KEY_SOURCE));
      assertEquals("POST", create.getString("method"));
      assertFalse(create.getBoolean("readOnly"));
      assertEquals("Creates a statement", create.getString("description"));
      assertEquals(NEO_ACTION, create.getString(KEY_INVOKE_VIA));
      assertFalse(create.has(McpActionsView.KEY_SHADOWS));

      JSONObject list = actions.getJSONObject(1);
      assertEquals("GET", list.getString("method"));
      assertTrue(list.getBoolean("readOnly"));
      assertFalse(list.has("description"), "a null description is omitted, not rendered");
    }

    @Test
    void testDeclaredEntryParametersIsAJsonSchemaObject() throws JSONException {
      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, new JSONArray(),
          List.of(createContract(), listContract()));
      JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);

      JSONObject parameters = actions.getJSONObject(0).getJSONObject(KEY_PARAMETERS);
      assertEquals("object", parameters.getString(KEY_TYPE));
      JSONObject properties = parameters.getJSONObject("properties");
      assertEquals("string", properties.getJSONObject(KEY_NAME).getString(KEY_TYPE));
      assertEquals("array", properties.getJSONObject(KEY_LINES).getString(KEY_TYPE));
      JSONArray required = parameters.getJSONArray("required");
      assertEquals(2, required.length());
      assertEquals(KEY_NAME, required.getString(0));
      assertEquals(KEY_LINES, required.getString(1));

      JSONObject noParams = actions.getJSONObject(1).getJSONObject(KEY_PARAMETERS);
      assertEquals(0, noParams.getJSONObject("properties").length());
      assertFalse(noParams.has("required"));
    }

    @Test
    void testDeclaredEntriesAreCountedAsActionsAndInvokable() throws JSONException {
      JSONArray fields = sampleFields();
      JSONObject blocked = buttonField("calculatePromotions", "Calculate Promotions");
      blocked.remove(KEY_INVOKE_VIA);
      fields.put(blocked);

      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, fields,
          List.of(createContract(), listContract()));

      assertEquals(5, response.getInt(KEY_ACTION_COUNT));
      assertEquals(4, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }

    @Test
    void testButtonWhoseActionCollidesIsDroppedAndDeclaredShadowsIt() throws JSONException {
      JSONArray fields = new JSONArray();
      JSONObject button = buttonField("processButton", "Process");
      button.put(KEY_ACTION, CREATE);
      fields.put(button);
      fields.put(buttonField("otherButton", "Other"));

      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, fields,
          List.of(createContract()));
      JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);

      assertEquals(2, actions.length());
      assertNull(entryNamed(actions, "processButton"));
      JSONObject declared = entryNamed(actions, CREATE);
      assertEquals(McpActionsView.SOURCE_HANDLER, declared.getString(McpActionsView.KEY_SOURCE));
      assertEquals(McpActionsView.TYPE_BUTTON, declared.getString(McpActionsView.KEY_SHADOWS));
      assertEquals(2, response.getInt(KEY_ACTION_COUNT));
    }

    @Test
    void testButtonWhoseNameCollidesIsDroppedAndDeclaredShadowsIt() throws JSONException {
      JSONArray fields = new JSONArray();
      JSONObject button = field(LIST, McpActionsView.TYPE_BUTTON);
      button.put(KEY_INVOKE_VIA, NEO_ACTION);
      fields.put(button);

      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, fields,
          List.of(listContract()));
      JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);

      assertEquals(1, actions.length());
      JSONObject only = actions.getJSONObject(0);
      assertEquals(McpActionsView.SOURCE_HANDLER, only.getString(McpActionsView.KEY_SOURCE));
      assertEquals(McpActionsView.TYPE_BUTTON, only.getString(McpActionsView.KEY_SHADOWS));
    }

    @Test
    void testNullDeclaredListBehavesAsNone() throws JSONException {
      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, sampleFields(), null);

      assertEquals(2, response.getInt(KEY_ACTION_COUNT));
      assertEquals(2, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }

    @Test
    void testThreeArgOverloadEqualsFourArgWithEmptyDeclarations() throws JSONException {
      JSONObject threeArg = McpActionsView.buildResponse(SPEC, ENTITY, sampleFields());
      JSONObject fourArg = McpActionsView.buildResponse(SPEC, ENTITY, sampleFields(), List.of());

      assertEquals(fourArg.toString(), threeArg.toString());
      JSONArray actions = threeArg.getJSONArray(McpActionsView.KEY_ACTIONS);
      for (int i = 0; i < actions.length(); i++) {
        assertFalse(actions.getJSONObject(i).has(McpActionsView.KEY_SOURCE),
            "button entries never carry a source");
      }
    }

    @Test
    void testDeclaredActionsWithNoFieldsStillBuildTheCatalog() throws JSONException {
      JSONObject response = McpActionsView.buildResponse(SPEC, ENTITY, null,
          List.of(listContract()));

      assertEquals(SPEC, response.getString("spec"));
      assertEquals(ENTITY, response.getString("entity"));
      assertEquals(1, response.getInt(KEY_ACTION_COUNT));
      assertEquals(1, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }
  }
}
