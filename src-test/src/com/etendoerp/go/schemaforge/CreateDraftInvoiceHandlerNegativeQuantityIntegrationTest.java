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
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.test.base.OBBaseTest;
import org.openbravo.test.base.TestConstants;

/**
 * Real-DB regression test for the ETP-4722 sales-side follow-up: the same
 * class of "negative-quantity line silently dropped" bug already fixed on
 * the purchase side (Order → Receipt, Receipt → Purchase Invoice) also
 * affects Goods Shipment → Sales Invoice.
 *
 * <p>Live evidence (ETP-4722 Jira thread): a real Goods Shipment (Albarán)
 * with 2 lines (qty 1 and qty -2), linked to its Sales Order, was converted
 * into a Sales Invoice via "Crear factura" and the generated invoice only
 * contained the qty-1 line.
 *
 * <p><b>Call-chain finding — does NOT mirror the purchase side structurally,
 * exactly as flagged before starting:</b> for a single shipment WITH a
 * linked order (the reported scenario — the shipment came from a confirmed
 * Sales Order), {@link CreateDraftInvoiceHandler#createFromShipments} does
 * NOT build invoice lines from the shipment's {@code ShipmentInOutLine}s at
 * all. It delegates straight to {@link CreateDraftInvoiceHandler#createFromOrder}
 * (see {@code createFromShipments}, the {@code shipments.size() == 1 &&
 * first.getSalesOrder() != null} branch), which re-derives "pending to
 * invoice" from the ORDER lines via {@link
 * CreateDraftInvoiceHandler#resolvePendingForLine}:
 * <pre>
 *   BigDecimal pending = ordered.subtract(invoiced);
 *   if (pending.compareTo(BigDecimal.ZERO) &lt;= 0) return null;
 * </pre>
 * That is the actual, confirmed point of failure — not {@code
 * resolveShipmentLineQty} (the {@code ShipmentInOutLine}-based helper used
 * only for multi-shipment invoices or shipments with no linked order, a
 * different, unverified branch — see the "not fixed" note below).
 *
 * <p>This test targets {@link CreateDraftInvoiceHandler#buildSelectedLinesForOrder}
 * directly (the method that owns the decision), not the full {@code
 * createFromShipments}/{@code createFromOrder} entry point, for the same
 * reason as the purchase-side test: the full method also invokes the
 * Weld-managed native {@code CreateInvoiceLinesFromProcess}, unrelated to
 * this bug and requiring a full CDI container to exercise.
 */
public class CreateDraftInvoiceHandlerNegativeQuantityIntegrationTest extends OBBaseTest {

  // Real F&B Group demo data, same client/org as PurchaseOrderUtils / CreateOrderFromQuotationTestUtils.
  private static final String BPARTNER_ID = "A6750F0D15334FB890C254369AC750A8"; // BP: Alimentos y Supermercados, S.A
  private static final String WAREHOUSE_ID = "B2D40D8A5D644DD89E329DC297309055"; // Warehouse: España Región Norte
  private static final String PAYMENT_METHOD_ID = "1ECC7ADB9EA2442FA4E4DA566AFD806D"; // Payment Method: Cash
  private static final String PAYMENT_TERM_ID = "66BA1164A7394344BB9CD1A6ECEED05D"; // Payment Term: 30 days
  private static final String PRODUCT_PRICE_ID = "4028E6C72959682B01295B03CEE40245";
  private static final String TAX_ID = "5A74E390B82747F9A5754C8EB1BDB47A"; // Tax: VAT 3%

