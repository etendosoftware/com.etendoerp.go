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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.common;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoNeoAuth;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.JdbcGoSessionStore;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Shared JWT authentication utility for Etendo GO servlets.
 *
 * ETP-4575 — {@link #authenticateOrFail} honours the {@code __Host-} cookie session first and
 * only falls back to {@code Authorization: Bearer} when no session cookie is present (and the
 * legacy path is still enabled). It used to be bearer-only, which made every servlet built on it
 * reject a perfectly valid cookie session with "Missing or invalid Authorization header" — and
 * because the frontend logs out on a 401, one peripheral widget failing that way revoked the whole
 * session and blanked every window. {@link #authenticate} stays the bearer-only primitive.
 */
public class JwtAuthUtils {

  private static final String AUTH_HEADER = "Authorization";
  private static final String UNAUTHORIZED_LOG = "Unauthorized {}: {}";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CLAIM_USER = "user";
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_ORG = "organization";
  private static final String CLAIM_WAREHOUSE = "warehouse";
  private static final String CLAIM_CLIENT = "client";
  private static final String MSG_MISSING_AUTH_HEADER = "Missing or invalid Authorization header";

  private static final GoSessionAuthenticator SESSION_AUTHENTICATOR =
      new GoSessionAuthenticator(new GoSessionService(new JdbcGoSessionStore()));

  private JwtAuthUtils() {
  }

  /**
   * Authenticates the request via Bearer JWT and sets up OBContext.
   *
   * @param request the incoming HTTP request carrying the Authorization header
   * @throws OBException if the token is missing, invalid, or has missing claims
   * @throws Exception   for any other decode/context failure
   */
  public static void authenticate(HttpServletRequest request) throws Exception {
    String token = extractBearerToken(request);
    Claims claims = decodeClaims(token);
    applyContext(request, claims);
  }

  /**
   * Authenticates the request and, on failure, writes a 401 response and logs the reason.
   *
   * @param request  the incoming HTTP request
   * @param response the HTTP response (used to write the 401 body on failure)
   * @param log      logger used to record the failure cause
   * @param context  short label for the endpoint, included in the log message
   * @return {@code true} when authentication succeeded, {@code false} when the caller must abort
   * @throws IOException if writing the 401 response body fails
   */
  public static boolean authenticateOrFail(HttpServletRequest request, HttpServletResponse response,
      Logger log, String context) throws IOException {
    GoSessionAuthResult sessionAuth = SESSION_AUTHENTICATOR.authenticate(request);
    switch (GoNeoAuth.decide(sessionAuth.getStatus(), GoLegacyBearer.isEnabled())) {
      case USE_SESSION:
        return applySessionOrFail(request, response, log, context, sessionAuth.getRecord());
      case CSRF_REJECTED:
        log.warn("Forbidden {}: CSRF validation failed", context);
        ServletResponseUtils.sendError(response, HttpServletResponse.SC_FORBIDDEN,
            "CSRF validation failed");
        return false;
      case SESSION_INVALID:
        log.warn("Unauthorized {}: invalid or expired session", context);
        ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
            "Invalid or expired session");
        return false;
      case NO_CREDENTIALS:
        log.warn("Unauthorized {}: no credentials", context);
        ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
            MSG_MISSING_AUTH_HEADER);
        return false;
      case USE_LEGACY_BEARER:
      default:
        GoLegacyBearer.recordUse();
        return authenticateBearerOrFail(request, response, log, context);
    }
  }

  private static boolean applySessionOrFail(HttpServletRequest request, HttpServletResponse response,
      Logger log, String context, GoSessionRecord session) throws IOException {
    try {
      applySessionContext(request, session);
      return true;
    } catch (OBException e) {
      log.warn(UNAUTHORIZED_LOG, context, e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return false;
    }
  }

  private static boolean authenticateBearerOrFail(HttpServletRequest request,
      HttpServletResponse response, Logger log, String context) throws IOException {
    try {
      authenticate(request);
      return true;
    } catch (OBException e) {
      log.warn(UNAUTHORIZED_LOG, context, e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return false;
    } catch (Exception e) {
      log.warn(UNAUTHORIZED_LOG, context, e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return false;
    }
  }

  /**
   * Reconstruct {@link OBContext} from a resolved cookie session, mirroring
   * {@link #applyContext} but sourcing the environment from the session record instead of JWT
   * claims. Same shape as NeoAuthenticator's own session path, deliberately: a caller must not be
   * able to tell which servlet it reached by how its session is honoured.
   */
  private static void applySessionContext(HttpServletRequest request, GoSessionRecord session) {
    if (StringUtils.isAnyBlank(session.getUserId(), session.getRoleId(), session.getCtxOrgId(),
        session.getCtxClientId())) {
      throw new OBException("Session has no environment selected");
    }
    OBContext ctx = SecureWebServicesUtils.createContext(session.getUserId(), session.getRoleId(),
        session.getCtxOrgId(), session.getWarehouseId(), session.getCtxClientId());
    OBContext.setOBContext(ctx);
    OBContext.setOBContextInSession(request, ctx);
  }

  private static String extractBearerToken(HttpServletRequest request) {
    String authHeader = request.getHeader(AUTH_HEADER);
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      throw new OBException(MSG_MISSING_AUTH_HEADER);
    }
    return authHeader.substring(BEARER_PREFIX.length());
  }

  private static Claims decodeClaims(String token) throws Exception {
    DecodedJWT decoded = SecureWebServicesUtils.decodeToken(token);
    if (decoded == null) {
      throw new OBException("Invalid token: unable to decode JWT");
    }
    Claims claims = new Claims(
        decoded.getClaim(CLAIM_USER).asString(),
        decoded.getClaim(CLAIM_ROLE).asString(),
        decoded.getClaim(CLAIM_ORG).asString(),
        decoded.getClaim(CLAIM_WAREHOUSE).asString(),
        decoded.getClaim(CLAIM_CLIENT).asString());
    if (StringUtils.isAnyBlank(claims.userId, claims.roleId, claims.orgId, claims.clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }
    return claims;
  }

  private static void applyContext(HttpServletRequest request, Claims c) {
    OBContext ctx = SecureWebServicesUtils.createContext(c.userId, c.roleId, c.orgId, c.warehouseId, c.clientId);
    OBContext.setOBContext(ctx);
    OBContext.setOBContextInSession(request, ctx);
  }

  private static final class Claims {
    final String userId;
    final String roleId;
    final String orgId;
    final String warehouseId;
    final String clientId;

    Claims(String userId, String roleId, String orgId, String warehouseId, String clientId) {
      this.userId = userId;
      this.roleId = roleId;
      this.orgId = orgId;
      this.warehouseId = warehouseId;
      this.clientId = clientId;
    }
  }
}
