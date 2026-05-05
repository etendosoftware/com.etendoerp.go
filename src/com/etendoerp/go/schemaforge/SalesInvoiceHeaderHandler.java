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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the Sales Invoice header entity.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler} (uses {@code CloneInvoiceHook})</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentHandler}</li>
 * </ul>
 *
 * GET post-hook: appends {@code bpEmail} (from {@code C_BPartner.EM_Etgo_Email}) to every
 * record so the Send Email modal can pre-fill the recipient address.
 */
@Named("salesInvoiceHeaderHandler")
public class SalesInvoiceHeaderHandler implements NeoHandler {

  private final Logger log = LogManager.getLogger(getClass());

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private RegisterPaymentHandler registerPaymentHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoHeaderActionRouter.dispatch(
        context,
        cloneRecordHandler,
        registerPaymentHandler);
  }

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
        JSONObject record = dataArr.getJSONObject(0);
        String bPartnerId = record.optString("businessPartner", null);
        record.put("bpEmail", fetchEmail(bPartnerId));
      } else {
        annotateBatch(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error annotating bpEmail on sales invoice response", e);
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
      String id = dataArr.getJSONObject(i).optString("businessPartner", null);
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
      String bpId = rec.optString("businessPartner", null);
      rec.put("bpEmail", bpId != null ? emailByBPartnerId.getOrDefault(bpId, "") : "");
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
