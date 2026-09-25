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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link BankStatementAgentActions} (ETP-5469) — the {@code neo_action} surface of
 * {@code bank-statements} — and the ACTION branch of {@link BankStatementsHandler#handle}:
 *
 * <ul>
 *   <li>the six declared contracts (names, order, mutating flag, required lists, id wording);</li>
 *   <li>each action re-enters the SAME package-private {@code handle*} method the SPA route uses,
 *       with the record id under the key that route reads ({@code FIN_Financial_Account_ID},
 *       {@code statementId} or {@code id});</li>
 *   <li>the refusals that run before anything is touched: contract (422), id (422), role (403) and
 *       the agent-only validation (422);</li>
 *   <li>the flush-to-clean of the writes and its rollback;</li>
 *   <li>the SPA's {@code ?action=} contexts (no endpoint type) keep their old routing.</li>
 * </ul>
 *
 * <p>The six handler methods are stubbed on a spy, so no DAL is needed; {@code OBDal} is mocked
 * statically only for the flush.</p>
 */
@SuppressWarnings("java:S2187")
@DisplayName("BankStatementAgentActions (ETP-5469)")
class BankStatementAgentActionsTest {

  private static final String SPEC = "bank-statements";
  private static final String ACC_ID = "ACC-1";
  private static final String STMT_ID = "ST-1";
  private static final String POST = "POST";
  private static final String GET = "GET";
  private static final String ERROR = "error";
  private static final String MESSAGE = "message";
  private static final String FORBIDDEN_MESSAGE = "Access denied to spec for current role";

  private static final String LIST = BankStatementAgentActions.LIST_STATEMENTS;
  private static final String LINES = BankStatementAgentActions.STATEMENT_LINES;
  private static final String CREATE = BankStatementAgentActions.CREATE_STATEMENT;
  private static final String IMPORT = BankStatementAgentActions.IMPORT_STATEMENT;
  private static final String PROCESS = BankStatementAgentActions.PROCESS_STATEMENT;
  private static final String REACTIVATE = BankStatementAgentActions.REACTIVATE_STATEMENT;

  /** The SPA handler method each agent action must reuse. */
  private enum Route {
    LIST, LINES, CREATE, IMPORT, PROCESS, REACTIVATE
  }

  private static final Map<String, Route> ACTION_TO_ROUTE = Map.of(
      LIST, Route.LIST, LINES, Route.LINES, CREATE, Route.CREATE, IMPORT, Route.IMPORT,
      PROCESS, Route.PROCESS, REACTIVATE, Route.REACTIVATE);

  private BankStatementsHandler handler;
  private SFSpec spec;
  private SFEntity sfEntity;
  private MockedStatic<NeoAccessHelper> access;
  private MockedStatic<OBDal> obDal;
  private OBDal dal;
  private Session session;

  /** One sentinel per route, so a mis-routing is caught by identity. */
  private final Map<Route, NeoResponse> sentinels = new EnumMap<>(Route.class);

  @BeforeEach
  void setUp() {
    handler = spy(new BankStatementsHandler());
    spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(SPEC);
    sfEntity = mock(SFEntity.class);
    when(sfEntity.getETGOSFSpec()).thenReturn(spec);

    for (Route r : Route.values()) {
      sentinels.put(r, NeoResponse.error(299, r.name()));
    }
    stubRoutes();

    access = mockStatic(NeoAccessHelper.class);
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(any(), anyString())).thenReturn(true);

    dal = mock(OBDal.class);
    session = mock(Session.class);
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

  /** (Re)stubs every route with its current sentinel. */
  private void stubRoutes() {
    doReturn(sentinels.get(Route.LIST)).when(handler).handleList(any());
    doReturn(sentinels.get(Route.LINES)).when(handler).handleGetLines(any());
    doReturn(sentinels.get(Route.CREATE)).when(handler).handleCreate(any());
    doReturn(sentinels.get(Route.IMPORT)).when(handler).handleImport(any());
    doReturn(sentinels.get(Route.PROCESS)).when(handler).handleProcess(any());
    doReturn(sentinels.get(Route.REACTIVATE)).when(handler).handleReactivate(any());
  }

  private void answer(Route route, NeoResponse response) {
    sentinels.put(route, response);
    stubRoutes();
  }

  /** Runs {@code call} against the spy's verification of {@code route}'s method. */
  private void onRoute(Route route, BankStatementsHandler target, NeoContext arg) {
    switch (route) {
      case LIST:
        target.handleList(arg);
        break;
      case LINES:
        target.handleGetLines(arg);
        break;
      case CREATE:
        target.handleCreate(arg);
        break;
      case IMPORT:
        target.handleImport(arg);
        break;
      case PROCESS:
        target.handleProcess(arg);
        break;
      default:
        target.handleReactivate(arg);
    }
  }

  /** @return the single context {@code route} received (asserting it ran exactly once). */
  private NeoContext onlyCallOf(Route route) {
    ArgumentCaptor<NeoContext> captor = ArgumentCaptor.forClass(NeoContext.class);
    onRoute(route, verify(handler, times(1)), captor.capture());
    for (Route other : Route.values()) {
      if (other != route) {
        onRoute(other, verify(handler, never()), any());
      }
    }
    return captor.getValue();
  }

  private void assertNoRouteCalled() {
    for (Route r : Route.values()) {
      onRoute(r, verify(handler, never()), any());
    }
  }

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

  private NeoContext spaContext(String method, String spaAction, JSONObject body,
      NeoEndpointType type) {
    Map<String, String> qp = new HashMap<>();
    if (spaAction != null) {
      qp.put("action", spaAction);
    }
    qp.put("accountId", ACC_ID);
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(SPEC)
        .httpMethod(method)
        .requestBody(body)
        .queryParams(qp)
        .sfEntity(sfEntity)
        .endpointType(type)
        .build();
  }

  private static JSONObject validLine() throws Exception {
    return new JSONObject().put("date", "2026-02-28").put("in", "12.50")
        .put("description", "Transfer").put("reference", "REF-1");
  }

  private static JSONObject validCreate() throws Exception {
    return new JSONObject().put(BankStatementAgentActions.P_NAME, "Statement Feb")
        .put(BankStatementAgentActions.P_TRANSACTION_DATE, "2026-02-28")
        .put(BankStatementAgentActions.P_LINES, new JSONArray().put(validLine()));
  }

  private static JSONObject validImport() throws Exception {
    return new JSONObject().put(BankStatementAgentActions.P_FILE_NAME, "feb.n43")
        .put(BankStatementAgentActions.P_CONTENT_BASE64, "QUJD");
  }

  /** Valid parameters for any action; the reads and process/reactivate declare none. */
  private static JSONObject paramsFor(String action) throws Exception {
    if (CREATE.equals(action)) {
      return validCreate();
    }
    if (IMPORT.equals(action)) {
      return validImport();
    }
    return new JSONObject();
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject(ERROR).getString(MESSAGE);
  }

  private static List<String> strings(JSONArray arr) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      out.add(arr.getString(i));
    }
    return out;
  }

  // ── the declaration ────────────────────────────────────────────────────

  @Test
  @DisplayName("the handler publishes the six actions; only the two reads are non-mutating")
  void handlerPublishesContracts() {
    BankStatementsHandler plain = new BankStatementsHandler();
    Map<String, NeoActionContract> contracts = plain.actionContracts();
    assertEquals(List.of(LIST, LINES, CREATE, IMPORT, PROCESS, REACTIVATE),
        new ArrayList<>(contracts.keySet()));
    contracts.forEach((name, c) -> assertEquals(!List.of(LIST, LINES).contains(name),
        c.isMutating(), name));
    assertTrue(plain.servesActions());
  }

  // ── routing: action → SPA method + id key ─────────────────────────────

  @Nested
  @DisplayName("routing")
  class Routing {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "listStatements", "statementLines" })
    @DisplayName("reads re-enter the SPA GET method with the record id as the only query param")
    void readsUseGetWithIdQueryParam(String action) {
      String idKey = LIST.equals(action) ? BankStatementAgentActions.KEY_ACCOUNT_ID
          : BankStatementAgentActions.KEY_STATEMENT_ID;
      Route route = ACTION_TO_ROUTE.get(action);

      NeoResponse response = handler.handle(actionContext(action, ACC_ID, null));

      assertSame(sentinels.get(route), response, "the SPA route's own response is returned");
      NeoContext derived = onlyCallOf(route);
      assertEquals(GET, derived.getHttpMethod());
      assertNull(derived.getRequestBody());
      assertEquals(Map.of(idKey, ACC_ID), derived.getQueryParams());
      assertNull(derived.getEndpointType(), "derived context looks like the SPA request");
      assertSame(sfEntity, derived.getSfEntity());
      assertTrue(derived.isMcpOrigin());
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET));
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST), never());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "createStatement", "importStatement", "processStatement",
        "reactivateStatement" })
    @DisplayName("writes re-enter the SPA POST method with the record id under the route's key")
    void writesUsePostWithIdInBody(String action) throws Exception {
      String idKey = List.of(CREATE, IMPORT).contains(action)
          ? BankStatementAgentActions.KEY_ACCOUNT_ID : BankStatementAgentActions.KEY_ID;
      Route route = ACTION_TO_ROUTE.get(action);
      JSONObject params = paramsFor(action);
      String paramsBefore = params.toString();

      // Padded on purpose: the record id is trimmed before it is handed over.
      NeoResponse response = handler.handle(actionContext(action, "  " + STMT_ID + " ", params));

      assertSame(sentinels.get(route), response);
      NeoContext derived = onlyCallOf(route);
      assertEquals(POST, derived.getHttpMethod());
      assertTrue(derived.getQueryParams().isEmpty(), "no ?action= is forged");
      assertNull(derived.getEndpointType());
      JSONObject body = derived.getRequestBody();
      assertEquals(STMT_ID, body.getString(idKey));
      assertEquals(params.length() + 1, body.length(), "every parameter passes through");
      for (java.util.Iterator<?> it = params.keys(); it.hasNext();) {
        String key = String.valueOf(it.next());
        assertEquals(params.get(key).toString(), body.get(key).toString(), key);
      }
      assertNotSame(params, body, "the caller's parameters are copied");
      assertEquals(paramsBefore, params.toString(), "the caller's parameters are not mutated");
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST));
    }
  }

  // ── a spoofed id inside the parameters ─────────────────────────────────

  @Nested
  @DisplayName("an id smuggled inside parameters")
  class SpoofedId {

    @Test
    @DisplayName("FIN_Financial_Account_ID in createStatement parameters is refused (undeclared)")
    void accountIdInCreateRefused() throws Exception {
      JSONObject params = validCreate().put(BankStatementAgentActions.KEY_ACCOUNT_ID, "OTHER");
      NeoResponse response = handler.handle(actionContext(CREATE, ACC_ID, params));
      assertEquals(422, response.getHttpStatus());
      assertEquals(List.of(BankStatementAgentActions.KEY_ACCOUNT_ID), strings(response.getBody()
          .getJSONObject(ERROR).getJSONArray("unknownParameters")));
      assertNoRouteCalled();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "processStatement", "reactivateStatement" })
    @DisplayName("id in process/reactivate parameters is refused (undeclared)")
    void idInStatementWriteRefused(String action) throws Exception {
      NeoResponse response = handler.handle(actionContext(action, STMT_ID,
          new JSONObject().put(BankStatementAgentActions.KEY_ID, "OTHER")));
      assertEquals(422, response.getHttpStatus());
      assertEquals(List.of(BankStatementAgentActions.KEY_ID), strings(response.getBody()
          .getJSONObject(ERROR).getJSONArray("unknownParameters")));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("statementId in statementLines parameters is refused, never forwarded")
    void statementIdInReadRefused() throws Exception {
      NeoResponse response = handler.handle(actionContext(LINES, STMT_ID,
          new JSONObject().put(BankStatementAgentActions.KEY_STATEMENT_ID, "OTHER")));
      assertEquals(422, response.getHttpStatus());
      assertNoRouteCalled();
    }
  }

  // ── refusals before anything runs ──────────────────────────────────────

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    @DisplayName("unknown action → 422 listing the six available actions")
    void unknownAction() throws Exception {
      NeoResponse response = handler.handle(actionContext("deleteStatement", ACC_ID, null));
      assertEquals(422, response.getHttpStatus());
      assertEquals(new ArrayList<>(BankStatementAgentActions.CONTRACTS.keySet()),
          strings(response.getBody().getJSONObject(ERROR).getJSONArray("availableActions")));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("a missing required parameter → 422 missingParameters")
    void missingRequired() throws Exception {
      NeoResponse response = handler.handle(actionContext(IMPORT, ACC_ID,
          new JSONObject().put(BankStatementAgentActions.P_FILE_NAME, "feb.n43")));
      assertEquals(422, response.getHttpStatus());
      assertEquals(List.of(BankStatementAgentActions.P_CONTENT_BASE64), strings(response
          .getBody().getJSONObject(ERROR).getJSONArray("missingParameters")));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("a parameter of the wrong type → 422 naming the field")
    void wrongType() throws Exception {
      NeoResponse response = handler.handle(actionContext(CREATE, ACC_ID,
          validCreate().put(BankStatementAgentActions.P_PROCESS, "yes")));
      assertEquals(422, response.getHttpStatus());
      assertEquals(BankStatementAgentActions.P_PROCESS,
          response.getBody().getJSONObject(ERROR).getString("field"));
      assertNoRouteCalled();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "listStatements, financial account id",
        "createStatement, financial account id",
        "importStatement, financial account id",
        "statementLines, bank statement id",
        "processStatement, bank statement id",
        "reactivateStatement, bank statement id" })
    @DisplayName("a null/blank id → 422 naming what the id must be for that action")
    void blankId(String action, String expectedWording) throws Exception {
      for (String id : new String[] { null, "", "   " }) {
        NeoResponse response = handler.handle(actionContext(action, id, paramsFor(action)));
        assertEquals(422, response.getHttpStatus(), "id=" + id);
        String message = errorMessage(response);
        assertTrue(message.startsWith("id is required: "), message);
        assertTrue(message.contains(expectedWording), message);
      }
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("the id check runs before the role gate (a blank id never asks for access)")
    void idCheckedBeforeAccess() {
      handler.handle(actionContext(PROCESS, null, null));
      access.verifyNoInteractions();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "createStatement", "importStatement", "processStatement",
        "reactivateStatement" })
    @DisplayName("a role without report-spec POST access → 403 on every write action")
    void writeWithoutPostIs403(String action) throws Exception {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST)).thenReturn(false);
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(true);

      NeoResponse response = handler.handle(actionContext(action, ACC_ID, paramsFor(action)));

      assertEquals(403, response.getHttpStatus());
      assertEquals(FORBIDDEN_MESSAGE, errorMessage(response));
      assertNoRouteCalled();
      obDal.verify(OBDal::getInstance, never());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "listStatements", "statementLines" })
    @DisplayName("a read-only role (GET but no POST) can still run the reads")
    void readOnlyRoleCanRead(String action) {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST)).thenReturn(false);
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(true);
      assertSame(sentinels.get(ACTION_TO_ROUTE.get(action)),
          handler.handle(actionContext(action, ACC_ID, null)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "listStatements", "statementLines" })
    @DisplayName("a role without GET access → 403 on the reads")
    void readWithoutGetIs403(String action) {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(false);
      assertEquals(403, handler.handle(actionContext(action, ACC_ID, null)).getHttpStatus());
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("fails closed (403) when the context carries no spec")
    void noSpecFailsClosed() {
      NeoContext ctx = NeoContext.builder().specName(SPEC).httpMethod(POST).recordId(ACC_ID)
          .endpointType(NeoEndpointType.ACTION).fieldName(LIST).build();
      assertEquals(403, handler.handle(ctx).getHttpStatus());
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("the agent validation runs after the role gate and blocks the route (422)")
    void agentValidationBlocksRoute() throws Exception {
      JSONObject params = validCreate().put(BankStatementAgentActions.P_LINES,
          new JSONArray().put(new JSONObject().put("date", "2026-02-28").put("in", "5")
              .put("out", "5")));
      NeoResponse response = handler.handle(actionContext(CREATE, ACC_ID, params));
      assertEquals(422, response.getHttpStatus());
      assertTrue(errorMessage(response).startsWith("lines[0]: "));
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("importStatement over the size cap is refused before the file is decoded")
    void importOverCapRefused() throws Exception {
      JSONObject params = validImport().put(BankStatementAgentActions.P_CONTENT_BASE64,
          "A".repeat(BankStatementAgentValidation.MAX_IMPORT_BASE64_CHARS + 1));
      NeoResponse response = handler.handle(actionContext(IMPORT, ACC_ID, params));
      assertEquals(422, response.getHttpStatus());
      assertNoRouteCalled();
    }
  }

  // ── writes flush to clean (the flush itself: AgentActionSupportTest) ──

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { "listStatements", "statementLines", "createStatement",
      "importStatement", "processStatement", "reactivateStatement" })
  @DisplayName("a successful write goes through the flush (failure → OBDal rollback + JSON 500); a read never does")
  void onlyWritesFlush(String action) throws Exception {
    boolean mutating = BankStatementAgentActions.CONTRACTS.get(action).isMutating();
    NeoResponse ok = NeoResponse.ok(new JSONObject());
    answer(ACTION_TO_ROUTE.get(action), ok);
    when(session.isDirty()).thenReturn(true);
    Mockito.doThrow(new IllegalStateException("constraint violated")).when(dal).flush();

    NeoResponse response = handler.handle(actionContext(action, ACC_ID, paramsFor(action)));

    if (mutating) {
      assertEquals(500, response.getHttpStatus());
      assertTrue(errorMessage(response).startsWith("The bank statement changes"));
      verify(dal).rollbackAndClose();
    } else {
      assertSame(ok, response);
      obDal.verify(OBDal::getInstance, never());
    }
  }

  // ── the SPA routes are untouched ───────────────────────────────────────

  @Nested
  @DisplayName("SPA ?action= routing is unchanged")
  class SpaRegression {

    private void assertSpaRoute(NeoContext ctx, Route route) {
      assertSame(sentinels.get(route), handler.handle(ctx));
      assertSame(ctx, onlyCallOf(route), "not diverted, not re-derived");
      access.verifyNoInteractions();
      obDal.verify(OBDal::getInstance, never());
    }

    @ParameterizedTest(name = "POST ?action={0}")
    @ValueSource(strings = { "create", "import", "process", "reactivate" })
    @DisplayName("SPA POST routes get the original context and body, unvalidated")
    void spaPostRoutes(String spaAction) throws Exception {
      Map<String, Route> spaRoutes = Map.of("create", Route.CREATE, "import", Route.IMPORT,
          "process", Route.PROCESS, "reactivate", Route.REACTIVATE);
      // A body the agent contract would refuse: the SPA route must not care.
      JSONObject body = new JSONObject().put(BankStatementAgentActions.KEY_ACCOUNT_ID, ACC_ID)
          .put("anythingTheSpaSends", "kept");
      NeoContext ctx = spaContext(POST, spaAction, body, null);

      assertSpaRoute(ctx, spaRoutes.get(spaAction));
      assertSame(body, ctx.getRequestBody());
    }

    @Test
    @DisplayName("GET without ?action= still lists the statements")
    void spaGetList() {
      assertSpaRoute(spaContext(GET, null, null, null), Route.LIST);
    }

    @Test
    @DisplayName("GET ?action=lines still returns the lines")
    void spaGetLines() {
      assertSpaRoute(spaContext(GET, "lines", null, null), Route.LINES);
    }

    @Test
    @DisplayName("a CRUD-typed context is not diverted either")
    void crudContextNotDiverted() throws Exception {
      assertSpaRoute(spaContext(POST, "create", new JSONObject(), NeoEndpointType.CRUD),
          Route.CREATE);
    }

    @Test
    @DisplayName("an agent-only action name on the SPA route stays unknown (405)")
    void agentNameOnSpaRoute() {
      assertEquals(405, handler.handle(spaContext(POST, CREATE, new JSONObject(), null))
          .getHttpStatus());
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("an ACTION context ignores a stray ?action= query param")
    void actionContextIgnoresQueryAction() {
      Map<String, String> qp = new HashMap<>();
      qp.put("action", "create");
      NeoContext ctx = NeoContext.builder().specName(SPEC).httpMethod(POST).recordId(ACC_ID)
          .queryParams(qp).sfEntity(sfEntity).endpointType(NeoEndpointType.ACTION)
          .fieldName(LIST).build();
      assertSame(sentinels.get(Route.LIST), handler.handle(ctx));
      verify(handler, never()).handleCreate(any());
    }

    @Test
    @DisplayName("an unsupported HTTP method on the SPA route is still 405")
    void unsupportedMethod() {
      Consumer<String> check = method -> assertEquals(405,
          handler.handle(spaContext(method, null, null, null)).getHttpStatus(), method);
      check.accept("PUT");
      check.accept("DELETE");
      assertNoRouteCalled();
    }
  }
}
