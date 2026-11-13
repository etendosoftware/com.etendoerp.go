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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SEPA Creditor Identifier rules: normalisation, the three rejection reasons, construction
 * with the ISO 7064 MOD 97-10 check digits and the positional split helpers.
 *
 * <p>The vectors were computed independently of the class (rearrange {@code nationalId + country +
 * "00"}, letters A=10..Z=35, {@code 98 - n mod 97}), so a regression in the algorithm cannot
 * silently move the expectation with it. {@code ES02000B49} / {@code ES99000B49} pin the parity
 * contract with {@code SEPAUtil}: 99 is congruent with 02 mod 97, and a {@code mod 97 == 1} test
 * would accept both, but only the recomputed 02 is what the SEPA module ever emits.
 *
 * @covers com.etendoerp.go.schemaforge.SepaCreditorId
 */
class SepaCreditorIdTest {

  // ---------------------------------------------------------------- build / check digits

  @ParameterizedTest(name = "{0}/{1}/{2} -> {3}")
  @CsvSource({
      "ES, 000, B12345678, ES97000B12345678",
      "ES, ZZZ, X1234567L, ES59ZZZX1234567L",
      "DE, 000, 0000000001, DE090000000000001",
      "FR, ZZZ, 123456, FR72ZZZ123456",
      "NL, 000, 1, NL920001",
  })
  void buildProducesTheKnownVector(String country, String suffix, String taxId, String expected) {
    String ci = SepaCreditorId.build(country, suffix, taxId);

    assertEquals(expected, ci);
    assertTrue(SepaCreditorId.isValid(ci), "a built CI always validates: " + ci);
  }

  @Test
  void computeCheckDigitsZeroPadsASingleDigitResult() {
    assertEquals("09", SepaCreditorId.computeCheckDigits("DE", "0000000001"));
    assertEquals("02", SepaCreditorId.computeCheckDigits("ES", "B49"));
  }

  @ParameterizedTest
  @CsvSource({ "E, B49", "ES1, B49", "es, B49", "ES, ''", "ES, B-49" })
  void computeCheckDigitsRejectsPartsThatAreNotNormalised(String country, String nationalId) {
    assertThrows(IllegalArgumentException.class,
        () -> SepaCreditorId.computeCheckDigits(country, nationalId));
  }

  @Test
  void computeCheckDigitsRejectsNullParts() {
    assertThrows(IllegalArgumentException.class,
        () -> SepaCreditorId.computeCheckDigits(null, "B49"));
    assertThrows(IllegalArgumentException.class,
        () -> SepaCreditorId.computeCheckDigits("ES", null));
  }

  @Test
  void buildNormalisesLowercaseAndSeparatorsInEveryPart() {
    assertEquals("ES97000B12345678", SepaCreditorId.build(" es ", "0-0-0", "b-1234.5678"));
  }

