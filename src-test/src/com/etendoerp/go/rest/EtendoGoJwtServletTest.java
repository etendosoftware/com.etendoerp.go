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
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * Unit tests for {@link EtendoGoJwtServlet}.
 */
public class EtendoGoJwtServletTest {

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();

  // ===================== doGet routing =====================

  @Test
  public void doGetUnknownPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/unknown");

    servlet.doGet(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertTrue(body.has("error"));
  }

  @Test
  public void doPostUnknownPathReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/unknown");

    servlet.doPost(req, resp.response);

    JSONObject body = new JSONObject(resp.body());
    assertTrue(body.has("error"));
  }

  // ===================== POST /register =====================

  @Test
  public void registerMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/register");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"email\":\"a@b.com\"}")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void registerEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/register");
    when(req.getContentType()).thenReturn("application/json");
    JSONObject body = new JSONObject();
    body.put("email", "");
    body.put("password", "pass");
    body.put("name", "Test");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void registerExistingEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/register");
    when(req.getContentType()).thenReturn("application/json");
    JSONObject body = new JSONObject();
    body.put("email", "exists@test.com");
    body.put("password", "pass123");
    body.put("name", "Test User");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));

    Account existingAccount = mock(Account.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("exists@test.com"))
          .thenReturn(existingAccount);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertTrue(respBody.toString().contains("already registered"));
  }

  @Test
  public void registerSuccessCreatesAccount() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/register");
    when(req.getContentType()).thenReturn("application/json");
    JSONObject body = new JSONObject();
    body.put("email", "new@test.com");
    body.put("password", "pass123");
    body.put("name", "New User");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("new@test.com");
    when(account.getName()).thenReturn("New User");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("new@test.com"))
          .thenReturn(null);
      dalMock.when(() -> EtendoGoJwtDalHelper.createAccount(
          anyString(), anyString(), anyString(), anyString()))
          .thenReturn(account);

      servlet.doPost(req, resp.response);
    }

    assertEquals(201, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
    assertNotNull(respBody.getString("token"));
  }

  @Test
  public void registerInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/register");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  // ===================== POST /login =====================

  @Test
  public void loginMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("{\"email\":\"a@b.com\"}")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void loginInvalidCredentialsReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getContentType()).thenReturn("application/json");
    JSONObject body = new JSONObject();
    body.put("email", "user@test.com");
    body.put("password", "wrong");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void loginInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("bad json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  // ===================== GET /me =====================

  @Test
  public void meMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/me");

    servlet.doGet(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void meInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/me");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("bad-token"))
          .thenReturn(null);

      servlet.doGet(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void meSuccessReturnsAccountInfo() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/me");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("Test User");
    when(account.getCreationDate()).thenReturn(new Date());

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);

      servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("acct-1", respBody.getString("id"));
    assertEquals("user@test.com", respBody.getString("email"));
  }

  // ===================== GET /environments =====================

  @Test
  public void environmentsMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");

    servlet.doGet(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void environmentsInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("bad-token"))
          .thenReturn(null);

      servlet.doGet(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void environmentsSuccessReturnsEnvironments() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenReturn(Collections.emptyList());

      servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertNotNull(respBody.getJSONArray("environments"));
  }

  // ===================== GET /login (environment login) =====================

  @Test
  public void envLoginMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getMethod()).thenReturn("GET");

    servlet.doGet(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void envLoginMissingUserIdReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn(null);

    servlet.doGet(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void envLoginInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    Connection conn = mock(Connection.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<EtendoGoJwtSupport> supportMock = mockStatic(EtendoGoJwtSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      supportMock.when(() -> EtendoGoJwtSupport.requireAccountEmail(conn, "bad-token"))
          .thenReturn(null);

      servlet.doGet(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void envLoginUserNotOwnedReturnsForbidden() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("other-user");

    Connection conn = mock(Connection.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<EtendoGoJwtSupport> supportMock = mockStatic(EtendoGoJwtSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      supportMock.when(() -> EtendoGoJwtSupport.requireAccountEmail(conn, "valid-token"))
          .thenReturn("user@test.com");
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          conn, "user@test.com", "other-user"))
          .thenReturn(false);

      servlet.doGet(req, resp.response);
    }

    assertEquals(403, resp.status);
  }

  // ===================== POST /onboarding =====================

  @Test
  public void onboardingMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");

    servlet.doPost(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void onboardingInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    Connection conn = mock(Connection.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getConnection()).thenReturn(conn);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<EtendoGoJwtSupport> supportMock = mockStatic(EtendoGoJwtSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      supportMock.when(() -> EtendoGoJwtSupport.requireAccountEmail(conn, "bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  // ===================== Helpers =====================

  private static HttpServletRequest mockRequest(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
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
