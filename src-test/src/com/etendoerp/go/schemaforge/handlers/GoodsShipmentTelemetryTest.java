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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.NeoBatchScope;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.ProductMovementsPolicy;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Specifies the goods shipment completion KPI event: {@code backend_goods_shipment_completed} is
 * sent only when a request moves a sales shipment from a non-complete status to {@code CO}, with
 * the tenant and the flag as its only properties.
 *
 * <p>The stored shipment row is answered by a mock statement; the event is captured on a mock
 * {@link NeoTelemetryService} (sending runs on a background thread, hence the timeouts).</p>
 *
 * @covers com.etendoerp.go.schemaforge.handlers.GoodsShipmentTelemetry
 */
class GoodsShipmentTelemetryTest {

  private static final String STATE_SQL = "FROM m_inout io";
  private static final String CLIENT_ID = "CLIENT-A";
  private static final String RECORD_ID = "SHIP-0001";
  private static final long WAIT_MS = 3000L;

  private MockedStatic<OBDal> obDalStatic;
  private Connection conn;
  private PreparedStatement stateStatement;
  private NeoTelemetryService telemetry;
  private OBContext obContext;

  @BeforeEach
  void setUp() {
    conn = mock(Connection.class);
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenReturn(conn);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    stateStatement = null;
    telemetry = mock(NeoTelemetryService.class);
    GoodsShipmentTelemetry.setTelemetryService(telemetry);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    obContext = mock(OBContext.class);
    when(obContext.getCurrentClient()).thenReturn(client);
  }

  @AfterEach
  void tearDown() {
    GoodsShipmentTelemetry.setTelemetryService(null);
    obDalStatic.close();
  }

  // ── beforeAction ─────────────────────────────────────────────────────────────────────────

  @Test
  void beforeActionRemembersTheStoredStatusOnTheContext() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    givenStoredStates(state("DR", "Y", "N"));

    GoodsShipmentTelemetry.beforeAction(ctx);

