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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONException;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link CreateGoodsReceiptHandler}.
 *
 * <p>The single test below locks down the link-step that the handler runs after
 * persisting each receipt line. Without that step, m_inout_post can't create
 * m_matchinv when the receipt is later completed, which leaves the delivery
 * status column at 0% on purchase invoices.
 */
public class CreateGoodsReceiptHandlerTest {

  /**
   * Test double that overrides {@link CreateGoodsReceiptHandler#findDefaultLocator}
   * to bypass the warehouse-locator lookup, leaving createReceiptLines focused on
   * the line-creation + link-update behavior.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestableHandler extends CreateGoodsReceiptHandler {
    Locator locatorToReturn;

    @Override
    protected Locator findDefaultLocator(Order order) {
      return locatorToReturn;
    }
  }

  /**
   * Verifies that after persisting each receipt line, the handler runs the
   * native UPDATE that links draft invoice lines (of the same order line and
   * still unlinked) to the freshly created shipment line. This is what
   * c_invoiceline.M_InOutLine_ID gets set to so that m_inout_post can create
   * m_matchinv when the receipt is completed.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateReceiptLinesLinksDraftInvoiceLines() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      // ── OBDal + Session + NativeQuery chain ─────────────────────────────
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      // ── OBContext for the user-id parameter ─────────────────────────────
      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-1");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      // ── Order with one valid order line ─────────────────────────────────
      Order order = mock(Order.class);
      OrderLine orderLine = mock(OrderLine.class);
      when(orderLine.getId()).thenReturn("ol-1");
      when(orderLine.isActive()).thenReturn(true);
      Product product = mock(Product.class);
      when(product.isStocked()).thenReturn(true);
      when(product.getProductType()).thenReturn("I");
      when(orderLine.getProduct()).thenReturn(product);
      when(orderLine.getUOM()).thenReturn(mock(UOM.class));
      when(orderLine.getOrderedQuantity()).thenReturn(new BigDecimal("3"));
      when(orderLine.getDeliveredQuantity()).thenReturn(BigDecimal.ZERO);
      when(orderLine.getClient()).thenReturn(null);
      when(orderLine.getOrganization()).thenReturn(null);
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(orderLine));

      // ── ShipmentInOutLine produced by OBProvider ────────────────────────
      OBProvider provider = mock(OBProvider.class);
      ShipmentInOutLine receiptLine = mock(ShipmentInOutLine.class);
      when(receiptLine.getId()).thenReturn("iol-new");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(receiptLine);

      // ── Run the method under test ───────────────────────────────────────
      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      java.util.List<InOutLineFromOrderFactory.PendingOrderLine> pendingLines =
          InOutLineFromOrderFactory.collectPendingLines(order);
      handler.createReceiptLines(receipt, pendingLines, handler.locatorToReturn);

      // ── Capture the SQL and verify shape ────────────────────────────────
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL should target C_InvoiceLine", sql.contains("UPDATE C_InvoiceLine"));
      assertTrue("SQL should set M_InOutLine_ID", sql.contains("SET M_InOutLine_ID"));
      assertTrue("SQL should filter by order line", sql.contains("C_OrderLine_ID = :orderLineId"));
      assertTrue("SQL should only touch unlinked lines", sql.contains("M_InOutLine_ID IS NULL"));

      verify(linkQuery).setParameter(eq("inoutLineId"), eq("iol-new"));
      verify(linkQuery).setParameter(eq("orderLineId"), eq("ol-1"));
      verify(linkQuery).setParameter(eq("userId"), eq("user-1"));
      verify(linkQuery).executeUpdate();
    }
  }

  // ── handle() end-to-end (ETP-5276) ────────────────────────────────────────
  //
  // The test above only ever exercised createReceiptLines() with a single
  // stockable line. It did not cover the actual bug: an order made up
  // entirely of non-stockable lines used to be silently dropped by
  // pendingQuantityFor(), and the header was persisted BEFORE the empty-lines
  // check ran, leaving an orphaned M_InOut behind despite the 400 response.
  // These tests drive the real handle(NeoContext) entry point end-to-end.

  private static final String SPEC_PURCHASE_ORDER = "purchase-order";
  private static final String ENTITY_HEADER = "header";
  private static final String ACTION_CREATE_RECEIPT = "createGoodsReceipt";

  private static NeoContext buildActionContext(String recordId) {
    return NeoContext.builder()
        .specName(SPEC_PURCHASE_ORDER).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE_RECEIPT).recordId(recordId).build();
  }

  /** Stubs the {@code M_DocType} criteria chain used by the handler's private findReceiptDocType. */
  @SuppressWarnings("unchecked")
  private static void stubReceiptDocTypeLookup(OBDal dal, DocumentType docType) {
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(docType));
  }

  /** Stubs the native-query chain {@code InvoiceLineLinker} runs after each line is saved. */
  @SuppressWarnings("unchecked")
  private static void stubInvoiceLineLinkerTail(OBDal dal) {
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery linkQuery = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
    when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
    when(linkQuery.executeUpdate()).thenReturn(1);
  }

  private static OrderLine mockPendingLine(String id, boolean stocked, String productType,
      BigDecimal ordered, BigDecimal delivered) {
    Product product = mock(Product.class);
    when(product.isStocked()).thenReturn(stocked);
    when(product.getProductType()).thenReturn(productType);

    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getId()).thenReturn(id);
    when(orderLine.isActive()).thenReturn(true);
    when(orderLine.getProduct()).thenReturn(product);
    when(orderLine.getUOM()).thenReturn(mock(UOM.class));
    when(orderLine.getOrderedQuantity()).thenReturn(ordered);
    when(orderLine.getDeliveredQuantity()).thenReturn(delivered);
    return orderLine;
  }

  /**
   * ETP-5276 core regression: a Purchase Order whose ONLY line is a Service product must still
   * produce a receipt with that one line — a Service/Expense/Resource line is a valid receipt
   * line, it just never gets a real storage bin.
   */
  @Test
  public void testHandleServiceOnlyOrderCreatesSingleLineWithNullBin() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinkerTail(dal);

      OrderLine serviceLine = mockPendingLine("ol-service", true, "S",
          new BigDecimal("5"), BigDecimal.ZERO);
      Order order = mock(Order.class);
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(serviceLine));
      when(dal.get(Order.class, "order-1")).thenReturn(order);

      DocumentType docType = mock(DocumentType.class);
      stubReceiptDocTypeLookup(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getId()).thenReturn("receipt-1");
      when(receipt.getDocumentNo()).thenReturn("V/0001"); // non-blank, skips ensureDocumentNo's fallback
      when(provider.get(ShipmentInOut.class)).thenReturn(receipt);
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      NeoResponse r = handler.handle(buildActionContext("order-1"));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      assertEquals("receipt-1",
          r.getBody().getJSONObject("response").getJSONObject("data").getString("id"));

      verify(provider, Mockito.times(1)).get(ShipmentInOutLine.class);
      verify(line).setStorageBin(null);
      verify(dal).save(receipt);
    }
  }

  /**
   * ETP-5276 orphan-header regression: an order with ZERO pending lines must return 400
   * WITHOUT ever persisting the receipt header. Before this fix, the header was saved
   * before the empty-lines check ran and the missing rollback let Hibernate commit it
   * anyway despite the 400 response.
   */
  @Test
  public void testHandleZeroPendingLinesReturns400WithoutSavingHeader() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<SessionHandler> sessionHandlerMock = Mockito.mockStatic(SessionHandler.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      SessionHandler sessionHandler = mock(SessionHandler.class);
      sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);

      Order order = mock(Order.class);
      when(order.getOrderLineList()).thenReturn(Collections.emptyList());
      when(dal.get(Order.class, "order-2")).thenReturn(order);

      TestableHandler handler = new TestableHandler();

      NeoResponse r = handler.handle(buildActionContext("order-2"));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message")
          .contains("No pending lines to receive"));

      verify(dal, never()).save(any(ShipmentInOut.class));
      verify(sessionHandler).rollback();
    }
  }

  /**
   * ETP-5276: an order made up entirely of Service/Expense/Resource lines must succeed even
   * when its warehouse has NO locator configured at all — such an order has no stock to bin,
   * so the locator lookup must be skipped rather than failing loudly.
   */
  @Test
  public void testHandleServiceOnlyOrderWithNoLocatorStillSucceeds() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinkerTail(dal);

      OrderLine serviceLine = mockPendingLine("ol-service", true, "S",
          new BigDecimal("2"), BigDecimal.ZERO);
      Order order = mock(Order.class);
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(serviceLine));
      when(dal.get(Order.class, "order-3")).thenReturn(order);

      DocumentType docType = mock(DocumentType.class);
      stubReceiptDocTypeLookup(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getDocumentNo()).thenReturn("V/0003");
      when(provider.get(ShipmentInOut.class)).thenReturn(receipt);
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = null; // warehouse has NO locator configured

      NeoResponse r = handler.handle(buildActionContext("order-3"));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(line).setStorageBin(null);
    }
  }

  /**
   * Contrast case for the test above: when the order DOES have a stockable line, a missing
   * warehouse locator must still fail loudly with the existing "No storage locator found"
   * error — this must not regress just because non-stockable-only orders were exempted.
   */
  @Test
  public void testHandleStockableLineWithNoLocatorReturns400() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<SessionHandler> sessionHandlerMock = Mockito.mockStatic(SessionHandler.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      SessionHandler sessionHandler = mock(SessionHandler.class);
      sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);

      OrderLine stockLine = mockPendingLine("ol-stock", true, "I",
          new BigDecimal("10"), BigDecimal.ZERO);
      Warehouse warehouse = mock(Warehouse.class);
      when(warehouse.getName()).thenReturn("WH Central");
      Order order = mock(Order.class);
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(stockLine));
      when(order.getWarehouse()).thenReturn(warehouse);
      when(dal.get(Order.class, "order-4")).thenReturn(order);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = null;

      NeoResponse r = handler.handle(buildActionContext("order-4"));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message")
          .contains("No storage locator found"));
      verify(dal, never()).save(any(ShipmentInOut.class));
      verify(sessionHandler).rollback();
    }
  }

  /**
   * ETP-5276: a mixed order (one stockable line + one Service line) must create both lines —
   * the stockable line gets a real storage bin, the Service line gets {@code null}.
   */
  @Test
  public void testHandleMixedOrderCreatesBothLinesWithCorrectBins() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinkerTail(dal);

      Warehouse warehouse = mock(Warehouse.class);
      when(warehouse.getId()).thenReturn("wh-1");
      Locator resolvedLocator = mock(Locator.class);
      when(resolvedLocator.getWarehouse()).thenReturn(warehouse);

      OrderLine stockLine = mockPendingLine("ol-stock", true, "I",
          new BigDecimal("10"), BigDecimal.ZERO);
      OrderLine serviceLine = mockPendingLine("ol-service", true, "S",
          new BigDecimal("5"), BigDecimal.ZERO);
      Order order = mock(Order.class);
      when(order.getOrderLineList()).thenReturn(Arrays.asList(stockLine, serviceLine));
      when(order.getWarehouse()).thenReturn(warehouse);
      when(dal.get(Order.class, "order-5")).thenReturn(order);

      DocumentType docType = mock(DocumentType.class);
      stubReceiptDocTypeLookup(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getWarehouse()).thenReturn(warehouse);
      when(receipt.getDocumentNo()).thenReturn("V/0005");
      when(provider.get(ShipmentInOut.class)).thenReturn(receipt);

      ShipmentInOutLine stockShipLine = mock(ShipmentInOutLine.class);
      ShipmentInOutLine serviceShipLine = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(stockShipLine, serviceShipLine);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = resolvedLocator;

      NeoResponse r = handler.handle(buildActionContext("order-5"));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(stockShipLine).setStorageBin(resolvedLocator);
      verify(serviceShipLine).setStorageBin(null);
      verify(dal, never()).createCriteria(Locator.class);
    }
  }
}
