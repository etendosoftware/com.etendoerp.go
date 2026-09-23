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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;

/**
 * Unit tests for the {@code __Host-go_session} cookie path on the billing endpoints (ETP-5443,
 * ADR-0001). {@code resolvePlatformAccount}/{@code runWithPlatformAccount} now accept the SPA's
 * cookie session — resolved through the same {@code resolveAuthenticatedAccount} path {@code /me}
 * already uses, so a session-bound CSRF proof ({@code X-Go-CSRF}) is required on unsafe methods —
 * in addition to the legacy platform-token Bearer, which is left untouched when no cookie is sent.
 *
 * <p>The criterion pinned above all others, same as {@link EtendoGoJwtServletBillingSubscriptionTest},
 * is authorization: the account acted upon is always the one the session (or bearer token) resolves
 * to, never anything the caller supplies in the query string, headers or body.
 */
public class EtendoGoJwtServletBillingCookieAuthTest {

  private static final String SUBSCRIPTION_PATH = "/billing/subscription";
  private static final String PORTAL_PATH = "/billing/subscription/portal";
  private static final String PURCHASES_PATH = "/billing/purchases";
  private static final String ORIGIN = "https://app.example.test";
  private static final String SESSION_TOKEN = "session-cookie-token";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String PLATFORM_TOKEN = "platform-token";
  private static final String ACCOUNT_ID = "account-1";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String FOREIGN_CUSTOMER = "cus_foreign";
  private static final String FOREIGN_SUBSCRIPTION = "sub_foreign";

  // ===================== GET /billing/subscription (cookie) =====================

  @Test
  public void cookieGetSubscriptionAuthenticatesTheSessionAccountIgnoringForeignIds() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(null);

