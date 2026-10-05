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
