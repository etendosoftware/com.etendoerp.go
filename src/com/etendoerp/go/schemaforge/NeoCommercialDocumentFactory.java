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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.ad.system.Client;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Shared commercial document projection helpers for order-driven documents.
 */
final class NeoCommercialDocumentFactory {

  static ShipmentInOut createReturnReceiptHeader(ShipmentInOut source, DocumentType docType) {
    ShipmentInOut ret = OBProvider.getInstance().get(ShipmentInOut.class);
    ret.setClient(source.getClient());
    ret.setOrganization(source.getOrganization());
    ret.setBusinessPartner(source.getBusinessPartner());
    ret.setPartnerAddress(source.getPartnerAddress());
    ret.setWarehouse(source.getWarehouse());
    ret.setMovementDate(new Date());
    ret.setAccountingDate(new Date());
    ret.setDocumentType(docType);
    ret.setDocumentNo("<*>");
    ret.setSalesTransaction(true);
    ret.setSalesOrder(source.getSalesOrder());
    ret.setProcessed(false);
    ret.setDocumentStatus("DR");
    ret.setMovementType("C-");
    // ETP-4028: EM_Etgo_Currency_ID is mandatory on M_InOut — every new record must carry it.
    ret.setEtgoCurrency(source.getEtgoCurrency());
    return ret;
  }

  private NeoCommercialDocumentFactory() {
  }