  @Before
  public void setUp() {
    // This class is listed in `isolatedDalTests` (modules/com.etendoerp.go/build.gradle), so it
    // runs in its own JVM with a pristine OBContext admin-mode stack. No defensive stack reset
    // belongs here: see docs/test-jvm-isolation.md.
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void buildSelectedLinesForOrderIncludesNegativeQuantityLine() throws Exception {
    Order order = createSalesOrder();

    ProductPrice productPrice = OBDal.getInstance().get(ProductPrice.class, PRODUCT_PRICE_ID);
    OrderLine positiveLine = newOrderLine(order, productPrice, 10L, new BigDecimal("1"));
    OrderLine negativeLine = newOrderLine(order, productPrice, 20L, new BigDecimal("-2"));
    OBDal.getInstance().save(positiveLine);
    OBDal.getInstance().save(negativeLine);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(order);

    assertEquals("sanity check: order must carry both lines before conversion to invoice",
        2, order.getOrderLineList().size());

    // Run the ACTUAL production routine that decides what enters the invoice — the exact
    // one reached when "Crear factura" runs on a single Goods Shipment linked to this order
    // (createFromShipments -> createFromOrder -> buildSelectedLinesForOrder), with no
    // explicit line overrides (the default / no-body case, matching the live evidence).
    CreateDraftInvoiceHandler handler = new CreateDraftInvoiceHandler();
    JSONArray selectedLines = handler.buildSelectedLinesForOrder(order, Collections.emptyMap());

    assertEquals("selectedLines must include BOTH order lines, including the negative-quantity "
        + "one that ETP-4722 reports as silently dropped when converting a Goods Shipment into "
        + "a Sales Invoice", 2, selectedLines.length());

    boolean negativeLineFound = false;
    for (int i = 0; i < selectedLines.length(); i++) {
      JSONObject entry = selectedLines.getJSONObject(i);
      if (negativeLine.getId().equals(entry.getString("id"))) {
        negativeLineFound = true;
        assertEquals("the negative sign must be preserved in the invoice line input",
            0, new BigDecimal("-2").compareTo(new BigDecimal(entry.getString("orderedQuantity"))));
      }
    }
    assertTrue("expected an entry for the negative-quantity order line", negativeLineFound);
  }

  private Order createSalesOrder() {
    Order order = OBProvider.getInstance().get(Order.class);
    BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, BPARTNER_ID);
    Warehouse warehouse = OBDal.getInstance().get(Warehouse.class, WAREHOUSE_ID);
    FIN_PaymentMethod paymentMethod = OBDal.getInstance().get(FIN_PaymentMethod.class, PAYMENT_METHOD_ID);
    PaymentTerm paymentTerm = OBDal.getInstance().get(PaymentTerm.class, PAYMENT_TERM_ID);
    ProductPrice productPrice = OBDal.getInstance().get(ProductPrice.class, PRODUCT_PRICE_ID);
    PriceList priceList = productPrice.getPriceListVersion().getPriceList();
    Currency currency = priceList.getCurrency();
    DocumentType docType = findSalesOrderDocType();
    Location location = bp.getBusinessPartnerLocationList().get(0);

    order.setClient(OBContext.getOBContext().getCurrentClient());
    order.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    order.setDocumentNo("SO-ETP4722-" + System.currentTimeMillis());
    order.setDocumentStatus("DR");
    order.setDocumentAction("CO");
    order.setDocumentType(docType);
    order.setTransactionDocument(docType);
    order.setSalesTransaction(true);
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

  private OrderLine newOrderLine(Order order, ProductPrice productPrice, long lineNo, BigDecimal qty) {
    TaxRate tax = OBDal.getInstance().get(TaxRate.class, TAX_ID);
    BigDecimal unitPrice = productPrice.getStandardPrice();

    OrderLine line = OBProvider.getInstance().get(OrderLine.class);
    line.setClient(order.getClient());
    line.setOrganization(order.getOrganization());
    line.setSalesOrder(order);
    line.setLineNo(lineNo);
    line.setOrderDate(order.getOrderDate());
    line.setScheduledDeliveryDate(order.getScheduledDeliveryDate());
    line.setWarehouse(order.getWarehouse());
    line.setProduct(productPrice.getProduct());
    line.setUOM(productPrice.getProduct().getUOM());
    line.setOrderedQuantity(qty);
    line.setCurrency(order.getCurrency());
    line.setTax(tax);
    line.setUnitPrice(unitPrice);
    line.setListPrice(productPrice.getListPrice());
    line.setLineNetAmount(qty.multiply(unitPrice));
    return line;
  }

  /** Dynamic lookup mirroring the defensive doc-type queries used throughout this package. */
  private DocumentType findSalesOrderDocType() {
    List<DocumentType> results = OBDal.getInstance().createCriteria(DocumentType.class)
        .add(Restrictions.eq(DocumentType.PROPERTY_CLIENT, OBContext.getOBContext().getCurrentClient()))
        .add(Restrictions.eq(DocumentType.PROPERTY_DOCUMENTCATEGORY, "SOO"))
        .add(Restrictions.eq(DocumentType.PROPERTY_SALESTRANSACTION, true))
        .add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true))
        .addOrderBy(DocumentType.PROPERTY_DEFAULT, false)
        .setMaxResults(1)
        .list();
    return results.isEmpty() ? null : results.get(0);
  }
}
