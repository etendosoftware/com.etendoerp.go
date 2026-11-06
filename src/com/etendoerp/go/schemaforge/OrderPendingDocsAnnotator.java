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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * The pending-document annotations of an order header GET, extracted unchanged from
 * {@link AbstractOrderHeaderHandler} (ETP-5828, which was past the method count Sonar accepts):
 * {@code needsPrimaryDoc} / {@code needsInvoiceDoc} on every record of the page (ETP-5295) and
 * {@code pendingInvoiceAmount} on a single completed record (ETP-5828). The handler calls it
 * explicitly from {@code afterHandle} and passes what is specific to it — the direction, whether
 * its invoice action follows the net-of-drafts rule, and its amount hook — so nothing here
 * selects an entity.
 */
final class OrderPendingDocsAnnotator {

  private static final Logger log = LogManager.getLogger(OrderPendingDocsAnnotator.class);
  private static final String FIELD_GRAND_TOTAL_AMOUNT = "grandTotalAmount";
  private static final String FIELD_ID = "id";
  /**
   * Deliberately neutral names (ETP-5295): the SAME two keys are annotated for sales and for
   * purchase, so the shared frontend hook that reads them ({@code useOrderWindow.jsx}) needs no
   * per-window parameterization. "Primary doc" is the goods shipment for a sales order and the
   * goods receipt for a purchase order — both rows of {@code M_InOut}.
   */
  private static final String FIELD_NEEDS_PRIMARY_DOC = "needsPrimaryDoc";
  private static final String FIELD_NEEDS_INVOICE_DOC = "needsInvoiceDoc";
  private static final String DOC_STATUS_DRAFT = "DR";
  private static final String DOC_STATUS_COMPLETED = "CO";

  private OrderPendingDocsAnnotator() {
  }

  /**
   * Annotates {@code needsPrimaryDoc} and {@code needsInvoiceDoc} on every record of a GET
   * response — list AND single-record (ETP-5295).
   *
   * <p><b>Why this exists.</b> The "Gestionar envío/factura" (sales) / "Gestionar
   * recepción/factura" (purchase) entry in the list-view row kebab menu used to derive its
   * visibility and label from the {@code DeliveryStatus}/{@code InvoiceStatus} percent columns,
   * while the "Gestionar" button on the detail form derives it from the real documents. The two
   * disagreed. Rather than make the kebab issue three extra HTTP requests per row, the real
   * derivation is computed here, server-side, once per page, so the frontend can replicate the
   * form's formula synchronously with ZERO extra requests.
   *
   * <p><b>The formula</b> (a literal transcription of the detail form's — see
   * {@code artifacts/sales-order/custom/OrderCreateInvoice.jsx} and
   * {@code artifacts/purchase-order/custom/PurchaseOrderActions.jsx} in {@code etendo_schema_forge}):
   * <pre>
   *   qtyPending      = SUM(C_OrderLine.QtyOrdered) - SUM(C_OrderLine.QtyDelivered)
   *                     (Total Discount line excluded — ETP-5525)
   *   needsPrimaryDoc = qtyPending != 0 AND no linked M_InOut in DocStatus 'DR'
   *
   *   totalPending    = order GrandTotal - SUM(GrandTotal of LINKED invoices in DocStatus 'CO')
   *   needsInvoiceDoc = totalPending != 0 AND no LINKED invoice in DocStatus 'DR'
   *
   *   with AbstractOrderHeaderHandler#invoicePendingNetOfDrafts() (the sales order, ETP-5828):
   *   needsInvoiceDoc = totalPending != 0 AND (no LINKED draft invoice
   *                     OR some line is still pending net of the draft invoices)
   * </pre>
   *
   * <p><b>Both flags are annotated unconditionally, for every document status.</b> The form only
   * evaluates them once the order is completed, and the kebab already gates its own entry on
   * {@code status === 'CO'}; keeping the backend status-agnostic means the annotation does not
   * have to re-read {@code documentStatus} and stays a pure function of the order's documents.
   *
   * <p><b>Degradation.</b> A DB failure annotates both flags {@code false} (menu entry hidden)
   * rather than leaving them absent or defaulting to {@code true}: a spuriously hidden shortcut
   * is recoverable from the detail form, a spuriously shown one sends the user into an empty
   * "manage" modal. The parent GET is never failed.
   */
  static void annotatePendingDocuments(JSONArray dataArr, boolean sales, boolean netOfDrafts)
      throws Exception {
    List<String> ids = collectPendingDocsCandidateIds(dataArr);
    if (ids.isEmpty()) {
      return;
    }
    Map<String, OrderQuantities> quantities;
    Set<String> withDraftPrimaryDoc;
    Map<String, LinkedInvoiceTotals> invoiceTotals;
    Set<String> pendingPastDrafts;
    try {
      // Sales ('Y') vs purchase ('N') comes from the SAME switch the price-list fallback uses,
      // so a subclass never has to know about this annotation to be classified correctly.
      String soTrx = sales ? "Y" : "N";
      quantities = batchFetchOrderedVsDelivered(ids);
      withDraftPrimaryDoc = batchFindOrdersWithDraftPrimaryDoc(ids, soTrx);
      invoiceTotals = batchFetchLinkedInvoiceTotals(ids, soTrx);
      pendingPastDrafts = netOfDrafts
          ? batchFindOrdersPendingPastDrafts(ordersWithDraftInvoice(invoiceTotals), soTrx)
          : null;
    } catch (Exception e) {
      log.error("DB error computing needsPrimaryDoc/needsInvoiceDoc for {} order(s)", ids.size(), e);
      denyPendingDocumentFlags(dataArr);
      return;
    }
    for (int i = 0; i < dataArr.length(); i++) {
      annotatePendingDocumentsOnRecord(
          dataArr.getJSONObject(i), quantities, withDraftPrimaryDoc, invoiceTotals,
          pendingPastDrafts);
    }
  }

