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
 * NeoHandler exposing the "Balance Sheet" / "Balance de Situación" report as an MCP report tool
 * ({@code generate_balance_sheet}, ETP-5483 slice 4).
 *
 * <p><b>Why this handler exists.</b> Before this class, {@code balance-sheet} was served ONLY by
 * the two Node report engines (the Vite dev plugin, {@code
 * tools/app-shell/vite-plugins/report-api.js}, and the production {@code
 * tools/report-server/server.js} in {@code schema_forge_core}), which run {@code
 * artifacts/balance-sheet/report-contract.json}'s {@code sql.query}/{@code sql.operandsQuery} — raw
 * SQL with {@code __PLACEHOLDER__} text substitution — directly against Postgres, then fold the
 * flat node list into an indented tree with {@code report-grouping.js}'s {@code
 * buildAccountReportTree}. Neither engine is reachable from the Java MCP servlet, so an MCP agent
 * could not run this report at all. This handler is a faithful Java port of that same query, using
 * real bind parameters instead of string substitution, plus {@link AccountReportTree} — a pure
 * Java port of {@code buildAccountReportTree} shared with a future Profit &amp; Loss handler (see
 * that class's javadoc for the reuse contract).
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> Same shape as
 * {@link TrialBalanceReportHandler} and {@link JournalEntriesReportHandler}: this report now has
 * TWO independent implementations of the same business logic — the Node/SQL-placeholder one (this
 * class's source of truth, kept in {@code schema_forge}'s {@code artifacts/balance-sheet/
 * report-contract.json}) and this Java one. They are NOT structurally linked, and the SPA keeps
 * using the Node path exclusively — this handler is additive, MCP-only. A change to the balance
 * sheet SQL (new root/group, changed net-income resolution, changed org scoping) in the Node
 * contract MUST be mirrored here by hand, and vice versa.
 *
 * <p><b>Org-filter semantics — an ORG TREE, unlike {@link TrialBalanceReportHandler}/{@link
 * JournalEntriesReportHandler}.</b> Those two siblings filter organization with plain equality
 * ({@code fa.ad_org_id = '__ORGID__'}). This report's own SQL is different: {@code
 * ad_isorgincluded(fa.ad_org_id, '__ORGID__', fa.ad_client_id) <> -1}, Etendo's own org-tree
 * membership function — so an explicit {@code orgId} includes that organization AND every
 * descendant in its org tree, not just the one row. A blank {@code orgId} applies no organization
 * filter at all (every org of the client), and an OMITTED {@code orgId} defaults to the session's
 * current organization — same "default to the session's organization" convention the two siblings
 * already use, for the same reason (the contract's own {@code orgId} parameter is {@code hidden:
 * true, required: true}, meaning the SPA always auto-fills it from the session; an MCP caller that
 * omits it gets the same sensible default rather than an unfiltered, client-wide report by
 * accident). An MCP caller that explicitly wants every organization can still pass an empty string.
 *
 * <p><b>{@code dateFrom}/{@code fromReferenceDate} are accepted but have NO effect.</b> Faithful to
 * the contract's own SQL: neither placeholder appears anywhere in {@code sql.query} — Balance Sheet
 * is a CUMULATIVE snapshot as of a point in time ({@code yearId}'s fiscal year end, optionally
 * narrowed by {@code dateTo}), not a period-activity report, so there is no lower date bound to
 * apply. This is a genuine, verified property of the source contract (confirmed by placeholder
 * extraction against the live JSON, ETP-5483 slice 4 investigation) and not an omission on this
 * handler's part — declaring the two parameters keeps the Java and Node contracts symmetric for a
 * caller who passes every field {@code report-contract.json} lists, without silently accepting and
 * then ignoring an undeclared key. Contrast with Profit &amp; Loss's own {@code report-contract.json}
 * (not yet ported), whose SQL DOES reference {@code __DATEFROM__}/{@code __FROMREFERENCEDATE__} —
 * a period-activity report, unlike this cumulative one.
 *
 * <p><b>{@code yearId} bounds the query, not {@code dateTo} alone.</b> The main query's upper bound
 * is {@code fa.dateacct <= MAX(c_period.enddate) WHERE c_year_id = yearId}, further narrowed by
 * {@code dateTo} when given. {@code yearId} is therefore a genuinely required parameter (matching
 * the contract), unlike {@code acctSchemaId}/{@code orgId} below.
 */
@Named("balanceSheetReportHandler")
public class BalanceSheetReportHandler extends AbstractAccountTreeReportHandler {

  // -------------------------------------------------------------------------
  // Access gate
  // -------------------------------------------------------------------------

