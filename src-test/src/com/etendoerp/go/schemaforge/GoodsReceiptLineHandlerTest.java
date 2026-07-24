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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

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
}
