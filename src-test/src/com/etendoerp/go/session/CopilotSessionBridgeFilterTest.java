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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.openbravo.dal.core.OBContext;

import com.etendoerp.copilot.rest.CopilotJwtServlet;
import com.etendoerp.copilot.rest.RestService;
import com.etendoerp.go.auth.AuthScheme;
import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentAuthOutcome.Status;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;

/**
 * Unit tests for {@link CopilotSessionBridgeFilter}: a cookie session must reach
 * {@code /sws/copilot/*} through the shared authentication pipeline, a refused cookie gets the
 * pipeline's answer, and a request without a cookie keeps the Copilot servlet's own behaviour.
 */
class CopilotSessionBridgeFilterTest {

  private EnvironmentRequestAuthenticator authenticator;
  private CopilotSessionBridgeFilter.CopilotDispatcher dispatcher;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;
  private StringWriter body;

  @BeforeEach
  void setUp() throws Exception {
    authenticator = mock(EnvironmentRequestAuthenticator.class);
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

  private void outcome(EnvironmentAuthOutcome outcome) {
    when(authenticator.authenticate(request, SurfacePolicy.NEO_DATA)).thenReturn(outcome);
  }

  private static EnvironmentAuthOutcome authenticated(AuthScheme scheme) {
    return EnvironmentAuthOutcome.authenticated(scheme, mock(OBContext.class), "U1", "R1", "C1",
        "O1");
  }

  // ETP-5289 — the context comes from the pipeline, which accepts a session with no warehouse
  // (an environment whose only warehouse is in org 0). No JWT is minted, so nothing can NPE.
  @Test
  void cookieSessionIsServedByCopilot() throws Exception {
    outcome(authenticated(AuthScheme.COOKIE));

    filter().doFilter(request, response, chain);

    verify(dispatcher).dispatch(request, response);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void refusedCookieSessionGetsThePipelineStatus() throws Exception {
    outcome(EnvironmentAuthOutcome.refused(Status.UNAUTHENTICATED, "Invalid or expired session",
        AuthScheme.COOKIE));

    filter().doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(body.toString().contains("Invalid or expired session"));
    verify(chain, never()).doFilter(any(), any());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void csrfFailureIsRejectedWith403() throws Exception {
    outcome(EnvironmentAuthOutcome.refused(Status.CSRF_REJECTED, "CSRF validation failed",
        AuthScheme.COOKIE));

    filter().doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void noCredentialIsLeftToTheServlet() throws Exception {
    outcome(EnvironmentAuthOutcome.refused(Status.UNAUTHENTICATED,
        "Missing or invalid Authorization header", null));

    filter().doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void bearerCallerIsLeftToTheServlet() throws Exception {
    outcome(authenticated(AuthScheme.JWT));

    filter().doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void preflightPassesThrough() throws Exception {
    when(request.getMethod()).thenReturn("OPTIONS");

    filter().doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(authenticator, dispatcher);
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
