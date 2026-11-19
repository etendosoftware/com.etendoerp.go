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

package com.etendoerp.go.schemaforge.handlers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * ETP-6365: a refusal of the {@code conversion-rates} customization, carrying the
 * {@code AD_MESSAGE} key and the HTTP status it is answered with. Thrown before anything is
 * written, so the caller only has to turn it into a response. A refusal about one request field
 * carries it ({@link #getField()}), answered as {@code error.field} and, by
 * {@code previewManualRate}, as {@code errorField}.
 *
 * <p>Message keys (Spanish and English text in {@code messages/neo_hints_*.properties}):</p>
 * <ul>
 *   <li>{@value #CURRENCY_REQUIRED}, {@value #CURRENCY_INVALID}, {@value #ORG_CURRENCY},
 *       {@value #AUTO_CURRENCY}, {@value #INVALID_RATE}, {@value #INVALID_VALID_FROM},
 *       {@value #FUTURE_START}, {@value #FIELD_NOT_EDITABLE} — 400, the request itself is
 *       wrong;</li>
 *   <li>{@value #OVERLAP}, {@value #SYNCED_LOCKED} — 409, the request
 *       collides with the stored ranges;</li>
 *   <li>{@value #NO_ORG_CURRENCY} — 422, the organization has no currency to convert from.</li>
 * </ul>
 */
class ConversionRateRefusal extends RuntimeException {

  private static final long serialVersionUID = 1L;

  static final String CURRENCY_REQUIRED = "ETGO_ConvRateCurrencyRequired";
  static final String CURRENCY_INVALID = "ETGO_ConvRateCurrencyInvalid";
  static final String ORG_CURRENCY = "ETGO_ConvRateOrgCurrency";
  static final String AUTO_CURRENCY = "ETGO_ConvRateAutoCurrency";
  static final String INVALID_RATE = "ETGO_ConvRateInvalidRate";
  static final String INVALID_VALID_FROM = "ETGO_ConvRateInvalidValidFrom";
  static final String OVERLAP = "ETGO_ConvRateOverlap";
  static final String SYNCED_LOCKED = "ETGO_ConvRateSyncedLocked";
  static final String FUTURE_START = "ETGO_ConvRateFutureStart";
  static final String FIELD_NOT_EDITABLE = "ETGO_ConvRateFieldNotEditable";
  static final String NO_ORG_CURRENCY = "ETGO_ConvRateNoOrgCurrency";
  /** Wording of an open end inside {@value #OVERLAP} ("sin fecha de fin"). */
  static final String NO_END_DATE = "ETGO_ConvRateNoEndDate";

  /** HTTP 422: the request is well formed but the organization cannot serve it. */
  static final int SC_UNPROCESSABLE = 422;

  /** How {@value #OVERLAP} shows an instant, in every language (the design's format). */
  static final DateTimeFormatter INSTANT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  private static final String NO_VALUE = "—";

  private final int status;
  private final String key;
  private final String field;
  private final transient Object[] args;

  ConversionRateRefusal(int status, String key, Object... args) {
    this(status, key, null, args);
  }

  private ConversionRateRefusal(int status, String key, String field, Object... args) {
    super(key);
    this.status = status;
    this.key = key;
    this.field = field;
    this.args = args == null ? new Object[0] : args.clone();
  }

  static ConversionRateRefusal badRequest(String key, Object... args) {
    return new ConversionRateRefusal(HttpServletResponse.SC_BAD_REQUEST, key, args);
  }

  static ConversionRateRefusal conflict(String key, Object... args) {
    return new ConversionRateRefusal(HttpServletResponse.SC_CONFLICT, key, args);
  }

  /**
   * The overlap refusal, with the conflicting range and the start to choose instead: "Ya existe
   * una tasa de conversión para EUR → CHF en las fechas indicadas (01/11/2026 09:00 – 18/11/2026
   * 14:29). Elija una fecha de inicio posterior al 18/11/2026 14:30."
   *
   * @param fromIso    the origin ISO code
   * @param toIso      the destination ISO code
   * @param conflict   the range the new one collides with; {@code null} renders "—"
   * @param suggestion the start to choose a later one than; {@code null} renders "—"
   * @return the 409, bound to {@code validFromDate}
   */
  static ConversionRateRefusal overlapOf(String fromIso, String toIso,
      ConversionRateRules.Range conflict, LocalDateTime suggestion) {
    String start = conflict != null ? format(conflict.validFrom()) : NO_VALUE;
    String end;
    if (conflict == null) {
      end = NO_VALUE;
    } else if (conflict.isOpen()) {
      end = NeoMessageText.text(NO_END_DATE);
    } else {
      end = format(conflict.validTo());
    }
    return new ConversionRateRefusal(HttpServletResponse.SC_CONFLICT, OVERLAP,
        ConversionRateWrites.F_VALID_FROM, fromIso, toIso, start, end, format(suggestion));
  }

  static String format(LocalDateTime instant) {
    return instant == null ? NO_VALUE : instant.format(INSTANT_FORMAT);
  }

  /**
   * The same refusal, bound to the request field it is about.
   *
   * @param requestField the body key, e.g. {@code validFromDate}
   * @return a copy carrying the field
   */
  ConversionRateRefusal on(String requestField) {
    return new ConversionRateRefusal(status, key, requestField, args);
  }

  int getStatus() {
    return status;
  }

  String getKey() {
    return key;
  }

  /** The request field the refusal is about, or {@code null}. */
  String getField() {
    return field;
  }

  /** The text in the request language. */
  String text() {
    return NeoMessageText.text(key, args);
  }

  /**
   * The keyed error response ({@code error.message} + {@code error.messageKeys}, and
   * {@code error.field} when the refusal is bound to one).
   */
  NeoResponse toResponse() {
    NeoResponse response = NeoMessageText.error(status, key, args);
    JSONObject error = response.getBody() != null ? response.getBody().optJSONObject("error")
        : null;
    if (error != null && field != null) {
      try {
        error.put("field", field);
      } catch (JSONException e) {
        // the keyed error is still a valid answer without the field
      }
    }
    return NeoResponse.ensureTopLevelMessage(response);
  }
}
