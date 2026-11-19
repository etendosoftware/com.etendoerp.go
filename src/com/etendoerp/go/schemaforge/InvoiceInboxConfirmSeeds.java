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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer;

/**
 * What a NEW vendor is created with when an inbox document is confirmed (ETP-6051): the Java twin of
 * the single-invoice OCR flow's seed, which the SPA builds in {@code buildOcrContactSeed} and
 * {@code buildOcrContactAddressSeed} ({@code CreateContactModalAdapter.jsx}) from the keys of
 * {@code createPrefilledFrom} ({@code ocrDocTypes.js}) and resolves the country of with
 * {@code lib/defaultCountry.js}. Pure: no database, so the mapping is pinned by a backend-only JSON fixture
 * ({@code invoice-inbox-vendor-seed.json}); the SPA repo has no copy of it.
 *
 * <p>The rules, those of the popup: the header carries the trimmed {@code name}, and only when
 * present {@code taxID}, {@code etgoEmail} and {@code etgoPhone}; the unique {@code Proveedor}
 * category is always seeded (the caller resolves it) and, when the {@code contact-category-type} flag
 * is off, {@code vendor = true} is sent too, exactly as the popup does; {@code customer} is never
 * sent. The address carries the
 * trimmed {@code address}, {@code postalCode} and {@code city} and the country by NAME: a blank
 * printed country is Spain (the aliases {@code España} / {@code Spain}); a printed one is matched to
 * the country list by ISO code, name or translated name, accents and case ignored, and when nothing
 * matches there is no country: <b>never a guess</b>, because a wrong country nobody sees is worse
 * than none. The region is never seeded. A vendor whose document prints none of address, postal code,
 * city or country has no usable address and none is invented.</p>
 */
final class InvoiceInboxConfirmSeeds {

  static final String K_ADDRESS = "address";
  static final String K_POSTAL_CODE = "postalCode";
  static final String K_CITY = "city";
  static final String K_COUNTRY = "country";
  static final String K_EMAIL = "email";
  static final String K_PHONE = "phone";

  /** The labels that identify the default country, as {@code DEFAULT_COUNTRY_LABEL_ALIASES}. */
  private static final List<String> DEFAULT_COUNTRY_ALIASES = List.of("espana", "spain");

  private InvoiceInboxConfirmSeeds() {
  }

  /** A country of the list a printed one is matched against. */
  static final class CountryRow {
    private final String id;
    private final String isoCode;
    private final String name;

    CountryRow(String id, String isoCode, String name) {
      this.id = id;
      this.isoCode = isoCode;
      this.name = name;
    }
  }

  /**
   * The body of the {@code contacts/businessPartner} create.
   *
   * @param extracted    the {@code vendor.extracted} block of the document
   * @param categoryType whether the {@code contact-category-type} flag is on
   * @param categoryId   the id of the unique {@code Proveedor} category, {@code null} when none
   * @return the body
   * @throws JSONException when the object cannot be built
   */
  static JSONObject vendorBody(JSONObject extracted, boolean categoryType, String categoryId)
      throws JSONException {
    JSONObject body = new JSONObject().put("name", text(extracted, "name"));
    putIfPresent(body, "taxID", text(extracted, "taxId"));
    putIfPresent(body, "etgoEmail", text(extracted, K_EMAIL));
    putIfPresent(body, "etgoPhone", text(extracted, K_PHONE));
    // As the SPA's popup: the category of a purchase document is ALWAYS seeded
    // (resolveContactCategorySeed); with the flag off the seed also carries vendor = true
    // (seedDocumentType), with it on the type comes from the category alone.
    putIfPresent(body, "businessPartnerCategory", categoryId);
    if (!categoryType) {
      body.put("vendor", true);
    }
    return body;
  }

  /**
   * Whether the document prints something to build an address from: any of the address, the postal
   * code, the city or the country (the rule of {@code buildOcrContactAddressSeed}).
   *
   * @param extracted the {@code vendor.extracted} block
   * @return {@code true} when there is a usable address
   */
  static boolean hasUsableAddress(JSONObject extracted) {
    return StringUtils.isNotBlank(text(extracted, K_ADDRESS))
        || StringUtils.isNotBlank(text(extracted, K_POSTAL_CODE))
        || StringUtils.isNotBlank(text(extracted, K_CITY))
        || StringUtils.isNotBlank(text(extracted, K_COUNTRY));
  }

