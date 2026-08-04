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

import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;

/**
 * OpenFeature provider that resolves flags from local configuration.
 *
 * <p>A flag {@code my-flag} is read from {@code etendo.go.flags.my-flag}, resolved through the
 * module's usual three sources in priority order: JVM system property, {@code Openbravo.properties},
 * environment variable ({@code ETGO_FLAG_MY_FLAG}). An absent or unparseable setting yields the
 * caller's default, which for every flag in this module is {@code false}.
 *
 * <p>Evaluation is purely local: no network call, no background thread, no polling, nothing to be
 * unreachable. Changing a flag requires a configuration change, so this provider serves
 * environment-level rollout rather than per-user targeting — the evaluation context is accepted for
 * API compatibility but does not affect the result.
 *
 * <p>This is a deliberate first step. Replacing it with a hosted control plane (Mixpanel Feature
 * Flags with local evaluation and polling, per the team plan) is a change to
 * {@link GoFeatureFlags#createProvider()} alone; nothing outside this package moves.
 *
 * <p>Only boolean flags are backed by configuration. The other OpenFeature types return the caller's
 * default rather than pretending to resolve, so a future typed flag fails visibly instead of
 * silently reading as an empty string or zero.
 */
public class PropertiesFeatureProvider implements dev.openfeature.sdk.FeatureProvider {

  static final String PROPERTY_PREFIX = "etendo.go.flags.";
  static final String ENV_PREFIX = "ETGO_FLAG_";
  private static final String NAME = "etendo-go-properties";
  private static final String REASON_STATIC = "STATIC";
  private static final String REASON_DEFAULT = "DEFAULT";

  @Override
  public Metadata getMetadata() {
    return () -> NAME;
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
      EvaluationContext ctx) {
    String configured = readFlagValue(key);
    if (configured == null) {
      return defaultResult(defaultValue);
    }
    Optional<Boolean> parsed = parseBoolean(configured);
    if (!parsed.isPresent()) {
      return ProviderEvaluation.<Boolean>builder()
          .value(defaultValue)
          .reason(REASON_DEFAULT)
          .errorCode(ErrorCode.PARSE_ERROR)
          .errorMessage("Flag '" + key + "' is not a boolean: " + configured)
          .build();
    }
    return ProviderEvaluation.<Boolean>builder()
        .value(parsed.get())
        .reason(REASON_STATIC)
        .build();
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue,
      EvaluationContext ctx) {
    return unsupportedType(defaultValue);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue,
      EvaluationContext ctx) {
    return unsupportedType(defaultValue);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue,
      EvaluationContext ctx) {
    return unsupportedType(defaultValue);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue,
      EvaluationContext ctx) {
    return unsupportedType(defaultValue);
  }

  /**
   * Reads the raw configured value for a flag key.
   *
   * @param flagKey the OpenFeature flag key
   * @return the configured value, or null when the flag is not configured anywhere
   */
  private static String readFlagValue(String flagKey) {
    String key = StringUtils.trimToNull(flagKey);
    if (key == null) {
      return null;
    }
    return GoRuntimeProperties.readValue(PROPERTY_PREFIX + key, toEnvName(key), null);
  }

  /**
   * Maps a flag key to its environment-variable name: uppercased, with every character that is not
   * a letter or digit replaced by an underscore, so {@code tenant-upgrade} becomes
   * {@code ETGO_FLAG_TENANT_UPGRADE}.
   */
  static String toEnvName(String flagKey) {
    return ENV_PREFIX + flagKey.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
  }

  /**
   * Accepts only explicit affirmatives and negatives. Anything else is a configuration mistake and
   * is reported as a parse error rather than silently read as false, which would make a typo
   * indistinguishable from an intentionally disabled flag.
   *
   * <p>Returns an {@link Optional} rather than a nullable {@code Boolean} so the "not parseable"
   * case cannot reach a caller that unboxes it into a NullPointerException.
   *
   * @param value the configured value
   * @return the parsed flag state, or empty when the value is neither affirmative nor negative
   */
  private static Optional<Boolean> parseBoolean(String value) {
    if (StringUtils.equalsAnyIgnoreCase(value, "true", "Y", "yes", "1")) {
      return Optional.of(Boolean.TRUE);
    }
    if (StringUtils.equalsAnyIgnoreCase(value, "false", "N", "no", "0")) {
      return Optional.of(Boolean.FALSE);
    }
    return Optional.empty();
  }

  private static <T> ProviderEvaluation<T> defaultResult(T defaultValue) {
    return ProviderEvaluation.<T>builder()
        .value(defaultValue)
        .reason(REASON_DEFAULT)
        .build();
  }

  private static <T> ProviderEvaluation<T> unsupportedType(T defaultValue) {
    return ProviderEvaluation.<T>builder()
        .value(defaultValue)
        .reason(REASON_DEFAULT)
        .errorCode(ErrorCode.TYPE_MISMATCH)
        .errorMessage("Only boolean flags are backed by local configuration")
        .build();
  }
}
