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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code etendo_routine} tool (ETP-6236, etendo-ai#51): several calls of the existing tools in
 * one call, each step able to use what an earlier step returned.
 *
 * <p>It owns no business logic. Every step is an ordinary tool call that goes through the router
 * ({@link StepRunner}, which is {@code McpToolRouter#route}), so the unknown-argument guard, the
 * scope check, the spec access check, the handlers and their error envelopes are the very ones a
 * separate call would get. What this class owns is what is new: validating the steps as a whole,
 * substituting {@code $ref} references, ending each step's transaction and reporting the outcome.
 * </p>
 *
 * <p><b>Not atomic, on purpose.</b> A document action commits by itself, so an all-or-nothing
 * routine cannot be promised. Each step commits when it succeeds; the first failure rolls back only
 * that step and stops the routine, and the answer lists what was done and what was not.</p>
 *
 * <p>Phase 1 of the proposal ({@code docs/proposals/mcp-routines.md} in schema_forge): only
 * {@code mode:"exec"}, inline steps. Saved routines, parameters and stored executions come
 * next.</p>
 */
final class McpRoutineTool {

  private static final Logger log = LogManager.getLogger(McpRoutineTool.class);

  /** Hard cap on the steps of one routine; a longer list is refused before anything runs. */
  static final int MAX_STEPS = 20;

  static final String PARAM_MODE = "mode";
  static final String PARAM_STEPS = "steps";
  static final String MODE_EXEC = "exec";

  static final String STEP_ID = "id";
  static final String STEP_TOOL = "tool";
  static final String STEP_ARGS = "args";
  static final String STEP_RETURN_RESULT = "returnResult";
  private static final Set<String> STEP_KEYS = Set.of(STEP_ID, STEP_TOOL, STEP_ARGS,
      STEP_RETURN_RESULT);

  /** A string value of this form is replaced with something an earlier step returned. */
  static final String REF_PREFIX = "$ref:";
  /** Reserved for the parameters of saved routines; refused until they exist. */
  static final String PARAM_PREFIX = "$param.";

  /**
   * The tools a step may call. Reads, plus the writes that add, change or complete a record.
   * Deleting, emailing and anything that reaches outside the ERP stay separate calls the agent
   * decides on one by one; a routine is also not a step of another routine.
   */
  static final List<String> STEP_TOOLS = List.of(
      McpConstants.TOOL_NEO_LIST,
      McpConstants.TOOL_NEO_GET,
      McpConstants.TOOL_NEO_SELECTORS,
      McpConstants.TOOL_NEO_DEFAULTS,
      McpConstants.TOOL_NEO_CREATE,
      McpConstants.TOOL_NEO_UPDATE,
      McpConstants.TOOL_NEO_ACTION);

  private static final Pattern STEP_ID_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,39}");
  private static final Pattern INDEX_PATTERN = Pattern.compile("\\d+");

  private static final String KEY_OK = "ok";
  private static final String KEY_RECORD_ID = "recordId";
  private static final String KEY_DATA = "data";
  private static final String NOTHING_RUN = " Nothing was run.";

  private McpRoutineTool() {
  }

  /** Runs one step: a tool call through the router, under the caller's role and scopes. */
  @FunctionalInterface
  interface StepRunner {

    /**
     * Calls one tool.
     *
     * @param toolName the tool of the step
     * @param args     the step's arguments, references already substituted
     * @return the MCP tool-result content of that call
     * @throws Exception whatever the router lets through (a scope refusal is thrown, not answered)
     */
    // The runner is the router's route(), which reports most failures as content but lets a scope
    // refusal escape; the routine turns each into the step's error
    JSONObject run(String toolName, JSONObject args) throws Exception; // NOSONAR java:S112
  }

  /** One validated step of the call. */
  private static final class Step {
    private final int index;
    private final String id;
    private final String tool;
    private final JSONObject args;
    private final boolean returnResult;

    Step(int index, String id, String tool, JSONObject args, boolean returnResult) {
      this.index = index;
      this.id = id;
      this.tool = tool;
      this.args = args;
      this.returnResult = returnResult;
    }
  }

  /** What a successful step left for the steps after it. */
  private static final class Done {
    private final Step step;
    private final JSONObject body;
    private final Object recordId;

    Done(Step step, JSONObject body) {
      this.step = step;
      this.body = body;
      this.recordId = recordIdOf(body);
    }
  }

  /** A step that could not run, or that ran and failed; carries the error body to report. */
  private static final class StepFailure extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient JSONObject body;

    StepFailure(JSONObject body) {
      super(body.optString(McpConstants.KEY_DETAIL, body.optString(McpConstants.KEY_ERROR)));
      this.body = body;
    }
  }

  // ── Definition ──────────────────────────────────────────────────────────

  /**
   * Builds the tool definition published in {@code tools/list}.
   *
   * @return the definition of {@code etendo_routine}
   */
  static McpToolDefinition definition() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(PARAM_MODE, enumProp("What to do. 'exec' runs the steps sent in 'steps', in order.",
        List.of(MODE_EXEC)));

    Map<String, Object> stepProps = new LinkedHashMap<>();
    stepProps.put(STEP_ID, McpJsonSchema.stringProp("This step's name, unique in the call, "
        + "letters, digits, '_' or '-', starting with a letter. Later steps refer to its result "
        + "as '$ref:<id>'."));
    stepProps.put(STEP_TOOL, enumProp("The tool this step calls.", STEP_TOOLS));
    stepProps.put(STEP_ARGS, McpJsonSchema.objectProp("The arguments of that tool, exactly as "
        + "the tool takes them on its own (e.g. {spec, entity, fields} for etendo_create)."));
    stepProps.put(STEP_RETURN_RESULT, McpJsonSchema.booleanProp("Optional. true returns this "
        + "step's full result in the answer. When no step sets it, only the last step's result "
        + "is returned."));
    Map<String, Object> steps = McpJsonSchema.objectArrayProp("The steps, run in this order, at "
        + "most " + MAX_STEPS + ".", stepProps, List.of(STEP_ID, STEP_TOOL, STEP_ARGS));
    steps.put("minItems", 1);
    steps.put("maxItems", MAX_STEPS);
    props.put(PARAM_STEPS, steps);

    return new McpToolDefinition(McpConstants.TOOL_NEO_ROUTINE, description(),
        buildObjectSchema(props, List.of(PARAM_MODE, PARAM_STEPS)));
  }

  /** The text the agent reads: what the tool does, what it does not, and the shape it answers. */
  private static String description() {
    return "Run several calls of the other tools in ONE call, in order, each step able to use what "
        + "an earlier step returned. Use it for a flow whose steps you already know — e.g. create "
        + "a customer, its address and an invoice for it, then complete the invoice — instead of "
        + "one call per step. Each step is {id, tool, args}: 'tool' is one of " + STEP_TOOLS
        + " and 'args' are that tool's own arguments; every step runs exactly as that tool would "
        + "run alone, with the same permissions, checks and errors. A string value '$ref:<id>' "
        + "anywhere in 'args' is replaced with the record id an earlier step created or read "
        + "(its data.id); '$ref:<id>.<path>' with any value of that step's result, e.g. "
        + "'$ref:agua.data[0].id' for the first row of an etendo_list. The whole string must be "
        + "the reference. NOT atomic: each step commits when it succeeds; the first failing step "
        + "is rolled back and stops the routine. "
        + "Returns {ok:true, steps:[{id, tool, ok:true, recordId}], results:{<id>: <that step's "
        + "result>}} — results carries the steps marked returnResult:true, or only the last one — "
        + "or, on failure, an error whose failedAt names the step, stepError is that step's own "
        + "error, completed lists the steps that are done and stay done (with their recordId) and "
        + "pending the ones that did not run. Fix the cause and send only the failed and pending "
        + "steps again, using the recordIds of the completed ones. At most " + MAX_STEPS
        + " steps; for many records of one kind use etendo_batch. Example — a customer with one "
        + "address: {mode:'exec', steps:[{id:'bp', tool:'etendo_create', args:{spec:'contacts', "
        + "entity:'businessPartner', fields:{name:'Acme'}}}, {id:'addr', tool:'etendo_create', "
        + "args:{spec:'contacts', entity:'locationAddress', parentId:'$ref:bp', "
        + "fields:{country:'106', addressLine1:'Calle Mayor 1', cityName:'Madrid', "
        + "postalCode:'28001'}}}]}.";
  }

  // ── Runtime ─────────────────────────────────────────────────────────────

  /**
   * Handles one {@code etendo_routine} call.
   *
   * @param args   the tool arguments
   * @param runner the router, one tool call per step
   * @return the MCP content: every step's outcome and the selected results, or an error naming the
   *         failed step with what was done and what was not
   */
  static JSONObject handle(JSONObject args, StepRunner runner) {
    List<Step> steps;
    try {
      steps = validate(args);
    } catch (StepFailure refused) {
      try {
        refused.body.put(McpConstants.KEY_DETAIL,
            refused.body.optString(McpConstants.KEY_DETAIL) + NOTHING_RUN);
      } catch (JSONException cannotHappen) {
        log.debug("Could not mark the refusal as nothing run", cannotHappen);
      }
      return McpToolRouter.wrapAsErrorContent(refused.body);
    }
    Map<String, Done> done = new HashMap<>();
    List<Done> completed = new ArrayList<>();
    for (Step step : steps) {
      try {
        Done outcome = runStep(step, done, runner);
        done.put(step.id, outcome);
        completed.add(outcome);
      } catch (StepFailure failed) {
        log.info("etendo_routine stopped at step {} ({}) of {}: {}", step.id, step.tool,
            steps.size(), failed.getMessage());
        return McpToolRouter.wrapAsErrorContent(failure(step, failed.body, completed, steps));
      }
    }
    log.info("etendo_routine ran {} steps", steps.size());
    return McpToolRouter.wrapAsTextContent(success(steps, completed));
  }

  /**
   * Runs one step and ends its transaction: commit on success, roll back on failure.
   *
   * @throws StepFailure when a reference cannot be resolved or the tool answers an error
   */
  private static Done runStep(Step step, Map<String, Done> done, StepRunner runner)
      throws StepFailure {
    JSONObject resolved;
    try {
      resolved = (JSONObject) resolve(copy(step.args), done, step);
    } catch (JSONException e) {
      throw new StepFailure(refusal(STEP_ARGS, "Step '" + step.id + "': its args could not be "
          + "read: " + e.getMessage() + "."));
    }
    JSONObject content;
    try {
      content = runner.run(step.tool, resolved);
    } catch (Exception e) {
      McpActionBatch.rollbackQuietly();
      throw new StepFailure(McpActionBatch.parseBody(
          McpActionBatch.errorBodyOf(e, step.tool)));
    } finally {
      McpActionBatch.ensureAdminMode();
    }
    JSONObject body = McpActionBatch.parseBody(McpActionBatch.textOf(content));
    boolean failed = content == null || content.optBoolean(McpActionBatch.KEY_IS_ERROR, false);
    McpActionBatch.finishTransaction(!failed);
    if (failed) {
      throw new StepFailure(body);
    }
    return new Done(step, body);
  }

  // ── Validation ──────────────────────────────────────────────────────────

  /**
   * Checks the call as a whole before anything runs: the mode, the step count, each step's shape
   * and tool, and that every reference names an earlier step.
   *
   * @return the steps, in order
   * @throws StepFailure a refusal; nothing ran
   */
  private static List<Step> validate(JSONObject args) throws StepFailure {
    String mode = args == null ? null : args.optString(PARAM_MODE, null);
    if (!MODE_EXEC.equals(mode)) {
      throw new StepFailure(refusal(PARAM_MODE, "'mode' must be '" + MODE_EXEC + "'."));
    }
    Object raw = args.opt(PARAM_STEPS);
    if (!(raw instanceof JSONArray) || ((JSONArray) raw).length() == 0) {
      throw new StepFailure(refusal(PARAM_STEPS, "'steps' must be a non-empty array of "
          + "{id, tool, args}."));
    }
    JSONArray array = (JSONArray) raw;
    if (array.length() > MAX_STEPS) {
      throw new StepFailure(refusal(PARAM_STEPS, "'steps' has " + array.length()
          + " steps and the maximum is " + MAX_STEPS + ". Split the flow, or use etendo_batch "
          + "for many records of one kind."));
    }
    List<Step> steps = new ArrayList<>();
    Set<String> earlier = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      Step step = parseStep(array.opt(i), i, earlier);
      checkReferences(step.args, step, earlier);
      earlier.add(step.id);
      steps.add(step);
    }
    return steps;
  }

  private static Step parseStep(Object raw, int index, Set<String> earlier) throws StepFailure {
    String where = "Step at position " + index;
    if (!(raw instanceof JSONObject)) {
      throw new StepFailure(refusal(PARAM_STEPS, where + " is not an object {id, tool, args}."));
    }
    JSONObject json = (JSONObject) raw;
    Iterator<?> keys = json.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (!STEP_KEYS.contains(key)) {
        throw new StepFailure(refusal(PARAM_STEPS, where + " has an unknown key '" + key
            + "'. A step takes only " + STEP_KEYS + "; the tool's arguments go inside 'args'."));
      }
    }
    String id = json.optString(STEP_ID, null);
    if (id == null || !STEP_ID_PATTERN.matcher(id).matches()) {
      throw new StepFailure(refusal(PARAM_STEPS, where + " needs an 'id' of letters, digits, "
          + "'_' or '-', starting with a letter (at most 40 characters)."));
    }
    if (earlier.contains(id)) {
      throw new StepFailure(refusal(PARAM_STEPS, "Step id '" + id + "' is repeated: each step "
          + "needs its own id."));
    }
    String tool = json.optString(STEP_TOOL, null);
    if (!STEP_TOOLS.contains(tool)) {
      throw new StepFailure(refusal(PARAM_STEPS, "Step '" + id + "' calls '" + tool
          + "', which a routine cannot run. A step may call " + STEP_TOOLS + "."));
    }
    Object stepArgs = json.opt(STEP_ARGS);
    if (!(stepArgs instanceof JSONObject)) {
      throw new StepFailure(refusal(PARAM_STEPS, "Step '" + id + "' needs 'args': the "
          + "arguments object of " + tool + "."));
    }
    Object returnResult = json.opt(STEP_RETURN_RESULT);
    if (returnResult != null && !(returnResult instanceof Boolean)) {
      throw new StepFailure(refusal(PARAM_STEPS, "Step '" + id + "': 'returnResult' must be "
          + "true or false."));
    }
    return new Step(index, id, tool, (JSONObject) stepArgs, Boolean.TRUE.equals(returnResult));
  }

  /** Walks the step's arguments and refuses a reference to a step that is not an earlier one. */
  private static void checkReferences(Object value, Step step, Set<String> earlier)
      throws StepFailure {
    if (value instanceof JSONObject) {
      Iterator<?> keys = ((JSONObject) value).keys();
      while (keys.hasNext()) {
        checkReferences(((JSONObject) value).opt(String.valueOf(keys.next())), step, earlier);
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        checkReferences(array.opt(i), step, earlier);
      }
    } else if (value instanceof String) {
      String text = (String) value;
      if (text.startsWith(PARAM_PREFIX)) {
        throw new StepFailure(refusal(PARAM_STEPS, "Step '" + step.id + "' uses '" + text
            + "': parameters belong to saved routines, which this server does not offer yet. "
            + "Send the value itself."));
      }
      if (text.startsWith(REF_PREFIX) && !earlier.contains(targetOf(text))) {
        throw new StepFailure(refusal(PARAM_STEPS, "Step '" + step.id + "' refers to '"
            + targetOf(text) + "' in '" + text + "', which is not an earlier step. A reference "
            + "can only use a step that runs before it" + (earlier.isEmpty() ? "" : ": "
            + earlier) + "."));
      }
    }
  }

  // ── References ──────────────────────────────────────────────────────────

  /** @return the step id a reference names: what follows the prefix, up to '.' or '[' */
  static String targetOf(String reference) {
    String rest = reference.substring(REF_PREFIX.length());
    int end = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '.' || c == '[') {
        end = i;
        break;
      }
    }
    return rest.substring(0, end);
  }

  /** Replaces every reference in {@code value} (a fresh copy) with the value it names. */
  private static Object resolve(Object value, Map<String, Done> done, Step step)
      throws JSONException, StepFailure {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      List<String> keys = new ArrayList<>();
      Iterator<?> it = object.keys();
      while (it.hasNext()) {
        keys.add(String.valueOf(it.next()));
      }
      for (String key : keys) {
        object.put(key, resolve(object.get(key), done, step));
      }
      return object;
    }
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        array.put(i, resolve(array.get(i), done, step));
      }
      return array;
    }
    if (value instanceof String && ((String) value).startsWith(REF_PREFIX)) {
      return valueOf((String) value, done, step);
    }
    return value;
  }

  /**
   * The value a reference names: {@code $ref:<id>} is the step's record id, {@code
   * $ref:<id>.<path>} a value of its result. Path segments are keys, {@code [n]} or {@code .n}
   * array positions.
   */
  private static Object valueOf(String reference, Map<String, Done> done, Step step)
      throws StepFailure {
    String target = targetOf(reference);
    Done source = done.get(target);
    String path = reference.substring(REF_PREFIX.length() + target.length());
    if (path.isEmpty()) {
      if (source.recordId == null) {
        throw new StepFailure(refusal(PARAM_STEPS, "Step '" + step.id + "' uses '" + reference
            + "', but step '" + target + "' (" + source.step.tool + ") returned no record id "
            + "(data.id). Name the value with a path, e.g. '" + reference + ".data[0].id'."));
      }
      return source.recordId;
    }
    Object current = source.body;
    for (String segment : segments(path)) {
      current = child(current, segment);
      if (current == null) {
        throw new StepFailure(refusal(PARAM_STEPS, "Step '" + step.id + "' uses '" + reference
            + "', but the result of step '" + target + "' has nothing at '" + segment
            + "'. Steps before it are done; check that result and send the rest again."));
      }
    }
    return current;
  }

  /** Splits {@code .data[0].id} or {@code .data.0.id} into {@code data, 0, id}. */
  private static List<String> segments(String path) {
    List<String> segments = new ArrayList<>();
    for (String part : path.replace("[", ".").replace("]", "").split("\\.")) {
      if (!part.isEmpty()) {
        segments.add(part);
      }
    }
    return segments;
  }

  private static Object child(Object parent, String segment) {
    if (parent instanceof JSONObject) {
      Object value = ((JSONObject) parent).opt(segment);
      return value == JSONObject.NULL ? null : value;
    }
    if (parent instanceof JSONArray && INDEX_PATTERN.matcher(segment).matches()) {
      Object value = ((JSONArray) parent).opt(Integer.parseInt(segment));
      return value == JSONObject.NULL ? null : value;
    }
    return null;
  }

  /** @return {@code data.id} of a step's result when it is one record's id, else {@code null} */
  private static Object recordIdOf(JSONObject body) {
    JSONObject data = body.optJSONObject(KEY_DATA);
    Object id = data == null ? null : data.opt(STEP_ID);
    return id instanceof String ? id : null;
  }

  private static JSONObject copy(JSONObject args) throws JSONException {
    return new JSONObject(args.toString());
  }

  // ── Answers ─────────────────────────────────────────────────────────────

  private static JSONObject success(List<Step> steps, List<Done> completed) {
    try {
      boolean anyMarked = steps.stream().anyMatch(s -> s.returnResult);
      JSONObject results = new JSONObject();
      for (Done d : completed) {
        if (d.step.returnResult || (!anyMarked && d.step.index == steps.size() - 1)) {
          results.put(d.step.id, d.body);
        }
      }
      JSONObject body = new JSONObject();
      body.put(KEY_OK, true);
      body.put(PARAM_STEPS, summaries(completed));
      body.put("results", results);
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine answer", e);
    }
  }

  private static JSONObject failure(Step step, JSONObject stepError, List<Done> completed,
      List<Step> steps) {
    try {
      JSONObject failedAt = new JSONObject();
      failedAt.put(STEP_ID, step.id);
      failedAt.put("index", step.index);
      failedAt.put(STEP_TOOL, step.tool);
      JSONArray pending = new JSONArray();
      for (Step later : steps.subList(step.index + 1, steps.size())) {
        pending.put(later.id);
      }
      JSONObject body = new JSONObject();
      body.put(KEY_OK, false);
      body.put(McpConstants.KEY_STATUS, stepError.optInt(McpConstants.KEY_STATUS,
          McpConstants.STATUS_UNPROCESSABLE));
      body.put(McpConstants.KEY_ERROR, stepError.optString(McpConstants.KEY_ERROR,
          McpConstants.ERROR_VALIDATION));
      body.put(McpConstants.KEY_DETAIL, "Step '" + step.id + "' (" + step.tool + ", position "
          + step.index + ") failed and was rolled back; " + completed.size() + " earlier step(s) "
          + "are done and stay done, " + pending.length() + " did not run.");
      body.put("failedAt", failedAt);
      body.put("stepError", stepError);
      body.put("completed", summaries(completed));
      body.put("pending", pending);
      body.put(McpConstants.KEY_HINT, "Fix what stepError names and send the failed step and the "
          + "pending ones again. Do not resend the completed steps: they are saved; use their "
          + "recordId instead of a $ref to them.");
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine failure", e);
    }
  }

  private static JSONArray summaries(List<Done> completed) throws JSONException {
    JSONArray summaries = new JSONArray();
    for (Done d : completed) {
      JSONObject summary = new JSONObject();
      summary.put(STEP_ID, d.step.id);
      summary.put(STEP_TOOL, d.step.tool);
      summary.put(KEY_OK, true);
      if (d.recordId != null) {
        summary.put(KEY_RECORD_ID, d.recordId);
      }
      summaries.put(summary);
    }
    return summaries;
  }

  /** @return a 422 validation refusal on {@code field}; nothing was run */
  private static JSONObject refusal(String field, String detail) {
    JSONObject body = new JSONObject();
    try {
      body.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
      body.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
      body.put(McpConstants.PARAM_FIELD, field);
      body.put(McpConstants.KEY_DETAIL, detail);
      body.put(McpConstants.KEY_TOOL, McpConstants.TOOL_NEO_ROUTINE);
    } catch (JSONException e) {
      throw new McpToolException("Error building a refusal", e);
    }
    return body;
  }
}
