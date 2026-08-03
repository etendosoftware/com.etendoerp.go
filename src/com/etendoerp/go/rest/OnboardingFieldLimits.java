package com.etendoerp.go.rest;

import org.apache.commons.lang3.StringUtils;

/**
 * Maximum accepted lengths for the onboarding payloads (ETP-4665).
 *
 * <p>Every limit is the size of the Etendo AD column the value ends up in. Provisioning writes
 * these values through {@code InitialClientSetup} / {@code InitialOrgSetup}, and Openbravo's DAL
 * raises a {@code ValidationException} at flush time when a value overflows its column. That
 * happens mid-transaction, so the whole tenant creation rolls back and the caller receives the
 * unresolved AD message key {@code @CreateClientFailed@} with no hint of which field was at
 * fault. Rejecting up front with a machine-readable code is what turns that into an actionable
 * error.
 *
 * <p>The frontend mirrors these numbers in {@code onboarding/fieldLimits.js}; keep both in sync.
 */
public final class OnboardingFieldLimits {

  private OnboardingFieldLimits() {
  }

  /** Stable error code returned to the client when a field exceeds its limit. */
  public static final String ERROR_CODE = "FIELD_TOO_LONG";

  /**
   * {@code ETGO_ACCOUNT.NAME} is VARCHAR(255), so storage does not constrain this one. It is
   * capped at {@link #FULL_NAME} anyway because the profile step pre-fills its "full name" field
   * with this value: a looser cap here would pre-fill a field already over its own limit. Keep
   * the two equal.
   */
  public static final int ACCOUNT_NAME = 60;

  /**
   * The account email is copied verbatim into {@code AD_USER.USERNAME} and {@code AD_USER.NAME},
   * both NVARCHAR(60).
   */
  public static final int EMAIL = 60;

  /**
   * No storage constraint exists: {@code ETGO_ACCOUNT.PASSWORD_HASH} always holds a fixed-length
   * {@code base64(salt):base64(sha256)} string, and the tenant's {@code AD_USER.PASSWORD} receives
   * the hash of a server-generated UUID rather than this password. There is no bcrypt in the
   * stack, so the usual 72-byte truncation does not apply. This bound only stops an abusive
   * client from making the server hash an unbounded payload.
   */
  public static final int PASSWORD = 128;

  /** Written to {@code AD_USER.NAME} NVARCHAR(60) as the tenant admin's display name. */
  public static final int FULL_NAME = 60;

  /**
   * The client name is written unchanged to {@code AD_CLIENT.NAME} NVARCHAR(60) and to
   * {@code AD_CLIENT.VALUE} / {@code AD_ORG.VALUE}, both NVARCHAR(40) — the search keys bind
   * first. {@code AD_ROLE.NAME} additionally receives {@code clientName + " Admin"}.
   */
  public static final int CLIENT_NAME = 40;

  /** {@code C_LOCATION.ADDRESS1} is NVARCHAR(60). */
  public static final int ADDRESS = 60;

  /**
   * A field that exceeded its limit. {@code field} is the JSON key the client sent, so the UI can
   * point at the right input.
   */
  public record LengthViolation(String field, int max) {
  }

  /**
   * Returns the first violated limit, or {@code null} when every value fits.
   *
   * @param fieldsAndValues
   *     flattened triples of {@code field name, value, max length}
   * @throws IllegalArgumentException
   *     when the varargs are not a whole number of triples
   */
  public static LengthViolation firstViolation(Object... fieldsAndValues) {
    if (fieldsAndValues.length % 3 != 0) {
      throw new IllegalArgumentException("Expected (field, value, max) triples");
    }
    for (int i = 0; i < fieldsAndValues.length; i += 3) {
      String field = (String) fieldsAndValues[i];
      String value = (String) fieldsAndValues[i + 1];
      int max = (Integer) fieldsAndValues[i + 2];
      if (exceeds(value, max)) {
        return new LengthViolation(field, max);
      }
    }
    return null;
  }

  /** True when {@code value} is longer than {@code max}. A null/blank value never violates. */
  public static boolean exceeds(String value, int max) {
    return StringUtils.length(value) > max;
  }
}
