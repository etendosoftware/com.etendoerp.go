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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountReportTree} — the pure Java port of
 * {@code report-grouping.js}'s {@code buildAccountReportTree} (ETP-5483 slice 4). No DB, no
 * OBContext: plain data in, plain data out.
 */
class AccountReportTreeTest {

  private static AccountReportTree.NodeRow node(String id, String parent, String sortPath,
      String group, String value, String name, String level, boolean alwaysShown, String sign,
      String own, String ownRef) {
    return new AccountReportTree.NodeRow(id, parent, sortPath, group, value, name, level,
        alwaysShown, sign, new BigDecimal(own), new BigDecimal(ownRef));
  }

  private static AccountReportTree.OperandRow operand(String owner, String operand, int sign) {
    return new AccountReportTree.OperandRow(owner, operand, sign);
  }

  /**
   * A two-root tree mirroring Balance Sheet's shape: root R1 (debit-normal, group G1) with a
   * heading A containing two leaves and a formula node summing them; root R2 (credit-normal,
   * group G2) with a single leaf.
   */
  private static List<AccountReportTree.NodeRow> twoRootTree() {
    return List.of(
        node("R1", null, "000001", "G1", "R1", "Root1", "E", false, "D", "0", "0"),
        node("A", "R1", "000001.000001", "G1", "A", "Heading A", "E", false, "D", "0", "0"),
        node("L1", "A", "000001.000001.000001", "G1", "100", "Cash", "C", false, "D", "100", "50"),
        node("L2", "A", "000001.000001.000002", "G1", "200", "Bank", "C", false, "D", "-30", "0"),
        node("F", "R1", "000001.000002", "G1", "F", "Total (100+200)", "E", false, "D", "0", "0"),
        node("R2", null, "000002", "G2", "R2", "Root2", "E", false, "C", "0", "0"),
        node("L3", "R2", "000002.000001", "G2", "700", "Sales", "S", false, "C", "500", "0"));
  }

  private static List<AccountReportTree.OperandRow> twoRootOperands() {
    return List.of(operand("F", "L1", 1), operand("F", "L2", 1));
  }

  private static AccountReportTree.OutputRow findById(List<AccountReportTree.OutputRow> rows,
      String nodeId) {
    return rows.stream().filter(r -> nodeId.equals(r.nodeId)).findFirst()
        .orElseThrow(() -> new AssertionError("row " + nodeId + " not found in " + rows.size()
            + " rows"));
  }

