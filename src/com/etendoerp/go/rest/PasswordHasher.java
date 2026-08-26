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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * ETP-4829: extracted from {@link EtendoGoJwtServlet#hashPassword}, which now delegates here.
 * Single source of truth for the {@code etgo_account.password_hash} algorithm — anything that
 * writes an {@code etgo_account} row with a real, verifiable password (self-registration,
 * password reset/change, company-invitation {@code register-and-accept}) must hash through this
 * class, never re-implement the salt/digest logic, or {@link EtendoGoJwtServlet#verifyPassword}
 * won't be able to check it back at login. (ETP-4830 removed the admin-set-password bypass that
 * used to be this class's other caller — {@code EtendoGoAccountProvisioning} no longer exists.)
 */
public final class PasswordHasher {

  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int SALT_BYTES = 16;

  private PasswordHasher() {
  }

  /**
   * Hash a plaintext password using SHA-256 with a random salt.
   * Returns "base64(salt):base64(hash)" so the salt can be recovered for verification.
   *
   * @param password the plaintext password to hash
   * @return "base64(salt):base64(hash)"
   */
  public static String hash(String password) {
    try {
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[SALT_BYTES];
      random.nextBytes(salt);

      MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
      md.update(salt);
      byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));

      String saltB64 = Base64.getEncoder().encodeToString(salt);
      String hashB64 = Base64.getEncoder().encodeToString(digest);
      return saltB64 + ":" + hashB64;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
