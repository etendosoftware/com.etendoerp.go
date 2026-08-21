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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpNamedFilters} — the pure, DAL-free parser for the per-entity
 * {@code NAMED_FILTERS} JSON that {@code neo_list} exposes as {@code {status:"<name>"}} filters
 * (ETP-4601).
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpNamedFilters")
class McpNamedFiltersTest {

  private static final String JSON =
      "[{\"name\":\"completed\",\"label\":\"Paid\",\"description\":\"Paid in full\","
          + "\"where\":\"e.paymentComplete = true\"},"
          + "{\"name\":\"pending\",\"where\":\"e.paymentComplete = false\"}]";

  @Nested
  @DisplayName("parseWhereByName")
  class ParseWhereByName {

    @Test
    @DisplayName("maps each name to its where fragment, preserving order")
    void mapsNames() {
      Map<String, String> byName = McpNamedFilters.parseWhereByName(JSON);
      assertEquals(2, byName.size());
      assertEquals("e.paymentComplete = true", byName.get("completed"));
      assertEquals("e.paymentComplete = false", byName.get("pending"));
      assertEquals(List.of("completed", "pending"), List.copyOf(byName.keySet()));
    }

    @Test
    @DisplayName("skips entries missing a name or where, first name wins on duplicates")
    void skipsInvalidAndDedupes() {
      String json = "[{\"name\":\"a\",\"where\":\"e.x = 1\"},"
          + "{\"name\":\"\",\"where\":\"e.y = 2\"},"
          + "{\"name\":\"b\"},"
          + "{\"where\":\"e.z = 3\"},"
          + "{\"name\":\"a\",\"where\":\"e.other = 9\"}]";
      Map<String, String> byName = McpNamedFilters.parseWhereByName(json);
      assertEquals(1, byName.size());
      assertEquals("e.x = 1", byName.get("a"));
    }

    @Test
    @DisplayName("blank, null or malformed JSON yields an empty map")
    void emptyForBlankOrMalformed() {
      assertTrue(McpNamedFilters.parseWhereByName(null).isEmpty());
      assertTrue(McpNamedFilters.parseWhereByName("").isEmpty());
      assertTrue(McpNamedFilters.parseWhereByName("   ").isEmpty());
      assertTrue(McpNamedFilters.parseWhereByName("not json").isEmpty());
      assertTrue(McpNamedFilters.parseWhereByName("{\"name\":\"x\"}").isEmpty());
    }
  }

  @Nested
  @DisplayName("describe")
  class Describe {

    @Test
    @DisplayName("exposes name/label/description but never the where fragment")
    void exposesDocsOnly() throws Exception {
      JSONArray docs = McpNamedFilters.describe(JSON);
      assertEquals(2, docs.length());
      assertEquals("completed", docs.getJSONObject(0).getString("name"));
      assertEquals("Paid", docs.getJSONObject(0).getString("label"));
      assertEquals("Paid in full", docs.getJSONObject(0).getString("description"));
      assertTrue(!docs.getJSONObject(0).has("where"));
      // second entry has no label/description
      assertEquals("pending", docs.getJSONObject(1).getString("name"));
      assertTrue(!docs.getJSONObject(1).has("label"));
      assertTrue(!docs.getJSONObject(1).has("description"));
    }

    @Test
    @DisplayName("blank or malformed JSON yields an empty array")
    void emptyForBlank() throws Exception {
      assertEquals(0, McpNamedFilters.describe(null).length());
      assertEquals(0, McpNamedFilters.describe("").length());
      assertEquals(0, McpNamedFilters.describe("garbage").length());
    }
  }

  /**
   * The failure an unknown filter name produces, which lives in {@link McpQuerySupport} but is only
   * meaningful against this parser's output (ETP-4793 / IMP-17, evidence C14).
   */
  @Nested
  @DisplayName("unknown filter name (ETP-4793 / IMP-17)")
  class UnknownFilterName {

    /**
     * IMP-3 made this failure self-correcting by naming the valid states; IMP-17 moves them out of the
     * prose into {@code available} and gives the response a status. It must stay a 422: the router's
     * catch-all classifies an unrecognised exception as {@code server_error}, which would tell an
     * agent to stop retrying a call one corrected word would fix.
     */
    @Test
    @DisplayName("is a 422 whose 'available' carries the names the parser found")
    void unknownNameIsA422WithTheAvailableNames() throws Exception {
      Map<String, String> byName = McpNamedFilters.parseWhereByName(JSON);

      McpRoutingException ex = McpRoutingException.unknownNamedFilter(
          "nonexistent-status-probe", "sales-invoice", List.copyOf(byName.keySet()));
      org.codehaus.jettison.json.JSONObject envelope = ex.toEnvelope();

      assertEquals(422, envelope.getInt("status"));
      assertEquals("validation_error", envelope.getString("error"));
      assertEquals("status", envelope.getString("field"));
      assertTrue(envelope.getString("detail").contains("nonexistent-status-probe"));
      JSONArray available = envelope.getJSONArray("available");
      assertEquals(2, available.length());
      assertEquals("completed", available.getString(0));
      assertEquals("pending", available.getString(1));
    }
  }
}
