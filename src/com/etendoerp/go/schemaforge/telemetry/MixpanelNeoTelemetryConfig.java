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

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.ConfigPropertyReader;

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
  /**
   * ETP-5581 — the deployment every backend event is tagged with, as {@code environment}. Set
   * explicitly with this property / {@link #ENV_ENVIRONMENT}; when unset, the deployment's
   * {@code DD_ENV} (the Datadog environment the ECS task already declares: {@code production},
   * {@code experimental}, {@code staging}) is used; else {@value #DEFAULT_ENVIRONMENT}.
   */
  static final String PROP_ENVIRONMENT = "etendo.go.mixpanel.environment";
  static final String ENV_ENVIRONMENT = "ETGO_MIXPANEL_ENVIRONMENT";
  /** The Datadog agent's system property for {@code DD_ENV}. */
  static final String PROP_DD_ENV = "dd.env";
  static final String ENV_DD_ENV = "DD_ENV";
  /** No deployment environment configured anywhere (a developer machine, a test). */
  static final String DEFAULT_ENVIRONMENT = "unknown";
  static final String DEFAULT_API_HOST = "https://api-eu.mixpanel.com";
  static final String DEFAULT_DISTINCT_ID = "neo-backend";
  static final int DEFAULT_TIMEOUT_MS = 5000;

  private final boolean enabled;
  private final String token;
  private final String apiHost;
  private final String distinctId;
  private final int timeoutMs;
  private final String environment;

  /**
   * Creates immutable backend Mixpanel configuration with normalized optional values and no
   * deployment environment ({@value #DEFAULT_ENVIRONMENT}).
   *
   * @param enabled whether backend Mixpanel submission is enabled
   * @param token Mixpanel project token
   * @param apiHost Mixpanel API host
   * @param distinctId backend distinct identifier
   * @param timeoutMs HTTP connect/read timeout in milliseconds
   */
  public MixpanelNeoTelemetryConfig(
      boolean enabled, String token, String apiHost, String distinctId, int timeoutMs) {
    this(enabled, token, apiHost, distinctId, timeoutMs, null);
  }

  /**
   * Creates immutable backend Mixpanel configuration with normalized optional values.
   *
   * @param enabled whether backend Mixpanel submission is enabled
   * @param token Mixpanel project token
   * @param apiHost Mixpanel API host
   * @param distinctId backend distinct identifier
   * @param timeoutMs HTTP connect/read timeout in milliseconds
   * @param environment deployment environment sent on every event, normalized by
   *     {@link #normalizeEnvironment}
   */
  public MixpanelNeoTelemetryConfig(boolean enabled, String token, String apiHost,
      String distinctId, int timeoutMs, String environment) {
    this.enabled = enabled;
    this.token = StringUtils.trimToNull(token);
    this.apiHost = StringUtils.stripEnd(StringUtils.defaultIfBlank(apiHost, DEFAULT_API_HOST), "/");
    this.distinctId = StringUtils.defaultIfBlank(distinctId, DEFAULT_DISTINCT_ID);
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    this.environment = normalizeEnvironment(environment);
  }

  /**
   * Reads backend Mixpanel configuration from Java, Openbravo, or environment properties.
   *
   * @return runtime backend Mixpanel configuration
   */
  public static MixpanelNeoTelemetryConfig fromRuntime() {
    boolean enabled = isTruthy(ConfigPropertyReader.readConfigValue(PROP_ENABLED, ENV_ENABLED, "true"));
    String token = ConfigPropertyReader.readConfigValue(PROP_TOKEN, ENV_TOKEN, null);
    String apiHost = ConfigPropertyReader.readConfigValue(PROP_API_HOST, ENV_API_HOST, DEFAULT_API_HOST);
    String distinctId = ConfigPropertyReader.readConfigValue(PROP_DISTINCT_ID, ENV_DISTINCT_ID, DEFAULT_DISTINCT_ID);
    int timeoutMs = parseTimeout(ConfigPropertyReader.readConfigValue(PROP_TIMEOUT_MS, ENV_TIMEOUT_MS,
        String.valueOf(DEFAULT_TIMEOUT_MS)));
    String environment = ConfigPropertyReader.readConfigValue(PROP_ENVIRONMENT, ENV_ENVIRONMENT,
        ConfigPropertyReader.readConfigValue(PROP_DD_ENV, ENV_DD_ENV, null));
    return new MixpanelNeoTelemetryConfig(enabled, token, apiHost, distinctId, timeoutMs,
        environment);
  }

  /**
   * The deployment environment as every backend event carries it: trimmed and lower case, with
   * Datadog's aliases folded onto their canonical name ({@code prod} → {@code production},
   * {@code stage} → {@code staging}) so the KPI collector's {@code environment = production}
   * filter matches whichever spelling the deployment uses; {@value #DEFAULT_ENVIRONMENT} when
   * blank.
   *
   * @param raw the configured value, or {@code null}
   * @return the normalized environment, never {@code null}
   */
  static String normalizeEnvironment(String raw) {
    String value = StringUtils.lowerCase(StringUtils.trimToNull(raw), Locale.ROOT);
    if (value == null) {
      return DEFAULT_ENVIRONMENT;
    }
    switch (value) {
      case "prod":
        return "production";
      case "stage":
        return "staging";
      default:
        return value;
    }
  }

  /**
   * Whether events can be submitted: enabled and with a token.
   *
   * @return {@code true} when configured
   */
  public boolean isConfigured() {
    return enabled && token != null;
  }

  /**
   * Whether submission is enabled.
   *
   * @return the enabled setting
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * The Mixpanel project token.
   *
   * @return the token, or {@code null}
   */
  public String getToken() {
    return token;
  }

  /**
   * The Mixpanel API host, without a trailing slash.
   *
   * @return the host
   */
  public String getApiHost() {
    return apiHost;
  }

  /**
   * The backend distinct id.
   *
   * @return the distinct id
   */
  public String getDistinctId() {
    return distinctId;
  }

  /**
   * The HTTP connect/read timeout.
   *
   * @return milliseconds
   */
  public int getTimeoutMs() {
    return timeoutMs;
  }

  /**
   * The deployment environment every backend event is tagged with (ETP-5581).
   *
   * @return the normalized environment, never {@code null}
   */
  public String getEnvironment() {
    return environment;
  }

  /**
   * {@code true} / {@code Y}, case-insensitive.
   *
   * @param value the raw value
   * @return whether it is truthy
   */
  private static boolean isTruthy(String value) {
    return "true".equalsIgnoreCase(value) || "Y".equalsIgnoreCase(value);
  }

  /**
   * The timeout, or the default when not a number.
   *
   * @param rawTimeout the raw value
   * @return milliseconds
   */
  private static int parseTimeout(String rawTimeout) {
    try {
      return Integer.parseInt(rawTimeout);
    } catch (NumberFormatException e) {
      return DEFAULT_TIMEOUT_MS;
    }
  }
}
