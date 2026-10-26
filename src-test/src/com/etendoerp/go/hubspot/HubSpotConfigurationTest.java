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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Where the HubSpot sync writes, read from the server's configuration.
 *
 * @covers com.etendoerp.go.hubspot.HubSpotConfiguration
 */
class HubSpotConfigurationTest {

  private static HubSpotConfiguration configured(Map<String, String> properties) {
    return HubSpotConfiguration.from((property, env) -> properties.get(property));
  }

  @Test
  void withoutATokenTheSyncIsOffAndTheProductionPipelineIsTheDefault() {
    HubSpotConfiguration configuration = configured(Map.of());

    assertFalse(configuration.isEnabled());
    assertNull(configuration.token());
    assertEquals("https://api.hubapi.com", configuration.apiBaseUrl());
    assertEquals(HubSpotConfiguration.DEFAULT_PIPELINE, configuration.pipelineId());
    assertEquals(HubSpotConfiguration.DEFAULT_TRIAL_STAGE, configuration.trialStage());
    assertEquals(HubSpotConfiguration.DEFAULT_LOST_STAGE, configuration.lostStage());
  }

  @Test
  void aTestPortalOverridesWhatItNeeds() {
    HubSpotConfiguration configuration = configured(Map.of(
        HubSpotConfiguration.TOKEN_PROPERTY, " pat-test ",
        "etendo.go.hubspot.api.base.url", "http://localhost:9999/",
        "etendo.go.hubspot.stage.won", "123",
        "etendo.go.hubspot.stage.lost", "  "));

    assertTrue(configuration.isEnabled());
    assertEquals("pat-test", configuration.token());
    assertEquals("http://localhost:9999", configuration.apiBaseUrl());
    assertEquals("123", configuration.wonStage());
    assertEquals(HubSpotConfiguration.DEFAULT_LOST_STAGE, configuration.lostStage());
    assertEquals(HubSpotConfiguration.DEFAULT_ACTIVE_STAGE, configuration.activeStage());
  }

  @Test
  void eachSettingIsReadByItsPropertyAndItsEnvironmentVariable() {
    HubSpotConfiguration configuration = HubSpotConfiguration.from(
        (property, env) -> HubSpotConfiguration.TOKEN_ENV.equals(env) ? "from-env" : null);

    assertEquals("from-env", configuration.token());
  }

  @Test
  void theTokenNeverReachesALogLine() {
    HubSpotConfiguration configuration = new HubSpotConfiguration("pat-secret",
        "https://api.hubapi.com", "p", "t", "a", "w", "l");

    assertFalse(configuration.toString().contains("pat-secret"));
    assertTrue(configuration.toString().contains("enabled=true"));
  }
}
