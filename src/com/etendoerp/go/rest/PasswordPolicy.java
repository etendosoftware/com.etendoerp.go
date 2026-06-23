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

import java.util.regex.Pattern;

/**
 * Shared password strength policy for local credential endpoints (register,
 * password-reset confirm, change-password). The backend is the security
 * boundary: the frontend mirrors these rules for UX only.
 *
 * <p>Minimum requirements: at least 8 characters, including at least one
 * uppercase letter, one lowercase letter, one digit, and one special
 * character.</p>
 */
public final class PasswordPolicy {

  /** Minimum number of characters a password must contain. */
  public static final int MIN_LENGTH = 8;

  /** Stable, machine-readable error code returned when a password is too weak. */
  public static final String ERROR_CODE = "WEAK_PASSWORD";

  /** Developer-facing message (English, stable). */
  public static final String MESSAGE = "Password does not meet minimum strength requirements.";

  /** End-user-facing message describing the requirements (translate on the frontend). */
  public static final String USER_MESSAGE =
      "Password must include at least 8 characters, uppercase and lowercase letters, "
          + "a number, and a special character.";

  private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
  private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
  private static final Pattern DIGIT = Pattern.compile("\\d");
  // Any character that is neither ASCII alphanumeric nor whitespace counts as "special".
  private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9\\s]");

  private PasswordPolicy() {
  }

  /**
   * Returns {@code true} when the password satisfies every minimum strength rule.
   * A {@code null} password is considered weak.
   *
   * @param password
   *     the raw password to evaluate
   * @return whether the password is strong enough to be accepted
   */
  public static boolean isStrong(String password) {
    return password != null
        && password.length() >= MIN_LENGTH
        && UPPERCASE.matcher(password).find()
        && LOWERCASE.matcher(password).find()
        && DIGIT.matcher(password).find()
        && SPECIAL.matcher(password).find();
  }
}
