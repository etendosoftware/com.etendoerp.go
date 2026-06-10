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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

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

    @Test
    @DisplayName("POST returns false when isPost is false")
    void postDisabledWhenFalse() throws Exception {
      SFEntity entity = createMockEntity(true, true, false, true, true, true);
      assertFalse(invokeIsMethodEnabled("POST", entity));
    }

    @Test
    @DisplayName("PUT returns false when isPut is false")
    void putDisabledWhenFalse() throws Exception {
      SFEntity entity = createMockEntity(true, true, true, false, true, true);
      assertFalse(invokeIsMethodEnabled("PUT", entity));
    }

    @Test
    @DisplayName("PATCH returns false when isPatch is false")
    void patchDisabledWhenFalse() throws Exception {
      SFEntity entity = createMockEntity(true, true, true, true, false, true);
      assertFalse(invokeIsMethodEnabled("PATCH", entity));
    }

    @Test
    @DisplayName("DELETE returns false when isDelete is false")
    void deleteDisabledWhenFalse() throws Exception {
      SFEntity entity = createMockEntity(true, true, true, true, true, false);
      assertFalse(invokeIsMethodEnabled("DELETE", entity));
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

    @Test
    @DisplayName("Parses body for POST and dispatches")
    void postParsesBodyAndDispatches() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(false, false, true, false, false, false);
      Tab adTab = mock(Tab.class);
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("myHook");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());

      NeoResponse hookResponse = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(eq("myHook"), any(), any(), any()))
          .thenReturn(hookResponse);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(
          toServletInputStream("{\"product\":\"P1\"}"));

      handler.handleWindowEntityCrud(spec, pathInfo, "POST", request, response);

      verify(servlet).writeResponse(eq(response), eq(hookResponse));
    }

    @Test
    @DisplayName("Parses body for PUT and dispatches")
    void putParsesBodyAndDispatches() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(false, false, false, true, false, false);
      Tab adTab = mock(Tab.class);
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("myHook");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());

      NeoResponse hookResponse = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(eq("myHook"), any(), any(), any()))
          .thenReturn(hookResponse);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", "REC-1");
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(
          toServletInputStream("{\"qty\":5}"));

      handler.handleWindowEntityCrud(spec, pathInfo, "PUT", request, response);

      verify(servlet).writeResponse(eq(response), eq(hookResponse));
    }

    @Test
    @DisplayName("Parses body for PATCH and dispatches")
    void patchParsesBodyAndDispatches() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(false, false, false, false, true, false);
      Tab adTab = mock(Tab.class);
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("myHook");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());

      NeoResponse hookResponse = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(eq("myHook"), any(), any(), any()))
          .thenReturn(hookResponse);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", "REC-1");
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(
          toServletInputStream("{\"status\":\"CO\"}"));

      handler.handleWindowEntityCrud(spec, pathInfo, "PATCH", request, response);

      verify(servlet).writeResponse(eq(response), eq(hookResponse));
    }

    @Test
    @DisplayName("Returns null and sends 400 when body is malformed JSON on POST")
    void malformedBodySends400() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(false, false, true, false, false, false);
      Tab adTab = mock(Tab.class);
      when(entity.getADTab()).thenReturn(adTab);
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(toServletInputStream("{not-valid"));

      handler.handleWindowEntityCrud(spec, pathInfo, "POST", request, response);

      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          contains("Invalid JSON body"));
      verify(servlet, never()).writeResponse(any(), any());
    }

    @Test
    @DisplayName("Does not write response when dispatchCrudRequest returns null")
    void nullDispatchResponseNotWritten() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      Tab adTab = mock(Tab.class);
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("myHook");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());
      when(servlet.handleWithHooks(anyString(), any(), any(), any())).thenReturn(null);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);

      handler.handleWindowEntityCrud(spec, pathInfo, "GET", request, response);

      verify(servlet, never()).writeResponse(any(), any());
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

    @Test
    @DisplayName("Returns 500 error body contains entity name when adTab is null")
    void noAdTabErrorContainsEntityName() throws Exception {
      NeoContext context = buildContext("GET", null, null,
          mock(SFEntity.class), null, null);

      NeoResponse result = handler.handleDefault(context);

      assertNotNull(result.getBody());
      String errorMsg = result.getBody().getJSONObject("error").getString("message");
      assertTrue(errorMsg.contains("testEntity"));
    }

    @Test
    @DisplayName("Catches generic exception and returns 500")
    void genericExceptionReturns500() {
      Tab adTab = mock(Tab.class);
      // table.getName() throws to simulate unexpected error
      when(adTab.getTable()).thenThrow(new RuntimeException("Unexpected error"));

      NeoContext context = buildContext("GET", null, adTab,
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
    @DisplayName("Error message mentions POST create must not include ID")
    void postWithRecordIdErrorMessage() throws Exception {
      NeoContext context = buildContext("POST", "REC-123", mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidatePost(context);

      String msg = result.getBody().getJSONObject("error").getString("message");
      assertTrue(msg.contains("POST"));
      assertTrue(msg.contains("record ID"));
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
    @DisplayName("Error message includes the method name")
    void updateErrorIncludesMethod() throws Exception {
      NeoContext context = buildContext("PATCH", null, mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidateUpdate(context);

      String msg = result.getBody().getJSONObject("error").getString("message");
      assertTrue(msg.contains("PATCH"));
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

    @Test
    @DisplayName("Returns fallback for whitespace-only input")
    void whitespaceReturnsFallback() throws Exception {
      assertEquals(50, invokeParseIntOrDefault("   ", 50));
    }

    @Test
    @DisplayName("Parses negative numbers")
    void parsesNegativeNumbers() throws Exception {
      assertEquals(-5, invokeParseIntOrDefault("-5", 0));
    }

    @Test
    @DisplayName("Returns fallback for decimal input")
    void decimalReturnsFallback() throws Exception {
      assertEquals(10, invokeParseIntOrDefault("3.14", 10));
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

    @Test
    @DisplayName("Response body has message field set to 'Missing required fields'")
    void responseHasMessage() throws Exception {
      MissingRequiredFieldsException ex = new MissingRequiredFieldsException(
          Arrays.asList("warehouse"));

      NeoResponse result = invokeBuildResponse(ex);

      JSONObject error = result.getBody().getJSONObject("error");
      assertEquals("Missing required fields", error.getString("message"));
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

    @Test
    @DisplayName("Adds only endRow when startRow is already present")
    void addsEndRowOnly() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("_startRow", "20");
      invokeApplyPaginationDefaults(params);

      assertEquals("20", params.get("_startRow"));
      assertEquals("100", params.get("_endRow"));
    }

    @Test
    @DisplayName("Adds only startRow when endRow is already present")
    void addsStartRowOnly() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("_endRow", "75");
      invokeApplyPaginationDefaults(params);

      assertEquals("0", params.get("_startRow"));
      assertEquals("75", params.get("_endRow"));
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

    @Test
    @DisplayName("Does not add whereAndFilterClause when nothing applies")
    void noWhereClauseWhenNothingApplies() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null);

      assertNull(params.get("whereAndFilterClause"));
    }

    @Test
    @DisplayName("Does not add whereAndFilterClause for blank tab where")
    void blankTabWhereNotAdded() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn("   ");
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null);

      assertNull(params.get("whereAndFilterClause"));
    }

    @Test
    @DisplayName("Adds parent filter for child tab with parentId")
    void addsParentFilterForChildTab() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(1L);
      when(adTab.getName()).thenReturn("Lines");

      try (MockedStatic<NeoTypeCoercionHelper> coercionMock =
               Mockito.mockStatic(NeoTypeCoercionHelper.class)) {
        NeoTypeCoercionHelper.ParentFilter parentFilter =
            mock(NeoTypeCoercionHelper.ParentFilter.class);
        when(parentFilter.resolveForStringApi()).thenReturn("e.salesOrder.id = 'ORDER-1'");
        coercionMock.when(() -> NeoTypeCoercionHelper.buildParentWhereClause(any(), anyString()))
            .thenReturn(parentFilter);

        Map<String, String> params = new HashMap<>();
        invokeApplyWhereClause(params, adTab, "ORDER-1");

        String where = params.get("whereAndFilterClause");
        assertNotNull(where);
        assertTrue(where.contains("e.salesOrder.id = 'ORDER-1'"));
      }
    }

    @Test
    @DisplayName("Does not add parent filter for top-level tabs")
    void noParentFilterForTopLevelTab() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, "PARENT-1");

      assertNull(params.get("whereAndFilterClause"));
    }

    @Test
    @DisplayName("Combines tab where, parent filter and neoWhere with AND")
    void combinesAllThreeClauses() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(adTab.getTabLevel()).thenReturn(1L);
      when(adTab.getName()).thenReturn("Lines");

      try (MockedStatic<NeoTypeCoercionHelper> coercionMock =
               Mockito.mockStatic(NeoTypeCoercionHelper.class)) {
        NeoTypeCoercionHelper.ParentFilter parentFilter =
            mock(NeoTypeCoercionHelper.ParentFilter.class);
        when(parentFilter.resolveForStringApi()).thenReturn("e.order.id = 'ORD-1'");
        coercionMock.when(() -> NeoTypeCoercionHelper.buildParentWhereClause(any(), anyString()))
            .thenReturn(parentFilter);

        Map<String, String> params = new HashMap<>();
        params.put("_neoWhere", "e.qty > 0");
        invokeApplyWhereClause(params, adTab, "ORD-1");

        String where = params.get("whereAndFilterClause");
        assertNotNull(where);
        assertTrue(where.contains("e.active = true"));
        assertTrue(where.contains("e.order.id = 'ORD-1'"));
        assertTrue(where.contains("e.qty > 0"));
      }
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
    @DisplayName("Returns same context when body is empty")
    void emptyBodyReturnsSameContext() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(toServletInputStream(""));

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
    @DisplayName("Preserves original context fields in new context")
    void preservesContextFields() throws Exception {
      Tab adTab = mock(Tab.class);
      SFEntity sfEntity = mock(SFEntity.class);
      Map<String, String> qp = new HashMap<>();
      qp.put("key", "val");
      NeoContext context = NeoContext.builder()
          .specName("mySpec")
          .entityName("myEntity")
          .httpMethod("POST")
          .recordId("REC-1")
          .queryParams(qp)
          .adTab(adTab)
          .sfEntity(sfEntity)
          .obContext(mock(OBContext.class))
          .endpointType(NeoEndpointType.CRUD)
          .build();
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getInputStream()).thenReturn(
          toServletInputStream("{\"a\":1}"));

      NeoContext result = invokeParseBody(context, request, response);

      assertEquals("mySpec", result.getSpecName());
      assertEquals("myEntity", result.getEntityName());
      assertEquals("POST", result.getHttpMethod());
      assertEquals("REC-1", result.getRecordId());
      assertEquals(adTab, result.getAdTab());
      assertEquals(sfEntity, result.getSfEntity());
      assertEquals(NeoEndpointType.CRUD, result.getEndpointType());
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

    @Test
    @DisplayName("Falls through to handleDefault when javaQualifier is blank")
    void fallsToDefaultWhenQualifierBlank() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      when(entity.getJavaQualifier()).thenReturn("");

      NeoContext context = buildContext("GET", null, null,
          entity, null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);

      // handleDefault with null adTab returns 500
      NeoResponse result = invokeDispatch(entity, context, request, response);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      verify(servlet, never()).handleWithHooks(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("Falls through to handleDefault when javaQualifier is null")
    void fallsToDefaultWhenQualifierNull() throws Exception {
      SFEntity entity = mock(SFEntity.class);
      when(entity.getJavaQualifier()).thenReturn(null);

      NeoContext context = buildContext("GET", null, null,
          entity, null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);

      NeoResponse result = invokeDispatch(entity, context, request, response);

      assertNotNull(result);
      verify(servlet, never()).handleWithHooks(anyString(), any(), any(), any());
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

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn("Something failed");

        NeoResponse result = invokeCheckResponse(json);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("Returns 500 with default message when no error object present")
    void returns500WithDefaultMessageWhenNoErrorObject() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      // no "error" key
      JSONObject json = new JSONObject();
      json.put("response", inner);

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            eq("Write operation failed"))).thenReturn("Write operation failed");

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

    @Test
    @DisplayName("Returns null for unknown non-error status")
    void returnsNullForUnknownStatus() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put("status", 99);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      assertNull(invokeCheckResponse(json));
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
      body.put("paymentTerms", "PT-1");
      body.put("account", "ACC-1");
      body.put("customerBlocking", true);
      body.put("purchasePricelist", "PPL-1");
      body.put("pOPaymentMethod", "PPM-1");
      body.put("pOPaymentTerms", "PPT-1");
      body.put("pOFinancialAccount", "PFA-1");
      body.put("vendorBlocking", false);
      body.put("someOtherField", "keep");

      invokeStrip(body, context, adTab);

      assertFalse(body.has("priceList"));
      assertFalse(body.has("priceList$_identifier"));
      assertFalse(body.has("paymentMethod"));
      assertFalse(body.has("paymentMethod$_identifier"));
      assertFalse(body.has("paymentTerms"));
      assertFalse(body.has("account"));
      assertFalse(body.has("customerBlocking"));
      assertFalse(body.has("purchasePricelist"));
      assertFalse(body.has("pOPaymentMethod"));
      assertFalse(body.has("pOPaymentTerms"));
      assertFalse(body.has("pOFinancialAccount"));
      assertFalse(body.has("vendorBlocking"));
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
    @DisplayName("Does not strip for non-businessPartner entity")
    void doesNotStripForNonBPEntity() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = NeoContext.builder()
          .specName("contacts")
          .entityName("invoiceLine")
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
    @DisplayName("Does not strip when tabLevel is null")
    void doesNotStripWhenTabLevelNull() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(null);

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

    @Test
    @DisplayName("Handles null body safely")
    void handlesNullBody() throws Exception {
      Tab adTab = mock(Tab.class);
      NeoContext context = buildContext("POST", null, adTab, mock(SFEntity.class), null, null);
      invokeStrip(null, context, adTab);
      // no exception
    }

    @Test
    @DisplayName("Case-insensitive match on contacts spec name")
    void caseInsensitiveContactsSpec() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = NeoContext.builder()
          .specName("CONTACTS")
          .entityName("businessPartner")
          .httpMethod("POST")
          .adTab(adTab)
          .endpointType(NeoEndpointType.CRUD)
          .build();

      JSONObject body = new JSONObject();
      body.put("priceList", "PL-1");

      invokeStrip(body, context, adTab);

      assertFalse(body.has("priceList"));
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

    @Test
    @DisplayName("Boolean value is stringified for both fields")
    void booleanValueStringified() throws Exception {
      JSONObject entry = invokeToDistinctEntry(true);
      assertEquals("true", entry.getString("id"));
      assertEquals("true", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("BaseOBObject uses id and identifier")
    void baseOBObjectUsesIdAndIdentifier() throws Exception {
      BaseOBObject bob = mock(BaseOBObject.class);
      when(bob.getId()).thenReturn("BOB-123");
      when(bob.getIdentifier()).thenReturn("My Order");

      JSONObject entry = invokeToDistinctEntry(bob);

      assertEquals("BOB-123", entry.getString("id"));
      assertEquals("My Order", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("BaseOBObject with null id uses empty string")
    void baseOBObjectNullId() throws Exception {
      BaseOBObject bob = mock(BaseOBObject.class);
      when(bob.getId()).thenReturn(null);
      when(bob.getIdentifier()).thenReturn("Label");

      JSONObject entry = invokeToDistinctEntry(bob);

      assertEquals("", entry.getString("id"));
    }

    @Test
    @DisplayName("BaseOBObject with blank identifier falls back to id")
    void baseOBObjectBlankIdentifierFallsBackToId() throws Exception {
      BaseOBObject bob = mock(BaseOBObject.class);
      when(bob.getId()).thenReturn("BOB-456");
      when(bob.getIdentifier()).thenReturn("  ");

      JSONObject entry = invokeToDistinctEntry(bob);

      assertEquals("BOB-456", entry.getString("id"));
      assertEquals("BOB-456", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("BaseOBObject with exception on getIdentifier falls back to id")
    void baseOBObjectIdentifierExceptionFallsBack() throws Exception {
      BaseOBObject bob = mock(BaseOBObject.class);
      when(bob.getId()).thenReturn("BOB-789");
      when(bob.getIdentifier()).thenThrow(new RuntimeException("Cannot compute identifier"));

      JSONObject entry = invokeToDistinctEntry(bob);

      assertEquals("BOB-789", entry.getString("id"));
      assertEquals("BOB-789", entry.getString("_identifier"));
    }
  }

  // -------------------------------------------------------------------------
  // resolveDistinctProperty tests (via reflection on static method)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveDistinctProperty")
  class ResolveDistinctProperty {

    private Property invokeResolveDistinctProperty(Entity entityDef, String fieldName)
        throws Exception {
      Method method = NeoCrudHandler.class.getDeclaredMethod(
          "resolveDistinctProperty", Entity.class, String.class);
      method.setAccessible(true);
      return (Property) method.invoke(null, entityDef, fieldName);
    }

    @Test
    @DisplayName("Returns null for null entity")
    void returnsNullForNullEntity() throws Exception {
      assertNull(invokeResolveDistinctProperty(null, "field"));
    }

    @Test
    @DisplayName("Returns direct property when name matches exactly")
    void returnsDirectProperty() throws Exception {
      Entity entity = mock(Entity.class);
      Property directProp = mock(Property.class);
      when(entity.getProperty("status", false)).thenReturn(directProp);

      Property result = invokeResolveDistinctProperty(entity, "status");
      assertEquals(directProp, result);
    }

    @Test
    @DisplayName("Falls back to case-insensitive property name match")
    void fallsBackToCaseInsensitiveName() throws Exception {
      Entity entity = mock(Entity.class);
      when(entity.getProperty("Status", false)).thenReturn(null);

      Property prop = mock(Property.class);
      when(prop.getName()).thenReturn("status");
      when(prop.getColumnName()).thenReturn("STATUS");
      when(entity.getProperties()).thenReturn(Collections.singletonList(prop));

      Property result = invokeResolveDistinctProperty(entity, "Status");
      assertEquals(prop, result);
    }

    @Test
    @DisplayName("Falls back to case-insensitive column name match")
    void fallsBackToColumnNameMatch() throws Exception {
      Entity entity = mock(Entity.class);
      when(entity.getProperty("c_order_id", false)).thenReturn(null);

      Property prop = mock(Property.class);
      when(prop.getName()).thenReturn("salesOrder");
      when(prop.getColumnName()).thenReturn("C_Order_ID");
      when(entity.getProperties()).thenReturn(Collections.singletonList(prop));

      Property result = invokeResolveDistinctProperty(entity, "c_order_id");
      assertEquals(prop, result);
    }

    @Test
    @DisplayName("Returns null when no match found")
    void returnsNullWhenNoMatch() throws Exception {
      Entity entity = mock(Entity.class);
      when(entity.getProperty("unknown", false)).thenReturn(null);

      Property prop = mock(Property.class);
      when(prop.getName()).thenReturn("status");
      when(prop.getColumnName()).thenReturn("STATUS");
      when(entity.getProperties()).thenReturn(Collections.singletonList(prop));

      assertNull(invokeResolveDistinctProperty(entity, "unknown"));
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
              BaseOBObject.class, Entity.class, String.class },
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
      BaseOBObject parentRecord = mock(BaseOBObject.class);

      String result = invokeResolveToken("c_order_id", "PARENT-1",
          parentRecord, null, "c_order");
      assertEquals("PARENT-1", result);
    }

    @Test
    @DisplayName("Resolves AD_Org_ID token from parent record organization")
    void resolvesOrgIdToken() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      BaseOBObject org = mock(BaseOBObject.class);
      when(org.getId()).thenReturn("ORG-UUID");
      when(parentRecord.get("organization")).thenReturn(org);

      String result = invokeResolveToken("AD_Org_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("ORG-UUID", result);
    }

    @Test
    @DisplayName("Resolves Org_ID token (alternative form) from parent record")
    void resolvesOrgIdAltToken() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      BaseOBObject org = mock(BaseOBObject.class);
      when(org.getId()).thenReturn("ORG-ALT");
      when(parentRecord.get("organization")).thenReturn(org);

      String result = invokeResolveToken("Org_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("ORG-ALT", result);
    }

    @Test
    @DisplayName("Falls back to parentId when org is not BaseOBObject")
    void orgFallbackWhenNotBaseOBObject() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      when(parentRecord.get("organization")).thenReturn("not-a-bob");

      String result = invokeResolveToken("AD_Org_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("PARENT-1", result);
    }

    @Test
    @DisplayName("Resolves AD_Client_ID token from parent record client")
    void resolvesClientIdToken() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      BaseOBObject client = mock(BaseOBObject.class);
      when(client.getId()).thenReturn("CLIENT-UUID");
      when(parentRecord.get("client")).thenReturn(client);

      String result = invokeResolveToken("AD_Client_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("CLIENT-UUID", result);
    }

    @Test
    @DisplayName("Resolves Client_ID token (alternative form)")
    void resolvesClientIdAltToken() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      BaseOBObject client = mock(BaseOBObject.class);
      when(client.getId()).thenReturn("CLI-ALT");
      when(parentRecord.get("client")).thenReturn(client);

      String result = invokeResolveToken("Client_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("CLI-ALT", result);
    }

    @Test
    @DisplayName("Falls back to parentId when client is not BaseOBObject")
    void clientFallbackWhenNotBaseOBObject() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      when(parentRecord.get("client")).thenReturn("not-a-bob");

      String result = invokeResolveToken("AD_Client_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("PARENT-1", result);
    }

    @Test
    @DisplayName("Falls back to parentId when org getter throws")
    void orgExceptionFallback() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      when(parentRecord.get("organization")).thenThrow(new RuntimeException("no prop"));

      String result = invokeResolveToken("AD_Org_ID", "PARENT-1",
          parentRecord, null, null);
      assertEquals("PARENT-1", result);
    }

    @Test
    @DisplayName("Resolves generic column name token from parent entity property")
    void resolvesGenericColumnToken() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      when(parentRecord.get("documentNo")).thenReturn("DOC-100");

      Entity parentEntity = mock(Entity.class);
      Property prop = mock(Property.class);
      when(prop.getColumnName()).thenReturn("DocumentNo");
      when(prop.getName()).thenReturn("documentNo");
      when(parentEntity.getProperties()).thenReturn(Collections.singletonList(prop));

      String result = invokeResolveToken("DocumentNo", "PARENT-1",
          parentRecord, parentEntity, null);
      assertEquals("DOC-100", result);
    }

    @Test
    @DisplayName("Resolves FK property to referenced entity id")
    void resolvesFkPropertyToId() throws Exception {
      BaseOBObject refObj = mock(BaseOBObject.class);
      when(refObj.getId()).thenReturn("REF-ID-99");

      BaseOBObject parentRecord = mock(BaseOBObject.class);
      when(parentRecord.get("warehouse")).thenReturn(refObj);

      Entity parentEntity = mock(Entity.class);
      Property prop = mock(Property.class);
      when(prop.getColumnName()).thenReturn("M_Warehouse_ID");
      when(prop.getName()).thenReturn("warehouse");
      when(parentEntity.getProperties()).thenReturn(Collections.singletonList(prop));

      String result = invokeResolveToken("M_Warehouse_ID", "PARENT-1",
          parentRecord, parentEntity, null);
      assertEquals("REF-ID-99", result);
    }

    @Test
    @DisplayName("Returns empty string when property value is null")
    void returnsEmptyWhenPropertyValueNull() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);
      when(parentRecord.get("description")).thenReturn(null);

      Entity parentEntity = mock(Entity.class);
      Property prop = mock(Property.class);
      when(prop.getColumnName()).thenReturn("Description");
      when(prop.getName()).thenReturn("description");
      when(parentEntity.getProperties()).thenReturn(Collections.singletonList(prop));

      String result = invokeResolveToken("Description", "PARENT-1",
          parentRecord, parentEntity, null);
      assertEquals("", result);
    }

    @Test
    @DisplayName("Falls back to parentId when no property column matches")
    void fallsBackWhenNoColumnMatches() throws Exception {
      BaseOBObject parentRecord = mock(BaseOBObject.class);

      Entity parentEntity = mock(Entity.class);
      Property prop = mock(Property.class);
      when(prop.getColumnName()).thenReturn("SomeOtherColumn");
      when(prop.getName()).thenReturn("someOther");
      when(parentEntity.getProperties()).thenReturn(Collections.singletonList(prop));

      String result = invokeResolveToken("UnknownColumn", "PARENT-1",
          parentRecord, parentEntity, null);
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
    @DisplayName("Returns 500 when tab has null table")
    void nullTableReturns500() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTable()).thenReturn(null);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");

      NeoResponse result = invokeDistinctFetch(adTab, params);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 400 when _distinct field name is blank")
    void blankFieldReturns400() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(adTab.getTable()).thenReturn(table);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "   ");

      NeoResponse result = invokeDistinctFetch(adTab, params);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 500 when DAL entity is unknown")
    void unknownEntityReturns500() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("UnknownTable");
      when(adTab.getTable()).thenReturn(table);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        when(mp.getEntity("UnknownTable")).thenThrow(
            new IllegalArgumentException("Unknown entity"));

        NeoResponse result = invokeDistinctFetch(adTab, params);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("Returns 400 when field name does not resolve to a property")
    void unknownFieldReturns400() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("C_Order");
      when(adTab.getTable()).thenReturn(table);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "nonExistentField");

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Entity entity = mock(Entity.class);
        when(mp.getEntity("C_Order")).thenReturn(entity);
        when(entity.getProperty("nonExistentField", false)).thenReturn(null);
        when(entity.getProperties()).thenReturn(Collections.emptyList());

        NeoResponse result = invokeDistinctFetch(adTab, params);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("Returns success response for valid distinct query")
    void validDistinctQueryReturnsOk() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("C_Order");
      when(adTab.getTable()).thenReturn(table);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "documentStatus");

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Entity entity = mock(Entity.class);
        when(mp.getEntity("C_Order")).thenReturn(entity);

        Property prop = mock(Property.class);
        when(prop.getName()).thenReturn("documentStatus");
        when(entity.getProperty("documentStatus", false)).thenReturn(prop);

        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        @SuppressWarnings("unchecked")
        OBQuery<BaseOBObject> obQuery = mock(OBQuery.class);
        when(dal.createQuery(eq("C_Order"), anyString())).thenReturn(obQuery);

        @SuppressWarnings("unchecked")
        org.hibernate.query.Query<Object> hQuery = mock(org.hibernate.query.Query.class);
        when(obQuery.createQuery(Object.class)).thenReturn(hQuery);

        List<Object> results = Arrays.asList("CO", "DR", "VO");
        when(hQuery.list()).thenReturn(results);

        NeoResponse result = invokeDistinctFetch(adTab, params);

        assertEquals(200, result.getHttpStatus());
        JSONObject body = result.getBody();
        assertNotNull(body);
        JSONObject response = body.getJSONObject("response");
        JSONArray data = response.getJSONArray("data");
        assertEquals(3, data.length());
        assertEquals("CO", data.getJSONObject(0).getString("id"));
        assertFalse(response.getBoolean("hasMore"));
      }
    }

    @Test
    @DisplayName("Sets hasMore=true when results exceed page size")
    void hasMoreTrueWhenExceedsPageSize() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("C_Order");
      when(adTab.getTable()).thenReturn(table);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");
      params.put("_startRow", "0");
      params.put("_endRow", "1"); // page size = 2

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Entity entity = mock(Entity.class);
        when(mp.getEntity("C_Order")).thenReturn(entity);

        Property prop = mock(Property.class);
        when(prop.getName()).thenReturn("status");
        when(entity.getProperty("status", false)).thenReturn(prop);

        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        @SuppressWarnings("unchecked")
        OBQuery<BaseOBObject> obQuery = mock(OBQuery.class);
        when(dal.createQuery(eq("C_Order"), anyString())).thenReturn(obQuery);

        @SuppressWarnings("unchecked")
        org.hibernate.query.Query<Object> hQuery = mock(org.hibernate.query.Query.class);
        when(obQuery.createQuery(Object.class)).thenReturn(hQuery);

        // 3 results, page size = 2, so hasMore = true
        List<Object> results = Arrays.asList("A", "B", "C");
        when(hQuery.list()).thenReturn(results);

        NeoResponse result = invokeDistinctFetch(adTab, params);

        assertEquals(200, result.getHttpStatus());
        JSONObject response = result.getBody().getJSONObject("response");
        assertTrue(response.getBoolean("hasMore"));
        assertEquals(2, response.getJSONArray("data").length());
      }
    }

    @Test
    @DisplayName("Returns 500 when OBQuery throws exception")
    void queryExceptionReturns500() throws Exception {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("C_Order");
      when(adTab.getTable()).thenReturn(table);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Entity entity = mock(Entity.class);
        when(mp.getEntity("C_Order")).thenReturn(entity);

        Property prop = mock(Property.class);
        when(prop.getName()).thenReturn("status");
        when(entity.getProperty("status", false)).thenReturn(prop);

        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.createQuery(anyString(), anyString()))
            .thenThrow(new RuntimeException("DB error"));

        NeoResponse result = invokeDistinctFetch(adTab, params);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      }
    }
  }

  // -------------------------------------------------------------------------
  // buildDalParams tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildDalParams")
  class BuildDalParams {

    private Map<String, String> invokeBuildDalParams(NeoContext context, Tab adTab,
        String dalEntityName) throws Exception {
      @SuppressWarnings("unchecked")
      Map<String, String> result = (Map<String, String>) invokePrivate(handler, "buildDalParams",
          new Class<?>[] { NeoContext.class, Tab.class, String.class },
          context, adTab, dalEntityName);
      return result;
    }

    @Test
    @DisplayName("Adds record ID when present in context")
    void addsRecordId() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = buildContext("GET", "REC-123", adTab,
          mock(SFEntity.class), null, null);

      Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

      assertEquals("REC-123", params.get("id"));
    }

    @Test
    @DisplayName("Does not add record ID when not in context")
    void noRecordIdWhenAbsent() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, null);

      Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

      assertFalse(params.containsKey("id"));
    }

    @Test
    @DisplayName("Merges query params into DAL params")
    void mergesQueryParams() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> qp = new HashMap<>();
      qp.put("_sortBy", "documentNo");
      qp.put("customParam", "val");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, qp);

      Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

      assertEquals("documentNo", params.get("_sortBy"));
      assertEquals("val", params.get("customParam"));
    }

    @Test
    @DisplayName("Applies pagination defaults")
    void appliesPaginationDefaults() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, null);

      Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

      assertEquals("0", params.get("_startRow"));
      assertEquals("100", params.get("_endRow"));
    }
  }

  // -------------------------------------------------------------------------
  // injectParentIdAsProperty tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("injectParentIdAsProperty")
  class InjectParentIdAsProperty {

    private void invokeInject(Tab adTab, JSONObject requestBody,
        String parentIdValue) throws Exception {
      invokePrivate(handler, "injectParentIdAsProperty",
          new Class<?>[] { Tab.class, JSONObject.class, String.class },
          adTab, requestBody, parentIdValue);
    }

    @Test
    @DisplayName("Does nothing for top-level tab (tabLevel 0)")
    void noOpForTopLevelTab() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      JSONObject body = new JSONObject();
      invokeInject(adTab, body, "PARENT-1");

      assertEquals(0, body.length());
    }

    @Test
    @DisplayName("Does nothing when tabLevel is null")
    void noOpWhenTabLevelNull() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(null);

      JSONObject body = new JSONObject();
      invokeInject(adTab, body, "PARENT-1");

      assertEquals(0, body.length());
    }

    @Test
    @DisplayName("Injects parent ID for child tab with link-to-parent column")
    void injectsParentIdForChildTab() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(1L);

      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn("C_OrderLine");
      when(adTab.getTable()).thenReturn(table);

      Column linkCol = mock(Column.class);
      when(linkCol.isLinkToParentColumn()).thenReturn(true);
      when(linkCol.isActive()).thenReturn(true);
      when(linkCol.getDBColumnName()).thenReturn("C_Order_ID");
      when(table.getADColumnList()).thenReturn(Collections.singletonList(linkCol));

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);

        Entity dalEnt = mock(Entity.class);
        when(mp.getEntityByTableName("C_OrderLine")).thenReturn(dalEnt);

        Property prop = mock(Property.class);
        when(prop.getName()).thenReturn("salesOrder");
        when(dalEnt.getPropertyByColumnName("C_Order_ID")).thenReturn(prop);

        JSONObject body = new JSONObject();
        invokeInject(adTab, body, "ORDER-UUID");

        assertEquals("ORDER-UUID", body.getString("salesOrder"));
      }
    }

    @Test
    @DisplayName("Skips inactive columns")
    void skipsInactiveColumns() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(1L);

      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn("C_OrderLine");
      when(adTab.getTable()).thenReturn(table);

      Column inactiveCol = mock(Column.class);
      when(inactiveCol.isLinkToParentColumn()).thenReturn(true);
      when(inactiveCol.isActive()).thenReturn(false);
      when(table.getADColumnList()).thenReturn(Collections.singletonList(inactiveCol));

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);

        Entity dalEnt = mock(Entity.class);
        when(mp.getEntityByTableName("C_OrderLine")).thenReturn(dalEnt);

        JSONObject body = new JSONObject();
        invokeInject(adTab, body, "ORDER-UUID");

        assertEquals(0, body.length());
      }
    }

    @Test
    @DisplayName("Skips non-link-to-parent columns")
    void skipsNonLinkColumns() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(1L);

      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn("C_OrderLine");
      when(adTab.getTable()).thenReturn(table);

      Column normalCol = mock(Column.class);
      when(normalCol.isLinkToParentColumn()).thenReturn(false);
      when(normalCol.isActive()).thenReturn(true);
      when(table.getADColumnList()).thenReturn(Collections.singletonList(normalCol));

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);

        Entity dalEnt = mock(Entity.class);
        when(mp.getEntityByTableName("C_OrderLine")).thenReturn(dalEnt);

        JSONObject body = new JSONObject();
        invokeInject(adTab, body, "ORDER-UUID");

        assertEquals(0, body.length());
      }
    }

    @Test
    @DisplayName("Does nothing when DAL entity is null")
    void noOpWhenDalEntityNull() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(1L);

      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn("Unknown");
      when(adTab.getTable()).thenReturn(table);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        when(mp.getEntityByTableName("Unknown")).thenReturn(null);

        JSONObject body = new JSONObject();
        invokeInject(adTab, body, "PARENT-1");

        assertEquals(0, body.length());
      }
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
    @DisplayName("Detects sequence fields (angle bracket placeholders) in body")
    void detectsSequenceFields() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      JSONObject body = new JSONObject();
      body.put("documentNo", "<sequence>");
      body.put("name", "Normal value");
      body.put("code", "<auto>");

      NeoContext context = buildContext("POST", null, adTab,
          mock(SFEntity.class), body, null);

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
               Mockito.mockStatic(NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock =
               Mockito.mockStatic(DocTypeResolver.class)) {
        cascadeMock.when(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            any(), any(), any(), any(), any())).then(invocation -> null);
        cascadeMock.when(() -> NeoDefaultsCascadeHelper.removeEmptyFkValues(
            any(), any())).then(invocation -> null);
        docTypeMock.when(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
            any(), any(), any())).then(invocation -> null);

        invokePrivate(handler, "executePostCalloutCascade",
            new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
                String.class, java.util.Set.class },
            body, adTab, context, null, new HashSet<>());

        // Verify cascade was called (no exception)
        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            any(), any(), any(), any(), any()));
      }
    }

    @Test
    @DisplayName("Uses empty set when protectedFields is null")
    void usesEmptySetForNullProtectedFields() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);

      JSONObject body = new JSONObject();
      NeoContext context = buildContext("POST", null, adTab,
          mock(SFEntity.class), body, null);

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
               Mockito.mockStatic(NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock =
               Mockito.mockStatic(DocTypeResolver.class)) {
        cascadeMock.when(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            any(), any(), any(), any(), any())).then(invocation -> null);
        cascadeMock.when(() -> NeoDefaultsCascadeHelper.removeEmptyFkValues(
            any(), any())).then(invocation -> null);
        docTypeMock.when(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
            any(), any(), any())).then(invocation -> null);

        // Pass null for protectedFields
        invokePrivate(handler, "executePostCalloutCascade",
            new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
                String.class, java.util.Set.class },
            body, adTab, context, null, (Set<String>) null);

        // Should not throw NPE
        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            any(), any(), any(), any(), any()));
      }
    }
  }

  // -------------------------------------------------------------------------
  // resolveParentFilter tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveParentFilter")
  class ResolveParentFilter {

    private String invokeResolveParentFilter(Tab childTab, String parentId) throws Exception {
      return (String) invokePrivate(handler, "resolveParentFilter",
          new Class<?>[] { Tab.class, String.class }, childTab, parentId);
    }

    @Test
    @DisplayName("Returns filter expression from NeoTypeCoercionHelper")
    void returnsFilterExpression() throws Exception {
      Tab childTab = mock(Tab.class);
      when(childTab.getName()).thenReturn("Lines");

      try (MockedStatic<NeoTypeCoercionHelper> coercionMock =
               Mockito.mockStatic(NeoTypeCoercionHelper.class)) {
        NeoTypeCoercionHelper.ParentFilter pf =
            mock(NeoTypeCoercionHelper.ParentFilter.class);
        when(pf.resolveForStringApi()).thenReturn("e.order.id = 'X'");
        coercionMock.when(() -> NeoTypeCoercionHelper.buildParentWhereClause(
            eq(childTab), eq("X"))).thenReturn(pf);

        String result = invokeResolveParentFilter(childTab, "X");
        assertEquals("e.order.id = 'X'", result);
      }
    }

    @Test
    @DisplayName("Returns null when parent filter is null")
    void returnsNullWhenNoFilter() throws Exception {
      Tab childTab = mock(Tab.class);
      when(childTab.getName()).thenReturn("Lines");

      try (MockedStatic<NeoTypeCoercionHelper> coercionMock =
               Mockito.mockStatic(NeoTypeCoercionHelper.class)) {
        coercionMock.when(() -> NeoTypeCoercionHelper.buildParentWhereClause(
            any(), anyString())).thenReturn(null);

        String result = invokeResolveParentFilter(childTab, "X");
        assertNull(result);
      }
    }

    @Test
    @DisplayName("Returns null when exception is thrown")
    void returnsNullOnException() throws Exception {
      Tab childTab = mock(Tab.class);
      when(childTab.getName()).thenReturn("Lines");

      try (MockedStatic<NeoTypeCoercionHelper> coercionMock =
               Mockito.mockStatic(NeoTypeCoercionHelper.class)) {
        coercionMock.when(() -> NeoTypeCoercionHelper.buildParentWhereClause(
            any(), anyString())).thenThrow(new RuntimeException("Boom"));

        String result = invokeResolveParentFilter(childTab, "X");
        assertNull(result);
      }
    }
  }

  // -------------------------------------------------------------------------
  // addTabWherePredicate tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("addTabWherePredicate")
  class AddTabWherePredicate {

    @SuppressWarnings("unchecked")
    private List<String> invokeAddTabWherePredicate(Tab adTab, String tabWhere,
        String parentId) throws Exception {
      List<String> predicates = new ArrayList<>();
      invokePrivate(handler, "addTabWherePredicate",
          new Class<?>[] { Tab.class, String.class, String.class, List.class },
          adTab, tabWhere, parentId, predicates);
      return predicates;
    }

    @Test
    @DisplayName("Adds wrapped predicate for a plain (token-free) where clause")
    void addsPlainWhereClause() throws Exception {
      Tab adTab = mock(Tab.class);

      List<String> predicates = invokeAddTabWherePredicate(adTab, "e.active = true", null);

      assertEquals(1, predicates.size());
      assertEquals("(e.active = true)", predicates.get(0));
    }

    @Test
    @DisplayName("Adds nothing for a blank tab where clause")
    void addsNothingForBlankClause() throws Exception {
      Tab adTab = mock(Tab.class);

      List<String> predicates = invokeAddTabWherePredicate(adTab, "   ", "PARENT-1");

      assertTrue(predicates.isEmpty());
    }

    @Test
    @DisplayName("Resolves @token@ via resolveTabWhereTokens when parentId is present")
    void resolvesTokensWhenParentIdPresent() throws Exception {
      Tab adTab = mock(Tab.class);

      // With no parent tab, resolveTokenFromParent falls back to the parentId for each token,
      // so the resolved clause no longer contains '@' and the predicate is added.
      try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
        KernelUtils kernelUtils = mock(KernelUtils.class);
        kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
        when(kernelUtils.getParentTab(adTab)).thenReturn(null);

        // resolveTabWhereTokens wraps the resolved value in single quotes, so the
        // token is written unquoted (as in real AD tab-where clauses).
        List<String> predicates = invokeAddTabWherePredicate(
            adTab, "e.client.id = @AD_Client_ID@", "PARENT-1");

        assertEquals(1, predicates.size());
        assertEquals("(e.client.id = 'PARENT-1')", predicates.get(0));
      }
    }

    @Test
    @DisplayName("Skips predicate when unresolved @token@ remains")
    void skipsWhenUnresolvedTokenRemains() throws Exception {
      Tab adTab = mock(Tab.class);

      // parentId is null, so token resolution is not attempted and the '@' stays — predicate skipped.
      List<String> predicates = invokeAddTabWherePredicate(
          adTab, "e.client.id = '@AD_Client_ID@'", null);

      assertTrue(predicates.isEmpty(), "Predicate with unresolved @token@ must be skipped");
    }
  }
}
