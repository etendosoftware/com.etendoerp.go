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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
}
