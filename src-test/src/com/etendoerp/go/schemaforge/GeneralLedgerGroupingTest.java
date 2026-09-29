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
 * Unit tests for {@link GeneralLedgerGrouping} — the pure nesting/opening-balance/running-balance
 * logic backing {@link GeneralLedgerReportHandler} (ETP-5483 slice 6, the last one). No DB, no
 * OBContext: plain data in, plain data out.
 */
class GeneralLedgerGroupingTest {

  private static GeneralLedgerGrouping.Row row(String accountNo, String accountName,
      String dateacct, String factAcctGroupId, String debit, String credit) {
    return GeneralLedgerGrouping.Row.builder()
        .accountNo(accountNo)
        .accountId("acct-id-" + accountNo)
        .accountName(accountName)
        .dateacct(dateacct)
        .factAcctGroupId(factAcctGroupId)
        .groupbyname("Some movement")
        .amtacctdr(new BigDecimal(debit))
        .amtacctcr(new BigDecimal(credit))
        .bpname("Acme")
        .productname("Widget")
        .projectname("Proj A")
        .costcentername("CC1")
        .build();
  }

  private static GeneralLedgerGrouping.OpeningRow opening(String accountNo, String openingDr,
      String openingCr) {
    return new GeneralLedgerGrouping.OpeningRow(accountNo, null, null, null, null,
        new BigDecimal(openingDr), new BigDecimal(openingCr));
  }

  private static GeneralLedgerGrouping.OpeningRow openingWithDimension(String accountNo,
      String bpname, String openingDr, String openingCr) {
    return new GeneralLedgerGrouping.OpeningRow(accountNo, bpname, null, null, null,
        new BigDecimal(openingDr), new BigDecimal(openingCr));
  }

  private static GeneralLedgerGrouping.AccountTotal total(String accountNo, String debit,
      String credit, long lineCount) {
    return GeneralLedgerGrouping.AccountTotal.builder()
        .accountNo(accountNo)
        .amtacctdr(new BigDecimal(debit))
        .amtacctcr(new BigDecimal(credit))
        .lineCount(lineCount)
        .build();
  }

  private static GeneralLedgerGrouping.AccountTotal totalWithDimension(String accountNo,
      String bpname, String debit, String credit, long lineCount) {
    return GeneralLedgerGrouping.AccountTotal.builder()
        .accountNo(accountNo)
        .bpname(bpname)
        .amtacctdr(new BigDecimal(debit))
        .amtacctcr(new BigDecimal(credit))
        .lineCount(lineCount)
        .build();
  }

  // -------------------------------------------------------------------------
  // Nesting — one Account per distinct account_no
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("nests multiple lines of the same account into one Account with a lines array")
  void nestsLinesOfSameAccount() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"),
        row("430", "Customers", "2026-01-06", "grp-2", "50", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(total("430", "150", "0", 2));

    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals);

