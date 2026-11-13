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
package com.etendoerp.go.mcp;

import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;
import static com.etendoerp.go.mcp.McpJsonSchema.enumProp;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.mcp.McpRoutineSteps.Refusal;
import com.etendoerp.go.mcp.McpRoutineSteps.Step;
import com.etendoerp.go.mcp.McpRoutineStore.Execution;
import com.etendoerp.go.mcp.McpRoutineStore.Owner;
import com.etendoerp.go.mcp.McpRoutineStore.Routine;
import com.etendoerp.go.mcp.McpRoutineStore.Version;

/**
 * The {@code etendo_routine} tool (ETP-6236, etendo-ai#51): several calls of the existing tools in
 * one call, and routines saved to run them again with new parameters.
 *
 * <p>Running steps is {@link McpRoutineSteps}; parameters are {@link McpRoutineParams}; storage is
 * {@link McpRoutineStore}. This class is the tool: its definition, the modes and their answers.
 * Routines and executions are personal: every lookup is scoped to the calling user.</p>
 */
final class McpRoutineTool {

  private static final Logger log = LogManager.getLogger(McpRoutineTool.class);

  static final String PARAM_MODE = "mode";
  static final String PARAM_STEPS = "steps";
  static final String PARAM_NAME = "name";
  static final String PARAM_DESCRIPTION = McpConstants.KEY_DESCRIPTION;
  static final String PARAM_PARAMS = McpRoutineParams.FIELD_PARAMS;
  static final String PARAM_VERSION = "version";
  static final String PARAM_EXECUTION_ID = "executionId";
  static final String PARAM_FROM_EXECUTION = "fromExecution";

  static final String MODE_EXEC = "exec";
  static final String MODE_RUN = "run";
  static final String MODE_SAVE = "save";
  static final String MODE_UPDATE = "update";
  static final String MODE_LIST = "list";
  static final String MODE_DESCRIBE = "describe";
  static final String MODE_DELETE = "delete";
  static final String MODE_EXECUTION = "execution";
  static final String MODE_HELP = "help";
  static final List<String> MODES = List.of(MODE_EXEC, MODE_RUN, MODE_SAVE, MODE_UPDATE,
      MODE_LIST, MODE_DESCRIBE, MODE_DELETE, MODE_EXECUTION, MODE_HELP);

  static final String STATUS_OK = "OK";
  static final String STATUS_FAILED = "FAILED";

