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

/**
 * Unit tests for {@link NeoHookDispatcher}.
 * Verifies hook resolution, chain execution, and error handling.
 */
class NeoHookDispatcherTest {

  private NeoServlet servlet;
  private NeoHookDispatcher dispatcher;
  private MockedStatic<OBContext> obContextStatic;

  private SFSpec spec;
  private NeoResponse defaultResponse;
  private Supplier<NeoResponse> defaultAction;
  private AtomicBoolean defaultActionCalled;

  @BeforeEach
  void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    dispatcher = new NeoHookDispatcher(servlet);

    obContextStatic = Mockito.mockStatic(OBContext.class);
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
    verify(servlet, never()).lookupHandler(anyString());
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
    verify(servlet, never()).lookupHandler(anyString());
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
    verify(servlet, never()).lookupHandler(anyString());
  }

  // ── dispatchWithHooks: null handler → default action ──

  @Test
  @DisplayName("Handler lookup returns null → default action runs directly")
  void nullHandlerRunsDefaultAction() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("myQualifier");
    when(servlet.findEntity(eq("spec-id-1"), eq("Header"))).thenReturn(entity);
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(null);

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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(afterResponse, result);
    assertEquals(false, defaultActionCalled.get());
    verify(handler).handle(Mockito.any(NeoContext.class));
    verify(handler).afterHandle(Mockito.any(NeoContext.class));
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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(afterResponse, result);
    assertEquals(true, defaultActionCalled.get());
    verify(handler).handle(Mockito.any(NeoContext.class));
    verify(handler).afterHandle(Mockito.any(NeoContext.class));
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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.CRUD, null, "GET", defaultAction);

    assertSame(hookResponse, result);
    assertEquals(false, defaultActionCalled.get());
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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

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
    when(servlet.lookupHandler(eq("actionQualifier"))).thenReturn(handler);

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
    when(servlet.lookupHandler(eq("myQualifier"))).thenReturn(handler);

    NeoResponse result = dispatcher.dispatchWithHooks(
        spec, "Header", NeoEndpointType.SELECTOR, "warehouse", "GET", defaultAction);

    assertSame(hookResponse, result);
    assertEquals(false, defaultActionCalled.get());
  }
}
