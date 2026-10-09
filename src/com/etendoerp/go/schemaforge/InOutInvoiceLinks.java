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
import java.sql.SQLException;
import java.util.function.Function;

/**
 * The one definition of "which shipment/receipt lines are linked to which invoice lines", shared
 * by every related-documents query and by the follow-up document service (ETP-5576).
 *
 * <p>An invoice line and a goods movement line ({@code M_InOutLine}) are linked by the core in
 * two places, and only the union of both is the complete picture:
 * <ul>
 *   <li>{@code C_InvoiceLine.M_InOutLine_ID} — a single, "first" movement line;</li>
 *   <li>the core match table — {@code M_MatchSI} on the sales side, {@code M_MatchInv} on the
 *       purchase side — a many-to-many table that holds every further partial movement.</li>
 * </ul>
 * A query that reads only the column misses the second and later partial shipments of an invoice
 * line, and every movement matched to an invoice it was not created from.
 *
 * <p><b>Structure, not identity.</b> Which match table applies is decided by {@code IsSOTrx},
 * a generic flag every commercial document carries — see {@link MatchTable#forSalesTransaction}.
 * Nothing here names a window, spec or entity.
 */
final class InOutInvoiceLinks {

  private InOutInvoiceLinks() {
  }

  /**
   * The core's invoice ↔ goods-movement match table for one transaction direction. Both tables
   * have the same shape ({@code M_InOutLine_ID}, {@code C_InvoiceLine_ID}, {@code Qty}, …); only
   * their name and primary-key column differ.
   */
  enum MatchTable {
    /** Sales side ({@code IsSOTrx = 'Y'}): {@code M_MatchSI}. */
    SALES("m_matchsi", "m_matchsi_id"),
    /** Purchase side ({@code IsSOTrx = 'N'}): {@code M_MatchInv}. */
    PURCHASE("m_matchinv", "m_matchinv_id");

    private final String tableName;
    private final String idColumn;

    MatchTable(String tableName, String idColumn) {
      this.tableName = tableName;
      this.idColumn = idColumn;
    }

    /** Lower-case physical table name. A fixed literal — never derived from request input. */
    String tableName() {
      return tableName;
    }

    /** Lower-case primary-key column of {@link #tableName()}. */
    String idColumn() {
      return idColumn;
    }

    /** Selects the match table from the document's {@code IsSOTrx} flag. */
    static MatchTable forSalesTransaction(boolean salesTransaction) {
      return salesTransaction ? SALES : PURCHASE;
    }
  }

  /**
   * Invoice filter shared by every "how much of this movement line is invoiced" reader: active
   * invoices that are not voided, closed or draft. {@code invoiceAlias} is a fixed SQL alias.
   */
  private static String countedInvoiceFilter(String invoiceAlias) {
    return invoiceAlias + ".docstatus NOT IN ('VO','CL','DR') AND " + invoiceAlias
        + ".isactive = 'Y'";
  }

  /**
   * Sub-select yielding {@code (m_inoutline_id, qtymatched)}: per movement line, the total
   * {@code |qty|} of the rows of {@code matchTable} that link it to a counted invoice (active, not
   * voided, closed or draft). For a reader whose movement direction is fixed — e.g. the Goods
   * Receipt header, always {@link MatchTable#PURCHASE}. Meant to be {@code LEFT JOIN}ed on
   * {@code m_inoutline_id}.
   */
  static String matchedQtyPerInOutLineSql(MatchTable matchTable) {
    return "SELECT mt.m_inoutline_id, SUM(ABS(mt.qty)) AS qtymatched "
        + "FROM " + matchTable.tableName() + " mt "
        + "JOIN c_invoiceline mil ON mil.c_invoiceline_id = mt.c_invoiceline_id "
        + "JOIN c_invoice mi ON mi.c_invoice_id = mil.c_invoice_id "
        + "WHERE " + countedInvoiceFilter("mi") + " "
        + "GROUP BY mt.m_inoutline_id";
  }

  /**
   * Scalar SQL expression choosing a per-direction sub-expression by the movement's own
   * {@code IsSOTrx} — for readers shared by Goods Shipment and Goods Receipt. Selection is by
   * structure ({@link MatchTable#forSalesTransaction}), never by a window or entity name.
   * PostgreSQL evaluates only the {@code CASE} branch that applies, so one match table is read
   * per row.
   *
   * @param isSoTrxRef SQL reference to the movement's {@code m_inout.issotrx} (a fixed alias)
   * @param exprForTable builds the scalar sub-expression for one match table
   */
  private static String byMovementDirection(String isSoTrxRef,
      Function<MatchTable, String> exprForTable) {
    return "(CASE WHEN " + isSoTrxRef + " = 'Y' "
        + "THEN (" + exprForTable.apply(MatchTable.forSalesTransaction(true)) + ") "
        + "ELSE (" + exprForTable.apply(MatchTable.forSalesTransaction(false)) + ") END)";
  }

