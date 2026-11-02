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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Optional;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentAccessEnforcementFlag;
import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.usage.IsolatedReader;
import com.etendoerp.go.usage.quota.QuotaEvaluator;
import com.etendoerp.go.usage.quota.TenantQuotas;

/**
 * Unit tests for the {@code __Host-go_session} cookie path on the billing endpoints (ETP-5443,
 * ADR-0001). {@code runWithPlatformAccount}/{@code runWithPlatformContext} now accept the SPA's
 * cookie session — resolved through the same {@code resolveAuthenticatedAccount} path {@code /me}
 * already uses, so a session-bound CSRF proof ({@code X-Go-CSRF}) is required on unsafe methods —
 * in addition to the legacy platform-token Bearer, which is left untouched when no cookie is sent.
 *
 * <p>The criterion pinned above all others, same as {@link EtendoGoJwtServletBillingSubscriptionTest},
 * is authorization: the account acted upon is always the one the session (or bearer token) resolves
 * to, never anything the caller supplies in the query string, headers or body.
 *
 * <p>ETP-5051 (O-1) — a cookie session that has entered an environment resolves that environment's
 * own subscription, not the account's newest one — on the page, in its plan quotas and in the
 * Customer Portal — and an entered environment without a purchase of its own answers "no
 * subscription" instead of falling back to another environment's. An account-level session
 * ({@code ctxClientId} absent, blank or {@code "0"}) and the legacy bearer keep the newest.
 *
 * <p>ETP-5051 (O-1b) — the portal's optional {@code {"clientId"}} body names the environment a
 * "Mis empresas" row manages. Without one ({@code "0"}, blank, {@code {}} or no body at all) the
 * rule above applies; an unbought client, one without a Stripe customer, a non-string
 * {@code clientId} or a case variant of an owned id is {@code 404 NO_SUBSCRIPTION}; a body that is not a JSON object is 400; and the cookie session's
 * CSRF and Origin checks refuse the request before the body is read.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
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
  private static final String ENTERED_CLIENT = "client-entered";
  private static final String NEWEST_CLIENT = "client-newest";
  /** Portal bodies that name no environment; null stands for a request with no body at all. */
  private static final String[] NO_CLIENT_BODIES = { null, "", "   ", "{}",
      "{\"clientId\":\"0\"}", "{\"clientId\":\"  \"}", "{\"clientId\":\"\"}",
      "{\"clientId\":null}", " \n\t\r\n " };

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

  /**
   * ETP-5051 (O-1) — an account that owns several environments, each with its own Stripe
   * subscription. Inside one of them (the session's entered client) the page must describe THAT
   * environment, not the account's newest purchase: the quota banner of SPRING7 links here, and
   * used to land on SPRING8's plan.
   */
  @Test
  public void cookieGetSubscriptionInsideAnEnvironmentResolvesThatEnvironmentsSubscription()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
    CheckoutRequest newest = purchase("sub_newest", "cus_newest", NEWEST_CLIENT);
    CheckoutRequest entered = purchase("sub_entered", "cus_entered", ENTERED_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(newest);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        ENTERED_CLIENT)).thenReturn(entered);
    when(fixture.portalService.retrieveSubscription("sub_entered")).thenReturn(fixture.live);

    HttpServletRequest req = cookieGet(SUBSCRIPTION_PATH, SESSION_TOKEN, false);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EnvironmentAccessEnforcementFlag> flag =
            mockStatic(EnvironmentAccessEnforcementFlag.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(200, resp.status);
    JSONObject body = resp.json();
    assertTrue(body.getBoolean("hasSubscription"));
    assertEquals(ENTERED_CLIENT, body.getString("clientId"));
    verify(fixture.portalService).retrieveSubscription("sub_entered");
    verify(fixture.portalService, never()).retrieveSubscription("sub_newest");
    verify(fixture.lifecycle).resolve(ENTERED_CLIENT);
    verify(fixture.subscriptionService).findLatest(ENTERED_CLIENT);
    verify(fixture.subscriptionService, never()).findLatest(NEWEST_CLIENT);
  }

  /**
   * An entered environment the account has no billable purchase for (a demo, or one it was only
   * invited into) shows "no subscription" — never the account's newest purchase of another one.
   */
  @Test
  public void cookieGetSubscriptionInsideAnEnvironmentWithoutItsOwnPurchaseHasNoSubscription()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
    CheckoutRequest newest = purchase("sub_newest", "cus_newest", NEWEST_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(newest);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        ENTERED_CLIENT)).thenReturn(null);

    ResponseCapture resp = cookieGetSubscription(fixture);

    assertEquals(200, resp.status);
    JSONObject body = resp.json();
    assertFalse(body.getBoolean("hasSubscription"));
    assertEquals(1, body.length());
    verify(fixture.requestStore).findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        ENTERED_CLIENT);
    verify(fixture.requestStore, never()).findSubscriptionForAccount(anyString(), anyString());
    verifyNoInteractions(fixture.portalService, fixture.lifecycle, fixture.subscriptionService);
  }

  /**
   * {@code "0"} is the System client: a session with it, or with a blank client, has entered no
   * environment, so it stays on the account's newest purchase.
   */
  @Test
  public void cookieGetSubscriptionWithTheSystemOrABlankClientStaysAccountLevel()
      throws Exception {
    for (String accountLevelClient : new String[] { "0", "   " }) {
      Fixture fixture = new Fixture();
      when(fixture.goSessionService.resolve(SESSION_TOKEN))
          .thenReturn(fixture.enteredSession(accountLevelClient));
      when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
          .thenReturn(null);

      ResponseCapture resp = cookieGetSubscription(fixture);

      assertEquals(accountLevelClient, 200, resp.status);
      assertFalse(resp.json().getBoolean("hasSubscription"));
      verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
      verify(fixture.requestStore, never()).findSubscriptionForAccountClient(anyString(),
          anyString(), anyString());
    }
  }

  /**
   * The plan quotas follow the entered environment too: its own {@code ETGO_SUBSCRIPTION} row is
   * read and the quota evaluator measures that tenant, never the newest purchase's.
   */
  @Test
  public void cookieGetSubscriptionInsideAnEnvironmentMeasuresThatEnvironmentsQuotas()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
    CheckoutRequest newest = purchase("sub_newest", "cus_newest", NEWEST_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(newest);
    CheckoutRequest entered = purchase("sub_entered", "cus_entered", ENTERED_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        ENTERED_CLIENT)).thenReturn(entered);
    when(fixture.portalService.retrieveSubscription("sub_entered")).thenReturn(fixture.live);
    Plan plan = mock(Plan.class);
    when(plan.getSearchKey()).thenReturn("productive-monthly");
    when(plan.getName()).thenReturn("Productive monthly");
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(fixture.subscriptionService.findLatest(ENTERED_CLIENT))
        .thenReturn(Optional.of(subscription));
    when(fixture.planCatalog.listActiveQuotas(plan)).thenReturn(Collections.emptyList());
    QuotaEvaluator evaluator = mock(QuotaEvaluator.class);
    when(evaluator.evaluate(any(TenantQuotas.class), eq(QuotaEvaluator.Scope.ACCOUNT)))
        .thenReturn(Collections.emptyList());
    fixture.servlet.quotaEvaluator = evaluator;
    fixture.servlet.quotaReads = IsolatedReader.INLINE;

    ResponseCapture resp = cookieGetSubscription(fixture);

    assertEquals(200, resp.status);
    assertEquals("productive-monthly", resp.json().getString("planKey"));
    verify(fixture.subscriptionService).findLatest(ENTERED_CLIENT);
    verify(fixture.subscriptionService, never()).findLatest(NEWEST_CLIENT);
    ArgumentCaptor<TenantQuotas> tenant = ArgumentCaptor.forClass(TenantQuotas.class);
    verify(evaluator).evaluate(tenant.capture(), eq(QuotaEvaluator.Scope.ACCOUNT));
    assertEquals(ENTERED_CLIENT, tenant.getValue().getClientId());
  }

  // ETP-5675 — the cookie is the browser profile's, so a tab still showing another account sends
  // this session's cookie with its own account in X-Go-Account. It must not read this account's
  // billing: refused before anything is looked up.
  @Test
  public void cookieGetSubscriptionFromATabOfAnotherAccountIsForbiddenAndTouchesNothing()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());

    HttpServletRequest req = cookieGet(SUBSCRIPTION_PATH, SESSION_TOKEN, false);
    when(req.getHeader(GoSessionSecurity.ACCOUNT_HEADER)).thenReturn("another-account");
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      fixture.servlet.doGet(req, resp.response);
    }

    assertEquals(403, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
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
   * Every environment has its own Stripe customer: inside one, the portal must open for THAT
   * customer, the one whose subscription the page shows — never the newest purchase's.
   */
  @Test
  public void cookiePostPortalInsideAnEnvironmentOpensThatEnvironmentsCustomer()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
    CheckoutRequest newest = purchase("sub_newest", "cus_newest", NEWEST_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(newest);
    CheckoutRequest entered = purchase("sub_entered", "cus_entered", ENTERED_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        ENTERED_CLIENT)).thenReturn(entered);
    when(fixture.portalService.createSession("cus_entered")).thenReturn(
        new JSONObject().put("url", "https://billing.stripe.test/entered"));

    ResponseCapture resp = cookiePostPortal(fixture);

    assertEquals(200, resp.status);
    assertEquals("https://billing.stripe.test/entered", resp.json().getString("url"));
    verify(fixture.portalService).createSession("cus_entered");
    verify(fixture.portalService, never()).createSession("cus_newest");
    verify(fixture.requestStore, never()).findSubscriptionForAccount(anyString(), anyString());
  }

  @Test
  public void cookiePostPortalInsideAnEnvironmentWithoutItsOwnPurchaseAnswers404()
      throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
    CheckoutRequest newest = purchase("sub_newest", "cus_newest", NEWEST_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(newest);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        ENTERED_CLIENT)).thenReturn(null);

    ResponseCapture resp = cookiePostPortal(fixture);

    assertEquals(404, resp.status);
    assertEquals("NO_SUBSCRIPTION", resp.errorCode());
    verify(fixture.requestStore, never()).findSubscriptionForAccount(anyString(), anyString());
    verifyNoInteractions(fixture.portalService);
  }

  /**
   * ETP-5051 (O-1b) — "Mis empresas" manages each row's own environment: a portal request naming
   * a {@code clientId} opens THAT environment's customer, whatever environment the session is in.
   */
  @Test
  public void cookiePostPortalNamingAClientOpensThatEnvironmentsCustomer() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
    CheckoutRequest row = purchase("sub_row", "cus_row", NEWEST_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        NEWEST_CLIENT)).thenReturn(row);
    when(fixture.portalService.createSession("cus_row"))
        .thenReturn(new JSONObject().put("url", "https://billing.stripe.test/row"));

    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF,
        new JSONObject().put("clientId", NEWEST_CLIENT).toString());
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals("https://billing.stripe.test/row", resp.json().getString("url"));
    verify(fixture.requestStore).findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        NEWEST_CLIENT);
    verifyNoMoreInteractions(fixture.requestStore);
    verify(fixture.portalService).createSession("cus_row");
    verifyNoMoreInteractions(fixture.portalService);
  }

  /** ETP-5051 (O-1b) — a portal body that is not JSON is a 400, and nothing is looked up. */
  @Test
  public void cookiePostPortalWithAMalformedBodyAnswers400AndTouchesNothing() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN))
        .thenReturn(fixture.enteredSession(ENTERED_CLIENT));

    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF, "{\"clientId\":");
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }

    assertEquals(400, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  /**
   * ETP-5051 (O-1b) — a body that names no environment keeps the page's rule. Inside an entered
   * environment that is the entered one's purchase, whatever the account bought last.
   */
  @Test
  public void cookiePostPortalNamingNoClientInsideAnEnvironmentOpensTheEnteredOne()
      throws Exception {
    for (String body : NO_CLIENT_BODIES) {
      Fixture fixture = new Fixture();
      when(fixture.goSessionService.resolve(SESSION_TOKEN))
          .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
      CheckoutRequest entered = purchase("sub_entered", "cus_entered", ENTERED_CLIENT);
      when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
          ENTERED_CLIENT)).thenReturn(entered);
      when(fixture.portalService.createSession("cus_entered"))
          .thenReturn(new JSONObject().put("url", "https://billing.stripe.test/entered"));

      ResponseCapture resp = cookiePostPortalWithBody(fixture, body);

      assertEquals(String.valueOf(body), 200, resp.status);
      verify(fixture.requestStore).findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
          ENTERED_CLIENT);
      verifyNoMoreInteractions(fixture.requestStore);
      verify(fixture.portalService).createSession("cus_entered");
    }
  }

  /** ETP-5051 (O-1b) — and in an account-level session it is the account's newest purchase. */
  @Test
  public void cookiePostPortalNamingNoClientAtAccountLevelOpensTheNewest() throws Exception {
    for (String body : NO_CLIENT_BODIES) {
      Fixture fixture = new Fixture();
      when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());
      CheckoutRequest newest = purchase("sub_newest", "cus_newest", NEWEST_CLIENT);
      when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
          .thenReturn(newest);
      when(fixture.portalService.createSession("cus_newest"))
          .thenReturn(new JSONObject().put("url", "https://billing.stripe.test/newest"));

      ResponseCapture resp = cookiePostPortalWithBody(fixture, body);

      assertEquals(String.valueOf(body), 200, resp.status);
      verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
      verifyNoMoreInteractions(fixture.requestStore);
      verify(fixture.portalService).createSession("cus_newest");
    }
  }

  /**
   * ETP-5051 (O-1b) — a client the account did not buy matches nothing in the store, and a match
   * without a Stripe customer cannot open a portal: both are {@code 404 NO_SUBSCRIPTION} (the code
   * the SPA keys its message on), and no portal session is created.
   */
  @Test
  public void cookiePostPortalForAnUnboughtOrCustomerlessClientAnswers404() throws Exception {
    CheckoutRequest withoutCustomer = purchase("sub_row", "  ", NEWEST_CLIENT);
    for (CheckoutRequest match : new CheckoutRequest[] { null, withoutCustomer }) {
      Fixture fixture = new Fixture();
      when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());
      when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
          "client-not-bought")).thenReturn(match);

      ResponseCapture resp = cookiePostPortalWithBody(fixture,
          new JSONObject().put("clientId", "client-not-bought").toString());

      assertEquals(404, resp.status);
      assertEquals("NO_SUBSCRIPTION", resp.errorCode());
      verify(fixture.requestStore).findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
          "client-not-bought");
      verifyNoMoreInteractions(fixture.requestStore);
      verifyNoInteractions(fixture.portalService);
    }
  }

  /**
   * ETP-5051 (O-1b, QA) — a {@code clientId} that is not a string (a number, a boolean, an array,
   * an object) names no environment the account bought: 404 {@code NO_SUBSCRIPTION}, never a 400
   * or a 500, never the page's fallback purchase, and no portal session.
   */
  @Test
  public void cookiePostPortalWithANonStringClientIdAnswers404() throws Exception {
    for (String value : new String[] { "42", "true", "[\"" + ENTERED_CLIENT + "\"]",
        "{\"id\":\"" + ENTERED_CLIENT + "\"}" }) {
      Fixture fixture = new Fixture();
      when(fixture.goSessionService.resolve(SESSION_TOKEN))
          .thenReturn(fixture.enteredSession(ENTERED_CLIENT));
      CheckoutRequest entered = purchase("sub_entered", "cus_entered", ENTERED_CLIENT);
      when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
          ENTERED_CLIENT)).thenReturn(entered);
      when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
          .thenReturn(entered);

      ResponseCapture resp = cookiePostPortalWithBody(fixture, "{\"clientId\":" + value + "}");

      assertEquals(value, 404, resp.status);
      assertEquals(value, "NO_SUBSCRIPTION", resp.errorCode());
      verify(fixture.requestStore, never()).findSubscriptionForAccountClient(ACCOUNT_ID,
          ACCOUNT_EMAIL, ENTERED_CLIENT);
      verify(fixture.requestStore, never()).findSubscriptionForAccount(anyString(), anyString());
      verifyNoInteractions(fixture.portalService);
    }
  }

  /**
   * ETP-5051 (O-1b, QA) — client ids are matched exactly: a lowercase variant of an owned id is
   * passed through unchanged, matches nothing and answers 404, opening no portal.
   */
  @Test
  public void cookiePostPortalWithALowercaseVariantOfAnOwnedClientAnswers404() throws Exception {
    String owned = "95E2A8B50A254B2AAE6774B8C2F28120";
    Fixture fixture = new Fixture();
    when(fixture.goSessionService.resolve(SESSION_TOKEN)).thenReturn(fixture.validSession());
    CheckoutRequest row = purchase("sub_row", "cus_row", owned);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL, owned))
        .thenReturn(row);

    ResponseCapture resp = cookiePostPortalWithBody(fixture,
        new JSONObject().put("clientId", owned.toLowerCase()).toString());

    assertEquals(404, resp.status);
    assertEquals("NO_SUBSCRIPTION", resp.errorCode());
    verify(fixture.requestStore).findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        owned.toLowerCase());
    verify(fixture.requestStore, never()).findSubscriptionForAccountClient(ACCOUNT_ID,
        ACCOUNT_EMAIL, owned);
    verifyNoInteractions(fixture.portalService);
  }

  /** ETP-5051 (O-1b) — valid JSON that is not an object is as bad a body as broken JSON. */
  @Test
  public void cookiePostPortalWithAnArrayOrScalarBodyAnswers400AndLooksNothingUp()
      throws Exception {
    for (String body : new String[] { "[\"client-1\"]", "42", "\"client-1\"", "true" }) {
      Fixture fixture = new Fixture();
      when(fixture.goSessionService.resolve(SESSION_TOKEN))
          .thenReturn(fixture.enteredSession(ENTERED_CLIENT));

      ResponseCapture resp = cookiePostPortalWithBody(fixture, body);

      assertEquals(body, 400, resp.status);
      assertTrue(body, resp.json().has("error"));
      verifyNoInteractions(fixture.requestStore, fixture.portalService);
    }
  }

  /**
   * ETP-5051 (O-1b) — the CSRF proof and the Origin are checked before the body: a request naming
   * a client is refused 403 without its body being read or anything being looked up.
   */
  @Test
  public void cookiePostPortalNamingAClientIsRefusedBeforeItsBodyIsRead() throws Exception {
    String body = new JSONObject().put("clientId", NEWEST_CLIENT).toString();

    Fixture noCsrf = new Fixture();
    when(noCsrf.goSessionService.resolve(SESSION_TOKEN)).thenReturn(noCsrf.validSession());
    HttpServletRequest noCsrfReq = cookiePost(PORTAL_PATH, SESSION_TOKEN, null, body);
    ResponseCapture noCsrfResp = send(noCsrf, noCsrfReq);

    Fixture badOrigin = new Fixture();
    when(badOrigin.goSessionService.resolve(SESSION_TOKEN)).thenReturn(badOrigin.validSession());
    HttpServletRequest badOriginReq = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF, body);
    when(badOriginReq.getHeader("Origin")).thenReturn("https://evil.example.test");
    ResponseCapture badOriginResp = send(badOrigin, badOriginReq);

    assertEquals(403, noCsrfResp.status);
    assertEquals(403, badOriginResp.status);
    verify(noCsrfReq, never()).getReader();
    verify(badOriginReq, never()).getReader();
    verifyNoInteractions(noCsrf.requestStore, noCsrf.portalService, badOrigin.requestStore,
        badOrigin.portalService);
  }

  /**
   * ETP-5051 (O-1b) — the legacy platform-token bearer may name a row too: it resolves through
   * the same owned-client lookup, never the account's newest purchase.
   */
  @Test
  public void legacyBearerPortalNamingAClientResolvesThatClientsPurchase() throws Exception {
    Fixture fixture = new Fixture();
    CheckoutRequest row = purchase("sub_row", "cus_row", NEWEST_CLIENT);
    when(fixture.requestStore.findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        NEWEST_CLIENT)).thenReturn(row);
    when(fixture.portalService.createSession("cus_row"))
        .thenReturn(new JSONObject().put("url", "https://billing.stripe.test/row"));

    HttpServletRequest req = bearerRequest(PORTAL_PATH, "POST", PLATFORM_TOKEN,
        new JSONObject().put("clientId", NEWEST_CLIENT).toString());
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(PLATFORM_TOKEN))
          .thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }

    assertEquals(200, resp.status);
    assertEquals("https://billing.stripe.test/row", resp.json().getString("url"));
    verify(fixture.requestStore).findSubscriptionForAccountClient(ACCOUNT_ID, ACCOUNT_EMAIL,
        NEWEST_CLIENT);
    verify(fixture.requestStore, never()).findSubscriptionForAccount(anyString(), anyString());
  }

  /**
   * The session authenticator requires the allowed {@code Origin} AND the CSRF token — a
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
   * Without the session cookie, {@code runWithPlatformContext} keeps the pre-ETP-5443 narrow
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
    // ETP-5051 (O-1) — a platform-token bearer carries no environment: account level, always.
    verify(fixture.requestStore, times(2)).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    verify(fixture.requestStore, never()).findSubscriptionForAccountClient(anyString(),
        anyString(), anyString());
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
    final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    final PlanCatalogService planCatalog = mock(PlanCatalogService.class);
    final Account account = mock(Account.class);
    final StripeCustomerPortalService.SubscriptionDetail live =
        mock(StripeCustomerPortalService.SubscriptionDetail.class);

    Fixture() {
      servlet.checkoutRequestStore = requestStore;
      servlet.stripeCustomerPortalService = portalService;
      servlet.tenantEnvironmentLifecycleService = lifecycle;
      servlet.subscriptionService = subscriptionService;
      servlet.planCatalogService = planCatalog;
      when(account.getId()).thenReturn(ACCOUNT_ID);
      when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
      when(live.getPlan()).thenReturn("Productive");
      when(live.getCurrency()).thenReturn("EUR");
      when(live.getStatus()).thenReturn("active");
    }

    GoSessionRecord validSession() {
      GoSessionRecord session = new GoSessionRecord();
      session.setAccountId(ACCOUNT_ID);
      session.setCsrfToken(CSRF);
      return session;
    }

    /** A session rotated into an environment by {@code POST /session/environment}. */
    GoSessionRecord enteredSession(String clientId) {
      GoSessionRecord session = validSession();
      session.setCtxClientId(clientId);
      return session;
    }
  }

  private static CheckoutRequest purchase(String subscriptionId, String customerId,
      String clientId) {
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getStripeSubscription()).thenReturn(subscriptionId);
    when(purchase.getStripeCustomer()).thenReturn(customerId);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(purchase.getCreatedClient()).thenReturn(client);
    return purchase;
  }

  /** Sends {@code GET /billing/subscription} with the session cookie and no foreign ids. */
  private static ResponseCapture cookieGetSubscription(Fixture fixture) throws Exception {
    HttpServletRequest req = cookieGet(SUBSCRIPTION_PATH, SESSION_TOKEN, false);
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EnvironmentAccessEnforcementFlag> flag =
            mockStatic(EnvironmentAccessEnforcementFlag.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      fixture.servlet.doGet(req, resp.response);
    }
    return resp;
  }

  /** Sends {@code POST /billing/subscription/portal} with the session cookie and a valid CSRF. */
  private static ResponseCapture cookiePostPortal(Fixture fixture) throws Exception {
    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF, "{}");
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }
    return resp;
  }

  /**
   * Sends {@code POST /billing/subscription/portal} with the session cookie, a valid CSRF and the
   * given body; a null body is a request whose reader is absent.
   */
  private static ResponseCapture cookiePostPortalWithBody(Fixture fixture, String body)
      throws Exception {
    HttpServletRequest req = cookiePost(PORTAL_PATH, SESSION_TOKEN, CSRF,
        body == null ? "" : body);
    if (body == null) {
      when(req.getReader()).thenReturn(null);
    }
    return send(fixture, req);
  }

  /** Sends a POST through the servlet with the session account resolvable by id. */
  private static ResponseCapture send(Fixture fixture, HttpServletRequest req) throws Exception {
    ResponseCapture resp = mockResponse();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
          .thenReturn(fixture.account);
      fixture.servlet.doPost(req, resp.response);
    }
    return resp;
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
      // ETP-6060 — clientId is the one parameter the subscription read honours (ownership is the
      // store query's); it has its own tests, so the foreign-ids request does not send it.
      when(req.getParameter(anyString())).thenAnswer(call -> {
        String name = call.getArgument(0);
        if ("clientId".equals(name)) return null;
        return name.toLowerCase().contains("customer") ? FOREIGN_CUSTOMER : FOREIGN_SUBSCRIPTION;
      });
      when(req.getHeader(anyString())).thenReturn(FOREIGN_SUBSCRIPTION);
      // ETP-5675 — X-Go-Account is not an arbitrary header: it names the account the tab believes
      // it is signed in as, and a foreign value is refused outright (covered below). Absent here,
      // so this case keeps proving that foreign ids elsewhere never pick the account.
      when(req.getHeader(GoSessionSecurity.ACCOUNT_HEADER)).thenReturn(null);
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
