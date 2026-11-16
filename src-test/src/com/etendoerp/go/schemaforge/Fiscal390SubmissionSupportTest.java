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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.hibernate.criterion.Criterion;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.common.MalformedJsonException;
import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Outcome;
import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Path;
import com.etendoerp.go.schemaforge.data.FiscalDecl;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * Unit tests for the Modelo 390 telematic filing ({@code POST /fiscal390/submit}, ETP-5716): the
 * guards, the request handed to the classic AEAT service (MODELO 390, PERIODO 0A, no NRC), the
 * persistence of an accepted filing (status, method, snapshot, {@code manualData.submission},
 * justificante attachment) and the "AEAT error keeps the draft untouched" rule; the validation
 * that precedes a real presentation, the attempt log rows every AEAT call leaves and the
 * single-attachment replacement of {@link FiscalAeatSubmissionSupport}. No network: the classic
 * {@link AEAT303SubmissionService} is a mock.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390SubmissionSupport
 * @covers com.etendoerp.go.schemaforge.FiscalAeatSubmissionSupport
 */
public class Fiscal390SubmissionSupportTest {

  private static final String DECL_ID = "D390";
  private static final String FILE = "<T3900>FILE</T3900>";
  private static final String VALIDATION_ENDPOINT =
      AEAT303SubmissionService.DEFAULT_VALIDATION_ENDPOINT;
  private static final String PRODUCTION_ENDPOINT =
      AEAT303SubmissionService.DEFAULT_PRODUCTION_ENDPOINT;
  private static final FiscalAeatAttemptLog.Declaration ATTEMPTED =
      new FiscalAeatAttemptLog.Declaration("390", 2026, "0A", DECL_ID, "org1");

  private NeoServlet servlet;
  private Fiscal390BoxesHandler handler;
  private Fiscal390SubmissionSupport support;
  private Fiscal390GenerateSupport generateSupport;
  private AEAT303SubmissionService service;
  private FiscalDecl decl;
  private OBDal obDal;
  private AttachImplementationManager attachManager;
  private FiscalDeclCrudHandler declHandler;
  private FiscalAeatAttemptLog attemptLog;

  private OBContext obContextInstance;
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
    attemptLog = mock(FiscalAeatAttemptLog.class);
    doReturn(attemptLog).when(handler).attemptLog();
    when(generateSupport.generateContent(eq("org1"), eq(2026), anyMap()))
        .thenReturn(new Fiscal390GenerateSupport.Generated(FILE, null));
    doReturn(new JSONObject("{\"boxes\":{\"86\":\"10.00\"},\"summary\":{\"annualResult\":\"10.00\"}}"))
        .when(handler).computePayload(eq("org1"), eq(2026), anyMap());

    support = spy(new Fiscal390SubmissionSupport(handler));
    service = mock(AEAT303SubmissionService.class);
    when(service.hasOrgCertificate(any())).thenReturn(true);
    when(service.getValidationEndpoint()).thenReturn(VALIDATION_ENDPOINT);
    when(service.getProductionEndpoint()).thenReturn(PRODUCTION_ENDPOINT);
    doReturn(service).when(support).newSubmissionService();
    doReturn(List.of()).when(support).blockingRuleIncidents(eq("org1"), eq(2026), any());

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
    obContextInstance = ctx;
    when(ctx.getCurrentClient()).thenReturn(client);
    User user = mock(User.class);
    when(user.getName()).thenReturn("Ayelen");
    when(ctx.getUser()).thenReturn(user);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

    attachManager = NeoAttachmentTestFixtures.mockStorageManager();
    attachMock = mockStatic(NeoAttachmentsHelper.class);
    attachMock.when(() -> NeoAttachmentsHelper.resolveTableId(anyString())).thenReturn("T1");
    attachMock.when(() -> NeoAttachmentsHelper.resolveTabId("T1", null)).thenReturn("TAB1");
    attachMock.when(NeoAttachmentsHelper::getAttachManager).thenReturn(attachManager);