  /** The orders of the page that have a linked DRAFT invoice. */
  private static List<String> ordersWithDraftInvoice(Map<String, LinkedInvoiceTotals> totals) {
    List<String> withDraft = new ArrayList<>();
    for (Map.Entry<String, LinkedInvoiceTotals> entry : totals.entrySet()) {
      if (entry.getValue().hasDraft()) {
        withDraft.add(entry.getKey());
      }
    }
    return withDraft;
  }

  /**
   * One query, the orders with a draft invoice only (ETP-5828): which of them still have a line
   * with something to invoice NET of the draft invoices — the {@link OrderInvoicePending} rule in
   * SQL, line by line: an active line with a product other than the total-discount line, whose
   * ordered minus invoiced is non-zero with the ordered sign, and still so once the quantity the
   * active lines of DRAFT invoices of the direction hold against it is taken away.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private static Set<String> batchFindOrdersPendingPastDrafts(List<String> ids, String soTrx)
      throws SQLException {
    Set<String> result = new HashSet<>();
    if (ids.isEmpty()) {
      return result;
    }
    String sql =
        "SELECT DISTINCT p.order_id FROM ( " +
        "SELECT ol.C_Order_ID AS order_id, ol.QtyOrdered AS ordered, " +
        "       ol.QtyOrdered - ol.QtyInvoiced AS base, " +
        "       ol.QtyOrdered - ol.QtyInvoiced - COALESCE((SELECT SUM(il.QtyInvoiced) " +
        "         FROM C_InvoiceLine il JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "        WHERE il.C_OrderLine_ID = ol.C_OrderLine_ID AND il.IsActive = 'Y' " +
        "          AND i.DocStatus = ? AND i.IsSOTrx = ?), 0) AS net " +
        "  FROM C_OrderLine ol " +
        " WHERE ol.C_Order_ID IN (" + placeholders(ids) + ") AND ol.IsActive = 'Y' " +
        "   AND ol.M_Product_ID IS NOT NULL AND ol.M_Product_ID <> ? " +
        ") p WHERE p.base <> 0 AND SIGN(p.base) = SIGN(p.ordered) AND SIGN(p.net) = SIGN(p.base)";
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      ps.setString(idx++, DOC_STATUS_DRAFT);
      ps.setString(idx++, soTrx);
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx, TotalDiscountService.DISCOUNT_PRODUCT_ID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  /**
   * The non-empty {@code id} of every record of the page, in page order — the exact id set the
   * three batch queries are built from. A record without a usable id contributes nothing, so a
   * page where NO record has one yields an empty list and no query is issued at all.
   */
  private static List<String> collectPendingDocsCandidateIds(JSONArray dataArr) throws JSONException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString(FIELD_ID, null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * Annotates both flags {@code false} on every record of the page — the degraded answer used
   * when the batch queries could not be run. See {@link #annotatePendingDocuments} for why the
   * flags are set to {@code false} rather than left absent.
   */
  private static void denyPendingDocumentFlags(JSONArray dataArr) throws JSONException {
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      rec.put(FIELD_NEEDS_PRIMARY_DOC, false);
      rec.put(FIELD_NEEDS_INVOICE_DOC, false);
    }
  }

  /**
   * Applies both flags to ONE record, reading only the already-fetched batch results — no query
   * of its own. A record without a usable id is in none of them and gets the same
   * {@code false}/{@code false} degraded answer a DB failure would produce.
   */
  private static void annotatePendingDocumentsOnRecord(JSONObject rec,
      Map<String, OrderQuantities> quantities, Set<String> withDraftPrimaryDoc,
      Map<String, LinkedInvoiceTotals> invoiceTotals, Set<String> pendingPastDrafts)
      throws JSONException {
    String id = rec.optString(FIELD_ID, null);
    if (id == null || id.isEmpty()) {
      rec.put(FIELD_NEEDS_PRIMARY_DOC, false);
      rec.put(FIELD_NEEDS_INVOICE_DOC, false);
      return;
    }
    OrderQuantities qty = quantities.get(id);
    BigDecimal ordered = qty != null ? qty.ordered() : BigDecimal.ZERO;
    BigDecimal delivered = qty != null ? qty.delivered() : BigDecimal.ZERO;
    rec.put(FIELD_NEEDS_PRIMARY_DOC,
        ordered.compareTo(delivered) != 0 && !withDraftPrimaryDoc.contains(id));

    LinkedInvoiceTotals totals = invoiceTotals.get(id);
    BigDecimal invoiced = totals != null ? totals.completedTotal() : BigDecimal.ZERO;
    boolean hasDraftInvoice = totals != null && totals.hasDraft();
    // The order total is read from the JSON record, not re-queried, so it is the SAME number
    // the form sees: OrderDraftTotalDiscount.applyToRecord() has already run over this array and may have
    // adjusted grandTotalAmount for a draft carrying a not-yet-materialized total discount.
    BigDecimal totalOrder = BigDecimal.valueOf(rec.optDouble(FIELD_GRAND_TOTAL_AMOUNT, 0.0));
    // ETP-5828: under the net rule (pendingPastDrafts non-null) a draft only blocks when, net of
    // what the drafts hold, no line is left to invoice; without a draft nothing changes.
    boolean draftBlocks = hasDraftInvoice
        && (pendingPastDrafts == null || !pendingPastDrafts.contains(id));
    rec.put(FIELD_NEEDS_INVOICE_DOC, totalOrder.compareTo(invoiced) != 0 && !draftBlocks);
  }

  /**
   * One query, all ids: ordered vs delivered quantity per order.
   *
   * <p>An order with no active lines is simply absent from the result and is then treated as
   * {@code 0 - 0 = 0} pending — the same answer the form reaches from an empty {@code orderLines}
   * array.
   *
   * <p><b>The Total Discount line is excluded (ETP-5525).</b> The dummy line
   * {@link TotalDiscountService} creates (product {@link TotalDiscountService#DISCOUNT_PRODUCT_ID},
   * ordered 1) can never be shipped or received, so counting it left {@code qtyPending} at 1 forever
   * and {@code needsPrimaryDoc} stuck {@code true} on a fully delivered order. It is excluded by the
   * same product-id criterion {@link DiscountLineFilter} applies to the {@code /lines} endpoint the
   * form reads, and {@code AbstractOrderHeaderHandler#batchComputeStatusPercentages} applies to the
   * percentages (ETP-5317).
   * The {@code IS NULL} arm keeps a product-less line counted, as it was before.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private static Map<String, OrderQuantities> batchFetchOrderedVsDelivered(List<String> ids) throws SQLException {
    String sql =
        "SELECT ol.C_Order_ID, COALESCE(SUM(ol.QtyOrdered), 0), COALESCE(SUM(ol.QtyDelivered), 0) " +
        "FROM C_OrderLine ol " +
        "WHERE ol.C_Order_ID IN (" + placeholders(ids) + ") AND ol.IsActive = 'Y' " +
        "AND (ol.M_Product_ID IS NULL OR ol.M_Product_ID <> ?) " +
        "GROUP BY ol.C_Order_ID";
    Map<String, OrderQuantities> result = new HashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx, TotalDiscountService.DISCOUNT_PRODUCT_ID);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1),
              new OrderQuantities(Objects.requireNonNullElse(rs.getBigDecimal(2), BigDecimal.ZERO),
                  Objects.requireNonNullElse(rs.getBigDecimal(3), BigDecimal.ZERO)));
        }
      }
    }
    return result;
  }

  /**
   * One query, all ids: which orders already have a DRAFT shipment (sales) / goods receipt
   * (purchase). Mirrors the form, which lists {@code M_InOut} by {@code C_Order_ID} and keeps the
   * rows in {@code DocStatus = 'DR'}.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private static Set<String> batchFindOrdersWithDraftPrimaryDoc(List<String> ids, String soTrx) throws SQLException {
    String sql =
        "SELECT DISTINCT io.C_Order_ID FROM M_InOut io " +
        "WHERE io.C_Order_ID IN (" + placeholders(ids) + ") AND io.IsActive = 'Y' " +
        "AND io.DocStatus = ? AND io.IsSOTrx = ?";
    Set<String> result = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx++, DOC_STATUS_DRAFT);
      ps.setString(idx, soTrx);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  /**
   * One query, all ids: per order, the summed {@code GrandTotal} of its COMPLETED linked invoices
   * and whether any linked invoice is still a DRAFT.
   *
   * <p><b>"Linked" means the union of TWO paths</b>, exactly as
   * {@code CreateDraftInvoiceHandler#handleList} — the very list the detail form reads — defines
   * it: (1) through the invoice lines ({@code C_InvoiceLine.C_OrderLine_ID → C_OrderLine.C_Order_ID}),
   * which covers invoices created from the classic Etendo UI and every partial-invoicing-by-lines
   * flow, and (2) directly through {@code C_Invoice.C_Order_ID}, which covers the edge case of an
   * invoice created by our own action that has no lines yet. The {@code UNION} deduplicates by
   * {@code (order, invoice)}, so an invoice reachable through both paths is counted once — the
   * same "merge both lists, deduplicate by id" that handler does in Java. Using only
   * {@code C_Invoice.C_Order_ID} here (as the narrower, unrelated
   * {@code AbstractOrderHeaderHandler#batchCheckLinkedDocuments} does) would make these flags
   * disagree with the form in
   * precisely the partial-invoicing cases this ticket is about.
   *
   * <p><b>Known pre-existing defect, replicated deliberately (ETP-5295).</b> When ONE invoice
   * groups lines coming from SEVERAL orders, its FULL {@code GrandTotal} is counted against EACH
   * of those orders, inflating the invoiced total and making {@code needsInvoiceDoc} read
   * {@code false} too early. The frontend form has exactly this bug today, and the goal of this
   * annotation is bit-for-bit parity with the form — fixing it here alone would replace one
   * disagreement with another. It is tracked separately; do NOT "fix" it in this method without
   * fixing the form in the same change.
   */
  // placeholders contains only "?" literals — all values are bound via setString(); no injection risk.
  @SuppressWarnings("java:S2077")
  private static Map<String, LinkedInvoiceTotals> batchFetchLinkedInvoiceTotals(List<String> ids, String soTrx)
      throws SQLException {
    String ph = placeholders(ids);
    String sql =
        "SELECT li.order_id, li.doc_status, COALESCE(SUM(li.grand_total), 0) FROM ( " +
        "SELECT ol.C_Order_ID AS order_id, i.C_Invoice_ID AS invoice_id, " +
        "       i.DocStatus AS doc_status, i.GrandTotal AS grand_total " +
        "  FROM C_Invoice i " +
        "  JOIN C_InvoiceLine il ON il.C_Invoice_ID = i.C_Invoice_ID AND il.IsActive = 'Y' " +
        "  JOIN C_OrderLine ol ON ol.C_OrderLine_ID = il.C_OrderLine_ID AND ol.IsActive = 'Y' " +
        " WHERE ol.C_Order_ID IN (" + ph + ") AND i.IsActive = 'Y' AND i.IsSOTrx = ? " +
        "UNION " +
        "SELECT i.C_Order_ID AS order_id, i.C_Invoice_ID AS invoice_id, " +
        "       i.DocStatus AS doc_status, i.GrandTotal AS grand_total " +
        "  FROM C_Invoice i " +
        " WHERE i.C_Order_ID IN (" + ph + ") AND i.IsActive = 'Y' AND i.IsSOTrx = ? " +
        ") li WHERE li.doc_status IN (?, ?) GROUP BY li.order_id, li.doc_status";
    Map<String, BigDecimal> completedTotals = new HashMap<>();
    Set<String> withDraft = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx++, soTrx);
      for (String id : ids) {
        ps.setString(idx++, id);
      }
      ps.setString(idx++, soTrx);
      ps.setString(idx++, DOC_STATUS_COMPLETED);
      ps.setString(idx, DOC_STATUS_DRAFT);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String orderId = rs.getString(1);
          if (DOC_STATUS_COMPLETED.equals(rs.getString(2))) {
            completedTotals.put(orderId,
                Objects.requireNonNullElse(rs.getBigDecimal(3), BigDecimal.ZERO));
          } else {
            withDraft.add(orderId);
          }
        }
      }
    }
    Map<String, LinkedInvoiceTotals> result = new HashMap<>();
    Set<String> touched = new HashSet<>(completedTotals.keySet());
    touched.addAll(withDraft);
    for (String orderId : touched) {
      result.put(orderId, new LinkedInvoiceTotals(
          completedTotals.getOrDefault(orderId, BigDecimal.ZERO), withDraft.contains(orderId)));
    }
    return result;
  }

  /**
   * Puts {@link AbstractOrderHeaderHandler#FIELD_PENDING_INVOICE_AMOUNT} on a completed order's
   * single-record GET when the header computes it ({@code amountOf}, the header's
   * {@link AbstractOrderHeaderHandler#pendingInvoiceAmount} hook) and {@code needsInvoiceDoc} (annotated just before) is {@code true}: with
   * nothing to invoice the modal shows no invoice card, so nothing is computed. Advisory: a
   * failure leaves the key out (the modal then derives its own figure, as before) and never fails
   * the GET. It is the last read of the GET: {@code DocumentListTotals.injectInto}, which runs
   * after it, is a no-op on a single-record GET, so a DB failure here cannot spoil a later query.
   */
  static void annotatePendingInvoiceAmount(JSONObject rec, String orderId,
      Function<String, BigDecimal> amountOf) {
    if (!DOC_STATUS_COMPLETED.equals(rec.optString("documentStatus", null))
        || !rec.optBoolean(FIELD_NEEDS_INVOICE_DOC, false)) {
      return;
    }
    try {
      BigDecimal amount = amountOf.apply(orderId);
      if (amount != null) {
        rec.put(AbstractOrderHeaderHandler.FIELD_PENDING_INVOICE_AMOUNT, amount);
      }
    } catch (Exception e) {
      log.warn("Could not compute {} for order {}: {}", AbstractOrderHeaderHandler.FIELD_PENDING_INVOICE_AMOUNT, orderId,
          e.getMessage());
    }
  }

  private static String placeholders(List<String> ids) {
    return ids.stream().map(id -> "?").collect(Collectors.joining(","));
  }

  /**
   * Aggregated line quantities of one order. Compared with {@code compareTo} rather than
   * subtracted into a {@code double}: the form's {@code qtyOrdered - qtyDelivered !== 0} runs on
   * JS floats, where an exactly-delivered order can leave a sub-unit residue and light the menu
   * entry up for nothing. Exact decimal comparison answers the question the form is asking.
   */
  private record OrderQuantities(BigDecimal ordered, BigDecimal delivered) { }

  /** Aggregated totals over the invoices linked to one order — see {@link #batchFetchLinkedInvoiceTotals}. */
  private record LinkedInvoiceTotals(BigDecimal completedTotal, boolean hasDraft) { }
}
