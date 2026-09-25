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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.hibernate.Session;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link ReconciliationAgentActions} (ETP-5468, front A) — the {@code neo_action}
 * surface of {@code bank-reconciliation} — and the regression guarantees around it:
 *
 * <ul>
 *   <li>CA1/CA7: the nine declared contracts (names, order, mutating flag, required lists);</li>
 *   <li>CA2–CA6: every action re-enters the SAME {@code ReconciliationHandlerSupport.handle*}
 *       wrapper the SPA route uses, with {@code id} mapped to {@code financialAccountId} (writes)
 *       or {@code accountId} (reads) and the parameters passed through untouched;</li>
 *   <li>the role gate (write actions need report-spec POST access → 403 otherwise);</li>
 *   <li>CA12: the SPA's {@code ?action=} contexts (no endpoint type) never reach the new
 *       dispatcher and keep hitting their route with the very same context.</li>
 * </ul>
 *
 * <p>The support layer is mocked statically, so no DAL / OBContext is needed; one nested class
 * goes one level deeper (real {@code runPostAction}) to prove the SPA route and the agent action
 * hand the business method an identical body.</p>
 */
@SuppressWarnings("java:S2187")
@DisplayName("ReconciliationAgentActions (ETP-5468)")
class ReconciliationAgentActionsTest {

  private static final String SPEC = "bank-reconciliation";
  private static final String ACC_ID = "ACC-1";
  private static final String LINE_ID = "LINE-1";

  /** The SPA route (ReconciliationHandlerSupport.handle*) each agent action must reuse. */
  private enum Route {
    PENDING_LINES, CANDIDATES, AUTO_MATCH, RECONCILE_GROUP, RECONCILE_DIFFERENCE, REACTIVATE,
    REMOVE_OPERATION, REACTIVATE_SELECTED, APPLY_SUGGESTIONS
  }

  private static final Map<String, Route> ACTION_TO_ROUTE = Map.of(
      "pendingLines", Route.PENDING_LINES,
      "candidates", Route.CANDIDATES,
      "autoMatch", Route.AUTO_MATCH,
      "reconcileGroup", Route.RECONCILE_GROUP,
      "reconcileDifference", Route.RECONCILE_DIFFERENCE,
      "undoReconciliation", Route.REACTIVATE,
      "removeOperation", Route.REMOVE_OPERATION,
      "reactivateSelected", Route.REACTIVATE_SELECTED,
      "applySuggestions", Route.APPLY_SUGGESTIONS);

  private ReconciliationHandler handler;
  private SFSpec spec;
  private SFEntity sfEntity;

  private MockedStatic<ReconciliationHandlerSupport> support;
  private MockedStatic<NeoAccessHelper> access;
  /** ETP-5468 BUG-2: writes flush the session to clean before returning; clean by default. */
  private MockedStatic<OBDal> obDal;
  private OBDal dal;
  private Session session;

  /** One sentinel response per route, so a mis-routing is caught by identity. */
  private final Map<Route, NeoResponse> sentinels = new EnumMap<>(Route.class);
  /** The (handler, context) each route received, in call order. */
  private final Map<Route, List<Object[]>> calls = new EnumMap<>(Route.class);

  @BeforeEach
  void setUp() {
    handler = spy(new ReconciliationHandler());
    spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(SPEC);
    sfEntity = mock(SFEntity.class);
    when(sfEntity.getETGOSFSpec()).thenReturn(spec);
    when(sfEntity.getName()).thenReturn(SPEC);

    for (Route r : Route.values()) {
      sentinels.put(r, NeoResponse.error(299, r.name()));
      calls.put(r, new ArrayList<>());
    }
    support = mockStatic(ReconciliationHandlerSupport.class);
    stubRoute(Route.PENDING_LINES,
        () -> ReconciliationHandlerSupport.handlePendingLines(any(), any()));
    stubRoute(Route.CANDIDATES, () -> ReconciliationHandlerSupport.handleCandidates(any(), any()));
    stubRoute(Route.AUTO_MATCH, () -> ReconciliationHandlerSupport.handleAutoMatch(any(), any()));
    stubRoute(Route.RECONCILE_GROUP,
        () -> ReconciliationHandlerSupport.handleReconcileGroup(any(), any()));
    stubRoute(Route.RECONCILE_DIFFERENCE,
        () -> ReconciliationHandlerSupport.handleReconcileDifference(any(), any()));
    stubRoute(Route.REACTIVATE, () -> ReconciliationHandlerSupport.handleReactivate(any(), any()));
    stubRoute(Route.REMOVE_OPERATION,
        () -> ReconciliationHandlerSupport.handleRemoveOperation(any(), any()));
    stubRoute(Route.REACTIVATE_SELECTED,
        () -> ReconciliationHandlerSupport.handleReactivateSelected(any(), any()));
    stubRoute(Route.APPLY_SUGGESTIONS,
        () -> ReconciliationHandlerSupport.handleApplySuggestions(any(), any()));

    access = mockStatic(NeoAccessHelper.class);
    access.when(() -> NeoAccessHelper.hasReportSpecAccess(any(), anyString())).thenReturn(true);

    dal = mock(OBDal.class);
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    when(session.isDirty()).thenReturn(false);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    Mockito.doNothing().when(handler).doRollbackAndClose();
  }

