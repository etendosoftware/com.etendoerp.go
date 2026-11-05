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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * Unit tests for the backend Mixpanel sink and its runtime configuration: the request body (event,
 * properties, token, and the {@code environment} / {@code $insert_id} every event carries since
 * ETP-5581), the track endpoint, the disabled / tokenless no-op, the swallowed HTTP failures, and
 * where the deployment {@code environment} comes from.
 *
 * @covers com.etendoerp.go.schemaforge.telemetry.MixpanelNeoTelemetrySink
 * @covers com.etendoerp.go.schemaforge.telemetry.MixpanelNeoTelemetryConfig
 * @covers com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvent
 */
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

  /**
   * ETP-5581: every event carries the deployment environment and a random {@code $insert_id},
   * which a caller property cannot replace.
   *
   * @throws Exception when the body cannot be built
   */
  @Test
  void buildRequestBodyAddsEnvironmentAndInsertId() throws Exception {
    MixpanelNeoTelemetrySink sink = new MixpanelNeoTelemetrySink(
        new MixpanelNeoTelemetryConfig(true, "token-123", null, null, 1000, " PROD "));
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("environment", "spoofed");
    NeoTelemetryEvent first = new NeoTelemetryEvent(
        NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED, properties, Instant.now());
    NeoTelemetryEvent second = new NeoTelemetryEvent(
        NeoTelemetryEvents.BACKEND_BANK_BOUNCE_PAIR_DECIDED, properties, Instant.now());

    String decoded = URLDecoder.decode(sink.buildRequestBody(first).substring("data=".length()),
        StandardCharsets.UTF_8.name());

    assertTrue(decoded.contains("\"environment\":\"production\""), decoded);
    assertFalse(decoded.contains("spoofed"), decoded);
    assertTrue(decoded.contains("\"$insert_id\":\"" + first.getInsertId() + "\""), decoded);
    assertEquals(32, first.getInsertId().length());
    assertFalse(first.getInsertId().equals(second.getInsertId()));
  }

  /** The environment is normalized: Datadog aliases folded, blank is {@code unknown}. */
  @Test
  void environmentIsNormalized() {
    assertEquals("production", MixpanelNeoTelemetryConfig.normalizeEnvironment("prod"));
    assertEquals("staging", MixpanelNeoTelemetryConfig.normalizeEnvironment("Stage"));
    assertEquals("experimental", MixpanelNeoTelemetryConfig.normalizeEnvironment("experimental"));
    assertEquals("unknown", MixpanelNeoTelemetryConfig.normalizeEnvironment("  "));
    assertEquals("unknown", new MixpanelNeoTelemetryConfig(true, "t", null, null, 1000)
        .getEnvironment());
  }

  /**
   * ETP-5581: where {@code fromRuntime} takes the deployment environment from, highest first —
   * the explicit override ({@code etendo.go.mixpanel.environment}, then
   * {@code ETGO_MIXPANEL_ENVIRONMENT}), then Datadog's ({@code dd.env}, then {@code DD_ENV}),
   * else {@code unknown} — and that the winner is normalized (trimmed, lower case, {@code prod}/{@code stage} folded). Each source is read
   * through {@link ConfigPropertyReader}, emulated here with its own precedence (a property beats
   * the environment variable of the same pair) so the environment variables can be set.
   *
   * @param overrideProperty {@code etendo.go.mixpanel.environment}, or empty
   * @param overrideEnv      {@code ETGO_MIXPANEL_ENVIRONMENT}, or empty
   * @param ddProperty       {@code dd.env}, or empty
   * @param ddEnv            {@code DD_ENV}, or empty
   * @param expected         the environment every event must carry
   */
  @ParameterizedTest(name = "[{index}] property={0} env={1} dd.env={2} DD_ENV={3} -> {4}")
  @CsvSource({
      "' Stage ', experimental, prod, prod, staging",
      ", EXPERIMENTAL, prod, stage, experimental",
      ", , stage, experimental, staging",
      ", , , PROD, production",
      ", , , ' Experimental ', experimental",
      ", , , , unknown",
      "'  ', '  ', , prod, production" })
  void fromRuntimeTakesTheEnvironmentByPrecedence(String overrideProperty, String overrideEnv,
      String ddProperty, String ddEnv, String expected) {
    Map<String, String> properties = new HashMap<>();
    Map<String, String> environment = new HashMap<>();
    putIfPresent(properties, MixpanelNeoTelemetryConfig.PROP_ENVIRONMENT, overrideProperty);
    putIfPresent(environment, MixpanelNeoTelemetryConfig.ENV_ENVIRONMENT, overrideEnv);
    putIfPresent(properties, MixpanelNeoTelemetryConfig.PROP_DD_ENV, ddProperty);
    putIfPresent(environment, MixpanelNeoTelemetryConfig.ENV_DD_ENV, ddEnv);

    try (MockedStatic<ConfigPropertyReader> reader =
        Mockito.mockStatic(ConfigPropertyReader.class)) {
      reader.when(() -> ConfigPropertyReader.readConfigValue(Mockito.anyString(),
          Mockito.anyString(), Mockito.nullable(String.class))).thenAnswer(inv -> {
            String fromProperty = trimToNull(properties.get(inv.getArgument(0)));
            if (fromProperty != null) {
              return fromProperty;
            }
            String fromEnv = trimToNull(environment.get(inv.getArgument(1)));
            return fromEnv != null ? fromEnv : inv.getArgument(2);
          });

      assertEquals(expected, MixpanelNeoTelemetryConfig.fromRuntime().getEnvironment());
    }
  }

  /**
   * ETP-5581: with the real {@link ConfigPropertyReader}, the override set as a JVM system property
   * wins over whatever the machine's environment variables say, and is normalized.
   */
  @Test
  void fromRuntimeHonoursTheOverrideSystemProperty() {
    String previous = System.getProperty(MixpanelNeoTelemetryConfig.PROP_ENVIRONMENT);
    System.setProperty(MixpanelNeoTelemetryConfig.PROP_ENVIRONMENT, " PROD ");
    try {
      assertEquals("production", MixpanelNeoTelemetryConfig.fromRuntime().getEnvironment());
    } finally {
      if (previous == null) {
        System.clearProperty(MixpanelNeoTelemetryConfig.PROP_ENVIRONMENT);
      } else {
        System.setProperty(MixpanelNeoTelemetryConfig.PROP_ENVIRONMENT, previous);
      }
    }
  }

  /**
   * Puts {@code value} under {@code key} unless it is {@code null} (an empty CSV cell).
   *
   * @param map   the simulated source
   * @param key   the property or variable name
   * @param value the value, or {@code null}
   */
  private static void putIfPresent(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  /**
   * The value trimmed, {@code null} when blank, as {@link ConfigPropertyReader} reads each source.
   *
   * @param value the raw value
   * @return the trimmed value, or {@code null}
   */
  private static String trimToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
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
