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

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;
import com.etendoerp.go.schemaforge.telemetry.MixpanelNeoTelemetryConfig;

/**
 * Runtime configuration for backend feature-flag evaluation.
 *
 * <p>The project token and API host are shared with backend Mixpanel telemetry
 * ({@code etendo.go.mixpanel.token} / {@code etendo.go.mixpanel.apiHost}) — flags and telemetry
 * target the same Mixpanel project. Only the flag-specific knobs get their own settings:
 *
 * <ul>
 *   <li>{@code etendo.go.featureflags.enabled} — master switch, default {@code true}</li>
 *   <li>{@code etendo.go.featureflags.pollingIntervalSeconds} — definition refresh cadence,
 *       default 60</li>
 *   <li>{@code etendo.go.featureflags.requestTimeoutSeconds} — HTTP timeout for the definitions
 *       fetch, default 10</li>
 * </ul>
 *
 * <p>When the token is absent the configuration is <em>not configured</em>, the provider is never
 * installed, and every flag resolves to its code default.
 */
public final class GoFeatureFlagsConfig {

  static final String PROP_ENABLED = "etendo.go.featureflags.enabled";
  static final String PROP_POLLING_INTERVAL_SECONDS =
      "etendo.go.featureflags.pollingIntervalSeconds";
  static final String PROP_REQUEST_TIMEOUT_SECONDS =
      "etendo.go.featureflags.requestTimeoutSeconds";
  static final String ENV_ENABLED = "ETGO_FEATUREFLAGS_ENABLED";
  static final String ENV_POLLING_INTERVAL_SECONDS = "ETGO_FEATUREFLAGS_POLLING_INTERVAL_SECONDS";
  static final String ENV_REQUEST_TIMEOUT_SECONDS = "ETGO_FEATUREFLAGS_REQUEST_TIMEOUT_SECONDS";

  static final String DEFAULT_API_HOST = "api-eu.mixpanel.com";
  static final int DEFAULT_POLLING_INTERVAL_SECONDS = 60;
  static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 10;

  private final boolean enabled;
  private final String projectToken;
  private final String apiHost;
  private final int pollingIntervalSeconds;
  private final int requestTimeoutSeconds;

  /**
   * Creates immutable feature-flag configuration with normalized optional values.
   *
   * @param enabled whether backend flag evaluation is enabled
   * @param projectToken Mixpanel project token
   * @param apiHost Mixpanel API host, with or without a URL scheme
   * @param pollingIntervalSeconds flag-definition refresh cadence in seconds
   * @param requestTimeoutSeconds HTTP timeout for the definitions fetch in seconds
   */
  public GoFeatureFlagsConfig(boolean enabled, String projectToken, String apiHost,
      int pollingIntervalSeconds, int requestTimeoutSeconds) {
    this.enabled = enabled;
    this.projectToken = StringUtils.trimToNull(projectToken);
    this.apiHost = normalizeHost(apiHost);
    this.pollingIntervalSeconds = pollingIntervalSeconds > 0
        ? pollingIntervalSeconds
        : DEFAULT_POLLING_INTERVAL_SECONDS;
    this.requestTimeoutSeconds = requestTimeoutSeconds > 0
        ? requestTimeoutSeconds
        : DEFAULT_REQUEST_TIMEOUT_SECONDS;
  }

  /**
   * Reads feature-flag configuration from system properties, {@code Openbravo.properties}, or
   * environment variables.
   *
   * @return runtime feature-flag configuration
   */
  public static GoFeatureFlagsConfig fromRuntime() {
    boolean enabled = GoRuntimeProperties.readBoolean(PROP_ENABLED, ENV_ENABLED, true);
    String token = GoRuntimeProperties.readValue(MixpanelNeoTelemetryConfig.PROP_TOKEN,
        MixpanelNeoTelemetryConfig.ENV_TOKEN, null);
    String host = GoRuntimeProperties.readValue(MixpanelNeoTelemetryConfig.PROP_API_HOST,
        MixpanelNeoTelemetryConfig.ENV_API_HOST, DEFAULT_API_HOST);
    int pollingInterval = GoRuntimeProperties.readInt(PROP_POLLING_INTERVAL_SECONDS,
        ENV_POLLING_INTERVAL_SECONDS, DEFAULT_POLLING_INTERVAL_SECONDS);
    int requestTimeout = GoRuntimeProperties.readInt(PROP_REQUEST_TIMEOUT_SECONDS,
        ENV_REQUEST_TIMEOUT_SECONDS, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    return new GoFeatureFlagsConfig(enabled, token, host, pollingInterval, requestTimeout);
  }

  /**
   * Indicates whether the Mixpanel provider can be installed. Incomplete configuration keeps the
   * provider uninstalled so every flag falls back to its code default.
   *
   * @return {@code true} when flag evaluation is enabled and a project token is present
   */
  public boolean isConfigured() {
    return enabled && projectToken != null;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getProjectToken() {
    return projectToken;
  }

  public String getApiHost() {
    return apiHost;
  }

  public int getPollingIntervalSeconds() {
    return pollingIntervalSeconds;
  }

  public int getRequestTimeoutSeconds() {
    return requestTimeoutSeconds;
  }

  /**
   * Strips the URL scheme and any trailing slash so a value shared with the telemetry sink (which
   * stores a full {@code https://…} URL) is accepted by the Mixpanel flags client, which expects a
   * bare host such as {@code api-eu.mixpanel.com}.
   */
  private static String normalizeHost(String rawHost) {
    String host = StringUtils.trimToNull(rawHost);
    if (host == null) {
      return DEFAULT_API_HOST;
    }
    host = StringUtils.removeStart(host, "https://");
    host = StringUtils.removeStart(host, "http://");
    host = StringUtils.stripEnd(host, "/");
    return StringUtils.defaultIfBlank(host, DEFAULT_API_HOST);
  }
}
