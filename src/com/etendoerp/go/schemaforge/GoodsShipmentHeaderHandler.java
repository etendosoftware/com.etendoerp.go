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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

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
  private static final String CAN_RETURN_SQL =
      "SELECT 1 FROM m_inoutline sil" +
      " WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'" +
      "   AND ABS(sil.movementqty) > (" +
      "     SELECT COALESCE(SUM(ABS(rl.movementqty)), 0)" +
      "     FROM m_inoutline rl" +
      "     JOIN m_inout r ON r.m_inout_id = rl.m_inout_id" +
      "     WHERE rl.canceled_inoutline_id = sil.m_inoutline_id" +
      "       AND rl.isactive = 'Y' AND r.isactive = 'Y'" +
      "       AND r.docstatus <> 'VO'" +
      "   )";
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";

  @Inject
  private CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  private NeoCloneRecordHandler neoCloneRecordHandler;

  @Inject
  private CreateReturnReceiptHandler createReturnReceiptHandler;

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
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
        enrichLinkedOrder(shipmentRec, context.getRecordId());
        enrichLinkedInvoices(shipmentRec, context.getRecordId());
        enrichReturnReceipts(shipmentRec, context.getRecordId());
        enrichCanCreateReturn(shipmentRec, context.getRecordId());
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

  @SuppressWarnings("java:S2077")
  private void enrichLinkedOrder(JSONObject shipmentRec, String shipmentId) {
    // Union: orders linked via header C_Order_ID + orders linked via imported lines
    String sql =
        "SELECT DISTINCT co.c_order_id, co.documentno, co.grandtotal, co.docstatus, cur.iso_code " +
        "FROM c_order co " +
        "LEFT JOIN c_currency cur ON cur.c_currency_id = co.c_currency_id " +
        "WHERE co.isactive = 'Y' AND co.c_order_id IN (" +
        "  SELECT io.c_order_id FROM m_inout io WHERE io.m_inout_id = ? AND io.c_order_id IS NOT NULL" +
        "  UNION" +
        "  SELECT ol.c_order_id FROM m_inoutline il JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id" +
        "  WHERE il.m_inout_id = ? AND il.isactive = 'Y'" +
        ")";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      ps.setString(2, shipmentId);
      JSONArray orders = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject order = new JSONObject();
          order.put("id", rs.getString(1));
          order.put(FIELD_DOCUMENT_NO, rs.getString(2));
          BigDecimal orderTotal = rs.getBigDecimal(3);
          order.put("grandTotalAmount", orderTotal != null ? orderTotal : JSONObject.NULL);
          order.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
          order.put("currency$_identifier", rs.getString(5));
          orders.put(order);
        }
      }
      shipmentRec.put("linkedOrders", orders);
    } catch (Exception e) {
      log.warn("Could not enrich linked orders for shipment {}: {}", shipmentId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedInvoices(JSONObject shipmentRec, String shipmentId) {
    // Covers both flows with a single scan:
    // - invoice created FROM this shipment: c_invoiceline.m_inoutline_id = shipment line
    // - shipment created FROM invoice (via order): shared c_orderline_id
    String sql =
        "SELECT DISTINCT i.c_invoice_id, i.documentno, i.grandtotal, i.docstatus, cur.iso_code " +
        "FROM m_inoutline sil " +
        "JOIN c_invoiceline il ON (" +
        "  il.m_inoutline_id = sil.m_inoutline_id " +
        "  OR (sil.c_orderline_id IS NOT NULL AND il.c_orderline_id = sil.c_orderline_id)" +
        ") " +
        "JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id " +
        "LEFT JOIN c_currency cur ON cur.c_currency_id = i.c_currency_id " +
        "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y' " +
        "  AND i.isactive = 'Y' AND i.docstatus NOT IN ('VO', 'CL')";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      JSONArray invoices = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject inv = new JSONObject();
          inv.put("id", rs.getString(1));
          inv.put(FIELD_DOCUMENT_NO, rs.getString(2));
          BigDecimal invTotal = rs.getBigDecimal(3);
          inv.put("grandTotalAmount", invTotal != null ? invTotal : JSONObject.NULL);
          inv.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
          inv.put("currency$_identifier", rs.getString(5));
          invoices.put(inv);
        }
      }
      shipmentRec.put("linkedInvoices", invoices);
    } catch (Exception e) {
      log.warn("Could not enrich linked invoices for shipment {}: {}", shipmentId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichReturnReceipts(JSONObject shipmentRec, String shipmentId) {
    String sql =
        "SELECT DISTINCT rio.m_inout_id, rio.documentno, rio.docstatus " +
        "FROM m_inoutline ril " +
        "JOIN m_inout rio ON rio.m_inout_id = ril.m_inout_id " +
        "WHERE ril.canceled_inoutline_id IN (" +
        "  SELECT sil.m_inoutline_id FROM m_inoutline sil " +
        "  WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'" +
        ") AND ril.isactive = 'Y' AND rio.isactive = 'Y'";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, shipmentId);
      JSONArray returns = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject ret = new JSONObject();
          ret.put("id", rs.getString(1));
          ret.put(FIELD_DOCUMENT_NO, rs.getString(2));
          ret.put(FIELD_DOCUMENT_STATUS, rs.getString(3));
          returns.put(ret);
        }
      }
      shipmentRec.put("returnReceipts", returns);
    } catch (Exception e) {
      log.warn("Could not enrich return receipts for shipment {}: {}", shipmentId, e.getMessage());
    }
  }

  /**
   * Sets {@code canCreateReturn=true} when at least one shipment line still has
   * returnable quantity (original qty > sum of non-voided return receipt qty).
   * Voided returns (docstatus='VO') are excluded so they don't block new returns.
   */
  @SuppressWarnings("java:S2077")
  private void enrichCanCreateReturn(JSONObject shipmentRec, String shipmentId) {
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(CAN_RETURN_SQL)) {
      ps.setString(1, shipmentId);
      try (ResultSet rs = ps.executeQuery()) {
        boolean canReturn = rs.next();
        shipmentRec.put("canCreateReturn", canReturn);
      }
    } catch (SQLException | JSONException e) {
      log.warn("Could not compute canCreateReturn for shipment {}: {}", shipmentId, e.getMessage());
    }
  }

  private static String buildInvoiceStatusSql(String whereClause) {
    return
      "SELECT iol.m_inout_id, " +
      "  CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 " +
      "       ELSE LEAST(100, ROUND( " +
      "         COALESCE(SUM(GREATEST( " +
      "           COALESCE(msi_qty.qtymatched, 0), " +
      "           COALESCE(direct_qty.qtyinvoiced, 0), " +
      "           COALESCE(ol_qty.qtyinvoiced, 0) " +
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
      // Invoice created from the order (m_inoutline_id is NULL on the invoice line):
      // join via c_orderline_id to find the shipment line.
      "LEFT JOIN ( " +
      "  SELECT iol2.m_inoutline_id, SUM(ABS(il3.qtyinvoiced)) AS qtyinvoiced " +
      "  FROM c_invoiceline il3 " +
      "  JOIN c_invoice i3 ON i3.c_invoice_id = il3.c_invoice_id " +
      "  JOIN m_inoutline iol2 ON iol2.c_orderline_id = il3.c_orderline_id " +
      "                        AND iol2.isactive = 'Y' " +
      "  WHERE i3.docstatus NOT IN ('VO','CL','DR') AND i3.isactive = 'Y' " +
      "    AND il3.c_orderline_id IS NOT NULL " +
      "    AND il3.m_inoutline_id IS NULL " +
      "  GROUP BY iol2.m_inoutline_id " +
      ") ol_qty ON ol_qty.m_inoutline_id = iol.m_inoutline_id " +
      "WHERE iol.isactive = 'Y' AND " + whereClause + " " +
      "GROUP BY iol.m_inout_id";
  }

}
