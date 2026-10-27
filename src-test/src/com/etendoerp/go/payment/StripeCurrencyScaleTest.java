/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs for the currency exponent table.
 *
 * <p>These pin the only thing that stands between the provider's integer minor-unit amount and a
 * correct price. The provider sends {@code 4900} and {@code "eur"} and nothing else — no exponent
 * — so the same integer is 49.00 EUR, 4900 JPY and 4.900 KWD. A wrong entry here does not fail:
 * it prices a plan a hundred or a thousand times off, silently, on a screen a customer reads.
 */
class StripeCurrencyScaleTest {

  @Test
  @DisplayName("a two-decimal currency is the ordinary case")
  void ordinaryCurrenciesHaveTwoDecimals() {
    assertAll(() -> assertEquals(2, StripeCurrencyScale.exponent("EUR")),
        () -> assertEquals(2, StripeCurrencyScale.exponent("USD")),
        () -> assertEquals(2, StripeCurrencyScale.exponent("GBP")));
  }

  @Test
  @DisplayName("a zero-decimal currency has no minor unit: the amount IS the price")
  void zeroDecimalCurrenciesAreNotDividedAtAll() {
    assertAll(() -> assertEquals(0, StripeCurrencyScale.exponent("JPY")),
        () -> assertEquals(0, StripeCurrencyScale.exponent("KRW")),
        () -> assertEquals(0, StripeCurrencyScale.exponent("CLP")),
        () -> assertEquals(0, StripeCurrencyScale.exponent("VND")),
        () -> assertEquals(0, StripeCurrencyScale.exponent("XOF")));
  }

  @Test
  @DisplayName("a three-decimal currency divides by a thousand, not a hundred")
  void threeDecimalCurrenciesUseThousandths() {
    assertAll(() -> assertEquals(3, StripeCurrencyScale.exponent("KWD")),
        () -> assertEquals(3, StripeCurrencyScale.exponent("BHD")),
        () -> assertEquals(3, StripeCurrencyScale.exponent("JOD")),
        () -> assertEquals(3, StripeCurrencyScale.exponent("OMR")),
        () -> assertEquals(3, StripeCurrencyScale.exponent("TND")));
  }

  @Test
  @DisplayName("the provider sends the code in lower case, so matching must ignore case")
  void matchingIgnoresCase() {
    // This is not a nicety: the provider's `currency` field is ALWAYS lower case, so a
    // case-sensitive table would silently return 2 for every currency, including JPY and KWD.
    assertAll(() -> assertEquals(0, StripeCurrencyScale.exponent("jpy")),
        () -> assertEquals(3, StripeCurrencyScale.exponent("kwd")),
        () -> assertEquals(0, StripeCurrencyScale.exponent("JpY")),
        () -> assertEquals(3, StripeCurrencyScale.exponent(" kwd ")));
  }

  @Test
  @DisplayName("an unknown or missing code defaults to two decimals")
  void unknownAndNullCodesDefaultToTwo() {
    // 2 is the right default, not a guess: every currency the provider has added since the
    // zero- and three-decimal lists were published has two decimals.
    assertAll(() -> assertEquals(2, StripeCurrencyScale.exponent("ZZZ")),
        () -> assertEquals(2, StripeCurrencyScale.exponent("")),
        () -> assertEquals(2, StripeCurrencyScale.exponent("   ")),
        () -> assertEquals(2, StripeCurrencyScale.exponent(null)));
  }
}
