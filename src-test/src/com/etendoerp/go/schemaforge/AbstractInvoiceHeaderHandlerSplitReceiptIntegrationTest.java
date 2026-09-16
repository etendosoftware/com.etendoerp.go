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
import static org.junit.Assert.assertTrue;
import static org.openbravo.test.costing.utils.TestCostingConstants.EURO_ID;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.CallStoredProcedure;

/**
 * Real-DB, no-mocks integration coverage for the ETP-5334 fix: {@link
 * AbstractInvoiceHeaderHandler#validateLineQtyBeforeComplete(NeoContext)} and its collaborator
 * {@link NeoInvoiceSupport#computePendingQtyPerOrderLine(String)}.
 *
 * <p><b>Why this class exists.</b> The 11 existing unit tests covering ETP-5334
 * ({@code AbstractInvoiceHeaderHandlerTest}, {@code NeoInvoiceSupportTest},
 * {@code PurchaseInvoiceHeaderHandlerTest}) all mock the JDBC layer
 * ({@code PreparedStatement}/{@code ResultSet}): they prove the Java control flow is correct
 * assuming the SQL returns a given shape, but they cannot catch a wrong {@code JOIN} or a wrong
 * aggregation in the actual SQL. This class builds the split-receipt scenario from scratch
 * against a live Postgres database (no mocks anywhere) and calls the real, package-private
 * {@code validateLineQtyBeforeComplete} directly.
 *
 * <p><b>Scenario built end to end</b> (mirrors the production repro: a Purchase Order confirmed
 * with "Confirmar + factura", whose reception later splits into two partial receipts):
 * <ul>
 *   <li>a real {@link Order} (Purchase Order) with one {@link OrderLine}, ordered qty 10;</li>
 *   <li>TWO real, independently-completed {@link ShipmentInOut} goods receipts (each booked via
 *   the actual {@code m_inout_post} stored procedure — {@code docStatus} ends up {@code 'CO'},
 *   never faked by a direct field write), each with one {@link ShipmentInOutLine} linked to the
 *   SAME order line via {@code C_OrderLine_ID}, with movement quantities that together cover the
 *   scenario under test;</li>
 *   <li>a draft AP {@link Invoice} with one {@link InvoiceLine} whose {@code C_OrderLine_ID}
 *   points at that same order line and whose {@code M_InOutLine_ID} points at only ONE of the two
 *   receipt lines — reproducing exactly what {@code InvoiceLineLinker} does in production (it can
 *   only ever link one), which is the root cause the ETP-5334 fix addresses.</li>
 * </ul>
 *
 * <p>Two scenarios, matching the task brief: {@link
 * #allowsCompletionWhenInvoicedQtyMatchesAggregatePendingAcrossSplitReceipts()} proves the actual
 * bug is fixed (10 invoiced, 4+6=10 received across two receipts, one linked line carries qty 4
 * — completion must now be ALLOWED even though the single linked receipt's own pending qty (4) is
 * far below the invoiced qty (10)). {@link
 * #blocksCompletionWhenInvoicedQtyExceedsAggregatePendingAcrossSplitReceipts()} is the guard's
 * most important behavior to keep correct: 10 invoiced but only 3+5=8 actually received across
 * the two receipts — completion must still be BLOCKED with {@code ETGO_InvoiceLineAlreadyInvoiced}.
 * A regression here would silently allow real over-invoicing in production.
 *
 * <p><b>Sales/shipment (AR) symmetric scenario — deliberately skipped.</b> A shipment (unlike a
 * goods receipt) only ever REMOVES stock, so {@code m_inout_post} needs pre-existing on-hand
 * stock at the shipping locator or completion is rejected for insufficient stock — this DB has
 * zero prior stock for every product available in the QA Testing/Spain fixture universe used
 * here. Covering the AR side for real would require first seeding stock (e.g. a preliminary
 * completed receipt) and a second full Sales Order + BP-as-customer + Sales price list document
 * chain, which is materially more fixture work than the AP case for the exact same SQL/Java code
 * path (the guard is shared verbatim between AR and AP — see {@code
 * AbstractInvoiceHeaderHandler#validateLineQtyBeforeComplete} javadoc, "Applies to Sales and
 * Purchase alike"). Per the task brief this is reported, not forced.
 *
 * <p>Uses the same QA Testing client / Spain org / real-{@code m_inout_post}-completion technique
 * already proven in this package by {@code GoodsReceiptNoStockCompletionIntegrationTest} and
 * {@code GoodsReceiptWarehouseSwitchReanchorIntegrationTest} (same {@code CallStoredProcedure}
 * call), rather than the F&amp;B Group / {@code PurchaseOrderUtils} fixtures used elsewhere in
 * this package — those never actually complete a receipt, and completion (real {@code docStatus
 * = 'CO'}) is exactly what {@link NeoInvoiceSupport#computePendingQtyPerOrderLine(String)}'s
 * {@code io.docstatus NOT IN ('VO','DR')} filter depends on.
 */
