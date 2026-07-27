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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.session;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Measured feature flag for accepting the legacy {@code Authorization: Bearer} path during the
 * migration to the cookie session (ETP-4575). Enabled by default; disable via the
 * {@code etgo.legacy.bearer.enabled} system property (or {@code ETGO_LEGACY_BEARER_ENABLED} env var)
 * set to {@code false} once the forced re-login window has closed. Every legacy use is counted and
 * logged so the operational owner can measure the window before switching it off.
 */
public final class GoLegacyBearer {

  private static final Logger log = LogManager.getLogger(GoLegacyBearer.class);
  private static final String PROPERTY = "etgo.legacy.bearer.enabled";
  private static final String ENV = "ETGO_LEGACY_BEARER_ENABLED";
  private static final AtomicLong USE_COUNT = new AtomicLong();

  private GoLegacyBearer() {
  }

  /**
   * @return {@code true} unless explicitly disabled via configuration (default: enabled)
   */
  public static boolean isEnabled() {
    String configured = System.getProperty(PROPERTY);
    if (StringUtils.isBlank(configured)) {
      configured = System.getenv(ENV);
    }
    if (StringUtils.isBlank(configured)) {
      return true;
    }
    return !"false".equalsIgnoreCase(configured.trim());
  }

  /** Record (count + log) one legacy Bearer authentication, for measuring the migration window. */
  public static void recordUse() {
    long count = USE_COUNT.incrementAndGet();
    log.info("Legacy Bearer authentication accepted (cumulative count={})", count);
  }

  /** @return the number of legacy Bearer authentications recorded since startup */
  public static long useCount() {
    return USE_COUNT.get();
  }

  static void resetUseCount() {
    USE_COUNT.set(0);
  }
}
