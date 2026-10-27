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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
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
 * @covers com.etendoerp.go.schemaforge.InvoiceDocumentAvailability
 * @covers com.etendoerp.go.schemaforge.ReturnDocumentAvailability
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
        Map<String, String> unavailable = new SalesOrderHeaderHandler()
            .unavailableActions(order("CO"));

        assertEquals(Set.of("documentAction:RE"), unavailable.keySet());
        assertTrue(unavailable.get("documentAction:RE").contains("linked"));
        assertEquals(unavailable, new PurchaseOrderHeaderHandler().unavailableActions(order("CO")));
      }
    }

    @Test
    @DisplayName("a completed order with nothing linked, a draft, or another record rules nothing out")
    void unlinkedDraftOrOtherRulesNothingOut() throws Exception {
      try (MockedStatic<OBDal> ignored = linkedDocuments(false)) {
        assertTrue(new SalesOrderHeaderHandler().unavailableActions(order("CO")).isEmpty());
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
  @DisplayName("invoices")
  class Invoices {

    private Invoice invoice(String status) {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getDocumentStatus()).thenReturn(status);
      when(invoice.getId()).thenReturn("I1");
      return invoice;
    }

    @Test
    @DisplayName("a completed invoice the reactivation guard would refuse rules Reactivate out")
    void guardedInvoiceRulesOutReactivate() {
      try (MockedStatic<InvoiceReactivationGuard> guard = mockStatic(InvoiceReactivationGuard.class)) {
        guard.when(() -> InvoiceReactivationGuard.computeBlock("I1")).thenReturn(
            new InvoiceReactivationGuard.Block(List.of("SII"), List.of("R-1", "R-2")));

        Map<String, String> unavailable = new SalesInvoiceHeaderHandler()
            .unavailableActions(invoice("CO"));

        assertEquals(Set.of("documentAction:RE"), unavailable.keySet());
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
        assertTrue(new SalesInvoiceHeaderHandler().unavailableActions(invoice("CO")).isEmpty());
        assertTrue(new SalesInvoiceHeaderHandler().unavailableActions(invoice("DR")).isEmpty());
        assertTrue(InvoiceDocumentAvailability.unavailable(mock(Order.class)).isEmpty());
        guard.when(() -> InvoiceReactivationGuard.computeBlock("I1"))
            .thenThrow(new IllegalStateException("boom"));
        assertTrue(InvoiceDocumentAvailability.unavailable(invoice("CO")).isEmpty());
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
