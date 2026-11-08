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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link OrderLineMovementAllocation}, the one rule that binds an order line's
 * quantity to the completed receipt / shipment lines that delivered it.
 *
 * <p>The {@code importOrderLines} action already guards, through its own seams, the oldest-first
 * split capped by pending, the product filter and a movement line with no pending
 * ({@code ImportOrderLinesIntoInvoiceHandlerTest}). This class covers what those seams bypass: the
 * unit and sign filters, the cap by the movement's own quantity, and {@code allocateOrderLine}, the
 * one-order-line entry point the sales invoice paths use (its query and its pending reads).
 *
 * @covers com.etendoerp.go.schemaforge.OrderLineMovementAllocation
 */
public class OrderLineMovementAllocationTest {

  private static final String PRODUCT = "P1";
  private static final String UNIT = "U1";

  /**
   * Which movement lines are candidates, and with which signed pending. Each row is one movement
   * line offered alone: {@code label, requested, moved, pending, productId, uomId, expected}, where
   * {@code expected} is the candidate's pending or {@code null} when it is not a candidate.
   */
  @Test
  public void candidatesOfKeepsSameProductUnitAndSignCappedByTheMovedQuantity() {
    Object[][] rows = {
        { "same product, unit and sign", "5", "6", "4", PRODUCT, UNIT, "4" },
        { "pending above the moved quantity is capped by it", "5", "3", "7", PRODUCT, UNIT, "3" },
        { "another unit is never a candidate", "5", "6", "4", PRODUCT, "U-OTHER", null },
        { "a return line for a positive request", "5", "-6", "4", PRODUCT, UNIT, null },
        { "a positive line for a negative request", "-5", "6", "4", PRODUCT, UNIT, null },
        { "a negative request takes a negative pending", "-5", "-6", "4", PRODUCT, UNIT, "-4" },
        { "nothing pending", "5", "6", "0", PRODUCT, UNIT, null },
    };
    OrderLine orderLine = orderLine();
    for (Object[] row : rows) {
      String label = (String) row[0];
      ShipmentInOutLine movement = movement("m1", "doc-1", (String) row[2], (String) row[4],
          (String) row[5]);
      Map<String, BigDecimal> pending = Collections.singletonMap("m1", new BigDecimal(
          (String) row[3]));

      List<OrderLineMovementAllocation.Candidate> candidates = OrderLineMovementAllocation
          .candidatesOf(orderLine, new BigDecimal((String) row[1]), List.of(movement), pending);

      if (row[6] == null) {
        assertTrue(label, candidates.isEmpty());
      } else {
        assertEquals(label, 1, candidates.size());
        assertSame(label, movement, candidates.get(0).line);
        assertEquals(label, 0, new BigDecimal((String) row[6]).compareTo(
            candidates.get(0).pending));
      }
    }
  }

