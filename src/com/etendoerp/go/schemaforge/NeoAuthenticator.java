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

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoNeoAuth;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.JdbcGoSessionStore;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Authentication and access-check collaborator for {@link NeoServlet}.
 * Validates JWTs, sets up the {@link OBContext}, and answers per-window
 * and per-process access questions for the current role.
 */
class NeoAuthenticator {

  private static final Logger log = LogManager.getLogger(NeoAuthenticator.class);

  private final NeoServlet servlet;
  private final GoSessionAuthenticator sessionAuthenticator =
      new GoSessionAuthenticator(new GoSessionService(new JdbcGoSessionStore()));
  private final TenantEnvironmentLifecycleService environmentLifecycleService =
      new TenantEnvironmentLifecycleService();

  NeoAuthenticator(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Verify the request carries a valid JWT and set up the corresponding
   * {@link OBContext}. On failure writes an HTTP error to {@code response}
   * and returns {@code false}.
   */
  boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      GoSessionAuthResult sessionAuth = sessionAuthenticator.authenticate(request);
      switch (GoNeoAuth.decide(sessionAuth.getStatus(), GoLegacyBearer.isEnabled())) {
        case USE_SESSION:
          applySessionContext(request, sessionAuth.getRecord());
          return true;
        case CSRF_REJECTED:
          servlet.sendError(response, HttpServletResponse.SC_FORBIDDEN, "CSRF validation failed");
          return false;
        case SESSION_INVALID:
          servlet.sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
              "Invalid or expired session");
          return false;
        case NO_CREDENTIALS:
          servlet.sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
              "Missing or invalid Authorization header");
          return false;
        case USE_LEGACY_BEARER:
        default:
          GoLegacyBearer.recordUse();
          authenticateJwt(request);
          return true;
      }
    } catch (CommercialAccessException e) {
      log.info("Commercial access denied for NEO request: {}", e.getMessage());
      servlet.sendError(response, HttpServletResponse.SC_PAYMENT_REQUIRED, e.getMessage());
      return false;
    } catch (OBException e) {
      // OBException messages are safe to expose (we control them)
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      servlet.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return false;
    } catch (Exception e) {
      // Other exceptions (JWT decode failures, NPEs) don't leak internals.
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      servlet.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return false;
    }
  }

  /**
   * Reconstruct {@link OBContext} from a resolved cookie session, mirroring {@link #authenticateJwt}
   * but sourcing the environment from the session record instead of JWT claims. Throws when no
   * environment has been selected on the session yet.
   */
  private void applySessionContext(HttpServletRequest request, GoSessionRecord sessionRecord) {
    if (StringUtils.isAnyBlank(sessionRecord.getUserId(), sessionRecord.getRoleId(), sessionRecord.getCtxOrgId(),
        sessionRecord.getCtxClientId())) {
      throw new OBException("Session has no environment selected");
    }
    OBContext context = SecureWebServicesUtils.createContext(sessionRecord.getUserId(), sessionRecord.getRoleId(),
        sessionRecord.getCtxOrgId(), sessionRecord.getWarehouseId(), sessionRecord.getCtxClientId());
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
    applyRequestLanguage(request);
  }

  void authenticateJwt(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decodedToken;
    try {
      decodedToken = SecureWebServicesUtils.decodeToken(token);
    } catch (Exception jwtFailure) {
      authenticateOAuth2Token(request, token, jwtFailure);
      return;
    }

    if (decodedToken == null) {
      authenticateOAuth2Token(request, token, null);
      return;
    }

    String userId = decodedToken.getClaim("user").asString();
    String roleId = decodedToken.getClaim("role").asString();
    String orgId = decodedToken.getClaim("organization").asString();
    String warehouseId = decodedToken.getClaim("warehouse").asString();
    String clientId = decodedToken.getClaim("client").asString();

    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId, clientId);
    if (context.getWarehouse() != null) {
      String whOrgId = context.getWarehouse().getOrganization().getId();
      boolean accessible = false;
      for (String readableOrg : context.getReadableOrganizations()) {
        if (readableOrg.equals(whOrgId)) {
          accessible = true;
          break;
        }
      }
      if (!accessible) {
        log.warn("JWT warehouse '{}' (org='{}') is not in user '{}' readable orgs — resolving accessible warehouse",
            warehouseId, whOrgId, userId);
        String correctedWarehouseId = NeoServletSupport.findAccessibleWarehouse(context);
        context = SecureWebServicesUtils.createContext(userId, roleId, orgId, correctedWarehouseId, clientId);
      }
    }
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
    enforceEnvironmentAccess(clientId);
    applyRequestLanguage(request);
  }

  private void enforceEnvironmentAccess(String clientId) throws CommercialAccessException {
    EnvironmentAccessPolicy.Decision decision = environmentLifecycleService.evaluateAccess(
        clientId, true, Instant.now());
    if (decision == null || decision == EnvironmentAccessPolicy.Decision.ALLOWED) {
      return;
    }
    throw new CommercialAccessException("Environment access is not available: " + decision.name());
  }

  private static final class CommercialAccessException extends Exception {
    private static final long serialVersionUID = 1L;

    CommercialAccessException(String message) {
      super(message);
    }
  }

  /**
   * Authenticate an opaque client-credentials token using the same persisted OAuth2 token
   * validation used by the MCP endpoint. OAuth2 access tokens are intentionally opaque and cannot
   * be passed to the JWT decoder; the resolved identity still carries the client tenant,
   * organization, role, scopes, expiry and revocation checks performed by OAuth2Filter.
   */
  private void authenticateOAuth2Token(HttpServletRequest request, String token, Exception jwtFailure)
      throws Exception {
    java.util.Map<String, String> identity = OAuth2Filter.validateToken(token);
    if (identity == null) {
      if (jwtFailure != null) throw jwtFailure;
      throw new OBException("Invalid or expired token");
    }

    String userId = identity.get(OAuth2Filter.ATTR_USER_ID);
    String roleId = identity.get(OAuth2Filter.ATTR_ROLE_ID);
    String orgId = identity.get(OAuth2Filter.ATTR_ORG_ID);
    String clientId = identity.get(OAuth2Filter.ATTR_CLIENT_ID);
    String scopes = identity.get(OAuth2Filter.ATTR_SCOPES);
    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)
        || !hasRequiredScope(request.getMethod(), scopes)) {
      throw new OBException("Insufficient scope or invalid token context");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, null, clientId);
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
    applyRequestLanguage(request);
  }

  private boolean hasRequiredScope(String method, String scopes) {
    if (scopes == null) return false;
    java.util.Set<String> granted = new java.util.HashSet<>(java.util.Arrays.asList(scopes.split("\\s+")));
    if (granted.contains("neo:*")) return true;
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      return granted.contains("neo:read");
    }
    return granted.contains("neo:write");
  }

  /**
   * Apply the UI language requested via the {@code Accept-Language} header to the
   * current {@link OBContext}, so server-side message translation (AD_Message)
   * matches the language the user selected in the frontend. Falls back silently
   * to the context default when the header is absent or not a valid active language.
   *
   * <p>Only Etendo language codes ({@code xx_YY}, e.g. {@code es_ES}) are honored;
   * browser-style values ({@code es-ES,es;q=0.9}) are ignored on purpose so the
   * explicit app locale wins and nothing else does.
   */
  private void applyRequestLanguage(HttpServletRequest request) {
    // Validation + AD_Language lookup live in the shared NeoLanguage helper so the
    // same "GO locale of the request" is used by selectors (ETP-4304) and messages
    // (ETP-4306). Only well-formed, active xx_YY codes are honored; otherwise the
    // context default stands.
    NeoLanguage.applyToContext(request.getHeader("Accept-Language"));
  }

  boolean hasWindowAccess(String windowId) {
    return NeoServletSupport.hasWindowAccess(windowId);
  }

  boolean hasWindowAccess(String windowId, String httpMethod) {
    return NeoServletSupport.hasWindowAccess(windowId, httpMethod);
  }

  boolean hasWindowAccessForSpec(SFSpec spec, String httpMethod) {
    return NeoServletSupport.hasWindowAccessForSpec(spec, httpMethod);
  }

  boolean hasReportSpecAccess(SFSpec spec, String httpMethod) {
    return NeoServletSupport.hasReportSpecAccess(spec, httpMethod);
  }

  boolean hasProcessAccess(String processId) {
    return NeoServletSupport.hasProcessAccess(processId);
  }
}
