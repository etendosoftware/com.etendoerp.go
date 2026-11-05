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

package com.etendoerp.go.schemaforge.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mixpanel HTTP sink for backend telemetry.
 */
final class MixpanelNeoTelemetrySink implements NeoTelemetrySink {

  private static final Logger log = LogManager.getLogger(MixpanelNeoTelemetrySink.class);
  /** Deployment environment, added to every event by this sink (ETP-5581). */
  static final String PROP_ENVIRONMENT = "environment";
  /** Mixpanel's de-duplication key, added to every event by this sink (ETP-5581). */
  static final String PROP_INSERT_ID = "$insert_id";

  private final MixpanelNeoTelemetryConfig config;
  private final ConnectionFactory connectionFactory;

  /**
   * A sink that opens real HTTP connections.
   *
   * @param config the Mixpanel configuration
   */
  MixpanelNeoTelemetrySink(MixpanelNeoTelemetryConfig config) {
    this(config, url -> (HttpURLConnection) url.openConnection());
  }

  /**
   * A sink with a custom connection factory (tests).
   *
   * @param config            the Mixpanel configuration
   * @param connectionFactory opens the connection to the track endpoint
   */
  MixpanelNeoTelemetrySink(
      MixpanelNeoTelemetryConfig config, ConnectionFactory connectionFactory) {
    this.config = config;
    this.connectionFactory = connectionFactory;
  }

  /**
   * Submits the event to Mixpanel when configured; a failure is thrown for the service to log.
   *
   * @param event the sanitized event
   */
  @Override
  public void emit(NeoTelemetryEvent event) {
    if (event == null || config == null || !config.isConfigured()) {
      return;
    }
    try {
      send(event);
    } catch (Exception e) {
      throw new IllegalStateException("Could not submit backend telemetry to Mixpanel", e);
    }
  }

  /**
   * POSTs the event to the track endpoint and checks the answer.
   *
   * @param event the event
   * @throws Exception when the request fails or Mixpanel answers an error
   */
  private void send(NeoTelemetryEvent event) throws Exception {
    byte[] body = buildRequestBody(event).getBytes(StandardCharsets.UTF_8);
    HttpURLConnection connection = connectionFactory.open(new URL(trackUrl()));
    connection.setRequestMethod("POST");
    connection.setConnectTimeout(config.getTimeoutMs());
    connection.setReadTimeout(config.getTimeoutMs());
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try (OutputStream outputStream = connection.getOutputStream()) {
      outputStream.write(body);
    }
    int status = connection.getResponseCode();
    String responseBody = readResponseBody(connection, status);
    if (status < 200 || status >= 300) {
      throw new IOException("Mixpanel returned HTTP " + status + " for event "
          + event.getName() + " with response " + responseBody);
    }
    log.info("Backend telemetry event submitted to Mixpanel event={} status={} response={}",
        event.getName(), status, responseBody);
  }

  /**
   * The form body of the track request: the event, its properties, and on every event the
   * deployment {@code environment} and the emission's {@code $insert_id} (ETP-5581).
   *
   * @param event the event
   * @return the URL-encoded {@code data=} body
   * @throws Exception when the JSON cannot be built
   */
  String buildRequestBody(NeoTelemetryEvent event) throws Exception {
    JSONObject properties = new JSONObject();
    properties.put("token", config.getToken());
    properties.put("distinct_id", config.getDistinctId());
    properties.put("time", event.getTimestamp().getEpochSecond());
    for (Map.Entry<String, Object> entry : event.getProperties().entrySet()) {
      properties.put(entry.getKey(), entry.getValue());
    }
    // ETP-5581: on EVERY backend event, after the caller's properties so none can replace them
    // (neither key is on the allowlist anyway). environment separates production from the test
    // deployments; $insert_id lets Mixpanel drop a duplicate submission of the same emission.
    properties.put(PROP_ENVIRONMENT, config.getEnvironment());
    properties.put(PROP_INSERT_ID, event.getInsertId());

    JSONObject payload = new JSONObject();
    payload.put("event", event.getName());
    payload.put("properties", properties);
    return "data=" + URLEncoder.encode("[" + payload + "]", StandardCharsets.UTF_8.name());
  }

  /**
   * The track endpoint, without IP geolocation.
   *
   * @return the URL
   */
  private String trackUrl() {
    return config.getApiHost() + "/track/?verbose=1&ip=0";
  }

  /**
   * The answer's body for the log, never throwing.
   *
   * @param connection the connection
   * @param status     the HTTP status
   * @return the body, or a marker when it cannot be read
   */
  private static String readResponseBody(HttpURLConnection connection, int status) {
    InputStream stream = null;
    try {
      stream = status >= 200 && status < 300
          ? connection.getInputStream()
          : connection.getErrorStream();
      if (stream == null) {
        return "";
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "unavailable:" + e.getClass().getSimpleName();
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException ignored) {
          // Ignore close failures while logging telemetry diagnostics.
        }
      }
    }
  }

  @FunctionalInterface
  interface ConnectionFactory {
    /**
     * Opens an HTTP connection to the Mixpanel track endpoint.
     *
     * @param url Mixpanel track endpoint URL
     * @return opened HTTP connection
     * @throws IOException when the connection cannot be opened
     */
    HttpURLConnection open(URL url) throws IOException;
  }
}
