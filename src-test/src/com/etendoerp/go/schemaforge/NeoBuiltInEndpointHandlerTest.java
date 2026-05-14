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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoImageHelper;

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
        NeoAttachmentsHelper.class)) {
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
        NeoAttachmentsHelper.class)) {
      attachmentsMock.when(() -> NeoAttachmentsHelper.handleUpload("c_order", "100", request))
          .thenReturn(payload);

      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "POST", request, response);

      assertTrue(handled);
      verify(servlet).writeResponse(response, payload);
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
   * Verifies ZIP download delegation for attachments record endpoint.
   */
  @Test
  public void handleAttachmentsZipGetDelegatesToDownloadAll() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/attachments/c_order/100/zip");

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      attachmentsMock.verify(() -> NeoAttachmentsHelper.handleDownloadAll("c_order", "100", response));
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

    try (MockedStatic<NeoAttachmentsHelper> attachmentsMock = Mockito.mockStatic(
        NeoAttachmentsHelper.class)) {
      boolean handled = handler.handle(new NeoServlet.NeoPathInfo("attachments", null, null),
          "GET", request, response);

      assertTrue(handled);
      attachmentsMock.verify(() -> NeoAttachmentsHelper.handleDownload("ATT123", response));
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
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

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
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

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
        NeoAttachmentsHelper.class)) {
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
        NeoAttachmentsHelper.class)) {
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
        NeoAttachmentsHelper.class)) {
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
}
