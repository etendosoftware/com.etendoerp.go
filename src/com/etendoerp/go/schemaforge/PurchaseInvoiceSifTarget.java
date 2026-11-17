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

import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * The {@code sifDefaults} preview of {@code createPurchaseInvoice} (ETP-6179): the purchase invoice
 * the creation would build, as far as its SIF fields depend on it. Lives outside
 * {@link CreatePurchaseInvoiceHandler}, which was already at Sonar's method-count limit (S1448).
 *
 * <p>The {@link InvoiceSifPreset.Target} it builds must match the one the creation ends up with
 * ({@code Target.of(invoice)}), or a caller that sends no {@code sifFields} gets values the preview
 * never showed: the document type is the one the creation picks, and the order is set only when
 * the invoice header will carry it — the order route and a single receipt with an order build the
 * header from the order, while several receipts (and a receipt without an order) build it from the
 * receipt, with no order on it (ETP-6179 review W2).
 */
final class PurchaseInvoiceSifTarget {

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceSifTarget.class);

  private static final String SPEC_GOODS_RECEIPT = "goods-receipt";

  private PurchaseInvoiceSifTarget() {
  }

  /**
   * Answers {@code sifDefaults} on a purchase order or goods receipt, creating nothing.
   *
   * @param handler the handler whose document-type and order resolution the creation uses
   * @param context the request
   * @return the 200 with the preview, a 400 for a refused source, a 500 on an internal failure
   */
  static NeoResponse handleSifDefaults(CreatePurchaseInvoiceHandler handler, NeoContext context) {
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          CreatePurchaseInvoiceHandler.ERR_RECORD_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        return InvoiceSifPreset.previewResponse(
            resolve(handler, context.getSpecName(), recordId, context.getRequestBody()));
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      return handler.errorResponse(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error previewing the SIF fields for {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while previewing the invoice's fiscal data");
    }
  }

  /**
   * The target of the purchase invoice the creation would build. See the class javadoc.
   *
   * @param handler the handler whose resolution the creation uses
   * @param specName {@code purchase-order} or {@code goods-receipt}
   * @param recordId the source record
   * @param body the same body keys the creation reads ({@code receiptIds})
   * @return the target
   */
  static InvoiceSifPreset.Target resolve(CreatePurchaseInvoiceHandler handler, String specName,
      String recordId, JSONObject body) {
    if (SPEC_GOODS_RECEIPT.equals(specName)) {
      return receiptTarget(handler, recordId, body);
    }
    Order order = OBDal.getInstance().get(Order.class, recordId);
    if (order == null) {
      throw new OBException("Purchase order not found: " + recordId);
    }
    return new InvoiceSifPreset.Target(order.getOrganization(), order.getBusinessPartner(),
        handler.resolveAPInvoiceDocType(order), false, order);
  }

  private static InvoiceSifPreset.Target receiptTarget(CreatePurchaseInvoiceHandler handler,
      String recordId, JSONObject body) {
    List<String> receiptIds = handler.parseReceiptIds(body, recordId);
    ShipmentInOut first = OBDal.getInstance().get(ShipmentInOut.class, receiptIds.get(0));
    if (first == null) {
      throw new OBException(CreatePurchaseInvoiceHandler.ERR_RECEIPT_NOT_FOUND + receiptIds.get(0));
    }
    boolean single = receiptIds.size() == 1;
    Order order;
    if (!single) {
      order = handler.resolveCommonOrder(MultiDocumentInvoiceSupport
          .loadAndValidateSameBusinessPartner(receiptIds,
              CreatePurchaseInvoiceHandler.ERR_RECEIPT_NOT_FOUND,
              CreatePurchaseInvoiceHandler.ERR_NO_RECEIPTS,
              CreatePurchaseInvoiceHandler.ERR_RECEIPTS_DIFFERENT_PARTNER));
    } else if (first.getSalesOrder() != null) {
      order = first.getSalesOrder();
    } else {
      order = handler.deriveOrderFromLines(first);
    }
    DocumentType docType = order != null ? handler.resolveAPInvoiceDocType(order)
        : handler.findAPInvoiceDocType(first.getClient().getId());
    // Only the single-receipt route builds the header from the order (and links it).
    return new InvoiceSifPreset.Target(first.getOrganization(), first.getBusinessPartner(),
        docType, false, single ? order : null);
  }
}
