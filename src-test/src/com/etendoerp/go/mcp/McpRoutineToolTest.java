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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>The router is replaced by a scripted {@link McpRoutineSteps.StepRunner}; what each tool does is
 * not under test here.</p>
 *
 * @covers com.etendoerp.go.mcp.McpRoutineTool
 * @covers com.etendoerp.go.mcp.McpRoutineSteps
 * @covers com.etendoerp.go.mcp.McpRoutineParams
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

  private static final McpRoutineStore.Owner ALICE =
      new McpRoutineStore.Owner("C1", "O1", "alice", "R1");
  private static final McpRoutineStore.Owner BOB =
      new McpRoutineStore.Owner("C1", "O1", "bob", "R1");
  private final MemoryStore store = new MemoryStore();
  private McpRoutineStore.Owner caller = ALICE;

  private JSONObject handle(JSONObject args, McpRoutineSteps.StepRunner runner) {
    return new McpRoutineTool(store, () -> caller, runner).handle(args);
  }

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
  private McpRoutineSteps.StepRunner scripted(JSONObject... answers) {
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
    JSONObject result = handle(exec(
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
    handle(exec(
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
    JSONObject result = handle(exec(first,
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
    JSONObject result = handle(exec(
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
    McpRoutineSteps.StepRunner runner = (tool, args) -> {
      events.add(tool);
      throw new SecurityException("requires scope 'etendo:write'");
    };
    JSONObject result = handle(exec(
        step("bp", "etendo_create", new JSONObject().put("spec", "contacts"))), runner);

    assertTrue(result.getBoolean("isError"));
    assertEquals(List.of("etendo_create", "rollback"), events);
    assertEquals(403, bodyOf(result).getInt("status"));
  }

  @Test
  @DisplayName("a reference to a later or unknown step is refused before anything runs")
  void forwardReferenceRefused() throws Exception {
    JSONObject result = handle(exec(
        step("addr", "etendo_create", new JSONObject().put("parentId", "$ref:bp")),
        step("bp", "etendo_create", new JSONObject())), scripted());

    assertTrue(result.getBoolean("isError"));
    assertTrue(events.isEmpty());
    assertTrue(bodyOf(result).getString("detail").contains("Nothing was run"));
  }

  @Test
  @DisplayName("a step calling a tool outside the allowed list is refused before anything runs")
  void disallowedToolRefused() throws Exception {
    JSONObject result = handle(exec(
        step("bp", "etendo_create", new JSONObject()),
        step("del", "etendo_delete", new JSONObject().put("id", "$ref:bp"))), scripted());

    assertTrue(result.getBoolean("isError"));
    assertTrue(events.isEmpty());
    assertTrue(bodyOf(result).getString("detail").contains("etendo_delete"));
  }

  @Test
  @DisplayName("more than the maximum number of steps is refused before anything runs")
  void tooManyStepsRefused() throws Exception {
    JSONObject[] steps = new JSONObject[McpRoutineSteps.MAX_STEPS + 1];
    for (int i = 0; i < steps.length; i++) {
      steps[i] = step("s" + i, "etendo_get", new JSONObject());
    }
    JSONObject result = handle(exec(steps), scripted());

    assertTrue(result.getBoolean("isError"));
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("a path that is not in the earlier result fails that step and keeps what was done")
  void missingPathFailsStep() throws Exception {
    JSONObject emptyList = McpToolRouter.wrapAsTextContent(new JSONObject()
        .put("data", new JSONArray()));
    JSONObject result = handle(exec(
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
    JSONObject result = handle(exec(
        step("agua", "etendo_list", new JSONObject()),
        step("line", "etendo_create", new JSONObject().put("product", "$ref:agua"))),
        scripted(list));

    assertTrue(result.getBoolean("isError"));
    assertTrue(bodyOf(result).getJSONObject("stepError").getString("detail")
        .contains("$ref:agua.data[0].id"));
  }

  // ── saved routines ───────────────────────────────────────────────────

  private static JSONObject param(String type, boolean required, String description)
      throws Exception {
    return new JSONObject().put("type", type).put("required", required)
        .put("description", description);
  }

  private static JSONObject bpStep(Object name) throws Exception {
    return step("bp", "etendo_create", new JSONObject().put("spec", "contacts")
        .put("entity", "businessPartner").put("fields", new JSONObject().put("name", name)));
  }

  private JSONObject save(String name, JSONObject params, JSONObject... steps) throws Exception {
    JSONObject args = new JSONObject().put("mode", "save").put("name", name)
        .put("description", "Creates a customer").put("steps", new JSONArray(List.of(steps)));
    if (params != null) {
      args.put("params", params);
    }
    return handle(args, scripted());
  }

  private JSONObject run(String name, JSONObject params, JSONObject... answers) throws Exception {
    JSONObject args = new JSONObject().put("mode", "run").put("name", name);
    if (params != null) {
      args.put("params", params);
    }
    return handle(args, scripted(answers));
  }

  @Test
  @DisplayName("exec answers an executionId whose record keeps every step's full result")
  void execIsRecordedAndReadable() throws Exception {
    JSONObject result = handle(exec(bpStep("Acme")), scripted(created("BP1")));
    String executionId = bodyOf(result).getString("executionId");

    JSONObject read = bodyOf(handle(new JSONObject().put("mode", "execution")
        .put("executionId", executionId), scripted()));
    assertTrue(read.getBoolean("ok"));
    assertEquals("exec", read.getString("mode"));
    assertEquals("BP1", read.getJSONArray("steps").getJSONObject(0).getJSONObject("result")
        .getJSONObject("data").getString("id"));
  }

  @Test
  @DisplayName("a successful exec saved with fromExecution runs again with new parameter values")
  void saveFromExecutionThenRun() throws Exception {
    JSONObject exec = handle(exec(bpStep("Acme"),
        step("addr", "etendo_create", new JSONObject().put("spec", "contacts")
            .put("parentId", "$ref:bp").put("fields", new JSONObject().put("cityName", "Madrid")
                .put("postalCode", 28001)))),
        scripted(created("BP1"), created("LOC1")));
    String executionId = bodyOf(exec).getString("executionId");

    JSONObject params = new JSONObject()
        .put("name", param("string", true, "Customer name").put("from",
            new JSONArray().put("bp.args.fields.name")))
        .put("zip", param("number", true, "Postal code").put("from",
            new JSONArray().put("addr.args.fields.postalCode")));
    JSONObject saved = handle(new JSONObject().put("mode", "save").put("name", "new-customer")
        .put("description", "Customer with address").put("fromExecution", executionId)
        .put("params", params), scripted());
    assertFalse(saved.optBoolean("isError", false), saved.toString());
    assertFalse(bodyOf(saved).getJSONObject("params").getJSONObject("name").has("from"));

    calls.clear();
    events.clear();
    JSONObject ran = run("new-customer", new JSONObject().put("name", "Globex").put("zip", 8001),
        created("BP2"), created("LOC2"));

    assertFalse(ran.optBoolean("isError", false), ran.toString());
    assertEquals("Globex", calls.get(0).getJSONObject("fields").getString("name"));
    assertEquals(8001, calls.get(1).getJSONObject("fields").get("postalCode"));
    assertEquals("BP2", calls.get(1).getString("parentId"));
    assertEquals("Madrid", calls.get(1).getJSONObject("fields").getString("cityName"));
    JSONObject body = bodyOf(ran);
    assertEquals("new-customer", body.getString("name"));
    assertEquals(1, body.getInt("version"));
  }

  @Test
  @DisplayName("run refuses missing, mistyped and unknown parameters before anything runs")
  void runValidatesParametersFirst() throws Exception {
    save("customer", new JSONObject().put("name", param("string", true, "Customer name")),
        bpStep("$param.name"));

    for (JSONObject values : List.of(new JSONObject(), new JSONObject().put("name", 7),
        new JSONObject().put("name", "Acme").put("city", "Madrid"))) {
      JSONObject result = run("customer", values);
      assertTrue(result.getBoolean("isError"), values.toString());
      assertTrue(bodyOf(result).getString("detail").contains("Nothing was run"));
    }
    assertTrue(events.isEmpty());
  }

  @Test
  @DisplayName("an optional parameter that is not sent drops its key from the step")
  void optionalParameterDropsItsKey() throws Exception {
    JSONObject fields = new JSONObject().put("name", "$param.name").put("description",
        "$param.note");
    save("customer", new JSONObject().put("name", param("string", true, "Customer name"))
        .put("note", param("string", false, "Optional note")),
        step("bp", "etendo_create", new JSONObject().put("spec", "contacts")
            .put("fields", fields)));

    run("customer", new JSONObject().put("name", "Acme"), created("BP1"));

    JSONObject sent = calls.get(0).getJSONObject("fields");
    assertEquals("Acme", sent.getString("name"));
    assertFalse(sent.has("description"));
  }

  @Test
  @DisplayName("save refuses an unused or undeclared parameter and a repeated name")
  void saveValidatesTheDefinition() throws Exception {
    JSONObject unused = save("a", new JSONObject().put("x", param("string", true, "X")),
        bpStep("Acme"));
    assertTrue(unused.getBoolean("isError"));
    assertTrue(bodyOf(unused).getString("detail").contains("[x]"));

    JSONObject undeclared = save("b", null, bpStep("$param.name"));
    assertTrue(undeclared.getBoolean("isError"));

    assertFalse(save("c", null, bpStep("Acme")).optBoolean("isError", false));
    JSONObject repeated = save("c", null, bpStep("Acme"));
    assertTrue(repeated.getBoolean("isError"));
    assertTrue(bodyOf(repeated).getString("detail").contains("mode update"));
  }

  @Test
  @DisplayName("update adds a version; an older one still runs with 'version'")
  void updateKeepsOlderVersions() throws Exception {
    save("customer", null, bpStep("Old"));
    JSONObject updated = handle(new JSONObject().put("mode", "update").put("name", "customer")
        .put("steps", new JSONArray().put(bpStep("New"))), scripted());
    assertEquals(2, bodyOf(updated).getInt("version"));

    run("customer", null, created("BP1"));
    handle(new JSONObject().put("mode", "run").put("name", "customer").put("version", 1),
        scripted(created("BP2")));

    assertEquals("New", calls.get(0).getJSONObject("fields").getString("name"));
    assertEquals("Old", calls.get(1).getJSONObject("fields").getString("name"));
    JSONObject described = bodyOf(handle(new JSONObject().put("mode", "describe")
        .put("name", "customer"), scripted()));
    assertEquals(2, described.getInt("currentVersion"));
    assertEquals(2, described.getJSONArray("versions").length());
  }

  @Test
  @DisplayName("routines and executions are personal: another user neither sees nor runs them")
  void routinesArePersonal() throws Exception {
    save("customer", null, bpStep("Acme"));
    String executionId = bodyOf(run("customer", null, created("BP1")))
        .getString("executionId");

    caller = BOB;
    assertEquals(0, bodyOf(handle(new JSONObject().put("mode", "list"), scripted()))
        .getJSONArray("routines").length());
    JSONObject foreignRun = run("customer", null);
    assertTrue(foreignRun.getBoolean("isError"));
    assertEquals("not_found", bodyOf(foreignRun).getString("error"));
    assertTrue(handle(new JSONObject().put("mode", "execution").put("executionId", executionId),
        scripted()).getBoolean("isError"));
  }

  @Test
  @DisplayName("list shows the caller's routines with their parameters and current version")
  void listShowsRoutines() throws Exception {
    save("customer", new JSONObject().put("name", param("string", true, "Customer name")),
        bpStep("$param.name"));

    JSONObject listed = bodyOf(handle(new JSONObject().put("mode", "list"), scripted()));
    JSONObject routine = listed.getJSONArray("routines").getJSONObject(0);
    assertEquals("customer", routine.getString("name"));
    assertEquals("string", routine.getJSONObject("params").getJSONObject("name")
        .getString("type"));
    assertEquals(1, routine.getInt("currentVersion"));
    assertTrue(listed.getString("hint").contains("Remember"));
  }

  @Test
  @DisplayName("a deleted routine no longer runs")
  void deletedRoutineIsGone() throws Exception {
    save("customer", null, bpStep("Acme"));
    assertFalse(handle(new JSONObject().put("mode", "delete").put("name", "customer"),
        scripted()).optBoolean("isError", false));

    JSONObject result = run("customer", null);
    assertTrue(result.getBoolean("isError"));
    assertEquals("not_found", bodyOf(result).getString("error"));
  }

  @Test
  @DisplayName("fromExecution refuses a failed execution and a path that is not in its steps")
  void fromExecutionRefusals() throws Exception {
    JSONObject failedRun = handle(exec(bpStep("Acme")), scripted(McpToolRouter
        .wrapAsErrorContent(new JSONObject().put("status", 422).put("error", "validation_error"))));
    String failedId = bodyOf(failedRun).getString("executionId");
    JSONObject fromFailed = handle(new JSONObject().put("mode", "save").put("name", "x")
        .put("description", "d").put("fromExecution", failedId), scripted());
    assertTrue(fromFailed.getBoolean("isError"));

    String okId = bodyOf(handle(exec(bpStep("Acme")), scripted(created("BP1"))))
        .getString("executionId");
    JSONObject badPath = handle(new JSONObject().put("mode", "save").put("name", "y")
        .put("description", "d").put("fromExecution", okId).put("params", new JSONObject()
            .put("name", param("string", true, "Name").put("from",
                new JSONArray().put("bp.args.fields.nombre")))), scripted());
    assertTrue(badPath.getBoolean("isError"));
    assertTrue(bodyOf(badPath).getString("detail").contains("bp.args.fields.nombre"));
  }

  // ── help ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("help answers the format and four examples, and takes no other argument")
  void helpAnswersTheFormat() throws Exception {
    JSONObject body = bodyOf(handle(new JSONObject().put("mode", "help"), scripted()));
    assertTrue(body.getString("references").contains("$ref:<id>.<path>"));
    assertEquals(4, body.getJSONArray("examples").length());
    assertTrue(handle(new JSONObject().put("mode", "help").put("name", "x"), scripted())
        .getBoolean("isError"));
  }

  @Test
  @DisplayName("the help examples are real calls: exec, save fromExecution and run work as shown")
  void helpExamplesWorkAsShown() throws Exception {
    JSONObject list = McpToolRouter.wrapAsTextContent(new JSONObject().put("data",
        new JSONArray().put(new JSONObject().put("id", "ROW1"))));
    JSONObject done = McpToolRouter.wrapAsTextContent(new JSONObject().put("success", true));
    JSONObject exec = handle(new JSONObject(McpRoutineHelp.EXAMPLE_EXEC),
        scripted(list, list, created("INV1"), created("LINE1"), done));
    assertFalse(exec.optBoolean("isError", false), exec.toString());

    JSONObject save = new JSONObject(McpRoutineHelp.EXAMPLE_SAVE_FROM_EXECUTION)
        .put("fromExecution", bodyOf(exec).getString("executionId"));
    JSONObject saved = handle(save, scripted());
    assertFalse(saved.optBoolean("isError", false), saved.toString());

    calls.clear();
    JSONObject ran = handle(new JSONObject(McpRoutineHelp.EXAMPLE_RUN),
        scripted(list, list, created("INV2"), created("LINE2"), done));
    assertFalse(ran.optBoolean("isError", false), ran.toString());
    assertEquals("Globex", calls.get(0).getJSONObject("filters").getJSONObject("name")
        .getString("contains"));
    assertEquals(3, calls.get(3).getJSONObject("fields").get("invoicedQuantity"));
    assertEquals(12.5, calls.get(3).getJSONObject("fields").getDouble("unitPrice"));

    JSONObject direct = handle(new JSONObject(McpRoutineHelp.EXAMPLE_SAVE), scripted());
    assertFalse(direct.optBoolean("isError", false), direct.toString());
  }

  /** The routine tables, in memory, scoped by client and user like the JDBC store. */
  private static final class MemoryStore implements McpRoutineStore {
    private final Map<String, Routine> routines = new HashMap<>();
    private final Map<String, String> routineKeys = new HashMap<>();
    private final Map<String, Version> versions = new HashMap<>();
    private final Map<String, Execution> executions = new HashMap<>();
    private final Map<String, String> executionOwners = new HashMap<>();
    private int sequence;

    private static String key(Owner owner, String name) {
      return owner.clientId + "/" + owner.userId + "/" + name;
    }

    @Override
    public Routine find(Owner owner, String name) {
      return routines.get(key(owner, name));
    }

    @Override
    public List<Routine> list(Owner owner) {
      List<Routine> mine = new ArrayList<>();
      routines.forEach((k, r) -> {
        if (k.startsWith(owner.clientId + "/" + owner.userId + "/")) {
          mine.add(r);
        }
      });
      return mine;
    }

    @Override
    public Version version(String routineId, int number) {
      return versions.get(routineId + "#" + number);
    }

    @Override
    public List<Integer> versionNumbers(String routineId) {
      List<Integer> numbers = new ArrayList<>();
      versions.keySet().stream().filter(k -> k.startsWith(routineId + "#"))
          .forEach(k -> numbers.add(Integer.parseInt(k.substring(k.indexOf('#') + 1))));
      numbers.sort(null);
      return numbers;
    }

    @Override
    public Routine create(Owner owner, String name, String description, String params,
        String steps) {
      Routine routine = new Routine("R" + (++sequence), name, description, 1);
      routines.put(key(owner, name), routine);
      routineKeys.put(routine.id, key(owner, name));
      versions.put(routine.id + "#1", new Version(1, params, steps, "now"));
      return routine;
    }

    @Override
    public int addVersion(Owner owner, Routine routine, String description, String params,
        String steps) {
      int number = versionNumbers(routine.id).size() + 1;
      versions.put(routine.id + "#" + number, new Version(number, params, steps, "now"));
      routines.put(routineKeys.get(routine.id),
          new Routine(routine.id, routine.name, description, number));
      return number;
    }

    @Override
    public boolean delete(Owner owner, String name) {
      return routines.remove(key(owner, name)) != null;
    }

    @Override
    public String recordExecution(Owner owner, Execution execution) {
      String id = "E" + (++sequence);
      executions.put(id, new Execution(id, execution.mode, execution.status,
          execution.routineName, execution.version, execution.steps, execution.result, "now"));
      executionOwners.put(id, owner.clientId + "/" + owner.userId);
      return id;
    }

    @Override
    public Execution findExecution(Owner owner, String executionId) {
      return (owner.clientId + "/" + owner.userId).equals(executionOwners.get(executionId))
          ? executions.get(executionId) : null;
    }
  }
}
