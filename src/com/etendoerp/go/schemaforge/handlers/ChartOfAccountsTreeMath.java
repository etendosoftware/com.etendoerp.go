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

package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pure, side-effect-free tree-walking helpers over the {@code (nodeId -> parentId)} /
 * {@code (nodeId -> value/name/elementLevel)} maps that {@link ChartOfAccountsHandler}
 * builds from {@code AD_TreeNode}.
 *
 * <p>Split out of {@link ChartOfAccountsHandler} purely to keep that class's method count
 * under the Sonar {@code java:S1448} limit (java:S1448) — every method here takes only the
 * maps it needs and returns a value, with no dependency on {@code NeoContext}, OBDal, or any
 * other handler state, so moving them here is a behavior-preserving, mechanical extraction.
 */
final class ChartOfAccountsTreeMath {

  /**
   * Length of the PGC 4-digit parent grouping code (e.g. {@code "4300"}) used by
   * {@link #findParentCode4} / {@link #findParentCode4Name}.
   */
  static final int PARENT_CODE_LENGTH = 4;

  private ChartOfAccountsTreeMath() {
    // Utility class — no instances.
  }

  /**
   * @return {@code value}, or {@link JSONObject#NULL} (the JSON-null sentinel {@code put}
   *     requires) when {@code value} is {@code null}.
   */
  static Object orNull(String value) {
    return value != null ? value : JSONObject.NULL;
  }

  /**
   * Walks the ancestor chain of {@code nodeId} (starting at its direct parent, excluding the
   * node itself) and returns it as a {@link JSONArray} ordered root-to-leaf — the shape the
   * frontend needs to build a genuine N-level nested tree (e.g. {@code A > A.A > A.A.I > 200
   * > 2000}), matching Etendo Classic's "Combinación de cuentas" grouped view.
   *
   * <p>Each entry is {@code {value, name, elementLevel}}. Capped at
   * {@value ChartOfAccountsHandler#MAX_TREE_DEPTH} hops to guard against circular references.
   *
   * @return a possibly-empty {@link JSONArray}; never {@code null}
   */
  static JSONArray buildAncestorChain(String nodeId, Map<String, String> nodeParentMap,
      Map<String, String> nodeValueMap, Map<String, String> nodeNameMap,
      Map<String, String> nodeElementLevelMap) throws Exception {
    List<JSONObject> chain = new ArrayList<>();
    String current = nodeParentMap.get(nodeId); // start at direct parent
    int guard = 0;
    while (current != null && guard < ChartOfAccountsHandler.MAX_TREE_DEPTH) {
      String value = nodeValueMap.get(current);
      String name = nodeNameMap.get(current);
      String level = nodeElementLevelMap.get(current);

      JSONObject ancestor = new JSONObject();
      ancestor.put("value", value != null ? value : JSONObject.NULL);
      ancestor.put("name", name != null ? name : JSONObject.NULL);
      ancestor.put("elementLevel", level != null ? level : JSONObject.NULL);
      chain.add(ancestor);

      current = nodeParentMap.get(current);
      guard++;
    }
    Collections.reverse(chain); // root-to-leaf order

    JSONArray result = new JSONArray();
    for (JSONObject ancestor : chain) {
      result.put(ancestor);
    }
    return result;
  }

  /**
   * Computes the depth of {@code nodeId} in the tree by walking up the parent chain.
   * Returns 0 for roots and for nodes not present in the map.
   * Capped at {@value ChartOfAccountsHandler#MAX_TREE_DEPTH} to guard against circular
   * references.
   */
  static int computeDepth(String nodeId, Map<String, String> nodeParentMap) {
    int depth = 0;
    String current = nodeParentMap.get(nodeId); // parent of nodeId
    int guard = 0;
    while (current != null && guard < ChartOfAccountsHandler.MAX_TREE_DEPTH) {
      depth++;
      current = nodeParentMap.get(current);
      guard++;
    }
    return depth;
  }

  /**
   * Walks up the ancestor chain of {@code nodeId} and returns the {@code Value}
   * of the nearest ancestor whose {@code Value} has exactly {@value #PARENT_CODE_LENGTH}
   * characters, or {@code null} if none is found before the root.
   *
   * <p>The node itself is excluded — traversal starts at its direct parent.
   */
  static String findParentCode4(String nodeId, Map<String, String> nodeParentMap,
      Map<String, String> nodeValueMap) {
    String current = nodeParentMap.get(nodeId); // start at direct parent
    int guard = 0;
    while (current != null && guard < ChartOfAccountsHandler.MAX_TREE_DEPTH) {
      String value = nodeValueMap.get(current);
      if (value != null && value.length() == PARENT_CODE_LENGTH) {
        return value;
      }
      current = nodeParentMap.get(current);
      guard++;
    }
    return null;
  }

  /**
   * Returns the {@code Name} of the nearest 4-digit ancestor, or {@code null} if none found.
   * Mirrors {@link #findParentCode4} but resolves the name instead of the value.
   */
  static String findParentCode4Name(String nodeId, Map<String, String> nodeParentMap,
      Map<String, String> nodeValueMap, Map<String, String> nodeNameMap) {
    String current = nodeParentMap.get(nodeId);
    int guard = 0;
    while (current != null && guard < ChartOfAccountsHandler.MAX_TREE_DEPTH) {
      String value = nodeValueMap.get(current);
      if (value != null && value.length() == PARENT_CODE_LENGTH) {
        return nodeNameMap.get(current);
      }
      current = nodeParentMap.get(current);
      guard++;
    }
    return null;
  }
}
