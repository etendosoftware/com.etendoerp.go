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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.StripeCustomerPortalService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Unit tests for {@code GET /sws/go/billing/subscription} and
 * {@code POST /sws/go/billing/subscription/portal} (ETP-5443).
 *
 * <p>The criterion these pin above all others is authorization: the Stripe subscription and
 * customer are taken from the authenticated account's own purchase row, never from anything the
 * caller sends. Every request here therefore carries a foreign id in its query string, its
 * parameters, every header and its body, and the tests assert the provider was reached with the
 * account's id and never with the injected one.
 */
public class EtendoGoJwtServletBillingSubscriptionTest {

  private static final String SUBSCRIPTION_PATH = "/billing/subscription";
  private static final String PORTAL_PATH = "/billing/subscription/portal";
  private static final String VALID_TOKEN = "platform-token";
  private static final String ACCOUNT_ID = "account-1";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String ACCOUNT_SUBSCRIPTION = "sub_account";
  private static final String ACCOUNT_CUSTOMER = "cus_account";
  private static final String FOREIGN_SUBSCRIPTION = "sub_foreign";
  private static final String FOREIGN_CUSTOMER = "cus_foreign";
  private static final String CLIENT_ID = "client-1";
  private static final int GRACE_DAYS = 7;

  // ===================== GET /billing/subscription =====================

