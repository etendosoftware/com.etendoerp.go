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
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link VerifactuConfigReadyHandler}.
 *
 * <p>Covers method-guard routing in {@link VerifactuConfigReadyHandler#afterHandle}, id
 * resolution for both PUT/PATCH (from the URL) and POST (from the just-committed CRUD
 * response envelope), the idempotency of {@link VerifactuConfigReadyHandler#markReadyIfNeeded}
 * (already-ready records are left untouched), and that any exception raised while applying the
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

  @Test
  public void handleAlwaysReturnsNull() {
    VerifactuConfigReadyHandler handler = new VerifactuConfigReadyHandler();
    assertNull(handler.handle(NeoContext.builder().httpMethod("POST").build()));
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
      when(nqSelect.uniqueResult()).thenReturn("N");

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
      when(nqSelect.uniqueResult()).thenReturn("Y");

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
