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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java port of {@code report-trial-balance}'s post-processing, i.e. what
 * {@code schema_forge_core/cli/src/report-grouping.js}'s {@code resolveGrouping} /
 * {@code foldAggregateRows} do to this report's rows (ETP-5483, slice 2).
 *
 * <p><b>Why this exists as its own pure class.</b> {@link TrialBalanceReportHandler}'s SQL
 * (ported faithfully from {@code artifacts/report-trial-balance/report-contract.json}) always
 * returns rows at the FINEST grain — one row per (account × contact × product × project × cost
 * center) combination — exactly like the Node engines do, so that any dimension can be chosen for
 * grouping without re-querying. Both Node engines then fold that fine grain down to what the
 * caller actually asked for: one row per account when no dimension was picked, or one row per
 * (dimension value, account) when one was. This class is that same fold, with no DB, no OBContext
 * and no JSON — plain data in, plain data out — so it can be unit tested directly and kept in sync
 * with {@code report-grouping.js} by inspection, without spinning up Etendo.</p>
 *
 * <p><b>Drift risk (must read before touching either side).</b> This is a hand-maintained Java
 * copy of a JavaScript module. The two are NOT structurally linked — nothing fails to compile or
 * to run if one changes and the other doesn't. Any change to {@code foldAggregateRows} in
 * {@code report-grouping.js} (the "has activity" criterion, the fold key, the sort order) MUST be
 * mirrored here by hand, and vice versa. See {@link TrialBalanceReportHandler}'s class javadoc for
 * the same warning from the handler's side.</p>
 */
final class TrialBalanceFolding {

  private TrialBalanceFolding() {
    // Static utility, never instantiated.
  }

  /**
   * One fine-grain input row — the shape {@link TrialBalanceReportHandler}'s SQL returns, before
   * folding. {@code dimensionValue}/{@code dimensionId} are already resolved by the caller to
   * whichever grouping dimension (contact/product/project/cost center) was requested — this class
   * does not know the column names, only that a dimension may or may not be present.
   */
  static final class Row {
    final String accountNo;
    final String accountId;
    final String accountName;
    /** The grouping dimension's display value for this row, or {@code null} when no grouping was requested. */
    final String dimensionValue;
    /** The grouping dimension's id for this row (only bPartner declares one), or {@code null}. */
    final String dimensionId;
    final BigDecimal openingBalance;
    final BigDecimal activityDebit;
    final BigDecimal activityCredit;
    final BigDecimal closingBalance;

    private Row(Builder b) {
      this.accountNo = b.accountNo;
      this.accountId = b.accountId;
      this.accountName = b.accountName;
      this.dimensionValue = b.dimensionValue;
      this.dimensionId = b.dimensionId;
      this.openingBalance = b.openingBalance == null ? BigDecimal.ZERO : b.openingBalance;
      this.activityDebit = b.activityDebit == null ? BigDecimal.ZERO : b.activityDebit;
      this.activityCredit = b.activityCredit == null ? BigDecimal.ZERO : b.activityCredit;
      this.closingBalance = b.closingBalance == null ? BigDecimal.ZERO : b.closingBalance;
    }

    static Builder builder() {
      return new Builder();
    }

    /** Fluent builder — {@link Row} has too many fields for a plain constructor (java:S107). */
    static final class Builder {
      private String accountNo;
      private String accountId;
      private String accountName;
      private String dimensionValue;
      private String dimensionId;
      private BigDecimal openingBalance;
      private BigDecimal activityDebit;
      private BigDecimal activityCredit;
      private BigDecimal closingBalance;

      Builder accountNo(String v) {
        this.accountNo = v;
        return this;
      }

      Builder accountId(String v) {
        this.accountId = v;
        return this;
      }

      Builder accountName(String v) {
        this.accountName = v;
        return this;
      }

      Builder dimensionValue(String v) {
        this.dimensionValue = v;
        return this;
      }

      Builder dimensionId(String v) {
        this.dimensionId = v;
        return this;
      }

      Builder openingBalance(BigDecimal v) {
        this.openingBalance = v;
        return this;
      }

      Builder activityDebit(BigDecimal v) {
        this.activityDebit = v;
        return this;
      }

      Builder activityCredit(BigDecimal v) {
        this.activityCredit = v;
        return this;
      }

      Builder closingBalance(BigDecimal v) {
        this.closingBalance = v;
        return this;
      }

      Row build() {
        return new Row(this);
      }
    }
  }

  /** One folded output row — one per account, or one per (dimension value, account) when grouped. */
  static final class FoldedRow {
    final String accountNo;
    final String accountId;
    final String accountName;
    final String dimensionValue;
    final String dimensionId;
    final BigDecimal openingBalance;
    final BigDecimal activityDebit;
    final BigDecimal activityCredit;
    final BigDecimal closingBalance;

    /** Snapshot of a finished accumulator; its amounts are never {@code null} (they start at ZERO). */
    private FoldedRow(MutableAccumulator acc) {
      this.accountNo = acc.accountNo;
      this.accountId = acc.accountId;
      this.accountName = acc.accountName;
      this.dimensionValue = acc.dimensionValue;
      this.dimensionId = acc.dimensionId;
      this.openingBalance = acc.openingBalance;
      this.activityDebit = acc.activityDebit;
      this.activityCredit = acc.activityCredit;
      this.closingBalance = acc.closingBalance;
    }
  }

