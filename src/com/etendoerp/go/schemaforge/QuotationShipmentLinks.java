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

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * The goods movements of a sales QUOTATION (ETP-6002, IMP-3): every sales shipment / customer
 * return whose own header ({@code M_InOut.C_Order_ID}) or one of whose lines
 * ({@code M_InOutLine.C_OrderLine_ID}) points at the quotation. That happens when a shipment is
 * made from an invoice that came straight from the quotation: the invoice lines carry quotation
 * lines, and the shipment lines copy them.
 *
 * <p>Only those direct links count; nothing is guessed through invoices or a shared order line —
 * the same principle that dropped the order-line arm from the invoice ↔ shipment readers
 * ({@link InOutInvoiceLinks}). The mirror is the shipment's {@code linkedOrders}, which marks the
 * quotation with {@code isQuotation}.
 */
final class QuotationShipmentLinks {

  private static final Logger log = LogManager.getLogger(QuotationShipmentLinks.class);

  static final String FIELD_LINKED_SHIPMENTS = "linkedShipments";

  // Constant SQL; every value is bound — no injection risk.
  static final String SQL =
      "SELECT DISTINCT io.m_inout_id, io.documentno, io.docstatus, dt.isreturn "
          + "FROM m_inout io "
          + "JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id "
          + "WHERE io.isactive = 'Y' AND io.issotrx = 'Y' AND io.docstatus NOT IN ('VO','CL') "
          + "  AND io.m_inout_id IN ("
          + "    SELECT io2.m_inout_id FROM m_inout io2 WHERE io2.c_order_id = ? "
          + "    UNION "
          + "    SELECT iol.m_inout_id FROM m_inoutline iol "
          + "    JOIN c_orderline ol ON ol.c_orderline_id = iol.c_orderline_id "
          + "    WHERE ol.c_order_id = ? AND iol.isactive = 'Y') "
          + "ORDER BY io.documentno";

  private QuotationShipmentLinks() {
  }

  /**
   * Puts {@value #FIELD_LINKED_SHIPMENTS} — {@code [{id, documentNo, documentStatus, isReturn}]} —
   * on the quotation detail record. A read failure leaves the key out (logged): it costs a chip,
   * never the record.
   */
  @SuppressWarnings("java:S2077")
  static void enrich(JSONObject rec, String quotationId) {
    if (rec == null || StringUtils.isBlank(quotationId)) {
      return;
    }
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(SQL)) {
      ps.setString(1, quotationId);
      ps.setString(2, quotationId);
      JSONArray shipments = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          shipments.put(new JSONObject()
              .put("id", rs.getString(1))
              .put("documentNo", rs.getString(2))
              .put("documentStatus", rs.getString(3))
              .put("isReturn", "Y".equalsIgnoreCase(rs.getString(4))));
        }
      }
      rec.put(FIELD_LINKED_SHIPMENTS, shipments);
    } catch (Exception e) {
      log.warn("Could not enrich linked shipments for quotation {}: {}", quotationId, e.getMessage());
    }
  }
}
