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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link NeoOrderedListPage}: the shared step that narrows a REST list fetch to the
 * page a customization ordered ({@link NeoHandler#orderedPage}) and puts the rows back in its order.
 *
 * @covers com.etendoerp.go.schemaforge.NeoOrderedListPage
 * @covers com.etendoerp.go.schemaforge.NeoOrderedPage
 */
class NeoOrderedListPageTest {

  private MockedStatic<NeoExtensionDispatcher> dispatcher;

  @BeforeEach
  void open() {
    dispatcher = Mockito.mockStatic(NeoExtensionDispatcher.class);
  }

  @AfterEach
  void close() {
    dispatcher.close();
  }

  private static NeoContext remembered(Map<String, String> params) {
    NeoContext ctx = NeoContext.builder().specName("s").entityName("e").httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD).build();
    NeoListAggregateQuery.remember(ctx, params);
    return ctx;
  }

  private static Map<String, String> params(String start, String end) {
    Map<String, String> p = new HashMap<>();
    p.put("_startRow", start);
    p.put("_endRow", end);
    p.put("_sortBy", "kind,-id");
    p.put("whereAndFilterClause", "(a = 1)");
    return p;
  }

  private void customization(NeoOrderedPage page) {
    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }

      @Override
      public NeoOrderedPage orderedPage(NeoContext context, NeoListAggregateQuery filteredSet, int startRow,
          int pageSize) {
        return page;
      }
    };
    dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(Mockito.any())).thenReturn(handler);
  }

  /** The fetch is narrowed to the page's ids, in one page sorted by id, keeping the existing where clause. */
  @Test
  void apply_narrowsTheFetchToThePage() {
    customization(new NeoOrderedPage(List.of("A1", "B2"), 90));
    Map<String, String> p = params("50", "99");

    assertTrue(NeoOrderedListPage.apply(remembered(p), p));

    assertEquals("((a = 1)) and e.id in ('A1', 'B2')", p.get("whereAndFilterClause"));
    assertEquals("0", p.get("_startRow"));
    assertEquals("1", p.get("_endRow"));
    assertEquals("id", p.get("_sortBy"));
  }

  /** An empty page serves nothing instead of falling back to the whole set. */
  @Test
  void apply_emptyPage_matchesNoRow() {
    customization(new NeoOrderedPage(List.of(), 0));
    Map<String, String> p = params("0", "49");
    assertTrue(NeoOrderedListPage.apply(remembered(p), p));
    assertTrue(p.get("whereAndFilterClause").endsWith("and 1 = 0"));
  }

  /** The customization declining leaves the params exactly as they were. */
  @Test
  void apply_whenTheCustomizationDeclines_changesNothing() {
    customization(null);
    Map<String, String> p = params("0", "49");
    Map<String, String> before = new HashMap<>(p);
    assertFalse(NeoOrderedListPage.apply(remembered(p), p));
    assertEquals(before, p);
  }

  /** No customization for the entity: untouched. */
  @Test
  void apply_withoutCustomization_changesNothing() {
    dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(Mockito.any())).thenReturn(null);
    Map<String, String> p = params("0", "49");
    Map<String, String> before = new HashMap<>(p);
    assertFalse(NeoOrderedListPage.apply(remembered(p), p));
    assertEquals(before, p);
  }

  /** A request that did not go through the default list path has nothing remembered: untouched. */
  @Test
  void apply_withoutRememberedFilteredSet_changesNothing() {
    customization(new NeoOrderedPage(List.of("A"), 1));
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    Map<String, String> p = params("0", "49");
    assertFalse(NeoOrderedListPage.apply(ctx, p));
  }

  /** An id that is not a plain identifier is refused whole: it would be spliced into the HQL. */
  @Test
  void apply_unsafeId_isRefused() {
    customization(new NeoOrderedPage(List.of("A1", "x') or 1=1 --"), 2));
    Map<String, String> p = params("0", "49");
    Map<String, String> before = new HashMap<>(p);
    assertFalse(NeoOrderedListPage.apply(remembered(p), p));
    assertEquals(before, p);
  }

  /** A page beyond the supported size, or a malformed range, keeps the default order. */
  @Test
  void apply_oversizedOrInvalidRange_changesNothing() {
    customization(new NeoOrderedPage(List.of("A"), 1));
    for (String[] range : new String[][] { { "0", "5000" }, { "10", "5" }, { "-1", "10" } }) {
      Map<String, String> p = params(range[0], range[1]);
      assertFalse(NeoOrderedListPage.apply(remembered(p), p), range[0] + "-" + range[1]);
    }
  }

  /** Rows come back in the page's order and the paging figures describe the whole list. */
  @Test
  void reorder_putsTheRowsInThePageOrderAndRestoresTheTotals() throws Exception {
    customization(new NeoOrderedPage(List.of("C", "A", "B"), 123));
    Map<String, String> p = params("50", "52");
    NeoContext ctx = remembered(p);
    NeoOrderedListPage.apply(ctx, p);
    JSONObject body = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray().put(new JSONObject().put("id", "A")).put(new JSONObject().put("id", "B"))
            .put(new JSONObject().put("id", "C")))
        .put("startRow", 0).put("endRow", 2).put("totalRows", 3));

    NeoOrderedListPage.reorder(ctx, body);

    JSONObject response = body.getJSONObject("response");
    JSONArray data = response.getJSONArray("data");
    assertEquals("C", data.getJSONObject(0).getString("id"));
    assertEquals("A", data.getJSONObject(1).getString("id"));
    assertEquals("B", data.getJSONObject(2).getString("id"));
    assertEquals(50, response.getInt("startRow"));
    assertEquals(52, response.getInt("endRow"));
    assertEquals(123, response.getInt("totalRows"));
  }

  /** When nothing was narrowed, the response is left alone. */
  @Test
  void reorder_withoutApply_isANoOp() throws Exception {
    NeoContext ctx = remembered(params("0", "49"));
    JSONObject body = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray().put(new JSONObject().put("id", "B")).put(new JSONObject().put("id", "A")))
        .put("totalRows", 2));
    String before = body.toString();
    NeoOrderedListPage.reorder(ctx, body);
    assertEquals(before, body.toString());
    NeoOrderedListPage.reorder(ctx, null);
    assertNull(ctx.getAttribute("none"));
  }

  /** The page size limit is inclusive: exactly 1000 rows are served, 1001 decline. */
  @Test
  void apply_pageSizeBoundary_is1000Inclusive() {
    customization(new NeoOrderedPage(List.of("A"), 1));
    Map<String, String> ok = params("0", "999");
    assertTrue(NeoOrderedListPage.apply(remembered(ok), ok));
    Map<String, String> tooBig = params("0", "1000");
    Map<String, String> before = new HashMap<>(tooBig);
    assertFalse(NeoOrderedListPage.apply(remembered(tooBig), tooBig));
    assertEquals(before, tooBig);
  }

  /** The identifier pattern: 64 characters pass, 65, empty, null and quoted values are refused. */
  @Test
  void apply_idPattern_boundaries() {
    String id64 = "a".repeat(64);
    customization(new NeoOrderedPage(List.of(id64, "A_b-9"), 2));
    Map<String, String> p = params("0", "49");
    assertTrue(NeoOrderedListPage.apply(remembered(p), p));
    for (String bad : new String[] { "a".repeat(65), "", "a b", "a'b", "a;b" }) {
      customization(new NeoOrderedPage(List.of("A", bad), 2));
      Map<String, String> q = params("0", "49");
      assertFalse(NeoOrderedListPage.apply(remembered(q), q), "[" + bad + "]");
    }
    customization(new NeoOrderedPage(java.util.Arrays.asList("A", null), 2));
    Map<String, String> n = params("0", "49");
    assertFalse(NeoOrderedListPage.apply(remembered(n), n));
  }

  /** Without an existing where clause the restriction stands alone; the request orderBy is dropped. */
  @Test
  void apply_withoutWhere_usesTheBareRestriction_andDropsOrderBy() {
    customization(new NeoOrderedPage(List.of("A1"), 1));
    Map<String, String> p = params("0", "49");
    p.remove("whereAndFilterClause");
    p.put("_orderBy", "name");
    assertTrue(NeoOrderedListPage.apply(remembered(p), p));
    assertEquals("e.id in ('A1')", p.get("whereAndFilterClause"));
    assertFalse(p.containsKey("_orderBy"));
  }

  /** A missing _startRow/_endRow is the first page of one row (no range to refuse). */
  @Test
  void apply_missingRange_defaultsToTheFirstPosition() {
    customization(new NeoOrderedPage(List.of("A1"), 1));
    Map<String, String> p = params("0", "0");
    p.remove("_startRow");
    p.remove("_endRow");
    assertTrue(NeoOrderedListPage.apply(remembered(p), p));
  }

  /** An id the narrowed fetch did not return is skipped; the rest keep order and the totals stay those of the list. */
  @Test
  void reorder_idMissingFromTheFetch_isTolerated() throws Exception {
    customization(new NeoOrderedPage(List.of("C", "GONE", "A"), 77));
    Map<String, String> p = params("10", "12");
    NeoContext ctx = remembered(p);
    NeoOrderedListPage.apply(ctx, p);
    JSONObject body = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray().put(new JSONObject().put("id", "A")).put(new JSONObject().put("id", "C")))
        .put("startRow", 0).put("endRow", 1).put("totalRows", 2));

    NeoOrderedListPage.reorder(ctx, body);

    JSONObject response = body.getJSONObject("response");
    JSONArray data = response.getJSONArray("data");
    assertEquals(2, data.length());
    assertEquals("C", data.getJSONObject(0).getString("id"));
    assertEquals("A", data.getJSONObject(1).getString("id"));
    assertEquals(10, response.getInt("startRow"));
    assertEquals(11, response.getInt("endRow"));
    assertEquals(77, response.getInt("totalRows"));
  }

  /** An empty page restores an empty row set, endRow = startRow and the real total (rows beyond the end). */
  @Test
  void reorder_emptyPage_restoresTheTotalsOfTheWholeList() throws Exception {
    customization(new NeoOrderedPage(List.of(), 40));
    Map<String, String> p = params("50", "99");
    NeoContext ctx = remembered(p);
    NeoOrderedListPage.apply(ctx, p);
    JSONObject body = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray()).put("startRow", 0).put("endRow", 0).put("totalRows", 0));

    NeoOrderedListPage.reorder(ctx, body);

    JSONObject response = body.getJSONObject("response");
    assertEquals(0, response.getJSONArray("data").length());
    assertEquals(50, response.getInt("startRow"));
    assertEquals(50, response.getInt("endRow"));
    assertEquals(40, response.getInt("totalRows"));
  }

  /** A response without the DAL envelope is left alone, even after a narrowed fetch. */
  @Test
  void reorder_responseWithoutEnvelope_isLeftAlone() throws Exception {
    customization(new NeoOrderedPage(List.of("A"), 1));
    Map<String, String> p = params("0", "49");
    NeoContext ctx = remembered(p);
    NeoOrderedListPage.apply(ctx, p);
    JSONObject body = new JSONObject().put("other", 1);
    NeoOrderedListPage.reorder(ctx, body);
    assertEquals("{\"other\":1}", body.toString());
  }

  /** The page value object tolerates null ids and does not let callers change its ids. */
  @Test
  void orderedPage_nullIds_areEmpty_andIdsAreReadOnly() {
    assertTrue(new NeoOrderedPage(null, 5).getIds().isEmpty());
    assertEquals(5, new NeoOrderedPage(null, 5).getTotalRows());
    org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
        () -> new NeoOrderedPage(new java.util.ArrayList<>(List.of("A")), 1).getIds().add("B"));
  }
}
