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
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpNamedFilters} — the pure, DAL-free parser for the per-entity
 * {@code NAMED_FILTERS} JSON that {@code etendo_list} exposes as {@code {status:"<name>"}} filters
 * (ETP-4601).
 *
 * @covers com.etendoerp.go.mcp.McpNamedFilters
 * @covers com.etendoerp.go.mcp.McpRoutingException
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

  @Nested
  @DisplayName("publishInto")
  class PublishInto {

    @Test
    @DisplayName("adds namedFilters with what describe exposes")
    void addsTheDescriptors() throws Exception {
      org.codehaus.jettison.json.JSONObject schema = new org.codehaus.jettison.json.JSONObject();
      McpNamedFilters.publishInto(schema, JSON);
      assertEquals(McpNamedFilters.describe(JSON).toString(),
          schema.getJSONArray("namedFilters").toString());
    }

    @Test
    @DisplayName("adds no key when the entity declares no filter")
    void addsNothingWithoutFilters() throws Exception {
      org.codehaus.jettison.json.JSONObject schema = new org.codehaus.jettison.json.JSONObject();
      McpNamedFilters.publishInto(schema, null);
      McpNamedFilters.publishInto(schema, "garbage");
      assertEquals(0, schema.length());
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

  // ── IMP-50 discoverability ──────────────────────────────────────────────

  /** Three filters, the third with a two-sentence description. */
  private static final String JSON3 = JSON.substring(0, JSON.length() - 1)
      + ",{\"name\":\"outstanding\",\"description\":\"Not fully paid. Includes partial.\","
      + "\"where\":\"e.outstandingAmount > 0\"}]";

  @Nested
  @DisplayName("catalogLine (IMP-50)")
  class CatalogLine {

    @Test
    @DisplayName("lists every configured filter with its first-sentence description")
    void listsConfiguredFilters() {
      assertEquals("inv/header: completed (Paid in full), pending, outstanding (Not fully paid.)",
          McpNamedFilters.catalogLine("inv", "header", JSON3));
    }

    @Test
    @DisplayName("yields nothing for an entity without named filters")
    void nothingWithoutFilters() {
      assertNull(McpNamedFilters.catalogLine("inv", "lines", null));
      assertNull(McpNamedFilters.catalogLine("inv", "lines", "garbage"));
      assertNull(McpNamedFilters.catalogLine("inv", "lines", "[]"));
    }
  }

  @Nested
  @DisplayName("catalogSummary (IMP-50)")
  class CatalogSummary {

    @Test
    @DisplayName("joins one line per entity when under the cap")
    void joinsLines() {
      assertEquals("a/x: one\nb/y: two",
          McpNamedFilters.catalogSummary(List.of("a/x: one", "b/y: two"), 1000));
    }

    @Test
    @DisplayName("stops at the cap and points at etendo_schema for the rest")
    void capsAndPointsAtSchema() {
      String summary = McpNamedFilters.catalogSummary(
          List.of("a/x: 1234567890", "b/y: 1234567890", "c/z: 1234567890"), 30);
      assertTrue(summary.startsWith("a/x: 1234567890\n"), summary);
      assertFalse(summary.contains("c/z"), summary);
      assertTrue(summary.endsWith("2 more: call etendo_schema view:\"full\""), summary);
    }

    @Test
    @DisplayName("is null when no entity declares a filter")
    void nullWhenEmpty() {
      assertNull(McpNamedFilters.catalogSummary(List.of(), 1000));
    }
  }

  @Nested
  @DisplayName("appliedBlock (IMP-50)")
  class AppliedBlock {

    @Test
    @DisplayName("names the applied filter and lists the others with their descriptions")
    void namesAppliedAndOthers() throws Exception {
      org.codehaus.jettison.json.JSONObject block = McpNamedFilters.appliedBlock(JSON3, "pending");
      assertEquals("pending", block.getString("applied"));
      assertFalse(block.has("description"), "pending has no description");
      JSONArray others = block.getJSONArray("available");
      assertEquals(2, others.length());
      assertEquals("completed", others.getJSONObject(0).getString("name"));
      assertEquals("Paid in full", others.getJSONObject(0).getString("description"));
      assertEquals("outstanding", others.getJSONObject(1).getString("name"));
      assertEquals("Not fully paid.", others.getJSONObject(1).getString("description"));
    }

    @Test
    @DisplayName("is null when no named filter was applied")
    void nullWithoutANamedFilter() throws Exception {
      assertNull(McpNamedFilters.appliedBlock(JSON3, null));
      assertNull(McpNamedFilters.appliedBlock(null, "pending"));
      // status used as a plain column on an entity without named filters
      assertNull(McpNamedFilters.appliedBlock("[]", "DR"));
    }

    @Test
    @DisplayName("attach adds namedFilters only when filters.status names a configured filter")
    void attachOnlyForANamedStatus() throws Exception {
      org.codehaus.jettison.json.JSONObject filters = new org.codehaus.jettison.json.JSONObject();
      filters.put("status", "outstanding");
      org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject();
      McpNamedFilters.attachApplied(body, JSON3, filters);
      assertEquals("outstanding", body.getJSONObject("namedFilters").getString("applied"));

      org.codehaus.jettison.json.JSONObject plain = new org.codehaus.jettison.json.JSONObject();
      McpNamedFilters.attachApplied(plain, JSON3, new org.codehaus.jettison.json.JSONObject());
      McpNamedFilters.attachApplied(plain, JSON3, null);
      assertEquals(0, plain.length());
    }
  }

  @Nested
  @DisplayName("unknown status 422 carries descriptions (IMP-50)")
  class UnknownStatusDescriptions {

    @Test
    @DisplayName("keeps 'available' as names and adds namedFilters with descriptions")
    void addsDescriptions() throws Exception {
      McpRoutingException ex = McpRoutingException.unknownNamedFilter("pendig", "header",
          List.copyOf(McpNamedFilters.parseWhereByName(JSON3).keySet()),
          McpNamedFilters.summarize(JSON3));
      org.codehaus.jettison.json.JSONObject envelope = ex.toEnvelope();
      assertEquals(422, envelope.getInt("status"));
      assertEquals(3, envelope.getJSONArray("available").length());
      assertEquals("completed", envelope.getJSONArray("available").getString(0));
      JSONArray described = envelope.getJSONArray("namedFilters");
      assertEquals(3, described.length());
      assertEquals("Paid in full", described.getJSONObject(0).getString("description"));
    }
  }
}
