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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;
import org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationData;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * Unit tests for the AEAT 303 telematic submission entity added to {@link Fiscal303BoxesHandler}
 * ({@code POST /neo/fiscal303/submit}).
 *
 * <p>Covers, in two groups:</p>
 * <ul>
 *   <li>Pure-logic / JSON-shape helpers (no mocking): {@code resolveNrcForSubmission},
 *       {@code safeFileToken}, {@code belongsTo}, {@code declarationDataJson},
 *       {@code buildFailureJson}, {@code buildSubmissionResultJson}, and the
 *       {@code isKnownEntity}/{@code allowsPost} routing contract.</li>
 *   <li>End-to-end {@code handle("submit", ...)} flows, with the real DB-resolution chain
 *       ({@code resolveTaxReport}/{@code resolveAcctSchema}/{@code resolvePeriods}) fully mocked
 *       via {@code stubFileGeneration} (a real {@link OBTL_TaxReport_I} test double,
 *       {@link FakeTaxReportGenerator}, backs the reflective {@code Class.forName(...)} call) and
 *       {@link AEAT303SubmissionService} intercepted via {@code mockConstruction} — covering the
 *       pre-flight gates (missing id, declaration not found, missing presenter, no certificate)
 *       and the full outcomes (test-mode success, production success with declaration
 *       persistence, AEAT error without persistence).</li>
 * </ul>
 */
public class Fiscal303SubmitHandlerTest {

  /**
   * Minimal, well-formed Modelo 303 page-1 content: 12-char page marker, then declaration type
   * (1), NIF (9), name (80, space-padded), fiscal year (4), period (2) — contiguous, matching
   * {@code AEAT303DeclarationDataExtractor}'s fixed offsets exactly (108 chars total), so
   * {@code AEAT303DeclarationDataExtractor.extract} never throws in these tests.
   */
  private static final String SAMPLE_303_CONTENT = "<T30301000>" + "O" + "B12345678"
      + StringUtils.rightPad("ACME SA", 80) + "2026" + "2T";

  /**
   * AD_Message key/translation pair used by the {@code Fiscal303SubmissionSupport} translation
   * regression tests below (ETP-5027) — the same real key/text used by
   * {@code NeoMessageTranslatorTest}/{@code AbstractFiscalHandlerTest}.
   */
  private static final String AD_MESSAGE_KEY = "@AEAT303_Bad_Bankruptcy_Statement_Date_Format@";
  private static final String TRANSLATED_MESSAGE =
      "Formato incorrecto para Fecha declaración del concurso (DDMMYYYY)";

  private Fiscal303BoxesHandler handler;

  @Before
  public void setUp() {
    handler = new Fiscal303BoxesHandler(mock(NeoServlet.class));
  }

  // ── resolveNrcForSubmission ─────────────────────────────────────────────────

  @Test
  public void testResolveNrcForSubmission_typeI_passesThrough() {
    assertEquals("NRC123", Fiscal303BoxesHandler.resolveNrcForSubmission("I", "NRC123"));
  }

  @Test
  public void testResolveNrcForSubmission_nonTypeI_returnsEmpty() {
    assertEquals("", Fiscal303BoxesHandler.resolveNrcForSubmission("O", "NRC123"));
    assertEquals("", Fiscal303BoxesHandler.resolveNrcForSubmission("V", "NRC123"));
    assertEquals("", Fiscal303BoxesHandler.resolveNrcForSubmission(null, "NRC123"));
  }

  @Test
  public void testResolveNrcForSubmission_nullNrcOnTypeI_returnsEmptyNotNull() {
    assertEquals("", Fiscal303BoxesHandler.resolveNrcForSubmission("I", null));
  }

  // ── safeFileToken ─────────────────────────────────────────────────────────

  @Test
  public void testSafeFileToken_blankReturnsNA() {
    assertEquals("NA", Fiscal303BoxesHandler.safeFileToken(null));
    assertEquals("NA", Fiscal303BoxesHandler.safeFileToken(""));
    assertEquals("NA", Fiscal303BoxesHandler.safeFileToken("   "));
  }

  @Test
  public void testSafeFileToken_specialCharsReplacedWithUnderscore() {
    assertEquals("2T_2026", Fiscal303BoxesHandler.safeFileToken("2T/2026"));
    assertEquals("A_B", Fiscal303BoxesHandler.safeFileToken("A B"));
  }

  // ── belongsTo ─────────────────────────────────────────────────────────────

  @Test
  public void testBelongsTo_nullDeclReturnsFalse() {
    assertFalse(handler.belongsTo(null, "client1", "org1"));
  }

