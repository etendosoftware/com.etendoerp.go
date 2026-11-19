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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /**
   * Matches "Street[,] Number" — the overwhelmingly common order in the Spanish/European invoices
   * this flow handles (e.g. {@code "Calle Mayor 45"}, {@code "Av. Libertador 1234"}). The number
   * token may carry a single trailing letter (e.g. {@code "45B"}).
   */
  private static final Pattern TRAILING_NUMBER =
      Pattern.compile("(?s)(.+?)[\\s,]+(\\d+[A-Za-z]?)\\s*$");

  /** Matches "Number[,] Street" — the less common but still realistic reverse order. */
  private static final Pattern LEADING_NUMBER =
      Pattern.compile("(?s)^\\s*(\\d+[A-Za-z]?)[\\s,]+(.+)$");

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
    // ETP-5806 follow-up — `structured-address-for-sepa` mandatorily requires streetName,
    // buildingNumber and cityName on every NEW C_Location, but only for the account it is enabled
    // for (ContactsLocationAddressHandler#rejectUnusableCreatePayload). While it is off for this
    // account that check does not run, so the pre-ETP-5806 addressLine1-only body is still
    // perfectly valid and this parsing is skipped — not just unneeded but actively undesirable,
    // since it would send fields the handler does not expect from an unmigrated account. While it
    // is on, the OCR extraction only ever prints ONE combined free-text line (`vendor_address`),
    // so it is split into street + number the same way a human reading the invoice would; a blank
    // buildingNumber is left for StructuredAddressComposer#effectiveBuildingNumber's own "s/n"
    // fallback rather than invented here.
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
   * @param addressLine the raw, possibly blank, printed address line
   * @return a 2-element array: {@code [streetName, buildingNumber]}, each possibly blank, never
   *     {@code null}
   */
  static String[] splitStreetAndNumber(String addressLine) {
    String trimmed = StringUtils.trim(addressLine);
    if (StringUtils.isBlank(trimmed)) {
      return new String[] {"", ""};
    }
    Matcher trailing = TRAILING_NUMBER.matcher(trimmed);
    if (trailing.matches()) {
      String street = StringUtils.stripEnd(trailing.group(1).trim(), ", ");
      return new String[] {street, trailing.group(2)};
    }
    Matcher leading = LEADING_NUMBER.matcher(trimmed);
    if (leading.matches()) {
      return new String[] {leading.group(2).trim(), leading.group(1)};
    }
    return new String[] {trimmed, ""};
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
