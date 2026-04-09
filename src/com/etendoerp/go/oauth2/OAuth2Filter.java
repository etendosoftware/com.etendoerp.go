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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * Servlet filter that validates OAuth2 Bearer tokens on requests to /sws/mcp.
 * <p>
 * Uses Hibernate session's JDBC connection via {@code doReturningWork()} to query
 * the token table. This shares the same connection that DalRequestFilter established,
 * avoiding PooledConnection corruption from opening/close separate JDBC connections.
 */
@WebFilter(urlPatterns = "/sws/mcp")
public class OAuth2Filter implements Filter {

  private static final Logger log = LogManager.getLogger(OAuth2Filter.class);

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String AUTH_HEADER = "Authorization";
  private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

  private static final String TOKEN_LOOKUP_SQL =
      "SELECT t.etgo_oauth2_token_id, t.scopes AS token_scopes, t.expires_at, t.is_revoked, "
          + "c.ad_user_id, c.ad_role_id, c.scopes AS client_scopes, c.isactive AS client_active, "
          + "c.ad_client_id AS etendo_client_id "
          + "FROM etgo_oauth2_token t "
          + "JOIN etgo_oauth2_client c ON t.etgo_oauth2_client_id = c.etgo_oauth2_client_id "
          + "WHERE t.access_token_hash = ?";

  // Request attribute keys for downstream consumption
  public static final String ATTR_USER_ID = "oauth2.userId";
  public static final String ATTR_ROLE_ID = "oauth2.roleId";
  public static final String ATTR_CLIENT_ID = "oauth2.clientId";
  public static final String ATTR_ORG_ID = "oauth2.orgId";
  public static final String ATTR_SCOPES = "oauth2.scopes";

  private static final String DEFAULT_ORG_ID = "0";

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

      httpReq.setAttribute(ATTR_USER_ID, tokenInfo.userId);
      httpReq.setAttribute(ATTR_ROLE_ID, tokenInfo.roleId);
      httpReq.setAttribute(ATTR_CLIENT_ID, tokenInfo.clientId);
      httpReq.setAttribute(ATTR_ORG_ID, DEFAULT_ORG_ID);
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

  @Override
  public void destroy() {
    log.info("OAuth2Filter destroyed");
  }

  /**
   * Look up a token hash using the Hibernate session's JDBC connection.
   * Does NOT open or close any connection — uses the one DalRequestFilter established.
   */
  static TokenInfo lookupToken(String tokenHash) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(TOKEN_LOOKUP_SQL)) {
        ps.setString(1, tokenHash);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return null;
          }

          String isRevoked = rs.getString("is_revoked");
          if ("Y".equals(isRevoked)) {
            log.debug("Token is revoked: {}", rs.getString("etgo_oauth2_token_id"));
            return TokenInfo.error("Token has been revoked");
          }

          Timestamp expiresAt = rs.getTimestamp("expires_at");
          if (expiresAt != null && OAuth2Utils.isTokenExpired(expiresAt)) {
            log.debug("Token expired at: {}", expiresAt);
            return TokenInfo.error("Token expired");
          }

          String clientActive = rs.getString("client_active");
          if (!"Y".equals(clientActive)) {
            return TokenInfo.error("OAuth2 client is inactive");
          }

          String tokenScopes = rs.getString("token_scopes");
          String clientScopes = rs.getString("client_scopes");
          String effectiveScopes = (tokenScopes != null && !tokenScopes.isEmpty())
              ? tokenScopes
              : clientScopes;

          return new TokenInfo(
              rs.getString("ad_user_id"),
              rs.getString("ad_role_id"),
              rs.getString("etendo_client_id"),
              effectiveScopes);
        }
      }
    });
  }

  /**
   * Validate a Bearer token and return the resolved identity, or null if invalid.
   * Uses Hibernate session's JDBC connection (no separate pool connection).
    *
    * @param bearerToken the raw OAuth2 bearer token received from the request
    * @return a map with the resolved identity attributes, or null when the token is invalid
   */
  public static java.util.Map<String, String> validateToken(String bearerToken) {
    if (bearerToken == null || bearerToken.isEmpty()) {
      return null;
    }

    String tokenHash = OAuth2Utils.hashToken(bearerToken);

    try {
      TokenInfo info = lookupToken(tokenHash);
      if (info == null || info.errorCode != null) {
        return null;
      }

      java.util.Map<String, String> identity = new java.util.HashMap<>();
      identity.put(ATTR_USER_ID, info.userId);
      identity.put(ATTR_ROLE_ID, info.roleId);
      identity.put(ATTR_CLIENT_ID, info.clientId);
      identity.put(ATTR_ORG_ID, DEFAULT_ORG_ID);
      identity.put(ATTR_SCOPES, info.scopes);
      return identity;
    } catch (Exception e) {
      log.error("Error during static token validation", e);
      return null;
    }
  }

  private void sendError(HttpServletRequest request, HttpServletResponse response, int statusCode,
      String error, String errorDescription) throws IOException {
    response.setStatus(statusCode);
    response.setContentType(CONTENT_TYPE_JSON);
    if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
      String metaUrl = buildResourceMetadataUrl(request);
      response.setHeader("WWW-Authenticate",
          "Bearer error=\"" + error + "\","
          + " resource_metadata=\"" + metaUrl + "\"");
    }
    try (PrintWriter writer = response.getWriter()) {
      writer.write("{\"error\":\"" + escapeJson(error)
          + "\",\"error_description\":\"" + escapeJson(errorDescription) + "\"}");
      writer.flush();
    }
  }

  private String buildResourceMetadataUrl(HttpServletRequest request) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    String contextPath = request.getContextPath();
    boolean defaultPort = ("http".equals(scheme) && port == 80)
        || ("https".equals(scheme) && port == 443);
    return scheme + "://" + host + (defaultPort ? "" : ":" + port)
        + contextPath + "/sws/mcp/.well-known/oauth-protected-resource";
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
    final String scopes;
    final String errorCode;
    final String errorDesc;

    TokenInfo(String userId, String roleId, String clientId, String scopes) {
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.scopes = scopes;
      this.errorCode = null;
      this.errorDesc = null;
    }

    private TokenInfo(String errorCode, String errorDesc) {
      this.userId = null;
      this.roleId = null;
      this.clientId = null;
      this.scopes = null;
      this.errorCode = errorCode;
      this.errorDesc = errorDesc;
    }

    static TokenInfo error(String desc) {
      return new TokenInfo("invalid_token", desc);
    }
  }
}
