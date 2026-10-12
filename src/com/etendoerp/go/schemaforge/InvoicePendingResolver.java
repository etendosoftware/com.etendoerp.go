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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * {@link PendingResolver} for an invoice → goods movement follow-up (ETP-5576): which quantities of
 * a completed standard invoice have not been shipped (sales) / received (purchase) yet. The
 * movement itself is built by {@link InOutFollowUpCreator} with {@link InvoiceInOutMapping}.
 *
 * <p><b>Binding.</b> One implementation for both directions, parametrised by the movement
 * {@link InOutTargetBuilder.Direction}. Each invoice header handler registers it in its own
 * {@code followUpFlows()} with its own "is this a standard invoice (FAC)?" classifier — the same
 * {@code classifyDocType} that produces {@code arInvoiceSubtype}/{@code apInvoiceSubtype}.
 * Nothing here compares a spec name.
 *
 * <p><b>Verdict</b> ({@link #loadSources}), first match wins: wrong {@code IsSOTrx} →
 * {@code WRONG_DIRECTION}; not {@code CO} → {@code NOT_COMPLETED}; not FAC →
 * {@code NOT_ELIGIBLE_TYPE}; no line with pending &gt; 0 → {@code NOTHING_PENDING}; otherwise
 * available with those lines. <b>Draft rule:</b> an existing DRAFT movement counts as already
 * moved (it is subtracted), so a second request reads {@code NOTHING_PENDING} rather than
 * {@code DRAFT_IN_PROGRESS}, and a partial draft still leaves the rest available.
 *
 * <p><b>Pending, per invoice line</b> (one SQL statement for a whole page of invoices, see
 * {@link #loadSources}):
 * <pre>
 *   moved        = SUM(MovementQty) over the DISTINCT movement lines linked to the invoice line
 *                  through C_InvoiceLine.M_InOutLine_ID OR the direction's match table,
 *                  excluding voided movements and return document types; DRAFT movements COUNT
 *   orderPending = QtyOrdered - QtyDelivered - SUM(MovementQty of DRAFT, non-return movement
 *                  lines on the same order line)                  (only when C_OrderLine_ID is set)
 *   pending      = max(0, min(QtyInvoiced - moved, orderPending - what the preceding lines of
 *                  the same invoice on the same order line already take))   -- shared order cap
 * </pre>
 * Each movement line is counted ONCE per invoice line, whether it is reached through the column,
 * the match table, or both — that is what keeps an invoice whose lines are matched twice to the
 * same movement line (seen in real data) from reading as double-moved. Using the movement line's
 * own quantity, rather than the match row's, follows Classic's "create lines from invoice"
 * ({@code CreateFrom_Shipment_data.xsql}, {@code selectInvoiceLineFromOrderLine}) and is the
 * conservative choice: a match row written at draft time freezes a quantity the user may still
 * change, whereas {@code MovementQty} is always current. It can only under-state pending (a
 * movement line split across several invoices counts in full for each), never over-state it.
 *
 * <p><b>Candidate lines.</b> Every active invoice line with a product, a UOM and
 * {@code QtyInvoiced > 0} — services included (ProductType 'S'); whether a line needs a storage
 * bin is not decided here but by {@link InvoiceInOutMapping}, with
 * {@link InOutLineFromOrderFactory#isStockable}. The Total
 * Discount line ({@link TotalDiscountService#DISCOUNT_PRODUCT_ID}) is excluded by id, the same
 * rule as {@link InOutLineFromOrderFactory#pendingQuantityFor}. Negative lines are not carried:
 * the target is a delivery/receipt, not a return.
 */
final class InvoicePendingResolver implements PendingResolver {

  private static final Logger log = LogManager.getLogger(InvoicePendingResolver.class);

  private static final String DOC_STATUS_COMPLETED = "CO";

  private final InOutTargetBuilder.Direction direction;
  /** The invoice ↔ movement match table of this direction, read by the pending SQL. */
  private final InOutInvoiceLinks.MatchTable matchTable;
  private final Predicate<String> standardDocTypeById;

  /**
   * @param direction the movement direction ({@code SALES} for a sales invoice)
   * @param standardDocTypeById {@code true} when the invoice document type (C_DocTypeTarget_ID)
   *     is a standard invoice (FAC) — never a credit note, return or rectificative. Must fail
   *     closed (see {@code AbstractInvoiceHeaderHandler#isStandardInvoiceDocType}).
   */
  InvoicePendingResolver(InOutTargetBuilder.Direction direction,
      Predicate<String> standardDocTypeById) {
    this.direction = direction;
    this.matchTable = InOutInvoiceLinks.MatchTable.forSalesTransaction(
        direction.isSalesTransaction());
    this.standardDocTypeById = standardDocTypeById;
  }

  @Override
  public Class<Invoice> sourceEntity() {
    return Invoice.class;
  }

  @Override
  public Map<String, Source> loadSources(Collection<String> sourceIds) {
    Map<String, Source> result = new LinkedHashMap<>();
    if (sourceIds == null || sourceIds.isEmpty()) {
      return result;
    }
    List<String> ids = new ArrayList<>(sourceIds);
    Map<String, SourceFacts> facts = new LinkedHashMap<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection()
        .prepareStatement(pendingSql(matchTable, ids.size()))) {
      ps.setString(1, TotalDiscountService.DISCOUNT_PRODUCT_ID);
      int idx = 2;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          readRow(rs, facts);
        }
      }
    } catch (SQLException e) {
      throw new OBException("Could not compute pending quantities: " + e.getMessage(), e);
    }
    for (SourceFacts f : facts.values()) {
      result.put(f.id, Source.fromLines(f.id, ineligibility(f), f.lines));
    }
    return result;
  }

  private static void readRow(ResultSet rs, Map<String, SourceFacts> facts) throws SQLException {
    String invoiceId = rs.getString("c_invoice_id");
    String isSoTrx = rs.getString("issotrx");
    String docStatus = rs.getString("docstatus");
    String docTypeId = rs.getString("c_doctypetarget_id");
    SourceFacts f = facts.computeIfAbsent(invoiceId,
        id -> new SourceFacts(id, isSoTrx, docStatus, docTypeId));
    String lineId = rs.getString("c_invoiceline_id");
    if (lineId == null) {
      return; // invoice without any candidate line (LEFT JOIN)
    }
    // own_pending is already max(0, QtyInvoiced - moved): only the shared upstream cap remains.
    BigDecimal pending = FollowUpDocumentService.pendingQuantity(rs.getBigDecimal("own_pending"),
        BigDecimal.ZERO, rs.getBigDecimal("upstream_remaining"));
    if (pending.signum() <= 0) {
      return;
    }
    f.lines.add(new SourceLine(lineId, pending));
  }

  /**
   * Eligibility rules, first failing rule wins: same direction as the endpoint, Completed, and a
   * standard (FAC) document type. Package-private for tests.
   */
  FollowUpException.Reason ineligibility(String isSoTrx, String docStatus, String docTypeId) {
    if (!(direction.isSalesTransaction() ? "Y" : "N").equals(isSoTrx)) {
      return FollowUpException.Reason.WRONG_DIRECTION;
    }
    if (!DOC_STATUS_COMPLETED.equals(docStatus)) {
      return FollowUpException.Reason.NOT_COMPLETED;
    }
    if (!standardDocTypeById.test(docTypeId)) {
      return FollowUpException.Reason.NOT_ELIGIBLE_TYPE;
    }
    return null;
  }

  private FollowUpException.Reason ineligibility(SourceFacts f) {
    return ineligibility(f.isSoTrx, f.docStatus, f.docTypeId);
  }

  /**
   * One statement for N invoices: one row per candidate line (or a single all-null-line row for
   * an invoice without any), with the invoice's eligibility facts repeated on each row.
   *
   * <p>Columns read back: {@code own_pending} = {@code max(0, QtyInvoiced - moved)} and
   * {@code upstream_remaining} = what is left of the ORDER line's pending quantity after the
   * preceding lines of the SAME invoice on the SAME order line have taken theirs (ordered by
   * {@code Line}, then id). Two invoice lines on one order line therefore share the order's
   * remaining quantity instead of each being capped by all of it (ETP-5576 review W1: order
   * line of 10 with 4 delivered and invoice lines 5 + 5 → 5 + 1, not 5 + 5). The final pending is
   * {@code max(0, min(own_pending, upstream_remaining))}.
   *
   * <p>Placeholders: {@code 1} = the Total Discount product id, {@code 2..N+1} = the invoice ids.
   * Candidate-line filtering (product, UOM, positive quantity, discount line) is done in the
   * {@code LEFT JOIN} condition so an invoice with no candidate line still yields its facts row.
   * PostgreSQL-only ({@code LATERAL}, window functions), as is the rest of this module.
   */
  // matchTable is a fixed enum literal and the id placeholders are "?" only — no injection risk.
  @SuppressWarnings("java:S2077")
  static String pendingSql(InOutInvoiceLinks.MatchTable matchTable, int idCount) {
    String placeholders = String.join(",", Collections.nCopies(idCount, "?"));
    String base = "SELECT i.c_invoice_id, i.issotrx, i.docstatus, i.c_doctypetarget_id, "
        + "il.c_invoiceline_id, il.line, il.c_orderline_id, "
        + "GREATEST(0, COALESCE(il.qtyinvoiced, 0) - COALESCE(mv.moved, 0)) AS own_pending, "
        + "CASE WHEN ol.c_orderline_id IS NULL THEN NULL "
        + "     ELSE ol.qtyordered - COALESCE(ol.qtydelivered, 0) - COALESCE(dr.draftqty, 0) "
        + "END AS order_pending "
        + "FROM c_invoice i "
        + "LEFT JOIN c_invoiceline il ON il.c_invoice_id = i.c_invoice_id AND il.isactive = 'Y' "
        + "  AND il.m_product_id IS NOT NULL AND il.c_uom_id IS NOT NULL AND il.qtyinvoiced > 0 "
        + "  AND il.m_product_id <> ? "
        + "LEFT JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id "
        + "LEFT JOIN LATERAL ("
        + "  SELECT SUM(iol.movementqty) AS moved "
        + "  FROM m_inoutline iol "
        + "  JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
        + "  JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id "
        + "  WHERE iol.m_inoutline_id IN ("
        + "      SELECT mt.m_inoutline_id FROM " + matchTable.tableName() + " mt "
        + "      WHERE mt.c_invoiceline_id = il.c_invoiceline_id "
        + "      UNION SELECT il.m_inoutline_id WHERE il.m_inoutline_id IS NOT NULL) "
        + "    AND iol.isactive = 'Y' AND io.isactive = 'Y' AND io.docstatus <> 'VO' "
        + "    AND dt.isreturn = 'N'"
        + ") mv ON TRUE "
        + "LEFT JOIN LATERAL ("
        + "  SELECT SUM(diol.movementqty) AS draftqty "
        + "  FROM m_inoutline diol "
        + "  JOIN m_inout dio ON dio.m_inout_id = diol.m_inout_id "
        + "  JOIN c_doctype ddt ON ddt.c_doctype_id = dio.c_doctype_id "
        + "  WHERE diol.c_orderline_id = il.c_orderline_id "
        + "    AND diol.isactive = 'Y' AND dio.isactive = 'Y' AND dio.docstatus = 'DR' "
        + "    AND ddt.isreturn = 'N'"
        + ") dr ON TRUE "
        + "WHERE i.c_invoice_id IN (" + placeholders + ")";
    return "SELECT b.*, "
        + "CASE WHEN b.c_orderline_id IS NULL THEN NULL "
        + "     ELSE b.order_pending - COALESCE(SUM(b.own_pending) OVER ("
        + "       PARTITION BY b.c_invoice_id, b.c_orderline_id "
        + "       ORDER BY b.line, b.c_invoiceline_id "
        + "       ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) "
        + "END AS upstream_remaining "
        + "FROM (" + base + ") b "
        + "ORDER BY b.c_invoice_id, b.line, b.c_invoiceline_id";
  }

  /**
   * Locks the invoice row, then every order line its lines come from, until the transaction
   * ends. The order lines matter because the pending of an invoice line is capped by its order
   * line's remaining quantity (see {@link #pendingSql}): two invoices of the SAME order line
   * creating their movements at once would otherwise each read the full remainder (ETP-5576
   * review W6). Order lines are locked in id order, so two transactions that need overlapping
   * sets always acquire them in the same sequence and cannot deadlock on them.
   */
  @Override
  public void lockSource(String sourceId) {
    lockRows("SELECT c_invoice_id FROM c_invoice WHERE c_invoice_id = ? FOR UPDATE", sourceId);
    lockRows("SELECT ol.c_orderline_id FROM c_orderline ol "
        + "WHERE ol.c_orderline_id IN (SELECT il.c_orderline_id FROM c_invoiceline il "
        + "  WHERE il.c_invoice_id = ? AND il.c_orderline_id IS NOT NULL) "
        + "ORDER BY ol.c_orderline_id FOR UPDATE", sourceId);
  }

  private static void lockRows(String sql, String sourceId) {
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, sourceId);
      try (ResultSet rs = ps.executeQuery()) {
        // The locks are the side effect; draining the cursor acquires all of them. An absent
        // invoice is reported by loadSources as NOT_FOUND.
        int locked = 0;
        while (rs.next()) {
          locked++;
        }
        log.debug("Locked {} row(s) for follow-up of invoice {}", locked, sourceId);
      }
    } catch (SQLException e) {
      throw new OBException("Could not lock invoice " + sourceId + ": " + e.getMessage(), e);
    }
  }

  /** Per-invoice facts gathered while reading the rows of {@link #pendingSql}. */
  private static final class SourceFacts {
    private final String id;
    private final String isSoTrx;
    private final String docStatus;
    private final String docTypeId;
    private final List<SourceLine> lines = new ArrayList<>();

    private SourceFacts(String id, String isSoTrx, String docStatus, String docTypeId) {
      this.id = id;
      this.isSoTrx = isSoTrx;
      this.docStatus = docStatus;
      this.docTypeId = docTypeId;
    }
  }
}
