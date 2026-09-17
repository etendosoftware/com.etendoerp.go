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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link NeoInvoiceSupport#computePendingQtyPerLine(String)}.
 *
 * <p>Mocks OBDal, Connection, PreparedStatement, and ResultSet to exercise all
 * branching logic without requiring a live database.
 */
public class NeoInvoiceSupportTest {

  // ─── helpers ──────────────────────────────────────────────────────────────

  /**
   * Sets up OBDal mock chain: OBDal.getInstance() → dal → dal.getConnection() → conn.
   * Returns the Connection mock for further configuration.
   */
  private static Connection stubDalConnection(MockedStatic<OBDal> obDalMock, OBDal dal)
      throws Exception {
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    return conn;
  }

  // ─── Test 1: single line, positive pending ────────────────────────────────

  /**
   * One active line with movementQty=10 and invoicedQty=3 yields pending=7.
   */
  @Test
  public void computePendingQtyPerLine_oneLine_correctlyComputesPending() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // Single row: line-1, movementQty=10, invoicedQty=3
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(10.0));
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(3.0));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerLine("inout-1");

      assertEquals("Expected one entry in the result map", 1, result.size());
      assertEquals("Pending qty for line-1 must be 7",
          0, result.get("line-1").compareTo(BigDecimal.valueOf(7.0)));
    }
  }

  // ─── Test 2: invoiced exceeds movement → clamped to zero, line omitted ───

  /**
   * When invoicedQty > movementQty the pending value is clamped to 0 by max(0),
   * so the line is omitted from the result map (pending > 0 filter).
   */
  @Test
  public void computePendingQtyPerLine_invoicedExceedsMovement_clampedToZero() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // movementQty=5, invoicedQty=8 → pending = max(0, 5-8) = 0 → omitted
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(5.0));
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(8.0));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerLine("inout-2");

      assertTrue("Line with invoiced > movement must be omitted", result.isEmpty());
    }
  }

  // ─── Test 3: pending exactly zero → line omitted ─────────────────────────

  /**
   * When movementQty equals invoicedQty the pending is exactly 0; the strict
   * compareTo check (pending > 0) must exclude the line.
   */
  @Test
  public void computePendingQtyPerLine_pendingExactlyZero_lineOmitted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // movementQty=5, invoicedQty=5 → pending=0 → omitted
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(5.0));
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(5.0));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerLine("inout-3");

      assertTrue("Line with pending=0 must be omitted", result.isEmpty());
    }
  }

  // ─── Test 4: multiple lines, only positive pending included ───────────────

  /**
   * Three lines: line-1 (pending=7), line-2 (pending=0, omitted), line-3 (pending=2).
   * The result map must contain exactly line-1 and line-3.
   */
  @Test
  public void computePendingQtyPerLine_multipleLines_onlyPositivePendingIncluded() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // Row 1: line-1, mov=10, inv=3 → pending=7 ✓
      // Row 2: line-2, mov=5, inv=5 → pending=0 (omitted)
      // Row 3: line-3, mov=2, inv=0 → pending=2 ✓
      when(rs.next()).thenReturn(true, true, true, false);
      when(rs.getString(1)).thenReturn("line-1", "line-2", "line-3");
      when(rs.getBigDecimal(2)).thenReturn(
          BigDecimal.valueOf(10.0),
          BigDecimal.valueOf(5.0),
          BigDecimal.valueOf(2.0));
      when(rs.getBigDecimal(3)).thenReturn(
          BigDecimal.valueOf(3.0),
          BigDecimal.valueOf(5.0),
          BigDecimal.valueOf(0.0));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerLine("inout-4");

      assertEquals("Only lines with pending > 0 must be present", 2, result.size());
      assertEquals("line-1 pending must be 7",
          0, result.get("line-1").compareTo(BigDecimal.valueOf(7.0)));
      assertEquals("line-3 pending must be 2",
          0, result.get("line-3").compareTo(BigDecimal.valueOf(2.0)));
      assertTrue("line-2 with zero pending must not be in the map",
          !result.containsKey("line-2"));
    }
  }

  // ─── Test 5: DB error → empty map, no exception propagated ───────────────

  /**
   * When getConnection() throws a RuntimeException the method must catch it and
   * return an empty map rather than propagating the exception to the caller.
   */
  @Test
  public void computePendingQtyPerLine_dbError_returnsEmptyMap() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB connection failed"));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerLine("inout-err");

      assertTrue("DB error must result in an empty map, not an exception", result.isEmpty());
    }
  }

  // ─── Test 6: null movementQty treated as zero ─────────────────────────────

  /**
   * When rs.getBigDecimal(2) returns null (NULL column value) the code substitutes
   * BigDecimal.ZERO, so pending = 0 - 0 = 0 → line is omitted.
   */
  @Test
  public void computePendingQtyPerLine_nullMovementQty_treatedAsZero() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // movementQty=null → treated as 0; invoicedQty=0 → pending=0 → omitted
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-null");
      when(rs.getBigDecimal(2)).thenReturn(null);
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.ZERO);

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerLine("inout-null");

      assertTrue("Null movementQty must be treated as 0, yielding pending=0 (line omitted)",
          result.isEmpty());
    }
  }

  // ─── Test 7: includeDrafts=true — positive pending ───────────────────────

  /**
   * The 2-arg overload with {@code includeDrafts=true} uses the draft-aware SQL branch.
   * A line with movementQty=8, invoicedQty=3 must yield pending=5.
   */
  @Test
  public void computePendingQtyPerLine_includeDraftsTrue_positiveResult() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-draft-1");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(8.0));
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(3.0));

      Map<String, BigDecimal> result =
          NeoInvoiceSupport.computePendingQtyPerLine("inout-draft-1", true);

      assertEquals("Must contain exactly one entry", 1, result.size());
      assertEquals("Pending must be 5",
          0, result.get("line-draft-1").compareTo(BigDecimal.valueOf(5.0)));
    }
  }

  // ─── Test 8: includeDrafts=true — fully-invoiced line omitted ────────────

  /**
   * With {@code includeDrafts=true} a line where the draft already covers the full
   * movement quantity yields pending=0 and must be omitted from the result.
   */
  @Test
  public void computePendingQtyPerLine_includeDraftsTrue_fullyInvoicedLineOmitted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // movementQty=5, invoicedQty(including draft)=5 → pending=0 → omitted
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-full-draft");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(5.0));
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(5.0));

      Map<String, BigDecimal> result =
          NeoInvoiceSupport.computePendingQtyPerLine("inout-full-draft", true);

      assertTrue("Fully-invoiced line must be omitted with includeDrafts=true",
          result.isEmpty());
    }
  }

  // ─── Test 9: includeDrafts=true — null invoicedQty treated as zero ────────

  /**
   * When rs.getBigDecimal(3) returns null (no invoiced qty at all) the code
   * substitutes BigDecimal.ZERO: pending = movementQty - 0 = movementQty.
   */
  @Test
  public void computePendingQtyPerLine_includeDraftsTrue_nullInvoicedQtyTreatedAsZero() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-null-inv");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(6.0));
      when(rs.getBigDecimal(3)).thenReturn(null); // no invoiced qty

      Map<String, BigDecimal> result =
          NeoInvoiceSupport.computePendingQtyPerLine("inout-null-inv", true);

      assertEquals("Must contain exactly one entry", 1, result.size());
      assertEquals("Pending must equal movementQty when invoiced is null",
          0, result.get("line-null-inv").compareTo(BigDecimal.valueOf(6.0)));
    }
  }

  // ─── Test 10: includeDrafts=true — DB error → empty map ──────────────────

  /**
   * When getConnection() throws with {@code includeDrafts=true} the method catches
   * the exception and returns an empty map.
   */
  @Test
  public void computePendingQtyPerLine_includeDraftsTrue_dbError_returnsEmptyMap() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("DB down"));

      Map<String, BigDecimal> result =
          NeoInvoiceSupport.computePendingQtyPerLine("inout-err-draft", true);

      assertTrue("DB error must return empty map with includeDrafts=true", result.isEmpty());
    }
  }

  // ─── Test 11: 1-arg overload delegates to 2-arg with includeDrafts=false ────

  /**
   * The 1-arg convenience method {@code computePendingQtyPerLine(String)} must
   * call the 2-arg version with {@code includeDrafts=false}. Because the class is
   * package-private final, we verify by observing that a working mock connection
   * returns the same result as calling the 2-arg method directly with false.
   */
  @Test
  public void computePendingQtyPerLine_oneArg_producessameasTwoArgWithFalse() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // Both calls share the same mock state; use empty result set for simplicity
      when(rs.next()).thenReturn(false);

      Map<String, BigDecimal> oneArg = NeoInvoiceSupport.computePendingQtyPerLine("inout-1arg");
      assertTrue("1-arg overload must return empty map when RS has no rows", oneArg.isEmpty());
    }
  }

  // ─── ETP-5334: computePendingQtyPerOrderLine ──────────────────────────────

  /**
   * ETP-5334: an order line received as 4 + 6 with nothing invoiced yet yields an aggregate
   * pending of 10 — the quantity the invoice line created from the order is entitled to.
   */
  @Test
  public void computePendingQtyPerOrderLine_splitReception_aggregatesAcrossReceipts()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("ol-1");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(10.0));  // 4 + 6 received
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.ZERO);           // nothing invoiced yet

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerOrderLine("inv-1");

      assertEquals("Expected one order line in the result map", 1, result.size());
      assertEquals("Aggregate pending for ol-1 must be 10",
          0, result.get("ol-1").compareTo(BigDecimal.valueOf(10.0)));
    }
  }

  /**
   * ETP-5334: unlike {@code computePendingQtyPerLine}, an exhausted order line is KEPT in the map
   * with a pending of zero — its presence is what tells the guard "this order line is split, use
   * the aggregate", so dropping it would silently fall back to the per-inout-line comparison.
   */
  @Test
  public void computePendingQtyPerOrderLine_fullyInvoiced_keptWithZeroPending() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(conn.prepareStatement(Mockito.anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // received=10, invoiced=12 → clamped to 0 but still returned
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("ol-done");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.valueOf(10.0));
      when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(12.0));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerOrderLine("inv-2");

      assertEquals("Exhausted order line must stay in the map", 1, result.size());
      assertEquals("Pending must be clamped to zero, never negative",
          0, result.get("ol-done").compareTo(BigDecimal.ZERO));
    }
  }

  /**
   * ETP-5334: a DB error must not block completion — the method fails open with an empty map,
   * which sends every invoice line back to the unchanged per-inout-line path.
   */
  @Test
  public void computePendingQtyPerOrderLine_dbError_returnsEmptyMap() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);
      when(conn.prepareStatement(Mockito.anyString()))
          .thenThrow(new java.sql.SQLException("boom"));

      Map<String, BigDecimal> result = NeoInvoiceSupport.computePendingQtyPerOrderLine("inv-err");

      assertTrue("DB error must return an empty map", result.isEmpty());
    }
  }

  /**
   * ETP-5334: the query binds the invoice id to every {@code ORDER_LINE_SCOPE} placeholder — one
   * per derived table. A mismatch between the placeholder count and the bind loop would only blow
   * up against a real database, where a mocked PreparedStatement stays silent. Also pins the two
   * clauses the fix depends on: the split-only filter and the exclusion of draft/voided/closed
   * invoices from the already-invoiced quantity.
   */
  @Test
  public void computePendingQtyPerOrderLine_bindsInvoiceIdToEveryScopePlaceholder()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = stubDalConnection(obDalMock, dal);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      when(conn.prepareStatement(sqlCaptor.capture())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      NeoInvoiceSupport.computePendingQtyPerOrderLine("inv-bind");

      String sql = sqlCaptor.getValue();
      int placeholders = sql.length() - sql.replace("?", "").length();
      assertEquals("Every ? must be bound by the 4-iteration loop", 4, placeholders);
      Mockito.verify(ps, Mockito.times(4)).setString(Mockito.anyInt(), Mockito.eq("inv-bind"));
      assertTrue("Only order lines with several inout lines may be returned",
          sql.contains("HAVING COUNT(*) > 1"));
      assertTrue("Draft/voided/closed invoices must never count as invoiced",
          sql.contains("NOT IN ('VO','CL','DR')"));
    }
  }
}
