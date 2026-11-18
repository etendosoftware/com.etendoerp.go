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
   * @param extracted the {@code vendor.extracted} block
   * @param countryId the resolved country (see {@link #countryId})
   * @return the body
   * @throws JSONException when the object cannot be built
   */
  static JSONObject addressBody(JSONObject extracted, String countryId) throws JSONException {
    JSONObject body = new JSONObject().put(K_COUNTRY, countryId);
    putIfPresent(body, "addressLine1", text(extracted, K_ADDRESS));
    putIfPresent(body, K_POSTAL_CODE, text(extracted, K_POSTAL_CODE));
    putIfPresent(body, "cityName", text(extracted, K_CITY));
    return body;
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
