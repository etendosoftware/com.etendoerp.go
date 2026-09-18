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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingForceTestModeService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentPlanCache;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Additional unit tests for {@link EtendoGoJwtServlet} targeting branch and exception
 * paths that the primary {@code EtendoGoJwtServletTest} suite does not exercise:
 * login/register success and database-error paths, the SSO update/conflict/error branches,
 * the change-password and password-reset validation branches, the /environments data-mapping
 * loop, the GET /login (environment login) success and user-not-found paths, and the
 * onboarding pre-flight helpers (resolveCurrencyId, parseOnboardingRequest,
 * resolveOnboardingAccountEmail, writeEnvironmentLoginResponse).
 *
 * <p>It also owns the specs for {@code applyPaidUpgradeSideEffects} (ETP-5046): which store records
 * a paid tenant's plan, and that the per-tenant retirement of the legacy ETGO_TenantPlan preference
 * can never fail an upgrade that has already been paid for.
 */
public class EtendoGoJwtServletCoverageTest {

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();

  // ===================== POST /register — error path =====================

  @Test
  public void registerDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/register",
        "{\"email\":\"new@test.com\",\"password\":\"Str0ng!Pass1\",\"name\":\"New User\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("new@test.com"))
          .thenReturn(null);
      dalMock.when(() -> EtendoGoJwtDalHelper.createAccount(
          anyString(), anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /login — success path =====================

  @Test
  public void loginValidCredentialsReturnsToken() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/login",
        "{\"email\":\"user@test.com\",\"password\":\"secret\"}");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("User Test");
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.updateSessionToken(eq(account), anyString()));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("success", body.getString("status"));
    assertNotNull(body.getString("token"));
  }

  @Test
  public void loginNoLocalPasswordReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/login",
        "{\"email\":\"sso@test.com\",\"password\":\"secret\"}");

    Account account = mock(Account.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("sso@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void loginDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/login",
        "{\"email\":\"user@test.com\",\"password\":\"secret\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /sso/google — update / conflict / error =====================

  @Test
  public void ssoGoogleExistingSsoIdentityUpdatesSession() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/sso/google");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"credential\":\"id-token\"}")));
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "google-sub",
        "user@gmail.com", "Google User", true);
    EtendoGoJwtServlet ssoServlet = new EtendoGoJwtServlet(new TransactionalAuthEmailSender(),
        (request, rawBody) -> assertion);

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@gmail.com");
    when(account.getName()).thenReturn("Google User");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "google-sub"))
          .thenReturn(account);

      ssoServlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.updateSsoSession(
          eq(account), eq("user@gmail.com"), anyString(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("sso", body.getString("authMethod"));
  }

  @Test
  public void ssoGoogleConflictingExistingSsoLinkReturnsConflict() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/sso/google");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"credential\":\"id-token\"}")));
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "google-sub",
        "user@gmail.com", "Google User", true);
    EtendoGoJwtServlet ssoServlet = new EtendoGoJwtServlet(new TransactionalAuthEmailSender(),
        (request, rawBody) -> assertion);

    Account account = mock(Account.class);
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "google-sub"))
          .thenReturn(null);
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@gmail.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.linkSsoIdentityIfCompatible(account,
          "google", "google-sub", "user@gmail.com")).thenReturn(false);

      ssoServlet.doPost(req, resp.response);
    }

    assertEquals(409, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("already linked"));
  }

  @Test
  public void ssoGoogleDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/sso/google");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"credential\":\"id-token\"}")));
    EtendoGoSsoAssertion assertion = new EtendoGoSsoAssertion("google", "google-sub",
        "user@gmail.com", "Google User", true);
    EtendoGoJwtServlet ssoServlet = new EtendoGoJwtServlet(new TransactionalAuthEmailSender(),
        (request, rawBody) -> assertion);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "google-sub"))
          .thenThrow(new RuntimeException("db down"));

      ssoServlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /change-password — validation branches =====================

  @Test
  public void changePasswordMissingTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"b\"}");

    servlet.doPost(req, resp.response);

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/change-password");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"currentPassword\":\"a\"}")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"\",\"newPassword\":\"\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void changePasswordInvalidTokenReturnsUnauthorized() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(401, resp.status);
  }

  @Test
  public void changePasswordDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"a\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /password-reset/request — validation branches ===========

  @Test
  public void passwordResetRequestInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/request");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestMissingEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request", "{}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestEmptyEmailReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request", "{\"email\":\"\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetRequestDatabaseErrorReturnsNeutralSuccess() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"user@test.com\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  /**
   * ETP-5115 / AUTH-05. This test used to assert the opposite: that an account with no local
   * password got no token and no email. That was the bug, pinned as if it were the contract — every
   * SSO-created account has no local password by design, so the one flow that exists to recover
   * access was a silent no-op for exactly the people who could not get in. It now issues the same
   * token and mails the same link, through the set-password contract rather than reset-password,
   * because the account is being asked to create a first password and not to restore a forgotten
   * one. Rewritten rather than deleted, so the regression cannot come back unnoticed.
   */
  @Test
  public void passwordResetRequestKnownEmailWithoutLocalPasswordIssuesSetPasswordLink()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"sso@test.com\"}");

    Account account = mock(Account.class);
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendSetPassword(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    // The link builder is pinned rather than left to read the ambient app base URL, same reason as
    // registerSuccessCreatesAccount: without it the outcome depends on whether the machine running
    // the suite has etendo.go.app.baseUrl set, and a null link makes the send be skipped entirely.
    // Pinning PublicUrlResolver instead would not work — it would also stub appendPath, which the
    // builder uses, so the link would come back null anyway.
    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("sso@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          any(Account.class), anyString(), any(Date.class)));
      verify(emailSender).sendSetPassword(eq(account), anyString(), anyString(), any());
      verify(emailSender, never()).sendPasswordReset(any(), anyString(), anyString(), any());
    }
    // The response is the same neutral body an unknown address gets. Varying it by account state
    // would tell an anonymous prober both that the address exists and which provider it uses.
    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  // ===================== POST /password-reset/confirm — validation branches ===========

  @Test
  public void passwordResetConfirmInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/password-reset/confirm");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmMissingFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm", "{\"token\":\"t\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmEmptyFieldsReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm",
        "{\"token\":\"\",\"password\":\"\"}");

    servlet.doPost(req, resp.response);

    assertEquals(400, resp.status);
  }

  @Test
  public void passwordResetConfirmDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/confirm",
        "{\"token\":\"valid-token\",\"password\":\"Str0ng!Pass1\"}");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash(
          anyString(), any(Date.class))).thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== GET /me — database error =====================

  @Test
  public void meDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/me");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== GET /environments — data-mapping loop =====================

  @Test
  public void environmentsMapsUsersWithAndWithoutOrganizations() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    User userWithOrgs = mock(User.class);
    Client clientWithOrgs = mock(Client.class);
    when(clientWithOrgs.getId()).thenReturn("client-1");
    when(userWithOrgs.getClient()).thenReturn(clientWithOrgs);

    User userWithoutOrgs = mock(User.class);
    Client clientWithoutOrgs = mock(Client.class);
    when(clientWithoutOrgs.getId()).thenReturn("client-2");
    when(userWithoutOrgs.getClient()).thenReturn(clientWithoutOrgs);

    Organization org = mock(Organization.class);

    List<User> users = new ArrayList<>();
    users.add(userWithOrgs);
    users.add(userWithoutOrgs);

    // The environment list resolves every tenant's plan through ONE subscription query built
    // before the sort. Stubbed here so this mapping spec stays a pure unit test.
    SubscriptionService subscriptionService = mock(SubscriptionService.class);
    when(subscriptionService.findOpenForClients(any())).thenReturn(java.util.Map.of());
    servlet.subscriptionService = subscriptionService;

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenReturn(users);
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations("client-1"))
          .thenReturn(Collections.singletonList(org));
      dalMock.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations("client-2"))
          .thenReturn(Collections.emptyList());
      dalMock.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          any(Client.class), any(), any(User.class), any(EnvironmentPlanCache.class)))
          .thenReturn(new JSONObject());

      servlet.doGet(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          eq(clientWithOrgs), eq(org), eq(userWithOrgs), any(EnvironmentPlanCache.class)));
      dalMock.verify(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(
          eq(clientWithoutOrgs), eq(null), eq(userWithoutOrgs),
          any(EnvironmentPlanCache.class)));
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals(2, body.getJSONArray("environments").length());
    // The account email is the backend's flag-targeting key, returned so the web client can target
    // on the same identity without a second call to /me (ETP-4686).
    assertEquals("user@test.com", body.getString("accountEmail"));
  }

  @Test
  public void environmentsDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/environments");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("user@test.com"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== GET /login (environment login) — success / not found ========

  @Test
  public void envLoginSuccessGeneratesJwt() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    User user = mock(User.class);
    Role role = mock(Role.class);
    EtendoGoJwtSupport.RoleListData roleListData =
        new EtendoGoJwtSupport.RoleListData("role-1", new JSONArray());

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(user);
    when(obDal.get(Role.class, "role-1")).thenReturn(role);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class);
         var swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubAuthenticatedAccount(dalMock);
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@test.com", "user-1")).thenReturn(true);
      supportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(user, role))
          .thenReturn("jwt-token");

      servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body());
    assertEquals("jwt-token", body.getString("token"));
    assertNotNull(body.getJSONArray("roleList"));
  }

  @Test
  public void envLoginUserNotFoundReturnsNotFound() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    EtendoGoJwtSupport.RoleListData roleListData =
        new EtendoGoJwtSupport.RoleListData(null, new JSONArray());

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(null);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubAuthenticatedAccount(dalMock);
      supportMock.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@test.com", "user-1")).thenReturn(true);
      supportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);

      servlet.doGet(req, resp.response);
    }

    assertEquals(404, resp.status);
  }

  @Test
  public void envLoginDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/login");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getParameter("userId")).thenReturn("user-1");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doGet(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  // ===================== POST /onboarding — pre-flight branches =====================

  @Test
  public void onboardingTokenValidationDatabaseErrorReturnsServerError() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
          .thenThrow(new RuntimeException("db down"));

      servlet.doPost(req, resp.response);
    }

    assertEquals(500, resp.status);
  }

  @Test
  public void onboardingMissingClientNameReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding", "{}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("clientName"));
  }

  @Test
  public void onboardingEmptyClientNameReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding", "{\"clientName\":\"  \"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void onboardingInvalidJsonReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mockRequest("/onboarding");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader("not json")));

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
  }

  @Test
  public void onboardingUnknownCurrencyReturnsBadRequest() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"XYZ\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("XYZ"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    assertTrue(new JSONObject(resp.body()).toString().contains("Unknown currency"));
  }

  @Test
  public void onboardingExistingClientOwnedByAnotherAccountStreamsFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      // Existing client owned by ANOTHER account -> resume refused (tenant isolation, ETP-4428).
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName("Acme"))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(false);

      servlet.doPost(req, resp.response);
    }

    // NDJSON stream: the servlet sets 200 before streaming, then emits a failure result line.
    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("already in use"));
  }

  @Test
  public void onboardingExistingClientMissingAdminRoleStreamsFailure() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName("Acme"))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      // No admin user-role for the resolved client -> resolveAdminContextData fails.
      dalMock.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("Admin role"));
  }

  @Test
  public void onboardingExistingClientResumesUntilDatasetOrgMissing() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/onboarding",
        "{\"clientName\":\"Acme\",\"currency\":\"EUR\",\"language\":\"en_US\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("currency-1");

    UserRoles adminUserRole = mock(UserRoles.class);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("role-1");
    User contact = mock(User.class);
    when(contact.getId()).thenReturn("user-1");
    when(adminUserRole.getRole()).thenReturn(role);
    when(adminUserRole.getUserContact()).thenReturn(contact);

    try (var ctxMock = mockStatic(OBContext.class);
         var supportMock = mockStatic(EtendoGoJwtSupport.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      stubAuthenticatedAccount(dalMock);
      dalMock.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR"))
          .thenReturn(currency);
      supportMock.when(() -> EtendoGoJwtSupport.findClientIdByName("Acme"))
          .thenReturn("client-1");
      dalMock.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail("client-1", "user@test.com"))
          .thenReturn(true);
      dalMock.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("client-1"))
          .thenReturn(adminUserRole);
      supportMock.when(() -> EtendoGoJwtSupport.findStarOrgId("client-1"))
          .thenReturn("star-org");
      // Organization already exists (resume path) but no first org can be resolved afterwards.
      supportMock.when(() -> EtendoGoJwtSupport.organizationExists("client-1"))
          .thenReturn(true);
      dalMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);
    }

    String ndjson = resp.body();
    assertTrue(ndjson.contains("\"step\":\"organization\""));
    assertTrue(ndjson.contains("\"success\":false"));
    assertTrue(ndjson.contains("Organization not found"));
  }

  // ===================== applySocialName() — ETP-4749 =====================
  //
  // AD_Org.SocialName ("Nombre comercial" in the Organization settings window) was never
  // set anywhere in the onboarding flow — InitialOrgSetup/InitialSetupUtility (Etendo core)
  // only set Name/SearchKey. applySocialName() reuses the same clientName already used for
  // Name (which the wizard's CompanyStep.jsx already resolves to the user's Full Name for
  // Freelancers, since that business type has no separate Company Name field) and persists
  // it once, right after organization creation succeeds — never as part of
  // OnboardingOrgInfoService's idempotent reconcile chain, so a resumed/retried onboarding
  // call never overwrites a "Nombre comercial" the user already edited by hand.

  @Test
  public void applySocialNameSetsSocialNameAndSavesWhenOrganizationFound() {
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Acme Corp");

      assertTrue(result);
      verify(org).setSocialName("Acme Corp");
      verify(dal).save(org);
      verify(dal).flush();
    }
  }

  @Test
  public void applySocialNameUsesTheFreelancerFullNameFallbackAlreadyResolvedByTheWizard() {
    // CompanyStep.jsx (schema_forge_core/packages/etendo-go-core) already resolves clientName
    // to the Freelancer's Full Name before this ever reaches Java — applySocialName has no
    // businessType branching of its own, it just persists whatever clientName it is given.
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Jane Freelancer");

      assertTrue(result);
      verify(org).setSocialName("Jane Freelancer");
    }
  }

  @Test
  public void applySocialNameReturnsFalseAndDoesNotSaveWhenOrganizationNotFound() {
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(null);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "Acme Corp");

      assertFalse(result);
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  @Test
  public void applySocialNameHasNoBlankGuardUnlikeApplyTaxId() {
    // Unlike OnboardingOrgInfoService.applyTaxId() (a deliberate no-op on blank, because
    // Tax ID is genuinely optional), the clientName reaching this method is guaranteed
    // non-blank by parseOnboardingRequest()'s upstream validation (FIELD_CLIENT_NAME must
    // not be empty — see parseOnboardingRequest's own validation branch). This method
    // intentionally carries no blank guard of its own: a blank value would still be
    // persisted as-is. Locking this in so a future "harmonize with applyTaxId" refactor
    // doesn't silently mask an upstream validation bug behind a no-op here.
    Organization org = mock(Organization.class);
    OBDal dal = mock(OBDal.class);

    try (var dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
         var obDalMock = mockStatic(OBDal.class)) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("client-1"))
          .thenReturn(org);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      boolean result = servlet.applySocialName("client-1", "");

      assertTrue(result);
      verify(org).setSocialName("");
      verify(dal).save(org);
      verify(dal).flush();
    }
  }

  // ===================== POST /change-password — ETP-5115 enrolment =====================
  //
  // An account created through an identity provider has passwordHash null by design and could
  // not give itself a local password from inside the app. currentPassword used to be read as a
  // required field while parsing the body, so such a caller — who has nothing to put there — was
  // rejected for missing credentials before anything ever looked at the account, which made the
  // NO_LOCAL_PASSWORD branch unreachable by the very accounts it described. It is now read with
  // optString and whether it is required is decided once the account is known.

  /**
   * ETP-5115. This test previously asserted the opposite — that an account with no local password
   * got 400 NO_LOCAL_PASSWORD. That branch is gone: such an account is enrolling, and the bearer
   * token already proves who is asking, so nothing is verified. Rewritten rather than deleted so
   * the dead end cannot come back unnoticed. Note the old assertion had also stopped proving its
   * own point — its newPassword was "b", which the strength policy rejects before the account is
   * ever looked up, so the 400 it saw no longer came from the branch named in the method.
   */
  @Test
  public void changePasswordWithoutLocalPasswordEnrolsAndMailsPasswordAdded() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-sso");
    when(account.getEmail()).thenReturn("sso@test.com");
    when(account.getName()).thenReturn("SSO User");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(false);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), sessionToken.capture(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals("success", json.getString("status"));
    // The session token handed to the DAL is freshly generated and is the one returned to the
    // caller — enrolling must rotate the session exactly as changing does.
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches("[0-9a-f]{32}"));
    // "Your password was changed" is alarming and wrong for somebody who just created a first one.
    verify(emailSender).sendPasswordAdded(account);
    verify(emailSender, never()).sendPasswordChanged(any());
    verify(emailSender, never()).sendPasswordChanged(any(), anyString());
  }

  @Test
  public void changePasswordWithLocalPasswordMailsPasswordChangedAndRotatesSession()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"secret\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("acct-1");
    when(account.getEmail()).thenReturn("user@test.com");
    when(account.getName()).thenReturn("User Test");
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          eq(account), anyString(), sessionToken.capture(), any(Date.class)));
    }

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches("[0-9a-f]{32}"));
    verify(emailSender).sendPasswordChanged(account);
    verify(emailSender, never()).sendPasswordAdded(any());
  }

  /**
   * Making currentPassword optional while parsing must not make it optional in fact: an account
   * that has a password still has to supply one, and still gets the same error code it always did.
   */
  @Test
  public void changePasswordWithLocalPasswordAndNoCurrentPasswordReturnsMissingCredentials()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(400, resp.status);
    assertEquals("CHANGE_PASSWORD_MISSING_CREDENTIALS",
        new JSONObject(resp.body()).getJSONObject("error").getString("code"));
    verify(emailSender, never()).sendPasswordAdded(any());
    verify(emailSender, never()).sendPasswordChanged(any());
  }

  @Test
  public void changePasswordWithLocalPasswordAndWrongCurrentPasswordReturnsUnauthorized()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"currentPassword\":\"wrong\",\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    Account account = mock(Account.class);
    when(account.getPasswordHash()).thenReturn(testPasswordHash("secret"));
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(401, resp.status);
    assertEquals("INVALID_CURRENT_PASSWORD",
        new JSONObject(resp.body()).getJSONObject("error").getString("code"));
    verify(emailSender, never()).sendPasswordAdded(any());
    verify(emailSender, never()).sendPasswordChanged(any());
  }

  /**
   * The strength policy still runs before the account is resolved, so a weak newPassword costs no
   * database lookup regardless of whether the caller is enrolling.
   */
  @Test
  public void changePasswordWeakNewPasswordIsRejectedBeforeAnyAccountLookup() throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password", "{\"newPassword\":\"weak\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer valid-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.findActiveAccountByToken(anyString()), never());
    }

    assertEquals(400, resp.status);
  }

  /**
   * The bearer token is what proves identity in the enrolment branch, so an invalid one must still
   * be refused even now that the request carries no currentPassword to reject it on instead.
   */
  @Test
  public void changePasswordInvalidTokenStillUnauthorizedWithoutCurrentPassword()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/change-password",
        "{\"newPassword\":\"Str0ng!Pass1\"}");
    when(req.getHeader("Authorization")).thenReturn("Bearer bad-token");

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("bad-token"))
          .thenReturn(null);

      servlet.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.changePassword(
          any(), anyString(), anyString(), any(Date.class)), never());
    }

    assertEquals(401, resp.status);
  }

  // ===================== POST /password-reset/request — ETP-5115 branch split ==========

  /**
   * The sibling of {@link #passwordResetRequestKnownEmailWithoutLocalPasswordIssuesSetPasswordLink}.
   * Widening the gate to let SSO accounts through must not have swapped the copy for everyone else:
   * an account that does have a password is restoring one it forgot, not creating a first.
   */
  @Test
  public void passwordResetRequestKnownEmailWithLocalPasswordIssuesResetPasswordLink()
      throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"user@test.com\"}");

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendPasswordReset(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("user@test.com"))
          .thenReturn(account);
      dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servletWithEmailSender.doPost(req, resp.response);

      dalMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          any(Account.class), anyString(), any(Date.class)));
      verify(emailSender).sendPasswordReset(eq(account), anyString(), anyString(), any());
      verify(emailSender, never()).sendSetPassword(any(), anyString(), anyString(), any());
    }

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  /**
   * The anti-enumeration guard, asserted explicitly rather than left implied by three separate
   * tests each checking only its own status. Varying the answer by account state would confirm to
   * an anonymous prober both that an address is registered and which identity provider it uses, so
   * the three branches — no account, reset, enrol — must produce a byte-identical body. The
   * disclosure belongs in the email, which only the owner of the mailbox reads.
   */
  @Test
  public void passwordResetRequestNeutralResponseIsIdenticalAcrossAllThreeBranches()
      throws Exception {
    String unknown = passwordResetRequestBody(null);
    String reset = passwordResetRequestBody(Boolean.TRUE);
    String enrol = passwordResetRequestBody(Boolean.FALSE);

    assertEquals(unknown, reset);
    assertEquals(unknown, enrol);
  }

  /**
   * Drives one password-reset request and returns the raw response body.
   *
   * @param hasLocalPassword null for an address with no account at all, otherwise whether the
   *     account found already has a local password
   * @return the exact bytes written back to the caller
   */
  private static String passwordResetRequestBody(Boolean hasLocalPassword) throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = jsonRequest("/password-reset/request",
        "{\"email\":\"probe@test.com\"}");

    TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    when(emailSender.sendPasswordReset(any(), anyString(), anyString(), any())).thenReturn(true);
    when(emailSender.sendSetPassword(any(), anyString(), anyString(), any())).thenReturn(true);
    EtendoGoJwtServlet servletWithEmailSender = new EtendoGoJwtServlet(emailSender);
    // Hoisted out of the when(...) below on purpose: a helper that stubs a mock cannot be called
    // inline inside a stubbing argument without tripping Mockito's UnfinishedStubbingException.
    final Account account = hasLocalPassword == null ? null : mock(Account.class);
    if (account != null) {
      when(account.getEmail()).thenReturn("probe@test.com");
    }

    try (var ctxMock = mockStatic(OBContext.class);
         var dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         var linkMock = mockStatic(EtendoGoAuthLinkBuilder.class)) {
      linkMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(anyString(), any()))
          .thenReturn("https://go.example.com/reset-password?token=t");
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail("probe@test.com"))
          .thenReturn(account);
      if (account != null) {
        dalMock.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account))
            .thenReturn(hasLocalPassword);
      }

      servletWithEmailSender.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    return resp.body();
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

  // ===================== applyPaidUpgradeSideEffects — ETP-5046 per-tenant retirement ==========
  //
  // The paid-upgrade path used to write BOTH stores on every upgrade: the ETGO_SUBSCRIPTION row
  // AND the legacy ETGO_TenantPlan preference. Since ETP-5046 the subscription is the source of
  // truth, so writing the preference alongside it was not a safety measure but a second answer
  // that drifts. The preference is now written ONLY when the subscription write failed — which is
  // exactly when TenantPlanPreferenceFallback needs it — and is RETIRED on the success path, so a
  // newly paid tenant lands directly in the post-cutover state. Grep marker for the Phase F
  // deletion: ETP-5046-TRANSITIONAL-FALLBACK.

  private static final String PAID_CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String PAID_STAR_ORG_ID = "9F5511B92BD0465FA678F75278FA9C3A";
  private static final String PAID_TOKEN = "chk_etp5046";

  /**
   * Wires the servlet with mocked collaborators and a checkout request that carries a plan, so the
   * subscription write has everything it needs.
   *
   * @param subscriptionOpens whether {@code openSubscription} succeeds or throws
   * @return the mocked collaborators, for verification
   */
  private PaidUpgradeFixture givenPaidUpgrade(boolean subscriptionOpens) {
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    CheckoutRequest checkoutRequest = mock(CheckoutRequest.class);
    when(checkoutRequest.getPlan()).thenReturn(mock(Plan.class));
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(checkoutRequest);
    if (subscriptionOpens) {
      when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any()))
          .thenReturn(mock(Subscription.class));
    } else {
      when(fixture.subscriptionService.openSubscription(anyString(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("subscription write failed"));
    }
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    return fixture;
  }

  private void applyPaidUpgrade() {
    servlet.applyPaidUpgradeSideEffects(PAID_CLIENT_ID, PAID_STAR_ORG_ID, "Acme S.L.",
        "user@test.com", PAID_TOKEN);
  }

  @Test
  public void paidUpgradeWithASubscriptionDoesNotWriteThePreferenceAndRetiresTheStaleOne() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    when(fixture.tenantPlanService.retireProductivePreference(PAID_CLIENT_ID)).thenReturn(true);

    applyPaidUpgrade();

    verify(fixture.subscriptionService)
        .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any());
    // The whole point: no parallel truth is written any more.
    verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    // ...and whatever marker this tenant still carried is retired, so it is immediately in the
    // post-cutover state and the fleet-wide count moves one closer to zero.
    verify(fixture.tenantPlanService).retireProductivePreference(PAID_CLIENT_ID);
    // A recorded productive tenant still gets its ETSG_ForceTestMode override reverted (ETP-5117).
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWhoseSubscriptionWriteFailsWritesThePreferenceAsTheSafetyNet() {
    PaidUpgradeFixture fixture = givenPaidUpgrade(false);
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    // Without a subscription row, the preference is the ONLY record that this tenant paid, and
    // TenantPlanPreferenceFallback is what will read it back. This is the case the fallback and
    // the whole transitional apparatus exist for.
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    // Nothing is retired: retiring the safety net in the same breath as writing it would leave the
    // tenant with no record at all.
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
    verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
  }

  @Test
  public void paidUpgradeWithNoCheckoutRequestFallsBackToThePreferenceAndRetiresNothing() {
    // The third way the subscription write can fail to record anything: the token resolves to no
    // request at all, so there is no plan to open a subscription on. Same answer as a throwing
    // write — the safety net is written, nothing is retired.
    PaidUpgradeFixture fixture = new PaidUpgradeFixture();
    when(fixture.checkoutRequestStore.find(eq(PAID_TOKEN), anyString())).thenReturn(null);
    servlet.checkoutRequestStore = fixture.checkoutRequestStore;
    servlet.subscriptionService = fixture.subscriptionService;
    servlet.tenantPlanService = fixture.tenantPlanService;
    servlet.onboardingForceTestModeService = fixture.forceTestModeService;
    when(fixture.tenantPlanService.markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID))
        .thenReturn(true);

    applyPaidUpgrade();

    verifyNoInteractions(fixture.subscriptionService);
    verify(fixture.tenantPlanService).markProductive(PAID_CLIENT_ID, PAID_STAR_ORG_ID);
    verify(fixture.tenantPlanService, never()).retireProductivePreference(anyString());
  }

  @Test
  public void aFailedRetirementIsLoggedAndNeverFailsAnUpgradeThatWasAlreadyPaidFor() {
    // Best-effort discipline, unchanged since ETP-4966: nothing in this method may abort a paid
    // signup. A failed retirement is the most harmless of the three failures — the transitional
    // fallback simply keeps answering for the tenant and the R37 backfill retires the row later —
    // so it must be logged loudly and then ignored.
    PaidUpgradeFixture fixture = givenPaidUpgrade(true);
    when(fixture.tenantPlanService.retireProductivePreference(PAID_CLIENT_ID))
        .thenThrow(new IllegalStateException("no session"));
    LogCapture errors = LogCapture.attachTo(EtendoGoJwtServlet.class, Level.ERROR);

    try {
      applyPaidUpgrade();

      List<String> logged = errors.messagesAt(Level.ERROR);
      assertEquals("the failure must be searchable, not silent: " + logged, 1, logged.size());
      assertTrue("the line must name the tenant: " + logged.get(0),
          logged.get(0).contains(PAID_CLIENT_ID));
      assertTrue("the line must name the preference that survived: " + logged.get(0),
          logged.get(0).contains(TenantPlanService.PREFERENCE_ATTRIBUTE));
      // The upgrade completed all the same: the subscription was written and the fiscal test-mode
      // override was still reverted.
      verify(fixture.subscriptionService)
          .openSubscription(eq(PAID_CLIENT_ID), any(), any(), any(), any());
      verify(fixture.forceTestModeService).revertTestModeForProductiveTenant(PAID_CLIENT_ID);
      // And the failed retirement must NOT make the servlet fall back to writing the marker: the
      // subscription exists, so the tenant is recorded.
      verify(fixture.tenantPlanService, never()).markProductive(anyString(), anyString());
    } finally {
      errors.detach();
    }
  }

  /** The mocked collaborators of one paid-upgrade spec. */
  private static final class PaidUpgradeFixture {
    final CheckoutRequestStore checkoutRequestStore = mock(CheckoutRequestStore.class);
    final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    final TenantPlanService tenantPlanService = mock(TenantPlanService.class);
    final OnboardingForceTestModeService forceTestModeService =
        mock(OnboardingForceTestModeService.class);
  }

  /**
   * Collects the log events of one logger so a spec can assert on them.
   *
   * <p>Log4j2's default configuration is ERROR-only in this build, so the level is pinned before
   * attaching and restored afterwards; without that a WARN would never reach an appender and a
   * spec asserting on one would pass vacuously.
   */
  private static final class LogCapture extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();
    private final String loggerName;
    private final Level previousLevel;

    private LogCapture(String loggerName, Level previousLevel) {
      super("Etp5046ServletCapture", (Filter) null, (Layout<? extends Serializable>) null, true,
          new Property[0]);
      this.loggerName = loggerName;
      this.previousLevel = previousLevel;
    }

    static LogCapture attachTo(Class<?> type, Level level) {
      String name = type.getName();
      Level previous = LogManager.getLogger(name).getLevel();
      Configurator.setLevel(name, level);
      LogCapture appender = new LogCapture(name, previous);
      appender.start();
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(name)).addAppender(appender);
      return appender;
    }

    void detach() {
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName))
          .removeAppender(this);
      stop();
      Configurator.setLevel(loggerName, previousLevel);
    }

    List<String> messagesAt(Level level) {
      List<String> messages = new ArrayList<>();
      for (LogEvent event : events) {
        if (level.equals(event.getLevel())) {
          messages.add(event.getMessage().getFormattedMessage());
        }
      }
      return messages;
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }

  private static Account stubAuthenticatedAccount(
      MockedStatic<EtendoGoJwtDalHelper> dalMock) {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
        .thenReturn(account);
    return account;
  }

  private static HttpServletRequest mockRequest(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  private static HttpServletRequest jsonRequest(String pathInfo, String json) throws Exception {
    HttpServletRequest request = mockRequest(pathInfo);
    when(request.getContentType()).thenReturn("application/json");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
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
