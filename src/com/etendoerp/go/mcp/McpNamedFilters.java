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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pure (DAL-free) parser for the per-entity <b>named filters</b> that {@code neo_list} exposes as
 * {@code {status: "<name>"}} business queries (ETP-4601).
 *
 * <p>Named filters are hand-authored in {@code decisions.json} per Schema Forge spec, carried through
 * the contract and {@code push-to-neo} into the {@code ETGO_SF_ENTITY.NAMED_FILTERS} CLOB as a JSON
 * array:
 * <pre>
 * [ { "name": "completed", "label": "Paid", "description": "Paid in full",
 *     "where": "e.paymentComplete = true" }, ... ]
 * </pre>
 * Each {@code where} is a self-contained HQL boolean fragment over the {@code e} alias — the same
 * power (field-to-field comparisons, {@code abs}, {@code now}) the previous hardcoded invoice logic
 * had, but authored per spec by a trusted human instead of baked into Java. Because a human never
 * authors a filter over a computed/transient column, the HTTP-500 class of bug that killed
 * {@code status:"overdue"} on a persisted-column-less due date simply cannot arise.
 *
 * <p>This class only parses and looks up; property resolution and where-clause splicing stay in
 * {@link McpToolRouterSupport}, and DAL access never happens here so it is unit-testable without a
 * running Openbravo instance.
 */
final class McpNamedFilters {

  private McpNamedFilters() {
  }

  static final String KEY_NAME = "name";
  static final String KEY_LABEL = "label";
  static final String KEY_DESCRIPTION = "description";
  static final String KEY_WHERE = "where";

  /**
   * Parse the {@code NAMED_FILTERS} JSON into an ordered {@code name -> where} map. Entries missing a
   * non-blank {@code name} or {@code where} are skipped; a later duplicate name does not overwrite an
   * earlier one (first wins), mirroring the pipeline's own normalization. A blank/malformed payload
   * yields an empty map (never {@code null}) so callers degrade gracefully.
   */
  static Map<String, String> parseWhereByName(String json) {
    Map<String, String> result = new LinkedHashMap<>();
    JSONArray arr = parseArray(json);
    if (arr == null) {
      return result;
    }
    for (int i = 0; i < arr.length(); i++) {
      collectWhereEntry(arr.optJSONObject(i), result);
    }
    return result;
  }

  /**
   * Add one {@code name -> where} entry to {@code result} (helper for {@link #parseWhereByName}).
   * Returns early — adding nothing — when the entry is absent, its {@code name}/{@code where} is
   * blank, or its name was already seen (first wins).
   */
  private static void collectWhereEntry(JSONObject entry, Map<String, String> result) {
    if (entry == null) {
      return;
    }
    String name = StringUtils.trimToEmpty(entry.optString(KEY_NAME, null));
    String where = StringUtils.trimToEmpty(entry.optString(KEY_WHERE, null));
    if (name.isEmpty() || where.isEmpty() || result.containsKey(name)) {
      return;
    }
    result.put(name, where);
  }

  /**
   * Parse the {@code NAMED_FILTERS} JSON into the ordered list of filter descriptors (name, label,
   * description) the schema/discover output advertises as documentation. The {@code where} fragment
   * is intentionally omitted — it is an implementation detail agents should not have to read.
   */
  static JSONArray describe(String json) throws JSONException {
    JSONArray out = new JSONArray();
    JSONArray arr = parseArray(json);
    if (arr == null) {
      return out;
    }
    for (int i = 0; i < arr.length(); i++) {
      JSONObject doc = describeEntry(arr.optJSONObject(i));
      if (doc != null) {
        out.put(doc);
      }
    }
    return out;
  }

  /**
   * Build one filter descriptor (name, optional label/description) for {@link #describe}. Returns
   * {@code null} — so the caller skips it — when the entry is absent or its {@code name}/{@code where}
   * is blank. The {@code where} fragment itself is intentionally never emitted.
   */
  private static JSONObject describeEntry(JSONObject entry) throws JSONException {
    if (entry == null) {
      return null;
    }
    String name = StringUtils.trimToEmpty(entry.optString(KEY_NAME, null));
    String where = StringUtils.trimToEmpty(entry.optString(KEY_WHERE, null));
    if (name.isEmpty() || where.isEmpty()) {
      return null;
    }
    JSONObject doc = new JSONObject();
    doc.put(KEY_NAME, name);
    String label = StringUtils.trimToNull(entry.optString(KEY_LABEL, null));
    if (label != null) {
      doc.put(KEY_LABEL, label);
    }
    String description = StringUtils.trimToNull(entry.optString(KEY_DESCRIPTION, null));
    if (description != null) {
      doc.put(KEY_DESCRIPTION, description);
    }
    return doc;
  }

  private static JSONArray parseArray(String json) {
    if (StringUtils.isBlank(json)) {
      return null;
    }
    try {
      return new JSONArray(json);
    } catch (JSONException e) {
      return null;
    }
  }
}
