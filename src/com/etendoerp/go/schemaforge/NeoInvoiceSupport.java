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
   * Restricts a derived table to the order lines referenced by the invoice under validation.
   * Consumes one {@code ?} parameter (the C_Invoice_ID).
   */
  private static final String ORDER_LINE_SCOPE =
      " IN (SELECT ils.c_orderline_id FROM c_invoiceline ils "
      + "WHERE ils.c_invoice_id = ? AND ils.isactive = 'Y' "
      + "  AND ils.c_orderline_id IS NOT NULL)";

  /**
   * Returns the pending (not yet invoiced) quantity aggregated at ORDER-LINE level, for the order
   * lines referenced by the given invoice that were received/shipped across <b>more than one</b>
   * inout line (ETP-5334 — split reception / partial shipments).
   *
   * <p>Why this exists: {@code InvoiceLineLinker} can only pin an invoice line to a SINGLE
   * {@code M_InOutLine_ID} (the column is a plain FK), so an invoice line created from the ORDER
   * carrying the full pending order quantity ends up linked to one arbitrary receipt line. The
   * per-inout-line guard then compares the full order quantity against that one receipt's
   * quantity and blocks completion forever. At order-line level the comparison is against
   * everything actually received for the order line, which is the quantity the invoice line is
   * genuinely entitled to.
   *
   * <p>Aggregation, mirroring {@link #computePendingQtyPerLine(String, boolean)}:
   * <ul>
   *   <li>{@code received} = SUM(ABS(movementqty)) over the order line's active inout lines whose
   *       shipment/receipt is neither draft nor voided — goods not yet received never become
   *       invoiceable quantity.</li>
   *   <li>{@code invoiced} = GREATEST(m_matchsi, invoice lines linked by {@code m_inoutline_id})
   *       + invoice lines of the same order line with a NULL {@code m_inoutline_id}. The GREATEST
   *       is the same anti-double-counting used per line (a matched line is also a linked line);
   *       the unlinked set is disjoint from the linked one, so it is ADDED rather than folded into
   *       the GREATEST — folding it in would under-count what is already invoiced and could let a
   *       real over-invoice through.</li>
   *   <li>Only non-draft, non-voided, non-closed invoices count as invoiced — identical to the
   *       per-line query, and it also means the draft being completed never counts against
   *       itself.</li>
   * </ul>
   *
   * <p>Unlike {@link #computePendingQtyPerLine(String, boolean)}, order lines whose pending
   * quantity is zero ARE returned (clamped at zero). Presence in the map is what tells the caller
   * "this order line was split, use the aggregate"; omitting the exhausted ones would silently
   * send a fully-invoiced order line back to the per-inout-line path.
   *
   * @param invoiceId the C_Invoice_ID of the draft invoice being completed
   * @return map of C_OrderLine_ID → aggregate pending quantity, containing ONLY order lines with
   *         two or more inout lines; never {@code null} (empty on error)
   */
  // SQL is a compile-time constant; every value is bound as a parameter.
  @SuppressWarnings("java:S2077")
  static Map<String, BigDecimal> computePendingQtyPerOrderLine(String invoiceId) {
    String sql =
        "SELECT ol.c_orderline_id, ol.received_qty, "
        + "  COALESCE(GREATEST("
        + "    COALESCE(msi_qty.qtymatched, 0), "
        + "    COALESCE(direct_qty.qtyinvoiced, 0) "
        + "  ), 0) + COALESCE(ol_qty.qtyinvoiced, 0) AS invoiced_qty "
        // Received across ALL the inout lines of the order line. HAVING COUNT(*) > 1 keeps the
        // single-receipt case on the untouched per-inout-line path (no behaviour change there).
        + "FROM ("
        + "  SELECT iol.c_orderline_id, SUM(ABS(iol.movementqty)) AS received_qty "
        + "  FROM m_inoutline iol "
        + "  JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
        + "  WHERE iol.isactive = 'Y' AND io.isactive = 'Y' "
        + "    AND io.docstatus NOT IN ('VO','DR') "
        + "    AND iol.c_orderline_id" + ORDER_LINE_SCOPE + " "
        + "  GROUP BY iol.c_orderline_id "
        + "  HAVING COUNT(*) > 1"
        + ") ol "
        + "LEFT JOIN ("
        + "  SELECT iol2.c_orderline_id, SUM(ABS(msi.qty)) AS qtymatched "
        + "  FROM m_matchsi msi "
        + "  JOIN m_inoutline iol2 ON iol2.m_inoutline_id = msi.m_inoutline_id "
        + "  JOIN c_invoiceline il ON il.c_invoiceline_id = msi.c_invoiceline_id "
        + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
        + "  WHERE i.docstatus NOT IN ('VO','CL','DR') AND i.isactive = 'Y' "
        + "    AND iol2.c_orderline_id" + ORDER_LINE_SCOPE + " "
        + "  GROUP BY iol2.c_orderline_id "
        + ") msi_qty ON msi_qty.c_orderline_id = ol.c_orderline_id "
        + "LEFT JOIN ("
        + "  SELECT iol3.c_orderline_id, SUM(ABS(il2.qtyinvoiced)) AS qtyinvoiced "
        + "  FROM c_invoiceline il2 "
        + "  JOIN c_invoice i2 ON i2.c_invoice_id = il2.c_invoice_id "
        + "  JOIN m_inoutline iol3 ON iol3.m_inoutline_id = il2.m_inoutline_id "
        + "  WHERE i2.docstatus NOT IN ('VO','CL','DR') AND i2.isactive = 'Y' "
        + "    AND iol3.c_orderline_id" + ORDER_LINE_SCOPE + " "
        + "  GROUP BY iol3.c_orderline_id "
        + ") direct_qty ON direct_qty.c_orderline_id = ol.c_orderline_id "
        // Invoiced straight off the order, never linked to any inout line — disjoint from
        // direct_qty (m_inoutline_id IS NULL), hence added, not folded into the GREATEST.
        + "LEFT JOIN ("
        + "  SELECT il3.c_orderline_id, SUM(ABS(il3.qtyinvoiced)) AS qtyinvoiced "
        + "  FROM c_invoiceline il3 "
        + "  JOIN c_invoice i3 ON i3.c_invoice_id = il3.c_invoice_id "
        + "  WHERE i3.docstatus NOT IN ('VO','CL','DR') AND i3.isactive = 'Y' "
        + "    AND il3.m_inoutline_id IS NULL "
        + "    AND il3.c_orderline_id" + ORDER_LINE_SCOPE + " "
        + "  GROUP BY il3.c_orderline_id "
        + ") ol_qty ON ol_qty.c_orderline_id = ol.c_orderline_id";

    Map<String, BigDecimal> result = new HashMap<>();
    try {
      // Same session connection as computePendingQtyPerLine: the scope subquery reads the
      // invoice's own lines, which may still be uncommitted in the current transaction.
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        for (int i = 1; i <= 4; i++) {  // four ORDER_LINE_SCOPE occurrences
          ps.setString(i, invoiceId);
        }
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String orderLineId = rs.getString(1);
            BigDecimal received = rs.getBigDecimal(2);
            BigDecimal invoiced = rs.getBigDecimal(3);
            BigDecimal pending = (received != null ? received : BigDecimal.ZERO)
                .subtract(invoiced != null ? invoiced : BigDecimal.ZERO)
                .max(BigDecimal.ZERO);
            result.put(orderLineId, pending);
          }
        }
      }
    } catch (Exception e) {
      log.error("DB error computing pending qty per order line for invoice {}", invoiceId, e);
    }
    return result;
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
    // includeDrafts=false: three paths — m_matchsi, direct m_inoutline_id, and ol_qty (invoice
    // created from the ORDER: m_inoutline_id IS NULL, joined via c_orderline_id scoped to this
    // shipment). Used for the billing-status badge and for blocking duplicate invoice creation.
    //
    // includeDrafts=true: same three paths plus draft_all (draft invoice lines joined via
    // c_orderline_id, scoped to this shipment). Prevents a second draft from being created even
    // when InvoiceLineLinker has not yet run for the existing draft.
    String sql;
    int paramCount;
    if (!includeDrafts) {
      sql =
          "SELECT sil.m_inoutline_id, "
          + "  ABS(sil.movementqty) AS movement_qty, "
          + "  COALESCE(GREATEST("
          + "    COALESCE(msi_qty.qtymatched, 0), "
          + "    COALESCE(direct_qty.qtyinvoiced, 0), "
          + "    COALESCE(ol_qty.qtyinvoiced, 0) "
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
          // Invoice created from the order (m_inoutline_id is NULL on the invoice line):
          // join via c_orderline_id scoped to this shipment to avoid counting other receipts.
          + "LEFT JOIN ("
          + "  SELECT iol2.m_inoutline_id, SUM(ABS(il3.qtyinvoiced)) AS qtyinvoiced "
          + "  FROM c_invoiceline il3 "
          + "  JOIN c_invoice i3 ON i3.c_invoice_id = il3.c_invoice_id "
          + "  JOIN m_inoutline iol2 ON iol2.c_orderline_id = il3.c_orderline_id "
          + "                       AND iol2.m_inout_id = ? "
          + "                       AND iol2.isactive = 'Y' "
          + "  WHERE i3.docstatus NOT IN ('VO','CL','DR') AND i3.isactive = 'Y' "
          + "    AND il3.c_orderline_id IS NOT NULL "
          + "    AND il3.m_inoutline_id IS NULL "
          + "  GROUP BY iol2.m_inoutline_id "
          + ") ol_qty ON ol_qty.m_inoutline_id = sil.m_inoutline_id "
          + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'";
      paramCount = 2;  // first ? = inOutId (ol_qty scope), second ? = inOutId (WHERE)
    } else {
      // draft_all: ANY draft invoice linked to the same order line as the receipt line,
      // regardless of whether m_inoutline_id is NULL (created from PO) or points to a
      // different receipt (InvoiceLineLinker already ran for a prior receipt).
      // GREATEST across all three paths — no additive double-counting.
      sql =
          "SELECT sil.m_inoutline_id, "
          + "  ABS(sil.movementqty) AS movement_qty, "
          + "  COALESCE(GREATEST("
          + "    COALESCE(msi_qty.qtymatched, 0), "
          + "    COALESCE(direct_qty.qtyinvoiced, 0), "
          + "    COALESCE(draft_all.qtydraft, 0) "
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
          + "    AND il3.c_orderline_id IS NOT NULL "
          + "  GROUP BY iol.m_inoutline_id "
          + ") draft_all ON draft_all.m_inoutline_id = sil.m_inoutline_id "
          + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'";
      paramCount = 2;  // first ? = inOutId (draft_all scope), second ? = inOutId (WHERE)
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
