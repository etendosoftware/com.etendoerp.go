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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Shared helpers for creating {@link ShipmentInOutLine} records from a
 * {@link OrderLine}, used by both the sales-shipment and the purchase-receipt
 * handlers. Centralized so the two handlers don't duplicate the
 * pending-quantity rules nor the line-population code.
 */
final class InOutLineFromOrderFactory {

  private static final Logger log = LogManager.getLogger(InOutLineFromOrderFactory.class);

  private InOutLineFromOrderFactory() {
  }

  /**
   * Returns the pending qty for an order line (ordered minus delivered), or
   * {@code null} when the line should be skipped from a new shipment/receipt
   * (inactive, missing product/UOM, non-stockable/non-Item product, or fully
   * shipped/received).
   *
   * <p>Returning {@code null} (instead of throwing or returning ZERO with a
   * separate flag) keeps the caller loop tight: "fetch qty, skip if null,
   * otherwise create line".
   *
   * <p><b>ETP-4853:</b> a product that is not stockable, or not of type Item
   * (e.g. the synthetic global-discount line materialized by
   * {@link TotalDiscountService}, or a Service/Expense product), never
   * represents physical stock movement and must never become a shipment/
   * receipt line. This mirrors the discriminator the classic {@code
   * M_INOUT_CREATE} stored procedure uses ({@code IsStocked='Y' AND
   * ProductType='I'}) to decide whether an order line belongs in the
   * generated document.
   *
   * <p><b>ETP-4722:</b> ordered/delivered quantities can be NEGATIVE since
   * ETP-4567 removed the old {@code min: 0} constraint on order lines (e.g.
   * a return-style line on a Purchase Order or Sales Order). "Pending" then
   * means "not yet delivered in either direction", so the line must be kept
   * whenever {@code pending} is non-zero — not only when it's strictly
   * positive. A strictly-positive check silently dropped every
   * negative-quantity line from the generated Goods Receipt / Goods
   * Shipment, because {@code pending} for such a line is itself negative.
   */
  static BigDecimal pendingQuantityFor(OrderLine orderLine) {
    if (!orderLine.isActive() || orderLine.getProduct() == null || orderLine.getUOM() == null) {
      return null;
    }
    if (!Boolean.TRUE.equals(orderLine.getProduct().isStocked())
        || !"I".equals(orderLine.getProduct().getProductType())) {
      return null;
    }
    BigDecimal orderedQty = orderLine.getOrderedQuantity();
    if (orderedQty == null) {
      return null;
    }
    BigDecimal deliveredQty = orderLine.getDeliveredQuantity() != null
        ? orderLine.getDeliveredQuantity() : BigDecimal.ZERO;
    BigDecimal pending = orderedQty.subtract(deliveredQty);
    return pending.compareTo(BigDecimal.ZERO) != 0 ? pending : null;
  }

  /**
   * Persists a new {@link ShipmentInOutLine} populated from {@code orderLine}
   * and attached to {@code parentInOut}, then links any draft invoice lines
   * of the same order line via {@link InvoiceLineLinker}. The flow mirrors
   * what the canonical {@code m_inout_create} stored procedure performs in
   * classic when generating a shipment/receipt from an order.
   *
   * <p><b>ETP-4863:</b> {@code locator} arrives resolved from the ORDER's warehouse — both
   * callers ({@code CreateShipmentHandler}, {@code CreateGoodsReceiptHandler}) obtain it via
   * {@code findDefaultLocator(order)}. What {@code M_INOUT_POST} actually follows when it books
   * the stock transaction is the LINE's bin measured against the DOCUMENT header's warehouse.
   * Those two warehouses happen to coincide today (the header is built from the same order), but
   * nothing enforced it, so this path is normalized through the same
   * {@link NeoHandlerUtils#anchorLocatorToWarehouse} rule as every other {@code M_InOutLine}
   * write path in the module rather than trusting an invariant that lives in another class.
   */
  static void createAndLinkLine(ShipmentInOut parentInOut, OrderLine orderLine,
      Locator locator, long lineNo, BigDecimal pendingQty) {
    ShipmentInOutLine line = OBProvider.getInstance().get(ShipmentInOutLine.class);
    line.setClient(orderLine.getClient());
    line.setOrganization(orderLine.getOrganization());
    line.setShipmentReceipt(parentInOut);
    line.setLineNo(lineNo);
    line.setProduct(orderLine.getProduct());
    line.setUOM(orderLine.getUOM());
    line.setStorageBin(
        NeoHandlerUtils.anchorLocatorToWarehouse(locator, parentInOut.getWarehouse(), log));
    line.setMovementQuantity(pendingQty);
    line.setSalesOrderLine(orderLine);
    line.setDescription(orderLine.getDescription());

    OBDal.getInstance().save(line);
    // Flush so the new inout line gets a persisted id available to the link
    // helper, which uses it as the `inoutLineId` parameter of the UPDATE.
    OBDal.getInstance().flush();
    InvoiceLineLinker.linkPendingInvoiceLinesToInout(line, orderLine.getId());
  }
}