  private static final class MutableAccumulator {
    String accountNo;
    String accountId;
    String accountName;
    String dimensionValue;
    String dimensionId;
    BigDecimal openingBalance = BigDecimal.ZERO;
    BigDecimal activityDebit = BigDecimal.ZERO;
    BigDecimal activityCredit = BigDecimal.ZERO;
    BigDecimal closingBalance = BigDecimal.ZERO;
  }

  /**
   * Folds fine-grain {@code rows} down to one row per account ({@code grouped == false}) or one
   * row per (dimension value, account) ({@code grouped == true}), mirroring
   * {@code report-grouping.js}'s {@code foldAggregateRows} exactly:
   * <ol>
   *   <li>Sum the four amount columns per fold key ({@code (dimensionValue, account_no)} when
   *       grouped, {@code account_no} alone otherwise).</li>
   *   <li>Drop a folded row whose opening balance, activity debit AND activity credit are all
   *       exactly zero — matching Classic's real "has activity" criterion
   *       ({@code ReportTrialBalance_data.xsql}: {@code "a.initialamt <>0 or a.amtacctcr <>0 or
   *       a.amtacctdr<>0"}). An account with a nonzero OPENING balance but zero period movement
   *       (e.g. an untouched cash/bank account) still belongs in the report — filtering on period
   *       activity alone would silently drop it and break the opening/closing column totals (a
   *       Trial Balance must always net to zero across all accounts). Deliberately NOT the
   *       JavaScript version's {@code 1e-9} epsilon: these are {@link BigDecimal}s straight out of
   *       a Postgres {@code SUM(...)} aggregate, so the comparison is exact decimal arithmetic with
   *       no floating-point rounding to guard against — {@code compareTo(ZERO) != 0} is the correct
   *       "is this actually nonzero" check here.</li>
   *   <li>Sort by account number first, then (when grouped) by dimension value — both
   *       case-insensitive, matching the JS sort.</li>
   * </ol>
   *
   * @param rows    the fine-grain rows, in any order
   * @param grouped whether a dimension was requested (i.e. {@code groupBy} was set); when
   *                {@code false}, every row's {@code dimensionValue}/{@code dimensionId} is
   *                ignored and everything folds into one row per account
   * @return the folded, filtered, sorted rows
   */
  static List<FoldedRow> fold(List<Row> rows, boolean grouped) {
    Map<String, MutableAccumulator> folded = new LinkedHashMap<>();
    if (rows != null) {
      for (Row r : rows) {
        accumulate(folded, r, grouped);
      }
    }

    List<FoldedRow> result = toFoldedRows(folded);
    result.sort(buildComparator(grouped));
    return result;
  }

  /** Folds one input row into its accumulator, creating the accumulator on first sight of its key. */
  private static void accumulate(Map<String, MutableAccumulator> folded, Row r, boolean grouped) {
    String dimValue = null;
    if (grouped) {
      dimValue = r.dimensionValue == null ? "" : r.dimensionValue;
    }
    String key = (grouped ? dimValue : "") + "\u0000" + safe(r.accountNo);
    MutableAccumulator acc = folded.get(key);
    if (acc == null) {
      acc = new MutableAccumulator();
      acc.accountNo = r.accountNo;
      acc.accountId = r.accountId;
      acc.accountName = r.accountName;
      acc.dimensionValue = grouped ? dimValue : null;
      acc.dimensionId = grouped ? r.dimensionId : null;
      folded.put(key, acc);
    }
    acc.openingBalance = acc.openingBalance.add(r.openingBalance);
    acc.activityDebit = acc.activityDebit.add(r.activityDebit);
    acc.activityCredit = acc.activityCredit.add(r.activityCredit);
    acc.closingBalance = acc.closingBalance.add(r.closingBalance);
  }

  /** Converts every accumulator to an output row, dropping ones with no activity (see {@link #fold}). */
  private static List<FoldedRow> toFoldedRows(Map<String, MutableAccumulator> folded) {
    List<FoldedRow> result = new ArrayList<>();
    for (MutableAccumulator acc : folded.values()) {
      if (!hasActivity(acc)) {
        continue;
      }
      result.add(new FoldedRow(acc));
    }
    return result;
  }

  private static boolean hasActivity(MutableAccumulator acc) {
    return acc.openingBalance.compareTo(BigDecimal.ZERO) != 0
        || acc.activityDebit.compareTo(BigDecimal.ZERO) != 0
        || acc.activityCredit.compareTo(BigDecimal.ZERO) != 0;
  }

  private static Comparator<FoldedRow> buildComparator(boolean grouped) {
    Comparator<FoldedRow> byAccount = Comparator.comparing(r -> safe(r.accountNo).toLowerCase());
    if (grouped) {
      byAccount = byAccount.thenComparing(r -> safe(r.dimensionValue).toLowerCase());
    }
    return byAccount;
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }
}
