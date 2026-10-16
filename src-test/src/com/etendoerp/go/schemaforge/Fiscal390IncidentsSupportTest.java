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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * Unit tests of the Modelo 390 incidents endpoint and guards (ETP-5716): {@code POST
 * /fiscal390/validate} recomputes and replaces the rule incidents of a draft (never of a
 * submitted declaration), the {@code generate} guard answers {@code 409 BLOCKING_INCIDENTS} with
 * the incidents, and the facts read the declaration's identification and the boxes. The rules
 * themselves are {@link Fiscal390IncidentRulesTest}.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390IncidentsSupport
 */
public class Fiscal390IncidentsSupportTest {

  private static final String DECL_ID = "D390";

  private NeoServlet servlet;
  private Fiscal390BoxesHandler handler;
  private Fiscal390IncidentsSupport support;
  private FiscalDeclCrudHandler declHandler;
  private Fiscal390SubmissionSupport submission;
  private FiscalDecl decl;
  private OBDal obDal;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @Before
  public void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    handler = spy(new Fiscal390BoxesHandler(servlet));
    doReturn("org1").when(handler).declarationOrgId();
    declHandler = mock(FiscalDeclCrudHandler.class);
    doReturn(declHandler).when(handler).declHandler();
    when(declHandler.incidentsJson(DECL_ID)).thenReturn(new JSONArray());
    submission = mock(Fiscal390SubmissionSupport.class);
    doReturn(submission).when(handler).submissionSupport();
    support = spy(new Fiscal390IncidentsSupport(handler));

    decl = mock(FiscalDecl.class);
    when(decl.getId()).thenReturn(DECL_ID);
    when(decl.getDeclarationStatus()).thenReturn("draft");
    when(decl.getManualData()).thenReturn("{\"aeatInputs\":{\"COMMON_TERRITORY\":\"50\"},"
        + "\"identification\":{\"sustitutiva\":true,\"formerStatement\":\"123\"}}");
    when(submission.isOwned390(decl, 2026)).thenReturn(true);