  /** An account without a subscription is a normal state, not a missing resource. */
  @Test
  public void accountWithoutSubscriptionAnswers200WithHasSubscriptionFalse() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(null);

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(200, resp.status);
    JSONObject body = resp.json();
    assertFalse(body.getBoolean("hasSubscription"));
    assertEquals(1, body.length());
    verifyNoInteractions(fixture.portalService);
  }

  @Test
  public void subscriptionProjectsTheLiveDetail() throws Exception {
    Fixture fixture = subscribedFixture(snapshot(
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null));

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(200, resp.status);
    JSONObject body = resp.json();
    assertTrue(body.getBoolean("hasSubscription"));
    assertEquals("Productive", body.getString("plan"));
    assertEquals(2900L, body.getLong("amountMinor"));
    assertEquals("EUR", body.getString("currency"));
    assertEquals("past_due", body.getString("status"));
    assertEquals("2026-12-01T00:00:00Z", body.getString("renewalAt"));
    assertTrue(body.getBoolean("cancelAtPeriodEnd"));
    assertTrue(body.isNull("graceEndsAt"));
    assertEquals(0, body.getInt("graceDaysRemaining"));
  }

  @Test
  public void missingRenewalIsProjectedAsNull() throws Exception {
    Fixture fixture = subscribedFixture(null);
    when(fixture.live.getRenewalAt()).thenReturn(null);

    JSONObject body = get(fixture, VALID_TOKEN).json();

    assertTrue(body.has("renewalAt"));
    assertTrue(body.isNull("renewalAt"));
  }

  /** Grace ends at the stored due date plus the configured grace days, rounded up to days. */
  @Test
  public void pastDueWithDueDateProjectsGraceEndAndRemainingDays() throws Exception {
    // 5 days and 1 hour of grace left: rounds up to 6. The hour absorbs the test's own runtime.
    Instant dueAt = Instant.now().minus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
        .truncatedTo(ChronoUnit.SECONDS);
    Fixture fixture = subscribedFixture(snapshot(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, dueAt));

    JSONObject body = get(fixture, VALID_TOKEN).json();

    assertEquals(dueAt.plus(GRACE_DAYS, ChronoUnit.DAYS).toString(),
        body.getString("graceEndsAt"));
    assertEquals(6, body.getInt("graceDaysRemaining"));
  }

  @Test
  public void elapsedGraceProjectsZeroRemainingDays() throws Exception {
    Instant dueAt = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    Fixture fixture = subscribedFixture(snapshot(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, dueAt));

    JSONObject body = get(fixture, VALID_TOKEN).json();

    assertEquals(dueAt.plus(GRACE_DAYS, ChronoUnit.DAYS).toString(),
        body.getString("graceEndsAt"));
    assertEquals(0, body.getInt("graceDaysRemaining"));
  }

  /** A due date lingering under CURRENT is not a grace period. */
  @Test
  public void currentStatusProjectsNoGraceEvenWithAStoredDueDate() throws Exception {
    Fixture fixture = subscribedFixture(snapshot(
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT,
        Instant.now().plus(3, ChronoUnit.DAYS)));

    JSONObject body = get(fixture, VALID_TOKEN).json();

    assertTrue(body.isNull("graceEndsAt"));
    assertEquals(0, body.getInt("graceDaysRemaining"));
  }

  @Test
  public void pastDueWithoutDueDateProjectsNoGrace() throws Exception {
    Fixture fixture = subscribedFixture(snapshot(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, null));

    JSONObject body = get(fixture, VALID_TOKEN).json();

    assertTrue(body.isNull("graceEndsAt"));
    assertEquals(0, body.getInt("graceDaysRemaining"));
  }

  @Test
  public void purchaseWithoutEnvironmentProjectsNoGraceAndResolvesNothing() throws Exception {
    Fixture fixture = new Fixture();
    CheckoutRequest withoutEnvironment = purchase(ACCOUNT_SUBSCRIPTION, ACCOUNT_CUSTOMER, null);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(withoutEnvironment);
    when(fixture.portalService.retrieveSubscription(ACCOUNT_SUBSCRIPTION))
        .thenReturn(fixture.live);

    JSONObject body = get(fixture, VALID_TOKEN).json();

    assertTrue(body.getBoolean("hasSubscription"));
    assertTrue(body.isNull("graceEndsAt"));
    assertEquals(0, body.getInt("graceDaysRemaining"));
    verify(fixture.lifecycle, never()).resolve(anyString());
  }

  @Test
  public void unconfiguredCheckoutAnswers503() throws Exception {
    Fixture fixture = subscribedFixture(null);
    when(fixture.portalService.retrieveSubscription(ACCOUNT_SUBSCRIPTION))
        .thenThrow(new IllegalStateException("Checkout is not configured"));

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(503, resp.status);
    assertEquals("CHECKOUT_NOT_CONFIGURED", resp.errorCode());
  }

  @Test
  public void providerFailureAnswers502() throws Exception {
    Fixture fixture = subscribedFixture(null);
    when(fixture.portalService.retrieveSubscription(ACCOUNT_SUBSCRIPTION))
        .thenThrow(new IOException("Billing provider rejected subscription detail"));

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(502, resp.status);
    assertEquals("BILLING_PROVIDER_ERROR", resp.errorCode());
  }

  /** The foreign subscription id arrives on every channel the caller controls, and is ignored. */
  @Test
  public void subscriptionIsReadFromTheAccountRowNeverFromTheRequest() throws Exception {
    Fixture fixture = subscribedFixture(null);

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(200, resp.status);
    verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    verifyNoMoreInteractions(fixture.requestStore);
    verify(fixture.portalService).retrieveSubscription(ACCOUNT_SUBSCRIPTION);
    verify(fixture.portalService, never()).retrieveSubscription(FOREIGN_SUBSCRIPTION);
    verifyNoMoreInteractions(fixture.portalService);
    verify(fixture.lifecycle).resolve(CLIENT_ID);
  }

  @Test
  public void subscriptionWithoutATokenAnswers401AndReadsNothing() throws Exception {
    Fixture fixture = subscribedFixture(null);

    ResponseCapture resp = get(fixture, null);

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  @Test
  public void subscriptionWithAnUnknownTokenAnswers401AndReadsNothing() throws Exception {
    Fixture fixture = subscribedFixture(null);

    ResponseCapture resp = get(fixture, "someone-elses-token");

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  /** A failing stored-state lookup is a server fault, not "checkout not configured". */
  @Test
  public void lifecycleResolveFailureAnswers500AndRollsBack() throws Exception {
    Fixture fixture = subscribedFixture(null);
    when(fixture.lifecycle.resolve(CLIENT_ID))
        .thenThrow(new IllegalStateException("Client not found"));

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(500, resp.status);
    assertFalse(resp.body().contains("CHECKOUT_NOT_CONFIGURED"));
    verify(fixture.dal).rollbackAndClose();
  }

  @Test
  public void graceConfigurationFailureAnswers500() throws Exception {
    Fixture fixture = subscribedFixture(snapshot(
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, Instant.now()));
    when(fixture.lifecycle.configuration())
        .thenThrow(new IllegalStateException("Invalid grace configuration"));

    ResponseCapture resp = get(fixture, VALID_TOKEN);

    assertEquals(500, resp.status);
    assertFalse(resp.body().contains("CHECKOUT_NOT_CONFIGURED"));
    verify(fixture.dal).rollbackAndClose();
  }

  // ===================== Page and portal agree =====================

  /**
   * An account with an older purchase (subscription A, customer A) and a newer one (B, B). The
   * store's single finder answers the newest; both endpoints must act on that same row, so the
   * portal always opens for the customer of the subscription the page shows. Which row is newest
   * is the store's decision and is pinned in {@code CheckoutRequestStoreSubscriptionFinderTest}.
   */
  @Test
  public void pageAndPortalActOnTheSameNewestPurchase() throws Exception {
    Fixture fixture = new Fixture();
    CheckoutRequest newer = purchase("sub_B", "cus_B", CLIENT_ID);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(newer);
    when(fixture.portalService.retrieveSubscription("sub_B")).thenReturn(fixture.live);
    when(fixture.portalService.createSession("cus_B")).thenReturn(
        new JSONObject("{\"url\":\"https://billing.stripe.test/session-b\"}"));

    ResponseCapture page = get(fixture, VALID_TOKEN);
    ResponseCapture portal = post(fixture, VALID_TOKEN);

    assertEquals(200, page.status);
    assertEquals(200, portal.status);
    assertEquals("https://billing.stripe.test/session-b", portal.json().getString("url"));
    InOrder order = inOrder(fixture.requestStore, fixture.portalService);
    order.verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    order.verify(fixture.portalService).retrieveSubscription("sub_B");
    order.verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    order.verify(fixture.portalService).createSession("cus_B");
    verify(fixture.portalService, never()).retrieveSubscription("sub_A");
    verify(fixture.portalService, never()).createSession("cus_A");
    verifyNoMoreInteractions(fixture.requestStore, fixture.portalService);
  }

  // ===================== POST /billing/subscription/portal =====================

  @Test
  public void portalWithoutABillablePurchaseAnswers404() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(null);

    ResponseCapture resp = post(fixture, VALID_TOKEN);

    assertEquals(404, resp.status);
    assertEquals("NO_SUBSCRIPTION", resp.errorCode());
    verifyNoInteractions(fixture.portalService);
  }

  @Test
  public void portalForAPurchaseWithoutCustomerAnswers404() throws Exception {
    Fixture fixture = new Fixture();
    CheckoutRequest withoutCustomer = purchase(ACCOUNT_SUBSCRIPTION, " ", CLIENT_ID);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(withoutCustomer);

    ResponseCapture resp = post(fixture, VALID_TOKEN);

    assertEquals(404, resp.status);
    assertEquals("NO_SUBSCRIPTION", resp.errorCode());
    verifyNoInteractions(fixture.portalService);
  }

  @Test
  public void portalReturnsTheSessionUrl() throws Exception {
    Fixture fixture = billableFixture();
    when(fixture.portalService.createSession(ACCOUNT_CUSTOMER)).thenReturn(
        new JSONObject("{\"id\":\"bps_1\",\"url\":\"https://billing.stripe.test/session\"}"));

    ResponseCapture resp = post(fixture, VALID_TOKEN);

    assertEquals(200, resp.status);
    JSONObject body = resp.json();
    assertEquals("https://billing.stripe.test/session", body.getString("url"));
    assertEquals(1, body.length());
  }

  @Test
  public void portalWithUnconfiguredCheckoutAnswers503() throws Exception {
    Fixture fixture = billableFixture();
    when(fixture.portalService.createSession(ACCOUNT_CUSTOMER))
        .thenThrow(new IllegalStateException("Checkout is not configured"));

    ResponseCapture resp = post(fixture, VALID_TOKEN);

    assertEquals(503, resp.status);
    assertEquals("CHECKOUT_NOT_CONFIGURED", resp.errorCode());
  }

  @Test
  public void portalProviderFailureAnswers502() throws Exception {
    Fixture fixture = billableFixture();
    when(fixture.portalService.createSession(ACCOUNT_CUSTOMER))
        .thenThrow(new IOException("Billing provider rejected portal session"));

    ResponseCapture resp = post(fixture, VALID_TOKEN);

    assertEquals(502, resp.status);
    assertEquals("BILLING_PROVIDER_ERROR", resp.errorCode());
  }

  /** The foreign customer id arrives on every channel the caller controls, and is ignored. */
  @Test
  public void portalCustomerIsReadFromTheAccountRowNeverFromTheRequest() throws Exception {
    Fixture fixture = billableFixture();
    when(fixture.portalService.createSession(ACCOUNT_CUSTOMER)).thenReturn(
        new JSONObject("{\"url\":\"https://billing.stripe.test/session\"}"));

    ResponseCapture resp = post(fixture, VALID_TOKEN);

    assertEquals(200, resp.status);
    verify(fixture.requestStore).findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL);
    verifyNoMoreInteractions(fixture.requestStore);
    verify(fixture.portalService).createSession(ACCOUNT_CUSTOMER);
    verify(fixture.portalService, never()).createSession(FOREIGN_CUSTOMER);
    verifyNoMoreInteractions(fixture.portalService);
  }

  @Test
  public void portalWithoutATokenAnswers401AndCreatesNothing() throws Exception {
    Fixture fixture = billableFixture();

    ResponseCapture resp = post(fixture, null);

    assertEquals(401, resp.status);
    verifyNoInteractions(fixture.requestStore, fixture.portalService);
  }

  // ===================== Fixture =====================

  private static final class Fixture {
    final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
    final CheckoutRequestStore requestStore = mock(CheckoutRequestStore.class);
    final StripeCustomerPortalService portalService = mock(StripeCustomerPortalService.class);
    final TenantEnvironmentLifecycleService lifecycle =
        mock(TenantEnvironmentLifecycleService.class);
    final Account account = mock(Account.class);
    final OBDal dal = mock(OBDal.class);
    final StripeCustomerPortalService.SubscriptionDetail live =
        mock(StripeCustomerPortalService.SubscriptionDetail.class);

    Fixture() {
      servlet.checkoutRequestStore = requestStore;
      servlet.stripeCustomerPortalService = portalService;
      servlet.tenantEnvironmentLifecycleService = lifecycle;
      when(account.getId()).thenReturn(ACCOUNT_ID);
      when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
      when(lifecycle.configuration())
          .thenReturn(new EnvironmentAccessPolicy.Configuration(14, GRACE_DAYS));
      when(live.getPlan()).thenReturn("Productive");
      when(live.getAmountMinor()).thenReturn(2900L);
      when(live.getCurrency()).thenReturn("EUR");
      when(live.getStatus()).thenReturn("past_due");
      when(live.getRenewalAt()).thenReturn("2026-12-01T00:00:00Z");
      when(live.isCancelAtPeriodEnd()).thenReturn(true);
    }
  }

  private static Fixture subscribedFixture(
      TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot) throws Exception {
    Fixture fixture = new Fixture();
    CheckoutRequest owned = purchase(ACCOUNT_SUBSCRIPTION, ACCOUNT_CUSTOMER, CLIENT_ID);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(owned);
    when(fixture.portalService.retrieveSubscription(ACCOUNT_SUBSCRIPTION))
        .thenReturn(fixture.live);
    when(fixture.lifecycle.resolve(CLIENT_ID)).thenReturn(snapshot);
    return fixture;
  }

  private static Fixture billableFixture() {
    Fixture fixture = new Fixture();
    CheckoutRequest owned = purchase(ACCOUNT_SUBSCRIPTION, ACCOUNT_CUSTOMER, CLIENT_ID);
    when(fixture.requestStore.findSubscriptionForAccount(ACCOUNT_ID, ACCOUNT_EMAIL))
        .thenReturn(owned);
    return fixture;
  }

  private static CheckoutRequest purchase(String subscriptionId, String customerId,
      String clientId) {
    CheckoutRequest purchase = mock(CheckoutRequest.class);
    when(purchase.getStripeSubscription()).thenReturn(subscriptionId);
    when(purchase.getStripeCustomer()).thenReturn(customerId);
    if (clientId != null) {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(clientId);
      when(purchase.getCreatedClient()).thenReturn(client);
    }
    return purchase;
  }

  private static TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot(
      EnvironmentAccessPolicy.SubscriptionStatus status, Instant dueAt) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        mock(TenantEnvironmentLifecycleService.EnvironmentSnapshot.class);
    when(snapshot.getSubscriptionStatus()).thenReturn(status);
    when(snapshot.getRenewalDueAt()).thenReturn(dueAt);
    return snapshot;
  }

  private static ResponseCapture get(Fixture fixture, String token) throws Exception {
    return send(fixture, SUBSCRIPTION_PATH, token, false);
  }

  private static ResponseCapture post(Fixture fixture, String token) throws Exception {
    return send(fixture, PORTAL_PATH, token, true);
  }

  /**
   * Sends a request that carries a foreign subscription and customer id on every channel a caller
   * controls: query string, every parameter, every header except Authorization, and the body.
   */
  private static ResponseCapture send(Fixture fixture, String path, String token, boolean post)
      throws Exception {
    String foreignBody = "{\"subscriptionId\":\"" + FOREIGN_SUBSCRIPTION
        + "\",\"stripeSubscription\":\"" + FOREIGN_SUBSCRIPTION + "\",\"customer\":\""
        + FOREIGN_CUSTOMER + "\",\"customerId\":\"" + FOREIGN_CUSTOMER + "\"}";
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getMethod()).thenReturn(post ? "POST" : "GET");
    when(req.getQueryString()).thenReturn("subscriptionId=" + FOREIGN_SUBSCRIPTION
        + "&customer=" + FOREIGN_CUSTOMER);
    when(req.getParameter(anyString())).thenAnswer(call ->
        String.valueOf(call.getArgument(0)).toLowerCase().contains("customer")
            ? FOREIGN_CUSTOMER : FOREIGN_SUBSCRIPTION);
    when(req.getHeader(anyString())).thenReturn(FOREIGN_SUBSCRIPTION);
    when(req.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(foreignBody)));
    ResponseCapture resp = mockResponse();

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(fixture.dal);
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByPlatformToken(anyString()))
          .thenAnswer(call -> VALID_TOKEN.equals(call.getArgument(0)) ? fixture.account : null);
      if (post) {
        fixture.servlet.doPost(req, resp.response);
      } else {
        fixture.servlet.doGet(req, resp.response);
      }
    }
    return resp;
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

    String body() {
      return body.toString();
    }

    JSONObject json() throws Exception {
      return new JSONObject(body.toString());
    }

    String errorCode() throws Exception {
      return json().getJSONObject("error").getString("code");
    }
  }
}
