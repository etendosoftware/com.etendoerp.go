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

package com.etendoerp.go.common;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared config-resolution precedence for NEO Headless config classes:
 * Java system property → Openbravo.properties → environment variable → default.
 *
 * Extracted from the identical logic duplicated across {@code EmailProviderConfig},
 * {@code MixpanelNeoTelemetryConfig}, and {@code JiraConfig} — new config classes
 * should delegate here instead of adding another copy.
 */
public final class ConfigPropertyReader {

  private static final Logger log = LogManager.getLogger(ConfigPropertyReader.class);

  private ConfigPropertyReader() {
  }

  /**
   * Resolves a config value using the standard precedence: Java system property,
   * then Openbravo.properties, then environment variable, then the given default.
   *
   * @param propertyName Java/Openbravo property key
   * @param envName      environment variable name
   * @param defaultValue fallback value when none of the above are set
   * @return the resolved value
   */
  public static String readConfigValue(String propertyName, String envName, String defaultValue) {
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
}
