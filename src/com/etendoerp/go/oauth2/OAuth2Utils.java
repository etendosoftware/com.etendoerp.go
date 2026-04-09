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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for OAuth2 token and credential operations.
 * Provides hashing, token generation, and validation helpers.
 */
public final class OAuth2Utils {

  private static final Logger log = LogManager.getLogger(OAuth2Utils.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int TOKEN_BYTE_LENGTH = 32;
  private static final int SALT_BYTE_LENGTH = 16;
  private static final int CLIENT_ID_HEX_LENGTH = 16;
  private static final String CLIENT_ID_PREFIX = "etgo-";
  private static final String HASH_ALGORITHM = "SHA-256";
  private static final String SALT_SEPARATOR = ":";
  private static final String AUTH_CODE_PREFIX = "ac-";

  private OAuth2Utils() {
    // prevent instantiation
  }

  /**
   * Generate a cryptographically secure random token.
   * Uses SecureRandom, returns 32-byte hex-encoded string (64 chars).
   *
   * @return hex-encoded random token (64 characters)
   */
  public static String generateSecureToken() {
    byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
    SECURE_RANDOM.nextBytes(bytes);
    return bytesToHex(bytes);
  }

  /**
   * Hash a client secret for secure storage.
   * Uses SHA-256 with a random 16-byte salt. The output format is "salt:hash"
   * where both salt and hash are hex-encoded.
   * @param plainSecret the plaintext secret to hash
   * @return salted hash in format "hexSalt:hexHash"
   */
  public static String hashSecret(String plainSecret) {
    byte[] salt = new byte[SALT_BYTE_LENGTH];
    SECURE_RANDOM.nextBytes(salt);
    String hexSalt = bytesToHex(salt);
    String hexHash = sha256Hex(hexSalt + plainSecret);
    return hexSalt + SALT_SEPARATOR + hexHash;
  }

  /**
   * Verify a plaintext secret against a salted SHA-256 hash.
   *
   * @param plainSecret  the plaintext secret to verify
   * @param hashedSecret the stored hash in "hexSalt:hexHash" format
   * @return true if the secret matches
   */
  public static boolean verifySecret(String plainSecret, String hashedSecret) {
    if (plainSecret == null || hashedSecret == null) {
      return false;
    }
    int separatorIndex = hashedSecret.indexOf(SALT_SEPARATOR);
    if (separatorIndex < 0) {
      log.warn("Invalid hashed secret format: missing salt separator");
      return false;
    }
    String hexSalt = hashedSecret.substring(0, separatorIndex);
    String storedHash = hashedSecret.substring(separatorIndex + 1);
    String computedHash = sha256Hex(hexSalt + plainSecret);
    return constantTimeEquals(storedHash, computedHash);
  }

  /**
   * Hash an access token using SHA-256 for O(1) database lookup.
   * Returns lowercase hex string (64 chars).
   *
   * @param accessToken the plaintext access token
   * @return SHA-256 hex digest of the token
   */
  public static String hashToken(String accessToken) {
    return sha256Hex(accessToken);
  }

  /**
   * Check if a token expiration timestamp has passed.
   *
   * @param expiresAt the expiration timestamp
   * @return true if the token is expired (expiresAt is before now), false if still valid
   */
  public static boolean isTokenExpired(Date expiresAt) {
    if (expiresAt == null) {
      return true;
    }
    return new Date().after(expiresAt);
  }

  /**
   * Generate a new OAuth2 client_id.
   * Format: "etgo-" followed by 16 random hex characters.
   *
   * @return client ID string (e.g., "etgo-a1b2c3d4e5f6a7b8")
   */
  public static String generateClientId() {
    return CLIENT_ID_PREFIX + generateSecureToken().substring(0, CLIENT_ID_HEX_LENGTH);
  }

  /**
   * Generate a cryptographically secure authorization code (32 bytes, 64 hex chars).
   *
   * @return authorization code string prefixed for log and storage differentiation
   */
  public static String generateAuthCode() {
    return AUTH_CODE_PREFIX + generateSecureToken();
  }

  /**
   * Verify a PKCE S256 code_challenge against a code_verifier.
   * S256: code_challenge = BASE64URL(SHA256(ASCII(code_verifier)))
   *
   * @param codeVerifier  the original code_verifier sent in the token request
   * @param codeChallenge the code_challenge sent in the authorize request
   * @return true if the verifier matches the challenge
   */
  public static boolean verifyCodeChallenge(String codeVerifier, String codeChallenge) {
    if (codeVerifier == null || codeChallenge == null) {
      return false;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
      byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
      String computed = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
      return constantTimeEquals(computed, codeChallenge);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Compute SHA-256 hex digest of the input string.
   */
  private static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return bytesToHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the Java spec; this should never happen
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Convert a byte array to a lowercase hex string.
   */
  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * Constant-time string comparison to prevent timing attacks.
   */
  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }
}
