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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link SifSubRecordAttachments}.
 *
 * <p>Coverage targets:
 * <ul>
 *   <li>blank/null invoiceId is a no-op (no DB access, no keys added)</li>
 *   <li>each of the 3 sub-record ids is injected under its own JSON key when a matching
 *       row is found</li>
 *   <li>a query that finds no row for a table simply omits that table's key</li>
 *   <li>one table's query failing (module not installed) does not prevent the other
 *       two from being injected</li>
 *   <li>when {@code OBDal} is entirely unavailable (bare unit-test context, no live
 *       database), the exception is swallowed and no key is added</li>
 * </ul>
 */
public class SifSubRecordAttachmentsTest {

  // ── blank/null guard ────────────────────────────────────────────────────

  @Test
  public void testEnrichBlankInvoiceIdIsNoop() throws JSONException {
    JSONObject rec = new JSONObject();
    SifSubRecordAttachments.enrich(rec, "");
    assertFalse(rec.has("aeatsiiFacturaId"));
    assertFalse(rec.has("tbaiSyncInvoiceId"));
    assertFalse(rec.has("invoiceVerifactuId"));
  }

  @Test
  public void testEnrichNullInvoiceIdIsNoop() throws JSONException {
    JSONObject rec = new JSONObject();
    SifSubRecordAttachments.enrich(rec, null);
    assertFalse(rec.has("aeatsiiFacturaId"));
    assertFalse(rec.has("tbaiSyncInvoiceId"));
    assertFalse(rec.has("invoiceVerifactuId"));
  }

  // ── OBDal unavailable (no live DB in this bare unit-test context) ───────

  @Test
  public void testEnrichWithOBDalUnavailableLeavesRecordUnchanged() throws JSONException {
    JSONObject rec = new JSONObject().put("id", "inv-001");
    SifSubRecordAttachments.enrich(rec, "inv-001");
    assertFalse(rec.has("aeatsiiFacturaId"));
    assertFalse(rec.has("tbaiSyncInvoiceId"));
    assertFalse(rec.has("invoiceVerifactuId"));
    assertEquals("inv-001", rec.getString("id"));
  }

  // ── happy path: all 3 tables have a matching row ────────────────────────

  @Test
  public void testEnrichInjectsAllThreeIdsWhenAllTablesHaveRows() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      stubTableQuery(conn, "aeatsii_facturas", "sii-row-001");
      stubTableQuery(conn, "tbai_syncinvoice", "tbai-row-001");
      stubTableQuery(conn, "etvfac_c_invoice_verifactu", "vf-row-001");

      JSONObject rec = new JSONObject().put("id", "inv-001");
      SifSubRecordAttachments.enrich(rec, "inv-001");

      assertEquals("sii-row-001", rec.getString("aeatsiiFacturaId"));
      assertEquals("tbai-row-001", rec.getString("tbaiSyncInvoiceId"));
      assertEquals("vf-row-001", rec.getString("invoiceVerifactuId"));
    }
  }

  // ── most-recent-row resolution (retry history) ──────────────────────────

  /**
   * Guards the "most recent sub-record wins" resolution against a real, already-observed
   * production scenario (ETP-4888 QA investigation): a single invoice can accumulate
   * MULTIPLE {@code etvfac_c_invoice_verifactu} rows across retries — an earlier attempt
   * that failed pre-send validation (no AEAT response, so no attachment), followed by a
   * later successful attempt that actually reached AEAT and carries the response
   * attachment. A plain Mockito {@link ResultSet} mock cannot exercise real SQL
   * {@code ORDER BY}/{@code LIMIT} semantics, so this test instead captures the literal SQL
   * text sent to {@link PreparedStatement} for every one of the 3 tables and asserts it
   * still carries {@code ORDER BY created DESC} and {@code LIMIT 1} — the only thing
   * standing between "most recent wins" and "whichever row the DB happens to return first"
   * if a future refactor ever drops that clause.
   */
  @Test
  public void testEnrichQueriesOrderByCreatedDescLimitOneForAllThreeTables() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getString(1)).thenReturn("some-id");
      when(ps.executeQuery()).thenReturn(rs);
      ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
      when(conn.prepareStatement(sqlCaptor.capture())).thenReturn(ps);

      JSONObject rec = new JSONObject().put("id", "inv-004");
      SifSubRecordAttachments.enrich(rec, "inv-004");

      java.util.List<String> queries = sqlCaptor.getAllValues();
      assertEquals(3, queries.size());
      for (String sql : queries) {
        assertTrue("Query must order by created DESC to pick the most recent retry: " + sql,
            sql.contains("ORDER BY created DESC"));
        assertTrue("Query must be limited to 1 row: " + sql, sql.contains("LIMIT 1"));
        assertTrue("Query must filter to active rows only: " + sql, sql.contains("isactive = 'Y'"));
      }
    }
  }

  // ── no matching row for any table ───────────────────────────────────────

  @Test
  public void testEnrichSkipsAllTablesWhenNoRowsFound() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(false);
      when(ps.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      JSONObject rec = new JSONObject().put("id", "inv-002");
      SifSubRecordAttachments.enrich(rec, "inv-002");

      assertFalse(rec.has("aeatsiiFacturaId"));
      assertFalse(rec.has("tbaiSyncInvoiceId"));
      assertFalse(rec.has("invoiceVerifactuId"));
    }
  }

  // ── one table's module not installed does not block the others ────────

  @Test
  public void testEnrichOneTableThrowingDoesNotBlockOthers() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // Simulate the TBAI module not being installed: its table lookup throws.
      when(conn.prepareStatement(contains("tbai_syncinvoice")))
          .thenThrow(new SQLException("relation \"tbai_syncinvoice\" does not exist"));

      stubTableQuery(conn, "aeatsii_facturas", "sii-row-001");
      stubTableQuery(conn, "etvfac_c_invoice_verifactu", "vf-row-001");

      JSONObject rec = new JSONObject().put("id", "inv-003");
      SifSubRecordAttachments.enrich(rec, "inv-003");

      assertEquals("sii-row-001", rec.getString("aeatsiiFacturaId"));
      assertFalse(rec.has("tbaiSyncInvoiceId"));
      assertEquals("vf-row-001", rec.getString("invoiceVerifactuId"));
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static void stubTableQuery(Connection conn, String tableName, String returnedId)
      throws SQLException {
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(returnedId);
    when(ps.executeQuery()).thenReturn(rs);
    when(conn.prepareStatement(contains(tableName))).thenReturn(ps);
  }
}
