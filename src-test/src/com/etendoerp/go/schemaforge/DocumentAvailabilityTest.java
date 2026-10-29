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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * The availability declarations of the document customizations (ETP-5714): the shared status
 * vocabulary and the three helpers that answer "which actions do not apply to THIS record" for
 * orders, invoices and returns, plus the handlers' delegation to them. Advisory data — nothing
 * here refuses a call.
 *
 * @covers com.etendoerp.go.schemaforge.DocumentAvailability
 * @covers com.etendoerp.go.schemaforge.OrderDocumentAvailability
 * @covers com.etendoerp.go.schemaforge.InOutInvoiceAvailability
 * @covers com.etendoerp.go.schemaforge.InvoiceDocumentAvailability
 * @covers com.etendoerp.go.schemaforge.ReturnDocumentAvailability
 *
 * <p>ETP-5876 adds, per invoice, the import actions that do not suit its kind (see
 * {@code RectificativeImportGate}): a normal invoice rules out the return and origin imports, a
 * rectificative the order and goods ones.
 */
// Test methods live in the @Nested inner classes below.
@SuppressWarnings("java:S2187")
@DisplayName("Document availability (ETP-5714)")
class DocumentAvailabilityTest {

  private static final String DOCUMENT_ACTION = "documentAction";

  private static java.util.function.UnaryOperator<String> subject(String status, String posted) {
    return property -> {
      if ("documentStatus".equals(property)) {
        return status;
      }
      return "posted".equals(property) ? posted : null;
    };
  }

  @Nested
  @DisplayName("shared vocabulary")
  class Vocabulary {

    @Test
    @DisplayName("post applies to a completed document that is not booked; unpost to one that is")
    void postAndUnpost() {
      assertEquals(Boolean.TRUE, DocumentAvailability.WHEN_POSTABLE
          .evaluate(subject("CO", "N")).available());
      assertEquals(Boolean.TRUE, DocumentAvailability.WHEN_POSTABLE
          .evaluate(subject("CO", "NC")).available());
      assertEquals(Boolean.FALSE, DocumentAvailability.WHEN_POSTABLE
          .evaluate(subject("CO", "Y")).available());
      assertEquals(Boolean.FALSE, DocumentAvailability.WHEN_POSTABLE
          .evaluate(subject("DR", "N")).available());
      assertEquals(Boolean.TRUE, DocumentAvailability.WHEN_UNPOSTABLE
          .evaluate(subject("CO", "Y")).available());
      assertEquals(Boolean.FALSE, DocumentAvailability.WHEN_UNPOSTABLE
          .evaluate(subject("CO", "N")).available());
    }

    @Test
    @DisplayName("goods documentAction offers CO on a draft and nothing, with the return note, once completed")
    void goodsDocumentAction() {
      NeoActionAvailability.Verdict draft =
          DocumentAvailability.GOODS_DOCUMENT_ACTION.evaluate(subject("DR", null));
      NeoActionAvailability.Verdict completed =
          DocumentAvailability.GOODS_DOCUMENT_ACTION.evaluate(subject("CO", null));
      NeoActionAvailability.Verdict voided =
          DocumentAvailability.GOODS_DOCUMENT_ACTION.evaluate(subject("VO", null));

      assertEquals(List.of("CO"), draft.valuesNow());
      assertEquals(Boolean.FALSE, completed.available());
      assertTrue(completed.reason().contains("cannot be reactivated or voided through MCP"));
      assertNull(voided.available(), "a voided document is not judged, nor given the completed note");
      assertNull(voided.reason());
    }
  }

  @Nested
  @DisplayName("orders")
  class Orders {

    private Order order(String status) {
      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn(status);
      when(order.getId()).thenReturn("O1");
      return order;
    }

