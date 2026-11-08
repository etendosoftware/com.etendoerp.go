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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link InvoiceLineLinker}.
 *
 * <p>Covers the link methods plus the null-safety fallback used when the
 * current {@link OBContext} has no user (background processes / system init), and the sales
 * "create invoice from order" planning: the shipment lines are planned before the invoice lines
 * exist, then each new line takes the oldest planned shipment line no line of the invoice holds.
 * The allocation rule itself is {@code OrderLineMovementAllocationTest}'s.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceLineLinker
 */
public class InvoiceLineLinkerTest {

  private static final String SYSTEM_USER_ID = "0";

  // ── linkPendingInvoiceLinesToInout ──────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkPendingInvoiceLinesToInoutUsesCurrentUserWhenAvailable() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-42");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      ShipmentInOutLine inoutLine = mock(ShipmentInOutLine.class);
      when(inoutLine.getId()).thenReturn("iol-1");

      InvoiceLineLinker.linkPendingInvoiceLinesToInout(inoutLine, "ol-1");

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue(sql.contains("UPDATE C_InvoiceLine"));
      assertTrue(sql.contains("M_InOutLine_ID IS NULL"));

      verify(query).setParameter(eq("inoutLineId"), eq("iol-1"));
      verify(query).setParameter(eq("orderLineId"), eq("ol-1"));
      verify(query).setParameter(eq("userId"), eq("user-42"));
      verify(query).executeUpdate();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkPendingInvoiceLinesToInoutFallsBackToSystemUserWhenContextHasNoUser() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(0);

      // Context exists but has no user (e.g. background process before login).
      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(null);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      ShipmentInOutLine inoutLine = mock(ShipmentInOutLine.class);
      when(inoutLine.getId()).thenReturn("iol-2");

      InvoiceLineLinker.linkPendingInvoiceLinesToInout(inoutLine, "ol-2");

      verify(query).setParameter(eq("userId"), eq(SYSTEM_USER_ID));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkPendingInvoiceLinesToInoutFallsBackToSystemUserWhenContextIsNull() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(0);

      // No context at all.
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      ShipmentInOutLine inoutLine = mock(ShipmentInOutLine.class);
      when(inoutLine.getId()).thenReturn("iol-3");

      InvoiceLineLinker.linkPendingInvoiceLinesToInout(inoutLine, "ol-3");

