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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

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
   *
   * <p>{@code inOutLineIdsSubquery} must be a {@code SELECT m_inoutline_id …} selecting the
   * movement lines to aggregate. It is applied to the match table's own {@code m_inoutline_id} —
   * the {@code GROUP BY} key — so it keeps or drops whole groups and never changes a sum, while
   * letting the planner read only the requested movement's lines instead of the whole match table
   * (ETP-5653).
   *
   * @param inOutLineIdsSubquery a {@code SELECT m_inoutline_id …} restricting the aggregation
   */
  static String matchedQtyPerInOutLineSql(MatchTable matchTable, String inOutLineIdsSubquery) {
    return "SELECT mt.m_inoutline_id, SUM(ABS(mt.qty)) AS qtymatched "
        + "FROM " + matchTable.tableName() + " mt "
        + "JOIN c_invoiceline mil ON mil.c_invoiceline_id = mt.c_invoiceline_id "
        + "JOIN c_invoice mi ON mi.c_invoice_id = mil.c_invoice_id "
        + "WHERE " + countedInvoiceFilter("mi")
        + " AND mt.m_inoutline_id IN (" + inOutLineIdsSubquery + ") "
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

  /**
   * ETP-5852: the {@code linkedInvoices} of a whole page of shipments or receipts in ONE query —
   * the same three arms as {@link #linkedInvoiceIdsSql} and the same invoice filter as the
   * single-record enrichment, keyed by movement. Movements with no invoice get an empty array.
   *
   * @param matchTable {@link MatchTable#SALES} for shipments, {@link MatchTable#PURCHASE} for
   *                   receipts
   * @param inOutIds   the page's {@code M_InOut} ids; empty answers an empty map
   * @return movement id → its invoices ({@code id, documentNo, grandTotalAmount,
   *         documentStatus, currency$_identifier}), in {@code inOutIds} order
   */
  // The SQL is built from a fixed enum literal and "?" placeholders; every value is bound.
  @SuppressWarnings("java:S2077")
  static Map<String, JSONArray> linkedInvoicesByInOut(MatchTable matchTable, List<String> inOutIds)
      throws SQLException, JSONException {
    Map<String, JSONArray> byInOut = new LinkedHashMap<>();
    if (inOutIds == null || inOutIds.isEmpty()) {
      return byInOut;
    }
    for (String id : inOutIds) {
      byInOut.put(id, new JSONArray());
    }
    String in = String.join(",", Collections.nCopies(inOutIds.size(), "?"));
    String sql = "SELECT DISTINCT lk.m_inout_id, i.c_invoice_id, i.documentno, i.grandtotal, "
        + "i.docstatus, cur.iso_code FROM ("
        + "SELECT sil.m_inout_id, il.c_invoice_id FROM m_inoutline sil "
        + "JOIN c_invoiceline il ON il.m_inoutline_id = sil.m_inoutline_id "
        + "WHERE sil.m_inout_id IN (" + in + ") AND sil.isactive = 'Y' "
        + "UNION "
        + "SELECT sil.m_inout_id, il.c_invoice_id FROM m_inoutline sil "
        + "JOIN " + matchTable.tableName() + " mt ON mt.m_inoutline_id = sil.m_inoutline_id "
        + "JOIN c_invoiceline il ON il.c_invoiceline_id = mt.c_invoiceline_id "
        + "WHERE sil.m_inout_id IN (" + in + ") AND sil.isactive = 'Y' "
        + "UNION "
        + "SELECT sil.m_inout_id, il.c_invoice_id FROM m_inoutline sil "
        + "JOIN c_invoiceline il ON il.c_orderline_id = sil.c_orderline_id "
        + "WHERE sil.m_inout_id IN (" + in + ") AND sil.isactive = 'Y' "
        + "AND sil.c_orderline_id IS NOT NULL) lk "
        + "JOIN c_invoice i ON i.c_invoice_id = lk.c_invoice_id "
        + "LEFT JOIN c_currency cur ON cur.c_currency_id = i.c_currency_id "
        + "WHERE i.isactive = 'Y' AND i.docstatus NOT IN ('VO', 'CL') "
        + "ORDER BY lk.m_inout_id, i.documentno";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      int idx = 1;
      for (int arm = 0; arm < LINKED_INVOICES_PARAMS; arm++) {
        for (String id : inOutIds) {
          ps.setString(idx++, id);
        }
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONArray invoices = byInOut.get(rs.getString(1));
          if (invoices == null) {
            continue;
          }
          JSONObject inv = new JSONObject();
          inv.put("id", rs.getString(2));
          inv.put("documentNo", rs.getString(3));
          BigDecimal total = rs.getBigDecimal(4);
          inv.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
          inv.put("documentStatus", rs.getString(5));
          inv.put("currency$_identifier", rs.getString(6));
          invoices.put(inv);
        }
      }
    }
    return byInOut;
  }

  /** Writes each row's {@code linkedInvoices} from {@link #linkedInvoicesByInOut}'s map. */
  static void putLinkedInvoices(JSONArray rows, Map<String, JSONArray> byInOut)
      throws JSONException {
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      JSONArray invoices = row == null ? null : byInOut.get(row.optString("id", ""));
      if (invoices != null) {
        row.put("linkedInvoices", invoices);
      }
    }
  }
}
