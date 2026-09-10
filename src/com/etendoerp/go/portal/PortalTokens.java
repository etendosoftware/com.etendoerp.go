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
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * Mints and hashes the opaque portal token that IS the Business Partner's credential.
 *
 * <p><b>The token is derived, not drawn, and that is a deliberate correction to the plan.</b> The
 * plan (§3 of {@code docs/plans/2026-09-10-bp-self-service-portal.md}) asks for two things that
 * cannot both hold of a random value: <em>"only its SHA-256 hash is ever persisted"</em> and
 * <em>"every subsequent invoice to the same BP reuses the same link — never rotates on its own"</em>.
 * A {@code SecureRandom} value drawn at mint time can satisfy either one, never both: the second
 * send has to rebuild the same link, and if the row holds only a hash there is nothing to rebuild it
 * from. Storing the raw token instead would satisfy the reuse rule by giving up the one property the
 * hashing rule exists for — a database dump (backup, replica, log) would hand over every live
 * portal credential.
 *
 * <p>So the token is {@code HMAC-SHA256(secret, "etgo-portal:v1:" + accessId)}: still 256 opaque
 * bits, still not a JWT, still only a hash in the row. What changes is where the entropy lives — in
 * one server-side secret ({@value #PROP_TOKEN_SECRET}) rather than in the row. That keeps both
 * invariants:
 * <ul>
 *   <li><b>Stable and bookmarkable.</b> Same row ⇒ same token, on every send, forever.</li>
 *   <li><b>Only the hash is persisted.</b> The row is useless on its own; deriving a token needs
 *       the secret, which never enters the database.</li>
 *   <li><b>Revoke-then-reissue rotates.</b> The derivation is keyed on the row id, and reissuing
 *       creates a NEW row (the old one stays, revoked, for audit — plan §3), so the reissued token
 *       cannot equal the revoked one.</li>
 * </ul>
 *
 * <p><b>Unset secret ⇒ no link, never a weak link.</b> {@link #deriveToken} answers empty rather
 * than falling back to something guessable, mirroring
 * {@code DocumentDownloadTokenService#createDownloadLink}, which already refuses to build a
 * document download link without its own configured secret. The portal link is then simply absent
 * from the email — the same observable outcome as either gate of plan §2.5 being closed.
 *
 * <p>Should the plan's literal wording ever be preferred over its reuse rule, this class is the only
 * place that decides: replace {@link #deriveToken} with a {@code SecureRandom} draw and the token
 * becomes per-mint random, at the cost of the second send carrying a different link.
 */
final class PortalTokens {

  /** Server-side HMAC key. Absent ⇒ no portal link is ever built. */
  static final String PROP_TOKEN_SECRET = "etendo.go.portal.tokenSecret";
  static final String ENV_TOKEN_SECRET = "ETGO_PORTAL_TOKEN_SECRET";

  private static final Logger log = LogManager.getLogger(PortalTokens.class);

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final String HASH_ALGORITHM = "SHA-256";
  /**
   * Version tag inside the HMAC input. It exists so a future change of derivation scheme can
   * coexist with links already in customers' inboxes instead of invalidating them silently.
   */
  private static final String DERIVATION_PREFIX = "etgo-portal:v1:";

  private PortalTokens() {
  }

  /**
   * Indicates whether a portal token can be built at all in this environment.
   *
   * @return {@code true} when the HMAC secret is configured
   */
  static boolean isSecretConfigured() {
    return readSecret() != null;
  }

  /**
   * Derives the stable opaque token of one portal access row.
   *
   * @param accessId the {@code etgo_portal_access} row id the token belongs to
   * @return the URL-safe token, or empty when the row id is blank or the secret is not configured
   */
  static Optional<String> deriveToken(String accessId) {
    String normalizedId = StringUtils.trimToNull(accessId);
    String secret = readSecret();
    if (normalizedId == null || secret == null) {
      return Optional.empty();
    }
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] derived = mac.doFinal(
          (DERIVATION_PREFIX + normalizedId).getBytes(StandardCharsets.UTF_8));
      return Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(derived));
    } catch (Exception e) {
      // Never propagates: a failure here must degrade to "no link", exactly like an unset secret.
      // The row id is safe to log; the token and the secret are not, and neither is logged.
      log.error("Could not derive the portal token for access row {}", normalizedId, e);
      return Optional.empty();
    }
  }

  /**
   * Hashes a token into the value stored in {@code etgo_portal_access.token_hash} and used as the
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

  private static String readSecret() {
    return StringUtils.trimToNull(
        ConfigPropertyReader.readConfigValue(PROP_TOKEN_SECRET, ENV_TOKEN_SECRET, null));
  }
}
