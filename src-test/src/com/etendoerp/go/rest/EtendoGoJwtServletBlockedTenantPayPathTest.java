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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.stream.Stream;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.onboarding.OnboardingCompanyDataService;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.StripePriceService;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantPlanService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5047 — the pay path stays reachable while the tenant is commercially blocked.
 *
 * <p>A blocked customer is told to pay, so every endpoint it pays through must keep answering:
 * the account itself ({@code /me}, {@code /environments}), the plan catalogue, the billing pages,
 * the Stripe customer portal, the checkout status and the two purchase-creating endpoints
 * (upgrade and account billing). They authenticate the ACCOUNT, not a tenant, and
 * {@link EnvironmentAccessGuard} says they "never call this class". This spec holds them to that:
 * the caller's cookie session sits in a tenant the lifecycle service refuses
 * ({@code SUBSCRIPTION_REQUIRED}, {@code DEMO_TRIAL_EXPIRED}), each endpoint must still answer its
 * success status, and the servlet's guard — replaced by a spy of itself — must see no
 * {@code check} (nor any other call) and the policy must not be consulted at all.
 *
 * <p>{@code POST /session/environment} is the one account endpoint that does read the decision:
 * it enters the blocked tenant (200) and reports {@code accessDecision}, through
 * {@link EnvironmentAccessGuard#enforcedDecision}, never {@code check}.
 *
 * <p>A control case proves the fixture is really blocked: the same session reading tenant data
 * ({@code /onboarding/company-data}) is refused with the 402 and does go through {@code check}.
 * A regression that plugged the guard into one of the account endpoints would turn it into that
 * 402 — and lock a blocked customer out of paying.
 *
 * <p>No database: the DAL helpers and the services are mocked; nothing is written.
 */
class EtendoGoJwtServletBlockedTenantPayPathTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String SESSION_TOKEN = "session-cookie-token";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String ACCOUNT_ID = "account-1";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String USER_ID = "user-1";
  private static final String ROLE_ID = "role-1";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String REQUEST_ID = "req-1";
  private static final String CLIENT_NAME = "Acme Corp";
  private static final String CUSTOMER_ID = "cus_1";

  /** The account endpoints a blocked customer pays through, with the status each answers. */
  enum AccountEndpoint {
    ME("GET", "/me", 200),
    ENVIRONMENTS("GET", "/environments", 200),
    PLANS("GET", "/plans", 200),
    BILLING_OFFERS("GET", "/billing/offers", 200),
    BILLING_OVERVIEW("GET", "/billing/overview", 200),
    BILLING_SUBSCRIPTION("GET", "/billing/subscription", 200),
    BILLING_PURCHASE("GET", "/billing/purchases/" + REQUEST_ID, 200),
    CHECKOUT_STATUS("GET", "/checkout/sessions/" + REQUEST_ID, 200),
    BILLING_PORTAL("POST", "/billing/subscription/portal", 200),
    UPGRADE_CHECKOUT("POST", "/checkout/sessions", 201),
    BILLING_PURCHASE_CREATE("POST", "/billing/purchases", 201);

    final String method;
    final String path;
    final int expectedStatus;

    AccountEndpoint(String method, String path, int expectedStatus) {
      this.method = method;
      this.path = path;
      this.expectedStatus = expectedStatus;
    }

    boolean createsAPurchase() {
      return this == UPGRADE_CHECKOUT || this == BILLING_PURCHASE_CREATE;
    }
  }

  static Stream<Arguments> accountEndpointsWhileBlocked() {
    return Stream.of(AccountEndpoint.values()).flatMap(endpoint -> Stream.of(
        Arguments.of(endpoint, Decision.SUBSCRIPTION_REQUIRED),
        Arguments.of(endpoint, Decision.DEMO_TRIAL_EXPIRED)));
  }

  private final GoSessionService goSessionService = mock(GoSessionService.class);
  private final StripePriceService stripePriceService = mock(StripePriceService.class);
  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
      mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
      goSessionService, stripePriceService);
  private final TenantEnvironmentLifecycleService lifecycle =
      mock(TenantEnvironmentLifecycleService.class);
  private final CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
  private final StripeCustomerPortalService portalService =
      mock(StripeCustomerPortalService.class);
  private final HostedCheckoutService checkoutService = mock(HostedCheckoutService.class);
  private final PlanCatalogService planCatalog = mock(PlanCatalogService.class);
  private final TenantPlanService tenantPlanService = mock(TenantPlanService.class);
  private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
  private final DemoDataTransferService transferService = mock(DemoDataTransferService.class);
  private final OnboardingCompanyDataService companyDataService =
      mock(OnboardingCompanyDataService.class);
  private final Account account = mock(Account.class);
  private final Client tenant = mock(Client.class);

  private EnvironmentAccessGuard guard;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<EtendoGoJwtDalHelper> dalHelper;
  private MockedStatic<EtendoGoJwtSupport> support;
  private MockedStatic<EmailVerificationDalHelper> emailVerification;
  private MockedStatic<AccountIdentityDalHelper> identities;
  private MockedStatic<SecureWebServicesUtils> sws;
  private MockedStatic<PublicUrlResolver> publicUrls;

  @BeforeEach
  void setUp() throws Exception {
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    servlet.checkoutRequestStore = requestStore;
    servlet.stripeCustomerPortalService = portalService;
    servlet.hostedCheckoutService = checkoutService;
    servlet.planCatalogService = planCatalog;
    servlet.tenantPlanService = tenantPlanService;
    servlet.subscriptionService = subscriptionService;
    servlet.demoDataTransferService = transferService;
    servlet.onboardingCompanyDataService = companyDataService;
    servlet.sessionRoleReconciler = mock(GoSessionRoleReconciler.class);
    guard = spyOnTheServletsGuard();

    when(account.getId()).thenReturn(ACCOUNT_ID);
    when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
    when(tenant.getId()).thenReturn(CLIENT_ID);
    when(tenant.getName()).thenReturn(CLIENT_NAME);
    when(goSessionService.resolve(SESSION_TOKEN)).thenReturn(sessionInTheBlockedTenant());

    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    dalHelper = mockStatic(EtendoGoJwtDalHelper.class);
    support = mockStatic(EtendoGoJwtSupport.class);
    emailVerification = mockStatic(EmailVerificationDalHelper.class);
    identities = mockStatic(AccountIdentityDalHelper.class);
    sws = mockStatic(SecureWebServicesUtils.class);
    publicUrls = mockStatic(PublicUrlResolver.class, CALLS_REAL_METHODS);

    stubTheAccountAndItsTenant();
    stubTheBillingServices();
    stubEnteringTheEnvironment();
  }

  @AfterEach
  void tearDown() {
    publicUrls.close();
    sws.close();
    identities.close();
    emailVerification.close();
    support.close();
    dalHelper.close();
    obDal.close();
    obContext.close();
  }

  // ===================== the account surface =====================

  @ParameterizedTest(name = "{0} while {1}")
  @MethodSource("accountEndpointsWhileBlocked")
  void theAccountSurfaceAnswersWithoutConsultingTheGuard(AccountEndpoint endpoint,
      Decision decision) throws Exception {
    decide(decision);

    ResponseCapture resp = send(endpoint.method, endpoint.path, bodyFor(endpoint, decision));

    assertEquals(endpoint.expectedStatus, resp.status, resp.body());
    assertFalse(resp.json().has("error"), resp.body());
    verifyNoInteractions(guard);
    verify(lifecycle, never()).evaluateAccess(anyString(), anyBoolean(), any());
  }

  /** The purchase is really created — not merely "not refused" — for a blocked tenant. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = AccountEndpoint.class, names = { "UPGRADE_CHECKOUT",
      "BILLING_PURCHASE_CREATE" })
  void anExpiredDemoBuysWithItsOwnDataCarriedOver(AccountEndpoint endpoint) throws Exception {
    decide(Decision.DEMO_TRIAL_EXPIRED);

    ResponseCapture resp = send(endpoint.method, endpoint.path,
        bodyFor(endpoint, Decision.DEMO_TRIAL_EXPIRED));

    assertEquals(201, resp.status, resp.body());
    verify(checkoutService).createSession(eq(ACCOUNT_ID), eq(ACCOUNT_EMAIL), eq(CLIENT_NAME),
        eq(ORIGIN), any(), any(HostedCheckoutService.SessionOptions.class));
    verifyNoInteractions(guard);
  }

  // ===================== entering the blocked environment =====================

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = Decision.class, names = { "SUBSCRIPTION_REQUIRED", "DEMO_TRIAL_EXPIRED" })
  void enteringTheBlockedTenantAnswers200AndReportsTheDecisionWithoutACheck(Decision decision)
      throws Exception {
    decide(decision);

    ResponseCapture resp = send("POST", "/session/environment", new JSONObject()
        .put("userId", USER_ID).put("roleId", ROLE_ID).put("orgId", ORG_ID).toString());

    assertEquals(200, resp.status, resp.body());
    assertEquals(decision.name(), resp.json().getString("accessDecision"));
    verify(guard).enforcedDecision(CLIENT_ID);
    verify(guard, never()).check(anyString(), anyString());
    verify(guard, never()).checkAsSystem(anyString(), anyString());
  }

  // ===================== control: the fixture really is blocked =====================

  @Test
  void theSameSessionIsRefusedTheTenantsDataThroughTheGuard() throws Exception {
    decide(Decision.SUBSCRIPTION_REQUIRED);

    ResponseCapture resp = send("GET", "/onboarding/company-data", null);

    assertEquals(402, resp.status, resp.body());
    assertEquals("SUBSCRIPTION_REQUIRED",
        resp.json().getJSONObject("error").getString("decision"));
    verify(guard).check(CLIENT_ID, "tenant-session");
    verifyNoInteractions(companyDataService);
  }

  // ===================== fixture =====================

  private void decide(Decision decision) {
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(decision);
  }

  /**
   * Replaces the servlet's one guard with a spy of itself, so every call is recorded while the
   * real decision logic (lifecycle service, kill switch, 402 body) still runs.
   */
  private EnvironmentAccessGuard spyOnTheServletsGuard() throws Exception {
    Field field = EtendoGoJwtServlet.class.getDeclaredField("environmentAccessGuard");
    field.setAccessible(true);
    EnvironmentAccessGuard spied = spy((EnvironmentAccessGuard) field.get(servlet));
    field.set(servlet, spied);
    return spied;
  }

  /** The cookie session of an owner working inside the blocked tenant. */
  private static GoSessionRecord sessionInTheBlockedTenant() {
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId(ACCOUNT_ID);
    session.setCsrfToken(CSRF);
    session.setUserId(USER_ID);
    session.setCtxClientId(CLIENT_ID);
    session.setCtxOrgId(ORG_ID);
    return session;
  }

  private void stubTheAccountAndItsTenant() {
    dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
        .thenReturn(account);
    dalHelper.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail(ACCOUNT_EMAIL))
        .thenReturn(true);
    dalHelper.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID,
        ACCOUNT_EMAIL)).thenReturn(true);
    User environmentUser = mock(User.class);
    when(environmentUser.getClient()).thenReturn(tenant);
    dalHelper.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(ACCOUNT_EMAIL))
        .thenReturn(Collections.singletonList(environmentUser));
    dalHelper.when(() -> EtendoGoJwtDalHelper.buildEnvironmentJson(eq(tenant), any(),
        eq(environmentUser), any())).thenReturn(new JSONObject());
    // A canceled subscription: the tenant's plan answers from the row, never the DB fallback.
    Subscription canceled = mock(Subscription.class);
    when(canceled.getSubscriptionStatus()).thenReturn(SubscriptionService.STATUS_CANCELED);
    when(canceled.getPlan()).thenReturn(mock(Plan.class));
    when(subscriptionService.findLatestForClients(any()))
        .thenReturn(Collections.singletonMap(CLIENT_ID, canceled));

    OBDal dal = mock(OBDal.class);
    when(dal.get(Client.class, CLIENT_ID)).thenReturn(tenant);
    obDal.when(OBDal::getInstance).thenReturn(dal);
  }

  private void stubTheBillingServices() throws Exception {
    StripePriceService.Price price = mock(StripePriceService.Price.class);
    when(price.getId()).thenReturn("price_1");
    when(price.getCurrency()).thenReturn("EUR");
    when(price.getInterval()).thenReturn("month");
    when(stripePriceService.retrieveConfiguredPrice()).thenReturn(price);

    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getRequest()).thenReturn(REQUEST_ID);
    when(purchase.getClientName()).thenReturn(CLIENT_NAME);
    when(purchase.getCheckoutRequestStatus()).thenReturn("COMPLETED");
    when(purchase.getCreatedClient()).thenReturn(tenant);
    when(purchase.getStripeCustomer()).thenReturn(CUSTOMER_ID);
    when(purchase.getStripeSubscription()).thenReturn("sub_1");
    when(requestStore.find(REQUEST_ID, ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(purchase);
    when(requestStore.isPaidFor(REQUEST_ID, ACCOUNT_ID, ACCOUNT_EMAIL, null)).thenReturn(true);
    when(requestStore.findForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(Collections.singletonList(purchase));
    when(requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL)).thenReturn(purchase);
    when(portalService.retrieveSubscription("sub_1"))
        .thenReturn(mock(StripeCustomerPortalService.SubscriptionDetail.class));
    when(portalService.createSession(CUSTOMER_ID))
        .thenReturn(new JSONObject().put("url", "https://billing.example.test/portal"));

    publicUrls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn(ORIGIN);
    when(checkoutService.createSession(anyString(), anyString(), anyString(), anyString(), any(),
        any(HostedCheckoutService.SessionOptions.class)))
        .thenReturn(new JSONObject().put("requestId", REQUEST_ID)
            .put("url", "https://checkout.example.test/" + REQUEST_ID));
  }

  /** What {@code POST /session/environment} needs to enter the tenant, as its own spec has it. */
  private void stubEnteringTheEnvironment() throws Exception {
    support.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(ACCOUNT_EMAIL,
        USER_ID)).thenReturn(true);
    support.when(() -> EtendoGoJwtSupport.loadRoleListData(USER_ID)).thenReturn(
        new EtendoGoJwtSupport.RoleListData(ROLE_ID, new JSONArray().put(new JSONObject()
            .put("id", ROLE_ID)
            .put("orgList", new JSONArray().put(new JSONObject().put("id", ORG_ID))))));
    User user = mock(User.class);
    when(user.getClient()).thenReturn(tenant);
    Role role = mock(Role.class);
    OBDal dal = OBDal.getInstance();
    when(dal.get(User.class, USER_ID)).thenReturn(user);
    when(dal.get(Role.class, ROLE_ID)).thenReturn(role);
    sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn("jwt-token");
    DecodedJWT decoded = mock(DecodedJWT.class);
    // Each claim is built before its stubbing starts: a mock stubbed inside another's stubbing
    // is an unfinished stubbing.
    Claim userClaim = claim(USER_ID);
    Claim roleClaim = claim(ROLE_ID);
    Claim clientClaim = claim(CLIENT_ID);
    Claim orgClaim = claim(ORG_ID);
    Claim warehouseClaim = claim("warehouse-1");
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("role")).thenReturn(roleClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("organization")).thenReturn(orgClaim);
    when(decoded.getClaim("warehouse")).thenReturn(warehouseClaim);
    sws.when(() -> SecureWebServicesUtils.decodeToken("jwt-token")).thenReturn(decoded);
    when(goSessionService.rotate(any())).thenReturn(new IssuedGoSession("newtok", "newref",
        "newcsrf", sessionInTheBlockedTenant()));
  }

  /**
   * The purchase body. A canceled productive tenant ({@code SUBSCRIPTION_REQUIRED}) buys for
   * itself; an expired demo ({@code DEMO_TRIAL_EXPIRED}) names itself as the demo whose data the
   * new environment carries over — ETP-5396, the case the upgrade page exists for.
   */
  private String bodyFor(AccountEndpoint endpoint, Decision decision) throws Exception {
    if (!endpoint.createsAPurchase()) {
      return "POST".equals(endpoint.method) ? "{}" : null;
    }
    boolean expiredDemo = decision == Decision.DEMO_TRIAL_EXPIRED;
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(expiredDemo
        ? TenantPlanService.PLAN_FREE : TenantPlanService.PLAN_PRODUCTIVE);
    JSONObject body = new JSONObject().put("clientName", CLIENT_NAME);
    if (expiredDemo) {
      body.put("demoClientId", CLIENT_ID);
    }
    return body.toString();
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  /**
   * Sends a request under the cookie session. Unsafe methods carry the same-origin
   * {@code Origin} and the session's CSRF proof, as the SPA sends them.
   */
  private ResponseCapture send(String method, String path, String body) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, SESSION_TOKEN) });
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go" + path));
    if ("POST".equals(method)) {
      when(req.getHeader("Origin")).thenReturn(ORIGIN);
      when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(CSRF);
      when(req.getContentType()).thenReturn("application/json");
      when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
    }
    ResponseCapture resp = new ResponseCapture();
    if ("POST".equals(method)) {
      servlet.doPost(req, resp.response);
    } else {
      servlet.doGet(req, resp.response);
    }
    return resp;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response = mock(HttpServletResponse.class);
    private final StringWriter body = new StringWriter();
    int status;

    ResponseCapture() throws Exception {
      doAnswer(inv -> {
        status = inv.getArgument(0);
        return null;
      }).when(response).setStatus(anyInt());
      when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    String body() {
      return body.toString();
    }

    JSONObject json() throws Exception {
      return new JSONObject(body());
    }
  }
}
