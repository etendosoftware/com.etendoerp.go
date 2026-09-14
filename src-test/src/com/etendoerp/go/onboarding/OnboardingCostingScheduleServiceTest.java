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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * frequency {@code '2'} is the {@code "S2"} key core's {@code TriggerProvider} maps to
 * {@code repeatMinutelyForever}, and it reads {@code MINUTELY_INTERVAL} — not the secondly or daily
 * interval — for the period. Getting any one of the three wrong yields a row that looks scheduled in
 * the Process Request window and either never fires or fires on the wrong cadence, which is exactly
 * the class of bug that leaves a tenant's costs uncalculated with nothing visibly broken.
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
   * A fresh schedule carries the minutely/5 trigger fields, and is saved and flushed once.
   *
   * <p>The interval is asserted on {@code intervalInMinutes} AND the two neighbouring interval
   * columns are asserted null: a stray {@code secondlyInterval} is precisely what the GOClient
   * sampledata dump carries (30s), and writing both would leave which one wins up to the trigger
   * generator.
   */
  @Test
  public void scheduleCostingBackgroundCreatesRequestWithMinutelySchedulingFields() {
    OnboardingCostingScheduleService service = spy(new OnboardingCostingScheduleService());
    Process process = mock(Process.class);
    doReturn(process).when(service)
        .resolveProcess(OnboardingCostingScheduleService.COSTING_PROCESS_KEY);
    doReturn(null).when(service).findExistingRequest(CLIENT_ID, process);
    doReturn(OB_CONTEXT).when(service)
        .buildObContext(CLIENT_ID, ORG_ID, ADMIN_USER_ID, ADMIN_ROLE_ID);

    // OBProvider.get() normally assigns the id; simulate that since OBDal.save is mocked here.
    // Spy so the FK setters are no-ops: setting a mock Client/Organization/User on a real DAL
    // object triggers BaseOBObject.checkIsValidValue, which NPEs on the mock's null Entity once
    // any prior test in the shared JVM has initialized the model. The scheduling-field setters
    // asserted below are left untouched.
    ProcessRequest request = spy(new ProcessRequest());
    request.setId(NEW_REQUEST_ID);
    doNothing().when(request).setClient(any());
    doNothing().when(request).setOrganization(any());
    doNothing().when(request).setUserContact(any());
    doNothing().when(request).setProcess(any());

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
      assertEquals("2", request.getFrequency());
      assertEquals(Long.valueOf(5L), request.getIntervalInMinutes());
      assertNull("a secondly interval would compete with the minutely one",
          request.getIntervalInSeconds());
      assertNull("an hourly interval would compete with the minutely one",
          request.getHourlyInterval());
      assertNull("no repetition cap — the schedule must repeat forever",
          request.getNumRepetitions());
      assertEquals("SCH", request.getStatus());
      assertEquals("Process Scheduler", request.getChannel());
      assertTrue(request.isSecurityBasedOnRole());
      assertTrue(request.isActive());
      assertEquals(OB_CONTEXT, request.getOpenbravoContext());
      assertNotNull(request.getStartDate());
      assertNotNull(request.getStartTime());

      verify(dal).save(request);
      verify(dal).flush();
    }
  }

  /** The start instant is spread inside the 5-minute cadence, so a bulk fix cannot phase-align tenants. */
  @Test
  public void spreadStartTimeStaysWithinTheCadence() {
    OnboardingCostingScheduleService service = new OnboardingCostingScheduleService();
    long cadenceMillis = 5L * 60L * 1000L;

    // Sampled rather than asserted once: the offset is random, so a single draw proves nothing
    // about the bound it is supposed to respect.
    for (int i = 0; i < 50; i++) {
      long before = System.currentTimeMillis();
      long start = service.spreadStartTime().getTime();
      long offset = start - before;
      assertTrue("start instant must not be in the past, was " + offset + "ms off",
          offset > -1000L);
      assertTrue("start instant must stay inside the 5-minute cadence, was " + offset + "ms off",
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
}
