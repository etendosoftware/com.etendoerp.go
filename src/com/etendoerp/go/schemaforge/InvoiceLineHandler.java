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
    String method = context.getHttpMethod();
    if ("GET".equals(method)) {
      return DiscountLineFilter.filterFromResponse(context);
    }
    if ("POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method)) {
      syncConversionRateDocumentAfterLineSave(context);
    }
    return null;
  }

  /**
   * Re-syncs the parent invoice's {@code C_Conversion_Rate_Document} (rate/foreignAmount)
   * after a line is added or edited (POST/PATCH/PUT). {@code AbstractInvoiceHeaderHandler
   * #autoCreateOrUpdateConversionRateDocument} only runs on HEADER saves — since it is bound
   * per-entity via {@code ETGO_SF_ENTITY.Java_Qualifier} — so nothing re-syncs the document's
   * {@code foreignAmount} as {@code grandTotalAmount} changes while lines are added after the
   * header currency/rate was first set (ETP-4029 follow-up gap, confirmed via manual QA on
   * invoice {@code 200611A7D11E4EE3B3B9DEEA607015D9}).
   *
   * <p>Resolves the parent invoice ID the same way {@link #resolveParentInvoiceId(NeoContext,
   * JSONObject)} does for {@code handle()}, adapted for {@code afterHandle()} where the original
   * request body is not guaranteed to still carry {@code parentId} (e.g. POST responses only
   * expose the created line via {@code getPreviousResult()}). Delegates the actual upsert to the
   * shared {@link AbstractInvoiceHeaderHandler#autoCreateOrUpdateConversionRateDocument(String)}
   * core — no SQL is duplicated here.
   *
   * @param context the current NeoContext (lines entity; {@code getRecordId()} is the LINE id)
   */
  private void syncConversionRateDocumentAfterLineSave(NeoContext context) {
    try {
      String invoiceId = resolveParentInvoiceIdAfterSave(context);
      AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(invoiceId);
    } catch (Exception e) {
      log.warn("[ETP-4029] Could not sync conversion rate document after line save: {}",
          e.getMessage());
    }
  }

  /**
   * Resolves the parent invoice ID after a line POST/PATCH/PUT has already completed.
   * PATCH/PUT: the line record already existed, so {@code context.getRecordId()} is the line ID
   * and can be looked up directly. POST: the line did not exist yet when the request started, so
   * the new line ID must be read from {@code context.getPreviousResult()}'s CRUD response body
   * (mirrors {@code AbstractInvoiceHeaderHandler#resolveInvoiceIdFromContext}'s POST-vs-PATCH
   * resolution pattern, adapted here because the "record" in this entity is the line, not the
   * invoice).
   *
   * @param context the current NeoContext
   * @return the parent invoice ID, or {@code null} if it could not be resolved
   */
  private String resolveParentInvoiceIdAfterSave(NeoContext context) {
    String lineId = context.getRecordId();
    if (StringUtils.isBlank(lineId)) {
      lineId = extractCreatedLineIdFromPreviousResult(context);
    }
    if (StringUtils.isBlank(lineId)) {
      return null;
    }
    InvoiceLine line = OBDal.getInstance().get(InvoiceLine.class, lineId);
    return (line != null && line.getInvoice() != null) ? line.getInvoice().getId() : null;
  }

  private String extractCreatedLineIdFromPreviousResult(NeoContext context) {
    return NeoHandlerUtils.extractCreatedIdFromPreviousResult(context);
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
