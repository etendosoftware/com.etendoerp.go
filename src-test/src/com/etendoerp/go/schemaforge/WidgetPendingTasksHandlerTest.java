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
import java.util.Collections;
import java.util.List;

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

/**
 * Unit tests for {@link WidgetPendingTasksHandler}.
 *
 * <p>Covers: method guard (405), SQL correctness for the pending-receptions query
 * (queries {@code m_inout} with {@code docstatus='DR'}, not {@code c_order}),
 * navigation shape (window="goods-receipt", params.DocStatus="DR"), zero-count
 * suppression, taskKey singular/plural logic, link value, overdue invoices,
 * collections/payments due today, low-stock alerts, and the exception path.
 *
 * <p>Key regression covered: pending-receptions must query {@code m_inout} in Draft
 * status, not {@code c_order}. The old pending-orders SQL must not filter by
 * {@code cancelledorder_id IS NULL}, which incorrectly excluded replacement orders
 * created by Etendo's reactivation flow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WidgetPendingTasksHandlerTest {

  private WidgetPendingTasksHandler handler;

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private Session session;

  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery overdueQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery scheduleQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery orderQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery mInoutQuery;
  @Mock
  @SuppressWarnings("rawtypes")
  private NativeQuery stockQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    handler = new WidgetPendingTasksHandler();
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
    obDalMock.close();
    obContextMock.close();
  }

  private NeoContext getContext() {
    return NeoContext.builder().specName("dashboard").entityName("pending-tasks").httpMethod("GET").endpointType(
        NeoEndpointType.CRUD).build();
  }

  @SuppressWarnings("unchecked")
  private void mockAllQueriesEmpty() {
    when(session.createNativeQuery(contains("SUM(outstandingamt)"))).thenReturn(overdueQuery);
    when(overdueQuery.setParameter(anyString(), any())).thenReturn(overdueQuery);
    when(overdueQuery.uniqueResult()).thenReturn(new Object[]{ 0L, BigDecimal.ZERO });

    when(session.createNativeQuery(contains("fin_payment_schedule"))).thenReturn(scheduleQuery);
    when(scheduleQuery.setParameter(anyString(), any())).thenReturn(scheduleQuery);
    when(scheduleQuery.uniqueResult()).thenReturn(0L);

    when(session.createNativeQuery(contains("m_inout"))).thenReturn(mInoutQuery);
    when(mInoutQuery.setParameter(anyString(), any())).thenReturn(mInoutQuery);
    when(mInoutQuery.uniqueResult()).thenReturn(0L);

    when(session.createNativeQuery(contains("c_order"))).thenReturn(orderQuery);
    when(orderQuery.setParameter(anyString(), any())).thenReturn(orderQuery);
    when(orderQuery.uniqueResult()).thenReturn(0L);

    when(session.createNativeQuery(contains("m_storage_detail"))).thenReturn(stockQuery);
    when(stockQuery.setParameter(anyString(), any())).thenReturn(stockQuery);
    when(stockQuery.list()).thenReturn(Collections.emptyList());
  }

  // ── Method guard ─────────────────────────────────────────────────────────

  /**
   * Verifies that the handler rejects POST requests with HTTP 405.
   */
  @Test
  void testNonGetMethodReturns405() {
    NeoContext ctx = NeoContext.builder().specName("dashboard").entityName("pending-tasks").httpMethod(
        "POST").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  /**
   * Verifies that the handler rejects PUT requests with HTTP 405.
   */
  @Test
  void testHandleRejectsPut() {
    NeoContext ctx = NeoContext.builder().specName("dashboard").entityName("pending-tasks").httpMethod(
        "PUT").endpointType(NeoEndpointType.CRUD).build();
    assertEquals(405, handler.handle(ctx).getHttpStatus());
  }

  // ── All-empty: structure ──────────────────────────────────────────────────

  /**
   * Verifies that the handler returns HTTP 200 when all queries return zero results.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleAllQueriesReturnEmptyReturns200() throws Exception {
    mockAllQueriesEmpty();
    assertEquals(200, handler.handle(getContext()).getHttpStatus());
  }

  /**
   * Verifies that the response count is zero when all queries return zero results.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleAllQueriesReturnEmptyReturnsZeroCount() throws Exception {
    mockAllQueriesEmpty();
    NeoResponse response = handler.handle(getContext());
    assertEquals(0, response.getBody().getJSONObject("response").getInt("count"));
  }

  /**
   * Verifies that the data array is empty when all queries return zero results.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleAllQueriesReturnEmptyReturnsEmptyDataArray() throws Exception {
    mockAllQueriesEmpty();
    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(0, data.length());
  }

  // ── addPendingReceptions: SQL targets m_inout, not c_order ───────────────

  /**
   * Source-reading test: verifies that the {@code addPendingReceptions} SQL block in
   * the handler source queries {@code m_inout} with {@code docstatus = 'DR'} and does
   * NOT reference {@code c_order}.
   *
   * <p>This guards against the previous incorrect implementation that queried
   * purchase orders instead of goods receipts in draft status.
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingReceptionsQueriesMInoutNotCOrder() throws Exception {
    mockAllQueriesEmpty();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());

    List<String> allSqls = sqlCaptor.getAllValues();

    // There must be at least one query against m_inout.
    boolean foundMInout = allSqls.stream().anyMatch(sql -> sql.contains("m_inout"));
    assertTrue(foundMInout, "addPendingReceptions must execute a query against m_inout");

    // The m_inout query must filter by docstatus = 'DR'.
    boolean foundDrFilter = allSqls.stream().filter(sql -> sql.contains("m_inout")).anyMatch(
        sql -> sql.contains("docstatus = 'DR'") || sql.contains("docstatus='DR'"));
    assertTrue(foundDrFilter, "The m_inout query must include a docstatus = 'DR' filter");

    // The m_inout query must NOT reference c_order within the same SQL string.
    boolean mInoutQueryAlsoHasCOrder = allSqls.stream().filter(sql -> sql.contains("m_inout")).anyMatch(
        sql -> sql.contains("c_order"));
    assertFalse(mInoutQueryAlsoHasCOrder,
        "The m_inout query must not reference c_order — " + "pending receptions query must be separate from the purchase-orders query");
  }

  // ── addPendingReceptions: navigation goes to goods-receipt ───────────────

  /**
   * Verifies that when the m_inout count is 2 the response contains a task whose
   * navigation points to window="goods-receipt" with params.DocStatus="DR".
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingReceptionsNavigatesToGoodsReceipt() throws Exception {
    mockAllQueriesEmpty();
    when(mInoutQuery.uniqueResult()).thenReturn(2L);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    JSONObject receptionTask = findTaskByKeyPrefix(data, "pendingReceptions");
    JSONObject navigation = receptionTask.getJSONObject("navigation");

    assertEquals("goods-receipt", navigation.getString("window"), "navigation.window must be 'goods-receipt'");
    assertEquals("DR", navigation.getJSONObject("params").getString("DocStatus"),
        "navigation.params.DocStatus must be 'DR'");
  }

  // ── addPendingReceptions: zero count produces no task ────────────────────

  /**
   * Verifies that when the m_inout count is 0 no pending-receptions task is
   * emitted in the response data array.
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingReceptionsZeroCountProducesEmptyData() throws Exception {
    mockAllQueriesEmpty();
    // mInoutQuery already returns 0L by default.

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(0, data.length(), "No tasks should be emitted when all counts are 0");
  }

  // ── buildTaskWithParams: link + navigation.type + taskKey singular ───────

  /**
   * Verifies that for count=1 the task emitted by {@code buildTaskWithParams} has:
   * navigation.type="list", link="/goods-receipt?DocStatus=DR", and
   * taskKey="pendingReceptions" (singular).
   */
  @Test
  @SuppressWarnings("unchecked")
  void buildTaskWithParamsUsesNavigationParams() throws Exception {
    mockAllQueriesEmpty();
    when(mInoutQuery.uniqueResult()).thenReturn(1L);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    JSONObject task = findTaskByKey(data, "pendingReceptions");

    assertEquals("list", task.getJSONObject("navigation").getString("type"), "navigation.type must be 'list'");
    assertEquals("/goods-receipt?DocStatus=DR", task.getString("link"), "link must be '/goods-receipt?DocStatus=DR'");
    assertEquals("pendingReceptions", task.getString("taskKey"),
        "taskKey must be 'pendingReceptions' (singular) when count=1");
  }

  // ── buildTaskWithParams: taskKey plural ──────────────────────────────────

  /**
   * Verifies that for count=3 the taskKey uses the plural form
   * "pendingReceptions_plural".
   */
  @Test
  @SuppressWarnings("unchecked")
  void buildTaskWithParamsUsesNavigationParamsPlural() throws Exception {
    mockAllQueriesEmpty();
    when(mInoutQuery.uniqueResult()).thenReturn(3L);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    JSONObject task = findTaskByKey(data, "pendingReceptions_plural");

    assertEquals("pendingReceptions_plural", task.getString("taskKey"),
        "taskKey must be 'pendingReceptions_plural' (plural) when count=3");
  }

  // ── SQL regression: cancelledorder_id must not appear in c_order query ───

  /**
   * Regression: the pending-orders SQL must not include {@code cancelledorder_id IS NULL},
   * which would incorrectly exclude replacement orders created during the Etendo
   * order reactivation flow.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPendingOrdersSQLDoesNotFilterByCancelledOrderId() throws Exception {
    mockAllQueriesEmpty();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());

    for (String sql : sqlCaptor.getAllValues()) {
      if (sql.contains("c_order")) {
        assertFalse(sql.contains("cancelledorder_id"),
            "Pending orders SQL must not filter by cancelledorder_id — " + "replacement orders (cancelledorder_id IS NOT NULL, iscancelled='N') must be counted");
      }
    }
  }

  /**
   * Verifies that the pending-orders SQL filters by {@code iscancelled} to exclude
   * reversed or counter orders from the count.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPendingOrdersSQLFiltersByIscancelled() throws Exception {
    mockAllQueriesEmpty();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());

    for (String sql : sqlCaptor.getAllValues()) {
      if (sql.contains("c_order")) {
        assertTrue(sql.contains("iscancelled"),
            "Pending orders SQL must filter by iscancelled to exclude reversed orders");
      }
    }
  }

  // ── Overdue invoices ──────────────────────────────────────────────────────

  /**
   * Verifies that an overdueInvoices task with the correct count and amount
   * appears in the response when the invoice query returns a non-zero value.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testOverdueInvoicesAppearsInResponseWhenNonZero() throws Exception {
    mockAllQueriesEmpty();
    when(overdueQuery.uniqueResult()).thenReturn(new Object[]{ 3L, new BigDecimal("1500.00") });

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if (task.optString("taskKey", "").startsWith("overdueInvoices")) {
        assertEquals(3, task.getInt("count"));
        assertTrue(task.has("amount"), "Task must have amount field");
        found = true;
      }
    }
    assertTrue(found, "Expected an overdueInvoices task in the response");
  }

  /**
   * Verifies that the singular taskKey {@code "overdueInvoices"} is used
   * when exactly one overdue invoice exists.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testOverdueInvoicesSingularKeyWhenCountIsOne() throws Exception {
    mockAllQueriesEmpty();
    when(overdueQuery.uniqueResult()).thenReturn(new Object[]{ 1L, new BigDecimal("500.00") });

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if ("overdueInvoices".equals(task.optString("taskKey", ""))) {
        assertEquals(1, task.getInt("count"));
        found = true;
      }
    }
    assertTrue(found, "Expected overdueInvoices (singular) task in the response");
  }

  // ── Collections / payments due today ─────────────────────────────────────

  /**
   * Verifies that a collectionsDueToday task appears in the response
   * when the payment schedule query returns a non-zero count for sales invoices.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testCollectionsDueTodayAppearsInResponseWhenNonZero() throws Exception {
    mockAllQueriesEmpty();
    when(scheduleQuery.uniqueResult()).thenReturn(5L);

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if (task.optString("taskKey", "").startsWith("collectionsDueToday")) {
        found = true;
        assertTrue(task.optString("link", "").contains("collectionsDueToday"),
            "Link must use collectionsDueToday filter, not overdue");
      }
    }
    assertTrue(found, "Expected a collectionsDueToday task in the response");
  }

  /**
   * Verifies that a paymentsDueToday task appears in the response
   * when the payment schedule query returns a non-zero count for purchase invoices.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPaymentsDueTodayAppearsInResponseWhenNonZero() throws Exception {
    mockAllQueriesEmpty();
    when(scheduleQuery.uniqueResult()).thenReturn(2L);

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if (task.optString("taskKey", "").startsWith("paymentsDueToday")) {
        found = true;
        assertTrue(task.optString("link", "").contains("paymentsDueToday"),
            "Link must use paymentsDueToday filter, not overdue");
      }
    }
    assertTrue(found, "Expected a paymentsDueToday task in the response");
  }

  // ── Low stock alerts ──────────────────────────────────────────────────────

  /**
   * Verifies that a singular lowStockAlerts task is created with the {@code detail}
   * field set to the product name when exactly one product is below its minimum stock.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testLowStockAlertsSingularWithDetailWhenCountIsOne() throws Exception {
    mockAllQueriesEmpty();
    List<Object[]> singleRow = Collections.singletonList(
        new Object[]{ "Product A", BigDecimal.valueOf(5), BigDecimal.valueOf(10) });
    when(stockQuery.list()).thenReturn(singleRow);

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if ("lowStockAlerts".equals(task.optString("taskKey", ""))) {
        assertEquals(1, task.getInt("count"));
        assertEquals("Product A", task.optString("detail", ""));
        found = true;
      }
    }
    assertTrue(found, "Expected a lowStockAlerts (singular) task in the response");
  }

  /**
   * Verifies that the plural taskKey {@code "lowStockAlerts_plural"} is used and no
   * {@code detail} field is set when more than one product is below its minimum stock.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testLowStockAlertsPluralWithoutDetailWhenMultiple() throws Exception {
    mockAllQueriesEmpty();
    List<Object[]> multipleRows = java.util.Arrays.asList(
        new Object[]{ "Product A", BigDecimal.valueOf(5), BigDecimal.valueOf(10) },
        new Object[]{ "Product B", BigDecimal.valueOf(2), BigDecimal.valueOf(8) });
    when(stockQuery.list()).thenReturn(multipleRows);

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if ("lowStockAlerts_plural".equals(task.optString("taskKey", ""))) {
        assertEquals(2, task.getInt("count"));
        assertFalse(task.has("detail"), "Plural task must not have a detail field");
        found = true;
      }
    }
    assertTrue(found, "Expected a lowStockAlerts_plural task in the response");
  }

  // ── Pending sales deliveries ──────────────────────────────────────────────

  /**
   * Verifies that a pendingSalesDeliveries task appears in the response
   * when the m_inout query for sales shipments returns a non-zero count.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testPendingSalesDeliveriesAppearsInResponseWhenNonZero() throws Exception {
    mockAllQueriesEmpty();
    when(mInoutQuery.uniqueResult()).thenReturn(7L);

    NeoResponse response = handler.handle(getContext());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if (task.optString("taskKey", "").startsWith("pendingSalesDeliveries")) {
        found = true;
      }
    }
    assertTrue(found, "Expected a pendingSalesDeliveries task in the response");
  }

  // ── addPendingSalesDeliveries: SQL targets m_inout with issotrx='Y' ──────

  /**
   * Source-reading test: verifies that the {@code addPendingSalesDeliveries} SQL block
   * queries {@code m_inout} with {@code issotrx = 'Y'} (sales transactions) and does
   * NOT use {@code c_order}.
   *
   * <p>This guards against a regression where sales deliveries might be queried from
   * the orders table (c_order) instead of the shipments table (m_inout in Draft status).
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingSalesDeliveriesQueriesMInoutIssotrxY() throws Exception {
    mockAllQueriesEmpty();

    handler.handle(getContext());

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(session, atLeastOnce()).createNativeQuery(sqlCaptor.capture());

    List<String> allSqls = sqlCaptor.getAllValues();

    // There must be at least one query against m_inout with issotrx = 'Y'.
    boolean foundSalesInout = allSqls.stream()
        .filter(sql -> sql.contains("m_inout"))
        .anyMatch(sql -> sql.contains("issotrx = 'Y'") || sql.contains("issotrx='Y'"));
    assertTrue(foundSalesInout,
        "addPendingSalesDeliveries must execute a query against m_inout with issotrx = 'Y'");

    // The m_inout query for sales must also filter by docstatus = 'DR'.
    boolean foundDrFilter = allSqls.stream()
        .filter(sql -> sql.contains("m_inout"))
        .filter(sql -> sql.contains("issotrx = 'Y'") || sql.contains("issotrx='Y'"))
        .anyMatch(sql -> sql.contains("docstatus = 'DR'") || sql.contains("docstatus='DR'"));
    assertTrue(foundDrFilter,
        "The m_inout (issotrx='Y') query must include a docstatus = 'DR' filter");
  }

  // ── addPendingSalesDeliveries: navigation goes to goods-shipment ──────────

  /**
   * Verifies that when the m_inout (issotrx='Y') count is 2 the response contains a
   * task whose navigation points to window="goods-shipment" with params.DocStatus="DR".
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingSalesDeliveriesNavigatesToGoodsShipment() throws Exception {
    mockAllQueriesEmpty();
    // mInoutQuery covers both purchase and sales m_inout queries; returning 2 from
    // uniqueResult triggers both addPendingReceptions and addPendingSalesDeliveries.
    // We isolate the deliveries task by its taskKey prefix.
    when(mInoutQuery.uniqueResult()).thenReturn(2L);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    JSONObject deliveriesTask = findTaskByKeyPrefix(data, "pendingSalesDeliveries");
    JSONObject navigation = deliveriesTask.getJSONObject("navigation");

    assertEquals("goods-shipment", navigation.getString("window"),
        "navigation.window must be 'goods-shipment'");
    assertEquals("DR", navigation.getJSONObject("params").getString("DocStatus"),
        "navigation.params.DocStatus must be 'DR'");
  }

  // ── addPendingSalesDeliveries: zero count produces no task ────────────────

  /**
   * Verifies that when the m_inout (issotrx='Y') count is 0 no
   * pendingSalesDeliveries task is emitted in the response data array.
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingSalesDeliveriesZeroCountProducesEmptyData() throws Exception {
    mockAllQueriesEmpty();
    // mInoutQuery returns 0L by default — no task should be emitted.

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");

    boolean found = false;
    for (int i = 0; i < data.length(); i++) {
      if (data.getJSONObject(i).optString("taskKey", "").startsWith("pendingSalesDeliveries")) {
        found = true;
        break;
      }
    }
    assertFalse(found, "No pendingSalesDeliveries task should be emitted when count is 0");
  }

  // ── addPendingSalesDeliveries: singular taskKey ───────────────────────────

  /**
   * Verifies that for count=1 the task emitted by {@code addPendingSalesDeliveries}
   * has taskKey="pendingSalesDeliveries" (singular) and link="/goods-shipment?DocStatus=DR".
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingSalesDeliveriesSingularTaskKey() throws Exception {
    mockAllQueriesEmpty();
    when(mInoutQuery.uniqueResult()).thenReturn(1L);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    JSONObject task = findTaskByKey(data, "pendingSalesDeliveries");

    assertEquals("pendingSalesDeliveries", task.getString("taskKey"),
        "taskKey must be 'pendingSalesDeliveries' (singular) when count=1");
    assertEquals("/goods-shipment?DocStatus=DR", task.getString("link"),
        "link must be '/goods-shipment?DocStatus=DR'");
    assertEquals("list", task.getJSONObject("navigation").getString("type"),
        "navigation.type must be 'list'");
  }

  // ── addPendingSalesDeliveries: plural taskKey ─────────────────────────────

  /**
   * Verifies that for count=3 the task emitted by {@code addPendingSalesDeliveries}
   * has taskKey="pendingSalesDeliveries_plural".
   */
  @Test
  @SuppressWarnings("unchecked")
  void addPendingSalesDeliveriesPluralTaskKey() throws Exception {
    mockAllQueriesEmpty();
    when(mInoutQuery.uniqueResult()).thenReturn(3L);

    NeoResponse response = handler.handle(getContext());

    assertEquals(200, response.getHttpStatus());
    JSONArray data = response.getBody().getJSONObject("response").getJSONArray("data");
    JSONObject task = findTaskByKey(data, "pendingSalesDeliveries_plural");

    assertEquals("pendingSalesDeliveries_plural", task.getString("taskKey"),
        "taskKey must be 'pendingSalesDeliveries_plural' (plural) when count=3");
  }

  // ── Exception / error path ────────────────────────────────────────────────

  /**
   * Verifies that the handler returns HTTP 500 when an unexpected exception
   * is thrown while building the response.
   */
  @Test
  void testHandleExceptionInContextReturns500() {
    obContextMock.when(OBContext::getOBContext).thenThrow(new RuntimeException("DB unavailable"));
    assertEquals(500, handler.handle(getContext()).getHttpStatus());
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  /**
   * Finds a task in the data array by its exact taskKey.
   * Throws {@link AssertionError} if not found.
   */
  private JSONObject findTaskByKey(JSONArray data, String taskKey) throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if (taskKey.equals(task.optString("taskKey"))) {
        return task;
      }
    }
    throw new AssertionError("Task with taskKey='" + taskKey + "' not found in data (length=" + data.length() + ")");
  }

  /**
   * Finds a task in the data array whose taskKey starts with the given prefix.
   * Throws {@link AssertionError} if not found.
   */
  private JSONObject findTaskByKeyPrefix(JSONArray data, String prefix) throws Exception {
    for (int i = 0; i < data.length(); i++) {
      JSONObject task = data.getJSONObject(i);
      if (task.optString("taskKey", "").startsWith(prefix)) {
        return task;
      }
    }
    throw new AssertionError(
        "Task with taskKey prefix='" + prefix + "' not found in data (length=" + data.length() + ")");
  }
}
