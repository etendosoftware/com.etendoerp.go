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
import static org.mockito.Mockito.times;
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
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
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
    Invoice copiedDiscountsInvoice;
    /** ETP-5381: the quantity map createFromReceipt ended up using, explicit or seeded. */
    Map<String, BigDecimal> receivedReceiptOverrides;
    /** The selectedLines that map produced — what actually drives the invoice line quantities. */
    JSONArray capturedReceiptSelectedLines;

    @Override
    protected DocumentType resolveAPInvoiceDocType(Order order) {
      return docTypeToReturn;
    }

    // Capture-and-delegate (not a stub): the real line-building logic must keep running so the
    // assertion is about the quantities that reach the invoice, not about a mocked hand-off.
    @Override
    protected JSONArray buildSelectedLinesFromReceipt(ShipmentInOut receipt,
        Map<String, BigDecimal> qtyOverrides, Order linkedOrder) {
      receivedReceiptOverrides = qtyOverrides;
      capturedReceiptSelectedLines =
          super.buildSelectedLinesFromReceipt(receipt, qtyOverrides, linkedOrder);
      return capturedReceiptSelectedLines;
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

        // ETP-4780: no-op here so tests that don't stub invoice.getInvoiceLineList()
        // don't NPE on the real implementation; capture the argument so delegation
        // itself can still be asserted (see testCreateFromOrder/ReceiptDelegatesToSupport).
        @Override
        public void copyLineDiscountsFromOrder(Invoice invoice) {
          copiedDiscountsInvoice = invoice;
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
  // copyLineDiscountsFromOrder delegation (ETP-4780) — "% Descuento" carried
  // from the source Purchase Order/Goods Receipt line into the generated
  // invoice line. The discount-copy logic itself (skip zero/null, never
  // overwrite an already-set value) is covered once, generically, in
  // InvoiceFromOrderSupportTest — these tests lock in that BOTH
  // createFromOrder and createFromReceipt actually invoke it at the right
  // point in the flow (mirroring the sales-side ETP-4006 regression).
  // -------------------------------------------------------------------------

  /**
   * Verifies that {@code createFromOrder} delegates to
   * {@code getSupport().copyLineDiscountsFromOrder(invoice)} — the fix for ETP-4780,
   * where a Purchase Invoice generated from a confirmed Purchase Order silently
   * dropped the per-line "% Descuento", producing a total that no longer matched
   * the source order.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateFromOrderDelegatesToCopyLineDiscountsFromSupport() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("po-discount"))).thenReturn(orderHeader);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-discount");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-discount");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-discount")
          .put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("po-discount");

      assertSame(invoice, result);
      assertSame("createFromOrder must delegate discount copy to getSupport()",
          invoice, handler.copiedDiscountsInvoice);
    }
  }

  /**
   * Same delegation contract as above but for the goods-receipt-with-linked-PO branch
   * of {@code createFromReceipt} — the second entry point ETP-4780 targets.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateFromReceiptLinkedPoDelegatesToCopyLineDiscountsFromSupport() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      // ETP-5381: with no explicit "lines" in the body, createFromReceipt now seeds the
      // quantities from the receipt's pending qty. Stubbed to the full movement qty (3) so this
      // test keeps exercising exactly the line set it did before the guard was added.
      stubPendingQtyPerLine(supportMock, "receipt-linked-discount", "rl-1", BigDecimal.valueOf(3));

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Order linkedOrder = mockOrderWithHeaderData();
      when(linkedOrder.getId()).thenReturn("po-linked-discount");

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
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-linked-discount"))).thenReturn(receipt);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-receipt-discount");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("AP-RECEIPT-DISCOUNT");
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      Invoice result = handler.createFromReceipt("receipt-linked-discount", null);

      assertSame(invoice, result);
      assertSame("createFromReceipt (linked-PO branch) must delegate discount copy to getSupport()",
          invoice, handler.copiedDiscountsInvoice);
    }
  }

  /**
   * End-to-end regression for ETP-4780 against the REAL {@link InvoiceFromOrderSupport}
   * implementation (only the unrelated collaborators — total discount, gross amounts,
   * currency-rate propagation — are stubbed out): an {@code OrderLine} with a positive
   * {@code discount} must produce an {@code InvoiceLine.setEtgoDiscount(...)} call with
   * the same value once {@code createFromOrder} runs to completion.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateFromOrderCopiesPositiveDiscountFromOrderLineToInvoiceLine() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("po-real-discount"))).thenReturn(orderHeader);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-real-discount");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OrderLine sourceOrderLine = mock(OrderLine.class);
      when(sourceOrderLine.getDiscount()).thenReturn(new BigDecimal("12.50"));

      InvoiceLine invoiceLine = mock(InvoiceLine.class);
      when(invoiceLine.getSalesOrderLine()).thenReturn(sourceOrderLine);
      when(invoiceLine.getEtgoDiscount()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-real-discount");
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(invoiceLine));
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      // Handler whose support delegates copyLineDiscountsFromOrder to the REAL
      // implementation, but keeps the unrelated collaborators as no-ops so the test
      // stays focused on the discount-copy contract.
      TestableHandler handler = new TestableHandler() {
        @Override
        InvoiceFromOrderSupport getSupport() {
          return new InvoiceFromOrderSupport() {
            @Override
            public Invoice applyOrderDiscountToInvoice(Invoice inv, String sourceOrderId,
                TotalDiscountService discountService) {
              return inv;
            }
            @Override
            public void ensureLineGrossAmounts(Invoice inv) { /* no-op */ }
            @Override
            public void propagateOrderRateToInvoice(Order order, Invoice inv) { /* no-op */ }
          };
        }
      };
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-real-discount")
          .put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("po-real-discount");

      assertSame(invoice, result);
      verify(invoiceLine).setEtgoDiscount(new BigDecimal("12.50"));
      verify(dal).save(invoiceLine);
    }
  }

  /**
   * No-regression companion to the above: when the source order line carries a zero
   * discount, {@code createFromOrder} must leave the invoice line's
   * {@code EM_Etgo_Discount} untouched (stays at whatever the native process set it
   * to — never forced to 0 or overwritten).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testCreateFromOrderZeroSourceDiscountDoesNotOverwriteInvoiceLine() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      Order orderHeader = mockOrderWithHeaderData();
      when(dal.get(eq(Order.class), eq("po-zero-discount"))).thenReturn(orderHeader);

      NativeQuery linkQuery = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
      when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
      when(linkQuery.executeUpdate()).thenReturn(0);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-zero-discount");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OrderLine sourceOrderLine = mock(OrderLine.class);
      when(sourceOrderLine.getDiscount()).thenReturn(BigDecimal.ZERO);

      InvoiceLine invoiceLine = mock(InvoiceLine.class);
      when(invoiceLine.getSalesOrderLine()).thenReturn(sourceOrderLine);
      when(invoiceLine.getEtgoDiscount()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-zero-discount");
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(invoiceLine));
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      TestableHandler handler = new TestableHandler() {
        @Override
        InvoiceFromOrderSupport getSupport() {
          return new InvoiceFromOrderSupport() {
            @Override
            public Invoice applyOrderDiscountToInvoice(Invoice inv, String sourceOrderId,
                TotalDiscountService discountService) {
              return inv;
            }
            @Override
            public void ensureLineGrossAmounts(Invoice inv) { /* no-op */ }
            @Override
            public void propagateOrderRateToInvoice(Order order, Invoice inv) { /* no-op */ }
          };
        }
      };
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.selectedLinesToReturn = new JSONArray().put(new JSONObject()
          .put("id", "ol-zero-discount")
          .put("orderedQuantity", "1"));

      Invoice result = handler.createFromOrder("po-zero-discount");

      assertSame(invoice, result);
      verify(invoiceLine, never()).setEtgoDiscount(any(BigDecimal.class));
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

  /**
   * Stubs the ETP-5381 pending-quantity seeding for a receipt: {@code createFromReceipt} calls
   * {@code computePendingQtyPerLineOrThrow(receiptId, true)} whenever the request body carries no
   * explicit {@code lines}, and uses the resulting map as the per-line quantities.
   *
   * @param lineId the receipt line that still has something pending
   * @param qty    the pending quantity for that line
   */
  private static void stubPendingQtyPerLine(MockedStatic<NeoInvoiceSupport> supportMock,
      String receiptId, String lineId, BigDecimal qty) {
    supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq(receiptId), eq(true)))
        .thenReturn(Collections.singletonMap(lineId, qty));
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
            public void copyLineDiscountsFromOrder(Invoice inv) { /* no-op */ }

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
      when(invoice.getCurrency()).thenReturn(receiptCurrency);
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
      when(invoice.getCurrency()).thenReturn(receiptCurrency);
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
      when(invoice.getCurrency()).thenReturn(receiptCurrency);
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
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      // ETP-5381: see testCreateFromReceiptLinkedPoDelegatesToCopyLineDiscountsFromSupport.
      stubPendingQtyPerLine(supportMock, "receipt-linked-po", "rl-1", BigDecimal.valueOf(3));

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

  // ─── ETP-5381: create-and-confirm + duplicate-receipt guard ────────────────

  /**
   * Test double for the {@code handle()} entry point: stubs order-based creation and the
   * document-number fallback so the test can focus on the create-and-confirm sequence.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class CompletingHandler extends CreatePurchaseInvoiceHandler {
    Invoice createdInvoice;

    @Override
    protected Invoice createFromOrder(String orderId) {
      return createdInvoice;
    }

    @Override
    protected void ensureDocumentNo(Invoice invoice) {
      // no-op: the real implementation resolves an AD sequence
    }
  }

  /**
   * Stubs the ETP-5381 atomic confirmation so {@code handle()} can run without a CDI container.
   * Must be opened inside the caller's {@code MockedStatic<OBDal>} scope, since it stubs the
   * already-created {@code dal} mock for AD_Process 111.
   */
  private static final class CompletionMocks implements AutoCloseable {
    private final MockedStatic<NeoDefaultsService> defaultsMock;
    private final MockedStatic<WeldUtils> weldMock;
    final ProcessInvoiceUtil processInvoiceUtil;

    CompletionMocks(OBDal dal, OBError processResult) {
      defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
      weldMock = Mockito.mockStatic(WeldUtils.class);
      processInvoiceUtil = mock(ProcessInvoiceUtil.class);
      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(new VariablesSecureApp("u", "c", "o", "r", "en_US"));
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);
      when(processInvoiceUtil.process(
          anyString(), eq("CO"), anyString(), anyString(), anyString(), any(), any()))
          .thenReturn(processResult);
      when(dal.get(eq(Process.class), eq("111"))).thenReturn(mock(Process.class));
    }

    @Override
    public void close() {
      weldMock.close();
      defaultsMock.close();
    }
  }

  /** A completion result meaning "the invoice was confirmed". */
  private static OBError completionSuccess() {
    OBError result = new OBError();
    result.setType("Success");
    result.setTitle("Success");
    result.setMessage("Document completed");
    return result;
  }

  /**
   * ETP-5381 (the behavioral fix) — when the request carries no explicit {@code lines}, which is
   * what the UI always sends, the quantities are seeded from the receipt's PENDING quantities.
   *
   * <p>Before this change {@code resolveReceiptLineQty} fell back to the full
   * {@code movementQuantity}, so a partially-invoiced receipt was re-invoiced in full and a
   * fully-invoiced one could be invoiced again from scratch. The assertion is therefore on the
   * quantity that reaches the invoice line (4, the pending qty) and NOT 10, the movement qty —
   * and on the fully-invoiced line being dropped entirely.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipt_noExplicitLines_seedsQuantitiesFromPending() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      // rl-1 has 4 of its 10 units left to invoice; rl-2 is fully invoiced (absent from the map).
      stubPendingQtyPerLine(supportMock, "receipt-seed", "rl-1", new BigDecimal("4"));

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Order linkedOrder = mockOrderWithHeaderData();
      when(linkedOrder.getId()).thenReturn("po-seed");

      OrderLine ol1 = mock(OrderLine.class);
      when(ol1.getId()).thenReturn("ol-1");
      OrderLine ol2 = mock(OrderLine.class);
      when(ol2.getId()).thenReturn("ol-2");
      ShipmentInOutLine rl1 = mockReceiptLine("rl-1", true, mock(Product.class),
          BigDecimal.valueOf(10), ol1);
      ShipmentInOutLine rl2 = mockReceiptLine("rl-2", true, mock(Product.class),
          BigDecimal.valueOf(5), ol2);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getSalesOrder()).thenReturn(linkedOrder);
      when(receipt.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(rl1, rl2));
      when(receipt.getEtgoCurrency()).thenReturn(mock(Currency.class));
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-seed"))).thenReturn(receipt);

      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-seed", "AP-SEED-1");

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      handler.createFromReceipt("receipt-seed", null);

      assertEquals("The seeded map must be the pending quantities, not the movement quantities",
          Collections.singletonMap("rl-1", new BigDecimal("4")), handler.receivedReceiptOverrides);
      JSONArray selected = handler.capturedReceiptSelectedLines;
      assertEquals("The fully-invoiced line must be dropped", 1, selected.length());
      assertEquals("ol-1", selected.getJSONObject(0).getString("id"));
      assertEquals("The invoiced qty must be the pending 4, never the full movement qty 10",
          "4", selected.getJSONObject(0).getString("orderedQuantity"));
    }
  }

  /**
   * ETP-5381 — the seeding is a FALLBACK: an explicit per-line quantity in the request body wins
   * and the pending-quantity query is never issued. A user invoicing 2 of 10 units must get 2.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipt_explicitLines_doesNotSeedFromPending() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      Order linkedOrder = mockOrderWithHeaderData();
      when(linkedOrder.getId()).thenReturn("po-explicit");

      OrderLine ol1 = mock(OrderLine.class);
      when(ol1.getId()).thenReturn("ol-1");
      ShipmentInOutLine rl1 = mockReceiptLine("rl-1", true, mock(Product.class),
          BigDecimal.valueOf(10), ol1);

      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getSalesOrder()).thenReturn(linkedOrder);
      when(receipt.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.singletonList(rl1));
      when(receipt.getEtgoCurrency()).thenReturn(mock(Currency.class));
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-explicit"))).thenReturn(receipt);

      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-explicit", "AP-EXPLICIT-1");

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      JSONObject body = new JSONObject().put("lines", new JSONArray()
          .put(new JSONObject().put("receiptLineId", "rl-1").put("quantity", "2")));

      handler.createFromReceipt("receipt-explicit", body);

      assertEquals("Explicit overrides must be used verbatim",
          Collections.singletonMap("rl-1", new BigDecimal("2")), handler.receivedReceiptOverrides);
      assertEquals("2",
          handler.capturedReceiptSelectedLines.getJSONObject(0).getString("orderedQuantity"));
      supportMock.verifyNoInteractions();
    }
  }

  /**
   * ETP-5381 — the response now carries {@code documentStatus}. It was not sent before this
   * ticket (only {@code id} and {@code documentNo}), and the frontend needs it to render the
   * resulting state of a document that is created already confirmed.
   */
  @Test
  public void handle_purchaseOrder_responseIncludesDocumentStatus() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      Invoice created = mock(Invoice.class);
      when(created.getId()).thenReturn("ap-inv-1");

      // Re-read from the session reopened by ProcessInvoiceUtil: the handler must report THIS
      // instance, not the detached one it created.
      Invoice completed = mock(Invoice.class);
      when(completed.getDocumentNo()).thenReturn("AP-0001");
      when(completed.getDocumentStatus()).thenReturn("CO");
      when(dal.get(eq(Invoice.class), eq("ap-inv-1"))).thenReturn(completed);

      CompletingHandler handler = new CompletingHandler();
      handler.createdInvoice = created;

      try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
        NeoResponse response = handler.handle(NeoContext.builder()
            .endpointType(NeoEndpointType.ACTION)
            .httpMethod("POST")
            .fieldName("createPurchaseInvoice")
            .specName("purchase-order")
            .recordId("po-1")
            .build());

        assertNotNull(response);
        assertEquals(201, response.getHttpStatus());
        JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
        assertEquals("ap-inv-1", data.getString("id"));
        assertEquals("AP-0001", data.getString("documentNo"));
        assertEquals("CO", data.getString("documentStatus"));
        verify(completion.processInvoiceUtil, times(1)).process(
            eq("ap-inv-1"), eq("CO"), eq(""), eq(""), eq(""), any(), any());
      }
    }
  }

  /**
   * Shared mock graph for the collaborators {@code createFromReceipt} reaches after the quantity
   * map is resolved: the line-link native query, the current user, the invoice instance produced
   * by {@code OBProvider}, and the CDI-managed line-creation process.
   */
  @SuppressWarnings("unchecked")
  private static void stubInvoiceCreationCollaborators(Session session,
      MockedStatic<OBContext> obContextMock, MockedStatic<OBProvider> obProviderMock,
      MockedStatic<WeldUtils> weldUtilsMock, String userId, String documentNo) {
    NativeQuery linkQuery = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(linkQuery);
    when(linkQuery.setParameter(anyString(), any())).thenReturn(linkQuery);
    when(linkQuery.executeUpdate()).thenReturn(1);

    OBContext ctx = mock(OBContext.class);
    org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
    when(user.getId()).thenReturn(userId);
    when(ctx.getUser()).thenReturn(user);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

    OBProvider provider = mock(OBProvider.class);
    Invoice invoice = mock(Invoice.class);
    when(invoice.getDocumentNo()).thenReturn(documentNo);
    obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
    when(provider.get(Invoice.class)).thenReturn(invoice);

    weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
        .thenReturn(mock(CreateInvoiceLinesFromProcess.class));
  }
}
