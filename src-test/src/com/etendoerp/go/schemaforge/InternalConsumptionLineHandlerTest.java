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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.InternalConsumptionLine;

/**
 * Unit tests for {@link InternalConsumptionLineHandler}.
 *
 * <p>Warehouse-name enrichment for locator FKs is handled generically for all windows by the
 * shared selector and CRUD pipelines ({@code NeoLocatorSelectorHelper} /
 * {@code NeoLocatorIdentifierHelper}) — those tests only pin the no-op contract for that part.
 *
 * <p>The remaining responsibility, covered below, is the write pre-hook added for ETP-4606: a
 * line cannot reference a Service-type {@code Product}.
 */
public class InternalConsumptionLineHandlerTest {

  private static final InternalConsumptionLineHandler HANDLER = new InternalConsumptionLineHandler();

  /**
   * handle() must always return null so the default CRUD path runs.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * handle() must return null even for CRUD endpoints.
   */
  @Test
  public void testHandleReturnsNullForCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * afterHandle() must always return null (no-op) even for the storage-bin selector, since
   * label enrichment now happens generically upstream.
   */
  @Test
  public void testAfterHandleIsNoOpForStorageBinSelector() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "loc-1").put("label", "Bin-A"));
    JSONObject body = new JSONObject().put("items", items);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("M_Locator_ID")
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * afterHandle() must return null for any other endpoint/field too.
   */
  @Test
  public void testAfterHandleIsNoOpForOtherEndpoints() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .fieldName("C_BPartner_ID")
        .build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── handle() — Service product rejection (ETP-4606) ───────────────────────────

  /**
   * handle() POST must reject with HTTP 400 when the product sent in the body is Service-type.
   */
  @Test
  public void testHandleRejectsServiceProductOnPost() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-service");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> messageUtilsMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Product product = mock(Product.class);
      when(product.getProductType()).thenReturn("S");
      when(dal.get(eq(Product.class), eq("prod-service"))).thenReturn(product);
      messageUtilsMock.when(() -> OBMessageUtils.messageBD("ETGO_ProductNotStockable"))
          .thenReturn("This product is of type Service and cannot be used in inventory movements.");

      NeoResponse response = HANDLER.handle(ctx);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      assertFalse(response.getBody().getJSONObject("error").getString("message").isEmpty());
    }
  }

  /**
   * handle() PATCH must reject with HTTP 400 when the body has no product field but the line
   * already persisted a Service-type product.
   */
  @Test
  public void testHandleRejectsServiceProductOnPatchWithPersistedProduct() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());
    when(ctx.getRecordId()).thenReturn("line-1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> messageUtilsMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InternalConsumptionLine line = mock(InternalConsumptionLine.class);
      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-service");
      when(product.getProductType()).thenReturn("S");
      when(line.getProduct()).thenReturn(product);
      when(dal.get(eq(InternalConsumptionLine.class), eq("line-1"))).thenReturn(line);
      when(dal.get(eq(Product.class), eq("prod-service"))).thenReturn(product);
      messageUtilsMock.when(() -> OBMessageUtils.messageBD("ETGO_ProductNotStockable"))
          .thenReturn("This product is of type Service and cannot be used in inventory movements.");

      NeoResponse response = HANDLER.handle(ctx);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }
  }

  /**
   * handle() must let a non-Service product through unchanged (default CRUD path runs).
   */
  @Test
  public void testHandleAllowsNonServiceProductToProceed() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-item");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Product product = mock(Product.class);
      when(product.getProductType()).thenReturn("I");
      when(dal.get(eq(Product.class), eq("prod-item"))).thenReturn(product);

      assertNull(HANDLER.handle(ctx));
    }
  }

  /**
   * handle() must be a no-op when the request body carries no product at all (e.g. only other
   * fields changed on PATCH) and the line has none persisted either.
   */
  @Test
  public void testHandleIsNoOpWhenNoProductCanBeResolved() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());
    when(ctx.getRecordId()).thenReturn("line-1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InternalConsumptionLine line = mock(InternalConsumptionLine.class);
      when(line.getProduct()).thenReturn(null);
      when(dal.get(eq(InternalConsumptionLine.class), eq("line-1"))).thenReturn(line);

      assertNull(HANDLER.handle(ctx));
    }
  }

  // ── afterCallout() — strip stock-derived movementQuantity (ETP-5445) ────────
  //
  // The strip is gated on the callout trigger: only a product selection
  // (SL_Internal_Consumption_Product) echoes the on-hand stock into movementQuantity. Any other
  // trigger (e.g. SL_Internal_Consumption_Conversion on orderUOM / quantityOrder) computes a
  // legitimate movementQuantity that must reach the client untouched.

  private static final String FIELD_MOVEMENT_QUANTITY = "movementQuantity";
  private static final String FIELD_UPDATES = "updates";
  private static final String FIELD_OTHER = "otherField";
  private static final String FIELD_TRIGGER = "field";
  private static final String TRIGGER_PRODUCT = "product";
  private static final String HTTP_POST = "POST";

  /** Request body whose {@code field} names the given trigger ({@code null} omits the key). */
  private static JSONObject triggerBody(String trigger) throws Exception {
    JSONObject body = new JSONObject();
    if (trigger != null) {
      body.put(FIELD_TRIGGER, trigger);
    }
    return body;
  }

  /** Callout updates carrying a stock-derived movementQuantity plus an unrelated key. */
  private static JSONObject calloutUpdates() throws Exception {
    return new JSONObject().put(FIELD_MOVEMENT_QUANTITY, 250.0).put(FIELD_OTHER, "x");
  }

  /** CALLOUT context with the given request body and a previous result wrapping {@code updates}. */
  private static NeoContext calloutContext(JSONObject requestBody, JSONObject updates)
      throws Exception {
    return NeoContext.builder()
        .httpMethod(HTTP_POST).endpointType(NeoEndpointType.CALLOUT)
        .requestBody(requestBody)
        .previousResult(NeoResponse.ok(new JSONObject().put(FIELD_UPDATES, updates)))
        .build();
  }

  /** Runs afterCallout for the given request body and asserts movementQuantity was stripped. */
  private static void assertStripsMovementQuantity(JSONObject requestBody) throws Exception {
    JSONObject updates = calloutUpdates();
    assertNull(HANDLER.afterCallout(calloutContext(requestBody, updates)));
    assertFalse("movementQuantity must be stripped for a product trigger",
        updates.has(FIELD_MOVEMENT_QUANTITY));
    assertEquals("other updates keys must be left untouched", "x", updates.getString(FIELD_OTHER));
  }

  /** Runs afterCallout for the given request body and asserts movementQuantity was kept. */
  private static void assertKeepsMovementQuantity(JSONObject requestBody) throws Exception {
    JSONObject updates = calloutUpdates();
    assertNull(HANDLER.afterCallout(calloutContext(requestBody, updates)));
    assertTrue("movementQuantity must be kept for a non-product trigger",
        updates.has(FIELD_MOVEMENT_QUANTITY));
    assertEquals(250.0, updates.getDouble(FIELD_MOVEMENT_QUANTITY), 0.0);
  }

  // afterCallout — product triggers strip movementQuantity

  @Test
  public void testAfterCalloutStripsMovementQuantityForProductTrigger() throws Exception {
    assertStripsMovementQuantity(triggerBody(TRIGGER_PRODUCT));
  }

  @Test
  public void testAfterCalloutStripsMovementQuantityForUppercaseProductTrigger() throws Exception {
    assertStripsMovementQuantity(triggerBody("PRODUCT"));
  }

  @Test
  public void testAfterCalloutStripsMovementQuantityForColumnNameTrigger() throws Exception {
    assertStripsMovementQuantity(triggerBody("M_Product_ID"));
  }

  @Test
  public void testAfterCalloutStripsMovementQuantityForInpNameTrigger() throws Exception {
    assertStripsMovementQuantity(triggerBody("inpmProductId"));
  }

  @Test
  public void testAfterCalloutStripsMovementQuantityForPaddedProductTrigger() throws Exception {
    assertStripsMovementQuantity(triggerBody(" product "));
  }

  // afterCallout — any other trigger keeps movementQuantity

  @Test
  public void testAfterCalloutKeepsMovementQuantityForOrderUomTrigger() throws Exception {
    assertKeepsMovementQuantity(triggerBody("orderUOM"));
  }

  @Test
  public void testAfterCalloutKeepsMovementQuantityForProductUomColumnTrigger() throws Exception {
    assertKeepsMovementQuantity(triggerBody("M_Product_Uom_Id"));
  }

  @Test
  public void testAfterCalloutKeepsMovementQuantityForQuantityOrderTrigger() throws Exception {
    assertKeepsMovementQuantity(triggerBody("quantityOrder"));
  }

  @Test
  public void testAfterCalloutKeepsMovementQuantityWhenTriggerFieldIsMissing() throws Exception {
    assertKeepsMovementQuantity(triggerBody(null));
  }

  @Test
  public void testAfterCalloutKeepsMovementQuantityWhenTriggerFieldIsBlank() throws Exception {
    assertKeepsMovementQuantity(triggerBody("   "));
  }

  @Test
  public void testAfterCalloutKeepsMovementQuantityWhenRequestBodyIsNull() throws Exception {
    assertKeepsMovementQuantity(null);
  }

  // afterCallout — scope and defensive no-ops

  /**
   * A non-CALLOUT endpoint (e.g. the CRUD write itself) must leave an "updates"-shaped body
   * untouched even when the request body names the product.
   */
  @Test
  public void testAfterCalloutNoOpWhenEndpointIsNotCallout() throws Exception {
    JSONObject updates = calloutUpdates();
    NeoContext ctx = NeoContext.builder()
        .httpMethod(HTTP_POST).endpointType(NeoEndpointType.CRUD)
        .requestBody(triggerBody(TRIGGER_PRODUCT))
        .previousResult(NeoResponse.ok(new JSONObject().put(FIELD_UPDATES, updates)))
        .build();

    assertNull(HANDLER.afterCallout(ctx));

    assertTrue("a non-CALLOUT endpoint must leave the updates object untouched",
        updates.has(FIELD_MOVEMENT_QUANTITY));
  }

  /** A product-triggered callout response without an "updates" object is a silent no-op. */
  @Test
  public void testAfterCalloutNoOpWhenUpdatesIsMissing() throws Exception {
    JSONObject body = new JSONObject().put("someOtherKey", "x");
    NeoContext ctx = NeoContext.builder()
        .httpMethod(HTTP_POST).endpointType(NeoEndpointType.CALLOUT)
        .requestBody(triggerBody(TRIGGER_PRODUCT))
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterCallout(ctx));
    assertEquals("x", body.getString("someOtherKey"));
  }

  /** A product-triggered callout with no previous result at all is also a silent no-op. */
  @Test
  public void testAfterCalloutNoOpWhenNoPreviousResult() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .httpMethod(HTTP_POST).endpointType(NeoEndpointType.CALLOUT)
        .requestBody(triggerBody(TRIGGER_PRODUCT))
        .build();

    assertNull(HANDLER.afterCallout(ctx));
  }

  @Test
  public void testAfterCalloutReturnsNullForNullContext() {
    assertNull(HANDLER.afterCallout(null));
  }

  // isProductTrigger — direct unit tests

  @Test
  public void testIsProductTriggerAcceptsEveryProductSpelling() throws Exception {
    String[] productTriggers = {TRIGGER_PRODUCT, "PRODUCT", "M_Product_ID", "m_product_id",
        "inpmProductId", "INPMPRODUCTID", " product ", "\tM_Product_ID\n"};
    for (String trigger : productTriggers) {
      assertTrue("expected a product trigger: [" + trigger + "]",
          InternalConsumptionLineHandler.isProductTrigger(triggerBody(trigger)));
    }
  }

  @Test
  public void testIsProductTriggerRejectsNonProductFields() throws Exception {
    String[] otherTriggers = {"orderUOM", "M_Product_Uom_Id", "quantityOrder", "movementQuantity",
        "productCode", "inpmProductUomId", "", "   "};
    for (String trigger : otherTriggers) {
      assertFalse("expected NOT a product trigger: [" + trigger + "]",
          InternalConsumptionLineHandler.isProductTrigger(triggerBody(trigger)));
    }
  }

  @Test
  public void testIsProductTriggerRejectsMissingField() throws Exception {
    assertFalse(InternalConsumptionLineHandler.isProductTrigger(triggerBody(null)));
  }

  @Test
  public void testIsProductTriggerRejectsNullRequestBody() {
    assertFalse(InternalConsumptionLineHandler.isProductTrigger(null));
  }
}
