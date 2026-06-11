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

package com.etendoerp.go.schemaforge.email;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-side provider configuration. No provider endpoint or API key is
 * hardcoded in the module.
 */
public final class EmailProviderConfig {

  private static final Logger log = LogManager.getLogger(EmailProviderConfig.class);

  static final String PROP_BASE_URL = "etendo.go.email.provider.baseUrl";
  static final String PROP_API_KEY = "etendo.go.email.provider.apiKey";
  static final String PROP_TIMEOUT_MS = "etendo.go.email.provider.timeoutMs";
  static final String PROP_ENABLED = "etendo.go.email.provider.enabled";
  static final String ENV_BASE_URL = "ETGO_EMAIL_PROVIDER_BASE_URL";
  static final String ENV_API_KEY = "ETGO_EMAIL_PROVIDER_API_KEY";
  static final String ENV_TIMEOUT_MS = "ETGO_EMAIL_PROVIDER_TIMEOUT_MS";
  static final String ENV_ENABLED = "ETGO_EMAIL_PROVIDER_ENABLED";
  static final int DEFAULT_TIMEOUT_MS = 10000;

  private final String baseUrl;
  private final String apiKey;
  private final boolean enabled;
  private final int timeoutMs;

  /**
   * Creates server-side provider configuration.
   *
   * @param baseUrl provider/API Gateway URL
   * @param apiKey provider API key
   * @param enabled whether provider submission is enabled
   * @param timeoutMs connect/read timeout in milliseconds
   */
  public EmailProviderConfig(String baseUrl, String apiKey, boolean enabled, int timeoutMs) {
    this.baseUrl = StringUtils.trimToNull(baseUrl);
    this.apiKey = StringUtils.trimToNull(apiKey);
    this.enabled = enabled;
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
  }

  /**
   * Reads provider configuration from Java properties, Openbravo properties, or environment.
   *
   * @return runtime provider configuration
   */
  public static EmailProviderConfig fromRuntime() {
    String baseUrl = readConfigValue(PROP_BASE_URL, ENV_BASE_URL, null);
    String apiKey = readConfigValue(PROP_API_KEY, ENV_API_KEY, null);
    boolean enabled = isTruthy(readConfigValue(PROP_ENABLED, ENV_ENABLED, "true"));
    int timeoutMs = parseTimeout(readConfigValue(PROP_TIMEOUT_MS, ENV_TIMEOUT_MS,
        String.valueOf(DEFAULT_TIMEOUT_MS)));
    return new EmailProviderConfig(baseUrl, apiKey, enabled, timeoutMs);
  }

  public boolean isConfigured() {
    return enabled && baseUrl != null && apiKey != null;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  private static String readConfigValue(String propertyName, String envName, String defaultValue) {
    String systemValue = StringUtils.trimToNull(System.getProperty(propertyName));
    if (systemValue != null) {
      return systemValue;
    }
    String openbravoValue = readOpenbravoProperty(propertyName);
    if (openbravoValue != null) {
      return openbravoValue;
    }
    String envValue = StringUtils.trimToNull(System.getenv(envName));
    return envValue != null ? envValue : defaultValue;
  }

  private static String readOpenbravoProperty(String propertyName) {
    try {
      return StringUtils.trimToNull(org.openbravo.base.session.OBPropertiesProvider.getInstance()
          .getOpenbravoProperties().getProperty(propertyName));
    } catch (Exception e) {
      log.debug("Could not read Openbravo property {}: {}", propertyName, e.getMessage(), e);
      return null;
    }
  }

  private static boolean isTruthy(String value) {
    return "true".equalsIgnoreCase(value) || "Y".equalsIgnoreCase(value);
  }

  private static int parseTimeout(String rawTimeout) {
    try {
      return Integer.parseInt(rawTimeout);
    } catch (NumberFormatException e) {
      return DEFAULT_TIMEOUT_MS;
    }
  }
}
