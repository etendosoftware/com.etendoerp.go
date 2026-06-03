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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import java.util.Base64;
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
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
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

      servletWithEmailSender.doPost(req, resp.response);
    }

    assertEquals(201, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
    assertNotNull(respBody.getString("token"));
    verify(emailSender).sendNewAccount(account);
  }

  @Test
  public void registerEmailFailureDoesNotExposeProviderFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/register");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
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
    doThrow(new RuntimeException("provider unavailable"))
        .when(emailSender).sendNewAccount(account);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("new@test.com"))
          .thenReturn(null);
      dalMock.when(() -> EtendoGoJwtDalHelper.createAccount(
          anyString(), anyString(), anyString(), anyString()))
          .thenReturn(account);

      servletWithEmailSender.doPost(req, resp.response);
    }

    assertEquals(201, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
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

  // ===================== POST /password-reset/request =====================

  @Test
  public void passwordResetRequestUnknownEmailReturnsNeutralSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"email\":\"missing@test.com\"}")));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("missing@test.com"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
    assertTrue(respBody.getString("message").contains("If an account exists"));
  }

  @Test
  public void passwordResetRequestKnownEmailStoresTokenAndSendsEmail() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"email\":\"user@test.com\"}")));

    Account account = mock(Account.class);
    when(emailSender.sendPasswordReset(eq(account), anyString(), anyString()))
        .thenReturn(true);
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.capturePasswordResetToken(account))
          .thenCallRealMethod();

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          eq(account), anyString(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    verify(emailSender).sendPasswordReset(eq(account), anyString(), anyString());
  }

  @Test
  public void passwordResetRequestEmailFailureStillReturnsNeutralSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"email\":\"user@test.com\"}")));

    Account account = mock(Account.class);
    when(emailSender.sendPasswordReset(eq(account), anyString(), anyString()))
        .thenReturn(false);
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.capturePasswordResetToken(account))
          .thenCallRealMethod();

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.restorePasswordResetToken(
          eq(account), any(EtendoGoJwtDalHelper.PasswordResetTokenState.class)));
    }

    assertEquals(200, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
  }

  @Test
  public void passwordResetRequestProviderExceptionRestoresPreviousToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"email\":\"user@test.com\"}")));

    Account account = mock(Account.class);
    when(emailSender.sendPasswordReset(eq(account), anyString(), anyString()))
        .thenThrow(new RuntimeException("provider unavailable"));
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.capturePasswordResetToken(account))
          .thenCallRealMethod();

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.restorePasswordResetToken(
          eq(account), any(EtendoGoJwtDalHelper.PasswordResetTokenState.class)));
    }

    assertEquals(200, resp.status);
  }

  // ===================== POST /password-reset/confirm =====================

  @Test
  public void passwordResetConfirmInvalidTokenReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/confirm");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"token\":\"bad-token\",\"password\":\"new-pass\"}")));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash(
          anyString(), any(Date.class))).thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmValidTokenChangesPasswordAndClearsSession() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/confirm");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"token\":\"valid-token\",\"password\":\"new-pass\"}")));

    Account account = mock(Account.class);
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash(
          anyString(), any(Date.class))).thenReturn(account);

      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.consumePasswordReset(
          eq(account), anyString(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
  }

  // ===================== POST /change-password =====================

  @Test
  public void changePasswordWrongCurrentPasswordReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"currentPassword\":\"wrong\",\"newPassword\":\"new-pass\"}")));

    Account account = mock(Account.class);
    when(account.getPasswordHash()).thenReturn(testPasswordHash("old-pass"));
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordValidCurrentPasswordRotatesTokenAndSendsNotice() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"currentPassword\":\"old-pass\",\"newPassword\":\"new-pass\"}")));

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("User Test");
    when(account.getPasswordHash()).thenReturn(testPasswordHash("old-pass"));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), anyString(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject respBody = new JSONObject(resp.body());
    assertEquals("success", respBody.getString("status"));
    assertNotNull(respBody.getString("token"));
    verify(emailSender).sendPasswordChanged(account);
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

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtSupport> supportMock = mockStatic(EtendoGoJwtSupport.class)) {
      supportMock.when(() -> EtendoGoJwtSupport.requireAccountEmail("bad-token"))
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

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtSupport> supportMock = mockStatic(EtendoGoJwtSupport.class)) {
      supportMock.when(() -> EtendoGoJwtSupport.requireAccountEmail("valid-token"))
          .thenReturn("user@test.com");
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@test.com", "other-user"))
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

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtSupport> supportMock = mockStatic(EtendoGoJwtSupport.class)) {
      supportMock.when(() -> EtendoGoJwtSupport.requireAccountEmail("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  // ===================== Helpers =====================

  private static String testPasswordHash(String password) throws Exception {
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(salt);
    byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(salt) + ":"
        + Base64.getEncoder().encodeToString(hash);
  }

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
