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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for {@link CreatePurchaseInvoiceHandler}.
 *
 * <p>Single test below verifies the link-step that the handler runs after
 * delegating line creation to {@code CreateInvoiceLinesFromProcess}. Without
 * this step, m_inout_post can't create m_matchinv when the receipt is later
 * completed, leaving the delivery status column at 0% on purchase invoices
 * even after a corresponding receipt is completed.
 */
public class CreatePurchaseInvoiceHandlerTest {

  /**
   * Test double that overrides the helper methods that hit the DB or other
   * heavy collaborators, leaving createFromOrder focused on the link-step.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestableHandler extends CreatePurchaseInvoiceHandler {
    DocumentType docTypeToReturn;
    JSONArray selectedLinesToReturn;

    @Override
    protected DocumentType resolveAPInvoiceDocType(Order order) {
      return docTypeToReturn;
    }

    @Override
    protected JSONArray buildSelectedLines(Order order) {
      return selectedLinesToReturn;
    }

    @Override
    InvoiceFromOrderSupport getSupport() {
      return new InvoiceFromOrderSupport() {
        @Override
        public Invoice applyOrderDiscountToInvoice(Invoice invoice, String sourceOrderId,
            TotalDiscountService discountService) {
          // no-op: tested separately in InvoiceFromOrderSupportTest
          return invoice;
        }
        @Override
        public void ensureLineGrossAmounts(Invoice invoice) {
          // no-op: tested separately in InvoiceFromOrderSupportTest
        }
      };
    }
  }

  /** Stub an Order with the minimum header data the factory expects. */
  private static Order mockOrderWithHeaderData() {
    Order order = mock(Order.class);
    when(order.getClient()).thenReturn(mock(Client.class));
    when(order.getOrganization()).thenReturn(mock(Organization.class));
    when(order.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
    when(order.getPriceList()).thenReturn(mock(PriceList.class));
    when(order.getCurrency()).thenReturn(mock(Currency.class));
    when(order.getPaymentTerms()).thenReturn(mock(PaymentTerm.class));
    when(order.getPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));
    return order;
  }

