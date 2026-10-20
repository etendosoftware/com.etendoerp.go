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

package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.mcp.McpRequestEra.Classification;
import com.etendoerp.go.mcp.McpRequestEra.Era;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.mcp.McpRequestEra.Headers;

/**
 * The era decision table of the ETP-5640 design (§2.3), row by row, on the pure classifier.
 *
 * @covers com.etendoerp.go.mcp.McpRequestEra
 * @covers com.etendoerp.go.mcp.McpProtocolVersion
 */
public class McpRequestEraTest {

  private static final String MODERN = McpProtocolVersion.MODERN_LATEST;

  private static JSONObject meta(Object version) throws Exception {
    JSONObject meta = new JSONObject()
        .put(McpRequestEra.META_CLIENT_CAPABILITIES, new JSONObject())
        .put(McpServlet.META_CLIENT_INFO,
            new JSONObject().put("name", "claude-code").put("version", "2.1.0"));
    if (version != null) {
      meta.put(McpRequestEra.META_PROTOCOL_VERSION, version);
    }
    return meta;
  }

  private static JSONObject toolCall(JSONObject meta) throws Exception {
    JSONObject params = new JSONObject().put("name", "etendo_list")
        .put("arguments", new JSONObject());
    return meta != null ? params.put("_meta", meta) : params;
  }

  private static Classification classify(String method, JSONObject params, Headers headers) {
    return McpRequestEra.classify(method, params, headers, false);
  }

  private static Headers full(String method, String name) {
    return new Headers(MODERN, method, name);
  }

  // ── Legacy rows ──────────────────────────────────────────────────────────

  @Test
  public void initializeIsLegacyEvenWithModernMeta() throws Exception {
    Classification c = classify("initialize",
        new JSONObject().put("_meta", meta(MODERN)), full("initialize", null));
    assertEquals(Era.LEGACY, c.era());
    assertNull(c.refusal());
  }

  @Test
  public void requestWithoutModernMarkerIsLegacy() throws Exception {
    assertEquals(Era.LEGACY,
        classify("tools/list", null, new Headers(null, null, null)).era());
    assertEquals(Era.LEGACY,
        classify("tools/list", null, new Headers("2025-11-25", null, null)).era());
    // L3: an unknown header without _meta is a legacy client with a bad header, not a probe.
    assertEquals(Era.LEGACY,
        classify("tools/list", null, new Headers("2027-01-01", null, null)).era());
  }

  @Test
  public void notificationInitializedIsLegacy() {
    assertEquals(Era.LEGACY,
        classify("notifications/initialized", null, new Headers(MODERN, null, null)).era());
  }

  // ── Modern rows ──────────────────────────────────────────────────────────

  @Test
  public void wellFormedModernRequestIsServedWithoutIssues() throws Exception {
    Classification c = classify("tools/call", toolCall(meta(MODERN)),
        full("tools/call", "etendo_list"));
    assertTrue(c.isModern());
    assertEquals(MODERN, c.protocolVersion());
    assertNull(c.refusal());
    assertTrue(c.issues().isEmpty());
  }

  @Test
  public void unsupportedDeclaredVersionIsRefusedWithSupportedList() throws Exception {
    Classification c = classify("tools/list",
        new JSONObject().put("_meta", meta("2027-01-01")),
        new Headers("2027-01-01", "tools/list", null));
    assertTrue(c.isModern());
    assertNotNull(c.refusal());
    assertEquals(400, c.refusal().httpStatus());
    assertEquals(McpRequestEra.UNSUPPORTED_PROTOCOL_VERSION, c.refusal().code());
    assertEquals("2027-01-01", c.refusal().data().getString("requested"));
    assertEquals(MODERN, c.refusal().data().getJSONArray("supported").getString(0));
  }

  @Test
  public void legacyVersionDeclaredPerRequestIsUnsupported() throws Exception {
    Classification c = classify("tools/list",
        new JSONObject().put("_meta", meta("2025-11-25")),
        new Headers("2025-11-25", "tools/list", null));
    assertEquals(McpRequestEra.UNSUPPORTED_PROTOCOL_VERSION, c.refusal().code());
  }

