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
 *
 * @covers com.etendoerp.go.schemaforge.AccountReportTree
 */
class AccountReportTreeTest {

  private static AccountReportTree.NodeRow node(String id, String parent, String sortPath,
      String group, String value, String name, String level, boolean alwaysShown, String sign,
      String own, String ownRef) {
    return AccountReportTree.NodeRow.builder()
        .nodeId(id)
        .parentId(parent)
        .sortPath(sortPath)
        .groupName(group)
        .value(value)
        .name(name)
        .elementLevel(level)
        .alwaysShown(alwaysShown)
        .accountSign(sign)
        .ownAmt(new BigDecimal(own))
        .ownAmtRef(new BigDecimal(ownRef))
        .build();
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

  // -------------------------------------------------------------------------
  // ShowValueCond (ETP-5662)
  // -------------------------------------------------------------------------

  /** A node carrying {@code ShowValueCond} / {@code IsSummary}; own amount is Dr-Cr. */
  private static AccountReportTree.NodeRow svcNode(String id, String parent, String sortPath,
      String group, String value, String level, String sign, String own, String ownRef,
      String svc, boolean summary) {
    return AccountReportTree.NodeRow.builder()
        .nodeId(id).parentId(parent).sortPath(sortPath).groupName(group).value(value)
        .name(value).elementLevel(level).accountSign(sign)
        .ownAmt(new BigDecimal(own)).ownAmtRef(new BigDecimal(ownRef))
        .showValueCond(svc).summary(summary).build();
  }

  /**
   * Mini PGC balance sheet. Activo (D): A.B &gt; 551(P) &gt; 5510(P) &gt; 55100000 leaf; A.TOTAL =
   * A.B. Pasivo (C): (551)(P) &gt; (5510)(P formula, -1 x 5510); 555 leaf. {@code dr} is the
   * Dr-Cr posted on 55100000, {@code dr555} on 555.
   */
  private static List<AccountReportTree.NodeRow> mirrorTree(String dr, String dr555) {
    return List.of(
        svcNode("A", null, "1", "A", "A", "E", "D", "0", "0", null, true),
        svcNode("AB", "A", "1.1", "A", "A.B", "E", "D", "0", "0", null, true),
        svcNode("551", "AB", "1.1.1", "A", "551", "C", "D", "0", "0", "P", true),
        svcNode("5510", "551", "1.1.1.1", "A", "5510", "D", "D", "0", "0", "P", true),
        svcNode("55100000", "5510", "1.1.1.1.1", "A", "55100000", "S", "D", dr, "0", null,
            false),
        svcNode("ATOT", "A", "1.2", "A", "A.TOTAL", "E", "D", "0", "0", null, true),
        svcNode("P", null, "2", "P", "P", "E", "C", "0", "0", null, true),
        svcNode("M551", "P", "2.1", "P", "(551)", "C", "C", "0", "0", "P", true),
        svcNode("M5510", "M551", "2.1.1", "P", "(5510)", "D", "C", "0", "0", "P", true),
        svcNode("555", "P", "2.2", "P", "555", "C", "C", dr555, "0", null, false));
  }

  private static List<AccountReportTree.OperandRow> mirrorOperands() {
    return List.of(operand("M5510", "5510", -1), operand("ATOT", "AB", 1));
  }

  private static BigDecimal amt(List<AccountReportTree.OutputRow> rows, String id) {
    return findById(rows, id).amount;
  }

  private static boolean has(List<AccountReportTree.OutputRow> rows, String id) {
    return rows.stream().anyMatch(r -> id.equals(r.nodeId));
  }

  @Test
  @DisplayName("oracle case 1: credit balance on 551 hides Activo side, mirror shows it")
  void oracleCreditBalance() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(mirrorTree("-1", "1"), mirrorOperands(), "S", true);

    assertFalse(has(rows, "AB"));
    assertFalse(has(rows, "551"));
    assertFalse(has(rows, "5510"));
    assertFalse(has(rows, "55100000"), "cascade: descendants of a reset node show 0");
    assertFalse(has(rows, "ATOT"), "a total over a clamped subtree is 0");
    assertEquals(0, amt(rows, "M5510").compareTo(BigDecimal.ONE));
    assertEquals(0, amt(rows, "M551").compareTo(BigDecimal.ONE));
    assertEquals(0, amt(rows, "555").compareTo(BigDecimal.ONE.negate()));
    assertTrue(rows.get(0).isGroupStart,
        "only Pasivo is visible, yet its group header must still be drawn");
  }

