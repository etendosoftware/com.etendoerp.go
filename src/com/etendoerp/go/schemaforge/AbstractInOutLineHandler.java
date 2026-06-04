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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Shared hook logic for M_InOutLine-based entities (goods receipts and shipments).
 *
 * <p>On POST: captures {@code invoiceLineId} from the request body before NeoFieldFilter
 * strips it, then sets {@code C_InvoiceLine.M_InOutLine_ID} after the line is created.
 * This marks the source invoice line as received so it no longer appears in the
 * "Add from Invoice" modal.
 *
 * <p>On GET: enriches each line with:
 * <ul>
 *   <li>{@code orderQuantity} — from {@code C_OrderLine.QtyOrdered} (authoritative for
 *       single-UOM products where {@code QuantityOrder} is typically null).</li>
 *   <li>{@code productCode} — the product search key ({@code M_Product.Value}).</li>
 *   <li>{@code invoicedQuantity} — already-invoiced qty across completed invoices.</li>
 * </ul>
 *
 * Subclasses only need to declare {@code @Named}.
 */
public abstract class AbstractInOutLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AbstractInOutLineHandler.class);
  private static final String FIELD_ORDER_QUANTITY    = "orderQuantity";
  private static final String FIELD_PRODUCT_CODE      = "productCode";
  private static final String FIELD_INVOICED_QUANTITY = "invoicedQuantity";

  // Captures invoiceLineId before NeoFieldFilter strips it. Cleared in afterHandle.
  private static final ThreadLocal<String> PENDING_INVOICE_LINE_ID = new ThreadLocal<>();

  private static final class LineData {
    final BigDecimal orderedQty;
    final String     productCode;
    final BigDecimal invoicedQty;
    LineData(BigDecimal orderedQty, String productCode, BigDecimal invoicedQty) {
      this.orderedQty   = orderedQty;
      this.productCode  = productCode;
      this.invoicedQty  = invoicedQty;
    }
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    PENDING_INVOICE_LINE_ID.remove();
    if ("POST".equalsIgnoreCase(context.getHttpMethod())) {
      JSONObject body = context.getRequestBody();
      if (body != null) {
        String invoiceLineId = body.optString("invoiceLineId", null);
        if (invoiceLineId != null && !invoiceLineId.isEmpty()) {
          PENDING_INVOICE_LINE_ID.set(invoiceLineId);
        }
      }
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if ("POST".equalsIgnoreCase(context.getHttpMethod())) {
      try {
        linkInvoiceLineIfPresent(context);
      } finally {
        PENDING_INVOICE_LINE_ID.remove();
      }
      return null;
    }
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      if (context.getPreviousResult() == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      List<String> lineIds = NeoHandlerUtils.collectIds(dataArr);
      Map<String, LineData> dataMap = fetchLineData(lineIds);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject line = dataArr.getJSONObject(i);
        String id = line.optString("id", null);
        LineData ld = dataMap.get(id);
        if (ld == null) {
          continue;
        }
        if (ld.orderedQty != null) {
          line.put(FIELD_ORDER_QUANTITY, ld.orderedQty);
        }
        if (ld.productCode != null && !ld.productCode.isEmpty()) {
          line.put(FIELD_PRODUCT_CODE, ld.productCode);
        }
        line.put(FIELD_INVOICED_QUANTITY, ld.invoicedQty != null ? ld.invoicedQty : BigDecimal.ZERO);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching inout lines", e);
      return null;
    }
  }

  private void linkInvoiceLineIfPresent(NeoContext context) {
    String invoiceLineId = PENDING_INVOICE_LINE_ID.get();
    if (invoiceLineId == null) return;
    try {
      NeoResponse prev = context.getPreviousResult();
      if (prev == null || prev.getBody() == null) return;
      JSONObject responseWrapper = prev.getBody().optJSONObject("response");
      if (responseWrapper == null) return;
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) return;
      String newLineId = dataArr.getJSONObject(0).optString("id", null);
      if (newLineId == null || newLineId.isEmpty()) return;

      OBDal.getInstance().getSession()
          .createNativeQuery(
              "UPDATE c_invoiceline SET m_inoutline_id = :lineId, updated = now() "
              + "WHERE c_invoiceline_id = :invLineId AND m_inoutline_id IS NULL")
          .setParameter("lineId", newLineId)
          .setParameter("invLineId", invoiceLineId)
          .executeUpdate();
    } catch (Exception e) {
      log.warn("Could not link invoice line after inout line creation: {}", e.getMessage());
    }
  }

  // placeholders contains only "?" literals — no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, LineData> fetchLineData(List<String> lineIds) {
    Map<String, LineData> result = new HashMap<>();
    if (lineIds.isEmpty()) {
      return result;
    }
    String placeholders = lineIds.stream().map(id -> "?").collect(Collectors.joining(","));
    // COALESCE(order line qty, invoice line qty) so receipt lines created from
    // an invoice (no c_orderline_id) still show the invoiced qty as "ordered qty".
    String sql =
        "SELECT il.m_inoutline_id,"
        + "  COALESCE(ol.qtyordered, src_il.qtyinvoiced) AS ordered_qty,"
        + "  p.value, "
        + "  COALESCE(("
        + "    SELECT SUM(ABS(cil.qtyinvoiced)) FROM c_invoiceline cil"
        + "    JOIN c_invoice ci ON ci.c_invoice_id = cil.c_invoice_id"
        + "    WHERE cil.m_inoutline_id = il.m_inoutline_id"
        + "      AND ci.docstatus NOT IN ('VO','CL','DR') AND ci.isactive = 'Y'"
        + "  ), 0) "
        + "FROM m_inoutline il "
        + "LEFT JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id "
        + "LEFT JOIN c_invoiceline src_il ON src_il.m_inoutline_id = il.m_inoutline_id "
        + "LEFT JOIN m_product p ON p.m_product_id = il.m_product_id "
        + "WHERE il.m_inoutline_id IN (" + placeholders + ")";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < lineIds.size(); i++) {
        ps.setString(i + 1, lineIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), new LineData(rs.getBigDecimal(2), rs.getString(3), rs.getBigDecimal(4)));
        }
      }
    } catch (Exception e) {
      log.error("DB error fetching line data for inout lines", e);
    }
    return result;
  }
}
