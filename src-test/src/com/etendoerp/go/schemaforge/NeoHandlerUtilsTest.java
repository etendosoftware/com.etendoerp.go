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
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link NeoHandlerUtils}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code extractGetDataArray} — all early-exit paths and the happy path.</li>
 *   <li>{@code collectIds} — normal, empty-id filtering, and empty-array cases.</li>
 * </ul>
 */
public class NeoHandlerUtilsTest {

  // ── extractGetDataArray ──────────────────────────────────────────────────

  @Test
  public void testExtractGetDataArrayReturnsNullForNonGet() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenBodyNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenNoResponseWrapper() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, new JSONObject()))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenDataArrayMissing() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject());
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsNullWhenDataArrayEmpty() throws Exception {
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    assertNull(NeoHandlerUtils.extractGetDataArray(ctx));
  }

  @Test
  public void testExtractGetDataArrayReturnsArrayWhenValid() throws Exception {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", "rec-1"));
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    JSONArray result = NeoHandlerUtils.extractGetDataArray(ctx);
    assertNotNull(result);
    assertEquals(1, result.length());
    assertEquals("rec-1", result.getJSONObject(0).getString("id"));
  }

  // ── collectIds ───────────────────────────────────────────────────────────

  @Test
  public void testCollectIdsReturnsAllNonBlankIds() throws Exception {
    JSONArray arr = new JSONArray()
        .put(new JSONObject().put("id", "id1"))
        .put(new JSONObject().put("id", "id2"));
    List<String> ids = NeoHandlerUtils.collectIds(arr);
    assertEquals(2, ids.size());
    assertTrue(ids.contains("id1"));
    assertTrue(ids.contains("id2"));
  }

  @Test
  public void testCollectIdsSkipsBlankAndMissingIds() throws Exception {
    JSONArray arr = new JSONArray()
        .put(new JSONObject().put("id", ""))
        .put(new JSONObject().put("id", "valid"))
        .put(new JSONObject());
    List<String> ids = NeoHandlerUtils.collectIds(arr);
    assertEquals(1, ids.size());
    assertEquals("valid", ids.get(0));
  }

  @Test
  public void testCollectIdsReturnsEmptyListForEmptyArray() throws Exception {
    List<String> ids = NeoHandlerUtils.collectIds(new JSONArray());
    assertTrue(ids.isEmpty());
  }
}
