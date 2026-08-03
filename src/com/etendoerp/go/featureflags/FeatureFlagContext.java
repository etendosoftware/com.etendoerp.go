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
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * Targeting information for a single flag evaluation, expressed without any OpenFeature type so
 * application code never depends on the flag vendor's API.
 *
 * <p>The targeting key is the account email, which matches the identity the web client uses as its
 * own targeting key. Both ends therefore land in the same percentage bucket for a given user.
 */
public final class FeatureFlagContext {

  /** Attribute carrying the AD_Client the evaluation is scoped to, when one is known. */
  public static final String ATTRIBUTE_CLIENT_ID = "clientId";

  private final String targetingKey;
  private final Map<String, String> attributes;

  private FeatureFlagContext(String targetingKey, Map<String, String> attributes) {
    this.targetingKey = targetingKey;
    this.attributes = attributes;
  }

  /**
   * Builds a context targeted at an account.
   *
   * @param accountEmail the authenticated account email; may be null when no account is known
   * @return a context carrying only the targeting key
   */
  public static FeatureFlagContext forAccount(String accountEmail) {
    return new FeatureFlagContext(StringUtils.trimToNull(accountEmail),
        Collections.emptyMap());
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
