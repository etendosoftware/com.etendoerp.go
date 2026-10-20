/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * How many decimal places a currency has, which is what turns the provider's integer minor-unit
 * amount into a real price.
 *
 * <p>This has to be a table because <b>the exponent is not in the provider's API response</b>. A
 * price comes back as {@code unit_amount: 4900} plus {@code currency: "eur"} and nothing else, so
 * there is no field to read: 4900 is 49.00 EUR, 4900 JPY, and 4.900 KWD. Dividing by 100 is wrong
 * for two of those three.
 *
 * <p>Unknown currencies default to 2, which is correct for every currency the provider has added
 * since this list was written and for every currency it is likely to add — the zero-decimal and
 * three-decimal sets are small, stable and historical. So a currency missing from the table is a
 * correct answer, not a gap; only a currency that is genuinely zero- or three-decimal and missing
 * from the sets below would be wrong, and those sets are the provider's own published lists.
 */
public final class StripeCurrencyScale {

  private static final int DEFAULT_EXPONENT = 2;

  /** Currencies with no minor unit at all: the amount IS the price. */
  private static final Set<String> ZERO_DECIMAL = unmodifiableSetOf("BIF", "CLP", "DJF", "GNF",
      "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

  /** Currencies whose minor unit is a thousandth, mostly Gulf dinars. */
  private static final Set<String> THREE_DECIMAL = unmodifiableSetOf("BHD", "JOD", "KWD", "OMR",
      "TND");

  private StripeCurrencyScale() {
  }

  /**
   * Returns the number of decimal places of a currency.
   *
   * @param isoCode ISO 4217 code in any case, e.g. {@code eur} or {@code EUR}; may be null
   * @return 0, 2 or 3; 2 for a null, blank or unrecognized code
   */
  public static int exponent(String isoCode) {
    if (isoCode == null) {
      return DEFAULT_EXPONENT;
    }
    String normalized = isoCode.trim().toUpperCase(Locale.ROOT);
    if (ZERO_DECIMAL.contains(normalized)) {
      return 0;
    }
    if (THREE_DECIMAL.contains(normalized)) {
      return 3;
    }
    return DEFAULT_EXPONENT;
  }

  private static Set<String> unmodifiableSetOf(String... codes) {
    return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(codes)));
  }
}
