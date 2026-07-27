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

package com.etendoerp.go.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.etendoerp.go.schemaforge.telemetry.MixpanelNeoTelemetryConfig;

class GoFeatureFlagsConfigTest {

  @AfterEach
  void clearOverrides() {
    System.clearProperty(GoFeatureFlagsConfig.PROP_ENABLED);
    System.clearProperty(GoFeatureFlagsConfig.PROP_POLLING_INTERVAL_SECONDS);
    System.clearProperty(GoFeatureFlagsConfig.PROP_REQUEST_TIMEOUT_SECONDS);
    System.clearProperty(MixpanelNeoTelemetryConfig.PROP_TOKEN);
    System.clearProperty(MixpanelNeoTelemetryConfig.PROP_API_HOST);
    GoFeatureFlags.reset();
  }

  @Test
  void isNotConfiguredWithoutAToken() {
    GoFeatureFlagsConfig config = new GoFeatureFlagsConfig(true, null, null, 60, 10);
    assertFalse(config.isConfigured());
    assertNull(config.getProjectToken());
  }

  @Test
  void isNotConfiguredWhenDisabledEvenWithAToken() {
    GoFeatureFlagsConfig config = new GoFeatureFlagsConfig(false, "token-123", null, 60, 10);
    assertFalse(config.isConfigured());
  }

  @Test
  void isConfiguredWhenEnabledWithAToken() {
    assertTrue(new GoFeatureFlagsConfig(true, "token-123", null, 60, 10).isConfigured());
  }

  @Test
  void blankTokenIsTreatedAsAbsent() {
    assertFalse(new GoFeatureFlagsConfig(true, "   ", null, 60, 10).isConfigured());
  }

  /**
   * The API host is shared with the telemetry sink, which stores a full URL, while the Mixpanel
   * flags client expects a bare host. Normalization is what lets one setting serve both.
   */
  @ParameterizedTest
  @CsvSource({
      "https://api-eu.mixpanel.com, api-eu.mixpanel.com",
      "http://api.mixpanel.com,     api.mixpanel.com",
      "https://api-eu.mixpanel.com/,api-eu.mixpanel.com",
      "api.mixpanel.com,            api.mixpanel.com"
  })
  void normalizesTheApiHost(String configured, String expected) {
    assertEquals(expected, new GoFeatureFlagsConfig(true, "t", configured, 60, 10).getApiHost());
  }

  @Test
  void fallsBackToTheDefaultHostWhenBlank() {
    assertEquals(GoFeatureFlagsConfig.DEFAULT_API_HOST,
        new GoFeatureFlagsConfig(true, "t", "   ", 60, 10).getApiHost());
    assertEquals(GoFeatureFlagsConfig.DEFAULT_API_HOST,
        new GoFeatureFlagsConfig(true, "t", null, 60, 10).getApiHost());
  }

  @ParameterizedTest
  @CsvSource({ "0", "-1", "-60" })
  void rejectsNonPositiveIntervals(int invalid) {
    GoFeatureFlagsConfig config = new GoFeatureFlagsConfig(true, "t", null, invalid, invalid);
    assertEquals(GoFeatureFlagsConfig.DEFAULT_POLLING_INTERVAL_SECONDS,
        config.getPollingIntervalSeconds());
    assertEquals(GoFeatureFlagsConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS,
        config.getRequestTimeoutSeconds());
  }

  @Test
  void fromRuntimeReadsSystemProperties() {
    System.setProperty(GoFeatureFlagsConfig.PROP_ENABLED, "true");
    System.setProperty(MixpanelNeoTelemetryConfig.PROP_TOKEN, "token-from-property");
    System.setProperty(MixpanelNeoTelemetryConfig.PROP_API_HOST, "https://api.mixpanel.com");
    System.setProperty(GoFeatureFlagsConfig.PROP_POLLING_INTERVAL_SECONDS, "15");
    System.setProperty(GoFeatureFlagsConfig.PROP_REQUEST_TIMEOUT_SECONDS, "3");

    GoFeatureFlagsConfig config = GoFeatureFlagsConfig.fromRuntime();

    assertTrue(config.isConfigured());
    assertEquals("token-from-property", config.getProjectToken());
    assertEquals("api.mixpanel.com", config.getApiHost());
    assertEquals(15, config.getPollingIntervalSeconds());
    assertEquals(3, config.getRequestTimeoutSeconds());
  }

  @Test
  void fromRuntimeFallsBackToDefaultsForMalformedNumbers() {
    System.setProperty(GoFeatureFlagsConfig.PROP_POLLING_INTERVAL_SECONDS, "not-a-number");
    System.setProperty(GoFeatureFlagsConfig.PROP_REQUEST_TIMEOUT_SECONDS, "");

    GoFeatureFlagsConfig config = GoFeatureFlagsConfig.fromRuntime();

    assertEquals(GoFeatureFlagsConfig.DEFAULT_POLLING_INTERVAL_SECONDS,
        config.getPollingIntervalSeconds());
    assertEquals(GoFeatureFlagsConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS,
        config.getRequestTimeoutSeconds());
  }

  /**
   * The safety property the whole feature rests on: with flag evaluation switched off no provider is
   * installed and every flag resolves to its code default, so the paywall stays inert.
   */
  @Test
  void flagsResolveToFalseWhenEvaluationIsDisabled() {
    System.setProperty(GoFeatureFlagsConfig.PROP_ENABLED, "false");
    GoFeatureFlags.reset();

    assertFalse(GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_TENANT_UPGRADE,
        FeatureFlagContext.forAccount("user@example.com")));
    assertFalse(GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_TENANT_UPGRADE, null));
  }
}
