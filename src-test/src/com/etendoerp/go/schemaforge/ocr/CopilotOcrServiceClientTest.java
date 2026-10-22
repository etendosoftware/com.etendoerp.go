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
package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The client of the extraction service, against a stub server on the loopback interface — never the
 * real service, which is a paid external model. What is asserted is the wire contract (the two calls the
 * application's own panel makes, with the same body), the time budget (the copilot module's client has
 * none), and that every way the service can fail is the same {@link OcrUnavailableException}.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.CopilotOcrServiceClient
 * @covers com.etendoerp.go.schemaforge.ocr.OcrServiceClient
 * @covers com.etendoerp.go.schemaforge.ocr.OcrUnavailableException
 */
class CopilotOcrServiceClientTest {

  @TempDir
  Path dir;

  private HttpServer server;
  private final AtomicReference<String> uploadBody = new AtomicReference<>();
  private final AtomicReference<String> uploadContentType = new AtomicReference<>();
  private final AtomicReference<String> toolBody = new AtomicReference<>();
  private volatile int uploadStatus = 200;
  private volatile String uploadAnswer = "{\"answer\":\"/tmp/copilot/factura.pdf\"}";
  private volatile int toolStatus = 200;
  private volatile String toolAnswer = "{\"answer\":\"{\\\"vendor_name\\\":\\\"ACME\\\"}\"}";
  private volatile long toolDelayMillis;
  private OcrDocType type;
  private Path pdf;

