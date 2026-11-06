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
    assertEquals(HubSpotConfiguration.DEFAULT_ACCOUNT_STAGE, configuration.accountStage());
    assertEquals(HubSpotConfiguration.DEFAULT_LOST_STAGE, configuration.lostStage());
  }

  @Test
  void theDefaultsAreTheSelfServePipelineMarketingCreatedOn20261029() {
    // ETP-6082: production only sets the token, so a wrong default id is a 400 on every deal.
    HubSpotConfiguration configuration = configured(Map.of());

    assertEquals("952774980", configuration.pipelineId());
    assertEquals("1471483196", configuration.accountStage());
    assertEquals("1471483197", configuration.activatedStage());
    assertEquals("1471504904", configuration.cardStage());
    assertEquals("1471504905", configuration.firstPaymentStage());
    assertEquals("1471504906", configuration.expiredStage());
    assertEquals("1471504907", configuration.lostStage());
  }

  @Test
  void theDemoPipelineDefaultsToMarketingsAndIsMovedFromDemoRealizada() {
    HubSpotConfiguration configuration = configured(Map.of());

    HubSpotConfiguration.Pipeline demo = configuration.pipeline("952778001").orElseThrow();
    assertEquals("1471455757", demo.entryStage());
    assertEquals("1471455758", demo.activatedStage());
    assertEquals("1471455759", demo.cardStage());
    assertEquals("1471455760", demo.firstPaymentStage());
    assertEquals("1471455761", demo.expiredStage());
    assertEquals("1471455762", demo.lostStage());
    assertEquals(configuration.selfServe(), configuration.pipeline("952774980").orElseThrow());
    assertTrue(configuration.pipeline("948690590").isEmpty(), "other pipelines are marketing's");
    assertTrue(configuration.pipeline(null).isEmpty());
  }

  @Test
  void aTestPortalOverridesTheDemoPipelineToo() {
    HubSpotConfiguration configuration = configured(Map.of(
        "etendo.go.hubspot.demo.pipeline", "77",
        "etendo.go.hubspot.demo.stage.entry", "78"));

    assertEquals("78", configuration.demo().entryStage());
    assertEquals(configuration.demo(), configuration.pipeline("77").orElseThrow());
    assertEquals(HubSpotConfiguration.DEFAULT_DEMO_LOST_STAGE, configuration.demo().lostStage());
  }

  @Test
  void aTestPortalOverridesWhatItNeeds() {
    HubSpotConfiguration configuration = configured(Map.of(
        HubSpotConfiguration.TOKEN_PROPERTY, " pat-test ",
        "etendo.go.hubspot.api.base.url", "http://localhost:9999/",
        "etendo.go.hubspot.stage.card", "123",
        "etendo.go.hubspot.stage.lost", "  "));

    assertTrue(configuration.isEnabled());
    assertEquals("pat-test", configuration.token());
    assertEquals("http://localhost:9999", configuration.apiBaseUrl());
    assertEquals("123", configuration.cardStage());
    assertEquals(HubSpotConfiguration.DEFAULT_LOST_STAGE, configuration.lostStage());
    assertEquals(HubSpotConfiguration.DEFAULT_ACTIVATED_STAGE, configuration.activatedStage());
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
        "https://api.hubapi.com", "p", "m", "s", "c", "f", "e", "l");

    assertFalse(configuration.toString().contains("pat-secret"));
    assertTrue(configuration.toString().contains("enabled=true"));
  }
}
