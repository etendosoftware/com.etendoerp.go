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

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * The MCP side of handler-declared named actions ({@link NeoHandler#declaredActions}, ETP-5447):
 * listing them in {@code neo_schema({view:"actions"})} and running them from {@code neo_action}
 * with the method the handler declared.
 *
 * <p>Deliberately NOT folded into {@link McpHookExecutor}, for the reason
 * {@link McpTablessReadDispatcher} gives: the router tests mock that class statically, so a
 * decision living there would be stubbed to {@code null} and never exercised. This class holds the
 * decisions and calls only the hook primitives.</p>
 */
final class McpDeclaredActions {

  private static final Logger log = LogManager.getLogger(McpDeclaredActions.class);

  /** Key listing the declared parameters a call is missing, as the report tools name it. */
  static final String KEY_MISSING_PARAMETERS = "missingParameters";

  private McpDeclaredActions() {
  }

  /**
   * The declared actions of the entity's handler, for the catalog.
   *
   * <p>Fail-open to an empty list: the catalog is a discovery path, and a CDI lookup or a
   * declaration that throws must cost the agent the handler entries, never the whole schema call.
   *
   * @return the declared actions; empty when there is no handler or the lookup failed
   */
  static List<NeoActionContract> forCatalog(SFEntity sfEntity, String specName,
      String entityName) {
    try {
      NeoHandler handler = NeoHandlerLookup.byQualifierQuietly(sfEntity.getJavaQualifier());
      return declaredBy(handler, specName, entityName);
    } catch (RuntimeException e) {
      log.warn("Could not read the declared actions of {}/{}: {}", specName, entityName,
          e.getMessage());
      return List.of();
    }
  }

  /**
   * Find the declared contract for {@code actionName} on the handler that will run it.
   *
   * <p>A declaration that throws is logged and treated as "not declared", which leaves
   * {@code neo_action} on the path it took before declarations existed.
   *
   * @return the contract, or {@code null} when the handler does not declare that action
   */
  static NeoActionContract find(NeoHandler handler, String specName, String entityName,
      String actionName) {
    List<NeoActionContract> declared;
    try {
      declared = declaredBy(handler, specName, entityName);
    } catch (RuntimeException e) {
      log.warn("Could not read the declared actions of {}/{}: {}", specName, entityName,
          e.getMessage());
      return null;
    }
    for (NeoActionContract contract : declared) {
      if (contract.getName().equals(actionName)) {
        return contract;
      }
    }
    return null;
  }

  private static List<NeoActionContract> declaredBy(NeoHandler handler, String specName,
      String entityName) {
    if (handler == null) {
      return List.of();
    }
    List<NeoActionContract> declared = handler.declaredActions(specName, entityName);
    return declared != null ? declared : List.of();
  }

  /**
   * Run a declared action: validate its required parameters, then fire the handler's pre-hook
   * with the declared method.
   *
   * <p>Never falls through to the AD button path. A declared action is by definition not a
   * button, so {@code NeoButtonActionHelper} could only answer "Action not found" — which is
   * exactly the misleading error this declaration exists to remove. A pre-hook that declines is
   * reported as what it is: the handler declared an action it did not answer.
   *
   * @param params the MCP {@code parameters} object; never {@code null}
   * @return the MCP result
   */
  static JSONObject run(NeoHandler handler, NeoActionContract contract, String specName,
      String entityName, String recordId, JSONObject params, SFEntity sfEntity)
      throws JSONException {
    JSONObject missing = validateRequired(contract, params);
    if (missing != null) {
      return McpToolRouter.wrapAsErrorContent(missing);
    }
    boolean isGet = NeoActionContract.METHOD_GET.equals(contract.getMethod());
    Map<String, String> query = isGet ? toQueryParams(params) : new HashMap<>();
    NeoContext ctx = McpHookExecutor.buildDeclaredActionHookContext(specName, entityName,
        recordId, contract, params, query, sfEntity);
    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);
    if (result != null) {
      return result;
    }
    return McpToolRouter.wrapAsErrorContent(notHandled(contract));
  }

  /**
   * The IMP-5 envelope for a call that omits a required declared parameter, returned before the
   * handler runs — the same check {@code generate_*} applies to a report's declared parameters.
   *
   * @return the error body, or {@code null} when every required parameter is present
   */
  static JSONObject validateRequired(NeoActionContract contract, JSONObject params)
      throws JSONException {
    JSONArray missing = new JSONArray();
    for (String name : contract.getRequiredParameterNames()) {
      if (isAbsent(params, name)) {
        missing.put(name);
      }
    }
    if (missing.length() == 0) {
      return null;
    }
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
    error.put(McpConstants.KEY_DETAIL,
        "Missing required parameters for action '" + contract.getName() + "'");
    error.put(KEY_MISSING_PARAMETERS, missing);
    error.put(McpConstants.KEY_HINT, "Put them under 'parameters'. neo_schema with "
        + "view:\"actions\" lists this action's parameters with their types and accepted values.");
    return error;
  }

  /** Absent, JSON null, or a blank string: every handler treats all three as "not given". */
  private static boolean isAbsent(JSONObject params, String name) {
    if (!params.has(name) || params.isNull(name)) {
      return true;
    }
    Object value = params.opt(name);
    return value instanceof String && StringUtils.isBlank((String) value);
  }

  /**
   * The parameters flattened to strings for a {@code GET} action's query-param map. A nested
   * object or array is carried as its JSON text; a JSON null is dropped.
   */
  static Map<String, String> toQueryParams(JSONObject params) {
    Map<String, String> query = new HashMap<>();
    for (Iterator<?> it = params.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      if (!params.isNull(key)) {
        query.put(key, String.valueOf(params.opt(key)));
      }
    }
    return query;
  }

  private static JSONObject notHandled(NeoActionContract contract) throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_SERVER_ERROR);
    error.put(McpConstants.KEY_ERROR, McpConstants.ERROR_SERVER);
    error.put(McpConstants.KEY_DETAIL, "Declared action '" + contract.getName()
        + "' was not handled by its handler");
    error.put(McpConstants.KEY_HINT, "The handler declares this action but did not answer it "
        + "for this record. This is a server defect, not a problem with the call: report it "
        + "with neo_feedback instead of retrying.");
    return error;
  }
}
