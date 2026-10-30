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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;

/**
 * How {@link NeoServlet}'s request entry point maps a failure inside the routed handler to an HTTP
 * status. The collaborators the servlet builds in its field initializers are replaced with
 * {@code mockConstruction} so the routed call can be made to throw.
 *
 * @covers com.etendoerp.go.schemaforge.NeoServlet
 * @covers com.etendoerp.go.schemaforge.NeoErrorResponses
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
}
