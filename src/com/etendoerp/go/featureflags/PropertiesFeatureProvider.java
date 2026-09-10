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
 * <p><b>Per-account targeting (ETP-5267).</b> A flag can additionally name the accounts it is on
 * for, as a comma-separated allowlist of {@code ETGO_ACCOUNT} emails:
 *
 * <pre>
 *   etendo.go.flags.my-flag          = false                  # the default for everyone
 *   etendo.go.flags.my-flag.emails   = someone@example.com, other@example.com
 * </pre>
 *
 * <p>The allowlist is matched against the evaluation context's targeting key — the account email
 * {@link FeatureFlagContext#forAccount(String)} carries — trimmed and case-insensitively, because AD
 * data is dirty. A match resolves <b>true</b>; anything else falls through to the flag's own boolean
 * value exactly as before. The two are therefore an <b>OR, never an AND</b>: naming an account is
 * sufficient on its own, so a targeted rollout needs one property set, not two.
 *
 * <p><b>Nothing configured ⇒ false, and an unlisted account ⇒ false.</b> There is no wildcard, and
 * an empty allowlist never means "everyone" — a blank entry cannot match, since the targeting key is
 * non-blank by the time it is compared. A flag with no {@code .emails} property behaves exactly as
 * it did before this existed, which is what keeps every other flag unaffected.
 *
 * <p>Evaluation is purely local: no network call, no background thread, no polling, nothing to be
 * unreachable. Changing a flag — or its allowlist — is a configuration change, so both take effect
 * on restart rather than instantly.
 *
 * <p>This is a deliberate first step. Replacing it with a hosted control plane (Mixpanel Feature
 * Flags with local evaluation and polling, per the team plan) is a change to
 * {@link GoFeatureFlags#createProvider()} alone; nothing outside this package moves. Percentage
 * rollouts and rule-based segments still need that control plane — the allowlist above covers
 * "these named accounts", not "20% of users".
 *
 * <p>Only boolean flags are backed by configuration. The other OpenFeature types return the caller's
 * default rather than pretending to resolve, so a future typed flag fails visibly instead of
 * silently reading as an empty string or zero.
 */
public class PropertiesFeatureProvider implements dev.openfeature.sdk.FeatureProvider {

  static final String PROPERTY_PREFIX = "etendo.go.flags.";
  static final String ENV_PREFIX = "ETGO_FLAG_";

  /**
   * Suffix that turns a flag key into its per-account allowlist key, so {@code bp-portal-link}
   * reads from {@code etendo.go.flags.bp-portal-link.emails} and
   * {@code ETGO_FLAG_BP_PORTAL_LINK_EMAILS}. Deliberately derived from the flag key rather than
   * configured separately: there is nothing to keep in sync, and the existing precedence and
   * env-name mapping are reused untouched.
   */
  static final String EMAIL_ALLOWLIST_SUFFIX = ".emails";

  private static final String NAME = "etendo-go-properties";
  private static final String REASON_STATIC = "STATIC";
  private static final String REASON_DEFAULT = "DEFAULT";
  private static final String REASON_TARGETING_MATCH = "TARGETING_MATCH";

  @Override
  public Metadata getMetadata() {
    return () -> NAME;
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue,
      EvaluationContext ctx) {
    if (isAccountAllowlisted(key, ctx)) {
      return ProviderEvaluation.<Boolean>builder()
          .value(Boolean.TRUE)
          .reason(REASON_TARGETING_MATCH)
          .build();
    }
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
   * Decides whether this evaluation's targeting key is named in the flag's per-account allowlist.
   *
   * <p>Every path out of here that is not a positive match answers {@code false}, which hands the
   * decision back to the flag's own boolean value — so a flag with no allowlist, an evaluation with
   * no account, and an account that simply is not listed are all indistinguishable from the
   * behaviour before allowlists existed. That is the backward-compatibility guarantee.
   *
   * <p>Comparison is trimmed and case-insensitive because these emails are typed by hand into
   * configuration on one side and read out of {@code ETGO_ACCOUNT} on the other. A blank allowlist
   * entry (a trailing comma, {@code a,,b}) can never match: {@code targetingKey} is non-blank by the
   * time it is compared, so there is no path where an empty allowlist means "everyone".
   *
   * @param flagKey the OpenFeature flag key
   * @param ctx the evaluation context, which may be null or carry no targeting key
   * @return {@code true} only when the context's targeting key is listed for this flag
   */
  private static boolean isAccountAllowlisted(String flagKey, EvaluationContext ctx) {
    String key = StringUtils.trimToNull(flagKey);
    if (key == null || ctx == null) {
      return false;
    }
    String targetingKey = StringUtils.trimToNull(ctx.getTargetingKey());
    if (targetingKey == null) {
      return false;
    }
    String allowlist = readFlagValue(key + EMAIL_ALLOWLIST_SUFFIX);
    if (StringUtils.isBlank(allowlist)) {
      return false;
    }
    for (String entry : StringUtils.split(allowlist, ',')) {
      if (StringUtils.equalsIgnoreCase(StringUtils.trimToNull(entry), targetingKey)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Maps a flag key to its environment-variable name: uppercased, with every character that is not
   * a letter or digit replaced by an underscore, so {@code some.other-flag} becomes
   * {@code ETGO_FLAG_SOME_OTHER_FLAG}.
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
