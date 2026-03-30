package com.etendoerp.go.oauth2;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Servlet filter that validates OAuth2 Bearer tokens on requests to /sws/mcp.
 * <p>
 * Extracts the Bearer token from the Authorization header, hashes it with SHA-256,
 * and looks up the hash in the ETGO_OAUTH2_TOKEN table via a JDBC join to
 * ETGO_OAUTH2_CLIENT. Validates that the token is not revoked, not expired,
 * and that the associated client is active.
 * <p>
 * On success, sets request attributes (oauth2.userId, oauth2.roleId, etc.)
 * for downstream servlets (McpServlet) to consume.
 * <p>
 * This filter does NOT set OBContext — that is handled per tool-call in McpServlet.
 */
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

    String authHeader = httpReq.getHeader(AUTH_HEADER);

    // Check Authorization header presence and format
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
          "invalid_request", "Missing or malformed Authorization header. Expected: Bearer <token>");
      return;
    }

    String bearerToken = authHeader.substring(BEARER_PREFIX.length()).trim();
    if (bearerToken.isEmpty()) {
      sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
          "invalid_request", "Bearer token is empty");
      return;
    }

    // Hash token for DB lookup
    String tokenHash = OAuth2Utils.hashToken(bearerToken);

    try (Connection conn = new DalConnectionProvider(false).getConnection();
         PreparedStatement ps = conn.prepareStatement(TOKEN_LOOKUP_SQL)) {

      ps.setString(1, tokenHash);

      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          log.debug("No token found for hash (first 8 chars): {}...", tokenHash.substring(0, 8));
          sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
              "invalid_token", "Token not recognized");
          return;
        }

        // Check revocation
        String isRevoked = rs.getString("is_revoked");
        if ("Y".equals(isRevoked)) {
          log.debug("Token is revoked: {}", rs.getString("etgo_oauth2_token_id"));
          sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
              "invalid_token", "Token has been revoked");
          return;
        }

        // Check expiration
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        if (expiresAt != null && OAuth2Utils.isTokenExpired(expiresAt)) {
          log.debug("Token expired at: {}", expiresAt);
          sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
              "invalid_token", "Token expired");
          return;
        }

        // Check client active status
        String clientActive = rs.getString("client_active");
        if (!"Y".equals(clientActive)) {
          log.debug("OAuth2 client is inactive for token: {}", rs.getString("etgo_oauth2_token_id"));
          sendError(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
              "invalid_token", "OAuth2 client is inactive");
          return;
        }

        // All checks passed — extract attributes
        String userId = rs.getString("ad_user_id");
        String roleId = rs.getString("ad_role_id");
        String etendoClientId = rs.getString("etendo_client_id");
        String tokenScopes = rs.getString("token_scopes");
        String clientScopes = rs.getString("client_scopes");

        // Use token-level scopes if present, otherwise fall back to client-level scopes
        String effectiveScopes = (tokenScopes != null && !tokenScopes.isEmpty())
            ? tokenScopes
            : clientScopes;

        // Set request attributes for McpServlet
        httpReq.setAttribute(ATTR_USER_ID, userId);
        httpReq.setAttribute(ATTR_ROLE_ID, roleId);
        httpReq.setAttribute(ATTR_CLIENT_ID, etendoClientId);
        httpReq.setAttribute(ATTR_ORG_ID, DEFAULT_ORG_ID);
        httpReq.setAttribute(ATTR_SCOPES, effectiveScopes);

        log.debug("OAuth2 token validated. userId={}, roleId={}, scopes={}",
            userId, roleId, effectiveScopes);

        // Continue to the servlet
        chain.doFilter(request, response);
      }
    } catch (SQLException e) {
      log.error("Database error during OAuth2 token validation", e);
      sendError(httpResp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "server_error", "Internal server error during token validation");
    }
  }

  @Override
  public void destroy() {
    log.info("OAuth2Filter destroyed");
  }

  /**
   * Send a JSON error response following OAuth2 error format.
   */
  private void sendError(HttpServletResponse response, int statusCode,
      String error, String errorDescription) throws IOException {
    response.setStatus(statusCode);
    response.setContentType(CONTENT_TYPE_JSON);
    // OAuth2 spec: WWW-Authenticate header on 401
    if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
      response.setHeader("WWW-Authenticate", "Bearer error=\"" + error + "\"");
    }
    try (PrintWriter writer = response.getWriter()) {
      writer.write("{\"error\":\"" + escapeJson(error)
          + "\",\"error_description\":\"" + escapeJson(errorDescription) + "\"}");
      writer.flush();
    }
  }

  /**
   * Minimal JSON string escaping for error messages.
   */
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

  // ── Static token validation (callable without filter registration) ────

  /**
   * Validate a Bearer token and return the resolved identity, or null if invalid.
   * This allows McpServlet to validate tokens directly when the filter is not
   * registered in the servlet container.
   *
   * @param bearerToken the raw access token (not hashed)
   * @return resolved identity map with keys matching ATTR_* constants, or null if invalid
   */
  public static java.util.Map<String, String> validateToken(String bearerToken) {
    if (bearerToken == null || bearerToken.isEmpty()) {
      return null;
    }

    String tokenHash = OAuth2Utils.hashToken(bearerToken);

    try (Connection conn = new DalConnectionProvider(false).getConnection();
         PreparedStatement ps = conn.prepareStatement(TOKEN_LOOKUP_SQL)) {

      ps.setString(1, tokenHash);

      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }

        if ("Y".equals(rs.getString("is_revoked"))) {
          return null;
        }

        Timestamp expiresAt = rs.getTimestamp("expires_at");
        if (expiresAt != null && OAuth2Utils.isTokenExpired(expiresAt)) {
          return null;
        }

        if (!"Y".equals(rs.getString("client_active"))) {
          return null;
        }

        String tokenScopes = rs.getString("token_scopes");
        String clientScopes = rs.getString("client_scopes");
        String effectiveScopes = (tokenScopes != null && !tokenScopes.isEmpty())
            ? tokenScopes
            : clientScopes;

        java.util.Map<String, String> identity = new java.util.HashMap<>();
        identity.put(ATTR_USER_ID, rs.getString("ad_user_id"));
        identity.put(ATTR_ROLE_ID, rs.getString("ad_role_id"));
        identity.put(ATTR_CLIENT_ID, rs.getString("etendo_client_id"));
        identity.put(ATTR_ORG_ID, DEFAULT_ORG_ID);
        identity.put(ATTR_SCOPES, effectiveScopes);
        return identity;
      }
    } catch (SQLException e) {
      log.error("Database error during static token validation", e);
      return null;
    }
  }
}
