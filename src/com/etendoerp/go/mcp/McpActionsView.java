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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Pure (DAL-free) re-shaper for {@code neo_schema({view:"actions"})} (IMP-6).
 *
 * <p>A full {@code neo_schema} dump can carry ~97 fields for a compliance-heavy window, most of
 * which are irrelevant to an agent that only wants to know which buttons/processes it can invoke.
 * The {@code type:"button"} fields are already fully described inline by
 * {@link McpSchemaFieldBuilder#buildSchemaFieldsArray} (name, label, {@code action},
 * {@code processType}/{@code processName}/{@code processId}, and either
 * {@code invokeVia:"neo_action"} or {@code invokable:false} + {@code notInvokableReason}) — this
 * view just filters the already-built field array down to those, no extra DAL access needed.
 *
 * <p><b>IMP-21:</b> the catalog stays complete — every button the window has is listed, including
 * the ones curation put out of scope — but it no longer implies they are all callable. {@code
 * invokableCount} sits next to {@code actionCount} so an agent sees the split before reading the
 * array: on sales-invoice most of the 22 are not callable.
 *
 * <p>No view / anything other than {@code "actions"} is a no-op — the caller keeps returning the
 * full schema, unchanged.
 */
final class McpActionsView {

  private McpActionsView() {
  }

  static final String PARAM_VIEW = "view";
  static final String VIEW_ACTIONS = "actions";
  static final String TYPE_BUTTON = "button";
  static final String KEY_ACTIONS = "actions";
  static final String KEY_INVOKABLE_COUNT = "invokableCount";

  /** @return {@code true} when {@code view} requests the actions-only projection. */
  static boolean isActionsView(String view) {
    return VIEW_ACTIONS.equalsIgnoreCase(view);
  }

  /**
   * Filters a fully-built schema {@code fields} array down to its {@code type:"button"} entries.
   *
   * @param fields the field array built by {@link McpSchemaFieldBuilder#buildSchemaFieldsArray}
   * @return a new array containing only the button (action) fields, in their original order
   */
  static JSONArray apply(JSONArray fields) throws JSONException {
    JSONArray actions = new JSONArray();
    if (fields == null) {
      return actions;
    }
    for (int i = 0; i < fields.length(); i++) {
      JSONObject field = fields.getJSONObject(i);
      if (TYPE_BUTTON.equals(field.optString("type", null))) {
        actions.put(field);
      }
    }
    return actions;
  }

  /**
   * Builds the {@code neo_schema({view:"actions"})} response shape: {@code {spec, entity,
   * actions, actionCount, invokableCount}}, dropping the full field dump.
   */
  static JSONObject buildResponse(String specName, String entityName, JSONArray fields)
      throws JSONException {
    return buildResponse(specName, entityName, fields, Map.of(), null);
  }

  /**
   * The same response for a window entity whose customization also declares actions (ETP-5558):
   * the AD buttons first, then the declared actions, in one catalogue.
   *
   * <p>On a window entity both are real — {@code documentAction} completes the invoice, and
   * {@code registerPayment} pays it — so neither replaces the other (a report spec, where the AD
   * tab only gates the role, still uses {@link #buildDeclaredResponse}). {@code MCP_CONFIG.actions}
   * shapes the buttons: a hidden one is left out, a redirected one stays listed, not invokable,
   * carrying {@code useInstead}. The declared contracts arrive already filtered, and each counts as
   * invokable.</p>
   *
   * @param specName   the spec
   * @param entityName the entity
   * @param fields     the full schema field array
   * @param declared   the actions the customization declares, minus the hidden ones
   * @param config     the entity's {@code MCP_CONFIG.actions}, or {@code null} for none
   * @return the response
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject buildResponse(String specName, String entityName, JSONArray fields,
      Map<String, NeoActionContract> declared, McpActionsSection.View config)
      throws JSONException {
    return buildResponse(specName, entityName, fields, declared, config, Set.of());
  }

  /**
   * Same, also leaving out the buttons the customization excludes from agents
   * ({@code NeoHandler#agentExcludedActions()}, ETP-5558) — {@code neo_action} refuses them.
   *
   * @param excluded the excluded action names
   */
  @SuppressWarnings("java:S107") // the catalogue's inputs; a holder would only rename them
  static JSONObject buildResponse(String specName, String entityName, JSONArray fields,
      Map<String, NeoActionContract> declared, McpActionsSection.View config,
      Set<String> excluded) throws JSONException {
    JSONObject response = new JSONObject();
    response.put("spec", specName);
    response.put("entity", entityName);
    JSONArray actions = apply(applyConfig(fields, config, excluded));
    for (NeoActionContract contract : declared.values()) {
      actions.put(contract.toJson());
    }
    withdrawAllIfUnusable(actions, config);
    response.put(KEY_ACTIONS, actions);
    response.put("actionCount", actions.length());
    response.put(KEY_INVOKABLE_COUNT, countInvokable(actions));
    return response;
  }

