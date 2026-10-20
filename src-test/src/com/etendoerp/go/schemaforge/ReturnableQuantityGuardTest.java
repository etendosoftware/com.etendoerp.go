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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link ReturnableQuantityGuard}: the lock-then-read sequence, the placeholder
 * binding, which requested line is refused and how the 409 is shaped.
 *
 * <p>The guard is called by three customizations whose own tests stub it; this class is the one
 * that exercises its SQL contract. The SQL itself cannot run here, so the filters that live in
 * it (inactive source line, voided returns) are asserted on the statement that is issued.
 *
 * @covers com.etendoerp.go.schemaforge.ReturnableQuantityGuard
 */
public class ReturnableQuantityGuardTest {

  private static final String LOCK_MARKER = "ORDER BY m_inoutline_id FOR UPDATE";
  private static final String RETURNABLE_MARKER = "SELECT sil.m_inoutline_id, p.name,";

  private MockedStatic<OBDal> obDalStatic;
  private OBDal dal;
  private Connection conn;
  private PreparedStatement lockPs;
  private PreparedStatement queryPs;
  private ResultSet rs;

  @Before
  public void setUp() throws Exception {
    dal = mock(OBDal.class);
    conn = mock(Connection.class);
    lockPs = mock(PreparedStatement.class);
    queryPs = mock(PreparedStatement.class);
    rs = mock(ResultSet.class);
    obDalStatic = Mockito.mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(anyString())).thenAnswer(
        inv -> ((String) inv.getArgument(0)).contains(LOCK_MARKER) ? lockPs : queryPs);
    when(queryPs.executeQuery()).thenReturn(rs);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
  }

  private static Map<String, BigDecimal> requested(String... idQtyPairs) {
    Map<String, BigDecimal> map = new LinkedHashMap<>();
    for (int i = 0; i < idQtyPairs.length; i += 2) {
      map.put(idQtyPairs[i], new BigDecimal(idQtyPairs[i + 1]));
    }
    return map;
  }

  /** One returnable row per call to {@code rs.next()}: id, product, shipped, already returned. */
  private void stubRows(String[]... rows) throws SQLException {
    int[] cursor = { -1 };
    when(rs.next()).thenAnswer(inv -> ++cursor[0] < rows.length);
    when(rs.getString(1)).thenAnswer(inv -> rows[cursor[0]][0]);
    when(rs.getString(2)).thenAnswer(inv -> rows[cursor[0]][1]);
    when(rs.getBigDecimal(3)).thenAnswer(inv -> new BigDecimal(rows[cursor[0]][2]));
    when(rs.getBigDecimal(4)).thenAnswer(inv -> new BigDecimal(rows[cursor[0]][3]));
  }

  // ── findExcess — lock and binding ─────────────────────────────────────────

  /**
   * The source lines are row-locked by a separate, id-ordered {@code FOR UPDATE} statement
   * BEFORE the returnable quantity is read — so a concurrent return is checked after this one,
   * never against the same figure. A POST (no edited line) binds {@code ""} to the exclusion
   * placeholder, and the read keeps voided returns out of the sum.
   */
  @Test
  public void findExcessLocksTheSourceLinesBeforeReadingWhatIsReturnable() throws Exception {
    stubRows(new String[] { "src-1", "Widget", "5", "0" });

    assertNull(ReturnableQuantityGuard.findExcess(requested("src-1", "2"), null));

    InOrder order = inOrder(conn, lockPs, queryPs);
    order.verify(conn).prepareStatement(argThat(sql -> sql.contains(LOCK_MARKER)));
    order.verify(lockPs).execute();
    order.verify(conn).prepareStatement(argThat(sql -> sql.startsWith(RETURNABLE_MARKER)
        && sql.contains("r.docstatus <> 'VO'") && sql.endsWith("IN (?)")));
    order.verify(queryPs).executeQuery();
    verify(queryPs).setString(1, "");
    verify(queryPs).setString(2, "src-1");
  }

  /**
   * With several requested lines, both statements get one placeholder per id. The returnable
   * read binds the edited return line first (it is excluded from the sum, so re-saving a line
   * never counts it twice) and the source ids from index 2; the lock binds them from index 1.
   */
  @Test
  public void findExcessBindsEveryIdAfterTheEditedReturnLine() throws Exception {
    stubRows(new String[] { "src-a", "A", "5", "0" }, new String[] { "src-b", "B", "5", "0" },
        new String[] { "src-c", "C", "5", "0" });

    assertNull(ReturnableQuantityGuard.findExcess(
        requested("src-a", "1", "src-b", "1", "src-c", "1"), "rl-9"));

    verify(conn).prepareStatement(argThat(sql -> sql.endsWith("IN (?,?,?)")));
    verify(conn).prepareStatement(argThat(sql -> sql.contains("IN (?,?,?) " + LOCK_MARKER)));
    verify(lockPs).setString(1, "src-a");
    verify(lockPs).setString(2, "src-b");
    verify(lockPs).setString(3, "src-c");
    verify(queryPs).setString(1, "rl-9");
    verify(queryPs).setString(2, "src-a");
    verify(queryPs).setString(3, "src-b");
    verify(queryPs).setString(4, "src-c");
  }

  /** An empty or missing request is a no-op: no connection, no lock, no read. */
  @Test
  public void findExcessIsANoOpWithoutRequestedLines() throws Exception {
    assertNull(ReturnableQuantityGuard.findExcess(null, null));
    assertNull(ReturnableQuantityGuard.findExcess(Collections.emptyMap(), "rl-1"));
    verify(dal, never()).getConnection();
  }

  /**
   * Fails CLOSED: when the lock cannot be taken the error propagates (the caller answers 500)
   * and the returnable quantity is never read, so no unchecked return slips through.
   */
  @Test
  public void refuseIfExceededPropagatesALockFailure() throws Exception {
    when(lockPs.execute()).thenThrow(new SQLException("lock timeout"));

    assertThrows(SQLException.class,
        () -> ReturnableQuantityGuard.refuseIfExceeded(requested("src-1", "1"), null));

    verify(conn, never()).prepareStatement(argThat(sql -> sql.startsWith(RETURNABLE_MARKER)));
  }

  // ── findExcess — which line is refused ────────────────────────────────────

  /**
   * The first row whose request plus what is already returned exceeds the shipped quantity is
   * the refusal; the rows after it are not read. A row that exactly fills the line passes.
   */
  @Test
  public void findExcessReturnsTheFirstLineThatGoesOver() throws Exception {
    stubRows(new String[] { "src-a", "A", "5", "3" }, new String[] { "src-b", "B", "3", "2" },
        new String[] { "src-c", "C", "1", "1" });

    ReturnableQuantityGuard.Excess excess = ReturnableQuantityGuard.findExcess(
        requested("src-a", "2", "src-b", "2", "src-c", "9"), null);

    assertNotNull(excess);
    assertEquals("src-b", excess.sourceLineId);
    assertEquals("B", excess.productName);
    assertEquals(0, new BigDecimal("3").compareTo(excess.shipped));
    assertEquals(0, new BigDecimal("2").compareTo(excess.alreadyReturned));
    assertEquals(0, new BigDecimal("2").compareTo(excess.requested));
    assertEquals(0, BigDecimal.ONE.compareTo(excess.available()));
    verify(rs, times(2)).next();
  }

  /**
   * An INACTIVE source line has nothing returnable: the read maps it to shipped 0 (the row stays,
   * so the refusal can still name the product). A product name the join cannot resolve is shown
   * as an empty string rather than {@code null}.
   */
  @Test
  public void findExcessRefusesAnInactiveSourceLineNamingItsProduct() throws Exception {
    String[][] cases = { { "Widget", "Widget" }, { null, "" } };
    for (String[] c : cases) {
      Mockito.reset(rs);
      stubRows(new String[] { "src-1", c[0], "0", "0" });

      ReturnableQuantityGuard.Excess excess =
          ReturnableQuantityGuard.findExcess(requested("src-1", "1"), null);

      assertNotNull("product " + c[0], excess);
      assertEquals(c[1], excess.productName);
      assertEquals(0, BigDecimal.ZERO.compareTo(excess.available()));
    }
    verify(conn, times(2)).prepareStatement(argThat(sql -> sql.startsWith(RETURNABLE_MARKER)
        && sql.contains("CASE WHEN sil.isactive = 'Y' THEN ABS(sil.movementqty) ELSE 0 END")));
  }

  /**
   * A requested id the read does not return — not a shipment line at all — is refused with
   * nothing returnable, instead of passing the check silently.
   */
  @Test
  public void findExcessRefusesAnIdTheReadDoesNotReturn() throws Exception {
    stubRows(new String[] { "src-1", "Widget", "5", "0" });

    ReturnableQuantityGuard.Excess excess =
        ReturnableQuantityGuard.findExcess(requested("src-1", "1", "ghost", "2"), null);

    assertNotNull(excess);
    assertEquals("ghost", excess.sourceLineId);
    assertEquals("", excess.productName);
    assertEquals(0, BigDecimal.ZERO.compareTo(excess.shipped));
    assertEquals(0, new BigDecimal("2").compareTo(excess.requested));
  }

  // ── findStoredReturnLine ──────────────────────────────────────────────────

  /**
   * A blank id reads nothing; an existing line yields its source line and magnitude; a missing
   * line yields {@code null}.
   */
  @Test
  public void findStoredReturnLineReadsTheSourceLineAndQuantity() throws Exception {
    assertNull(ReturnableQuantityGuard.findStoredReturnLine(" "));
    verify(dal, never()).getConnection();

    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("src-1");
    when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("4"));

    ReturnableQuantityGuard.StoredReturnLine stored =
        ReturnableQuantityGuard.findStoredReturnLine("rl-1");

    assertNotNull(stored);
    assertEquals("src-1", stored.sourceLineId);
    assertEquals(0, new BigDecimal("4").compareTo(stored.quantity));
    verify(queryPs).setString(1, "rl-1");
    assertNull(ReturnableQuantityGuard.findStoredReturnLine("rl-gone"));
  }

  // ── exceededResponse ──────────────────────────────────────────────────────

  /**
   * The 409 is the structured NEO error: the code, the translatable key and its params for the
   * SPA, and an English message carrying the numbers an agent needs. Numbers are plain (no
   * trailing zeros, no exponent), and "available" never goes below 0.
   */
  @Test
  public void exceededResponseCarriesTheNumbersAsPlainValues() throws Exception {
    NeoResponse response = ReturnableQuantityGuard.exceededResponse(new ReturnableQuantityGuard.Excess(
        "src-1", "Widget", new BigDecimal("5.000"), new BigDecimal("2.50"), new BigDecimal("4.0")));

    assertEquals(HttpServletResponse.SC_CONFLICT, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(ReturnableQuantityGuard.RETURN_QTY_EXCEEDED_CODE, error.getString("code"));
    assertEquals(ReturnableQuantityGuard.RETURN_QTY_EXCEEDED_KEY,
        error.getJSONArray("messageKeys").getString(0));
    assertEquals("Cannot return 4 of Widget: only 2.5 of the 5 shipped can still be returned"
        + " (draft and completed returns count).", error.getString("message"));
    JSONObject params = error.getJSONObject("messageParams");
    assertEquals("4", params.getString("requested"));
    assertEquals("2.5", params.getString("available"));
    assertEquals("5", params.getString("shipped"));
    assertEquals("src-1", params.getString("sourceLineId"));

    NeoResponse overdrawn = ReturnableQuantityGuard.exceededResponse(new ReturnableQuantityGuard.Excess(
        "src-2", "Gadget", new BigDecimal("3"), new BigDecimal("5"), new BigDecimal("1E+1")));
    JSONObject overdrawnParams =
        overdrawn.getBody().getJSONObject("error").getJSONObject("messageParams");
    assertEquals("0", overdrawnParams.getString("available"));
    assertEquals("10", overdrawnParams.getString("requested"));
  }

  /**
   * When the structured body cannot be built the refusal is still a 409 — the request is never
   * let through, and never turned into a different status.
   */
  @Test
  public void exceededResponseStaysA409WhenTheBodyCannotBeBuilt() {
    ReturnableQuantityGuard.Excess excess = new ReturnableQuantityGuard.Excess(
        "src-1", "Widget", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN);

    try (MockedConstruction<JSONObject> json = Mockito.mockConstruction(JSONObject.class,
        (mock, ctx) -> {
          when(mock.put(anyString(), any(Object.class))).thenThrow(new JSONException("boom"));
          when(mock.put(anyString(), anyInt())).thenThrow(new JSONException("boom"));
        })) {
      NeoResponse response = ReturnableQuantityGuard.exceededResponse(excess);

      assertEquals(HttpServletResponse.SC_CONFLICT, response.getHttpStatus());
      assertTrue(json.constructed().size() >= 1);
    }
  }
}
