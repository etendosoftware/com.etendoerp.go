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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.exception.OBException;

/**
 * Unit tests for {@link ApiGatewayEmailProviderAdapter}.
 */
public class ApiGatewayEmailProviderAdapterTest {

  @Test
  public void sendsResolvedProviderPayloadWithServerSideConfig() throws Exception {
    CapturingTransport transport = new CapturingTransport(new EmailProviderResponse(202, "{}"));
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        true, 1200);
    ApiGatewayEmailProviderAdapter adapter = new ApiGatewayEmailProviderAdapter(config, transport);

    JSONObject data = new JSONObject();
    data.put("name", "Lucas");
    EmailProviderResponse response = adapter.send(new EmailProviderRequest("user@example.com",
        "reset-password", data, null));

    JSONObject payload = new JSONObject(transport.body);
    assertEquals(202, response.getStatusCode());
    assertEquals("https://provider.example/send", transport.endpoint);
    assertEquals("secret", transport.apiKey);
    assertEquals(1200, transport.timeoutMs);
    assertEquals("user@example.com", payload.getString("to"));
    assertEquals("reset-password", payload.getString("template"));
    assertEquals("Lucas", payload.getJSONObject("data").getString("name"));
    assertFalse(payload.has("from"));
    assertFalse(payload.has("sender"));
  }

  @Test
  public void advertisesMultiRecipientAndCcCapabilityForSes() {
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        true, 1200);
    ApiGatewayEmailProviderAdapter adapter = new ApiGatewayEmailProviderAdapter(config,
        new CapturingTransport(new EmailProviderResponse(202, "{}")));

    assertTrue(adapter.supportsMultipleRecipients());
    assertTrue(adapter.supportsCcChannel());
  }

  @Test
  public void sendsMultiRecipientPayloadAsArraysWithCc() throws Exception {
    CapturingTransport transport = new CapturingTransport(new EmailProviderResponse(202, "{}"));
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        true, 1200);
    ApiGatewayEmailProviderAdapter adapter = new ApiGatewayEmailProviderAdapter(config, transport);

    EmailRecipientSet recipients = EmailRecipientSet.of(
        Arrays.asList("primary@example.com", "second@example.com"),
        Arrays.asList("cc@example.com"));
    adapter.send(new EmailProviderRequest(recipients, "sales-invoice-send", new JSONObject(), null));

    JSONObject payload = new JSONObject(transport.body);
    assertEquals(2, payload.getJSONArray("to").length());
    assertEquals("primary@example.com", payload.getJSONArray("to").getString(0));
    assertEquals("second@example.com", payload.getJSONArray("to").getString(1));
    assertEquals(1, payload.getJSONArray("cc").length());
    assertEquals("cc@example.com", payload.getJSONArray("cc").getString(0));
  }

  @Test
  public void rejectsSendWhenProviderConfigIsIncomplete() throws Exception {
    CapturingTransport transport = new CapturingTransport(new EmailProviderResponse(202, "{}"));
    EmailProviderConfig config = new EmailProviderConfig(null, null, true, 1200);
    ApiGatewayEmailProviderAdapter adapter = new ApiGatewayEmailProviderAdapter(config, transport);

    try {
      adapter.send(new EmailProviderRequest("user@example.com", "reset-password",
          new JSONObject(), null));
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("not properly configured"));
      assertEquals(null, transport.endpoint);
      return;
    }
    throw new AssertionError("Expected incomplete provider configuration to fail");
  }

  @Test
  public void httpTransportPostsJsonPayloadAndReadsSuccessResponse() throws Exception {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> apiKey = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = startServer(exchange -> {
      method.set(exchange.getRequestMethod());
      apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(202, response.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(response);
      }
    });

    try {
      EmailProviderResponse response = new ApiGatewayEmailProviderAdapter.HttpUrlConnectionEmailTransport()
          .post(endpoint(server, "/send"), "server-secret", "{\"to\":\"user@example.com\"}", 1000);

      assertEquals(202, response.getStatusCode());
      assertEquals("{\"accepted\":true}", response.getBody());
      assertEquals("POST", method.get());
      assertEquals("server-secret", apiKey.get());
      assertEquals("{\"to\":\"user@example.com\"}", requestBody.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void httpTransportReadsErrorResponseBody() throws Exception {
    HttpServer server = startServer(exchange -> {
      byte[] response = "provider down".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(503, response.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(response);
      }
    });

    try {
      EmailProviderResponse response = new ApiGatewayEmailProviderAdapter.HttpUrlConnectionEmailTransport()
          .post(endpoint(server, "/send"), "server-secret", "{}", 1000);

      assertEquals(503, response.getStatusCode());
      assertEquals("provider down", response.getBody());
      assertFalse(response.isSuccessful());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void httpTransportDefaultsMissingResponseBodyToEmptyString() throws Exception {
    HttpServer server = startServer(exchange -> exchange.sendResponseHeaders(500, -1));

    try {
      EmailProviderResponse response = new ApiGatewayEmailProviderAdapter.HttpUrlConnectionEmailTransport()
          .post(endpoint(server, "/send"), "server-secret", "{}", 1000);

      assertEquals(500, response.getStatusCode());
      assertEquals("", response.getBody());
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer startServer(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/send", handler::handle);
    server.start();
    return server;
  }

  private static String endpoint(HttpServer server, String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static class CapturingTransport implements ApiGatewayEmailProviderAdapter.EmailTransport {
    private final EmailProviderResponse response;
    private String endpoint;
    private String apiKey;
    private String body;
    private int timeoutMs;

    CapturingTransport(EmailProviderResponse response) {
      this.response = response;
    }

    @Override
    public EmailProviderResponse post(String endpoint, String apiKey, String body, int timeoutMs)
        throws IOException {
      this.endpoint = endpoint;
      this.apiKey = apiKey;
      this.body = body;
      this.timeoutMs = timeoutMs;
      return response;
    }
  }

  @FunctionalInterface
  private interface HttpHandler {
    void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
  }
}