public class AbstractInvoiceHeaderHandlerSplitReceiptIntegrationTest extends WeldBaseTest {

  // QA Testing client / Spain org — same fixtures GoodsReceiptNoStockCompletionIntegrationTest
  // (ETP-4671) and GoodsReceiptWarehouseSwitchReanchorIntegrationTest (ETP-4863) use for real
  // m_inout_post completion, reused here directly to avoid a second, parallel fixture universe.
  private static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  private static final String ORG_ID = "357947E87C284935AD1D783CF6F099A1";
  private static final String USER_ID = "100";
  private static final String ROLE_ID = "4028E6C72959682B01295A071429011E";

  private static final String BPARTNER_ID = "4028E6C72959682B01295F40BDDF02E3"; // Vendor A
  private static final String BPARTNER_LOCATION_ID = "4028E6C72959682B01295F40C14A02E5";
  private static final String WAREHOUSE_ID = "4028E6C72959682B01295ECFEF4502A0"; // Spain warehouse
  private static final String LOCATOR_ID = "193476BDD14E4A11B651B4E3E8D767C8"; // L01
  private static final String DOCTYPE_PO_ID = "FF8080812C2ABFC6012C2B3BDF51006E"; // Purchase Order
  private static final String DOCTYPE_MMR_ID = "FF8080812C2ABFC6012C2B3BDF530078"; // MM Receipt
  private static final String DOCTYPE_API_ID = "FF8080812C2ABFC6012C2B3BDF520075"; // AP Invoice
  private static final String PRICELIST_PURCHASE_ID = "4028E6C72959682B01295ADC1769021B";
  private static final String PRODUCT_ID = "A8B10A097DBD4BF5865BA3C844A2299C"; // costing Product 1
  private static final String TAX_ID = "5A74E390B82747F9A5754C8EB1BDB47A"; // VAT 3%
  private static final String PAYMENT_METHOD_ID = "42E87E97974E4B35849A430B8F6F2884"; // 1 (Spain)
  private static final String PAYMENT_TERM_ID = "11E7E66E53EA4CFD8C70293CACC14DB5"; // 30-60

  private static final String DOCUMENT_STATUS_DRAFT = "DR";
  private static final String DOCUMENT_ACTION_COMPLETE = "CO";
  private static final String PROCESS_INOUT_POST = "m_inout_post";

  private static final BigDecimal UNIT_PRICE = new BigDecimal("5");

