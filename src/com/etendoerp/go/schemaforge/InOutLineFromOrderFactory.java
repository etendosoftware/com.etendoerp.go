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
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
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
   * (inactive, missing product/UOM, the synthetic discount line, or fully
   * shipped/received).
   *
   * <p>Returning {@code null} (instead of throwing or returning ZERO with a
   * separate flag) keeps the caller loop tight: "fetch qty, skip if null,
   * otherwise create line".
   *
   * <p><b>ETP-4844:</b> the synthetic global-discount line ({@code ETGO_DTO},
   * {@link TotalDiscountService#DISCOUNT_PRODUCT_ID}) is excluded by explicit ID,
   * the same guard {@code CreateDraftInvoiceHandler.resolvePendingForLine()} uses
   * for the Order → Invoice path. Goods Receipt/Shipment are quantity-only,
   * non-fiscal documents that structurally cannot hold a priced discount line —
   * one leaking in corrupts any invoice generated downstream from that receipt/
   * shipment. This check is by explicit product ID, not by stockable/Item-type,
   * so it does not depend on that product's master data staying configured a
   * particular way (see ETP-5276 below).
   *
   * <p><b>ETP-5276:</b> a product that is not stockable, or not of type Item
   * (e.g. a Service/Expense/Resource product), DOES represent a valid
   * shipment/receipt line — the classic {@code M_INOUT_CREATE} stored
   * procedure copies exactly these lines into the generated document (with a
   * {@code NULL} storage bin, see its {@code -- Copy Ad-hoc lines, Comments OR
   * Service Items} branch). A previous revision of this method (ETP-4853)
   * dropped non-stockable/non-Item lines entirely, believing that mirrored
   * {@code M_INOUT_CREATE}'s discriminator — it does not: that discriminator
   * only selects HOW a line is built (with or without a bin), never WHETHER
   * it is included. Dropping every such line meant an order made up only of
   * service products produced an empty, orphaned shipment/receipt with a
   * misleading "no pending lines" error (ETP-5276). See
   * {@link #isStockable(Product)} for where the stockable/Item-type
   * distinction is still used — to decide the storage bin, not eligibility.
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
    if (TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(orderLine.getProduct().getId())) {
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
   * True when {@code product} represents physical stock ({@code IsStocked='Y'}
   * and {@code ProductType='I'}) and therefore needs a storage bin on its
   * shipment/receipt line. A Service/Expense/Resource product (or any
   * non-stockable Item) is a valid line — see {@link #pendingQuantityFor} —
   * but must never be assigned one, mirroring how classic
   * {@code M_INOUT_CREATE} leaves {@code M_Locator_ID} {@code NULL} for those
   * lines.
   */
  static boolean isStockable(Product product) {
    return product != null
        && Boolean.TRUE.equals(product.isStocked())
        && "I".equals(product.getProductType());
  }

  /**
   * Walks every line of {@code order} and returns the ones still pending
   * delivery/receipt, paired with their pending quantity. Single collection
   * point shared by {@code CreateShipmentHandler} and
   * {@code CreateGoodsReceiptHandler} so both can validate "are there any
   * lines at all" BEFORE creating and persisting the document header —
   * see ETP-5276, where persisting the header first left an empty, orphaned
   * shipment/receipt behind whenever this list turned out empty.
   */
  static List<PendingOrderLine> collectPendingLines(Order order) {
    List<PendingOrderLine> pendingLines = new ArrayList<>();
    for (OrderLine orderLine : order.getOrderLineList()) {
      BigDecimal pendingQty = pendingQuantityFor(orderLine);
      if (pendingQty != null) {
        pendingLines.add(new PendingOrderLine(orderLine, pendingQty));
      }
    }
    return pendingLines;
  }

  /**
   * True when at least one of {@code pendingLines} needs a real storage bin
   * ({@link #isStockable}). Callers use this to decide whether resolving the
   * order's default locator is required at all — an order made up entirely
   * of Service/Expense/Resource lines has no stock to bin and must not fail
   * just because its warehouse has no locator configured.
   */
  static boolean hasStockableLine(List<PendingOrderLine> pendingLines) {
    for (PendingOrderLine pendingLine : pendingLines) {
      if (isStockable(pendingLine.getOrderLine().getProduct())) {
        return true;
      }
    }
    return false;
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
   *
   * <p><b>ETP-5276:</b> that anchoring is applied ONLY when the line's product
   * {@link #isStockable}. {@code anchorLocatorToWarehouse} resolves a fallback bin whenever its
   * candidate is {@code null} (see its javadoc), so passing {@code locator=null} straight through
   * for a non-stockable line would still end up anchoring a bin onto it. The bin must stay
   * {@code null} for those lines, matching classic {@code M_INOUT_CREATE}.
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
    line.setStorageBin(isStockable(orderLine.getProduct())
        ? NeoHandlerUtils.anchorLocatorToWarehouse(locator, parentInOut.getWarehouse(), log)
        : null);
    line.setMovementQuantity(pendingQty);
    line.setSalesOrderLine(orderLine);
    line.setDescription(orderLine.getDescription());

    OBDal.getInstance().save(line);
    // Flush so the new inout line gets a persisted id available to the link
    // helper, which uses it as the `inoutLineId` parameter of the UPDATE.
    OBDal.getInstance().flush();
    InvoiceLineLinker.linkPendingInvoiceLinesToInout(line, orderLine.getId());
  }

  /**
   * Pairs an {@link OrderLine} with its still-pending quantity, as computed by
   * {@link #pendingQuantityFor}. Immutable value holder returned by
   * {@link #collectPendingLines}.
   */
  static final class PendingOrderLine {
    private final OrderLine orderLine;
    private final BigDecimal pendingQty;

    private PendingOrderLine(OrderLine orderLine, BigDecimal pendingQty) {
      this.orderLine = orderLine;
      this.pendingQty = pendingQty;
    }

    OrderLine getOrderLine() {
      return orderLine;
    }

    BigDecimal getPendingQty() {
      return pendingQty;
    }
  }
}
