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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.schemaforge.data.FiscalDecl;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for the Modelo 390 telematic filing ({@code POST /fiscal390/submit}, ETP-5716): the
 * guards, the request handed to the classic AEAT service (MODELO 390, PERIODO 0A, no NRC), the
 * persistence of an accepted filing (status, method, snapshot, {@code manualData.submission},
 * justificante attachment) and the "AEAT error keeps the draft untouched" rule. No network: the
 * classic {@link AEAT303SubmissionService} is a mock.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390SubmissionSupport
 * @covers com.etendoerp.go.schemaforge.FiscalAeatSubmissionSupport
 */
public class Fiscal390SubmissionSupportTest {

  private static final String DECL_ID = "D390";
  private static final String FILE = "<T3900>FILE</T3900>";

  private NeoServlet servlet;
  private Fiscal390BoxesHandler handler;
  private Fiscal390SubmissionSupport support;
  private Fiscal390GenerateSupport generateSupport;
  private AEAT303SubmissionService service;
  private FiscalDecl decl;
  private OBDal obDal;
  private AttachImplementationManager attachManager;
  private FiscalDeclCrudHandler declHandler;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoAttachmentsHelper> attachMock;
  private MockedStatic<FiscalSubmittedSnapshotSupport> snapshotMock;
  private MockedStatic<NeoAccessHelper> accessMock;

  @Before
  public void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    handler = spy(new Fiscal390BoxesHandler(servlet));
    doReturn("org1").when(handler).resolveEffectiveOrg();
    doReturn("org1").when(handler).declarationOrgId();
    generateSupport = mock(Fiscal390GenerateSupport.class);
    doReturn(generateSupport).when(handler).generateSupport();
    declHandler = mock(FiscalDeclCrudHandler.class);
    doReturn(declHandler).when(handler).declHandler();
    when(generateSupport.generateContent(eq("org1"), eq(2026), anyMap()))
        .thenReturn(new Fiscal390GenerateSupport.Generated(FILE, null));
    doReturn(new JSONObject("{\"boxes\":{\"86\":\"10.00\"},\"summary\":{\"annualResult\":\"10.00\"}}"))
        .when(handler).computePayload(eq("org1"), eq(2026), anyMap());

    support = spy(new Fiscal390SubmissionSupport(handler));
    service = mock(AEAT303SubmissionService.class);
    when(service.hasOrgCertificate(any())).thenReturn(true);
    doReturn(service).when(support).newSubmissionService();
    doReturn(0L).when(support).countBlockingIncidents(DECL_ID);

    decl = mock(FiscalDecl.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org1");
    when(decl.getId()).thenReturn(DECL_ID);
    when(decl.getClient()).thenReturn(client);
    when(decl.getOrganization()).thenReturn(org);
    when(decl.getFiscalModel()).thenReturn("390");
    when(decl.getFiscalYear()).thenReturn(2026L);
    when(decl.getDeclarationStatus()).thenReturn("draft");
    when(decl.getDeclarationType()).thenReturn("O");
    when(decl.getManualData()).thenReturn(
        "{\"aeatInputs\":{\"INGRESAR_01\":\"5\"},\"identification\":{}}");

    obDal = mock(OBDal.class);
    when(obDal.get(FiscalDecl.class, DECL_ID)).thenReturn(decl);
    when(obDal.get(Organization.class, "org1")).thenReturn(org);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);

    OBContext ctx = mock(OBContext.class);
    when(ctx.getCurrentClient()).thenReturn(client);
    User user = mock(User.class);
    when(user.getName()).thenReturn("Ayelen");
    when(ctx.getUser()).thenReturn(user);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

    attachManager = mock(AttachImplementationManager.class);
    attachMock = mockStatic(NeoAttachmentsHelper.class);
    attachMock.when(() -> NeoAttachmentsHelper.resolveTableId(anyString())).thenReturn("T1");
    attachMock.when(() -> NeoAttachmentsHelper.resolveTabId("T1", null)).thenReturn("TAB1");
    attachMock.when(NeoAttachmentsHelper::getAttachManager).thenReturn(attachManager);