    HttpServletRequest req = cookieGet(SUBSCRIPTION_PATH, SESSION_TOKEN, true);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertFalse(resp.json().getBoolean("hasSubscription"));
    verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    verifyNoMoreInteractions(fixture.requestStore);
    verifyNoInteractions(fixture.portalService);
  }

  @Test
  public void invalidOrExpiredCookieAnswers401AndTouchesNothing() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(null);

    HttpServletRequest req = cookieGet(SUBSCRIPTION_PATH, SESSION_TOKEN, false);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  @Test
  public void noCredentialAnswers401AndTouchesNothing() throws Exception {
    Fixture fixture = new Fixture();

    HttpServletRequest req = cookieGet(SUBSCRIPTION_PATH, null, false);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  // ===================== POST /billing/purchases (cookie) =====================

  @Test
  public void cookiePostPurchasesWithoutCsrfIsForbidden() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());

    HttpServletRequest req = cookiePost(PURCHASES_PATH, SESSION_TOKEN, null,
        new JSONObject().put("clientName", "Acme Corp").toString());
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }

    assertEquals(403, resp.status);
    verifyNoInteractions(fixture.requestStore);
  }

  @Test
  public void cookiePostPurchasesWithValidCsrfActsOnTheSessionAccountIgnoringForeignIds()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());
    CheckoutRequest existing = mock(CheckoutRequest.class);
    when(existing.getCheckoutRequestStatus()).thenReturn("COMPLETED");
    when(existing.getRequest()).thenReturn("req-1");
    when(existing.getClientName()).thenReturn("Acme Corp");
    when(fixture.requestStore.findActiveForAccountAndClientName(ACCOUNT_ID, ACCOUNT_EMAIL, "Acme Corp"))
        .thenReturn(existing);

    String foreignBody = new JSONObject()
        .put("clientName", "Acme Corp")
        .put("customerId", FOREIGN_CUSTOMER)
        .put("subscriptionId", FOREIGN_SUBSCRIPTION)
        .toString();
    HttpServletRequest req = cookiePost(PURCHASES_PATH, SESSION_TOKEN, CSRF, foreignBody);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail(ACCOUNT_EMAIL))
          .thenReturn(true);
      fixture.servlet.doPost(req, resp.response);
    }

    // 409 (an active purchase already exists) proves auth let the request through — not 401/403 —
    // and that the row it acted on belongs to the session's account, never the foreign body ids.
    assertEquals(409, resp.status);
    verify(fixture.requestStore).findActiveForAccountAndClientName(ACCOUNT_ID, ACCOUNT_EMAIL, "Acme Corp");
    verifyNoMoreInteractions(fixture.requestStore);
  }

  // ===================== POST /billing/subscription/portal (cookie) =====================

  @Test
  public void cookiePostPortalWithoutCsrfIsForbidden() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());

    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, null, "{}");
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }

    assertEquals(403, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  @Test
  public void cookiePostPortalWithValidCsrfActsOnTheSessionAccountIgnoringForeignIds()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(null);

    String foreignBody = new JSONObject()
        .put("customerId", FOREIGN_CUSTOMER)
        .put("subscriptionId", FOREIGN_SUBSCRIPTION)
        .toString();
    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF, foreignBody);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }

    // 404 NO_SUBSCRIPTION (no purchase found) proves auth let the request through — not 401/403.
    assertEquals(404, resp.status);
    assertEquals("NO_SUBSCRIPTION", resp.errorCode());
    verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    verifyNoMoreInteractions(fixture.requestStore);
    verifyNoInteractions(fixture.portalService);
  }

  /**
   * {@code isUnsafeRequestAuthorized} requires the allowed {@code Origin} AND the CSRF token — a
   * correct {@code X-Go-CSRF} does not compensate for a disallowed origin, so this must fail on its
   * own, distinct from the CSRF-missing tests above (which use an allowed origin).
   */
  @Test
  public void cookiePostWithValidCsrfButDisallowedOriginIsForbidden() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());

    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF, "{}");
    when(req.getHeader("Origin")).thenReturn("https://evil.example.test");

    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      fixture.servlet.doPost(req, resp.response);
    }

    assertEquals(403, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  // ===================== Legacy Bearer is unaffected =====================

  /**
   * Without the session cookie, {@code resolvePlatformAccount} keeps the pre-ETP-5443 narrow
   * platform-token lookup verbatim — no CSRF proof is required on the POST, unlike the cookie path.
   */
  @Test
  public void legacyBearerStillAuthenticatesGetAndPostWithoutCsrf() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(null);

    HttpServletRequest getReq = bearerRequest(SUBSCRIPTION_PATH, "GET", PLATFORM_TOKEN, null);
    ResponseCapture getResp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      fixture.servlet.doGet(getReq, getResp.response);
    }
    assertEquals(200, getResp.status);
    assertFalse(getResp.json().getBoolean("hasSubscription"));

    HttpServletRequest postReq = bearerRequest(PORTAL_PATH, "POST", PLATFORM_TOKEN, "{}");
    ResponseCapture postResp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      fixture.servlet.doPost(postReq, postResp.response);
    }
    assertEquals(404, postResp.status);
    assertEquals("NO_SUBSCRIPTION", postResp.errorCode());
  }

  /**
   * The cookie always wins when both credentials are present: an invalid/expired session answers
   * 401 without ever falling back to a bearer header sent alongside it, cookie or not.
   */
  @Test
  public void invalidCookieWithValidBearerAlongsideAnswers401WithoutFallingBackToBearer()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(null);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(SUBSCRIPTION_PATH);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + PLATFORM_TOKEN);
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, SESSION_TOKEN) });

    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  /**
   * A cookie with the right name but a blank value is {@code NO_SESSION}, not a credential at all,
   * so it must not change which Bearer billing accepts. ETP-5455 — this used to send billing down
   * the cookie resolver's WIDE {@code findActiveAccountByBearerToken} lookup, which accepts
   * environment JWTs and so bypassed the platform-token-only rule; the single account resolver
   * keeps billing on the narrow {@code findActiveAccountByPlatformToken} lookup either way.
   */
  @Test
  public void blankCookieValueIsNoCredentialAndKeepsBillingOnThePlatformLookup() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(null);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(SUBSCRIPTION_PATH);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + PLATFORM_TOKEN);
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "") });

    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      fixture.servlet.doGet(req, resp.response);
      dal.verify(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(anyString()),
          never());
    }

    assertEquals(200, resp.status);
    verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
  }

  // ===================== Fixture & request/response plumbing =====================

  private static final class Fixture {
    final GoSessionService goSessionService = mock(GoSessionService.class);
    final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
        goSessionService);
    final CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    final StripeCustomerPortalService portalService = mock(StripeCustomerPortalService.class);
    final TenantEnvironmentLifecycleService lifecycle =
        mock(TenantEnvironmentLifecycleService.class);
    final Account account = mock(Account.class);

    Fixture() {
      servlet.checkoutRequestStore = requestStore;
      servlet.stripeCustomerPortalService = portalService;
      servlet.tenantEnvironmentLifecycleService = lifecycle;
      when(account.getId()).thenReturn(ACCOUNT_ID);
      when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
    }

    GoSessionRecord validSession() {
      GoSessionRecord record = new GoSessionRecord();
      record.setAccountId(ACCOUNT_ID);
      record.setCsrfToken(CSRF);
      return record;
    }
  }

  /** A GET request carrying (or not) the {@code __Host-go_session} cookie; GET is CSRF-exempt. */
  private static HttpServletRequest cookieGet(String path, String cookieValue,
      boolean withForeignParams) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(path);
    if (withForeignParams) {
      when(req.getQueryString()).thenReturn("subscriptionId=" + FOREIGN_SUBSCRIPTION
          + "&customer=" + FOREIGN_CUSTOMER);
      when(req.getParameter(anyString())).thenAnswer(call ->
          String.valueOf(call.getArgument(0)).toLowerCase().contains("customer")
              ? FOREIGN_CUSTOMER : FOREIGN_SUBSCRIPTION);
      when(req.getHeader(anyString())).thenReturn(FOREIGN_SUBSCRIPTION);
    }
    when(req.getHeader("Authorization")).thenReturn(null);
    if (cookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }

  /**
   * A POST request carrying the {@code __Host-go_session} cookie, a same-origin {@code Origin}
   * header, and the given (possibly absent) {@code X-Go-CSRF} proof.
   */
  private static HttpServletRequest cookiePost(String path, String cookieValue, String csrf,
      String bodyJson) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn(path);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(bodyJson)));
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader("Referer")).thenReturn(null);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrf);
    when(req.getHeader("Authorization")).thenReturn(null);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + path));
    if (cookieValue != null) {
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, cookieValue) });
    } else {
      when(req.getCookies()).thenReturn(null);
    }
    return req;
  }

  /** A request carrying only the legacy {@code Authorization: Bearer} header, no cookie. */
  private static HttpServletRequest bearerRequest(String path, String method, String token,
      String bodyJson) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
    when(req.getCookies()).thenReturn(null);
    if (bodyJson != null) {
      when(req.getContentType()).thenReturn("application/json");
      when(req.getReader()).thenReturn(new BufferedReader(new StringReader(bodyJson)));
    }
    return req;
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

    String errorCode() throws Exception {
      return json().getJSONObject("error").getString("code");
    }
  }
}
