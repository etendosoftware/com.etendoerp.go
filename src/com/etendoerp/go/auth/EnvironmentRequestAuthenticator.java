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

package com.etendoerp.go.auth;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.EnvironmentAuthOutcome.Status;
import com.etendoerp.go.oauth2.OAuth2Filter;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoSessionAuthResult;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.JdbcGoSessionStore;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * The single authentication pipeline for environment-scoped surfaces (ETP-5455).
 *
 * <p>ADR-0001 added the cookie session next to the legacy Bearer flow, and every servlet grew its
 * own copy of "credential → {@link OBContext}". Cross-cutting rules were then added to whichever
 * copy their author had in front of them: commercial access on the NEO JWT branch only, warehouse
 * repair on the JWT branch only, the legacy kill switch in front of OAuth2 too, cookie support on
 * some servlets and not others. This class replaces those copies with two phases:
 *
 * <ol>
 *   <li><b>Resolve</b> the credential into one identity: the cookie session first; then, only when
 *   no cookie is present, a Bearer token — as a legacy JWT (gated by {@link GoLegacyBearer}) or,
 *   where the {@link SurfacePolicy} allows it, as an OAuth2 client-credentials token (NOT gated by
 *   the legacy switch: it is not the browser's credential).</li>
 *   <li><b>Bind</b> that identity, identically for every scheme: build the context, repair an
 *   unreadable warehouse, install it, refuse a commercially blocked environment when the policy
 *   says so, and apply the request language.</li>
 * </ol>
 *
 * <p>Status codes and messages are the ones the consumers answered before, so no client sees a
 * contract change — only the cases where the branches used to disagree now agree. (One wording
 * was unified: support used to name the missing claim; every surface now answers
 * {@value #MSG_MISSING_CLAIMS}, still with 401.)
 */
public class EnvironmentRequestAuthenticator {

  private static final Logger log = LogManager.getLogger(EnvironmentRequestAuthenticator.class);

  static final String MSG_CSRF_FAILED = "CSRF validation failed";
  static final String MSG_SESSION_INVALID = "Invalid or expired session";
  static final String MSG_NO_CREDENTIALS = "Missing or invalid Authorization header";
  static final String MSG_NO_ENVIRONMENT = "Session has no environment selected";
  static final String MSG_MISSING_CLAIMS = "Invalid token: missing required claims";
  static final String MSG_INVALID_TOKEN = "Invalid or expired token";
  static final String MSG_INSUFFICIENT_SCOPE = "Insufficient scope or invalid token context";
  static final String MSG_ACCESS_PREFIX = "Environment access is not available: ";

  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String SCOPE_ALL = "neo:*";
  private static final String SCOPE_READ = "neo:read";
  private static final String SCOPE_WRITE = "neo:write";

  private final GoSessionAuthenticator sessionAuthenticator;
  private final TenantEnvironmentLifecycleService lifecycleService;
  private final WarehouseResolver warehouseResolver;

  /** Production wiring: JDBC-backed sessions, the tenant lifecycle policy and the DAL. */
  public EnvironmentRequestAuthenticator() {
    this(new GoSessionAuthenticator(new GoSessionService(new JdbcGoSessionStore())),
        new TenantEnvironmentLifecycleService(), new DalWarehouseResolver());
  }

  /**
   * Wiring with explicit collaborators, for callers that already hold a session service and for
   * tests.
   *
   * @param sessionAuthenticator resolves the {@code __Host-go_session} cookie
   * @param lifecycleService     answers the commercial access decision for a client
   * @param warehouseResolver    repairs a context whose warehouse the role cannot read
   */
  public EnvironmentRequestAuthenticator(GoSessionAuthenticator sessionAuthenticator,
      TenantEnvironmentLifecycleService lifecycleService, WarehouseResolver warehouseResolver) {
    this.sessionAuthenticator = sessionAuthenticator;
    this.lifecycleService = lifecycleService;
    this.warehouseResolver = warehouseResolver;
  }

  /**
   * Authenticates the request and, on success, installs its {@link OBContext}.
   *
   * @param request the incoming request
   * @param policy  what the calling surface requires beyond a valid credential
   * @return the outcome; on refusal it carries the status and message to answer with
   */
  public EnvironmentAuthOutcome authenticate(HttpServletRequest request, SurfacePolicy policy) {
    Resolution resolution;
    try {
      resolution = resolve(request, policy);
    } catch (RuntimeException e) {
      return refusedFor(e, null);
    }
    if (resolution.refusal != null) {
      return resolution.refusal;
    }
    try {
      return bind(request, policy, resolution.identity);
    } catch (RuntimeException e) {
      return refusedFor(e, resolution.identity.scheme);
    }
  }

  /**
   * Resolves the credential exactly like {@link #authenticate} — same cookie, CSRF, kill switch,
   * claims and OAuth2 rules, same refusals — but installs no {@link OBContext}. For a surface that
   * builds its own per-operation context from the identity (support conversations) and so has no
   * use for the bind step.
   *
   * <p>Only for policies without a commercial-access requirement: that check lives in the bind
   * step, so resolving without binding under such a policy would skip it.
   *
   * @param request the incoming request
   * @param policy  a policy whose {@link SurfacePolicy#isCommercialAccessRequired()} is false
   * @return the outcome; when authenticated it carries the identity ids and no context
   * @throws IllegalArgumentException if the policy requires commercial access
   */
  public EnvironmentAuthOutcome identify(HttpServletRequest request, SurfacePolicy policy) {
    if (policy.isCommercialAccessRequired()) {
      throw new IllegalArgumentException(
          policy + " requires the commercial-access check; use authenticate()");
    }
    Resolution resolution;
    try {
      resolution = resolve(request, policy);
    } catch (RuntimeException e) {
      return refusedFor(e, null);
    }
    if (resolution.refusal != null) {
      return resolution.refusal;
    }
    Identity identity = resolution.identity;
    return EnvironmentAuthOutcome.authenticated(identity.scheme, null, identity.userId,
        identity.roleId, identity.clientId, identity.orgId);
  }

  // ------------------------------------------------------------------ phase 1: resolve

  private Resolution resolve(HttpServletRequest request, SurfacePolicy policy) {
    GoSessionAuthResult sessionAuth = sessionAuthenticator.authenticate(request);
    switch (sessionAuth.getStatus()) {
      case AUTHENTICATED:
        return fromSession(sessionAuth.getRecord());
      case CSRF_FAILED:
        return Resolution.refused(Status.CSRF_REJECTED, MSG_CSRF_FAILED, AuthScheme.COOKIE);
      case UNAUTHENTICATED:
        // A dead cookie is final: falling back to a Bearer sent alongside it would let a
        // revoked session keep working through whatever token the page still holds.
        return Resolution.refused(Status.UNAUTHENTICATED, MSG_SESSION_INVALID, AuthScheme.COOKIE);
      case NO_SESSION:
      default:
        return fromBearer(request, policy);
    }
  }

  private static Resolution fromSession(GoSessionRecord session) {
    if (StringUtils.isAnyBlank(session.getUserId(), session.getRoleId(), session.getCtxOrgId(),
        session.getCtxClientId())) {
      return Resolution.refused(Status.UNAUTHENTICATED, MSG_NO_ENVIRONMENT, AuthScheme.COOKIE);
    }
    return Resolution.of(new Identity(AuthScheme.COOKIE, session.getUserId(), session.getRoleId(),
        session.getCtxOrgId(), session.getWarehouseId(), session.getCtxClientId()));
  }

  private Resolution fromBearer(HttpServletRequest request, SurfacePolicy policy) {
    String header = request.getHeader(HEADER_AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Resolution.refused(Status.UNAUTHENTICATED, MSG_NO_CREDENTIALS, null);
    }
    String token = header.substring(BEARER_PREFIX.length());
    DecodedJWT jwt = decodeJwt(token);
    if (jwt != null) {
      return fromJwt(jwt);
    }
    if (policy.isOAuth2Allowed()) {
      return fromOAuth2(request, token);
    }
    return Resolution.refused(Status.UNAUTHENTICATED, MSG_INVALID_TOKEN, null);
  }

  /** The token as an Etendo JWT, or null when it is not one (an OAuth2 token, garbage). */
  private static DecodedJWT decodeJwt(String token) {
    try {
      return SecureWebServicesUtils.decodeToken(token);
    } catch (Exception notAJwt) {
      log.debug("Bearer token is not a valid Etendo JWT: {}", notAJwt.getMessage());
      return null;
    }
  }

  private static Resolution fromJwt(DecodedJWT jwt) {
    // The switch retires exactly this credential, and only this one is counted as a legacy
    // use: the counter is what says when turning the switch off is safe.
    if (!GoLegacyBearer.isEnabled()) {
      return Resolution.refused(Status.UNAUTHENTICATED, MSG_NO_CREDENTIALS, AuthScheme.JWT);
    }
    GoLegacyBearer.recordUse();
    Identity identity = new Identity(AuthScheme.JWT, claim(jwt, "user"), claim(jwt, "role"),
        claim(jwt, "organization"), claim(jwt, "warehouse"), claim(jwt, "client"));
    if (identity.isIncomplete()) {
      return Resolution.refused(Status.UNAUTHENTICATED, MSG_MISSING_CLAIMS, AuthScheme.JWT);
    }
    return Resolution.of(identity);
  }

  /** Null-safe: a claim the token does not carry reads as absent, never as an NPE. */
  private static String claim(DecodedJWT jwt, String name) {
    Claim value = jwt.getClaim(name);
    return value == null ? null : value.asString();
  }

  private static Resolution fromOAuth2(HttpServletRequest request, String token) {
    Map<String, String> validated = OAuth2Filter.validateToken(token);
    if (validated == null) {
      return Resolution.refused(Status.UNAUTHENTICATED, MSG_INVALID_TOKEN, AuthScheme.OAUTH2);
    }
    Identity identity = new Identity(AuthScheme.OAUTH2, validated.get(OAuth2Filter.ATTR_USER_ID),
        validated.get(OAuth2Filter.ATTR_ROLE_ID), validated.get(OAuth2Filter.ATTR_ORG_ID), null,
        validated.get(OAuth2Filter.ATTR_CLIENT_ID));
    if (identity.isIncomplete()
        || !hasRequiredScope(request.getMethod(), validated.get(OAuth2Filter.ATTR_SCOPES))) {
      return Resolution.refused(Status.UNAUTHENTICATED, MSG_INSUFFICIENT_SCOPE, AuthScheme.OAUTH2);
    }
    return Resolution.of(identity);
  }

  private static boolean hasRequiredScope(String method, String scopes) {
    if (scopes == null) {
      return false;
    }
    Set<String> granted = new HashSet<>(Arrays.asList(scopes.split("\\s+")));
    if (granted.contains(SCOPE_ALL)) {
      return true;
    }
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      return granted.contains(SCOPE_READ);
    }
    return granted.contains(SCOPE_WRITE);
  }

  // ------------------------------------------------------------------ phase 2: bind

  /** The one post-authentication step. Every scheme reaches it, and nothing here asks which. */
  private EnvironmentAuthOutcome bind(HttpServletRequest request, SurfacePolicy policy,
      Identity identity) {
    OBContext context = createContext(identity, identity.warehouseId);
    if (!isWarehouseReadable(context)) {
      log.warn("Warehouse '{}' is not in user '{}' readable orgs — resolving an accessible one",
          identity.warehouseId, identity.userId);
      context = createContext(identity, warehouseResolver.findAccessibleWarehouse(context));
    }
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);

    if (policy.isCommercialAccessRequired()) {
      EnvironmentAccessPolicy.Decision decision =
          lifecycleService.evaluateAccess(identity.clientId, true, Instant.now());
      // null is a tenant that predates lifecycle metadata: the controlled legacy transition,
      // not a refusal.
      if (decision != null && decision != EnvironmentAccessPolicy.Decision.ALLOWED) {
        return EnvironmentAuthOutcome.refused(Status.PAYMENT_REQUIRED,
            MSG_ACCESS_PREFIX + decision.name(), identity.scheme);
      }
    }
    NeoLanguage.applyToContext(request.getHeader(HEADER_ACCEPT_LANGUAGE));
    return EnvironmentAuthOutcome.authenticated(identity.scheme, context, identity.userId,
        identity.roleId, identity.clientId, identity.orgId);
  }

  private static OBContext createContext(Identity identity, String warehouseId) {
    return SecureWebServicesUtils.createContext(identity.userId, identity.roleId, identity.orgId,
        warehouseId, identity.clientId);
  }

  private static boolean isWarehouseReadable(OBContext context) {
    if (context.getWarehouse() == null) {
      return true;
    }
    String warehouseOrgId = context.getWarehouse().getOrganization().getId();
    return Arrays.asList(context.getReadableOrganizations()).contains(warehouseOrgId);
  }

  // ------------------------------------------------------------------ failures

  /**
   * Maps an unexpected failure to the answer the consumers already gave: an {@link OBException}
   * message is ours and safe to show; anything else (decode failures, NPEs) must not leak.
   */
  private static EnvironmentAuthOutcome refusedFor(RuntimeException e, AuthScheme scheme) {
    log.warn("Environment authentication failed: {}", e.getMessage());
    String message = e instanceof OBException ? e.getMessage() : MSG_INVALID_TOKEN;
    return EnvironmentAuthOutcome.refused(Status.UNAUTHENTICATED, message, scheme);
  }

  /** The credential's claims, whatever carried them. */
  private static final class Identity {
    private final AuthScheme scheme;
    private final String userId;
    private final String roleId;
    private final String orgId;
    private final String warehouseId;
    private final String clientId;

    private Identity(AuthScheme scheme, String userId, String roleId, String orgId,
        String warehouseId, String clientId) {
      this.scheme = scheme;
      this.userId = userId;
      this.roleId = roleId;
      this.orgId = orgId;
      this.warehouseId = warehouseId;
      this.clientId = clientId;
    }

    private boolean isIncomplete() {
      return StringUtils.isAnyBlank(userId, roleId, orgId, clientId);
    }
  }

  /** Phase-1 result: an identity to bind, or the refusal to answer with. */
  private static final class Resolution {
    private final Identity identity;
    private final EnvironmentAuthOutcome refusal;

    private Resolution(Identity identity, EnvironmentAuthOutcome refusal) {
      this.identity = identity;
      this.refusal = refusal;
    }

    private static Resolution of(Identity identity) {
      return new Resolution(identity, null);
    }

    private static Resolution refused(Status status, String message, AuthScheme scheme) {
      return new Resolution(null, EnvironmentAuthOutcome.refused(status, message, scheme));
    }
  }
}
