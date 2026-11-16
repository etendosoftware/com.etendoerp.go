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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Outcome;
import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Path;
import com.etendoerp.go.schemaforge.data.FiscalDecl;

/**
 * Unit tests for {@code POST /fiscal390/generate-validated} ({@link Fiscal390FileValidationSupport},
 * ETP-5716): the file is always handed over, and the AEAT validator's answer decides what happens
 * to the declaration's AEAT incidents, to the attempt log and to the validation PDF. No network:
 * the classic {@link AEAT303SubmissionService} is a mock.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390FileValidationSupport
 */
public class Fiscal390FileValidationSupportTest {

  private static final String DECL_ID = "D390";
  /** Characters whose ISO-8859-1 and UTF-8 encodings differ. */
  private static final String FILE = "<T3900>ESPA\u00d1A A\u00d1O 2026 \u00c1\u00c9\u00cd</T3900>";
  private static final String ENDPOINT = AEAT303SubmissionService.DEFAULT_VALIDATION_ENDPOINT;
  private static final String VALIDATION_PDF_NAME = "VALIDACION-390-2026-0A.pdf";

  private NeoServlet servlet;
  private Fiscal390BoxesHandler handler;
  private Fiscal390FileValidationSupport support;
  private Fiscal390GenerateSupport generateSupport;
  private FiscalDeclCrudHandler declHandler;
  private FiscalAeatAttemptLog attemptLog;
  private AEAT303SubmissionService service;
  private FiscalDecl decl;
  private OBDal obDal;
  private AttachImplementationManager attachManager;
  private Map<String, String> inputs;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<NeoAttachmentsHelper> attachMock;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    handler = spy(new Fiscal390BoxesHandler(servlet));
    doReturn("org1").when(handler).declarationOrgId();
    generateSupport = mock(Fiscal390GenerateSupport.class);
    doReturn(generateSupport).when(handler).generateSupport();
    declHandler = mock(FiscalDeclCrudHandler.class);
    doReturn(declHandler).when(handler).declHandler();
    attemptLog = mock(FiscalAeatAttemptLog.class);
    doReturn(attemptLog).when(handler).attemptLog();
    doReturn(new Fiscal390SubmissionSupport(handler)).when(handler).submissionSupport();
    inputs = new LinkedHashMap<>();
    inputs.put("INGRESAR_01", "5");
    when(generateSupport.generateContent("org1", 2026, inputs))
        .thenReturn(new Fiscal390GenerateSupport.Generated(FILE, null));

    service = mock(AEAT303SubmissionService.class);
    when(service.getValidationEndpoint()).thenReturn(ENDPOINT);
    support = spy(new Fiscal390FileValidationSupport(handler));
    doReturn(service).when(support).newValidationService();

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

