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
package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.ad.ui.ProcessRun;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessBundle;

/**
 * Unit tests for {@link SFAcctProcessMonitor} (ETP-5269).
 *
 * <p>Extends {@link BaseWebhookTest} for the shared {@code OBDal}/{@code OBContext} statics, and
 * mirrors {@code SFDebugInvitationBypassTest}'s convention of proving the access gate first and the
 * behaviour after it second. Three things this webhook does are load-bearing enough to be asserted
 * explicitly rather than inferred:</p>
 *
 * <ol>
 *   <li><b>Scope.</b> A manual run must be built from the CALLING session's client, not from the
 *       recurring System row's context — that is the whole point of the ETP-5269 scope change, and
 *       getting it wrong lets one tenant's admin post every other tenant's accounting. Asserted by
 *       capturing the {@link ProcessBundle} constructor arguments.</li>
 *   <li><b>The recurring row is never written.</b> Asserted by inspecting every invocation recorded
 *       on the recurring {@link ProcessRequest} mock and requiring that none of them is a setter,
 *       which survives a future refactor that reaches for a different setter than the one a
 *       hand-written {@code verify(..., never()).setX()} happened to name.</li>
 *   <li><b>The log is never on the wire.</b> {@code AD_PROCESS_RUN.LOG} and {@code REPORT} are read
 *       through {@code getLog()}/{@code getReport()}, so the test stubs both with a sentinel and
 *       requires the sentinel to be absent from the whole serialized response.</li>
 * </ol>
 *
 * <h2>How the criteria are mocked</h2>
 *
 * <p>Four different {@code OBCriteria} are built against only two entity classes
 * ({@link ProcessRun} twice, {@link ProcessRequest} twice), so a plain
 * {@code when(createCriteria(X)).thenReturn(mock)} cannot tell them apart. Each call therefore
 * mints a FRESH criteria mock that records the {@code toString()} of every {@link
 * org.hibernate.criterion.Criterion} added to it and resolves {@code list()} lazily from those
 * recordings — {@code status=PRC} identifies the in-progress probe, {@code active=true} identifies
 * the recurring-request lookup. Recording the criterions also lets the tenant-scope tests assert
 * the actual restriction, which is where the scoping really lives (the DAL is mocked, so a test
 * that "filtered" a fixture list would be asserting its own fixture).</p>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFAcctProcessMonitorTest extends BaseWebhookTest {

  private static final String PROCESS_ID = "800064";
  private static final String PROCESS_NAME = "Accounting server process";
  private static final String CALLER_CLIENT_ID = "tenant-client-1";
  private static final String OTHER_CLIENT_ID = "tenant-client-2";
  private static final String CALLER_USER_ID = "tenant-user-1";
  private static final String CALLER_ROLE_ID = "tenant-admin-role";
  private static final String CALLER_LANGUAGE = "es_ES";
  private static final String SYSTEM_CLIENT_ID = "0";

  /** Written into {@code getLog()}/{@code getReport()} and required to never reach the response. */
  private static final String LOG_SENTINEL = "SECRET-PROCESS-OUTPUT-DO-NOT-LEAK";

  private SFAcctProcessMonitor webhook;

  private MockedStatic<OBScheduler> obSchedulerMock;
  private MockedConstruction<ProcessBundle> processBundleConstruction;
  private final List<Object[]> bundleArguments = new ArrayList<>();

  private OBScheduler scheduler;
  private Process acctProcess;
  private ProcessRequest recurring;

  /** Criterion recordings, one inner list per criteria object, in creation order. */
  private final List<List<String>> runCriteria = new ArrayList<>();
  private final List<List<String>> requestCriteria = new ArrayList<>();

  /**
   * Every query and every scheduler call, in the order they actually happened. Some invariants of
   * this endpoint are about ORDER rather than about any single value — above all "the history is
   * read AFTER the run is scheduled", which is the whole of the W1 fix and which no count of
   * queries can express. Appended at {@code list()} time (i.e. the query really ran) and from the
   * {@code schedule(...)} stub.
   */
  private final List<String> callLog = new ArrayList<>();

  private static final String EVENT_SCHEDULE = "schedule";
  private static final String EVENT_HISTORY_READ = "query:runHistory";
  private static final String EVENT_IN_PROGRESS_PROBE = "query:runInProgress";

  /** Rows each of the four queries answers with. Reassigned per test. */
  private List<Process> processRows = new ArrayList<>();
  private List<ProcessRun> historyRows = new ArrayList<>();
  private List<ProcessRun> inProgressRows = new ArrayList<>();
  private List<ProcessRequest> recurringRows = new ArrayList<>();
  private List<ProcessRequest> pendingManualRows = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    webhook = new SFAcctProcessMonitor();

    acctProcess = mock(Process.class);
    when(acctProcess.getId()).thenReturn(PROCESS_ID);
    when(acctProcess.getName()).thenReturn(PROCESS_NAME);
    processRows = new ArrayList<>(Collections.singletonList(acctProcess));

    recurring = mock(ProcessRequest.class);
    when(recurring.getChannel()).thenReturn("Process Scheduler");
    when(recurring.getNextExecution()).thenReturn(new Date(1_800_000_000_000L));
    recurringRows = new ArrayList<>(Collections.singletonList(recurring));

    historyRows = new ArrayList<>();
    inProgressRows = new ArrayList<>();
    pendingManualRows = new ArrayList<>();

    givenCallerSession(CALLER_CLIENT_ID);

    stubCriteria(Process.class, criterions -> processRows);
    stubCriteria(ProcessRun.class,
        criterions -> criterions.contains("status=PRC") ? inProgressRows : historyRows);
    stubCriteria(ProcessRequest.class,
        criterions -> criterions.contains("active=true") ? recurringRows : pendingManualRows);

    scheduler = mock(OBScheduler.class);
    // A bare mock answers false here, which is the "node in standby" refusal — stub the ordinary
    // case explicitly so only the test that cares about standby sees it.
    when(scheduler.isSchedulingAllowed()).thenReturn(true);
    when(scheduler.getConnection()).thenReturn(mock(ConnectionProvider.class));
    // Logged so the ordering assertions can place it against the queries. A test that needs
    // `schedule` to fail re-stubs it with doThrow, which replaces this.
    doAnswer(invocation -> {
      callLog.add(EVENT_SCHEDULE);
      return null;
    }).when(scheduler).schedule(any(ProcessBundle.class));
    obSchedulerMock = mockStatic(OBScheduler.class);
    obSchedulerMock.when(OBScheduler::getInstance).thenReturn(scheduler);

    bundleArguments.clear();
    processBundleConstruction = mockConstruction(ProcessBundle.class, (bundle, context) -> {
      bundleArguments.add(context.arguments().toArray());
      // The production call chains `.init(connection)`; returning the mock keeps that chain valid
      // so `schedule(bundle)` receives the same object whose arguments were just captured.
      when(bundle.init(any())).thenReturn(bundle);
    });
  }

  @AfterEach
  void tearDown() {
    processBundleConstruction.close();
    obSchedulerMock.close();
  }

  // ── fixtures / helpers ────────────────────────────────────────────────────

  /** The ambient session: client, user, role and language the bundle must be built from. */
  private void givenCallerSession(String clientId) {
    when(client.getId()).thenReturn(clientId);
    when(user.getId()).thenReturn(CALLER_USER_ID);
    when(role.getId()).thenReturn(CALLER_ROLE_ID);
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn(CALLER_LANGUAGE);
    when(obContext.getLanguage()).thenReturn(language);
  }

  /** A client-admin role for the caller's own tenant — the ordinary allowed caller. */
  private void givenClientAdminCaller() {
    when(role.isClientAdmin()).thenReturn(true);
  }

  /** A role that is neither System Administrator nor a client-admin. */
  private void givenRestrictedCaller() {
    when(role.isClientAdmin()).thenReturn(false);
  }

  /**
   * Mints a fresh recording criteria mock on every {@code createCriteria(entityClass)} call, so the
   * two queries that share an entity class can be told apart by what they restrict on.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private <T extends BaseOBObject> void stubCriteria(Class<T> entityClass,
      Function<List<String>, List<? extends BaseOBObject>> resolver) {
    when(obDal.createCriteria((Class) entityClass)).thenAnswer(creation -> {
      OBCriteria<T> criteria = mock(OBCriteria.class);
      List<String> criterions = new ArrayList<>();
      recordingsFor(entityClass).add(criterions);
      when(criteria.add(any())).thenAnswer(added -> {
        // Typed explicitly: `String.valueOf(added.getArgument(0))` would infer the char[] overload
        // from the generic return type and blow up at runtime.
        Object criterion = added.getArgument(0, Object.class);
        criterions.add(String.valueOf(criterion));
        return criteria;
      });
      // Orders land in the same recording, prefixed. `setMaxResults(1)` only picks a stable row if
      // the query is ordered, so the ordering is part of what a criteria assertion has to see.
      when(criteria.addOrder(any())).thenAnswer(ordered -> {
        criterions.add("order:" + ordered.getArgument(0, Object.class));
        return criteria;
      });
      when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
      when(criteria.list()).thenAnswer(listed -> {
        logQuery(entityClass, criterions);
        return resolver.apply(criterions);
      });
      return criteria;
    });
  }

  private List<List<String>> recordingsFor(Class<?> entityClass) {
    if (entityClass == ProcessRun.class) {
      return runCriteria;
    }
    if (entityClass == ProcessRequest.class) {
      return requestCriteria;
    }
    return new ArrayList<>();
  }

  /**
   * Records a query on {@link #callLog} at the moment it is executed. Only the two {@link
   * ProcessRun} queries are distinguished, since they are the ones whose ordering relative to the
   * schedule call is an invariant; anything else is logged by entity name.
   */
  private void logQuery(Class<?> entityClass, List<String> criterions) {
    if (entityClass != ProcessRun.class) {
      callLog.add("query:" + entityClass.getSimpleName());
      return;
    }
    callLog.add(criterions.contains("status=PRC") ? EVENT_IN_PROGRESS_PROBE : EVENT_HISTORY_READ);
  }

  /** The criterions of the history query (the ProcessRun criteria that is NOT the PRC probe). */
  private List<String> historyQueryCriterions() {
    return runCriteria.stream()
        .filter(c -> !c.contains("status=PRC"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No history criteria was built"));
  }

  private List<String> inProgressQueryCriterions() {
    return runCriteria.stream()
        .filter(c -> c.contains("status=PRC"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No in-progress criteria was built"));
  }

  private List<String> pendingManualQueryCriterions() {
    return requestCriteria.stream()
        .filter(c -> !c.contains("active=true"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No pending-one-shot criteria was built"));
  }

  /** A history row. {@code channel} is the OWNING request's channel, or {@code null} for no request. */
  private ProcessRun run(String id, String status, String channel) {
    ProcessRun processRun = mock(ProcessRun.class);
    when(processRun.getId()).thenReturn(id);
    when(processRun.getStatus()).thenReturn(status);
    when(processRun.getStartTime()).thenReturn(new Date(1_700_000_000_000L));
    when(processRun.getEndTime()).thenReturn(new Date(1_700_000_001_000L));
    when(processRun.getDuration()).thenReturn("00:00:01");
    when(processRun.getLog()).thenReturn(LOG_SENTINEL);
    when(processRun.getReport()).thenReturn(LOG_SENTINEL);
    if (channel != null) {
      ProcessRequest owner = mock(ProcessRequest.class);
      when(owner.getChannel()).thenReturn(channel);
      when(processRun.getProcessRequest()).thenReturn(owner);
    }
    return processRun;
  }

  private JSONObject invoke() throws Exception {
    webhook.get(parameters, responseVars);
    return resultOf();
  }

  private JSONObject invokeTrigger() throws Exception {
    parameters.put("Action", "trigger");
    return invoke();
  }

  private JSONObject resultOf() throws Exception {
    String raw = responseVars.get("result");
    assertNotNull(raw, "Expected a 'result' entry, got: " + responseVars);
    return new JSONObject(raw);
  }

  // ── access gate ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A non-admin caller is refused, and nothing is read or scheduled")
  void restrictedRoleIsRefused() throws Exception {
    givenRestrictedCaller();

    JSONObject result = invoke();

    assertTrue(result.getBoolean("error"));
    assertEquals("notAuthorized", result.getString("reason"));
    verify(obDal, never()).createCriteria(any(Class.class));
    obSchedulerMock.verifyNoInteractions();
  }

  @Test
  @DisplayName("A caller with no role at all is refused")
  void missingRoleIsRefused() throws Exception {
    when(obContext.getRole()).thenReturn(null);

    JSONObject result = invoke();

    assertTrue(result.getBoolean("error"));
    assertEquals("notAuthorized", result.getString("reason"));
    verify(obDal, never()).createCriteria(any(Class.class));
  }

  @Test
  @DisplayName("A restricted role is refused even when it explicitly asks to trigger")
  void restrictedRoleCannotTrigger() throws Exception {
    givenRestrictedCaller();

    JSONObject result = invokeTrigger();

    assertEquals("notAuthorized", result.getString("reason"));
    assertFalse(result.has("triggered"));
    obSchedulerMock.verifyNoInteractions();
  }

  @Test
  @DisplayName("A client-admin caller is allowed and gets the status payload")
  void clientAdminCallerIsAllowed() throws Exception {
    givenClientAdminCaller();
    historyRows.add(run("run-1", "SUC", "Process Scheduler"));

    JSONObject result = invoke();

    assertFalse(result.getBoolean("error"));
    assertEquals(PROCESS_NAME, result.getString("processName"));
    assertTrue(result.getBoolean("scheduled"));
    assertEquals(1, result.getJSONArray("history").length());
  }

  @Test
  @DisplayName("The System Administrator role (id 0) is allowed to read")
  void systemAdministratorRoleIsAllowed() throws Exception {
    when(role.getId()).thenReturn("0");
    when(role.isClientAdmin()).thenReturn(false);

    JSONObject result = invoke();

    assertFalse(result.getBoolean("error"));
  }

  @Test
  @DisplayName("An instance with no accounting process answers notInstalled, not a crash")
  void notInstalledWhenTheProcessIsAbsent() throws Exception {
    givenClientAdminCaller();
    processRows.clear();

    JSONObject result = invoke();

    assertTrue(result.getBoolean("error"));
    assertEquals("notInstalled", result.getString("reason"));
  }

  // ── default action is READ ────────────────────────────────────────────────

  @Test
  @DisplayName("A bare GET with no Action reads only — it never reaches the scheduler")
  void bareGetSchedulesNothing() throws Exception {
    givenClientAdminCaller();

    JSONObject result = invoke();

    assertFalse(result.has("triggered"));
    obSchedulerMock.verifyNoInteractions();
    assertTrue(bundleArguments.isEmpty(), "No ProcessBundle may be constructed by a read");
  }

  @Test
  @DisplayName("An unrelated Action value is treated as a read, not as a trigger")
  void unknownActionIsTreatedAsARead() throws Exception {
    givenClientAdminCaller();
    parameters.put("Action", "refresh");

    JSONObject result = invoke();

    assertFalse(result.has("triggered"));
    obSchedulerMock.verifyNoInteractions();
  }

  @Test
  @DisplayName("Action matching is case-insensitive")
  void triggerActionIsCaseInsensitive() throws Exception {
    givenClientAdminCaller();
    parameters.put("Action", "TRIGGER");

    JSONObject result = invoke();

    assertTrue(result.getJSONObject("triggered").getBoolean("started"));
  }

  // ── tenant scope of the trigger ───────────────────────────────────────────

  @Test
  @DisplayName("The one-shot bundle is built from the CALLING client, not the recurring System row")
  void triggerBuildsTheBundleFromTheCallersOwnClient() throws Exception {
    givenClientAdminCaller();

    JSONObject result = invokeTrigger();

    assertTrue(result.getJSONObject("triggered").getBoolean("started"));
    assertEquals("started", result.getJSONObject("triggered").getString("reason"));
    assertEquals(1, bundleArguments.size(), "Exactly one one-shot bundle per trigger");

    Object[] args = bundleArguments.get(0);
    assertEquals(PROCESS_ID, args[0]);
    assertEquals(CALLER_CLIENT_ID, args[3], "Bundle client must be the caller's, never System '0'");
    assertEquals("0", args[4], "Org '0' means every organization WITHIN that one client");

    VariablesSecureApp vars = (VariablesSecureApp) args[1];
    assertEquals(CALLER_CLIENT_ID, vars.getClient());
    assertEquals(CALLER_USER_ID, vars.getUser());
    assertEquals(CALLER_ROLE_ID, vars.getRole());
    assertEquals("0", vars.getOrg());

    // The recurring row's own stored context is never consulted to build the bundle — reading it
    // is exactly how the pre-scope-change version widened a tenant's run to the whole instance.
    verify(recurring, never()).getOpenbravoContext();
  }

  @Test
  @DisplayName("The one-shot uses Channel.BACKGROUND, never DIRECT")
  void triggerUsesTheBackgroundChannel() throws Exception {
    givenClientAdminCaller();

    invokeTrigger();

    Object[] args = bundleArguments.get(0);
    assertSame(ProcessBundle.Channel.BACKGROUND, args[2],
        "DIRECT makes AcctServerProcess load its org from a pinstance that does not exist");
  }

  @Test
  @DisplayName("A System-context caller is refused with systemClientNotScopable and schedules nothing")
  void systemContextCallerCannotTrigger() throws Exception {
    givenClientAdminCaller();
    givenCallerSession(SYSTEM_CLIENT_ID);

    JSONObject result = invokeTrigger();

    JSONObject triggered = result.getJSONObject("triggered");
    assertFalse(triggered.getBoolean("started"));
    assertEquals("systemClientNotScopable", triggered.getString("reason"));
    verify(scheduler, never()).schedule(any(ProcessBundle.class));
    assertTrue(bundleArguments.isEmpty(), "No bundle may be built for a System-context caller");
  }

  // ── the recurring request is read-only ────────────────────────────────────

  @Test
  @DisplayName("Triggering never writes or mutates the recurring request row")
  void triggerNeverWritesTheRecurringRequest() throws Exception {
    givenClientAdminCaller();

    invokeTrigger();

    verify(scheduler).schedule(any(ProcessBundle.class));
    verify(obDal, never()).save(any());
    verify(obDal, never()).remove(any());
    verify(obDal, never()).flush();
    List<String> setters = new ArrayList<>();
    for (Invocation invocation : mockingDetails(recurring).getInvocations()) {
      String name = invocation.getMethod().getName();
      if (name.startsWith("set")) {
        setters.add(name);
      }
    }
    assertTrue(setters.isEmpty(), "The recurring row must stay untouched, but got: " + setters);
  }

  @Test
  @DisplayName("The recurring lookup excludes both one-shot channels, so it cannot pick up our own run")
  void recurringLookupExcludesOneShotChannels() throws Exception {
    givenClientAdminCaller();

    invoke();

    List<String> criterions = requestCriteria.get(0);
    assertTrue(criterions.contains("active=true"));
    assertTrue(criterions.contains("status=SCH"));
    assertTrue(criterions.contains("not channel in (Background, Direct)"),
        "Expected both one-shot channels excluded, got: " + criterions);
  }

  @Test
  @DisplayName("The recurring lookup is ordered, so setMaxResults(1) picks the same row every time")
  void recurringLookupIsDeterministic() throws Exception {
    // An unordered `setMaxResults(1)` returns whichever row the database felt like, so on an
    // instance with more than one scheduled request the page's next-run time — and the
    // `isSecurityBasedOnRole` the one-shot bundle inherits — could differ between two identical
    // requests. The id tie-breaker matters as much as the primary key: two requests can share a
    // next-execution instant, which would leave the choice non-deterministic again.
    givenClientAdminCaller();

    invoke();

    List<String> criterions = requestCriteria.get(0);
    assertTrue(criterions.contains("order:nextExecution asc"),
        "Expected the soonest request to win, got: " + criterions);
    assertTrue(criterions.contains("order:id asc"),
        "Expected an id tie-breaker for equal next-execution instants, got: " + criterions);
  }

  @Test
  @DisplayName("History is ordered recent-first")
  void historyIsOrderedRecentFirst() throws Exception {
    givenClientAdminCaller();

    invoke();

    assertTrue(historyQueryCriterions().contains("order:startTime desc"),
        "Got: " + historyQueryCriterions());
  }

  // ── history contents and scope ────────────────────────────────────────────

  @Test
  @DisplayName("The run log and report never reach the response")
  void historyNeverExposesTheLog() throws Exception {
    givenClientAdminCaller();
    historyRows.add(run("run-1", "SUC", "Process Scheduler"));
    historyRows.add(run("run-2", "ERR", "Background"));

    JSONObject result = invoke();

    JSONArray history = result.getJSONArray("history");
    assertEquals(2, history.length());
    for (int i = 0; i < history.length(); i++) {
      JSONObject row = history.getJSONObject(i);
      assertFalse(row.has("log"), "A history row must not carry the log");
      assertFalse(row.has("report"), "A history row must not carry the report");
    }
    assertFalse(responseVars.get("result").contains(LOG_SENTINEL),
        "The raw process output must not appear anywhere in the payload");
  }

  @Test
  @DisplayName("History is scoped to the caller's own client PLUS System, and to nobody else")
  void historyIsScopedToTheCallerPlusSystem() throws Exception {
    givenClientAdminCaller();

    invoke();

    String scope = "client.id in (" + CALLER_CLIENT_ID + ", " + SYSTEM_CLIENT_ID + ")";
    assertTrue(historyQueryCriterions().contains(scope),
        "Expected " + scope + ", got: " + historyQueryCriterions());
    assertFalse(historyQueryCriterions().toString().contains(OTHER_CLIENT_ID),
        "Another tenant's runs must never be in scope");
    assertTrue(historyQueryCriterions().contains("request.process.id=" + PROCESS_ID),
        "History is resolved at the PROCESS level, across every request of that process");
  }

  @Test
  @DisplayName("The in-progress probe shares the history scope — the shared System run blocks a manual one")
  void inProgressProbeIsScopedToTheCallerPlusSystem() throws Exception {
    givenClientAdminCaller();

    invoke();

    assertTrue(inProgressQueryCriterions()
            .contains("client.id in (" + CALLER_CLIENT_ID + ", " + SYSTEM_CLIENT_ID + ")"),
        "Got: " + inProgressQueryCriterions());
  }

  @Test
  @DisplayName("The pending-one-shot guard looks at the caller's client ONLY, never System")
  void pendingOneShotGuardIsScopedToTheCallerAlone() throws Exception {
    givenClientAdminCaller();

    invokeTrigger();

    List<String> criterions = pendingManualQueryCriterions();
    assertTrue(criterions.contains("client.id=" + CALLER_CLIENT_ID), "Got: " + criterions);
    assertTrue(criterions.contains("channel=Background"), "Got: " + criterions);
    assertFalse(criterions.contains("client.id in (" + CALLER_CLIENT_ID + ", " + SYSTEM_CLIENT_ID + ")"),
        "Another tenant's queued manual run must not disable this caller's button");
  }

  @Test
  @DisplayName("A run is labelled manual only when its own request used the Background channel")
  void runsAreLabelledManualByTheirOwnRequestChannel() throws Exception {
    givenClientAdminCaller();
    historyRows.add(run("run-manual", "SUC", "Background"));
    historyRows.add(run("run-auto", "SUC", "Process Scheduler"));
    historyRows.add(run("run-orphan", "SUC", null));

    JSONArray history = invoke().getJSONArray("history");

    assertTrue(history.getJSONObject(0).getBoolean("manual"));
    assertFalse(history.getJSONObject(1).getBoolean("manual"));
    assertFalse(history.getJSONObject(2).getBoolean("manual"),
        "A run whose request row is gone is automatic, not manual");
  }

  @Test
  @DisplayName("lastRun is the first history row, and null when the process has never run")
  void lastRunMirrorsTheMostRecentHistoryRow() throws Exception {
    givenClientAdminCaller();

    assertTrue(invoke().isNull("lastRun"));

    responseVars.clear();
    runCriteria.clear();
    requestCriteria.clear();
    historyRows.add(run("run-latest", "SUC", "Process Scheduler"));

    JSONObject result = invoke();
    assertEquals("run-latest", result.getJSONObject("lastRun").getString("id"));
  }

  // ── trigger refusals ──────────────────────────────────────────────────────

  @Test
  @DisplayName("A run already in progress refuses the trigger instead of stacking a second one")
  void triggerRefusesWhileARunIsInProgress() throws Exception {
    givenClientAdminCaller();
    inProgressRows.add(run("run-live", "PRC", "Process Scheduler"));

    JSONObject triggered = invokeTrigger().getJSONObject("triggered");

    assertFalse(triggered.getBoolean("started"));
    assertEquals("alreadyRunning", triggered.getString("reason"));
    verify(scheduler, never()).schedule(any(ProcessBundle.class));
  }

  @Test
  @DisplayName("A one-shot already queued for this client refuses the trigger")
  void triggerRefusesWhenAOneShotIsAlreadyQueued() throws Exception {
    givenClientAdminCaller();
    pendingManualRows.add(mock(ProcessRequest.class));

    JSONObject triggered = invokeTrigger().getJSONObject("triggered");

    assertEquals("alreadyRunning", triggered.getString("reason"));
    verify(scheduler, never()).schedule(any(ProcessBundle.class));
  }

  @Test
  @DisplayName("A scheduler in standby is reported, never claimed as a started run")
  void triggerReportsAStandbyScheduler() throws Exception {
    givenClientAdminCaller();
    when(scheduler.isSchedulingAllowed()).thenReturn(false);

    JSONObject triggered = invokeTrigger().getJSONObject("triggered");

    assertFalse(triggered.getBoolean("started"));
    assertEquals("schedulerUnavailable", triggered.getString("reason"));
    verify(scheduler, never()).schedule(any(ProcessBundle.class));
  }

  @Test
  @DisplayName("An instance with no recurring request refuses the trigger with notScheduled")
  void triggerRefusesWhenNothingIsScheduled() throws Exception {
    givenClientAdminCaller();
    recurringRows.clear();

    JSONObject result = invokeTrigger();

    assertFalse(result.getBoolean("scheduled"));
    assertEquals("notScheduled", result.getJSONObject("triggered").getString("reason"));
    obSchedulerMock.verifyNoInteractions();
  }

  @Test
  @DisplayName("A scheduler failure is reported as scheduleFailed, not thrown at the bridge")
  void triggerReportsASchedulerFailure() throws Exception {
    givenClientAdminCaller();
    org.mockito.Mockito.doThrow(new org.quartz.SchedulerException("quartz is down"))
        .when(scheduler).schedule(any(ProcessBundle.class));

    JSONObject result = invokeTrigger();

    assertFalse(result.getBoolean("error"), "The status card must still render");
    assertEquals("scheduleFailed", result.getJSONObject("triggered").getString("reason"));
    assertFalse(responseVars.containsKey("error"));
  }

  /**
   * The W1 invariant, stated as an ORDER rather than as a count.
   *
   * <p>A trigger response must describe the world AFTER the run was scheduled. The endpoint reads
   * the status exactly ONCE, and that read comes after {@code OBScheduler.schedule(...)} — so
   * {@code history}, {@code lastRun} and {@code running} all describe the same instant, and that
   * instant is a post-trigger one.</p>
   *
   * <p>This assertion used to expect TWO history reads, which encoded the original
   * read-then-trigger-then-re-read shape: the status was captured BEFORE the run was scheduled and
   * only {@code history} was patched afterwards. That left {@code running} necessarily false in
   * every successful trigger response — the trigger refuses outright when a run is already in
   * progress — while {@code lastRun} came from the stale read and {@code history} from the fresh
   * one, three fields disagreeing with each other. The frontend keys its poll off {@code running},
   * so the poll never started and the page claimed a run had begun while nothing arrived. Commit
   * {@code c0e27031} collapsed that into one post-trigger read; this test was not updated with it,
   * and only a real Gradle run caught it.</p>
   *
   * <p>Asserting the ORDER, not just the count, is deliberate: a bare "exactly one history read"
   * would still pass if someone moved the read back in front of the trigger and dropped the
   * re-read, which is precisely the defect.</p>
   */
  @Test
  @DisplayName("A successful trigger reads the history AFTER scheduling, exactly once")
  void triggerReadsTheHistoryAfterScheduling() throws Exception {
    givenClientAdminCaller();
    historyRows.add(run("run-1", "SUC", "Process Scheduler"));

    JSONObject result = invokeTrigger();

    assertTrue(result.getJSONObject("triggered").getBoolean("started"));
    assertEquals(1, result.getJSONArray("history").length());

    int scheduledAt = callLog.indexOf(EVENT_SCHEDULE);
    int historyReadAt = callLog.indexOf(EVENT_HISTORY_READ);
    assertTrue(scheduledAt >= 0, "The run was never scheduled. Call log: " + callLog);
    assertTrue(historyReadAt > scheduledAt,
        "The history must be read AFTER the run is scheduled, not before. Call log: " + callLog);
    // Exactly one read, so no stale pre-trigger snapshot survives anywhere in the response.
    assertEquals(1, callLog.stream().filter(EVENT_HISTORY_READ::equals).count(),
        "Call log: " + callLog);
  }

  @Test
  @DisplayName("The in-progress flag is also captured after scheduling, so it agrees with history")
  void triggerCapturesRunningAfterScheduling() throws Exception {
    givenClientAdminCaller();

    invokeTrigger();

    // `running` comes from the LAST in-progress probe, the one inside buildStatus. The earlier
    // probe is the trigger's own already-running guard, which runs before the schedule by
    // necessity. What must not happen is the reported `running` being taken from that earlier one.
    int scheduledAt = callLog.indexOf(EVENT_SCHEDULE);
    int lastProbeAt = callLog.lastIndexOf(EVENT_IN_PROGRESS_PROBE);
    assertTrue(lastProbeAt > scheduledAt,
        "The reported running flag must be probed after scheduling. Call log: " + callLog);
  }

  @Test
  @DisplayName("A plain read schedules nothing and still reads the history exactly once")
  void readOnlyRequestReadsTheHistoryOnce() throws Exception {
    givenClientAdminCaller();
    historyRows.add(run("run-1", "SUC", "Process Scheduler"));

    invoke();

    assertFalse(callLog.contains(EVENT_SCHEDULE), "Call log: " + callLog);
    assertEquals(1, callLog.stream().filter(EVENT_HISTORY_READ::equals).count(),
        "Call log: " + callLog);
  }

  // ── history limit ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("An unparseable or out-of-range Limit falls back instead of failing")
  void historyLimitIsClampedRatherThanRejected() throws Exception {
    givenClientAdminCaller();
    parameters.put("Limit", "not-a-number");

    assertFalse(invoke().getBoolean("error"));

    responseVars.clear();
    parameters.put("Limit", "-5");
    assertFalse(invoke().getBoolean("error"));

    responseVars.clear();
    parameters.put("Limit", "10000");
    assertFalse(invoke().getBoolean("error"));
  }
}
