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

import javax.inject.Named;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Pre/post-save hook for the businessPartner entity in the contacts spec.
 *
 * <p>On GET (defaults endpoint):
 * <ul>
 *   <li>{@code afterHandle()} replaces the incorrect AD-sequence placeholder for
 *       {@code etgoIdentifier} with the real next value from the
 *       {@code BusinessPartner-EM_Etgo_Identifier} AD sequence.</li>
 * </ul>
 *
 * <p>On POST (new record):
 * <ul>
 *   <li>{@code handle()} injects {@code searchKey = name} as a temporary placeholder so the
 *       mandatory field passes validation before the record is saved.</li>
 *   <li>{@code afterHandle()} overwrites {@code searchKey} with the auto-generated
 *       {@code em_etgo_identifier} value once Etendo has assigned it during save.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'businessPartnerHandler'} on the
 * ETGO_SF_ENTITY record for the contacts spec's businessPartner entity.
 */
@Named("businessPartnerHandler")
public class BusinessPartnerHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BusinessPartnerHandler.class);
  private static final String FIELD_SEARCH_KEY = "searchKey";
  private static final String FIELD_ETGO_IDENTIFIER = "etgoIdentifier";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (!"POST".equals(ctx.getHttpMethod())) {
      return null;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      String name = body.optString("name", null);
      if (StringUtils.isNotBlank(name) && !body.has(FIELD_SEARCH_KEY)) {
        body.put(FIELD_SEARCH_KEY, name);
      }
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error injecting temporary searchKey", e);
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    if ("GET".equals(ctx.getHttpMethod())) {
      return patchDefaultsIdentifier(ctx);
    }
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

  private NeoResponse patchDefaultsIdentifier(NeoContext ctx) {
    NeoResponse previousResult = ctx.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    JSONObject body = previousResult.getBody();
    if (!body.has("defaults")) {
      return null;
    }
    try {
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      long nextValue = queryNextIdentifier(clientId);
      if (nextValue <= 0) {
        return null;
      }
      body.getJSONObject("defaults").put(FIELD_ETGO_IDENTIFIER, "<" + nextValue + ">");
      JSONArray seqFields = body.optJSONArray("sequenceFields");
      if (seqFields == null) {
        seqFields = new JSONArray();
        body.put("sequenceFields", seqFields);
      }
      boolean alreadyPresent = false;
      for (int i = 0; i < seqFields.length(); i++) {
        if (FIELD_ETGO_IDENTIFIER.equals(seqFields.getString(i))) {
          alreadyPresent = true;
          break;
        }
      }
      if (!alreadyPresent) {
        seqFields.put(FIELD_ETGO_IDENTIFIER);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error patching defaults with etgoIdentifier", e);
      return null;
    }
  }

  private static long queryNextIdentifier(String clientId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT currentnext FROM ad_sequence" +
        " WHERE name = 'BusinessPartner-EM_Etgo_Identifier'" +
        " AND ad_client_id = ?" +
        " ORDER BY currentnext DESC LIMIT 1")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getLong(1);
        }
      }
    }
    return -1;
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
    try (PreparedStatement ps = conn.prepareStatement(
        "UPDATE c_bpartner SET value = ? WHERE c_bpartner_id = ?")) {
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
}
