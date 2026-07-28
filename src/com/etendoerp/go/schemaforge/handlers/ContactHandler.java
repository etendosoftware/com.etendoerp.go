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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Pre-save hook for the {@code contact} entity ({@code AD_User}) of the contacts spec.
 *
 * <p>Fills the two AD-mandatory columns that the window does not expose as editable
 * fields, so a create driven only by the visible fields (first name / last name) passes
 * validation:
 * <ul>
 *   <li><b>{@code name}</b> — {@code AD_User.Name} is mandatory but declared
 *       {@code readOnly / form: false} in {@code decisions.json}. Derived as
 *       {@code firstName + " " + lastName} when the effective name is blank
 *       (body value on POST, persisted value on PATCH/PUT).</li>
 *   <li><b>{@code username}</b> — {@code AD_User.Username} is AD-mandatory and is not
 *       declared as a Schema Forge field at all, so
 *       {@code NeoHiddenMandatoryDefaultsResolver} cannot resolve it (the column has no
 *       AD default). Derived from {@code name} on POST only, when blank.</li>
 * </ul>
 *
 * <p>Both values are truncated to the column length ({@code NVARCHAR(60)} for
 * {@code AD_User.Name} and {@code AD_User.Username}).
 *
 * <p><b>ETP-4156.</b> This logic used to live in the app-shell's generic
 * {@code useEntity} hook, branching on hardcoded entity names
 * ({@code contact} / {@code adUser} / {@code user}), which violated the
 * "no window-specific logic in generic services" principle. Moving it here also covers
 * the {@code /batch} import path, which routes through {@code handleWithHooks} too.
 *
 * <p>Two deliberate differences from the removed front-end code:
 * <ul>
 *   <li>{@code username} is derived on POST only. The old hook also rewrote it on every
 *       update whose payload carried a new {@code name}, silently reassigning a unique
 *       login identifier on rename.</li>
 *   <li>It applies to the contacts spec's {@code contact} entity only. The {@code user}
 *       spec exposes {@code name} and {@code username} as editable fields, so it no
 *       longer gets a silent autofill.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'contactHandler'} on the ETGO_SF_ENTITY
 * record for the contacts spec's {@code contact} entity.
 *
 * @see com.etendoerp.go.schemaforge.BusinessPartnerHandler the sibling hook that does the
 *     equivalent derivation for {@code C_BPartner}
 */
@Named("contactHandler")
public class ContactHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ContactHandler.class);

  private static final String FIELD_NAME = "name";
  private static final String FIELD_USERNAME = "username";
  private static final String FIELD_FIRSTNAME = "firstName";
  private static final String FIELD_LASTNAME = "lastName";

  /** {@code AD_User.Name} and {@code AD_User.Username} are both {@code NVARCHAR(60)}. */
  private static final int MAX_LENGTH = 60;

  @Override
  public NeoResponse handle(NeoContext ctx) {
    String method = ctx.getHttpMethod();
    boolean isWrite = "POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method);
    if (!isWrite) {
      return null;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      deriveName(ctx, body);
      if ("POST".equals(method)) {
        deriveUsername(body);
      }
    } catch (Exception e) {
      log.error("ContactHandler: error in handle()", e);
      throw new OBException("Error processing Contact name derivation", e);
    }
    return null;
  }

  /**
   * Derives {@code name} from the first/last name parts when the effective name is blank.
   * A name that is already set is left untouched.
   */
  private void deriveName(NeoContext ctx, JSONObject body) throws Exception {
    boolean hasFirstname = body.has(FIELD_FIRSTNAME);
    boolean hasLastname = body.has(FIELD_LASTNAME);
    if (!hasFirstname && !hasLastname) {
      return;
    }

    String firstname;
    String lastname;

    if ("POST".equals(ctx.getHttpMethod())) {
      if (StringUtils.isNotBlank(body.optString(FIELD_NAME, null))) {
        return;
      }
      firstname = StringUtils.trimToEmpty(body.optString(FIELD_FIRSTNAME, ""));
      lastname = StringUtils.trimToEmpty(body.optString(FIELD_LASTNAME, ""));
    } else {
      String recordId = ctx.getRecordId();
      if (StringUtils.isBlank(recordId)) {
        return;
      }
      // persisted = [name, firstname, lastname]
      String[] persisted = queryPersistedNameParts(recordId);
      if (StringUtils.isNotBlank(persisted[0])) {
        return;
      }
      // The body value wins over the persisted one for each part it carries.
      firstname = hasFirstname ? StringUtils.trimToEmpty(body.optString(FIELD_FIRSTNAME, "")) : persisted[1];
      lastname = hasLastname ? StringUtils.trimToEmpty(body.optString(FIELD_LASTNAME, "")) : persisted[2];
    }

    String derived = buildFullName(firstname, lastname);
    if (StringUtils.isNotBlank(derived)) {
      body.put(FIELD_NAME, truncate(derived));
    }
  }

  /**
   * Fills the mandatory {@code username} from {@code name} when the create body omits it.
   */
  private void deriveUsername(JSONObject body) throws Exception {
    if (StringUtils.isNotBlank(body.optString(FIELD_USERNAME, null))) {
      return;
    }
    String name = StringUtils.trimToEmpty(body.optString(FIELD_NAME, ""));
    if (StringUtils.isBlank(name)) {
      return;
    }
    body.put(FIELD_USERNAME, truncate(name));
  }

  /**
   * Concatenates the non-blank parts separated by a single space.
   */
  private static String buildFullName(String firstname, String lastname) {
    String combined = (firstname + " " + lastname).trim();
    return combined.replaceAll("\\s{2,}", " ");
  }

  private static String truncate(String value) {
    return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
  }

  /**
   * Returns {@code [name, firstname, lastname]} for the given {@code AD_User} record, or
   * three empty strings when the record does not exist.
   */
  private static String[] queryPersistedNameParts(String recordId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT name, firstname, lastname FROM ad_user WHERE ad_user_id = ?")) {
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
