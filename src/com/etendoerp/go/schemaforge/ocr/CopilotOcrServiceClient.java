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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.common.BoundedJsonParser;

/**
 * The real {@link OcrServiceClient}: it talks to the copilot service the application's own upload
 * panel reaches through {@code /sws/copilot}, with the same two calls ({@code attachFile} to hand it
 * the PDF, {@code executeTool} to run the extraction tool on it) and the same connection properties
 * ({@code copilot.host}, {@code copilot.port}).
 *
 * <p>It is its own client, not the copilot module's, for one reason: that one waits without a
 * timeout, and an MCP call that waits forever on a paid external model holds a server thread. Here the
 * whole exchange has one time budget ({@value #BUDGET_SECONDS} seconds by default); running past it is
 * {@link OcrUnavailableException}, the same as the service being down.</p>
 */
public final class CopilotOcrServiceClient implements OcrServiceClient {

  /** Time the whole extraction may take, upload and tool run together. */
  public static final long BUDGET_SECONDS = 90;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final String PROP_HOST = "copilot.host";
  private static final String PROP_PORT = "copilot.port";
  private static final String DEFAULT_HOST = "localhost";
  private static final String DEFAULT_PORT = "5005";
  /** Largest answer read from the service: a reading of an invoice is a few KB, never megabytes. */
  static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 299;
  private static final String KEY_ANSWER = "answer";

  private final String baseUrl;
  private final Duration budget;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
      .version(HttpClient.Version.HTTP_1_1).build();

  /** The service named by the instance's {@code copilot.host} / {@code copilot.port}. */
  public CopilotOcrServiceClient() {
    this(baseUrlOf(OBPropertiesProvider.getInstance().getOpenbravoProperties()),
        Duration.ofSeconds(BUDGET_SECONDS));
  }

  /**
   * A client for an explicit service and time budget; for tests.
   *
   * @param baseUrl the service's base URL, without a trailing slash
   * @param budget  the time the whole exchange may take
   */
  CopilotOcrServiceClient(String baseUrl, Duration budget) {
    this.baseUrl = baseUrl;
    this.budget = budget;
  }

  static String baseUrlOf(Properties properties) {
    return "http://" + property(properties, PROP_HOST, DEFAULT_HOST) + ":"
        + property(properties, PROP_PORT, DEFAULT_PORT);
  }

  /** The property, or its legacy upper-case spelling ({@code COPILOT_PORT}), or the default. */
  private static String property(Properties properties, String name, String fallback) {
    if (properties.containsKey(name)) {
      return properties.getProperty(name);
    }
    String legacy = name.toUpperCase(java.util.Locale.ROOT).replace('.', '_');
    return properties.containsKey(legacy) ? properties.getProperty(legacy) : fallback;
  }

  @Override
  public JSONObject extract(File pdf, OcrDocType type) throws OcrUnavailableException {
    long deadline = System.nanoTime() + budget.toNanos();
    try {
      String path = upload(pdf, remaining(deadline));
      return runTool(path, type, remaining(deadline));
    } catch (IOException | JSONException | RuntimeException e) {
      throw new OcrUnavailableException("The extraction service did not answer.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OcrUnavailableException("The extraction was interrupted.", e);
    }
  }

  private static Duration remaining(long deadline) throws IOException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new IOException("The extraction time budget is spent.");
    }
    return Duration.ofNanos(nanos);
  }

  /** Hands the PDF to the service; the answer is the path it keeps it under. */
  private String upload(File pdf, Duration timeout) throws IOException, InterruptedException,
      JSONException {
    String boundary = "etgo" + UUID.randomUUID().toString().replace("-", "");
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/attachFile"))
        .timeout(timeout).header("Content-Type", "multipart/form-data;boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(pdf, boundary))).build();
    JSONObject body = send(request);
    String path = body.optString(KEY_ANSWER, "");
    if (StringUtils.isBlank(path)) {
      throw new IOException("The service did not return a path for the document.");
    }
    return path;
  }

  private static byte[] multipart(File pdf, String boundary) throws IOException {
    String name = pdf.getName().replaceAll("[^\\p{L}\\p{N} ._()\\-]", "_");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
        + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        .getBytes(StandardCharsets.UTF_8));
    out.write(Files.readAllBytes(pdf.toPath()));
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private JSONObject runTool(String path, OcrDocType type, Duration timeout)
      throws IOException, InterruptedException, JSONException {
    JSONObject params = new JSONObject().put("path", path).put("question", type.getQuestion())
        .put("structured_output_schema", type.getSchema());
    JSONObject payload = new JSONObject().put("tool_name", type.getToolName()).put("params", params);
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/executeTool"))
        .timeout(timeout).header("Content-Type", "application/json;charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
        .build();
    return send(request);
  }

  private JSONObject send(HttpRequest request) throws IOException, InterruptedException,
      JSONException {
    HttpResponse<InputStream> response = http.send(request,
        HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() > HTTP_OK_MAX) {
        throw new IOException("The service answered with status " + response.statusCode());
      }
      byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_RESPONSE_BYTES) {
        throw new IOException("The service answer is larger than " + MAX_RESPONSE_BYTES + " bytes");
      }
      return BoundedJsonParser.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }
  }
}