    private MockedStatic<OBDal> linkedDocuments(boolean linked) throws SQLException {
      MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
      OBDal dal = mock(OBDal.class);
      Connection connection = mock(Connection.class);
      PreparedStatement statement = mock(PreparedStatement.class);
      ResultSet rows = mock(ResultSet.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(connection);
      when(connection.prepareStatement(anyString())).thenReturn(statement);
      when(statement.executeQuery()).thenReturn(rows);
      when(rows.next()).thenReturn(linked);
      return obDal;
    }

    @Test
    @DisplayName("a completed order with linked documents rules Reactivate out, keeping the others")
    void linkedOrderRulesOutReactivate() throws Exception {
      try (MockedStatic<OBDal> ignored = linkedDocuments(true)) {
        Map<String, String> unavailable = OrderDocumentAvailability.unavailable(order("CO"));

        assertEquals(Set.of("documentAction:RE"), unavailable.keySet());
        assertTrue(unavailable.get("documentAction:RE").contains("linked"));
        // both headers add it to their own answer, next to the creation actions' (ETP-5714)
        assertEquals(unavailable.get("documentAction:RE"), new SalesOrderHeaderHandler()
            .unavailableActions(order("CO")).get("documentAction:RE"));
        assertEquals(unavailable.get("documentAction:RE"), new PurchaseOrderHeaderHandler()
            .unavailableActions(order("CO")).get("documentAction:RE"));
      }
    }

    @Test
    @DisplayName("a completed order with nothing linked, a draft, or another record rules nothing out")
    void unlinkedDraftOrOtherRulesNothingOut() throws Exception {
      try (MockedStatic<OBDal> ignored = linkedDocuments(false)) {
        assertTrue(OrderDocumentAvailability.unavailable(order("CO")).isEmpty());
      }
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        assertTrue(new SalesOrderHeaderHandler().unavailableActions(order("DR")).isEmpty());
        assertTrue(OrderDocumentAvailability.unavailable(mock(Invoice.class)).isEmpty());
        obDal.verify(OBDal::getInstance, never());
      }
    }

    @Test
    @DisplayName("a lookup that cannot run rules nothing out (advisory, never a guess)")
    void failedLookupRulesNothingOut() throws Exception {
      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        Connection connection = mock(Connection.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("down"));

        assertTrue(OrderDocumentAvailability.unavailable(order("CO")).isEmpty());
      }
    }

