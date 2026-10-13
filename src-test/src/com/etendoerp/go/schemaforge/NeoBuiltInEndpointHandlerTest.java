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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;
import com.etendoerp.go.schemaforge.AmortizationPlanService;
import com.etendoerp.go.schemaforge.NeoRequestBodyParser;

/**
 * Unit tests for attachment and built-in endpoint routing in
 * {@link NeoBuiltInEndpointHandler}.
 */
public class NeoBuiltInEndpointHandlerTest {

  private NeoServlet servlet;
  private NeoBuiltInEndpointHandler handler;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    NeoDiscoveryHandler discoveryHandler = mock(NeoDiscoveryHandler.class);
    handler = new NeoBuiltInEndpointHandler(servlet, discoveryHandler);
  }

  private static Object invokePrivate(NeoBuiltInEndpointHandler target, String methodName,
      Class<?>[] paramTypes, Object... args) throws Exception {
    Method method = NeoBuiltInEndpointHandler.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(target, args);
  }

  private String[] parseSegments(String pathInfo) throws Exception {
    return (String[]) invokePrivate(handler, "parseAttachmentsSegments",
        new Class<?>[] {String.class}, pathInfo);
  }

  private String readDescription(String body, HttpServletResponse response) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
    return (String) invokePrivate(handler, "readDescriptionFromBody",
        new Class<?>[] {HttpServletRequest.class, HttpServletResponse.class}, request, response);
  }

  private static ServletInputStream toServletInputStream(String content) {
    ByteArrayInputStream stream = new ByteArrayInputStream(
        content.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
      @Override
      public int read() {
        return stream.read();
      }

      @Override
      public boolean isFinished() {
        return stream.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        // Synchronous test stream.
      }
    };
  }

  /**
   * Verifies that the "attachments" prefix is removed from path segments.
   */
  @Test
  public void parseAttachmentsSegmentsDropsAttachmentsPrefix() throws Exception {
    String[] segments = parseSegments("/attachments/c_order/123/zip");
    assertArrayEquals(new String[] {"c_order", "123", "zip"}, segments);
  }

  /**
   * Verifies that null path info returns an empty segments array.
   */
  @Test
  public void parseAttachmentsSegmentsReturnsEmptyForNullPath() throws Exception {
    String[] segments = parseSegments(null);
    assertEquals(0, segments.length);
  }

  /**
   * Verifies that a bare attachments path does not produce usable segments.
   */
  @Test
  public void parseAttachmentsSegmentsReturnsEmptyForAttachmentsOnly() throws Exception {
    String[] segments = parseSegments("/attachments");
    assertEquals(0, segments.length);
  }

  /**
   * Verifies extraction of the description field for PATCH payloads.
   */
  @Test
  public void readDescriptionFromBodyReturnsValueWhenPresent() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    String description = readDescription("{\"description\":\"Updated from UI\"}", response);

    assertEquals("Updated from UI", description);
    verify(servlet, never()).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        contains("Invalid JSON body"));
  }

  /**
   * Verifies null result when description key is missing.
   */
  @Test
  public void readDescriptionFromBodyReturnsNullWhenFieldIsMissing() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    String description = readDescription("{\"other\":\"value\"}", response);

    assertNull(description);
    verify(servlet, never()).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        contains("Invalid JSON body"));
  }

  /**
   * Verifies null result for blank request body.
   */
  @Test
  public void readDescriptionFromBodyReturnsNullWhenBodyIsBlank() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    String description = readDescription("   ", response);
    assertNull(description);
  }

  /**
   * Verifies validation error for malformed JSON payloads.
   */
  @Test
  public void readDescriptionFromBodySendsBadRequestForInvalidJson() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    String description = readDescription("{bad-json", response);

    assertNull(description);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        contains("Invalid JSON body"));
  }

  /**
   * Verifies unknown specs are not handled by the built-in handler.
   */
  @Test
  public void handleReturnsFalseForUnknownSpec() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("unknown", null, null),
        "GET", request, response);

    assertFalse(handled);
  }

  /**
   * Verifies method check for discovery endpoint.
   */
  @Test
  public void handleDiscoveryRejectsNonGetMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo(null, null, null),
        "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Discovery endpoint only supports GET"));
  }

  /**
   * Verifies successful GET handling for the session built-in endpoint.
   */
  @Test
  public void handleSessionGetWritesResolvedSession() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse sessionPayload = NeoResponse.ok(new JSONObject());

    try (MockedStatic<NeoSessionService> sessionMock = Mockito.mockStatic(NeoSessionService.class)) {
      sessionMock.when(NeoSessionService::resolveSession).thenReturn(sessionPayload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("session", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, sessionPayload);
    }
  }

  /**
   * Verifies method restriction for the session endpoint.
   */
  @Test
  public void handleSessionRejectsNonGetMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("session", null, null),
        "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Session endpoint only supports GET"));
  }

  /**
   * Verifies successful routing for contract-driven email commands.
   */
  @Test
  public void handleEmailContractsPostWritesServiceResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse serviceResponse = NeoResponse.ok(new JSONObject());
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    NeoDiscoveryHandler discoveryHandler = mock(NeoDiscoveryHandler.class);
    handler = new NeoBuiltInEndpointHandler(servlet, discoveryHandler, emailService);

    when(request.getPathInfo()).thenReturn("/email-contracts/reset-password/send");
    when(request.getInputStream()).thenReturn(toServletInputStream("{\"recordId\":\"1\"}"));
    when(emailService.send(eq("reset-password"), any(JSONObject.class))).thenReturn(serviceResponse);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("email-contracts", "reset-password",
        "send"), "POST", request, response);

    assertTrue(handled);
    verify(emailService).send(eq("reset-password"), any(JSONObject.class));
    verify(servlet).writeResponse(response, serviceResponse);
  }

  /**
   * Verifies method restriction for email contract endpoint.
   */
  @Test
  public void handleEmailContractsRejectsNonPostMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("email-contracts", "reset-password",
        "send"), "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Email contract endpoint only supports POST"));
  }

  /**
   * Verifies only /email-contracts/{contract}/send is accepted.
   */
  @Test
  public void handleEmailContractsRejectsUnknownShape() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("email-contracts", "reset-password",
        "preview"), "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_NOT_FOUND),
        eq("Unknown email contract endpoint"));
  }

  /**
   * Verifies DAL rollback when email contract handling catches an unexpected error.
   */
  @Test
  public void handleEmailContractsRollsBackOnUnexpectedError() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    NeoDiscoveryHandler discoveryHandler = mock(NeoDiscoveryHandler.class);
    OBDal dal = mock(OBDal.class);
    handler = new NeoBuiltInEndpointHandler(servlet, discoveryHandler, emailService);

    when(request.getPathInfo()).thenReturn("/email-contracts/reset-password/send");
    when(request.getInputStream()).thenReturn(toServletInputStream("{\"recordId\":\"1\"}"));
    when(emailService.send(eq("reset-password"), any(JSONObject.class)))
        .thenThrow(new RuntimeException("boom"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("email-contracts",
          "reset-password", "send"), "POST", request, response);

      assertTrue(handled);
      verify(dal).rollbackAndClose();
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
          eq("Email contract request failed"));
    }
  }

  /**
   * Verifies successful GET handling for the filters endpoint.
   */
  @Test
  public void handleFiltersGetWritesPresetsResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse presets = NeoResponse.ok(new JSONObject());

    try (MockedStatic<NeoFiltersService> filtersMock = Mockito.mockStatic(NeoFiltersService.class)) {
      filtersMock.when(() -> NeoFiltersService.getWindowPresets("sales-order")).thenReturn(presets);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("filters", "sales-order", null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, presets);
    }
  }

  /**
   * Verifies that filters endpoint requires a window name segment.
   */
  @Test
  public void handleFiltersRequiresWindowName() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("filters", null, null),
        "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        contains("Window name required"));
  }

  /**
   * Verifies PUT mutation flow for filter presets.
   */
  @Test
  public void handleFiltersPutSavesPresetAndFlushes() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<NeoFiltersService> filtersMock = Mockito.mockStatic(NeoFiltersService.class);
        MockedStatic<NeoRequestBodyParser> bodyParserMock = Mockito.mockStatic(NeoRequestBodyParser.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(request)).thenReturn("{\"a\":1}");
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("filters", "sales-order", "my-preset"),
          "PUT", request, response);

      assertTrue(handled);
      filtersMock.verify(
          () -> NeoFiltersService.savePreset("sales-order", "my-preset", "{\"a\":1}"));
      verify(dal).flush();
      verify(servlet).writeResponse(eq(response), isNull());
    }
  }

  /**
   * Verifies DELETE mutation flow for filter presets.
   */
  @Test
  public void handleFiltersDeleteRemovesPresetAndFlushes() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<NeoFiltersService> filtersMock = Mockito.mockStatic(NeoFiltersService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("filters", "sales-order", "my-preset"),
          "DELETE", request, response);

      assertTrue(handled);
      filtersMock.verify(() -> NeoFiltersService.deletePreset("sales-order", "my-preset"));
      verify(dal).flush();
      verify(servlet).writeResponse(eq(response), isNull());
    }
  }

  /**
   * Verifies method restriction for filter preset mutations.
   */
  @Test
  public void handleFiltersRejectsUnsupportedMutationMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("filters", "sales-order", "my-preset"),
        "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Filters endpoint only supports GET, PUT and DELETE"));
  }

  /**
   * Verifies successful GET handling for the fiscal models catalog endpoint, with a role that
   * holds the Tax Report window grant (ETP-5546: "Modelos Fiscales" access proxy).
   */
  @Test
  public void handleFiscalModelsCatalogGetWritesActiveModelsResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject());

    try (MockedStatic<NeoFiscalModelsCatalogService> catalogMock =
             Mockito.mockStatic(NeoFiscalModelsCatalogService.class);
         MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
          NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID, "GET")).thenReturn(true);
      catalogMock.when(NeoFiscalModelsCatalogService::getActiveModels).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("fiscal-models-catalog", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * ETP-5546 — a role without the Tax Report window grant gets 403 from the fiscal models
   * catalog GET, instead of reaching the service.
   */
  @Test
  public void handleFiscalModelsCatalogGetDeniedWithoutWindowAccess() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    try (MockedStatic<NeoFiscalModelsCatalogService> catalogMock =
             Mockito.mockStatic(NeoFiscalModelsCatalogService.class);
         MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
          NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID, "GET")).thenReturn(false);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("fiscal-models-catalog", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_FORBIDDEN), eq("Access denied"));
      catalogMock.verifyNoInteractions();
    }
  }

  /**
   * Verifies PUT saves the active-models JSON body and responds 204 (via a null NeoResponse),
   * with a role that holds the Tax Report window grant.
   */
  @Test
  public void handleFiscalModelsCatalogPutSavesAndFlushes() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<NeoFiscalModelsCatalogService> catalogMock =
             Mockito.mockStatic(NeoFiscalModelsCatalogService.class);
         MockedStatic<NeoRequestBodyParser> bodyParserMock =
             Mockito.mockStatic(NeoRequestBodyParser.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {

      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
          NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID, "PUT")).thenReturn(true);
      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(request))
          .thenReturn("{\"303\":true,\"349\":false}");
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("fiscal-models-catalog", null, null),
          "PUT", request, response);

      assertTrue(handled);
      catalogMock.verify(() -> NeoFiscalModelsCatalogService.saveActiveModels("{\"303\":true,\"349\":false}"));
      verify(dal).flush();
      verify(servlet).writeResponse(eq(response), isNull());
    }
  }

  /**
   * ETP-5546 — a role without the Tax Report window grant gets 403 from the fiscal models
   * catalog PUT, instead of reaching the service (write tier, not just GET).
   */
  @Test
  public void handleFiscalModelsCatalogPutDeniedWithoutWindowAccess() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    try (MockedStatic<NeoFiscalModelsCatalogService> catalogMock =
             Mockito.mockStatic(NeoFiscalModelsCatalogService.class);
         MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
          NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID, "PUT")).thenReturn(false);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("fiscal-models-catalog", null, null),
          "PUT", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_FORBIDDEN), eq("Access denied"));
      catalogMock.verifyNoInteractions();
    }
  }

  /**
   * Verifies PUT with an invalid JSON body returns 400, matching the service's
   * {@link IllegalArgumentException} contract.
   */
  @Test
  public void handleFiscalModelsCatalogPutInvalidJsonReturnsBadRequest() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    try (MockedStatic<NeoFiscalModelsCatalogService> catalogMock =
             Mockito.mockStatic(NeoFiscalModelsCatalogService.class);
         MockedStatic<NeoRequestBodyParser> bodyParserMock =
             Mockito.mockStatic(NeoRequestBodyParser.class);
         MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {

      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
          NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID, "PUT")).thenReturn(true);
      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(request)).thenReturn("not-json");
      catalogMock.when(() -> NeoFiscalModelsCatalogService.saveActiveModels("not-json"))
          .thenThrow(new IllegalArgumentException("Invalid JSON body"));

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("fiscal-models-catalog", null, null),
          "PUT", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          eq("Invalid JSON body"));
    }
  }

  /**
   * Verifies method restriction for the fiscal models catalog endpoint.
   */
  @Test
  public void handleFiscalModelsCatalogRejectsUnsupportedMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
          NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID, "DELETE")).thenReturn(true);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("fiscal-models-catalog", null, null),
          "DELETE", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
          eq("Fiscal models catalog endpoint only supports GET and PUT"));
    }
  }

  /**
   * Verifies successful GET handling for the certificate endpoint.
   */
  @Test
  public void handleCertificateGetWritesResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject());

    try (MockedStatic<NeoCertificateHelper> certificateMock = Mockito.mockStatic(
        NeoCertificateHelper.class)) {
      certificateMock.when(() -> NeoCertificateHelper.handleCertificateGet(request)).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("certificate", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * Verifies successful POST handling for certificate upload.
   */
  @Test
  public void handleCertificatePostWritesResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.created(new JSONObject());

    try (MockedStatic<NeoCertificateHelper> certificateMock = Mockito.mockStatic(
        NeoCertificateHelper.class)) {
      certificateMock.when(() -> NeoCertificateHelper.handleCertificateUpload(request)).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("certificate", null, null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * Verifies successful DELETE handling for certificate removal.
   */
  @Test
  public void handleCertificateDeleteWritesResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.noContent();

    try (MockedStatic<NeoCertificateHelper> certificateMock = Mockito.mockStatic(
        NeoCertificateHelper.class)) {
      certificateMock.when(() -> NeoCertificateHelper.handleCertificateDelete(request)).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("certificate", null, null),
          "DELETE", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * Verifies method restriction for certificate endpoint.
   */
  @Test
  public void handleCertificateRejectsUnsupportedMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("certificate", null, null),
        "PUT", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Certificate endpoint supports GET, POST and DELETE"));
  }

  /**
   * Verifies that image requests are delegated to the image helper.
   */
  @Test
  public void handleImageDelegatesToImageHelper() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    try (MockedStatic<NeoImageHelper> imageMock = Mockito.mockStatic(NeoImageHelper.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("image", "Product", null),
          "GET", request, response);

      assertTrue(handled);
      imageMock.verify(() -> NeoImageHelper.handleImageRequest("Product", "GET", request, response));
    }
  }

  /**
   * Verifies bad-request response for missing table and record segments.
   */
  @Test
  public void handleAttachmentsRequiresTableAndRecordSegments() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments");

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
        "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        contains("Attachments endpoint requires"));
  }

  /**
   * Verifies GET list flow for attachments record endpoint.
   */
  @Test
  public void handleAttachmentsRecordGetWritesListResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleList("c_order", "100")).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * Verifies POST upload flow for attachments record endpoint.
   */
  @Test
  public void handleAttachmentsRecordPostWritesUploadResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.created(new JSONObject());
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleUpload("c_order", "100", request, false))
          .thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * ETP-5205 — a Solo-Lectura role gets the authorizer's 403 and the upload never runs.
   */
  @Test
  public void handleAttachmentsRecordPostWritesAuthorizerDenialWithoutUploading() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse denied = NeoResponse.error(403, "Access denied to spec for current role");
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      authorizerMock.when(() -> NeoAttachmentAuthorizer.checkWriteOnTable("c_order"))
          .thenReturn(denied);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, denied);
      attachmentsMock.verifyNoInteractions();
    }
  }

  /**
   * ETP-5205 — delete, description and mark-main are all refused by the authorizer, which reads
   * the table from the attachment id, and none of the write helpers runs.
   */
  @Test
  public void handleAttachmentsFileWritesAreRefusedByTheAuthorizer() throws Exception {
    String[][] cases = {
        {"DELETE", "/attachments/file/ATT123"},
        {"PATCH", "/attachments/file/ATT123"},
        {"PATCH", "/attachments/file/ATT123/main"},
    };
    for (String[] c : cases) {
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      NeoResponse denied = NeoResponse.error(403, "Access denied to spec for current role");
      when(request.getPathInfo()).thenReturn(c[1]);

      try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
          NeoAttachmentsHelper.class);
           MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
               NeoAttachmentAuthorizer.class)) {
        authorizerMock.when(() -> NeoAttachmentAuthorizer.checkWriteOnAttachment("ATT123"))
            .thenReturn(denied);

        boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
            c[0], request, response);

        assertTrue(c[0] + " " + c[1], handled);
        verify(servlet).writeResponse(response, denied);
        attachmentsMock.verifyNoInteractions();
      }
    }
  }

  /**
   * ETP-5205 — reads are out of this slice's scope: a GET download never consults the authorizer.
   */
  @Test
  public void handleAttachmentsFileGetDoesNotConsultTheWriteAuthorizer() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");

    try (MockedStatic<NeoAttachmentsDownloader> downloaderMock = Mockito.mockStatic(
        NeoAttachmentsDownloader.class);
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      downloaderMock.verify(() -> NeoAttachmentsDownloader.handleDownload("ATT123", response));
      authorizerMock.verifyNoInteractions();
    }
  }

  /**
   * Verifies method restrictions for attachments record endpoint.
   */
  @Test
  public void handleAttachmentsRecordRejectsUnsupportedMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100");

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
        "PUT", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Attachments record endpoint supports GET (list) and POST (upload)"));
  }

  /**
   * Verifies ZIP download delegation for attachments record endpoint: with no {@code ids}
   * query parameter the route must still ask for the whole record, which it does by
   * passing {@code null} through to the subset-aware overload (ETP-5526). Asserting the
   * {@code null} explicitly is what keeps "no selection means everything" a pinned
   * contract rather than an accident of argument order.
   */
  @Test
  public void handleAttachmentsZipGetDelegatesToDownloadAll() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100/zip");
    when(request.getParameter("ids")).thenReturn((String) null);

    try (MockedStatic<NeoAttachmentsDownloader> downloaderMock = Mockito.mockStatic(
        NeoAttachmentsDownloader.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      downloaderMock.verify(() -> NeoAttachmentsDownloader.handleDownloadAll(
          "c_order", "100", (String) null, response));
    }
  }

  /**
   * ETP-5526 — the other half of the same seam: when the Attachments tab's selection bar
   * sends {@code ?ids=...}, the route hands the raw parameter to the helper verbatim. It
   * must not parse, split, trim or validate it here — the ownership check that makes the
   * subset safe lives in the helper, and a dispatcher that pre-digested the parameter
   * could quietly bypass it.
   */
  @Test
  public void handleAttachmentsZipGetPassesTheIdsParameterThrough() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100/zip");
    when(request.getParameter("ids")).thenReturn("ATT-A,ATT-B");

    try (MockedStatic<NeoAttachmentsDownloader> downloaderMock = Mockito.mockStatic(
        NeoAttachmentsDownloader.class);
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      downloaderMock.verify(() -> NeoAttachmentsDownloader.handleDownloadAll(
          "c_order", "100", "ATT-A,ATT-B", response));
    }
  }

  /**
   * Verifies method restriction for the attachments zip subresource.
   */
  @Test
  public void handleAttachmentsZipRejectsNonGetMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100/zip");

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
        "DELETE", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Attachments zip endpoint only supports GET"));
  }

  /**
   * ETP-5526 — GET /attachments/{table}/{record}/count writes handleCount's response and never
   * loads the list.
   */
  @Test
  public void handleAttachmentsCountGetDelegatesToHandleCount() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject().put("count", 2));
    when(request.getPathInfo()).thenReturn("/attachments/c_order/123/count");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleCount("c_order", "123")).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
      attachmentsMock.verify(() -> NeoAttachmentsHelper.handleList(Mockito.anyString(), Mockito.anyString()),
          never());
    }
  }

  /**
   * ETP-5526 — the count subresource is read-only: any other verb is a 405 and no helper runs
   * (in particular a POST must not fall through to the upload).
   */
  @Test
  public void handleAttachmentsCountRejectsNonGetMethod() throws Exception {
    for (String method : new String[] { "POST", "DELETE", "PATCH" }) {
      HttpServletRequest request = mock(HttpServletRequest.class);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(request.getPathInfo()).thenReturn("/attachments/c_order/123/count");

      try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
          NeoAttachmentsHelper.class);
           MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
               NeoAttachmentAuthorizer.class)) {
        boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
            method, request, response);

        assertTrue(method, handled);
        verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
            eq("Attachments count endpoint only supports GET"));
        attachmentsMock.verifyNoInteractions();
      }
    }
  }

  /**
   * ETP-5526 — "count" is only the subresource as a THIRD segment: with two segments it is the
   * record id, so GET /attachments/c_order/count is still the list of record "count".
   */
  @Test
  public void handleAttachmentsTwoSegmentCountPathIsTheListOfRecordCount() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(request.getPathInfo()).thenReturn("/attachments/c_order/count");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleList("c_order", "count")).thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
      attachmentsMock.verify(() -> NeoAttachmentsHelper.handleCount(Mockito.anyString(), Mockito.anyString()),
          never());
    }
  }

  /**
   * Verifies method restrictions for attachments file endpoint.
   */
  @Test
  public void handleAttachmentsFileRejectsUnsupportedMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
        "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Attachments file endpoint supports GET, DELETE and PATCH"));
  }

  /**
   * Verifies GET file flow for attachments file endpoint.
   */
  @Test
  public void handleAttachmentsFileGetDelegatesToDownload() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");

    try (MockedStatic<NeoAttachmentsDownloader> downloaderMock = Mockito.mockStatic(
        NeoAttachmentsDownloader.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      downloaderMock.verify(() -> NeoAttachmentsDownloader.handleDownload("ATT123", response));
    }
  }

  /**
   * Verifies file endpoint validation for blank attachment identifier.
   */
  @Test
  public void handleAttachmentsFileRequiresAttachmentId() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/file/   ");

    boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
        "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
        contains("Attachments file endpoint requires"));
  }

  /**
   * Verifies DELETE file flow flushes DAL when deletion succeeds.
   */
  @Test
  public void handleAttachmentsFileDeleteFlushesWhenDeletionSucceeds() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBDal dal = mock(OBDal.class);
    NeoResponse deleteResponse = NeoResponse.noContent();
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {

      attachmentsMock.when(() -> NeoAttachmentsHelper.handleDelete("ATT123")).thenReturn(deleteResponse);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "DELETE", request, response);

      assertTrue(handled);
      verify(dal).flush();
      verify(servlet).writeResponse(response, deleteResponse);
    }
  }

  /**
   * Verifies DELETE file flow does not flush DAL for failing deletions.
   */
  @Test
  public void handleAttachmentsFileDeleteDoesNotFlushWhenDeletionFails() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBDal dal = mock(OBDal.class);
    NeoResponse deleteResponse = NeoResponse.error(500, "delete failed");
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {

      attachmentsMock.when(() -> NeoAttachmentsHelper.handleDelete("ATT123")).thenReturn(deleteResponse);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "DELETE", request, response);

      assertTrue(handled);
      verify(dal, never()).flush();
      verify(servlet).writeResponse(response, deleteResponse);
    }
  }

  /**
   * Verifies PATCH flow with a valid description body.
   */
  @Test
  public void handleAttachmentsFilePatchUpdatesDescription() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader("{\"description\":\"new\"}")));

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleUpdateDescription("ATT123", "new"))
          .thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "PATCH", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
    }
  }

  /**
   * Verifies PATCH behavior when body parsing fails and response is already committed.
   */
  @Test
  public void handleAttachmentsFilePatchStopsWhenInvalidJsonCommitsResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader("{bad-json")));
    when(response.isCommitted()).thenReturn(true);

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "PATCH", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          contains("Invalid JSON body"));
      verify(servlet, never()).writeResponse(eq(response), any());
      attachmentsMock.verifyNoInteractions();
    }
  }

  /**
   * Verifies PATCH branch when parsing fails but response is not committed yet.
   */
  @Test
  public void handleAttachmentsFilePatchContinuesWhenInvalidJsonNotCommitted() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(request.getPathInfo()).thenReturn("/attachments/file/ATT123");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader("{bad-json")));
    when(response.isCommitted()).thenReturn(false);

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class);
         // ETP-5205 — the write-tier authorizer runs first; a static mock answers null (allowed).
         MockedStatic<NeoAttachmentAuthorizer> authorizerMock = Mockito.mockStatic(
             NeoAttachmentAuthorizer.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleUpdateDescription("ATT123", null))
          .thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "PATCH", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          contains("Invalid JSON body"));
      verify(servlet).writeResponse(response, payload);
    }
  }

  // -------------------------------------------------------------------------
  // handleAmortizationEndpoint — generate-plan REST glue
  // -------------------------------------------------------------------------

  /**
   * Verifies that amortization/generate-plan is intercepted and returns true.
   * Regular amortization CRUD entities (header, lines) must NOT be intercepted.
   */
  @Test
  public void handleAmortizationGeneratePlanIsIntercepted() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse serviceResponse = NeoResponse.ok(new JSONObject());

    try (MockedStatic<NeoRequestBodyParser> bodyMock = Mockito.mockStatic(NeoRequestBodyParser.class);
        MockedStatic<AmortizationPlanService> serviceMock = Mockito.mockStatic(
            AmortizationPlanService.class)) {
      bodyMock.when(() -> NeoRequestBodyParser.readRequestBody(request))
          .thenReturn("{\"assetId\":\"ASSET-001\"}");
      bodyMock.when(() -> NeoRequestBodyParser.parseJsonObject("{\"assetId\":\"ASSET-001\"}"))
          .thenCallRealMethod();
      serviceMock.when(() -> AmortizationPlanService.generatePlan("ASSET-001"))
          .thenReturn(serviceResponse);

      boolean handled = handler.handle(
          new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
          "POST", request, response);

      assertTrue(handled);
    }
  }

  /**
   * Verifies that regular amortization CRUD entity names fall through to the
   * standard spec router (handler returns false).
   */
  @Test
  public void handleAmortizationHeaderFallsThrough() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(
        new NeoServlet.NeoPathInfo("amortization", "header", null),
        "GET", request, response);

    assertFalse(handled);
  }

  /**
   * Verifies that the generate-plan endpoint rejects non-POST methods with 405.
   */
  @Test
  public void handleAmortizationGeneratePlanRejectsNonPostMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(
        new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
        "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Amortization generate-plan endpoint only supports POST"));
  }

  /**
   * Verifies that a PUT on generate-plan is also rejected with 405.
   */
  @Test
  public void handleAmortizationGeneratePlanRejectsPutMethod() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = handler.handle(
        new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
        "PUT", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Amortization generate-plan endpoint only supports POST"));
  }

  /**
   * Happy path: valid POST with assetId delegates to the service and writes the
   * success response.
   */
  @Test
  public void handleAmortizationGeneratePlanHappyPathWritesServiceResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse serviceResponse = NeoResponse.ok(new JSONObject());
    when(request.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"assetId\":\"ASSET-42\"}")));

    try (MockedStatic<AmortizationPlanService> serviceMock = Mockito.mockStatic(
        AmortizationPlanService.class)) {
      serviceMock.when(() -> AmortizationPlanService.generatePlan("ASSET-42"))
          .thenReturn(serviceResponse);

      boolean handled = handler.handle(
          new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
          "POST", request, response);

      assertTrue(handled);
      serviceMock.verify(() -> AmortizationPlanService.generatePlan("ASSET-42"));
      verify(servlet).writeResponse(response, serviceResponse);
    }
  }

  /**
   * Error propagation: when the service returns an error NeoResponse, the handler
   * writes that exact response (does not swallow or replace it).
   */
  @Test
  public void handleAmortizationGeneratePlanPropagatesServiceErrorResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse errorResponse = NeoResponse.error(404, "Asset not found");
    when(request.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"assetId\":\"MISSING\"}")));

    try (MockedStatic<AmortizationPlanService> serviceMock = Mockito.mockStatic(
        AmortizationPlanService.class)) {
      serviceMock.when(() -> AmortizationPlanService.generatePlan("MISSING"))
          .thenReturn(errorResponse);

      boolean handled = handler.handle(
          new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, errorResponse);
      verify(servlet, never()).sendError(eq(response), any(Integer.class), any());
    }
  }

  /**
   * Conflict-error propagation: a 409 from the service is also written through.
   */
  @Test
  public void handleAmortizationGeneratePlanPropagatesConflictResponse() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse conflictResponse = NeoResponse.error(409, "Plan already exists");
    when(request.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"assetId\":\"DUP-ASSET\"}")));

    try (MockedStatic<AmortizationPlanService> serviceMock = Mockito.mockStatic(
        AmortizationPlanService.class)) {
      serviceMock.when(() -> AmortizationPlanService.generatePlan("DUP-ASSET"))
          .thenReturn(conflictResponse);

      handler.handle(
          new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
          "POST", request, response);

      verify(servlet).writeResponse(response, conflictResponse);
    }
  }

  /**
   * Missing assetId: body is valid JSON but contains no assetId key →
   * handler sends 400 Bad Request and does NOT invoke the service.
   */
  @Test
  public void handleAmortizationGeneratePlanReturnsBadRequestWhenAssetIdMissing() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"other\":\"value\"}")));

    try (MockedStatic<AmortizationPlanService> serviceMock = Mockito.mockStatic(
        AmortizationPlanService.class)) {

      boolean handled = handler.handle(
          new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          contains("assetId"));
      serviceMock.verifyNoInteractions();
    }
  }

  /**
   * Invalid JSON body: parse exception is caught and a 400 Bad Request is returned.
   * The service is never called.
   */
  @Test
  public void handleAmortizationGeneratePlanReturnsBadRequestForInvalidJson() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    try (MockedStatic<NeoRequestBodyParser> bodyMock = Mockito.mockStatic(NeoRequestBodyParser.class);
        MockedStatic<AmortizationPlanService> serviceMock = Mockito.mockStatic(
            AmortizationPlanService.class)) {
      bodyMock.when(() -> NeoRequestBodyParser.readRequestBody(request))
          .thenReturn("{not-valid-json");
      bodyMock.when(() -> NeoRequestBodyParser.parseJsonObject("{not-valid-json"))
          .thenCallRealMethod();

      boolean handled = handler.handle(
          new NeoServlet.NeoPathInfo("amortization", "generate-plan", null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          contains("Invalid JSON body"));
      serviceMock.verifyNoInteractions();
    }
  }
}
