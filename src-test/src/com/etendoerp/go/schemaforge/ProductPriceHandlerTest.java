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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

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
import org.junit.jupiter.api.function.Executable;
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
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.service.json.JsonUtils;

import com.etendoerp.go.schemaforge.util.NeoDateFormat;

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

  /**
   * The server-local wall clock the {@code row[15]} fixture ({@code "2026-08-15 10:30:00.123456"},
   * the raw Postgres shape) denotes, with NO zone offset.
   *
   * <p>Deliberately <b>not</b> the expected value: it is what {@code NeoDateFormat.toCanonical}
   * emitted before ETP-5255, kept so the assertions can state that the token preserves this wall
   * clock and is nevertheless never equal to it.
   */
  private static final String SAMPLE_UPDATED_WALL_CLOCK = "2026-08-15T10:30:00";

  /**
   * The shape of an {@code updated} audit token: the canonical ISO datetime plus a
   * <b>mandatory</b> RFC-822 zone offset — the pattern
   * {@code JsonUtils.createDateTimeFormat()} ({@code yyyy-MM-dd'T'HH:mm:ssZZZZZ}) emits and
   * requires back. The offset is asserted as a shape, not a literal, because it is the JVM's
   * default zone: hardcoding a developer machine's {@code -0300} would only move the failure to
   * CI.
   */
  private static final Pattern AUDIT_TOKEN_SHAPE = Pattern
      .compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{4}$");

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
        "pp-id-1",                     // [0]  id
        "product-123",                 // [1]  product_id
        "plv-id-1",                    // [2]  plv_id
        "Sales Q1 2026",               // [3]  plv_name
        new BigDecimal("100.00"),      // [4]  standard_price
        new BigDecimal("120.00"),      // [5]  list_price
        new BigDecimal("90.00"),       // [6]  price_limit
        "S",                           // [7]  algo_code
        "Y",                           // [8]  is_sales
        "Sales Price List",            // [9]  price_list_name
        "product-123 - Sales Q1 2026", // [10] identifier
        "$",                           // [11] currency_symbol
        "USD",                         // [12] currency_iso
        "Y",                           // [13] is_default (pl.isdefault)
        java.sql.Date.valueOf("2026-01-01"), // [14] valid_from_date (plv.validfrom)
        "2026-08-15 10:30:00.123456"   // [15] updated (raw Postgres timestamp)
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
    // New fields: is_default and valid_from_date (added at indices 13 and 14)
    assertTrue(item.getBoolean("priceListVersion$default"));
    assertEquals(String.valueOf(java.sql.Date.valueOf("2026-01-01")), item.getString("priceListVersion$validFromDate"));
    assertEquals("PricingProductPrice", item.getString("_entityName"));
    // ETP-5203: updated (row[15]) must be present so PUT/PATCH can echo it back
    // for the mandatory optimistic-concurrency check (missing_updated regression).
    // ETP-5255: and it must be rendered by NeoDateFormat.toAuditToken, NOT toCanonical — the
    // token is read back by JsonUtils.createDateTimeFormat(), whose offset is mandatory, so an
    // offsetless value is silently re-read as UTC and every edit on this tab came back stale by
    // exactly the server's UTC offset. See NeoDateFormat.toAuditToken's javadoc.
    assertIsAuditTokenFor(SAMPLE_UPDATED_WALL_CLOCK, item.getString("updated"));
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
        "pp-id-2", "product-456", "plv-id-2", "Purchase Q1",        // [0-3]
        new BigDecimal("50.00"), new BigDecimal("60.00"),             // [4-5]
        new BigDecimal("45.00"),                                      // [6]
        "S", "N", "Purchase Price List",                             // [7-9]
        "product-456 - Purchase Q1", null, "EUR",                    // [10-12]
        "N",                                                          // [13] is_default
        null,                                                         // [14] valid_from_date (null)
        "2026-08-15 10:30:00.123456"                                 // [15] updated
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
        "pp-id-3", "product-789", "plv-id-3", "PLV Name",           // [0-3]
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,           // [4-6]
        "W", "Y", "PL Name", "ident", "$", "USD",                   // [7-12]
        "N",                                                          // [13] is_default
        null,                                                         // [14] valid_from_date (null)
        "2026-08-15 10:30:00.123456"                                 // [15] updated
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
   * Verifies that priceListVersion$default is false and priceListVersion$validFromDate
   * is null when row[13] is 'N' and row[14] is null.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListDefaultFalseAndNullValidFromDate() throws Exception {
    String parentId = "product-defaults";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), eq(parentId))).thenReturn(nativeQuery);

    Object[] row = new Object[]{
        "pp-id-d1", "product-defaults", "plv-id-d1", "PLV Default Test", // [0-3]
        new BigDecimal("10.00"), new BigDecimal("12.00"),                  // [4-5]
        new BigDecimal("9.00"),                                            // [6]
        "S", "Y", "PL Default Test",                                      // [7-9]
        "product-defaults - PLV Default Test", "$", "USD",                // [10-12]
        "N",                                                               // [13] is_default = false
        null,                                                              // [14] valid_from_date = null
        "2026-08-15 10:30:00.123456"                                      // [15] updated
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

    JSONObject item = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertFalse(item.getBoolean("priceListVersion$default"));
    assertTrue(item.isNull("priceListVersion$validFromDate"));
  }

  /**
   * Verifies that priceListVersion$default is true and priceListVersion$validFromDate
   * equals String.valueOf(row[14]) when row[13] is 'Y' and row[14] is a date.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleGetListDefaultTrueAndValidFromDatePresent() throws Exception {
    String parentId = "product-default-true";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), eq(parentId))).thenReturn(nativeQuery);

    java.sql.Date validFrom = java.sql.Date.valueOf("2025-06-01");
    Object[] row = new Object[]{
        "pp-id-d2", "product-default-true", "plv-id-d2", "PLV True Test", // [0-3]
        new BigDecimal("20.00"), new BigDecimal("25.00"),                   // [4-5]
        new BigDecimal("18.00"),                                            // [6]
        "S", "Y", "PL True Test",                                          // [7-9]
        "product-default-true - PLV True Test", null, "EUR",               // [10-12]
        "Y",                                                                // [13] is_default = true
        validFrom,                                                          // [14] valid_from_date
        "2026-08-15 10:30:00.123456"                                       // [15] updated
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

    JSONObject item = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertTrue(item.getBoolean("priceListVersion$default"));
    assertEquals(String.valueOf(validFrom), item.getString("priceListVersion$validFromDate"));
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
    // ETP-5245: the POST path now also looks for an existing row on the resolved tariff; without
    // this stub the test would silently traverse the upsert's error branch instead of the insert.
    givenTheProductHasNoPriceOnThatTariff();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    assertNull(handler.handle(ctx));
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
    givenTheProductHasNoPriceOnThatTariff();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    assertNull(handler.handle(ctx));
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
    givenTheProductHasNoPriceOnThatTariff();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    assertNull(handler.handle(ctx));
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
    givenTheProductHasNoPriceOnThatTariff();

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();

    assertNull(handler.handle(ctx));
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

  // ── ETP-5245: POST on an already-priced tariff updates instead of duplicating ─────────────
  //
  // M_ProductPrice is unique on (M_PriceList_Version_ID, M_Product_ID). Since ProductDefaultsHandler
  // seeds a zero-priced row on each default tariff at product creation, the products import's own
  // price POST — same /batch call, same tariff — would otherwise hit that constraint. Returning a
  // non-null response from handle() short-circuits the default CRUD, so the INSERT never runs.

  private static final String UPSERT_PRODUCT_ID = "product-upsert";
  private static final String UPSERT_VERSION_ID = "plv-upsert";
  private static final String UPSERT_PRICE_ID = "pp-upsert";

  /** Makes ProductHandlerUtils.findExistingPrice see one existing active row for the pair. */
  @SuppressWarnings("unchecked")
  private ProductPrice givenTheProductIsAlreadyPricedOnThatTariff() {
    ProductPrice existing = mock(ProductPrice.class);
    when(existing.getId()).thenReturn(UPSERT_PRICE_ID);
    OBCriteria<ProductPrice> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(ProductPrice.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(existing));
    return existing;
  }

  /** Makes ProductHandlerUtils.findExistingPrice see no row for the pair. */
  @SuppressWarnings("unchecked")
  private void givenTheProductHasNoPriceOnThatTariff() {
    OBCriteria<ProductPrice> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(ProductPrice.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());
  }

  /** Stubs the read-back query so the updated row can be returned in the GET row shape. */
  @SuppressWarnings("unchecked")
  private void stubReadBackOf(String priceId) {
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(eq("productId"), anyString())).thenReturn(nativeQuery);
    Object[] row = new Object[]{
        priceId, UPSERT_PRODUCT_ID, UPSERT_VERSION_ID, "Default Sales",
        new BigDecimal("199.99"), new BigDecimal("199.99"), new BigDecimal("199.99"),
        "S", "Y", "Default Sales PL", UPSERT_PRODUCT_ID + " - Default Sales", "\u20ac", "EUR",
        "Y", java.sql.Date.valueOf("2026-01-01"), "2026-08-15 10:30:00.123456"
    };
    Object[] otherRow = new Object[]{
        "pp-someone-else", UPSERT_PRODUCT_ID, "plv-other", "Other",
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        "S", "N", "Other PL", "ident", null, "EUR",
        "N", null, "2026-08-15 10:30:00.123456"
    };
    when(nativeQuery.list()).thenReturn(Arrays.asList(otherRow, row));
  }

  private NeoContext upsertCtx(JSONObject body) {
    return NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .obContext(obContext)
        .build();
  }

  private static JSONObject upsertBody() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", UPSERT_PRODUCT_ID);
    body.put("priceListVersion", UPSERT_VERSION_ID);
    return body;
  }

  /**
   * Verifies that posting a price for a tariff the product already sits on updates that row and
   * returns it, short-circuiting the insert that would violate the unique constraint.
   */
  @Test
  void testHandlePostUpdatesTheExistingRowInsteadOfInsertingADuplicate() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", "199.99");
    body.put("listPrice", "199.99");
    body.put("priceLimit", "199.99");

    NeoResponse response = handler.handle(upsertCtx(body));

    // Non-null == the default CRUD never runs == no INSERT == no constraint violation.
    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    verify(existing).setStandardPrice(new BigDecimal("199.99"));
    verify(existing).setListPrice(new BigDecimal("199.99"));
    verify(existing).setPriceLimit(new BigDecimal("199.99"));
    verify(obDal).save(existing);
    verify(obDal).flush();

    // The response carries only the updated row, in the same shape a GET would return.
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());
    assertEquals(UPSERT_PRICE_ID, data.getJSONObject(0).getString("id"));
    assertEquals(UPSERT_VERSION_ID, data.getJSONObject(0).getString("priceListVersion"));
  }

  /**
   * ETP-5245: the row found may have been deactivated by hand. It is invisible in the UI but
   * still occupies the unique (version, product) pair, so the upsert has to reactivate it —
   * otherwise the price the user just posted is written to a row nobody can see, and the tariff
   * still looks unpriced.
   */
  @Test
  void testHandlePostReactivatesADeactivatedRowItUpserts() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    when(existing.isActive()).thenReturn(Boolean.FALSE);
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", "42.00");

    NeoResponse response = handler.handle(upsertCtx(body));

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    verify(existing).setActive(true);
    verify(existing).setStandardPrice(new BigDecimal("42.00"));
    verify(obDal).save(existing);
  }

  /**
   * The reactivation is unconditional, so an already-active row takes the same path — asserted
   * separately so a future "only when inactive" optimisation cannot silently split the two.
   */
  @Test
  void testHandlePostKeepsAnAlreadyActiveRowActive() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    when(existing.isActive()).thenReturn(Boolean.TRUE);
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", "42.00");

    assertNotNull(handler.handle(upsertCtx(body)));
    verify(existing).setActive(true);
    verify(existing, never()).setActive(false);
  }

  /**
   * Verifies the other half of the upsert: with no existing row, handle() returns null so the
   * generic CRUD performs the normal insert.
   */
  @Test
  void testHandlePostLetsTheNormalInsertProceedWhenTheTariffIsUnpriced() throws Exception {
    givenTheProductHasNoPriceOnThatTariff();

    JSONObject body = upsertBody();
    body.put("standardPrice", "10");

    assertNull(handler.handle(upsertCtx(body)));
    verify(obDal, never()).save(any());
    verify(obDal, never()).flush();
  }

  /**
   * Verifies that the upsert lookup is skipped entirely when the pair is incomplete — there is
   * nothing to collide with, so the insert must proceed.
   */
  @Test
  void testHandlePostSkipsTheUpsertLookupWhenNoPriceListVersionCouldBeResolved() throws Exception {
    mockEmptyCriteria();

    JSONObject body = new JSONObject();
    body.put("product", UPSERT_PRODUCT_ID);
    body.put("standardPrice", "10");

    assertNull(handler.handle(upsertCtx(body)));
    verify(obDal, never()).createCriteria(ProductPrice.class);
  }

  /**
   * applyPrice: a field the caller did not send keeps its stored value. Note priceLimit IS still
   * written here — handlePost derives it from the standard price before the upsert runs.
   */
  @Test
  void testHandlePostLeavesUnsentPriceFieldsUntouched() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", "42.00");

    assertNotNull(handler.handle(upsertCtx(body)));

    verify(existing).setStandardPrice(new BigDecimal("42.00"));
    verify(existing).setPriceLimit(new BigDecimal("42.00"));
    verify(existing, never()).setListPrice(any());
  }

  /**
   * applyPrice: an explicit JSON null is ignored rather than wiping the stored amount.
   */
  @Test
  void testHandlePostIgnoresNullPriceFields() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", JSONObject.NULL);
    body.put("listPrice", JSONObject.NULL);
    body.put("priceLimit", JSONObject.NULL);

    assertNotNull(handler.handle(upsertCtx(body)));

    verify(existing, never()).setStandardPrice(any());
    verify(existing, never()).setListPrice(any());
    verify(existing, never()).setPriceLimit(any());
  }

  /**
   * applyPrice: a blank cell is ignored rather than parsed as zero.
   */
  @Test
  void testHandlePostIgnoresBlankPriceFields() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", "   ");
    body.put("listPrice", "");
    body.put("priceLimit", "  ");

    assertNotNull(handler.handle(upsertCtx(body)));

    verify(existing, never()).setStandardPrice(any());
    verify(existing, never()).setListPrice(any());
    verify(existing, never()).setPriceLimit(any());
  }

  /**
   * applyPrice: an unparseable amount is logged and skipped — it must not blow up the request nor
   * overwrite the stored price with garbage.
   */
  @Test
  void testHandlePostIgnoresUnparseablePriceFields() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", "not-a-number");
    body.put("listPrice", "12,50");

    NeoResponse response = handler.handle(upsertCtx(body));

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    verify(existing, never()).setStandardPrice(any());
    verify(existing, never()).setListPrice(any());
  }

  /**
   * applyPrice: surrounding whitespace is trimmed, and zero is a real value (not "blank").
   */
  @Test
  void testHandlePostAcceptsPaddedAmountsAndZero() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    stubReadBackOf(UPSERT_PRICE_ID);

    JSONObject body = upsertBody();
    body.put("standardPrice", " 12.50 ");
    body.put("listPrice", "0");
    body.put("priceLimit", "0");

    assertNotNull(handler.handle(upsertCtx(body)));

    verify(existing).setStandardPrice(new BigDecimal("12.50"));
    verify(existing).setListPrice(new BigDecimal("0"));
    verify(existing).setPriceLimit(new BigDecimal("0"));
  }

  /**
   * Verifies a failing update surfaces as a 500 instead of falling through to the insert the
   * unique constraint would reject anyway.
   */
  @Test
  void testHandlePostReturns500WhenTheUpdateFails() throws Exception {
    ProductPrice existing = givenTheProductIsAlreadyPricedOnThatTariff();
    doThrow(new IllegalStateException("db down")).when(obDal).save(existing);

    JSONObject body = upsertBody();
    body.put("standardPrice", "10");

    NeoResponse response = handler.handle(upsertCtx(body));

    assertNotNull(response);
    assertEquals(500, response.getHttpStatus());
  }

  // ── ETP-5245: the selector exposes the tenant's default-tariff flag ───────────────────────

  /**
   * Verifies the price list version selector now carries the M_PriceList.IsDefault flag, which the
   * products import reads to agree with PriceListVersionResolver about which tariff is "the" one.
   */
  @Test
  void testAfterHandleExposesTheDefaultFlagOnSelectorItems() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject defaultItem = new JSONObject();
    defaultItem.put("id", "plv-default");
    items.put(defaultItem);
    JSONObject otherItem = new JSONObject();
    otherItem.put("id", "plv-other");
    items.put(otherItem);

    JSONObject body = new JSONObject();
    body.put("items", items);

    PriceListVersion defaultPlv = mock(PriceListVersion.class);
    PriceList defaultPl = mock(PriceList.class);
    when(defaultPlv.getPriceList()).thenReturn(defaultPl);
    when(defaultPl.isSalesPriceList()).thenReturn(Boolean.TRUE);
    when(defaultPl.isDefault()).thenReturn(Boolean.TRUE);
    when(defaultPl.getId()).thenReturn("pl-default");
    when(defaultPl.getIdentifier()).thenReturn("Default Sales PL");
    when(obDal.get(PriceListVersion.class, "plv-default")).thenReturn(defaultPlv);

    PriceListVersion otherPlv = mock(PriceListVersion.class);
    PriceList otherPl = mock(PriceList.class);
    when(otherPlv.getPriceList()).thenReturn(otherPl);
    when(otherPl.isSalesPriceList()).thenReturn(Boolean.TRUE);
    when(otherPl.isDefault()).thenReturn(Boolean.FALSE);
    when(otherPl.getId()).thenReturn("pl-other");
    when(otherPl.getIdentifier()).thenReturn("Other Sales PL");
    when(obDal.get(PriceListVersion.class, "plv-other")).thenReturn(otherPlv);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(new NeoResponse(200, body))
        .build();

    assertEquals(200, handler.afterHandle(ctx).getHttpStatus());

    assertTrue(items.getJSONObject(0).getBoolean("default"));
    assertTrue(items.getJSONObject(0).getBoolean("priceListVersion$default"));
    assertFalse(items.getJSONObject(1).getBoolean("default"));
    assertFalse(items.getJSONObject(1).getBoolean("priceListVersion$default"));
  }

  /**
   * Verifies an unset IsDefault (null on the model) reads as false rather than throwing.
   */
  @Test
  void testAfterHandleTreatsAnUnsetDefaultFlagAsFalse() throws Exception {
    JSONArray items = new JSONArray();
    JSONObject item = new JSONObject();
    item.put("id", "plv-unset");
    items.put(item);

    JSONObject body = new JSONObject();
    body.put("items", items);

    PriceListVersion plv = mock(PriceListVersion.class);
    PriceList pl = mock(PriceList.class);
    when(plv.getPriceList()).thenReturn(pl);
    when(pl.isSalesPriceList()).thenReturn(Boolean.TRUE);
    when(pl.isDefault()).thenReturn(null);
    when(pl.getId()).thenReturn("pl-unset");
    when(pl.getIdentifier()).thenReturn("Unset PL");
    when(obDal.get(PriceListVersion.class, "plv-unset")).thenReturn(plv);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR)
        .fieldName("priceListVersion")
        .previousResult(new NeoResponse(200, body))
        .build();

    handler.afterHandle(ctx);

    assertFalse(items.getJSONObject(0).getBoolean("default"));
    assertFalse(items.getJSONObject(0).getBoolean("priceListVersion$default"));
  }

  // ── ETP-5245: the echoed `updated` has to survive core's own reader ───────────────────────
  //
  // The bug this section guards: `updated` was formatted with NeoDateFormat.toCanonical(raw,
  // true), which DELIBERATELY drops the zone offset. Core's reader
  // (JsonUtils.convertFromXSDToJavaFormat) treats an offset-less token as UTC — "make them utc,
  // the timezone must be there" — so on a UTC-3 server the value the client echoed back parsed
  // three hours earlier than the row's own `updated`, and NeoRecordVersion.isStale (an equality
  // comparison to the second, not an "older than") answered "stale" for every write. Nobody had
  // touched the row; no price could be edited at all.
  //
  // The old assertion could not catch this: it pinned the exact offset-less string, so dropping
  // the offset was the expected outcome. These cases assert the property instead — the echoed
  // stamp always carries an offset, and it round-trips through core's reader back to the very
  // instant it came from, under any server time zone.

  /** The shape a native query hands the column over in when it arrives as raw text. */
  private static final String RAW_PG_UPDATED = "2026-08-15 10:30:00.123456";

  /**
   * The server zones every echo case runs under.
   *
   * <p>Buenos Aires is the reported failure, UTC is the zone where the bug is invisible (and the
   * one a CI runner is most likely to be in), and Kolkata is there for its <b>half-hour</b>
   * offset: {@code JsonUtils.convertToCorrectXSDFormat} inserts the colon by POSITION
   * ({@code length-2}), so {@code +0530} is the case that would expose a mis-placed colon —
   * which the reader would then not recognise, silently falling back to its {@code +0000} repair.
   */
  private static final String[] SERVER_ZONES = {
      "America/Argentina/Buenos_Aires", "UTC", "Asia/Kolkata" };

  /**
   * Runs {@code body} with the JVM default time zone forced to {@code zoneId} and restores the
   * original zone afterwards.
   *
   * <p>The server's zone is the variable this whole bug turns on, so it has to be an input of
   * the test rather than whatever the machine running the suite happens to be set to — a case
   * pinned to {@code -0300} would pass here and fail in a UTC CI runner, and a case pinned to
   * the ambient zone would go green on a UTC runner while the bug was still live.
   *
   * @param zoneId the zone to install for the duration of {@code body}
   * @param body   the assertions to run under that zone
   * @throws Throwable whatever {@code body} throws
   */
  private void withDefaultTimeZone(String zoneId, Executable body) throws Throwable {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
      body.execute();
    } finally {
      TimeZone.setDefault(original);
    }
  }

  /** A price row whose column 15 carries {@code rawUpdated}; everything else is filler. */
  private static Object[] priceRowWithUpdated(Object rawUpdated) {
    return new Object[]{
        "pp-upd-1", "product-upd", "plv-upd-1", "PLV Updated",   // [0-3]
        new BigDecimal("10.00"), new BigDecimal("12.00"),         // [4-5]
        new BigDecimal("9.00"),                                   // [6]
        "S", "Y", "PL Updated",                                   // [7-9]
        "product-upd - PLV Updated", "$", "USD",                  // [10-12]
        "Y", java.sql.Date.valueOf("2026-01-01"),                 // [13-14]
        rawUpdated                                                // [15] updated
    };
  }

  /** Runs a GET list over {@code rows} and returns the serialised data array. */
  @SuppressWarnings("unchecked")
  private JSONArray runGetListWith(List<Object[]> rows) throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("parentId", "product-upd");
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    when(nativeQuery.list()).thenReturn(rows);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);
    assertEquals(200, response.getHttpStatus());
    return response.getBody().getJSONObject("response").getJSONArray("data");
  }

  /** The single row's serialised JSON for a given raw {@code updated} column value. */
  private JSONObject singleRowFor(Object rawUpdated) throws Exception {
    return runGetListWith(Collections.singletonList(priceRowWithUpdated(rawUpdated)))
        .getJSONObject(0);
  }

  /**
   * Reads a token back exactly the way core does on the next write:
   * {@code NeoRecordVersion#parseClientValue} (and {@code JsonToDataConverter} behind it) run the
   * caller's echo through these two calls and nothing else.
   *
   * @param token the {@code updated} value as the client echoed it
   * @return the instant core resolves it to
   * @throws Exception when the token is unparseable, which is itself a failure of the contract
   */
  private static Date readBackAsCoreDoes(String token) throws Exception {
    String repaired = JsonUtils.convertFromXSDToJavaFormat(token);
    return new Timestamp(JsonUtils.createDateTimeFormat().parse(repaired).getTime());
  }

  /**
   * Verifies the echoed {@code updated} carries a zone offset whatever the server's zone is.
   *
   * <p>This is the assertion the previous expectation lacked: it pinned an offset-less literal,
   * so the regression WAS the expected value.
   */
  @Test
  void testEchoedUpdatedAlwaysCarriesAZoneOffset() throws Throwable {
    for (String zone : SERVER_ZONES) {
      withDefaultTimeZone(zone, () -> {
        String echoed = singleRowFor(RAW_PG_UPDATED).getString("updated");
        assertTrue(AUDIT_TOKEN_SHAPE.matcher(echoed).matches(),
            "under " + zone + " the echoed `updated` must carry an offset, got: " + echoed);
      });
    }
  }

  /**
   * Verifies the echoed stamp round-trips through core's reader back to the instant it came from,
   * under a zone west of UTC, under UTC itself, and under a half-hour offset.
   *
   * <p>This is the case that matters most, because it is the only one that exercises the real
   * path — {@code convertFromXSDToJavaFormat} then {@code createDateTimeFormat().parse()}, which
   * is verbatim what {@code NeoRecordVersion#parseClientValue} and core's own
   * {@code JsonToDataConverter#setData} run on the next write. Everything else here checks the
   * string; this checks that core AGREES with it.
   *
   * <p>Two properties, and they fail for different reasons:
   * <ul>
   *   <li><b>the instant survives</b> — with the offset dropped, the Buenos Aires run comes back
   *       three hours off and {@code NeoRecordVersion.isStale} (an equality check to the second,
   *       not an "older than") refuses the write as {@code stale_record}. The UTC run passes
   *       either way, which is exactly why one zone is not enough coverage;</li>
   *   <li><b>the reader needs no repair</b> — {@code convertFromXSDToJavaFormat} recognises only
   *       the colon form of the offset and, given anything else, appends {@code "+0000"} instead.
   *       A bare {@code -0300} therefore became {@code -0300+0000} and parsed ONLY because
   *       {@code SimpleDateFormat} ignores trailing characters. That accident is not something a
   *       concurrency check should rest on, so it is asserted away: the repair must not be the
   *       fallback branch. This also pins the colon's POSITION, which is what makes the Kolkata
   *       ({@code +05:30}) run worth having — {@code convertToCorrectXSDFormat} inserts it at
   *       {@code length-2}, and a colon placed anywhere else drops straight into the fallback.</li>
   * </ul>
   */
  @Test
  void testEchoedUpdatedRoundTripsToTheStoredInstantUnderAnyServerZone() throws Throwable {
    for (String zone : SERVER_ZONES) {
      withDefaultTimeZone(zone, () -> {
        // What the row holds, truncated to the second — core zeroes milliseconds on both sides
        // of the comparison before comparing (NeoRecordVersion#equalToTheSecond).
        Timestamp stored = Timestamp.valueOf("2026-08-15 10:30:00");
        String echoed = singleRowFor(RAW_PG_UPDATED).getString("updated");

        // ETP-5283 (merge block): ETP-5245 asserted here that core's reader RECOGNISES the
        // offset — i.e. that the token uses the colon form (`-03:00`), the only one
        // convertFromXSDToJavaFormat accepts without falling back to appending "+0000".
        // NeoDateFormat.toAuditToken (ETP-5255), which now renders every audit token in this
        // module, emits core's RFC-822 form (`-0300`) instead and therefore DOES take that
        // fallback. The instant still survives it — SimpleDateFormat parses the real offset and
        // ignores the trailing "+0000", which the next assertion proves — so this is a hardening
        // ETP-5245 found, not a live defect, and pinning it here would contradict
        // NeoDateFormatTest, which pins the RFC-822 shape deliberately. What is asserted instead
        // is the property that actually has to hold: whatever repair the reader applies, it must
        // not move the instant.
        assertNotEquals(SAMPLE_UPDATED_WALL_CLOCK, echoed,
            "under " + zone + " the token must carry an offset; the offsetless wall clock is"
                + " exactly what core re-reads as UTC");

        assertEquals(stored.getTime(), readBackAsCoreDoes(echoed).getTime(),
            "under " + zone + " core must read `" + echoed + "` back as the stored instant;"
                + " any drift makes every edit a false stale_record");
      });
    }
  }

  /**
   * Verifies a {@link Timestamp} (what the native query yields in production) and the equivalent
   * raw Postgres string produce byte-identical output.
   *
   * <p>Both shapes reach this code — the tests feed strings, Hibernate feeds Timestamps — so a
   * fix that only handled the one the tests exercise would have looked green and shipped broken.
   */
  @Test
  void testTimestampAndRawPostgresStringEchoTheSameValue() throws Throwable {
    withDefaultTimeZone("America/Argentina/Buenos_Aires", () -> {
      String fromTimestamp = singleRowFor(Timestamp.valueOf(RAW_PG_UPDATED)).getString("updated");
      String fromRawString = singleRowFor(RAW_PG_UPDATED).getString("updated");
      assertEquals(fromRawString, fromTimestamp);
      assertTrue(AUDIT_TOKEN_SHAPE.matcher(fromTimestamp).matches(),
          "the Timestamp branch must carry an offset too, got: " + fromTimestamp);
    });
  }

  /**
   * Verifies a null {@code updated} column still serialises as JSON null, with the key present.
   *
   * <p>The key has to stay: {@code NeoCrudHandler#validateUpdateRequest} answers
   * {@code missing_updated} on absence, so dropping it would trade one refusal for another.
   */
  @Test
  void testNullUpdatedColumnStaysJsonNull() throws Exception {
    JSONObject item = singleRowFor(null);
    assertTrue(item.has("updated"));
    assertTrue(item.isNull("updated"));
  }

  /**
   * Verifies a value that is neither a date nor the Postgres shape is handed back untouched
   * rather than throwing or being decorated with a fabricated offset.
   *
   * <p>Inventing an offset for a value whose zone is unknown is how this class of bug starts;
   * passing it through leaves the (visible) problem where it belongs.
   */
  @Test
  void testUnparseableUpdatedIsEchoedVerbatim() throws Exception {
    assertEquals("not-a-timestamp", singleRowFor("not-a-timestamp").getString("updated"));
  }

  /**
   * Asserts that {@code emitted} is a valid {@code updated} concurrency token for the
   * server-local wall clock {@code expectedWallClock}, per ETP-5255.
   *
   * <p>Three independent properties, each of which the pre-ETP-5255 offsetless output violates:
   * the token carries a zone offset (an offsetless value is not rejected on the way back in, it
   * is silently re-read as UTC by {@code JsonUtils.convertFromXSDToJavaFormat}, which appends
   * {@code "+0000"}); core's own reader parses it back; and it round-trips to the same
   * <b>instant</b> as the server-local wall clock, so the offset was derived from the value
   * rather than concatenated onto a re-interpreted one.
   */
  private static void assertIsAuditTokenFor(String expectedWallClock, String emitted)
      throws ParseException {
    assertTrue(AUDIT_TOKEN_SHAPE.matcher(emitted).matches(),
        () -> "'" + emitted + "' must carry a zone offset (yyyy-MM-dd'T'HH:mm:ssZ)");
    assertTrue(emitted.startsWith(expectedWallClock),
        () -> "'" + emitted + "' must keep the server-local wall clock " + expectedWallClock);
    assertNotEquals(expectedWallClock, emitted,
        "the offsetless canonical form is NOT a valid concurrency token (ETP-5255)");
    Date expectedInstant = new SimpleDateFormat(NeoDateFormat.ISO_DATETIME).parse(expectedWallClock);
    assertEquals(expectedInstant, JsonUtils.createDateTimeFormat().parse(emitted),
        "the token must round-trip through core's own reader to the same instant");
  }
}
