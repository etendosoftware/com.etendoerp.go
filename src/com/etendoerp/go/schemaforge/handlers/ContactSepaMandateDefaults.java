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
package com.etendoerp.go.schemaforge.handlers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.SepaRemittancesFlag;

/**
 * ETP-6148 — the customer's SEPA direct-debit mandate on a contact write
 * ({@code C_BPartner.EM_Obsepad_Mandate_Identifier} / {@code EM_Obsepad_Mandate_Date}, owned by
 * the OBSEPAD module). Called only by the contacts {@code businessPartner} customization
 * ({@code BusinessPartnerHandler}); it is that entity's behaviour, not a shared rule.
 *
 * <p><b>Flag on</b> ({@link SepaRemittancesFlag}), the values are STORED, never only suggested:
 * <ul>
 *   <li><b>Create</b> of a customer: a blank mandate identifier takes the contact's tax ID
 *       ({@code TaxID}, the CIF/NIF, cut to 35) when it has one, and a blank signature date takes
 *       today (the creation date).</li>
 *   <li><b>Update</b> of a customer (any save, which also fills the contacts created before the
 *       feature or with the flag off when they are next saved): each mandate field that is empty
 *       in the database AND that the body does not send is filled — the identifier with the
 *       current tax ID (the one the body sends, else the stored one) when there is one, the date
 *       with the contact's {@code Created} date.</li>
 *   <li>A value the caller sends always wins. An <b>explicit clear</b> (the body sends the key
 *       empty) is respected for that save, but <b>not remembered</b>: there is no column marking
 *       the mandate as cleared on purpose, so the next save that does not send the key fills it
 *       again from the tax ID / creation date. Clearing a mandate for good is not possible
 *       through this rule.</li>
 *   <li>A non-blank stored value is never overwritten, so a later change of the tax ID keeps
 *       the mandate identifier, which must stay a stable mandate reference.</li>
 *   <li>A contact that is not a customer gets nothing; a create of a non-customer drops the two
 *       keys, which only the form's DEFAULTS put there. An update of a non-customer keeps what it
 *       carries.</li>
 *   <li><b>DEFAULTS</b> of a new contact prefill the signature date with today
 *       ({@link #applyDefaults}), so the form shows what the create will store.</li>
 * </ul>
 * On a create, "blank" is the value the body sends (absent, null or empty all get the default).
 *
 * <p><b>Flag off</b>: the two keys are removed from the write body, so they are never persisted
 * and never rejected. Same contract as {@code structured-address-for-sepa} on the address
 * ({@code BpLocationHandler#stripStructuredAddressKeys}): a stray value from a stale client or an
 * agent that read the field off the schema is ignored silently. A 403 would refuse the whole
 * contact save for two keys the MCP schema still advertises while the flag is off.
 *
 * <p>The body is handled in every section that can carry the keys: the body itself and its
 * {@code {"data": {...}}} envelope, which {@code NeoFieldFilter#filterBody} unwraps and persists.
 * Defaults are written into the innermost section.
 */
public final class ContactSepaMandateDefaults {

  private static final Logger log = LogManager.getLogger(ContactSepaMandateDefaults.class);

  /** DAL property of {@code EM_Obsepad_Mandate_Identifier}. */
  public static final String FIELD_MANDATE_ID = "obsepadMandateIdentifier";
  /** DAL property of {@code EM_Obsepad_Mandate_Date}. */
  public static final String FIELD_MANDATE_DATE = "obsepadMandateDate";
  private static final String COLUMN_MANDATE_ID = "EM_Obsepad_Mandate_Identifier";
  private static final String COLUMN_MANDATE_DATE = "EM_Obsepad_Mandate_Date";
  /** {@code EM_Obsepad_Mandate_Identifier} is {@code NVARCHAR(35)}, the SEPA mandate id length. */
  static final int MANDATE_ID_MAX_LENGTH = 35;

  private static final List<String> MANDATE_ID_KEYS = List.of(FIELD_MANDATE_ID, COLUMN_MANDATE_ID);
  private static final List<String> MANDATE_DATE_KEYS = List.of(FIELD_MANDATE_DATE, COLUMN_MANDATE_DATE);
  private static final String FIELD_CUSTOMER = "customer";
  private static final String FIELD_TAX_ID = "taxID";
  private static final String KEY_DATA = "data";
  private static final String BP_ENTITY = "BusinessPartner";

  private static final String PERSISTED_SQL =
      "SELECT iscustomer, taxid, em_obsepad_mandate_identifier,"
      + " to_char(em_obsepad_mandate_date, 'YYYY-MM-DD'), to_char(created, 'YYYY-MM-DD')"
      + " FROM c_bpartner WHERE c_bpartner_id = ?";

