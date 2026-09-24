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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.HostedCheckoutService;
import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.PlanNotAvailableException;
import com.etendoerp.go.payment.StripePriceService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.session.GoSessionService;

/**
 * Specs for {@code GET /sws/go/plans} and {@code POST /sws/go/checkout/sessions} under the
 * <b>legacy price fallback</b> (ETP-5046 design §6.1).
 *
 * <p>The plan list and checkout decide on the same predicate
 * ({@link PlanCatalogService#isLegacyFallbackActive()}), so the list can never offer something
 * checkout refuses. While the fallback is on, the list is exactly {@code legacy-productive}, quoted
 * from the configured <em>Stripe</em> price — never from the typed billing offer — so the amount
 * shown is the amount charged. When Stripe cannot quote it the plan is left out; the page must
 * never get a 500 for a problem the buyer cannot act on.
 */
public class EtendoGoJwtServletLegacyFallbackTest {

  private static final String TOKEN = "valid-token";
  private static final String LEGACY_KEY = PlanCatalogService.LEGACY_PLAN_KEY;
  private static final String LEGACY_PRICE_ID = "price_LEGACY_SECRET";

  private final StripePriceService prices = mock(StripePriceService.class);
  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
      new TransactionalAuthEmailSender(), new EtendoGoSsoProviderRegistry(),
      mock(GoSessionService.class), prices);

  // ===================== GET /plans — fallback active =====================

  @Test
  public void theFallbackListsExactlyTheGrandfatheredPlanQuotedFromStripe() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, 2900, "eur", "month"));

    JSONArray plans = getPlans(catalog);

    assertEquals(1, plans.length());
    JSONObject plan = plans.getJSONObject(0);
    assertEquals(LEGACY_KEY, plan.getString("planKey"));
    assertEquals("Productive (legacy)", plan.getString("name"));
    assertEquals("Grandfathered plan", plan.getString("description"));
    // Minor units converted with the currency's own exponent, as a string (no float re-rounding).
    assertEquals("29.00", plan.getString("displayPrice"));
    assertEquals("EUR", plan.getString("currency"));
    assertEquals("month", plan.getString("billingInterval"));
    // One answer, not two: the priced-plan branch is not consulted while the fallback holds.
    verify(catalog, never()).listPurchasablePlans();
  }

  @Test
  public void aZeroDecimalCurrencyIsNotDividedByAHundred() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, 4900, "jpy", "year"));

    JSONObject plan = getPlans(catalog).getJSONObject(0);

    // 4900 JPY is 4900 yen, not 49.00 — dividing by 100 would under-quote the price a hundredfold.
    assertEquals("4900", plan.getString("displayPrice"));
    assertEquals("JPY", plan.getString("currency"));
    assertEquals("year", plan.getString("billingInterval"));
  }

  @Test
  public void aThreeDecimalCurrencyKeepsItsThirdDecimal() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, 4900, "kwd", "month"));

    assertEquals("4.900", getPlans(catalog).getJSONObject(0).getString("displayPrice"));
  }

  @Test
  public void aOneTimeConfiguredPriceIsListedWithItsOnceInterval() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, 9900, "eur", "once"));

    assertEquals("once", getPlans(catalog).getJSONObject(0).getString("billingInterval"));
  }

  @Test
  public void aStripeFailureLeavesTheLegacyPlanOutAndStillAnswers200() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice()).thenThrow(new IOException("Stripe unavailable"));

    ResponseCapture resp = get(catalog);

    // Offering a plan whose price nobody could verify is the one thing this list must not do, and
    // a 500 would break the upgrade page for a problem the buyer cannot act on.
    assertEquals(200, resp.status);
    assertEquals(0, new JSONObject(resp.body()).getJSONArray("plans").length());
  }

  @Test
  public void aMisconfiguredLegacyPriceLeavesTheLegacyPlanOutAndStillAnswers200() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice())
        .thenThrow(new IllegalStateException("Checkout Price is not configured"));

    ResponseCapture resp = get(catalog);

    assertEquals(200, resp.status);
    assertEquals(0, new JSONObject(resp.body()).getJSONArray("plans").length());
  }

  @Test
  public void theConfiguredProviderPriceIdIsNeverSentToTheBrowser() throws Exception {
    PlanCatalogService catalog = catalogWithFallback();
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, 2900, "eur", "month"));

    ResponseCapture resp = get(catalog);

    assertEquals(200, resp.status);
    assertFalse(resp.body(), resp.body().contains(LEGACY_PRICE_ID));
    assertFalse(resp.body(), resp.body().toLowerCase(Locale.ROOT).contains("priceid"));
  }

  // ===================== GET /plans — fallback inactive =====================

  @Test
  public void withoutTheFallbackOnlyPricedPlansAreListedAndTheLegacyPlanIsAbsent()
      throws Exception {
    PlanCatalogService catalog = mock(PlanCatalogService.class);
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.empty());
    Plan priced = pricedPlan("productive-monthly", "price_PLAN_SECRET");
    when(catalog.listPurchasablePlans()).thenReturn(List.of(priced));

    ResponseCapture resp = get(catalog);

    assertEquals(200, resp.status);
    JSONArray plans = new JSONObject(resp.body()).getJSONArray("plans");
    assertEquals(1, plans.length());
    assertEquals("productive-monthly", plans.getJSONObject(0).getString("planKey"));
    assertFalse(resp.body(), resp.body().contains(LEGACY_KEY));
    assertFalse(resp.body(), resp.body().contains("price_PLAN_SECRET"));
    // The legacy price is not even quoted once a priced plan exists.
    verify(prices, never()).retrieveConfiguredPrice();
  }

  // ===================== POST /checkout/sessions — PLAN_NOT_AVAILABLE =====================

  @Test
  public void aRetiredFallbackAnswersPlanNotAvailableAndAsksTheBuyerToReload() throws Exception {
    // A page loaded before the first priced plan was created still offers legacy-productive.
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    when(checkout.createSession(anyString(), anyString(), anyString(), any(), anyString(),
        any(HostedCheckoutService.SessionOptions.class)))
        .thenThrow(new PlanNotAvailableException(LEGACY_KEY));

    ResponseCapture resp = postCheckout(checkout, LEGACY_KEY);

    assertEquals(400, resp.status);
    JSONObject error = new JSONObject(resp.body()).getJSONObject("error");
    assertEquals("PLAN_NOT_AVAILABLE", error.getString("code"));
    String message = error.getString("message").toLowerCase(Locale.ROOT);
    assertTrue(message, message.contains("no longer available"));
    assertTrue(message, message.contains("reload"));
    assertFalse(message, message.contains(LEGACY_KEY));
  }

  @Test
  public void aBlankPlanKeyReachesCheckoutAsNoKeyAtAll() throws Exception {
    HostedCheckoutService checkout = mock(HostedCheckoutService.class);
    JSONObject created = new JSONObject().put("requestId", "REQ-1")
        .put("checkoutUrl", "https://checkout.test/s");
    when(checkout.createSession(anyString(), anyString(), anyString(), any(), isNull(),
        any(HostedCheckoutService.SessionOptions.class))).thenReturn(created);

    ResponseCapture resp = postCheckout(checkout, "   ");

    // The fallback keys on "no plan named", so whitespace must not become a key of its own.
    assertEquals(201, resp.status);
    verify(checkout).createSession(anyString(), anyString(), anyString(), any(), isNull(),
        any(HostedCheckoutService.SessionOptions.class));
  }

  // ===================== helpers =====================

  private static PlanCatalogService catalogWithFallback() {
    Plan legacy = mock(Plan.class);
    when(legacy.getSearchKey()).thenReturn(LEGACY_KEY);
    when(legacy.getName()).thenReturn("Productive (legacy)");
    when(legacy.getDescription()).thenReturn("Grandfathered plan");
    PlanCatalogService catalog = mock(PlanCatalogService.class);
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));
    return catalog;
  }

  private static Plan pricedPlan(String key, String priceId) {
    Plan plan = mock(Plan.class);
    when(plan.getSearchKey()).thenReturn(key);
    when(plan.getName()).thenReturn("Productive");
    when(plan.getDescription()).thenReturn("");
    when(plan.getDisplayPrice()).thenReturn(new BigDecimal("49.00"));
    when(plan.getCurrencyCode()).thenReturn("EUR");
    when(plan.getBillingInterval()).thenReturn("month");
    when(plan.getProviderPriceID()).thenReturn(priceId);
    return plan;
  }

  private static StripePriceService.Price price(String id, long amountMinor, String currency,
      String interval) throws Exception {
    JSONObject json = new JSONObject().put("id", id).put("active", true)
        .put("unit_amount", amountMinor).put("currency", currency);
    if (!"once".equals(interval)) {
      json.put("recurring", new JSONObject().put("interval", interval).put("interval_count", 1));
    }
    return parsePrice(json);
  }

  /** {@code StripePriceService.parsePrice} is package-private to the payment package. */
  private static StripePriceService.Price parsePrice(JSONObject json) throws Exception {
    java.lang.reflect.Method parse =
        StripePriceService.class.getDeclaredMethod("parsePrice", JSONObject.class);
    parse.setAccessible(true);
    return (StripePriceService.Price) parse.invoke(null, json);
  }

  private JSONArray getPlans(PlanCatalogService catalog) throws Exception {
    ResponseCapture resp = get(catalog);
    assertEquals(resp.body(), 200, resp.status);
    return new JSONObject(resp.body()).getJSONArray("plans");
  }

  private ResponseCapture get(PlanCatalogService catalog) throws Exception {
    servlet.planCatalogService = catalog;
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = authenticatedRequest("/plans");
    Account account = account();
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(TOKEN))
          .thenReturn(account);
      servlet.doGet(req, resp.response);
    }
    return resp;
  }

  private ResponseCapture postCheckout(HostedCheckoutService checkout, String planKey)
      throws Exception {
    servlet.hostedCheckoutService = checkout;
    // No purchase in flight for this environment name, so a new checkout is opened.
    servlet.checkoutRequestStore = mock(CheckoutRequestStore.class);
    JSONObject body = new JSONObject().put("clientName", "Acme Productive").put("planKey", planKey);
    HttpServletRequest req = authenticatedRequest("/checkout/sessions");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));
    ResponseCapture resp = mockResponse();
    Account account = account();
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dalMock = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      dalMock.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(TOKEN))
          .thenReturn(account);
      // The checkout endpoint is owner-gated; an unstubbed boolean would answer 403 first.
      dalMock.when(() -> EtendoGoJwtDalHelper.hasOwnedEnvironmentForAccountEmail("user@test.com"))
          .thenReturn(true);
      servlet.doPost(req, resp.response);
    }
    return resp;
  }

  private static Account account() {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACC-1");
    when(account.getEmail()).thenReturn("user@test.com");
    return account;
  }

  private static HttpServletRequest authenticatedRequest(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);
    return request;
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

    String body() {
      return body.toString();
    }
  }
}
