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

import java.util.HashSet;
import java.util.List;
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
 * <p><b>ETP-5447:</b> the entity handler's declared named actions
 * ({@code NeoHandler#declaredActions}) are appended as {@code source:"handler"} entries, each with
 * its HTTP method and a JSON-schema {@code parameters} object built by the same renderer as the
 * {@code generate_*} report tools. They are otherwise invisible: they exist only inside the
 * handler's pre-hook. A declared name that collides with an AD button replaces the button entry,
 * because {@code neo_action} runs the declared action for that name.
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
  /** Where a catalog entry comes from; only handler-declared entries carry it (ETP-5447). */
  static final String KEY_SOURCE = "source";
  /** {@link #KEY_SOURCE} value of a handler-declared action. */
  static final String SOURCE_HANDLER = "handler";
  /** Marks a declared action that replaced an AD button entry of the same name. */
  static final String KEY_SHADOWS = "shadows";
  private static final String KEY_ACTION = "action";
  private static final String KEY_NAME = "name";

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
    return buildResponse(specName, entityName, fields, List.of());
  }

  /**
   * Same as {@link #buildResponse(String, String, JSONArray)}, plus the handler's declared named
   * actions (ETP-5447).
   *
   * <p>Button entries come first, in field order, then the declared actions in declaration order.
   * A button whose {@code action} or {@code name} equals a declared name is dropped, and the
   * declared entry carries {@code shadows:"button"}: {@code neo_action} runs the declared action
   * for that name, so listing both would describe a button that can no longer be reached.
   *
   * @param declared the handler's declared actions; {@code null} or empty for none
   */
  static JSONObject buildResponse(String specName, String entityName, JSONArray fields,
      List<NeoActionContract> declared) throws JSONException {
    List<NeoActionContract> declaredActions = declared != null ? declared : List.of();
    Set<String> declaredNames = new HashSet<>();
    for (NeoActionContract action : declaredActions) {
      declaredNames.add(action.getName());
    }
    JSONArray actions = new JSONArray();
    Set<String> shadowed = new HashSet<>();
    JSONArray buttons = apply(fields);
    for (int i = 0; i < buttons.length(); i++) {
      JSONObject button = buttons.getJSONObject(i);
      String collision = collidingName(button, declaredNames);
      if (collision != null) {
        shadowed.add(collision);
      } else {
        actions.put(button);
      }
    }
    for (NeoActionContract action : declaredActions) {
      actions.put(toCatalogEntry(action, shadowed.contains(action.getName())));
    }

    JSONObject response = new JSONObject();
    response.put("spec", specName);
    response.put("entity", entityName);
    response.put(KEY_ACTIONS, actions);
    response.put("actionCount", actions.length());
    response.put(KEY_INVOKABLE_COUNT, countInvokable(actions));
    return response;
  }

  /** @return the declared name this button collides with, or {@code null} when none. */
  private static String collidingName(JSONObject button, Set<String> declaredNames) {
    String action = button.optString(KEY_ACTION, null);
    if (action != null && declaredNames.contains(action)) {
      return action;
    }
    String name = button.optString(KEY_NAME, null);
    return name != null && declaredNames.contains(name) ? name : null;
  }

  /**
   * One catalog entry for a declared action. Always invokable: the handler declared that it
   * answers it, which is the whole point of the declaration.
   */
  private static JSONObject toCatalogEntry(NeoActionContract action, boolean shadowsButton)
      throws JSONException {
    JSONObject entry = new JSONObject();
    entry.put(KEY_NAME, action.getName());
    entry.put(KEY_ACTION, action.getName());
    entry.put(KEY_SOURCE, SOURCE_HANDLER);
    entry.put("method", action.getMethod());
    entry.put("readOnly", action.isReadOnly());
    if (action.getDescription() != null) {
      entry.put(McpConstants.KEY_DESCRIPTION, action.getDescription());
    }
    entry.put(McpConstants.PARAM_PARAMETERS, McpJsonSchema.toJsonObject(
        McpJsonSchema.declaredParamsSchema(action.getParameters())));
    entry.put(McpSchemaFieldBuilder.KEY_INVOKE_VIA, "neo_action");
    if (shadowsButton) {
      entry.put(KEY_SHADOWS, McpActionsView.TYPE_BUTTON);
    }
    return entry;
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
