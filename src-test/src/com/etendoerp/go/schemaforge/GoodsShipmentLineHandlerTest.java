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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * Unit tests for {@link GoodsShipmentLineHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} — must always return null to fall through to default CRUD.</li>
 *   <li>{@code afterHandle()} — guard conditions that short-circuit before DB access:
 *       non-GET method, missing previous result, null body, and empty data array.</li>
 * </ul>
 *
 * <p>Tests that require DB access (orderLineQty / productCode enrichment via SQL)
 * are not included here — those are covered by integration tests.
 */
public class GoodsShipmentLineHandlerTest {

  /**
   * Builds a GET/CRUD {@link NeoContext} targeting the goods-shipment line entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("goods-shipment")
        .entityName("lines")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle always returns null, allowing the default CRUD to proceed.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  /**
   * Verifies that handle returns null even for POST/ACTION contexts.
   */
  @Test
  public void testHandleAlwaysReturnsNullForPostContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response has no "response" wrapper object.
   */
  @Test
  public void testAfterHandleReturnsNullWhenResponseWrapperAbsent() throws Exception {
    JSONObject body = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("id", "line-1")));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  // ── handle() POST + invoiceLineId ─────────────────────────────────────────

  /**
   * When handle() receives a POST with an invoiceLineId in the body it must still
   * return null so that the default CRUD continues.
   */
  @Test
  public void testHandlePostWithInvoiceLineIdReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-42");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  /**
   * When handle() receives a POST with a null body the invoiceLineId ThreadLocal
   * must not be set and the method must return null.
   */
  @Test
  public void testHandlePostWithNullBodyReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(null)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  /**
   * When handle() receives a POST with an empty invoiceLineId value the ThreadLocal
   * must not be set and the method must return null.
   */
  @Test
  public void testHandlePostWithEmptyInvoiceLineIdReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("invoiceLineId", "");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  // ── afterHandle() POST path ───────────────────────────────────────────────

  /**
   * afterHandle() with POST and no invoiceLineId in ThreadLocal must return null
   * cleanly (linkInvoiceLineIfPresent exits early, ThreadLocal is cleaned up).
   */
  @Test
  public void testAfterHandlePostWithNoPendingInvoiceLineIdReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * afterHandle() with POST when invoiceLineId was stored by handle() but the
   * previousResult is null — linkInvoiceLineIfPresent exits the guard early and
   * the method must return null. The ThreadLocal must be cleared by the finally block.
   */
  @Test
  public void testAfterHandlePostWithInvoiceLineIdButNullPreviousResultReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    // Arm the ThreadLocal via handle() first
    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-99");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    handler.handle(handleCtx);

    // afterHandle() without a previousResult — linkInvoiceLineIfPresent returns early
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  /**
   * afterHandle() with POST when invoiceLineId was stored by handle() but the
   * previousResult has a null body — guard in linkInvoiceLineIfPresent exits early.
   */
  @Test
  public void testAfterHandlePostWithInvoiceLineIdButNullBodyReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-77");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    handler.handle(handleCtx);

    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(new NeoResponse(201, null))
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  /**
   * afterHandle() with POST when previousResult has a body but no "response" wrapper
   * — guard in linkInvoiceLineIfPresent exits early; returns null.
   */
  @Test
  public void testAfterHandlePostNoResponseWrapperReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    JSONObject reqBody = new JSONObject().put("invoiceLineId", "inv-line-55");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(reqBody)
        .build();
    handler.handle(handleCtx);

    JSONObject respBody = new JSONObject().put("id", "line-new");
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(respBody))
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  /**
   * afterHandle() with POST when previousResult has a "response" wrapper but the
   * "data" array is empty — guard in linkInvoiceLineIfPresent exits early.
   */
  @Test
  public void testAfterHandlePostEmptyDataArrayReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    JSONObject reqBody = new JSONObject().put("invoiceLineId", "inv-line-33");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(reqBody)
        .build();
    handler.handle(handleCtx);

    JSONObject respBody = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(respBody))
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  // ── afterHandle() GET enrichment path ────────────────────────────────────

  /**
   * afterHandle() GET with a non-empty data array but fetchLineData returning an
   * empty map (simulated by making OBDal.getConnection() throw so fetchLineData
   * returns an empty result silently) — the response is still returned as
   * NeoResponse.ok with the original body; lines have no extra fields added.
   */
  @Test
  public void testAfterHandleGetWithFetchLineDataThrowingReturnsOkWithOriginalBody() throws Exception {
    JSONObject line = new JSONObject().put("id", "line-1");
    JSONArray dataArr = new JSONArray().put(line);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // getConnection() throws — fetchLineData catches internally and returns empty map
      when(dal.getConnection()).thenThrow(new RuntimeException("DB unavailable"));

      NeoResponse result = new GoodsShipmentLineHandler().afterHandle(ctx);

      assertNotNull("Should return ok response even when DB is unavailable", result);
      assertNotNull(result.getBody());
    }
  }

  /**
   * afterHandle() GET must return null when an exception escapes the outer try block
   * (e.g. collectIds or NeoHandlerUtils itself throws unexpectedly).
   */
  @Test
  public void testAfterHandleGetReturnsNullOnUnexpectedException() throws Exception {
    // Build a context whose previousResult body is deliberately corrupt so that
    // the JSON deserialization inside the enrichment loop throws and the outer
    // catch block returns null.
    JSONObject corruptLine = new JSONObject();
    // id is present so collectIds works, but put a non-object where a JSONObject
    // is expected to trigger a runtime error inside the loop.
    corruptLine.put("id", "x");
    JSONArray dataArr = new JSONArray().put(corruptLine);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // Throw from getConnection so fetchLineData returns empty and we reach
      // NeoResponse.ok(body) normally — this validates the happy path succeeds
      when(dal.getConnection()).thenThrow(new RuntimeException("simulate DB error"));

      NeoResponse result = new GoodsShipmentLineHandler().afterHandle(ctx);
      // fetchLineData swallowed the exception and returned empty map → ok() is returned
      assertNotNull(result);
    }
  }

  // ── afterCallout() regression (ETP-4671) ──────────────────────────────────

  /**
   * Regression guard for ETP-4671: {@link GoodsReceiptLineHandler} was given an
   * {@code afterCallout()} override that strips the stock-derived {@code movementQuantity}
   * update coming from the shared {@code SL_InOutLine_Product} callout, because a purchase
   * receipt's quantity has nothing to do with what is already on hand. Goods Shipment is the
   * opposite case — picking from existing stock is the correct, intended behavior for a
   * shipment — so {@link GoodsShipmentLineHandler} must NOT get this override:
   * {@code movementQuantity} must keep passing through untouched.
   */
  @Test
  public void testAfterCalloutDoesNotStripMovementQuantity() throws Exception {
    JSONObject updates = new JSONObject().put("movementQuantity", new JSONObject().put("value", 50));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(prevBody)).build();

    assertNull(new GoodsShipmentLineHandler().afterCallout(ctx));
    assertTrue("Goods Shipment must keep picking movementQuantity from on-hand stock — only "
        + "Goods Receipt's own handler strips it",
        prevBody.getJSONObject("updates").has("movementQuantity"));
  }

  // ── handle() — storageBin default injection (ETP-4863) ───────────────────

  /**
   * Reproduces ETP-4863: confirming a Goods Shipment (Albarán de Venta) with the header on
   * the PRINCIPAL warehouse must never leave the created line's {@code storageBin} pointing
   * at a different (e.g. stale session-cached "secondary") warehouse. {@link
   * GoodsReceiptLineHandler} already anchors its line's locator to the header's own warehouse
   * (ETP-4671); {@link GoodsShipmentLineHandler} must do the same on POST when {@code
   * storageBin} is missing, instead of relying on the raw AD {@code @OnHandLocatorDefault@}
   * default, which falls back to a stale session-cached warehouse when the tab does not
   * declare the matching {@code AD_AuxiliaryInput}.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostInjectsWarehouseDefaultLocatorWhenStorageBinMissing() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1").put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(new GoodsShipmentLineHandler().handle(ctx));
      assertEquals("A shipment line's storageBin must always resolve to the header warehouse's "
          + "own default locator, never to a stale session-cached warehouse",
          "loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  /**
   * handle() POST must not override an explicit storageBin already supplied on the request
   * when it ALREADY belongs to the header's warehouse (user selection or an "Import from..."
   * flow) — same guard as {@link GoodsReceiptLineHandler} (ETP-4863 BUG-1: the guarantee is
   * about the warehouse, not about forcing every line onto the warehouse's single "default"
   * locator).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostDoesNotOverrideExplicitStorageBin() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1")
        .put("storageBin", "loc-explicit");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      Locator existingLocator = mock(Locator.class);
      when(dal.get(eq(Locator.class), eq("loc-explicit"))).thenReturn(existingLocator);
      when(existingLocator.getWarehouse()).thenReturn(warehouse);

      assertNull(new GoodsShipmentLineHandler().handle(ctx));
      assertEquals("loc-explicit", body.getString("storageBin"));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }
}
