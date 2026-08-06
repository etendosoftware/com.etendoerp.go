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

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pure (DAL-free) re-shaper for the {@code neo_defaults} response (IMP-7).
 *
 * <p>{@code neo_defaults} pre-fills every column of a new record — for compliance-heavy specs
 * (invoices, payments) that is ~65 keys, most of them audit/compliance flags the server owns and
 * the agent should never touch. The optional {@code view} argument lets the caller collapse that
 * noise:
 * <ul>
 *   <li><b>no view / {@code "full"}</b> — the historical flat {@code {defaults, metadata}} shape,
 *       unchanged (backward compatible).</li>
 *   <li><b>{@code "grouped"}</b> — splits the flat map into {@code confirm} (fields the agent should
 *       review/override, i.e. the {@code editable}-visibility ones) and {@code systemManaged}
 *       (everything else), keeping {@code metadata}.</li>
 *   <li><b>{@code "minimal"}</b> — only the {@code confirm} block (plus {@code metadata}).</li>
 * </ul>
 *
 * <p><b>A blank value is not a resolved value.</b> {@code NeoDefaultsService} records a name in
 * {@code metadata.unresolvedFields} only when resolution threw; a column that resolved to the empty
 * string did not throw, so it used to land in {@code confirm} looking exactly like a value the agent
 * could accept. On {@code sales-invoice/header} that is what {@code partnerAddress} does: the server
 * knows the field, could not produce a value, and reported {@code ""} while
 * {@code metadata.unresolvedFields} stayed {@code []} — the one place the agent most needs to be told
 * to supply something was the place that looked settled. The grouping views therefore relocate
 * blank-valued {@code confirm} members into {@code metadata.unresolvedFields}, which already carries
 * exactly that meaning. Only {@code confirm} is filtered: a blank in {@code systemManaged} is not the
 * agent's problem and would only pad the list.
 *
 * <p>The default (no {@code view}) response is deliberately left untouched — same reasoning as
 * {@code neo_schema}'s: the projections are the authoritative agent-facing surface, and the flat dump
 * keeps its historical shape for backward compatibility.
 *
 * <p>The classification input — the set of writable DAL property names — is computed by
 * {@link McpToolRouterSupport#editablePropertyNames} (which owns the DAL/model access); this class
 * only rearranges the JSON, so it is unit-testable without a running instance.
 */
final class McpDefaultsView {

  private McpDefaultsView() {
  }

  static final String PARAM_VIEW = "view";
  static final String VIEW_FULL = "full";
  static final String VIEW_GROUPED = "grouped";
  static final String VIEW_MINIMAL = "minimal";

  static final String GROUP_CONFIRM = "confirm";
  static final String GROUP_SYSTEM_MANAGED = "systemManaged";
  static final String KEY_DEFAULTS = "defaults";
  static final String KEY_METADATA = "metadata";
  static final String KEY_UNRESOLVED_FIELDS = "unresolvedFields";

  /** @return {@code true} if {@code view} asks for a re-grouped shape ({@code grouped}/{@code minimal}). */
  static boolean isGroupingView(String view) {
    return VIEW_GROUPED.equalsIgnoreCase(view) || VIEW_MINIMAL.equalsIgnoreCase(view);
  }

  /**
   * The base DAL property behind a defaults key: strips the {@code $_identifier} (and any other
   * {@code $}-suffixed) companion so a FK's label follows its value into the same group.
   */
  static String baseProperty(String key) {
    int dollar = key.indexOf('$');
    return dollar < 0 ? key : key.substring(0, dollar);
  }

  /**
   * Whether a defaults value means "the server knows this field and has no value for it".
   *
   * <p>{@code null}, JSON {@code null} and a blank string all mean the agent still has to supply the
   * field; only a non-blank value counts as resolved. Shared with
   * {@link McpSchemaCreateView#resolvedDefaultNames} so {@code view:"create"} and
   * {@code view:"grouped"} cannot drift on what "resolved" means for the same field.
   */
  static boolean isUnresolvedValue(Object value) {
    return value == null || JSONObject.NULL.equals(value)
        || (value instanceof String && ((String) value).trim().isEmpty());
  }

  /**
   * Re-shape a {@code {defaults, metadata}} response into {@code confirm}/{@code systemManaged}
   * groups. Returns {@code response} untouched when {@code view} is not a grouping view or the
   * payload has no {@code defaults} object, so the default (full) behavior is preserved.
   *
   * @param response      the original neo_defaults body
   * @param editableProps DAL property names the agent may write ({@code editable} visibility)
   * @param view          the requested view ({@code grouped}/{@code minimal}); anything else is a no-op
   */
  static JSONObject apply(JSONObject response, Set<String> editableProps, String view)
      throws JSONException {
    if (response == null || !isGroupingView(view)) {
      return response;
    }
    JSONObject defaults = response.optJSONObject(KEY_DEFAULTS);
    if (defaults == null) {
      return response;
    }
    JSONObject confirm = new JSONObject();
    JSONObject systemManaged = new JSONObject();
    Set<String> blanks = new LinkedHashSet<>();
    Iterator<?> keys = defaults.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Object value = defaults.get(key);
      if (editableProps != null && editableProps.contains(baseProperty(key))) {
        // A blank is a field the agent must still supply — say so where that is already recorded,
        // instead of listing it in confirm as if the server had settled it.
        if (isUnresolvedValue(value)) {
          blanks.add(baseProperty(key));
        } else {
          confirm.put(key, value);
        }
      } else {
        systemManaged.put(key, value);
      }
    }

    JSONObject out = new JSONObject();
    out.put(GROUP_CONFIRM, confirm);
    if (!VIEW_MINIMAL.equalsIgnoreCase(view)) {
      out.put(GROUP_SYSTEM_MANAGED, systemManaged);
    }
    if (blanks.isEmpty()) {
      if (response.has(KEY_METADATA)) {
        out.put(KEY_METADATA, response.get(KEY_METADATA));
      }
    } else {
      out.put(KEY_METADATA, withUnresolved(response.optJSONObject(KEY_METADATA), blanks));
    }
    return out;
  }

  /**
   * A copy of {@code metadata} whose {@code unresolvedFields} array also contains {@code extra},
   * de-duplicated against what is already listed. Copies rather than mutates: {@code apply} must not
   * modify the response object it was handed.
   */
  private static JSONObject withUnresolved(JSONObject metadata, Set<String> extra)
      throws JSONException {
    JSONObject out = new JSONObject();
    JSONArray unresolved = new JSONArray();
    Set<String> seen = new LinkedHashSet<>();
    if (metadata != null) {
      Iterator<?> keys = metadata.keys();
      while (keys.hasNext()) {
        String key = String.valueOf(keys.next());
        if (!KEY_UNRESOLVED_FIELDS.equals(key)) {
          out.put(key, metadata.get(key));
        }
      }
      JSONArray existing = metadata.optJSONArray(KEY_UNRESOLVED_FIELDS);
      for (int i = 0; existing != null && i < existing.length(); i++) {
        String name = String.valueOf(existing.opt(i));
        if (seen.add(name)) {
          unresolved.put(name);
        }
      }
    }
    for (String name : extra) {
      if (seen.add(name)) {
        unresolved.put(name);
      }
    }
    out.put(KEY_UNRESOLVED_FIELDS, unresolved);
    return out;
  }
}
