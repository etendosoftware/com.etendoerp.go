/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the shared {@link PasswordPolicy} strength rules. */
class PasswordPolicyTest {

  @Test
  @DisplayName("A password meeting every rule is accepted")
  void acceptsStrongPassword() {
    assertTrue(PasswordPolicy.isStrong("Str0ng!Pass"));
    assertTrue(PasswordPolicy.isStrong("Aa1!aaaa"));
  }

  @Test
  @DisplayName("Null is rejected")
  void rejectsNull() {
    assertFalse(PasswordPolicy.isStrong(null));
  }

  @ParameterizedTest(name = "rejects weak password: \"{0}\"")
  @ValueSource(strings = {
      "Aa1!aa",      // too short (6 chars)
      "abc1!def",    // no uppercase
      "ABC1!DEF",    // no lowercase
      "Abcdef!!",    // no digit
      "Abcdef12",    // no special character
      "123",         // the trivial password from the ticket
      "a"            // single char
  })
  @DisplayName("Passwords missing any rule are rejected")
  void rejectsWeakPasswords(String password) {
    assertFalse(PasswordPolicy.isStrong(password));
  }

  @Test
  @DisplayName("Whitespace does not count as a special character")
  void whitespaceIsNotSpecial() {
    assertFalse(PasswordPolicy.isStrong("Abcdef12 "));
  }

  @Test
  @DisplayName("Exactly the minimum length is accepted when all rules pass")
  void acceptsMinimumLength() {
    assertTrue(PasswordPolicy.isStrong("Aa1!" + "aaaa"));
  }
}
