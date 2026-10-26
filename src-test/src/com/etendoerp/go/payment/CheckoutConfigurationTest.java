/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Specs for what "checkout is configured" means now that there is no configured price.
 *
 * <p>{@code isConfigured()} used to require a price id as well, so a true answer additionally
 * proved that <em>a purchasable thing existed</em>. That guarantee moved to the Subscription Plan
 * Catalog plus the legacy price fallback ({@link PlanCatalogService#isLegacyFallbackActive()}).
 * The price setting still exists, but only as that fallback's input: it must never again be a
 * condition of "checkout is configured", or a deployment selling priced plans would need a
 * leftover legacy price to sell anything at all.
 */
class CheckoutConfigurationTest {

  private static final String SECRET_KEY_PROPERTY = "etendo.go.checkout.secret.key";
  private static final String WEBHOOK_SECRET_PROPERTY = "etendo.go.checkout.webhook.secret";
  private static final String LEGACY_PRICE_PROPERTY = "etendo.go.checkout.price.id";
  private static final String CONNECT_TIMEOUT_PROPERTY = "etendo.go.checkout.connect.timeout.ms";
  private static final String READ_TIMEOUT_PROPERTY = "etendo.go.checkout.read.timeout.ms";

  private MockedStatic<OBPropertiesProvider> propertiesMock;

  /** Isolates from whatever the developer has in their own {@code Openbravo.properties}. */
  @BeforeEach
  void isolateConfiguration() {
    OBPropertiesProvider provider = Mockito.mock(OBPropertiesProvider.class);
    Mockito.when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = Mockito.mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  @AfterEach
  void restore() {
    if (propertiesMock != null) {
      propertiesMock.close();
    }
    System.clearProperty(SECRET_KEY_PROPERTY);
    System.clearProperty(WEBHOOK_SECRET_PROPERTY);
    System.clearProperty(LEGACY_PRICE_PROPERTY);
    System.clearProperty(CONNECT_TIMEOUT_PROPERTY);
    System.clearProperty(READ_TIMEOUT_PROPERTY);
  }

  @Test
  @DisplayName("the secret key and the webhook secret are the whole requirement")
  void secretAndWebhookSecretAreEnough() {
    System.setProperty(SECRET_KEY_PROPERTY, "sk_test_abc");
    System.setProperty(WEBHOOK_SECRET_PROPERTY, "whsec_abc");

    assertTrue(CheckoutConfiguration.isConfigured(),
        "a deployment selling priced plans has no legacy price, so requiring one would make it"
            + " permanently unconfigured");
  }

  @Test
  @DisplayName("the legacy price setting neither enables nor is required for checkout")
  void theLegacyPriceSettingDoesNotDecideWhetherCheckoutIsConfigured() {
    // Present: still configured (it is only the fallback's input)...
    System.setProperty(LEGACY_PRICE_PROPERTY, "price_LEGACY");
    System.setProperty(SECRET_KEY_PROPERTY, "sk_test_abc");
    System.setProperty(WEBHOOK_SECRET_PROPERTY, "whsec_abc");
    assertTrue(CheckoutConfiguration.isConfigured());
    assertEquals("price_LEGACY", CheckoutConfiguration.priceId());

    // ...and on its own it configures nothing: a price without credentials cannot be charged.
    System.clearProperty(SECRET_KEY_PROPERTY);
    assertFalse(CheckoutConfiguration.isConfigured());
  }

  @Test
  @DisplayName("without the legacy price setting the fallback input reads as blank")
  void anAbsentLegacyPriceReadsAsBlank() {
    System.setProperty(SECRET_KEY_PROPERTY, "sk_test_abc");
    System.setProperty(WEBHOOK_SECRET_PROPERTY, "whsec_abc");

    assertTrue(CheckoutConfiguration.isConfigured());
    assertTrue(CheckoutConfiguration.priceId().isBlank(),
        "a blank price is what keeps the legacy fallback inactive");
  }

  @Test
  @DisplayName("a missing secret key means not configured")
  void aMissingSecretKeyMeansNotConfigured() {
    System.setProperty(WEBHOOK_SECRET_PROPERTY, "whsec_abc");

    assertFalse(CheckoutConfiguration.isConfigured());
  }

  @Test
  @DisplayName("a missing webhook secret means not configured")
  void aMissingWebhookSecretMeansNotConfigured() {
    System.setProperty(SECRET_KEY_PROPERTY, "sk_test_abc");

    assertFalse(CheckoutConfiguration.isConfigured());
  }

  @Test
  @DisplayName("the provider call is time-boxed by default")
  void timeoutsHaveNonZeroDefaults() {
    // Zero would mean "wait forever", which is what HttpURLConnection does when nothing sets
    // these — on a save path that means a browser tab that never comes back.
    assertAll(() -> assertEquals(10000, CheckoutConfiguration.connectTimeoutMs()),
        () -> assertEquals(20000, CheckoutConfiguration.readTimeoutMs()));
  }

  @Test
  @DisplayName("the timeouts are configurable per environment")
  void timeoutsCanBeOverridden() {
    System.setProperty(CONNECT_TIMEOUT_PROPERTY, "1500");
    System.setProperty(READ_TIMEOUT_PROPERTY, "2500");

    assertAll(() -> assertEquals(1500, CheckoutConfiguration.connectTimeoutMs()),
        () -> assertEquals(2500, CheckoutConfiguration.readTimeoutMs()));
  }
}
