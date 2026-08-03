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
}
