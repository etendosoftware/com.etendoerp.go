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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONException;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
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
 * Unit tests for {@link CreateShipmentHandler}.
 *
 * <p>Mirrors {@code CreateGoodsReceiptHandlerTest}: the single test below
 * locks down the link-step that the handler runs after persisting each
 * shipment line. Without that step, m_inout_post can't create m_matchsi
 * when the shipment is later completed, leaving the delivery status column
 * at 0% on sales invoices.
 *
 * @covers com.etendoerp.go.schemaforge.CreateShipmentHandler
 * @covers com.etendoerp.go.schemaforge.InOutLineFromOrderFactory
 */
public class CreateShipmentHandlerTest {

  /**
   * Test double that overrides {@link CreateShipmentHandler#findDefaultLocator}
   * to bypass the warehouse-locator lookup.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestableHandler extends CreateShipmentHandler {
    Locator locatorToReturn;

    @Override
    protected Locator findDefaultLocator(Order order) {
      return locatorToReturn;
    }
  }

  /**
   * Verifies that after persisting each shipment line, the handler runs the
   * native UPDATE that links draft invoice lines (of the same order line and
   * still unlinked) to the freshly created shipment line. This is what
   * c_invoiceline.M_InOutLine_ID gets set to so that m_inout_post can create
   * m_matchsi when the shipment is completed.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateShipmentLinesLinksDraftInvoiceLines() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);
      stubDraftRows(dal, Collections.<Object[]>emptyList());

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-2");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      Order order = mock(Order.class);

      when(order.getDocumentStatus()).thenReturn("CO");
      OrderLine orderLine = mock(OrderLine.class);
      when(orderLine.getId()).thenReturn("ol-2");
      when(orderLine.isActive()).thenReturn(true);
      Product product = mock(Product.class);
      when(product.isStocked()).thenReturn(true);
      when(product.getProductType()).thenReturn("I");
      when(orderLine.getProduct()).thenReturn(product);
      when(orderLine.getUOM()).thenReturn(mock(UOM.class));
      when(orderLine.getOrderedQuantity()).thenReturn(new BigDecimal("5"));
      when(orderLine.getDeliveredQuantity()).thenReturn(BigDecimal.ZERO);
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(orderLine));

      OBProvider provider = mock(OBProvider.class);
      ShipmentInOutLine shipmentLine = mock(ShipmentInOutLine.class);
      when(shipmentLine.getId()).thenReturn("iol-ship-new");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(shipmentLine);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      ShipmentInOut shipment = mock(ShipmentInOut.class);
      java.util.List<InOutLineFromOrderFactory.PendingOrderLine> pendingLines =
          InOutLineFromOrderFactory.collectPendingLines(order, true,
              new java.util.LinkedHashSet<String>());
      handler.createShipmentLines(shipment, pendingLines, handler.locatorToReturn);

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL should target C_InvoiceLine", sql.contains("UPDATE C_InvoiceLine"));
      assertTrue("SQL should set M_InOutLine_ID", sql.contains("SET M_InOutLine_ID"));
      assertTrue("SQL should filter by order line", sql.contains("C_OrderLine_ID = :orderLineId"));
      assertTrue("SQL should only touch unlinked lines", sql.contains("M_InOutLine_ID IS NULL"));

      verify(linkQuery).setParameter(eq("inoutLineId"), eq("iol-ship-new"));
      verify(linkQuery).setParameter(eq("orderLineId"), eq("ol-2"));
      verify(linkQuery).setParameter(eq("userId"), eq("user-2"));
      verify(linkQuery).executeUpdate();
    }
  }

  // ── handle() end-to-end (ETP-5276) ────────────────────────────────────────
  //
  // The tests above only ever exercised createShipmentLines() with a single
  // stockable line. None of them covered the actual bug: an order made up
  // entirely of non-stockable lines used to be silently dropped by
  // pendingQuantityFor(), and the header was persisted BEFORE the empty-lines
  // check ran, leaving an orphaned M_InOut behind despite the 400 response.
  // These tests drive the real handle(NeoContext) entry point end-to-end.

  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String ENTITY_HEADER = "header";
  private static final String ACTION_CREATE_SHIPMENT = "createShipment";

  private static NeoContext buildActionContext(String recordId) {
    return NeoContext.builder()
        .specName(SPEC_SALES_ORDER).entityName(ENTITY_HEADER)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(ACTION_CREATE_SHIPMENT).recordId(recordId).build();
  }

  /** Stubs the {@code M_DocType} criteria chain used by {@code findShipmentDocType}. */
  @SuppressWarnings("unchecked")
  private static void stubShipmentDocTypeLookup(OBDal dal, DocumentType docType) {
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
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
    stubDraftRows(dal, Collections.<Object[]>emptyList());
  }

