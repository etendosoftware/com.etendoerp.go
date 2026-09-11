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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.criterion.Criterion;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessRequest;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.OBScheduler;
import org.openbravo.scheduling.ProcessContext;

/**
 * Unit tests for {@link SiiTbaiAutoSendScheduleService} (ETP-5117).
 *
 * <p>Testing convention: the
 * protected seams ({@code resolveProcess}, {@code findExistingRequest}, {@code buildObContext})
 * are stubbed via a Mockito spy for the orchestration-level tests ({@link #ensureAutoSendSchedule}
 * skip / idempotent / creation branches and the scheduling field values written on a fresh
 * request), while {@link #findExistingRequest} and {@link #resolveProcess} are also exercised
 * directly (real method, mocked {@link OBCriteria} chain) to prove the actual client + organization
 * + process + active scoping used for idempotency recognition, and {@link #activateSchedule} is
 * tested for its best-effort error-swallowing contract.
 */
public class SiiTbaiAutoSendScheduleServiceTest {

  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String OTHER_ORG_ID = "org-2";
  private static final String USER_ID = "user-1";
  private static final String ROLE_ID = "role-1";
  private static final String EXISTING_REQUEST_ID = "req-existing";
  private static final String NEW_REQUEST_ID = "req-new";
  private static final String OB_CONTEXT = "ob-context-string";
  private static final String DESCRIPTION = "Automatic SII invoice sending (Etendo GO)";

  // "Human-created row" shape used by the existing-row-recognition test below.
  private static final String GO_CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  private static final String GO_ORG_ID = "E443A31992CB4635AFCAEABE7183CE85";

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ─── ensureAutoSendSchedule: process resolution ──────────────────────────────

