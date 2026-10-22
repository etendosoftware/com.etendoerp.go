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
import static com.etendoerp.go.mcp.McpJsonSchema.objectProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringArrayProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringProp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * The {@code etendo_action_batch} tool: one document action over several documents, with one
 * result per document (ETP-5715).
 *
 * <p>It is {@code etendo_action} repeated, not a second implementation of it. Every document goes
 * through the very same {@link ActionRunner} the router uses for a single call
 * ({@code McpToolRouter#handleAction}), so the declared-contract check, the tenant guard, the
 * customization dispatch and the button/process execution are shared, not copied. What this class
 * owns is only what is new: validating the call as a whole, giving each document its own
 * transaction and reporting each outcome.</p>
 *
 * <p><b>Not atomic, on purpose.</b> This mirrors what the SPA's list toolbar does with a
 * selection (one request per record, a failure does not stop the others, a per-record result),
 * and it is the only honest model for document actions: the core process commits by itself.
 * After a failed document the session is rolled back and closed so the next one starts from a
 * clean one, and the administrator mode the router set is put back if a handler lost it.</p>
 *
 * <p>Availability ({@code availableWhen}, ETP-5714) stays advisory exactly as in
 * {@code etendo_action}: nothing here refuses a document because the action does not apply to
 * its state — the core answers, and that answer is the document's result.</p>
 */
final class McpActionBatch {

  private static final Logger log = LogManager.getLogger(McpActionBatch.class);

  /** Hard cap on the documents of one call; a longer list is refused before anything runs. */
  static final int MAX_DOCUMENTS = 50;

  /** The request argument carrying the documents' ids. */
  static final String PARAM_IDS = "ids";
  /** The request argument carrying the action name, the same one {@code etendo_action} reads. */
  static final String PARAM_ACTION = "action";

  /** The MCP content flag that marks an error answer. */
  static final String KEY_IS_ERROR = "isError";
  private static final String KEY_OK = "ok";
  private static final String KEY_RESULT = "result";
  private static final String KEY_RESULTS = "results";
  private static final String KEY_SUMMARY = "summary";
  private static final String KEY_TOTAL = "total";
  private static final String STATUS_SUCCESS = "success";
  private static final String NOTHING_RUN = " Nothing was run.";

  private McpActionBatch() {
  }

  /**
   * Runs one document through the single-document action path, the way the router does.
   */
  @FunctionalInterface
  interface ActionRunner {

    /**
     * Fires the action of one document.
     *
     * @param specName the spec the call addresses
     * @param args     the single-document arguments: {@code entity}, {@code id}, {@code action}
     *                 and optionally {@code parameters}
     * @return the MCP tool-result content of that document
     * @throws Exception whatever the single-document path throws
     */
    // The runner is the router's handleAction, which propagates the checked
    // exceptions NeoButtonActionHelper declares; the batch turns each into the document's error
    JSONObject run(String specName, JSONObject args) throws Exception; // NOSONAR java:S112
  }

  /**
   * Builds the tool definition published in {@code tools/list}.
   *
   * @param specNames the specs the role can reach, offered as the {@code spec} enum
   * @return the definition of {@code etendo_action_batch}
   */
  static McpToolDefinition definition(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_SPEC, enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ENTITY, stringProp(McpConstants.LABEL_ENTITY_NAME));
    props.put(PARAM_ACTION, stringProp(
        "The 'action' value of the button from etendo_schema view:\"actions\" (its field name, "
            + "e.g. 'documentAction'), or a declared action such as 'post', 'unpost' or "
            + "'cloneRecord'. The same value etendo_action takes."));
    Map<String, Object> ids = stringArrayProp("The ids of the documents to run the action on, at "
        + "most " + MAX_DOCUMENTS + ", without repeats. Results come back in this order.");
    ids.put("minItems", 1);
    ids.put("maxItems", MAX_DOCUMENTS);
    ids.put("uniqueItems", Boolean.TRUE);
    props.put(PARAM_IDS, ids);
    props.put(McpConstants.PARAM_PARAMETERS, objectProp(
        "Process parameters, the same for every document, exactly as etendo_action takes them — "
            + "e.g. {\"docAction\": \"CO\"}."));
    return new McpToolDefinition(McpConstants.TOOL_NEO_ACTION_BATCH, description(),
        buildObjectSchema(props, List.of(McpConstants.PARAM_SPEC, McpConstants.PARAM_ENTITY,
            PARAM_ACTION, PARAM_IDS)));
  }

  /** The text the agent reads: what the tool does, what it does not, and the shape it answers. */
  private static String description() {
    return "Run ONE action over several documents of the same entity (up to " + MAX_DOCUMENTS
        + " ids) and get one result per document, in the order sent. It is etendo_action repeated "
        + "for each id: the same actions (documentAction with parameters:{docAction:'CO'} to "
        + "complete, 'RE' to reactivate; post, unpost, cloneRecord; any declared action), the same "
        + "parameters for every document, the same refusals and the same error envelopes. "
        + "NOT atomic: each document runs and commits in its own transaction; a failure rolls back "
        + "only that document and the batch carries on with the next one. Nothing is hidden: read "
        + "summary.failed and every results[].ok before telling the user it worked. "
        + "Returns {summary:{total,succeeded,failed}, atomic:false, results:[{id, ok:true, status, "
        + "result} | {id, ok:false, error}]}, where 'result' is that document's own etendo_action "
        + "answer (e.g. the new id of a clone) and 'error' its own error envelope. Whether the "
        + "action applies to a document is advice only (etendo_schema view:\"actions\" with id): a "
        + "document where it does not apply answers what etendo_action would answer. A malformed "
        + "call (empty ids, more than " + MAX_DOCUMENTS + ", repeated ids, unknown entity or "
        + "action, parameters the action's contract rejects) is refused before anything runs. "
        + "Like the SPA's bulk Reactivate, reactivating (docAction 'RE') a POSTED document whose "
        + "entity declares an unpost action un-posts it first, then reactivates it; that "
        + "document's result lists steps:['unpost','documentAction']. If the reactivation then "
        + "fails the un-post stays done (the result says so under 'partial'). "
        + "This tool never creates documents: creations go through etendo_batch (creates only, "
        + "atomic) or etendo_create. Example — complete three draft purchase orders: "
        + "{spec:'purchase-order', entity:'header', action:'documentAction', "
        + "ids:['<id1>','<id2>','<id3>'], parameters:{docAction:'CO'}}.";
  }

  /**
   * Handles one {@code etendo_action_batch} call.
   *
   * @param specName the spec named by the call, already authorized by the router
   * @param args     the tool arguments
   * @param runner   the single-document action path
   * @return the MCP content: per-document results with a summary, or a refusal when the call is
   *         malformed and nothing ran
   * @throws Exception when a lookup of the spec or entity fails; the router answers it
   */
  static JSONObject handle(String specName, JSONObject args, ActionRunner runner)
      throws Exception {
    McpToolRouterSupport.validateArgs(args, McpConstants.PARAM_SPEC, McpConstants.PARAM_ENTITY,
        PARAM_ACTION, PARAM_IDS);
    List<String> ids = new ArrayList<>();
    JSONObject malformed = collectIds(args.opt(PARAM_IDS), ids);
    if (malformed != null) {
      return McpToolRouter.wrapAsErrorContent(malformed);
    }
    String action = args.getString(PARAM_ACTION);
    JSONObject parameters = args.optJSONObject(McpConstants.PARAM_PARAMETERS);
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity entity = McpToolRouterSupport.findIncludedEntity(spec.getId(),
        args.getString(McpConstants.PARAM_ENTITY));
    JSONObject unknown = refuseUnknownAction(entity, action, parameters);
    if (unknown != null) {
      return McpToolRouter.wrapAsErrorContent(unknown);
    }
    // read before the loop: the entity is detached after the first commitAndClose
    String entityName = entity.getName();
    Call call = new Call(runner, specName, entityName, action, parameters,
        McpActionBatchUnpost.forCall(entity, action, parameters));
    JSONArray results = new JSONArray();
    int succeeded = 0;
    for (String id : ids) {
      JSONObject outcome = runDocument(call, id);
      results.put(outcome);
      succeeded += outcome.optBoolean(KEY_OK) ? 1 : 0;
    }
    log.info("etendo_action_batch spec={} entity={} action={} total={} succeeded={}", specName,
        entityName, action, ids.size(), succeeded);
    return McpToolRouter.wrapAsTextContent(
        response(specName, entityName, action, results, succeeded));
  }

  /**
   * Validates the {@code ids} argument and collects its values.
   *
   * @param raw the {@code ids} value as received
   * @param ids receives the ids, in order, when they are valid
   * @return a refusal body, or {@code null} when the ids are a usable list
   */
  private static JSONObject collectIds(Object raw, List<String> ids) {
    if (!(raw instanceof JSONArray)) {
      return refusal(PARAM_IDS, "'ids' must be an array of document ids.");
    }
    JSONArray array = (JSONArray) raw;
    if (array.length() == 0) {
      return refusal(PARAM_IDS, "'ids' is empty: send at least one document id.");
    }
    if (array.length() > MAX_DOCUMENTS) {
      return refusal(PARAM_IDS, "'ids' has " + array.length() + " ids and the maximum is "
          + MAX_DOCUMENTS + ". Split them into calls of at most " + MAX_DOCUMENTS + ".");
    }
    for (int i = 0; i < array.length(); i++) {
      String id = StringUtils.trimToNull(array.optString(i, null));
      if (id == null || !(array.opt(i) instanceof String)) {
        return refusal(PARAM_IDS, "'ids' position " + i + " is not a document id string.");
      }
      ids.add(id);
    }
    return duplicatesRefusal(ids);
  }

  /** @return a refusal naming the repeated ids, or {@code null} when every id is distinct */
  private static JSONObject duplicatesRefusal(List<String> ids) {
    Set<String> seen = new HashSet<>();
    Set<String> repeated = new LinkedHashSet<>();
    for (String id : ids) {
      if (!seen.add(id)) {
        repeated.add(id);
      }
    }
    return repeated.isEmpty() ? null : refusal(PARAM_IDS, "'ids' repeats " + repeated
        + ". Send each document once: a document run twice in one call would be completed or "
        + "cloned twice.");
  }

  /**
   * Judges the action once for the whole call. An action the entity hides or redirects, or
   * parameters its declared contract rejects, would fail every document identically, so they
   * refuse the call; so does a name that is neither a declared action nor an AD button.
   *
   * @return a refusal body, or {@code null} when the call may run
   */
  private static JSONObject refuseUnknownAction(SFEntity entity, String action,
      JSONObject parameters) throws JSONException {
    NeoActionContract declared = McpDeclaredActions.precheck(entity, action, parameters);
    if (declared == null && NeoButtonActionHelper.findButtonColumn(entity.getId(), action) == null) {
      JSONObject body = refusal(PARAM_ACTION, "Entity '" + entity.getName() + "' has no action '"
          + action + "'.");
      body.put(McpConstants.KEY_HINT, "Use an action from etendo_schema(view:'actions') for this "
          + "entity, exactly as etendo_action takes it." + NOTHING_RUN);
      return body;
    }
    return null;
  }

  /** What is the same for every document of the call. */
  private static final class Call {
    private final ActionRunner runner;
    private final String specName;
    private final String entityName;
    private final String action;
    private final JSONObject parameters;
    /** The un-post-before-reactivate step, or {@code null} when this call never has one. */
    private final McpActionBatchUnpost unpost;

    Call(ActionRunner runner, String specName, String entityName, String action,
        JSONObject parameters, McpActionBatchUnpost unpost) {
      this.runner = runner;
      this.specName = specName;
      this.entityName = entityName;
      this.action = action;
      this.parameters = parameters;
      this.unpost = unpost;
    }
  }

  /**
   * Runs one document and turns whatever happens into its result. A reactivation of a posted
   * document runs the un-post step first, each step in its own transaction.
   *
   * @return {@code {id, ok:true, status, result}} or {@code {id, ok:false, error}}, plus
   *         {@code steps} when the un-post step ran
   */
  private static JSONObject runDocument(Call call, String id) {
    JSONArray steps = new JSONArray();
    JSONObject outcome;
    try {
      outcome = runSteps(call, id, steps);
    } catch (Exception e) {
      log.warn("etendo_action_batch document {} failed: {}", id, e.toString());
      rollbackQuietly();
      outcome = failure(id, parseBody(errorBodyOf(e, McpConstants.TOOL_NEO_ACTION_BATCH)));
    } finally {
      ensureAdminMode();
    }
    return withSteps(outcome, steps);
  }

  /**
   * The un-post step when it applies, then the action itself. A failed un-post is the document's
   * result and the action is never fired. A failed action after a successful un-post cannot undo
   * the un-post: it was committed on its own, exactly as the SPA leaves it.
   */
  private static JSONObject runSteps(Call call, String id, JSONArray steps) throws Exception {
    JSONObject refused = call.unpost == null ? null
        : call.unpost.unpostFirst(call.specName, id, call.action, call.runner, steps);
    if (refused != null) {
      return outcomeOf(id, refused);
    }
    return step(call, id, call.action, call.parameters);
  }

  /** Fires one action of one document through the single-document path and ends its transaction. */
  private static JSONObject step(Call call, String id, String action, JSONObject parameters)
      throws Exception {
    JSONObject content = call.runner.run(call.specName,
        documentArgs(call.entityName, action, parameters, id));
    JSONObject outcome = outcomeOf(id, content);
    finishTransaction(outcome.optBoolean(KEY_OK));
    return outcome;
  }

  /**
   * @return the outcome with the steps that ran, when an extra step ran; and the note that the
   *         committed un-post stays when the action after it failed
   */
  private static JSONObject withSteps(JSONObject outcome, JSONArray steps) {
    if (steps.length() == 0) {
      return outcome;
    }
    try {
      outcome.put(McpActionBatchUnpost.KEY_STEPS, steps);
      if (steps.length() > 1 && !outcome.optBoolean(KEY_OK)) {
        outcome.put(McpActionBatchUnpost.KEY_PARTIAL, McpActionBatchUnpost.PARTIAL_NOTE);
      }
    } catch (JSONException e) {
      throw new McpToolException("Error building a document result", e);
    }
    return outcome;
  }

  /**
   * The arguments of one document: a fresh copy of the parameters each time, because the action
   * path writes into the object it is given (the record id) and a handler may normalise it.
   */
  private static JSONObject documentArgs(String entityName, String action, JSONObject parameters,
      String id) throws JSONException {
    JSONObject docArgs = new JSONObject();
    docArgs.put(McpConstants.PARAM_ENTITY, entityName);
    docArgs.put(McpConstants.PARAM_ID, id);
    docArgs.put(PARAM_ACTION, action);
    if (parameters != null) {
      docArgs.put(McpConstants.PARAM_PARAMETERS, new JSONObject(parameters.toString()));
    }
    return docArgs;
  }

  /** Splits the single-document content into this document's result. */
  private static JSONObject outcomeOf(String id, JSONObject content) throws JSONException {
    JSONObject body = parseBody(textOf(content));
    if (content.optBoolean(KEY_IS_ERROR, false)) {
      return failure(id, body);
    }
    JSONObject outcome = new JSONObject();
    outcome.put(McpConstants.PARAM_ID, id);
    outcome.put(KEY_OK, true);
    outcome.put(McpConstants.KEY_STATUS, body.optString(McpConstants.KEY_PROCESS_RESULT,
        STATUS_SUCCESS));
    outcome.put(KEY_RESULT, body);
    return outcome;
  }

  /** @return the failed result of a document, carrying the error exactly as it was answered */
  private static JSONObject failure(String id, JSONObject error) {
    JSONObject outcome = new JSONObject();
    try {
      outcome.put(McpConstants.PARAM_ID, id);
      outcome.put(KEY_OK, false);
      outcome.put(McpConstants.KEY_ERROR, error);
    } catch (JSONException e) {
      throw new McpToolException("Error building a document result", e);
    }
    return outcome;
  }

  /** @return the text of the first content entry, or an empty string when there is none */
  static String textOf(JSONObject content) {
    JSONArray entries = content == null ? null : content.optJSONArray("content");
    JSONObject first = entries == null ? null : entries.optJSONObject(0);
    return first == null ? "" : first.optString("text", "");
  }

  /** @return the text as a JSON object, or {@code {message:<text>}} when it is plain prose */
  static JSONObject parseBody(String text) {
    try {
      return new JSONObject(text);
    } catch (JSONException notJson) {
      JSONObject prose = new JSONObject();
      try {
        prose.put(McpConstants.KEY_MESSAGE, text);
      } catch (JSONException cannotHappen) {
        // A string under a constant key cannot fail, so this body is never built half-way; the
        // failure is marked by the isError flag, not by the message.
        log.debug("Could not build the message of a prose error body", cannotHappen);
      }
      return prose;
    }
  }

  /**
   * The error body of an exception, the way the router answers the same exception for a single
   * call: a routing refusal keeps its own envelope, a role refusal is 403, the rest is a server
   * error.
   *
   * @param e        what was thrown
   * @param toolName the tool the caller used, named in the envelope
   * @return the error body text
   */
  static String errorBodyOf(Exception e, String toolName) {
    if (e instanceof McpRoutingException) {
      return McpToolResponses.buildRoutingErrorBody((McpRoutingException) e, toolName);
    }
    if (e instanceof SecurityException || e instanceof OBSecurityException) {
      return McpRouterErrorBodies.forbidden(toolName, e.getMessage()).toString();
    }
    return McpToolResponses.buildUnexpectedErrorBody(toolName, e);
  }

  /**
   * Ends the document's transaction: commit a success, roll a failure back. Both close the
   * session, so the next document opens a fresh one.
   */
  static void finishTransaction(boolean succeeded) {
    if (succeeded) {
      OBDal.getInstance().commitAndClose();
    } else {
      rollbackQuietly();
    }
  }

  /** Rolls the current transaction back and closes the session; never throws. */
  static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException rollbackFailure) {
      log.warn("etendo_action_batch rollback did not complete: {}", rollbackFailure.getMessage());
    }
  }

  /**
   * Puts the administrator mode the router opened back if a handler lost it, so a document never
   * runs with less than the one before. A lost mode is a pop too many; one extra push balances
   * the router's own restore at the end of the call.
   */
  private static void ensureAdminMode() {
    OBContext context = OBContext.getOBContext();
    if (context != null && !context.isInAdministratorMode()) {
      log.warn("etendo_action_batch: administrator mode was lost by a document; restoring it");
      OBContext.setAdminMode(true);
    }
  }

  /** @return the tool answer: the call's coordinates, the summary and every document's result */
  private static JSONObject response(String specName, String entityName, String action,
      JSONArray results, int succeeded) throws JSONException {
    int total = results.length();
    JSONObject summary = new JSONObject();
    summary.put(KEY_TOTAL, total);
    summary.put("succeeded", succeeded);
    summary.put("failed", total - succeeded);
    JSONObject body = new JSONObject();
    body.put(McpConstants.PARAM_SPEC, specName);
    body.put(McpConstants.PARAM_ENTITY, entityName);
    body.put(PARAM_ACTION, action);
    body.put(KEY_SUMMARY, summary);
    body.put("atomic", false);
    body.put(KEY_RESULTS, results);
    if (succeeded < total) {
      body.put(McpConstants.KEY_HINT, "Each document ran in its own transaction: the ones with "
          + "ok:true are done and stay done; the ones with ok:false were rolled back by this "
          + "call. Fix the cause named in each error and send only the failed ids again.");
    }
    return body;
  }

  /** @return a 422 validation refusal on {@code field}; nothing was run */
  private static JSONObject refusal(String field, String detail) {
    JSONObject body = new JSONObject();
    try {
      body.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
      body.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
      body.put(McpConstants.PARAM_FIELD, field);
      body.put(McpConstants.KEY_DETAIL, detail + NOTHING_RUN);
      body.put(McpConstants.KEY_TOOL, McpConstants.TOOL_NEO_ACTION_BATCH);
      body.put(McpConstants.KEY_HINT, "Correct the argument named in 'field' and call again. "
          + "Creating documents is not done here: use etendo_batch.");
    } catch (JSONException e) {
      throw new McpToolException("Error building a refusal", e);
    }
    return body;
  }
}
