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

  private final MixpanelNeoTelemetryConfig config;
  private final ConnectionFactory connectionFactory;

  MixpanelNeoTelemetrySink(MixpanelNeoTelemetryConfig config) {
    this(config, url -> (HttpURLConnection) url.openConnection());
  }

  MixpanelNeoTelemetrySink(
      MixpanelNeoTelemetryConfig config, ConnectionFactory connectionFactory) {
    this.config = config;
    this.connectionFactory = connectionFactory;
  }

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

  String buildRequestBody(NeoTelemetryEvent event) throws Exception {
    JSONObject properties = new JSONObject();
    properties.put("token", config.getToken());
    properties.put("distinct_id", config.getDistinctId());
    properties.put("time", event.getTimestamp().getEpochSecond());
    for (Map.Entry<String, Object> entry : event.getProperties().entrySet()) {
      properties.put(entry.getKey(), entry.getValue());
    }

    JSONObject payload = new JSONObject();
    payload.put("event", event.getName());
    payload.put("properties", properties);
    return "data=" + URLEncoder.encode("[" + payload + "]", StandardCharsets.UTF_8.name());
  }

  private String trackUrl() {
    return config.getApiHost() + "/track/?verbose=1&ip=0";
  }

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