  /**
   * Gates on the SAME coarse, category-level anchor the {@code report-viewer} gallery already uses
   * for this exact report row — {@link ReportAccessCatalog#FINANCIAL_REPORTS_WINDOW_ID} ("Informes
   * financieros" / Financial Reports). See {@link TrialBalanceReportHandler#isAccessibleForCurrentRole()}'s
   * javadoc for the full evidence trail; identical reasoning applies here, confirmed via the same
   * {@code ReportAccessCatalog.ROWS} entry for {@code balance-sheet}.
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
        "C_Year id whose fiscal period end bounds the report — the balance is a cumulative "
            + "snapshot as of that year's last period end date (further narrowed by dateTo, "
            + "if given), not a period-activity total.",
        "Accepted for symmetry with the report's other date parameters, but has NO EFFECT: "
            + "Balance Sheet is a cumulative snapshot, not a period-activity report, so there "
            + "is no lower date bound to apply. See yearId/dateTo for what actually bounds "
            + "the query.",
        "Upper bound (inclusive) on the snapshot date, narrowing yearId's own fiscal "
            + "year-end bound. Default: no narrowing (the full fiscal year through yearId's "
            + "last period end).",
        "Whether to also compute each row's amount as of a second, comparison period "
            + "(referenceYearId/toReferenceDate). Default: false. When true, "
            + "referenceYearId is required.",
        "C_Year id for the comparison snapshot. Required when compareTo is true; ignored "
            + "otherwise.",
        "Accepted for symmetry, but has NO EFFECT — same reason as dateFrom above.",
        "Upper bound (inclusive) on the comparison snapshot date, narrowing "
            + "referenceYearId's own fiscal year-end bound. Only meaningful when compareTo "
            + "is true.");
  }

  @Override
  String reportName() {
    return "Balance Sheet";
  }

  @Override
  String reportDescription() {
    return "Balance de Situación — Assets vs Liabilities + Equity, as an "
        + "indented account tree with roll-up and formula (computed total) rows, as of a "
        + "fiscal year end.";
  }

  @Override
  String reportLabel() {
    return "balance sheet";
  }

  @Override
  String yearIdRequiredHint() {
    return "Pass the C_Year id whose fiscal period end the balance should be reported as of.";
  }

  @Override
  String referenceYearIdRequiredHint() {
    return "Pass the C_Year id for the comparison snapshot, or set compareTo to false.";
  }

  // -------------------------------------------------------------------------
  // SQL — faithful port of artifacts/balance-sheet/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  @Override
  String reportType() {
    return "Y";
  }

  /** {@code dateFrom}/{@code fromReferenceDate} have no effect — see this class's javadoc. */
  @Override
  boolean hasLowerDateBounds() {
    return false;
  }

  /**
   * Cumulative snapshot: everything posted up to the year's last period end, further narrowed by
   * the optional upper bound. There is no lower bound (see {@link #hasLowerDateBounds()}), so
   * {@code hasFrom}/{@code fromParam} are never used. When comparing, this is also the reference
   * CASE — faithful to the contract's own {@code '__COMPARETO__' = 'true' AND (...)} clause,
   * resolved in Java; without {@code compareTo} the comparison branch is a constant {@code 0},
   * exactly what the Node placeholder substitution collapses to once {@code
   * stripBlankOptionalClauses} strips the dead branch.
   */
  @Override
  String amountCase(String yearParam, boolean hasFrom, String fromParam, boolean hasTo,
      String toParam) {
    return "CASE WHEN fa.dateacct <= (SELECT MAX(p.enddate) FROM c_period p "
        + "WHERE p.c_year_id = :" + yearParam + ")"
        + (hasTo ? " AND fa.dateacct <= :" + toParam : "")
        + " THEN fa.amtacctdr - fa.amtacctcr ELSE 0 END";
  }

  /**
   * {@code posted} per account, plus the synthetic {@code net_income} row unioned onto the
   * income-summary account so the balance sheet closes — which is why {@code facts} can carry two
   * rows for one account and the outer query aggregates (see {@link #outerGroupBy()}).
   */
  @Override
  String factsCtes(String mainCase, String refCase, String orgFilter) {
    return "income_summary AS ( "
        + "  SELECT vc.account_id AS node_id FROM c_acctschema_gl gl "
        + "  JOIN c_validcombination vc ON vc.c_validcombination_id = gl.incomesummary_acct "
        + "  WHERE gl.c_acctschema_id = :acctSchemaId LIMIT 1 "
        + "), "
        + "posted AS ( "
        + "  SELECT fa.account_id, "
        + "    SUM(" + mainCase + ") AS dr_minus_cr, "
        + "    SUM(" + refCase + ") AS dr_minus_cr_ref "
        + "  FROM fact_acct fa "
        + "  WHERE fa.ad_client_id = :clientId AND fa.factaccttype NOT IN ('R', 'C') "
        + orgFilter
        + "    AND fa.c_acctschema_id = :acctSchemaId "
        + "  GROUP BY fa.account_id "
        + "), "
        + "net_income AS ( "
        + "  SELECT "
        + "    SUM(" + mainCase + ") AS dr_minus_cr, "
        + "    SUM(" + refCase + ") AS dr_minus_cr_ref "
        + "  FROM fact_acct fa JOIN c_elementvalue ev ON ev.c_elementvalue_id = fa.account_id "
        + "  WHERE fa.ad_client_id = :clientId AND fa.factaccttype NOT IN ('R', 'C') "
        + "    AND ev.accounttype IN ('R', 'E') "
        + orgFilter
        + "    AND fa.c_acctschema_id = :acctSchemaId "
        + "), "
        + "facts AS ( "
        + "  SELECT account_id, dr_minus_cr, dr_minus_cr_ref FROM posted "
        + "  UNION ALL "
        + "  SELECT (SELECT node_id FROM income_summary), (SELECT dr_minus_cr FROM net_income), "
        + "    (SELECT dr_minus_cr_ref FROM net_income) "
        + ") ";
  }

  @Override
  String ownAmountColumns() {
    return "COALESCE(SUM(f.dr_minus_cr), 0) AS own_amt, "
        + "  COALESCE(SUM(f.dr_minus_cr_ref), 0) AS own_amt_ref ";
  }

  @Override
  String outerGroupBy() {
    return "GROUP BY t.node_id, t.parent_id, t.depth, t.sort_path, t.group_name, ev.value, "
        + "  ev.name, ev.elementlevel, ev.isalwaysshown, ev.accountsign ";
  }
}
