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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Cookie;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.AuthScheme;
import com.etendoerp.go.auth.DalWarehouseResolver;
import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;
import com.etendoerp.go.common.BoundedJsonParserTest;
import com.etendoerp.go.common.MalformedJsonException;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.OAuth2Client;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.JdbcGoSessionStore;
import com.etendoerp.go.session.SessionRoleRevokedException;
import com.etendoerp.go.usageevents.LogCapture;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;

/**
 * Unit tests for {@link OAuth2Servlet}.
 * Covers token endpoints, client CRUD, revocation, introspection,
 * authorization code flow, refresh token, dynamic client registration, and the self-service
 * public API key lifecycle ({@code /api-keys}).
 *
 * @covers com.etendoerp.go.oauth2.OAuth2Servlet
 */
public class OAuth2ServletTest {

  private static final String ADMIN_TOKEN = "admin-jwt-token";
  private static final String ADMIN_USER_ID = "0";
  private static final String ADMIN_ROLE_ID = "0";
  private static final String TEST_CLIENT_ID = "etgo-test123456";
  private static final String TEST_CLIENT_DB_ID = "uuid-client-1";

  private final OAuth2Servlet servlet = servletWithFakeRoleLookups();

  /**
   * ETP-5270 — a legacy JWT (the admin token, on the management endpoints and on authorize) now
   * has its role checked against {@code AD_User_Roles}. The DAL is mocked per test, so both role
   * lookups are a fake that finds the role held; everything else is the production wiring.
   */
  private static OAuth2Servlet servletWithFakeRoleLookups() {
    GoSessionService sessionService = new GoSessionService(new JdbcGoSessionStore());
    OAuth2Servlet created = new OAuth2Servlet(sessionService, new EnvironmentRequestAuthenticator(
        new GoSessionAuthenticator(sessionService), new TenantEnvironmentLifecycleService(),
        new DalWarehouseResolver(), mock(GoSessionRoleReconciler.class)));
    created.sessionRoleReconciler = mock(GoSessionRoleReconciler.class);
    return created;
  }

  // ===================== doGet routing =====================

