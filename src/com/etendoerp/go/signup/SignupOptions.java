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

package com.etendoerp.go.signup;

import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.rest.OnboardingFieldLimits;

/**
 * What the signup form may offer and send: the values {@code GET /config} publishes and
 * {@link SignupRequest} accepts, so the two can never disagree.
 *
 * <p>Mirrors the app's own onboarding ({@code OnboardingPage.jsx} in schema_forge: Spain only,
 * euros, Spanish or English). Display names are not served: the form renders them in its own
 * language, the way the app does with {@code Intl.DisplayNames}.
 */
final class SignupOptions {

  /** A country the signup accepts, with the currency and language it defaults to. */
  record Country(String code, String currency, String language) {
  }

  static final List<Country> COUNTRIES = List.of(new Country("ES", "EUR", "es_ES"));
  static final List<String> CURRENCIES = List.of("EUR");
  static final List<String> LANGUAGES = List.of("es_ES", "en_US");

  /** {@code AD_ORGINFO.TAXID} and {@code C_BPARTNER.TAXID} are VARCHAR(20). */
  static final int TAX_ID_MAX_LENGTH = 20;

  private SignupOptions() {
  }

  static Optional<Country> country(String code) {
    return COUNTRIES.stream().filter(country -> country.code().equals(code)).findFirst();
  }

  /** The {@code GET /config} body. */
  static JSONObject toJson() throws JSONException {
    JSONArray countries = new JSONArray();
    for (Country country : COUNTRIES) {
      countries.put(new JSONObject()
          .put("code", country.code())
          .put("currency", country.currency())
          .put("language", country.language()));
    }
    JSONObject limits = new JSONObject()
        .put(SignupRequest.FIELD_NAME, OnboardingFieldLimits.ACCOUNT_NAME)
        .put(SignupRequest.FIELD_EMAIL, OnboardingFieldLimits.EMAIL)
        .put(SignupRequest.FIELD_COMPANY_NAME, OnboardingFieldLimits.CLIENT_NAME)
        .put(SignupRequest.FIELD_TAX_ID, TAX_ID_MAX_LENGTH)
        .put(SignupRequest.FIELD_SOURCE, SignupRequest.SOURCE_MAX_LENGTH);
    return new JSONObject()
        .put("countries", countries)
        .put("currencies", new JSONArray(CURRENCIES))
        .put("languages", new JSONArray(LANGUAGES))
        .put("limits", limits)
        .put("taxIdValidatedFor", new JSONArray(List.of("ES")));
  }
}
