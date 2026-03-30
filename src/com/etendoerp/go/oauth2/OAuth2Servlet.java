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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.service.OBDal;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * OAuth2 servlet handling token issuance, client CRUD, revocation, and introspection.
 *
 * Mapped to /sws/oauth2/* — endpoints:
 *   POST /token          — Client Credentials grant (RFC 6749 Section 4.4)
 *   GET  /clients        — List all OAuth2 clients (admin only)
 *   POST /clients        — Create a new OAuth2 client (admin only)
 *   PUT  /clients/{id}   — Update an OAuth2 client (admin only)
 *   DELETE /clients/{id} — Delete an OAuth2 client and its tokens (admin only)
 *   PUT  /clients/{id}/regenerate-secret — Regenerate client secret (admin only)
 *   POST /revoke         — Revoke all tokens for a client (admin only)
 *   POST /introspect     — Token introspection (admin only)
 *
 * Database access uses raw JDBC since DAL model classes for ETGO_OAUTH2_* tables
 * are not yet generated.
 */
public class OAuth2Servlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(OAuth2Servlet.class);

  private static final int TOKEN_EXPIRY_SECONDS = 3600;
  private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
  private static final String ADMIN_ROLE_ID = "0";

  private static final Set<String> VALID_SCOPES = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("neo:read", "neo:write", "neo:process", "neo:report", "neo:*"))
  );

  private static final String WILDCARD_SCOPE = "neo:*";

  // --- SQL constants ---

  private static final String SQL_FIND_CLIENT =
      "SELECT etgo_oauth2_client_id, client_secret_hash, scopes, ad_client_id, ad_user_id, ad_role_id "
      + "FROM etgo_oauth2_client WHERE client_identifier = ? AND isactive = 'Y'";

  private static final String SQL_INSERT_TOKEN =
      "INSERT INTO etgo_oauth2_token "
      + "(etgo_oauth2_token_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "etgo_oauth2_client_id, access_token_hash, scopes, expires_at, is_revoked) "
      + "VALUES (get_uuid(), ?, '0', 'Y', now(), ?, now(), ?, ?, ?, ?, ?, 'N')";

  private static final String SQL_LIST_CLIENTS =
      "SELECT etgo_oauth2_client_id, name, client_identifier, ad_user_id, ad_role_id, scopes, isactive "
      + "FROM etgo_oauth2_client ORDER BY name";

  private static final String SQL_INSERT_CLIENT =
      "INSERT INTO etgo_oauth2_client "
      + "(etgo_oauth2_client_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "name, client_identifier, client_secret_hash, ad_user_id, ad_role_id, scopes, ad_module_id) "
      + "VALUES (get_uuid(), '0', '0', ?, now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, '0') "
      + "RETURNING etgo_oauth2_client_id";

  private static final String SQL_UPDATE_CLIENT =
      "UPDATE etgo_oauth2_client SET name = ?, scopes = ?, ad_user_id = ?, ad_role_id = ?, "
      + "isactive = ?, updated = now(), updatedby = ? "
      + "WHERE etgo_oauth2_client_id = ?";

  private static final String SQL_DELETE_TOKENS_BY_CLIENT =
      "DELETE FROM etgo_oauth2_token WHERE etgo_oauth2_client_id = ?";

  private static final String SQL_DELETE_CLIENT =
      "DELETE FROM etgo_oauth2_client WHERE etgo_oauth2_client_id = ?";

  private static final String SQL_REVOKE_TOKENS =
      "UPDATE etgo_oauth2_token SET is_revoked = 'Y', updated = now() "
      + "WHERE etgo_oauth2_client_id = "
      + "(SELECT etgo_oauth2_client_id FROM etgo_oauth2_client WHERE client_identifier = ?)";

  private static final String SQL_INTROSPECT_TOKEN =
      "SELECT t.scopes, t.expires_at, t.is_revoked, c.client_identifier "
      + "FROM etgo_oauth2_token t "
      + "JOIN etgo_oauth2_client c ON t.etgo_oauth2_client_id = c.etgo_oauth2_client_id "
      + "WHERE t.access_token_hash = ?";

  private static final String SQL_UPDATE_SECRET =
      "UPDATE etgo_oauth2_client SET client_secret_hash = ?, updated = now(), updatedby = ? "
      + "WHERE etgo_oauth2_client_id = ?";

  private static final String SQL_FIND_CLIENT_BY_ID =
      "SELECT etgo_oauth2_client_id, name, client_identifier, ad_user_id, ad_role_id, scopes, isactive "
      + "FROM etgo_oauth2_client WHERE etgo_oauth2_client_id = ?";

  // --- HTTP method dispatchers ---

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if ("/clients".equals(path) || "/clients/".equals(path)) {
      handleListClients(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "invalid_request",
          "Unknown endpoint: " + path);
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (path == null || "/token".equals(path) || "/token/".equals(path)) {
      handleTokenRequest(request, response);
    } else if ("/clients".equals(path) || "/clients/".equals(path)) {
      handleCreateClient(request, response);
    } else if ("/revoke".equals(path) || "/revoke/".equals(path)) {
      handleRevoke(request, response);
    } else if ("/introspect".equals(path) || "/introspect/".equals(path)) {
      handleIntrospect(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "invalid_request",
          "Unknown endpoint: " + path);
    }
  }

  @Override
  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (path == null) {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "invalid_request", "Missing path");
      return;
    }

    // PUT /clients/{id}/regenerate-secret
    if (path.matches("/clients/[^/]+/regenerate-secret/?")) {
      String clientId = extractPathSegment(path, 2);
      handleRegenerateSecret(request, response, clientId);
    }
    // PUT /clients/{id}
    else if (path.matches("/clients/[^/]+/?")) {
      String clientId = extractPathSegment(path, 2);
      handleUpdateClient(request, response, clientId);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "invalid_request",
          "Unknown endpoint: " + path);
    }
  }

  @Override
  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (path != null && path.matches("/clients/[^/]+/?")) {
      String clientId = extractPathSegment(path, 2);
      handleDeleteClient(request, response, clientId);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "invalid_request",
          "Unknown endpoint: " + path);
    }
  }

  // --- Token endpoint (B2 — existing) ---

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

  // --- Client CRUD (B3) ---

  /**
   * GET /sws/oauth2/clients — List all OAuth2 clients.
   * Requires JWT auth with System Administrator role.
   */
  private void handleListClients(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      requireAdmin(request);

      Connection conn = OBDal.getInstance().getConnection();
      JSONArray clients = new JSONArray();

      try (PreparedStatement ps = conn.prepareStatement(SQL_LIST_CLIENTS);
           ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject client = new JSONObject();
          client.put("id", rs.getString("etgo_oauth2_client_id"));
          client.put("name", rs.getString("name"));
          client.put("clientId", rs.getString("client_identifier"));
          client.put("adUserId", rs.getString("ad_user_id"));
          client.put("adRoleId", rs.getString("ad_role_id"));
          client.put("scopes", rs.getString("scopes"));
          client.put("isActive", "Y".equals(rs.getString("isactive")));
          clients.put(client);
        }
      }

      JSONObject result = new JSONObject();
      result.put("clients", clients);
      writeJsonResponse(response, HttpServletResponse.SC_OK, result);

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error listing OAuth2 clients", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  /**
   * POST /sws/oauth2/clients — Create a new OAuth2 client.
   * Requires JWT auth with System Administrator role.
   * Returns the plaintext client secret ONCE in the response.
   */
  private void handleCreateClient(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      DecodedJWT jwt = requireAdmin(request);
      String adminUserId = jwt.getClaim("user").asString();

      JSONObject body = parseJsonBody(request);
      String name = body.optString("name", null);
      String adUserId = body.optString("adUserId", null);
      String adRoleId = body.optString("adRoleId", null);
      String scopes = body.optString("scopes", "neo:read");
      boolean isActive = body.optBoolean("isActive", true);

      if (name == null || name.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "name is required");
        return;
      }
      if (adUserId == null || adUserId.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "adUserId is required");
        return;
      }
      if (adRoleId == null || adRoleId.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "adRoleId is required");
        return;
      }

      String clientIdentifier = OAuth2Utils.generateClientId();
      String plainSecret = OAuth2Utils.generateSecureToken();
      String secretHash = OAuth2Utils.hashSecret(plainSecret);

      Connection conn = OBDal.getInstance().getConnection();
      String generatedId;

      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_CLIENT)) {
        ps.setString(1, isActive ? "Y" : "N");   // isactive
        ps.setString(2, adminUserId);             // createdby
        ps.setString(3, adminUserId);             // updatedby
        ps.setString(4, name.trim());             // name
        ps.setString(5, clientIdentifier);        // client_identifier
        ps.setString(6, secretHash);              // client_secret_hash
        ps.setString(7, adUserId.trim());         // ad_user_id
        ps.setString(8, adRoleId.trim());         // ad_role_id
        ps.setString(9, scopes.trim());           // scopes
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          generatedId = rs.getString(1);
        }
      }
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", generatedId);
      result.put("name", name.trim());
      result.put("clientId", clientIdentifier);
      result.put("clientSecret", plainSecret);
      result.put("adUserId", adUserId.trim());
      result.put("adRoleId", adRoleId.trim());
      result.put("scopes", scopes.trim());
      result.put("isActive", isActive);

      writeJsonResponse(response, HttpServletResponse.SC_CREATED, result);
      log.info("OAuth2 client created: {} ({})", name, clientIdentifier);

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error creating OAuth2 client", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  /**
   * PUT /sws/oauth2/clients/{id} — Update an existing OAuth2 client.
   * Requires JWT auth with System Administrator role.
   * Updates name, scopes, adUserId, adRoleId, isActive. Does NOT touch the secret.
   */
  private void handleUpdateClient(HttpServletRequest request, HttpServletResponse response,
      String id) throws IOException {
    try {
      DecodedJWT jwt = requireAdmin(request);
      String adminUserId = jwt.getClaim("user").asString();

      JSONObject body = parseJsonBody(request);

      // Verify client exists
      JSONObject existing = findClientById(id);
      if (existing == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "not_found",
            "Client not found: " + id);
        return;
      }

      String name = body.optString("name", existing.getString("name"));
      String scopes = body.optString("scopes", existing.getString("scopes"));
      String adUserId = body.optString("adUserId", existing.getString("adUserId"));
      String adRoleId = body.optString("adRoleId", existing.getString("adRoleId"));
      boolean isActive = body.has("isActive") ? body.getBoolean("isActive") : existing.getBoolean("isActive");

      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_CLIENT)) {
        ps.setString(1, name.trim());
        ps.setString(2, scopes.trim());
        ps.setString(3, adUserId.trim());
        ps.setString(4, adRoleId.trim());
        ps.setString(5, isActive ? "Y" : "N");
        ps.setString(6, adminUserId);
        ps.setString(7, id);
        int rows = ps.executeUpdate();
        if (rows == 0) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, "not_found",
              "Client not found: " + id);
          return;
        }
      }
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", id);
      result.put("name", name.trim());
      result.put("clientId", existing.getString("clientId"));
      result.put("adUserId", adUserId.trim());
      result.put("adRoleId", adRoleId.trim());
      result.put("scopes", scopes.trim());
      result.put("isActive", isActive);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 client updated: {}", id);

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error updating OAuth2 client", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  /**
   * DELETE /sws/oauth2/clients/{id} — Delete an OAuth2 client and all its tokens.
   * Requires JWT auth with System Administrator role.
   */
  private void handleDeleteClient(HttpServletRequest request, HttpServletResponse response,
      String id) throws IOException {
    try {
      requireAdmin(request);

      Connection conn = OBDal.getInstance().getConnection();

      // Delete associated tokens first (FK constraint)
      try (PreparedStatement ps = conn.prepareStatement(SQL_DELETE_TOKENS_BY_CLIENT)) {
        ps.setString(1, id);
        ps.executeUpdate();
      }

      // Delete the client
      int rows;
      try (PreparedStatement ps = conn.prepareStatement(SQL_DELETE_CLIENT)) {
        ps.setString(1, id);
        rows = ps.executeUpdate();
      }

      if (rows == 0) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "not_found",
            "Client not found: " + id);
        return;
      }

      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("deleted", true);
      result.put("id", id);
      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 client deleted: {}", id);

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error deleting OAuth2 client", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  // --- B5: Regenerate Secret ---

  /**
   * PUT /sws/oauth2/clients/{id}/regenerate-secret — Generate a new secret for an OAuth2 client.
   * Requires JWT auth with System Administrator role.
   * Optionally revokes existing tokens. Returns plaintext secret ONCE.
   */
  private void handleRegenerateSecret(HttpServletRequest request, HttpServletResponse response,
      String id) throws IOException {
    try {
      DecodedJWT jwt = requireAdmin(request);
      String adminUserId = jwt.getClaim("user").asString();

      // Check if caller wants to revoke existing tokens
      boolean revokeTokens = true;
      String contentType = request.getContentType();
      if (contentType != null && contentType.contains("application/json")) {
        JSONObject body = parseJsonBody(request);
        revokeTokens = body.optBoolean("revokeExistingTokens", true);
      }

      // Verify client exists
      JSONObject existing = findClientById(id);
      if (existing == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, "not_found",
            "Client not found: " + id);
        return;
      }

      // Generate new secret
      String plainSecret = OAuth2Utils.generateSecureToken();
      String secretHash = OAuth2Utils.hashSecret(plainSecret);

      Connection conn = OBDal.getInstance().getConnection();

      // Update the secret hash
      try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_SECRET)) {
        ps.setString(1, secretHash);
        ps.setString(2, adminUserId);
        ps.setString(3, id);
        ps.executeUpdate();
      }

      // Optionally revoke existing tokens
      if (revokeTokens) {
        try (PreparedStatement ps = conn.prepareStatement(SQL_DELETE_TOKENS_BY_CLIENT)) {
          ps.setString(1, id);
          ps.executeUpdate();
        }
      }

      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("id", id);
      result.put("clientId", existing.getString("clientId"));
      result.put("clientSecret", plainSecret);
      result.put("tokensRevoked", revokeTokens);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 client secret regenerated: {}", id);

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error regenerating OAuth2 client secret", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  // --- B4: Revoke and Introspect ---

  /**
   * POST /sws/oauth2/revoke — Revoke all tokens for a client.
   * Requires JWT auth with System Administrator role.
   */
  private void handleRevoke(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      requireAdmin(request);

      JSONObject body = parseJsonBody(request);
      String clientIdentifier = body.optString("clientId", null);

      if (clientIdentifier == null || clientIdentifier.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "clientId is required");
        return;
      }

      Connection conn = OBDal.getInstance().getConnection();
      int rowsUpdated;
      try (PreparedStatement ps = conn.prepareStatement(SQL_REVOKE_TOKENS)) {
        ps.setString(1, clientIdentifier.trim());
        rowsUpdated = ps.executeUpdate();
      }
      OBDal.getInstance().flush();

      JSONObject result = new JSONObject();
      result.put("revoked", true);
      result.put("clientId", clientIdentifier.trim());
      result.put("tokensRevoked", rowsUpdated);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 tokens revoked for client: {} ({} tokens)", clientIdentifier, rowsUpdated);

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error revoking OAuth2 tokens", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  /**
   * POST /sws/oauth2/introspect — Token introspection (RFC 7662).
   * Requires JWT auth with System Administrator role.
   * Returns active status, scopes, expiry, and client_id for a given access token.
   */
  private void handleIntrospect(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      requireAdmin(request);

      JSONObject body = parseJsonBody(request);
      String token = body.optString("token", null);

      if (token == null || token.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "token is required");
        return;
      }

      String tokenHash = OAuth2Utils.hashToken(token.trim());

      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_INTROSPECT_TOKEN)) {
        ps.setString(1, tokenHash);
        try (ResultSet rs = ps.executeQuery()) {
          JSONObject result = new JSONObject();

          if (!rs.next()) {
            // Token not found — inactive
            result.put("active", false);
          } else {
            String isRevoked = rs.getString("is_revoked");
            Timestamp expiresAt = rs.getTimestamp("expires_at");
            boolean expired = OAuth2Utils.isTokenExpired(expiresAt);
            boolean revoked = "Y".equals(isRevoked);

            if (expired || revoked) {
              result.put("active", false);
            } else {
              result.put("active", true);
              result.put("scope", rs.getString("scopes"));
              result.put("exp", expiresAt.getTime() / 1000);
              result.put("client_id", rs.getString("client_identifier"));
            }
          }

          writeJsonResponse(response, HttpServletResponse.SC_OK, result);
        }
      }

    } catch (AuthException e) {
      writeError(response, e.statusCode, "access_denied", e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error during OAuth2 token introspection", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  // --- Auth helpers ---

  /**
   * Authenticate a JWT Bearer token from the Authorization header.
   *
   * @param request the HTTP request
   * @return decoded JWT
   * @throws AuthException if authentication fails
   */
  private DecodedJWT authenticateJwt(HttpServletRequest request) throws AuthException {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED,
          "Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    try {
      return SecureWebServicesUtils.decodeToken(token);
    } catch (Exception e) {
      log.warn("JWT authentication failed: {}", e.getMessage());
      throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired JWT token");
    }
  }

  /**
   * Authenticate JWT and verify the caller has System Administrator role (roleId = "0").
   *
   * @param request the HTTP request
   * @return decoded JWT
   * @throws AuthException if authentication or authorization fails
   */
  private DecodedJWT requireAdmin(HttpServletRequest request) throws AuthException {
    DecodedJWT jwt = authenticateJwt(request);
    String roleId = jwt.getClaim("role").asString();
    if (!ADMIN_ROLE_ID.equals(roleId)) {
      throw new AuthException(HttpServletResponse.SC_FORBIDDEN,
          "System Administrator role required");
    }
    return jwt;
  }

  // --- Data access helpers ---

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
   * Find an OAuth2 client by its primary key (etgo_oauth2_client_id).
   *
   * @param id the client UUID
   * @return JSON object with client data, or null if not found
   */
  private JSONObject findClientById(String id) throws SQLException, JSONException {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_CLIENT_BY_ID)) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        JSONObject client = new JSONObject();
        client.put("id", rs.getString("etgo_oauth2_client_id"));
        client.put("name", rs.getString("name"));
        client.put("clientId", rs.getString("client_identifier"));
        client.put("adUserId", rs.getString("ad_user_id"));
        client.put("adRoleId", rs.getString("ad_role_id"));
        client.put("scopes", rs.getString("scopes"));
        client.put("isActive", "Y".equals(rs.getString("isactive")));
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

  // --- Scope helpers ---

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

  // --- JSON/HTTP helpers ---

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
   * Extract a path segment by index from a pathInfo string.
   * E.g., extractPathSegment("/clients/abc-123/regenerate-secret", 2) returns "abc-123".
   */
  private String extractPathSegment(String path, int index) {
    String[] segments = path.split("/");
    // segments[0] is empty (leading /), segments[1] is "clients", segments[2] is the id, etc.
    if (index < segments.length) {
      return segments[index];
    }
    return null;
  }

  // --- Inner classes ---

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

  /**
   * Exception for authentication/authorization failures with HTTP status codes.
   */
  private static class AuthException extends Exception {
    final int statusCode;

    AuthException(int statusCode, String message) {
      super(message);
      this.statusCode = statusCode;
    }
  }
}
