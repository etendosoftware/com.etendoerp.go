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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;

import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Unit tests for {@link RemittanceFileAction}, the SEPA file actions of the remittance header:
 * <ul>
 *   <li>the requests: a write that is not a POST is a 405, an unknown remittance a 404, every
 *       action a 403 with the flag off;</li>
 *   <li>«Confirmar» ({@code confirm}): refused when already processed, without lines, or not ready
 *       (422 with {@code error.problems}); on the happy path it backfills the type's file setup,
 *       runs REM with the grouping WITHOUT automatic settlement (ETP-6247), generates and stores the file
 *       and answers {@code {status, attachmentId, fileName, downloadUrl}}; a REM error, a REM
 *       "success" that left the remittance unprocessed and a generator refusal are each a 422 with
 *       the transaction rolled back;</li>
 *   <li>«Regenerar fichero» ({@code generateFile}): refused on an unprocessed remittance;</li>
 *   <li>the telemetry flag {@code is_regeneration}: false for confirm, true for generateFile.</li>
 * </ul>
 *
 * <p>The REM run, the generator, the storage, the download link, the readiness and the telemetry
 * are the class's static seams, mocked through a {@code CALLS_REAL_METHODS} static mock so the
 * action logic runs for real; the DAL answers the reloaded remittance. {@code
 * RemittanceFileReadinessTest} specifies the readiness itself.
 *
 * <p>A class of its own: {@code RemittanceFileAction} is a new production class; {@code
 * RemittanceHeaderHandlerTest} pins only the action list.
 *
 * <p>ETP-6247: {@code generateFile} is refused with {@code ETGO_RemittanceFileHasReturns} when the
 * remittance has returns ({@link RemittanceReturnState#hasReturns}, a static seam mocked here).
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileAction
 */
class RemittanceFileActionTest {

  private static final String REM_ID = "REM-1";

  private MockedStatic<RemittanceFileAction> action;
  private MockedStatic<RemittanceFileReadiness> readiness;
  private MockedStatic<RemittanceSepaFileSetup> setup;
  private MockedStatic<RemittanceProcessAction> processAction;
  private MockedStatic<RemittanceFileTelemetry> telemetry;
  private MockedStatic<RemittanceReturnState> returnState;
  private MockedStatic<NeoMessageTranslator> translator;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<NeoLanguage> language;

  private OBDal dal;
  private RemittanceHeaderHandler handler;
  private Remittance remittance;
  private RemittanceType type;
  private RemittanceFileReadiness.Result ready;

  @BeforeEach
  void setUp() throws Exception {
    dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(mock(org.hibernate.Session.class));
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    language = mockStatic(NeoLanguage.class);
    language.when(NeoLanguage::requestCode).thenReturn("en_US");
    translator = mockStatic(NeoMessageTranslator.class);
    translator.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> inv.getArgument(0));
    action = mockStatic(RemittanceFileAction.class, Mockito.CALLS_REAL_METHODS);
    readiness = mockStatic(RemittanceFileReadiness.class, Mockito.CALLS_REAL_METHODS);
    setup = mockStatic(RemittanceSepaFileSetup.class);
    processAction = mockStatic(RemittanceProcessAction.class, Mockito.CALLS_REAL_METHODS);
    processAction.when(RemittanceProcessAction::rollback).then(inv -> null);
    telemetry = mockStatic(RemittanceFileTelemetry.class);
    returnState = mockStatic(RemittanceReturnState.class); // hasReturns: false unless a test says so

    type = mock(RemittanceType.class);
    remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn(REM_ID);
    when(remittance.getRemittanceType()).thenReturn(type);
    when(remittance.isProcessed()).thenReturn(false);
    handler = spy(new RemittanceHeaderHandler());
    doReturn(true).when(handler).isFlagEnabled();
    doReturn(remittance).when(handler).loadRemittance(REM_ID);

    ready = result(RemittanceSepaProvisioning.SCHEME_CORE, new RemittanceFileProblems(), 2, 2);
    readiness.when(() -> RemittanceFileReadiness.evaluate(any())).thenReturn(ready);
  }

  @AfterEach
  void tearDown() {
    returnState.close();
    telemetry.close();
    processAction.close();
    setup.close();
    readiness.close();
    action.close();
    translator.close();
    language.close();
    contextStatic.close();
    dalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- requests

  @ParameterizedTest
  @ValueSource(strings = { "confirm", "generateFile" })
  void aWriteThatIsNotAPostIsA405(String name) throws Exception {
    NeoResponse response = handler.handle(context("GET", name, new JSONObject()));

    assertEquals(405, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
  }

  @ParameterizedTest
  @ValueSource(strings = { "fileReadiness", "confirm", "generateFile" })
  void anUnknownRemittanceIsA404(String name) throws Exception {
    doReturn(null).when(handler).loadRemittance(REM_ID);

    NeoResponse response = handler.handle(context("POST", name, new JSONObject()));

    assertEquals(404, response.getHttpStatus());
  }

  @ParameterizedTest
  @ValueSource(strings = { "fileReadiness", "confirm", "generateFile" })
  void everyActionAnswers403WhileTheFlagIsOff(String name) throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    NeoResponse response = handler.handle(context("POST", name, new JSONObject()));

    assertEquals(403, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
  }

  @Test
  void fileReadinessIsAReadServedOnGet() throws Exception {
    JSONObject body = new JSONObject().put("ready", true);
    readiness.when(() -> RemittanceFileReadiness.body(remittance)).thenReturn(body);

    NeoResponse response = handler.handle(context("GET", "fileReadiness", null));

    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().getBoolean("ready"));
  }

  @Test
  void anInvalidGroupingIsRefusedByTheContract() throws Exception {
    NeoResponse response = handler.handle(context("POST", "confirm",
        new JSONObject().put("fieldValues", new JSONObject().put("grouping", "XX"))));

    assertEquals(422, response.getHttpStatus());
    verify(handler, never()).loadRemittance(any());
  }

  // ---------------------------------------------------------------- confirm refusals

  @Test
  void confirmingAProcessedRemittanceIsRefused() throws Exception {
    when(remittance.isProcessed()).thenReturn(true);

    NeoResponse response = confirm(new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_ALREADY_PROCESSED, messageKey(response));
    action.verify(() -> RemittanceFileAction.runProcess(anyString(), anyString()), never());
  }

  @Test
  void confirmingARemittanceWithoutLinesIsRefused() throws Exception {
    readiness.when(() -> RemittanceFileReadiness.evaluate(any()))
        .thenReturn(result(RemittanceSepaProvisioning.SCHEME_CORE,
            new RemittanceFileProblems(), 0, 0));

    NeoResponse response = confirm(new JSONObject());

    assertEquals(RemittanceProcessAction.MSG_NO_LINES, messageKey(response));
    action.verify(() -> RemittanceFileAction.runProcess(anyString(), anyString()), never());
  }

  @Test
  void confirmingARemittanceThatIsNotReadyListsTheProblemsAndWritesNothing() throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    problems.org("ORG-1", RemittanceFileReadiness.MSG_ORG_NO_TAX_ID, "Norte");
    problems.account("ACC-1", RemittanceFileReadiness.MSG_ACCOUNT_NOT_EUR, "BBVA");
    readiness.when(() -> RemittanceFileReadiness.evaluate(any()))
        .thenReturn(result(RemittanceSepaProvisioning.SCHEME_CORE, problems, 2, 2));

    NeoResponse response = confirm(new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceFileAction.MSG_NOT_READY, messageKey(response));
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(2, error.getJSONArray("problems").length());
    assertTrue(error.getString("message").contains("2"), error.getString("message"));
    verify(remittance, never()).setETARAutomaticprocess(any());
    action.verify(() -> RemittanceFileAction.runProcess(anyString(), anyString()), never());
  }

  // ---------------------------------------------------------------- confirm happy path

  @Test
  void confirmProcessesGeneratesAndStoresInOrderAndAnswersTheFile() throws Exception {
    Remittance processed = processedReload("PR");
    givenRun(success());
    givenGenerated();

    NeoResponse response = confirm(new JSONObject()
        .put("fieldValues", new JSONObject().put("grouping", "GGPD")));

    assertEquals(200, response.getHttpStatus(), String.valueOf(response.getBody()));
    JSONObject body = response.getBody();
    assertEquals("PR", body.getString("status"));
    assertEquals("ATT-1", body.getString("attachmentId"));
    assertEquals("R-1_Cobros.xml", body.getString("fileName"));
    assertEquals("https://app/download/ATT-1", body.getString("downloadUrl"));
    // ETP-6247: processed WITHOUT the automatic settlement; the flag is never turned on.
    verify(remittance, never()).setETARAutomaticprocess(any());
    setup.verify(() -> RemittanceSepaFileSetup.ensure(type, RemittanceSepaProvisioning.SCHEME_CORE));
    action.verify(() -> RemittanceFileAction.runProcess(REM_ID, "GGPD"));
    action.verify(() -> RemittanceFileAction.generate(processed));
    action.verify(() -> RemittanceFileAction.store(eq(processed), any()));
    processAction.verify(RemittanceProcessAction::rollback, never());
  }

  /**
   * A remittance an earlier version created automatic is reset to N before REM runs, so the hook
   * does not settle it: the payments stay Remitted until «Cobrada».
   */
  @Test
  void confirmResetsAnAutomaticRemittanceBeforeRunningRem() throws Exception {
    processedReload("PR");
    when(remittance.isETARAutomaticprocess()).thenReturn(true);
    givenRun(success());
    givenGenerated();

    NeoResponse response = confirm(new JSONObject());

    assertEquals(200, response.getHttpStatus(), String.valueOf(response.getBody()));
    InOrder order = inOrder(remittance, dal);
    order.verify(remittance).setETARAutomaticprocess(false);
    order.verify(dal).save(remittance);
    order.verify(dal).flush();
    verify(remittance, never()).setETARAutomaticprocess(true);
  }

  @Test
  void confirmDefaultsToOnePaymentPerLineAndAnswersANullLinkWithoutASecret() throws Exception {
    processedReload("PR");
    givenRun(success());
    givenGenerated();
    action.when(() -> RemittanceFileAction.downloadUrl(anyString(), anyString())).thenReturn(null);

    NeoResponse response = confirm(new JSONObject());

    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().isNull("downloadUrl"));
    action.verify(() -> RemittanceFileAction.runProcess(REM_ID,
        RemittanceProcessAction.GROUPING_NONE));
  }

  // ---------------------------------------------------------------- confirm failures

  @Test
  void aRemErrorIsProcessFailedAndRolledBack() throws Exception {
    OBError error = new OBError();
    error.setType("Error");
    error.setMessage("La factura ya está pagada");
    givenRun(error);

    NeoResponse response = confirm(new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_PROCESS_FAILED, messageKey(response));
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("La factura ya está pagada"));
    processAction.verify(RemittanceProcessAction::rollback);
    action.verify(() -> RemittanceFileAction.generate(any()), never());
  }

  @Test
  void aRemSuccessThatLeftTheRemittanceUnprocessedIsProcessFailedAndRolledBack()
      throws Exception {
    givenRun(success());
    Remittance stillPending = mock(Remittance.class);
    when(stillPending.isProcessed()).thenReturn(false);
    when(dal.get(Remittance.class, REM_ID)).thenReturn(stillPending);

    NeoResponse response = confirm(new JSONObject());

    assertEquals(RemittanceProcessAction.MSG_PROCESS_FAILED, messageKey(response));
    processAction.verify(RemittanceProcessAction::rollback);
    action.verify(() -> RemittanceFileAction.generate(any()), never());

    when(dal.get(Remittance.class, REM_ID)).thenReturn(null);
    assertEquals(RemittanceProcessAction.MSG_PROCESS_FAILED,
        messageKey(confirm(new JSONObject())), "a remittance REM removed");
  }

  @Test
  void aGeneratorRefusalIsA422WithItsKeyAndArgsAndRolledBack() throws Exception {
    processedReload("PR");
    givenRun(success());
    action.when(() -> RemittanceFileAction.generate(any())).thenThrow(
        new RemittanceFileGenerator.Refusal(RemittanceFileGenerator.MSG_INVALID,
            "pain.008.001.08", "cvc-pattern-valid"));

    NeoResponse response = confirm(new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceFileGenerator.MSG_INVALID, messageKey(response));
    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message.contains("pain.008.001.08") && message.contains("cvc-pattern-valid"),
        message);
    processAction.verify(RemittanceProcessAction::rollback);
    action.verify(() -> RemittanceFileAction.store(any(), any()), never());
    telemetry.verifyNoInteractions();
  }

  /** An unexpected failure is a 500 and rolls the transaction back. */
  @Test
  void anUnexpectedFailureIsA500AndRolledBack() throws Exception {
    readiness.when(() -> RemittanceFileReadiness.evaluate(any()))
        .thenThrow(new IllegalStateException("boom"));

    try (MockedStatic<NeoErrorResponses> errors = mockStatic(NeoErrorResponses.class)) {
      NeoResponse response = confirm(new JSONObject());

      assertEquals(500, response.getHttpStatus());
      errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
    }
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  // ---------------------------------------------------------------- generateFile

  @Test
  void generateFileOnAnUnprocessedRemittanceIsRefused() throws Exception {
    NeoResponse response = handler.handle(context("POST", "generateFile", new JSONObject()));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceFileAction.MSG_NOT_PROCESSED, messageKey(response));
    action.verify(() -> RemittanceFileAction.generate(any()), never());
  }

  @Test
  void generateFileOfAProcessedRemittanceStoresANewFileWithoutRunningRem() throws Exception {
    when(remittance.isProcessed()).thenReturn(true);
    when(remittance.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)).thenReturn("CT");
    givenGenerated();

    NeoResponse response = handler.handle(context("POST", "generateFile", new JSONObject()));

    assertEquals(200, response.getHttpStatus());
    assertEquals("ATT-1", response.getBody().getString("attachmentId"));
    setup.verify(() -> RemittanceSepaFileSetup.ensure(type, RemittanceSepaProvisioning.SCHEME_CORE));
    action.verify(() -> RemittanceFileAction.runProcess(anyString(), anyString()), never());
    verify(remittance, never()).setETARAutomaticprocess(any());
  }

  @Test
  void generateFileIsRefusedWhenTheRemittanceHasReturns() throws Exception {
    when(remittance.isProcessed()).thenReturn(true);
    returnState.when(() -> RemittanceReturnState.hasReturns(remittance)).thenReturn(true);
    givenGenerated();

    NeoResponse response = handler.handle(context("POST", "generateFile", new JSONObject()));

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceFileHasReturns", messageKey(response));
    action.verify(() -> RemittanceFileAction.generate(any()), never());
    action.verify(() -> RemittanceFileAction.store(any(), any()), never());
    setup.verifyNoInteractions();
    readiness.verify(() -> RemittanceFileReadiness.evaluate(any()), never());
  }

  // ---------------------------------------------------------------- telemetry

  @Test
  void isRegenerationIsFalseForConfirmAndTrueForGenerateFile() throws Exception {
    Remittance processed = processedReload("CT");
    givenRun(success());
    givenGenerated();

    confirm(new JSONObject());
    telemetry.verify(() -> RemittanceFileTelemetry.fileGenerated(processed, true, 2, false));

    when(remittance.isProcessed()).thenReturn(true);
    handler.handle(context("POST", "generateFile", new JSONObject()));
    telemetry.verify(() -> RemittanceFileTelemetry.fileGenerated(remittance, true, 2, true));
    telemetry.verify(() -> RemittanceFileTelemetry.fileGenerated(any(), anyBoolean(), anyInt(),
        anyBoolean()), Mockito.times(2));
  }

  // ---------------------------------------------------------------- helpers

  private NeoResponse confirm(JSONObject body) {
    return handler.handle(context("POST", "confirm", body));
  }

  private Remittance processedReload(String status) {
    Remittance processed = mock(Remittance.class);
    when(processed.getId()).thenReturn(REM_ID);
    when(processed.isProcessed()).thenReturn(true);
    when(processed.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)).thenReturn(status);
    when(dal.get(Remittance.class, REM_ID)).thenReturn(processed);
    return processed;
  }

  private void givenRun(OBError result) {
    action.when(() -> RemittanceFileAction.runProcess(anyString(), anyString()))
        .thenReturn(result);
  }

  private void givenGenerated() {
    action.when(() -> RemittanceFileAction.generate(any())).thenReturn("<xml/>".getBytes());
    action.when(() -> RemittanceFileAction.store(any(), any()))
        .thenReturn(new RemittanceFileGenerator.Stored("ATT-1", "R-1_Cobros.xml"));
    action.when(() -> RemittanceFileAction.downloadUrl(REM_ID, "ATT-1"))
        .thenReturn("https://app/download/ATT-1");
  }

  private static OBError success() {
    OBError ok = new OBError();
    ok.setType("Success");
    return ok;
  }

  private static RemittanceFileReadiness.Result result(String scheme,
      RemittanceFileProblems problems, int lines, int invoices) {
    return new RemittanceFileReadiness.Result(scheme, problems, lines, invoices, BigDecimal.TEN);
  }

  private static NeoContext context(String method, String name, JSONObject body) {
    return NeoContext.builder().specName("remittance").entityName("remittance")
        .httpMethod(method).endpointType(NeoEndpointType.ACTION).fieldName(name)
        .recordId(REM_ID).requestBody(body).build();
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }

  @Test
  void theActionsAreDeclaredWithTheirKind() {
    java.util.Map<String, com.etendoerp.go.schemaforge.util.NeoActionContract> contracts =
        new java.util.LinkedHashMap<>();
    RemittanceFileAction.addContracts(contracts);

    assertEquals(java.util.List.of("fileReadiness", "confirm", "generateFile"),
        new java.util.ArrayList<>(contracts.keySet()));
    assertFalse(contracts.get("fileReadiness").isMutating());
    assertTrue(contracts.get("confirm").isMutating());
    assertTrue(contracts.get("confirm").isFieldValuesBody());
    assertTrue(contracts.get("generateFile").isMutating());
    assertTrue(RemittanceFileAction.serves("confirm"));
    assertFalse(RemittanceFileAction.serves("process"));
  }
}