    snapshotMock = mockStatic(FiscalSubmittedSnapshotSupport.class);
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
        eq(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID), anyString())).thenReturn(true);
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

  private static AEAT303SubmissionResult rejectedByTheValidator() {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(AEAT303SubmissionResult.Status.ERROR);
    r.setTestMode(true);
    r.addError("E39000101 - NIF del declarante no identificado");
    r.addWarning("A001 - Aviso informativo");
    r.setPdfContent(new byte[] { 9 });
    return r;
  }

  private static AEAT303SubmissionResult acceptedByTheValidator() {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    r.setTestMode(true);
    return r;
  }

  /** The validation that precedes a real presentation: the 5-argument call with the 390 file. */
  private void validatorAnswers(AEAT303SubmissionResult result) {
    when(service.submitValidation("390", FILE, "2026", "0A", "ES")).thenReturn(result);
  }

  /** Asserts that no validation was requested and no attempt row was written. */
  private void assertTheAeatWasNeverCalled() {
    verify(service, never()).submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString());
    verify(service, never()).submitProduction(any());
    verifyNoInteractions(attemptLog);
  }

  /** The rows the request left in the attempt log, in order; all for the same declaration. */
  private List<FiscalAeatValidationOutcome> attemptRows(int expected, String... endpoints) {
    ArgumentCaptor<FiscalAeatAttemptLog.Declaration> declaration =
        ArgumentCaptor.forClass(FiscalAeatAttemptLog.Declaration.class);
    ArgumentCaptor<FiscalAeatValidationOutcome> outcome =
        ArgumentCaptor.forClass(FiscalAeatValidationOutcome.class);
    ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
    verify(attemptLog, times(expected)).record(declaration.capture(), outcome.capture(),
        endpoint.capture());
    for (FiscalAeatAttemptLog.Declaration d : declaration.getAllValues()) {
      assertEquals(ATTEMPTED, d);
    }
    assertEquals(List.of(endpoints), endpoint.getAllValues());
    return outcome.getAllValues();
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
    // The failed presentation is an attempt too: its row follows the validation's.
    List<FiscalAeatValidationOutcome> rows = attemptRows(2, VALIDATION_ENDPOINT,
        PRODUCTION_ENDPOINT);
    assertEquals(Path.DIRECT_SUBMISSION, rows.get(1).path());
    assertEquals(Outcome.NOT_VALIDATED, rows.get(1).outcome());
    assertFalse(rows.get(1).testMode());
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
    assertTheAeatWasNeverCalled();
  }

  @Test
  public void testBlockingIncidentsRejectTheFiling() throws Exception {
    doReturn(List.of(new Fiscal390IncidentRules.Incident(Fiscal390IncidentRules.NO_MAIN_IAE,
        Fiscal390IncidentRules.SEVERITY_BLOCK, "no main IAE")))
        .when(support).blockingRuleIncidents(eq("org1"), eq(2026), any());
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
    assertEquals(Fiscal390SubmissionSupport.ERR_BLOCKING_INCIDENTS, json.getString("errorCode"));
    assertEquals(Fiscal390IncidentRules.NO_MAIN_IAE,
        json.getJSONArray("incidents").getJSONObject(0).getString("code"));
    assertTheAeatWasNeverCalled();
  }

  /** Test mode (ServValiDos) never changes the declaration, so the blocking guard is skipped. */
  @Test
  public void testBlockingIncidentsDoNotStopAValidationRun() throws Exception {
    doReturn(List.of(new Fiscal390IncidentRules.Incident(Fiscal390IncidentRules.NO_MAIN_IAE,
        Fiscal390IncidentRules.SEVERITY_BLOCK, "no main IAE")))
        .when(support).blockingRuleIncidents(eq("org1"), eq(2026), any());
    when(service.submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString())).thenReturn(success(true));
    StringWriter out = new StringWriter();

    submit("{\"testMode\":true}", response(out), out);

    verify(service).submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString());
  }

  @Test
  public void testMissingPresenterIs400() throws Exception {
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit("{\"presenterNif\":\" \",\"presenterName\":\"ACME\"}", resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertEquals(Fiscal390SubmissionSupport.ERR_MISSING_PRESENTER, json.getString("errorCode"));
    assertTheAeatWasNeverCalled();
  }

  @Test
  public void testNoCertificateIs409() throws Exception {
    when(service.hasOrgCertificate(any())).thenReturn(false);
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
    assertEquals(Fiscal390SubmissionSupport.ERR_NO_CERTIFICATE, json.getString("errorCode"));
    assertTheAeatWasNeverCalled();
  }

  /**
   * ETP-6158: the no-certificate refusal is GO text ({@code ETGO_FiscalSubmitNoCertificate}) in
   * the request language, Spanish and English.
   */
  @Test
  public void testNoCertificateMessageIsInTheRequestLanguage() throws Exception {
    when(service.hasOrgCertificate(any())).thenReturn(false);
    String[][] cases = {
        { "es_ES", "Esta organización no tiene un certificado configurado. Sube uno desde "
            + "Configuración fiscal antes de presentar en producción (el modo de prueba no "
            + "requiere certificado)." },
        { "en_US", "This organization has no certificate configured. Upload one from Fiscal "
            + "configuration before submitting in production (test mode does not require a "
            + "certificate)." } };
    for (String[] c : cases) {
      Language language = mock(Language.class);
      when(language.getLanguage()).thenReturn(c[0]);
      when(obContextInstance.getLanguage()).thenReturn(language);
      StringWriter out = new StringWriter();

      JSONObject json = submit(PRODUCTION_BODY, response(out), out);

      assertEquals(c[0], c[1], json.getJSONArray("errors").getString(0));
    }
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
    assertTheAeatWasNeverCalled();
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
    // "Validar sin presentar" is ONE call to the validator and one manual_validation row.
    verify(service, times(1)).submitValidation(anyString(), anyString(), anyString(),
        anyString(), anyString());
    List<FiscalAeatValidationOutcome> rows = attemptRows(1, VALIDATION_ENDPOINT);
    assertEquals(Path.MANUAL_VALIDATION, rows.get(0).path());
    assertEquals(Outcome.ACCEPTED, rows.get(0).outcome());
    assertTrue(rows.get(0).testMode());
  }

  /**
   * The blocking guard is evaluated fresh from the STORED inputs (the ones the file is built
   * from), never from the stored incident rows — so a stored AEAT answer cannot block the retry.
   */
  @Test
  public void testBlockingGuardEvaluatesTheStoredInputs() throws Exception {
    Fiscal390IncidentsSupport incidents = mock(Fiscal390IncidentsSupport.class);
    doReturn(incidents).when(handler).incidentsSupport();
    when(incidents.blockingIncidents(eq("org1"), eq(2026), anyString(), anyMap()))
        .thenReturn(List.of());
    Fiscal390SubmissionSupport real = new Fiscal390SubmissionSupport(handler);

    String manualData = decl.getManualData();
    assertTrue(real.blockingRuleIncidents("org1", 2026, decl).isEmpty());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> inputs = ArgumentCaptor.forClass(Map.class);
    verify(incidents).blockingIncidents(eq("org1"), eq(2026), eq(manualData),
        inputs.capture());
    assertEquals("5", inputs.getValue().get("INGRESAR_01"));
    verify(obDal, never()).createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT),
        anyString());
  }

  // ── validation before a real presentation (ETP-5716) ────────────────

  /**
   * The validator rejects the file a real presentation is about to send: nothing is presented,
   * the validator's errors become the declaration's AEAT incidents, the declaration stays a
   * draft and the only attempt row is the validation's.
   */
  @Test
  public void testProductionStopsWhenTheValidatorRejects() throws Exception {
    AEAT303SubmissionResult rejected = rejectedByTheValidator();
    validatorAnswers(rejected);
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(service, never()).submitProduction(any());
    verify(declHandler).replaceAeatIncidentsNoCommit(decl,
        List.of("E39000101 - NIF del declarante no identificado"),
        List.of("A001 - Aviso informativo"));
    verify(obDal).commitAndClose();
    verify(decl, never()).setDeclarationStatus(anyString());
    verify(decl, never()).setManualData(anyString());
    verify(decl, never()).setSubmittedSnapshot(anyString());
    verify(attachManager, never()).upload(any(), anyString(), anyString(), anyString(), any());

    verify(resp).setStatus(HttpServletResponse.SC_OK);
    assertEquals("ERROR", json.getString("status"));
    assertEquals(FiscalAeatSubmissionSupport.STAGE_VALIDATION, json.getString("stage"));
    assertFalse(json.getBoolean("testMode"));
    assertEquals("E39000101 - NIF del declarante no identificado",
        json.getJSONArray("errors").getString(0));
    assertTrue(json.isNull("pdfBase64"));

    List<FiscalAeatValidationOutcome> rows = attemptRows(1, VALIDATION_ENDPOINT);
    assertEquals(Path.PRE_SUBMISSION_VALIDATION, rows.get(0).path());
    assertEquals(Outcome.REJECTED, rows.get(0).outcome());
  }

  /**
   * The validator accepts: the presentation goes ahead with the very file that was validated,
   * and the request leaves two rows — the validation, then the presentation.
   */
  @Test
  public void testProductionPresentsTheValidatedFile() throws Exception {
    AEAT303SubmissionResult accepted = acceptedByTheValidator();
    accepted.addWarning("A001 - Aviso informativo");
    validatorAnswers(accepted);
    when(service.submitProduction(any())).thenReturn(success(false));
    StringWriter out = new StringWriter();

    JSONObject json = submit(PRODUCTION_BODY, response(out), out);

    ArgumentCaptor<String> validated = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<AEAT303SubmissionService.ProductionSubmissionRequest> presented =
        ArgumentCaptor.forClass(AEAT303SubmissionService.ProductionSubmissionRequest.class);
    InOrder order = inOrder(service);
    order.verify(service).submitValidation(eq("390"), validated.capture(), eq("2026"), eq("0A"),
        eq("ES"));
    order.verify(service).submitProduction(presented.capture());
    assertEquals(FILE, validated.getValue());
    assertEquals(validated.getValue(), field(presented.getValue(), "fileContent"));

    assertEquals("SUCCESS", json.getString("status"));
    assertFalse(json.has("stage"));
    verify(decl).setDeclarationStatus("submitted_ack");

    List<FiscalAeatValidationOutcome> rows = attemptRows(2, VALIDATION_ENDPOINT,
        PRODUCTION_ENDPOINT);
    assertEquals(Path.PRE_SUBMISSION_VALIDATION, rows.get(0).path());
    assertEquals(Outcome.ACCEPTED, rows.get(0).outcome());
    assertTrue(rows.get(0).testMode());
    assertEquals(Path.DIRECT_SUBMISSION, rows.get(1).path());
    assertEquals(Outcome.ACCEPTED, rows.get(1).outcome());
    assertFalse(rows.get(1).testMode());
  }

  /**
   * The validator does not answer: what happens next is
   * {@link FiscalAeatSubmissionSupport#PRESENT_WHEN_VALIDATOR_UNANSWERED} — present anyway, or
   * stop with {@code VALIDATION_UNAVAILABLE}.
   */
  @Test
  public void testProductionFollowsThePolicyWhenTheValidatorDoesNotAnswer() throws Exception {
    when(service.submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString())).thenThrow(new OBException("timeout", new SocketTimeoutException()));
    when(service.submitProduction(any())).thenReturn(success(false));
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = submit(PRODUCTION_BODY, resp, out);

    verify(declHandler, FiscalAeatSubmissionSupport.PRESENT_WHEN_VALIDATOR_UNANSWERED
        ? times(1) : never()).replaceAeatIncidentsNoCommit(any(), any(), any());
    if (FiscalAeatSubmissionSupport.PRESENT_WHEN_VALIDATOR_UNANSWERED) {
      verify(service).submitProduction(any());
      assertEquals("SUCCESS", json.getString("status"));
      List<FiscalAeatValidationOutcome> rows = attemptRows(2, VALIDATION_ENDPOINT,
          PRODUCTION_ENDPOINT);
      assertEquals(Path.PRE_SUBMISSION_VALIDATION, rows.get(0).path());
      assertEquals(Outcome.CONNECTION_FAILURE, rows.get(0).outcome());
      assertEquals(Path.DIRECT_SUBMISSION, rows.get(1).path());
    } else {
      verify(service, never()).submitProduction(any());
      verify(resp).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
      assertEquals(FiscalAeatSubmissionSupport.ERR_VALIDATION_UNAVAILABLE,
          json.getString("errorCode"));
      assertEquals(FiscalAeatSubmissionSupport.STAGE_VALIDATION, json.getString("stage"));
      assertEquals(Outcome.CONNECTION_FAILURE,
          attemptRows(1, VALIDATION_ENDPOINT).get(0).outcome());
    }
  }

  /** A rejected presentation (after an accepted validation) is recorded as a rejected filing. */
  @Test
  public void testARejectedPresentationLeavesItsOwnRow() throws Exception {
    validatorAnswers(acceptedByTheValidator());
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(AEAT303SubmissionResult.Status.ERROR);
    r.addError("E39000101 - NIF del declarante no identificado");
    when(service.submitProduction(any())).thenReturn(r);
    StringWriter out = new StringWriter();

    JSONObject json = submit(PRODUCTION_BODY, response(out), out);

    assertFalse(json.has("stage"));
    List<FiscalAeatValidationOutcome> rows = attemptRows(2, VALIDATION_ENDPOINT,
        PRODUCTION_ENDPOINT);
    assertEquals(Outcome.REJECTED, rows.get(1).outcome());
    assertEquals(List.of("E39000101"), rows.get(1).errorCodes());
  }

  // ── validateBeforeSubmission (shared by 303 and 390) ────────────────

  private static final FiscalAeatSubmissionSupport.FileToPresent FILE_TO_PRESENT =
      new FiscalAeatSubmissionSupport.FileToPresent("390", FILE, "2026", "0A", "ES");

  private FiscalAeatSubmissionSupport.PreSubmissionValidation validateBeforeSubmission() {
    return FiscalAeatSubmissionSupport.validateBeforeSubmission(service, attemptLog, ATTEMPTED,
        FILE_TO_PRESENT);
  }

  /** A rejection stops the presentation; its answer says so: stage, not test mode, the errors. */
  @Test
  public void testValidateBeforeSubmissionStopsOnARejection() throws Exception {
    AEAT303SubmissionResult rejected = rejectedByTheValidator();
    validatorAnswers(rejected);

    FiscalAeatSubmissionSupport.PreSubmissionValidation validation = validateBeforeSubmission();

    assertFalse(validation.proceed());
    assertSame(rejected, validation.rejected);
    assertFalse(validation.unavailable);

    JSONObject json = FiscalAeatSubmissionSupport.validationRejectedJson(validation.rejected,
        new JSONObject().put("fiscalYear", "2026"));
    assertEquals("validation", json.getString("stage"));
    assertFalse("the user asked for a presentation, not for a test", json.getBoolean("testMode"));
    assertEquals("ERROR", json.getString("status"));
    assertEquals(1, json.getJSONArray("errors").length());
    assertEquals("E39000101 - NIF del declarante no identificado",
        json.getJSONArray("errors").getString(0));
    assertEquals("A001 - Aviso informativo", json.getJSONArray("warnings").getString(0));
    assertTrue("the validator's draft is not a justificante", json.isNull("pdfBase64"));
    assertEquals("2026", json.getJSONObject("declarationData").getString("fiscalYear"));

    List<FiscalAeatValidationOutcome> rows = attemptRows(1, VALIDATION_ENDPOINT);
    assertEquals(Path.PRE_SUBMISSION_VALIDATION, rows.get(0).path());
    assertEquals("pre_submission_validation", rows.get(0).path().key());
    assertEquals(Outcome.REJECTED, rows.get(0).outcome());
    assertEquals("390", rows.get(0).model());
  }

  /** An accepted file proceeds — AEAT warnings do not stop a presentation. */
  @Test
  public void testValidateBeforeSubmissionProceedsWhenAccepted() {
    validatorAnswers(acceptedByTheValidator());
    FiscalAeatSubmissionSupport.PreSubmissionValidation clean = validateBeforeSubmission();
    assertTrue(clean.proceed());
    assertNull(clean.rejected);

    AEAT303SubmissionResult withWarnings = acceptedByTheValidator();
    withWarnings.addWarning("A001 - Aviso informativo");
    validatorAnswers(withWarnings);
    FiscalAeatSubmissionSupport.PreSubmissionValidation warned = validateBeforeSubmission();
    assertTrue(warned.proceed());
    assertNull(warned.rejected);

    List<FiscalAeatValidationOutcome> rows =
        attemptRows(2, VALIDATION_ENDPOINT, VALIDATION_ENDPOINT);
    assertEquals(Outcome.ACCEPTED, rows.get(0).outcome());
    assertEquals(Outcome.ACCEPTED, rows.get(1).outcome());
    assertEquals(List.of("A001"), rows.get(1).loggedWarningCodes());
    assertEquals(Path.PRE_SUBMISSION_VALIDATION, rows.get(1).path());
  }

  /** No answer (an exception, or no result at all) follows the policy constant; never throws. */
  @Test
  public void testValidateBeforeSubmissionFollowsThePolicyWithoutAnAnswer() {
    when(service.submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString())).thenThrow(new OBException("timeout", new SocketTimeoutException()))
        .thenReturn(null);

    FiscalAeatSubmissionSupport.PreSubmissionValidation thrown = validateBeforeSubmission();
    FiscalAeatSubmissionSupport.PreSubmissionValidation noResult = validateBeforeSubmission();

    for (FiscalAeatSubmissionSupport.PreSubmissionValidation v : List.of(thrown, noResult)) {
      assertEquals(FiscalAeatSubmissionSupport.PRESENT_WHEN_VALIDATOR_UNANSWERED, v.proceed());
      assertEquals(!FiscalAeatSubmissionSupport.PRESENT_WHEN_VALIDATOR_UNANSWERED, v.unavailable);
      assertNull(v.rejected);
    }
    List<FiscalAeatValidationOutcome> rows =
        attemptRows(2, VALIDATION_ENDPOINT, VALIDATION_ENDPOINT);
    assertEquals(Outcome.CONNECTION_FAILURE, rows.get(0).outcome());
    assertEquals(Outcome.NOT_VALIDATED, rows.get(1).outcome());
    assertEquals(Path.PRE_SUBMISSION_VALIDATION, rows.get(0).path());
    assertTrue(rows.get(0).testMode());
  }

  /** The answer of a presentation stopped because the validator could not be reached. */
  @Test
  public void testValidationUnavailableJsonShape() throws Exception {
    JSONObject json = FiscalAeatSubmissionSupport.validationUnavailableJson();

    assertEquals("ERROR", json.getString("status"));
    assertEquals("VALIDATION_UNAVAILABLE", json.getString("errorCode"));
    assertEquals("validation", json.getString("stage"));
    assertFalse(json.getBoolean("testMode"));
    assertEquals(1, json.getJSONArray("errors").length());
  }

  /**
   * The validation waits 10 s / 30 s at most, and the service gets its defaults back before the
   * presentation that follows — whether the validator answered or the call threw.
   */
  @Test
  public void testValidateBeforeSubmissionBoundsTheWaitAndRestoresTheTimeouts() {
    assertEquals(10_000, FiscalAeatSubmissionSupport.VALIDATION_CONNECT_TIMEOUT_MILLIS);
    assertEquals(30_000, FiscalAeatSubmissionSupport.VALIDATION_READ_TIMEOUT_MILLIS);
    validatorAnswers(acceptedByTheValidator());

    validateBeforeSubmission();

    InOrder answered = inOrder(service);
    answered.verify(service).withTimeouts(10_000, 30_000);
    answered.verify(service).submitValidation("390", FILE, "2026", "0A", "ES");
    answered.verify(service).withDefaultTimeouts();

    AEAT303SubmissionService failing = mock(AEAT303SubmissionService.class);
    when(failing.submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString())).thenThrow(new IllegalStateException("boom"));
    FiscalAeatSubmissionSupport.validateBeforeSubmission(failing, attemptLog, ATTEMPTED,
        FILE_TO_PRESENT);

    InOrder thrown = inOrder(failing);
    thrown.verify(failing).withTimeouts(10_000, 30_000);
    thrown.verify(failing).submitValidation("390", FILE, "2026", "0A", "ES");
    thrown.verify(failing).withDefaultTimeouts();
  }

  /** A service that cannot even take the timeouts is "no answer", and is still restored. */
  @Test
  public void testValidateBeforeSubmissionNeverThrows() {
    AEAT303SubmissionService broken = mock(AEAT303SubmissionService.class);
    when(broken.withTimeouts(10_000, 30_000)).thenThrow(new IllegalArgumentException("bad"));
    when(broken.withDefaultTimeouts()).thenThrow(new IllegalStateException("cannot restore"));
    when(broken.getValidationEndpoint()).thenThrow(new OBException("no context"));

    FiscalAeatSubmissionSupport.PreSubmissionValidation validation =
        FiscalAeatSubmissionSupport.validateBeforeSubmission(broken, attemptLog, ATTEMPTED,
            FILE_TO_PRESENT);

    assertEquals(FiscalAeatSubmissionSupport.PRESENT_WHEN_VALIDATOR_UNANSWERED,
        validation.proceed());
    verify(broken, never()).submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString());
    ArgumentCaptor<FiscalAeatValidationOutcome> outcome =
        ArgumentCaptor.forClass(FiscalAeatValidationOutcome.class);
    verify(attemptLog).record(eq(ATTEMPTED), outcome.capture(), eq((String) null));
    assertEquals(Outcome.NOT_VALIDATED, outcome.getValue().outcome());
  }

  // ── replaceAttachedPdf ──────────────────────────────────────────────

  private static final String VALIDATION_PDF = "VALIDACION-390-2026-0A.pdf";
  private static final String LEGACY_VALIDATION_PDF = "VALIDACION-justificante-390-2026-0A.pdf";

  @SuppressWarnings("unchecked")
  private OBCriteria<Attachment> attachmentsCriteria(List<Attachment> found) {
    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(criteria.list()).thenReturn(found);
    when(obDal.createCriteria(Attachment.class)).thenReturn(criteria);
    return criteria;
  }

  private void replaceValidationPdf() {
    FiscalAeatSubmissionSupport.replaceAttachedPdf(decl, obDal.get(Organization.class, "org1"),
        Fiscal390SubmissionSupport.validationFileName(2026, "0A"), new byte[] { 1, 2, 3 },
        "aeat390goval", "390", Fiscal390SubmissionSupport.legacyValidationFileName(2026, "0A"));
  }

  /**
   * Only the attachments of THIS declaration named like the validation PDF — its current name or
   * the legacy one — are looked up and removed; then the new PDF is attached under the new name.
   */
  @Test
  public void testReplaceAttachedPdfRemovesOnlyTheSupersededDocument() throws Exception {
    assertEquals(VALIDATION_PDF, Fiscal390SubmissionSupport.validationFileName(2026, "0A"));
    assertEquals(LEGACY_VALIDATION_PDF,
        Fiscal390SubmissionSupport.legacyValidationFileName(2026, "0A"));
    Attachment current = mock(Attachment.class);
    Attachment legacy = mock(Attachment.class);
    OBCriteria<Attachment> criteria = attachmentsCriteria(List.of(current, legacy));

    replaceValidationPdf();

    ArgumentCaptor<Criterion> restrictions = ArgumentCaptor.forClass(Criterion.class);
    verify(criteria, times(3)).add(restrictions.capture());
    List<String> where = new java.util.ArrayList<>();
    for (Criterion c : restrictions.getAllValues()) {
      where.add(c.toString());
    }
    assertEquals(List.of("table.id=T1", "record=" + DECL_ID,
        "name in (" + LEGACY_VALIDATION_PDF + ", " + VALIDATION_PDF + ")"), where);
    verify(criteria).setFilterOnReadableOrganization(false);

    InOrder order = inOrder(attachManager);
    order.verify(attachManager).delete(current);
    order.verify(attachManager).delete(legacy);
    ArgumentCaptor<File> file = ArgumentCaptor.forClass(File.class);
    order.verify(attachManager).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"),
        file.capture());
    assertEquals(VALIDATION_PDF, file.getValue().getName());
    verify(attachManager, times(2)).delete(any());
    verify(obDal, never()).commitAndClose();
  }

  /** The previous PDF cannot be removed (or looked up): the new one is attached all the same. */
  @Test
  public void testReplaceAttachedPdfStillAttachesWhenTheDeleteFails() throws Exception {
    Attachment previous = mock(Attachment.class);
    attachmentsCriteria(List.of(previous));
    doThrow(new OBException("cannot delete")).when(attachManager).delete(previous);

    replaceValidationPdf();

    verify(attachManager).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"), any());

    when(obDal.createCriteria(Attachment.class)).thenThrow(new OBException("no session"));
    replaceValidationPdf();

    verify(attachManager, times(2)).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"), any());
  }

  /** Nothing to supersede: nothing is deleted. */
  @Test
  public void testReplaceAttachedPdfDeletesNothingWhenThereIsNoPreviousOne() throws Exception {
    attachmentsCriteria(Collections.emptyList());

    replaceValidationPdf();

    verify(attachManager, never()).delete(any());
    verify(attachManager).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"), any());
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

  // ── request body (shared by /fiscal303/submit and /fiscal390/submit) ─────────

  @Test
  public void testReadJsonBodyGoesThroughTheBoundedParser() throws Exception {
    assertEquals(0, FiscalAeatSubmissionSupport.readJsonBody(requestWithBody("")).length());
    assertTrue(FiscalAeatSubmissionSupport.readJsonBody(requestWithBody("{\"testMode\":true}"))
        .getBoolean(FiscalAeatSubmissionSupport.PARAM_TEST_MODE));

    assertRejected("{bad");
    assertRejected("{\"a\":".repeat(200) + "1" + "}".repeat(200));
  }

  private static void assertRejected(String body) throws Exception {
    try {
      FiscalAeatSubmissionSupport.readJsonBody(requestWithBody(body));
      fail("Expected MalformedJsonException for body: " + body);
    } catch (MalformedJsonException expected) {
      // bounded parser refused it — the 400 "Invalid JSON body" path in runDispatch
    }
  }

  private static HttpServletRequest requestWithBody(String body) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
    return req;
  }
}
