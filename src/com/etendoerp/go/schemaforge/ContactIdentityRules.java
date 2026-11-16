/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoBooleanFormat;

/**
 * ETP-6175 — the identity shape of a contact ({@code C_BPartner} on the contacts spec): a
 * <b>person</b> ({@code etgoIsperson = Y}) is named by {@code etgoFirstname} + {@code etgoLastname}
 * and its {@code name} is always derived from them; a <b>company</b> ({@code N}) is named by
 * {@code name} and carries no first or last name. Called only from {@link BusinessPartnerHandler},
 * so it applies on every channel that resolves that customization (REST single, REST batch, MCP).
 *
 * <p>Rules, with <b>blank counting as absent</b> everywhere (the Contacts form clears a field with
 * {@code ""} when the type toggles, and sends the type together with the name fields):
 * <ul>
 *   <li><b>Person.</b> A non-blank {@code name} is refused ({@link #MSG_PERSON_NAME_NOT_ALLOWED},
 *       field {@code name}) unless it equals the derivation of the effective first + last name —
 *       a redundant value is not a conflict, and both the contacts CSV import (which sends the
 *       derived name) and the MCP create pre-pass ({@code resolveServerCreateFields}) produce
 *       exactly that. {@code etgoFirstname} and {@code etgoLastname} are both required
 *       ({@code MISSING_REQUIRED_FIELDS} with {@link #MSG_PERSON_NAMES_REQUIRED}). {@code name} is
 *       then written as the derivation.</li>
 *   <li><b>Company.</b> A non-blank {@code etgoFirstname} or {@code etgoLastname} is refused
 *       ({@link #MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED}, field named; its text tells the caller to
 *       send {@code etgoIsperson=true} to create a person); {@code name} is required
 *       ({@code MISSING_REQUIRED_FIELDS} with {@link #MSG_COMPANY_NAME_REQUIRED}). On an update, a
 *       stored first or last name the body does not carry is cleared ({@code ""}), the same result
 *       the app produces when the type toggles to company.</li>
 * </ul>
 *
 * <p><b>Create</b> ({@code POST}): the type comes from the body, else the column's AD default
 * {@code N} (company), which the mandatory-defaults pass injects only after this pre-hook — so a
 * create that sends a first or last name without {@code etgoIsperson} is a company and is refused
 * (strict: the type is never guessed from the fields sent). Every rule is checked.
 *
 * <p><b>Update</b> ({@code PATCH}/{@code PUT}): nothing is checked unless the body carries one of
 * the four identity keys, so a legacy record with a null first or last name is never blocked by an
 * unrelated edit. When it does, the persisted row supplies every key the body does not carry, and
 * a requirement is enforced only for what the request touches: the part it sends, or every part of
 * the target type when the request switches the type. A person record with a null last name can
 * therefore still have its first name corrected.
 */
final class ContactIdentityRules {

  /** {@code AD_MESSAGE.Value}: a person is missing its first and/or last name. */
  static final String MSG_PERSON_NAMES_REQUIRED = "ETGO_BPartnerPersonNamesRequired";
  /** {@code AD_MESSAGE.Value}: a person was sent a name of its own. */
  static final String MSG_PERSON_NAME_NOT_ALLOWED = "ETGO_BPartnerPersonNameNotAllowed";
  /** {@code AD_MESSAGE.Value}: a company is missing its name. */
  static final String MSG_COMPANY_NAME_REQUIRED = "ETGO_BPartnerCompanyNameRequired";
  /** {@code AD_MESSAGE.Value}: a company was sent a first or last name. */
  static final String MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED =
      "ETGO_BPartnerCompanyPersonNamesNotAllowed";

  /** {@code error.code} of a refusal of a field the contact type does not accept. */
  static final String ERROR_FIELD_NOT_ALLOWED = "FIELD_NOT_ALLOWED";

  static final String FIELD_IS_PERSON = "etgoIsperson";
  static final String FIELD_NAME = "name";
  static final String FIELD_FIRSTNAME = "etgoFirstname";
  static final String FIELD_LASTNAME = "etgoLastname";

  private static final String METHOD_POST = "POST";

  private static final String PERSISTED_SQL = "SELECT em_etgo_isperson, name, em_etgo_firstname,"
      + " em_etgo_lastname FROM c_bpartner WHERE c_bpartner_id = ?";

  /** The identity columns of a persisted contact, trimmed to {@code ""}. */
  static final class Persisted {
    final boolean person;
    final String name;
    final String firstname;
    final String lastname;

    Persisted(boolean person, String name, String firstname, String lastname) {
      this.person = person;
      this.name = StringUtils.trimToEmpty(name);
      this.firstname = StringUtils.trimToEmpty(firstname);
      this.lastname = StringUtils.trimToEmpty(lastname);
    }
  }

