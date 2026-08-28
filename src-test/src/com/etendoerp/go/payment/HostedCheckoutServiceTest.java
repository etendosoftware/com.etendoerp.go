/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UnsupportedEncodingException;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Specs for the Checkout Session body Etendo sends to Stripe.
 *
 * <p>The body is the entire agreement with the provider — what is charged, what the webhook can
 * correlate back to an account, and what the buyer is allowed to do on the hosted page. None of it
 * is observable from this side once the request leaves, so a field that is missing or misspelled only
 * shows up when a human reaches the checkout page and finds it behaves differently than intended.
 * These specs pin each field explicitly for that reason.
 */
class HostedCheckoutServiceTest {

  private static final String REQUEST_ID = "req-abc-123";
  private static final String ACCOUNT_EMAIL = "buyer@example.test";
  private static final String CLIENT_NAME = "Acme Productive";
  private static final String ORIGIN = "https://go.experimental.etendo.cloud";

  private static final String MODE_PROPERTY = "etendo.go.checkout.mode";
  private static final String PRICE_PROPERTY = "etendo.go.checkout.price.id";

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  /**
   * Pin the configuration this body is built from, so the assertions below cannot pass or fail
   * because of a developer's local {@code Openbravo.properties}. Same reasoning as
   * {@code PropertiesFeatureProviderTest}.
   */
  @BeforeEach
  void isolateConfiguration() {
    OBPropertiesProvider provider = Mockito.mock(OBPropertiesProvider.class);
    Mockito.when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = Mockito.mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    System.setProperty(PRICE_PROPERTY, "price_TEST");
  }

  @AfterEach
  void clearConfiguration() {
    if (propertiesMock != null) {
      propertiesMock.close();
    }
    System.clearProperty(MODE_PROPERTY);
    System.clearProperty(PRICE_PROPERTY);
  }

  private static String form() throws UnsupportedEncodingException {
    return HostedCheckoutService.buildSessionForm(REQUEST_ID, ACCOUNT_EMAIL, CLIENT_NAME, ORIGIN);
  }

  @Test
  void offersThePromotionCodeFieldOnTheHostedPage() throws UnsupportedEncodingException {
    // Without this parameter Stripe hides the "Add promotion code" input entirely, so a coupon the
    // business has already created is unreachable and the buyer pays full price with no way to say
    // otherwise. It is a product decision, not a default.
    assertTrue(form().contains("allow_promotion_codes=true"),
        "the session must let the buyer enter a promotion code");
  }

  @Test
  void skipsCardCollectionWhenAPromotionCodeLeavesNothingDue()
      throws UnsupportedEncodingException {
    // Stripe defaults to `always`, which asks for a card even when a 100%-off promotion code brings
    // the total to zero. `if_required` is evaluated per session against the amount due, so the
    // normal paid path still collects a card and only a fully discounted one skips it.
    //
    // This is safe ONLY for coupons with duration=forever. A duration=once or =repeating coupon
    // leaves no payment method on file, so the first invoice after the discount ends has nothing to
    // charge and the subscription fails to renew. Coupons meant to make an environment free must be
    // created as `forever`.
    assertTrue(form().contains("payment_method_collection=if_required"),
        "a fully discounted session must not demand a card");
  }

  @Test
  void chargesTheServerSelectedPriceExactlyOnce() throws UnsupportedEncodingException {
    String body = form();

    // Pricing is server-owned: the browser sends product intent only, never an amount or a price id.
    assertTrue(body.contains("line_items%5B0%5D%5Bprice%5D=price_TEST"), body);
    assertTrue(body.contains("line_items%5B0%5D%5Bquantity%5D=1"), body);
  }

  @Test
  void carriesTheCorrelationTheWebhookNeedsToConfirmThePayment() throws UnsupportedEncodingException {
    String body = form();

    // CheckoutPaymentRegistry matches a confirmed payment on all three of these. Lose any one and
    // the webhook records a payment the paywall can never find, so the account is charged and
    // provisioning is refused.
    assertTrue(body.contains("metadata%5Brequest_id%5D=" + REQUEST_ID), body);
    assertTrue(body.contains("metadata%5Baccount_email%5D=buyer%40example.test"), body);
    assertTrue(body.contains("metadata%5Bclient_name%5D=Acme+Productive"), body);
    assertTrue(body.contains("client_reference_id=" + REQUEST_ID), body);
  }

  @Test
  void bindsTheSessionToTheAuthenticatedAccount() throws UnsupportedEncodingException {
    assertTrue(form().contains("customer_email=buyer%40example.test"));
  }

  @Test
  void returnsTheBuyerToTheUpgradePageCarryingTheRequestId()
      throws UnsupportedEncodingException {
    String body = form();

    assertTrue(body.contains("success_url=https%3A%2F%2Fgo.experimental.etendo.cloud%2Fupgrade"
        + "%3Fcheckout%3Dsuccess%26requestId%3D" + REQUEST_ID), body);
    assertTrue(body.contains("cancel_url=https%3A%2F%2Fgo.experimental.etendo.cloud%2Fupgrade"
        + "%3Fcheckout%3Dcancelled%26requestId%3D" + REQUEST_ID), body);
  }

  @Test
  void repeatsTheCorrelationOnTheSubscriptionItself() throws UnsupportedEncodingException {
    System.setProperty(MODE_PROPERTY, "subscription");

    String body = form();

    assertTrue(body.contains("mode=subscription"), body);
    // Renewal events arrive on the subscription, not on the original session, so the correlation has
    // to live on both.
    assertTrue(body.contains("subscription_data%5Bmetadata%5D%5Brequest_id%5D=" + REQUEST_ID), body);
  }

  @Test
  void neverSendsPaymentMethodCollectionInPaymentMode() throws UnsupportedEncodingException {
    System.setProperty(MODE_PROPERTY, "payment");

    String body = form();

    // Stripe accepts payment_method_collection in subscription mode only, and rejects the whole
    // session when it appears anywhere else. That failure is total — no checkout URL at all — so
    // this guard matters more than the feature it guards.
    assertTrue(body.contains("mode=payment"), body);
    assertFalse(body.contains("payment_method_collection"), body);
    assertFalse(body.contains("subscription_data"), body);
  }

  @Test
  void keepsManagedPaymentsOffUntilTheProductIsEligible() throws UnsupportedEncodingException {
    assertTrue(form().contains("managed_payments%5Benabled%5D=false"));
  }
}
