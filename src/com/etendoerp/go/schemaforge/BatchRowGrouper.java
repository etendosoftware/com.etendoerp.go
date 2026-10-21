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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Splits the operations of an asynchronous batch into <b>rows</b> (ETP-5677): the connected
 * components of the operations graph, where two operations are linked when one names the other in
 * its {@code parentRef} or in a {@code ":<id>"} string anywhere in its body.
 *
 * <p>A product plus its price, or a document plus its lines, is one row; two unrelated products are
 * two rows. The async worker commits one row at a time, so a rejected row costs only itself while
 * every operation of a row is still atomic with its dependencies. Operations keep their original
 * relative order inside a row, and rows are ordered by their first operation.</p>
 */
final class BatchRowGrouper {

  private BatchRowGrouper() {
  }

  /**
   * @param operations the batch operations, in request order
   * @return one list of operation indexes per row, each ascending, rows ordered by first operation
   */
  static List<List<Integer>> group(JSONArray operations) {
    int n = operations.length();
    int[] parent = new int[n];
    for (int i = 0; i < n; i++) {
      parent[i] = i;
    }
    Map<String, Integer> firstIndexOfId = new HashMap<>();
    for (int i = 0; i < n; i++) {
      JSONObject op = operations.optJSONObject(i);
      String id = op == null ? null : op.optString("id", null);
      if (id != null) {
        Integer first = firstIndexOfId.putIfAbsent(id, i);
        if (first != null) {
          // A duplicate id lands in the same row so the batch reports it as the sync path does.
          union(parent, first, i);
        }
      }
    }
    for (int i = 0; i < n; i++) {
      JSONObject op = operations.optJSONObject(i);
      if (op == null) {
        continue;
      }
      link(parent, firstIndexOfId, i, op.optString("parentRef", null));
      collectRefs(parent, firstIndexOfId, i, op.optJSONObject("body"));
    }
    Map<Integer, List<Integer>> rows = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      rows.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
    }
    return new ArrayList<>(rows.values());
  }

  private static void collectRefs(int[] parent, Map<String, Integer> ids, int self, Object node) {
    if (node instanceof String) {
      String text = (String) node;
      if (text.startsWith(BatchService.REF_PREFIX)) {
        link(parent, ids, self, text.substring(BatchService.REF_PREFIX.length()));
      }
    } else if (node instanceof JSONObject) {
      JSONObject object = (JSONObject) node;
      Iterator<?> keys = object.keys();
      while (keys.hasNext()) {
        collectRefs(parent, ids, self, object.opt((String) keys.next()));
      }
    } else if (node instanceof JSONArray) {
      JSONArray array = (JSONArray) node;
      for (int i = 0; i < array.length(); i++) {
        collectRefs(parent, ids, self, array.opt(i));
      }
    }
  }

  private static void link(int[] parent, Map<String, Integer> ids, int self, String targetId) {
    Integer target = targetId == null ? null : ids.get(targetId);
    if (target != null) {
      union(parent, self, target);
    }
  }

  private static int find(int[] parent, int i) {
    int root = i;
    while (parent[root] != root) {
      root = parent[root];
    }
    int cursor = i;
    while (parent[cursor] != root) {
      int next = parent[cursor];
      parent[cursor] = root;
      cursor = next;
    }
    return root;
  }

  /** Smaller index wins as root, so a row is keyed by its first operation. */
  private static void union(int[] parent, int a, int b) {
    int ra = find(parent, a);
    int rb = find(parent, b);
    if (ra == rb) {
      return;
    }
    if (ra < rb) {
      parent[rb] = ra;
    } else {
      parent[ra] = rb;
    }
  }
}
