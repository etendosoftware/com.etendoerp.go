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

  @Inject
  private NeoCloneRecordHandler neoCloneRecordHandler;

  @Inject
  private CreateReturnReceiptHandler createReturnReceiptHandler;

  @Override
  public NeoResponse handle(NeoContext context) {
    return NeoHeaderActionRouter.dispatch(context,
        createDraftInvoiceHandler, neoCloneRecordHandler, createReturnReceiptHandler);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      if (context.getRecordId() != null) {
        JSONObject shipmentRec = dataArr.getJSONObject(0);
        shipmentRec.put(FIELD_INVOICE_STATUS, computeSingle(context.getRecordId()));
        enrichIssuerOrg(shipmentRec, context.getRecordId());
        enrichReturnReceipts(shipmentRec, context.getRecordId());
        enrichRelatedInvoices(shipmentRec, context.getRecordId());
      } else {
        annotateBatch(dataArr);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error computing invoiceStatus for goods shipment", e);
      return null;
    }
  }

  private void enrichIssuerOrg(JSONObject shipmentRec, String recordId) {
    try {
      OBContext.setAdminMode(true);
      ShipmentInOut shipment = OBDal.getReadOnlyInstance().get(ShipmentInOut.class, recordId);
      if (shipment == null) {
        return;
      }
      String orgId = shipment.getOrganization().getId();
      JSONObject orgInfo = NeoSessionService.resolveOrganization(orgId);
      if (orgInfo != null) {
        shipmentRec.put("issuerOrg", orgInfo);
      }
    } catch (Exception e) {
      log.warn("Could not enrich issuer org for shipment {}: {}", recordId, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void annotateBatch(JSONArray dataArr) throws Exception {
    List<String> ids = NeoHandlerUtils.collectIds(dataArr);
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
  // Uses GREATEST(m_matchsi qty, direct FK qty) per line to cover both Etendo-classic matching
  // and NEO-created invoices without double-counting.
  @SuppressWarnings("java:S2077")
  private Map<String, Integer> computeBatch(List<String> ids) {
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = buildInvoiceStatusSql("iol.m_inout_id IN (" + placeholders + ")");
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
    String sql = buildInvoiceStatusSql("iol.m_inout_id = ?");
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(2) : 0;
      }
    } catch (Exception e) {
      log.error("DB error computing invoice status for shipment {}", shipmentId, e);
      return 0;
    }
  }

  private static String buildInvoiceStatusSql(String whereClause) {
    return
      "SELECT iol.m_inout_id, " +
      "  CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 " +
      "       ELSE LEAST(100, ROUND( " +
      "         COALESCE(SUM(GREATEST( " +
      "           COALESCE(msi_qty.qtymatched, 0), " +
      "           COALESCE(direct_qty.qtyinvoiced, 0) " +
      "         )), 0) / SUM(ABS(iol.movementqty)) * 100 " +
      "       )) " +
      "  END " +
      "FROM m_inoutline iol " +
      "LEFT JOIN ( " +
      "  SELECT msi.m_inoutline_id, SUM(ABS(msi.qty)) AS qtymatched " +
      "  FROM m_matchsi msi " +
      "  JOIN c_invoiceline il ON il.c_invoiceline_id = msi.c_invoiceline_id " +
      "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id " +
      "  WHERE i.docstatus NOT IN ('VO','CL','DR') AND i.isactive = 'Y' " +
      "  GROUP BY msi.m_inoutline_id " +
      ") msi_qty ON msi_qty.m_inoutline_id = iol.m_inoutline_id " +
      "LEFT JOIN ( " +
      "  SELECT il2.m_inoutline_id, SUM(ABS(il2.qtyinvoiced)) AS qtyinvoiced " +
      "  FROM c_invoiceline il2 " +
      "  JOIN c_invoice i2 ON i2.c_invoice_id = il2.c_invoice_id " +
      "  WHERE i2.docstatus NOT IN ('VO','CL','DR') AND i2.isactive = 'Y' " +
      "  GROUP BY il2.m_inoutline_id " +
      ") direct_qty ON direct_qty.m_inoutline_id = iol.m_inoutline_id " +
      "WHERE iol.isactive = 'Y' AND " + whereClause + " " +
      "GROUP BY iol.m_inout_id";
  }

  @SuppressWarnings("java:S2077")
  private void enrichReturnReceipts(JSONObject shipmentRec, String shipmentId) {
    String sql =
        "SELECT DISTINCT ret.M_InOut_ID, ret.DocumentNo, ret.DocStatus " +
        "FROM M_InOutLine src " +
        "JOIN M_InOutLine ret_line ON ret_line.Canceled_Inoutline_ID = src.M_InOutLine_ID " +
        "JOIN M_InOut ret ON ret.M_InOut_ID = ret_line.M_InOut_ID " +
        "WHERE src.M_InOut_ID = ? AND ret.DocStatus != 'VO'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      JSONArray arr = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject row = new JSONObject();
          row.put("id", rs.getString(1));
          row.put("documentNo", rs.getString(2));
          row.put("documentStatus", rs.getString(3));
          arr.put(row);
        }
      }
      shipmentRec.put("returnReceipts", arr);
    } catch (Exception e) {
      log.warn("Could not enrich returnReceipts for shipment {}: {}", shipmentId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichRelatedInvoices(JSONObject shipmentRec, String shipmentId) {
    String sql =
        "SELECT DISTINCT i.C_Invoice_ID, i.DocumentNo, i.DocStatus " +
        "FROM M_InOutLine iol " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = iol.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "WHERE iol.M_InOut_ID = ? AND i.DocStatus != 'VO' AND i.IsActive = 'Y'";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      JSONArray arr = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject row = new JSONObject();
          row.put("id", rs.getString(1));
          row.put("documentNo", rs.getString(2));
          row.put("documentStatus", rs.getString(3));
          arr.put(row);
        }
      }
      shipmentRec.put("relatedInvoices", arr);
    } catch (Exception e) {
      log.warn("Could not enrich relatedInvoices for shipment {}: {}", shipmentId, e.getMessage());
    }
  }
}
