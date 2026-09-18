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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.ClientOutcome;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.RealignReport;

/**
 * Unit tests for {@link CostingCadenceStartup} (ETP-5370) — the boot-time migration that brings
 * every EXISTING tenant to the 30-second costing cadence, marks each one in the data-fix ledger, and
 * from the next boot on does nothing.
 *
 * <p>Mirrors {@link StoredColumnQueueScheduleStartupTest}'s convention: the class under test is
 * subclassed and its package-visible seams are overridden, so no test ever sleeps on the real
 * scheduler wait or reaches the real {@code OBScheduler}. The ledger, which is plain JDBC through
 * {@code OBDal.getConnection()}, is mocked end to end — {@link Connection} →
 * {@link PreparedStatement} → {@link ResultSet} — with the SELECT and the INSERT handed DIFFERENT
 * statement mocks so the reads can be told apart from the writes.
 *
 * <p><b>What is actually worth asserting here.</b> This class holds no business logic — it is a
 * trigger with a ledger — so the assertions are about the four decisions it does make:</p>
 *
 * <ol>
 *   <li><b>The sweep argument is {@code null}.</b> {@code null} is what makes
 *       {@link OnboardingCostingScheduleService#realignCadence(String, Set)} cross-tenant; any client
 *       id here would silently realign one tenant at boot and report nothing unusual, which is
 *       exactly the per-tenant manual step this class exists to remove.</li>
 *   <li><b>It is a MIGRATION, not a standing policy.</b> Marked tenants go into the skip set, so the
 *       second boot writes nothing. A permanent sweep would re-commit and re-arm Quartz once per
 *       tenant per restart to leave everything as it was, and would silently reset a user-chosen
 *       costing frequency on the next deploy. The "no INSERT, no commit on an empty report" test is
 *       what pins that down.</li>
 *   <li><b>A FAILED tenant is left UNMARKED.</b> Marking it would consume its only retry — the next
 *       boot is the whole recovery mechanism for a tenant whose re-arm did not take.</li>
 *   <li><b>An unreadable ledger is fatal to the pass, on purpose.</b> Swallowing it would mean
 *       guessing "nobody is migrated" and re-sweeping the entire fleet, re-arming every tenant's
 *       Quartz trigger for nothing.</li>
 * </ol>
 *
 * <p>The remediation itself is covered by
 * {@code com.etendoerp.go.onboarding.OnboardingCostingScheduleRealignTest}; here the service is a
 * mock substituted through the {@code scheduleService()} seam.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CostingCadenceStartupTest {

  private static final String CLIENT_ONE_ID = "client-1";
  private static final String CLIENT_TWO_ID = "client-2";
  private static final String CLIENT_THREE_ID = "client-3";
  private static final String REQUEST_ONE_ID = "req-1";
  private static final String REQUEST_TWO_ID = "req-2";
  private static final String REQUEST_THREE_ID = "req-3";
  private static final String FAILURE_DETAIL = "Could not re-arm costing request req-3";

  private static final String MIGRATED_CLIENT_A = "already-migrated-a";
  private static final String MIGRATED_CLIENT_B = "already-migrated-b";

  /**
   * The ledger key, as the literal that actually reaches the database. Deliberately NOT read from
   * {@code CostingCadenceStartup.MARKER_FIX_ID}: changing that constant changes which tenants count
   * as migrated on every existing instance, so it is a wire value and a test that reused the
   * constant would agree with any change to it.
   */
  private static final String EXPECTED_FIX_ID = "__costing-cadence-30s__";

  private static final String SYSTEM_ID = "0";
  private static final String STATUS_APPLIED = "APPLIED";
  private static final String STATUS_SKIPPED_NOT_NEEDED = "SKIPPED_NOT_NEEDED";

  // Bind positions of SQL_INSERT_MARKER, so a row can be read back by meaning rather than by order.
  private static final int COL_AD_CLIENT_ID = 1;
  private static final int COL_AD_ORG_ID = 2;
  private static final int COL_REMEDIATED_CLIENT_ID = 5;
  private static final int COL_FIX_ID = 6;
  private static final int COL_STATUS = 7;
  private static final int COL_ROWS_AFFECTED = 9;
  private static final int COL_DETAIL = 10;

  @Mock
  private OBDal dal;
  @Mock
  private Connection connection;
  @Mock
  private PreparedStatement selectStatement;
  @Mock
  private PreparedStatement insertStatement;
  @Mock
  private ResultSet resultSet;
  @Mock
  private OnboardingCostingScheduleService scheduleService;
  @Mock
  private RealignReport report;
  @Mock
  private ClientOutcome outcomeRealigned;
  @Mock
  private ClientOutcome outcomeAlreadyCorrect;
  @Mock
  private ClientOutcome outcomeFailed;

  @Captor
  private ArgumentCaptor<Set<String>> skipCaptor;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;

  private TestableStartup startup;

  /** Every SQL string the pass prepared, in order. */
  private final List<String> preparedSql = new ArrayList<>();
  /** One entry per completed INSERT, keyed by bind position. */
  private final List<Map<Integer, Object>> insertedRows = new ArrayList<>();
  /** Binds accumulated for the INSERT currently being built. */
  private final Map<Integer, Object> pendingRow = new LinkedHashMap<>();

  @BeforeEach
  void setUp() throws SQLException {
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    obContextStatic = mockStatic(OBContext.class);

    startup = new TestableStartup(scheduleService);

    when(dal.getConnection()).thenReturn(connection);
    // The SELECT and the INSERT must be distinguishable, otherwise "nothing was written" could not
    // be told apart from "nothing was read".
    when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      preparedSql.add(sql);
      return sql.trim().startsWith("SELECT") ? selectStatement : insertStatement;
    });

    when(selectStatement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(false);

    doAnswer(invocation -> {
      int index = invocation.getArgument(0);
      return recordBind(index, invocation.getArgument(1));
    }).when(insertStatement).setString(anyInt(), any());
    doAnswer(invocation -> {
      int index = invocation.getArgument(0);
      return recordBind(index, invocation.getArgument(1));
    }).when(insertStatement).setInt(anyInt(), anyInt());
    doAnswer(invocation -> {
      int index = invocation.getArgument(0);
      return recordBind(index, invocation.getArgument(1));
    }).when(insertStatement).setTimestamp(anyInt(), any());
    when(insertStatement.executeUpdate()).thenAnswer(invocation -> {
      insertedRows.add(new LinkedHashMap<>(pendingRow));
      pendingRow.clear();
      return 1;
    });

    when(report.isSchedulerAvailable()).thenReturn(true);
    when(report.getOutcomes()).thenReturn(List.of());
    when(scheduleService.realignCadence(any(), any())).thenReturn(report);
  }

  @AfterEach
  void tearDown() {
    obContextStatic.close();
    obDalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── the sweep call ────────────────────────────────────────────────────────

  /**
   * {@code null} is the whole point of the first argument: it is what turns {@code realignCadence}
   * into a cross-tenant sweep. Asserting the ARGUMENT rather than just "the service was called" is
   * what separates this from a boot hook that quietly fixed one client.
   */
  @Test
  @DisplayName("A ready scheduler triggers one cross-tenant sweep")
  void testRealignEveryTenantSweepsEveryTenantWhenTheSchedulerIsReady() throws SQLException {
    startup.realignEveryTenant();

    verify(scheduleService).realignCadence(isNull(), skipCaptor.capture());
    assertTrue(skipCaptor.getValue().isEmpty(), "an empty ledger means nobody is skipped");
    assertEquals(1, startup.seamCalls);
    verify(selectStatement).setString(1, EXPECTED_FIX_ID);
    assertTrue(preparedSql.get(0).contains("etgo_data_fix_history"),
        "the markers must be read from the shared data-fix ledger, got: " + preparedSql.get(0));
  }

  /**
   * The case that matters most for correctness. A half-corrected tenant — cadence columns rewritten,
   * trigger never re-armed — is the one outcome worth avoiding, so a scheduler that never leaves
   * standby means the pass is skipped entirely and retried on the next boot. The ledger is not even
   * read: there is nothing to decide.
   */
  @Test
  @DisplayName("A scheduler that never wakes up leaves every row untouched")
  void testRealignEveryTenantDoesNothingWhenTheSchedulerNeverBecomesAvailable() throws SQLException {
    startup.schedulingAllowed = false;

    startup.realignEveryTenant();

    verify(scheduleService, never()).realignCadence(any(), any());
    assertEquals(0, startup.seamCalls, "the service must not even be constructed");
    verify(connection, never()).prepareStatement(anyString());
  }

  /**
   * The scheduler can also go unavailable mid-pass, which the report reports rather than throws.
   * Nothing further is read and no tenant is marked — marking a tenant whose trigger was never
   * re-armed would consume its only retry.
   */
  @Test
  @DisplayName("A report with no scheduler is a quiet no-op, and marks nobody")
  void testRealignEveryTenantIsQuietWhenTheReportHasNoScheduler() {
    when(report.isSchedulerAvailable()).thenReturn(false);

    assertDoesNotThrow(() -> startup.realignEveryTenant());

    verify(scheduleService).realignCadence(isNull(), any());
    verify(report, never()).getOutcomes();
    assertTrue(insertedRows.isEmpty(), "no marker may be written: " + insertedRows);
    verify(dal, never()).commitAndClose();
  }

  // ── the one-shot ledger ───────────────────────────────────────────────────

  /**
   * The mechanism that turns this from a standing policy into a migration: whoever the ledger
   * already lists is handed to the service as the skip set, so their rows are never touched and
   * their Quartz triggers are never re-armed again.
   */
  @Test
  @DisplayName("Tenants already in the ledger are passed to the service as the skip set")
  void testRealignEveryTenantSkipsTheTenantsTheLedgerAlreadyMarked() throws SQLException {
    when(resultSet.next()).thenReturn(true, true, false);
    when(resultSet.getString(1)).thenReturn(MIGRATED_CLIENT_A, MIGRATED_CLIENT_B);

    startup.realignEveryTenant();

    verify(scheduleService).realignCadence(isNull(), skipCaptor.capture());
    assertEquals(Set.of(MIGRATED_CLIENT_A, MIGRATED_CLIENT_B), skipCaptor.getValue());
  }

  /**
   * The second boot, and every boot after it. An empty report means the service found nothing left
   * to realign, so there is nothing to mark and nothing to commit — this is the assertion that
   * proves the migration really stops instead of quietly re-running forever.
   */
  @Test
  @DisplayName("An empty report writes no marker and commits nothing")
  void testRealignEveryTenantWritesNoMarkerWhenEveryTenantIsAlreadyMigrated() throws SQLException {
    assertDoesNotThrow(() -> startup.realignEveryTenant());

    verify(insertStatement, never()).executeUpdate();
    assertTrue(insertedRows.isEmpty());
    verify(dal, never()).commitAndClose();
    verify(report).getOutcomes();
  }

  /**
   * Both non-failing statuses are marked, but with the ledger's own distinction: {@code APPLIED}
   * when the cadence was actually rewritten, {@code SKIPPED_NOT_NEEDED} when it was already correct.
   * Reading a row back by bind position keeps this honest about WHICH column carries what.
   */
  @Test
  @DisplayName("Realigned and already-correct tenants are marked with the matching status")
  void testRealignEveryTenantMarksMigratedTenantsWithTheMatchingStatus() throws SQLException {
    givenOutcome(outcomeRealigned, CLIENT_ONE_ID, ClientOutcome.REALIGNED, REQUEST_ONE_ID, 1, null);
    givenOutcome(outcomeAlreadyCorrect, CLIENT_TWO_ID, ClientOutcome.ALREADY_CORRECT,
        REQUEST_TWO_ID, 0, null);
    when(report.getOutcomes()).thenReturn(List.of(outcomeRealigned, outcomeAlreadyCorrect));

    startup.realignEveryTenant();

    verify(insertStatement, times(2)).executeUpdate();
    assertEquals(2, insertedRows.size());

    Map<Integer, Object> applied = insertedRowFor(CLIENT_ONE_ID);
    assertEquals(STATUS_APPLIED, applied.get(COL_STATUS));
    assertEquals(EXPECTED_FIX_ID, applied.get(COL_FIX_ID));
    assertEquals(Integer.valueOf(1), applied.get(COL_ROWS_AFFECTED),
        "rows_affected carries the deactivated count");
    assertEquals("Surviving request " + REQUEST_ONE_ID, applied.get(COL_DETAIL));
    assertEquals(SYSTEM_ID, applied.get(COL_AD_CLIENT_ID), "the ledger row is System-owned");
    assertEquals(SYSTEM_ID, applied.get(COL_AD_ORG_ID));

    Map<Integer, Object> skipped = insertedRowFor(CLIENT_TWO_ID);
    assertEquals(STATUS_SKIPPED_NOT_NEEDED, skipped.get(COL_STATUS));
    assertEquals(EXPECTED_FIX_ID, skipped.get(COL_FIX_ID));
    assertEquals(Integer.valueOf(0), skipped.get(COL_ROWS_AFFECTED));

    // Without the ledger's own uniqueness guard a re-run could duplicate markers, which would make
    // the "already migrated" set meaningless.
    assertTrue(preparedSql.stream().anyMatch(
        sql -> sql.contains("ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un")),
        "the marker INSERT must be guarded by the ledger uniqueness constraint: " + preparedSql);

    verify(dal).commitAndClose();
  }

  /**
   * The retry guarantee. A tenant whose realignment failed must stay out of the ledger, because the
   * next boot is its only recovery path — marking it would make the failure permanent and invisible.
   */
  @Test
  @DisplayName("A failed tenant is left unmarked so the next boot retries it")
  void testRealignEveryTenantLeavesAFailedTenantUnmarkedForTheNextBoot() throws SQLException {
    givenOutcome(outcomeRealigned, CLIENT_ONE_ID, ClientOutcome.REALIGNED, REQUEST_ONE_ID, 0, null);
    givenOutcome(outcomeFailed, CLIENT_THREE_ID, ClientOutcome.FAILED, REQUEST_THREE_ID, 0,
        FAILURE_DETAIL);
    when(report.getOutcomes()).thenReturn(List.of(outcomeRealigned, outcomeFailed));

    assertDoesNotThrow(() -> startup.realignEveryTenant());

    verify(insertStatement, times(1)).executeUpdate();
    assertEquals(1, insertedRows.size());
    assertEquals(CLIENT_ONE_ID, insertedRows.get(0).get(COL_REMEDIATED_CLIENT_ID));
    assertTrue(insertedRows.stream().noneMatch(row -> row.containsValue(CLIENT_THREE_ID)),
        "the failed tenant must appear in no bind of any marker row: " + insertedRows);

    // It IS reported, just never marked — that log line is the only trace an operator gets.
    verify(outcomeFailed).getDetail();
    verify(outcomeFailed).getClientId();
    verify(outcomeFailed).getRequestId();
    verify(dal).commitAndClose();
  }

  /**
   * An unreadable ledger must abort the pass rather than default to "nobody is migrated": that
   * default would re-sweep and re-arm the entire fleet on a boot where the only thing actually
   * broken was a query. The service is not reached at all.
   */
  @Test
  @DisplayName("A ledger read failure propagates and no tenant is swept")
  void testRealignEveryTenantPropagatesAFailureToReadTheLedger() throws SQLException {
    when(selectStatement.executeQuery()).thenThrow(new SQLException("ledger unreadable"));

    assertThrows(OBException.class, () -> startup.realignEveryTenant());

    verify(scheduleService, never()).realignCadence(any(), any());
    assertEquals(0, startup.seamCalls);
  }

  @Test
  @DisplayName("A ledger query that cannot even be prepared propagates and no tenant is swept")
  void testRealignEveryTenantPropagatesAFailureToPrepareTheLedgerQuery() throws SQLException {
    doThrow(new SQLException("no connection")).when(connection).prepareStatement(anyString());

    assertThrows(OBException.class, () -> startup.realignEveryTenant());

    verify(scheduleService, never()).realignCadence(any(), any());
    assertEquals(0, startup.seamCalls);
  }

  // ── outcome consumption ───────────────────────────────────────────────────

  /**
   * A failed tenant is recorded by the service, never thrown, and must not stop this hook: a boot
   * that aborted on the first bad tenant would leave every later one on the old cadence, and the
   * only trace would be a startup stack trace nobody reads.
   */
  @Test
  @DisplayName("Every outcome status is consumed and a failed client never propagates")
  void testRealignEveryTenantConsumesEveryOutcomeStatusWithoutThrowing() {
    givenOutcome(outcomeRealigned, CLIENT_ONE_ID, ClientOutcome.REALIGNED, REQUEST_ONE_ID, 1, null);
    givenOutcome(outcomeAlreadyCorrect, CLIENT_TWO_ID, ClientOutcome.ALREADY_CORRECT,
        REQUEST_TWO_ID, 0, null);
    givenOutcome(outcomeFailed, CLIENT_THREE_ID, ClientOutcome.FAILED, REQUEST_THREE_ID, 0,
        FAILURE_DETAIL);
    when(report.getOutcomes())
        .thenReturn(List.of(outcomeRealigned, outcomeAlreadyCorrect, outcomeFailed));

    assertDoesNotThrow(() -> startup.realignEveryTenant());

    // Every outcome contributes to the deactivated total, the failed one included. (The marked ones
    // are read a second time for the ledger's rows_affected, hence atLeastOnce.)
    verify(outcomeRealigned, atLeastOnce()).getDeactivated();
    verify(outcomeAlreadyCorrect, atLeastOnce()).getDeactivated();
    verify(outcomeFailed, atLeastOnce()).getDeactivated();

    // Only the failure branch reports a cause; the marked ones never have their detail read.
    verify(outcomeFailed).getDetail();
    verify(outcomeRealigned, never()).getDetail();
    verify(outcomeAlreadyCorrect, never()).getDetail();

    assertEquals(2, insertedRows.size(), "only the two non-failing tenants are marked");
  }

  @Test
  @DisplayName("runPass delegates to the realignment pass")
  void testRunPassDelegatesToRealignEveryTenant() {
    startup.runPass();

    assertEquals(1, startup.realignCalls);
    verify(scheduleService).realignCadence(isNull(), any());
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  /** Records one JDBC bind of the INSERT being built; always returns {@code null} (void setter). */
  private Object recordBind(int index, Object value) {
    pendingRow.put(index, value);
    return null;
  }

  private Map<Integer, Object> insertedRowFor(String clientId) {
    return insertedRows.stream()
        .filter(row -> clientId.equals(row.get(COL_REMEDIATED_CLIENT_ID)))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "no marker row was inserted for client " + clientId + ", got: " + insertedRows));
  }

  private static void givenOutcome(ClientOutcome outcome, String clientId, String status,
      String requestId, int deactivated, String detail) {
    when(outcome.getClientId()).thenReturn(clientId);
    when(outcome.getStatus()).thenReturn(status);
    when(outcome.getRequestId()).thenReturn(requestId);
    when(outcome.getDeactivated()).thenReturn(deactivated);
    when(outcome.getDetail()).thenReturn(detail);
  }

  /**
   * Substitutes the two seams that would otherwise reach the real scheduler — {@code
   * scheduleService()} and the bounded {@code waitForSchedulingAllowed()} poll loop, which would
   * really sleep for up to two minutes — and records how many times each was reached.
   *
   * <p>{@code realignEveryTenant()} is recorded but still delegates to {@code super}, so the
   * {@code runPass} delegation test proves the real pass ran rather than that a stub was called.
   */
  private static final class TestableStartup extends CostingCadenceStartup {
    private final OnboardingCostingScheduleService service;
    private boolean schedulingAllowed = true;
    private int seamCalls;
    private int realignCalls;

    private TestableStartup(OnboardingCostingScheduleService service) {
      this.service = service;
    }

    @Override
    OnboardingCostingScheduleService scheduleService() {
      seamCalls++;
      return service;
    }

    @Override
    boolean waitForSchedulingAllowed() {
      return schedulingAllowed;
    }

    @Override
    void realignEveryTenant() {
      realignCalls++;
      super.realignEveryTenant();
    }
  }
}
