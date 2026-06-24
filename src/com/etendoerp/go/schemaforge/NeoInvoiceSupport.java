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
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * Shared invoice-related utilities used by both sales and purchase invoice handlers.
 */
final class NeoInvoiceSupport {

  private static final Logger log = LogManager.getLogger(NeoInvoiceSupport.class);

  private NeoInvoiceSupport() {
  }

  /**
   * Returns the pending (not yet invoiced) quantity for every active line of the
   * given shipment or goods receipt. Uses GREATEST(M_MatchSI, direct C_InvoiceLine)
   * to avoid double-counting, and excludes voided and closed invoices.
   * Draft invoices are excluded by default (pass {@code true} to include them).
   *
   * @param inOutId        the M_InOut_ID of the shipment or receipt
   * @param includeDrafts  when {@code true} draft invoices count toward invoiced qty,
   *                       preventing duplicate drafts; when {@code false} (default)
   *                       drafts are excluded so the billing-status badge is unaffected
   * @return map of M_InOutLine_ID → pending quantity (lines with pending ≤ 0 are omitted)
   */
  /** Delegates to {@link #computePendingQtyPerLine(String, boolean)} with {@code includeDrafts=false}. */
  static Map<String, BigDecimal> computePendingQtyPerLine(String inOutId) {
    return computePendingQtyPerLine(inOutId, false);
  }

  // SQL literals derived from trusted booleans — no injection risk.
  @SuppressWarnings("java:S2077")
  static Map<String, BigDecimal> computePendingQtyPerLine(String inOutId, boolean includeDrafts) {
    // When includeDrafts=false: original two-path query (m_matchsi + direct m_inoutline_id link),
    // excluding draft invoices. Used for the billing-status badge.
    //
    // When includeDrafts=true: adds a third path (draft_unlinked) that detects draft invoice
    // lines where M_InOutLine_ID is not yet set, falling back to C_OrderLine_ID scoped to this
    // shipment. This prevents a second draft from being created for already-committed quantities
    // even when InvoiceLineLinker has not yet run for the existing draft.
    String sql;
    int paramCount;
    if (!includeDrafts) {
      sql =
          "SELECT sil.m_inoutline_id, "
          + "  ABS(sil.movementqty) AS movement_qty, "
          + "  COALESCE(GREATEST("
          + "    COALESCE(msi_qty.qtymatched, 0), "
          + "    COALESCE(direct_qty.qtyinvoiced, 0) "
          + "  ), 0) AS invoiced_qty "
          + "FROM m_inoutline sil "
          + "LEFT JOIN ("
          + "  SELECT msi.m_inoutline_id, SUM(ABS(msi.qty)) AS qtymatched "
          + "  FROM m_matchsi msi "
          + "  JOIN c_invoiceline il ON il.c_invoiceline_id = msi.c_invoiceline_id "
          + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
          + "  WHERE i.docstatus NOT IN ('VO','CL','DR') AND i.isactive = 'Y' "
          + "  GROUP BY msi.m_inoutline_id "
          + ") msi_qty ON msi_qty.m_inoutline_id = sil.m_inoutline_id "
          + "LEFT JOIN ("
          + "  SELECT il2.m_inoutline_id, SUM(ABS(il2.qtyinvoiced)) AS qtyinvoiced "
          + "  FROM c_invoiceline il2 "
          + "  JOIN c_invoice i2 ON i2.c_invoice_id = il2.c_invoice_id "
          + "  WHERE i2.docstatus NOT IN ('VO','CL','DR') AND i2.isactive = 'Y' "
          + "  GROUP BY il2.m_inoutline_id "
          + ") direct_qty ON direct_qty.m_inoutline_id = sil.m_inoutline_id "
          + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'";
      paramCount = 1;
    } else {
      // draft_unlinked: draft invoices whose lines have no M_InOutLine_ID yet — find
      // the matching shipment line via C_OrderLine_ID, scoped to this exact shipment.
      sql =
          "SELECT sil.m_inoutline_id, "
          + "  ABS(sil.movementqty) AS movement_qty, "
          + "  COALESCE(GREATEST("
          + "    COALESCE(msi_qty.qtymatched, 0), "
          + "    COALESCE(direct_qty.qtyinvoiced, 0) "
          + "  ), 0) + COALESCE(draft_unlinked.qtydraft, 0) AS invoiced_qty "
          + "FROM m_inoutline sil "
          + "LEFT JOIN ("
          + "  SELECT msi.m_inoutline_id, SUM(ABS(msi.qty)) AS qtymatched "
          + "  FROM m_matchsi msi "
          + "  JOIN c_invoiceline il ON il.c_invoiceline_id = msi.c_invoiceline_id "
          + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
          + "  WHERE i.docstatus NOT IN ('VO','CL','DR') AND i.isactive = 'Y' "
          + "  GROUP BY msi.m_inoutline_id "
          + ") msi_qty ON msi_qty.m_inoutline_id = sil.m_inoutline_id "
          + "LEFT JOIN ("
          + "  SELECT il2.m_inoutline_id, SUM(ABS(il2.qtyinvoiced)) AS qtyinvoiced "
          + "  FROM c_invoiceline il2 "
          + "  JOIN c_invoice i2 ON i2.c_invoice_id = il2.c_invoice_id "
          + "  WHERE i2.docstatus NOT IN ('VO','CL') AND i2.isactive = 'Y' "
          + "    AND il2.m_inoutline_id IS NOT NULL "
          + "  GROUP BY il2.m_inoutline_id "
          + ") direct_qty ON direct_qty.m_inoutline_id = sil.m_inoutline_id "
          + "LEFT JOIN ("
          + "  SELECT iol.m_inoutline_id, SUM(ABS(il3.qtyinvoiced)) AS qtydraft "
          + "  FROM c_invoiceline il3 "
          + "  JOIN c_invoice i3 ON i3.c_invoice_id = il3.c_invoice_id "
          + "  JOIN m_inoutline iol ON iol.c_orderline_id = il3.c_orderline_id "
          + "                      AND iol.m_inout_id = ? "
          + "  WHERE i3.docstatus = 'DR' AND i3.isactive = 'Y' "
          + "    AND il3.m_inoutline_id IS NULL "
          + "    AND il3.c_orderline_id IS NOT NULL "
          + "  GROUP BY iol.m_inoutline_id "
          + ") draft_unlinked ON draft_unlinked.m_inoutline_id = sil.m_inoutline_id "
          + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'";
      paramCount = 2;  // first ? = inOutId (draft_unlinked scope), second ? = inOutId (WHERE)
    }

    Map<String, BigDecimal> result = new HashMap<>();
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        for (int i = 1; i <= paramCount; i++) {
          ps.setString(i, inOutId);
        }
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String lineId = rs.getString(1);
            BigDecimal movQty = rs.getBigDecimal(2);
            BigDecimal invQty = rs.getBigDecimal(3);
            BigDecimal pending = (movQty != null ? movQty : BigDecimal.ZERO)
                .subtract(invQty != null ? invQty : BigDecimal.ZERO)
                .max(BigDecimal.ZERO);
            if (pending.compareTo(BigDecimal.ZERO) > 0) {
              result.put(lineId, pending);
            }
          }
        }
      }
    } catch (Exception e) {
      log.error("DB error computing pending qty per line for inout {}", inOutId, e);
    }
    return result;
  }
}
