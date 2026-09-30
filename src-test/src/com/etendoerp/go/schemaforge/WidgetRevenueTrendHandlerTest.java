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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Unit tests for {@link WidgetRevenueTrendHandler} (ETP-5493: period-aware buckets).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetRevenueTrendHandlerTest {

  private WidgetRevenueTrendHandler handler;

  @Mock private OBContext obContext;
  @Mock private Client client;
  @Mock private OBDal obDal;
  @Mock private Session session;
  @Mock private NativeQuery<Object[]> nativeQuery;
  @Mock private NativeQuery<Object[]> previousQuery;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  // ETP-5088 — every widget now resolves the caller's role and gates on AD_Window_Access before
  // querying. These suites cover the widget's own behaviour, so they grant access and let the
  // real WidgetAccessPolicy run on top of a mocked NeoAccessHelper; the gate itself is covered by
  // WidgetAccessPolicyTest and by the per-role denial case at the end of this file.
  @Mock private Role role;
  private MockedStatic<NeoAccessHelper> accessHelperMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetRevenueTrendHandler();
    accessHelperMock = mockStatic(NeoAccessHelper.class);
    accessHelperMock.when(NeoAccessHelper::resolveCurrentRole).thenReturn(role);
    accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), anyString(), anyString()))
        .thenReturn(true);
    obContextMock = mockStatic(OBContext.class);
    obDalMock = mockStatic(OBDal.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn("test-client-id");
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    // The previous-period revenue query is told apart from the series query by its alias.
    when(session.createNativeQuery(contains("previous_revenue"))).thenReturn(previousQuery);
    when(previousQuery.setParameter(anyString(), any())).thenReturn(previousQuery);
    when(previousQuery.list()).thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    accessHelperMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  private NeoContext buildContext(String method) {
    return buildContext(method, null);
  }

  private NeoContext buildContext(String method, String range) {
    Map<String, String> params = new HashMap<>();
    if (range != null) {
      params.put("range", range);
    }
    return NeoContext.builder()
        .specName("dashboard").entityName("revenue-trend")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();
  }

  private static Object[] row(String date, String label, String revenue, String expense) {
    return new Object[] { date, label, new BigDecimal(revenue), new BigDecimal(expense) };
  }

  private static Object[] previousRow(Object revenue) {
    return new Object[] { revenue, 3L };
  }

  private JSONObject trendOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
  }

  /**
   * Collapses every whitespace run to one space so assertions do not depend on how the handler
   * splits its SQL across concatenated literals (line breaks and indentation).
   */
  private static String normalizeSql(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  /** SQL of the series query (the one with the buckets CTE), captured from the session. */
  private String captureTrendSql() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    return sqlCaptor.getAllValues().stream()
        .filter(q -> q.contains("WITH buckets"))
        .map(WidgetRevenueTrendHandlerTest::normalizeSql)
        .findFirst()
        .orElseThrow(() -> new AssertionError("series query was not issued"));
  }

  private String capturePreviousSql() {
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    return sqlCaptor.getAllValues().stream()
        .filter(q -> q.contains("previous_revenue"))
        .map(WidgetRevenueTrendHandlerTest::normalizeSql)
        .findFirst()
        .orElseThrow(() -> new AssertionError("previous query was not issued"));
  }

  @Nested
  @DisplayName("Method guard")
  class MethodGuard {
    @Test
    void rejectsPost() {
      assertEquals(405, handler.handle(buildContext("POST")).getHttpStatus());
    }

    @Test
    void rejectsDelete() {
      assertEquals(405, handler.handle(buildContext("DELETE")).getHttpStatus());
    }
  }

  @Nested
  @DisplayName("Role gate")
  class RoleGate {
    @Test
    void deniedRoleGetsEmptyResponseAndNoQuery() throws Exception {
      accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess(any(Role.class), anyString(), anyString()))
          .thenReturn(false);

      NeoResponse response = handler.handle(buildContext("GET", "ytd"));

      assertEquals(200, response.getHttpStatus());
      JSONObject data = response.getBody().getJSONObject("response");
      assertEquals(0, data.getJSONArray("data").length());
      assertEquals(0, data.getInt("count"));
      org.mockito.Mockito.verify(session, org.mockito.Mockito.never()).createNativeQuery(anyString());
    }
  }

  @Nested
  @DisplayName("granularityFor")
  class GranularityFor {
    @Test
    void dailyRanges() {
      assertEquals("day", WidgetRevenueTrendHandler.granularityFor("last30d"));
      assertEquals("day", WidgetRevenueTrendHandler.granularityFor("mtd"));
    }

    @Test
    void weeklyRange() {
      assertEquals("week", WidgetRevenueTrendHandler.granularityFor("last90d"));
    }

    @Test
    void monthlyRanges() {
      assertEquals("month", WidgetRevenueTrendHandler.granularityFor("ytd"));
      assertEquals("month", WidgetRevenueTrendHandler.granularityFor("lastYear"));
    }

    @Test
    void nullBlankAndUnknownFallBackToMonth() {
      assertEquals("month", WidgetRevenueTrendHandler.granularityFor(null));
      assertEquals("month", WidgetRevenueTrendHandler.granularityFor(""));
      assertEquals("month", WidgetRevenueTrendHandler.granularityFor("   "));
      assertEquals("month", WidgetRevenueTrendHandler.granularityFor("decade"));
    }
  }

  @Nested
  @DisplayName("Captured SQL")
  class CapturedSql {
    @Test
    void usesMatchingDateTruncUnitPerRange() {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      String[][] cases = { { "last30d", "day" }, { "mtd", "day" }, { "last90d", "week" },
          { "ytd", "month" }, { "lastYear", "month" } };
      for (String[] c : cases) {
        org.mockito.Mockito.clearInvocations(session);
        handler.handle(buildContext("GET", c[0]));
        String sql = captureTrendSql();
        assertTrue(sql.contains("date_trunc('" + c[1] + "'"), c[0] + " must bucket by " + c[1]);
        assertTrue(sql.contains("CAST('1 " + c[1] + "' AS interval)"), c[0] + " step must be 1 " + c[1]);
        assertTrue(sql.contains("totallines"));
        assertFalse(sql.contains("grandtotal"));
      }
    }

    @Test
    void hasNoLeftoverPlaceholders() {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      handler.handle(buildContext("GET", "last90d"));

      for (String sql : new String[] { captureTrendSql(), capturePreviousSql() }) {
        assertFalse(sql.contains("@UNIT@"));
        assertFalse(sql.contains("%1$s"));
        assertFalse(sql.contains("%2$s"));
        assertFalse(sql.contains("%3$s"));
      }
    }

    @Test
    void carriesRangeExpressionsFromHelper() {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      for (String range : new String[] { "last30d", "last90d", "mtd", "ytd", "lastYear" }) {
        org.mockito.Mockito.clearInvocations(session);
        handler.handle(buildContext("GET", range));

        assertTrue(captureTrendSql().contains(WidgetQueryHelper.rangeToSqlDateFrom(range)), range);
        String previousSql = capturePreviousSql();
        assertTrue(previousSql.contains(WidgetQueryHelper.rangeToSqlPrevFrom(range)), range);
        assertTrue(previousSql.contains(WidgetQueryHelper.rangeToSqlPrevTo(range)), range);
      }
    }

    @Test
    void seriesStartsAtFirstMidnightOnOrAfterFrom() {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      handler.handle(buildContext("GET", "last30d"));

      String from = WidgetQueryHelper.rangeToSqlDateFrom("last30d");
      String expected = "CASE WHEN date_trunc('day', " + from + ") < " + from
          + " THEN date_trunc('day', " + from + ") + INTERVAL '1 day' ELSE date_trunc('day', " + from
          + ") END";
      assertTrue(captureTrendSql().contains(expected));
    }

    @Test
    void missingRangeUsesLastYearBounds() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      handler.handle(buildContext("GET"));

      assertTrue(captureTrendSql().contains(WidgetQueryHelper.rangeToSqlDateFrom("lastYear")));
      assertTrue(capturePreviousSql().contains(WidgetQueryHelper.rangeToSqlPrevFrom("lastYear")));
      assertTrue(captureTrendSql().contains("date_trunc('month'"));
    }

    @Test
    void blankRangeUsesLastYearBounds() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      NeoResponse response = handler.handle(buildContext("GET", "   "));

      assertEquals("month", trendOf(response).getString("granularity"));
      assertTrue(captureTrendSql().contains(WidgetQueryHelper.rangeToSqlDateFrom("lastYear")));
    }

    @Test
    void injectedRangeNeverReachesSql() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      String evil = "day'; DROP TABLE x;--";

      NeoResponse response = handler.handle(buildContext("GET", evil));

      assertEquals(200, response.getHttpStatus());
      assertFalse(captureTrendSql().contains("DROP TABLE"));
      assertFalse(capturePreviousSql().contains("DROP TABLE"));
      assertTrue(List.of("day", "week", "month").contains(trendOf(response).getString("granularity")));
    }

    /**
     * Regression test for ETP-5011 (Inconsistency 2): the trend query must sum
     * {@code totallines} (tax-exclusive "base imponible"), not {@code grandtotal}.
     */
    @Test
    void usesTaxExclusiveTotalNotGrandtotal() {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());

      handler.handle(buildContext("GET"));

      String sql = captureTrendSql();
      assertTrue(sql.contains("totallines"), "Trend query must sum totallines");
      assertFalse(sql.contains("grandtotal"), "Trend query must not sum grandtotal (VAT included)");
      assertFalse(capturePreviousSql().contains("grandtotal"));
    }
  }

  /**
   * ETP-5493 (W2): the trend's {@code revenueTotal} and the kpis revenue must describe the same
   * windows. The two handlers own separate SQL, so this pins their bounds together: if one query's
   * window changes and the other's does not, the "Resumen" and "Evolucion" cards would silently
   * disagree for the same period.
   */
  @Nested
  @DisplayName("Window parity with the kpis revenue query")
  class KpisWindowParity {
    private static final String[] RANGES = { "last30d", "last90d", "mtd", "ytd", "lastYear" };

    /** The kpis revenue SQL resolved for {@code range} exactly like executeRangedQuery does. */
    private String kpisRevenueSql(String range) throws Exception {
      java.lang.reflect.Field field = WidgetKpisHandler.class.getDeclaredField("REVENUE_SQL");
      field.setAccessible(true);
      String template = (String) field.get(null);
      return normalizeSql(String.format(template,
          WidgetQueryHelper.rangeToSqlDateFrom(range),
          WidgetQueryHelper.rangeToSqlPrevFrom(range),
          WidgetQueryHelper.rangeToSqlPrevTo(range)));
    }

    @Test
    void currentWindowUsesTheSameLowerAndUpperBound() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      for (String range : RANGES) {
        org.mockito.Mockito.clearInvocations(session);
        handler.handle(buildContext("GET", range));

        String window = "i.dateinvoiced >= " + WidgetQueryHelper.rangeToSqlDateFrom(range)
            + " AND i.dateinvoiced <= NOW()";
        assertTrue(kpisRevenueSql(range).contains(window), "kpis current window for " + range);
        assertTrue(captureTrendSql().contains(window), "trend current window for " + range);
      }
    }

    @Test
    void previousWindowUsesTheSameBoundsInBothQueries() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      for (String range : RANGES) {
        org.mockito.Mockito.clearInvocations(session);
        handler.handle(buildContext("GET", range));

        String window = "i.dateinvoiced >= " + WidgetQueryHelper.rangeToSqlPrevFrom(range)
            + " AND i.dateinvoiced < " + WidgetQueryHelper.rangeToSqlPrevTo(range);
        assertTrue(kpisRevenueSql(range).contains(window), "kpis previous window for " + range);
        assertTrue(capturePreviousSql().contains(window), "trend previous window for " + range);
      }
    }

    @Test
    void bothQueriesSumTheSameAmountColumn() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());
      handler.handle(buildContext("GET", "ytd"));

      assertTrue(kpisRevenueSql("ytd").contains("i.totallines"));
      assertTrue(captureTrendSql().contains("i.totallines"));
      assertTrue(capturePreviousSql().contains("i.totallines"));
    }
  }

  @Nested
  @DisplayName("GET responses")
  class GetResponses {
    @Test
    void emptyResultReturns200WithEmptyArrays() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.emptyList());

      NeoResponse response = handler.handle(buildContext("GET"));
      assertEquals(200, response.getHttpStatus());

      JSONObject trend = trendOf(response);
      assertEquals(0, trend.getJSONArray("labels").length());
      assertEquals(0, trend.getJSONArray("values").length());
      assertEquals(0, trend.getJSONArray("expenseValues").length());
      assertEquals(0, trend.getJSONArray("dates").length());
      assertEquals(0.0, trend.getDouble("revenueTotal"), 0.001);
    }

    @Test
    void twoMonthsMappedCorrectly() throws Exception {
      when(nativeQuery.list()).thenReturn(Arrays.asList(
          row("2026-01-01", "Jan", "5000", "2000"), row("2026-02-01", "Feb", "7000", "3000")));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));
      JSONArray labels = trend.getJSONArray("labels");
      JSONArray values = trend.getJSONArray("values");
      JSONArray expenseValues = trend.getJSONArray("expenseValues");
      JSONArray dates = trend.getJSONArray("dates");

      assertEquals(2, labels.length());
      assertEquals("Jan", labels.getString(0));
      assertEquals("Feb", labels.getString(1));
      assertEquals("2026-01-01", dates.getString(0));
      assertEquals("2026-02-01", dates.getString(1));
      assertEquals(5000L, values.getLong(0));
      assertEquals(7000L, values.getLong(1));
      assertEquals(2000L, expenseValues.getLong(0));
      assertEquals(3000L, expenseValues.getLong(1));
      assertEquals("month", trend.getString("granularity"));
    }

    @Test
    void labelTrimsWhitespace() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-03-01", "  Mar  ", "100", "50")));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));
      assertEquals("Mar", trend.getJSONArray("labels").getString(0));
    }

    @Test
    void dayBucketsUseIsoDatesAsLabels() throws Exception {
      when(nativeQuery.list()).thenReturn(Arrays.asList(
          row("2026-09-01", "Sep", "10", "1"), row("2026-09-02", "Sep", "20", "2")));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "mtd")));

      assertEquals("day", trend.getString("granularity"));
      assertEquals("2026-09-01", trend.getJSONArray("labels").getString(0));
      assertEquals("2026-09-02", trend.getJSONArray("labels").getString(1));
    }

    @Test
    void weekBucketsUseIsoDatesAsLabels() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-09-21", "Sep", "10", "1")));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "last90d")));

      assertEquals("week", trend.getString("granularity"));
      assertEquals("2026-09-21", trend.getJSONArray("labels").getString(0));
      assertEquals("2026-09-21", trend.getJSONArray("dates").getString(0));
    }

    /**
     * Regression test for ETP-5011: revenue/expense totals must preserve cents instead
     * of being truncated to whole euros.
     */
    @Test
    void preservesCentsInsteadOfTruncating() throws Exception {
      when(nativeQuery.list()).thenReturn(
          Collections.singletonList(row("2026-08-01", "Aug", "319114.00", "71.39")));

      JSONObject trend = trendOf(handler.handle(buildContext("GET")));

      assertEquals(319114.00, trend.getJSONArray("values").getDouble(0), 0.001);
      assertEquals(71.39, trend.getJSONArray("expenseValues").getDouble(0), 0.001);
    }
  }

  @Nested
  @DisplayName("Totals and comparison")
  class Totals {
    @Test
    void payloadCarriesTotalsGrowthAndHasPrevious() throws Exception {
      when(nativeQuery.list()).thenReturn(Arrays.asList(
          row("2026-01-01", "Jan", "60", "10"), row("2026-02-01", "Feb", "60", "10")));
      when(previousQuery.list()).thenReturn(Collections.singletonList(previousRow(new BigDecimal("100"))));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));

      assertEquals(120.0, trend.getDouble("revenueTotal"), 0.001);
      assertEquals(100.0, trend.getDouble("previousRevenueTotal"), 0.001);
      assertEquals(WidgetKpisHandler.calculateTrend(new BigDecimal("120"), new BigDecimal("100")),
          trend.getDouble("growthPct"), 0.001);
      assertEquals(20.0, trend.getDouble("growthPct"), 0.001);
      assertTrue(trend.getBoolean("hasPrevious"));
    }

    @Test
    void negativeGrowthIsReported() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-01-01", "Jan", "50", "0")));
      when(previousQuery.list()).thenReturn(Collections.singletonList(previousRow(new BigDecimal("100"))));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));

      assertEquals(-50.0, trend.getDouble("growthPct"), 0.001);
      assertTrue(trend.getBoolean("hasPrevious"));
    }

    @Test
    void zeroPreviousRevenueMeansNoPrevious() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-01-01", "Jan", "50", "0")));
      when(previousQuery.list()).thenReturn(Collections.singletonList(previousRow(BigDecimal.ZERO)));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));

      assertFalse(trend.getBoolean("hasPrevious"));
      assertEquals(0.0, trend.getDouble("growthPct"), 0.001);
      assertEquals(0.0, trend.getDouble("previousRevenueTotal"), 0.001);
    }

    @Test
    void emptyPreviousQueryCountsAsZero() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-01-01", "Jan", "50", "0")));
      when(previousQuery.list()).thenReturn(Collections.emptyList());

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));

      assertFalse(trend.getBoolean("hasPrevious"));
      assertEquals(0.0, trend.getDouble("previousRevenueTotal"), 0.001);
    }

    @Test
    void nullPreviousValueCountsAsZero() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-01-01", "Jan", "50", "0")));
      when(previousQuery.list()).thenReturn(Collections.singletonList(previousRow(null)));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));

      assertFalse(trend.getBoolean("hasPrevious"));
      assertEquals(0.0, trend.getDouble("previousRevenueTotal"), 0.001);
    }

    @Test
    void nonBigDecimalPreviousValueIsParsed() throws Exception {
      when(nativeQuery.list()).thenReturn(Collections.singletonList(row("2026-01-01", "Jan", "150", "0")));
      when(previousQuery.list()).thenReturn(Collections.singletonList(previousRow(100L)));

      JSONObject trend = trendOf(handler.handle(buildContext("GET", "ytd")));

      assertEquals(100.0, trend.getDouble("previousRevenueTotal"), 0.001);
      assertEquals(50.0, trend.getDouble("growthPct"), 0.001);
    }
  }
}
