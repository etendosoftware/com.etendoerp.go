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

  private InOutLineFromOrderFactory() {
  }

  /**
   * Returns the pending qty for an order line (ordered minus delivered), or
   * {@code null} when the line should be skipped from a new shipment/receipt
   * (inactive, missing product/UOM, or fully shipped/received).
   *
   * <p>Returning {@code null} (instead of throwing or returning ZERO with a
   * separate flag) keeps the caller loop tight: "fetch qty, skip if null,
   * otherwise create line".
   */
  static BigDecimal pendingQuantityFor(OrderLine orderLine) {
    if (!orderLine.isActive() || orderLine.getProduct() == null || orderLine.getUOM() == null) {
      return null;
    }
    BigDecimal orderedQty = orderLine.getOrderedQuantity();
    if (orderedQty == null) {
      return null;
    }
    BigDecimal deliveredQty = orderLine.getDeliveredQuantity() != null
        ? orderLine.getDeliveredQuantity() : BigDecimal.ZERO;
    BigDecimal pending = orderedQty.subtract(deliveredQty);
    return pending.compareTo(BigDecimal.ZERO) > 0 ? pending : null;
  }

  /**
   * Persists a new {@link ShipmentInOutLine} populated from {@code orderLine}
   * and attached to {@code parentInOut}, then links any draft invoice lines
   * of the same order line via {@link InvoiceLineLinker}. The flow mirrors
   * what the canonical {@code m_inout_create} stored procedure performs in
   * classic when generating a shipment/receipt from an order.
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
    line.setStorageBin(locator);
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
