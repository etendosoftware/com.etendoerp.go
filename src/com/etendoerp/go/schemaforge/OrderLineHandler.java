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
 * <p>Fixes price editing on tax-included price lists (istaxincluded = 'Y').
 * For those price lists the DB trigger {@code c_orderline_trg} always derives
 * {@code priceActual} from {@code gross_unit_price}, so patching only
 * {@code priceActual} has no lasting effect. After the default CRUD runs we
 * check whether the trigger produced the intended net price; if not, we compute
 * the correct {@code grossUnitPrice} and flush it so the trigger recalculates
 * correctly within the same transaction.
 *
 * <p>Registered via {@code javaQualifier = "orderLineHandler"} on the lines
 * entity of sales-order, purchase-order and sales-quotation specs.
 */
@Named("orderLineHandler")
public class OrderLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(OrderLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    if (!"POST".equals(context.getHttpMethod())) {
      return null;
    }

    JSONObject body = context.getRequestBody();
    if (body.optDouble("grossUnitPrice", -1) <= 0) {
      return null;
    }

    // parentId is injected by NeoCrudHandler from the request body before this hook runs.
    String orderId = body.optString("parentId", null);
    if (orderId == null || orderId.isEmpty()) {
      return null;
    }

    Order order = OBDal.getInstance().get(Order.class, orderId);
    if (order == null || order.getPriceList() == null) {
      return null;
    }

    // For net price lists (istaxincluded=N) the frontend selector incorrectly maps
    // standardPrice → grossUnitPrice (standardPrice is the net price, not gross).
    // Reset grossUnitPrice to 0 before the CRUD runs so the backend callout chain
    // does not treat it as a gross price and NeoDefaultsService takes the net path:
    // lineGrossAmount = unitPrice × qty × (1 + taxRate).
    if (!Boolean.TRUE.equals(order.getPriceList().isPriceIncludesTax())) {
      try {
        body.put("grossUnitPrice", 0);
        log.debug("[OrderLineHandler] Net price list '{}' — reset grossUnitPrice to 0 on new line",
            order.getPriceList().getIdentifier());
      } catch (Exception e) {
        log.warn("[OrderLineHandler] Could not reset grossUnitPrice: {}", e.getMessage());
      }
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    if (!"PATCH".equals(context.getHttpMethod())) {
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
    if (order == null || order.getPriceList() == null
        || !Boolean.TRUE.equals(order.getPriceList().isPriceIncludesTax())) {
      return null;
    }

    BigDecimal sentUnitPrice;
    try {
      sentUnitPrice = new BigDecimal(body.getString("unitPrice"));
    } catch (Exception e) {
      log.warn("[OrderLineHandler] Could not parse unitPrice from body, skipping", e);
      return null;
    }

    // Refresh from DB to read the trigger-computed unitPrice after the first flush.
    // Within the same transaction PostgreSQL makes our own DML visible via SELECT,
    // so refresh() returns the trigger-updated value.
    OBDal.getInstance().refresh(line);
    BigDecimal dbUnitPrice = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;

    // If the trigger already produced the correct value, nothing to do.
    if (sentUnitPrice.setScale(2, RoundingMode.HALF_UP)
        .compareTo(dbUnitPrice.setScale(2, RoundingMode.HALF_UP)) == 0) {
      return null;
    }

    // The trigger used a wrong (or zero) grossUnitPrice.
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

  /**
   * Callout post-hook: enrich the response with a synthetic {@code taxRate} update
   * when the user changed the line tax. Logic shared with invoice lines lives in
   * {@link LineCalloutTaxRateHelper}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    return LineCalloutTaxRateHelper.augmentTaxRate(context);
  }
}