  @Test
  @DisplayName("oracle case 2: debit balance on 551 shows Activo side, mirror is hidden")
  void oracleDebitBalance() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(mirrorTree("5", "-5"), mirrorOperands(), "S", true);

    for (String id : List.of("AB", "551", "5510", "55100000", "ATOT")) {
      assertEquals(0, amt(rows, id).compareTo(new BigDecimal("5")), id);
    }
    assertFalse(has(rows, "M551"));
    assertFalse(has(rows, "M5510"));
  }

  @Test
  @DisplayName("N clamp keeps only negative values; non-summary and null svc pass through")
  void negativeClampAndPassThrough() {
    List<AccountReportTree.NodeRow> rows = List.of(
        svcNode("R", null, "1", "G", "R", "E", "D", "0", "0", null, true),
        svcNode("N1", "R", "1.1", "G", "N1", "C", "D", "5", "0", "N", true),
        svcNode("N2", "R", "1.2", "G", "N2", "C", "D", "-5", "0", "N", true),
        svcNode("L", "R", "1.3", "G", "L", "C", "D", "5", "0", "P", false),
        svcNode("X", "R", "1.4", "G", "X", "C", "D", "-5", "0", null, true),
        svcNode("A", "R", "1.5", "G", "A", "C", "D", "-5", "0", "A", true));
    List<AccountReportTree.OutputRow> out = AccountReportTree.build(rows, List.of(), "S", false);

    assertEquals(0, amt(out, "N1").signum());
    assertEquals(0, amt(out, "N2").compareTo(new BigDecimal("-5")));
    assertEquals(0, amt(out, "L").compareTo(new BigDecimal("5")));
    assertEquals(0, amt(out, "X").compareTo(new BigDecimal("-5")));
    assertEquals(0, amt(out, "A").compareTo(new BigDecimal("-5")));
  }

  @Test
  @DisplayName("clamped child contributes 0 to its parent roll-up")
  void parentExcludesClampedChild() {
    List<AccountReportTree.NodeRow> rows = List.of(
        svcNode("R", null, "1", "G", "R", "E", "D", "0", "0", null, true),
        svcNode("H", "R", "1.1", "G", "H", "E", "D", "0", "0", null, true),
        svcNode("C1", "H", "1.1.1", "G", "C1", "C", "D", "-3", "0", "P", true),
        svcNode("C2", "H", "1.1.2", "G", "C2", "C", "D", "4", "0", null, false));
    List<AccountReportTree.OutputRow> out = AccountReportTree.build(rows, List.of(), "S", true);

    assertEquals(0, amt(out, "H").compareTo(new BigDecimal("4")));
    assertFalse(has(out, "C1"));
  }

  @Test
  @DisplayName("reference-period reset is independent of the main period")
  void referenceResetIsIndependent() {
    List<AccountReportTree.NodeRow> rows = List.of(
        svcNode("R", null, "1", "G", "R", "E", "D", "0", "0", null, true),
        svcNode("S", "R", "1.1", "G", "S", "C", "D", "0", "0", "P", true),
        svcNode("K", "S", "1.1.1", "G", "K", "S", "D", "7", "-2", null, false));
    List<AccountReportTree.OutputRow> out = AccountReportTree.build(rows, List.of(), "S", false);

    assertEquals(0, amt(out, "K").compareTo(new BigDecimal("7")));
    assertEquals(0, findById(out, "K").amountRef.signum(), "ref period was reset");
    assertEquals(0, findById(out, "S").amountRef.signum());
  }

  @Test
  @DisplayName("a tree with no operand rows: mirror formula node resolves to 0 (R39 precondition)")
  void noOperandsLeavesMirrorEmpty() {
    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(mirrorTree("-1", "1"), List.of(), "S", true);

    assertFalse(has(rows, "551"));
    assertFalse(has(rows, "M551"));
  }

  /**
   * The PGC 552 cross-wired pair, as the chart defines it: on the Activo side {@code 552A} groups
   * {@code 5523 + 5524} and {@code 552B} holds {@code 5525}; on the Pasivo side the mirrors are
   * wired the other way round, {@code (552A)} holds the formula {@code (5525)} and {@code (552B)}
   * holds the formulas {@code (5523) + (5524)}. Every node is a summary with {@code P}. Posted
   * Dr-Cr: 55230000 +4, 55240000 -2, 55250000 -3.
   */
  private static List<AccountReportTree.NodeRow> crossWired552Tree() {
    return List.of(
        svcNode("A", null, "1", "A", "A", "E", "D", "0", "0", null, true),
        svcNode("552A", "A", "1.1", "A", "552A", "C", "D", "0", "0", "P", true),
        svcNode("5523", "552A", "1.1.1", "A", "5523", "D", "D", "0", "0", "P", true),
        svcNode("55230000", "5523", "1.1.1.1", "A", "55230000", "S", "D", "4", "0", "A", false),
        svcNode("5524", "552A", "1.1.2", "A", "5524", "D", "D", "0", "0", "P", true),
        svcNode("55240000", "5524", "1.1.2.1", "A", "55240000", "S", "D", "-2", "0", "A", false),
        svcNode("552B", "A", "1.2", "A", "552B", "C", "D", "0", "0", "P", true),
        svcNode("5525", "552B", "1.2.1", "A", "5525", "D", "D", "0", "0", "P", true),
        svcNode("55250000", "5525", "1.2.1.1", "A", "55250000", "S", "D", "-3", "0", "A", false),
        svcNode("P", null, "2", "P", "P", "E", "C", "0", "0", null, true),
        svcNode("M552A", "P", "2.1", "P", "(552A)", "C", "C", "0", "0", "P", true),
        svcNode("M5525", "M552A", "2.1.1", "P", "(5525)", "D", "C", "0", "0", "P", true),
        svcNode("M552B", "P", "2.2", "P", "(552B)", "C", "C", "0", "0", "P", true),
        svcNode("M5523", "M552B", "2.2.1", "P", "(5523)", "D", "C", "0", "0", "P", true),
        svcNode("M5524", "M552B", "2.2.2", "P", "(5524)", "D", "C", "0", "0", "P", true));
  }

  @Test
  @DisplayName("552 cross-wired pair: each mirror reads its own operand's raw value and clamps")
  void crossWired552PairResolvesAndClamps() {
    List<AccountReportTree.OperandRow> operands = List.of(
        operand("M5523", "5523", -1), operand("M5524", "5524", -1),
        operand("M5525", "5525", -1));

    List<AccountReportTree.OutputRow> rows =
        AccountReportTree.build(crossWired552Tree(), operands, "S", true);

    // Activo: 5524 (-2) and 5525 (-3) are clamped to 0, so 552A keeps only 5523 and 552B is 0.
    assertEquals(0, amt(rows, "552A").compareTo(new BigDecimal("4")));
    assertEquals(0, amt(rows, "5523").compareTo(new BigDecimal("4")));
    assertEquals(0, amt(rows, "55230000").compareTo(new BigDecimal("4")));
    for (String id : List.of("5524", "55240000", "552B", "5525", "55250000")) {
      assertFalse(has(rows, id), id + " must be hidden (clamped, or under a reset ancestor)");
    }
    // Pasivo: each mirror reads its OWN operand, not its Activo sibling's parent.
    assertFalse(has(rows, "M5523"), "(5523) = -4 is clamped by P");
    assertEquals(0, amt(rows, "M5524").compareTo(new BigDecimal("2")));
    assertEquals(0, amt(rows, "M552B").compareTo(new BigDecimal("2")),
        "(552B) = (5523) clamped 0 + (5524) 2");
    assertEquals(0, amt(rows, "M5525").compareTo(new BigDecimal("3")));
    assertEquals(0, amt(rows, "M552A").compareTo(new BigDecimal("3")),
        "(552A) holds (5525), cross-wired against 552A holding 5523 + 5524");
  }

  @Test
  @DisplayName("a zero-net parent hides while its children with value still render")
  void zeroNetParentHidesButChildrenRender() {
    List<AccountReportTree.NodeRow> rows = List.of(
        svcNode("R", null, "1", "G", "R", "E", "C", "0", "0", null, true),
        svcNode("H", "R", "1.1", "G", "H", "E", "C", "0", "0", null, true),
        svcNode("C1", "H", "1.1.1", "G", "C1", "C", "C", "5", "0", null, false),
        svcNode("C2", "H", "1.1.2", "G", "C2", "C", "C", "-5", "0", null, false));

    List<AccountReportTree.OutputRow> out = AccountReportTree.build(rows, List.of(), "S", true);

    assertFalse(has(out, "H"), "the heading nets to 0 and is dropped by only-with-value");
    assertEquals(List.of("C1", "C2"), out.stream().map(r -> r.nodeId).toList(),
        "Classic keeps the rows without their heading; they must not be dropped with it");
    assertEquals(0, amt(out, "C1").compareTo(new BigDecimal("-5")));
    assertEquals(0, amt(out, "C2").compareTo(new BigDecimal("5")));
  }

  @Test
  @DisplayName("N clamp on a formula node keeps only a strictly negative result")
  void negativeClampOnFormulaNode() {
    List<AccountReportTree.NodeRow> rows = List.of(
        svcNode("R", null, "1", "G", "R", "E", "D", "0", "0", null, true),
        svcNode("POS", "R", "1.1", "G", "POS", "C", "D", "6", "0", null, false),
        svcNode("NEG", "R", "1.2", "G", "NEG", "C", "D", "-6", "0", null, false),
        svcNode("FP", "R", "1.3", "G", "FP", "C", "D", "0", "0", "N", true),
        svcNode("FN", "R", "1.4", "G", "FN", "C", "D", "0", "0", "N", true),
        svcNode("FZ", "R", "1.5", "G", "FZ", "C", "D", "0", "0", "N", true));
    List<AccountReportTree.OperandRow> operands = List.of(
        operand("FP", "POS", 1), operand("FN", "NEG", 1),
        operand("FZ", "POS", 1), operand("FZ", "NEG", 1));

    List<AccountReportTree.OutputRow> out = AccountReportTree.build(rows, operands, "S", false);

    assertTrue(findById(out, "FP").isFormula);
    assertEquals(0, amt(out, "FP").signum(), "+6 is not strictly negative, so N clamps it to 0");
    assertEquals(0, amt(out, "FN").compareTo(new BigDecimal("-6")));
    assertEquals(0, amt(out, "FZ").signum(), "0 is not strictly negative either");
  }

  @Test
  @DisplayName("a single-root report never forces a group header, even when its first rows hide")
  void singleRootReportGetsNoForcedGroupHeader() {
    List<AccountReportTree.NodeRow> rows = List.of(
        svcNode("R", null, "1", "PyG", "R", "E", "C", "0", "0", null, true),
        svcNode("Z", "R", "1.1", "PyG", "Z", "C", "C", "0", "0", null, false),
        svcNode("X", "R", "1.2", "PyG", "X", "C", "C", "-10", "0", null, false),
        svcNode("Y", "R", "1.3", "PyG", "Y", "C", "C", "4", "0", null, false));

    List<AccountReportTree.OutputRow> out = AccountReportTree.build(rows, List.of(), "S", true);

    assertEquals(List.of("X", "Y"), out.stream().map(r -> r.nodeId).toList());
    assertTrue(out.stream().noneMatch(r -> r.isGroupStart),
        "one root means one group, so no row is flagged isGroupStart");
  }
}
