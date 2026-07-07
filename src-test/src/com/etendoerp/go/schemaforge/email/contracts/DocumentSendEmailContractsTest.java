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

package com.etendoerp.go.schemaforge.email.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for purchase and shipment document email contract wiring and
 * purchase family accept logic.
 */
public class DocumentSendEmailContractsTest {

  @Test
  public void providerRegistersPurchaseDocumentContracts() {
    Collection<EmailContract> contracts = new PurchaseDocumentEmailContractProvider().getContracts();

    Set<String> contractNames = contracts.stream()
        .map(EmailContract::getName)
        .collect(Collectors.toSet());

    assertEquals(2, contracts.size());
    assertTrue(contractNames.contains("purchase-order-send"));
    assertTrue(contractNames.contains("return-to-vendor-send"));
    assertTrue(contracts.stream().anyMatch(PurchaseOrderSendEmailContract.class::isInstance));
    assertTrue(contracts.stream().anyMatch(ReturnToVendorSendEmailContract.class::isInstance));
  }

  @Test
  public void providerRegistersShipmentDocumentContracts() {
    Collection<EmailContract> contracts = new ShipmentDocumentEmailContractProvider().getContracts();

    Set<String> contractNames = contracts.stream()
        .map(EmailContract::getName)
        .collect(Collectors.toSet());

    assertEquals(1, contracts.size());
    assertTrue(contractNames.contains("goods-shipment-send"));
    assertTrue(contracts.stream().anyMatch(GoodsShipmentSendEmailContract.class::isInstance));
  }

