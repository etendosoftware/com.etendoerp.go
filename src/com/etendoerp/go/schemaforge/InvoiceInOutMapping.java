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

import java.util.ArrayList;
import java.util.List;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * The invoice side of an invoice → goods movement follow-up (ETP-5576): what
 * {@link InOutFollowUpCreator} needs from an invoice and nothing more — the
 * {@link InOutFollowUpCreator.SourceMapper source mapping} ({@link #map}) and the
 * {@link InOutTargetBuilder.LineLinker line link} ({@link #linker}). Kept out of the creator so the
 * creator stays reusable by other sources (an order mapper + its own linker).
 *
 * <p>Movement header: the invoice's client, organization, BP, address and currency; warehouse and
 * {@code C_Order_ID} as described on {@link #resolveWarehouse} / {@link #resolveOrder} (the
 * warehouse honors the caller's optional {@code warehouseId} input). Lines:
 * product, UOM, ASI, order line and description of the invoice line, the quantity
 * {@link InvoicePendingResolver} decided, and "needs a storage bin" =
 * {@link InOutLineFromOrderFactory#isStockable} of the line's product ({@code IsStocked='Y'} and
 * {@code ProductType='I'} — the rule the pending SQL applied before ETP-5576's resolver/creator
 * split moved it here, unchanged).
 */
final class InvoiceInOutMapping {

  private InvoiceInOutMapping() {
  }

  /**
   * {@link InOutFollowUpCreator.SourceMapper} for an invoice.
   *
   * @throws FollowUpException {@code NOT_FOUND} when the invoice, or any of the pending lines'
   *     invoice lines, does not exist; {@code INVALID_INPUT} / {@code WAREHOUSE_REQUIRED} from
   *     {@link #resolveWarehouse} — all thrown before any movement is built
   */
  static InOutFollowUpCreator.Mapping map(String invoiceId,
      List<PendingResolver.SourceLine> pendingLines, FollowUpInputs inputs) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (invoice == null) {
      throw new FollowUpException(FollowUpException.Reason.NOT_FOUND);
    }
    List<InvoiceLine> invoiceLines = new ArrayList<>(pendingLines.size());
    List<InOutTargetBuilder.Line> lines = new ArrayList<>(pendingLines.size());
    for (PendingResolver.SourceLine pending : pendingLines) {
      InvoiceLine il = OBDal.getInstance().get(InvoiceLine.class, pending.getSourceLineId());
      if (il == null) {
        // The line vanished between the pending verdict and this mapping: reject it like a
        // missing invoice instead of dereferencing null (a 500). Nothing has been built yet.
        throw new FollowUpException(FollowUpException.Reason.NOT_FOUND,
            "Source document line not found: " + pending.getSourceLineId());
      }
      invoiceLines.add(il);
      lines.add(InOutTargetBuilder.Line.builder()
          .sourceLineId(pending.getSourceLineId())
          .product(il.getProduct())
          .uom(il.getUOM())
          .attributeSetValue(il.getAttributeSetValue())
          .quantity(pending.getPendingQty())
          .orderLine(il.getSalesOrderLine())
          .description(il.getDescription())
          .stockable(InOutLineFromOrderFactory.isStockable(il.getProduct()))
          .build());
    }
    InOutTargetBuilder.Header header = new InOutTargetBuilder.Header(invoice.getClient(),
        invoice.getOrganization(), invoice.getBusinessPartner(), invoice.getPartnerAddress(),
        resolveWarehouse(invoice, invoiceLines, inputs), invoice.getCurrency(),
        resolveOrder(invoice, invoiceLines));
    return new InOutFollowUpCreator.Mapping(header, lines);
  }

  /**
   * Links each created movement line back to its invoice line through the invoice ↔ movement
   * match table of {@code direction} ({@link InvoiceLineLinker#linkInvoiceLineToInOutLine}).
   */
  static InOutTargetBuilder.LineLinker linker(InOutTargetBuilder.Direction direction) {
    InOutInvoiceLinks.MatchTable matchTable = InOutInvoiceLinks.MatchTable.forSalesTransaction(
        direction.isSalesTransaction());
    return (line, created) -> InvoiceLineLinker.linkInvoiceLineToInOutLine(
        line.getSourceLineId(), created.getId(), matchTable);
  }

  /**
   * Warehouse of the new movement: {@link InOutWarehouseResolver#resolve} with the invoice's
   * client and organization, the caller's {@code inputs}, and as the source's own warehouse
   * {@link #orderWarehouse} (the invoice's order, else the first carried line's order that has
   * one). Without an order, the caller's default warehouse, else the only usable one; several
   * usable → {@code WAREHOUSE_REQUIRED}, none → {@code null} ({@code MISSING_SETUP}). The former
   * "first active warehouse of the invoice's exact organization" fallback is gone: it ignored
   * warehouses of parent organizations ({@code *}) and picked arbitrarily among several.
   *
   * @throws FollowUpException {@code INVALID_INPUT} for a rejected {@code warehouseId} input;
   *     {@code WAREHOUSE_REQUIRED} when several warehouses are usable and none is designated
   */
  static Warehouse resolveWarehouse(Invoice invoice, List<InvoiceLine> lines,
      FollowUpInputs inputs) {
    return InOutWarehouseResolver.resolve(invoice.getClient(), invoice.getOrganization(),
        orderWarehouse(invoice, lines), inputs);
  }

  /**
   * The warehouse the invoice's orders designate, unchanged from the former
   * {@code CreateInvoiceShipmentHandler}: the invoice's order, else the order of the first carried
   * line that has one with a warehouse; {@code null} when none does.
   */
  static Warehouse orderWarehouse(Invoice invoice, List<InvoiceLine> lines) {
    if (invoice.getSalesOrder() != null && invoice.getSalesOrder().getWarehouse() != null) {
      return invoice.getSalesOrder().getWarehouse();
    }
    for (InvoiceLine line : lines) {
      OrderLine orderLine = line.getSalesOrderLine();
      if (orderLine != null && orderLine.getSalesOrder() != null
          && orderLine.getSalesOrder().getWarehouse() != null) {
        return orderLine.getSalesOrder().getWarehouse();
      }
    }
    return null;
  }

  /**
   * {@code C_Order_ID} of the new movement: the invoice's own order, else the single order every
   * carried line comes from, else none (lines from several orders, or none).
   */
  static Order resolveOrder(Invoice invoice, List<InvoiceLine> lines) {
    if (invoice.getSalesOrder() != null) {
      return invoice.getSalesOrder();
    }
    Order common = null;
    for (InvoiceLine line : lines) {
      Order order = line.getSalesOrderLine() != null ? line.getSalesOrderLine().getSalesOrder() : null;
      if (order == null) {
        return null;
      }
      if (common == null) {
        common = order;
      } else if (!common.getId().equals(order.getId())) {
        return null;
      }
    }
    return common;
  }
}
