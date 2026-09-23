/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.sun.net.httpserver.HttpServer;

/** Specs for the Stripe Customer Portal session form and the live subscription detail. */
class StripeCustomerPortalServiceTest {

  @Test
  void encodesCustomerAndReturnUrlAsFormValues() throws IOException {
    String form = StripeCustomerPortalService.buildForm(
        "cus/alpha + beta",
        "https://go.example.test/account?tab=billing&mode=full");

    assertEquals(
        "customer=cus%2Falpha+%2B+beta&return_url=https%3A%2F%2Fgo.example.test%2Faccount"
            + "%3Ftab%3Dbilling%26mode%3Dfull",
        form);
  }

  @Test
  void keepsTheAccountPathInTheEncodedReturnUrl() throws IOException {
    String form = StripeCustomerPortalService.buildForm(
        "cus_test_123",
        "https://go.example.test/account");

    assertEquals(
        "customer=cus_test_123&return_url=https%3A%2F%2Fgo.example.test%2Faccount",
        form);
  }

  @Test
  void encodesEmptyValuesWithoutDroppingFormKeys() throws IOException {
    assertEquals("customer=&return_url=", StripeCustomerPortalService.buildForm("", ""));
  }

  // ===================== Live subscription detail =====================

  /** 2026-12-01T00:00:00Z. */
  private static final long PERIOD_END = 1796083200L;

  private static JSONObject subscription(String extraTopLevel, String extraItem) throws Exception {
    return new JSONObject("{\"status\":\"active\",\"cancel_at_period_end\":true"
        + extraTopLevel + ",\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
        + "\"nickname\":\"Productive\",\"unit_amount\":2900,\"currency\":\"eur\"}"
        + extraItem + "}]}}");
  }

  @Test
  void normalizesTheProviderCurrencyToUpperCase() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(subscription("", ""));

