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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * Unit tests for {@link GoodsReceiptLineHandler} (ETP-4671).
 *
 * <h3>Bug 2 — stock-derived {@code movementQuantity} prefill</h3>
 * The classic {@code SL_InOutLine_Product} callout (shared by every {@code M_InOutLine}-based
 * window) echoes the product selector's on-hand-stock auxiliary value back as the new
 * {@code movementQuantity} whenever a line is added manually (not imported from an order). That
 * is the right default for a Goods Shipment (pick from what's in stock) but wrong for a Goods
 * Receipt, where the quantity being received has nothing to do with what is already on hand.
 * {@code afterCallout()} must strip that stock-derived value so the frontend keeps the row's own
 * default instead of silently overwriting it.
 */
public class GoodsReceiptLineHandlerTest {

  private static final GoodsReceiptLineHandler HANDLER = new GoodsReceiptLineHandler();

  // ── afterCallout() guard clauses ──────────────────────────────────────────

  /**
   * afterCallout() must be a no-op when the endpoint is not CALLOUT.
   */
  @Test
  public void testAfterCalloutReturnsNullForNonCalloutEndpoint() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must return null without throwing when there is no previous callout result.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenPreviousResultIsNull() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must return null without throwing when the previous result body is null.
   */
  @Test
  public void testAfterCalloutReturnsNullWhenPreviousResultBodyIsNull() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(new NeoResponse(200, null)).build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  /**
   * afterCallout() must leave the response untouched when the callout returned no updates at all
   * (e.g. a callout on a field other than product).
   */
  @Test
  public void testAfterCalloutIsNoOpWhenUpdatesAbsent() throws Exception {
    JSONObject prevBody = new JSONObject().put("combos", new JSONObject());
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(prevBody)).build();
    assertNull(HANDLER.afterCallout(ctx));
    assertFalse(prevBody.has("updates"));
  }

  /**
   * afterCallout() must leave other fields (e.g. a resolved UOM) untouched when
   * movementQuantity is not part of the callout response.
   */
  @Test
  public void testAfterCalloutLeavesOtherUpdatesUntouchedWhenMovementQuantityAbsent() throws Exception {
    JSONObject updates = new JSONObject().put("uOM", new JSONObject().put("value", "Unit"));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(prevBody)).build();

    assertNull(HANDLER.afterCallout(ctx));
    assertTrue("Unrelated fields must survive untouched", updates.has("uOM"));
  }

  // ── afterCallout() — the actual bug ───────────────────────────────────────

  /**
   * Reproduces ETP-4671 bug 2: on a product-change callout, the classic
   * {@code SL_InOutLine_Product} callout echoes the product's on-hand stock back as
   * {@code movementQuantity} (see the callout's own source comment: "modifies the quantity
   * field with the quantity in the warehouse" whenever the line is not created from an order).
   * That default is correct for a shipment but wrong for a receipt — the quantity being
   * received is unrelated to what is already in stock. {@code afterCallout()} must strip it
   * so the frontend row keeps its own value instead of silently jumping to the on-hand qty.
   */
  @Test
  public void testAfterCalloutStripsStockDerivedMovementQuantity() throws Exception {
    // Simulates the exact shape NeoCalloutService.transformResponse() produces after running
    // SL_InOutLine_Product for a product with 50 units on hand and no pending order import.
    JSONObject updates = new JSONObject()
        .put("movementQuantity", new JSONObject().put("value", 50))
        .put("uOM", new JSONObject().put("value", "Unit"));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(prevBody)).build();

    assertNull(HANDLER.afterCallout(ctx));

    assertFalse("movementQuantity must be stripped — a purchase receipt must not silently "
        + "prefill the received quantity with the product's current on-hand stock",
        prevBody.getJSONObject("updates").has("movementQuantity"));
    assertTrue("Unrelated fields (e.g. uOM) must be preserved",
        prevBody.getJSONObject("updates").has("uOM"));
  }

  // ── handle() — storageBin default injection (Bug 1a) ──────────────────────

  /**
   * handle() POST with a blank request body must be a no-op (no DB access, no exception).
   */
  @Test
  public void testHandlePostWithNullBodyIsNoOp() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() POST must not touch storageBin when the create request already supplies one
   * that ALREADY belongs to the receiving warehouse (explicit user selection, or a line
   * imported from a Purchase Order) — a deliberate bin choice, not a gap (ETP-4863 BUG-1: the
   * header-warehouse guarantee is about the warehouse, not about forcing every line onto the
   * warehouse's single "default" locator).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostDoesNotOverrideExplicitStorageBin() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "receipt-1").put("storageBin", "loc-explicit");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      Locator existingLocator = mock(Locator.class);
      when(dal.get(eq(Locator.class), eq("loc-explicit"))).thenReturn(existingLocator);
      when(existingLocator.getWarehouse()).thenReturn(warehouse);

      assertNull(HANDLER.handle(ctx));
      assertEquals("loc-explicit", body.getString("storageBin"));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  /**
   * handle() POST with an empty parentId must not inject a locator (nothing to resolve the
   * warehouse from).
   */
  @Test
  public void testHandlePostWithEmptyParentIdDoesNotInjectLocator() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    assertNull(HANDLER.handle(ctx));
    assertFalse(body.has("storageBin"));
  }

  /**
   * handle() POST must not inject a locator when the parent Goods Receipt header cannot be
   * found.
   */
  @Test
  public void testHandlePostDoesNotInjectLocatorWhenHeaderNotFound() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "receipt-missing");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-missing"))).thenReturn(null);

      assertNull(HANDLER.handle(ctx));
      assertFalse(body.has("storageBin"));
    }
  }

  /**
   * handle() POST must not inject a locator when the receipt header has no warehouse set.
   */
  @Test
  public void testHandlePostDoesNotInjectLocatorWhenWarehouseIsNull() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "receipt-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(null);

      assertNull(HANDLER.handle(ctx));
      assertFalse(body.has("storageBin"));
    }
  }

  /**
   * handle() POST must not inject a locator when the warehouse has no default active locator
   * configured (the "no locator found" fallback must leave storageBin unset rather than throw).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostDoesNotInjectLocatorWhenNoneConfiguredForWarehouse() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "receipt-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      assertNull(HANDLER.handle(ctx));
      assertFalse(body.has("storageBin"));
    }
  }

  /**
   * Reproduces ETP-4671 bug 1a: a manually-added receipt line for a product with NO prior stock
   * never gets a {@code storageBin} (the raw AD default, "the locator where this product
   * already has stock", resolves to nothing) — so completion later fails with
   * {@code InoutLineWithoutLocator}. handle() must default storageBin to the receiving
   * warehouse's own default active locator, exactly like {@link InventoryLineHandler} already
   * does for Physical Inventory lines.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostInjectsWarehouseDefaultLocatorWhenStorageBinMissing() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "receipt-1").put("product", "prod-unstocked");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      when(locator.getId()).thenReturn("loc-default-wh1");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(HANDLER.handle(ctx));
      assertEquals("Unstocked-product receipt lines must still get a valid warehouse locator, "
          + "or completion fails with InoutLineWithoutLocator regardless of prior stock",
          "loc-default-wh1", body.getString("storageBin"));
    }
  }

  /**
   * Reproduces the LIVE production crash found when re-verifying this fix (ETP-4671): the
   * frontend's generated {@code addLineFields.hidden} merge (DetailView.jsx) copies the raw,
   * UNRESOLVED AD_Column default literal ({@code "@OnHandLocatorDefault@"}) straight into the
   * create payload for any hidden field the row never touched — confirmed via the live Tomcat
   * log: {@code [REMAP] body recibido: {..., "storageBin":"@OnHandLocatorDefault@", ...}}.
   * The original guard ({@code StringUtils.isNotBlank(...)}) treated that literal as an
   * "already supplied" value and skipped injection entirely, so the line was persisted with
   * {@code M_Locator_ID='@OnHandLocatorDefault@'} — not a real id — and
   * {@code DefaultJsonDataService} rejected it with "New object Locator(null) ... refered to
   * but not present in the import set". handle() must recognize this unresolved-token shape
   * and override it exactly as if the field were absent.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostOverridesUnresolvedTokenStorageBin() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "receipt-1").put("product", "prod-unstocked")
        .put("storageBin", "@OnHandLocatorDefault@");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      when(locator.getId()).thenReturn("loc-default-wh1");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(HANDLER.handle(ctx));
      assertEquals("An unresolved '@Token@' literal reaching storageBin is not a real user "
          + "value — it must be overridden with the warehouse's default locator, not treated "
          + "as already supplied",
          "loc-default-wh1", body.getString("storageBin"));
    }
  }
}
