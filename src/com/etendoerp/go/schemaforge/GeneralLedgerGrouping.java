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
import java.util.List;

/**
 * Pure Java port of {@code report-general-ledger}'s post-processing, i.e. what
 * {@code schema_forge_core/cli/src/report-grouping.js}'s {@code buildNestedGroups} /
 * {@code foldOpeningBalance} do to this report's rows (ETP-5483, slice 6, the last one).
 *
 * <p><b>Why this exists as its own pure class.</b> Same rationale as {@link TrialBalanceFolding}
 * and {@link JournalEntriesGrouping}: no DB, no OBContext, no JSON — plain data in, plain data
 * out — so the nesting, opening-balance fold, running-balance accumulation and truncation rules
 * can be unit tested directly without spinning up Etendo.</p>
 *
 * <p><b>Drift risk (must read before touching either side).</b> This is a hand-maintained Java
 * copy of {@code buildNestedGroups}/{@code foldOpeningBalance} in {@code report-grouping.js} — the
 * SAME function the two Node report engines call for this report ({@code contract.type ===
 * "grouped-listing"}, so it is NOT gated off the way {@code report-trial-balance}'s
 * {@code resolveGrouping} gates it). Any change there (the opening-balance fold rule, the
 * running-balance accumulation, the dimension-vs-flat grouping) MUST be mirrored here by hand,
 * and vice versa. See {@link GeneralLedgerReportHandler}'s class javadoc for the SQL side of the
 * same warning.</p>
 *
 * <p><b>Size-safety design — read before changing the cap.</b> Unlike the JS path (which has no
 * cap at all and pulls every matching line into memory), this handler enforces TWO independent
 * SQL-level caps: {@code accountLimit} (never cuts an account in half — an included account is
 * always the account in full risk-wise) and {@code linesPerAccountLimit} (may truncate a single
 * very active account's lines). Both {@link Account#opening}, {@link Account#subtotal} and
 * {@link Account#total} are populated from SQL-level AGGREGATES ({@code SUM(...)} over the WHOLE
 * period/account, never subject to {@code linesPerAccountLimit}) rather than by summing the
 * (possibly truncated) {@link Account#lines} list — so the totals are always numerically correct
 * even when lines were cut. {@code runningBalance} on the RETURNED lines is still correct: it is a
 * prefix sum starting at {@link Account#opening}'s total, over exactly the ordered lines that were
 * actually returned (the SQL only ever drops lines off the END of an account's own chronological
 * order, never reorders or drops from the middle) — so every returned line's running balance is
 * the true cumulative balance as of that line. Only when {@link Account#linesTruncated} is
 * {@code true} does the LAST returned line's runningBalance stop matching {@link Account#total}'s
 * total (by design: some lines were not returned).</p>
 */
final class GeneralLedgerGrouping {

  private GeneralLedgerGrouping() {
    // Static utility, never instantiated.
  }

  /** {@code dimensionField} values — shared by every row shape's own {@code dimensionValue()}. */
  private static final String DIM_BPNAME = "bpname";
  private static final String DIM_PRODUCTNAME = "productname";
  private static final String DIM_PROJECTNAME = "projectname";
  private static final String DIM_COSTCENTERNAME = "costcentername";

  /**
   * Shared implementation of {@code dimensionValue(dimensionField)} for {@link Row}, {@link
   * OpeningRow} and {@link AccountTotal} — they all carry the exact same four dimension columns
   * and the same field-name-to-column mapping, so this is the single place that mapping is spelled
   * out (java:S1192).
   */
  private static String resolveDimensionValue(String dimensionField, String bpname,
      String productname, String projectname, String costcentername) {
    if (dimensionField == null) {
      return null;
    }
    switch (dimensionField) {
      case DIM_BPNAME:
        return bpname == null ? "" : bpname;
      case DIM_PRODUCTNAME:
        return productname == null ? "" : productname;
      case DIM_PROJECTNAME:
        return projectname == null ? "" : projectname;
      case DIM_COSTCENTERNAME:
        return costcentername == null ? "" : costcentername;
      default:
        return "";
    }
  }

  // -------------------------------------------------------------------------
  // Input shapes
  // -------------------------------------------------------------------------

  /** One flat, line-grain input row — the shape {@link GeneralLedgerReportHandler}'s main SQL returns. */
  static final class Row {
    final String accountNo;
    final String accountId;
    final String accountName;
    final String dateacct;
    final String factAcctGroupId;
    final String groupbyname;
    final BigDecimal amtacctdr;
    final BigDecimal amtacctcr;
    final String bpname;
    final String productname;
    final String projectname;
    final String costcentername;