  @Test
  public void allowsCompletionWhenInvoicedQtyMatchesAggregatePendingAcrossSplitReceipts()
      throws Exception {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
    try {
      PeriodTestUtils.ensureOpenPeriod(new Date());

      Order order = createOrder();
      OrderLine orderLine = createOrderLine(order, new BigDecimal("10"));

      // Split reception: 4 + 6 = 10, exactly matching the order/invoice quantity.
      ShipmentInOutLine smallReceiptLine =
          createAndCompleteReceiptLine(order, orderLine, new BigDecimal("4"), "A");
      createAndCompleteReceiptLine(order, orderLine, new BigDecimal("6"), "B");

      Invoice invoice = createDraftApInvoice(order);
      // InvoiceLineLinker can only ever point M_InOutLine_ID at ONE receipt line — deliberately
      // the smaller one (qty 4), so a per-inout-line-only comparison (the pre-fix behaviour)
      // would reject this (10 > 4) even though the aggregate across both receipts covers it.
      addApInvoiceLine(invoice, orderLine, smallReceiptLine, new BigDecimal("10"));

      NeoResponse response = callValidateLineQtyBeforeComplete(invoice);

      assertNull("ETP-5334 fix: invoice line qty (10) matches the pending qty aggregated across "
          + "BOTH receipts of the split order line (4+6=10), so completion must be ALLOWED even "
          + "though the invoice line's own linked receipt line only ever received 4",
          response);
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void blocksCompletionWhenInvoicedQtyExceedsAggregatePendingAcrossSplitReceipts()
      throws Exception {
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    OBContext.setAdminMode(true);
    try {
      PeriodTestUtils.ensureOpenPeriod(new Date());

      Order order = createOrder();
      OrderLine orderLine = createOrderLine(order, new BigDecimal("10"));

      // Split reception: 3 + 5 = 8, LESS than the invoiced quantity below (10) — genuine
      // over-invoicing that the guard must still catch, aggregate or not.
      ShipmentInOutLine smallReceiptLine =
          createAndCompleteReceiptLine(order, orderLine, new BigDecimal("3"), "A");
      createAndCompleteReceiptLine(order, orderLine, new BigDecimal("5"), "B");

      Invoice invoice = createDraftApInvoice(order);
      addApInvoiceLine(invoice, orderLine, smallReceiptLine, new BigDecimal("10"));

      NeoResponse response = callValidateLineQtyBeforeComplete(invoice);

      assertNotNull("The aggregation must not become a no-op: invoiced qty (10) exceeds what was "
          + "actually received across both receipts (3+5=8), so completion must still be BLOCKED "
          + "— a regression here would silently allow real over-invoicing in production", response);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
      String message = response.getBody().getString("message");
      assertTrue("Expected the ETGO_InvoiceLineAlreadyInvoiced message, got: " + message,
          message.contains("exceeds pending quantity"));
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.restorePreviousMode();
    }
  }

  // ── fixture builders ──────────────────────────────────────────────────────

  private Order createOrder() {
    Order order = OBProvider.getInstance().get(Order.class);
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, BPARTNER_ID);
    Location location = OBDal.getInstance().get(Location.class, BPARTNER_LOCATION_ID);
    Warehouse warehouse = OBDal.getInstance().get(Warehouse.class, WAREHOUSE_ID);
    PriceList priceList = OBDal.getInstance().get(PriceList.class, PRICELIST_PURCHASE_ID);
    Currency currency = OBDal.getInstance().get(Currency.class, EURO_ID);
    FIN_PaymentMethod paymentMethod = OBDal.getInstance().get(FIN_PaymentMethod.class, PAYMENT_METHOD_ID);
    PaymentTerm paymentTerm = OBDal.getInstance().get(PaymentTerm.class, PAYMENT_TERM_ID);
    DocumentType docType = OBDal.getInstance().get(DocumentType.class, DOCTYPE_PO_ID);

    order.setClient(OBContext.getOBContext().getCurrentClient());
    order.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    order.setTransactionDocument(docType);
    order.setDocumentType(docType);
    order.setDocumentNo("ETP5334PO-" + System.currentTimeMillis());
    order.setDocumentStatus(DOCUMENT_STATUS_DRAFT);
    order.setDocumentAction(DOCUMENT_ACTION_COMPLETE);
    order.setSalesTransaction(false);
    order.setOrderDate(new Date());
    order.setAccountingDate(new Date());
    order.setScheduledDeliveryDate(new Date());
    order.setBusinessPartner(bp);
    order.setPartnerAddress(location);
    order.setInvoiceAddress(location);
    order.setPriceList(priceList);
    order.setCurrency(currency);
    order.setPaymentMethod(paymentMethod);
    order.setPaymentTerms(paymentTerm);
    order.setWarehouse(warehouse);

    OBDal.getInstance().save(order);
    OBDal.getInstance().flush();
    return order;
  }

  private OrderLine createOrderLine(Order order, BigDecimal orderedQty) {
    Product product = OBDal.getInstance().get(Product.class, PRODUCT_ID);
    TaxRate tax = OBDal.getInstance().get(TaxRate.class, TAX_ID);

    OrderLine line = OBProvider.getInstance().get(OrderLine.class);
    line.setClient(order.getClient());
    line.setOrganization(order.getOrganization());
    line.setSalesOrder(order);
    line.setOrderDate(order.getOrderDate());
    line.setScheduledDeliveryDate(order.getScheduledDeliveryDate());
    line.setWarehouse(order.getWarehouse());
    line.setLineNo(10L);
    line.setProduct(product);
    line.setUOM(product.getUOM());
    line.setOrderedQuantity(orderedQty);
    line.setCurrency(order.getCurrency());
    line.setTax(tax);
    line.setUnitPrice(UNIT_PRICE);
    line.setListPrice(UNIT_PRICE);
    line.setLineNetAmount(orderedQty.multiply(UNIT_PRICE));

    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(order);
    return line;
  }

  /**
   * Builds ONE goods receipt with ONE line linked to {@code orderLine}, and completes it for real
   * via the {@code m_inout_post} stored procedure — same technique as {@code
   * GoodsReceiptNoStockCompletionIntegrationTest#completeReceipt}. Asserts the fixture actually
   * reached {@code docStatus = 'CO'} before returning, since {@link
   * NeoInvoiceSupport#computePendingQtyPerOrderLine(String)} depends on exactly that column value.
   */
  private ShipmentInOutLine createAndCompleteReceiptLine(Order order, OrderLine orderLine,
      BigDecimal movementQty, String label) throws Exception {
    DocumentType docType = OBDal.getInstance().get(DocumentType.class, DOCTYPE_MMR_ID);
    ShipmentInOut receipt = NeoCommercialDocumentFactory.createShipmentReceiptHeader(
        order, docType, false, "V+");
    receipt.setDocumentAction(DOCUMENT_ACTION_COMPLETE);
    // Explicit documentNo, same as createOrder()/createDraftApInvoice() below — without it,
    // DocumentNoHandlerLegacy tries to auto-assign one via RequestContext.getVariablesSecureApp(),
    // which throws "No request object set" outside a real HTTP request (JUnit has none).
    receipt.setDocumentNo("ETP5334MMR" + label + "-" + System.currentTimeMillis());
    OBDal.getInstance().save(receipt);
    OBDal.getInstance().flush();

    Locator locator = OBDal.getInstance().get(Locator.class, LOCATOR_ID);
    ShipmentInOutLine line = OBProvider.getInstance().get(ShipmentInOutLine.class);
    line.setClient(receipt.getClient());
    line.setOrganization(receipt.getOrganization());
    line.setShipmentReceipt(receipt);
    line.setBusinessPartner(receipt.getBusinessPartner());
    line.setLineNo(10L);
    line.setProduct(orderLine.getProduct());
    line.setUOM(orderLine.getUOM());
    line.setStorageBin(locator);
    line.setMovementQuantity(movementQty);
    line.setSalesOrderLine(orderLine);
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(receipt);

    completeReceipt(receipt);

    OBDal.getInstance().refresh(receipt);
    assertEquals("Fixture setup: receipt " + label + " must actually complete (docStatus=CO) — "
        + "the ETP-5334 aggregation query filters inout headers on docstatus NOT IN ('VO','DR')",
        DOCUMENT_ACTION_COMPLETE, receipt.getDocumentStatus());

    OBDal.getInstance().refresh(line);
    return line;
  }

  private static void completeReceipt(ShipmentInOut receipt) throws Exception {
    List<Object> parameters = new ArrayList<>();
    parameters.add(null);
    parameters.add(receipt.getId());
    CallStoredProcedure.getInstance().call(PROCESS_INOUT_POST, parameters, null, true, false);
    OBDal.getInstance().flush();
  }

  private Invoice createDraftApInvoice(Order order) {
    Invoice invoice = OBProvider.getInstance().get(Invoice.class);
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, BPARTNER_ID);
    Location location = OBDal.getInstance().get(Location.class, BPARTNER_LOCATION_ID);
    PriceList priceList = OBDal.getInstance().get(PriceList.class, PRICELIST_PURCHASE_ID);
    PaymentTerm paymentTerm = OBDal.getInstance().get(PaymentTerm.class, PAYMENT_TERM_ID);
    FIN_PaymentMethod paymentMethod = OBDal.getInstance().get(FIN_PaymentMethod.class, PAYMENT_METHOD_ID);
    DocumentType docType = OBDal.getInstance().get(DocumentType.class, DOCTYPE_API_ID);

    invoice.setClient(order.getClient());
    invoice.setOrganization(order.getOrganization());
    invoice.setTransactionDocument(docType);
    invoice.setDocumentType(docType);
    invoice.setDocumentNo("ETP5334PI-" + System.currentTimeMillis());
    invoice.setDocumentStatus(DOCUMENT_STATUS_DRAFT);
    invoice.setDocumentAction(DOCUMENT_ACTION_COMPLETE);
    invoice.setSalesTransaction(false);
    invoice.setAccountingDate(new Date());
    invoice.setInvoiceDate(new Date());
    invoice.setBusinessPartner(bp);
    invoice.setPartnerAddress(location);
    invoice.setPriceList(priceList);
    invoice.setPaymentMethod(paymentMethod);
    invoice.setPaymentTerms(paymentTerm);
    invoice.setCurrency(order.getCurrency());

    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
    return invoice;
  }

