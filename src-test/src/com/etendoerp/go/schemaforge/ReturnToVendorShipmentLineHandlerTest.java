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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

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

  // ── handle() — storageBin default injection (ETP-4863) ───────────────────

  /**
   * Reproduces ETP-4863: confirming a Return to Vendor Shipment (Devolución de Compra) with
   * the header on the PRINCIPAL warehouse must never leave the created line's {@code
   * storageBin} pointing at a different (e.g. stale session-cached "secondary") warehouse.
   * Unlike {@link GoodsReceiptLineHandler} and {@link GoodsShipmentLineHandler}, this handler
   * implements {@link NeoHandler} directly (does not extend {@code AbstractInOutLineHandler}),
   * so it never inherited the ETP-4671 locator-defaulting fix. handle() must default {@code
   * storageBin} to the header warehouse's own default locator on POST when missing, exactly
   * like the other two M_InOutLine-based line handlers.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostInjectsWarehouseDefaultLocatorWhenStorageBinMissing() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "return-1").put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("return-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(HANDLER.handle(ctx));
      assertEquals("A return-to-vendor-shipment line's storageBin must always resolve to the "
          + "header warehouse's own default locator, never to a stale session-cached warehouse",
          "loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  /**
   * handle() POST must not override an explicit storageBin already supplied on the request.
   */
  @Test
  public void testHandlePostDoesNotOverrideExplicitStorageBin() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "return-1")
        .put("storageBin", "loc-explicit");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    assertNull(HANDLER.handle(ctx));
    assertEquals("loc-explicit", body.getString("storageBin"));
  }

  /**
   * Interaction edge case (QA): this handler is the only one of the 4 M_InOutLine-based line
   * handlers whose {@code handle()} runs BOTH the new ETP-4863 locator guard AND the
   * pre-existing movementQuantity-negation logic on the same POST. Neither the "inject locator"
   * test nor the "negate qty" tests above exercise a realistic create payload carrying both a
   * missing {@code storageBin} and a positive {@code movementQuantity} at once — this proves
   * the two behaviors compose correctly (both fire, neither short-circuits the other) instead
   * of assuming it from two tests that never overlap on the same request body.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostInjectsLocatorAndNegatesQuantityTogether() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "return-1")
        .put("product", "prod-1").put("movementQuantity", 6.0);
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("return-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(HANDLER.handle(ctx));

      assertEquals("loc-default-wh-principal", body.getString("storageBin"));
      assertEquals(-6.0, body.getDouble("movementQuantity"), 0.0001);
      assertTrue("product must be kept for POST (only PUT/PATCH strip it)",
          body.has("product"));
    }
  }
}
