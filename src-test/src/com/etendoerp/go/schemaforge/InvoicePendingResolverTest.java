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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link InvoicePendingResolver} (ETP-5576): the invoice eligibility rules, how a
 * row of the pending query becomes a {@link PendingResolver.SourceLine} under the shared order
 * cap, the per-invoice verdict, the direction's match table and the lock order.
 *
 * <p>The direction is only ever observed through behaviour ({@code WRONG_DIRECTION}, the match
 * table the SQL reads), never through an accessor.
 *
 * @covers com.etendoerp.go.schemaforge.InvoicePendingResolver
 */
class InvoicePendingResolverTest {

  private static final String FAC_DOC_TYPE = "dt-fac";

  private MockedStatic<OBDal> obDalStatic;
  private Connection conn;

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    OBDal dal = mock(OBDal.class);
    conn = mock(Connection.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getConnection()).thenReturn(conn);
  }

  @AfterEach
  void tearDown() {
    obDalStatic.close();
  }

  // ── eligibility ───────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}: isSOTrx={1} docStatus={2} docType={3} -> {4}")
  @CsvSource({
      "SALES,N,CO,dt-fac,WRONG_DIRECTION",
      "SALES,,CO,dt-fac,WRONG_DIRECTION",
      "SALES,N,DR,dt-other,WRONG_DIRECTION",     // first failing rule wins
      "SALES,Y,DR,dt-fac,NOT_COMPLETED",
      "SALES,Y,DR,dt-other,NOT_COMPLETED",       // status is checked before the type
      "SALES,Y,CO,dt-other,NOT_ELIGIBLE_TYPE",
      "SALES,Y,CO,dt-fac,",                      // eligible
      "PURCHASE,Y,CO,dt-fac,WRONG_DIRECTION",
      "PURCHASE,N,CO,dt-fac,",                   // eligible
  })
  void ineligibilityAppliesDirectionThenStatusThenDocumentType(String direction, String isSoTrx,
      String docStatus, String docTypeId, String expected) {
    InvoicePendingResolver resolver = resolver(InOutTargetBuilder.Direction.valueOf(direction));

    FollowUpException.Reason reason = resolver.ineligibility(isSoTrx, docStatus, docTypeId);

    assertEquals(expected == null ? null : FollowUpException.Reason.valueOf(expected), reason);
  }

  // ── loadSources ───────────────────────────────────────────────────────────

  @Test
  void loadSourcesOfNoIdIsEmptyAndRunsNoQuery() {
    assertTrue(resolver(InOutTargetBuilder.Direction.SALES)
        .loadSources(Collections.emptyList()).isEmpty());
    verifyNoInteractions(conn);
  }

  /**
   * {@code own_pending} is already {@code max(0, QtyInvoiced - moved)}; the resolver applies the
   * shared order cap ({@code upstream_remaining}, what the preceding lines of the same invoice
   * left of the order line) and drops every line with nothing left (ETP-5576 review W1).
   */
  @Test
  void loadSourcesCapsEachLineByTheOrderRemainderAndDropsLinesWithNothingLeft() throws Exception {
    prepared(rows(
        row("inv-1", "Y", "CO", "il-1", "5", "6"),     // own 5, order leaves 6 -> 5
        row("inv-1", "Y", "CO", "il-2", "5", "1"),     // own 5, order leaves 1 -> 1
        row("inv-1", "Y", "CO", "il-3", "5", "0"),     // order exhausted -> dropped
        row("inv-1", "Y", "CO", "il-4", "5", "-2"),    // over-taken order -> dropped
        row("inv-1", "Y", "CO", "il-5", "3", null)));  // no order line -> own 3

    Map<String, PendingResolver.Source> result = resolver(InOutTargetBuilder.Direction.SALES)
        .loadSources(Collections.singletonList("inv-1"));

    PendingResolver.Source source = result.get("inv-1");
    assertTrue(source.isAvailable());
    List<PendingResolver.SourceLine> lines = source.getLines();
    assertEquals(3, lines.size());
    assertLine(lines.get(0), "il-1", "5");
    assertLine(lines.get(1), "il-2", "1");
    assertLine(lines.get(2), "il-5", "3");
  }

  @ParameterizedTest(name = "{0} reads {2}, never {3}")
  @CsvSource({
      "SALES,Y,m_matchsi,m_matchinv",
      "PURCHASE,N,m_matchinv,m_matchsi",
  })
  void loadSourcesBindsTheIdsReadsTheDirectionMatchTableAndGivesOneVerdictPerInvoice(
      String direction, String isSoTrx, String matchTable, String otherMatchTable)
      throws Exception {
    PreparedStatement ps = prepared(rows(
        row("inv-none", isSoTrx, "CO", null, null, null),     // eligible, no candidate line
        row("inv-draft", isSoTrx, "DR", "il-9", "4", null)));  // pending, but not completed

    Map<String, PendingResolver.Source> result =
        resolver(InOutTargetBuilder.Direction.valueOf(direction))
            .loadSources(Arrays.asList("inv-none", "inv-draft", "inv-missing"));

    assertEquals(FollowUpException.Reason.NOTHING_PENDING,
        result.get("inv-none").getUnavailability());
    assertEquals(FollowUpException.Reason.NOT_COMPLETED,
        result.get("inv-draft").getUnavailability());
    assertTrue(result.get("inv-draft").getLines().isEmpty());
    assertFalse(result.containsKey("inv-missing"), "an id with no row is absent, not invented");
    InOrder binds = inOrder(ps);
    binds.verify(ps).setString(1, TotalDiscountService.DISCOUNT_PRODUCT_ID);
    binds.verify(ps).setString(2, "inv-none");
    binds.verify(ps).setString(3, "inv-draft");
    binds.verify(ps).setString(4, "inv-missing");
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(conn).prepareStatement(sql.capture());
    assertTrue(sql.getValue().contains(matchTable), direction + " pending must read " + matchTable);
    assertFalse(sql.getValue().contains(otherMatchTable),
        direction + " pending must not read " + otherMatchTable);
  }

  // ── lockSource ────────────────────────────────────────────────────────────

  @Test
  void lockSourceLocksTheInvoiceThenItsOrderLinesInIdOrderReadingEveryRow() throws Exception {
    PreparedStatement invoicePs = mock(PreparedStatement.class);
    PreparedStatement orderLinesPs = mock(PreparedStatement.class);
    ResultSet invoiceRs = mock(ResultSet.class);
    ResultSet orderLinesRs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(invoicePs, orderLinesPs);
    when(invoicePs.executeQuery()).thenReturn(invoiceRs);
    when(orderLinesPs.executeQuery()).thenReturn(orderLinesRs);
    when(invoiceRs.next()).thenReturn(true, false);
    when(orderLinesRs.next()).thenReturn(true, true, false);

    resolver(InOutTargetBuilder.Direction.SALES).lockSource("inv-1");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(conn, times(2)).prepareStatement(sql.capture());
    List<String> statements = sql.getAllValues(); // in call order
    assertTrue(statements.get(0).contains("FROM c_invoice WHERE c_invoice_id = ? FOR UPDATE"));
    assertTrue(statements.get(1).contains("FROM c_orderline"));
    assertTrue(statements.get(1).endsWith("ORDER BY ol.c_orderline_id FOR UPDATE"));
    InOrder order = inOrder(invoicePs, orderLinesPs);
    order.verify(invoicePs).setString(1, "inv-1");
    order.verify(invoicePs).executeQuery();
    order.verify(orderLinesPs).setString(1, "inv-1");
    order.verify(orderLinesPs).executeQuery();
    // Draining the cursor is what acquires every row lock.
    verify(invoiceRs, times(2)).next();
    verify(orderLinesRs, times(3)).next();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static InvoicePendingResolver resolver(InOutTargetBuilder.Direction direction) {
    return new InvoicePendingResolver(direction, FAC_DOC_TYPE::equals);
  }

  /** One row of the pending query; the invoice's doc type is always the FAC one. */
  private static Map<String, Object> row(String invoiceId, String isSoTrx, String docStatus,
      String lineId, String ownPending, String upstreamRemaining) {
    Map<String, Object> row = new HashMap<>();
    row.put("c_invoice_id", invoiceId);
    row.put("issotrx", isSoTrx);
    row.put("docstatus", docStatus);
    row.put("c_doctypetarget_id", FAC_DOC_TYPE);
    row.put("c_invoiceline_id", lineId);
    row.put("own_pending", ownPending != null ? new BigDecimal(ownPending) : null);
    row.put("upstream_remaining",
        upstreamRemaining != null ? new BigDecimal(upstreamRemaining) : null);
    return row;
  }

  /** A ResultSet mock that walks {@code rows} and answers getters by column label. */
  @SafeVarargs
  private static ResultSet rows(Map<String, Object>... rows) throws Exception {
    List<Map<String, Object>> data = Arrays.asList(rows);
    int[] cursor = { -1 };
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenAnswer(inv -> ++cursor[0] < data.size());
    when(rs.getString(anyString()))
        .thenAnswer(inv -> (String) data.get(cursor[0]).get(inv.getArgument(0)));
    when(rs.getBigDecimal(anyString()))
        .thenAnswer(inv -> (BigDecimal) data.get(cursor[0]).get(inv.getArgument(0)));
    return rs;
  }

  private PreparedStatement prepared(ResultSet rs) throws Exception {
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    return ps;
  }

  private static void assertLine(PendingResolver.SourceLine line, String id, String qty) {
    assertEquals(id, line.getSourceLineId());
    assertEquals(0, new BigDecimal(qty).compareTo(line.getPendingQty()),
        id + " pending was " + line.getPendingQty());
  }
}
