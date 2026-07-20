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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Unit tests for {@link NeoRequestRouter}.
 *
 * <p>Covers: parseRequestPath (valid, null, exception), handleSpecRequest dispatch
 * (process/report/window), handleProcessSpecRequest (access denied, GET describe,
 * GET with null process, POST execute, unsupported methods), handleWindowSpecRequest
 * (access denied, spec describe on GET without entity, method not allowed without entity).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoRequestRouterTest {

  private NeoRequestRouter router;

  @Mock
  private NeoServlet servlet;
  @Mock
  private HttpServletRequest request;
  @Mock
  private HttpServletResponse response;
  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;

  private MockedStatic<NeoServletSupport> supportMock;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    setFinalField(servlet, "authenticator", mock(NeoAuthenticator.class));
    setFinalField(servlet, "processReportEndpoint", mock(NeoProcessReportEndpoint.class));
    setFinalField(servlet, "discoveryHandler", mock(NeoDiscoveryHandler.class));
    setFinalField(servlet, "subEndpointDispatcher", mock(NeoSubEndpointDispatcher.class));
    setFinalField(servlet, "crudHandler", mock(NeoCrudHandler.class));

    router = new NeoRequestRouter(servlet);

    supportMock = mockStatic(NeoServletSupport.class);
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() {
    if (supportMock != null) {
      supportMock.close();
    }
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  private static void setFinalField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set field " + fieldName, e);
    }
  }

  // ── parseRequestPath ─────────────────────────────────────────────────────

  /**
   * Verifies that a valid path is parsed and returned successfully.
   */
  @Test
  void testParseRequestPathValidPath() throws Exception {
    NeoPathInfo expected = new NeoPathInfo("mySpec", "myEntity", null);
    when(request.getPathInfo()).thenReturn("/mySpec/myEntity");
    supportMock.when(() -> NeoServletSupport.parsePath("/mySpec/myEntity")).thenReturn(expected);

    NeoPathInfo result = router.parseRequestPath(request, response);

    assertNotNull(result);
    assertEquals("mySpec", result.specName);
    assertEquals("myEntity", result.entityName);
  }

  /**
   * Verifies that when parsePath returns null, sendError is called with 400
   * and the method returns null.
   */
  @Test
  void testParseRequestPathNullPathSends400() throws Exception {
    when(request.getPathInfo()).thenReturn(null);
    supportMock.when(() -> NeoServletSupport.parsePath(null)).thenReturn(null);

    NeoPathInfo result = router.parseRequestPath(request, response);

    assertNull(result);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  /**
   * Verifies that an IllegalArgumentException from parsePath triggers a 400 error
   * with the exception message.
   */
  @Test
  void testParseRequestPathIllegalArgumentSends400() throws Exception {
    when(request.getPathInfo()).thenReturn("/bad/path");
    supportMock.when(() -> NeoServletSupport.parsePath("/bad/path"))
        .thenThrow(new IllegalArgumentException("Invalid path segment"));

    NeoPathInfo result = router.parseRequestPath(request, response);

    assertNull(result);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        eq("Invalid path segment"));
  }

  // ── handleSpecRequest ────────────────────────────────────────────────────

  /**
   * Verifies that a 404 is sent when the spec is not found.
   */
  @Test
  void testHandleSpecRequestSpecNotFound() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("unknown", null, null);
    supportMock.when(() -> NeoServletSupport.findSpec("unknown")).thenReturn(null);

    router.handleSpecRequest(pathInfo, "GET", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  /**
   * Verifies that a process spec (type "P") routes to handleProcessSpecRequest.
   */
  @Test
  void testHandleSpecRequestRoutesToProcess() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("myProcess", null, null);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getSpecType()).thenReturn("P");
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-id");
    when(spec.getProcess()).thenReturn(process);
    when(servlet.authenticator.hasProcessAccess("proc-id")).thenReturn(true);

    NeoResponse describeResult = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    supportMock.when(() -> NeoServletSupport.findSpec("myProcess")).thenReturn(spec);

    try (MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      processMock.when(() -> NeoProcessService.describeProcess(process)).thenReturn(describeResult);

      router.handleSpecRequest(pathInfo, "GET", request, response);

      verify(servlet).writeResponse(response, describeResult);
    }
  }

  /**
   * Verifies that a report spec (type "R") with NO NEO-native handler routes to
   * handleReportSpecRequest and is exposed as non-callable: the router writes the
   * canonical {@code not_configured_for_report_generation} body (HTTP 200) instead of
   * executing any Jasper/AD_Process report (ETP-4255).
   */
  @SuppressWarnings("unchecked")
  @Test
  void testHandleSpecRequestRoutesToReportNotConfigured() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("myReport", null, null);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getSpecType()).thenReturn("R");
    when(spec.getId()).thenReturn("spec-id");
    when(spec.getName()).thenReturn("myReport");

    supportMock.when(() -> NeoServletSupport.findSpec("myReport")).thenReturn(spec);

    // No SFEntity carries a Java_Qualifier -> resolveReportHandlerQualifier returns null
    // -> spec is non-callable, no handler dispatch, no Jasper fallback.
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any(Criterion.class))).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Collections.emptyList());

    router.handleSpecRequest(pathInfo, "GET", request, response);

    // The handler must NEVER be invoked for a non-callable report.
    verify(servlet, never()).handleWithHooks(anyString(), any(), any(), any());

    ArgumentCaptor<NeoResponse> captor = ArgumentCaptor.forClass(NeoResponse.class);
    verify(servlet).writeResponse(eq(response), captor.capture());
    NeoResponse written = captor.getValue();
    assertEquals(200, written.getHttpStatus());
    org.codehaus.jettison.json.JSONObject body = written.getBody();
    assertEquals("myReport", body.getString("name"));
    assertEquals("report", body.getString("type"));
    assertEquals(false, body.getBoolean("callable"));
    assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, body.getString("status"));
    assertTrue(body.getString("message").contains("not configured"));
  }

  /**
   * Verifies that a report spec backed by a NEO-native handler (an entity declaring a
   * {@code Java_Qualifier}) dispatches through {@code servlet.handleWithHooks} and writes
   * the handler's NeoResponse — never a Jasper/AD_Process result.
   */
  @SuppressWarnings("unchecked")
  @Test
  void testHandleSpecRequestRoutesToReportHandler() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("aging-receivable", null, null);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getSpecType()).thenReturn("R");
    when(spec.getId()).thenReturn("spec-id");
    when(spec.getName()).thenReturn("aging-receivable");

    supportMock.when(() -> NeoServletSupport.findSpec("aging-receivable")).thenReturn(spec);

    // One SFEntity exposes a NEO report handler qualifier -> callable.
    SFEntity entity = mock(SFEntity.class);
    when(entity.getJavaQualifier()).thenReturn("agingReportHandler");
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any(Criterion.class))).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(java.util.List.of(entity));

    when(servlet.extractQueryParams(request)).thenReturn(Collections.emptyMap());

    org.codehaus.jettison.json.JSONObject reportBody = new org.codehaus.jettison.json.JSONObject();
    reportBody.put("rows", new org.codehaus.jettison.json.JSONArray());
    NeoResponse handlerResult = NeoResponse.ok(reportBody);
    when(servlet.handleWithHooks(eq("agingReportHandler"), any(), eq(request), eq(response)))
        .thenReturn(handlerResult);

    try (MockedStatic<NeoRequestBodyParser> bodyParserMock = mockStatic(NeoRequestBodyParser.class);
         MockedStatic<NeoCsvExportService> csvMock = mockStatic(NeoCsvExportService.class)) {
      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(request)).thenReturn(null);
      bodyParserMock.when(() -> NeoRequestBodyParser.parseOptionalJsonObject(any()))
          .thenReturn(null);
      csvMock.when(() -> NeoCsvExportService.tryExport(any(), any(), eq(response)))
          .thenReturn(false);

      router.handleSpecRequest(pathInfo, "GET", request, response);

      verify(servlet).handleWithHooks(eq("agingReportHandler"), any(), eq(request), eq(response));
      verify(servlet).writeResponse(response, handlerResult);
    }
  }

  /**
   * Verifies that a window spec (type "W") routes to handleWindowSpecRequest.
   */
  @Test
  void testHandleSpecRequestRoutesToWindow() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("myWindow", "myEntity", null);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getSpecType()).thenReturn("W");
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("win-id");
    when(spec.getADWindow()).thenReturn(window);
    when(servlet.authenticator.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
    when(servlet.subEndpointDispatcher.handleWindowSubEndpoint(
        eq(spec), eq(pathInfo), eq("GET"), eq(request), eq(response))).thenReturn(false);

    supportMock.when(() -> NeoServletSupport.findSpec("myWindow")).thenReturn(spec);

    router.handleSpecRequest(pathInfo, "GET", request, response);

    verify(servlet.crudHandler).handleWindowEntityCrud(spec, pathInfo, "GET", request, response);
  }

  // ── handleProcessSpecRequest ─────────────────────────────────────────────

  /**
   * Verifies that access denied to the process returns 403.
   */
  @Test
  void testHandleProcessSpecAccessDenied() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-id");
    when(spec.getProcess()).thenReturn(process);
    when(servlet.authenticator.hasProcessAccess("proc-id")).thenReturn(false);

    router.handleProcessSpecRequest(spec, "GET", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_FORBIDDEN), anyString());
  }

  /**
   * Verifies that GET with null process returns 500.
   */
  @Test
  void testHandleProcessSpecGetNullProcess() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getProcess()).thenReturn(null);

    router.handleProcessSpecRequest(spec, "GET", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  /**
   * Verifies that GET with a valid process describes it.
   */
  @Test
  void testHandleProcessSpecGetDescribes() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-id");
    when(spec.getProcess()).thenReturn(process);
    when(servlet.authenticator.hasProcessAccess("proc-id")).thenReturn(true);

    NeoResponse describeResult = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());

    try (MockedStatic<NeoProcessService> processMock = mockStatic(NeoProcessService.class)) {
      processMock.when(() -> NeoProcessService.describeProcess(process)).thenReturn(describeResult);

      router.handleProcessSpecRequest(spec, "GET", request, response);

      verify(servlet).writeResponse(response, describeResult);
    }
  }

  /**
   * Verifies that POST delegates to processReportEndpoint.handleProcessSpec.
   */
  @Test
  void testHandleProcessSpecPostExecutes() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-id");
    when(spec.getProcess()).thenReturn(process);
    when(servlet.authenticator.hasProcessAccess("proc-id")).thenReturn(true);

    router.handleProcessSpecRequest(spec, "POST", request, response);

    verify(servlet.processReportEndpoint).handleProcessSpec(spec, request, response);
  }

  /**
   * Verifies that PUT returns 405.
   */
  @Test
  void testHandleProcessSpecPutReturns405() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-id");
    when(spec.getProcess()).thenReturn(process);
    when(servlet.authenticator.hasProcessAccess("proc-id")).thenReturn(true);

    router.handleProcessSpecRequest(spec, "PUT", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }

  /**
   * Verifies that DELETE returns 405.
   */
  @Test
  void testHandleProcessSpecDeleteReturns405() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn("proc-id");
    when(spec.getProcess()).thenReturn(process);
    when(servlet.authenticator.hasProcessAccess("proc-id")).thenReturn(true);

    router.handleProcessSpecRequest(spec, "DELETE", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }

  // ── handleWindowSpecRequest ──────────────────────────────────────────────

  /**
   * Verifies that access denied to the window returns 403.
   */
  @Test
  void testHandleWindowSpecAccessDenied() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("win-id");
    when(spec.getADWindow()).thenReturn(window);
    when(servlet.authenticator.hasWindowAccessForSpec(spec, "GET")).thenReturn(false);
    NeoPathInfo pathInfo = new NeoPathInfo("myWindow", "myEntity", null);

    router.handleWindowSpecRequest(spec, pathInfo, "GET", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_FORBIDDEN), anyString());
  }

  /**
   * ETP-4510 BUG-3: before this fix, {@code spec.getADWindow() == null} skipped the
   * access check entirely, for every role including one with no role assigned at all.
   * Verifies the router now always asks {@code hasWindowAccessForSpec} — even for a
   * windowless spec — and honors a denial with a 403, rather than silently allowing it.
   */
  @Test
  void testHandleWindowSpecWindowlessSpecStillChecksAccess() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(servlet.authenticator.hasWindowAccessForSpec(spec, "GET")).thenReturn(false);
    NeoPathInfo pathInfo = new NeoPathInfo("myWindow", "myEntity", null);

    router.handleWindowSpecRequest(spec, pathInfo, "GET", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_FORBIDDEN), anyString());
  }

  /**
   * Companion: a windowless spec for which hasWindowAccessForSpec grants access (e.g. no
   * combination data + an authenticated role) proceeds normally, past the access gate.
   */
  @Test
  void testHandleWindowSpecWindowlessSpecAllowedProceedsNormally() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(servlet.authenticator.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
    NeoPathInfo pathInfo = new NeoPathInfo("myWindow", null, null);

    router.handleWindowSpecRequest(spec, pathInfo, "GET", request, response);

    verify(servlet.discoveryHandler).handleSpecDescribe(response, spec);
  }

  /**
   * Verifies that GET without entityName triggers spec describe.
   */
  @Test
  void testHandleWindowSpecNoEntityGetDescribes() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("win-id");
    when(spec.getADWindow()).thenReturn(window);
    when(servlet.authenticator.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
    NeoPathInfo pathInfo = new NeoPathInfo("myWindow", null, null);

    router.handleWindowSpecRequest(spec, pathInfo, "GET", request, response);

    verify(servlet.discoveryHandler).handleSpecDescribe(response, spec);
  }

  /**
   * Verifies that non-GET without entityName returns 405.
   */
  @Test
  void testHandleWindowSpecNoEntityNonGetReturns405() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("win-id");
    when(spec.getADWindow()).thenReturn(window);
    when(servlet.authenticator.hasWindowAccessForSpec(spec, "POST")).thenReturn(true);
    NeoPathInfo pathInfo = new NeoPathInfo("myWindow", null, null);

    router.handleWindowSpecRequest(spec, pathInfo, "POST", request, response);

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }
}