    assertEquals(1, groups.size());
    assertEquals(1, groups.get(0).accounts.size());
    GeneralLedgerGrouping.Account account = groups.get(0).accounts.get(0);
    assertEquals("430", account.value);
    assertEquals("Customers", account.name);
    assertEquals(2, account.lines.size());
  }

  @Test
  @DisplayName("two different accounts produce two separate Account objects, in row order")
  void separatesDifferentAccounts() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"),
        row("570", "Cash", "2026-01-06", "grp-2", "50", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        total("430", "100", "0", 1), total("570", "50", "0", 1));

    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals);

    assertEquals(1, groups.size());
    assertEquals(2, groups.get(0).accounts.size());
    assertEquals("430", groups.get(0).accounts.get(0).value);
    assertEquals("570", groups.get(0).accounts.get(1).value);
  }

  @Test
  @DisplayName("ungrouped report produces a single Group with a null dimensionValue")
  void ungroupedProducesSingleGroup() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));

    List<GeneralLedgerGrouping.Group> groups =
        GeneralLedgerGrouping.nest(rows, null, List.of(), List.of(total("430", "100", "0", 1)));

    assertEquals(1, groups.size());
    assertEquals(null, groups.get(0).dimensionValue);
  }

  // -------------------------------------------------------------------------
  // Opening balance fold — foldOpeningBalance
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("opening balance is the sum of every matching opening row for the account")
  void openingBalanceSumsMatchingRows() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));
    List<GeneralLedgerGrouping.OpeningRow> openingRows = List.of(
        opening("430", "500", "200"), opening("430", "50", "0"));

    GeneralLedgerGrouping.Account account = GeneralLedgerGrouping
        .nest(rows, null, openingRows, List.of(total("430", "100", "0", 1)))
        .get(0).accounts.get(0);

    // 500 + 50 debit, 200 + 0 credit -> total = 550 - 200 = 350
    assertEquals(0, new BigDecimal("550").compareTo(account.opening.amtacctdr));
    assertEquals(0, new BigDecimal("200").compareTo(account.opening.amtacctcr));
    assertEquals(0, new BigDecimal("350").compareTo(account.opening.total));
  }

  @Test
  @DisplayName("an account with no opening rows gets a zero opening balance, not null/exception")
  void openingBalanceDefaultsToZeroWhenAbsent() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));

    GeneralLedgerGrouping.Account account = GeneralLedgerGrouping
        .nest(rows, null, List.of(), List.of(total("430", "100", "0", 1)))
        .get(0).accounts.get(0);

    assertEquals(0, BigDecimal.ZERO.compareTo(account.opening.total));
  }

  @Test
  @DisplayName("when grouped by dimension, opening balance only counts rows matching that dimension value")
  void openingBalanceScopedToDimensionWhenGrouped() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));
    List<GeneralLedgerGrouping.OpeningRow> openingRows = List.of(
        openingWithDimension("430", "Acme", "500", "0"),
        openingWithDimension("430", "Other Partner", "9999", "0"));

    GeneralLedgerGrouping.Account account = GeneralLedgerGrouping
        .nest(rows, "bpname", openingRows, List.of(total("430", "100", "0", 1)))
        .get(0).accounts.get(0);

    // Only the "Acme" opening row (matching this row's bpname) must count, not "Other Partner"'s.
    assertEquals(0, new BigDecimal("500").compareTo(account.opening.amtacctdr));
  }

  // -------------------------------------------------------------------------
  // Running balance — accumulates from opening.total, invariant on the last line
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("running balance accumulates from the opening total across the account's lines")
  void runningBalanceAccumulatesFromOpening() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"),
        row("430", "Customers", "2026-01-06", "grp-2", "0", "40"));
    List<GeneralLedgerGrouping.OpeningRow> openingRows = List.of(opening("430", "1000", "0"));

    GeneralLedgerGrouping.Account account = GeneralLedgerGrouping
        .nest(rows, null, openingRows, List.of(total("430", "100", "40", 2)))
        .get(0).accounts.get(0);

    assertEquals(0, new BigDecimal("1100").compareTo(account.lines.get(0).runningBalance));
    assertEquals(0, new BigDecimal("1060").compareTo(account.lines.get(1).runningBalance));
  }

  @Test
  @DisplayName("invariant: when NOT truncated, the last line's runningBalance equals the account's total")
  void lastRunningBalanceEqualsTotalWhenNotTruncated() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"),
        row("430", "Customers", "2026-01-06", "grp-2", "0", "40"),
        row("430", "Customers", "2026-01-07", "grp-3", "10", "0"));
    List<GeneralLedgerGrouping.OpeningRow> openingRows = List.of(opening("430", "1000", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(total("430", "110", "40", 3));

    GeneralLedgerGrouping.Account account =
        GeneralLedgerGrouping.nest(rows, null, openingRows, totals).get(0).accounts.get(0);

    assertFalse(account.linesTruncated);
    GeneralLedgerGrouping.Line lastLine = account.lines.get(account.lines.size() - 1);
    assertEquals(0, account.total.total.compareTo(lastLine.runningBalance));
  }

  // -------------------------------------------------------------------------
  // Totals come from the aggregate, never from summing the (possibly truncated) lines
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("subtotal/total are always correct from the aggregate, even when fewer lines are passed than the aggregate reports")
  void totalsStayCorrectWhenLinesAreTruncated() {
    // Only ONE line is actually passed in, but the aggregate says there were 500 lines totalling
    // much more activity — simulating what buildLineRows/buildAccountTotals hand the grouping
    // code when linesPerAccountLimit cut the line list short.
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"));
    List<GeneralLedgerGrouping.OpeningRow> openingRows = List.of(opening("430", "1000", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(total("430", "50000", "20000", 500));

    GeneralLedgerGrouping.Account account =
        GeneralLedgerGrouping.nest(rows, null, openingRows, totals).get(0).accounts.get(0);

    assertEquals(0, new BigDecimal("50000").compareTo(account.subtotal.amtacctdr));
    assertEquals(0, new BigDecimal("20000").compareTo(account.subtotal.amtacctcr));
    assertEquals(0, new BigDecimal("31000").compareTo(account.total.total)); // 1000 + (50000-20000)
    assertTrue(account.linesTruncated, "500 total lines but only 1 was returned");
    assertEquals(500, account.totalLines);
    assertEquals(1, account.lines.size());
  }

  @Test
  @DisplayName("linesTruncated is false when totalLines equals the number of returned lines")
  void linesNotTruncatedWhenCountsMatch() {
    List<GeneralLedgerGrouping.Row> rows = List.of(
        row("430", "Customers", "2026-01-05", "grp-1", "100", "0"),
        row("430", "Customers", "2026-01-06", "grp-2", "50", "0"));
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(total("430", "150", "0", 2));

    GeneralLedgerGrouping.Account account =
        GeneralLedgerGrouping.nest(rows, null, List.of(), totals).get(0).accounts.get(0);

    assertFalse(account.linesTruncated);
  }

  // -------------------------------------------------------------------------
  // groupBy / dimension nesting
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("groupBy nests accounts inside dimension groups, in stable dimension-sorted order")
  void groupByNestsAccountsInsideDimensionGroups() {
    GeneralLedgerGrouping.Row rowAcme430 = GeneralLedgerGrouping.Row.builder()
        .accountNo("430").accountId("acct-430").accountName("Customers")
        .dateacct("2026-01-05").factAcctGroupId("grp-1").groupbyname("desc")
        .amtacctdr(new BigDecimal("100")).amtacctcr(BigDecimal.ZERO)
        .bpname("Acme").build();
    GeneralLedgerGrouping.Row rowBeta430 = GeneralLedgerGrouping.Row.builder()
        .accountNo("430").accountId("acct-430").accountName("Customers")
        .dateacct("2026-01-06").factAcctGroupId("grp-2").groupbyname("desc")
        .amtacctdr(new BigDecimal("50")).amtacctcr(BigDecimal.ZERO)
        .bpname("Beta").build();
    List<GeneralLedgerGrouping.Row> rows = List.of(rowAcme430, rowBeta430);
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        totalWithDimension("430", "Acme", "100", "0", 1),
        totalWithDimension("430", "Beta", "50", "0", 1));

    List<GeneralLedgerGrouping.Group> groups = GeneralLedgerGrouping.nest(rows, "bpname",
        List.of(), totals);

    assertEquals(2, groups.size());
    assertEquals("Acme", groups.get(0).dimensionValue);
    assertEquals("Beta", groups.get(1).dimensionValue);
    assertEquals(1, groups.get(0).accounts.get(0).lines.size());
    assertEquals(1, groups.get(1).accounts.get(0).lines.size());
  }

  /**
   * Regression guard: the SAME account (430) appears under TWO different dimension values (Acme,
   * Beta). Before this test's fix, {@code AccountTotal} was keyed by account alone, so BOTH
   * dimension groups would show account 430's COMBINED subtotal (150) instead of each group's own
   * slice (100 for Acme, 50 for Beta) — an account with activity under multiple contacts would
   * silently double-count.
   */
  @Test
  @DisplayName("when grouped by dimension, each group's account subtotal is scoped to that dimension value, never the account's combined total")
  void subtotalScopedToDimensionWhenAccountSpansMultipleDimensionValues() {
    GeneralLedgerGrouping.Row rowAcme430 = GeneralLedgerGrouping.Row.builder()
        .accountNo("430").accountId("acct-430").accountName("Customers")
        .dateacct("2026-01-05").factAcctGroupId("grp-1").groupbyname("desc")
        .amtacctdr(new BigDecimal("100")).amtacctcr(BigDecimal.ZERO)
        .bpname("Acme").build();
    GeneralLedgerGrouping.Row rowBeta430 = GeneralLedgerGrouping.Row.builder()
        .accountNo("430").accountId("acct-430").accountName("Customers")
        .dateacct("2026-01-06").factAcctGroupId("grp-2").groupbyname("desc")
        .amtacctdr(new BigDecimal("50")).amtacctcr(BigDecimal.ZERO)
        .bpname("Beta").build();
    List<GeneralLedgerGrouping.Row> rows = List.of(rowAcme430, rowBeta430);
    List<GeneralLedgerGrouping.AccountTotal> totals = List.of(
        totalWithDimension("430", "Acme", "100", "0", 1),
        totalWithDimension("430", "Beta", "50", "0", 1));

    List<GeneralLedgerGrouping.Group> groups = GeneralLedgerGrouping.nest(rows, "bpname",
        List.of(), totals);

    GeneralLedgerGrouping.Account acmeAccount = groups.get(0).accounts.get(0);
    GeneralLedgerGrouping.Account betaAccount = groups.get(1).accounts.get(0);
    assertEquals(0, new BigDecimal("100").compareTo(acmeAccount.subtotal.amtacctdr));
    assertEquals(0, new BigDecimal("50").compareTo(betaAccount.subtotal.amtacctdr));
  }

  // -------------------------------------------------------------------------
  // Empty / missing data — total functions, no exceptions
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("nest() on an empty row list returns an empty list, not an exception")
  void nestOnEmptyRowsReturnsEmptyList() {
    assertTrue(GeneralLedgerGrouping.nest(List.of(), null, List.of(), List.of()).isEmpty());
  }

  @Test
  @DisplayName("nest() with null rows/openingRows/accountTotals does not throw")
  void nestToleratesNullLists() {
    List<GeneralLedgerGrouping.Group> groups = GeneralLedgerGrouping.nest(null, null, null, null);

    assertTrue(groups.isEmpty());
  }

  @Test
  @DisplayName("foldOpeningBalance on an account with no rows at all returns a zero Amounts, never null")
  void foldOpeningBalanceReturnsZeroForUnknownAccount() {
    GeneralLedgerGrouping.Amounts amounts =
        GeneralLedgerGrouping.foldOpeningBalance(List.of(opening("999", "10", "5")), "430", null,
            null);

    assertEquals(0, BigDecimal.ZERO.compareTo(amounts.amtacctdr));
    assertEquals(0, BigDecimal.ZERO.compareTo(amounts.amtacctcr));
    assertEquals(0, BigDecimal.ZERO.compareTo(amounts.total));
  }
}
