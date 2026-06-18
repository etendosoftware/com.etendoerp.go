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
    if (password == null || password.length() < MIN_LENGTH) {
      return false;
    }
    boolean hasUpper = false;
    boolean hasLower = false;
    boolean hasDigit = false;
    boolean hasSpecial = false;
    for (int i = 0; i < password.length(); i++) {
      char c = password.charAt(i);
      if (Character.isUpperCase(c)) {
        hasUpper = true;
      } else if (Character.isLowerCase(c)) {
        hasLower = true;
      } else if (Character.isDigit(c)) {
        hasDigit = true;
      } else if (!Character.isWhitespace(c)) {
        hasSpecial = true;
      }
    }
    return hasUpper && hasLower && hasDigit && hasSpecial;
  }
}
