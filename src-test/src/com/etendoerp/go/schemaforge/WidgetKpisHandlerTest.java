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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  // ── ETP-5011: calendar-year query, range selector ignored on purpose ──────

  /**
   * Regression test for ETP-5011: the revenue/expense SQL must aggregate over the
   * full calendar year (Jan 1st - Dec 31st), not a single month anchored to the most
   * recent invoice.
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
   * Regression test for ETP-5011: the Financial Summary widget deliberately ignores
   * the dashboard date-range selector (?range=). It must query the same calendar-year
   * SQL regardless of whether a range query param is present.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandle_ignoresRangeQueryParam() throws Exception {
    mockActivityQuery("1");
    mockRevenueAndPendingQueries();

    handler.handle(getContextWithRange("last30d"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());
    boolean anyRevenueSqlUsesYearTruncation = sqlCaptor.getAllValues().stream()
        .filter(sql -> sql.contains("totallines"))
        .anyMatch(sql -> sql.contains("date_trunc('year'"));

    assertTrue(anyRevenueSqlUsesYearTruncation,
        "Presence of ?range= must not change the KPI SQL — this widget is always calendar-year");
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
