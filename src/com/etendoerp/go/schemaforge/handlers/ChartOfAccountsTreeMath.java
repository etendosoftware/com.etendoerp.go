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
import java.util.regex.Pattern;

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

  /** Matches a {@code Value} that is purely numeric — a genuine PGC grouping/leaf code. */
  private static final Pattern NUMERIC_VALUE = Pattern.compile("\\d+");

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

  // ── ETP-5399 — real insertion-children resolution (letter-suffixed grouping fix) ──

  /** {@code C_ElementValue.ElementLevel} value for a Breakdown-level grouping node. */
  private static final String LEVEL_BREAKDOWN = "D";

  /** {@code C_ElementValue.ElementLevel} value for a genuine leaf/posting account. */
  private static final String LEVEL_SUBACCOUNT = "S";

  /**
   * Resolves the real, structural insertion point(s) for a new subaccount created under
   * {@code parentNodeId} — the fix for ETP-5399: {@link #findParentCode4} counts
   * <em>characters</em>, not digits, so a letter-suffixed grouping code (e.g. {@code "430A"},
   * {@code "103A"}) is indistinguishable from a genuine 4-digit numeric grouping code.
   *
   * <p>This does NOT fix the bug by inspecting or stripping the letter — that is exactly the
   * text-manipulation approach the ticket rules out. Instead it uses {@code ElementLevel}
   * ({@code C_ElementValue.ElementLevel}: {@code E} Heading, {@code C} Account, {@code D}
   * Breakdown, {@code S} Subaccount), a semantic AD column that already encodes the node's
   * tree position independently of what its {@code Value} text looks like. Live-data
   * verification against this client's full 1798-node wired tree confirms the transition is
   * always exactly {@code E -> E|C}, {@code C -> D}, {@code D -> S}, with zero exceptions —
   * so "is this node's level {@code D}?" is a fully general terminal test, whether the
   * {@code D} node's {@code Value} happens to be numeric ({@code "2000"}) or letter-suffixed
   * ({@code "4300A"}). This is what makes the fix generalize to any of the ~49 letter-suffixed
   * families and to any depth, with no per-family lookup table and no letter/length parsing.
   *
   * <p>Algorithm (operates purely on {@code (nodeId -> value/name/elementLevel)} and the
   * {@code (parentId -> List<childId>)} inverse map — no SQL, no side effects):
   * <ul>
   *   <li><b>{@code ElementLevel == "D"}</b> (Breakdown) — this IS the correct grouping depth
   *       (the existing/working case, e.g. {@code "2000"}, and — newly correct — a
   *       letter-suffixed Breakdown like {@code "4300A"}). Returns the node itself, regardless
   *       of whether it already has leaf children. The pre-existing numeric behavior is
   *       unchanged; this is the exact node {@code findParentCode4} missed for letters.</li>
   *   <li><b>{@code ElementLevel == "S"}</b> (Subaccount/leaf) — a real leaf was chosen
   *       directly as the "parent," which is not a valid insertion point; returns an empty
   *       result (no confident answer) rather than fabricating one from its own code.</li>
   *   <li><b>Any other level</b> ({@code "E"}/{@code "C"}, or unknown — see the fallback
   *       below) — not yet at grouping depth, so this drills into the node's real children:
   *       zero children degrades gracefully to the node itself (first-ever subaccount under a
   *       brand-new branch — nothing to drill into, and no leaf-numbering scheme can be
   *       invented from nothing); exactly one child recurses into it (generalizes to any
   *       depth); more than one child surfaces ALL of them as candidates — covering both a
   *       purely-numeric fan-out ({@code 160B -> 1603, 1604}) and a still-letter-suffixed
   *       fan-out ({@code 430A -> 4300A, 4304A, 4309A}, confirmed live) uniformly, since
   *       neither branch inspects the children's {@code Value} text at all. Never
   *       averages/guesses a single answer among multiple candidates.</li>
   * </ul>
   *
   * <p>When {@code ElementLevel} is unavailable for a node (defensive fallback only — every
   * real {@code C_ElementValue} row has it; this exists for corrupted data or a caller that
   * only populated the value/children maps), the terminal test falls back to the pre-ETP-5399
   * heuristic PLUS the missing digit check: {@code Value} is purely numeric AND exactly
   * {@value #PARENT_CODE_LENGTH} characters. This still closes the original bug (a
   * letter-suffixed 4-character value like {@code "430A"} no longer matches) even without
   * {@code ElementLevel} data.
   *
   * <p>Capped at {@value ChartOfAccountsHandler#MAX_TREE_DEPTH} recursive hops, mirroring every
   * other traversal in this class, to guard against a circular reference in corrupted
   * {@code AD_TreeNode} data.
   *
   * @return a possibly-empty {@link JSONArray} of candidates, each {@code {id, value, name,
   *     elementLevel}}; empty when {@code parentNodeId} is unknown, is itself a leaf, or
   *     (not yet at grouping depth) has no children to drill into.
   */
  static JSONArray resolveInsertionChildren(String parentNodeId, Map<String, String> nodeValueMap,
      Map<String, String> nodeNameMap, Map<String, String> nodeElementLevelMap,
      Map<String, List<String>> childrenMap) throws Exception {
    return resolveInsertionChildren(parentNodeId, nodeValueMap, nodeNameMap, nodeElementLevelMap,
        childrenMap, 0);
  }

  private static JSONArray resolveInsertionChildren(String nodeId, Map<String, String> nodeValueMap,
      Map<String, String> nodeNameMap, Map<String, String> nodeElementLevelMap,
      Map<String, List<String>> childrenMap, int depth) throws Exception {
    JSONArray result = new JSONArray();
    String value = nodeValueMap.get(nodeId);
    if (value == null || value.isEmpty()) {
      return result; // unknown/blank node — no confident answer
    }
    String level = nodeElementLevelMap.get(nodeId);

    if (isTerminalGroupingLevel(level, value)) {
      result.put(buildCandidate(nodeId, nodeValueMap, nodeNameMap, nodeElementLevelMap));
      return result;
    }
    if (LEVEL_SUBACCOUNT.equals(level)) {
      return result; // a real leaf chosen directly as "parent" — no confident answer
    }

    List<String> children = childrenMap.getOrDefault(nodeId, Collections.emptyList());
    if (children.isEmpty() || depth >= ChartOfAccountsHandler.MAX_TREE_DEPTH) {
      // Nothing deeper to offer — graceful fallback to this node itself. Covers the
      // first-ever-subaccount case (§3.2.c) and the circular-reference guard uniformly.
      result.put(buildCandidate(nodeId, nodeValueMap, nodeNameMap, nodeElementLevelMap));
      return result;
    }
    if (children.size() == 1) {
      return resolveInsertionChildren(children.get(0), nodeValueMap, nodeNameMap,
          nodeElementLevelMap, childrenMap, depth + 1);
    }
    // More than one real child — surface all of them. Never average/guess a single answer.
    for (String child : children) {
      result.put(buildCandidate(child, nodeValueMap, nodeNameMap, nodeElementLevelMap));
    }
    return result;
  }

  /**
   * @return {@code true} when {@code level} is the Breakdown level ({@value #LEVEL_BREAKDOWN}),
   *     or — when {@code level} is unavailable — {@code value} is purely numeric AND exactly
   *     {@value #PARENT_CODE_LENGTH} characters (the defensive fallback; see this method's
   *     caller javadoc).
   */
  private static boolean isTerminalGroupingLevel(String level, String value) {
    if (level != null) {
      return LEVEL_BREAKDOWN.equals(level);
    }
    return value.length() == PARENT_CODE_LENGTH && NUMERIC_VALUE.matcher(value).matches();
  }

  /**
   * @return the posting prefix derived from the single resolved candidate in an
   *     {@code insertionChildren} array, or {@code null} when it has zero or more than one
   *     entry. Numeric candidates are returned unchanged; a structural letter-suffixed
   *     candidate such as {@code "4300A"} yields its numeric PGC prefix ({@code "4300"}).
   *     Used by {@link ChartOfAccountsHandler#injectCodePrefix} to decide whether a single
   *     {@code codePrefix} can be derived without changing the candidate's structural identity.
   */
  static String resolveSingleInsertionValue(JSONArray insertionChildren) throws Exception {
    if (insertionChildren == null || insertionChildren.length() != 1) {
      return null;
    }
    JSONObject only = insertionChildren.optJSONObject(0);
    if (only == null || only.isNull("value")) {
      return null;
    }
    String value = only.optString("value", null);
    if (value == null || NUMERIC_VALUE.matcher(value).matches()) {
      return value;
    }
    if (value.length() < PARENT_CODE_LENGTH) {
      return null;
    }
    String prefix = value.substring(0, PARENT_CODE_LENGTH);
    return NUMERIC_VALUE.matcher(prefix).matches() ? prefix : null;
  }

  /** Builds one {@code {id, value, name, elementLevel}} candidate entry. */
  private static JSONObject buildCandidate(String nodeId, Map<String, String> nodeValueMap,
      Map<String, String> nodeNameMap, Map<String, String> nodeElementLevelMap) throws Exception {
    JSONObject candidate = new JSONObject();
    candidate.put("id", nodeId);
    candidate.put("value", orNull(nodeValueMap.get(nodeId)));
    candidate.put("name", orNull(nodeNameMap.get(nodeId)));
    candidate.put("elementLevel", orNull(nodeElementLevelMap.get(nodeId)));
    return candidate;
  }
}
