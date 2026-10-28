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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;

/**
 * The global discount of {@code importOrderLines} into a draft invoice (ETP-5828): whether the
 * selected orders' {@code etgoTotalDiscount} (always a percentage) can be carried to the invoice's
 * total discount, the 422 that asks the caller when it cannot, and setting it. Used only by
 * {@link ImportOrderLinesIntoInvoiceHandler}; it mirrors the window's "Add from order" popup
 * ({@code resolveOrderGlobalDiscount} in {@code orderGlobalDiscountRule.js}).
 */
final class InvoiceGlobalDiscountRule {

  /** The orders' global discount cannot be carried to the invoice; nothing was imported. */
  static final String ERROR_GLOBAL_DISCOUNT_CONFLICT = "globalDiscountConflict";
  /** Conflict (a): the selected orders do not share one % (0 counts as different). */
  static final String CONFLICT_ORDERS_DIFFER = "ordersDiffer";
  /** Conflict (b): the invoice already has a total discount of another %. */
  static final String CONFLICT_INVOICE_OTHER_DISCOUNT = "invoiceHasOtherDiscount";
  /** Conflict (c): the invoice already has lines and no total discount. */
  static final String CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT = "invoiceHasLinesWithoutDiscount";

  private InvoiceGlobalDiscountRule() {
  }

  /**
   * The % to set on the invoice's total discount, or {@code null} when there is nothing to carry
   * (the selected orders have no global discount). Judged on the invoice as it is BEFORE the
   * call's lines exist.
   *
   * @throws DocumentLineImport.ImportRefusal 422 {@value #ERROR_GLOBAL_DISCOUNT_CONFLICT} when the
   *         popup would warn instead of offering the carry
   */
  static BigDecimal toCarry(Invoice invoice, List<Order> orders) throws JSONException {
    List<BigDecimal> discounts = orderDiscounts(orders);
    boolean hadLines = hasProductLines(invoice);
    String conflict = discountConflict(discounts, invoice.getEtgoTotalDiscount(), hadLines);
    if (conflict != null) {
      throw discountConflictRefusal(conflict, orders, invoice.getEtgoTotalDiscount(), hadLines);
    }
    return firstPositive(discounts);
  }

  /**
   * Which popup warning applies, or {@code null} when there is none. Mirrors
   * {@code resolveOrderGlobalDiscount} ({@code orderGlobalDiscountRule.js}) step by step, every %
   * normalised by {@link #normalizedPercent} (null, zero or negative as 0):
   * <ol>
   *   <li>the orders do not all have the same % (0 counts as a %) →
   *       {@value #CONFLICT_ORDERS_DIFFER};</li>
   *   <li>orders and invoice all at 0 → {@code null}: nothing to carry, whatever the lines;</li>
   *   <li>the invoice has a % other than the orders' one, orders at 0 included →
   *       {@value #CONFLICT_INVOICE_OTHER_DISCOUNT};</li>
   *   <li>the invoice has no % but already had lines →
   *       {@value #CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT}: carrying the orders' % would discount
   *       those lines too;</li>
   *   <li>otherwise {@code null}: the orders' % is carried (or the invoice already holds it).</li>
   * </ol>
   *
   * @param orderDiscounts  the global discount of each selected order, {@code null} as 0
   * @param invoiceDiscount the invoice's total discount before the call, {@code null} as 0
   * @param hadLines        whether the invoice had lines (other than the discount line) before
   */
  static String discountConflict(List<BigDecimal> orderDiscounts, BigDecimal invoiceDiscount,
      boolean hadLines) {
    BigDecimal common = commonPercent(orderDiscounts);
    if (common == null) {
      return CONFLICT_ORDERS_DIFFER;
    }
    BigDecimal current = normalizedPercent(invoiceDiscount);
    if (common.signum() == 0 && current.signum() == 0) {
      return null;
    }
    if (current.signum() > 0 && current.compareTo(common) != 0) {
      return CONFLICT_INVOICE_OTHER_DISCOUNT;
    }
    return current.signum() == 0 && hadLines ? CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT : null;
  }

