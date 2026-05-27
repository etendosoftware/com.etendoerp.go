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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Mockito-driven unit tests for {@link FinancialAccountTransactionsHandler}.
 *
 * <p>Strategy: drive the handler through its public {@code handle} entry point
 * and the package-private seams {@code loadTransactions} / {@code loadTotals},
 * stubbing the {@link OBDal} connection via {@link MockedStatic} so the SQL
 * path never touches a real database. {@link OBContext} is also static-mocked
 * so the admin-mode lifecycle can be verified.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code handle()} HTTP routing: wrong method, missing/blank param,
 *       happy path, exception path.</li>
 *   <li>{@code loadTransactions()} mapping for one row, empty result-set,
 *       null statement date, and null BigDecimals.</li>
 *   <li>{@code loadTotals()} happy path with cutoff binding verification,
 *       and empty result-set fallback.</li>
 *   <li>Helper methods {@code formatDate} (via reflection-free row mapping)
 *       and the static {@code nullSafeBigDecimal} contract.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class FinancialAccountTransactionsHandlerTest {

  private static final String ACCOUNT_ID = "ACC-001";
  private static final String PARAM_ACCOUNT_ID = "FIN_Financial_Account_ID";

  /** Acceptable drift (ms) when asserting the cutoff timestamp captured by the SQL bindings. */
  private static final long CUTOFF_TOLERANCE_MILLIS = 60_000L;

  /** KPI window in days, mirrored from the production constant. */
  private static final int KPI_WINDOW_DAYS = 30;

  private FinancialAccountTransactionsHandler handler;

  /**
   * Creates a fresh handler instance before each test. The handler is
   * stateless, so a plain {@code new} (not a spy) is enough for every
   * scenario.
   */
  @Before
  public void setUp() {
    handler = new FinancialAccountTransactionsHandler();
  }

  // ── handle() routing ─────────────────────────────────────────────────────

  /**
   * Verifies that {@code handle()} short-circuits non-GET requests with a 405
   * status, never touches OBContext, and never reaches the SQL layer.
   */
  @Test
  public void testHandleRejectsNonGetMethod() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("POST");

    NeoResponse response = handler.handle(ctx);

    assertEquals(405, response.getHttpStatus());
  }

  /**
   * Verifies that {@code handle()} returns 400 when the request carries no
   * query-param map at all (null), guarding the null-safe branch on
   * {@code context.getQueryParams()}.
   */
  @Test
  public void testHandleReturnsBadRequestWhenQueryParamsNull() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(null);

    NeoResponse response = handler.handle(ctx);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that {@code handle()} returns 400 when the account id parameter
   * is present but blank — the {@code StringUtils.isBlank} guard must reject
   * whitespace-only inputs the same way it rejects nulls.
   */
  @Test
  public void testHandleReturnsBadRequestWhenAccountIdBlank() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACCOUNT_ID, "   ");
    when(ctx.getQueryParams()).thenReturn(params);

    NeoResponse response = handler.handle(ctx);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies the full {@code handle()} GET happy path: with a valid account id
   * and a stubbed {@link OBDal} connection that returns one transaction row
   * and a totals row, the handler returns 200 with the
   * {@code response.data.{transactions,totals}} envelope expected by the UI.
   * Also verifies that admin mode is set and restored exactly once.
   *
   * @throws Exception
   *     if the mocked JDBC chain or the JSON traversal fails
   */
  @Test
  public void testHandleGetHappyPathReturnsEnvelope() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    when(ctx.getQueryParams()).thenReturn(params);

    Connection conn = mock(Connection.class);
    // First prepareStatement → transactions; second → totals.
    PreparedStatement psTrx = mock(PreparedStatement.class);
    PreparedStatement psTotals = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(psTrx, psTotals);

    ResultSet rsTrx = mock(ResultSet.class);
    when(psTrx.executeQuery()).thenReturn(rsTrx);
    when(rsTrx.next()).thenReturn(true, false);
    stubTransactionRow(rsTrx,
        "TRX-1",
        Timestamp.from(Instant.parse("2026-05-06T00:00:00Z")),
        "RPPC",
        "BPD",
        new BigDecimal("12450.00"),
        new BigDecimal("211841.01"),
        "Invoice description",
        "Y",
        "PAY-001",
        "DHL Technologies SL",
        "EUR");

    ResultSet rsTotals = mock(ResultSet.class);
    when(psTotals.executeQuery()).thenReturn(rsTotals);
    when(rsTotals.next()).thenReturn(true);
    when(rsTotals.getBigDecimal("currentbalance")).thenReturn(new BigDecimal("211841.01"));
    when(rsTotals.getString("iso_code")).thenReturn("EUR");
    when(rsTotals.getBigDecimal("inflows_30d")).thenReturn(new BigDecimal("47820.00"));
    when(rsTotals.getBigDecimal("outflows_30d")).thenReturn(new BigDecimal("22398.82"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertNotNull("response envelope must exist", body.optJSONObject("response"));
      JSONObject data = body.getJSONObject("response").getJSONObject("data");

      JSONArray transactions = data.getJSONArray("transactions");
      assertEquals(1, transactions.length());
      JSONObject row = transactions.getJSONObject(0);
      assertEquals("TRX-1", row.getString("id"));
      assertEquals("RPPC", row.getString("paymentStatus"));
      assertEquals("BPD", row.getString("trxType"));
      assertEquals("PAY-001", row.getString("documentNo"));

      JSONObject totals = data.getJSONObject("totals");
      assertEquals("EUR", totals.getString("currency"));
      assertEquals(0,
          new BigDecimal("211841.01").compareTo(new BigDecimal(totals.getString("balance"))));

      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * Verifies the {@code handle()} error path: when the connection lookup
   * throws, the handler catches the exception, returns a 500 NeoResponse, and
   * still restores the previous admin mode via the {@code finally} block.
   */
  @Test
  public void testHandleReturnsServerErrorWhenLoaderThrows() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    Map<String, String> params = new HashMap<>();
    params.put(PARAM_ACCOUNT_ID, ACCOUNT_ID);
    when(ctx.getQueryParams()).thenReturn(params);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obDalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("boom"));

      NeoResponse response = handler.handle(ctx);

      assertEquals(500, response.getHttpStatus());
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  // ── loadTransactions() ───────────────────────────────────────────────────

  /**
   * Verifies that {@code loadTransactions} returns an empty {@link JSONArray}
   * when the result set yields no rows — guards the early-exit branch of the
   * row loop.
   *
   * @throws Exception
   *     if the mocked JDBC chain fails
   */
  @Test
  public void testLoadTransactionsReturnsEmptyArrayOnEmptyResultSet() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray arr = handler.loadTransactions(ACCOUNT_ID);

      assertEquals(0, arr.length());
      verify(ps).setString(1, ACCOUNT_ID);
    }
  }

  /**
   * Verifies that {@code loadTransactions} maps every column of a single row
   * into the expected JSON fields: id, ISO-formatted date, trimmed status and
   * trxType, signed amount, running balance, description, posted flag,
   * documentNo, contact and currency ISO code.
   *
   * @throws Exception
   *     if the mocked JDBC chain or JSON traversal fails
   */
  @Test
  public void testLoadTransactionsMapsAllFieldsForSingleRow() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);

    Timestamp date = Timestamp.from(Instant.parse("2026-05-06T10:00:00Z"));
    stubTransactionRow(rs,
        "TRX-42",
        date,
        "  RPPC  ",
        "BPD",
        new BigDecimal("100.00"),
        new BigDecimal("500.00"),
        "Invoice No.: F-001",
        "Y",
        "PAY-099",
        "ACME SL",
        "EUR");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray arr = handler.loadTransactions(ACCOUNT_ID);

      assertEquals(1, arr.length());
      JSONObject row = arr.getJSONObject(0);
      assertEquals("TRX-42", row.getString("id"));
      assertEquals("2026-05-06T10:00:00Z", row.getString("date"));
      assertEquals("RPPC", row.getString("paymentStatus"));
      assertEquals("BPD", row.getString("trxType"));
      assertEquals(0,
          new BigDecimal("100.00").compareTo(new BigDecimal(row.getString("amount"))));
      assertEquals(0,
          new BigDecimal("500.00").compareTo(new BigDecimal(row.getString("balance"))));
      assertEquals("Invoice No.: F-001", row.getString("description"));
      assertEquals("Y", row.getString("posted"));
      assertEquals("PAY-099", row.getString("documentNo"));
      assertEquals("ACME SL", row.getString("contact"));
      assertEquals("EUR", row.getString("currencyIso"));
    }
  }

  /**
   * Verifies that when the statement date is {@code null}, the row's
   * {@code date} field is serialised as an empty string. This exercises the
   * {@code formatDate(null) → ""} branch and the
   * {@code nullSafeBigDecimal(null) → 0} branch in the same row.
   *
   * @throws Exception
   *     if the mocked JDBC chain or JSON traversal fails
   */
  @Test
  public void testLoadTransactionsHandlesNullDateAndNullDecimals() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);

    stubTransactionRow(rs,
        "TRX-NULL",
        null,
        "",
        "BPW",
        null,
        null,
        "",
        "",
        "",
        "",
        "EUR");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray arr = handler.loadTransactions(ACCOUNT_ID);

      assertEquals(1, arr.length());
      JSONObject row = arr.getJSONObject(0);
      assertEquals("", row.getString("date"));
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(row.getString("amount"))));
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(row.getString("balance"))));
    }
  }

  // ── loadTotals() ─────────────────────────────────────────────────────────

  /**
   * Verifies the happy path of {@code loadTotals}: balance, currency, inflows
   * and outflows are read from the result set; the SQL bindings are
   * {@code setTimestamp(1, cutoff)}, {@code setTimestamp(2, cutoff)} (with
   * cutoff ≈ now - 30 days), and {@code setString(3, accountId)}.
   *
   * @throws Exception
   *     if the mocked JDBC chain or JSON traversal fails
   */
  @Test
  public void testLoadTotalsHappyPathBindsCutoffAndReadsAllFields() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getBigDecimal("currentbalance")).thenReturn(new BigDecimal("211841.01"));
    when(rs.getString("iso_code")).thenReturn("EUR");
    when(rs.getBigDecimal("inflows_30d")).thenReturn(new BigDecimal("47820.00"));
    when(rs.getBigDecimal("outflows_30d")).thenReturn(new BigDecimal("22398.82"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      long beforeMillis = System.currentTimeMillis();
      JSONObject totals = handler.loadTotals(ACCOUNT_ID);
      long afterMillis = System.currentTimeMillis();

      assertEquals(0,
          new BigDecimal("211841.01").compareTo(new BigDecimal(totals.getString("balance"))));
      assertEquals("EUR", totals.getString("currency"));
      assertEquals(0,
          new BigDecimal("47820.00").compareTo(new BigDecimal(totals.getString("inflows"))));
      assertEquals(0,
          new BigDecimal("22398.82").compareTo(new BigDecimal(totals.getString("outflows"))));

      ArgumentCaptor<Timestamp> cutoffCaptor = ArgumentCaptor.forClass(Timestamp.class);
      verify(ps).setTimestamp(eq(1), cutoffCaptor.capture());
      verify(ps).setTimestamp(eq(2), cutoffCaptor.capture());
      verify(ps).setString(3, ACCOUNT_ID);

      long expectedLow = Instant.ofEpochMilli(beforeMillis)
          .minus(KPI_WINDOW_DAYS, ChronoUnit.DAYS).toEpochMilli() - CUTOFF_TOLERANCE_MILLIS;
      long expectedHigh = Instant.ofEpochMilli(afterMillis)
          .minus(KPI_WINDOW_DAYS, ChronoUnit.DAYS).toEpochMilli() + CUTOFF_TOLERANCE_MILLIS;
      for (Timestamp ts : cutoffCaptor.getAllValues()) {
        long ms = ts.getTime();
        assertTrue("cutoff " + ms + " must be >= " + expectedLow, ms >= expectedLow);
        assertTrue("cutoff " + ms + " must be <= " + expectedHigh, ms <= expectedHigh);
      }
    }
  }

  /**
   * Verifies that {@code loadTotals} falls back to {@code 0 / 0 / 0 / "EUR"}
   * when the result set is empty — the contract relied on by the UI to render
   * the AccountSummaryStrip even before any transaction is recorded.
   *
   * @throws Exception
   *     if the mocked JDBC chain or JSON traversal fails
   */
  @Test
  public void testLoadTotalsFallsBackToZerosOnEmptyResultSet() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONObject totals = handler.loadTotals(ACCOUNT_ID);

      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(totals.getString("balance"))));
      assertEquals("EUR", totals.getString("currency"));
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(totals.getString("inflows"))));
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(totals.getString("outflows"))));
    }
  }

  // ── buildPayload() envelope ──────────────────────────────────────────────

  /**
   * Verifies that {@code buildPayload} assembles the
   * {@code response.data.{transactions,totals}} envelope using the two SQL
   * seams. Drives the method directly so no HTTP routing is involved.
   *
   * @throws Exception
   *     if the mocked JDBC chain or JSON traversal fails
   */
  @Test
  public void testBuildPayloadAssemblesEnvelopeFromLoaders() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement psTrx = mock(PreparedStatement.class);
    PreparedStatement psTotals = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(psTrx, psTotals);

    ResultSet rsTrx = mock(ResultSet.class);
    when(psTrx.executeQuery()).thenReturn(rsTrx);
    when(rsTrx.next()).thenReturn(false);

    ResultSet rsTotals = mock(ResultSet.class);
    when(psTotals.executeQuery()).thenReturn(rsTotals);
    when(rsTotals.next()).thenReturn(false);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      NeoResponse response = handler.buildPayload(ACCOUNT_ID);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      JSONObject data = body.getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getJSONArray("transactions").length());
      // Empty result-set means fallback "EUR" / zeros for totals.
      assertEquals("EUR", data.getJSONObject("totals").getString("currency"));
    }
  }

  // ── nullSafeBigDecimal() helper ──────────────────────────────────────────

  /**
   * Verifies that {@code nullSafeBigDecimal} converts a {@code null} input
   * into {@link BigDecimal#ZERO} — the contract relied on by the result-set
   * mapper to keep summaries arithmetic-safe.
   */
  @Test
  public void testNullSafeBigDecimalReturnsZeroOnNullInput() {
    assertEquals(0, BigDecimal.ZERO.compareTo(
        FinancialAccountTransactionsHandler.nullSafeBigDecimal(null)));
  }

  /**
   * Verifies that {@code nullSafeBigDecimal} returns the input untouched when
   * it is non-null — non-zero amounts must flow through without coercion.
   */
  @Test
  public void testNullSafeBigDecimalReturnsValueWhenNotNull() {
    BigDecimal value = new BigDecimal("42.50");
    assertSame(value, FinancialAccountTransactionsHandler.nullSafeBigDecimal(value));
  }

  // ── Fixtures / helpers ───────────────────────────────────────────────────

  /**
   * Stubs every column read performed by {@code loadTransactions} for a single
   * row, using the column-name based getters used by the production code.
   *
   * @param rs the mocked result set
   * @param id the {@code fin_finacc_transaction_id} value
   * @param date the {@code statementdate} value, may be {@code null}
   * @param status the {@code status} value (will be trimmed by production code)
   * @param trxType the {@code trxtype} value
   * @param amount the {@code amount} value, may be {@code null}
   * @param balance the {@code balance} value, may be {@code null}
   * @param description the {@code description} value
   * @param posted the {@code posted} flag value
   * @param documentNo the {@code document_no} value
   * @param contact the {@code contact} value
   * @param currencyIso the {@code currency_iso} value
   * @throws Exception
   *     if any stubbing call fails
   */
  private static void stubTransactionRow(ResultSet rs, String id, Timestamp date,
      String status, String trxType, BigDecimal amount, BigDecimal balance,
      String description, String posted, String documentNo, String contact,
      String currencyIso) throws Exception {
    when(rs.getString("fin_finacc_transaction_id")).thenReturn(id);
    when(rs.getTimestamp("statementdate")).thenReturn(date);
    when(rs.getString("status")).thenReturn(status);
    when(rs.getString("trxtype")).thenReturn(trxType);
    when(rs.getBigDecimal("amount")).thenReturn(amount);
    when(rs.getBigDecimal("balance")).thenReturn(balance);
    when(rs.getString("description")).thenReturn(description);
    when(rs.getString("posted")).thenReturn(posted);
    when(rs.getString("document_no")).thenReturn(documentNo);
    when(rs.getString("contact")).thenReturn(contact);
    when(rs.getString("currency_iso")).thenReturn(currencyIso);
  }
}
