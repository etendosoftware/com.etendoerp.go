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
 * Authentication helpers for {@link OAuth2Servlet}'s authorize endpoint: cookie-session resolution
 * with the legacy JWT (carried in the authorize body) as fallback. ETP-5455 moved the SPA-facing
 * management endpoints (API keys, OAuth2 clients) to the shared environment pipeline, together with
 * the header-only JWT decode and the admin check that used to live here.
 * Extracted from {@code OAuth2Servlet} to keep that class under the method-count limit.
 */
final class OAuth2RequestAuthenticator {

  private static final Logger log = LogManager.getLogger(OAuth2RequestAuthenticator.class);
  /** The System Administrator role; the OAuth2 client-management endpoints require it. */
  static final String ADMIN_ROLE_ID = "0";

  private OAuth2RequestAuthenticator() {
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
