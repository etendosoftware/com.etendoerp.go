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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.SpanishTaxIdValidator;
import com.etendoerp.go.rest.OnboardingFieldLimits;
import com.etendoerp.go.rest.SignupAccountService;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;

/**
 * The validated body of {@code POST /accounts}.
 *
 * <p>Validates what the app's registration and onboarding validate (email shape, column lengths,
 * Spanish tax id), plus the country, currency and language against {@link SignupOptions}, so an
 * account created here can always go on to create its environment. There is no password: the
 * account signs in with the login link mailed when its environment is ready. Unknown fields are
 * ignored. The first problem found is the one reported, in the order the form shows its fields.
 */
final class SignupRequest {

  static final String FIELD_NAME = "name";
  static final String FIELD_EMAIL = "email";
  static final String FIELD_COMPANY_NAME = "companyName";
  static final String FIELD_COUNTRY_CODE = "countryCode";
  static final String FIELD_CURRENCY = "currency";
  static final String FIELD_LANGUAGE = "language";
  static final String FIELD_TAX_ID = "taxId";
  static final String FIELD_INCLUDE_SAMPLE_DATA = "includeSampleData";
  static final String FIELD_SEND_EMAILS = "sendEmails";
  static final String FIELD_TERMS_ACCEPTED = "termsAccepted";
  static final String FIELD_SOURCE = "source";

  static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";
  static final String CODE_REQUIRED_FIELD = "REQUIRED_FIELD";
  static final String CODE_INVALID_EMAIL_FORMAT = "INVALID_EMAIL_FORMAT";
  static final String CODE_UNSUPPORTED_COUNTRY = "UNSUPPORTED_COUNTRY";
  static final String CODE_UNSUPPORTED_CURRENCY = "UNSUPPORTED_CURRENCY";
  static final String CODE_UNSUPPORTED_LANGUAGE = "UNSUPPORTED_LANGUAGE";
  static final String CODE_INVALID_TAX_ID_FORMAT = "INVALID_TAX_ID_FORMAT";
  static final String CODE_INVALID_TAX_ID_CHECK_DIGIT = "INVALID_TAX_ID_CHECK_DIGIT";

  /** {@code ETGO_ACCOUNT.SIGNUP_SOURCE} is VARCHAR(2000). */
  static final int SOURCE_MAX_LENGTH = 2000;

  /** The onboarding wizard step a draft opens on, should the owner ever reopen the wizard. */
  private static final int DRAFT_STEP_COMPANY = 2;

  private final String name;
  private final String email;
  private final String companyName;
  private final String countryCode;
  private final String currency;
  private final String language;
  private final String taxId;
  private final boolean includeSampleData;
  private final boolean sendEmails;
  private final boolean termsAccepted;
  private final String source;

  private SignupRequest(JSONObject body) throws SignupException {
    name = requiredText(body, FIELD_NAME);
    email = requiredText(body, FIELD_EMAIL).toLowerCase();
    companyName = requiredText(body, FIELD_COMPANY_NAME);
    countryCode = requiredText(body, FIELD_COUNTRY_CODE).toUpperCase();
    SignupOptions.Country country = SignupOptions.country(countryCode)
        .orElseThrow(() -> badRequest(CODE_UNSUPPORTED_COUNTRY, FIELD_COUNTRY_CODE,
            "Country is not supported"));
    currency = StringUtils.defaultIfBlank(optionalText(body, FIELD_CURRENCY), country.currency())
        .toUpperCase();
    language = StringUtils.defaultIfBlank(optionalText(body, FIELD_LANGUAGE), country.language());
    taxId = optionalText(body, FIELD_TAX_ID);
    includeSampleData = body.optBoolean(FIELD_INCLUDE_SAMPLE_DATA, false);
    sendEmails = body.optBoolean(FIELD_SEND_EMAILS, true);
    termsAccepted = body.optBoolean(FIELD_TERMS_ACCEPTED, false);
    JSONObject sourceObject = body.optJSONObject(FIELD_SOURCE);
    source = sourceObject == null ? null : sourceObject.toString();
    validate();
  }

  /**
   * Reads and validates a {@code POST /accounts} body.
   *
   * @throws SignupException the first problem found, as the API reports it
   */
  static SignupRequest parse(JSONObject body) throws SignupException {
    return new SignupRequest(body);
  }