  /**
   * ETP-5709: stubs the single draft-quantity HQL of the draft-aware pending computation. Each
   * element of {@code rowsPerCall} is what one successive call returns: rows of
   * {@code {orderLineId, movementQuantity, documentNo}}.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Query stubDraftRows(OBDal dal, List<Object[]>... rowsPerCall) {
    Session session = dal.getSession();
    if (session == null) {
      session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
    }
    Query draftQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(draftQuery);
    when(draftQuery.setParameter(anyString(), any())).thenReturn(draftQuery);
    when(draftQuery.setParameterList(anyString(), anyCollection())).thenReturn(draftQuery);
    org.mockito.stubbing.OngoingStubbing<List> stubbing = when(draftQuery.list());
    for (List<Object[]> rows : rowsPerCall) {
      stubbing = stubbing.thenReturn(rows);
    }
    return draftQuery;
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
   * ETP-5276 core regression: a Sales Order whose ONLY line is a Service product must still
   * produce a shipment with that one line — a Service/Expense/Resource line is a valid
   * shipment line, it just never gets a real storage bin.
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
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(serviceLine));
      when(dal.get(Order.class, "order-1")).thenReturn(order);

      DocumentType docType = mock(DocumentType.class);
      stubShipmentDocTypeLookup(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getId()).thenReturn("ship-1");
      when(shipment.getDocumentNo()).thenReturn("DOC-1");
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      NeoResponse r = handler.handle(buildActionContext("order-1"));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      assertEquals("ship-1",
          r.getBody().getJSONObject("response").getJSONObject("data").getString("id"));

      verify(provider, Mockito.times(1)).get(ShipmentInOutLine.class);
      verify(line).setStorageBin(null);
      verify(dal).save(shipment);
    }
  }

  /**
   * ETP-5276 orphan-header regression: an order with ZERO pending lines must return 400
   * WITHOUT ever persisting the shipment header. Before this fix, the header was saved
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

      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Collections.emptyList());
      when(dal.get(Order.class, "order-2")).thenReturn(order);

      TestableHandler handler = new TestableHandler();

      NeoResponse r = handler.handle(buildActionContext("order-2"));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message")
          .contains("No hay líneas pendientes"));

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
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(serviceLine));
      when(dal.get(Order.class, "order-3")).thenReturn(order);

      DocumentType docType = mock(DocumentType.class);
      stubShipmentDocTypeLookup(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);
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
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(stockLine));
      when(order.getWarehouse()).thenReturn(warehouse);
      when(dal.get(Order.class, "order-4")).thenReturn(order);
      stubDraftRows(dal, Collections.<Object[]>emptyList());

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
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Arrays.asList(stockLine, serviceLine));
      when(order.getWarehouse()).thenReturn(warehouse);
      when(dal.get(Order.class, "order-5")).thenReturn(order);

      DocumentType docType = mock(DocumentType.class);
      stubShipmentDocTypeLookup(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      when(shipment.getWarehouse()).thenReturn(warehouse);
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);

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

  /**
   * ETP-5709: requesting the document twice over the same order must not leave two documents
   * behind. A DRAFT shipment reserves nothing ({@code c_orderline.qtydelivered} only moves on
   * completion), so the second call must treat the first draft's quantity as taken: with
   * everything already in the draft it answers 400 naming that draft and persists no header.
   */
  @Test
  public void testHandleSecondCallOverSameOrderDoesNotCreateSecondDocument() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<SessionHandler> sessionHandlerMock = Mockito.mockStatic(SessionHandler.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(mock(SessionHandler.class));
      stubInvoiceLineLinkerTail(dal);
      // call 1: no drafts yet; call 2: the draft created by call 1 holds the full 5.
      Query draftQuery = stubDraftRows(dal, Collections.<Object[]>emptyList(),
          Collections.singletonList(new Object[] { "ol-dup", new BigDecimal("5"), "DOC-1" }));

      OrderLine serviceLine = mockPendingLine("ol-dup", true, "S",
          new BigDecimal("5"), BigDecimal.ZERO);
      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(serviceLine));
      when(dal.get(Order.class, "order-dup")).thenReturn(order);
      stubShipmentDocTypeLookup(dal, mock(DocumentType.class));

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut first = mock(ShipmentInOut.class);
      when(first.getId()).thenReturn("doc-1");
      when(first.getDocumentNo()).thenReturn("DOC-1");
      when(provider.get(ShipmentInOut.class)).thenReturn(first, mock(ShipmentInOut.class));
      when(provider.get(ShipmentInOutLine.class)).thenReturn(mock(ShipmentInOutLine.class));

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      NeoResponse r1 = handler.handle(buildActionContext("order-dup"));
      NeoResponse r2 = handler.handle(buildActionContext("order-dup"));

