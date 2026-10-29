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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.inject.Inject;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.test.base.TestConstants;

/**
 * Real-DAL, real-native-process proof of the order import into a draft purchase invoice when the
 * receipt line is already covered by ANOTHER DRAFT invoice (ETP-5713). The native
 * {@code CreateInvoiceLinesFromProcess} ignores drafts, so for it that receipt line is still "not
 * invoiced" and would replace a plain order-line entry by the receipt's quantity; the unit tests
 * only prove what the handler asks of a double. This runs the real process.
 *
 * <p>Scenario (the one found live): order of 10, receipt of 6 completed, a first draft invoice
 * created from the receipt (6, bound to the receipt line and the order line), and a second draft
 * on which {@code importOrderLines} must produce ONE line of 4, linked to the order line only
 * ({@code goodsShipmentLine} NULL). A change of the native swap rule in core makes it fail here.
 *
 * <p>Fixture: copies of a real, single-line purchase order and receipt of the F&amp;B demo client,
 * whatever their quantities (the copies are made draft, resized to ORDERED and RECEIVED, and marked completed
 * with SQL inside the transaction), so no demo document is touched and the test does not depend on
 * a hand-made document. Everything is rolled back in {@code tearDown}; nothing is committed.
 *
 * <p>Runs in its own JVM: listed in {@code isolatedDalTests} (see {@code docs/test-jvm-isolation.md}).
 *
 * <p>It also parses, against the real DAL model, the HQL the rectificative imports run
 * (ETP-5876): a bad property path in a plain {@code Session.createQuery} is swallowed by the
 * handler's error translation and leaves the transaction rollback-only, and the unit tests' mocks
 * never run it.
 *
 * @covers com.etendoerp.go.schemaforge.ImportInOutLinesIntoInvoiceHandler
 * @covers com.etendoerp.go.schemaforge.ImportOriginInvoiceLinesHandler
 */
public class ImportOrderLinesIntoInvoiceHandlerIntegrationTest extends WeldBaseTest {

  /** Quantity of the copied order line. */
  private static final BigDecimal ORDERED = BigDecimal.TEN;
  /** Quantity of the copied, completed receipt line (covered by the first draft). */
  private static final BigDecimal RECEIVED = new BigDecimal("6");
  /** What is left to invoice once the receipt is covered by another draft. */
  private static final BigDecimal REST = ORDERED.subtract(RECEIVED);

  @Inject
  private ImportOrderLinesIntoInvoiceHandler orderImport;

  @Inject
  private ImportInOutLinesIntoInvoiceHandler receiptImport;