  /** What the persisted contact already holds, for an update. */
  record Persisted(boolean customer, String taxId, String mandateId, String mandateDate,
      String created) {
  }

  private ContactSepaMandateDefaults() {
  }

  /**
   * Applies the mandate rules to a contact write. A no-op for anything but a CRUD create or
   * update. Evaluates the flag at request entry, as {@link SepaRemittancesFlag} requires.
   *
   * @param ctx the request context
   * @param body the write body; mutated in place
   * @throws SQLException if the persisted contact cannot be read
   * @throws JSONException if a default cannot be written into the body
   */
  public static void apply(NeoContext ctx, JSONObject body) throws SQLException, JSONException {
    if (body == null || !ContactTypeRules.isCrudWrite(ctx)) {
      return;
    }
    boolean create = ContactTypeRules.isCreate(ctx);
    apply(body, SepaRemittancesFlag.isEnabled(), create,
        create ? null : ctx.getRecordId(), LocalDate.now());
  }

  /**
   * The rules with every input explicit, for tests.
   *
   * @param body the write body; mutated in place
   * @param enabled whether the flag is on for the caller
   * @param create whether the write is a create
   * @param recordId the contact id of an update; ignored on a create
   * @param today the date a create's signature date defaults to
   * @throws SQLException if the persisted contact cannot be read
   * @throws JSONException if a default cannot be written into the body
   */
  static void apply(JSONObject body, boolean enabled, boolean create, String recordId,
      LocalDate today) throws SQLException, JSONException {
    List<JSONObject> sections = writeSections(body);
    if (!enabled) {
      strip(sections);
      return;
    }
    if (!mandateColumnsInstalled()) {
      return;
    }
    if (create) {
      applyOnCreate(sections, today);
    } else {
      applyOnUpdate(sections, recordId);
    }
  }

  /**
   * Create: a customer gets the tax ID as identifier and today as signature date, for each field
   * the body leaves blank. A non-customer gets neither: a mandate authorizes collecting from a
   * customer, and the keys of a non-customer create can only come from the form's DEFAULTS
   * ({@link #applyDefaults}), fetched before the user chooses the type, so they are dropped.
   */
  private static void applyOnCreate(List<JSONObject> sections, LocalDate today)
      throws JSONException {
    // IsCustomer's AD default is 'Y', so a create that does not send the flag is a customer.
    if (!ContactTypeRules.flag(effective(sections, List.of(FIELD_CUSTOMER), Boolean.TRUE))) {
      strip(sections);
      return;
    }
    JSONObject target = sections.get(sections.size() - 1);
    String taxId = text(effective(sections, List.of(FIELD_TAX_ID), null));
    if (text(effective(sections, MANDATE_ID_KEYS, null)) == null && taxId != null) {
      putReplacing(sections, target, MANDATE_ID_KEYS, mandateIdOf(taxId));
    }
    if (text(effective(sections, MANDATE_DATE_KEYS, null)) == null) {
      putReplacing(sections, target, MANDATE_DATE_KEYS, today.toString());
    }
  }

  /**
   * Update of a customer: each field empty in the database AND not sent is filled. A field the
   * body sends, even empty (an explicit clear), is the caller's decision for this save. A missing
   * record (or a blank id) is the generic CRUD's to answer.
   */
  private static void applyOnUpdate(List<JSONObject> sections, String recordId)
      throws SQLException, JSONException {
    Persisted persisted = queryPersisted(recordId);
    if (persisted == null) {
      return;
    }
    if (!ContactTypeRules.flag(effective(sections, List.of(FIELD_CUSTOMER),
        persisted.customer()))) {
      return;
    }
    JSONObject target = sections.get(sections.size() - 1);
    String taxId = text(effective(sections, List.of(FIELD_TAX_ID), persisted.taxId()));
    if (!sends(sections, MANDATE_ID_KEYS) && text(persisted.mandateId()) == null
        && taxId != null) {
      target.put(FIELD_MANDATE_ID, mandateIdOf(taxId));
    }
    if (!sends(sections, MANDATE_DATE_KEYS) && text(persisted.mandateDate()) == null
        && persisted.created() != null) {
      target.put(FIELD_MANDATE_DATE, persisted.created());
    }
  }

  private static String mandateIdOf(String taxId) {
    return StringUtils.left(taxId, MANDATE_ID_MAX_LENGTH);
  }

