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
import java.util.Date;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Shared commercial document projection helpers for order-driven documents.
 */
final class NeoCommercialDocumentFactory {

  private NeoCommercialDocumentFactory() {
  }

  static ShipmentInOut createShipmentReceiptHeader(Order order, DocumentType docType,
      boolean salesTransaction, String movementType) {
    ShipmentInOut shipment = OBProvider.getInstance().get(ShipmentInOut.class);
    shipment.setClient(order.getClient());
    shipment.setOrganization(order.getOrganization());
    shipment.setBusinessPartner(order.getBusinessPartner());
    shipment.setPartnerAddress(order.getPartnerAddress());
    shipment.setWarehouse(order.getWarehouse());
    shipment.setMovementDate(new Date());
    shipment.setAccountingDate(new Date());
    shipment.setDocumentType(docType);
    shipment.setDocumentNo("<*>");
    shipment.setSalesTransaction(salesTransaction);
    shipment.setSalesOrder(order);
    shipment.setProcessed(false);
    shipment.setDocumentStatus("DR");
    shipment.setMovementType(movementType);
    return shipment;
  }

  static ShipmentInOut createShipmentReceiptHeader(ShipmentInOut source, DocumentType docType,
      boolean salesTransaction, String movementType) {
    ShipmentInOut shipment = OBProvider.getInstance().get(ShipmentInOut.class);
    shipment.setClient(source.getClient());
    shipment.setOrganization(source.getOrganization());
    shipment.setBusinessPartner(source.getBusinessPartner());
    shipment.setPartnerAddress(source.getPartnerAddress());
    shipment.setWarehouse(source.getWarehouse());
    Date now = new Date();
    shipment.setMovementDate(now);
    shipment.setAccountingDate(now);
    shipment.setDocumentType(docType);
    shipment.setDocumentNo("<*>");
    shipment.setSalesTransaction(salesTransaction);
    shipment.setSalesOrder(source.getSalesOrder());
    shipment.setProcessed(false);
    shipment.setDocumentStatus("DR");
    shipment.setMovementType(movementType);
    return shipment;
  }

  /**
   * Builds a draft AP Invoice header from a goods receipt that has no linked purchase order.
   * Financial fields (price list, payment terms, payment method) come from the
   * business partner's purchase defaults, resolved by the caller.
   */
  static Invoice createInvoiceFromReceiptHeader(ShipmentInOut receipt,
      DocumentType invoiceDocType, PriceList priceList,
      PaymentTerm paymentTerms, FIN_PaymentMethod paymentMethod) {
    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(receipt.getClient());
    invoice.setOrganization(receipt.getOrganization());
    invoice.setDocumentType(invoiceDocType);
    invoice.setTransactionDocument(invoiceDocType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(false);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(receipt.getBusinessPartner());
    invoice.setPartnerAddress(receipt.getPartnerAddress());
    invoice.setPriceList(priceList);
    invoice.setCurrency(priceList.getCurrency());
    invoice.setPaymentTerms(paymentTerms);
    invoice.setPaymentMethod(paymentMethod);
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);
    invoice.setDocumentNo("<*>");
    return invoice;
  }

  static Invoice createInvoiceFromOrderHeader(Order order, DocumentType invoiceDocType,
      boolean salesTransaction) {
    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    invoice.setClient(order.getClient());
    invoice.setOrganization(order.getOrganization());
    invoice.setDocumentType(invoiceDocType);
    invoice.setTransactionDocument(invoiceDocType);
    invoice.setDocumentStatus("DR");
    invoice.setDocumentAction("CO");
    invoice.setSalesTransaction(salesTransaction);
    invoice.setInvoiceDate(new Date());
    invoice.setAccountingDate(new Date());
    invoice.setBusinessPartner(order.getBusinessPartner());
    invoice.setPartnerAddress(order.getPartnerAddress());
    invoice.setPriceList(order.getPriceList());
    invoice.setCurrency(order.getCurrency());
    invoice.setPaymentTerms(order.getPaymentTerms());
    invoice.setPaymentMethod(order.getPaymentMethod());
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);
    invoice.setSalesOrder(order);
    invoice.setDocumentNo("<*>");
    // Carry over the header-level total discount percentage so TotalDiscountService
    // can materialize the matching ETGO_DTO discount line on the new invoice.
    invoice.setEtgoTotalDiscount(order.getEtgoTotalDiscount());
    return invoice;
  }
}