  private void validate() throws SignupException {
    if (!EmailContractCommandSupport.isValidEmail(email)) {
      throw badRequest(CODE_INVALID_EMAIL_FORMAT, FIELD_EMAIL, "Invalid email format");
    }
    OnboardingFieldLimits.LengthViolation violation = OnboardingFieldLimits.firstViolation(
        FIELD_NAME, name, OnboardingFieldLimits.ACCOUNT_NAME,
        FIELD_EMAIL, email, OnboardingFieldLimits.EMAIL,
        FIELD_COMPANY_NAME, companyName, OnboardingFieldLimits.CLIENT_NAME,
        FIELD_TAX_ID, taxId, SignupOptions.TAX_ID_MAX_LENGTH,
        FIELD_SOURCE, source, SOURCE_MAX_LENGTH);
    if (violation != null) {
      throw new SignupException(HttpServletResponse.SC_BAD_REQUEST, OnboardingFieldLimits.ERROR_CODE,
          violation.field(), violation.max(),
          String.format("Field %s must not exceed %d characters", violation.field(),
              violation.max()));
    }
    if (!SignupOptions.CURRENCIES.contains(currency)) {
      throw badRequest(CODE_UNSUPPORTED_CURRENCY, FIELD_CURRENCY, "Currency is not supported");
    }
    if (!SignupOptions.LANGUAGES.contains(language)) {
      throw badRequest(CODE_UNSUPPORTED_LANGUAGE, FIELD_LANGUAGE, "Language is not supported");
    }
    validateTaxId();
  }

  /** Same rule as the app's onboarding (ETP-5190): Spanish ids only, blank allowed. */
  private void validateTaxId() throws SignupException {
    if (!SpanishTaxIdValidator.SPAIN_COUNTRY_CODE.equals(countryCode)) {
      return;
    }
    switch (SpanishTaxIdValidator.validate(taxId)) {
      case BAD_FORMAT:
        throw badRequest(CODE_INVALID_TAX_ID_FORMAT, FIELD_TAX_ID, SpanishTaxIdValidator.ERR_FORMAT);
      case BAD_CHECK_DIGIT:
        throw badRequest(CODE_INVALID_TAX_ID_CHECK_DIGIT, FIELD_TAX_ID,
            SpanishTaxIdValidator.ERR_CHECK_DIGIT);
      default:
        break;
    }
  }

  /**
   * The company data in the onboarding draft shape: what the background provisioning reads, with
   * the same form keys {@code POST /sws/go/onboarding} takes.
   */
  String toOnboardingDraft() throws JSONException {
    JSONObject form = new JSONObject()
        .put("fullName", name)
        .put("businessType", "company")
        .put("clientName", companyName)
        .put(FIELD_CURRENCY, currency)
        .put(FIELD_LANGUAGE, language)
        .put(FIELD_COUNTRY_CODE, countryCode)
        .put("fiscalIdType", "NIF")
        .put("fiscalIdValue", taxId)
        .put(FIELD_INCLUDE_SAMPLE_DATA, includeSampleData);
    return new JSONObject().put("step", DRAFT_STEP_COMPANY).put("form", form).toString();
  }

  /** The account to create, for {@link SignupAccountService#create}. */
  SignupAccountService.NewAccount toNewAccount(String channel) throws JSONException {
    return new SignupAccountService.NewAccount(email, name, toOnboardingDraft(), channel,
        sendEmails, source, termsAccepted);
  }

  private static String requiredText(JSONObject body, String field) throws SignupException {
    String value = optionalText(body, field);
    if (value.isEmpty()) {
      throw badRequest(CODE_REQUIRED_FIELD, field, "Missing required field: " + field);
    }
    return value;
  }

  /**
   * The field as trimmed text, empty when absent or JSON {@code null}. Not {@code optString}:
   * jettison answers the string {@code "null"} for a JSON null, which would pass as a name or a
   * tax id.
   */
  static String optionalText(JSONObject body, String field) {
    Object value = body.opt(field);
    return value == null || JSONObject.NULL.equals(value) ? "" : value.toString().trim();
  }

  static SignupException badRequest(String code, String field, String message) {
    return new SignupException(HttpServletResponse.SC_BAD_REQUEST, code, field, message);
  }

  String email() {
    return email;
  }

  String companyName() {
    return companyName;
  }

  boolean sendEmails() {
    return sendEmails;
  }
}
