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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TrialBalanceFolding} — the pure Java port of
 * {@code report-grouping.js}'s {@code foldAggregateRows} for {@code report-trial-balance}
 * (ETP-5483 slice 2). No DB, no OBContext: plain data in, plain data out.
 */
class TrialBalanceFoldingTest {

  private static TrialBalanceFolding.Row row(String accountNo, String accountId,
      String accountName, String dimensionValue, String dimensionId, String opening,
      String debit, String credit, String closing) {
    return new TrialBalanceFolding.Row(accountNo, accountId, accountName, dimensionValue,
        dimensionId, new BigDecimal(opening), new BigDecimal(debit), new BigDecimal(credit),
        new BigDecimal(closing));
  }

  // -------------------------------------------------------------------------
  // Ungrouped folding
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("ungrouped: sums fine-grain rows for the same account into one row")
  void ungroupedFoldsSameAccountRows() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("700", "acc-1", "Sales", "Acme", "bp-1", "0", "100", "0", "100"),
        row("700", "acc-1", "Sales", "Beta", "bp-2", "0", "50", "0", "50"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, false);

    assertEquals(1, folded.size());
    TrialBalanceFolding.FoldedRow only = folded.get(0);
    assertEquals("700", only.accountNo);
    assertEquals(0, new BigDecimal("150").compareTo(only.activityDebit));
    assertEquals(null, only.dimensionValue, "ungrouped rows must not carry a dimension");
  }

  @Test
  @DisplayName("ungrouped: an account with zero opening/debit/credit is dropped")
  void ungroupedDropsAllZeroAccount() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("100", "acc-1", "Cash", null, null, "0", "0", "0", "0"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, false);

    assertTrue(folded.isEmpty());
  }

  @Test
  @DisplayName("ungrouped: a nonzero OPENING balance with zero period activity is kept")
  void ungroupedKeepsNonzeroOpeningWithNoActivity() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("100", "acc-1", "Cash", null, null, "500", "0", "0", "500"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, false);

    assertEquals(1, folded.size(),
        "an untouched cash/bank account with a real opening balance must not be dropped");
  }

  @Test
  @DisplayName("ungrouped: sorted by account number, case-insensitively")
  void ungroupedSortedByAccountNumber() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("700", "acc-2", "Sales", null, null, "0", "10", "0", "10"),
        row("100", "acc-1", "Cash", null, null, "0", "5", "0", "5"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, false);

    assertEquals(List.of("100", "700"),
        folded.stream().map(r -> r.accountNo).toList());
  }

  // -------------------------------------------------------------------------
  // Grouped folding
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("grouped: one row per (dimension value, account) instead of one per account")
  void groupedFoldsPerDimensionAndAccount() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("700", "acc-1", "Sales", "Acme", "bp-1", "0", "100", "0", "100"),
        row("700", "acc-1", "Sales", "Beta", "bp-2", "0", "50", "0", "50"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, true);

    assertEquals(2, folded.size());
    assertEquals(List.of("Acme", "Beta"),
        folded.stream().map(r -> r.dimensionValue).toList());
  }

  @Test
  @DisplayName("grouped: carries the dimension id through when the raw row has one")
  void groupedCarriesDimensionId() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("700", "acc-1", "Sales", "Acme", "bp-1", "0", "100", "0", "100"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, true);

    assertEquals("bp-1", folded.get(0).dimensionId);
  }

  @Test
  @DisplayName("grouped: a blank dimension value is its own row, not merged into another")
  void groupedKeepsBlankDimensionAsOwnRow() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("700", "acc-1", "Sales", "Acme", "bp-1", "0", "100", "0", "100"),
        row("700", "acc-1", "Sales", "", null, "0", "25", "0", "25"),
        row("700", "acc-1", "Sales", null, null, "0", "15", "0", "15"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, true);

    // "" and null both normalize to the same blank-dimension bucket and fold together.
    assertEquals(2, folded.size());
  }

  @Test
  @DisplayName("grouped: sorted by account first, then dimension value, both case-insensitively")
  void groupedSortedByAccountThenDimension() {
    List<TrialBalanceFolding.Row> rows = List.of(
        row("700", "acc-1", "Sales", "beta", null, "0", "10", "0", "10"),
        row("700", "acc-1", "Sales", "Acme", null, "0", "20", "0", "20"),
        row("100", "acc-2", "Cash", "Zeta", null, "0", "5", "0", "5"));

    List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(rows, true);

    assertEquals(List.of("100|Zeta", "700|Acme", "700|beta"),
        folded.stream().map(r -> r.accountNo + "|" + r.dimensionValue).toList());
  }

  // -------------------------------------------------------------------------
  // Edge cases
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("null/empty input never throws")
  void nullOrEmptyInputIsTotal() {
    assertTrue(TrialBalanceFolding.fold(null, false).isEmpty());
    assertTrue(TrialBalanceFolding.fold(List.of(), true).isEmpty());
  }
}
