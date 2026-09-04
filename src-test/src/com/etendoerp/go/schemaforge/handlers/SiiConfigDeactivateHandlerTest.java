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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Date;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.data.AEATSIIFacturas;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.SiiTbaiAutoSendScheduleService;

/**
 * Unit tests for {@link SiiConfigDeactivateHandler} (ETP-4785).
 *
 * <p>Covers the dispatch guard clauses in {@link SiiConfigDeactivateHandler#handle} (non-PUT,
 * not deactivating, blank recordId), and all branches of
 * {@link SiiConfigDeactivateHandler#smartDeactivate}: config not found, no adoption date (→
 * delete), adoption date set with SII invoices (→ null / fall through to CRUD), and adoption date
 * set without SII invoices (→ delete).
 */
public class SiiConfigDeactivateHandlerTest {

  private static final String RECORD_ID = "sii-config-001";
  private static final String ORG_ID = "org-spain-001";

  // ─── handle(): dispatch guards ────────────────────────────────────────────────

  @Test
  public void handleReturnsNullForGetMethod() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("GET").build()));
  }

  @Test
  public void handleReturnsNullForPostMethod() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("POST").build()));
  }

  @Test
  public void handleReturnsNullForPatchMethod() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("PATCH").build()));
  }

  @Test
  public void handleReturnsNullWhenBodyHasNoActiveField() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("name", "foo"))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenBodyIsNull() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenActiveIsTrue() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", true))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenRecordIdIsBlank() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId("   ")
        .build()));
  }

  @Test
  public void handleReturnsNullWhenRecordIdIsMissing() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .build()));
  }

  // ─── handle(): isExplicitlyDeactivating edge cases ───────────────────────────

  @Test
  public void handleRecognizesStringFalseAsDeactivating() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // Config not found — simplest path confirming handle() was entered.
      when(dal.get(eq(AEATSIIConfig.class), Mockito.anyString())).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", "false"))
          .recordId(RECORD_ID)
          .build());
      // null because config not found in smartDeactivate
      assertNull(result);
    }
  }

  @Test
  public void handleRecognizesStringNAsDeactivating() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), Mockito.anyString())).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", "N"))
          .recordId(RECORD_ID)
          .build());
      assertNull(result);
    }
  }

  // ─── afterHandle(): always returns null ──────────────────────────────────────

  @Test
  public void afterHandleAlwaysReturnsNull() {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("PUT").recordId(RECORD_ID).build()));
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("POST").build()));
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("GET").build()));
  }

  // ─── smartDeactivate(): config not found ─────────────────────────────────────

  @Test
  public void smartDeactivateReturnsNullWhenConfigNotFound() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(null);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);
      assertNull(result);

      verify(dal, never()).remove(any());
      verify(dal, never()).flush();
    }
  }

  // ─── smartDeactivate(): no adoption date (→ delete) ──────────────────────────

  @Test
  public void smartDeactivateDeletesAndReturnsDeletedWhenFechaAcogidaIsNull() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      AEATSIIConfig config = mock(AEATSIIConfig.class);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      // fechaAcogidaSII is null — config never entered the fiscal system
      when(config.getFechaAcogidaSII()).thenReturn(null);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      verify(dal).remove(config);
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  // ─── smartDeactivate(): adoption date set, SII invoices exist (→ null) ───────

  @Test
  public void smartDeactivateReturnsNullWhenSiiInvoicesExist() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    Date adoptionDate = new Date();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      AEATSIIConfig config = mock(AEATSIIConfig.class);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      when(config.getFechaAcogidaSII()).thenReturn(adoptionDate);

      // hasSiiInvoicesSince → OBCriteria returns count > 0
      @SuppressWarnings("unchecked")
      OBCriteria<AEATSIIFacturas> crit = mock(OBCriteria.class);
      when(dal.createCriteria(AEATSIIFacturas.class)).thenReturn(crit);
      when(crit.createAlias(Mockito.anyString(), Mockito.anyString())).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(3L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      // SII invoices found → fallthrough to default CRUD deactivation
      assertNull(result);
      verify(dal, never()).remove(any());
    }
  }

  // ─── smartDeactivate(): adoption date set, no SII invoices (→ delete) ────────

  @Test
  public void smartDeactivateDeletesWhenAdoptionDateSetButNoSiiInvoices() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    Date adoptionDate = new Date();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      AEATSIIConfig config = mock(AEATSIIConfig.class);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      when(config.getFechaAcogidaSII()).thenReturn(adoptionDate);

      // hasSiiInvoicesSince → OBCriteria returns count 0
      @SuppressWarnings("unchecked")
      OBCriteria<AEATSIIFacturas> crit = mock(OBCriteria.class);
      when(dal.createCriteria(AEATSIIFacturas.class)).thenReturn(crit);
      when(crit.createAlias(Mockito.anyString(), Mockito.anyString())).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(0L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      verify(dal).remove(config);
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  // ─── handle(): 500 on unexpected exception (must NOT fall through to default CRUD) ───

  @Test
  public void handleReturns500OnUnexpectedException() throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), Mockito.anyString()))
          .thenThrow(new RuntimeException("DB exploded"));

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());
      // Must return 500, NOT null — returning null would let the default CRUD deactivate
      // the record without verifying pending invoices, bypassing the business rule.
      assertNotNull(result);
      assertEquals(500, result.getHttpStatus());
      obCtxMock.verify(OBContext::restorePreviousMode, Mockito.times(1));
    }
  }

  // ─── afterHandle(): ETP-5117 twice-a-day auto-send schedule ─────────────────

  private static final String AUTO_SEND_SCHEDULE_DESCRIPTION =
      "Automatic SII invoice sending (Etendo GO)";
  private static final String CLIENT_ID = "client-001";
  private static final String USER_ID = "user-001";
  private static final String ROLE_ID = "role-001";

  /**
   * Injects a mock {@link SiiTbaiAutoSendScheduleService} into the handler's private final
   * {@code scheduleService} field via reflection, bypassing its real construction so the
   * ETP-5117 auto-send scheduling call can be verified in isolation from the DAL wiring
   * {@link SiiTbaiAutoSendScheduleService} itself needs — same convention as
   * {@code SalesInvoiceHeaderHandlerTest#handlerWithTotalDiscountMock}.
   */
  private static SiiConfigDeactivateHandler handlerWithScheduleServiceMock(
      SiiTbaiAutoSendScheduleService mockScheduleService) throws Exception {
    SiiConfigDeactivateHandler handler = new SiiConfigDeactivateHandler();
    Field field = SiiConfigDeactivateHandler.class.getDeclaredField("scheduleService");
    field.setAccessible(true);
    field.set(handler, mockScheduleService);
    return handler;
  }

  private static OBContext mockObContextWithUserAndRole() {
    OBContext obContext = mock(OBContext.class);
    User user = mock(User.class);
    when(user.getId()).thenReturn(USER_ID);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(ROLE_ID);
    when(obContext.getUser()).thenReturn(user);
    when(obContext.getRole()).thenReturn(role);
    return obContext;
  }

  @SuppressWarnings("rawtypes")
  private static NativeQuery stubInSiiSystemNativeQuery(OBDal dal) {
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery nq = mock(NativeQuery.class);
    when(session.createNativeQuery(Mockito.anyString())).thenReturn(nq);
    when(nq.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nq);
    return nq;
  }

  /** A POST that creates an active SII config triggers the auto-send schedule. */
  @Test
  public void afterHandlePostTriggersAutoSendScheduleForActiveConfig() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    JSONObject dataRow = new JSONObject().put("id", RECORD_ID);
    JSONObject response = new JSONObject().put("data", new JSONArray().put(dataRow));
    JSONObject body = new JSONObject().put("response", response);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .previousResult(new NeoResponse(201, body))
        .obContext(mockObContextWithUserAndRole())
        .build();

    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    AEATSIIConfig config = mock(AEATSIIConfig.class);
    when(config.isActive()).thenReturn(true);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(org);

    when(scheduleService.ensureAutoSendSchedule(eq(CLIENT_ID), eq(ORG_ID), eq(USER_ID), eq(ROLE_ID),
        eq(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY), eq(AUTO_SEND_SCHEDULE_DESCRIPTION)))
        .thenReturn("req-new");

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      assertNull(handler.afterHandle(ctx));

      verify(scheduleService).ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, AUTO_SEND_SCHEDULE_DESCRIPTION);
      verify(scheduleService).activateSchedule("req-new");
    }
  }

  /**
   * A PUT that explicitly deactivates the config never reaches the CREATE-side scheduling logic
   * ({@code ensureAutoSendSchedule}/{@code activateSchedule}) — instead it takes the ETP-5117
   * follow-up cleanup branch, which calls {@code unscheduleAutoSend} instead (see the dedicated
   * cleanup tests below for its client/organization resolution rules).
   */
  @Test
  public void afterHandlePutDeactivatingDoesNotTriggerCreateSideAutoSendSchedule() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId(RECORD_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // No obContext on this NeoContext AND the record is gone — the cleanup call itself is a
      // safe no-op (see afterHandlePutDeactivatingSkipsCleanlyWhenNeitherRecordNorObContextResolve).
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(null);

      assertNull(handler.afterHandle(ctx));

      verify(scheduleService, never())
          .ensureAutoSendSchedule(any(), any(), any(), any(), any(), any());
      verify(scheduleService, never()).activateSchedule(any());
    }
  }

  // ─── ETP-5117 follow-up: unschedule cleanup runs in the PRE-hook, record-scoped ──

  /**
   * The organization id a real Etendo GO client-admin session reports: {@code '0'}, the {@code '*'}
   * org. The acting client-admin role has {@code ad_role.ad_org_id = '0'} and the user carries no
   * {@code default_ad_org_id}, so {@code OBContext.getCurrentOrganization()} is <em>never</em> the
   * business organization the config record — and therefore its auto-send schedule — belongs to.
   * Resolving the cleanup scope from the session is what made this feature silently no-op in live
   * testing while four rounds of unit tests stayed green, because those tests mocked the session
   * organization equal to the record's.
   */
  private static final String SESSION_ORG_ID = "0";

  /** A session client id deliberately different from the record's, for the same reason. */
  private static final String SESSION_CLIENT_ID = "session-client-999";

  /** A real business organization id, the kind a config record actually belongs to. */
  private static final String RECORD_ORG_ID = "C0376D5E8CFA4D4A8870D956E14CE5A4";

  /**
   * Builds an {@link OBContext} shaped like a real Etendo GO client-admin session: current
   * organization {@code '0'} (the {@code '*'} org) and a client id different from the record's.
   * Any test that resolves the cleanup scope from this context instead of from the record proves
   * nothing — the production bug is precisely that this context does not describe the record.
   */
  private static OBContext mockGoClientAdminSessionObContext() {
    OBContext session = mock(OBContext.class);
    Client sessionClient = mock(Client.class);
    when(sessionClient.getId()).thenReturn(SESSION_CLIENT_ID);
    Organization starOrg = mock(Organization.class);
    when(starOrg.getId()).thenReturn(SESSION_ORG_ID);
    when(session.getCurrentClient()).thenReturn(sessionClient);
    when(session.getCurrentOrganization()).thenReturn(starOrg);
    return session;
  }

  /** An {@link AEATSIIConfig} that answers for its own client/organization. */
  private static AEATSIIConfig mockConfigOwnedBy(String clientId, String orgId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    AEATSIIConfig config = mock(AEATSIIConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(org);
    return config;
  }

  /**
   * THE regression test for the live bug. A deactivating PUT arrives on a GO client-admin session
   * whose current organization is {@code '0'} and whose current client differs from the record's;
   * the config record itself belongs to a business organization. The schedule must be removed with
   * the <b>record's</b> client/organization, and the session's must never be consulted at all.
   */
  @Test
  public void handlePutDeactivatingUnschedulesWithTheRecordScopeNotTheSessionOrg() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    OBContext session = mockGoClientAdminSessionObContext();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId(RECORD_ID)
        .obContext(session)
        .build();

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, RECORD_ORG_ID);
    when(config.getFechaAcogidaSII()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      // The record's own scope — the only one that can match the schedule row.
      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, RECORD_ORG_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      // Never the session's client, never the session's org '0'.
      verify(scheduleService, never()).unscheduleAutoSend(eq(SESSION_CLIENT_ID), any(), any());
      verify(scheduleService, never()).unscheduleAutoSend(any(), eq(SESSION_ORG_ID), any());
      // Stronger still: the session context is not even asked.
      verify(session, never()).getCurrentOrganization();
      verify(session, never()).getCurrentClient();
    }
  }

  /**
   * Same regression, DELETE entry point: GO's "Eliminar" action sends a real {@code DELETE}, which
   * never reaches {@code smartDeactivate}. The {@code beforeDelete} pre-hook must resolve the scope
   * from the record while it still exists, never from the session's {@code '0'} organization.
   */
  @Test
  public void handleDeleteUnschedulesWithTheRecordScopeNotTheSessionOrg() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    OBContext session = mockGoClientAdminSessionObContext();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE")
        .recordId(RECORD_ID)
        .obContext(session)
        .build();

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, RECORD_ORG_ID);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      // null so NEO's default CRUD still performs the delete.
      assertNull(handler.handle(ctx));

      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, RECORD_ORG_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      verify(scheduleService, never()).unscheduleAutoSend(eq(SESSION_CLIENT_ID), any(), any());
      verify(scheduleService, never()).unscheduleAutoSend(any(), eq(SESSION_ORG_ID), any());
      verify(session, never()).getCurrentOrganization();
      verify(session, never()).getCurrentClient();
    }
  }

  /**
   * Deactivating PUT, delete outcome (no acogida date): the schedule is removed <em>before</em> the
   * record is removed. Ordering is the whole point — after the removal the record can no longer
   * answer for its client/organization, which is exactly how the old design ended up guessing.
   */
  @Test
  public void handlePutDeactivatingUnschedulesBeforeRemovingTheRecord() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getFechaAcogidaSII()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      assertNotNull(result);
      assertEquals(true, result.getBody().getBoolean("deleted"));

      InOrder ordered = inOrder(scheduleService, dal);
      ordered.verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      ordered.verify(dal).remove(config);
      ordered.verify(dal).flush();
    }
  }

  /**
   * Deactivating PUT, fall-through outcome (acogida date set AND SII invoices exist, so the record
   * survives and default CRUD deactivates it): the schedule is still removed, and
   * {@code smartDeactivate} still returns {@code null} so the fall-through happens.
   */
  @Test
  public void handlePutDeactivatingFallThroughStillUnschedulesAndReturnsNull() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getFechaAcogidaSII()).thenReturn(new Date());

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<AEATSIIFacturas> crit = mock(OBCriteria.class);
      when(dal.createCriteria(AEATSIIFacturas.class)).thenReturn(crit);
      when(crit.createAlias(Mockito.anyString(), Mockito.anyString())).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(7L);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build()));

      // Both outcomes of smartDeactivate unschedule — this is the fall-through one.
      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      verify(dal, never()).remove(any());
    }
  }

  /**
   * A genuine {@code DELETE} must leave NEO's default CRUD alone: {@code handle()} returns
   * {@code null} so the hard delete still happens, and the schedule is removed with the record's
   * own client/organization on the way there.
   */
  @Test
  public void handleDeleteReturnsNullSoDefaultCrudProceedsAndUnschedulesRecordScope()
      throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .build()));

      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      // The pre-hook never deletes anything itself — default CRUD owns the delete.
      verify(dal, never()).remove(any());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * A {@code DELETE} with a blank/absent record id: nothing to look up and nothing to unschedule.
   * The guard fires before admin mode is even entered, and {@code handle()} still returns
   * {@code null}.
   */
  @Test
  public void handleDeleteWithBlankRecordIdDoesNothingAtAll() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId("   ")
          .build()));
      assertNull(handler.handle(NeoContext.builder().httpMethod("DELETE").build()));

      verifyNoInteractions(scheduleService);
      verifyNoInteractions(dal);
      obCtxMock.verify(() -> OBContext.setAdminMode(true), never());
    }
  }

  /**
   * A {@code DELETE} whose record cannot be loaded (already gone, or invisible to this session):
   * the cleanup is skipped entirely rather than falling back to a guessed scope. Guessing is the
   * bug — a wrongly-scoped {@code unscheduleAutoSend} silently matches nothing and hides the
   * failure. {@code handle()} still returns {@code null}.
   */
  @Test
  public void handleDeleteWithUnloadableRecordNeverGuessesAScope() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    OBContext session = mockGoClientAdminSessionObContext();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(null);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .obContext(session)
          .build()));

      verifyNoInteractions(scheduleService);
      verify(session, never()).getCurrentOrganization();
      verify(session, never()).getCurrentClient();
    }
  }

  /**
   * The cleanup is a side effect and must never block the operation the user asked for: when
   * {@code unscheduleAutoSend} blows up during a {@code DELETE}, the exception is swallowed and
   * {@code handle()} still returns {@code null} so default CRUD deletes the record.
   */
  @Test
  public void handleDeleteSwallowsUnscheduleFailureAndStillLetsTheDeleteProceed() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);
    doThrow(new RuntimeException("scheduler down")).when(scheduleService)
        .unscheduleAutoSend(any(), any(), any());

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .build()));

      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * Same swallow contract on the deactivating-PUT entry point: an {@code unscheduleAutoSend}
   * failure must not abort {@code smartDeactivate} — the record is still deleted and the
   * {@code {"deleted":true}} response is still returned.
   */
  @Test
  public void handlePutDeactivatingSwallowsUnscheduleFailureAndStillDeletes() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);
    doThrow(new RuntimeException("scheduler down")).when(scheduleService)
        .unscheduleAutoSend(any(), any(), any());

    AEATSIIConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getFechaAcogidaSII()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
      verify(dal).remove(config);
    }
  }

  // ─── afterHandle(): no longer unschedules — the pre-hook owns cleanup now ────

  /**
   * A deactivating PUT's cleanup ran in {@code smartDeactivate}; {@code afterHandle} must not
   * repeat it (nor attempt it against a record that is typically already gone).
   */
  @Test
  public void afterHandleNoLongerUnschedulesOnDeactivatingPut() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId(RECORD_ID)
        .obContext(mockGoClientAdminSessionObContext())
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(handler.afterHandle(ctx));

      verify(scheduleService, never()).unscheduleAutoSend(any(), any(), any());
      verifyNoInteractions(scheduleService);
    }
  }

  /**
   * A genuine {@code DELETE}'s cleanup ran in {@code beforeDelete}; {@code afterHandle} short
   * circuits with no schedule-service interaction at all.
   */
  @Test
  public void afterHandleNoLongerUnschedulesOnDelete() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE")
        .recordId(RECORD_ID)
        .obContext(mockGoClientAdminSessionObContext())
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(handler.afterHandle(ctx));

      verify(scheduleService, never()).unscheduleAutoSend(any(), any(), any());
      verifyNoInteractions(scheduleService);
      verifyNoInteractions(dal);
      obCtxMock.verify(() -> OBContext.setAdminMode(true), never());
    }
  }

  /**
   * Regression/completeness check on the mutual-exclusivity claim in {@code afterHandle}: an
   * unrelated method (GET) is neither POST, PUT nor DELETE, so it short-circuits immediately with
   * no side effects at all — no schedule creation, no cleanup.
   */
  @Test
  public void afterHandleGetMethodShortCircuitsWithNoSideEffects() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder().httpMethod("GET").recordId(RECORD_ID).build();

    assertNull(handler.afterHandle(ctx));

    verifyNoInteractions(scheduleService);
  }

  /**
   * A PUT that leaves the config active triggers the auto-send schedule AND the pre-existing
   * {@code INSIISYSTEM='Y'} native-SQL update still runs — regression guard confirming ETP-5117
   * did not disturb the ETP-4783 logic.
   */
  @Test
  public void afterHandlePutNonDeactivatingTriggersAutoSendScheduleAndKeepsInsiiSystemLogic()
      throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("name", "foo"))
        .recordId(RECORD_ID)
        .obContext(mockObContextWithUserAndRole())
        .build();

    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    AEATSIIConfig config = mock(AEATSIIConfig.class);
    when(config.isActive()).thenReturn(true);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(org);

    when(scheduleService.ensureAutoSendSchedule(eq(CLIENT_ID), eq(ORG_ID), eq(USER_ID), eq(ROLE_ID),
        eq(SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY), eq(AUTO_SEND_SCHEDULE_DESCRIPTION)))
        .thenReturn("req-new");

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);
      @SuppressWarnings("rawtypes")
      NativeQuery nq = stubInSiiSystemNativeQuery(dal);

      assertNull(handler.afterHandle(ctx));

      verify(scheduleService).ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, AUTO_SEND_SCHEDULE_DESCRIPTION);
      verify(scheduleService).activateSchedule("req-new");
      // Regression: the INSIISYSTEM native update still fires for a non-deactivating PUT.
      verify(nq).setParameter(eq("id"), eq(RECORD_ID));
      verify(nq).executeUpdate();
      // Regression (ETP-5117 follow-up): a non-deactivating save never triggers the cleanup path.
      verify(scheduleService, never()).unscheduleAutoSend(any(), any(), any());
    }
  }

  /**
   * A config that resolves {@code isActive() == false} after load must not get a schedule, even
   * when the PUT body itself did not explicitly set {@code active=false} (e.g. some other flow
   * already deactivated it). The pre-existing INSIISYSTEM update is unaffected since it is not
   * gated by the active flag.
   */
  @Test
  public void afterHandleDoesNotScheduleWhenConfigIsInactiveAfterLoad() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("name", "foo"))
        .recordId(RECORD_ID)
        .obContext(mockObContextWithUserAndRole())
        .build();

    AEATSIIConfig config = mock(AEATSIIConfig.class);
    when(config.isActive()).thenReturn(false);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(AEATSIIConfig.class), eq(RECORD_ID))).thenReturn(config);
      @SuppressWarnings("rawtypes")
      NativeQuery nq = stubInSiiSystemNativeQuery(dal);

      assertNull(handler.afterHandle(ctx));

      verifyNoInteractions(scheduleService);
      // Regression: INSIISYSTEM update still runs regardless of the config's active flag.
      verify(nq).setParameter(eq("id"), eq(RECORD_ID));
      verify(nq).executeUpdate();
    }
  }
}

