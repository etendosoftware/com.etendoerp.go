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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SEPA Creditor Identifier (CI, EPC "AT-02", "Identificador de acreedor") — normalisation,
 * validation, construction and splitting (ETP-6148).
 *
 * <p>Layout of a normalised CI (max 35 characters, ISO 20022 {@code Max35Text}):
 * <pre>
 *   positions 0-1  country ISO code      (ES)
 *   positions 2-3  check digits          (kk)
 *   positions 4-6  creditor business code / suffix, NOT part of the check (default 000)
 *   positions 7+   national identifier    (the tax id, 1-28 alphanumerics)
 * </pre>
 *
 * <p>The check digits are the ISO 7064 MOD 97-10 value used by IBAN, computed over
 * {@code nationalId + country + "00"} with letters mapped A=10 .. Z=35, as
 * {@code 98 - (n mod 97)}, zero-padded to two digits.
 *
 * <p><b>Parity contract.</b> {@link #build} produces exactly what
 * {@code org.openbravo.sepa.iso20022.util.SEPAUtil#getAT02Identification} produces for the same
 * (already upper-case) inputs — that method is what writes the CI into the pain.008 XML, so the
 * value stored and validated here must be the value the SEPA module emits. The SEPA module is NOT a
 * dependency of this module, so the algorithm is reimplemented rather than called.
 * {@link #isValid} compares against the recomputed digits (not a {@code mod 97 == 1} test): the
 * two differ for the congruent pairs 00/97, 01/98 and 99/02, and only the recomputed digits are
 * what {@code SEPAUtil} would ever generate.
 *
 * <p>The SPA mirrors this API in {@code tools/app-shell/src/lib/sepaCreditorId.js}; keep both in
 * step.
 *
 * <p>Plain static utility, no DAL and no CDI, so it can be unit tested without a database.
 */
public final class SepaCreditorId {

  /** Default creditor business code (positions 4-6) when the organization declares none. */
  public static final String DEFAULT_SUFFIX = "000";

  /** Maximum length of a CI: ISO 20022 {@code Max35Text}. */
  public static final int MAX_LENGTH = 35;

  /** Maximum length of the national identifier part (35 - 2 - 2 - 3). */
  public static final int NATIONAL_ID_MAX_LENGTH = 28;

  private static final int CHECK_DIGITS_START = 2;
  private static final int SUFFIX_START = 4;
  private static final int NATIONAL_ID_START = 7;

  private static final Pattern CI_FORMAT = Pattern
      .compile("^[A-Z]{2}\\d{2}[A-Z0-9]{3}[A-Z0-9]{1,28}$");
  private static final Pattern COUNTRY_FORMAT = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern SUFFIX_FORMAT = Pattern.compile("^[A-Z0-9]{3}$");
  private static final Pattern NATIONAL_ID_FORMAT = Pattern.compile("^[A-Z0-9]{1,28}$");
  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");

  private static final BigInteger MOD_97 = BigInteger.valueOf(97);
  private static final int CHECK_BASE = 98;

  /**
   * Why a CI was rejected. Each reason carries the {@code AD_MESSAGE} value a handler should
   * surface; the SPA maps the same keys in {@code backendErrors.js}.
   */
  public enum Reason {
    /** Nothing left after normalisation. */
    BLANK("ETGO_SepaCreditorIdRequired"),
    /** Not {@code CC kk XXX national-id} — wrong length, wrong character classes. */
    INVALID_FORMAT("ETGO_SepaCreditorIdInvalidFormat"),
    /** Well formed, but the check digits do not match the country + national identifier. */
    INVALID_CHECK_DIGITS("ETGO_SepaCreditorIdInvalidCheckDigits");

    private final String messageKey;

    Reason(String messageKey) {
      this.messageKey = messageKey;
    }

    /** The {@code AD_MESSAGE.Value} for this rejection. */
    public String getMessageKey() {
      return messageKey;
    }
  }

  /**
   * Outcome of {@link #validate}. {@code normalized} is always set (possibly empty) so a caller can
   * persist the canonical form; {@code reason} and {@code expectedCheckDigits} are null when valid.
   */
  public static final class Result {
    private final String normalized;
    private final Reason reason;
    private final String expectedCheckDigits;

    private Result(String normalized, Reason reason, String expectedCheckDigits) {
      this.normalized = normalized;
      this.reason = reason;
      this.expectedCheckDigits = expectedCheckDigits;
    }

    public boolean isValid() {
      return reason == null;
    }

    public String getNormalized() {
      return normalized;
    }

    /** Null when valid. */
    public Reason getReason() {
      return reason;
    }

    /** The digits the CI should carry; set only for {@link Reason#INVALID_CHECK_DIGITS}. */
    public String getExpectedCheckDigits() {
      return expectedCheckDigits;
    }
  }

  private SepaCreditorId() {
  }

  /**
   * Upper-cases and strips every non-alphanumeric character (spaces, dashes, dots). Same
   * normalisation {@code SEPAUtil} applies to the tax id.
   *
   * @param raw the value as typed, may be {@code null}
   * @return the normalised value, never {@code null} (empty for {@code null})
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    return NON_ALPHANUMERIC.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
  }

  /**
   * Normalises {@code raw} and validates format, then check digits.
   *
   * @param raw the creditor identifier as typed, may be {@code null}
   * @return the outcome, with the normalised value and, when invalid, the reason
   */
  public static Result validate(String raw) {
    String ci = normalize(raw);
    if (ci.isEmpty()) {
      return new Result(ci, Reason.BLANK, null);
    }
    if (!CI_FORMAT.matcher(ci).matches()) {
      return new Result(ci, Reason.INVALID_FORMAT, null);
    }
    String expected = computeCheckDigits(countryOf(ci), nationalIdOf(ci));
    if (!expected.equals(checkDigitsOf(ci))) {
      return new Result(ci, Reason.INVALID_CHECK_DIGITS, expected);
    }
    return new Result(ci, null, null);
  }

  /**
   * Shorthand for {@code validate(raw).isValid()}.
   *
   * @param raw the creditor identifier as typed, may be {@code null}
   * @return whether it is a valid creditor identifier
   */
  public static boolean isValid(String raw) {
    return validate(raw).isValid();
  }

  /**
   * The two check digits for {@code country} + {@code nationalId}, both expected already
   * normalised: {@code 98 - (nationalId + country + "00" mod 97)}, zero-padded. Always in
   * {@code 02..98}.
   *
   * @param country the country ISO code, two upper-case letters
   * @param nationalId the national identifier, 1-28 upper-case alphanumerics
   * @return the two check digits
   * @throws IllegalArgumentException
   *           when {@code country} is not two letters or {@code nationalId} is not 1-28
   *           alphanumerics
   */
  public static String computeCheckDigits(String country, String nationalId) {
    if (country == null || !COUNTRY_FORMAT.matcher(country).matches()) {
      throw new IllegalArgumentException("Invalid country ISO code: " + country);
    }
    if (nationalId == null || !NATIONAL_ID_FORMAT.matcher(nationalId).matches()) {
      throw new IllegalArgumentException("Invalid national identifier: " + nationalId);
    }
    String rearranged = nationalId + country + "00";
    StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
    for (int i = 0; i < rearranged.length(); i++) {
      char ch = rearranged.charAt(i);
      if (Character.isDigit(ch)) {
        numeric.append(ch);
      } else {
        numeric.append(ch - 'A' + 10);
      }
    }
    int check = CHECK_BASE - new BigInteger(numeric.toString()).mod(MOD_97).intValue();
    return check < 10 ? "0" + check : Integer.toString(check);
  }

  /**
   * Builds the CI the way {@code SEPAUtil.getAT02Identification} does:
   * {@code country + checkDigits + suffix + taxId}. Every part is normalised first; a blank
   * {@code suffix} becomes {@link #DEFAULT_SUFFIX}.
   *
   * @param countryIso the country ISO code
   * @param suffix the creditor business code, blank for {@link #DEFAULT_SUFFIX}
   * @param taxId the tax id (national identifier)
   * @return the CI, or null when the parts cannot form one (blank tax id — the same answer
   *         {@code SEPAUtil} gives — or a country that is not two letters, a suffix that is not
   *         three alphanumerics, a tax id longer than 28 characters)
   */
  public static String build(String countryIso, String suffix, String taxId) {
    String country = normalize(countryIso);
    String code = normalize(suffix);
    if (code.isEmpty()) {
      code = DEFAULT_SUFFIX;
    }
    String nationalId = normalize(taxId);
    if (!COUNTRY_FORMAT.matcher(country).matches() || !SUFFIX_FORMAT.matcher(code).matches()
        || !NATIONAL_ID_FORMAT.matcher(nationalId).matches()) {
      return null;
    }
    return country + computeCheckDigits(country, nationalId) + code + nationalId;
  }

  /*
   * Split helpers. Callers pass a normalised CI that passed the format check, and anything
   * shorter than the part asked for yields null.
   */

  /**
   * Positions 0-1, the country ISO code.
   *
   * @param ci a normalised creditor identifier, may be {@code null}
   * @return the country code, or {@code null} when {@code ci} is too short
   */
  public static String countryOf(String ci) {
    return ci == null || ci.length() < CHECK_DIGITS_START ? null
        : ci.substring(0, CHECK_DIGITS_START);
  }

  /**
   * Positions 2-3, the check digits.
   *
   * @param ci a normalised creditor identifier, may be {@code null}
   * @return the check digits, or {@code null} when {@code ci} is too short
   */
  public static String checkDigitsOf(String ci) {
    return ci == null || ci.length() < SUFFIX_START ? null
        : ci.substring(CHECK_DIGITS_START, SUFFIX_START);
  }

  /**
   * Positions 4-6, the creditor business code (suffix).
   *
   * @param ci a normalised creditor identifier, may be {@code null}
   * @return the suffix, or {@code null} when {@code ci} is too short
   */
  public static String suffixOf(String ci) {
    return ci == null || ci.length() < NATIONAL_ID_START ? null
        : ci.substring(SUFFIX_START, NATIONAL_ID_START);
  }

  /**
   * Positions 7+, the national identifier (tax id).
   *
   * @param ci a normalised creditor identifier, may be {@code null}
   * @return the national identifier, or {@code null} when {@code ci} is too short
   */
  public static String nationalIdOf(String ci) {
    return ci == null || ci.length() <= NATIONAL_ID_START ? null
        : ci.substring(NATIONAL_ID_START);
  }
}
