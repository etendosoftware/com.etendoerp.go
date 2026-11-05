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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.oauth2;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.session.GoSessionSecurity;

/**
 * Servlet filter that validates OAuth2 Bearer tokens on requests to the MCP endpoints.
 * <p>
 * Uses Hibernate session's JDBC connection via {@code doReturningWork()} to query
 * the token table. This shares the same connection that DalRequestFilter established,
 * avoiding PooledConnection corruption from opening/close separate JDBC connections.
 */
@WebFilter(urlPatterns = { "/sws/mcp", "/mcp" })
public class OAuth2Filter implements Filter {

  private static final Logger log = LogManager.getLogger(OAuth2Filter.class);

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String AUTH_HEADER = "Authorization";
  private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

  private static final String TOKEN_LOOKUP_SQL =
      "SELECT t.etgo_oauth2_token_id, t.scopes AS token_scopes, t.expires_at, t.is_revoked, "
          + "c.ad_user_id, c.ad_role_id, c.scopes AS client_scopes, c.isactive AS client_active, "
          + "COALESCE(o.ad_client_id, c.ad_client_id) AS etendo_client_id, "
          + "t.ad_org_id AS etendo_org_id, "
          // ETP-6057: the key that owns the token and its role's tenant, for usage attribution.
          + "c.client_identifier, r.ad_client_id AS role_client_id "
          + "FROM etgo_oauth2_token t "
          + "JOIN etgo_oauth2_client c ON t.etgo_oauth2_client_id = c.etgo_oauth2_client_id "
          + "LEFT JOIN ad_org o ON t.ad_org_id = o.ad_org_id "
          + "LEFT JOIN ad_role r ON c.ad_role_id = r.ad_role_id "
          + "WHERE t.access_token_hash = ?";

  /** ETP-6057 — why the MCP refuses a public API key's token. */
  public static final String MSG_PUBLIC_KEY_NOT_FOR_MCP =
      "Public API keys are accepted by the public API only; connect MCP clients with their own "
          + "OAuth client";

