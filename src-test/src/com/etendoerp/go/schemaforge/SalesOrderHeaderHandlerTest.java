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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for SalesOrderHeaderHandler.afterHandle().
 *
 * Tests are split into two groups:
 * <ul>
 *   <li><strong>Guard-condition</strong> – verifies early returns without any DB access</li>
 *   <li><strong>DB-mocked</strong> – mocks OBDal/Connection/PreparedStatement/ResultSet to test
 *       the hasLinkedDocuments annotation logic for both single-record and list responses</li>
 * </ul>
 */
public class SalesOrderHeaderHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtxWithId(String recordId) {
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static NeoContext listCtx() {
    return getCtxWithId(null);
  }

  /** Wraps a single JSON record in the standard NEO response envelope. */
  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONObject record = new JSONObject().put("id", id).put("documentNo", "1000000");
    JSONArray data = new JSONArray().put(record);
    JSONObject response = new JSONObject().put("data", data);
    return new JSONObject().put("response", response);
  }

  /** Builds a list response body with the given ids. */
  private static JSONObject listBody(String... ids) throws JSONException {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put("id", id).put("documentNo", id + "-DOC"));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoContext ctxWithPreviousResult(String recordId, JSONObject body) {
    NeoResponse previous = NeoResponse.ok(body);
    NeoContext ctx = getCtxWithId(recordId);
    ctx.setPreviousResult(previous);
    return ctx;
  }

  private static NeoContext listCtxWithPreviousResult(JSONObject body) {
    NeoResponse previous = NeoResponse.ok(body);
    NeoContext ctx = listCtx();
    ctx.setPreviousResult(previous);
    return ctx;
  }

  // ── guard conditions (no DB needed) ───────────────────────────────────────

  @Test
  public void afterHandle_returnsNull_forPostMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_forPatchMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenPreviousResultIsNull() {
    NeoContext ctx = getCtxWithId("order-1");
    // previousResult not set → null
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenBodyIsNull() {
    NeoContext ctx = getCtxWithId("order-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenNoResponseWrapper() throws JSONException {
    NeoContext ctx = getCtxWithId("order-1");
    ctx.setPreviousResult(NeoResponse.ok(new JSONObject().put("something", "else")));
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("order-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  // ── single-record GET ──────────────────────────────────────────────────────

  @Test
  public void afterHandle_singleRecord_annotatesTrue_whenLinkedDocumentExists() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true); // linked document found

      JSONObject body = singleRecordBody("order-1");
      NeoContext ctx = ctxWithPreviousResult("order-1", body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertTrue(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  @Test
  public void afterHandle_singleRecord_annotatesFalse_whenNoLinkedDocuments() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no linked documents

      JSONObject body = singleRecordBody("order-2");
      NeoContext ctx = ctxWithPreviousResult("order-2", body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  // ── list GET (batch) ───────────────────────────────────────────────────────

  @Test
  public void afterHandle_list_annotatesMatchingIds_asTrue() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      // Only "order-1" has linked documents
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("order-1");

      JSONObject body = listBody("order-1", "order-2");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      assertTrue(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  @Test
  public void afterHandle_list_annotatesAllFalse_whenNoneHaveLinkedDocuments() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // empty result set

      JSONObject body = listBody("order-3", "order-4");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  @Test
  public void afterHandle_list_returnsOkWithOriginalBody_afterAnnotation() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = listBody("order-5");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull("afterHandle must return a response after annotating", result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  // ── DB error resilience ────────────────────────────────────────────────────

  @Test
  public void afterHandle_singleRecord_returnsFalse_whenDbQueryThrows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("DB down"));

      JSONObject body = singleRecordBody("order-3");
      NeoContext ctx = ctxWithPreviousResult("order-3", body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      // checkLinkedDocuments catches the exception and returns false
      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  @Test
  public void afterHandle_list_annotatesAllFalse_whenBatchQueryThrows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("DB down"));

      JSONObject body = listBody("order-6", "order-7");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      // batchCheckLinkedDocuments catches the exception and returns empty set → all false
      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  @Test
  public void afterHandle_list_returnsResponse_whenAllRecordsHaveNoId() throws JSONException {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("documentNo", "NO-ID-DOC"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = listCtxWithPreviousResult(body);

    // annotateListWithLinkedDocuments collects no ids → returns early without DB call
    NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
  }
}