    @Test
    @DisplayName("the order header declares hasLinkedDocuments and the pending-document flags")
    void enrichedFields() {
      assertEquals(Set.of("hasLinkedDocuments", "needsPrimaryDoc", "needsInvoiceDoc"),
          OrderDocumentAvailability.RESPONSE_ENRICHED_FIELDS);
    }
  }

  @Nested
  @DisplayName("order creation actions (ETP-5714)")
  class OrderCreationActions {

    private static final String RECEIVE = "createGoodsReceipt";
    private static final String INVOICE = "createPurchaseInvoice";

    private final OrderDocumentAvailability.Flow purchase =
        new OrderDocumentAvailability.Flow(false, RECEIVE, INVOICE, false);
    private final OrderDocumentAvailability.Flow sales =
        new OrderDocumentAvailability.Flow(true, "createShipment", "createDraftInvoice", true);

    private OrderLine line(String productId, String ordered, String invoiced) {
      OrderLine line = mock(OrderLine.class);
      Product product = mock(Product.class);
      when(product.getId()).thenReturn(productId);
      when(line.isActive()).thenReturn(true);
      when(line.getProduct()).thenReturn(product);
      when(line.getOrderedQuantity()).thenReturn(new BigDecimal(ordered));
      when(line.getInvoicedQuantity()).thenReturn(new BigDecimal(invoiced));
      return line;
    }

    private Order completed(OrderLine... lines) {
      Order order = mock(Order.class);
      when(order.getDocumentStatus()).thenReturn("CO");
      when(order.getId()).thenReturn("O1");
      when(order.getOrderLineList()).thenReturn(List.of(lines));
      return order;
    }

    /** Runs {@code body} with the goods-side pending lines and the draft invoices stubbed. */
    private Map<String, String> judge(Order order, OrderDocumentAvailability.Flow flow,
        boolean goodsPending, Set<String> goodsDrafts, List<String> draftInvoices)
        throws Exception {
      try (MockedStatic<InOutLineFromOrderFactory> factory =
          mockStatic(InOutLineFromOrderFactory.class);
          MockedStatic<AbstractOrderHeaderHandler> linked =
              mockStatic(AbstractOrderHeaderHandler.class);
          MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
          MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
        OBContext context = mock(OBContext.class);
        obContext.when(OBContext::getOBContext).thenReturn(context);
        when(context.getReadableClients()).thenReturn(new String[] { "C1" });
        when(context.getReadableOrganizations()).thenReturn(new String[] { "O1" });
        factory.when(() -> InOutLineFromOrderFactory.collectPendingLines(any(), anyBoolean(),
            any())).thenAnswer(invocation -> {
              Set<String> sink = invocation.getArgument(2);
              sink.addAll(goodsDrafts);
              return goodsPending
                  ? List.of(mock(InOutLineFromOrderFactory.PendingOrderLine.class)) : List.of();
            });
        linked.when(() -> AbstractOrderHeaderHandler.hasLinkedDocuments(anyString()))
            .thenReturn(false);
        OBDal dal = mock(OBDal.class);
        Session session = mock(Session.class);
        @SuppressWarnings("unchecked")
        Query<String> query = mock(Query.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.getSession()).thenReturn(session);
        when(session.createQuery(OrderDocumentAvailability.DRAFT_INVOICES_HQL, String.class))
            .thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setParameterList(anyString(), anyCollection())).thenReturn(query);
        when(query.list()).thenReturn(draftInvoices);
        return OrderDocumentAvailability.unavailable(order, flow);
      }
    }

    @Test
    @DisplayName("nothing left to receive or invoice: both actions are unavailable, with a reason each")
    void nothingLeftRulesBothOut() throws Exception {
      Map<String, String> unavailable = judge(completed(line("P1", "5", "5")), purchase, false,
          Set.of(), List.of());

      assertTrue(unavailable.get(RECEIVE).startsWith("Nothing is left to receive"));
      assertTrue(unavailable.get(INVOICE).startsWith("Nothing is left to invoice"));
    }

    @Test
    @DisplayName("a draft goods receipt covering the pending quantity is named, and so is a draft invoice")
    void draftsCountAsNotPending() throws Exception {
      Map<String, String> unavailable = judge(completed(line("P1", "5", "0")), purchase, false,
          Set.of("GR-1"), List.of("INV-D1", "INV-D2"));

      assertTrue(unavailable.get(RECEIVE).contains("draft goods receipt (GR-1)"));
      assertTrue(unavailable.get(RECEIVE).contains("complete or delete it"));
      assertTrue(unavailable.get(INVOICE).contains("draft invoice (INV-D1, INV-D2)"));
    }

    @Test
    @DisplayName("something left and no draft: neither action is ruled out")
    void pendingAndNoDraftRulesNothingOut() throws Exception {
      Map<String, String> unavailable = judge(completed(line("P1", "5", "2")), purchase, true,
          Set.of(), List.of());

      assertTrue(unavailable.isEmpty(), unavailable.toString());
    }

    @Test
    @DisplayName("an over-invoiced line is not pending; a negative ordered line is")
    void quantitySignDecidesPending() throws Exception {
      assertTrue(judge(completed(line("P1", "2", "4")), purchase, true, Set.of(), List.of())
          .containsKey(INVOICE));
      assertTrue(judge(completed(line("P1", "-3", "0")), purchase, true, Set.of(), List.of())
          .isEmpty());
    }

    @Test
    @DisplayName("the total-discount line counts for the purchase flow only when asked, never for sales")
    void discountLineIsSkippedWhenTheFlowSaysSo() throws Exception {
      Order order = completed(line(TotalDiscountService.DISCOUNT_PRODUCT_ID, "1", "0"));

      assertTrue(judge(order, sales, true, Set.of(), List.of())
          .get("createDraftInvoice").startsWith("Nothing is left to invoice"));
      assertTrue(judge(order, purchase, true, Set.of(), List.of()).isEmpty());
    }

    @Test
    @DisplayName("a failure judging a creation action keeps the Reactivate advice and the other action")
    void aFailingPartDoesNotDropTheOthers() throws Exception {
      Order order = completed(line("P1", "5", "5"));
      try (MockedStatic<InOutLineFromOrderFactory> factory =
          mockStatic(InOutLineFromOrderFactory.class);
          MockedStatic<AbstractOrderHeaderHandler> linked =
              mockStatic(AbstractOrderHeaderHandler.class)) {
        factory.when(() -> InOutLineFromOrderFactory.collectPendingLines(any(), anyBoolean(),
            any())).thenThrow(new IllegalStateException("boom"));
        linked.when(() -> AbstractOrderHeaderHandler.hasLinkedDocuments(anyString()))
            .thenReturn(true);

        Map<String, String> unavailable = OrderDocumentAvailability.unavailable(order, purchase);

        assertTrue(unavailable.containsKey("documentAction:RE"));
        assertTrue(!unavailable.containsKey(RECEIVE));
        assertTrue(unavailable.get(INVOICE).startsWith("Nothing is left to invoice"));
      }
    }

    @Test
    @DisplayName("sales wording, and a draft, a non-completed order or another record are judged right")
    void salesWordingAndGuards() throws Exception {
      Map<String, String> unavailable = judge(completed(line("P1", "5", "5")), sales, false,
          Set.of(), List.of());
      assertTrue(unavailable.get("createShipment").startsWith("Nothing is left to ship"));

      Order draft = mock(Order.class);
      when(draft.getDocumentStatus()).thenReturn("DR");
      assertTrue(judge(draft, sales, false, Set.of(), List.of()).isEmpty());
      assertTrue(OrderDocumentAvailability.unavailable(mock(Invoice.class), sales).isEmpty());
    }
  }

  @Nested
  @DisplayName("goods receipt / shipment invoice action (ETP-5714)")
  class InOutInvoiceAction {

    private ShipmentInOut inout(String status) {
      ShipmentInOut inout = mock(ShipmentInOut.class);
      when(inout.getDocumentStatus()).thenReturn(status);
      when(inout.getId()).thenReturn("IO1");
      return inout;
    }

    private Map<String, String> judge(ShipmentInOut inout, Map<String, BigDecimal> withDrafts,
        Map<String, BigDecimal> withoutDrafts) {
      try (MockedStatic<NeoInvoiceSupport> support = mockStatic(NeoInvoiceSupport.class)) {
        support.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow("IO1", true))
            .thenReturn(withDrafts);
        support.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow("IO1", false))
            .thenReturn(withoutDrafts);
        return InOutInvoiceAvailability.unavailable(inout, "createPurchaseInvoice",
            "goods receipt");
      }
    }

    @Test
    @DisplayName("everything invoiced: the action is unavailable and says so")
    void fullyInvoiced() {
      Map<String, String> unavailable = judge(inout("CO"), Map.of(), Map.of());

      assertEquals(Set.of("createPurchaseInvoice"), unavailable.keySet());
      assertTrue(unavailable.get("createPurchaseInvoice").startsWith("Nothing is left to invoice"));
    }

    @Test
    @DisplayName("only a draft invoice covers what is left: the reason names the draft")
    void coveredByADraft() {
      Map<String, String> unavailable = judge(inout("CO"), Map.of(),
          Map.of("L1", BigDecimal.ONE));

      assertTrue(unavailable.get("createPurchaseInvoice").contains("A draft invoice already covers"));
      assertTrue(unavailable.get("createPurchaseInvoice").contains("complete or delete it"));
    }

    @Test
    @DisplayName("something left to invoice, a draft receipt or another record rules nothing out")
    void otherwiseNothingRuledOut() {
      assertTrue(judge(inout("CO"), Map.of("L1", BigDecimal.ONE), Map.of("L1", BigDecimal.ONE))
          .isEmpty());
      assertTrue(judge(inout("DR"), Map.of(), Map.of()).isEmpty());
      assertTrue(InOutInvoiceAvailability.unavailable(mock(Invoice.class), "a", "b").isEmpty());
    }

    @Test
    @DisplayName("the receipt and shipment headers delegate with their own action name")
    void handlersDelegate() {
      try (MockedStatic<NeoInvoiceSupport> support = mockStatic(NeoInvoiceSupport.class)) {
        support.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(anyString(),
            anyBoolean())).thenReturn(Map.of());

        assertEquals(Set.of("createPurchaseInvoice"),
            new GoodsReceiptHeaderHandler().unavailableActions(inout("CO")).keySet());
        assertEquals(Set.of("createDraftInvoice"),
            new GoodsShipmentHeaderHandler().unavailableActions(inout("CO")).keySet());
      }
    }
  }

  @Nested
  @DisplayName("invoices")
  class Invoices {

    private Invoice invoice(String status) {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentStatus()).thenReturn(status);
      when(invoice.getId()).thenReturn("I1");
      return invoice;
    }

    /** The import actions a NORMAL invoice rules out: the rectificative ones. */
    private final Set<String> importActionsOfANormalInvoice =
        RectificativeImportGate.RECTIFICATIVE_ACTIONS;

    private Map<String, String> withoutTheImportActions(Map<String, String> unavailable) {
      Map<String, String> rest = new java.util.LinkedHashMap<>(unavailable);
      rest.keySet().removeAll(RectificativeImportGate.RECTIFICATIVE_ACTIONS);
      rest.keySet().removeAll(RectificativeImportGate.STANDARD_ACTIONS);
      return rest;
    }

    @Test
    @DisplayName("a completed invoice the reactivation guard would refuse rules Reactivate out")
    void guardedInvoiceRulesOutReactivate() {
      try (MockedStatic<InvoiceReactivationGuard> guard = mockStatic(InvoiceReactivationGuard.class)) {
        guard.when(() -> InvoiceReactivationGuard.computeBlock("I1")).thenReturn(
            new InvoiceReactivationGuard.Block(List.of("SII"), List.of("R-1", "R-2")));

        Map<String, String> unavailable = new SalesInvoiceHeaderHandler()
            .unavailableActions(invoice("CO"));

        assertEquals(Set.of("documentAction:RE"), withoutTheImportActions(unavailable).keySet());
        assertTrue(unavailable.get("documentAction:RE").contains("SII"));
        assertTrue(unavailable.get("documentAction:RE").contains("R-1, R-2"));
        assertEquals(unavailable, new PurchaseInvoiceHeaderHandler()
            .unavailableActions(invoice("CO")));
      }
    }

    @Test
    @DisplayName("an unblocked invoice, a draft, another record or a failing guard rule nothing out")
    void otherwiseNothing() {
      try (MockedStatic<InvoiceReactivationGuard> guard = mockStatic(InvoiceReactivationGuard.class)) {
        guard.when(() -> InvoiceReactivationGuard.computeBlock("I1")).thenReturn(null);
        assertTrue(withoutTheImportActions(
            new SalesInvoiceHeaderHandler().unavailableActions(invoice("CO"))).isEmpty());
        assertTrue(withoutTheImportActions(
            new SalesInvoiceHeaderHandler().unavailableActions(invoice("DR"))).isEmpty());
        assertTrue(InvoiceDocumentAvailability.unavailable(mock(Order.class)).isEmpty());
        guard.when(() -> InvoiceReactivationGuard.computeBlock("I1"))
            .thenThrow(new IllegalStateException("boom"));
        assertTrue(InvoiceDocumentAvailability.unavailable(invoice("CO")).isEmpty());
      }
    }

    @Test
    @DisplayName("a normal invoice rules out the return and origin imports, with the reason")
    void aNormalInvoiceRulesOutTheRectificativeImports() {
      Invoice normal = invoice("DR");
      when(normal.getDocumentNo()).thenReturn("FV-1");

      for (Map<String, String> unavailable : List.of(
          new SalesInvoiceHeaderHandler().unavailableActions(normal),
          new PurchaseInvoiceHeaderHandler().unavailableActions(normal))) {
        assertEquals(importActionsOfANormalInvoice, unavailable.keySet());
        assertTrue(unavailable.get("importReturnLines").contains("FV-1"));
        assertTrue(unavailable.get("importOriginInvoiceLines").contains("is not a rectificative"));
      }
    }

    @Test
    @DisplayName("a rectificative rules out the order and goods imports, whichever side it is on")
    void aRectificativeRulesOutTheOrderAndGoodsImports() {
      AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(false);
      try {
        Invoice salesRectificative = invoice("DR");
        when(salesRectificative.getDocumentNo()).thenReturn("FVR-1");
        DocumentType arc = mock(DocumentType.class);
        when(arc.getDocumentCategory()).thenReturn("ARC");
        when(salesRectificative.getTransactionDocument()).thenReturn(arc);
        Invoice purchaseRectificative = invoice("DR");
        DocumentType apc = mock(DocumentType.class);
        when(apc.getDocumentCategory()).thenReturn("APC");
        when(purchaseRectificative.getTransactionDocument()).thenReturn(apc);

        Map<String, String> sales = new SalesInvoiceHeaderHandler()
            .unavailableActions(salesRectificative);
        Map<String, String> purchase = new PurchaseInvoiceHeaderHandler()
            .unavailableActions(purchaseRectificative);

        assertEquals(RectificativeImportGate.STANDARD_ACTIONS, sales.keySet());
        assertEquals(RectificativeImportGate.STANDARD_ACTIONS, purchase.keySet());
        assertTrue(sales.get("importOrderLines").contains("FVR-1"));
        assertTrue(sales.get("importShipmentLines").contains("importReturnLines"));
      } finally {
        AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(null);
      }
    }
  }

  @Nested
  @DisplayName("returns")
  class Returns {

    private ShipmentInOut inout(String status) {
      ShipmentInOut inout = mock(ShipmentInOut.class);
      when(inout.getDocumentStatus()).thenReturn(status);
      when(inout.getId()).thenReturn("R1");
      return inout;
    }

    private JSONArray rectificativas(String... docNos) throws Exception {
      JSONArray array = new JSONArray();
      for (String docNo : docNos) {
        array.put(new JSONObject().put("documentNo", docNo));
      }
      return array;
    }

    @Test
    @DisplayName("a completed return that already has a factura rectificativa rules createReturnInvoice out")
    void rectifiedReturn() throws Exception {
      try (MockedStatic<ReturnShipmentUtils> utils = mockStatic(ReturnShipmentUtils.class)) {
        utils.when(() -> ReturnShipmentUtils.fetchPreviousReturnInvoices("R1"))
            .thenReturn(rectificativas("R-1", "R-2"));

        for (AbstractReturnDocumentHeaderHandler handler : List.of(
            new ReturnToVendorShipmentHeaderHandler(), new ReturnMaterialReceiptHeaderHandler())) {
          Map<String, String> unavailable = handler.unavailableActions(inout("CO"));

          assertEquals(Set.of("createReturnInvoice"), unavailable.keySet());
          assertTrue(unavailable.get("createReturnInvoice").contains("R-1, R-2"));
          assertTrue(unavailable.get("createReturnInvoice")
              .contains("refused with 409 returnInvoiceAlreadyExists and creates nothing"));
          assertTrue(unavailable.get("createReturnInvoice").contains("allowDuplicate:true"));
        }
      }
    }

    @Test
    @DisplayName("no rectificativa yet, a draft, another record or a failing lookup rule nothing out")
    void otherwiseNothing() throws Exception {
      try (MockedStatic<ReturnShipmentUtils> utils = mockStatic(ReturnShipmentUtils.class)) {
        utils.when(() -> ReturnShipmentUtils.fetchPreviousReturnInvoices("R1"))
            .thenReturn(rectificativas());
        assertTrue(ReturnDocumentAvailability.unavailable(inout("CO")).isEmpty());
        assertTrue(ReturnDocumentAvailability.unavailable(inout("DR")).isEmpty());
        assertTrue(ReturnDocumentAvailability.unavailable(mock(Order.class)).isEmpty());
        utils.when(() -> ReturnShipmentUtils.fetchPreviousReturnInvoices("R1"))
            .thenThrow(new OBException("could not verify"));
        assertTrue(ReturnDocumentAvailability.unavailable(inout("CO")).isEmpty());
      }
    }

    @Test
    @DisplayName("enrichedFields is the common keys plus the two that name the source documents")
    void enrichedFieldsUnion() {
      Set<String> fields = ReturnDocumentAvailability.enrichedFields("sourceReceipts",
          "sourceReceiptDocNo");

      assertTrue(fields.containsAll(ReturnDocumentAvailability.COMMON_ENRICHED_FIELDS));
      assertTrue(fields.containsAll(Set.of("sourceReceipts", "sourceReceiptDocNo")));
      assertEquals(ReturnDocumentAvailability.COMMON_ENRICHED_FIELDS.size() + 2, fields.size());
    }
  }

  @Test
  @DisplayName("documentAction is spelled the same by the order and invoice helpers and the contracts")
  void documentActionNameIsConsistent() {
    assertEquals(DOCUMENT_ACTION, OrderDocumentAvailability.ACTION_DOCUMENT_ACTION);
    assertEquals(DOCUMENT_ACTION, InOutCompletionContract.CONTRACT.getName());
  }
}