  /** No order line, no quantity or a zero quantity: nothing to allocate, and nothing is read. */
  @Test
  public void allocateOrderLineWithoutAnythingToAllocateReadsNothing() {
    OrderLine orderLine = orderLine();
    Object[][] rows = {
        { "no order line", null, BigDecimal.ONE },
        { "no quantity", orderLine, null },
        { "zero quantity", orderLine, BigDecimal.ZERO },
    };
    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      for (Object[] row : rows) {
        List<OrderLineMovementAllocation.Allocation> allocations = OrderLineMovementAllocation
            .allocateOrderLine((OrderLine) row[1], (BigDecimal) row[2]);

        assertTrue((String) row[0], allocations.isEmpty());
      }
      dalStatic.verifyNoInteractions();
    }
  }

  /**
   * The whole rule for one order line: the completed movement lines come from a query ordered
   * oldest first (movement date, then id); the pending is read once per document, drafts
   * included; the quantity is split over the lines in that order, each up to its pending.
   */
  @Test
  public void allocateOrderLineSplitsOverTheCompletedLinesOldestFirst() {
    OrderLine orderLine = orderLine();
    ShipmentInOutLine older = movement("m1", "doc-1", "3", PRODUCT, UNIT);
    ShipmentInOutLine newer = movement("m2", "doc-2", "5", PRODUCT, UNIT);
    Map<String, BigDecimal> pendingDoc1 = Collections.singletonMap("m1", new BigDecimal("3"));
    Map<String, BigDecimal> pendingDoc2 = Collections.singletonMap("m2", new BigDecimal("5"));

    Run run = new Run(List.of(older, newer));
    run.pending.put("doc-1", pendingDoc1);
    run.pending.put("doc-2", pendingDoc2);
    List<OrderLineMovementAllocation.Allocation> allocations =
        run.allocate(orderLine, new BigDecimal("6"));

    String hql = run.hql;
    assertTrue(hql, hql.contains("sh.documentStatus = 'CO'"));
    assertTrue(hql, hql.contains("sh.processed = 'Y'"));
    assertTrue(hql, hql.contains("order by sh.movementDate, il.id"));
    assertEquals(List.of("doc-1", "doc-2"), run.pendingReads);
    assertEquals(2, allocations.size());
    assertSame(older, allocations.get(0).line);
    assertEquals(0, new BigDecimal("3").compareTo(allocations.get(0).quantity));
    assertSame(newer, allocations.get(1).line);
    assertEquals(0, new BigDecimal("3").compareTo(allocations.get(1).quantity));
  }

  /** Every completed line already invoiced or held by a draft: no allocation at all. */
  @Test
  public void allocateOrderLineWithNothingPendingAllocatesNothing() {
    OrderLine orderLine = orderLine();
    ShipmentInOutLine shipped = movement("m1", "doc-1", "6", PRODUCT, UNIT);
    Map<String, BigDecimal> nothingPending = Collections.singletonMap("m1", BigDecimal.ZERO);

    Run run = new Run(List.of(shipped));
    run.pending.put("doc-1", nothingPending);

    assertTrue(run.allocate(orderLine, new BigDecimal("4")).isEmpty());
  }

  /**
   * Runs {@code allocateOrderLine} against a mocked session answering {@code movements} and a
   * mocked pending reader answering {@link #pending} per document.
   */
  private static final class Run {
    final List<ShipmentInOutLine> movements;
    final Map<String, Map<String, BigDecimal>> pending = new HashMap<>();
    final List<String> pendingReads = new ArrayList<>();
    String hql;

    Run(List<ShipmentInOutLine> movements) {
      this.movements = movements;
    }

    @SuppressWarnings("unchecked")
    List<OrderLineMovementAllocation.Allocation> allocate(OrderLine orderLine,
        BigDecimal quantity) {
      Query<ShipmentInOutLine> query = mock(Query.class);
      when(query.setParameter(anyString(), any())).thenReturn(query);
      when(query.list()).thenReturn(movements);
      Session session = mock(Session.class);
      when(session.createQuery(anyString(), eq(ShipmentInOutLine.class))).thenReturn(query);
      OBDal dal = mock(OBDal.class);
      when(dal.getSession()).thenReturn(session);

      try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class);
          MockedStatic<NeoInvoiceSupport> support = Mockito.mockStatic(NeoInvoiceSupport.class)) {
        dalStatic.when(OBDal::getInstance).thenReturn(dal);
        support.when(() -> NeoInvoiceSupport.computePendingQtyPerLineOrThrow(anyString(),
            eq(true))).thenAnswer(inv -> {
              String documentId = inv.getArgument(0);
              pendingReads.add(documentId);
              return pending.getOrDefault(documentId, Collections.emptyMap());
            });

        List<OrderLineMovementAllocation.Allocation> allocations =
            OrderLineMovementAllocation.allocateOrderLine(orderLine, quantity);

        ArgumentCaptor<String> hqlCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(session).createQuery(hqlCaptor.capture(), eq(ShipmentInOutLine.class));
        hql = hqlCaptor.getValue();
        Mockito.verify(query).setParameter("id", "OL-1");
        return allocations;
      }
    }
  }

  private static OrderLine orderLine() {
    Product product = product(PRODUCT);
    UOM uom = uom(UNIT);
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getId()).thenReturn("OL-1");
    when(orderLine.getProduct()).thenReturn(product);
    when(orderLine.getUOM()).thenReturn(uom);
    return orderLine;
  }

  private static ShipmentInOutLine movement(String id, String documentId, String moved,
      String productId, String uomId) {
    ShipmentInOut document = mock(ShipmentInOut.class);
    when(document.getId()).thenReturn(documentId);
    Product product = product(productId);
    UOM uom = uom(uomId);
    ShipmentInOutLine line = mock(ShipmentInOutLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getShipmentReceipt()).thenReturn(document);
    when(line.getMovementQuantity()).thenReturn(new BigDecimal(moved));
    when(line.getProduct()).thenReturn(product);
    when(line.getUOM()).thenReturn(uom);
    return line;
  }

  private static Product product(String id) {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn(id);
    return product;
  }

  private static UOM uom(String id) {
    UOM uom = mock(UOM.class);
    when(uom.getId()).thenReturn(id);
    return uom;
  }
}
