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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * ETP-5848: when semantic search cannot run or finds nothing, {@code etendo_vector_search} searches
 * the same targets by text through {@code etendo_list}.
 */
class McpVectorTextFallbackTest {

  /** A fake {@code etendo_list}: rows per "spec/field", and a record of every call. */
  private static final class FakeList implements Function<JSONObject, JSONObject> {
    private final Map<String, JSONArray> rows = new LinkedHashMap<>();
    private final List<JSONObject> calls = new ArrayList<>();
    private final List<String> refused = new ArrayList<>();

    FakeList answer(String spec, String field, String id, String value) throws Exception {
      rows.computeIfAbsent(spec + "/" + field, k -> new JSONArray())
          .put(new JSONObject().put("id", id).put(field, value));
      return this;
    }

    @Override
    public JSONObject apply(JSONObject args) {
      calls.add(args);
      try {
        String spec = args.getString("spec");
        String field = (String) args.getJSONObject("filters").keys().next();
        if (refused.contains(spec)) {
          return McpToolRouter.wrapAsErrorContent(new JSONObject().put("status", 403));
        }
        return McpToolRouter.wrapAsTextContent(new JSONObject().put("data",
            rows.getOrDefault(spec + "/" + field, new JSONArray())));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static Function<String, List<String>> fields(Map<String, List<String>> byTarget) {
    return target -> byTarget.getOrDefault(target, List.of());
  }

  private static List<String> ids(JSONObject response) throws Exception {
    List<String> ids = new ArrayList<>();
    JSONArray matches = response.getJSONArray("matches");
    for (int i = 0; i < matches.length(); i++) {
      ids.add(matches.getJSONObject(i).getString("id"));
    }
    return ids;
  }

  @Test
  @DisplayName("the provider's failure and an empty semantic answer call for text; other errors"
      + " and a semantic hit do not")
  void reasonFor() throws Exception {
    assertEquals(McpVectorTextFallback.REASON_EMBEDDING_UNAVAILABLE,
        McpVectorTextFallback.reasonFor(NeoResponse.error(422,
            "VECTOR_EMBEDDING_OPERATION_FAILED: The OpenAI API key is not configured.")));
    assertEquals(McpVectorTextFallback.REASON_NO_SEMANTIC_MATCHES,
        McpVectorTextFallback.reasonFor(NeoResponse.ok(
            new JSONObject().put("matches", new JSONArray()))));
    assertNull(McpVectorTextFallback.reasonFor(NeoResponse.error(403, "Access denied")),
        "a refused target is reported, not searched around");
    assertNull(McpVectorTextFallback.reasonFor(NeoResponse.error(422,
        "VECTOR_DIMENSION_MISMATCH: Vector dimension does not match the collection.")));
    assertNull(McpVectorTextFallback.reasonFor(NeoResponse.ok(new JSONObject().put("matches",
        new JSONArray().put(new JSONObject().put("id", "A"))))));
  }

  @Test
  @DisplayName("each content field of each target is listed with contains on the query")
  void listsEveryContentFieldOfEveryTarget() throws Exception {
    FakeList list = new FakeList();
    McpVectorTextFallback.search(List.of("contacts", "product"), "  Agua ", 10, 0d, 1d,
        McpVectorTextFallback.REASON_EMBEDDING_UNAVAILABLE,
        fields(Map.of("contacts", List.of("name", "searchKey"), "product", List.of("name"))),
        list);

    assertEquals(3, list.calls.size());
    JSONObject first = list.calls.get(0);
    assertEquals("contacts", first.getString("spec"));
    assertEquals("Agua", first.getJSONObject("filters").getJSONObject("name")
        .getString("contains"));
    assertEquals(List.of("name", "searchKey"), List.of(first.getJSONArray("fields").getString(0),
        first.getJSONArray("fields").getString(1)), "only the indexed fields come back");
    assertEquals(10, first.getInt("limit"));
  }

  @Test
  @DisplayName("exact beats prefix beats contains; a record found on two fields counts once, at"
      + " its best score")
  void ranksAndDeduplicates() throws Exception {
    FakeList list = new FakeList()
        .answer("product", "name", "P-CONTAINS", "Bidón de agua")
        .answer("product", "name", "P-EXACT", "agua")
        .answer("product", "searchKey", "P-CONTAINS", "Bidón de agua")
        .answer("product", "searchKey", "P-PREFIX", "AGUA-500");

    JSONObject response = McpVectorTextFallback.search(List.of("product"), "Agua", 10, 0d, 1d,
        McpVectorTextFallback.REASON_EMBEDDING_UNAVAILABLE,
        fields(Map.of("product", List.of("name", "searchKey"))), list);

    assertEquals(List.of("P-EXACT", "P-PREFIX", "P-CONTAINS"), ids(response),
        "P-CONTAINS was found on two fields and is listed once");
    JSONArray matches = response.getJSONArray("matches");
    assertEquals(McpVectorTextFallback.SCORE_EXACT, matches.getJSONObject(0).getDouble("score"));
    assertEquals(McpVectorTextFallback.SCORE_PREFIX, matches.getJSONObject(1).getDouble("score"),
        "AGUA-500 starts with the query, ignoring case");
    assertEquals(McpVectorTextFallback.SCORE_CONTAINS, matches.getJSONObject(2).getDouble("score"));
    assertEquals("lexical", response.getString("mode"));
    assertEquals("lexical", matches.getJSONObject(0).getString("matchType"));
    assertEquals("product", matches.getJSONObject(0).getString("target"));
  }

  @Test
  @DisplayName("the score range and topK hold, and a target the role cannot list contributes"
      + " nothing instead of failing the search")
  void honoursRangeTopKAndRefusals() throws Exception {
    FakeList list = new FakeList()
        .answer("contacts", "name", "C-EXACT", "Agua")
        .answer("contacts", "name", "C-CONTAINS", "Distribuidora de agua")
        .answer("product", "name", "P-EXACT", "Agua");
    list.refused.add("product");

    JSONObject inRange = McpVectorTextFallback.search(List.of("contacts", "product"), "agua", 10,
        0.8d, 1d, McpVectorTextFallback.REASON_NO_SEMANTIC_MATCHES,
        fields(Map.of("contacts", List.of("name"), "product", List.of("name"))), list);
    assertEquals(List.of("C-EXACT"), ids(inRange),
        "a contains-match is below 0.8, and the refused product target adds nothing");
    assertEquals(McpVectorTextFallback.REASON_NO_SEMANTIC_MATCHES,
        inRange.getString("fallbackReason"));

    JSONObject top1 = McpVectorTextFallback.search(List.of("contacts"), "agua", 1, 0d, 1d,
        McpVectorTextFallback.REASON_NO_SEMANTIC_MATCHES,
        fields(Map.of("contacts", List.of("name"))), list);
    assertEquals(List.of("C-EXACT"), ids(top1));
    assertTrue(top1.getString("hint").contains("etendo_get"));
  }
}
