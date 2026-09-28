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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.JwtAuthUtils;
import com.etendoerp.go.onboarding.OnboardingCompanyDataService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5443 — {@code resolveTenantSession} must resolve the tenant from the {@code
 * __Host-go_session} cookie session's SELECTED ENVIRONMENT ({@code ctxClientId}/{@code
 * ctxOrgId} on the {@code GoSessionRecord}), not only from a Bearer NEO JWT.
 *
 * <p>Before the fix, a cookie-only request never carries an {@code Authorization} header, so
 * {@code resolveTenantSession} decoded a {@code null} bearer token, resolved no client, and every
 * cookie-authenticated caller of {@code GET /sws/go/onboarding/company-data} got a 400
 * ("This endpoint requires an environment session") — even one sitting inside a real environment.
 *
 * <p>Uses {@code GET /sws/go/onboarding/company-data} as the probe endpoint: it is the one caller
 * of {@code resolveTenantSession} today.
 */
public class EtendoGoJwtServletTenantSessionCookieTest {

  private static final String PATH = "/onboarding/company-data";
  private static final String SESSION_TOKEN = "session-cookie-token";
  private static final String PLATFORM_TOKEN = "platform-bearer-token";
  private static final String ACCOUNT_ID = "account-1";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String FOREIGN_CLIENT_ID = "client-foreign";
  private static final String MISSING_ENV_MESSAGE = "This endpoint requires an environment session";
  private static final String FOREIGN_CLIENT_MESSAGE =
      "The session client is not owned by this account";

  // ===================== Owned environment (cookie) → 200 =====================

