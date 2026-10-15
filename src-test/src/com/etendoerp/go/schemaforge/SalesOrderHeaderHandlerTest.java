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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.validation.ValidationException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

/**
 * Unit tests for SalesOrderHeaderHandler.afterHandle().
 *
 * Tests are split into two groups:
 * <ul>
 *   <li><strong>Guard-condition</strong> – verifies early returns without any DB access</li>
 *   <li><strong>DB-mocked</strong> – mocks OBDal/Connection/PreparedStatement/ResultSet to test
 *       the hasLinkedDocuments annotation logic for both single-record and list responses</li>
 * </ul>
 *
 * <p>{@code handle()} is also covered, including the Reactivate refusal on orders with linked
 * documents.
 *
 * @covers com.etendoerp.go.schemaforge.SalesOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.CreateShipmentHandler
 * @covers com.etendoerp.go.schemaforge.CreateDraftInvoiceHandler
 * @covers com.etendoerp.go.schemaforge.OrderReactivationGuard
 * @covers com.etendoerp.go.schemaforge.AbstractOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.DocActionRequestSupport
 * @covers com.etendoerp.go.schemaforge.DocActionResolution
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
      // ETP-5295 — stubbing a single true value would make this mocked ResultSet report a row
      // FOREVER. That was harmless while the after-handle hook advanced the cursor exactly
      // once, for the limit-one hasLinkedDocuments check, but it now issues three further
      // batch queries for the needsPrimaryDoc and needsInvoiceDoc flags, and each of those
      // keeps advancing the cursor until it reports no more rows. Against an always-true
      // cursor they never terminate, so this test would hang instead of failing.
      // Hence one row for the hasLinkedDocuments check and then exhaustion: this test is
      // about that flag only, and an empty cursor is the right answer for the other three.
      when(rs.next()).thenReturn(true, false);

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

  // ── Reactivate (DocAction RE) refused on orders with linked documents ──────

  /** Sentinel for the stored-DocAction column of the table below: loading the record throws. */
  private static final String STORED_READ_FAILS = "<stored read fails>";
  /** Sentinel for the stored-DocAction column: the context carries no tab to resolve. */
  private static final String NO_TAB = "<no tab>";
  /** Sentinel for the stored-DocAction column: the tab resolves, the record does not exist. */
  private static final String RECORD_NOT_FOUND = "<record not found>";
  /** Sentinel for the stored-DocAction column: the record exists, its entity has no DocAction. */
  private static final String NO_DOC_ACTION_PROPERTY = "<no documentAction property>";
  /** Property-check column of the table below: the requested value is not a member of the list. */
  private static final String NOT_IN_LIST = "not in list";
  /** Property-check column of the table below: the property refuses any write. */
  private static final String NOT_WRITABLE = "not writable";

  /**
   * Reactivate guard through {@code handle()}: it decides on the DocAction the DAL will actually
   * write and the DB procedure run — root {@code docAction} when non-blank and accepted by the
   * {@code documentAction} property ({@code checkIsValidValue} + {@code checkIsWritable}, the
   * checks {@code BaseOBObject.set} runs), else the record's stored {@code DocAction} — never on
   * {@code fieldValues.documentAction}. A refusal is 400 and never reaches the action router; a
   * lookup that cannot run is 500 (fail closed). The record is resolved as
   * {@code DocActionResolution.docActionToRun} does: tab → table name → record →
   * {@code documentAction}; a tab or record that cannot be resolved leaves the action unknown and
   * is refused with 500, while a record with nothing stored is not an error. An order id that
   * does not exist answers 404 {@code Record not found} whatever DocAction is sent — the same
   * answer {@code NeoActionRecordGuard} gives an unknown id.
   */
  @Test
  public void testHandleReactivateIsGuardedByLinkedDocuments_table() throws Exception {
    final String orderId = "order-re-1";
    final String tabId = "tab-order";
    final String entityName = "Order";
    // Columns: the label, the body sent, the stored DocAction, the property check on the requested
    // value (null when it is accepted), whether linked documents exist (null when the query
    // throws) and the expected status (null when the request falls through to the router).
    Object[][] cases = {
        { "root RE with linked docs is refused",
            "{\"docAction\":\"RE\"}", "CO", null, Boolean.TRUE, 400 },
        { "root RE wins over fieldValues CO",
            "{\"fieldValues\":{\"documentAction\":\"CO\"},\"docAction\":\"RE\"}", "CO", null,
            Boolean.TRUE, 400 },
        { "written root CO wins over fieldValues RE and the stored RE",
            "{\"fieldValues\":{\"documentAction\":\"RE\"},\"docAction\":\"CO\"}", "RE", null,
            Boolean.TRUE, null },
        { "empty body runs the stored RE and is refused",
            "{}", "RE", null, Boolean.TRUE, 400 },
        { "fieldValues RE without root runs the stored CO",
            "{\"fieldValues\":{\"documentAction\":\"RE\"}}", "CO", null, Boolean.TRUE, null },
        { "root RE without linked docs falls through",
            "{\"docAction\":\"RE\"}", "CO", null, Boolean.FALSE, null },
        { "unreadable record fails closed even when docAction is sent",
            "{\"docAction\":\"RE\"}", STORED_READ_FAILS, null, Boolean.TRUE, 500 },
        { "failed linked-docs lookup fails closed",
            "{\"docAction\":\"RE\"}", "CO", null, null, 500 },
        { "root re outside the list is not written, the stored RE runs and is refused",
            "{\"docAction\":\"re\"}", "RE", NOT_IN_LIST, Boolean.TRUE, 400 },
        { "root CO on a non-writable property is not written, the stored RE runs and is refused",
            "{\"docAction\":\"CO\"}", "RE", NOT_WRITABLE, Boolean.TRUE, 400 },
        { "root RE with no resolvable tab fails closed",
            "{\"docAction\":\"RE\"}", NO_TAB, null, Boolean.TRUE, 500 },
        { "root RE on an unknown order id answers 404",
            "{\"docAction\":\"RE\"}", RECORD_NOT_FOUND, null, Boolean.TRUE, 404 },
        { "root CO on an unknown order id answers 404, not the router",
            "{\"docAction\":\"CO\"}", RECORD_NOT_FOUND, null, Boolean.TRUE, 404 },
        { "root RE on a record whose entity has no DocAction fails closed",
            "{\"docAction\":\"RE\"}", NO_DOC_ACTION_PROPERTY, null, Boolean.TRUE, 500 },
        { "empty body on a record with nothing stored falls through",
            "{}", null, null, Boolean.TRUE, null },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      JSONObject body = new JSONObject((String) c[1]);
      String stored = (String) c[2];
      String propertyCheck = (String) c[3];
      Boolean linked = (Boolean) c[4];
      Integer expectedStatus = (Integer) c[5];
      boolean noTab = NO_TAB.equals(stored);

      NeoCloneRecordHandler router = mock(NeoCloneRecordHandler.class);
      SalesOrderHeaderHandler handler = handlerWithMockClone(router);
      Tab tab = mock(Tab.class);
      when(tab.getId()).thenReturn(tabId);
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("header")
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("DocAction").recordId(orderId).adTab(noTab ? null : tab)
          .requestBody(body)
          .build();

      OBDal dal = mock(OBDal.class);
      if (STORED_READ_FAILS.equals(stored)) {
        when(dal.get(Tab.class, tabId)).thenThrow(new IllegalStateException("DAL down"));
      } else if (!noTab) {
        Table table = mock(Table.class);
        when(table.getName()).thenReturn(entityName);
        when(tab.getTable()).thenReturn(table);
        Property property = mock(Property.class);
        String requested = body.optString("docAction", null);
        if (NOT_IN_LIST.equals(propertyCheck)) {
          doThrow(new ValidationException()).when(property).checkIsValidValue(requested);
        } else if (NOT_WRITABLE.equals(propertyCheck)) {
          doThrow(new ValidationException()).when(property).checkIsWritable();
        }
        Entity entity = mock(Entity.class);
        when(entity.hasProperty("documentAction"))
            .thenReturn(!NO_DOC_ACTION_PROPERTY.equals(stored));
        when(entity.getProperty("documentAction")).thenReturn(property);
        BaseOBObject order = mock(BaseOBObject.class);
        when(order.getEntity()).thenReturn(entity);
        when(order.get("documentAction")).thenReturn(stored);
        when(dal.get(Tab.class, tabId)).thenReturn(tab);
        when(dal.get(entityName, orderId))
            .thenReturn(RECORD_NOT_FOUND.equals(stored) ? null : order);
      }
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      if (linked == null) {
        when(ps.executeQuery()).thenThrow(new SQLException("DB down"));
      } else {
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(linked);
      }

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class);
          MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        msgMock.when(() -> OBMessageUtils.messageBD(anyString())).thenReturn("refused");

        NeoResponse result = handler.handle(ctx);

        if (expectedStatus == null) {
          assertNull(label, result);
          verify(router).handle(ctx);
        } else {
          assertNotNull(label, result);
          assertEquals(label, expectedStatus.intValue(), result.getHttpStatus());
          if (expectedStatus == 404) {
            assertEquals(label, NeoActionRecordGuard.MSG_RECORD_NOT_FOUND,
                result.getBody().getJSONObject("error").getString("message"));
          }
          verify(router, never()).handle(any());
        }
      }
    }
  }

  // ── ETP-5238: paymentMethod SELECTOR is independent of Financial Account ────

  /**
   * A SELECTOR context wired with an AD Tab/Window pair whose {@code isSalesTransaction()} is
   * {@code true} — the sales-order window declares {@link
   * com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport.DirectionFallback#WINDOW},
   * so this is what the handler resolves direction from once the {@code IsSOTrx} request param
   * is absent.
   */
  private static NeoContext salesWindowSelectorCtx() {
    Window window = mock(Window.class);
    when(window.isSalesTransaction()).thenReturn(true);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return NeoContext.builder()
        .specName("sales-order").entityName("header").httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR).fieldName("paymentMethod")
        .adTab(tab).build();
  }

  private void stubPaymentMethodSelectorRequest(MockedStatic<RequestContext> reqCtx,
      Map<String, String> params) {
    RequestContext requestContext = mock(RequestContext.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    reqCtx.when(RequestContext::get).thenReturn(requestContext);
    when(requestContext.getRequest()).thenReturn(request);
    when(request.getParameter(anyString())).thenReturn(null);

    Map<String, String[]> parameterMap = new HashMap<>();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      parameterMap.put(entry.getKey(), new String[] { entry.getValue() });
    }
    when(request.getParameterMap()).thenReturn(parameterMap);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Criterion> stubPaymentMethodCriteria(MockedStatic<OBDal> obDal,
      List<FIN_PaymentMethod> rows) {
    OBDal obDalMock = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalMock);
    OBCriteria<FIN_PaymentMethod> criteria = mock(OBCriteria.class);
    when(obDalMock.createCriteria(FIN_PaymentMethod.class)).thenReturn(criteria);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(criteria.add(captor.capture())).thenReturn(criteria);
    when(criteria.addOrderBy(any(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.setFirstResult(anyInt())).thenReturn(criteria);
    when(criteria.count()).thenReturn(rows.size());
    when(criteria.list()).thenReturn(rows);
    return captor;
  }

  /** Renders every captured restriction (read only AFTER invoking the method under test). */
  private List<String> renderedCriteria(ArgumentCaptor<Criterion> captor) {
    return captor.getAllValues().stream().map(Criterion::toString)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * The paymentMethod SELECTOR request must be served by
   * {@code com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport}, short-circuiting
   * before any dispatch to the injected ACTION delegates, and — since this window declares the
   * {@code WINDOW} direction fallback — the criteria built when {@code IsSOTrx} is absent must be
   * against {@code payinAllow}, never {@code payoutAllow}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorIndependentOfFinancialAccount() throws Exception {
    NeoContext ctx = salesWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      stubPaymentMethodSelectorRequest(reqCtx, Collections.emptyMap());

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new SalesOrderHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payinAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
      assertFalse("must not filter on payoutAllow for a sales document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
    }
  }

  /**
   * Same window, but with the request explicitly sending {@code IsSOTrx=Y} (the normal case in
   * production) — must resolve identically to the absent-param case above.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorBuildsPayinCriteriaWhenIsSOTrxIsY() throws Exception {
    NeoContext ctx = salesWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "Y");
      stubPaymentMethodSelectorRequest(reqCtx, params);

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = new SalesOrderHeaderHandler().handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payinAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
      assertFalse("must not filter on payoutAllow for a sales document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
    }
  }

  /**
   * Non-selector requests are completely unaffected by the new SELECTOR branch (no-regression).
   */
  @Test
  public void testHandleStillReturnsNullForNonSelectorRequestAfterPaymentMethodWiring() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new SalesOrderHeaderHandler().handle(ctx));
  }

  // ── declared action contracts (ETP-5709) ──────────────────────────────────

  @Test
  public void actionContracts_declaresShipmentInvoiceAndClone() {
    Map<String, com.etendoerp.go.schemaforge.util.NeoActionContract> contracts = new SalesOrderHeaderHandler().actionContracts();

    assertEquals(List.of("createShipment", "createDraftInvoice", "cloneRecord"),
        List.copyOf(contracts.keySet()));
    for (com.etendoerp.go.schemaforge.util.NeoActionContract c : contracts.values()) {
      assertEquals("POST", c.getHttpMethod());
    }
    assertTrue(contracts.get("createShipment").getParams().isEmpty());
    assertTrue(contracts.get("cloneRecord").getParams().isEmpty());
    assertTrue(contracts.get("createShipment").getDescription().contains("DRAFT"));
    String invoice = contracts.get("createDraftInvoice").getDescription();
    assertTrue("must warn that the invoice is completed despite the name",
        invoice.contains("COMPLETES") && invoice.contains("despite the name"));
    assertEquals(List.of("lines"), contracts.get("createDraftInvoice").getParams().stream()
        .map(com.etendoerp.go.schemaforge.util.NeoActionContract.Param::getName).collect(java.util.stream.Collectors.toList()));
  }

  @Test
  public void actionContracts_acceptEveryBodyTheSpaSends() throws Exception {
    Map<String, com.etendoerp.go.schemaforge.util.NeoActionContract> contracts = new SalesOrderHeaderHandler().actionContracts();

    for (String action : contracts.keySet()) {
      assertNull(action, com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts, action, new JSONObject()));
    }
    assertNull(com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts, "createDraftInvoice", new JSONObject().put("lines",
        new JSONArray().put(new JSONObject().put("orderLineId", "L1").put("quantity", "2")))));
    assertEquals(com.etendoerp.go.schemaforge.util.NeoActionContract.SC_UNPROCESSABLE, com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
        "createShipment", new JSONObject().put("warehouseId", "W1")).getHttpStatus());
  }
}
