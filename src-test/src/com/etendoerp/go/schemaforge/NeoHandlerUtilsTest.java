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

  // ── extractCreatedIdFromPreviousResult (ETP-4029) ────────────────────────

  /**
   * Correct/real shape produced by {@code DefaultJsonDataService.add()}: {@code response.data}
   * is a {@code JSONArray} with a single element — the created record.
   */
  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsIdFromOneElementArray() throws Exception {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", "created-1"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertEquals("created-1", NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullForEmptyArray() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder().build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenBodyNull() {
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, null))
        .build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenNoResponseWrapper() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, new JSONObject()))
        .build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenDataMissing() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject());
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();
    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  /**
   * Malformed shape: {@code data} present but as a plain {@code JSONObject}, not an array
   * (the OLD, wrong assumption some earlier code relied on). {@code optJSONArray} silently
   * returns {@code null} for a non-array value — the method must fail closed (return null),
   * never throw.
   */
  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenDataIsPlainObjectNotArray()
      throws Exception {
    JSONObject dataAsObject = new JSONObject().put("id", "should-not-be-read");
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataAsObject));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsNullWhenFirstElementHasNoId()
      throws Exception {
    JSONArray data = new JSONArray().put(new JSONObject().put("someOtherField", "x"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  @Test
  public void testExtractCreatedIdFromPreviousResultReturnsFirstElementIdWhenMultipleElements()
      throws Exception {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "first-id"))
        .put(new JSONObject().put("id", "second-id"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = NeoContext.builder()
        .previousResult(new NeoResponse(201, body))
        .build();

    assertEquals("first-id", NeoHandlerUtils.extractCreatedIdFromPreviousResult(ctx));
  }

  // ── ETP-4531: mirrorFieldValue ────────────────────────────────────────────

  @Test
  public void testMirrorFieldValueCopiesSourceIntoTarget() throws Exception {
    JSONObject body = new JSONObject().put("invoiceDate", "2026-07-01");

    NeoHandlerUtils.mirrorFieldValue(body, "invoiceDate", "accountingDate");

    assertEquals("2026-07-01", body.getString("accountingDate"));
  }

  @Test
  public void testMirrorFieldValueOverwritesExistingTargetValue() throws Exception {
    JSONObject body = new JSONObject()
        .put("invoiceDate", "2026-07-10").put("accountingDate", "2026-01-01");

    NeoHandlerUtils.mirrorFieldValue(body, "invoiceDate", "accountingDate");

    assertEquals("2026-07-10", body.getString("accountingDate"));
  }

  @Test
  public void testMirrorFieldValueNoopWhenSourceFieldAbsent() throws Exception {
    JSONObject body = new JSONObject().put("otherField", "x");

    NeoHandlerUtils.mirrorFieldValue(body, "invoiceDate", "accountingDate");

    assertTrue(!body.has("accountingDate"));
  }

  @Test
  public void testMirrorFieldValueNullBodyDoesNotThrow() {
    // Must be a no-op guard: null body is a valid request-body shape to defend against.
    NeoHandlerUtils.mirrorFieldValue(null, "invoiceDate", "accountingDate");
  }
}
