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
    JSONObject orderRec = new JSONObject().put("id", id).put("documentNo", "1000000");
    JSONArray data = new JSONArray().put(orderRec);
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

  /**
   * Verifies that afterHandle returns null for POST requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForPostMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null for PATCH requests without invoking DB logic.
   */
  @Test
  public void testAfterHandleReturnsNullForPatchMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = getCtxWithId("order-1");
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtxWithId("order-1");
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response JSON lacks the "response" wrapper.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoResponseWrapper() throws JSONException {
    NeoContext ctx = getCtxWithId("order-1");
    ctx.setPreviousResult(NeoResponse.ok(new JSONObject().put("something", "else")));
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtxWithId("order-1");
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new SalesOrderHeaderHandler().afterHandle(ctx));
  }

  // ── total discount adjustment (ETP-4029 follow-up: same gap as invoices) ──
  //
  // afterHandle() always runs the hasLinkedDocuments DB check after the discount loop (single
  // LIMIT-1 query for detail, one batch query for list) — every test below mocks OBDal minimally
  // (no linked documents found) purely to keep that unconditional step from touching a real DB,
  // exactly like the existing single-record/list-GET tests further down in this file.

  /**
   * Creates a {@link SalesOrderHeaderHandler} with its {@code totalDiscountService} field
   * replaced by the provided mock via reflection, bypassing CDI in the unit-test context.
   */
  private static SalesOrderHeaderHandler handlerWithTotalDiscountMock(
      TotalDiscountService mock) throws Exception {
    SalesOrderHeaderHandler handler = new SalesOrderHeaderHandler();
    Field field = SalesOrderHeaderHandler.class.getDeclaredField("totalDiscountService");
    field.setAccessible(true);
    field.set(handler, mock);
    return handler;
  }

  private static JSONObject orderRecordWithDiscount(boolean processed, double discount,
      double grandTotal) throws JSONException {
    return new JSONObject().put("id", "order-disc-1").put("processed", processed).put(
        "etgoTotalDiscount", discount).put("grandTotalAmount", grandTotal);
  }

  /** Stubs OBDal so the DB-backed hasLinkedDocuments check finds nothing, for either flow. */
  private static void stubNoLinkedDocuments(MockedStatic<OBDal> obDalMock) throws Exception {
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);
  }

  /**
   * Verifies that a processed order is not adjusted — TotalDiscountService already
   * materialized the discount line in the DB at completion time.
   */
  @Test
  public void testAfterHandleSkipsProcessedOrderWithDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(true, 10.0, 198.99))));
      NeoContext ctx = getCtxWithId("order-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft order with no total discount (etgoTotalDiscount=0) is not modified.
   */
  @Test
  public void testAfterHandleSkipsDraftOrderWithNoDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(false, 0.0, 221.10))));
      NeoContext ctx = getCtxWithId("order-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(221.10, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft order whose discount is already materialized as a real line is NOT
   * adjusted a second time.
   */
  @Test
  public void testAfterHandleSkipsDraftOrderWithMaterializedDiscountLine() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("order-disc-1", false)).thenReturn(true);
      SalesOrderHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(false, 10.0, 198.99))));
      NeoContext ctx = getCtxWithId("order-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft order with etgoTotalDiscount=10 and grandTotalAmount=221.10 is
   * adjusted to 198.99 (221.10 x 0.90, rounded to 2 decimals) — the exact scenario reproduced
   * manually against the running app (order #1000207, product 201.00 + 21% tax, 10% discount).
   * Uses a mocked service since the record carries an "id" (unlike the guard-condition tests
   * above, which never reach {@code hasDiscountLine} because the discount is 0 or the order is
   * processed).
   */
  @Test
  public void testAfterHandleAdjustsGrandTotalForDraftOrderWithDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("order-disc-1", false)).thenReturn(false);
      SalesOrderHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(orderRecordWithDiscount(false, 10.0, 221.10))));
      NeoContext ctx = getCtxWithId("order-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.005);
    }
  }

  /**
   * Verifies that every record in a list response is adjusted independently when each carries
   * its own positive discount.
   */
  @Test
  public void testAfterHandleAdjustsAllRecordsInListResponse() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      SalesOrderHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONArray data = new JSONArray()
          .put(new JSONObject().put("id", "order-1").put("processed", false).put("etgoTotalDiscount", 10.0)
              .put("grandTotalAmount", 100.0))
          .put(new JSONObject().put("id", "order-2").put("processed", false).put("etgoTotalDiscount", 20.0)
              .put("grandTotalAmount", 200.0));
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
      NeoContext ctx = listCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(90.0, resultData.getJSONObject(0).getDouble("grandTotalAmount"), 0.001);
      assertEquals(160.0, resultData.getJSONObject(1).getDouble("grandTotalAmount"), 0.001);
    }
  }

  // ── single-record GET ──────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates hasLinkedDocuments=true when the DB query finds a linked document.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesTrueWhenLinkedDocumentExists() throws Exception {
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

      JSONObject body = singleRecordBody("order-1");
      NeoContext ctx = ctxWithPreviousResult("order-1", body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertTrue(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle annotates hasLinkedDocuments=false when the DB query returns no rows.
   */
  @Test
  public void testAfterHandleSingleRecordAnnotatesFalseWhenNoLinkedDocuments() throws Exception {
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

      JSONObject body = singleRecordBody("order-2");
      NeoContext ctx = ctxWithPreviousResult("order-2", body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  // ── list GET (batch) ───────────────────────────────────────────────────────

  /**
   * Verifies that afterHandle annotates only the ids returned by the batch query as true.
   */
  @Test
  public void testAfterHandleListAnnotatesMatchingIdsAsTrue() throws Exception {
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

  /**
   * Verifies that afterHandle annotates all list records as false when none have linked documents.
   */
  @Test
  public void testAfterHandleListAnnotatesAllFalseWhenNoneHaveLinkedDocuments() throws Exception {
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

      JSONObject body = listBody("order-3", "order-4");
      NeoContext ctx = listCtxWithPreviousResult(body);

      NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle returns a 200 response after annotating the list.
   */
  @Test
  public void testAfterHandleListReturnsOkWithOriginalBodyAfterAnnotation() throws Exception {
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

  /**
   * Verifies that afterHandle annotates hasLinkedDocuments=false and does not throw when the single-record DB query fails.
   */
  @Test
  public void testAfterHandleSingleRecordReturnsFalseWhenDbQueryThrows() throws Exception {
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

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle annotates all list records as false and does not throw when the batch DB query fails.
   */
  @Test
  public void testAfterHandleListAnnotatesAllFalseWhenBatchQueryThrows() throws Exception {
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

      assertNotNull(result);
      JSONArray data = result.getBody().getJSONObject("response").getJSONArray("data");
      assertFalse(data.getJSONObject(0).getBoolean("hasLinkedDocuments"));
      assertFalse(data.getJSONObject(1).getBoolean("hasLinkedDocuments"));
    }
  }

  /**
   * Verifies that afterHandle returns a valid response when all list records lack an id field,
   * skipping the batch DB query entirely.
   */
  @Test
  public void testAfterHandleListReturnsResponseWhenAllRecordsHaveNoId() throws JSONException {
    JSONArray data = new JSONArray();
    data.put(new JSONObject().put("documentNo", "NO-ID-DOC"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = listCtxWithPreviousResult(body);

    NeoResponse result = new SalesOrderHeaderHandler().afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  private static SalesOrderHeaderHandler handlerWithMockClone(NeoCloneRecordHandler mockClone)
      throws Exception {
    SalesOrderHeaderHandler handler = new SalesOrderHeaderHandler();
    Field field = SalesOrderHeaderHandler.class.getDeclaredField("cloneRecordHandler");
    field.setAccessible(true);
    field.set(handler, mockClone);
    return handler;
  }

  /**
   * Verifies that handle returns the clone response immediately when the clone handler matches.
   */
  @Test
  public void testHandleShortCircuitsWhenCloneHandlerResponds() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    SalesOrderHeaderHandler handler = handlerWithMockClone(mockClone);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "clone"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("cloneRecord").build();
    when(mockClone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when no downstream handler matches the context.
   */
  @Test
  public void testHandleReturnsNullWhenNoHandlerMatches() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    SalesOrderHeaderHandler handler = handlerWithMockClone(mockClone);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockClone.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }
}