  /** As the popup's {@code normalizeGlobalDiscountPct}: null or not positive is 0, 4 decimals. */
  static BigDecimal normalizedPercent(BigDecimal value) {
    return value == null || value.signum() <= 0 ? BigDecimal.ZERO
        : value.setScale(4, RoundingMode.HALF_UP);
  }

  /** Whether the invoice holds an active line other than the total-discount line. */
  static boolean hasProductLines(Invoice invoice) {
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (!Boolean.FALSE.equals(line.isActive()) && (line.getProduct() == null
          || !TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(line.getProduct().getId()))) {
        return true;
      }
    }
    return false;
  }

  /** Sets the carried % on the header; {@code null} leaves the header untouched. */
  static void apply(Invoice invoice, BigDecimal discount) {
    if (discount == null) {
      return;
    }
    invoice.setEtgoTotalDiscount(discount);
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
  }

  /**
   * The 422 the caller turns into a question to the user: each order's %, the invoice's state
   * before the call, the case, and how to import anyway.
   */
  static DocumentLineImport.ImportRefusal discountConflictRefusal(String conflict,
      List<Order> selected, BigDecimal invoiceDiscount, boolean hadLines) throws JSONException {
    JSONArray orders = new JSONArray();
    for (Order order : selected) {
      orders.put(new JSONObject().put(OrderImportRequest.PARAM_ORDER_ID, order.getId())
          .put("documentNo", order.getDocumentNo())
          .put("globalDiscount", orZero(order.getEtgoTotalDiscount())));
    }
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("conflict", conflict);
    extra.put(OrderImportRequest.PARAM_ORDERS, orders);
    extra.put("invoice", new JSONObject().put("totalDiscount", orZero(invoiceDiscount))
        .put("hadLines", hadLines));
    extra.put("hint", "Nothing was imported. Tell the user this conflict and ask whether to import "
        + "anyway. Only if they agree, resend the same call with "
        + OrderImportRequest.PARAM_CARRY_DISCOUNT
        + ":false: every line keeps its own line discount, no order's global discount is carried "
        + "and the invoice's total discount is left unchanged (an existing one also applies to "
        + "the imported lines).");
    return new DocumentLineImport.ImportRefusal(DocumentLineImport.SC_UNPROCESSABLE,
        ERROR_GLOBAL_DISCOUNT_CONFLICT, conflictMessage(conflict, selected, invoiceDiscount),
        extra);
  }

  private static String conflictMessage(String conflict, List<Order> selected,
      BigDecimal invoiceDiscount) {
    if (CONFLICT_ORDERS_DIFFER.equals(conflict)) {
      return "The selected orders have different global discounts (0% counts as different): "
          + "no single total discount can be carried to the invoice";
    }
    BigDecimal common = commonPercent(orderDiscounts(selected));
    if (CONFLICT_INVOICE_OTHER_DISCOUNT.equals(conflict)) {
      String orders = common == null || common.signum() == 0
          ? "the selected orders have no global discount"
          : "different from the orders' " + percent(common);
      return "The invoice already has a total discount of " + percent(invoiceDiscount) + ", "
          + orders + ": it is kept and also applies to the imported lines";
    }
    return "The invoice already has lines and no total discount: carrying the orders' "
        + percent(common) + " would discount those lines too";
  }

  /** The normalised % every order shares (0 for none or no orders), {@code null} if mixed. */
  private static BigDecimal commonPercent(List<BigDecimal> values) {
    BigDecimal common = null;
    for (BigDecimal value : values) {
      BigDecimal pct = normalizedPercent(value);
      if (common == null) {
        common = pct;
      } else if (common.compareTo(pct) != 0) {
        return null;
      }
    }
    return common == null ? BigDecimal.ZERO : common;
  }

  private static List<BigDecimal> orderDiscounts(List<Order> orders) {
    List<BigDecimal> discounts = new ArrayList<>();
    for (Order order : orders) {
      discounts.add(order.getEtgoTotalDiscount());
    }
    return discounts;
  }

  private static BigDecimal firstPositive(List<BigDecimal> values) {
    for (BigDecimal value : values) {
      if (value != null && value.signum() > 0) {
        return value;
      }
    }
    return null;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String percent(BigDecimal value) {
    BigDecimal stripped = orZero(value).stripTrailingZeros();
    return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString() + "%";
  }
}
