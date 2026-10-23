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

package com.etendoerp.go.hubspot;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Where and as whom the HubSpot sync writes, and which deal pipeline it moves.
 *
 * <p>Each value is read as a JVM system property, then {@code Openbravo.properties}, then an
 * environment variable ({@link GoRuntimeProperties}). Only the token has no default: without it
 * the sync sends nothing and the outbox keeps every event pending. The pipeline and stage ids
 * default to the "Self-serve" pipeline of the production portal; a test portal has its own ids
 * and must configure them.
 *
 * @param token       the private app token, {@code null} when not configured
 * @param apiBaseUrl  the API root, without a trailing slash
 * @param pipelineId  the deal pipeline every self-serve deal is created in
 * @param trialStage  the stage of a deal whose demo is ready
 * @param activeStage the stage of a deal whose owner entered the demo
 * @param wonStage    the stage of a deal whose owner paid
 * @param lostStage   the stage of a deal whose trial ended unpaid
 */
public record HubSpotConfiguration(String token, String apiBaseUrl, String pipelineId,
    String trialStage, String activeStage, String wonStage, String lostStage) {

  static final String TOKEN_PROPERTY = "etendo.go.hubspot.token";
  static final String TOKEN_ENV = "ETGO_HUBSPOT_TOKEN";
  static final String DEFAULT_API_BASE_URL = "https://api.hubapi.com";

  /** "Self-serve" pipeline and its stages in portal 52086088, read on 2026-10-22. */
  static final String DEFAULT_PIPELINE = "944761479";
  static final String DEFAULT_TRIAL_STAGE = "1458221379";
  static final String DEFAULT_ACTIVE_STAGE = "1458221380";
  static final String DEFAULT_WON_STAGE = "1458221381";
  static final String DEFAULT_LOST_STAGE = "1458221382";

  /** Where the settings come from. */
  @FunctionalInterface
  interface Settings {
    /**
     * Reads one setting.
     *
     * @param propertyName its property name
     * @param envName      its environment variable
     * @return its value, or {@code null} when it is not set
     */
    String read(String propertyName, String envName);
  }

  /**
   * Reads the configuration as currently set on this server.
   *
   * @return the configuration
   */
  public static HubSpotConfiguration fromRuntime() {
    return from((property, env) -> GoRuntimeProperties.readValue(property, env, null));
  }

  static HubSpotConfiguration from(Settings settings) {
    return new HubSpotConfiguration(
        StringUtils.trimToNull(settings.read(TOKEN_PROPERTY, TOKEN_ENV)),
        StringUtils.removeEnd(read(settings, "api.base.url", "API_BASE_URL",
            DEFAULT_API_BASE_URL), "/"),
        read(settings, "pipeline", "PIPELINE", DEFAULT_PIPELINE),
        read(settings, "stage.trial", "STAGE_TRIAL", DEFAULT_TRIAL_STAGE),
        read(settings, "stage.active", "STAGE_ACTIVE", DEFAULT_ACTIVE_STAGE),
        read(settings, "stage.won", "STAGE_WON", DEFAULT_WON_STAGE),
        read(settings, "stage.lost", "STAGE_LOST", DEFAULT_LOST_STAGE));
  }

  private static String read(Settings settings, String propertySuffix, String envSuffix,
      String defaultValue) {
    String value = settings.read("etendo.go.hubspot." + propertySuffix,
        "ETGO_HUBSPOT_" + envSuffix);
    return StringUtils.defaultIfBlank(StringUtils.trim(value), defaultValue);
  }

  /**
   * Tells whether anything may be sent.
   *
   * @return whether a token is configured
   */
  public boolean isEnabled() {
    return token != null;
  }

  @Override
  public String toString() {
    // The token is a credential: never let it reach a log line.
    return "HubSpotConfiguration[enabled=" + isEnabled() + ", apiBaseUrl=" + apiBaseUrl
        + ", pipelineId=" + pipelineId + "]";
  }
}
