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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

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
    JSONObject response = new JSONObject();
    response.put("spec", specName);
    response.put("entity", entityName);
    JSONArray actions = apply(fields);
    response.put(KEY_ACTIONS, actions);
    response.put("actionCount", actions.length());
    response.put(KEY_INVOKABLE_COUNT, countInvokable(actions));
    return response;
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