    obDal = mock(OBDal.class);
    when(obDal.get(FiscalDecl.class, DECL_ID)).thenReturn(decl);
    when(obDal.get(Organization.class, "org1")).thenReturn(org);
    OBCriteria<Attachment> noPrevious = mock(OBCriteria.class);
    when(noPrevious.list()).thenReturn(Collections.emptyList());
    when(obDal.createCriteria(Attachment.class)).thenReturn(noPrevious);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);

    OBContext ctx = mock(OBContext.class);
    when(ctx.getCurrentClient()).thenReturn(client);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

    attachManager = NeoAttachmentTestFixtures.mockStorageManager();
    attachMock = mockStatic(NeoAttachmentsHelper.class);
    attachMock.when(() -> NeoAttachmentsHelper.resolveTableId(anyString())).thenReturn("T1");
    attachMock.when(() -> NeoAttachmentsHelper.resolveTabId("T1", null)).thenReturn("TAB1");
    attachMock.when(NeoAttachmentsHelper::getAttachManager).thenReturn(attachManager);
  }

  @After
  public void tearDown() {
    attachMock.close();
    obContextMock.close();
    obDalMock.close();
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private static HttpServletRequest request(String declId) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("id")).thenReturn(declId);
    return req;
  }

  private static HttpServletResponse response(StringWriter out) throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(out, true));
    return resp;
  }

  /** Runs the endpoint for the session's declaration and returns its JSON answer. */
  private JSONObject generateValidated(HttpServletResponse resp, StringWriter out)
      throws Exception {
    support.handle("org1", 2026, "0A", inputs, request(DECL_ID), resp);
    return new JSONObject(out.toString());
  }

  private JSONObject generateValidated() throws Exception {
    StringWriter out = new StringWriter();
    return generateValidated(response(out), out);
  }

  private static AEAT303SubmissionResult answer(boolean accepted) {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(accepted ? AEAT303SubmissionResult.Status.SUCCESS
        : AEAT303SubmissionResult.Status.ERROR);
    r.setTestMode(true);
    return r;
  }

  private void validatorAnswers(AEAT303SubmissionResult result) {
    when(service.submitValidation("390", FILE, "2026", "0A", "ES")).thenReturn(result);
  }

  private void validatorFails(RuntimeException failure) {
    when(service.submitValidation(anyString(), anyString(), anyString(), anyString(),
        anyString())).thenThrow(failure);
  }

  /** The single row the attempt left in the attempt log. */
  private FiscalAeatValidationOutcome recordedAttempt() {
    ArgumentCaptor<FiscalAeatAttemptLog.Declaration> declaration =
        ArgumentCaptor.forClass(FiscalAeatAttemptLog.Declaration.class);
    ArgumentCaptor<FiscalAeatValidationOutcome> outcome =
        ArgumentCaptor.forClass(FiscalAeatValidationOutcome.class);
    verify(attemptLog).record(declaration.capture(), outcome.capture(), eq(ENDPOINT));
    assertEquals(new FiscalAeatAttemptLog.Declaration("390", 2026, "0A", DECL_ID, "org1"),
        declaration.getValue());
    assertEquals(Path.DOWNLOAD, outcome.getValue().path());
    assertTrue(outcome.getValue().testMode());
    return outcome.getValue();
  }

  private void assertFileIsInTheAnswer(JSONObject json) throws Exception {
    assertEquals("390_0A_2026.txt", json.getString("fileName"));
    assertArrayEquals(FILE.getBytes(StandardCharsets.ISO_8859_1),
        Base64.getDecoder().decode(json.getString("fileBase64")));
  }

  // ── accepted ────────────────────────────────────────────────────────

  /** A clean answer leaves no AEAT incident (replaced by none) and commits. */
  @Test
  public void acceptedClearsTheAeatIncidentsAndCommits() throws Exception {
    validatorAnswers(answer(true));
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = generateValidated(resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_OK);
    assertFileIsInTheAnswer(json);
    JSONObject validation = json.getJSONObject("validation");
    assertEquals("accepted", validation.getString("outcome"));
    assertEquals("download", validation.getString("path"));
    assertTrue(validation.getBoolean("testMode"));
    assertEquals(0, validation.getJSONArray("errors").length());
    verify(declHandler).replaceAeatIncidentsNoCommit(decl, Collections.emptyList(),
        Collections.emptyList());
    verify(obDal).commitAndClose();
    verify(obDal, never()).rollbackAndClose();
    assertEquals(Outcome.ACCEPTED, recordedAttempt().outcome());
  }

  /** The validator is given a bounded wait before it is called. */
  @Test
  public void theValidatorIsCalledWithABoundedWait() throws Exception {
    validatorAnswers(answer(true));

    generateValidated();

    InOrder order = inOrder(service);
    order.verify(service).withTimeouts(10_000, 30_000);
    order.verify(service).submitValidation("390", FILE, "2026", "0A", "ES");
    verify(service, never()).submitProduction(any());
  }

  // ── rejected ────────────────────────────────────────────────────────

  /** The AEAT errors replace the incidents as errors, its warnings as warnings; one commit. */
  @Test
  public void rejectedReplacesTheAeatIncidentsWithTheAnswer() throws Exception {
    AEAT303SubmissionResult rejected = answer(false);
    rejected.addError("E39000101 - NIF del declarante no identificado");
    rejected.addError("texto sin codigo");
    rejected.addWarning("A001 - Aviso informativo");
    validatorAnswers(rejected);

    JSONObject json = generateValidated();

    assertFileIsInTheAnswer(json);
    JSONObject validation = json.getJSONObject("validation");
    assertEquals("rejected", validation.getString("outcome"));
    assertEquals("E39000101", validation.getJSONArray("errorCodes").getString(0));
    assertEquals(2, validation.getJSONArray("errors").length());
    assertEquals("A001", validation.getJSONArray("warnings").getJSONObject(0).getString("code"));
    verify(declHandler).replaceAeatIncidentsNoCommit(decl,
        List.of("E39000101 - NIF del declarante no identificado", "texto sin codigo"),
        List.of("A001 - Aviso informativo"));
    verify(obDal).commitAndClose();
    FiscalAeatValidationOutcome attempt = recordedAttempt();
    assertEquals(Outcome.REJECTED, attempt.outcome());
    assertEquals(List.of("E39000101", FiscalAeatValidationOutcome.UNCODED),
        attempt.loggedErrorCodes());
    assertEquals(List.of("A001"), attempt.loggedWarningCodes());
  }

  // ── no answer ───────────────────────────────────────────────────────

  /** A timeout / refused connection anywhere in the cause chain: the incidents stay as they were. */
  @Test
  public void anIoFailureIsAConnectionFailureAndLeavesTheIncidentsUntouched() throws Exception {
    validatorFails(new OBException("AEAT unreachable",
        new IllegalStateException(new ConnectException("refused"))));
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = generateValidated(resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_OK);
    assertFileIsInTheAnswer(json);
    assertEquals("connection_failure", json.getJSONObject("validation").getString("outcome"));
    verifyNoInteractions(declHandler);
    verify(obDal, never()).commitAndClose();
    verify(attachManager, never()).upload(any(), anyString(), anyString(), anyString(), any());
    assertEquals(Outcome.CONNECTION_FAILURE, recordedAttempt().outcome());
  }

  /** Any other failure (an answer that is not the validator's): not validated, incidents kept. */
  @Test
  public void anyOtherFailureIsNotValidatedAndLeavesTheIncidentsUntouched() throws Exception {
    validatorFails(new OBException("Unexpected AEAT answer"));

    JSONObject json = generateValidated();

    assertFileIsInTheAnswer(json);
    assertEquals("not_validated", json.getJSONObject("validation").getString("outcome"));
    verifyNoInteractions(declHandler);
    verify(obDal, never()).commitAndClose();
    assertEquals(Outcome.NOT_VALIDATED, recordedAttempt().outcome());
  }

  // ── request guards ──────────────────────────────────────────────────

  @Test
  public void aMissingIdIs400AndNothingIsGeneratedOrValidated() throws Exception {
    HttpServletResponse resp = response(new StringWriter());

    for (String blank : new String[] { null, "  " }) {
      support.handle("org1", 2026, "0A", inputs, request(blank), resp);
    }

    verify(servlet, org.mockito.Mockito.times(2)).sendError(resp,
        HttpServletResponse.SC_BAD_REQUEST, "Missing required param: id");
    verify(generateSupport, never()).generateContent(anyString(), anyInt(), anyMap());
    verify(support, never()).newValidationService();
    verifyNoInteractions(attemptLog);
  }

  /** Another model, exercise, client or org — or no such declaration: 404, the AEAT is not called. */
  @Test
  public void aForeignDeclarationIs404AndNothingIsGeneratedOrValidated() throws Exception {
    HttpServletResponse resp = response(new StringWriter());
    when(decl.getFiscalModel()).thenReturn("303");

    support.handle("org1", 2026, "0A", inputs, request(DECL_ID), resp);
    support.handle("org1", 2026, "0A", inputs, request("UNKNOWN"), resp);

    verify(servlet).sendError(resp, HttpServletResponse.SC_NOT_FOUND,
        "Declaration not found: " + DECL_ID);
    verify(servlet).sendError(resp, HttpServletResponse.SC_NOT_FOUND,
        "Declaration not found: UNKNOWN");
    verify(generateSupport, never()).generateContent(anyString(), anyInt(), anyMap());
    verify(support, never()).newValidationService();
    verifyNoInteractions(attemptLog);
  }

  // ── best-effort persistence ─────────────────────────────────────────

  /** The incidents cannot be written: rolled back, and the user still gets the file and the answer. */
  @Test
  public void aFailedIncidentsWriteStillReturnsTheFile() throws Exception {
    AEAT303SubmissionResult rejected = answer(false);
    rejected.addError("E39000101 - NIF del declarante no identificado");
    validatorAnswers(rejected);
    doThrow(new OBException("incidents table locked")).when(declHandler)
        .replaceAeatIncidentsNoCommit(any(), any(), any());
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = generateValidated(resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_OK);
    assertFileIsInTheAnswer(json);
    assertEquals("rejected", json.getJSONObject("validation").getString("outcome"));
    verify(obDal).rollbackAndClose();
    verify(obDal, never()).commitAndClose();
    assertEquals(Outcome.REJECTED, recordedAttempt().outcome());
  }

  /** A commit that fails is answered the same way. */
  @Test
  public void aFailedCommitStillReturnsTheFile() throws Exception {
    validatorAnswers(answer(true));
    doThrow(new OBException("commit failed")).when(obDal).commitAndClose();
    doThrow(new OBException("rollback failed too")).when(obDal).rollbackAndClose();

    JSONObject json = generateValidated();

    assertFileIsInTheAnswer(json);
    assertEquals("accepted", json.getJSONObject("validation").getString("outcome"));
  }

  // ── the file ────────────────────────────────────────────────────────

  /** {@code fileBase64} is the ISO-8859-1 bytes of the file — the bytes {@code GET generate} serves. */
  @Test
  public void theFileBytesAreIso88591() throws Exception {
    validatorAnswers(answer(true));

    JSONObject json = generateValidated();

    byte[] served = Base64.getDecoder().decode(json.getString("fileBase64"));
    assertEquals(FILE.length(), served.length);
    assertEquals(FILE, new String(served, StandardCharsets.ISO_8859_1));
    assertFalse(java.util.Arrays.equals(FILE.getBytes(StandardCharsets.UTF_8), served));
  }

  @Test
  public void theAnswerNamesTheTaxReportTheFileWasBuiltWith() throws Exception {
    validatorAnswers(answer(true));
    assertFalse(generateValidated().has("taxReport"));

    when(generateSupport.generateContent("org1", 2026, inputs))
        .thenReturn(new Fiscal390GenerateSupport.Generated(FILE,
            new AbstractFiscalHandler.ResolvedTaxReport(null, "AEAT390_2025_Y", 2025, 2026)));
    JSONObject json = generateValidated();

    assertEquals("AEAT390_2025_Y", json.getString("taxReport"));
    assertEquals(2025, json.getInt("layoutYear"));
  }

  // ── validation PDF ──────────────────────────────────────────────────

  /** The validator's draft of an accepted file is kept under its own name, replacing the last one. */
  @Test
  public void anAcceptedAnswerWithPdfAttachesItUnderTheValidationName() throws Exception {
    AEAT303SubmissionResult accepted = answer(true);
    accepted.setPdfContent(new byte[] { 1, 2, 3 });
    validatorAnswers(accepted);
    Attachment previous = mock(Attachment.class);
    when(obDal.createCriteria(Attachment.class).list()).thenReturn(List.of(previous));

    generateValidated();

    ArgumentCaptor<File> file = ArgumentCaptor.forClass(File.class);
    verify(attachManager).upload(any(), eq("TAB1"), eq(DECL_ID), eq("org1"), file.capture());
    assertEquals(VALIDATION_PDF_NAME, file.getValue().getName());
    assertEquals(VALIDATION_PDF_NAME, Fiscal390SubmissionSupport.validationFileName(2026, "0A"));
    verify(attachManager).delete(previous);
    InOrder order = inOrder(declHandler, attachManager, obDal);
    order.verify(declHandler).replaceAeatIncidentsNoCommit(any(), any(), any());
    order.verify(attachManager).upload(any(), anyString(), anyString(), anyString(), any());
    order.verify(obDal).commitAndClose();
  }

  @Test
  public void anAcceptedAnswerWithoutPdfAttachesNothing() throws Exception {
    validatorAnswers(answer(true));

    generateValidated();

    verify(attachManager, never()).upload(any(), anyString(), anyString(), anyString(), any());
    verify(attachManager, never()).delete(any());
  }

  /** A rejected file is not a validated one: its PDF, if any, is never kept. */
  @Test
  public void aRejectedAnswerAttachesNothingEvenWithAPdf() throws Exception {
    AEAT303SubmissionResult rejected = answer(false);
    rejected.addError("E39000101 - NIF del declarante no identificado");
    rejected.setPdfContent(new byte[] { 1, 2, 3 });
    validatorAnswers(rejected);

    generateValidated();

    verify(attachManager, never()).upload(any(), anyString(), anyString(), anyString(), any());
    verify(attachManager, never()).delete(any());
  }

  /** The PDF cannot be attached: the file was accepted all the same, and the incidents commit. */
  @Test
  public void aFailingAttachStillAnswersAccepted() throws Exception {
    AEAT303SubmissionResult accepted = answer(true);
    accepted.setPdfContent(new byte[] { 1, 2, 3 });
    validatorAnswers(accepted);
    doThrow(new OBException("storage down")).when(attachManager)
        .upload(any(), anyString(), anyString(), anyString(), any());
    StringWriter out = new StringWriter();
    HttpServletResponse resp = response(out);

    JSONObject json = generateValidated(resp, out);

    verify(resp).setStatus(HttpServletResponse.SC_OK);
    assertFileIsInTheAnswer(json);
    assertEquals("accepted", json.getJSONObject("validation").getString("outcome"));
    verify(obDal).commitAndClose();
    assertEquals(Outcome.ACCEPTED, recordedAttempt().outcome());
  }

  /** The organization of the attachment cannot be read: same outcome, nothing thrown. */
  @Test
  public void attachValidationPdfNeverThrows() throws Exception {
    AEAT303SubmissionResult accepted = answer(true);
    accepted.setPdfContent(new byte[] { 1, 2, 3 });
    FiscalAeatValidationOutcome outcome =
        FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, accepted);
    when(obDal.get(Organization.class, "org1")).thenThrow(new OBException("no session"));

    support.attachValidationPdf(decl,
        new FiscalAeatAttemptLog.Declaration("390", 2026, "0A", DECL_ID, "org1"), outcome);

    verify(attachManager, never()).upload(any(), anyString(), anyString(), anyString(), any());
  }
}