  @Test
  public void purchaseOrderResolverAcceptsOnlyNonReturnDocuments() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    assertTrue(resolver.acceptsReturnDocument(false));
    assertFalse(resolver.acceptsReturnDocument(true));
  }

  @Test
  public void purchaseReturnResolverAcceptsOnlyReturnDocuments() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_RETURN);

    assertTrue(resolver.acceptsReturnDocument(true));
    assertFalse(resolver.acceptsReturnDocument(false));
  }

  @Test
  public void purchaseOrderResolverResolvesNonReturnOrder() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");
    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenReturn("USD");
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    when(businessPartner.getEtgoEmail()).thenReturn("vendor@example.com");

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(order.getTransactionDocument()).thenReturn(null);
    when(order.getClient()).thenReturn(client);
    when(order.getCurrency()).thenReturn(currency);
    when(order.getBusinessPartner()).thenReturn(businessPartner);
    when(order.getGrandTotalAmount()).thenReturn(BigDecimal.TEN);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      Optional<EmailDocumentRecord> result = resolver.resolve("id-1");

      assertTrue(result.isPresent());
      assertEquals("vendor@example.com", result.get().getRecipientEmail());
    }
  }

  @Test
  public void purchaseReturnResolverResolvesReturnOrder() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_RETURN);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");
    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenReturn("USD");
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    when(businessPartner.getEtgoEmail()).thenReturn("vendor@example.com");
    DocumentType documentType = mock(DocumentType.class);
    when(documentType.isReturn()).thenReturn(Boolean.TRUE);

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(order.getTransactionDocument()).thenReturn(documentType);
    when(order.getClient()).thenReturn(client);
    when(order.getCurrency()).thenReturn(currency);
    when(order.getBusinessPartner()).thenReturn(businessPartner);
    when(order.getGrandTotalAmount()).thenReturn(BigDecimal.TEN);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      Optional<EmailDocumentRecord> result = resolver.resolve("id-1");

      assertTrue(result.isPresent());
      assertEquals("vendor@example.com", result.get().getRecipientEmail());
    }
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyForNullId() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    assertFalse(resolver.resolve(null).isPresent());
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyWhenRecordMissing() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "missing")).thenReturn(null);

      assertFalse(resolver.resolve("missing").isPresent());
    }
  }

  @Test
  public void shipmentResolverResolvesSalesShipment() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");
    BusinessPartner businessPartner = mock(BusinessPartner.class);
    when(businessPartner.getEtgoEmail()).thenReturn("customer@example.com");

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.isActive()).thenReturn(Boolean.TRUE);
    when(shipment.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(shipment.getClient()).thenReturn(client);
    when(shipment.getBusinessPartner()).thenReturn(businessPartner);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "ship-1")).thenReturn(shipment);

      Optional<EmailDocumentRecord> result = resolver.resolve("ship-1");

      assertTrue(result.isPresent());
      assertEquals("customer@example.com", result.get().getRecipientEmail());
    }
  }

  @Test
  public void shipmentResolverReturnsEmptyForNullId() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    assertFalse(resolver.resolve(null).isPresent());
  }

  @Test
  public void shipmentResolverReturnsEmptyWhenRecordMissing() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "missing")).thenReturn(null);

      assertFalse(resolver.resolve("missing").isPresent());
    }
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyForInactiveOrder() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.FALSE);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      assertFalse(resolver.resolve("id-1").isPresent());
    }
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyForSalesTransactionOrder() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.TRUE);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      assertFalse(resolver.resolve("id-1").isPresent());
    }
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyForWrongFamily() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    DocumentType documentType = mock(DocumentType.class);
    when(documentType.isReturn()).thenReturn(Boolean.TRUE);

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(order.getTransactionDocument()).thenReturn(documentType);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      assertFalse(resolver.resolve("id-1").isPresent());
    }
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyForNullClient() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(order.getTransactionDocument()).thenReturn(null);
    when(order.getClient()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      assertFalse(resolver.resolve("id-1").isPresent());
    }
  }

  @Test
  public void purchaseOrderResolverReturnsEmptyForNullCurrency() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(order.getTransactionDocument()).thenReturn(null);
    when(order.getClient()).thenReturn(client);
    when(order.getCurrency()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      assertFalse(resolver.resolve("id-1").isPresent());
    }
  }

  @Test
  public void purchaseOrderResolverResolvesOrderWithoutBusinessPartner() {
    DalPurchaseOrderEmailDocumentResolver resolver = new DalPurchaseOrderEmailDocumentResolver(
        DalPurchaseOrderEmailDocumentResolver.PurchaseOrderDocumentFamily.PURCHASE_ORDER);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");
    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenReturn("USD");

    Order order = mock(Order.class);
    when(order.isActive()).thenReturn(Boolean.TRUE);
    when(order.isSalesTransaction()).thenReturn(Boolean.FALSE);
    when(order.getTransactionDocument()).thenReturn(null);
    when(order.getClient()).thenReturn(client);
    when(order.getCurrency()).thenReturn(currency);
    when(order.getBusinessPartner()).thenReturn(null);
    when(order.getGrandTotalAmount()).thenReturn(BigDecimal.TEN);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Order.class, "id-1")).thenReturn(order);

      Optional<EmailDocumentRecord> result = resolver.resolve("id-1");

      assertTrue(result.isPresent());
      assertNull(result.get().getRecipientEmail());
    }
  }

  @Test
  public void shipmentResolverReturnsEmptyForInactiveShipment() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.isActive()).thenReturn(Boolean.FALSE);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "ship-1")).thenReturn(shipment);

      assertFalse(resolver.resolve("ship-1").isPresent());
    }
  }

  @Test
  public void shipmentResolverReturnsEmptyForNonSalesShipment() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.isActive()).thenReturn(Boolean.TRUE);
    when(shipment.isSalesTransaction()).thenReturn(Boolean.FALSE);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "ship-1")).thenReturn(shipment);

      assertFalse(resolver.resolve("ship-1").isPresent());
    }
  }

  @Test
  public void shipmentResolverReturnsEmptyForNullClient() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.isActive()).thenReturn(Boolean.TRUE);
    when(shipment.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(shipment.getClient()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "ship-1")).thenReturn(shipment);

      assertFalse(resolver.resolve("ship-1").isPresent());
    }
  }

  @Test
  public void shipmentResolverResolvesShipmentWithoutBusinessPartner() {
    DalShipmentEmailDocumentResolver resolver = new DalShipmentEmailDocumentResolver();

    Client client = mock(Client.class);
    when(client.getId()).thenReturn("0");

    ShipmentInOut shipment = mock(ShipmentInOut.class);
    when(shipment.isActive()).thenReturn(Boolean.TRUE);
    when(shipment.isSalesTransaction()).thenReturn(Boolean.TRUE);
    when(shipment.getClient()).thenReturn(client);
    when(shipment.getBusinessPartner()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "ship-1")).thenReturn(shipment);

      Optional<EmailDocumentRecord> result = resolver.resolve("ship-1");

      assertTrue(result.isPresent());
      assertNull(result.get().getRecipientEmail());
    }
  }
}
