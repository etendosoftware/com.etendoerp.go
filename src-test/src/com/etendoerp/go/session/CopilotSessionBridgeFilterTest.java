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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openbravo.base.exception.OBException;

/**
 * Unit tests for {@link CopilotSessionBridgeFilter}: the cookie session must reach
 * {@code /sws/copilot/*} as a Bearer token, and every non-authenticated outcome must keep the
 * behaviour the Copilot servlet had before.
 */
class CopilotSessionBridgeFilterTest {

  private static final String MINTED = "minted.jwt";

  private GoSessionAuthenticator authenticator;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;
  private StringWriter body;

  @BeforeEach
  void setUp() throws Exception {
    authenticator = mock(GoSessionAuthenticator.class);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
    body = new StringWriter();
    when(request.getMethod()).thenReturn("POST");
    when(response.getWriter()).thenReturn(new PrintWriter(body));
  }

  private CopilotSessionBridgeFilter filterMinting(String token) {
    return new CopilotSessionBridgeFilter(authenticator, session -> token);
  }

  @Test
  void requestWithAuthorizationHeaderIsLeftUntouched() throws Exception {
    when(request.getHeader(CopilotSessionBridgeFilter.AUTH_HEADER)).thenReturn("Bearer legacy");

    filterMinting(MINTED).doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    verifyNoInteractions(authenticator);
  }

  @Test
  void noSessionPassesThroughSoTheServletAnswersItsOwn401() throws Exception {
    when(authenticator.authenticate(request)).thenReturn(GoSessionAuthResult.noSession());

    filterMinting(MINTED).doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  void invalidSessionIsRejectedWith401() throws Exception {
    when(authenticator.authenticate(request)).thenReturn(GoSessionAuthResult.unauthenticated());

    filterMinting(MINTED).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void csrfFailureIsRejectedWith403() throws Exception {
    when(authenticator.authenticate(request)).thenReturn(GoSessionAuthResult.csrfFailed());

    filterMinting(MINTED).doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void authenticatedSessionIsForwardedWithAMintedBearer() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(new GoSessionRecord()));
    when(request.getHeaderNames()).thenReturn(Collections.enumeration(
        Collections.singletonList("Cookie")));

    filterMinting(MINTED).doFilter(request, response, chain);

    ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
    verify(chain).doFilter(forwarded.capture(), any());
    HttpServletRequest wrapped = (HttpServletRequest) forwarded.getValue();
    assertEquals("Bearer " + MINTED, wrapped.getHeader("authorization"));
    assertEquals("Bearer " + MINTED, wrapped.getHeaders("Authorization").nextElement());
    assertTrue(Collections.list(wrapped.getHeaderNames()).contains("Authorization"));
  }

  @Test
  void sessionWithoutEnvironmentIsRejectedWith401() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(new GoSessionRecord()));
    CopilotSessionBridgeFilter filter = new CopilotSessionBridgeFilter(authenticator,
        CopilotSessionBridgeFilter::mintSwsToken);

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    assertTrue(body.toString().contains("Session has no environment selected"));
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void unexpectedMintFailureIsRejectedWith401() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(new GoSessionRecord()));
    CopilotSessionBridgeFilter filter = new CopilotSessionBridgeFilter(authenticator, session -> {
      throw new IllegalStateException("no key");
    });

    filter.doFilter(request, response, chain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void otherHeadersStillComeFromTheOriginalRequest() {
    when(request.getHeader("Origin")).thenReturn("http://localhost:3100");

    HttpServletRequest wrapped = new CopilotSessionBridgeFilter.BearerRequest(request, MINTED);

    assertEquals("http://localhost:3100", wrapped.getHeader("Origin"));
    assertSame(request, ((javax.servlet.ServletRequestWrapper) wrapped).getRequest());
  }

  @Test
  void obExceptionMessageReachesTheClient() throws Exception {
    when(authenticator.authenticate(request))
        .thenReturn(GoSessionAuthResult.authenticated(new GoSessionRecord()));
    CopilotSessionBridgeFilter filter = new CopilotSessionBridgeFilter(authenticator, session -> {
      throw new OBException("Session user not found");
    });

    filter.doFilter(request, response, chain);

    assertTrue(body.toString().contains("Session user not found"));
  }
}