    obDal = mock(OBDal.class);
    when(obDal.get(FiscalDecl.class, DECL_ID)).thenReturn(decl);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    OBContext ctx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    when(ctx.getCurrentClient()).thenReturn(client);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);
  }

  @After
  public void tearDown() {
    obContextMock.close();
    obDalMock.close();
  }

  private static HttpServletRequest request(String id) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("id")).thenReturn(id);
    return req;
  }

  private static HttpServletResponse response(StringWriter out) throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(out, true));
    return resp;
  }

  /** Facts with no incident, so a test only sees what it changes. */
  private static Fiscal390IncidentRules.Facts cleanFacts() {
    Fiscal390IncidentRules.Facts f = new Fiscal390IncidentRules.Facts();
    f.orgNif = "B1";
    f.taxpayerName = "ACME";
    f.mainActivities = List.of(new Fiscal390IncidentRules.Activity("A", true));
    f.representatives = List.of(new Fiscal390IncidentRules.Representative("N", "R"));
    return f;
  }

  // ── validate ────────────────────────────────────────────────────────

  /** A draft: every rule recomputed from the STORED inputs, rule rows replaced, committed. */
  @Test
  public void testValidateRecomputesAndReplacesTheRuleIncidentsOfADraft() throws Exception {
    Fiscal390IncidentRules.Facts facts = cleanFacts();
    facts.mainActivities = List.of();
    doReturn(facts).when(support).loadFacts(eq("org1"), eq(2026), anyString(), anyMap(),
        eq(true));
    StringWriter out = new StringWriter();

    support.handleValidate("org1", 2026, request(DECL_ID), response(out));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<FiscalDeclCrudHandler.RuleIncidentRow>> rows =
        ArgumentCaptor.forClass(List.class);
    verify(declHandler).replaceRuleIncidentsNoCommit(eq(decl), rows.capture());
    assertEquals(1, rows.getValue().size());
    FiscalDeclCrudHandler.RuleIncidentRow row = rows.getValue().get(0);
    assertEquals(Fiscal390IncidentRules.NO_MAIN_IAE, row.code());
    assertEquals(FiscalDeclCrudHandler.SEVERITY_BLOCK, row.severity());
    JSONObject message = new JSONObject(row.message());
    assertEquals("iae", message.getJSONObject("action").getString("section"));
    verify(obDal).commitAndClose();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> inputs = ArgumentCaptor.forClass(Map.class);
    verify(support).loadFacts(eq("org1"), eq(2026), anyString(), inputs.capture(), eq(true));
    assertEquals("50", inputs.getValue().get("COMMON_TERRITORY"));
    assertFalse(new JSONObject(out.toString()).getBoolean("frozen"));
  }

  /** A submitted declaration's incidents are frozen: nothing recomputed, nothing written. */
  @Test
  public void testValidateDoesNotRecomputeASubmittedDeclaration() throws Exception {
    when(decl.getDeclarationStatus()).thenReturn("submitted_ack");
    StringWriter out = new StringWriter();

    support.handleValidate("org1", 2026, request(DECL_ID), response(out));

    verify(support, never()).loadFacts(anyString(), anyInt(), any(), anyMap(), anyBoolean());
    verify(declHandler, never()).replaceRuleIncidentsNoCommit(any(), any());
    verify(obDal, never()).commitAndClose();
    assertTrue(new JSONObject(out.toString()).getBoolean("frozen"));
  }

  @Test
  public void testValidateWithoutIdIs400() throws Exception {
    HttpServletResponse resp = response(new StringWriter());
    support.handleValidate("org1", 2026, request(null), resp);
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testValidateOfAnotherOrgsDeclarationIs404() throws Exception {
    when(submission.isOwned390(decl, 2026)).thenReturn(false);
    HttpServletResponse resp = response(new StringWriter());
    support.handleValidate("org1", 2026, request(DECL_ID), resp);
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
    verify(declHandler, never()).replaceRuleIncidentsNoCommit(any(), any());
  }

  // ── generate guard ──────────────────────────────────────────────────

  /** A blocking rule: 409 BLOCKING_INCIDENTS with the incidents, so the page can list them. */
  @Test
  public void testGenerateGuardAnswers409WithTheIncidents() throws Exception {
    BaseOBObject latest = mock(BaseOBObject.class);
    when(latest.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA)).thenReturn("{}");
    when(declHandler.findLatestDeclaration("client1", "org1", "390", 2026, "0A"))
        .thenReturn(latest);
    Fiscal390IncidentRules.Facts facts = cleanFacts();
    facts.orgNif = null;
    doReturn(facts).when(support).loadFacts(eq("org1"), eq(2026), eq("{}"), anyMap(), eq(false));
    HttpServletResponse resp = response(new StringWriter());

    assertTrue(support.rejectGenerate("org1", 2026, "0A", Map.of(), resp));

    ArgumentCaptor<NeoResponse> sent = ArgumentCaptor.forClass(NeoResponse.class);
    verify(servlet).writeResponse(eq(resp), sent.capture());
    assertEquals(HttpServletResponse.SC_CONFLICT, sent.getValue().getHttpStatus());
    String body = String.valueOf(sent.getValue().getBody());
    assertTrue(body, body.contains(Fiscal390IncidentsSupport.ERR_BLOCKING_INCIDENTS));
    assertTrue(body, body.contains(Fiscal390IncidentRules.NO_ORG_NIF));
  }

  /** Warnings never block the file. */
  @Test
  public void testGenerateGuardLetsWarningsThrough() throws Exception {
    Fiscal390IncidentRules.Facts facts = cleanFacts();
    facts.representatives = List.of(); // NO_LEGAL_REPRESENTATIVE: a warning
    doReturn(facts).when(support).loadFacts(eq("org1"), eq(2026), any(), anyMap(), eq(false));
    HttpServletResponse resp = response(new StringWriter());

    assertFalse(support.rejectGenerate("org1", 2026, "0A", Map.of(), resp));
    verify(servlet, never()).writeResponse(any(), any());
  }

  // ── facts ───────────────────────────────────────────────────────────

  /** The identification and the boxes payload reach the facts; a compute failure is kept. */
  @Test
  public void testLoadFactsReadsIdentificationAndBoxes() throws Exception {
    doReturn(new JSONObject("{\"mode\":\"territory\",\"summary\":{\"annualResult\":\"12.50\"},"
        + "\"warnings\":[{\"code\":\"UNSUPPORTED_RATE\",\"parameter\":\"P\",\"rate\":\"3.00\"}]}"))
        .when(handler).computePayload(eq("org1"), eq(2026), anyMap());
    when(obDal.get(Organization.class, "org1")).thenReturn(null);

    Fiscal390IncidentRules.Facts f = support.loadFacts("org1", 2026, decl.getManualData(),
        Map.of("A", "1"), false);

    assertTrue(f.substituteFlag);
    assertEquals("123", f.formerStatement);
    assertTrue(f.territoryMode);
    assertEquals("12.50", f.annualResult.toPlainString());
    assertEquals("UNSUPPORTED_RATE", f.calculatorWarnings.get(0).get("code"));
    assertEquals(null, f.missing303Periods);
  }

  @Test
  public void testLoadFactsKeepsTheComputeFailure() throws Exception {
    doThrow(new IllegalArgumentException("Invalid numeric value for INGRESAR_01"))
        .when(handler).computePayload(eq("org1"), eq(2026), anyMap());
    when(obDal.get(Organization.class, "org1")).thenReturn(null);

    Fiscal390IncidentRules.Facts f = support.loadFacts("org1", 2026, null, Map.of(), false);

    assertNotNull(f.computeError);
    assertTrue(f.computeError.contains("INGRESAR_01"));
  }
}
