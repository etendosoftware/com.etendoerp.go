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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java port of {@code schema_forge_core/cli/src/report-grouping.js}'s
 * {@code buildAccountReportTree} (ETP-4899), the roll-up/formula engine BOTH Balance Sheet
 * ({@link BalanceSheetReportHandler}, ETP-5483 slice 4) and Profit &amp; Loss render — Classic's
 * {@code AccountTree} engine (the {@code GeneralAccountingReports} process) drives both reports
 * with the SAME Java class, differing only in which {@code C_ACCT_RPT} (reporttype {@code 'N'} vs
 * {@code 'Y'}) feeds the SQL, exactly like this one function now does on both the Node and Java
 * sides.
 *
 * <p><b>Why this exists as its own pure class.</b> No DB, no OBContext, no JSON — plain data in,
 * plain data out — so the roll-up, formula-node and cutoff rules can be unit tested directly,
 * without spinning up Etendo. It takes the FLAT node list the contract's {@code sql.query} returns
 * (one row per node reachable from the accounting report's root(s), carrying its own raw posted
 * amount) plus the formula edges from {@code sql.operandsQuery}, and returns the same flattened,
 * document-ordered, indented rows the SPA's Handlebars template renders.
 *
 * <p><b>Reuse contract for Profit &amp; Loss.</b> {@code artifacts/profit-loss/report-contract.json}'s
 * {@code sql.query} produces the exact same node-row column shape (node_id/parent_id/depth/
 * sort_path/group_name/value/name/elementlevel/isalwaysshown/accountsign/own_amt/own_amt_ref) from
 * the same {@code tree}/{@code roots} CTE skeleton, differing only in which {@code facts} CTE feeds
 * {@code own_amt} (period activity via {@code BETWEEN} vs Balance Sheet's cumulative-to-date
 * {@code <=}) — a difference that lives entirely in the SQL that PRODUCES {@link NodeRow}s, never
 * in this class. A future {@code ProfitLossReportHandler} can therefore call {@link #build} with no
 * change to this file: it only needs its own SQL producing {@link NodeRow}/{@link OperandRow}.
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> This is a
 * hand-maintained Java copy of a JavaScript module, exactly like {@link TrialBalanceFolding} is for
 * {@code foldAggregateRows} and {@link JournalEntriesGrouping} is for its own nesting. The two are
 * NOT structurally linked — nothing fails to compile or to run if one changes and the other
 * doesn't. Any change to {@code buildAccountReportTree} in {@code report-grouping.js} (the
 * sign-propagation rule, the formula-resolution algorithm, the level cutoff, the "has value"
 * threshold, the group-start rule) MUST be mirrored here by hand, and vice versa.
 *
 * <p>Four Classic behaviours this reproduces, all verified against {@code report-grouping.js}'s own
 * javadoc-equivalent comment (itself verified against real PDFs):
 * <ul>
 *   <li>A node's value is the roll-up of its children; a node with NO children but WITH operands is
 *       a <i>formula</i> node instead ({@code hasOperand} -&gt; {@code operandsCalculate}), e.g.
 *       "A) RESULTADO DE EXPLOTACIÓN (1+2+...+12)". Formulas nest (C = A+B), hence the recursion
 *       plus cycle guard.</li>
 *   <li>Every node's OWN amount is displayed in its branch's inherited sign, not a fixed per-report
 *       rule ({@code applySignAsPerParent}): a node's sign is forced to match its root's
 *       {@code accountsign}, regardless of what its own row carries — Balance Sheet's {@code A}
 *       (Activo) root is debit-normal while {@code P} (Patrimonio Neto y Pasivo) is credit-normal,
 *       so the SAME account-shaped node prints with opposite polarity depending which branch it is
 *       filed under.</li>
 *   <li>{@code accountLevel} is a CUMULATIVE DEPTH CUTOFF, not an equality filter: everything from
 *       the root down to and including that level is shown, and the walk stops there ({@code
 *       levelFilter}'s sticky {@code found} flag).</li>
 *   <li>{@code showOnlyWithValue} keeps a node when EITHER period is non-zero (an OR of {@code
 *       amount}/{@code amount_ref}), and never hides a node whose row carries {@code
 *       isalwaysshown = 'Y'}.</li>
 * </ul>
 */
final class AccountReportTree {

  private AccountReportTree() {
    // Static utility, never instantiated.
  }

  /** Chart-of-accounts depth ranking (ETP-4899), coarsest first — mirrors report-grouping.js's ELEMENT_LEVEL_RANK. */
  private static final Map<String, Integer> ELEMENT_LEVEL_RANK = Map.of(
      "E", 0,
      "C", 1,
      "D", 2,
      "S", 3);

  private static final BigDecimal HAS_VALUE_EPSILON = new BigDecimal("0.005");

  /**
   * One flat input row — the shape a {@code sql.query} that declares {@code operandsQuery} returns:
   * one row per node reachable from the accounting report's root(s).
   */
  static final class NodeRow {
    final String nodeId;
    final String parentId;
    final String sortPath;
    final String groupName;
    final String value;
    final String name;
    final String elementLevel;
    final boolean alwaysShown;
    final String accountSign;
    final BigDecimal ownAmt;
    final BigDecimal ownAmtRef;

    NodeRow(String nodeId, String parentId, String sortPath, String groupName, String value,
        String name, String elementLevel, boolean alwaysShown, String accountSign,
        BigDecimal ownAmt, BigDecimal ownAmtRef) {
      this.nodeId = nodeId;
      this.parentId = (parentId == null || parentId.isEmpty()) ? null : parentId;
      this.sortPath = sortPath == null ? "" : sortPath;
      this.groupName = groupName;
      this.value = value;
      this.name = name;
      this.elementLevel = elementLevel;
      this.alwaysShown = alwaysShown;
      this.accountSign = accountSign;
      this.ownAmt = ownAmt == null ? BigDecimal.ZERO : ownAmt;
      this.ownAmtRef = ownAmtRef == null ? BigDecimal.ZERO : ownAmtRef;
    }
  }

  /** One formula edge from {@code C_ELEMENTVALUE_OPERAND} — {@code sql.operandsQuery}'s output. */
  static final class OperandRow {
    final String ownerId;
    final String operandId;
    final int sign;

    OperandRow(String ownerId, String operandId, int sign) {
      this.ownerId = ownerId;
      this.operandId = operandId;
      this.sign = sign;
    }
  }

  /** One flattened, document-ordered output row, exactly what the caller renders/serializes. */
  static final class OutputRow {
    final String nodeId;
    final String value;
    final String name;
    final String element;
    final String elementLevel;
    final BigDecimal amount;
    final BigDecimal amountRef;
    final int indent;
    final boolean isHeading;
    final String group;
    final boolean isGroupStart;
    final boolean isFormula;

    OutputRow(String nodeId, String value, String name, String element, String elementLevel,
        BigDecimal amount, BigDecimal amountRef, int indent, boolean isHeading, String group,
        boolean isGroupStart, boolean isFormula) {
      this.nodeId = nodeId;
      this.value = value;
      this.name = name;
      this.element = element;
      this.elementLevel = elementLevel;
      this.amount = amount;
      this.amountRef = amountRef;
      this.indent = indent;
      this.isHeading = isHeading;
      this.group = group;
      this.isGroupStart = isGroupStart;
      this.isFormula = isFormula;
    }
  }

  /** Mutable working node, built once per {@link #build} call from the caller's flat rows. */
  private static final class Node {
    final NodeRow row;
    final List<Node> children = new ArrayList<>();
    String sign;
    BigDecimal amount;
    BigDecimal amountRef;
    boolean resolved;

    Node(NodeRow row) {
      this.row = row;
    }
  }

  /**
   * Builds the indented, roll-up/formula-resolved account report tree from the flat node rows and
   * formula edges, applying the {@code accountLevel} depth cutoff and {@code showOnlyWithValue}
   * filter — see class javadoc for the full behavioural contract.
   *
   * @param nodeRows        the flat tree the report's SQL returned, in any order; never {@code null}
   *                        (an empty/{@code null} list is total and returns an empty result)
   * @param operandRows     the formula edges from {@code sql.operandsQuery}; may be {@code null} or
   *                        empty when the report has no formula nodes
   * @param accountLevel    one of {@code E}/{@code C}/{@code D}/{@code S}; an unrecognised or blank
   *                        value falls back to {@code S} (finest, i.e. no cutoff), matching
   *                        report-grouping.js's own {@code ?? ELEMENT_LEVEL_RANK.S}
   * @param showOnlyWithValue whether to drop a node whose amount and reference amount are both
   *                          (near-)zero and which is not marked {@code isalwaysshown}
   * @return the flattened, document-ordered rows; never {@code null}
   */
  static List<OutputRow> build(List<NodeRow> nodeRows, List<OperandRow> operandRows,
      String accountLevel, boolean showOnlyWithValue) {
    Map<String, Node> byId = new LinkedHashMap<>();
    if (nodeRows != null) {
      for (NodeRow r : nodeRows) {
        byId.put(r.nodeId, new Node(r));
      }
    }
    for (Node node : byId.values()) {
      Node parent = node.row.parentId == null ? null : byId.get(node.row.parentId);
      if (parent != null) {
        parent.children.add(node);
      }
    }

    List<Node> roots = new ArrayList<>();
    for (Node node : byId.values()) {
      if (node.row.parentId == null || !byId.containsKey(node.row.parentId)) {
        roots.add(node);
      }
    }

    // applySignAsPerParent: every node in a branch inherits its ROOT's sign, overriding whatever
    // its own row carries.
    for (Node root : roots) {
      propagateSign(root, root.row.accountSign);
    }

    Map<String, List<OperandRow>> operandsByOwner = new HashMap<>();
    if (operandRows != null) {
      for (OperandRow o : operandRows) {
        operandsByOwner.computeIfAbsent(o.ownerId, k -> new ArrayList<>()).add(o);
      }
    }

    Set<String> inProgress = new HashSet<>();
    for (Node node : byId.values()) {
      resolve(node, byId, operandsByOwner, inProgress);
    }

    int cutoffRank = ELEMENT_LEVEL_RANK.getOrDefault(accountLevel, ELEMENT_LEVEL_RANK.get("S"));
    List<OutputRow> out = new ArrayList<>();
    Comparator<Node> byPath = Comparator.comparing(n -> n.row.sortPath);
    List<Node> sortedRoots = new ArrayList<>(roots);
    sortedRoots.sort(byPath);
    // lastGroup is threaded through the recursive walk via a 1-element holder, mirroring the JS
    // closure over `lastGroup`.
    String[] lastGroup = { null };
    for (Node root : sortedRoots) {
      visit(root, 0, true, cutoffRank, showOnlyWithValue, operandsByOwner, out, lastGroup, byPath);
    }

    // The first row never flips isGroupStart (no previous row to differ from) — but a report with
    // more than one c_acct_rpt_group (Balance Sheet's "Activo"/"Patrimonio Neto y Pasivo") still
    // needs its very first group's header rendered, so force it on iff the report actually has more
    // than one distinct group. A single-group report (Profit & Loss) never has more than one, so
    // this is a no-op for it.
    if (!out.isEmpty()) {
      Set<String> groups = new HashSet<>();
      for (OutputRow r : out) {
        groups.add(r.group);
      }
      if (groups.size() > 1) {
        OutputRow first = out.get(0);
        out.set(0, new OutputRow(first.nodeId, first.value, first.name, first.element,
            first.elementLevel, first.amount, first.amountRef, first.indent, first.isHeading,
            first.group, true, first.isFormula));
      }
    }

    return out;
  }

  private static void propagateSign(Node node, String sign) {
    node.sign = sign;
    for (Node child : node.children) {
      propagateSign(child, sign);
    }
  }

  /** Classic's ACCOUNTSIGN convention: 'C' displays credit - debit, everything else debit - credit. */
  private static int accountSignMultiplier(String sign) {
    return "C".equals(sign) ? -1 : 1;
  }

  /**
   * Resolves {@code node.amount}/{@code node.amountRef}, recursing into children or formula
   * operands as needed. A node already resolved, or caught in a cycle (malformed data: a formula
   * referencing itself, directly or transitively), resolves to zero rather than recursing forever —
   * total by construction, never throws on malformed input.
   */
  private static void resolve(Node node, Map<String, Node> byId,
      Map<String, List<OperandRow>> operandsByOwner, Set<String> inProgress) {
    if (node.resolved) {
      return;
    }
    if (inProgress.contains(node.row.nodeId)) {
      node.amount = BigDecimal.ZERO;
      node.amountRef = BigDecimal.ZERO;
      node.resolved = true;
      return;
    }
    inProgress.add(node.row.nodeId);
    int multiplier = accountSignMultiplier(node.sign);
    BigDecimal own = node.row.ownAmt.multiply(BigDecimal.valueOf(multiplier));
    BigDecimal ownRef = node.row.ownAmtRef.multiply(BigDecimal.valueOf(multiplier));

    List<OperandRow> operands = operandsByOwner.get(node.row.nodeId);
    if (node.children.isEmpty() && operands != null && !operands.isEmpty()) {
      // Formula nodes sum their operands' already sign-adjusted values directly — Classic's
      // operandsCalculate never re-flips by the owner's own accountsign, only the operands
      // contribute their own polarity.
      BigDecimal sum = BigDecimal.ZERO;
      BigDecimal sumRef = BigDecimal.ZERO;
      for (OperandRow o : operands) {
        Node target = byId.get(o.operandId);
        if (target == null) {
          continue;
        }
        resolve(target, byId, operandsByOwner, inProgress);
        sum = sum.add(target.amount.multiply(BigDecimal.valueOf(o.sign)));
        sumRef = sumRef.add(target.amountRef.multiply(BigDecimal.valueOf(o.sign)));
      }
      node.amount = sum;
      node.amountRef = sumRef;
    } else if (!node.children.isEmpty()) {
      BigDecimal sum = own;
      BigDecimal sumRef = ownRef;
      for (Node child : node.children) {
        resolve(child, byId, operandsByOwner, inProgress);
        sum = sum.add(child.amount);
        sumRef = sumRef.add(child.amountRef);
      }
      node.amount = sum;
      node.amountRef = sumRef;
    } else {
      node.amount = own;
      node.amountRef = ownRef;
    }
    inProgress.remove(node.row.nodeId);
    node.resolved = true;
  }

  private static void visit(Node node, int indent, boolean isRoot, int cutoffRank,
      boolean showOnlyWithValue, Map<String, List<OperandRow>> operandsByOwner,
      List<OutputRow> out, String[] lastGroup, Comparator<Node> byPath) {
    boolean withinCutoff = true;
    if (!isRoot) {
      // An unknown/blank elementlevel is never a reason to drop a row.
      Integer rank = ELEMENT_LEVEL_RANK.get(node.row.elementLevel);
      withinCutoff = rank == null || rank <= cutoffRank;
      boolean hasValue = node.amount.abs().compareTo(HAS_VALUE_EPSILON) > 0
          || node.amountRef.abs().compareTo(HAS_VALUE_EPSILON) > 0;
      if (withinCutoff && (!showOnlyWithValue || hasValue || node.row.alwaysShown)) {
        boolean isGroupStart = !out.isEmpty() && !equalsNullable(node.row.groupName, lastGroup[0]);
        lastGroup[0] = node.row.groupName;
        boolean isFormula = node.children.isEmpty()
            && operandsByOwner.getOrDefault(node.row.nodeId, List.of()).size() > 0;
        out.add(new OutputRow(
            node.row.nodeId,
            node.row.value,
            node.row.name,
            node.row.value + " - " + node.row.name,
            node.row.elementLevel,
            node.amount,
            node.amountRef,
            indent,
            "E".equals(node.row.elementLevel),
            node.row.groupName,
            isGroupStart,
            isFormula));
      }
      if (!withinCutoff) {
        return; // cutoff reached: do not descend further
      }
    }
    // The report's root node is a container (the accounting report's own node), never a row — so
    // its children start the visible tree at indent 0.
    List<Node> sortedChildren = new ArrayList<>(node.children);
    sortedChildren.sort(byPath);
    for (Node child : sortedChildren) {
      visit(child, isRoot ? 0 : indent + 1, false, cutoffRank, showOnlyWithValue, operandsByOwner,
          out, lastGroup, byPath);
    }
  }

  private static boolean equalsNullable(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }
}
