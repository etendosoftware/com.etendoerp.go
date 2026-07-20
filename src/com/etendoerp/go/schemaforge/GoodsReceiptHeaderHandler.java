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

import java.math.BigDecimal;
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
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.Location;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * NeoHandler for the Goods Receipt header entity.
 *
 * <p>Routes custom ACTION requests:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createPurchaseInvoice} → {@link CreatePurchaseInvoiceHandler}</li>
 *   <li>{@code createPurchaseReturn} → {@link CreatePurchaseReturnHandler}</li>
 * </ul>
 */
@Named("goodsReceiptHeaderHandler")
public class GoodsReceiptHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsReceiptHeaderHandler.class);
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";
  private static final String FIELD_MOVEMENT_DATE = "movementDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CreatePurchaseInvoiceHandler createPurchaseInvoiceHandler;

  @Inject
  private CreatePurchaseReturnHandler createPurchaseReturnHandler;

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    mirrorAccountingDate(context);
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equals(context.getHttpMethod())
        && context.getRecordId() == null) {
      injectPartnerAddressIfMissing(context);
    }
    return NeoHeaderActionRouter.dispatch(context,
        cloneRecordHandler, createPurchaseInvoiceHandler, createPurchaseReturnHandler);
  }

  /**
   * Mirrors the single visible {@code movementDate} field into the hidden
   * {@code accountingDate} field on the request body, unconditionally, before the default CRUD
   * path persists it (ETP-4531 — unified date). The user never sees or edits accountingDate
   * directly; whatever value is saved for movementDate (create or update) must also become the
   * receipt's accounting date.
   *
   * <p><b>ETP-4531 fix:</b> must fire on {@code PATCH} as well as {@code POST}/{@code PUT} — the
   * live React UI ({@code useEntity.js#getMethod}) always sends {@code PATCH} for edits to an
   * EXISTING receipt; it never sends a full {@code PUT}. See {@link NeoHandlerUtils#isWriteMethod}.
   */
  static void mirrorAccountingDate(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      NeoHandlerUtils.mirrorFieldValue(context.getRequestBody(), FIELD_MOVEMENT_DATE, FIELD_ACCOUNTING_DATE);
    }
  }

  private void injectPartnerAddressIfMissing(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null || body.has("partnerAddress")) {
        return;
      }
      String bpId = body.optString("businessPartner", null);
      if (bpId == null || bpId.isEmpty()) {
        return;
      }
      OBContext.setAdminMode(true);
      try {
        Location loc = findShipToLocation(bpId);
        if (loc == null) {
          loc = findAnyActiveLocation(bpId);
        }
        if (loc != null) {
          body.put("partnerAddress", loc.getId());
          log.debug("Injected partnerAddress {} for BP {} on goods receipt create", loc.getId(), bpId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.debug("Could not inject partnerAddress for goods receipt: {}", e.getMessage());
    }
  }

  private Location findShipToLocation(String bpId) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", bpId));
    crit.add(Restrictions.eq(Location.PROPERTY_SHIPTOADDRESS, true));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    return (Location) crit.uniqueResult();
  }

  private Location findAnyActiveLocation(String bpId) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", bpId));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    return (Location) crit.uniqueResult();
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
        JSONObject rec = dataArr.getJSONObject(0);
        rec.put("invoiceStatus", computeInvoiceStatus(context.getRecordId()));
        rec.put("returnStatus", computeReturnStatus(context.getRecordId()));
        enrichLinkedInvoices(rec, context.getRecordId());
        enrichLinkedOrder(rec, context.getRecordId());
        enrichLinkedReturns(rec, context.getRecordId());
      } else {
        List<String> ids = NeoHandlerUtils.collectIds(dataArr);
        Map<String, Integer> statusMap = computeInvoiceStatusBatch(ids);
        for (int i = 0; i < dataArr.length(); i++) {
          JSONObject rec = dataArr.getJSONObject(i);
          String id = rec.optString("id", null);
          if (id != null) {
            rec.put("invoiceStatus", statusMap.getOrDefault(id, 0));
          }
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching goods receipt header", e);
      return null;
    }
  }

  private int computeInvoiceStatus(String receiptId) {
    String sql = buildInvoiceStatusSql("iol.m_inout_id = ?");
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(2) : 0;
      }
    } catch (Exception e) {
      log.error("DB error computing invoice status for receipt {}", receiptId, e);
      return 0;
    }
  }

  // placeholders contains only "?" literals — no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, Integer> computeInvoiceStatusBatch(List<String> ids) {
    if (ids.isEmpty()) return new HashMap<>();
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = buildInvoiceStatusSql("iol.m_inout_id IN (" + placeholders + ")");
    Map<String, Integer> result = new HashMap<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
      }
    } catch (Exception e) {
      log.error("DB error in batch invoice status for receipts", e);
    }
    return result;
  }

  private static String buildInvoiceStatusSql(String whereClause) {
    // Three detection paths per receipt line, to handle the full invoice lifecycle:
    //
    // msi_qty   — via M_MatchSI: populated by m_inout_post after the receipt is posted.
    // direct_qty — via c_invoiceline.m_inoutline_id: set by InvoiceLineLinker when the
    //              invoice was created directly from this receipt.
    // ol_qty    — via c_orderline_id fallback: covers invoices created from the purchase
    //              order (not from the receipt) where m_inoutline_id is never set.
    //              Each line's contribution is capped at movementqty to avoid over-stating
    //              when the order invoice covers more units than this receipt delivered.
    return
      "SELECT iol.m_inout_id, "
      + "  CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 "
      + "       ELSE LEAST(100, ROUND("
      + "         COALESCE(SUM(LEAST("
      + "           GREATEST("
      + "             COALESCE(msi_qty.qtymatched, 0),"
      + "             COALESCE(direct_qty.qtyinvoiced, 0),"
      + "             COALESCE(ol_qty.qtyinvoiced_via_ol, 0)"
      + "           ),"
      + "           ABS(iol.movementqty)"
      + "         )), 0) / SUM(ABS(iol.movementqty)) * 100"
      + "       )) "
      + "  END "
      + "FROM m_inoutline iol "
      + "LEFT JOIN ("
      + "  SELECT msi.m_inoutline_id, SUM(ABS(msi.qty)) AS qtymatched "
      + "  FROM m_matchsi msi "
      + "  JOIN c_invoiceline il ON il.c_invoiceline_id = msi.c_invoiceline_id "
      + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
      + "  WHERE i.docstatus NOT IN ('VO','CL','DR') AND i.isactive = 'Y' "
      + "  GROUP BY msi.m_inoutline_id "
      + ") msi_qty ON msi_qty.m_inoutline_id = iol.m_inoutline_id "
      + "LEFT JOIN ("
      + "  SELECT il2.m_inoutline_id, SUM(ABS(il2.qtyinvoiced)) AS qtyinvoiced "
      + "  FROM c_invoiceline il2 "
      + "  JOIN c_invoice i2 ON i2.c_invoice_id = il2.c_invoice_id "
      + "  WHERE i2.docstatus NOT IN ('VO','CL','DR') AND i2.isactive = 'Y' "
      + "  GROUP BY il2.m_inoutline_id "
      + ") direct_qty ON direct_qty.m_inoutline_id = iol.m_inoutline_id "
      + "LEFT JOIN ("
      + "  SELECT iol2.m_inoutline_id, SUM(ABS(il3.qtyinvoiced)) AS qtyinvoiced_via_ol "
      + "  FROM c_invoiceline il3 "
      + "  JOIN c_invoice i3 ON i3.c_invoice_id = il3.c_invoice_id "
      + "  JOIN m_inoutline iol2 ON iol2.c_orderline_id = il3.c_orderline_id "
      + "  WHERE i3.docstatus NOT IN ('VO','CL','DR') AND i3.isactive = 'Y' "
      + "    AND il3.m_inoutline_id IS NULL "
      + "    AND il3.c_orderline_id IS NOT NULL "
      + "  GROUP BY iol2.m_inoutline_id "
      + ") ol_qty ON ol_qty.m_inoutline_id = iol.m_inoutline_id "
      + "WHERE iol.isactive = 'Y' AND " + whereClause + " "
      + "GROUP BY iol.m_inout_id";
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedInvoices(JSONObject rec, String receiptId) {
    String sql =
        "SELECT DISTINCT i.c_invoice_id, i.documentno, i.grandtotal, i.docstatus, cur.iso_code "
        + "FROM m_inoutline ril "
        + "JOIN c_invoiceline il ON ("
        + "  il.m_inoutline_id = ril.m_inoutline_id "
        + "  OR (ril.c_orderline_id IS NOT NULL AND il.c_orderline_id = ril.c_orderline_id)"
        + ") "
        + "JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
        + "LEFT JOIN c_currency cur ON cur.c_currency_id = i.c_currency_id "
        + "WHERE ril.m_inout_id = ? AND ril.isactive = 'Y' "
        + "  AND i.isactive = 'Y' AND i.docstatus NOT IN ('VO','CL')";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      JSONArray invoices = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject inv = new JSONObject();
          inv.put("id", rs.getString(1));
          inv.put(FIELD_DOCUMENT_NO, rs.getString(2));
          BigDecimal total = rs.getBigDecimal(3);
          inv.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
          inv.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
          inv.put("currency$_identifier", rs.getString(5));
          invoices.put(inv);
        }
      }
      rec.put("linkedInvoices", invoices);
    } catch (Exception e) {
      log.warn("Could not enrich linked invoices for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  private int computeReturnStatus(String receiptId) {
    String sql =
        "SELECT CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 "
        + "ELSE LEAST(100, ROUND("
        + "  COALESCE(SUM(ABS(ril.movementqty)), 0) / SUM(ABS(iol.movementqty)) * 100"
        + ")) END "
        + "FROM m_inoutline iol "
        + "LEFT JOIN m_inoutline ril ON ril.canceled_inoutline_id = iol.m_inoutline_id "
        + "  AND ril.isactive = 'Y' "
        + "  AND EXISTS ("
        + "    SELECT 1 FROM m_inout rio WHERE rio.m_inout_id = ril.m_inout_id"
        + "    AND rio.docstatus NOT IN ('VO','DR') AND rio.isactive = 'Y'"
        + "  ) "
        + "WHERE iol.m_inout_id = ? AND iol.isactive = 'Y'";
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (Exception e) {
      log.error("DB error computing return status for receipt {}", receiptId, e);
      return 0;
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedReturns(JSONObject rec, String receiptId) {
    String sql =
        "SELECT DISTINCT rio.m_inout_id, rio.documentno, rio.docstatus "
        + "FROM m_inoutline ril "
        + "JOIN m_inout rio ON rio.m_inout_id = ril.m_inout_id "
        + "WHERE ril.canceled_inoutline_id IN ("
        + "  SELECT sil.m_inoutline_id FROM m_inoutline sil "
        + "  WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'"
        + ") AND ril.isactive = 'Y' AND rio.isactive = 'Y'";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
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
      rec.put("linkedReturns", returns);
    } catch (Exception e) {
      log.warn("Could not enrich linked returns for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedOrder(JSONObject rec, String receiptId) {
    String sql =
        "SELECT DISTINCT co.c_order_id, co.documentno, co.grandtotal, co.docstatus, cur.iso_code "
        + "FROM c_order co "
        + "LEFT JOIN c_currency cur ON cur.c_currency_id = co.c_currency_id "
        + "WHERE co.isactive = 'Y' AND co.c_order_id IN ("
        + "  SELECT io.c_order_id FROM m_inout io WHERE io.m_inout_id = ? AND io.c_order_id IS NOT NULL"
        + "  UNION"
        + "  SELECT ol.c_order_id FROM m_inoutline il JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id"
        + "  WHERE il.m_inout_id = ? AND il.isactive = 'Y'"
        + ")";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      ps.setString(2, receiptId);
      JSONArray orders = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject order = new JSONObject();
          order.put("id", rs.getString(1));
          order.put(FIELD_DOCUMENT_NO, rs.getString(2));
          BigDecimal total = rs.getBigDecimal(3);
          order.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
          order.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
          order.put("currency$_identifier", rs.getString(5));
          orders.put(order);
        }
      }
      rec.put("linkedOrders", orders);
    } catch (Exception e) {
      log.warn("Could not enrich linked orders for receipt {}: {}", receiptId, e.getMessage());
    }
  }
}
