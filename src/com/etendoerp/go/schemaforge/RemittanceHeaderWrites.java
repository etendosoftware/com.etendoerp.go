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
    NeoResponse fieldError = validateIban(body);
    if (fieldError == null) {
      fieldError = validateOptions(handler, body, stored);
    }
    if (fieldError == null) {
      fieldError = validateCreditorId(handler, body, stored);
    }
    if (fieldError == null) {
      fieldError = validateName(body, create);
    }
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
    String accountRefusal = handler.accountRefusal(account,
        remittanceHasIban(body, stored, create, accountId, typeId));
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
   * ETP-6247 — the IBAN written on the remittance ({@value RemittanceHeaderHandler#FIELD_IBAN}):
   * normalized in place (upper case, no separators) and validated (15 to 34 characters, mod-97
   * check digits), 422 {@value RemittanceHeaderHandler#MSG_IBAN_INVALID} otherwise. A blank value
   * is dropped from the body, so the database copies the account's. It is never written to the
   * account.
   */
  private static NeoResponse validateIban(JSONObject body) throws JSONException {
    if (!body.has(RemittanceHeaderHandler.FIELD_IBAN)) {
      return null;
    }
    Object raw = body.opt(RemittanceHeaderHandler.FIELD_IBAN);
    String iban = raw == null || JSONObject.NULL.equals(raw)
        ? "" : RemittanceIban.normalize(raw.toString());
    if (iban.isEmpty()) {
      body.remove(RemittanceHeaderHandler.FIELD_IBAN);
      return null;
    }
    if (!RemittanceIban.isValid(iban)) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_IBAN_INVALID);
    }
    body.put(RemittanceHeaderHandler.FIELD_IBAN, iban);
    return null;
  }

  /**
   * ETP-6247 — the remittance's own SEPA creditor identifier
   * ({@value RemittanceHeaderHandler#FIELD_CREDITOR_ID}): normalized in place and validated by
   * {@link SepaCreditorId} (country, mod-97 check digits, business code, national id, at most 35
   * characters): 422 with {@code ETGO_SepaCreditorIdInvalidFormat} /
   * {@code ETGO_SepaCreditorIdInvalidCheckDigits} ({@code error.expectedCheckDigits}). A blank value
   * is dropped from the body (the database copies the account's on insert). Only a collection takes
   * one: on a payment, 422 {@value RemittanceHeaderHandler#MSG_OPTIONS_COLLECTION_ONLY}. Never
   * written to the account.
   */
  private static NeoResponse validateCreditorId(RemittanceHeaderHandler handler, JSONObject body,
      Remittance stored) throws JSONException {
    if (!body.has(RemittanceHeaderHandler.FIELD_CREDITOR_ID)) {
      return null;
    }
    Object raw = body.opt(RemittanceHeaderHandler.FIELD_CREDITOR_ID);
    String text = raw == null || JSONObject.NULL.equals(raw) ? "" : raw.toString();
    if (SepaCreditorId.normalize(text).isEmpty()) {
      body.remove(RemittanceHeaderHandler.FIELD_CREDITOR_ID);
      return null;
    }
    SepaCreditorId.Result result = SepaCreditorId.validate(text);
    if (!result.isValid()) {
      NeoResponse refusal = NeoMessageText.error(SC_UNPROCESSABLE,
          result.getReason().getMessageKey());
      JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject("error")
          : null;
      if (error != null && result.getExpectedCheckDigits() != null) {
        error.put("expectedCheckDigits", result.getExpectedCheckDigits());
      }
      return refusal;
    }
    String typeId = body.has(RemittanceHeaderHandler.FIELD_TYPE)
        ? RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_TYPE))
        : stored != null ? RemittanceHeaderHandler.idOf(stored.getRemittanceType()) : null;
    RemittanceType type = typeId != null ? handler.loadType(typeId) : null;
    if (type != null
        && !RemittanceSepaProvisioning.SCHEME_CORE.equals(RemittanceSepaFileSetup.schemeOf(type))) {
      return NeoMessageText.error(SC_UNPROCESSABLE,
          RemittanceHeaderHandler.MSG_OPTIONS_COLLECTION_ONLY);
    }
    body.put(RemittanceHeaderHandler.FIELD_CREDITOR_ID, result.getNormalized());
    return null;
  }

  /**
   * ETP-6247 — the direct debit options of a collection ({@value RemittanceHeaderHandler#FIELD_SCHEME}
   * {@code CORE | B2B}, {@value RemittanceHeaderHandler#FIELD_FINANCED} boolean). Normalized in
   * place; a blank scheme is dropped (the database defaults a collection to CORE). 422
   * {@value RemittanceHeaderHandler#MSG_SCHEME_INVALID} for any other scheme. On a PAYMENT remittance
   * they do not apply: {@code B2B} or {@code financed: true} is refused (422
   * {@value RemittanceHeaderHandler#MSG_OPTIONS_COLLECTION_ONLY}); {@code CORE} / {@code false} is
   * dropped from the body. Processed remittances never get here.
   */
  private static NeoResponse validateOptions(RemittanceHeaderHandler handler, JSONObject body,
      Remittance stored) throws JSONException {
    boolean hasScheme = body.has(RemittanceHeaderHandler.FIELD_SCHEME);
    boolean hasFinanced = body.has(RemittanceHeaderHandler.FIELD_FINANCED);
    if (!hasScheme && !hasFinanced) {
      return null;
    }
    String scheme = null;
    if (hasScheme) {
      Object raw = body.opt(RemittanceHeaderHandler.FIELD_SCHEME);
      String text = raw == null || JSONObject.NULL.equals(raw) ? "" : raw.toString().trim();
      if (text.isEmpty()) {
        body.remove(RemittanceHeaderHandler.FIELD_SCHEME);
      } else {
        scheme = RemittanceSepaOptions.normalizeScheme(text);
        if (scheme == null) {
          return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_SCHEME_INVALID);
        }
        body.put(RemittanceHeaderHandler.FIELD_SCHEME, scheme);
      }
    }
    boolean financed = false;
    if (hasFinanced) {
      Object raw = body.opt(RemittanceHeaderHandler.FIELD_FINANCED);
      String text = raw == null ? "" : raw.toString().trim();
      financed = "true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text);
      body.put(RemittanceHeaderHandler.FIELD_FINANCED, financed);
    }
    String typeId = body.has(RemittanceHeaderHandler.FIELD_TYPE)
        ? RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_TYPE))
        : stored != null ? RemittanceHeaderHandler.idOf(stored.getRemittanceType()) : null;
    RemittanceType type = typeId != null ? handler.loadType(typeId) : null;
    if (type == null
        || RemittanceSepaProvisioning.SCHEME_CORE.equals(RemittanceSepaFileSetup.schemeOf(type))) {
      return null;
    }
    if (RemittanceSepaOptions.SCHEME_B2B.equals(scheme) || financed) {
      return NeoMessageText.error(SC_UNPROCESSABLE,
          RemittanceHeaderHandler.MSG_OPTIONS_COLLECTION_ONLY);
    }
    body.remove(RemittanceHeaderHandler.FIELD_SCHEME);
    body.remove(RemittanceHeaderHandler.FIELD_FINANCED);
    return null;
  }

  /**
   * Whether the write leaves the remittance with a valid IBAN of its own, so the account need not
   * have one: the body carries one, or (an update that changes neither account nor type, which the
   * database would otherwise re-copy from the account) the stored copy is valid.
   */
  private static boolean remittanceHasIban(JSONObject body, Remittance stored, boolean create,
      String accountId, String typeId) {
    boolean typed = body.has(RemittanceHeaderHandler.FIELD_IBAN); // validated by validateIban
    if (create || stored == null) {
      return typed;
    }
    // An update that changes the account or the type makes the database re-copy the account's IBAN
    // over whatever is typed or stored.
    boolean recopied = !accountId.equals(
        RemittanceHeaderHandler.idOf(stored.getFinancialAccount()))
        || !typeId.equals(RemittanceHeaderHandler.idOf(stored.getRemittanceType()));
    return !recopied && (typed || RemittanceIban.isValid(RemittanceIban.stored(stored)));
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
