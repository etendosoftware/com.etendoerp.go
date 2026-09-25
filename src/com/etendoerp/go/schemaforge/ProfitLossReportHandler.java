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

import java.util.List;
import java.util.Optional;

import javax.inject.Named;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;

/**
 * NeoHandler exposing the "Profit &amp; Loss" / "Pérdidas y Ganancias" report as an MCP report
 * tool ({@code generate_profit_loss}, ETP-5483 slice 5).
 *
 * <p><b>Why this handler exists.</b> Same reasoning as {@link BalanceSheetReportHandler}: before
 * this class, {@code profit-loss} was served ONLY by the two Node report engines (the Vite dev
 * plugin, {@code tools/app-shell/vite-plugins/report-api.js}, and the production {@code
 * tools/report-server/server.js} in {@code schema_forge_core}), which run {@code
 * artifacts/profit-loss/report-contract.json}'s {@code sql.query}/{@code sql.operandsQuery} — raw
 * SQL with {@code __PLACEHOLDER__} text substitution — directly against Postgres, then fold the
 * flat node list into an indented tree with {@code report-grouping.js}'s {@code
 * buildAccountReportTree}. Neither engine is reachable from the Java MCP servlet. This handler is
 * a faithful Java port of that same query, using real bind parameters instead of string
 * substitution, and REUSES {@link AccountReportTree} unchanged — its own javadoc already
 * documented the reuse contract for this exact handler.
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> Same shape as
 * {@link BalanceSheetReportHandler}: this report now has TWO independent implementations of the
 * same business logic — the Node/SQL-placeholder one (this class's source of truth, kept in
 * {@code schema_forge}'s {@code artifacts/profit-loss/report-contract.json}) and this Java one.
 * They are NOT structurally linked. A change to the P&amp;L SQL in the Node contract MUST be
 * mirrored here by hand, and vice versa.
 *
 * <p><b>Structural differences from {@link BalanceSheetReportHandler} — do not copy its CTEs
 * blindly.</b> Verified by diffing {@code artifacts/profit-loss/report-contract.json} against
 * {@code artifacts/balance-sheet/report-contract.json}:
 * <ul>
 *   <li><b>{@code reporttype = 'N'}</b> (Balance Sheet uses {@code 'Y'}) — a different
 *       {@code C_ACCT_RPT} row, hence a different root/group tree.</li>
 *   <li><b>Period-activity, not a cumulative snapshot.</b> Balance Sheet's date filter is
 *       {@code dateacct <= yearEnd} (everything posted up to a point in time). P&amp;L's is
 *       {@code dateacct BETWEEN yearStart AND yearEnd} — only postings WITHIN the fiscal year (or
 *       narrower, via {@code dateFrom}/{@code dateTo}) count. This is the textbook difference
 *       between a balance (stock) and an income statement (flow).</li>
 *   <li><b>{@code dateFrom}/{@code fromReferenceDate} DO have effect here</b> — the opposite of
 *       Balance Sheet, where the same two parameters are accepted but ignored (see that class's
 *       javadoc). The contract's own SQL references {@code __DATEFROM__}/{@code
 *       __FROMREFERENCEDATE__} directly as an additional lower bound alongside the year's period
 *       range.</li>
 *   <li><b>No {@code income_summary}/{@code net_income} CTE.</b> Balance Sheet synthesizes a extra
 *       fact row for the year's net income and unions it onto the income-summary account so the
 *       balance sheet closes. P&amp;L has no such synthetic row: it is the report that PRODUCES
 *       that net income figure in the first place (as its own root/formula total, e.g. "A)
 *       RESULTADO DE EXPLOTACIÓN" and the final result row), so nothing needs to be pre-summed
 *       into it.</li>
 *   <li><b>No {@code GROUP BY} in the outer query.</b> Balance Sheet's {@code facts} CTE can carry
 *       two rows for the same {@code account_id} (a real posted row plus the synthetic
 *       income-summary union row), so its outer {@code SELECT} needs {@code SUM(...) ... GROUP BY}
 *       to collapse them. P&amp;L's {@code facts} CTE already aggregates once per {@code
 *       account_id} (a plain {@code GROUP BY fa.account_id}), and {@code tree.node_id} is unique
 *       per node, so the outer {@code LEFT JOIN facts} is already 1:1 — no further aggregation is
 *       needed or present in the contract's own SQL.</li>
 *   <li><b>Org filter and everything else (org-tree {@code ad_isorgincluded} semantics, the
 *       {@code tree}/{@code roots} CTE skeleton, {@code operandsQuery}, output columns) are
 *       IDENTICAL to Balance Sheet</b> — see that class's javadoc for the org-tree-vs-exact-match
 *       rationale, which applies here unchanged.</li>
 * </ul>
 *
 * <p><b>{@code yearId} is required</b>, exactly like {@link BalanceSheetReportHandler} — the
 * contract itself declares it {@code required: true} on both sides. Unlike Balance Sheet, though,
 * {@code yearId} here bounds a RANGE (the fiscal year's period start through its period end), not
 * a single upper bound.
 */
@Named("profitLossReportHandler")
public class ProfitLossReportHandler extends AbstractAccountTreeReportHandler {

  // -------------------------------------------------------------------------
  // Access gate
  // -------------------------------------------------------------------------

  /**
   * Gates on the SAME coarse, category-level anchor the {@code report-viewer} gallery already uses
   * for this exact report row — {@link ReportAccessCatalog#FINANCIAL_REPORTS_WINDOW_ID} ("Informes
   * financieros" / Financial Reports). See {@link TrialBalanceReportHandler#isAccessibleForCurrentRole()}'s
   * javadoc for the full evidence trail; identical reasoning applies here, confirmed via the same
   * {@code ReportAccessCatalog.ROWS} entry for {@code profit-loss}.
   */
  @Override
  public boolean isAccessibleForCurrentRole() {
    return NeoAccessHelper.hasWindowAccess(ReportAccessCatalog.FINANCIAL_REPORTS_WINDOW_ID);
  }

