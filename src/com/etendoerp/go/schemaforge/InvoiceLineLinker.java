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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Utility for linking draft invoice lines to a freshly created shipment/receipt
 * inout line. Mirrors the UPDATE that the canonical {@code m_inout_create}
 * stored procedure performs in classic when generating a shipment/receipt from
 * an order. Without this step, {@code m_inout_post} can't create
 * {@code m_matchsi}/{@code m_matchinv} when the inout is later completed,
 * leaving the delivery-status column at 0% on the related invoices.
 */
final class InvoiceLineLinker {

  /**
   * Etendo's "System" user id. Used as the {@code UpdatedBy} fallback when no
   * user is present in the current {@link OBContext} (e.g. background
   * processes), matching the convention used elsewhere in Etendo.
   */
  private static final String SYSTEM_USER_ID = "0";

  /** Named-parameter keys shared by the native statements below. */
  private static final String PARAM_INOUT_LINE_ID = "inoutLineId";
  private static final String PARAM_USER_ID = "userId";

  private InvoiceLineLinker() {
  }

  /**
   * Links any draft invoice line that points to {@code orderLineId} and is
   * still unlinked to {@code newInoutLine}. Idempotent: rows already linked
   * to a different inout line are left untouched.
   *
   * <p>Caller is expected to have flushed the new inout line before calling
   * this method, so {@code newInoutLine.getId()} returns a persisted id.
   */
  static void linkPendingInvoiceLinesToInout(ShipmentInOutLine newInoutLine, String orderLineId) {
    OBDal.getInstance().getSession()
        .createNativeQuery(
            "UPDATE C_InvoiceLine "
            + "SET M_InOutLine_ID = :inoutLineId, "
            + "    Updated = now(), "
            + "    UpdatedBy = :userId "
            + "WHERE C_OrderLine_ID = :orderLineId "
            + "  AND M_InOutLine_ID IS NULL")
        .setParameter(PARAM_INOUT_LINE_ID, newInoutLine.getId())
        .setParameter(PARAM_USER_ID, currentUserIdOrSystem())
        .setParameter("orderLineId", orderLineId)
        .executeUpdate();
  }

  /**
   * SALES "create invoice from order" (ETP-6002): plans, BEFORE the invoice lines exist, which
   * shipment lines each plain order-line entry of the native selection would take under the one
   * rule ({@link OrderLineMovementAllocation}: oldest pending completed shipment line first, split
   * over the next ones). Computed before the native process runs because the pending quantity
   * counts draft invoices: afterwards the invoice's own new lines would hold every shipment line
   * and nothing would look pending. Entries already bound to a movement line
   * ({@code shipmentInOutLine}) are left out.
   *
   * @param selectedLines the native selection, {@code [{id: orderLineId, orderedQuantity, ...}]}
   * @return order line id → its allocations, oldest first; order lines with none are absent
   */
  static Map<String, List<OrderLineMovementAllocation.Allocation>> planSalesShipmentLines(
      JSONArray selectedLines) {
    Map<String, List<OrderLineMovementAllocation.Allocation>> plan = new LinkedHashMap<>();
    if (selectedLines == null) {
      return plan;
    }
    for (int i = 0; i < selectedLines.length(); i++) {
      JSONObject entry = selectedLines.optJSONObject(i);
      if (entry == null || entry.has(SHIPMENT_INOUT_LINE)) {
        continue;
      }
      String orderLineId = entry.optString("id", null);
      BigDecimal quantity = quantityOf(entry.optString(ORDERED_QUANTITY, null));
      OrderLine orderLine = orderLineId == null ? null
          : OBDal.getInstance().get(OrderLine.class, orderLineId);
      List<OrderLineMovementAllocation.Allocation> allocations =
          OrderLineMovementAllocation.allocateOrderLine(orderLine, quantity);
      if (!allocations.isEmpty()) {
        plan.put(orderLineId, allocations);
      }
    }
    return plan;
  }

