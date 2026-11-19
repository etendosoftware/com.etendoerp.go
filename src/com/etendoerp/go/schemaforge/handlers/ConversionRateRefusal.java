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

import javax.servlet.http.HttpServletResponse;

import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * ETP-6365: a refusal of the {@code conversion-rates} customization, carrying the
 * {@code AD_MESSAGE} key and the HTTP status it is answered with. Thrown before anything is
 * written, so the caller only has to turn it into a response.
 *
 * <p>Message keys (Spanish and English text in {@code messages/neo_hints_*.properties}):</p>
 * <ul>
 *   <li>{@value #CURRENCY_REQUIRED}, {@value #CURRENCY_INVALID}, {@value #ORG_CURRENCY},
 *       {@value #AUTO_CURRENCY}, {@value #INVALID_RATE}, {@value #INVALID_VALID_FROM}, {@value #FUTURE_START},
 *       {@value #FIELD_NOT_EDITABLE} — 400, the request itself is wrong;</li>
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

  /** HTTP 422: the request is well formed but the organization cannot serve it. */
  static final int SC_UNPROCESSABLE = 422;

  private final int status;
  private final String key;
  private final transient Object[] args;

  ConversionRateRefusal(int status, String key, Object... args) {
    super(key);
    this.status = status;
    this.key = key;
    this.args = args == null ? new Object[0] : args.clone();
  }

  static ConversionRateRefusal badRequest(String key, Object... args) {
    return new ConversionRateRefusal(HttpServletResponse.SC_BAD_REQUEST, key, args);
  }

  static ConversionRateRefusal conflict(String key, Object... args) {
    return new ConversionRateRefusal(HttpServletResponse.SC_CONFLICT, key, args);
  }

  static ConversionRateRefusal overlapOf(String fromIso, String toIso) {
    return conflict(OVERLAP, fromIso, toIso);
  }

  int getStatus() {
    return status;
  }

  String getKey() {
    return key;
  }

  /** The text in the request language. */
  String text() {
    return NeoMessageText.text(key, args);
  }

  /** The keyed error response ({@code error.message} + {@code error.messageKeys}). */
  NeoResponse toResponse() {
    return NeoResponse.ensureTopLevelMessage(NeoMessageText.error(status, key, args));
  }
}
