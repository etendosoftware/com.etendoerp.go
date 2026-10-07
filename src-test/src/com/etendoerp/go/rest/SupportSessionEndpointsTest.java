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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.etendoerp.go.supportaccess.SupportAccessRecord;
import com.etendoerp.go.supportaccess.SupportAccessService;
import com.etendoerp.go.supportaccess.SupportHandoffService;
import com.etendoerp.go.supportaccess.SupportTicketException;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5351 (T4/T5) — the support endpoints of {@link EtendoGoJwtServlet} and the refusals a
 * support session gets on the account surface. Same no-database style as
 * {@code GoSessionEndpointsTest}: the session and support services are mocks and the DAL helpers
 * are statically stubbed.
 */
public class SupportSessionEndpointsTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String TOKEN = "tok";
  private static final String TICKET = "pass_value-1";
  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);
  private static final String ERROR = "error";
  private static final String CODE = "code";

  private final GoSessionService goSessionService = mock(GoSessionService.class);
  private final EtendoGoSsoProviderRegistry ssoRegistry = mock(EtendoGoSsoProviderRegistry.class);
  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
      mock(TransactionalAuthEmailSender.class), ssoRegistry, goSessionService);
  private final SupportAccessService supportAccessService = mock(SupportAccessService.class);
  private final SupportHandoffService supportHandoffService = mock(SupportHandoffService.class);

  @Before
  public void inject() {
    servlet.sessionRoleReconciler = mock(GoSessionRoleReconciler.class);
    servlet.supportAccessService = supportAccessService;
    servlet.supportHandoffService = supportHandoffService;
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);
  }

  private static GoSessionRecord supportSession() {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setId("S1");
    sessionRecord.setAccountId(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    sessionRecord.setAuthMethod(SupportAccessGuard.AUTH_METHOD_SUPPORT);
    sessionRecord.setSupportAccessId("ACCESS1");
    sessionRecord.setCsrfToken(CSRF);
    sessionRecord.setUserId(SUPPORT_USER);
    sessionRecord.setRoleId("R1");
    sessionRecord.setCtxClientId(CLIENT);
    sessionRecord.setCtxOrgId("O1");
    sessionRecord.setWarehouseId("W1");
    return sessionRecord;
  }

  private static Account supportAccount() {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    when(account.getEmail()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL);
    when(account.getName()).thenReturn("Soporte Etendo");
    return account;
  }

  // ------------------------------------------------------------------ handoff (T4)

  @Test
  public void handoffOpensTheSessionWithTheEnvironmentShape() throws Exception {
    GoSessionRecord created = supportSession();
    IssuedGoSession issued = new IssuedGoSession("new-tok", "new-ref", "new-csrf", created);
    when(supportHandoffService.open(eq(TICKET), any(), any())).thenReturn(
        new SupportHandoffService.SupportHandoff(issued, new SupportAccessRecord()));
    when(supportAccessService.describeSupportSession(created)).thenReturn(new JSONObject()
        .put("clientId", CLIENT).put("clientName", "Acme SL")
        .put("expiresAt", "2026-10-05T18:00:00Z"));
    Account account = supportAccount();

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(
          SupportAccessGuard.SUPPORT_ACCOUNT_ID)).thenReturn(account);
      support.when(() -> EtendoGoJwtSupport.loadRoleListData(SUPPORT_USER)).thenReturn(
          new EtendoGoJwtSupport.RoleListData("R1",
              new JSONArray().put(new JSONObject().put("id", "R1"))));
      servlet.doPost(handoffRequest(ORIGIN, null), resp.response);
    }

    assertEquals(200, resp.status);
    assertTrue(resp.cookie(GoSessionSecurity.COOKIE_NAME)
        .startsWith(GoSessionSecurity.COOKIE_NAME + "=new-tok"));
    assertNotNull(resp.cookie(GoSessionSecurity.REFRESH_COOKIE_NAME));
    JSONObject body = new JSONObject(resp.body.toString());
    assertEquals("success", body.getString("status"));
    assertEquals(CLIENT, body.getJSONObject("environment").getString("clientId"));
    assertEquals(SUPPORT_USER, body.getJSONObject("environment").getString("userId"));
    assertEquals("new-csrf", body.getString("csrfToken"));
    assertEquals("R1", body.getJSONArray("roleList").getJSONObject(0).getString("id"));
    assertEquals("Acme SL", body.getJSONObject("supportSession").getString("clientName"));
    assertFalse("no credential in the body", body.has("token"));
    assertEquals("no-store", resp.headers.get("Cache-Control"));
    verify(supportHandoffService).retireBrowserSession(null);
  }

  @Test
  public void handoffRetiresTheSessionCookieTheBrowserStillHeld() throws Exception {
    GoSessionRecord created = supportSession();
    when(supportHandoffService.open(eq(TICKET), any(), any())).thenReturn(
        new SupportHandoffService.SupportHandoff(
            new IssuedGoSession("new-tok", "new-ref", "new-csrf", created),
            new SupportAccessRecord()));

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      support.when(() -> EtendoGoJwtSupport.loadRoleListData(SUPPORT_USER)).thenReturn(
          new EtendoGoJwtSupport.RoleListData(null, new JSONArray()));
      servlet.doPost(handoffRequest(ORIGIN, "old-cookie"), resp.response);
    }

    assertEquals(200, resp.status);
    verify(supportHandoffService).retireBrowserSession("old-cookie");
    verify(goSessionService, never()).resolve("old-cookie");
  }

  @Test
  public void handoffWithAnInvalidPassIs401() throws Exception {
    assertTicketRefused(SupportTicketException.Reason.INVALID, "support_ticket_invalid");
  }

  @Test
  public void handoffWithAnExpiredPassIs401() throws Exception {
    assertTicketRefused(SupportTicketException.Reason.EXPIRED, "support_ticket_expired");
  }

  @Test
  public void handoffWithAReusedPassIs401() throws Exception {
    assertTicketRefused(SupportTicketException.Reason.USED, "support_ticket_used");
  }

  private void assertTicketRefused(SupportTicketException.Reason reason, String code)
      throws Exception {
    when(supportHandoffService.open(eq(TICKET), any(), any()))
        .thenThrow(new SupportTicketException(reason));

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      servlet.doPost(handoffRequest(ORIGIN, "old-cookie"), resp.response);
    }

    assertEquals(401, resp.status);
    assertEquals(code, new JSONObject(resp.body.toString()).getString(ERROR));
    assertNull("a refused pass sets no session", resp.cookie(GoSessionSecurity.COOKIE_NAME));
    verify(supportHandoffService, never()).retireBrowserSession(anyString());
  }

  @Test
  public void handoffFromAForeignOriginIsRefusedBeforeTouchingThePass() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    servlet.doPost(handoffRequest("https://evil.example.test", null), resp.response);

    assertEquals(403, resp.status);
    verify(supportHandoffService, never()).open(any(), any(), any());
  }

  @Test
  public void handoffWithoutTheTechnicalAccountFailsClosed() throws Exception {
    when(supportHandoffService.open(eq(TICKET), any(), any())).thenThrow(
        new SupportAccessException(SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING, "x"));

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      servlet.doPost(handoffRequest(ORIGIN, null), resp.response);
    }

    assertEquals(503, resp.status);
    assertEquals(SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING,
        new JSONObject(resp.body.toString()).getJSONObject(ERROR).getString(CODE));
    assertNull(resp.cookie(GoSessionSecurity.COOKIE_NAME));
  }

  // ------------------------------------------------------------------ logout, /me, /session

  @Test
  public void logoutOfASupportSessionClosesTheAudit() throws Exception {
    GoSessionRecord sessionRecord = supportSession();
    when(goSessionService.resolve(TOKEN)).thenReturn(sessionRecord);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      servlet.doDelete(request("DELETE", "/session", null), resp.response);
    }

    assertEquals(204, resp.status);
    verify(goSessionService).revoke(sessionRecord);
    verify(supportAccessService).closeOnLogout(sessionRecord);
  }

  @Test
  public void meDescribesTheSupportSession() throws Exception {
    GoSessionRecord sessionRecord = supportSession();
    when(goSessionService.resolve(TOKEN)).thenReturn(sessionRecord);
    when(supportAccessService.describeSupportSession(sessionRecord)).thenReturn(
        new JSONObject().put("clientId", CLIENT).put("clientName", "Acme SL")
            .put("expiresAt", "2026-10-05T18:00:00Z"));
    Account account = supportAccount();

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EmailVerificationDalHelper> verify =
            mockStatic(EmailVerificationDalHelper.class);
        MockedStatic<AccountIdentityDalHelper> identities =
            mockStatic(AccountIdentityDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(
          SupportAccessGuard.SUPPORT_ACCOUNT_ID)).thenReturn(account);
      identities.when(() -> AccountIdentityDalHelper.identitiesFor(account))
          .thenReturn(new ArrayList<>());
      servlet.doGet(request("GET", "/me", null), resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject block = new JSONObject(resp.body.toString()).getJSONObject("supportSession");
    assertEquals(CLIENT, block.getString("clientId"));
    assertEquals("2026-10-05T18:00:00Z", block.getString("expiresAt"));
  }

  // ------------------------------------------------------------------ tenant pinning (T5)

  @Test
  public void aSupportSessionCannotEnterAnotherUsersEnvironment() throws Exception {
    when(goSessionService.resolve(TOKEN)).thenReturn(supportSession());
    Account account = supportAccount();

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(
          SupportAccessGuard.SUPPORT_ACCOUNT_ID)).thenReturn(account);
      servlet.doPost(request("POST", "/session/environment",
          new JSONObject().put("userId", "OTHER_TENANT_USER").toString()), resp.response);
      support.verify(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(any(), any()),
          never());
    }

    assertSupportForbidden(resp);
    verify(goSessionService, never()).rotate(any());
  }

  @Test
  public void aSupportSessionMayReenterItsOwnEnvironment() throws Exception {
    GoSessionRecord sessionRecord = supportSession();
    when(goSessionService.resolve(TOKEN)).thenReturn(sessionRecord);
    Account account = supportAccount();
    User user = mock(User.class);
    Role role = mock(Role.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, SUPPORT_USER)).thenReturn(user);
    when(obDal.get(Role.class, "R1")).thenReturn(role);
    DecodedJWT decoded = mock(DecodedJWT.class);
    Claim userClaim = claim(SUPPORT_USER);
    Claim roleClaim = claim("R1");
    Claim clientClaim = claim(CLIENT);
    Claim orgClaim = claim("O1");
    Claim warehouseClaim = claim("W1");
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("role")).thenReturn(roleClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("organization")).thenReturn(orgClaim);
    when(decoded.getClaim("warehouse")).thenReturn(warehouseClaim);
    EtendoGoJwtSupport.RoleListData roleListData = new EtendoGoJwtSupport.RoleListData("R1",
        new JSONArray().put(new JSONObject().put("id", "R1")
            .put("orgList", new JSONArray().put(new JSONObject().put("id", "O1")))));

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(
          SupportAccessGuard.SUPPORT_ACCOUNT_ID)).thenReturn(account);
      support.when(() -> EtendoGoJwtSupport.loadRoleListData(SUPPORT_USER))
          .thenReturn(roleListData);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn("jwt");
      sws.when(() -> SecureWebServicesUtils.decodeToken("jwt")).thenReturn(decoded);
      servlet.doPost(request("POST", "/session/environment", new JSONObject()
          .put("userId", SUPPORT_USER).put("roleId", "R1").put("orgId", "O1").toString()),
          resp.response);
      support.verify(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(any(), any()),
          never());
    }

    assertEquals(200, resp.status);
    assertEquals(CLIENT, new JSONObject(resp.body.toString()).getJSONObject("environment")
        .getString("clientId"));
  }

  @Test
  public void environmentsOfASupportSessionListOnlyItsTenant() throws Exception {
    GoSessionRecord sessionRecord = supportSession();
    when(goSessionService.resolve(TOKEN)).thenReturn(sessionRecord);
    Account account = supportAccount();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    when(client.getName()).thenReturn("Acme SL");
    User user = mock(User.class);
    when(user.getClient()).thenReturn(client);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, SUPPORT_USER)).thenReturn(user);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(
          SupportAccessGuard.SUPPORT_ACCOUNT_ID)).thenReturn(account);
      dal.when(() -> EtendoGoJwtDalHelper.findNonStarOrganizations(CLIENT))
          .thenReturn(Collections.emptyList());
      dal.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(client, null, user))
          .thenReturn(new JSONObject().put("clientId", CLIENT));
      servlet.doGet(request("GET", "/environments", null), resp.response);
      dal.verify(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(anyString()),
          never());
    }

    assertEquals(200, resp.status);
    JSONArray environments = new JSONObject(resp.body.toString()).getJSONArray("environments");
    assertEquals(1, environments.length());
    assertEquals(CLIENT, environments.getJSONObject(0).getString("clientId"));
  }

  // ------------------------------------------------------------------ account writes (T5)

  @Test
  public void supportSessionCannotChangeThePassword() throws Exception {
    assertAccountWriteRefused("/change-password", new JSONObject()
        .put("currentPassword", "Old1!Passw0rd").put("newPassword", "Str0ng!Passw0rd"));
  }

  @Test
  public void supportSessionCannotRemoveAnAuthMethod() throws Exception {
    assertAccountWriteRefused("/auth-methods/remove", new JSONObject().put("method", "password"));
  }

  @Test
  public void supportSessionCannotSendInvitations() throws Exception {
    assertAccountWriteRefused("/company-invitations",
        new JSONObject().put("email", "someone@example.test"));
  }

  @Test
  public void supportSessionCannotAcceptInvitations() throws Exception {
    assertAccountWriteRefused("/company-invitations/accept",
        new JSONObject().put("token", "invite"));
  }

  @Test
  public void supportSessionCannotCreateEnvironments() throws Exception {
    assertAccountWriteRefused("/onboarding", new JSONObject().put("clientName", "Escape SL"));
  }

  @Test
  public void supportSessionCannotPurchase() throws Exception {
    assertAccountWriteRefused("/billing/purchases", new JSONObject().put("clientName", "X"));
  }

  @Test
  public void supportSessionCannotResendTheVerificationEmail() throws Exception {
    assertAccountWriteRefused("/verify-email/resend", new JSONObject());
  }

  private void assertAccountWriteRefused(String path, JSONObject body) throws Exception {
    when(goSessionService.resolve(TOKEN)).thenReturn(supportSession());
    Account account = supportAccount();

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(
          SupportAccessGuard.SUPPORT_ACCOUNT_ID)).thenReturn(account);
      servlet.doPost(request("POST", path, body.toString()), resp.response);
      dal.verify(() -> EtendoGoJwtDalHelper.changePassword(any(), any(), any(), any()), never());
    }

    assertSupportForbidden(resp);
  }

  // ------------------------------------------------------------------ ways in (T5)

  @Test
  public void nobodyRegistersTheSupportEmail() throws Exception {
    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.doPost(request("POST", "/session/register", new JSONObject()
          .put("email", SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL)
          .put("password", "Str0ng!Passw0rd").put("name", "Intruder").toString()),
          resp.response);
      dal.verify(() -> EtendoGoJwtDalHelper.createAccount(any(), any(), any(), any()), never());
    }

    assertEquals(400, resp.status);
    assertEquals("EMAIL_ALREADY_REGISTERED",
        new JSONObject(resp.body.toString()).getJSONObject(ERROR).getString(CODE));
  }

  @Test
  public void nobodySignsInWithSsoAsTheSupportAccount() throws Exception {
    EtendoGoSsoAssertion assertion = mock(EtendoGoSsoAssertion.class);
    when(assertion.getProvider()).thenReturn("google");
    when(assertion.getSubject()).thenReturn("sub-1");
    when(assertion.getEmail()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL);
    when(ssoRegistry.verify(eq("google"), any(), anyString())).thenReturn(assertion);

    CapturedResponse resp = new CapturedResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.doPost(request("POST", "/session/sso/google",
          new JSONObject().put("credential", "id-token").toString()), resp.response);
      dal.verify(() -> EtendoGoJwtDalHelper.createSsoAccount(any(), any(), any(), any(), any(),
          any(), any()), never());
    }

    assertEquals(401, resp.status);
    verify(goSessionService, never()).create(anyString(), anyString(), any(), any());
  }

  // ------------------------------------------------------------------ helpers

  private static void assertSupportForbidden(CapturedResponse resp) throws Exception {
    assertEquals(403, resp.status);
    assertEquals(SupportAccessGuard.ERROR_CODE_FORBIDDEN,
        new JSONObject(resp.body.toString()).getJSONObject(ERROR).getString(CODE));
  }

  private static Claim claim(String value) {
    Claim c = mock(Claim.class);
    when(c.asString()).thenReturn(value);
    return c;
  }

  private static HttpServletRequest handoffRequest(String origin, String oldCookie)
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn("/session/support-handoff");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        new JSONObject().put("ticket", TICKET).toString())));
    when(req.getHeader("Origin")).thenReturn(origin);
    when(req.getHeader("User-Agent")).thenReturn("JUnit");
    when(req.getRemoteAddr()).thenReturn("10.0.0.9");
    when(req.getRequestURL()).thenReturn(
        new StringBuffer(ORIGIN + "/sws/go/session/support-handoff"));
    when(req.getCookies()).thenReturn(oldCookie == null ? null
        : new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, oldCookie) });
    return req;
  }

  /** A same-origin request carrying the session cookie and its CSRF token. */
  private static HttpServletRequest request(String method, String path, String jsonBody)
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        jsonBody == null ? "{}" : jsonBody)));
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(CSRF);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go" + path));
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, TOKEN) });
    return req;
  }

  /** Mock {@link HttpServletResponse} that captures status, headers, cookies and body. */
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
        }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());
        when(response.getWriter()).thenReturn(new PrintWriter(body));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    String cookie(String name) {
      return setCookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
    }
  }
}
