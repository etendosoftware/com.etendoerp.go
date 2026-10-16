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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.exception.OBException;
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
 *
 * @covers com.etendoerp.go.schemaforge.CreatePurchaseInvoiceHandler
 * @covers com.etendoerp.go.schemaforge.OrderInvoiceListSupport
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
    when(order.getDocumentStatus()).thenReturn("CO");
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
      when(receipt.getDocumentStatus()).thenReturn("CO");
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
    when(receipt.getDocumentStatus()).thenReturn("CO");
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

  private static Order orderWithLine(BigDecimal ordered, BigDecimal invoiced) {
    OrderLine line = mock(OrderLine.class);
    when(line.isActive()).thenReturn(true);
    when(line.getProduct()).thenReturn(mock(Product.class));
    when(line.getId()).thenReturn("ol-1");
    when(line.getOrderedQuantity()).thenReturn(ordered);
    when(line.getInvoicedQuantity()).thenReturn(invoiced);
    Order order = mock(Order.class);
    when(order.getOrderLineList()).thenReturn(Collections.singletonList(line));
    return order;
  }

  /**
   * ETP-5709 (item 3): a line ordered 2 and invoiced 4 has pending -2 — over-invoiced, not
   * pending. It used to be selected and would have created a -2 invoice line.
   */
  @Test
  public void buildSelectedLines_overInvoicedLine_isNotSelected() {
    Order order = orderWithLine(BigDecimal.valueOf(2), BigDecimal.valueOf(4));

    assertEquals(0, new CreatePurchaseInvoiceHandler().buildSelectedLines(order).length());
  }

  /** ETP-5709 — same rule for a negative (return-style) line over-invoiced in its own direction. */
  @Test
  public void buildSelectedLines_overInvoicedNegativeLine_isNotSelected() {
    Order order = orderWithLine(BigDecimal.valueOf(-3), BigDecimal.valueOf(-5));

    assertEquals(0, new CreatePurchaseInvoiceHandler().buildSelectedLines(order).length());
  }

  /** ETP-4722/ETP-4567 must keep working: ordered -3, invoiced 0 is a legit -3 pending line. */
  @Test
  public void buildSelectedLines_legitNegativeLine_isStillInvoiced() throws JSONException {
    Order order = orderWithLine(BigDecimal.valueOf(-3), BigDecimal.ZERO);

    JSONArray result = new CreatePurchaseInvoiceHandler().buildSelectedLines(order);

    assertEquals(1, result.length());
    assertEquals("-3", result.getJSONObject(0).getString("orderedQuantity"));
  }

  /** Order-route pending is ORDERED-based (ordered - invoiced): a partially received line stays pending in full. */
  @Test
  public void buildSelectedLines_isOrderedBasedNotReceivedBased() throws JSONException {
    Order order = orderWithLine(BigDecimal.valueOf(5), BigDecimal.ZERO);
    when(order.getOrderLineList().get(0).getDeliveredQuantity()).thenReturn(BigDecimal.valueOf(3));

    JSONArray result = new CreatePurchaseInvoiceHandler().buildSelectedLines(order);

    assertEquals("5", result.getJSONObject(0).getString("orderedQuantity"));
  }

  /** A partially invoiced negative line keeps its remaining (negative) quantity. */
  @Test
  public void buildSelectedLines_partiallyInvoicedNegativeLine_keepsRemainingNegativeQuantity()
      throws JSONException {
    Order order = orderWithLine(BigDecimal.valueOf(-3), BigDecimal.valueOf(-1));

    JSONArray result = new CreatePurchaseInvoiceHandler().buildSelectedLines(order);

    assertEquals("-2", result.getJSONObject(0).getString("orderedQuantity"));
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
    when(receipt.getDocumentStatus()).thenReturn("CO");
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
      when(receipt.getDocumentStatus()).thenReturn("CO");
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

      when(receipt.getDocumentStatus()).thenReturn("CO");
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
   * Builds a completed receipt linked to a purchase order, registered in {@code dal} under
   * {@code receiptId}, for the {@code createFromReceipt} explicit-{@code lines} tests.
   */
  private static ShipmentInOut stubReceiptWithOrder(OBDal dal, String receiptId, String poId,
      ShipmentInOutLine... lines) {
    Order linkedOrder = mockOrderWithHeaderData();
    when(linkedOrder.getId()).thenReturn(poId);
    ShipmentInOut receipt = mock(ShipmentInOut.class);
    when(receipt.getDocumentNo()).thenReturn("GR-" + receiptId);
    when(receipt.getDocumentStatus()).thenReturn("CO");
    when(receipt.getSalesOrder()).thenReturn(linkedOrder);
    when(receipt.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(lines));
    when(receipt.getEtgoCurrency()).thenReturn(mock(Currency.class));
    when(dal.get(eq(ShipmentInOut.class), eq(receiptId))).thenReturn(receipt);
    return receipt;
  }

  private static ShipmentInOutLine receiptLineWithOrderLine(String id, String movementQty) {
    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-" + id);
    ShipmentInOutLine rl = mockReceiptLine(id, true, mock(Product.class),
        new BigDecimal(movementQty), ol);
    when(rl.getLineNo()).thenReturn(10L);
    return rl;
  }

  private static JSONObject explicitLines(String lineId, String quantity) throws JSONException {
    return new JSONObject().put("lines", new JSONArray()
        .put(new JSONObject().put("receiptLineId", lineId).put("quantity", quantity)));
  }

  /**
   * ETP-5709 — an explicit quantity within what is pending is used VERBATIM (2 of 10 means 2),
   * and the pending query is now issued for it too, because that is what the quantity is
   * validated against. (Before ETP-5709 explicit lines skipped the query entirely.)
   */
  @Test
  public void createFromReceipt_explicitLinesWithinPending_usedVerbatim() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      stubReceiptWithOrder(dal, "receipt-explicit", "po-explicit",
          receiptLineWithOrderLine("rl-1", "10"));
      stubPendingQtyPerLine(supportMock, "receipt-explicit", "rl-1", BigDecimal.TEN);
      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-explicit", "AP-EXPLICIT-1");

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      handler.createFromReceipt("receipt-explicit", explicitLines("rl-1", "2"));

      assertEquals("Explicit overrides must be used verbatim",
          Collections.singletonMap("rl-1", new BigDecimal("2")), handler.receivedReceiptOverrides);
      assertEquals("2",
          handler.capturedReceiptSelectedLines.getJSONObject(0).getString("orderedQuantity"));
      supportMock.verify(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow("receipt-explicit",
          true));
    }
  }

  /**
   * ETP-5709 (item 5) — an explicit quantity above what is pending is a 400 naming the line and
   * the pending quantity, never a silent cap and never an over-invoice. Nothing is built.
   */
  @Test
  public void createFromReceipt_explicitQuantityAbovePending_answers400NamingLineAndPending()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      stubReceiptWithOrder(dal, "receipt-over", "po-over", receiptLineWithOrderLine("rl-1", "10"));
      stubPendingQtyPerLine(supportMock, "receipt-over", "rl-1", new BigDecimal("4"));
      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-over", "AP-OVER-1");

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      try {
        handler.createFromReceipt("receipt-over", explicitLines("rl-1", "5"));
        fail("5 requested but only 4 pending must be refused");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("GR-receipt-over"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("requested quantity 5"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("pending to invoice is 4"));
      }
      assertNull("nothing may be built for a refused request", handler.capturedReceiptSelectedLines);
    }
  }

  /** ETP-5709 (item 5) — explicit lines on a receipt with nothing pending: 400, not an invoice. */
  @Test
  public void createFromReceipt_explicitLinesButNothingPending_answers400() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      stubReceiptWithOrder(dal, "receipt-done", "po-done", receiptLineWithOrderLine("rl-1", "10"));
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("receipt-done"),
          eq(true))).thenReturn(Collections.emptyMap());
      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-done", "AP-DONE-1");

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      try {
        handler.createFromReceipt("receipt-done", explicitLines("rl-1", "1"));
        fail("a line with nothing pending must not be invoiceable by explicit request");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("pending to invoice is 0"));
      }
      assertNull(handler.capturedReceiptSelectedLines);
    }
  }

  // ── ETP-5709: the invoice is scoped to THIS receipt's lines, at the requested quantity ──────
  //
  // Core replaces an order-line entry that carries no `shipmentInOutLine` by EVERY related
  // not-yet-invoiced receipt line at its full uninvoiced quantity, silently discarding the
  // quantity we sent (live: receipt lowered to 3, lines [{rl, 1}] -> invoice of 3). An entry that
  // names its receipt line is used as is, so each receipt line gets its own entry.

  /** Runs createFromReceipt for one receipt line (movement {@code movement}) and returns the entries. */
  private static JSONArray selectedLinesForSingleReceiptLine(String movement, String pending,
      JSONObject body) {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      stubReceiptWithOrder(dal, "receipt-scope", "po-scope", receiptLineWithOrderLine("rl-1", movement));
      stubPendingQtyPerLine(supportMock, "receipt-scope", "rl-1", new BigDecimal(pending));
      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-scope", "AP-SCOPE-1");
      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);
      handler.createFromReceipt("receipt-scope", body);
      return handler.capturedReceiptSelectedLines;
    }
  }

  /** The reported case: receipt of 3, `lines` asks for 1 -> ONE entry of 1 bound to that receipt line. */
  @Test
  public void createFromReceipt_partialExplicitQuantity_emitsEntryBoundToTheReceiptLine()
      throws JSONException {
    JSONArray selected = selectedLinesForSingleReceiptLine("3", "3", explicitLines("rl-1", "1"));

    assertEquals(1, selected.length());
    assertEquals("ol-rl-1", selected.getJSONObject(0).getString("id"));
    assertEquals("1", selected.getJSONObject(0).getString("orderedQuantity"));
    assertEquals("without shipmentInOutLine core would replace this entry by the full receipt",
        "rl-1", selected.getJSONObject(0).getString("shipmentInOutLine"));
  }

  @Test
  public void createFromReceipt_noLines_emitsPendingQuantityBoundToTheReceiptLine()
      throws JSONException {
    JSONArray selected = selectedLinesForSingleReceiptLine("3", "2", null);

    assertEquals(1, selected.length());
    assertEquals("2", selected.getJSONObject(0).getString("orderedQuantity"));
    assertEquals("rl-1", selected.getJSONObject(0).getString("shipmentInOutLine"));
  }

  /** Two receipt lines of the SAME order line are no longer folded into one order-line entry. */
  @Test
  public void buildSelectedLinesFromReceipt_twoReceiptLinesOfOneOrderLine_emitTwoEntries()
      throws JSONException {
    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-1");
    ShipmentInOutLine rlA = mockReceiptLine("rl-a", true, mock(Product.class), BigDecimal.valueOf(2), ol);
    ShipmentInOutLine rlB = mockReceiptLine("rl-b", true, mock(Product.class), BigDecimal.valueOf(3), ol);

    JSONArray result = new CreatePurchaseInvoiceHandler().buildSelectedLinesFromReceipt(
        receiptWith(rlA, rlB), Collections.emptyMap(), null);

    assertEquals(2, result.length());
    assertEquals("ol-1", result.getJSONObject(0).getString("id"));
    assertEquals("2", result.getJSONObject(0).getString("orderedQuantity"));
    assertEquals("rl-a", result.getJSONObject(0).getString("shipmentInOutLine"));
    assertEquals("ol-1", result.getJSONObject(1).getString("id"));
    assertEquals("3", result.getJSONObject(1).getString("orderedQuantity"));
    assertEquals("rl-b", result.getJSONObject(1).getString("shipmentInOutLine"));
  }

  /** ETP-4722 regression: a negative (return-style) receipt line keeps its sign, in full or partial. */
  @Test
  public void createFromReceipt_negativeLine_keepsSignWhetherPendingSeededOrExplicit()
      throws JSONException {
    // the pending map is magnitude-only, the invoice line must still be negative
    JSONArray seeded = selectedLinesForSingleReceiptLine("-3", "3", null);
    assertEquals("-3", seeded.getJSONObject(0).getString("orderedQuantity"));
    assertEquals("rl-1", seeded.getJSONObject(0).getString("shipmentInOutLine"));

    JSONArray partial = selectedLinesForSingleReceiptLine("-3", "3", explicitLines("rl-1", "-1"));
    assertEquals("-1", partial.getJSONObject(0).getString("orderedQuantity"));
  }

  /**
   * ETP-4722 + ETP-5709: a return-style receipt line of -3 with an explicit partial of -1 emits ONE
   * entry of -1 (sign kept, the requested quantity and not the pending one) bound to its receipt
   * line, so the native process cannot swap it for the full line.
   */
  @Test
  public void createFromReceipt_negativePartial_emitsNegativeRequestedQuantityBoundToTheLine()
      throws JSONException {
    JSONArray selected = selectedLinesForSingleReceiptLine("-3", "3", explicitLines("rl-1", "-1"));

    assertEquals(1, selected.length());
    JSONObject entry = selected.getJSONObject(0);
    assertEquals("ol-rl-1", entry.getString("id"));
    assertEquals("-1", entry.getString("orderedQuantity"));
    assertEquals("rl-1", entry.getString("shipmentInOutLine"));
  }

  /** The whole pending magnitude requested explicitly (-3 on -3) is accepted and stays negative. */
  @Test
  public void createFromReceipt_negativeExplicitEqualToPending_isAcceptedAndNegative()
      throws JSONException {
    JSONArray selected = selectedLinesForSingleReceiptLine("-3", "3", explicitLines("rl-1", "-3"));

    assertEquals("-3", selected.getJSONObject(0).getString("orderedQuantity"));
  }

  /**
   * A requested magnitude above pending (-4 on a -3 line) is a 400 naming the line and the pending
   * quantity signed like the line (-3); no selection is built and nothing is saved.
   */
  @Test
  public void createFromReceipt_negativeQuantityAbovePending_answers400NamingLineAndSignedPending() {
    OBException e = refusedForSingleReceiptLine("-3", "3", "-4");

    assertTrue(e.getMessage(), e.getMessage().contains("GR-receipt-scope"));
    assertTrue(e.getMessage(), e.getMessage().contains("requested quantity -4"));
    assertTrue(e.getMessage(), e.getMessage().contains("pending to invoice is -3"));
  }

  /** The opposite sign (+1 on a -3 line) is refused too, with the same naming; nothing is built. */
  @Test
  public void createFromReceipt_oppositeSignOnNegativeLine_answers400NamingLineAndSignedPending() {
    OBException e = refusedForSingleReceiptLine("-3", "3", "1");

    assertTrue(e.getMessage(), e.getMessage().contains("GR-receipt-scope"));
    assertTrue(e.getMessage(), e.getMessage().contains("requested quantity 1"));
    assertTrue(e.getMessage(), e.getMessage().contains("pending to invoice is -3"));
  }

  /** Runs createFromReceipt with a refused explicit quantity and asserts nothing was built/saved. */
  private static OBException refusedForSingleReceiptLine(String movement, String pending,
      String requested) {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      stubReceiptWithOrder(dal, "receipt-scope", "po-scope", receiptLineWithOrderLine("rl-1", movement));
      stubPendingQtyPerLine(supportMock, "receipt-scope", "rl-1", new BigDecimal(pending));
      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-scope", "AP-SCOPE-1");
      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);
      try {
        handler.createFromReceipt("receipt-scope", explicitLines("rl-1", requested));
      } catch (OBException expected) {
        assertNull("nothing may be built for a refused request", handler.capturedReceiptSelectedLines);
        Mockito.verify(dal, Mockito.never()).save(Mockito.any());
        return expected;
      } catch (JSONException e) {
        throw new IllegalStateException(e);
      }
      fail("quantity " + requested + " on a " + movement + " line must be refused");
      return null;
    }
  }

  /** orderQuantity / operativeQuantity follow the invoiced fraction, as core's getInOutLineJson does. */
  @Test
  public void buildSelectedLinesFromReceipt_scalesOrderAndOperativeQuantitiesToTheInvoicedFraction()
      throws JSONException {
    OrderLine ol = mock(OrderLine.class);
    when(ol.getId()).thenReturn("ol-1");
    ShipmentInOutLine rl = mockReceiptLine("rl-1", true, mock(Product.class), BigDecimal.valueOf(4), ol);
    when(rl.getOrderQuantity()).thenReturn(BigDecimal.valueOf(8));
    when(rl.getOperativeQuantity()).thenReturn(BigDecimal.valueOf(40));
    UOM aum = mock(UOM.class);
    when(aum.getId()).thenReturn("aum-1");
    when(rl.getOperativeUOM()).thenReturn(aum);

    JSONArray result = new CreatePurchaseInvoiceHandler().buildSelectedLinesFromReceipt(
        receiptWith(rl), Collections.singletonMap("rl-1", BigDecimal.ONE), null);

    JSONObject entry = result.getJSONObject(0);
    assertEquals("1", entry.getString("orderedQuantity"));
    assertEquals(0, new BigDecimal(entry.getString("orderQuantity")).compareTo(BigDecimal.valueOf(2)));
    assertEquals(0, new BigDecimal(entry.getString("operativeQuantity")).compareTo(BigDecimal.TEN));
    assertEquals("aum-1", entry.getString("operativeUOM"));
  }

  /**
   * ETP-5709 — a {@code lines} array that parses to nothing (zero quantity here) used to leave an
   * empty override map, i.e. "no lines requested", and the whole pending receipt was invoiced.
   */
  @Test
  public void createFromReceipt_linesThatParseToNothing_answers400InsteadOfInvoicingEverything()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      stubReceiptWithOrder(dal, "receipt-zero", "po-zero", receiptLineWithOrderLine("rl-1", "10"));
      stubPendingQtyPerLine(supportMock, "receipt-zero", "rl-1", BigDecimal.TEN);
      stubInvoiceCreationCollaborators(session, obContextMock, obProviderMock, weldUtilsMock,
          "user-zero", "AP-ZERO-1");

      TestableHandler handler = new TestableHandler();
      handler.docTypeToReturn = mock(DocumentType.class);

      try {
        handler.createFromReceipt("receipt-zero", explicitLines("rl-1", "0"));
        fail("lines with no usable entry must be refused, not read as 'invoice everything'");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("'lines' was sent"));
      }
      assertNull(handler.capturedReceiptSelectedLines);
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

  // ═══════════════════════════════════════════════════════════════════════════
  // Bulk (multi-receipt) invoice creation — receiptIds, createFromReceipts,
  // createInvoiceHeaderFromReceipts, pendingInvoiceLines
  // ═══════════════════════════════════════════════════════════════════════════

  // ─── parseReceiptIds ────────────────────────────────────────────────────────

  @Test
  public void parseReceiptIds_nullBody_returnsFallback() {
    assertEquals(Collections.singletonList("r-1"),
        new CreatePurchaseInvoiceHandler().parseReceiptIds(null, "r-1"));
  }

  @Test
  public void parseReceiptIds_noKey_returnsFallback() throws JSONException {
    JSONObject body = new JSONObject().put("priceListId", "pl-1");
    assertEquals(Collections.singletonList("r-1"),
        new CreatePurchaseInvoiceHandler().parseReceiptIds(body, "r-1"));
  }

  @Test
  public void parseReceiptIds_withArray_returnsAllIds() throws JSONException {
    JSONObject body = new JSONObject().put("receiptIds", new JSONArray().put("r-1").put("r-2"));
    assertEquals(Arrays.asList("r-1", "r-2"),
        new CreatePurchaseInvoiceHandler().parseReceiptIds(body, "fallback"));
  }

  @Test
  public void parseReceiptIds_emptyArray_returnsFallback() throws JSONException {
    JSONObject body = new JSONObject().put("receiptIds", new JSONArray());
    assertEquals(Collections.singletonList("r-1"),
        new CreatePurchaseInvoiceHandler().parseReceiptIds(body, "r-1"));
  }

  /** A malformed value (not an array) must degrade to the fallback, not blow up the request. */
  @Test
  public void parseReceiptIds_malformedArray_returnsFallback() throws JSONException {
    JSONObject body = new JSONObject().put("receiptIds", "not-an-array");
    assertEquals(Collections.singletonList("r-1"),
        new CreatePurchaseInvoiceHandler().parseReceiptIds(body, "r-1"));
  }

  // ─── loadAndValidateReceipts ────────────────────────────────────────────────

  @Test(expected = OBException.class)
  public void loadAndValidateReceipts_emptyList_throws() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      new CreatePurchaseInvoiceHandler().loadAndValidateReceipts(Collections.emptyList());
    }
  }

  @Test(expected = OBException.class)
  public void loadAndValidateReceipts_idNotFound_throws() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), eq("missing"))).thenReturn(null);
      new CreatePurchaseInvoiceHandler().loadAndValidateReceipts(Collections.singletonList("missing"));
    }
  }

  @Test(expected = OBException.class)
  public void loadAndValidateReceipts_differentBusinessPartners_throws() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      BusinessPartner bp1 = mock(BusinessPartner.class);
      when(bp1.getId()).thenReturn("bp-1");
      BusinessPartner bp2 = mock(BusinessPartner.class);
      when(bp2.getId()).thenReturn("bp-2");

      ShipmentInOut r1 = mock(ShipmentInOut.class);
      when(r1.getDocumentStatus()).thenReturn("CO");
      when(r1.getBusinessPartner()).thenReturn(bp1);
      ShipmentInOut r2 = mock(ShipmentInOut.class);
      when(r2.getBusinessPartner()).thenReturn(bp2);

      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);
      when(dal.get(eq(ShipmentInOut.class), eq("r-2"))).thenReturn(r2);

      new CreatePurchaseInvoiceHandler().loadAndValidateReceipts(Arrays.asList("r-1", "r-2"));
    }
  }

  /**
   * Hardening over the sales handler's original {@code loadAndValidateShipments}, which NPEs
   * comparing a null Business Partner's id via {@code s.getBusinessPartner().getId()}. This
   * must report the same validation error a real mismatch would, never an NPE.
   */
  @Test
  public void loadAndValidateReceipts_nullBusinessPartnerOnOneReceipt_throwsWithoutNPE() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      BusinessPartner bp1 = mock(BusinessPartner.class);
      when(bp1.getId()).thenReturn("bp-1");

      ShipmentInOut r1 = mock(ShipmentInOut.class);
      when(r1.getBusinessPartner()).thenReturn(bp1);
      ShipmentInOut r2 = mock(ShipmentInOut.class);
      when(r2.getBusinessPartner()).thenReturn(null);

      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);
      when(dal.get(eq(ShipmentInOut.class), eq("r-2"))).thenReturn(r2);

      try {
        new CreatePurchaseInvoiceHandler().loadAndValidateReceipts(Arrays.asList("r-1", "r-2"));
        fail("expected OBException, not a silent pass or an NPE");
      } catch (OBException expected) {
        // ok — reported as an ordinary validation error
      }
    }
  }

  @Test
  public void loadAndValidateReceipts_singleReceipt_returnsIt() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut r1 = mock(ShipmentInOut.class);
      when(r1.getDocumentStatus()).thenReturn("CO");
      when(r1.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);

      List<ShipmentInOut> result =
          new CreatePurchaseInvoiceHandler().loadAndValidateReceipts(Collections.singletonList("r-1"));
      assertEquals(1, result.size());
      assertSame(r1, result.get(0));
    }
  }

  // ─── handle() dispatch gating for pendingInvoiceLines ──────────────────────

  @Test
  public void handle_pendingInvoiceLinesPost_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName("pendingInvoiceLines")
        .specName("goods-receipt")
        .recordId("r-1")
        .build()));
  }

  /**
   * Regression guard for the shadowing risk: this handler is ALSO injected into
   * {@code PurchaseOrderHeaderHandler}'s dispatch chain (for {@code createFromOrder}), and
   * {@code NeoHeaderActionRouter.dispatch} takes the first non-null response. Without gating
   * {@code pendingInvoiceLines} to the goods-receipt spec, a GET on the purchase-order spec
   * would treat a {@code C_Order_ID} as an {@code M_InOut_ID} and would also shadow
   * {@code currencyOptionsHandler}, which sits later in that same chain.
   */
  @Test
  public void handle_pendingInvoiceLinesGet_onPurchaseOrderSpec_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("GET")
        .fieldName("pendingInvoiceLines")
        .specName("purchase-order")
        .recordId("po-1")
        .build()));
  }

  @Test
  public void handle_pendingInvoiceLinesGet_onGoodsReceiptSpec_routesToHandlePendingLines() {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLine(eq("r-1"), eq(true)))
          .thenReturn(Collections.emptyMap());
      // ETP-5410 follow-up: handlePendingLines now also loads the receipt itself — a null doc
      // keeps this test focused on the dispatch-routing assertion below.
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), anyString())).thenReturn(null);

      NeoResponse response = new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
          .endpointType(NeoEndpointType.ACTION)
          .httpMethod("GET")
          .fieldName("pendingInvoiceLines")
          .specName("goods-receipt")
          .recordId("r-1")
          .build());

      // Not null (dispatched) and 200, not the createPurchaseInvoice branch's 400/null.
      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
    }
  }

  // ─── handle() listInvoices (purchase-order only) ───────────────────────────

  private static NeoContext listInvoicesContext(String spec, String method, String action,
      String recordId) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod(method)
        .fieldName(action)
        .specName(spec)
        .recordId(recordId)
        .build();
  }

  private static Invoice listedInvoice(String id, String documentNo) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn(id);
    when(invoice.getDocumentNo()).thenReturn(documentNo);
    when(invoice.getDocumentStatus()).thenReturn("CO");
    return invoice;
  }

  /** Stubs the two queries OrderInvoiceListSupport runs: via lines first, then by header. */
  @SuppressWarnings("unchecked")
  private static Query<Invoice>[] stubListQueries(MockedStatic<OBDal> obDalMock,
      List<Invoice> viaLines, List<Invoice> viaHeader) {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    Query<Invoice> lineQuery = mock(Query.class);
    Query<Invoice> headerQuery = mock(Query.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), eq(Invoice.class))).thenReturn(lineQuery, headerQuery);
    when(lineQuery.setParameter(anyString(), any())).thenReturn(lineQuery);
    when(lineQuery.setMaxResults(anyInt())).thenReturn(lineQuery);
    when(lineQuery.list()).thenReturn(viaLines);
    when(headerQuery.setParameter(anyString(), any())).thenReturn(headerQuery);
    when(headerQuery.setMaxResults(anyInt())).thenReturn(headerQuery);
    when(headerQuery.list()).thenReturn(viaHeader);
    return new Query[] {lineQuery, headerQuery};
  }

  private static NeoResponse listPurchaseInvoices(List<Invoice> viaLines, List<Invoice> viaHeader,
      Query<Invoice>[][] queriesOut) {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      queriesOut[0] = stubListQueries(obDalMock, viaLines, viaHeader);
      return new CreatePurchaseInvoiceHandler()
          .handle(listInvoicesContext("purchase-order", "GET", "listInvoices", "po-1"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_listInvoicesGet_onPurchaseOrder_bindsPurchaseFlagInBothQueries()
      throws Exception {
    Query<Invoice>[][] q = new Query[1][];
    NeoResponse response =
        listPurchaseInvoices(Collections.emptyList(), Collections.emptyList(), q);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
    for (Query<Invoice> query : q[0]) {
      verify(query).setParameter("salesTrx", false);
      verify(query).setParameter("orderId", "po-1");
      verify(query, never()).setParameter("salesTrx", true);
    }
    assertEquals(0, response.getBody().getJSONObject("response").getJSONArray("data").length());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_listInvoicesGet_returnsInvoiceLinkedOnlyThroughItsLines() throws Exception {
    Invoice viaLines = listedInvoice("inv-l", "PINV-L");

    NeoResponse response = listPurchaseInvoices(Collections.singletonList(viaLines),
        Collections.emptyList(), new Query[1][]);

    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());
    assertEquals("inv-l", data.getJSONObject(0).getString("id"));
    assertEquals("PINV-L", data.getJSONObject(0).getString("documentNo"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_listInvoicesGet_returnsInvoiceLinkedOnlyByHeader() throws Exception {
    Invoice byHeader = listedInvoice("inv-h", "PINV-H");

    NeoResponse response = listPurchaseInvoices(Collections.emptyList(),
        Collections.singletonList(byHeader), new Query[1][]);

    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(1, data.length());
    assertEquals("inv-h", data.getJSONObject(0).getString("id"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_listInvoicesGet_invoiceLinkedBothWaysAppearsOnce() throws Exception {
    Invoice both = listedInvoice("inv-b", "PINV-B");
    Invoice headerOnly = listedInvoice("inv-h", "PINV-H");

    NeoResponse response = listPurchaseInvoices(Collections.singletonList(both),
        Arrays.asList(both, headerOnly), new Query[1][]);

    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(2, data.length());
    assertEquals("inv-b", data.getJSONObject(0).getString("id"));
    assertEquals("inv-h", data.getJSONObject(1).getString("id"));
  }

  @Test
  public void handle_listInvoicesGet_withBlankRecordId_returns400() {
    NeoResponse response = new CreatePurchaseInvoiceHandler()
        .handle(listInvoicesContext("purchase-order", "GET", "listInvoices", "  "));

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handle_listInvoicesGet_onGoodsReceiptSpec_isNotHandled() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new CreatePurchaseInvoiceHandler()
          .handle(listInvoicesContext("goods-receipt", "GET", "listInvoices", "r-1")));
      obDalMock.verifyNoInteractions();
    }
  }

  @Test
  public void handle_listInvoicesPost_onPurchaseOrder_isNotHandled() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new CreatePurchaseInvoiceHandler()
          .handle(listInvoicesContext("purchase-order", "POST", "listInvoices", "po-1")));
      obDalMock.verifyNoInteractions();
    }
  }

  @Test
  public void handle_otherGetAction_onPurchaseOrder_isNotHandledByTheListBranch() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new CreatePurchaseInvoiceHandler()
          .handle(listInvoicesContext("purchase-order", "GET", "someOtherAction", "po-1")));
      obDalMock.verifyNoInteractions();
    }
  }

  // ─── handlePendingLines ─────────────────────────────────────────────────────

  /** Test double: stubs the DB-hitting pending-quantity computation. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class PendingLinesHandler extends CreatePurchaseInvoiceHandler {
    Map<String, BigDecimal> pendingToReturn = Collections.emptyMap();
    boolean throwOnCompute;

    @Override
    protected Map<String, BigDecimal> computePendingQtyPerLine(String inOutId, boolean includeDrafts) {
      if (throwOnCompute) {
        throw new RuntimeException("db burst");
      }
      return pendingToReturn;
    }
  }

  @Test
  public void handlePendingLines_blankRecordId_returns400() {
    NeoResponse r = new CreatePurchaseInvoiceHandler().handlePendingLines(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("GET")
        .fieldName("pendingInvoiceLines")
        .specName("goods-receipt")
        .recordId("")
        .build());
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void handlePendingLines_success_returnsLineIdAndPendingQty() throws JSONException {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      // ETP-5410 follow-up: handlePendingLines now also loads the receipt itself (for the
      // product/salesOrderLine enrichment and the resolved price list) — a null doc here keeps
      // this test focused on the pre-existing lineId/pendingQty assertions below.
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(ShipmentInOut.class), anyString())).thenReturn(null);

      Map<String, BigDecimal> pending = new LinkedHashMap<>();
      pending.put("l-1", BigDecimal.valueOf(4));
      pending.put("l-2", BigDecimal.valueOf(2));

      PendingLinesHandler handler = new PendingLinesHandler();
      handler.pendingToReturn = pending;

      NeoResponse response = handler.handlePendingLines(NeoContext.builder()
          .endpointType(NeoEndpointType.ACTION)
          .httpMethod("GET")
          .fieldName("pendingInvoiceLines")
          .specName("goods-receipt")
          .recordId("receipt-1")
          .build());

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(2, data.length());
      assertEquals("l-1", data.getJSONObject(0).getString("lineId"));
      assertEquals(4, data.getJSONObject(0).getInt("pendingQty"));
      assertEquals("l-2", data.getJSONObject(1).getString("lineId"));
    }
  }

  @Test
  public void handlePendingLines_exception_returns500() {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      PendingLinesHandler handler = new PendingLinesHandler();
      handler.throwOnCompute = true;

      NeoResponse response = handler.handlePendingLines(NeoContext.builder()
          .endpointType(NeoEndpointType.ACTION)
          .httpMethod("GET")
          .fieldName("pendingInvoiceLines")
          .specName("goods-receipt")
          .recordId("receipt-1")
          .build());

      assertNotNull(response);
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ─── handleProductPrices ────────────────────────────────────────────────────

  /**
   * Regression guard mirroring {@code handle_pendingInvoiceLinesGet_onPurchaseOrderSpec_
   * returnsNull}: this handler also sits in {@code PurchaseOrderHeaderHandler}'s dispatch
   * chain, so productPrices must stay gated to goods-receipt or it could shadow a sibling
   * handler serving the same action name on purchase-order.
   */
  @Test
  public void handle_productPricesPost_onPurchaseOrderSpec_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName("productPrices")
        .specName("purchase-order")
        .recordId("po-1")
        .build()));
  }

  /** GET method for productPrices is not routed (only POST is handled). */
  @Test
  public void handle_productPricesGet_onGoodsReceiptSpec_returnsNull() {
    assertNull(new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("GET")
        .fieldName("productPrices")
        .specName("goods-receipt")
        .recordId("r-1")
        .build()));
  }

  /**
   * Happy path on the goods-receipt spec: delegates to
   * {@link MultiDocumentInvoiceSupport#buildProductPricesResponse}, which in turn calls
   * {@link MultiDocumentInvoiceSupport#resolveProductPrices} and serialises the result as
   * {@code [{productId, price}, ...]}. {@code CALLS_REAL_METHODS} keeps
   * {@code buildProductPricesResponse} itself real (it's the delegation this test verifies)
   * while only {@code resolveProductPrices} — the DB-touching part — is stubbed.
   */
  @Test
  public void handle_productPricesPost_onGoodsReceiptSpec_returns200WithPricedProducts()
      throws JSONException {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<MultiDocumentInvoiceSupport> supportMock =
            Mockito.mockStatic(MultiDocumentInvoiceSupport.class, Answers.CALLS_REAL_METHODS)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
      Map<String, BigDecimal> prices = new LinkedHashMap<>();
      prices.put("prod-1", new BigDecimal("9.99"));
      supportMock.when(() -> MultiDocumentInvoiceSupport.resolveProductPrices(
          eq("PL-1"), any())).thenReturn(prices);

      JSONObject body = new JSONObject()
          .put("priceListId", "PL-1")
          .put("productIds", new JSONArray().put("prod-1"));

      NeoResponse response = new CreatePurchaseInvoiceHandler().handle(NeoContext.builder()
          .endpointType(NeoEndpointType.ACTION)
          .httpMethod("POST")
          .fieldName("productPrices")
          .specName("goods-receipt")
          .recordId("r-1")
          .requestBody(body)
          .build());

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
      assertEquals(1, data.length());
      assertEquals("prod-1", data.getJSONObject(0).getString("productId"));
      assertEquals(9.99, data.getJSONObject(0).getDouble("price"), 0.0);
    }
  }

  // ─── handle() routing: single- vs multi-receipt path (compatibility guard) ──

  /**
   * Test double for {@code handle()}: records which of {@code createFromReceipt} /
   * {@code createFromReceipts} was invoked, without running either's real body. This is the
   * guard for the backward-compatibility contract the whole feature depends on: every EXISTING
   * caller sends no {@code receiptIds} at all, so {@code parseReceiptIds} must keep falling
   * back to {@code [recordId]} and {@code handle()} must keep routing that straight into the
   * untouched, single-receipt {@code createFromReceipt} — never the new bulk path.
   */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class RoutingHandler extends CreatePurchaseInvoiceHandler {
    Invoice invoiceToReturn;
    List<String> receivedSingleReceiptId = new ArrayList<>();
    List<List<String>> receivedMultiReceiptIds = new ArrayList<>();

    @Override
    protected Invoice createFromReceipt(String receiptId, JSONObject body) {
      receivedSingleReceiptId.add(receiptId);
      return invoiceToReturn;
    }

    @Override
    protected Invoice createFromReceipts(List<String> receiptIds, JSONObject body) {
      receivedMultiReceiptIds.add(receiptIds);
      return invoiceToReturn;
    }

    @Override
    protected void ensureDocumentNo(Invoice invoice) {
      // no-op: the real implementation resolves an AD sequence
    }
  }

  /** Shared plumbing for the three handle()-routing tests below. */
  private static NeoResponse invokeHandleCreate(RoutingHandler handler, OBDal dal, JSONObject body,
      String recordId) {
    Invoice created = mock(Invoice.class);
    when(created.getId()).thenReturn("ap-inv-routing");
    Invoice completed = mock(Invoice.class);
    when(completed.getDocumentNo()).thenReturn("AP-ROUTING-1");
    when(completed.getDocumentStatus()).thenReturn("CO");
    when(dal.get(eq(Invoice.class), eq("ap-inv-routing"))).thenReturn(completed);
    handler.invoiceToReturn = created;

    try (CompletionMocks completion = new CompletionMocks(dal, completionSuccess())) {
      return handler.handle(NeoContext.builder()
          .endpointType(NeoEndpointType.ACTION)
          .httpMethod("POST")
          .fieldName("createPurchaseInvoice")
          .specName("goods-receipt")
          .recordId(recordId)
          .requestBody(body)
          .build());
    }
  }

  @Test
  public void handleCreate_withoutReceiptIds_usesSingleReceiptPath() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      RoutingHandler handler = new RoutingHandler();
      NeoResponse response = invokeHandleCreate(handler, dal, null, "receipt-1");

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      assertEquals(Collections.singletonList("receipt-1"), handler.receivedSingleReceiptId);
      assertTrue("the multi-receipt path must not be used", handler.receivedMultiReceiptIds.isEmpty());
    }
  }

  @Test
  public void handleCreate_withOneReceiptIdInArray_stillUsesSingleReceiptPath() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      RoutingHandler handler = new RoutingHandler();
      JSONObject body = new JSONObject().put("receiptIds", new JSONArray().put("receipt-1"));
      NeoResponse response = invokeHandleCreate(handler, dal, body, "receipt-1");

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      assertEquals(Collections.singletonList("receipt-1"), handler.receivedSingleReceiptId);
      assertTrue("a size-1 array must still take the single-receipt path",
          handler.receivedMultiReceiptIds.isEmpty());
    }
  }

  @Test
  public void handleCreate_withTwoReceiptIds_usesMultiReceiptPath() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      RoutingHandler handler = new RoutingHandler();
      JSONObject body = new JSONObject().put("receiptIds", new JSONArray().put("r-1").put("r-2"));
      NeoResponse response = invokeHandleCreate(handler, dal, body, "r-1");

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      assertTrue("the single-receipt path must not be used", handler.receivedSingleReceiptId.isEmpty());
      assertEquals(1, handler.receivedMultiReceiptIds.size());
      assertEquals(Arrays.asList("r-1", "r-2"), handler.receivedMultiReceiptIds.get(0));
    }
  }

  // ─── createFromReceipts ─────────────────────────────────────────────────────

  /** Test double that records the receipts createInvoiceHeaderFromReceipts was called with. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class HeaderStubHandler extends CreatePurchaseInvoiceHandler {
    Invoice headerToReturn;
    List<ShipmentInOut> receivedReceiptsForHeader;

    @Override
    protected Invoice createInvoiceHeaderFromReceipts(ShipmentInOut first, List<ShipmentInOut> receipts) {
      receivedReceiptsForHeader = receipts;
      return headerToReturn;
    }

    @Override
    InvoiceFromOrderSupport getSupport() {
      return new InvoiceFromOrderSupport() {
        @Override
        public void ensureLineGrossAmounts(Invoice invoice) {
          // no-op: tested separately in InvoiceFromOrderSupportTest
        }
      };
    }
  }

  private static ShipmentInOut mockReceiptForBulk(String id, BusinessPartner bp, ShipmentInOutLine... lines) {
    ShipmentInOut r = mock(ShipmentInOut.class);
    when(r.getDocumentStatus()).thenReturn("CO");
    when(r.getId()).thenReturn(id);
    when(r.getBusinessPartner()).thenReturn(bp);
    when(r.getMaterialMgmtShipmentInOutLineList()).thenReturn(Arrays.asList(lines));
    return r;
  }

  /**
   * The ordering guard behind the "validate before the first setter" rule: when nothing is
   * pending across the whole selection, the request must fail BEFORE
   * {@code createInvoiceHeaderFromReceipts} is even called and BEFORE anything is saved —
   * never after a header has already been built and flushed.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipts_emptySelection_throwsBeforeBuildingHeaderOrSaving() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");

      ShipmentInOut r1 = mockReceiptForBulk("r-1", bp);
      ShipmentInOut r2 = mockReceiptForBulk("r-2", bp);
      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);
      when(dal.get(eq(ShipmentInOut.class), eq("r-2"))).thenReturn(r2);

      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(anyString(), eq(true)))
          .thenReturn(Collections.emptyMap());

      HeaderStubHandler handler = new HeaderStubHandler();
      try {
        handler.createFromReceipts(Arrays.asList("r-1", "r-2"), null);
        fail("expected OBException when nothing is pending");
      } catch (OBException expected) {
        // ok
      }
      assertNull("the header must never be built when nothing is selected",
          handler.receivedReceiptsForHeader);
      verify(dal, never()).save(any(Invoice.class));
    }
  }

  /**
   * Pins the Core-behavior finding this whole path is designed around: the native line-creation
   * process MUST be called with {@code ShipmentInOutLine.class}, never {@code OrderLine.class}.
   * Core's order-line path (CreateLinesFromUtil#getRelatedInOutLinesNotAlreadyInvoiced) re-derives
   * quantities from EVERY completed receipt of that order line, ignoring which receipts the user
   * actually selected here — so "simplifying" this back to OrderLine.class would silently
   * re-include deselected receipts.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipts_passesShipmentInOutLineClassAndBothReceiptsLines() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");

      ShipmentInOutLine line1 = mockReceiptLine("l-1", true, mock(Product.class), BigDecimal.TEN, null);
      ShipmentInOut r1 = mockReceiptForBulk("r-1", bp, line1);
      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);

      ShipmentInOutLine line2 = mockReceiptLine("l-2", true, mock(Product.class), BigDecimal.valueOf(3), null);
      ShipmentInOut r2 = mockReceiptForBulk("r-2", bp, line2);
      when(dal.get(eq(ShipmentInOut.class), eq("r-2"))).thenReturn(r2);

      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("r-1"), eq(true)))
          .thenReturn(Collections.singletonMap("l-1", BigDecimal.TEN));
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("r-2"), eq(true)))
          .thenReturn(Collections.singletonMap("l-2", BigDecimal.valueOf(3)));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getPriceList()).thenReturn(mock(PriceList.class));

      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class))
          .thenReturn(process);

      HeaderStubHandler handler = new HeaderStubHandler();
      handler.headerToReturn = invoice;

      Invoice result = handler.createFromReceipts(Arrays.asList("r-1", "r-2"), null);
      assertSame(invoice, result);
      assertEquals(2, handler.receivedReceiptsForHeader.size());

      ArgumentCaptor<JSONArray> linesCaptor = ArgumentCaptor.forClass(JSONArray.class);
      verify(process).createInvoiceLinesFromDocumentLines(
          linesCaptor.capture(), eq(invoice), eq(ShipmentInOutLine.class));
      assertEquals("both receipts' lines must be selected", 2, linesCaptor.getValue().length());
    }
  }

  /**
   * Shared setup for the ETP-5709 multi-receipt tests: two receipts of the same partner (GR-1
   * with l-1 of 10 units, GR-2 with l-2 of 3 units), the line-creation process mocked, and a
   * header stub whose invoice has a price list so that a path which fails to refuse the request
   * runs to the end and is caught by the assertions instead of dying on an NPE.
   */
  private static class MultiReceiptFixture {
    final OBDal dal = mock(OBDal.class);
    final CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
    final HeaderStubHandler handler = new HeaderStubHandler();

    MultiReceiptFixture(MockedStatic<OBDal> obDalMock, MockedStatic<WeldUtils> weldUtilsMock) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");
      ShipmentInOutLine line1 = mockReceiptLine("l-1", true, mock(Product.class), BigDecimal.TEN,
          null);
      when(line1.getLineNo()).thenReturn(10L);
      ShipmentInOutLine line2 = mockReceiptLine("l-2", true, mock(Product.class),
          BigDecimal.valueOf(3), null);
      when(line2.getLineNo()).thenReturn(10L);
      ShipmentInOut r1 = mockReceiptForBulk("r-1", bp, line1);
      when(r1.getDocumentNo()).thenReturn("GR-1");
      ShipmentInOut r2 = mockReceiptForBulk("r-2", bp, line2);
      when(r2.getDocumentNo()).thenReturn("GR-2");
      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);
      when(dal.get(eq(ShipmentInOut.class), eq("r-2"))).thenReturn(r2);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getPriceList()).thenReturn(mock(PriceList.class));
      handler.headerToReturn = invoice;
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(
          CreateInvoiceLinesFromProcess.class)).thenReturn(process);
    }

    void pending(MockedStatic<NeoInvoiceSupport> supportMock, String receiptId,
        Map<String, BigDecimal> pending) {
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq(receiptId),
          eq(true))).thenReturn(pending);
    }
  }

  /**
   * ETP-5709 (item 1) — every line already invoiced. The pending map omits fully-invoiced lines,
   * and an absent line used to be read as "fully pending, invoice the movement quantity", so the
   * bulk action created a duplicate of the invoice. Now: 400 naming the receipts, nothing built.
   */
  @Test
  public void createFromReceipts_everyLineAlreadyInvoiced_answers400NamingReceiptsAndBuildsNothing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      MultiReceiptFixture f = new MultiReceiptFixture(obDalMock, weldUtilsMock);
      f.pending(supportMock, "r-1", Collections.emptyMap());
      f.pending(supportMock, "r-2", Collections.emptyMap());

      try {
        f.handler.createFromReceipts(Arrays.asList("r-1", "r-2"), null);
        fail("two fully-invoiced receipts must not produce a second invoice");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("GR-1, GR-2"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("nothing left to invoice"));
      }
      assertNull("no header may be built", f.handler.receivedReceiptsForHeader);
      verify(f.process, never()).createInvoiceLinesFromDocumentLines(any(), any(), any());
    }
  }

  /** ETP-5709 (item 1) — only the line that still has pending quantity is invoiced, at that quantity. */
  @Test
  public void createFromReceipts_oneLineInvoicedOnePending_invoicesOnlyThePendingLine()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      MultiReceiptFixture f = new MultiReceiptFixture(obDalMock, weldUtilsMock);
      f.pending(supportMock, "r-1", Collections.emptyMap());
      f.pending(supportMock, "r-2", Collections.singletonMap("l-2", BigDecimal.valueOf(3)));

      f.handler.createFromReceipts(Arrays.asList("r-1", "r-2"), null);

      ArgumentCaptor<JSONArray> captor = ArgumentCaptor.forClass(JSONArray.class);
      verify(f.process).createInvoiceLinesFromDocumentLines(captor.capture(), any(),
          eq(ShipmentInOutLine.class));
      assertEquals("the invoiced line must not come back", 1, captor.getValue().length());
      assertEquals("l-2", captor.getValue().getJSONObject(0).getString("id"));
      assertEquals("3", captor.getValue().getJSONObject(0).getString("orderedQuantity"));
    }
  }

  /** ETP-5709 (item 5) — explicit quantity above pending on the bulk path: 400, nothing built. */
  @Test
  public void createFromReceipts_explicitQuantityAbovePending_answers400BeforeBuildingHeader()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      MultiReceiptFixture f = new MultiReceiptFixture(obDalMock, weldUtilsMock);
      f.pending(supportMock, "r-1", Collections.singletonMap("l-1", BigDecimal.valueOf(7)));
      f.pending(supportMock, "r-2", Collections.singletonMap("l-2", BigDecimal.valueOf(3)));

      try {
        f.handler.createFromReceipts(Arrays.asList("r-1", "r-2"), explicitLines("l-1", "8"));
        fail("8 requested but only 7 pending must be refused");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("GR-1"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("requested quantity 8"));
        assertTrue(expected.getMessage(), expected.getMessage().contains("pending to invoice is 7"));
      }
      assertNull(f.handler.receivedReceiptsForHeader);
      verify(f.process, never()).createInvoiceLinesFromDocumentLines(any(), any(), any());
    }
  }

  /** ETP-5709 (item 5) — a partial explicit quantity within pending is honoured as sent. */
  @Test
  public void createFromReceipts_explicitQuantityWithinPending_isInvoicedAsSent()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      MultiReceiptFixture f = new MultiReceiptFixture(obDalMock, weldUtilsMock);
      f.pending(supportMock, "r-1", Collections.singletonMap("l-1", BigDecimal.valueOf(7)));
      f.pending(supportMock, "r-2", Collections.singletonMap("l-2", BigDecimal.valueOf(3)));

      f.handler.createFromReceipts(Arrays.asList("r-1", "r-2"), explicitLines("l-1", "4"));

      ArgumentCaptor<JSONArray> captor = ArgumentCaptor.forClass(JSONArray.class);
      verify(f.process).createInvoiceLinesFromDocumentLines(captor.capture(), any(),
          eq(ShipmentInOutLine.class));
      assertEquals(1, captor.getValue().length());
      assertEquals("4", captor.getValue().getJSONObject(0).getString("orderedQuantity"));
    }
  }

  /** ETP-5709 — {@code lines} that parse to nothing must not widen into "invoice all pending". */
  @Test
  public void createFromReceipts_linesThatParseToNothing_answers400() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      MultiReceiptFixture f = new MultiReceiptFixture(obDalMock, weldUtilsMock);
      f.pending(supportMock, "r-1", Collections.singletonMap("l-1", BigDecimal.valueOf(7)));
      f.pending(supportMock, "r-2", Collections.singletonMap("l-2", BigDecimal.valueOf(3)));

      try {
        f.handler.createFromReceipts(Arrays.asList("r-1", "r-2"), explicitLines("l-1", "0"));
        fail("lines with no usable entry must be refused");
      } catch (OBException expected) {
        assertTrue(expected.getMessage(), expected.getMessage().contains("'lines' was sent"));
      }
      verify(f.process, never()).createInvoiceLinesFromDocumentLines(any(), any(), any());
    }
  }

  /** {@code ensurePriceListResolved} must reject a header with no resolved price list. */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipts_unresolvedPriceList_throwsPriceListRequired() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");

      ShipmentInOutLine line1 = mockReceiptLine("l-1", true, mock(Product.class), BigDecimal.TEN, null);
      ShipmentInOut r1 = mockReceiptForBulk("r-1", bp, line1);
      when(dal.get(eq(ShipmentInOut.class), eq("r-1"))).thenReturn(r1);

      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(eq("r-1"), eq(true)))
          .thenReturn(Collections.singletonMap("l-1", BigDecimal.TEN));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getPriceList()).thenReturn(null); // nothing resolved, no override in body

      HeaderStubHandler handler = new HeaderStubHandler();
      handler.headerToReturn = invoice;

      try {
        handler.createFromReceipts(Collections.singletonList("r-1"), null);
        fail("expected OBException when no price list is resolved");
      } catch (OBException expected) {
        // ok
      }
      verify(dal, never()).save(any(Invoice.class));
    }
  }

  // ─── createInvoiceHeaderFromReceipts ────────────────────────────────────────

  @Test
  public void createInvoiceHeaderFromReceipts_commonOrder_usesOrderFinancials() {
    try (MockedStatic<NeoCommercialDocumentFactory> factoryMock =
        Mockito.mockStatic(NeoCommercialDocumentFactory.class)) {

      Order commonOrder = mockOrderWithHeaderData();
      when(commonOrder.getId()).thenReturn("po-common");
      PriceList orderPriceList = mock(PriceList.class);
      PaymentTerm orderPaymentTerms = mock(PaymentTerm.class);
      FIN_PaymentMethod orderPaymentMethod = mock(FIN_PaymentMethod.class);
      when(commonOrder.getPriceList()).thenReturn(orderPriceList);
      when(commonOrder.getPaymentTerms()).thenReturn(orderPaymentTerms);
      when(commonOrder.getPaymentMethod()).thenReturn(orderPaymentMethod);

      Currency currency = mock(Currency.class);
      ShipmentInOut r1 = mock(ShipmentInOut.class);
      when(r1.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
      when(r1.getSalesOrder()).thenReturn(commonOrder);
      when(r1.getEtgoCurrency()).thenReturn(currency);

      ShipmentInOut r2 = mock(ShipmentInOut.class);
      when(r2.getSalesOrder()).thenReturn(commonOrder);

      TestableHandler handler = new TestableHandler();
      DocumentType docType = mock(DocumentType.class);
      handler.docTypeToReturn = docType;

      Invoice expected = mock(Invoice.class);
      factoryMock.when(() -> NeoCommercialDocumentFactory.createInvoiceFromReceiptHeader(
          eq(r1), eq(docType), eq(orderPriceList), eq(orderPaymentTerms), eq(orderPaymentMethod), eq(currency)))
          .thenReturn(expected);

      Invoice result = handler.createInvoiceHeaderFromReceipts(r1, Arrays.asList(r1, r2));

      assertSame("must use the common order's own financials, not the BP's purchase defaults",
          expected, result);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void createInvoiceHeaderFromReceipts_noCommonOrder_fallsBackToBusinessPartnerDefaults() {
    try (MockedStatic<NeoCommercialDocumentFactory> factoryMock =
        Mockito.mockStatic(NeoCommercialDocumentFactory.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType apDocType = mock(DocumentType.class);
      stubApInvoiceDocType(dal, apDocType);

      Order order1 = mock(Order.class);
      when(order1.getId()).thenReturn("po-1");
      Order order2 = mock(Order.class);
      when(order2.getId()).thenReturn("po-2");

      BusinessPartner bp = mock(BusinessPartner.class);
      PriceList bpPriceList = mock(PriceList.class);
      PaymentTerm bpPaymentTerms = mock(PaymentTerm.class);
      FIN_PaymentMethod bpPaymentMethod = mock(FIN_PaymentMethod.class);
      when(bp.getPurchasePricelist()).thenReturn(bpPriceList);
      when(bp.getPOPaymentTerms()).thenReturn(bpPaymentTerms);
      when(bp.getPOPaymentMethod()).thenReturn(bpPaymentMethod);

      Client client = mock(Client.class);
      Currency currency = mock(Currency.class);

      ShipmentInOut r1 = mock(ShipmentInOut.class);
      when(r1.getBusinessPartner()).thenReturn(bp);
      when(r1.getSalesOrder()).thenReturn(order1);
      when(r1.getClient()).thenReturn(client);
      when(r1.getEtgoCurrency()).thenReturn(currency);

      // Different order than r1 -> resolveCommonOrder must return null, not order1 or order2.
      ShipmentInOut r2 = mock(ShipmentInOut.class);
      when(r2.getSalesOrder()).thenReturn(order2);

      Invoice expected = mock(Invoice.class);
      factoryMock.when(() -> NeoCommercialDocumentFactory.createInvoiceFromReceiptHeader(
          eq(r1), eq(apDocType), eq(bpPriceList), eq(bpPaymentTerms), eq(bpPaymentMethod), eq(currency)))
          .thenReturn(expected);

      CreatePurchaseInvoiceHandler handler = new CreatePurchaseInvoiceHandler();
      Invoice result = handler.createInvoiceHeaderFromReceipts(r1, Arrays.asList(r1, r2));

      assertSame("different linked orders must fall back to the Business Partner's own "
          + "purchase defaults, exactly like createFromReceiptNoPo", expected, result);
    }
  }

  // ── source must be completed (ETP-5709) ───────────────────────────────────

  @Test
  public void createFromOrder_nonCompletedOrder_throwsNamingNumberAndStatusAndSavesNothing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn("DR");
      when(order.getDocumentNo()).thenReturn("PO-9");
      when(dal.get(Order.class, "o-draft")).thenReturn(order);

      OBException e = expectObException(
          () -> new CreatePurchaseInvoiceHandler().createFromOrder("o-draft"));

      org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("PO-9") && e.getMessage().contains("DR"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
    }
  }

  @Test
  public void createFromReceipt_nonCompletedReceipt_throws() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getDocumentStatus()).thenReturn("DR");
      when(receipt.getDocumentNo()).thenReturn("GR-3");
      when(dal.get(eq(ShipmentInOut.class), eq("r-draft"))).thenReturn(receipt);

      OBException e = expectObException(
          () -> new CreatePurchaseInvoiceHandler().createFromReceipt("r-draft", null));

      org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("GR-3") && e.getMessage().contains("DR"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
    }
  }

  @Test
  public void createFromReceipts_oneNonCompletedIdAmongSeveral_throwsBeforeAnything() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getId()).thenReturn("bp-1");
      ShipmentInOut ok = mockReceiptForBulk("r-ok", bp);
      ShipmentInOut draft = mockReceiptForBulk("r-draft", bp);
      when(draft.getDocumentStatus()).thenReturn("DR");
      when(draft.getDocumentNo()).thenReturn("GR-4");
      when(dal.get(eq(ShipmentInOut.class), eq("r-ok"))).thenReturn(ok);
      when(dal.get(eq(ShipmentInOut.class), eq("r-draft"))).thenReturn(draft);

      OBException e = expectObException(
          () -> new CreatePurchaseInvoiceHandler()
              .createFromReceipts(Arrays.asList("r-ok", "r-draft"), new JSONObject()));

      org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("GR-4") && e.getMessage().contains("DR"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
    }
  }

  @Test
  public void createFromOrder_closedOrder_throwsNamingNumberAndClAndSavesNothing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn("CL");
      when(order.getDocumentNo()).thenReturn("PO-CL-9");
      when(dal.get(Order.class, "o-closed")).thenReturn(order);

      OBException e = expectObException(
          () -> new CreatePurchaseInvoiceHandler().createFromOrder("o-closed"));

      org.junit.Assert.assertTrue(e.getMessage(),
          e.getMessage().contains("PO-CL-9") && e.getMessage().contains("CL"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
      Mockito.verify(order, Mockito.never()).getOrderLineList();
    }
  }

  @Test
  public void createFromReceipt_closedReceipt_throwsNamingNumberAndClAndSavesNothing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut receipt = mock(ShipmentInOut.class);
      when(receipt.getDocumentStatus()).thenReturn("CL");
      when(receipt.getDocumentNo()).thenReturn("GR-CL-3");
      when(dal.get(eq(ShipmentInOut.class), eq("r-closed"))).thenReturn(receipt);

      OBException e = expectObException(
          () -> new CreatePurchaseInvoiceHandler().createFromReceipt("r-closed", null));

      org.junit.Assert.assertTrue(e.getMessage(),
          e.getMessage().contains("GR-CL-3") && e.getMessage().contains("CL"));
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
    }
  }

  private static OBException expectObException(Runnable call) {
    try {
      call.run();
    } catch (OBException e) {
      return e;
    }
    throw new AssertionError("expected an OBException");
  }

  // ── nothing left to invoice must not read as "invoice everything" (ETP-5709 live bug) ──

  /**
   * Repro of the live duplicate-invoice bug: a goods receipt whose every line is already invoiced
   * has an EMPTY pending map, and the empty map used to be read as "no overrides, invoice the full
   * movement quantity", so a second call created a second identical invoice.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipt_nothingPendingAndNoExplicitLines_throwsAndInvoicesNothing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(
          eq("receipt-done"), eq(true))).thenReturn(Collections.emptyMap());
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Order linkedOrder = mockOrderWithHeaderData();
      OrderLine ol1 = mock(OrderLine.class);
      when(ol1.getId()).thenReturn("ol-1");
      ShipmentInOutLine rl1 = mockReceiptLine("rl-1", true, mock(Product.class),
          BigDecimal.valueOf(2), ol1);
      ShipmentInOut receipt = receiptWith(rl1);
      when(receipt.getDocumentNo()).thenReturn("GR-35");
      when(receipt.getSalesOrder()).thenReturn(linkedOrder);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-done"))).thenReturn(receipt);

      TestableHandler handler = new TestableHandler();
      OBException e = expectObException(() -> handler.createFromReceipt("receipt-done", null));

      org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("GR-35"));
      org.junit.Assert.assertNull("no invoice lines may be built",
          handler.capturedReceiptSelectedLines);
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
    }
  }

  /** Same guard on the branch of a receipt with no linked purchase order. */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceipt_nothingPending_noPoBranch_neverReachesNoPoCreation() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<NeoInvoiceSupport> supportMock = Mockito.mockStatic(NeoInvoiceSupport.class)) {
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(
          eq("receipt-nopo-done"), eq(true))).thenReturn(Collections.emptyMap());
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      ShipmentInOut receipt = receiptNoPoWith(mock(BusinessPartner.class),
          mockNoPoLine(BigDecimal.valueOf(2)));
      when(receipt.getDocumentNo()).thenReturn("GR-36");
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-nopo-done"))).thenReturn(receipt);

      final boolean[] reached = {false};
      TestableHandler handler = new TestableHandler() {
        @Override
        protected Invoice createFromReceiptNoPo(ShipmentInOut r, Map<String, BigDecimal> q,
            JSONObject body) {
          reached[0] = true;
          return null;
        }
      };
      OBException e = expectObException(() -> handler.createFromReceipt("receipt-nopo-done", null));

      org.junit.Assert.assertTrue(e.getMessage(), e.getMessage().contains("GR-36"));
      org.junit.Assert.assertFalse("must fail before the no-PO creation", reached[0]);
    }
  }

  /**
   * No-PO branch with a NON-empty map: a line absent from it is fully invoiced and must be
   * skipped, not invoiced again at its full movement quantity.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void createFromReceiptNoPo_lineAbsentFromNonEmptyMap_isSkipped() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class);
        MockedStatic<WeldUtils> weldUtilsMock = Mockito.mockStatic(WeldUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      BusinessPartner bp = mock(BusinessPartner.class);
      when(bp.getPurchasePricelist()).thenReturn(mock(PriceList.class));
      ShipmentInOutLine pending = mockNoPoLine(BigDecimal.valueOf(5));
      when(pending.getId()).thenReturn("rl-pending");
      ShipmentInOutLine done = mockNoPoLine(BigDecimal.valueOf(7));
      when(done.getId()).thenReturn("rl-done");
      ShipmentInOut receipt = receiptNoPoWith(bp, pending, done);
      Currency receiptCurrency = mock(Currency.class);
      when(receipt.getEtgoCurrency()).thenReturn(receiptCurrency);
      stubApInvoiceDocType(dal, mock(DocumentType.class));

      OBProvider provider = mock(OBProvider.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentNo()).thenReturn("AP-NOPO-3");
      when(invoice.getCurrency()).thenReturn(receiptCurrency);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(Invoice.class)).thenReturn(invoice);
      CreateInvoiceLinesFromProcess process = mock(CreateInvoiceLinesFromProcess.class);
      weldUtilsMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(
          CreateInvoiceLinesFromProcess.class)).thenReturn(process);

      new CreatePurchaseInvoiceHandler().createFromReceiptNoPo(receipt,
          Collections.singletonMap("rl-pending", new BigDecimal("2")));

      org.mockito.ArgumentCaptor<JSONArray> captor = org.mockito.ArgumentCaptor.forClass(JSONArray.class);
      verify(process).createInvoiceLinesFromDocumentLines(captor.capture(), any(),
          eq(ShipmentInOutLine.class));
      assertEquals(1, captor.getValue().length());
      assertEquals("rl-pending", captor.getValue().getJSONObject(0).getString("id"));
      assertEquals("2", captor.getValue().getJSONObject(0).getString("orderedQuantity"));
    }
  }
}
