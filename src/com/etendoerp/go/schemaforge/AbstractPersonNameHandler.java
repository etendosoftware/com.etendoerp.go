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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Shared base for the handlers of person-shaped entities whose mandatory display name is
 * not an editable field, and therefore has to be derived from a first-name / last-name pair.
 *
 * <p>The rule is identical for every such entity and lives here once:
 * <ul>
 *   <li>Do nothing unless the request body carries at least one of the two name parts.</li>
 *   <li>On {@code POST}, derive only when the body's own name is blank.</li>
 *   <li>On {@code PATCH} / {@code PUT}, read the persisted row: a name that is already set is
 *       never overwritten. For the part the body omits, the persisted value is used.</li>
 *   <li>Truncate the result to the target column's length (see {@link #maxNameLength()}).</li>
 * </ul>
 *
 * <p>What differs per entity — the property names, the query that reads the persisted row, and
 * the column length — is supplied by the subclass. Concrete subclasses carry the
 * {@code @Named} qualifier; this class must not (see {@code docs/neo-headless-extensibility.md}
 * §2.2: a normal-scoped bean resolves to a Weld proxy whose subclass drops the non-inherited
 * {@code @Named}, and the handler is then silently skipped).
 *
 * <p><b>ETP-4156.</b> Extracted from {@code BusinessPartnerHandler} when {@code ContactHandler}
 * needed the same derivation for {@code AD_User}, so the rule has a single implementation.
 *
 * @see BusinessPartnerHandler {@code C_BPartner} in person mode
 */
public abstract class AbstractPersonNameHandler implements NeoHandler {

  /** Store the derived name verbatim, without truncating. */
  protected static final int NO_LIMIT = 0;

  protected static final String FIELD_NAME = "name";

  private static final String METHOD_POST = "POST";

  /** The property holding the first name in this entity's request body. */
  protected abstract String firstnameField();

  /** The property holding the last name in this entity's request body. */
  protected abstract String lastnameField();

  /**
   * A single-parameter query returning the persisted name parts for one record, selecting
   * exactly three columns in this order: name, first name, last name. The parameter is the
   * record id.
   */
  protected abstract String persistedNamePartsSql();

  /**
   * Length of the target name column. Defaults to {@link #NO_LIMIT}; override when the derived
   * value can exceed the column, which fails the insert rather than the validation.
   */
  protected int maxNameLength() {
    return NO_LIMIT;
  }

  /**
   * Derives the name from the two parts when the effective name is blank — the body value on
   * {@code POST}, the persisted value on {@code PATCH} / {@code PUT}. A name that already has
   * a value is left untouched.
   *
   * @param ctx the request context, read for the HTTP method and the record id
   * @param body the request body, mutated in place when a name is derived
   */
  protected final void deriveName(NeoContext ctx, JSONObject body) throws Exception {
    boolean hasFirstname = body.has(firstnameField());
    boolean hasLastname = body.has(lastnameField());
    if (!hasFirstname && !hasLastname) {
      return;
    }

    String firstname;
    String lastname;

    if (METHOD_POST.equals(ctx.getHttpMethod())) {
      // New record: the name must be absent or blank to auto-fill.
      if (StringUtils.isNotBlank(body.optString(FIELD_NAME, null))) {
        return;
      }
      firstname = trimmedField(body, firstnameField());
      lastname = trimmedField(body, lastnameField());
    } else {
      // PATCH / PUT: respect a name that is already persisted.
      String recordId = ctx.getRecordId();
      if (StringUtils.isBlank(recordId)) {
        return;
      }
      // persisted = [name, firstname, lastname], already trimmed.
      String[] persisted = queryPersistedNameParts(recordId);
      if (StringUtils.isNotBlank(persisted[0])) {
        return;
      }
      // Merge: the body value takes precedence over the persisted one, part by part.
      firstname = hasFirstname ? trimmedField(body, firstnameField()) : persisted[1];
      lastname = hasLastname ? trimmedField(body, lastnameField()) : persisted[2];
    }

    String derived = buildFullName(firstname, lastname);
    if (StringUtils.isNotBlank(derived)) {
      body.put(FIELD_NAME, truncateToMaxNameLength(derived));
    }
  }

  /**
   * Truncates to {@link #maxNameLength()}, or returns the value unchanged when the subclass
   * declares {@link #NO_LIMIT}. Callers pass an already non-null value.
   */
  protected final String truncateToMaxNameLength(String value) {
    int max = maxNameLength();
    if (max == NO_LIMIT || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  /** Reads a field as a trimmed string, treating absent and JSON-null as {@code ""}. */
  private static String trimmedField(JSONObject body, String field) {
    return StringUtils.trimToEmpty(body.optString(field, ""));
  }

  /**
   * Concatenates the non-blank parts separated by a single space.
   */
  private static String buildFullName(String firstname, String lastname) {
    String combined = (firstname + " " + lastname).trim();
    return combined.replaceAll("\\s{2,}", " ");
  }

  /**
   * Returns {@code [name, firstname, lastname]} for the given record, or three empty strings
   * when the record does not exist. Values are trimmed.
   */
  private String[] queryPersistedNameParts(String recordId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(persistedNamePartsSql())) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return new String[]{ StringUtils.trimToEmpty(rs.getString(1)),
              StringUtils.trimToEmpty(rs.getString(2)), StringUtils.trimToEmpty(rs.getString(3)) };
        }
      }
    }
    return new String[]{ "", "", "" };
  }
}
