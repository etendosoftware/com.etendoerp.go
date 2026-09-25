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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoLegacyBearer;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;

/**
 * ETP-5455 — the account surface ({@code /sws/go/*}) must resolve every credential through ONE
 * resolver, whose only per-endpoint knob is which bearer it accepts: any account credential
 * (the wide lookup, environment JWTs included) or the platform token only (billing, which rejects
 * environment JWTs on purpose — see {@code findActiveAccountByPlatformToken}).
 *
 * <p>Pinned here, each red on develop:
 * <ul>
 *   <li>{@code POST /auth-methods/remove} resolves its own bearer inline, so the SPA's cookie
 *   session gets 401 there — and the frontend logs out on a 401.</li>
 *   <li>Neither that endpoint nor billing's bearer branch consults the legacy kill switch, so
 *   turning it off leaves both accepting Bearer tokens; billing does not count the use either.</li>
 *   <li>A blank {@code __Host-go_session} cookie sends billing down the cookie resolver's WIDE
 *   bearer lookup, so an environment JWT gets past the platform-token-only rule.</li>
 * </ul>
 *
 * <p>No database: the DAL helpers are mocked statically; nothing is written.
 */
class EtendoGoJwtServletAccountAuthParityTest {

  private static final String SUBSCRIPTION_PATH = "/billing/subscription";
  private static final String REMOVE_PATH = "/auth-methods/remove";
  private static final String REMOVE_BODY = "{\"method\":\"etp5455-none\"}";
  private static final String ORIGIN = "https://app.example.test";
  private static final String SESSION_TOKEN = "session-cookie-token";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String PLATFORM_TOKEN = "platform-token";
  private static final String ENVIRONMENT_JWT = "environment.jwt.token";
  private static final String ACCOUNT_ID = "account-1";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";

  @AfterEach
  void tearDown() {
    System.clearProperty(LEGACY_BEARER_PROPERTY);
  }

  // ===================== H2 — remove-auth-method under the cookie session =====================

