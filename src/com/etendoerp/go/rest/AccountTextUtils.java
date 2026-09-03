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

package com.etendoerp.go.rest;

import org.apache.commons.lang3.StringUtils;

/**
 * String helpers shared by the account DAL code: masking an identifier before it reaches a log, and
 * escaping a value before it is embedded in a LIKE pattern.
 *
 * <p>They live here rather than in {@code EtendoGoJwtDalHelper} because neither touches the
 * database — they are text transformations that happen to be used by queries and log lines. Moving
 * them out also keeps that class inside its method budget, which is what prompted the split.
 */
final class AccountTextUtils {

  private AccountTextUtils() {
  }

  /** Masks a username for logging, mirroring {@code EtendoGoJwtServlet.maskEmail}. */
  static String maskUsername(String username) {
    String trimmed = StringUtils.trimToNull(username);
    if (trimmed == null) {
      return "(unknown)";
    }
    int at = trimmed.indexOf('@');
    return at <= 0 ? trimmed.charAt(0) + "***" : trimmed.charAt(0) + "***" + trimmed.substring(at);
  }

  /**
   * Escapes the LIKE wildcards in a value so it matches literally.
   *
   * @param value
   *     the raw value to embed inside a LIKE pattern
   * @return the value with LIKE wildcards escaped for use with {@code escape '\'}
   */
  static String escapeLikeWildcards(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
