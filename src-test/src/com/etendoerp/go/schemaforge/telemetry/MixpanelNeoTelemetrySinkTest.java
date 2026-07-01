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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MixpanelNeoTelemetrySinkTest {

  @Test
  void buildRequestBodyContainsEventPropertiesAndToken() throws Exception {
    MixpanelNeoTelemetrySink sink = new MixpanelNeoTelemetrySink(
        new MixpanelNeoTelemetryConfig(true, "token-123", "https://api-eu.mixpanel.com",
            "backend-node", 1000));
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("source", "neo");
    properties.put("specName", "sales-invoice");
    properties.put("count", 2);
    NeoTelemetryEvent event = new NeoTelemetryEvent(
        NeoTelemetryEvents.BACKEND_BANK_MATCH_ATTEMPTED, properties,
        Instant.parse("2026-06-25T12:00:00Z"));

    String body = sink.buildRequestBody(event);
    String decoded = URLDecoder.decode(body.substring("data=".length()),
        StandardCharsets.UTF_8.name());

    assertTrue(decoded.contains("\"event\":\"backend_bank_match_attempted\""));
    assertTrue(decoded.contains("\"token\":\"token-123\""));
    assertTrue(decoded.contains("\"distinct_id\":\"backend-node\""));
    assertTrue(decoded.contains("\"source\":\"neo\""));
    assertTrue(decoded.contains("\"specName\":\"sales-invoice\""));
    assertTrue(decoded.contains("\"count\":2"));
    assertTrue(decoded.contains("\"time\":1782388800"));
    assertFalse(decoded.contains("recordId"));
  }

  @Test
  void emitPostsToMixpanelTrackEndpoint() {
    FakeConnection connection = new FakeConnection(200);
    MixpanelNeoTelemetrySink sink = new MixpanelNeoTelemetrySink(
        new MixpanelNeoTelemetryConfig(true, "token-123", "https://api-eu.mixpanel.com/",
            "neo-backend", 1000), url -> {
          connection.url = url;
          return connection;
        });

    sink.emit(new NeoTelemetryEvent(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED,
        mapOf("status", "success"), Instant.parse("2026-06-25T12:00:00Z")));

    assertEquals("https://api-eu.mixpanel.com/track/?verbose=1&ip=0",
        connection.url.toString());
    assertEquals("POST", connection.method);
    assertEquals("application/x-www-form-urlencoded",
        connection.requestProperties.get("Content-Type"));
    assertTrue(connection.body.toString(StandardCharsets.UTF_8)
        .contains("backend_write_operation_completed"));
  }

  @Test
  void emitSkipsWhenConfigIsDisabledOrTokenMissing() {
    FakeConnection connection = new FakeConnection(200);
    MixpanelNeoTelemetrySink disabled = new MixpanelNeoTelemetrySink(
        new MixpanelNeoTelemetryConfig(false, "token-123", null, null, 1000), url -> connection);
    MixpanelNeoTelemetrySink missingToken = new MixpanelNeoTelemetrySink(
        new MixpanelNeoTelemetryConfig(true, null, null, null, 1000), url -> connection);

    disabled.emit(event());
    missingToken.emit(event());

    assertEquals(0, connection.body.size());
  }

  @Test
  void serviceSwallowsMixpanelHttpFailures() {
    MixpanelNeoTelemetrySink sink = new MixpanelNeoTelemetrySink(
        new MixpanelNeoTelemetryConfig(true, "token-123", null, null, 1000),
        url -> new FakeConnection(500));
    NeoTelemetryService service = new NeoTelemetryService(sink, () -> 0L);

    assertDoesNotThrow(() ->
        service.emit(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED,
            mapOf("status", "success")));
  }

  private static NeoTelemetryEvent event() {
    return new NeoTelemetryEvent(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED,
        mapOf("status", "success"), Instant.parse("2026-06-25T12:00:00Z"));
  }

  private static Map<String, Object> mapOf(String key, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(key, value);
    return map;
  }

  private static final class FakeConnection extends HttpURLConnection {
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final Map<String, String> requestProperties = new LinkedHashMap<>();
    private final int responseCode;
    private String method;
    private URL url;

    private FakeConnection(int responseCode) {
      super(null);
      this.responseCode = responseCode;
    }

    @Override
    public void disconnect() {
      // No-op.
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() {
      // No-op.
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
      this.method = method;
    }

    @Override
    public void setRequestProperty(String key, String value) {
      requestProperties.put(key, value);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      return body;
    }

    @Override
    public int getResponseCode() {
      return responseCode;
    }
  }
}