  /**
   * The country id of the address: Spain when the document prints none, the matching one when it
   * prints one, {@code null} when it prints one that matches nothing.
   *
   * @param printed   the country as printed, may be blank
   * @param countries the countries to match against
   * @return the id, or {@code null}
   */
  static String countryId(String printed, List<CountryRow> countries) {
    String wanted = fold(printed);
    if (wanted.isEmpty()) {
      return firstByNames(countries, DEFAULT_COUNTRY_ALIASES);
    }
    if (DEFAULT_COUNTRY_ALIASES.contains(wanted)) {
      // "España" and "Spain" are Spain whatever the translation the tenant's country list carries
      // (the popup's DEFAULT_COUNTRY_LABEL_ALIASES): a local DB can list Spain as "Spain" in es_ES.
      return firstByNames(countries, DEFAULT_COUNTRY_ALIASES);
    }
    for (CountryRow row : countries) {
      if (wanted.equalsIgnoreCase(row.isoCode) || wanted.equals(fold(row.name))) {
        return row.id;
      }
    }
    return null;
  }

  private static String firstByNames(List<CountryRow> countries, List<String> aliases) {
    for (String alias : aliases) {
      for (CountryRow row : countries) {
        if (alias.equals(fold(row.name))) {
          return row.id;
        }
      }
    }
    return null;
  }

  /**
   * The body of the {@code contacts/locationAddress} create; the {@code businessPartner} is the
   * parent of the operation.
   *
   * <p>ETP-5806 follow-up: the {@code structured-address-for-sepa} flag mandates {@code
   * streetName}, {@code buildingNumber} and {@code cityName} on every new {@code C_Location}, but
   * only for the accounts where it is enabled, see {@link
   * ContactsLocationAddressHandler#rejectUnusableCreatePayload}. When the flag is off for the
   * current account, that validation does not run, so the original {@code addressLine1}-only body
   * is still valid and the street/number split below is skipped on purpose, not just unneeded:
   * sending those extra fields to an account whose handler does not expect them would be wrong.
   * When the flag is on, the OCR extraction only ever prints one combined free-text line for the
   * vendor address, so it is split into street and number the way a person reading the invoice
   * would; a blank {@code buildingNumber} is left for {@link
   * StructuredAddressComposer#effectiveBuildingNumber}'s own {@code "s/n"} fallback rather than
   * invented here.
   *
   * @param extracted the {@code vendor.extracted} block
   * @param countryId the resolved country (see {@link #countryId})
   * @return the body
   * @throws JSONException when the object cannot be built
   */
  static JSONObject addressBody(JSONObject extracted, String countryId) throws JSONException {
    JSONObject body = new JSONObject().put(K_COUNTRY, countryId);
    String addressLine = text(extracted, K_ADDRESS);
    putIfPresent(body, "addressLine1", addressLine);
    putIfPresent(body, K_POSTAL_CODE, text(extracted, K_POSTAL_CODE));
    putIfPresent(body, "cityName", text(extracted, K_CITY));
    if (StructuredAddressComposer.isEnabled(GoAccountResolver.currentAccountEmail())) {
      String[] streetAndNumber = splitStreetAndNumber(addressLine);
      putIfPresent(body, "streetName", streetAndNumber[0]);
      putIfPresent(body, "buildingNumber", streetAndNumber[1]);
    }
    return body;
  }

  /**
   * Splits a free-text OCR address line into a Street Name and a Building Number (ETP-5806
   * follow-up). Pragmatic, scoped to the invoice-inbox OCR use case, not a general address parser:
   *
   * <ol>
   *   <li>"Street[,] Number" (e.g. {@code "Calle Mayor 45"}) — the overwhelmingly common order for
   *       the Spanish/European vendor addresses this flow sees.</li>
   *   <li>"Number[,] Street" (e.g. {@code "45 Main Street"}) — the reverse order, still realistic.</li>
   *   <li>No number token found at either end — the whole line becomes the Street Name and the
   *       Building Number is left blank, rather than failing: a blank Building Number with a
   *       present Street Name falls back to {@code "s/n"} downstream (see
   *       {@link StructuredAddressComposer#effectiveBuildingNumber}).</li>
   * </ol>
   *
   * <p>Implemented as a manual character scan rather than two regexes. The natural regex shape
   * for each order — an unbounded free-text run immediately followed by an unbounded
   * separator run, e.g. {@code (.+?)[\s,]+(\d+[A-Za-z]?)} — has two adjacent, non-possessive
   * repetitions that can both match the same whitespace/comma characters, which is the textbook
   * polynomial-backtracking shape (CWE-1333): on adversarial input such as a long run of
   * separators with no number, the engine retries the split point up to {@code O(n)} times, each
   * retry rescanning the remaining separator run, for {@code O(n^2)} total work. The OCR text this
   * method parses is extracted from a scanned document and not meaningfully length- or
   * content-bounded, so it is treated as adversarial input here. A single forward/backward scan is
   * {@code O(n)} with no backtracking possible, and reproduces the exact split semantics the two
   * regexes had.
   *
   * @param addressLine the raw, possibly blank, printed address line
   * @return a 2-element array: {@code [streetName, buildingNumber]}, each possibly blank, never
   *     {@code null}
   */
  static String[] splitStreetAndNumber(String addressLine) {
    String trimmed = StringUtils.trim(addressLine);
    if (StringUtils.isBlank(trimmed)) {
      return new String[] {"", ""};
    }
    String[] trailing = trailingNumberSplit(trimmed);
    if (trailing != null) {
      return trailing;
    }
    String[] leading = leadingNumberSplit(trimmed);
    if (leading != null) {
      return leading;
    }
    return new String[] {trimmed, ""};
  }