  // -------------------------------------------------------------------------
  // Report contract
  // -------------------------------------------------------------------------

  @Override
  public Optional<List<NeoReportParam>> reportParameters() {
    return accountTreeParameters(
        "C_Year id whose fiscal period range bounds the report — Profit & Loss is a "
            + "PERIOD-ACTIVITY total of postings within that year (further narrowed by "
            + "dateFrom/dateTo, if given), unlike Balance Sheet's cumulative snapshot.",
        "Lower bound (inclusive) on the posting date, narrowing yearId's own fiscal "
            + "year-start bound. Default: no narrowing (the full fiscal year from yearId's "
            + "first period start).",
        "Upper bound (inclusive) on the posting date, narrowing yearId's own fiscal "
            + "year-end bound. Default: no narrowing (the full fiscal year through yearId's "
            + "last period end).",
        "Whether to also compute each row's amount as of a second, comparison period "
            + "(referenceYearId/fromReferenceDate/toReferenceDate). Default: false. When "
            + "true, referenceYearId is required.",
        "C_Year id for the comparison period. Required when compareTo is true; ignored "
            + "otherwise.",
        "Lower bound (inclusive) on the comparison posting date, narrowing "
            + "referenceYearId's own fiscal year-start bound. Only meaningful when compareTo "
            + "is true.",
        "Upper bound (inclusive) on the comparison posting date, narrowing "
            + "referenceYearId's own fiscal year-end bound. Only meaningful when compareTo "
            + "is true.");
  }

  @Override
  String reportName() {
    return "Profit & Loss";
  }

  @Override
  String reportDescription() {
    return "Pérdidas y Ganancias — Revenue vs Expenses, as an indented "
        + "account tree with roll-up and formula (computed total) rows, for a fiscal-year "
        + "period of activity.";
  }

  @Override
  String reportLabel() {
    return "profit and loss";
  }

  @Override
  String yearIdRequiredHint() {
    return "Pass the C_Year id whose fiscal period range the P&L should be reported over.";
  }

  @Override
  String referenceYearIdRequiredHint() {
    return "Pass the C_Year id for the comparison period, or set compareTo to false.";
  }

  // -------------------------------------------------------------------------
  // SQL — faithful port of artifacts/profit-loss/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  @Override
  String reportType() {
    return "N";
  }

  /** Unlike Balance Sheet, {@code dateFrom}/{@code fromReferenceDate} DO narrow the period here. */
  @Override
  boolean hasLowerDateBounds() {
    return true;
  }

  @Override
  String amountCase(String yearParam, boolean hasFrom, String fromParam, boolean hasTo,
      String toParam) {
    return buildPeriodCase(true, yearParam, hasFrom, fromParam, hasTo, toParam);
  }

  /**
   * Builds one period-activity SUM CASE: {@code dateacct} must fall within the given year's own
   * fiscal period range ({@code MIN(startdate)}..{@code MAX(enddate)} across its {@code c_period}
   * rows), further narrowed by an optional lower/upper date bound. Faithful to the contract's own
   * {@code fa.dateacct BETWEEN ... AND ('__DATEFROM__' = '' OR fa.dateacct >= ...) AND
   * ('__DATETO__' = '' OR fa.dateacct <= ...)} clause, resolved in Java rather than bound as a
   * string.
   */
  private static String buildPeriodCase(boolean hasYear, String yearParam, boolean hasFrom,
      String fromParam, boolean hasTo, String toParam) {
    StringBuilder condition = new StringBuilder();
    if (hasYear) {
      condition.append("fa.dateacct BETWEEN (SELECT MIN(p.startdate) FROM c_period p "
          + "WHERE p.c_year_id = :").append(yearParam).append(") AND "
          + "(SELECT MAX(p.enddate) FROM c_period p WHERE p.c_year_id = :").append(yearParam)
          .append(")");
    }
    if (hasFrom) {
      if (condition.length() > 0) {
        condition.append(" AND ");
      }
      condition.append("fa.dateacct >= :").append(fromParam);
    }
    if (hasTo) {
      if (condition.length() > 0) {
        condition.append(" AND ");
      }
      condition.append("fa.dateacct <= :").append(toParam);
    }
    return "CASE WHEN " + condition + " THEN fa.amtacctdr - fa.amtacctcr ELSE 0 END";
  }

  /**
   * One {@code facts} row per account, already aggregated — no {@code income_summary}/{@code
   * net_income} union (see this class's javadoc), hence no outer {@code GROUP BY}.
   */
  @Override
  String factsCtes(String mainCase, String refCase, String orgFilter) {
    return "facts AS ( "
        + "  SELECT fa.account_id, "
        + "    SUM(" + mainCase + ") AS own_amt, "
        + "    SUM(" + refCase + ") AS own_amt_ref "
        + "  FROM fact_acct fa "
        + "  WHERE fa.ad_client_id = :clientId AND fa.factaccttype NOT IN ('R', 'C') "
        + orgFilter
        + "    AND fa.c_acctschema_id = :acctSchemaId "
        + "  GROUP BY fa.account_id "
        + ") ";
  }

  @Override
  String ownAmountColumns() {
    return "COALESCE(f.own_amt, 0) AS own_amt, "
        + "  COALESCE(f.own_amt_ref, 0) AS own_amt_ref ";
  }

  @Override
  String outerGroupBy() {
    return "";
  }
}
