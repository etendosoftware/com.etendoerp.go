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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.rest.OnboardingFieldLimits;

/**
 * ETP-6223 — validates the bodies of the signup API calls that manage existing accounts: the demo
 * expiry ({@code PATCH /accounts/{id}}) and a new group ({@code POST /groups}). Same error contract
 * as {@link SignupRequest}.
 */
final class SignupAdminRequests {

  static final String FIELD_DEMO_EXPIRES_AT = "demoExpiresAt";
  static final String FIELD_KEY = "key";
  static final String FIELD_NAME = "name";
  static final String FIELD_DESCRIPTION = "description";

  static final String CODE_INVALID_DATE = "INVALID_DATE";
  static final String CODE_INVALID_GROUP_KEY = "INVALID_GROUP_KEY";

  /** {@code ETGO_GROUP.VALUE}, {@code NAME} and {@code DESCRIPTION}. */
  static final int KEY_MAX_LENGTH = 60;
  static final int NAME_MAX_LENGTH = 120;
  static final int DESCRIPTION_MAX_LENGTH = 255;

  /** Lowercase letters, digits, {@code -} and {@code _}, starting with a letter or a digit. */
  static final Pattern GROUP_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,59}");
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private SignupAdminRequests() {
  }

  /** A group to create. */
  record NewGroup(String key, String name, String description) {
  }

  /**
   * The new demo expiry of {@code PATCH /accounts/{id}}.
   *
   * @param body the request body
   * @return the expiry day, or null for a JSON {@code null}, which goes back to the standard trial
   * @throws SignupException {@code REQUIRED_FIELD} when the field is missing, {@code INVALID_DATE}
   *     when it is not a {@code YYYY-MM-DD} calendar date
   */
  static LocalDate parseDemoExpiry(JSONObject body) throws SignupException {
    if (!body.has(FIELD_DEMO_EXPIRES_AT)) {
      throw SignupRequest.badRequest(SignupRequest.CODE_REQUIRED_FIELD, FIELD_DEMO_EXPIRES_AT,
          "Missing required field: " + FIELD_DEMO_EXPIRES_AT);
    }
    Object value = body.opt(FIELD_DEMO_EXPIRES_AT);
    if (JSONObject.NULL.equals(value)) {
      return null;
    }
    String text = String.valueOf(value).trim();
    if (!ISO_DATE.matcher(text).matches()) {
      throw invalidDate();
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw invalidDate();
    }
  }

  /**
   * The group of {@code POST /groups}.
   *
   * @param body the request body
   * @return the validated group
   * @throws SignupException {@code REQUIRED_FIELD}, {@code INVALID_GROUP_KEY} or
   *     {@code FIELD_TOO_LONG}
   */
  static NewGroup parseNewGroup(JSONObject body) throws SignupException {
    String key = SignupRequest.optionalText(body, FIELD_KEY);
    String name = SignupRequest.optionalText(body, FIELD_NAME);
    String description = SignupRequest.optionalText(body, FIELD_DESCRIPTION);
    requireText(FIELD_KEY, key);
    requireText(FIELD_NAME, name);
    if (key.length() > KEY_MAX_LENGTH) {
      throw tooLong(FIELD_KEY, KEY_MAX_LENGTH);
    }
    if (!GROUP_KEY.matcher(key).matches()) {
      throw SignupRequest.badRequest(CODE_INVALID_GROUP_KEY, FIELD_KEY,
          "A group key has lowercase letters, digits, '-' and '_' only, and starts with a letter"
              + " or a digit");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      throw tooLong(FIELD_NAME, NAME_MAX_LENGTH);
    }
    if (description.length() > DESCRIPTION_MAX_LENGTH) {
      throw tooLong(FIELD_DESCRIPTION, DESCRIPTION_MAX_LENGTH);
    }
    return new NewGroup(key, name, description.isEmpty() ? null : description);
  }

  private static void requireText(String field, String value) throws SignupException {
    if (value.isEmpty()) {
      throw SignupRequest.badRequest(SignupRequest.CODE_REQUIRED_FIELD, field,
          "Missing required field: " + field);
    }
  }

  private static SignupException tooLong(String field, int max) {
    return new SignupException(HttpServletResponse.SC_BAD_REQUEST, OnboardingFieldLimits.ERROR_CODE,
        field, max, field + " is longer than " + max + " characters");
  }

  private static SignupException invalidDate() {
    return SignupRequest.badRequest(CODE_INVALID_DATE, FIELD_DEMO_EXPIRES_AT,
        "demoExpiresAt must be a calendar date, YYYY-MM-DD");
  }
}
