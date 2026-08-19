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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * ETP-4863 — {@code createAndLinkLine} receives the locator resolved from the ORDER's warehouse
 * ({@code CreateShipmentHandler} / {@code CreateGoodsReceiptHandler} both call
 * {@code findDefaultLocator(order)}), but the stock transaction follows the LINE's bin against
 * the DOCUMENT header's warehouse. Those two warehouses are normally the same, yet nothing in
 * the code guarantees it. This normalizes the line's bin to {@code parentInOut.getWarehouse()},
 * the single rule shared by every {@code M_InOutLine} write path in this module.
 */
public class InOutLineFromOrderFactoryTest {

  private static final String WH_PRINCIPAL = LocatorTestSupport.WH_PRINCIPAL;
  private static final String WH_SECONDARY = LocatorTestSupport.WH_SECONDARY;

  /**
   * Stubs the native-query chain {@code InvoiceLineLinker.linkPendingInvoiceLinesToInout} runs at
   * the tail of {@code createAndLinkLine}, so the test exercises the locator logic instead of
   * dying on an unstubbed session.
   */
  @SuppressWarnings("unchecked")
  private static void stubInvoiceLineLinker(OBDal dal) {
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(0);
  }

  private static OrderLine mockOrderLine() {
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getId()).thenReturn("ordln-1");
    return orderLine;
  }

  /**
   * The caller-supplied locator belongs to another warehouse than the shipment/receipt header:
   * it must be replaced by the header warehouse's own default bin, never persisted as-is.
   */
  @Test
  public void createAndLinkLine_locatorFromAnotherWarehouse_anchorsToParentWarehouse() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-secondary-A", LocatorTestSupport.mockWarehouse(WH_SECONDARY));
      Locator headerDefaultBin = LocatorTestSupport.mockLocator("loc-principal-default", headerWarehouse);
      LocatorTestSupport.stubDefaultLocatorLookup(dal, headerDefaultBin);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line, never()).setStorageBin(orderLocator);
      verify(line).setStorageBin(headerDefaultBin);
      verify(dal).save(line);
    }
  }

  /**
   * The caller-supplied locator already belongs to the header's warehouse — a legitimate choice
   * that must survive untouched, with no extra lookup issued.
   */
  @Test
  public void createAndLinkLine_locatorInParentWarehouse_isKept() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-principal-A", headerWarehouse);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line).setStorageBin(orderLocator);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }

  /**
   * Cascade step 4 at this call site: the header's warehouse has no active locator, so the
   * order's foreign locator must NOT be persisted — the line gets a null bin and the document
   * fails loudly at posting. Uniform with {@code assignBinsToLines} and
   * {@code createReturnLineShell}.
   */
  @Test
  public void createAndLinkLine_headerWarehouseHasNoLocator_doesNotKeepForeignLocator() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Warehouse headerWarehouse = LocatorTestSupport.mockWarehouse(WH_PRINCIPAL);
      Locator orderLocator = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(WH_SECONDARY));
      LocatorTestSupport.stubLocatorCascade(dal, null);

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(headerWarehouse);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line, never()).setStorageBin(orderLocator);
      verify(line).setStorageBin(null);
    }
  }

  /**
   * QA edge case: the parent shipment/receipt has NO warehouse set at all (e.g. malformed
   * document). There is nothing to anchor to, so the order-resolved locator must be kept
   * unmodified and no {@code M_Locator} lookup issued — same null-warehouse guard verified at
   * {@code NeoHandlerUtils.anchorLocatorToWarehouse} level, pinned here at the call site too.
   */
  @Test
  public void createAndLinkLine_parentWarehouseIsNull_keepsOrderLocatorUnanchored() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinker(dal);

      Locator orderLocator = LocatorTestSupport.mockLocator("loc-secondary-A",
          LocatorTestSupport.mockWarehouse(WH_SECONDARY));

      ShipmentInOut parentInOut = mock(ShipmentInOut.class);
      when(parentInOut.getWarehouse()).thenReturn(null);

      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      InOutLineFromOrderFactory.createAndLinkLine(
          parentInOut, mockOrderLine(), orderLocator, 10L, BigDecimal.ONE);

      verify(line).setStorageBin(orderLocator);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }
}
