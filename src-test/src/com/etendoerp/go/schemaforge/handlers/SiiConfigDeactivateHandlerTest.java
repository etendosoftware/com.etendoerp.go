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

  /** A PUT that explicitly deactivates the config never reaches the scheduling logic. */
  @Test
  public void afterHandlePutDeactivatingDoesNotTriggerAutoSendSchedule() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    SiiConfigDeactivateHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId(RECORD_ID)
        .build();

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
