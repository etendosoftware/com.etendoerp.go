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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.AttributeSetInstance;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link InvoiceInOutMapping} (ETP-5576): the invoice side of an invoice → goods
 * movement follow-up — the neutral header/lines handed to {@link InOutTargetBuilder}, the
 * warehouse and order of the new movement, and the line linker of each direction. The
 * warehouse-resolution rule is unchanged from the removed {@code CreateInvoiceShipmentHandler}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInOutMapping
 */
class InvoiceInOutMappingTest {

  private MockedStatic<OBDal> obDalStatic;
  private OBDal dal;

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    obDalStatic.close();
  }

  // ── map ───────────────────────────────────────────────────────────────────

  @Test
  void mapOfAMissingInvoiceIsNotFound() {
    when(dal.get(Invoice.class, "inv-gone")).thenReturn(null);
    List<PendingResolver.SourceLine> pending = Collections.singletonList(
        new PendingResolver.SourceLine("il-1", BigDecimal.ONE));

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> InvoiceInOutMapping.map("inv-gone", pending));

    assertEquals(FollowUpException.Reason.NOT_FOUND, e.getReason());
  }

  /**
   * A pending line whose invoice line vanished is NOT_FOUND, rejected before the movement is
   * shaped: no warehouse lookup, and the header (client, order, warehouse) is never read.
   */
  @Test
  void mapOfAMissingInvoiceLineIsNotFoundBeforeBuildingTheMovement() {
    Invoice invoice = invoice("inv-1", null);
    when(dal.get(InvoiceLine.class, "il-gone")).thenReturn(null);
    List<PendingResolver.SourceLine> pending = Collections.singletonList(
        new PendingResolver.SourceLine("il-gone", BigDecimal.ONE));

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> InvoiceInOutMapping.map("inv-1", pending));

    assertEquals(FollowUpException.Reason.NOT_FOUND, e.getReason());
    verify(dal, never()).createCriteria(Warehouse.class);
    verify(invoice, never()).getSalesOrder();
    verify(invoice, never()).getClient();
  }

  /**
   * One missing line among several still rejects the whole mapping, even when an earlier line
   * resolved: no partial Mapping, no warehouse lookup.
   */
  @Test
  void mapIsNotFoundWhenALaterPendingLineIsMissingAfterAnEarlierOneResolved() {
    Invoice invoice = invoice("inv-1", null);
    InvoiceLine first = mock(InvoiceLine.class);
    when(dal.get(InvoiceLine.class, "il-1")).thenReturn(first);
    when(dal.get(InvoiceLine.class, "il-gone")).thenReturn(null);
    List<PendingResolver.SourceLine> pending = Arrays.asList(
        new PendingResolver.SourceLine("il-1", BigDecimal.ONE),
        new PendingResolver.SourceLine("il-gone", BigDecimal.ONE));

    FollowUpException e = assertThrows(FollowUpException.class,
        () -> InvoiceInOutMapping.map("inv-1", pending));

    assertEquals(FollowUpException.Reason.NOT_FOUND, e.getReason());
    verify(dal).get(InvoiceLine.class, "il-gone");
    verify(dal, never()).createCriteria(Warehouse.class);
    verify(invoice, never()).getSalesOrder();
    verify(invoice, never()).getClient();
  }

  @Test
  void mapCarriesTheInvoiceHeaderAndEachLineWithTheResolverQuantity() throws Exception {
    Order order = order("ord-1");
    Warehouse orderWarehouse = mock(Warehouse.class);
    when(order.getWarehouse()).thenReturn(orderWarehouse);
    Invoice invoice = invoice("inv-1", order);
    Client client = mock(Client.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    Location address = mock(Location.class);
    Currency currency = mock(Currency.class);
    when(invoice.getClient()).thenReturn(client);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(invoice.getPartnerAddress()).thenReturn(address);
    when(invoice.getCurrency()).thenReturn(currency);
    Product product = product(true, "I");
    UOM uom = mock(UOM.class);
    AttributeSetInstance asi = mock(AttributeSetInstance.class);
    InvoiceLine il = lineFrom(order);
    when(il.getProduct()).thenReturn(product);
    when(il.getUOM()).thenReturn(uom);
    when(il.getAttributeSetValue()).thenReturn(asi);
    when(il.getDescription()).thenReturn("first delivery");
    when(dal.get(InvoiceLine.class, "il-1")).thenReturn(il);
    OrderLine orderLine = il.getSalesOrderLine();

    InOutFollowUpCreator.Mapping mapping = InvoiceInOutMapping.map("inv-1",
        Collections.singletonList(new PendingResolver.SourceLine("il-1", new BigDecimal("4"))));

    InOutTargetBuilder.Header header = mapping.getHeader();
    assertSame(client, field(header, "client"));
    assertSame(invoice.getOrganization(), field(header, "organization"));
    assertSame(bp, field(header, "businessPartner"));
    assertSame(address, field(header, "partnerAddress"));
    assertSame(currency, field(header, "currency"));
    assertSame(orderWarehouse, field(header, "warehouse"));
    assertSame(order, field(header, "order"));
    assertEquals(1, mapping.getLines().size());
    InOutTargetBuilder.Line line = mapping.getLines().get(0);
    assertEquals("il-1", line.getSourceLineId());
    assertSame(product, field(line, "product"));
    assertSame(uom, field(line, "uom"));
    assertSame(asi, field(line, "attributeSetValue"));
    assertSame(orderLine, field(line, "orderLine"));
    assertEquals("first delivery", field(line, "description"));
    assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) field(line, "quantity")),
        "the quantity is the resolver's, not the invoice line's");
  }

  /** "Needs a storage bin" = a stocked Item; a service, a non-stocked item or no product do not. */
  @ParameterizedTest(name = "product={0} isStocked={1} type={2} -> stockable={3}")
  @CsvSource({
      "true,true,I,true",
      "true,true,S,false",
      "true,false,I,false",
      "false,,,false",
  })
  void mapMarksALineStockableOnlyForAStockedItem(boolean hasProduct, Boolean isStocked,
      String productType, boolean expected) {
    Order order = order("ord-1");
    Warehouse warehouse = mock(Warehouse.class);
    when(order.getWarehouse()).thenReturn(warehouse);
    invoice("inv-1", order);
    InvoiceLine il = mock(InvoiceLine.class);
    Product product = hasProduct ? product(isStocked, productType) : null;
    when(il.getProduct()).thenReturn(product);
    when(dal.get(InvoiceLine.class, "il-1")).thenReturn(il);

    InOutFollowUpCreator.Mapping mapping = InvoiceInOutMapping.map("inv-1",
        Collections.singletonList(new PendingResolver.SourceLine("il-1", BigDecimal.ONE)));

    assertEquals(expected, mapping.getLines().get(0).isStockable());
  }

  // ── resolveWarehouse ──────────────────────────────────────────────────────

  @Test
  void resolveWarehousePrefersTheInvoiceOrderWarehouse() {
    Warehouse invoiceOrderWarehouse = mock(Warehouse.class);
    Order invoiceOrder = order("ord-inv");
    when(invoiceOrder.getWarehouse()).thenReturn(invoiceOrderWarehouse);
    Order lineOrder = order("ord-line");
    when(lineOrder.getWarehouse()).thenReturn(mock(Warehouse.class));
    Invoice invoice = invoice("inv-1", invoiceOrder);

    assertSame(invoiceOrderWarehouse, InvoiceInOutMapping.resolveWarehouse(invoice,
        Collections.singletonList(lineFrom(lineOrder))));
  }

  @Test
  void resolveWarehouseTakesTheFirstCarriedLineOrderWithAWarehouseWhenTheInvoiceHasNoOrder() {
    Warehouse lineWarehouse = mock(Warehouse.class);
    Order orderWithoutWarehouse = order("ord-no-wh");
    Order lineOrder = order("ord-line");
    when(lineOrder.getWarehouse()).thenReturn(lineWarehouse);
    Invoice invoice = invoice("inv-1", null);
    List<InvoiceLine> lines = Arrays.asList(lineFrom(null), lineFrom(orderWithoutWarehouse),
        lineFrom(lineOrder));

    assertSame(lineWarehouse, InvoiceInOutMapping.resolveWarehouse(invoice, lines));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveWarehouseFallsBackToTheFirstActiveWarehouseOfTheOrganization() {
    Warehouse orgWarehouse = mock(Warehouse.class);
    Invoice invoice = invoice("inv-1", null);
    OBCriteria<Warehouse> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Warehouse.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(orgWarehouse));

    assertSame(orgWarehouse, InvoiceInOutMapping.resolveWarehouse(invoice,
        Collections.singletonList(lineFrom(null))));
  }

  // ── resolveOrder ──────────────────────────────────────────────────────────

  @Test
  void resolveOrderPrefersTheInvoiceOwnOrder() {
    Order invoiceOrder = order("ord-inv");
    Invoice invoice = invoice("inv-1", invoiceOrder);
    List<InvoiceLine> lines = Collections.singletonList(lineFrom(order("ord-other")));

    assertSame(invoiceOrder, InvoiceInOutMapping.resolveOrder(invoice, lines));
  }

  @Test
  void resolveOrderUsesTheSingleOrderEveryCarriedLineComesFrom() {
    Order lineOrder = order("ord-1");
    List<InvoiceLine> lines = Arrays.asList(lineFrom(lineOrder), lineFrom(order("ord-1")));

    assertSame(lineOrder, InvoiceInOutMapping.resolveOrder(invoice("inv-1", null), lines));
  }

  @Test
  void resolveOrderIsNoneWhenLinesComeFromSeveralOrdersOrFromNone() {
    Invoice invoice = invoice("inv-1", null);
    List<InvoiceLine> twoOrders = Arrays.asList(lineFrom(order("ord-1")), lineFrom(order("ord-2")));
    List<InvoiceLine> oneWithoutOrder = Arrays.asList(lineFrom(order("ord-1")), lineFrom(null));

    assertNull(InvoiceInOutMapping.resolveOrder(invoice, twoOrders));
    assertNull(InvoiceInOutMapping.resolveOrder(invoice, oneWithoutOrder));
  }

  // ── linker ────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0} links through {1}")
  @CsvSource({ "SALES,SALES", "PURCHASE,PURCHASE" })
  void linkerLinksTheSourceLineToTheCreatedLineThroughTheDirectionMatchTable(String direction,
      String matchTable) {
    InOutTargetBuilder.LineLinker linker =
        InvoiceInOutMapping.linker(InOutTargetBuilder.Direction.valueOf(direction));
    InOutTargetBuilder.Line line = InOutTargetBuilder.Line.builder().sourceLineId("il-1")
        .quantity(BigDecimal.ONE).stockable(false).build();
    ShipmentInOutLine created = mock(ShipmentInOutLine.class);
    when(created.getId()).thenReturn("iol-1");

    try (MockedStatic<InvoiceLineLinker> linkerStatic = mockStatic(InvoiceLineLinker.class)) {
      linker.link(line, created);

      linkerStatic.verify(() -> InvoiceLineLinker.linkInvoiceLineToInOutLine("il-1", "iol-1",
          InOutInvoiceLinks.MatchTable.valueOf(matchTable)));
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Invoice invoice(String id, Order order) {
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(invoice.getSalesOrder()).thenReturn(order);
    when(invoice.getOrganization()).thenReturn(org);
    when(dal.get(Invoice.class, id)).thenReturn(invoice);
    return invoice;
  }

  private static Order order(String id) {
    Order order = mock(Order.class);
    when(order.getId()).thenReturn(id);
    return order;
  }

  private static Product product(Boolean isStocked, String productType) {
    Product product = mock(Product.class);
    when(product.isStocked()).thenReturn(isStocked);
    when(product.getProductType()).thenReturn(productType);
    return product;
  }

  /** An invoice line whose order line belongs to {@code order}; {@code null} = no order line. */
  private static InvoiceLine lineFrom(Order order) {
    InvoiceLine line = mock(InvoiceLine.class);
    if (order != null) {
      OrderLine orderLine = mock(OrderLine.class);
      when(orderLine.getSalesOrder()).thenReturn(order);
      when(line.getSalesOrderLine()).thenReturn(orderLine);
    }
    return line;
  }

  /** Header and Line are builder inputs without getters; read the mapped value directly. */
  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
