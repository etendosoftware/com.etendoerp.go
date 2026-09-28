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

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.copilot.rest.CopilotJwtServlet;
import com.etendoerp.go.auth.AuthScheme;
import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;
import com.etendoerp.go.common.ServletResponseUtils;

/**
 * Lets the cookie session reach the Copilot endpoints under {@code /sws/copilot/*}.
 *
 * <p>Since ETP-4576 the SPA authenticates with the {@code __Host-} session cookie and holds no
 * token, but {@code CopilotJwtServlet} (module com.etendoerp.copilot) only accepts
 * {@code Authorization: Bearer <SWS JWT>}. Every OCR upload and tool call was therefore answered
 * 401, which the SPA reads as an expired session and turns into a logout.
 *
 * <p>The request is authenticated by the shared {@link EnvironmentRequestAuthenticator}
 * (ETP-5455), so the cookie gets the same role reconciliation, warehouse repair and commercial
 * access check as NEO. For a cookie session the context it installs is used to hand the request
 * to Copilot's own {@code RestService}. No JWT is minted for the servlet:
 * {@code SecureWebServicesUtils.generateToken} dereferences the resolved warehouse
 * unconditionally, so an environment whose only warehouse belongs to org {@code 0} (linked
 * through {@code AD_Org_Warehouse}) made it throw a NullPointerException.
 *
 * <p>How each outcome is answered:
 * <ul>
 *   <li>cookie session authenticated: served by Copilot with the session's context;</li>
 *   <li>cookie session refused (expired, CSRF, no environment, blocked): the pipeline's status;</li>
 *   <li>no cookie: left to the servlet, which keeps validating its own Bearer JWT.</li>
 * </ul>
 */
@WebFilter(urlPatterns = { "/sws/copilot/*" })
public class CopilotSessionBridgeFilter implements Filter {

  private static final Logger log = LogManager.getLogger(CopilotSessionBridgeFilter.class);

  /** Hands an authenticated request to Copilot. A seam so tests need no Copilot service. */
  @FunctionalInterface
  interface CopilotDispatcher {
    /**
     * Serve the request with {@code OBContext} already set for the session.
     *
     * @param request  the incoming request
     * @param response the response to write
     * @throws IOException if writing the response fails
     */
    void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException;
  }

  private final EnvironmentRequestAuthenticator authenticator;
  private final CopilotDispatcher dispatcher;

  /** Container constructor. */
  public CopilotSessionBridgeFilter() {
    this(new EnvironmentRequestAuthenticator(), CopilotSessionBridgeFilter::dispatchToRestService);
  }

  CopilotSessionBridgeFilter(EnvironmentRequestAuthenticator authenticator,
      CopilotDispatcher dispatcher) {
    this.authenticator = authenticator;
    this.dispatcher = dispatcher;
  }

  @Override
  public void init(FilterConfig filterConfig) {
    // No configuration.
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) request;
    HttpServletResponse httpResp = (HttpServletResponse) response;

    if ("OPTIONS".equalsIgnoreCase(httpReq.getMethod())) {
      chain.doFilter(request, response);
      return;
    }

    EnvironmentAuthOutcome outcome = authenticator.authenticate(httpReq, SurfacePolicy.NEO_DATA);
    if (outcome.getScheme() != AuthScheme.COOKIE) {
      // No cookie: a Bearer caller, or nobody. The servlet keeps answering those itself.
      chain.doFilter(request, response);
      return;
    }
    if (!outcome.isAuthenticated()) {
      log.warn("Refused copilot request ({}): {}", outcome.getHttpStatus(), outcome.getMessage());
      ServletResponseUtils.sendError(httpResp, outcome.getHttpStatus(), outcome.getMessage());
      return;
    }
    dispatcher.dispatch(httpReq, httpResp);
  }

  /**
   * Route to Copilot's {@code RestService}, as {@code CopilotJwtServlet} does once its JWT check
   * passes. The servlet serves only GET and POST.
   */
  static void dispatchToRestService(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if ("GET".equalsIgnoreCase(request.getMethod())) {
      CopilotJwtServlet.getInstance().doGet(request, response);
    } else if ("POST".equalsIgnoreCase(request.getMethod())) {
      CopilotJwtServlet.getInstance().doPost(request, response);
    } else {
      response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }
  }

  @Override
  public void destroy() {
    // Nothing to release.
  }
}
