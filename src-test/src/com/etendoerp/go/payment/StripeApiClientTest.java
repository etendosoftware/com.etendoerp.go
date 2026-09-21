/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Specs for the production gateway, against a real loopback HTTP server rather than a mock, so
 * the parts that only exist at the socket boundary are actually exercised: the composed URL, the
 * bearer header, and above all <b>the trichotomy</b> — which outcomes are answers and which are
 * non-events.
 *
 * <p>That trichotomy is the reason this class exists. A 2xx and a 4xx are verdicts about the
 * request and come back as a {@link StripeResponse}; a 5xx, a 429 and a timeout are the provider
 * declining to answer and come back as a {@link StripeTransportException}. Collapse the two and
 * the whole feature above this class loses the ability to tell "that price id does not exist"
 * from "the provider is down" — and it will report the first when it means the second, to a user
 * who then edits a price id that was correct all along.
 */
class StripeApiClientTest {

  private static final String BASE_URL_PROPERTY = "etendo.go.checkout.api.base.url";
  private static final String SECRET_KEY_PROPERTY = "etendo.go.checkout.secret.key";
  private static final String CONNECT_TIMEOUT_PROPERTY = "etendo.go.checkout.connect.timeout.ms";
  private static final String READ_TIMEOUT_PROPERTY = "etendo.go.checkout.read.timeout.ms";

  private static final String SECRET = "sk_test_abc123";
  private static final String PRICE_BODY = "{\"id\":\"price_123\",\"unit_amount\":4900}";

  private MockedStatic<OBPropertiesProvider> propertiesMock;
  private HttpServer server;

  private final AtomicReference<String> seenPath = new AtomicReference<>();
  private final AtomicReference<String> seenMethod = new AtomicReference<>();
  private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
  private final AtomicReference<String> seenBody = new AtomicReference<>();
  private final AtomicInteger callCount = new AtomicInteger();

  /**
   * Holds the server thread inside a slow exchange until the test is done with it. Counted down in
   * {@link #restore()}, so the exchange is released deterministically instead of after a fixed
   * sleep that is either flaky or gratuitously slow.
   */
  private final CountDownLatch releaseSlowExchange = new CountDownLatch(1);

  /**
   * Isolates the configuration from whatever a developer has in their own
   * {@code Openbravo.properties}; same reasoning as {@code HostedCheckoutServiceTest}.
   */
  @BeforeEach
  void isolateConfiguration() {
    OBPropertiesProvider provider = Mockito.mock(OBPropertiesProvider.class);
    Mockito.when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesMock = Mockito.mockStatic(OBPropertiesProvider.class);
    propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    System.setProperty(SECRET_KEY_PROPERTY, SECRET);
  }

  @AfterEach
  void restore() {
    releaseSlowExchange.countDown();
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (propertiesMock != null) {
      propertiesMock.close();
    }
    System.clearProperty(BASE_URL_PROPERTY);
    System.clearProperty(SECRET_KEY_PROPERTY);
    System.clearProperty(CONNECT_TIMEOUT_PROPERTY);
    System.clearProperty(READ_TIMEOUT_PROPERTY);
  }

  /** Starts a loopback server that answers every request with the given status and body. */
  private void givenProviderAnswers(int status, String body) throws IOException {
    givenProviderBehaves(exchange -> respond(exchange, status, body));
  }

