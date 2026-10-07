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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link McpFieldProjection} — the pure {@code etendo_list}/{@code etendo_get} field
 * projection behind the IMP-2 {@code fields} / {@code view:"summary"} arguments — and for the
 * emittable set {@code McpQuerySupport.applyProjection} judges a {@code fields:[…]} whitelist by.
 *
 * @covers com.etendoerp.go.mcp.McpFieldProjection
 * @covers com.etendoerp.go.mcp.McpQuerySupport
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpFieldProjection")
class McpFieldProjectionTest {

  /** A customization that injects {@code followUp} on every GET record (ETP-5576). */
  private static final NeoHandler DECLARES_FOLLOW_UP = new NeoHandler() {
    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }

    @Override
    public Set<String> responseEnrichedFields() {
      return Set.of("followUp");
    }
  };

  private static Set<String> req(String... names) {
    return new HashSet<>(java.util.Arrays.asList(names));
  }

  /** A SmartClient envelope with two rows, each carrying a FK label and a compliance flag. */
  private static JSONObject envelope() throws JSONException {
    JSONArray data = new JSONArray();
    data.put(row("INV-1", "Juan Perez"));
    data.put(row("INV-2", "ACME"));
    JSONObject response = new JSONObject();
    response.put("data", data);
    JSONObject root = new JSONObject();
    root.put("response", response);
    return root;
  }

  private static JSONObject row(String docNo, String bpLabel) throws JSONException {
    JSONObject r = new JSONObject();
    r.put("id", "ID-" + docNo);
    r.put("documentNo", docNo);
    r.put("businessPartner", "BP-" + docNo);
    r.put("businessPartner$_identifier", bpLabel);
    r.put("grandTotalAmount", 100);
    r.put("aeatsiiEstado", "PE");
    return r;
  }

  @Nested
  @DisplayName("parseFields / isSummaryView")
  class Inputs {

    @Test
    @DisplayName("parseFields collects names and drops blanks")
    void parseFields() throws JSONException {
      JSONArray arr = new JSONArray();
      arr.put("documentNo");
      arr.put("  grandTotalAmount  ");
      arr.put("");
      Set<String> parsed = McpFieldProjection.parseFields(arr);
      assertEquals(2, parsed.size());
      assertTrue(parsed.contains("documentNo"));
      assertTrue(parsed.contains("grandTotalAmount"));
    }

    @Test
    @DisplayName("parseFields on null returns an empty set")
    void parseFieldsNull() throws JSONException {
      assertTrue(McpFieldProjection.parseFields(null).isEmpty());
    }

    @Test
    @DisplayName("isSummaryView is case-insensitive and false for null/full")
    void isSummaryView() {
      assertTrue(McpFieldProjection.isSummaryView("summary"));
      assertTrue(McpFieldProjection.isSummaryView("SUMMARY"));
      assertFalse(McpFieldProjection.isSummaryView(null));
      assertFalse(McpFieldProjection.isSummaryView("full"));
    }
  }

  @Nested
  @DisplayName("apply")
  class Apply {

    @Test
    @DisplayName("keeps id, the requested fields, and each FK's $_identifier; drops the rest")
    void projectsRows() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.apply(root, req("documentNo", "businessPartner", "grandTotalAmount"));

      JSONArray data = root.getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      JSONObject r0 = data.getJSONObject(0);
      assertTrue(r0.has("id"));
      assertTrue(r0.has("documentNo"));
      assertTrue(r0.has("businessPartner"));
      assertTrue(r0.has("businessPartner$_identifier"));
      assertTrue(r0.has("grandTotalAmount"));
      assertFalse(r0.has("aeatsiiEstado"));
      assertEquals(5, r0.length());
    }

    @Test
    @DisplayName("an empty request set is a no-op (full rows preserved)")
    void emptyRequestIsNoOp() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.apply(root, req());
      assertEquals(6, root.getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).length());
    }

    @Test
    @DisplayName("a payload without response.data is left untouched")
    void noData() throws JSONException {
      JSONObject root = new JSONObject();
      root.put("response", new JSONObject());
      // must not throw
      McpFieldProjection.apply(root, req("documentNo"));
      assertTrue(root.getJSONObject("response").length() == 0);
    }
  }

  @Nested
  @DisplayName("baseNames (IMP-18)")
  class BaseNames {

    @Test
    @DisplayName("a requested $_identifier companion resolves to its base property")
    void companionResolvesToBase() {
      Set<String> base = McpFieldProjection.baseNames(req("businessPartner$_identifier"));
      assertEquals(req("businessPartner"), base);
    }

    @Test
    @DisplayName("asking only for the companion still projects the FK and its label, not just id")
    void companionOnlyRequestStillProjects() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.apply(root, McpFieldProjection.baseNames(
          req("businessPartner$_identifier")));

      JSONObject r0 = root.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(r0.has("businessPartner"));
      assertTrue(r0.has("businessPartner$_identifier"));
      assertEquals("Juan Perez", r0.getString("businessPartner$_identifier"));
      assertFalse(r0.has("documentNo"));
    }

    @Test
    @DisplayName("null in means an empty set out, never a null")
    void nullIsEmpty() {
      assertTrue(McpFieldProjection.baseNames(null).isEmpty());
    }
  }

  @Nested
  @DisplayName("reportUnknownFields (IMP-18)")
  class UnknownFields {

    private static final String KEY_UNKNOWN = "unknownFields";

    @Test
    @DisplayName("a name the entity cannot emit is reported, sorted, alongside data")
    void reportsUnknown() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req("documentNo", "totalGross", "bpartner"),
          Optional.of(req("id", "documentNo", "businessPartner", "grandTotalAmount")));

      JSONArray unknown = root.getJSONObject("response").getJSONArray(KEY_UNKNOWN);
      assertEquals(2, unknown.length());
      assertEquals("bpartner", unknown.getString(0));
      assertEquals("totalGross", unknown.getString(1));
      // the rows themselves are untouched by the reporting step
      assertTrue(root.getJSONObject("response").getJSONArray("data").length() == 2);
    }

    @Test
    @DisplayName("an empty result set still reports the typo — the case that used to be silent")
    void reportsOnEmptyResultSet() throws JSONException {
      JSONObject response = new JSONObject();
      response.put("data", new JSONArray());
      JSONObject root = new JSONObject();
      root.put("response", response);

      McpFieldProjection.reportUnknownFields(root, req("totalGross"),
          Optional.of(req("id", "grandTotalAmount")));

      assertEquals("totalGross",
          root.getJSONObject("response").getJSONArray(KEY_UNKNOWN).getString(0));
    }

    @Test
    @DisplayName("all names known adds no key, so a clean call stays clean")
    void silentWhenAllKnown() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req("documentNo"),
          Optional.of(req("id", "documentNo")));
      assertFalse(root.getJSONObject("response").has(KEY_UNKNOWN));
    }

    @Test
    @DisplayName("an absent emittable set leaves the names unjudged rather than accusing them")
    void emptyEmittableIsNoOp() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req("whatever"), Optional.empty());
      assertFalse(root.getJSONObject("response").has(KEY_UNKNOWN));
    }

    @Test
    @DisplayName("no requested names and no response envelope are both no-ops")
    void degenerateInputs() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req(), Optional.of(req("id")));
      assertFalse(root.getJSONObject("response").has(KEY_UNKNOWN));

      // must not throw when there is no envelope to attach to
      McpFieldProjection.reportUnknownFields(new JSONObject(), req("x"), Optional.of(req("id")));
    }
  }

  /**
   * ETP-5576 (MCP obs. 11): {@code McpQuerySupport.applyProjection} adds the keys the entity's
   * customization injects on every GET record ({@code NeoHandler#responseEnrichedFields}) to the
   * emittable set. Before, {@code fields:["followUp"]} on an invoice was reported in
   * {@code unknownFields} by the very response that carried {@code followUp}.
   */
  @Nested
  @DisplayName("applyProjection — keys a customization injects on read (ETP-5576)")
  class InjectedKeys {

    /**
     * Rows: case, the bound customization ({@code null} = none), whether the spec's emittable set
     * can be determined, the expected {@code unknownFields} ({@code null} = no key at all).
     */
    static Stream<Arguments> cases() {
      return Stream.of(
          Arguments.of("a declared key is emittable, a typo is still reported",
              DECLARES_FOLLOW_UP, true, List.of("bogus")),
          Arguments.of("an entity declaring nothing is judged as before",
              null, true, List.of("bogus", "followUp")),
          Arguments.of("an undeterminable emittable set still judges nothing",
              DECLARES_FOLLOW_UP, false, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void unknownFieldsLeavesOutTheDeclaredInjectedKeys(String scenario, NeoHandler customization,
        boolean emittableKnown, List<String> expectedUnknown) throws Exception {
      Optional<Set<String>> specKeys =
          emittableKnown ? Optional.of(req("id", "documentNo")) : Optional.empty();
      NeoFieldFilter fieldFilter = mock(NeoFieldFilter.class);
      when(fieldFilter.emittableResponseKeys()).thenReturn(specKeys);
      // The DAL fallback for an unknown spec set: no entity for the table, so "cannot validate".
      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn("C_Invoice");
      Tab adTab = mock(Tab.class);
      when(adTab.getTable()).thenReturn(table);
      ModelProvider provider = mock(ModelProvider.class);
      SFEntity sfEntity = mock(SFEntity.class);
      JSONObject root = envelope();
      JSONObject args = new JSONObject();
      args.put(McpFieldProjection.PARAM_FIELDS, new JSONArray().put("followUp").put("bogus"));

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
              mockStatic(NeoExtensionDispatcher.class);
          MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(customization);
        models.when(ModelProvider::getInstance).thenReturn(provider);

        McpQuerySupport.applyProjection(root, args, sfEntity, adTab, fieldFilter);
      }

      JSONObject response = root.getJSONObject("response");
      if (expectedUnknown == null) {
        assertFalse(response.has(McpFieldProjection.KEY_UNKNOWN_FIELDS), scenario);
        return;
      }
      JSONArray unknown = response.getJSONArray(McpFieldProjection.KEY_UNKNOWN_FIELDS);
      List<String> names = new ArrayList<>();
      for (int i = 0; i < unknown.length(); i++) {
        names.add(unknown.getString(i));
      }
      assertEquals(expectedUnknown, names, scenario);
    }
  }
}
