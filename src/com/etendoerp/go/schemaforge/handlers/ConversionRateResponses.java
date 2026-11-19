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
import java.util.Locale;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.currency.ConversionRate;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

import com.etendoerp.go.schemaforge.NeoErrorResponses;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * ETP-6365: the responses the {@code conversion-rates} customization serves itself — the written
 * row in the generic write's shape, and the translation of a refusal raised by the database.
 */
final class ConversionRateResponses {

  private static final Logger log = LogManager.getLogger(ConversionRateResponses.class);

  /** Virtual column {@code EM_Etgo_Rate_Status}: CURRENT or HISTORIC. */
  static final String F_STATUS = "etgoRateStatus";
  /** Core ConversionRateEventHandler: two ranges of a pair overlap. */
  static final String CORE_OVERLAP = "20504";
  /** com.smf.currency.conversionrate: a downloaded row cannot be deleted. */
  static final String SMF_SYNCED_DELETE = "SMFCR_CannotDeleteSyncedRate";

  private ConversionRateResponses() {
  }

  /** An end on the wire: {@code null} when open (empty, or year 2999 or later). */
  static Object wireEnd(java.util.Date end) {
    LocalDateTime local = ConversionRateStore.toLocal(end);
    return local == null || local.getYear() >= ConversionRateRules.OPEN_YEAR ? JSONObject.NULL
        : NeoDateFormat.toWireDateTime(end);
  }

  /** The 404 for an unknown rate or one of another tenant. */
  static NeoResponse notFound(String recordId) {
    return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
        "Conversion rate not found: " + recordId);
  }

  /**
   * The row as the generic read serialises it, its dates as server-local wall clock
   * ({@code yyyy-MM-dd'T'HH:mm:ss}; an open end — year 2999 or later — as {@code null}) and its
   * state read back.
   *
   * @param row   the row
   * @param store reads the state
   * @return the row's JSON
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject recordJson(ConversionRate row, ConversionRateStore store)
      throws JSONException {
    DataToJsonConverter converter = OBProvider.getInstance().get(DataToJsonConverter.class);
    JSONObject json = converter.toJsonObject(row, DataResolvingMode.FULL);
    json.put(ConversionRateWrites.F_VALID_FROM,
        NeoDateFormat.toWireDateTime(row.getValidFromDate()));
    json.put(ConversionRateWrites.F_VALID_TO, wireEnd(row.getValidToDate()));
    json.put(F_STATUS, store.statusOf(row.getId()));
    return json;
  }

  /**
   * A failure raised while writing, after the session was rolled back: the database refusals the
   * customization knows are worded with its own keys, anything else is classified generically.
   *
   * @param failure    what was thrown
   * @param httpMethod the request method
   * @param writes     the writer of the request, which words an overlap with its context
   * @return the error response
   */
  static NeoResponse failure(Throwable failure, String httpMethod, ConversionRateWrites writes) {
    String text = messagesOf(failure);
    if (text.contains(SMF_SYNCED_DELETE.toLowerCase(Locale.ROOT))) {
      return ConversionRateRefusal.conflict(ConversionRateRefusal.SYNCED_LOCKED).toResponse();
    }
    if (text.contains(CORE_OVERLAP) || containsCoreOverlapText(text)) {
      return overlapAfterFailure(writes).toResponse();
    }
    log.error("[ETP-6365] Conversion rate write failed", failure);
    return NeoErrorResponses.fromException(failure, httpMethod, "conversion-rates write", null);
  }

  private static ConversionRateRefusal overlapAfterFailure(ConversionRateWrites writes) {
    try {
      return writes.overlapAfterFailure();
    } catch (RuntimeException e) {
      log.debug("Could not read the colliding range again: {}", e.getMessage());
      return ConversionRateRefusal.overlapOf(null, null, null, null);
    }
  }

  /** The core overlap refusal arrives translated (OBMessageUtils.messageBD), without its key. */
  private static boolean containsCoreOverlapText(String text) {
    try {
      String coreText = OBMessageUtils.messageBD(CORE_OVERLAP);
      return coreText != null && !CORE_OVERLAP.equals(coreText)
          && text.contains(coreText.toLowerCase(Locale.ROOT));
    } catch (RuntimeException e) {
      log.debug("Could not read message {}: {}", CORE_OVERLAP, e.getMessage());
      return false;
    }
  }

  /** Every message of the cause chain, lower case. */
  static String messagesOf(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t.getMessage() != null) {
        text.append(t.getMessage().toLowerCase(Locale.ROOT)).append('\n');
      }
    }
    return text.toString();
  }
}
