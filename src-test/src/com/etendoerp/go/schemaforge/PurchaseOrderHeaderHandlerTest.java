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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
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
 * Unit tests for PurchaseOrderHeaderHandler.afterHandle().
 *
 * The afterHandle() logic is identical to SalesOrderHeaderHandler — it annotates
 * hasLinkedDocuments by querying C_Invoice and M_InOut. These tests confirm the same
 * contract holds for the purchase-order handler.
 */
public class PurchaseOrderHeaderHandlerTest {

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtxWithId(String recordId) {
    return NeoContext.builder()
        .specName("purchase-order")
        .entityName("header")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONObject orderRec = new JSONObject().put("id", id).put("documentNo", "PO-" + id);
    JSONArray data = new JSONArray().put(orderRec);
    JSONObject response = new JSONObject().put("data", data);
    return new JSONObject().put("response", response);
  }

  private static JSONObject listBody(String... ids) throws JSONException {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put("id", id).put("documentNo", "PO-" + id));
    }
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  // ── guard conditions ───────────────────────────────────────────────────────

  @Test
  public void afterHandle_returnsNull_forNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenPreviousResultIsNull() {
    NeoContext ctx = getCtxWithId("po-1");
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenBodyIsNull() {
    NeoContext ctx = getCtxWithId("po-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
  }

  @Test
  public void afterHandle_returnsNull_whenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("po-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new PurchaseOrderHeaderHandler().afterHandle(ctx));
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
      when(rs.next()).thenReturn(true);

      JSONObject body = singleRecordBody("po-1");
      NeoContext ctx = getCtxWithId("po-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

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
      when(rs.next()).thenReturn(false);

      JSONObject body = singleRecordBody("po-2");
      NeoContext ctx = getCtxWithId("po-2");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

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
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("po-1");

      JSONObject body = listBody("po-1", "po-2");
      NeoContext ctx = NeoContext.builder()
          .specName("purchase-order").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertTrue(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
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

      JSONObject body = singleRecordBody("po-3");
      NeoContext ctx = getCtxWithId("po-3");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

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

      JSONArray dataArr = new JSONArray();
      dataArr.put(new JSONObject().put("id", "po-4").put("documentNo", "PO-4"));
      dataArr.put(new JSONObject().put("id", "po-5").put("documentNo", "PO-5"));
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", dataArr));
      NeoContext ctx = NeoContext.builder()
          .specName("purchase-order").entityName("header")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .build();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

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
    NeoContext ctx = NeoContext.builder()
        .specName("purchase-order").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .build();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = new PurchaseOrderHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  private static PurchaseOrderHeaderHandler handlerWithMockClone(NeoCloneRecordHandler mockClone)
      throws Exception {
    PurchaseOrderHeaderHandler handler = new PurchaseOrderHeaderHandler();
    Field field = PurchaseOrderHeaderHandler.class.getDeclaredField("cloneRecordHandler");
    field.setAccessible(true);
    field.set(handler, mockClone);
    return handler;
  }

  @Test
  public void handle_shortCircuits_whenCloneHandlerResponds() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    PurchaseOrderHeaderHandler handler = handlerWithMockClone(mockClone);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "clone"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("cloneRecord").build();
    when(mockClone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  @Test
  public void handle_returnsNull_whenNoHandlerMatches() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    PurchaseOrderHeaderHandler handler = handlerWithMockClone(mockClone);

    // CRUD endpoint — all downstream handlers return null without DB access
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockClone.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }
}
