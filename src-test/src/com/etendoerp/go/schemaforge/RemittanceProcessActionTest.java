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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.process.REMRemittanceProcess;
import org.openbravo.module.remittance.utility.RemittanceStatusUtil;
import org.openbravo.scheduling.ClusterInstanceProcessAccess;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Unit tests for {@link RemittanceProcessAction}, «Procesar» and «Reactivar»: the contracts, the
 * refusals made before calling Classic (already processed or no active lines to process; not
 * processed to reactivate), the action code passed to {@code REMRemittanceProcess} (the grouping,
 * or {@code R}), a refusal of the process answered 422 with its text, and the success body with
 * the status read back.
 *
 * <p>{@link RemittanceProcessAction#run} — the Classic process — is stubbed through a
 * {@code CALLS_REAL_METHODS} static mock, so {@code process} itself runs for real (the run tests
 * leave it unstubbed and mock what it calls: Weld, the bundle's {@code ProcessData} lookup and its
 * DAL connection); the DAL answers
 * the active-line count and the reloaded remittance. {@link NeoMessageTranslator} is mocked to
 * identity: translating {@code @key@} tokens needs a database.
 *
 * <p>A class of its own: {@code RemittanceProcessAction} is a separate production class;
 * {@code RemittanceHeaderHandlerTest} pins only the routing of the {@code process} action.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceProcessAction
 */
class RemittanceProcessActionTest {

  private static final String REM_ID = "REM-1";

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<RemittanceProcessAction> actionStatic;
  private MockedStatic<NeoMessageTranslator> translatorStatic;
  private OBDal dal;
  private Long activeLines;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    Query<Long> count = mock(Query.class, RETURNS_SELF);
    when(count.uniqueResult()).thenAnswer(inv -> activeLines);
    when(session.createQuery(anyString(), eq(Long.class))).thenReturn(count);
    activeLines = 2L;

    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    translatorStatic = mockStatic(NeoMessageTranslator.class);
    translatorStatic.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> inv.getArgument(0));
    actionStatic = mockStatic(RemittanceProcessAction.class, CALLS_REAL_METHODS);
  }

  @AfterEach
  void tearDown() {
    actionStatic.close();
    translatorStatic.close();
    dalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  @Test
  void theContractIsAMutatingProcessWithAnOptionalGrouping() throws Exception {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    RemittanceProcessAction.addContract(contracts);

    NeoActionContract contract = contracts.get("process");
    assertTrue(contract.isMutating());
    assertEquals("grouping", contract.getParams().get(0).getName());
    assertEquals(null, NeoActionContract.validate(contracts, "process", new JSONObject()),
        "the grouping is optional");
    assertEquals(null, NeoActionContract.validate(contracts, "process",
        new JSONObject().put("grouping", "GGPD")));
    assertEquals(422, NeoActionContract.validate(contracts, "process",
        new JSONObject().put("grouping", "XX")).getHttpStatus(), "only P or GGPD");
  }

  @Test
  void theReactivateContractIsAMutatingActionWithoutParameters() throws Exception {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    RemittanceProcessAction.addContract(contracts);

    assertEquals(java.util.List.of("process", "reactivate"),
        new java.util.ArrayList<>(contracts.keySet()));
    assertTrue(contracts.get("reactivate").isMutating());
    assertTrue(contracts.get("reactivate").getParams().isEmpty());
    assertEquals(422, NeoActionContract.validate(contracts, "reactivate",
        new JSONObject().put("grouping", "P")).getHttpStatus());
    assertTrue(RemittanceProcessAction.serves("process"));
    assertTrue(RemittanceProcessAction.serves("reactivate"));
    assertFalse(RemittanceProcessAction.serves("availableDueItems"));
    assertFalse(RemittanceProcessAction.serves(null));
  }

  @Test
  void reactivatingAnUnprocessedRemittanceIsRefused422WithoutRunningClassic() throws Exception {
    NeoResponse response = RemittanceProcessAction.reactivate(remittance(false));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_NOT_PROCESSED, messageKey(response));
    assertEquals("ETGO_RemittanceNotProcessed", messageKey(response));
    actionStatic.verify(() -> RemittanceProcessAction.run(anyString(), anyString()), never());
  }

  @Test
  void aRefusalOfTheReactivationIsAnswered422WithRemsText() throws Exception {
    OBError refusal = new OBError();
    refusal.setType("Error");
    refusal.setMessage("La remesa tiene líneas liquidadas");
    stubRun(refusal);

    NeoResponse response = RemittanceProcessAction.reactivate(remittance(true));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_REACTIVATE_FAILED, messageKey(response));
    assertEquals("ETGO_RemittanceReactivateFailed", messageKey(response));
    assertTrue(errorMessage(response).contains("La remesa tiene líneas liquidadas"),
        errorMessage(response));
    actionStatic.verify(() -> RemittanceProcessAction.run(REM_ID, "R"));
  }

  @Test
  void aSuccessfulReactivationAnswersTheStatusReadBack() throws Exception {
    stubRun(success("Remesa reactivada"));
    reloaded("PE");

    NeoResponse response = RemittanceProcessAction.reactivate(remittance(true));

    assertEquals(200, response.getHttpStatus());
    assertEquals("PE", response.getBody().getString("status"));
    assertEquals("Remesa reactivada", response.getBody().getString("message"));
    actionStatic.verify(() -> RemittanceProcessAction.run(REM_ID,
        RemittanceProcessAction.REACTIVATE_CODE));
    assertEquals("R", RemittanceProcessAction.REACTIVATE_CODE);
  }

  @Test
  void anAlreadyProcessedRemittanceIsRefused422WithoutRunningClassic() throws Exception {
    NeoResponse response = RemittanceProcessAction.process(remittance(true), new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_ALREADY_PROCESSED, messageKey(response));
    actionStatic.verify(() -> RemittanceProcessAction.run(anyString(), anyString()), never());
  }

  @Test
  void aRemittanceWithoutActiveLinesIsRefused422WithoutRunningClassic() throws Exception {
    for (Long none : new Long[] { 0L, null }) {
      activeLines = none;

      NeoResponse response = RemittanceProcessAction.process(remittance(false), new JSONObject());

      assertEquals(422, response.getHttpStatus());
      assertEquals(RemittanceProcessAction.MSG_NO_LINES, messageKey(response));
    }
    actionStatic.verify(() -> RemittanceProcessAction.run(anyString(), anyString()), never());
  }

  @Test
  void theGroupingDefaultsToOnePaymentPerLineAndGgpdIsPassedThrough() throws Exception {
    stubRun(success("ok"));
    reloaded("PR");

    RemittanceProcessAction.process(remittance(false), new JSONObject());
    actionStatic.verify(() -> RemittanceProcessAction.run(REM_ID, "P"));

    RemittanceProcessAction.process(remittance(false), new JSONObject().put("grouping", " "));
    actionStatic.verify(() -> RemittanceProcessAction.run(REM_ID, "P"), Mockito.times(2));

    RemittanceProcessAction.process(remittance(false), new JSONObject().put("grouping", "GGPD"));
    actionStatic.verify(() -> RemittanceProcessAction.run(REM_ID, "GGPD"));
  }

  @Test
  void aSuccessAnswersTheStatusReadBackAndRemsMessage() throws Exception {
    stubRun(success("Remesa procesada"));
    reloaded("PR");

    NeoResponse response = RemittanceProcessAction.process(remittance(false), new JSONObject());

    assertEquals(200, response.getHttpStatus());
    assertEquals("PR", response.getBody().getString("status"),
        "read back from the reloaded remittance, as REM's refresh left it");
    assertEquals("Remesa procesada", response.getBody().getString("message"));
  }

  @Test
  void aSuccessWithoutAMessageOrAReloadedRecordStillAnswers200() throws Exception {
    stubRun(success(null));
    when(dal.get(Remittance.class, REM_ID)).thenReturn(null);

    NeoResponse response = RemittanceProcessAction.process(remittance(false), new JSONObject());

    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().isNull("status"));
    assertEquals("", response.getBody().getString("message"));
  }

  @Test
  void aRefusalOfTheProcessIsAnswered422WithRemsText() throws Exception {
    OBError refusal = new OBError();
    refusal.setType("Error");
    refusal.setTitle("Error");
    refusal.setMessage("El método de pago no está configurado para la cuenta");
    stubRun(refusal);

    NeoResponse response = RemittanceProcessAction.process(remittance(false), new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_PROCESS_FAILED, messageKey(response));
    assertTrue(errorMessage(response).contains("El método de pago no está configurado"),
        errorMessage(response));
  }

  @Test
  void aRefusalWithoutMessageUsesItsTitleAndNoAnswerAtAllIsARefusalToo() throws Exception {
    OBError titled = new OBError();
    titled.setType("ERROR");
    titled.setTitle("Remesa no válida");
    stubRun(titled);
    NeoResponse response = RemittanceProcessAction.process(remittance(false), new JSONObject());
    assertEquals(422, response.getHttpStatus());
    assertTrue(errorMessage(response).contains("Remesa no válida"), errorMessage(response));

    stubRun(null);
    NeoResponse none = RemittanceProcessAction.process(remittance(false), new JSONObject());
    assertEquals(422, none.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_PROCESS_FAILED, messageKey(none));
  }

  @Test
  void aSuccessIsNotMistakenForAFailure() throws Exception {
    OBError warning = new OBError();
    warning.setType("Warning");
    warning.setMessage("Not grouped");
    stubRun(warning);
    reloaded("PR");

    NeoResponse response = RemittanceProcessAction.process(remittance(false), new JSONObject());

    assertEquals(200, response.getHttpStatus(), "only type Error is a refusal");
    assertFalse(response.getBody().has("error"));
  }

  // ---------------------------------------------------------------- rollback on a REM refusal

  /**
   * A refused process or reactivation may have written before REM refused: the transaction is
   * rolled back ({@code NeoErrorResponses.rollbackUnlessBatchOwned}, outside a batch) before the
   * 422 is answered. A success is never rolled back.
   */
  @Test
  void aRemRefusalRollsTheTransactionBackAndASuccessDoesNot() throws Exception {
    OBError refusal = new OBError();
    refusal.setType("Error");
    refusal.setMessage("refused");
    stubRun(refusal);
    assertEquals(422, RemittanceProcessAction.process(remittance(false), new JSONObject())
        .getHttpStatus());
    assertEquals(422, RemittanceProcessAction.reactivate(remittance(true)).getHttpStatus());
    verify(dal, Mockito.times(2)).rollbackAndClose();
    actionStatic.verify(RemittanceProcessAction::rollback, Mockito.times(2));

    Mockito.clearInvocations(dal);
    stubRun(success("ok"));
    reloaded("PR");
    assertEquals(200, RemittanceProcessAction.process(remittance(false), new JSONObject())
        .getHttpStatus());
    verify(dal, never()).rollbackAndClose();
  }

  /** REM's text is translated while the session still works, i.e. before the rollback. */
  @Test
  void theRefusalTextIsTranslatedBeforeTheRollback() throws Exception {
    java.util.List<String> order = new java.util.ArrayList<>();
    translatorStatic.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> {
          order.add("translate");
          return "translated: " + inv.getArgument(0);
        });
    actionStatic.when(RemittanceProcessAction::rollback).thenAnswer(inv -> {
      order.add("rollback");
      return null;
    });
    OBError refusal = new OBError();
    refusal.setType("Error");
    refusal.setMessage("@REM_SomeKey@");
    stubRun(refusal);

    NeoResponse response = RemittanceProcessAction.reactivate(remittance(true));

    assertEquals(java.util.List.of("translate", "rollback"), order);
    assertTrue(errorMessage(response).contains("translated: @REM_SomeKey@"), errorMessage(response));
  }

  @Test
  void aRefusalBeforeRemRunsNeedsNoRollback() throws Exception {
    RemittanceProcessAction.process(remittance(true), new JSONObject());
    RemittanceProcessAction.reactivate(remittance(false));
    activeLines = 0L;
    RemittanceProcessAction.process(remittance(false), new JSONObject());

    verify(dal, never()).rollbackAndClose();
    actionStatic.verify(RemittanceProcessAction::rollback, never());
  }

  // ---------------------------------------------------------------- run (the Classic process)

  /**
   * REM's payment creation runs the payment process on {@code bundle.getConnection()}: the bundle
   * must be initialised on a DAL connection, or processing a remittance fails with an NPE inside
   * {@code ProcessData}. Pins {@code new ProcessBundle(...).init(new DalConnectionProvider(false))}
   * — the bundle's process lookup runs on that connection and the bundle keeps it — with REM's
   * parameters set after the init (which resets them).
   */
  @Test
  void runInitialisesTheBundleOnADalConnectionAndPassesRemsParameters() throws Exception {
    REMRemittanceProcess process = mock(REMRemittanceProcess.class);
    ProcessBundle[] seen = new ProcessBundle[1];
    OBError remAnswer = success("Remesa procesada");
    Mockito.doAnswer(inv -> {
      seen[0] = inv.getArgument(0);
      seen[0].setResult(remAnswer);
      return null;
    }).when(process).execute(any(ProcessBundle.class));
    PreparedStatement statement = processLookup();

    try (ClassicRun classic = new ClassicRun(process, statement)) {
      OBError result = RemittanceProcessAction.run(REM_ID, "GGPD");

      assertSame(remAnswer, result, "REM's own result is returned");
      ProcessBundle bundle = seen[0];
      assertNotNull(bundle, "REM ran");
      assertEquals(1, classic.connections.constructed().size());
      DalConnectionProvider connection = classic.connections.constructed().get(0);
      assertNotNull(bundle.getConnection(), "the payment process needs a connection");
      assertSame(connection, bundle.getConnection());
      verify(connection).getPreparedStatement(org.mockito.ArgumentMatchers.contains("AD_Process"));
      verify(statement).setString(1, RemittanceProcessAction.PROCESS_ID);
      assertEquals(RemittanceProcessAction.PROCESS_ID, bundle.getProcessId());
      assertEquals(REM_ID, bundle.getParams().get("REM_Remittance_ID"));
      assertEquals("GGPD", bundle.getParams().get("action"));
    }
  }

  @Test
  void aRunThatThrowsIsAnErrorResultWithTheExceptionText() throws Exception {
    REMRemittanceProcess process = mock(REMRemittanceProcess.class);
    Mockito.doThrow(new IllegalStateException("payment failed")).when(process)
        .execute(any(ProcessBundle.class));

    try (ClassicRun classic = new ClassicRun(process, processLookup())) {
      OBError result = RemittanceProcessAction.run(REM_ID, "P");

      assertEquals("Error", result.getType());
      assertEquals("payment failed", result.getMessage());
    }
  }

  @Test
  void aRunWhoseResultIsNotAnObErrorAnswersNull() throws Exception {
    REMRemittanceProcess process = mock(REMRemittanceProcess.class);

    try (ClassicRun classic = new ClassicRun(process, processLookup())) {
      assertNull(RemittanceProcessAction.run(REM_ID, "P"),
          "no result: process() reads it as a refusal");
      verify(process).execute(any(ProcessBundle.class));
    }
  }

  /** The bundle's {@code AD_Process} lookup: a statement whose query finds no row. */
  private static PreparedStatement processLookup() throws Exception {
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet empty = mock(ResultSet.class);
    when(statement.executeQuery()).thenReturn(empty);
    return statement;
  }

  /**
   * What {@code run} reaches outside the bundle: Weld (REM's process), the context and its
   * variables, the DAL connection (answering the bundle's process lookup with {@code statement})
   * and the cluster check. Closed by try-with-resources.
   */
  private static final class ClassicRun implements AutoCloseable {
    final MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class);
    final MockedStatic<OBContext> context = mockStatic(OBContext.class);
    final MockedStatic<NeoDefaultsService> defaults = mockStatic(NeoDefaultsService.class);
    final MockedConstruction<DalConnectionProvider> connections;
    final MockedConstruction<ClusterInstanceProcessAccess> cluster =
        Mockito.mockConstruction(ClusterInstanceProcessAccess.class);

    ClassicRun(REMRemittanceProcess process, PreparedStatement statement) {
      connections = Mockito.mockConstruction(DalConnectionProvider.class,
          (connection, ctx) -> when(connection.getPreparedStatement(anyString()))
              .thenReturn(statement));
      weld.when(() -> WeldUtils.getInstanceFromStaticBeanManager(REMRemittanceProcess.class))
          .thenReturn(process);
      context.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
      defaults.when(() -> NeoDefaultsService.buildVariablesSecureApp(any(OBContext.class)))
          .thenReturn(mock(VariablesSecureApp.class));
    }

    @Override
    public void close() {
      cluster.close();
      connections.close();
      defaults.close();
      context.close();
      weld.close();
    }
  }

  // ---------------------------------------------------------------- helpers

  private void stubRun(OBError result) {
    actionStatic.when(() -> RemittanceProcessAction.run(anyString(), anyString()))
        .thenReturn(result);
  }

  private void reloaded(String status) {
    Remittance reloaded = mock(Remittance.class);
    when(reloaded.get(RemittanceStatusUtil.PROPERTY_REMITTANCE_STATUS)).thenReturn(status);
    when(dal.get(Remittance.class, REM_ID)).thenReturn(reloaded);
  }

  private static OBError success(String message) {
    OBError ok = new OBError();
    ok.setType("Success");
    ok.setMessage(message);
    return ok;
  }

  private static Remittance remittance(boolean processed) {
    Remittance remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn(REM_ID);
    when(remittance.isProcessed()).thenReturn(processed);
    return remittance;
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }
}
