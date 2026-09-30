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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link WidgetKpisHandler}.
 * Covers: method guard (non-GET), empty-activity early return, KPI structure,
 * trend calculation, and toBigDecimal edge cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetKpisHandlerTest {

  private WidgetKpisHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Session session;
  @Mock
  private NativeQuery<Object> activityQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery revenueQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery pendingQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  // ETP-5088 — every widget now resolves the caller's role and gates on AD_Window_Access before
  // querying. These suites cover the widget's own behaviour, so they grant access and let the
  // real WidgetAccessPolicy run on top of a mocked NeoAccessHelper; the gate itself is covered by
  // WidgetAccessPolicyTest and by the per-role denial case at the end of this file.
  @Mock private Role role;
  private MockedStatic<NeoAccessHelper> accessHelperMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetKpisHandler();
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    accessHelperMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(role);
    accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), anyString(), anyString()))
        .thenReturn(true);
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
    when(obDal.getSession()).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    accessHelperMock.close();
    obDalMock.close();
    obContextMock.close();
  }

  private NeoContext getContext() {
    return NeoContext.builder()
        .specName("dashboard")
        .entityName("kpis")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private NeoContext getContextWithRange(String range) {
    return NeoContext.builder()
        .specName("dashboard")
        .entityName("kpis")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(Map.of("range", range))
        .build();
  }

  // ── Method guard ─────────────────────────────────────────────────────────

  @Test
  void testHandleRejectsPost() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("kpis")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  @Test
  void testHandleRejectsPut() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("kpis")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  @Test
  void testHandleRejectsDelete() {
    NeoContext ctx = NeoContext.builder()
        .specName("dashboard").entityName("kpis")
        .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  // ── No-activity early return ──────────────────────────────────────────────

  @Test
  void testHandle_noInvoiceActivity_returns200() throws Exception {
    mockActivityQuery(null);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
  }

  @Test
  void testHandle_noInvoiceActivity_returnsEmptyDataArray() throws Exception {
    mockActivityQuery(null);

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    assertEquals(0, data.length());
  }

  @Test
  void testHandle_noInvoiceActivity_returnsZeroCount() throws Exception {
    mockActivityQuery(null);

    NeoResponse response = handler.handle(getContext());
    int count = response.getBody().getJSONObject("response").getInt("count");

    assertEquals(0, count);
  }

  // ── With activity: KPI structure ──────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_withActivity_returnsFourKpis() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    NeoResponse response = handler.handle(getContext());
    JSONObject responseData = response.getBody().getJSONObject("response");

    assertEquals(200, response.getHttpStatus());
    assertEquals(4, responseData.getInt("count"));
    assertEquals(4, responseData.getJSONArray("data").length());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_withActivity_kpiKeysAreCorrect() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    assertEquals("revenueThisMonth",  data.getJSONObject(0).getString("key"));
    assertEquals("expensesThisMonth", data.getJSONObject(1).getString("key"));
    assertEquals("netProfit",         data.getJSONObject(2).getString("key"));
    assertEquals("pendingInvoices",   data.getJSONObject(3).getString("key"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_withActivity_kpiFormatIsCorrect() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    assertEquals("currency", data.getJSONObject(0).getString("format"));
    assertEquals("currency", data.getJSONObject(1).getString("format"));
    assertEquals("currency", data.getJSONObject(2).getString("format"));
    assertEquals("number",   data.getJSONObject(3).getString("format"));
  }

  // ── ETP-5011 / ETP-5493: period query (defaults to year-to-date), follows ?range= ──

  /**
   * Regression test for ETP-5011 (updated by ETP-5493): with the default range the
   * revenue/expense SQL must aggregate year-to-date ({@code date_trunc('year', NOW())} to
   * {@code NOW()}), not a single month anchored to the most recent invoice.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandle_queriesFullCalendarYear_notJustCurrentMonth() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    boolean anyRevenueSqlUsesYearTruncation = sqlCaptor.getAllValues().stream()
        .filter(sql -> sql.contains("totallines"))
        .anyMatch(sql -> sql.contains("date_trunc('year'"));

    assertTrue(anyRevenueSqlUsesYearTruncation,
        "Revenue/expense SQL must aggregate by calendar year (date_trunc('year', ...))");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_doesNotAnchorToMostRecentInvoiceMonth() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    boolean anyRevenueSqlAnchorsToMonth = sqlCaptor.getAllValues().stream()
        .filter(sql -> sql.contains("totallines"))
        .anyMatch(sql -> sql.contains("date_trunc('month'") || sql.contains("MAX(dateinvoiced)"));

    assertFalse(anyRevenueSqlAnchorsToMonth,
        "Revenue/expense SQL must not be anchored to the most recent invoice month anymore");
  }

  /**
   * ETP-5493 (supersedes the ETP-5011 "range is ignored" test): the Financial Summary follows
   * the dashboard period selector, so ?range=last30d must produce the rolling 30-day SQL and
   * not the calendar-year one.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandle_followsRangeQueryParam() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange("last30d"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    boolean anyRevenueSqlUsesRollingWindow = sqlCaptor.getAllValues().stream()
        .filter(sql -> sql.contains("totallines"))
        .anyMatch(sql -> sql.contains("INTERVAL '30 days'") && !sql.contains("date_trunc('year'"));

    assertTrue(anyRevenueSqlUsesRollingWindow,
        "?range=last30d must switch the KPI SQL to the rolling 30-day window");
  }

  /**
   * Regression test for ETP-5011 (Inconsistency 2): revenue/expenses must use
   * {@code totallines} (tax-exclusive "base imponible"), not {@code grandtotal}
   * (which includes VAT/IVA). VAT is not the company's own income or expense.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandle_usesTaxExclusiveTotalNotGrandtotal() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    boolean anyQueryStillSumsGrandtotal = sqlCaptor.getAllValues().stream()
        .anyMatch(sql -> sql.contains("SUM(CASE") && sql.contains("grandtotal"));

    assertFalse(anyQueryStillSumsGrandtotal,
        "Revenue/expense SQL must sum totallines (net), not grandtotal (gross, VAT included)");
  }

  /**
   * ETP-5493: under mtd the SQL legitimately uses date_trunc('month', ...) (start of the current
   * month), so the ETP-5011 "no date_trunc('month'" guard only holds for the default (ytd) range.
   * What must never come back for any range is the anchoring to the most recent invoice.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandle_mtd_usesCalendarMonthButDoesNotAnchorToMostRecentInvoice() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange("mtd"));

    List<String> revenueSqls = revenueSqls(captureSqls());
    assertFalse(revenueSqls.isEmpty());
    for (String sql : revenueSqls) {
      assertTrue(sql.contains("date_trunc('month'"), "mtd must start at the current calendar month");
      assertFalse(sql.contains("MAX(dateinvoiced)"), "must not anchor to the most recent invoice");
    }
  }

  // ── ETP-5493: range -> SQL expressions ───────────────────────────────────

  private static final String[] RANGES = { "ytd", "mtd", "last30d", "last90d", "lastYear" };

  /** All SQL strings sent to createNativeQuery during the last handle() call. */
  private List<String> captureSqls() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    List<String> sqls = new ArrayList<>();
    for (String sql : sqlCaptor.getAllValues()) {
      if (sql != null) {
        sqls.add(sql);
      }
    }
    return sqls;
  }

  private static List<String> revenueSqls(List<String> sqls) {
    List<String> result = new ArrayList<>();
    for (String sql : sqls) {
      if (sql.contains("totallines")) {
        result.add(sql);
      }
    }
    return result;
  }

  private static void assertUsesRangeExpressions(String sql, String range) {
    assertTrue(sql.contains(WidgetQueryHelper.rangeToSqlDateFrom(range)),
        "SQL must contain the current-period 'from' expression for " + range);
    assertTrue(sql.contains(WidgetQueryHelper.rangeToSqlPrevFrom(range)),
        "SQL must contain the previous-period 'from' expression for " + range);
    assertTrue(sql.contains(WidgetQueryHelper.rangeToSqlPrevTo(range)),
        "SQL must contain the previous-period 'to' expression for " + range);
    assertFalse(sql.contains("%1$s") || sql.contains("%2$s") || sql.contains("%3$s"),
        "No format placeholder may be left in the SQL");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_missingRange_usesYtdExpressions() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContext());

    List<String> revenueSqls = revenueSqls(captureSqls());
    assertEquals(2, revenueSqls.size());
    for (String sql : revenueSqls) {
      assertUsesRangeExpressions(sql, "ytd");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "", " ", "   " })
  @SuppressWarnings("unchecked")
  void testHandle_blankRange_usesYtdExpressions(String blank) throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange(blank));

    List<String> revenueSqls = revenueSqls(captureSqls());
    assertEquals(2, revenueSqls.size());
    for (String sql : revenueSqls) {
      assertUsesRangeExpressions(sql, "ytd");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_rangeWithSurroundingWhitespace_isTrimmed() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange(" mtd "));

    for (String sql : revenueSqls(captureSqls())) {
      assertUsesRangeExpressions(sql, "mtd");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "ytd", "mtd", "last30d", "last90d", "lastYear" })
  @SuppressWarnings("unchecked")
  void testHandle_knownRange_usesItsOwnExpressions(String range) throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange(range));

    List<String> revenueSqls = revenueSqls(captureSqls());
    assertEquals(2, revenueSqls.size(), "one revenue and one expenses query");
    for (String sql : revenueSqls) {
      assertUsesRangeExpressions(sql, range);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_eachKnownRange_producesADifferentSql() throws Exception {
    Set<String> distinctSqls = new HashSet<>();
    for (String range : RANGES) {
      org.mockito.Mockito.clearInvocations(session);
      mockActivityQuery("1");
      mockRevenueAndPendingQueries();

      handler.handle(getContextWithRange(range));

      distinctSqls.add(revenueSqls(captureSqls()).get(0));
    }
    assertEquals(RANGES.length, distinctSqls.size(), "every range must resolve to its own SQL");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_unknownRange_fallsBackToRollingTwelveMonths() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange("not-a-range"));

    List<String> revenueSqls = revenueSqls(captureSqls());
    assertEquals(2, revenueSqls.size());
    for (String sql : revenueSqls) {
      assertUsesRangeExpressions(sql, "lastYear");
      assertUsesRangeExpressions(sql, "not-a-range");
    }
    assertEquals(WidgetQueryHelper.rangeToSqlDateFrom("lastYear"),
        WidgetQueryHelper.rangeToSqlDateFrom("not-a-range"));
  }

  // ── ETP-5493: 'Y' / 'N' literal, one query each ──────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void testHandle_runsOneRevenueQueryWithYAndOneExpensesQueryWithN() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange("last30d"));

    List<String> revenueSqls = revenueSqls(captureSqls());
    assertEquals(2, revenueSqls.size(), "exactly one query per side");
    long withY = revenueSqls.stream().filter(sql -> sql.contains("i.issotrx = 'Y'")).count();
    long withN = revenueSqls.stream().filter(sql -> sql.contains("i.issotrx = 'N'")).count();
    assertEquals(1, withY);
    assertEquals(1, withN);
    for (String sql : revenueSqls) {
      assertFalse(sql.contains(":isSoTrx"), "isSoTrx is a literal, not a bind parameter");
      assertFalse(sql.contains("@ISSOTRX@"), "the placeholder must be replaced");
    }
  }

  // ── ETP-5493: pending invoices ignore the period ─────────────────────────

  @ParameterizedTest
  @ValueSource(strings = { "ytd", "mtd", "last30d", "last90d", "lastYear" })
  @SuppressWarnings("unchecked")
  void testHandle_pendingInvoicesSql_hasNoDateFilter(String range) throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange(range));

    List<String> pendingSqls = new ArrayList<>();
    for (String sql : captureSqls()) {
      if (sql.contains("outstandingamt")) {
        pendingSqls.add(sql);
      }
    }
    assertEquals(1, pendingSqls.size());
    String pendingSql = pendingSqls.get(0);
    assertFalse(pendingSql.contains("dateinvoiced"));
    assertFalse(pendingSql.contains("NOW()"));
    assertFalse(pendingSql.contains("INTERVAL"));
  }

  // ── ETP-5493: values, hasPrevious, labels ────────────────────────────────

  private static Object[] row(String current, String previous) {
    return new Object[] { new BigDecimal(current), new BigDecimal(previous) };
  }

  /**
   * Stubs the activity, revenue ('Y'), expenses ('N') and pending queries with distinct results,
   * so each KPI can be traced back to the query that produced it.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void mockPeriodTotals(Object[] revenueRow, Object[] expensesRow, long pendingCount) {
    mockActivityQuery("1");
    when(session.createNativeQuery(contains("SELECT 1 FROM"))).thenReturn(activityQuery);

    NativeQuery revenueSideQuery = mock(NativeQuery.class);
    when(revenueSideQuery.setParameter(anyString(), any())).thenReturn(revenueSideQuery);
    when(revenueSideQuery.list()).thenReturn(Collections.singletonList(revenueRow));
    when(session.createNativeQuery(argThat((String sql) ->
        sql != null && sql.contains("totallines") && sql.contains("i.issotrx = 'Y'"))))
        .thenReturn(revenueSideQuery);

    NativeQuery expensesSideQuery = mock(NativeQuery.class);
    when(expensesSideQuery.setParameter(anyString(), any())).thenReturn(expensesSideQuery);
    when(expensesSideQuery.list()).thenReturn(Collections.singletonList(expensesRow));
    when(session.createNativeQuery(argThat((String sql) ->
        sql != null && sql.contains("totallines") && sql.contains("i.issotrx = 'N'"))))
        .thenReturn(expensesSideQuery);

    when(session.createNativeQuery(contains("outstandingamt"))).thenReturn(pendingQuery);
    when(pendingQuery.setParameter(anyString(), any())).thenReturn(pendingQuery);
    when(pendingQuery.uniqueResult()).thenReturn(pendingCount);
  }

  private JSONArray handleAndGetData(String range) throws Exception {
    NeoResponse response = handler.handle(getContextWithRange(range));
    assertEquals(200, response.getHttpStatus());
    return response.getBody().getJSONObject("response").getJSONArray("data");
  }

  @Test
  void testHandle_kpiLabelsAreRevenueAndExpenses() throws Exception {
    mockPeriodTotals(row("500", "200"), row("120", "50"), 0L);

    JSONArray data = handleAndGetData("mtd");

    assertEquals("Revenue", data.getJSONObject(0).getString("label"));
    assertEquals("Expenses", data.getJSONObject(1).getString("label"));
  }

  @Test
  void testHandle_netProfitIsRevenueMinusExpensesPerWindow() throws Exception {
    // revenue 500 vs 200, expenses 120 vs 50 -> net 380 vs 150
    mockPeriodTotals(row("500", "200"), row("120", "50"), 0L);

    JSONArray data = handleAndGetData("last90d");

    assertEquals(500.0, data.getJSONObject(0).getDouble("value"), 0.001);
    assertEquals(120.0, data.getJSONObject(1).getDouble("value"), 0.001);
    assertEquals(380.0, data.getJSONObject(2).getDouble("value"), 0.001);
    assertEquals(150.0, data.getJSONObject(0).getDouble("trend"), 0.001);   // (500-200)/200
    assertEquals(140.0, data.getJSONObject(1).getDouble("trend"), 0.001);   // (120-50)/50
    assertEquals(153.3, data.getJSONObject(2).getDouble("trend"), 0.001);   // (380-150)/150
  }

  @Test
  void testHandle_hasPreviousIsTrueWhenEveryPreviousValueIsNonZero() throws Exception {
    mockPeriodTotals(row("500", "200"), row("120", "50"), 3L);

    JSONArray data = handleAndGetData("ytd");

    assertTrue(data.getJSONObject(0).getBoolean("hasPrevious"));
    assertTrue(data.getJSONObject(1).getBoolean("hasPrevious"));
    assertTrue(data.getJSONObject(2).getBoolean("hasPrevious"));
  }

  @Test
  void testHandle_hasPreviousIsFalseWhenEveryPreviousValueIsZero() throws Exception {
    mockPeriodTotals(row("500", "0"), row("120", "0"), 3L);

    JSONArray data = handleAndGetData("ytd");

    for (int i = 0; i < 3; i++) {
      assertFalse(data.getJSONObject(i).getBoolean("hasPrevious"), "kpi " + i);
      assertEquals(0.0, data.getJSONObject(i).getDouble("trend"), 0.001);
    }
  }

  @Test
  void testHandle_hasPreviousIsPerKpi_revenueOnlyPrevious() throws Exception {
    mockPeriodTotals(row("500", "200"), row("120", "0"), 0L);

    JSONArray data = handleAndGetData("mtd");

    assertTrue(data.getJSONObject(0).getBoolean("hasPrevious"));
    assertFalse(data.getJSONObject(1).getBoolean("hasPrevious"));
    assertTrue(data.getJSONObject(2).getBoolean("hasPrevious"), "net previous = 200 - 0");
  }

  @Test
  void testHandle_netProfitHasPreviousWhenItsPreviousValueIsNegative() throws Exception {
    // previous: revenue 0, expenses 50 -> net previous -50 (non-zero, so a comparison exists)
    mockPeriodTotals(row("100", "0"), row("40", "50"), 0L);

    JSONArray data = handleAndGetData("last30d");

    assertFalse(data.getJSONObject(0).getBoolean("hasPrevious"));
    assertTrue(data.getJSONObject(1).getBoolean("hasPrevious"));
    assertTrue(data.getJSONObject(2).getBoolean("hasPrevious"));
    // net 60 vs -50 -> (60 + 50) * 100 / 50
    assertEquals(220.0, data.getJSONObject(2).getDouble("trend"), 0.001);
  }

  @Test
  void testHandle_netProfitHasNoPreviousWhenRevenueAndExpensesCancelOut() throws Exception {
    mockPeriodTotals(row("500", "100"), row("120", "100"), 0L);

    JSONArray data = handleAndGetData("ytd");

    assertTrue(data.getJSONObject(0).getBoolean("hasPrevious"));
    assertTrue(data.getJSONObject(1).getBoolean("hasPrevious"));
    assertFalse(data.getJSONObject(2).getBoolean("hasPrevious"), "net previous = 100 - 100 = 0");
  }

  @Test
  void testHandle_pendingInvoicesHasPreviousIsAlwaysFalse() throws Exception {
    mockPeriodTotals(row("500", "200"), row("120", "50"), 7L);

    JSONArray data = handleAndGetData("lastYear");

    JSONObject pending = data.getJSONObject(3);
    assertEquals("pendingInvoices", pending.getString("key"));
    assertEquals(7, pending.getInt("value"));
    assertFalse(pending.getBoolean("hasPrevious"));
    assertEquals(0.0, pending.getDouble("trend"), 0.001);
  }

  @Test
  void testHandle_everyKpiCarriesAHasPreviousFlag() throws Exception {
    mockPeriodTotals(row("500", "200"), row("120", "50"), 0L);

    JSONArray data = handleAndGetData("ytd");

    for (int i = 0; i < data.length(); i++) {
      assertTrue(data.getJSONObject(i).has("hasPrevious"), "kpi " + i + " must expose hasPrevious");
    }
  }

  // ── ETP-5493: no activity still short-circuits when a range is sent ──────

  @Test
  void testHandle_noInvoiceActivity_withRange_returnsEmptyResponseWithoutKpiQueries() throws Exception {
    mockActivityQuery(null);

    NeoResponse response = handler.handle(getContextWithRange("mtd"));
    JSONObject responseData = response.getBody().getJSONObject("response");

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, responseData.getJSONArray("data").length());
    assertEquals(0, responseData.getInt("count"));
    assertTrue(revenueSqls(captureSqls()).isEmpty(), "no revenue/expenses query without activity");
    assertNotEquals(0, captureSqls().size(), "the activity probe itself must still run");
  }

  // ── calculateTrend (private static, via reflection) ──────────────────────

  @Test
  void testCalculateTrend_positiveGrowth() throws Exception {
    double result = invokeTrend(new BigDecimal("120"), new BigDecimal("100"));
    assertEquals(20.0, result, 0.001);
  }

  @Test
  void testCalculateTrend_negativeGrowth() throws Exception {
    double result = invokeTrend(new BigDecimal("80"), new BigDecimal("100"));
    assertEquals(-20.0, result, 0.001);
  }

  @Test
  void testCalculateTrend_noChange_returnsZero() throws Exception {
    double result = invokeTrend(new BigDecimal("100"), new BigDecimal("100"));
    assertEquals(0.0, result, 0.001);
  }

  @Test
  void testCalculateTrend_zeroPrevious_returnsZero() throws Exception {
    double result = invokeTrend(new BigDecimal("100"), BigDecimal.ZERO);
    assertEquals(0.0, result, 0.001);
  }

  @Test
  void testCalculateTrend_bothZero_returnsZero() throws Exception {
    double result = invokeTrend(BigDecimal.ZERO, BigDecimal.ZERO);
    assertEquals(0.0, result, 0.001);
  }

  // ── toBigDecimal (private static, via reflection) ─────────────────────────

  @Test
  void testToBigDecimal_nullValue_returnsZero() throws Exception {
    BigDecimal result = invokeToBigDecimal(null);
    assertEquals(0, BigDecimal.ZERO.compareTo(result));
  }

  @Test
  void testToBigDecimal_bigDecimalInput_returnsSame() throws Exception {
    BigDecimal input = new BigDecimal("123.45");
    BigDecimal result = invokeToBigDecimal(input);
    assertEquals(0, input.compareTo(result));
  }

  @Test
  void testToBigDecimal_integerInput_converts() throws Exception {
    BigDecimal result = invokeToBigDecimal(42);
    assertEquals(0, new BigDecimal("42").compareTo(result));
  }

  @Test
  void testToBigDecimal_stringInput_converts() throws Exception {
    BigDecimal result = invokeToBigDecimal("99.9");
    assertEquals(0, new BigDecimal("99.9").compareTo(result));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void mockActivityQuery(Object uniqueResult) {
    when(session.createNativeQuery(anyString())).thenReturn(activityQuery);
    when(activityQuery.setParameter(anyString(), any())).thenReturn(activityQuery);
    when(activityQuery.setMaxResults(1)).thenReturn(activityQuery);
    when(activityQuery.uniqueResult()).thenReturn(uniqueResult);
  }

  @SuppressWarnings("unchecked")
  private void mockRevenueAndPendingQueries() {
    Object[] zeroRow = { BigDecimal.ZERO, BigDecimal.ZERO };

    when(session.createNativeQuery(contains("SELECT 1 FROM"))).thenReturn(activityQuery);
    when(activityQuery.setParameter(anyString(), any())).thenReturn(activityQuery);
    when(activityQuery.setMaxResults(1)).thenReturn(activityQuery);
    when(activityQuery.uniqueResult()).thenReturn("1");

    when(session.createNativeQuery(contains("totallines"))).thenReturn(revenueQuery);
    when(revenueQuery.setParameter(anyString(), any())).thenReturn(revenueQuery);
    when(revenueQuery.list()).thenReturn(Collections.singletonList(zeroRow));

    when(session.createNativeQuery(contains("outstandingamt"))).thenReturn(pendingQuery);
    when(pendingQuery.setParameter(anyString(), any())).thenReturn(pendingQuery);
    when(pendingQuery.uniqueResult()).thenReturn(0L);
  }

  private double invokeTrend(BigDecimal current, BigDecimal previous) throws Exception {
    Method m = WidgetKpisHandler.class.getDeclaredMethod(
        "calculateTrend", BigDecimal.class, BigDecimal.class);
    m.setAccessible(true);
    return (double) m.invoke(null, current, previous);
  }

  private BigDecimal invokeToBigDecimal(Object value) throws Exception {
    Method m = WidgetKpisHandler.class.getDeclaredMethod("toBigDecimal", Object.class);
    m.setAccessible(true);
    return (BigDecimal) m.invoke(null, value);
  }
}
