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
package com.etendoerp.go.onboarding.pool;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.common.GoRuntimeProperties;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Configuration of the pre-provisioned tenant pool (ETP-5389).
 *
 * <p>The switch is the module's feature-flag stack ({@link GoFeatureFlags}, flag
 * {@value GoFeatureFlags#FLAG_ONBOARDING_TENANT_POOL}), resolved like every other backend flag:
 * ConfigCat when an SDK key is configured, otherwise {@code etendo.go.flags.onboarding-tenant-pool}
 * / {@code ETGO_FLAG_ONBOARDING_TENANT_POOL}. Absent means <b>off</b>, and off means onboarding is
 * exactly the classic from-scratch path. The numeric knobs are plain runtime properties
 * ({@link GoRuntimeProperties}), since the flag stack only carries booleans.
 */
public final class TenantPoolConfig {

  /** How many READY tenants the filler keeps on hand. */
  public static final String PROP_SIZE = "etendo.go.onboarding.pool.size";
  public static final String ENV_SIZE = "ETGO_ONBOARDING_POOL_SIZE";
  public static final int DEFAULT_SIZE = 3;
  /** Upper bound on {@link #PROP_SIZE}: each pooled tenant is a full client in the instance. */
  static final int MAX_SIZE = 20;

  /** A READY tenant older than this is retired instead of claimed. */
  public static final String PROP_MAX_AGE_HOURS = "etendo.go.onboarding.pool.maxAgeHours";
  public static final String ENV_MAX_AGE_HOURS = "ETGO_ONBOARDING_POOL_MAX_AGE_HOURS";
  public static final int DEFAULT_MAX_AGE_HOURS = 168;

  /** A PROVISIONING row older than this is presumed dead (the JVM died mid-run). */
  public static final String PROP_LEASE_MINUTES = "etendo.go.onboarding.pool.leaseMinutes";
  public static final String ENV_LEASE_MINUTES = "ETGO_ONBOARDING_POOL_LEASE_MINUTES";
  public static final int DEFAULT_LEASE_MINUTES = 60;

  /** The only onboarding combination supported today; anything else takes the classic path. */
  public static final String SUPPORTED_CURRENCY = "EUR";
  public static final String SUPPORTED_COUNTRY = "ES";
  public static final String SUPPORTED_LANGUAGE = "es_ES";

  /** Prefix of a pooled tenant's placeholder client/org name until it is claimed. */
  public static final String PLACEHOLDER_PREFIX = "POOL-";

  private TenantPoolConfig() {
  }

  /**
   * @param accountEmail the account onboarding, or {@code null} for the filler (no account)
   * @return whether pool-based onboarding is switched on
   */
  public static boolean isEnabled(String accountEmail) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_ONBOARDING_TENANT_POOL,
        FeatureFlagContext.forAccount(accountEmail));
  }

  /** @return the configured pool size, clamped to {@code [0, MAX_SIZE]} */
  public static int poolSize() {
    return clamp(GoRuntimeProperties.readInt(PROP_SIZE, ENV_SIZE, DEFAULT_SIZE), 0, MAX_SIZE);
  }

  /** @return the maximum age of a READY tenant, in hours (at least 1) */
  public static int maxAgeHours() {
    return Math.max(1, GoRuntimeProperties.readInt(PROP_MAX_AGE_HOURS, ENV_MAX_AGE_HOURS,
        DEFAULT_MAX_AGE_HOURS));
  }

  /** @return the provisioning lease, in minutes (at least 5) */
  public static int leaseMinutes() {
    return Math.max(5, GoRuntimeProperties.readInt(PROP_LEASE_MINUTES, ENV_LEASE_MINUTES,
        DEFAULT_LEASE_MINUTES));
  }

  /**
   * Whether a request can be served from the pool. Every pooled tenant is built EUR / ES / es_ES,
   * so any other combination has to be provisioned from scratch.
   */
  public static boolean supports(String currencyIso, String countryCode, String language) {
    return SUPPORTED_CURRENCY.equalsIgnoreCase(StringUtils.trimToEmpty(currencyIso))
        && SUPPORTED_COUNTRY.equalsIgnoreCase(StringUtils.trimToEmpty(countryCode))
        && SUPPORTED_LANGUAGE.equals(StringUtils.trimToEmpty(language));
  }

  /** @return {@code true} for a client name reserved for an unclaimed pooled tenant */
  public static boolean isPlaceholderName(String clientName) {
    return clientName != null && clientName.startsWith(PLACEHOLDER_PREFIX);
  }

  static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
