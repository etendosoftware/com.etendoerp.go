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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * The step engine of {@code etendo_routine} (ETP-6236): validating a list of steps and running it.
 *
 * <p>It owns no business logic. Every step is an ordinary tool call that goes through the router
 * ({@link StepRunner}, which is {@code McpToolRouter#route}), so the unknown-argument guard, the
 * scope check, the spec access check, the handlers and their error envelopes are the very ones a
 * separate call would get. What this class owns is what is new: the shape of the steps, the
 * {@code $ref} and {@code $param} substitution, ending each step's transaction and reporting.</p>
 *
 * <p><b>Not atomic, on purpose.</b> A document action commits by itself, so an all-or-nothing
 * routine cannot be promised. Each step commits when it succeeds; the first failure rolls back only
 * that step and stops the routine, and the answer lists what was done and what was not.</p>
 */
final class McpRoutineSteps {

  private static final Logger log = LogManager.getLogger(McpRoutineSteps.class);

  /** Hard cap on the steps of one routine; a longer list is refused before anything runs. */
  static final int MAX_STEPS = 20;

  static final String STEP_ID = "id";
  static final String STEP_TOOL = "tool";
  static final String STEP_ARGS = "args";
  static final String STEP_RETURN_RESULT = "returnResult";
  private static final Set<String> STEP_KEYS = Set.of(STEP_ID, STEP_TOOL, STEP_ARGS,
      STEP_RETURN_RESULT);

  /** A string value of this form is replaced with something an earlier step returned. */
  static final String REF_PREFIX = "$ref:";
  /** A string value of this form is replaced with a parameter of a saved routine. */
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

  static final String KEY_OK = "ok";
  static final String KEY_RESULT = "result";
  private static final String KEY_RECORD_ID = "recordId";
  private static final String KEY_DATA = "data";
  private static final String FIELD_STEPS = "steps";

  private static final Pattern STEP_ID_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,39}");
  private static final Pattern INDEX_PATTERN = Pattern.compile("\\d+");

  /** Marks an optional parameter that was not sent: the key holding it is dropped. */
  private static final Object ABSENT = new Object();

  private McpRoutineSteps() {
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

  /** A refusal of the call, or of one step: carries the error body to answer. */
  static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient JSONObject body;

    Refusal(JSONObject body) {
      super(body.optString(McpConstants.KEY_DETAIL, body.optString(McpConstants.KEY_ERROR)));
      this.body = body;
    }

    JSONObject body() {
      return body;
    }
  }

  /** One validated step. */
  static final class Step {
    final int index;
    final String id;
    final String tool;
    final JSONObject args;
    final boolean returnResult;

    Step(int index, String id, String tool, JSONObject args, boolean returnResult) {
      this.index = index;
      this.id = id;
      this.tool = tool;
      this.args = args;
      this.returnResult = returnResult;
    }
  }

  /** What a run produced: the answer for the agent and the record of every step that ran. */
  static final class Outcome {
    final boolean ok;
    final JSONObject body;
    final JSONArray record;

    Outcome(boolean ok, JSONObject body, JSONArray record) {
      this.ok = ok;
      this.body = body;
      this.record = record;
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

  // ── Validation ──────────────────────────────────────────────────────────

  /**
   * Checks a list of steps as a whole: the count, each step's shape and tool, that every
   * reference names an earlier step and that every parameter is one of {@code declared}.
   *
   * @param raw      the {@code steps} value as received or stored
   * @param declared the parameters the steps may use; empty for inline steps
   * @param used     receives the parameter names the steps use
   * @return the steps, in order
   * @throws Refusal naming what is wrong
   */
  static List<Step> parse(Object raw, Set<String> declared, Set<String> used) throws Refusal {
    if (!(raw instanceof JSONArray) || ((JSONArray) raw).length() == 0) {
      throw refusal(FIELD_STEPS, "'steps' must be a non-empty array of {id, tool, args}.");
    }
    JSONArray array = (JSONArray) raw;
    if (array.length() > MAX_STEPS) {
      throw refusal(FIELD_STEPS, "'steps' has " + array.length() + " steps and the maximum is "
          + MAX_STEPS + ". Split the flow, or use etendo_batch for many records of one kind.");
    }
    List<Step> steps = new ArrayList<>();
    Set<String> earlier = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      Step step = parseStep(array.opt(i), i, earlier);
      checkValues(step.args, step, earlier, declared, used);
      earlier.add(step.id);
      steps.add(step);
    }
    return steps;
  }

  private static Step parseStep(Object raw, int index, Set<String> earlier) throws Refusal {
    String where = "Step at position " + index;
    if (!(raw instanceof JSONObject)) {
      throw refusal(FIELD_STEPS, where + " is not an object {id, tool, args}.");
    }
    JSONObject json = (JSONObject) raw;
    Iterator<?> keys = json.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (!STEP_KEYS.contains(key)) {
        throw refusal(FIELD_STEPS, where + " has an unknown key '" + key + "'. A step takes only "
            + STEP_KEYS + "; the tool's arguments go inside 'args'.");
      }
    }
    String id = json.optString(STEP_ID, null);
    if (id == null || !STEP_ID_PATTERN.matcher(id).matches()) {
      throw refusal(FIELD_STEPS, where + " needs an 'id' of letters, digits, '_' or '-', "
          + "starting with a letter (at most 40 characters).");
    }
    if (earlier.contains(id)) {
      throw refusal(FIELD_STEPS, "Step id '" + id + "' is repeated: each step needs its own id.");
    }
    String tool = json.optString(STEP_TOOL, null);
    if (tool == null || !STEP_TOOLS.contains(tool)) {
      throw refusal(FIELD_STEPS, "Step '" + id + "' calls '" + tool + "', which a routine cannot "
          + "run. A step may call " + STEP_TOOLS + ".");
    }
    Object stepArgs = json.opt(STEP_ARGS);
    if (!(stepArgs instanceof JSONObject)) {
      throw refusal(FIELD_STEPS, "Step '" + id + "' needs 'args': the arguments object of " + tool
          + ".");
    }
    Object returnResult = json.opt(STEP_RETURN_RESULT);
    if (returnResult != null && !(returnResult instanceof Boolean)) {
      throw refusal(FIELD_STEPS, "Step '" + id + "': 'returnResult' must be true or false.");
    }
    return new Step(index, id, tool, (JSONObject) stepArgs, Boolean.TRUE.equals(returnResult));
  }

  /** Walks a step's arguments checking every {@code $ref} and {@code $param} value. */
  private static void checkValues(Object value, Step step, Set<String> earlier,
      Set<String> declared, Set<String> used) throws Refusal {
    if (value instanceof JSONObject) {
      Iterator<?> keys = ((JSONObject) value).keys();
      while (keys.hasNext()) {
        checkValues(((JSONObject) value).opt(String.valueOf(keys.next())), step, earlier,
            declared, used);
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        checkValues(array.opt(i), step, earlier, declared, used);
      }
    } else if (value instanceof String) {
      checkString((String) value, step, earlier, declared, used);
    }
  }

  private static void checkString(String text, Step step, Set<String> earlier,
      Set<String> declared, Set<String> used) throws Refusal {
    if (text.startsWith(PARAM_PREFIX)) {
      String name = text.substring(PARAM_PREFIX.length());
      if (!declared.contains(name)) {
        throw refusal(FIELD_STEPS, "Step '" + step.id + "' uses '" + text + "', which is not a "
            + "declared parameter" + (declared.isEmpty()
                ? ": inline steps take no parameters (save the routine to declare them)."
                : ". Declared: " + declared + "."));
      }
      used.add(name);
    } else if (text.startsWith(REF_PREFIX) && !earlier.contains(targetOf(text))) {
      throw refusal(FIELD_STEPS, "Step '" + step.id + "' refers to '" + targetOf(text) + "' in '"
          + text + "', which is not an earlier step. A reference can only use a step that runs "
          + "before it" + (earlier.isEmpty() ? "" : ": " + earlier) + ".");
    }
  }

  // ── Running ─────────────────────────────────────────────────────────────

  /**
   * Runs the steps in order, each in its own transaction, and stops at the first failure.
   *
   * @param steps  the validated steps
   * @param params the parameter values, already validated; empty for inline steps
   * @param runner the router, one tool call per step
   * @return the answer and the record of every step that ran
   */
  static Outcome run(List<Step> steps, Map<String, Object> params, StepRunner runner) {
    Map<String, Done> done = new HashMap<>();
    List<Done> completed = new ArrayList<>();
    JSONArray record = new JSONArray();
    for (Step step : steps) {
      try {
        Done outcome = runStep(step, done, params, runner);
        done.put(step.id, outcome);
        completed.add(outcome);
        record.put(recordEntry(step, true, outcome.body));
      } catch (Refusal failed) {
        log.info("etendo_routine stopped at step {} ({}) of {}: {}", step.id, step.tool,
            steps.size(), failed.getMessage());
        record.put(recordEntry(step, false, failed.body));
        return new Outcome(false, failure(step, failed.body, completed, steps), record);
      }
    }
    log.info("etendo_routine ran {} steps", steps.size());
    return new Outcome(true, success(steps, completed), record);
  }

  /**
   * Runs one step and ends its transaction: commit on success, roll back on failure.
   *
   * @throws Refusal when a reference cannot be resolved or the tool answers an error
   */
  private static Done runStep(Step step, Map<String, Done> done, Map<String, Object> params,
      StepRunner runner) throws Refusal {
    JSONObject resolved;
    try {
      resolved = (JSONObject) resolve(new JSONObject(step.args.toString()), done, params, step);
    } catch (JSONException e) {
      throw refusal(STEP_ARGS, "Step '" + step.id + "': its args could not be read: "
          + e.getMessage() + ".");
    }
    JSONObject content;
    try {
      content = runner.run(step.tool, resolved);
    } catch (Exception e) {
      McpActionBatch.rollbackQuietly();
      throw new Refusal(McpActionBatch.parseBody(McpActionBatch.errorBodyOf(e, step.tool)));
    } finally {
      McpActionBatch.ensureAdminMode();
    }
    JSONObject body = McpActionBatch.parseBody(McpActionBatch.textOf(content));
    boolean failed = content == null || content.optBoolean(McpActionBatch.KEY_IS_ERROR, false);
    McpActionBatch.finishTransaction(!failed);
    if (failed) {
      throw new Refusal(body);
    }
    return new Done(step, body);
  }

  /** @return the stored record of one step: its result, or its error */
  private static JSONObject recordEntry(Step step, boolean ok, JSONObject body) {
    try {
      JSONObject entry = new JSONObject();
      entry.put(STEP_ID, step.id);
      entry.put(STEP_TOOL, step.tool);
      entry.put(KEY_OK, ok);
      entry.put(ok ? KEY_RESULT : McpConstants.KEY_ERROR, body);
      return entry;
    } catch (JSONException e) {
      throw new McpToolException("Error recording a routine step", e);
    }
  }

  // ── References and parameters ───────────────────────────────────────────

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

  /**
   * Replaces every reference and parameter in {@code value} (a fresh copy). A key whose value is
   * an optional parameter that was not sent is dropped, as if the step never had it.
   */
  private static Object resolve(Object value, Map<String, Done> done, Map<String, Object> params,
      Step step) throws JSONException, Refusal {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      List<String> keys = new ArrayList<>();
      Iterator<?> it = object.keys();
      while (it.hasNext()) {
        keys.add(String.valueOf(it.next()));
      }
      for (String key : keys) {
        Object resolved = resolve(object.get(key), done, params, step);
        if (resolved == ABSENT) {
          object.remove(key);
        } else {
          object.put(key, resolved);
        }
      }
      return object;
    }
    if (value instanceof JSONArray) {
      JSONArray source = (JSONArray) value;
      JSONArray array = new JSONArray();
      for (int i = 0; i < source.length(); i++) {
        Object resolved = resolve(source.get(i), done, params, step);
        if (resolved != ABSENT) {
          array.put(resolved);
        }
      }
      return array;
    }
    if (value instanceof String) {
      String text = (String) value;
      if (text.startsWith(REF_PREFIX)) {
        return valueOf(text, done, step);
      }
      if (text.startsWith(PARAM_PREFIX)) {
        String name = text.substring(PARAM_PREFIX.length());
        return params.containsKey(name) ? params.get(name) : ABSENT;
      }
    }
    return value;
  }

  /**
   * The value a reference names: {@code $ref:<id>} is the step's record id, {@code
   * $ref:<id>.<path>} a value of its result. Path segments are keys, {@code [n]} or {@code .n}
   * array positions.
   */
  private static Object valueOf(String reference, Map<String, Done> done, Step step)
      throws Refusal {
    String target = targetOf(reference);
    Done source = done.get(target);
    String path = reference.substring(REF_PREFIX.length() + target.length());
    if (path.isEmpty()) {
      if (source.recordId == null) {
        throw refusal(FIELD_STEPS, "Step '" + step.id + "' uses '" + reference + "', but step '"
            + target + "' (" + source.step.tool + ") returned no record id (data.id). Name the "
            + "value with a path, e.g. '" + reference + ".data[0].id'.");
      }
      return source.recordId;
    }
    Object current = source.body;
    for (String segment : segments(path)) {
      current = child(current, segment);
      if (current == null) {
        throw refusal(FIELD_STEPS, "Step '" + step.id + "' uses '" + reference + "', but the "
            + "result of step '" + target + "' has nothing at '" + segment + "'. Steps before it "
            + "are done; check that result and send the rest again.");
      }
    }
    return current;
  }

  /** Splits {@code .data[0].id} or {@code .data.0.id} into {@code data, 0, id}. */
  static List<String> segments(String path) {
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
      body.put(FIELD_STEPS, summaries(completed));
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

  /** @return a 422 validation refusal of {@code etendo_routine} on {@code field} */
  static Refusal refusal(String field, String detail) {
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
    return new Refusal(body);
  }
}
