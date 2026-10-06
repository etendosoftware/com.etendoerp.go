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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.rest;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.etendoerp.go.session.SessionRoleRevokedException;
import org.codehaus.jettison.json.JSONArray;
import org.mockito.ArgumentCaptor;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for the {@code /sws/go/session} endpoints (ETP-4575, slice 4a.i). {@link GoSessionService}
 * is mocked and {@code OBContext}/{@code EtendoGoJwtDalHelper} are statically stubbed, so these run
 * fast with no database — the servlet wiring (routing → service → cookie/CSRF/response envelope) is
 * what's under test. DB-level store behavior is covered by {@code JdbcGoSessionStoreIntegrationTest}.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
public class GoSessionEndpointsTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String EMAIL = "user@example.test";
  private static final String PASSWORD = "Str0ng!Passw0rd";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String COMMIT = "commit";
  private static final String SET_COOKIE = "Set-Cookie";
  private static final String BODY = "body";
  private static final String SESSION_WRITE = "session-write";
  private static final String PASSWORD_WRITE = "password-write";
  private static final String EMAIL_SENT = "email";

  private final GoSessionService goSessionService = mock(GoSessionService.class);
  private final EtendoGoSsoProviderRegistry ssoRegistry = mock(EtendoGoSsoProviderRegistry.class);
  private final TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
      emailSender, ssoRegistry, goSessionService);
  // ETP-5395 — the restore reconciles the session role against the database; these tests have
  // none, so by default the reconciler reports the role as still valid (a no-op).
  private final GoSessionRoleReconciler roleReconciler = mock(GoSessionRoleReconciler.class);

  @Before
  public void injectRoleReconciler() {
    servlet.sessionRoleReconciler = roleReconciler;
  }

  @Test
  public void createSetsHostCookieAndDoesNotLeakTokenInBody() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");
    when(account.getPasswordHash()).thenReturn(storedHash(PASSWORD));

    IssuedGoSession issued = new IssuedGoSession(
        "sess-token-xyz", "refresh-abc", "csrf-xyz", new GoSessionRecord());
    when(goSessionService.create(eq("ACC1"), eq("password"), any(), any())).thenReturn(issued);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servlet.doPost(jsonPost("/session", new JSONObject().put("email", EMAIL).put("password", PASSWORD)),
          resp.response);
    }

    assertEquals(200, resp.status);
    String setCookie = resp.cookie(GoSessionSecurity.COOKIE_NAME);
    assertNotNull(setCookie);
    assertNotNull("refresh cookie must be set too", resp.cookie(GoSessionSecurity.REFRESH_COOKIE_NAME));
    assertTrue(setCookie.startsWith(GoSessionSecurity.COOKIE_NAME + "=sess-token-xyz"));
    assertTrue(setCookie.contains("HttpOnly"));
    assertTrue(setCookie.contains("Secure"));
    assertEquals("no-store", resp.headers.get("Cache-Control"));

    JSONObject body = new JSONObject(resp.body.toString());
    assertFalse("session token must NOT be in the body", body.has("token"));
    assertEquals("csrf-xyz", body.getString("csrfToken"));
    assertTrue(body.has("account"));
  }

  @Test
  public void createWithInvalidCredentialsReturns401() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(null);

      servlet.doPost(jsonPost("/session", new JSONObject().put("email", EMAIL).put("password", PASSWORD)),
          resp.response);
    }

    assertEquals(401, resp.status);
    verify(goSessionService, never()).create(anyString(), anyString(), any(), any());
  }

  @Test
  public void createWithMissingFieldsReturns400() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(jsonPost("/session", new JSONObject().put("email", EMAIL)), resp.response);
    }
    assertEquals(400, resp.status);
  }

  @Test
  public void sessionRegisterCreatesCookieWithoutLeakingLegacyToken() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");
    IssuedGoSession issued = new IssuedGoSession(
        "session-register", "refresh-register", "csrf-register", new GoSessionRecord());
    when(goSessionService.create(eq("ACC1"), eq("password"), any(), any())).thenReturn(issued);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(null);
      dal.when(() -> EtendoGoJwtDalHelper.createAccount(eq(EMAIL), anyString(), eq("User"),
          anyString())).thenReturn(account);

      servlet.doPost(jsonPost("/session/register", new JSONObject()
          .put("email", EMAIL)
          .put("password", PASSWORD)
          .put("name", "User")), resp.response);
    }

    assertEquals(201, resp.status);
    assertNotNull(resp.cookie(GoSessionSecurity.COOKIE_NAME));
    JSONObject body = new JSONObject(resp.body.toString());
    assertFalse(body.has("token"));
    assertEquals("csrf-register", body.getString("csrfToken"));
  }

  @Test
  public void logoutWithValidCsrfRevokesAndClearsCookie() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doDelete(deleteRequest("tok", CSRF), resp.response);
    }

    assertEquals(204, resp.status);
    assertTrue(resp.cookie(GoSessionSecurity.COOKIE_NAME).contains("Max-Age=0"));
    verify(goSessionService).revoke(sessionRecord);
  }

  @Test
  public void logoutWithoutCsrfIsForbiddenAndDoesNotRevoke() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doDelete(deleteRequest("tok", null), resp.response);
    }

    assertEquals(403, resp.status);
    assertEquals("CSRF validation failed", errorMessage(resp));
    verify(goSessionService, never()).revoke(any());
  }

  @Test
  public void environmentFromAForeignOriginIsForbiddenForItsOrigin() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);
    HttpServletRequest req = postEnv(new JSONObject().put("userId", "U1").toString(), "tok", CSRF);
    when(req.getHeader("Origin")).thenReturn("https://evil.example.test");

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(req, resp.response);
    }

    assertEquals(403, resp.status);
    assertEquals("Origin not allowed", errorMessage(resp));
    verify(goSessionService, never()).rotate(any());
  }

  @Test
  public void restoreReturnsAccountAndCsrf() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      servlet.doGet(getRequest("/session", "tok"), resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body.toString());
    assertEquals(CSRF, body.getString("csrfToken"));
    assertTrue(body.has("account"));
    assertTrue("no environment selected yet", body.isNull("environment"));
  }

  @Test
  public void restoreWithoutCookieReturns401() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doGet(getRequest("/session", null), resp.response);
    }
    assertEquals(401, resp.status);
  }

  @Test
  public void meAcceptsCookieSessionWithoutBearer() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");

    CapturedResponse resp = new CapturedResponse();
    // ETP-5115 enriched GET /me with an authMethods block, so the handler now also reaches
    // AccountIdentityDalHelper and EmailVerificationDalHelper. Left unstubbed they hit the real
    // DAL and the handler answers 500, which reads as a broken cookie session rather than a
    // missing stub.
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EmailVerificationDalHelper> verify =
            mockStatic(EmailVerificationDalHelper.class);
        MockedStatic<AccountIdentityDalHelper> identities =
            mockStatic(AccountIdentityDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      identities.when(() -> AccountIdentityDalHelper.identitiesFor(account))
          .thenReturn(new ArrayList<>());
      servlet.doGet(getRequest("/me", "tok"), resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals(EMAIL, new JSONObject(resp.body.toString()).getString("email"));
  }

  @Test
  public void meRejectsLegacyBearerWhenMigrationFlagIsOff() throws Exception {
    System.setProperty("etgo.legacy.bearer.enabled", "false");
    HttpServletRequest req = getRequest("/me", null);
    when(req.getHeader("Authorization")).thenReturn("Bearer legacy-platform-token");
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doGet(req, resp.response);
    } finally {
      System.clearProperty("etgo.legacy.bearer.enabled");
    }
    assertEquals(401, resp.status);
  }

  @Test
  public void restoreIncludesSelectedEnvironment() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    sessionRecord.setUserId("U1");
    sessionRecord.setRoleId("R1");
    sessionRecord.setCtxClientId("C1");
    sessionRecord.setCtxOrgId("O1");
    sessionRecord.setWarehouseId("W1");
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");

    CapturedResponse resp = new CapturedResponse();
    EtendoGoJwtSupport.RoleListData roleListData = new EtendoGoJwtSupport.RoleListData(
        null, new JSONArray().put(new JSONObject().put("id", "R1")));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      support.when(() -> EtendoGoJwtSupport.loadRoleListData("U1")).thenReturn(roleListData);
      servlet.doGet(getRequest("/session", "tok"), resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject body = new JSONObject(resp.body.toString());
    JSONObject env = body.getJSONObject("environment");
    assertEquals("U1", env.getString("userId"));
    assertEquals("R1", env.getString("roleId"));
    assertEquals("O1", env.getString("orgId"));
    assertEquals("R1", body.getJSONArray("roleList").getJSONObject(0).getString("id"));
  }

  private GoSessionRecord environmentSession() {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    sessionRecord.setUserId("U1");
    sessionRecord.setRoleId("R1");
    sessionRecord.setCtxClientId("C1");
    sessionRecord.setCtxOrgId("O1");
    sessionRecord.setWarehouseId("W1");
    return sessionRecord;
  }

  private CapturedResponse restore(GoSessionRecord sessionRecord) throws Exception {
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");
    EtendoGoJwtSupport.RoleListData roleListData = new EtendoGoJwtSupport.RoleListData(
        null, new JSONArray().put(new JSONObject().put("id", "R2")));
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      support.when(() -> EtendoGoJwtSupport.loadRoleListData("U1")).thenReturn(roleListData);
      servlet.doGet(getRequest("/session", "tok"), resp.response);
    }
    return resp;
  }

  /**
   * ETP-5395 — a user promoted/demoted since entering the environment must get back the role
   * they hold now; before, the restore returned the revoked role and the app landed on
   * "no access".
   */
  @Test
  public void restoreReportsTheRoleTheSessionWasReboundTo() throws Exception {
    GoSessionRecord sessionRecord = environmentSession();
    doAnswer(invocation -> {
      invocation.<GoSessionRecord>getArgument(0).setRoleId("R2");
      return true;
    }).when(roleReconciler).reconcile(sessionRecord);

    CapturedResponse resp = restore(sessionRecord);

    assertEquals(200, resp.status);
    JSONObject env = new JSONObject(resp.body.toString()).getJSONObject("environment");
    assertEquals("R2", env.getString("roleId"));
    verify(roleReconciler).reconcile(sessionRecord);
  }

  @Test
  public void restoreRejectsASessionWhoseUserHoldsNoRoleAnyMore() throws Exception {
    GoSessionRecord sessionRecord = environmentSession();
    when(roleReconciler.reconcile(sessionRecord))
        .thenThrow(new SessionRoleRevokedException("no role left"));

    CapturedResponse resp = restore(sessionRecord);

    assertEquals(401, resp.status);
  }

  private static HttpServletRequest getRequest(String path, String cookieValue) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(path);
    if (cookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }

  @Test
  public void environmentMissingUserIdReturns400() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    servlet.doPost(postEnv("{}", "tok", CSRF), resp.response);
    assertEquals(400, resp.status);
  }

  @Test
  public void environmentWithoutCsrfIsForbidden() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(postEnv(new JSONObject().put("userId", "U1").toString(), "tok", null),
          resp.response);
    }
    assertEquals(403, resp.status);
  }

  @Test
  public void environmentWithUnownedUserIsForbidden() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(EMAIL);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> supp = mockStatic(EtendoGoJwtSupport.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      supp.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(any(), eq("U1")))
          .thenReturn(false);
      servlet.doPost(postEnv(new JSONObject().put("userId", "U1").toString(), "tok", CSRF),
          resp.response);
    }
    assertEquals(403, resp.status);
  }

  @Test
  public void environmentRotatesSessionAndStoresContext() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);

    GoSessionRecord rotatedRecord = new GoSessionRecord();
    rotatedRecord.setUserId("U1");
    rotatedRecord.setRoleId("R1");
    rotatedRecord.setCtxClientId("C1");
    rotatedRecord.setCtxOrgId("O1");
    rotatedRecord.setWarehouseId("W1");
    IssuedGoSession rotated = new IssuedGoSession("newtok", "newref", "newcsrf", rotatedRecord);
    when(goSessionService.rotate(any())).thenReturn(rotated);

    CapturedResponse resp = enterEnvironment(sessionRecord);

    assertEquals(200, resp.status);
    assertTrue(resp.cookie(GoSessionSecurity.COOKIE_NAME).startsWith(GoSessionSecurity.COOKIE_NAME + "=newtok"));
    JSONObject body = new JSONObject(resp.body.toString());
    assertEquals("newcsrf", body.getString("csrfToken"));
    assertTrue(body.has("roleList"));
    assertEquals("U1", body.getJSONObject("environment").getString("userId"));
    assertEquals("O1", body.getJSONObject("environment").getString("orgId"));

    ArgumentCaptor<GoSessionRecord> captor = ArgumentCaptor.forClass(GoSessionRecord.class);
    verify(goSessionService).rotate(captor.capture());
    assertEquals("U1", captor.getValue().getUserId());
    assertEquals("C1", captor.getValue().getCtxClientId());
  }

  /**
   * ETP-5550 — re-entering the environment the session already holds is not a privilege change,
   * so it must not rotate: rotating revokes the CSRF token every other open tab still holds, and
   * the onboarding re-enters on its own whenever it mounts with a live session.
   */
  @Test
  public void environmentReentryWithSameContextKeepsSessionAndCsrf() throws Exception {
    GoSessionRecord sessionRecord = sessionInEnvironment("O1");

    CapturedResponse resp = enterEnvironment(sessionRecord);

    assertEquals(200, resp.status);
    verify(goSessionService, never()).rotate(any());
    assertNull("the session cookie must stay as it is", resp.cookie(GoSessionSecurity.COOKIE_NAME));
    assertNull("the refresh cookie must stay as it is",
        resp.cookie(GoSessionSecurity.REFRESH_COOKIE_NAME));
    JSONObject body = new JSONObject(resp.body.toString());
    assertEquals(CSRF, body.getString("csrfToken"));
    assertEquals("U1", body.getJSONObject("environment").getString("userId"));
    assertEquals("O1", body.getJSONObject("environment").getString("orgId"));
    assertTrue(body.has("roleList"));
    assertEquals("no-store", resp.headers.get("Cache-Control"));
  }

  @Test
  public void environmentSwitchToAnotherOrganizationStillRotates() throws Exception {
    GoSessionRecord sessionRecord = sessionInEnvironment("O2");
    GoSessionRecord rotatedRecord = new GoSessionRecord();
    rotatedRecord.setUserId("U1");
    rotatedRecord.setCtxOrgId("O1");
    when(goSessionService.rotate(any()))
        .thenReturn(new IssuedGoSession("newtok", "newref", "newcsrf", rotatedRecord));

    CapturedResponse resp = enterEnvironment(sessionRecord);

    assertEquals(200, resp.status);
    verify(goSessionService).rotate(any());
    assertEquals("newcsrf", new JSONObject(resp.body.toString()).getString("csrfToken"));
  }

  /** A live session already inside U1/R1/C1/{@code orgId}/W1, as the resolver returns it. */
  private static GoSessionRecord sessionInEnvironment(String orgId) {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    sessionRecord.setUserId("U1");
    sessionRecord.setRoleId("R1");
    sessionRecord.setCtxClientId("C1");
    sessionRecord.setCtxOrgId(orgId);
    sessionRecord.setWarehouseId("W1");
    return sessionRecord;
  }

  /**
   * Posts {@code /session/environment} for U1/R1/O1 with the platform stubbed so the
   * context derivation yields U1/R1/C1/O1/W1. {@code rotate} is left to each test.
   */
  private CapturedResponse enterEnvironment(GoSessionRecord sessionRecord)
      throws Exception {
    return enterEnvironment(sessionRecord, new CapturedResponse());
  }

  /** As {@link #enterEnvironment(GoSessionRecord)}, answering into {@code resp}. */
  private CapturedResponse enterEnvironment(GoSessionRecord sessionRecord, CapturedResponse resp)
      throws Exception {
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(EMAIL);

    EtendoGoJwtSupport.RoleListData roleListData = new EtendoGoJwtSupport.RoleListData(
        "R1",
        new JSONArray().put(new JSONObject()
            .put("id", "R1")
            .put("orgList", new JSONArray().put(new JSONObject().put("id", "O1")))));

    User user = mock(User.class);
    Role role = mock(Role.class);
    OBDal obDal = recordingDal(resp);
    when(obDal.get(User.class, "U1")).thenReturn(user);
    when(obDal.get(Role.class, "R1")).thenReturn(role);

    DecodedJWT decoded = mock(DecodedJWT.class);
    Claim userClaim = claim("U1");
    Claim roleClaim = claim("R1");
    Claim clientClaim = claim("C1");
    Claim orgClaim = claim("O1");
    Claim warehouseClaim = claim("W1");
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("role")).thenReturn(roleClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("organization")).thenReturn(orgClaim);
    when(decoded.getClaim("warehouse")).thenReturn(warehouseClaim);

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> supp = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      supp.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(any(), eq("U1")))
          .thenReturn(true);
      supp.when(() -> EtendoGoJwtSupport.loadRoleListData("U1")).thenReturn(roleListData);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn("jwt");
      sws.when(() -> SecureWebServicesUtils.decodeToken("jwt")).thenReturn(decoded);

      servlet.doPost(postEnv(new JSONObject()
              .put("userId", "U1")
              .put("roleId", "R1")
              .put("orgId", "O1").toString(), "tok", CSRF),
          resp.response);
    }
    return resp;
  }

  @Test
  public void createViaSsoSetsCookieWithoutLeakingToken() throws Exception {
    EtendoGoSsoAssertion assertion = mock(EtendoGoSsoAssertion.class);
    when(assertion.getProvider()).thenReturn("google");
    when(assertion.getSubject()).thenReturn("sub-1");
    when(assertion.getEmail()).thenReturn(EMAIL);
    when(assertion.getName()).thenReturn("SSO User");
    when(ssoRegistry.verify(eq("google"), any(), anyString())).thenReturn(assertion);

    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("SSO User");

    IssuedGoSession issued = new IssuedGoSession("sess-sso", "ref-sso", "csrf-sso",
        new GoSessionRecord());
    when(goSessionService.create(eq("ACC1"), eq("sso"), any(), any())).thenReturn(issued);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "sub-1"))
          .thenReturn(account);
      servlet.doPost(jsonPost("/session/sso/google",
          new JSONObject().put("credential", "google-id-token")), resp.response);
    }

    assertEquals(200, resp.status);
    assertNotNull(resp.cookie(GoSessionSecurity.COOKIE_NAME));
    assertNotNull(resp.cookie(GoSessionSecurity.REFRESH_COOKIE_NAME));
    JSONObject body = new JSONObject(resp.body.toString());
    assertFalse("platform token must NOT be in the body", body.has("token"));
    assertEquals("csrf-sso", body.getString("csrfToken"));
  }

  // ===================== ETP-5628 — commit before the cookie leaves =====================
  //
  // The session row joins the request transaction, which DalRequestFilter only commits after the
  // servlet returns; writing the body completes the HTTP response before that. The SPA's next
  // request (/me, /environments) arrived within ~8 ms, could not see the row and got a 401, and an
  // onboarded user was sent back to onboarding. Each test below fails if the request's writes are
  // not committed before the first Set-Cookie and before the body.

  @Test
  public void createCommitsTheSessionBeforeSendingTheCookie() throws Exception {
    Account account = passwordAccount();
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.create(eq("ACC1"), eq("password"), any(), any())).thenAnswer(
        sessionWrite(resp, new IssuedGoSession("sess-token-xyz", "refresh-abc", "csrf-xyz",
            new GoSessionRecord())));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servlet.doPost(jsonPost("/session", new JSONObject().put("email", EMAIL).put("password", PASSWORD)),
          resp.response);
    }

    assertEquals(200, resp.status);
    assertCommittedBeforeResponse(resp);
  }

  /**
   * A commit that fails must leave the client without a cookie: the cookie would point at a row
   * that does not exist. Committing before the cookie is what makes that possible at all.
   */
  @Test
  public void createWhoseCommitFailsSendsNoCookie() throws Exception {
    Account account = passwordAccount();
    when(goSessionService.create(eq("ACC1"), eq("password"), any(), any())).thenReturn(
        new IssuedGoSession("sess-token-xyz", "refresh-abc", "csrf-xyz", new GoSessionRecord()));

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      OBDal obDal = mock(OBDal.class);
      doThrow(new IllegalStateException("commit refused")).when(obDal).commitAndClose();
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servlet.doPost(jsonPost("/session", new JSONObject().put("email", EMAIL).put("password", PASSWORD)),
          resp.response);
    }

    assertEquals(500, resp.status);
    assertTrue("no cookie may be sent for an uncommitted session: " + resp.setCookies,
        resp.setCookies.isEmpty());
  }

  /** A rotation whose commit fails must not hand out the rotated cookie. */
  @Test
  public void environmentRotationWhoseCommitFailsSendsNoCookie() throws Exception {
    GoSessionRecord sessionRecord = sessionInEnvironment("O2");
    GoSessionRecord rotatedRecord = new GoSessionRecord();
    rotatedRecord.setUserId("U1");
    rotatedRecord.setCtxOrgId("O1");
    CapturedResponse resp = new CapturedResponse();
    resp.failCommit = true;
    when(goSessionService.rotate(any())).thenAnswer(
        sessionWrite(resp, new IssuedGoSession("newtok", "newref", "newcsrf", rotatedRecord)));

    enterEnvironment(sessionRecord, resp);

    assertEquals(500, resp.status);
    assertTrue("no cookie may be sent for an uncommitted rotation: " + resp.setCookies,
        resp.setCookies.isEmpty());
  }

  @Test
  public void refreshWhoseCommitFailsSendsNoCookie() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    resp.failCommit = true;
    when(goSessionService.refresh("rtok")).thenAnswer(sessionWrite(resp,
        new IssuedGoSession("newtok", "newref", "newcsrf", new GoSessionRecord())));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doPost(postRefresh("rtok"), resp.response);
    }

    assertEquals(500, resp.status);
    assertTrue("no cookie may be sent for an uncommitted rotation: " + resp.setCookies,
        resp.setCookies.isEmpty());
  }

  /** Registration commits the account, its verification and the session as one unit. */
  @Test
  public void sessionRegisterCommitsTheAccountAndSessionBeforeSendingTheCookie() throws Exception {
    Account account = passwordAccount();
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.create(eq("ACC1"), eq("password"), any(), any())).thenAnswer(
        sessionWrite(resp, new IssuedGoSession("session-register", "refresh-register",
            "csrf-register", new GoSessionRecord())));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(null);
      dal.when(() -> EtendoGoJwtDalHelper.createAccount(eq(EMAIL), anyString(), eq("User"),
          anyString())).thenReturn(account);

      servlet.doPost(jsonPost("/session/register", new JSONObject()
          .put("email", EMAIL)
          .put("password", PASSWORD)
          .put("name", "User")), resp.response);
    }

    assertEquals(201, resp.status);
    assertCommittedBeforeResponse(resp);
  }

  @Test
  public void ssoCreateCommitsTheSessionBeforeSendingTheCookie() throws Exception {
    EtendoGoSsoAssertion assertion = mock(EtendoGoSsoAssertion.class);
    when(assertion.getProvider()).thenReturn("google");
    when(assertion.getSubject()).thenReturn("sub-1");
    when(assertion.getEmail()).thenReturn(EMAIL);
    when(ssoRegistry.verify(eq("google"), any(), anyString())).thenReturn(assertion);
    Account account = passwordAccount();
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.create(eq("ACC1"), eq("sso"), any(), any())).thenAnswer(
        sessionWrite(resp, new IssuedGoSession("sess-sso", "ref-sso", "csrf-sso",
            new GoSessionRecord())));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "sub-1"))
          .thenReturn(account);

      servlet.doPost(jsonPost("/session/sso/google",
          new JSONObject().put("credential", "google-id-token")), resp.response);
    }

    assertEquals(200, resp.status);
    assertCommittedBeforeResponse(resp);
  }

  @Test
  public void environmentRotationCommitsBeforeSendingTheRotatedCookie() throws Exception {
    GoSessionRecord sessionRecord = sessionInEnvironment("O2");
    GoSessionRecord rotatedRecord = new GoSessionRecord();
    rotatedRecord.setUserId("U1");
    rotatedRecord.setCtxOrgId("O1");
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.rotate(any())).thenAnswer(
        sessionWrite(resp, new IssuedGoSession("newtok", "newref", "newcsrf", rotatedRecord)));

    enterEnvironment(sessionRecord, resp);

    assertEquals(200, resp.status);
    assertCommittedBeforeResponse(resp);
  }

  @Test
  public void refreshCommitsTheRotationBeforeSendingTheRotatedCookie() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.refresh("rtok")).thenAnswer(sessionWrite(resp,
        new IssuedGoSession("newtok", "newref", "newcsrf", new GoSessionRecord())));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doPost(postRefresh("rtok"), resp.response);
    }

    assertEquals(200, resp.status);
    assertCommittedBeforeResponse(resp);
  }

  /** A replayed refresh revokes the whole family; that revocation is durable before the 401. */
  @Test
  public void refreshReplayCommitsTheRevocationBeforeClearingTheCookies() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.refresh("rtok")).thenAnswer(sessionWrite(resp, null));

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doPost(postRefresh("rtok"), resp.response);
    }

    assertEquals(401, resp.status);
    assertCommittedBeforeResponse(resp);
  }

  @Test
  public void logoutCommitsTheRevocationBeforeClearingTheCookies() throws Exception {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);
    CapturedResponse resp = new CapturedResponse();
    doAnswer(sessionWrite(resp, null)).when(goSessionService).revoke(sessionRecord);

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      servlet.doDelete(deleteRequest("tok", CSRF), resp.response);
    }

    assertEquals(204, resp.status);
    verify(goSessionService).revoke(sessionRecord);
    assertCommittedBeforeResponse(resp);
  }

  /**
   * Changing the password on a cookie session rotates it. The rotation runs first, so the new
   * password and the rotated session are committed by one commit (the one changePassword makes),
   * and the rotated cookie and the change notice both follow that commit.
   */
  @Test
  public void changePasswordOnACookieSessionCommitsPasswordAndRotationTogether()
      throws Exception {
    GoSessionRecord sessionRecord = cookieSession();
    CapturedResponse resp = new CapturedResponse();
    when(goSessionService.rotate(sessionRecord)).thenAnswer(sessionWrite(resp,
        new IssuedGoSession("newtok", "newref", "newcsrf", new GoSessionRecord())));
    Account account = passwordAccount();
    when(emailSender.sendPasswordChanged(account)).thenAnswer(inv -> {
      resp.events.add(EMAIL_SENT);
      return true;
    });

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      OBDal obDal = recordingDal(resp);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);
      // The real helper flushes and commits the request transaction (flushAndCommitDalChanges).
      dal.when(() -> EtendoGoJwtDalHelper.changePassword(eq(account), anyString(), anyString(),
          any())).thenAnswer(inv -> {
            resp.events.add(PASSWORD_WRITE);
            obDal.commitAndClose();
            return null;
          });

      servlet.doPost(changePasswordRequest(), resp.response);

      dal.verify(() -> EtendoGoJwtDalHelper.changePassword(eq(account), anyString(), anyString(),
          any()));
    }

    assertEquals(200, resp.status);
    assertTrue(resp.cookie(GoSessionSecurity.COOKIE_NAME)
        .startsWith(GoSessionSecurity.COOKIE_NAME + "=newtok"));
    assertCommittedBeforeResponse(resp);
    List<String> events = resp.events;
    int rotation = events.indexOf(SESSION_WRITE);
    int password = events.indexOf(PASSWORD_WRITE);
    assertTrue("the session must be rotated before the password changes: " + events,
        rotation >= 0 && rotation < password);
    int commit = events.subList(rotation, events.size()).indexOf(COMMIT) + rotation;
    assertTrue("rotation and password must commit together, in one commit: " + events,
        commit > password);
    assertTrue("the change notice must follow the commit: " + events,
        events.indexOf(EMAIL_SENT) > commit);
  }

  /**
   * A rotation lost to a concurrent request answers 409 with nothing changed: the password stays
   * as it was (so the client's retry still passes the current-password check), no notice is sent,
   * no cookie is set, and the request transaction is rolled back.
   */
  @Test
  public void changePasswordWhoseRotationIsLostChangesNothing() throws Exception {
    GoSessionRecord sessionRecord = cookieSession();
    when(goSessionService.rotate(sessionRecord)).thenReturn(null);
    Account account = passwordAccount();
    OBDal obDal = mock(OBDal.class);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);

      servlet.doPost(changePasswordRequest(), resp.response);

      dal.verify(() -> EtendoGoJwtDalHelper.changePassword(any(), any(), any(), any()), never());
    }

    assertEquals(409, resp.status);
    assertTrue("no cookie may be set: " + resp.setCookies, resp.setCookies.isEmpty());
    verifyNoInteractions(emailSender);
    verify(obDal).rollbackAndClose();
    verify(obDal, never()).commitAndClose();
  }

  /**
   * A change whose commit fails answers 500 with nothing announced: no rotated cookie, no notice,
   * and the request transaction rolled back (the password and the rotation with it).
   */
  @Test
  public void changePasswordWhoseCommitFailsSendsNoCookieAndNoNotice() throws Exception {
    GoSessionRecord sessionRecord = cookieSession();
    CapturedResponse resp = new CapturedResponse();
    resp.failCommit = true;
    when(goSessionService.rotate(sessionRecord)).thenAnswer(sessionWrite(resp,
        new IssuedGoSession("newtok", "newref", "newcsrf", new GoSessionRecord())));
    Account account = passwordAccount();

    OBDal obDal = recordingDal(resp);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(account)).thenReturn(true);
      // The real helper flushes and commits the request transaction (flushAndCommitDalChanges).
      dal.when(() -> EtendoGoJwtDalHelper.changePassword(eq(account), anyString(), anyString(),
          any())).thenAnswer(inv -> {
            obDal.commitAndClose();
            return null;
          });

      servlet.doPost(changePasswordRequest(), resp.response);
    }

    assertEquals(500, resp.status);
    assertTrue("no cookie may be set for an uncommitted change: " + resp.setCookies,
        resp.setCookies.isEmpty());
    verifyNoInteractions(emailSender);
    verify(obDal).rollbackAndClose();
  }

  /** A live cookie session of account {@code ACC1}, resolved from cookie {@code tok}. */
  private GoSessionRecord cookieSession() {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("ACC1");
    sessionRecord.setCsrfToken(CSRF);
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);
    return sessionRecord;
  }

  /** {@code POST /change-password} on the cookie session, from {@link #PASSWORD} to a strong one. */
  private static HttpServletRequest changePasswordRequest() throws Exception {
    return postWithSession("/change-password", new JSONObject()
        .put("currentPassword", PASSWORD)
        .put("newPassword", "N3w!Str0ngPassw0rd").toString(), "tok", CSRF);
  }

  /** An account {@code ACC1} whose local password is {@link #PASSWORD}. */
  private static Account passwordAccount() throws Exception {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC1");
    when(account.getEmail()).thenReturn(EMAIL);
    when(account.getName()).thenReturn("User");
    when(account.getPasswordHash()).thenReturn(storedHash(PASSWORD));
    return account;
  }

  /**
   * A stubbed {@link OBDal} whose {@code commitAndClose()} is recorded among the response events, or
   * fails when {@link CapturedResponse#failCommit} is set.
   */
  private static OBDal recordingDal(CapturedResponse resp) {
    OBDal obDal = mock(OBDal.class);
    doAnswer(inv -> {
      if (resp.failCommit) {
        throw new IllegalStateException("commit refused");
      }
      resp.events.add(COMMIT);
      return null;
    }).when(obDal).commitAndClose();
    return obDal;
  }

  /**
   * Stands for a {@link GoSessionService} call that writes {@code etgo_go_session}: records it among
   * the response events and answers {@code result}.
   */
  private static Answer<Object> sessionWrite(CapturedResponse resp, Object result) {
    return inv -> {
      resp.events.add(SESSION_WRITE);
      return result;
    };
  }

  /**
   * ETP-5628 — the session write was committed, and nothing reached the client before that commit:
   * no {@code Set-Cookie} and no body. A commit that happened before the session write (register
   * commits the account first) does not count.
   */
  private static void assertCommittedBeforeResponse(CapturedResponse resp) {
    int write = resp.events.lastIndexOf(SESSION_WRITE);
    assertTrue("the request never wrote the session: " + resp.events, write >= 0);
    int commit = resp.events.subList(write, resp.events.size()).indexOf(COMMIT);
    assertTrue("the session write must be committed before the response is sent: "
        + resp.events, commit >= 0);
    List<String> beforeCommit = resp.events.subList(0, write + commit);
    assertFalse("a cookie left before the session write was committed: " + resp.events,
        beforeCommit.contains(SET_COOKIE));
    assertFalse("the body was written before the session write was committed: " + resp.events,
        beforeCommit.contains(BODY));
    assertTrue("the response must still send its cookies: " + resp.events,
        resp.events.contains(SET_COOKIE));
  }

  private static String errorMessage(CapturedResponse resp) throws Exception {
    return new JSONObject(resp.body.toString()).getJSONObject("error").getString("message");
  }

  private static Claim claim(String value) {
    Claim c = mock(Claim.class);
    when(c.asString()).thenReturn(value);
    return c;
  }

  private static HttpServletRequest postEnv(String bodyJson, String cookieValue, String csrf)
      throws Exception {
    return postWithSession("/session/environment", bodyJson, cookieValue, csrf);
  }

  /** A same-origin JSON POST to {@code path} carrying the session cookie and the CSRF proof. */
  private static HttpServletRequest postWithSession(String path, String bodyJson,
      String cookieValue, String csrf) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn(path);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(bodyJson)));
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrf);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go" + path));
    if (cookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }

  @Test
  public void refreshRotatesAndSetsNewCookies() throws Exception {
    IssuedGoSession rotated = new IssuedGoSession("newtok", "newref", "newcsrf", new GoSessionRecord());
    when(goSessionService.refresh("rtok")).thenReturn(rotated);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(postRefresh("rtok"), resp.response);
    }

    assertEquals(200, resp.status);
    assertTrue(resp.cookie(GoSessionSecurity.COOKIE_NAME)
        .startsWith(GoSessionSecurity.COOKIE_NAME + "=newtok"));
    assertTrue(resp.cookie(GoSessionSecurity.REFRESH_COOKIE_NAME)
        .startsWith(GoSessionSecurity.REFRESH_COOKIE_NAME + "=newref"));
    assertEquals("newcsrf", new JSONObject(resp.body.toString()).getString("csrfToken"));
  }

  @Test
  public void refreshWithoutCookieReturns401() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(postRefresh(null), resp.response);
    }
    assertEquals(401, resp.status);
    verify(goSessionService, never()).refresh(anyString());
  }

  @Test
  public void refreshWithInvalidTokenReturns401AndClearsCookies() throws Exception {
    when(goSessionService.refresh("rtok")).thenReturn(null);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(postRefresh("rtok"), resp.response);
    }

    assertEquals(401, resp.status);
    assertTrue(resp.cookie(GoSessionSecurity.COOKIE_NAME).contains("Max-Age=0"));
  }

  @Test
  public void refreshWithForeignOriginIsForbidden() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn("/session/refresh");
    when(req.getHeader("Origin")).thenReturn("https://evil.example.test");
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go/session/refresh"));
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.REFRESH_COOKIE_NAME, "rtok") });

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doPost(req, resp.response);
    }

    assertEquals(403, resp.status);
    assertEquals("Origin not allowed", errorMessage(resp));
    verify(goSessionService, never()).refresh(anyString());
  }

  private static HttpServletRequest postRefresh(String refreshCookieValue) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn("/session/refresh");
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go/session/refresh"));
    if (refreshCookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.REFRESH_COOKIE_NAME, refreshCookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }

  private static String storedHash(String password) throws Exception {
    byte[] salt = new byte[16];
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update(salt);
    byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
  }

  private static HttpServletRequest jsonPost(String path, JSONObject body) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn(path);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));
    return req;
  }

  private static HttpServletRequest deleteRequest(String cookieValue, String csrf) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("DELETE");
    when(req.getPathInfo()).thenReturn("/session");
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrf);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go/session"));
    return req;
  }

  /** Mock {@link HttpServletResponse} that captures status, headers, {@code Set-Cookie}s and body. */
  private static final class CapturedResponse {
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final Map<String, String> headers = new HashMap<>();
    final List<String> setCookies = new ArrayList<>();
    final StringWriter body = new StringWriter();
    /**
     * ETP-5628 — what reached the response, and when the session was written and the request
     * transaction committed, in order: {@link #SESSION_WRITE}, {@link #COMMIT},
     * {@link #SET_COOKIE} and {@link #BODY}.
     */
    final List<String> events = new ArrayList<>();
    /** ETP-5628 — when set, the {@link #recordingDal} commit of this request fails. */
    boolean failCommit;
    int status;

    CapturedResponse() {
      try {
        doAnswer(inv -> {
          headers.put(inv.getArgument(0), inv.getArgument(1));
          return null;
        }).when(response).setHeader(anyString(), anyString());
        doAnswer(inv -> {
          if ("Set-Cookie".equals(inv.<String>getArgument(0))) {
            setCookies.add(inv.getArgument(1));
            events.add(SET_COOKIE);
          }
          return null;
        }).when(response).addHeader(anyString(), anyString());
        doAnswer(inv -> {
          status = inv.getArgument(0);
          return null;
        }).when(response).setStatus(anyInt());
        PrintWriter writer = new PrintWriter(body);
        when(response.getWriter()).thenAnswer(inv -> {
          events.add(BODY);
          return writer;
        });
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    /** The {@code Set-Cookie} value for the given cookie name, or {@code null}. */
    String cookie(String name) {
      return setCookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }
  }
}
