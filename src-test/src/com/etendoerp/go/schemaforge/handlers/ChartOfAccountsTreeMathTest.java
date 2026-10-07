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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link ChartOfAccountsTreeMath#resolveInsertionChildren} and
 * {@link ChartOfAccountsTreeMath#resolveSingleInsertionValue} — the ETP-5399 fix.
 *
 * <p>New file (mirrors the {@link ChartOfAccountsCriteriaTest} split) rather than growing the
 * already-large {@link ChartOfAccountsHandlerTest}, and to keep {@link ChartOfAccountsHandler}'s
 * own test file focused on handler routing/wiring while this one owns the pure tree-math cases.
 *
 * <p>Fixtures use real family shapes confirmed live against this client's chart of accounts
 * (see the ETP-5399 investigation): {@code 430A -> 4300A -> 43001000} (Pattern A, single-hop),
 * {@code 103A -> 1030 -> 10300000} and {@code 160B -> 1603/1604} (Pattern B), and the
 * {@code E -> E|C -> D -> S} {@code ElementLevel} chain that never has an exception across the
 * client's whole 1798-node wired tree.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ChartOfAccountsTreeMathTest {

  private static final String LEVEL_ACCOUNT = "C";
  private static final String LEVEL_BREAKDOWN = "D";
  private static final String LEVEL_SUBACCOUNT = "S";

  /** Minimal fixture builder for {@code resolveInsertionChildren}'s four parallel maps. */
  private static final class TreeFixture {
    final Map<String, String> values = new HashMap<>();
    final Map<String, String> names = new HashMap<>();
    final Map<String, String> levels = new HashMap<>();
    final Map<String, List<String>> children = new HashMap<>();

    TreeFixture node(String id, String value, String name, String level) {
      values.put(id, value);
      names.put(id, name);
      if (level != null) {
        levels.put(id, level);
      }
      return this;
    }

    TreeFixture child(String parentId, String childId) {
      children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(childId);
      return this;
    }

    JSONArray resolve(String nodeId) throws Exception {
      return ChartOfAccountsTreeMath.resolveInsertionChildren(nodeId, values, names, levels,
          children);
    }
  }

  private static JSONObject candidateAt(JSONArray array, int index) throws Exception {
    return array.getJSONObject(index);
  }

  // ── numeric parent — existing/working case, must not change ──────────────

  @Test
  public void numericBreakdownNodeReturnsItselfRegardlessOfExistingLeafChildren() throws Exception {
    // "2000" (D-level, purely numeric) already has real 8-digit leaf children — must still
    // resolve to itself, not drill into the leaves. Regression guard for the pre-ETP-5399
    // working case.
    TreeFixture tree = new TreeFixture()
        .node("2000-id", "2000", "Proveedores", LEVEL_BREAKDOWN)
        .node("leaf-id", "20000001", "Proveedores (euros)", LEVEL_SUBACCOUNT)
        .child("2000-id", "leaf-id");

    JSONArray result = tree.resolve("2000-id");

    assertEquals(1, result.length());
    assertEquals("2000", candidateAt(result, 0).getString("value"));
    assertEquals("2000-id", candidateAt(result, 0).getString("id"));
  }

  @Test
  public void numericAccountNodeDrillsDownToSingleBreakdownChild() throws Exception {
    // "200" (C-level, 3-digit Account) has one Breakdown child "2000" — must drill down, not
    // stop at the shallower Account-level node.
    TreeFixture tree = new TreeFixture()
        .node("200-id", "200", "Proveedores", LEVEL_ACCOUNT)
        .node("2000-id", "2000", "Proveedores (euros)", LEVEL_BREAKDOWN)
        .child("200-id", "2000-id");

    JSONArray result = tree.resolve("200-id");

    assertEquals(1, result.length());
    assertEquals("2000", candidateAt(result, 0).getString("value"));
  }

  // ── letter-suffixed parent — the actual ETP-5399 bug ──────────────────────

  @Test
  public void letterAccountNodeWithSingleLetterChildRecursesToTheRealBreakdownNode() throws Exception {
    // 430A (C) -> 4300A (D) — the exact family from the ticket's root-cause write-up. The old
    // findParentCode4 heuristic returned "430A" itself (4 characters); this must instead
    // resolve to "4300A", the real Breakdown-level insertion point.
    TreeFixture tree = new TreeFixture()
        .node("430A-id", "430A", "Clientes a largo plazo", LEVEL_ACCOUNT)
        .node("4300A-id", "4300A", "Clientes (euros) a largo plazo", LEVEL_BREAKDOWN)
        .child("430A-id", "4300A-id");

    JSONArray result = tree.resolve("430A-id");

    assertEquals(1, result.length());
    assertEquals("4300A", candidateAt(result, 0).getString("value"));
    assertEquals("4300A-id", candidateAt(result, 0).getString("id"));
  }

  @Test
  public void letterBreakdownNodeWithThreeExistingLeavesStillResolvesToItself() throws Exception {
    // 4300A (D) already has THREE leaf children (fan-out AT THE LEAF LEVEL) — confirms the
    // algorithm still stops at the Breakdown node itself and is not confused by, or forced to
    // pick among, its existing leaves.
    TreeFixture tree = new TreeFixture()
        .node("4300A-id", "4300A", "Clientes (euros) a largo plazo", LEVEL_BREAKDOWN)
        .node("leaf1", "43001000", "leaf1", LEVEL_SUBACCOUNT)
        .node("leaf2", "43002000", "leaf2", LEVEL_SUBACCOUNT)
        .node("leaf3", "43003000", "leaf3", LEVEL_SUBACCOUNT)
        .child("4300A-id", "leaf1")
        .child("4300A-id", "leaf2")
        .child("4300A-id", "leaf3");

    JSONArray result = tree.resolve("4300A-id");

    assertEquals(1, result.length());
    assertEquals("4300A", candidateAt(result, 0).getString("value"));
  }

  @Test
  public void letterAccountNodeWithOnePlainNumericChildResolvesToIt() throws Exception {
    // 103A (C) -> 1030 (D, plain numeric) -> 10300000 (S) — Pattern B, one child.
    TreeFixture tree = new TreeFixture()
        .node("103A-id", "103A", "Socios por desembolsos no exigidos", LEVEL_ACCOUNT)
        .node("1030-id", "1030", "Socios por desembolsos no exigidos (euros)", LEVEL_BREAKDOWN)
        .node("leaf-id", "10300000", "leaf", LEVEL_SUBACCOUNT)
        .child("103A-id", "1030-id")
        .child("1030-id", "leaf-id");

    JSONArray result = tree.resolve("103A-id");

    assertEquals(1, result.length());
    assertEquals("1030", candidateAt(result, 0).getString("value"));
  }

  @Test
  public void letterAccountNodeWithTwoPlainNumericChildrenSurfacesBothCandidates() throws Exception {
    // 160B (C) -> 1603, 1604 (both D, plain numeric) — Pattern B, two children. Must never
    // average/guess a single answer.
    TreeFixture tree = new TreeFixture()
        .node("160B-id", "160B", "Deudas a largo plazo, partes vinculadas", LEVEL_ACCOUNT)
        .node("1603-id", "1603", "1603 name", LEVEL_BREAKDOWN)
        .node("1604-id", "1604", "1604 name", LEVEL_BREAKDOWN)
        .child("160B-id", "1603-id")
        .child("160B-id", "1604-id");

    JSONArray result = tree.resolve("160B-id");

    assertEquals(2, result.length());
    List<String> resolvedValues = new ArrayList<>();
    resolvedValues.add(candidateAt(result, 0).getString("value"));
    resolvedValues.add(candidateAt(result, 1).getString("value"));
    assertTrue(resolvedValues.contains("1603"));
    assertTrue(resolvedValues.contains("1604"));
  }

  @Test
  public void letterAccountNodeWithThreePlainNumericChildrenSurfacesAllThree() throws Exception {
    // 795B (C) -> 7951, 7952, 7955 (D) — Pattern B, three children.
    TreeFixture tree = new TreeFixture()
        .node("795B-id", "795B", "795B name", LEVEL_ACCOUNT)
        .node("7951-id", "7951", "7951 name", LEVEL_BREAKDOWN)
        .node("7952-id", "7952", "7952 name", LEVEL_BREAKDOWN)
        .node("7955-id", "7955", "7955 name", LEVEL_BREAKDOWN)
        .child("795B-id", "7951-id")
        .child("795B-id", "7952-id")
        .child("795B-id", "7955-id");

    JSONArray result = tree.resolve("795B-id");

    assertEquals(3, result.length());
  }

  @Test
  public void letterAccountNodeWithSingleNumericChildHavingThreeLeavesStopsAtTheChild() throws Exception {
    // 795A (C) -> 7954 (D) -> 79540000/79544000/79549000 (S, three leaves) — confirms fan-out
    // AT THE LEAF LEVEL below a single-child Breakdown node does not affect resolution: 7954
    // is still the correct, single insertion point.
    TreeFixture tree = new TreeFixture()
        .node("795A-id", "795A", "795A name", LEVEL_ACCOUNT)
        .node("7954-id", "7954", "7954 name", LEVEL_BREAKDOWN)
        .node("leaf1", "79540000", "leaf1", LEVEL_SUBACCOUNT)
        .node("leaf2", "79544000", "leaf2", LEVEL_SUBACCOUNT)
        .node("leaf3", "79549000", "leaf3", LEVEL_SUBACCOUNT)
        .child("795A-id", "7954-id")
        .child("7954-id", "leaf1")
        .child("7954-id", "leaf2")
        .child("7954-id", "leaf3");

    JSONArray result = tree.resolve("795A-id");

    assertEquals(1, result.length());
    assertEquals("7954", candidateAt(result, 0).getString("value"));
  }

  @Test
  public void letterFanOutChildrenThatAreThemselvesLetterSuffixedAreSurfacedDirectly() throws Exception {
    // Real live shape: 430A (C) actually has THREE letter-suffixed Breakdown children
    // (4300A, 4304A, 4309A), not just one. Confirms multi-child fan-out works even when every
    // candidate is still letter-suffixed — the algorithm never inspects the letter itself, so
    // this needs no special casing beyond "more than one real child".
    TreeFixture tree = new TreeFixture()
        .node("430A-id", "430A", "Clientes a largo plazo", LEVEL_ACCOUNT)
        .node("4300A-id", "4300A", "4300A name", LEVEL_BREAKDOWN)
        .node("4304A-id", "4304A", "4304A name", LEVEL_BREAKDOWN)
        .node("4309A-id", "4309A", "4309A name", LEVEL_BREAKDOWN)
        .child("430A-id", "4300A-id")
        .child("430A-id", "4304A-id")
        .child("430A-id", "4309A-id");

    JSONArray result = tree.resolve("430A-id");

    assertEquals(3, result.length());
  }

  // ── zero-existing-children — graceful degradation (§3.2.c) ────────────────

  @Test
  public void letterAccountNodeWithZeroChildrenDegradesGracefullyToItself() throws Exception {
    // First-ever subaccount under a brand-new letter branch — nothing to drill into. Must not
    // crash; falls back to the node itself as a prefix-only candidate.
    TreeFixture tree = new TreeFixture()
        .node("999X-id", "999X", "Brand new family", LEVEL_ACCOUNT);

    JSONArray result = tree.resolve("999X-id");

    assertEquals(1, result.length());
    assertEquals("999X", candidateAt(result, 0).getString("value"));
  }

  @Test
  public void numericAccountNodeWithZeroChildrenDegradesGracefullyToItself() throws Exception {
    TreeFixture tree = new TreeFixture().node("500-id", "500", "Brand new numeric family",
        LEVEL_ACCOUNT);

    JSONArray result = tree.resolve("500-id");

    assertEquals(1, result.length());
    assertEquals("500", candidateAt(result, 0).getString("value"));
  }

  // ── a real leaf chosen directly — no confident answer ─────────────────────

  @Test
  public void subaccountLevelNodeChosenDirectlyReturnsEmpty() throws Exception {
    TreeFixture tree = new TreeFixture().node("leaf-id", "20000001", "leaf", LEVEL_SUBACCOUNT);

    JSONArray result = tree.resolve("leaf-id");

    assertEquals(0, result.length());
  }

  // ── unknown / blank node ───────────────────────────────────────────────────

  @Test
  public void unknownNodeReturnsEmpty() throws Exception {
    TreeFixture tree = new TreeFixture();
    JSONArray result = tree.resolve("ghost");
    assertEquals(0, result.length());
  }

  @Test
  public void nullNodeIdReturnsEmpty() throws Exception {
    TreeFixture tree = new TreeFixture();
    JSONArray result = tree.resolve(null);
    assertEquals(0, result.length());
  }

  // ── ElementLevel-unavailable fallback (defensive) ─────────────────────────

  @Test
  public void fallbackWithoutElementLevelStillRejectsLetterSuffixedFourCharValue() throws Exception {
    // No ElementLevel populated at all (defensive path). "430A" is 4 characters but NOT purely
    // numeric — must NOT be treated as terminal (this is the exact original bug). With a
    // numeric single child available, it must drill down to it instead.
    TreeFixture tree = new TreeFixture()
        .node("430A-id", "430A", "Clientes a largo plazo", null)
        .node("4300-id", "4300", "4300 name", null)
        .child("430A-id", "4300-id");

    JSONArray result = tree.resolve("430A-id");

    assertEquals(1, result.length());
    assertEquals("4300", candidateAt(result, 0).getString("value"));
  }

  @Test
  public void fallbackWithoutElementLevelAcceptsFourDigitNumericValueAsTerminal() throws Exception {
    TreeFixture tree = new TreeFixture()
        .node("2000-id", "2000", "Proveedores", null)
        .node("leaf-id", "20000001", "leaf", null)
        .child("2000-id", "leaf-id");

    JSONArray result = tree.resolve("2000-id");

    assertEquals(1, result.length());
    assertEquals("2000", candidateAt(result, 0).getString("value"));
  }

  // ── circular-reference guard (reuses the MAX_TREE_DEPTH pattern) ──────────

  @Test
  public void circularSingleChildChainIsCappedAtMaxTreeDepth() throws Exception {
    // n0 -> n1 -> n2 -> ... -> n0 (cycle), every node Account-level with exactly one child, so
    // resolveInsertionChildren keeps recursing. Must terminate instead of looping forever.
    TreeFixture tree = new TreeFixture();
    int cycleSize = 40; // > MAX_TREE_DEPTH (30)
    for (int i = 0; i < cycleSize; i++) {
      tree.node("n" + i, "n" + i + "A", "node " + i, LEVEL_ACCOUNT);
    }
    for (int i = 0; i < cycleSize; i++) {
      tree.child("n" + i, "n" + ((i + 1) % cycleSize));
    }

    JSONArray result = tree.resolve("n0");

    // The depth guard must stop it — any finite, non-throwing result is a pass.
    assertTrue("result must be bounded, not an infinite recursion", result.length() <= 1);
  }

  // ── resolveSingleInsertionValue ────────────────────────────────────────────

  @Test
  public void resolveSingleInsertionValueReturnsNullForNullArray() throws Exception {
    assertNull(ChartOfAccountsTreeMath.resolveSingleInsertionValue(null));
  }

  @Test
  public void resolveSingleInsertionValueReturnsNullForEmptyArray() throws Exception {
    assertNull(ChartOfAccountsTreeMath.resolveSingleInsertionValue(new JSONArray()));
  }

  @Test
  public void resolveSingleInsertionValueReturnsNullForMultipleCandidates() throws Exception {
    JSONArray array = new JSONArray();
    array.put(new JSONObject().put("id", "a").put("value", "1603"));
    array.put(new JSONObject().put("id", "b").put("value", "1604"));
    assertNull(ChartOfAccountsTreeMath.resolveSingleInsertionValue(array));
  }

  @Test
  public void resolveSingleInsertionValueReturnsNumericPrefixForLetterSuffixedCandidate()
      throws Exception {
    JSONArray array = new JSONArray();
    array.put(new JSONObject().put("id", "a").put("value", "4300A"));
    assertEquals("4300", ChartOfAccountsTreeMath.resolveSingleInsertionValue(array));
  }

  @Test
  public void resolveSingleInsertionValueKeepsNumericCandidateUnchanged() throws Exception {
    JSONArray array = new JSONArray();
    array.put(new JSONObject().put("id", "a").put("value", "1603"));
    assertEquals("1603", ChartOfAccountsTreeMath.resolveSingleInsertionValue(array));
  }

  @Test
  public void resolveSingleInsertionValueRejectsCandidateWithoutFourLeadingDigits()
      throws Exception {
    JSONArray array = new JSONArray();
    array.put(new JSONObject().put("id", "a").put("value", "99XA"));
    assertNull(ChartOfAccountsTreeMath.resolveSingleInsertionValue(array));
  }

  @Test
  public void resolveSingleInsertionValueReturnsNullWhenValueIsJsonNull() throws Exception {
    JSONArray array = new JSONArray();
    JSONObject candidate = new JSONObject().put("id", "a");
    candidate.put("value", JSONObject.NULL);
    array.put(candidate);
    assertNull(ChartOfAccountsTreeMath.resolveSingleInsertionValue(array));
  }
}
