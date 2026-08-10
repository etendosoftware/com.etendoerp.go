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

import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpFieldProjection} — the pure {@code neo_list}/{@code neo_get} field
 * projection behind the IMP-2 {@code fields} / {@code view:"summary"} arguments.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpFieldProjection")
class McpFieldProjectionTest {

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
          req("id", "documentNo", "businessPartner", "grandTotalAmount"));

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
          req("id", "grandTotalAmount"));

      assertEquals("totalGross",
          root.getJSONObject("response").getJSONArray(KEY_UNKNOWN).getString(0));
    }

    @Test
    @DisplayName("all names known adds no key, so a clean call stays clean")
    void silentWhenAllKnown() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req("documentNo"),
          req("id", "documentNo"));
      assertFalse(root.getJSONObject("response").has(KEY_UNKNOWN));
    }

    @Test
    @DisplayName("an unknown emittable set leaves the names unjudged rather than accusing them")
    void nullEmittableIsNoOp() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req("whatever"), null);
      assertFalse(root.getJSONObject("response").has(KEY_UNKNOWN));
    }

    @Test
    @DisplayName("no requested names and no response envelope are both no-ops")
    void degenerateInputs() throws JSONException {
      JSONObject root = envelope();
      McpFieldProjection.reportUnknownFields(root, req(), req("id"));
      assertFalse(root.getJSONObject("response").has(KEY_UNKNOWN));

      // must not throw when there is no envelope to attach to
      McpFieldProjection.reportUnknownFields(new JSONObject(), req("x"), req("id"));
    }
  }
}