  /** When the process cannot be resolved, the schedule is skipped and nothing is persisted. */
  @Test
  public void ensureAutoSendScheduleReturnsNullWhenProcessNotFound() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    doReturn(null).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      String result = service.ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, DESCRIPTION);

      assertNull(result);
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  /** resolveProcess is used with the SII search key constant when called for SII. */
  @Test
  public void ensureAutoSendScheduleUsesSiiSearchKey() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    doReturn(null).when(service).resolveProcess(any());

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      service.ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, DESCRIPTION);

      verify(service).resolveProcess(eq("Grouped invoices SII sending process"));
    }
  }

  /** resolveProcess is used with the TBAI search key constant when called for TBAI. */
  @Test
  public void ensureAutoSendScheduleUsesTbaiSearchKey() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    doReturn(null).when(service).resolveProcess(any());

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      service.ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY, DESCRIPTION);

      verify(service).resolveProcess(eq("RegisterTBAInvoice"));
    }
  }

  // ─── ensureAutoSendSchedule: idempotency ─────────────────────────────────────

  /** A second call for the same client + organization + process reuses the existing request. */
  @Test
  public void ensureAutoSendScheduleIsIdempotentForSameClientAndOrg() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    doReturn(existing).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      String result = service.ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, DESCRIPTION);

      assertEquals(EXISTING_REQUEST_ID, result);
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  /**
   * A call for a DIFFERENT organization of the same client does not reuse the first
   * organization's request — it creates its own row, because SII/TBAI configs are inherently
   * per-organization (unlike the client-wide PSD2 onboarding schedule).
   */
  @Test
  public void ensureAutoSendScheduleCreatesSeparateRequestForDifferentOrganization() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existingForFirstOrg = mock(ProcessRequest.class);
    when(existingForFirstOrg.getId()).thenReturn(EXISTING_REQUEST_ID);

    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    // First organization already has a request.
    doReturn(existingForFirstOrg).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);
    // Second organization has none yet.
    doReturn(null).when(service).findExistingRequest(CLIENT_ID, OTHER_ORG_ID, process);
    doReturn(OB_CONTEXT).when(service).buildObContext(CLIENT_ID, OTHER_ORG_ID, USER_ID, ROLE_ID);

    ProcessRequest newRequest = spy(new ProcessRequest());
    newRequest.setId(NEW_REQUEST_ID);
    doNothing().when(newRequest).setClient(any());
    doNothing().when(newRequest).setOrganization(any());
    doNothing().when(newRequest).setUserContact(any());
    doNothing().when(newRequest).setProcess(any());

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ProcessRequest.class)).thenReturn(newRequest);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, CLIENT_ID)).thenReturn(mock(Client.class));
      when(dal.get(Organization.class, OTHER_ORG_ID)).thenReturn(mock(Organization.class));
      when(dal.get(User.class, USER_ID)).thenReturn(mock(User.class));

      // First org: reuses the existing row, no save.
      String firstResult = service.ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, DESCRIPTION);
      assertEquals(EXISTING_REQUEST_ID, firstResult);

      // Second org (same client): a NEW row is created.
      String secondResult = service.ensureAutoSendSchedule(CLIENT_ID, OTHER_ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, DESCRIPTION);
      assertEquals(NEW_REQUEST_ID, secondResult);

      verify(dal, times(1)).save(any(ProcessRequest.class));
      verify(dal, times(1)).flush();
    }
  }

  // ─── ensureAutoSendSchedule: row correctness ─────────────────────────────────

  /** A fresh schedule is built with every documented scheduling field, then saved and flushed once. */
  @Test
  public void ensureAutoSendScheduleCreatesRequestWithSchedulingFields() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    doReturn(null).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);
    doReturn(OB_CONTEXT).when(service).buildObContext(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID);

    // Spy so the FK setters are no-ops:
    // setting a mock Client/Organization/User on a real DAL object triggers
    // BaseOBObject.checkIsValidValue, which NPEs on the mock's null Entity. The scheduling-field
    // setters asserted below are left untouched.
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
      when(dal.get(User.class, USER_ID)).thenReturn(mock(User.class));

      String result = service.ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, DESCRIPTION);

      assertEquals(NEW_REQUEST_ID, result);
      assertEquals("S", request.getTiming());
      assertEquals("3", request.getFrequency());
      assertEquals(Long.valueOf(12L), request.getHourlyInterval());
      assertEquals("SCH", request.getStatus());
      assertEquals("Process Scheduler", request.getChannel());
      assertTrue(request.isSecurityBasedOnRole());
      assertFalse(request.isFinishes());
      assertTrue(request.isActive());
      assertEquals(DESCRIPTION, request.getDescription());
      assertEquals(OB_CONTEXT, request.getOpenbravoContext());

      assertNotNull(request.getStartDate());
      assertNotNull(request.getStartTime());
      int hour = request.getStartTime().toLocalDateTime().getHour();
      int minute = request.getStartTime().toLocalDateTime().getMinute();
      assertEquals("startTime hour must be 11", 11, hour);
      assertEquals("startTime minute must be 0", 0, minute);

      verify(dal).save(request);
      verify(dal).flush();
    }
  }

  // ─── resolveProcess: real method, mocked OBCriteria chain ────────────────────

  @Test
  public void resolveProcessQueriesBySearchKeyAndReturnsMatch() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();
    Process process = mock(Process.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<Process> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Process.class)).thenReturn(criteria);
      ArgumentCaptor<Criterion> criterionCaptor = ArgumentCaptor.forClass(Criterion.class);
      when(criteria.add(criterionCaptor.capture())).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(process);

      Process result = service.resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

      assertEquals(process, result);
      verify(criteria).setMaxResults(1);
      List<String> added = criterionCaptor.getAllValues().stream()
          .map(Criterion::toString).collect(Collectors.toList());
      assertTrue("expected the searchKey filter, got: " + added,
          added.stream().anyMatch(s -> s.equals("searchKey=Grouped invoices SII sending process")));
    }
  }

  @Test
  public void resolveProcessReturnsNullWhenNoMatch() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<Process> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Process.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      Process result = service.resolveProcess("Not Configured Process Key");

      assertNull(result);
    }
  }

  // ─── findExistingRequest: real method, existing-row recognition ─────────────

  /**
   * Simulates the shape of a row a human created by hand in Classic's Process Request window
   * (client/org resolved from the DB, the SII process, active) and confirms
   * {@link SiiTbaiAutoSendScheduleService#findExistingRequest} scopes the query to
   * client + organization + process + active and recognizes it as already existing.
   */
  @Test
  public void findExistingRequestRecognizesManuallyCreatedActiveRow() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();
    Client goClient = mock(Client.class);
    Organization goOrg = mock(Organization.class);
    Process siiProcess = mock(Process.class);
    ProcessRequest humanCreatedRow = mock(ProcessRequest.class);
    when(humanCreatedRow.getId()).thenReturn(EXISTING_REQUEST_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, GO_CLIENT_ID)).thenReturn(goClient);
      when(dal.get(Organization.class, GO_ORG_ID)).thenReturn(goOrg);

      @SuppressWarnings("unchecked")
      OBCriteria<ProcessRequest> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(ProcessRequest.class)).thenReturn(criteria);
      ArgumentCaptor<Criterion> criterionCaptor = ArgumentCaptor.forClass(Criterion.class);
      when(criteria.add(criterionCaptor.capture())).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(humanCreatedRow);

      ProcessRequest result = service.findExistingRequest(GO_CLIENT_ID, GO_ORG_ID, siiProcess);

      assertEquals(humanCreatedRow, result);
      verify(criteria).setMaxResults(1);
      List<String> added = criterionCaptor.getAllValues().stream()
          .map(Criterion::toString).collect(Collectors.toList());
      assertEquals(4, added.size());
      assertTrue("expected the client filter, got: " + added,
          added.stream().anyMatch(s -> s.equals("client=" + goClient)));
      assertTrue("expected the organization filter, got: " + added,
          added.stream().anyMatch(s -> s.equals("organization=" + goOrg)));
      assertTrue("expected the process filter, got: " + added,
          added.stream().anyMatch(s -> s.equals("process=" + siiProcess)));
      assertTrue("expected the active=true filter, got: " + added,
          added.stream().anyMatch(s -> s.equals("active=true")));
    }
  }

  @Test
  public void findExistingRequestReturnsNullWhenNoMatch() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();
    Process process = mock(Process.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, CLIENT_ID)).thenReturn(mock(Client.class));
      when(dal.get(Organization.class, ORG_ID)).thenReturn(mock(Organization.class));

      @SuppressWarnings("unchecked")
      OBCriteria<ProcessRequest> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(ProcessRequest.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      ProcessRequest result = service.findExistingRequest(CLIENT_ID, ORG_ID, process);

      assertNull(result);
    }
  }

  // ─── activateSchedule: best-effort ────────────────────────────────────────────

  @Test
  public void activateScheduleIsNoOpWhenRequestIdIsNull() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      service.activateSchedule(null);
      obDal.verifyNoInteractions();
    }
  }

  @Test
  public void activateScheduleIsNoOpWhenRequestNotFound() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ProcessRequest.class, NEW_REQUEST_ID)).thenReturn(null);

      service.activateSchedule(NEW_REQUEST_ID);
      // No exception; nothing further attempted.
    }
  }

  /**
   * A failure while building the scheduler context or registering with {@link OBScheduler} (e.g.
   * because the enclosing request's transaction has not committed yet — see class Javadoc) is
   * caught and logged, never propagated to the caller.
   */
  @Test
  public void activateScheduleSwallowsSchedulerFailure() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();
    ProcessRequest request = mock(ProcessRequest.class);
    when(request.getOpenbravoContext()).thenReturn(OB_CONTEXT);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ProcessContext> processContext = mockStatic(ProcessContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ProcessRequest.class, NEW_REQUEST_ID)).thenReturn(request);

      // Simulate a failure while resolving the scheduling context (stand-in for any downstream
      // OBScheduler failure) — must not propagate.
      processContext.when(() -> ProcessContext.newInstance(OB_CONTEXT))
          .thenThrow(new RuntimeException("scheduler unavailable"));

      service.activateSchedule(NEW_REQUEST_ID);
      // No exception propagated — best-effort contract holds.
      obContext.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  @Test
  public void activateScheduleSwallowsErrorLoadingRequest() {
    SiiTbaiAutoSendScheduleService service = new SiiTbaiAutoSendScheduleService();

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ProcessRequest.class, NEW_REQUEST_ID))
          .thenThrow(new RuntimeException("DB unavailable"));

      service.activateSchedule(NEW_REQUEST_ID);
      obContext.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── unscheduleAutoSend: process resolution (ETP-5117 follow-up) ────────────

  /** When the process cannot be resolved, unscheduling is a safe no-op — nothing touched. */
  @Test
  public void unscheduleAutoSendIsNoOpWhenProcessNotFound() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    doReturn(null).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

      verify(service, never()).findExistingRequest(any(), any(), any());
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  // ─── unscheduleAutoSend: no existing schedule ────────────────────────────────

  /**
   * Idempotent no-op branch: no active {@code AD_Process_Request} exists for client + org +
   * process — either the fiscal config was only ever saved through Classic UI, or it was already
   * unscheduled by a prior call.
   */
  @Test
  public void unscheduleAutoSendIsNoOpWhenNoExistingActiveRequest() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    doReturn(null).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obScheduler = mockStatic(OBScheduler.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

      verify(dal, never()).save(any());
      verify(dal, never()).flush();
      obScheduler.verifyNoInteractions();
    }
  }

  // ─── unscheduleAutoSend: deactivates the row + best-effort Quartz unschedule ─

  /**
   * An existing active schedule is deactivated ({@code Active=false}, saved, flushed) and a
   * best-effort live Quartz unschedule is attempted with a {@link ProcessContext} rebuilt from
   * the row's own stored {@code OpenbravoContext}.
   */
  @Test
  public void unscheduleAutoSendDeactivatesExistingRequestAndUnschedulesFromQuartz() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    when(existing.getOpenbravoContext()).thenReturn(OB_CONTEXT);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    doReturn(existing).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);

    ProcessContext processContext = mock(ProcessContext.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obSchedulerStatic = mockStatic(OBScheduler.class);
        MockedStatic<ProcessContext> processContextStatic = mockStatic(ProcessContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      processContextStatic.when(() -> ProcessContext.newInstance(OB_CONTEXT)).thenReturn(processContext);

      OBScheduler scheduler = mock(OBScheduler.class);
      obSchedulerStatic.when(OBScheduler::getInstance).thenReturn(scheduler);

      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

      verify(scheduler).unschedule(EXISTING_REQUEST_ID, processContext);
      verify(existing).setActive(false);
      verify(dal).save(existing);
      verify(dal).flush();
    }
  }

  /**
   * When the row's stored {@code OpenbravoContext} cannot be rebuilt into a usable
   * {@link ProcessContext} (missing/corrupt), the live Quartz call is skipped entirely — but the
   * row is still deactivated, since that DB flag alone guarantees the schedule will not be picked
   * up again.
   */
  @Test
  public void unscheduleAutoSendSkipsQuartzCallWhenStoredContextIsUnusableButStillDeactivatesRow() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    when(existing.getOpenbravoContext()).thenReturn(null);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    doReturn(existing).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obSchedulerStatic = mockStatic(OBScheduler.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBScheduler scheduler = mock(OBScheduler.class);
      obSchedulerStatic.when(OBScheduler::getInstance).thenReturn(scheduler);
      // ProcessContext.newInstance(null) runs for REAL here (not mocked) — it returns null for a
      // blank input per its own contract, exercising unscheduleFromQuartz's own null-guard.

      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

      verify(scheduler, never()).unschedule(any(), any());
      verify(existing).setActive(false);
      verify(dal).save(existing);
      verify(dal).flush();
    }
  }

  /**
   * A failure inside the live Quartz unschedule call (e.g. scheduler unavailable) is caught and
   * logged — never propagated — and the row is still deactivated, matching
   * {@link #activateScheduleSwallowsSchedulerFailure}'s best-effort contract on the creation side.
   */
  @Test
  public void unscheduleAutoSendSwallowsQuartzFailureAndStillDeactivatesRow() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    when(existing.getOpenbravoContext()).thenReturn(OB_CONTEXT);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    doReturn(existing).when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);

    ProcessContext processContext = mock(ProcessContext.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obSchedulerStatic = mockStatic(OBScheduler.class);
        MockedStatic<ProcessContext> processContextStatic = mockStatic(ProcessContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      processContextStatic.when(() -> ProcessContext.newInstance(OB_CONTEXT)).thenReturn(processContext);

      OBScheduler scheduler = mock(OBScheduler.class);
      obSchedulerStatic.when(OBScheduler::getInstance).thenReturn(scheduler);
      doThrow(new RuntimeException("scheduler unavailable"))
          .when(scheduler).unschedule(EXISTING_REQUEST_ID, processContext);

      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      // No exception propagated — best-effort contract holds.

      verify(existing).setActive(false);
      verify(dal).save(existing);
      verify(dal).flush();
      obContext.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── unscheduleAutoSend: idempotency ──────────────────────────────────────────

  /**
   * Calling {@code unscheduleAutoSend} twice in a row is a safe no-op the second time: the first
   * call already flips {@code Active} to {@code false}, and
   * {@link SiiTbaiAutoSendScheduleService#findExistingRequest} only ever matches active rows —
   * mirroring a config that is deactivated twice, or a second deactivating PUT on an already
   * unscheduled config.
   */
  @Test
  public void unscheduleAutoSendSecondCallIsNoOpAfterFirstDeactivates() {
    SiiTbaiAutoSendScheduleService service = spy(new SiiTbaiAutoSendScheduleService());
    Process process = mock(Process.class);
    ProcessRequest existing = mock(ProcessRequest.class);
    when(existing.getId()).thenReturn(EXISTING_REQUEST_ID);
    when(existing.getOpenbravoContext()).thenReturn(OB_CONTEXT);
    doReturn(process).when(service).resolveProcess(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    // First call finds the active row; the second finds nothing — findExistingRequest only ever
    // matches Active=true, and the first call already deactivated it.
    doReturn(existing, (ProcessRequest) null)
        .when(service).findExistingRequest(CLIENT_ID, ORG_ID, process);

    ProcessContext processContext = mock(ProcessContext.class);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBScheduler> obSchedulerStatic = mockStatic(OBScheduler.class);
        MockedStatic<ProcessContext> processContextStatic = mockStatic(ProcessContext.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      processContextStatic.when(() -> ProcessContext.newInstance(OB_CONTEXT)).thenReturn(processContext);
      OBScheduler scheduler = mock(OBScheduler.class);
      obSchedulerStatic.when(OBScheduler::getInstance).thenReturn(scheduler);

      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      service.unscheduleAutoSend(CLIENT_ID, ORG_ID, SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);

      // Only the FIRST call did any work.
      verify(existing, times(1)).setActive(false);
      verify(dal, times(1)).save(existing);
      verify(dal, times(1)).flush();
      verify(scheduler, times(1)).unschedule(EXISTING_REQUEST_ID, processContext);
    }
  }
}
