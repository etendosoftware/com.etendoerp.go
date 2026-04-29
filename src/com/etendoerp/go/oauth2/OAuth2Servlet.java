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
import org.openbravo.erpCommon.utility.SequenceIdData;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.common.ProtocolErrorAdapters;
import com.etendoerp.go.oauth2.OAuth2ClientPolicy;
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
  private static final int AUTH_CODE_EXPIRY_MS = 300_000; // 5 minutes
    private static final String APPLICATION_JSON = "application/json";
    private static final String UTF_8 = "UTF-8";
    private static final String TOKEN_TYPE_BEARER = "bearer";
    private static final String ERROR_INVALID_REQUEST = "invalid_request";
    private static final String ERROR_SERVER = "server_error";
    private static final String ERROR_ACCESS_DENIED = "access_denied";
    private static final String ERROR_NOT_FOUND = "not_found";
    private static final String ERROR_INVALID_GRANT = "invalid_grant";
    private static final String MESSAGE_INTERNAL_SERVER_ERROR = "Internal server error";
    private static final String MESSAGE_UNKNOWN_ENDPOINT = "Unknown endpoint: ";
    private static final String MESSAGE_CLIENT_NOT_FOUND = "Client not found: ";
    private static final String PATH_AUTHORIZE = "/authorize";
  private static final String ERROR_INVALID_CLIENT = "invalid_client";
  private static final String ERROR_INVALID_SCOPE = "invalid_scope";
  private static final String MESSAGE_SCOPE_EXCEEDS_PERMISSIONS =
      "Requested scope exceeds client permissions";
  private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";
  private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
  private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final String SCOPE_NEO_READ = "neo:read";
    private static final String SCOPE_NEO_WRITE = "neo:write";
    private static final String SCOPE_NEO_PROCESS = "neo:process";
    private static final String SCOPE_NEO_REPORT = "neo:report";
  private static final String ADMIN_ROLE_ID = "0";
    private static final String FIELD_ID = "id";
    private static final String FIELD_CLIENT_ID = "clientId";
    private static final String FIELD_CLIENT_ID_REQUEST = "client_id";
    private static final String FIELD_CLIENT_IDENTIFIER = "client_identifier";
    private static final String FIELD_AD_USER_ID = "adUserId";
    private static final String FIELD_DB_AD_USER_ID = "ad_user_id";
    private static final String FIELD_AD_ROLE_ID = "adRoleId";
    private static final String FIELD_DB_AD_ROLE_ID = "ad_role_id";
    private static final String FIELD_SCOPES = "scopes";
    private static final String FIELD_REDIRECT_URIS_JSON = "redirectUrisJson";
    private static final String FIELD_SCOPE = "scope";
    private static final String FIELD_IS_ACTIVE = "isActive";
    private static final String FIELD_ACTIVE = "active";
    private static final String FIELD_TOKEN = "token";
    private static final String FIELD_ACCESS_TOKEN = "access_token";
    private static final String FIELD_TOKEN_TYPE = "token_type";
    private static final String FIELD_EXPIRES_IN = "expires_in";
    private static final String FIELD_REFRESH_TOKEN = GRANT_TYPE_REFRESH_TOKEN;
    private static final String FIELD_REDIRECT_URI = "redirect_uri";
    private static final String FIELD_REDIRECT_URIS = "redirect_uris";
    private static final String FIELD_CODE_CHALLENGE = "code_challenge";
    private static final String FIELD_STATE = "state";
    private static final String DB_OAUTH2_CLIENT_ID = "etgo_oauth2_client_id";

  private static final String WILDCARD_SCOPE = "neo:*";

  /** In-memory store for authorization codes (short-lived, single-use). */
  private static final Map<String, AuthCodeData> AUTH_CODE_STORE = new ConcurrentHashMap<>();

  private static final Set<String> VALID_SCOPES = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList(SCOPE_NEO_READ, SCOPE_NEO_WRITE, SCOPE_NEO_PROCESS,
        SCOPE_NEO_REPORT, WILDCARD_SCOPE))
  );

  // --- SQL constants ---

  private static final String SQL_FIND_CLIENT =
      "SELECT etgo_oauth2_client_id, client_secret_hash, scopes, redirect_uris, ad_client_id, ad_user_id, ad_role_id "
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
      "SELECT etgo_oauth2_client_id, name, client_identifier, ad_user_id, ad_role_id, scopes, redirect_uris, isactive "
      + "FROM etgo_oauth2_client ORDER BY name";

  private static final String SQL_INSERT_CLIENT =
      "INSERT INTO etgo_oauth2_client "
      + "(etgo_oauth2_client_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "name, client_identifier, client_secret_hash, ad_user_id, ad_role_id, scopes, redirect_uris, ad_module_id) "
      + "VALUES (?, '0', '0', ?, now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, '0')";

  private static final String SQL_UPDATE_CLIENT =
      "UPDATE etgo_oauth2_client SET name = ?, scopes = ?, redirect_uris = ?, ad_user_id = ?, ad_role_id = ?, "
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
      "SELECT etgo_oauth2_client_id, name, client_identifier, ad_user_id, ad_role_id, scopes, redirect_uris, isactive "
      + "FROM etgo_oauth2_client WHERE etgo_oauth2_client_id = ?";

  private static final String SQL_FIND_CLIENT_BY_IDENTIFIER =
      "SELECT etgo_oauth2_client_id, name, client_identifier, client_secret_hash, "
      + "ad_user_id, ad_role_id, scopes, redirect_uris, isactive "
      + "FROM etgo_oauth2_client WHERE client_identifier = ?";

  private static final String SQL_INSERT_DCR_CLIENT =
      "INSERT INTO etgo_oauth2_client "
      + "(etgo_oauth2_client_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, "
      + "name, client_identifier, client_secret_hash, ad_user_id, ad_role_id, scopes, redirect_uris, ad_module_id) "
      + "VALUES (?, '0', '0', 'Y', now(), '0', now(), '0', ?, ?, '', '0', '0', ?, ?, '0')";

  // --- CORS ---

  private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
    CorsUtils.apply(request, response, "GET, POST, PUT, DELETE, OPTIONS",
        "Content-Type, Authorization, Accept", null, false);
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws javax.servlet.ServletException, IOException {
    setCorsHeaders(request, response);
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
    } else if (PATH_AUTHORIZE.equals(path) || (PATH_AUTHORIZE + "/").equals(path)) {
      handleAuthorizeGet(request, response);
    } else if ("/metadata".equals(path) || "/metadata/".equals(path)
        || "/.well-known/oauth-authorization-server".equals(path)
        || path == null || path.isEmpty() || "/".equals(path)) {
      handleMetadata(request, response);
    } else {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_INVALID_REQUEST,
          MESSAGE_UNKNOWN_ENDPOINT + path);
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
    } else if (PATH_AUTHORIZE.equals(path) || (PATH_AUTHORIZE + "/").equals(path)) {
      handleAuthorizePost(request, response);
    } else if ("/register".equals(path) || "/register/".equals(path)) {
      handleRegister(request, response);
    } else {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_INVALID_REQUEST,
          MESSAGE_UNKNOWN_ENDPOINT + path);
    }
  }

  @Override
  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (path == null) {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_INVALID_REQUEST, "Missing path");
      return;
    }

    // PUT /clients/{id}/regenerate-secret
    if (path.matches("/clients/[^/]+/regenerate-secret/?")) {
      String clientId = extractPathSegment(path, 2);
      handleRegenerateSecret(request, response, clientId);
    }
    else if (path.matches("/clients/[^/]+/?")) {
      String clientId = extractPathSegment(path, 2);
      handleUpdateClient(request, response, clientId);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_INVALID_REQUEST,
          MESSAGE_UNKNOWN_ENDPOINT + path);
    }
  }

  @Override
  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (path != null && path.matches("/clients/[^/]+/?")) {
      String clientId = extractPathSegment(path, 2);
      handleDeleteClient(request, response, clientId);
    } else {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_INVALID_REQUEST,
          MESSAGE_UNKNOWN_ENDPOINT + path);
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
      if (contentType != null && contentType.contains(APPLICATION_JSON)) {
        JSONObject body = parseJsonBody(request);
        grantType = body.optString("grant_type", null);
        clientId = body.optString(FIELD_CLIENT_ID_REQUEST, null);
        clientSecret = body.optString("client_secret", null);
        scopeParam = body.optString(FIELD_SCOPE, null);
      } else {
        // Default: form-encoded
        grantType = request.getParameter("grant_type");
        clientId = request.getParameter(FIELD_CLIENT_ID_REQUEST);
        clientSecret = request.getParameter("client_secret");
        scopeParam = request.getParameter(FIELD_SCOPE);
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
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "client_id and client_secret are required");
        return;
      }

      // Look up client in database
      ClientRecord client = findClient(clientId);
      if (client == null) {
        log.warn("OAuth2 token request for unknown client_id: {}", clientId);
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR_INVALID_CLIENT,
            "Client authentication failed");
        return;
      }

      // Verify secret
      if (!OAuth2Utils.verifySecret(clientSecret, client.secretHash)) {
        log.warn("OAuth2 token request with invalid secret for client_id: {}", clientId);
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR_INVALID_CLIENT,
            "Client authentication failed");
        return;
      }

      // Parse and validate scopes
      Set<String> requestedScopes = OAuth2ClientPolicy.parseScopes(scopeParam, VALID_SCOPES);
      Set<String> allowedScopes = OAuth2ClientPolicy.parseScopes(client.scopes, VALID_SCOPES);

      if (!requestedScopes.isEmpty()
          && !OAuth2ClientPolicy.isScopeAllowed(requestedScopes, allowedScopes, WILDCARD_SCOPE)) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_SCOPE,
            MESSAGE_SCOPE_EXCEEDS_PERMISSIONS);
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
      result.put(FIELD_ACCESS_TOKEN, accessToken);
      result.put(FIELD_TOKEN_TYPE, TOKEN_TYPE_BEARER);
      result.put(FIELD_EXPIRES_IN, TOKEN_EXPIRY_SECONDS);
      result.put(FIELD_REFRESH_TOKEN, refreshToken);
      result.put(FIELD_SCOPE, grantedScopeStr);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 token issued for client: {}", clientId);

    } catch (JSONException e) {
      log.error("Failed to build OAuth2 response", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
    } catch (SQLException e) {
      log.error("Database error during OAuth2 token request", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
          client.put(FIELD_ID, rs.getString(DB_OAUTH2_CLIENT_ID));
          client.put("name", rs.getString("name"));
          client.put(FIELD_CLIENT_ID, rs.getString(FIELD_CLIENT_IDENTIFIER));
          client.put(FIELD_AD_USER_ID, rs.getString(FIELD_DB_AD_USER_ID));
          client.put(FIELD_AD_ROLE_ID, rs.getString(FIELD_DB_AD_ROLE_ID));
          client.put(FIELD_SCOPES, rs.getString(FIELD_SCOPES));
          client.put(FIELD_REDIRECT_URIS_JSON, rs.getString("redirect_uris"));
          client.put(FIELD_IS_ACTIVE, "Y".equals(rs.getString("isactive")));
          clients.put(client);
        }
      }

      JSONObject result = new JSONObject();
      result.put("clients", clients);
      writeJsonResponse(response, HttpServletResponse.SC_OK, result);

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error listing OAuth2 clients", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
      String adUserId = body.optString(FIELD_AD_USER_ID, null);
      String adRoleId = body.optString(FIELD_AD_ROLE_ID, null);
      String scopes = body.optString(FIELD_SCOPES, SCOPE_NEO_READ);
      boolean isActive = body.optBoolean(FIELD_IS_ACTIVE, true);
      String redirectUrisJson = body.optString(FIELD_REDIRECT_URIS_JSON, "[]");

      if (name == null || name.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "name is required");
        return;
      }
      if (adUserId == null || adUserId.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "adUserId is required");
        return;
      }
      if (adRoleId == null || adRoleId.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "adRoleId is required");
        return;
      }

      String clientIdentifier = OAuth2Utils.generateClientId();
      String plainSecret = OAuth2Utils.generateSecureToken();
      String secretHash = OAuth2Utils.hashSecret(plainSecret);

      Connection conn = OBDal.getInstance().getConnection();
      String generatedId = SequenceIdData.getUUID();

      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_CLIENT)) {
        ps.setString(1, generatedId);             // etgo_oauth2_client_id
        ps.setString(2, isActive ? "Y" : "N");   // isactive
        ps.setString(3, adminUserId);             // createdby
        ps.setString(4, adminUserId);             // updatedby
        ps.setString(5, name.trim());             // name
        ps.setString(6, clientIdentifier);        // client_identifier
        ps.setString(7, secretHash);              // client_secret_hash
        ps.setString(8, adUserId.trim());         // ad_user_id
        ps.setString(9, adRoleId.trim());         // ad_role_id
        ps.setString(10, scopes.trim());          // scopes
        ps.setString(11, redirectUrisJson);       // redirect_uris
        ps.executeUpdate();
      }

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, generatedId);
      result.put("name", name.trim());
      result.put(FIELD_CLIENT_ID, clientIdentifier);
      result.put("clientSecret", plainSecret);
      result.put(FIELD_AD_USER_ID, adUserId.trim());
      result.put(FIELD_AD_ROLE_ID, adRoleId.trim());
      result.put(FIELD_SCOPES, scopes.trim());
      result.put(FIELD_REDIRECT_URIS_JSON, redirectUrisJson);
      result.put(FIELD_IS_ACTIVE, isActive);

      writeJsonResponse(response, HttpServletResponse.SC_CREATED, result);
      log.info("OAuth2 client created: {} ({})", name, clientIdentifier);

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error creating OAuth2 client", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
        writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_NOT_FOUND,
            MESSAGE_CLIENT_NOT_FOUND + id);
        return;
      }

      String name = body.optString("name", existing.getString("name"));
      String scopes = body.optString(FIELD_SCOPES, existing.getString(FIELD_SCOPES));
      String adUserId = body.optString(FIELD_AD_USER_ID, existing.getString(FIELD_AD_USER_ID));
      String adRoleId = body.optString(FIELD_AD_ROLE_ID, existing.getString(FIELD_AD_ROLE_ID));
      String redirectUrisJson = body.optString(
          FIELD_REDIRECT_URIS_JSON, existing.optString(FIELD_REDIRECT_URIS_JSON, "[]"));
      boolean isActive = body.has(FIELD_IS_ACTIVE)
          ? body.getBoolean(FIELD_IS_ACTIVE)
          : existing.getBoolean(FIELD_IS_ACTIVE);

      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_CLIENT)) {
        ps.setString(1, name.trim());
        ps.setString(2, scopes.trim());
        ps.setString(3, redirectUrisJson);
        ps.setString(4, adUserId.trim());
        ps.setString(5, adRoleId.trim());
        ps.setString(6, isActive ? "Y" : "N");
        ps.setString(7, adminUserId);
        ps.setString(8, id);
        int rows = ps.executeUpdate();
        if (rows == 0) {
          writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_NOT_FOUND,
              MESSAGE_CLIENT_NOT_FOUND + id);
          return;
        }
      }

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, id);
      result.put("name", name.trim());
      result.put(FIELD_CLIENT_ID, existing.getString(FIELD_CLIENT_ID));
      result.put(FIELD_AD_USER_ID, adUserId.trim());
      result.put(FIELD_AD_ROLE_ID, adRoleId.trim());
      result.put(FIELD_SCOPES, scopes.trim());
      result.put(FIELD_REDIRECT_URIS_JSON, redirectUrisJson);
      result.put(FIELD_IS_ACTIVE, isActive);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 client updated: {}", id);

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error updating OAuth2 client", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
        writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_NOT_FOUND,
          MESSAGE_CLIENT_NOT_FOUND + id);
        return;
      }

      JSONObject result = new JSONObject();
      result.put("deleted", true);
      result.put(FIELD_ID, id);
      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 client deleted: {}", id);

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error deleting OAuth2 client", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
      if (contentType != null && contentType.contains(APPLICATION_JSON)) {
        JSONObject body = parseJsonBody(request);
        revokeTokens = body.optBoolean("revokeExistingTokens", true);
      }

      // Verify client exists
      JSONObject existing = findClientById(id);
      if (existing == null) {
        writeError(response, HttpServletResponse.SC_NOT_FOUND, ERROR_NOT_FOUND,
          MESSAGE_CLIENT_NOT_FOUND + id);
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

      JSONObject result = new JSONObject();
      result.put(FIELD_ID, id);
      result.put(FIELD_CLIENT_ID, existing.getString(FIELD_CLIENT_ID));
      result.put("clientSecret", plainSecret);
      result.put("tokensRevoked", revokeTokens);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 client secret regenerated: {}", id);

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error regenerating OAuth2 client secret", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
      String clientIdentifier = body.optString(FIELD_CLIENT_ID, null);

      if (clientIdentifier == null || clientIdentifier.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "clientId is required");
        return;
      }

      Connection conn = OBDal.getInstance().getConnection();
      int rowsUpdated;
      try (PreparedStatement ps = conn.prepareStatement(SQL_REVOKE_TOKENS)) {
        ps.setString(1, clientIdentifier.trim());
        rowsUpdated = ps.executeUpdate();
      }

      JSONObject result = new JSONObject();
      result.put("revoked", true);
      result.put(FIELD_CLIENT_ID, clientIdentifier.trim());
      result.put("tokensRevoked", rowsUpdated);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 tokens revoked for client: {} ({} tokens)", clientIdentifier, rowsUpdated);

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error revoking OAuth2 tokens", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
      String token = body.optString(FIELD_TOKEN, null);

      if (token == null || token.trim().isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
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
            result.put(FIELD_ACTIVE, false);
          } else {
            String isRevoked = rs.getString("is_revoked");
            Timestamp expiresAt = rs.getTimestamp("expires_at");
            boolean expired = OAuth2Utils.isTokenExpired(expiresAt);
            boolean revoked = "Y".equals(isRevoked);

            if (expired || revoked) {
              result.put(FIELD_ACTIVE, false);
            } else {
              result.put(FIELD_ACTIVE, true);
              result.put(FIELD_SCOPE, rs.getString(FIELD_SCOPES));
              result.put("exp", expiresAt.getTime() / 1000);
              result.put(FIELD_CLIENT_ID_REQUEST, rs.getString(FIELD_CLIENT_IDENTIFIER));
            }
          }

          writeJsonResponse(response, HttpServletResponse.SC_OK, result);
        }
      }

    } catch (AuthException e) {
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException | JSONException e) {
      log.error("Error during OAuth2 token introspection", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
    }
  }

  // --- Authorization Code + PKCE flow ---

  /**
   * GET /sws/oauth2/authorize — Serve the login page for the Authorization Code flow.
   * Query params: client_id, redirect_uri, response_type, code_challenge, code_challenge_method, state, scope
   */
  private void handleAuthorizeGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String clientId = request.getParameter(FIELD_CLIENT_ID_REQUEST);
    String redirectUri = request.getParameter(FIELD_REDIRECT_URI);
    String responseType = request.getParameter("response_type");
    String codeChallenge = request.getParameter(FIELD_CODE_CHALLENGE);
    String codeChallengeMethod = request.getParameter("code_challenge_method");
    String state = request.getParameter(FIELD_STATE);
    String scope = request.getParameter(FIELD_SCOPE);

    if (!"code".equals(responseType)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "unsupported_response_type",
          "Only response_type=code is supported");
      return;
    }

    if (codeChallenge == null || codeChallenge.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "code_challenge is required (PKCE S256)");
      return;
    }

    if (!"S256".equals(codeChallengeMethod)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "code_challenge_method must be S256");
      return;
    }

    try {
      if (!validateAuthorizeClientRequest(response, clientId, redirectUri, scope)) {
        return;
      }
    } catch (SQLException e) {
      log.error("Error validating OAuth2 authorize request", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
      return;
    }

    // Redirect to the PWA authorize page, forwarding all OAuth params.
    // The PWA handles authentication (user is already logged in) and consent UI.
    String appBase = resolveAppUrl(request);
    StringBuilder appUrl = new StringBuilder(appBase)
        .append(PATH_AUTHORIZE)
        .append("?response_type=code")
        .append("&client_id=").append(java.net.URLEncoder.encode(clientId, UTF_8))
        .append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, UTF_8))
        .append("&code_challenge=").append(java.net.URLEncoder.encode(codeChallenge, UTF_8))
        .append("&code_challenge_method=S256");
    if (state != null && !state.isEmpty()) {
      appUrl.append("&state=").append(java.net.URLEncoder.encode(state, UTF_8));
    }
    if (scope != null && !scope.isEmpty()) {
      appUrl.append("&scope=").append(java.net.URLEncoder.encode(scope, UTF_8));
    }
    response.sendRedirect(appUrl.toString());
  }

  private static final class AuthorizeRequestData {
    private final String jwtToken;
    private final String clientId;
    private final String redirectUri;
    private final String codeChallenge;
    private final String state;
    private final String scope;

    private AuthorizeRequestData(String jwtToken, String clientId, String redirectUri,
        String codeChallenge, String state, String scope) {
      this.jwtToken = jwtToken;
      this.clientId = clientId;
      this.redirectUri = redirectUri;
      this.codeChallenge = codeChallenge;
      this.state = state;
      this.scope = scope;
    }
  }


  /**
   * POST /sws/oauth2/authorize — Process the login form submission.
   * Validates credentials via /sws/login, generates an authorization code, and redirects.
   */
  private void handleAuthorizePost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      OAuth2AuthorizeSupport.AuthorizeRequestData authorizeRequest =
          OAuth2AuthorizeSupport.parseAuthorizeRequest(
              request, APPLICATION_JSON, FIELD_TOKEN, FIELD_CLIENT_ID_REQUEST, FIELD_REDIRECT_URI,
              FIELD_CODE_CHALLENGE, FIELD_STATE, FIELD_SCOPE, this::parseJsonBody);
      if (!validateAuthorizePostRequest(response, authorizeRequest)) {
        return;
      }

      ClientRecord client = findClient(authorizeRequest.clientId);
      Set<String> requestedScopes =
          OAuth2ClientPolicy.parseScopes(authorizeRequest.scope, VALID_SCOPES);
      Set<String> allowedScopes = OAuth2ClientPolicy.parseScopes(client.scopes, VALID_SCOPES);
      DecodedJWT jwt = authenticateJwt(authorizeRequest.jwtToken);

      String authCode = OAuth2Utils.generateAuthCode();
      String codeHash = OAuth2Utils.hashToken(authCode);
      purgeExpiredAuthCodes();

      AuthCodeData codeData = OAuth2AuthorizeSupport.buildAuthCodeData(
          authorizeRequest,
          jwt.getClaim("user").asString(),
          jwt.getClaim("role").asString(),
          requestedScopes,
          allowedScopes,
          AUTH_CODE_EXPIRY_MS);
      AUTH_CODE_STORE.put(codeHash, codeData);

      log.info("Authorization code issued for user={}, client={}",
          codeData.userId, authorizeRequest.clientId);
      OAuth2AuthorizeSupport.writeAuthorizeSuccess(
          response, authorizeRequest.redirectUri, authorizeRequest.state, authCode, this);

    } catch (AuthException e) {
      log.warn("Authorization code request with invalid JWT: {}", e.getMessage());
      writeError(response, e.statusCode, ERROR_ACCESS_DENIED, e.getMessage());
    } catch (SQLException e) {
      log.error("Error validating OAuth2 authorize request", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
    } catch (JSONException e) {
      log.error("Error processing authorize request", e);
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
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
      String redirectUri;

      if (contentType != null && contentType.contains(APPLICATION_JSON)) {
        JSONObject body = parseJsonBody(request);
        code = body.optString("code", null);
        codeVerifier = body.optString("code_verifier", null);
        redirectUri = body.optString(FIELD_REDIRECT_URI, null);
      } else {
        code = request.getParameter("code");
        codeVerifier = request.getParameter("code_verifier");
        redirectUri = request.getParameter(FIELD_REDIRECT_URI);
      }

      if (code == null || code.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "code is required");
        return;
      }

      if (codeVerifier == null || codeVerifier.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
            "code_verifier is required (PKCE)");
        return;
      }

      // Look up the authorization code
      String codeHash = OAuth2Utils.hashToken(code);
        AuthCodeData codeData = AUTH_CODE_STORE.get(codeHash);
        String grantError = OAuth2AuthorizationCodeSupport.validateAuthorizationCode(codeData,
          codeVerifier, redirectUri);
      if (grantError != null) {
        if (codeData != null) {
          AUTH_CODE_STORE.remove(codeHash);
        }
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_GRANT, grantError);
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
            tokenClient.id = rs.getString(DB_OAUTH2_CLIENT_ID);
            tokenClient.adClientId = "0";
          }
        }
      }

      if (tokenClient.id == null) {
        log.warn("No client record found for client_id: {}", codeData.clientId);
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_GRANT,
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
      result.put(FIELD_ACCESS_TOKEN, accessToken);
      result.put(FIELD_TOKEN_TYPE, TOKEN_TYPE_BEARER);
      result.put(FIELD_EXPIRES_IN, TOKEN_EXPIRY_SECONDS);
      result.put(FIELD_REFRESH_TOKEN, refreshToken);
      result.put(FIELD_SCOPE, codeData.scopes);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 token issued via authorization_code for user={}, client={}",
          codeData.userId, codeData.clientId);

    } catch (JSONException e) {
      log.error("Error building authorization_code response", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
    } catch (SQLException e) {
      log.error("Database error during authorization_code token exchange", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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

      if (contentType != null && contentType.contains(APPLICATION_JSON)) {
        JSONObject body = parseJsonBody(request);
        refreshTokenParam = body.optString(GRANT_TYPE_REFRESH_TOKEN, null);
      } else {
        refreshTokenParam = request.getParameter(GRANT_TYPE_REFRESH_TOKEN);
      }

      if (refreshTokenParam == null || refreshTokenParam.isEmpty()) {
        writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
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
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR_INVALID_GRANT,
                "Refresh token not found or expired");
            return;
          }
          tokenId = rs.getString("etgo_oauth2_token_id");
          oauth2ClientId = rs.getString(DB_OAUTH2_CLIENT_ID);
          scopes = rs.getString(FIELD_SCOPES);
          revoked = "Y".equals(rs.getString("is_revoked"));
          adUserId = rs.getString(FIELD_DB_AD_USER_ID);
          adRoleId = rs.getString(FIELD_DB_AD_ROLE_ID);
          adClientId = rs.getString("etendo_client_id");
          clientActive = "Y".equals(rs.getString("client_active"));
        }
      }

      if (revoked) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR_INVALID_GRANT,
            "Refresh token has been revoked");
        return;
      }

      if (!clientActive) {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR_INVALID_GRANT,
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
      result.put(FIELD_ACCESS_TOKEN, newAccessToken);
      result.put(FIELD_TOKEN_TYPE, TOKEN_TYPE_BEARER);
      result.put(FIELD_EXPIRES_IN, TOKEN_EXPIRY_SECONDS);
      result.put(FIELD_REFRESH_TOKEN, newRefreshToken);
      result.put(FIELD_SCOPE, scopes);

      writeJsonResponse(response, HttpServletResponse.SC_OK, result);
      log.info("OAuth2 token refreshed for client_id={}", oauth2ClientId);

    } catch (JSONException e) {
      log.error("Error building refresh_token response", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
    } catch (SQLException e) {
      log.error("Database error during refresh_token grant", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
      String scopes = SCOPE_NEO_READ;
      String redirectUris = OAuth2ClientPolicy.normalizeRedirectUris(
          body.optJSONArray(FIELD_REDIRECT_URIS));

      // Generate a unique client_identifier
      String clientIdentifier = OAuth2Utils.generateClientId();

      Connection conn = OBDal.getInstance().getConnection();
      String generatedId = SequenceIdData.getUUID();

      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_DCR_CLIENT)) {
        ps.setString(1, generatedId);        // etgo_oauth2_client_id
        ps.setString(2, clientName);         // name
        ps.setString(3, clientIdentifier);   // client_identifier
        ps.setString(4, scopes);             // scopes
        ps.setString(5, redirectUris);       // redirect_uris
        ps.executeUpdate();
      }

      // RFC 7591 response
      JSONObject result = new JSONObject();
        result.put(FIELD_CLIENT_ID_REQUEST, clientIdentifier);
      result.put("client_name", clientName);
        result.put("grant_types",
          new JSONArray(Arrays.asList(GRANT_TYPE_AUTHORIZATION_CODE, GRANT_TYPE_REFRESH_TOKEN)));
      result.put("response_types", new JSONArray(Arrays.asList("code")));
      result.put("token_endpoint_auth_method", "none");
      result.put(FIELD_REDIRECT_URIS, new JSONArray(redirectUris));
      result.put("client_id_issued_at", System.currentTimeMillis() / 1000);
      result.put("client_secret_expires_at", 0);

      writeJsonResponse(response, HttpServletResponse.SC_CREATED, result);
      log.info("DCR client registered: {} ({})", clientName, clientIdentifier);

    } catch (IllegalArgumentException e) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST, e.getMessage());
    } catch (JSONException e) {
      log.error("Error processing DCR request", e);
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "Malformed request body");
    } catch (SQLException e) {
      log.error("Database error during DCR", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
      metadata.put("authorization_endpoint", appUrl + PATH_AUTHORIZE);
      metadata.put("token_endpoint", baseUrl + "/oauth2/token");
      metadata.put("registration_endpoint", baseUrl + "/oauth2/register");
      metadata.put("scopes_supported",
            new JSONArray(Arrays.asList(SCOPE_NEO_READ, SCOPE_NEO_WRITE, SCOPE_NEO_PROCESS,
              SCOPE_NEO_REPORT, WILDCARD_SCOPE)));
      metadata.put("response_types_supported", new JSONArray(Arrays.asList("code")));
      metadata.put("grant_types_supported",
            new JSONArray(Arrays.asList(GRANT_TYPE_AUTHORIZATION_CODE,
              GRANT_TYPE_CLIENT_CREDENTIALS, GRANT_TYPE_REFRESH_TOKEN)));
      metadata.put("token_endpoint_auth_methods_supported",
          new JSONArray(Arrays.asList("none", "client_secret_post")));
      metadata.put("code_challenge_methods_supported", new JSONArray(Arrays.asList("S256")));

      writeJsonResponse(response, HttpServletResponse.SC_OK, metadata);
    } catch (JSONException e) {
      log.error("Error building metadata response", e);
        writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERROR_SERVER,
          MESSAGE_INTERNAL_SERVER_ERROR);
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
    return authenticateJwt(authHeader.substring(7));
  }

  private DecodedJWT authenticateJwt(String token) throws AuthException {
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

  private boolean validateAuthorizePostRequest(HttpServletResponse response,
      OAuth2AuthorizeSupport.AuthorizeRequestData authorizeRequest) throws IOException, SQLException {
    if (authorizeRequest.jwtToken == null || authorizeRequest.jwtToken.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "JWT token is required");
      return false;
    }
    if (authorizeRequest.codeChallenge == null || authorizeRequest.codeChallenge.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "code_challenge is required");
      return false;
    }
    return validateAuthorizeClientRequest(
        response, authorizeRequest.clientId, authorizeRequest.redirectUri, authorizeRequest.scope);
  }



  private boolean validateAuthorizeClientRequest(HttpServletResponse response, String clientId,
      String redirectUri, String scope) throws IOException, SQLException {
    if (clientId == null || clientId.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "client_id is required");
      return false;
    }
    if (redirectUri == null || redirectUri.isEmpty()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "redirect_uri is required");
      return false;
    }
    ClientRecord client = findClient(clientId);
    if (client == null) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ERROR_INVALID_CLIENT,
          "Unknown or inactive client_id");
      return false;
    }
    if (!OAuth2ClientPolicy.isRegisteredRedirectUri(client.redirectUrisJson, redirectUri)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "redirect_uri is not registered for this client_id");
      return false;
    }
    if (!OAuth2ClientPolicy.isSafeRedirectUri(redirectUri)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_REQUEST,
          "redirect_uri must use https, http://localhost, or http://127.0.0.1");
      return false;
    }
    Set<String> requestedScopes = OAuth2ClientPolicy.parseScopes(scope, VALID_SCOPES);
    Set<String> allowedScopes = OAuth2ClientPolicy.parseScopes(client.scopes, VALID_SCOPES);
    if (!requestedScopes.isEmpty()
        && !OAuth2ClientPolicy.isScopeAllowed(requestedScopes, allowedScopes, WILDCARD_SCOPE)) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, ERROR_INVALID_SCOPE,
          MESSAGE_SCOPE_EXCEEDS_PERMISSIONS);
      return false;
    }
    return true;
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
        client.id = rs.getString(DB_OAUTH2_CLIENT_ID);
        client.secretHash = rs.getString("client_secret_hash");
        client.scopes = rs.getString(FIELD_SCOPES);
        client.redirectUrisJson = rs.getString("redirect_uris");
        client.adClientId = rs.getString("ad_client_id");
        client.adUserId = rs.getString(FIELD_DB_AD_USER_ID);
        client.adRoleId = rs.getString(FIELD_DB_AD_ROLE_ID);
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
        client.put(FIELD_ID, rs.getString(DB_OAUTH2_CLIENT_ID));
        client.put("name", rs.getString("name"));
        client.put(FIELD_CLIENT_ID, rs.getString(FIELD_CLIENT_IDENTIFIER));
        client.put(FIELD_AD_USER_ID, rs.getString(FIELD_DB_AD_USER_ID));
        client.put(FIELD_AD_ROLE_ID, rs.getString(FIELD_DB_AD_ROLE_ID));
        client.put(FIELD_SCOPES, rs.getString(FIELD_SCOPES));
        client.put(FIELD_REDIRECT_URIS_JSON, rs.getString("redirect_uris"));
        client.put(FIELD_IS_ACTIVE, "Y".equals(rs.getString("isactive")));
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
  }

  // --- Scope helpers ---


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
  void writeJsonResponse(HttpServletResponse response, int status, JSONObject body)
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
    ProtocolErrorAdapters.writeOAuthError(response, status, error, description);
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
    String redirectUrisJson;
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
  static class AuthCodeData {
    String clientId;
    String userId;
    String roleId;
    String redirectUri;
    String codeChallenge;
    String scopes;
    long expiresAt;
    boolean used;
  }

}