  @Test
  void buildDefaultsABlankSuffixTo000() {
    assertEquals("ES97000B12345678", SepaCreditorId.build("ES", null, "B12345678"));
    assertEquals("ES97000B12345678", SepaCreditorId.build("ES", "  ", "B12345678"));
    assertEquals("000", SepaCreditorId.DEFAULT_SUFFIX);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   ", "-.-" })
  void buildReturnsNullOnABlankTaxId(String taxId) {
    assertNull(SepaCreditorId.build("ES", "000", taxId));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "E", "ESP", "E1" })
  void buildReturnsNullOnACountryThatIsNotTwoLetters(String country) {
    assertNull(SepaCreditorId.build(country, "000", "B12345678"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "00", "0000", "Z" })
  void buildReturnsNullOnASuffixThatIsNotThreeAlphanumerics(String suffix) {
    assertNull(SepaCreditorId.build("ES", suffix, "B12345678"));
  }

  @Test
  void buildReturnsNullOnATaxIdLongerThan28Characters() {
    String longest = "1234567890123456789012345678";
    assertEquals(SepaCreditorId.NATIONAL_ID_MAX_LENGTH, longest.length());

    String ci = SepaCreditorId.build("ES", "000", longest);

    assertEquals(SepaCreditorId.MAX_LENGTH, ci.length(), "28 characters fill Max35Text exactly");
    assertNull(SepaCreditorId.build("ES", "000", longest + "9"));
  }

  // ---------------------------------------------------------------- normalize

  @Test
  void normalizeUppercasesAndStripsEverySeparator() {
    assertEquals("ES97000B12345678", SepaCreditorId.normalize(" es-97 000.b1234 5678 "));
    assertEquals("ES59ZZZX1234567L", SepaCreditorId.normalize("es59/zzz/x1234567l"));
  }

  @Test
  void normalizeNeverReturnsNull() {
    assertEquals("", SepaCreditorId.normalize(null));
    assertEquals("", SepaCreditorId.normalize(" - . "));
  }

  // ---------------------------------------------------------------- validate

  @ParameterizedTest
  @ValueSource(strings = { "ES97000B12345678", "ES59ZZZX1234567L", "DE090000000000001",
      "FR72ZZZ123456", "NL920001", "ES02000B49", "DE98ZZZ09999999999" })
  void validateAcceptsAWellFormedCiWithTheRecomputedDigits(String ci) {
    SepaCreditorId.Result result = SepaCreditorId.validate(ci);

    assertTrue(result.isValid(), ci);
    assertNull(result.getReason());
    assertNull(result.getExpectedCheckDigits());
    assertEquals(ci, result.getNormalized());
  }

  @Test
  void validateNormalisesLowercaseInputWithSeparators() {
    SepaCreditorId.Result result = SepaCreditorId.validate("es97 000-b1234.5678");

    assertTrue(result.isValid());
    assertEquals("ES97000B12345678", result.getNormalized());
  }

  @Test
  void validateRejectsTheCongruentPairThatSepaUtilNeverGenerates() {
    // 99 = 02 (mod 97): a mod-97 test would accept it; the recomputed digits are 02.
    SepaCreditorId.Result result = SepaCreditorId.validate("ES99000B49");

    assertFalse(result.isValid());
    assertEquals(SepaCreditorId.Reason.INVALID_CHECK_DIGITS, result.getReason());
    assertEquals("02", result.getExpectedCheckDigits());
    assertEquals("ETGO_SepaCreditorIdInvalidCheckDigits", result.getReason().getMessageKey());
  }

  @Test
  void validateRejectsWrongCheckDigitsAndNamesTheExpectedOnes() {
    SepaCreditorId.Result result = SepaCreditorId.validate("ES98000B12345678");

    assertEquals(SepaCreditorId.Reason.INVALID_CHECK_DIGITS, result.getReason());
    assertEquals("97", result.getExpectedCheckDigits());
    assertEquals("ES98000B12345678", result.getNormalized());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   ", "-./" })
  void validateAnswersBlankWhenNothingIsLeftAfterNormalising(String raw) {
    SepaCreditorId.Result result = SepaCreditorId.validate(raw);

    assertFalse(result.isValid());
    assertEquals(SepaCreditorId.Reason.BLANK, result.getReason());
    assertEquals("", result.getNormalized());
    assertEquals("ETGO_SepaCreditorIdRequired", result.getReason().getMessageKey());
    assertFalse(SepaCreditorId.isValid(raw));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ES97000", // no national identifier
      "E197000B12345678", // country with a digit
      "ESA7000B12345678", // check digits with a letter
      "ES97000B1234567890123456789012345678", // national id of 29 characters (36 in total)
      "1297000B12345678" })
  void validateRejectsAMalformedCi(String raw) {
    SepaCreditorId.Result result = SepaCreditorId.validate(raw);

    assertEquals(SepaCreditorId.Reason.INVALID_FORMAT, result.getReason(), raw);
    assertNull(result.getExpectedCheckDigits());
    assertEquals("ETGO_SepaCreditorIdInvalidFormat", result.getReason().getMessageKey());
  }

  // ---------------------------------------------------------------- split helpers

  @Test
  void splitHelpersReturnEachPositionalPart() {
    String ci = "ES97000B12345678";

    assertEquals("ES", SepaCreditorId.countryOf(ci));
    assertEquals("97", SepaCreditorId.checkDigitsOf(ci));
    assertEquals("000", SepaCreditorId.suffixOf(ci));
    assertEquals("B12345678", SepaCreditorId.nationalIdOf(ci));
  }

  @Test
  void splitHelpersReturnNullForAnythingShorterThanThePartAskedFor() {
    assertNull(SepaCreditorId.countryOf(null));
    assertNull(SepaCreditorId.countryOf("E"));
    assertNull(SepaCreditorId.checkDigitsOf("ES9"));
    assertNull(SepaCreditorId.suffixOf("ES9700"));
    assertNull(SepaCreditorId.nationalIdOf("ES97000"));
    assertNull(SepaCreditorId.nationalIdOf(null));
    assertEquals("ES", SepaCreditorId.countryOf("ES"));
    assertEquals("97", SepaCreditorId.checkDigitsOf("ES97"));
    assertEquals("000", SepaCreditorId.suffixOf("ES97000"));
  }
}
