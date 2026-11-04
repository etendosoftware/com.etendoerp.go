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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * @covers com.etendoerp.go.rest.SignupPhone
 */
class SignupPhoneTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "+34 600123456|+34 600123456",
      "  +34   600 12 34 56 |+34 600 12 34 56",
      "+54 (11) 4567-8901|+54 (11) 4567-8901",
      "+1 555.0100|+1 555.0100",
  })
  void keepsAPhoneWithItsPrefix(String raw, String expected) {
    assertEquals(expected, SignupPhone.normalize(raw));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   ", "600123456", "+34600123456", "+34 600abc", "+34 12",
      "+12345 600123456", "+34 6001234567890123456789012345678901" })
  void dropsWhatIsNotAPhone(String raw) {
    assertNull(SignupPhone.normalize(raw));
  }
}