  @Test
  public void protocolHeaderDifferentFromBodyIsHeaderMismatchEvenWhenLenient() throws Exception {
    Classification c = classify("tools/list",
        new JSONObject().put("_meta", meta(MODERN)),
        new Headers("2025-11-25", "tools/list", null));
    assertEquals(McpRequestEra.HEADER_MISMATCH, c.refusal().code());
    assertEquals(400, c.refusal().httpStatus());
  }

  @Test
  public void missingProtocolHeaderIsServedWithIssueWhenLenientAndRefusedWhenStrict()
      throws Exception {
    JSONObject params = new JSONObject().put("_meta", meta(MODERN));
    Headers headers = new Headers(null, "tools/list", null);

    Classification lenient = McpRequestEra.classify("tools/list", params, headers, false);
    assertNull(lenient.refusal());
    assertEquals(1, lenient.issues().size());
    assertTrue(lenient.issues().get(0).contains(McpProtocolVersion.HEADER));

    Classification strict = McpRequestEra.classify("tools/list", params, headers, true);
    assertEquals(McpRequestEra.HEADER_MISMATCH, strict.refusal().code());
  }

  @Test
  public void methodHeaderDifferentFromBodyIsRefused() throws Exception {
    Classification c = classify("tools/list",
        new JSONObject().put("_meta", meta(MODERN)), full("tools/call", null));
    assertEquals(McpRequestEra.HEADER_MISMATCH, c.refusal().code());
  }

  @Test
  public void nameHeaderIsComparedAfterBase64Decoding() throws Exception {
    String encoded = "=?base64?" + Base64.getEncoder()
        .encodeToString("etendo_list".getBytes(StandardCharsets.UTF_8)) + "?=";
    assertNull(classify("tools/call", toolCall(meta(MODERN)), full("tools/call", encoded))
        .refusal());

    Classification other = classify("tools/call", toolCall(meta(MODERN)),
        full("tools/call", "etendo_get"));
    assertEquals(McpRequestEra.HEADER_MISMATCH, other.refusal().code());
  }

  @Test
  public void nameHeaderWithBrokenBase64IsRefused() throws Exception {
    Classification c = classify("tools/call", toolCall(meta(MODERN)),
        full("tools/call", "=?base64?***?="));
    assertEquals(McpRequestEra.HEADER_MISMATCH, c.refusal().code());
  }

  /** W4: a decoded header carrying a line break cannot forge a log line through the message. */
  @Test
  public void mismatchMessageNeverCarriesControlCharacters() throws Exception {
    String forged = "x\nWARN fake line\r\u2028end";
    String encoded = "=?base64?" + Base64.getEncoder()
        .encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "?=";

    Classification c = classify("tools/call", toolCall(meta(MODERN)), full("tools/call", encoded));

    String message = c.refusal().message();
    assertEquals(McpRequestEra.HEADER_MISMATCH, c.refusal().code());
    assertFalse(message, message.contains("\n") || message.contains("\r")
        || message.contains("\u2028"));
    assertTrue(message, message.contains("x?WARN fake line??end"));
  }

  @Test
  public void printableReplacesControlCharactersAndBoundsTheValue() {
    assertEquals("a?b?c", McpRequestEra.printable("a\tb\u0085c"));
    assertNull(McpRequestEra.printable(null));
    assertTrue(McpRequestEra.printable("y".repeat(500)).length() <= 60);
  }

  @Test
  public void resourcesReadMirrorsTheUri() throws Exception {
    JSONObject params = new JSONObject().put("uri", "etendo://specs")
        .put("_meta", meta(MODERN));
    assertNull(classify("resources/read", params, full("resources/read", "etendo://specs"))
        .refusal());
    assertEquals(McpRequestEra.HEADER_MISMATCH,
        classify("resources/read", params, full("resources/read", "etendo://other"))
            .refusal().code());
  }

  @Test
  public void missingMethodAndNameHeadersAreIssuesWhenLenient() throws Exception {
    Classification c = classify("tools/call", toolCall(meta(MODERN)),
        new Headers(MODERN, null, null));
    assertNull(c.refusal());
    assertEquals(2, c.issues().size());
  }