  /**
   * Shape the AD buttons of a schema field array the way {@code MCP_CONFIG.actions} and the
   * customization's agent-excluded actions say (ETP-5558), leaving every other field untouched.
   *
   * <p>Every projection of {@code neo_schema} that describes a button goes through here —
   * {@code view:"actions"}, {@code view:"full"} and its {@code fields:[…]} whitelist — so they
   * cannot disagree. Before this, only the actions view was shaped: a blind agent read the full
   * view, found {@code aPRMProcessPayment} still offering Void, and offered it to its user. A
   * hidden or excluded button is left out, a redirected one is withdrawn with {@code useInstead},
   * a narrowed one keeps only its allowed {@code actionValues}, and an unusable configuration
   * withdraws every button. A button is matched by its field name and by its DB column, the two
   * names {@code neo_action} fires it by.</p>
   *
   * @param fields   the full schema field array; its kept buttons are shaped in place
   * @param config   the entity's {@code MCP_CONFIG.actions}, or {@code null} for none
   * @param excluded the action names the customization keeps for people only
   * @return a new array, in the original order
   * @throws JSONException if the JSON cannot be built
   */
  static JSONArray applyConfig(JSONArray fields, McpActionsSection.View config,
      Set<String> excluded) throws JSONException {
    JSONArray shaped = new JSONArray();
    if (fields == null) {
      return shaped;
    }
    Set<String> keptForPeople = excluded != null ? excluded : Set.of();
    for (int i = 0; i < fields.length(); i++) {
      JSONObject field = fields.getJSONObject(i);
      if (!TYPE_BUTTON.equals(field.optString("type", null))) {
        shaped.put(field);
      } else if (shapeButton(field, config, keptForPeople)) {
        shaped.put(field);
      }
    }
    return shaped;
  }

  /** @return {@code false} when the button must be left out; otherwise shapes it in place */
  private static boolean shapeButton(JSONObject button, McpActionsSection.View config,
      Set<String> excluded) throws JSONException {
    List<String> names = namesOf(button);
    for (String name : names) {
      if (excluded.contains(name) || (config != null && config.isHidden(name))) {
        return false;
      }
    }
    if (config == null) {
      return true;
    }
    for (String name : names) {
      String instead = config.redirectOf(name);
      if (instead != null) {
        redirect(button, instead, config.getRedirectReason());
        break;
      }
    }
    for (String name : names) {
      Set<String> allowed = config.allowedValuesOf(name);
      if (allowed != null) {
        narrowValues(button, allowed);
        break;
      }
    }
    if (config.isUnusable()) {
      withdraw(button, "Not run through MCP: " + config.getReason());
    }
    return true;
  }