      verify(query).setParameter(eq("userId"), eq(SYSTEM_USER_ID));
    }
  }

  // ── linkInvoiceLinesToExistingInouts ────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkInvoiceLinesToExistingInoutsRunsBulkUpdate() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(1);

      OBContext ctx = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-99");
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      InvoiceLineLinker.linkInvoiceLinesToExistingInouts("invoice-bulk");

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session).createNativeQuery(sqlCaptor.capture());
      String sql = sqlCaptor.getValue();
      assertTrue(sql.contains("UPDATE C_InvoiceLine"));
      assertTrue(sql.contains("MAX(iol.M_InOutLine_ID)"));
      assertTrue(sql.contains("il.C_Invoice_ID = :invoiceId"));
      assertTrue(sql.contains("M_InOutLine_ID IS NULL"));
      assertTrue(sql.contains("C_OrderLine_ID IS NOT NULL"));

      verify(query).setParameter(eq("invoiceId"), eq("invoice-bulk"));
      verify(query).setParameter(eq("userId"), eq("user-99"));
      verify(query).executeUpdate();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLinkInvoiceLinesToExistingInoutsFallsBackToSystemUserWhenContextHasNoUser() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(0);

      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(null);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      InvoiceLineLinker.linkInvoiceLinesToExistingInouts("invoice-no-user");

      verify(query).setParameter(eq("userId"), eq(SYSTEM_USER_ID));
    }
  }

  // ── linkInvoiceLineToInOutLine (ETP-5576) ───────────────────────────────

  /**
   * The invoice line had no movement link yet: the column is set and NO match row is written —
   * M_INOUT_POST creates it at completion from that column, with the final quantity.
   */
  @Test
  public void testLinkInvoiceLineToInOutLineSetsTheColumnAndWritesNoMatchRowWhenItWasUnlinked() {
    List<String> sqls = runLinkInvoiceLineToInOutLine(InOutInvoiceLinks.MatchTable.SALES, 1);

    assertEquals(1, sqls.size());
    assertTrue(sqls.get(0).contains("UPDATE C_InvoiceLine"));
    assertTrue(sqls.get(0).contains("C_InvoiceLine_ID = :invoiceLineId"));
    assertTrue(sqls.get(0).contains("M_InOutLine_ID IS NULL"));
  }

  /**
   * The invoice line already points at another movement line (a second partial movement): the
   * column cannot hold the link, so an idempotent match row is inserted into the direction's
   * match table — M_MatchSI on the sales side, M_MatchInv on the purchase side.
   */
  @Test
  public void testLinkInvoiceLineToInOutLineInsertsAnIdempotentMatchRowWhenAlreadyLinked() {
    for (InOutInvoiceLinks.MatchTable matchTable : InOutInvoiceLinks.MatchTable.values()) {
      String table = matchTable == InOutInvoiceLinks.MatchTable.SALES ? "m_matchsi" : "m_matchinv";
      String other = matchTable == InOutInvoiceLinks.MatchTable.SALES ? "m_matchinv" : "m_matchsi";

      List<String> sqls = runLinkInvoiceLineToInOutLine(matchTable, 0);

      assertEquals(matchTable + ": UPDATE then INSERT", 2, sqls.size());
      String insert = sqls.get(1);
      assertTrue(insert, insert.startsWith("INSERT INTO " + table + " (" + table + "_id,"));
      assertTrue(insert, insert.contains("NOT EXISTS (SELECT 1 FROM " + table + " mt"));
      assertFalse(insert, insert.contains(other));
    }
  }

  /**
   * The plan covers only the plain order-line entries: an entry the selection already binds to a
   * shipment line ({@code shipmentInOutLine}) is neither read nor allocated again.
   */
  @Test
  public void planSalesShipmentLinesSkipsEntriesAlreadyBoundToAShipmentLine() throws Exception {
    OrderLine plain = mock(OrderLine.class);
    ShipmentInOutLine oldest = shipmentLine("SL-OLD");
    List<OrderLineMovementAllocation.Allocation> allocations = new ArrayList<>();
    allocations.add(new OrderLineMovementAllocation.Allocation(oldest, new BigDecimal("3")));
    OBDal dal = mock(OBDal.class);
    when(dal.get(OrderLine.class, "OL-PLAIN")).thenReturn(plain);
    JSONArray selection = new JSONArray()
        .put(new JSONObject().put("id", "OL-BOUND").put("orderedQuantity", "2")
            .put("shipmentInOutLine", "SL-BOUND"))
        .put(new JSONObject().put("id", "OL-PLAIN").put("orderedQuantity", "3"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OrderLineMovementAllocation> rule =
            Mockito.mockStatic(OrderLineMovementAllocation.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      rule.when(() -> OrderLineMovementAllocation.allocateOrderLine(same(plain),
          eq(new BigDecimal("3")))).thenReturn(allocations);

      Map<String, List<OrderLineMovementAllocation.Allocation>> plan =
          InvoiceLineLinker.planSalesShipmentLines(selection);

      assertEquals(List.of("OL-PLAIN"), new ArrayList<>(plan.keySet()));
      assertEquals(allocations, plan.get("OL-PLAIN"));
      verify(dal, never()).get(OrderLine.class, "OL-BOUND");
      rule.verify(() -> OrderLineMovementAllocation.allocateOrderLine(any(), any()), times(1));
    }
  }

  /**
   * Each unlinked line takes the oldest planned shipment line that no line of THIS invoice holds:
   * one the native process already bound is skipped, and a line linked earlier in the same pass
   * takes its shipment line out of the plan for the next. With nothing left, the line stays
   * unlinked.
   */
  @Test
  public void linkSalesInvoiceLinesToPlannedShipmentsSkipsShipmentLinesTheInvoiceAlreadyHolds() {
    ShipmentInOutLine first = shipmentLine("SL-1");
    ShipmentInOutLine second = shipmentLine("SL-2");
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getId()).thenReturn("OL-1");
    InvoiceLine boundByNative = invoiceLine(orderLine, first);
    InvoiceLine takesSecond = invoiceLine(orderLine, null);
    InvoiceLine nothingLeft = invoiceLine(orderLine, null);
    List<InvoiceLine> lines = new ArrayList<>(List.of(boundByNative, takesSecond, nothingLeft));
    Invoice invoice = mock(Invoice.class);
    when(invoice.getInvoiceLineList()).thenReturn(lines);
    List<OrderLineMovementAllocation.Allocation> planned = List.of(
        new OrderLineMovementAllocation.Allocation(first, new BigDecimal("2")),
        new OrderLineMovementAllocation.Allocation(second, new BigDecimal("2")));
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      InvoiceLineLinker.linkSalesInvoiceLinesToPlannedShipments(invoice,
          Map.of("OL-1", planned));

      verify(takesSecond).setGoodsShipmentLine(second);
      verify(nothingLeft, never()).setGoodsShipmentLine(any());
      verify(boundByNative, never()).setGoodsShipmentLine(any());
      verify(dal).save(takesSecond);
      verify(dal, times(1)).save(any());
      verify(dal).flush();
    }
  }

  private static ShipmentInOutLine shipmentLine(String id) {
    ShipmentInOutLine line = mock(ShipmentInOutLine.class);
    when(line.getId()).thenReturn(id);
    return line;
  }

  private static InvoiceLine invoiceLine(OrderLine orderLine, ShipmentInOutLine shipmentLine) {
    InvoiceLine line = mock(InvoiceLine.class);
    when(line.getSalesOrderLine()).thenReturn(orderLine);
    when(line.getGoodsShipmentLine()).thenReturn(shipmentLine);
    return line;
  }

  /**
   * Runs {@code linkInvoiceLineToInOutLine("il-1", "iol-1", matchTable)} with the UPDATE reporting
   * {@code updatedRows}, and returns every native SQL statement issued, in order.
   */
  @SuppressWarnings("unchecked")
  private static List<String> runLinkInvoiceLineToInOutLine(
      InOutInvoiceLinks.MatchTable matchTable, int updatedRows) {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);
      NativeQuery query = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.executeUpdate()).thenReturn(updatedRows, 1);
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      InvoiceLineLinker.linkInvoiceLineToInOutLine("il-1", "iol-1", matchTable);

      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      verify(session, Mockito.atLeastOnce()).createNativeQuery(sqlCaptor.capture());
      verify(query, Mockito.atLeastOnce()).setParameter(eq("invoiceLineId"), eq("il-1"));
      verify(query, Mockito.atLeastOnce()).setParameter(eq("inoutLineId"), eq("iol-1"));
      return sqlCaptor.getAllValues();
    }
  }
}
