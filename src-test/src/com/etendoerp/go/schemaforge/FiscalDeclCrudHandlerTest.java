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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link FiscalDeclCrudHandler}'s AEAT incidents read/write path (ETP-4456):
 * {@code GET /fiscal303/incidents}, {@link FiscalDeclCrudHandler#replaceIncidents} and the
 * {@code "CODE - message"} parsing helper {@link FiscalDeclCrudHandler#splitAeatError}.
 *
 * <p>The physical {@code ETGO_Fiscal_Decl_Incident} table is not exercised here — all DAL calls
 * are mocked, matching the existing convention in {@link AbstractFiscalHandlerTest} and
 * {@code Fiscal303SubmitHandlerTest}.</p>
 */
public class FiscalDeclCrudHandlerTest {

  private NeoServlet servlet;
  private FiscalDeclCrudHandler handler;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    handler = new FiscalDeclCrudHandler(servlet);
  }

  // ── splitAeatError ────────────────────────────────────────────────

  @Test
  public void testSplitAeatErrorNumericCode() {
    String[] parts = FiscalDeclCrudHandler.splitAeatError(
        "35068 - El resultado a ingresar es distinto de cero. Hay que indicar la forma de pago.");
    assertArrayEquals(
        new String[] { "35068",
            "El resultado a ingresar es distinto de cero. Hay que indicar la forma de pago." },
        parts);
  }

  @Test
  public void testSplitAeatErrorAlphanumericCode() {
    String[] parts = FiscalDeclCrudHandler.splitAeatError(
        "E010124 - Para periodo mensual de 01 a 11 debe estar Inscrito en el Registro.");
    assertArrayEquals(
        new String[] { "E010124", "Para periodo mensual de 01 a 11 debe estar Inscrito en el Registro." },
        parts);
  }

  @Test
  public void testSplitAeatErrorNoDashFallsBackToEmptyCode() {
    String[] parts = FiscalDeclCrudHandler.splitAeatError("A message with no code prefix");
    assertArrayEquals(new String[] { "", "A message with no code prefix" }, parts);
  }

  @Test
  public void testSplitAeatErrorNullReturnsEmptyPair() {
    assertArrayEquals(new String[] { "", "" }, FiscalDeclCrudHandler.splitAeatError(null));
  }

  @Test
  public void testSplitAeatErrorTrimsWhitespace() {
    String[] parts = FiscalDeclCrudHandler.splitAeatError("  35068 - trimmed message  ");
    assertArrayEquals(new String[] { "35068", "trimmed message" }, parts);
  }

  // ── handleIncidents (read path) ───────────────────────────────────

  @Test
  public void testHandleIncidentsRejectsNonGet() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    handler.handleIncidents("POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  @Test
  public void testHandleIncidentsMissingIdReturns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn(null);

    handler.handleIncidents("GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testHandleIncidentsDeclarationNotFoundReturns404() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(null);

      handler.handleIncidents("GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  @Test
  public void testHandleIncidentsWrongOrgReturns404() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");

    BaseOBObject decl = mock(BaseOBObject.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    Organization otherOrg = mock(Organization.class);
    when(otherOrg.getId()).thenReturn("some-other-org");
    when(decl.get("client")).thenReturn(client);
    when(decl.get("organization")).thenReturn(otherOrg);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleIncidents("GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleIncidentsReturnsPersistedRows() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");

    BaseOBObject decl = mock(BaseOBObject.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org1");
    when(decl.get("client")).thenReturn(client);
    when(decl.get("organization")).thenReturn(org);

    BaseOBObject inc = mock(BaseOBObject.class);
    when(inc.get(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE)).thenReturn("35068");
    when(inc.get(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE))
        .thenReturn("El resultado a ingresar es distinto de cero.");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(inc));

      handler.handleIncidents("GET", req, resp);
    }

    String body = sw.toString();
    assertTrue(body.contains("35068"));
    assertTrue(body.contains("El resultado a ingresar es distinto de cero."));
  }

  // ── replaceIncidents (write path) ─────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsDeletesExistingThenInsertsParsedErrors() {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    BaseOBObject staleInc = mock(BaseOBObject.class);
    BaseOBObject newInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(staleInc));

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      List<String> errors = Arrays.asList(
          "35068 - El resultado a ingresar es distinto de cero.",
          "E010124 - Para periodo mensual de 01 a 11.");

      handler.replaceIncidents(decl, errors);

      verify(obDal).remove(staleInc);
      verify(obDal, times(2)).save(newInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "35068");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "El resultado a ingresar es distinto de cero.");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "E010124");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "Para periodo mensual de 01 a 11.");
      verify(newInc, times(2)).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_DECL, decl);
      verify(obDal).commitAndClose();
    }
  }

  /**
   * A successful submission carries an empty {@code errors} list — the declaration must end up
   * with zero incident rows: only the delete step runs, nothing is (re)inserted.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsEmptyErrorsOnlyDeletesNoInsert() {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    BaseOBObject staleInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(staleInc));

      handler.replaceIncidents(decl, Collections.emptyList());

      verify(obDal).remove(staleInc);
      verify(obDal, never()).save(any());
      verify(obDal).commitAndClose();
    }
  }

  /**
   * A second, different set of errors must fully replace the first — never accumulate. Modeled
   * here as two independent {@code replaceIncidents} calls (mirroring two separate submission
   * attempts); each call deletes whatever {@code queryIncidents} currently returns before
   * inserting the new set, so nothing from the first attempt can survive into the second.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsSecondAttemptReplacesFirst() {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    BaseOBObject firstAttemptInc = mock(BaseOBObject.class);
    BaseOBObject newInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(query);
      // Simulates the row already persisted by a prior attempt being present when the second
      // attempt's replaceIncidents runs.
      when(query.list()).thenReturn(Collections.singletonList(firstAttemptInc));

      handler.replaceIncidents(decl, Collections.singletonList("35100 - new error this attempt"));

      verify(obDal).remove(firstAttemptInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "35100");
    }
  }

  // ── helpers ────────────────────────────────────────────────────────

  private static void mockContext(MockedStatic<OBContext> ctxMock, String clientId, String orgId) {
    OBContext ctx = mock(OBContext.class);
    ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(ctx.getCurrentClient()).thenReturn(client);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(ctx.getCurrentOrganization()).thenReturn(org);
  }
}
