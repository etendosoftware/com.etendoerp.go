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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpSchemaCreateView} — the pure re-shaper behind
 * {@code neo_schema({view:"create"})} and {@code neo_schema({fields:[…]})} (IMP-12). No DAL/model
 * access, so these run without a live instance.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpSchemaCreateView")
class McpSchemaCreateViewTest {

  private static JSONObject field(String name, String visibility, boolean readOnly,
      boolean userRequired) throws JSONException {
    JSONObject fieldObj = new JSONObject();
    fieldObj.put("name", name);
    fieldObj.put("type", "string");
    fieldObj.put("visibility", visibility);
    fieldObj.put("readOnly", readOnly);
    fieldObj.put(McpSchemaFieldBuilder.KEY_USER_REQUIRED, userRequired);
    return fieldObj;
  }

  /**
   * A miniature of {@code sales-invoice/header}: one field of every class the view must decide on.
   * Only {@code businessPartner} (required) and {@code description} (optional) may be sent.
   */
  private static JSONArray sampleFields() throws JSONException {
    JSONArray fields = new JSONArray();
    fields.put(field("businessPartner", "editable", false, true));
    fields.put(field("description", "editable", false, false));
    // readOnly + businessCritical — the combination IMP-12's original rule wrongly admitted.
    JSONObject grandTotal = field("grandTotal", "readOnly", true, false);
    grandTotal.put("businessCritical", true);
    fields.put(grandTotal);
    fields.put(field("createdBy", "system", false, false));
    fields.put(field("emAeatsiiDescripcionSii", "discarded", false, false));
    JSONObject button = field("completeAction", "editable", false, false);
    button.put("type", "button");
    fields.put(button);
    return fields;
  }

  @Nested
  @DisplayName("isCreateView")
  class Predicate {

    @Test
    @DisplayName("only \"create\" (case-insensitive) requests the create-shaped projection")
    void isCreateView() {
      assertTrue(McpSchemaCreateView.isCreateView("create"));
      assertTrue(McpSchemaCreateView.isCreateView("CREATE"));
      assertFalse(McpSchemaCreateView.isCreateView("actions"));
      assertFalse(McpSchemaCreateView.isCreateView("full"));
      assertFalse(McpSchemaCreateView.isCreateView(null));
    }
  }

  @Nested
  @DisplayName("buildResponse")
  class BuildResponse {

    @Test
    @DisplayName("splits the suppliable fields into required/optional, dropping everything else")
    void splitsRequiredAndOptional() throws JSONException {
      JSONObject response =
          McpSchemaCreateView.buildResponse("sales-invoice", "header", sampleFields());

      assertEquals("sales-invoice", response.getString("spec"));
      assertEquals("header", response.getString("entity"));
      assertEquals(1, response.getInt("requiredCount"));
      assertEquals(1, response.getInt("optionalCount"));
      assertEquals("businessPartner", response.getJSONArray("required").getJSONObject(0)
          .getString("name"));
      assertEquals("description", response.getJSONArray("optional").getJSONObject(0)
          .getString("name"));
    }

    @Test
    @DisplayName("drops the full field dump, table, methods and namedFilters")
    void dropsFullDump() throws JSONException {
      JSONObject response =
          McpSchemaCreateView.buildResponse("sales-invoice", "header", sampleFields());

      assertFalse(response.has("fields"));
      assertFalse(response.has("table"));
      assertFalse(response.has("methods"));
      assertFalse(response.has("namedFilters"));
      assertTrue(response.has("hint"));
    }

    // A readOnly businessCritical field (grandTotal) must NOT appear: businessCritical answers
    // "confirm this with the user before writing", not "you may send it". Intersecting rather than
    // unioning is the correction IMP-12 §4 records against its own original specification.
    @Test
    @DisplayName("a readOnly businessCritical field is excluded from both groups")
    void excludesReadOnlyBusinessCritical() throws JSONException {
      JSONObject response =
          McpSchemaCreateView.buildResponse("sales-invoice", "header", sampleFields());

      assertFalse(response.getJSONArray("required").toString().contains("grandTotal"));
      assertFalse(response.getJSONArray("optional").toString().contains("grandTotal"));
    }

