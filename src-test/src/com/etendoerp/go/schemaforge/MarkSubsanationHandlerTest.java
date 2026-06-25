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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link MarkSubsanationHandler}.
 *
 * <p>Covers the pure-logic guard paths (method filter, isSubsanation flag, blank recordId)
 * and the four DB-driven branches (VF not found, invoice not updated, success, exception).
 */
public class MarkSubsanationHandlerTest {

  private final MarkSubsanationHandler handler = new MarkSubsanationHandler();

  // ─── method guard ────────────────────────────────────────────────────────────

  @Test
  public void handleReturnsNullForGetRequest() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullForDeleteRequest() {
    NeoContext ctx = NeoContext.builder().httpMethod("DELETE").build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullForPostRequest() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
    assertNull(handler.handle(ctx));
  }

  // ─── isSubsanation=false guard ───────────────────────────────────────────────

  @Test
  public void handleReturnsNullWhenIsSubsanationIsFalse() throws Exception {
    JSONObject body = new JSONObject();
    body.put("isSubsanation", false);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullWhenIsSubsanationIsFalseViaPatch() throws Exception {
    JSONObject body = new JSONObject();
    body.put("isSubsanation", false);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── recordId blank guard ────────────────────────────────────────────────────

  @Test
  public void handleReturnsBadRequestWhenRecordIdIsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId(null)
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleReturnsBadRequestWhenRecordIdIsBlank() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId("   ")
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleReturnsBadRequestWhenBodyHasNoIsSubsanationAndRecordIdIsBlank() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject())
        .recordId("")
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleContinuesWhenIsSubsanationIsNonBooleanThenReturnsBadRequestForBlankId()
      throws Exception {
    // Non-boolean value causes JSONException → fall through; blank recordId → 400.
    JSONObject body = new JSONObject();
    body.put("isSubsanation", "maybe");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .requestBody(body)
        .recordId("")
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  // ─── DB-driven paths (OBDal mocked) ──────────────────────────────────────────

  @Test
  public void handleReturnsNotFoundWhenVFRecordNotFound() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId("vf-record-001")
        .build();

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

      NeoResponse response = handler.handle(ctx);
      assertEquals(404, response.getHttpStatus());
    }
  }

  @Test
  public void handleReturnsNotFoundWhenInvoiceNotUpdated() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId("vf-record-001")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

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
      when(nqSelect.uniqueResult()).thenReturn("invoice-001");

      when(nqUpdate.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqUpdate);
      when(nqUpdate.executeUpdate()).thenReturn(0);

      NeoResponse response = handler.handle(ctx);
      assertEquals(404, response.getHttpStatus());
    }
  }

  @Test
  public void handleReturnsNoContentOnSuccess() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId("vf-record-001")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

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
      when(nqSelect.uniqueResult()).thenReturn("invoice-001");

      when(nqUpdate.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(nqUpdate);
      when(nqUpdate.executeUpdate()).thenReturn(1);

      NeoResponse response = handler.handle(ctx);
      assertEquals(204, response.getHttpStatus());
    }
  }

  @Test
  public void handleReturnsInternalErrorOnException() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .recordId("vf-record-001")
        .build();

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

      NeoResponse response = handler.handle(ctx);
      assertEquals(500, response.getHttpStatus());
    }
  }
}
