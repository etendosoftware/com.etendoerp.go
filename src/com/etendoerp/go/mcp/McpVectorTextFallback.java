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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The text fallback of {@code etendo_vector_search} (ETP-5848): when semantic search cannot run or
 * finds nothing, the same targets are searched by text through {@code etendo_list}.
 *
 * <p>Searching records depended on the embeddings alone: with the provider down or its key not
 * configured, every call failed and the agent had to guess which spec to list instead. Each target
 * is named after the spec that owns it, so the fallback lists that spec with a {@code contains}
 * filter on each field the target indexes as content (name, search key, document number...). The
 * list call goes through the router, so the role, the spec's window filter and the scopes apply
 * exactly as for any other read. A value equal to the query scores {@value #SCORE_EXACT}, one that
 * starts with it {@value #SCORE_PREFIX}, any other match {@value #SCORE_CONTAINS}.</p>
 */
final class McpVectorTextFallback {

  static final String REASON_EMBEDDING_UNAVAILABLE = "embedding_unavailable";
  static final String REASON_NO_SEMANTIC_MATCHES = "no_semantic_matches";
  static final double SCORE_EXACT = 1d;
  static final double SCORE_PREFIX = 0.9d;
  static final double SCORE_CONTAINS = 0.75d;
  private static final String EMBEDDING_FAILURE = "VECTOR_EMBEDDING_OPERATION_FAILED";
  private static final String KEY_MATCHES = "matches";
  private static final String KEY_TARGET = "target";
  private static final String KEY_SCORE = "score";

  private McpVectorTextFallback() {
  }

  /**
   * Why the semantic answer calls for the text search, or {@code null} when it stands: the
   * embedding provider failed, or the search ran and matched nothing. Any other error is reported
   * as it is — a refused target or a broken configuration is not hidden behind a text search.
   *
   * @param response the semantic search response
   * @return the fallback reason, or {@code null}
   */
  static String reasonFor(NeoResponse response) {
    JSONObject body = response.getBody();
    if (response.getHttpStatus() >= 400) {
      JSONObject error = body == null ? null : body.optJSONObject("error");
      String message = error == null ? null : error.optString("message", null);
      return message != null && message.startsWith(EMBEDDING_FAILURE)
          ? REASON_EMBEDDING_UNAVAILABLE : null;
    }
    JSONArray matches = body == null ? null : body.optJSONArray(KEY_MATCHES);
    return matches == null || matches.length() == 0 ? REASON_NO_SEMANTIC_MATCHES : null;
  }

  /** How many matches to return and the score range to keep, as the caller asked. */
  static final class Limits {
    final int topK;
    final double minScore;
    final double maxScore;

    Limits(int topK, double minScore, double maxScore) {
      this.topK = topK;
      this.minScore = minScore;
      this.maxScore = maxScore;
    }
  }

  /**
   * Search the targets by text.
   *
   * @param targets    the target keys, each the name of a spec
   * @param query      the caller's query
   * @param limits     topK and the score range
   * @param reason     why the fallback runs
   * @param textFields the content fields of a target
   * @param listTool   runs {@code etendo_list} with the given arguments, as the router does
   * @return the response body, shaped like the semantic one plus {@code mode:"lexical"}
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject search(List<String> targets, String query, Limits limits, String reason,
      Function<String, List<String>> textFields, Function<JSONObject, JSONObject> listTool)
      throws JSONException {
    String text = query.trim();
    Map<String, JSONObject> found = new LinkedHashMap<>();
    for (String target : targets) {
      List<String> fields = textFields.apply(target);
      for (String field : fields) {
        keepBest(found, target, field, normalize(text),
            rows(listTool.apply(listArgs(target, field, fields, text, limits.topK))));
      }
    }
    return new JSONObject().put("targets", new JSONArray(targets)).put("mode", "lexical")
        .put("fallbackReason", reason)
        .put("hint", "Semantic search was unavailable or found nothing, so these are text matches "
            + "on the indexed fields. Read a match with etendo_get(spec: <its target>, id).")
        .put(KEY_MATCHES, ranked(found.values(), limits));
  }

  /** Adds each row of one field's list, keeping a record found on several fields at its best score. */
  private static void keepBest(Map<String, JSONObject> found, String target, String field,
      String wanted, List<JSONObject> rows) throws JSONException {
    for (JSONObject row : rows) {
      double score = scoreOf(row.optString(field, ""), wanted);
      String key = target + "/" + row.optString("id");
      JSONObject previous = found.get(key);
      if (previous == null || previous.getDouble(KEY_SCORE) < score) {
        found.put(key, new JSONObject().put(KEY_TARGET, target).put("id", row.optString("id"))
            .put(KEY_SCORE, score).put("matchType", "lexical").put("fields", row));
      }
    }
  }

  /** The matches inside the score range, best first, at most topK. */
  private static JSONArray ranked(Collection<JSONObject> found, Limits limits) {
    List<JSONObject> kept = new ArrayList<>();
    for (JSONObject match : found) {
      double score = match.optDouble(KEY_SCORE);
      if (score >= limits.minScore && score <= limits.maxScore) {
        kept.add(match);
      }
    }
    kept.sort(Comparator.comparingDouble((JSONObject m) -> -m.optDouble(KEY_SCORE)));
    return new JSONArray(kept.subList(0, Math.min(limits.topK, kept.size())));
  }

  private static JSONObject listArgs(String target, String field, List<String> fields,
      String query, int topK) throws JSONException {
    return new JSONObject().put("spec", target)
        .put("filters", new JSONObject().put(field, new JSONObject().put("contains", query)))
        .put("fields", new JSONArray(fields)).put("limit", topK);
  }

  /** The {@code data} rows of an {@code etendo_list} result; none when the call was refused. */
  private static List<JSONObject> rows(JSONObject result) {
    List<JSONObject> rows = new ArrayList<>();
    if (result == null || result.optBoolean("isError", false)) {
      return rows;
    }
    try {
      JSONArray content = result.getJSONArray("content");
      JSONArray data = new JSONObject(content.getJSONObject(0).getString("text"))
          .optJSONArray("data");
      for (int i = 0; data != null && i < data.length(); i++) {
        rows.add(data.getJSONObject(i));
      }
    } catch (JSONException e) {
      // An unreadable answer contributes nothing; the other fields and targets still count.
    }
    return rows;
  }

  private static double scoreOf(String value, String wanted) {
    String normalized = normalize(value);
    if (normalized.equals(wanted)) {
      return SCORE_EXACT;
    }
    return normalized.startsWith(wanted) ? SCORE_PREFIX : SCORE_CONTAINS;
  }

  private static String normalize(String text) {
    return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
  }
}
