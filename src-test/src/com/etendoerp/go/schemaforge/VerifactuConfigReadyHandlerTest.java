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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link VerifactuConfigReadyHandler}.
 *
 * <p>Covers method-guard routing in {@link VerifactuConfigReadyHandler#afterHandle}, id
 * resolution for both PUT/PATCH (from the URL) and POST (from the just-committed CRUD
 * response envelope), the idempotency of {@link VerifactuConfigReadyHandler#markReadyIfNeeded}
 * (fully-adopted records — {@code is_ready='Y'} AND {@code in_vfactu_system} set — are left
 * untouched, while legacy/partially-migrated records with {@code is_ready='Y'} but a
 * {@code null} adoption date are backfilled), and that any exception raised while applying the
 * auto-fill is swallowed rather than propagated or turned into an error response.
 */
public class VerifactuConfigReadyHandlerTest {

  private static final String RECORD_ID = "vfconfig-001";

  // ─── afterHandle: method guard ───────────────────────────────────────────────

  @Test
  public void afterHandleReturnsNullForGetRequest() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).markReadyIfNeeded(any());
  }

  @Test
  public void afterHandleReturnsNullForDeleteRequest() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    NeoContext ctx = NeoContext.builder().httpMethod("DELETE").build();

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).markReadyIfNeeded(any());
  }

  // ─── afterHandle: id resolution ──────────────────────────────────────────────

  @Test
  public void afterHandleUsesRecordIdForPut() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    doNothing().when(handler).markReadyIfNeeded(RECORD_ID);
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").recordId(RECORD_ID).build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));
      verify(handler, times(1)).markReadyIfNeeded(RECORD_ID);
    }
  }

  @Test
  public void afterHandleUsesRecordIdForPatch() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    doNothing().when(handler).markReadyIfNeeded(RECORD_ID);
    NeoContext ctx = NeoContext.builder().httpMethod("PATCH").recordId(RECORD_ID).build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));
      verify(handler, times(1)).markReadyIfNeeded(RECORD_ID);
    }
  }

  @Test
  public void afterHandleSkipsWhenRecordIdBlankForPut() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").recordId("  ").build();

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).markReadyIfNeeded(any());
  }

  @Test
  public void afterHandlePostResolvesIdFromDataArrayEnvelope() throws Exception {
    JSONObject dataRow = new JSONObject().put("id", RECORD_ID);
    JSONObject response = new JSONObject().put("data", new JSONArray().put(dataRow));
    JSONObject body = new JSONObject().put("response", response);

    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    doNothing().when(handler).markReadyIfNeeded(RECORD_ID);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .previousResult(new NeoResponse(201, body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));
      verify(handler, times(1)).markReadyIfNeeded(RECORD_ID);
    }
  }

  @Test
  public void afterHandlePostResolvesIdFromDataObjectEnvelope() throws Exception {
    JSONObject dataRow = new JSONObject().put("id", RECORD_ID);
    JSONObject response = new JSONObject().put("data", dataRow);
    JSONObject body = new JSONObject().put("response", response);

    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    doNothing().when(handler).markReadyIfNeeded(RECORD_ID);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .previousResult(new NeoResponse(201, body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));
      verify(handler, times(1)).markReadyIfNeeded(RECORD_ID);
    }
  }

  @Test
  public void afterHandlePostSkipsWhenPreviousResultMissing() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).markReadyIfNeeded(any());
  }

  @Test
  public void afterHandlePostSkipsWhenResponseHasNoId() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject());
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .previousResult(new NeoResponse(201, body))
        .build();

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).markReadyIfNeeded(any());
  }

  // ─── afterHandle: failures are swallowed (best-effort side effect) ──────────

  @Test
  public void afterHandleSwallowsExceptionFromMarkReadyIfNeeded() {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    doThrow(new RuntimeException("boom")).when(handler).markReadyIfNeeded(RECORD_ID);
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").recordId(RECORD_ID).build();

    assertNull(handler.afterHandle(ctx));
  }

  // ─── handle(): smart deactivation dispatch guards ────────────────────────────

  @Test
  public void handleReturnsNullForGetMethod() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("GET").build()));
  }

  @Test
  public void handleReturnsNullForPostMethod() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("POST").build()));
  }

  @Test
  public void handleReturnsNullForPatchMethod() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("PATCH").build()));
  }

  @Test
  public void handleReturnsNullWhenBodyHasNoActiveField() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("name", "foo"))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenBodyIsNull() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenActiveIsTrue() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", true))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenRecordIdIsBlank() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId("   ")
        .build()));
  }

  // ─── handle(): isExplicitlyDeactivating edge cases ───────────────────────────

  @Test
  public void handleRecognizesBooleanFalseAsDeactivating() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nq = mock(NativeQuery.class);
      when(session.createNativeQuery(Mockito.anyString())).thenReturn(nq);
      when(nq.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nq);
      // Record not found — simplest path that confirms handle() was entered.
      when(nq.uniqueResult()).thenReturn(null);

      // Boolean false → isExplicitlyDeactivating returns true → handle() proceeds
      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());
      // null because record not found in smartDeactivate
      assertNull(result);
    }
  }

  @Test
  public void handleRecognizesStringFalseAsDeactivating() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nq = mock(NativeQuery.class);
      when(session.createNativeQuery(Mockito.anyString())).thenReturn(nq);
      when(nq.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", "false"))
          .recordId(RECORD_ID)
          .build());
      assertNull(result);
    }
  }

  @Test
  public void handleRecognizesStringNAsDeactivating() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nq = mock(NativeQuery.class);
      when(session.createNativeQuery(Mockito.anyString())).thenReturn(nq);
      when(nq.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", "N"))
          .recordId(RECORD_ID)
          .build());
      assertNull(result);
    }
  }

  // ─── handle(): smartDeactivate scenarios ─────────────────────────────────────

  @Test
  public void smartDeactivateReturnsNullWhenConfigNotFound() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nq = mock(NativeQuery.class);
      when(session.createNativeQuery(Mockito.contains("SELECT"))).thenReturn(nq);
      when(nq.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nq);
      when(nq.uniqueResult()).thenReturn(null);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);
      assertNull(result);
    }
  }

  @Test
  public void smartDeactivateDeletesAndReturnsDeletedWhenAdoptionDateIsNull() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqDelete = mock(NativeQuery.class);

      when(session.createNativeQuery(Mockito.contains("SELECT"))).thenReturn(nqSelect);
      when(session.createNativeQuery(Mockito.contains("DELETE"))).thenReturn(nqDelete);

      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      // row[1] (adoptionDate) is null — config never entered the fiscal system
      when(nqSelect.uniqueResult()).thenReturn(new Object[] { "N", null, "org-001" });

      when(nqDelete.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqDelete);
      when(nqDelete.executeUpdate()).thenReturn(1);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      verify(session).createNativeQuery(Mockito.contains("DELETE"));
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  @Test
  public void smartDeactivateReturns400WhenPendingInvoicesExist() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    java.sql.Timestamp adoptionDate = java.sql.Timestamp.valueOf("2025-01-01 00:00:00");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {

      msgMock.when(() -> OBMessageUtils.messageBD(Mockito.anyString()))
          .thenReturn("Cannot deactivate: pending invoices");

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqPending = mock(NativeQuery.class);

      when(session.createNativeQuery(Mockito.contains("SELECT is_ready"))).thenReturn(nqSelect);
      when(session.createNativeQuery(Mockito.contains("em_etvfac_invoice_status"))).thenReturn(nqPending);

      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      when(nqSelect.uniqueResult()).thenReturn(new Object[] { "Y", adoptionDate, "org-001" });

      when(nqPending.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqPending);
      // pending invoices count > 0
      when(nqPending.uniqueResult()).thenReturn(3L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
    }
  }

  @Test
  public void smartDeactivateDeletesWhenAdoptionDateSetButNoVerifactuInvoices() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    java.sql.Timestamp adoptionDate = java.sql.Timestamp.valueOf("2025-01-01 00:00:00");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqPending = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqDelete = mock(NativeQuery.class);

      when(session.createNativeQuery(Mockito.contains("SELECT is_ready"))).thenReturn(nqSelect);
      when(session.createNativeQuery(Mockito.contains("em_etvfac_invoice_status"))).thenReturn(nqPending);
      when(session.createNativeQuery(Mockito.contains("DELETE"))).thenReturn(nqDelete);

      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      when(nqSelect.uniqueResult()).thenReturn(new Object[] { "Y", adoptionDate, "org-001" });

      when(nqPending.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqPending);
      // no pending invoices
      when(nqPending.uniqueResult()).thenReturn(0L);

      when(nqDelete.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqDelete);
      when(nqDelete.executeUpdate()).thenReturn(1);

      // No sent invoices via OBCriteria — mock createCriteria to return count 0
      @SuppressWarnings("unchecked")
      OBCriteria<Invoice> crit = mock(OBCriteria.class);
      when(dal.createCriteria(Invoice.class)).thenReturn(crit);
      when(crit.add(Mockito.any())).thenReturn(crit);
      when(crit.setProjection(Mockito.any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(0L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      verify(session).createNativeQuery(Mockito.contains("DELETE"));
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  @Test
  public void smartDeactivateReturnsNullWhenVerifactuInvoicesExist() throws Exception {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    java.sql.Timestamp adoptionDate = java.sql.Timestamp.valueOf("2025-01-01 00:00:00");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqPending = mock(NativeQuery.class);

      when(session.createNativeQuery(Mockito.contains("SELECT is_ready"))).thenReturn(nqSelect);
      when(session.createNativeQuery(Mockito.contains("em_etvfac_invoice_status"))).thenReturn(nqPending);

      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      when(nqSelect.uniqueResult()).thenReturn(new Object[] { "Y", adoptionDate, "org-001" });

      when(nqPending.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqPending);
      // no pending invoices
      when(nqPending.uniqueResult()).thenReturn(0L);

      // Sent invoices exist via OBCriteria — count > 0
      @SuppressWarnings("unchecked")
      OBCriteria<Invoice> crit = mock(OBCriteria.class);
      when(dal.createCriteria(Invoice.class)).thenReturn(crit);
      when(crit.add(Mockito.any())).thenReturn(crit);
      when(crit.setProjection(Mockito.any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(5L);

      NeoResponse result = handler.smartDeactivate(RECORD_ID);

      // Has sent invoices → fallthrough to default CRUD deactivation
      assertNull(result);
      // DELETE must NOT have been called
      verify(session, never()).createNativeQuery(Mockito.contains("DELETE"));
    }
  }

  // ─── afterHandle: skips markReadyIfNeeded when handle() already deleted ──────

  @Test
  public void afterHandleSkipsMarkReadyWhenPreResultIndicatesDeleted() throws Exception {
    VerifactuConfigReadyHandler handler = spy(new VerifactuConfigReadyHandler());
    JSONObject body = new JSONObject().put("deleted", true);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .previousResult(new NeoResponse(200, body))
        .build();

    assertNull(handler.afterHandle(ctx));
    verify(handler, never()).markReadyIfNeeded(any());
  }

  // ─── markReadyIfNeeded (real method, OBDal/OBContext mocked statically) ─────

  @Test
  public void markReadyIfNeededSetsReadyAndDateWhenNotYetReady() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqUpdate = mock(NativeQuery.class);

      when(session.createNativeQuery(Mockito.contains("SELECT"))).thenReturn(nqSelect);
      when(session.createNativeQuery(Mockito.contains("UPDATE"))).thenReturn(nqUpdate);

      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      when(nqSelect.uniqueResult()).thenReturn(new Object[] { "N", null });

      when(nqUpdate.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqUpdate);
      when(nqUpdate.executeUpdate()).thenReturn(1);

      handler.markReadyIfNeeded(RECORD_ID);

      verify(session, times(1)).createNativeQuery(Mockito.contains("UPDATE"));
      verify(dal, times(1)).flush();
    }
  }

  @Test
  public void markReadyIfNeededIsIdempotentWhenAlreadyReady() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      when(session.createNativeQuery(Mockito.contains("SELECT"))).thenReturn(nqSelect);
      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      when(nqSelect.uniqueResult())
          .thenReturn(new Object[] { "Y", java.sql.Timestamp.valueOf("2026-01-01 00:00:00") });

      handler.markReadyIfNeeded(RECORD_ID);

      verify(session, never()).createNativeQuery(Mockito.contains("UPDATE"));
      verify(dal, never()).flush();
    }
  }

  @Test
  public void markReadyIfNeededBackfillsAdoptionDateForLegacyReadyRecord() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      @SuppressWarnings("rawtypes")
      NativeQuery nqUpdate = mock(NativeQuery.class);

      when(session.createNativeQuery(Mockito.contains("SELECT"))).thenReturn(nqSelect);
      when(session.createNativeQuery(Mockito.contains("UPDATE"))).thenReturn(nqUpdate);

      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      // Legacy/partially-migrated record: is_ready='Y' but in_vfactu_system is still null.
      when(nqSelect.uniqueResult()).thenReturn(new Object[] { "Y", null });

      when(nqUpdate.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqUpdate);
      when(nqUpdate.executeUpdate()).thenReturn(1);

      handler.markReadyIfNeeded(RECORD_ID);

      verify(session, times(1)).createNativeQuery(Mockito.contains("UPDATE"));
      verify(dal, times(1)).flush();
    }
  }

  @Test
  public void markReadyIfNeededSkipsWhenRecordNotFound() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("rawtypes")
      NativeQuery nqSelect = mock(NativeQuery.class);
      when(session.createNativeQuery(Mockito.contains("SELECT"))).thenReturn(nqSelect);
      when(nqSelect.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqSelect);
      when(nqSelect.uniqueResult()).thenReturn(null);

      handler.markReadyIfNeeded(RECORD_ID);

      verify(session, never()).createNativeQuery(Mockito.contains("UPDATE"));
      verify(dal, never()).flush();
    }
  }

  @Test
  public void afterHandleUnderAdminModeSwallowsSqlException() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").recordId(RECORD_ID).build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createNativeQuery(Mockito.anyString()))
          .thenThrow(new RuntimeException("DB unavailable"));

      assertEquals(null, handler.afterHandle(ctx));
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }
}
