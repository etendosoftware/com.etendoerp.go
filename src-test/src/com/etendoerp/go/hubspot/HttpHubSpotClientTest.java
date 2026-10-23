/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.hubspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.hubspot.HubSpotClient.HubSpotUnreachableException;
import com.etendoerp.go.hubspot.HubSpotClient.Response;
import com.sun.net.httpserver.HttpServer;

/**
 * The HTTP call to HubSpot, against a local server: method, path, credentials, body, and what
 * becomes of an answer that is not a success or of no answer at all.
 *
 * @covers com.etendoerp.go.hubspot.HttpHubSpotClient
 * @covers com.etendoerp.go.hubspot.HubSpotClient
 */
class HttpHubSpotClientTest {

  private HttpServer server;
  private final AtomicReference<String> seen = new AtomicReference<>();
  private int status = 200;
  private String answer = "{\"id\":\"42\"}";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " | "
          + exchange.getRequestHeaders().getFirst("Authorization") + " | "
          + exchange.getRequestHeaders().getFirst("Content-Type") + " | " + body);
      byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private HttpHubSpotClient client() {
    return new HttpHubSpotClient(new HubSpotConfiguration("pat-test",
        "http://127.0.0.1:" + server.getAddress().getPort(), "p", "t", "a", "w", "l"));
  }

  @Test
  void anUpdateIsAPatchWithTheTokenAndTheJsonBody() throws Exception {
    Response response = client().send("PATCH", "/crm/v3/objects/deals/7",
        new JSONObject().put("properties", new JSONObject().put("dealstage", "won")));

    assertTrue(response.isSuccess());
    assertEquals("{\"id\":\"42\"}", response.body());
    assertEquals("PATCH /crm/v3/objects/deals/7 | Bearer pat-test | application/json | "
        + "{\"properties\":{\"dealstage\":\"won\"}}", seen.get());
  }

  @Test
  void aCallWithoutBodySendsNone() throws Exception {
    client().send("GET", "/crm/v3/pipelines/deals?archived=false", null);

    assertTrue(seen.get().startsWith("GET /crm/v3/pipelines/deals?archived=false | Bearer"));
    assertTrue(seen.get().endsWith("| "));
  }

  @Test
  void aRefusalIsAnAnswerNotAnError() throws Exception {
    status = 400;
    answer = "{\"message\":\"Property values were not valid\"}";

    Response response = client().send("POST", "/crm/v3/objects/contacts/batch/upsert",
        new JSONObject());

    assertFalse(response.isSuccess());
    assertFalse(response.isRetryable());
    assertEquals(400, response.status());
    assertTrue(response.body().contains("not valid"));
  }

  @Test
  void anEmptyAnswerReadsAsAnEmptyBody() throws Exception {
    status = 204;
    answer = "";

    assertEquals("", client().send("PATCH", "/x", new JSONObject()).body());
  }

  @Test
  void whichAnswersMaySucceedLater() {
    assertTrue(new Response(401, "").isRetryable());
    assertTrue(new Response(403, "").isRetryable());
    assertTrue(new Response(429, "").isRetryable());
    assertTrue(new Response(503, "").isRetryable());
    assertFalse(new Response(404, "").isRetryable());
    assertFalse(new Response(409, "").isRetryable());
    assertTrue(new Response(201, "").isSuccess());
  }

  @Test
  void noServerMeansUnreachable() {
    server.stop(0);

    HubSpotUnreachableException e = assertThrows(HubSpotUnreachableException.class,
        () -> client().send("PATCH", "/crm/v3/objects/deals/7", new JSONObject()));
    assertTrue(e.getMessage().contains("PATCH /crm/v3/objects/deals/7 did not complete"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void anInterruptedCallIsUnreachableAndKeepsTheInterrupt() throws Exception {
    HttpClient interrupted = mock(HttpClient.class);
    when(interrupted.send(any(), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("stop"));
    HttpHubSpotClient client = new HttpHubSpotClient(new HubSpotConfiguration("t",
        "http://127.0.0.1:1", "p", "t", "a", "w", "l"), interrupted);

    try {
      HubSpotUnreachableException e = assertThrows(HubSpotUnreachableException.class,
          () -> client.send("GET", "/x", null));
      assertTrue(e.getMessage().contains("was interrupted"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
