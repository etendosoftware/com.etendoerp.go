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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrSanitizer;

/**
 * The value helpers of {@link InvoiceInboxApply} (ETP-6050): cleaning and bounding what a request
 * carries, and reading what the document stores. Pure; refusals are the apply action's own.
 */
final class InvoiceInboxApplyValues {

  private static final String KEY_FIELD = "field";
  private static final int MAX_ID_LENGTH = 64;
  private static final int MAX_NUMBER_DIGITS = 30;
  private static final int MIN_YEAR = 1900;
  private static final int MAX_YEAR = 2100;
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private InvoiceInboxApplyValues() {
  }

  static InvoiceInboxApply.Refusal invalidValue(String field, Integer index) {
    JSONObject details = InvoiceInboxEditGate.details(KEY_FIELD, field);
    try {
      if (index != null) {
        details.put(K_INDEX, index.intValue());
      }
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the error details", e);
    }
    return new InvoiceInboxApply.Refusal(InvoiceInboxEditGate.refuse(422,
        InvoiceInboxApply.CODE_INVALID_VALUE, InvoiceInboxMessages.INVALID_VALUE, details,
        KEY_FIELD, field));
  }

  /** A text of the request, cleaned like an extracted one; {@code null} reads as empty. */
  static String cleanText(Object value, String field, Integer index) {
    if (JSONObject.NULL.equals(value)) {
      return "";
    }
    if (!(value instanceof String)) {
      throw invalidValue(field, index);
    }
    return OcrSanitizer.text((String) value);
  }

  /** A finite JSON number within bounds, rounded HALF_UP to the scale the document stores. */
  static BigDecimal parseNumber(Object value, String field, Integer index,
      BigDecimal min, BigDecimal max, int scale) {
    if (!(value instanceof Number)) {
      throw invalidValue(field, index);
    }
    double asDouble = ((Number) value).doubleValue();
    if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
      throw invalidValue(field, index);
    }
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      throw invalidValue(field, index);
    }
    if (parsed.precision() > MAX_NUMBER_DIGITS || Math.abs(parsed.scale()) > MAX_NUMBER_DIGITS) {
      throw invalidValue(field, index);
    }
    BigDecimal scaled = parsed.setScale(scale, RoundingMode.HALF_UP);
    if (scaled.compareTo(min) < 0 || scaled.compareTo(max) > 0) {
      throw invalidValue(field, index);
    }
    return scaled;
  }

  /** A stored number at the scale the document keeps, {@code null} when absent or null. */
  static BigDecimal storedNumber(JSONObject object, String key, int scale) {
    Object value = object == null ? null : object.opt(key);
    if (!(value instanceof Number)) {
      return null;
    }
    try {
      return new BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String text(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? ((String) value).trim() : "";
  }

  /** {@code yyyy-MM-dd} in 1900-2100, or empty for {@code null}/blank (clears the date). */
  static String isoDate(Object value) {
    if (JSONObject.NULL.equals(value)) {
      return "";
    }
    if (!(value instanceof String)) {
      throw invalidValue(K_INVOICE_DATE, null);
    }
    String date = ((String) value).trim();
    if (date.isEmpty()) {
      return "";
    }
    try {
      if (!ISO_DATE.matcher(date).matches()) {
        throw invalidValue(K_INVOICE_DATE, null);
      }
      int year = LocalDate.parse(date).getYear();
      if (year < MIN_YEAR || year > MAX_YEAR) {
        throw invalidValue(K_INVOICE_DATE, null);
      }
    } catch (DateTimeParseException e) {
      throw invalidValue(K_INVOICE_DATE, null);
    }
    return date;
  }

  static boolean isIndex(Object value) {
    return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0
        && ((Number) value).longValue() <= Integer.MAX_VALUE;
  }

  static boolean isId(Object value) {
    return value instanceof String && !StringUtils.isBlank((String) value)
        && ((String) value).length() <= MAX_ID_LENGTH;
  }
}
