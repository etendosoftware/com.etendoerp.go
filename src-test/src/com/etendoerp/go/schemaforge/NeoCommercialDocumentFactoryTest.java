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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for the second {@code createShipmentReceiptHeader} overload in
 * {@link NeoCommercialDocumentFactory} — the one that copies fields from an
 * existing {@link ShipmentInOut} source instead of an {@link Order}.
 *
 * <p>Each test mocks {@code OBProvider.getInstance()} statically so that
 * {@code OBProvider.getInstance().get(ShipmentInOut.class)} returns a controlled
 * mock, then verifies that every setter on the returned shipment is called with
 * the value taken from {@code source}.
 */
public class NeoCommercialDocumentFactoryTest {

  // ── createShipmentReceiptHeader(ShipmentInOut, ...) ──────────────────────

  /**
   * Verifies that all fields from the source shipment are copied to the new
   * shipment returned by the factory, and that the fixed fields (documentNo,
   * processed, documentStatus) are set to their expected initial values.
   */
  @Test
  public void testCreateShipmentReceiptHeaderFromSourceCopiesAllFields() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      // ── Source shipment with known field values ───────────────────────────
      ShipmentInOut source = mock(ShipmentInOut.class);
      Organization org = mock(Organization.class);
      org.openbravo.model.ad.system.Client client = mock(org.openbravo.model.ad.system.Client.class);
      org.openbravo.model.common.businesspartner.BusinessPartner bp =
          mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
      org.openbravo.model.common.enterprise.Warehouse warehouse =
          mock(org.openbravo.model.common.enterprise.Warehouse.class);
      Order salesOrder = mock(Order.class);

      when(source.getClient()).thenReturn(client);
      when(source.getOrganization()).thenReturn(org);
      when(source.getBusinessPartner()).thenReturn(bp);
      when(source.getPartnerAddress()).thenReturn(null);
      when(source.getWarehouse()).thenReturn(warehouse);
      when(source.getSalesOrder()).thenReturn(salesOrder);

      DocumentType docType = mock(DocumentType.class);

