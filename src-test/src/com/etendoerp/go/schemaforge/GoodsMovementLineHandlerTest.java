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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.InternalMovementLine;

/**
 * Unit tests for {@link GoodsMovementLineHandler} (ETP-4606).
 *
 * <p>Covers the write pre-hook: a Goods Movement line must be rejected with HTTP 400 when it
 * references a Service-type product ({@code productType == "S"}), on both POST (product sent in
 * the body) and PATCH (product resolved from the persisted line when absent from the body).
 */
public class GoodsMovementLineHandlerTest {

  private static final int BAD_REQUEST = HttpServletResponse.SC_BAD_REQUEST;
  private static final String PRODUCT_ID = "prod-service-1";

  private GoodsMovementLineHandler handler;
  private MockedStatic<OBMessageUtils> mockedMessageUtils;

  @Before
  public void setUp() {
    handler = new GoodsMovementLineHandler();
    // validateWrite() resolves the rejection text via OBMessageUtils.messageBD(), which reads
    // OBContext.getOBContext() internally — unavailable in a plain unit test. Mock the static
    // call directly (same pattern as AssetGroupNameUniqueHandlerTest) instead of the message
    // content itself, since the exact translated text is not under test here.
    mockedMessageUtils = mockStatic(OBMessageUtils.class);
    mockedMessageUtils.when(() -> OBMessageUtils.messageBD("ETGO_ProductNotStockable"))
        .thenReturn("This product is of type Service and cannot be used in inventory movements.");
  }

  @After
  public void clearMocks() {
    mockedMessageUtils.close();
    Mockito.framework().clearInlineMocks();
  }

  private static JSONObject bodyWithProductId(String productId) throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", productId);
    return body;
  }

  private static JSONObject bodyWithProductObject(String productId) throws Exception {
    JSONObject productRef = new JSONObject();
    productRef.put("id", productId);
    JSONObject body = new JSONObject();
    body.put("product", productRef);
    return body;
  }

  private static NeoContext postContext() {
    return NeoContext.builder()
        .specName("goodsMovementLineHandler")
        .entityName("movementLine")
        .httpMethod("POST")
        .build();
  }

  private static NeoContext patchContext(String recordId) {
    return NeoContext.builder()
        .specName("goodsMovementLineHandler")
        .entityName("movementLine")
        .httpMethod("PATCH")
        .recordId(recordId)
        .build();
  }

  private static Product product(String productType, String name) {
    return product(productType, name, PRODUCT_ID);
  }

  private static Product product(String productType, String name, String id) {
    Product product = mock(Product.class);
    when(product.getProductType()).thenReturn(productType);
    when(product.getName()).thenReturn(name);
    when(product.getId()).thenReturn(id);
    return product;
  }

  // ── validateWrite() — POST ───────────────────────────────────────────────

  @Test
  public void postRejectsServiceProductSentAsPlainId() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Product product = product("S", "Consulting Hours");
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      NeoResponse response = handler.validateWrite(postContext(), bodyWithProductId(PRODUCT_ID));

      assertNotNullBadRequest(response);
      assertFalse(response.getBody().getJSONObject("error").getString("message").isEmpty());
    }
  }

  @Test
  public void postRejectsServiceProductSentAsObject() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Product product = product("S", "Support Plan");
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      NeoResponse response = handler.validateWrite(postContext(), bodyWithProductObject(PRODUCT_ID));

      assertNotNullBadRequest(response);
    }
  }

  @Test
  public void postAllowsItemProduct() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Product product = product("I", "Widget");
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(product);

      NeoResponse response = handler.validateWrite(postContext(), bodyWithProductId(PRODUCT_ID));

      assertNull(response);
    }
  }

  @Test
  public void postWithUnknownProductLetsGenericCrudHandleTheFkError() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Product.class), eq(PRODUCT_ID))).thenReturn(null);

      NeoResponse response = handler.validateWrite(postContext(), bodyWithProductId(PRODUCT_ID));

      assertNull(response);
    }
  }

  @Test
  public void postWithNoProductInBodyIsANoOp() throws Exception {
    NeoResponse response = handler.validateWrite(postContext(), new JSONObject());
    assertNull(response);
  }

  // ── validateWrite() — PATCH (product resolved from persisted line) ──────

  @Test
  public void patchWithoutProductInBodyFallsBackToPersistedProductAndRejectsService() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      InternalMovementLine line = mock(InternalMovementLine.class);
      Product serviceProduct = product("S", "Installation Service");
      String serviceProductId = serviceProduct.getId();
      when(line.getProduct()).thenReturn(serviceProduct);
      when(dal.get(eq(InternalMovementLine.class), eq("line-1"))).thenReturn(line);
      when(dal.get(eq(Product.class), eq(serviceProductId))).thenReturn(serviceProduct);

      NeoResponse response = handler.validateWrite(patchContext("line-1"), new JSONObject());

      assertNotNullBadRequest(response);
    }
  }

  @Test
  public void patchWithNoPersistedLineAndNoProductInBodyIsANoOp() throws Exception {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(InternalMovementLine.class), eq("line-missing"))).thenReturn(null);

      NeoResponse response = handler.validateWrite(patchContext("line-missing"), new JSONObject());

      assertNull(response);
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static void assertNotNullBadRequest(NeoResponse response) throws Exception {
    org.junit.Assert.assertNotNull("expected a rejection response, got null (accepted)", response);
    assertEquals(BAD_REQUEST, response.getHttpStatus());
  }
}