    private Row(Builder b) {
      this.accountNo = b.accountNo;
      this.accountId = b.accountId;
      this.accountName = b.accountName;
      this.dateacct = b.dateacct;
      this.factAcctGroupId = b.factAcctGroupId;
      this.groupbyname = b.groupbyname;
      this.amtacctdr = b.amtacctdr == null ? BigDecimal.ZERO : b.amtacctdr;
      this.amtacctcr = b.amtacctcr == null ? BigDecimal.ZERO : b.amtacctcr;
      this.bpname = b.bpname;
      this.productname = b.productname;
      this.projectname = b.projectname;
      this.costcentername = b.costcentername;
    }

    String dimensionValue(String dimensionField) {
      return resolveDimensionValue(dimensionField, bpname, productname, projectname, costcentername);
    }

    static Builder builder() {
      return new Builder();
    }

    /** Fluent builder — {@link Row} has too many fields for a plain constructor (java:S107). */
    static final class Builder {
      private String accountNo;
      private String accountId;
      private String accountName;
      private String dateacct;
      private String factAcctGroupId;
      private String groupbyname;
      private BigDecimal amtacctdr;
      private BigDecimal amtacctcr;
      private String bpname;
      private String productname;
      private String projectname;
      private String costcentername;

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

      Builder dateacct(String v) {
        this.dateacct = v;
        return this;
      }

      Builder factAcctGroupId(String v) {
        this.factAcctGroupId = v;
        return this;
      }

      Builder groupbyname(String v) {
        this.groupbyname = v;
        return this;
      }

      Builder amtacctdr(BigDecimal v) {
        this.amtacctdr = v;
        return this;
      }

      Builder amtacctcr(BigDecimal v) {
        this.amtacctcr = v;
        return this;
      }

      Builder bpname(String v) {
        this.bpname = v;
        return this;
      }

      Builder productname(String v) {
        this.productname = v;
        return this;
      }

      Builder projectname(String v) {
        this.projectname = v;
        return this;
      }

      Builder costcentername(String v) {
        this.costcentername = v;
        return this;
      }

      Row build() {
        return new Row(this);
      }
    }
  }

  /**
   * One opening-balance breakdown row — the shape {@link GeneralLedgerReportHandler}'s opening
   * query returns: one row per account × dimension-breakdown combo (already aggregated in SQL),
   * mirroring {@code contract.sql.openingQuery}'s output exactly, dimension columns included.
   */
  static final class OpeningRow {
    final String accountNo;
    final String bpname;
    final String productname;
    final String projectname;
    final String costcentername;
    final BigDecimal openingDr;
    final BigDecimal openingCr;

    /**
     * @param openingDr accumulated debit before the period, or {@code null}
     * @param openingCr accumulated credit before the period, or {@code null}. There is
     *     deliberately no separate {@code openingTotal} parameter: {@link Amounts} always
     *     derives {@code total} from {@code amtacctdr - amtacctcr}, so a caller-supplied total
     *     could silently drift from the debit/credit pair it should be consistent with.
     */
    OpeningRow(String accountNo, String bpname, String productname, String projectname,
        String costcentername, BigDecimal openingDr, BigDecimal openingCr) {
      this.accountNo = accountNo;
      this.bpname = bpname;
      this.productname = productname;
      this.projectname = projectname;
      this.costcentername = costcentername;
      this.openingDr = openingDr == null ? BigDecimal.ZERO : openingDr;
      this.openingCr = openingCr == null ? BigDecimal.ZERO : openingCr;
    }

    String dimensionValue(String dimensionField) {
      return resolveDimensionValue(dimensionField, bpname, productname, projectname, costcentername);
    }
  }

  /**
   * One account × dimension-breakdown activity total, from an uncapped SQL aggregate (never
   * subject to {@code linesPerAccountLimit}) — the source of truth for {@link Account#subtotal}
   * and {@link Account#totalLines}, so a truncated {@link Account#lines} list never corrupts a
   * total. Carries the SAME dimension breakdown columns as {@link OpeningRow} (one row per
   * account × contact/product/project/cost-center combo, mirroring {@code
   * artifacts/report-general-ledger/report-contract.json}'s {@code openingQuery} shape) so that,
   * when {@code groupBy} is set, a dimension group's own slice of an account's activity is folded
   * independently of that SAME account's activity under a DIFFERENT dimension value — see
   * {@link #foldAccountTotal}. Without this breakdown, an account appearing under two different
   * dimension values (e.g. two different contacts) would have BOTH dimension groups showing that
   * account's COMBINED subtotal instead of each group's own slice.
   */
  static final class AccountTotal {
    final String accountNo;
    final String bpname;
    final String productname;
    final String projectname;
    final String costcentername;
    final BigDecimal amtacctdr;
    final BigDecimal amtacctcr;
    final long lineCount;

