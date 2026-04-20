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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * NeoHandler for order line entities (Sales Order, Purchase Order, Sales Quotation).
 *
 * <p>Covers two price-list scenarios on both POST (create) and PATCH (edit):
 *
 * <p><b>Tax-included price lists</b> ({@code istaxincluded = 'Y'}, PATCH only): the DB
 * trigger {@code c_orderline_trg} always derives {@code priceActual} from
 * {@code gross_unit_price}. On direct edits (PATCH) the frontend sends only the net
 * {@code unitPrice}, so we recompute {@code grossUnitPrice = unitPrice * (1 + taxRate)}
 * and flush so the trigger recalculates correctly. On POST the addLine callout chain
 * already filled {@code grossUnitPrice} before submit, so only a refresh is needed.
 *
 * <p><b>Net-price lists</b> ({@code istaxincluded = 'N'}): the trigger sets
 * {@code gross_unit_price = priceActual} and {@code line_gross_amount = qty * gross_unit_price},
 * but Hibernate's first-level cache still holds the pre-trigger zeros. A simple
 * {@code refresh()} re-reads the trigger-computed values so NEO serializes them
 * in the response.
 *
 * <p>Registered via {@code javaQualifier = "orderLineHandler"} on the lines
 * entity of sales-order, purchase-order and sales-quotation specs.
 */
@Named("orderLineHandler")
public class OrderLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(OrderLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!"PATCH".equals(method) && !"POST".equals(method)) {
      return null;
    }

    JSONObject body = context.getRequestBody();
    if (!body.has("unitPrice")) {
      return null;
    }

    String recordId = context.getRecordId();
    if (recordId == null) {
      return null;
    }

    OrderLine line = OBDal.getInstance().get(OrderLine.class, recordId);
    if (line == null) {
      return null;
    }

    Order order = line.getSalesOrder();
    if (order == null || order.getPriceList() == null) {
      return null;
    }

    boolean taxIncluded = Boolean.TRUE.equals(order.getPriceList().isPriceIncludesTax());

    if (!taxIncluded) {
      // For net-price lists the trigger already set grossUnitPrice = priceActual and
      // lineGrossAmount = qty * grossUnitPrice, but the Hibernate cache still holds
      // pre-trigger zeros. Refresh so NEO returns the correct values.
      OBDal.getInstance().refresh(line);
      return null;
    }

    // Tax-included path only applies to PATCH (direct field edit without callout).
    // On POST the addLine callout chain already filled grossUnitPrice before submit,
    // so the trigger computed priceActual correctly — no recomputation needed.
    if ("POST".equals(method)) {
      OBDal.getInstance().refresh(line);
      return null;
    }

    // Recompute grossUnitPrice from the intended net price (PATCH only).
    BigDecimal sentUnitPrice;
    try {
      sentUnitPrice = new BigDecimal(body.getString("unitPrice"));
    } catch (Exception e) {
      log.warn("[OrderLineHandler] Could not parse unitPrice from body, skipping", e);
      return null;
    }

    // Refresh to read the trigger-computed priceActual within the same transaction.
    OBDal.getInstance().refresh(line);
    BigDecimal dbUnitPrice = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;

    // If the trigger already produced the correct value, nothing to do.
    if (sentUnitPrice.setScale(2, RoundingMode.HALF_UP)
        .compareTo(dbUnitPrice.setScale(2, RoundingMode.HALF_UP)) == 0) {
      return null;
    }

    // Recompute: grossUnitPrice = netUnitPrice * (1 + taxRate / 100).
    TaxRate tax = line.getTax();
    if (tax == null || tax.getRate() == null) {
      log.warn("[OrderLineHandler] No tax or tax rate on line {}, cannot fix gross price", recordId);
      return null;
    }

    BigDecimal rate = tax.getRate();
    BigDecimal gross = sentUnitPrice
        .multiply(BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)))
        .setScale(6, RoundingMode.HALF_UP);

    log.debug("[OrderLineHandler] Fixing grossUnitPrice on line {}: {} -> {} (net={}, rate={})",
        recordId, line.getGrossUnitPrice(), gross, sentUnitPrice, rate);

    line.setGrossUnitPrice(gross);
    OBDal.getInstance().flush();

    return null;
  }
}
