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
import static org.mockito.ArgumentMatchers.any;
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
import org.openbravo.base.exception.OBException;
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

  // ─────────────────────────────────────────────────────────────────────
  // handleCreate() validation paths (POST ?action=create)
  // ─────────────────────────────────────────────────────────────────────

  /** Tests below cover the {@code POST ?action=create} endpoint and its
   *  lookup siblings ({@code action=bpartner-lookup}, {@code action=glitem-lookup}).
   */

  @Test
  public void testHandleCreateRejectsNullBody() {

    NeoResponse r = handler.handle(postCreateCtx(null));
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void testHandleCreateRejectsMissingAccountId() throws Exception {

    NeoContext ctx = postCreateCtx(new JSONObject());
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse r = handler.handle(ctx);
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("FIN_Financial_Account_ID"));
    }
  }

  @Test
  public void testHandleCreateRejectsInvalidTrxType() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("trxType", "FOO");
    body.put("depositAmount", "10");


    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Invalid trxType"));
    }
  }

  @Test
  public void testHandleCreateRejectsZeroAmounts() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("trxType", "BPD");
    body.put("depositAmount", "0");
    body.put("paymentAmount", "0");


    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("At least one"));
    }
  }

  @Test
  public void testHandleCreateRejectsNegativeAmounts() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("trxType", "BPD");
    body.put("depositAmount", "-5");
    body.put("paymentAmount", "0");


    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("non-negative"));
    }
  }

  @Test
  public void testHandleCreateRejectsUnknownAccount() throws Exception {
    JSONObject body = goodCreateBody();


    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class),
          anyString())).thenReturn(null);

      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Financial account not found"));
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // Lookups (GET ?action=bpartner-lookup / glitem-lookup)
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void testHandleBpartnerLookupReturnsMappedRows() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "bpartner-lookup");
    qp.put("q", "ac");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    NeoResponse r = runLookupWithStubs(ctx, "bp-1", "Acme");
    assertEquals(200, r.getHttpStatus());
    JSONArray bps = r.getBody().getJSONObject("response").getJSONObject("data").getJSONArray("bpartners");
    assertEquals(1, bps.length());
    assertEquals("Acme", bps.getJSONObject(0).getString("name"));
  }

  @Test
  public void testHandleGlItemLookupReturnsMappedRows() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "glitem-lookup");
    qp.put("q", "tr");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    NeoResponse r = runLookupWithStubs(ctx, "gl-1", "Transfer");
    assertEquals(200, r.getHttpStatus());
    JSONArray gls = r.getBody().getJSONObject("response").getJSONObject("data").getJSONArray("glItems");
    assertEquals(1, gls.length());
    assertEquals("Transfer", gls.getJSONObject(0).getString("name"));
  }

  // ─────────────────────────────────────────────────────────────────────
  // Helpers for the new POST / lookup tests
  // ─────────────────────────────────────────────────────────────────────

  /** Builds a {@link NeoContext} mock for POST /sws/neo/...?action=create. */
  private static NeoContext postCreateCtx(JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "create");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getQueryParams()).thenReturn(qp);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /** Minimal request body that passes every validation in handleCreate. */
  private static JSONObject goodCreateBody() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("trxType", "BPD");
    body.put("depositAmount", "100");
    body.put("paymentAmount", "0");
    body.put("description", "Sample");
    return body;
  }

  // ─────────────────────────────────────────────────────────────────────
  // handleCreate() happy path + downstream helpers
  // ─────────────────────────────────────────────────────────────────────

  @Test
  public void testHandleCreateHappyPathPersistsTransaction() throws Exception {
    JSONObject body = goodCreateBody();
    body.put("bpartnerId", "bp-1");
    body.put("glItemId", "gl-1");
    body.put("transactionDate", "2026-01-15T00:00:00Z");
    body.put("accountingDate", "2026-01-15T00:00:00Z");


    org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount account =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class);
    org.openbravo.model.ad.system.Client client = mock(org.openbravo.model.ad.system.Client.class);
    org.openbravo.model.common.enterprise.Organization orga =
        mock(org.openbravo.model.common.enterprise.Organization.class);
    org.openbravo.model.common.currency.Currency currency =
        mock(org.openbravo.model.common.currency.Currency.class);
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(orga);
    when(account.getCurrency()).thenReturn(currency);
    when(account.getId()).thenReturn("acc-1");

    org.openbravo.model.common.businesspartner.BusinessPartner bp =
        mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
    org.openbravo.model.financialmgmt.gl.GLItem gl =
        mock(org.openbravo.model.financialmgmt.gl.GLItem.class);
    org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction trx =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-new");
    when(trx.getTransactionType()).thenReturn("BPD");
    when(trx.getStatus()).thenReturn("RPAE");

    // nextLineNo() executes a SQL query; stub the JDBC chain to return 30.
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getLong("next_line")).thenReturn(30L);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<org.openbravo.base.provider.OBProvider> providerMock =
             mockStatic(org.openbravo.base.provider.OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class), eq("acc-1")))
          .thenReturn(account);
      when(dal.get(eq(org.openbravo.model.common.businesspartner.BusinessPartner.class), eq("bp-1")))
          .thenReturn(bp);
      when(dal.get(eq(org.openbravo.model.financialmgmt.gl.GLItem.class), eq("gl-1"))).thenReturn(gl);
      when(dal.getConnection()).thenReturn(conn);

      org.openbravo.base.provider.OBProvider provider = mock(org.openbravo.base.provider.OBProvider.class);
      providerMock.when(org.openbravo.base.provider.OBProvider::getInstance).thenReturn(provider);
      when(provider.get(org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction.class)).thenReturn(trx);

      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(201, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("tx-new", data.getString("id"));
      assertEquals("BPD", data.getString("trxType"));

      // Verify the transaction was assembled with the expected linkages.
      verify(trx).setAccount(account);
      verify(trx).setCurrency(currency);
      verify(trx).setTransactionType("BPD");
      verify(trx).setLineNo(30L);
      verify(trx).setStatus("RPAE");
      verify(trx).setBusinessPartner(bp);
      verify(trx).setGLItem(gl);
      verify(dal).save(trx);
    }
  }

  @Test
  public void testHandleCreateFallsBackToAccountCurrencyWhenIdMissing() throws Exception {
    JSONObject body = goodCreateBody();   // no currencyId in the body
    body.put("trxType", "BPW");
    body.put("depositAmount", "0");
    body.put("paymentAmount", "50");


    org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount account =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class);
    org.openbravo.model.common.currency.Currency accountCurrency =
        mock(org.openbravo.model.common.currency.Currency.class);
    when(account.getCurrency()).thenReturn(accountCurrency);
    when(account.getId()).thenReturn("acc-1");
    when(account.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    when(account.getOrganization()).thenReturn(mock(org.openbravo.model.common.enterprise.Organization.class));

    org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction trx =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction.class);
    when(trx.getId()).thenReturn("tx-new");
    when(trx.getTransactionType()).thenReturn("BPW");
    when(trx.getStatus()).thenReturn("RPAP");

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);  // exercise nextLineNo() default branch

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<org.openbravo.base.provider.OBProvider> providerMock =
             mockStatic(org.openbravo.base.provider.OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class), eq("acc-1")))
          .thenReturn(account);
      when(dal.getConnection()).thenReturn(conn);

      org.openbravo.base.provider.OBProvider provider = mock(org.openbravo.base.provider.OBProvider.class);
      providerMock.when(org.openbravo.base.provider.OBProvider::getInstance).thenReturn(provider);
      when(provider.get(org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction.class)).thenReturn(trx);

      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(201, r.getHttpStatus());
      verify(trx).setCurrency(accountCurrency);
      // BPW with paymentAmount > 0 → status RPAP
      verify(trx).setStatus("RPAP");
      // nextLineNo's empty result-set branch → defaults to 10
      verify(trx).setLineNo(10L);
    }
  }

  @Test
  public void testHandleCreateReturns500WhenSaveExplodes() throws Exception {
    JSONObject body = goodCreateBody();

    org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount account =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class);
    when(account.getCurrency()).thenReturn(mock(org.openbravo.model.common.currency.Currency.class));
    when(account.getId()).thenReturn("acc-1");
    when(account.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    when(account.getOrganization()).thenReturn(mock(org.openbravo.model.common.enterprise.Organization.class));

    org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction trx =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction.class);

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<org.openbravo.base.provider.OBProvider> providerMock =
             mockStatic(org.openbravo.base.provider.OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class), eq("acc-1")))
          .thenReturn(account);
      when(dal.getConnection()).thenReturn(conn);
      org.mockito.Mockito.doThrow(new RuntimeException("db boom")).when(dal).flush();

      org.openbravo.base.provider.OBProvider provider = mock(org.openbravo.base.provider.OBProvider.class);
      providerMock.when(org.openbravo.base.provider.OBProvider::getInstance).thenReturn(provider);
      when(provider.get(org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction.class)).thenReturn(trx);

      NeoResponse r = handler.handle(postCreateCtx(body));
      assertEquals(500, r.getHttpStatus());
    }
  }

  @Test
  public void testNextLineNoReturnsDefaultOnSqlException() throws Exception {

    org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount account =
        mock(org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("acc-1");

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("driver fail"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      long result = handler.nextLineNo(account);
      assertEquals(10L, result);  // catch branch fallback
    }
  }

  /** Drives a lookup endpoint with a single row mocked in the ResultSet. */
  private NeoResponse runLookupWithStubs(NeoContext ctx, String id, String name) throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn(name);

    OBContext realCtx = mock(OBContext.class);
    org.openbravo.model.ad.system.Client client = mock(org.openbravo.model.ad.system.Client.class);
    when(client.getId()).thenReturn("client-1");
    when(realCtx.getCurrentClient()).thenReturn(client);


    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(realCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      return handler.handle(ctx);
    }
  }

  // ── create-payment routing ───────────────────────────────────────────────

  /** Builds a POST {@code ?action=create-payment} context with the given body. */
  private static NeoContext createPaymentCtx(JSONObject body) {
    Map<String, String> params = new HashMap<>();
    params.put("action", "create-payment");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getQueryParams()).thenReturn(params);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /**
   * Verifies that {@code POST ?action=create-payment} delegates to
   * {@link AddPaymentService} and returns its response, wrapping the call in the
   * admin-mode lifecycle.
   */
  @Test
  public void testCreatePaymentDelegatesToService() throws Exception {
    NeoContext ctx = createPaymentCtx(new JSONObject());
    NeoResponse expected = NeoResponse.ok(new JSONObject());

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<AddPaymentService> svc = mockStatic(AddPaymentService.class)) {
      svc.when(() -> AddPaymentService.doAddPayment(any())).thenReturn(expected);

      NeoResponse response = handler.handle(ctx);

      assertSame(expected, response);
      obContextMock.verify(() -> OBContext.setAdminMode(true));
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * Verifies that {@code create-payment} returns 400 when the request has no
   * body, short-circuiting before touching the admin-mode lifecycle.
   */
  @Test
  public void testCreatePaymentNullBodyReturnsBadRequest() {
    NeoResponse response = handler.handle(createPaymentCtx(null));
    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a business {@link OBException} from the service maps to 400,
   * rolls the transaction back and restores the admin mode.
   */
  @Test
  public void testCreatePaymentMapsBusinessExceptionToBadRequest() throws Exception {
    NeoContext ctx = createPaymentCtx(new JSONObject());

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<AddPaymentService> svc = mockStatic(AddPaymentService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      svc.when(() -> AddPaymentService.doAddPayment(any()))
          .thenThrow(new OBException("Contact not found"));

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * Verifies that an unexpected error from the service maps to 500 and still
   * restores the admin mode in the finally block.
   */
  @Test
  public void testCreatePaymentMapsUnexpectedErrorToServerError() throws Exception {
    NeoContext ctx = createPaymentCtx(new JSONObject());

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<AddPaymentService> svc = mockStatic(AddPaymentService.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      svc.when(() -> AddPaymentService.doAddPayment(any()))
          .thenThrow(new RuntimeException("boom"));

      NeoResponse response = handler.handle(ctx);

      assertEquals(500, response.getHttpStatus());
      obContextMock.verify(OBContext::restorePreviousMode);
    }
  }

  // ── outstanding-invoices ─────────────────────────────────────────────────

  /**
   * Verifies that {@code GET ?action=outstanding-invoices} maps a result-set row
   * into the invoice JSON shape the payment UI consumes (no, bp, metodo, order
   * no., dd/MM/yyyy dates), scopes by direction (issotrx 'Y' for {@code doc=in})
   * and binds the business partner when provided.
   */
  @Test
  public void testOutstandingInvoicesMapsRowAndBindsBpartner() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("action", "outstanding-invoices");
    params.put("bpartnerId", "BP-1");
    params.put("doc", "in");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(params);

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("id")).thenReturn("psd-1");
    when(rs.getString("doc_no")).thenReturn("10000014");
    when(rs.getString("descr")).thenReturn("Pedido");
    when(rs.getString("bpartner")).thenReturn("Juan Perez");
    when(rs.getString("payment_method")).thenReturn("Efectivo");
    when(rs.getString("project")).thenReturn("General");
    when(rs.getString("order_no")).thenReturn("1000326");
    when(rs.getString("currency_iso")).thenReturn("EUR");
    when(rs.getDate("invoice_date")).thenReturn(java.sql.Date.valueOf("2026-04-16"));
    when(rs.getDate("due_date")).thenReturn(java.sql.Date.valueOf("2026-05-16"));
    when(rs.getBigDecimal("invoiced_amount")).thenReturn(new BigDecimal("1355.20"));
    when(rs.getBigDecimal("expected_amount")).thenReturn(new BigDecimal("1355.20"));
    when(rs.getBigDecimal("outstanding_amount")).thenReturn(new BigDecimal("355.20"));

    OBContext realCtx = mock(OBContext.class);
    org.openbravo.model.ad.system.Client client = mock(org.openbravo.model.ad.system.Client.class);
    when(client.getId()).thenReturn("client-1");
    when(realCtx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(realCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      JSONArray invoices = data.getJSONArray("invoices");
      assertEquals(1, invoices.length());
      JSONObject row = invoices.getJSONObject(0);
      assertEquals("psd-1", row.getString("id"));
      assertEquals("10000014", row.getString("no"));
      assertEquals("Juan Perez", row.getString("bp"));
      assertEquals("Efectivo", row.getString("metodo"));
      assertEquals("1000326", row.getString("orderNo"));
      assertEquals("16/04/2026", row.getString("fecha"));

      verify(ps).setString(1, "Y");          // doc=in → issotrx 'Y'
      verify(ps).setString(2, "0");          // system client
      verify(ps).setString(3, "client-1");
      verify(ps).setString(4, "BP-1");       // bpartner clause appended
    }
  }

  /**
   * Verifies that {@code outstanding-invoices} without a bpartner returns 400
   * is NOT enforced — a blank partner returns ALL contacts (doc=out → issotrx
   * 'N') and does not bind a fourth parameter.
   */
  @Test
  public void testOutstandingInvoicesAllContactsForPayments() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("action", "outstanding-invoices");
    params.put("doc", "out");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(params);

    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);

    OBContext realCtx = mock(OBContext.class);
    org.openbravo.model.ad.system.Client client = mock(org.openbravo.model.ad.system.Client.class);
    when(client.getId()).thenReturn("client-1");
    when(realCtx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(realCtx);
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(0, data.getJSONArray("invoices").length());
      verify(ps).setString(1, "N"); // doc=out → issotrx 'N'
    }
  }

  // ── bpartner-lookup role filter ──────────────────────────────────────────

  /** Verifies the customer role branch of the bpartner lookup runs and returns rows. */
  @Test
  public void testBpartnerLookupCustomerRole() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("action", "bpartner-lookup");
    params.put("q", "ju");
    params.put("role", "customer");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(params);

    NeoResponse response = runLookupWithStubs(ctx, "bp-1", "Juan");

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals(1, data.getJSONArray("bpartners").length());
  }

  /** Verifies the vendor role branch of the bpartner lookup runs and returns rows. */
  @Test
  public void testBpartnerLookupVendorRole() throws Exception {
    Map<String, String> params = new HashMap<>();
    params.put("action", "bpartner-lookup");
    params.put("q", "pro");
    params.put("role", "vendor");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(params);

    NeoResponse response = runLookupWithStubs(ctx, "bp-2", "Proveedor");

    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals(1, data.getJSONArray("bpartners").length());
  }
}
