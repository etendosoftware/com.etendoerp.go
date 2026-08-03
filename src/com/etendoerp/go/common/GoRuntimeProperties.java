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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves Etendo Go runtime configuration values from the three sources the module supports,
 * in priority order: JVM system property, {@code Openbravo.properties}, environment variable.
 *
 * <p>Reading {@code Openbravo.properties} is best-effort: outside a fully initialized Openbravo
 * runtime (unit tests, early startup) the provider throws, and the lookup falls through to the
 * environment variable and finally the supplied default.
 *
 * <p>The precedence itself lives in {@link ConfigPropertyReader}, the shared resolver the NEO
 * config classes already delegate to. This class adds only the typed ({@code boolean} /
 * {@code int}) readers the feature-flag plumbing needs on top of it.
 */
public final class GoRuntimeProperties {

  private static final Logger log = LogManager.getLogger(GoRuntimeProperties.class);

  private GoRuntimeProperties() {
  }

  /**
   * Reads a configuration value, preferring the JVM system property, then the Openbravo property
   * of the same name, then the environment variable.
   *
   * @param propertyName system / Openbravo property name (e.g. {@code etendo.go.mixpanel.token})
   * @param envName environment variable name (e.g. {@code ETGO_MIXPANEL_TOKEN})
   * @param defaultValue value returned when none of the sources define the setting
   * @return the resolved value, or {@code defaultValue} when unset in every source
   */
  public static String readValue(String propertyName, String envName, String defaultValue) {
    return ConfigPropertyReader.readConfigValue(propertyName, envName, defaultValue);
  }

  /**
   * Reads a boolean configuration value. Only {@code true} and {@code Y} (case-insensitive) are
   * truthy, matching the convention already used by the module's Mixpanel telemetry settings.
   *
   * @param propertyName system / Openbravo property name
   * @param envName environment variable name
   * @param defaultValue value assumed when the setting is unset
   * @return {@code true} when the resolved value is truthy
   */
  public static boolean readBoolean(String propertyName, String envName, boolean defaultValue) {
    String value = readValue(propertyName, envName, defaultValue ? "true" : "false");
    return "true".equalsIgnoreCase(value) || "Y".equalsIgnoreCase(value);
  }

  /**
   * Reads an integer configuration value, falling back to {@code defaultValue} when the setting is
   * unset or not parseable as an integer.
   *
   * @param propertyName system / Openbravo property name
   * @param envName environment variable name
   * @param defaultValue value assumed when the setting is unset or malformed
   * @return the resolved integer
   */
  public static int readInt(String propertyName, String envName, int defaultValue) {
    String value = readValue(propertyName, envName, String.valueOf(defaultValue));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      log.debug("Non-numeric value for {}: {}", propertyName, value);
      return defaultValue;
    }
  }
}
