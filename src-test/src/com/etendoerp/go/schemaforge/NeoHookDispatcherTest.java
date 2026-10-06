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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAuditTokenRefresh;

/**
 * Unit tests for {@link NeoHookDispatcher}.
 * Verifies hook resolution, chain execution, and error handling.
 */
class NeoHookDispatcherTest {

  private NeoServlet servlet;
  /**
   * ETP-5415: resolution moved off {@code servlet.lookupHandler} (an instance call) onto
   * {@code NeoServletSupport.lookupHandler} (static), because {@link NeoHookDispatcher} now
   * dispatches through {@code NeoExtensionDispatcher} — one resolution order and one trace for
   * every path. The assertions below are unchanged in substance; only the seam they stub moved.
   */
  private MockedStatic<NeoServletSupport> servletSupportStatic;
  private NeoHookDispatcher dispatcher;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<NeoAuditTokenRefresh> auditTokenRefreshStatic;

  private SFSpec spec;
  private NeoResponse defaultResponse;
  private Supplier<NeoResponse> defaultAction;
  private AtomicBoolean defaultActionCalled;

  @BeforeEach
  void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    dispatcher = new NeoHookDispatcher(servlet);

    obContextStatic = Mockito.mockStatic(OBContext.class);
    auditTokenRefreshStatic = Mockito.mockStatic(NeoAuditTokenRefresh.class);
    servletSupportStatic = Mockito.mockStatic(NeoServletSupport.class);
    OBContext mockOBContext = mock(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(mockOBContext);

    spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-id-1");
    when(spec.getName()).thenReturn("TestSpec");

    defaultResponse = NeoResponse.ok(new JSONObject().put("source", "default"));
    defaultActionCalled = new AtomicBoolean(false);
    defaultAction = () -> {
      defaultActionCalled.set(true);
      return defaultResponse;
    };
  }

  @AfterEach
  void tearDown() {
    if (obContextStatic != null) {
      obContextStatic.close();
    }
    if (auditTokenRefreshStatic != null) {
      auditTokenRefreshStatic.close();
    }
    if (servletSupportStatic != null) {
      servletSupportStatic.close();
    }
  }

  // ── dispatchWithHooks: no entity found → default action ──

  @Test
  @DisplayName("No entity found → default action runs directly")
  void noEntityFoundRunsDefaultAction() {
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(null);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(defaultResponse, result);
    assertEquals(true, defaultActionCalled.get());
    servletSupportStatic.verify(() -> NeoServletSupport.lookupHandler(anyString()), never());
  }

  // ── dispatchWithHooks: blank qualifier → default action ──

