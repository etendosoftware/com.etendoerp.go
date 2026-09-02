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
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
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
 */
public class GoSessionEndpointsTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String EMAIL = "user@example.test";
  private static final String PASSWORD = "Str0ng!Passw0rd";
  private static final String CSRF = "csrf-token-value-123456";

  private final GoSessionService goSessionService = mock(GoSessionService.class);
  private final EtendoGoSsoProviderRegistry ssoRegistry = mock(EtendoGoSsoProviderRegistry.class);
  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
      mock(TransactionalAuthEmailSender.class), ssoRegistry, goSessionService);

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
    verify(goSessionService, never()).revoke(any());
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
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1")).thenReturn(account);
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
    EtendoGoJwtSupport.RoleListData roleListData = new EtendoGoJwtSupport.RoleListData();
    roleListData.roleArray = new JSONArray().put(new JSONObject().put("id", "R1"));

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
    when(goSessionService.resolve("tok")).thenReturn(sessionRecord);

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(EMAIL);

    EtendoGoJwtSupport.RoleListData roleListData = new EtendoGoJwtSupport.RoleListData();
    roleListData.firstRoleId = "R1";
    roleListData.roleArray = new JSONArray().put(new JSONObject()
        .put("id", "R1")
        .put("orgList", new JSONArray().put(new JSONObject().put("id", "O1"))));

    User user = mock(User.class);
    Role role = mock(Role.class);
    OBDal obDal = mock(OBDal.class);
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

    GoSessionRecord rotatedRecord = new GoSessionRecord();
    rotatedRecord.setUserId("U1");
    rotatedRecord.setRoleId("R1");
    rotatedRecord.setCtxClientId("C1");
    rotatedRecord.setCtxOrgId("O1");
    rotatedRecord.setWarehouseId("W1");
    IssuedGoSession rotated = new IssuedGoSession("newtok", "newref", "newcsrf", rotatedRecord);
    when(goSessionService.rotate(any())).thenReturn(rotated);

    CapturedResponse resp = new CapturedResponse();
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

  private static Claim claim(String value) {
    Claim c = mock(Claim.class);
    when(c.asString()).thenReturn(value);
    return c;
  }

  private static HttpServletRequest postEnv(String bodyJson, String cookieValue, String csrf)
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn("/session/environment");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(bodyJson)));
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrf);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go/session/environment"));
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
          }
          return null;
        }).when(response).addHeader(anyString(), anyString());
        doAnswer(inv -> {
          status = inv.getArgument(0);
          return null;
        }).when(response).setStatus(anyInt());
        when(response.getWriter()).thenReturn(new PrintWriter(body));
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
