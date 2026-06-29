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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * Unit tests for {@link OnboardingPsd2SyncService}.
 *
 * <p>The protected seams ({@code resolveProcess}, {@code findExistingRequest}, {@code buildObContext})
 * are stubbed via a Mockito spy so the tests assert only the service's orchestration: the skip / idempotent
 * branches, the scheduling field values written on a fresh request, and the best-effort error swallowing of
 * {@link OnboardingPsd2SyncService#activateSchedule(String)}.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class OnboardingPsd2SyncServiceTest {

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

  /** When the PSD2 process is not installed, the schedule is skipped and nothing is persisted. */
  @Test
  public void schedulePsd2StatementSyncSkipsWhenProcessNotFound() {
    OnboardingPsd2SyncService service = spy(new OnboardingPsd2SyncService());
    doReturn(null).when(service).resolveProcess(OnboardingPsd2SyncService.PSD2_PROCESS_KEY);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      String result = service.schedulePsd2StatementSync(CLIENT_ID, ORG_ID, ADMIN_USER_ID, ADMIN_ROLE_ID);

      assertNull(result);
      verify(dal, never()).save(any());
    }
  }

  /** A second onboarding of the same client reuses the existing request without persisting a new row. */
  @Test
  public void schedulePsd2StatementSyncIsIdempotent() {
    OnboardingPsd2SyncService service = spy(new OnboardingPsd2SyncService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    doReturn(process).when(service).resolveProcess(OnboardingPsd2SyncService.PSD2_PROCESS_KEY);
    doReturn(existing).when(service).findExistingRequest(CLIENT_ID, process);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      String result = service.schedulePsd2StatementSync(CLIENT_ID, ORG_ID, ADMIN_USER_ID, ADMIN_ROLE_ID);

      assertEquals(EXISTING_REQUEST_ID, result);
      verify(dal, never()).save(any());
    }
  }

  /** A fresh schedule is built with the daily 03:00–06:00 scheduling fields, then saved and flushed once. */
  @Test
  public void schedulePsd2StatementSyncCreatesRequestWithSchedulingFields() {
    OnboardingPsd2SyncService service = spy(new OnboardingPsd2SyncService());
    Process process = mock(Process.class);
    doReturn(process).when(service).resolveProcess(OnboardingPsd2SyncService.PSD2_PROCESS_KEY);
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

      String result = service.schedulePsd2StatementSync(CLIENT_ID, ORG_ID, ADMIN_USER_ID, ADMIN_ROLE_ID);

      assertEquals(NEW_REQUEST_ID, result);
      assertEquals("S", request.getTiming());
      assertEquals("4", request.getFrequency());
      assertEquals(Long.valueOf(1L), request.getDailyInterval());
      assertEquals("N", request.getDailyOption());
      assertEquals("SCH", request.getStatus());
      assertEquals("Process Scheduler", request.getChannel());
      assertTrue(request.isSecurityBasedOnRole());
      assertTrue(request.isActive());
      assertEquals(OB_CONTEXT, request.getOpenbravoContext());
      assertNotNull(request.getStartTime());
      int hour = request.getStartTime().toLocalDateTime().getHour();
      assertTrue("startTime hour must be in [3,6) but was " + hour, hour >= 3 && hour < 6);

      verify(dal).save(request);
      verify(dal).flush();
    }
  }

  /** activateSchedule is best-effort: an error resolving the process is swallowed, never propagated. */
  @Test
  public void activateScheduleSwallowsErrors() {
    OnboardingPsd2SyncService service = spy(new OnboardingPsd2SyncService());
    doThrow(new RuntimeException("boom")).when(service)
        .resolveProcess(OnboardingPsd2SyncService.PSD2_PROCESS_KEY);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      service.activateSchedule(CLIENT_ID);
      // No exception propagated — best-effort contract holds.
    }
  }
}
