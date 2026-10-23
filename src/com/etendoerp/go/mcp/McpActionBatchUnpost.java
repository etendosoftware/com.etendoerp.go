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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * The "un-post first" step of a batch reactivation (ETP-5715): what the SPA's bulk bar does with
 * {@code preUnpostActions}, derived from structure instead of a list of windows.
 *
 * <p><b>What the SPA does.</b> For the Reactivate entry of an invoice window the bulk bar, the row
 * kebab and the form kebab run {@code runPreUnpost}: when the row is posted ({@code posted}
 * {@code 'Y'}) it fires the {@code unpost} action first and only then the {@code RE} document
 * action. A failed un-post stops that document (nothing is reactivated) and the next document goes
 * on. When the reactivation is already known to be refused (fiscally accepted and rectified) the
 * SPA skips the un-post too, so a doomed reactivation leaves nothing behind (ETP-5745).</p>
 *
 * <p><b>What decides it here.</b> The step applies to a document when all of these hold: the call
 * is the document-action button with {@code docAction RE}; the entity's customization <em>opts
 * in</em> ({@code NeoHandler#unpostBeforeReactivate}, true only for the sales and purchase invoice
 * headers, the windows whose SPA un-posts: goods documents, returns and journals do not, and
 * neither does the MCP); it also <em>declares</em> an {@code unpost} action; the table has the
 * standard {@code Posted} column (the one {@code AcctServer} and
 * {@code DocumentPostingService.notPostableFailure} require on every posted table) and it is
 * {@code 'Y'} for this record; the record is one the caller's tenant may see (otherwise the call
 * reaches the normal guard and answers its bare 404, with no step to give the record away); and the
 * customization has not declared the reactivation unavailable for this record
 * ({@code NeoHandler#unavailableActions}, the hook that already tells agents a reactivation would be
 * refused).</p>
 *
 * <p><b>One gate, two callers.</b> {@code etendo_action_batch} (per document) and a single-document
 * {@code etendo_action} both go through {@link #unpostFirst}, so they cannot diverge. The hook sits
 * in the MCP router only ({@link #runSingle}); the REST action endpoint and the SPA never reach it,
 * so a client that already un-posts on its own (the SPA) is never un-posted twice.</p>
 */
final class McpActionBatchUnpost {

  /** The action the extra step fires, exactly as {@code etendo_action} takes it. */
  static final String ACTION_UNPOST = "unpost";

  private static final String DOC_ACTION_REACTIVATE = "RE";
  /** The AD document-action button column; it is the same on every document table. */
  private static final String DOC_ACTION_COLUMN = "DocAction";
  /** The AD accounting-status column every posted table carries. */
  private static final String POSTED_COLUMN = "Posted";
  private static final String POSTED_YES = "Y";

  /** Result key listing the steps that ran, present only when the extra step ran. */
  static final String KEY_STEPS = "steps";
  /** Result key explaining that a committed un-post stays after a failed reactivation. */
  static final String KEY_PARTIAL = "partial";
  /** What {@link #KEY_PARTIAL} says. */
  static final String PARTIAL_NOTE = "The un-post step was committed before the "
      + "reactivation was refused and is not undone: the document stays Completed and un-posted. "
      + "Post it again with action 'post' if that is wanted.";

  private final String specId;
  private final String entityName;
  private final String dalEntityName;
  private final String postedProperty;
  private final String reactivateKey;

  /**
   * Builds the per-call decision, resolved once and used for every document.
   *
   * @param specId         the spec id, to reload the entity after each commit
   * @param entityName     the entity name within the spec
   * @param dalEntityName  the DAL entity of the entity's table
   * @param postedProperty the DAL property of the table's {@code Posted} column
   * @param reactivateKey  the availability key of the reactivation ({@code action:RE})
   */
  McpActionBatchUnpost(String specId, String entityName, String dalEntityName,
      String postedProperty, String reactivateKey) {
    this.specId = specId;
    this.entityName = entityName;
    this.dalEntityName = dalEntityName;
    this.postedProperty = postedProperty;
    this.reactivateKey = reactivateKey;
  }

  /**
   * Decides, for the whole call, whether a posted document is un-posted before it is reactivated.
   *
   * @param entity     the entity of the call
   * @param action     the action as typed
   * @param parameters the call's parameters, may be {@code null}
   * @return the step, or {@code null} when this call never un-posts
   */
  static McpActionBatchUnpost forCall(SFEntity entity, String action, JSONObject parameters) {
    if (!isReactivation(entity, action, parameters)
        || !McpDeclaredActions.unpostsBeforeReactivation(entity) || !declaresUnpost(entity)) {
      return null;
    }
    Tab tab = entity.getADTab();
    Table table = tab == null ? null : tab.getTable();
    Entity dal = table == null ? null : ModelProvider.getInstance().getEntityByTableId(table.getId());
    Property posted = dal == null ? null : dal.getPropertyByColumnName(POSTED_COLUMN, false);
    if (posted == null) {
      return null;
    }
    String key = NeoActionAvailability.valueKey(
        McpDeclaredActions.customizationNameOf(entity, action), DOC_ACTION_REACTIVATE);
    return new McpActionBatchUnpost(entity.getETGOSFSpec().getId(), entity.getName(),
        dal.getName(), posted.getName(), key);
  }

  /**
   * Whether the call is a reactivation: the document-action button fired with {@code docAction}
   * {@code RE}, spelled however the agent typed the button.
   *
   * @param entity     the entity of the call
   * @param action     the action as typed
   * @param parameters the call's parameters, may be {@code null}
   * @return {@code true} for a document-action reactivation
   */
  static boolean isReactivation(SFEntity entity, String action, JSONObject parameters) {
    if (!asksForReactivation(parameters)) {
      return false;
    }
    Column button = NeoButtonActionHelper.findButtonColumn(entity.getId(), action);
    return button != null && DOC_ACTION_COLUMN.equalsIgnoreCase(button.getDBColumnName());
  }

  /**
   * The cheap half of {@link #isReactivation}: the parameters ask for {@code docAction RE}. It
   * needs no lookup, so a call that is not a reactivation costs nothing.
   *
   * @param parameters the call's parameters, may be {@code null}
   * @return {@code true} when the parameters carry {@code RE}
   */
  static boolean asksForReactivation(JSONObject parameters) {
    return parameters != null && DOC_ACTION_REACTIVATE.equals(parameters.optString(
        McpConstants.PARAM_DOC_ACTION, parameters.optString(McpActionBatch.PARAM_ACTION, "")));
  }

  /** @return whether the entity's customization declares an {@code unpost} action it may run */
  private static boolean declaresUnpost(SFEntity entity) {
    try {
      return McpDeclaredActions.precheck(entity, ACTION_UNPOST, null) != null;
    } catch (McpRoutingException | JSONException hiddenOrInvalid) {
      return false;
    }
  }

  /**
   * Whether this document must be un-posted before it is reactivated: it is posted, and the
   * customization has not ruled the reactivation out for it.
   *
   * @param id the document id
   * @return {@code true} when the un-post step runs first
   */
  boolean appliesTo(String id) {
    SFEntity fresh = McpToolRouterSupport.findIncludedEntity(specId, entityName);
    if (NeoActionRecordGuard.refusalFor(fresh, id) != null) {
      return false;
    }
    BaseOBObject row = OBDal.getInstance().get(dalEntityName, id);
    if (row == null || !POSTED_YES.equals(row.get(postedProperty))) {
      return false;
    }
    return !McpDeclaredActions.unavailableFor(fresh, row).containsKey(reactivateKey);
  }

  /**
   * The step both callers share: when the document is posted and the gate holds, un-posts it in its
   * own transaction before the reactivation. A successful un-post is committed (it cannot be
   * undone afterwards) and the action is added to {@code steps}; a failed one is rolled back and
   * returned, and the reactivation must not run.
   *
   * @param specName the spec of the call
   * @param id       the document id
   * @param action   the action the caller will fire next, recorded in {@code steps}
   * @param runner   the single-document action path
   * @param steps    receives the steps that run; left empty when the step does not apply
   * @return {@code null} when the reactivation may go on (the step did not apply, or succeeded);
   *         otherwise the failed un-post's MCP content
   * @throws Exception whatever the single-document path throws
   */
  JSONObject unpostFirst(String specName, String id, String action,
      McpActionBatch.ActionRunner runner, JSONArray steps) throws Exception {
    if (!appliesTo(id)) {
      return null;
    }
    steps.put(ACTION_UNPOST);
    JSONObject unpostArgs = new JSONObject().put(McpConstants.PARAM_ENTITY, entityName)
        .put(McpConstants.PARAM_ID, id).put(McpActionBatch.PARAM_ACTION, ACTION_UNPOST);
    JSONObject content = runner.run(specName, unpostArgs);
    boolean succeeded = !content.optBoolean(McpActionBatch.KEY_IS_ERROR, false);
    McpActionBatch.finishTransaction(succeeded);
    if (succeeded) {
      steps.put(action);
      return null;
    }
    return content;
  }

  /**
   * {@code etendo_action} on ONE document: the same step as the batch, then the unchanged action.
   * Only a call asking for {@code docAction RE} is looked at; everything else goes straight to the
   * runner exactly as before.
   *
   * @param specName the spec of the call
   * @param args     the {@code etendo_action} arguments
   * @param runner   the single-document action path
   * @return the action's MCP content, with {@code steps} (and {@code partial}) added only when the
   *         un-post step ran
   * @throws Exception whatever the single-document path throws
   */
  static JSONObject runSingle(String specName, JSONObject args, McpActionBatch.ActionRunner runner)
      throws Exception {
    McpActionBatchUnpost unpost = forSingle(specName, args);
    if (unpost == null) {
      return runner.run(specName, args);
    }
    String id = args.getString(McpConstants.PARAM_ID);
    JSONArray steps = new JSONArray();
    JSONObject refused = unpost.unpostFirst(specName, id,
        args.getString(McpActionBatch.PARAM_ACTION), runner, steps);
    if (refused != null) {
      return annotate(refused, steps);
    }
    JSONObject content;
    try {
      content = runner.run(specName, args);
    } catch (Exception e) {
      if (steps.length() == 0) {
        throw e;
      }
      McpActionBatch.rollbackQuietly();
      return annotate(McpToolRouter.wrapAsErrorContent(
          McpActionBatch.errorBodyOf(e, McpConstants.TOOL_NEO_ACTION)), steps);
    }
    if (steps.length() > 0 && content.optBoolean(McpActionBatch.KEY_IS_ERROR, false)) {
      McpActionBatch.finishTransaction(false);
    }
    return annotate(content, steps);
  }

  /** @return the step for a single call, or {@code null} when the call is not a reactivation */
  private static McpActionBatchUnpost forSingle(String specName, JSONObject args) {
    JSONObject parameters = args == null ? null : args.optJSONObject(McpConstants.PARAM_PARAMETERS);
    String entityName = args == null ? null : args.optString(McpConstants.PARAM_ENTITY, null);
    String action = args == null ? null : args.optString(McpActionBatch.PARAM_ACTION, null);
    if (!asksForReactivation(parameters) || StringUtils.isAnyBlank(entityName, action)
        || StringUtils.isBlank(args.optString(McpConstants.PARAM_ID, null))) {
      return null;
    }
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    return forCall(McpToolRouterSupport.findIncludedEntity(spec.getId(), entityName), action,
        parameters);
  }

  /**
   * Adds {@code steps} (and, when a failure follows a committed un-post, {@code partial}) to a
   * single call's answer, keeping its shape and its error flag. Nothing changes when no extra step
   * ran.
   *
   * <p>The keys are spliced into the body text that was already rendered, not parsed and rendered
   * again: {@code McpResponseSanitizer} guarantees no number is re-parsed on the way out, and a
   * decimal such as {@code 100.10} must reach the caller as written. A body that is not a JSON
   * object (prose) is wrapped under {@code message} instead, where there are no numbers to keep.</p>
   */
  private static JSONObject annotate(JSONObject content, JSONArray steps) throws JSONException {
    if (steps.length() == 0) {
      return content;
    }
    boolean error = content.optBoolean(McpActionBatch.KEY_IS_ERROR, false);
    String text = McpActionBatch.textOf(content).trim();
    StringBuilder extra = new StringBuilder("\"").append(KEY_STEPS).append("\":").append(steps);
    if (error && steps.length() > 1) {
      extra.append(",\"").append(KEY_PARTIAL).append("\":").append(JSONObject.quote(PARTIAL_NOTE));
    }
    String annotated;
    if (text.startsWith("{") && text.endsWith("}")) {
      String inner = text.substring(1, text.length() - 1).trim();
      annotated = "{" + inner + (inner.isEmpty() ? "" : ",") + extra + "}";
    } else {
      annotated = "{\"" + McpConstants.KEY_MESSAGE + "\":" + JSONObject.quote(text) + "," + extra + "}";
    }
    return error ? McpToolRouter.wrapAsErrorContent(annotated)
        : McpToolRouter.wrapAsTextContent(annotated);
  }
}
