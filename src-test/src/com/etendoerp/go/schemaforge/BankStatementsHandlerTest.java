/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link BankStatementsHandler}.
 *
 * <p>Covers handle() routing, every list/lines/import/preview endpoint, the
 * file-format detector, the upload-input validator and the SQL marshallers.
 * Heavy DB-bound seams (newBankStatement, processStatement, parseC43,
 * parseGenericCsv, loadStatements, loadLines, readLinesForPreview) are
 * stubbed via {@code spy(handler)} + {@code doReturn} so the tests run
 * fully offline.
 */
// Silent runner: clearMocks() (below) wipes the inline mock maker registry after
// each test to keep the shared test-worker heap flat; the strict runner would
// then fail its post-run mock inspection with NotAMockException, so use Silent.
@RunWith(MockitoJUnitRunner.Silent.class)
public class BankStatementsHandlerTest {

  private BankStatementsHandler handler;

  @Before
  public void setUp() {
    handler = spy(new BankStatementsHandler());
  }

  /**
   * Releases the references the Mockito inline mock maker retains for every mock
   * created in a test. Without this they survive until GC and accumulate across
   * the whole module suite (single test JVM), pushing the fork past its heap
   * limit. Clearing them after each test keeps the heap flat.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── handle() routing ───────────────────────────────────────────────────

  @Test
  public void handleRoutesGetLinesActionToHandleGetLines() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "lines");
    qp.put("statementId", "stmt-1");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    doReturn(new JSONArray()).when(handler).loadLines("stmt-1");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse response = handler.handle(ctx);
      assertEquals(200, response.getHttpStatus());
    }
  }

  @Test
  public void handleReturnsMissingStatementIdWhenAbsent() {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "lines");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleReturnsMissingAccountIdOnList() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(new HashMap<>());

    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleListReturns500WhenLoadStatementsThrows() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("FIN_Financial_Account_ID", "acc-1");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    doThrow(new RuntimeException("db boom")).when(handler).loadStatements("acc-1");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse response = handler.handle(ctx);
      assertEquals(500, response.getHttpStatus());
    }
  }

  @Test
  public void handleGetLinesReturns500WhenLoadLinesThrows() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "lines");
    qp.put("statementId", "stmt-1");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    doThrow(new RuntimeException("boom")).when(handler).loadLines("stmt-1");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse response = handler.handle(ctx);
      assertEquals(500, response.getHttpStatus());
    }
  }

  @Test
  public void handleListHappyPath() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("FIN_Financial_Account_ID", "acc-1");
    when(ctx.getHttpMethod()).thenReturn("GET");
    when(ctx.getQueryParams()).thenReturn(qp);

    JSONArray rows = new JSONArray();
    JSONObject row = new JSONObject();
    row.put("id", "stmt-1");
    rows.put(row);
    doReturn(rows).when(handler).loadStatements("acc-1");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      NeoResponse response = handler.handle(ctx);
      assertEquals(200, response.getHttpStatus());
      JSONObject env = response.getBody();
      assertEquals(1, env.getJSONObject("response").getJSONObject("data")
          .getJSONArray("statements").length());
    }
  }

  @Test
  public void handleRoutesPostImportAction() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "import");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getQueryParams()).thenReturn(qp);
    when(ctx.getRequestBody()).thenReturn(null);

    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleRoutesPostPreviewAction() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    Map<String, String> qp = new HashMap<>();
    qp.put("action", "preview");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getQueryParams()).thenReturn(qp);
    when(ctx.getRequestBody()).thenReturn(null);

    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleReturns405ForUnknownMethod() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("DELETE");
    when(ctx.getQueryParams()).thenReturn(new HashMap<>());

    NeoResponse response = handler.handle(ctx);
    assertEquals(405, response.getHttpStatus());
  }

  // ── handleImport / handlePreview validation ───────────────────────────

  @Test
  public void importRejectsMissingAccountId() throws Exception {
    NeoResponse r = invokeImport(handler, body(null, "f.c43", "abc"));
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("FIN_Financial_Account_ID"));
  }

  @Test
  public void importRejectsMissingFileName() throws Exception {
    NeoResponse r = invokeImport(handler, body("acc-1", null, "abc"));
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("fileName"));
  }

  @Test
  public void importRejectsMissingContentBase64() throws Exception {
    NeoResponse r = invokeImport(handler, body("acc-1", "f.c43", null));
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("contentBase64"));
  }

  @Test
  public void importRejectsUnknownAccount() throws Exception {
    JSONObject body = body("acc-1", "f.c43", encode("11 0001"));
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(null);

      NeoResponse r = handler.handle(postCtx(ctx, "import"));
      assertEquals(400, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").contains("Financial account not found"));
    }
  }

  @Test
  public void importRejectsUnknownFormat() throws Exception {
    JSONObject body = body("acc-1", "f.txt", encode("not a recognised format\nsome random text"));
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);

      NeoResponse r = handler.handle(postCtx(ctx, "import"));
      assertEquals(400, r.getHttpStatus());
    }
  }

  @Test
  public void importHappyPathSavesStatementAndReturns201() throws Exception {
    JSONObject body = body("acc-1", "f.c43", encode(c43LineEighty()));
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("stmt-new");

    doReturn(statement).when(handler).newBankStatement(eq(account), anyString());
    doReturn(7).when(handler).parseC43(any(ByteArrayInputStream.class), eq(statement));
    doNothing().when(handler).processStatement(statement);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);

      NeoResponse r = handler.handle(postCtx(ctx, "import"));
      assertEquals(201, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("stmt-new", data.getString("id"));
      assertEquals(7, data.getInt("lineCount"));
    }
  }

  @Test
  public void importRoutesCsvFilesToGenericCsvParser() throws Exception {
    JSONObject body = body("acc-1", "f.csv", encode(csvHeaderAndOneRow()));
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("stmt-new");

    doReturn(statement).when(handler).newBankStatement(eq(account), anyString());
    doReturn(1).when(handler).parseGenericCsv(any(ByteArrayInputStream.class), eq(statement));
    doNothing().when(handler).processStatement(statement);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);

      NeoResponse r = handler.handle(postCtx(ctx, "import"));
      assertEquals(201, r.getHttpStatus());
      verify(handler).parseGenericCsv(any(ByteArrayInputStream.class), eq(statement));
      verify(handler, never()).parseC43(any(ByteArrayInputStream.class), any());
    }
  }

  @Test
  public void importReturns500WhenParserBlowsUp() throws Exception {
    JSONObject body = body("acc-1", "f.c43", encode(c43LineEighty()));
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);

    doReturn(statement).when(handler).newBankStatement(eq(account), anyString());
    doThrow(new RuntimeException("boom")).when(handler).parseC43(any(), any());

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);

      NeoResponse r = handler.handle(postCtx(ctx, "import"));
      assertEquals(500, r.getHttpStatus());
    }
  }

  @Test
  public void previewHappyPathReadsLinesFromDb() throws Exception {
    JSONObject body = body("acc-1", "f.c43", encode(c43LineEighty()));
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("stmt-tmp");

    doReturn(statement).when(handler).newBankStatement(eq(account), anyString());
    doReturn(3).when(handler).parseC43(any(), any());
    JSONArray lines = new JSONArray();
    JSONObject l1 = new JSONObject();
    l1.put("cramount", new BigDecimal("100").toString());
    l1.put("dramount", new BigDecimal("0").toString());
    l1.put("date", "2026-01-01T00:00:00Z");
    lines.put(l1);
    doReturn(lines).when(handler).readLinesForPreview("stmt-tmp");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);

      NeoResponse r = handler.handle(postCtx(ctx, "preview"));
      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals(1, data.getInt("lineCount"));
      assertEquals("C43", data.getString("format"));
    }
  }

  // ── detectFormat() ─────────────────────────────────────────────────────

  @Test
  public void detectFormatReturnsUnknownForNullOrEmpty() {
    assertEquals(BankStatementsHandler.StatementFormat.UNKNOWN,
        BankStatementsHandler.detectFormat(null));
    assertEquals(BankStatementsHandler.StatementFormat.UNKNOWN,
        BankStatementsHandler.detectFormat(new byte[0]));
  }

  @Test
  public void detectFormatRecognisesC43() {
    byte[] c43 = c43LineEighty().getBytes(StandardCharsets.UTF_8);
    assertEquals(BankStatementsHandler.StatementFormat.C43,
        BankStatementsHandler.detectFormat(c43));
  }

  @Test
  public void detectFormatRecognisesCsv() {
    byte[] csv = csvHeaderAndOneRow().getBytes(StandardCharsets.UTF_8);
    assertEquals(BankStatementsHandler.StatementFormat.GENERIC_CSV,
        BankStatementsHandler.detectFormat(csv));
  }

  @Test
  public void detectFormatReturnsUnknownForArbitraryText() {
    byte[] text = "Hello world\nsecond line".getBytes(StandardCharsets.UTF_8);
    assertEquals(BankStatementsHandler.StatementFormat.UNKNOWN,
        BankStatementsHandler.detectFormat(text));
  }

  @Test
  public void detectFormatReturnsUnknownForOnlyOneCsvHeaderToken() {
    // Single token isn't enough — needs at least 2 matches.
    byte[] text = "transaction date,foo,bar".getBytes(StandardCharsets.UTF_8);
    assertEquals(BankStatementsHandler.StatementFormat.UNKNOWN,
        BankStatementsHandler.detectFormat(text));
  }

  // ── deriveStatementStatus() ───────────────────────────────────────────

  @Test
  public void deriveStatementStatusReturnsPendingForEmptyStatement() {
    assertEquals("PENDING", BankStatementsHandler.deriveStatementStatus(0, 0));
  }

  @Test
  public void deriveStatementStatusReturnsPendingWhenNoMatches() {
    assertEquals("PENDING", BankStatementsHandler.deriveStatementStatus(10, 0));
  }

  @Test
  public void deriveStatementStatusReturnsPartialWhenSomeMatched() {
    assertEquals("PARTIAL", BankStatementsHandler.deriveStatementStatus(10, 4));
  }

  @Test
  public void deriveStatementStatusReturnsReconciledWhenAllMatched() {
    assertEquals("RECONCILED", BankStatementsHandler.deriveStatementStatus(10, 10));
  }

  // ── nullSafeBigDecimal ────────────────────────────────────────────────

  @Test
  public void nullSafeBigDecimalReturnsZeroForNull() {
    assertEquals(0, BigDecimal.ZERO.compareTo(BankStatementsHandler.nullSafeBigDecimal(null)));
  }

  @Test
  public void nullSafeBigDecimalReturnsValueWhenNotNull() {
    BigDecimal v = new BigDecimal("12.34");
    assertEquals(v, BankStatementsHandler.nullSafeBigDecimal(v));
  }

  // ── loadStatements / loadLines SQL marshalling ────────────────────────

  @Test
  public void loadStatementsMapsResultSetIntoEnvelopeRows() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_bankstatement_id")).thenReturn("stmt-1");
    when(rs.getString("documentno")).thenReturn("1000001");
    when(rs.getString("name")).thenReturn("January");
    when(rs.getString("filename")).thenReturn("jan.c43");
    when(rs.getTimestamp(anyString())).thenReturn(new Timestamp(0));
    when(rs.getString("processed")).thenReturn("Y");
    when(rs.getString("posted")).thenReturn("N");
    when(rs.getInt("line_count")).thenReturn(7);
    when(rs.getInt("matched_count")).thenReturn(7);
    when(rs.getBigDecimal("total_amount")).thenReturn(new BigDecimal("12345.67"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray rows = handler.loadStatements("acc-1");

      assertEquals(1, rows.length());
      JSONObject row = rows.getJSONObject(0);
      assertEquals("stmt-1", row.getString("id"));
      assertEquals(7, row.getInt("lineCount"));
      assertEquals("RECONCILED", row.getString("status"));
    }
  }

  @Test
  public void loadLinesMapsResultSetIntoEnvelopeRows() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString("fin_bankstatementline_id")).thenReturn("line-1");
    when(rs.getLong("line")).thenReturn(10L);
    when(rs.getTimestamp("datetrx")).thenReturn(new Timestamp(0));
    when(rs.getString("description")).thenReturn("Sample");
    when(rs.getString("referenceno")).thenReturn("REF-1");
    when(rs.getString("bpartnername")).thenReturn("Acme");
    when(rs.getBigDecimal("cramount")).thenReturn(new BigDecimal("100"));
    when(rs.getBigDecimal("dramount")).thenReturn(new BigDecimal("0"));
    when(rs.getString("fin_finacc_transaction_id")).thenReturn("tx-1");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray rows = handler.loadLines("stmt-1");

      assertEquals(1, rows.length());
      JSONObject row = rows.getJSONObject(0);
      assertEquals("line-1", row.getString("id"));
      assertEquals(10L, row.getLong("lineNo"));
      assertTrue(row.getBoolean("matched"));
    }
  }

  @Test
  public void readLinesForPreviewReusesLinesSql() throws Exception {
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, false);
    when(rs.getLong("line")).thenReturn(10L);
    when(rs.getTimestamp("datetrx")).thenReturn(new Timestamp(0));
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("bpartnername")).thenReturn("bp");
    when(rs.getString("referenceno")).thenReturn("ref");
    when(rs.getBigDecimal("cramount")).thenReturn(new BigDecimal("50"));
    when(rs.getBigDecimal("dramount")).thenReturn(new BigDecimal("0"));

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);

      JSONArray rows = handler.readLinesForPreview("stmt-1");
      assertEquals(1, rows.length());
      assertNotNull(rows.getJSONObject(0).getString("date"));
    }
  }

  // ── Default afterHandle hooks ─────────────────────────────────────────

  @Test
  public void afterHandleHooksReturnNullByDefault() {
    NeoContext ctx = mock(NeoContext.class);
    assertNull(handler.afterHandle(ctx));
    assertNull(handler.afterCallout(ctx));
  }

  // ── Fixtures / helpers ────────────────────────────────────────────────

  private static JSONObject body(String accountId, String fileName, String content) throws Exception {
    JSONObject body = new JSONObject();
    if (accountId != null) body.put("FIN_Financial_Account_ID", accountId);
    if (fileName != null) body.put("fileName", fileName);
    if (content != null) body.put("contentBase64", content);
    return body;
  }

  private static String encode(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Crafts an 80-char C43 record that {@code detectFormat} accepts. */
  private static String c43LineEighty() {
    StringBuilder sb = new StringBuilder("11");
    while (sb.length() < 80) sb.append("0");
    return sb.toString();
  }

