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

package com.etendoerp.go.common;

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Format + check-digit validation for a Spanish tax identifier (ETP-5190).
 *
 * <p>Used at the only two points where a tenant sets its own fiscal identifier: the signup
 * wizard (through {@code parseOnboardingRequest}) and the Organization window (through
 * {@code OrganizationInformationHandler}). The browser runs the same three rules in
 * {@code lib/taxIdValidation.js}; this class is what makes them binding.
 *
 * <p><b>Why this is not left to classic.</b> Nothing validates
 * {@code AD_OrgInfo.TaxID} — no callout, no validation rule, no event handler. The only
 * check that ever looks at an organization's own identifier is
 * {@code com.etendoerp.verifactu}'s {@code InitialValidator}, which calls
 * {@code NIFValidator.validateCompanyNIF(VerifactuUtils.getTaxIDIssuer(...))} while
 * COMPLETING AN INVOICE — so a wrong NIF entered at signup surfaces as a failure to invoice,
 * weeks later, on a value that is by then stamped on the tenant's fiscal configuration.
 * (What classic does observe is the BUSINESS PARTNER identifier, via
 * {@code bptaxidkey}'s {@code ViesStatusObserver} — and that one only records a
 * {@code V}/{@code I}/{@code P} status and swallows its own failures, so it never rejects
 * anything either.)
 *
 * <p><b>Why the rules are re-implemented rather than called.</b> The CIF algorithm below is
 * the one in {@code com.etendoerp.verifactu}'s {@code NIFValidator#validateCompanyNIF}, but
 * Verifactu is a Spain-localization module that is not installed on every instance — making
 * Etendo GO depend on it would break the deployments that do not have it. The person-NIF and
 * NIE check digits are not in that class at all (it only regex-matches those), so there was
 * nothing to reuse for them regardless.
 *
 * <p><b>All three shapes are accepted, and that matters.</b> The signup wizard offers
 * {@code businessType} {@code company} / {@code freelancer}: an autónomo signs up with a
 * personal DNI, not a company CIF. Validating only the CIF form would have rejected every
 * freelancer in the product.
 */
public final class SpanishTaxIdValidator {

  /** The reason a value was rejected. {@link #VALID} is the only non-failure. */
  public enum Result {
    /** The value is a well-formed Spanish tax identifier, or blank. */
    VALID,
    /** Matches none of the three accepted shapes. */
    BAD_FORMAT,
    /** The right shape, but the check digit does not match the rest of the value. */
    BAD_CHECK_DIGIT,
  }

  /** Company (CIF): a letter of entity type, 7 digits, and a control digit or letter. */
  private static final Pattern CIF = Pattern.compile("^[ABCDEFGHJKLMNPQRSUVW]\\d{7}[0-9A-J]$");

  /** Natural person (DNI/NIF): 8 digits and a control letter. */
  private static final Pattern PERSON = Pattern.compile("^\\d{8}[A-Z]$");

  /** Foreign resident (NIE): X, Y or Z, 7 digits and a control letter. */
  private static final Pattern NIE = Pattern.compile("^[XYZ]\\d{7}[A-Z]$");

  /** CIF control characters, indexed by the computed control digit. */
  private static final String CIF_CONTROL_LETTERS = "JABCDEFGHI";

  /** DNI/NIE control letters, indexed by {@code number % 23}. */
  private static final String PERSON_CONTROL_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";

  private static final int PERSON_MODULUS = 23;

  /** ISO code of the only country these rules describe. Callers gate on it. */
  public static final String SPAIN_COUNTRY_CODE = "ES";

  /*
   * The rejection messages, owned here rather than by either caller: the signup endpoint and
   * the Organization handler must answer the same words for the same value, and these exact
   * literals are the KEYS in the frontend's BACKEND_ERROR_MAP (`lib/backendErrors.js`).
   * Changing one without changing that map silently reverts the message to English.
   *
   * Two messages rather than one: "this is not a NIF at all" and "this is a NIF with the wrong
   * last character" send the user to different places, and the second is by far the more common
   * — a typo anywhere in the digits changes the check digit.
   */
  public static final String ERR_FORMAT = "The tax ID is not a valid NIF, CIF or NIE.";
  public static final String ERR_CHECK_DIGIT =
      "The tax ID check digit does not match. Review the number.";

  private SpanishTaxIdValidator() {
  }

