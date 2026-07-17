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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
import java.sql.Timestamp;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.PublicUrlResolver;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;

/**
 * Unit tests for {@link OAuth2Servlet}.
 * Covers token endpoints, client CRUD, revocation, introspection,
 * authorization code flow, refresh token, and dynamic client registration.
 */
public class OAuth2ServletTest {

  private static final String ADMIN_TOKEN = "admin-jwt-token";
  private static final String ADMIN_USER_ID = "0";
  private static final String ADMIN_ROLE_ID = "0";
  private static final String TEST_CLIENT_ID = "etgo-test123456";
  private static final String TEST_CLIENT_DB_ID = "uuid-client-1";

  private final OAuth2Servlet servlet = new OAuth2Servlet();

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
