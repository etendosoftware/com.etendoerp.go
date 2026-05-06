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

package com.etendoerp.go.apps;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.CorsUtils;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Etendo Go Apps - F1 spike servlet (ETP-3805).
 *
 * <p>Mapped to {@code /sws/apps/*} via {@code AD_MODEL_OBJECT} / {@code AD_MODEL_OBJECT_MAPPING}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /sws/apps/.well-known/jwks.json} - publishes the RS256 public key
 *       (public, no auth). External apps fetch this to verify JWTs.</li>
 *   <li>{@code POST /sws/apps/token?appId=<id>} - mints a short-lived RS256 JWT for the
 *       given app. Requires an Etendo session JWT in the {@code Authorization: Bearer ...}
 *       header (same HS256 JWT the shell already uses for {@code /sws/neo/*}).</li>
 * </ul>
 *
 * <p>Keys: loaded from PEM files at {@code config/apps-spike/*.pem} (module-relative by default).
 * Override with system property {@code etendo.apps.keysDir} or env var
 * {@code ETENDO_APPS_KEYS_DIR}. Production must load from a secrets manager.
 */
public class AppsServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(AppsServlet.class);

  private static final String KID = "apps-spike-1";
  private static final String PATH_TOKEN = "/token";
  private static final String PATH_JWKS = "/.well-known/jwks.json";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CONTENT_TYPE_JSON = "application/json";
  private static final String DEFAULT_KEYS_DIR = "modules/com.etendoerp.go/config/apps-spike";

  // Hardcoded scopes for the F1 spike. F1 proper will read these from the app descriptor.
  private static final List<String> SPIKE_SCOPES =
      Collections.unmodifiableList(Arrays.asList("read:products", "read:users"));

  /** Lazily-loaded singleton - keys live on disk so we only read them once. */
  private static final AtomicReference<JwtIssuerService> issuer = new AtomicReference<>();

  // --- CORS ---

  private void setCorsHeaders(HttpServletRequest request, HttpServletResponse response) {
    CorsUtils.apply(request, response, "GET, POST, OPTIONS",
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

  // --- Dispatchers ---

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (PATH_JWKS.equals(path)) {
      handleJwks(response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + path);
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String path = request.getPathInfo();
    if (PATH_TOKEN.equals(path) || (PATH_TOKEN + "/").equals(path)) {
      handleToken(request, response);
    } else {
      writeError(response, HttpServletResponse.SC_NOT_FOUND, "Unknown endpoint: " + path);
    }
  }

  // --- Handlers ---

  private void handleJwks(HttpServletResponse response) throws IOException {
    try {
      JwtIssuerService svc = getIssuer();
      JSONObject jwk = buildJwk(svc);
      JSONObject payload = new JSONObject();
      payload.put("keys", new JSONArray().put(jwk));
      writeJson(response, HttpServletResponse.SC_OK, payload);
    } catch (Exception e) {
      log.error("JWKS endpoint failed", e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "JWKS unavailable");
    }
  }

  private void handleToken(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String appId = request.getParameter("appId");
    if (appId == null || appId.isBlank()) {
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing 'appId' parameter");
      return;
    }

    String authHeader = request.getHeader(HEADER_AUTHORIZATION);
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          "Missing or invalid Authorization header");
      return;
    }
    String etendoToken = authHeader.substring(BEARER_PREFIX.length());

    DecodedJWT decoded;
    try {
      decoded = SecureWebServicesUtils.decodeToken(etendoToken);
    } catch (Exception e) {
      log.warn("Rejected invalid Etendo JWT on /sws/apps/token: {}", e.getMessage());
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid Etendo session token");
      return;
    }

    String userId = decoded.getClaim("user").asString();
    String clientId = decoded.getClaim("client").asString();
    String orgId = decoded.getClaim("organization").asString();
    if (userId == null || userId.isBlank() || clientId == null || clientId.isBlank()
        || orgId == null || orgId.isBlank()) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
          "Etendo token missing required claims (user, client, organization)");
      return;
    }

    try {
      JwtIssuerService svc = getIssuer();
      String appToken = svc.issue(userId, clientId, orgId, appId, SPIKE_SCOPES);
      JSONObject payload = new JSONObject();
      payload.put("token", appToken);
      payload.put("expiresInSeconds", JwtIssuerService.TTL_SECONDS);
      writeJson(response, HttpServletResponse.SC_OK, payload);
    } catch (Exception e) {
      log.error("Failed to mint app token for appId={} user={}", appId, userId, e);
      writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to mint token");
    }
  }

  // --- JWKS helpers ---

  static JSONObject buildJwk(JwtIssuerService svc) throws JSONException {
    java.security.interfaces.RSAPublicKey pub = svc.getPublicKey();
    JSONObject jwk = new JSONObject();
    jwk.put("kty", "RSA");
    jwk.put("alg", "RS256");
    jwk.put("use", "sig");
    jwk.put("kid", svc.getKid());
    jwk.put("n", base64UrlUnsigned(pub.getModulus()));
    jwk.put("e", base64UrlUnsigned(pub.getPublicExponent()));
    return jwk;
  }

  /** Encode a BigInteger as unsigned big-endian base64url (JWK convention). */
  static String base64UrlUnsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    // JWK modulus/exponent values must omit the sign padding byte.
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] stripped = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, stripped, 0, stripped.length);
      bytes = stripped;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  // --- Issuer lifecycle ---

  private static JwtIssuerService getIssuer() throws Exception {
    JwtIssuerService local = issuer.get();
    if (local == null) {
      synchronized (AppsServlet.class) {
        local = issuer.get();
        if (local == null) {
          local = loadIssuer();
          issuer.set(local);
        }
      }
    }
    return local;
  }

  private static JwtIssuerService loadIssuer() throws Exception {
    String keysDir = System.getProperty("etendo.apps.keysDir");
    if (keysDir == null || keysDir.isBlank()) {
      keysDir = System.getenv("ETENDO_APPS_KEYS_DIR");
    }
    if (keysDir == null || keysDir.isBlank()) {
      keysDir = DEFAULT_KEYS_DIR;
    }
    Path base = Path.of(keysDir);
    log.info("Loading Apps spike keys from {}", base.toAbsolutePath());
    return JwtIssuerService.fromPemFiles(
        base.resolve("private-key.pem"),
        base.resolve("public-key.pem"),
        KID);
  }

  // --- Response helpers ---

  private static void writeJson(HttpServletResponse response, int status, JSONObject body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(CONTENT_TYPE_JSON);
    response.setCharacterEncoding("UTF-8");
    try (PrintWriter out = response.getWriter()) {
      out.write(body.toString());
    }
  }

  private static void writeError(HttpServletResponse response, int status, String message)
      throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", message);
      writeJson(response, status, body);
    } catch (JSONException e) {
      response.sendError(status, message);
    }
  }
}
