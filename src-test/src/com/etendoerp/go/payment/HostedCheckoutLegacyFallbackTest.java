/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Specs for the <b>legacy price fallback</b> at checkout (ETP-5046 design §6.1).
 *
 * <p>While {@code etendo.go.checkout.price.id} is configured and no active plan carries a provider
 * price, a request naming {@code legacy-productive} — or naming no plan at all, which is how an
 * app-shell older than the plan catalog asks — is sold at that configured price and recorded
 * under the grandfathered plan. Once the first priced plan exists the same request is refused as
 * {@code PLAN_NOT_AVAILABLE}.
 *
 * <p>Three things here fail silently if they regress, which is why each is pinned:
 * <ul>
 *   <li>the fallback charging a <em>plan's</em> price or a plan being charged the fallback's
 *       price — both look like a working checkout;</li>
 *   <li>the fallback catching arbitrary keys, which would sell the legacy price under any string
 *       a browser sends;</li>
 *   <li>a reopened checkout re-deciding its price from the current catalog instead of the price
 *       stored on the request.</li>
 * </ul>
 */
class HostedCheckoutLegacyFallbackTest {

  private static final String ACCOUNT_ID = "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6";
  private static final String ACCOUNT_EMAIL = "buyer@example.test";
  private static final String CLIENT_NAME = "Acme Productive";
  private static final String ORIGIN = "https://go.experimental.etendo.cloud";
  private static final String LEGACY_KEY = PlanCatalogService.LEGACY_PLAN_KEY;
  private static final String LEGACY_PRICE_ID = "price_LEGACY_configured";
  private static final String PLAN_KEY = "productive-monthly";
  private static final String PLAN_PRICE_ID = "price_PLAN_monthly";
  private static final String REQUEST_ID = "purchase-legacy-1";
  private static final String PRICE_FIELD = "line_items%5B0%5D%5Bprice%5D=";

  private static final String SECRET_PROPERTY = "etendo.go.checkout.secret.key";
  private static final String WEBHOOK_PROPERTY = "etendo.go.checkout.webhook.secret";

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  private HostedCheckoutService service;
  private PlanCatalogService catalog;
  private CheckoutRequestStore store;
  private StripeApiClient stripe;
  private StripePriceService prices;

  @BeforeEach
  void setUp() throws Exception {
    OBPropertiesProvider provider = Mockito.mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    // Credentials only: isConfigured() no longer needs a price, the fallback reads its own.
    System.setProperty(SECRET_PROPERTY, "sk_test_x");
    System.setProperty(WEBHOOK_PROPERTY, "whsec_x");

    catalog = mock(PlanCatalogService.class);
    // The shared "is it sellable" predicate stays real, so no spec can pass on a stubbed answer.
    when(catalog.hasProviderPrice(any())).thenCallRealMethod();
    store = mock(CheckoutRequestStore.class);
    stripe = mock(StripeApiClient.class);
    prices = mock(StripePriceService.class);
    service = new HostedCheckoutService();
    service.planCatalogService = catalog;
    service.checkoutRequestStore = store;
    service.stripeApiClient = stripe;
    service.stripePriceService = prices;

    when(stripe.postForm(anyString(), anyString(), anyString())).thenReturn(new StripeResponse(200,
        new JSONObject().put("id", "cs_new").put("url", "https://checkout.test/s").toString()));
  }

  @AfterEach
  void tearDown() {
    propertiesMock.close();
    System.clearProperty(SECRET_PROPERTY);
    System.clearProperty(WEBHOOK_PROPERTY);
  }

  // ===================== fallback active =====================

