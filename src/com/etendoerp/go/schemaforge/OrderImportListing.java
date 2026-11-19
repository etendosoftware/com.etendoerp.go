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
 * unchanged (ETP-5828, the handler was past the method count Sonar accepts): the contract
 * parameters of {@code availableOrders} / {@code availableOrderLines} (shared listing parameters
 * of {@link ImportListing} since ETP-6099), and the per-line row an {@code availableOrderLines}
 * answer carries (price, line discount, amount, and the {@code detail} keys). No DAL access, no seams: the handler keeps everything a test overrides.
 */
final class OrderImportListing {

  private OrderImportListing() {
  }

  /** What {@code detail} adds to an {@code availableOrderLines} row (ETP-6099). */
  static final String LINE_DETAIL_KEYS = "listPrice, grossUnitPrice, tax, description, "
      + "orderedQuantity and invoicedQuantity of the order line";

  /**
   * {@code limit}, {@code offset}, {@code includeCovered} on both directions: ETP-5828 gave the
   * sales invoice {@code limit}, ETP-6099 the rest, and the purchase invoice the same set (its
   * popup lists through the action now).
   */
  static NeoActionContract.Param[] availableOrdersParams() {
    return ImportListing.listParams(OrderImportRequest.MAX_ORDERS, "creationDate");
  }

  /** {@code orderId}, {@code includeCovered} and {@code detail}, on both directions (ETP-6099). */
  static NeoActionContract.Param[] availableLinesParams() {
    NeoActionContract.Param orderId = NeoActionContract.Param.required(
        OrderImportRequest.PARAM_ORDER_ID,
        NeoActionContract.TYPE_STRING, "Id of an order returned by availableOrders.");
    return ImportListing.linesParams(LINE_DETAIL_KEYS, orderId);
  }

  /** The keys {@code detail} adds to an {@code availableOrderLines} row (ETP-6099). */
  static void putLineDetail(JSONObject row, OrderLine ol) throws JSONException {
    ImportListing.putNullable(row, "listPrice", ol.getListPrice());
    ImportListing.putNullable(row, "grossUnitPrice", ol.getGrossUnitPrice());
    ImportListing.putNullable(row, "tax", ol.getTax() == null ? null : ol.getTax().getId());
    ImportListing.putNullable(row, "description", ol.getDescription());
    ImportListing.putNullable(row, "orderedQuantity", ol.getOrderedQuantity());
    ImportListing.putNullable(row, "invoicedQuantity", ol.getInvoicedQuantity());
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
