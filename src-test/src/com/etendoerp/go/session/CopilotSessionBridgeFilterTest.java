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
package com.etendoerp.go.session;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.copilot.rest.CopilotJwtServlet;
import com.etendoerp.copilot.rest.RestService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for {@link CopilotSessionBridgeFilter}: the cookie session must reach
 * {@code /sws/copilot/*} with {@code OBContext} built from the session, and every
 * non-authenticated outcome must keep the behaviour the Copilot servlet had before.
 */
class CopilotSessionBridgeFilterTest {

  private GoSessionAuthenticator authenticator;
  private CopilotSessionBridgeFilter.CopilotDispatcher dispatcher;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;
  private StringWriter body;

  @BeforeEach
  void setUp() throws Exception {
    authenticator = mock(GoSessionAuthenticator.class);
    dispatcher = mock(CopilotSessionBridgeFilter.CopilotDispatcher.class);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
    body = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(response.getWriter()).thenReturn(new PrintWriter(body));
  }

  private CopilotSessionBridgeFilter filter() {
    return new CopilotSessionBridgeFilter(authenticator, dispatcher);
  }

  private static GoSessionRecord sessionWithEnvironment(String warehouseId) {
    GoSessionRecord session = new GoSessionRecord();
    session.setUserId("U1");
    session.setRoleId("R1");
    session.setCtxOrgId("O1");
    session.setCtxClientId("C1");
    session.setWarehouseId(warehouseId);
    return session;
  }

  @Test
  void requestWithAuthorizationHeaderIsLeftToTheServlet() throws Exception {
    when(request.getHeader(CopilotSessionBridgeFilter.AUTH_HEADER)).thenReturn("Bearer legacy");

    filter().doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(authenticator, dispatcher);
  }

  @Test
  void preflightPassesThrough() throws Exception {
    when(request.getMethod()).thenReturn("OPTIONS");

    filter().doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(authenticator);
  }

  @Test
  void noSessionPassesThroughSoTheServletAnswersItsOwn401() throws Exception {
    when(authenticator.authenticate(request)).thenReturn(GoSessionAuthResult.noSession());

    filter().doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void invalidSessionIsRejectedWith401() throws Exception {
    when(authenticator.authenticate(request)).thenReturn(GoSessionAuthResult.unauthenticated());

    filter().doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void csrfFailureIsRejectedWith403() throws Exception {
    when(authenticator.authenticate(request)).thenReturn(GoSessionAuthResult.csrfFailed());

    filter().doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void sessionWithoutEnvironmentIsRejectedWith401() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(new GoSessionRecord()));

    filter().doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(body.toString().contains("Session has no environment selected"));
    verifyNoInteractions(dispatcher);
  }

  // ETP-5289 — an environment whose only warehouse belongs to org 0 yields a session with no
  // warehouse. Minting an SWS JWT for it threw a NullPointerException inside generateToken;
  // the context must be built with a null warehouse instead, as NEO does.
  @Test
  void sessionWithoutWarehouseIsServedWithItsContext() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(sessionWithEnvironment(null)));
    OBContext ctx = mock(OBContext.class);
    try (MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBContext> obc = mockStatic(OBContext.class)) {
      sws.when(() -> SecureWebServicesUtils.createContext("U1", "R1", "O1", null, "C1"))
          .thenReturn(ctx);

      filter().doFilter(request, response, chain);

      sws.verify(() -> SecureWebServicesUtils.createContext(any(), any(), any(), isNull(), any()));
      obc.verify(() -> OBContext.setOBContext(ctx));
      obc.verify(() -> OBContext.setOBContextInSession(request, ctx));
    }
    verify(dispatcher).dispatch(request, response);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void contextFailureIsRejectedWith401() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(sessionWithEnvironment("W1")));
    try (MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      sws.when(() -> SecureWebServicesUtils.createContext(any(), any(), any(), any(), any()))
          .thenThrow(new OBException("Role not accessible"));

      filter().doFilter(request, response, chain);
    }
    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(body.toString().contains("Role not accessible"));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void blankEnvironmentThrowsBeforeTouchingTheContext() {
    GoSessionRecord session = new GoSessionRecord();
    assertThrows(OBException.class,
        () -> CopilotSessionBridgeFilter.applySessionContext(request, session));
  }

  @Test
  void getAndPostAreRoutedToTheCopilotRestService() throws Exception {
    RestService rest = mock(RestService.class);
    try (MockedStatic<CopilotJwtServlet> servlet = mockStatic(CopilotJwtServlet.class)) {
      servlet.when(CopilotJwtServlet::getInstance).thenReturn(rest);

      when(request.getMethod()).thenReturn("GET");
      CopilotSessionBridgeFilter.dispatchToRestService(request, response);
      when(request.getMethod()).thenReturn("POST");
      CopilotSessionBridgeFilter.dispatchToRestService(request, response);
    }
    verify(rest).doGet(request, response);
    verify(rest).doPost(request, response);
  }

  @Test
  void otherMethodsAreRejectedWith405() throws Exception {
    when(request.getMethod()).thenReturn("DELETE");

    CopilotSessionBridgeFilter.dispatchToRestService(request, response);

    verify(response).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
  }
}