  @BeforeEach
  void setUp() throws IOException, JSONException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/attachFile", exchange -> {
      uploadBody.set(new String(exchange.getRequestBody().readAllBytes(),
          StandardCharsets.ISO_8859_1));
      uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
      reply(exchange, uploadStatus, uploadAnswer);
    });
    server.createContext("/executeTool", exchange -> {
      toolBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      sleep(toolDelayMillis);
      reply(exchange, toolStatus, toolAnswer);
    });
    server.start();
    type = new OcrDocType("purchase-invoice", "SimpleOcrTool", "purchase-invoice",
        "Extract all invoice fields", new JSONObject().put("type", "object"), List.of("vendor_name"));
    pdf = dir.resolve("factura.pdf");
    Files.writeString(pdf, "%PDF-1.4 content");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private CopilotOcrServiceClient client(Duration budget) {
    return new CopilotOcrServiceClient("http://127.0.0.1:" + server.getAddress().getPort(), budget);
  }

  @Test
  @DisplayName("uploads the PDF as multipart under the field 'file', then runs the tool on the returned path")
  void uploadsThenRunsTheTool() throws Exception {
    JSONObject answer = client(Duration.ofSeconds(10)).extract(pdf.toFile(), type);

    assertTrue(uploadContentType.get().startsWith("multipart/form-data;boundary="));
    assertTrue(uploadBody.get().contains("name=\"file\"; filename=\"factura.pdf\""),
        uploadBody.get());
    assertTrue(uploadBody.get().contains("%PDF-1.4 content"));
    JSONObject call = new JSONObject(toolBody.get());
    assertEquals("SimpleOcrTool", call.getString("tool_name"));
    assertEquals("/tmp/copilot/factura.pdf", call.getJSONObject("params").getString("path"));
    assertEquals("Extract all invoice fields", call.getJSONObject("params").getString("question"));
    assertEquals("object", call.getJSONObject("params").getJSONObject("structured_output_schema")
        .getString("type"));
    assertEquals("{\"vendor_name\":\"ACME\"}", answer.getString("answer"));
  }

  @Test
  @DisplayName("a file name with characters that could break the multipart header is neutralised")
  void oddFileNameIsNeutralised() throws Exception {
    Path odd = dir.resolve("we\"ird\r\nname.pdf");
    Files.writeString(odd, "%PDF-1.4");

    client(Duration.ofSeconds(10)).extract(odd.toFile(), type);

    assertFalse(uploadBody.get().contains("we\"ird"), uploadBody.get());
    assertTrue(uploadBody.get().contains("filename=\"we_ird__name.pdf\""), uploadBody.get());
  }

  @Test
  @DisplayName("the tool's own error answer (HTTP 200) is returned for the caller to judge")
  void toolErrorIsReturned() throws Exception {
    toolAnswer = "{\"error\":\"could not read the file\"}";

    JSONObject answer = client(Duration.ofSeconds(10)).extract(pdf.toFile(), type);

    assertEquals("could not read the file", answer.getString("error"));
  }

  @Test
  @DisplayName("a service that does not answer within the budget is unavailable, not waited for")
  void timesOut() {
    toolDelayMillis = 3000;
    long started = System.nanoTime();

    OcrUnavailableException e = assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofMillis(700)).extract(pdf.toFile(), type));

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertTrue(elapsedMillis < 2500, "gave up after " + elapsedMillis + " ms");
    assertEquals("The extraction service did not answer.", e.getMessage());
  }

  @Test
  @DisplayName("an upload that answers with a failure status is unavailable and the tool is never run")
  void uploadFailure() {
    uploadStatus = 500;

    assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofSeconds(10)).extract(pdf.toFile(), type));
    assertEquals(null, toolBody.get());
  }

  @Test
  @DisplayName("an upload answer without a path is unavailable")
  void uploadWithoutPath() {
    uploadAnswer = "{\"answer\":\"\"}";

    assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofSeconds(10)).extract(pdf.toFile(), type));
    assertEquals(null, toolBody.get());
  }

  @Test
  @DisplayName("a tool call that answers 500 or text that is not JSON is unavailable")
  void toolFailures() {
    toolStatus = 500;
    assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofSeconds(10)).extract(pdf.toFile(), type));

    toolStatus = 200;
    toolAnswer = "<html>bad gateway</html>";
    assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofSeconds(10)).extract(pdf.toFile(), type));
  }

  @Test
  @DisplayName("a service that is down is unavailable, and the message names no address")
  void serviceDown() throws IOException {
    int port = server.getAddress().getPort();
    server.stop(0);

    OcrUnavailableException e = assertThrows(OcrUnavailableException.class,
        () -> new CopilotOcrServiceClient("http://127.0.0.1:" + port, Duration.ofSeconds(5))
            .extract(pdf.toFile(), type));

    assertFalse(e.getMessage().contains("127.0.0.1"));
    assertFalse(e.getMessage().contains(String.valueOf(port)));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  @Test
  @DisplayName("a missing PDF is unavailable, not an unchecked failure")
  void missingFile() {
    assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofSeconds(5)).extract(dir.resolve("gone.pdf").toFile(), type));
  }

  @Test
  @DisplayName("the service address comes from copilot.host and copilot.port, legacy spelling included")
  void baseUrlFromProperties() {
    assertEquals("http://localhost:5005", CopilotOcrServiceClient.baseUrlOf(new Properties()));
    Properties current = new Properties();
    current.setProperty("copilot.host", "copilot.internal");
    current.setProperty("copilot.port", "6000");
    assertEquals("http://copilot.internal:6000", CopilotOcrServiceClient.baseUrlOf(current));
    Properties legacy = new Properties();
    legacy.setProperty("COPILOT_HOST", "legacy-host");
    legacy.setProperty("COPILOT_PORT", "7000");
    assertEquals("http://legacy-host:7000", CopilotOcrServiceClient.baseUrlOf(legacy));
    Properties both = new Properties();
    both.setProperty("copilot.port", "6000");
    both.setProperty("COPILOT_PORT", "7000");
    assertEquals("http://localhost:6000", CopilotOcrServiceClient.baseUrlOf(both));
  }

  @Test
  @DisplayName("an answer larger than the cap is unavailable, not read into memory")
  void oversizedAnswerIsUnavailable() {
    toolAnswer = "{\"answer\":\"" + "x".repeat(CopilotOcrServiceClient.MAX_RESPONSE_BYTES + 10) + "\"}";

    assertThrows(OcrUnavailableException.class,
        () -> client(Duration.ofSeconds(10)).extract(pdf.toFile(), type));
  }

  @Test
  @DisplayName("an answer just under the cap is read")
  void answerUnderTheCapIsRead() throws Exception {
    toolAnswer = "{\"answer\":\"" + "x".repeat(CopilotOcrServiceClient.MAX_RESPONSE_BYTES - 100) + "\"}";

    assertTrue(client(Duration.ofSeconds(10)).extract(pdf.toFile(), type).has("answer"));
  }
}