  private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,59}");
  private static final int MAX_DESCRIPTION = 2000;
  private static final String NOTHING_RUN = " Nothing was run.";
  private static final String KEY_ROUTINES = "routines";
  private static final String KEY_CURRENT_VERSION = "currentVersion";

  private final McpRoutineStore store;
  private final Supplier<Owner> owner;
  private final McpRoutineSteps.StepRunner runner;

  /**
   * @param store  where routines and executions live
   * @param owner  who the call runs as; read once, when first needed
   * @param runner the router, one tool call per step
   */
  McpRoutineTool(McpRoutineStore store, Supplier<Owner> owner,
      McpRoutineSteps.StepRunner runner) {
    this.store = store;
    this.owner = owner;
    this.runner = runner;
  }

  // ── Definition ──────────────────────────────────────────────────────────

  /**
   * Builds the tool definition published in {@code tools/list}.
   *
   * @return the definition of {@code etendo_routine}
   */
  static McpToolDefinition definition() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(PARAM_MODE, enumProp("What to do: exec runs 'steps' now; save stores them as a "
        + "routine; run runs a saved routine with 'params'; list, describe, update and delete "
        + "manage your routines; execution reads a past execution; help returns the full format "
        + "with complete examples.", MODES));

    Map<String, Object> stepProps = new LinkedHashMap<>();
    stepProps.put(McpRoutineSteps.STEP_ID, McpJsonSchema.stringProp("This step's name, unique in "
        + "the routine, letters, digits, '_' or '-', starting with a letter. Later steps refer to "
        + "its result as '$ref:<id>'."));
    stepProps.put(McpRoutineSteps.STEP_TOOL, enumProp("The tool this step calls.",
        McpRoutineSteps.STEP_TOOLS));
    stepProps.put(McpRoutineSteps.STEP_ARGS, McpJsonSchema.objectProp("The arguments of that "
        + "tool, exactly as the tool takes them on its own (e.g. {spec, entity, fields} for "
        + "etendo_create)."));
    stepProps.put(McpRoutineSteps.STEP_RETURN_RESULT, McpJsonSchema.booleanProp("Optional. true "
        + "returns this step's full result in the answer. When no step sets it, only the last "
        + "step's result is returned."));
    Map<String, Object> steps = McpJsonSchema.objectArrayProp("exec, save, update: the steps, run "
        + "in this order, at most " + McpRoutineSteps.MAX_STEPS + ".", stepProps,
        List.of(McpRoutineSteps.STEP_ID, McpRoutineSteps.STEP_TOOL, McpRoutineSteps.STEP_ARGS));
    steps.put("minItems", 1);
    steps.put("maxItems", McpRoutineSteps.MAX_STEPS);
    props.put(PARAM_STEPS, steps);
    props.put(PARAM_NAME, McpJsonSchema.stringProp("run, save, update, describe, delete: the "
        + "routine's name, lowercase letters, digits, '-' or '_' (e.g. 'create-customer')."));
    props.put(PARAM_DESCRIPTION, McpJsonSchema.stringProp("save (required), update (optional): "
        + "what the routine does and when to use it."));
    props.put(PARAM_PARAMS, McpJsonSchema.objectProp("save, update: the parameters the steps use "
        + "as '$param.<name>', each {type: string|number|boolean|id, required, description}; with "
        + "fromExecution each also takes from:[<stepId>.<path>, ...], the values of that "
        + "execution it replaces. run: the values, {<name>: value}."));
    props.put(PARAM_VERSION, McpJsonSchema.numericProp("integer", "run, describe: an older "
        + "version of the routine; the current one by default."));
    props.put(PARAM_FROM_EXECUTION, McpJsonSchema.stringProp("save: the executionId of a "
        + "successful exec whose steps become the routine, instead of sending 'steps'."));
    props.put(PARAM_EXECUTION_ID, McpJsonSchema.stringProp("execution: the executionId to read."));

    return new McpToolDefinition(McpConstants.TOOL_NEO_ROUTINE, description(),
        buildObjectSchema(props, List.of(PARAM_MODE)));
  }

  /** The text the agent reads: what the tool does, what it does not, and the shape it answers. */
  private static String description() {
    return "Run several calls of the other tools in ONE call, in order, each step able to use what "
        + "an earlier step returned; and save such a flow as a routine of yours to run it again "
        + "with new values. Use it for a flow whose steps you already know — e.g. create a "
        + "customer, its address and an invoice for it, then complete the invoice — instead of "
        + "one call per step. Each step is {id, tool, args}: 'tool' is one of "
        + McpRoutineSteps.STEP_TOOLS + " and 'args' are that tool's own arguments; every step "
        + "runs exactly as that tool would run alone, with the same permissions, checks and "
        + "errors. A string value '$ref:<id>' anywhere in 'args' is replaced with the record id "
        + "an earlier step created or read (its data.id); '$ref:<id>.<path>' with any value of "
        + "that step's result, e.g. '$ref:agua.data[0].id' for the first row of an etendo_list; "
        + "'$param.<name>' with a parameter of a saved routine. The whole string must be the "
        + "reference. NOT atomic: each step commits when it succeeds; the first failing step is "
        + "rolled back and stops the routine. exec and run return {ok:true, executionId, "
        + "steps:[{id, tool, ok:true, recordId}], results:{<id>: <that step's result>}} — "
        + "results carries the steps marked returnResult:true, or only the last one — or, on "
        + "failure, an error whose failedAt names the step, stepError is that step's own error, "
        + "completed lists the steps that are done and stay done (with their recordId) and "
        + "pending the ones that did not run; mode execution with the executionId returns every "
        + "step's full result later. Routines are yours only: list shows them (call it at the "
        + "start of a task: a routine may already do it), describe shows one with its steps, "
        + "save stores one (or, with fromExecution, the steps of a successful exec, turning the "
        + "values named in each parameter's 'from' into parameters), update stores a new version "
        + "and delete removes it. At most " + McpRoutineSteps.MAX_STEPS + " steps; for many "
        + "records of one kind use etendo_batch. Before writing your first routine call "
        + "{mode:'help'}: the full format, how to turn a finished exec into a routine, and four "
        + "complete examples. Example — save a routine that creates a customer with its address, "
        + "then run it: {mode:'save', name:'customer-with-address', description:'Creates a "
        + "customer with its address', params:{name:{type:'string', required:true, "
        + "description:'Customer name'}, city:{type:'string', required:true, "
        + "description:'City'}}, steps:[{id:'bp', tool:'etendo_create', args:{spec:'contacts', "
        + "entity:'businessPartner', fields:{name:'$param.name'}}}, {id:'address', "
        + "tool:'etendo_create', args:{spec:'contacts', entity:'locationAddress', "
        + "parentId:'$ref:bp', fields:{country:'106', addressLine1:'Calle Mayor 1', "
        + "cityName:'$param.city'}}}]} then {mode:'run', name:'customer-with-address', "
        + "params:{name:'Acme', city:'Madrid'}}.";
  }

  // ── Dispatch ────────────────────────────────────────────────────────────

  /**
   * Handles one {@code etendo_routine} call.
   *
   * @param args the tool arguments
   * @return the MCP content of the mode's answer, or its refusal
   */
  JSONObject handle(JSONObject args) {
    JSONObject arguments = args == null ? new JSONObject() : args;
    String mode = arguments.optString(PARAM_MODE, null);
    try {
      if (mode == null || !MODES.contains(mode)) {
        throw McpRoutineSteps.refusal(PARAM_MODE, "'mode' must be one of " + MODES + ".");
      }
      return McpToolRouter.wrapAsTextContent(dispatch(mode, arguments));
    } catch (Refusal refused) {
      return McpToolRouter.wrapAsErrorContent(refused.body());
    } catch (StepsFailed failed) {
      return McpToolRouter.wrapAsErrorContent(failed.body);
    }
  }

  /** A run that stopped at a failing step; its body is the routine's failure answer. */
  private static final class StepsFailed extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient JSONObject body;

    StepsFailed(JSONObject body) {
      super("routine step failed");
      this.body = body;
    }
  }

  private JSONObject dispatch(String mode, JSONObject args) throws Refusal, StepsFailed {
    switch (mode) {
      case MODE_EXEC:
        return exec(args);
      case MODE_RUN:
        return run(args);
      case MODE_SAVE:
        return save(args);
      case MODE_UPDATE:
        return update(args);
      case MODE_LIST:
        return list();
      case MODE_DESCRIBE:
        return describe(args);
      case MODE_DELETE:
        return delete(args);
      case MODE_HELP:
        onlyArguments(args);
        return McpRoutineHelp.body();
      default:
        return execution(args);
    }
  }

  // ── exec / run ──────────────────────────────────────────────────────────

  private JSONObject exec(JSONObject args) throws Refusal, StepsFailed {
    onlyArguments(args, PARAM_STEPS);
    Object rawSteps = args.opt(PARAM_STEPS);
    List<Step> steps = nothingRun(() -> McpRoutineSteps.parse(rawSteps, Set.of(),
        new HashSet<>()));
    return execute(steps, Map.of(), MODE_EXEC, null, null, rawSteps);
  }

  private JSONObject run(JSONObject args) throws Refusal, StepsFailed {
    onlyArguments(args, PARAM_NAME, PARAM_PARAMS, PARAM_VERSION);
    Routine routine = existing(args);
    Version version = version(routine, args);
    JSONObject declarations = parse(version.params);
    JSONArray storedSteps = parseArray(version.steps);
    Map<String, Object> values = nothingRun(() -> McpRoutineParams.values(declarations,
        args.opt(PARAM_PARAMS)));
    List<Step> steps = nothingRun(() -> McpRoutineSteps.parse(storedSteps,
        McpRoutineParams.names(declarations), new HashSet<>()));
    return execute(steps, values, MODE_RUN, routine.name, version.number, storedSteps);
  }

  /** Runs the steps, records the execution and answers with its id. */
  private JSONObject execute(List<Step> steps, Map<String, Object> values, String mode,
      String routineName, Integer versionNo, Object sentSteps) throws StepsFailed {
    McpRoutineSteps.Outcome outcome = McpRoutineSteps.run(steps, values, runner);
    String executionId = record(new Execution(null, mode, outcome.ok ? STATUS_OK : STATUS_FAILED,
        routineName, versionNo, String.valueOf(sentSteps), outcome.record.toString(), null));
    try {
      if (executionId != null) {
        outcome.body.put(PARAM_EXECUTION_ID, executionId);
      }
      if (routineName != null) {
        outcome.body.put(PARAM_NAME, routineName);
        outcome.body.put(PARAM_VERSION, versionNo);
      }
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine answer", e);
    }
    if (!outcome.ok) {
      throw new StepsFailed(outcome.body);
    }
    return outcome.body;
  }

  /**
   * Stores an execution. A failure to store it does not change what the steps did, so it is
   * logged and the answer simply carries no executionId.
   */
  private String record(Execution execution) {
    try {
      return store.recordExecution(owner.get(), execution);
    } catch (RuntimeException e) {
      log.warn("etendo_routine could not record the execution: {}", e.getMessage());
      return null;
    }
  }

  // ── save / update ───────────────────────────────────────────────────────

  private JSONObject save(JSONObject args) throws Refusal {
    onlyArguments(args, PARAM_NAME, PARAM_DESCRIPTION, PARAM_PARAMS, PARAM_STEPS,
        PARAM_FROM_EXECUTION);
    String name = name(args);
    String description = description(args, true);
    String fromExecution = StringUtils.trimToNull(args.optString(PARAM_FROM_EXECUTION, null));
    if (fromExecution != null && args.has(PARAM_STEPS)) {
      throw McpRoutineSteps.refusal(PARAM_STEPS, "Send either 'steps' or 'fromExecution', not "
          + "both.");
    }
    if (store.find(owner.get(), name) != null) {
      throw McpRoutineSteps.refusal(PARAM_NAME, "You already have a routine named '" + name
          + "'. Use mode update to store a new version of it, or pick another name.");
    }
    JSONObject declarations = McpRoutineParams.declarations(args.opt(PARAM_PARAMS),
        fromExecution != null);
    Object rawSteps = fromExecution == null ? args.opt(PARAM_STEPS)
        : stepsFromExecution(fromExecution, declarations);
    JSONArray steps = checkedDefinition(rawSteps, declarations);
    Routine routine = store.create(owner.get(), name, description, declarations.toString(),
        steps.toString());
    return saved(routine.name, 1, declarations,
        "Saved. Run it with {mode:'run', name:'" + routine.name + "', params:{...}}.");
  }

  private JSONObject update(JSONObject args) throws Refusal {
    onlyArguments(args, PARAM_NAME, PARAM_DESCRIPTION, PARAM_PARAMS, PARAM_STEPS);
    Routine routine = existing(args);
    if (!args.has(PARAM_STEPS)) {
      throw McpRoutineSteps.refusal(PARAM_STEPS, "update stores a complete new version: send "
          + "'steps' and 'params' (describe shows the current ones).");
    }
    String description = args.has(PARAM_DESCRIPTION) ? description(args, true)
        : routine.description;
    JSONObject declarations = McpRoutineParams.declarations(args.opt(PARAM_PARAMS), false);
    JSONArray steps = checkedDefinition(args.opt(PARAM_STEPS), declarations);
    int number = store.addVersion(owner.get(), routine, description, declarations.toString(),
        steps.toString());
    return saved(routine.name, number, declarations, "Version " + number + " is now current; "
        + "older versions stay available with run/describe and 'version'.");
  }

  /**
   * Validates the steps of a routine being stored: their shape, and that every declared
   * parameter is used and every used one declared.
   *
   * @return the steps as they will be stored
   */
  private static JSONArray checkedDefinition(Object rawSteps, JSONObject declarations)
      throws Refusal {
    Set<String> declared = McpRoutineParams.names(declarations);
    Set<String> used = new HashSet<>();
    McpRoutineSteps.parse(rawSteps, declared, used);
    Set<String> unused = new java.util.TreeSet<>(declared);
    unused.removeAll(used);
    if (!unused.isEmpty()) {
      throw McpRoutineSteps.refusal(PARAM_PARAMS, "Parameter(s) " + unused + " are declared but "
          + "no step uses them as '$param.<name>'. Remove them or use them.");
    }
    return (JSONArray) rawSteps;
  }

  /**
   * The steps of a successful inline execution of the caller, with every value a parameter's
   * {@code from} names replaced by {@code $param.<name>}. The {@code from} keys are removed
   * from {@code declarations}.
   */
  private JSONArray stepsFromExecution(String executionId, JSONObject declarations)
      throws Refusal {
    Execution execution = store.findExecution(owner.get(), executionId);
    if (execution == null) {
      throw McpRoutineSteps.refusal(PARAM_FROM_EXECUTION, "You have no execution '" + executionId
          + "'.");
    }
    if (!MODE_EXEC.equals(execution.mode) || !STATUS_OK.equals(execution.status)) {
      throw McpRoutineSteps.refusal(PARAM_FROM_EXECUTION, "Execution '" + executionId + "' is "
          + "not a successful exec (it is a " + execution.mode + " that ended " + execution.status
          + "). Only the steps of an exec that finished ok can become a routine.");
    }
    JSONArray steps = parseArray(execution.steps);
    for (String name : McpRoutineParams.names(declarations)) {
      JSONObject declaration = declarations.optJSONObject(name);
      Object from = declaration.remove(McpRoutineParams.KEY_FROM);
      if (!(from instanceof JSONArray) || ((JSONArray) from).length() == 0) {
        throw McpRoutineSteps.refusal(PARAM_PARAMS, "Parameter '" + name + "' needs 'from': the "
            + "values of the execution it replaces, as ['<stepId>.<path>', ...] (e.g. "
            + "'bp.args.fields.name').");
      }
      JSONArray paths = (JSONArray) from;
      for (int i = 0; i < paths.length(); i++) {
        replaceAt(steps, paths.optString(i), McpRoutineSteps.PARAM_PREFIX + name);
      }
    }
    return steps;
  }

  /** Replaces the value at {@code <stepId>.<path>} of the steps; refuses a path that is absent. */
  private static void replaceAt(JSONArray steps, String path, String replacement)
      throws Refusal {
    List<String> segments = McpRoutineSteps.segments("." + path);
    JSONObject step = null;
    for (int i = 0; !segments.isEmpty() && i < steps.length(); i++) {
      JSONObject candidate = steps.optJSONObject(i);
      if (candidate != null && segments.get(0).equals(candidate.optString(
          McpRoutineSteps.STEP_ID))) {
        step = candidate;
      }
    }
    if (step == null || segments.size() < 2) {
      throw missingPath(path);
    }
    Object parent = step;
    for (int i = 1; i < segments.size() - 1; i++) {
      parent = childOf(parent, segments.get(i));
      if (parent == null) {
        throw missingPath(path);
      }
    }
    String last = segments.get(segments.size() - 1);
    try {
      if (parent instanceof JSONObject && ((JSONObject) parent).has(last)) {
        ((JSONObject) parent).put(last, replacement);
        return;
      }
      if (parent instanceof JSONArray && last.matches("\\d+")
          && Integer.parseInt(last) < ((JSONArray) parent).length()) {
        ((JSONArray) parent).put(Integer.parseInt(last), replacement);
        return;
      }
    } catch (JSONException e) {
      throw new McpToolException("Error replacing a routine value", e);
    }
    throw missingPath(path);
  }

  private static Object childOf(Object parent, String segment) {
    if (parent instanceof JSONObject) {
      return ((JSONObject) parent).opt(segment);
    }
    if (parent instanceof JSONArray && segment.matches("\\d+")) {
      return ((JSONArray) parent).opt(Integer.parseInt(segment));
    }
    return null;
  }

  private static Refusal missingPath(String path) {
    return McpRoutineSteps.refusal(PARAM_PARAMS, "'from' path '" + path + "' is not a value of "
        + "the execution's steps. Use '<stepId>.<path>' as the step was sent, e.g. "
        + "'bp.args.fields.name'.");
  }

  private static JSONObject saved(String name, int version, JSONObject declarations, String hint)
      throws Refusal {
    try {
      JSONObject body = new JSONObject();
      body.put(McpRoutineSteps.KEY_OK, true);
      body.put(PARAM_NAME, name);
      body.put(PARAM_VERSION, version);
      body.put(PARAM_PARAMS, declarations);
      body.put(McpConstants.KEY_HINT, hint);
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine answer", e);
    }
  }

  // ── list / describe / delete / execution ────────────────────────────────

  private JSONObject list() throws Refusal {
    try {
      JSONArray routines = new JSONArray();
      for (Routine routine : store.list(owner.get())) {
        Version current = store.version(routine.id, routine.currentVersion);
        JSONObject entry = new JSONObject();
        entry.put(PARAM_NAME, routine.name);
        entry.put(PARAM_DESCRIPTION, routine.description);
        entry.put(PARAM_PARAMS, current == null ? new JSONObject() : parse(current.params));
        entry.put(KEY_CURRENT_VERSION, routine.currentVersion);
        routines.put(entry);
      }
      JSONObject body = new JSONObject();
      body.put(KEY_ROUTINES, routines);
      body.put(McpConstants.KEY_HINT, routines.length() == 0
          ? "You have no saved routines yet. When you finish a flow with exec, save it "
              + "(fromExecution) to run it again in one call."
          : "Remember these routines and prefer them when a task matches one: one run call "
              + "replaces all its steps. List them again from time to time: they can change.");
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine list", e);
    }
  }

  private JSONObject describe(JSONObject args) throws Refusal {
    onlyArguments(args, PARAM_NAME, PARAM_VERSION);
    Routine routine = existing(args);
    Version version = version(routine, args);
    try {
      JSONObject body = new JSONObject();
      body.put(PARAM_NAME, routine.name);
      body.put(PARAM_DESCRIPTION, routine.description);
      body.put(PARAM_VERSION, version.number);
      body.put(KEY_CURRENT_VERSION, routine.currentVersion);
      body.put("versions", new JSONArray(store.versionNumbers(routine.id)));
      body.put(PARAM_PARAMS, parse(version.params));
      body.put(PARAM_STEPS, parseArray(version.steps));
      body.put("created", version.created);
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine description", e);
    }
  }

  private JSONObject delete(JSONObject args) throws Refusal {
    onlyArguments(args, PARAM_NAME);
    String name = name(args);
    if (!store.delete(owner.get(), name)) {
      throw notFound(name);
    }
    try {
      JSONObject body = new JSONObject();
      body.put(McpRoutineSteps.KEY_OK, true);
      body.put(PARAM_NAME, name);
      body.put(McpConstants.KEY_DETAIL, "Routine '" + name + "' and all its versions were "
          + "deleted. Past executions stay readable with mode execution.");
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine answer", e);
    }
  }

  private JSONObject execution(JSONObject args) throws Refusal {
    onlyArguments(args, PARAM_EXECUTION_ID);
    String executionId = StringUtils.trimToNull(args.optString(PARAM_EXECUTION_ID, null));
    if (executionId == null) {
      throw McpRoutineSteps.refusal(PARAM_EXECUTION_ID, "mode execution needs 'executionId'.");
    }
    Execution execution = store.findExecution(owner.get(), executionId);
    if (execution == null) {
      throw McpRoutineSteps.refusal(PARAM_EXECUTION_ID, "You have no execution '" + executionId
          + "'.");
    }
    try {
      JSONObject body = new JSONObject();
      body.put(PARAM_EXECUTION_ID, execution.id);
      body.put(PARAM_MODE, execution.mode);
      body.put(McpRoutineSteps.KEY_OK, STATUS_OK.equals(execution.status));
      if (execution.routineName != null) {
        body.put(PARAM_NAME, execution.routineName);
        body.put(PARAM_VERSION, execution.version);
      }
      body.put("created", execution.created);
      body.put(PARAM_STEPS, parseArray(execution.result));
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the execution", e);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /** Refuses an argument the mode does not take, naming the ones it does. */
  private static void onlyArguments(JSONObject args, String... allowed) throws Refusal {
    Set<String> accepted = new HashSet<>(List.of(allowed));
    accepted.add(PARAM_MODE);
    Iterator<?> keys = args.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (!accepted.contains(key)) {
        throw McpRoutineSteps.refusal(key, "mode '" + args.optString(PARAM_MODE) + "' does not "
            + "take '" + key + "'. It takes " + List.of(allowed) + ".");
      }
    }
  }

  private static String name(JSONObject args) throws Refusal {
    String name = StringUtils.trimToNull(args.optString(PARAM_NAME, null));
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw McpRoutineSteps.refusal(PARAM_NAME, "'name' must be lowercase letters, digits, '-' "
          + "or '_', starting with a letter or digit (at most 60 characters), e.g. "
          + "'create-customer'.");
    }
    return name;
  }

  private static String description(JSONObject args, boolean required) throws Refusal {
    String description = StringUtils.trimToNull(args.optString(PARAM_DESCRIPTION, null));
    if (description == null && required) {
      throw McpRoutineSteps.refusal(PARAM_DESCRIPTION, "'description' is required: what the "
          + "routine does and when to use it, so you and list can tell routines apart.");
    }
    if (description != null && description.length() > MAX_DESCRIPTION) {
      throw McpRoutineSteps.refusal(PARAM_DESCRIPTION, "'description' has more than "
          + MAX_DESCRIPTION + " characters.");
    }
    return description;
  }

  private Routine existing(JSONObject args) throws Refusal {
    String name = name(args);
    Routine routine = store.find(owner.get(), name);
    if (routine == null) {
      throw notFound(name);
    }
    return routine;
  }

  private Version version(Routine routine, JSONObject args) throws Refusal {
    int number = routine.currentVersion;
    if (args.has(PARAM_VERSION)) {
      Object raw = args.opt(PARAM_VERSION);
      if (!(raw instanceof Integer)) {
        throw McpRoutineSteps.refusal(PARAM_VERSION, "'version' must be an integer.");
      }
      number = (Integer) raw;
    }
    Version version = store.version(routine.id, number);
    if (version == null) {
      throw McpRoutineSteps.refusal(PARAM_VERSION, "Routine '" + routine.name + "' has no "
          + "version " + number + ". Versions: " + store.versionNumbers(routine.id) + ".");
    }
    return version;
  }

  private static Refusal notFound(String name) {
    Refusal refusal = McpRoutineSteps.refusal(PARAM_NAME, "You have no routine named '" + name
        + "'. mode list shows yours.");
    try {
      refusal.body().put(McpConstants.KEY_STATUS, McpConstants.STATUS_NOT_FOUND);
      refusal.body().put(McpConstants.KEY_ERROR, McpConstants.ERROR_NOT_FOUND);
    } catch (JSONException e) {
      throw new McpToolException("Error building a refusal", e);
    }
    return refusal;
  }

  private static JSONObject parse(String json) {
    try {
      return new JSONObject(json);
    } catch (JSONException e) {
      throw new McpToolException("A stored routine could not be read", e);
    }
  }

  private static JSONArray parseArray(String json) {
    try {
      return new JSONArray(json);
    } catch (JSONException e) {
      throw new McpToolException("A stored routine could not be read", e);
    }
  }

  /** A check that refuses the call before anything ran. */
  @FunctionalInterface
  private interface Check<T> {
    T get() throws Refusal;
  }

  /** Runs a check; its refusal says that nothing was run. */
  private static <T> T nothingRun(Check<T> check) throws Refusal {
    try {
      return check.get();
    } catch (Refusal refused) {
      try {
        refused.body().put(McpConstants.KEY_DETAIL,
            refused.body().optString(McpConstants.KEY_DETAIL) + NOTHING_RUN);
      } catch (JSONException cannotHappen) {
        log.debug("Could not mark the refusal as nothing run", cannotHappen);
      }
      throw refused;
    }
  }
}
