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

import static com.etendoerp.go.testsupport.ProcessRequestTestFixture.propertyBackedRequest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import com.etendoerp.go.payment.TestLogCapture;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

/**
 * Unit tests for {@link OnboardingCostingScheduleService}.
 *
 * <p>Testing convention: the protected seams
 * ({@code resolveProcess}, {@code findExistingRequest}, {@code buildObContext}) are stubbed via a
 * Mockito spy so the tests assert only this service's own orchestration — the skip / idempotent
 * branches, the scheduling field values written on a fresh request, and the best-effort error
 * swallowing of {@link OnboardingCostingScheduleService#activateSchedule(String)}.
 *
 * <p>The field assertions are the point of this class, not ceremony. Timing {@code 'S'} plus
 * frequency {@code '1'} is the {@code "S1"} key core's {@code TriggerProvider} maps to
 * {@code repeatSecondlyForever}, and it reads {@code SECONDLY_INTERVAL} — not the minutely or daily
 * interval — for the period. Getting any one of the three wrong yields a row that looks scheduled in
 * the Process Request window and either never fires or fires on the wrong cadence, which is exactly
 * the class of bug that leaves a tenant's costs uncalculated with nothing visibly broken.
 *
 * <p>ETP-5370 lowered the cadence from 5 minutes ({@code '2'} + {@code MINUTELY_INTERVAL=5}) to 30
 * seconds, which swapped which interval column has to be written and which ones have to stay empty.
 * That inversion is what the assertions below pin down; the corrective half of the same ticket
 * ({@code realignCadence}) is covered by {@link OnboardingCostingScheduleRealignTest}.
 *
 * @covers com.etendoerp.go.onboarding.OnboardingCostingScheduleService
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class OnboardingCostingScheduleServiceTest {

  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String ADMIN_USER_ID = "user-1";
  private static final String ADMIN_ROLE_ID = "role-1";
  private static final String EXISTING_REQUEST_ID = "req-existing";
  private static final String NEW_REQUEST_ID = "req-new";
  private static final String OB_CONTEXT = "ob-context-string";
  private static final String ACTIVATE_REQUEST_ID = "req-activate";

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /** An AD without the core costing process (partially updated) is skipped, not fatal. */
  @Test
  public void scheduleCostingBackgroundSkipsWhenProcessNotFound() {
    OnboardingCostingScheduleService service = spy(new OnboardingCostingScheduleService());
    doReturn(null).when(service).resolveProcess(OnboardingCostingScheduleService.COSTING_PROCESS_KEY);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      String result = service.scheduleCostingBackground(CLIENT_ID, ORG_ID, ADMIN_USER_ID,
          ADMIN_ROLE_ID);

      assertNull(result);
      verify(dal, never()).save(any());
    }
  }

  /** A second onboarding of the same client reuses the existing request instead of duplicating it. */
  @Test
  public void scheduleCostingBackgroundIsIdempotent() {
    OnboardingCostingScheduleService service = spy(new OnboardingCostingScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    doReturn(process).when(service)
        .resolveProcess(OnboardingCostingScheduleService.COSTING_PROCESS_KEY);
    doReturn(existing).when(service).findExistingRequest(CLIENT_ID, process);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      String result = service.scheduleCostingBackground(CLIENT_ID, ORG_ID, ADMIN_USER_ID,
          ADMIN_ROLE_ID);

      assertEquals(EXISTING_REQUEST_ID, result);
      verify(dal, never()).save(any());
    }
  }

  /**
   * A fresh schedule carries the secondly/30 trigger fields, and is saved and flushed once.
   *
   * <p>The interval is asserted on {@code intervalInSeconds} AND the two neighbouring interval
   * columns are asserted null: a leftover {@code minutelyInterval} from the pre-ETP-5370 5-minute
   * shape is inert (the scheduler reads only the column matching {@code FREQUENCY}) but it produces
   * a row that matches no hand-made one, which is the hard part to diagnose later.
   */
  @Test
  public void scheduleCostingBackgroundCreatesRequestWithSecondlySchedulingFields() {
    OnboardingCostingScheduleService service = spy(new OnboardingCostingScheduleService());
    Process process = mock(Process.class);
    doReturn(process).when(service)
        .resolveProcess(OnboardingCostingScheduleService.COSTING_PROCESS_KEY);
    doReturn(null).when(service).findExistingRequest(CLIENT_ID, process);
    doReturn(OB_CONTEXT).when(service)
        .buildObContext(CLIENT_ID, ORG_ID, ADMIN_USER_ID, ADMIN_ROLE_ID);

    // The generated constructor resolves real DAL metadata before any scheduling code runs.
    // Keep the generated field accessors, but back their generic get/set storage with a map:
    // this still observes the complete final schedule without initializing a database model.
    ProcessRequest request = propertyBackedRequest();
    // OBProvider normally assigns this identifier; the DAL save is isolated below.
    when(request.getId()).thenReturn(NEW_REQUEST_ID);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ProcessRequest.class)).thenReturn(request);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, CLIENT_ID)).thenReturn(mock(Client.class));
      when(dal.get(Organization.class, ORG_ID)).thenReturn(mock(Organization.class));
      when(dal.get(User.class, ADMIN_USER_ID)).thenReturn(mock(User.class));

      String result = service.scheduleCostingBackground(CLIENT_ID, ORG_ID, ADMIN_USER_ID,
          ADMIN_ROLE_ID);

      assertEquals(NEW_REQUEST_ID, result);
      assertEquals("S", request.getTiming());
      assertEquals("1", request.getFrequency());
      assertEquals(Long.valueOf(30L), request.getIntervalInSeconds());
      assertNull("a minutely interval would compete with the secondly one",
          request.getIntervalInMinutes());
      assertNull("an hourly interval would compete with the secondly one",
          request.getHourlyInterval());
      assertNull("no repetition cap — the schedule must repeat forever",
          request.getNumRepetitions());
      assertEquals("SCH", request.getStatus());
      assertEquals("Process Scheduler", request.getChannel());
      assertEquals("Automatic cost calculation (Etendo onboarding)", request.getDescription());
      assertTrue(request.isSecurityBasedOnRole());
      assertTrue(request.isActive());
      assertEquals(OB_CONTEXT, request.getOpenbravoContext());
      assertNotNull(request.getStartDate());
      assertNotNull(request.getStartTime());

      verify(dal).save(request);
      verify(dal).flush();
    }
  }

  /** The start instant is spread inside the 30-second cadence, so a bulk fix cannot phase-align tenants. */
  @Test
  public void spreadStartTimeStaysWithinTheCadence() {
    OnboardingCostingScheduleService service = new OnboardingCostingScheduleService();
    long cadenceMillis = 30L * 1000L;

    // Sampled rather than asserted once: the offset is random, so a single draw proves nothing
    // about the bound it is supposed to respect.
    for (int i = 0; i < 50; i++) {
      long before = System.currentTimeMillis();
      long start = service.spreadStartTime().getTime();
      long offset = start - before;
      assertTrue("start instant must not be in the past, was " + offset + "ms off",
          offset > -1000L);
      assertTrue("start instant must stay inside the 30-second cadence, was " + offset + "ms off",
          offset < cadenceMillis);
    }
  }

  /** activateSchedule is best-effort: an error resolving the process is swallowed, never propagated. */
  @Test
  public void activateScheduleSwallowsErrors() {
    OnboardingCostingScheduleService service = spy(new OnboardingCostingScheduleService());
    doThrow(new RuntimeException("boom")).when(service)
        .resolveProcess(OnboardingCostingScheduleService.COSTING_PROCESS_KEY);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      service.activateSchedule(CLIENT_ID);
      // No exception propagated — best-effort contract holds.
    }
  }

  /** Spy whose request is in {@code status} and whose scheduler state is as given. */
  private OnboardingCostingScheduleService activationService(String status, boolean allowed,
      boolean jobExists) throws Exception {
    OnboardingCostingScheduleService service = spy(new OnboardingCostingScheduleService());
    Process process = mock(Process.class);
    ProcessRequest request = mock(ProcessRequest.class);
    when(request.getId()).thenReturn(ACTIVATE_REQUEST_ID);
    when(request.getStatus()).thenReturn(status);
    doReturn(process).when(service)
        .resolveProcess(OnboardingCostingScheduleService.COSTING_PROCESS_KEY);
    doReturn(request).when(service).findExistingRequest(CLIENT_ID, process);
    doReturn(allowed).when(service).schedulingAllowed();
    return service;
  }

  /** Runs activateSchedule with the scheduler mocked to report {@code jobExists}. */
  private void activate(OnboardingCostingScheduleService service, boolean jobExists)
      throws Exception {
    OBScheduler scheduler = mock(OBScheduler.class);
    Scheduler quartz = mock(Scheduler.class);
    when(scheduler.getScheduler()).thenReturn(quartz);
    when(quartz.checkExists(any(JobKey.class))).thenReturn(jobExists);
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBScheduler> obScheduler = mockStatic(OBScheduler.class)) {
      obScheduler.when(OBScheduler::getInstance).thenReturn(scheduler);
      service.activateSchedule(CLIENT_ID);
    }
  }

  @Test
  public void activateScheduleSkipsWhenSchedulingNotAllowed() throws Exception {
    OnboardingCostingScheduleService service = activationService("SCH", false, false);
    activate(service, false);
    verify(service, never()).scheduleOnCleanConnection(anyString(), any());
  }

  @Test
  public void activateScheduleSkipsWhenJobAlreadyExists() throws Exception {
    OnboardingCostingScheduleService service = activationService("SCH", true, true);
    activate(service, true);
    verify(service, never()).scheduleOnCleanConnection(anyString(), any());
  }

  @Test
  public void activateScheduleSkipsWhenRequestNotScheduled() throws Exception {
    OnboardingCostingScheduleService service = activationService("UNS", true, false);
    activate(service, false);
    verify(service, never()).scheduleOnCleanConnection(anyString(), any());
  }

  /** The scheduler call runs on a thread other than the caller's, so it gets its own connection. */
  @Test
  public void activateScheduleArmsOnADifferentThreadThanTheCaller() throws Exception {
    OnboardingCostingScheduleService service = activationService("SCH", true, false);
    AtomicReference<Thread> armedOn = new AtomicReference<>();
    doAnswer(invocation -> {
      armedOn.set(Thread.currentThread());
      return null;
    }).when(service).armOnCurrentThread(anyString(), any());

    activate(service, false);

    verify(service).armOnCurrentThread(Mockito.eq(ACTIVATE_REQUEST_ID), any());
    assertNotSame(Thread.currentThread(), armedOn.get());
    assertTrue(armedOn.get().isDaemon());
    assertEquals("costing-schedule-activation", armedOn.get().getName());
  }

  /** The activation thread applies the caller's context, schedules, and closes its own DAL session. */
  @Test
  public void armOnCurrentThreadAppliesContextSchedulesAndClosesSession() throws Exception {
    OnboardingCostingScheduleService service = new OnboardingCostingScheduleService();
    OBContext callerContext = mock(OBContext.class);
    ProcessRequest request = mock(ProcessRequest.class);
    OBDal dal = mock(OBDal.class);
    when(dal.get(ProcessRequest.class, ACTIVATE_REQUEST_ID)).thenReturn(request);
    OBScheduler scheduler = mock(OBScheduler.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obScheduler = mockStatic(OBScheduler.class);
        MockedStatic<ProcessContext> processContext = mockStatic(ProcessContext.class);
        MockedStatic<ProcessBundle> processBundle = mockStatic(ProcessBundle.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obScheduler.when(OBScheduler::getInstance).thenReturn(scheduler);
      processContext.when(() -> ProcessContext.newInstance(any()))
          .thenReturn(mock(ProcessContext.class));
      processBundle.when(() -> ProcessBundle.request(anyString(), any(), any()))
          .thenReturn(mock(ProcessBundle.class));

      service.armOnCurrentThread(ACTIVATE_REQUEST_ID, callerContext);

      obContext.verify(() -> OBContext.setOBContext(callerContext));
      verify(scheduler).schedule(Mockito.eq(ACTIVATE_REQUEST_ID), any());
      verify(dal).rollbackAndClose();
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /** Even when scheduling fails, the thread's DAL session is closed and the failure propagates. */
  @Test
  public void armOnCurrentThreadClosesSessionWhenSchedulingFails() throws Exception {
    OnboardingCostingScheduleService service = new OnboardingCostingScheduleService();
    OBDal dal = mock(OBDal.class);
    when(dal.get(ProcessRequest.class, ACTIVATE_REQUEST_ID)).thenReturn(mock(ProcessRequest.class));
    OBScheduler scheduler = mock(OBScheduler.class);
    SchedulerException failure = new SchedulerException("trigger");
    doThrow(failure).when(scheduler).schedule(anyString(), any());

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obScheduler = mockStatic(OBScheduler.class);
        MockedStatic<ProcessContext> processContext = mockStatic(ProcessContext.class);
        MockedStatic<ProcessBundle> processBundle = mockStatic(ProcessBundle.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obScheduler.when(OBScheduler::getInstance).thenReturn(scheduler);
      processContext.when(() -> ProcessContext.newInstance(any()))
          .thenReturn(mock(ProcessContext.class));
      processBundle.when(() -> ProcessBundle.request(anyString(), any(), any()))
          .thenReturn(mock(ProcessBundle.class));

      try {
        service.armOnCurrentThread(ACTIVATE_REQUEST_ID, mock(OBContext.class));
        throw new AssertionError("expected the scheduler failure to propagate");
      } catch (SchedulerException e) {
        assertSame(failure, e);
      }
      verify(dal).rollbackAndClose();
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /** A scheduler failure is swallowed, and the WARN carries the exception with its cause. */
  @Test
  public void activateScheduleLogsCauseOnSchedulerFailure() throws Exception {
    OnboardingCostingScheduleService service = activationService("SCH", true, false);
    RuntimeException cause = new RuntimeException("sqlstate");
    SchedulerException failure = new SchedulerException("trigger", cause);
    doThrow(failure).when(service).armOnCurrentThread(anyString(), any());

    TestLogCapture capture = TestLogCapture.attachTo(OnboardingCostingScheduleService.class,
        Level.WARN);
    try {
      activate(service, false);
      assertEquals(1, capture.thrownAt(Level.WARN).size());
      Throwable logged = capture.thrownAt(Level.WARN).get(0);
      assertSame(failure, logged);
      assertSame(cause, logged.getCause());
    } finally {
      capture.detach();
    }
  }

  /** A hung activation is abandoned after the timeout and ends in a WARN, not in an exception. */
  @Test
  public void activateScheduleWarnsAndReturnsWhenActivationTimesOut() throws Exception {
    OnboardingCostingScheduleService service = activationService("SCH", true, false);
    service.activationTimeoutSeconds = 1L;
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    doAnswer(invocation -> {
      release.await(30, java.util.concurrent.TimeUnit.SECONDS);
      return null;
    }).when(service).armOnCurrentThread(anyString(), any());

    TestLogCapture capture = TestLogCapture.attachTo(OnboardingCostingScheduleService.class,
        Level.WARN);
    try {
      activate(service, false);
      assertEquals(1, capture.thrownAt(Level.WARN).size());
      assertTrue(capture.thrownAt(Level.WARN).get(0) instanceof java.util.concurrent.TimeoutException);
    } finally {
      release.countDown();
      capture.detach();
    }
  }
}
