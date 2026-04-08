package com.etendoerp.go.oauth2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
  private static final int REFRESH_TOKEN_EXPIRY_SECONDS = 604800; // 7 days
  private static final int AUTH_CODE_EXPIRY_MS = 300_000; // 5 minutes
  private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
  private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
  private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
  private static final String ADMIN_ROLE_ID = "0";

  /** In-memory store for authorization codes (short-lived, single-use). */
  private static final Map<String, AuthCodeData> AUTH_CODE_STORE = new ConcurrentHashMap<>();

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
      + "etgo_oauth2_client_id, access_token_hash, refresh_token_hash, scopes, expires_at, is_revoked) "
      + "VALUES (get_uuid(), ?, '0', 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, 'N')";

  private static final String SQL_FIND_BY_REFRESH_TOKEN =
      "SELECT t.etgo_oauth2_token_id, t.etgo_oauth2_client_id, t.scopes, t.is_revoked, "
      + "c.ad_user_id, c.ad_role_id, c.ad_client_id AS etendo_client_id, c.isactive AS client_active "
      + "FROM etgo_oauth2_token t "
      + "JOIN etgo_oauth2_client c ON t.etgo_oauth2_client_id = c.etgo_oauth2_client_id "
      + "WHERE t.refresh_token_hash = ?";

  private static final String SQL_REVOKE_TOKEN_BY_ID =
      "UPDATE etgo_oauth2_token SET is_revoked = 'Y', updated = now() "
      + "WHERE etgo_oauth2_token_id = ?";

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

  private static final String SQL_FIND_CLIENT_BY_IDENTIFIER =
      "SELECT etgo_oauth2_client_id, name, client_identifier, client_secret_hash, "
      + "ad_user_id, ad_role_id, scopes, isactive "
      + "FROM etgo_oauth2_client WHERE client_identifier = ?";

  private static final String SQL_INSERT_DCR_CLIENT =
      "INSERT INTO etgo_oauth2_client "
      + "(etgo_oauth2_client_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "name, client_identifier, client_secret_hash, ad_user_id, ad_role_id, scopes, ad_module_id) "
      + "VALUES (get_uuid(), '0', '0', 'Y', now(), '0', now(), '0', ?, ?, '', '0', '0', ?, '0') "
      + "RETURNING etgo_oauth2_client_id, client_identifier";

  // --- CORS ---

  private void setCorsHeaders(HttpServletResponse response) {
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
    response.setHeader("Access-Control-Allow-Headers",
        "Content-Type, Authorization, Accept");
    response.setHeader("Access-Control-Max-Age", "86400");
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws javax.servlet.ServletException, IOException {
    setCorsHeaders(response);
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }
    super.service(request, response);
  }

  // --- HTTP method dispatchers ---

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if ("/clients".equals(path) || "/clients/".equals(path)) {
      handleListClients(request, response);
    } else if ("/authorize".equals(path) || "/authorize/".equals(path)) {
      handleAuthorizeGet(request, response);
    } else if ("/metadata".equals(path) || "/metadata/".equals(path)
        || "/.well-known/oauth-authorization-server".equals(path)
        || path == null || path.isEmpty() || "/".equals(path)) {
      handleMetadata(request, response);
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
    } else if ("/authorize".equals(path) || "/authorize/".equals(path)) {
      handleAuthorizePost(request, response);
    } else if ("/register".equals(path) || "/register/".equals(path)) {
      handleRegister(request, response);
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

      // Route by grant_type
      if (GRANT_TYPE_AUTHORIZATION_CODE.equals(grantType)) {
        handleAuthorizationCodeGrant(request, response, contentType);
        return;
      }

      if (GRANT_TYPE_REFRESH_TOKEN.equals(grantType)) {
        handleRefreshTokenGrant(request, response, contentType);
        return;
      }

      if (!GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "unsupported_grant_type",
            "Supported grant types: client_credentials, authorization_code, refresh_token");
        return;
      }

      // --- Client Credentials flow (existing) ---

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

      // Generate access token and refresh token
      String accessToken = OAuth2Utils.generateSecureToken();
      String tokenHash = OAuth2Utils.hashToken(accessToken);
      String refreshToken = OAuth2Utils.generateSecureToken();
      String refreshTokenHash = OAuth2Utils.hashToken(refreshToken);
      Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000L));

      storeToken(client, tokenHash, refreshTokenHash, grantedScopeStr, expiresAt);

      // Build RFC 6749 response
      JSONObject result = new JSONObject();
      result.put("access_token", accessToken);
      result.put("token_type", "bearer");
      result.put("expires_in", TOKEN_EXPIRY_SECONDS);
      result.put("refresh_token", refreshToken);
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

  // --- Authorization Code + PKCE flow ---

  /**
   * GET /sws/oauth2/authorize — Serve the login page for the Authorization Code flow.
   * Query params: client_id, redirect_uri, response_type, code_challenge, code_challenge_method, state, scope
   */
  private void handleAuthorizeGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String clientId = request.getParameter("client_id");
    String redirectUri = request.getParameter("redirect_uri");
    String responseType = request.getParameter("response_type");
    String codeChallenge = request.getParameter("code_challenge");
    String codeChallengeMethod = request.getParameter("code_challenge_method");
    String state = request.getParameter("state");
    String scope = request.getParameter("scope");

    if (!"code".equals(responseType)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "unsupported_response_type",
          "Only response_type=code is supported");
      return;
    }

    if (codeChallenge == null || codeChallenge.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
          "code_challenge is required (PKCE S256)");
      return;
    }

    if (!"S256".equals(codeChallengeMethod)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
          "code_challenge_method must be S256");
      return;
    }

    // Redirect to the PWA authorize page, forwarding all OAuth params.
    // The PWA handles authentication (user is already logged in) and consent UI.
    String appBase = resolveAppUrl(request);
    StringBuilder appUrl = new StringBuilder(appBase)
        .append("/authorize")
        .append("?response_type=code")
        .append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
        .append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
        .append("&code_challenge=").append(java.net.URLEncoder.encode(codeChallenge, "UTF-8"))
        .append("&code_challenge_method=S256");
    if (state != null && !state.isEmpty()) {
      appUrl.append("&state=").append(java.net.URLEncoder.encode(state, "UTF-8"));
    }
    if (scope != null && !scope.isEmpty()) {
      appUrl.append("&scope=").append(java.net.URLEncoder.encode(scope, "UTF-8"));
    }
    response.sendRedirect(appUrl.toString());
  }

  /**
   * POST /sws/oauth2/authorize — Process the login form submission.
   * Validates credentials via /sws/login, generates an authorization code, and redirects.
   */
  private void handleAuthorizePost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      String contentType = request.getContentType();
      String jwtToken;
      String clientId;
      String redirectUri;
      String codeChallenge;
      String state;
      String scope;

      if (contentType != null && contentType.contains("application/json")) {
        JSONObject body = parseJsonBody(request);
        jwtToken = body.optString("token", null);
        clientId = body.optString("client_id", null);
        redirectUri = body.optString("redirect_uri", null);
        codeChallenge = body.optString("code_challenge", null);
        state = body.optString("state", null);
        scope = body.optString("scope", null);
      } else {
        jwtToken = request.getParameter("token");
        clientId = request.getParameter("client_id");
        redirectUri = request.getParameter("redirect_uri");
        codeChallenge = request.getParameter("code_challenge");
        state = request.getParameter("state");
        scope = request.getParameter("scope");
      }

      if (jwtToken == null || jwtToken.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "JWT token is required");
        return;
      }

      if (redirectUri == null || redirectUri.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "redirect_uri is required");
        return;
      }

      if (codeChallenge == null || codeChallenge.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "code_challenge is required");
        return;
      }

      // Validate JWT to get user identity
      DecodedJWT jwt;
      try {
        jwt = SecureWebServicesUtils.decodeToken(jwtToken);
      } catch (Exception e) {
        log.warn("Authorization code request with invalid JWT: {}", e.getMessage());
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "access_denied",
            "Invalid JWT token");
        return;
      }

      String userId = jwt.getClaim("user").asString();
      String roleId = jwt.getClaim("role").asString();

      // Generate authorization code and store it
      String authCode = OAuth2Utils.generateAuthCode();
      String codeHash = OAuth2Utils.hashToken(authCode);

      purgeExpiredAuthCodes();

      AuthCodeData codeData = new AuthCodeData();
      codeData.clientId = clientId;
      codeData.userId = userId;
      codeData.roleId = roleId;
      codeData.redirectUri = redirectUri;
      codeData.codeChallenge = codeChallenge;
      codeData.scopes = scope != null ? scope : "neo:read neo:write";
      codeData.expiresAt = System.currentTimeMillis() + AUTH_CODE_EXPIRY_MS;
      codeData.used = false;

      AUTH_CODE_STORE.put(codeHash, codeData);

      // Build redirect URL
      StringBuilder redirect = new StringBuilder(redirectUri);
      redirect.append(redirectUri.contains("?") ? "&" : "?");
      redirect.append("code=").append(authCode);
      if (state != null && !state.isEmpty()) {
        redirect.append("&state=").append(state);
      }

      log.info("Authorization code issued for user={}, client={}", userId, clientId);

      // Return JSON with redirect URL (fetch() can't read Location header on opaque redirects)
      JSONObject result = new JSONObject();
      result.put("redirect_url", redirect.toString());
      writeJsonResponse(response, HttpServletResponse.SC_OK, result);

    } catch (JSONException e) {
      log.error("Error processing authorize request", e);
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
          "Malformed request body");
    }
  }

  /**
   * Handle the authorization_code grant type in the token endpoint.
   * Validates the code, verifies PKCE, and issues an access token.
   */
  private void handleAuthorizationCodeGrant(HttpServletRequest request,
      HttpServletResponse response, String contentType) throws IOException {
    try {
      String code;
      String codeVerifier;
      String clientId;
      String redirectUri;

      if (contentType != null && contentType.contains("application/json")) {
        JSONObject body = parseJsonBody(request);
        code = body.optString("code", null);
        codeVerifier = body.optString("code_verifier", null);
        clientId = body.optString("client_id", null);
        redirectUri = body.optString("redirect_uri", null);
      } else {
        code = request.getParameter("code");
        codeVerifier = request.getParameter("code_verifier");
        clientId = request.getParameter("client_id");
        redirectUri = request.getParameter("redirect_uri");
      }

      if (code == null || code.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "code is required");
        return;
      }

      if (codeVerifier == null || codeVerifier.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "code_verifier is required (PKCE)");
        return;
      }

      // Look up the authorization code
      String codeHash = OAuth2Utils.hashToken(code);
      AuthCodeData codeData = AUTH_CODE_STORE.get(codeHash);

      if (codeData == null) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
            "Authorization code not found or expired");
        return;
      }

      // Single-use check
      if (codeData.used) {
        AUTH_CODE_STORE.remove(codeHash);
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
            "Authorization code already used");
        return;
      }

      // Expiry check
      if (System.currentTimeMillis() > codeData.expiresAt) {
        AUTH_CODE_STORE.remove(codeHash);
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
            "Authorization code expired");
        return;
      }

      // Verify PKCE code_challenge
      if (!OAuth2Utils.verifyCodeChallenge(codeVerifier, codeData.codeChallenge)) {
        AUTH_CODE_STORE.remove(codeHash);
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
            "PKCE verification failed");
        return;
      }

      // Verify redirect_uri matches
      if (redirectUri != null && !redirectUri.equals(codeData.redirectUri)) {
        AUTH_CODE_STORE.remove(codeHash);
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
            "redirect_uri mismatch");
        return;
      }

      // Mark code as used
      codeData.used = true;
      AUTH_CODE_STORE.remove(codeHash);

      // Build a synthetic ClientRecord for token storage
      // For DCR/public clients, user and role come from the auth code (login), not the client record
      ClientRecord tokenClient = new ClientRecord();
      tokenClient.adClientId = "0";
      tokenClient.adUserId = codeData.userId;
      tokenClient.adRoleId = codeData.roleId;

      // Find the client record and update it with the authenticated user/role
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_CLIENT_BY_IDENTIFIER)) {
        ps.setString(1, codeData.clientId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            tokenClient.id = rs.getString("etgo_oauth2_client_id");
            tokenClient.adClientId = "0";
          }
        }
      }

      if (tokenClient.id == null) {
        log.warn("No client record found for client_id: {}", codeData.clientId);
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_grant",
            "Client not found");
        return;
      }

      // Update DCR client with the authenticated user/role so OAuth2Filter resolves them
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE etgo_oauth2_client SET ad_user_id = ?, ad_role_id = ?, updated = now() "
          + "WHERE etgo_oauth2_client_id = ?")) {
        ps.setString(1, codeData.userId);
        ps.setString(2, codeData.roleId);
        ps.setString(3, tokenClient.id);
        ps.executeUpdate();
      }
      OBDal.getInstance().flush();
      log.info("DCR client updated with user={}, role={}", codeData.userId, codeData.roleId);

      // Generate access token and refresh token
      String accessToken = OAuth2Utils.generateSecureToken();
      String tokenHash = OAuth2Utils.hashToken(accessToken);
      String refreshToken = OAuth2Utils.generateSecureToken();
      String refreshTokenHash = OAuth2Utils.hashToken(refreshToken);
      Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000L));

      storeToken(tokenClient, tokenHash, refreshTokenHash, codeData.scopes, expiresAt);

      // Build response
      JSONObject result = new JSONObject();
      result.put("access_token", accessToken);
      result.put("token_type", "bearer");
      result.put("expires_in", TOKEN_EXPIRY_SECONDS);
      result.put("refresh_token", refreshToken);
      result.put("scope", codeData.scopes);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 token issued via authorization_code for user={}, client={}",
          codeData.userId, codeData.clientId);

    } catch (JSONException e) {
      log.error("Error building authorization_code response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    } catch (SQLException e) {
      log.error("Database error during authorization_code token exchange", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  // --- Refresh Token Grant (RFC 6749 Section 6) ---

  /**
   * Handle the refresh_token grant type.
   * Validates the refresh token, revokes the old token pair, and issues new access + refresh tokens.
   */
  private void handleRefreshTokenGrant(HttpServletRequest request,
      HttpServletResponse response, String contentType) throws IOException {
    try {
      String refreshTokenParam;

      if (contentType != null && contentType.contains("application/json")) {
        JSONObject body = parseJsonBody(request);
        refreshTokenParam = body.optString("refresh_token", null);
      } else {
        refreshTokenParam = request.getParameter("refresh_token");
      }

      if (refreshTokenParam == null || refreshTokenParam.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
            "refresh_token is required");
        return;
      }

      String refreshHash = OAuth2Utils.hashToken(refreshTokenParam);

      Connection conn = OBDal.getInstance().getConnection();

      // Look up the token row by refresh_token_hash
      String tokenId = null;
      String oauth2ClientId = null;
      String scopes = null;
      String adUserId = null;
      String adRoleId = null;
      String adClientId = null;
      boolean revoked = false;
      boolean clientActive = false;

      try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_BY_REFRESH_TOKEN)) {
        ps.setString(1, refreshHash);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_grant",
                "Refresh token not found or expired");
            return;
          }
          tokenId = rs.getString("etgo_oauth2_token_id");
          oauth2ClientId = rs.getString("etgo_oauth2_client_id");
          scopes = rs.getString("scopes");
          revoked = "Y".equals(rs.getString("is_revoked"));
          adUserId = rs.getString("ad_user_id");
          adRoleId = rs.getString("ad_role_id");
          adClientId = rs.getString("etendo_client_id");
          clientActive = "Y".equals(rs.getString("client_active"));
        }
      }

      if (revoked) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_grant",
            "Refresh token has been revoked");
        return;
      }

      if (!clientActive) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_grant",
            "OAuth2 client is inactive");
        return;
      }

      // Revoke the old token pair (rotation: each refresh token is single-use)
      try (PreparedStatement ps = conn.prepareStatement(SQL_REVOKE_TOKEN_BY_ID)) {
        ps.setString(1, tokenId);
        ps.executeUpdate();
      }

      // Issue new access token + refresh token
      ClientRecord client = new ClientRecord();
      client.id = oauth2ClientId;
      client.adClientId = adClientId;
      client.adUserId = adUserId;
      client.adRoleId = adRoleId;

      String newAccessToken = OAuth2Utils.generateSecureToken();
      String newTokenHash = OAuth2Utils.hashToken(newAccessToken);
      String newRefreshToken = OAuth2Utils.generateSecureToken();
      String newRefreshHash = OAuth2Utils.hashToken(newRefreshToken);
      Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000L));

      storeToken(client, newTokenHash, newRefreshHash, scopes, expiresAt);

      // Build response
      JSONObject result = new JSONObject();
      result.put("access_token", newAccessToken);
      result.put("token_type", "bearer");
      result.put("expires_in", TOKEN_EXPIRY_SECONDS);
      result.put("refresh_token", newRefreshToken);
      result.put("scope", scopes);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 token refreshed for client_id={}", oauth2ClientId);

    } catch (JSONException e) {
      log.error("Error building refresh_token response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    } catch (SQLException e) {
      log.error("Database error during refresh_token grant", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  // --- Dynamic Client Registration (RFC 7591) ---

  /**
   * POST /sws/oauth2/register — Register a new OAuth2 client dynamically.
   * Public endpoint (no auth required). Creates a public client (no secret).
   */
  private void handleRegister(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      JSONObject body = parseJsonBody(request);
      String clientName = body.optString("client_name", "MCP Client");
      String scopes = "neo:read neo:write";

      // Generate a unique client_identifier
      String clientIdentifier = OAuth2Utils.generateClientId();

      Connection conn = OBDal.getInstance().getConnection();
      String generatedId;

      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_DCR_CLIENT)) {
        ps.setString(1, clientName);         // name
        ps.setString(2, clientIdentifier);   // client_identifier
        ps.setString(3, scopes);             // scopes
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          generatedId = rs.getString(1);
        }
      }
      OBDal.getInstance().flush();

      // RFC 7591 response
      JSONObject result = new JSONObject();
      result.put("client_id", clientIdentifier);
      result.put("client_name", clientName);
      result.put("grant_types", new JSONArray(Arrays.asList("authorization_code", "refresh_token")));
      result.put("response_types", new JSONArray(Arrays.asList("code")));
      result.put("token_endpoint_auth_method", "none");
      result.put("redirect_uris", body.optJSONArray("redirect_uris"));
      result.put("client_id_issued_at", System.currentTimeMillis() / 1000);
      result.put("client_secret_expires_at", 0);

      writeJsonResponse(response, HttpServletResponse.SC_CREATED, result);
      log.info("DCR client registered: {} ({})", clientName, clientIdentifier);

    } catch (JSONException e) {
      log.error("Error processing DCR request", e);
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
          "Malformed request body");
    } catch (SQLException e) {
      log.error("Database error during DCR", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  // --- Authorization Server Metadata ---

  /**
   * GET /oauth2/metadata — Return OAuth2 Authorization Server Metadata (RFC 8414).
   */
  private void handleMetadata(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      String baseUrl = buildBaseUrl(request);

      JSONObject metadata = new JSONObject();
      String appUrl = resolveAppUrl(request);
      metadata.put("issuer", baseUrl + "/oauth2");
      metadata.put("authorization_endpoint", appUrl + "/authorize");
      metadata.put("token_endpoint", baseUrl + "/oauth2/token");
      metadata.put("registration_endpoint", baseUrl + "/oauth2/register");
      metadata.put("scopes_supported",
          new JSONArray(Arrays.asList("neo:read", "neo:write", "neo:process", "neo:report", "neo:*")));
      metadata.put("response_types_supported", new JSONArray(Arrays.asList("code")));
      metadata.put("grant_types_supported",
          new JSONArray(Arrays.asList("authorization_code", "client_credentials", "refresh_token")));
      metadata.put("token_endpoint_auth_methods_supported",
          new JSONArray(Arrays.asList("none", "client_secret_post")));
      metadata.put("code_challenge_methods_supported", new JSONArray(Arrays.asList("S256")));

      writeJsonResponse(response, HttpServletResponse.SC_OK, metadata);
    } catch (JSONException e) {
      log.error("Error building metadata response", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error",
          "Internal server error");
    }
  }

  /**
   * Resolve the PWA app URL.
   * Priority: Openbravo.properties (etgo.app.url) > env ETGO_APP_URL > request origin.
   */
  private String resolveAppUrl(HttpServletRequest request) {
    String appUrl = null;
    try {
      appUrl = org.openbravo.base.session.OBPropertiesProvider.getInstance()
          .getOpenbravoProperties().getProperty("etgo.app.url");
    } catch (Exception e) {
      log.debug("Could not read etgo.app.url from properties: {}", e.getMessage());
    }
    if (appUrl == null || appUrl.isEmpty()) {
      appUrl = System.getenv("ETGO_APP_URL");
    }
    if (appUrl == null || appUrl.isEmpty()) {
      appUrl = buildBaseUrl(request);
    }
    if (appUrl.endsWith("/")) {
      appUrl = appUrl.substring(0, appUrl.length() - 1);
    }
    return appUrl;
  }

  /**
   * Build the base URL (scheme + host + contextPath) from the current request.
   */
  private String buildBaseUrl(HttpServletRequest request) {
    String scheme = request.getScheme();
    String host = request.getServerName();
    int port = request.getServerPort();
    String contextPath = request.getContextPath();

    StringBuilder url = new StringBuilder();
    url.append(scheme).append("://").append(host);
    if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
      url.append(":").append(port);
    }
    url.append(contextPath);
    return url.toString();
  }

  /**
   * Build the HTML login page for the OAuth2 authorize flow.
   */
  private String buildLoginPage(String contextPath, String clientId, String redirectUri,
      String codeChallenge, String state, String scope) {
    String safeClientId = escapeHtml(clientId != null ? clientId : "");
    String safeRedirectUri = escapeHtml(redirectUri != null ? redirectUri : "");
    String safeCodeChallenge = escapeHtml(codeChallenge != null ? codeChallenge : "");
    String safeState = escapeHtml(state != null ? state : "");
    String safeScope = escapeHtml(scope != null ? scope : "");

    return "<!DOCTYPE html>\n"
      + "<html lang=\"en\">\n<head>\n"
      + "<meta charset=\"UTF-8\">\n"
      + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
      + "<title>Etendo — Authorize MCP Client</title>\n"
      + "<style>\n"
      + "  * { box-sizing: border-box; margin: 0; padding: 0; }\n"
      + "  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
      + "    background: #f5f5f5; display: flex; justify-content: center; align-items: center;\n"
      + "    min-height: 100vh; }\n"
      + "  .card { background: white; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.1);\n"
      + "    padding: 2rem; width: 100%; max-width: 400px; }\n"
      + "  h1 { font-size: 1.5rem; margin-bottom: 0.5rem; color: #1a1a1a; }\n"
      + "  p.sub { color: #666; font-size: 0.9rem; margin-bottom: 1.5rem; }\n"
      + "  label { display: block; font-weight: 500; margin-bottom: 0.25rem; color: #333; font-size: 0.9rem; }\n"
      + "  input[type=text], input[type=password], select { width: 100%; padding: 0.6rem 0.8rem;\n"
      + "    border: 1px solid #ddd; border-radius: 6px; font-size: 1rem; margin-bottom: 1rem;\n"
      + "    background: white; }\n"
      + "  input:focus, select:focus { outline: none; border-color: #2563eb;\n"
      + "    box-shadow: 0 0 0 3px rgba(37,99,235,0.1); }\n"
      + "  button { width: 100%; padding: 0.7rem; background: #2563eb; color: white;\n"
      + "    border: none; border-radius: 6px; font-size: 1rem; cursor: pointer; font-weight: 500; }\n"
      + "  button:hover { background: #1d4ed8; }\n"
      + "  button:disabled { background: #93c5fd; cursor: not-allowed; }\n"
      + "  .error { color: #dc2626; font-size: 0.85rem; margin-bottom: 1rem; display: none; }\n"
      + "  .info { color: #666; font-size: 0.8rem; margin-top: 1rem; text-align: center; }\n"
      + "  .hidden { display: none; }\n"
      + "</style>\n</head>\n<body>\n"
      + "<div class=\"card\">\n"
      + "  <h1>Authorize MCP Client</h1>\n"
      + "  <p class=\"sub\">Sign in with your Etendo credentials to authorize access.</p>\n"
      + "  <div id=\"error\" class=\"error\"></div>\n"
      + "  <form id=\"loginForm\">\n"
      + "    <label for=\"username\">Username</label>\n"
      + "    <input type=\"text\" id=\"username\" name=\"username\" autocomplete=\"username\" required autofocus>\n"
      + "    <label for=\"password\">Password</label>\n"
      + "    <input type=\"password\" id=\"password\" name=\"password\" autocomplete=\"current-password\" required>\n"
      + "    <div id=\"roleSection\" class=\"hidden\">\n"
      + "      <label for=\"roleSelect\">Role</label>\n"
      + "      <select id=\"roleSelect\"></select>\n"
      + "    </div>\n"
      + "    <button type=\"submit\" id=\"submitBtn\">Sign In &amp; Authorize</button>\n"
      + "  </form>\n"
      + "  <p class=\"info\">Client: " + safeClientId + "</p>\n"
      + "</div>\n"
      + "<script>\n"
      + "const form = document.getElementById('loginForm');\n"
      + "const errorDiv = document.getElementById('error');\n"
      + "const submitBtn = document.getElementById('submitBtn');\n"
      + "const roleSection = document.getElementById('roleSection');\n"
      + "const roleSelect = document.getElementById('roleSelect');\n"
      + "const CTX = '" + escapeJs(contextPath) + "';\n"
      + "\n"
      + "let currentToken = null;\n"
      + "let currentPassword = null;\n"
      + "\n"
      + "form.addEventListener('submit', async (e) => {\n"
      + "  e.preventDefault();\n"
      + "  errorDiv.style.display = 'none';\n"
      + "  submitBtn.disabled = true;\n"
      + "  submitBtn.textContent = 'Signing in...';\n"
      + "\n"
      + "  const username = document.getElementById('username').value;\n"
      + "  const password = document.getElementById('password').value;\n"
      + "\n"
      + "  try {\n"
      + "    // If a role is selected, re-login with that role and authorize\n"
      + "    if (roleSection.classList.contains('hidden') === false && roleSelect.value) {\n"
      + "      const reLoginResp = await fetch(CTX + '/sws/login', {\n"
      + "        method: 'POST',\n"
      + "        headers: { 'Content-Type': 'application/json' },\n"
      + "        body: JSON.stringify({ username, password: currentPassword, role: roleSelect.value })\n"
      + "      });\n"
      + "      const reLoginData = await reLoginResp.json();\n"
      + "      if (reLoginData.status === 'error' || !reLoginData.token) {\n"
      + "        throw new Error(reLoginData.message || 'Authentication failed');\n"
      + "      }\n"
      + "      currentToken = reLoginData.token;\n"
      + "      await submitAuthorize(currentToken);\n"
      + "      return;\n"
      + "    }\n"
      + "\n"
      + "    // Step 1: First login without role to get roleList\n"
      + "    currentPassword = password;\n"
      + "    const loginResp = await fetch(CTX + '/sws/login', {\n"
      + "      method: 'POST',\n"
      + "      headers: { 'Content-Type': 'application/json' },\n"
      + "      body: JSON.stringify({ username, password })\n"
      + "    });\n"
      + "    const loginData = await loginResp.json();\n"
      + "    if (loginData.status === 'error' || !loginData.token) {\n"
      + "      throw new Error(loginData.message || 'Authentication failed');\n"
      + "    }\n"
      + "    currentToken = loginData.token;\n"
      + "\n"
      + "    // If roleList has multiple roles, show selector\n"
      + "    if (loginData.roleList && loginData.roleList.length > 1) {\n"
      + "      roleSelect.innerHTML = '';\n"
      + "      loginData.roleList.forEach(r => {\n"
      + "        const opt = document.createElement('option');\n"
      + "        opt.value = r.id;\n"
      + "        opt.textContent = r.name;\n"
      + "        roleSelect.appendChild(opt);\n"
      + "      });\n"
      + "      roleSection.classList.remove('hidden');\n"
      + "      submitBtn.disabled = false;\n"
      + "      submitBtn.textContent = 'Authorize';\n"
      + "      return;\n"
      + "    }\n"
      + "\n"
      + "    // Single role — authorize directly\n"
      + "    await submitAuthorize(currentToken);\n"
      + "  } catch (err) {\n"
      + "    errorDiv.textContent = err.message;\n"
      + "    errorDiv.style.display = 'block';\n"
      + "    submitBtn.disabled = false;\n"
      + "    submitBtn.textContent = roleSection.classList.contains('hidden') ? 'Sign In & Authorize' : 'Authorize';\n"
      + "  }\n"
      + "});\n"
      + "\n"
      + "async function submitAuthorize(token) {\n"
      + "  const authResp = await fetch(CTX + '/oauth2/authorize', {\n"
      + "    method: 'POST',\n"
      + "    headers: { 'Content-Type': 'application/json' },\n"
      + "    body: JSON.stringify({\n"
      + "      token: token,\n"
      + "      client_id: '" + escapeJs(safeClientId) + "',\n"
      + "      redirect_uri: '" + escapeJs(safeRedirectUri) + "',\n"
      + "      code_challenge: '" + escapeJs(safeCodeChallenge) + "',\n"
      + "      state: '" + escapeJs(safeState) + "',\n"
      + "      scope: '" + escapeJs(safeScope) + "'\n"
      + "    })\n"
      + "  });\n"
      + "\n"
      + "  const authData = await authResp.json();\n"
      + "  if (authData.redirect_url) {\n"
      + "    window.location.href = authData.redirect_url;\n"
      + "  } else {\n"
      + "    throw new Error(authData.error_description || authData.error || 'Authorization failed');\n"
      + "  }\n"
      + "}\n"
      + "</script>\n</body>\n</html>";
  }

  /**
   * Purge expired authorization codes from the in-memory store.
   */
  private void purgeExpiredAuthCodes() {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, AuthCodeData>> it = AUTH_CODE_STORE.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().expiresAt < now) {
        it.remove();
      }
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
  private void storeToken(ClientRecord client, String tokenHash, String refreshTokenHash,
      String scopes, Timestamp expiresAt) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_TOKEN)) {
      ps.setString(1, client.adClientId);    // ad_client_id
      ps.setString(2, client.adUserId);      // createdby
      ps.setString(3, client.adUserId);      // updatedby
      ps.setString(4, client.id);            // etgo_oauth2_client_id
      ps.setString(5, tokenHash);            // access_token_hash
      ps.setString(6, refreshTokenHash);     // refresh_token_hash
      ps.setString(7, scopes);               // scopes
      ps.setTimestamp(8, expiresAt);          // expires_at
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

  /**
   * In-memory holder for authorization codes (short-lived, single-use).
   */
  private static class AuthCodeData {
    String clientId;
    String userId;
    String roleId;
    String redirectUri;
    String codeChallenge;
    String scopes;
    long expiresAt;
    boolean used;
  }

  /**
   * Escape a string for safe inclusion in HTML attributes.
   */
  private String escapeHtml(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /**
   * Escape a string for safe inclusion in JavaScript string literals.
   */
  private String escapeJs(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
