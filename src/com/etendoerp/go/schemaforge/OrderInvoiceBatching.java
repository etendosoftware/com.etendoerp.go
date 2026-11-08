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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * How {@link ImportOrderLinesIntoInvoiceHandler} splits the planned quantity of an order into the
 * native calls (entries bound to a delivered movement line, then the remainder) and undoes the
 * remainder's marker link, moved unchanged out of the handler (ETP-6099, the handler was past the
 * method count Sonar accepts). Every DAL read, lock and rollback goes back through the handler's
 * seams, so what a test overrides on the handler is still what runs.
 */
final class OrderInvoiceBatching {

  private final ImportOrderLinesIntoInvoiceHandler handler;

  OrderInvoiceBatching(ImportOrderLinesIntoInvoiceHandler handler) {
    this.handler = handler;
  }

  /**
   * One native call per phase. First the entries bound to a movement line (the order line AND the
   * receipt / shipment line, so the invoice line points at both and the movement counts as invoiced
   * for the receipt cap); then, in a second call, the remainder: what was ordered and not delivered
   * (or already taken by a draft), plus any line without movements.
   *
   * <p>The native process replaces a PLAIN order-line entry by every completed, not yet invoiced
   * movement line of that order line, at THEIR quantity, and it ignores drafts: a receipt line fully
   * covered by another draft invoice is still "not invoiced" for it (live case: order 10, receipt 6
   * in a draft, request 4 gets swapped for the receipt's 6). An entry that already names a movement
   * line is never swapped, so a remainder entry of an order line that has such lines carries one of
   * them as a MARKER ({@code shipmentInOutLine}) and the link is removed once the line exists
   * ({@link #unmarkRemainder}): the quantity is the entry's, the prices, tax and UOM are the
   * order line's, and the invoice line ends up pointing at the order line only, so the receipt
   * is not counted twice by its cap.
   */
  ImportOrderLinesIntoInvoiceHandler.Batches buildBatches(Map<String, OrderLine> linesById,
      Map<String, BigDecimal> planned, boolean sales) throws SQLException, JSONException {
    Map<String, List<ShipmentInOutLine>> movementsByLine = new LinkedHashMap<>();
    Set<String> movementIds = new java.util.TreeSet<>();
    Set<String> documentIds = new java.util.LinkedHashSet<>();
    for (String id : planned.keySet()) {
      List<ShipmentInOutLine> movements = handler.movementLinesOf(linesById.get(id));
      movementsByLine.put(id, movements);
      for (ShipmentInOutLine movement : movements) {
        movementIds.add(movement.getId());
        documentIds.add(movement.getShipmentReceipt().getId());
      }
    }
    handler.lockInOutLines(movementIds);
    Map<String, BigDecimal> pendingByMovement = new HashMap<>();
    for (String documentId : documentIds) {
      pendingByMovement.putAll(handler.movementPending(documentId));
    }
    ImportOrderLinesIntoInvoiceHandler.Batches batches =
        new ImportOrderLinesIntoInvoiceHandler.Batches();
    JSONArray bound = new JSONArray();
    JSONArray remainders = new JSONArray();
    for (Map.Entry<String, BigDecimal> entry : planned.entrySet()) {
      OrderLine orderLine = linesById.get(entry.getKey());
      List<OrderLineMovementAllocation.Allocation> allocations =
          OrderLineMovementAllocation.allocate(entry.getValue(),
              OrderLineMovementAllocation.candidatesOf(orderLine, entry.getValue(),
                  movementsByLine.get(entry.getKey()), pendingByMovement));
      BigDecimal remainder = entry.getValue();
      for (OrderLineMovementAllocation.Allocation allocation : allocations) {
        bound.put(MultiDocumentInvoiceSupport.buildOrderLineEntryForInOutLine(orderLine.getId(),
            allocation.line, allocation.quantity));
        remainder = remainder.subtract(allocation.quantity);
      }
      if (remainder.signum() != 0) {
        JSONObject plain = new JSONObject().put("id", orderLine.getId())
            .put("orderedQuantity", remainder.toPlainString());
        Set<String> related = handler.nativeRelatedRemaining(orderLine, sales).keySet();
        if (!related.isEmpty()) {
          String marker = related.iterator().next();
          plain.put("shipmentInOutLine", marker);
          batches.markerIds.add(marker);
        }
        remainders.put(plain);
      }
    }
    if (bound.length() > 0) {
      batches.calls.add(bound);
    }
    if (remainders.length() > 0) {
      batches.calls.add(remainders);
      batches.remainderCall = batches.calls.size() - 1;
    }
    return batches;
  }

  /**
   * Removes the marker link from the lines the remainder call created, then asserts that NO line of
   * that call still points at a movement line: a skipped unmark would leave the receipt counted
   * twice by its cap, so it is rolled back and refused, never left silent.
   */
  void unmarkRemainder(ImportOrderLinesIntoInvoiceHandler.Batches batches, int call,
      List<InvoiceLine> created) {
    if (call != batches.remainderCall || batches.markerIds.isEmpty()) {
      return;
    }
    for (InvoiceLine line : created) {
      ShipmentInOutLine linked = line.getGoodsShipmentLine();
      if (linked != null && batches.markerIds.contains(linked.getId())) {
        line.setGoodsShipmentLine(null);
        OBDal.getInstance().save(line);
      }
    }
    OBDal.getInstance().flush();
    for (InvoiceLine line : created) {
      if (line.getGoodsShipmentLine() != null) {
        handler.rollback();
        throw DocumentLineImport.refusal(DocumentLineImport.ERROR_QUANTITY_NOT_HONOURED,
            "A remainder line is still linked to a receipt / shipment line; nothing was imported");
      }
    }
  }
}
