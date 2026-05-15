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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoCrudHandler}.
 * Uses JUnit 5 (Jupiter) and Mockito.
 */
class NeoCrudHandlerTest {

  private NeoServlet servlet;
  private NeoCrudHandler handler;

  @BeforeEach
  void setUp() {
    servlet = mock(NeoServlet.class);
    handler = new NeoCrudHandler(servlet);
  }

  private SFEntity createMockEntity(boolean get, boolean getById, boolean post,
      boolean put, boolean patch, boolean delete) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.isGet()).thenReturn(get);
    when(entity.isGetByID()).thenReturn(getById);
    when(entity.isPost()).thenReturn(post);
    when(entity.isPut()).thenReturn(put);
    when(entity.isPatch()).thenReturn(patch);
    when(entity.isDelete()).thenReturn(delete);
    return entity;
  }

  private NeoContext buildContext(String method, String recordId, Tab adTab,
      SFEntity sfEntity, JSONObject body, Map<String, String> queryParams) {
    return NeoContext.builder()
        .specName("testSpec")
        .entityName("testEntity")
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .queryParams(queryParams)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(mock(OBContext.class))
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static Object invokePrivate(Object target, String methodName,
      Class<?>[] paramTypes, Object... args) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private static ServletInputStream toServletInputStream(String content) {
    ByteArrayInputStream bais = new ByteArrayInputStream(
        content.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
      @Override
      public int read() {
        return bais.read();
      }

      @Override
      public boolean isFinished() {
        return bais.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        // no-op
      }
    };
  }

  // -------------------------------------------------------------------------
  // isMethodEnabled tests (via reflection on private method)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isMethodEnabled")
  class IsMethodEnabled {

    private boolean invokeIsMethodEnabled(String method, SFEntity entity) throws Exception {
      return (boolean) invokePrivate(handler, "isMethodEnabled",
          new Class<?>[] { String.class, SFEntity.class }, method, entity);
    }

    @Test
    @DisplayName("GET returns true when entity.isGet() is true")
    void getEnabledWhenIsGetTrue() throws Exception {
      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      assertTrue(invokeIsMethodEnabled("GET", entity));
    }

    @Test
    @DisplayName("GET returns true when entity.isGetByID() is true")
    void getEnabledWhenIsGetByIdTrue() throws Exception {
      SFEntity entity = createMockEntity(false, true, false, false, false, false);
      assertTrue(invokeIsMethodEnabled("GET", entity));
    }

    @Test
    @DisplayName("POST returns true when entity.isPost() is true")
    void postEnabled() throws Exception {
      SFEntity entity = createMockEntity(false, false, true, false, false, false);
      assertTrue(invokeIsMethodEnabled("POST", entity));
    }

    @Test
    @DisplayName("PUT returns true when entity.isPut() is true")
    void putEnabled() throws Exception {
      SFEntity entity = createMockEntity(false, false, false, true, false, false);
      assertTrue(invokeIsMethodEnabled("PUT", entity));
    }

    @Test
    @DisplayName("PATCH returns true when entity.isPatch() is true")
    void patchEnabled() throws Exception {
      SFEntity entity = createMockEntity(false, false, false, false, true, false);
      assertTrue(invokeIsMethodEnabled("PATCH", entity));
    }

    @Test
    @DisplayName("DELETE returns true when entity.isDelete() is true")
    void deleteEnabled() throws Exception {
      SFEntity entity = createMockEntity(false, false, false, false, false, true);
      assertTrue(invokeIsMethodEnabled("DELETE", entity));
    }

    @Test
    @DisplayName("Unknown method returns false")
    void unknownMethodReturnsFalse() throws Exception {
      SFEntity entity = createMockEntity(true, true, true, true, true, true);
      assertEquals(false, invokeIsMethodEnabled("OPTIONS", entity));
    }

    @Test
    @DisplayName("GET returns false when both isGet and isGetByID are false")
    void getDisabledWhenBothFalse() throws Exception {
      SFEntity entity = createMockEntity(false, false, false, false, false, false);
      assertEquals(false, invokeIsMethodEnabled("GET", entity));
    }
  }

  // -------------------------------------------------------------------------
  // handleWindowEntityCrud tests
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleWindowEntityCrud")
  class HandleWindowEntityCrud {

    @Test
    @DisplayName("Returns 404 when entity is not found in spec")
    void entityNotFoundSends404() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");
      when(servlet.findEntity("SPEC-1", "missing")).thenReturn(null);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "missing", null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);

      handler.handleWindowEntityCrud(spec, pathInfo, "GET", request, response);

      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
          contains("Entity not found in spec"));
    }

    @Test
    @DisplayName("Returns 405 when method is not enabled on entity")
    void methodNotAllowedSends405() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      Tab adTab = mock(Tab.class);
      when(entity.getADTab()).thenReturn(adTab);
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);

      handler.handleWindowEntityCrud(spec, pathInfo, "DELETE", request, response);

      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
          contains("DELETE not enabled for orders"));
    }
  }

  // -------------------------------------------------------------------------
  // handleDefault tests
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleDefault")
  class HandleDefault {

    @Test
    @DisplayName("Returns 500 when adTab is null")
    void noAdTabReturns500() {
      NeoContext context = buildContext("GET", null, null,
          mock(SFEntity.class), null, null);

      NeoResponse result = handler.handleDefault(context);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // validatePostRequest tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("validatePostRequest")
  class ValidatePostRequest {

    private NeoResponse invokeValidatePost(NeoContext context) throws Exception {
      return (NeoResponse) invokePrivate(handler, "validatePostRequest",
          new Class<?>[] { NeoContext.class }, context);
    }

    @Test
    @DisplayName("Returns error when POST includes a record ID")
    void postWithRecordIdReturnsError() throws Exception {
      NeoContext context = buildContext("POST", "REC-123", mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidatePost(context);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }

    @Test
    @DisplayName("Returns null when POST has no record ID")
    void postWithoutRecordIdReturnsNull() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidatePost(context);

      assertNull(result);
    }
  }

  // -------------------------------------------------------------------------
  // validateUpdateRequest tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("validateUpdateRequest")
  class ValidateUpdateRequest {

    private NeoResponse invokeValidateUpdate(NeoContext context) throws Exception {
      return (NeoResponse) invokePrivate(handler, "validateUpdateRequest",
          new Class<?>[] { NeoContext.class }, context);
    }

    @Test
    @DisplayName("Returns error when PUT/PATCH has no record ID")
    void updateWithoutRecordIdReturnsError() throws Exception {
      NeoContext context = buildContext("PUT", null, mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidateUpdate(context);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }

    @Test
    @DisplayName("Returns null when PUT/PATCH has record ID")
    void updateWithRecordIdReturnsNull() throws Exception {
      NeoContext context = buildContext("PATCH", "REC-123", mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidateUpdate(context);

      assertNull(result);
    }
  }

  // -------------------------------------------------------------------------
  // parseIntOrDefault tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseIntOrDefault")
  class ParseIntOrDefault {

    private int invokeParseIntOrDefault(String raw, int fallback) throws Exception {
      Method method = NeoCrudHandler.class.getDeclaredMethod(
          "parseIntOrDefault", String.class, int.class);
      method.setAccessible(true);
      return (int) method.invoke(null, raw, fallback);
    }

    @ParameterizedTest
    @CsvSource({
        "'42', 0, 42",
        "'', 10, 10",
        "'abc', 5, 5",
        "' 7 ', 0, 7"
    })
    @DisplayName("Parses valid integers and falls back for invalid values")
    void parsesCorrectly(String raw, int fallback, int expected) throws Exception {
      // CsvSource wraps null-like in quotes; handle blank
      String input = raw.isEmpty() ? "" : raw;
      assertEquals(expected, invokeParseIntOrDefault(input, fallback));
    }

    @Test
    @DisplayName("Returns fallback for null input")
    void nullReturnsFallback() throws Exception {
      assertEquals(99, invokeParseIntOrDefault(null, 99));
    }
  }

  // -------------------------------------------------------------------------
  // buildMissingRequiredFieldsResponse tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildMissingRequiredFieldsResponse")
  class BuildMissingRequiredFieldsResponse {

    private NeoResponse invokeBuildResponse(MissingRequiredFieldsException e) throws Exception {
      return (NeoResponse) invokePrivate(handler, "buildMissingRequiredFieldsResponse",
          new Class<?>[] { MissingRequiredFieldsException.class }, e);
    }

    @Test
    @DisplayName("Returns 400 with MISSING_REQUIRED_FIELDS code and field list")
    void buildsStructuredResponse() throws Exception {
      MissingRequiredFieldsException ex = new MissingRequiredFieldsException(
          Arrays.asList("bpartner", "priceList"));

      NeoResponse result = invokeBuildResponse(ex);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());

      JSONObject body = result.getBody();
      assertNotNull(body);
      JSONObject error = body.getJSONObject("error");
      assertEquals("MISSING_REQUIRED_FIELDS", error.getString("code"));
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, error.getInt("status"));

      JSONArray fields = error.getJSONArray("fields");
      assertEquals(2, fields.length());
      assertEquals("bpartner", fields.getString(0));
      assertEquals("priceList", fields.getString(1));
    }

    @Test
    @DisplayName("Handles empty field list")
    void handlesEmptyFieldList() throws Exception {
      MissingRequiredFieldsException ex = new MissingRequiredFieldsException(
          Collections.emptyList());

      NeoResponse result = invokeBuildResponse(ex);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      JSONArray fields = result.getBody().getJSONObject("error").getJSONArray("fields");
      assertEquals(0, fields.length());
    }
  }

  // -------------------------------------------------------------------------
  // applyPaginationDefaults tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("applyPaginationDefaults")
  class ApplyPaginationDefaults {

    @SuppressWarnings("unchecked")
    private void invokeApplyPaginationDefaults(Map<String, String> params) throws Exception {
      invokePrivate(handler, "applyPaginationDefaults",
          new Class<?>[] { Map.class }, params);
    }

    @Test
    @DisplayName("Adds startRow=0 and endRow=100 when not present")
    void addsDefaults() throws Exception {
      Map<String, String> params = new HashMap<>();
      invokeApplyPaginationDefaults(params);

      assertEquals("0", params.get("_startRow"));
      assertEquals("100", params.get("_endRow"));
    }

    @Test
    @DisplayName("Does not overwrite existing pagination parameters")
    void doesNotOverwrite() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("_startRow", "10");
      params.put("_endRow", "50");
      invokeApplyPaginationDefaults(params);

      assertEquals("10", params.get("_startRow"));
      assertEquals("50", params.get("_endRow"));
    }
  }

  // -------------------------------------------------------------------------
  // applyWhereClause tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("applyWhereClause")
  class ApplyWhereClause {

    private void invokeApplyWhereClause(Map<String, String> params,
        Tab adTab, String parentId) throws Exception {
      invokePrivate(handler, "applyWhereClause",
          new Class<?>[] { Map.class, Tab.class, String.class }, params, adTab, parentId);
    }

    @Test
    @DisplayName("Sets useAlias=true even when no clauses apply")
    void setsUseAlias() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null);

      assertEquals("true", params.get("_use_alias"));
    }

    @Test
    @DisplayName("Adds tab HQL where clause when present")
    void addsTabWhereClause() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null);

      String where = params.get("whereAndFilterClause");
      assertNotNull(where);
      assertTrue(where.contains("e.active = true"));
    }

    @Test
    @DisplayName("Appends neoWhere clause when present in params")
    void appendsNeoWhere() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      params.put("_neoWhere", "e.status = 'CO'");
      invokeApplyWhereClause(params, adTab, null);

      String where = params.get("whereAndFilterClause");
      assertNotNull(where);
      assertTrue(where.contains("e.status = 'CO'"));
      assertNull(params.get("_neoWhere"));
    }

    @Test
    @DisplayName("Combines tab where and neoWhere with AND")
    void combinesTabAndNeoWhere() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      params.put("_neoWhere", "e.status = 'CO'");
      invokeApplyWhereClause(params, adTab, null);

      String where = params.get("whereAndFilterClause");
      assertNotNull(where);
      assertTrue(where.contains("e.active = true"));
      assertTrue(where.contains(" and "));
      assertTrue(where.contains("e.status = 'CO'"));
    }
  }

  // -------------------------------------------------------------------------
  // parseAndAttachRequestBody tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parseAndAttachRequestBody")
  class ParseAndAttachRequestBody {

    private NeoContext invokeParseBody(NeoContext neoContext,
        HttpServletRequest request, HttpServletResponse response) throws Exception {
      return (NeoContext) invokePrivate(handler, "parseAndAttachRequestBody",
          new Class<?>[] { NeoContext.class, HttpServletRequest.class,
              HttpServletResponse.class },
          neoContext, request, response);
    }

    @Test
    @DisplayName("Returns same context when body is blank")
    void blankBodyReturnsSameContext() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(toServletInputStream("   "));

      NeoContext result = invokeParseBody(context, request, response);

      assertEquals(context, result);
    }

    @Test
    @DisplayName("Attaches parsed JSON body to new context")
    void validJsonAttachesBody() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(
          toServletInputStream("{\"name\":\"Order1\"}"));

      NeoContext result = invokeParseBody(context, request, response);

      assertNotNull(result);
      assertNotNull(result.getRequestBody());
      assertEquals("Order1", result.getRequestBody().getString("name"));
    }

    @Test
    @DisplayName("Returns null and sends 400 for malformed JSON")
    void invalidJsonReturnsNullAndSends400() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(toServletInputStream("{bad-json"));

      NeoContext result = invokeParseBody(context, request, response);

      assertNull(result);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          contains("Invalid JSON body"));
    }
  }

  // -------------------------------------------------------------------------
  // dispatchCrudRequest tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("dispatchCrudRequest")
  class DispatchCrudRequest {

    private NeoResponse invokeDispatch(SFEntity entity, NeoContext context,
        HttpServletRequest request, HttpServletResponse response) throws Exception {
      return (NeoResponse) invokePrivate(handler, "dispatchCrudRequest",
          new Class<?>[] { SFEntity.class, NeoContext.class,
              HttpServletRequest.class, HttpServletResponse.class },
          entity, context, request, response);
    }

    @Test
    @DisplayName("Delegates to handleWithHooks when javaQualifier is set")
    void delegatesToHooksWhenQualifierPresent() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      when(entity.getJavaQualifier()).thenReturn("myHandler");

      NeoContext context = buildContext("GET", null, mock(Tab.class),
          entity, null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      NeoResponse expected = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(eq("myHandler"), any(), any(), any()))
          .thenReturn(expected);

      NeoResponse result = invokeDispatch(entity, context, request, response);

      assertEquals(expected, result);
      verify(servlet).handleWithHooks(eq("myHandler"), eq(context), eq(request), eq(response));
    }
  }

  // -------------------------------------------------------------------------
  // checkJsonServiceResponse tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("checkJsonServiceResponse")
  class CheckJsonServiceResponse {

    private NeoResponse invokeCheckResponse(JSONObject responseJson) throws Exception {
      return (NeoResponse) invokePrivate(handler, "checkJsonServiceResponse",
          new Class<?>[] { JSONObject.class }, responseJson);
    }

    @Test
    @DisplayName("Returns null when no response wrapper present")
    void returnsNullWhenNoResponseWrapper() throws Exception {
      JSONObject json = new JSONObject();
      json.put("data", "some data");

      assertNull(invokeCheckResponse(json));
    }

    @Test
    @DisplayName("Returns null for successful status")
    void returnsNullForSuccessfulStatus() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", 0);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      assertNull(invokeCheckResponse(json));
    }

    @Test
    @DisplayName("Returns 500 error for failure status")
    void returns500ForFailureStatus() throws Exception {
      JSONObject error = new JSONObject();
      error.put("message", "Something failed");
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      inner.put("error", error);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      try (org.mockito.MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          org.mockito.Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn("Something failed");

        NeoResponse result = invokeCheckResponse(json);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("Returns 400 for validation error status")
    void returns400ForValidationError() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", -4);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      NeoResponse result = invokeCheckResponse(json);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // stripContactsPreCreateBillingDefaults tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("stripContactsPreCreateBillingDefaults")
  class StripContactsBilling {

    private void invokeStrip(JSONObject body, NeoContext context, Tab adTab) throws Exception {
      invokePrivate(handler, "stripContactsPreCreateBillingDefaults",
          new Class<?>[] { JSONObject.class, NeoContext.class, Tab.class },
          body, context, adTab);
    }

    @Test
    @DisplayName("Strips billing fields for contacts/businessPartner tab-level-0")
    void stripsBillingFieldsForContacts() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = NeoContext.builder()
          .specName("contacts")
          .entityName("businessPartner")
          .httpMethod("POST")
          .adTab(adTab)
          .endpointType(NeoEndpointType.CRUD)
          .build();

      JSONObject body = new JSONObject();
      body.put("priceList", "PL-1");
      body.put("priceList$_identifier", "Standard");
      body.put("paymentMethod", "PM-1");
      body.put("paymentMethod$_identifier", "Wire Transfer");
      body.put("someOtherField", "keep");

      invokeStrip(body, context, adTab);

      assertTrue(!body.has("priceList"));
      assertTrue(!body.has("priceList$_identifier"));
      assertTrue(!body.has("paymentMethod"));
      assertTrue(body.has("someOtherField"));
    }

    @Test
    @DisplayName("Does not strip for non-contacts spec")
    void doesNotStripForNonContactsSpec() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = NeoContext.builder()
          .specName("sales-order")
          .entityName("businessPartner")
          .httpMethod("POST")
          .adTab(adTab)
          .endpointType(NeoEndpointType.CRUD)
          .build();

      JSONObject body = new JSONObject();
      body.put("priceList", "PL-1");

      invokeStrip(body, context, adTab);

      assertTrue(body.has("priceList"));
    }

    @Test
    @DisplayName("Does not strip for child tabs (tabLevel > 0)")
    void doesNotStripForChildTabs() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(1L);

      NeoContext context = NeoContext.builder()
          .specName("contacts")
          .entityName("businessPartner")
          .httpMethod("POST")
          .adTab(adTab)
          .endpointType(NeoEndpointType.CRUD)
          .build();

      JSONObject body = new JSONObject();
      body.put("priceList", "PL-1");

      invokeStrip(body, context, adTab);

      assertTrue(body.has("priceList"));
    }

    @Test
    @DisplayName("Handles null arguments safely")
    void handlesNullArgs() throws Exception {
      invokeStrip(null, null, null);
      // no exception
    }
  }

  // -------------------------------------------------------------------------
  // toDistinctEntry tests (via reflection on static method)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("toDistinctEntry")
  class ToDistinctEntry {

    private JSONObject invokeToDistinctEntry(Object value) throws Exception {
      Method method = NeoCrudHandler.class.getDeclaredMethod("toDistinctEntry", Object.class);
      method.setAccessible(true);
      return (JSONObject) method.invoke(null, value);
    }

    @Test
    @DisplayName("Null value produces empty id and identifier")
    void nullValueProducesEmptyFields() throws Exception {
      JSONObject entry = invokeToDistinctEntry(null);
      assertEquals("", entry.getString("id"));
      assertEquals("", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("String value uses same value for id and identifier")
    void stringValueUsedForBothFields() throws Exception {
      JSONObject entry = invokeToDistinctEntry("Active");
      assertEquals("Active", entry.getString("id"));
      assertEquals("Active", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("Integer value is stringified for both fields")
    void integerValueStringified() throws Exception {
      JSONObject entry = invokeToDistinctEntry(42);
      assertEquals("42", entry.getString("id"));
      assertEquals("42", entry.getString("_identifier"));
    }
  }

  // -------------------------------------------------------------------------
  // resolveDistinctProperty tests (via reflection on static method)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveDistinctProperty")
  class ResolveDistinctProperty {

    @Test
    @DisplayName("Returns null for null entity")
    void returnsNullForNullEntity() throws Exception {
      Method method = NeoCrudHandler.class.getDeclaredMethod(
          "resolveDistinctProperty",
          org.openbravo.base.model.Entity.class, String.class);
      method.setAccessible(true);

      Object result = method.invoke(null, (org.openbravo.base.model.Entity) null, "field");
      assertNull(result);
    }
  }

  // -------------------------------------------------------------------------
  // resolveTokenFromParent tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveTokenFromParent")
  class ResolveTokenFromParent {

    private String invokeResolveToken(String token, String parentId,
        Object parentRecord, Object parentEntity, String parentTableName) throws Exception {
      return (String) invokePrivate(handler, "resolveTokenFromParent",
          new Class<?>[] { String.class, String.class,
              org.openbravo.base.structure.BaseOBObject.class,
              org.openbravo.base.model.Entity.class, String.class },
          token, parentId, parentRecord, parentEntity, parentTableName);
    }

    @Test
    @DisplayName("Returns parentId when parentRecord is null")
    void returnsParentIdWhenNoRecord() throws Exception {
      String result = invokeResolveToken("any_token", "PARENT-1", null, null, null);
      assertEquals("PARENT-1", result);
    }

    @Test
    @DisplayName("Returns parentId for table name PK token")
    void returnsParentIdForPkToken() throws Exception {
      org.openbravo.base.structure.BaseOBObject parentRecord =
          mock(org.openbravo.base.structure.BaseOBObject.class);

      String result = invokeResolveToken("c_order_id", "PARENT-1",
          parentRecord, null, "c_order");
      assertEquals("PARENT-1", result);
    }
  }

  // -------------------------------------------------------------------------
  // handleDistinctFetch tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleDistinctFetch")
  class HandleDistinctFetch {

    private NeoResponse invokeDistinctFetch(Tab adTab, Map<String, String> queryParams)
        throws Exception {
      return (NeoResponse) invokePrivate(handler, "handleDistinctFetch",
          new Class<?>[] { Tab.class, Map.class }, adTab, queryParams);
    }

    @Test
    @DisplayName("Returns 500 when tab is null")
    void nullTabReturns500() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");

      NeoResponse result = invokeDistinctFetch(null, params);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 400 when _distinct field name is blank")
    void blankFieldReturns400() throws Exception {
      Tab adTab = mock(Tab.class);
      org.openbravo.model.ad.datamodel.Table table =
          mock(org.openbravo.model.ad.datamodel.Table.class);
      when(adTab.getTable()).thenReturn(table);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "   ");

      NeoResponse result = invokeDistinctFetch(adTab, params);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // executePostCalloutCascade tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("executePostCalloutCascade")
  class ExecutePostCalloutCascade {

    @Test
    @DisplayName("Skips cascade when adTab is null")
    void skipsWhenTabNull() throws Exception {
      JSONObject body = new JSONObject();
      NeoContext context = buildContext("POST", null, null,
          mock(SFEntity.class), body, null);

      invokePrivate(handler, "executePostCalloutCascade",
          new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
              String.class, java.util.Set.class },
          body, null, context, null, Collections.emptySet());
      // no exception = success
    }

    @Test
    @DisplayName("Skips cascade when tabLevel is not 0")
    void skipsWhenChildTab() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(1L);

      JSONObject body = new JSONObject();
      NeoContext context = buildContext("POST", null, adTab,
          mock(SFEntity.class), body, null);

      invokePrivate(handler, "executePostCalloutCascade",
          new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
              String.class, java.util.Set.class },
          body, adTab, context, null, Collections.emptySet());
      // no exception = success
    }
  }
}