  /**
   * Validates a tax identifier.
   *
   * <p>A blank value is {@link Result#VALID}: whether the field is required is a separate
   * question, answered by the form (the signup wizard marks it optional; the Organization
   * window marks it required) and not by the format rules. A value that is only separators
   * is NOT blank and is rejected — see {@link #validate}.
   *
   * @param rawTaxId the value as the user typed it; case and surrounding space are normalized
   * @return why it was rejected, or {@link Result#VALID}
   */
  public static Result validate(String rawTaxId) {
    String taxId = normalize(rawTaxId);
    if (taxId.isEmpty()) {
      // Blank is acceptable; a value made ENTIRELY of the separators normalize() strips
      // ("---", "...") is not. Without this it would normalize to empty and be waved through
      // — and the Organization window's required-field check passes it too, since that one
      // only tests for blankness, so "---" would have been stored as a NIF.
      return StringUtils.isBlank(rawTaxId) ? Result.VALID : Result.BAD_FORMAT;
    }
    if (CIF.matcher(taxId).matches()) {
      return isValidCif(taxId) ? Result.VALID : Result.BAD_CHECK_DIGIT;
    }
    if (PERSON.matcher(taxId).matches()) {
      return isValidPersonControlLetter(taxId, taxId.substring(0, 8)) ? Result.VALID
          : Result.BAD_CHECK_DIGIT;
    }
    if (NIE.matcher(taxId).matches()) {
      // X/Y/Z stand in for a leading 0/1/2 before the modulus is taken.
      int prefix = "XYZ".indexOf(taxId.charAt(0));
      return isValidPersonControlLetter(taxId, prefix + taxId.substring(1, 8)) ? Result.VALID
          : Result.BAD_CHECK_DIGIT;
    }
    return Result.BAD_FORMAT;
  }

  /**
   * Convenience predicate over {@link #validate}, for the callers that only need to admit or
   * refuse a value and have no use for the reason. Anything that reports the reason back to a
   * user must call {@link #validate} instead — collapsing BAD_FORMAT and BAD_CHECK_DIGIT into
   * one boolean is what produces a message that cannot say what to correct.
   *
   * @param rawTaxId the raw value as typed or pasted, may be {@code null} or blank
   * @return {@code true} only when {@link #validate} returns {@link Result#VALID}
   */
  public static boolean isValid(String rawTaxId) {
    return validate(rawTaxId) == Result.VALID;
  }

  /**
   * Uppercases and strips whitespace plus the separators people paste in ({@code .} and
   * {@code -}), so {@code "b-12345678"} is judged as {@code "B12345678"} rather than being
   * rejected on punctuation the user cannot see the problem with.
   *
   * @param rawTaxId the raw value, may be {@code null}
   * @return the normalized value, never {@code null}
   */
  public static String normalize(String rawTaxId) {
    if (StringUtils.isBlank(rawTaxId)) {
      return "";
    }
    return rawTaxId.replaceAll("[\\s.\\-]", "").toUpperCase();
  }

  /**
   * The CIF check character, Luhn-like: the odd-positioned digits are doubled and their own
   * digits summed, the even-positioned ones added as they are. Copied from
   * {@code com.etendoerp.verifactu}'s {@code NIFValidator#validateCompanyNIF} — including the
   * part that matters, which is that BOTH representations of the control character are
   * accepted (the digit itself, and its letter from {@link #CIF_CONTROL_LETTERS}), because
   * which one a given entity type uses depends on the leading letter.
   */
  private static boolean isValidCif(String cif) {
    String digits = cif.substring(1, 8);
    int sumEven = 0;
    int sumOdd = 0;
    for (int i = 0; i < digits.length(); i++) {
      int digit = digits.charAt(i) - '0';
      if (i % 2 == 0) {
        int doubled = digit * 2;
        sumOdd += (doubled / 10) + (doubled % 10);
      } else {
        sumEven += digit;
      }
    }
    int control = (10 - ((sumEven + sumOdd) % 10)) % 10;
    char provided = cif.charAt(8);
    return provided == (char) ('0' + control) || provided == CIF_CONTROL_LETTERS.charAt(control);
  }

  /**
   * The DNI/NIE control letter: the 8-digit number modulo 23, indexed into
   * {@link #PERSON_CONTROL_LETTERS}.
   *
   * @param taxId the normalized identifier, whose last character is the control letter
   * @param numericPart the 8 digits the modulus is taken of (for a NIE, with X/Y/Z already
   *     replaced by 0/1/2)
   */
  private static boolean isValidPersonControlLetter(String taxId, String numericPart) {
    int number = Integer.parseInt(numericPart);
    char expected = PERSON_CONTROL_LETTERS.charAt(number % PERSON_MODULUS);
    return taxId.charAt(taxId.length() - 1) == expected;
  }
}