    assertEquals("DR", ctx.getAttribute(GoodsShipmentTelemetry.ATTR_STATUS_BEFORE));
  }

  @Test
  void beforeActionRecordsNothingForABlankRecordId() throws Exception {
    NeoContext ctx = context("  ", 200);

    GoodsShipmentTelemetry.beforeAction(ctx);

    assertNull(ctx.getAttribute(GoodsShipmentTelemetry.ATTR_STATUS_BEFORE));
    verify(conn, never()).prepareStatement(anyString());
  }

  @Test
  void beforeActionRecordsNothingForAnUnknownShipment() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    givenStoredStates(empty);

    GoodsShipmentTelemetry.beforeAction(ctx);

    assertNull(ctx.getAttribute(GoodsShipmentTelemetry.ATTR_STATUS_BEFORE));
  }

  @Test
  void beforeActionSwallowsDatabaseFailures() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

    assertDoesNotThrow(() -> GoodsShipmentTelemetry.beforeAction(ctx));
    assertNull(ctx.getAttribute(GoodsShipmentTelemetry.ATTR_STATUS_BEFORE));
  }

  // ── afterAction: the event ───────────────────────────────────────────────────────────────

  @Test
  void completingASalesShipmentEmitsTheEventWithOnlyTheTenantAndTheFlag() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    givenStoredStates(state("DR", "Y", "N"), state("CO", "Y", "N"));

    try (MockedStatic<ProductMovementsPolicy> policy = mockStatic(ProductMovementsPolicy.class)) {
      policy.when(ProductMovementsPolicy::isEnabled).thenReturn(true);
      GoodsShipmentTelemetry.beforeAction(ctx);
      GoodsShipmentTelemetry.afterAction(ctx);
    }

    Map<String, Object> props = emitted();
    assertEquals(CLIENT_ID, props.get("account_id"));
    assertEquals(true, props.get("flag_enabled"));
    assertEquals(new HashSet<>(Arrays.asList("account_id", "flag_enabled")), props.keySet(),
        "no record identifier or other tenant key may be sent");
    for (Object value : props.values()) {
      assertFalse(RECORD_ID.equals(value), "the shipment id leaked: " + props);
    }
  }

  /**
   * Columns: status before, status after, IsSOTrx, IsReturn, HTTP status of the action. None of
   * these may send anything. An empty cell is a SQL NULL: a NULL re-read DocStatus, or a NULL
   * IsReturn because the document type is missing (LEFT JOIN).
   */
  @ParameterizedTest(name = "{0} -> {1} issotrx={2} return={3} http={4}")
  @CsvSource({
      "CO, CO, Y, N, 200",
      "DR, CO, Y, N, 400",
      "DR, CO, Y, N, 500",
      "DR, VO, Y, N, 200",
      "DR, DR, Y, N, 200",
      "DR, CO, N, N, 200",
      "DR, CO, Y, Y, 200",
      "DR, , Y, N, 200",
      "DR, CO, Y, , 200",
  })
  void noEventWhenTheRequestDidNotCompleteASalesShipment(String before, String after,
      String sotrx, String isReturn, int http) throws Exception {
    NeoContext ctx = context(RECORD_ID, http);
    givenStoredStates(state(before, sotrx, isReturn), state(after, sotrx, isReturn));

    GoodsShipmentTelemetry.beforeAction(ctx);
    GoodsShipmentTelemetry.afterAction(ctx);

    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  @Test
  void noEventWhenTheShipmentRowIsNotFoundOnReRead() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    ResultSet missing = mock(ResultSet.class);
    when(missing.next()).thenReturn(false);
    givenStoredStates(state("DR", "Y", "N"), missing);

    GoodsShipmentTelemetry.beforeAction(ctx);
    GoodsShipmentTelemetry.afterAction(ctx);

    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  @Test
  void noEventWithoutABeforeSnapshot() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    givenStoredStates(state("CO", "Y", "N"));

    GoodsShipmentTelemetry.afterAction(ctx);

    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  @Test
  void afterActionNeverPropagatesAnException() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    givenStoredStates(state("DR", "Y", "N"));
    GoodsShipmentTelemetry.beforeAction(ctx);
    when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

    assertDoesNotThrow(() -> GoodsShipmentTelemetry.afterAction(ctx));
    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  @Test
  void aFailingFlagEvaluationIsReportedAsOff() throws Exception {
    NeoContext ctx = context(RECORD_ID, 200);
    givenStoredStates(state("DR", "Y", "N"), state("CO", "Y", "N"));

    try (MockedStatic<ProductMovementsPolicy> policy = mockStatic(ProductMovementsPolicy.class)) {
      policy.when(ProductMovementsPolicy::isEnabled).thenThrow(new IllegalStateException("x"));
      GoodsShipmentTelemetry.beforeAction(ctx);
      GoodsShipmentTelemetry.afterAction(ctx);
    }

    assertEquals(false, emitted().get("flag_enabled"));
  }

  // ── batch scope ──────────────────────────────────────────────────────────────────────────

  @Test
  void insideABatchTheEventWaitsForTheCommit() throws Exception {
    Object previous = invokeScope("open", new Class<?>[] {boolean.class}, false);
    try {
      NeoContext ctx = context(RECORD_ID, 200);
      givenStoredStates(state("DR", "Y", "N"), state("CO", "Y", "N"));
      GoodsShipmentTelemetry.beforeAction(ctx);
      GoodsShipmentTelemetry.afterAction(ctx);

      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());

      invokeScope("runAfterCommitActions", new Class<?>[0]);
      emitted();
    } finally {
      invokeScope("close", new Class<?>[] {NeoBatchScope.class}, previous);
    }
  }

  @Test
  void aRolledBackBatchSendsNothing() throws Exception {
    Object previous = invokeScope("open", new Class<?>[] {boolean.class}, false);
    try {
      NeoContext ctx = context(RECORD_ID, 200);
      givenStoredStates(state("DR", "Y", "N"), state("CO", "Y", "N"));
      GoodsShipmentTelemetry.beforeAction(ctx);
      GoodsShipmentTelemetry.afterAction(ctx);
    } finally {
      // Rollback path: the scope closes without runAfterCommitActions having run.
      invokeScope("close", new Class<?>[] {NeoBatchScope.class}, previous);
    }

    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  @Test
  void aBatchOfThreeDistinctShipmentsSendsThreeEventsOnlyAfterTheCommit() throws Exception {
    givenStoredStates(state("CO", "Y", "N"));
    Object previous = invokeScope("open", new Class<?>[] {boolean.class}, false);
    try {
      for (String id : new String[] {"SHIP-A", "SHIP-B", "SHIP-C"}) {
        completeFor(obContext, id);
      }

      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());

      invokeScope("runAfterCommitActions", new Class<?>[0]);
      verify(telemetry, timeout(WAIT_MS).times(3)).emit(
          eq(NeoTelemetryEvents.BACKEND_GOODS_SHIPMENT_COMPLETED), anyMap(), eq(CLIENT_ID));
    } finally {
      invokeScope("close", new Class<?>[] {NeoBatchScope.class}, previous);
    }
  }

  /**
   * The second completion of the same shipment sees {@code CO} in its pre-hook (the first one
   * already stored it), so only the first of the two emits.
   */
  @Test
  void theSameShipmentCompletedTwiceInOneBatchSendsOneEvent() throws Exception {
    NeoContext first = context(RECORD_ID, 200);
    NeoContext second = context(RECORD_ID, 200);
    givenStoredStates(state("DR", "Y", "N"), state("CO", "Y", "N"), state("CO", "Y", "N"),
        state("CO", "Y", "N"));
    Object previous = invokeScope("open", new Class<?>[] {boolean.class}, false);
    try {
      GoodsShipmentTelemetry.beforeAction(first);
      GoodsShipmentTelemetry.afterAction(first);
      GoodsShipmentTelemetry.beforeAction(second);
      GoodsShipmentTelemetry.afterAction(second);

      invokeScope("runAfterCommitActions", new Class<?>[0]);
      verify(telemetry, timeout(WAIT_MS).times(1)).emit(
          eq(NeoTelemetryEvents.BACKEND_GOODS_SHIPMENT_COMPLETED), anyMap(), eq(CLIENT_ID));
      verify(telemetry, after(400).times(1)).emit(anyString(), anyMap(), any());
    } finally {
      invokeScope("close", new Class<?>[] {NeoBatchScope.class}, previous);
    }
  }

  // ── savepoint around the state read ──────────────────────────────────────────────────────

  @Test
  void aFailingSelectRollsBackToTheSavepointRethrowsAndLeavesTheConnectionUsable()
      throws Exception {
    Savepoint savepoint = givenSavepoint();
    SQLException failure = new SQLException("select failed");
    when(conn.prepareStatement(anyString())).thenThrow(failure);

    SQLException thrown = assertThrows(SQLException.class,
        () -> GoodsShipmentTelemetry.queryState(RECORD_ID));

    assertSame(failure, thrown);
    verify(conn).rollback(savepoint);
    verify(conn, never()).releaseSavepoint(any());
    verify(conn, never()).rollback();
    verify(conn, never()).close();

    // The same connection answers the next read.
    stateStatement = null;
    givenStoredStates(state("CO", "Y", "N"));
    assertEquals("CO", GoodsShipmentTelemetry.queryState(RECORD_ID).docStatus);
  }

  @Test
  void aSuccessfulReadReleasesTheSavepointOnceAndReturnsTheState() throws Exception {
    Savepoint savepoint = givenSavepoint();
    givenStoredStates(state("CO", "Y", "N"));

    GoodsShipmentTelemetry.ShipmentState state = GoodsShipmentTelemetry.queryState(RECORD_ID);

    assertEquals("CO", state.docStatus);
    assertTrue(state.sales);
    verify(conn, times(1)).releaseSavepoint(savepoint);
    verify(conn, never()).rollback(any(Savepoint.class));
  }

  @Test
  void theOriginalExceptionPropagatesWhenTheSavepointRollbackFails() throws Exception {
    Savepoint savepoint = givenSavepoint();
    SQLException original = new SQLException("select failed");
    when(conn.prepareStatement(anyString())).thenThrow(original);
    doThrow(new SQLException("rollback failed")).when(conn).rollback(savepoint);

    SQLException thrown = assertThrows(SQLException.class,
        () -> GoodsShipmentTelemetry.queryState(RECORD_ID));

    assertSame(original, thrown);
  }

  @Test
  void aFailingSetSavepointSkipsTheTelemetryAndNothingPropagates() throws Exception {
    when(conn.setSavepoint()).thenThrow(new SQLException("no savepoint"));
    NeoContext ctx = context(RECORD_ID, 200);
    ctx.setAttribute(GoodsShipmentTelemetry.ATTR_STATUS_BEFORE, "DR");

    assertDoesNotThrow(() -> GoodsShipmentTelemetry.beforeAction(ctx));
    assertDoesNotThrow(() -> GoodsShipmentTelemetry.afterAction(ctx));

    verify(conn, never()).prepareStatement(anyString());
    verify(conn, never()).rollback(any(Savepoint.class));
    verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
  }

  // ── flag memoization per batch ───────────────────────────────────────────────────────────

  @Test
  void insideABatchTheFlagIsEvaluatedOncePerTenant() throws Exception {
    givenSavepoint();
    givenStoredStates(state("CO", "Y", "N"));
    Object previous = invokeScope("open", new Class<?>[] {boolean.class}, false);
    try (MockedStatic<ProductMovementsPolicy> policy = mockStatic(ProductMovementsPolicy.class)) {
      policy.when(ProductMovementsPolicy::isEnabled).thenReturn(true);

      for (int i = 0; i < 3; i++) {
        completeFor(obContext);
      }
      policy.verify(ProductMovementsPolicy::isEnabled, times(1));

      completeFor(obContextOf("CLIENT-B"));
      completeFor(obContextOf("CLIENT-B"));
      policy.verify(ProductMovementsPolicy::isEnabled, times(2));
    } finally {
      invokeScope("close", new Class<?>[] {NeoBatchScope.class}, previous);
    }
  }

  @Test
  void outsideABatchTheFlagIsEvaluatedOnEveryCompletion() throws Exception {
    givenSavepoint();
    givenStoredStates(state("CO", "Y", "N"));
    try (MockedStatic<ProductMovementsPolicy> policy = mockStatic(ProductMovementsPolicy.class)) {
      policy.when(ProductMovementsPolicy::isEnabled).thenReturn(true);

      completeFor(obContext);
      completeFor(obContext);

      policy.verify(ProductMovementsPolicy::isEnabled, times(2));
    }
  }

  // ── sender ───────────────────────────────────────────────────────────────────────────────

  @Test
  void aThrowingEmitDoesNotPropagateAndALaterEventIsStillSent() throws Exception {
    givenSavepoint();
    givenStoredStates(state("CO", "Y", "N"));
    doThrow(new IllegalStateException("sink down")).doNothing().when(telemetry)
        .emit(anyString(), anyMap(), any());

    assertDoesNotThrow(() -> completeFor(obContext));
    verify(telemetry, timeout(WAIT_MS).times(1)).emit(anyString(), anyMap(), any());
    assertDoesNotThrow(() -> completeFor(obContext));

    verify(telemetry, timeout(WAIT_MS).times(2)).emit(eq(NeoTelemetryEvents.BACKEND_GOODS_SHIPMENT_COMPLETED),
        anyMap(), eq(CLIENT_ID));
  }

  /**
   * The sender queue is bounded and drops when full: the one worker thread is held busy and its
   * queue filled, so the completion is rejected. Nothing is thrown and nothing is emitted.
   */
  @Test
  void aFullSenderQueueDropsTheEventWithoutThrowing() throws Exception {
    givenSavepoint();
    givenStoredStates(state("CO", "Y", "N"));
    Field senderField = GoodsShipmentTelemetry.class.getDeclaredField("SENDER");
    senderField.setAccessible(true);
    ThreadPoolExecutor sender = (ThreadPoolExecutor) senderField.get(null);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch busy = new CountDownLatch(1);
    CountDownLatch drained = new CountDownLatch(1);
    sender.execute(() -> {
      busy.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      assertTrue(busy.await(WAIT_MS, TimeUnit.MILLISECONDS), "the sender worker never started");
      while (sender.getQueue().remainingCapacity() > 1) {
        sender.execute(() -> { });
      }
      // the last queued task: the worker is FIFO, so once it runs the whole queue has drained
      sender.execute(drained::countDown);

      assertDoesNotThrow(() -> completeFor(obContext));

      verify(telemetry, after(300).never()).emit(anyString(), anyMap(), any());
    } finally {
      release.countDown();
      assertTrue(drained.await(10, TimeUnit.SECONDS), "the sender queue never drained");
    }
  }

  // ── properties ───────────────────────────────────────────────────────────────────────────

  @Test
  void propertiesOmitTheAccountWhenUnknown() {
    Map<String, Object> props = GoodsShipmentTelemetry.properties(null, false);

    assertEquals(new HashSet<>(Arrays.asList("flag_enabled")), props.keySet());
    assertEquals(false, props.get("flag_enabled"));
  }

  @Test
  void propertiesCarryExactlyTheAccountAndTheFlag() {
    Map<String, Object> props = GoodsShipmentTelemetry.properties("ACC", true);

    assertEquals(new HashSet<>(Arrays.asList("account_id", "flag_enabled")), props.keySet());
    assertEquals("ACC", props.get("account_id"));
  }

  // ── fixtures ─────────────────────────────────────────────────────────────────────────────

  private Savepoint givenSavepoint() throws SQLException {
    Savepoint savepoint = mock(Savepoint.class);
    when(conn.setSavepoint()).thenReturn(savepoint);
    return savepoint;
  }

  private OBContext obContextOf(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    OBContext other = mock(OBContext.class);
    when(other.getCurrentClient()).thenReturn(client);
    return other;
  }

  /** One completion as the post-hook sees it: the pre-hook status is DR, the re-read says CO. */
  private void completeFor(OBContext ob) {
    completeFor(ob, RECORD_ID);
  }

  private void completeFor(OBContext ob, String shipmentId) {
    NeoContext ctx = NeoContext.builder()
        .specName("goods-shipment").entityName("goodsShipment").httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION).fieldName("documentAction").recordId(shipmentId)
        .requestBody(new JSONObject()).obContext(ob)
        .previousResult(new NeoResponse(200, new JSONObject())).build();
    ctx.setAttribute(GoodsShipmentTelemetry.ATTR_STATUS_BEFORE, "DR");
    GoodsShipmentTelemetry.afterAction(ctx);
  }

  private NeoContext context(String recordId, int status) {
    return NeoContext.builder()
        .specName("goods-shipment")
        .entityName("goodsShipment")
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId(recordId)
        .requestBody(new JSONObject())
        .obContext(obContext)
        .previousResult(new NeoResponse(status, new JSONObject()))
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> emitted() {
    ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
    verify(telemetry, timeout(WAIT_MS)).emit(eq(NeoTelemetryEvents.BACKEND_GOODS_SHIPMENT_COMPLETED),
        props.capture(), eq(CLIENT_ID));
    return props.getValue();
  }

  /** One stored shipment row: DocStatus, IsSOTrx, document type IsReturn. */
  private static ResultSet state(String docStatus, String soTrx, String isReturn)
      throws SQLException {
    ResultSet row = mock(ResultSet.class);
    when(row.next()).thenReturn(true);
    when(row.getString(1)).thenReturn(docStatus);
    when(row.getString(2)).thenReturn(soTrx);
    when(row.getString(3)).thenReturn(isReturn);
    return row;
  }

  /** Answers the shipment lookup with the given rows, one per call (before, after). */
  private void givenStoredStates(ResultSet first, ResultSet... rest) throws SQLException {
    if (stateStatement == null) {
      stateStatement = mock(PreparedStatement.class);
      when(conn.prepareStatement(argThat(sql -> sql != null && sql.contains(STATE_SQL))))
          .thenReturn(stateStatement);
    }
    when(stateStatement.executeQuery()).thenReturn(first, rest);
  }

  /**
   * NeoBatchScope's open/close/runAfterCommitActions are package-private to schemaforge, and this
   * test lives in schemaforge.handlers: no public seam exists from here (ProductDefaultsHandlerTest
   * calls them directly only because it sits in the same package). A test-only bridge class in
   * schemaforge would be a new class for no other benefit, so reflection stays.
   */
  private static Object invokeScope(String name, Class<?>[] types, Object... args)
      throws Exception {
    Method method = NeoBatchScope.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(null, args);
  }
}