    private AccountTotal(Builder b) {
      this.accountNo = b.accountNo;
      this.bpname = b.bpname;
      this.productname = b.productname;
      this.projectname = b.projectname;
      this.costcentername = b.costcentername;
      this.amtacctdr = b.amtacctdr == null ? BigDecimal.ZERO : b.amtacctdr;
      this.amtacctcr = b.amtacctcr == null ? BigDecimal.ZERO : b.amtacctcr;
      this.lineCount = b.lineCount;
    }

    String dimensionValue(String dimensionField) {
      return resolveDimensionValue(dimensionField, bpname, productname, projectname, costcentername);
    }

    static Builder builder() {
      return new Builder();
    }

    /** Fluent builder — {@link AccountTotal} has too many fields for a plain constructor (java:S107). */
    static final class Builder {
      private String accountNo;
      private String bpname;
      private String productname;
      private String projectname;
      private String costcentername;
      private BigDecimal amtacctdr;
      private BigDecimal amtacctcr;
      private long lineCount;

      Builder accountNo(String v) {
        this.accountNo = v;
        return this;
      }

      Builder bpname(String v) {
        this.bpname = v;
        return this;
      }

      Builder productname(String v) {
        this.productname = v;
        return this;
      }

      Builder projectname(String v) {
        this.projectname = v;
        return this;
      }

      Builder costcentername(String v) {
        this.costcentername = v;
        return this;
      }

      Builder amtacctdr(BigDecimal v) {
        this.amtacctdr = v;
        return this;
      }

      Builder amtacctcr(BigDecimal v) {
        this.amtacctcr = v;
        return this;
      }

      Builder lineCount(long v) {
        this.lineCount = v;
        return this;
      }

      AccountTotal build() {
        return new AccountTotal(this);
      }
    }
  }

  // -------------------------------------------------------------------------
  // Output shapes
  // -------------------------------------------------------------------------

  /** {amtacctdr, amtacctcr, total} — the shape every opening/subtotal/total triple takes. */
  static final class Amounts {
    final BigDecimal amtacctdr;
    final BigDecimal amtacctcr;
    final BigDecimal total;

    Amounts(BigDecimal amtacctdr, BigDecimal amtacctcr) {
      this.amtacctdr = amtacctdr;
      this.amtacctcr = amtacctcr;
      this.total = amtacctdr.subtract(amtacctcr);
    }

