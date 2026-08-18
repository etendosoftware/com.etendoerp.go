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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.Test;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;

/**
 * Unit tests for {@link InOutLineFromOrderFactory#pendingQuantityFor(OrderLine)}.
 *
 * <p><b>ETP-4853:</b> when a Sales/Purchase Order carries a global discount, the
 * synthetic discount line materialized by {@link TotalDiscountService} (a
 * non-stockable, non-Item dummy product) must never be carried over into the
 * generated Goods Shipment/Receipt — it never represents physical stock
 * movement. {@code pendingQuantityFor} must skip (return {@code null}) any
 * order line whose product is not stockable and of type Item, mirroring the
 * discriminator the classic {@code M_INOUT_CREATE} stored procedure uses
 * ({@code IsStocked='Y' AND ProductType='I'}).
 */
public class InOutLineFromOrderFactoryTest {

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
   * ETP-4853 regression: a non-stockable product (e.g. the synthetic global-discount
   * line, {@code ETGO_DTO}) must be skipped even though it has a nonzero ordered
   * quantity and nothing has been "delivered" against it.
   */
  @Test
  public void testNonStockedProductWithPendingQuantityReturnsNull() {
    OrderLine orderLine = mockOrderLine(false, "I", new BigDecimal("-100"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertNull("A non-stockable product must never produce a shipment/receipt line", pending);
  }

  /**
   * ETP-4853 regression: a product that is stocked but not of type Item (e.g. a
   * Service or Expense product) must also be skipped — only stockable Items
   * represent real stock movement.
   */
  @Test
  public void testStockedButNonItemProductTypeReturnsNull() {
    OrderLine orderLine = mockOrderLine(true, "S", new BigDecimal("5"), BigDecimal.ZERO);

    BigDecimal pending = InOutLineFromOrderFactory.pendingQuantityFor(orderLine);

    assertNull("A non-Item product type must never produce a shipment/receipt line", pending);
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
}
