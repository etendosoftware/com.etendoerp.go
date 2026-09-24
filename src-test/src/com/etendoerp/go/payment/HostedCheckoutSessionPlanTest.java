/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Properties;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Specs for how a Checkout Session decides what it charges (ETP-5046).
 *
 * <p>Before this ticket the price came from a deployment property. It now comes from the
 * Subscription Plan Catalog row named by the {@code planKey} the browser sent. The deployment
 * property survives only as the narrow, self-retiring <b>legacy price fallback</b> for the
 * grandfathered plan, pinned separately in {@link HostedCheckoutLegacyFallbackTest}; a named,
 * priced plan is never charged it.
 *
 * <p>Two refusals must stay distinguishable, because they mean opposite things to the person on
 * the other end — "your request named something that is not for sale" versus "this deployment has
 * nothing to sell yet".
 */
class HostedCheckoutSessionPlanTest {

  private static final String ACCOUNT_ID = "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6";
  private static final String ACCOUNT_EMAIL = "buyer@example.test";
  private static final String CLIENT_NAME = "Acme Productive";
  private static final String ORIGIN = "https://go.experimental.etendo.cloud";
  private static final String PLAN_KEY = "productive-monthly";
  private static final String PRICE_ID = "price_LIVE_123";

  private static final String SECRET_PROPERTY = "etendo.go.checkout.secret.key";
  private static final String WEBHOOK_PROPERTY = "etendo.go.checkout.webhook.secret";

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  private HostedCheckoutService service;
  private PlanCatalogService planCatalogService;
  private CheckoutRequestStore checkoutRequestStore;
  private StripeApiClient stripeApiClient;
  private StripePriceService stripePriceService;

  @BeforeEach
  void setUp() {
    OBPropertiesProvider provider = Mockito.mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    // isConfigured() now proves ONLY that provider credentials exist. It used to additionally
    // prove that a purchasable thing existed; that guarantee moved to the plan catalog.
    System.setProperty(SECRET_PROPERTY, "sk_test_x");
    System.setProperty(WEBHOOK_PROPERTY, "whsec_x");

    planCatalogService = mock(PlanCatalogService.class);
    checkoutRequestStore = mock(CheckoutRequestStore.class);
    stripeApiClient = mock(StripeApiClient.class);
    stripePriceService = mock(StripePriceService.class);
    service = new HostedCheckoutService();
    service.stripePriceService = stripePriceService;
    service.planCatalogService = planCatalogService;
    service.checkoutRequestStore = checkoutRequestStore;
    service.stripeApiClient = stripeApiClient;
  }

  @AfterEach
  void tearDown() {
    propertiesMock.close();
    System.clearProperty(SECRET_PROPERTY);
    System.clearProperty(WEBHOOK_PROPERTY);
  }

  private static Plan planWith(String priceId) {
    Plan plan = mock(Plan.class);
    when(plan.getSearchKey()).thenReturn(PLAN_KEY);
    when(plan.getProviderPriceID()).thenReturn(priceId);
    return plan;
  }

  private void givenTheProviderAccepts() throws Exception {
    // The plan's price is validated against the provider before anything is recorded.
    when(stripePriceService.retrievePrice(PRICE_ID)).thenReturn(StripePriceService.parsePrice(
        new JSONObject().put("id", PRICE_ID).put("active", true).put("unit_amount", 2900)
            .put("currency", "eur")
            .put("recurring", new JSONObject().put("interval", "month").put("interval_count", 1))));
    when(stripeApiClient.postForm(anyString(), anyString(), anyString()))
        .thenReturn(new StripeResponse(200,
            new JSONObject().put("id", "cs_test_1").put("url", "https://checkout.test/s").toString()));
  }

  @Test
  void chargesThePriceOfTheResolvedPlan() throws Exception {
    Plan plan = planWith(PRICE_ID);
    when(planCatalogService.findPurchasablePlan(PLAN_KEY)).thenReturn(Optional.of(plan));
    when(planCatalogService.hasProviderPrice(plan)).thenReturn(true);
    givenTheProviderAccepts();

    JSONObject result =
        service.createSession(ACCOUNT_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, PLAN_KEY);

    assertEquals("https://checkout.test/s", result.getString("checkoutUrl"));
    ArgumentCaptor<String> form = ArgumentCaptor.forClass(String.class);
    verify(stripeApiClient).postForm(anyString(), form.capture(), anyString());
    assertTrue(form.getValue().contains("line_items%5B0%5D%5Bprice%5D=" + PRICE_ID),
        form.getValue());
  }

  @Test
  void recordsTheBoughtPlanOnTheDurableRequestRow() throws Exception {
    Plan plan = planWith(PRICE_ID);
    when(planCatalogService.findPurchasablePlan(PLAN_KEY)).thenReturn(Optional.of(plan));
    when(planCatalogService.hasProviderPrice(plan)).thenReturn(true);
    givenTheProviderAccepts();

    service.createSession(ACCOUNT_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, PLAN_KEY);

    // The subscription opened after payment reads its plan off this row, so it records what the
    // buyer actually saw rather than whatever the plan catalog holds by the time provisioning runs.
    verify(checkoutRequestStore).recordRequested(anyString(), any(), any(), any(), any(),
        any(Plan.class));
  }

  @Test
  void refusesAPlanKeyThatNamesNoActiveCatalogRow() {
    // A tampered or stale key. Unknown and inactive are one outcome on purpose: the endpoint must
    // not let a caller enumerate the plan catalog by probing keys.
    when(planCatalogService.findPurchasablePlan("tampered-key")).thenReturn(Optional.empty());

    assertThrows(PlanNotAvailableException.class, () -> service.createSession(ACCOUNT_ID,
        ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, "tampered-key"));
  }

  @Test
  void recordsNothingAndContactsNobodyWhenThePlanIsUnavailable() throws Exception {
    when(planCatalogService.findPurchasablePlan("tampered-key")).thenReturn(Optional.empty());

    assertThrows(PlanNotAvailableException.class, () -> service.createSession(ACCOUNT_ID,
        ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, "tampered-key"));

    // The durable row is evidence that someone tried to buy something real. A rejected key is not
    // that, and the provider is never contacted for it either.
    verify(checkoutRequestStore, never())
        .recordRequested(anyString(), any(), any(), any(), any(), any());
    verify(stripeApiClient, never()).postForm(anyString(), anyString(), anyString());
  }

  @Test
  void refusesToSellAPlanThatCarriesNoProviderPrice() throws Exception {
    // The grandfathered legacy plan, and any plan an operator has not yet attached a real price
    // id to — a price id is environment-specific (test vs live) and cannot ship as sourcedata.
    // This is a deployment state, not a bad request, so it is the same IllegalStateException the
    // missing-credentials case raises and lands on CHECKOUT_NOT_CONFIGURED.
    Plan legacy = planWith(null);
    when(planCatalogService.findPurchasablePlan(PLAN_KEY)).thenReturn(Optional.of(legacy));
    when(planCatalogService.hasProviderPrice(legacy)).thenReturn(false);

    assertThrows(IllegalStateException.class,
        () -> service.createSession(ACCOUNT_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, PLAN_KEY));

    verify(checkoutRequestStore, never())
        .recordRequested(anyString(), any(), any(), any(), any(), any());
    verify(stripeApiClient, never()).postForm(anyString(), anyString(), anyString());
  }

  @Test
  void refusesWhenTheProviderCredentialsAreMissing() {
    System.clearProperty(SECRET_PROPERTY);

    assertThrows(IllegalStateException.class,
        () -> service.createSession(ACCOUNT_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, PLAN_KEY));
  }
}