  private ContactIdentityRules() {
  }

  /**
   * Applies the rules to a contact write, reading the persisted row from the database.
   *
   * @param ctx    the request context (HTTP method, record id)
   * @param body   the request body; on success {@code name} is rewritten for a person
   * @param method the HTTP method ({@code POST}, {@code PATCH} or {@code PUT})
   * @return the refusal, or {@code null} to continue
   */
  static NeoResponse apply(NeoContext ctx, JSONObject body, String method) throws JSONException {
    return apply(ctx, body, method, ContactIdentityRules::queryPersisted);
  }

  /**
   * {@link #apply(NeoContext, JSONObject, String)} with the persisted-row lookup supplied, so the
   * rules can be exercised without a database.
   */
  static NeoResponse apply(NeoContext ctx, JSONObject body, String method,
      Function<String, Persisted> persistedLoader) throws JSONException {
    if (body == null) {
      return null;
    }
    boolean create = METHOD_POST.equals(method);
    boolean touches = body.has(FIELD_IS_PERSON) || body.has(FIELD_NAME) || body.has(FIELD_FIRSTNAME)
        || body.has(FIELD_LASTNAME);
    if (!create && !touches) {
      return null;
    }
    Persisted persisted = null;
    if (!create) {
      String recordId = ctx == null ? null : ctx.getRecordId();
      if (StringUtils.isBlank(recordId)) {
        return null;
      }
      persisted = persistedLoader.apply(recordId);
      if (persisted == null) {
        // The record is gone (a race with a delete): the default CRUD answers that.
        return null;
      }
    }
    Optional<Boolean> requestedPerson = requestedType(body);
    boolean person = requestedPerson.orElse(persisted != null && persisted.person);
    boolean typeChanged = persisted != null && requestedPerson.isPresent()
        && requestedPerson.get() != persisted.person;
    boolean checkAll = create || typeChanged;
    return person
        ? applyPerson(body, persisted, checkAll)
        : applyCompany(body, persisted, checkAll);
  }

  /**
   * The name a create derives for a person body, written into {@code name} when the body leaves
   * it blank — the MCP create pre-pass runs before the pre-hook and needs it for its mandatory
   * check.
   * A company body is left alone, so the pre-check reports its missing {@code name}.
   *
   * @param body the create body, mutated in place
   */
  static void deriveNameForCreate(JSONObject body) throws JSONException {
    if (body == null || !requestedType(body).orElse(false) || text(body, FIELD_NAME) != null) {
      return;
    }
    String derived = AbstractPersonNameHandler.buildFullName(
        StringUtils.trimToEmpty(text(body, FIELD_FIRSTNAME)),
        StringUtils.trimToEmpty(text(body, FIELD_LASTNAME)));
    if (StringUtils.isNotBlank(derived)) {
      body.put(FIELD_NAME, derived);
    }
  }

  private static NeoResponse applyPerson(JSONObject body, Persisted persisted, boolean checkAll)
      throws JSONException {
    String firstname = effective(body, FIELD_FIRSTNAME,
        persisted == null ? "" : persisted.firstname);
    String lastname = effective(body, FIELD_LASTNAME, persisted == null ? "" : persisted.lastname);
    String derived = AbstractPersonNameHandler.buildFullName(firstname, lastname);

    String sentName = text(body, FIELD_NAME);
    if (sentName != null
        && !AbstractPersonNameHandler.buildFullName(sentName, "").equals(derived)) {
      return notAllowed(FIELD_NAME, MSG_PERSON_NAME_NOT_ALLOWED);
    }

    List<String> missing = new ArrayList<>();
    if ((checkAll || body.has(FIELD_FIRSTNAME)) && firstname.isEmpty()) {
      missing.add(FIELD_FIRSTNAME);
    }
    if ((checkAll || body.has(FIELD_LASTNAME)) && lastname.isEmpty()) {
      missing.add(FIELD_LASTNAME);
    }
    if (!missing.isEmpty()) {
      return required(missing, MSG_PERSON_NAMES_REQUIRED);
    }

    if (!derived.isEmpty()) {
      body.put(FIELD_NAME, derived);
    } else {
      // A legacy person with neither part stored: keep the stored name rather than blank it.
      body.remove(FIELD_NAME);
    }
    return null;
  }