    assertEquals("EUR", detail.getCurrency());
    assertEquals("Productive", detail.getPlan());
    assertEquals(2900L, detail.getAmountMinor());
    assertEquals("active", detail.getStatus());
    assertTrue(detail.isCancelAtPeriodEnd());
  }

  // ===================== Plan name (nickname vs. product vs. price id) =====================

  /** Stripe sends {@code nickname: null} for a price with no custom nickname, not an absent key. */
  @Test
  void fallsBackToTheExpandedProductNameWhenNicknameIsNull() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(new JSONObject(
            "{\"status\":\"active\",\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
                + "\"nickname\":null,\"unit_amount\":2900,\"currency\":\"eur\","
                + "\"product\":{\"id\":\"prod_1\",\"name\":\"Etendo GO Pro\"}}}]}}"));

    assertEquals("Etendo GO Pro", detail.getPlan());
  }

  @Test
  void fallsBackToThePriceIdWhenNicknameIsNullAndTheProductHasNoUsableName() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail unnamedProduct =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(new JSONObject(
            "{\"status\":\"active\",\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
                + "\"nickname\":null,\"unit_amount\":2900,\"currency\":\"eur\","
                + "\"product\":{\"id\":\"prod_1\"}}}]}}"));
    assertEquals("price_1", unnamedProduct.getPlan());

    // product not expanded: Stripe sends it as a bare id string instead of an object.
    StripeCustomerPortalService.SubscriptionDetail unexpandedProduct =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(new JSONObject(
            "{\"status\":\"active\",\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
                + "\"nickname\":null,\"unit_amount\":2900,\"currency\":\"eur\","
                + "\"product\":\"prod_1\"}}]}}"));
    assertEquals("price_1", unexpandedProduct.getPlan());
  }

  @Test
  void aNonNullNicknameWinsOverTheExpandedProductName() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(new JSONObject(
            "{\"status\":\"active\",\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
                + "\"nickname\":\"Productive\",\"unit_amount\":2900,\"currency\":\"eur\","
                + "\"product\":{\"id\":\"prod_1\",\"name\":\"Etendo GO Pro\"}}}]}}"));

    assertEquals("Productive", detail.getPlan());
  }

  /** A null currency or status must read as blank, never as the literal text "null". */
  @Test
  void readsNullCurrencyAndStatusAsBlankNotTheLiteralWordNull() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(new JSONObject(
            "{\"status\":null,\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
                + "\"nickname\":\"Productive\",\"unit_amount\":2900,\"currency\":null}}]}}"));

    assertEquals("", detail.getCurrency());
    assertEquals("", detail.getStatus());
  }

  @Test
  void readsTheRenewalFromTheTopLevelPeriodEnd() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(
            subscription(",\"current_period_end\":" + PERIOD_END, ""));

    assertEquals("2026-12-01T00:00:00Z", detail.getRenewalAt());
  }

  /** API 2025-03-31 and later moved the period onto the subscription item. */
  @Test
  void readsTheRenewalFromTheFirstItemWhenTheTopLevelHasNone() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(
            subscription("", ",\"current_period_end\":" + PERIOD_END));

    assertEquals("2026-12-01T00:00:00Z", detail.getRenewalAt());
  }

  @Test
  void leavesTheRenewalEmptyWhenNoPeriodEndIsPresent() throws Exception {
    StripeCustomerPortalService.SubscriptionDetail detail =
        StripeCustomerPortalService.SubscriptionDetail.fromProviderJson(subscription("", ""));

    assertNull(detail.getRenewalAt());
  }

  @Test
  void rejectsASubscriptionWithoutItems() {
    assertThrows(IOException.class, () -> StripeCustomerPortalService.SubscriptionDetail
        .fromProviderJson(new JSONObject("{\"status\":\"active\"}")));
  }

  @Test
  void boundsEveryProviderCall() {
    assertEquals(5_000, StripeCustomerPortalService.CONNECT_TIMEOUT_MS);
    assertEquals(10_000, StripeCustomerPortalService.READ_TIMEOUT_MS);
  }

  /** A crafted id must not escape the subscription path segment. */
  @Test
  void encodesTheSubscriptionIdIntoItsOwnPathSegment() throws Exception {
    AtomicReference<String> rawPath = new AtomicReference<>();
    HttpServer server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      rawPath.set(exchange.getRequestURI().getRawPath());
      byte[] body = subscriptionBody().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try (MockedStatic<CheckoutConfiguration> configuration =
             mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::isConfigured).thenReturn(true);
      configuration.when(CheckoutConfiguration::secretKey).thenReturn("sk_test");
      configuration.when(CheckoutConfiguration::apiBaseUrl)
          .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());

      StripeCustomerPortalService.SubscriptionDetail detail =
          new StripeCustomerPortalService().retrieveSubscription("sub_1/../customers x");

      assertEquals("/v1/subscriptions/sub_1%2F..%2Fcustomers+x", rawPath.get());
      assertEquals("EUR", detail.getCurrency());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refusesToRetrieveWithoutASubscriptionId() {
    try (MockedStatic<CheckoutConfiguration> configuration =
             mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::isConfigured).thenReturn(true);
      assertThrows(IllegalArgumentException.class,
          () -> new StripeCustomerPortalService().retrieveSubscription("  "));
    }
  }

  @Test
  void refusesToRetrieveWhenCheckoutIsNotConfigured() {
    try (MockedStatic<CheckoutConfiguration> configuration =
             mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::isConfigured).thenReturn(false);
      assertThrows(IllegalStateException.class,
          () -> new StripeCustomerPortalService().retrieveSubscription("sub_1"));
    }
  }

  private static String subscriptionBody() {
    return "{\"status\":\"active\",\"items\":{\"data\":[{\"price\":{\"id\":\"price_1\","
        + "\"unit_amount\":2900,\"currency\":\"eur\"}}]}}";
  }
}
