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
package com.etendoerp.go.oauth2;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Authentication helpers shared by {@link OAuth2Servlet} endpoints: legacy JWT bearer validation,
 * System Administrator role enforcement, and cookie-session resolution for the authorize endpoint.
 * Extracted from {@code OAuth2Servlet} to keep that class under the method-count limit.
 */
final class OAuth2RequestAuthenticator {

  private static final Logger log = LogManager.getLogger(OAuth2RequestAuthenticator.class);
  private static final String ADMIN_ROLE_ID = "0";

  private OAuth2RequestAuthenticator() {
  }

  /**
   * Authenticate the request's {@code Authorization: Bearer <jwt>} header.
   *
   * @param request the HTTP request
   * @return decoded JWT
   * @throws OAuth2Servlet.AuthException if authentication fails
   */
  static DecodedJWT authenticateJwt(HttpServletRequest request) throws OAuth2Servlet.AuthException {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_UNAUTHORIZED,
          "Missing or invalid Authorization header");
    }
    return authenticateJwt(authHeader.substring(7));
  }

  static DecodedJWT authenticateJwt(String token) throws OAuth2Servlet.AuthException {
    try {
      return SecureWebServicesUtils.decodeToken(token);
    } catch (Exception e) {
      log.warn("JWT authentication failed: {}", e.getMessage());
      throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_UNAUTHORIZED,
          "Invalid or expired JWT token");
    }
  }

  /**
   * Authenticate JWT and verify the caller has System Administrator role (roleId = "0").
   *
   * @param request the HTTP request
   * @return decoded JWT
   * @throws OAuth2Servlet.AuthException if authentication or authorization fails
   */
  static DecodedJWT requireAdmin(HttpServletRequest request) throws OAuth2Servlet.AuthException {
    DecodedJWT jwt = authenticateJwt(request);
    String roleId = jwt.getClaim("role").asString();
    if (!ADMIN_ROLE_ID.equals(roleId)) {
      throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_FORBIDDEN,
          "System Administrator role required");
    }
    return jwt;
  }

  /**
   * Resolve the calling principal for {@code POST /oauth2/authorize}: prefers the cookie session
   * (with CSRF/Origin already enforced by {@link GoSessionAuthenticator}), falling back to the
   * legacy JWT bearer while the migration flag is on.
   *
   * @param goSessionService the session service used to resolve the cookie session
   * @param request           the HTTP request
   * @param authorizeRequest  the parsed authorize request data (for the legacy JWT fallback)
   * @return the authenticated user/role pair
   * @throws OAuth2Servlet.AuthException if neither a valid session nor a valid legacy JWT is present
   */
  static AuthorizePrincipal authenticateAuthorizeRequest(GoSessionService goSessionService,
      HttpServletRequest request, OAuth2AuthorizeSupport.AuthorizeRequestData authorizeRequest)
      throws OAuth2Servlet.AuthException {
    GoSessionAuthResult sessionAuth = new GoSessionAuthenticator(goSessionService).authenticate(request);
    if (sessionAuth.getStatus() == GoSessionAuthResult.Status.CSRF_FAILED) {
      throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_FORBIDDEN,
          "CSRF validation failed");
    }
    if (sessionAuth.getStatus() == GoSessionAuthResult.Status.UNAUTHENTICATED) {
      throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_UNAUTHORIZED,
          "Invalid or expired session");
    }
    if (sessionAuth.isAuthenticated()) {
      GoSessionRecord sessionRecord = sessionAuth.getRecord();
      if (StringUtils.isAnyBlank(sessionRecord.getUserId(), sessionRecord.getRoleId())) {
        throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_FORBIDDEN,
            "Session has no environment selected");
      }
      return new AuthorizePrincipal(sessionRecord.getUserId(), sessionRecord.getRoleId());
    }
    if (!GoLegacyBearer.isEnabled() || StringUtils.isBlank(authorizeRequest.jwtToken)) {
      throw new OAuth2Servlet.AuthException(HttpServletResponse.SC_UNAUTHORIZED,
          "Session authentication is required");
    }
    GoLegacyBearer.recordUse();
    DecodedJWT jwt = authenticateJwt(authorizeRequest.jwtToken);
    return new AuthorizePrincipal(jwt.getClaim("user").asString(),
        jwt.getClaim("role").asString());
  }

  /** The authenticated user/role pair resolved for an authorize request. */
  static final class AuthorizePrincipal {
    final String userId;
    final String roleId;

    private AuthorizePrincipal(String userId, String roleId) {
      this.userId = userId;
      this.roleId = roleId;
    }
  }
}
