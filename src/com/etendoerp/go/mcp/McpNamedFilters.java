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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pure (DAL-free) parser for the per-entity <b>named filters</b> that {@code etendo_list} exposes as
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
  /** Response/refusal key carrying the entity's filters, name plus short description (IMP-50). */
  static final String KEY_NAMED_FILTERS = "namedFilters";
  static final String KEY_APPLIED = "applied";
  static final String KEY_AVAILABLE = "available";
  /**
   * Hard cap, in characters, on the named-filter summary {@code etendo_list} carries in the catalog
   * (IMP-50). Past it the summary points at {@code etendo_schema view:"full"} for the rest, so a
   * tenant that configures many filters cannot grow every session's priming cost without bound.
   */
  static final int CATALOG_CAP = 1500;

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
   * Named business filters (ETP-4601): advertise the spec's hand-authored status filters, each
   * keyed by name, so the agent can discover them instead of guessing. Only the
   * name/label/description are exposed — the HQL where fragment stays server-side. Nothing is
   * added when the entity declares none.
   *
   * @param entitySchema the {@code etendo_schema} full response being built
   * @param json         the entity's {@code NAMED_FILTERS} JSON, may be {@code null}
   * @throws JSONException if the descriptors cannot be added
   */
  static void publishInto(JSONObject entitySchema, String json) throws JSONException {
    JSONArray namedFilters = describe(json);
    if (namedFilters.length() > 0) {
      entitySchema.put(KEY_NAMED_FILTERS, namedFilters);
    }
  }

  /**
   * The compact form of the configured filters (IMP-50): {@code [{name, description?}]}, each
   * description cut to its first sentence. What a response or a refusal can afford to repeat,
   * unlike the full descriptor {@link #describe} hands to {@code etendo_schema}.
   *
   * @param json the entity's {@code NAMED_FILTERS} JSON, may be {@code null}
   * @return the summaries, empty (never {@code null}) when none is configured
   */
  static JSONArray summarize(String json) {
    JSONArray out = new JSONArray();
    try {
      JSONArray described = describe(json);
      for (int i = 0; i < described.length(); i++) {
        JSONObject doc = described.getJSONObject(i);
        JSONObject summary = new JSONObject();
        summary.put(KEY_NAME, doc.getString(KEY_NAME));
        String description = firstSentence(doc.optString(KEY_DESCRIPTION, null));
        if (description != null) {
          summary.put(KEY_DESCRIPTION, description);
        }
        out.put(summary);
      }
    } catch (JSONException e) {
      // describe only emits objects with a name; nothing to recover from beyond an empty list.
      return new JSONArray();
    }
    return out;
  }

  /**
   * One catalog line for an entity's filters (IMP-50):
   * {@code "<spec>/<entity>: name (first sentence), name, …"}.
   *
   * @return the line, or {@code null} when the entity declares no named filter
   */
  static String catalogLine(String specName, String entityName, String json) {
    JSONArray summaries = summarize(json);
    if (summaries.length() == 0) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < summaries.length(); i++) {
      JSONObject summary = summaries.optJSONObject(i);
      String description = summary.optString(KEY_DESCRIPTION, null);
      String name = summary.optString(KEY_NAME);
      parts.add(description == null ? name : name + " (" + description + ")");
    }
    return specName + "/" + entityName + ": " + String.join(", ", parts);
  }

  /**
   * Join the catalog lines, one per entity, stopping before {@code cap} characters with a pointer
   * to {@code etendo_schema view:"full"} for the lines left out (IMP-50).
   *
   * @return the summary, or {@code null} when there is no line at all
   */
  static String catalogSummary(List<String> lines, int cap) {
    if (lines == null || lines.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      int separator = out.length() > 0 ? 1 : 0;
      if (out.length() + separator + line.length() > cap) {
        if (separator > 0) {
          out.append('\n');
        }
        out.append("… ").append(lines.size() - i)
            .append(" more: call etendo_schema view:\"full\"");
        break;
      }
      if (separator > 0) {
        out.append('\n');
      }
      out.append(line);
    }
    return out.toString();
  }

  /**
   * The {@code namedFilters} block of an {@code etendo_list} response (IMP-50):
   * {@code {"applied":name, "description":…, "available":[{name, description}, …]}}, where
   * {@code available} holds the entity's other filters.
   *
   * @return the block, or {@code null} when {@code applied} is not one of the configured filters —
   *     including when the entity has none and {@code status} was read as a plain column
   */
  static JSONObject appliedBlock(String json, String applied) throws JSONException {
    if (StringUtils.isBlank(applied)) {
      return null;
    }
    JSONArray summaries = summarize(json);
    JSONObject block = null;
    JSONArray others = new JSONArray();
    for (int i = 0; i < summaries.length(); i++) {
      JSONObject summary = summaries.getJSONObject(i);
      if (applied.equals(summary.getString(KEY_NAME))) {
        block = new JSONObject();
        block.put(KEY_APPLIED, applied);
        if (summary.has(KEY_DESCRIPTION)) {
          block.put(KEY_DESCRIPTION, summary.getString(KEY_DESCRIPTION));
        }
      } else {
        others.put(summary);
      }
    }
    if (block != null) {
      block.put(KEY_AVAILABLE, others);
    }
    return block;
  }

  /**
   * Put the {@link #appliedBlock} on an {@code etendo_list} body when the call's {@code filters}
   * applied a named filter (IMP-50), so the agent learns the other filters exactly when it is
   * using one. Nothing is added otherwise.
   *
   * @param body    the response body about to be returned
   * @param json    the entity's {@code NAMED_FILTERS} JSON, may be {@code null}
   * @param filters the call's filters, may be {@code null}
   */
  static void attachApplied(JSONObject body, String json, JSONObject filters)
      throws JSONException {
    if (body == null || filters == null) {
      return;
    }
    Iterator<?> keys = filters.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Object value = filters.opt(key);
      if (McpBusinessFilters.STATUS_KEY.equalsIgnoreCase(key) && value instanceof String) {
        JSONObject block = appliedBlock(json, (String) value);
        if (block != null) {
          body.put(KEY_NAMED_FILTERS, block);
        }
        return;
      }
    }
  }

  /** The first sentence of {@code text} (up to and including the first ". "), trimmed. */
  static String firstSentence(String text) {
    String trimmed = StringUtils.trimToNull(text);
    if (trimmed == null) {
      return null;
    }
    int end = trimmed.indexOf(". ");
    return end < 0 ? trimmed : trimmed.substring(0, end + 1);
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
