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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;

/**
 * Mints and hashes the opaque portal token that IS the link's credential.
 *
 * <p><b>One random token per link (ETP-6209).</b> Each emailed link gets 256 bits drawn from
 * {@link SecureRandom}, written into that one email and never rebuilt: a later send mints a new
 * link with a new token, and a renewal does too. Only the SHA-256 hash is stored, so a database
 * dump (backup, replica, log) hands over no usable credential.
 *
 * <p>The MVP (ETP-5267) derived the token with an HMAC over the row id, keyed by
 * {@code etendo.go.portal.tokenSecret}, because its single link per Business Partner had to be
 * rebuilt identically on every send. A per-send link never has to be rebuilt, so the derivation
 * and that secret are gone (plan {@code 2026-11-11-customer-portal-sales-documents.md} §A.1).
 */
final class PortalTokens {

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private PortalTokens() {
  }

  /**
   * Draws a new opaque token.
   *
   * @return 256 random bits, URL-safe base64 without padding
   */
  static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Hashes a token into the value stored in {@code etgo_portal_link.token_hash} and used as the
   * lookup key on every portal request.
   *
   * <p>Base64 of the SHA-256 digest, matching {@code CompanyInvitationService#hashToken} so the
   * module has one spelling of "hashed token at rest".
   *
   * @param token the opaque token
   * @return the hash, or {@code null} when {@code token} is blank
   */
  static String hash(String token) {
    String normalized = StringUtils.trimToNull(token);
    if (normalized == null) {
      return null;
    }
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance(HASH_ALGORITHM)
          .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(HASH_ALGORITHM + " is required for portal tokens", e);
    }
  }
}