  /**
   * The "Street[,] Number" order: a trailing run of digits with an optional single trailing
   * letter, preceded by a non-empty run of whitespace/comma separators, preceded by at least one
   * more character. Mirrors the full-string match of the former
   * {@code (.+?)[\s,]+(\d+[A-Za-z]?)\s*$} regex, without its backtracking shape.
   *
   * @param trimmed the already-trimmed, non-blank address line
   * @return {@code [street, number]}, or {@code null} when the line does not end in that shape
   */
  private static String[] trailingNumberSplit(String trimmed) {
    int length = trimmed.length();
    int numberEnd = length;
    if (numberEnd > 0 && isAsciiLetter(trimmed.charAt(numberEnd - 1))) {
      numberEnd--;
    }
    int digitsStart = numberEnd;
    while (digitsStart > 0 && isAsciiDigit(trimmed.charAt(digitsStart - 1))) {
      digitsStart--;
    }
    if (digitsStart == numberEnd) {
      return null;
    }
    int sepStart = digitsStart;
    while (sepStart > 0 && isSeparator(trimmed.charAt(sepStart - 1))) {
      sepStart--;
    }
    if (sepStart == digitsStart || sepStart == 0) {
      return null;
    }
    String street = StringUtils.stripEnd(trimmed.substring(0, sepStart).trim(), ", ");
    String number = trimmed.substring(digitsStart, length);
    return new String[] {street, number};
  }

  /**
   * The "Number[,] Street" order: a leading run of digits with an optional single trailing
   * letter, followed by a non-empty run of whitespace/comma separators, followed by at least one
   * more character. Mirrors the full-string match of the former
   * {@code ^\s*(\d+[A-Za-z]?)[\s,]+(.+)$} regex, without its backtracking shape.
   *
   * @param trimmed the already-trimmed, non-blank address line
   * @return {@code [street, number]}, or {@code null} when the line does not start in that shape
   */
  private static String[] leadingNumberSplit(String trimmed) {
    int length = trimmed.length();
    int digitsStart = 0;
    while (digitsStart < length && isWhitespace(trimmed.charAt(digitsStart))) {
      digitsStart++;
    }
    int numberEnd = digitsStart;
    while (numberEnd < length && isAsciiDigit(trimmed.charAt(numberEnd))) {
      numberEnd++;
    }
    if (numberEnd == digitsStart) {
      return null;
    }
    if (numberEnd < length && isAsciiLetter(trimmed.charAt(numberEnd))) {
      numberEnd++;
    }
    int sepEnd = numberEnd;
    while (sepEnd < length && isSeparator(trimmed.charAt(sepEnd))) {
      sepEnd++;
    }
    if (sepEnd == numberEnd || sepEnd == length) {
      return null;
    }
    String number = trimmed.substring(digitsStart, numberEnd);
    String street = trimmed.substring(sepEnd).trim();
    return new String[] {street, number};
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
  }

  /** Matches regex {@code \s} (ASCII, default flags): space, tab, newline, vertical tab, form
   * feed or carriage return. */
  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == (char) 0x0B || c == '\f' || c == '\r';
  }

  private static boolean isSeparator(char c) {
    return isWhitespace(c) || c == ',';
  }

  /** Lowercase with the accents removed, trimmed: the comparison of a printed country. */
  static String fold(String value) {
    String decomposed = Normalizer.normalize(StringUtils.defaultString(value).trim(),
        Normalizer.Form.NFD);
    return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
  }

  private static void putIfPresent(JSONObject body, String key, String value)
      throws JSONException {
    if (StringUtils.isNotBlank(value)) {
      body.put(key, value);
    }
  }

  private static String text(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? ((String) value).trim() : "";
  }
}
