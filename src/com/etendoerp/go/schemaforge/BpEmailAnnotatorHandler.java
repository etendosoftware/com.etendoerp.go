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

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Reusable post-hook that enriches GET responses with {@code bpEmail} fetched from
 * {@code C_BPartner.EM_Etgo_Email}, using the {@code businessPartner} FK present in each record.
 *
 * <p>Designed to be injected into header handlers (sales-invoice, purchase-invoice, …) and called
 * from their {@code afterHandle()} method. Single-record GETs use a LIMIT 1 query; list GETs use
 * a single batch IN query to avoid N+1.
 *
 * <p>This handler is not registered against any entity {@code Java_Qualifier} — the unique
 * {@code @Named} value exists only so CDI can resolve it for {@code @Inject}.
 */
@ApplicationScoped
@Named("bpEmailAnnotatorHandler")
public class BpEmailAnnotatorHandler implements NeoHandler {

  private static final String FIELD_BUSINESS_PARTNER = "businessPartner";
  private static final String FIELD_BP_EMAIL = "bpEmail";

  private final Logger log = LogManager.getLogger(getClass());

  /**
   * Not used: this is a post-hook helper, not a primary request handler.
   *
   * @param context the NeoContext
   * @return always {@code null} so the default service runs
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  /**
   * Annotates the GET response body with {@code bpEmail} on every record.
   * Single-record GETs perform one query; list GETs perform a single batch query.
   *
   * @param context the NeoContext whose {@code previousResult} carries the default service body
   * @return the same body wrapped in a 200 response, or {@code null} to keep the original
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        String bPartnerId = rec.optString(FIELD_BUSINESS_PARTNER, null);
        rec.put(FIELD_BP_EMAIL, fetchEmail(bPartnerId));
      } else {
        annotateBatch(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error annotating bpEmail on response", e);
      return null;
    }
  }

  /**
   * Annotates every record in a list GET response with {@code bpEmail} using a single batch query.
   * Records without a {@code businessPartner} field receive an empty string.
   *
   * @param dataArr the {@code response.data} array from the NEO response body
   */
  private void annotateBatch(JSONArray dataArr) throws Exception {
    List<String> bPartnerIds = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString(FIELD_BUSINESS_PARTNER, null);
      if (id != null && !id.isEmpty()) {
        bPartnerIds.add(id);
      }
    }
    if (bPartnerIds.isEmpty()) {
      return;
    }
    Map<String, String> emailByBPartnerId = fetchEmailBatch(bPartnerIds);
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String bpId = rec.optString(FIELD_BUSINESS_PARTNER, null);
      rec.put(FIELD_BP_EMAIL, bpId != null ? emailByBPartnerId.getOrDefault(bpId, "") : "");
    }
  }

  /**
   * Queries {@code C_BPartner.EM_Etgo_Email} for a single business partner.
   *
   * @param bPartnerId the {@code C_BPartner_ID} value; returns empty string if null or blank
   * @return the email address, or an empty string if not found or on DB error
   */
  private String fetchEmail(String bPartnerId) {
    if (bPartnerId == null || bPartnerId.isEmpty()) {
      return "";
    }
    String sql = "SELECT EM_Etgo_Email FROM C_BPartner WHERE C_BPartner_ID = ? AND IsActive = 'Y'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, bPartnerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String email = rs.getString(1);
          return email != null ? email : "";
        }
      }
    } catch (Exception e) {
      log.error("DB error fetching bpEmail for bPartner {}", bPartnerId, e);
    }
    return "";
  }

  /**
   * Queries {@code C_BPartner.EM_Etgo_Email} for a list of business partners in a single IN query.
   * Returns a map of {@code C_BPartner_ID → email}; partners with no email are absent from the map.
   *
   * <p>placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
   *
   * @param bPartnerIds non-empty list of {@code C_BPartner_ID} values to look up
   * @return map of bPartnerId to email address; empty map on DB error
   */
  @SuppressWarnings("java:S2077")
  private Map<String, String> fetchEmailBatch(List<String> bPartnerIds) {
    Map<String, String> result = new HashMap<>();
    String placeholders = String.join(",", bPartnerIds.stream().map(id -> "?").toArray(String[]::new));
    String sql = "SELECT C_BPartner_ID, EM_Etgo_Email FROM C_BPartner WHERE C_BPartner_ID IN (" + placeholders + ") AND IsActive = 'Y'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < bPartnerIds.size(); i++) {
        ps.setString(i + 1, bPartnerIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String email = rs.getString(2);
          result.put(rs.getString(1), email != null ? email : "");
        }
      }
    } catch (Exception e) {
      log.error("DB error in batch bpEmail fetch", e);
    }
    return result;
  }
}