      assertNotNull(r1);
      assertEquals(201, r1.getHttpStatus());
      assertNotNull(r2);
      assertEquals(400, r2.getHttpStatus());
      String message = r2.getBody().getJSONObject("error").getString("message");
      assertTrue(message, message.contains("DOC-1"));
      assertTrue(message, message.contains("albarán borrador"));
      verify(dal, Mockito.times(1)).save(any(ShipmentInOut.class));
      verify(draftQuery, Mockito.times(2)).setParameter("sot", true);
    }
  }

  /**
   * ETP-5709: a PARTIAL draft (2 of 5 already in a draft) must yield a new document with ONLY
   * the remainder (3), not the full pending quantity and not an error.
   */
  @Test
  public void testHandlePartialDraftCreatesDocumentWithOnlyTheRemainder() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubInvoiceLineLinkerTail(dal);
      stubDraftRows(dal, Collections.singletonList(
          new Object[] { "ol-part", new BigDecimal("2"), "DOC-7" }));

      OrderLine serviceLine = mockPendingLine("ol-part", true, "S",
          new BigDecimal("5"), BigDecimal.ZERO);
      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getOrderLineList()).thenReturn(Collections.singletonList(serviceLine));
      when(dal.get(Order.class, "order-part")).thenReturn(order);
      stubShipmentDocTypeLookup(dal, mock(DocumentType.class));

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      ShipmentInOut doc = mock(ShipmentInOut.class);
      when(doc.getDocumentNo()).thenReturn("DOC-8");
      when(provider.get(ShipmentInOut.class)).thenReturn(doc);
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(provider.get(ShipmentInOutLine.class)).thenReturn(line);

      TestableHandler handler = new TestableHandler();
      handler.locatorToReturn = mock(Locator.class);

      NeoResponse r = handler.handle(buildActionContext("order-part"));

      assertNotNull(r);
      assertEquals(201, r.getHttpStatus());
      verify(line).setMovementQuantity(new BigDecimal("3"));
    }
  }

  /**
   * ETP-5709: the source order must be completed. A DRAFT order answers 400 naming its number and
   * status, and nothing is saved.
   */
  @Test
  public void testHandleNonCompletedOrderReturns400WithoutSavingAnything() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<SessionHandler> sessionHandlerMock = Mockito.mockStatic(SessionHandler.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      SessionHandler sessionHandler = mock(SessionHandler.class);
      sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);

      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn("DR");
      when(order.getDocumentNo()).thenReturn("PO-77");
      when(dal.get(Order.class, "order-draft")).thenReturn(order);

      NeoResponse r = new TestableHandler().handle(buildActionContext("order-draft"));

      assertNotNull(r);
      assertEquals(400, r.getHttpStatus());
      String message = r.getBody().getJSONObject("error").getString("message");
      assertTrue(message, message.contains("PO-77") && message.contains("DR"));
      verify(dal, never()).save(any(ShipmentInOut.class));
      verify(order, never()).getOrderLineList();
    }
  }

  @org.junit.Test
  public void contract_isPostWithoutParametersAndDescribesDraftAndPrecondition() {
    com.etendoerp.go.schemaforge.util.NeoActionContract c = CreateShipmentHandler.CONTRACT;

    assertEquals("createShipment", c.getName());
    assertEquals("POST", c.getHttpMethod());
    assertTrue(c.getParams().isEmpty());
    assertTrue(c.getDescription().contains("DRAFT"));
    assertTrue(c.getDescription().contains("status CO"));
  }

  @org.junit.Test
  public void testHandleNonCompletedOrderReturns400NamingNumberAndStatus() throws JSONException {
    // Covered together with the draft-order case above; this one pins the Spanish message.
    try (org.mockito.MockedStatic<org.openbravo.dal.core.OBContext> ctxMock =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.core.OBContext.class);
        org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class);
        org.mockito.MockedStatic<org.openbravo.dal.core.SessionHandler> shMock =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.core.SessionHandler.class)) {
      org.openbravo.dal.service.OBDal dal = org.mockito.Mockito.mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      shMock.when(org.openbravo.dal.core.SessionHandler::getInstance)
          .thenReturn(org.mockito.Mockito.mock(org.openbravo.dal.core.SessionHandler.class));
      org.openbravo.model.common.order.Order order =
          org.mockito.Mockito.mock(org.openbravo.model.common.order.Order.class);
      org.mockito.Mockito.when(order.getDocumentStatus()).thenReturn("DR");
      org.mockito.Mockito.when(order.getDocumentNo()).thenReturn("SO-8");
      org.mockito.Mockito.when(dal.get(org.openbravo.model.common.order.Order.class, "o-d"))
          .thenReturn(order);

      NeoResponse r = new CreateShipmentHandler().handle(NeoContext.builder().httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION).fieldName("createShipment")
          .specName("sales-order").recordId("o-d").build());

      assertEquals(400, r.getHttpStatus());
      String message = r.getBody().getJSONObject("error").getString("message");
      assertTrue(message, message.contains("SO-8") && message.contains("DR")
          && message.contains("no está completado"));
    }
  }

  /**
   * A CLOSED ({@code CL}) sales order is refused like any non-completed one: 400 naming the order
   * number and the status {@code CL}, and no shipment is saved.
   */
  @org.junit.Test
  public void testHandleClosedOrderReturns400NamingNumberAndClosedStatus() throws JSONException {
    try (org.mockito.MockedStatic<org.openbravo.dal.core.OBContext> ctxMock =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.core.OBContext.class);
        org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> obDalMock =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class);
        org.mockito.MockedStatic<org.openbravo.dal.core.SessionHandler> shMock =
            org.mockito.Mockito.mockStatic(org.openbravo.dal.core.SessionHandler.class)) {
      org.openbravo.dal.service.OBDal dal = org.mockito.Mockito.mock(org.openbravo.dal.service.OBDal.class);
      obDalMock.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
      shMock.when(org.openbravo.dal.core.SessionHandler::getInstance)
          .thenReturn(org.mockito.Mockito.mock(org.openbravo.dal.core.SessionHandler.class));
      org.openbravo.model.common.order.Order order =
          org.mockito.Mockito.mock(org.openbravo.model.common.order.Order.class);
      org.mockito.Mockito.when(order.getDocumentStatus()).thenReturn("CL");
      org.mockito.Mockito.when(order.getDocumentNo()).thenReturn("SO-CL-1");
      org.mockito.Mockito.when(dal.get(org.openbravo.model.common.order.Order.class, "o-cl"))
          .thenReturn(order);

      NeoResponse r = new CreateShipmentHandler().handle(NeoContext.builder().httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION).fieldName("createShipment")
          .specName("sales-order").recordId("o-cl").build());

      assertEquals(400, r.getHttpStatus());
      String message = r.getBody().getJSONObject("error").getString("message");
      assertTrue(message, message.contains("SO-CL-1") && message.contains("estado CL"));
      org.mockito.Mockito.verify(dal, org.mockito.Mockito.never())
          .save(org.mockito.ArgumentMatchers.any(ShipmentInOut.class));
      org.mockito.Mockito.verify(order, org.mockito.Mockito.never()).getOrderLineList();
    }
  }
}
