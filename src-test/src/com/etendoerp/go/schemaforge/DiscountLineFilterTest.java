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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link DiscountLineFilter#filterFromResponse(NeoContext)}.
 *
 * Verifies that discount product lines (ETGO_DTO) are removed from GET responses
 * and that the method returns null (keep original) when no filtering is needed.
 */
public class DiscountLineFilterTest {

  private static final String DISCOUNT_ID = TotalDiscountService.DISCOUNT_PRODUCT_ID;
  private static final String REGULAR_PRODUCT = "00000000000000000000000000000001";

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext contextWith(NeoResponse prev) {
    NeoContext ctx = NeoContext.builder().build();
    ctx.setPreviousResult(prev);
    return ctx;
  }

  private static JSONObject responseBodyWith(JSONArray data) throws Exception {
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static JSONObject row(String id, String productId) throws Exception {
    return new JSONObject().put("id", id).put("product", productId);
  }

  // ── guard conditions ──────────────────────────────────────────────────────

  @Test
  public void testNullPreviousResult_returnsNull() {
    assertNull(DiscountLineFilter.filterFromResponse(contextWith(null)));
  }

  @Test
  public void testBodyWithoutResponseWrapper_returnsNull() throws Exception {
    NeoResponse prev = NeoResponse.ok(new JSONObject().put("status", "ok"));
    assertNull(DiscountLineFilter.filterFromResponse(contextWith(prev)));
  }

  @Test
  public void testEmptyDataArray_returnsNull() throws Exception {
    NeoResponse prev = NeoResponse.ok(responseBodyWith(new JSONArray()));
    assertNull(DiscountLineFilter.filterFromResponse(contextWith(prev)));
  }

  @Test
  public void testNoDiscountLines_returnsNull() throws Exception {
    JSONArray data = new JSONArray()
        .put(row("1", REGULAR_PRODUCT))
        .put(row("2", REGULAR_PRODUCT));
    assertNull(DiscountLineFilter.filterFromResponse(contextWith(NeoResponse.ok(responseBodyWith(data)))));
  }

  // ── filtering ─────────────────────────────────────────────────────────────

  @Test
  public void testOneDiscountLineAmongRegular_discountLineRemoved() throws Exception {
    JSONArray data = new JSONArray()
        .put(row("line-1", REGULAR_PRODUCT))
        .put(row("line-2", DISCOUNT_ID))
        .put(row("line-3", REGULAR_PRODUCT));
    NeoResponse prev = NeoResponse.ok(responseBodyWith(data));

    NeoResponse result = DiscountLineFilter.filterFromResponse(contextWith(prev));

    assertNotNull(result);
    JSONArray filtered = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(2, filtered.length());
    assertEquals("line-1", filtered.getJSONObject(0).getString("id"));
    assertEquals("line-3", filtered.getJSONObject(1).getString("id"));
  }

  @Test
  public void testMultipleDiscountLines_allRemovedRegularLinesKept() throws Exception {
    JSONArray data = new JSONArray()
        .put(row("line-1", DISCOUNT_ID))
        .put(row("line-2", REGULAR_PRODUCT))
        .put(row("line-3", DISCOUNT_ID));
    NeoResponse prev = NeoResponse.ok(responseBodyWith(data));

    NeoResponse result = DiscountLineFilter.filterFromResponse(contextWith(prev));

    assertNotNull(result);
    JSONArray filtered = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, filtered.length());
    assertEquals("line-2", filtered.getJSONObject(0).getString("id"));
  }

  @Test
  public void testOnlyDiscountLines_returnsResponseWithEmptyData() throws Exception {
    JSONArray data = new JSONArray()
        .put(row("line-1", DISCOUNT_ID))
        .put(row("line-2", DISCOUNT_ID));
    NeoResponse prev = NeoResponse.ok(responseBodyWith(data));

    NeoResponse result = DiscountLineFilter.filterFromResponse(contextWith(prev));

    assertNotNull(result);
    assertEquals(0, result.getBody().getJSONObject("response").getJSONArray("data").length());
  }

  @Test
  public void testOrderOfRemainingLinesPreserved() throws Exception {
    JSONArray data = new JSONArray()
        .put(row("line-3", REGULAR_PRODUCT))
        .put(row("line-1", DISCOUNT_ID))
        .put(row("line-2", REGULAR_PRODUCT));
    NeoResponse prev = NeoResponse.ok(responseBodyWith(data));

    NeoResponse result = DiscountLineFilter.filterFromResponse(contextWith(prev));

    assertNotNull(result);
    JSONArray filtered = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals("line-3", filtered.getJSONObject(0).getString("id"));
    assertEquals("line-2", filtered.getJSONObject(1).getString("id"));
  }
}