  /** The names a button is known by: its field name, then its DB column. */
  private static List<String> namesOf(JSONObject button) {
    List<String> names = new ArrayList<>(2);
    for (String key : List.of("name", "column")) {
      String name = button.optString(key, null);
      if (name != null && !names.contains(name)) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Builds the actions catalog for an entity whose handler declares named actions (ETP-5468) —
   * the same {@code {spec, entity, actions, actionCount, invokableCount}} shape as
   * {@link #buildResponse}, but each entry is a declared contract with a JSON Schema for its
   * {@code parameters} instead of an AD button column. Every declared action is invokable.
   *
   * @param specName   the spec
   * @param entityName the entity
   * @param contracts  the handler's declared actions
   * @return the response
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject buildDeclaredResponse(String specName, String entityName,
      Map<String, NeoActionContract> contracts) throws JSONException {
    return buildDeclaredResponse(specName, entityName, contracts, null);
  }

  /**
   * {@link #buildDeclaredResponse(String, String, Map)} that also reports an unusable
   * {@code MCP_CONFIG} (ETP-5558): {@code neo_action} then refuses every action, so none is listed
   * as invokable.
   *
   * @param config the entity's {@code MCP_CONFIG.actions}, or {@code null} for none
   */
  static JSONObject buildDeclaredResponse(String specName, String entityName,
      Map<String, NeoActionContract> contracts, McpActionsSection.View config)
      throws JSONException {
    JSONObject response = new JSONObject();
    response.put("spec", specName);
    response.put("entity", entityName);
    JSONArray actions = new JSONArray();
    for (NeoActionContract contract : contracts.values()) {
      actions.put(contract.toJson());
    }
    withdrawAllIfUnusable(actions, config);
    response.put(KEY_ACTIONS, actions);
    response.put("actionCount", actions.length());
    response.put(KEY_INVOKABLE_COUNT, countInvokable(actions));
    response.put("hint", "Call neo_action with this spec and entity, id = the record each action "
        + "acts on (its idDescription says which), action = one of the names above and "
        + "parameters matching its schema. Undeclared or mistyped parameters are refused with 422 "
        + "before anything runs.");
    return response;
  }

  /**
   * Keep only the {@code actionValues} {@code MCP_CONFIG.actions.values} offers for this button
   * (ETP-5558): the others are what the UI's own button never sends.
   */
  private static void narrowValues(JSONObject button, Set<String> allowed) throws JSONException {
    JSONArray values = allowed == null ? null
        : button.optJSONArray(McpConstants.KEY_ACTION_VALUES);
    if (values == null) {
      return;
    }
    JSONArray kept = new JSONArray();
    for (int i = 0; i < values.length(); i++) {
      JSONObject entry = values.optJSONObject(i);
      if (entry != null && allowed.contains(entry.optString("value", null))) {
        kept.put(entry);
      }
    }
    button.put(McpConstants.KEY_ACTION_VALUES, kept);
  }

  /** Mark a button as one to leave for {@code instead}: listed, not invokable, and why. */
  private static void redirect(JSONObject button, String instead, String reason)
      throws JSONException {
    withdraw(button, "Not run through MCP: " + reason + ". Use '" + instead
        + "' (listed below) instead.");
    button.put("useInstead", instead);
  }

  /**
   * An unusable {@code MCP_CONFIG} makes {@code neo_action} refuse every action of the entity
   * (ETP-5558), so the catalogue must not call any of them invokable.
   */
  private static void withdrawAllIfUnusable(JSONArray actions, McpActionsSection.View config)
      throws JSONException {
    if (config == null || !config.isUnusable()) {
      return;
    }
    for (int i = 0; i < actions.length(); i++) {
      withdraw(actions.getJSONObject(i), "Not run through MCP: " + config.getReason());
    }
  }

  private static void withdraw(JSONObject action, String reason) throws JSONException {
    action.remove(McpSchemaFieldBuilder.KEY_INVOKE_VIA);
    action.put(McpSchemaFieldBuilder.KEY_INVOKABLE, false);
    action.put(McpSchemaFieldBuilder.KEY_NOT_INVOKABLE_REASON, reason);
  }

  /** @return how many of the catalog's actions {@code neo_action} can actually run (IMP-21). */
  private static int countInvokable(JSONArray actions) throws JSONException {
    int invokable = 0;
    for (int i = 0; i < actions.length(); i++) {
      if (actions.getJSONObject(i).has(McpSchemaFieldBuilder.KEY_INVOKE_VIA)) {
        invokable++;
      }
    }
    return invokable;
  }
}
