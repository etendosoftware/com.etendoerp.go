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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
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
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Post-hook for the Goods Shipment header entity.
 *
 * Appends {@code invoiceStatus} (0–100 integer) to every GET response by
 * calling the existing {@code C_GETINVOICESTATUSFROMSHIPMENT} DB function.
 * List responses use a single batch IN query to avoid N+1.
 */
@Named("goodsShipmentHeaderHandler")
public class GoodsShipmentHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsShipmentHeaderHandler.class);
  private static final String FIELD_INVOICE_STATUS = "invoiceStatus";

  @Inject
  private CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoHeaderActionRouter.dispatch(context, createDraftInvoiceHandler);
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
        record.put(FIELD_INVOICE_STATUS, computeSingle(context.getRecordId()));
        enrichIssuerOrg(record, context.getRecordId());
      } else {
        annotateBatch(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error computing invoiceStatus for goods shipment", e);
      return null;
    }
  }

  private void enrichIssuerOrg(JSONObject record, String recordId) {
    try {
      OBContext.setAdminMode(true);
      ShipmentInOut shipment = OBDal.getReadOnlyInstance().get(ShipmentInOut.class, recordId);
      if (shipment == null) {
        return;
      }
      String orgId = shipment.getOrganization().getId();
      JSONObject orgInfo = NeoSessionService.resolveOrganization(orgId);
      if (orgInfo != null) {
        record.put("issuerOrg", orgInfo);
      }
    } catch (Exception e) {
      log.warn("Could not enrich issuer org for shipment {}: {}", recordId, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void annotateBatch(JSONArray dataArr) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<String, Integer> statusMap = computeBatch(ids);
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String id = rec.optString("id", null);
      if (id != null) {
        rec.put(FIELD_INVOICE_STATUS, statusMap.getOrDefault(id, 0));
      }
    }
  }

  // placeholders contains only "?" literals — all values bound via setString(). No injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, Integer> computeBatch(List<String> ids) {
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql =
        "SELECT iol.m_inout_id, " +
        "  CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 " +
        "       ELSE LEAST(100, ROUND( " +
        "         COALESCE(SUM(CASE WHEN inv.docstatus = 'CO' THEN ABS(invl.qtyinvoiced) ELSE 0 END), 0) " +
        "         / SUM(ABS(iol.movementqty)) * 100 " +
        "       )) " +
        "  END " +
        "FROM m_inoutline iol " +
        "LEFT JOIN c_invoiceline invl ON invl.m_inoutline_id = iol.m_inoutline_id " +
        "LEFT JOIN c_invoice inv ON inv.c_invoice_id = invl.c_invoice_id " +
        "  AND inv.isactive = 'Y' AND inv.docstatus = 'CO' " +
        "WHERE iol.isactive = 'Y' AND iol.m_inout_id IN (" + placeholders + ") " +
        "GROUP BY iol.m_inout_id";
    Map<String, Integer> result = new HashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) {
        ps.setString(i + 1, ids.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), rs.getInt(2));
        }
      }
    } catch (Exception e) {
      log.error("DB error in batch invoice status computation for goods shipment", e);
    }
    return result;
  }

  private int computeSingle(String shipmentId) {
    String sql =
        "SELECT CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 " +
        "            ELSE LEAST(100, ROUND( " +
        "              COALESCE(SUM(CASE WHEN inv.docstatus = 'CO' THEN ABS(invl.qtyinvoiced) ELSE 0 END), 0) " +
        "              / SUM(ABS(iol.movementqty)) * 100 " +
        "            )) " +
        "       END " +
        "FROM m_inoutline iol " +
        "LEFT JOIN c_invoiceline invl ON invl.m_inoutline_id = iol.m_inoutline_id " +
        "LEFT JOIN c_invoice inv ON inv.c_invoice_id = invl.c_invoice_id " +
        "  AND inv.isactive = 'Y' AND inv.docstatus = 'CO' " +
        "WHERE iol.isactive = 'Y' AND iol.m_inout_id = ?";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (Exception e) {
      log.error("DB error computing invoice status for shipment {}", shipmentId, e);
      return 0;
    }
  }
}