  /**
   * Mirrors what {@code InvoiceLineLinker}/{@code CreatePurchaseInvoiceHandler.createFromOrder()}
   * produce for "Confirmar + factura": one invoice line carrying the FULL {@code invoicedQty},
   * linked to the order line via {@code C_OrderLine_ID} AND to a single receipt line via {@code
   * M_InOutLine_ID}.
   */
  private InvoiceLine addApInvoiceLine(Invoice invoice, OrderLine orderLine,
      ShipmentInOutLine linkedReceiptLine, BigDecimal invoicedQty) {
    TaxRate tax = OBDal.getInstance().get(TaxRate.class, TAX_ID);

    InvoiceLine line = OBProvider.getInstance().get(InvoiceLine.class);
    line.setClient(invoice.getClient());
    line.setOrganization(invoice.getOrganization());
    line.setInvoice(invoice);
    line.setLineNo(10L);
    line.setProduct(orderLine.getProduct());
    line.setUOM(orderLine.getUOM());
    line.setInvoicedQuantity(invoicedQty);
    line.setUnitPrice(UNIT_PRICE);
    line.setTax(tax);
    line.setLineNetAmount(invoicedQty.multiply(UNIT_PRICE));
    line.setSalesOrderLine(orderLine);
    line.setGoodsShipmentLine(linkedReceiptLine);

    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(invoice);
    return line;
  }

  private static NeoResponse callValidateLineQtyBeforeComplete(Invoice invoice) throws Exception {
    JSONObject body = new JSONObject().put(AbstractInvoiceHeaderHandler.FIELD_DOCUMENT_ACTION_INV,
        DOCUMENT_ACTION_COMPLETE);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(invoice.getId())
        .requestBody(body)
        .build();
    return AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx);
  }
}
