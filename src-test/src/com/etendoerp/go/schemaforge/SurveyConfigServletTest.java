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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link SurveyConfigServlet}.
 *
 * <p>Covers: doOptions 204, doGet JWT-auth guards (OBException / generic
 * Exception -&gt; 401), the happy-path response shape (global settings,
 * per-survey config grouped by survey key, and canned responses grouped by
 * survey/language with their score range), the empty-row cases,
 * internal-error handling (500), and (ETP-4352 GDPR remediation) doPost
 * {@code /response} — persisting a submitted survey response server-side.
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
  private NativeQuery globalQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery surveyTypesQuery;
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

    // createNativeQuery() calls happen in order: global settings, survey types, canned responses.
    when(session.createNativeQuery(anyString())).thenReturn(globalQuery, surveyTypesQuery, cannedQuery);

    when(globalQuery.list()).thenReturn(Collections.emptyList());
    when(surveyTypesQuery.list()).thenReturn(Collections.emptyList());
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

  /** Authenticates the request AND returns a real (mocked) {@link OBContext} carrying
   * client/organization/user, as {@code doPost}'s {@code handleSubmitResponse} needs those to
   * stamp the inserted {@code etgo_survey_response} row — unlike {@link #authenticated()}, which
   * returns {@code null} because {@code doGet} never reads the returned context. */
  private void authenticatedWithContext(String clientId, String orgId, String userId) throws Exception {
    OBContext ctx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    User user = mock(User.class);
    when(user.getId()).thenReturn(userId);
    when(ctx.getCurrentClient()).thenReturn(client);
    when(ctx.getCurrentOrganization()).thenReturn(org);
    when(ctx.getUser()).thenReturn(user);
    neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any())).thenReturn(ctx);
  }

  private static HttpServletRequest requestWithBody(String pathInfo, String body) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body == null ? "" : body)));
    return request;
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
    @DisplayName("returns all 3 global fields from the settings row")
    void returnsGlobalConfig() throws Exception {
      authenticated();
      when(globalQuery.list()).thenReturn(List.<Object[]>of(
          new Object[]{ 30, 21, 2 }
      ));

      servlet.doGet(request, response);

      verify(response).setStatus(HttpServletResponse.SC_OK);
      JSONObject result = new JSONObject(getResponseBody());
      assertEquals(30, result.getInt("globalCooldownDays"));
      assertEquals(21, result.getInt("dismissedCooldownDays"));
      assertEquals(2, result.getInt("maxPerMonth"));
    }

    @Test
    @DisplayName("omits global fields entirely when no settings row exists")
    void omitsConfigWhenNoRow() throws Exception {
      authenticated();
      when(globalQuery.list()).thenReturn(Collections.emptyList());

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      assertFalse(result.has("globalCooldownDays"));
      // perSurvey/canned are always present (possibly empty), global fields are not.
      assertTrue(result.has("perSurvey"));
      assertTrue(result.has("canned"));
    }

    @Test
    @DisplayName("groups survey-type rows by key, omitting fields the survey doesn't use")
    void groupsSurveyTypes() throws Exception {
      authenticated();
      when(surveyTypesQuery.list()).thenReturn(List.<Object[]>of(
          new Object[]{ "nps", 60, 14, null, null, 90, "Y" },
          new Object[]{ "csat_invoicing", null, null, 5, 30, 90, "Y" }
      ));

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      JSONObject perSurvey = result.getJSONObject("perSurvey");

      JSONObject nps = perSurvey.getJSONObject("nps");
      assertEquals(60, nps.getInt("minAccountAgeDays"));
      assertEquals(14, nps.getInt("inactivityGuardDays"));
      assertEquals(90, nps.getInt("responseCooldownDays"));
      assertFalse(nps.has("minDocuments"));
      assertFalse(nps.has("documentGap"));
      assertTrue(nps.getBoolean("enabled"));

      JSONObject invoicing = perSurvey.getJSONObject("csat_invoicing");
      assertEquals(5, invoicing.getInt("minDocuments"));
      assertEquals(30, invoicing.getInt("documentGap"));
      assertFalse(invoicing.has("minAccountAgeDays"));
      assertFalse(invoicing.has("inactivityGuardDays"));
      assertTrue(invoicing.getBoolean("enabled"));
    }

    @Test
    @DisplayName("reports enabled=false for an isactive='N' survey-type row instead of omitting it")
    void reportsDisabledSurveyType() throws Exception {
      authenticated();
      when(surveyTypesQuery.list()).thenReturn(List.<Object[]>of(
          new Object[]{ "csat_order", 60, 14, 5, 30, 90, "N" }
      ));

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      JSONObject order = result.getJSONObject("perSurvey").getJSONObject("csat_order");
      assertFalse(order.getBoolean("enabled"));
      // Tuning fields are still reported alongside the disable flag — the frontend must not
      // need them (a disabled survey never runs isEligible), but they aren't hidden either.
      assertEquals(5, order.getInt("minDocuments"));
    }

    @Test
    @DisplayName("groups canned responses by survey key and language, carrying the score range")
    void groupsCannedResponses() throws Exception {
      authenticated();
      when(cannedQuery.list()).thenReturn(List.<Object[]>of(
          new Object[]{ "csat_invoicing", "en_US", "🐢", "Too slow", 1, 3 },
          new Object[]{ "csat_invoicing", "en_US", "🤔", "Hard to use", 1, 2 },
          new Object[]{ "csat_invoicing", "es_ES", "🐢", "Es lento", 1, 3 },
          new Object[]{ "csat_order", "en_US", "🔍", "Couldn't find the product", 1, 3 }
      ));

      servlet.doGet(request, response);

      JSONObject result = new JSONObject(getResponseBody());
      JSONObject canned = result.getJSONObject("canned");

      JSONArray invoicingEn = canned.getJSONObject("csat_invoicing").getJSONArray("en_US");
      assertEquals(2, invoicingEn.length());
      assertEquals("Too slow", invoicingEn.getJSONObject(0).getString("text"));
      assertEquals(3, invoicingEn.getJSONObject(0).getInt("maxScore"));
      assertEquals("Hard to use", invoicingEn.getJSONObject(1).getString("text"));
      assertEquals(2, invoicingEn.getJSONObject(1).getInt("maxScore"));

      JSONArray invoicingEs = canned.getJSONObject("csat_invoicing").getJSONArray("es_ES");
      assertEquals(1, invoicingEs.length());
      assertEquals("Es lento", invoicingEs.getJSONObject(0).getString("text"));

      JSONArray orderEn = canned.getJSONObject("csat_order").getJSONArray("en_US");
      assertEquals(1, orderEn.length());
      assertEquals("Couldn't find the product", orderEn.getJSONObject(0).getString("text"));
      assertEquals(1, orderEn.getJSONObject(0).getInt("minScore"));
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
  // doPost /response - unknown sub-path (ETP-4352 GDPR remediation: persisting a
  // submitted survey response server-side instead of sending raw feedback to Mixpanel)
  // ===========================================================================

  @Nested
  @DisplayName("doPost - unknown endpoint")
  class DoPostUnknownEndpointTests {

    @Test
    @DisplayName("returns 404 for a sub-path other than /response")
    void unknownSubPathReturns404() throws Exception {
      HttpServletRequest req = requestWithBody("/something-else", "{}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
      assertTrue(getResponseBody().contains("Unknown endpoint"));
    }

    @Test
    @DisplayName("returns 404 when pathInfo is null")
    void nullPathInfoReturns404() throws Exception {
      HttpServletRequest req = requestWithBody(null, "{}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
      assertTrue(getResponseBody().contains("Unknown endpoint"));
    }
  }

  // ===========================================================================
  // doPost /response - authentication guards
  // ===========================================================================

  @Nested
  @DisplayName("doPost /response - auth guards")
  class DoPostAuthTests {

    @Test
    @DisplayName("returns 401 when JWT authentication throws OBException")
    void authFailureOBException() throws Exception {
      HttpServletRequest req = requestWithBody("/response", "{\"surveyKey\":\"nps\"}");
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any()))
          .thenThrow(new OBException("bad token"));

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      assertTrue(getResponseBody().contains("bad token"));
    }

    @Test
    @DisplayName("returns 401 when JWT authentication throws a generic Exception")
    void authFailureGenericException() throws Exception {
      HttpServletRequest req = requestWithBody("/response", "{\"surveyKey\":\"nps\"}");
      neoSupportMock.when(() -> NeoServletSupport.authenticateJwt(any()))
          .thenThrow(new RuntimeException("unexpected"));

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      assertTrue(getResponseBody().contains("Invalid or expired token"));
    }
  }

  // ===========================================================================
  // doPost /response - request validation
  // ===========================================================================

  @Nested
  @DisplayName("doPost /response - request validation")
  class DoPostValidationTests {

    @Test
    @DisplayName("returns 400 when surveyKey is missing")
    void missingSurveyKeyReturns400() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      HttpServletRequest req = requestWithBody("/response", "{\"score\":9}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(getResponseBody().contains("Missing required field: surveyKey"));
      verify(session, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("returns 400 when surveyKey is blank/whitespace-only")
    void blankSurveyKeyReturns400() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      HttpServletRequest req = requestWithBody("/response", "{\"surveyKey\":\"   \"}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(getResponseBody().contains("Missing required field: surveyKey"));
    }

    @Test
    @DisplayName("returns 400 on malformed JSON body")
    void malformedJsonReturns400() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      HttpServletRequest req = requestWithBody("/response", "not json");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(getResponseBody().contains("Invalid JSON body"));
      verify(session, never()).createNativeQuery(anyString());
    }
  }

  // ===========================================================================
  // doPost /response - success (persists the response, never the raw feedback to Mixpanel)
  // ===========================================================================

  @Nested
  @DisplayName("doPost /response - success")
  @SuppressWarnings({ "rawtypes", "unchecked" })
  class DoPostSuccessTests {

    private NativeQuery stubInsertQuery() {
      NativeQuery insertQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(insertQuery);
      return insertQuery;
    }

    private Map<String, Object> capturedParams(NativeQuery insertQuery) {
      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
      verify(insertQuery, org.mockito.Mockito.atLeastOnce())
          .setParameter(nameCaptor.capture(), valueCaptor.capture());
      Map<String, Object> params = new HashMap<>();
      List<String> names = nameCaptor.getAllValues();
      List<Object> values = valueCaptor.getAllValues();
      for (int i = 0; i < names.size(); i++) {
        params.put(names.get(i), values.get(i));
      }
      return params;
    }

    @Test
    @DisplayName("persists surveyKey, score, feedback and joined tags; returns 201 {status: ok}")
    void happyPathPersistsFullPayload() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      NativeQuery insertQuery = stubInsertQuery();
      String body = "{\"surveyKey\":\"nps\",\"score\":9,\"feedback\":\"Great tool!\",\"tags\":[\"fast\",\"easy\"]}";
      HttpServletRequest req = requestWithBody("/response", body);

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_CREATED);
      JSONObject result = new JSONObject(getResponseBody());
      assertEquals("ok", result.getString("status"));

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue(sql.contains(":score"));
      assertTrue(sql.contains(":feedback"));
      assertTrue(sql.contains(":tags"));
      assertTrue(sql.contains("etgo_survey_response"));

      Map<String, Object> params = capturedParams(insertQuery);
      assertEquals("CLIENT-1", params.get("clientId"));
      assertEquals("ORG-1", params.get("orgId"));
      assertEquals("USER-1", params.get("actorId"));
      assertEquals("nps", params.get("surveyKey"));
      assertEquals(9, params.get("score"));
      assertEquals("Great tool!", params.get("feedback"));
      assertEquals("fast,easy", params.get("tags"));

      verify(insertQuery).executeUpdate();
    }

    @Test
    @DisplayName("a surveyKey-only payload inlines NULL for score/feedback/tags (no bound parameters "
        + "for the absent fields) and still returns 201")
    void minimalPayloadInlinesNullLiterals() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      NativeQuery insertQuery = stubInsertQuery();
      HttpServletRequest req = requestWithBody("/response", "{\"surveyKey\":\"csat_order\"}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_CREATED);

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertFalse(sql.contains(":score"));
      assertFalse(sql.contains(":feedback"));
      assertFalse(sql.contains(":tags"));
      assertTrue(sql.contains("NULL"));

      Map<String, Object> params = capturedParams(insertQuery);
      assertFalse(params.containsKey("score"));
      assertFalse(params.containsKey("feedback"));
      assertFalse(params.containsKey("tags"));
      assertEquals("csat_order", params.get("surveyKey"));
    }

    @Test
    @DisplayName("accepts a trailing slash on /response/")
    void trailingSlashIsAccepted() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      stubInsertQuery();
      HttpServletRequest req = requestWithBody("/response/", "{\"surveyKey\":\"nps\"}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_CREATED);
    }

    @Test
    @DisplayName("does not bind a feedback parameter when feedback is blank/whitespace-only")
    void blankFeedbackIsTreatedAsAbsent() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      NativeQuery insertQuery = stubInsertQuery();
      HttpServletRequest req = requestWithBody("/response",
          "{\"surveyKey\":\"nps\",\"feedback\":\"   \"}");

      servlet.doPost(req, response);

      Map<String, Object> params = capturedParams(insertQuery);
      assertFalse(params.containsKey("feedback"));
    }
  }

  // ===========================================================================
  // doPost /response - internal error handling
  // ===========================================================================

  @Nested
  @DisplayName("doPost /response - internal errors")
  class DoPostErrorTests {

    @Test
    @DisplayName("returns 500 when the insert query throws")
    void internalErrorReturns500() throws Exception {
      authenticatedWithContext("CLIENT-1", "ORG-1", "USER-1");
      when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("db down"));
      HttpServletRequest req = requestWithBody("/response", "{\"surveyKey\":\"nps\"}");

      servlet.doPost(req, response);

      verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      assertTrue(getResponseBody().contains("An internal error occurred while saving the survey response."));
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