  /** Returns the first active, non-return MMS (Goods Shipment) document type for the given client, or null. */
  static DocumentType findShipmentDocType(Client client) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, client))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "MMS"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_RETURN, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Returns the active, non-return goods-movement ({@code M_InOut}) document type of
   * {@code client} for the given base type and direction, preferring the one flagged default, or
   * {@code null} when none exists. Direction-agnostic counterpart of
   * {@link #findShipmentDocType(Client)} (which stays as it is for its existing callers): the
   * caller passes {@code MMS}/{@code true} for a goods shipment, {@code MMR}/{@code false} for a
   * goods receipt. {@code IsReturn = 'N'} is required on both sides — every client also carries
   * an "RTV Shipment" ({@code MMR}, purchase, return) and an "RFC Receipt" ({@code MMS}, sales,
   * return) that must never be picked for a regular movement (ETP-5576).
   */
  static DocumentType findInOutDocType(Client client, String docBaseType, boolean salesTransaction) {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, client))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, docBaseType))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, salesTransaction))
        .add(Restrictions.eq(DocumentType.PROPERTY_RETURN, false))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Defensive fallback when {@code DocumentNoHandlerLegacy} could not resolve the movement's
   * document number. Goods-movement document types are often configured with
   * {@code IsDocNoControlled='N'} and no {@code DocNoSequence_ID}, so the listener leaves the
   * placeholder (or an empty string) behind. Resolves the next number from the table-level
   * {@code DocumentNo_M_InOut} sequence using the movement's OWN client (not
   * {@code vars.getClient()}, which can differ under {@code OBContext.setAdminMode(true)} + NEO
   * Headless), on the OBDal JDBC connection so the sequence advance stays in the same
   * transaction. No-op when the movement already carries a real number.
   *
   * <p>Extracted unchanged from {@code CreateGoodsReceiptHandler#ensureDocumentNo} so the
   * follow-up document service (ETP-5576) gets the same guarantee for both directions.
   */
  static void ensureInOutDocumentNo(ShipmentInOut inout, Logger log) {
    String current = inout.getDocumentNo();
    if (StringUtils.isNotBlank(current) && !current.startsWith("<")) {
      return;
    }
    String docNo = Utility.getDocumentNoConnection(
        OBDal.getInstance().getConnection(false),
        new DalConnectionProvider(false),
        inout.getClient().getId(),
        "M_InOut",
        true);
    if (StringUtils.isBlank(docNo)) {
      log.warn(
          "Could not generate documentNo for goods movement {} (docType={}, client={}). "
              + "Configure DocNoSequence_ID on the document type or activate "
              + "AD_Sequence 'DocumentNo_M_InOut' for the client.",
          inout.getId(),
          inout.getDocumentType() != null ? inout.getDocumentType().getName() : "null",
          inout.getClient().getId());
      return;
    }
    log.info("Generated documentNo='{}' for goods movement {}", docNo, inout.getId());
    inout.setDocumentNo(docNo);
    OBDal.getInstance().save(inout);
    OBDal.getInstance().flush();
  }

  /** Returns the default active locator for the given warehouse, falling back to any active one. */
  static Locator findDefaultLocator(Warehouse warehouse) {
    List<Locator> defaults = OBDal.getInstance().createCriteria(Locator.class)
        .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE, warehouse))
        .add(Restrictions.eq(Locator.PROPERTY_DEFAULT, true))
        .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    if (!defaults.isEmpty()) return defaults.get(0);
    List<Locator> any = OBDal.getInstance().createCriteria(Locator.class)
        .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE, warehouse))
        .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    return any.isEmpty() ? null : any.get(0);
  }

  // ETP-4888: shipment/receipt headers (M_InOut) intentionally do NOT call
  // NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity here. The confirmed SII/SIF
  // gaps (etsgDateOperation, aeatsiiFechaRegCont) live on the sales-invoice/purchase-invoice
  // header entities, not on the shipment/goods-receipt spec — M_InOut carries no SII fields.
  // Only the invoice header builders below need the declared-derivation resolution pass.

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
    // ETP-4028: the shipment/receipt inherits the currency from its source order.
    shipment.setEtgoCurrency(order.getCurrency());
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
    // ETP-4028: EM_Etgo_Currency_ID is mandatory on M_InOut — every new record must carry it.
    shipment.setEtgoCurrency(source.getEtgoCurrency());
    return shipment;
  }

  // ETP-5576: createShipmentFromInvoiceHeader was removed. The invoice → movement header is now
  // built by InOutTargetBuilder from a neutral InOutTargetBuilder.Header, shared by every
  // "… → shipment/receipt" follow-up.

  /**
   * Builds a draft AP Invoice header from a goods receipt that has no linked purchase order.
   * Financial fields (price list, payment terms, payment method) come from the
   * business partner's purchase defaults, resolved by the caller.
   */
  static Invoice createInvoiceFromReceiptHeader(ShipmentInOut receipt,
      DocumentType invoiceDocType, PriceList priceList,
      PaymentTerm paymentTerms, FIN_PaymentMethod paymentMethod, Currency currency) {
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
    // ETP-4028: the invoice's currency is always inherited from the receipt's own
    // (editable-until-confirmed) currency, never from the price list — those can
    // diverge (e.g. no purchase price list exists in the receipt's currency).
    invoice.setCurrency(currency);
    invoice.setPaymentTerms(paymentTerms);
    invoice.setPaymentMethod(paymentMethod);
    invoice.setSummedLineAmount(BigDecimal.ZERO);
    invoice.setGrandTotalAmount(BigDecimal.ZERO);
    invoice.setWithholdingamount(BigDecimal.ZERO);
    invoice.setDocumentNo("<*>");
    // ETP-4888: this header is built directly via OBProvider, bypassing the normal NEO CRUD
    // "new record" HTTP path that would otherwise resolve every declared contract.json
    // derivation (e.g. SII/SIF fields like aeatsiiFechaRegCont). Fields already set above are
    // never overwritten — only properties still blank are filled in.
    NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity("purchase-invoice", "header",
        invoice, receipt.getId());
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
    // ETP-4888: this header is built directly via OBProvider, bypassing the normal NEO CRUD
    // "new record" HTTP path that would otherwise resolve every declared contract.json
    // derivation (e.g. SII/SIF fields like etsgDateOperation/aeatsiiFechaRegCont). Fields
    // already set above are never overwritten — only properties still blank are filled in.
    // Used by both the AR (sales-invoice) and AP (purchase-invoice) order-to-invoice paths.
    String invoiceSpecName = salesTransaction ? "sales-invoice" : "purchase-invoice";
    NeoBackgroundDefaultsService.applyDeclaredDefaultsToBackgroundEntity(invoiceSpecName, "header",
        invoice, order.getId());
    return invoice;
  }
}