  private static NeoResponse applyCompany(JSONObject body, Persisted persisted, boolean checkAll)
      throws JSONException {
    if (text(body, FIELD_FIRSTNAME) != null) {
      return notAllowed(FIELD_FIRSTNAME, MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED);
    }
    if (text(body, FIELD_LASTNAME) != null) {
      return notAllowed(FIELD_LASTNAME, MSG_COMPANY_PERSON_NAMES_NOT_ALLOWED);
    }
    String name = effective(body, FIELD_NAME, persisted == null ? "" : persisted.name);
    if ((checkAll || body.has(FIELD_NAME)) && name.isEmpty()) {
      return required(List.of(FIELD_NAME), MSG_COMPANY_NAME_REQUIRED);
    }
    if (persisted != null) {
      // A company keeps no first or last name. The app clears them by sending "" when the type
      // toggles; an API caller that only sends etgoIsperson=false gets the same result here, and
      // so does a company that still stores parts from an earlier switch.
      clearStoredPart(body, FIELD_FIRSTNAME, persisted.firstname);
      clearStoredPart(body, FIELD_LASTNAME, persisted.lastname);
    }
    return null;
  }

  /**
   * Writes {@code ""} for {@code field} when the body omits it and the row still stores a value.
   */
  private static void clearStoredPart(JSONObject body, String field, String persistedValue)
      throws JSONException {
    if (!body.has(field) && !persistedValue.isEmpty()) {
      body.put(field, "");
    }
  }

  /**
   * The type the request asks for: {@code true} person, {@code false} company, empty when the body
   * does not carry it (absent, JSON null or blank). Accepts a JSON boolean, {@code Y/N}
   * and {@code true/false} — the app sends a boolean, the CSV import {@code Y/N}.
   */
  static Optional<Boolean> requestedType(JSONObject body) {
    Object raw = body.opt(FIELD_IS_PERSON);
    if (raw == null || raw == JSONObject.NULL) {
      return Optional.empty();
    }
    if (raw instanceof Boolean) {
      return Optional.of((Boolean) raw);
    }
    String value = StringUtils.trimToNull(raw.toString());
    return value == null ? Optional.empty() : Optional.of(NeoBooleanFormat.toLenientBoolean(value));
  }

  /** The body's trimmed value of {@code field}, else the persisted one; never {@code null}. */
  private static String effective(JSONObject body, String field, String persistedValue) {
    if (!body.has(field)) {
      return persistedValue;
    }
    return StringUtils.trimToEmpty(text(body, field));
  }

  /**
   * The trimmed non-blank string value of {@code field}, or {@code null} when it is absent, JSON
   * null or blank. Not {@code optString}: it turns a JSON null into the text {@code "null"}.
   */
  private static String text(JSONObject body, String field) {
    Object raw = body.opt(field);
    if (raw == null || raw == JSONObject.NULL) {
      return null;
    }
    return StringUtils.trimToNull(raw.toString());
  }

  private static NeoResponse required(List<String> fields, String messageKey) {
    NeoResponse response = NeoCrudHandler.buildMissingRequiredFieldsResponse(fields,
        NeoMessageText.text(messageKey));
    attachMessageKey(response, messageKey);
    return response;
  }

  /**
   * A 400 naming the field the contact type does not accept: {@code error.field} (the shape other
   * field refusals use) and {@code error.fields} (the shape {@code MISSING_REQUIRED_FIELDS} uses,
   * which the SPA reads to mark a field), plus {@code error.messageKeys}.
   */
  private static NeoResponse notAllowed(String field, String messageKey) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("code", ERROR_FIELD_NOT_ALLOWED);
    error.put("status", 400);
    error.put("message", NeoMessageText.text(messageKey));
    error.put("field", field);
    error.put("fields", new JSONArray().put(field));
    error.put(NeoProcessService.MESSAGE_KEYS, new JSONArray().put(messageKey));
    return NeoResponse.error(400, new JSONObject().put("error", error));
  }

  private static void attachMessageKey(NeoResponse response, String messageKey) {
    JSONObject body = response.getBody();
    JSONObject error = body == null ? null : body.optJSONObject("error");
    if (error == null) {
      return;
    }
    try {
      error.put(NeoProcessService.MESSAGE_KEYS, new JSONArray().put(messageKey));
    } catch (JSONException ignored) {
      // the MISSING_REQUIRED_FIELDS body is still a valid answer
    }
  }

  /**
   * The persisted identity columns of {@code recordId}, or {@code null} when the row does not
   * exist. A failed read is rethrown: the pre-hook turns it into an error instead of letting an
   * unvalidated write through.
   */
  private static Persisted queryPersisted(String recordId) {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(PERSISTED_SQL)) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return new Persisted("Y".equalsIgnoreCase(StringUtils.trimToEmpty(rs.getString(1))),
            rs.getString(2), rs.getString(3), rs.getString(4));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the contact " + recordId, e);
    }
  }
}
