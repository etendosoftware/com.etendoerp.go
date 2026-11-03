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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoFieldFilter;

/**
 * ETP-5848 — {@code etendo_list} without {@code fields} returns the entity's default columns, not
 * every field: the {@code list} section of {@code MCP_CONFIG} when declared, else the window's list
 * columns. Rows of 57 to 109 fields were 30% of everything agents read in the OKR O2 sweep.
 *
 * <p>The window-list fallback reads {@code ETGO_SF_FIELD.GRID_SEQNO} through the DAL and is
 * verified against a live environment; these tests pin the section and the precedence.</p>
 *
 * @covers com.etendoerp.go.mcp.McpListSection
 * @covers com.etendoerp.go.mcp.McpQuerySupport
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("ETP-5848 — etendo_list default columns")
class McpListSectionTest {

  private static int seq;

  @BeforeEach
  void setUp() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
  }

  @AfterEach
  void tearDown() {
    McpConfigCache.invalidateAll();
  }

  private static SFEntity entity(String mcpConfig) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-" + (++seq));
    when(spec.getName()).thenReturn("sales-invoice");
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(0L);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("ent-" + (++seq));
    when(entity.getName()).thenReturn("header");
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(mcpConfig);
    return entity;
  }

  private static JSONObject listResponse() throws Exception {
    JSONArray data = new JSONArray();
    for (String docNo : List.of("INV-1", "INV-2")) {
      data.put(new JSONObject()
          .put("id", "ID-" + docNo)
          .put("documentNo", docNo)
          .put("businessPartner", "BP-" + docNo)
          .put("businessPartner$_identifier", "ACME")
          .put("grandTotalAmount", 100)
          .put("description", "long text"));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static List<String> names(JSONArray array) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      out.add(array.getString(i));
    }
    return out;
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("a non-empty array of names is valid")
    void valid() throws Exception {
      assertTrue(McpListSection.validate(
          new JSONObject("{\"fields\":[\"documentNo\",\"businessPartner\"]}")).isEmpty());
    }

    @Test
    @DisplayName("fields is required and cannot be empty")
    void fieldsRequired() throws Exception {
      assertFalse(McpListSection.validate(new JSONObject("{}")).isEmpty());
      assertFalse(McpListSection.validate(new JSONObject("{\"fields\":[]}")).isEmpty());
      assertFalse(McpListSection.validate(new JSONObject("{\"fields\":\"documentNo\"}")).isEmpty());
    }

    @Test
    @DisplayName("a blank or non-string name is refused, naming its position")
    void namesAreStrings() throws Exception {
      List<String> problems = McpListSection.validate(
          new JSONObject("{\"fields\":[\"documentNo\",\" \",3]}"));
      assertEquals(2, problems.size(), problems.toString());
      assertTrue(problems.get(0).contains("fields[1]"), problems.toString());
    }
  }

  @Nested
  @DisplayName("configuredFields")
  class Configured {

    @Test
    @DisplayName("the declared names, in their order")
    void declared() {
      SFEntity e = entity("{\"list\":{\"fields\":[\"grandTotalAmount\",\"documentNo\"]}}");
      assertEquals(List.of("grandTotalAmount", "documentNo"),
          new ArrayList<>(McpListSection.configuredFields(e)));
    }

    @Test
    @DisplayName("no section, or an unusable configuration, is read as not configured")
    void absentOrUnusable() {
      assertNull(McpListSection.configuredFields(entity(null)));
      assertNull(McpListSection.configuredFields(entity("{\"list\":{\"fields\":[]}}")));
    }
  }

  @Nested
  @DisplayName("applyProjection on a list")
  class Precedence {

    private final SFEntity configured =
        entity("{\"list\":{\"fields\":[\"documentNo\",\"grandTotalAmount\"]}}");

    private JSONObject project(JSONObject args, boolean list) throws Exception {
      JSONObject root = listResponse();
      // The spec knows every key the rows carry, so an explicit list is judged without the DAL.
      NeoFieldFilter fieldFilter = mock(NeoFieldFilter.class);
      when(fieldFilter.emittableResponseKeys()).thenReturn(Optional.of(Set.of("id", "documentNo",
          "businessPartner", "grandTotalAmount", "description")));
      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
          mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        McpQuerySupport.applyProjection(root, args, configured, mock(Tab.class), fieldFilter,
            list);
      }
      return root.getJSONObject("response");
    }

    @Test
    @DisplayName("without fields, rows keep the default columns and the rest is named")
    void defaultColumns() throws Exception {
      JSONObject response = project(new JSONObject(), true);

      JSONObject row = response.getJSONArray("data").getJSONObject(0);
      assertEquals(List.of("documentNo", "grandTotalAmount", "id"),
          names(row.names()).stream().sorted().toList());
      assertEquals(List.of("businessPartner", "description"),
          names(response.getJSONArray(McpFieldProjection.KEY_OTHER_FIELDS)).stream().sorted()
              .toList());
      assertTrue(response.has(McpFieldProjection.KEY_FIELDS_NOTE));
    }

    @Test
    @DisplayName("view:full returns every field and names nothing")
    void fullView() throws Exception {
      JSONObject response = project(new JSONObject().put("view", "full"), true);

      assertEquals(6, response.getJSONArray("data").getJSONObject(0).length());
      assertFalse(response.has(McpFieldProjection.KEY_OTHER_FIELDS));
    }

    @Test
    @DisplayName("an explicit fields list wins over the default")
    void explicitFieldsWin() throws Exception {
      JSONObject response = project(
          new JSONObject().put("fields", new JSONArray().put("businessPartner")), true);

      JSONObject row = response.getJSONArray("data").getJSONObject(0);
      assertTrue(row.has("businessPartner$_identifier"));
      assertFalse(row.has("documentNo"));
      assertFalse(response.has(McpFieldProjection.KEY_OTHER_FIELDS));
    }

    @Test
    @DisplayName("a get keeps every field: the default applies to lists only")
    void getIsUntouched() throws Exception {
      JSONObject response = project(new JSONObject(), false);

      assertEquals(6, response.getJSONArray("data").getJSONObject(0).length());
    }
  }
}
