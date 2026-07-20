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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
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
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link SurveyConfigServlet}.
 *
 * <p>Covers: doOptions 204, doGet JWT-auth guards (OBException / generic
 * Exception -&gt; 401), the happy-path response shape (numeric config fields
 * plus canned responses grouped by survey/language), the empty-config-row
 * case, and internal-error handling (500).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurveyConfigServletTest {

  private SurveyConfigServlet servlet;

  @Mock
  private HttpServletRequest request;
  @Mock
  private HttpServletResponse response;
  @Mock
  private OBDal obDal;
  @Mock
  private Session session;

  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery configQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery cannedQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoServletSupport> neoSupportMock;
  private MockedStatic<com.etendoerp.go.common.CorsUtils> corsMock;

  private StringWriter stringWriter;
  private PrintWriter printWriter;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    servlet = new SurveyConfigServlet();

    stringWriter = new StringWriter();
    printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);

    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    neoSupportMock = mockStatic(NeoServletSupport.class);
    corsMock = mockStatic(com.etendoerp.go.common.CorsUtils.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);

    // First createNativeQuery() call is the config query, second is the canned-response query.
    when(session.createNativeQuery(anyString())).thenReturn(configQuery, cannedQuery);

    when(configQuery.list()).thenReturn(Collections.emptyList());
    when(cannedQuery.list()).thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    corsMock.close();
    neoSupportMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  private String getResponseBody() {
    printWriter.flush();
    return stringWriter.toString();
  }

  private void authenticated() throws Exception {
    neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any())).thenReturn(null);
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
  // doGet - authentication guards
  // ===========================================================================

  @Nested
  @DisplayName("doGet - auth guards")
  class DoGetAuthTests {

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
    @DisplayName("returns 401 when JWT authentication throws a generic Exception")
    void authFailureGenericException() throws Exception {
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any()))
          .thenThrow(new RuntimeException("unexpected"));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      assertTrue(getResponseBody().contains("Invalid or expired token"));
    }
  }

  // ===========================================================================
  // doGet - happy path
  // ===========================================================================

  @Nested
  @DisplayName("doGet - success")
  class DoGetSuccessTests {

    @Test
    @DisplayName("returns all 8 numeric fields from the config row")
    void returnsNumericConfig() throws Exception {
      authenticated();
      when(configQuery.list()).thenReturn(List.<Object[]>of(
          new Object[]{ 30, 21, 2, 60, 14, 90, 5, 30 }
      ));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject result = new JSONObject(getResponseBody());
      assertEquals(30, result.getInt("globalCooldownDays"));
      assertEquals(21, result.getInt("dismissedCooldownDays"));
      assertEquals(2, result.getInt("maxPerMonth"));
      assertEquals(60, result.getInt("npsMinAgeDays"));
      assertEquals(14, result.getInt("npsInactivityDays"));
      assertEquals(90, result.getInt("responseCooldownDays"));
      assertEquals(5, result.getInt("csatMinDocs"));
      assertEquals(30, result.getInt("csatDocGap"));
    }

    @Test
    @DisplayName("omits numeric fields entirely when no config row exists")
    void omitsConfigWhenNoRow() throws Exception {
      authenticated();
      when(configQuery.list()).thenReturn(Collections.emptyList());

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      assertFalse(result.has("globalCooldownDays"));
      // canned is always present (possibly empty), config fields are not.
      assertTrue(result.has("canned"));
    }

    @Test
    @DisplayName("groups canned responses by survey key and language")
    void groupsCannedResponses() throws Exception {
      authenticated();
      when(cannedQuery.list()).thenReturn(List.<Object[]>of(
          new Object[]{ "csat_invoicing", "en_US", "🐢", "Too slow" },
          new Object[]{ "csat_invoicing", "en_US", "🤔", "Hard to use" },
          new Object[]{ "csat_invoicing", "es_ES", "🐢", "Es lento" },
          new Object[]{ "csat_order", "en_US", "🔍", "Couldn't find the product" }
      ));

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      JSONObject canned = result.getJSONObject("canned");

      JSONArray invoicingEn = canned.getJSONObject("csat_invoicing").getJSONArray("en_US");
      assertEquals(2, invoicingEn.length());
      assertEquals("Too slow", invoicingEn.getJSONObject(0).getString("text"));
      assertEquals("Hard to use", invoicingEn.getJSONObject(1).getString("text"));

      JSONArray invoicingEs = canned.getJSONObject("csat_invoicing").getJSONArray("es_ES");
      assertEquals(1, invoicingEs.length());
      assertEquals("Es lento", invoicingEs.getJSONObject(0).getString("text"));

      JSONArray orderEn = canned.getJSONObject("csat_order").getJSONArray("en_US");
      assertEquals(1, orderEn.length());
      assertEquals("Couldn't find the product", orderEn.getJSONObject(0).getString("text"));
    }

    @Test
    @DisplayName("returns an empty canned object when there are no canned-response rows")
    void emptyCannedWhenNoRows() throws Exception {
      authenticated();
      when(cannedQuery.list()).thenReturn(Collections.emptyList());

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      assertEquals(0, result.getJSONObject("canned").length());
    }

    @Test
    @DisplayName("sets JSON content type and UTF-8 charset on success")
    void setsJsonContentType() throws Exception {
      authenticated();

      servlet.doGet(request, response);

      verify(response).setContentType("application/json");
      verify(response).setCharacterEncoding("UTF-8");
    }
  }

  // ===========================================================================
  // doGet - internal error handling
  // ===========================================================================

  @Nested
  @DisplayName("doGet - internal errors")
  class DoGetErrorTests {

    @Test
    @DisplayName("returns 500 when the config query throws")
    void internalErrorReturns500() throws Exception {
      authenticated();
      when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("db down"));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      assertTrue(getResponseBody().contains("An internal error occurred"));
    }

    @Test
    @DisplayName("restores the OBContext admin mode even when an error occurs")
    void restoresContextOnError() throws Exception {
      authenticated();
      when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("db down"));

      servlet.doGet(request, response);

      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  // ===========================================================================
  // Sanity: JSON round-trip guard (fails loudly if the servlet ever emits
  // malformed JSON instead of silently passing an empty-body assertion)
  // ===========================================================================

  @Test
  @DisplayName("success response is always parseable JSON")
  void successResponseIsValidJson() throws Exception {
    authenticated();
    try {
      new JSONObject(runAndGetBody());
    } catch (JSONException e) {
      throw new AssertionError("Response body is not valid JSON: " + getResponseBody(), e);
    }
  }

  private String runAndGetBody() throws Exception {
    servlet.doGet(request, response);
    return getResponseBody();
  }
}