  @Test
  void removeAuthMethodAcceptsTheCookieSession() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());

    ResponseCapture resp = fixture.post(cookiePost(REMOVE_PATH, CSRF, REMOVE_BODY), dal ->
        dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
            .thenReturn(fixture.account));

    assertEquals(404, resp.status, "the cookie must authenticate; 404 is the unknown method");
    assertEquals("AUTH_METHOD_NOT_FOUND", resp.errorCode());
  }

  @Test
  void removeAuthMethodWithoutTheCsrfProofIs403() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());

    ResponseCapture resp = fixture.post(cookiePost(REMOVE_PATH, null, REMOVE_BODY), dal -> { });

    assertEquals(403, resp.status);
  }

  /** Any account credential: an environment JWT still reaches it while the switch is on. */
  @Test
  void removeAuthMethodStillAcceptsAnEnvironmentJwtWhileTheSwitchIsOn() throws Exception {
    Fixture fixture = new Fixture();

    ResponseCapture resp = fixture.post(bearerRequest(REMOVE_PATH, "POST", ENVIRONMENT_JWT,
        REMOVE_BODY), dal -> dal.when(
            () -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(ENVIRONMENT_JWT))
            .thenReturn(fixture.account));

    assertEquals(404, resp.status);
  }

  // ===================== the kill switch closes every Bearer path =====================

  @Test
  void removeAuthMethodRefusesABearerWhenTheSwitchIsOff() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");
    Fixture fixture = new Fixture();

    ResponseCapture resp = fixture.post(bearerRequest(REMOVE_PATH, "POST", ENVIRONMENT_JWT,
        REMOVE_BODY), dal -> dal.when(
            () -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(ENVIRONMENT_JWT))
            .thenReturn(fixture.account));

    assertEquals(401, resp.status);
  }

  @Test
  void billingRefusesThePlatformTokenWhenTheSwitchIsOff() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");
    Fixture fixture = new Fixture();

    ResponseCapture resp = fixture.get(bearerRequest(SUBSCRIPTION_PATH, "GET", PLATFORM_TOKEN,
        null), dal -> dal.when(
            () -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
            .thenReturn(fixture.account));

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore);
  }

  @Test
  void billingCountsAPlatformTokenAsALegacyBearerUse() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(null);
    long before = GoLegacyBearer.useCount();

    ResponseCapture resp = fixture.get(bearerRequest(SUBSCRIPTION_PATH, "GET", PLATFORM_TOKEN,
        null), dal -> dal.when(
            () -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
            .thenReturn(fixture.account));

    assertEquals(200, resp.status);
    assertEquals(before + 1, GoLegacyBearer.useCount(),
        "the counter says when the switch is safe to flip; billing's bearers must be in it");
  }

  // ===================== billing stays platform-token-only (decision 1) =====================

  @Test
  void billingRefusesAnEnvironmentJwt() throws Exception {
    Fixture fixture = new Fixture();

    ResponseCapture resp = fixture.get(bearerRequest(SUBSCRIPTION_PATH, "GET", ENVIRONMENT_JWT,
        null), wideLookupAccepts(fixture));

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore);
  }

  /** H8 — a blank cookie is no credential, so it must not change which bearer billing accepts. */
  @Test
  void aBlankSessionCookieDoesNotLetAnEnvironmentJwtIntoBilling() throws Exception {
    Fixture fixture = new Fixture();
    HttpServletRequest req = bearerRequest(SUBSCRIPTION_PATH, "GET", ENVIRONMENT_JWT, null);
    when(req.getCookies()).thenReturn(new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "") });

    ResponseCapture resp = fixture.get(req, wideLookupAccepts(fixture));

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore);
  }

  @Test
  void billingStillAcceptsThePlatformTokenWhileTheSwitchIsOn() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(null);

    ResponseCapture resp = fixture.get(bearerRequest(SUBSCRIPTION_PATH, "GET", PLATFORM_TOKEN,
        null), dal -> dal.when(
            () -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
            .thenReturn(fixture.account));

    assertEquals(200, resp.status);
  }

  // ===================== fixture =====================

  /** The wide lookup resolves the environment JWT, the narrow one does not — as in production. */
  private static DalStubs wideLookupAccepts(Fixture fixture) {
    return dal -> {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(ENVIRONMENT_JWT))
          .thenReturn(fixture.account);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(ENVIRONMENT_JWT))
          .thenReturn(null);
    };
  }

  @FunctionalInterface
  private interface DalStubs {
    void apply(MockedStatic<EtendoGoJwtDalHelper> dal);
  }

  private static final class Fixture {
    final GoSessionService goSessionService = mock(GoSessionService.class);
    final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
        goSessionService);
    final CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    final Account account = mock(Account.class);

    Fixture() {
      servlet.checkoutRequestStore = requestStore;
      servlet.stripeCustomerPortalService = mock(StripeCustomerPortalService.class);
      servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
      when(account.getId()).thenReturn(ACCOUNT_ID);
      when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
    }

    GoSessionRecord validSession() {
      GoSessionRecord sessionRecord = new GoSessionRecord();
      sessionRecord.setAccountId(ACCOUNT_ID);
      sessionRecord.setCsrfToken(CSRF);
      return sessionRecord;
    }

    ResponseCapture get(HttpServletRequest req, DalStubs stubs) throws Exception {
      return run(req, stubs, false);
    }

    ResponseCapture post(HttpServletRequest req, DalStubs stubs) throws Exception {
      return run(req, stubs, true);
    }

    private ResponseCapture run(HttpServletRequest req, DalStubs stubs, boolean post)
        throws Exception {
      ResponseCapture resp = mockResponse();
      try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
          MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
          MockedStatic<AccountIdentityDalHelper> identities =
              mockStatic(AccountIdentityDalHelper.class)) {
        identities.when(() -> AccountIdentityDalHelper.identitiesFor(any()))
            .thenReturn(Collections.emptyList());
        identities.when(() -> AccountIdentityDalHelper.identityForProvider(any(), anyString()))
            .thenReturn(null);
        stubs.apply(dal);
        if (post) {
          servlet.doPost(req, resp.response);
        } else {
          servlet.doGet(req, resp.response);
        }
        dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
            never());
      }
      return resp;
    }
  }

  private static HttpServletRequest cookiePost(String path, String csrf, String bodyJson)
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn(path);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(bodyJson)));
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrf);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + path));
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, SESSION_TOKEN) });
    return req;
  }

  private static HttpServletRequest bearerRequest(String path, String method, String token,
      String bodyJson) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
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
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    when(response.getWriter()).thenReturn(new PrintWriter(body));
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

    String errorCode() throws Exception {
      return new JSONObject(body.toString()).getJSONObject("error").getString("code");
    }
  }
}