    snapshotMock = mockStatic(FiscalSubmittedSnapshotSupport.class);
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
        eq(NeoAttachmentAuthorizer.TAX_REPORT_WINDOW_ID), anyString())).thenReturn(true);
  }

  @After
  public void tearDown() {
    accessMock.close();
    snapshotMock.close();
    attachMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private static HttpServletRequest request(String body) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("id")).thenReturn(DECL_ID);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(AbstractFiscalHandler.PERIOD_KEY)).thenReturn("0A");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
    return req;
  }

  private static final String PRODUCTION_BODY =
      "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SL\"}";

  private JSONObject submit(String body, HttpServletResponse resp, StringWriter out)
      throws Exception {
    support.handleSubmit("org1", 2026, "0A", request(body), resp);
    return out.toString().isEmpty() ? null : new JSONObject(out.toString());
  }

  private static HttpServletResponse response(StringWriter out) throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(out, true));
    return resp;
  }

  private static AEAT303SubmissionResult success(boolean testMode) {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    r.setTestMode(testMode);
    r.setCsv("CSV123");
    r.setPresentationDate("2027-01-15 10:20:30");
    r.setRegistryNumber("REG1");
    r.setJustificanteNumber("3901234567890");
    r.setPdfContent(new byte[] { 1, 2, 3 });
    return r;
  }

  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  // ── routing ─────────────────────────────────────────────────────────

  @Test
  public void testSubmitIsPostOnly() throws Exception {
    assertTrue(handler.isKnownEntity(Fiscal390SubmissionSupport.SUBMIT));
    assertTrue(handler.allowsPost(Fiscal390SubmissionSupport.SUBMIT));
    assertFalse(handler.allowsGet(Fiscal390SubmissionSupport.SUBMIT));
    assertFalse(handler.allowsPost(Fiscal390BoxesHandler.BOXES));

    HttpServletResponse resp = response(new StringWriter());
    handler.handle(Fiscal390SubmissionSupport.SUBMIT, "GET", request(""), resp);
    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }

  // ── production success ──────────────────────────────────────────────

  /**
   * The classic service is asked for MODELO 390 / PERIODO 0A / no NRC with the file generated
   * from the STORED inputs; an accepted filing persists status, method, snapshot and the
   * submission data, attaches the PDF and commits once.
   */
  @Test
  public void testProductionSuccessPersistsSubmission() throws Exception {
    when(service.submitProduction(any())).thenReturn(success(false));
    StringWriter out = new StringWriter();

    JSONObject json = submit(PRODUCTION_BODY, response(out), out);

    ArgumentCaptor<AEAT303SubmissionService.ProductionSubmissionRequest> req =
        ArgumentCaptor.forClass(AEAT303SubmissionService.ProductionSubmissionRequest.class);
    verify(service).submitProduction(req.capture());
    assertEquals("390", field(req.getValue(), "modelo"));
    assertEquals("0A", field(req.getValue(), "period"));
    assertEquals("2026", field(req.getValue(), "fiscalYear"));
    assertEquals("", field(req.getValue(), "nrc"));
    assertEquals(FILE, field(req.getValue(), "fileContent"));
    assertEquals("B12345678", field(req.getValue(), "presenterNif"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> inputs = ArgumentCaptor.forClass(Map.class);
    verify(generateSupport).generateContent(eq("org1"), eq(2026), inputs.capture());
    assertEquals("5", inputs.getValue().get("INGRESAR_01"));

    verify(decl).setDeclarationStatus("submitted_ack");
    verify(decl).setSubmissionMethod("aeat_telematic");
    verify(decl).setDeclarationFileName("justificante-390-2026-0A.pdf");
    verify(decl).setSubmittedSnapshot(anyString());
    ArgumentCaptor<String> manual = ArgumentCaptor.forClass(String.class);
    verify(decl).setManualData(manual.capture());
    JSONObject stored = new JSONObject(manual.getValue());
    assertEquals("5", stored.getJSONObject("aeatInputs").getString("INGRESAR_01"));
    JSONObject submission = stored.getJSONObject(Fiscal390SubmissionSupport.SUBMISSION_KEY);
    assertEquals("3901234567890", submission.getString("justificante"));
    assertEquals("CSV123", submission.getString("csv"));
    assertEquals("Ayelen", submission.getString("user"));
    assertEquals("aeat_telematic", submission.getString("method"));
    verify(attachManager).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"), any());
    verify(declHandler).replaceAeatIncidentsNoCommit(eq(decl), any(), any());
    verify(obDal).commitAndClose();

    assertEquals("SUCCESS", json.getString("status"));
    assertEquals("3901234567890", json.getString("justificanteNumber"));
    assertEquals("10.00", json.getJSONObject("declarationData").getString("resultAmount"));
  }

  // ── AEAT error ──────────────────────────────────────────────────────

  /**
   * The AEAT rejects the filing: the declaration status/data do not change, the errors come back
   * and are stored as AEAT incidents (committed), like 303.
   */
  @Test
  public void testAeatErrorKeepsDraft() throws Exception {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(AEAT303SubmissionResult.Status.ERROR);
    r.addError("E390001: NIF no identificado");
    when(service.submitProduction(any())).thenReturn(r);
    StringWriter out = new StringWriter();

    JSONObject json = submit(PRODUCTION_BODY, response(out), out);

    assertEquals("ERROR", json.getString("status"));
    assertEquals("E390001: NIF no identificado", json.getJSONArray("errors").getString(0));
    verify(decl, never()).setDeclarationStatus(anyString());
    verify(decl, never()).setManualData(anyString());
    verify(declHandler).replaceAeatIncidentsNoCommit(decl,
        java.util.Collections.singletonList("E390001: NIF no identificado"),
        java.util.Collections.emptyList());
    verify(obDal).commitAndClose();
    verify(attachManager, never()).upload(any(), anyString(), anyString(), anyString(), any());
  }

  /** A transport failure is a 502 SUBMISSION_FAILED and the declaration is untouched. */
  @Test
  public void testServiceExceptionIs502() throws Exception {
    when(service.submitProduction(any())).thenThrow(new OBException("connection refused"));
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
    assertEquals(Fiscal390SubmissionSupport.ERR_SUBMISSION_FAILED, json.getString("errorCode"));
    verify(decl, never()).setDeclarationStatus(anyString());
  }

  // ── guards ──────────────────────────────────────────────────────────

  @Test
  public void testAlreadySubmittedIsRejectedWithoutCallingTheAeat() throws Exception {
    when(decl.getDeclarationStatus()).thenReturn("submitted_ext");
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
    assertEquals(Fiscal390SubmissionSupport.ERR_ALREADY_SUBMITTED, json.getString("errorCode"));
    verify(support, never()).newSubmissionService();
  }

  @Test
  public void testBlockingIncidentsRejectTheFiling() throws Exception {
    doReturn(2L).when(support).countBlockingIncidents(DECL_ID);
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
    assertEquals(Fiscal390SubmissionSupport.ERR_BLOCKING_INCIDENTS, json.getString("errorCode"));
    verify(service, never()).submitProduction(any());
  }

  @Test
  public void testMissingPresenterIs400() throws Exception {
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit("{\"presenterNif\":\" \",\"presenterName\":\"ACME\"}", resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertEquals(Fiscal390SubmissionSupport.ERR_MISSING_PRESENTER, json.getString("errorCode"));
  }

  @Test
  public void testNoCertificateIs409() throws Exception {
    when(service.hasOrgCertificate(any())).thenReturn(false);
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
    assertEquals(Fiscal390SubmissionSupport.ERR_NO_CERTIFICATE, json.getString("errorCode"));
    verify(service, never()).submitProduction(any());
  }

  /** Another model, another exercise or another org: 404, nothing filed. */
  @Test
  public void testForeignDeclarationIs404() throws Exception {
    when(decl.getFiscalModel()).thenReturn("303");
    HttpServletResponse resp = response(new StringWriter());

    support.handleSubmit("org1", 2026, "0A", request(PRODUCTION_BODY), resp);

    verify(servlet).sendError(resp, HttpServletResponse.SC_NOT_FOUND,
        "Declaration not found: " + DECL_ID);
    when(decl.getFiscalModel()).thenReturn("390");
    when(decl.getFiscalYear()).thenReturn(2025L);
    assertFalse(support.isOwned390(decl, 2026));
    when(decl.getFiscalYear()).thenReturn(2026L);
    doReturn("org2").when(handler).declarationOrgId();
    assertFalse(support.isOwned390(decl, 2026));
  }

  /** Figures that cannot be frozen stop the filing before the AEAT is called. */
  @Test
  public void testSnapshotFailureStopsBeforeTheAeat() throws Exception {
    doThrow(new OBException("no periods")).when(handler)
        .computePayload(eq("org1"), eq(2026), anyMap());
    StringWriter out = new StringWriter();

    JSONObject json = submit(PRODUCTION_BODY, response(out), out);

    assertEquals(Fiscal390SubmissionSupport.ERR_SNAPSHOT_FAILED, json.getString("errorCode"));
    verify(service, never()).submitProduction(any());
  }

  // ── test mode ───────────────────────────────────────────────────────

  /** ServValiDos with MODELO 390: the declaration is never touched, the draft PDF is attached. */
  @Test
  public void testTestModeValidatesOnly() throws Exception {
    when(service.submitValidation("390", FILE, "2026", "0A", "ES")).thenReturn(success(true));
    StringWriter out = new StringWriter();

    JSONObject json = submit("{\"testMode\":true}", response(out), out);

    assertEquals("TEST_SUCCESS", json.getString("status"));
    verify(service, never()).submitProduction(any());
    verify(handler, never()).computePayload(anyString(), anyInt(), anyMap());
    verify(decl, never()).setDeclarationStatus(anyString());
    verify(attachManager).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"), any());
    verify(declHandler).replaceAeatIncidentsNoCommit(eq(decl), any(), any());
  }

  /** The blocking guard counts only rule incidents: AEAT-origin rows are excluded by code. */
  @Test
  public void testBlockingIncidentCountExcludesAeatRows() {
    @SuppressWarnings("unchecked")
    org.openbravo.dal.service.OBQuery<org.openbravo.base.structure.BaseOBObject> query =
        mock(org.openbravo.dal.service.OBQuery.class);
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), hql.capture()))
        .thenReturn(query);
    when(query.count()).thenReturn(1);
    Fiscal390SubmissionSupport real = new Fiscal390SubmissionSupport(handler);

    assertEquals(1L, real.countBlockingIncidents(DECL_ID));
    assertTrue(hql.getValue().contains("code not like :aeat"));
    verify(query).setNamedParameter("aeat", "AEAT-%");
  }

  // ── manualData.submission ───────────────────────────────────────────

  @Test
  public void testWithSubmissionKeepsTheOtherKeysAndReplacesMalformedData() throws Exception {
    Date at = new Date(0L);
    JSONObject merged = Fiscal390SubmissionSupport.withSubmission(
        "{\"aeatInputs\":{\"A\":\"1\"}}", success(false), "u", at);
    assertEquals("1", merged.getJSONObject("aeatInputs").getString("A"));
    assertEquals("1970-01-01T00:00:00Z",
        merged.getJSONObject(Fiscal390SubmissionSupport.SUBMISSION_KEY).getString("submittedAt"));

    JSONObject replaced = Fiscal390SubmissionSupport.withSubmission("{bad", success(false), "u", at);
    assertEquals("REG1", replaced.getJSONObject(Fiscal390SubmissionSupport.SUBMISSION_KEY)
        .getString("registryNumber"));
  }
}