    static final Amounts ZERO = new Amounts(BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** One line of an account, with its accumulated {@code runningBalance}. */
  static final class Line {
    final String dateacct;
    final String factAcctGroupId;
    final String groupbyname;
    final BigDecimal amtacctdr;
    final BigDecimal amtacctcr;
    final BigDecimal runningBalance;
    final String bpname;
    final String productname;
    final String projectname;
    final String costcentername;

    private Line(Builder b) {
      this.dateacct = b.dateacct;
      this.factAcctGroupId = b.factAcctGroupId;
      this.groupbyname = b.groupbyname;
      this.amtacctdr = b.amtacctdr;
      this.amtacctcr = b.amtacctcr;
      this.runningBalance = b.runningBalance;
      this.bpname = b.bpname;
      this.productname = b.productname;
      this.projectname = b.projectname;
      this.costcentername = b.costcentername;
    }

    static Builder builder() {
      return new Builder();
    }

    /** Fluent builder — {@link Line} has too many fields for a plain constructor (java:S107). */
    static final class Builder {
      private String dateacct;
      private String factAcctGroupId;
      private String groupbyname;
      private BigDecimal amtacctdr;
      private BigDecimal amtacctcr;
      private BigDecimal runningBalance;
      private String bpname;
      private String productname;
      private String projectname;
      private String costcentername;

      Builder dateacct(String v) {
        this.dateacct = v;
        return this;
      }

      Builder factAcctGroupId(String v) {
        this.factAcctGroupId = v;
        return this;
      }

      Builder groupbyname(String v) {
        this.groupbyname = v;
        return this;
      }

      Builder amtacctdr(BigDecimal v) {
        this.amtacctdr = v;
        return this;
      }

      Builder amtacctcr(BigDecimal v) {
        this.amtacctcr = v;
        return this;
      }

      Builder runningBalance(BigDecimal v) {
        this.runningBalance = v;
        return this;
      }

      Builder bpname(String v) {
        this.bpname = v;
        return this;
      }

      Builder productname(String v) {
        this.productname = v;
        return this;
      }

      Builder projectname(String v) {
        this.projectname = v;
        return this;
      }

      Builder costcentername(String v) {
        this.costcentername = v;
        return this;
      }

      Line build() {
        return new Line(this);
      }
    }
  }

  /** One account block: opening balance, its (possibly line-truncated) lines, and its totals. */
  static final class Account {
    final String value;
    final String accountId;
    final String name;
    final List<Line> lines = new ArrayList<>();
    Amounts opening = Amounts.ZERO;
    Amounts subtotal = Amounts.ZERO;
    Amounts total = Amounts.ZERO;
    long totalLines;
    boolean linesTruncated;

    Account(String value, String accountId, String name) {
      this.value = value;
      this.accountId = accountId;
      this.name = name;
    }
  }

  /** One dimension group ({@code dimensionValue == null} when the report is not grouped). */
  static final class Group {
    final String dimensionValue;
    final List<Account> accounts = new ArrayList<>();

    Group(String dimensionValue) {
      this.dimensionValue = dimensionValue;
    }
  }

  // -------------------------------------------------------------------------
  // Opening balance fold — mirrors report-grouping.js's foldOpeningBalance
  // -------------------------------------------------------------------------

  /**
   * Sums an account's opening-balance breakdown rows down to a single {@link Amounts} for one
   * account — mirroring {@code report-grouping.js}'s {@code foldOpeningBalance} exactly: when
   * {@code dimensionField} is set, only rows whose dimension value matches {@code dimensionValue}
   * count (Classic's "opening balance scoped to this account under this dimension value"); when
   * there's no dimension, every breakdown row for the account is summed.
   */
  static Amounts foldOpeningBalance(List<OpeningRow> openingRows, String accountValue,
      String dimensionField, String dimensionValue) {
    BigDecimal dr = BigDecimal.ZERO;
    BigDecimal cr = BigDecimal.ZERO;
    if (openingRows != null) {
      for (OpeningRow r : openingRows) {
        boolean accountMatches = safe(accountValue).equals(safe(r.accountNo));
        boolean dimensionMatches = dimensionField == null
            || safe(dimensionValue).equals(r.dimensionValue(dimensionField));
        if (accountMatches && dimensionMatches) {
          dr = dr.add(r.openingDr);
          cr = cr.add(r.openingCr);
        }
      }
    }
    return new Amounts(dr, cr);
  }

  /** {@link #foldAccountTotal}'s result: the folded {@link Amounts} plus the matching line count. */
  static final class SubtotalFold {
    final Amounts amounts;
    final long lineCount;

    SubtotalFold(Amounts amounts, long lineCount) {
      this.amounts = amounts;
      this.lineCount = lineCount;
    }
  }

  /**
   * Sums an account's activity-total breakdown rows down to a single {@link SubtotalFold} for one
   * account — same fold rule as {@link #foldOpeningBalance}, applied to {@link AccountTotal}
   * instead of {@link OpeningRow}: when {@code dimensionField} is set, only rows whose dimension
   * value matches {@code dimensionValue} count, so the SAME account under two different dimension
   * values (e.g. two different contacts) gets each dimension group's own slice of its activity,
   * never the account's combined total. When there's no dimension, every breakdown row for the
   * account is summed.
   */
  static SubtotalFold foldAccountTotal(List<AccountTotal> totals, String accountValue,
      String dimensionField, String dimensionValue) {
    BigDecimal dr = BigDecimal.ZERO;
    BigDecimal cr = BigDecimal.ZERO;
    long lineCount = 0;
    if (totals != null) {
      for (AccountTotal t : totals) {
        boolean accountMatches = safe(accountValue).equals(safe(t.accountNo));
        boolean dimensionMatches = dimensionField == null
            || safe(dimensionValue).equals(t.dimensionValue(dimensionField));
        if (accountMatches && dimensionMatches) {
          dr = dr.add(t.amtacctdr);
          cr = cr.add(t.amtacctcr);
          lineCount += t.lineCount;
        }
      }
    }
    return new SubtotalFold(new Amounts(dr, cr), lineCount);
  }

  // -------------------------------------------------------------------------
  // Nesting — mirrors report-grouping.js's buildNestedGroups
  // -------------------------------------------------------------------------

  /**
   * Nests {@code rows} (already ordered by account, then by date/entry within each account — the
   * caller's SQL {@code ORDER BY}) into dimension → account groups, each carrying its opening
   * balance (from {@code openingRows}), its (possibly line-capped) lines with an accumulated
   * {@code runningBalance}, and its subtotal/total (from {@code accountTotals}, NOT from summing
   * {@code rows} — see this class's javadoc "Size-safety design").
   *
   * <p>When {@code dimensionField} is non-null, rows are first stably sorted by that dimension's
   * value — exactly like {@code report-grouping.js}'s {@code resolveGrouping} does before calling
   * {@code buildNestedGroups} — so accounts nest inside each dimension group, in the same relative
   * (account, date) order the SQL already gave them (stable sort preserves that).</p>
   *
   * @param rows           the line-grain rows, already ordered by account then date/entry
   * @param dimensionField {@code "bpname"}/{@code "productname"}/{@code "projectname"}/
   *                       {@code "costcentername"}, or {@code null} for a flat (ungrouped) report
   * @param openingRows    the per-account × dimension opening-balance breakdown rows
   * @param accountTotals  the per-account, UNCAPPED full-period totals (keyed by account value)
   * @return one {@link Group} per distinct dimension value ({@code null} dimensionValue when
   *     {@code dimensionField} is {@code null}), each with its nested {@link Account}s in order
   */
  static List<Group> nest(List<Row> rows, String dimensionField, List<OpeningRow> openingRows,
      List<AccountTotal> accountTotals) {
    List<Row> ordered = rows == null ? List.of() : rows;
    if (dimensionField != null) {
      ordered = new ArrayList<>(ordered);
      ordered.sort((a, b) -> {
        String va = safe(a.dimensionValue(dimensionField)).toLowerCase();
        String vb = safe(b.dimensionValue(dimensionField)).toLowerCase();
        return va.compareTo(vb);
      });
    }

    List<Group> groups = new ArrayList<>();
    Group currentGroup = null;
    Account currentAccount = null;
    BigDecimal running = BigDecimal.ZERO;

    for (Row r : ordered) {
      String dimValue = dimensionField != null ? r.dimensionValue(dimensionField) : null;
      if (currentGroup == null || !safeEquals(currentGroup.dimensionValue, dimValue)) {
        currentGroup = new Group(dimValue);
        groups.add(currentGroup);
        currentAccount = null;
      }
      String acctKey = safe(r.accountNo);
      if (currentAccount == null || !currentAccount.value.equals(acctKey)) {
        currentAccount = new Account(r.accountNo, r.accountId, r.accountName);
        currentAccount.opening = foldOpeningBalance(openingRows, r.accountNo, dimensionField, dimValue);
        SubtotalFold subtotalFold = foldAccountTotal(accountTotals, r.accountNo, dimensionField, dimValue);
        currentAccount.subtotal = subtotalFold.amounts;
        currentAccount.totalLines = subtotalFold.lineCount;
        currentAccount.total = new Amounts(
            currentAccount.opening.amtacctdr.add(currentAccount.subtotal.amtacctdr),
            currentAccount.opening.amtacctcr.add(currentAccount.subtotal.amtacctcr));
        currentGroup.accounts.add(currentAccount);
        running = currentAccount.opening.total;
      }
      running = running.add(r.amtacctdr.subtract(r.amtacctcr));
      currentAccount.lines.add(Line.builder()
          .dateacct(r.dateacct)
          .factAcctGroupId(r.factAcctGroupId)
          .groupbyname(r.groupbyname)
          .amtacctdr(r.amtacctdr)
          .amtacctcr(r.amtacctcr)
          .runningBalance(running)
          .bpname(r.bpname)
          .productname(r.productname)
          .projectname(r.projectname)
          .costcentername(r.costcentername)
          .build());
    }

    for (Group g : groups) {
      for (Account a : g.accounts) {
        a.linesTruncated = a.totalLines > a.lines.size();
      }
    }

    return groups;
  }

  private static boolean safeEquals(String a, String b) {
    return safe(a).equals(safe(b));
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }
}