  /**
   * Verifies that after creating the invoice and delegating line creation,
   * the handler runs the native UPDATE that links each new invoice line to
   * an existing receipt line of the same order line.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateFromOrderLinksInvoiceLinesToExistingInoutLines() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("po-1"))).thenReturn(orderHeader);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-3");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-PO");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-1")
          .put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("po-1");
      assertSame(invoice, result);

      verify(process).createInvoiceLinesFromDocumentLines(
          eq(handler.selectedLinesToReturn), eq(invoice), eq(OrderLine.class));

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue("SQL should target C_InvoiceLine", sql.contains("UPDATE C_InvoiceLine"));
      assertTrue("SQL should set M_InOutLine_ID", sql.contains("SET M_InOutLine_ID"));
      assertTrue("SQL should subquery MAX over M_InOutLine", sql.contains("MAX(iol.M_InOutLine_ID)"));
      assertTrue("SQL should join via C_OrderLine_ID", sql.contains("iol.C_OrderLine_ID = il.C_OrderLine_ID"));
      assertTrue("SQL should scope to the new invoice", sql.contains(":invoiceId"));
      assertTrue("SQL should only touch unlinked lines", sql.contains("M_InOutLine_ID IS NULL"));

      verify(linkQuery).setParameter(eq("userId"), eq("user-3"));
      verify(linkQuery).setParameter(eq("invoiceId"), eq("invoice-PO"));
      verify(linkQuery).executeUpdate();
    }
  }

  // -------------------------------------------------------------------------
  // buildSelectedLinesFromReceipt — ETP-4032: receipt-to-invoice line mapping
  // -------------------------------------------------------------------------

  private static ShipmentInOutLine mockReceiptLine(String id, boolean active, Product product,
      BigDecimal movementQty, OrderLine salesOrderLine) {
    ShipmentInOutLine rl = mock(ShipmentInOutLine.class);
    when(rl.getId()).thenReturn(id);
    when(rl.isActive()).thenReturn(active);
    when(rl.getProduct()).thenReturn(product);
    when(rl.getMovementQuantity()).thenReturn(movementQty);
    when(rl.getSalesOrderLine()).thenReturn(salesOrderLine);
    return rl;
  }

  private static ShipmentInOut receiptWith(ShipmentInOutLine... lines) {
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getMaterialMgmtShipmentInOutLineList())
        .thenReturn(Arrays.asList(lines));
    return receipt;
  }

  @Test
  public void buildSelectedLinesFromReceipt_usesMovementQtyWhenNoOverride() throws JSONException {
    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-1");
    Product product = mock(Product.class);
    when(product.getId()).thenReturn("prod-1");
    ShipmentInOutLine rl = mockReceiptLine("rl-1", true, product, BigDecimal.valueOf(3), ol);

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(rl), Collections.emptyMap(), null);

    assertEquals(1, result.length());
    assertEquals("ol-1", result.getJSONObject(0).getString("id"));
    assertEquals("3", result.getJSONObject(0).getString("orderedQuantity"));
  }

  @Test
  public void buildSelectedLinesFromReceipt_appliesQtyOverrideInsteadOfMovementQty() throws JSONException {
    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-1");
    ShipmentInOutLine rl = mockReceiptLine("rl-1", true, mock(Product.class), BigDecimal.valueOf(5), ol);

    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("rl-1", BigDecimal.valueOf(2));

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(rl), overrides, null);

    assertEquals(1, result.length());
    assertEquals("2", result.getJSONObject(0).getString("orderedQuantity"));
  }

  @Test
  public void buildSelectedLinesFromReceipt_skipsInactiveLine() throws JSONException {
    ShipmentInOutLine rl = mockReceiptLine("rl-1", false, mock(Product.class), BigDecimal.ONE, mock(OrderLine.class));

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(rl), Collections.emptyMap(), null);

    assertEquals(0, result.length());
  }

  @Test
  public void buildSelectedLinesFromReceipt_skipsLineWithNeitherDirectLinkNorProductMatch() throws JSONException {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn("prod-orphan");
    ShipmentInOutLine rl = mockReceiptLine("rl-1", true, product, BigDecimal.ONE, null);

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(rl), Collections.emptyMap(), null);

    assertEquals(0, result.length());
  }

  @Test
  public void buildSelectedLinesFromReceipt_fallsBackToProductMatchWhenNoDirectOrderLine() throws JSONException {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn("prod-1");

    ShipmentInOutLine rl = mockReceiptLine("rl-1", true, product, BigDecimal.valueOf(4), null);

    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-fallback");
    when(ol.isActive()).thenReturn(true);
    when(ol.getProduct()).thenReturn(product);

    Order linkedOrder = mock(Order.class);
    when(linkedOrder.getOrderLineList()).thenReturn(Collections.singletonList(ol));

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(rl), Collections.emptyMap(), linkedOrder);

    assertEquals(1, result.length());
    assertEquals("ol-fallback", result.getJSONObject(0).getString("id"));
    assertEquals("4", result.getJSONObject(0).getString("orderedQuantity"));
  }

  @Test
  public void buildSelectedLinesFromReceipt_skipsLineWhenQtyOverrideIsZero() throws JSONException {
    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-1");
    ShipmentInOutLine rl = mockReceiptLine("rl-1", true, mock(Product.class), BigDecimal.valueOf(3), ol);

    Map<String, BigDecimal> overrides = new HashMap<>();
    overrides.put("rl-1", BigDecimal.ZERO);

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(rl), overrides, null);

    assertEquals(0, result.length());
  }

  @Test
  public void buildSelectedLinesFromReceipt_multipleLines_onlyActiveWithOrderLineIncluded() throws JSONException {
    OrderLine ol1 = mock(OrderLine.class);
    when(ol1.getId()).thenReturn("ol-1");
    OrderLine ol2 = mock(OrderLine.class);
    when(ol2.getId()).thenReturn("ol-2");

    ShipmentInOutLine active = mockReceiptLine("rl-a", true, mock(Product.class), BigDecimal.valueOf(2), ol1);
    ShipmentInOutLine inactive = mockReceiptLine("rl-b", false, mock(Product.class), BigDecimal.ONE, ol2);
    ShipmentInOutLine noLink = mockReceiptLine("rl-c", true, mock(Product.class), BigDecimal.ONE, null);

    JSONArray result = new CreatePurchaseInvoiceHandler()
        .buildSelectedLinesFromReceipt(receiptWith(active, inactive, noLink), Collections.emptyMap(), null);

    assertEquals(1, result.length());
    assertEquals("ol-1", result.getJSONObject(0).getString("id"));
  }

  // ─── handle() dispatch guards ─────────────────────────────────────────────

  @Test
  public void handle_nonActionEndpoint_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .fieldName("createPurchaseInvoice")
        .specName("purchase-order")
        .build()));
  }

  @Test
  public void handle_wrongActionName_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName("otherAction")
        .specName("purchase-order")
        .build()));
  }

  @Test
  public void handle_getMethod_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("GET")
        .fieldName("createPurchaseInvoice")
        .specName("purchase-order")
        .build()));
  }

  @Test
  public void handle_unknownSpec_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName("createPurchaseInvoice")
        .specName("sales-order")
        .build()));
  }

  @Test
  public void handle_blankRecordId_returns400() {
    NeoResponse r = new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName("createPurchaseInvoice")
        .specName("purchase-order")
        .recordId("")
        .build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  // ─── buildSelectedLines (also covers getPendingQuantity logic) ────────────

  @Test
  public void buildSelectedLines_skipsInactiveLine() {
    OrderLine inactive = mock(OrderLine.class);
    when(inactive.isActive()).thenReturn(false);
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(inactive));

    assertEquals(0, new CreatePurchaseInvoiceHandler().buildSelectedLines(order).length());
  }

  @Test
  public void buildSelectedLines_skipsLineWithNullProduct() {
    OrderLine line = mock(OrderLine.class);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(null);
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(line));

    assertEquals(0, new CreatePurchaseInvoiceHandler().buildSelectedLines(order).length());
  }

  @Test
  public void buildSelectedLines_skipsFullyInvoicedLine() {
    OrderLine line = mock(OrderLine.class);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(mock(Product.class));
    when(line.getOrderedQuantity()).thenReturn(BigDecimal.valueOf(3));
    when(line.getInvoicedQuantity()).thenReturn(BigDecimal.valueOf(3));
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(line));

    assertEquals(0, new CreatePurchaseInvoiceHandler().buildSelectedLines(order).length());
  }

  @Test
  public void buildSelectedLines_includesPendingLine() throws JSONException {
    OrderLine line = mock(OrderLine.class);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(mock(Product.class));
    when(line.getId()).thenReturn("ol-pending");
    when(line.getOrderedQuantity()).thenReturn(BigDecimal.valueOf(5));
    when(line.getInvoicedQuantity()).thenReturn(BigDecimal.valueOf(2));
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(line));

    JSONArray result = new CreatePurchaseInvoiceHandler().buildSelectedLines(order);
    assertEquals(1, result.length());
    assertEquals("ol-pending", result.getJSONObject(0).getString("id"));
    assertEquals("3", result.getJSONObject(0).getString("orderedQuantity"));
  }

  // ── createFromOrder — propagateOrderRateToInvoice wiring ────────────────────

  /**
   * Verifies that after invoice creation {@code createFromOrder} calls
   * {@code InvoiceFromOrderSupport.propagateOrderRateToInvoice(order, invoice)}.
   *
   * <p>The test uses a subclass that overrides {@code getSupport()} to return a
   * spy over a minimal {@link InvoiceFromOrderSupport}, allowing verification
   * of the delegation without any real JDBC or CDI wiring.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromOrder_propagateOrderRateToInvoiceIsCalled() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("po-rate-test"))).thenReturn(orderHeader);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-rate");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-rate");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      // Track whether propagateOrderRateToInvoice was called with the correct arguments.
      AtomicReference<Order> capturedOrder = new AtomicReference<>();
      AtomicReference<Invoice> capturedInvoice = new AtomicReference<>();

      TestableHandler handler = new TestableHandler() {
        @Override
        InvoiceFromOrderSupport getSupport() {
          return new InvoiceFromOrderSupport() {
            @Override
            public Invoice applyOrderDiscountToInvoice(Invoice inv, String sourceOrderId,
                TotalDiscountService svc) {
              return inv;
            }
            @Override
            public void ensureLineGrossAmounts(Invoice inv) { /* no-op */ }

            @Override
            public void propagateOrderRateToInvoice(Order order, Invoice inv) {
              capturedOrder.set(order);
              capturedInvoice.set(inv);
            }
          };
        }
      };
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-1").put("orderedQuantity", "2"));

      handler.createFromOrder("po-rate-test");

      assertSame("propagateOrderRateToInvoice must receive the source order",
          orderHeader, capturedOrder.get());
      assertSame("propagateOrderRateToInvoice must receive the created invoice",
          invoice, capturedInvoice.get());
    }
  }

  /**
   * Mirrors the sales path: when {@code getSupport()} returns a support instance
   * that never calls {@code propagateOrderRateToInvoice}, the test verifies the
   * OLD behaviour (before ETP-4027) is absent — i.e. by explicitly overriding
   * propagateOrderRateToInvoice with a no-op, the method must NOT throw and the
   * invoice must be returned normally. This confirms the guard is non-blocking.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromOrder_propagateOrderRateToInvoice_noopDoesNotBreakFlow() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("po-noop"))).thenReturn(orderHeader);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-noop");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-noop");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-2").put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("po-noop");
      assertSame(invoice, result);
    }
  }

  // ─── resolvePriceListOverride / applyPriceListOverride (ETP-4028) ─────────
  //
  // Both are private helpers with no protected seam, so they are exercised via
  // reflection — the same convention already used elsewhere in this codebase
  // (see e.g. McpSchemaFieldBuilderTest) for private-method coverage.

  private static Object invokeResolvePriceListOverride(CreatePurchaseInvoiceHandler handler,
      JSONObject body) throws Exception {
    Method method = CreatePurchaseInvoiceHandler.class.getDeclaredMethod(
        "resolvePriceListOverride", JSONObject.class);
    method.setAccessible(true);
    return method.invoke(handler, body);
  }

  private static void invokeApplyPriceListOverride(CreatePurchaseInvoiceHandler handler,
      Invoice invoice, JSONObject body) throws Exception {
    Method method = CreatePurchaseInvoiceHandler.class.getDeclaredMethod(
        "applyPriceListOverride", Invoice.class, JSONObject.class);
    method.setAccessible(true);
    method.invoke(handler, invoice, body);
  }

  @Test
  public void resolvePriceListOverride_nullBody_returnsNull() throws Exception {
    assertNull(invokeResolvePriceListOverride(new CreatePurchaseInvoiceHandler(), null));
  }

  @Test
  public void resolvePriceListOverride_missingKey_returnsNull() throws Exception {
    JSONObject body = new JSONObject();
    assertNull(invokeResolvePriceListOverride(new CreatePurchaseInvoiceHandler(), body));
  }

  @Test
  public void resolvePriceListOverride_blankPriceListId_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("priceListId", "   ");
    assertNull(invokeResolvePriceListOverride(new CreatePurchaseInvoiceHandler(), body));
  }

  @Test
  public void resolvePriceListOverride_validId_returnsResolvedPriceList() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList priceList = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-42")).thenReturn(priceList);

      JSONObject body = new JSONObject().put("priceListId", "pl-42");
      Object result = invokeResolvePriceListOverride(new CreatePurchaseInvoiceHandler(), body);

      assertSame(priceList, result);
    }
  }

  @Test
  public void resolvePriceListOverride_idDoesNotResolve_returnsNull() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(PriceList.class, "pl-missing")).thenReturn(null);

      JSONObject body = new JSONObject().put("priceListId", "pl-missing");
      assertNull(invokeResolvePriceListOverride(new CreatePurchaseInvoiceHandler(), body));
    }
  }

  @Test
  public void applyPriceListOverride_nullResolution_neverCallsSetPriceList() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      invokeApplyPriceListOverride(new CreatePurchaseInvoiceHandler(), invoice, null);

      verify(invoice, never()).setPriceList(any(PriceList.class));
    }
  }

  @Test
  public void applyPriceListOverride_resolvedPriceList_callsSetPriceList() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      PriceList priceList = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-99")).thenReturn(priceList);

      Invoice invoice = mock(Invoice.class);
      JSONObject body = new JSONObject().put("priceListId", "pl-99");
      invokeApplyPriceListOverride(new CreatePurchaseInvoiceHandler(), invoice, body);

      verify(invoice).setPriceList(priceList);
    }
  }

  // ─── createFromReceiptNoPo (ETP-4028 — price-list override) ───────────────

  private static ShipmentInOut receiptNoPoWith(BusinessPartner bp, ShipmentInOutLine... lines) {
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getBusinessPartner()).thenReturn(bp);
    when(receipt.getClient()).thenReturn(mock(Client.class));
    when(receipt.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(lines));
    return receipt;
  }

  /** Stubs the OBDal.createCriteria(DocumentType.class) chain used by findAPInvoiceDocType. */
  @SuppressWarnings("unchecked")
  private static void stubApInvoiceDocType(OBDal dal, DocumentType docType) {
    OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(docType));
  }

  private static ShipmentInOutLine mockNoPoLine(BigDecimal movementQty) {
    ShipmentInOutLine rl = mock(ShipmentInOutLine.class);
    when(rl.getId()).thenReturn("rl-nopo-1");
    when(rl.isActive()).thenReturn(true);
    when(rl.getProduct()).thenReturn(mock(Product.class));
    when(rl.getUOM()).thenReturn(mock(UOM.class));
    when(rl.getMovementQuantity()).thenReturn(movementQty);
    return rl;
  }

  /**
   * Verifies that the backward-compatible 2-arg {@code createFromReceiptNoPo} overload
   * behaves identically to calling the 3-arg version with {@code body=null}: the invoice
   * ends up with the business partner's purchase price list, since there is no override
   * to apply.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceiptNoPo_twoArgOverload_fallsBackToBusinessPartnerPriceList() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      BusinessPartner bp = mock(BusinessPartner.class);
      PriceList bpPriceList = mock(PriceList.class);
      when(bp.getPurchasePricelist()).thenReturn(bpPriceList);
      when(bp.getPOPaymentTerms()).thenReturn(mock(PaymentTerm.class));
      when(bp.getPOPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));

      ShipmentInOut receipt = receiptNoPoWith(bp, mockNoPoLine(BigDecimal.valueOf(2)));
      // ETP-4028: the invoice's currency comes from the receipt's own currency,
      // never from the (possibly absent) purchase price list's currency.
      Currency receiptCurrency = mock(Currency.class);
      when(receipt.getEtgoCurrency()).thenReturn(receiptCurrency);

      DocumentType docType = mock(DocumentType.class);
      stubApInvoiceDocType(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("AP-NOPO-1");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      Invoice result = new CreatePurchaseInvoiceHandler()
          .createFromReceiptNoPo(receipt, Collections.emptyMap());

      assertSame(invoice, result);
      verify(invoice).setPriceList(bpPriceList);
      verify(invoice).setCurrency(receiptCurrency);
    }
  }

  /**
   * Verifies that when the request body carries a valid {@code priceListId}, the 3-arg
   * {@code createFromReceiptNoPo} overload uses that price list INSTEAD of the business
   * partner's purchase price list.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceiptNoPo_threeArg_bodyPriceListOverridesBusinessPartnerDefault() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      BusinessPartner bp = mock(BusinessPartner.class);
      PriceList bpPriceList = mock(PriceList.class);
      when(bp.getPurchasePricelist()).thenReturn(bpPriceList);
      when(bp.getPOPaymentTerms()).thenReturn(mock(PaymentTerm.class));
      when(bp.getPOPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));

      PriceList overridePriceList = mock(PriceList.class);
      when(dal.get(PriceList.class, "pl-override")).thenReturn(overridePriceList);

      ShipmentInOut receipt = receiptNoPoWith(bp, mockNoPoLine(BigDecimal.valueOf(3)));
      // ETP-4028: the invoice's currency comes from the receipt's own currency,
      // never from the (user-selected) override price list's currency.
      Currency receiptCurrency = mock(Currency.class);
      when(receipt.getEtgoCurrency()).thenReturn(receiptCurrency);

      DocumentType docType = mock(DocumentType.class);
      stubApInvoiceDocType(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("AP-NOPO-2");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      JSONObject body = new JSONObject().put("priceListId", "pl-override");
      Invoice result = new CreatePurchaseInvoiceHandler()
          .createFromReceiptNoPo(receipt, Collections.emptyMap(), body);

      assertSame(invoice, result);
      verify(invoice).setPriceList(overridePriceList);
      verify(invoice).setCurrency(receiptCurrency);
      verify(invoice, never()).setPriceList(bpPriceList);
    }
  }

  /**
   * Verifies that when the body is present but {@code priceListId} is absent/blank, the
   * 3-arg overload falls back to the business partner's purchase price list — same as the
   * 2-arg overload.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceiptNoPo_threeArg_blankPriceListIdFallsBackToBusinessPartnerDefault() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      BusinessPartner bp = mock(BusinessPartner.class);
      PriceList bpPriceList = mock(PriceList.class);
      when(bp.getPurchasePricelist()).thenReturn(bpPriceList);
      when(bp.getPOPaymentTerms()).thenReturn(mock(PaymentTerm.class));
      when(bp.getPOPaymentMethod()).thenReturn(mock(FIN_PaymentMethod.class));

      ShipmentInOut receipt = receiptNoPoWith(bp, mockNoPoLine(BigDecimal.valueOf(1)));
      // ETP-4028: the invoice's currency comes from the receipt's own currency,
      // never from the (possibly absent) purchase price list's currency.
      Currency receiptCurrency = mock(Currency.class);
      when(receipt.getEtgoCurrency()).thenReturn(receiptCurrency);

      DocumentType docType = mock(DocumentType.class);
      stubApInvoiceDocType(dal, docType);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("AP-NOPO-3");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      JSONObject body = new JSONObject().put("priceListId", "");
      Invoice result = new CreatePurchaseInvoiceHandler()
          .createFromReceiptNoPo(receipt, Collections.emptyMap(), body);

      assertSame(invoice, result);
      verify(invoice).setPriceList(bpPriceList);
      verify(invoice).setCurrency(receiptCurrency);
    }
  }

  // ─── createFromReceipt — linked-PO branch (ETP-4028 / ETP-4314) ───────────

  /**
   * Verifies that the linked-PO branch of {@code createFromReceipt} overrides the
   * invoice's currency with the receipt's own {@code EM_Etgo_Currency_ID} — read
   * BEFORE the receipt/its lines are evicted from the Hibernate session (a lazy FK
   * read on a detached entity would throw {@code LazyInitializationException}) —
   * rather than the linked purchase order's currency.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipt_linkedPo_setsCurrencyFromReceiptBeforeEvict() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Order linkedOrder = mockOrderWithHeaderData();
      when(linkedOrder.getId()).thenReturn("po-linked");

      OrderLine ol = mock(OrderLine.class);
      when(ol.getId()).thenReturn("ol-1");
      Product product = mock(Product.class);
      when(product.getId()).thenReturn("prod-1");
      ShipmentInOutLine rl = mockReceiptLine("rl-1", true, product, BigDecimal.valueOf(3), ol);

      Currency receiptCurrency = mock(Currency.class);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getSalesOrder()).thenReturn(linkedOrder);
      when(receipt.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.singletonList(rl));
      when(receipt.getEtgoCurrency()).thenReturn(receiptCurrency);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-linked-po"))).thenReturn(receipt);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-receipt");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      // Avoid ensureDocumentNo() falling through to the real (unmocked) Utility helper.
      when(invoice.getDocumentNo()).thenReturn("AP-RECEIPT-1");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      Invoice result = handler.createFromReceipt("receipt-linked-po", null);

      assertSame(invoice, result);
      verify(invoice).setCurrency(receiptCurrency);
      // The lazy FK read that produces receiptCurrency must happen before eviction.
      verify(session).evict(rl);
      verify(session).evict(receipt);
    }
  }
}
