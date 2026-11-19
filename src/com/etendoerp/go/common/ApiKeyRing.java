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

package com.etendoerp.go.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * The keys that may call one server-to-server API, one per named caller. A request names its key
 * in the {@value #HEADER} header; the answer is the name of the caller the key belongs to.
 *
 * <p>Configured as a system property or, when it is blank, an environment variable: a
 * comma-separated list of {@code <name>:<sha256 hex of the key>}, e.g.
 * {@code web:9f86d0…,crm:2c26b4…}. Only hashes are configured, so a leaked configuration
 * hands out no key. Hash a new key with {@code printf %s '<key>' | shasum -a 256}. Revoke a key
 * by removing its entry; rotate it by replacing the hash.
 *
 * <p>No key configured means no caller is accepted: the API is closed until one is.
 *
 * <p>Each API has its own ring (its own property and variable), so a key handed to one API never
 * opens another: the signup API ({@code ETGO_SIGNUP_API_KEYS}) and the payments API
 * ({@code ETGO_PAYMENTS_API_KEYS}, ETP-6322).
 */
public final class ApiKeyRing {

  /** The request header that carries the key. */
  public static final String HEADER = "X-Api-Key";

  /** Caller names are recorded in VARCHAR(60) columns. */
  private static final int NAME_MAX_LENGTH = 60;

  private static final int SHA256_HEX_LENGTH = 64;

  private final String property;
  private final String environmentVariable;

  /**
   * Creates a ring read from its configuration on every call, so a changed key applies without a
   * restart wherever the property is set at runtime.
   *
   * @param property            the system property holding the entries
   * @param environmentVariable the environment variable read when the property is blank
   */
  public ApiKeyRing(String property, String environmentVariable) {
    this.property = property;
    this.environmentVariable = environmentVariable;
  }

  /**
   * The caller a key belongs to.
   *
   * @param rawKey the header's value, as sent
   * @return the key's name, or {@code null} when the key is missing or not configured
   */
  public String callerFor(String rawKey) {
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
        return StringUtils.left(name, NAME_MAX_LENGTH);
      }
    }
    return null;
  }

  private String configured() {
    String configured = System.getProperty(property);
    return StringUtils.isNotBlank(configured) ? configured : System.getenv(environmentVariable);
  }

  private static byte[] parseHex(String hex) {
    String normalized = StringUtils.trimToEmpty(hex).toLowerCase(Locale.ROOT);
    if (normalized.length() != SHA256_HEX_LENGTH) {
      return new byte[0];
    }
    try {
      return HexFormat.of().parseHex(normalized);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  /**
   * The SHA-256 digest of a text, the form keys are configured in.
   *
   * @param value the text, read as UTF-8
   * @return its digest
   */
  public static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
