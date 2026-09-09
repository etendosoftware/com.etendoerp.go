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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SpanishTaxIdValidator} (ETP-5190).
 *
 * <p>The cases here are deliberately the SAME ones as the browser-side
 * {@code tools/app-shell/src/lib/__tests__/taxIdValidation.test.js}. The two implementations
 * must agree on every value; a shared case list is what turns a divergence into a failing test
 * instead of a user being refused by one side and accepted by the other.
 */
class SpanishTaxIdValidatorTest {

  /*
   * Check-digit-correct identifiers of each accepted shape. The first two are the SAME base
   * number written with each of the two legal control characters, which is what makes the
   * "both representations" test meaningful.
   */
  private static final String VALID_CIF_LETTER = "B1234567D";
  private static final String VALID_CIF_DIGIT = "B12345674";
  private static final String VALID_DNI = "12345678Z";

  @Nested
  @DisplayName("accepts every shape a real tenant can have")
  class Accepts {

    @Test
    @DisplayName("accepts a CIF under either representation of its control character")
    void acceptsBothCifControlForms() {
      // Which one an entity uses depends on the leading letter, so accepting only one half
      // would reject real companies.
      assertEquals(SpanishTaxIdValidator.Result.VALID,
          SpanishTaxIdValidator.validate(VALID_CIF_LETTER));
      assertEquals(SpanishTaxIdValidator.Result.VALID,
          SpanishTaxIdValidator.validate(VALID_CIF_DIGIT));
      assertEquals(SpanishTaxIdValidator.Result.VALID,
          SpanishTaxIdValidator.validate("A58818501"));
    }

    @Test
    @DisplayName("accepts a natural-person DNI")
    void acceptsPersonDni() {
      // REGRESSION GUARD: the signup wizard offers businessType `freelancer`, and an autónomo
      // has a personal DNI, not a company CIF. Validating only the CIF form would have
      // rejected every freelancer in the product.
      assertTrue(SpanishTaxIdValidator.isValid(VALID_DNI));
    }

    @ParameterizedTest
    @ValueSource(strings = { "X1234567L", "Y1234567X", "Z1234567R" })
    @DisplayName("accepts a NIE under each of its three prefixes")
    void acceptsNie(String nie) {
      assertTrue(SpanishTaxIdValidator.isValid(nie));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    @DisplayName("accepts a blank value — requiredness is a separate mechanism")
    void acceptsBlank(String blank) {
      assertEquals(SpanishTaxIdValidator.Result.VALID, SpanishTaxIdValidator.validate(blank));
    }
  }

  @Nested
  @DisplayName("rejects a wrong check digit")
  class RejectsCheckDigit {

    @ParameterizedTest
    @ValueSource(strings = { "B12345679", "B1234567A", "12345678A", "X1234567X" })
    @DisplayName("rejects a well-shaped value whose control character does not match")
    void rejectsWrongCheckDigit(String taxId) {
      assertEquals(SpanishTaxIdValidator.Result.BAD_CHECK_DIGIT,
          SpanishTaxIdValidator.validate(taxId));
    }

    @Test
    @DisplayName("applies the X/Y/Z to 0/1/2 substitution rather than skipping it")
    void appliesNiePrefixSubstitution() {
      // Y1234567X is valid and X1234567X is not: the same digits and the same control letter,
      // separated only by the prefix substitution. Skipping it would accept both.
      assertTrue(SpanishTaxIdValidator.isValid("Y1234567X"));
      assertFalse(SpanishTaxIdValidator.isValid("X1234567X"));
    }

    @Test
    @DisplayName("keeps the two failure reasons distinct")
    void separatesFormatFromCheckDigit() {
      // "this is not a NIF at all" and "this is a NIF with the wrong last character" send the
      // user to different places, so they must not collapse into one message.
      assertEquals(SpanishTaxIdValidator.Result.BAD_CHECK_DIGIT,
          SpanishTaxIdValidator.validate("B12345679"));
      assertEquals(SpanishTaxIdValidator.Result.BAD_FORMAT,
          SpanishTaxIdValidator.validate("BB1234567"));
    }
  }

