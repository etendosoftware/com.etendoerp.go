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
import org.codehaus.jettison.json.JSONArray;
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
 * <p>On GET: filters discount lines (dummy product {@code ETGO_DTO}) from the response so the
 * UI never displays the internal discount line as a regular product line.
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

    // For POST: apply gross-unit-price normalisation on tax-inclusive price lists.
    if ("POST".equals(context.getHttpMethod())) {
      JSONObject body = context.getRequestBody();
      if (body != null && body.optDouble("grossUnitPrice", -1) > 0) {
        // parentId is injected by NeoCrudHandler from the request body before this hook runs.
        String orderId = body.optString("parentId", null);
        if (orderId != null && !orderId.isEmpty()) {
          Order order = OBDal.getInstance().get(Order.class, orderId);
          if (order != null && order.getPriceList() != null) {
            NeoCommercialLinePolicy.normalizeOrderLineSelectorPriceMapping(
                body,
                Boolean.TRUE.equals(order.getPriceList().isPriceIncludesTax()),
                order.getPriceList().getIdentifier());
          }
        }
      }
    }

    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }

    String method = context.getHttpMethod();

    // Filter discount lines from GET responses so the UI never sees them.
    if ("GET".equals(method)) {
      return filterDiscountLinesFromResponse(context);
    }

    // Existing PATCH logic: fix grossUnitPrice on tax-inclusive price lists.
    if ("PATCH".equals(method)) {
      return fixGrossPriceAfterPatch(context);
    }

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

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /**
   * Removes discount lines (dummy product {@value TotalDiscountService#DISCOUNT_PRODUCT_ID})
   * from the GET response data array. Returns a new NeoResponse if any lines were removed,
   * or {@code null} to keep the original.
   */
  private NeoResponse filterDiscountLinesFromResponse(NeoContext context) {
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = prev.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      JSONArray filtered = new JSONArray();
      boolean removed = false;
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject row = dataArr.optJSONObject(i);
        if (row == null) {
          continue;
        }
        String productId = row.optString("product", "");
        if (TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(productId)) {
          removed = true;
        } else {
          filtered.put(row);
        }
      }
      if (!removed) {
        return null;
      }
      responseWrapper.put("data", filtered);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("[OrderLineHandler] Could not filter discount lines from GET response: {}",
          e.getMessage());
      return null;
    }
  }

  /**
   * Post-PATCH fix: corrects {@code grossUnitPrice} on tax-inclusive price lists when the DB
   * trigger derived the wrong net price.
   */
  private NeoResponse fixGrossPriceAfterPatch(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has("unitPrice")) {
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
}
