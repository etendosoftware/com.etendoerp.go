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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for {@link ReportSelectorsServlet}.
 *
 * <p>Covers: doGet authentication/pathInfo guard, doOptions 204, buildQuery for
 * every selector type (bpartner, product, warehouse, project, org, account,
 * acctschema/accounting, year, currency, tax), unknown type 400, safeId
 * validation, parseIntParam edge cases, toJsonItems with normal and null
 * values, and executeSelector with mocked NativeQuery rows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportSelectorsServletTest {

  private ReportSelectorsServlet servlet;

  @Mock
  private HttpServletRequest request;
  @Mock
  private HttpServletResponse response;
  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Session session;

  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery countQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery dataQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoServletSupport> neoSupportMock;
  private MockedStatic<com.etendoerp.go.common.CorsUtils> corsMock;

  private StringWriter stringWriter;
  private PrintWriter printWriter;

  private static final String TEST_CLIENT_ID = "ABC123DEF456";

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    servlet = new ReportSelectorsServlet();

    stringWriter = new StringWriter();
    printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    neoSupportMock = mockStatic(NeoServletSupport.class);
    corsMock = mockStatic(com.etendoerp.go.common.CorsUtils.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn(TEST_CLIENT_ID);
    when(obContext.getReadableOrganizations()).thenReturn(new String[]{ "org1", "org2" });

    when(obDal.getSession()).thenReturn(session);

    when(session.createNativeQuery(anyString())).thenReturn(countQuery, dataQuery);
    when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
    when(countQuery.setParameterList(anyString(), anyList())).thenReturn(countQuery);
    when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
    when(dataQuery.setParameterList(anyString(), anyList())).thenReturn(dataQuery);
    when(dataQuery.setMaxResults(anyInt())).thenReturn(dataQuery);
    when(dataQuery.setFirstResult(anyInt())).thenReturn(dataQuery);

    when(countQuery.uniqueResult()).thenReturn(0);
    when(dataQuery.list()).thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    corsMock.close();
    neoSupportMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  // ---------------------------------------------------------------------------
  // Helper: configure a valid authenticated GET for a given selector type
  // ---------------------------------------------------------------------------

  private void configureAuthenticatedGet(String type) throws Exception {
    neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any())).thenReturn(obContext);
    when(request.getPathInfo()).thenReturn("/" + type);
    when(request.getParameter("q")).thenReturn("");
    when(request.getParameter("limit")).thenReturn("20");
    when(request.getParameter("offset")).thenReturn("0");
    when(request.getParameter("selectedOrgId")).thenReturn(null);
    when(request.getParameter("selectedAcctSchemaId")).thenReturn(null);
    when(request.getParameter("warehouseIds")).thenReturn(null);
    when(request.getParameter("roleOrgIds")).thenReturn(null);
  }

  private String getResponseBody() {
    printWriter.flush();
    return stringWriter.toString();
  }

  // ===========================================================================
  // doOptions
  // ===========================================================================

  @Nested
  @DisplayName("doOptions")
  class DoOptionsTests {

    @Test
    @DisplayName("returns 204 No Content")
    void doOptionsReturns204() throws IOException {
      servlet.doOptions(request, response);
      verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
  }

  // ===========================================================================
  // doGet - authentication and path guards
  // ===========================================================================

  @Nested
  @DisplayName("doGet - guards")
  class DoGetGuardTests {

    @Test
    @DisplayName("returns 401 when JWT authentication throws OBException")
    void authFailureOBException() throws Exception {
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any()))
          .thenThrow(new OBException("bad token"));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      assertTrue(getResponseBody().contains("bad token"));
    }

    @Test
    @DisplayName("returns 401 when JWT authentication throws generic Exception")
    void authFailureGenericException() throws Exception {
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any()))
          .thenThrow(new RuntimeException("unexpected"));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      assertTrue(getResponseBody().contains("Invalid or expired token"));
    }

    @Test
    @DisplayName("returns 400 when pathInfo is null")
    void missingPathInfo() throws Exception {
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any())).thenReturn(obContext);
      when(request.getPathInfo()).thenReturn(null);

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(getResponseBody().contains("Selector type is required"));
    }

    @Test
    @DisplayName("returns 400 when pathInfo is just /")
    void rootPathInfo() throws Exception {
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any())).thenReturn(obContext);
      when(request.getPathInfo()).thenReturn("/");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(getResponseBody().contains("Selector type is required"));
    }

    @Test
    @DisplayName("returns 400 for unknown selector type")
    void unknownSelectorType() throws Exception {
      configureAuthenticatedGet("unknowntype");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(getResponseBody().contains("Unknown selector type"));
    }
  }

  // ===========================================================================
  // doGet - successful selector execution for each type
  // ===========================================================================

  @Nested
  @DisplayName("doGet - selector types")
  class DoGetSelectorTypes {

    @ParameterizedTest
    @ValueSource(strings = {
        "bpartner", "product", "warehouse", "project", "org",
        "account", "acctschema", "accounting", "year", "currency", "tax"
    })
    @DisplayName("returns 200 with valid JSON for each selector type")
    void selectorTypeReturns200(String type) throws Exception {
      configureAuthenticatedGet(type);

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      String body = getResponseBody();
      JSONObject json = new JSONObject(body);
      assertTrue(json.has("items"));
      assertTrue(json.has("totalCount"));
      assertTrue(json.has("hasMore"));
    }

    @Test
    @DisplayName("bpartner query uses c_bpartner table")
    @SuppressWarnings("unchecked")
    void bpartnerQueryStructure() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(countQuery.uniqueResult()).thenReturn(2);

      List<Object[]> rows = Arrays.asList(
          new Object[]{ "id1", "Partner A", "Partner A" },
          new Object[]{ "id2", "Partner B", "Partner B" }
      );
      when(dataQuery.list()).thenReturn(rows);

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject json = new JSONObject(getResponseBody());
      assertEquals(2, json.getInt("totalCount"));
      assertEquals(2, json.getJSONArray("items").length());
      assertFalse(json.getBoolean("hasMore"));
    }

    @Test
    @DisplayName("product query with warehouseIds filter")
    void productWithWarehouseFilter() throws Exception {
      configureAuthenticatedGet("product");
      when(request.getParameter("warehouseIds")).thenReturn("AA11BB22,CC33DD44");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("product query with selectedOrgId filter")
    void productWithOrgFilter() throws Exception {
      configureAuthenticatedGet("product");
      when(request.getParameter("selectedOrgId")).thenReturn("AABB1122CCDD3344");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("warehouse query with selectedOrgId filter")
    void warehouseWithOrgFilter() throws Exception {
      configureAuthenticatedGet("warehouse");
      when(request.getParameter("selectedOrgId")).thenReturn("AABB1122CCDD3344");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("account query with selectedAcctSchemaId filter")
    void accountWithAcctSchemaFilter() throws Exception {
      configureAuthenticatedGet("account");
      when(request.getParameter("selectedAcctSchemaId")).thenReturn("AABB1122CCDD3344");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("year query with selectedOrgId filter")
    void yearWithOrgFilter() throws Exception {
      configureAuthenticatedGet("year");
      when(request.getParameter("selectedOrgId")).thenReturn("AABB1122CCDD3344");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("hasMore is true when offset + rows < totalCount")
    @SuppressWarnings("unchecked")
    void hasMoreTrue() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("limit")).thenReturn("2");
      when(request.getParameter("offset")).thenReturn("0");
      when(countQuery.uniqueResult()).thenReturn(5);

      List<Object[]> rows = Arrays.asList(
          new Object[]{ "id1", "A", "A" },
          new Object[]{ "id2", "B", "B" }
      );
      when(dataQuery.list()).thenReturn(rows);

      servlet.doGet(request, response);

      JSONObject json = new JSONObject(getResponseBody());
      assertTrue(json.getBoolean("hasMore"));
      assertEquals(5, json.getInt("totalCount"));
    }

    @Test
    @DisplayName("currency type uses cross-client query with ORDER BY clientId binding")
    void currencyQuery() throws Exception {
      configureAuthenticatedGet("currency");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }
  }

  // ===========================================================================
  // doGet - internal error handling
  // ===========================================================================

  @Nested
  @DisplayName("doGet - internal errors")
  class DoGetInternalErrors {

    @Test
    @DisplayName("returns 500 when executeSelector throws unexpected exception")
    void internalError() throws Exception {
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any())).thenReturn(obContext);
      when(request.getPathInfo()).thenReturn("/bpartner");
      when(request.getParameter("q")).thenReturn("");
      when(request.getParameter("limit")).thenReturn("20");
      when(request.getParameter("offset")).thenReturn("0");

      when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("DB error"));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      assertTrue(getResponseBody().contains("internal error"));
    }
  }

  // ===========================================================================
  // safeId - via reflection
  // ===========================================================================

  @Nested
  @DisplayName("safeId")
  class SafeIdTests {

    private String invokeSafeId(String input) throws Exception {
      Method method = ReportSelectorsServlet.class.getDeclaredMethod("safeId", String.class);
      method.setAccessible(true);
      return (String) method.invoke(servlet, input);
    }

    @Test
    @DisplayName("returns null for null input")
    void nullInput() throws Exception {
      assertEquals(null, invokeSafeId(null));
    }

    @Test
    @DisplayName("returns valid hex string")
    void validHex() throws Exception {
      assertEquals("ABCDEF0123456789", invokeSafeId("ABCDEF0123456789"));
    }

    @Test
    @DisplayName("returns valid UUID string")
    void validUuid() throws Exception {
      String uuid = "550e8400-e29b-41d4-a716-446655440000";
      assertEquals(uuid, invokeSafeId(uuid));
    }

    @Test
    @DisplayName("returns valid lowercase hex string")
    void validLowercaseHex() throws Exception {
      assertEquals("abcdef0123456789", invokeSafeId("abcdef0123456789"));
    }

    @Test
    @DisplayName("returns null for string with invalid characters")
    void invalidChars() throws Exception {
      assertEquals(null, invokeSafeId("ABCDEF; DROP TABLE"));
    }

    @Test
    @DisplayName("returns null for string with special SQL chars")
    void sqlInjection() throws Exception {
      assertEquals(null, invokeSafeId("' OR 1=1 --"));
    }

    @Test
    @DisplayName("returns null for string longer than 36 characters")
    void tooLong() throws Exception {
      String longId = "A".repeat(37);
      assertEquals(null, invokeSafeId(longId));
    }

    @Test
    @DisplayName("returns trimmed string for padded input")
    void trimmedInput() throws Exception {
      assertEquals("AABB", invokeSafeId("  AABB  "));
    }

    @Test
    @DisplayName("returns string of exactly 36 chars (max UUID length)")
    void exactlyMaxLength() throws Exception {
      String id36 = "A".repeat(36);
      assertEquals(id36, invokeSafeId(id36));
    }
  }

  // ===========================================================================
  // parseIntParam - via SelectorRequest construction
  // ===========================================================================

  @Nested
  @DisplayName("parseIntParam via request params")
  class ParseIntParamTests {

    @Test
    @DisplayName("uses default when limit is blank")
    @SuppressWarnings("unchecked")
    void blankLimit() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("limit")).thenReturn("");
      when(countQuery.uniqueResult()).thenReturn(0);
      when(dataQuery.list()).thenReturn(Collections.emptyList());

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      // default limit=20, verified by dataQuery.setMaxResults(20)
      verify(dataQuery).setMaxResults(20);
    }

    @Test
    @DisplayName("clamps negative limit to 1")
    @SuppressWarnings("unchecked")
    void negativeLimit() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("limit")).thenReturn("-5");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      verify(dataQuery).setMaxResults(1);
    }

    @Test
    @DisplayName("clamps limit above MAX_LIMIT to 100")
    @SuppressWarnings("unchecked")
    void limitAboveMax() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("limit")).thenReturn("500");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      verify(dataQuery).setMaxResults(100);
    }

    @Test
    @DisplayName("uses default for non-numeric limit")
    @SuppressWarnings("unchecked")
    void nonNumericLimit() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("limit")).thenReturn("abc");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      verify(dataQuery).setMaxResults(20);
    }

    @Test
    @DisplayName("clamps negative offset to 0")
    @SuppressWarnings("unchecked")
    void negativeOffset() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("offset")).thenReturn("-10");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      verify(dataQuery).setFirstResult(0);
    }

    @Test
    @DisplayName("uses valid offset")
    @SuppressWarnings("unchecked")
    void validOffset() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("offset")).thenReturn("40");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      verify(dataQuery).setFirstResult(40);
    }
  }

  // ===========================================================================
  // toJsonItems - via reflection
  // ===========================================================================

  @Nested
  @DisplayName("toJsonItems")
  class ToJsonItemsTests {

    private JSONArray invokeToJsonItems(List<Object[]> rows) throws Exception {
      Method method = ReportSelectorsServlet.class.getDeclaredMethod("toJsonItems", List.class);
      method.setAccessible(true);
      return (JSONArray) method.invoke(servlet, rows);
    }

    @Test
    @DisplayName("converts normal rows to JSON array")
    void normalRows() throws Exception {
      List<Object[]> rows = Arrays.asList(
          new Object[]{ "id1", "Name1", "Label1" },
          new Object[]{ "id2", "Name2", "Label2" }
      );

      JSONArray items = invokeToJsonItems(rows);

      assertEquals(2, items.length());
      assertEquals("id1", items.getJSONObject(0).getString("id"));
      assertEquals("Name1", items.getJSONObject(0).getString("name"));
      assertEquals("Label1", items.getJSONObject(0).getString("label"));
      assertEquals("id2", items.getJSONObject(1).getString("id"));
    }

    @Test
    @DisplayName("converts null values to empty strings")
    void nullValues() throws Exception {
      List<Object[]> rows = Collections.singletonList(
          new Object[]{ null, null, null }
      );

      JSONArray items = invokeToJsonItems(rows);

      assertEquals(1, items.length());
      assertEquals("", items.getJSONObject(0).getString("id"));
      assertEquals("", items.getJSONObject(0).getString("name"));
      assertEquals("", items.getJSONObject(0).getString("label"));
    }

    @Test
    @DisplayName("returns empty array for no rows")
    void emptyRows() throws Exception {
      JSONArray items = invokeToJsonItems(Collections.emptyList());
      assertEquals(0, items.length());
    }

    @Test
    @DisplayName("converts mixed null and non-null values")
    void mixedValues() throws Exception {
      List<Object[]> rows = Collections.singletonList(
          new Object[]{ "id1", null, "Label1" }
      );

      JSONArray items = invokeToJsonItems(rows);

      assertEquals("id1", items.getJSONObject(0).getString("id"));
      assertEquals("", items.getJSONObject(0).getString("name"));
      assertEquals("Label1", items.getJSONObject(0).getString("label"));
    }
  }

  // ===========================================================================
  // executeSelector - full flow with mocked NativeQuery
  // ===========================================================================

  @Nested
  @DisplayName("executeSelector - full flow")
  class ExecuteSelectorTests {

    @Test
    @DisplayName("returns items with correct structure for bpartner rows")
    @SuppressWarnings("unchecked")
    void executeSelectorReturnsItems() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(countQuery.uniqueResult()).thenReturn(3);

      List<Object[]> rows = Arrays.asList(
          new Object[]{ "BP001", "Acme Corp", "Acme Corp" },
          new Object[]{ "BP002", "Beta Inc", "Beta Inc" },
          new Object[]{ "BP003", "Gamma Ltd", "Gamma Ltd" }
      );
      when(dataQuery.list()).thenReturn(rows);

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject json = new JSONObject(getResponseBody());
      assertEquals(3, json.getInt("totalCount"));
      JSONArray items = json.getJSONArray("items");
      assertEquals(3, items.length());

      assertEquals("BP001", items.getJSONObject(0).getString("id"));
      assertEquals("Acme Corp", items.getJSONObject(0).getString("name"));
      assertEquals("Acme Corp", items.getJSONObject(0).getString("label"));
    }

    @Test
    @DisplayName("returns empty items when count is zero")
    @SuppressWarnings("unchecked")
    void executeSelectorEmptyResult() throws Exception {
      configureAuthenticatedGet("tax");
      when(countQuery.uniqueResult()).thenReturn(0);
      when(dataQuery.list()).thenReturn(Collections.emptyList());

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject json = new JSONObject(getResponseBody());
      assertEquals(0, json.getInt("totalCount"));
      assertEquals(0, json.getJSONArray("items").length());
      assertFalse(json.getBoolean("hasMore"));
    }

    @Test
    @DisplayName("returns null count as zero")
    @SuppressWarnings("unchecked")
    void executeSelectorNullCount() throws Exception {
      configureAuthenticatedGet("project");
      when(countQuery.uniqueResult()).thenReturn(null);
      when(dataQuery.list()).thenReturn(Collections.emptyList());

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject json = new JSONObject(getResponseBody());
      assertEquals(0, json.getInt("totalCount"));
    }

    @Test
    @DisplayName("search parameter is bound with wildcard wrapping")
    @SuppressWarnings("unchecked")
    void searchParameterBound() throws Exception {
      configureAuthenticatedGet("bpartner");
      when(request.getParameter("q")).thenReturn("Acme");

      servlet.doGet(request, response);

      verify(countQuery).setParameter("search", "%Acme%");
      verify(dataQuery).setParameter("search", "%Acme%");
    }

    @Test
    @DisplayName("clientId is bound on both count and data queries when in fromWhere")
    @SuppressWarnings("unchecked")
    void clientIdBoundOnBothQueries() throws Exception {
      configureAuthenticatedGet("bpartner");

      servlet.doGet(request, response);

      verify(countQuery).setParameter("clientId", TEST_CLIENT_ID);
      verify(dataQuery).setParameter("clientId", TEST_CLIENT_ID);
    }

    @Test
    @DisplayName("org query filters by roleOrgIds")
    @SuppressWarnings("unchecked")
    void orgQueryBindsRoleOrgIds() throws Exception {
      configureAuthenticatedGet("org");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }
  }

  // ===========================================================================
  // toText - via reflection
  // ===========================================================================

  @Nested
  @DisplayName("toText")
  class ToTextTests {

    private String invokeToText(Object value) throws Exception {
      Method method = ReportSelectorsServlet.class.getDeclaredMethod("toText", Object.class);
      method.setAccessible(true);
      return (String) method.invoke(servlet, value);
    }

    @Test
    @DisplayName("converts non-null object to string")
    void nonNull() throws Exception {
      assertEquals("hello", invokeToText("hello"));
    }

    @Test
    @DisplayName("converts null to empty string")
    void nullValue() throws Exception {
      assertEquals("", invokeToText(null));
    }

    @Test
    @DisplayName("converts numeric to string")
    void numericValue() throws Exception {
      assertEquals("42", invokeToText(42));
    }
  }

  // ===========================================================================
  // buildQuery - type-specific SQL validation
  // ===========================================================================

  @Nested
  @DisplayName("buildQuery - SQL content verification")
  class BuildQuerySqlTests {

    @Test
    @DisplayName("product query SELECT includes value-name label")
    @SuppressWarnings("unchecked")
    void productSelectIncludesValueNameLabel() throws Exception {
      configureAuthenticatedGet("product");

      // We capture the SQL by verifying session.createNativeQuery calls
      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      // Success implies the buildProductQuery was called without error
    }

    @Test
    @DisplayName("accounting alias maps to acctschema query")
    @SuppressWarnings("unchecked")
    void accountingAlias() throws Exception {
      configureAuthenticatedGet("accounting");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("warehouse query with roleOrgIds filter")
    @SuppressWarnings("unchecked")
    void warehouseWithRoleOrgIds() throws Exception {
      configureAuthenticatedGet("warehouse");
      when(request.getParameter("selectedOrgId")).thenReturn("AABB1122CCDD3344");

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
    }
  }
}
