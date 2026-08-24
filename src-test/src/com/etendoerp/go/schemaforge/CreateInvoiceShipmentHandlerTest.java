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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link CreateInvoiceShipmentHandler}.
 *
 * <p>Covers all branches of {@code handle()}, including:
 * <ul>
 *   <li>Early-exit guards (non-ACTION, wrong action, wrong method, wrong spec, blank recordId)</li>
 *   <li>Error paths: invoice not found, warehouse not found, doc type not found,
 *       locator not found, no valid lines</li>
 *   <li>Success path with 201 response</li>
 *   <li>Warehouse resolution: from invoice's order, from line's order, from org fallback</li>
 * </ul>
 */
public class CreateInvoiceShipmentHandlerTest {

  private CreateInvoiceShipmentHandler handler;

  @Before
  public void setUp() {
    handler = new CreateInvoiceShipmentHandler();
  }

  // ── handle() — early-exit guards ─────────────────────────────────────────────

  @Test
  public void handle_nonActionEndpoint_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .fieldName("createShipment").specName("sales-invoice").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handle_wrongActionName_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("otherAction").specName("sales-invoice").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handle_nonPostMethod_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("createShipment").specName("sales-invoice").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handle_wrongSpecName_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createShipment").specName("purchase-invoice").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handle_blankRecordId_returns400() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createShipment").specName("sales-invoice").recordId(null).build();

    NeoResponse result = handler.handle(ctx);
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  // ── handle() — error paths ────────────────────────────────────────────────────

  @Test
  public void handle_invoiceNotFound_returns400() {
    NeoContext ctx = actionCtx("inv-missing");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-missing")).thenReturn(null);

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_warehouseNotFound_returns400() {
    NeoContext ctx = actionCtx("inv-no-wh");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "inv-no-wh")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.emptyList());
      when(invoice.getOrganization()).thenReturn(org);

      OBCriteria<Warehouse> whCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Warehouse.class)).thenReturn(whCriteria);
      when(whCriteria.add(any(Criterion.class))).thenReturn(whCriteria);
      when(whCriteria.setMaxResults(1)).thenReturn(whCriteria);
      when(whCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_docTypeNotFound_returns400() {
    NeoContext ctx = actionCtx("inv-no-dt");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Warehouse warehouse = mock(Warehouse.class);
      Order order = mock(Order.class);
      Client client = mock(Client.class);
      when(dal.get(Invoice.class, "inv-no-dt")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(order);
      when(order.getWarehouse()).thenReturn(warehouse);
      when(invoice.getClient()).thenReturn(client);

      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.emptyList());

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_locatorNotFound_returns400() {
    NeoContext ctx = actionCtx("inv-no-loc");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      Warehouse warehouse = mock(Warehouse.class);
      Order order = mock(Order.class);
      Client client = mock(Client.class);
      DocumentType docType = mock(DocumentType.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);

      when(dal.get(Invoice.class, "inv-no-loc")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(order);
      when(order.getWarehouse()).thenReturn(warehouse);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(mock(Organization.class));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.emptyList());
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);

      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.singletonList(docType));

      // Both locator criteria return empty — findDefaultLocator returns null
      OBCriteria<Locator> locCriteria1 = mock(OBCriteria.class);
      OBCriteria<Locator> locCriteria2 = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class))
          .thenReturn(locCriteria1)
          .thenReturn(locCriteria2);
      when(locCriteria1.add(any(Criterion.class))).thenReturn(locCriteria1);
      when(locCriteria1.setMaxResults(1)).thenReturn(locCriteria1);
      when(locCriteria1.list()).thenReturn(Collections.emptyList());
      when(locCriteria2.add(any(Criterion.class))).thenReturn(locCriteria2);
      when(locCriteria2.setMaxResults(1)).thenReturn(locCriteria2);
      when(locCriteria2.list()).thenReturn(Collections.emptyList());

      when(warehouse.getName()).thenReturn("Main Warehouse");

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_noValidLines_returns400() {
    NeoContext ctx = actionCtx("inv-no-lines");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      Order order = mock(Order.class);
      Client client = mock(Client.class);
      DocumentType docType = mock(DocumentType.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);

      when(dal.get(Invoice.class, "inv-no-lines")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(order);
      when(order.getWarehouse()).thenReturn(warehouse);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(mock(Organization.class));
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);

      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.singletonList(docType));

      OBCriteria<Locator> locCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(locCriteria);
      when(locCriteria.add(any(Criterion.class))).thenReturn(locCriteria);
      when(locCriteria.setMaxResults(1)).thenReturn(locCriteria);
      when(locCriteria.list()).thenReturn(Collections.singletonList(locator));

      // Line with null product — skipped
      InvoiceLine invLine = mock(InvoiceLine.class);
      when(invLine.getProduct()).thenReturn(null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(invLine));

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  // ── handle() — success path ───────────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void handle_success_returns201WithShipmentData() throws Exception {
    NeoContext ctx = actionCtx("inv-ok");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      Order order = mock(Order.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      DocumentType docType = mock(DocumentType.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      ShipmentInOutLine shipLine = mock(ShipmentInOutLine.class);
      Product product = mock(Product.class);
      UOM uom = mock(UOM.class);

      when(dal.get(Invoice.class, "inv-ok")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(order);
      when(order.getWarehouse()).thenReturn(warehouse);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(org);

      // findShipmentDocType → uses OBDal criteria on DocumentType
      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.singletonList(docType));

      // createShipmentFromInvoiceHeader → OBProvider.get(ShipmentInOut.class)
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);
      when(shipment.getId()).thenReturn("shipment-1");
      when(shipment.getDocumentNo()).thenReturn("ALB-0001");

      // findDefaultLocator → first criteria returns locator (default-flagged)
      OBCriteria<Locator> locCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(locCriteria);
      when(locCriteria.add(any(Criterion.class))).thenReturn(locCriteria);
      when(locCriteria.setMaxResults(1)).thenReturn(locCriteria);
      when(locCriteria.list()).thenReturn(Collections.singletonList(locator));

      // Valid invoice line
      InvoiceLine invLine = mock(InvoiceLine.class);
      when(invLine.getProduct()).thenReturn(product);
      when(invLine.getUOM()).thenReturn(uom);
      when(invLine.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
      when(invLine.getSalesOrderLine()).thenReturn(null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(invLine));

      // createShipmentLines → OBProvider.get(ShipmentInOutLine.class)
      when(provider.get(ShipmentInOutLine.class)).thenReturn(shipLine);

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(201, result.getHttpStatus());
      JSONObject body = result.getBody();
      assertNotNull(body);
      JSONObject data = body.getJSONObject("response").getJSONObject("data");
      assertEquals("shipment-1", data.getString("id"));
      assertEquals("ALB-0001", data.getString("documentNo"));
    }
  }

  // ── ETP-4863: createShipmentLines anchors storageBin to the shipment's warehouse ───────────
  //
  // CreateInvoiceShipmentHandler (the "create shipment from invoice" flow) was the only DAL
  // M_InOutLine-create path in the module that wrote storageBin verbatim, bypassing
  // NeoHandlerUtils.anchorLocatorToWarehouse — the same guarantee InOutLineFromOrderFactory
  // already applies. This proves createShipmentLines routes the resolved locator through that
  // helper, using the ANCHORED result (not the raw resolveDefaultLocator() result) as the
  // line's storageBin.

  @Test
  @SuppressWarnings("unchecked")
  public void handle_success_anchorsStorageBinToShipmentWarehouse() throws Exception {
    NeoContext ctx = actionCtx("inv-anchor");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class);
         MockedStatic<NeoHandlerUtils> utilsMock = Mockito.mockStatic(NeoHandlerUtils.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      Warehouse orderWarehouse = mock(Warehouse.class);
      Warehouse shipmentWarehouse = mock(Warehouse.class);
      Locator resolvedLocator = mock(Locator.class);
      Locator anchoredLocator = mock(Locator.class);
      Order order = mock(Order.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      DocumentType docType = mock(DocumentType.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      ShipmentInOutLine shipLine = mock(ShipmentInOutLine.class);
      Product product = mock(Product.class);
      UOM uom = mock(UOM.class);

      when(dal.get(Invoice.class, "inv-anchor")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(order);
      when(order.getWarehouse()).thenReturn(orderWarehouse);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(org);

      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.singletonList(docType));

      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);
      when(shipment.getId()).thenReturn("shipment-anchor");
      when(shipment.getDocumentNo()).thenReturn("ALB-ANCHOR");
      // The created shipment's own warehouse — what the line must actually anchor to.
      when(shipment.getWarehouse()).thenReturn(shipmentWarehouse);

      OBCriteria<Locator> locCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(locCriteria);
      when(locCriteria.add(any(Criterion.class))).thenReturn(locCriteria);
      when(locCriteria.setMaxResults(1)).thenReturn(locCriteria);
      when(locCriteria.list()).thenReturn(Collections.singletonList(resolvedLocator));

      InvoiceLine invLine = mock(InvoiceLine.class);
      when(invLine.getProduct()).thenReturn(product);
      when(invLine.getUOM()).thenReturn(uom);
      when(invLine.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
      when(invLine.getSalesOrderLine()).thenReturn(null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(invLine));

      when(provider.get(ShipmentInOutLine.class)).thenReturn(shipLine);

      // Simulate the anchor helper correcting the resolved locator to a DIFFERENT one, so the
      // assertion below can only pass if createShipmentLines actually routes through it.
      utilsMock.when(() -> NeoHandlerUtils.anchorLocatorToWarehouse(
          eq(resolvedLocator), eq(shipmentWarehouse), any())).thenReturn(anchoredLocator);

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(201, result.getHttpStatus());
      Mockito.verify(shipLine).setStorageBin(anchoredLocator);
      utilsMock.verify(() -> NeoHandlerUtils.anchorLocatorToWarehouse(
          eq(resolvedLocator), eq(shipmentWarehouse), any()));
    }
  }

  // ── resolveWarehouse — fallback paths ────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void handle_warehouseResolvedFromLineOrder_createsShipment() throws Exception {
    NeoContext ctx = actionCtx("inv-line-wh");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      OrderLine orderLine = mock(OrderLine.class);
      Order lineOrder = mock(Order.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      DocumentType docType = mock(DocumentType.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      ShipmentInOutLine shipLine = mock(ShipmentInOutLine.class);

      when(dal.get(Invoice.class, "inv-line-wh")).thenReturn(invoice);
      // No invoice-level order → falls through to line resolution
      when(invoice.getSalesOrder()).thenReturn(null);
      // Line has an order with a warehouse
      InvoiceLine lineWithOrder = mock(InvoiceLine.class);
      when(lineWithOrder.getSalesOrderLine()).thenReturn(orderLine);
      when(orderLine.getSalesOrder()).thenReturn(lineOrder);
      when(lineOrder.getWarehouse()).thenReturn(warehouse);
      when(lineWithOrder.getProduct()).thenReturn(mock(Product.class));
      when(lineWithOrder.getUOM()).thenReturn(mock(UOM.class));
      when(lineWithOrder.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(lineWithOrder));

      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(org);
      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);
      when(shipment.getId()).thenReturn("shipment-2");
      when(shipment.getDocumentNo()).thenReturn("ALB-0002");

      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.singletonList(docType));

      OBCriteria<Locator> locCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(locCriteria);
      when(locCriteria.add(any(Criterion.class))).thenReturn(locCriteria);
      when(locCriteria.setMaxResults(1)).thenReturn(locCriteria);
      when(locCriteria.list()).thenReturn(Collections.singletonList(locator));

      when(provider.get(ShipmentInOutLine.class)).thenReturn(shipLine);

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(201, result.getHttpStatus());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_warehouseResolvedFromOrgFallback_createsShipment() throws Exception {
    NeoContext ctx = actionCtx("inv-org-wh");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);

      Invoice invoice = mock(Invoice.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      DocumentType docType = mock(DocumentType.class);
      ShipmentInOut shipment = mock(ShipmentInOut.class);
      ShipmentInOutLine shipLine = mock(ShipmentInOutLine.class);

      when(dal.get(Invoice.class, "inv-org-wh")).thenReturn(invoice);
      when(invoice.getSalesOrder()).thenReturn(null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.emptyList());
      when(invoice.getOrganization()).thenReturn(org);
      when(invoice.getClient()).thenReturn(client);

      // Warehouse found via org criteria
      OBCriteria<Warehouse> whCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Warehouse.class)).thenReturn(whCriteria);
      when(whCriteria.add(any(Criterion.class))).thenReturn(whCriteria);
      when(whCriteria.setMaxResults(1)).thenReturn(whCriteria);
      when(whCriteria.list()).thenReturn(Collections.singletonList(warehouse));

      OBCriteria<DocumentType> dtCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(DocumentType.class)).thenReturn(dtCriteria);
      when(dtCriteria.add(any(Criterion.class))).thenReturn(dtCriteria);
      when(dtCriteria.setMaxResults(1)).thenReturn(dtCriteria);
      when(dtCriteria.list()).thenReturn(Collections.singletonList(docType));

      when(provider.get(ShipmentInOut.class)).thenReturn(shipment);
      when(shipment.getId()).thenReturn("shipment-3");
      when(shipment.getDocumentNo()).thenReturn("ALB-0003");

      // Invoice line with valid product — needed so createShipmentLines doesn't throw
      InvoiceLine invLine = mock(InvoiceLine.class);
      when(invLine.getProduct()).thenReturn(mock(Product.class));
      when(invLine.getUOM()).thenReturn(mock(UOM.class));
      when(invLine.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
      when(invLine.getSalesOrderLine()).thenReturn(null);
      // Re-stub getInvoiceLineList to return the line for createShipmentLines
      when(invoice.getInvoiceLineList())
          .thenReturn(Collections.emptyList())  // first call in resolveWarehouse loop
          .thenReturn(Collections.singletonList(invLine)); // second call in createShipmentLines

      OBCriteria<Locator> locCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(locCriteria);
      when(locCriteria.add(any(Criterion.class))).thenReturn(locCriteria);
      when(locCriteria.setMaxResults(1)).thenReturn(locCriteria);
      when(locCriteria.list()).thenReturn(Collections.singletonList(locator));

      when(provider.get(ShipmentInOutLine.class)).thenReturn(shipLine);

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(201, result.getHttpStatus());
    }
  }

  // ── handle() — exception handling ────────────────────────────────────────────

  @Test
  public void handle_unexpectedException_returns500() {
    NeoContext ctx = actionCtx("inv-ex");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-ex")).thenThrow(new RuntimeException("unexpected"));

      NeoResponse result = handler.handle(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  // ── helper ────────────────────────────────────────────────────────────────────

  private static NeoContext actionCtx(String recordId) {
    return NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createShipment").specName("sales-invoice")
        .recordId(recordId).build();
  }
}
