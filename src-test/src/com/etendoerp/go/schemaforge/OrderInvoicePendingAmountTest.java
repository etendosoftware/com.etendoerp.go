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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * The amount "Generar factura" would invoice now (ETP-5828): priced like the generated invoice —
 * line net at the currency precision, the global discount % per tax at 2 decimals
 * ({@link TotalDiscountService}), tax per tax on the discounted base.
 *
 * <p>New class: it is the unit test of the new {@link OrderInvoicePendingAmount}; the quantity
 * rule it is fed with is covered where it lives ({@code ImportOrderLinesIntoInvoiceHandlerTest}
 * {@code #shipmentScopedPending_*}) and the annotation in {@code OrderPendingDocsAnnotationTest}.</p>
 *
 * @covers com.etendoerp.go.schemaforge.OrderInvoicePendingAmount
 */
public class OrderInvoicePendingAmountTest {

  private static OrderInvoicePendingAmount.Part part(String tax, String net, String rate) {
    return new OrderInvoicePendingAmount.Part(tax, new BigDecimal(net), new BigDecimal(rate));
  }

  /**
   * Two taxes: 21 % on 10.00 + 20.00 and 10 % on 15.55. No discount: 30.00 + 6.30 + 15.55 + 1.56
   * = 53.41. At 10 %: 30.00 − 3.00 = 27.00 + 5.67, and 15.55 − 1.56 (1.555 rounded, as the
   * discount line) = 13.99 + 1.40 → 48.06. Nothing pending: 0.00.
   */
  @Test
  public void total_discountsPerTaxAtTwoDecimalsThenTaxesTheDiscountedBase() {
    List<OrderInvoicePendingAmount.Part> parts = List.of(part("A", "10.00", "21"),
        part("A", "20.00", "21"), part("B", "15.55", "10"));
    Object[][] cases = {
        // name, parts, discount %, expected
        { "no global discount", parts, null, "53.41" },
        { "a zero discount is none", parts, BigDecimal.ZERO, "53.41" },
        { "10 % global discount", parts, new BigDecimal("10"), "48.06" },
        { "nothing pending", List.of(), new BigDecimal("10"), "0.00" },
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      List<OrderInvoicePendingAmount.Part> given = (List<OrderInvoicePendingAmount.Part>) c[1];
      assertEquals((String) c[0], new BigDecimal((String) c[3]),
          OrderInvoicePendingAmount.total(given, (BigDecimal) c[2], 2));
    }
  }

  /**
   * forOrder prices only the lines the action would invoice (a line absent from the quantities,
   * or at 0, is not counted), at the order line's net unit price and tax, with the order's %:
   * 2 × 9.995 = 19.99, − 2.00 = 17.99, + 3.78 = 21.77. A partial draft or a shipped-only line
   * arrives here as a smaller quantity.
   */
  @Test
  public void forOrder_pricesTheActionsQuantitiesWithTheOrderDiscount() {
    TaxRate tax = mock(TaxRate.class);
    when(tax.getId()).thenReturn("tax-21");
    when(tax.getRate()).thenReturn(new BigDecimal("21"));
    OrderLine invoiced = line("l1", "9.995", tax);
    OrderLine notPending = line("l2", "50", tax);
    OrderLine zero = line("l3", "50", tax);
    Currency currency = mock(Currency.class);
    when(currency.getStandardPrecision()).thenReturn(2L);
    Order order = mock(Order.class);
    when(order.getCurrency()).thenReturn(currency);
    when(order.getEtgoTotalDiscount()).thenReturn(new BigDecimal("10"));
    when(order.getOrderLineList()).thenReturn(List.of(invoiced, notPending, zero));

    assertEquals(new BigDecimal("21.77"), OrderInvoicePendingAmount.forOrder(order,
        Map.of("l1", new BigDecimal("2"), "l3", BigDecimal.ZERO)));
    assertEquals("nothing pending", new BigDecimal("0.00"),
        OrderInvoicePendingAmount.forOrder(order, Map.of()));
  }

  private static OrderLine line(String id, String unitPrice, TaxRate tax) {
    OrderLine line = mock(OrderLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getUnitPrice()).thenReturn(new BigDecimal(unitPrice));
    when(line.getTax()).thenReturn(tax);
    return line;
  }
}