  @AfterEach
  void tearDown() {
    support.close();
    access.close();
    obDal.close();
    Mockito.framework().clearInlineMocks();
  }

  private void stubRoute(Route route, MockedStatic.Verification call) {
    support.when(call).thenAnswer(inv -> {
      calls.get(route).add(new Object[] { inv.getArgument(0), inv.getArgument(1) });
      return sentinels.get(route);
    });
  }

  private NeoContext actionContext(String action, String recordId, JSONObject params) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName(SPEC)
        .httpMethod("POST")
        .recordId(recordId)
        .requestBody(params)
        .sfEntity(sfEntity)
        .mcpOrigin(true)
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(action)
        .build();
  }

  private NeoContext spaContext(String method, String action, JSONObject body,
      NeoEndpointType type) {
    Map<String, String> qp = new HashMap<>();
    qp.put("action", action);
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

  /** @return the single context the route received (asserting it was called exactly once). */
  private NeoContext onlyCallOf(Route route) {
    List<Object[]> received = calls.get(route);
    assertEquals(1, received.size(), route + " must be called exactly once");
    assertSame(handler, received.get(0)[0], "the SAME handler instance is passed through");
    return (NeoContext) received.get(0)[1];
  }

  private void assertNoRouteCalled() {
    for (Route r : Route.values()) {
      assertTrue(calls.get(r).isEmpty(), r + " must not run");
    }
  }

  private static JSONObject paramsFor(String action) throws Exception {
    switch (action) {
      case "reconcileGroup":
        return new JSONObject().put("statementLineId", LINE_ID)
            .put("operationIds", new JSONArray().put("T1").put("T2"))
            .put("invoices", new JSONArray().put(
                new JSONObject().put("invoiceId", "I1").put("scheduleId", "S1")))
            .put("writeoffDifference", false)
            .put("glItemId", "GL-1")
            .put("description", "desc");
      case "reconcileDifference":
      case "undoReconciliation":
        return new JSONObject().put("statementLineId", LINE_ID);
      case "removeOperation":
      case "reactivateSelected":
        return new JSONObject().put("statementLineId", LINE_ID)
            .put("transactionIds", new JSONArray().put("T1"));
      case "applySuggestions":
        return new JSONObject().put("groups", new JSONArray().put(
            new JSONObject().put("statementLineId", LINE_ID)
                .put("operationIds", new JSONArray().put("T1"))));
      default:
        return new JSONObject();
    }
  }

  private static List<String> strings(JSONArray arr) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      out.add(arr.getString(i));
    }
    return out;
  }

  // ── CA1 / CA7: the declaration ─────────────────────────────────────────

  @Nested
  @DisplayName("declared contracts")
  class Contracts {

    @Test
    @DisplayName("nine actions, reads first then writes, in presentation order")
    void nineActionsInOrder() {
      assertEquals(List.of("pendingLines", "candidates", "autoMatch", "reconcileGroup",
          "reconcileDifference", "undoReconciliation", "removeOperation", "reactivateSelected",
          "applySuggestions"), new ArrayList<>(ReconciliationAgentActions.CONTRACTS.keySet()));
    }

    @Test
    @DisplayName("only pendingLines / candidates / autoMatch are read-only")
    void mutatingFlags() {
      ReconciliationAgentActions.CONTRACTS.forEach((name, c) -> assertEquals(
          !List.of("pendingLines", "candidates", "autoMatch").contains(name), c.isMutating(),
          name));
    }

    @Test
    @DisplayName("each contract renders the expected required list")
    void requiredLists() throws Exception {
      Map<String, List<String>> expected = new HashMap<>();
      expected.put("pendingLines", List.of());
      expected.put("candidates", List.of("statementLineId"));
      expected.put("autoMatch", List.of());
      expected.put("reconcileGroup", List.of("statementLineId"));
      expected.put("reconcileDifference", List.of("statementLineId"));
      expected.put("undoReconciliation", List.of("statementLineId"));
      expected.put("removeOperation", List.of("statementLineId", "transactionIds"));
      expected.put("reactivateSelected", List.of("statementLineId", "transactionIds"));
      expected.put("applySuggestions", List.of("groups"));
      for (Map.Entry<String, NeoActionContract> e : ReconciliationAgentActions.CONTRACTS
          .entrySet()) {
        JSONObject schema = e.getValue().toJson().getJSONObject("parameters");
        assertEquals(expected.get(e.getKey()), strings(schema.getJSONArray("required")),
            e.getKey());
        assertFalse(schema.getBoolean("additionalProperties"), e.getKey());
      }
    }

    @Test
    @DisplayName("every action (read and write groups alike) says id = the financial account id")
    void everyActionCarriesIdDescription() throws Exception {
      for (NeoActionContract c : ReconciliationAgentActions.CONTRACTS.values()) {
        assertNotNull(c.getIdDescription(), c.getName());
        assertTrue(c.getIdDescription().toLowerCase().contains("financial account id"),
            c.getName());
        assertEquals(c.getIdDescription(), c.toJson().getString("idDescription"), c.getName());
      }
    }

    @Test
    @DisplayName("the declaration is immutable")
    void contractsAreUnmodifiable() {
      org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
          () -> ReconciliationAgentActions.CONTRACTS.remove("autoMatch"));
    }

    @Test
    @DisplayName("reconcileDifference accepts glItemId/description but NO amount")
    void reconcileDifferenceHasNoAmount() throws Exception {
      JSONObject props = ReconciliationAgentActions.CONTRACTS.get("reconcileDifference").toJson()
          .getJSONObject("parameters").getJSONObject("properties");
      assertTrue(props.has("glItemId"));
      assertTrue(props.has("description"));
      assertFalse(props.has("amount"));
      assertEquals(3, props.length());
    }

    @Test
    @DisplayName("the handler publishes exactly these contracts and therefore serves actions")
    void handlerPublishesContracts() {
      assertSame(ReconciliationAgentActions.CONTRACTS, new ReconciliationHandler().actionContracts());
      assertTrue(new ReconciliationHandler().servesActions());
    }

    @Test
    @DisplayName("CA12: a handler without actionContracts() keeps servesActions() == false")
    void defaultHandlerDoesNotServeActions() {
      NeoHandler plain = context -> null;
      assertTrue(plain.actionContracts().isEmpty());
      assertFalse(plain.servesActions());
    }
  }

  // ── CA2–CA6: dispatch of the writes ────────────────────────────────────

  @Nested
  @DisplayName("write actions")
  class Writes {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "reconcileGroup", "reconcileDifference", "undoReconciliation",
        "removeOperation", "reactivateSelected", "applySuggestions" })
    @DisplayName("delegate to the SPA's POST wrapper with id → financialAccountId")
    void delegatesToSpaPostRoute(String action) throws Exception {
      JSONObject params = paramsFor(action);
      String paramsBefore = params.toString();
      Route route = ACTION_TO_ROUTE.get(action);

      NeoResponse response = handler.handle(actionContext(action, ACC_ID, params));

      assertSame(sentinels.get(route), response, "the SPA route's own response is returned");
      NeoContext derived = onlyCallOf(route);
      assertEquals("POST", derived.getHttpMethod());
      assertNull(derived.getEndpointType(),
          "derived context looks like the SPA request (no endpoint type)");
      assertEquals(SPEC, derived.getSpecName());
      assertSame(sfEntity, derived.getSfEntity());
      assertTrue(derived.isMcpOrigin());
      assertTrue(derived.getQueryParams().isEmpty());

      JSONObject body = derived.getRequestBody();
      assertEquals(ACC_ID, body.getString("financialAccountId"));
      assertEquals(params.length() + 1, body.length(), "every parameter passes through");
      for (java.util.Iterator<?> it = params.keys(); it.hasNext();) {
        String key = String.valueOf(it.next());
        assertEquals(params.get(key).toString(), body.get(key).toString(), key);
      }
      assertNotSame(params, body, "the caller's parameters are copied");
      assertEquals(paramsBefore, params.toString(), "the caller's parameters are not mutated");

      for (Route other : Route.values()) {
        if (other != route) {
          assertTrue(calls.get(other).isEmpty(), other + " must not run for " + action);
        }
      }
    }

    @Test
    @DisplayName("id is trimmed before it becomes financialAccountId")
    void idIsTrimmed() throws Exception {
      handler.handle(actionContext("undoReconciliation", "  " + ACC_ID + " ",
          paramsFor("undoReconciliation")));
      assertEquals(ACC_ID,
          onlyCallOf(Route.REACTIVATE).getRequestBody().getString("financialAccountId"));
    }

    @Test
    @DisplayName("a financialAccountId smuggled in the parameters is refused (undeclared)")
    void financialAccountIdParamRefused() throws Exception {
      JSONObject params = paramsFor("undoReconciliation").put("financialAccountId", "OTHER");
      NeoResponse response = handler.handle(actionContext("undoReconciliation", ACC_ID, params));
      assertEquals(422, response.getHttpStatus());
      assertEquals(List.of("financialAccountId"), strings(response.getBody()
          .getJSONObject("error").getJSONArray("unknownParameters")));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("reconcileDifference WITH glItemId passes it through")
    void reconcileDifferenceWithGlItem() throws Exception {
      JSONObject params = paramsFor("reconcileDifference").put("glItemId", "GL-9")
          .put("description", "rounding");
      handler.handle(actionContext("reconcileDifference", ACC_ID, params));
      JSONObject body = onlyCallOf(Route.RECONCILE_DIFFERENCE).getRequestBody();
      assertEquals("GL-9", body.getString("glItemId"));
      assertEquals("rounding", body.getString("description"));
      assertEquals(LINE_ID, body.getString("statementLineId"));
    }

    @Test
    @DisplayName("reconcileDifference WITHOUT glItemId sends none and returns GL_ITEM_REQUIRED as-is")
    void reconcileDifferenceWithoutGlItemPropagatesGlItemRequired() throws Exception {
      JSONObject payload = new JSONObject()
          .put("error", new JSONObject().put("message", "pick a GL item").put("status", 400))
          .put("code", "GL_ITEM_REQUIRED")
          .put("differenceAmount", "0.02");
      NeoResponse glItemRequired = NeoResponse.error(400, payload);
      sentinels.put(Route.RECONCILE_DIFFERENCE, glItemRequired);

      NeoResponse response = handler.handle(
          actionContext("reconcileDifference", ACC_ID, paramsFor("reconcileDifference")));

      assertSame(glItemRequired, response);
      assertEquals("GL_ITEM_REQUIRED", response.getBody().getString("code"));
      assertFalse(onlyCallOf(Route.RECONCILE_DIFFERENCE).getRequestBody().has("glItemId"));
    }

    @Test
    @DisplayName("applySuggestions sends only the accepted subset of groups, verbatim")
    void applySuggestionsSubset() throws Exception {
      JSONArray accepted = new JSONArray()
          .put(new JSONObject().put("statementLineId", "L1")
              .put("operationIds", new JSONArray().put("T1")))
          .put(new JSONObject().put("statementLineId", "L3")
              .put("operationIds", new JSONArray()).put("createPayment", new JSONObject()));
      handler.handle(actionContext("applySuggestions", ACC_ID,
          new JSONObject().put("groups", accepted)));
      JSONArray groups = onlyCallOf(Route.APPLY_SUGGESTIONS).getRequestBody()
          .getJSONArray("groups");
      assertEquals(2, groups.length());
      assertEquals(accepted.toString(), groups.toString());
    }

    @Test
    @DisplayName("applySuggestions with an empty groups list is refused before running")
    void applySuggestionsEmptyRefused() throws Exception {
      NeoResponse response = handler.handle(actionContext("applySuggestions", ACC_ID,
          new JSONObject().put("groups", new JSONArray())));
      assertEquals(422, response.getHttpStatus());
      assertEquals(List.of("groups"), strings(response.getBody().getJSONObject("error")
          .getJSONArray("missingParameters")));
      assertNoRouteCalled();
    }
  }

  // ── reads ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("read actions")
  class Reads {

    @Test
    @DisplayName("pendingLines → GET handlePendingLines with accountId + filters as query params")
    void pendingLines() throws Exception {
      JSONObject params = new JSONObject().put("dateFrom", "2026-01-01")
          .put("dateTo", "2026-01-31").put("q", "rent");
      NeoResponse response = handler.handle(actionContext("pendingLines", ACC_ID, params));

      assertSame(sentinels.get(Route.PENDING_LINES), response);
      NeoContext derived = onlyCallOf(Route.PENDING_LINES);
      assertEquals("GET", derived.getHttpMethod());
      assertNull(derived.getRequestBody());
      Map<String, String> qp = derived.getQueryParams();
      assertEquals(ACC_ID, qp.get("accountId"));
      assertEquals("2026-01-01", qp.get("dateFrom"));
      assertEquals("2026-01-31", qp.get("dateTo"));
      assertEquals("rent", qp.get("q"));
      assertEquals(4, qp.size());
      assertFalse(qp.containsKey("action"), "no ?action= is forged into the derived context");
    }

    @Test
    @DisplayName("pendingLines without parameters (null body) still sends accountId")
    void pendingLinesNoParams() {
      handler.handle(actionContext("pendingLines", ACC_ID, null));
      Map<String, String> qp = onlyCallOf(Route.PENDING_LINES).getQueryParams();
      assertEquals(Map.of("accountId", ACC_ID), qp);
    }

    @Test
    @DisplayName("candidates maps statementLineId → the SPA's lineId query param")
    void candidatesAliasesLineId() throws Exception {
      JSONObject params = new JSONObject().put("statementLineId", LINE_ID)
          .put("kind", "invoices").put("docType", "payments");
      handler.handle(actionContext("candidates", ACC_ID, params));
      Map<String, String> qp = onlyCallOf(Route.CANDIDATES).getQueryParams();
      assertEquals(LINE_ID, qp.get("lineId"));
      assertFalse(qp.containsKey("statementLineId"));
      assertEquals("invoices", qp.get("kind"));
      assertEquals("payments", qp.get("docType"));
      assertEquals(ACC_ID, qp.get("accountId"));
    }

    @Test
    @DisplayName("an explicit null optional parameter is not forwarded")
    void nullParamNotForwarded() throws Exception {
      handler.handle(actionContext("pendingLines", ACC_ID,
          new JSONObject().put("q", JSONObject.NULL)));
      assertFalse(onlyCallOf(Route.PENDING_LINES).getQueryParams().containsKey("q"));
    }

    @Test
    @DisplayName("autoMatch is read-only: GET preview only, no write route runs")
    void autoMatchIsReadOnly() {
      NeoResponse response = handler.handle(actionContext("autoMatch", ACC_ID, null));
      assertSame(sentinels.get(Route.AUTO_MATCH), response);
      NeoContext derived = onlyCallOf(Route.AUTO_MATCH);
      assertEquals("GET", derived.getHttpMethod());
      assertEquals(Map.of("accountId", ACC_ID), derived.getQueryParams());
      assertTrue(calls.get(Route.APPLY_SUGGESTIONS).isEmpty());
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, "GET"));
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(any(), eq("POST")), never());
    }

    @Test
    @DisplayName("autoMatch refuses any parameter (it declares none)")
    void autoMatchRefusesParams() throws Exception {
      NeoResponse response = handler.handle(actionContext("autoMatch", ACC_ID,
          new JSONObject().put("apply", true)));
      assertEquals(422, response.getHttpStatus());
      assertNoRouteCalled();
    }
  }

  // ── refusals before anything runs ──────────────────────────────────────

  @Nested
  @DisplayName("refusals")
  class Refusals {

    @Test
    @DisplayName("unknown action → 422 availableActions (the 9 names)")
    void unknownAction() throws Exception {
      NeoResponse response = handler.handle(actionContext("matchStatement", ACC_ID, null));
      assertEquals(422, response.getHttpStatus());
      JSONArray available = response.getBody().getJSONObject("error")
          .getJSONArray("availableActions");
      assertEquals(new ArrayList<>(ReconciliationAgentActions.CONTRACTS.keySet()),
          strings(available));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("the SPA's own action name 'reactivate' is not an agent action")
    void spaNameIsNotAnAgentAction() {
      NeoResponse response = handler.handle(actionContext("reactivate", ACC_ID,
          new JSONObject()));
      assertEquals(422, response.getHttpStatus());
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("a missing required parameter → 422 missingParameters")
    void missingParameter() throws Exception {
      NeoResponse response = handler.handle(actionContext("removeOperation", ACC_ID,
          new JSONObject().put("statementLineId", LINE_ID)));
      assertEquals(422, response.getHttpStatus());
      assertEquals(List.of("transactionIds"), strings(response.getBody()
          .getJSONObject("error").getJSONArray("missingParameters")));
      assertNoRouteCalled();
    }

    @ParameterizedTest(name = "id=''{0}''")
    @ValueSource(strings = { "", "   " })
    @DisplayName("a blank id → 422 naming the financial account id")
    void blankId(String id) throws Exception {
      NeoResponse response = handler.handle(actionContext("autoMatch", id, null));
      assertEquals(422, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .contains("financial account id"));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("a null id → 422")
    void nullId() {
      NeoResponse response = handler.handle(actionContext("undoReconciliation", null,
          new JSONObject()));
      // validation runs first: statementLineId is missing
      assertEquals(422, response.getHttpStatus());
      NeoResponse noId = handler.handle(actionContext("autoMatch", null, null));
      assertEquals(422, noId.getHttpStatus());
      assertNoRouteCalled();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "reconcileGroup", "reconcileDifference", "undoReconciliation",
        "removeOperation", "reactivateSelected", "applySuggestions" })
    @DisplayName("a role without report-spec POST access → 403 on every write action")
    void writeWithoutPostAccessIs403(String action) throws Exception {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, "POST")).thenReturn(false);
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, "GET")).thenReturn(true);

      NeoResponse response = handler.handle(actionContext(action, ACC_ID, paramsFor(action)));

      assertEquals(403, response.getHttpStatus());
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("the same read-only role can still run the read actions")
    void readOnlyRoleCanRead() {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, "POST")).thenReturn(false);
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, "GET")).thenReturn(true);
      assertSame(sentinels.get(Route.AUTO_MATCH),
          handler.handle(actionContext("autoMatch", ACC_ID, null)));
    }

    @Test
    @DisplayName("fails closed (403) when the context carries no spec")
    void noSpecFailsClosed() {
      NeoContext ctx = NeoContext.builder().specName(SPEC).httpMethod("POST").recordId(ACC_ID)
          .endpointType(NeoEndpointType.ACTION).fieldName("autoMatch").build();
      assertEquals(403, handler.handle(ctx).getHttpStatus());
      assertNoRouteCalled();
    }
  }

  // ── CA12: the SPA routes are untouched ─────────────────────────────────

  @Nested
  @DisplayName("CA12 — SPA ?action= routing is unchanged")
  class SpaRegression {

    @Test
    @DisplayName("GET ?action=pendingLines (no endpoint type) reaches its route with the SAME context")
    void spaGetUsesSameContext() {
      NeoContext ctx = spaContext("GET", "pendingLines", null, null);
      assertSame(sentinels.get(Route.PENDING_LINES), handler.handle(ctx));
      assertSame(ctx, onlyCallOf(Route.PENDING_LINES), "not diverted, not re-derived");
      access.verifyNoInteractions();
    }

    @ParameterizedTest(name = "POST ?action={0}")
    @ValueSource(strings = { "reconcileGroup", "reconcileDifference", "reactivate",
        "removeOperation", "reactivateSelected", "applySuggestions" })
    @DisplayName("every SPA POST route still gets the original context and body")
    void spaPostRoutes(String spaAction) throws Exception {
      JSONObject body = new JSONObject().put("financialAccountId", ACC_ID)
          .put("statementLineId", LINE_ID).put("anythingTheSpaSends", "kept");
      Route route = "reactivate".equals(spaAction) ? Route.REACTIVATE
          : ACTION_TO_ROUTE.get(spaAction);
      NeoContext ctx = spaContext("POST", spaAction, body, null);

      assertSame(sentinels.get(route), handler.handle(ctx));
      NeoContext received = onlyCallOf(route);
      assertSame(ctx, received);
      assertSame(body, received.getRequestBody(),
          "the SPA body is not validated against the agent contract");
      access.verifyNoInteractions();
    }

    @Test
    @DisplayName("GET ?action=candidates / autoMatch still route with the same context")
    void spaOtherGets() {
      NeoContext candidates = spaContext("GET", "candidates", null, null);
      handler.handle(candidates);
      assertSame(candidates, onlyCallOf(Route.CANDIDATES));
      NeoContext auto = spaContext("GET", "autoMatch", null, null);
      handler.handle(auto);
      assertSame(auto, onlyCallOf(Route.AUTO_MATCH));
    }

    @Test
    @DisplayName("an agent-only action name on the SPA route stays an unknown route (null)")
    void agentNameOnSpaRouteFallsThrough() {
      assertNull(handler.handle(spaContext("POST", "undoReconciliation", new JSONObject(), null)));
      assertNoRouteCalled();
    }

    @Test
    @DisplayName("a CRUD-typed context is not diverted either")
    void crudContextNotDiverted() {
      NeoContext ctx = spaContext("GET", "autoMatch", null, NeoEndpointType.CRUD);
      handler.handle(ctx);
      assertSame(ctx, onlyCallOf(Route.AUTO_MATCH));
    }

    @Test
    @DisplayName("an ACTION context ignores a stray ?action= query param")
    void actionContextIgnoresQueryAction() {
      Map<String, String> qp = new HashMap<>();
      qp.put("action", "reconcileGroup");
      NeoContext ctx = NeoContext.builder().specName(SPEC).httpMethod("POST").recordId(ACC_ID)
          .queryParams(qp).sfEntity(sfEntity).endpointType(NeoEndpointType.ACTION)
          .fieldName("autoMatch").build();
      assertSame(sentinels.get(Route.AUTO_MATCH), handler.handle(ctx));
      assertTrue(calls.get(Route.RECONCILE_GROUP).isEmpty());
    }

    @Test
    @DisplayName("the mocked mock-context contract of the legacy tests still holds (no endpoint type)")
    void mockContextWithoutEndpointType() {
      NeoContext ctx = mock(NeoContext.class);
      when(ctx.getHttpMethod()).thenReturn("GET");
      when(ctx.getQueryParams()).thenReturn(Map.of("action", "somethingElse"));
      assertNull(handler.handle(ctx));
      assertNoRouteCalled();
    }
  }

  // ── ETP-5468 BUG-2: writes flush to clean while the OBContext is still set ─

  @Nested
  @DisplayName("write results are flushed to a clean session before returning")
  class FlushWhileContextIsSet {

    private NeoResponse runUndo() throws Exception {
      return handler.handle(actionContext("undoReconciliation", ACC_ID,
          paramsFor("undoReconciliation")));
    }

    @Test
    @DisplayName("dirty after the first flush → flushes again until clean, then the success JSON")
    void flushesUntilClean() throws Exception {
      NeoResponse ok = NeoResponse.ok(new JSONObject().put("reactivated", true));
      sentinels.put(Route.REACTIVATE, ok);
      when(session.isDirty()).thenReturn(true, true, false);

      NeoResponse response = runUndo();

      assertSame(ok, response);
      Mockito.verify(dal, Mockito.times(2)).flush();
      Mockito.verify(handler, never()).doRollbackAndClose();
    }

    @Test
    @DisplayName("an already clean session is not flushed")
    void cleanSessionNotFlushed() throws Exception {
      NeoResponse ok = NeoResponse.ok(new JSONObject());
      sentinels.put(Route.REACTIVATE, ok);
      assertSame(ok, runUndo());
      Mockito.verify(dal, never()).flush();
    }

    @Test
    @DisplayName("a failing flush → rollback + JSON 500 naming the cause")
    void flushFailureRollsBack() throws Exception {
      sentinels.put(Route.REACTIVATE, NeoResponse.ok(new JSONObject().put("reactivated", true)));
      when(session.isDirty()).thenReturn(true);
      Mockito.doThrow(new IllegalStateException("constraint violated")).when(dal).flush();

      NeoResponse response = runUndo();

      assertEquals(500, response.getHttpStatus());
      assertEquals("The reconciliation changes could not be saved and were rolled back: "
          + "constraint violated",
          response.getBody().getJSONObject("error").getString("message"));
      Mockito.verify(handler).doRollbackAndClose();
    }

    @Test
    @DisplayName("a flush failure without a message names the exception class")
    void flushFailureWithoutMessage() throws Exception {
      sentinels.put(Route.REACTIVATE, NeoResponse.ok(new JSONObject()));
      when(session.isDirty()).thenReturn(true);
      Mockito.doThrow(new NullPointerException()).when(dal).flush();

      NeoResponse response = runUndo();

      assertEquals(500, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject("error").getString("message")
          .endsWith("rolled back: NullPointerException"));
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = { 400, 409, 500 })
    @DisplayName("an error response passes through untouched, with no flush and no extra rollback")
    void errorResponsePassesThrough(int status) throws Exception {
      NeoResponse error = NeoResponse.error(status, "refused");
      sentinels.put(Route.REACTIVATE, error);
      when(session.isDirty()).thenReturn(true);

      assertSame(error, runUndo());
      Mockito.verify(dal, never()).flush();
      Mockito.verify(handler, never()).doRollbackAndClose();
    }

    @Test
    @DisplayName("a null write result passes through untouched")
    void nullResultPassesThrough() throws Exception {
      sentinels.put(Route.REACTIVATE, null);
      assertNull(runUndo());
      obDal.verify(OBDal::getInstance, never());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "pendingLines", "candidates", "autoMatch" })
    @DisplayName("read actions never reach the flush helper")
    void readsSkipFlush(String action) throws Exception {
      JSONObject params = "candidates".equals(action)
          ? new JSONObject().put("statementLineId", LINE_ID) : new JSONObject();
      when(session.isDirty()).thenReturn(true);

      handler.handle(actionContext(action, ACC_ID, params));

      obDal.verify(OBDal::getInstance, never());
      Mockito.verify(dal, never()).flush();
    }

    @Test
    @DisplayName("a session that never gets clean stops at the 100-flush cap and still answers")
    void stopsAtCap() throws Exception {
      NeoResponse ok = NeoResponse.ok(new JSONObject());
      sentinels.put(Route.REACTIVATE, ok);
      when(session.isDirty()).thenReturn(true);

      assertSame(ok, runUndo());
      Mockito.verify(dal, Mockito.times(100)).flush();
      Mockito.verify(handler, never()).doRollbackAndClose();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "reconcileGroup", "reconcileDifference", "removeOperation",
        "reactivateSelected", "applySuggestions" })
    @DisplayName("every write action goes through the flush")
    void everyWriteFlushes(String action) throws Exception {
      Route route = ACTION_TO_ROUTE.get(action);
      NeoResponse ok = NeoResponse.ok(new JSONObject());
      sentinels.put(route, ok);
      when(session.isDirty()).thenReturn(true, false);

      assertSame(ok, handler.handle(actionContext(action, ACC_ID, paramsFor(action))));
      Mockito.verify(dal).flush();
    }
  }

  // ── one level deeper: identical body for the business method ──────────

  @Nested
  @DisplayName("SPA route and agent action feed the business method the same body")
  class EndToEndEquivalence {

    @Test
    @DisplayName("reconcileDifference: SPA body == agent-derived body at ReconciliationDifferenceSupport")
    void reconcileDifferenceSameBody() throws Exception {
      // Let the real wrapper run for this test only.
      support.close();
      support = mockStatic(ReconciliationHandlerSupport.class, Mockito.CALLS_REAL_METHODS);
      Mockito.doNothing().when(handler).doRollbackAndClose();

      AtomicReference<JSONObject> seen = new AtomicReference<>();
      List<String> bodies = new ArrayList<>();
      NeoResponse ok = NeoResponse.ok(new JSONObject().put("reconciled", true));
      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
          MockedStatic<ReconciliationDifferenceSupport> diff =
              mockStatic(ReconciliationDifferenceSupport.class)) {
        diff.when(() -> ReconciliationDifferenceSupport.reconcileDifference(eq(handler), any()))
            .thenAnswer(inv -> {
              seen.set(inv.getArgument(1));
              bodies.add(seen.get().toString());
              return ok;
            });

        JSONObject spaBody = new JSONObject().put("statementLineId", LINE_ID)
            .put("glItemId", "GL-1").put("financialAccountId", ACC_ID);
        NeoResponse viaSpa = handler.handle(spaContext("POST", "reconcileDifference", spaBody,
            null));
        NeoResponse viaAgent = handler.handle(actionContext("reconcileDifference", ACC_ID,
            new JSONObject().put("statementLineId", LINE_ID).put("glItemId", "GL-1")));

        assertSame(ok, viaSpa);
        assertSame(ok, viaAgent);
      }
      assertEquals(2, bodies.size());
      JSONObject spaSeen = new JSONObject(bodies.get(0));
      JSONObject agentSeen = new JSONObject(bodies.get(1));
      assertEquals(spaSeen.length(), agentSeen.length());
      for (String key : List.of("statementLineId", "glItemId", "financialAccountId")) {
        assertEquals(spaSeen.getString(key), agentSeen.getString(key), key);
      }
      assertNotNull(seen.get());
    }

    @Test
    @DisplayName("undoReconciliation: a guard refusal comes back as the SPA's 400 + rollback")
    void undoRefusalIs400() throws Exception {
      support.close();
      support = mockStatic(ReconciliationHandlerSupport.class, Mockito.CALLS_REAL_METHODS);
      Mockito.doNothing().when(handler).doRollbackAndClose();
      Mockito.doThrow(new org.openbravo.base.exception.OBException(
          ReconciliationDraftGuard.foreignDraftMessage("REC-7")))
          .when(handler).reactivate(any());

      NeoResponse response;
      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
        response = handler.handle(actionContext("undoReconciliation", ACC_ID,
            new JSONObject().put("statementLineId", LINE_ID)));
      }
      assertEquals(400, response.getHttpStatus());
      assertEquals(ReconciliationDraftGuard.foreignDraftMessage("REC-7"),
          response.getBody().getJSONObject("error").getString("message"));
      Mockito.verify(handler).doRollbackAndClose();
      Mockito.verify(handler).reactivate(Mockito.argThat(b -> ACC_ID.equals(
          b.optString("financialAccountId")) && LINE_ID.equals(b.optString("statementLineId"))));
    }
  }
}
