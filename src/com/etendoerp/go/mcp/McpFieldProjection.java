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
import java.util.Iterator;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pure (DAL-free) field projection for {@code neo_list} / {@code neo_get} responses (IMP-2).
 *
 * <p>Compliance-heavy specs return ~60 columns per row; an agent that only wants
 * {@code documentNo, businessPartner, invoiceDate, grandTotalAmount, outstandingAmount} should be
 * able to say so and get exactly that, instead of pulling everything and filtering client-side. Two
 * optional inputs drive it, both additive:
 * <ul>
 *   <li><b>{@code fields:[...]}</b> — an explicit whitelist of property names.</li>
 *   <li><b>{@code view:"summary"}</b> — a curated set the servlet derives from the spec's
 *       business-critical fields (resolved in {@link McpToolRouterSupport#summaryFields}).</li>
 * </ul>
 * Omitting both leaves the response untouched (full, backward compatible).
 *
 * <p>The response is the SmartClient envelope {@code {response:{data:[ {row}, … ]}}}; each row is
 * trimmed to the requested properties. A FK's {@code $_identifier} (and any other {@code $}-suffixed
 * companion) rides along with its base property, and {@code id} is always kept so records stay
 * addressable.
 */
final class McpFieldProjection {

  private McpFieldProjection() {
  }

  static final String PARAM_FIELDS = "fields";
  static final String PARAM_VIEW = "view";
  static final String VIEW_SUMMARY = "summary";

  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final String KEY_ID = "id";
  static final String KEY_UNKNOWN_FIELDS = "unknownFields";

  /** @return {@code true} if {@code view} requests the curated summary projection. */
  static boolean isSummaryView(String view) {
    return VIEW_SUMMARY.equalsIgnoreCase(view);
  }

  /** Parse a {@code fields} JSON array argument into a set of property names (never {@code null}). */
  static Set<String> parseFields(JSONArray fields) throws JSONException {
    Set<String> result = new HashSet<>();
    if (fields == null) {
      return result;
    }
    for (int i = 0; i < fields.length(); i++) {
      Object raw = fields.opt(i);
      if (raw == null) {
        continue;
      }
      String name = String.valueOf(raw).trim();
      if (!name.isEmpty()) {
        result.add(name);
      }
    }
    return result;
  }

  /**
   * Reduce requested names to their base properties, so a caller who asks for the companion
   * ({@code businessPartner$_identifier}) gets — and is judged on — the property it belongs to.
   *
   * <p>Without this, {@link #apply} compared a row key's base property against the raw requested
   * name and dropped every companion-only request, returning a row of nothing but {@code id}; and
   * {@link #reportUnknownFields} would then have called the name unknown, blaming a typo for a
   * defect on our side.
   */
  static Set<String> baseNames(Set<String> requested) {
    Set<String> base = new HashSet<>();
    if (requested == null) {
      return base;
    }
    for (String name : requested) {
      base.add(McpDefaultsView.baseProperty(name));
    }
    return base;
  }

  /**
   * Report the requested names this entity cannot emit, as {@code response.unknownFields} (IMP-18).
   *
   * <p>A projection is a whitelist, so a typo used to be indistinguishable from a field that simply
   * held no value: the key was absent either way and the caller had no signal. Naming the rejects
   * mirrors what {@code neo_schema} already does for its own {@code fields} argument
   * (see {@link McpSchemaCreateView#unknownFields}) — the same argument name now behaves the same
   * way on both tools.
   *
   * <p>Validation is against what the entity can emit, not against the rows that came back: on an
   * empty result set no row can answer the question, and that is exactly when a typo costs the most
   * (an agent reads "no matches" and concludes the data is missing). A {@code null} {@code emittable}
   * means the emittable set could not be determined, and leaves the names unjudged — silence is
   * better than accusing a valid field.
   */
  static void reportUnknownFields(JSONObject responseJson, Set<String> requestedBase,
      Set<String> emittable) throws JSONException {
    if (responseJson == null || requestedBase == null || requestedBase.isEmpty()
        || emittable == null) {
      return;
    }
    java.util.List<String> unknown = new java.util.ArrayList<>();
    for (String name : requestedBase) {
      if (!emittable.contains(name)) {
        unknown.add(name);
      }
    }
    if (unknown.isEmpty()) {
      return;
    }
    java.util.Collections.sort(unknown);
    JSONObject response = responseJson.optJSONObject(KEY_RESPONSE);
    if (response == null) {
      return;
    }
    response.put(KEY_UNKNOWN_FIELDS, new JSONArray(unknown));
  }

  /**
   * Trim every {@code response.data[]} row down to {@code requested} (plus each base property's
   * {@code $}-companions and the always-kept {@code id}). A {@code null}/empty {@code requested}
   * set, or a payload without a {@code response.data} array, is a no-op so the full response is
   * preserved.
   */
  static void apply(JSONObject responseJson, Set<String> requested) throws JSONException {
    if (responseJson == null || requested == null || requested.isEmpty()) {
      return;
    }
    JSONObject response = responseJson.optJSONObject(KEY_RESPONSE);
    if (response == null) {
      return;
    }
    JSONArray data = response.optJSONArray(KEY_DATA);
    if (data == null) {
      return;
    }
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.optJSONObject(i);
      if (row == null) {
        continue;
      }
      data.put(i, projectRow(row, requested));
    }
  }

  private static JSONObject projectRow(JSONObject row, Set<String> requested) throws JSONException {
    JSONObject projected = new JSONObject();
    Iterator<?> keys = row.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (KEY_ID.equals(key) || requested.contains(McpDefaultsView.baseProperty(key))) {
        projected.put(key, row.get(key));
      }
    }
    return projected;
  }
}
