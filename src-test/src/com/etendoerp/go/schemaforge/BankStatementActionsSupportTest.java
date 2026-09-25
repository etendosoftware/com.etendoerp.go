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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link BankStatementActionsSupport} (ETP-5447): every named bank-statement action
 * must reach {@link BankStatementsHandler} as the exact request the SPA sends to
 * {@code /sws/neo/bank-statements} — the {@code action} query param, the ids the engine reads and
 * the body — with the record id always winning over an id sent in the parameters.
 *
 * <p>The engine is mocked and the synthetic context it receives is captured and asserted.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankStatementActionsSupportTest {

  private static final String SPEC = "financial-account";
  private static final String ENTITY_ACCOUNT = "account";
  private static final String ENTITY_STATEMENTS = "importedBankStatements";
  private static final String ACCOUNT_ID = "ACC-1";
  private static final String STATEMENT_ID = "BS-1";
  private static final String FOREIGN_ID = "SOMEONE-ELSES";
  private static final String POST = "POST";
  private static final String GET = "GET";
  private static final String KEY_ACTION = "action";
  private static final String KEY_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final String KEY_ID = "id";
  private static final String NAME = "name";
  private static final String TRANSACTION_DATE = "transactionDate";
  private static final String IMPORT_DATE = "importDate";
  private static final String LINES = "lines";
  private static final String FILE_NAME = "fileName";
  private static final String CONTENT = "contentBase64";
  private static final String PROCESS = "process";
  private static final String REACTIVATE = "reactivate";
  private static final String DELETE = "delete";
  private static final String UPDATE = "update";
  private static final String CREATE_STATEMENT = "createStatement";
  private static final String LIST_STATEMENTS = "listStatements";
  private static final String PREVIEW_STATEMENT = "previewStatement";
  private static final String IMPORT_STATEMENT = "importStatement";

  @Mock
  private BankStatementsHandler engine;
  @Mock
  private Tab adTab;
  @Mock
  private SFEntity sfEntity;

  private BankStatementActionsSupport support;
  private NeoResponse engineResponse;

  @BeforeEach
  void setUp() throws Exception {
    support = new BankStatementActionsSupport();
    support.setBankStatementsHandler(engine);
    engineResponse = NeoResponse.ok(new JSONObject().put("ok", true));
    when(engine.handle(any())).thenReturn(engineResponse);
  }

  private NeoContext actionContext(String entity, String action, String method,
      String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(entity)
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(action)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .mcpOrigin(true)
        .build();
  }

  private NeoContext engineContext() {
    ArgumentCaptor<NeoContext> captor = ArgumentCaptor.forClass(NeoContext.class);
    verify(engine).handle(captor.capture());
    return captor.getValue();
  }

  private static JSONObject statementBody() throws Exception {
    return new JSONObject()
        .put(NAME, "June")
        .put(TRANSACTION_DATE, "2026-06-30")
        .put(IMPORT_DATE, "2026-07-01")
        .put(LINES, new JSONArray().put(new JSONObject().put("in", 10)));
  }

  private static List<String> namesOf(List<NeoActionContract> contracts) {
    List<String> names = new ArrayList<>();
    for (NeoActionContract contract : contracts) {
      names.add(contract.getName());
    }
    return names;
  }

  private static NeoActionContract named(List<NeoActionContract> contracts, String name) {
    for (NeoActionContract contract : contracts) {
      if (contract.getName().equals(name)) {
        return contract;
      }
    }
    return null;
  }

  private static NeoReportParam param(NeoActionContract contract, String name) {
    for (NeoReportParam param : contract.getParameters()) {
      if (param.getName().equals(name)) {
        return param;
      }
    }
    return null;
  }

  // ── account actions ───────────────────────────────────────────────────

  @Test
  void testCreateStatementPostsCreateWithTheBodyAndTheAccountId() throws Exception {
    JSONObject body = statementBody();

    NeoResponse response = support.handle(
        actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, POST, ACCOUNT_ID, body));

    assertSame(engineResponse, response);
    NeoContext ctx = engineContext();
    assertEquals(POST, ctx.getHttpMethod());
    assertEquals(NeoEndpointType.CRUD, ctx.getEndpointType());
    assertEquals(Map.of(KEY_ACTION, "create"), ctx.getQueryParams());
    JSONObject engineBody = ctx.getRequestBody();
    assertEquals(ACCOUNT_ID, engineBody.getString(KEY_ACCOUNT_ID));
    assertEquals("June", engineBody.getString(NAME));
    assertEquals("2026-06-30", engineBody.getString(TRANSACTION_DATE));
    assertEquals("2026-07-01", engineBody.getString(IMPORT_DATE));
    assertEquals(1, engineBody.getJSONArray(LINES).length());
  }

  @Test
  void testCreateStatementCarriesTheCallerContext() {
    support.handle(actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, POST, ACCOUNT_ID,
        new JSONObject()));

    NeoContext ctx = engineContext();
    assertEquals(SPEC, ctx.getSpecName());
    assertEquals(ENTITY_ACCOUNT, ctx.getEntityName());
    assertEquals(ACCOUNT_ID, ctx.getRecordId());
    assertSame(adTab, ctx.getAdTab());
    assertSame(sfEntity, ctx.getSfEntity());
    assertTrue(ctx.isMcpOrigin());
    assertNull(ctx.getFieldName());
  }

  @Test
  void testCreateStatementRecordIdOverwritesAnAccountIdSentInTheParameters() throws Exception {
    JSONObject body = statementBody().put(KEY_ACCOUNT_ID, FOREIGN_ID);

    support.handle(actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, POST, ACCOUNT_ID, body));

    assertEquals(ACCOUNT_ID, engineContext().getRequestBody().getString(KEY_ACCOUNT_ID));
    assertEquals(FOREIGN_ID, body.getString(KEY_ACCOUNT_ID),
        "the caller's body must not be mutated: the engine gets a copy");
  }

  @Test
  void testCreateStatementWithoutABodySendsOnlyTheAccountId() throws Exception {
    support.handle(actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, POST, ACCOUNT_ID, null));

    JSONObject engineBody = engineContext().getRequestBody();
    assertEquals(1, engineBody.length());
    assertEquals(ACCOUNT_ID, engineBody.getString(KEY_ACCOUNT_ID));
  }

  /**
   * listStatements was removed: statements are read through the generic CRUD of the
   * importedBankStatements entity, so the support no longer knows the name on any method.
   */
  @Test
  void testListStatementsIsNoLongerAnAction() {
    assertNull(support.handle(
        actionContext(ENTITY_ACCOUNT, LIST_STATEMENTS, GET, ACCOUNT_ID, new JSONObject())));
    assertNull(support.handle(
        actionContext(ENTITY_ACCOUNT, LIST_STATEMENTS, POST, ACCOUNT_ID, null)));
    verify(engine, never()).handle(any());
  }

  @Test
  void testPreviewStatementViaGetAnswers405EvenThoughItIsReadOnly() throws Exception {
    NeoResponse response = support.handle(actionContext(ENTITY_ACCOUNT, PREVIEW_STATEMENT, GET,
        ACCOUNT_ID, new JSONObject().put(FILE_NAME, "june.csv").put(CONTENT, "QUJD")));

    assertEquals(405, response.getHttpStatus());
    assertTrue(response.getBody().toString().contains(POST), response.getBody().toString());
    verify(engine, never()).handle(any());
  }

  @Test
  void testPreviewStatementPostsPreviewWithTheBodyAndTheAccountId() throws Exception {
    JSONObject body = new JSONObject().put(FILE_NAME, "june.csv").put(CONTENT, "QUJD");

    support.handle(actionContext(ENTITY_ACCOUNT, PREVIEW_STATEMENT, POST, ACCOUNT_ID, body));

    NeoContext ctx = engineContext();
    assertEquals(POST, ctx.getHttpMethod());
    assertEquals(Map.of(KEY_ACTION, "preview"), ctx.getQueryParams());
    assertEquals("june.csv", ctx.getRequestBody().getString(FILE_NAME));
    assertEquals("QUJD", ctx.getRequestBody().getString(CONTENT));
    assertEquals(ACCOUNT_ID, ctx.getRequestBody().getString(KEY_ACCOUNT_ID));
  }

  @Test
  void testImportStatementPostsImportWithTheBodyAndTheAccountId() throws Exception {
    JSONObject body = new JSONObject().put(FILE_NAME, "june.n43").put(CONTENT, "QUJD")
        .put(KEY_ACCOUNT_ID, FOREIGN_ID);

    support.handle(actionContext(ENTITY_ACCOUNT, IMPORT_STATEMENT, POST, ACCOUNT_ID, body));

    NeoContext ctx = engineContext();
    assertEquals(POST, ctx.getHttpMethod());
    assertEquals(Map.of(KEY_ACTION, "import"), ctx.getQueryParams());
    assertEquals("june.n43", ctx.getRequestBody().getString(FILE_NAME));
    assertEquals(ACCOUNT_ID, ctx.getRequestBody().getString(KEY_ACCOUNT_ID));
  }

  // ── statement actions ─────────────────────────────────────────────────

  private void assertIdOnlyAction(String action) throws Exception {
    JSONObject body = new JSONObject().put(KEY_ID, FOREIGN_ID).put("stray", "x")
        .put(NAME, "ignored");

    NeoResponse response = support.handle(
        actionContext(ENTITY_STATEMENTS, action, POST, STATEMENT_ID, body));

    assertSame(engineResponse, response);
    NeoContext ctx = engineContext();
    assertEquals(POST, ctx.getHttpMethod());
    assertEquals(Map.of(KEY_ACTION, action), ctx.getQueryParams());
    JSONObject engineBody = ctx.getRequestBody();
    assertEquals(1, engineBody.length(), engineBody.toString());
    assertEquals(STATEMENT_ID, engineBody.getString(KEY_ID));
  }

  @Test
  void testProcessSendsOnlyTheStatementIdAndDropsOtherParameters() throws Exception {
    assertIdOnlyAction(PROCESS);
  }

  @Test
  void testReactivateSendsOnlyTheStatementIdAndDropsOtherParameters() throws Exception {
    assertIdOnlyAction(REACTIVATE);
  }

  @Test
  void testDeleteSendsOnlyTheStatementIdAndDropsOtherParameters() throws Exception {
    assertIdOnlyAction(DELETE);
  }

  @Test
  void testUpdateSendsTheParametersWithTheStatementIdOverwritten() throws Exception {
    JSONObject body = statementBody().put(KEY_ID, FOREIGN_ID).put(PROCESS, true);

    support.handle(actionContext(ENTITY_STATEMENTS, UPDATE, POST, STATEMENT_ID, body));

    NeoContext ctx = engineContext();
    assertEquals(POST, ctx.getHttpMethod());
    assertEquals(Map.of(KEY_ACTION, UPDATE), ctx.getQueryParams());
    JSONObject engineBody = ctx.getRequestBody();
    assertEquals(STATEMENT_ID, engineBody.getString(KEY_ID));
    assertEquals("June", engineBody.getString(NAME));
    assertTrue(engineBody.getBoolean(PROCESS));
    assertEquals(FOREIGN_ID, body.getString(KEY_ID), "the caller's body must not be mutated");
  }

  /**
   * lines was removed: statement lines are read through the generic CRUD of the
   * bankStatementLines entity, so the support no longer knows the name on any method.
   */
  @Test
  void testLinesIsNoLongerAnAction() {
    assertNull(support.handle(
        actionContext(ENTITY_STATEMENTS, LINES, GET, STATEMENT_ID, null)));
    assertNull(support.handle(
        actionContext(ENTITY_STATEMENTS, LINES, POST, STATEMENT_ID, null)));
    verify(engine, never()).handle(any());
  }

  // ── guards ────────────────────────────────────────────────────────────

  @Test
  void testBlankRecordIdAnswers400WithoutCallingTheEngine() throws Exception {
    NeoResponse response = support.handle(
        actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, POST, "  ", statementBody()));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().toString().contains("financial account"),
        response.getBody().toString());
    verify(engine, never()).handle(any());
  }

  @Test
  void testNullStatementIdAnswers400NamingTheStatement() {
    NeoResponse response = support.handle(
        actionContext(ENTITY_STATEMENTS, PROCESS, POST, null, null));

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().toString().contains("bank statement"),
        response.getBody().toString());
    verify(engine, never()).handle(any());
  }

  @Test
  void testWriteActionViaGetAnswers405WithoutCallingTheEngine() throws Exception {
    NeoResponse response = support.handle(
        actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, GET, ACCOUNT_ID, statementBody()));

    assertEquals(405, response.getHttpStatus());
    assertTrue(response.getBody().toString().contains(POST), response.getBody().toString());
    verify(engine, never()).handle(any());
  }

  @Test
  void testDeleteViaGetAnswers405() {
    NeoResponse response = support.handle(
        actionContext(ENTITY_STATEMENTS, DELETE, GET, STATEMENT_ID, null));

    assertEquals(405, response.getHttpStatus());
    verify(engine, never()).handle(any());
  }

  @Test
  void testWriteActionWithoutAMethodAnswers405() {
    NeoResponse response = support.handle(
        actionContext(ENTITY_STATEMENTS, PROCESS, null, STATEMENT_ID, null));

    assertEquals(405, response.getHttpStatus());
    verify(engine, never()).handle(any());
  }

  @Test
  void testWriteActionAcceptsALowerCasePost() {
    support.handle(actionContext(ENTITY_STATEMENTS, PROCESS, "post", STATEMENT_ID, null));

    assertEquals(POST, engineContext().getHttpMethod());
  }

  @Test
  void testUnknownActionReturnsNull() {
    assertNull(support.handle(
        actionContext(ENTITY_ACCOUNT, "Processed", POST, ACCOUNT_ID, null)));
    verify(engine, never()).handle(any());
  }

  @Test
  void testActionOfTheOtherEntityReturnsNull() {
    assertNull(support.handle(actionContext(ENTITY_ACCOUNT, PROCESS, POST, ACCOUNT_ID, null)));
    assertNull(support.handle(
        actionContext(ENTITY_STATEMENTS, CREATE_STATEMENT, POST, STATEMENT_ID, null)));
    verify(engine, never()).handle(any());
  }

  @Test
  void testActionOnAnotherEntityReturnsNull() {
    assertNull(support.handle(
        actionContext("bankStatementLines", DELETE, POST, STATEMENT_ID, null)));
    verify(engine, never()).handle(any());
  }

  @Test
  void testBlankActionNameReturnsNull() {
    assertNull(support.handle(actionContext(ENTITY_ACCOUNT, " ", POST, ACCOUNT_ID, null)));
    assertNull(support.handle(actionContext(ENTITY_ACCOUNT, null, POST, ACCOUNT_ID, null)));
  }

  @Test
  void testNonActionEndpointReturnsNull() {
    NeoContext crud = NeoContext.builder()
        .specName(SPEC)
        .entityName(ENTITY_ACCOUNT)
        .endpointType(NeoEndpointType.CRUD)
        .fieldName(CREATE_STATEMENT)
        .httpMethod(POST)
        .recordId(ACCOUNT_ID)
        .build();

    assertNull(support.handle(crud));
    verify(engine, never()).handle(any());
  }

  @Test
  void testNullContextReturnsNull() {
    assertNull(support.handle(null));
  }

  @Test
  void testEngineErrorIsPassedThroughUnchanged() {
    NeoResponse error = NeoResponse.error(400, "Missing required field: transactionDate");
    when(engine.handle(any())).thenReturn(error);

    assertSame(error, support.handle(
        actionContext(ENTITY_ACCOUNT, CREATE_STATEMENT, POST, ACCOUNT_ID, new JSONObject())));
  }

  // ── declaredActions ───────────────────────────────────────────────────

  @Test
  void testAccountDeclaresItsThreeActionsInOrder() {
    List<NeoActionContract> declared = support.declaredActions(ENTITY_ACCOUNT);

    assertEquals(List.of(CREATE_STATEMENT, PREVIEW_STATEMENT, IMPORT_STATEMENT),
        namesOf(declared));
    assertNull(named(declared, LIST_STATEMENTS));
  }

  @Test
  void testCreateStatementDeclaresPostWriteWithRequiredDatesAndLines() {
    NeoActionContract create = named(support.declaredActions(ENTITY_ACCOUNT), CREATE_STATEMENT);

    assertNotNull(create);
    assertEquals(POST, create.getMethod());
    assertFalse(create.isReadOnly());
    assertEquals(List.of(NAME, TRANSACTION_DATE, IMPORT_DATE, LINES),
        create.getRequiredParameterNames());
    assertEquals(NeoReportParam.TYPE_DATE, param(create, TRANSACTION_DATE).getType());
    assertEquals(NeoReportParam.TYPE_DATE, param(create, IMPORT_DATE).getType());
    assertEquals(NeoReportParam.TYPE_ARRAY, param(create, LINES).getType());
    assertEquals(NeoReportParam.TYPE_BOOLEAN, param(create, PROCESS).getType());
    assertFalse(param(create, PROCESS).isRequired());
    assertTrue(create.getDescription().contains(TRANSACTION_DATE));
  }

  @Test
  void testPreviewIsReadOnlyAndImportIsAWriteBothRequiringTheFile() {
    List<NeoActionContract> declared = support.declaredActions(ENTITY_ACCOUNT);
    NeoActionContract preview = named(declared, PREVIEW_STATEMENT);
    NeoActionContract importAction = named(declared, IMPORT_STATEMENT);

    assertEquals(POST, preview.getMethod());
    assertTrue(preview.isReadOnly());
    assertEquals(List.of(FILE_NAME, CONTENT), preview.getRequiredParameterNames());
    assertEquals(POST, importAction.getMethod());
    assertFalse(importAction.isReadOnly());
    assertEquals(List.of(FILE_NAME, CONTENT), importAction.getRequiredParameterNames());
  }

  @Test
  void testStatementsDeclareTheirFourActionsInOrder() {
    List<NeoActionContract> declared = support.declaredActions(ENTITY_STATEMENTS);

    assertEquals(List.of(UPDATE, PROCESS, REACTIVATE, DELETE), namesOf(declared));
    assertNull(named(declared, LINES));
  }

  @Test
  void testEveryDeclaredActionIsAPostAndOnlyThePreviewIsReadOnly() {
    int count = 0;
    for (String entity : List.of(ENTITY_ACCOUNT, ENTITY_STATEMENTS)) {
      for (NeoActionContract contract : support.declaredActions(entity)) {
        count++;
        assertEquals(POST, contract.getMethod(), contract.getName());
        assertEquals(PREVIEW_STATEMENT.equals(contract.getName()), contract.isReadOnly(),
            contract.getName());
      }
    }
    assertEquals(7, count);
  }

  @Test
  void testUpdateRequiresNameAndBothDatesButNotLines() {
    NeoActionContract update = named(support.declaredActions(ENTITY_STATEMENTS), UPDATE);

    assertEquals(POST, update.getMethod());
    assertFalse(update.isReadOnly());
    assertEquals(List.of(NAME, TRANSACTION_DATE, IMPORT_DATE),
        update.getRequiredParameterNames());
    NeoReportParam lines = param(update, LINES);
    assertNotNull(lines);
    assertFalse(lines.isRequired());
    assertEquals(NeoReportParam.TYPE_ARRAY, lines.getType());
  }

  @Test
  void testProcessReactivateAndDeleteArePostWritesWithoutParameters() {
    List<NeoActionContract> declared = support.declaredActions(ENTITY_STATEMENTS);

    for (String name : List.of(PROCESS, REACTIVATE, DELETE)) {
      NeoActionContract contract = named(declared, name);
      assertEquals(POST, contract.getMethod(), name);
      assertFalse(contract.isReadOnly(), name);
      assertTrue(contract.getParameters().isEmpty(), name);
      assertNotNull(contract.getDescription(), name);
    }
  }

  @Test
  void testEveryDeclaredActionIsRoutable() throws Exception {
    for (String entity : List.of(ENTITY_ACCOUNT, ENTITY_STATEMENTS)) {
      for (NeoActionContract contract : support.declaredActions(entity)) {
        NeoResponse response = support.handle(actionContext(entity, contract.getName(),
            contract.getMethod(), STATEMENT_ID, new JSONObject()));
        assertSame(engineResponse, response, entity + "/" + contract.getName());
      }
    }
  }

  @Test
  void testEveryDeclaredActionAnswers405OnGet() throws Exception {
    for (String entity : List.of(ENTITY_ACCOUNT, ENTITY_STATEMENTS)) {
      for (NeoActionContract contract : support.declaredActions(entity)) {
        NeoResponse response = support.handle(actionContext(entity, contract.getName(),
            GET, STATEMENT_ID, new JSONObject()));
        assertNotNull(response, entity + "/" + contract.getName());
        assertEquals(405, response.getHttpStatus(), entity + "/" + contract.getName());
      }
    }
    verify(engine, never()).handle(any());
  }

  @Test
  void testOtherEntitiesDeclareNothing() {
    assertTrue(support.declaredActions("bankStatementLines").isEmpty());
    assertTrue(support.declaredActions(null).isEmpty());
  }
}
