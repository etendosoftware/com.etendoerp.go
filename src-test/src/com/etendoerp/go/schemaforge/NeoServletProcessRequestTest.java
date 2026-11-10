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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Language;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;
import com.etendoerp.go.oauth2.PublicApiKeyAttribution;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.usageevents.PublicApiCall;
import com.etendoerp.go.usageevents.PublicApiCaller;
import com.etendoerp.go.usageevents.PublicApiUsageCounter;

/**
 * How {@link NeoServlet}'s request entry point maps a failure inside the routed handler to an HTTP
 * status. The collaborators the servlet builds in its field initializers are replaced with
 * {@code mockConstruction} so the routed call can be made to throw. ETP-6158: the response it
 * writes has its Classic fiscal messages reworded in the language captured after authentication.
 *
 * @covers com.etendoerp.go.schemaforge.NeoServlet
 * @covers com.etendoerp.go.schemaforge.NeoPublicApiUsage
 * @covers com.etendoerp.go.schemaforge.NeoErrorResponses
 * @covers com.etendoerp.go.schemaforge.ClassicMessageRemap
 */
class NeoServletProcessRequestTest {

  private static final String SECRET = "hunter2";

  private MockedStatic<OBContext> obContext;
  private MockedConstruction<NeoAuthenticator> authenticators;
  private MockedConstruction<NeoRequestRouter> routers;
  private MockedConstruction<NeoBuiltInEndpointHandler> builtIns;
  private MockedConstruction<NeoPseudoSpecDispatcher> pseudoSpecs;
  private final StringWriter body = new StringWriter();
  private HttpServletResponse response;
  private NeoServlet servlet;

