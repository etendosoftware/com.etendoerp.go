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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link McpRoutineTool}, the {@code etendo_routine} tool (ETP-6236): references
 * between steps, one transaction per step, the report of a failed routine, and the refusals that
 * stop a call before anything runs.
 *
 * <p>The router is replaced by a scripted {@link McpRoutineTool.StepRunner}; what each tool does is
 * not under test here.</p>
 *
 * @covers com.etendoerp.go.mcp.McpRoutineTool
 */
class McpRoutineToolTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  /** What happened, in order: every tool call with its resolved args, every commit and rollback. */
  private final List<String> events = new ArrayList<>();
  private final List<JSONObject> calls = new ArrayList<>();

  @BeforeEach
  void setUp() {
    OBDal dal = mock(OBDal.class);
    OBContext context = mock(OBContext.class);
    when(context.isInAdministratorMode()).thenReturn(true);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(context);
    doAnswer(inv -> events.add("commit")).when(dal).commitAndClose();
    doAnswer(inv -> events.add("rollback")).when(dal).rollbackAndClose();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private static JSONObject step(String id, String tool, JSONObject args) throws Exception {
    return new JSONObject().put("id", id).put("tool", tool).put("args", args);
  }

  private static JSONObject exec(JSONObject... steps) throws Exception {
    return new JSONObject().put("mode", "exec").put("steps", new JSONArray(List.of(steps)));
  }

  private static JSONObject created(String id) throws Exception {
    return McpToolRouter.wrapAsTextContent(new JSONObject().put("data",
        new JSONObject().put("id", id)));
  }

  private static JSONObject bodyOf(JSONObject content) throws Exception {
    return new JSONObject(McpActionBatch.textOf(content));
  }

  /** A runner that answers each call with the next scripted content and records the call. */
  private McpRoutineTool.StepRunner scripted(JSONObject... answers) {
    int[] next = { 0 };
    return (tool, args) -> {
      events.add(tool);
      calls.add(args);
      return answers[next[0]++];
    };
  }

  // ── tests ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a later step receives the record id of an earlier one, and each step commits")
  void chainsRecordIdAndCommitsEachStep() throws Exception {
    JSONObject result = McpRoutineTool.handle(exec(
        step("bp", "etendo_create", new JSONObject().put("spec", "contacts")
            .put("entity", "businessPartner").put("fields", new JSONObject().put("name", "Acme"))),
        step("addr", "etendo_create", new JSONObject().put("spec", "contacts")
            .put("entity", "locationAddress").put("parentId", "$ref:bp")
            .put("fields", new JSONObject().put("cityName", "Madrid")))),
        scripted(created("BP1"), created("LOC1")));

    assertFalse(result.optBoolean("isError", false));
    assertEquals("BP1", calls.get(1).getString("parentId"));
    assertEquals(List.of("etendo_create", "commit", "etendo_create", "commit"), events);
    JSONObject body = bodyOf(result);
    assertTrue(body.getBoolean("ok"));
    JSONArray steps = body.getJSONArray("steps");
    assertEquals("BP1", steps.getJSONObject(0).getString("recordId"));
    assertEquals("LOC1", steps.getJSONObject(1).getString("recordId"));
    JSONObject results = body.getJSONObject("results");
    assertFalse(results.has("bp"), "without returnResult only the last step is returned");
    assertEquals("LOC1", results.getJSONObject("addr").getJSONObject("data").getString("id"));
  }

  @Test
  @DisplayName("a path reference reads a value out of an earlier list result, keeping its type")
  void resolvesPathIntoListResult() throws Exception {
    JSONObject list = McpToolRouter.wrapAsTextContent(new JSONObject().put("data",
        new JSONArray().put(new JSONObject().put("id", "AGUA").put("price", 100))));
    McpRoutineTool.handle(exec(
        step("agua", "etendo_list", new JSONObject().put("spec", "product")),
        step("line", "etendo_create", new JSONObject().put("spec", "sales-invoice")
            .put("fields", new JSONObject().put("product", "$ref:agua.data[0].id")
                .put("unitPrice", "$ref:agua.data.0.price")))),
        scripted(list, created("L1")));

    JSONObject fields = calls.get(1).getJSONObject("fields");
    assertEquals("AGUA", fields.getString("product"));
    assertEquals(100, fields.get("unitPrice"));
  }

  @Test
  @DisplayName("returnResult selects which step results come back")
  void returnResultSelectsResults() throws Exception {
    JSONObject first = step("bp", "etendo_create", new JSONObject().put("spec", "contacts"))
        .put("returnResult", true);
    JSONObject result = McpRoutineTool.handle(exec(first,
        step("addr", "etendo_create", new JSONObject().put("spec", "contacts"))),
        scripted(created("BP1"), created("LOC1")));

    JSONObject results = bodyOf(result).getJSONObject("results");
    assertTrue(results.has("bp"));
    assertFalse(results.has("addr"));
  }

  @Test
  @DisplayName("a failing step is rolled back, stops the routine and reports done and pending")
  void failureStopsAndReports() throws Exception {
    JSONObject refused = McpToolRouter.wrapAsErrorContent(new JSONObject().put("status", 422)
        .put("error", "validation_error").put("detail", "cityName is mandatory"));
    JSONObject result = McpRoutineTool.handle(exec(
        step("bp", "etendo_create", new JSONObject().put("spec", "contacts")),
        step("addr", "etendo_create", new JSONObject().put("spec", "contacts")
            .put("parentId", "$ref:bp")),
        step("inv", "etendo_create", new JSONObject().put("spec", "sales-invoice")
            .put("fields", new JSONObject().put("businessPartner", "$ref:bp")))),
        scripted(created("BP1"), refused));

    assertTrue(result.getBoolean("isError"));
    assertEquals(List.of("etendo_create", "commit", "etendo_create", "rollback"), events);
    JSONObject body = bodyOf(result);
    assertFalse(body.getBoolean("ok"));
    assertEquals("validation_error", body.getString("error"));
    assertEquals("addr", body.getJSONObject("failedAt").getString("id"));
    assertEquals("cityName is mandatory", body.getJSONObject("stepError").getString("detail"));
    assertEquals("BP1", body.getJSONArray("completed").getJSONObject(0).getString("recordId"));
    assertEquals("inv", body.getJSONArray("pending").getString(0));
  }

  @Test
  @DisplayName("an exception the router lets through (a scope refusal) is that step's forbidden error")
  void thrownRefusalIsStepError() throws Exception {
    McpRoutineTool.StepRunner runner = (tool, args) -> {
      events.add(tool);
      throw new SecurityException("requires scope 'etendo:write'");
    };
    JSONObject result = McpRoutineTool.handle(exec(
        step("bp", "etendo_create", new JSONObject().put("spec", "contacts"))), runner);

    assertTrue(result.getBoolean("isError"));
    assertEquals(List.of("etendo_create", "rollback"), events);
    assertEquals(403, bodyOf(result).getInt("status"));
  }

  @Test
  @DisplayName("a reference to a later or unknown step is refused before anything runs")
  void forwardReferenceRefused() throws Exception {
    JSONObject result = McpRoutineTool.handle(exec(
        step("addr", "etendo_create", new JSONObject().put("parentId", "$ref:bp")),
        step("bp", "etendo_create", new JSONObject())), scripted());

    assertTrue(result.getBoolean("isError"));
    assertTrue(events.isEmpty());
    assertTrue(bodyOf(result).getString("detail").contains("Nothing was run"));
  }

  @Test
  @DisplayName("a step calling a tool outside the allowed list is refused before anything runs")
  void disallowedToolRefused() throws Exception {
    JSONObject result = McpRoutineTool.handle(exec(
        step("bp", "etendo_create", new JSONObject()),
        step("del", "etendo_delete", new JSONObject().put("id", "$ref:bp"))), scripted());

    assertTrue(result.getBoolean("isError"));
    assertTrue(events.isEmpty());
    assertTrue(bodyOf(result).getString("detail").contains("etendo_delete"));
  }

  @Test
  @DisplayName("more than the maximum number of steps is refused before anything runs")
  void tooManyStepsRefused() throws Exception {
    JSONObject[] steps = new JSONObject[McpRoutineTool.MAX_STEPS + 1];
    for (int i = 0; i < steps.length; i++) {
      steps[i] = step("s" + i, "etendo_get", new JSONObject());
    }
    JSONObject result = McpRoutineTool.handle(exec(steps), scripted());

    assertTrue(result.getBoolean("isError"));
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("a path that is not in the earlier result fails that step and keeps what was done")
  void missingPathFailsStep() throws Exception {
    JSONObject emptyList = McpToolRouter.wrapAsTextContent(new JSONObject()
        .put("data", new JSONArray()));
    JSONObject result = McpRoutineTool.handle(exec(
        step("agua", "etendo_list", new JSONObject()),
        step("line", "etendo_create", new JSONObject().put("fields",
            new JSONObject().put("product", "$ref:agua.data[0].id")))),
        scripted(emptyList));

    assertTrue(result.getBoolean("isError"));
    assertEquals(List.of("etendo_list", "commit"), events);
    JSONObject body = bodyOf(result);
    assertEquals("line", body.getJSONObject("failedAt").getString("id"));
    assertEquals("agua", body.getJSONArray("completed").getJSONObject(0).getString("id"));
  }

  @Test
  @DisplayName("$ref:<id> to a step without a record id fails that step and names a path to use")
  void bareReferenceWithoutRecordId() throws Exception {
    JSONObject list = McpToolRouter.wrapAsTextContent(new JSONObject().put("data",
        new JSONArray().put(new JSONObject().put("id", "AGUA"))));
    JSONObject result = McpRoutineTool.handle(exec(
        step("agua", "etendo_list", new JSONObject()),
        step("line", "etendo_create", new JSONObject().put("product", "$ref:agua"))),
        scripted(list));

    assertTrue(result.getBoolean("isError"));
    assertTrue(bodyOf(result).getJSONObject("stepError").getString("detail")
        .contains("$ref:agua.data[0].id"));
  }
}
