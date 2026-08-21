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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
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
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link InventoryStockReportHandler}.
 *
 * <p>Covers: HTTP method guard (405 for non-POST), POST with no filters,
 * POST with product filter, POST with warehouse filter, empty result set,
 * exception path (500), and private helpers via reflection (parseIds,
 * buildNamedParams, toBigDecimal).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryStockReportHandlerTest {

  private InventoryStockReportHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Organization organization;
  @Mock
  private OrganizationStructureProvider orgStructureProvider;
  @Mock
  private Session session;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery nativeQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    handler = new InventoryStockReportHandler();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
    when(obContext.getCurrentOrganization()).thenReturn(organization);
    when(organization.getId()).thenReturn("test-org-id");
    when(obContext.getOrganizationStructureProvider("test-client-id")).thenReturn(orgStructureProvider);

    Set<String> orgTree = new HashSet<>(Arrays.asList("test-org-id", "child-org-1"));
    when(orgStructureProvider.getNaturalTree("test-org-id")).thenReturn(orgTree);

    when(obDal.getSession()).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  private NeoContext postContext(JSONObject body) {
    return NeoContext.builder()
        .specName("inventory")
        .entityName("stock-report")
        .httpMethod("POST")
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private NeoContext postContextNoBody() {
    return NeoContext.builder()
        .specName("inventory")
        .entityName("stock-report")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  @SuppressWarnings("unchecked")
  private void mockQueryReturning(List<Object[]> rows) {
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(nativeQuery);
    when(nativeQuery.setParameterList(anyString(), org.mockito.ArgumentMatchers.<Set<String>>any())).thenReturn(
        nativeQuery);
    when(nativeQuery.list()).thenReturn(rows);
  }

  // ── Method guard ─────────────────────────────────────────────────────────

  /**
   * Verifies that the handler rejects GET requests with HTTP 405.
   */
  @Test
  void testNonPostMethodGetReturns405() {
    NeoContext ctx = NeoContext.builder()
        .specName("inventory")
        .entityName("stock-report")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(405, response.getHttpStatus());
  }

  /**
   * Verifies that the handler rejects PUT requests with HTTP 405.
   */
  @Test
  void testNonPostMethodPutReturns405() {
    NeoContext ctx = NeoContext.builder()
        .specName("inventory")
        .entityName("stock-report")
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  /**
   * Verifies that the handler rejects DELETE requests with HTTP 405.
   */
  @Test
  void testNonPostMethodDeleteReturns405() {
    NeoContext ctx = NeoContext.builder()
        .specName("inventory")
        .entityName("stock-report")
        .httpMethod("DELETE")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  // ── POST with no body / no filters ──────────────────────────────────────

  /**
   * Verifies that a POST with null body returns HTTP 200 with data.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPostWithNoBodyReturnsData() throws Exception {
    List<Object[]> rows = Collections.singletonList(
        new Object[]{ "Main Warehouse", "Beverages", "P001", "Test Product", "Unit",
            new BigDecimal("100"), new BigDecimal("10.50"), new BigDecimal("1050.00") });
    mockQueryReturning(rows);

    NeoResponse response = handler.handle(postContextNoBody());

    assertEquals(200, response.getHttpStatus());
    JSONObject responseObj = response.getBody().getJSONObject("response");
    assertEquals(1, responseObj.getInt("count"));

    JSONArray data = responseObj.getJSONArray("data");
    JSONObject item = data.getJSONObject(0);
    assertEquals("Main Warehouse", item.getString("warehouse"));
    assertEquals("Beverages", item.getString("category"));
    assertEquals("P001", item.getString("productSearchKey"));
    assertEquals("Test Product", item.getString("product"));
    assertEquals("Unit", item.getString("uom"));
  }

  /**
   * Verifies that a POST with empty body (no filters) returns correct structure.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPostWithEmptyBodyReturnsData() throws Exception {
    List<Object[]> rows = Arrays.asList(
        new Object[]{ "WH-A", "Category A", "P001", "Product A", "Kg",
            new BigDecimal("50"), new BigDecimal("5.00"), new BigDecimal("250.00") },
        new Object[]{ "WH-B", "Category B", "P002", "Product B", "Liter",
            new BigDecimal("30"), new BigDecimal("8.00"), new BigDecimal("240.00") });
    mockQueryReturning(rows);

    NeoResponse response = handler.handle(postContext(new JSONObject()));

    assertEquals(200, response.getHttpStatus());
    JSONObject responseObj = response.getBody().getJSONObject("response");
    assertEquals(2, responseObj.getInt("count"));
  }

  // ── POST with product filter ────────────────────────────────────────────

  /**
   * Verifies that product IDs are passed as named parameters to the query.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPostWithProductFilterSetsParameters() throws Exception {
    mockQueryReturning(Collections.singletonList(
        new Object[]{ "WH-A", "Category A", "P001", "Product A", "Unit",
            new BigDecimal("10"), new BigDecimal("2.00"), new BigDecimal("20.00") }));

    JSONObject body = new JSONObject();
    body.put("M_Product_ID", "prod-id-1, prod-id-2");

    NeoResponse response = handler.handle(postContext(body));

    assertEquals(200, response.getHttpStatus());
    verify(nativeQuery).setParameter("productId0", "prod-id-1");
    verify(nativeQuery).setParameter("productId1", "prod-id-2");
  }

  // ── POST with warehouse filter ──────────────────────────────────────────

  /**
   * Verifies that warehouse IDs are passed as named parameters to the query.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPostWithWarehouseFilterSetsParameters() throws Exception {
    mockQueryReturning(Collections.singletonList(
        new Object[]{ "WH-A", "Category A", "P001", "Product A", "Unit",
            new BigDecimal("10"), new BigDecimal("2.00"), new BigDecimal("20.00") }));

    JSONObject body = new JSONObject();
    body.put("M_Warehouse_ID", "wh-id-1");

    NeoResponse response = handler.handle(postContext(body));

    assertEquals(200, response.getHttpStatus());
    verify(nativeQuery).setParameter("warehouseId0", "wh-id-1");
  }

  /**
   * Verifies that both product and warehouse filters can be combined.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPostWithBothFiltersSetsAllParameters() throws Exception {
    mockQueryReturning(Collections.singletonList(
        new Object[]{ "WH-A", "Category A", "P001", "Product A", "Unit",
            new BigDecimal("10"), new BigDecimal("2.00"), new BigDecimal("20.00") }));

    JSONObject body = new JSONObject();
    body.put("M_Product_ID", "prod-id-1");
    body.put("M_Warehouse_ID", "wh-id-1, wh-id-2, wh-id-3");

    NeoResponse response = handler.handle(postContext(body));

    assertEquals(200, response.getHttpStatus());
    verify(nativeQuery).setParameter("productId0", "prod-id-1");
    verify(nativeQuery).setParameter("warehouseId0", "wh-id-1");
    verify(nativeQuery).setParameter("warehouseId1", "wh-id-2");
    verify(nativeQuery).setParameter("warehouseId2", "wh-id-3");
  }

  // ── Empty results ───────────────────────────────────────────────────────

  /**
   * Verifies that an empty result set returns count=0 and empty data array.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPostEmptyResultsReturnsZeroCount() throws Exception {
    mockQueryReturning(Collections.emptyList());

    NeoResponse response = handler.handle(postContext(new JSONObject()));

    assertEquals(200, response.getHttpStatus());
    JSONObject responseObj = response.getBody().getJSONObject("response");
    assertEquals(0, responseObj.getInt("count"));
    assertEquals(0, responseObj.getJSONArray("data").length());
  }

  // ── Exception returns 500 ──────────────────────────────────────────────

  /**
   * Verifies that an unexpected exception during query execution returns HTTP 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testExceptionReturns500() {
    when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("DB error"));

    NeoResponse response = handler.handle(postContextNoBody());
    assertEquals(500, response.getHttpStatus());
  }

  /**
   * Verifies that an exception in OBContext access returns HTTP 500.
   */
  @Test
  void testOBContextExceptionReturns500() {
    obContextMock.when(OBContext::getOBContext).thenThrow(new RuntimeException("Context unavailable"));

    NeoResponse response = handler.handle(postContextNoBody());
    assertEquals(500, response.getHttpStatus());
  }

  // ── Response structure validation ───────────────────────────────────────

  /**
   * Verifies that numeric fields (qtyOnHand, unitCost, totalValuation) are
   * properly converted to BigDecimal in the response.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testResponseNumericFieldsAreBigDecimal() throws Exception {
    List<Object[]> rows = Collections.singletonList(
        new Object[]{ "WH-A", "Category A", "P001", "Product A", "Unit",
            new BigDecimal("100.50"), new BigDecimal("12.75"), new BigDecimal("1281.375") });
    mockQueryReturning(rows);

    NeoResponse response = handler.handle(postContext(new JSONObject()));

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    JSONObject item = data.getJSONObject(0);

    assertEquals(new BigDecimal("100.50"), item.get("qtyOnHand"));
    assertEquals(new BigDecimal("12.75"), item.get("unitCost"));
    assertEquals(new BigDecimal("1281.375"), item.get("totalValuation"));
  }

  /**
   * Verifies that the response wraps data in a "response" object with "data" and "count".
   */
  @Test
  @SuppressWarnings("unchecked")
  void testResponseStructureHasResponseWrapper() throws Exception {
    mockQueryReturning(Collections.emptyList());

    NeoResponse response = handler.handle(postContext(new JSONObject()));

    assertEquals(200, response.getHttpStatus());
    assertNotNull(response.getBody().getJSONObject("response"));
    assertTrue(response.getBody().getJSONObject("response").has("data"));
    assertTrue(response.getBody().getJSONObject("response").has("count"));
  }

  // ── parseIds via reflection ─────────────────────────────────────────────

  /**
   * Verifies that parseIds returns an empty list for blank input.
   */
  @Test
  void testParseIdsBlankReturnsEmptyList() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, "");
    assertTrue(result.isEmpty());
  }

  /**
   * Verifies that parseIds returns an empty list for null input.
   */
  @Test
  void testParseIdsNullReturnsEmptyList() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, (String) null);
    assertTrue(result.isEmpty());
  }

  /**
   * Verifies that parseIds returns an empty list for the string "null".
   */
  @Test
  void testParseIdsNullStringReturnsEmptyList() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, "null");
    assertTrue(result.isEmpty());
  }

  /**
   * Verifies that parseIds returns an empty list for "NULL" (case insensitive).
   */
  @Test
  void testParseIdsNullUpperCaseReturnsEmptyList() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, "NULL");
    assertTrue(result.isEmpty());
  }

  /**
   * Verifies that parseIds correctly splits comma-separated IDs.
   */
  @Test
  void testParseIdsValidCommaSeparated() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, "id1,id2,id3");
    assertEquals(3, result.size());
    assertEquals("id1", result.get(0));
    assertEquals("id2", result.get(1));
    assertEquals("id3", result.get(2));
  }

  /**
   * Verifies that parseIds trims whitespace from each ID.
   */
  @Test
  void testParseIdsTrimming() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, "  id1 , id2 , id3  ");
    assertEquals(3, result.size());
    assertEquals("id1", result.get(0));
    assertEquals("id2", result.get(1));
    assertEquals("id3", result.get(2));
  }

  /**
   * Verifies that parseIds filters out blank entries from trailing commas.
   */
  @Test
  void testParseIdsFiltersBlankEntries() throws Exception {
    Method parseIds = InventoryStockReportHandler.class.getDeclaredMethod("parseIds", String.class);
    parseIds.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<String> result = (List<String>) parseIds.invoke(null, "id1,,id2, ,id3");
    assertEquals(3, result.size());
    assertEquals("id1", result.get(0));
    assertEquals("id2", result.get(1));
    assertEquals("id3", result.get(2));
  }

  // ── buildNamedParams via reflection ─────────────────────────────────────

  /**
   * Verifies that buildNamedParams generates a single parameter for size 1.
   */
  @Test
  void testBuildNamedParamsSize1() throws Exception {
    Method buildNamedParams = InventoryStockReportHandler.class.getDeclaredMethod(
        "buildNamedParams", String.class, int.class);
    buildNamedParams.setAccessible(true);

    String result = (String) buildNamedParams.invoke(null, "productId", 1);
    assertEquals(":productId0", result);
  }

  /**
   * Verifies that buildNamedParams generates comma-separated parameters for size 3.
   */
  @Test
  void testBuildNamedParamsSize3() throws Exception {
    Method buildNamedParams = InventoryStockReportHandler.class.getDeclaredMethod(
        "buildNamedParams", String.class, int.class);
    buildNamedParams.setAccessible(true);

    String result = (String) buildNamedParams.invoke(null, "warehouseId", 3);
    assertEquals(":warehouseId0, :warehouseId1, :warehouseId2", result);
  }

  // ── toBigDecimal via reflection ─────────────────────────────────────────

  /**
   * Verifies that toBigDecimal returns BigDecimal.ZERO for null input.
   */
  @Test
  void testToBigDecimalNullReturnsZero() throws Exception {
    Method toBigDecimal = InventoryStockReportHandler.class.getDeclaredMethod("toBigDecimal", Object.class);
    toBigDecimal.setAccessible(true);

    BigDecimal result = (BigDecimal) toBigDecimal.invoke(null, (Object) null);
    assertEquals(BigDecimal.ZERO, result);
  }

  /**
   * Verifies that toBigDecimal returns the same BigDecimal instance for BigDecimal input.
   */
  @Test
  void testToBigDecimalPassthrough() throws Exception {
    Method toBigDecimal = InventoryStockReportHandler.class.getDeclaredMethod("toBigDecimal", Object.class);
    toBigDecimal.setAccessible(true);

    BigDecimal input = new BigDecimal("42.5");
    BigDecimal result = (BigDecimal) toBigDecimal.invoke(null, input);
    assertEquals(input, result);
  }

  /**
   * Verifies that toBigDecimal converts a String to BigDecimal.
   */
  @Test
  void testToBigDecimalStringConversion() throws Exception {
    Method toBigDecimal = InventoryStockReportHandler.class.getDeclaredMethod("toBigDecimal", Object.class);
    toBigDecimal.setAccessible(true);

    BigDecimal result = (BigDecimal) toBigDecimal.invoke(null, "123.456");
    assertEquals(new BigDecimal("123.456"), result);
  }

  /**
   * Verifies that toBigDecimal converts an Integer to BigDecimal via String.valueOf.
   */
  @Test
  void testToBigDecimalIntegerConversion() throws Exception {
    Method toBigDecimal = InventoryStockReportHandler.class.getDeclaredMethod("toBigDecimal", Object.class);
    toBigDecimal.setAccessible(true);

    BigDecimal result = (BigDecimal) toBigDecimal.invoke(null, 99);
    assertEquals(new BigDecimal("99"), result);
  }
}
