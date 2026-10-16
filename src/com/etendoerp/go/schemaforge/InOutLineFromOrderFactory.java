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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
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
   * The tail of the validate-before-mutate step shared by {@code CreateShipmentHandler} and
   * {@code CreateGoodsReceiptHandler} (see
   * {@link #resolvePendingLinesAndLocator(Order, boolean, String, String, Function)}): throws
   * {@code emptyLinesMessage} when there are no pending lines, and — only when at least one
   * pending line {@link #isStockable} — resolves and validates the order's default locator,
   * throwing when the warehouse has none configured. Extracted here (rather than duplicated per
   * handler) because both handlers previously carried near-identical copies of this exact
   * sequence (ETP-5276 Sonar duplication follow-up).
   *
   * <p>{@code locatorResolver} is a method reference to the CALLING handler's own
   * {@code findDefaultLocator(Order)} — a {@code protected} hook each handler's tests override to
   * bypass the criteria query — so this static helper still dispatches through that per-instance
   * override instead of hardcoding one handler's lookup.
   *
   * @param order the order being converted to a shipment/receipt
   * @param pendingLines the lines still pending, already computed by the caller
   * @param emptyLinesMessage the handler-specific message to throw when there are zero pending lines
   * @param locatorResolver resolves the calling handler's default locator for {@code order}
   * @return the validated pending lines plus the resolved default locator ({@code null} when no
   *         pending line needs one)
   * @throws OBException when there are no pending lines, or a stockable line exists but the
   *         order's warehouse has no locator configured
   */
  private static PendingLinesResult finishResolution(Order order,
      List<PendingOrderLine> pendingLines, String emptyLinesMessage,
      Function<Order, Locator> locatorResolver) {
    if (pendingLines.isEmpty()) {
      throw new OBException(emptyLinesMessage);
    }
    Locator defaultLocator = null;
    if (hasStockableLine(pendingLines)) {
      defaultLocator = locatorResolver.apply(order);
      if (defaultLocator == null) {
        String warehouseName = order.getWarehouse() != null ? order.getWarehouse().getName() : "unknown";
        throw new OBException("No storage locator found for warehouse: " + warehouseName);
      }
    }
    return new PendingLinesResult(pendingLines, defaultLocator);
  }

  /**
   * The single validate-before-mutate step shared by {@code CreateShipmentHandler} and
   * {@code CreateGoodsReceiptHandler} (ETP-5276, draft-aware since ETP-5709): computes the pending
   * lines, throws when there are none, and resolves the default locator when a line needs one.
   * A DRAFT shipment/receipt reserves nothing ({@code c_orderline.qtydelivered} only
   * moves when the document is completed), so without this the same order could be turned into
   * several drafts for the same quantities and, once all were completed, over-received.
   *
   * <p>pending(line) = ordered - delivered - SUM(movementQuantity of the lines of DRAFT
   * documents of the same {@code salesTransaction} that point at that order line). A line is created
   * only when the remainder is non-zero AND has the same sign as the base pending quantity
   * (negative lines are legitimate, ETP-4722); a zero or sign-flipped remainder means the drafts
   * already cover the line, possibly with MORE than is pending (a manual or edited draft, or an
   * order line reduced after the draft), and the line is skipped rather than turned into an
   * inverted-sign line. When no line is left but at least one was skipped only because
   * of drafts, the error is {@code draftCoveredMessageFormat} formatted with the covering draft
   * document numbers; otherwise it is {@code emptyLinesMessage}.
   *
   * <p>Generic by construction: it keys on {@code salesTransaction} (a structural flag of the
   * document) and never names a spec or entity; each handler passes its own direction and
   * messages. Single HQL per call, aggregated in Java per order line.
   *
   * <p><b>Concurrency:</b> two simultaneous calls can both read "no draft" and each create one.
   * This is intentionally not handled here (no row lock on the order); the window is the
   * duration of one request.
   *
   * @param draftCoveredMessageFormat {@link String#format} pattern with one {@code %s} for the
   *     comma-separated draft document numbers
   */
  static PendingLinesResult resolvePendingLinesAndLocator(Order order, boolean salesTransaction,
      String emptyLinesMessage, String draftCoveredMessageFormat,
      Function<Order, Locator> locatorResolver) {
    Set<String> coveringDrafts = new LinkedHashSet<>();
    List<PendingOrderLine> pendingLines =
        collectPendingLines(order, salesTransaction, coveringDrafts);
    if (pendingLines.isEmpty() && !coveringDrafts.isEmpty()) {
      throw new OBException(String.format(draftCoveredMessageFormat,
          String.join(", ", coveringDrafts)));
    }
    return finishResolution(order, pendingLines, emptyLinesMessage, locatorResolver);
  }

  /**
   * Collects the lines still pending after discounting DRAFT documents; see
   * {@link #resolvePendingLinesAndLocator(Order, boolean, String, String, Function)}.
   *
   * @param coveringDrafts out parameter: document numbers of the drafts that fully or partially
   *     covered a line that would otherwise have been pending
   */
  static List<PendingOrderLine> collectPendingLines(Order order, boolean salesTransaction,
      Set<String> coveringDrafts) {
    Map<String, BigDecimal> basePending = new HashMap<>();
    List<OrderLine> candidates = new ArrayList<>();
    for (OrderLine orderLine : order.getOrderLineList()) {
      BigDecimal pendingQty = pendingQuantityFor(orderLine);
      if (pendingQty != null) {
        basePending.put(orderLine.getId(), pendingQty);
        candidates.add(orderLine);
      }
    }
    List<PendingOrderLine> pendingLines = new ArrayList<>();
    if (candidates.isEmpty()) {
      return pendingLines;
    }

    Map<String, BigDecimal> draftQty = new HashMap<>();
    Map<String, Set<String>> draftDocs = new HashMap<>();
    loadDraftQuantities(basePending.keySet(), salesTransaction, draftQty, draftDocs);

    for (OrderLine orderLine : candidates) {
      String id = orderLine.getId();
      BigDecimal inDraft = draftQty.getOrDefault(id, BigDecimal.ZERO);
      BigDecimal remainder = basePending.get(id).subtract(inDraft);
      // Same sign only: a draft larger than the pending quantity leaves a remainder of the
      // opposite sign, which would otherwise become an inverted line on the new document.
      if (remainder.signum() == basePending.get(id).signum()) {
        pendingLines.add(new PendingOrderLine(orderLine, remainder));
      } else if (draftDocs.containsKey(id)) {
        coveringDrafts.addAll(draftDocs.get(id));
      }
    }
    return pendingLines;
  }

  /** One HQL for every order line: quantity and document number of lines in DRAFT documents. */
  private static void loadDraftQuantities(java.util.Collection<String> orderLineIds,
      boolean salesTransaction, Map<String, BigDecimal> draftQty,
      Map<String, Set<String>> draftDocs) {
    // The IN list binds one parameter per order line; PostgreSQL caps a statement at 32767 bind
    // parameters, so an order with tens of thousands of lines would fail here. Not chunked: no
    // real order comes close.
    String hql = "select iol.salesOrderLine.id, iol.movementQuantity, io.documentNo "
        + "from MaterialMgmtShipmentInOutLine iol join iol.shipmentReceipt io "
        + "where io.documentStatus = 'DR' and io.salesTransaction = :sot "
        + "and iol.salesOrderLine.id in :ids order by io.creationDate";
    List<Object[]> rows = OBDal.getInstance().getSession().createQuery(hql, Object[].class)
        .setParameter("sot", salesTransaction)
        .setParameterList("ids", orderLineIds)
        .list();
    for (Object[] row : rows) {
      String id = (String) row[0];
      BigDecimal qty = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
      draftQty.merge(id, qty, BigDecimal::add);
      draftDocs.computeIfAbsent(id, k -> new LinkedHashSet<>()).add((String) row[2]);
    }
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
   * {@link #collectPendingLines(Order, boolean, Set)}.
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

  /**
   * Return value of {@link #resolvePendingLinesAndLocator}: the pending lines to create, plus the
   * default locator to use for stockable ones ({@code null} when none of them need a bin).
   */
  static final class PendingLinesResult {
    private final List<PendingOrderLine> pendingLines;
    private final Locator locator;

    private PendingLinesResult(List<PendingOrderLine> pendingLines, Locator locator) {
      this.pendingLines = pendingLines;
      this.locator = locator;
    }

    List<PendingOrderLine> getPendingLines() {
      return pendingLines;
    }

    Locator getLocator() {
      return locator;
    }
  }
}
