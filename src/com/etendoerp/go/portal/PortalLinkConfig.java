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

package com.etendoerp.go.portal;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * The portal link's tunable numbers (ETP-6209, plan §A.1 and §A.4), each read from
 * {@code gradle.properties}/{@code Openbravo.properties} or its environment variable.
 *
 * <p>Read on every use rather than cached: they are cheap reads, and a value changed for a QA run
 * takes effect on the next request.
 */
public final class PortalLinkConfig {

  /** How long a link lives after its email left. */
  public static final String PROP_TTL_MINUTES = "etendo.go.portal.link.ttlMinutes";
  public static final String ENV_TTL_MINUTES = "ETGO_PORTAL_LINK_TTL_MINUTES";
  /** 48 hours — the product rule (ETP-6209 story item 3). */
  static final long DEFAULT_TTL_MINUTES = 48L * 60L;

  /** An expired link is renewed at most once per this many minutes. */
  public static final String PROP_RENEWAL_COOLDOWN_MINUTES =
      "etendo.go.portal.renewal.cooldownMinutes";
  public static final String ENV_RENEWAL_COOLDOWN_MINUTES =
      "ETGO_PORTAL_RENEWAL_COOLDOWN_MINUTES";
  static final long DEFAULT_RENEWAL_COOLDOWN_MINUTES = 60L;

  /** Renewals per Business Partner per rolling 24 hours. {@code 0} means no limit. */
  public static final String PROP_RENEWAL_MAX_PER_DAY = "etendo.go.portal.renewal.maxPerDay";
  public static final String ENV_RENEWAL_MAX_PER_DAY = "ETGO_PORTAL_RENEWAL_MAX_PER_DAY";
  static final long DEFAULT_RENEWAL_MAX_PER_DAY = 5L;

  private static final Logger log = LogManager.getLogger(PortalLinkConfig.class);

  private PortalLinkConfig() {
  }

  /**
   * @return the link lifetime; a non-positive or malformed override falls back to 48 hours
   */
  public static Duration ttl() {
    return Duration.ofMinutes(readPositive(PROP_TTL_MINUTES, ENV_TTL_MINUTES,
        DEFAULT_TTL_MINUTES));
  }

  /**
   * @return the per-link renewal cool-down; a non-positive or malformed override falls back to
   *     60 minutes
   */
  public static Duration renewalCooldown() {
    return Duration.ofMinutes(readPositive(PROP_RENEWAL_COOLDOWN_MINUTES,
        ENV_RENEWAL_COOLDOWN_MINUTES, DEFAULT_RENEWAL_COOLDOWN_MINUTES));
  }

  /**
   * Renewals allowed per Business Partner per rolling 24 hours.
   *
   * <p>{@code 0} is honoured and means <em>no limit</em> — unlike the email throttle ceilings,
   * where a non-positive value falls back to the default. "No limit" is a legitimate product answer
   * here, so it has to be expressible. A negative or malformed value falls back to the default.
   *
   * @return the ceiling, {@code 0} for none
   */
  public static long renewalMaxPerDay() {
    String configured = ConfigPropertyReader.readConfigValue(PROP_RENEWAL_MAX_PER_DAY,
        ENV_RENEWAL_MAX_PER_DAY, null);
    if (StringUtils.isBlank(configured)) {
      return DEFAULT_RENEWAL_MAX_PER_DAY;
    }
    try {
      long parsed = Long.parseLong(configured.trim());
      if (parsed >= 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // falls through to the warning below
    }
    log.warn("Ignoring invalid {}={}, using {}", PROP_RENEWAL_MAX_PER_DAY, configured,
        DEFAULT_RENEWAL_MAX_PER_DAY);
    return DEFAULT_RENEWAL_MAX_PER_DAY;
  }

  private static long readPositive(String property, String env, long defaultValue) {
    String configured = ConfigPropertyReader.readConfigValue(property, env, null);
    if (StringUtils.isBlank(configured)) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(configured.trim());
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // falls through to the warning below
    }
    log.warn("Ignoring invalid {}={}, using {}", property, configured, defaultValue);
    return defaultValue;
  }
}
