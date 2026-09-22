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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
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
    when(inc.get(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY))
        .thenReturn(FiscalDeclCrudHandler.SEVERITY_WARN);

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
    assertTrue(body.contains(FiscalDeclCrudHandler.SEVERITY_WARN));
  }

  /**
   * A row persisted before ETP-4456 (or one where {@code severity} was otherwise never set) must
   * default to {@link FiscalDeclCrudHandler#SEVERITY_BLOCK} in the API response — preserves the
   * pre-existing "every row is an error" assumption for data that predates this column.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleIncidentsDefaultsMissingSeverityToBlock() throws Exception {
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
    when(inc.get(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE)).thenReturn("Legacy row.");
    when(inc.get(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY)).thenReturn(null);

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
    assertTrue(body.contains(FiscalDeclCrudHandler.SEVERITY_BLOCK));
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

      handler.replaceIncidents(decl, errors, Collections.emptyList());

      verify(obDal).remove(staleInc);
      verify(obDal, times(2)).save(newInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "35068");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "El resultado a ingresar es distinto de cero.");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "E010124");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "Para periodo mensual de 01 a 11.");
      verify(newInc, times(2)).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_DECL, decl);
      verify(newInc, times(2)).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_BLOCK);
      verify(obDal).commitAndClose();
    }
  }

  /**
   * A successful submission carries empty {@code errors} AND {@code warnings} lists — the
   * declaration must end up with zero incident rows: only the delete step runs, nothing is
   * (re)inserted. Covers the case where a PRIOR attempt left both blocking and non-blocking rows
   * behind and the new, clean attempt must clear both, not just one severity.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsEmptyErrorsAndWarningsOnlyDeletesNoInsert() {
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

      handler.replaceIncidents(decl, Collections.emptyList(), Collections.emptyList());

      verify(obDal).remove(staleInc);
      verify(obDal, never()).save(any());
      verify(obDal).commitAndClose();
    }
  }

  /**
   * A submission that returns BOTH AEAT errors and warnings must persist both groups, each row
   * tagged with the correct severity ({@link FiscalDeclCrudHandler#SEVERITY_BLOCK} for errors,
   * {@link FiscalDeclCrudHandler#SEVERITY_WARN} for warnings) — the core behavior added in
   * ETP-4456 to stop discarding AEAT's warnings list on every submission.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsPersistsErrorsAndWarningsWithDistinctSeverities() {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
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
      when(query.list()).thenReturn(Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      List<String> errors = Collections.singletonList("35068 - El resultado a ingresar.");
      List<String> warnings = Collections.singletonList("A001 - Aviso informativo AEAT.");

      handler.replaceIncidents(decl, errors, warnings);

      verify(obDal, times(2)).save(newInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "35068");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_BLOCK);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "A001");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "Aviso informativo AEAT.");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_WARN);
      verify(obDal).commitAndClose();
    }
  }

  /**
   * An error and a warning that happen to share the EXACT same raw {@code "CODE - message"} text
   * must NOT be collapsed into a single row — {@link FiscalDeclCrudHandler#replaceIncidents}
   * dedupes within each severity group independently, never across groups. Two rows are expected:
   * one {@code block}, one {@code warn}.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsDoesNotDedupeAcrossSeverityGroups() {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
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
      when(query.list()).thenReturn(Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      String sameText = "35068 - El resultado a ingresar es distinto de cero.";

      handler.replaceIncidents(decl,
          Collections.singletonList(sameText), Collections.singletonList(sameText));

      // One row per severity group -> 2 total, even though the raw text is identical.
      verify(obDal, times(2)).save(newInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_BLOCK);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_WARN);
    }
  }

  /**
   * Within a single severity group, duplicate raw strings are still deduped (pre-existing
   * behavior, now verified per-group rather than globally).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testReplaceIncidentsDedupesWithinSameSeverityGroup() {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
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
      when(query.list()).thenReturn(Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      String sameText = "E010063 - Duplicated warning text.";

      handler.replaceIncidents(decl,
          Collections.emptyList(), Arrays.asList(sameText, sameText));

      verify(obDal, times(1)).save(newInc);
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

      handler.replaceIncidents(decl,
          Collections.singletonList("35100 - new error this attempt"), Collections.emptyList());

      verify(obDal).remove(firstAttemptInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "35100");
    }
  }

  // ── handleDeclPut (manualData) ─────────────────────────────────────

  /**
   * A valid {@code manualData} nested object in the PUT body must be persisted as its compact
   * JSON string via {@code decl.set(PROPERTY_MANUAL_DATA, ...)}.
   */
  @Test
  public void testHandleDeclPutWithManualDataSetsPropertyAsCompactJsonString() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    String requestBody = "{\"manualData\":{\"identification\":{\"nifOk\":true},"
        + "\"manualOverrides\":{\"box01\":\"100.00\"}}}";
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(decl).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), captor.capture());
    JSONObject stored = new JSONObject((String) captor.getValue());
    assertTrue(stored.getJSONObject("identification").getBoolean("nifOk"));
    assertEquals("100.00", stored.getJSONObject("manualOverrides").getString("box01"));
  }

  /**
   * A PUT body that never mentions {@code manualData} must leave the property completely
   * untouched — matches the established "don't touch fields the caller didn't send" contract
   * already followed by {@code status}/{@code fileExternal}/{@code fileName} in this method. The
   * response must be the plain {@code {"ok":true}} — no {@code manualDataApplied} key — since
   * nothing about manualData failed; it was simply never sent.
   */
  @Test
  public void testHandleDeclPutWithoutManualDataDoesNotTouchProperty() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"submitted\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), any());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * An explicit {@code "manualData": null} in the PUT body is a DIFFERENT JSON shape than the
   * key being absent entirely (covered above) — {@code body.has(...)} is true here, but
   * {@code body.isNull(...)} is also true. Per the documented intentional asymmetry with
   * {@code fileName} (W1), this must be treated as "not sent": the stored value is left
   * completely untouched, exactly like the absent-key case, and the plain {@code {"ok":true}} is
   * returned (no {@code manualDataApplied} key, since this is not a failure).
   */
  @Test
  public void testHandleDeclPutWithExplicitNullManualDataDoesNotTouchProperty() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"status\":\"submitted\",\"manualData\":null}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), any());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * A malformed {@code manualData} value (present, non-null, but not a JSON object — a bare
   * string) must not crash the whole PUT — the rest of the body (here {@code status}) is still
   * applied, the property itself is left untouched, AND the response must now signal the partial
   * failure via {@code "manualDataApplied":false} (W2) rather than the plain {@code {"ok":true}}
   * (see {@code setManualDataIfPresent}'s javadoc for why this fork deliberately does not throw).
   */
  @Test
  public void testHandleDeclPutWithMalformedManualDataDoesNotCrashRestOfPut() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"status\":\"submitted\",\"manualData\":\"not-an-object\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), any());
      verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
      verify(obDal).commitAndClose();
    }
    assertEquals("{\"ok\":true,\"manualDataApplied\":false}", sw.toString());
  }

  /**
   * S1 — a syntactically-valid JSON ARRAY (e.g. {@code [1,2,3]}) is a distinct malformed shape
   * from a bare string: it IS valid JSON, but jettison's {@code getJSONObject} still rejects it
   * because a {@code JSONArray} is not a {@code JSONObject}. Must degrade through the exact same
   * skip-and-log / {@code manualDataApplied:false} path as the bare-string case above, not be
   * accidentally accepted (e.g. via some silent array-to-object coercion) and not crash with an
   * uncaught exception type.
   */
  @Test
  public void testHandleDeclPutWithJsonArrayManualDataTreatedAsMalformed() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"status\":\"submitted\",\"manualData\":[1,2,3]}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), any());
      verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
      verify(obDal).commitAndClose();
    }
    assertEquals("{\"ok\":true,\"manualDataApplied\":false}", sw.toString());
  }

  /**
   * A valid {@code manualData} object must still yield the plain {@code {"ok":true}} response —
   * no {@code manualDataApplied} key at all when nothing failed (W2's "stay plain on success"
   * requirement, distinct from {@code testHandleDeclPutWithManualDataSetsPropertyAsCompactJsonString}
   * above, which only asserts the stored value, not the response body).
   */
  @Test
  public void testHandleDeclPutWithValidManualDataReturnsPlainOkResponse() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"status\":\"submitted\",\"manualData\":{\"identification\":{}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(decl).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), any());
      verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
    }
    assertEquals("{\"ok\":true}", sw.toString());
  }

  // ── handleDeclPut (negative box111/box77 rejection, ETP-5393 Bug C) ─
  // The classic AEAT303Report engine hard-rejects a negative value for box 111
  // (AEAT303Report2024.java:276-278, @AEAT303_Negative_Not_Allowed_For_111@) and box 77
  // (AEAT303Report2015.java:149-162, @AEAT303_Negative_IVA_IMPORT_ADUANA@). The GO
  // manualOverrides PUT had no equivalent server-side check at all. Unlike a malformed
  // manualData blob (tolerated, see the tests above), a negative value on either box is a real
  // business-rule violation and must reject the whole PUT with 400, leaving the record untouched.

  /**
   * A negative box 111 in {@code manualData.manualOverrides} must reject the PUT with 400 and
   * leave the declaration record completely unwritten — no status/manualData set, no commit.
   */
  @Test
  public void testHandleDeclPutWithNegativeBox111Returns400AndLeavesRecordUnchanged()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"111\":-500}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
      verify(obDal, never()).commitAndClose();
    }
  }

  /**
   * A negative box 77 in {@code manualData.manualOverrides} must also reject the PUT with 400 —
   * mirrors the box 111 case above, the other classic-engine negative-not-allowed box.
   */
  @Test
  public void testHandleDeclPutWithNegativeBox77Returns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"77\":-12.34}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A negative value on a box OTHER than 111/77 (e.g. box 27, a normal accrued-VAT box that can
   * legitimately be negative — credit notes) must NOT be rejected: the guard only watches the
   * two classic-engine "negative not allowed" boxes, everything else passes through unchanged.
   */
  @Test
  public void testHandleDeclPutWithNegativeOtherBoxSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"27\":-100}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
      verify(decl).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), any());
    }
  }

  /**
   * A positive box 111 must be accepted normally — the guard only fires on a negative value.
   */
  @Test
  public void testHandleDeclPutWithPositiveBox111Succeeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"111\":250}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }

  /**
   * QA edge case (ETP-5393 Bug C): the negative-value guard reads {@code manualOverrides} via
   * {@code JSONObject#optDouble}, which also coerces a JSON STRING value (not just a JSON
   * number) — this endpoint is a generic PUT body, not exclusively fed by the frontend's own
   * numeric serializer, so a string-encoded negative box 111 (e.g. {@code "111": "-12"}) must be
   * rejected exactly like a numeric one, not silently pass through as a non-numeric default.
   */
  @Test
  public void testHandleDeclPutWithStringEncodedNegativeBox111Returns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"111\":\"-12\"}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * QA edge case (ETP-5393 Bug C): box 111 exactly {@code 0} (the boundary, not just a clearly
   * positive value) must be accepted — the guard's condition is strictly {@code < 0}.
   */
  @Test
  public void testHandleDeclPutWithZeroBox111Succeeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"111\":0}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }

  /**
   * QA edge case (ETP-5393 Bug C): boxes 111 AND 77 both negative in the SAME PUT body must
   * still reject with a single 400 (the loop returns on the first offending box found) — no
   * partial application, no double-write.
   */
  @Test
  public void testHandleDeclPutWithBothBoxesNegativeReturns400Once() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"111\":-1,\"77\":-2}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, times(1)).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  // ── handleDeclPut (negative box70/78/109/110 rejection, ETP-5438) ───
  // Casillas 70, 78, 109 and 110 are declared "Num" (numérico sin signo / unsigned) in the
  // official AEAT Modelo 303 "Diseño de registro" (DR303e26v101 v1.01), exactly like 111 and 77
  // above — same guard, same set, just widened. See NEGATIVE_NOT_ALLOWED_BOX_KEYS's javadoc.

  /**
   * A negative box 70 ("a_deducir") in {@code manualData.manualOverrides} must reject the PUT
   * with 400 — same contract as the box 111/77 tests above.
   */
  @Test
  public void testHandleDeclPutWithNegativeBox70Returns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"70\":-100}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A negative box 78 ("cuotas_compensar_aplic") must also reject the PUT with 400.
   */
  @Test
  public void testHandleDeclPutWithNegativeBox78Returns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"78\":-50.25}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A negative box 109 ("devoluciones_at") must also reject the PUT with 400.
   */
  @Test
  public void testHandleDeclPutWithNegativeBox109Returns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"109\":-10}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A negative box 110 ("cuotas_compensar") must also reject the PUT with 400.
   */
  @Test
  public void testHandleDeclPutWithNegativeBox110Returns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":{\"110\":-1}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * Zero (the boundary, not a clearly positive value) on all 4 newly-guarded boxes at once must
   * be accepted — the guard's condition is strictly {@code < 0}, same as the 111/77 boundary test.
   */
  @Test
  public void testHandleDeclPutWithZeroOnAllFourNewBoxesSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"manualOverrides\":"
            + "{\"70\":0,\"78\":0,\"109\":0,\"110\":0}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }

  // ── handleDeclPut (identification maxLength + bank_sepa enum, ETP-5438) ─
  // AEAT-spec audit follow-up: the alphanumeric ("An") identification fields have fixed max
  // lengths, and bank_sepa is a 4-value enum, not free text. See IDENTIFICATION_MAX_LENGTHS and
  // VALID_BANK_SEPA_VALUES' javadoc for the exact spec citations.

  /**
   * A {@code bank_iban} longer than its 34-char AEAT slot must reject the PUT with 400 and leave
   * the record unwritten — mirrors the negative-box tests' "reject the whole PUT" contract.
   */
  @Test
  public void testHandleDeclPutWithOversizedBankIbanReturns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    String oversizedIban = "ES" + "1".repeat(33); // 35 chars, 1 over the 34-char limit
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"identification\":{\"bank_iban\":\""
            + oversizedIban + "\"}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A {@code bank_iban} exactly at the 34-char limit must be accepted — the guard's condition is
   * strictly {@code length > max}.
   */
  @Test
  public void testHandleDeclPutWithBankIbanAtMaxLengthSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    String maxLengthIban = "ES" + "1".repeat(32); // exactly 34 chars
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"identification\":{\"bank_iban\":\""
            + maxLengthIban + "\"}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }

  /**
   * A {@code nro_justificante} longer than its 13-char AEAT slot must reject the PUT with 400 —
   * covers a second field on the map, not just bank_iban.
   */
  @Test
  public void testHandleDeclPutWithOversizedNroJustificanteReturns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"identification\":"
            + "{\"nro_justificante\":\"12345678901234\"}}}"))); // 14 chars, 1 over the limit

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A {@code bank_sepa} value outside the AEAT 4-value enum ("0"/"1"/"2"/"3") must reject the PUT
   * with 400.
   */
  @Test
  public void testHandleDeclPutWithInvalidBankSepaReturns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"identification\":{\"bank_sepa\":\"9\"}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
      verify(decl, never()).set(any(), any());
    }
  }

  /**
   * A valid {@code bank_sepa} enum value ("1" — Cuenta España) must be accepted normally.
   */
  @Test
  public void testHandleDeclPutWithValidBankSepaSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"identification\":{\"bank_sepa\":\"1\"}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }

  /**
   * An empty-string {@code bank_sepa} (field visible but not yet chosen) must NOT be rejected —
   * the enum guard only fires on a non-blank, out-of-range value; requiredness is a separate,
   * frontend-only concern (see {@code _BANK_FULL_BLOCK_REQUIRED_WHEN} in {@code fm303Layouts.js}).
   */
  @Test
  public void testHandleDeclPutWithEmptyBankSepaSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"status\":\"draft\",\"manualData\":{\"identification\":{\"bank_sepa\":\"\"}}}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }

  // ── handleDeclPut (submissionMethod, ETP-4755) ──────────────────────
  // Mirrors the manualData tests above exactly: submissionMethod follows the same
  // "explicit null means not sent" precedent (see handleDeclPut's javadoc comment), not
  // fileName's "explicit null clears it" one.

  /**
   * A valid {@code submissionMethod} in the PUT body must be persisted verbatim via
   * {@code decl.set(PROPERTY_SUBMISSION_METHOD, ...)}.
   */
  @Test
  public void testHandleDeclPutWithValidSubmissionMethodSetsProperty() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"status\":\"submitted_ack\",\"submissionMethod\":\"manual_ack\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD, "manual_ack");
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted_ack");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * A PUT body that never mentions {@code submissionMethod} must leave the property completely
   * untouched — matches the established "don't touch fields the caller didn't send" contract
   * (same as {@code manualData} above).
   */
  @Test
  public void testHandleDeclPutWithoutSubmissionMethodDoesNotTouchProperty() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"submitted\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD), any());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * An explicit {@code "submissionMethod": null} in the PUT body must be treated as "not sent" —
   * same precedent as {@code manualData} — leaving the stored value completely untouched.
   */
  @Test
  public void testHandleDeclPutWithExplicitNullSubmissionMethodDoesNotTouchProperty()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"status\":\"submitted_ack\",\"submissionMethod\":null}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD), any());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted_ack");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  // ── handleDeclPut (Reactivar declaración / reject aeat_telematic, ETP-5338) ─
  // Reverting a declaration to draft ("Reactivar declaración") goes through this same PUT path
  // (status: "draft"). The guard reads the declaration's CURRENTLY STORED submissionMethod (not
  // whatever the request body says) — the frontend never sends submissionMethod on a reactivate
  // call at all (see FmListPage.jsx's handleConfirmReactivate), so the guard must work purely off
  // the persisted value.

  /**
   * A declaration whose stored {@code submissionMethod} is {@code aeat_telematic} must be
   * rejected with 409 when the PUT tries to revert it to draft — reactivating a declaration that
   * was genuinely filed with the AEAT would desync this table from what Hacienda has on record.
   * The declaration record itself must be left completely unchanged: no status write, no commit.
   */
  @Test
  public void testHandleDeclPutReactivateAeatTelematicReturns409AndLeavesRecordUnchanged()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"draft\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn("aeat_telematic");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
      verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS), any());
      verify(obDal, never()).commitAndClose();
    }
  }

  /**
   * A declaration filed with {@code manual_ack} (a manual submission with AEAT acknowledgment,
   * not a real telematic one) must be allowed to reactivate — the status is persisted as
   * {@code draft} and the PUT succeeds normally.
   */
  @Test
  public void testHandleDeclPutReactivateManualAckSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"draft\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn("manual_ack");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "draft");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * A declaration filed with {@code manual_no_receipt} must also be allowed to reactivate — the
   * guard only special-cases {@code aeat_telematic}, every other submissionMethod (including this
   * one) is unaffected.
   */
  @Test
  public void testHandleDeclPutReactivateManualNoReceiptSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"draft\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD))
        .thenReturn("manual_no_receipt");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "draft");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * A declaration with NO stored {@code submissionMethod} at all (null — predates the feature, or
   * was never set) must not be swallowed by the guard: {@code asString(null)} yields {@code ""},
   * which is not equal to {@code aeat_telematic}, so the reactivate must succeed exactly like the
   * manual_ack/manual_no_receipt cases. Guards against a regression where the null case is
   * accidentally treated as "unknown, so block it".
   */
  @Test
  public void testHandleDeclPutReactivateNullSubmissionMethodSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"draft\"}")));

    // declOwnedBy leaves PROPERTY_SUBMISSION_METHOD unstubbed → Mockito's default null return,
    // which is the exact "never set" case this test targets.
    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "draft");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * The guard must only fire when the target status is {@code draft} — a PUT that changes
   * {@code aeat_telematic}'s OTHER fields (e.g. {@code fileExternal}) without touching status must
   * not be rejected. Confirms the guard is scoped to the reactivate transition specifically, not
   * to "any PUT on an aeat_telematic declaration".
   */
  @Test
  public void testHandleDeclPutOnAeatTelematicWithoutStatusChangeIsNotRejected() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"fileExternal\":true}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn("aeat_telematic");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_FILE_EXTERNAL, true);
    assertEquals("{\"ok\":true}", sw.toString());
  }

  // ── handleDeclPut (rejectRepresentation — block re-presentation, ETP-5438) ─
  //
  // "block re-presentation once already submitted" — this is the backend half of that: a PUT
  // that re-sends a submitted-family `status` on a declaration that is ALREADY in a
  // submitted-family status must be rejected, regardless of what the frontend does (it already
  // hides "Registrar/Presentar" once isSubmitted). Model-agnostic — the same ETGO_Fiscal_Decl
  // table/PUT path serves both 303 and 349, so these tests exercise the shared handler directly
  // rather than a model-specific one.

  /**
   * The base case: current status {@code submitted}, PUT tries to set {@code submitted_ack} (a
   * different manual path re-presenting the SAME declaration) — rejected with 409, no status
   * write, no commit.
   */
  @Test
  public void testHandleDeclPutRepresentAlreadySubmittedReturns409AndLeavesRecordUnchanged()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"submitted_ack\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
      verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS), any());
      verify(obDal, never()).commitAndClose();
    }
  }

  /** Same guard, exercised with the exact same status on both sides (idempotent re-PUT). */
  @Test
  public void testHandleDeclPutRepresentSameStatusReturns409() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"submitted\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
    }
  }

  /** {@code submitted_ext} (legacy) counts as "already submitted" too — mixed-status coverage. */
  @Test
  public void testHandleDeclPutRepresentFromSubmittedExtReturns409() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"submitted\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted_ext");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);

      verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
    }
  }

  /**
   * The normal, FIRST-time presentation (current {@code ready} -> new {@code submitted}) must NOT
   * be rejected — the guard only fires when BOTH sides are already in the submitted family.
   */
  @Test
  public void testHandleDeclPutFirstPresentationFromReadySucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"submitted\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("ready");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "submitted");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * "Reactivar declaración" (current {@code submitted}, new {@code draft}, non-telematic) must
   * remain unaffected by this new guard — it only special-cases an INCOMING submitted-family
   * status, and {@code draft} is not one. {@link #rejectTelematicReactivation} is what already
   * guards this specific transition on its own, narrower terms.
   */
  @Test
  public void testHandleDeclPutReactivateFromSubmittedStillSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");
    when(req.getReader())
        .thenReturn(new BufferedReader(new StringReader("{\"status\":\"draft\"}")));

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn("manual_ack");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    verify(servlet, never()).sendError(any(), anyInt(), anyString());
    verify(decl).set(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS, "draft");
    assertEquals("{\"ok\":true}", sw.toString());
  }

  // ── findLatestDeclarationStatus (ETP-5438) ───────────────────────────

  /** No declaration exists yet for the natural key -> {@code null} ("not submitted" by default). */
  @SuppressWarnings("unchecked")
  @Test
  public void testFindLatestDeclarationStatusNoneReturnsNull() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      String status = handler.findLatestDeclarationStatus("client1", "org1", "349", 2026L, "T1");

      assertEquals(null, status);
    }
  }

  /**
   * Two declarations for the same natural key (rectificativa flow) — the one with the HIGHER
   * {@code DECL_SEQ} wins, regardless of list iteration order, matching {@code
   * resolveNextDeclSeq}'s own "latest wins" ordinal.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testFindLatestDeclarationStatusReturnsHighestDeclSeqStatus() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);

      BaseOBObject older = mock(BaseOBObject.class);
      when(older.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(0L);
      when(older.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");
      BaseOBObject newer = mock(BaseOBObject.class);
      when(newer.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(1L);
      when(newer.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("draft");
      // Older-first in the list, on purpose — the result must not depend on iteration order.
      when(query.list()).thenReturn(Arrays.asList(older, newer));

      String status = handler.findLatestDeclarationStatus("client1", "org1", "349", 2026L, "T1");

      assertEquals("draft", status);
    }
  }

  // ── declToJson (manualData) ────────────────────────────────────────

  /**
   * A validly stored {@code manual_data} JSON string comes back parsed as a nested object, not
   * a raw string the frontend would have to double-parse.
   */
  @Test
  public void testDeclToJsonReturnsStoredManualDataAsNestedObject() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn(
        "{\"identification\":{\"nifOk\":true},\"manualOverrides\":{\"box01\":\"100.00\"}}");

    JSONObject json = handler.declToJson(decl);

    JSONObject manualData = json.getJSONObject("manualData");
    assertTrue(manualData.getJSONObject("identification").getBoolean("nifOk"));
    assertEquals("100.00", manualData.getJSONObject("manualOverrides").getString("box01"));
  }

  /** A never-touched (null) stored value must come back as an empty object, not a crash. */
  @Test
  public void testDeclToJsonNullManualDataReturnsEmptyObject() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn(null);

    JSONObject json = handler.declToJson(decl);

    assertEquals(0, json.getJSONObject("manualData").length());
  }

  /** A blank (whitespace-only) stored value must also come back as an empty object. */
  @Test
  public void testDeclToJsonBlankManualDataReturnsEmptyObject() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn("   ");

    JSONObject json = handler.declToJson(decl);

    assertEquals(0, json.getJSONObject("manualData").length());
  }

  /**
   * A corrupted/malformed stored string (simulates data corruption) must degrade to an empty
   * object rather than propagating a parse exception up through {@code declToJson} — mirrors
   * {@code NeoFiscalModelsCatalogService.getActiveModels()}'s graceful-degradation convention for
   * the same "arbitrary JSON blob in a persisted text field" shape.
   */
  @Test
  public void testDeclToJsonMalformedManualDataReturnsEmptyObjectInsteadOfCrashing()
      throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn("{not-valid-json");

    JSONObject json = handler.declToJson(decl);

    assertEquals(0, json.getJSONObject("manualData").length());
  }

  /**
   * S1 — a stored value that is syntactically-valid JSON but shaped as an ARRAY (e.g.
   * {@code [1,2,3]}) rather than an object is a distinct malformed case from the corrupted-string
   * case above: {@code new JSONObject(raw)} on a top-level array is a parse failure too (jettison
   * expects a leading {@code {}), so it must degrade to an empty object exactly like the
   * corrupted-string case, not throw an uncaught exception type.
   */
  @Test
  public void testDeclToJsonJsonArrayManualDataReturnsEmptyObjectInsteadOfCrashing()
      throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn("[1,2,3]");

    JSONObject json = handler.declToJson(decl);

    assertEquals(0, json.getJSONObject("manualData").length());
  }

  // ── declToJson (submissionMethod, ETP-4755) ─────────────────────────

  /** A stored {@code submission_method} value comes back verbatim in the response JSON. */
  @Test
  public void testDeclToJsonReturnsStoredSubmissionMethod() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn("aeat_telematic");

    JSONObject json = handler.declToJson(decl);

    assertEquals("aeat_telematic", json.getString("submissionMethod"));
  }

  /**
   * A declaration that never had {@code submissionMethod} set (predates the feature, or the
   * status change that would set it never happened) must come back as JSON {@code null} — not an
   * empty string, not the key omitted entirely, not a crash.
   */
  @Test
  public void testDeclToJsonNeverSetSubmissionMethodReturnsJsonNull() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn(null);

    JSONObject json = handler.declToJson(decl);

    assertTrue(json.has("submissionMethod"));
    assertTrue(json.isNull("submissionMethod"));
  }

  /** A blank (whitespace-only) stored value must also come back as JSON {@code null}. */
  @Test
  public void testDeclToJsonBlankSubmissionMethodReturnsJsonNull() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_SUBMISSION_METHOD)).thenReturn("   ");

    JSONObject json = handler.declToJson(decl);

    assertTrue(json.isNull("submissionMethod"));
  }

  // ── QA adversarial coverage ─────────────────────────────────────────

  /**
   * QA (post-review adversarial pass): {@code manual_data} is a Postgres {@code text} column
   * (unbounded) — confirmed via {@code \d etgo_fiscal_decl} — and neither
   * {@code BaseOBObject#set} nor {@code StringDomainType}/{@code BasePrimitiveDomainType}
   * enforce any length cap (verified against Openbravo core: {@code checkIsValidValue} only
   * checks {@code instanceof String}). The AD_Column's {@code fieldlength=2000} is a pure UI
   * form-field hint with zero effect here, since this field is never exposed as an editable AD
   * form field — only written via {@code decl.set()} from this REST handler. This test PUTs a
   * payload north of 10,000 characters and round-trips it through {@code declToJson} to prove
   * there is no truncation in practice, not just in theory.
   */
  @Test
  public void testHandleDeclPutWithLargeManualDataPayloadRoundTripsWithoutTruncation()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");

    StringBuilder padding = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      padding.append('x');
    }
    String requestBody = "{\"manualData\":{\"identification\":{\"nifOk\":true},"
        + "\"manualOverrides\":{\"box01\":\"" + padding + "\"}}}";
    assertTrue(requestBody.length() > 10_000);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(requestBody)));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(decl).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), captor.capture());
    String storedJson = (String) captor.getValue();
    assertTrue("Stored manualData JSON must not be truncated", storedJson.length() > 10_000);

    // Feed the captured (un-truncated) stored string back as if declToJson re-read it from the
    // manual_data column, and confirm the large value survives the round trip byte-for-byte.
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn(storedJson);
    JSONObject json = handler.declToJson(decl);
    String roundTripped = json.getJSONObject("manualData")
        .getJSONObject("manualOverrides").getString("box01");
    assertEquals(padding.toString(), roundTripped);
    assertEquals(10_000, roundTripped.length());
  }

  /**
   * QA: a Modelo 349 declaration lives in the SAME {@code ETGO_Fiscal_Decl} table (the
   * {@code model} column just distinguishes 303/349) and never has anything write its
   * {@code manual_data} (no 349 frontend feature ever sends {@code manualData}). Confirms
   * {@link FiscalDeclCrudHandler#declToJson} has no missing model-specific branch — a 349 row
   * with a null {@code manual_data} still comes back with a clean {@code manualData: {}} and the
   * rest of the payload (including {@code model}) intact.
   */
  @Test
  public void testDeclToJsonOn349ModelWithNullManualDataReturnsEmptyObjectCleanly()
      throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.getId()).thenReturn("decl349");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL)).thenReturn("349");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn(null);

    JSONObject json = handler.declToJson(decl);

    assertEquals("349", json.getString("model"));
    assertEquals(0, json.getJSONObject("manualData").length());
  }

  /**
   * QA: {@code manualData} is a tenant-authored, arbitrary JSON blob. Confirms the store
   * (PUT) → retrieve ({@code declToJson}) round trip through jettison's {@code JSONObject}
   * preserves HTML-special characters, quote characters, a SQL-metacharacter-shaped string,
   * unicode text, and a 20-level-deep nested structure verbatim — nothing is mangled, escaped
   * incorrectly, or silently dropped. Also documents that this write path never builds a raw SQL
   * string (it always goes through {@code decl.set()} / OBDal), so there is no naive
   * string-concatenation SQL-injection surface to worry about here.
   */
  @Test
  public void testHandleDeclPutManualDataWithSpecialCharactersRoundTripsSafely() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");

    JSONObject overrides = new JSONObject();
    overrides.put("note",
        "<script>alert('x')</script>; DROP TABLE etgo_fiscal_decl; -- \" ' \n\t");
    overrides.put("unicode", "façade — año 2026 — 日本語 — ñ");
    JSONObject deepRoot = new JSONObject();
    JSONObject cursor = deepRoot;
    for (int i = 0; i < 20; i++) {
      JSONObject next = new JSONObject();
      cursor.put("nested", next);
      cursor = next;
    }
    cursor.put("leaf", "bottom");
    overrides.put("deep", deepRoot);

    JSONObject sentManualData = new JSONObject();
    sentManualData.put("manualOverrides", overrides);
    JSONObject body = new JSONObject();
    body.put("manualData", sentManualData);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body.toString())));

    BaseOBObject decl = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("PUT", req, resp);
    }

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(decl).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), captor.capture());
    String storedJson = (String) captor.getValue();

    when(decl.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn(storedJson);
    JSONObject json = handler.declToJson(decl);
    JSONObject roundTrippedOverrides =
        json.getJSONObject("manualData").getJSONObject("manualOverrides");
    assertEquals(overrides.getString("note"), roundTrippedOverrides.getString("note"));
    assertEquals(overrides.getString("unicode"), roundTrippedOverrides.getString("unicode"));

    JSONObject deepCursor = roundTrippedOverrides.getJSONObject("deep");
    for (int i = 0; i < 20; i++) {
      deepCursor = deepCursor.getJSONObject("nested");
    }
    assertEquals("bottom", deepCursor.getString("leaf"));
  }

  /**
   * QA: {@code declToJson} reads {@code manual_data} straight off the {@code decl} instance
   * passed in — confirms two different declarations, each carrying different {@code manualData},
   * never leak into each other (no shared/static state anywhere in the read path).
   */
  @Test
  public void testDeclToJsonForTwoDifferentDeclarationsDoesNotCrossContaminateManualData()
      throws Exception {
    BaseOBObject declA = mock(BaseOBObject.class);
    when(declA.getId()).thenReturn("declA");
    when(declA.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA))
        .thenReturn("{\"manualOverrides\":{\"box01\":\"AAA\"}}");

    BaseOBObject declB = mock(BaseOBObject.class);
    when(declB.getId()).thenReturn("declB");
    when(declB.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA))
        .thenReturn("{\"manualOverrides\":{\"box01\":\"BBB\"}}");

    JSONObject jsonA = handler.declToJson(declA);
    JSONObject jsonB = handler.declToJson(declB);

    assertEquals("AAA",
        jsonA.getJSONObject("manualData").getJSONObject("manualOverrides").getString("box01"));
    assertEquals("BBB",
        jsonB.getJSONObject("manualData").getJSONObject("manualOverrides").getString("box01"));
  }

  /**
   * QA: two PUTs targeting two DIFFERENT declaration ids, each with its own {@code manualData},
   * must each only mutate the specific {@code decl} instance {@code resolveOwnedDeclaration}
   * resolved for that request's {@code id} — no cross-declaration writes.
   */
  @Test
  public void testHandleDeclPutTargetsOnlyTheResolvedDeclarationById() throws Exception {
    HttpServletRequest reqA = mock(HttpServletRequest.class);
    HttpServletResponse respA = mock(HttpServletResponse.class);
    when(respA.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(reqA.getParameter("id")).thenReturn("declA");
    when(reqA.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"manualData\":{\"manualOverrides\":{\"box01\":\"AAA\"}}}")));

    HttpServletRequest reqB = mock(HttpServletRequest.class);
    HttpServletResponse respB = mock(HttpServletResponse.class);
    when(respB.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    when(reqB.getParameter("id")).thenReturn("declB");
    when(reqB.getReader()).thenReturn(new BufferedReader(new StringReader(
        "{\"manualData\":{\"manualOverrides\":{\"box01\":\"BBB\"}}}")));

    BaseOBObject declA = declOwnedBy("client1", "org1");
    BaseOBObject declB = declOwnedBy("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "declA")).thenReturn(declA);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "declB")).thenReturn(declB);

      handler.handleDeclarations("PUT", reqA, respA);
      handler.handleDeclarations("PUT", reqB, respB);
    }

    ArgumentCaptor<Object> captorA = ArgumentCaptor.forClass(Object.class);
    verify(declA).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), captorA.capture());
    ArgumentCaptor<Object> captorB = ArgumentCaptor.forClass(Object.class);
    verify(declB).set(eq(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA), captorB.capture());

    assertTrue(((String) captorA.getValue()).contains("AAA"));
    assertTrue(((String) captorB.getValue()).contains("BBB"));
    assertTrue(!((String) captorA.getValue()).contains("BBB"));
    assertTrue(!((String) captorB.getValue()).contains("AAA"));
  }

  // ── resolveNextDeclSeq (ETP-5187) ───────────────────────────────────

  /** No declarations exist yet for the natural key -> the first one gets {@code DECL_SEQ = 0}. */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveNextDeclSeqNoExistingDeclarationsReturnsZero() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      long seq = handler.resolveNextDeclSeq("client1", "org1", "303", 2026L, "1T");

      assertEquals(0L, seq);
    }
  }

  /** A single existing declaration with {@code DECL_SEQ = 0} -> the next one gets {@code 1}. */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveNextDeclSeqOneExistingWithSeqZeroReturnsOne() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      BaseOBObject existing = mock(BaseOBObject.class);
      when(existing.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(0L);
      when(query.list()).thenReturn(Collections.singletonList(existing));

      long seq = handler.resolveNextDeclSeq("client1", "org1", "303", 2026L, "1T");

      assertEquals(1L, seq);
    }
  }

  /**
   * Several existing declarations with non-contiguous, out-of-order {@code DECL_SEQ} values
   * (3, 0, 5, 2) -> the next one gets {@code MAX + 1 = 6}, regardless of iteration order.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveNextDeclSeqMultipleOutOfOrderReturnsMaxPlusOne() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);

      BaseOBObject e1 = mock(BaseOBObject.class);
      when(e1.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(3L);
      BaseOBObject e2 = mock(BaseOBObject.class);
      when(e2.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(0L);
      BaseOBObject e3 = mock(BaseOBObject.class);
      when(e3.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(5L);
      BaseOBObject e4 = mock(BaseOBObject.class);
      when(e4.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(2L);
      when(query.list()).thenReturn(Arrays.asList(e1, e2, e3, e4));

      long seq = handler.resolveNextDeclSeq("client1", "org1", "303", 2026L, "1T");

      assertEquals(6L, seq);
    }
  }

  /**
   * Isolation: the query is parameterized on the FULL natural key
   * ({@code clientId, orgId, model, year, period}) — a declaration for a different org, model,
   * year or period is scoped out by the underlying HQL filter (not exercised against a real DB
   * here), which this test verifies at the query-construction level by asserting every named
   * parameter is bound to the exact value passed in.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolveNextDeclSeqScopesQueryToExactNaturalKey() {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      handler.resolveNextDeclSeq("clientX", "orgY", "349", 2025L, "2T");

      verify(query).setNamedParameter("clientId", "clientX");
      verify(query).setNamedParameter("orgId", "orgY");
      verify(query).setNamedParameter("model", "349");
      verify(query).setNamedParameter("year", Long.valueOf(2025L));
      verify(query).setNamedParameter("period", "2T");
    }
  }

  // ── handleDeclPost (creation, ETP-5187) ─────────────────────────────

  /** A brand-new natural key -> the created declaration gets {@code DECL_SEQ = 0}. */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleDeclPostFirstDeclarationForNewNaturalKeyGetsSeqZero() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"model\":\"303\",\"year\":2026,\"period\":\"1T\"}")));

    BaseOBObject newDecl = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL)).thenReturn(newDecl);

      handler.handleDeclarations("POST", req, resp);

      verify(newDecl).set(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL, "303");
      verify(newDecl).set(FiscalDeclCrudHandler.PROPERTY_PERIOD, "1T");
      verify(newDecl).set(eq(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ), eq(0L));
      verify(obDal).save(newDecl);
      verify(obDal).commitAndClose();
    }
    verify(resp).setStatus(HttpServletResponse.SC_CREATED);
  }

  /**
   * A 2nd declaration for an already-declared period must NOT throw (the ETP-5187 fix, replacing
   * the old 2-declaration cap on {@code ETGO_FISCAL_DECL_UQ}) and must get the next free
   * {@code DECL_SEQ}.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleDeclPostSecondDeclarationForSamePeriodGetsSeqOne() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"model\":\"303\",\"year\":2026,\"period\":\"1T\"}")));

    BaseOBObject existing0 = mock(BaseOBObject.class);
    when(existing0.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(0L);
    BaseOBObject secondDecl = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(existing0));

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL)).thenReturn(secondDecl);

      handler.handleDeclarations("POST", req, resp);

      verify(secondDecl).set(eq(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ), eq(1L));
      verify(obDal).save(secondDecl);
      verify(obDal).commitAndClose();
    }
    verify(resp).setStatus(HttpServletResponse.SC_CREATED);
  }

  /**
   * A 4th declaration for an already-declared period (3 prior rectificativas already on file) —
   * there is no AEAT/legal cap, so this must succeed exactly like the 2nd, with a correctly
   * incremented {@code DECL_SEQ}.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleDeclPostFourthDeclarationForSamePeriodGetsSeqThreeAndSucceeds()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"model\":\"303\",\"year\":2026,\"period\":\"1T\"}")));

    BaseOBObject existing0 = mock(BaseOBObject.class);
    when(existing0.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(0L);
    BaseOBObject existing1 = mock(BaseOBObject.class);
    when(existing1.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(1L);
    BaseOBObject existing2 = mock(BaseOBObject.class);
    when(existing2.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(2L);
    BaseOBObject fourthDecl = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      // 3 declarations already exist for this exact period -- pre-fix, this used to 500 past the
      // old 2-declaration cap on ETGO_FISCAL_DECL_UQ.
      when(query.list()).thenReturn(Arrays.asList(existing0, existing1, existing2));

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL)).thenReturn(fourthDecl);

      handler.handleDeclarations("POST", req, resp);

      verify(fourthDecl).set(eq(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ), eq(3L));
      verify(obDal).save(fourthDecl);
      verify(obDal).commitAndClose();
    }
    verify(resp).setStatus(HttpServletResponse.SC_CREATED);
  }

  // ── handleDeclPost (draft-status guard, ETP-5272) ───────────────────

  /**
   * An existing DRAFT declaration for the same natural key must block creation of a new one:
   * {@code hasDraftDeclaration} short-circuits {@code handleDeclPost} with a 409 BEFORE
   * {@link FiscalDeclCrudHandler#resolveNextDeclSeq} ever runs, and no new row is created —
   * the core ETP-5272 gate ("complete or delete the draft before starting another one").
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleDeclPostExistingDraftDeclarationReturns409AndDoesNotCreate()
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"model\":\"303\",\"year\":2026,\"period\":\"1T\"}")));

    BaseOBObject draftDecl = mock(BaseOBObject.class);
    when(draftDecl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("draft");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(draftDecl));

      handler.handleDeclarations("POST", req, resp);

      verify(obDal, never()).save(any());
      verify(obDal, never()).commitAndClose();
    }
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
    verify(resp, never()).setStatus(HttpServletResponse.SC_CREATED);
  }

  /**
   * Regression: existing declarations for the same natural key that are ALL non-draft (e.g. one
   * {@code ready}, one {@code submitted} — the corrective/rectificativa case, ETP-5187) must NOT
   * be blocked by the ETP-5272 guard. Creation succeeds and still routes through the
   * pre-existing, untouched {@link FiscalDeclCrudHandler#resolveNextDeclSeq} to get the next free
   * ordinal — confirms the new guard is additive and does not alter resolveNextDeclSeq's
   * long-established contract.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleDeclPostAllExistingNonDraftSucceedsAndRoutesToNextSeq() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getReader()).thenReturn(new BufferedReader(
        new StringReader("{\"model\":\"303\",\"year\":2026,\"period\":\"1T\"}")));

    BaseOBObject ready = mock(BaseOBObject.class);
    when(ready.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("ready");
    when(ready.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(0L);
    BaseOBObject submitted = mock(BaseOBObject.class);
    when(submitted.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");
    when(submitted.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ)).thenReturn(1L);
    BaseOBObject thirdDecl = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBQuery<BaseOBObject> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), anyString()))
          .thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(ready, submitted));

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL)).thenReturn(thirdDecl);

      handler.handleDeclarations("POST", req, resp);

      verify(thirdDecl).set(eq(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ), eq(2L));
      verify(obDal).save(thirdDecl);
      verify(obDal).commitAndClose();
    }
    verify(resp).setStatus(HttpServletResponse.SC_CREATED);
  }

  // ── handleDeclDelete (draft-only guard, ETP-5187) ───────────────────

  /** Deleting a {@code draft} declaration succeeds: the row is removed and committed. */
  @Test
  public void testHandleDeclDeleteDraftStatusSucceeds() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    when(req.getParameter("id")).thenReturn("decl1");

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("draft");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("DELETE", req, resp);

      verify(obDal).remove(decl);
      verify(obDal).commitAndClose();
    }
    assertEquals("{\"ok\":true}", sw.toString());
  }

  /**
   * Deleting a declaration with any status other than {@code draft} (e.g. {@code submitted})
   * must be rejected with 409 and must NOT remove the row.
   */
  @Test
  public void testHandleDeclDeleteNonDraftStatusReturns409AndDoesNotDelete() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");

    BaseOBObject decl = declOwnedBy("client1", "org1");
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("submitted");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "decl1")).thenReturn(decl);

      handler.handleDeclarations("DELETE", req, resp);

      verify(obDal, never()).remove(any());
    }
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_CONFLICT), anyString());
  }

  /** Deleting a non-existent declaration id follows the shared not-found convention (404). */
  @Test
  public void testHandleDeclDeleteNonExistentDeclarationReturns404() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("missing-id");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      mockContext(ctxMock, "client1", "org1");
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "missing-id")).thenReturn(null);

      handler.handleDeclarations("DELETE", req, resp);

      verify(obDal, never()).remove(any());
    }
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  // ── helpers ────────────────────────────────────────────────────────

  /**
   * Builds a mocked {@code ETGO_Fiscal_Decl} row that passes
   * {@link FiscalDeclCrudHandler#resolveOwnedDeclaration} for the given client/org — shared setup
   * for the {@code handleDeclPut} manualData tests above.
   */
  private static BaseOBObject declOwnedBy(String clientId, String orgId) {
    BaseOBObject decl = mock(BaseOBObject.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(decl.get("client")).thenReturn(client);
    when(decl.get("organization")).thenReturn(org);
    return decl;
  }

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
