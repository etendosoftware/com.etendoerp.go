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

import java.time.LocalDate;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;

/**
 * ETP-6148 — the write rules of the remittance header (create, update, delete), run by
 * {@link RemittanceHeaderHandler} before the generic CRUD. Split out of the handler; every DAL read,
 * the account rules and the day are still taken through the handler's seams.
 */
final class RemittanceHeaderWrites {

  private static final int SC_UNPROCESSABLE = 422;

  private RemittanceHeaderWrites() {
  }

  /**
   * The refusal of a write, or {@code null} to let the generic CRUD run. A processed remittance
   * cannot be modified or deleted; the name is required and bounded; a new or changed due date
   * cannot be in the past; the account must be a remittance account and the type a provisioned
   * remittance type linked to it.
   *
   * @param handler the remittance header customization (its seams)
   * @param context the write request
   * @return the refusal, or {@code null}
   * @throws JSONException if the body cannot be read or the suggested name written
   */
  static NeoResponse validate(RemittanceHeaderHandler handler, NeoContext context)
      throws JSONException {
    String method = context.getHttpMethod();
    boolean create = "POST".equals(method);
    boolean update = "PUT".equals(method) || "PATCH".equals(method);
    boolean delete = "DELETE".equals(method);
    if (!create && !update && !delete) {
      return null;
    }
    Remittance stored = create ? null : handler.loadRemittance(context.getRecordId());
    if (!create && stored == null) {
      return null; // the generic CRUD answers the missing record
    }
    if (stored != null && Boolean.TRUE.equals(stored.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_PROCESSED);
    }
    JSONObject body = context.getRequestBody();
    if (delete || body == null) {
      return null;
    }
    if (create) {
      RemittanceNameCallout.fillSuggestedName(handler, body);
    }
    NeoResponse fieldError = validateName(body, create);
    if (fieldError == null) {
      fieldError = validateDueDate(handler, body, stored);
    }
    return fieldError != null ? fieldError : validateAccountAndType(handler, body, stored, create);
  }

  /**
   * The account and type rules, when the write sets either (always on create): a remittance
   * account ({@link RemittanceHeaderHandler#accountRefusal}) and a provisioned type linked to it.
   * Missing or unknown references are left to the generic write.
   */
  private static NeoResponse validateAccountAndType(RemittanceHeaderHandler handler,
      JSONObject body, Remittance stored, boolean create) {
    boolean bodyHasAccount = body.has(RemittanceHeaderHandler.FIELD_ACCOUNT);
    boolean bodyHasType = body.has(RemittanceHeaderHandler.FIELD_TYPE);
    if (!create && !bodyHasAccount && !bodyHasType) {
      return null;
    }
    String accountId = bodyHasAccount
        ? RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_ACCOUNT))
        : RemittanceHeaderHandler.idOf(stored.getFinancialAccount());
    String typeId = bodyHasType
        ? RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_TYPE))
        : RemittanceHeaderHandler.idOf(stored.getRemittanceType());
    if (accountId == null || typeId == null) {
      return null; // the generic mandatory check names the missing field
    }
    FIN_FinancialAccount account = handler.loadAccount(accountId);
    if (account == null) {
      return null; // unknown reference: the generic write preflight answers it
    }
    String accountRefusal = handler.accountRefusal(account);
    if (accountRefusal != null) {
      return NeoMessageText.error(SC_UNPROCESSABLE, accountRefusal);
    }
    RemittanceType type = handler.loadType(typeId);
    if (type == null || handler.isTypeAvailable(account, type)) {
      return null;
    }
    return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_TYPE_NOT_AVAILABLE);
  }

  /**
   * A new or changed due date (the charge / credit date) cannot be earlier than today, the
   * server's calendar day in the JVM default time zone ({@link RemittanceHeaderHandler#today()}).
   * Checked only when the body carries {@code dueDate} and, on update, only when it differs from
   * the stored day, so a remittance whose stored date is already in the past stays editable in its
   * other fields. A create without {@code dueDate} gets the AD default (today). Processed
   * remittances never get here. An unparseable value is left to the generic write, which refuses
   * it.
   *
   * <p>"Today" is the server's {@code LocalDate} (JVM zone) while the SPA's date picker uses the
   * browser's, so a client west of the server can pick its own today and still get the 422 in the
   * hours around midnight. Accepted: SEPA remittances are EU-only, where the offset is small.</p>
   */
  private static NeoResponse validateDueDate(RemittanceHeaderHandler handler, JSONObject body,
      Remittance stored) {
    if (!body.has(RemittanceHeaderHandler.FIELD_DUE_DATE)) {
      return null;
    }
    LocalDate requested = RemittanceNameSuggestion.parseDay(
        RemittanceNameCallout.stringOf(body.opt(RemittanceHeaderHandler.FIELD_DUE_DATE)));
    if (requested == null) {
      return null;
    }
    if (stored != null && requested.equals(toLocalDate(stored.getDueDate()))) {
      return null;
    }
    if (requested.isBefore(handler.today())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_DUE_DATE_PAST);
    }
    return null;
  }

  private static LocalDate toLocalDate(java.util.Date date) {
    if (date == null) {
      return null;
    }
    if (date instanceof java.sql.Date) {
      return ((java.sql.Date) date).toLocalDate();
    }
    return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
  }

  private static NeoResponse validateName(JSONObject body, boolean create) {
    if (!create && !body.has(RemittanceHeaderHandler.FIELD_NAME)) {
      return null;
    }
    Object raw = body.opt(RemittanceHeaderHandler.FIELD_NAME);
    String name = raw == null || JSONObject.NULL.equals(raw) ? "" : raw.toString().trim();
    if (name.isEmpty()) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST,
          RemittanceHeaderHandler.MSG_NAME_REQUIRED);
    }
    if (name.length() > RemittanceHeaderHandler.NAME_MAX_LENGTH) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST,
          RemittanceHeaderHandler.MSG_NAME_TOO_LONG, RemittanceHeaderHandler.NAME_MAX_LENGTH);
    }
    return null;
  }
}
