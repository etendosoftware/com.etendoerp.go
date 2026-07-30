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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.ProcessContext;

/**
 * Unit tests for {@link StoredColumnQueueScheduleStartup}.
 *
 * <p>Covers: a single System Process Request is inserted with the correct schedule shape when none
 * exists and is then registered with the scheduler in the same boot; idempotency per process — an
 * existing active {@code SCH} request short-circuits before any insert, commit or scheduling; a
 * missing process definition is skipped gracefully; and the serialized {@code ob_context} is a valid
 * System ({@code client '0'} / {@code org '0'}) ProcessContext.</p>
 *
 * <p>The real {@link org.openbravo.scheduling.OBScheduler} is not exercised: the class-under-test is
 * subclassed to capture the {@code registerWithScheduler} call instead, keeping the assertions on the
 * DAL layer.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoredColumnQueueScheduleStartupTest {

  private static final String PROCESS_ID = "D35DC63A8838412890AEE01D31CD70A3";
  private static final String REQUEST_ID = "98E3901D0ABB49A2AC4B961228BF165E";
  private static final String SYSTEM = "0";
  private static final String SYSTEM_USER = "100";

  private StoredColumnQueueScheduleStartup startup;
  /** Captures the request id passed to registerWithScheduler(), or null if it was never called. */
  private String scheduledRequestId;

  private OBDal obDal;
  private OBProvider obProvider;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<OBContext> obContextMock;

  private Organization orgZero;
  private Client systemClient;
  private User systemUser;
  private Process process;

  @BeforeEach
  void setUp() {
    scheduledRequestId = null;
    startup = new StoredColumnQueueScheduleStartup() {
      @Override
      void registerWithScheduler(String requestId) {
        scheduledRequestId = requestId;
      }
    };
    obDal = mock(OBDal.class);
    obProvider = mock(OBProvider.class);

    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

    orgZero = mock(Organization.class);
    systemClient = mock(Client.class);
    systemUser = mock(User.class);
    process = mock(Process.class);

    when(obDal.get(Organization.class, SYSTEM)).thenReturn(orgZero);
    when(obDal.get(Client.class, SYSTEM)).thenReturn(systemClient);
    when(obDal.get(User.class, SYSTEM_USER)).thenReturn(systemUser);
    when(obDal.get(Process.class, PROCESS_ID)).thenReturn(process);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obProviderMock != null) {
      obProviderMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  @SuppressWarnings("unchecked")
  private void stubExistingRequests(List<ProcessRequest> result) {
    OBCriteria<ProcessRequest> criteria = mock(OBCriteria.class);
    when(criteria.list()).thenReturn(result);
    when(obDal.createCriteria(ProcessRequest.class)).thenReturn(criteria);
  }

  @Test
  @DisplayName("inserts one System request with the correct schedule shape when none exists")
  void insertsSystemRequestWhenNoneExists() {
    stubExistingRequests(List.of());
    ProcessRequest request = mock(ProcessRequest.class);
    when(obProvider.get(ProcessRequest.class)).thenReturn(request);

    startup.ensureScheduled();

    verify(request).setNewOBObject(true);
    verify(request).setId(REQUEST_ID);
    verify(request).setClient(systemClient);
    verify(request).setOrganization(orgZero);
    verify(request).setActive(true);
    verify(request).setProcess(process);
    verify(request).setUserContact(systemUser);
    verify(request).setSecurityBasedOnRole(true);
    verify(request).setStatus("SCH");
    verify(request).setChannel("Process Scheduler");
    verify(request).setTiming("S");
    verify(request).setFrequency("2");
    verify(request).setIntervalInMinutes(5L);
    verify(request).setGroup(false);
    verify(obDal).save(request);
    verify(obDal).flush();
    verify(obDal).commitAndClose();
    // Registered with the scheduler in the same boot, after the commit.
    assertEquals(REQUEST_ID, scheduledRequestId);
  }

  @Test
  @DisplayName("idempotent per process: an existing active SCH request inserts nothing and never commits")
  void idempotentWhenActiveRequestExists() {
    stubExistingRequests(List.of(mock(ProcessRequest.class)));

    startup.ensureScheduled();

    verify(obProvider, never()).get(ProcessRequest.class);
    verify(obDal, never()).save(any());
    verify(obDal, never()).flush();
    verify(obDal, never()).commitAndClose();
    // Nothing inserted -> the scheduler is not touched (an existing SCH row is already scheduled).
    assertNull(scheduledRequestId);
  }

  @Test
  @DisplayName("missing process definition is skipped gracefully (no insert, no commit, no scheduling)")
  void skipsWhenProcessNotInstalled() {
    stubExistingRequests(List.of());
    when(obDal.get(Process.class, PROCESS_ID)).thenReturn(null);

    startup.ensureScheduled();

    verify(obProvider, never()).get(ProcessRequest.class);
    verify(obDal, never()).save(any());
    verify(obDal, never()).commitAndClose();
    assertNull(scheduledRequestId);
  }

  @Test
  @DisplayName("ob_context serializes a valid System ProcessContext (client 0 / org 0 / user 100)")
  void obContextIsValidSystemProcessContext() {
    stubExistingRequests(List.of());
    ProcessRequest request = mock(ProcessRequest.class);
    when(obProvider.get(ProcessRequest.class)).thenReturn(request);

    startup.ensureScheduled();

    ArgumentCaptor<String> ctxCaptor = ArgumentCaptor.forClass(String.class);
    verify(request).setOpenbravoContext(ctxCaptor.capture());

    String raw = ctxCaptor.getValue();
    assertTrue(raw.contains(ProcessContext.class.getName()),
        "ob_context must be wrapped by the ProcessContext FQN: " + raw);

    ProcessContext ctx = ProcessContext.newInstance(raw);
    assertNotNull(ctx, "ob_context must parse back into a ProcessContext: " + raw);
    assertEquals(SYSTEM, ctx.getClient());
    assertEquals(SYSTEM, ctx.getOrganization());
    assertEquals(SYSTEM_USER, ctx.getUser());
    assertTrue(ctx.isRoleSecurity(), "System run must use role security");
  }
}
