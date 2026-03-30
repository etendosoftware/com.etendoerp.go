package com.etendoerp.go.oauth2;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.service.OBDal;

/**
 * OAuth2 token endpoint servlet.
 *
 * Mapped to /sws/oauth2/* — handles the Client Credentials grant (RFC 6749 Section 4.4).
 * Clients authenticate with client_id + client_secret and receive an opaque access token.
 *
 * The access token can then be used with NeoServlet endpoints via Bearer authentication.
 *
 * Database access uses raw JDBC since DAL model classes for ETGO_OAUTH2_* tables
 * are not yet generated.
 */
public class OAuth2Servlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(OAuth2Servlet.class);

  private static final int TOKEN_EXPIRY_SECONDS = 3600;
  private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";

  private static final Set<String> VALID_SCOPES = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("neo:read", "neo:write", "neo:process", "neo:report", "neo:*"))
  );

  private static final String WILDCARD_SCOPE = "neo:*";

  private static final String SQL_FIND_CLIENT =
      "SELECT etgo_oauth2_client_id, client_secret_hash, scopes, ad_client_id, ad_user_id, ad_role_id "
      + "FROM etgo_oauth2_client WHERE client_identifier = ? AND isactive = 'Y'";

  private static final String SQL_INSERT_TOKEN =
      "INSERT INTO etgo_oauth2_token "
      + "(etgo_oauth2_token_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "etgo_oauth2_client_id, access_token_hash, scopes, expires_at, is_revoked) "
      + "VALUES (get_uuid(), ?, '0', 'Y', now(), ?, now(), ?, ?, ?, ?, ?, 'N')";

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String pathInfo = request.getPathInfo();
    if (pathInfo == null || "/token".equals(pathInfo) || "/token/".equals(pathInfo)) {
      handleTokenRequest(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "invalid_request",
          "Unknown endpoint: " + pathInfo);
    }
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    writeError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "invalid_request",
        "Only POST is supported on the token endpoint");
  }

  /**
   * Handle the POST /sws/oauth2/token request.
   * Supports both application/x-www-form-urlencoded and application/json request bodies.
   */
  private void handleTokenRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      // Parse parameters from form-encoded or JSON body
      String grantType;
      String clientId;
      String clientSecret;
      String scopeParam;

      String contentType = request.getContentType();
      if (contentType != null && contentType.contains("application/json")) {
        JSONObject body = parseJsonBody(request);
        grantType = body.optString("grant_type", null);
        clientId = body.optString("client_id", null);
        clientSecret = body.optString("client_secret", null);
        scopeParam = body.optString("scope", null);
      } else {
        // Default: form-encoded
        grantType = request.getParameter("grant_type");
        clientId = request.getParameter("client_id");
        clientSecret = request.getParameter("client_secret");
        scopeParam = request.getParameter("scope");
      }

      // Validate grant_type
      if (!GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "unsupported_grant_type",
            "Only client_credentials grant type is supported");
        return;
      }

      // Validate required fields
      if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "client_id and client_secret are required");
        return;
      }

      // Look up client in database
      ClientRecord client = findClient(clientId);
      if (client == null) {
        log.warn("OAuth2 token request for unknown client_id: {}", clientId);
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_client",
            "Client authentication failed");
        return;
      }

      // Verify secret
      if (!OAuth2Utils.verifySecret(clientSecret, client.secretHash)) {
        log.warn("OAuth2 token request with invalid secret for client_id: {}", clientId);
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_client",
            "Client authentication failed");
        return;
      }

      // Parse and validate scopes
      Set<String> requestedScopes = parseScopes(scopeParam);
      Set<String> allowedScopes = parseScopes(client.scopes);

      if (!requestedScopes.isEmpty() && !isScopeAllowed(requestedScopes, allowedScopes)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_scope",
            "Requested scope exceeds client permissions");
        return;
      }

      // Use client's full scopes if none requested
      Set<String> grantedScopes = requestedScopes.isEmpty() ? allowedScopes : requestedScopes;
      String grantedScopeStr = String.join(" ", grantedScopes);

      // Generate token and store hash
      String accessToken = OAuth2Utils.generateSecureToken();
      String tokenHash = OAuth2Utils.hashToken(accessToken);
      Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000L));

      storeToken(client, tokenHash, grantedScopeStr, expiresAt);

      // Build RFC 6749 response
      JSONObject result = new JSONObject();
      result.put("access_token", accessToken);
      result.put("token_type", "bearer");
      result.put("expires_in", TOKEN_EXPIRY_SECONDS);
      result.put("scope", grantedScopeStr);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 token issued for client: {}", clientId);

    } catch (JSONException e) {
      log.error("Failed to build OAuth2 response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    } catch (SQLException e) {
      log.error("Database error during OAuth2 token request", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  /**
   * Look up an active OAuth2 client by client_identifier using raw JDBC.
   *
   * @param clientIdentifier the client_id from the token request
   * @return the client record, or null if not found
   */
  private ClientRecord findClient(String clientIdentifier) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_CLIENT)) {
      ps.setString(1, clientIdentifier);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        ClientRecord client = new ClientRecord();
        client.id = rs.getString("etgo_oauth2_client_id");
        client.secretHash = rs.getString("client_secret_hash");
        client.scopes = rs.getString("scopes");
        client.adClientId = rs.getString("ad_client_id");
        client.adUserId = rs.getString("ad_user_id");
        client.adRoleId = rs.getString("ad_role_id");
        return client;
      }
    }
  }

  /**
   * Store the hashed access token in ETGO_OAUTH2_TOKEN.
   */
  private void storeToken(ClientRecord client, String tokenHash, String scopes,
      Timestamp expiresAt) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_TOKEN)) {
      ps.setString(1, client.adClientId);    // ad_client_id
      ps.setString(2, client.adUserId);      // createdby
      ps.setString(3, client.adUserId);      // updatedby
      ps.setString(4, client.id);            // etgo_oauth2_client_id
      ps.setString(5, tokenHash);            // access_token_hash
      ps.setString(6, scopes);               // scopes
      ps.setTimestamp(7, expiresAt);          // expires_at
      ps.executeUpdate();
    }
    OBDal.getInstance().flush();
  }

  /**
   * Parse a space-separated scope string into a set.
   */
  private Set<String> parseScopes(String scopeStr) {
    if (scopeStr == null || scopeStr.trim().isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> scopes = new HashSet<>(Arrays.asList(scopeStr.trim().split("\\s+")));
    scopes.retainAll(VALID_SCOPES);
    return scopes;
  }

  /**
   * Check that all requested scopes are allowed by the client's configured scopes.
   * The wildcard scope "neo:*" grants access to all scopes.
   */
  private boolean isScopeAllowed(Set<String> requested, Set<String> allowed) {
    if (allowed.contains(WILDCARD_SCOPE)) {
      return true;
    }
    return allowed.containsAll(requested);
  }

  /**
   * Parse the request body as JSON.
   */
  private JSONObject parseJsonBody(HttpServletRequest request) throws IOException, JSONException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    return new JSONObject(sb.toString());
  }

  /**
   * Write a JSON response with the given HTTP status code.
   */
  private void writeJsonResponse(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    response.getWriter().write(body.toString());
  }

  /**
   * Write an RFC 6749 error response.
   */
  private void writeError(HttpServletResponse response, int status, String error,
      String description) throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", error);
      body.put("error_description", description);
      writeJsonResponse(response, status, body);
    } catch (JSONException e) {
      log.error("Failed to build error response", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Internal holder for client data loaded from ETGO_OAUTH2_CLIENT via JDBC.
   */
  private static class ClientRecord {
    String id;
    String secretHash;
    String scopes;
    String adClientId;
    String adUserId;
    String adRoleId;
  }
}
