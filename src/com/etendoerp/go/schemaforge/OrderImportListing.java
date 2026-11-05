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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The pure pieces of {@link ImportOrderLinesIntoInvoiceHandler}'s listing actions, extracted
 * unchanged (ETP-5828, the handler was past the method count Sonar accepts): the sales-only
 * contract parameters of {@code availableOrders} / {@code availableOrderLines}, the
 * {@code limit} parsing, and the per-line row an {@code availableOrderLines} answer carries (price,
 * line discount, amount). No DAL access, no seams: the handler keeps everything a test overrides.
 */
final class OrderImportListing {

  private static final String PARAM_LIMIT = ImportOrderLinesIntoInvoiceHandler.PARAM_LIMIT;
  private static final String PARAM_INCLUDE_COVERED =
      ImportOrderLinesIntoInvoiceHandler.PARAM_INCLUDE_COVERED;
  private static final int MAX_LISTED_ORDERS = ImportOrderLinesIntoInvoiceHandler.MAX_LISTED_ORDERS;

  private OrderImportListing() {
  }

  /** {@code limit} on a sales invoice only (ETP-5828); a purchase invoice keeps no parameter. */
  static NeoActionContract.Param[] availableOrdersParams(boolean sales) {
    if (!sales) {
      return new NeoActionContract.Param[0];
    }
    return new NeoActionContract.Param[] {
        NeoActionContract.Param.optional(PARAM_LIMIT, NeoActionContract.TYPE_NUMBER,
            "How many orders to list at most, a whole number from 1 to " + MAX_LISTED_ORDERS
                + "; default " + OrderImportRequest.MAX_ORDERS
                + ". The scan looks at no fewer candidates than "
                + "that.") };
  }

  /** {@code orderId}, plus {@code includeCovered} on a sales invoice only (ETP-5828). */
  static NeoActionContract.Param[] availableLinesParams(boolean sales) {
    NeoActionContract.Param orderId = NeoActionContract.Param.required(
        OrderImportRequest.PARAM_ORDER_ID,
        NeoActionContract.TYPE_STRING, "Id of an order returned by availableOrders.");
    if (!sales) {
      return new NeoActionContract.Param[] { orderId };
    }
    return new NeoActionContract.Param[] { orderId,
        NeoActionContract.Param.optional(PARAM_INCLUDE_COVERED, NeoActionContract.TYPE_BOOLEAN,
            "Default false. true also lists the order's lines with nothing left to invoice "
                + "(already invoiced, or held by a draft invoice, this one included) with "
                + "pendingQuantity 0, as the import popup shows them greyed out; they cannot be "
                + "imported.") };
  }

  /**
   * {@code limit} of a sales {@code availableOrders}: absent is {@code defaultLimit}, anything but
   * a whole number from 1 to {@value #MAX_LISTED_ORDERS} a 400.
   */
  static int requestedLimit(JSONObject body, int defaultLimit) {
    Object value = body == null ? null : body.opt(PARAM_LIMIT);
    if (value == null || JSONObject.NULL.equals(value)) {
      return defaultLimit;
    }
    int limit;
    try {
      limit = new BigDecimal(String.valueOf(value).trim()).intValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      limit = 0;
    }
    if (limit < 1 || limit > MAX_LISTED_ORDERS) {
      throw new DocumentLineImport.ImportRefusal(javax.servlet.http.HttpServletResponse
          .SC_BAD_REQUEST, DocumentLineImport.ERROR_INVALID_PARAMETER, PARAM_LIMIT
          + " must be a whole number from 1 to " + MAX_LISTED_ORDERS, null);
    }
    return limit;
  }

  /** An active line other than the total-discount line: what the order's lines GET lists. */
  static boolean isListedLine(OrderLine ol) {
    return ol.isActive() && (ol.getProduct() == null
        || !TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(ol.getProduct().getId()));
  }

  static JSONObject availableLineRow(Order order, OrderLine ol, BigDecimal left)
      throws JSONException {
    JSONObject row = DocumentLineImport.lineRow(ol.getId(), ol.getLineNo(),
        ol.getProduct() == null ? null : ol.getProduct().getId(),
        ol.getProduct() == null ? null : ol.getProduct().getName(),
        ol.getUOM() == null ? null : ol.getUOM().getId(), left);
    // What the popup shows per line (ETP-5828, UI/MCP parity): price, line discount, amount.
    row.put(ImportOrderLinesIntoInvoiceHandler.KEY_UNIT_PRICE, unitPriceOf(ol));
    BigDecimal discount = ol.getDiscount() == null ? BigDecimal.ZERO : ol.getDiscount();
    row.put(ImportOrderLinesIntoInvoiceHandler.KEY_DISCOUNT, trimmed(discount));
    row.put(ImportOrderLinesIntoInvoiceHandler.KEY_AMOUNT, unitPriceOf(ol).multiply(left)
        .setScale(amountPrecision(order), RoundingMode.HALF_UP));
    return row;
  }

  static BigDecimal unitPriceOf(OrderLine ol) {
    return ol.getUnitPrice() == null ? BigDecimal.ZERO : ol.getUnitPrice();
  }

  /** Standard precision of the order's currency; 2 when it is unknown. */
  static int amountPrecision(Order order) {
    Currency currency = order.getCurrency();
    return currency == null || currency.getStandardPrecision() == null ? 2
        : currency.getStandardPrecision().intValue();
  }

  /** A % without trailing zeros nor exponent ({@code 10}, {@code 12.5}), sign kept. */
  static BigDecimal trimmed(BigDecimal value) {
    BigDecimal stripped = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
