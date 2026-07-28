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

package com.etendoerp.go.oauth2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Token-validity policy for the {@code authorization_code} grant.
 *
 * <p>Holds the user-selectable token validity ({@code validity_seconds}) bounds and the
 * normalization logic that clamps a requested value into the allowed range.
 */
final class OAuth2ValidityPolicy {

  private static final Logger log = LogManager.getLogger(OAuth2ValidityPolicy.class);

  // --- authorization_code grant: user-selectable token validity (validity_seconds) ---
  public static final long DEFAULT_AUTHORIZE_VALIDITY_SECONDS = 86_400; // 1 day
  public static final long MAX_AUTHORIZE_VALIDITY_SECONDS = 2_592_000; // 30 days
  public static final long MIN_AUTHORIZE_VALIDITY_SECONDS = 300; // 5 minutes
  public static final long VALIDITY_NO_EXPIRATION = 0;

  private OAuth2ValidityPolicy() {
  }

  /**
   * Normalize a user-requested {@code validity_seconds} value against the authorize policy.
   *
   * <p>Rule: {@code 0} means no expiration; missing/non-numeric/negative values (represented
   * by any value {@code < 0}) fall back to {@link #DEFAULT_AUTHORIZE_VALIDITY_SECONDS};
   * values above {@link #MAX_AUTHORIZE_VALIDITY_SECONDS} are clamped down; positive values
   * below {@link #MIN_AUTHORIZE_VALIDITY_SECONDS} are clamped up.
   *
   * @param requestedSeconds the raw requested validity, or a negative sentinel when absent
   * @return the normalized validity in seconds ({@code 0} = no expiration)
   */
  public static long normalizeValiditySeconds(long requestedSeconds) {
    if (requestedSeconds == VALIDITY_NO_EXPIRATION) {
      return VALIDITY_NO_EXPIRATION;
    }
    if (requestedSeconds < 0) {
      return DEFAULT_AUTHORIZE_VALIDITY_SECONDS;
    }
    if (requestedSeconds > MAX_AUTHORIZE_VALIDITY_SECONDS) {
      log.info("Requested validity_seconds={} exceeds max, clamping to {}",
          requestedSeconds, MAX_AUTHORIZE_VALIDITY_SECONDS);
      return MAX_AUTHORIZE_VALIDITY_SECONDS;
    }
    if (requestedSeconds < MIN_AUTHORIZE_VALIDITY_SECONDS) {
      log.info("Requested validity_seconds={} below min, clamping to {}",
          requestedSeconds, MIN_AUTHORIZE_VALIDITY_SECONDS);
      return MIN_AUTHORIZE_VALIDITY_SECONDS;
    }
    return requestedSeconds;
  }
}
