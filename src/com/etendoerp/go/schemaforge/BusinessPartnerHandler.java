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

import java.util.Set;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

/**
 * Pre/post-save hook for the businessPartner entity in the contacts spec.
 *
 * <p>On POST (new record):
 * <ul>
 *   <li>{@code handle()} derives {@code name} from {@code etgoFirstname} + {@code etgoLastname}
 *       when {@code name} is blank and at least one of the name parts is present (person mode).</li>
 *   <li>{@code handle()} then injects {@code searchKey = name} as a temporary placeholder so the
 *       mandatory field passes validation before the record is saved.</li>
 *   <li>{@code afterHandle()} overwrites {@code searchKey} with the auto-generated
 *       {@code em_etgo_identifier} value once Etendo has assigned it during save.</li>
 * </ul>
 *
 * <p>On PATCH/PUT (update):
 * <ul>
 *   <li>{@code handle()} derives {@code name} from the incoming firstname/lastname values
 *       (merging with the persisted values for whichever part is absent from the body)
 *       only when the record's current {@code name} is blank in the database.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'businessPartnerHandler'} on the
 * ETGO_SF_ENTITY record for the contacts spec's businessPartner entity.
 */
@Named("businessPartnerHandler")
public class BusinessPartnerHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BusinessPartnerHandler.class);
  private static final String FIELD_SEARCH_KEY = "searchKey";
  private static final String FIELD_NAME = "name";

  private static final Set<String> PRECREATE_BILLING_FIELDS = Set.of("priceList", "paymentMethod", "paymentTerms",
      "account", "customerBlocking", "purchasePricelist", "pOPaymentMethod", "pOPaymentTerms", "pOFinancialAccount",
      "vendorBlocking");
  private static final String FIELD_FIRSTNAME = "etgoFirstname";
  private static final String FIELD_LASTNAME = "etgoLastname";

  /**
   * Concatenates non-blank parts separated by a single space.
   */
  private static String buildFullName(String firstname, String lastname) {
    String combined = (firstname + " " + lastname).trim();
    return combined.replaceAll("\\s{2,}", " ");
  }

  /**
   * Returns [name, em_etgo_firstname, em_etgo_lastname] for the given record.
   */
  private static String[] queryPersistedNameParts(String recordId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT name, em_etgo_firstname, em_etgo_lastname" + "  FROM c_bpartner WHERE c_bpartner_id = ?")) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return new String[]{ StringUtils.trimToEmpty(rs.getString(1)), StringUtils.trimToEmpty(
              rs.getString(2)), StringUtils.trimToEmpty(rs.getString(3)) };
        }
      }
    }
    return new String[]{ "", "", "" };
  }

  private static String extractRecordId(JSONObject body) {
    try {
      JSONObject response = body.optJSONObject("response");
      if (response == null) {
        return null;
      }
      JSONArray data = response.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return null;
      }
      String id = data.getJSONObject(0).optString("id", null);
      return StringUtils.isNotBlank(id) ? id : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String queryIdentifier(String recordId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT em_etgo_identifier FROM c_bpartner WHERE c_bpartner_id = ?")) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
      }
    }
    return null;
  }

  private static void updateSearchKey(String recordId, String identifier) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement("UPDATE c_bpartner SET value = ? WHERE c_bpartner_id = ?")) {
      ps.setString(1, identifier);
      ps.setString(2, recordId);
      ps.executeUpdate();
    }
  }

  private static void patchSearchKeyInResponse(JSONObject body, String identifier) {
    try {
      JSONObject response = body.optJSONObject("response");
      if (response == null) {
        return;
      }
      JSONArray data = response.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return;
      }
      data.getJSONObject(0).put(FIELD_SEARCH_KEY, identifier);
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not patch searchKey in response", e);
    }
  }

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
      deriveNameFromPerson(ctx, body);

      if ("POST".equals(method)) {
        stripPreCreateBillingDefaults(body);
        String name = body.optString(FIELD_NAME, null);
        if (StringUtils.isNotBlank(name) && !body.has(FIELD_SEARCH_KEY)) {
          body.put(FIELD_SEARCH_KEY, name);
        }
      }
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error in handle()", e);
      throw new OBException("Error processing BusinessPartner name derivation", e);
    }
    return null;
  }

  private void stripPreCreateBillingDefaults(JSONObject body) {
    for (String key : PRECREATE_BILLING_FIELDS) {
      body.remove(key);
      body.remove(key + "$_identifier");
    }
  }

  /**
   * Derives {@code name} from {@code etgoFirstname} + {@code etgoLastname} when:
   * <ul>
   *   <li>At least one of the name parts is present in the request body.</li>
   *   <li>The effective {@code name} is blank (body value for POST; DB value for PATCH/PUT).</li>
   * </ul>
   * If {@code name} already has a value it is left untouched.
   */
  private void deriveNameFromPerson(NeoContext ctx, JSONObject body) throws Exception {
    boolean hasFirstname = body.has(FIELD_FIRSTNAME);
    boolean hasLastname = body.has(FIELD_LASTNAME);
    if (!hasFirstname && !hasLastname) {
      return;
    }

    String firstname;
    String lastname;

    if ("POST".equals(ctx.getHttpMethod())) {
      // New record: name must be absent or blank to auto-fill.
      if (StringUtils.isNotBlank(body.optString(FIELD_NAME, null))) {
        return;
      }
      firstname = StringUtils.trimToEmpty(body.optString(FIELD_FIRSTNAME, ""));
      lastname = StringUtils.trimToEmpty(body.optString(FIELD_LASTNAME, ""));
    } else {
      // PATCH / PUT: check the persisted name; if already set, respect it.
      String recordId = ctx.getRecordId();
      if (StringUtils.isBlank(recordId)) {
        return;
      }
      String[] persisted = queryPersistedNameParts(recordId);
      // persisted[0] = name, persisted[1] = firstname, persisted[2] = lastname
      if (StringUtils.isNotBlank(persisted[0])) {
        return;
      }
      // Merge: body value takes precedence over persisted value for each part.
      firstname = hasFirstname ? StringUtils.trimToEmpty(body.optString(FIELD_FIRSTNAME, "")) : StringUtils.trimToEmpty(
          persisted[1]);
      lastname = hasLastname ? StringUtils.trimToEmpty(body.optString(FIELD_LASTNAME, "")) : StringUtils.trimToEmpty(
          persisted[2]);
    }

    String derived = buildFullName(firstname, lastname);
    if (StringUtils.isNotBlank(derived)) {
      body.put(FIELD_NAME, derived);
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    if (!"POST".equals(ctx.getHttpMethod())) {
      return null;
    }
    NeoResponse previousResult = ctx.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      String recordId = extractRecordId(previousResult.getBody());
      if (recordId == null) {
        return null;
      }
      String identifier = queryIdentifier(recordId);
      if (StringUtils.isBlank(identifier)) {
        return null;
      }
      updateSearchKey(recordId, identifier);
      patchSearchKeyInResponse(previousResult.getBody(), identifier);
      return NeoResponse.ok(previousResult.getBody());
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error updating searchKey from em_etgo_identifier", e);
      return null;
    }
  }
}
