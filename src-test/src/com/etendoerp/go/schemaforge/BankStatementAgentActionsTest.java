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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link BankStatementAgentActions} (ETP-5447) — the {@code neo_action} surface of
 * the {@code bank-statements} report spec:
 *
 * <ul>
 *   <li>the seven declared contracts (names, order, read/write, required lists, id descriptions,
 *       date-typed header dates);</li>
 *   <li>the refusals that run before the engine (contract 422, blank id 422, role gate 403 with
 *       GET for the preview and POST for the writes);</li>
 *   <li>the derived engine request each action hands to {@link BankStatementsHandler#handle} —
 *       POST, {@code ?action=<engine action>}, no endpoint type, the SPA's body keys;</li>
 *   <li>the flush-to-clean loop that runs after a successful write only.</li>
 * </ul>
 *
 * <p>The engine is a mocked {@link BankStatementsHandler}: this class tests the translation, the
 * engine itself is covered by {@code BankStatementsHandlerTest}. {@link NeoAccessHelper} and
 * {@link OBDal} are mocked statically, so no DAL / OBContext is needed.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BankStatementAgentActions (ETP-5447)")
class BankStatementAgentActionsTest {

  private static final String SPEC = "bank-statements";
  private static final String ACC_ID = "ACC-1";
  private static final String STMT_ID = "STMT-1";
  private static final String POST = "POST";
  private static final String GET = "GET";
  private static final String ACTION = "action";
  private static final String ERROR = "error";
  private static final String MESSAGE = "message";
  private static final String ACCOUNT_KEY = "FIN_Financial_Account_ID";
  private static final String ID = "id";
  private static final String NAME = "name";
  private static final String TRANSACTION_DATE = "transactionDate";
  private static final String IMPORT_DATE = "importDate";
  private static final String LINES = "lines";
  private static final String PROCESS = "process";
  private static final String NOTES = "notes";
  private static final String FILE_NAME = "fileName";
  private static final String CONTENT = "contentBase64";
  private static final String PARAMETERS = "parameters";
  private static final String PROPERTIES = "properties";
  private static final String REQUIRED = "required";
  private static final String UNKNOWN_PARAMETERS = "unknownParameters";
  private static final String MISSING_PARAMETERS = "missingParameters";
  private static final String DAY = "2026-06-02";
  private static final String CSV_NAME = "extracto-junio.csv";
  private static final String CSV_CONTENT = "VHJhbnNhY3Rpb24gRGF0ZQ==";
  private static final String FORBIDDEN_MESSAGE = "Access denied to spec for current role";

  private static final String CREATE = BankStatementAgentActions.CREATE_STATEMENT;
  private static final String PREVIEW = BankStatementAgentActions.PREVIEW_STATEMENT;
  private static final String IMPORT = BankStatementAgentActions.IMPORT_STATEMENT;
  private static final String UPDATE = BankStatementAgentActions.UPDATE_STATEMENT;
  private static final String PROCESS_ACTION = BankStatementAgentActions.PROCESS_STATEMENT;
  private static final String REACTIVATE = BankStatementAgentActions.REACTIVATE_STATEMENT;
  private static final String DELETE = BankStatementAgentActions.DELETE_STATEMENT;

  private static final List<String> ALL_ACTIONS = List.of(CREATE, PREVIEW, IMPORT, UPDATE,
      PROCESS_ACTION, REACTIVATE, DELETE);
  private static final List<String> ACCOUNT_ACTIONS = List.of(CREATE, PREVIEW, IMPORT);
  private static final List<String> ID_ONLY_ACTIONS = List.of(PROCESS_ACTION, REACTIVATE, DELETE);

  @Mock
  private BankStatementsHandler handler;
  @Mock
  private SFSpec spec;
  @Mock
  private SFEntity sfEntity;
  @Mock
  private OBDal dal;
  @Mock
  private Session session;
  /** A real JSON object whose {@code get} can be made to fail for the JSONException branch. */
  @Spy
  private JSONObject brokenParams = new JSONObject();

  private MockedStatic<NeoAccessHelper> access;
  private MockedStatic<OBDal> obDal;

  /** What the mocked engine answers; a success by default. */
  private NeoResponse engineAnswer;
  /** Every context the engine received, in call order. */
  private final List<NeoContext> received = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    engineAnswer = NeoResponse.ok(new JSONObject().put(ID, STMT_ID));
    when(handler.handle(any())).thenAnswer(inv -> {
      received.add(inv.getArgument(0));
      return engineAnswer;
    });
    when(spec.getName()).thenReturn(SPEC);
    when(sfEntity.getETGOSFSpec()).thenReturn(spec);

    access = mockStatic(NeoAccessHelper.class);
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(any(), anyString())).thenReturn(true);

    when(dal.getSession()).thenReturn(session);
    when(session.isDirty()).thenReturn(false);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    access.close();
    obDal.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private NeoContext actionContext(String action, String recordId, JSONObject params) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(SPEC)
        .httpMethod(POST)
        .recordId(recordId)
        .requestBody(params)
        .sfEntity(sfEntity)
        .mcpOrigin(true)
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(action)
        .build();
  }

  private NeoResponse run(String action, String recordId, JSONObject params) {
    return BankStatementAgentActions.dispatch(handler, actionContext(action, recordId, params));
  }

  private static String recordIdFor(String action) {
    return ACCOUNT_ACTIONS.contains(action) ? ACC_ID : STMT_ID;
  }

  private static JSONObject line() throws JSONException {
    return new JSONObject().put("date", DAY).put("description", "Transferencia")
        .put("bpartnerName", "Acme").put("in", 3500.0).put("out", 0);
  }

  private static JSONObject headerParams() throws JSONException {
    return new JSONObject().put(NAME, "Extracto junio").put(TRANSACTION_DATE, DAY)
        .put(IMPORT_DATE, DAY).put(LINES, new JSONArray().put(line()))
        .put(PROCESS, false).put(NOTES, "manual").put(FILE_NAME, CSV_NAME);
  }

  private static JSONObject uploadParams() throws JSONException {
    return new JSONObject().put(FILE_NAME, CSV_NAME).put(CONTENT, CSV_CONTENT);
  }

  /** Valid parameters for each action (id-only actions take none). */
  private static JSONObject paramsFor(String action) throws JSONException {
    if (CREATE.equals(action) || UPDATE.equals(action)) {
      return headerParams();
    }
    if (PREVIEW.equals(action) || IMPORT.equals(action)) {
      return uploadParams();
    }
    return new JSONObject();
  }

  private static String engineActionFor(String action) {
    Map<String, String> engine = new HashMap<>();
    engine.put(CREATE, "create");
    engine.put(PREVIEW, "preview");
    engine.put(IMPORT, "import");
    engine.put(UPDATE, "update");
    engine.put(PROCESS_ACTION, "process");
    engine.put(REACTIVATE, "reactivate");
    engine.put(DELETE, "delete");
    return engine.get(action);
  }

  private NeoContext onlyEngineCall() {
    assertEquals(1, received.size(), "the engine must be called exactly once");
    return received.get(0);
  }

  private void assertEngineNotCalled() {
    verify(handler, never()).handle(any());
  }

  private static JSONObject errorOf(NeoResponse response) throws JSONException {
    assertNotNull(response);
    return response.getBody().getJSONObject(ERROR);
  }

  private static List<String> strings(JSONArray arr) throws JSONException {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      out.add(arr.getString(i));
    }
    return out;
  }

  private static JSONObject parametersSchemaOf(String action) throws JSONException {
    return BankStatementAgentActions.CONTRACTS.get(action).toJson().getJSONObject(PARAMETERS);
  }

  // ── the declaration ──────────────────────────────────────────────────

  @Test
  @DisplayName("seven actions: account-level first, then statement-level")
  void testContractsDeclareTheSevenActionsInPresentationOrder() {
    assertEquals(ALL_ACTIONS, new ArrayList<>(BankStatementAgentActions.CONTRACTS.keySet()));
    BankStatementAgentActions.CONTRACTS.forEach(
        (name, contract) -> assertEquals(name, contract.getName(), name));
  }

  @Test
  @DisplayName("only previewStatement is a read")
  void testOnlyPreviewStatementIsRead() {
    BankStatementAgentActions.CONTRACTS.forEach(
        (name, contract) -> assertEquals(!PREVIEW.equals(name), contract.isMutating(), name));
  }

  @Test
  @DisplayName("each contract renders its required list and is a closed object")
  void testEachContractRendersTheExpectedRequiredList() throws Exception {
    Map<String, List<String>> expected = new HashMap<>();
    expected.put(CREATE, List.of(NAME, TRANSACTION_DATE, IMPORT_DATE, LINES));
    expected.put(PREVIEW, List.of(FILE_NAME, CONTENT));
    expected.put(IMPORT, List.of(FILE_NAME, CONTENT));
    expected.put(UPDATE, List.of(NAME, TRANSACTION_DATE, IMPORT_DATE));
    expected.put(PROCESS_ACTION, List.of());
    expected.put(REACTIVATE, List.of());
    expected.put(DELETE, List.of());
    for (String action : ALL_ACTIONS) {
      JSONObject schema = parametersSchemaOf(action);
      assertEquals(expected.get(action), strings(schema.getJSONArray(REQUIRED)), action);
      assertFalse(schema.getBoolean("additionalProperties"), action);
    }
  }

  @Test
  @DisplayName("create and update declare the same seven header/line parameters")
  void testCreateAndUpdateDeclareTheHeaderParameters() throws Exception {
    for (String action : List.of(CREATE, UPDATE)) {
      JSONObject props = parametersSchemaOf(action).getJSONObject(PROPERTIES);
      assertEquals(7, props.length(), action);
      for (String key : List.of(NAME, TRANSACTION_DATE, IMPORT_DATE, LINES, PROCESS, NOTES,
          FILE_NAME)) {
        assertTrue(props.has(key), action + " declares " + key);
      }
      assertFalse(props.has(ACCOUNT_KEY), action);
      assertFalse(props.has(ID), action);
    }
  }

  @Test
  @DisplayName("preview and import declare only fileName + contentBase64")
  void testUploadActionsDeclareOnlyTheFile() throws Exception {
    for (String action : List.of(PREVIEW, IMPORT)) {
      JSONObject props = parametersSchemaOf(action).getJSONObject(PROPERTIES);
      assertEquals(2, props.length(), action);
      assertTrue(props.has(FILE_NAME), action);
      assertTrue(props.has(CONTENT), action);
    }
  }

  @Test
  @DisplayName("process / reactivate / delete declare no parameter at all")
  void testIdOnlyActionsDeclareNoParameter() throws Exception {
    for (String action : ID_ONLY_ACTIONS) {
      assertEquals(0, parametersSchemaOf(action).getJSONObject(PROPERTIES).length(), action);
      assertTrue(BankStatementAgentActions.CONTRACTS.get(action).getParams().isEmpty(), action);
    }
  }

  @Test
  @DisplayName("both header dates are date-typed; lines is an array of objects; process a boolean")
  void testHeaderDatesAreDateTyped() throws Exception {
    for (String action : List.of(CREATE, UPDATE)) {
      JSONObject props = parametersSchemaOf(action).getJSONObject(PROPERTIES);
      for (String date : List.of(TRANSACTION_DATE, IMPORT_DATE)) {
        assertEquals("string", props.getJSONObject(date).getString("type"), action + date);
        assertEquals("date", props.getJSONObject(date).getString("format"), action + date);
      }
      assertEquals("array", props.getJSONObject(LINES).getString("type"), action);
      assertEquals("object",
          props.getJSONObject(LINES).getJSONObject("items").getString("type"), action);
      assertEquals("boolean", props.getJSONObject(PROCESS).getString("type"), action);
    }
    BankStatementAgentActions.CONTRACTS.get(CREATE).getParams().stream()
        .filter(p -> TRANSACTION_DATE.equals(p.getName()) || IMPORT_DATE.equals(p.getName()))
        .forEach(p -> assertEquals(NeoActionContract.TYPE_DATE, p.getType(), p.getName()));
  }

  @Test
  @DisplayName("account-level actions say id = financial account; the rest id = bank statement")
  void testEveryActionCarriesAnIdDescription() throws Exception {
    for (NeoActionContract contract : BankStatementAgentActions.CONTRACTS.values()) {
      String description = contract.getIdDescription();
      assertNotNull(description, contract.getName());
      String expected = ACCOUNT_ACTIONS.contains(contract.getName())
          ? "financial account id" : "bank statement id";
      assertTrue(description.toLowerCase().contains(expected), contract.getName());
      assertEquals(description, contract.toJson().getString("idDescription"), contract.getName());
    }
  }

  @Test
  @DisplayName("every action has a non-blank description")
  void testEveryActionHasADescription() {
    BankStatementAgentActions.CONTRACTS.forEach((name, contract) -> assertFalse(
        contract.getDescription() == null || contract.getDescription().isBlank(), name));
  }

  @Test
  @DisplayName("the declaration is immutable")
  void testContractsAreUnmodifiable() {
    assertThrows(UnsupportedOperationException.class,
        () -> BankStatementAgentActions.CONTRACTS.remove(CREATE));
  }

  // ── refusals before the engine runs ──────────────────────────────────

  @Test
  @DisplayName("unknown action → 422 availableActions (the seven names)")
  void testUnknownActionIsRefused() throws Exception {
    NeoResponse response = run("matchStatement", ACC_ID, null);

    assertEquals(422, response.getHttpStatus());
    assertEquals(ALL_ACTIONS, strings(errorOf(response).getJSONArray("availableActions")));
    assertEngineNotCalled();
    access.verifyNoInteractions();
  }

  @Test
  @DisplayName("a null action → 422")
  void testNullActionIsRefused() {
    assertEquals(422, run(null, ACC_ID, null).getHttpStatus());
    assertEngineNotCalled();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "create", "preview", "import", "update", "process", "reactivate",
      "delete" })
  @DisplayName("the SPA's own engine action names are not agent actions")
  void testEngineActionNamesAreNotAgentActions(String engineAction) {
    assertEquals(422, run(engineAction, ACC_ID, new JSONObject()).getHttpStatus());
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("createStatement without its required parameters → 422 missingParameters")
  void testCreateWithoutRequiredParametersIsRefused() throws Exception {
    NeoResponse response = run(CREATE, ACC_ID, new JSONObject().put(NOTES, "only notes"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(List.of(NAME, TRANSACTION_DATE, IMPORT_DATE, LINES),
        strings(errorOf(response).getJSONArray(MISSING_PARAMETERS)));
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("createStatement with an empty lines array → 422 missing lines")
  void testCreateWithEmptyLinesIsRefused() throws Exception {
    JSONObject params = headerParams().put(LINES, new JSONArray());
    NeoResponse response = run(CREATE, ACC_ID, params);

    assertEquals(422, response.getHttpStatus());
    assertEquals(List.of(LINES), strings(errorOf(response).getJSONArray(MISSING_PARAMETERS)));
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("updateStatement without lines is accepted by the contract (lines optional)")
  void testUpdateWithoutLinesReachesTheEngine() throws Exception {
    JSONObject params = headerParams();
    params.remove(LINES);
    NeoResponse response = run(UPDATE, STMT_ID, params);

    assertSame(engineAnswer, response);
    assertFalse(onlyEngineCall().getRequestBody().has(LINES));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "previewStatement", "importStatement" })
  @DisplayName("an upload without its content → 422 missing contentBase64")
  void testUploadWithoutContentIsRefused(String action) throws Exception {
    NeoResponse response = run(action, ACC_ID, new JSONObject().put(FILE_NAME, CSV_NAME));

    assertEquals(422, response.getHttpStatus());
    assertEquals(List.of(CONTENT), strings(errorOf(response).getJSONArray(MISSING_PARAMETERS)));
    assertEngineNotCalled();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "02/06/2026", "2026-6-2", "yesterday" })
  @DisplayName("a header date not in yyyy-MM-dd → 422 naming the field")
  void testBadDateShapeIsRefused(String badDate) throws Exception {
    JSONObject params = headerParams().put(TRANSACTION_DATE, badDate);
    NeoResponse response = run(CREATE, ACC_ID, params);

    assertEquals(422, response.getHttpStatus());
    JSONObject error = errorOf(response);
    assertEquals(TRANSACTION_DATE, error.getString("field"));
    assertEquals("date (yyyy-MM-dd)", error.getString("expectedType"));
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("updateStatement with a bad importDate → 422 naming importDate")
  void testUpdateBadImportDateIsRefused() throws Exception {
    NeoResponse response = run(UPDATE, STMT_ID, headerParams().put(IMPORT_DATE, "2026/06/02"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(IMPORT_DATE, errorOf(response).getString("field"));
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("a non-boolean process flag → 422")
  void testNonBooleanProcessIsRefused() throws Exception {
    NeoResponse response = run(CREATE, ACC_ID, headerParams().put(PROCESS, "yes"));

    assertEquals(422, response.getHttpStatus());
    assertEquals(PROCESS, errorOf(response).getString("field"));
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("a line that is not an object → 422 naming lines")
  void testNonObjectLineIsRefused() throws Exception {
    NeoResponse response = run(CREATE, ACC_ID,
        headerParams().put(LINES, new JSONArray().put("not a line")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(LINES, errorOf(response).getString("field"));
    assertEngineNotCalled();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "processStatement", "reactivateStatement", "deleteStatement" })
  @DisplayName("an id-only action refuses any parameter")
  void testIdOnlyActionRefusesExtraKeys(String action) throws Exception {
    NeoResponse response = run(action, STMT_ID, new JSONObject().put("force", true));

    assertEquals(422, response.getHttpStatus());
    assertEquals(List.of("force"), strings(errorOf(response).getJSONArray(UNKNOWN_PARAMETERS)));
    assertEngineNotCalled();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "createStatement", "previewStatement", "importStatement" })
  @DisplayName("a FIN_Financial_Account_ID smuggled in the parameters is refused (undeclared)")
  void testAccountIdInParametersIsRefused(String action) throws Exception {
    JSONObject params = paramsFor(action).put(ACCOUNT_KEY, "OTHER");
    NeoResponse response = run(action, ACC_ID, params);

    assertEquals(422, response.getHttpStatus());
    assertEquals(List.of(ACCOUNT_KEY),
        strings(errorOf(response).getJSONArray(UNKNOWN_PARAMETERS)));
    assertEngineNotCalled();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "updateStatement", "processStatement", "deleteStatement" })
  @DisplayName("an id smuggled in the parameters is refused (undeclared)")
  void testIdInParametersIsRefused(String action) throws Exception {
    JSONObject params = paramsFor(action).put(ID, "OTHER");
    NeoResponse response = run(action, STMT_ID, params);

    assertEquals(422, response.getHttpStatus());
    assertEquals(List.of(ID), strings(errorOf(response).getJSONArray(UNKNOWN_PARAMETERS)));
    assertEngineNotCalled();
  }

  @ParameterizedTest(name = "id=''{0}''")
  @ValueSource(strings = { "", "   " })
  @DisplayName("a blank id on an account-level action → 422 naming the financial account id")
  void testBlankAccountIdIsRefused(String id) throws Exception {
    NeoResponse response = run(IMPORT, id, uploadParams());

    assertEquals(422, response.getHttpStatus());
    assertEquals("id (the financial account id) is required",
        errorOf(response).getString(MESSAGE));
    assertEngineNotCalled();
    access.verifyNoInteractions();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "updateStatement", "processStatement", "reactivateStatement",
      "deleteStatement" })
  @DisplayName("a null id on a statement-level action → 422 naming the bank statement id")
  void testNullStatementIdIsRefused(String action) throws Exception {
    NeoResponse response = run(action, null, paramsFor(action));

    assertEquals(422, response.getHttpStatus());
    assertEquals("id (the bank statement id) is required", errorOf(response).getString(MESSAGE));
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("contract validation runs before the id check")
  void testValidationRunsBeforeTheIdCheck() throws Exception {
    NeoResponse response = run(CREATE, null, new JSONObject());

    assertEquals(422, response.getHttpStatus());
    assertTrue(errorOf(response).has(MISSING_PARAMETERS));
  }

  // ── the role gate ────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "createStatement", "importStatement", "updateStatement",
      "processStatement", "reactivateStatement", "deleteStatement" })
  @DisplayName("a role without report-spec POST access → 403 on every write")
  void testWriteWithoutPostAccessIsForbidden(String action) throws Exception {
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST)).thenReturn(false);
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(true);

    NeoResponse response = run(action, recordIdFor(action), paramsFor(action));

    assertEquals(403, response.getHttpStatus());
    assertEquals(FORBIDDEN_MESSAGE, errorOf(response).getString(MESSAGE));
    access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST));
    access.verify(() -> NeoAccessHelper.hasReportSpecAccess(any(), eq(GET)), never());
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("previewStatement is gated as a GET, so a read-only role can preview")
  void testPreviewIsCheckedWithGet() throws Exception {
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST)).thenReturn(false);
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(true);

    NeoResponse response = run(PREVIEW, ACC_ID, uploadParams());

    assertSame(engineAnswer, response);
    access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET));
    access.verify(() -> NeoAccessHelper.hasReportSpecAccess(any(), eq(POST)), never());
  }

  @Test
  @DisplayName("previewStatement without GET access → 403")
  void testPreviewWithoutGetAccessIsForbidden() throws Exception {
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(false);

    assertEquals(403, run(PREVIEW, ACC_ID, uploadParams()).getHttpStatus());
    assertEngineNotCalled();
  }

  @Test
  @DisplayName("fails closed (403) when the context carries no entity")
  void testNoEntityFailsClosed() throws Exception {
    NeoContext ctx = NeoContext.builder().specName(SPEC).httpMethod(POST).recordId(STMT_ID)
        .endpointType(NeoEndpointType.ACTION).fieldName(DELETE).build();

    assertEquals(403, BankStatementAgentActions.dispatch(handler, ctx).getHttpStatus());
    assertEngineNotCalled();
    access.verifyNoInteractions();
  }

  @Test
  @DisplayName("fails closed (403) when the entity resolves no spec")
  void testNoSpecFailsClosed() {
    when(sfEntity.getETGOSFSpec()).thenReturn(null);

    assertEquals(403, run(DELETE, STMT_ID, null).getHttpStatus());
    assertEngineNotCalled();
    access.verifyNoInteractions();
  }

  // ── the derived engine request ───────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "createStatement", "previewStatement", "importStatement",
      "updateStatement", "processStatement", "reactivateStatement", "deleteStatement" })
  @DisplayName("every action re-enters the engine as the SPA's POST ?action=<engine action>")
  void testDerivedContextLooksLikeTheSpaRequest(String action) throws Exception {
    NeoResponse response = run(action, recordIdFor(action), paramsFor(action));

    assertSame(engineAnswer, response, "the engine's own response is returned");
    NeoContext derived = onlyEngineCall();
    assertEquals(POST, derived.getHttpMethod());
    assertNull(derived.getEndpointType(), "no endpoint type, so it cannot re-enter dispatch");
    assertNull(derived.getFieldName());
    assertEquals(Map.of(ACTION, engineActionFor(action)), derived.getQueryParams());
    assertEquals(SPEC, derived.getSpecName());
    assertEquals(SPEC, derived.getEntityName());
    assertSame(sfEntity, derived.getSfEntity());
    assertTrue(derived.isMcpOrigin());
    assertEquals(recordIdFor(action), derived.getRecordId());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "createStatement", "previewStatement", "importStatement" })
  @DisplayName("account-level: body = parameters + FIN_Financial_Account_ID = id")
  void testAccountLevelBodyCarriesTheParametersAndTheAccount(String action) throws Exception {
    JSONObject params = paramsFor(action);
    String before = params.toString();

    run(action, ACC_ID, params);

    JSONObject body = onlyEngineCall().getRequestBody();
    assertEquals(ACC_ID, body.getString(ACCOUNT_KEY));
    assertFalse(body.has(ID));
    assertEquals(params.length() + 1, body.length(), "every parameter passes through");
    for (Iterator<?> it = params.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      assertEquals(params.get(key).toString(), body.get(key).toString(), key);
    }
    assertNotSame(params, body, "the caller's parameters are copied");
    assertEquals(before, params.toString(), "the caller's parameters are not mutated");
  }

  @Test
  @DisplayName("updateStatement: body = parameters + id = the statement id")
  void testUpdateBodyCarriesTheParametersAndTheStatementId() throws Exception {
    JSONObject params = headerParams();

    run(UPDATE, STMT_ID, params);

    JSONObject body = onlyEngineCall().getRequestBody();
    assertEquals(STMT_ID, body.getString(ID));
    assertFalse(body.has(ACCOUNT_KEY));
    assertEquals(params.length() + 1, body.length());
    assertEquals(DAY, body.getString(TRANSACTION_DATE));
    assertEquals(DAY, body.getString(IMPORT_DATE));
    assertEquals(1, body.getJSONArray(LINES).length());
    assertFalse(params.has(ID), "the caller's parameters are not mutated");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "processStatement", "reactivateStatement", "deleteStatement" })
  @DisplayName("id-only actions send exactly {id}, as the SPA does")
  void testIdOnlyBodyIsTheStatementIdAlone(String action) throws Exception {
    run(action, STMT_ID, null);

    JSONObject body = onlyEngineCall().getRequestBody();
    assertEquals(1, body.length());
    assertEquals(STMT_ID, body.getString(ID));
  }

  @Test
  @DisplayName("the id is trimmed before it goes into the engine body")
  void testIdIsTrimmedInTheBody() throws Exception {
    run(IMPORT, "  " + ACC_ID + " ", uploadParams());

    assertEquals(ACC_ID, onlyEngineCall().getRequestBody().getString(ACCOUNT_KEY));
  }

  @Test
  @DisplayName("an explicit null optional parameter is accepted and not forwarded")
  void testNullOptionalParameterIsNotForwarded() throws Exception {
    JSONObject params = headerParams();
    params.put(NOTES, JSONObject.NULL);

    run(CREATE, ACC_ID, params);

    JSONObject body = onlyEngineCall().getRequestBody();
    assertTrue(!body.has(NOTES) || body.isNull(NOTES));
  }

  @Test
  @DisplayName("an engine error response is returned untouched")
  void testEngineErrorIsReturnedUntouched() {
    engineAnswer = NeoResponse.error(400, "The statement is already processed");

    assertSame(engineAnswer, run(PROCESS_ACTION, STMT_ID, null));
  }

  @Test
  @DisplayName("a JSONException while building the engine body → 422 and the engine never runs")
  void testJsonExceptionIsUnprocessable() throws Exception {
    brokenParams.put(FILE_NAME, CSV_NAME).put(CONTENT, CSV_CONTENT);
    // The contract check reads fileName once (real value); the body copy reads it again and fails.
    doCallRealMethod().doThrow(new JSONException("broken value")).when(brokenParams)
        .get(FILE_NAME);

    NeoResponse response = run(IMPORT, ACC_ID, brokenParams);

    assertEquals(422, response.getHttpStatus());
    assertEquals("Invalid action parameters: broken value", errorOf(response).getString(MESSAGE));
    assertEngineNotCalled();
  }

  // ── flush to clean after a successful write ──────────────────────────

  @Test
  @DisplayName("dirty after the engine → flushed until clean, then the engine's success")
  void testDirtySessionIsFlushedUntilClean() {
    when(session.isDirty()).thenReturn(true, true, false);

    NeoResponse response = run(PROCESS_ACTION, STMT_ID, null);

    assertSame(engineAnswer, response);
    verify(dal, times(2)).flush();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("a clean session is not flushed")
  void testCleanSessionIsNotFlushed() {
    assertSame(engineAnswer, run(REACTIVATE, STMT_ID, null));
    verify(dal, never()).flush();
    verify(session).isDirty();
  }

  @Test
  @DisplayName("a 201 (created) is a success and goes through the flush")
  void testCreatedResponseIsFlushed() throws Exception {
    engineAnswer = NeoResponse.created(new JSONObject().put(ID, STMT_ID));
    when(session.isDirty()).thenReturn(true, false);

    assertSame(engineAnswer, run(CREATE, ACC_ID, headerParams()));
    verify(dal).flush();
  }

  @Test
  @DisplayName("a session that never gets clean stops at the 100-flush cap and still answers")
  void testFlushLoopStopsAtTheCap() {
    when(session.isDirty()).thenReturn(true);

    assertSame(engineAnswer, run(DELETE, STMT_ID, null));
    verify(dal, times(100)).flush();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("a failing flush → rollback + JSON 500 naming the cause")
  void testFlushFailureRollsBackAndAnswers500() throws Exception {
    when(session.isDirty()).thenReturn(true);
    doThrow(new IllegalStateException("constraint violated")).when(dal).flush();

    NeoResponse response = run(IMPORT, ACC_ID, uploadParams());

    assertEquals(500, response.getHttpStatus());
    assertEquals("The bank statement changes could not be saved and were rolled back: "
        + "constraint violated", errorOf(response).getString(MESSAGE));
    verify(dal).rollbackAndClose();
  }

  @Test
  @DisplayName("a flush failure without a message names the exception class")
  void testFlushFailureWithoutMessageNamesTheClass() throws Exception {
    when(session.isDirty()).thenReturn(true);
    doThrow(new NullPointerException()).when(dal).flush();

    NeoResponse response = run(UPDATE, STMT_ID, headerParams());

    assertEquals(500, response.getHttpStatus());
    assertTrue(errorOf(response).getString(MESSAGE).endsWith("rolled back: NullPointerException"));
    verify(dal).rollbackAndClose();
  }

  @ParameterizedTest(name = "status {0}")
  @ValueSource(ints = { 400, 404, 409, 500 })
  @DisplayName("an engine error skips the flush and the extra rollback")
  void testErrorResponseSkipsTheFlush(int status) {
    engineAnswer = NeoResponse.error(status, "refused");
    when(session.isDirty()).thenReturn(true);

    assertSame(engineAnswer, run(DELETE, STMT_ID, null));
    obDal.verify(OBDal::getInstance, never());
    verify(dal, never()).flush();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("a null engine result passes through untouched")
  void testNullEngineResultPassesThrough() {
    engineAnswer = null;

    assertNull(run(PROCESS_ACTION, STMT_ID, null));
    obDal.verify(OBDal::getInstance, never());
  }

  @Test
  @DisplayName("previewStatement never reaches the flush, even on a dirty session")
  void testPreviewSkipsTheFlush() throws Exception {
    when(session.isDirty()).thenReturn(true);

    assertSame(engineAnswer, run(PREVIEW, ACC_ID, uploadParams()));
    obDal.verify(OBDal::getInstance, never());
    verify(dal, never()).flush();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "createStatement", "importStatement", "updateStatement",
      "processStatement", "reactivateStatement", "deleteStatement" })
  @DisplayName("every write goes through the flush")
  void testEveryWriteFlushes(String action) throws Exception {
    when(session.isDirty()).thenReturn(true, false);

    assertSame(engineAnswer, run(action, recordIdFor(action), paramsFor(action)));
    verify(dal).flush();
  }
}