  @Nested
  @DisplayName("rejects a wrong shape")
  class RejectsFormat {

    @ParameterizedTest
    @ValueSource(strings = { "I1234567A", "O1234567A", "T1234567A" })
    @DisplayName("rejects a leading letter that is not a CIF entity type")
    void rejectsNonEntityLetter(String taxId) {
      // X, Y and Z are deliberately absent: those are NIE prefixes and fall through to the NIE
      // rule instead of being format errors.
      assertEquals(SpanishTaxIdValidator.Result.BAD_FORMAT,
          SpanishTaxIdValidator.validate(taxId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "B1234567", "B123456789", "1234567Z", "123456789Z", "12345678" })
    @DisplayName("rejects the wrong number of digits, or a missing control character")
    void rejectsWrongLength(String taxId) {
      assertEquals(SpanishTaxIdValidator.Result.BAD_FORMAT,
          SpanishTaxIdValidator.validate(taxId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "not a nif", "ES12345678Z", "DE123456789" })
    @DisplayName("rejects free text and another country's VAT number")
    void rejectsForeignAndFreeText(String taxId) {
      // Note ES12345678Z: the VIES-style country-prefixed form is NOT what AD_OrgInfo holds,
      // and accepting it would let a value through that no fiscal module can read.
      assertEquals(SpanishTaxIdValidator.Result.BAD_FORMAT,
          SpanishTaxIdValidator.validate(taxId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "---", "...", " - . - " })
    @DisplayName("rejects a value made entirely of the separators normalize() strips")
    void rejectsSeparatorsOnly(String taxId) {
      // REGRESSION GUARD. These normalize to the empty string, and "empty" is VALID — so
      // without the explicit blank-vs-normalized-empty distinction in validate() they were
      // waved through. The Organization window's required-field check passes them too (it only
      // tests for blankness), so "---" would have been stored as a NIF.
      assertEquals(SpanishTaxIdValidator.Result.BAD_FORMAT,
          SpanishTaxIdValidator.validate(taxId));
    }
  }

  @Nested
  @DisplayName("normalize — what the user is allowed to paste")
  class Normalize {

    @ParameterizedTest
    @ValueSource(strings = { "b1234567d", "12345678z" })
    @DisplayName("is case-insensitive")
    void uppercases(String taxId) {
      assertTrue(SpanishTaxIdValidator.isValid(taxId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "B-1234567D", "B.1234567D", " B 1234 567D ", "12.345.678-Z" })
    @DisplayName("strips the separators people paste in")
    void stripsSeparators(String taxId) {
      // A NIF copied out of a document commonly carries dots or a hyphen. Rejecting on
      // punctuation whose problem the user cannot see is worse than normalizing it.
      assertTrue(SpanishTaxIdValidator.isValid(taxId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "B/1234567D", "B_1234567D" })
    @DisplayName("does not strip characters that make the value wrong")
    void keepsRealFormatErrors(String taxId) {
      assertEquals(SpanishTaxIdValidator.Result.BAD_FORMAT,
          SpanishTaxIdValidator.validate(taxId));
    }

    @Test
    @DisplayName("never returns null")
    void neverReturnsNull() {
      assertEquals("", SpanishTaxIdValidator.normalize(null));
      assertEquals("", SpanishTaxIdValidator.normalize("   "));
    }
  }

  @Nested
  @DisplayName("the rejection messages the frontend maps")
  class Messages {

    @Test
    @DisplayName("are the exact strings backendErrors.js keys on")
    void messagesAreStable() {
      // These literals are the KEY in the frontend's BACKEND_ERROR_MAP; changing one here
      // without changing it there silently reverts the message to untranslated English. Both
      // callers (the signup endpoint and OrganizationInformationHandler) read them from here,
      // so there is one copy to keep in sync rather than three.
      assertEquals("The tax ID is not a valid NIF, CIF or NIE.", SpanishTaxIdValidator.ERR_FORMAT);
      assertEquals("The tax ID check digit does not match. Review the number.",
          SpanishTaxIdValidator.ERR_CHECK_DIGIT);
      assertEquals("ES", SpanishTaxIdValidator.SPAIN_COUNTRY_CODE);
    }
  }
}
