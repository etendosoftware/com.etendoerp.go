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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoDistinctFetchSupport;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * Unit tests for {@link NeoCrudHandler}.
 * Uses JUnit 5 (Jupiter) and Mockito.
 *
 * @covers com.etendoerp.go.schemaforge.NeoCrudHandler
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

  /**
   * Like {@link #invokePrivate} but for a {@code static} method, invoked with no target
   * instance. Used for {@link NeoParentTabFilterResolver}'s private static helpers, which moved
   * out of {@code NeoCrudHandler} (Sonar method-count split) but are still exercised directly
   * via reflection here, same as their previous instance-method form.
   */
  private static Object invokeStaticPrivate(Class<?> clazz, String methodName,
      Class<?>[] paramTypes, Object... args) throws Exception {
    Method method = clazz.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
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

  @Test
  @DisplayName("ETP-5556: REST write with read-only fields logs every one of them, does not reject")
  void clientReadOnlyFieldsAreLoggedNotRejected() throws Exception {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    SFEntity entity = mock(SFEntity.class);
    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(adTab.getTable()).thenReturn(table);
    when(table.getName()).thenReturn("OrderLine");
    JSONObject body = new JSONObject()
        .put("lineNetAmount", "30.00")
        .put("grossAmount", "36.30");
    when(filter.findClientReadOnlyFields(body, "PATCH"))
        .thenReturn(List.of("lineNetAmount", "grossAmount"));
    List<String> warnings = new ArrayList<>();
    Logger handlerLogger = (Logger) LogManager.getLogger(NeoCrudHandler.class);
    AbstractAppender capture = new AbstractAppender("capture-etp-5556", null, null, true,
        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
      @Override
      public void append(LogEvent event) {
        if (event.getLevel() == Level.WARN) {
          warnings.add(event.getMessage().getFormattedMessage());
        }
      }
    };
    capture.start();
    handlerLogger.addAppender(capture);

    try (MockedStatic<NeoFieldFilter> fieldFilters = Mockito.mockStatic(NeoFieldFilter.class)) {
      fieldFilters.when(() -> NeoFieldFilter.forEntity(entity, "OrderLine")).thenReturn(filter);

      handler.warnOnClientReadOnlyFields(
          buildContext("PATCH", "record-1", adTab, entity, body, Collections.emptyMap()));
    } finally {
      handlerLogger.removeAppender(capture);
      capture.stop();
    }

    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("PATCH"));
    assertTrue(warnings.get(0).contains("[lineNetAmount, grossAmount]"));
    verify(filter, never()).validateClientWriteRequest(any(), any());
  }

  // -------------------------------------------------------------------------
  // Method-flag gate. ETP-4254 extracted the private NeoCrudHandler#isMethodEnabled into
  // the shared NeoMethodPolicy (the MCP write path needed the same check), so these cases
  // now exercise the shared helper directly instead of through reflection. They are kept
  // here because this is the gate NeoCrudHandler#handleWindowEntityCrud applies to produce
  // the REST 405 — see also NeoMethodPolicyTest for the helper's own edge cases.
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("isMethodEnabled")
  class IsMethodEnabled {

    private boolean invokeIsMethodEnabled(String method, SFEntity entity) {
      return NeoMethodPolicy.isMethodEnabled(entity, method);
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

    @ParameterizedTest
    @ValueSource(strings = { "POST", "PUT", "PATCH" })
    @DisplayName("ETP-5556: a read-only field no longer blocks the REST write from reaching a "
        + "handler")
    void readOnlyFieldDoesNotBlockDispatch(String method) throws Exception {
      SFSpec spec = mock(SFSpec.class);
      SFEntity entity = createMockEntity(false, false, true, true, true, false);
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      NeoFieldFilter filter = mock(NeoFieldFilter.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", "POST".equals(method) ? null : "REC-1");
      when(spec.getId()).thenReturn("SPEC-1");
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("orderHook");
      when(adTab.getTable()).thenReturn(table);
      when(table.getName()).thenReturn("Order");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());
      when(request.getInputStream()).thenReturn(
          toServletInputStream("{\"documentNo\":\"SO-9999\"}"));
      when(filter.findClientReadOnlyFields(any(), any())).thenReturn(List.of("documentNo"));
      NeoResponse hookResponse = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(eq("orderHook"), any(), eq(request), eq(response)))
          .thenReturn(hookResponse);

      try (MockedStatic<NeoFieldFilter> fieldFilters = Mockito.mockStatic(NeoFieldFilter.class)) {
        fieldFilters.when(() -> NeoFieldFilter.forEntity(entity, "Order")).thenReturn(filter);

        handler.handleWindowEntityCrud(spec, pathInfo, method, request, response);
      }

      verify(servlet).handleWithHooks(eq("orderHook"), any(), eq(request), eq(response));
      verify(servlet).writeResponse(response, hookResponse);
    }

    /**
     * Builds a REAL (non-mocked) {@link NeoFieldFilter} via reflection on its package-private
     * constructor — same technique {@code NeoFieldFilterTest} uses — so this test exercises the
     * actual {@code validateClientWriteRequest}/{@code rejectableOnCreateFields} interaction
     * instead of restating it as a stub. Mirrors an entity with a {@code Java_Qualifier} (like
     * {@code assets}/{@code AssetsHandler}): {@code currency} is included + read-only, but NOT in
     * {@code rejectableOnCreateFields}, exactly as {@code NeoFieldFilter#forEntity} would build it
     * for such an entity (see its {@code entityHasHandler} branch).
     */
    private NeoFieldFilter buildCreateExemptedFilter() throws Exception {
      Constructor<NeoFieldFilter> ctor = NeoFieldFilter.class.getDeclaredConstructor(
          Set.class, Set.class, Set.class, Map.class, Map.class, boolean.class);
      ctor.setAccessible(true);
      return ctor.newInstance(
          new HashSet<>(Set.of("id", "currency")), new HashSet<>(Set.of("id")),
          Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap(), true);
    }

    @Test
    @DisplayName("ETP-5537: POST accepts a create-exempted read-only field (entity has a "
        + "NeoHandler) and reaches dispatch; ETP-5556: PUT with the same field reaches it too")
    void createExemptedFieldPassesAndUpdateIsNotRejected() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      SFEntity entity = createMockEntity(false, false, true, true, true, false);
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(spec.getId()).thenReturn("SPEC-1");
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("assetsHandler");
      when(adTab.getTable()).thenReturn(table);
      when(table.getName()).thenReturn("A_Asset");
      when(servlet.findEntity("SPEC-1", "assets")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());
      NeoFieldFilter filter = buildCreateExemptedFilter();

      try (MockedStatic<NeoFieldFilter> fieldFilters = Mockito.mockStatic(NeoFieldFilter.class)) {
        fieldFilters.when(() -> NeoFieldFilter.forEntity(entity, "A_Asset")).thenReturn(filter);

        // POST: currency in the body is exempted (create-only) -> reaches dispatch.
        when(request.getInputStream()).thenReturn(
            toServletInputStream("{\"currency\":\"102\"}"));
        NeoServlet.NeoPathInfo postPath = new NeoServlet.NeoPathInfo("assets", "assets", null);
        handler.handleWindowEntityCrud(spec, postPath, "POST", request, response);
        verify(servlet).handleWithHooks(eq("assetsHandler"), any(), eq(request), eq(response));
        verify(servlet, never()).writeResponse(eq(response), any());

        // PUT on an existing record: the field is not a create exemption here, but ETP-5556
        // only logs it, so the write still reaches the handler instead of a 422.
        HttpServletRequest putRequest = mock(HttpServletRequest.class);
        when(putRequest.getInputStream()).thenReturn(
            toServletInputStream("{\"currency\":\"102\"}"));
        NeoServlet.NeoPathInfo putPath = new NeoServlet.NeoPathInfo("assets", "assets", "REC-1");
        handler.handleWindowEntityCrud(spec, putPath, "PUT", putRequest, response);

        verify(servlet).handleWithHooks(eq("assetsHandler"), any(), eq(putRequest), eq(response));
        verify(servlet, never()).writeResponse(eq(response), any());
      }
    }

    /**
     * ETP-5556 regression: the line grid autosave PATCHes the whole row, so the body carries
     * read-only computed columns (amounts, audit fields) next to the one field the user edited.
     * The write must still reach the handler instead of failing the whole save with a 422.
     */
    @ParameterizedTest
    @ValueSource(strings = { "PUT", "PATCH" })
    @DisplayName("ETP-5556: a full-row write carrying read-only fields still reaches the handler")
    void fullRowWriteWithReadOnlyFieldsReachesHandler(String method) throws Exception {
      SFSpec spec = mock(SFSpec.class);
      SFEntity entity = createMockEntity(false, false, true, true, true, false);
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(spec.getId()).thenReturn("SPEC-1");
      when(entity.getADTab()).thenReturn(adTab);
      when(entity.getJavaQualifier()).thenReturn("linesHook");
      when(adTab.getTable()).thenReturn(table);
      when(table.getName()).thenReturn("OrderLine");
      when(servlet.findEntity("SPEC-1", "lines")).thenReturn(entity);
      when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());
      when(request.getInputStream()).thenReturn(toServletInputStream(
          "{\"orderedQuantity\":\"3\",\"lineNetAmount\":\"30.00\",\"grossAmount\":\"36.30\"}"));
      NeoResponse hookResponse = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(eq("linesHook"), any(), eq(request), eq(response)))
          .thenReturn(hookResponse);
      NeoFieldFilter filter = buildLineFilter();

      try (MockedStatic<NeoFieldFilter> fieldFilters = Mockito.mockStatic(NeoFieldFilter.class)) {
        fieldFilters.when(() -> NeoFieldFilter.forEntity(entity, "OrderLine")).thenReturn(filter);

        handler.handleWindowEntityCrud(spec,
            new NeoServlet.NeoPathInfo("testSpec", "lines", "LINE-1"), method, request, response);
      }

      verify(servlet).handleWithHooks(eq("linesHook"), any(), eq(request), eq(response));
      verify(servlet).writeResponse(response, hookResponse);
    }

    /**
     * Real filter for a sales order line: {@code orderedQuantity} is writable, the computed
     * amounts are included but read-only.
     */
    private NeoFieldFilter buildLineFilter() throws Exception {
      Constructor<NeoFieldFilter> ctor = NeoFieldFilter.class.getDeclaredConstructor(
          Set.class, Set.class, Set.class, Map.class, Map.class, boolean.class);
      ctor.setAccessible(true);
      return ctor.newInstance(
          new HashSet<>(Set.of("id", "orderedQuantity", "lineNetAmount", "grossAmount")),
          new HashSet<>(Set.of("id", "orderedQuantity")),
          Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap(), true);
    }

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

    @ParameterizedTest
    @ValueSource(strings = { "{\"x\":[", "[[", "{\"a\":[}", "[{\"f\":1/*x*/}]" })
    @DisplayName("Sends 400 for a malformed criteria before any handler or datasource runs")
    void malformedCriteriaSends400BeforeDispatch(String criteria) throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(mock(Tab.class));
      when(entity.getJavaQualifier()).thenReturn("myHook");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      Map<String, String> params = new HashMap<>();
      params.put("criteria", criteria);
      when(servlet.extractQueryParams(any())).thenReturn(params);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletResponse response = mock(HttpServletResponse.class);

      handler.handleWindowEntityCrud(spec, pathInfo, "GET",
          mock(HttpServletRequest.class), response);

      verify(servlet).sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid criteria: malformed JSON");
      verify(servlet, never()).handleWithHooks(anyString(), any(), any(), any());
      verify(servlet, never()).writeResponse(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"x\":[", "[[", "{\"a\":[}", "[{\"f\":1/*x*/}]" })
    @DisplayName("Sends 400 for a malformed criteria on a _distinct request, before the distinct fetch")
    void malformedCriteriaOnADistinctRequestSends400(String criteria) throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(mock(Tab.class));
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");
      params.put("criteria", criteria);
      when(servlet.extractQueryParams(any())).thenReturn(params);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletResponse response = mock(HttpServletResponse.class);

      try (MockedStatic<NeoListAggregateQuery> agg =
          Mockito.mockStatic(NeoListAggregateQuery.class)) {
        handler.handleWindowEntityCrud(spec, pathInfo, "GET",
            mock(HttpServletRequest.class), response);

        agg.verifyNoInteractions();
      }

      verify(servlet).sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid criteria: malformed JSON");
      verify(servlet, never()).writeResponse(any(), any());
    }

    @Test
    @DisplayName("Sends 400 for a too deeply nested criteria on a _distinct request")
    void tooDeeplyNestedCriteriaOnADistinctRequestSends400() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(mock(Tab.class));
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");
      params.put("criteria", "[".repeat(65) + "]".repeat(65));
      when(servlet.extractQueryParams(any())).thenReturn(params);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletResponse response = mock(HttpServletResponse.class);

      handler.handleWindowEntityCrud(spec, pathInfo, "GET",
          mock(HttpServletRequest.class), response);

      verify(servlet).sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid criteria: malformed JSON");
      verify(servlet, never()).writeResponse(any(), any());
    }

    @Test
    @DisplayName("A well-formed criteria reaches the handler")
    void wellFormedCriteriaIsDispatched() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("SPEC-1");

      SFEntity entity = createMockEntity(true, false, false, false, false, false);
      when(entity.getADTab()).thenReturn(mock(Tab.class));
      when(entity.getJavaQualifier()).thenReturn("myHook");
      when(servlet.findEntity("SPEC-1", "orders")).thenReturn(entity);
      Map<String, String> params = new HashMap<>();
      params.put("criteria", "[{\"fieldName\":\"name\",\"operator\":\"iContains\",\"value\":\"a\"}]");
      when(servlet.extractQueryParams(any())).thenReturn(params);
      when(servlet.handleWithHooks(anyString(), any(), any(), any())).thenReturn(null);

      NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
          "testSpec", "orders", null);
      HttpServletResponse response = mock(HttpServletResponse.class);

      handler.handleWindowEntityCrud(spec, pathInfo, "GET",
          mock(HttpServletRequest.class), response);

      verify(servlet).handleWithHooks(eq("myHook"), any(), any(), any());
      verify(servlet, never()).sendError(any(), Mockito.anyInt(), anyString());
    }

    @Test
    @DisplayName("isCriteriaWithinLimits accepts an absent or blank criteria and refuses a deep one")
    void isCriteriaWithinLimitsBoundaries() {
      assertTrue(NeoCrudHandler.isCriteriaWithinLimits(null));
      assertTrue(NeoCrudHandler.isCriteriaWithinLimits(new HashMap<>()));
      assertTrue(NeoCrudHandler.isCriteriaWithinLimits(Map.of("criteria", "  ")));
      assertTrue(NeoCrudHandler.isCriteriaWithinLimits(Map.of("criteria", "{\"a\":1}")));
      String tooDeep = "[".repeat(65) + "]".repeat(65);
      assertFalse(NeoCrudHandler.isCriteriaWithinLimits(Map.of("criteria", tooDeep)));
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
  // handleDefault: aggregate handover (ETP-5778)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleDefault remembers the list params for aggregates")
  class HandleDefaultAggregateHandover {

    private Tab tab() {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      Window window = mock(Window.class);
      when(table.getName()).thenReturn("FIN_Payment");
      when(adTab.getTable()).thenReturn(table);
      when(adTab.getId()).thenReturn("tab-1");
      when(adTab.getWindow()).thenReturn(window);
      when(window.getId()).thenReturn("win-1");
      return adTab;
    }

    /** Runs handleDefault with every collaborator stubbed and returns what remember() saw. */
    private List<Map<String, String>> run(String method, String recordId) throws Exception {
      return run(method, recordId, null);
    }

    private List<Map<String, String>> run(String method, String recordId,
        Map<String, String> query) throws Exception {
      org.openbravo.service.json.DefaultJsonDataService json;
      // DefaultJsonDataService's static initialiser asks Weld for its singleton, which does not
      // exist outside a running Etendo: keep Weld stubbed while the class is first initialised.
      try (MockedStatic<org.openbravo.base.weld.WeldUtils> weld =
          mockStatic(org.openbravo.base.weld.WeldUtils.class)) {
        json = mock(org.openbravo.service.json.DefaultJsonDataService.class);
      }
      when(json.fetch(any())).thenReturn("{\"response\":{\"status\":0,\"data\":[]}}");
      List<Map<String, String>> remembered = new ArrayList<>();
      try (MockedStatic<BatchService> batch = mockStatic(BatchService.class);
          MockedStatic<NeoFieldFilter> filter = mockStatic(NeoFieldFilter.class);
          MockedStatic<NeoReadPredicates> preds = mockStatic(NeoReadPredicates.class);
          MockedStatic<NeoParentTabFilterResolver> parent =
              mockStatic(NeoParentTabFilterResolver.class);
          MockedStatic<NeoTabDefaultSort> sort = mockStatic(NeoTabDefaultSort.class);
          MockedStatic<NeoListAggregateQuery> agg = mockStatic(NeoListAggregateQuery.class)) {
        batch.when(BatchService::currentJsonService).thenReturn(json);
        filter.when(() -> NeoFieldFilter.forEntity(any(), anyString(), any()))
            .thenReturn(mock(NeoFieldFilter.class));
        filter.when(() -> NeoFieldFilter.forEntity(any(), anyString()))
            .thenReturn(mock(NeoFieldFilter.class));
        agg.when(() -> NeoListAggregateQuery.remember(any(), any()))
            .thenAnswer(inv -> remembered.add(inv.getArgument(1)));

        NeoContext context = buildContext(method, recordId, tab(), null, null, query);
        handler.handleDefault(context);
      }
      return remembered;
    }

    @Test
    @DisplayName("a list GET hands the DAL params to NeoListAggregateQuery")
    void listGetRemembersParams() throws Exception {
      List<Map<String, String>> remembered = run("GET", null);
      assertEquals(1, remembered.size());
      assertEquals("tab-1", remembered.get(0).get(JsonConstants.TAB_PARAMETER));
    }

    @Test
    @DisplayName("a _distinct list GET (FK dropdown) does not")
    void distinctListDoesNotRemember() throws Exception {
      Map<String, String> q = new HashMap<>();
      q.put(JsonConstants.DISTINCT_PARAMETER, "businessPartner");
      assertTrue(run("GET", null, q).isEmpty());
    }

    @Test
    @DisplayName("a _summary list GET (grid summary row) does not")
    void summaryListDoesNotRemember() throws Exception {
      Map<String, String> q = new HashMap<>();
      q.put(JsonConstants.SUMMARY_PARAMETER, "{\"amount\":\"sum\"}");
      assertTrue(run("GET", null, q).isEmpty());
    }

    @Test
    @DisplayName("a read-by-id GET does not")
    void readByIdDoesNotRemember() throws Exception {
      assertTrue(run("GET", "REC-1").isEmpty());
    }

    @Test
    @DisplayName("a POST does not")
    void postDoesNotRemember() throws Exception {
      assertTrue(run("POST", "REC-1").isEmpty());
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
    @DisplayName("Returns null when POST has no record ID and a body")
    void postWithoutRecordIdReturnsNull() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), new JSONObject(), null);

      NeoResponse result = invokeValidatePost(context);

      assertNull(result);
    }

    /**
     * A blank body reaches the handler as a null request body. Creating from it used to fail
     * deep in the callout cascade with a NullPointerException: a 500 and an ERROR stack trace
     * for what is the caller's mistake.
     */
    @Test
    @DisplayName("Refuses POST with a blank body as a 400")
    void postWithoutBodyIsRefused() throws Exception {
      NeoContext context = buildContext("POST", null, mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidatePost(context);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertEquals("Request body is required",
          result.getBody().getJSONObject("error").getString("message"));
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

    /**
     * ETP-5073 / DOC-04 changed this contract. A record ID used to be the only requirement; an
     * update must now also carry the {@code updated} value of the record as it was read, because
     * that value is what lets the concurrency check evaluate at all. Before the ticket our layer
     * stripped it from every write, so the check never ran for any entity and the second of two
     * concurrent editors silently erased the first.
     *
     * <p>Kept as two cases rather than adjusted in place: the refusal and the pass are both worth
     * pinning, and the refusal is the half that regresses silently — an update that goes through
     * without the token writes without a check and nothing anywhere reports it.
     */
    @Test
    @DisplayName("Returns null when PUT/PATCH has both a record ID and `updated`")
    void updateWithRecordIdAndVersionReturnsNull() throws Exception {
      JSONObject body = new JSONObject();
      body.put("updated", "2026-08-21T16:20:38+00:00");
      NeoContext context = buildContext("PATCH", "REC-123", mock(Tab.class),
          mock(SFEntity.class), body, null);

      NeoResponse result = invokeValidateUpdate(context);

      assertNull(result);
    }

    @Test
    @DisplayName("Refuses PUT/PATCH that has a record ID but no `updated`")
    void updateWithRecordIdButNoVersionIsRefused() throws Exception {
      NeoContext context = buildContext("PATCH", "REC-123", mock(Tab.class),
          mock(SFEntity.class), new JSONObject(), null);

      NeoResponse result = invokeValidateUpdate(context);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertEquals("missing_updated", result.getBody().getString("error"));
    }

    /** No body at all is not a missing token: the caller sent nothing to update with. */
    @ParameterizedTest
    @ValueSource(strings = { "PUT", "PATCH" })
    @DisplayName("Refuses PUT/PATCH with a blank body as a 400")
    void updateWithoutBodyIsRefused(String method) throws Exception {
      NeoContext context = buildContext(method, "REC-123", mock(Tab.class),
          mock(SFEntity.class), null, null);

      NeoResponse result = invokeValidateUpdate(context);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertEquals("Request body is required",
          result.getBody().getJSONObject("error").getString("message"));
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
  // NeoReadOnlyFieldResponse tests (IMP-28 clause 2)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("NeoReadOnlyFieldResponse")
  class BuildReadOnlyFieldRejectedResponse {

    private NeoResponse invokeBuildResponse(ReadOnlyFieldRejectedException e) {
      return NeoReadOnlyFieldResponse.build(e);
    }

    @Test
    @DisplayName("Returns the IMP-5-shaped 422: status/error/detail/field/hint/seeAlso")
    void buildsFlatImp5Shape() throws Exception {
      ReadOnlyFieldRejectedException ex = new ReadOnlyFieldRejectedException("eTGOSalePrice");

      NeoResponse result = invokeBuildResponse(ex);

      assertNotNull(result);
      assertEquals(422, result.getHttpStatus());

      JSONObject body = result.getBody();
      assertNotNull(body);
      assertEquals(422, body.getInt("status"));
      assertEquals("read_only_field", body.getString("error"));
      assertEquals("eTGOSalePrice", body.getString("field"));
      assertTrue(body.has("detail"));
      assertTrue(body.has("hint"));
      assertEquals("docs(topic:\"creating records\")", body.getString("seeAlso"));
    }

    @Test
    @DisplayName("detail and hint both name the rejected field")
    void detailAndHintNameTheField() throws Exception {
      ReadOnlyFieldRejectedException ex = new ReadOnlyFieldRejectedException("quantityOnHand");

      NeoResponse result = invokeBuildResponse(ex);

      JSONObject body = result.getBody();
      assertTrue(body.getString("detail").contains("quantityOnHand"));
      assertTrue(body.getString("hint").contains("quantityOnHand"));
    }
  }

  // -------------------------------------------------------------------------
  // handleDefault + ReadOnlyFieldRejectedException wiring (IMP-28 clause 2)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("handleDefault — ReadOnlyFieldRejectedException")
  class HandleDefaultReadOnlyFieldRejected {

    @Test
    @DisplayName("A ReadOnlyFieldRejectedException thrown deep in the write path answers 422, "
        + "not the generic 500 the catch-all(Exception) branch would otherwise produce")
    void translatesToStructured422() throws JSONException {
      Tab adTab = mock(Tab.class);
      // Forces handleDefault's try-block to throw before reaching the DAL —
      // getTable() is the first call handleDefault makes on adTab.
      when(adTab.getTable()).thenThrow(new ReadOnlyFieldRejectedException("salePrice"));

      NeoContext context = buildContext("POST", null, adTab,
          mock(SFEntity.class), null, null);

      NeoResponse result = handler.handleDefault(context);

      assertNotNull(result);
      assertEquals(422, result.getHttpStatus(),
          "must be routed through NeoReadOnlyFieldResponse, not the generic 500 path");
      assertEquals("read_only_field", result.getBody().getString("error"));
      assertEquals("salePrice", result.getBody().getString("field"));
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
      invokeApplyWhereClause(params, adTab, parentId, null);
    }

    private void invokeApplyWhereClause(Map<String, String> params,
        Tab adTab, String parentId, String readPredicate) throws Exception {
      invokePrivate(handler, "applyWhereClause",
          new Class<?>[] { Map.class, Tab.class, String.class, String.class },
          params, adTab, parentId, readPredicate);
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
    @DisplayName("Resolves the parent placeholder of the tab where clause (rule shared with MCP)")
    void resolvesParentPlaceholderInTabWhere() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn("e.storageBin.id=@Locator.id@");
      when(adTab.getTabLevel()).thenReturn(0L);

      // With no parent tab, each token falls back to the parent id (see resolveTokenFromParent).
      try (MockedStatic<KernelUtils> kernelMock = Mockito.mockStatic(KernelUtils.class)) {
        KernelUtils kernelUtils = mock(KernelUtils.class);
        kernelMock.when(KernelUtils::getInstance).thenReturn(kernelUtils);
        when(kernelUtils.getParentTab(adTab)).thenReturn(null);

        Map<String, String> params = new HashMap<>();
        invokeApplyWhereClause(params, adTab, "BIN-1");

        assertEquals("(e.storageBin.id='BIN-1')", params.get("whereAndFilterClause"));
      }
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
    @DisplayName("Combines tab where and parent filter with AND")
    void combinesTabWhereAndParentFilter() throws Exception {
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
        invokeApplyWhereClause(params, adTab, "ORD-1");

        assertEquals("(e.active = true) and (e.order.id = 'ORD-1')",
            params.get("whereAndFilterClause"));
      }
    }

    @Test
    @DisplayName("ETP-5009: a read predicate alone becomes the where clause, parenthesised")
    void readPredicateAlone() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null, "e.hidden = false");

      assertEquals("(e.hidden = false)", params.get("whereAndFilterClause"));
      assertEquals("true", params.get("_use_alias"));
    }

    @Test
    @DisplayName("ETP-5009: the read predicate is ANDed after the tab where")
    void readPredicateAndedAfterTabWhere() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null, "e.hidden = false");

      assertEquals("(e.active = true) and (e.hidden = false)",
          params.get("whereAndFilterClause"));
    }

    @Test
    @DisplayName("ETP-5009: the read predicate is ANDed after the parent filter of a child tab")
    void readPredicateAndedAfterParentFilter() throws Exception {
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
        invokeApplyWhereClause(params, adTab, "ORDER-1", "e.hidden = false");

        assertEquals("(e.salesOrder.id = 'ORDER-1') and (e.hidden = false)",
            params.get("whereAndFilterClause"));
      }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    @DisplayName("ETP-5009: a blank read predicate adds nothing")
    void blankReadPredicateAddsNothing(String readPredicate) throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> params = new HashMap<>();
      invokeApplyWhereClause(params, adTab, null, readPredicate);

      assertNull(params.get("whereAndFilterClause"));
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    @DisplayName("ETP-5693: a blank/null javaQualifier still goes through handleWithHooks")
    void delegatesToHooksWhenQualifierBlank(String qualifier) throws Exception {
      // An @NeoExtension on a qualifier-less entity must run on REST; the hook pipeline itself
      // falls back to handleDefault when nothing resolves.
      SFEntity entity = mock(SFEntity.class);
      when(entity.getJavaQualifier()).thenReturn(qualifier);

      NeoContext context = buildContext("GET", null, null,
          entity, null, null);
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      NeoResponse expected = NeoResponse.ok(new JSONObject());
      when(servlet.handleWithHooks(qualifier, context, request, response)).thenReturn(expected);

      NeoResponse result = invokeDispatch(entity, context, request, response);

      assertEquals(expected, result);
      verify(servlet).handleWithHooks(qualifier, context, request, response);
    }
  }

  // -------------------------------------------------------------------------
  // checkJsonServiceResponse tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("checkJsonServiceResponse")
  class CheckJsonServiceResponse {

    private NeoResponse invokeCheckResponse(JSONObject responseJson) throws Exception {
      return invokeCheckResponse(responseJson, null);
    }

    /**
     * ETP-4793 / IMP-17: the method now also receives the tab, because a not-null violation has to be
     * mapped from a DB column back to the property name the caller sends. Passing {@code null} keeps
     * the pre-existing cases exactly as they were — none of them reach the mapping.
     */
    private NeoResponse invokeCheckResponse(JSONObject responseJson, Tab adTab) throws Exception {
      return (NeoResponse) invokePrivate(handler, "checkJsonServiceResponse",
          new Class<?>[] { JSONObject.class, Tab.class }, responseJson, adTab);
    }

    private JSONObject failureWithMessage(String rawMessage) throws Exception {
      JSONObject error = new JSONObject();
      error.put("message", rawMessage);
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      inner.put("error", error);
      JSONObject json = new JSONObject();
      json.put("response", inner);
      return json;
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
    @DisplayName("ETP-5577: a period refusal is a 400 carrying its message key, not a 500")
    void returns400WithKeyForPeriodRefusal() throws Exception {
      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("Periodo no válido para esta fecha de contabilidad");

        NeoResponse result = invokeCheckResponse(failureWithMessage("@PeriodNotValid@"));

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
        JSONObject error = result.getBody().getJSONObject("error");
        assertEquals("Periodo no válido para esta fecha de contabilidad", error.getString("message"));
        assertEquals("[\"PeriodNotValid\"]", error.getJSONArray("messageKeys").toString());
      }
    }

    @Test
    @DisplayName("Returns 409 when translated message reports a duplicate-key violation")
    void returns409ForDuplicateKeyMessage() throws Exception {
      JSONObject error = new JSONObject();
      error.put("message", "@BPNameSearchKeyUnique@");
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      inner.put("error", error);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      String duplicateKeyMessage = "There is already a Business Partner with the same "
          + "(Client, Organization, Search Key). (Client, Organization, Search Key) must be "
          + "unique. You must change the values entered.";

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn(duplicateKeyMessage);

        NeoResponse result = invokeCheckResponse(json);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_CONFLICT, result.getHttpStatus());
      }
    }

    @Test
    @DisplayName("Returns 500 (not 409) when translated message does not report a duplicate key)")
    void returns500ForNonDuplicateFailureMessage() throws Exception {
      JSONObject error = new JSONObject();
      error.put("message", "@GenericDatabaseError@");
      JSONObject inner = new JSONObject();
      inner.put("status", -1);
      inner.put("error", error);
      JSONObject json = new JSONObject();
      json.put("response", inner);

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn("Database connection lost");

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

    /**
     * ETP-5323 — {@code buildValidationErrorResponse}. {@code DefaultJsonDataService} catches a
     * per-property setter failure (e.g. {@code StringPropertyValidator} rejecting a
     * "Description" longer than its AD column) during JSON-to-entity conversion and reports it
     * as a {@code RPCREQUEST_STATUS_VALIDATION_ERROR} body whose message lives under
     * {@code response.errors.<propertyName>} — a sibling of {@code response.error} (singular),
     * not a variant of it. Before this fix the raw {@code responseJson} was returned verbatim,
     * a shape {@code parseBackendErrorMessage} (app-shell) does not recognize, so the frontend
     * fell back to the bare "Error 400" even though the real message was one level down.
     */
    @Test
    @DisplayName("Returns 400 with translated message under error.message for a validation-error errors map")
    void returns400WithTranslatedMessageForValidationErrorsMap() throws Exception {
      JSONObject errors = new JSONObject();
      String rawMsg = "C_OrderLine.description: Value too long. Length 2150, maximum allowed 2000 "
          + "[Some very long description text...]";
      errors.put("description", rawMsg);
      JSONObject inner = new JSONObject();
      inner.put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR);
      inner.put(JsonConstants.RESPONSE_ERRORS, errors);
      JSONObject json = new JSONObject();
      json.put(JsonConstants.RESPONSE_RESPONSE, inner);

      String translated = "Value too long. Length 2150, maximum allowed 2000 characters.";
      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(eq(rawMsg)))
            .thenReturn(translated);

        NeoResponse result = invokeCheckResponse(json);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
        JSONObject body = result.getBody();
        assertNotNull(body, "error body must not be null");
        assertTrue(body.has("error"), "body must carry a top-level 'error' object");
        assertEquals(translated, body.getJSONObject("error").getString("message"));
      }
    }

    /**
     * ETP-5323 follow-up: when the {@code errors} map is empty (e.g. the property-level
     * validation was recorded but not captured with a message), the handler must still answer a
     * generic 400 rather than throwing — the pre-existing {@code returns400ForValidationError}
     * test above covers the "no errors key at all" case; this covers "errors key present but
     * empty".
     */
    @Test
    @DisplayName("Falls back to a generic 400 message when the errors map is empty, without throwing")
    void returns400WithGenericMessageWhenErrorsMapIsEmpty() throws Exception {
      JSONObject inner = new JSONObject();
      inner.put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR);
      inner.put(JsonConstants.RESPONSE_ERRORS, new JSONObject());
      JSONObject json = new JSONObject();
      json.put(JsonConstants.RESPONSE_RESPONSE, inner);

      NeoResponse result = invokeCheckResponse(json);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      JSONObject body = result.getBody();
      assertNotNull(body, "error body must not be null");
      assertEquals("Validation failed", body.getJSONObject("error").getString("message"));
    }

    /**
     * ETP-5323 follow-up: a raw property-validation message shaped like a DAL row dump or an
     * object-reference toString must go through the same sanitization chain
     * ({@code stripRowDump}/{@code redactObjectReferences}) as the FAILURE branch above — this is
     * the "consistent between the two ways core can report a rejected write" guarantee the
     * method's own javadoc documents.
     */
    @Test
    @DisplayName("Sanitizes a row-dump-shaped validation-error message the same way as the FAILURE branch")
    void sanitizesRowDumpShapedValidationErrorMessage() throws Exception {
      // NeoErrorSanitizer.stripRowDump only redacts a parenthesised run of at least 200 chars
      // (MAX_PARENTHESISED_RUN) — mirror a real oversized Postgres "Failing row contains (…)"
      // detail rather than a short tuple, or the sanitizer has nothing to strip.
      String longTuple = "1, 2, ".repeat(40); // well over 200 chars before the closing paren
      JSONObject errors = new JSONObject();
      String rawMsg = "Some validation failure. Failing row contains (" + longTuple + ").";
      errors.put("description", rawMsg);
      JSONObject inner = new JSONObject();
      inner.put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR);
      inner.put(JsonConstants.RESPONSE_ERRORS, errors);
      JSONObject json = new JSONObject();
      json.put(JsonConstants.RESPONSE_RESPONSE, inner);

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(eq(rawMsg)))
            .thenReturn(rawMsg);

        NeoResponse result = invokeCheckResponse(json);

        assertNotNull(result);
        String message = result.getBody().getJSONObject("error").getString("message");
        // stripRowDump only redacts the parenthesised tuple itself (replacing it with "(…)") — the
        // surrounding "Failing row contains" lead-in text is untouched, same as the FAILURE branch.
        // The leak this guards against is the actual row data, so assert on that.
        assertFalse(message.contains(longTuple),
            "the long parenthesised row dump must be stripped from a validation-error message, same as FAILURE branch");
        assertTrue(message.contains("(…)"),
            "the stripped tuple must be replaced with the redaction marker, same as FAILURE branch");
      }
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

    /**
     * ETP-4793 / IMP-17 (absorbing IMP-23 §9.4): the not-null violation is the caller's mistake, so it
     * must arrive as the same {@code MISSING_REQUIRED_FIELDS} 400 a pre-flight failure produces —
     * naming the property the caller sends, not the DB column the constraint names.
     */
    @Test
    @DisplayName("Reclassifies a not-null violation as MISSING_REQUIRED_FIELDS 400 naming the property")
    void reclassifiesNotNullViolationAsMissingRequiredFields() throws Exception {
      JSONObject json = failureWithMessage("@GenericDatabaseError@");
      String violation = "ERROR: null value in column \"c_bpartner_location_id\" of relation "
          + "\"c_invoice\" violates not-null constraint";

      Table table = mock(Table.class);
      when(table.getId()).thenReturn("318");
      Tab adTab = mock(Tab.class);
      when(adTab.getTable()).thenReturn(table);

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
               Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class);
           MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn(violation);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Entity entity = mock(Entity.class);
        when(mp.getEntityByTableId("318")).thenReturn(entity);
        Property prop = mock(Property.class);
        when(prop.getColumnName()).thenReturn("C_BPartner_Location_ID");
        when(prop.getName()).thenReturn("partnerAddress");
        when(entity.getProperties()).thenReturn(List.of(prop));

        NeoResponse result = invokeCheckResponse(json, adTab);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
        JSONObject error = result.getBody().getJSONObject("error");
        assertEquals(MissingRequiredFieldsException.ERROR_CODE, error.getString("code"));
        assertEquals("partnerAddress", error.getJSONArray("fields").getString(0));
      }
    }

    /**
     * ETP-4793 / IMP-17: when the column cannot be mapped to a property the status is still corrected
     * to 400, but the response must not fall back to an empty {@code fields} array alone — and the
     * Postgres row dump (~90 columns of internals, both a leak and an ACE cost) must be stripped.
     */
    @Test
    @DisplayName("Corrects the status and strips the row dump when the column maps to no property")
    void stripsRowDumpWhenColumnUnmappable() throws Exception {
      JSONObject json = failureWithMessage("@GenericDatabaseError@");
      String violation = "ERROR: null value in column \"c_bpartner_location_id\" violates "
          + "not-null constraint  Detail: Failing row contains ("
          + "A".repeat(300) + ").";

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn(violation);

        NeoResponse result = invokeCheckResponse(json, null);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
        String body = result.getBody().toString();
        assertFalse(body.contains("AAAA"), "the failing-row dump must not reach the caller");
      }
    }

    /**
     * ETP-4793 / IMP-17: a failure that stays a 500 must still lose the row dump. The status is
     * unchanged from the pre-existing behaviour; only the payload shrinks.
     */
    @Test
    @DisplayName("Strips the row dump from a failure that remains a 500")
    void stripsRowDumpOnServerError() throws Exception {
      JSONObject json = failureWithMessage("@GenericDatabaseError@");
      String dump = "Database error  Detail: Failing row contains (" + "B".repeat(300) + ").";

      try (MockedStatic<org.openbravo.erpCommon.utility.OBMessageUtils> msgMock =
          Mockito.mockStatic(org.openbravo.erpCommon.utility.OBMessageUtils.class)) {
        msgMock.when(() -> org.openbravo.erpCommon.utility.OBMessageUtils.messageBD(
            org.mockito.ArgumentMatchers.anyString())).thenReturn(dump);

        NeoResponse result = invokeCheckResponse(json, null);

        assertNotNull(result);
        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
        assertFalse(result.getBody().toString().contains("BBBB"),
            "the failing-row dump must not reach the caller");
      }
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
      Method method = NeoDistinctFetchSupport.class.getDeclaredMethod("toDistinctEntry", Object.class);
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
  // buildDistinctProjection tests (via reflection on static method) — ETP-4770
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("buildDistinctProjection")
  class BuildDistinctProjection {

    private String invokeBuildProjection(Property prop, String resolvedProperty) throws Exception {
      Method method = NeoDistinctFetchSupport.class.getDeclaredMethod(
          "buildDistinctProjection", Property.class, String.class);
      method.setAccessible(true);
      return (String) method.invoke(null, prop, resolvedProperty);
    }

    @Test
    @DisplayName("Scalar property projects as-is")
    void scalarProjectsAsIs() throws Exception {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(true);

      assertEquals("e.documentStatus", invokeBuildProjection(prop, "documentStatus"));
    }

    @Test
    @DisplayName("Relation (FK) property projects through its .id")
    void relationProjectsThroughId() throws Exception {
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);

      assertEquals("e.businessPartner.id", invokeBuildProjection(prop, "businessPartner"));
    }
  }

  // -------------------------------------------------------------------------
  // loadIdentifiers tests (via reflection on static method) — ETP-4770
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("loadIdentifiers")
  class LoadIdentifiers {

    @SuppressWarnings("unchecked")
    private Map<String, String> invokeLoadIdentifiers(Entity targetEntity, List<Object> ids)
        throws Exception {
      Method method = NeoDistinctFetchSupport.class.getDeclaredMethod(
          "loadIdentifiers", Entity.class, List.class);
      method.setAccessible(true);
      return (Map<String, String>) method.invoke(null, targetEntity, ids);
    }

    @Test
    @DisplayName("Returns empty map for null target entity")
    void returnsEmptyMapForNullEntity() throws Exception {
      Map<String, String> result = invokeLoadIdentifiers(null, Arrays.asList("ID-1"));
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty map for null ids list")
    void returnsEmptyMapForNullIds() throws Exception {
      Entity targetEntity = mock(Entity.class);
      Map<String, String> result = invokeLoadIdentifiers(targetEntity, null);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty map for empty ids list without querying")
    void returnsEmptyMapForEmptyIds() throws Exception {
      Entity targetEntity = mock(Entity.class);
      Map<String, String> result = invokeLoadIdentifiers(targetEntity, Collections.emptyList());
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Batch-loads identifiers by id via a single IN query")
    void batchLoadsIdentifiers() throws Exception {
      Entity targetEntity = mock(Entity.class);
      when(targetEntity.getName()).thenReturn("BusinessPartner");

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        org.hibernate.Session session = mock(org.hibernate.Session.class);
        when(dal.getSession()).thenReturn(session);

        @SuppressWarnings("unchecked")
        org.hibernate.query.Query<BaseOBObject> query = mock(org.hibernate.query.Query.class);
        when(session.createQuery(anyString(), eq(BaseOBObject.class))).thenReturn(query);
        when(query.setParameterList(eq("ids"), any(List.class))).thenReturn(query);

        BaseOBObject bp1 = mock(BaseOBObject.class);
        when(bp1.getId()).thenReturn("BP-1");
        when(bp1.getIdentifier()).thenReturn("Acme Corp");
        when(query.list()).thenReturn(Arrays.asList(bp1));

        Map<String, String> result = invokeLoadIdentifiers(targetEntity, Arrays.asList("BP-1"));

        assertEquals(1, result.size());
        assertEquals("Acme Corp", result.get("BP-1"));
      }
    }

    @Test
    @DisplayName("Skips null id entries when building the IN parameter list")
    void skipsNullIdEntries() throws Exception {
      Entity targetEntity = mock(Entity.class);
      when(targetEntity.getName()).thenReturn("BusinessPartner");

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        org.hibernate.Session session = mock(org.hibernate.Session.class);
        when(dal.getSession()).thenReturn(session);

        @SuppressWarnings("unchecked")
        org.hibernate.query.Query<BaseOBObject> query = mock(org.hibernate.query.Query.class);
        when(session.createQuery(anyString(), eq(BaseOBObject.class))).thenReturn(query);
        ArgumentCaptor<List> idsCaptor = ArgumentCaptor.forClass(List.class);
        when(query.setParameterList(eq("ids"), idsCaptor.capture())).thenReturn(query);
        when(query.list()).thenReturn(Collections.emptyList());

        invokeLoadIdentifiers(targetEntity, Arrays.asList("BP-1", null, "BP-2"));

        assertEquals(Arrays.asList("BP-1", "BP-2"), idsCaptor.getValue());
      }
    }
  }

  // -------------------------------------------------------------------------
  // toRelationDistinctEntry tests (via reflection on static method) — ETP-4770
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("toRelationDistinctEntry")
  class ToRelationDistinctEntry {

    private JSONObject invokeToRelationDistinctEntry(Object value,
        Map<String, String> identifierById) throws Exception {
      Method method = NeoDistinctFetchSupport.class.getDeclaredMethod(
          "toRelationDistinctEntry", Object.class, Map.class);
      method.setAccessible(true);
      return (JSONObject) method.invoke(null, value, identifierById);
    }

    @Test
    @DisplayName("Uses the resolved identifier when present in the map")
    void usesResolvedIdentifier() throws Exception {
      Map<String, String> identifiers = new HashMap<>();
      identifiers.put("BP-1", "Acme Corp");

      JSONObject entry = invokeToRelationDistinctEntry("BP-1", identifiers);

      assertEquals("BP-1", entry.getString("id"));
      assertEquals("Acme Corp", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("Falls back to the raw id when the map has no entry for it")
    void fallsBackToIdWhenNotResolved() throws Exception {
      JSONObject entry = invokeToRelationDistinctEntry("BP-ORPHAN", new HashMap<>());

      assertEquals("BP-ORPHAN", entry.getString("id"));
      assertEquals("BP-ORPHAN", entry.getString("_identifier"));
    }

    @Test
    @DisplayName("Null value produces empty id and falls back identifier to empty")
    void nullValueProducesEmptyId() throws Exception {
      JSONObject entry = invokeToRelationDistinctEntry(null, new HashMap<>());

      assertEquals("", entry.getString("id"));
      assertEquals("", entry.getString("_identifier"));
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
      Method method = NeoDistinctFetchSupport.class.getDeclaredMethod(
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
      // Moved to NeoParentTabFilterResolver (Sonar method-count split of NeoCrudHandler) —
      // invoked here with no target instance since it is now a static helper.
      return (String) invokeStaticPrivate(NeoParentTabFilterResolver.class, "resolveTokenFromParent",
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
      return invokeDistinctFetch(adTab, queryParams, null);
    }

    private NeoResponse invokeDistinctFetch(Tab adTab, Map<String, String> queryParams,
        NeoContext context) throws Exception {
      return (NeoResponse) invokePrivate(handler, "handleDistinctFetch",
          new Class<?>[] { Tab.class, Map.class, NeoContext.class }, adTab, queryParams, context);
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

    // ETP-5561: the filtered set now comes from NeoListAggregateQuery (the list's own params),
    // so these tests stub that seam instead of the OBDal/OBQuery chain.

    private Tab distinctTab(String tableName) {
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      Window window = mock(Window.class);
      when(table.getName()).thenReturn(tableName);
      when(adTab.getTable()).thenReturn(table);
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);
      return adTab;
    }

    private Property stubProperty(ModelProvider mp, String entityName, String propName,
        boolean primitive) {
      Entity entity = mock(Entity.class);
      when(mp.getEntity(entityName)).thenReturn(entity);
      Property prop = mock(Property.class);
      when(prop.getName()).thenReturn(propName);
      when(prop.isPrimitive()).thenReturn(primitive);
      when(entity.getProperty(propName, false)).thenReturn(prop);
      return prop;
    }

    /** Stubs {@code forParams} to hand back a query whose distinct page is {@code rows}. */
    private NeoListAggregateQuery stubQuery(MockedStatic<NeoListAggregateQuery> agg,
        ArgumentCaptor<Map<String, String>> paramsCaptor, List<Object> rows) {
      NeoListAggregateQuery query = mock(NeoListAggregateQuery.class);
      agg.when(() -> NeoListAggregateQuery.forParams(paramsCaptor.capture())).thenReturn(query);
      when(query.distinctValues(anyString(), any(), any(), anyInt(), anyInt())).thenReturn(rows);
      return query;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, String>> newParamsCaptor() {
      return ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
    }

    @Test
    @DisplayName("Returns success response for valid distinct query")
    void validDistinctQueryReturnsOk() throws Exception {
      Tab adTab = distinctTab("C_Order");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "documentStatus");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "C_Order", "documentStatus", true);
        NeoListAggregateQuery query = stubQuery(agg, newParamsCaptor(),
            Arrays.asList("CO", "DR", "VO"));

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        JSONObject response = result.getBody().getJSONObject("response");
        JSONArray data = response.getJSONArray("data");
        assertEquals(3, data.length());
        assertEquals("CO", data.getJSONObject(0).getString("id"));
        assertFalse(response.getBoolean("hasMore"));
        verify(query).distinctValues(eq("e.documentStatus"), eq(null), eq(null), eq(0), eq(51));
      }
    }

    /**
     * ETP-5561: the params handed to the query are the list's own, with everything that would
     * change what core builds removed before the query service exists.
     */
    @Test
    @DisplayName("ETP-5561: criteria reaches the query; _distinct, search, sort and id are stripped")
    void forwardsCriteriaAndStripsDistinctSortAndId() throws Exception {
      Tab adTab = distinctTab("Invoice");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "documentStatus");
      params.put("_distinctSearch", "dra");
      params.put("criteria", "{\"fieldName\":\"x\"}");
      params.put("_sortBy", "documentNo");
      params.put("_orderBy", "documentNo");
      params.put("_startRow", "10");
      params.put("_endRow", "19");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);
      ArgumentCaptor<Map<String, String>> captor = newParamsCaptor();

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "Invoice", "documentStatus", true);
        NeoListAggregateQuery query = stubQuery(agg, captor, Collections.emptyList());

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        Map<String, String> forwarded = captor.getValue();
        assertEquals("{\"fieldName\":\"x\"}", forwarded.get("criteria"));
        for (String stripped : new String[] { "_distinct", "_distinctSearch", "_sortBy",
            "_orderBy", "id" }) {
          assertFalse(forwarded.containsKey(stripped), stripped + " must not reach core");
        }
        assertEquals("10", forwarded.get("_startRow"));
        assertEquals("19", forwarded.get("_endRow"));
        verify(query).distinctValues(eq("e.documentStatus"),
            eq("LOWER(CAST(e.documentStatus AS string)) LIKE :search"),
            eq(Map.of("search", "%dra%")), eq(10), eq(11));
      }
    }

    /**
     * ETP-5561 security: {@code id} makes {@code forRestListGet} return null (a read by id), and
     * the fetch drops {@code id} before core sees it — so the read predicate must be re-applied
     * here or {@code &id=x} would widen the values offered.
     */
    @Test
    @DisplayName("ETP-5561: a request carrying id still applies the customization read predicate")
    void idParamDoesNotDropReadPredicate() throws Exception {
      Tab adTab = distinctTab("Product");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "productType");
      params.put("id", "SOME-ID");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);
      ArgumentCaptor<Map<String, String>> captor = newParamsCaptor();

      NeoHandler customization = mock(NeoHandler.class);
      when(customization.readPredicates(any())).thenReturn(Collections.singletonList("e.x = 1"));

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(customization);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "Product", "productType", true);
        stubQuery(agg, captor, Collections.emptyList());

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        Map<String, String> forwarded = captor.getValue();
        assertFalse(forwarded.containsKey("id"));
        assertEquals("(e.x = 1)", forwarded.get("whereAndFilterClause"));
      }
    }

    @Test
    @DisplayName("Relation (FK) property projects through .id and resolves display "
        + "identifiers instead of failing with a Postgres SELECT DISTINCT/ORDER BY "
        + "mismatch (ETP-4770)")
    void relationPropertyProjectsThroughIdAndResolvesIdentifiers() throws Exception {
      Tab adTab = distinctTab("Invoice");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "businessPartner");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        // businessPartner is a to-one association (FK), not a scalar column.
        Property bpProp = stubProperty(mp, "Invoice", "businessPartner", false);
        Entity bpEntity = mock(Entity.class);
        when(bpProp.getTargetEntity()).thenReturn(bpEntity);
        when(bpEntity.getName()).thenReturn("BusinessPartner");

        // The projected/ordered page: raw FK ids (not BaseOBObject references).
        NeoListAggregateQuery query = stubQuery(agg, newParamsCaptor(),
            Arrays.asList("BP-1", "BP-2"));

        // Batch identifier lookup issued by NeoDistinctFetchSupport.loadIdentifiers.
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        org.hibernate.Session session = mock(org.hibernate.Session.class);
        when(dal.getSession()).thenReturn(session);
        @SuppressWarnings("unchecked")
        org.hibernate.query.Query<BaseOBObject> identifierQuery = mock(org.hibernate.query.Query.class);
        when(session.createQuery(anyString(), eq(BaseOBObject.class))).thenReturn(identifierQuery);
        when(identifierQuery.setParameterList(eq("ids"), any(List.class))).thenReturn(identifierQuery);

        BaseOBObject bp1 = mock(BaseOBObject.class);
        when(bp1.getId()).thenReturn("BP-1");
        when(bp1.getIdentifier()).thenReturn("Sleep Well Hotels, Co.");
        BaseOBObject bp2 = mock(BaseOBObject.class);
        when(bp2.getId()).thenReturn("BP-2");
        when(bp2.getIdentifier()).thenReturn("Hoteles Buenas Noches, S.A.");
        when(identifierQuery.list()).thenReturn(Arrays.asList(bp1, bp2));

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());

        // The .id projection is what avoids Postgres's
        // "for SELECT DISTINCT, ORDER BY expressions must appear in select list".
        verify(query).distinctValues(eq("e.businessPartner.id"), eq(null), eq(null), eq(0),
            eq(51));

        JSONObject response = result.getBody().getJSONObject("response");
        JSONArray data = response.getJSONArray("data");
        assertEquals(2, data.length());
        assertEquals("BP-1", data.getJSONObject(0).getString("id"));
        assertEquals("Sleep Well Hotels, Co.", data.getJSONObject(0).getString("_identifier"));
        assertEquals("BP-2", data.getJSONObject(1).getString("id"));
        assertEquals("Hoteles Buenas Noches, S.A.", data.getJSONObject(1).getString("_identifier"));
      }
    }

    @Test
    @DisplayName("Relation property with an id that has no matching record falls back "
        + "to the raw id as its identifier")
    void relationPropertyMissingRecordFallsBackToId() throws Exception {
      Tab adTab = distinctTab("Invoice");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "businessPartner");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Property bpProp = stubProperty(mp, "Invoice", "businessPartner", false);
        Entity bpEntity = mock(Entity.class);
        when(bpProp.getTargetEntity()).thenReturn(bpEntity);
        when(bpEntity.getName()).thenReturn("BusinessPartner");
        stubQuery(agg, newParamsCaptor(), Collections.singletonList("BP-ORPHAN"));

        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        org.hibernate.Session session = mock(org.hibernate.Session.class);
        when(dal.getSession()).thenReturn(session);
        @SuppressWarnings("unchecked")
        org.hibernate.query.Query<BaseOBObject> identifierQuery = mock(org.hibernate.query.Query.class);
        when(session.createQuery(anyString(), eq(BaseOBObject.class))).thenReturn(identifierQuery);
        when(identifierQuery.setParameterList(eq("ids"), any(List.class))).thenReturn(identifierQuery);
        // No matching record comes back (e.g. deleted after the distinct scan).
        when(identifierQuery.list()).thenReturn(Collections.emptyList());

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        JSONObject response = result.getBody().getJSONObject("response");
        JSONArray data = response.getJSONArray("data");
        assertEquals(1, data.length());
        assertEquals("BP-ORPHAN", data.getJSONObject(0).getString("id"));
        assertEquals("BP-ORPHAN", data.getJSONObject(0).getString("_identifier"));
      }
    }

    @Test
    @DisplayName("Relation property with an empty result page never issues the "
        + "batch identifier lookup query")
    void relationPropertyEmptyPageSkipsIdentifierLookup() throws Exception {
      Tab adTab = distinctTab("Invoice");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "businessPartner");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        Property bpProp = stubProperty(mp, "Invoice", "businessPartner", false);
        when(bpProp.getTargetEntity()).thenReturn(mock(Entity.class));
        stubQuery(agg, newParamsCaptor(), Collections.emptyList());
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        JSONObject response = result.getBody().getJSONObject("response");
        assertEquals(0, response.getJSONArray("data").length());
        // dal.getSession() is never touched when there is nothing to resolve.
        Mockito.verify(dal, never()).getSession();
      }
    }

    @Test
    @DisplayName("Search on a relation property targets the related entity's identifier, "
        + "not a CAST of the relation itself (ETP-4609)")
    void searchOnRelationPropertyTargetsIdentifier() throws Exception {
      Tab adTab = distinctTab("M_Product");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "productCategory");
      params.put("_distinctSearch", "beverages");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        // productCategory is a to-one relation (foreign key), not a scalar column.
        Property categoryProp = stubProperty(mp, "M_Product", "productCategory", false);
        Entity categoryEntity = mock(Entity.class);
        when(categoryProp.getTargetEntity()).thenReturn(categoryEntity);
        Property nameProp = mock(Property.class);
        when(nameProp.getName()).thenReturn("name");
        when(nameProp.isPrimitive()).thenReturn(true);
        when(categoryEntity.getIdentifierProperties())
            .thenReturn(Collections.singletonList(nameProp));
        NeoListAggregateQuery query = stubQuery(agg, newParamsCaptor(),
            Collections.emptyList());

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        ArgumentCaptor<String> predicate = ArgumentCaptor.forClass(String.class);
        verify(query).distinctValues(eq("e.productCategory.id"), predicate.capture(),
            eq(Map.of("search", "%beverages%")), eq(0), eq(51));
        assertTrue(predicate.getValue().contains("e.productCategory.name"),
            "Expected the search to target the related entity's identifier property ('name'), "
                + "was: " + predicate.getValue());
        assertFalse(predicate.getValue().contains("CAST(e.productCategory AS string)"),
            "Search must not CAST the relation property itself to string");
      }
    }

    @Test
    @DisplayName("Sets hasMore=true when results exceed page size")
    void hasMoreTrueWhenExceedsPageSize() throws Exception {
      Tab adTab = distinctTab("C_Order");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");
      params.put("_startRow", "0");
      params.put("_endRow", "1"); // page size = 2
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "C_Order", "status", true);
        // 3 results, page size = 2, so hasMore = true
        stubQuery(agg, newParamsCaptor(), Arrays.asList("A", "B", "C"));

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        JSONObject response = result.getBody().getJSONObject("response");
        assertTrue(response.getBoolean("hasMore"));
        assertEquals(2, response.getJSONArray("data").length());
      }
    }

    @Test
    @DisplayName("Returns 500 when the distinct query throws")
    void queryExceptionReturns500() throws Exception {
      Tab adTab = distinctTab("C_Order");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "status");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "C_Order", "status", true);
        NeoListAggregateQuery query = mock(NeoListAggregateQuery.class);
        agg.when(() -> NeoListAggregateQuery.forParams(any())).thenReturn(query);
        when(query.distinctValues(anyString(), any(), any(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("DB error"));

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      }
    }

    /**
     * ETP-5009 regression: the Product window's Categoría / Tipo filters offered "Discounts" and
     * Servicio, values only the hidden {@code ETGO_DTO} product carries. The customization's read
     * predicate must be part of the filtered set the distinct query runs over.
     */
    @Test
    @DisplayName("ETP-5009: product _distinct includes the system-category exclusion in the where")
    void productDistinctExcludesSystemCategoryProducts() throws Exception {
      Tab adTab = distinctTab("Product");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "productType");
      NeoContext context = NeoContext.builder()
          .specName("product").entityName("product")
          .httpMethod("GET").queryParams(params)
          .adTab(adTab).sfEntity(mock(SFEntity.class))
          .obContext(mock(OBContext.class))
          .endpointType(NeoEndpointType.CRUD)
          .build();
      ArgumentCaptor<Map<String, String>> captor = newParamsCaptor();

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(new ProductDefaultsHandler());
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "Product", "productType", true);
        stubQuery(agg, captor, Arrays.asList("I"));

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        String where = captor.getValue().get("whereAndFilterClause");
        assertEquals("((" + ProductDefaultsHandler.EXCLUDE_SYSTEM_CATEGORY_PREDICATE + "))", where);
      }
    }

    @Test
    @DisplayName("Distinct fetch with no customization keeps the where clause free of read predicates")
    void distinctWithoutCustomizationAddsNoPredicate() throws Exception {
      Tab adTab = distinctTab("C_Order");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "documentStatus");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);
      ArgumentCaptor<Map<String, String>> captor = newParamsCaptor();

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "C_Order", "documentStatus", true);
        stubQuery(agg, captor, Arrays.asList("CO"));

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(200, result.getHttpStatus());
        assertFalse(captor.getValue().containsKey("whereAndFilterClause"),
            "No tab where, parent filter or read predicate expected");
      }
    }

    /**
     * ETP-5009: a customization whose predicate throws must fail the fetch rather than offer
     * the values of the rows it was meant to exclude.
     */
    @Test
    @DisplayName("Distinct fetch returns 500 when the customization's read predicate throws")
    void distinctFailsWhenReadPredicateThrows() throws Exception {
      Tab adTab = distinctTab("C_Order");
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "documentStatus");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, params);

      NeoHandler customization = mock(NeoHandler.class);
      when(customization.readPredicates(any()))
          .thenThrow(new IllegalStateException("predicate failure"));

      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(customization);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "C_Order", "documentStatus", true);

        NeoResponse result = invokeDistinctFetch(adTab, params, context);

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
        agg.verify(() -> NeoListAggregateQuery.forParams(any()), never());
      }
    }

    /** What one stubbed distinct fetch produced: the response, the forwarded params, the query. */
    private final class DistinctRun {
      NeoResponse result;
      Map<String, String> forwarded;
      NeoListAggregateQuery query;
    }

    /**
     * Runs a scalar {@code documentStatus} distinct fetch over a stubbed query seam. The tab where
     * and parent filter the (statically stubbed) parent-tab resolver returns are passed in.
     */
    private DistinctRun runDistinct(Tab adTab, Map<String, String> params, String recordId,
        NeoHandler customization, String tabWhere, String parentFilter) throws Exception {
      NeoContext context = buildContext("GET", recordId, adTab, mock(SFEntity.class), null, params);
      ArgumentCaptor<Map<String, String>> captor = newParamsCaptor();
      DistinctRun run = new DistinctRun();
      try (MockedStatic<ModelProvider> mpMock = Mockito.mockStatic(ModelProvider.class);
           MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class);
           MockedStatic<NeoParentTabFilterResolver> resolver =
               Mockito.mockStatic(NeoParentTabFilterResolver.class);
           MockedStatic<NeoListAggregateQuery> agg =
               Mockito.mockStatic(NeoListAggregateQuery.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(customization);
        resolver.when(() -> NeoParentTabFilterResolver.resolveTabWhere(any(), any()))
            .thenReturn(tabWhere);
        resolver.when(() -> NeoParentTabFilterResolver.resolveParentFilter(any(), any()))
            .thenReturn(parentFilter);
        ModelProvider mp = mock(ModelProvider.class);
        mpMock.when(ModelProvider::getInstance).thenReturn(mp);
        stubProperty(mp, "C_Order", "documentStatus", true);
        run.query = stubQuery(agg, captor, Collections.emptyList());

        run.result = invokeDistinctFetch(adTab, params, context);
        if (!captor.getAllValues().isEmpty()) {
          run.forwarded = captor.getValue();
        }
      }
      return run;
    }

    private Map<String, String> distinctParams() {
      Map<String, String> params = new HashMap<>();
      params.put("_distinct", "documentStatus");
      return params;
    }

    private NeoHandler readPredicateCustomization(String predicate) {
      NeoHandler customization = mock(NeoHandler.class);
      when(customization.readPredicates(any())).thenReturn(Collections.singletonList(predicate));
      return customization;
    }

    @Test
    @DisplayName("A path recordId is stripped like the query-string id and keeps the read predicate")
    void pathRecordIdIsStrippedAndKeepsReadPredicate() throws Exception {
      DistinctRun run = runDistinct(distinctTab("C_Order"), distinctParams(), "PATH-ID",
          readPredicateCustomization("e.x = 1"), null, null);

      assertEquals(200, run.result.getHttpStatus());
      assertFalse(run.forwarded.containsKey("id"), "the path id must not reach core");
      assertEquals("(e.x = 1)", run.forwarded.get("whereAndFilterClause"));
    }

    @Test
    @DisplayName("An id with no customization adds no read predicate to the where clause")
    void idWithoutCustomizationAddsNoReadPredicate() throws Exception {
      Map<String, String> params = distinctParams();
      params.put("id", "SOME-ID");

      DistinctRun run = runDistinct(distinctTab("C_Order"), params, null, null, null, null);

      assertEquals(200, run.result.getHttpStatus());
      assertFalse(run.forwarded.containsKey("id"));
      assertFalse(run.forwarded.containsKey("whereAndFilterClause"));
    }

    @Test
    @DisplayName("An id with a throwing read predicate fails with 500 and never queries")
    void idWithThrowingReadPredicateReturns500() throws Exception {
      Map<String, String> params = distinctParams();
      params.put("id", "SOME-ID");
      NeoHandler customization = mock(NeoHandler.class);
      when(customization.readPredicates(any()))
          .thenThrow(new IllegalStateException("predicate failure"));

      DistinctRun run = runDistinct(distinctTab("C_Order"), params, null, customization, null,
          null);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, run.result.getHttpStatus());
      assertNull(run.forwarded, "no query may be built when the read predicate cannot be resolved");
      verify(run.query, never()).distinctValues(anyString(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("A child tab with parentId forwards the resolved tab where and the parent filter")
    void childTabForwardsParentFilterAndResolvedTabWhere() throws Exception {
      Tab adTab = distinctTab("C_Order");
      when(adTab.getTabLevel()).thenReturn(1L);
      Map<String, String> params = distinctParams();
      params.put("parentId", "PARENT-1");

      DistinctRun run = runDistinct(adTab, params, null, null, "e.processed = false",
          "e.salesOrder.id = 'PARENT-1'");

      assertEquals(200, run.result.getHttpStatus());
      assertEquals("(e.processed = false) and (e.salesOrder.id = 'PARENT-1')",
          run.forwarded.get("whereAndFilterClause"));
    }

    @Test
    @DisplayName("Paging: a request above the page cap is capped at 200 rows (+1 for hasMore)")
    void pageSizeAboveTheCapIsCapped() throws Exception {
      Map<String, String> params = distinctParams();
      params.put("_startRow", "0");
      params.put("_endRow", "999");

      DistinctRun run = runDistinct(distinctTab("C_Order"), params, null, null, null, null);

      verify(run.query).distinctValues(eq("e.documentStatus"), eq(null), eq(null), eq(0), eq(201));
      assertEquals("0", run.forwarded.get("_startRow"));
      assertEquals("199", run.forwarded.get("_endRow"));
    }

    @Test
    @DisplayName("Paging: endRow before startRow falls back to the default page of 50")
    void endRowBeforeStartRowFallsBackToDefaultPage() throws Exception {
      Map<String, String> params = distinctParams();
      params.put("_startRow", "30");
      params.put("_endRow", "10");

      DistinctRun run = runDistinct(distinctTab("C_Order"), params, null, null, null, null);

      verify(run.query).distinctValues(eq("e.documentStatus"), eq(null), eq(null), eq(30), eq(51));
      assertEquals("30", run.forwarded.get("_startRow"));
      assertEquals("79", run.forwarded.get("_endRow"));
    }

    @Test
    @DisplayName("Server-owned where params sent by the client never reach the query")
    void clientSentWhereParamsNeverReachTheQuery() throws Exception {
      Map<String, String> params = distinctParams();
      params.put("_where", "1=1 or e.x = 'leak'");
      params.put("_neoWhere", "1=1");
      params.put("whereAndFilterClause", "1=1");

      DistinctRun run = runDistinct(distinctTab("C_Order"), params, null, null, null, null);

      assertEquals(200, run.result.getHttpStatus());
      assertFalse(run.forwarded.containsKey("_where"));
      assertFalse(run.forwarded.containsKey("_neoWhere"));
      assertFalse(run.forwarded.containsKey("whereAndFilterClause"));
    }

    @Test
    @DisplayName("A client-sent whereAndFilterClause is replaced by the server-built clause")
    void clientWhereParamsAreReplacedByTheServerClause() throws Exception {
      Map<String, String> params = distinctParams();
      params.put("whereAndFilterClause", "1=1 or e.x = 'leak'");

      DistinctRun run = runDistinct(distinctTab("C_Order"), params, null,
          readPredicateCustomization("e.y = 2"), null, null);

      assertEquals("((e.y = 2))", run.forwarded.get("whereAndFilterClause"));
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

    private Tab childTab() {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-L");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getTabLevel()).thenReturn(1L);
      return adTab;
    }

    @Test
    @DisplayName("ETP-5611: a list GET applies the tab's default sort")
    void listGetAppliesTabDefaultSort() throws Exception {
      Tab adTab = childTab();
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, null);
      try (MockedStatic<NeoTabDefaultSort> sort = mockStatic(NeoTabDefaultSort.class)) {
        Map<String, String> params = invokeBuildDalParams(context, adTab, "GL_JournalLine");
        sort.verify(() -> NeoTabDefaultSort.applyIfAbsent(params, adTab, "GL_JournalLine"));
      }
    }

    @Test
    @DisplayName("ETP-5611: a single-record GET or a write never gets the default sort")
    void recordGetAndWritesSkipTabDefaultSort() throws Exception {
      Tab adTab = childTab();
      try (MockedStatic<NeoTabDefaultSort> sort = mockStatic(NeoTabDefaultSort.class)) {
        invokeBuildDalParams(buildContext("GET", "REC-1", adTab, mock(SFEntity.class), null, null),
            adTab, "GL_JournalLine");
        invokeBuildDalParams(buildContext("POST", null, adTab, mock(SFEntity.class), null, null),
            adTab, "GL_JournalLine");
        sort.verify(() -> NeoTabDefaultSort.applyIfAbsent(any(), any(Tab.class), anyString()), never());
      }
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

    /**
     * ETP-5568: core splices whereAndFilterClause (and _where) into the HQL text verbatim, and
     * _neoWhere used to be ANDed in the same way. None of them may come from the query string:
     * the where clause is exactly the one the server builds.
     */
    @Test
    @DisplayName("ETP-5568: HQL-carrying query params never reach the where clause")
    void queryStringCannotInjectHql() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> qp = new HashMap<>();
      qp.put("_neoWhere", "1=1) or (1=1");
      qp.put("whereAndFilterClause", "1=1");
      qp.put("_where", "1=1");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, qp);

      Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

      assertEquals("(e.active = true)", params.get("whereAndFilterClause"));
      assertFalse(params.containsKey("_neoWhere"));
      assertFalse(params.containsKey("_where"));
    }

    /**
     * ETP-5568: with no tab where, no parent and no read predicate the server sets no where
     * clause at all — a client-sent one used to pass straight through to core in that case.
     */
    @Test
    @DisplayName("ETP-5568: a client whereAndFilterClause is dropped when the server sets none")
    void clientWhereClauseDroppedWhenServerHasNone() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> qp = new HashMap<>();
      qp.put("whereAndFilterClause", "exists (select 1 from ADUser u where u.password like 'a%')");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, qp);

      Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

      assertFalse(params.containsKey("whereAndFilterClause"));
    }

    /**
     * ETP-5009: a list GET (no record id) asks the entity's customization for its read
     * predicates and ANDs them into the where clause core's list query, count and paging share.
     */
    @Test
    @DisplayName("List GET ANDs the customization's read predicates into the where clause")
    void listGetAppliesReadPredicates() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn("e.active = true");
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoHandler customization = mock(NeoHandler.class);
      when(customization.readPredicates(any())).thenReturn(List.of("e.hidden = false"));
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, null);

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(customization);

        Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

        assertEquals("(e.active = true) and ((e.hidden = false))",
            params.get("whereAndFilterClause"));
        assertEquals("true", params.get("_use_alias"));
      }
    }

    /**
     * ETP-5009: a read by id is resolved by core with its own {@code id = :id} query, which
     * ignores the where clause — so the customization is not even resolved for it.
     */
    @Test
    @DisplayName("GET by record id does not resolve read predicates")
    void getByIdDoesNotResolveReadPredicates() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = buildContext("GET", "REC-123", adTab,
          mock(SFEntity.class), null, null);

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class)) {
        Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

        dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
        assertNull(params.get("whereAndFilterClause"));
      }
    }

    /**
     * ETP-5009 (W1): {@code GET …?id=X} with no path id is a read by id as well — core fetches it
     * by id, ignoring the where clause — so the predicates are skipped for it exactly as for the
     * path form, and the id is still handed to core.
     */
    @Test
    @DisplayName("GET with a query-string id and no path id does not resolve read predicates")
    void getByQueryIdDoesNotResolveReadPredicates() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      Map<String, String> qp = new HashMap<>();
      qp.put("id", "REC-QUERY");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, qp);

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class)) {
        Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

        dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
        assertNull(params.get("whereAndFilterClause"));
        assertEquals("REC-QUERY", params.get("id"));
      }
    }

    /**
     * A blank {@code ?id=} names no record, so the request stays a list read and the predicates
     * still apply — the skip must not widen into "any request carrying an id key".
     */
    @Test
    @DisplayName("GET with a blank query-string id still applies read predicates")
    void getWithBlankQueryIdAppliesReadPredicates() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoHandler customization = mock(NeoHandler.class);
      when(customization.readPredicates(any())).thenReturn(List.of("e.hidden = false"));
      Map<String, String> qp = new HashMap<>();
      qp.put("id", " ");
      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, qp);

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any()))
            .thenReturn(customization);

        Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

        dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()));
        assertNotNull(params.get("whereAndFilterClause"));
        assertTrue(params.get("whereAndFilterClause").contains("e.hidden = false"));
      }
    }

    @Test
    @DisplayName("Non-GET request does not resolve read predicates")
    void nonGetDoesNotResolveReadPredicates() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = buildContext("DELETE", null, adTab, mock(SFEntity.class), null, null);

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class)) {
        invokeBuildDalParams(context, adTab, "C_Order");

        dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(any()), never());
      }
    }

    @Test
    @DisplayName("List GET with no customization leaves the where clause untouched")
    void listGetWithoutCustomizationAddsNothing() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getId()).thenReturn("TAB-1");
      Window window = mock(Window.class);
      when(window.getId()).thenReturn("WIN-1");
      when(adTab.getWindow()).thenReturn(window);
      when(adTab.getHqlwhereclause()).thenReturn(null);
      when(adTab.getTabLevel()).thenReturn(0L);

      NeoContext context = buildContext("GET", null, adTab, mock(SFEntity.class), null, null);

      try (MockedStatic<NeoExtensionDispatcher> dispatcher =
               Mockito.mockStatic(NeoExtensionDispatcher.class)) {
        dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);

        Map<String, String> params = invokeBuildDalParams(context, adTab, "C_Order");

        assertNull(params.get("whereAndFilterClause"));
      }
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
              java.util.Set.class, java.util.Set.class },
          body, null, context, Collections.emptySet(), Collections.emptySet());
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
            any(), any(), any(), any(), any(), any(), any())).then(invocation -> null);
        cascadeMock.when(() -> NeoDefaultsCascadeHelper.removeEmptyFkValues(
            any(), any())).then(invocation -> null);
        docTypeMock.when(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
            any(), any(), any())).then(invocation -> null);

        invokePrivate(handler, "executePostCalloutCascade",
            new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
                java.util.Set.class, java.util.Set.class },
            body, adTab, context, new HashSet<>(), new HashSet<>());

        // Verify cascade was called (no exception)
        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            any(), any(), any(), any(), any(), any(), any()));
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
            any(), any(), any(), any(), any(), any(), any())).then(invocation -> null);
        cascadeMock.when(() -> NeoDefaultsCascadeHelper.removeEmptyFkValues(
            any(), any())).then(invocation -> null);
        docTypeMock.when(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
            any(), any(), any())).then(invocation -> null);

        // Pass null for protectedFields
        invokePrivate(handler, "executePostCalloutCascade",
            new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
                java.util.Set.class, java.util.Set.class },
            body, adTab, context, (Set<String>) null, (Set<String>) null);

        // Should not throw NPE
        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            any(), any(), any(), any(), any(), any(), any()));
      }
    }

    /**
     * ETP-5676: inside a batch the cascade gets the batch's callout-discard memory; outside one it
     * gets {@code null}, so a single REST or MCP create runs every callout as before.
     */
    @Test
    @DisplayName("Hands the cascade the batch's discard memory only inside a batch")
    void passesTheBatchDiscardMemoryOnlyInsideABatch() throws Exception {
      Tab adTab = mock(Tab.class);
      JSONObject body = new JSONObject();
      NeoContext context = buildContext("POST", null, adTab, mock(SFEntity.class), body, null);
      Class<?>[] signature = { JSONObject.class, Tab.class, NeoContext.class,
          java.util.Set.class, java.util.Set.class };

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
               Mockito.mockStatic(NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock =
               Mockito.mockStatic(DocTypeResolver.class)) {
        invokePrivate(handler, "executePostCalloutCascade", signature,
            body, adTab, context, Collections.emptySet(), Collections.emptySet());
        cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
            eq(context), eq(adTab), any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.isNull()));

        NeoBatchScope previous = NeoBatchScope.open(false, true);
        try {
          NeoCalloutDiscardMemory memory = NeoBatchScope.currentCalloutDiscardMemory();
          assertNotNull(memory);
          invokePrivate(handler, "executePostCalloutCascade", signature,
              body, adTab, context, Collections.emptySet(), Collections.emptySet());
          cascadeMock.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(
              eq(context), eq(adTab), any(), any(), any(), any(),
              org.mockito.ArgumentMatchers.same(memory)));
        } finally {
          NeoBatchScope.close(previous);
        }
      }
    }

    @Test
    @DisplayName("Runs the tab-aware document type resolver for every CRUD create path")
    void runsTabAwareDocumentTypeResolver() throws Exception {
      Tab adTab = mock(Tab.class);
      when(adTab.getTabLevel()).thenReturn(0L);
      JSONObject body = new JSONObject().put("documentType", "quotation");
      NeoContext context = buildContext("POST", null, adTab,
          mock(SFEntity.class), body, null);

      try (MockedStatic<NeoDefaultsCascadeHelper> cascadeMock =
               Mockito.mockStatic(NeoDefaultsCascadeHelper.class);
           MockedStatic<DocTypeResolver> docTypeMock =
               Mockito.mockStatic(DocTypeResolver.class)) {
        invokePrivate(handler, "executePostCalloutCascade",
            new Class<?>[] { JSONObject.class, Tab.class, NeoContext.class,
                java.util.Set.class, java.util.Set.class },
            body, adTab, context, Collections.emptySet(), Collections.emptySet());

        docTypeMock.verify(() -> DocTypeResolver.reapplyDocTypeFromTabFilter(
            body, adTab, context, Collections.emptySet()));
      }
    }
  }

  // -------------------------------------------------------------------------
  // resolveParentFilter tests (via reflection)
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveParentFilter")
  class ResolveParentFilter {

    private String invokeResolveParentFilter(Tab childTab, String parentId) {
      // Moved to NeoParentTabFilterResolver (Sonar method-count split of NeoCrudHandler) as a
      // package-private static method — callable directly, no reflection needed.
      return NeoParentTabFilterResolver.resolveParentFilter(childTab, parentId);
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
  // ETP-5051: the POST create runs inside the plan-quota gate
  // -------------------------------------------------------------------------

  /**
   * The REST single and batch create runs its insert inside {@code NeoQuotaWriteGate}: the gate
   * picks the service, checks the created row after the insert, a refusal is returned, and the
   * caller goes on with the gate's result (core's error JSON when the gate's own commit failed).
   * The POST path needs a live DAL and an {@code AD_Tab}, so the wiring is pinned on the source,
   * anchored on the calls rather than on local variable names.
   */
  @Nested
  @DisplayName("the POST create runs inside the plan-quota gate (ETP-5051)")
  class QuotaGateWiring {

    /**
     * Also the ETP-5051 re-review W1 guard: the create ({@code executePostCreate}, callout cascade
     * and add) is what the handler hands to {@code NeoQuotaWriteGate.run}, so the gate can refuse
     * before it runs; {@code NeoQuotaWriteGateTest} shows it is never invoked on that refusal. The
     * gate's {@code begin} is package-private, so no handler can open the gate around a create of
     * its own.
     */
    @Test
    void theCreateRunsItsInsertInsideTheGate() throws Exception {
      String source = readSource("com/etendoerp/go/schemaforge/NeoCrudHandler.java")
          .replaceAll("\\s+", " ");

      int run = source.indexOf("NeoQuotaWriteGate.run(");
      int insert = source.indexOf("executePostCreate(", run);
      int refusal = source.indexOf(".refusal()", run);
      int returned = source.indexOf("return ", refusal);
      int result = source.indexOf(".result()", returned);

      assertTrue(run >= 0, "NeoCrudHandler no longer runs its create inside the quota gate");
      assertTrue(insert > run && insert < refusal,
          "the create is no longer handed to the gate (it must run inside run(...))");
      assertTrue(returned > refusal, "a quota refusal is no longer returned to the caller");
      assertTrue(result > returned, "the caller no longer goes on with the gate's result");
    }

    private String readSource(String relative) throws java.io.IOException {
      java.nio.file.Path fromRoot = java.nio.file.Paths.get("modules/com.etendoerp.go/src",
          relative);
      java.nio.file.Path file = java.nio.file.Files.exists(fromRoot) ? fromRoot
          : java.nio.file.Paths.get("src", relative);
      return new String(java.nio.file.Files.readAllBytes(file),
          java.nio.charset.StandardCharsets.UTF_8);
    }
  }
}
