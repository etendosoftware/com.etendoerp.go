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

/**
 * NeoHandler for invoice line entities (Sales Invoice, Purchase Invoice).
 *
 * <p>Implements {@link #afterCallout(NeoContext)} to publish the tax rate to the frontend
 * when the user changes the line tax. The shared logic lives in {@link LineCalloutTaxRateHelper},
 * mirroring what {@link OrderLineHandler} does for order/quotation lines.
 *
 * <p>On GET: filters discount lines (dummy product {@code ETGO_DTO}) from the response so the
 * UI never displays the internal discount line as a regular product line.
 * Filtering logic is shared with {@link OrderLineHandler} via {@link DiscountLineFilter}.
 *
 * <p>Registered via {@code javaQualifier = "invoiceLineHandler"} on the lines
 * entity of sales-invoice and purchase-invoice specs.
 */
@Named("invoiceLineHandler")
public class InvoiceLineHandler implements NeoHandler {

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
      return DiscountLineFilter.filterFromResponse(context);
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
}
