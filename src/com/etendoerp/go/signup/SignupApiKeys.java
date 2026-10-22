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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * The keys that may call the signup API, one per channel (the marketing site's backend, the CRM,
 * an agent). A request names its key in the {@value #HEADER} header; the key's name is the
 * channel recorded on the account it creates.
 *
 * <p>Configured as {@code etgo.signup.api.keys} (system property) or
 * {@code ETGO_SIGNUP_API_KEYS} (environment): a comma-separated list of
 * {@code <name>:<sha256 hex of the key>}, e.g. {@code web:9f86d0…,crm:2c26b4…}. Only hashes are
 * configured, so a leaked configuration hands out no key. Hash a new key with
 * {@code printf %s '<key>' | shasum -a 256}. Revoke a key by removing its entry; rotate it by
 * replacing the hash.
 *
 * <p>No key configured means no caller is accepted: the API is closed until one is.
 */
final class SignupApiKeys {

  static final String HEADER = "X-Api-Key";
  static final String PROPERTY = "etgo.signup.api.keys";
  static final String ENV = "ETGO_SIGNUP_API_KEYS";

  /** {@code ETGO_ACCOUNT.SIGNUP_CHANNEL} is VARCHAR(60). */
  private static final int CHANNEL_MAX_LENGTH = 60;

  private SignupApiKeys() {
  }

  /**
   * The channel a key belongs to.
   *
   * @param rawKey the header's value, as sent
   * @return the key's name, or {@code null} when the key is missing or not configured
   */
  static String channelFor(String rawKey) {
    String key = StringUtils.trimToNull(rawKey);
    if (key == null) {
      return null;
    }
    byte[] presented = sha256(key);
    for (String entry : StringUtils.split(StringUtils.defaultString(configured()), ',')) {
      String name = StringUtils.trimToNull(StringUtils.substringBefore(entry, ":"));
      byte[] expected = parseHex(StringUtils.substringAfter(entry, ":"));
      // Constant time, so the response time does not tell how close a guess came.
      if (name != null && expected.length > 0 && MessageDigest.isEqual(presented, expected)) {
        return StringUtils.left(name, CHANNEL_MAX_LENGTH);
      }
    }
    return null;
  }

  private static String configured() {
    String configured = System.getProperty(PROPERTY);
    return StringUtils.isNotBlank(configured) ? configured : System.getenv(ENV);
  }

  private static byte[] parseHex(String hex) {
    String normalized = StringUtils.trimToEmpty(hex).toLowerCase(Locale.ROOT);
    if (normalized.length() != 64) {
      return new byte[0];
    }
    try {
      return HexFormat.of().parseHex(normalized);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