  @BeforeEach
  void setUp() throws Exception {
    obContext = mockStatic(OBContext.class);
    authenticators = mockConstruction(NeoAuthenticator.class,
        (auth, ctx) -> when(auth.authenticateRequest(any(), any())).thenReturn(true));
    routers = mockConstruction(NeoRequestRouter.class,
        (router, ctx) -> when(router.parseRequestPath(any(), any()))
            .thenReturn(new NeoServlet.NeoPathInfo("spec", "entity", null)));
    builtIns = mockConstruction(NeoBuiltInEndpointHandler.class);
    pseudoSpecs = mockConstruction(NeoPseudoSpecDispatcher.class);
    servlet = new NeoServlet();
    response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body, true));
  }

  @AfterEach
  void tearDown() {
    ClassicMessageRemap.setTextsForTest(null);
    NeoLanguage.restoreRequestCode(null);
    pseudoSpecs.close();
    builtIns.close();
    routers.close();
    authenticators.close();
    obContext.close();
  }

  private void routedCallThrows(Exception e) throws Exception {
    doThrow(e).when(routers.constructed().get(0))
        .handleSpecRequest(any(), anyString(), any(), any());
  }

  private static MalformedJsonException parseFailureOf(String text) {
    try {
      BoundedJsonParser.parseObject(text);
    } catch (MalformedJsonException e) {
      return e;
    }
    throw new AssertionError("expected " + text + " to be malformed");
  }

  private static final String REVERSED_ES = "Si el tipo de factura es Rectificativa o Factura "
      + "emitida en sustitución de facturas simplificadas, debe añadir las facturas relacionadas "
      + "en la solapa Facturas Rectificativas.";
  private static final String REVERSED_GO_ES = "El Tipo de Factura de VERI*FACTU (solapa SIF) es "
      + "rectificativo o F3 (emitida en sustitución de simplificadas): añade las facturas que "
      + "rectifica en la pestaña Rectificaciones.";

  private static void switchLanguage(OBContext ctx, String code) {
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn(code);
    when(ctx.getLanguage()).thenReturn(language);
  }

  /**
   * ETP-6158, REST end to end: a handler answers a Classic fiscal message after a Classic call
   * switched the {@code OBContext} language; the response leaves reworded in the language the
   * request was authenticated with, with the AD_MESSAGE key in {@code error.messageKeys}, and the
   * captured language does not outlive the request.
   */
  @Test
  void aClassicFiscalMessageLeavesRewordedInTheRequestLanguage() throws Exception {
    ClassicMessageRemap.setTextsForTest(Map.of("ETVFAC_reversed_invoice", List.of(REVERSED_ES)));
    OBContext ctx = mock(OBContext.class);
    switchLanguage(ctx, "es_ES");
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
    doAnswer(inv -> {
      switchLanguage(ctx, "en_US"); // a Classic call changing the session language
      servlet.writeResponse(inv.getArgument(3),
          NeoResponse.ensureTopLevelMessage(NeoResponse.error(422, REVERSED_ES)));
      return null;
    }).when(routers.constructed().get(0)).handleSpecRequest(any(), anyString(), any(), any());

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(422);
    JSONObject written = new JSONObject(body.toString());
    assertEquals(REVERSED_GO_ES, written.getString("message"));
    JSONObject error = written.getJSONObject("error");
    assertEquals(REVERSED_GO_ES, error.getString("message"));
    assertEquals("ETVFAC_reversed_invoice", error.getJSONArray("messageKeys").getString(0));
    assertEquals("en_US", NeoLanguage.requestCode());
  }

  /** writeResponse on its own: the remap runs before the body is serialized. */
  @Test
  void writeResponseRemapsBeforeWriting() throws Exception {
    ClassicMessageRemap.setTextsForTest(Map.of("ETVFAC_reversed_invoice", List.of(REVERSED_ES)));
    OBContext ctx = mock(OBContext.class);
    switchLanguage(ctx, "en_US");
    obContext.when(OBContext::getOBContext).thenReturn(ctx);

    servlet.writeResponse(response, NeoResponse.error(409, "Cannot complete: " + REVERSED_ES));

    verify(response).setStatus(409);
    verify(response).setContentType(eq("application/json"));
    JSONObject error = new JSONObject(body.toString()).getJSONObject("error");
    assertEquals("Cannot complete: The VERI*FACTU Invoice type (SIF tab) is a corrective one or "
        + "F3 (issued to replace simplified invoices): add the invoices it corrects in the "
        + "Rectifications tab.", error.getString("message"));
    assertEquals("ETVFAC_reversed_invoice", error.getJSONArray("messageKeys").getString(0));
  }

  @Test
  void aMalformedJsonBodyIsA400InvalidJsonBody() throws Exception {
    routedCallThrows(parseFailureOf("{\"x\":["));

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(body.toString().contains("Invalid JSON body"), body.toString());
  }

  /** jettison's syntax error quotes the whole text; the 400 must not echo the client's body. */
  @Test
  void theErrorDoesNotEchoTheBody() throws Exception {
    routedCallThrows(parseFailureOf("{\"password\":\"" + SECRET + "\":1}"));

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(body.toString().contains("Invalid JSON body"), body.toString());
    assertFalse(body.toString().contains(SECRET), body.toString());
  }

  @Test
  void anyOtherFailureIsStillA500() throws Exception {
    routedCallThrows(new IllegalStateException("boom"));

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  /**
   * ETP-5716 — the fiscal models catalog PUT refused because the stored catalog cannot be read
   * (an {@link IllegalStateException} out of the built-in endpoint, wrapping the preference
   * failure) is a 500: a server fault the client cannot fix by changing its body, not a 400, and
   * never a 200/204 that would read as saved.
   */
  @Test
  void aRefusedFiscalModelsCatalogSaveIsA500() throws Exception {
    when(builtIns.constructed().get(0).handle(any(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException(
            "The stored fiscal models catalog could not be read; nothing was saved",
            new org.openbravo.erpCommon.utility.PropertyException("conflict", null)));

    servlet.doPut(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    verify(response, org.mockito.Mockito.never()).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    verify(response, org.mockito.Mockito.never()).setStatus(HttpServletResponse.SC_OK);
    verify(response, org.mockito.Mockito.never()).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  /** A refused DAL access is a decision about the role, not a server failure. */
  @Test
  void aRefusedAccessIsA403() throws Exception {
    routedCallThrows(new org.openbravo.base.exception.OBSecurityException(
        "Entity C_Order is not writable by this role"));
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      obdal.when(org.openbravo.dal.service.OBDal::getInstance)
          .thenReturn(mock(org.openbravo.dal.service.OBDal.class));

      servlet.doPost(mock(HttpServletRequest.class), response);
    }

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    assertTrue(body.toString().contains("\"code\":\"forbidden\""), body.toString());
    assertFalse(body.toString().contains("C_Order"), body.toString());
  }

  /** A trigger refusal escaping a handler is the caller's to fix, and nothing it wrote is kept. */
  @Test
  void aTriggerRefusalIsA422AndRollsBack() throws Exception {
    routedCallThrows(new org.hibernate.exception.GenericJDBCException("could not execute",
        new java.sql.SQLException("ERROR: @20502@", "P0001")));
    org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);
    try (MockedStatic<org.openbravo.dal.service.OBDal> obdal =
        mockStatic(org.openbravo.dal.service.OBDal.class)) {
      obdal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      servlet.doPost(mock(HttpServletRequest.class), response);
    }

    verify(response).setStatus(422);
    verify(dal).rollbackAndClose();
    assertTrue(body.toString().contains("\"code\":\"business_rule\""), body.toString());
  }

  /**
   * ETP-5715 — the MCP attachment upload link is credentialed by its token, so it is answered BEFORE
   * authentication, exactly like the image link; nothing else about the request is trusted.
   */
  @Test
  void aPutToTheAttachmentUploadLinkIsServedWithoutAuthentication() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn("/attachment/upload/some-token");

    try (MockedStatic<NeoAttachmentUploadEndpoint> endpoint =
        mockStatic(NeoAttachmentUploadEndpoint.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      endpoint.when(() -> NeoAttachmentUploadEndpoint.handle(any(), any())).thenAnswer(i -> null);

      servlet.doPut(request, response);

      endpoint.verify(() -> NeoAttachmentUploadEndpoint.handle(request, response));
    }
    org.mockito.Mockito.verifyNoInteractions(authenticators.constructed().get(0));
  }

  /** Only a PUT is the link: any other verb on that path goes through authentication as usual. */
  @Test
  void aGetOnTheAttachmentUploadLinkPathIsNotTheLink() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn("/attachment/upload/some-token");

    try (MockedStatic<NeoAttachmentUploadEndpoint> endpoint =
        mockStatic(NeoAttachmentUploadEndpoint.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      servlet.doGet(request, response);

      endpoint.verify(() -> NeoAttachmentUploadEndpoint.handle(any(), any()),
          org.mockito.Mockito.never());
    }
    verify(authenticators.constructed().get(0)).authenticateRequest(request, response);
  }

  // ── public API usage (ETP-6057): the answer is counted whichever path wrote it ──

  private static final PublicApiCaller KEY =
      new PublicApiCaller("TENANT", "ORG", "USER", "ROLE", "key-public");

  private final AtomicInteger answeredStatus = new AtomicInteger(0);
  private PublicApiUsageCounter usageCounter;

  private HttpServletRequest publicKeyRequest(PublicApiCaller caller) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(PublicApiKeyAttribution.REQUEST_ATTRIBUTE)).thenReturn(caller);
    when(request.getPathInfo()).thenReturn("/sales-order/order");
    doAnswer(inv -> {
      answeredStatus.set(inv.getArgument(0));
      return null;
    }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());
    when(response.getStatus()).thenAnswer(inv -> answeredStatus.get());
    return request;
  }

  /** Runs {@code call} with the servlet's usage recorder wired to a mock counter. */
  private void counting(java.util.concurrent.Callable<Object> call) throws Exception {
    usageCounter = mock(PublicApiUsageCounter.class);
    NeoPublicApiUsage real = new NeoPublicApiUsage(usageCounter, () -> 0L);
    try (MockedStatic<NeoPublicApiUsage> usage = mockStatic(NeoPublicApiUsage.class);
        MockedStatic<org.openbravo.dal.service.OBDal> obdal =
            mockStatic(org.openbravo.dal.service.OBDal.class)) {
      usage.when(NeoPublicApiUsage::getDefault).thenReturn(real);
      obdal.when(org.openbravo.dal.service.OBDal::getInstance)
          .thenReturn(mock(org.openbravo.dal.service.OBDal.class));
      call.call();
    }
  }

  private void routedCallAnswers(int status) throws Exception {
    doAnswer(inv -> {
      response.setStatus(status);
      return null;
    }).when(routers.constructed().get(0)).handleSpecRequest(any(), anyString(), any(), any());
  }

  private void verifyCounted(String method, int status) {
    verify(usageCounter).count(KEY, PublicApiCall.of("sales-order", "order", null, method, status));
  }

  @Test
  void aPublicKeyRequestAnswered2xxIsCountedWithThatStatus() throws Exception {
    HttpServletRequest request = publicKeyRequest(KEY);
    routedCallAnswers(200);

    counting(() -> {
      servlet.doGet(request, response);
      return null;
    });

    verifyCounted("GET", 200);
  }

  @Test
  void aHandlerAnswering4xxIsCountedWithThatStatus() throws Exception {
    HttpServletRequest request = publicKeyRequest(KEY);
    routedCallAnswers(404);

    counting(() -> {
      servlet.doPost(request, response);
      return null;
    });

    verifyCounted("POST", 404);
  }

  @Test
  void aRefusedAccessIsCountedAs403() throws Exception {
    HttpServletRequest request = publicKeyRequest(KEY);
    routedCallThrows(new org.openbravo.base.exception.OBSecurityException("not writable"));

    counting(() -> {
      servlet.doPut(request, response);
      return null;
    });

    verifyCounted("PUT", 403);
  }

  @Test
  void anExceptionPathIsCountedWithTheStatusItAnswered() throws Exception {
    HttpServletRequest request = publicKeyRequest(KEY);
    routedCallThrows(new IllegalStateException("boom"));

    counting(() -> {
      servlet.doDelete(request, response);
      return null;
    });

    verifyCounted("DELETE", 500);
  }

  @Test
  void aRequestRefusedByAuthenticationIsStillCounted() throws Exception {
    HttpServletRequest request = publicKeyRequest(KEY);
    when(authenticators.constructed().get(0).authenticateRequest(any(), any()))
        .thenAnswer(inv -> {
          response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
          return false;
        });

    counting(() -> {
      servlet.doGet(request, response);
      return null;
    });

    verifyCounted("GET", 402);
  }

  @Test
  void aTokenOrCookieSessionThatIsNotAPublicKeyIsNotCounted() throws Exception {
    HttpServletRequest request = publicKeyRequest(null);
    routedCallAnswers(200);

    counting(() -> {
      servlet.doGet(request, response);
      return null;
    });

    verify(usageCounter, never()).count(any(), any());
  }
}