  @Test
  public void testBelongsTo_mismatchedClientReturnsFalse() {
    FiscalDecl decl = mock(FiscalDecl.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("other-client");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org1");
    when(decl.getClient()).thenReturn(client);
    when(decl.getOrganization()).thenReturn(org);

    assertFalse(handler.belongsTo(decl, "client1", "org1"));
  }

  @Test
  public void testBelongsTo_mismatchedOrgReturnsFalse() {
    FiscalDecl decl = mock(FiscalDecl.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("other-org");
    when(decl.getClient()).thenReturn(client);
    when(decl.getOrganization()).thenReturn(org);

    assertFalse(handler.belongsTo(decl, "client1", "org1"));
  }

  @Test
  public void testBelongsTo_matchingReturnsTrue() {
    FiscalDecl decl = mock(FiscalDecl.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org1");
    when(decl.getClient()).thenReturn(client);
    when(decl.getOrganization()).thenReturn(org);

    assertTrue(handler.belongsTo(decl, "client1", "org1"));
  }

  // ── routing contract: isKnownEntity / allowsPost ─────────────────────────────

  @Test
  public void testIsKnownEntity_submitIsKnown() {
    assertTrue(handler.isKnownEntity("submit"));
    assertTrue(handler.isKnownEntity("boxes"));
    assertTrue(handler.isKnownEntity("generate"));
    assertFalse(handler.isKnownEntity("invoices"));
  }

  @Test
  public void testAllowsPost_onlySubmitAllowsPost() {
    assertTrue(handler.allowsPost("submit"));
    assertFalse(handler.allowsPost("boxes"));
    assertFalse(handler.allowsPost("generate"));
    assertFalse(handler.allowsPost("modified"));
  }

  // ── declarationDataJson ───────────────────────────────────────────────────

  @Test
  public void testDeclarationDataJson_populatesAllFields() throws Exception {
    AEAT303DeclarationData data = new AEAT303DeclarationData();
    data.setNif("B12345678");
    data.setBusinessName("ACME SA");
    data.setFiscalYear("2026");
    data.setPeriod("2T");
    data.setDeclarationType("O");
    data.setResultAmount(new BigDecimal("123.45"));
    data.setIban("ES1234567890123456789012");

    JSONObject json = handler.declarationDataJson(data);

    assertEquals("B12345678", json.getString("nif"));
    assertEquals("ACME SA", json.getString("businessName"));
    assertEquals("2026", json.getString("fiscalYear"));
    assertEquals("2T", json.getString("period"));
    assertEquals("O", json.getString("declarationType"));
    assertEquals("123.45", json.getString("resultAmount"));
    assertEquals("ES1234567890123456789012", json.getString("iban"));
  }

  @Test
  public void testDeclarationDataJson_nullsBecomeJsonNull() throws Exception {
    AEAT303DeclarationData data = new AEAT303DeclarationData();
    // resultAmount and iban left null; string fields left null too.

    JSONObject json = handler.declarationDataJson(data);

    assertEquals("", json.getString("nif"));
    assertTrue(json.isNull("resultAmount"));
    assertTrue(json.isNull("iban"));
  }

  // ── buildFailureJson ──────────────────────────────────────────────────────

  @Test
  public void testBuildFailureJson_shape() throws Exception {
    JSONObject json = handler.buildFailureJson(false, "NO_CERTIFICATE", "No cert configured");

    assertEquals("ERROR", json.getString("status"));
    assertFalse(json.getBoolean("testMode"));
    assertEquals("NO_CERTIFICATE", json.getString("errorCode"));
    assertEquals(1, json.getJSONArray("errors").length());
    assertEquals("No cert configured", json.getJSONArray("errors").getString(0));
    assertEquals(0, json.getJSONArray("warnings").length());
  }

  // ── buildSubmissionResultJson ─────────────────────────────────────────────

  @Test
  public void testBuildSubmissionResultJson_productionSuccess() throws Exception {
    AEAT303SubmissionResult result = new AEAT303SubmissionResult();
    result.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    result.setTestMode(false);
    result.setCsv("CSV123");
    result.setPresentationDate("2026-07-21 10:00");
    result.setRegistryNumber("REG1");
    result.setJustificanteNumber("JUS1");
    result.setPdfContent("pdf-bytes".getBytes());

    JSONObject json = handler.buildSubmissionResultJson(result, sampleDeclarationData());

    assertEquals("SUCCESS", json.getString("status"));
    assertFalse(json.getBoolean("testMode"));
    assertEquals("CSV123", json.getString("csv"));
    assertEquals("REG1", json.getString("registryNumber"));
    assertEquals("JUS1", json.getString("justificanteNumber"));
    assertFalse(json.isNull("pdfBase64"));
    assertFalse(json.getBoolean("pdfDownloadFailed"));
    assertEquals(0, json.getJSONArray("errors").length());
  }

  @Test
  public void testBuildSubmissionResultJson_testModeSuccess() throws Exception {
    AEAT303SubmissionResult result = new AEAT303SubmissionResult();
    result.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    result.setTestMode(true);

    JSONObject json = handler.buildSubmissionResultJson(result, sampleDeclarationData());

    assertEquals("TEST_SUCCESS", json.getString("status"));
    assertTrue(json.getBoolean("testMode"));
  }

  @Test
  public void testBuildSubmissionResultJson_errorRegardlessOfTestMode() throws Exception {
    AEAT303SubmissionResult prodResult = new AEAT303SubmissionResult();
    prodResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    prodResult.setTestMode(false);
    prodResult.addError("E0100803 - some AEAT cause");

    JSONObject prodJson = handler.buildSubmissionResultJson(prodResult, sampleDeclarationData());
    assertEquals("ERROR", prodJson.getString("status"));
    assertEquals(1, prodJson.getJSONArray("errors").length());
    assertEquals("E0100803 - some AEAT cause", prodJson.getJSONArray("errors").getString(0));

    AEAT303SubmissionResult testResult = new AEAT303SubmissionResult();
    testResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    testResult.setTestMode(true);

    JSONObject testJson = handler.buildSubmissionResultJson(testResult, sampleDeclarationData());
    assertEquals("ERROR", testJson.getString("status"));
  }

  @Test
  public void testBuildSubmissionResultJson_noPdfContent_pdfBase64Null() throws Exception {
    AEAT303SubmissionResult result = new AEAT303SubmissionResult();
    result.setStatus(AEAT303SubmissionResult.Status.SUCCESS);

    JSONObject json = handler.buildSubmissionResultJson(result, sampleDeclarationData());

    assertTrue(json.isNull("pdfBase64"));
  }

  private static AEAT303DeclarationData sampleDeclarationData() {
    AEAT303DeclarationData data = new AEAT303DeclarationData();
    data.setFiscalYear("2026");
    data.setPeriod("2T");
    data.setDeclarationType("O");
    return data;
  }

  // ── handleSubmit (end-to-end via handle()) ────────────────────────────────

  /** GET /fiscal303/submit is method-gated the same as any other unknown-for-POST entity. */
  @Test
  public void testHandleSubmit_missingIdReturns400() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn("T2");
    // "id" is null (default) — the guard must trigger before any DB access.

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1")) {
      h.handle("submit", "POST", req, res);
    }

    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testHandleSubmit_declarationNotFoundReturns404() throws IOException {
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn("T2");
    when(req.getParameter("id")).thenReturn("missing-decl");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "missing-decl")).thenReturn(null);

      h.handle("submit", "POST", req, res);
    }

    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  @Test
  public void testHandleSubmit_missingPresenterInProductionReturns400Json() throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      FiscalDecl decl = matchingDecl("client1", "org1");
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);

      h.handle("submit", "POST", req, res);
    }

    verify(res).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    JSONObject body = new JSONObject(capturedBody.toString());
    assertEquals("ERROR", body.getString("status"));
    assertEquals("MISSING_PRESENTER", body.getString("errorCode"));
  }

  @Test
  public void testHandleSubmit_noCertificateInProductionReturns409Json() throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(mockService.hasOrgCertificate(any())).thenReturn(false))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      FiscalDecl decl = matchingDecl("client1", "org1");
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));

      h.handle("submit", "POST", req, res);
    }

    verify(res).setStatus(HttpServletResponse.SC_CONFLICT);
    JSONObject body = new JSONObject(capturedBody.toString());
    assertEquals("ERROR", body.getString("status"));
    assertEquals("NO_CERTIFICATE", body.getString("errorCode"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeHappyPath_doesNotMutateDeclaration() throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);
    validationResult.setPdfContent("draft-pdf".getBytes());

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);
    }

    JSONObject body = new JSONObject(capturedBody.toString());
    assertEquals("TEST_SUCCESS", body.getString("status"));
    assertFalse(body.isNull("pdfBase64"));
    // Test mode must never mutate the declaration record.
    verify(decl, never()).setDeclarationStatus(anyString());
    // ETP-4755: submissionMethod is a production-only, real-success write — test mode never sets it.
    verify(decl, never()).setSubmissionMethod(anyString());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_productionHappyPath_updatesDeclarationStatus() throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult prodResult = new AEAT303SubmissionResult();
    prodResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    prodResult.setTestMode(false);
    prodResult.setCsv("CSV999");
    // No PDF content: exercises the "attach skipped, no PDF to attach" branch without needing
    // filesystem/AD_Tab setup — attachment persistence itself is covered separately (known gap,
    // see Fiscal303BoxesHandler#attachJustificante javadoc).

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenReturn(prodResult);
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("SUCCESS", body.getString("status"));
      assertEquals("CSV999", body.getString("csv"));
      verify(decl).setDeclarationStatus("submitted_ack");
      verify(decl).setFileExternal(false);
      // ETP-4755: a real production success also sets submissionMethod = aeat_telematic,
      // disambiguating this path from the two manual "Presentado" paths that also land on
      // submitted_ack.
      verify(decl).setSubmissionMethod("aeat_telematic");
      verify(obDal, times(1)).commitAndClose();
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_productionAeatError_doesNotMutateDeclaration() throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult errorResult = new AEAT303SubmissionResult();
    errorResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    errorResult.setTestMode(false);
    errorResult.addError("E0100803 - Razon social del Declarante");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenReturn(errorResult);
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);
    }

    JSONObject body = new JSONObject(capturedBody.toString());
    assertEquals("ERROR", body.getString("status"));
    assertEquals("E0100803 - Razon social del Declarante", body.getJSONArray("errors").getString(0));
    verify(decl, never()).setDeclarationStatus(anyString());
    // ETP-4755: a failed production submission must not set submissionMethod either.
    verify(decl, never()).setSubmissionMethod(anyString());
  }

  /**
   * PARTIALLY FIXED (ETP-5027). {@code handleSubmit}'s own try/catch around
   * {@code submitProduction}/{@code submitValidation} only catches {@link OBException} — the
   * documented AEAT-specific failure type. A genuinely unexpected {@link RuntimeException} (a bug
   * in the reflective call, a {@code ClassCastException}, a {@code NullPointerException} from an
   * AEAT response shape the parser wasn't built for, ...) is still NOT caught there: it
   * propagates through {@code dispatch()}'s generic catch and is wrapped in a
   * {@code FiscalHandlerException(cause)}.
   *
   * <p>What ETP-5027 changed is the message that reaches the client.
   * {@code FiscalHandlerException} is built as {@code super(cause)}, so its {@code getMessage()}
   * is {@code cause.toString()} — previously forwarded verbatim, leaking the internal exception
   * CLASS NAME (e.g. {@code "java.lang.NullPointerException: boom"}). {@code
   * AbstractFiscalHandler.userMessage()} now unwraps that wrapper and reports the cause's own
   * message, and runs it through {@code NeoMessageTranslator} so AD_Message keys are resolved.
   * The full exception, class name and stack trace included, is still written to the server log
   * by the same catch block — diagnosability is unchanged, only the browser-facing text is.</p>
   *
   * <p>Still open, and deliberately out of ETP-5027's scope: the response arrives in a completely
   * different JSON shape ({@code {"error":{"message":...,"status":...}}}, from {@link
   * NeoResponse#error}) than every other submit-flow outcome ({@code
   * {"status":"ERROR","errorCode":...}}, from {@code buildFailureJson}/{@code
   * buildSubmissionResultJson}). The frontend (`AeatSubmitFlow.jsx`) has no top-level {@code
   * status} field to branch on for this shape, so it falls through to the generic "connection
   * error" message — safe (no crash, no misleading success), but the user gets no indication this
   * was a server-side crash rather than a real network issue.</p>
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_unexpectedRuntimeExceptionFromAeatService_reportsUnwrappedCause()
      throws Exception {
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    HttpServletResponse res = mock(HttpServletResponse.class);
    FiscalDecl decl = matchingDecl("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenThrow(new NullPointerException("boom"));
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);
    }

    // ETP-5027: the cause's own message, with the FiscalHandlerException wrapper's
    // class-name prefix stripped — no internal type names reach the browser.
    verify(servlet).sendError(eq(res), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        argThat(msg -> "boom".equals(msg)));
    // The crash path must not also corrupt persisted state.
    verify(decl, never()).setDeclarationStatus(anyString());
  }

  /**
   * ETP-5027: {@code Fiscal303SubmissionSupport}'s AEAT submission failure branch (site 2) — the
   * documented {@link OBException} catch, distinct from the unexpected-{@code RuntimeException}
   * path exercised just above — must also run {@code e.getMessage()} through
   * {@code NeoMessageTranslator} so a real AEAT-raised {@code @AD_Message_Key@} comes back
   * translated instead of as the literal key.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_aeatOBExceptionCarriesAdMessageKey_returnsTranslatedMessage()
      throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    FiscalDecl decl = matchingDecl("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenThrow(new OBException(AD_MESSAGE_KEY));
            })) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY))
          .thenReturn(TRANSLATED_MESSAGE);
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("SUBMISSION_FAILED", body.getString("errorCode"));
      assertEquals(TRANSLATED_MESSAGE, body.getJSONArray("errors").getString(0));
    }
    verify(decl, never()).setDeclarationStatus(anyString());
  }

  /**
   * ETP-5027: same AEAT-failure branch (site 2), but without a live OBContext — translation
   * cannot run, so the raw {@code @AD_Message_Key@} text must be returned unchanged rather than
   * throwing or blanking the error out (mirrors {@code NeoMessageTranslator}'s own fallback
   * contract).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_aeatOBExceptionCarriesAdMessageKey_fallsBackWhenNoContext()
      throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    FiscalDecl decl = matchingDecl("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenThrow(new OBException(AD_MESSAGE_KEY));
            })) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY))
          .thenThrow(new NullPointerException("no OBContext"));
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("SUBMISSION_FAILED", body.getString("errorCode"));
      assertEquals(AD_MESSAGE_KEY, body.getJSONArray("errors").getString(0));
    }
  }

  /**
   * FIXED (Sentinel QA BUG-1, ETP-4456): {@code handleSubmit} now reads {@code
   * decl.getDeclarationStatus()} before ever regenerating the file or touching the AEAT service.
   * A production submission for a declaration already {@code submitted_ack} is rejected outright
   * with {@code errorCode=ALREADY_SUBMITTED} — {@link AEAT303SubmissionService} is never even
   * constructed ({@code mockConstruction}'s {@code constructed()} list stays empty), which proves
   * no stray call could possibly reach the real AEAT endpoint. This blocks the silent
   * double-submission risk (double-click, network retry, second tab); filing a genuine
   * complementaria for an already-submitted declaration remains a separate, manual process,
   * deliberately not implemented by this endpoint (see {@code handleSubmit} javadoc).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_alreadySubmittedDeclaration_blocksResubmission() throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");
    // Declaration was already successfully submitted in a prior call.
    when(decl.getDeclarationStatus()).thenReturn("submitted_ack");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);

      h.handle("submit", "POST", req, res);

      // The guard fires before Organization lookup, file generation, and — crucially — before
      // `new AEAT303SubmissionService()` itself, so the AEAT service is never even constructed.
      assertTrue("AEAT303SubmissionService must not be constructed for an already-submitted decl",
          serviceMock.constructed().isEmpty());
      verify(res).setStatus(HttpServletResponse.SC_CONFLICT);
      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("ERROR", body.getString("status"));
      assertEquals("ALREADY_SUBMITTED", body.getString("errorCode"));
      verify(decl, never()).setDeclarationStatus(anyString());
    }
  }

  /**
   * Companion to the guard above: test-mode (ServValiDos) validations must NEVER be blocked by
   * the already-submitted check, regardless of the declaration's current status. Test-mode never
   * changes declaration status, so re-validating an already-{@code submitted_ack} declaration is
   * harmless and legitimate (e.g. a user re-checking the file before deciding to go to
   * production again for a later period).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeResubmissionOfAlreadySubmittedDeclaration_isAllowed()
      throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");
    // Already submitted_ack from a prior PRODUCTION call — must not block a TEST-mode re-check.
    when(decl.getDeclarationStatus()).thenReturn("submitted_ack");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      verify(serviceMock.constructed().get(0))
          .submitValidation(anyString(), anyString(), anyString(), anyString());
      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("TEST_SUCCESS", body.getString("status"));
      // Test mode must never mutate the declaration, resubmission guard or not.
      verify(decl, never()).setDeclarationStatus(anyString());
    }
  }

  /**
   * Coverage gap (Sentinel QA, ETP-4456), not a bug: {@code handleSubmit}'s try/catch around
   * {@code generateElectronicFile} — distinct from the AEAT-call try/catch, and unlike it, this
   * one catches generic {@link Exception}, not just {@link OBException} — had zero test coverage
   * before this. Confirms a mid-generation failure (e.g. malformed accounting data throwing from
   * the reflective {@code OBTL_TaxReport_I} implementation) degrades gracefully: it responds with
   * the same AEAT-shaped JSON contract as every other outcome ({@code errorCode:
   * SUBMISSION_FAILED}), and — critically — is distinguishable from a real AEAT rejection because
   * an actual rejection never sets {@code errorCode} at all. AEAT is never contacted for this
   * failure mode, confirmed via the service mock never receiving a submit call.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_fileGenerationThrows_returnsSubmissionFailedWithoutCallingAeat()
      throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) ->
                when(mockService.hasOrgCertificate(any())).thenReturn(true))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, ThrowingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);

      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("ERROR", body.getString("status"));
      assertEquals("SUBMISSION_FAILED", body.getString("errorCode"));
      // The AEAT service must never have been reached — this failed before submission.
      verify(serviceMock.constructed().get(0), never()).submitProduction(any());
      verify(decl, never()).setDeclarationStatus(anyString());
    }

    verify(res).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  /**
   * ETP-5027: {@code Fiscal303SubmissionSupport}'s file-generation failure branch (site 1) must
   * run {@code e.getMessage()} through {@code NeoMessageTranslator} before it reaches the
   * response — a raw {@code @AD_Message_Key@} token must never leak to the browser the way
   * {@code @AEAT303_Bad_Bankruptcy_Statement_Date_Format@} did before this fix.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_fileGenerationThrowsAdMessageKey_returnsTranslatedMessage()
      throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) ->
                when(mockService.hasOrgCertificate(any())).thenReturn(true))) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY))
          .thenReturn(TRANSLATED_MESSAGE);
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, KeyThrowingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);

      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("SUBMISSION_FAILED", body.getString("errorCode"));
      assertEquals(TRANSLATED_MESSAGE, body.getJSONArray("errors").getString(0));
    }
  }

  /**
   * ETP-5027: without a live OBContext (translation cannot run — the everyday unit-test
   * situation), the file-generation failure branch must degrade to the raw message unchanged
   * rather than throwing or blanking the error out.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_fileGenerationThrowsAdMessageKey_fallsBackWhenNoContext()
      throws Exception {
    StringWriter capturedBody = new StringWriter();
    HttpServletResponse res = responseCapturing(capturedBody);
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    NeoServlet servlet = mock(NeoServlet.class);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(servlet);
    FiscalDecl decl = matchingDecl("client1", "org1");

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) ->
                when(mockService.hasOrgCertificate(any())).thenReturn(true))) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY))
          .thenThrow(new NullPointerException("no OBContext"));
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, KeyThrowingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);

      JSONObject body = new JSONObject(capturedBody.toString());
      assertEquals("SUBMISSION_FAILED", body.getString("errorCode"));
      assertEquals(AD_MESSAGE_KEY, body.getJSONArray("errors").getString(0));
    }
  }

  // ── handleSubmit — Justificante attachment behavior (test-mode attach follow-up, ETP-4456) ──
  //
  // Covers the gating change in handleSubmit: `result.isSuccessful()` now branches on `testMode`
  // to call either `attachTestJustificante` (test mode: attaches a "TEST-" prefixed PDF, never
  // touches `decl`) or the pre-existing `persistSuccessfulSubmission` (production: attaches the
  // real justificante AND mutates `decl`). `NeoAttachmentsHelper` is mocked statically so
  // `attachJustificante`'s tab-resolution + `AttachImplementationManager.upload(...)` call can be
  // observed instead of silently no-op'ing (as it does, best-effort, in the tests above that
  // don't mock it).

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeSuccessWithPdf_attachesTestPrefixedJustificante()
      throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);
    validationResult.setPdfContent("draft-pdf".getBytes());

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
      verify(aim).upload(any(), eq("tab-1"), any(), any(), fileCaptor.capture());
      String uploadedName = fileCaptor.getValue().getName();
      assertTrue("expected a TEST- prefixed filename, got: " + uploadedName,
          uploadedName.startsWith("TEST-justificante-303-"));
      assertTrue(uploadedName.endsWith(".pdf"));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeSuccessWithPdf_neverMutatesOrSavesDeclaration()
      throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);
    validationResult.setPdfContent("draft-pdf".getBytes());

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      verify(decl, never()).setDeclarationStatus(anyString());
      verify(decl, never()).setDeclarationFileName(anyString());
      verify(decl, never()).setFileExternal(any(Boolean.class));
      // ETP-4755: test mode must never set submissionMethod either — only a real production
      // success does (persistSuccessfulSubmission).
      verify(decl, never()).setSubmissionMethod(anyString());
      verify(obDal, never()).save(decl);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeSuccessWithNullPdf_neverAttempsToAttach() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);
    // No PDF content — the attach path must not even be attempted.

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      attachMock.verify(NeoAttachmentsHelper::getAttachManager, never());
      verify(aim, never()).upload(any(), anyString(), any(), any(), any());
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_productionSuccessWithPdf_attachesNonTestPrefixedJustificante()
      throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult prodResult = new AEAT303SubmissionResult();
    prodResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    prodResult.setTestMode(false);
    prodResult.setCsv("CSV999");
    prodResult.setPdfContent("real-pdf".getBytes());

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenReturn(prodResult);
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      verify(decl).setDeclarationStatus("submitted_ack");
      verify(decl).setFileExternal(false);
      // ETP-4755: paired assertion — this production success also sets submissionMethod.
      verify(decl).setSubmissionMethod("aeat_telematic");
      verify(obDal).save(decl);

      ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
      verify(aim).upload(any(), eq("tab-1"), any(), any(), fileCaptor.capture());
      String uploadedName = fileCaptor.getValue().getName();
      assertFalse("production filename must never carry the TEST- marker: " + uploadedName,
          uploadedName.startsWith("TEST-"));
      assertTrue(uploadedName.startsWith("justificante-303-"));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeAeatError_neverAttachesOrMutatesDeclaration()
      throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult errorResult = new AEAT303SubmissionResult();
    errorResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    errorResult.setTestMode(true);
    errorResult.addError("E0100803 - some AEAT cause");

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(errorResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      attachMock.verify(NeoAttachmentsHelper::getAttachManager, never());
      verify(decl, never()).setDeclarationStatus(anyString());
      verify(obDal, never()).save(decl);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_productionAeatError_neverAttachesOrMutatesDeclaration()
      throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult errorResult = new AEAT303SubmissionResult();
    errorResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    errorResult.setTestMode(false);
    errorResult.addError("E0100803 - Razon social del Declarante");

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenReturn(errorResult);
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      h.handle("submit", "POST", req, res);

      attachMock.verify(NeoAttachmentsHelper::getAttachManager, never());
      verify(decl, never()).setDeclarationStatus(anyString());
      verify(obDal, never()).save(decl);
    }
  }

  // ── AEAT incidents persistence (ETP-4456) ──────────────────────────────────────────────
  //
  // handleSubmit must persist result.getErrors() into ETGO_Fiscal_Decl_Incident on EVERY
  // attempt — test mode and production, success or failure — via
  // AbstractFiscalHandler#replaceIncidents -> FiscalDeclCrudHandler#replaceIncidents. These
  // tests stub the OBQuery/OBProvider plumbing that method uses so its side effects (delete
  // existing rows, insert one per error) are directly observable.

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeAeatError_persistsIncidents() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult errorResult = new AEAT303SubmissionResult();
    errorResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    errorResult.setTestMode(true);
    errorResult.addError("35068 - El resultado a ingresar es distinto de cero.");
    errorResult.addError("E010124 - Para periodo mensual de 01 a 11.");

    BaseOBObject staleInc = mock(BaseOBObject.class);
    BaseOBObject newInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(errorResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString())).thenReturn(incQuery);
      when(incQuery.list()).thenReturn(Collections.singletonList(staleInc));
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      h.handle("submit", "POST", req, res);

      // Existing (prior-attempt) row deleted, one new row inserted per AEAT error.
      verify(obDal).remove(staleInc);
      verify(obDal, times(2)).save(newInc);
      verify(newInc).set("code", "35068");
      verify(newInc).set("message", "El resultado a ingresar es distinto de cero.");
      verify(newInc).set("code", "E010124");
      verify(newInc).set("message", "Para periodo mensual de 01 a 11.");
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeSuccess_clearsIncidentsWithNoInsert() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult successResult = new AEAT303SubmissionResult();
    successResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    successResult.setTestMode(true);
    // No errors added — the success case.

    BaseOBObject staleIncFromPriorFailedAttempt = mock(BaseOBObject.class);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(successResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString())).thenReturn(incQuery);
      // Simulates a row left over from a previous FAILED attempt — must be deleted even though
      // this attempt succeeds, so the tab ends up empty rather than showing stale errors.
      when(incQuery.list()).thenReturn(Collections.singletonList(staleIncFromPriorFailedAttempt));

      h.handle("submit", "POST", req, res);

      verify(obDal).remove(staleIncFromPriorFailedAttempt);
      // Empty errors list -> delete only, nothing (re)inserted.
      verify(obDal, never()).save(argThat(o -> o != decl && o != null && o != staleIncFromPriorFailedAttempt));
    }
  }

  /**
   * MEDIUM bug closed (Sentinel finding, ETP-4456): every incidents-persistence test above this
   * point uses {@code testMode:true}. Both existing production-mode tests
   * ({@code testHandleSubmit_productionHappyPath_updatesDeclarationStatus} /
   * {@code testHandleSubmit_productionAeatError_doesNotMutateDeclaration}) never stub the
   * incidents-persistence DAL calls ({@code OBProvider}/the incidents {@code OBQuery}), so an NPE
   * inside {@code persistIncidentsBestEffort} for a production call is silently swallowed by its
   * best-effort {@code catch} — those tests would pass identically whether or not production-mode
   * incident persistence actually works. This test properly stubs the incidents plumbing (mirroring
   * {@link #testHandleSubmit_testModeAeatError_persistsIncidents}) for a PRODUCTION submission that
   * gets an AEAT rejection with both errors and warnings, and verifies — via explicit mock
   * verification, not a vacuous assertion — that both are actually persisted with the correct
   * severities. Closes the coverage gap for real.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_productionAeatErrorAndWarning_persistsBothSeverities()
      throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult mixedResult = new AEAT303SubmissionResult();
    mixedResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    mixedResult.setTestMode(false);
    mixedResult.addError("E0100803 - Razon social del Declarante.");
    mixedResult.addWarning("A001 - Aviso informativo AEAT.");

    BaseOBObject staleInc = mock(BaseOBObject.class);
    BaseOBObject newInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenReturn(mixedResult);
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(incQuery);
      // Simulates a row left over from a prior attempt — must be deleted regardless of mode.
      when(incQuery.list()).thenReturn(Collections.singletonList(staleInc));
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      h.handle("submit", "POST", req, res);

      verify(obDal).remove(staleInc);
      verify(obDal, times(2)).save(newInc);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "E0100803");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "Razon social del Declarante.");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_BLOCK);
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_CODE, "A001");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_MESSAGE,
          "Aviso informativo AEAT.");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_WARN);
      // A failed production submission must never mutate the declaration itself.
      verify(decl, never()).setDeclarationStatus(anyString());
    }
  }

  /**
   * A submission whose AEAT response carries BOTH errors and warnings (e.g. a validation attempt
   * that is rejected but also returns informational avisos) must persist both groups, tagged with
   * the correct severity — {@code result.getErrors()} as {@code block}, {@code
   * result.getWarnings()} as {@code warn}. End-to-end via {@code handle("submit", ...)}, matching
   * {@link #testHandleSubmit_testModeAeatError_persistsIncidents} but exercising the previously
   * discarded warnings channel.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeAeatErrorAndWarning_persistsBothSeverities() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult mixedResult = new AEAT303SubmissionResult();
    mixedResult.setStatus(AEAT303SubmissionResult.Status.ERROR);
    mixedResult.setTestMode(true);
    mixedResult.addError("35068 - El resultado a ingresar es distinto de cero.");
    mixedResult.addWarning("A001 - Aviso informativo AEAT.");

    BaseOBObject newInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mixedResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString())).thenReturn(incQuery);
      when(incQuery.list()).thenReturn(Collections.emptyList());
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      h.handle("submit", "POST", req, res);

      verify(obDal, times(2)).save(newInc);
      verify(newInc).set("code", "35068");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_BLOCK);
      verify(newInc).set("code", "A001");
      verify(newInc).set("message", "Aviso informativo AEAT.");
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_WARN);
    }
  }

  /**
   * A subsequent successful attempt (no errors, no warnings) after a prior mixed attempt must
   * clear BOTH severities — not just the blocking ones. Regression guard for a partial-clear bug
   * (e.g. only deleting {@code block} rows and leaving stale {@code warn} rows behind).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_successAfterMixedAttempt_clearsBothSeverities() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult successResult = new AEAT303SubmissionResult();
    successResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    successResult.setTestMode(true);
    // No errors, no warnings added — the clean success case.

    BaseOBObject staleBlockInc = mock(BaseOBObject.class);
    BaseOBObject staleWarnInc = mock(BaseOBObject.class);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(successResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString())).thenReturn(incQuery);
      // Simulates rows of BOTH severities left over from the prior mixed attempt.
      when(incQuery.list()).thenReturn(Arrays.asList(staleBlockInc, staleWarnInc));

      h.handle("submit", "POST", req, res);

      verify(obDal).remove(staleBlockInc);
      verify(obDal).remove(staleWarnInc);
      verify(obDal, never()).save(argThat(o -> o != decl && o != null
          && o != staleBlockInc && o != staleWarnInc));
    }
  }

  // ── Transaction atomicity — single consolidated commit (ETP-4456) ─────────────────────────
  //
  // Before this fix, a successful submission could trigger up to 3 independent
  // commitAndClose() calls: one inside persistIncidentsBestEffort (via the committing
  // FiscalDeclCrudHandler#replaceIncidents), one inside persistSuccessfulSubmission, and one
  // inside attachJustificante. A process death between any two of those could leave a reachable
  // partial state (e.g. incidents persisted but the declaration status/attachment not updated).
  // The fix routes everything through the no-commit variants and a single, final
  // commitSubmissionBestEffort(declId) call. These two tests exercise a FULL success path —
  // incidents write (with a warning, so insertIncidents actually runs) + the status/attachment
  // write + the PDF attach — for both production and test mode, and assert commitAndClose() is
  // invoked exactly ONCE for the whole call, not 2-3 times.

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_productionFullSuccessPath_commitsExactlyOnce() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1",
        "{\"testMode\":false,\"presenterNif\":\"B12345678\",\"presenterName\":\"ACME SA\"}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult prodResult = new AEAT303SubmissionResult();
    prodResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    prodResult.setTestMode(false);
    prodResult.setCsv("CSV999");
    prodResult.setPdfContent("real-pdf".getBytes());
    // A success can still carry AEAT warnings (avisos) — exercises insertIncidents for real
    // rather than the "empty lists, delete-only" branch.
    prodResult.addWarning("A001 - Aviso informativo AEAT.");

    BaseOBObject newInc = mock(BaseOBObject.class);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class, (mockService, ctx) -> {
              when(mockService.hasOrgCertificate(any())).thenReturn(true);
              when(mockService.submitProduction(any())).thenReturn(prodResult);
            })) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(incQuery);
      when(incQuery.list()).thenReturn(Collections.emptyList());
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      h.handle("submit", "POST", req, res);

      // All three write paths actually ran...
      verify(decl).setDeclarationStatus("submitted_ack");
      // ETP-4755: paired assertion — the consolidated production success path also sets
      // submissionMethod, part of the same single commit as everything else here.
      verify(decl).setSubmissionMethod("aeat_telematic");
      verify(obDal).save(decl);
      verify(aim).upload(any(), eq("tab-1"), any(), any(), any(File.class));
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_WARN);
      // ...but they all share ONE consolidated commit, not one each.
      verify(obDal, times(1)).commitAndClose();
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_testModeFullSuccessPath_commitsExactlyOnce() throws Exception {
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");
    when(decl.getId()).thenReturn("decl-1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);
    validationResult.setPdfContent("draft-pdf".getBytes());
    validationResult.addWarning("A001 - Aviso informativo AEAT.");

    BaseOBObject newInc = mock(BaseOBObject.class);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerMock = mockStatic(OBProvider.class);
        MockedStatic<NeoAttachmentsHelper> attachMock = mockAttachInfra(aim);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal);

      OBQuery<BaseOBObject> incQuery = mock(OBQuery.class);
      when(obDal.createQuery(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT), anyString()))
          .thenReturn(incQuery);
      when(incQuery.list()).thenReturn(Collections.emptyList());
      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL_INCIDENT)).thenReturn(newInc);

      h.handle("submit", "POST", req, res);

      // Attach + incidents write ran, declaration itself stays untouched (test mode)...
      ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
      verify(aim).upload(any(), eq("tab-1"), any(), any(), fileCaptor.capture());
      assertTrue(fileCaptor.getValue().getName().startsWith("TEST-justificante-303-"));
      verify(decl, never()).setDeclarationStatus(anyString());
      // ETP-4755: test mode never sets submissionMethod, even on the full success path.
      verify(decl, never()).setSubmissionMethod(anyString());
      verify(newInc).set(FiscalDeclCrudHandler.PROPERTY_INCIDENT_SEVERITY,
          FiscalDeclCrudHandler.SEVERITY_WARN);
      // ...but everything still shares ONE consolidated commit.
      verify(obDal, times(1)).commitAndClose();
    }
  }

  // ── generateElectronicFile — AEAT request param forwarding (bug fix, ETP-4456) ─────────────
  //
  // Regression coverage for the bug where Fiscal303BoxesHandler built inputParams from only
  // three hardcoded keys, silently dropping every AEAT-protocol param the frontend sends
  // (IBAN, BIC, Special_Compensations, ...). For declaration types D/G/I/V/U/X, AEAT303Report2014
  // hard-requires IBAN downstream and throws when it is missing — the exact error the user hit
  // submitting a tipo=U (domiciliación) declaration.

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_forwardsIbanFromRequestParams() throws Exception {
    RecordingTaxReportGenerator.lastInputParams = null;
    HttpServletResponse res = responseCapturing(new StringWriter());
    Map<String, String[]> extraParams = new HashMap<>();
    extraParams.put("IBAN", new String[]{"ES1234567890123456789012"});
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}", extraParams);
    when(req.getParameter("tipo")).thenReturn("U");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, RecordingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);
    }

    assertEquals("ES1234567890123456789012", RecordingTaxReportGenerator.lastInputParams.get("IBAN"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_omitsAeatParamsAbsentFromRequest() throws Exception {
    RecordingTaxReportGenerator.lastInputParams = null;
    HttpServletResponse res = responseCapturing(new StringWriter());
    Map<String, String[]> extraParams = new HashMap<>();
    extraParams.put("IBAN", new String[]{"ES1234567890123456789012"});
    // BIC is deliberately absent from the request.
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}", extraParams);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, RecordingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);
    }

    assertFalse("BIC was never in the request and must not appear (no blank-string pollution)",
        RecordingTaxReportGenerator.lastInputParams.containsKey("BIC"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_structuralParamsNeverMergedIntoInputParams() throws Exception {
    RecordingTaxReportGenerator.lastInputParams = null;
    HttpServletResponse res = responseCapturing(new StringWriter());
    // "period" is a structural routing param (already consumed via request.getParameter("period"))
    // and must never be forwarded as a literal AEAT param, even though it is present in the map
    // returned by request.getParameterMap() (real servlet containers expose it there too).
    Map<String, String[]> extraParams = new HashMap<>();
    extraParams.put("period", new String[]{"T2"});
    extraParams.put("year", new String[]{"2026"});
    extraParams.put("tipo", new String[]{"U"});
    extraParams.put("id", new String[]{"decl-1"});
    HttpServletRequest req = requestFor("2026", "T2", "decl-1", "{\"testMode\":true}", extraParams);
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, RecordingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);
    }

    Map<String, String> params = RecordingTaxReportGenerator.lastInputParams;
    assertFalse(params.containsKey("period"));
    assertFalse(params.containsKey("year"));
    assertFalse(params.containsKey("tipo"));
    assertFalse(params.containsKey("id"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testHandleSubmit_hardcodedParamsUnchangedWithNoExtraRequestParams() throws Exception {
    RecordingTaxReportGenerator.lastInputParams = null;
    HttpServletResponse res = responseCapturing(new StringWriter());
    HttpServletRequest req =
        requestFor("2026", "T2", "decl-1", "{\"testMode\":true}", Collections.emptyMap());
    when(req.getParameter("tipo")).thenReturn("U");
    Fiscal303BoxesHandler h = new Fiscal303BoxesHandler(mock(NeoServlet.class));
    FiscalDecl decl = matchingDecl("client1", "org1");

    AEAT303SubmissionResult validationResult = new AEAT303SubmissionResult();
    validationResult.setStatus(AEAT303SubmissionResult.Status.SUCCESS);
    validationResult.setTestMode(true);

    try (MockedStatic<OBContext> ctxMock = mockContext("client1", "org1");
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedConstruction<AEAT303SubmissionService> serviceMock =
            mockConstruction(AEAT303SubmissionService.class,
                (mockService, ctx) -> when(
                    mockService.submitValidation(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(validationResult))) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(FiscalDecl.class, "decl-1")).thenReturn(decl);
      when(obDal.get(Organization.class, "org1")).thenReturn(mock(Organization.class));
      stubFileGeneration(obDal, RecordingTaxReportGenerator.class);

      h.handle("submit", "POST", req, res);
    }

    Map<String, String> params = RecordingTaxReportGenerator.lastInputParams;
    assertEquals("303_T2_2026", params.get("FileName"));
    assertEquals("Y", params.get("Declaration_U"));
    assertEquals("100", params.get("ToPublicTreasury"));
    assertEquals("No extra keys must be present when the request has no extra params",
        3, params.size());
  }

  // ── test helpers ──────────────────────────────────────────────────────────

  /**
   * Stubs the real DB-resolution chain {@code generateElectronicFile} walks (
   * {@code resolveTaxReport} → {@code resolveAcctSchema} → {@code resolvePeriods}), pointing the
   * reflective {@code Class.forName(taxReport.getJavaClassName())} call at {@link
   * FakeTaxReportGenerator} so the real, unmodified {@code generateElectronicFile} executes end
   * to end and returns {@link #SAMPLE_303_CONTENT}.
   *
   * <p>Earlier revision of these tests tried to bypass this chain entirely by spying {@code
   * Fiscal303BoxesHandler} and stubbing the package-private {@code generateElectronicFile}
   * directly via {@code doReturn(...).when(spy).generateElectronicFile(...)}. That does not work
   * reliably here: the call happens as a <b>self-invocation</b> several hops deep (
   * {@code AbstractFiscalHandler.handle()} → {@code dispatch()} → the private
   * {@code handleSubmit()} → {@code generateElectronicFile()}), and the spy's stub was never
   * observed — the real method ran instead and hit the (deliberately incomplete, for those
   * tests' purposes) {@code OBCriteria} mocks, producing a NullPointerException on
   * {@code crit.add(...)} because the unstubbed {@code createCriteria(...)} returned {@code
   * null}. Fully mocking the real resolution chain instead (this method) sidesteps the
   * self-invocation question entirely and, as a side benefit, exercises more real handler code.
   */
  @SuppressWarnings("unchecked")
  private static void stubFileGeneration(OBDal obDal) {
    stubFileGeneration(obDal, FakeTaxReportGenerator.class);
  }

  /**
   * Same as {@link #stubFileGeneration(OBDal)} but lets the caller point the reflective
   * {@code Class.forName(...)} call at a different {@link OBTL_TaxReport_I} test double — used by
   * {@code testHandleSubmit_fileGenerationThrows_returnsSubmissionFailedWithoutCallingAeat} to
   * exercise the "file generation itself fails mid-way" branch with {@link
   * ThrowingTaxReportGenerator}.
   */
  @SuppressWarnings("unchecked")
  private static void stubFileGeneration(OBDal obDal, Class<? extends OBTL_TaxReport_I> generatorClass) {
    TaxReport taxReport = mock(TaxReport.class);
    when(taxReport.getId()).thenReturn("report-1");
    when(taxReport.getJavaClassName()).thenReturn(generatorClass.getName());
    OBCriteria<TaxReport> taxCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(TaxReport.class)).thenReturn(taxCriteria);
    when(taxCriteria.add(any())).thenReturn(taxCriteria);
    when(taxCriteria.setMaxResults(1)).thenReturn(taxCriteria);
    when(taxCriteria.list()).thenReturn(Collections.singletonList(taxReport));

    AcctSchema acctSchema = mock(AcctSchema.class);
    when(acctSchema.getId()).thenReturn("acct-1");
    OBCriteria<AcctSchema> acctCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AcctSchema.class)).thenReturn(acctCriteria);
    when(acctCriteria.add(any())).thenReturn(acctCriteria);
    when(acctCriteria.setMaxResults(1)).thenReturn(acctCriteria);
    when(acctCriteria.list()).thenReturn(Collections.singletonList(acctSchema));

    Year fiscalYear = mock(Year.class);
    when(fiscalYear.getId()).thenReturn("year-1");
    Period period = mock(Period.class);
    when(period.getId()).thenReturn("period-1");
    when(period.getYear()).thenReturn(fiscalYear);

    Session session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    Query<Period> periodQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(Period.class))).thenReturn(periodQuery);
    when(periodQuery.setParameter(anyString(), any())).thenReturn(periodQuery);
    when(periodQuery.list()).thenReturn(Collections.singletonList(period));
  }

  /**
   * Minimal real {@link OBTL_TaxReport_I} implementation used only so
   * {@code Fiscal303BoxesHandler#generateElectronicFile}'s reflective
   * {@code Class.forName(...).getDeclaredConstructor().newInstance()} call resolves to something
   * real and returns {@link #SAMPLE_303_CONTENT} — see {@link #stubFileGeneration}.
   */
  public static final class FakeTaxReportGenerator implements OBTL_TaxReport_I {
    @Override
    public HashMap<String, Object> generateElectronicFile(String strOrgId, String strReportId,
        String strAcctSchemaId, String strYearId, String strPeriodId,
        Map<String, String> inputParams) {
      HashMap<String, Object> result = new HashMap<>();
      result.put("file", SAMPLE_303_CONTENT);
      return result;
    }
  }

  /**
   * {@link OBTL_TaxReport_I} test double that always throws, simulating a mid-generation failure
   * (e.g. malformed accounting data) — used by
   * {@code testHandleSubmit_fileGenerationThrows_returnsSubmissionFailedWithoutCallingAeat} to
   * verify {@code handleSubmit}'s dedicated try/catch around {@code generateElectronicFile}
   * degrades gracefully (AEAT-shaped JSON, {@code errorCode=SUBMISSION_FAILED}, AEAT never
   * contacted) rather than propagating like an uncaught {@link RuntimeException} would.
   */
  public static final class ThrowingTaxReportGenerator implements OBTL_TaxReport_I {
    @Override
    public HashMap<String, Object> generateElectronicFile(String strOrgId, String strReportId,
        String strAcctSchemaId, String strYearId, String strPeriodId,
        Map<String, String> inputParams) {
      throw new IllegalStateException("Malformed accounting data — cannot generate file");
    }
  }

  /**
   * {@link OBTL_TaxReport_I} test double that throws an {@link OBException} carrying a raw
   * {@code @AD_Message_Key@} token (mirroring what {@code AEAT303Report2023#isValidDate} actually
   * raises via {@code OBTL_Exception}, e.g. {@code AD_MESSAGE_KEY}) — used by the ETP-5027
   * translation regression tests below to verify {@code handleSubmit}'s file-generation catch
   * block ({@code Fiscal303SubmissionSupport} site 1) runs the message through
   * {@code NeoMessageTranslator} before it reaches {@code buildFailureJson}.
   */
  public static final class KeyThrowingTaxReportGenerator implements OBTL_TaxReport_I {
    @Override
    public HashMap<String, Object> generateElectronicFile(String strOrgId, String strReportId,
        String strAcctSchemaId, String strYearId, String strPeriodId,
        Map<String, String> inputParams) {
      throw new OBException(AD_MESSAGE_KEY);
    }
  }

  /**
   * {@link OBTL_TaxReport_I} test double that records the {@code inputParams} map it was called
   * with into a static field — used by the {@code generateElectronicFile} AEAT-param-forwarding
   * regression tests below (ETP-4456) to inspect what {@code Fiscal303BoxesHandler} actually built,
   * since {@code generateElectronicFile} is only reachable end-to-end via {@code handleSubmit}
   * (see {@link #stubFileGeneration} javadoc on why direct spying does not work here). Static
   * because {@code Class.forName(...).getDeclaredConstructor().newInstance()} creates a fresh
   * instance per call; each test resets the field before invoking {@code handle(...)}.
   */
  public static final class RecordingTaxReportGenerator implements OBTL_TaxReport_I {
    static Map<String, String> lastInputParams;

    @Override
    public HashMap<String, Object> generateElectronicFile(String strOrgId, String strReportId,
        String strAcctSchemaId, String strYearId, String strPeriodId,
        Map<String, String> inputParams) {
      lastInputParams = inputParams;
      HashMap<String, Object> result = new HashMap<>();
      result.put("file", SAMPLE_303_CONTENT);
      return result;
    }
  }

  private static FiscalDecl matchingDecl(String clientId, String orgId) {
    FiscalDecl decl = mock(FiscalDecl.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(decl.getClient()).thenReturn(client);
    when(decl.getOrganization()).thenReturn(org);
    return decl;
  }

  /**
   * Mocks the {@link NeoAttachmentsHelper} static façade so {@code attachJustificante} (invoked
   * from either {@code attachTestJustificante} or {@code persistSuccessfulSubmission}) resolves a
   * fixed table/tab id and hands out the given {@link AttachImplementationManager} mock instead of
   * hitting the real DB / Weld container — letting tests observe {@code upload(...)} calls instead
   * of the method silently degrading to a no-op (best-effort, per {@code attachJustificante}
   * javadoc) as it does in tests that don't mock this class.
   */
  private static MockedStatic<NeoAttachmentsHelper> mockAttachInfra(AttachImplementationManager aim) {
    MockedStatic<NeoAttachmentsHelper> attachMock = mockStatic(NeoAttachmentsHelper.class);
    attachMock.when(() -> NeoAttachmentsHelper.resolveTableId(anyString())).thenReturn("table-1");
    attachMock.when(() -> NeoAttachmentsHelper.resolveTabId(anyString(), any())).thenReturn("tab-1");
    attachMock.when(NeoAttachmentsHelper::getAttachManager).thenReturn(aim);
    return attachMock;
  }

  private static MockedStatic<OBContext> mockContext(String clientId, String orgId) {
    MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
    OBContext context = mock(OBContext.class);
    Client contextClient = mock(Client.class);
    when(contextClient.getId()).thenReturn(clientId);
    Organization contextOrg = mock(Organization.class);
    when(contextOrg.getId()).thenReturn(orgId);
    when(context.getCurrentClient()).thenReturn(contextClient);
    when(context.getCurrentOrganization()).thenReturn(contextOrg);
    ctxMock.when(OBContext::getOBContext).thenReturn(context);
    return ctxMock;
  }

  private static HttpServletRequest requestFor(String year, String period, String declId,
      String jsonBody) throws IOException {
    return requestFor(year, period, declId, jsonBody, Collections.emptyMap());
  }

  /**
   * Same as {@link #requestFor(String, String, String, String)} but also stubs {@code
   * getParameterMap()} with the given extra AEAT params — exercised by the {@code
   * generateElectronicFile} param-forwarding tests below. {@code getParameterMap()} is stubbed
   * unconditionally (even to an empty map) because {@code generateElectronicFile} always calls
   * it; an unstubbed Mockito mock would return {@code null} there and NPE on {@code entrySet()}.
   */
  private static HttpServletRequest requestFor(String year, String period, String declId,
      String jsonBody, Map<String, String[]> extraParams) throws IOException {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("year")).thenReturn(year);
    when(req.getParameter("period")).thenReturn(period);
    when(req.getParameter("id")).thenReturn(declId);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(jsonBody)));
    when(req.getParameterMap()).thenReturn(extraParams);
    return req;
  }

  private static HttpServletResponse responseCapturing(StringWriter capturedBody)
      throws IOException {
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(res.getWriter()).thenReturn(new PrintWriter(capturedBody));
    return res;
  }
}