  /** Whether any section carries any spelling of the field, whatever its value. */
  private static boolean sends(List<JSONObject> sections, List<String> keys) {
    for (JSONObject section : sections) {
      if (matchingKey(section, keys) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * DEFAULTS of a new contact (the form prefill): with the flag on, a signature date not already
   * defaulted is today — what a create stores for a customer. The identifier is not prefilled:
   * the tax ID is not known when the form opens; the create stores it.
   *
   * @param defaults the {@code defaults} object of the DEFAULTS response; mutated in place
   * @param enabled whether the flag is on for the caller
   * @param today the date to prefill
   * @return whether {@code defaults} changed
   * @throws JSONException if the default cannot be written
   */
  public static boolean applyDefaults(JSONObject defaults, boolean enabled, LocalDate today)
      throws JSONException {
    if (defaults == null || !enabled || !mandateColumnsInstalled()
        || text(defaults.opt(FIELD_MANDATE_DATE)) != null) {
      return false;
    }
    defaults.put(FIELD_MANDATE_DATE, today.toString());
    return true;
  }

  /**
   * The body itself and, when present, its {@code data} envelope, outermost first. The innermost
   * section is the one generic CRUD persists.
   */
  static List<JSONObject> writeSections(JSONObject body) {
    JSONObject wrapped = body.optJSONObject(KEY_DATA);
    return wrapped != null ? List.of(body, wrapped) : List.of(body);
  }

  /** Removes every spelling of the two mandate keys from every section. */
  private static void strip(List<JSONObject> sections) {
    List<String> dropped = new ArrayList<>();
    for (JSONObject section : sections) {
      dropped.addAll(removeKeys(section, MANDATE_ID_KEYS));
      dropped.addAll(removeKeys(section, MANDATE_DATE_KEYS));
    }
    if (!dropped.isEmpty()) {
      log.debug("ContactSepaMandateDefaults: dropped {} — sepa-remittances is off for this account",
          String.join(", ", dropped));
    }
  }

  /**
   * The value the record ends up with for one field: the innermost section that carries any of
   * its spellings wins, otherwise {@code fallback}.
   */
  private static Object effective(List<JSONObject> sections, List<String> keys, Object fallback) {
    for (int i = sections.size() - 1; i >= 0; i--) {
      String present = matchingKey(sections.get(i), keys);
      if (present != null) {
        return sections.get(i).opt(present);
      }
    }
    return fallback;
  }

  /**
   * Writes the default under the DAL property name in {@code target}, after removing every other
   * spelling from every section, so a blank value sent under another key cannot win over it.
   */
  private static void putReplacing(List<JSONObject> sections, JSONObject target, List<String> keys,
      String value) throws JSONException {
    for (JSONObject section : sections) {
      removeKeys(section, keys);
    }
    target.put(keys.get(0), value);
  }

  private static List<String> removeKeys(JSONObject section, List<String> keys) {
    List<String> removed = new ArrayList<>();
    String present = matchingKey(section, keys);
    while (present != null) {
      section.remove(present);
      removed.add(present);
      present = matchingKey(section, keys);
    }
    return removed;
  }

  /**
   * The key {@code section} carries for any of {@code keys}, exactly or ignoring case (AD column
   * names resolve case-insensitively downstream), or {@code null}.
   */
  private static String matchingKey(JSONObject section, List<String> keys) {
    for (String key : keys) {
      if (section.has(key)) {
        return key;
      }
    }
    Iterator<?> it = section.keys();
    while (it.hasNext()) {
      String candidate = String.valueOf(it.next());
      String lower = candidate.toLowerCase(Locale.ROOT);
      for (String key : keys) {
        if (key.toLowerCase(Locale.ROOT).equals(lower)) {
          return candidate;
        }
      }
    }
    return null;
  }

  /** A trimmed non-blank string, or {@code null} for an absent, null or blank value. */
  private static String text(Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    return StringUtils.trimToNull(value.toString());
  }

  /**
   * Whether the runtime model knows the OBSEPAD columns. Etendo GO depends on OBSEPAD through
   * Gradle only, so an instance without it must not fail a contact save on an unknown column.
   */
  static boolean mandateColumnsInstalled() {
    Entity entity = ModelProvider.getInstance().getEntity(BP_ENTITY, false);
    return entity != null && entity.hasProperty(FIELD_MANDATE_ID)
        && entity.hasProperty(FIELD_MANDATE_DATE);
  }

  /** The persisted contact, or {@code null} when the id is blank or the row does not exist. */
  static Persisted queryPersisted(String recordId) throws SQLException {
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(PERSISTED_SQL)) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return new Persisted("Y".equals(rs.getString(1)), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5));
      }
    }
  }
}