  @Test
  public void cookieSessionWithOwnedEnvironmentReturns200ForThatClient() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(CLIENT_ID, ORG_ID));
    when(fixture.companyDataService.read(CLIENT_ID, ORG_ID)).thenReturn(null);

    HttpServletRequest req = cookieGet(SESSION_TOKEN, null);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    verify(fixture.companyDataService).read(CLIENT_ID, ORG_ID);
  }

  // ===================== Foreign environment (cookie) → same refusal as bearer =====================

  @Test
  public void cookieSessionWithForeignEnvironmentAnswersSameForbiddenAsBearer() throws Exception {
    Fixture fixture = new Fixture();

    // Cookie session selected into a client the account does not own.
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(FOREIGN_CLIENT_ID, ORG_ID));
    HttpServletRequest cookieReq = cookieGet(SESSION_TOKEN, null);
    ResponseCapture cookieResp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(FOREIGN_CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(false);
      fixture.servlet.doGet(cookieReq, cookieResp.response);
    }

    // Bearer NEO JWT claiming the same foreign client.
    HttpServletRequest bearerReq = bearerGet(PLATFORM_TOKEN, null);
    ResponseCapture bearerResp = mockResponse();
    // Built before the stubbing chain below opens: a mock() call nested inside an in-progress
    // when(...).thenReturn(...) trips Mockito's UnfinishedStubbingException.
    DecodedJWT foreignJwt = jwtWithClaims(FOREIGN_CLIENT_ID, ORG_ID);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(FOREIGN_CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(false);
      sws.when(() -> SecureWebServicesUtils.decodeToken(PLATFORM_TOKEN)).thenReturn(foreignJwt);
      fixture.servlet.doGet(bearerReq, bearerResp.response);
    }

    assertEquals(403, cookieResp.status);
    assertEquals(cookieResp.status, bearerResp.status);
    assertEquals(FOREIGN_CLIENT_MESSAGE, cookieResp.errorMessage());
    assertEquals(bearerResp.errorMessage(), cookieResp.errorMessage());
    verifyNoInteractions(fixture.companyDataService);
  }

  // ===================== No environment selected (cookie) → same 4xx as bearer's missing tenant ====

  @Test
  public void cookieSessionWithNoEnvironmentSelectedAnswersSameBadRequestAsBearerMissingTenant()
      throws Exception {
    Fixture fixture = new Fixture();

    // Cookie session authenticated but never switched into an environment: no ctxClientId.
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithNoEnvironment());
    HttpServletRequest cookieReq = cookieGet(SESSION_TOKEN, null);
    ResponseCapture cookieResp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      fixture.servlet.doGet(cookieReq, cookieResp.response);
    }

    // Bearer: a pure account-session token, carrying no NEO environment claims at all.
    HttpServletRequest bearerReq = bearerGet(PLATFORM_TOKEN, null);
    ResponseCapture bearerResp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      sws.when(() -> SecureWebServicesUtils.decodeToken(PLATFORM_TOKEN)).thenReturn(null);
      fixture.servlet.doGet(bearerReq, bearerResp.response);
    }

    assertEquals(400, cookieResp.status);
    assertEquals(cookieResp.status, bearerResp.status);
    assertEquals(MISSING_ENV_MESSAGE, cookieResp.errorMessage());
    assertEquals(bearerResp.errorMessage(), cookieResp.errorMessage());
    verifyNoInteractions(fixture.companyDataService);
  }

  // ===================== A client id in query/body is ignored =====================

  @Test
  public void clientIdSuppliedInQueryStringIsIgnoredUnderCookieSession() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(CLIENT_ID, ORG_ID));
    when(fixture.companyDataService.read(CLIENT_ID, ORG_ID)).thenReturn(null);

    // The caller tries to name a different tenant via the query string.
    HttpServletRequest req = cookieGet(SESSION_TOKEN,
        "clientId=" + FOREIGN_CLIENT_ID + "&organizationId=org-foreign");
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    // The session's own client/org, never the query string's foreign ids.
    verify(fixture.companyDataService).read(CLIENT_ID, ORG_ID);
    verify(fixture.companyDataService, never()).read(FOREIGN_CLIENT_ID, "org-foreign");
  }

  // ===================== Missing ctxOrgId falls back to org "0" =====================

  @Test
  public void cookieSessionWithNullOrgFallsBackToOrgZero() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(CLIENT_ID, null));
    when(fixture.companyDataService.read(CLIENT_ID, "0")).thenReturn(null);

    HttpServletRequest req = cookieGet(SESSION_TOKEN, null);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    verify(fixture.companyDataService).read(CLIENT_ID, "0");
  }

  @Test
  public void cookieSessionWithBlankOrgFallsBackToOrgZero() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(CLIENT_ID, ""));
    when(fixture.companyDataService.read(CLIENT_ID, "0")).thenReturn(null);

    HttpServletRequest req = cookieGet(SESSION_TOKEN, null);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    verify(fixture.companyDataService).read(CLIENT_ID, "0");
  }

  // ===================== Cookie session wins over a conflicting Bearer header =====================

  /**
   * A cookie session is always resolved through {@code AuthenticatedAccount.sessionRecord}
   * ({@code resolveAuthenticatedAccountContext} short-circuits to the {@code AUTHENTICATED}
   * branch before ever looking at a bearer header). So a Bearer header sent alongside a valid
   * session cookie must never influence the resolved tenant, and {@code
   * SecureWebServicesUtils.decodeToken} must never even be invoked — proven here by stubbing it
   * to return a DIFFERENT (foreign) client, then asserting both that the session's own client won
   * and that the decode call itself never happened.
   */
  @Test
  public void cookieSessionIgnoresConflictingBearerJwtAndNeverDecodesIt() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(CLIENT_ID, ORG_ID));
    when(fixture.companyDataService.read(CLIENT_ID, ORG_ID)).thenReturn(null);
    // Built before the stubbing chain below opens (see the note in jwtWithClaims callers above).
    DecodedJWT foreignJwt = jwtWithClaims(FOREIGN_CLIENT_ID, "org-foreign");

    HttpServletRequest req = cookieGetWithBearer(SESSION_TOKEN, PLATFORM_TOKEN);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      // Would resolve to the foreign client IF the code ever decoded it — it must not.
      sws.when(() -> SecureWebServicesUtils.decodeToken(PLATFORM_TOKEN)).thenReturn(foreignJwt);
      fixture.servlet.doGet(req, resp.response);

      assertEquals(200, resp.status);
      verify(fixture.companyDataService).read(CLIENT_ID, ORG_ID);
      verify(fixture.companyDataService, never()).read(FOREIGN_CLIENT_ID, "org-foreign");
      sws.verifyNoInteractions();
    }
  }

  // ===================== Bearer path unchanged =====================

  @Test
  public void bearerPathStillResolvesTenantFromJwtClaims() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.companyDataService.read(CLIENT_ID, ORG_ID)).thenReturn(null);

    HttpServletRequest req = bearerGet(PLATFORM_TOKEN, null);
    ResponseCapture resp = mockResponse();
    DecodedJWT jwt = jwtWithClaims(CLIENT_ID, ORG_ID);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      sws.when(() -> SecureWebServicesUtils.decodeToken(PLATFORM_TOKEN)).thenReturn(jwt);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    verify(fixture.companyDataService).read(CLIENT_ID, ORG_ID);
  }

  // ===================== Parity: cookie and bearer agree for the same account/client =====================

  @Test
  public void cookieAndBearerParityForSameAccountAndClient() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.companyDataService.read(CLIENT_ID, ORG_ID)).thenReturn(null);

    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.sessionWithEnvironment(CLIENT_ID, ORG_ID));
    HttpServletRequest cookieReq = cookieGet(SESSION_TOKEN, null);
    ResponseCapture cookieResp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      fixture.servlet.doGet(cookieReq, cookieResp.response);
    }

    HttpServletRequest bearerReq = bearerGet(PLATFORM_TOKEN, null);
    ResponseCapture bearerResp = mockResponse();
    DecodedJWT jwt = jwtWithClaims(CLIENT_ID, ORG_ID);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
          .thenReturn(true);
      sws.when(() -> SecureWebServicesUtils.decodeToken(PLATFORM_TOKEN)).thenReturn(jwt);
      fixture.servlet.doGet(bearerReq, bearerResp.response);
    }

    assertEquals(200, cookieResp.status);
    assertEquals(cookieResp.status, bearerResp.status);
    verify(fixture.companyDataService, times(2)).read(CLIENT_ID, ORG_ID);
  }

  // ===================== Fixture & request/response plumbing =====================

  private static final class Fixture {
    final GoSessionService goSessionService = mock(GoSessionService.class);
    final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
        goSessionService);
    final OnboardingCompanyDataService companyDataService =
        mock(OnboardingCompanyDataService.class);
    final Account account = mock(Account.class);

    Fixture() {
      servlet.onboardingCompanyDataService = companyDataService;
      // ETP-5455: resolveTenantSession now consults the commercial policy; a mock with no stubs
      // answers null (a tenant with no lifecycle metadata = allowed), so this class keeps
      // testing tenant resolution alone and never reaches the real service's DAL lookups.
      servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
      when(account.getId()).thenReturn(ACCOUNT_ID);
      when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
    }

    GoSessionRecord sessionWithEnvironment(String clientId, String orgId) {
      GoSessionRecord record = new GoSessionRecord();
      record.setAccountId(ACCOUNT_ID);
      record.setCtxClientId(clientId);
      record.setCtxOrgId(orgId);
      return record;
    }

    GoSessionRecord sessionWithNoEnvironment() {
      GoSessionRecord record = new GoSessionRecord();
      record.setAccountId(ACCOUNT_ID);
      return record;
    }
  }

  /** A GET request carrying the {@code __Host-go_session} cookie; GET is CSRF-exempt. */
  private static HttpServletRequest cookieGet(String cookieValue, String queryString) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(PATH);
    if (queryString != null) {
      when(req.getQueryString()).thenReturn(queryString);
      when(req.getParameter(anyString())).thenAnswer(call ->
          String.valueOf(call.getArgument(0)).toLowerCase().contains("organization")
              ? "org-foreign" : FOREIGN_CLIENT_ID);
    }
    when(req.getHeader(anyString())).thenReturn(null);
    when(req.getHeader("Authorization")).thenReturn(null);
    if (cookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }

  /** A GET request carrying BOTH the session cookie and a (conflicting) Bearer header. */
  private static HttpServletRequest cookieGetWithBearer(String cookieValue, String bearerToken) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(PATH);
    when(req.getHeader(anyString())).thenReturn(null);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + bearerToken);
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    return req;
  }

  /** A request carrying only the legacy {@code Authorization: Bearer} header, no cookie. */
  private static HttpServletRequest bearerGet(String token, String queryString) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(PATH);
    if (queryString != null) {
      when(req.getQueryString()).thenReturn(queryString);
      when(req.getParameter(anyString())).thenReturn(FOREIGN_CLIENT_ID);
    }
    when(req.getCookies()).thenReturn(null);
    when(req.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
    return req;
  }

  /** A {@link DecodedJWT} carrying the given NEO {@code client}/{@code organization} claims. */
  private static DecodedJWT jwtWithClaims(String clientId, String orgId) {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim clientClaim = mock(Claim.class);
    when(clientClaim.asString()).thenReturn(clientId);
    Claim orgClaim = mock(Claim.class);
    when(orgClaim.asString()).thenReturn(orgId);
    when(jwt.getClaim(JwtAuthUtils.CLAIM_CLIENT)).thenReturn(clientClaim);
    when(jwt.getClaim(JwtAuthUtils.CLAIM_ORG)).thenReturn(orgClaim);
    return jwt;
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
    doAnswer(inv -> null).when(response).setContentType(anyString());
    doAnswer(inv -> null).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    JSONObject json() throws Exception {
      return new JSONObject(body.toString());
    }

    String errorMessage() throws Exception {
      return json().getJSONObject("error").getString("message");
    }
  }
}
