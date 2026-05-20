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
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

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
    return invoice;
  }
}
