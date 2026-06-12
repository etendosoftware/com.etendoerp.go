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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link ReturnToVendorShipmentLineHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — GET/DELETE pass-through, PUT/PATCH/POST quantity negation, product
 *       field removal rules, and null-body guard.</li>
 *   <li>{@code afterHandle()} — guard conditions, movementQuantity sign flip, and the SQL
 *       enrichment paths for orderQuantity and productCode injection.</li>
 * </ul>
 */
public class ReturnToVendorShipmentLineHandlerTest {

  private static final ReturnToVendorShipmentLineHandler HANDLER =
      new ReturnToVendorShipmentLineHandler();

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("return-to-vendor-shipment")
        .entityName("lines")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static JSONObject lineBody(String lineId, double movementQty) throws Exception {
    JSONObject line = new JSONObject()
        .put("id", lineId)
        .put("movementQuantity", movementQty);
    return new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(line)));
  }

  // ── handle() — non-write methods pass through ─────────────────────────────

  /**
   * GET requests are pass-through: handle returns null and does not modify any body.
   */
  @Test
  public void testHandleGetReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * DELETE requests are pass-through: handle returns null.
   */
  @Test
  public void testHandleDeleteReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  // ── handle() — PUT negation ────────────────────────────────────────────────

  /**
   * PUT with a positive movementQuantity: the value is negated to negative.
   */
  @Test
  public void testHandlePutNegatesPositiveMovementQuantity() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", 5.0);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    NeoResponse result = HANDLER.handle(ctx);

    assertNull(result);
    assertEquals(-5.0, body.getDouble("movementQuantity"), 0.0001);
  }

  /**
   * PUT with a positive movementQuantity: the "product" field is removed from the body
   * so the SL_InOutLine_Product callout cannot overwrite the quantity.
   */
  @Test
  public void testHandlePutRemovesProductField() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementQuantity", 3.0)
        .put("product", "prod-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertFalse("product field should be removed for PUT", body.has("product"));
  }

  // ── handle() — POST keeps product ─────────────────────────────────────────

  /**
   * POST with a positive movementQuantity: the value is negated but the "product" field
   * is kept (only PUT/PATCH strip it).
   */
  @Test
  public void testHandlePostNegatesQtyButKeepsProduct() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementQuantity", 7.0)
        .put("product", "prod-2");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertEquals(-7.0, body.getDouble("movementQuantity"), 0.0001);
    assertTrue("product field must be kept for POST", body.has("product"));
  }

  // ── handle() — PATCH negation and product removal ─────────────────────────

  /**
   * PATCH with a positive movementQuantity: the value is negated and "product" is removed.
   */
  @Test
  public void testHandlePatchNegatesQtyAndRemovesProduct() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementQuantity", 2.0)
        .put("product", "prod-3");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertEquals(-2.0, body.getDouble("movementQuantity"), 0.0001);
    assertFalse("product field should be removed for PATCH", body.has("product"));
  }

  // ── handle() — already-negative and missing field ─────────────────────────

  /**
   * PUT with an already-negative movementQuantity: the value is NOT negated again.
   */
  @Test
  public void testHandlePutDoesNotNegateAlreadyNegativeQty() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", -4.0);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertEquals(-4.0, body.getDouble("movementQuantity"), 0.0001);
  }

  /**
   * PUT with no movementQuantity field in the body: returns null and leaves product untouched.
   */
  @Test
  public void testHandlePutWithNoMovementQuantityFieldReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-4");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    NeoResponse result = HANDLER.handle(ctx);

    assertNull(result);
    assertTrue("product field must remain when movementQuantity is absent", body.has("product"));
  }

  /**
   * PUT with a null request body: returns null without throwing a NullPointerException.
   */
  @Test
  public void testHandlePutWithNullBodyReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(null).build();

    NeoResponse result = HANDLER.handle(ctx);

    assertNull(result);
  }

  // ── afterHandle() — guard conditions ──────────────────────────────────────

  /**
   * Non-GET endpoint: afterHandle returns null immediately (no enrichment).
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * GET without a previous result set: afterHandle returns null.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── afterHandle() — movementQuantity sign flip ────────────────────────────

  /**
   * GET response with a record whose movementQuantity is negative (as stored in the DB):
   * afterHandle flips it to positive so the frontend sees an absolute value.
   * OBDal is mocked so the SQL call succeeds with empty results (allowing execution to reach
   * the sign-flip loop after fetchLineData).
   */
  @Test
  public void testAfterHandleFlipsNegativeMovementQuantityToPositive() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no enrichment data needed

      JSONObject body = lineBody("line-1", -3.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(3.0, rec.getDouble("movementQuantity"), 0.0001);
    }
  }

  /**
   * GET response with a record whose movementQuantity is already positive:
   * afterHandle leaves it unchanged.
   * OBDal is mocked so the SQL call succeeds with empty results.
   */
  @Test
  public void testAfterHandleDoesNotFlipPositiveMovementQuantity() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = lineBody("line-2", 5.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(5.0, rec.getDouble("movementQuantity"), 0.0001);
    }
  }

  // ── afterHandle() — SQL enrichment: DB returns rows ───────────────────────

  /**
   * GET with a line id, DB returns orderQuantity and productCode:
   * both are injected into the record.
   */
  @Test
  public void testAfterHandleInjectsOrderQtyAndProductCodeFromDb() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-3");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("10.00"));
      when(rs.getString(3)).thenReturn("VND-PROD-001");

      JSONObject body = lineBody("line-3", -10.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(new BigDecimal("10.00"), rec.get("orderQuantity"));
      assertEquals("VND-PROD-001", rec.getString("productCode"));
    }
  }

  /**
   * GET with a line id, DB returns no rows for that id:
   * neither orderQuantity nor productCode is injected.
   */
  @Test
  public void testAfterHandleDoesNotInjectWhenDbReturnsNoRows() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = lineBody("line-4", -2.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse("orderQuantity should not be present", rec.has("orderQuantity"));
      assertFalse("productCode should not be present", rec.has("productCode"));
    }
  }
}