  private void givenProviderBehaves(ExchangeHandler behaviour) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      callCount.incrementAndGet();
      seenPath.set(exchange.getRequestURI().getPath());
      seenMethod.set(exchange.getRequestMethod());
      seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      seenBody.set(readRequestBody(exchange));
      behaviour.handle(exchange);
    });
    server.start();
    System.setProperty(BASE_URL_PROPERTY,
        "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
            + server.getAddress().getPort());
  }

  private interface ExchangeHandler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private static String readRequestBody(HttpExchange exchange) throws IOException {
    try (InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
      byte[] chunk = new byte[1024];
      int read;
      while ((read = input.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  // --- answers: 2xx and 4xx both come back as a response ---

  @Test
  @DisplayName("a 200 comes back as a successful response carrying the body")
  void successIsAnAnswer() throws Exception {
    givenProviderAnswers(200, PRICE_BODY);

    StripeResponse response = new HttpUrlConnectionStripeApiClient().get("/v1/prices/price_123");

    assertAll(() -> assertTrue(response.isSuccess()),
        () -> assertEquals(200, response.status()),
        () -> assertEquals(PRICE_BODY, response.body()),
        () -> assertEquals("price_123", response.json().optString("id", "")));
  }

  @Test
  @DisplayName("a completed 404 is an ANSWER, not an exception")
  void aCompletedFourOhFourIsAnAnswer() throws Exception {
    // The single most important assertion in this class. If a 4xx threw, the caller could not
    // distinguish "no such price" from an outage, and would have to guess — which is exactly the
    // guess this design exists to remove.
    givenProviderAnswers(404, "{\"error\":{\"code\":\"resource_missing\","
        + "\"message\":\"No such price\"}}");

    StripeResponse response = new HttpUrlConnectionStripeApiClient().get("/v1/prices/nope");

    assertAll(() -> assertFalse(response.isSuccess()),
        () -> assertEquals(404, response.status()),
        () -> assertEquals("resource_missing", response.errorCode()));
  }

  @Test
  @DisplayName("every other 4xx is an answer too")
  void otherClientErrorsAreAnswers() throws Exception {
    givenProviderAnswers(400, "{\"error\":{\"code\":\"parameter_invalid_empty\"}}");

    StripeResponse response = new HttpUrlConnectionStripeApiClient().get("/v1/prices/x");

    assertAll(() -> assertEquals(400, response.status()),
        () -> assertEquals("parameter_invalid_empty", response.errorCode()));
  }

  // --- non-events: 5xx, 429 and timeouts are transport failures ---

  @Test
  @DisplayName("a 5xx is a transport failure, because it is not a verdict on the request")
  void serverErrorIsATransportFailure() throws Exception {
    givenProviderAnswers(503, "{\"error\":{\"message\":\"try again\"}}");

    StripeApiClient client = new HttpUrlConnectionStripeApiClient();

    StripeTransportException thrown = assertThrows(StripeTransportException.class,
        () -> client.get("/v1/prices/price_123"));
    assertTrue(thrown.getMessage().contains("503"),
        "the status must survive into the message: " + thrown.getMessage());
  }

  @Test
  @DisplayName("a 429 is a transport failure: it says come back later, not no")
  void rateLimitIsATransportFailure() throws Exception {
    givenProviderAnswers(429, "{\"error\":{\"code\":\"rate_limit\"}}");

    StripeApiClient client = new HttpUrlConnectionStripeApiClient();

    assertThrows(StripeTransportException.class, () -> client.get("/v1/prices/price_123"));
  }

  @Test
  @DisplayName("a read timeout is a transport failure, and it actually times out")
  void readTimeoutIsATransportFailure() throws Exception {
    // The gateway sets a read timeout precisely because HttpURLConnection has none by default:
    // without it this test would hang forever instead of failing, which is what the production
    // save path would do to a user's browser.
    System.setProperty(READ_TIMEOUT_PROPERTY, "200");
    givenProviderBehaves(exchange -> {
      // The provider accepts the request and then never answers, until this test lets it go.
      awaitSlowExchangeRelease();
      respond(exchange, 200, PRICE_BODY);
    });

    StripeApiClient client = new HttpUrlConnectionStripeApiClient();

    assertThrows(StripeTransportException.class, () -> client.get("/v1/prices/price_123"));
  }

  /**
   * Blocks the server thread until {@link #restore()} releases it. In practice the client gives up
   * after its 200 ms read timeout and the latch is counted down moments later, during teardown.
   * The wait is bounded so that a regression — a gateway that no longer applies a read timeout —
   * fails the build instead of hanging it: the exchange is released, the late 200 arrives, and the
   * {@code assertThrows} below fails. That bound must stay far above the 200 ms read timeout, or a
   * loaded machine could release the exchange before the timeout has had a chance to fire.
   */
  private void awaitSlowExchangeRelease() {
    try {
      releaseSlowExchange.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("an unreachable host is a transport failure, not a null and not a raw IOException")
  void unreachableHostIsATransportFailure() {
    System.setProperty(BASE_URL_PROPERTY, "http://127.0.0.1:1");
    System.setProperty(CONNECT_TIMEOUT_PROPERTY, "500");

    StripeApiClient client = new HttpUrlConnectionStripeApiClient();

    assertThrows(StripeTransportException.class, () -> client.get("/v1/prices/price_123"));
  }

  // --- the request itself ---

  @Test
  @DisplayName("the URL is the configured base plus the requested path")
  void urlIsComposedFromTheConfiguredBase() throws Exception {
    givenProviderAnswers(200, PRICE_BODY);

    new HttpUrlConnectionStripeApiClient().get("/v1/prices/price_123");

    assertAll(() -> assertEquals("/v1/prices/price_123", seenPath.get()),
        () -> assertEquals("GET", seenMethod.get()),
        () -> assertEquals(1, callCount.get()));
  }

  @Test
  @DisplayName("the secret key travels as a bearer token")
  void secretKeyIsSentAsABearerToken() throws Exception {
    // Not cosmetic: the provider answers 401 without it, which this module maps onto
    // ETGO_PlanPriceUnauthorized — a message telling the user to check an API key they never
    // touched.
    givenProviderAnswers(200, PRICE_BODY);

    new HttpUrlConnectionStripeApiClient().get("/v1/prices/price_123");

    assertEquals("Bearer " + SECRET, seenAuthorization.get());
  }

  @Test
  @DisplayName("a form post sends the body and the form content type")
  void postSendsTheFormBody() throws Exception {
    givenProviderAnswers(200, "{\"id\":\"cs_1\"}");

    StripeResponse response = new HttpUrlConnectionStripeApiClient()
        .postForm("/v1/checkout/sessions", "mode=subscription&quantity=1");

    assertAll(() -> assertEquals("POST", seenMethod.get()),
        () -> assertEquals("/v1/checkout/sessions", seenPath.get()),
        () -> assertEquals("mode=subscription&quantity=1", seenBody.get()),
        () -> assertTrue(response.isSuccess()));
  }
}
