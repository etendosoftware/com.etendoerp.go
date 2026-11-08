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

import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_DAYS;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_NAME;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_NEXT_BUSINESS_DAY;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_OFFSET_MONTH;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_SEARCH_KEY;

import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoContext;

/**
 * The create-time defaults of the {@code payment-term} header (ETP-5826, ETP-5978): what a
 * days-only create derives from the number of days, and how a create tells the caller's own
 * values from the ones the server filled before {@link PaymentTermHandler} ran.
 *
 * <p>A plain helper of {@link PaymentTermHandler}, which is the only binding and calls it from its
 * pre-hook and from {@code resolveServerCreateFields}. Not a bean: no CDI scope, no
 * {@code @Named}, no {@code @NeoExtension}.</p>
 */
final class PaymentTermCreateDefaults {

  static final String IMMEDIATE_NAME = "Inmediato";
  static final String IMMEDIATE_SEARCH_KEY = "I";

  /** The days and every field a create derives from them (ETP-5978). */
  static final List<String> CREATE_DERIVED_FIELDS = List.of(F_DAYS, F_NAME, F_SEARCH_KEY,
      F_OFFSET_MONTH, F_NEXT_BUSINESS_DAY);

  private PaymentTermCreateDefaults() {
  }

  /**
   * Whether {@code key} in a create body is the caller's own value, not one the server filled
   * before this customization ran. REST single and batch run the pre-hook before any defaults
   * pass, so there presence in the body is the answer. MCP {@code etendo_create} runs the
   * mandatory-defaults pass first and says what it did: a key in
   * {@link NeoContext#getFallbackDefaults()} is a placeholder (ETP-5669, forwarded to the
   * pre-hook since ETP-5680), and when {@link NeoContext#ATTR_CLIENT_PROVIDED_FIELDS} is present
   * only the keys it lists were sent.
   *
   * @param context the request; {@code null} means presence in the body
   */
  static boolean isCallerValue(NeoContext context, JSONObject body, String key) {
    if (!body.has(key)) {
      return false;
    }
    if (context == null) {
      return true;
    }
    if (context.getFallbackDefaults().contains(key)) {
      return false;
    }
    Object clientProvided = context.getAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS);
    return !(clientProvided instanceof Collection)
        || ((Collection<?>) clientProvided).contains(key);
  }

  /**
   * On create, removes from the body the days-derived fields the server filled before the
   * pre-hook (see {@link #isCallerValue}), so the pre-hook decides them exactly as on REST: days
   * the caller did not send are refused as missing (400
   * {@value PaymentTermHandler#MSG_DAYS_INVALID}) instead of creating a zero-day term from a
   * placeholder, and {@code name}, {@code searchKey},
   * {@code offsetMonthDue} and {@code nextBusinessDay} are derived from the days rather than kept
   * from a default. A value the caller sent is never touched. A no-op without a context, and on
   * REST, where the body only holds what the caller sent.
   */
  static void dropServerFilledCreateFields(JSONObject body, NeoContext context) {
    if (context == null) {
      return;
    }
    for (String key : CREATE_DERIVED_FIELDS) {
      if (body.has(key) && !isCallerValue(context, body, key)) {
        body.remove(key);
      }
    }
  }

  /** Fills the fields the days-only create form does not send, leaving any value the caller sent. */
  static void enrichCreate(JSONObject body, long days) throws JSONException {
    if (isAbsent(body, F_NAME)) {
      body.put(F_NAME, deriveName(days));
    }
    if (isAbsent(body, F_SEARCH_KEY)) {
      body.put(F_SEARCH_KEY, deriveSearchKey(days));
    }
    if (!body.has(F_OFFSET_MONTH) || body.isNull(F_OFFSET_MONTH)) {
      body.put(F_OFFSET_MONTH, 0L);
    }
    if (!body.has(F_NEXT_BUSINESS_DAY) || body.isNull(F_NEXT_BUSINESS_DAY)) {
      body.put(F_NEXT_BUSINESS_DAY, days != 0);
    }
  }

  /** {@code "N Días"}, {@code "1 Día"}, or {@code "Inmediato"} for zero — the seed's naming. */
  static String deriveName(long days) {
    if (days == 0) {
      return IMMEDIATE_NAME;
    }
    return days == 1 ? "1 D\u00EDa" : days + " D\u00EDas";
  }

  /** {@code "ND"} (e.g. {@code "12D"}), or {@code "I"} for zero — the seed's search keys. */
  static String deriveSearchKey(long days) {
    return days == 0 ? IMMEDIATE_SEARCH_KEY : days + "D";
  }

  /**
   * Absent, JSON-null or blank exactly as {@code AbstractNeoHandler#optTrimmed} reads it: blank
   * after {@link String#trim()} (characters up to U+0020), not {@link StringUtils#isBlank}.
   */
  private static boolean isAbsent(JSONObject body, String key) {
    return !body.has(key) || body.isNull(key)
        || StringUtils.trimToNull(body.optString(key, "")) == null;
  }
}
