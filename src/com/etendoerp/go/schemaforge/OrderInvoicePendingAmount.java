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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * The gross amount "Generar factura" on a sales order WOULD invoice now (ETP-5828), for the
 * manage-documents modal's «X € pendientes de facturar»: the quantities are the action's own
 * ({@link ImportOrderLinesIntoInvoiceHandler#shipmentScopedPending}, net of the draft invoices and
 * shipment-scoped — no second rule), priced the way the generated invoice is:
 *
 * <ol>
 *   <li>line net = quantity × the order line's unit price (its line discount already applied), at
 *   the currency's precision, as the native line copy does;</li>
 *   <li>per tax of the order lines, the order's global discount % taken off that tax's net subtotal
 *   at 2 decimals — exactly how {@link TotalDiscountService#recalculate} builds the invoice's
 *   discount lines at completion;</li>
 *   <li>tax per tax on the discounted base, at the currency's precision (one tax line per rate, as
 *   {@code C_InvoiceTax}), and the total = discounted bases + taxes.</li>
 * </ol>
 *
 * <p>Known limits, all ± rounding or configuration the generated invoice resolves itself: a price
 * list with prices including tax (the invoice derives its net from the gross price, here the net
 * unit price is used), summary-level or cascading taxes (the rate of the line's tax is applied as
 * is), and a shipment line the native copy would take whole where the pending quantity is
 * smaller. Pure arithmetic in {@link #total}; nothing here selects an entity.</p>
 */
final class OrderInvoicePendingAmount {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  /** The scale {@link TotalDiscountService} rounds each discount line to. */
  private static final int DISCOUNT_SCALE = 2;
  private static final int DEFAULT_PRECISION = 2;

  /** One invoiceable slice: its net amount and the rate (%) of its tax. */
  record Part(String taxId, BigDecimal net, BigDecimal ratePct) { }

  private OrderInvoicePendingAmount() {
  }

  /**
   * The amount for {@code order} and the per-line quantities the action would invoice.
   *
   * @param order      the sales order
   * @param quantities {@code orderLineId -> quantity}, from {@code shipmentScopedPending}
   * @return the gross amount, at the currency's precision; zero when nothing would be invoiced
   */
  static BigDecimal forOrder(Order order, Map<String, BigDecimal> quantities) {
    int precision = precisionOf(order.getCurrency());
    List<Part> parts = new ArrayList<>();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal quantity = quantities.get(ol.getId());
      if (quantity == null || quantity.signum() == 0) {
        continue;
      }
      BigDecimal price = ol.getUnitPrice() == null ? BigDecimal.ZERO : ol.getUnitPrice();
      TaxRate tax = ol.getTax();
      parts.add(new Part(tax == null ? null : tax.getId(),
          quantity.multiply(price).setScale(precision, RoundingMode.HALF_UP),
          tax == null || tax.getRate() == null ? BigDecimal.ZERO : tax.getRate()));
    }
    return total(parts, order.getEtgoTotalDiscount(), precision);
  }

  /**
   * The arithmetic of the class javadoc over already-priced parts.
   *
   * @param parts       the line nets with their tax
   * @param discountPct the global discount %; {@code null} or not positive means none
   * @param precision   the currency's standard precision
   * @return discounted bases + taxes, at {@code precision}
   */
  static BigDecimal total(List<Part> parts, BigDecimal discountPct, int precision) {
    Map<String, BigDecimal> netByTax = new LinkedHashMap<>();
    Map<String, BigDecimal> rateByTax = new LinkedHashMap<>();
    for (Part part : parts) {
      String key = String.valueOf(part.taxId());
      netByTax.merge(key, part.net(), BigDecimal::add);
      rateByTax.putIfAbsent(key, part.ratePct());
    }
    boolean discounted = discountPct != null && discountPct.signum() > 0;
    BigDecimal total = BigDecimal.ZERO;
    for (Map.Entry<String, BigDecimal> entry : netByTax.entrySet()) {
      BigDecimal base = entry.getValue();
      if (discounted) {
        base = base.subtract(base.multiply(discountPct)
            .divide(HUNDRED, DISCOUNT_SCALE, RoundingMode.HALF_UP));
      }
      BigDecimal tax = base.multiply(rateByTax.get(entry.getKey()))
          .divide(HUNDRED, precision, RoundingMode.HALF_UP);
      total = total.add(base).add(tax);
    }
    return total.setScale(precision, RoundingMode.HALF_UP);
  }

  private static int precisionOf(Currency currency) {
    return currency == null || currency.getStandardPrecision() == null ? DEFAULT_PRECISION
        : currency.getStandardPrecision().intValue();
  }
}