  // Request attribute keys for downstream consumption
  public static final String ATTR_USER_ID = "oauth2.userId";
  public static final String ATTR_ROLE_ID = "oauth2.roleId";
  public static final String ATTR_CLIENT_ID = "oauth2.clientId";
  public static final String ATTR_ORG_ID = "oauth2.orgId";
  public static final String ATTR_SCOPES = "oauth2.scopes";
  /** ETP-6057 — the public {@code client_identifier} of the key that owns the token. */
  public static final String ATTR_KEY_ID = "oauth2.keyId";
  /** ETP-6057 — the client of the key's role, the tenant when the token's org is {@code *}. */
  public static final String ATTR_ROLE_CLIENT_ID = "oauth2.roleClientId";
  /**
   * ETP-6057 — {@code "true"} when the token belongs to a user-owned public API key. Decided on the
   * KEY's stored scopes, which carry {@code neo:public-api-key}: a token's own scopes never do,
   * because the token exchange keeps only the grantable scopes.
   */
  public static final String ATTR_PUBLIC_API_KEY = "oauth2.publicApiKey";

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    log.info("OAuth2Filter initialized for MCP endpoint");
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) request;
    HttpServletResponse httpResp = (HttpServletResponse) response;

    // Allow CORS preflight and GET (discovery) requests through without authentication
    String method = httpReq.getMethod();
    if ("OPTIONS".equalsIgnoreCase(method) || "GET".equalsIgnoreCase(method)) {
      chain.doFilter(request, response);
      return;
    }

    String authHeader = httpReq.getHeader(AUTH_HEADER);

    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      if (hasGoSessionCookie(httpReq)) {
        // Since ETP-4576 the browser SPA authenticates with the `__Host-` cookie session and
        // sends no Authorization header at all, so rejecting here cut off every in-app
        // conversation before the servlet could resolve the cookie. This filter validates
        // OAuth2 tokens and has none to validate, so it defers: the servlet resolves the
        // session and answers 401/403 itself.
        //
        // Gated on the cookie deliberately. A request carrying neither credential still gets
        // this filter's own 401, byte for byte — including the `error="invalid_request"` field
        // of `WWW-Authenticate`, which is what an MCP client's OAuth discovery reads.
        chain.doFilter(request, response);
        return;
      }
      sendError(httpReq, httpResp, HttpServletResponse.SC_UNAUTHORIZED,
          "invalid_request", "Missing or malformed Authorization header. Expected: Bearer <token>");
      return;
    }

    String bearerToken = authHeader.substring(BEARER_PREFIX.length()).trim();
    if (bearerToken.isEmpty()) {
      sendError(httpReq, httpResp, HttpServletResponse.SC_UNAUTHORIZED,
          "invalid_request", "Bearer token is empty");
      return;
    }

    String tokenHash = OAuth2Utils.hashToken(bearerToken);

    try {
      TokenInfo tokenInfo = lookupToken(tokenHash);

      if (tokenInfo == null) {
        // Not an OAuth2 token — pass through to servlet for JWT fallback
        log.debug("Token not found in OAuth2 store, passing to servlet for JWT fallback");
        chain.doFilter(request, response);
        return;
      }

      if (tokenInfo.errorCode != null) {
        sendError(httpReq, httpResp, HttpServletResponse.SC_UNAUTHORIZED,
            "invalid_token", tokenInfo.errorDesc);
        return;
      }

      if (tokenInfo.publicApiKey) {
        // ETP-6057 — a public API key is for the public API only, where it is counted and limited.
        // 403, not 401: the token is valid, it is just not one the MCP accepts.
        sendError(httpReq, httpResp, HttpServletResponse.SC_FORBIDDEN,
            "insufficient_scope", MSG_PUBLIC_KEY_NOT_FOR_MCP);
        return;
      }

      httpReq.setAttribute(ATTR_USER_ID, tokenInfo.userId);
      httpReq.setAttribute(ATTR_ROLE_ID, tokenInfo.roleId);
      httpReq.setAttribute(ATTR_CLIENT_ID, tokenInfo.clientId);
      httpReq.setAttribute(ATTR_ORG_ID, tokenInfo.orgId);
      httpReq.setAttribute(ATTR_SCOPES, tokenInfo.scopes);

      log.debug("OAuth2 token validated. userId={}, roleId={}, scopes={}",
          tokenInfo.userId, tokenInfo.roleId, tokenInfo.scopes);

      chain.doFilter(request, response);

    } catch (Exception e) {
      log.error("Error during OAuth2 token validation", e);
      sendError(httpReq, httpResp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "server_error", "Internal server error during token validation");
    }
  }

  /**
   * Whether the request carries an Etendo Go session cookie, i.e. whether deferring to the
   * servlet can plausibly resolve a credential this filter cannot see.
   */
  private static boolean hasGoSessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return false;
    }
    for (Cookie cookie : cookies) {
      if (GoSessionSecurity.COOKIE_NAME.equals(cookie.getName())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void destroy() {
    log.info("OAuth2Filter destroyed");
  }

  /**
   * Look up a token hash using the Hibernate session's JDBC connection.
   * Does NOT open or close any connection — uses the one DalRequestFilter established.
   *
   * <p>A refused token (revoked, expired, inactive client) answers an error {@link TokenInfo}
   * that still carries, in {@link TokenInfo#refusedIdentity}, who the token belonged to: the
   * refusal itself is decided exactly as before, the identity is only for usage attribution
   * (ETP-6057).</p>
   */
  static TokenInfo lookupToken(String tokenHash) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(TOKEN_LOOKUP_SQL)) {
        ps.setString(1, tokenHash);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return null;
          }
          TokenInfo identity = identityOf(rs);
          String refusal = refusalOf(rs);
          if (refusal == null) {
            return identity;
          }
          TokenInfo error = TokenInfo.error(refusal);
          error.refusedIdentity = identity;
          return error;
        }
      }
    });
  }

  private static TokenInfo identityOf(ResultSet rs) throws java.sql.SQLException {
    String tokenScopes = rs.getString("token_scopes");
    String clientScopes = rs.getString("client_scopes");
    String effectiveScopes = (tokenScopes != null && !tokenScopes.isEmpty())
        ? tokenScopes
        : clientScopes;
    TokenInfo identity = new TokenInfo(
        rs.getString("ad_user_id"),
        rs.getString("ad_role_id"),
        rs.getString("etendo_client_id"),
        rs.getString("etendo_org_id"),
        effectiveScopes);
    identity.keyId = rs.getString("client_identifier");
    identity.roleClientId = rs.getString("role_client_id");
    identity.publicApiKey = PublicApiKeyPolicy.isPublicApiKey(clientScopes);
    return identity;
  }

  /** @return why the token is refused, or null when it is usable; same order as ever */
  private static String refusalOf(ResultSet rs) throws java.sql.SQLException {
    if ("Y".equals(rs.getString("is_revoked"))) {
      log.debug("Token is revoked: {}", rs.getString("etgo_oauth2_token_id"));
      return "Token has been revoked";
    }
    Timestamp expiresAt = rs.getTimestamp("expires_at");
    if (expiresAt != null && OAuth2Utils.isTokenExpired(expiresAt)) {
      log.debug("Token expired at: {}", expiresAt);
      return "Token expired";
    }
    if (!"Y".equals(rs.getString("client_active"))) {
      return "OAuth2 client is inactive";
    }
    return null;
  }

  /**
   * ETP-6057 — the outcome of one token lookup, for a caller that must both authenticate the token
   * and, when it is refused, attribute the refusal to the key that owned it. One query for both.
   */
  public static final class TokenCheck {
    private static final TokenCheck NONE = new TokenCheck(null, null);

    private final java.util.Map<String, String> identity;
    private final java.util.Map<String, String> refusedIdentity;

    private TokenCheck(java.util.Map<String, String> identity,
        java.util.Map<String, String> refusedIdentity) {
      this.identity = identity;
      this.refusedIdentity = refusedIdentity;
    }

    /**
     * Build a check, for a caller (or a test) that already knows the outcome.
     *
     * @param identity        the identity of a usable token, or null
     * @param refusedIdentity who a refused token belonged to, or null
     * @return the check
     */
    public static TokenCheck of(java.util.Map<String, String> identity,
        java.util.Map<String, String> refusedIdentity) {
      return new TokenCheck(identity, refusedIdentity);
    }

    /** @return the identity attributes of a usable token, or null when it is not usable */
    public java.util.Map<String, String> identity() {
      return identity;
    }

    /**
     * Who a refused token (revoked, expired, inactive key) belonged to — usage attribution only,
     * never authentication.
     *
     * @return its identity attributes, or null when the token is usable, unknown or unreadable
     */
    public java.util.Map<String, String> refusedIdentity() {
      return refusedIdentity;
    }
  }

  /**
   * Look a Bearer token up once and say whether it is usable and, if not, whose it was.
   * {@link #validateToken} is this check's {@link TokenCheck#identity()}.
   *
   * @param bearerToken the raw OAuth2 bearer token received from the request
   * @return the check; never null (an unknown token, or a lookup failure, has neither identity)
   */
  public static TokenCheck checkToken(String bearerToken) {
    if (bearerToken == null || bearerToken.isEmpty()) {
      return TokenCheck.NONE;
    }
    try {
      TokenInfo info = lookupToken(OAuth2Utils.hashToken(bearerToken));
      if (info == null) {
        return TokenCheck.NONE;
      }
      if (info.errorCode == null) {
        return TokenCheck.of(identityMap(info), null);
      }
      return TokenCheck.of(null,
          info.refusedIdentity == null ? null : identityMap(info.refusedIdentity));
    } catch (Exception e) {
      log.error("Error during static token validation", e);
      return TokenCheck.NONE;
    }
  }

  /**
   * Validate a Bearer token and return the resolved identity, or null if invalid.
   * Uses Hibernate session's JDBC connection (no separate pool connection).
   *
   * @param bearerToken the raw OAuth2 bearer token received from the request
   * @return a map with the resolved identity attributes, or null when the token is invalid
   */
  public static java.util.Map<String, String> validateToken(String bearerToken) {
    return checkToken(bearerToken).identity();
  }

  private static java.util.Map<String, String> identityMap(TokenInfo info) {
    java.util.Map<String, String> identity = new java.util.HashMap<>();
    identity.put(ATTR_USER_ID, info.userId);
    identity.put(ATTR_ROLE_ID, info.roleId);
    identity.put(ATTR_CLIENT_ID, info.clientId);
    identity.put(ATTR_ORG_ID, info.orgId);
    identity.put(ATTR_SCOPES, info.scopes);
    identity.put(ATTR_KEY_ID, info.keyId);
    identity.put(ATTR_ROLE_CLIENT_ID, info.roleClientId);
    identity.put(ATTR_PUBLIC_API_KEY, String.valueOf(info.publicApiKey));
    return identity;
  }

  private void sendError(HttpServletRequest request, HttpServletResponse response, int statusCode,
      String error, String errorDescription) throws IOException {
    response.setStatus(statusCode);
    response.setContentType(CONTENT_TYPE_JSON);
    if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
      String metaUrl = buildResourceMetadataUrl(request);
      if (metaUrl != null) {
        response.setHeader("WWW-Authenticate",
            "Bearer error=\"" + error + "\","
            + " resource_metadata=\"" + metaUrl + "\"");
      }
    }
    try (PrintWriter writer = response.getWriter()) {
      writer.write("{\"error\":\"" + escapeJson(error)
          + "\",\"error_description\":\"" + escapeJson(errorDescription) + "\"}");
      writer.flush();
    }
  }

  private String buildResourceMetadataUrl(HttpServletRequest request) {
    return PublicUrlResolver.appendPath(PublicUrlResolver.resolveMcpResourceUrl(request),
        ".well-known/oauth-protected-resource");
  }

  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /**
   * Holds token validation result.
   */
  static class TokenInfo {
    final String userId;
    final String roleId;
    final String clientId;
    final String orgId;
    final String scopes;
    final String errorCode;
    final String errorDesc;
    /** ETP-6057 — the key's public client_identifier; null when not read. */
    String keyId;
    /** ETP-6057 — the client of the key's role; null when not read. */
    String roleClientId;
    /** ETP-6057 — whether the owning key is a user-owned public API key. */
    boolean publicApiKey;
    /** ETP-6057 — on an error, who the refused token belonged to; usage attribution only. */
    TokenInfo refusedIdentity;

    TokenInfo(String userId, String roleId, String clientId, String scopes) {
      this(userId, roleId, clientId, "0", scopes);
    }

    TokenInfo(String userId, String roleId, String clientId, String orgId, String scopes) {
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
      this.scopes = scopes;
      this.errorCode = null;
      this.errorDesc = null;
    }

    private TokenInfo(String errorCode, String errorDesc) {
      this.userId = null;
      this.roleId = null;
      this.clientId = null;
      this.orgId = null;
      this.scopes = null;
      this.errorCode = errorCode;
      this.errorDesc = errorDesc;
    }

    static TokenInfo error(String desc) {
      return new TokenInfo("invalid_token", desc);
    }
  }
}
