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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessContext;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.ClientOutcome;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.RealignReport;

/**
 * Unit tests for the corrective half of ETP-5370,
 * {@link OnboardingCostingScheduleService#realignCadence(String)} — the sweep that brings already
 * onboarded tenants to the invariant the preventive half provisions for new ones: exactly ONE active
 * scheduled {@code CostingBackground} request per client, running every 30 seconds.
 *
 * <p><b>How the scheduler is kept out of reach.</b> The two seams that would touch a live Quartz
 * scheduler — {@code rearm(String)} and {@code schedulingAllowed()} — are package-visible precisely
 * so a test can override them, which {@link TestableService} does. Every assertion about re-arming is
 * therefore made against a recorded list of request ids rather than against {@link OBScheduler},
 * and a failing tenant is simulated by making one re-arm throw, which is exactly how the production
 * method reports failure.
 *
 * <p><b>Why the criteria mock replays filtering and ordering.</b> Two of the invariants under test
 * are properties of the QUERY, not of the Java that follows it: the winner is "the most recently
 * created active {@code SCH} request" and {@code clientIdFilter} really narrows the sweep. A mocked
 * criteria that returned a hand-ordered fixture list would assert the fixture, not the code — so the
 * criteria mock records every {@link org.hibernate.criterion.Criterion} and every {@code addOrderBy}
 * and resolves {@code list()} by applying them to the fixture rows. Flip the sort to ascending or
 * drop the client restriction in the service and these tests fail, which is the point.
 *
 * <p><b>Why a native-query seam is stubbed here.</b> {@code NEXT_FIRE_TIME} is scheduler bookkeeping
 * and is deliberately not mapped on the {@code ProcessRequest} entity, so the service clears it with
 * a native {@code UPDATE} through {@code OBDal.getSession()}. That path is mocked end to end
 * (session → {@link NativeQuery} → {@code executeUpdate}) both so the routine can run at all and so
 * the clear — the one defect live verification caught — can be asserted, ORDER included.
 *
 * <p>The scheduling-field shape written on a FRESH request, and the cadence constants themselves,
 * are covered by {@link OnboardingCostingScheduleServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingCostingScheduleRealignTest {

  private static final String CLIENT_A_ID = "client-a";
  private static final String CLIENT_B_ID = "client-b";
  private static final String CLIENT_A_NAME = "Tenant A";
  private static final String CLIENT_B_NAME = "Tenant B";

  private static final String REQUEST_A_OLD_ID = "req-a-old";
  private static final String REQUEST_A_NEW_ID = "req-a-new";
  private static final String REQUEST_B_ID = "req-b";

  private static final String OB_CONTEXT = "ob-context-string";

  /** Timing option "Schedule" — what a recurring request must carry. */
  private static final String TIMING_SCHEDULED = "S";
  /** {@code Frequency.SECONDLY} — the post-ETP-5370 cadence. */
  private static final String FREQUENCY_SECONDLY = "1";
  /** {@code Frequency.MINUTELY} — the legacy 5-minute shape this sweep has to replace. */
  private static final String FREQUENCY_MINUTELY = "2";
  private static final Long THIRTY_SECONDS = 30L;
  private static final Long FIVE_MINUTES = 5L;
  private static final String STATUS_SCHEDULED = "SCH";
  private static final String STATUS_UNSCHEDULED = "UNS";

  private static final String REARM_FAILURE = "quartz is unhappy";

  /** The scheduler-bookkeeping column the native UPDATE has to clear; see {@code clearNextFireTime}. */
  private static final String NEXT_FIRE_TIME_COLUMN = "next_fire_time";
  private static final String REQUEST_ID_PARAMETER = "id";

  private static final String EVENT_CLEAR_NEXT_FIRE = "clearNextFireTime";
  private static final String EVENT_COMMIT = "commitAndClose";
  private static final String EVENT_REARM = "rearm";

  /** Prefix of the {@code Restrictions.eq("client.id", ...)} recording, as Hibernate renders it. */
  private static final String CLIENT_ID_CRITERION_PREFIX = ProcessRequest.PROPERTY_CLIENT + ".id=";

  @Mock
  private OBDal dal;
  @Mock
  private OBCriteria<ProcessRequest> requestCriteria;
  @Mock
  private Session session;
  @Mock
  private NativeQuery<Object> nativeQuery;
  @Mock
  private OBScheduler scheduler;
  @Mock
  private ProcessContext processContext;
  @Mock
  private Process costingProcess;
  @Mock
  private Client clientA;
  @Mock
  private Client clientB;
  @Mock
  private ProcessRequest requestAOld;
  @Mock
  private ProcessRequest requestANew;
  @Mock
  private ProcessRequest requestB;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<OBScheduler> obSchedulerStatic;
  private MockedStatic<ProcessContext> processContextStatic;

  private TestableService service;

  /** Fixture rows the mocked criteria draws from, in insertion order. */
  private final List<ProcessRequest> rows = new ArrayList<>();
  /** {@code toString()} of every criterion the service added to the criteria. */
  private final List<String> criterions = new ArrayList<>();
  /** Every {@code addOrderBy(property, ascending)} the service asked for, in order. */
  private final List<OrderBy> orderBys = new ArrayList<>();

  /**
   * The three steps whose ORDER is load-bearing, appended as they really happen. Some of what this
   * routine guarantees cannot be expressed as a count or a value: the {@code NEXT_FIRE_TIME} clear
   * is worthless if it lands after the commit, and the re-arm reads a stale row if it runs before
   * it. See {@link #testRealignCadenceClearsTheStaleNextFireTimeInsideTheSameTransaction}.
   */
  private final List<String> callLog = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    obContextStatic = mockStatic(OBContext.class);
    obSchedulerStatic = mockStatic(OBScheduler.class);
    obSchedulerStatic.when(OBScheduler::getInstance).thenReturn(scheduler);
    processContextStatic = mockStatic(ProcessContext.class);
    processContextStatic.when(() -> ProcessContext.newInstance(OB_CONTEXT))
        .thenReturn(processContext);

    when(clientA.getId()).thenReturn(CLIENT_A_ID);
    when(clientA.getName()).thenReturn(CLIENT_A_NAME);
    when(clientB.getId()).thenReturn(CLIENT_B_ID);
    when(clientB.getName()).thenReturn(CLIENT_B_NAME);
    when(dal.get(Client.class, CLIENT_A_ID)).thenReturn(clientA);
    when(dal.get(Client.class, CLIENT_B_ID)).thenReturn(clientB);

    when(dal.createCriteria(ProcessRequest.class)).thenReturn(requestCriteria);
    when(requestCriteria.add(any())).thenAnswer(invocation -> {
      Object criterion = invocation.getArgument(0);
      criterions.add(String.valueOf(criterion));
      return requestCriteria;
    });
    when(requestCriteria.addOrderBy(any(), anyBoolean())).thenAnswer(invocation -> {
      String property = invocation.getArgument(0);
      boolean ascending = invocation.getArgument(1);
      orderBys.add(new OrderBy(property, ascending));
      return requestCriteria;
    });
    when(requestCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(requestCriteria);
    when(requestCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(requestCriteria);
    when(requestCriteria.list()).thenAnswer(invocation -> queryResult());

    // NEXT_FIRE_TIME is not mapped on the ProcessRequest entity, so the service clears it with a
    // native UPDATE — this is the seam that stands in for it.
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    when(nativeQuery.executeUpdate()).thenAnswer(invocation -> {
      callLog.add(EVENT_CLEAR_NEXT_FIRE);
      return 1;
    });
    doAnswer(invocation -> {
      callLog.add(EVENT_COMMIT);
      return null;
    }).when(dal).commitAndClose();

    service = new TestableService(costingProcess, callLog);
  }

  @AfterEach
  void tearDown() {
    processContextStatic.close();
    obSchedulerStatic.close();
    obContextStatic.close();
    obDalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ─── the single-row cases ─────────────────────────────────────────────────────

  /**
   * The base corrective case: a tenant left on the pre-ETP-5370 5-minute shape is rewritten to
   * {@code '1'} / 30s, and {@code MINUTELY_INTERVAL} is CLEARED rather than left behind. A leftover
   * minutely value is inert (the scheduler reads only the column matching {@code FREQUENCY}) but it
   * produces a row that matches no hand-made one, which is the expensive part to diagnose later.
   */
  @Test
  void testRealignCadenceRewritesAFiveMinuteRequestToThirtySeconds() {
    RequestState winner = givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L,
        FREQUENCY_MINUTELY, null, FIVE_MINUTES);

    RealignReport report = service.realignCadence(null);

    assertTrue(report.isSchedulerAvailable());
    assertEquals(1, report.getOutcomes().size());
    ClientOutcome outcome = report.getOutcomes().get(0);
    assertEquals(CLIENT_A_ID, outcome.getClientId());
    assertEquals(CLIENT_A_NAME, outcome.getClientName());
    assertEquals(ClientOutcome.REALIGNED, outcome.getStatus());
    assertEquals(REQUEST_A_NEW_ID, outcome.getRequestId());
    assertEquals(0, outcome.getDeactivated());
    assertNull(outcome.getDetail());

    assertEquals(TIMING_SCHEDULED, winner.timing);
    assertEquals(FREQUENCY_SECONDLY, winner.frequency);
    assertEquals(THIRTY_SECONDS, winner.intervalInSeconds);
    assertNull(winner.intervalInMinutes,
        "the legacy minutely interval must be cleared, not left behind");

    verify(dal).save(requestANew);
    verify(dal).flush();
    verify(dal).commitAndClose();
    assertEquals(List.of(REQUEST_A_NEW_ID), service.rearmed);
  }

  /**
   * A row that already reads {@code '1'} / 30 / no minutely interval keeps its CADENCE columns
   * untouched — but it is still restarted and re-armed. That is the whole design point of the
   * method: the row is not evidence about the live Quartz trigger (an {@code UPDATE} never reached
   * an already-armed one), so the only thing that actually guarantees the invariant is re-arming it
   * from a start boundary of NOW.
   *
   * <p>Note what is deliberately NOT asserted any more: that the row is never saved. Since the
   * {@code NEXT_FIRE_TIME} finding (see {@link
   * #testRealignCadenceClearsTheStaleNextFireTimeInsideTheSameTransaction}) the start boundary is
   * rewritten on every pass, already-correct included, so the row IS saved. What still has to hold
   * is that the cadence itself is not needlessly churned.
   */
  @Test
  void testRealignCadenceRestartsAnAlreadyCorrectRequestWithoutRewritingItsCadence() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_SECONDLY,
        THIRTY_SECONDS, null);

    RealignReport report = service.realignCadence(null);

    assertEquals(1, report.getOutcomes().size());
    ClientOutcome outcome = report.getOutcomes().get(0);
    assertEquals(ClientOutcome.ALREADY_CORRECT, outcome.getStatus());
    assertEquals(0, outcome.getDeactivated());

    verify(requestANew, never()).setTiming(any());
    verify(requestANew, never()).setFrequency(any());
    verify(requestANew, never()).setIntervalInSeconds(any());
    verify(requestANew, never()).setIntervalInMinutes(any());

    verify(requestANew).setStartDate(any());
    verify(requestANew).setStartTime(any());
    verify(dal).save(requestANew);

    assertEquals(List.of(REQUEST_A_NEW_ID), service.rearmed,
        "an already-correct row must still be re-armed — the row says nothing about the trigger");
  }

  /**
   * The regression test for the one defect live verification caught and no earlier unit test could:
   * {@code ScheduledTriggerGenerator#getBuilder} starts a rebuilt trigger AT {@code NEXT_FIRE_TIME}
   * when that column is populated, instead of at the request's start boundary. Left in place, the
   * column still holds the OLD trigger's next fire, so the re-armed 30-second trigger sits dormant
   * until the old cadence's next tick (measured: realigned at 19:55:02, first run 19:58:55). It was
   * only harmless because the old cadence was 5 minutes; a daily one would have idled the job a day.
   *
   * <p>ORDER is the functional part here, which is why it is asserted twice over — a clear that
   * lands after the commit is in a different transaction and does nothing, and a re-arm that runs
   * before the commit reads the pre-realignment row through the scheduler's own JDBC connection.
   */
  @Test
  void testRealignCadenceClearsTheStaleNextFireTimeInsideTheSameTransaction() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_MINUTELY, null,
        FIVE_MINUTES);

    service.realignCadence(null);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    String statement = sql.getValue().toLowerCase(Locale.ROOT);
    assertTrue(statement.contains(NEXT_FIRE_TIME_COLUMN),
        "the UPDATE must target next_fire_time, got: " + sql.getValue());
    assertTrue(statement.contains("null"),
        "the column must be nulled, not set to a value, got: " + sql.getValue());
    verify(nativeQuery).setParameter(REQUEST_ID_PARAMETER, REQUEST_A_NEW_ID);
    verify(nativeQuery).executeUpdate();

    InOrder ordered = inOrder(dal, nativeQuery);
    ordered.verify(dal).flush();
    ordered.verify(nativeQuery).executeUpdate();
    ordered.verify(dal).commitAndClose();

    assertEquals(List.of(EVENT_CLEAR_NEXT_FIRE, EVENT_COMMIT, EVENT_REARM), callLog,
        "the clear must be committed with the cadence, and only then may the trigger be re-armed");
  }

  // ─── the duplicate-rows case ──────────────────────────────────────────────────

  /**
   * Two active {@code SCH} requests for one tenant: the most recently created one wins (it is the
   * one this service or the ETP-5245 data-fix provisioned with a resolved {@code ob_context}), the
   * older one is unscheduled from the live scheduler and marked {@code UNS} + inactive. The loser is
   * never deleted — the history has to stay auditable.
   */
  @Test
  void testRealignCadenceKeepsTheNewestRequestAndDeactivatesTheExtras() {
    RequestState loser = givenRequest(requestAOld, REQUEST_A_OLD_ID, clientA, 1_000L,
        FREQUENCY_MINUTELY, null, FIVE_MINUTES);
    RequestState winner = givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L,
        FREQUENCY_MINUTELY, null, FIVE_MINUTES);

    RealignReport report = service.realignCadence(null);

    assertEquals(1, report.getOutcomes().size());
    ClientOutcome outcome = report.getOutcomes().get(0);
    assertEquals(ClientOutcome.REALIGNED, outcome.getStatus());
    assertEquals(REQUEST_A_NEW_ID, outcome.getRequestId(), "the newest request must win");
    assertEquals(1, outcome.getDeactivated());

    assertEquals(FREQUENCY_SECONDLY, winner.frequency);
    assertEquals(THIRTY_SECONDS, winner.intervalInSeconds);
    assertNull(winner.intervalInMinutes);

    assertEquals(STATUS_UNSCHEDULED, loser.status);
    assertEquals(Boolean.FALSE, loser.active, "the redundant request must be deactivated");
    assertEquals(FREQUENCY_MINUTELY, loser.frequency,
        "the loser's cadence is irrelevant once it is unscheduled — it must not be rewritten");

    verify(scheduler).unschedule(REQUEST_A_OLD_ID, processContext);
    verify(dal, never()).remove(any());
    assertEquals(List.of(REQUEST_A_NEW_ID), service.rearmed,
        "only the surviving request is re-armed");
  }

  // ─── the guard rails ──────────────────────────────────────────────────────────

  /**
   * On a node where scheduling is not allowed (a standby), {@code schedule}/{@code reschedule}
   * silently no-op, so reporting success would be a lie. Nothing is queried, written or re-armed.
   */
  @Test
  void testRealignCadenceDoesNothingWhenSchedulingIsNotAllowed() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_MINUTELY, null,
        FIVE_MINUTES);
    service.schedulingAllowed = false;

    RealignReport report = service.realignCadence(null);

    assertFalse(report.isSchedulerAvailable());
    assertTrue(report.getOutcomes().isEmpty());
    assertTrue(service.rearmed.isEmpty());
    assertTrue(criterions.isEmpty(), "no query may even be built on a node that cannot schedule");
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
    verify(dal, never()).commitAndClose();
    verify(requestANew, never()).setFrequency(any());
    assertTrue(callLog.isEmpty(), "no clear, no commit and no re-arm may happen: " + callLog);
  }

  /** A partially updated AD without the core costing process is reported empty, not fatal. */
  @Test
  void testRealignCadenceReturnsAnEmptyReportWhenTheCostingProcessIsMissing() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_MINUTELY, null,
        FIVE_MINUTES);
    service.process = null;

    RealignReport report = service.realignCadence(null);

    assertTrue(report.isSchedulerAvailable());
    assertTrue(report.getOutcomes().isEmpty());
    assertTrue(service.rearmed.isEmpty());
    verify(dal, never()).save(any());
  }

  /**
   * The sweep is cross-tenant by design and must see only real schedules: active, status
   * {@code SCH} (a {@code COM} row is execution history, not a schedule), and both readable-scope
   * filters switched off — without that escape the sweep would silently cover only the calling
   * session's own client, which is the failure mode that looks like success.
   */
  @Test
  void testRealignCadenceQueriesOnlyActiveScheduledRequestsOfEveryTenant() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_MINUTELY, null,
        FIVE_MINUTES);

    service.realignCadence(null);

    assertTrue(criterions.contains(ProcessRequest.PROPERTY_ACTIVE + "=true"),
        "expected an active=true restriction, got: " + criterions);
    assertTrue(criterions.contains(ProcessRequest.PROPERTY_STATUS + "=" + STATUS_SCHEDULED),
        "expected a status=SCH restriction, got: " + criterions);
    assertTrue(criterions.stream().noneMatch(c -> c.startsWith(CLIENT_ID_CRITERION_PREFIX)),
        "an unfiltered sweep must not restrict the client, got: " + criterions);
    verify(requestCriteria).setFilterOnReadableClients(false);
    verify(requestCriteria).setFilterOnReadableOrganization(false);
  }

  // ─── multi-tenant behaviour ───────────────────────────────────────────────────

  /**
   * One tenant blowing up must never abort the sweep: it is recorded as {@code FAILED}, its
   * transaction is rolled back so the next tenant starts clean, and the remaining tenants are still
   * realigned. A sweep that stopped at the first bad tenant would leave the rest silently unfixed.
   */
  @Test
  void testRealignCadenceContinuesAfterOneClientFails() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_MINUTELY, null,
        FIVE_MINUTES);
    RequestState survivor = givenRequest(requestB, REQUEST_B_ID, clientB, 1_000L,
        FREQUENCY_MINUTELY, null, FIVE_MINUTES);
    service.rearmFailureId = REQUEST_A_NEW_ID;

    RealignReport report = service.realignCadence(null);

    assertEquals(2, report.getOutcomes().size());
    ClientOutcome failed = outcomeFor(report, CLIENT_A_ID);
    assertEquals(ClientOutcome.FAILED, failed.getStatus());
    assertEquals(REQUEST_A_NEW_ID, failed.getRequestId());
    assertEquals(0, failed.getDeactivated());
    assertTrue(failed.getDetail() != null && failed.getDetail().contains(REARM_FAILURE),
        "the failure detail must carry the underlying cause, got: " + failed.getDetail());

    ClientOutcome succeeded = outcomeFor(report, CLIENT_B_ID);
    assertEquals(ClientOutcome.REALIGNED, succeeded.getStatus());
    assertEquals(REQUEST_B_ID, succeeded.getRequestId());
    assertEquals(FREQUENCY_SECONDLY, survivor.frequency);
    assertEquals(THIRTY_SECONDS, survivor.intervalInSeconds);

    verify(dal).rollbackAndClose();
    assertEquals(List.of(REQUEST_A_NEW_ID, REQUEST_B_ID), service.rearmed);
  }

  /** A single-client run touches that client only — the other tenant's row is left exactly as it was. */
  @Test
  void testRealignCadenceOnlyTouchesTheRequestedClientWhenFiltered() {
    givenRequest(requestANew, REQUEST_A_NEW_ID, clientA, 2_000L, FREQUENCY_MINUTELY, null,
        FIVE_MINUTES);
    RequestState untouched = givenRequest(requestB, REQUEST_B_ID, clientB, 1_000L,
        FREQUENCY_MINUTELY, null, FIVE_MINUTES);

    RealignReport report = service.realignCadence(CLIENT_A_ID);

    assertEquals(1, report.getOutcomes().size());
    assertEquals(CLIENT_A_ID, report.getOutcomes().get(0).getClientId());
    assertTrue(criterions.contains(CLIENT_ID_CRITERION_PREFIX + CLIENT_A_ID),
        "expected the client restriction, got: " + criterions);

    assertEquals(List.of(REQUEST_A_NEW_ID), service.rearmed);
    verify(requestB, never()).setFrequency(any());
    verify(requestB, never()).setIntervalInSeconds(any());
    verify(requestB, never()).setActive(any());
    verify(dal, never()).save(requestB);
    assertEquals(FREQUENCY_MINUTELY, untouched.frequency);
    assertEquals(FIVE_MINUTES, untouched.intervalInMinutes);
  }

  // ─── fixtures ─────────────────────────────────────────────────────────────────

  private static ClientOutcome outcomeFor(RealignReport report, String clientId) {
    return report.getOutcomes().stream()
        .filter(outcome -> clientId.equals(outcome.getClientId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no outcome reported for client " + clientId));
  }

  /**
   * Registers one fixture row: a {@link ProcessRequest} mock backed by a mutable {@link RequestState}
   * so the setters the service calls are observable, resolvable by id through the mocked DAL, and
   * visible to the mocked criteria.
   */
  private RequestState givenRequest(ProcessRequest request, String id, Client client,
      long creationMillis, String frequency, Long intervalInSeconds, Long intervalInMinutes) {
    RequestState state = new RequestState();
    state.timing = TIMING_SCHEDULED;
    state.frequency = frequency;
    state.intervalInSeconds = intervalInSeconds;
    state.intervalInMinutes = intervalInMinutes;
    state.status = STATUS_SCHEDULED;
    state.active = Boolean.TRUE;

    when(request.getId()).thenReturn(id);
    when(request.getClient()).thenReturn(client);
    when(request.getCreationDate()).thenReturn(new Date(creationMillis));
    when(request.getOpenbravoContext()).thenReturn(OB_CONTEXT);
    when(request.getTiming()).thenAnswer(invocation -> state.timing);
    when(request.getFrequency()).thenAnswer(invocation -> state.frequency);
    when(request.getIntervalInSeconds()).thenAnswer(invocation -> state.intervalInSeconds);
    when(request.getIntervalInMinutes()).thenAnswer(invocation -> state.intervalInMinutes);
    when(request.getStatus()).thenAnswer(invocation -> state.status);
    when(request.isActive()).thenAnswer(invocation -> state.active);
    doAnswer(invocation -> {
      state.timing = invocation.getArgument(0);
      return null;
    }).when(request).setTiming(any());
    doAnswer(invocation -> {
      state.frequency = invocation.getArgument(0);
      return null;
    }).when(request).setFrequency(any());
    doAnswer(invocation -> {
      state.intervalInSeconds = invocation.getArgument(0);
      return null;
    }).when(request).setIntervalInSeconds(any());
    doAnswer(invocation -> {
      state.intervalInMinutes = invocation.getArgument(0);
      return null;
    }).when(request).setIntervalInMinutes(any());
    doAnswer(invocation -> {
      state.status = invocation.getArgument(0);
      return null;
    }).when(request).setStatus(any());
    doAnswer(invocation -> {
      state.active = invocation.getArgument(0);
      return null;
    }).when(request).setActive(any());

    when(dal.get(ProcessRequest.class, id)).thenReturn(request);
    rows.add(request);
    return state;
  }

  /**
   * Replays the recorded client restriction and the recorded {@code addOrderBy} calls over the
   * fixture rows, so the winner-selection and the client filter are properties of the service's own
   * query rather than of the fixture's insertion order.
   */
  private List<ProcessRequest> queryResult() {
    List<ProcessRequest> result = new ArrayList<>(rows);
    for (String criterion : criterions) {
      if (criterion.startsWith(CLIENT_ID_CRITERION_PREFIX)) {
        String wanted = criterion.substring(CLIENT_ID_CRITERION_PREFIX.length());
        result.removeIf(row -> !wanted.equals(row.getClient().getId()));
      }
    }
    Comparator<ProcessRequest> ordering = recordedOrdering();
    if (ordering != null) {
      result.sort(ordering);
    }
    return result;
  }

  /** The recorded order-by chain, first recorded property most significant; null when none. */
  private Comparator<ProcessRequest> recordedOrdering() {
    Comparator<ProcessRequest> chain = null;
    for (OrderBy orderBy : orderBys) {
      Comparator<ProcessRequest> next;
      if (ProcessRequest.PROPERTY_CREATIONDATE.equals(orderBy.property)) {
        next = Comparator.<ProcessRequest, Date>comparing(ProcessRequest::getCreationDate);
      } else {
        next = Comparator.<ProcessRequest, String>comparing(ProcessRequest::getId);
      }
      if (!orderBy.ascending) {
        next = next.reversed();
      }
      chain = chain == null ? next : chain.thenComparing(next);
    }
    return chain;
  }

  /** One recorded {@code addOrderBy(property, ascending)} call. */
  private static final class OrderBy {
    private final String property;
    private final boolean ascending;

    private OrderBy(String property, boolean ascending) {
      this.property = property;
      this.ascending = ascending;
    }
  }

  /** The mutable columns of one fixture request, written by the service through its mock. */
  private static final class RequestState {
    private String timing;
    private String frequency;
    private Long intervalInSeconds;
    private Long intervalInMinutes;
    private String status;
    private Boolean active;
  }

  /**
   * Keeps the tests off the real scheduler: {@code rearm} and {@code schedulingAllowed} are
   * package-visible on the service exactly so this override is possible, and {@code resolveProcess}
   * is stubbed here so no Process criteria has to be mocked.
   */
  private static final class TestableService extends OnboardingCostingScheduleService {
    private final List<String> rearmed = new ArrayList<>();
    private final List<String> callLog;
    private Process process;
    private boolean schedulingAllowed = true;
    private String rearmFailureId;

    private TestableService(Process process, List<String> callLog) {
      this.process = process;
      this.callLog = callLog;
    }

    @Override
    protected Process resolveProcess(String searchKey) {
      return process;
    }

    @Override
    boolean schedulingAllowed() {
      return schedulingAllowed;
    }

    @Override
    void rearm(String requestId) {
      rearmed.add(requestId);
      callLog.add(EVENT_REARM);
      if (requestId.equals(rearmFailureId)) {
        throw new IllegalStateException(REARM_FAILURE);
      }
    }
  }
}
