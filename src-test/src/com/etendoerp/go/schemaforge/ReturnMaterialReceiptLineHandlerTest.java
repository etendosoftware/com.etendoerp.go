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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link ReturnMaterialReceiptLineHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — always returns null (pass-through pre-hook).</li>
 *   <li>{@code afterHandle()} — guard conditions (non-GET, no previous result, empty data),
 *       and the SQL enrichment paths: both fields, null productCode, null qty, empty lineIds,
 *       and SQL exception resilience.</li>
 * </ul>
 *
 * <p>Tests that require a live Etendo DB are covered by integration tests.
 */
public class ReturnMaterialReceiptLineHandlerTest {

  private static final ReturnMaterialReceiptLineHandler HANDLER =
      new ReturnMaterialReceiptLineHandler();

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Builds a GET/CRUD context targeting the return-material-receipt lines entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("return-material-receipt")
        .entityName("lines")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Builds a minimal response body wrapping a single line record with the given id.
   */
  private static JSONObject lineBody(String lineId) throws Exception {
    JSONObject line = new JSONObject().put("id", lineId).put("movementQty", 3.0);
    return new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(line)));
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle always returns null, delegating to default CRUD.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * Verifies that handle returns null even for POST/ACTION contexts.
   */
  @Test
  public void testHandleReturnsNullForPostContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.handle(ctx));
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response has no "response" wrapper object.
   */
  @Test
  public void testAfterHandleReturnsNullWhenResponseWrapperAbsent() throws Exception {
    JSONObject body = new JSONObject().put("data",
        new JSONArray().put(new JSONObject().put("id", "line-1")));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── afterHandle() — SQL enrichment paths ──────────────────────────────────

  /**
   * Verifies that both orderQuantity and productCode are injected when the SQL row
   * contains non-null values for both.
   */
  @Test
  public void testAfterHandleInjectsBothQtyAndProductCodeWhenBothPresent() throws Exception {
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
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5.00"));
      when(rs.getString(3)).thenReturn("PROD-CODE-A");

      JSONObject body = lineBody("line-1");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(new BigDecimal("5.00"), rec.get("orderQuantity"));
      assertEquals("PROD-CODE-A", rec.getString("productCode"));
    }
  }

  /**
   * Verifies that only orderQuantity is injected (no productCode key) when the SQL row
   * returns a null product code — meaning the product was not found in M_Product.
   */
  @Test
  public void testAfterHandleInjectsOnlyQtyWhenProductCodeIsNull() throws Exception {
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
      when(rs.getString(1)).thenReturn("line-2");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("2.00"));
      when(rs.getString(3)).thenReturn(null); // null productCode

      JSONObject body = lineBody("line-2");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(rec.has("orderQuantity"));
      assertFalse(rec.has("productCode"));
    }
  }

  /**
   * Verifies that only productCode is injected (no orderQuantity key) when the SQL row
   * returns a null effective_qty — meaning no original line was found.
   */
  @Test
  public void testAfterHandleInjectsOnlyProductCodeWhenQtyIsNull() throws Exception {
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
      when(rs.getString(1)).thenReturn("line-3");
      when(rs.getBigDecimal(2)).thenReturn(null); // null qty
      when(rs.getString(3)).thenReturn("PROD-CODE-B");

      JSONObject body = lineBody("line-3");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(rec.has("orderQuantity"));
      assertEquals("PROD-CODE-B", rec.getString("productCode"));
    }
  }

  /**
   * Verifies that when the data array contains records with no id, the SQL is still called
   * (ids list is empty) and afterHandle returns a valid unmodified response.
   */
  @Test
  public void testAfterHandlePassesThroughWhenAllLinesHaveNoId() throws Exception {
    JSONObject lineNoId = new JSONObject().put("movementQty", 1.0);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(lineNoId)));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed — fetchLineData returns empty map without hitting DB
    NeoResponse result = HANDLER.afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    JSONObject rec = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertFalse(rec.has("orderQuantity"));
    assertFalse(rec.has("productCode"));
  }

  /**
   * Verifies that a SQL exception inside fetchLineData is caught silently —
   * afterHandle returns a valid response without throwing.
   */
  @Test
  public void testAfterHandleSqlExceptionCaughtSilently() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("connection lost"));

      JSONObject body = lineBody("line-err");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }
}
