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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * Unit tests for {@link McpActionBatch}, the {@code etendo_action_batch} tool (ETP-5715): the
 * per-document results and summary, the one-transaction-per-document discipline, the validation
 * of the call as a whole, and the guarantee that a failing document never aborts the others.
 *
 * <p>The single-document path ({@code McpToolRouter#handleAction}) is replaced by a scripted
 * {@link McpActionBatch.ActionRunner}; what is asserted here is only what this class owns.</p>
 *
 * @covers com.etendoerp.go.mcp.McpActionBatch
 */
class McpActionBatchTest {

  private static final String SPEC = "purchase-order";
  private static final String ENTITY = "header";
  private static final String ACTION = "documentAction";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<McpToolRouterSupport> supportMock;
  private MockedStatic<McpDeclaredActions> declaredMock;
  private MockedStatic<NeoButtonActionHelper> buttonMock;
  private OBDal dal;
  private OBContext context;
  private final List<String> events = new ArrayList<>();

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    context = mock(OBContext.class);
    when(context.isInAdministratorMode()).thenReturn(true);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(context);
    doAnswer(inv -> events.add("commit")).when(dal).commitAndClose();
    doAnswer(inv -> events.add("rollback")).when(dal).rollbackAndClose();

    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("entity-1");
    when(entity.getName()).thenReturn(ENTITY);
    supportMock = mockStatic(McpToolRouterSupport.class, CALLS_REAL_METHODS);
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString())).thenReturn(spec);
    supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
        .thenReturn(entity);
    declaredMock = mockStatic(McpDeclaredActions.class);
    declaredMock.when(() -> McpDeclaredActions.precheck(any(), anyString(), any()))
        .thenReturn(null);
    buttonMock = mockStatic(NeoButtonActionHelper.class);
    buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
        .thenReturn(mock(Column.class));
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
    supportMock.close();
    declaredMock.close();
    buttonMock.close();
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private static JSONObject call(String... ids) throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC);
    args.put("entity", ENTITY);
    args.put("action", ACTION);
    args.put("ids", new JSONArray(List.of(ids)));
    args.put("parameters", new JSONObject().put("docAction", "CO"));
    return args;
  }

  private static JSONObject ok(String message) throws Exception {
    return McpToolRouter.wrapAsTextContent(
        new JSONObject().put("processResult", "success").put("processMessage", message));
  }

  private static JSONObject failed(String message) throws Exception {
    return McpToolRouter.wrapAsErrorContent(
        new JSONObject().put("processResult", "error").put("processMessage", message));
  }

  private McpActionBatch.ActionRunner recording(McpActionBatch.ActionRunner delegate) {
    return (spec, args) -> {
      events.add("run:" + args.getString("id"));
      return delegate.run(spec, args);
    };
  }

  private static JSONObject body(JSONObject content) throws Exception {
    return new JSONObject(content.getJSONArray("content").getJSONObject(0).getString("text"));
  }

  private JSONObject run(JSONObject args, McpActionBatch.ActionRunner runner) throws Exception {
    return McpActionBatch.handle(SPEC, args, recording(runner));
  }

  private static void assertRefused(JSONObject content, String field) throws Exception {
    assertTrue(content.getBoolean("isError"), content.toString());
    JSONObject refusal = body(content);
    assertEquals(422, refusal.getInt("status"));
    assertEquals("validation_error", refusal.getString("error"));
    assertEquals(field, refusal.getString("field"));
    assertTrue(refusal.getString("detail").endsWith("Nothing was run."), refusal.toString());
  }

  // ── results ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("three documents succeed: one result each, in order, one commit each")
  void allSucceed() throws Exception {
    JSONObject content = run(call("a", "b", "c"), (spec, args) -> ok("done " + args.getString("id")));

    assertFalse(content.has("isError"));
    JSONObject answer = body(content);
    assertEquals(3, answer.getJSONObject("summary").getInt("total"));
    assertEquals(3, answer.getJSONObject("summary").getInt("succeeded"));
    assertEquals(0, answer.getJSONObject("summary").getInt("failed"));
    assertFalse(answer.getBoolean("atomic"));
    assertFalse(answer.has("hint"));
    JSONArray results = answer.getJSONArray("results");
    for (int i = 0; i < 3; i++) {
      JSONObject result = results.getJSONObject(i);
      assertEquals(List.of("a", "b", "c").get(i), result.getString("id"));
      assertTrue(result.getBoolean("ok"));
      assertEquals("success", result.getString("status"));
      assertEquals("done " + result.getString("id"),
          result.getJSONObject("result").getString("processMessage"));
    }
    assertEquals(List.of("run:a", "commit", "run:b", "commit", "run:c", "commit"), events);
  }

  @Test
  @DisplayName("one fails among three: the others still run and every outcome is reported")
  void oneFailsAmongThree() throws Exception {
    JSONObject content = run(call("a", "b", "c"), (spec, args) ->
        "b".equals(args.getString("id")) ? failed("already completed") : ok("done"));

    assertFalse(content.has("isError"), "a partial failure is still a 200 with per-document results");
    JSONObject answer = body(content);
    assertEquals(2, answer.getJSONObject("summary").getInt("succeeded"));
    assertEquals(1, answer.getJSONObject("summary").getInt("failed"));
    assertTrue(answer.has("hint"));
    JSONArray results = answer.getJSONArray("results");
    assertTrue(results.getJSONObject(0).getBoolean("ok"));
    JSONObject bad = results.getJSONObject(1);
    assertFalse(bad.getBoolean("ok"));
    assertEquals("already completed", bad.getJSONObject("error").getString("processMessage"));
    assertFalse(bad.has("result"));
    assertTrue(results.getJSONObject(2).getBoolean("ok"));
    assertEquals(List.of("run:a", "commit", "run:b", "rollback", "run:c", "commit"), events);
  }

  @Test
  @DisplayName("an exception escaping one document does not abort the batch; the next starts clean")
  void escapingExceptionDoesNotAbortTheBatch() throws Exception {
    JSONObject content = run(call("a", "b", "c"), (spec, args) -> {
      if ("b".equals(args.getString("id"))) {
        throw new IllegalStateException("boom");
      }
      return ok("done");
    });

    JSONArray results = body(content).getJSONArray("results");
    JSONObject bad = results.getJSONObject(1);
    assertFalse(bad.getBoolean("ok"));
    assertEquals(500, bad.getJSONObject("error").getInt("status"));
    assertEquals("server_error", bad.getJSONObject("error").getString("error"));
    assertTrue(results.getJSONObject(2).getBoolean("ok"));
    // the session is rolled back and closed BEFORE the next document runs
    // ETP-5782: classifying an unexpected failure rolls back too; on the already-closed session the
    // second rollback is a no-op, and the batch's own one still runs BEFORE the next document.
    assertEquals(List.of("run:a", "commit", "run:b", "rollback", "rollback", "run:c", "commit"),
        events);
  }

  @Test
  @DisplayName("a routing refusal of one document keeps its own envelope")
  void routingRefusalKeepsItsEnvelope() throws Exception {
    JSONObject content = run(call("a", "ghost"), (spec, args) -> {
      if ("ghost".equals(args.getString("id"))) {
        throw McpRoutingException.recordNotFound(SPEC, ENTITY, "ghost");
      }
      return ok("done");
    });

    JSONArray results = body(content).getJSONArray("results");
    assertTrue(results.getJSONObject(0).getBoolean("ok"));
    JSONObject error = results.getJSONObject(1).getJSONObject("error");
    assertEquals(404, error.getInt("status"));
    assertEquals(McpConstants.TOOL_NEO_ACTION_BATCH, error.getString("tool"));
  }

  @Test
  @DisplayName("a role refusal of one document is a 403, not a server error, and the rest run")
  void securityExceptionIsForbidden() throws Exception {
    JSONObject content = run(call("a", "b"), (spec, args) -> {
      if ("a".equals(args.getString("id"))) {
        throw new SecurityException("no access");
      }
      return ok("done");
    });

    JSONArray results = body(content).getJSONArray("results");
    assertEquals(403, results.getJSONObject(0).getJSONObject("error").getInt("status"));
    assertTrue(results.getJSONObject(1).getBoolean("ok"));
  }

  @Test
  @DisplayName("another client's id and a nonexistent id answer per document and abort nothing")
  void foreignAndMissingIdsAnswerPerDocument() throws Exception {
    JSONObject notFound = McpToolRouter.wrapAsErrorContent(
        new JSONObject().put("status", 404).put("error", "not_found").put("detail", "Record not found"));
    JSONObject content = run(call("mine", "foreign", "missing", "mine2"), (spec, args) ->
        args.getString("id").startsWith("mine") ? ok("done") : notFound);

    JSONObject answer = body(content);
    assertEquals(2, answer.getJSONObject("summary").getInt("succeeded"));
    assertEquals(2, answer.getJSONObject("summary").getInt("failed"));
    assertEquals(404, answer.getJSONArray("results").getJSONObject(1)
        .getJSONObject("error").getInt("status"));
    assertEquals(4, events.stream().filter(e -> e.startsWith("run:")).count());
  }

  @Test
  @DisplayName("a warning is a success with status warning")
  void warningIsASuccess() throws Exception {
    JSONObject content = run(call("a"), (spec, args) -> McpToolRouter.wrapAsTextContent(
        new JSONObject().put("processResult", "warning").put("processMessage", "careful")));

    JSONObject result = body(content).getJSONArray("results").getJSONObject(0);
    assertTrue(result.getBoolean("ok"));
    assertEquals("warning", result.getString("status"));
  }

  @Test
  @DisplayName("a clone keeps its own answer, the new id included, for each of three documents")
  void cloneKeepsEachNewId() throws Exception {
    JSONObject args = call("i1", "i2", "i3");
    args.put("action", "cloneRecord");
    args.remove("parameters");

    JSONObject content = run(args, (spec, doc) -> McpToolRouter.wrapAsTextContent(
        new JSONObject().put("id", "clone-of-" + doc.getString("id"))
            .put("documentNo", "DN-" + doc.getString("id"))));

    JSONArray results = body(content).getJSONArray("results");
    for (int i = 0; i < 3; i++) {
      JSONObject own = results.getJSONObject(i).getJSONObject("result");
      assertEquals("clone-of-i" + (i + 1), own.getString("id"));
      assertEquals("DN-i" + (i + 1), own.getString("documentNo"));
      assertEquals("success", results.getJSONObject(i).getString("status"));
    }
  }

  @Test
  @DisplayName("a prose error from a document becomes its error.message")
  void proseErrorBecomesAMessage() throws Exception {
    JSONObject content = run(call("a"),
        (spec, args) -> McpToolRouter.wrapAsErrorContent("plain failure"));

    assertEquals("plain failure", body(content).getJSONArray("results").getJSONObject(0)
        .getJSONObject("error").getString("message"));
  }

  @Test
  @DisplayName("a commit that fails marks that document failed and the next one still runs")
  void commitFailureFailsOnlyThatDocument() throws Exception {
    doThrow(new OBException("commit refused")).doAnswer(inv -> events.add("commit"))
        .when(dal).commitAndClose();

    JSONObject content = run(call("a", "b"), (spec, args) -> ok("done"));

    JSONArray results = body(content).getJSONArray("results");
    assertFalse(results.getJSONObject(0).getBoolean("ok"));
    assertTrue(results.getJSONObject(1).getBoolean("ok"));
    // ETP-5782: classifying an unexpected failure rolls back too; on the already-closed session the
    // second rollback is a no-op, and the batch's own one still runs BEFORE the next document.
    assertEquals(List.of("run:a", "rollback", "rollback", "run:b", "commit"), events);
  }

  // ── session and context ──────────────────────────────────────────────

  @Test
  @DisplayName("administrator mode lost by a document is put back before the next one")
  void lostAdminModeIsRestored() throws Exception {
    when(context.isInAdministratorMode()).thenReturn(false);

    run(call("a", "b"), (spec, args) -> ok("done"));

    obContextMock.verify(() -> OBContext.setAdminMode(true), times(2));
  }

  @Test
  @DisplayName("administrator mode that is still on is left alone")
  void intactAdminModeIsNotTouched() throws Exception {
    run(call("a", "b"), (spec, args) -> ok("done"));

    obContextMock.verify(() -> OBContext.setAdminMode(true), never());
  }

  @Test
  @DisplayName("each document gets its own copy of the parameters, and the caller's is untouched")
  void parametersAreCopiedPerDocument() throws Exception {
    JSONObject args = call("a", "b");
    List<JSONObject> seen = new ArrayList<>();

    run(args, (spec, doc) -> {
      JSONObject params = doc.getJSONObject("parameters");
      seen.add(new JSONObject(params.toString()));
      params.put("recordId", doc.getString("id"));
      params.put("leaked", true);
      return ok("done");
    });

    assertFalse(seen.get(1).has("leaked"), "the second document saw the first one's mutation");
    assertFalse(seen.get(1).has("recordId"));
    assertFalse(args.getJSONObject("parameters").has("leaked"));
    assertEquals("CO", seen.get(0).getString("docAction"));
  }

  @Test
  @DisplayName("every document is fired as etendo_action would: entity, id, action, parameters")
  void documentArgsMirrorEtendoAction() throws Exception {
    List<JSONObject> seen = new ArrayList<>();

    run(call("a"), (spec, doc) -> {
      seen.add(doc);
      assertEquals(SPEC, spec);
      return ok("done");
    });

    JSONObject doc = seen.get(0);
    assertEquals(ENTITY, doc.getString("entity"));
    assertEquals("a", doc.getString("id"));
    assertEquals(ACTION, doc.getString("action"));
    assertEquals("CO", doc.getJSONObject("parameters").getString("docAction"));
  }

  // ── validation of the call: nothing runs ─────────────────────────────

  @Test
  @DisplayName("empty ids is refused and nothing runs")
  void emptyIdsRefused() throws Exception {
    JSONObject content = run(call(), (spec, args) -> ok("never"));

    assertRefused(content, "ids");
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("51 ids is refused, 50 is accepted")
  void cap() throws Exception {
    List<String> fifty = new ArrayList<>();
    for (int i = 0; i < McpActionBatch.MAX_DOCUMENTS; i++) {
      fifty.add("id" + i);
    }
    JSONObject accepted = run(call(fifty.toArray(new String[0])), (spec, args) -> ok("done"));
    assertEquals(50, body(accepted).getJSONObject("summary").getInt("total"));

    events.clear();
    fifty.add("one-too-many");
    JSONObject refused = run(call(fifty.toArray(new String[0])), (spec, args) -> ok("never"));

    assertRefused(refused, "ids");
    assertTrue(body(refused).getString("detail").contains("maximum is 50"));
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("a repeated id is refused by name and nothing runs")
  void duplicateIdsRefused() throws Exception {
    JSONObject content = run(call("a", "b", "a"), (spec, args) -> ok("never"));

    assertRefused(content, "ids");
    assertTrue(body(content).getString("detail").contains("[a]"), body(content).toString());
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("an id that is blank or not a string is refused")
  void nonStringIdRefused() throws Exception {
    JSONObject blank = call("a");
    blank.put("ids", new JSONArray().put("a").put("  "));
    JSONObject number = call("a");
    number.put("ids", new JSONArray().put("a").put(42));

    assertRefused(run(blank, (spec, args) -> ok("never")), "ids");
    assertRefused(run(number, (spec, args) -> ok("never")), "ids");
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("ids that is not an array is refused")
  void idsNotAnArrayRefused() throws Exception {
    JSONObject args = call("a");
    args.put("ids", "a,b");

    assertRefused(run(args, (spec, doc) -> ok("never")), "ids");
  }

  @Test
  @DisplayName("a missing required argument is a 422 naming it")
  void missingArgumentThrows() throws Exception {
    JSONObject args = call("a");
    args.remove("ids");

    McpRoutingException e = assertThrows(McpRoutingException.class,
        () -> run(args, (spec, doc) -> ok("never")));

    assertEquals("ids", e.getField());
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("an action that is neither declared nor a button is refused before anything runs")
  void unknownActionRefused() throws Exception {
    buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
        .thenReturn(null);

    JSONObject content = run(call("a"), (spec, args) -> ok("never"));

    assertRefused(content, "action");
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("an action whose contract rejects the parameters refuses the whole call")
  void contractViolationRefusesTheCall() throws Exception {
    declaredMock.when(() -> McpDeclaredActions.precheck(any(), anyString(), any()))
        .thenThrow(McpRoutingException.missingArgument("bad parameters", "parameters"));

    McpRoutingException e = assertThrows(McpRoutingException.class,
        () -> run(call("a", "b"), (spec, doc) -> ok("never")));

    assertEquals("parameters", e.getField());
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("an unknown entity is refused by the same lookup etendo_action uses")
  void unknownEntityRefused() throws Exception {
    supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
        .thenThrow(McpRoutingException.missingArgument("no such entity", "entity"));

    assertThrows(McpRoutingException.class, () -> run(call("a"), (spec, doc) -> ok("never")));
    assertTrue(events.isEmpty());
    verify(dal, never()).commitAndClose();
  }

  @Test
  @DisplayName("availability is advisory: a document the action may not apply to is still sent")
  void availabilityIsNeverConsulted() throws Exception {
    run(call("a"), (spec, args) -> failed("Document already processed"));

    // the batch added no refusal of its own: the core's answer is the document's result
    assertEquals(List.of("run:a", "rollback"), events);
  }

  // ── the published definition ─────────────────────────────────────────

  @Test
  @DisplayName("definition: name, required arguments, ids bounded by the cap, batch-vs-create guidance")
  void definitionShape() {
    McpToolDefinition tool = McpActionBatch.definition(List.of(SPEC, "sales-invoice"));

    assertEquals("etendo_action_batch", tool.getName());
    assertTrue(tool.getDescription().contains("etendo_batch"));
    assertTrue(tool.getDescription().contains("NOT atomic"));
    java.util.Map<String, Object> schema = tool.getInputSchema();
    assertEquals(List.of("spec", "entity", "action", "ids"), schema.get("required"));
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> props = (java.util.Map<String, Object>) schema.get("properties");
    assertEquals(List.of("spec", "entity", "action", "ids", "parameters"),
        new ArrayList<>(props.keySet()));
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> ids = (java.util.Map<String, Object>) props.get("ids");
    assertEquals(McpActionBatch.MAX_DOCUMENTS, ids.get("maxItems"));
    assertEquals(1, ids.get("minItems"));
    assertNotNull(ids.get("items"));
  }
}