  @Test
  @DisplayName("Entity has blank qualifier → default action runs directly")
  void blankQualifierRunsDefaultAction() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("   ");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(defaultResponse, result);
    assertEquals(true, defaultActionCalled.get());
    servletSupportStatic.verify(() -> NeoServletSupport.lookupHandler(anyString()), never());
  }

  @Test
  @DisplayName("Entity has null qualifier → default action runs directly")
  void nullQualifierRunsDefaultAction() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn(null);
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(defaultResponse, result);
    assertEquals(true, defaultActionCalled.get());
    servletSupportStatic.verify(() -> NeoServletSupport.lookupHandler(anyString()), never());
  }

  // ── dispatchWithHooks: null handler → default action ──

  @Test
  @DisplayName("Handler lookup returns null → default action runs directly")
  void nullHandlerRunsDefaultAction() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(null);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(defaultResponse, result);
    assertEquals(true, defaultActionCalled.get());
  }

  // ── Hook chain: handle() returns response → default skipped, afterHandle enriches ──

  @Test
  @DisplayName("handle() returns response → default skipped, afterHandle enriches result")
  void handleReturnsResponseSkipsDefault() throws Exception {
    NeoResponse hookResponse = NeoResponse.ok(new JSONObject().put("source", "hook"));
    NeoResponse afterResponse = NeoResponse.ok(new JSONObject().put("source", "enriched"));

    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenReturn(hookResponse);
    when(handler.afterHandle(Mockito.any(NeoContext.class))).thenReturn(afterResponse);

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(afterResponse, result);
    assertEquals(false, defaultActionCalled.get());
    verify(handler).handle(Mockito.any(NeoContext.class));
    verify(handler).afterHandle(Mockito.any(NeoContext.class));
    auditTokenRefreshStatic.verify(() -> NeoAuditTokenRefresh.refreshInResponse(
        Mockito.any(NeoContext.class), eq(afterResponse)));
  }

  // ── Hook chain: handle() returns null → default runs, afterHandle enriches ──

  @Test
  @DisplayName("handle() returns null → default runs, afterHandle enriches result")
  void handleReturnsNullRunsDefaultThenAfterHandle() throws Exception {
    NeoResponse afterResponse = NeoResponse.ok(new JSONObject().put("source", "enriched"));

    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenReturn(null);
    when(handler.afterHandle(Mockito.any(NeoContext.class))).thenReturn(afterResponse);

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(afterResponse, result);
    assertEquals(true, defaultActionCalled.get());
    verify(handler).handle(Mockito.any(NeoContext.class));
    verify(handler).afterHandle(Mockito.any(NeoContext.class));
    auditTokenRefreshStatic.verify(() -> NeoAuditTokenRefresh.refreshInResponse(
        Mockito.any(NeoContext.class), eq(afterResponse)));
  }

  // ── afterHandle returns null → original result preserved ──

  @Test
  @DisplayName("handle() returns response, afterHandle returns null → pre-hook result preserved")
  void afterHandleNullPreservesPreHookResult() throws Exception {
    NeoResponse hookResponse = NeoResponse.ok(new JSONObject().put("source", "hook"));

    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenReturn(hookResponse);
    when(handler.afterHandle(Mockito.any(NeoContext.class))).thenReturn(null);

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(hookResponse, result);
    assertEquals(false, defaultActionCalled.get());
    auditTokenRefreshStatic.verify(() -> NeoAuditTokenRefresh.refreshInResponse(
        Mockito.any(NeoContext.class), eq(hookResponse)));
  }

  @Test
  @DisplayName("handle() returns null, afterHandle returns null → default result preserved")
  void afterHandleNullPreservesDefaultResult() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenReturn(null);
    when(handler.afterHandle(Mockito.any(NeoContext.class))).thenReturn(null);

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(defaultResponse, result);
    assertEquals(true, defaultActionCalled.get());
  }

  // ── Exception in handler → 500 error ──

  @Test
  @DisplayName("Exception in handler.handle() → returns 500 error response")
  void exceptionInHandlerReturns500() {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class)))
        .thenThrow(new RuntimeException("Simulated failure"));

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertNotNull(result);
    assertEquals(500, result.getHttpStatus());
    assertEquals(false, defaultActionCalled.get());
  }

  @Test
  @DisplayName("Exception in handler.afterHandle() → returns 500 error response")
  void exceptionInAfterHandleReturns500() {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenReturn(null);
    when(handler.afterHandle(Mockito.any(NeoContext.class)))
        .thenThrow(new RuntimeException("afterHandle failure"));

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertNotNull(result);
    assertEquals(500, result.getHttpStatus());
  }

  // ── Overload with ActionDispatchParams passes recordId/requestBody to context ──

  @Test
  @DisplayName("ActionDispatchParams overload passes recordId and requestBody to hook context")
  void actionDispatchParamsPassedToContext() throws Exception {
    JSONObject requestBody = new JSONObject().put("action", "complete");
    NeoSubEndpointDispatcher.ActionDispatchParams actionParams =
        new NeoSubEndpointDispatcher.ActionDispatchParams("rec-123", requestBody);

    NeoContext[] capturedCtx = new NeoContext[1];
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenAnswer(invocation -> {
      capturedCtx[0] = invocation.getArgument(0);
      return null;
    });
    when(handler.afterHandle(Mockito.any(NeoContext.class))).thenReturn(null);

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("actionQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("actionQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.ACTION, "docAction", "POST",
        actionParams, defaultAction);

    assertSame(defaultResponse, result);
    assertNotNull(capturedCtx[0]);
    assertEquals("rec-123", capturedCtx[0].getRecordId());
    assertSame(requestBody, capturedCtx[0].getRequestBody());
    assertEquals("docAction", capturedCtx[0].getFieldName());
    assertEquals(NeoEndpointType.ACTION, capturedCtx[0].getEndpointType());
    assertEquals("POST", capturedCtx[0].getHttpMethod());
    assertEquals("TestSpec", capturedCtx[0].getSpecName());
    assertEquals("Header", capturedCtx[0].getEntityName());
  }

  // ── Simple overload (no ActionDispatchParams) delegates correctly ──

  @Test
  @DisplayName("Simple overload without ActionDispatchParams delegates to full overload")
  void simpleOverloadDelegates() throws Exception {
    NeoResponse hookResponse = NeoResponse.ok(new JSONObject().put("source", "hook"));

    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class))).thenReturn(hookResponse);
    when(handler.afterHandle(Mockito.any(NeoContext.class))).thenReturn(null);

    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("myQualifier")).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.SELECTOR, "warehouse", "GET", defaultAction);

    assertSame(hookResponse, result);
    assertEquals(false, defaultActionCalled.get());
  }
  // ── ETP-5558: the record-ownership guard on the REST action path ──

  /** A customized entity whose handler would answer, so a skipped guard shows up as its answer. */
  private NeoHandler customizedHeader(SFEntity entity) throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.handle(Mockito.any(NeoContext.class)))
        .thenReturn(NeoResponse.ok(new JSONObject().put("source", "hook")));
    when(entity.getJavaQualifier()).thenReturn("guardedQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    servletSupportStatic.when(() -> NeoServletSupport.lookupHandler("guardedQualifier"))
        .thenReturn(handler);
    return handler;
  }

  private static NeoSubEndpointDispatcher.ActionDispatchParams paramsFor(String recordId)
      throws Exception {
    return new NeoSubEndpointDispatcher.ActionDispatchParams(recordId, new JSONObject());
  }

  @Test
  @DisplayName("ETP-5558: an action on another tenant's record is a 404 before anything runs")
  void foreignRecordIsRefusedBeforeTheCustomization() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    NeoHandler handler = customizedHeader(entity);
    NeoResponse notFound = NeoResponse.error(404, "Record not found");

    try (MockedStatic<NeoActionRecordGuard> guard = Mockito.mockStatic(NeoActionRecordGuard.class)) {
      guard.when(() -> NeoActionRecordGuard.refusalFor(entity, "foreign-1")).thenReturn(notFound);

      NeoResponse result = dispatcher.dispatchWithHooks(spec, "Header", NeoEndpointType.ACTION,
          "eTPRRemovePayment", "POST", paramsFor("foreign-1"), defaultAction);

      assertSame(notFound, result);
      verify(handler, never()).handle(Mockito.any(NeoContext.class));
      assertEquals(false, defaultActionCalled.get(), "the AD button must not run either");
    }
  }

  @Test
  @DisplayName("ETP-5558: an action on an own record runs as before")
  void ownRecordRunsAsBefore() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    customizedHeader(entity);

    try (MockedStatic<NeoActionRecordGuard> guard = Mockito.mockStatic(NeoActionRecordGuard.class)) {
      guard.when(() -> NeoActionRecordGuard.refusalFor(entity, "own-1")).thenReturn(null);

      NeoResponse result = dispatcher.dispatchWithHooks(spec, "Header", NeoEndpointType.ACTION,
          "eTPRRemovePayment", "POST", paramsFor("own-1"), defaultAction);

      assertEquals("hook", result.getBody().getString("source"));
      guard.verify(() -> NeoActionRecordGuard.refusalFor(entity, "own-1"));
    }
  }

  @Test
  @DisplayName("ETP-5558: the guard is not consulted outside ACTION or without action params")
  void guardOnlyOnActionsWithParams() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    customizedHeader(entity);

    try (MockedStatic<NeoActionRecordGuard> guard = Mockito.mockStatic(NeoActionRecordGuard.class)) {
      guard.when(() -> NeoActionRecordGuard.refusalFor(Mockito.any(), Mockito.any()))
          .thenReturn(NeoResponse.error(404, "Record not found"));

      NeoResponse selector = dispatcher.dispatchWithHooks(spec, "Header",
          NeoEndpointType.SELECTOR, "warehouse", "GET", paramsFor("foreign-1"), defaultAction);
      NeoResponse noParams = dispatcher.dispatchWithHooks(spec, "Header",
          NeoEndpointType.ACTION, "docAction", "POST", defaultAction);

      assertEquals("hook", selector.getBody().getString("source"));
      assertEquals("hook", noParams.getBody().getString("source"));
      guard.verify(() -> NeoActionRecordGuard.refusalFor(Mockito.any(), Mockito.any()), never());
    }
  }
}
