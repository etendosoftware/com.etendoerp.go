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

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * Resolves the window during which an identical prior send is a duplicate (ETP-5484).
 */
final class EmailDuplicateWindow {

  static final String PROP_SECONDS = "etendo.go.email.duplicate.window.seconds";
  static final String ENV_SECONDS = "ETGO_EMAIL_DUPLICATE_WINDOW_SECONDS";
  static final long DEFAULT_SECONDS = 60L;
  private static final long MAX_SECONDS = 24L * 3600L;

  private EmailDuplicateWindow() {
  }

  /** Reads the configured window in seconds, falling back to the default when invalid. */
  static long resolveSeconds() {
    return parseSeconds(ConfigPropertyReader.readConfigValue(PROP_SECONDS, ENV_SECONDS, null));
  }

  /**
   * Parses the configured window. Blank, non-numeric and non-positive values fall back to the
   * default (a non-positive window would silently disable double-send protection); large values
   * are capped so the millisecond conversion cannot overflow.
   */
  static long parseSeconds(String configured) {
    if (StringUtils.isBlank(configured)) {
      return DEFAULT_SECONDS;
    }
    try {
      long parsed = Long.parseLong(configured.trim());
      return parsed > 0 ? Math.min(parsed, MAX_SECONDS) : DEFAULT_SECONDS;
    } catch (NumberFormatException e) {
      return DEFAULT_SECONDS;
    }
  }
}
