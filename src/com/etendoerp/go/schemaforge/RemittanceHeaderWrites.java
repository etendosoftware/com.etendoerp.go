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
    if (fieldError == null) {
      fieldError = validateAccountAndType(handler, body, stored, create);
    }
    if (fieldError == null) {
      proposeAccountValues(handler, body, stored, create);
    }
    return fieldError;
  }

  /**
   * ETP-6247 — the SEPA values a remittance takes from its account and type, written into the body
   * so the generic write persists them (the database no longer defaults or re-copies them; the
   * Classic form gets them from the {@code REM_Remittance_SepaDefaults} callout). A valid value the
   * body already carries is always respected (the user typed it on purpose).
   * <ul>
   *   <li><b>Create</b>: the account's IBAN when the body has none; for a collection the account's
   *       valid creditor identifier when the body has none, and the scheme {@code CORE} when the
   *       body has none. A payment gets neither creditor identifier nor scheme.</li>
   *   <li><b>Update</b> that changes the account, or switches between collection and payment: the
   *       account's IBAN when the body has none; the creditor identifier of the new account (cleared
   *       when it has no valid one) for a collection, cleared for a payment. An update that changes
   *       neither (including a collection to collection type change) touches nothing.</li>
   * </ul>
   * Runs after every validation, so an account without a valid IBAN has already been refused
   * unless the remittance carries its own.
   */
  private static void proposeAccountValues(RemittanceHeaderHandler handler, JSONObject body,
      Remittance stored, boolean create) throws JSONException {
    boolean bodyHasAccount = body.has(RemittanceHeaderHandler.FIELD_ACCOUNT);
    boolean bodyHasType = body.has(RemittanceHeaderHandler.FIELD_TYPE);
    if (!create && !bodyHasAccount && !bodyHasType) {
      return;
    }
    String accountId = bodyHasAccount
        ? RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_ACCOUNT))
        : stored != null ? RemittanceHeaderHandler.idOf(stored.getFinancialAccount()) : null;
    String typeId = bodyHasType
        ? RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_TYPE))
        : stored != null ? RemittanceHeaderHandler.idOf(stored.getRemittanceType()) : null;
    if (accountId == null || typeId == null) {
      return;
    }
    boolean collection = isCollection(handler.loadType(typeId));
    if (!create && !accountOrKindChanged(stored, accountId, collection)) {
      return;
    }
    FIN_FinancialAccount account = handler.loadAccount(accountId);
    if (account == null) {
      return;
    }
    if (!body.has(RemittanceHeaderHandler.FIELD_IBAN)) {
      String iban = RemittanceIban.normalize(account.getIBAN());
      if (!iban.isEmpty()) {
        body.put(RemittanceHeaderHandler.FIELD_IBAN, iban);
      }
    }
    if (collection) {
      proposeCreditorId(body, account, create);
      if (create && !body.has(RemittanceHeaderHandler.FIELD_SCHEME)) {
        body.put(RemittanceHeaderHandler.FIELD_SCHEME, RemittanceSepaProvisioning.SCHEME_CORE);
      }
    } else if (!create) {
      body.put(RemittanceHeaderHandler.FIELD_CREDITOR_ID, JSONObject.NULL);
    }
  }

  /**
   * The creditor identifier of a collection: the body's when it carries one, else the account's
   * valid one; on update, with none to give, the stored one (from the previous account) is cleared.
   */
  private static void proposeCreditorId(JSONObject body, FIN_FinancialAccount account,
      boolean create) throws JSONException {
    if (body.has(RemittanceHeaderHandler.FIELD_CREDITOR_ID)) {
      return;
    }
    String creditorId = RemittanceCreditorId.stored(account);
    if (creditorId != null && SepaCreditorId.isValid(creditorId)) {
      body.put(RemittanceHeaderHandler.FIELD_CREDITOR_ID, creditorId);
    } else if (!create) {
      body.put(RemittanceHeaderHandler.FIELD_CREDITOR_ID, JSONObject.NULL);
    }
  }

  /** Whether an update moves the remittance to another account or to the other kind of type. */
  private static boolean accountOrKindChanged(Remittance stored, String accountId,
      boolean collection) {
    return stored != null
        && (!accountId.equals(RemittanceHeaderHandler.idOf(stored.getFinancialAccount()))
            || collection != isCollection(stored.getRemittanceType()));
  }

  /**
   * A collection type: its payment method is a pay-in one, the same rule the Classic callout
   * {@code REM_Remittance_SepaDefaults} uses.
   */
  private static boolean isCollection(RemittanceType type) {
    return type != null && type.getPaymentMethod() != null
        && Boolean.TRUE.equals(type.getPaymentMethod().isPayinAllow());
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
        remittanceHasIban(body, stored, create, accountId, typeId, handler));
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
   * is dropped from the body; {@link #proposeAccountValues} then writes the account's IBAN on a
   * create and when an update changes the account or the kind of type. It is never written to the
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
   * is dropped from the body ({@link #proposeAccountValues} then writes the account's). Only a collection takes
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
   * place; a blank scheme is dropped ({@link #proposeAccountValues} then sets CORE on the create of a collection). 422
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
    if (hasScheme && !normalizeSchemeField(body)) {
      return NeoMessageText.error(SC_UNPROCESSABLE, RemittanceHeaderHandler.MSG_SCHEME_INVALID);
    }
    String scheme = body.optString(RemittanceHeaderHandler.FIELD_SCHEME, null);
    boolean financed = hasFinanced && normalizeFinancedField(body);
    if (!isPaymentType(handler, effectiveTypeId(body, stored))) {
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
   * Normalizes the body's scheme in place: a blank one is dropped, a known one upper-cased.
   *
   * @return {@code false} when the scheme is neither blank nor CORE/B2B (left untouched)
   */
  private static boolean normalizeSchemeField(JSONObject body) throws JSONException {
    Object raw = body.opt(RemittanceHeaderHandler.FIELD_SCHEME);
    String text = raw == null || JSONObject.NULL.equals(raw) ? "" : raw.toString().trim();
    if (text.isEmpty()) {
      body.remove(RemittanceHeaderHandler.FIELD_SCHEME);
      return true;
    }
    String scheme = RemittanceSepaOptions.normalizeScheme(text);
    if (scheme == null) {
      return false;
    }
    body.put(RemittanceHeaderHandler.FIELD_SCHEME, scheme);
    return true;
  }

  /** Normalizes the body's financed flag in place to a boolean ({@code true} / {@code Y}). */
  private static boolean normalizeFinancedField(JSONObject body) throws JSONException {
    Object raw = body.opt(RemittanceHeaderHandler.FIELD_FINANCED);
    String text = raw == null ? "" : raw.toString().trim();
    boolean financed = "true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text);
    body.put(RemittanceHeaderHandler.FIELD_FINANCED, financed);
    return financed;
  }

  /** The type the write leaves on the remittance: the body's, else the stored one's; may be null. */
  private static String effectiveTypeId(JSONObject body, Remittance stored) {
    if (body.has(RemittanceHeaderHandler.FIELD_TYPE)) {
      return RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_TYPE));
    }
    return stored != null ? RemittanceHeaderHandler.idOf(stored.getRemittanceType()) : null;
  }

  /** Whether {@code typeId} names a known type that is not a collection (CORE) one. */
  private static boolean isPaymentType(RemittanceHeaderHandler handler, String typeId) {
    RemittanceType type = typeId != null ? handler.loadType(typeId) : null;
    return type != null
        && !RemittanceSepaProvisioning.SCHEME_CORE.equals(RemittanceSepaFileSetup.schemeOf(type));
  }

  /**
   * Whether the write leaves the remittance with a valid IBAN of its own, so the account need not
   * have one: the body carries one, or the stored copy is valid and stays (an update that changes
   * neither the account nor the kind of type; a change of either replaces it with the new
   * account's IBAN, see {@link #proposeAccountValues}, which then must be valid).
   */
  private static boolean remittanceHasIban(JSONObject body, Remittance stored, boolean create,
      String accountId, String typeId, RemittanceHeaderHandler handler) {
    boolean typed = body.has(RemittanceHeaderHandler.FIELD_IBAN); // validated by validateIban
    if (create || stored == null) {
      return typed;
    }
    boolean replaced = accountOrKindChanged(stored, accountId,
        isCollection(handler.loadType(typeId)));
    return typed || (!replaced && RemittanceIban.isValid(RemittanceIban.stored(stored)));
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
