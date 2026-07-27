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

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Runtime Mixpanel configuration for backend telemetry.
 */
public final class MixpanelNeoTelemetryConfig {

  static final String PROP_ENABLED = "etendo.go.mixpanel.enabled";
  static final String PROP_TOKEN = "etendo.go.mixpanel.token";
  static final String PROP_API_HOST = "etendo.go.mixpanel.apiHost";
  static final String PROP_TIMEOUT_MS = "etendo.go.mixpanel.timeoutMs";
  static final String PROP_DISTINCT_ID = "etendo.go.mixpanel.distinctId";
  static final String ENV_ENABLED = "ETGO_MIXPANEL_ENABLED";
  static final String ENV_TOKEN = "ETGO_MIXPANEL_TOKEN";
  static final String ENV_API_HOST = "ETGO_MIXPANEL_API_HOST";
  static final String ENV_TIMEOUT_MS = "ETGO_MIXPANEL_TIMEOUT_MS";
  static final String ENV_DISTINCT_ID = "ETGO_MIXPANEL_DISTINCT_ID";
  static final String DEFAULT_API_HOST = "https://api-eu.mixpanel.com";
  static final String DEFAULT_DISTINCT_ID = "neo-backend";
  static final int DEFAULT_TIMEOUT_MS = 5000;

  private final boolean enabled;
  private final String token;
  private final String apiHost;
  private final String distinctId;
  private final int timeoutMs;

  /**
   * Creates immutable backend Mixpanel configuration with normalized optional values.
   *
   * @param enabled whether backend Mixpanel submission is enabled
   * @param token Mixpanel project token
   * @param apiHost Mixpanel API host
   * @param distinctId backend distinct identifier
   * @param timeoutMs HTTP connect/read timeout in milliseconds
   */
  public MixpanelNeoTelemetryConfig(
      boolean enabled, String token, String apiHost, String distinctId, int timeoutMs) {
    this.enabled = enabled;
    this.token = StringUtils.trimToNull(token);
    this.apiHost = StringUtils.stripEnd(StringUtils.defaultIfBlank(apiHost, DEFAULT_API_HOST), "/");
    this.distinctId = StringUtils.defaultIfBlank(distinctId, DEFAULT_DISTINCT_ID);
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
  }

  /**
   * Reads backend Mixpanel configuration from Java, Openbravo, or environment properties.
   *
   * @return runtime backend Mixpanel configuration
   */
  public static MixpanelNeoTelemetryConfig fromRuntime() {
    boolean enabled = GoRuntimeProperties.readBoolean(PROP_ENABLED, ENV_ENABLED, true);
    String token = GoRuntimeProperties.readValue(PROP_TOKEN, ENV_TOKEN, null);
    String apiHost = GoRuntimeProperties.readValue(PROP_API_HOST, ENV_API_HOST, DEFAULT_API_HOST);
    String distinctId = GoRuntimeProperties.readValue(PROP_DISTINCT_ID, ENV_DISTINCT_ID,
        DEFAULT_DISTINCT_ID);
    int timeoutMs = GoRuntimeProperties.readInt(PROP_TIMEOUT_MS, ENV_TIMEOUT_MS,
        DEFAULT_TIMEOUT_MS);
    return new MixpanelNeoTelemetryConfig(enabled, token, apiHost, distinctId, timeoutMs);
  }

  public boolean isConfigured() {
    return enabled && token != null;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getToken() {
    return token;
  }

  public String getApiHost() {
    return apiHost;
  }

  public String getDistinctId() {
    return distinctId;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

}
