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

import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.CODE_DAYS_DUPLICATE;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.CODE_DAYS_INVALID;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.CODE_DAYS_OUT_OF_RANGE;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.CODE_NAME_DUPLICATE;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_DAYS;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.F_NAME;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.MAX_DAYS;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.MIN_DAYS;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.MSG_DAYS_DUPLICATE;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.MSG_DAYS_INVALID;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.MSG_DAYS_OUT_OF_RANGE;
import static com.etendoerp.go.schemaforge.handlers.PaymentTermHandler.MSG_NAME_DUPLICATE;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The keyed refusals of the {@code payment-term} header (ETP-5826): invalid or out-of-range days
 * (400), a duplicate simple term (409) and a duplicate name (409). The message keys and codes are
 * declared on {@link PaymentTermHandler}, whose class javadoc documents the rules.
 *
 * <p>A plain helper of {@link PaymentTermHandler}, which is the only binding. Not a bean: no CDI
 * scope, no {@code @Named}, no {@code @NeoExtension}.</p>
 */
final class PaymentTermRefusals {

  private static final String KEY_ERROR = "error";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_STATUS = "status";
  private static final String KEY_CODE = "code";
  private static final String KEY_FIELDS = "fields";
  private static final String KEY_HINT = "hint";
  private static final String KEY_MESSAGE_KEYS = "messageKeys";
  private static final String KEY_MESSAGE_PARAMS = "messageParams";

  private PaymentTermRefusals() {
  }

  static NeoResponse invalidDays(Object value) throws JSONException {
    JSONObject params = rangeParams().put("value", value == null ? JSONObject.NULL : value);
    String message = "The number of days (" + F_DAYS + ") is required and must be a whole number"
        + " between " + MIN_DAYS + " and " + MAX_DAYS
        + (value == null ? "." : "; got '" + value + "'.");
    return refusal(HttpServletResponse.SC_BAD_REQUEST, CODE_DAYS_INVALID, MSG_DAYS_INVALID,
        message, params, null);
  }

  static NeoResponse daysOutOfRange(long days) throws JSONException {
    JSONObject params = rangeParams().put("value", days);
    String message = "The number of days (" + F_DAYS + ") must be between " + MIN_DAYS + " and "
        + MAX_DAYS + "; got " + days + ".";
    return refusal(HttpServletResponse.SC_BAD_REQUEST, CODE_DAYS_OUT_OF_RANGE,
        MSG_DAYS_OUT_OF_RANGE, message, params, null);
  }

  static NeoResponse duplicate(long days, PaymentTerm existing) throws JSONException {
    boolean active = Boolean.TRUE.equals(existing.isActive());
    JSONObject params = new JSONObject()
        .put("days", days)
        .put("paymentTermId", existing.getId())
        .put("paymentTermName", existing.getName())
        .put("paymentTermSearchKey", existing.getSearchKey())
        .put("paymentTermActive", active);
    String message = "A payment term for " + days + " days already exists"
        + (active ? "" : " but is inactive") + ": '" + existing.getName() + "' ("
        + existing.getSearchKey() + ", id " + existing.getId() + ").";
    String hint = active
        ? "Reuse the existing payment term: set paymentTerms to '" + existing.getId()
            + "' on the document instead of creating a new term."
        : "Reactivate the existing payment term '" + existing.getId()
            + "' (payment-term/header, active=true) instead of creating a new term.";
    return refusal(HttpServletResponse.SC_CONFLICT, CODE_DAYS_DUPLICATE, MSG_DAYS_DUPLICATE,
        message, params, hint);
  }

  static NeoResponse nameDuplicate(PaymentTerm existing) throws JSONException {
    boolean active = Boolean.TRUE.equals(existing.isActive());
    JSONObject params = new JSONObject()
        .put("paymentTermName", existing.getName())
        .put("paymentTermId", existing.getId())
        .put("paymentTermActive", active);
    String message = "A payment term named '" + existing.getName() + "' already exists in this"
        + " organization" + (active ? "" : " (inactive)") + " (id " + existing.getId() + ").";
    String hint = "Choose another name for the payment term. If it was created from a number of"
        + " days, the existing term with that name has another due-date shape (a month offset, a"
        + " fixed due date or installment lines): send an explicit name, or rename the existing"
        + " term.";
    return refusal(HttpServletResponse.SC_CONFLICT, F_NAME, CODE_NAME_DUPLICATE,
        MSG_NAME_DUPLICATE, message, params, hint);
  }

  private static JSONObject rangeParams() throws JSONException {
    return new JSONObject().put("field", F_DAYS).put("min", MIN_DAYS).put("max", MAX_DAYS);
  }

  /**
   * The refusal shape shared with {@code CompletedInvoiceWriteFence}: everything under
   * {@code error}, with {@code messageKeys}/{@code messageParams} for the SPA and the MCP, and a
   * {@code hint} an agent can act on.
   */
  private static NeoResponse refusal(int status, String code, String messageKey, String message,
      JSONObject params, String hint) throws JSONException {
    return refusal(status, F_DAYS, code, messageKey, message, params, hint);
  }

  private static NeoResponse refusal(int status, String field, String code, String messageKey,
      String message, JSONObject params, String hint) throws JSONException {
    JSONObject error = new JSONObject()
        .put(KEY_STATUS, status)
        .put(KEY_CODE, code)
        .put(KEY_MESSAGE, message)
        .put(KEY_FIELDS, new JSONArray().put(field))
        .put(KEY_MESSAGE_KEYS, new JSONArray().put(messageKey))
        .put(KEY_MESSAGE_PARAMS, params);
    if (hint != null) {
      error.put(KEY_HINT, hint);
    }
    return NeoResponse.error(status, new JSONObject().put(KEY_ERROR, error));
  }
}
