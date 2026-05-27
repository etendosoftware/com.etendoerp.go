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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Unit tests for {@link ProductPriceHandler}.
 *
 * <p>Covers: handle() guards (null context, non-CRUD, GET with recordId, PUT),
 * handleGetList (parentId presence, SQL mapping), handlePost (default injection
 * for product, priceLimit, priceListVersion), afterHandle() guards (null context,
 * non-SELECTOR, wrong fieldName), and afterHandle enrichment of selector items
 * with salesPriceList flag.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductPriceHandlerTest {

  private ProductPriceHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Organization organization;
  @Mock
  private Session session;

  @SuppressWarnings("rawtypes")
  @Mock
  private NativeQuery nativeQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    handler = new ProductPriceHandler();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(client.getId()).thenReturn("test-client-id");
    when(organization.getId()).thenReturn("test-org-id");
    when(obDal.getSession()).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── handle(): guard clauses ───────────────────────────────────────────────

  /**
   * Verifies that handle() returns null when the context is null.
   */
  @Test
  void testHandleNullContextReturnsNull() {
    assertNull(handler.handle(null));
  }

  /**
   * Verifies that handle() returns null when the endpoint type is not CRUD.
   */
  @Test
  void testHandleNonCrudEndpointReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that handle() returns null for a GET request with a recordId
   * (single-record GET, not a list request).
   */
  @Test
  void testHandleGetWithRecordIdReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("some-record-id")
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that handle() returns null for PUT requests.
   */
  @Test
  void testHandlePutReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that handle() returns null for DELETE requests.
   */
  @Test
  void testHandleDeleteReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ── handle() → handleGetList ──────────────────────────────────────────────

  /**
   * Verifies that a GET list request without parentId returns null.
   */
  @Test
  void testHandleGetListWithoutParentIdReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(Collections.emptyMap())
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that a GET list request with null queryParams returns null.
   */
  @Test
  void testHandleGetListNullQueryParamsReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that a GET list request with blank parentId returns null.
   */
  @Test
  void testHandleGetListBlankParentIdReturnsNull() {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "  ");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that a GET list request with a valid parentId returns HTTP 200
   * and maps SQL result rows into the expected JSON structure.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListWithParentIdReturnsData() throws Exception {
    String parentId = "product-123";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), eq(parentId))).thenReturn(nativeQuery);

    Object[] row = new Object[]{
        "pp-id-1",                     // id
        "product-123",                 // product_id
        "plv-id-1",                    // plv_id
        "Sales Q1 2026",               // plv_name
        new BigDecimal("100.00"),      // standard_price
        new BigDecimal("120.00"),      // list_price
        new BigDecimal("90.00"),       // price_limit
        "S",                           // algo_code
        "Y",                           // is_sales
        "Sales Price List",            // price_list_name
        "product-123 - Sales Q1 2026", // identifier
        "$",                           // currency_symbol
        "USD"                          // currency_iso
    };
    List<Object[]> rows = Collections.singletonList(row);
    when(nativeQuery.list()).thenReturn(rows);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    JSONObject inner = body.getJSONObject("response");
    assertEquals(1, inner.getInt("totalRows"));

    JSONArray data = inner.getJSONArray("data");
    assertEquals(1, data.length());

    JSONObject item = data.getJSONObject(0);
    assertEquals("pp-id-1", item.getString("id"));
    assertEquals("product-123", item.getString("product"));
    assertEquals("plv-id-1", item.getString("priceListVersion"));
    assertEquals("Sales Q1 2026", item.getString("priceListVersion$_identifier"));
    assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(item.getString("standardPrice"))));
    assertEquals(0, new BigDecimal("120.00").compareTo(new BigDecimal(item.getString("listPrice"))));
    assertEquals(0, new BigDecimal("90.00").compareTo(new BigDecimal(item.getString("priceLimit"))));
    assertEquals("S", item.getString("algorithm"));
    assertEquals("Standard", item.getString("algorithm$_identifier"));
    assertTrue(item.getBoolean("priceListVersion$salesPriceList"));
    assertEquals("Sales Price List", item.getString("priceList$_identifier"));
    assertEquals("$", item.getString("currencySymbol"));
    assertEquals("USD", item.getString("currencyIso"));
    assertEquals("PricingProductPrice", item.getString("_entityName"));
  }

  /**
   * Verifies that a GET list request returns an empty data array when there
   * are no matching product prices.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListEmptyResultsReturnsEmptyData() throws Exception {
    String parentId = "product-no-prices";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), eq(parentId))).thenReturn(nativeQuery);
    when(nativeQuery.list()).thenReturn(Collections.emptyList());

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(0, data.length());
  }

  /**
   * Verifies that non-sales prices (is_sales='N') set priceListVersion$salesPriceList
   * to false.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListNonSalesPriceHasSalesFlagFalse() throws Exception {
    String parentId = "product-456";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), eq(parentId))).thenReturn(nativeQuery);

    Object[] row = new Object[]{
        "pp-id-2", "product-456", "plv-id-2", "Purchase Q1",
        new BigDecimal("50.00"), new BigDecimal("60.00"), new BigDecimal("45.00"),
        "S", "N", "Purchase Price List",
        "product-456 - Purchase Q1", null, "EUR"
    };
    when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);
    JSONObject item = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertFalse(item.getBoolean("priceListVersion$salesPriceList"));
  }

  /**
   * Verifies that a non-standard algorithm code is preserved as-is in algorithm
   * and algorithm$_identifier.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListNonStandardAlgoCode() throws Exception {
    String parentId = "product-789";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), eq(parentId))).thenReturn(nativeQuery);

    Object[] row = new Object[]{
        "pp-id-3", "product-789", "plv-id-3", "PLV Name",
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        "W", "Y", "PL Name", "ident", "$", "USD"
    };
    when(nativeQuery.list()).thenReturn(Collections.singletonList(row));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);
    JSONObject item = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("W", item.getString("algorithm"));
    assertEquals("W", item.getString("algorithm$_identifier"));
  }

  /**
   * Verifies that when the native query throws an exception, handle returns
   * an HTTP 500 error response.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListSqlExceptionReturns500() {
    String parentId = "product-err";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    when(nativeQuery.list()).thenThrow(new RuntimeException("DB error"));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);
    assertEquals(500, response.getHttpStatus());
  }

  // ── handle() → handlePost ─────────────────────────────────────────────────

  /**
   * Verifies that handlePost returns null when the request body is null.
   */
  @Test
  void testHandlePostNullBodyReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.handle(ctx));
  }

  /**
   * Verifies that handlePost injects the parentId as the product field
   * when the body does not already contain a product.
   */
  @Test
  void testHandlePostInjectsProductFromParentId() throws Exception {
    JSONObject body = new JSONObject();
    body.put("standardPrice", new BigDecimal("100.00"));

    Map<String, String> params = new HashMap<>();
    params.put("parentId", "product-parent-id");

    // Mock OBCriteria for resolveDefaultSalesPriceListVersionId
    mockEmptyCriteria();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .queryParams(params)
        .obContext(obContext)
        .build();

    assertNull(handler.handle(ctx));
    assertEquals("product-parent-id", body.getString("product"));
  }

  /**
   * Verifies that handlePost does not overwrite an existing product field.
   */
  @Test
  void testHandlePostDoesNotOverwriteExistingProduct() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "existing-product-id");

    Map<String, String> params = new HashMap<>();
    params.put("parentId", "product-parent-id");

    mockEmptyCriteria();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .queryParams(params)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals("existing-product-id", body.getString("product"));
  }

  /**
   * Verifies that handlePost sets priceLimit from listPrice when priceLimit
   * is missing and listPrice is present.
   */
  @Test
  void testHandlePostDefaultsPriceLimitFromListPrice() throws Exception {
    JSONObject body = new JSONObject();
    body.put("listPrice", new BigDecimal("150.00"));
    body.put("product", "already-set");

    mockEmptyCriteria();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals(0, new BigDecimal("150.00").compareTo(new BigDecimal(body.get("priceLimit").toString())));
  }

  /**
   * Verifies that handlePost sets priceLimit from standardPrice when
   * priceLimit and listPrice are missing but standardPrice is present.
   */
  @Test
  void testHandlePostDefaultsPriceLimitFromStandardPrice() throws Exception {
    JSONObject body = new JSONObject();
    body.put("standardPrice", new BigDecimal("80.00"));
    body.put("product", "already-set");

    mockEmptyCriteria();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals(0, new BigDecimal("80.00").compareTo(new BigDecimal(body.get("priceLimit").toString())));
  }

  /**
   * Verifies that handlePost does not overwrite an existing priceLimit.
   */
  @Test
  void testHandlePostDoesNotOverwriteExistingPriceLimit() throws Exception {
    JSONObject body = new JSONObject();
    body.put("priceLimit", new BigDecimal("50.00"));
    body.put("listPrice", new BigDecimal("150.00"));
    body.put("product", "already-set");

    mockEmptyCriteria();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals(0, new BigDecimal("50.00").compareTo(new BigDecimal(body.get("priceLimit").toString())));
  }

  /**
   * Verifies that handlePost injects a default priceListVersion when one is
   * found via OBCriteria and the body does not already contain the field.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandlePostInjectsDefaultPriceListVersion() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "already-set");
    body.put("listPrice", new BigDecimal("100.00"));

    PriceListVersion mockPlv = mock(PriceListVersion.class);
    when(mockPlv.getId()).thenReturn("resolved-plv-id");

    OBCriteria<PriceListVersion> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(PriceListVersion.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.createAlias(anyString(), anyString())).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(mockPlv));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals("resolved-plv-id", body.getString("priceListVersion"));
  }

  /**
   * Verifies that handlePost does not overwrite an existing priceListVersion.
   */
  @Test
  void testHandlePostDoesNotOverwriteExistingPriceListVersion() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "already-set");
    body.put("priceListVersion", "existing-plv-id");

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals("existing-plv-id", body.getString("priceListVersion"));
  }

  /**
   * Verifies that resolveDefaultSalesPriceListVersionId falls back to org=0
   * when org-specific search returns no results.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandlePostFallsBackToSharedOrgForPriceListVersion() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "already-set");
    body.put("listPrice", new BigDecimal("100.00"));

    PriceListVersion mockPlv = mock(PriceListVersion.class);
    when(mockPlv.getId()).thenReturn("shared-plv-id");

    OBCriteria<PriceListVersion> orgCriteria = mock(OBCriteria.class);
    OBCriteria<PriceListVersion> sharedCriteria = mock(OBCriteria.class);

    when(obDal.createCriteria(PriceListVersion.class))
        .thenReturn(orgCriteria)
        .thenReturn(sharedCriteria);

    // First call (org-specific) returns empty
    when(orgCriteria.add(any(Criterion.class))).thenReturn(orgCriteria);
    when(orgCriteria.createAlias(anyString(), anyString())).thenReturn(orgCriteria);
    when(orgCriteria.addOrder(any(Order.class))).thenReturn(orgCriteria);
    when(orgCriteria.setMaxResults(1)).thenReturn(orgCriteria);
    when(orgCriteria.list()).thenReturn(Collections.emptyList());

    // Second call (shared org=0) returns a result
    when(sharedCriteria.add(any(Criterion.class))).thenReturn(sharedCriteria);
    when(sharedCriteria.createAlias(anyString(), anyString())).thenReturn(sharedCriteria);
    when(sharedCriteria.addOrder(any(Order.class))).thenReturn(sharedCriteria);
    when(sharedCriteria.setMaxResults(1)).thenReturn(sharedCriteria);
    when(sharedCriteria.list()).thenReturn(Collections.singletonList(mockPlv));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals("shared-plv-id", body.getString("priceListVersion"));
  }

  /**
   * Verifies that resolveDefaultSalesPriceListVersionId only tries org=0 once
   * when the current org is already "0".
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandlePostOrgZeroOnlyTriesOnce() throws Exception {
    when(organization.getId()).thenReturn("0");

    JSONObject body = new JSONObject();
    body.put("product", "already-set");
    body.put("listPrice", new BigDecimal("100.00"));

    PriceListVersion mockPlv = mock(PriceListVersion.class);
    when(mockPlv.getId()).thenReturn("zero-org-plv-id");

    OBCriteria<PriceListVersion> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(PriceListVersion.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.createAlias(anyString(), anyString())).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(mockPlv));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    handler.handle(ctx);
    assertEquals("zero-org-plv-id", body.getString("priceListVersion"));
  }

  /**
   * Verifies that handlePost with null obContext in the NeoContext does not
   * inject priceListVersion (resolveDefaultSalesPriceListVersionId returns null).
   */
  @Test
  void testHandlePostNullObContextSkipsPriceListVersionResolution() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "already-set");
    body.put("listPrice", new BigDecimal("100.00"));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();

    handler.handle(ctx);
    assertFalse(body.has("priceListVersion"));
  }

  // ── afterHandle(): guard clauses ──────────────────────────────────────────

  /**
   * Verifies that afterHandle() returns null when the context is null.
   */
  @Test
  void testAfterHandleNullContextReturnsNull() {
    assertNull(handler.afterHandle(null));
  }

  /**
   * Verifies that afterHandle() returns null when the endpoint type is not SELECTOR.
   */
  @Test
  void testAfterHandleNonSelectorEndpointReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .fieldName("priceListVersion")
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle() returns null when the fieldName does not match
   * the expected priceListVersion field.
   */
  @Test
  void testAfterHandleWrongFieldNameReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("someOtherField")
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle() returns null when previousResult is null.
   */
  @Test
  void testAfterHandleNullPreviousResultReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle() returns null when previousResult body is null.
   */
  @Test
  void testAfterHandleNullPreviousResultBodyReturnsNull() {
    NeoResponse prev = new NeoResponse(200, null);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(prev)
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle() returns null when the body has no items array.
   */
  @Test
  void testAfterHandleNoItemsArrayReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    body.put("someKey", "someValue");
    NeoResponse prev = new NeoResponse(200, body);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(prev)
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle(): enrichment ─────────────────────────────────────────────

  /**
   * Verifies that afterHandle() enriches selector items with salesPriceList,
   * priceListVersion$salesPriceList, priceList, and priceList$_identifier
   * when using the field name "priceListVersion".
   */
  @Test
  void testAfterHandleEnrichesSelectorItemsWithFieldName() throws Exception {
    verifyAfterHandleEnrichment("priceListVersion");
  }

  /**
   * Verifies that afterHandle() also matches the column name
   * "M_PriceList_Version_ID" (case-insensitive).
   */
  @Test
  void testAfterHandleEnrichesSelectorItemsWithColumnName() throws Exception {
    verifyAfterHandleEnrichment("M_PriceList_Version_ID");
  }

  /**
   * Verifies that afterHandle() handles items with blank/missing id gracefully,
   * skipping enrichment for those items.
   */
  @Test
  void testAfterHandleSkipsItemsWithBlankId() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject itemWithoutId = new JSONObject();
    itemWithoutId.put("name", "no-id-item");
    items.put(itemWithoutId);

    JSONObject body = new JSONObject();
    body.put("items", items);
    NeoResponse prev = new NeoResponse(200, body);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(prev)
        .build();

    NeoResponse result = handler.afterHandle(ctx);
    assertEquals(200, result.getHttpStatus());
    assertFalse(items.getJSONObject(0).has("salesPriceList"));
  }

  /**
   * Verifies that afterHandle() gracefully handles an item whose id does not
   * correspond to a real PriceListVersion (OBDal.get returns null).
   */
  @Test
  void testAfterHandleSkipsItemsWithUnknownVersionId() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item = new JSONObject();
    item.put("id", "non-existent-plv-id");
    items.put(item);

    JSONObject body = new JSONObject();
    body.put("items", items);
    NeoResponse prev = new NeoResponse(200, body);

    when(obDal.get(PriceListVersion.class, "non-existent-plv-id")).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(prev)
        .build();

    NeoResponse result = handler.afterHandle(ctx);
    assertEquals(200, result.getHttpStatus());
    assertFalse(items.getJSONObject(0).has("salesPriceList"));
  }

  /**
   * Verifies that afterHandle() enriches multiple items correctly, including
   * a mix of sales and purchase price list versions.
   */
  @Test
  void testAfterHandleEnrichesMultipleItems() throws Exception {
    JSONArray items = new JSONArray();

    JSONObject salesItem = new JSONObject();
    salesItem.put("id", "plv-sales");
    items.put(salesItem);

    JSONObject purchaseItem = new JSONObject();
    purchaseItem.put("id", "plv-purchase");
    items.put(purchaseItem);

    JSONObject body = new JSONObject();
    body.put("items", items);
    NeoResponse prev = new NeoResponse(200, body);

    // Sales price list version
    PriceListVersion salesPlv = mock(PriceListVersion.class);
    PriceList salesPl = mock(PriceList.class);
    when(salesPlv.getPriceList()).thenReturn(salesPl);
    when(salesPl.isSalesPriceList()).thenReturn(Boolean.TRUE);
    when(salesPl.getId()).thenReturn("pl-sales-id");
    when(salesPl.getIdentifier()).thenReturn("Sales Price List");
    when(obDal.get(PriceListVersion.class, "plv-sales")).thenReturn(salesPlv);

    // Purchase price list version
    PriceListVersion purchasePlv = mock(PriceListVersion.class);
    PriceList purchasePl = mock(PriceList.class);
    when(purchasePlv.getPriceList()).thenReturn(purchasePl);
    when(purchasePl.isSalesPriceList()).thenReturn(Boolean.FALSE);
    when(purchasePl.getId()).thenReturn("pl-purchase-id");
    when(purchasePl.getIdentifier()).thenReturn("Purchase Price List");
    when(obDal.get(PriceListVersion.class, "plv-purchase")).thenReturn(purchasePlv);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(prev)
        .build();

    NeoResponse result = handler.afterHandle(ctx);
    assertEquals(200, result.getHttpStatus());

    JSONObject enrichedSales = items.getJSONObject(0);
    assertTrue(enrichedSales.getBoolean("salesPriceList"));
    assertTrue(enrichedSales.getBoolean("priceListVersion$salesPriceList"));
    assertEquals("pl-sales-id", enrichedSales.getString("priceList"));
    assertEquals("Sales Price List", enrichedSales.getString("priceList$_identifier"));

    JSONObject enrichedPurchase = items.getJSONObject(1);
    assertFalse(enrichedPurchase.getBoolean("salesPriceList"));
    assertFalse(enrichedPurchase.getBoolean("priceListVersion$salesPriceList"));
    assertEquals("pl-purchase-id", enrichedPurchase.getString("priceList"));
    assertEquals("Purchase Price List", enrichedPurchase.getString("priceList$_identifier"));
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  /**
   * Sets up an empty OBCriteria mock for PriceListVersion so that
   * resolveDefaultSalesPriceListVersionId returns null.
   */
  @SuppressWarnings("unchecked")
  private void mockEmptyCriteria() {
    OBCriteria<PriceListVersion> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(PriceListVersion.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.createAlias(anyString(), anyString())).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());
  }

  /**
   * Common verification for afterHandle enrichment with different field names.
   */
  private void verifyAfterHandleEnrichment(String fieldName) throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item = new JSONObject();
    item.put("id", "plv-test-id");
    items.put(item);

    JSONObject body = new JSONObject();
    body.put("items", items);
    NeoResponse prev = new NeoResponse(200, body);

    PriceListVersion mockPlv = mock(PriceListVersion.class);
    PriceList mockPl = mock(PriceList.class);
    when(mockPlv.getPriceList()).thenReturn(mockPl);
    when(mockPl.isSalesPriceList()).thenReturn(Boolean.TRUE);
    when(mockPl.getId()).thenReturn("pl-id-1");
    when(mockPl.getIdentifier()).thenReturn("Test Sales PL");
    when(obDal.get(PriceListVersion.class, "plv-test-id")).thenReturn(mockPlv);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName(fieldName)
        .previousResult(prev)
        .build();

    NeoResponse result = handler.afterHandle(ctx);
    assertEquals(200, result.getHttpStatus());

    JSONObject enriched = items.getJSONObject(0);
    assertTrue(enriched.getBoolean("salesPriceList"));
    assertTrue(enriched.getBoolean("priceListVersion$salesPriceList"));
    assertEquals("pl-id-1", enriched.getString("priceList"));
    assertEquals("Test Sales PL", enriched.getString("priceList$_identifier"));
  }
}