  /**
   * SALES counterpart of {@link #linkInvoiceLinesToExistingInouts}: links each line of
   * {@code invoice} that has an order line and no shipment line yet to the oldest shipment line
   * {@link #planSalesShipmentLines} assigned to that order line which no line of this invoice
   * already points at (the native process may have bound some lines itself). Replaces the
   * {@code MAX(M_InOutLine_ID)} backfill, which could pick a shipment line another invoice had
   * already billed. An existing invoice line is never split: a line holding more than the chosen
   * shipment line's share is still linked to it alone.
   */
  static void linkSalesInvoiceLinesToPlannedShipments(Invoice invoice,
      Map<String, List<OrderLineMovementAllocation.Allocation>> plan) {
    if (invoice == null || plan == null || plan.isEmpty()) {
      return;
    }
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    Set<String> taken = new HashSet<>();
    for (InvoiceLine line : lines) {
      if (line.getGoodsShipmentLine() != null) {
        taken.add(line.getGoodsShipmentLine().getId());
      }
    }
    boolean changed = false;
    for (InvoiceLine line : lines) {
      if (line.getGoodsShipmentLine() != null || line.getSalesOrderLine() == null) {
        continue;
      }
      List<OrderLineMovementAllocation.Allocation> allocations =
          plan.get(line.getSalesOrderLine().getId());
      if (allocations == null) {
        continue;
      }
      for (OrderLineMovementAllocation.Allocation allocation : allocations) {
        if (taken.add(allocation.line.getId())) {
          line.setGoodsShipmentLine(allocation.line);
          OBDal.getInstance().save(line);
          changed = true;
          break;
        }
      }
    }
    if (changed) {
      OBDal.getInstance().flush();
    }
  }

  private static final String SHIPMENT_INOUT_LINE = "shipmentInOutLine";
  private static final String ORDERED_QUANTITY = "orderedQuantity";