  @Test
  void theLegacyKeyIsSoldAtTheConfiguredPriceWhileTheFallbackIsActive() throws Exception {
    Plan legacy = legacyPlan();
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));
    StripePriceService.Price configured = price(LEGACY_PRICE_ID, "month");
    when(prices.retrieveConfiguredPrice()).thenReturn(configured);

    JSONObject result = createSession(LEGACY_KEY);

    assertEquals(LEGACY_PRICE_ID, result.getString("priceId"));
    assertEquals("subscription", result.getString("mode"));
    String form = sentForm();
    assertTrue(form.contains(PRICE_FIELD + LEGACY_PRICE_ID), form);
    assertTrue(form.contains("metadata%5Bplan_key%5D=" + LEGACY_KEY), form);
    // Recorded under the grandfathered plan with the price actually charged: the subscription
    // opened after payment snapshots exactly this price id, because the plan has none of its own.
    verify(store).recordRequested(anyString(), eq(ACCOUNT_ID), eq(ACCOUNT_EMAIL), eq(CLIENT_NAME),
        eq(new CheckoutRequestStore.RequestOptions(null, true, false, false, LEGACY_PRICE_ID)),
        eq(legacy));
    verify(prices, never()).retrievePrice(anyString());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  void aRequestNamingNoPlanIsSoldAtTheConfiguredPriceWhileTheFallbackIsActive(String planKey)
      throws Exception {
    // An app-shell deployed before the plan catalog sends no planKey at all. It must keep buying.
    Plan legacy = legacyPlan();
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, "month"));

    JSONObject result = createSession(planKey);

    assertEquals(LEGACY_PRICE_ID, result.getString("priceId"));
    verify(store).recordRequested(anyString(), any(), any(), any(),
        eq(new CheckoutRequestStore.RequestOptions(null, true, false, false, LEGACY_PRICE_ID)),
        eq(legacy));
    assertTrue(sentForm().contains(PRICE_FIELD + LEGACY_PRICE_ID));
  }

  @Test
  void aOneTimeConfiguredPriceMakesAPaymentCheckout() throws Exception {
    Plan legacy = legacyPlan();
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));
    when(prices.retrieveConfiguredPrice()).thenReturn(price(LEGACY_PRICE_ID, "once"));

    JSONObject result = createSession(LEGACY_KEY);

    assertEquals("payment", result.getString("mode"));
    assertTrue(sentForm().contains("mode=payment"));
  }

  @Test
  void anUnknownKeyIsRefusedEvenWhileTheFallbackIsActive() throws Exception {
    // The fallback answers ONLY for the grandfathered key or no key. Anything else a browser sends
    // is looked up in the catalog like always, and refused — never sold at the legacy price.
    Plan legacy = legacyPlan();
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));
    when(catalog.findPurchasablePlan("tampered-key")).thenReturn(Optional.empty());

    assertThrows(PlanNotAvailableException.class, () -> createSession("tampered-key"));

    verify(prices, never()).retrieveConfiguredPrice();
    verifyNothingRecordedOrSent();
  }

  @Test
  void aConfiguredPriceStripeRefusesStopsTheCheckoutBeforeAnythingIsRecorded() throws Exception {
    Plan legacy = legacyPlan();
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));
    when(prices.retrieveConfiguredPrice()).thenThrow(new IOException("price archived"));

    assertThrows(IOException.class, () -> createSession(LEGACY_KEY));

    verifyNothingRecordedOrSent();
  }

  // ===================== fallback inactive =====================

  @Test
  void theLegacyKeyIsRefusedOnceTheFallbackIsInactive() throws Exception {
    // A priced plan exists now (or the property was removed): the grandfathered row is still
    // active, but it has no price of its own and nothing may be sold under it.
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.empty());
    Plan legacy = legacyPlan();
    when(catalog.findPurchasablePlan(LEGACY_KEY)).thenReturn(Optional.of(legacy));

    assertThrows(PlanNotAvailableException.class, () -> createSession(LEGACY_KEY));

    verify(prices, never()).retrieveConfiguredPrice();
    verifyNothingRecordedOrSent();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = { "", "   " })
  void aRequestNamingNoPlanIsRefusedOnceTheFallbackIsInactive(String planKey) throws Exception {
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.empty());

    // PLAN_NOT_AVAILABLE, not CHECKOUT_NOT_CONFIGURED: the deployment is fine, the page is stale.
    assertThrows(PlanNotAvailableException.class, () -> createSession(planKey));

    verify(prices, never()).retrieveConfiguredPrice();
    verifyNothingRecordedOrSent();
  }

  @Test
  void aLegacyPlanGivenItsOwnPriceIsSoldAtThatPriceOnceTheFallbackIsInactive() throws Exception {
    // The documented escape hatch: an operator attaching a price to the grandfathered row makes
    // it an ordinary priced plan.
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.empty());
    Plan legacy = plan(LEGACY_KEY, "price_LEGACY_own");
    when(catalog.findPurchasablePlan(LEGACY_KEY)).thenReturn(Optional.of(legacy));
    when(prices.retrievePrice("price_LEGACY_own")).thenReturn(price("price_LEGACY_own", "year"));

    JSONObject result = createSession(LEGACY_KEY);

    assertEquals("price_LEGACY_own", result.getString("priceId"));
    verify(prices, never()).retrieveConfiguredPrice();
  }

  // ===================== priced plans =====================

  @ParameterizedTest
  @CsvSource({ "month, subscription", "year, subscription", "once, payment" })
  void aPricedPlanIsChargedItsOwnPriceNeverTheConfiguredOne(String interval, String mode)
      throws Exception {
    Plan priced = plan(PLAN_KEY, PLAN_PRICE_ID);
    when(catalog.findPurchasablePlan(PLAN_KEY)).thenReturn(Optional.of(priced));
    when(prices.retrievePrice(PLAN_PRICE_ID)).thenReturn(price(PLAN_PRICE_ID, interval));

    JSONObject result = createSession(PLAN_KEY);

    assertEquals(PLAN_PRICE_ID, result.getString("priceId"));
    // The mode derives from the price interval, not from etendo.go.checkout.mode.
    assertEquals(mode, result.getString("mode"));
    String form = sentForm();
    assertTrue(form.contains("mode=" + mode), form);
    assertTrue(form.contains(PRICE_FIELD + PLAN_PRICE_ID), form);
    assertFalse(form.contains(LEGACY_PRICE_ID), form);
    verify(prices, never()).retrieveConfiguredPrice();
    // A named, non-legacy plan never even asks whether the fallback is on.
    verify(catalog, never()).findLegacyFallbackPlan();
    verify(store).recordRequested(anyString(), any(), any(), any(),
        eq(new CheckoutRequestStore.RequestOptions(null, true, false, false, PLAN_PRICE_ID)),
        eq(priced));
  }

  @Test
  void anActiveNonLegacyPlanWithoutAPriceIsADeploymentStateNotABadRequest() throws Exception {
    Plan unpriced = plan(PLAN_KEY, null);
    when(catalog.findPurchasablePlan(PLAN_KEY)).thenReturn(Optional.of(unpriced));

    // 503 CHECKOUT_NOT_CONFIGURED at the servlet — the specific type, not any IllegalStateException
    // (a locked transfer selection is one too, and answers something else).
    assertThrows(HostedCheckoutService.CheckoutNotConfiguredException.class,
        () -> createSession(PLAN_KEY));

    verify(prices, never()).retrieveConfiguredPrice();
    verifyNothingRecordedOrSent();
  }

  // ===================== reopen =====================

  @Test
  void aReopenedLegacyCheckoutChargesItsStoredPriceAfterTheFallbackRetired() throws Exception {
    // Bought under the fallback; since then an operator added a priced plan. The catalog would now
    // refuse the legacy key — but a reopen never asks it: it charges what the buyer was shown.
    Plan pricedSinceThen = plan(PLAN_KEY, PLAN_PRICE_ID);
    when(catalog.isLegacyFallbackActive()).thenReturn(false);
    when(catalog.listPurchasablePlans()).thenReturn(List.of(pricedSinceThen));
    when(store.findStripeSessionId(REQUEST_ID)).thenReturn("cs_expired");
    when(stripe.get(anyString())).thenReturn(new StripeResponse(200,
        new JSONObject().put("id", "cs_expired").put("status", "expired").toString()));
    when(store.findStripePriceId(REQUEST_ID)).thenReturn(LEGACY_PRICE_ID);
    when(prices.retrievePrice(LEGACY_PRICE_ID)).thenReturn(price(LEGACY_PRICE_ID, "month"));
    Plan legacy = legacyPlan();
    CheckoutRequest request = mock(CheckoutRequest.class);
    when(request.getPlan()).thenReturn(legacy);
    when(store.find(REQUEST_ID, ACCOUNT_EMAIL)).thenReturn(request);

    JSONObject result = service.reopenSession(REQUEST_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN);

    assertEquals(LEGACY_PRICE_ID, result.getString("priceId"));
    String form = sentForm();
    assertTrue(form.contains(PRICE_FIELD + LEGACY_PRICE_ID), form);
    assertFalse(form.contains(PLAN_PRICE_ID), form);
    assertTrue(form.contains("metadata%5Bplan_key%5D=" + LEGACY_KEY), form);
    verify(prices, never()).retrieveConfiguredPrice();
    verifyNoInteractions(catalog);
    verify(store).recordSessionCreated(REQUEST_ID, "cs_expired", "cs_new");
  }

  @Test
  void aReopenWithNoStoredPriceCannotBeResumed() throws Exception {
    // A request with no price recorded cannot be resumed at "whatever the catalog says now":
    // that would charge a price the buyer was never shown.
    when(store.findStripeSessionId(REQUEST_ID)).thenReturn(null);
    when(store.findStripePriceId(REQUEST_ID)).thenReturn(null);
    Plan legacy = legacyPlan();
    when(catalog.findLegacyFallbackPlan()).thenReturn(Optional.of(legacy));

    assertThrows(HostedCheckoutService.OriginalPriceUnavailableException.class,
        () -> service.reopenSession(REQUEST_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN));

    verify(prices, never()).retrieveConfiguredPrice();
    verify(prices, never()).retrievePrice(anyString());
    verify(stripe, never()).postForm(anyString(), anyString(), anyString());
  }

  // ===================== helpers =====================

  private JSONObject createSession(String planKey) throws Exception {
    return service.createSession(ACCOUNT_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN, planKey);
  }

  private String sentForm() throws Exception {
    ArgumentCaptor<String> form = ArgumentCaptor.forClass(String.class);
    verify(stripe).postForm(anyString(), form.capture(), anyString());
    return form.getValue();
  }

  private void verifyNothingRecordedOrSent() throws Exception {
    verify(store, never()).recordRequested(anyString(), any(), any(), any(), any(), any());
    verify(stripe, never()).postForm(anyString(), anyString(), anyString());
  }

  private static Plan legacyPlan() {
    return plan(LEGACY_KEY, null);
  }

  private static Plan plan(String key, String priceId) {
    Plan plan = mock(Plan.class);
    when(plan.getSearchKey()).thenReturn(key);
    when(plan.getProviderPriceID()).thenReturn(priceId);
    return plan;
  }

  private static StripePriceService.Price price(String id, String interval) throws Exception {
    JSONObject json = new JSONObject().put("id", id).put("active", true).put("unit_amount", 2900)
        .put("currency", "eur");
    if (!"once".equals(interval)) {
      json.put("recurring", new JSONObject().put("interval", interval).put("interval_count", 1));
    }
    return StripePriceService.parsePrice(json);
  }
}
