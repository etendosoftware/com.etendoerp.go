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
 * proved that <em>a purchasable thing existed</em>. That guarantee moved to the plan catalog, and
 * deliberately did not acquire a fallback: a fallback price is a price nobody reviewed, selected
 * exactly at the moment the intended configuration is missing — the one moment it must not be
 * charged. These specs pin that the price setting is gone rather than merely unused, because a
 * lingering read of it would quietly reintroduce exactly that.
 */
class CheckoutConfigurationTest {

  private static final String SECRET_KEY_PROPERTY = "etendo.go.checkout.secret.key";
  private static final String WEBHOOK_SECRET_PROPERTY = "etendo.go.checkout.webhook.secret";
  private static final String RETIRED_PRICE_PROPERTY = "etendo.go.checkout.price.id";
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
    System.clearProperty(RETIRED_PRICE_PROPERTY);
    System.clearProperty(CONNECT_TIMEOUT_PROPERTY);
    System.clearProperty(READ_TIMEOUT_PROPERTY);
  }

  @Test
  @DisplayName("the secret key and the webhook secret are the whole requirement")
  void secretAndWebhookSecretAreEnough() {
    System.setProperty(SECRET_KEY_PROPERTY, "sk_test_abc");
    System.setProperty(WEBHOOK_SECRET_PROPERTY, "whsec_abc");

    assertTrue(CheckoutConfiguration.isConfigured(),
        "no price setting exists any more, so requiring one would make checkout permanently"
            + " unconfigured");
  }

  @Test
  @DisplayName("the retired price setting is not consulted, even when it is present")
  void theRetiredPriceSettingIsIgnored() {
    // Set to a value that would once have been used. What is purchasable comes from the plan
    // catalog now; a read of this key sneaking back in is exactly the fallback the ticket forbids.
    System.setProperty(RETIRED_PRICE_PROPERTY, "price_LEFTOVER");
    System.setProperty(SECRET_KEY_PROPERTY, "sk_test_abc");
    System.setProperty(WEBHOOK_SECRET_PROPERTY, "whsec_abc");

    assertTrue(CheckoutConfiguration.isConfigured());
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
