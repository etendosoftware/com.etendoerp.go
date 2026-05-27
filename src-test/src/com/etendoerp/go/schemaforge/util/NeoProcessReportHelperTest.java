/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoProcessReportHelper}.
 *
 * <p>Covers: resolveReportHandlerQualifier (no entities, blank qualifier, valid qualifier),
 * handleProcessSpec (null process, blank body, valid JSON body, exception),
 * writeResponse via handleProcessSpec (null response -> 204, response with body -> JSON).
 * handleReportSpec is intentionally skipped because it depends on ReportingUtils
 * which cannot be safely mocked due to static initializers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoProcessReportHelperTest {

  @Mock
  private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<NeoAccessHelper> accessHelperMock;
  private MockedStatic<NeoProcessService> processServiceMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    processServiceMock = mockStatic(NeoProcessService.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (processServiceMock != null) {
      processServiceMock.close();
    }
    if (accessHelperMock != null) {
      accessHelperMock.close();
    }
    if (obDalMock != null) {
      obDalMock.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private SFSpec mockSpec(String id, String name) {
    SFSpec spec = mock(SFSpec.class);
    doReturn(id).when(spec).getId();
    doReturn(name).when(spec).getName();
    return spec;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private OBCriteria<SFEntity> mockCriteriaReturning(List<SFEntity> results) {
    OBCriteria criteria = mock(OBCriteria.class);
    when(criteria.list()).thenReturn(results);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    return criteria;
  }

  private HttpServletRequest createRequestWithBody(String body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
    ServletInputStream sis = new ServletInputStream() {
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
    when(request.getInputStream()).thenReturn(sis);
    return request;
  }

  private HttpServletResponse createMockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    when(response.getWriter()).thenReturn(pw);
    return response;
  }

  // ---------------------------------------------------------------------------
  // resolveReportHandlerQualifier
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("resolveReportHandlerQualifier")
  class ResolveReportHandlerQualifier {

    @Test
    @DisplayName("returns null when no entities exist for spec")
    void noEntitiesReturnsNull() {
      SFSpec spec = mockSpec("spec-1", "TestSpec");
      mockCriteriaReturning(Collections.emptyList());

      String result = NeoProcessReportHelper.resolveReportHandlerQualifier(spec);

      assertNull(result);
    }

    @Test
    @DisplayName("returns null when entity has blank qualifier")
    void blankQualifierReturnsNull() {
      SFSpec spec = mockSpec("spec-2", "TestSpec");
      SFEntity entity = mock(SFEntity.class);
      when(entity.getJavaQualifier()).thenReturn("   ");
      mockCriteriaReturning(Collections.singletonList(entity));

      String result = NeoProcessReportHelper.resolveReportHandlerQualifier(spec);

      assertNull(result);
    }

    @Test
    @DisplayName("returns null when entity has null qualifier")
    void nullQualifierReturnsNull() {
      SFSpec spec = mockSpec("spec-3", "TestSpec");
      SFEntity entity = mock(SFEntity.class);
      when(entity.getJavaQualifier()).thenReturn(null);
      mockCriteriaReturning(Collections.singletonList(entity));

      String result = NeoProcessReportHelper.resolveReportHandlerQualifier(spec);

      assertNull(result);
    }

    @Test
    @DisplayName("returns first non-blank qualifier from entities")
    void returnsFirstNonBlankQualifier() {
      SFSpec spec = mockSpec("spec-4", "TestSpec");

      SFEntity entityBlank = mock(SFEntity.class);
      when(entityBlank.getJavaQualifier()).thenReturn("");

      SFEntity entityValid = mock(SFEntity.class);
      when(entityValid.getJavaQualifier()).thenReturn("customReportHandler");

      SFEntity entityOther = mock(SFEntity.class);
      when(entityOther.getJavaQualifier()).thenReturn("anotherHandler");

      mockCriteriaReturning(Arrays.asList(entityBlank, entityValid, entityOther));

      String result = NeoProcessReportHelper.resolveReportHandlerQualifier(spec);

      assertEquals("customReportHandler", result);
    }
  }

  // ---------------------------------------------------------------------------
  // handleProcessSpec
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("handleProcessSpec")
  class HandleProcessSpec {

    @Test
    @DisplayName("sends 500 when resolved process is null")
    void nullProcessSends500() throws Exception {
      SFSpec spec = mockSpec("spec-p1", "ProcessSpec");
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(null);

      HttpServletRequest request = createRequestWithBody("");
      HttpServletResponse response = createMockResponse();

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      verify(response).setContentType("application/json");
      processServiceMock.verify(
          () -> NeoProcessService.executeProcess(any(), any()), never());
    }

    @Test
    @DisplayName("executes with null params when body is blank")
    void blankBodyExecutesWithNullParams() throws Exception {
      SFSpec spec = mockSpec("spec-p2", "ProcessSpec");
      Process adProcess = mock(Process.class);
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);

      NeoResponse neoResponse = NeoResponse.noContent();
      processServiceMock.when(
          () -> NeoProcessService.executeProcess(eq(adProcess), eq(null))).thenReturn(neoResponse);

      HttpServletRequest request = createRequestWithBody("");
      HttpServletResponse response = createMockResponse();

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      processServiceMock.verify(
          () -> NeoProcessService.executeProcess(eq(adProcess), eq(null)));
    }

    @Test
    @DisplayName("passes parsed JSON body to executeProcess")
    void validJsonBodyPassedToExecuteProcess() throws Exception {
      SFSpec spec = mockSpec("spec-p3", "ProcessSpec");
      Process adProcess = mock(Process.class);
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);

      JSONObject resultBody = new JSONObject();
      resultBody.put("status", "ok");
      NeoResponse neoResponse = NeoResponse.ok(resultBody);
      processServiceMock.when(
          () -> NeoProcessService.executeProcess(eq(adProcess), any(JSONObject.class)))
          .thenReturn(neoResponse);

      String jsonBody = "{\"key\":\"value\",\"number\":42}";
      HttpServletRequest request = createRequestWithBody(jsonBody);
      HttpServletResponse response = createMockResponse();

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      processServiceMock.verify(
          () -> NeoProcessService.executeProcess(eq(adProcess), any(JSONObject.class)));
      verify(response).setStatus(200);
      verify(response).setContentType("application/json");
    }

    @Test
    @DisplayName("sends 500 when executeProcess throws exception")
    void exceptionDuringExecutionSends500() throws Exception {
      SFSpec spec = mockSpec("spec-p4", "ProcessSpec");
      Process adProcess = mock(Process.class);
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);

      processServiceMock.when(
          () -> NeoProcessService.executeProcess(any(), any()))
          .thenThrow(new RuntimeException("Process failed"));

      HttpServletRequest request = createRequestWithBody("{}");
      HttpServletResponse response = createMockResponse();

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      verify(response).setContentType("application/json");
    }
  }

  // ---------------------------------------------------------------------------
  // writeResponse (tested indirectly via handleProcessSpec)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("writeResponse via handleProcessSpec")
  class WriteResponse {

    @Test
    @DisplayName("null NeoResponse writes 204 No Content")
    void nullResponseWrites204() throws Exception {
      SFSpec spec = mockSpec("spec-w1", "ProcessSpec");
      Process adProcess = mock(Process.class);
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);

      processServiceMock.when(
          () -> NeoProcessService.executeProcess(eq(adProcess), any()))
          .thenReturn(null);

      HttpServletRequest request = createRequestWithBody("");
      HttpServletResponse response = createMockResponse();

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
      verify(response, never()).setContentType(anyString());
    }

    @Test
    @DisplayName("NeoResponse with body writes JSON and correct status")
    void responseWithBodyWritesJson() throws Exception {
      SFSpec spec = mockSpec("spec-w2", "ProcessSpec");
      Process adProcess = mock(Process.class);
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);

      JSONObject body = new JSONObject();
      body.put("result", "success");
      NeoResponse neoResponse = NeoResponse.ok(body);
      neoResponse.withHeader("X-Custom", "test-value");

      processServiceMock.when(
          () -> NeoProcessService.executeProcess(eq(adProcess), any()))
          .thenReturn(neoResponse);

      HttpServletRequest request = createRequestWithBody("{}");
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(response.getWriter()).thenReturn(pw);

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      verify(response).setStatus(200);
      verify(response).setContentType("application/json");
      verify(response).setCharacterEncoding(StandardCharsets.UTF_8.name());
      verify(response).setHeader("X-Custom", "test-value");

      pw.flush();
      String written = sw.toString();
      JSONObject writtenJson = new JSONObject(written);
      assertEquals("success", writtenJson.getString("result"));
    }

    @Test
    @DisplayName("NeoResponse with null body sets status but no content type")
    void responseWithNullBodySetsStatusOnly() throws Exception {
      SFSpec spec = mockSpec("spec-w3", "ProcessSpec");
      Process adProcess = mock(Process.class);
      accessHelperMock.when(() -> NeoAccessHelper.resolveProcess(spec)).thenReturn(adProcess);

      NeoResponse neoResponse = new NeoResponse(200, null);

      processServiceMock.when(
          () -> NeoProcessService.executeProcess(eq(adProcess), any()))
          .thenReturn(neoResponse);

      HttpServletRequest request = createRequestWithBody("{}");
      HttpServletResponse response = createMockResponse();

      NeoProcessReportHelper.handleProcessSpec(spec, request, response);

      verify(response).setStatus(200);
      verify(response, never()).setContentType(anyString());
    }
  }
}
