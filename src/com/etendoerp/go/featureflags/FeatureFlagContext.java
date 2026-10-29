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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * Targeting information for a single flag evaluation, expressed without any OpenFeature type so
 * application code never depends on the flag vendor's API.
 *
 * <p>The targeting key is the account email. Every hosted provider buckets percentage rollouts on
 * the targeting key, and each one names the email attribute its rules match differently, so the
 * address is published once per provider spelling — see {@link #ATTRIBUTE_EMAIL} and
 * {@link #ATTRIBUTE_EMAIL_LOWERCASE}. Note the web client does <em>not</em> share this key yet
 * (the {@code targeting-key-divergence} item in {@code GoFeatureFlags}).
 */
public final class FeatureFlagContext {

  /** Attribute carrying the AD_Client the evaluation is scoped to, when one is known. */
  public static final String ATTRIBUTE_CLIENT_ID = "clientId";

  /**
   * Attribute carrying the account email, <b>in addition to</b> the targeting key.
   *
   * <p><b>The capitalisation is load-bearing and the duplication is deliberate.</b> A hosted
   * provider turns this context into its own user object: the targeting key becomes the
   * <em>identifier</em>, and only an attribute named exactly {@code Email} becomes the
   * <em>email</em> (ConfigCat's {@code ContextTransformer} matches that literal; anything else
   * lands as a custom attribute). Publishing the address in both places means a targeting rule
   * written against Email and one written against Identifier both match. Sending it only as the
   * targeting key is the trap: a dashboard rule on Email — the obvious one to write — would match
   * nobody, and a flag that silently resolves false is indistinguishable from one deliberately off
   * (ETP-4966).
   */
  public static final String ATTRIBUTE_EMAIL = "Email";

  /**
   * The same account email under the attribute name Datadog Feature Flags targeting rules use
   * (ETP-5751).
   *
   * <p>Datadog has no reserved user fields: the targeting key drives bucketing and every rule
   * matches a flat attribute <em>by its exact name</em>, and the rules in the Datadog project are
   * written against lowercase {@code email}. Attribute names are case-sensitive, so
   * {@value #ATTRIBUTE_EMAIL} alone would match none of them and the flag would read as off for
   * everyone — the ETP-4966 failure again. Both spellings are published while ConfigCat remains a
   * fallback arm; drop {@value #ATTRIBUTE_EMAIL} only when ConfigCat is retired.
   *
   * <p><b>The value is lowercased</b> ({@link Locale#ROOT}): rule values are matched
   * case-sensitively too, and every value in the Datadog project is lowercase, so a mixed-case
   * {@code ETGO_ACCOUNT} email would otherwise match no rule. The targeting key and
   * {@value #ATTRIBUTE_EMAIL} keep the address as stored.
   */
  public static final String ATTRIBUTE_EMAIL_LOWERCASE = "email";

  private final String targetingKey;
  private final Map<String, String> attributes;

  private FeatureFlagContext(String targetingKey, Map<String, String> attributes) {
    this.targetingKey = targetingKey;
    this.attributes = attributes;
  }

  /**
   * Builds the context of an <b>environment-wide</b> evaluation: no account, no targeting key, no
   * attributes. Every rule that targets a user or an email resolves "not matched" against it, so
   * only operational switches (kill switches, startup and scheduled jobs with no user) may use it.
   *
   * <p>A flag that can be rolled out to specific users must NOT be evaluated with this: the SPA
   * sends the email and shows the feature while the backend never matches the rule. Use
   * {@link #forAccount(String)} with the resolved account email instead. Prefer this factory over
   * {@code forAccount(null)}: it states the intent and is greppable.
   *
   * @return a context with no targeting information
   */
  public static FeatureFlagContext environment() {
    return forAccount(null);
  }

  /**
   * Builds a context targeted at an account, carrying the email as the targeting key and as both
   * the {@value #ATTRIBUTE_EMAIL} (ConfigCat) and {@value #ATTRIBUTE_EMAIL_LOWERCASE} (Datadog)
   * attributes — see those constants for why each.
   *
   * @param accountEmail the authenticated account email; may be null when no account is known
   * @return a context targeted at the account, or one with no targeting key at all when the email
   *     is blank (which every provider must treat as "not this account", never as "everyone")
   */
  public static FeatureFlagContext forAccount(String accountEmail) {
    String normalized = StringUtils.trimToNull(accountEmail);
    // with() ignores a blank value, so an unknown account yields a context carrying neither the
    // key nor the attribute rather than an empty-string identity that could match a rule.
    return new FeatureFlagContext(normalized, Collections.emptyMap())
        .with(ATTRIBUTE_EMAIL, normalized)
        .with(ATTRIBUTE_EMAIL_LOWERCASE,
            normalized == null ? null : normalized.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns a copy of this context with an extra targeting attribute. Blank keys and values are
   * ignored so callers can pass optional identifiers without null-checking first.
   *
   * @param key attribute name
   * @param value attribute value
   * @return a context including the attribute, or this context when either argument is blank
   */
  public FeatureFlagContext with(String key, String value) {
    String trimmedKey = StringUtils.trimToNull(key);
    String trimmedValue = StringUtils.trimToNull(value);
    if (trimmedKey == null || trimmedValue == null) {
      return this;
    }
    Map<String, String> merged = new LinkedHashMap<>(attributes);
    merged.put(trimmedKey, trimmedValue);
    return new FeatureFlagContext(targetingKey, Collections.unmodifiableMap(merged));
  }

  public String getTargetingKey() {
    return targetingKey;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }
}
