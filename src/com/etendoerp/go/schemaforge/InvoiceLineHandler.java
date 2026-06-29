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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

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
 * <p>On POST/PATCH/PUT for return invoices (ARI_RM / isReturn=Y): auto-negates
 * {@code invoicedQuantity} and {@code lineNetAmount} when positive, so the
 * {@code C_Invoice_Post} validation ({@code @ReturnInvoiceNegativeQty@}) never fires
 * regardless of how the line was created (manual form or import modal).
 *
 * <p>Registered via {@code javaQualifier = "invoiceLineHandler"} on the lines
 * entity of sales-invoice and purchase-invoice specs.
 */
@Named("invoiceLineHandler")
public class InvoiceLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InvoiceLineHandler.class);
  private static final String FIELD_INVOICED_QTY = "invoicedQuantity";
  private static final String FIELD_LINE_NET_AMOUNT = "lineNetAmount";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!"POST".equals(method) && !"PATCH".equals(method) && !"PUT".equals(method)) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || !body.has(FIELD_INVOICED_QTY)) {
      return null;
    }
    try {
      String parentId = resolveParentInvoiceId(context, body);
      if (StringUtils.isBlank(parentId) || !isReturnInvoice(parentId)) {
        return null;
      }
      double qty = body.optDouble(FIELD_INVOICED_QTY, 0);
      if (qty > 0) {
        body.put(FIELD_INVOICED_QTY, -qty);
      }
      if (body.has(FIELD_LINE_NET_AMOUNT)) {
        double amount = body.optDouble(FIELD_LINE_NET_AMOUNT, 0);
        if (amount > 0) {
          body.put(FIELD_LINE_NET_AMOUNT, -amount);
        }
      }
    } catch (Exception e) {
      log.warn("Could not auto-negate return invoice line quantities: {}", e.getMessage());
    }
    return null;
  }

  private String resolveParentInvoiceId(NeoContext context, JSONObject body) {
    String fromBody = body.optString("parentId", null);
    if (StringUtils.isNotBlank(fromBody)) {
      return fromBody;
    }
    // PATCH/PUT: parentId may not be in body — resolve via the existing line record
    String lineId = context.getRecordId();
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    InvoiceLine line = OBDal.getInstance().get(InvoiceLine.class, lineId);
    return (line != null && line.getInvoice() != null) ? line.getInvoice().getId() : null;
  }

  private boolean isReturnInvoice(String invoiceId) {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
    if (invoice == null) {
      return false;
    }
    var docType = invoice.getTransactionDocument();
    return docType != null && Boolean.TRUE.equals(docType.isReturn());
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