  // -------------------------------------------------------------------------
  // Basic roll-up and sign propagation
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("a node's amount is the roll-up of its children")
  void rollsUpChildrenIntoParent() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    AccountReportTree.OutputRow a = findById(rows, "A");
    assertEquals(0, new BigDecimal("70").compareTo(a.amount),
        "Heading A must sum its two leaves: 100 + (-30) = 70");
  }

  @Test
  @DisplayName("a leaf under a credit-normal root has its sign flipped")
  void creditNormalRootFlipsSign() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    AccountReportTree.OutputRow l3 = findById(rows, "L3");
    assertEquals(0, new BigDecimal("-500").compareTo(l3.amount),
        "L3 inherits R2's credit-normal (C) sign, so its raw +500 posting displays as -500");
  }

  @Test
  @DisplayName("a leaf under a debit-normal root keeps its raw sign")
  void debitNormalRootKeepsSign() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    AccountReportTree.OutputRow l1 = findById(rows, "L1");
    assertEquals(0, new BigDecimal("100").compareTo(l1.amount));
  }

  // -------------------------------------------------------------------------
  // Formula nodes
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("a childless node with operands is a formula node summing its operands")
  void formulaNodeSumsOperands() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    AccountReportTree.OutputRow f = findById(rows, "F");
    assertEquals(0, new BigDecimal("70").compareTo(f.amount),
        "F = L1 (100) + L2 (-30), each with operand sign +1");
    assertTrue(f.isFormula);
  }

  @Test
  @DisplayName("a formula node's amount_ref sums its operands' reference amounts")
  void formulaNodeSumsReferenceAmounts() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    AccountReportTree.OutputRow f = findById(rows, "F");
    assertEquals(0, new BigDecimal("50").compareTo(f.amountRef),
        "F's reference amount = L1's own_amt_ref (50) + L2's own_amt_ref (0)");
  }

  @Test
  @DisplayName("a non-formula node (has children) is never flagged isFormula")
  void nodeWithChildrenIsNotFormula() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    assertFalse(findById(rows, "A").isFormula);
  }

  @Test
  @DisplayName("a formula referencing an operand that does not resolve is skipped, not thrown")
  void formulaWithMissingOperandTargetIsTotal() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("F", "R", "000001.000001", "G", "F", "Formula", "E", false, "D", "0", "0"));
    List<AccountReportTree.OperandRow> operands = List.of(operand("F", "GHOST", 1));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, operands, "S", false);

    assertEquals(0, BigDecimal.ZERO.compareTo(findById(rows, "F").amount));
  }

  @Test
  @DisplayName("a formula that references itself does not infinite-loop and resolves to zero")
  void selfReferencingFormulaIsTotal() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("F", "R", "000001.000001", "G", "F", "Formula", "E", false, "D", "0", "0"));
    List<AccountReportTree.OperandRow> operands = List.of(operand("F", "F", 1));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, operands, "S", false);

    assertEquals(0, BigDecimal.ZERO.compareTo(findById(rows, "F").amount),
        "malformed self-referencing data must resolve to zero, not throw or hang");
  }

  // -------------------------------------------------------------------------
  // accountLevel cutoff
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("accountLevel='S' (finest) includes every level")
  void finestLevelIncludesEverything() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    assertEquals(5, rows.size(), "A, L1, L2, F, L3 — every non-root node (R1/R2 are containers)");
  }

  @Test
  @DisplayName("accountLevel='E' (coarsest) cuts off before Account/Subaccount levels")
  void coarsestLevelCutsOffDescendants() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "E", false);

    List<String> ids = rows.stream().map(r -> r.nodeId).toList();
    assertEquals(List.of("A", "F"), ids,
        "only the 'E' (Heading) level nodes survive; L1/L2 ('C') and L3 ('S') are cut off, and "
            + "R2 (a root, never emitted) has no 'E' descendant to replace it");
  }

  @Test
  @DisplayName("a node with an unrecognised/blank elementlevel is never cut off")
  void unknownElementLevelIsNeverCutOff() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("X", "R", "000001.000001", "G", "X", "Unknown level", "", false, "D", "10", "0"));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, List.of(), "E", false);

    assertEquals(1, rows.size());
  }

  // -------------------------------------------------------------------------
  // showOnlyWithValue
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("showOnlyWithValue drops a zero-amount node that is not always-shown")
  void showOnlyWithValueDropsZeroNode() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("Z", "R", "000001.000001", "G", "300", "Zero account", "C", false, "D", "0", "0"));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, List.of(), "S", true);

    assertTrue(rows.isEmpty());
  }

  @Test
  @DisplayName("showOnlyWithValue keeps a zero-amount node marked isalwaysshown")
  void showOnlyWithValueKeepsAlwaysShownZeroNode() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("Z", "R", "000001.000001", "G", "300", "Always shown", "C", true, "D", "0", "0"));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, List.of(), "S", true);

    assertEquals(1, rows.size());
  }

  @Test
  @DisplayName("showOnlyWithValue keeps a node whose amount_ref alone is non-zero")
  void showOnlyWithValueKeepsNodeWithOnlyReferenceValue() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("Z", "R", "000001.000001", "G", "300", "Ref only", "C", false, "D", "0", "40"));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, List.of(), "S", true);

    assertEquals(1, rows.size(),
        "showOnlyWithValue must keep a row when EITHER amount or amount_ref is non-zero");
  }

  @Test
  @DisplayName("showOnlyWithValue=false keeps every node regardless of amount")
  void showOnlyWithValueFalseKeepsEverything() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G", "R", "Root", "E", false, "D", "0", "0"),
        node("Z", "R", "000001.000001", "G", "300", "Zero account", "C", false, "D", "0", "0"));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, List.of(), "S", false);

    assertEquals(1, rows.size());
  }

  // -------------------------------------------------------------------------
  // Group-start flag
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("the first row of a second group is flagged isGroupStart when there is more than one group")
  void secondGroupFirstRowIsFlagged() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    assertTrue(findById(rows, "A").isGroupStart, "the very first row is forced to true when the "
        + "report has more than one group");
    assertFalse(findById(rows, "L1").isGroupStart);
    assertTrue(findById(rows, "L3").isGroupStart, "L3 is the first row of the second group (G2)");
  }

  @Test
  @DisplayName("a single-group report never flags isGroupStart past the first row")
  void singleGroupNeverFlagsGroupStart() {
    List<AccountReportTree.NodeRow> nodes = List.of(
        node("R", null, "000001", "G1", "R", "Root", "E", false, "D", "0", "0"),
        node("X", "R", "000001.000001", "G1", "100", "X", "C", false, "D", "5", "0"),
        node("Y", "R", "000001.000002", "G1", "200", "Y", "C", false, "D", "5", "0"));

    List<AccountReportTree.OutputRow> rows = AccountReportTree.build(nodes, List.of(), "S", false);

    assertTrue(rows.stream().noneMatch(r -> r.isGroupStart));
  }

  // -------------------------------------------------------------------------
  // Totality — null/empty input never throws
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("null node rows and null operand rows never throw, and return an empty result")
  void nullInputIsTotal() {
    assertTrue(AccountReportTree.build(null, null, "S", false).isEmpty());
  }

  @Test
  @DisplayName("empty node rows never throw, and return an empty result")
  void emptyInputIsTotal() {
    assertTrue(AccountReportTree.build(List.of(), List.of(), "C", true).isEmpty());
  }

  @Test
  @DisplayName("an unrecognised accountLevel falls back to 'S' (finest, no cutoff)")
  void unrecognisedAccountLevelFallsBackToFinest() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "not-a-level", false);

    assertEquals(5, rows.size());
  }

  // -------------------------------------------------------------------------
  // Indentation
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("a root's children start at indent 0, and depth increases by one per level")
  void indentTracksDepthFromRootChildren() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(twoRootTree(), twoRootOperands(), "S", false);

    assertEquals(0, findById(rows, "A").indent);
    assertEquals(1, findById(rows, "L1").indent);
    assertEquals(0, findById(rows, "L3").indent);
  }
}