    @Test
    @DisplayName("buttons are excluded — they belong to view:\"actions\"")
    void excludesButtons() throws JSONException {
      JSONObject response =
          McpSchemaCreateView.buildResponse("sales-invoice", "header", sampleFields());

      assertFalse(response.getJSONArray("optional").toString().contains("completeAction"));
    }

    @Test
    @DisplayName("a null fields array yields empty (never null) groups")
    void nullFields() throws JSONException {
      JSONObject response = McpSchemaCreateView.buildResponse("tax", "tax", null);

      assertEquals(0, response.getInt("requiredCount"));
      assertEquals(0, response.getInt("optionalCount"));
      assertEquals(0, response.getJSONArray("required").length());
    }
  }

  @Nested
  @DisplayName("applyFieldWhitelist")
  class ApplyFieldWhitelist {

    @Test
    @DisplayName("keeps only the requested descriptors, in their original order")
    void keepsRequested() throws JSONException {
      JSONArray filtered = McpSchemaCreateView.applyFieldWhitelist(sampleFields(),
          Set.of("createdBy", "businessPartner"));

      assertEquals(2, filtered.length());
      assertEquals("businessPartner", filtered.getJSONObject(0).getString("name"));
      assertEquals("createdBy", filtered.getJSONObject(1).getString("name"));
    }

    // Backward compatibility: omitting `fields` must return the response byte-for-byte as before,
    // so the same array instance comes back rather than a copy.
    @Test
    @DisplayName("a null or empty request set returns the array unchanged")
    void nullOrEmptyRequest() throws JSONException {
      JSONArray fields = sampleFields();

      assertSame(fields, McpSchemaCreateView.applyFieldWhitelist(fields, null));
      assertSame(fields, McpSchemaCreateView.applyFieldWhitelist(fields, Set.of()));
    }

    @Test
    @DisplayName("no descriptor matches — the result is empty, not the full array")
    void noMatches() throws JSONException {
      assertEquals(0,
          McpSchemaCreateView.applyFieldWhitelist(sampleFields(), Set.of("nope")).length());
    }

    @Test
    @DisplayName("a null fields array is returned as-is")
    void nullFields() throws JSONException {
      assertNull(McpSchemaCreateView.applyFieldWhitelist(null, Set.of("a")));
    }
  }

  @Nested
  @DisplayName("unknownFields")
  class UnknownFields {

    // IMP-18 tracks exactly this defect on neo_list's projection: a typo makes the field vanish in
    // silence and the agent concludes the field does not exist. Echoed back here at birth.
    @Test
    @DisplayName("echoes back names that matched no descriptor")
    void echoesUnmatched() throws JSONException {
      JSONArray unknown = McpSchemaCreateView.unknownFields(sampleFields(),
          Set.of("businessPartner", "buisnessPartner"));

      assertEquals(1, unknown.length());
      assertEquals("buisnessPartner", unknown.getString(0));
    }

    @Test
    @DisplayName("every name matched — the array is empty, so the caller omits the key")
    void allMatched() throws JSONException {
      assertEquals(0,
          McpSchemaCreateView.unknownFields(sampleFields(), Set.of("description")).length());
    }

    @Test
    @DisplayName("a null/empty request set, or a null fields array, never throws")
    void nullInputs() throws JSONException {
      assertEquals(0, McpSchemaCreateView.unknownFields(sampleFields(), null).length());
      assertEquals(0, McpSchemaCreateView.unknownFields(sampleFields(), Set.of()).length());
      assertEquals(1, McpSchemaCreateView.unknownFields(null, Set.of("a")).length());
    }
  }
}
