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

package com.etendoerp.go.signup;

import com.etendoerp.go.common.ApiKeyRing;

/**
 * The keys that may call the signup API, one per channel (the marketing site's backend, the CRM,
 * an agent). A request names its key in the {@value #HEADER} header; the key's name is the
 * channel recorded on the account it creates.
 *
 * <p>Configured as {@code etgo.signup.api.keys} (system property) or
 * {@code ETGO_SIGNUP_API_KEYS} (environment). The format, hashing and revocation rules are the
 * ones of {@link ApiKeyRing}, shared with the payments API (ETP-6322), which has its own keys.
 *
 * <p>No key configured means no caller is accepted: the API is closed until one is.
 */
final class SignupApiKeys {

  static final String HEADER = ApiKeyRing.HEADER;
  static final String PROPERTY = "etgo.signup.api.keys";
  static final String ENV = "ETGO_SIGNUP_API_KEYS";

  private static final ApiKeyRing RING = new ApiKeyRing(PROPERTY, ENV);

  private SignupApiKeys() {
  }

  /**
   * The channel a key belongs to. {@code ETGO_ACCOUNT.SIGNUP_CHANNEL} is VARCHAR(60), the length
   * {@link ApiKeyRing} caps names at.
   *
   * @param rawKey the header's value, as sent
   * @return the key's name, or {@code null} when the key is missing or not configured
   */
  static String channelFor(String rawKey) {
    return RING.callerFor(rawKey);
  }

  static byte[] sha256(String value) {
    return ApiKeyRing.sha256(value);
  }
}
