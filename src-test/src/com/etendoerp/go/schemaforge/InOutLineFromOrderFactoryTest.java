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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link InOutLineFromOrderFactory#pendingQuantityFor(OrderLine)} and
 * {@link InOutLineFromOrderFactory#createAndLinkLine}.
 *
 * <p><b>ETP-4844:</b> when a Sales/Purchase Order carries a global discount, the
 * synthetic discount line materialized by {@link TotalDiscountService} (product id
 * {@link TotalDiscountService#DISCOUNT_PRODUCT_ID}) must never be carried over into
 * the generated Goods Shipment/Receipt — it never represents physical stock
 * movement. {@code pendingQuantityFor} skips it by explicit product ID.
 *
 * <p><b>ETP-5276:</b> a Service/Expense/Resource product (or any non-stockable Item)
 * is NOT excluded — it is a valid shipment/receipt line, exactly like classic
 * {@code M_INOUT_CREATE} treats it. A previous revision (ETP-4853) dropped every
 * non-stockable/non-Item line, which meant an order made up entirely of such lines
 * produced an empty, orphaned shipment/receipt instead of one with those lines
 * included. {@link InOutLineFromOrderFactory#isStockable} is what now decides
 * whether a line gets a real storage bin, not whether it is included at all.
 *
 * <p><b>ETP-4863:</b> {@code createAndLinkLine} receives the locator resolved from the ORDER's
 * warehouse ({@code CreateShipmentHandler} / {@code CreateGoodsReceiptHandler} both call
 * {@code findDefaultLocator(order)}), but the stock transaction follows the LINE's bin against
 * the DOCUMENT header's warehouse. Those two warehouses are normally the same, yet nothing in
 * the code guarantees it. This normalizes the line's bin to {@code parentInOut.getWarehouse()},
 * the single rule shared by every {@code M_InOutLine} write path in this module — but only for
 * stockable lines; a non-stockable line's bin stays {@code null} unconditionally (ETP-5276).
 */
public class InOutLineFromOrderFactoryTest {

  private static final String WH_PRINCIPAL = LocatorTestSupport.WH_PRINCIPAL;
  private static final String WH_SECONDARY = LocatorTestSupport.WH_SECONDARY;

  private static OrderLine mockOrderLine(boolean stocked, String productType,
      BigDecimal ordered, BigDecimal delivered) {
    Product product = mock(Product.class);
    when(product.isStocked()).thenReturn(stocked);
    when(product.getProductType()).thenReturn(productType);

    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.isActive()).thenReturn(true);
    when(orderLine.getProduct()).thenReturn(product);
    when(orderLine.getUOM()).thenReturn(mock(UOM.class));
    when(orderLine.getOrderedQuantity()).thenReturn(ordered);
    when(orderLine.getDeliveredQuantity()).thenReturn(delivered);
    return orderLine;
  }

  /** Existing behavior: a stockable Item-type product with pending qty is kept. */
  @Test
  public void testStockableItemProductWithPendingQuantityReturnsPendingQty() {
    OrderLine orderLine = mockOrderLine(true, "I", new BigDecimal("10"), new BigDecimal("4"));

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertEquals(new BigDecimal("6"), pending);
  }

  /**
   * ETP-5276 regression: a non-stockable product is a VALID shipment/receipt line —
   * only its storage bin, not its inclusion, depends on stockability. This used to
   * return {@code null} (ETP-4853), which is what produced empty, orphaned
   * shipments/receipts for orders made up entirely of such lines.
   */
  @Test
  public void testNonStockedProductWithPendingQuantityIsKept() {
    OrderLine orderLine = mockOrderLine(false, "I", new BigDecimal("-100"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertEquals(new BigDecimal("-100"), pending);
  }

  /**
   * ETP-5276 regression: a Service product (stocked flag irrelevant, ProductType='S')
   * must be kept — this is the exact case from the bug report (a Sales/Purchase Order
   * whose only line is a Service product must still produce a shipment/receipt line).
   */
  @Test
  public void testServiceProductReturnsPendingQuantity() {
    OrderLine orderLine = mockOrderLine(true, "S", new BigDecimal("5"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertEquals(new BigDecimal("5"), pending);
  }

  /** ETP-5276: an Expense product ('E') must also be kept. */
  @Test
  public void testExpenseProductReturnsPendingQuantity() {
    OrderLine orderLine = mockOrderLine(true, "E", new BigDecimal("2"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertEquals(new BigDecimal("2"), pending);
  }

  /** ETP-5276: a Resource product ('R') must also be kept. */
  @Test
  public void testResourceProductReturnsPendingQuantity() {
    OrderLine orderLine = mockOrderLine(true, "R", new BigDecimal("1"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertEquals(new BigDecimal("1"), pending);
  }

  /** ETP-4844 regression: the synthetic global-discount line is still excluded by ID. */
  @Test
  public void testDiscountProductIsSkippedByExplicitId() {
    OrderLine orderLine = mockOrderLine(false, "S", new BigDecimal("-3"), BigDecimal.ZERO);
    when(orderLine.getProduct().getId()).thenReturn(TotalDiscountService.DISCOUNT_PRODUCT_ID);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertNull("The synthetic discount line must never produce a shipment/receipt line", pending);
  }

  /** {@link InOutLineFromOrderFactory#isStockable} true only for IsStocked='Y' + ProductType='I'. */
  @Test
  public void testIsStockable() {
    Product stockableItem = mock(Product.class);
    when(stockableItem.isStocked()).thenReturn(true);
    when(stockableItem.getProductType()).thenReturn("I");
    org.junit.Assert.assertTrue(InOutLineFromOrderFactory.isStockable(stockableItem));

    Product service = mock(Product.class);
    when(service.isStocked()).thenReturn(true);
    when(service.getProductType()).thenReturn("S");
    org.junit.Assert.assertFalse(InOutLineFromOrderFactory.isStockable(service));

    Product notStocked = mock(Product.class);
    when(notStocked.isStocked()).thenReturn(false);
    when(notStocked.getProductType()).thenReturn("I");
    org.junit.Assert.assertFalse(InOutLineFromOrderFactory.isStockable(notStocked));

    org.junit.Assert.assertFalse(InOutLineFromOrderFactory.isStockable(null));
  }

  /** Existing edge case: an inactive order line is always skipped. */
  @Test
  public void testInactiveLineReturnsNull() {
    OrderLine orderLine = mockOrderLine(true, "I", new BigDecimal("10"), BigDecimal.ZERO);
    when(orderLine.isActive()).thenReturn(false);

    assertNull(InOutLineFromOrderFactory.pendingQuantityFor(orderLine));
  }

  /** Existing edge case: a missing product is always skipped. */
  @Test
  public void testMissingProductReturnsNull() {
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.isActive()).thenReturn(true);
    when(orderLine.getProduct()).thenReturn(null);
    when(orderLine.getUOM()).thenReturn(mock(UOM.class));

    assertNull(InOutLineFromOrderFactory.pendingQuantityFor(orderLine));
  }

  /** Existing edge case: a missing UOM is always skipped. */
  @Test
  public void testMissingUOMReturnsNull() {
    OrderLine orderLine = mockOrderLine(true, "I", new BigDecimal("10"), BigDecimal.ZERO);
    when(orderLine.getUOM()).thenReturn(null);

    assertNull(InOutLineFromOrderFactory.pendingQuantityFor(orderLine));
  }

  /** Existing edge case: a fully delivered line (pending == 0) is skipped. */
  @Test
  public void testFullyDeliveredLineReturnsNull() {
    OrderLine orderLine = mockOrderLine(true, "I", new BigDecimal("10"), new BigDecimal("10"));

    assertNull(InOutLineFromOrderFactory.pendingQuantityFor(orderLine));
  }

  /**
   * ETP-4722 regression: a negative pending quantity (return-style line) must
   * still be kept — only an exact-zero pending quantity is skipped.
   */
  @Test
  public void testNegativePendingQuantityIsKept() {
    OrderLine orderLine = mockOrderLine(true, "I", new BigDecimal("-5"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertEquals(new BigDecimal("-5"), pending);
  }

  /**
   * Unit tests for {@link InOutLineFromOrderFactory#resolvePendingLinesAndLocator}, the
   * validate-before-mutate step extracted out of {@code CreateShipmentHandler} and
   * {@code CreateGoodsReceiptHandler} (ETP-5276 Sonar duplication follow-up) so both handlers
   * share one copy instead of two near-identical ones.
   */
  private static Order mockOrder(OrderLine... lines) {
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(java.util.Arrays.asList(lines));
    return order;
  }

  @Test
  public void resolvePendingLinesAndLocator_noPendingLines_throwsGivenMessage() {
    Order order = mockOrder();
    try {
      InOutLineFromOrderFactory.resolvePendingLinesAndLocator(
          order, "custom empty-lines message", o -> mock(Locator.class));
      org.junit.Assert.fail("expected OBException");
    } catch (org.openbravo.base.exception.OBException e) {
      assertEquals("custom empty-lines message", e.getMessage());
    }
  }

  @Test
  public void resolvePendingLinesAndLocator_stockableLine_resolvesAndReturnsLocator() {
    OrderLine stockLine = mockOrderLine(true, "I", new BigDecimal("10"), BigDecimal.ZERO);
    Order order = mockOrder(stockLine);
    Locator resolved = mock(Locator.class);

    InOutLineFromOrderFactory.PendingLinesResult result =
        InOutLineFromOrderFactory.resolvePendingLinesAndLocator(
            order, "unused", o -> resolved);

    assertEquals(1, result.getPendingLines().size());
    org.junit.Assert.assertSame(resolved, result.getLocator());
  }

  @Test
  public void resolvePendingLinesAndLocator_stockableLineAndNoLocator_throws() {
    OrderLine stockLine = mockOrderLine(true, "I", new BigDecimal("10"), BigDecimal.ZERO);
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getName()).thenReturn("WH Central");
    Order order = mockOrder(stockLine);
    when(order.getWarehouse()).thenReturn(warehouse);

    try {
      InOutLineFromOrderFactory.resolvePendingLinesAndLocator(order, "unused", o -> null);
      org.junit.Assert.fail("expected OBException");
    } catch (org.openbravo.base.exception.OBException e) {
      org.junit.Assert.assertTrue(e.getMessage().contains("WH Central"));
    }
  }

  /**
   * ETP-5276: an order made up entirely of non-stockable lines must not even ask for a locator —
   * the resolver callback must never run.
   */
  @Test
  public void resolvePendingLinesAndLocator_onlyNonStockableLines_neverResolvesLocator() {
    OrderLine serviceLine = mockOrderLine(true, "S", new BigDecimal("5"), BigDecimal.ZERO);
    Order order = mockOrder(serviceLine);
    java.util.concurrent.atomic.AtomicBoolean resolverCalled = new java.util.concurrent.atomic.AtomicBoolean(false);

    InOutLineFromOrderFactory.PendingLinesResult result =
        InOutLineFromOrderFactory.resolvePendingLinesAndLocator(order, "unused", o -> {
          resolverCalled.set(true);
          return mock(Locator.class);
        });

    assertEquals(1, result.getPendingLines().size());
    assertNull(result.getLocator());
    org.junit.Assert.assertFalse("locatorResolver must not run when no line is stockable",
        resolverCalled.get());
  }

  /**
   * Stubs the native-query chain {@code InvoiceLineLinker.linkPendingInvoiceLinesToInout} runs at
   * the tail of {@code createAndLinkLine}, so the test exercises the locator logic instead of
   * dying on an unstubbed session.
   */
  @SuppressWarnings("unchecked")
  private static void stubInvoiceLineLinker(OBDal dal) {
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(0);
  }

  /**
   * A generic order line with a stockable product ({@code IsStocked='Y'},
   * {@code ProductType='I'}) — the ordinary case for the {@code createAndLinkLine}
   * anchoring tests below, which exercise the locator cascade, not stockability.
   * See {@link #mockOrderLine(String)} for the non-stockable variant.
   */
  private static OrderLine mockOrderLine() {
    return mockOrderLine("I");
  }

  private static OrderLine mockOrderLine(String productType) {
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getId()).thenReturn("ordln-1");
    Product product = mock(Product.class);
    when(product.isStocked()).thenReturn(true);
    when(product.getProductType()).thenReturn(productType);
    when(orderLine.getProduct()).thenReturn(product);
    return orderLine;
  }

  /**
   * The caller-supplied locator belongs to another warehouse than the shipment/receipt header:
   * it must be replaced by the header warehouse's own default bin, never persisted as-is.
   */
  @Test
  public void createAndLinkLine_locatorFromAnotherWarehouse_anchorsToParentWarehouse() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-secondary-A", LocatorTestSupport.mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = LocatorTestSupport.mockLocator("loc-principal-default", headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, headerDefaultBin);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line, never()).setStorageBin(orderLocator);
      verify(line).setStorageBin(headerDefaultBin);
      verify(dal).save(line);
    }
  }

  /**
   * The caller-supplied locator already belongs to the header's warehouse — a legitimate choice
   * that must survive untouched, with no extra lookup issued.
   */
  @Test
  public void createAndLinkLine_locatorInParentWarehouse_isKept() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-principal-A", headerWarehouse);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line).setStorageBin(orderLocator);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }

  /**
   * Cascade step 4 at this call site: the header's warehouse has no active locator, so the
   * order's foreign locator must NOT be persisted — the line gets a null bin and the document
   * fails loudly at posting. Uniform with {@code assignBinsToLines} and
   * {@code createReturnLineShell}.
   */
  @Test
  public void createAndLinkLine_headerWarehouseHasNoLocator_doesNotKeepForeignLocator() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(WH_SECONDARY));
      LocatorTestSupport.stubLocatorCascade(dal, null);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line, never()).setStorageBin(orderLocator);
      verify(line).setStorageBin(null);
    }
  }

  /**
   * QA edge case: the parent shipment/receipt has NO warehouse set at all (e.g. malformed
   * document). There is nothing to anchor to, so the order-resolved locator must be kept
   * unmodified and no {@code M_Locator} lookup issued — same null-warehouse guard verified at
   * {@code NeoHandlerUtils.anchorLocatorToWarehouse} level, pinned here at the call site too.
   */
  @Test
  public void createAndLinkLine_parentWarehouseIsNull_keepsOrderLocatorUnanchored() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Locator orderLocator = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(WH_SECONDARY));

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(null);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line).setStorageBin(orderLocator);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }

  /**
   * ETP-5276: a non-stockable line (e.g. Service product) must get a {@code null} storage bin
   * unconditionally, WITHOUT going through the anchoring cascade at all — even though the header
   * warehouse has a perfectly good default locator and the caller passed a non-null candidate.
   * {@code anchorLocatorToWarehouse} resolves a fallback bin whenever its candidate is
   * {@code null}, so this line's bin must never be routed through it; if it were, this line would
   * incorrectly end up with a real bin.
   */
  @Test
  public void createAndLinkLine_nonStockableProduct_setsNullBinWithoutAnchoring() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-principal-A", headerWarehouse);
      Locator headerDefaultBin = LocatorTestSupport.mockLocator("loc-principal-default", headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, headerDefaultBin);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine("S"), orderLocator, 10L, BigDecimal.ONE);

      verify(line).setStorageBin(null);
      verify(line, never()).setStorageBin(orderLocator);
      verify(line, never()).setStorageBin(headerDefaultBin);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }
}