  @Test
  public void doGetUnknownPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/unknown");

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void doGetClientsRequiresAuth() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/clients");

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(anyString()))
          .thenThrow(new RuntimeException("bad"));

      servlet.doGet(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("access_denied", body.getString("error"));
  }

  @Test
  public void doGetPublicApiKeysRequiresAuth() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/api-keys");

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(anyString()))
          .thenThrow(new RuntimeException("bad"));

      servlet.doGet(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("access_denied", body.getString("error"));
    assertFalse(body.has("apiKeys"));
  }

  @Test
  public void doGetClientsNonAdminRoleForbidden() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/clients");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, "100");

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);

      servlet.doGet(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("access_denied", body.getString("error"));
    assertTrue(body.getString("error_description").contains("System Administrator"));
  }

  @Test
  public void doGetListClientsSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/clients");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("name")).thenReturn("Test Client");
    when(rs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getString("redirect_uris")).thenReturn(null);
    when(rs.getString("isactive")).thenReturn("Y");

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doGet(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    JSONArray clients = body.getJSONArray("clients");
    assertEquals(1, clients.length());
    JSONObject client = clients.getJSONObject(0);
    assertEquals(TEST_CLIENT_DB_ID, client.getString("id"));
    assertEquals("Test Client", client.getString("name"));
    assertEquals(TEST_CLIENT_ID, client.getString("clientId"));
    assertTrue(client.getBoolean("isActive"));
  }

  // ===================== doPost /token — client_credentials =====================

  @Test
  public void tokenUnsupportedGrantTypeReturnsError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("implicit");

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("unsupported_grant_type", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsMissingClientId() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(null);
    when(req.getParameter("client_secret")).thenReturn("secret");

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsMissingSecret() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("client_secret")).thenReturn(null);

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsUnknownClient() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn("unknown-client");
    when(req.getParameter("client_secret")).thenReturn("secret");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_client", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsWrongSecret() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("client_secret")).thenReturn("wrong-secret");
    when(req.getParameter("scope")).thenReturn(null);

    String correctHash = OAuth2Utils.hashSecret("correct-secret");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("client_secret_hash")).thenReturn(correctHash);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getString("redirect_uris")).thenReturn("[]");
    when(rs.getString("ad_client_id")).thenReturn("0");
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_client", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsInvalidScope() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("client_secret")).thenReturn("correct-secret");
    when(req.getParameter("scope")).thenReturn("invalid:scope");

    String secretHash = OAuth2Utils.hashSecret("correct-secret");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("client_secret_hash")).thenReturn(secretHash);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getString("redirect_uris")).thenReturn("[]");
    when(rs.getString("ad_client_id")).thenReturn("0");
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_scope", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsScopeExceedsClientPermissions() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("client_secret")).thenReturn("correct-secret");
    when(req.getParameter("scope")).thenReturn("neo:write");

    String secretHash = OAuth2Utils.hashSecret("correct-secret");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("client_secret_hash")).thenReturn(secretHash);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getString("redirect_uris")).thenReturn("[]");
    when(rs.getString("ad_client_id")).thenReturn("0");
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_scope", body.getString("error"));
  }

  @Test
  public void tokenClientCredentialsSuccessFormEncoded() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("client_secret")).thenReturn("correct-secret");
    when(req.getParameter("scope")).thenReturn("neo:read");

    String secretHash = OAuth2Utils.hashSecret("correct-secret");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("client_secret_hash")).thenReturn(secretHash);
    when(findRs.getString("scopes")).thenReturn("neo:read neo:write");
    when(findRs.getString("redirect_uris")).thenReturn("[]");
    when(findRs.getString("ad_client_id")).thenReturn("0");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertNotNull(body.getString("access_token"));
    assertEquals("bearer", body.getString("token_type"));
    assertEquals(3600, body.getInt("expires_in"));
    assertNotNull(body.getString("refresh_token"));
    assertEquals("neo:read", body.getString("scope"));
  }

  /**
   * A client configured with the deprecated neo: scopes requesting the current etendo: name gets a
   * token, and the token echoes the requested name (ETP-5602).
   */
  @Test
  public void tokenClientCredentialsGrantsEtendoScopeFromLegacyClientScope() throws Exception {
    JSONObject body = issueClientCredentialsToken("etendo:read", "neo:read neo:write");
    assertNotNull(body.getString("access_token"));
    assertEquals("etendo:read", body.getString("scope"));
  }

  /** A client re-saved with etendo: allowed scopes still serves a legacy neo: request. */
  @Test
  public void tokenClientCredentialsGrantsLegacyScopeFromEtendoAllowList() throws Exception {
    JSONObject body = issueClientCredentialsToken("neo:read", "etendo:read");
    assertNotNull(body.getString("access_token"));
    assertEquals("neo:read", body.getString("scope"));
  }

  /** An etendo: allow-list does not widen access for a legacy request either. */
  @Test
  public void tokenClientCredentialsRejectsLegacyWriteForEtendoReadAllowList() throws Exception {
    JSONObject body = issueClientCredentialsToken("neo:write", "etendo:read");
    assertEquals("invalid_scope", body.getString("error"));
  }

  /** The reverse: a new-scope client still serves a legacy neo: request, echoed as requested. */
  @Test
  public void tokenClientCredentialsGrantsLegacyScopeFromEtendoWildcard() throws Exception {
    JSONObject body = issueClientCredentialsToken("neo:process etendo:read", "etendo:*");
    assertEquals("neo:process etendo:read", body.getString("scope"));
  }

  /** The alias never widens access: a neo:read client cannot obtain etendo:write. */
  @Test
  public void tokenClientCredentialsRejectsEtendoWriteForLegacyReadClient() throws Exception {
    JSONObject body = issueClientCredentialsToken("etendo:write", "neo:read");
    assertEquals("invalid_scope", body.getString("error"));
  }

  /** Unknown scopes of the new prefix are rejected like any other unknown scope. */
  @Test
  public void tokenClientCredentialsRejectsUnknownEtendoScope() throws Exception {
    JSONObject body = issueClientCredentialsToken("etendo:admin", "etendo:*");
    assertEquals("invalid_scope", body.getString("error"));
  }

  private JSONObject issueClientCredentialsToken(String requestedScope, String clientScopes)
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("client_credentials");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("client_secret")).thenReturn("correct-secret");
    when(req.getParameter("scope")).thenReturn(requestedScope);

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("client_secret_hash"))
        .thenReturn(OAuth2Utils.hashSecret("correct-secret"));
    when(findRs.getString("scopes")).thenReturn(clientScopes);
    when(findRs.getString("redirect_uris")).thenReturn("[]");
    when(findRs.getString("ad_client_id")).thenReturn("0");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);
    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, insertPs);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doPost(req, resp.response);
    }
    return new JSONObject(resp.body());
  }

  @Test
  public void tokenClientCredentialsSuccessJsonBody() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", null);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("grant_type", "client_credentials");
    requestBody.put("client_id", TEST_CLIENT_ID);
    requestBody.put("client_secret", "correct-secret");
    requestBody.put("scope", "neo:read");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    String secretHash = OAuth2Utils.hashSecret("correct-secret");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("client_secret_hash")).thenReturn(secretHash);
    when(findRs.getString("scopes")).thenReturn("neo:read neo:write");
    when(findRs.getString("redirect_uris")).thenReturn("[]");
    when(findRs.getString("ad_client_id")).thenReturn("0");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertNotNull(body.getString("access_token"));
    assertEquals("bearer", body.getString("token_type"));
  }

  // ===================== doPost /token — refresh_token =====================

  @Test
  public void tokenRefreshMissingToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn(null);

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void tokenRefreshTokenNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("nonexistent-token");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_grant", body.getString("error"));
  }

  @Test
  public void tokenRefreshRevokedToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("revoked-token");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getString("is_revoked")).thenReturn("Y");
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");
    when(rs.getString("etendo_client_id")).thenReturn("0");
    when(rs.getString("client_active")).thenReturn("Y");

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_grant", body.getString("error"));
    assertTrue(body.getString("error_description").contains("revoked"));
  }

  @Test
  public void tokenRefreshInactiveClient() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("some-token");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getString("is_revoked")).thenReturn("N");
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");
    when(rs.getString("etendo_client_id")).thenReturn("0");
    when(rs.getString("client_active")).thenReturn("N");

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_grant", body.getString("error"));
    assertTrue(body.getString("error_description").contains("inactive"));
  }

  @Test
  public void tokenRefreshSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("valid-refresh-token");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("scopes")).thenReturn("neo:read neo:write");
    when(findRs.getString("is_revoked")).thenReturn("N");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("etendo_client_id")).thenReturn("0");
    when(findRs.getString("client_active")).thenReturn("Y");
    // ETP-4393 — legacy row predating the validity_seconds column: getLong()
    // would return 0 for SQL NULL, indistinguishable from "no expiration",
    // so the production code checks wasNull() to detect this and falls back
    // to the default (86400 = 1 day).
    when(findRs.getLong("validity_seconds")).thenReturn(0L);
    when(findRs.wasNull()).thenReturn(true);

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, revokePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertNotNull(body.getString("access_token"));
    assertEquals("bearer", body.getString("token_type"));
    assertEquals(86400, body.getInt("expires_in"));
    assertNotNull(body.getString("refresh_token"));
    assertEquals("neo:read neo:write", body.getString("scope"));
  }

  // ===================== ETP-4393 — refresh_token validity_seconds reuse =====================

  @Test
  public void tokenRefreshReusesStoredValiditySeconds() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("valid-refresh-token");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("scopes")).thenReturn("neo:read neo:write");
    when(findRs.getString("is_revoked")).thenReturn("N");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("etendo_client_id")).thenReturn("0");
    when(findRs.getString("client_active")).thenReturn("Y");
    // Non-legacy row: the previous token was issued with a 1-week validity —
    // the refreshed token must reuse that same value, not the default.
    when(findRs.getLong("validity_seconds")).thenReturn(604_800L);
    when(findRs.wasNull()).thenReturn(false);

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, revokePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals(604_800, body.getInt("expires_in"));
  }

  @Test
  public void tokenRefreshLegacyNullRowFallsBackTo86400() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("valid-refresh-token");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("scopes")).thenReturn("neo:read");
    when(findRs.getString("is_revoked")).thenReturn("N");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("etendo_client_id")).thenReturn("0");
    when(findRs.getString("client_active")).thenReturn("Y");
    // Legacy row: column is SQL NULL (predates the ETP-4393 migration).
    when(findRs.getLong("validity_seconds")).thenReturn(0L);
    when(findRs.wasNull()).thenReturn(true);

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, revokePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals(86400, body.getInt("expires_in"));
  }

  @Test
  public void tokenRefreshNoExpirationOmitsExpiresIn() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("valid-refresh-token");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("scopes")).thenReturn("neo:read");
    when(findRs.getString("is_revoked")).thenReturn("N");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("etendo_client_id")).thenReturn("0");
    when(findRs.getString("client_active")).thenReturn("Y");
    // Stored 0 = "no expiration" sentinel — must not be confused with legacy NULL.
    when(findRs.getLong("validity_seconds")).thenReturn(0L);
    when(findRs.wasNull()).thenReturn(false);

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, revokePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertNotNull(body.getString("access_token"));
    assertFalse("expires_in must be absent when validity_seconds is 0 (no expiration)",
        body.has("expires_in"));
  }

  @Test
  public void tokenRefreshSuccessJsonBody() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("valid-refresh-token");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("scopes")).thenReturn("neo:read");
    when(findRs.getString("is_revoked")).thenReturn("N");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("etendo_client_id")).thenReturn("0");
    when(findRs.getString("client_active")).thenReturn("Y");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    PreparedStatement insertPs = mock(PreparedStatement.class);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, revokePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertNotNull(body.getString("access_token"));
    assertEquals("bearer", body.getString("token_type"));
  }

  // ===================== doPost /token — authorization_code =====================

  @Test
  public void tokenAuthCodeMissingCode() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("authorization_code");
    when(req.getParameter("code")).thenReturn(null);

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void tokenAuthCodeMissingCodeVerifier() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("authorization_code");
    when(req.getParameter("code")).thenReturn("some-code");
    when(req.getParameter("code_verifier")).thenReturn(null);

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("code_verifier"));
  }

  @Test
  public void tokenAuthCodeJsonMissingCode() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("authorization_code");
    when(req.getParameter("code")).thenReturn(null);

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  // ===================== doPost /clients — Create Client =====================

  @Test
  public void createClientMissingName() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/clients");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("adUserId", "user-1");
    requestBody.put("adRoleId", "role-1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("name"));
  }

  @Test
  public void createClientMissingAdUserId() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/clients");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Test Client");
    requestBody.put("adRoleId", "role-1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("adUserId"));
  }

  @Test
  public void createClientMissingAdRoleId() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/clients");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Test Client");
    requestBody.put("adUserId", "user-1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("adRoleId"));
  }

  @Test
  public void createClientSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/clients");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "My Client");
    requestBody.put("adUserId", "user-1");
    requestBody.put("adRoleId", "role-1");
    requestBody.put("scopes", "neo:read neo:write");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<SequenceIdData> seqMock = mockStatic(SequenceIdData.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      seqMock.when(SequenceIdData::getUUID).thenReturn("generated-uuid");

      servlet.doPost(req, resp.response);
    }

    assertEquals(201, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("generated-uuid", body.getString("id"));
    assertEquals("My Client", body.getString("name"));
    assertNotNull(body.getString("clientId"));
    assertNotNull(body.getString("clientSecret"));
    assertEquals("user-1", body.getString("adUserId"));
    assertEquals("role-1", body.getString("adRoleId"));
    assertEquals("neo:read neo:write", body.getString("scopes"));
    assertTrue(body.getBoolean("isActive"));
  }

  // ===================== doPut /clients/{id} — Update Client =====================

  @Test
  public void updateClientNullPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", null);

    servlet.doPut(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void updateClientUnknownPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", "/unknown");

    servlet.doPut(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void updateClientNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", "/clients/nonexistent-id");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPut(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("not_found", body.getString("error"));
  }

  @Test
  public void updateClientSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", "/clients/" + TEST_CLIENT_DB_ID);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Updated Name");
    requestBody.put("scopes", "neo:read neo:write neo:process");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    // findClientById returns existing client
    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("name")).thenReturn("Old Name");
    when(findRs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("scopes")).thenReturn("neo:read");
    when(findRs.getString("redirect_uris")).thenReturn("[]");
    when(findRs.getString("isactive")).thenReturn("Y");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement updatePs = mock(PreparedStatement.class);
    when(updatePs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, updatePs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPut(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals(TEST_CLIENT_DB_ID, body.getString("id"));
    assertEquals("Updated Name", body.getString("name"));
    assertEquals(TEST_CLIENT_ID, body.getString("clientId"));
  }

  /**
   * A non-boolean {@code isActive} is the caller's 400, not the 500 + ERROR stack trace a
   * {@code JSONException} from {@code getBoolean} used to fall into; nothing is written.
   */
  @Test
  public void updateClientWithNonBooleanIsActiveReturnsBadRequestQuietly() throws Exception {
    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);
    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);
    when(findRs.getString("isactive")).thenReturn("Y");
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(findRs);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         LogCapture logs = LogCapture.of(OAuth2Servlet.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      for (String raw : new String[] { "{\"isActive\":\"maybe\"}", "{\"isActive\":1}",
          "{\"isActive\":[]}", "{\"isActive\":{}}" }) {
        ResponseCapture resp = mockResponse();
        HttpServletRequest req = mockRequest("PUT", "/clients/" + TEST_CLIENT_DB_ID);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
        when(req.getContentType()).thenReturn("application/json");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(raw)));

        servlet.doPut(req, resp.response);

        assertEquals(raw, 400, resp.status);
        JSONObject error = new JSONObject(resp.body());
        assertEquals(raw, "invalid_request", error.getString("error"));
        // The JSON is well formed; the description names the field, not a malformed body.
        assertEquals(raw, "isActive must be a boolean", error.getString("error_description"));
      }
      assertEquals(List.of(), logs.messages(Level.ERROR));
      for (LogEvent warn : logs.events(Level.WARN)) {
        assertNull(warn.getMessage().getFormattedMessage(), warn.getThrown());
      }
    }
    verify(ps, never()).executeUpdate();
  }

  /** The shared reader behind PUT /clients: a client type error is malformed (PUT /api-keys is stricter, see requireBoolean). */
  @Test
  public void requireBooleanFieldRejectsNonBooleansAsMalformed() throws Exception {
    assertTrue(OAuth2Servlet.requireBooleanField(new JSONObject("{\"f\":true}"), "f"));
    assertFalse(OAuth2Servlet.requireBooleanField(new JSONObject("{\"f\":\"false\"}"), "f"));
    for (String raw : new String[] { "{\"f\":\"maybe\"}", "{\"f\":1}", "{\"f\":null}",
        "{\"f\":[]}" }) {
      try {
        OAuth2Servlet.requireBooleanField(new JSONObject(raw), "f");
        throw new AssertionError("Expected MalformedJsonException for " + raw);
      } catch (MalformedJsonException expected) {
        assertEquals("f must be a boolean", expected.getMessage());
      }
    }
  }

  // ===================== doDelete /clients/{id} =====================

  @Test
  public void deleteClientUnknownPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("DELETE", "/unknown");

    servlet.doDelete(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void deleteClientNullPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("DELETE", null);

    servlet.doDelete(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void deleteClientNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("DELETE", "/clients/nonexistent");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    PreparedStatement deleteTokensPs = mock(PreparedStatement.class);
    when(deleteTokensPs.executeUpdate()).thenReturn(0);

    PreparedStatement deleteClientPs = mock(PreparedStatement.class);
    when(deleteClientPs.executeUpdate()).thenReturn(0);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(deleteTokensPs, deleteClientPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doDelete(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("not_found", body.getString("error"));
  }

  @Test
  public void deleteClientSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("DELETE", "/clients/" + TEST_CLIENT_DB_ID);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    PreparedStatement deleteTokensPs = mock(PreparedStatement.class);
    when(deleteTokensPs.executeUpdate()).thenReturn(3);

    PreparedStatement deleteClientPs = mock(PreparedStatement.class);
    when(deleteClientPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(deleteTokensPs, deleteClientPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doDelete(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertTrue(body.getBoolean("deleted"));
    assertEquals(TEST_CLIENT_DB_ID, body.getString("id"));
  }

  // ===================== PUT /clients/{id}/regenerate-secret =====================

  @Test
  public void regenerateSecretNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", "/clients/nonexistent/regenerate-secret");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPut(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("not_found", body.getString("error"));
  }

  @Test
  public void regenerateSecretSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", "/clients/" + TEST_CLIENT_DB_ID + "/regenerate-secret");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    // findClientById
    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("name")).thenReturn("Test Client");
    when(findRs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("scopes")).thenReturn("neo:read");
    when(findRs.getString("redirect_uris")).thenReturn("[]");
    when(findRs.getString("isactive")).thenReturn("Y");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement updatePs = mock(PreparedStatement.class);
    when(updatePs.executeUpdate()).thenReturn(1);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(2);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, updatePs, revokePs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPut(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals(TEST_CLIENT_DB_ID, body.getString("id"));
    assertEquals(TEST_CLIENT_ID, body.getString("clientId"));
    assertNotNull(body.getString("clientSecret"));
    assertTrue(body.getBoolean("tokensRevoked"));
  }

  @Test
  public void regenerateSecretWithoutRevokeTokens() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("PUT", "/clients/" + TEST_CLIENT_DB_ID + "/regenerate-secret");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("revokeExistingTokens", false);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("name")).thenReturn("Test Client");
    when(findRs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("scopes")).thenReturn("neo:read");
    when(findRs.getString("redirect_uris")).thenReturn("[]");
    when(findRs.getString("isactive")).thenReturn("Y");

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement updatePs = mock(PreparedStatement.class);
    when(updatePs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, updatePs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPut(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertFalse(body.getBoolean("tokensRevoked"));
  }

  // ===================== POST /revoke =====================

  @Test
  public void revokeMissingClientId() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/revoke");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void revokeSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/revoke");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("clientId", TEST_CLIENT_ID);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(5);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(revokePs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertTrue(body.getBoolean("revoked"));
    assertEquals(TEST_CLIENT_ID, body.getString("clientId"));
    assertEquals(5, body.getInt("tokensRevoked"));
  }

  // ===================== POST /introspect =====================

  @Test
  public void introspectMissingToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/introspect");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void introspectTokenNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/introspect");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("token", "unknown-token");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertFalse(body.getBoolean("active"));
  }

  @Test
  public void introspectActiveToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/introspect");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("token", "valid-access-token");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    Timestamp futureExpiry = new Timestamp(System.currentTimeMillis() + 3600_000);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("scopes")).thenReturn("neo:read neo:write");
    when(rs.getTimestamp("expires_at")).thenReturn(futureExpiry);
    when(rs.getString("is_revoked")).thenReturn("N");
    when(rs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertTrue(body.getBoolean("active"));
    assertEquals("neo:read neo:write", body.getString("scope"));
    assertEquals(TEST_CLIENT_ID, body.getString("client_id"));
    assertTrue(body.getLong("exp") > 0);
  }

  @Test
  public void introspectRevokedToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/introspect");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("token", "revoked-token");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    Timestamp futureExpiry = new Timestamp(System.currentTimeMillis() + 3600_000);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getTimestamp("expires_at")).thenReturn(futureExpiry);
    when(rs.getString("is_revoked")).thenReturn("Y");
    when(rs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertFalse(body.getBoolean("active"));
  }

  @Test
  public void introspectExpiredToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/introspect");
    when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("token", "expired-token");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);

    Timestamp pastExpiry = new Timestamp(System.currentTimeMillis() - 3600_000);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("scopes")).thenReturn("neo:read");
    when(rs.getTimestamp("expires_at")).thenReturn(pastExpiry);
    when(rs.getString("is_revoked")).thenReturn("N");
    when(rs.getString("client_identifier")).thenReturn(TEST_CLIENT_ID);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertFalse(body.getBoolean("active"));
  }

  // ===================== malformed JSON bodies (unauthenticated endpoints) =====================

  /**
   * POST /token answers without a token, so a body that breaks jettison (StackOverflowError /
   * OutOfMemoryError, which escape every catch (Exception)) must be a plain 400, and so must any
   * other body that is not JSON: it was a 500 before.
   */
  @Test
  public void tokenMalformedJsonBodyReturnsBadRequest() throws Exception {
    assertMalformedBodiesAnswerBadRequestQuietly(null);
  }

  /** POST /register (DCR) also answers without a token. */
  @Test
  public void registerMalformedJsonBodyReturnsBadRequest() throws Exception {
    assertMalformedBodiesAnswerBadRequestQuietly("/register");
  }

  /**
   * A syntactically valid body with a missing or wrongly typed field is still the client's error:
   * a 400 without an ERROR, never the 500 that a response-building failure means.
   */
  @Test
  public void registerValidJsonWithMissingOrWrongTypedFieldsReturnsBadRequestQuietly()
      throws Exception {
    List<String> bodies = List.of("{}", "{\"redirect_uris\":\"x\"}",
        "{\"redirect_uris\":[1]}", "{\"redirect_uris\":[null]}", "{\"redirect_uris\":[[]]}",
        "{\"redirect_uris\":{}}", "{\"client_name\":{},\"scope\":[],\"redirect_uris\":[]}");
    try (LogCapture logs = LogCapture.of(OAuth2Servlet.class)) {
      for (String raw : bodies) {
        ResponseCapture resp = mockResponse();
        HttpServletRequest req = mockRequest("POST", "/register");
        when(req.getContentType()).thenReturn("application/json");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(raw)));

        servlet.doPost(req, resp.response);

        // invalid_request or invalid_client_metadata: either way the client's 400
        assertEquals(raw, 400, resp.status);
      }
      assertEquals(List.of(), logs.messages(Level.ERROR));
    }
  }

  /** POST /authorize parses its body before the caller is authenticated. */
  @Test
  public void authorizePostMalformedJsonBodyReturnsBadRequest() throws Exception {
    assertMalformedBodiesAnswerBadRequestQuietly("/authorize");
  }

  /**
   * A client's malformed body is a 400 and its own fault: anyone can send one to these
   * endpoints, so it must never log an ERROR with a stack trace (a free log flood) — at most a
   * WARN without the throwable.
   */
  private void assertMalformedBodiesAnswerBadRequestQuietly(String pathInfo) throws Exception {
    try (LogCapture logs = LogCapture.of(OAuth2Servlet.class)) {
      for (String raw : malformedBodies()) {
        ResponseCapture resp = mockResponse();
        HttpServletRequest req = mockRequest("POST", pathInfo);
        when(req.getContentType()).thenReturn("application/json");
        when(req.getReader()).thenReturn(new BufferedReader(new StringReader(raw)));

        servlet.doPost(req, resp.response);

        assertEquals(abbreviate(raw), 400, resp.status);
        assertEquals("invalid_request", new JSONObject(resp.body()).getString("error"));
      }
      assertEquals("no ERROR for a client's malformed body", List.of(),
          logs.messages(Level.ERROR));
      for (LogEvent warn : logs.events(Level.WARN)) {
        assertNull("WARN without a stack trace: " + warn.getMessage().getFormattedMessage(),
            warn.getThrown());
      }
    }
  }

  /**
   * The admin management endpoints (client create/update/regenerate-secret, revoke, introspect)
   * read a JSON body after authenticating: a malformed one is the caller's {@code invalid_request}
   * 400, not the 500 their {@code JSONException} catch means for a response-building failure.
   */
  @Test
  public void adminManagementMalformedJsonBodyReturnsBadRequest() throws Exception {
    String[][] endpoints = {
        { "POST", "/clients" },
        { "PUT", "/clients/" + TEST_CLIENT_DB_ID },
        { "PUT", "/clients/" + TEST_CLIENT_DB_ID + "/regenerate-secret" },
        { "POST", "/revoke" },
        { "POST", "/introspect" } };
    DecodedJWT jwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);
    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(jwt);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      for (String[] endpoint : endpoints) {
        for (String raw : new String[] { "{\"x\":[", "[[", "not json" }) {
          ResponseCapture resp = mockResponse();
          HttpServletRequest req = mockRequest(endpoint[0], endpoint[1]);
          when(req.getHeader("Authorization")).thenReturn("Bearer " + ADMIN_TOKEN);
          when(req.getContentType()).thenReturn("application/json");
          when(req.getReader()).thenReturn(new BufferedReader(new StringReader(raw)));

          if ("PUT".equals(endpoint[0])) {
            servlet.doPut(req, resp.response);
          } else {
            servlet.doPost(req, resp.response);
          }

          String label = endpoint[0] + " " + endpoint[1] + " " + raw;
          assertEquals(label, 400, resp.status);
          assertEquals(label, "invalid_request", new JSONObject(resp.body()).getString("error"));
        }
      }
      verify(obDal, never()).getConnection();
    }
  }

  private static List<String> malformedBodies() {
    List<String> bodies = new ArrayList<>(BoundedJsonParserTest.stackBreakingBodies().toList());
    bodies.add("not json");
    return bodies;
  }

  private static String abbreviate(String raw) {
    return raw.length() > 40 ? raw.substring(0, 40) + "..." : raw;
  }

  // ===================== POST /register (DCR) =====================

  @Test
  public void registerSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/register");
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("client_name", "MCP Test Client");
    requestBody.put("scope", "neo:read neo:write");
    requestBody.put("redirect_uris", new JSONArray().put("https://example.com/callback"));
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<SequenceIdData> seqMock = mockStatic(SequenceIdData.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      seqMock.when(SequenceIdData::getUUID).thenReturn("dcr-uuid");

      servlet.doPost(req, resp.response);
    }

    assertEquals(201, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertNotNull(body.getString("client_id"));
    assertEquals("MCP Test Client", body.getString("client_name"));
    assertEquals("none", body.getString("token_endpoint_auth_method"));
    assertEquals("neo:read neo:write", body.getString("scope"));
  }

  @Test
  public void registerMissingRedirectUris() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/register");
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("client_name", "No Redirect Client");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void registerUnsafeRedirectUri() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/register");
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("client_name", "Unsafe Redirect Client");
    requestBody.put("redirect_uris", new JSONArray().put("http://evil.com/callback"));
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  @Test
  public void registerInvalidScope() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/register");
    when(req.getContentType()).thenReturn("application/json");

    JSONObject requestBody = new JSONObject();
    requestBody.put("client_name", "Bad Scope Client");
    requestBody.put("scope", "admin:all");
    requestBody.put("redirect_uris", new JSONArray().put("https://example.com/callback"));
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody.toString())));

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_client_metadata", body.getString("error"));
  }

  // ===================== GET /authorize =====================

  @Test
  public void authorizeGetUnsupportedResponseType() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("token");

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("unsupported_response_type", body.getString("error"));
  }

  @Test
  public void authorizeGetMissingCodeChallenge() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("code");
    when(req.getParameter("code_challenge")).thenReturn(null);

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("code_challenge"));
  }

  @Test
  public void authorizeGetInvalidChallengeMethod() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("code");
    when(req.getParameter("code_challenge")).thenReturn("challenge-value");
    when(req.getParameter("code_challenge_method")).thenReturn("plain");

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("S256"));
  }

  @Test
  public void authorizeGetMissingClientId() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("code");
    when(req.getParameter("code_challenge")).thenReturn("challenge-value");
    when(req.getParameter("code_challenge_method")).thenReturn("S256");
    when(req.getParameter("client_id")).thenReturn(null);

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("client_id"));
  }

  @Test
  public void authorizeGetMissingRedirectUri() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("code");
    when(req.getParameter("code_challenge")).thenReturn("challenge-value");
    when(req.getParameter("code_challenge_method")).thenReturn("S256");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("redirect_uri")).thenReturn(null);

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
    assertTrue(body.getString("error_description").contains("redirect_uri"));
  }

  @Test
  public void authorizeGetUnknownClient() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("code");
    when(req.getParameter("code_challenge")).thenReturn("challenge-value");
    when(req.getParameter("code_challenge_method")).thenReturn("S256");
    when(req.getParameter("client_id")).thenReturn("unknown");
    when(req.getParameter("redirect_uri")).thenReturn("https://example.com/cb");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(false);

    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doGet(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_client", body.getString("error"));
  }

  /**
   * ETP-5602: the authorize step checks the request against the client's allow-list with the
   * neo:/etendo: equivalence, in both directions, without widening access.
   */
  @Test
  public void authorizeGetAcceptsScopeAliasesOfTheClientAllowList() throws Exception {
    assertFalse(authorizeGet("neo:read", "etendo:read").contains("invalid_scope"));
    assertFalse(authorizeGet("etendo:read", "neo:read").contains("invalid_scope"));
    assertTrue(authorizeGet("neo:write", "etendo:read").contains("invalid_scope"));
    assertTrue(authorizeGet("etendo:write", "neo:read").contains("invalid_scope"));
  }

  private String authorizeGet(String requestedScope, String clientScopes) throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("GET", "/authorize");
    when(req.getParameter("response_type")).thenReturn("code");
    when(req.getParameter("code_challenge")).thenReturn("challenge-value");
    when(req.getParameter("code_challenge_method")).thenReturn("S256");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("redirect_uri")).thenReturn("https://example.com/cb");
    when(req.getParameter("scope")).thenReturn(requestedScope);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(rs.getString("scopes")).thenReturn(clientScopes);
    when(rs.getString("redirect_uris")).thenReturn("[\"https://example.com/cb\"]");
    when(rs.getString("ad_client_id")).thenReturn("0");
    when(rs.getString("ad_user_id")).thenReturn("user-1");
    when(rs.getString("ad_role_id")).thenReturn("role-1");
    PreparedStatement ps = mock(PreparedStatement.class);
    when(ps.executeQuery()).thenReturn(rs);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doGet(req, resp.response);
    } catch (RuntimeException afterValidation) {
      // Past the scope check the servlet resolves the PWA URL, which this unit test does not
      // configure; reaching that point is the "accepted" outcome.
      return "";
    }
    return resp.body();
  }

  // ===================== POST unknown path =====================

  @Test
  public void doPostUnknownPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/unknown");

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertEquals("invalid_request", body.getString("error"));
  }

  // ===================== CORS =====================

  @Test
  public void serviceOptionsReturnsNoContent() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("OPTIONS", "/token");
    when(req.getMethod()).thenReturn("OPTIONS");

    servlet.service(req, resp.response);

    verify(resp.response).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  // ===================== ETP-4393 — normalizeValiditySeconds boundaries =====================

  @Test
  public void normalizeValiditySecondsZeroMeansNoExpiration() {
    assertEquals(0L, OAuth2ValidityPolicy.normalizeValiditySeconds(0L));
  }

  @Test
  public void normalizeValiditySecondsNegativeFallsBackToDefault() {
    assertEquals(86_400L, OAuth2ValidityPolicy.normalizeValiditySeconds(-1L));
  }

  @Test
  public void normalizeValiditySecondsAbsentSentinelFallsBackToDefault() {
    // -1 is the sentinel OAuth2AuthorizeSupport uses for missing/blank/non-numeric input.
    assertEquals(86_400L, OAuth2ValidityPolicy.normalizeValiditySeconds(-1L));
  }

  @Test
  public void normalizeValiditySecondsAboveMaxIsClampedDown() {
    assertEquals(2_592_000L, OAuth2ValidityPolicy.normalizeValiditySeconds(99_999_999L));
  }

  @Test
  public void normalizeValiditySecondsExactlyAtMaxIsUnchanged() {
    assertEquals(2_592_000L, OAuth2ValidityPolicy.normalizeValiditySeconds(2_592_000L));
  }

  @Test
  public void normalizeValiditySecondsBelowMinIsClampedUp() {
    assertEquals(300L, OAuth2ValidityPolicy.normalizeValiditySeconds(60L));
  }

  @Test
  public void normalizeValiditySecondsExactlyAtMinIsUnchanged() {
    assertEquals(300L, OAuth2ValidityPolicy.normalizeValiditySeconds(300L));
  }

  @Test
  public void normalizeValiditySecondsWithinRangeIsUnchanged() {
    assertEquals(604_800L, OAuth2ValidityPolicy.normalizeValiditySeconds(604_800L));
  }

  // ===== ETP-4393 — authorization_code grant: end-to-end validity_seconds propagation =====
  //
  // AUTH_CODE_STORE is private, so it cannot be seeded directly. These tests drive the real
  // two-step flow: POST /authorize (issues the code) then POST /token (exchanges it), and
  // assert on the expires_in field of the final token response.

  @Test
  public void authorizeCodeGrantDefaultValidityWhenParamAbsent() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange(null);
    assertNotNull(expiresIn);
    assertEquals(86_400L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantPreset1Day() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("86400");
    assertNotNull(expiresIn);
    assertEquals(86_400L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantPreset1Week() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("604800");
    assertNotNull(expiresIn);
    assertEquals(604_800L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantPreset1Month() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("2592000");
    assertNotNull(expiresIn);
    assertEquals(2_592_000L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantNoExpirationOmitsExpiresIn() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("0");
    assertNull("expires_in must be absent when validity_seconds=0 (no expiration)", expiresIn);
  }

  @Test
  public void authorizeCodeGrantInvalidNegativeFallsBackToDefault() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("-42");
    assertNotNull(expiresIn);
    assertEquals(86_400L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantNonNumericFallsBackToDefault() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("not-a-number");
    assertNotNull(expiresIn);
    assertEquals(86_400L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantExcessiveValueIsClampedToMax() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("99999999");
    assertNotNull(expiresIn);
    assertEquals(2_592_000L, expiresIn.longValue());
  }

  @Test
  public void authorizeCodeGrantBelowMinValueIsClampedToMin() throws Exception {
    Long expiresIn = runAuthorizeThenTokenExchange("60");
    assertNotNull(expiresIn);
    assertEquals(300L, expiresIn.longValue());
  }

  /**
   * ETP-5395 — an OAuth client authorized from a cookie session keeps the role for as long as its
   * tokens live, so the consent must not hand out a role the user no longer holds.
   */
  @Test
  public void authorizePostRefusesACookieSessionWhoseRoleWasRevoked() throws Exception {
    GoSessionService sessionService = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setUserId("user-cookie");
    session.setRoleId("revoked-role");
    session.setCtxClientId("client-cookie");
    session.setCsrfToken("csrf-cookie");
    when(sessionService.resolve("session-cookie")).thenReturn(session);
    OAuth2Servlet cookieServlet = new OAuth2Servlet(sessionService);
    GoSessionRoleReconciler reconciler = mock(GoSessionRoleReconciler.class);
    when(reconciler.reconcile(session)).thenThrow(new SessionRoleRevokedException("no role left"));
    cookieServlet.sessionRoleReconciler = reconciler;

    String redirectUri = "https://example.com/callback";
    HttpServletRequest req = mockRequest("POST", "/authorize");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("redirect_uri")).thenReturn(redirectUri);
    when(req.getParameter("code_challenge")).thenReturn("challenge");
    when(req.getParameter("scope")).thenReturn("neo:read");
    when(req.getCookies()).thenReturn(new Cookie[] {
        new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie") });
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-cookie");
    when(req.getHeader("Origin")).thenReturn("https://app.example.test");
    when(req.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/oauth2/authorize"));

    ResultSet clientRs = mock(ResultSet.class);
    when(clientRs.next()).thenReturn(true);
    when(clientRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(clientRs.getString("scopes")).thenReturn("neo:read");
    when(clientRs.getString("redirect_uris")).thenReturn("[\"" + redirectUri + "\"]");
    PreparedStatement findClientPs = mock(PreparedStatement.class);
    when(findClientPs.executeQuery()).thenReturn(clientRs);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findClientPs);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    ResponseCapture response = mockResponse();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      cookieServlet.doPost(req, response.response);
    }

    assertEquals(403, response.status);
    assertFalse(response.body().contains("code="));
    verify(reconciler).reconcile(session);
  }

  @Test
  public void authorizePostAcceptsCookieSessionWithoutJwtInBrowserPayload() throws Exception {
    GoSessionService sessionService = mock(GoSessionService.class);
    GoSessionRecord session = new GoSessionRecord();
    session.setUserId("user-cookie");
    session.setRoleId("role-cookie");
    session.setCsrfToken("csrf-cookie");
    when(sessionService.resolve("session-cookie")).thenReturn(session);
    OAuth2Servlet cookieServlet = new OAuth2Servlet(sessionService);

    String redirectUri = "https://example.com/callback";
    HttpServletRequest req = mockRequest("POST", "/authorize");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(req.getParameter("redirect_uri")).thenReturn(redirectUri);
    when(req.getParameter("code_challenge")).thenReturn("challenge");
    when(req.getParameter("scope")).thenReturn("neo:read");
    when(req.getCookies()).thenReturn(new Cookie[] {
        new Cookie(GoSessionSecurity.COOKIE_NAME, "session-cookie") });
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn("csrf-cookie");
    when(req.getHeader("Origin")).thenReturn("https://app.example.test");
    when(req.getRequestURL()).thenReturn(
        new StringBuffer("https://app.example.test/oauth2/authorize"));

    ResultSet clientRs = mock(ResultSet.class);
    when(clientRs.next()).thenReturn(true);
    when(clientRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(clientRs.getString("scopes")).thenReturn("neo:read");
    when(clientRs.getString("redirect_uris")).thenReturn("[\"" + redirectUri + "\"]");
    PreparedStatement findClientPs = mock(PreparedStatement.class);
    when(findClientPs.executeQuery()).thenReturn(clientRs);
    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findClientPs);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    ResponseCapture response = mockResponse();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      cookieServlet.doPost(req, response.response);
    }

    assertTrue(new JSONObject(response.body()).getString("redirect_url").contains("code="));
  }

  /**
   * Drives POST /authorize followed by POST /token (authorization_code grant) and returns
   * the resulting {@code expires_in} value, or {@code null} when the field is absent
   * (i.e. the token has no expiration).
   *
   * @param validitySecondsParam the form value sent as {@code validity_seconds} on the
   *     /authorize request, or {@code null} to omit the parameter entirely
   */
  private Long runAuthorizeThenTokenExchange(String validitySecondsParam) throws Exception {
    String codeVerifier = "test-code-verifier-" + java.util.UUID.randomUUID();
    String codeChallenge = buildChallenge(codeVerifier);
    String redirectUri = "https://example.com/callback";

    ResponseCapture authorizeResp = mockResponse();
    HttpServletRequest authorizeReq = mockRequest("POST", "/authorize");
    when(authorizeReq.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(authorizeReq.getParameter("token")).thenReturn(ADMIN_TOKEN);
    when(authorizeReq.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(authorizeReq.getParameter("redirect_uri")).thenReturn(redirectUri);
    when(authorizeReq.getParameter("code_challenge")).thenReturn(codeChallenge);
    when(authorizeReq.getParameter("scope")).thenReturn("neo:read");
    when(authorizeReq.getParameter("validity_seconds")).thenReturn(validitySecondsParam);

    ResultSet clientRs = mock(ResultSet.class);
    when(clientRs.next()).thenReturn(true);
    when(clientRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(clientRs.getString("client_secret_hash")).thenReturn("irrelevant-hash");
    when(clientRs.getString("scopes")).thenReturn("neo:read neo:write");
    when(clientRs.getString("redirect_uris")).thenReturn("[\"" + redirectUri + "\"]");
    when(clientRs.getString("ad_client_id")).thenReturn("0");
    when(clientRs.getString("ad_user_id")).thenReturn(ADMIN_USER_ID);
    when(clientRs.getString("ad_role_id")).thenReturn(ADMIN_ROLE_ID);

    PreparedStatement findClientPs = mock(PreparedStatement.class);
    when(findClientPs.executeQuery()).thenReturn(clientRs);

    ResultSet findByIdRs = mock(ResultSet.class);
    when(findByIdRs.next()).thenReturn(true);
    when(findByIdRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);

    PreparedStatement findByIdPs = mock(PreparedStatement.class);
    when(findByIdPs.executeQuery()).thenReturn(findByIdRs);

    PreparedStatement updatePs = mock(PreparedStatement.class);
    when(updatePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    // Call order within handleAuthorizePost: findClient() is invoked twice (once from
    // validateAuthorizeClientRequest, once directly), then handleAuthorizationCodeGrant
    // runs SQL_FIND_CLIENT_BY_IDENTIFIER, the DCR user/role UPDATE, and finally the INSERT.
    when(conn.prepareStatement(anyString())).thenReturn(
        findClientPs, findClientPs, findByIdPs, updatePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      DecodedJWT adminJwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(adminJwt);

      servlet.doPost(authorizeReq, authorizeResp.response);

      JSONObject authorizeBody = new JSONObject(authorizeResp.body());
      String code = extractQueryParam(authorizeBody.getString("redirect_url"), "code");

      ResponseCapture tokenResp = mockResponse();
      HttpServletRequest tokenReq = mockRequest("POST", "/token");
      when(tokenReq.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(tokenReq.getParameter("grant_type")).thenReturn("authorization_code");
      when(tokenReq.getParameter("code")).thenReturn(code);
      when(tokenReq.getParameter("code_verifier")).thenReturn(codeVerifier);
      when(tokenReq.getParameter("redirect_uri")).thenReturn(redirectUri);

      servlet.doPost(tokenReq, tokenResp.response);

      JSONObject tokenBody = new JSONObject(tokenResp.body());
      assertNotNull(tokenBody.getString("access_token"));
      return tokenBody.has("expires_in") ? tokenBody.getLong("expires_in") : null;
    }
  }

  private static String buildChallenge(String verifier) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  private static String extractQueryParam(String url, String name) throws Exception {
    int queryIndex = url.indexOf('?');
    String query = queryIndex >= 0 ? url.substring(queryIndex + 1) : url;
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      if (pair.substring(0, eq).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
      }
    }
    throw new IllegalStateException("Query param not found in URL: " + name);
  }

  // ===== ETP-5430 — the organization stored on the issued token must never be null =====
  //
  // etgo_oauth2_token.ad_org_id is NOT NULL, and storeToken() binds parameter 2 to it.
  // Both grants below build their ClientRecord by hand instead of going through
  // loadClient(), so each one has to resolve the organization itself: the
  // neo:public-api-owner-org:<id> scope wins, otherwise the organization the record was
  // read from. Leaving it unset made every exchange fail with
  // "null value in column ad_org_id violates not-null constraint".
  //
  // Note the owner-org scope is only asserted on the refresh path: it is not in VALID_SCOPES,
  // so /authorize rejects it as unsupported and it can never reach an authorization_code
  // exchange. On a refresh it can, because the scopes are read back from the stored token row
  // rather than from the request.

  @Test
  public void authorizeCodeGrantStoresClientOrganization() throws Exception {
    PreparedStatement insertPs = runAuthorizeCapturingTokenInsert("client-7", "ORG-OF-CLIENT");

    verify(insertPs).setString(1, "client-7");
    verify(insertPs).setString(2, "ORG-OF-CLIENT");
  }

  @Test
  public void tokenRefreshStoresOrganizationOfRotatedToken() throws Exception {
    PreparedStatement insertPs = runRefreshCapturingTokenInsert("neo:read neo:write",
        "ORG-OF-TOKEN");

    verify(insertPs).setString(2, "ORG-OF-TOKEN");
  }

  @Test
  public void tokenRefreshOwnerOrgScopeOverridesRotatedTokenOrganization() throws Exception {
    PreparedStatement insertPs = runRefreshCapturingTokenInsert(
        "neo:read neo:public-api-owner-org:ORG-FROM-SCOPE", "ORG-OF-TOKEN");

    verify(insertPs).setString(2, "ORG-FROM-SCOPE");
  }

  /**
   * Drives POST /authorize followed by POST /token (authorization_code grant) and returns the
   * mocked statement that performed the token INSERT, so the caller can verify the bound
   * parameters.
   *
   * @param adClientId the ad_client_id stored on the client record
   * @param adOrgId    the ad_org_id stored on the client record
   */
  private PreparedStatement runAuthorizeCapturingTokenInsert(String adClientId, String adOrgId)
      throws Exception {
    String codeVerifier = "test-code-verifier-" + java.util.UUID.randomUUID();
    String codeChallenge = buildChallenge(codeVerifier);
    String redirectUri = "https://example.com/callback";

    ResponseCapture authorizeResp = mockResponse();
    HttpServletRequest authorizeReq = mockRequest("POST", "/authorize");
    when(authorizeReq.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(authorizeReq.getParameter("token")).thenReturn(ADMIN_TOKEN);
    when(authorizeReq.getParameter("client_id")).thenReturn(TEST_CLIENT_ID);
    when(authorizeReq.getParameter("redirect_uri")).thenReturn(redirectUri);
    when(authorizeReq.getParameter("code_challenge")).thenReturn(codeChallenge);
    when(authorizeReq.getParameter("scope")).thenReturn("neo:read");

    ResultSet clientRs = mock(ResultSet.class);
    when(clientRs.next()).thenReturn(true);
    when(clientRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(clientRs.getString("client_secret_hash")).thenReturn("irrelevant-hash");
    when(clientRs.getString("scopes")).thenReturn("neo:read neo:write");
    when(clientRs.getString("redirect_uris")).thenReturn("[\"" + redirectUri + "\"]");
    when(clientRs.getString("ad_client_id")).thenReturn(adClientId);
    when(clientRs.getString("ad_user_id")).thenReturn(ADMIN_USER_ID);
    when(clientRs.getString("ad_role_id")).thenReturn(ADMIN_ROLE_ID);

    PreparedStatement findClientPs = mock(PreparedStatement.class);
    when(findClientPs.executeQuery()).thenReturn(clientRs);

    // SQL_FIND_CLIENT_BY_IDENTIFIER, read inside handleAuthorizationCodeGrant: it is the only
    // place the organization of the issued token can come from on this path.
    ResultSet findByIdRs = mock(ResultSet.class);
    when(findByIdRs.next()).thenReturn(true);
    when(findByIdRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findByIdRs.getString("ad_client_id")).thenReturn(adClientId);
    when(findByIdRs.getString("ad_org_id")).thenReturn(adOrgId);

    PreparedStatement findByIdPs = mock(PreparedStatement.class);
    when(findByIdPs.executeQuery()).thenReturn(findByIdRs);

    PreparedStatement updatePs = mock(PreparedStatement.class);
    when(updatePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(
        findClientPs, findClientPs, findByIdPs, updatePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      DecodedJWT adminJwt = mockJwt(ADMIN_USER_ID, ADMIN_ROLE_ID);
      swsMock.when(() -> SecureWebServicesUtils.decodeToken(ADMIN_TOKEN)).thenReturn(adminJwt);

      servlet.doPost(authorizeReq, authorizeResp.response);

      JSONObject authorizeBody = new JSONObject(authorizeResp.body());
      String code = extractQueryParam(authorizeBody.getString("redirect_url"), "code");

      ResponseCapture tokenResp = mockResponse();
      HttpServletRequest tokenReq = mockRequest("POST", "/token");
      when(tokenReq.getContentType()).thenReturn("application/x-www-form-urlencoded");
      when(tokenReq.getParameter("grant_type")).thenReturn("authorization_code");
      when(tokenReq.getParameter("code")).thenReturn(code);
      when(tokenReq.getParameter("code_verifier")).thenReturn(codeVerifier);
      when(tokenReq.getParameter("redirect_uri")).thenReturn(redirectUri);

      servlet.doPost(tokenReq, tokenResp.response);

      JSONObject tokenBody = new JSONObject(tokenResp.body());
      assertNotNull("the exchange must succeed for the INSERT to have run",
          tokenBody.getString("access_token"));
    }
    return insertPs;
  }

  /**
   * Drives a refresh_token exchange and returns the mocked statement that performed the token
   * INSERT, so the caller can verify the bound parameters.
   *
   * @param scopes the scopes stored on the token being rotated
   * @param tokenOrgId the ad_org_id of the token being rotated
   */
  private PreparedStatement runRefreshCapturingTokenInsert(String scopes, String tokenOrgId)
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("POST", "/token");
    when(req.getContentType()).thenReturn("application/x-www-form-urlencoded");
    when(req.getParameter("grant_type")).thenReturn("refresh_token");
    when(req.getParameter("refresh_token")).thenReturn("valid-refresh-token");

    ResultSet findRs = mock(ResultSet.class);
    when(findRs.next()).thenReturn(true);
    when(findRs.getString("etgo_oauth2_token_id")).thenReturn("token-1");
    when(findRs.getString("etgo_oauth2_client_id")).thenReturn(TEST_CLIENT_DB_ID);
    when(findRs.getString("scopes")).thenReturn(scopes);
    when(findRs.getString("is_revoked")).thenReturn("N");
    when(findRs.getString("ad_user_id")).thenReturn("user-1");
    when(findRs.getString("ad_role_id")).thenReturn("role-1");
    when(findRs.getString("etendo_client_id")).thenReturn("0");
    // The rotated token carries the organization the new one must inherit.
    when(findRs.getString("token_org_id")).thenReturn(tokenOrgId);
    when(findRs.getString("client_active")).thenReturn("Y");
    when(findRs.getLong("validity_seconds")).thenReturn(86_400L);
    when(findRs.wasNull()).thenReturn(false);

    PreparedStatement findPs = mock(PreparedStatement.class);
    when(findPs.executeQuery()).thenReturn(findRs);

    PreparedStatement revokePs = mock(PreparedStatement.class);
    when(revokePs.executeUpdate()).thenReturn(1);

    PreparedStatement insertPs = mock(PreparedStatement.class);
    when(insertPs.executeUpdate()).thenReturn(1);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(findPs, revokePs, insertPs);

    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPost(req, resp.response);
    }

    JSONObject body = new JSONObject(resp.body());
    assertNotNull("the refresh must succeed for the INSERT to have run",
        body.getString("access_token"));
    return insertPs;
  }

  // ===================== Public API keys (/api-keys) =====================
  //
  // The lifecycle runs against a mocked PublicApiKeyStore: these cases guard what the endpoints
  // answer. What the store does to the database (tokens revoked by SQL, not DAL) is guarded by
  // PublicApiKeyStoreTest and, against a real database, PublicApiKeyStoreIntegrationTest.

  private static final String KEY_USER_ID = "user-1";
  private static final String KEY_ROLE_ID = "role-1";
  private static final String KEY_CLIENT_ID = "client-1";
  private static final String KEY_ORG_ID = "org-1";
  private static final String READ_WRITE_SCOPES =
      "neo:public-api-key etendo:read etendo:write neo:public-api-owner-org:org-1";

  private final PublicApiKeyStore apiKeyStore = mock(PublicApiKeyStore.class);
  private final OAuth2Servlet apiKeyServlet = apiKeyServletOver(apiKeyStore);

  /** ETP-5781 — rotating a key that has issued tokens answered 500; it must revoke them. */
  @Test
  public void rotateUsedPublicApiKeyRevokesItsTokensAndReturnsANewSecret() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    when(apiKeyStore.revokeTokens("key-1", KEY_USER_ID)).thenReturn(3);

    ResponseCapture resp = callApiKeys("POST", "/api-keys/key-1/rotate", null);

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals(3, body.getInt("tokensRevoked"));
    assertTrue(body.getString("clientSecret").length() > 0);
    verify(key).setClientSecretHash(anyString());
    verify(apiKeyStore).save(key, KEY_USER_ID);
    verify(apiKeyStore).revokeTokens("key-1", KEY_USER_ID);
  }

  /** ETP-5781 — rotate answered {@code "capabilities": []}; it must return the stored ones. */
  @Test
  public void rotatePublicApiKeyReturnsTheRealCapabilities() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);

    ResponseCapture resp = callApiKeys("POST", "/api-keys/key-1/rotate", null);

    assertEquals(200, resp.status);
    assertCapabilities(new JSONObject(resp.body()), "public-api:read", "public-api:write");
  }

  /** A failed store call must not let the request commit what was already flushed. */
  @Test
  public void rotateThatFailsToRevokeTokensRollsBackTheNewSecret() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    when(apiKeyStore.revokeTokens("key-1", KEY_USER_ID)).thenThrow(new SQLException("boom"));

    assertServerErrorRollsBack(() -> callApiKeys("POST", "/api-keys/key-1/rotate", null));
  }

  @Test
  public void revokeTokensThatFailsRollsBack() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    when(apiKeyStore.revokeTokens("key-1", KEY_USER_ID)).thenThrow(new SQLException("boom"));

    assertServerErrorRollsBack(() -> callApiKeys("POST", "/api-keys/key-1/revoke-tokens", null));
  }

  @Test
  public void deleteThatFailsRollsBack() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    doThrow(new SQLException("boom")).when(apiKeyStore).delete(key);

    assertServerErrorRollsBack(() -> callApiKeys("DELETE", "/api-keys/key-1", null));
  }

  @Test
  public void rotateOfAKeyThatIsNotOwnedIsNotFound() throws Exception {
    ResponseCapture resp = callApiKeys("POST", "/api-keys/someone-else/rotate", null);

    assertEquals(404, resp.status);
    verify(apiKeyStore, never()).revokeTokens(anyString(), anyString());
  }

  @Test
  public void revokeTokensOfAKeyThatIsNotOwnedIsNotFound() throws Exception {
    ResponseCapture resp = callApiKeys("POST", "/api-keys/someone-else/revoke-tokens", null);

    assertEquals(404, resp.status);
    assertEquals("not_found", new JSONObject(resp.body()).getString("error"));
    verify(apiKeyStore, never()).revokeTokens(anyString(), anyString());
  }

  /** ETP-5781 — revoke-tokens of a used key answered 500. */
  @Test
  public void revokeTokensOfAUsedPublicApiKeyReportsHowManyWereRevoked() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    when(apiKeyStore.revokeTokens("key-1", KEY_USER_ID)).thenReturn(2);

    ResponseCapture resp = callApiKeys("POST", "/api-keys/key-1/revoke-tokens", null);

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertTrue(body.getBoolean("revoked"));
    assertEquals(2, body.getInt("tokensRevoked"));
    assertFalse("revoke-tokens never returns credential material", body.has("clientSecret"));
  }

  @Test
  public void deleteOfAUsedPublicApiKeyDeletesItThroughTheStore() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);

    ResponseCapture resp = callApiKeys("DELETE", "/api-keys/key-1", null);

    assertEquals(200, resp.status);
    assertTrue(new JSONObject(resp.body()).getBoolean("deleted"));
    verify(apiKeyStore).delete(key);
  }

  @Test
  public void deleteOfAKeyThatIsNotOwnedIsIdempotent() throws Exception {
    ResponseCapture resp = callApiKeys("DELETE", "/api-keys/gone", null);

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertFalse(body.getBoolean("deleted"));
    assertEquals("gone", body.getString("id"));
    verify(apiKeyStore, never()).delete(any());
  }

  /** ETP-5781 — PUT answered {@code "capabilities": []}; it must return the stored ones. */
  @Test
  public void updatePublicApiKeyReturnsTheRealCapabilities() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"name\":\"Renamed\"}");

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("Renamed", body.getString("name"));
    assertCapabilities(body, "public-api:read", "public-api:write");
  }

  /** ETP-5781 — malformed, empty or wrongly typed bodies answered 500 instead of 400. */
  @Test
  public void createPublicApiKeyWithInvalidJsonIsABadRequest() throws Exception {
    assertBadRequest(callApiKeys("POST", "/api-keys", "{not json"));
  }

  @Test
  public void createPublicApiKeyWithAnEmptyBodyIsABadRequest() throws Exception {
    assertBadRequest(callApiKeys("POST", "/api-keys", ""));
  }

  @Test
  public void createPublicApiKeyWithANonObjectBodyIsABadRequest() throws Exception {
    assertBadRequest(callApiKeys("POST", "/api-keys", "[\"public-api:read\"]"));
  }

  @Test
  public void createPublicApiKeyWithANonStringNameIsABadRequest() throws Exception {
    assertBadRequest(callApiKeys("POST", "/api-keys",
        "{\"name\":42,\"capabilities\":[\"public-api:read\"]}"));
  }

  @Test
  public void updatePublicApiKeyWithInvalidJsonIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":"));
  }

  @Test
  public void updatePublicApiKeyWithAnEmptyBodyIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", ""));
  }

  @Test
  public void updatePublicApiKeyWithAStringIsActiveIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":\"true\"}"));
  }

  @Test
  public void updatePublicApiKeyWithANullIsActiveIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":null}"));
  }

  @Test
  public void updatePublicApiKeyWithANonObjectBodyIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", "[1]"));
  }

  @Test
  public void updatePublicApiKeyWithANonStringNameIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", "{\"name\":42}"));
  }

  @Test
  public void updatePublicApiKeyWithANumericIsActiveIsABadRequest() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    assertBadRequest(callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":1}"));
  }

  /** ETP-5781 — reactivating skipped the 10-active-keys limit. */
  @Test
  public void reactivatingAKeyBeyondTheActiveLimitIsAConflict() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Dormant", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned = new ArrayList<>();
    owned.add(key);
    for (int i = 0; i < PublicApiKeyPolicy.MAX_ACTIVE_KEYS_PER_OWNER; i++) {
      owned.add(otherKey("other-" + i, "Other " + i, true));
    }
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":true}");

    assertEquals(409, resp.status);
    assertTrue(errorDescription(resp).contains("maximum number of active public API keys"));
    verify(apiKeyStore, never()).save(any(), anyString());
  }

  /** ETP-5781 — reactivating skipped the duplicate-name check against the other active keys. */
  @Test
  public void reactivatingAKeyWhoseNameAnotherActiveKeyTookIsAConflict() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Production", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned = Arrays.asList(key, otherKey("key-2", "PRODUCTION", true));
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":true}");

    assertEquals(409, resp.status);
    assertTrue(errorDescription(resp).contains("name already exists"));
    verify(apiKeyStore, never()).save(any(), anyString());
  }

  @Test
  public void reactivatingAKeyWithinTheLimitSucceeds() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Production", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned = Arrays.asList(key, otherKey("key-2", "Staging", true),
        otherKey("key-3", "Production", false));
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":true}");

    assertEquals(200, resp.status);
    assertTrue(new JSONObject(resp.body()).getBoolean("isActive"));
    verify(apiKeyStore).save(key, KEY_USER_ID);
  }

  /** The limit counts the OTHER active keys: one below it leaves room for this one. */
  @Test
  public void reactivatingAKeyWithOneOtherActiveKeyLessThanTheLimitSucceeds() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Dormant", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned =
        ownedWithOtherActiveKeys(key, PublicApiKeyPolicy.MAX_ACTIVE_KEYS_PER_OWNER - 1);
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":true}");

    assertEquals(200, resp.status);
    assertTrue(new JSONObject(resp.body()).getBoolean("isActive"));
    verify(apiKeyStore).save(key, KEY_USER_ID);
  }

  /**
   * An inactive key does not take part in the name check, so it may be renamed to an active
   * key's name; the collision is then caught when it is reactivated.
   */
  @Test
  public void renamingAnInactiveKeyToAnActiveKeysNameIsAcceptedUntilItIsReactivated()
      throws Exception {
    OAuth2Client key = ownedKey("key-1", "Dormant", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned = Arrays.asList(key, otherKey("key-2", "Production", true));
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture rename = callApiKeys("PUT", "/api-keys/key-1", "{\"name\":\"production\"}");

    assertEquals(200, rename.status);
    JSONObject renamed = new JSONObject(rename.body());
    assertEquals("production", renamed.getString("name"));
    assertFalse(renamed.getBoolean("isActive"));

    ResponseCapture reactivate = callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":true}");

    assertEquals(409, reactivate.status);
    assertTrue(errorDescription(reactivate).contains("name already exists"));
    assertFalse(key.isActive());
    verify(apiKeyStore, times(1)).save(key, KEY_USER_ID);
  }

  /** Deactivating never adds an active key, so it is not subject to the limit at all. */
  @Test
  public void deactivatingAKeyWhileAtTheActiveLimitSucceeds() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);
    List<OAuth2Client> owned =
        ownedWithOtherActiveKeys(key, PublicApiKeyPolicy.MAX_ACTIVE_KEYS_PER_OWNER - 1);
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"isActive\":false}");

    assertEquals(200, resp.status);
    assertFalse(new JSONObject(resp.body()).getBoolean("isActive"));
    verify(apiKeyStore).save(key, KEY_USER_ID);
    verify(apiKeyStore, never()).ownedKeys(any());
  }

  /** The SPA sends name and isActive together: a reactivation with a fresh name still counts. */
  @Test
  public void renameAndReactivateInOnePutIsCheckedAgainstTheActiveLimit() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Dormant", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned =
        ownedWithOtherActiveKeys(key, PublicApiKeyPolicy.MAX_ACTIVE_KEYS_PER_OWNER);
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1",
        "{\"name\":\"Fresh name\",\"isActive\":true}");

    assertEquals(409, resp.status);
    assertTrue(errorDescription(resp).contains("maximum number of active public API keys"));
    verify(apiKeyStore, never()).save(any(), anyString());
  }

  @Test
  public void renameAndReactivateInOnePutIsCheckedAgainstTheOtherActiveNames() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Dormant", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned = Arrays.asList(key, otherKey("key-2", "Production", true));
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1",
        "{\"name\":\" PRODUCTION \",\"isActive\":true}");

    assertEquals(409, resp.status);
    assertTrue(errorDescription(resp).contains("name already exists"));
    verify(apiKeyStore, never()).save(any(), anyString());
  }

  @Test
  public void renameAndReactivateInOnePutAppliesBothWhenNeitherConflicts() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Dormant", READ_WRITE_SCOPES, false);
    List<OAuth2Client> owned =
        ownedWithOtherActiveKeys(key, PublicApiKeyPolicy.MAX_ACTIVE_KEYS_PER_OWNER - 1);
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1",
        "{\"name\":\"Fresh name\",\"isActive\":true}");

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("Fresh name", body.getString("name"));
    assertTrue(body.getBoolean("isActive"));
    verify(apiKeyStore).save(key, KEY_USER_ID);
  }

  /** ETP-5781 decision — a case-only rename of an active key is the same key: accept it. */
  @Test
  public void caseOnlyRenameOfAnActiveKeyIsAccepted() throws Exception {
    OAuth2Client key = ownedKey("key-1", "production", READ_WRITE_SCOPES, true);
    when(apiKeyStore.ownedKeys(any())).thenReturn(Arrays.asList(key));

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"name\":\"Production\"}");

    assertEquals(200, resp.status);
    assertEquals("Production", new JSONObject(resp.body()).getString("name"));
    verify(apiKeyStore).save(key, KEY_USER_ID);
  }

  @Test
  public void renamingAnActiveKeyToAnotherActiveKeysNameIsAConflict() throws Exception {
    OAuth2Client key = ownedKey("key-1", "Staging", READ_WRITE_SCOPES, true);
    List<OAuth2Client> owned = Arrays.asList(key, otherKey("key-2", "Production", true));
    when(apiKeyStore.ownedKeys(any())).thenReturn(owned);

    ResponseCapture resp = callApiKeys("PUT", "/api-keys/key-1", "{\"name\":\"production\"}");

    assertEquals(409, resp.status);
    verify(apiKeyStore, never()).save(any(), anyString());
  }

  /** ETP-5781 decision — a role that cannot write is a permission refusal: 403, not 400. */
  @Test
  public void createWithWriteCapabilityForARoleWithoutWritableAccessIsForbidden()
      throws Exception {
    ResponseCapture resp = callApiKeys("POST", "/api-keys",
        "{\"name\":\"Writer\",\"capabilities\":[\"public-api:write\"]}");

    assertEquals(403, resp.status);
    assertEquals("public-api:write is not allowed for the active role", errorDescription(resp));
    verify(apiKeyStore, never()).create(any(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  public void createWithProcessCapabilityForARoleWithoutProcessAccessIsForbidden()
      throws Exception {
    ResponseCapture resp = callApiKeys("POST", "/api-keys",
        "{\"name\":\"Runner\",\"capabilities\":[\"public-api:process\"]}");

    assertEquals(403, resp.status);
    assertEquals("public-api:process is not allowed for the active role", errorDescription(resp));
  }

  @Test
  public void createWithAnUnknownCapabilityIsStillABadRequest() throws Exception {
    assertBadRequest(callApiKeys("POST", "/api-keys",
        "{\"name\":\"Odd\",\"capabilities\":[\"etendo:read\"]}"));
  }

  @Test
  public void createPublicApiKeyReturnsItsCapabilitiesAndTheSecretOnce() throws Exception {
    when(apiKeyStore.hasWritableWindowAccess(KEY_ROLE_ID, KEY_CLIENT_ID, KEY_ORG_ID))
        .thenReturn(true);
    OAuth2Client created = apiKey("key-new", "Integration", READ_WRITE_SCOPES, true);
    when(apiKeyStore.create(any(), eq("Integration"), anyString(), anyString(),
        eq("neo:public-api-key etendo:read etendo:write"))).thenReturn(created);

    ResponseCapture resp = callApiKeys("POST", "/api-keys",
        "{\"name\":\"  Integration \",\"capabilities\":[\"public-api:read\",\"public-api:write\"]}");

    assertEquals(201, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("key-new", body.getString("id"));
    assertTrue(body.getString("clientSecret").length() > 0);
    assertCapabilities(body, "public-api:read", "public-api:write");
  }

  @Test
  public void getPublicApiKeyReturnsTheBareKeyObject() throws Exception {
    ownedKey("key-1", "Production", READ_WRITE_SCOPES, true);

    ResponseCapture resp = callApiKeys("GET", "/api-keys/key-1", null);

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("key-1", body.getString("id"));
    assertFalse(body.has("apiKeys"));
    assertFalse(body.has("clientSecret"));
  }

  private OAuth2Servlet apiKeyServletOver(PublicApiKeyStore store) {
    EnvironmentRequestAuthenticator authenticator = mock(EnvironmentRequestAuthenticator.class);
    when(authenticator.authenticate(any(), eq(SurfacePolicy.NEO_DATA))).thenReturn(
        EnvironmentAuthOutcome.authenticated(AuthScheme.COOKIE, mock(OBContext.class),
            KEY_USER_ID, KEY_ROLE_ID, KEY_CLIENT_ID, KEY_ORG_ID));
    OAuth2Servlet created = new OAuth2Servlet(mock(GoSessionService.class), authenticator);
    created.publicApiKeyStore = store;
    return created;
  }

  private ResponseCapture callApiKeys(String method, String path, String body) throws Exception {
    HttpServletRequest req = mockRequest(method, path);
    if (body != null) {
      when(req.getContentType()).thenReturn("application/json");
      when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
    }
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ignored = mockStatic(OBContext.class)) {
      if ("GET".equals(method)) {
        apiKeyServlet.doGet(req, resp.response);
      } else if ("POST".equals(method)) {
        apiKeyServlet.doPost(req, resp.response);
      } else if ("PUT".equals(method)) {
        apiKeyServlet.doPut(req, resp.response);
      } else {
        apiKeyServlet.doDelete(req, resp.response);
      }
    }
    return resp;
  }

  /** A key the store finds as owned by the caller under {@code id}. */
  private OAuth2Client ownedKey(String id, String name, String scopes, boolean active) {
    OAuth2Client key = apiKey(id, name, scopes, active);
    when(apiKeyStore.findOwned(eq(id), any())).thenReturn(key);
    return key;
  }

  /** The key itself plus {@code count} other active keys with distinct names. */
  private static List<OAuth2Client> ownedWithOtherActiveKeys(OAuth2Client key, int count) {
    List<OAuth2Client> owned = new ArrayList<>();
    owned.add(key);
    for (int i = 0; i < count; i++) {
      owned.add(otherKey("other-" + i, "Other " + i, true));
    }
    return owned;
  }

  private static OAuth2Client otherKey(String id, String name, boolean active) {
    return apiKey(id, name, READ_WRITE_SCOPES, active);
  }

  /** A key mock whose name and active flag behave like entity properties. */
  private static OAuth2Client apiKey(String id, String name, String scopes, boolean active) {
    Map<String, Object> state = new HashMap<>();
    state.put("name", name);
    state.put("active", active);
    OAuth2Client key = mock(OAuth2Client.class);
    when(key.getId()).thenReturn(id);
    when(key.getClientIdentifier()).thenReturn("etgo-" + id);
    when(key.getScopes()).thenReturn(scopes);
    when(key.getName()).thenAnswer(inv -> state.get("name"));
    when(key.isActive()).thenAnswer(inv -> state.get("active"));
    doAnswer(inv -> state.put("name", inv.getArgument(0))).when(key).setName(anyString());
    doAnswer(inv -> state.put("active", inv.getArgument(0))).when(key).setActive(any());
    return key;
  }

  private interface ApiKeyCall {
    ResponseCapture run() throws Exception;
  }

  private static void assertServerErrorRollsBack(ApiKeyCall call) throws Exception {
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      ResponseCapture resp = call.run();

      assertEquals(500, resp.status);
      assertEquals("server_error", new JSONObject(resp.body()).getString("error"));
      verify(dal).rollbackAndClose();
    }
  }

  private void assertBadRequest(ResponseCapture resp) throws Exception {
    assertEquals(400, resp.status);
    assertEquals("invalid_request", new JSONObject(resp.body()).getString("error"));
    verify(apiKeyStore, never()).save(any(), anyString());
    verify(apiKeyStore, never()).create(any(), anyString(), anyString(), anyString(), anyString());
  }

  private static void assertCapabilities(JSONObject key, String... expected) throws Exception {
    JSONArray capabilities = key.getJSONArray("capabilities");
    assertEquals(expected.length, capabilities.length());
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], capabilities.getString(i));
    }
  }

  private static String errorDescription(ResponseCapture resp) throws Exception {
    return new JSONObject(resp.body()).getString("error_description");
  }

  // ===================== Helpers =====================

  private static HttpServletRequest mockRequest(String method, String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    when(request.getMethod()).thenReturn(method);
    return request;
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(inv -> {
      capture.contentType = inv.getArgument(0);
      return null;
    }).when(response).setContentType(anyString());
    doAnswer(inv -> {
      capture.encoding = inv.getArgument(0);
      return null;
    }).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  private static DecodedJWT mockJwt(String userId, String roleId) {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim userClaim = mock(Claim.class);
    when(userClaim.asString()).thenReturn(userId);
    Claim roleClaim = mock(Claim.class);
    when(roleClaim.asString()).thenReturn(roleId);
    when(jwt.getClaim("user")).thenReturn(userClaim);
    when(jwt.getClaim("role")).thenReturn(roleClaim);
    Claim clientClaim = mock(Claim.class);
    when(clientClaim.asString()).thenReturn("client-1");
    Claim organizationClaim = mock(Claim.class);
    when(organizationClaim.asString()).thenReturn("org-1");
    when(jwt.getClaim("client")).thenReturn(clientClaim);
    when(jwt.getClaim("organization")).thenReturn(organizationClaim);
    return jwt;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;
    String contentType;
    String encoding;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
