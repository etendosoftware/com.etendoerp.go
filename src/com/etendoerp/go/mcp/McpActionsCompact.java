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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The last step of {@code etendo_schema({view:"actions"})} (ETP-5848): what reaches the agent.
 *
 * <p>The catalogue was built for completeness, and an agent pays for every byte of it on every
 * later turn. In the OKR O2 sweep this view was a quarter of all the text agents received:
 * {@code sales-invoice} answered 51 kB for 43 actions, 18 of which the agent cannot run, and the
 * agent nearly always asked for it to find ONE action. Four changes, none of which alters what
 * {@code etendo_action} accepts:</p>
 * <ul>
 *   <li>an action the agent cannot run ({@code invokable:false}) leaves {@code actions} for
 *       {@code notOffered}, grouped by reason, with its {@code useInstead} when it has one;</li>
 *   <li>a listed action drops the AD column metadata that only describes the window
 *       ({@link #WINDOW_KEYS}), a {@code name} equal to its {@code action}, and a
 *       {@code description} that repeats its label;</li>
 *   <li>without {@code action} the catalogue is an index: per action its name, label, a one-line
 *       {@code summary} and when it applies — no parameters, values or long description;</li>
 *   <li>{@code action:"<name>"} answers that action alone, in full; a name that is neither listed
 *       nor not offered is refused with the names that exist, instead of being ignored.</li>
 * </ul>
 */
final class McpActionsCompact {

  /** Argument: answer one action only. */
  static final String PARAM_ACTION = "action";
  static final String KEY_NOT_OFFERED = "notOffered";
  private static final String KEY_ACTION = "action";
  private static final String KEY_ACTIONS_IN_GROUP = "actions";
  private static final String KEY_NAME = "name";
  private static final String KEY_LABEL = "label";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_REASON = "reason";
  private static final String KEY_USE_INSTEAD = "useInstead";

  /**
   * AD column metadata of a button: how the window renders it, not how to call it. The call needs
   * {@code action}, its values or parameters, and its availability, which all stay.
   */
  static final Set<String> WINDOW_KEYS = Set.of("column", "type", "readOnly", "visibility",
      McpSchemaFieldBuilder.KEY_USER_REQUIRED, "triggerValue", "processType", "processName",
      "processId", McpSchemaFieldBuilder.KEY_DEFAULT_EXPRESSION);

  private McpActionsCompact() {
  }

  /**
   * Compacts an actions-view response in place and, when {@code onlyAction} is given, narrows it
   * to that action.
   *
   * @param response   the response {@link McpActionsView} built
   * @param onlyAction the action asked for, or {@code null}/blank for the whole catalogue
   * @return the same object
   * @throws JSONException       if the JSON cannot be rebuilt
   * @throws McpRoutingException 422 when {@code onlyAction} names nothing in the catalogue
   */
  static JSONObject shape(JSONObject response, String onlyAction) throws JSONException {
    JSONArray all = response.optJSONArray(McpActionsView.KEY_ACTIONS);
    if (all == null) {
      return response;
    }
    JSONArray offered = new JSONArray();
    Map<String, JSONObject> notOffered = new LinkedHashMap<>();
    for (int i = 0; i < all.length(); i++) {
      JSONObject action = all.getJSONObject(i);
      if (action.optBoolean(McpSchemaFieldBuilder.KEY_INVOKABLE, true)) {
        offered.put(trimmed(action));
      } else {
        addNotOffered(notOffered, action);
      }
    }
    String wanted = StringUtils.trimToNull(onlyAction);
    if (wanted != null) {
      offered = narrow(offered, notOffered, wanted);
    } else {
      offered = index(offered);
      response.remove(KEY_DECLARED_ACTIONS_HINT);
      response.put(McpConstants.KEY_HINT, INDEX_HINT);
    }
    response.put(McpActionsView.KEY_ACTIONS, offered);
    response.put("actionCount", offered.length());
    response.put(McpActionsView.KEY_INVOKABLE_COUNT, offered.length());
    response.remove(KEY_NOT_OFFERED);
    if (!notOffered.isEmpty()) {
      response.put(KEY_NOT_OFFERED, new JSONArray(new ArrayList<>(notOffered.values())));
    }
    groupWithheld(response);
    return response;
  }

  /**
   * {@code withheldActions} ({@code MCP_CONFIG.actions.hidden}) as one entry per reason: a
   * configuration usually gives every hidden button the same paragraph, which {@code payment-in}
   * repeated six times (6.9 kB of its 7.6 kB answer).
   */
  private static void groupWithheld(JSONObject response) throws JSONException {
    JSONArray withheld = response.optJSONArray(McpActionsView.KEY_WITHHELD_ACTIONS);
    if (withheld == null) {
      return;
    }
    Map<String, JSONObject> groups = new LinkedHashMap<>();
    for (int i = 0; i < withheld.length(); i++) {
      JSONObject one = withheld.getJSONObject(i);
      String reason = one.optString(KEY_REASON, "");
      groups.computeIfAbsent(reason, r -> group(r))
          .getJSONArray(KEY_ACTIONS_IN_GROUP).put(one.optString(KEY_ACTION));
    }
    response.put(McpActionsView.KEY_WITHHELD_ACTIONS,
        new JSONArray(new ArrayList<>(groups.values())));
  }

  private static JSONObject group(String reason) {
    try {
      return new JSONObject().put(KEY_ACTIONS_IN_GROUP, new JSONArray()).put(KEY_REASON, reason);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String KEY_DECLARED_ACTIONS_HINT = "declaredActionsHint";
  private static final String KEY_SUMMARY = "summary";
  /** Longest summary kept, in characters: one line, enough to choose. */
  static final int MAX_SUMMARY = 200;
  static final String INDEX_HINT = "This is the index: one line per action. To run one, call "
      + "etendo_schema again with action:\"<name>\" for its parameters and values, then "
      + "etendo_action with them.";

  /**
   * What an index entry keeps: how to name the action, what it does in one line, and when it
   * applies. Parameters, values and the long description come with {@code action:"<name>"}.
   */
  private static final Set<String> INDEX_KEYS = Set.of(KEY_ACTION, KEY_LABEL, "mutating",
      "businessCritical", "availableWhen", "valuesByStatus", "availableNow", "unavailableReason",
      "availableValuesNow");

  /** The whole catalogue without the per-action detail (ETP-5848). */
  private static JSONArray index(JSONArray offered) throws JSONException {
    JSONArray index = new JSONArray();
    for (int i = 0; i < offered.length(); i++) {
      JSONObject action = offered.getJSONObject(i);
      JSONObject entry = new JSONObject().put(KEY_ACTION, nameOf(action));
      for (String key : INDEX_KEYS) {
        if (action.has(key) && !entry.has(key)) {
          entry.put(key, action.get(key));
        }
      }
      String summary = summaryOf(action.optString(KEY_DESCRIPTION, null));
      if (summary != null && !summary.equals(entry.optString(KEY_LABEL, null))) {
        entry.put(KEY_SUMMARY, summary);
      }
      index.put(entry);
    }
    return index;
  }

  /** The first sentence of a description, cut at {@link #MAX_SUMMARY} characters. */
  static String summaryOf(String description) {
    String text = StringUtils.trimToNull(description);
    if (text == null) {
      return null;
    }
    int end = text.indexOf(". ");
    String first = end > 0 ? text.substring(0, end + 1) : text;
    return first.length() <= MAX_SUMMARY ? first
        : StringUtils.abbreviate(first, MAX_SUMMARY);
  }

  /** The name {@code etendo_action} fires an entry by. */
  private static String nameOf(JSONObject action) {
    return StringUtils.defaultIfBlank(action.optString(KEY_ACTION, null),
        action.optString(KEY_NAME, ""));
  }

  private static JSONObject trimmed(JSONObject action) {
    for (String key : WINDOW_KEYS) {
      action.remove(key);
    }
    if (nameOf(action).equals(action.optString(KEY_NAME, null))) {
      action.remove(KEY_NAME);
    }
    String description = action.optString(KEY_DESCRIPTION, null);
    if (description != null && description.equals(action.optString(KEY_LABEL, null))) {
      action.remove(KEY_DESCRIPTION);
    }
    return action;
  }

  /** One group per reason and redirect: twelve curated-out buttons share one sentence. */
  private static void addNotOffered(Map<String, JSONObject> groups, JSONObject action)
      throws JSONException {
    String reason = action.optString(McpSchemaFieldBuilder.KEY_NOT_INVOKABLE_REASON, "");
    String useInstead = action.optString(KEY_USE_INSTEAD, null);
    String key = reason + "\u0000" + StringUtils.defaultString(useInstead);
    JSONObject group = groups.get(key);
    if (group == null) {
      group = new JSONObject().put(KEY_ACTIONS_IN_GROUP, new JSONArray()).put(KEY_REASON, reason);
      if (useInstead != null) {
        group.put(KEY_USE_INSTEAD, useInstead);
      }
      groups.put(key, group);
    }
    group.getJSONArray(KEY_ACTIONS_IN_GROUP).put(nameOf(action));
  }

  /**
   * The offered action named {@code wanted}, alone; {@code notOffered} is left holding only its
   * group when the name is a not-offered one.
   */
  private static JSONArray narrow(JSONArray offered, Map<String, JSONObject> notOffered,
      String wanted) throws JSONException {
    List<String> names = new ArrayList<>();
    JSONArray only = new JSONArray();
    for (int i = 0; i < offered.length(); i++) {
      JSONObject action = offered.getJSONObject(i);
      names.add(nameOf(action));
      if (nameOf(action).equalsIgnoreCase(wanted)) {
        only.put(action);
      }
    }
    JSONObject notOfferedMatch = null;
    for (JSONObject group : notOffered.values()) {
      JSONArray groupNames = group.getJSONArray(KEY_ACTIONS_IN_GROUP);
      for (int i = 0; i < groupNames.length(); i++) {
        String name = groupNames.getString(i);
        names.add(name);
        if (name.equalsIgnoreCase(wanted)) {
          notOfferedMatch = new JSONObject(group.toString())
              .put(KEY_ACTIONS_IN_GROUP, new JSONArray().put(name));
        }
      }
    }
    if (only.length() == 0 && notOfferedMatch == null) {
      throw McpRoutingException.unprocessable("No action '" + wanted + "' on this entity.",
          PARAM_ACTION, names,
          "Use one of the names listed in 'available', or omit 'action' for the whole catalogue.");
    }
    notOffered.clear();
    if (notOfferedMatch != null) {
      notOffered.put(wanted, notOfferedMatch);
    }
    return only;
  }
}
