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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

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
 * <p>This filter only translates the credential. A resolved session gets a short-lived SWS JWT
 * for the same user, role, organization and warehouse, injected as the {@code Authorization}
 * header. The Copilot servlet stays the authority that validates the token and routes the
 * request. It decides like {@link com.etendoerp.go.common.JwtAuthUtils#authenticateOrFail}:
 * <ul>
 *   <li>a request that already carries {@code Authorization} is left untouched;</li>
 *   <li>no session cookie: pass through, so the servlet answers its own 401;</li>
 *   <li>invalid or expired session: 401;</li>
 *   <li>unsafe method failing CSRF/Origin: 403 (the authenticator enforces it).</li>
 * </ul>
 */
@WebFilter(urlPatterns = { "/sws/copilot/*" })
public class CopilotSessionBridgeFilter implements Filter {

  private static final Logger log = LogManager.getLogger(CopilotSessionBridgeFilter.class);

  static final String AUTH_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  /** Mints the SWS JWT for a resolved session. A seam so tests need no key material. */
  @FunctionalInterface
  interface SessionTokenMinter {
    String mint(GoSessionRecord session) throws Exception;
  }

  private final GoSessionAuthenticator authenticator;
  private final SessionTokenMinter minter;

  /** Container constructor. */
  public CopilotSessionBridgeFilter() {
    this(new GoSessionAuthenticator(new GoSessionService(new JdbcGoSessionStore())),
        CopilotSessionBridgeFilter::mintSwsToken);
  }

  CopilotSessionBridgeFilter(GoSessionAuthenticator authenticator, SessionTokenMinter minter) {
    this.authenticator = authenticator;
    this.minter = minter;
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
        forwardWithToken(result.getRecord(), httpReq, httpResp, chain);
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

  private void forwardWithToken(GoSessionRecord session, HttpServletRequest request,
      HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
    String token = mintOrFail(session, response);
    if (token != null) {
      chain.doFilter(new BearerRequest(request, token), response);
    }
  }

  private String mintOrFail(GoSessionRecord session, HttpServletResponse response)
      throws IOException {
    try {
      return minter.mint(session);
    } catch (OBException e) {
      log.warn("Unauthorized copilot request: {}", e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
    } catch (Exception e) {
      log.error("Could not mint a copilot token for the session", e);
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
          "Could not authenticate the session");
    }
    return null;
  }

  /**
   * Mint an SWS JWT carrying the session's environment. {@code OBContext} is set first because
   * {@code generateToken} reads the signing-algorithm preference through it.
   */
  static String mintSwsToken(GoSessionRecord session) throws Exception {
    if (StringUtils.isAnyBlank(session.getUserId(), session.getRoleId(), session.getCtxOrgId(),
        session.getCtxClientId())) {
      throw new OBException("Session has no environment selected");
    }
    OBContext.setOBContext(SecureWebServicesUtils.createContext(session.getUserId(),
        session.getRoleId(), session.getCtxOrgId(), session.getWarehouseId(),
        session.getCtxClientId()));
    OBContext.setAdminMode(true);
    try {
      OBDal dal = OBDal.getInstance();
      User user = dal.get(User.class, session.getUserId());
      if (user == null) {
        throw new OBException("Session user not found");
      }
      Warehouse warehouse = StringUtils.isBlank(session.getWarehouseId()) ? null
          : dal.get(Warehouse.class, session.getWarehouseId());
      return SecureWebServicesUtils.generateToken(user, dal.get(Role.class, session.getRoleId()),
          dal.get(Organization.class, session.getCtxOrgId()), warehouse);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void destroy() {
    // Nothing to release.
  }

  /** Exposes a minted token as the request's {@code Authorization} header. */
  static final class BearerRequest extends HttpServletRequestWrapper {
    private final String authorization;

    BearerRequest(HttpServletRequest request, String token) {
      super(request);
      this.authorization = BEARER_PREFIX + token;
    }

    @Override
    public String getHeader(String name) {
      return AUTH_HEADER.equalsIgnoreCase(name) ? authorization : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return AUTH_HEADER.equalsIgnoreCase(name)
          ? Collections.enumeration(Collections.singletonList(authorization))
          : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names = new ArrayList<>();
      Enumeration<String> original = super.getHeaderNames();
      while (original != null && original.hasMoreElements()) {
        String name = original.nextElement();
        if (!AUTH_HEADER.equalsIgnoreCase(name)) {
          names.add(name);
        }
      }
      names.add(AUTH_HEADER);
      return Collections.enumeration(names);
    }
  }
}
