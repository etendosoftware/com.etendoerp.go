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

import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * How {@link ImportInOutLinesIntoInvoiceHandler} loads and validates the target invoice and the
 * goods shipment / receipt / return it reads lines from, moved unchanged out of the handler
 * (ETP-6099, the handler was past the method count Sonar accepts). Every DAL read goes back
 * through the handler's seams, so what a test overrides on the handler is still what runs.
 */
final class InOutInvoiceSourceRules {

  private static final String DOCUMENT = "Document ";

  private final ImportInOutLinesIntoInvoiceHandler handler;

  InOutInvoiceSourceRules(ImportInOutLinesIntoInvoiceHandler handler) {
    this.handler = handler;
  }

  static boolean isSales(Invoice invoice) {
    return Boolean.TRUE.equals(invoice.isSalesTransaction());
  }

  static boolean isReturn(ShipmentInOut doc) {
    return doc.getDocumentType() != null && Boolean.TRUE.equals(doc.getDocumentType().isReturn());
  }

  /**
   * The target invoice of the direction the action belongs to ({@code null}: any, the direction
   * of a return action is the invoice's). A write needs a draft with a price list; a read only
   * needs the invoice.
   */
  Invoice requireInvoiceOfDirection(String id, Boolean sales, boolean write) {
    Invoice invoice = write ? handler.requireDraftInvoice(id) : handler.requireExistingInvoice(id);
    if (sales != null && isSales(invoice) != sales) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_DIRECTION_MISMATCH,
          "This is a " + (sales ? "purchase" : "sales") + " invoice: use "
              + (sales ? ImportInOutLinesIntoInvoiceHandler.ACTION_IMPORT_RECEIPT_LINES
                  : ImportInOutLinesIntoInvoiceHandler.ACTION_IMPORT_SHIPMENT_LINES)
              + " for it");
    }
    return invoice;
  }

  ShipmentInOut requireSource(Invoice invoice, String sourceId, boolean returns,
      RectificativeImportPolicy policy) {
    ShipmentInOut source = requireCommonSource(invoice, sourceId);
    if (returns) {
      requireReturnRules(invoice, source, policy);
    } else {
      requireStandardRules(invoice, source);
    }
    return source;
  }

  /** What every source must satisfy: exists for the caller, completed, same side and partner. */
  private ShipmentInOut requireCommonSource(Invoice invoice, String sourceId) {
    ShipmentInOut source = handler.loadSource(sourceId);
    if (source == null) {
      throw DocumentLineImport.notFound("Document", sourceId);
    }
    handler.requireOwnClient("Document", sourceId, source.getClient());
    DocumentLineImport.requireCompletedSource(source.getDocumentNo(), source.getDocumentStatus());
    DocumentLineImport.requireSameDirection(isSales(invoice),
        Boolean.TRUE.equals(source.isSalesTransaction()), source.getDocumentNo());
    DocumentLineImport.requireSameBusinessPartner(
        invoice.getBusinessPartner() == null ? null : invoice.getBusinessPartner().getId(),
        source.getBusinessPartner() == null ? null : source.getBusinessPartner().getId(),
        source.getDocumentNo());
    return source;
  }

  /** A shipment or receipt: same currency, and never a return. */
  private static void requireStandardRules(Invoice invoice, ShipmentInOut source) {
    DocumentLineImport.requireSameCurrency(
        invoice.getCurrency() == null ? null : invoice.getCurrency().getId(),
        source.getEtgoCurrency() == null ? null : source.getEtgoCurrency().getId(),
        source.getDocumentNo());
    if (isReturn(source)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_DIRECTION_MISMATCH,
          DOCUMENT + source.getDocumentNo() + " is a return: it is credited through a "
              + "rectificative invoice ("
              + ImportInOutLinesIntoInvoiceHandler.ACTION_IMPORT_RETURN_LINES
              + " or createReturnInvoice on the return), not imported here");
    }
  }

  /** A return: it is one, in the invoice's currency where the side compares it, not yet in full. */
  private void requireReturnRules(Invoice invoice, ShipmentInOut source,
      RectificativeImportPolicy policy) {
    if (!isReturn(source)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_SOURCE_NOT_RETURN,
          DOCUMENT + source.getDocumentNo() + " is not a return: "
              + ImportInOutLinesIntoInvoiceHandler.ACTION_AVAILABLE_RETURNS
              + " lists the returns a rectificative invoice can take lines from");
    }
    if (returnCurrencyMismatch(invoice, source, policy)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_CURRENCY_MISMATCH, DOCUMENT
          + source.getDocumentNo() + " is in another currency than the document it is imported "
          + "into");
    }
    if (isInvoicedInFull(source)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_NOTHING_PENDING,
          "Return " + source.getDocumentNo() + " is already invoiced in full: it is not offered");
    }
  }

  /** The window lists only returns whose {@code invoiceStatus} is below 100. */
  boolean isInvoicedInFull(ShipmentInOut doc) {
    BigDecimal status = handler.invoiceStatusOf(doc.getId());
    return status != null && status.compareTo(BigDecimal.valueOf(100)) >= 0;
  }

  /**
   * The sales modal compares the currency of the return's sales order, when it has one, with the
   * invoice's; the purchase modal compares nothing.
   */
  static boolean returnCurrencyMismatch(Invoice invoice, ShipmentInOut doc,
      RectificativeImportPolicy policy) {
    if (!policy.comparesCurrency() || doc.getSalesOrder() == null
        || doc.getSalesOrder().getCurrency() == null || invoice.getCurrency() == null) {
      return false;
    }
    return !doc.getSalesOrder().getCurrency().getId().equals(invoice.getCurrency().getId());
  }
}
