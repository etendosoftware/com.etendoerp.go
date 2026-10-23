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

package com.etendoerp.go.schemaforge.ocr;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Null-safe reads of a (sanitized) extraction payload (ETP-5715). Jettison's {@code optString}
 * returns the text {@code "null"} for a JSON null, which would read as a vendor named "null".
 */
final class OcrJson {

  private OcrJson() {
  }

  /**
   * A string value, trimmed.
   *
   * @param object the object to read, may be {@code null}
   * @param key    the key
   * @return the text, empty when the key is missing, null or not a string
   */
  static String str(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? ((String) value).trim() : "";
  }

  /** Largest magnitude accepted in any number read from a document. */
  private static final BigDecimal MAX_MAGNITUDE = new BigDecimal("1000000000000000");
  /** Most digits (and decimals) of a number read from a document. */
  private static final int MAX_DIGITS = 30;

  /**
   * A number read from a document, bounded: a finite number of sane size, or a numeric string with
   * at most {@value #MAX_DIGITS} digits and no exponent. Anything else — {@code 1e999999999},
   * {@code Infinity}, {@code NaN}, a ten-thousand-digit number — is rejected, never converted.
   *
   * @param object the object to read, may be {@code null}
   * @param key    the key
   * @return the value, {@code null} when missing, blank, null, not a number or out of bounds
   */
  static BigDecimal decimal(JSONObject object, String key) {
    Number number = boundedNumber(object == null ? null : object.opt(key));
    return number == null ? null : new BigDecimal(number.toString());
  }

  /**
   * The bounded number a value stands for.
   *
   * @param value a JSON value
   * @return a finite {@link BigDecimal} within bounds, or {@code null}
   */
  static BigDecimal boundedNumber(Object value) {
    String text = null;
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      text = Double.isNaN(d) || Double.isInfinite(d) ? null : BigDecimal.valueOf(d).toPlainString();
    } else if (value instanceof BigDecimal) {
      BigDecimal big = (BigDecimal) value;
      text = big.precision() > MAX_DIGITS || Math.abs(big.scale()) > MAX_DIGITS ? null
          : big.toPlainString();
    } else if (value instanceof java.math.BigInteger) {
      text = ((java.math.BigInteger) value).bitLength() > 2 * MAX_DIGITS ? null : value.toString();
    } else if (value instanceof Number) {
      text = value.toString();
    } else if (value instanceof String) {
      text = ((String) value).trim();
    }
    return text == null ? null : parseBounded(text);
  }

  private static BigDecimal parseBounded(String text) {
    if (text.isEmpty() || text.length() > 2 * MAX_DIGITS + 2
        || !text.matches("-?\\d+(\\.\\d+)?")) {
      return null;
    }
    BigDecimal parsed = new BigDecimal(text);
    boolean tooBig = parsed.precision() > MAX_DIGITS || parsed.scale() > MAX_DIGITS
        || parsed.abs().compareTo(MAX_MAGNITUDE) > 0;
    return tooBig ? null : parsed;
  }

  /**
   * Whether a text is not blank.
   *
   * @param text the text
   * @return {@code true} when it has something other than whitespace
   */
  static boolean filled(String text) {
    return StringUtils.isNotBlank(text);
  }

  /**
   * The application's {@code toIsoDate}: {@code yyyy-MM-dd} stays, {@code d/M/yyyy} and
   * {@code d-M-yy} are converted (two-digit years are 20yy), anything else is returned as it came.
   *
   * @param value the date as read from the document
   * @return the ISO date, or the trimmed input when it is not a recognised shape
   */
  static String toIsoDate(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return trimmed;
    }
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})").matcher(trimmed);
    if (!m.matches()) {
      return trimmed;
    }
    String year = m.group(3).length() == 2 ? "20" + m.group(3) : m.group(3);
    return year + "-" + StringUtils.leftPad(m.group(2), 2, '0') + "-"
        + StringUtils.leftPad(m.group(1), 2, '0');
  }

  /**
   * A notice for the agent: a code and a fixed sentence written by this module (never document text).
   *
   * @param code    the notice code
   * @param message the fixed message
   * @return the notice object
   * @throws JSONException when the object cannot be built
   */
  static JSONObject notice(String code, String message) throws JSONException {
    return new JSONObject().put("code", code).put("message", message);
  }
}
