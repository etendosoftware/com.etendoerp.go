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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * NeoHandler for invoice line entities (Sales Invoice, Purchase Invoice).
 *
 * <p>Implements {@link #afterCallout(NeoContext)} to publish the tax rate to the frontend
 * when the user changes the line tax. The shared logic lives in {@link LineCalloutTaxRateHelper},
 * mirroring what {@link OrderLineHandler} does for order/quotation lines.
 *
 * <p>On GET: filters discount lines (dummy product {@code ETGO_DTO}) from the response so the
 * UI never displays the internal discount line as a regular product line.
 *
 * <p>Registered via {@code javaQualifier = "invoiceLineHandler"} on the lines
 * entity of sales-invoice and purchase-invoice specs.
 */
@Named("invoiceLineHandler")
public class InvoiceLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InvoiceLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    if ("GET".equals(context.getHttpMethod())) {
      return filterDiscountLinesFromResponse(context);
    }
    return null;
  }

  /**
   * Callout post-hook: enrich the response with a synthetic {@code taxRate} update
   * when the user changed the line tax. Logic shared with order lines lives in
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
      log.warn("[InvoiceLineHandler] Could not filter discount lines from GET response: {}",
          e.getMessage());
      return null;
    }
  }
}
