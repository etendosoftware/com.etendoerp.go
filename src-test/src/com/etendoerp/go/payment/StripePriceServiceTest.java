/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Contract tests for retrieving the exact recurring Stripe Price configured for checkout. */
class StripePriceServiceTest {

  @Test
  void retrievesTheConfiguredCheckoutPriceWithServerAuthentication() throws Exception {
    AtomicReference<String> requestPath = new AtomicReference<>();
    AtomicReference<String> requestMethod = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    try (StubStripeServer stripe = new StubStripeServer(200,
        "{\"id\":\"price_configured\",\"active\":true,\"unit_amount\":1000,"
            + "\"currency\":\"usd\",\"recurring\":{\"interval\":\"year\","
            + "\"interval_count\":1}}", requestPath, requestMethod, authorization);
        MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::priceId).thenReturn("price_configured");
      configuration.when(CheckoutConfiguration::secretKey).thenReturn("sk_test_server_only");
      configuration.when(CheckoutConfiguration::apiBaseUrl).thenReturn(stripe.baseUrl());
      configuration.when(CheckoutConfiguration::mode).thenReturn("subscription");

      BillingOfferConfiguration.Offer offer = BillingOfferConfiguration.current();

      assertEquals("/v1/prices/price_configured", requestPath.get());
      assertEquals("GET", requestMethod.get());
      assertEquals("Bearer sk_test_server_only", authorization.get());
      assertEquals("price_configured", offer.getPriceId());
      assertEquals(1000L, offer.getAmountMinor());
      assertEquals("USD", offer.getCurrency());
      assertEquals("year", offer.getInterval());
    }
  }

  @Test
  void failsClosedWhenTheProviderRejectsTheConfiguredPriceLookup() throws Exception {
    try (StubStripeServer stripe = new StubStripeServer(503, "{\"error\":\"unavailable\"}",
        new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configure(configuration, stripe.baseUrl());

      assertThrows(IOException.class,
          () -> new StripePriceService().retrieveConfiguredPrice());
    }
  }

  @Test
  void failsClosedWhenPriceFieldsAreMissingOrMalformed() throws Exception {
    assertInvalidPrice("{\"currency\":\"eur\",\"recurring\":{\"interval\":\"month\"}} ");
    assertInvalidPrice("{\"unit_amount\":\"not-a-number\",\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\"}}");
    assertInvalidPrice("{\"unit_amount\":1000,\"currency\":\"eur\"}");
    assertThrows(JSONException.class, () -> StripePriceService.parsePrice(new JSONObject("{")));
  }

  @Test
  void rejectsInactivePriceAndNonUnitIntervalCount() throws Exception {
    assertInvalidPrice("{\"active\":false,\"unit_amount\":1000,\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");
    assertInvalidPrice("{\"active\":true,\"unit_amount\":1000,\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":3}}");
    assertInvalidPrice("{\"active\":true,\"unit_amount\":1000,\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":0}}");
    assertInvalidPrice("{\"active\":1,\"unit_amount\":1000,\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");
    assertInvalidPrice("{\"active\":true,\"unit_amount\":1000.5,\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");
    assertInvalidPrice("{\"active\":true,\"unit_amount\":-1,\"currency\":\"eur\","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");
    assertInvalidPrice("{\"active\":true,\"unit_amount\":1000,\"currency\":\"  \","
        + "\"recurring\":{\"interval\":\"month\",\"interval_count\":1}}");
  }

  @Test
  void rejectsOneTimePriceWhenSubscriptionCheckoutIsConfigured() throws Exception {
    String oneTimePrice = "{\"id\":\"price_configured\",\"active\":true,"
        + "\"unit_amount\":1000,\"currency\":\"eur\"}";
    try (StubStripeServer stripe = new StubStripeServer(200, oneTimePrice,
        new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configure(configuration, stripe.baseUrl());

      assertThrows(IOException.class,
          () -> new StripePriceService().retrieveConfiguredPrice());
    }
  }

  @Test
  void refusesPriceLookupWhenStripeCredentialsOrPriceIdAreNotConfigured() {
    try (MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::priceId).thenReturn("");
      configuration.when(CheckoutConfiguration::secretKey).thenReturn("");

      assertThrows(IllegalStateException.class,
          () -> new StripePriceService().retrieveConfiguredPrice());
    }
  }

  @Test
  void reopeningAnOpenPurchaseReusesItsStripeSessionWithoutCreatingAnotherPayableSession()
      throws Exception {
    try (ExistingSessionStripeServer stripe = new ExistingSessionStripeServer();
        MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::isConfigured).thenReturn(true);
      configuration.when(CheckoutConfiguration::priceId).thenReturn("price_default");
      configuration.when(CheckoutConfiguration::secretKey).thenReturn("sk_test_server_only");
      configuration.when(CheckoutConfiguration::apiBaseUrl).thenReturn(stripe.baseUrl());
      configuration.when(CheckoutConfiguration::mode).thenReturn("subscription");
      CheckoutRequestStore store = mock(CheckoutRequestStore.class);
      when(store.findStripePriceId("purchase-1")).thenReturn("price_saved");
      when(store.findStripeSessionId("purchase-1")).thenReturn("cs_existing");
      HostedCheckoutService checkout = new HostedCheckoutService();
      checkout.checkoutRequestStore = store;

      JSONObject first = checkout.reopenSession("purchase-1", "owner@example.test",
          "Acme", "https://app.example.test");
      JSONObject second = checkout.reopenSession("purchase-1", "owner@example.test",
          "Acme", "https://app.example.test");

      assertEquals("purchase-1", first.getString("requestId"));
      assertEquals("purchase-1", second.getString("requestId"));
      assertEquals("https://checkout.example.test/cs_existing", first.getString("checkoutUrl"));
      assertEquals(first.getString("checkoutUrl"), second.getString("checkoutUrl"));
      assertEquals(0, stripe.createSessionCalls.get(),
          "An open Stripe session must be reused instead of creating a second payable session");
      assertEquals(2, stripe.retrieveSessionCalls.get());
      verify(store, org.mockito.Mockito.times(2)).findStripeSessionId("purchase-1");
    }
  }

  @Test
  void repeatedRecoveryForMissingStoredSessionUsesTheSameStripeIdempotencyKey() throws Exception {
    try (ExistingSessionStripeServer stripe = new ExistingSessionStripeServer();
        MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::isConfigured).thenReturn(true);
      configuration.when(CheckoutConfiguration::priceId).thenReturn("price_default");
      configuration.when(CheckoutConfiguration::secretKey).thenReturn("sk_test_server_only");
      configuration.when(CheckoutConfiguration::apiBaseUrl).thenReturn(stripe.baseUrl());
      configuration.when(CheckoutConfiguration::mode).thenReturn("subscription");
      CheckoutRequestStore store = mock(CheckoutRequestStore.class);
      when(store.findStripePriceId("purchase-recovery")).thenReturn("price_saved");
      when(store.findStripeSessionId("purchase-recovery")).thenReturn(null);
      HostedCheckoutService checkout = new HostedCheckoutService();
      checkout.checkoutRequestStore = store;

      checkout.reopenSession("purchase-recovery", "owner@example.test", "Acme",
          "https://app.example.test");
      checkout.reopenSession("purchase-recovery", "owner@example.test", "Acme",
          "https://app.example.test");

      assertEquals(2, stripe.createSessionCalls.get());
      assertEquals(2, stripe.idempotencyKeys.size());
      assertEquals(stripe.idempotencyKeys.get(0), stripe.idempotencyKeys.get(1),
          "A retry after a lost session write must send Stripe the same idempotency key");
      assertEquals(HostedCheckoutService.initialIdempotencyKey("purchase-recovery"),
          stripe.idempotencyKeys.get(0));
    }
  }

  @Test
  void reopeningAnExpiredSessionCreatesItsReplacementWithAnAttemptSpecificIdempotencyKey()
      throws Exception {
    try (ExistingSessionStripeServer stripe = new ExistingSessionStripeServer("expired");
        MockedStatic<CheckoutConfiguration> configuration = mockStatic(CheckoutConfiguration.class)) {
      configuration.when(CheckoutConfiguration::isConfigured).thenReturn(true);
      configuration.when(CheckoutConfiguration::priceId).thenReturn("price_default");
      configuration.when(CheckoutConfiguration::secretKey).thenReturn("sk_test_server_only");
      configuration.when(CheckoutConfiguration::apiBaseUrl).thenReturn(stripe.baseUrl());
      configuration.when(CheckoutConfiguration::mode).thenReturn("subscription");
      CheckoutRequestStore store = mock(CheckoutRequestStore.class);
      when(store.findStripePriceId("purchase-expired")).thenReturn("price_saved");
      when(store.findStripeSessionId("purchase-expired")).thenReturn("cs_existing");
      HostedCheckoutService checkout = new HostedCheckoutService();
      checkout.checkoutRequestStore = store;

      JSONObject replacement = checkout.reopenSession("purchase-expired", "owner@example.test",
          "Acme", "https://app.example.test");

      assertEquals("purchase-expired", replacement.getString("requestId"));
      assertEquals("https://checkout.example.test/cs_duplicate", replacement.getString("checkoutUrl"));
      assertEquals(1, stripe.createSessionCalls.get());
      assertEquals(HostedCheckoutService.replacementIdempotencyKey("purchase-expired", "cs_existing"),
          stripe.idempotencyKeys.get(0));
    }
  }

  private static void assertInvalidPrice(String body) throws JSONException {
    assertThrows(IOException.class,
        () -> StripePriceService.parsePrice(new JSONObject(body)));
  }

  private static void configure(MockedStatic<CheckoutConfiguration> configuration,
      String apiBaseUrl) {
    configuration.when(CheckoutConfiguration::priceId).thenReturn("price_configured");
    configuration.when(CheckoutConfiguration::secretKey).thenReturn("sk_test_server_only");
    configuration.when(CheckoutConfiguration::apiBaseUrl).thenReturn(apiBaseUrl);
    configuration.when(CheckoutConfiguration::mode).thenReturn("subscription");
  }

  private static final class StubStripeServer implements AutoCloseable {
    private final HttpServer server;

    StubStripeServer(int status, String responseBody, AtomicReference<String> requestPath,
        AtomicReference<String> requestMethod, AtomicReference<String> authorization)
        throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        requestPath.set(exchange.getRequestURI().getPath());
        requestMethod.set(exchange.getRequestMethod());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
          output.write(body);
        }
      });
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class ExistingSessionStripeServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger createSessionCalls = new AtomicInteger();
    private final AtomicInteger retrieveSessionCalls = new AtomicInteger();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();

    ExistingSessionStripeServer() throws IOException {
      this("open");
    }

    ExistingSessionStripeServer(String sessionStatus) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/prices/price_saved", exchange -> respond(exchange, 200,
          "{\"id\":\"price_saved\",\"active\":true,\"unit_amount\":1000,"
              + "\"currency\":\"eur\",\"recurring\":{\"interval\":\"month\","
              + "\"interval_count\":1}}"));
      server.createContext("/v1/checkout/sessions/cs_existing", exchange -> {
        retrieveSessionCalls.incrementAndGet();
        respond(exchange, 200,
            "{\"id\":\"cs_existing\",\"status\":\"" + sessionStatus + "\","
                + "\"mode\":\"subscription\","
                + "\"url\":\"https://checkout.example.test/cs_existing\"}");
      });
      server.createContext("/v1/checkout/sessions", exchange -> {
        createSessionCalls.incrementAndGet();
        idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        respond(exchange, 200,
            "{\"id\":\"cs_duplicate\",\"url\":\"https://checkout.example.test/cs_duplicate\"}");
      });
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
        String responseBody) throws IOException {
      byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