  private static BigDecimal quantityOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Backfills the inout link on every draft invoice line of the given invoice
   * whose order line already has at least one shipment/receipt line. Mirrors
   * the lookup that {@code c_invoice_create} performs in classic when an
   * invoice is generated from an order, so the matching tables get populated
   * regardless of which document was created first (invoice or shipment).
   *
   * <p>Since ETP-6002 the PURCHASE caller only ({@code CreatePurchaseInvoiceHandler}, unchanged).
   * The sales "create invoice from order" path uses {@link #planSalesShipmentLines} and
   * {@link #linkSalesInvoiceLinesToPlannedShipments} instead.
   */
  static void linkInvoiceLinesToExistingInouts(String invoiceId) {
    OBDal.getInstance().getSession()
        .createNativeQuery(
            "UPDATE C_InvoiceLine il "
            + "SET M_InOutLine_ID = ("
            + "    SELECT MAX(iol.M_InOutLine_ID) "
            + "    FROM M_InOutLine iol "
            + "    WHERE iol.C_OrderLine_ID = il.C_OrderLine_ID), "
            + "    Updated = now(), "
            + "    UpdatedBy = :userId "
            + "WHERE il.C_Invoice_ID = :invoiceId "
            + "  AND il.M_InOutLine_ID IS NULL "
            + "  AND il.C_OrderLine_ID IS NOT NULL "
            + "  AND EXISTS ("
            + "    SELECT 1 FROM M_InOutLine iol "
            + "    WHERE iol.C_OrderLine_ID = il.C_OrderLine_ID)")
        .setParameter(PARAM_USER_ID, currentUserIdOrSystem())
        .setParameter("invoiceId", invoiceId)
        .executeUpdate();
  }

  /**
   * Links ONE invoice line to ONE freshly created (draft) movement line — the explicit, pair-wise
   * link the follow-up document service uses (ETP-5576). Unlike
   * {@link #linkPendingInvoiceLinesToInout}, it never touches any other invoice line.
   *
   * <p>Mirrors Classic's "Create lines from invoice" ({@code CreateFrom.java}, the receipt branch
   * and {@code updateInvoiceAndBOMStructure}) with one deliberate refinement, and is the same rule
   * for both directions:
   * <ul>
   *   <li><b>The invoice line has no {@code M_InOutLine_ID} yet</b> → set it, and write NO match
   *       row. {@code M_INOUT_POST} creates the match row itself when the movement is completed,
   *       from that very column and with the movement's FINAL quantity: unconditionally on the
   *       purchase side ({@code M_MatchInv}, no existence check — a draft-time row here would be
   *       duplicated at completion), with a {@code NOT EXISTS} guard on the sales side
   *       ({@code M_MatchSI}). Classic inserts the sales row at draft time as well; deferring it
   *       to completion gives the same end state without freezing a quantity the user may still
   *       edit on the draft.</li>
   *   <li><b>The invoice line already points at another movement line</b> (a second or later
   *       partial shipment/receipt) → the column cannot hold a second link, and
   *       {@code M_INOUT_POST} will not create a match row for a line the column does not point
   *       at, so the match row is written now, idempotently ({@code NOT EXISTS}), with the movement
   *       line's current quantity — exactly Classic's {@code insertMatchSI}/{@code insertMatchInv}.</li>
   * </ul>
   *
   * <p>Both statements are native SQL on purpose: the invoice is already completed, and the core
   * {@code C_INVOICELINE_TRG} lets a link-only update through (it returns early unless quantity,
   * amount, product, tax or UOM change). Match rows cascade-delete with the movement line, so
   * deleting the draft movement leaves nothing behind; the column is {@code ON DELETE SET NULL}.
   *
   * @param invoiceLineId the source invoice line
   * @param inoutLineId the new movement line; must already be flushed
   * @param matchTable the match table of the transaction direction
   */
  static void linkInvoiceLineToInOutLine(String invoiceLineId, String inoutLineId,
      InOutInvoiceLinks.MatchTable matchTable) {
    String userId = currentUserIdOrSystem();
    int updated = OBDal.getInstance().getSession()
        .createNativeQuery(
            "UPDATE C_InvoiceLine "
            + "SET M_InOutLine_ID = :inoutLineId, "
            + "    Updated = now(), "
            + "    UpdatedBy = :userId "
            + "WHERE C_InvoiceLine_ID = :invoiceLineId "
            + "  AND M_InOutLine_ID IS NULL")
        .setParameter(PARAM_INOUT_LINE_ID, inoutLineId)
        .setParameter(PARAM_USER_ID, userId)
        .setParameter("invoiceLineId", invoiceLineId)
        .executeUpdate();
    if (updated > 0) {
      return;
    }
    String table = matchTable.tableName();
    OBDal.getInstance().getSession()
        .createNativeQuery(
            "INSERT INTO " + table + " ("
            + matchTable.idColumn() + ", ad_client_id, ad_org_id, isactive, created, createdby, "
            + "updated, updatedby, m_inoutline_id, c_invoiceline_id, m_product_id, datetrx, qty, "
            + "processing, processed, posted) "
            + "SELECT get_uuid(), iol.ad_client_id, iol.ad_org_id, 'Y', now(), :userId, "
            + "now(), :userId, iol.m_inoutline_id, il.c_invoiceline_id, iol.m_product_id, "
            + "i.dateacct, iol.movementqty, 'N', 'Y', 'N' "
            + "FROM m_inoutline iol "
            + "JOIN c_invoiceline il ON il.c_invoiceline_id = :invoiceLineId "
            + "JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id "
            + "WHERE iol.m_inoutline_id = :inoutLineId "
            + "AND NOT EXISTS (SELECT 1 FROM " + table + " mt "
            + "  WHERE mt.m_inoutline_id = iol.m_inoutline_id "
            + "    AND mt.c_invoiceline_id = il.c_invoiceline_id)")
        .setParameter(PARAM_USER_ID, userId)
        .setParameter("invoiceLineId", invoiceLineId)
        .setParameter(PARAM_INOUT_LINE_ID, inoutLineId)
        .executeUpdate();
  }

  /**
   * Returns the current user's id, or the System user id when the context has
   * no user (background tasks, system-init paths). Avoids NPEs at the call
   * sites of {@link #linkPendingInvoiceLinesToInout}.
   */
  private static String currentUserIdOrSystem() {
    OBContext ctx = OBContext.getOBContext();
    if (ctx == null) return SYSTEM_USER_ID;
    User user = ctx.getUser();
    return user != null ? user.getId() : SYSTEM_USER_ID;
  }
}