      // ── New shipment instance returned by OBProvider ─────────────────────
      ShipmentInOut newShipment = mock(ShipmentInOut.class);
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOut.class)).thenReturn(newShipment);

      // ── Invoke the method under test ──────────────────────────────────────
      ShipmentInOut result = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
          source, docType, true, "C+");

      // ── Verify the returned instance is the mocked one ────────────────────
      assertNotNull(result);
      assertEquals(newShipment, result);

      // ── Verify fields copied from source ──────────────────────────────────
      verify(newShipment).setClient(client);
      verify(newShipment).setOrganization(org);
      verify(newShipment).setBusinessPartner(bp);
      verify(newShipment).setPartnerAddress(null);
      verify(newShipment).setWarehouse(warehouse);
      verify(newShipment).setSalesOrder(salesOrder);

      // ── Verify fields set by the factory itself ────────────────────────────
      verify(newShipment).setDocumentType(docType);
      verify(newShipment).setDocumentNo("<*>");
      verify(newShipment).setSalesTransaction(true);
      verify(newShipment).setProcessed(false);
      verify(newShipment).setDocumentStatus("DR");
      verify(newShipment).setMovementType("C+");
    }
  }

  /**
   * Verifies that salesTransaction=false is forwarded correctly to the new
   * shipment, confirming the boolean parameter is not hardcoded.
   */
  @Test
  public void testCreateShipmentReceiptHeaderPurchaseSalesTransactionFalse() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      ShipmentInOut source = mock(ShipmentInOut.class);
      when(source.getClient()).thenReturn(null);
      when(source.getOrganization()).thenReturn(null);
      when(source.getBusinessPartner()).thenReturn(null);
      when(source.getPartnerAddress()).thenReturn(null);
      when(source.getWarehouse()).thenReturn(null);
      when(source.getSalesOrder()).thenReturn(null);

      ShipmentInOut newShipment = mock(ShipmentInOut.class);
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOut.class)).thenReturn(newShipment);

      DocumentType docType = mock(DocumentType.class);

      ShipmentInOut result = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
          source, docType, false, "V-");

      assertNotNull(result);
      verify(newShipment).setSalesTransaction(false);
      verify(newShipment).setMovementType("V-");
    }
  }

  /**
   * Verifies that movementType is forwarded verbatim for receipt direction ("V+").
   */
  @Test
  public void testCreateShipmentReceiptHeaderMovementTypeReceipt() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      ShipmentInOut source = mock(ShipmentInOut.class);
      when(source.getClient()).thenReturn(null);
      when(source.getOrganization()).thenReturn(null);
      when(source.getBusinessPartner()).thenReturn(null);
      when(source.getPartnerAddress()).thenReturn(null);
      when(source.getWarehouse()).thenReturn(null);
      when(source.getSalesOrder()).thenReturn(null);

      ShipmentInOut newShipment = mock(ShipmentInOut.class);
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOut.class)).thenReturn(newShipment);

      DocumentType docType = mock(DocumentType.class);

      ShipmentInOut result = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
          source, docType, false, "V+");

      assertNotNull(result);
      verify(newShipment).setMovementType("V+");
    }
  }

  /**
   * Verifies that the new shipment always starts in draft state with processed=false
   * and documentStatus="DR", regardless of the source shipment's state.
   */
  @Test
  public void testCreateShipmentReceiptHeaderAlwaysDraftAndNotProcessed() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      ShipmentInOut source = mock(ShipmentInOut.class);
      when(source.getClient()).thenReturn(null);
      when(source.getOrganization()).thenReturn(null);
      when(source.getBusinessPartner()).thenReturn(null);
      when(source.getPartnerAddress()).thenReturn(null);
      when(source.getWarehouse()).thenReturn(null);
      when(source.getSalesOrder()).thenReturn(null);

      ShipmentInOut newShipment = mock(ShipmentInOut.class);
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOut.class)).thenReturn(newShipment);

      NeoCommercialDocumentFactory.createShipmentReceiptHeader(
          source, mock(DocumentType.class), true, "C+");

      verify(newShipment).setProcessed(false);
      verify(newShipment).setDocumentStatus("DR");
      verify(newShipment).setDocumentNo("<*>");
    }
  }

  // ── createShipmentReceiptHeader(Order, ...) ──────────────────────────────

  @Test
  public void testCreateShipmentReceiptHeaderFromOrderSetsAllFields() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut newShipment = mock(ShipmentInOut.class);
      when(provider.get(ShipmentInOut.class)).thenReturn(newShipment);

      Order order = mock(Order.class);
      DocumentType docType = mock(DocumentType.class);

      ShipmentInOut result = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
          order, docType, false, "V+");

      assertSame(newShipment, result);
      verify(newShipment).setClient(order.getClient());
      verify(newShipment).setOrganization(order.getOrganization());
      verify(newShipment).setBusinessPartner(order.getBusinessPartner());
      verify(newShipment).setWarehouse(order.getWarehouse());
      verify(newShipment).setDocumentType(docType);
      verify(newShipment).setDocumentNo("<*>");
      verify(newShipment).setSalesTransaction(false);
      verify(newShipment).setMovementType("V+");
      verify(newShipment).setDocumentStatus("DR");
      verify(newShipment).setProcessed(false);
      verify(newShipment).setSalesOrder(order);
    }
  }

  // ── createInvoiceFromOrderHeader ────────────────────────────────────────

  @Test
  public void testCreateInvoiceFromOrderHeaderSetsAllFields() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      Invoice invoice = mock(Invoice.class);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      Order order = mock(Order.class);
      DocumentType docType = mock(DocumentType.class);

      Invoice result = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(
          order, docType, false);

      assertSame(invoice, result);
      verify(invoice).setDocumentType(docType);
      verify(invoice).setTransactionDocument(docType);
      verify(invoice).setDocumentStatus("DR");
      verify(invoice).setDocumentAction("CO");
      verify(invoice).setSalesTransaction(false);
      verify(invoice).setDocumentNo("<*>");
      verify(invoice).setSalesOrder(order);
      verify(invoice).setSummedLineAmount(BigDecimal.ZERO);
      verify(invoice).setGrandTotalAmount(BigDecimal.ZERO);
      verify(invoice).setWithholdingamount(BigDecimal.ZERO);
      verify(invoice).setClient(order.getClient());
      verify(invoice).setOrganization(order.getOrganization());
    }
  }

  // ── createInvoiceFromReceiptHeader ──────────────────────────────────────

  @Test
  public void testCreateInvoiceFromReceiptHeaderSetsAllFields() {
    try (MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      Invoice invoice = mock(Invoice.class);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      DocumentType docType = mock(DocumentType.class);
      PriceList priceList = mock(PriceList.class);
      Currency currency = mock(Currency.class);
      when(priceList.getCurrency()).thenReturn(currency);
      PaymentTerm paymentTerm = mock(PaymentTerm.class);
      FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);

      Invoice result = NeoCommercialDocumentFactory.createInvoiceFromReceiptHeader(
          receipt, docType, priceList, paymentTerm, paymentMethod);

      assertSame(invoice, result);
      verify(invoice).setDocumentType(docType);
      verify(invoice).setTransactionDocument(docType);
      verify(invoice).setDocumentStatus("DR");
      verify(invoice).setDocumentAction("CO");
      verify(invoice).setSalesTransaction(false);
      verify(invoice).setPriceList(priceList);
      verify(invoice).setCurrency(currency);
      verify(invoice).setPaymentTerms(paymentTerm);
      verify(invoice).setPaymentMethod(paymentMethod);
      verify(invoice).setDocumentNo("<*>");
      verify(invoice).setSummedLineAmount(BigDecimal.ZERO);
      verify(invoice).setGrandTotalAmount(BigDecimal.ZERO);
    }
  }
}