  @Before
  public void setUpCallerAdmin() {
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP);
    RequestContext.get().setVariableSecureApp(new VariablesSecureApp(TestConstants.Users.ADMIN,
        TestConstants.Clients.FB_GRP, TestConstants.Orgs.ESP, TestConstants.Roles.FB_GRP_ADMIN,
        "en_US"));
  }

  @After
  public void tearDown() {
    OBDal.getInstance().rollbackAndClose();
  }

  /** The candidate queries of the return and origin invoice imports resolve against the model. */
  @Test
  public void rectificativeImportQueriesResolveAgainstTheRealModel() {
    org.hibernate.Session session = OBDal.getInstance().getSession();
    session.createQuery(ImportInOutLinesIntoInvoiceHandler.candidateReturnsHql(),
        ShipmentInOut.class);
    for (boolean withCurrency : new boolean[] {false, true}) {
      session.createQuery(ImportOriginInvoiceLinesHandler.candidateInvoicesHql(withCurrency),
          Invoice.class);
    }
  }

  @Test
  public void receiptCoveredByAnotherDraft_importOfTheOrder_isOneLineOfTheRestBoundToTheOrderOnly()
      throws Exception {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OrderLine templateLine = (OrderLine) OBDal.getInstance().getSession().createQuery(
        "select ol from OrderLine ol where ol.salesOrder.client.id = :client "
            + "and ol.salesOrder.salesTransaction = false and ol.salesOrder.documentStatus = 'CO' "
            + "and size(ol.salesOrder.orderLineList) = 1 "
            + "and size(ol.materialMgmtShipmentInOutLineList) = 1 "
            + "order by ol.creationDate desc")
        .setParameter("client", clientId).setMaxResults(1).uniqueResult();
    assertNotNull("fixture: the demo client needs a completed single-line purchase order with one"
        + " receipt line (any quantity: the copy is resized below)", templateLine);

    // Order of ORDERED and a completed receipt of RECEIVED, copies of the template documents.
    // Header and line are copied WITHOUT children: the DB triggers write the tax rows themselves.
    Order order = (Order) DalUtil.copy(templateLine.getSalesOrder(), false);
    order.setDocumentNo("IT5713-PO-" + System.currentTimeMillis());
    order.setDocumentStatus("DR");
    order.setProcessed(false);
    order.setPosted("N");
    OBDal.getInstance().save(order);
    OrderLine orderLine = (OrderLine) DalUtil.copy(templateLine, false);
    orderLine.setSalesOrder(order);
    orderLine.setOrderedQuantity(ORDERED);
    orderLine.setDeliveredQuantity(BigDecimal.ZERO);
    orderLine.setInvoicedQuantity(BigDecimal.ZERO);
    OBDal.getInstance().save(orderLine);
    OBDal.getInstance().flush();
    String orderId = order.getId();
    String orderLineId = orderLine.getId();
    complete("C_Order", "C_Order_ID", orderId);

    ShipmentInOutLine templateReceiptLine = templateLine.getMaterialMgmtShipmentInOutLineList().get(0);
    ShipmentInOut receipt = (ShipmentInOut) DalUtil.copy(templateReceiptLine.getShipmentReceipt(),
        false);
    receipt.setDocumentNo("IT5713-GR-" + System.currentTimeMillis());
    receipt.setDocumentStatus("DR");
    receipt.setProcessed(false);
    receipt.setPosted("N");
    receipt.setSalesOrder(OBDal.getInstance().get(Order.class, orderId));
    OBDal.getInstance().save(receipt);
    ShipmentInOutLine receiptLine = (ShipmentInOutLine) DalUtil.copy(templateReceiptLine, false);
    receiptLine.setShipmentReceipt(receipt);
    receiptLine.setSalesOrderLine(OBDal.getInstance().get(OrderLine.class, orderLineId));
    receiptLine.setMovementQuantity(RECEIVED);
    OBDal.getInstance().save(receiptLine);
    OBDal.getInstance().flush();
    String receiptId = receipt.getId();
    String receiptLineId = receiptLine.getId();
    complete("M_InOut", "M_InOut_ID", receiptId);
    OBDal.getInstance().getSession().clear();

    // First draft: the whole receipt (RECEIVED), through the real receipt import.
    String draftA = newDraftInvoice(orderId);
    NeoResponse first = receiptImport.handle(action("importReceiptLines", draftA,
        "{\"receiptId\":\"" + receiptId + "\"}"));
    assertEquals(String.valueOf(first.getBody()), 200, first.getHttpStatus());

    // Second draft: the order import must give the rest (REST), against the order line only.
    String draftB = newDraftInvoice(orderId);
    NeoResponse second = orderImport.handle(action("importOrderLines", draftB,
        "{\"orderId\":\"" + orderId + "\"}"));
    assertEquals(String.valueOf(second.getBody()), 200, second.getHttpStatus());
    JSONObject data = second.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals(1, data.getInt("importedCount"));
    assertEquals(0, REST.compareTo(
        new BigDecimal(data.getJSONArray("lines").getJSONObject(0).get("quantity").toString())));

    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    assertDraftLines(draftA, 1, RECEIVED, receiptLineId);
    assertDraftLines(draftB, 1, REST, null);

    // Drafts count: a second import of the same order finds nothing left.
    NeoResponse again = orderImport.handle(action("importOrderLines", draftB,
        "{\"orderId\":\"" + orderId + "\"}"));
    assertEquals(422, again.getHttpStatus());
    assertEquals("importNothingPending",
        again.getBody().getJSONObject("error").getString("code"));
  }

  private static NeoContext action(String action, String recordId, String body) throws Exception {
    return NeoContext.builder().endpointType(NeoEndpointType.ACTION).httpMethod("POST")
        .fieldName(action).recordId(recordId).requestBody(new JSONObject(body)).build();
  }

  /** Marks a copied document completed inside the transaction (the copy was saved as a draft). */
  private static void complete(String table, String idColumn, String id) throws Exception {
    OBDal.getInstance().flush();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement("UPDATE " + table
        + " SET DocStatus = 'CO', DocAction = 'CL', Processed = 'Y' WHERE " + idColumn + " = ?")) {
      ps.setString(1, id);
      assertEquals(1, ps.executeUpdate());
    }
  }

  /** A draft purchase invoice header for the order's partner, created the way the handlers do. */
  private static String newDraftInvoice(String orderId) {
    OBContext.setAdminMode(true);
    try {
      return newDraftInvoiceInAdminMode(orderId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String newDraftInvoiceInAdminMode(String orderId) {
    Order order = OBDal.getInstance().get(Order.class, orderId);
    DocumentType type = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType t where t.client.id = :client and t.documentCategory = 'API' "
            + "and t.salesTransaction = false and t.active = true and t.return = false "
            + "order by t.default desc").setParameter("client", order.getClient().getId())
        .setMaxResults(1).uniqueResult();
    Invoice invoice = NeoCommercialDocumentFactory.createInvoiceFromOrderHeader(order, type, false);
    invoice.setCurrency(order.getCurrency());
    OBDal.getInstance().save(invoice);
    OBDal.getInstance().flush();
    return invoice.getId();
  }

  private static void assertDraftLines(String invoiceId, int count, BigDecimal quantity,
      String expectedMovementLineId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*), SUM(QtyInvoiced), "
        + "MAX(M_InOutLine_ID), MAX(C_OrderLine_ID) FROM C_InvoiceLine WHERE C_Invoice_ID = ?")) {
      ps.setString(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(count, rs.getInt(1));
        assertEquals(0, quantity.compareTo(rs.getBigDecimal(2)));
        assertNotNull("every line points at its order line", rs.getString(4));
        if (expectedMovementLineId == null) {
          assertNull("the rest is bound to the order line only", rs.getString(3));
        } else {
          assertEquals(expectedMovementLineId, rs.getString(3));
        }
      }
    }
  }
}
