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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.copilot.rest.CopilotJwtServlet;
import com.etendoerp.go.common.ServletResponseUtils;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Lets the cookie session reach the Copilot endpoints under {@code /sws/copilot/*}.
 *
 * <p>Since ETP-4576 the SPA authenticates with the {@code __Host-} session cookie and holds no
 * token, but {@code CopilotJwtServlet} (module com.etendoerp.copilot) only accepts
 * {@code Authorization: Bearer <SWS JWT>}. Every OCR upload and tool call was therefore answered
 * 401, which the SPA reads as an expired session and turns into a logout.
 *
 * <p>For a resolved session this filter builds {@code OBContext} from the session's environment,
 * exactly like NEO does, and hands the request to Copilot's own {@code RestService}. It does NOT
 * mint a JWT for the servlet: {@code SecureWebServicesUtils.generateToken} dereferences the
 * resolved warehouse unconditionally, so an environment whose only warehouse belongs to org
 * {@code 0} (linked through {@code AD_Org_Warehouse}) made it throw a NullPointerException. The
 * servlet's JWT also demands a warehouse claim, which a session does not always carry.
 *
 * <p>It decides like {@link com.etendoerp.go.common.JwtAuthUtils#authenticateOrFail}:
 * <ul>
 *   <li>a request that already carries {@code Authorization} is left to the servlet;</li>
 *   <li>no session cookie: pass through, so the servlet answers its own 401;</li>
 *   <li>invalid or expired session, or no environment selected: 401;</li>
 *   <li>unsafe method failing CSRF/Origin: 403 (the authenticator enforces it).</li>
 * </ul>
 */
@WebFilter(urlPatterns = { "/sws/copilot/*" })
public class CopilotSessionBridgeFilter implements Filter {

  private static final Logger log = LogManager.getLogger(CopilotSessionBridgeFilter.class);

  static final String AUTH_HEADER = "Authorization";

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

  private final GoSessionAuthenticator authenticator;
  private final CopilotDispatcher dispatcher;

  /** Container constructor. */
  public CopilotSessionBridgeFilter() {
    this(new GoSessionAuthenticator(new GoSessionService(new JdbcGoSessionStore())),
        CopilotSessionBridgeFilter::dispatchToRestService);
  }

  CopilotSessionBridgeFilter(GoSessionAuthenticator authenticator, CopilotDispatcher dispatcher) {
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

    if ("OPTIONS".equalsIgnoreCase(httpReq.getMethod())
        || StringUtils.isNotBlank(httpReq.getHeader(AUTH_HEADER))) {
      chain.doFilter(request, response);
      return;
    }

    GoSessionAuthResult result = authenticator.authenticate(httpReq);
    switch (result.getStatus()) {
      case AUTHENTICATED:
        serveWithSession(result.getRecord(), httpReq, httpResp);
        return;
      case CSRF_FAILED:
        log.warn("Forbidden copilot request: CSRF validation failed");
        ServletResponseUtils.sendError(httpResp, HttpServletResponse.SC_FORBIDDEN,
            "CSRF validation failed");
        return;
      case UNAUTHENTICATED:
        ServletResponseUtils.sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
            "Invalid or expired session");
        return;
      case NO_SESSION:
      default:
        chain.doFilter(request, response);
    }
  }

  private void serveWithSession(GoSessionRecord session, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      applySessionContext(request, session);
    } catch (OBException e) {
      log.warn("Unauthorized copilot request: {}", e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    }
    dispatcher.dispatch(request, response);
  }

  /**
   * Rebuild {@code OBContext} from the session record, the same shape as NEO's and
   * {@code JwtAuthUtils}' session path. A null warehouse is valid here.
   */
  static void applySessionContext(HttpServletRequest request, GoSessionRecord session) {
    if (StringUtils.isAnyBlank(session.getUserId(), session.getRoleId(), session.getCtxOrgId(),
        session.getCtxClientId())) {
      throw new OBException("Session has no environment selected");
    }
    OBContext ctx = SecureWebServicesUtils.createContext(session.getUserId(), session.getRoleId(),
        session.getCtxOrgId(), session.getWarehouseId(), session.getCtxClientId());
    OBContext.setOBContext(ctx);
    OBContext.setOBContextInSession(request, ctx);
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