  @Test
  public void missingClientCapabilitiesIsInvalidParamsWhenStrict() throws Exception {
    JSONObject meta = meta(MODERN);
    meta.remove(McpRequestEra.META_CLIENT_CAPABILITIES);
    JSONObject params = new JSONObject().put("_meta", meta);
    Headers headers = full("tools/list", null);

    assertNull(McpRequestEra.classify("tools/list", params, headers, false).refusal());
    assertEquals(McpRequestEra.INVALID_PARAMS,
        McpRequestEra.classify("tools/list", params, headers, true).refusal().code());
  }

  @Test
  public void nonStringDeclaredVersionIsInvalidParams() throws Exception {
    Classification c = classify("tools/list",
        new JSONObject().put("_meta", meta(20260728)), full("tools/list", null));
    assertEquals(McpRequestEra.INVALID_PARAMS, c.refusal().code());
  }

  @Test
  public void discoverWithoutMetaIsModernAndServedWhenLenient() {
    Classification lenient = classify(McpRequestEra.SERVER_DISCOVER, null,
        new Headers(null, null, null));
    assertTrue(lenient.isModern());
    assertNull(lenient.refusal());
    assertEquals(MODERN, lenient.protocolVersion());
    assertFalse(lenient.issues().isEmpty());

    Classification strict = McpRequestEra.classify(McpRequestEra.SERVER_DISCOVER, null,
        new Headers(null, null, null), true);
    assertEquals(McpRequestEra.INVALID_PARAMS, strict.refusal().code());
  }

  @Test
  public void modernHeaderWithoutMetaIsModern() {
    Classification c = classify("tools/list", null, new Headers(MODERN, "tools/list", null));
    assertTrue(c.isModern());
    assertNull(c.refusal());
    assertEquals(1, c.issues().size());
  }

  @Test
  public void plainHeaderValuesAreNotDecoded() {
    assertEquals("etendo_list", McpRequestEra.decodeHeaderValue("etendo_list"));
    assertNull(McpRequestEra.decodeHeaderValue(null));
  }

  @Test
  public void allSupportedListsModernFirstThenEveryLegacyRevision() {
    assertEquals(MODERN, McpProtocolVersion.ALL_SUPPORTED.get(0));
    assertTrue(McpProtocolVersion.ALL_SUPPORTED.containsAll(McpProtocolVersion.SUPPORTED));
    assertEquals(McpProtocolVersion.SUPPORTED.size() + 1,
        McpProtocolVersion.ALL_SUPPORTED.size());
  }

  // ── Switches ─────────────────────────────────────────────────────────────

  private static final String KILL_SWITCH_PROPERTY =
      "etendo.go.flags." + GoFeatureFlags.FLAG_MCP_MODERN_ERA_DISABLED;

  /**
   * Runs {@code body} with only the given JVM properties set: the ambient Openbravo.properties is
   * replaced by an empty one, so a developer's local override cannot leak in.
   */
  private static void withProperties(String key, String value, Runnable body) {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    String previous = System.getProperty(key);
    try (MockedStatic<OBPropertiesProvider> mocked = mockStatic(OBPropertiesProvider.class)) {
      mocked.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      body.run();
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  public void modernEraIsOnWhenTheKillSwitchIsUnset() {
    withProperties(KILL_SWITCH_PROPERTY, null,
        () -> assertTrue(McpRequestEra.modernEnabled()));
  }

  @Test
  public void modernEraIsOnWhenTheKillSwitchIsFalse() {
    withProperties(KILL_SWITCH_PROPERTY, "false",
        () -> assertTrue(McpRequestEra.modernEnabled()));
  }

  @Test
  public void killSwitchTrueTurnsTheModernEraOff() {
    withProperties(KILL_SWITCH_PROPERTY, "true",
        () -> assertFalse(McpRequestEra.modernEnabled()));
  }

  @Test
  public void strictIsOffByDefaultAndOnWhenConfigured() {
    withProperties(McpRequestEra.PROP_STRICT, null, () -> assertFalse(McpRequestEra.strict()));
    withProperties(McpRequestEra.PROP_STRICT, "true", () -> assertTrue(McpRequestEra.strict()));
  }
}