  private static String csvHeaderAndOneRow() {
    return "Transaction Date,Reference No.,Business Partner Name,Amount OUT,Amount IN,Description\n"
        + "01/02/2026,REF-1,Acme,0,100,Line\n";
  }

  private static NeoContext postCtx(NeoContext ctx, String action) {
    Map<String, String> qp = new HashMap<>();
    qp.put("action", action);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getQueryParams()).thenReturn(qp);
    return ctx;
  }

  /** Helper invoked from validation tests that just need handleImport's early returns. */
  private NeoResponse invokeImport(BankStatementsHandler h, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(body);
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      return h.handle(postCtx(ctx, "import"));
    }
  }

  // ── ?action=create (manual statement) ──────────────────────────────────

  /**
   * Invokes the private static {@code validateCreateBody} via reflection — it
   * only reads the JSON body, so this covers every 400 branch without the
   * {@code mockStatic(OBContext)} that going through {@code handle()} would
   * otherwise force just to get past admin mode.
   */
  private static NeoResponse invokeValidateCreate(JSONObject body) throws Exception {
    java.lang.reflect.Method m =
        BankStatementsHandler.class.getDeclaredMethod("validateCreateBody", JSONObject.class);
    m.setAccessible(true);
    return (NeoResponse) m.invoke(null, body);
  }

  private static JSONObject createLine(String date, String desc, String cp, Object in, Object out)
      throws Exception {
    JSONObject l = new JSONObject();
    l.put("date", date);
    l.put("description", desc);
    l.put("bpartnerName", cp);
    l.put("in", in);
    l.put("out", out);
    return l;
  }

  @Test
  public void handleCreateNullBodyReturns400() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getRequestBody()).thenReturn(null);
    NeoResponse r = handler.handle(postCtx(ctx, "create"));
    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void validateCreateBodyRejectsMissingAccount() throws Exception {
    NeoResponse r = invokeValidateCreate(new JSONObject());
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message")
        .contains("FIN_Financial_Account_ID"));
  }

  @Test
  public void validateCreateBodyRejectsMissingName() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    NeoResponse r = invokeValidateCreate(body);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("name"));
  }

  @Test
  public void validateCreateBodyRejectsEmptyLines() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("name", "Extracto manual");
    body.put("lines", new JSONArray());
    NeoResponse r = invokeValidateCreate(body);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message").contains("line"));
  }

  @Test
  public void validateCreateBodyAcceptsValidBody() throws Exception {
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("name", "Extracto manual");
    JSONArray lines = new JSONArray();
    lines.put(createLine("2026-06-02T00:00:00Z", "Transferencia", "Acme", 3500.0, 0));
    body.put("lines", lines);
    assertNull(invokeValidateCreate(body));
  }

  @Test
  public void handleCreateHappyPathPersistsLinesAndProcesses() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("name", "Extracto manual");
    body.put("transactionDate", "2026-06-04T00:00:00Z");
    body.put("importDate", "2026-06-04T00:00:00Z");
    JSONArray lines = new JSONArray();
    JSONObject rich = new JSONObject();
    rich.put("date", "2026-06-02T00:00:00Z");
    rich.put("reference", "REF-1");
    rich.put("bpartnerName", "Acme");
    rich.put("bpartnerId", "bp-1");
    rich.put("glItemId", "gl-1");
    rich.put("description", "Transferencia");
    rich.put("in", 3500.0);
    rich.put("out", 0);
    lines.put(rich);
    lines.put(createLine("", "", "", 0, 0)); // blank trailing row → skipped
    body.put("lines", lines);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("stmt-new");
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    org.openbravo.model.common.businesspartner.BusinessPartner bp =
        mock(org.openbravo.model.common.businesspartner.BusinessPartner.class);
    org.openbravo.model.financialmgmt.gl.GLItem gl =
        mock(org.openbravo.model.financialmgmt.gl.GLItem.class);

    // Stub the DB-bound seams so only createLines runs for real.
    doReturn(statement).when(handler)
        .newManualBankStatement(any(), any());
    doNothing().when(handler).processStatement(any());

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);
      when(dal.get(eq(org.openbravo.model.common.businesspartner.BusinessPartner.class), eq("bp-1")))
          .thenReturn(bp);
      when(dal.get(eq(org.openbravo.model.financialmgmt.gl.GLItem.class), eq("gl-1"))).thenReturn(gl);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_BankStatementLine.class)).thenReturn(line);

      NeoResponse response = handler.handle(postCtx(ctx, "create"));

      assertEquals(201, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("stmt-new", data.getString("id"));
      assertEquals(1, data.getInt("lineCount"));
      verify(handler).processStatement(statement);
      verify(dal).save(line); // the single non-blank line was persisted
      // Classic line fields are mapped: reference, counterparty name + FK, GL item, description.
      verify(line).setReferenceNo("REF-1");
      verify(line).setBpartnername("Acme");
      verify(line).setBusinessPartner(bp);
      verify(line).setGLItem(gl);
      verify(line).setDescription("Transferencia");
    }
  }

  @Test
  public void handleCreateSaveAsDraftSkipsProcessing() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "acc-1");
    body.put("name", "Borrador");
    body.put("process", false); // "save as draft"
    JSONArray lines = new JSONArray();
    lines.put(createLine("2026-06-02T00:00:00Z", "X", "Y", 10, 0));
    body.put("lines", lines);
    when(ctx.getRequestBody()).thenReturn(body);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_BankStatement statement = mock(FIN_BankStatement.class);
    when(statement.getId()).thenReturn("stmt-draft");
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);

    doReturn(statement).when(handler).newManualBankStatement(any(), any());

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("acc-1"))).thenReturn(account);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_BankStatementLine.class)).thenReturn(line);

      NeoResponse response = handler.handle(postCtx(ctx, "create"));

      assertEquals(201, response.getHttpStatus());
      verify(dal).save(line);
      // Draft → the statement is persisted but NOT processed.
      verify(handler, never()).processStatement(any());
    }
  }

  @Test
  public void handleCreateReturns400WhenAccountNotFound() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    body.put("FIN_Financial_Account_ID", "ghost");
    body.put("name", "Extracto manual");
    JSONArray lines = new JSONArray();
    lines.put(createLine("2026-06-02T00:00:00Z", "X", "Y", 10, 0));
    body.put("lines", lines);
    when(ctx.getRequestBody()).thenReturn(body);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_FinancialAccount.class), eq("ghost"))).thenReturn(null);

      NeoResponse response = handler.handle(postCtx(ctx, "create"));
      assertEquals(400, response.getHttpStatus());
    }
  }
}