  /**
   * Scalar SQL expression: total {@code |qty|} matched to the movement line {@code inOutLineRef}
   * in the match table of the movement's direction ({@code isSoTrxRef}), counting only active
   * invoices that are not voided, closed or draft. {@code NULL} when no row qualifies — wrap it in
   * {@code COALESCE}. Both arguments are fixed SQL column references, never request input.
   *
   * <p>Readers combine it with the {@code C_InvoiceLine.M_InOutLine_ID} arm through
   * {@code GREATEST}, never by adding: the first movement of an invoice line is linked by the
   * column AND, once completed, by the match row {@code M_INOUT_POST} writes for it, so a sum
   * would count it twice.
   */
  static String matchedQtyByMovementDirectionExpr(String inOutLineRef, String isSoTrxRef) {
    return byMovementDirection(isSoTrxRef, mt ->
        "SELECT SUM(ABS(mt.qty)) FROM " + mt.tableName() + " mt "
        + "JOIN c_invoiceline mil ON mil.c_invoiceline_id = mt.c_invoiceline_id "
        + "JOIN c_invoice mi ON mi.c_invoice_id = mil.c_invoice_id "
        + "WHERE mt.m_inoutline_id = " + inOutLineRef + " AND " + countedInvoiceFilter("mi"));
  }

  /**
   * Scalar SQL expression: the {@code QtyInvoiced} of the invoice line that the match table of the
   * movement's direction links to {@code inOutLineRef} — the source invoice line of a second or
   * later partial movement, which {@code C_InvoiceLine.M_InOutLine_ID} cannot point at. Mirrors
   * the column-based source lookup (no invoice-status filter); {@code MAX} keeps it single-valued
   * so it never multiplies the reader's rows.
   */
  static String matchedSourceInvoiceQtyByMovementDirectionExpr(String inOutLineRef,
      String isSoTrxRef) {
    return byMovementDirection(isSoTrxRef, mt ->
        "SELECT MAX(mil.qtyinvoiced) FROM " + mt.tableName() + " mt "
        + "JOIN c_invoiceline mil ON mil.c_invoiceline_id = mt.c_invoiceline_id "
        + "WHERE mt.m_inoutline_id = " + inOutLineRef);
  }

  /** Number of {@code ?} placeholders in {@link #linkedInOutLineIdsSql}, all bound to the invoice id. */
  static final int LINKED_INOUT_LINES_PARAMS = 3;

  /** Number of {@code ?} placeholders in {@link #linkedInvoiceIdsSql}, all bound to the movement id. */
  static final int LINKED_INVOICES_PARAMS = 3;

  /**
   * Sub-select yielding one column, {@code m_inoutline_id}: every movement line linked to the
   * invoice bound to its {@value #LINKED_INOUT_LINES_PARAMS} placeholders (bind the same invoice
   * id to all of them, see {@link #bindRepeated}). Three arms, deduplicated by {@code UNION}:
   * <ol>
   *   <li>{@code C_InvoiceLine.M_InOutLine_ID};</li>
   *   <li>the match table;</li>
   *   <li>the pre-existing order-line fallback — an invoice line with no direct movement link
   *       reaches every movement line of the same order line. Kept exactly as the
   *       related-documents queries had it before ETP-5576.</li>
   * </ol>
   * Only active invoice lines participate, as before.
   */
  static String linkedInOutLineIdsSql(MatchTable matchTable) {
    return "SELECT il.m_inoutline_id FROM c_invoiceline il "
        + "WHERE il.c_invoice_id = ? AND il.isactive = 'Y' AND il.m_inoutline_id IS NOT NULL "
        + "UNION "
        + "SELECT mt.m_inoutline_id FROM " + matchTable.tableName() + " mt "
        + "JOIN c_invoiceline il ON il.c_invoiceline_id = mt.c_invoiceline_id "
        + "WHERE il.c_invoice_id = ? AND il.isactive = 'Y' "
        + "UNION "
        + "SELECT iol.m_inoutline_id FROM c_invoiceline il "
        + "JOIN m_inoutline iol ON iol.c_orderline_id = il.c_orderline_id "
        + "WHERE il.c_invoice_id = ? AND il.isactive = 'Y' "
        + "AND il.m_inoutline_id IS NULL AND il.c_orderline_id IS NOT NULL";
  }

  /**
   * Sub-select yielding one column, {@code c_invoice_id}: every invoice linked to the goods
   * movement bound to its {@value #LINKED_INVOICES_PARAMS} placeholders. Mirror image of
   * {@link #linkedInOutLineIdsSql}: direct column, match table, and the pre-existing order-line
   * arm (any invoice line on the same order line as one of the movement's lines). Only active
   * movement lines participate, as before.
   */
  static String linkedInvoiceIdsSql(MatchTable matchTable) {
    return "SELECT il.c_invoice_id FROM m_inoutline sil "
        + "JOIN c_invoiceline il ON il.m_inoutline_id = sil.m_inoutline_id "
        + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y' "
        + "UNION "
        + "SELECT il.c_invoice_id FROM m_inoutline sil "
        + "JOIN " + matchTable.tableName() + " mt ON mt.m_inoutline_id = sil.m_inoutline_id "
        + "JOIN c_invoiceline il ON il.c_invoiceline_id = mt.c_invoiceline_id "
        + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y' "
        + "UNION "
        + "SELECT il.c_invoice_id FROM m_inoutline sil "
        + "JOIN c_invoiceline il ON il.c_orderline_id = sil.c_orderline_id "
        + "WHERE sil.m_inout_id = ? AND sil.isactive = 'Y' AND sil.c_orderline_id IS NOT NULL";
  }

  /**
   * Binds {@code value} to {@code times} consecutive placeholders starting at {@code startIndex}.
   *
   * @return the next free placeholder index
   */
  static int bindRepeated(PreparedStatement ps, int startIndex, String value, int times)
      throws SQLException {
    int idx = startIndex;
    for (int i = 0; i < times; i++) {
      ps.setString(idx++, value);
    }
    return idx;
  }
}
