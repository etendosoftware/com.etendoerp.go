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

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;

/**
 * NeoHandler exposing the "General Ledger" / "Libro Mayor" report as an MCP report tool
 * ({@code generate_report_general_ledger}, ETP-5483 slice 6, the last one).
 *
 * <p><b>Why this handler exists.</b> Before this class, {@code report-general-ledger} was served
 * ONLY by the two Node report engines (the Vite dev plugin, {@code
 * tools/app-shell/vite-plugins/report-api.js}, and the production {@code
 * tools/report-server/server.js} in {@code schema_forge_core}), which run {@code
 * artifacts/report-general-ledger/report-contract.json}'s {@code sql.query}/{@code
 * sql.openingQuery} — raw SQL strings with {@code __PLACEHOLDER__} text substitution — directly
 * against Postgres, then fold the flat result into per-account cards with
 * {@code schema_forge_core/cli/src/report-grouping.js}'s {@code buildNestedGroups}/
 * {@code foldOpeningBalance}. Neither engine is reachable from the Java MCP servlet, so an MCP
 * agent could not run this report at all. This handler is a faithful Java port of that same SQL,
 * using real bind parameters instead of string substitution, plus {@link GeneralLedgerGrouping} —
 * a Java port of {@code buildNestedGroups}/{@code foldOpeningBalance} — for the post-processing.
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> Same shape as
 * {@link TrialBalanceReportHandler} and {@link JournalEntriesReportHandler}: this report now has
 * TWO independent implementations of the same business logic — the Node/SQL-placeholder one (this
 * class's source of truth, kept in {@code schema_forge}'s {@code
 * artifacts/report-general-ledger/report-contract.json} plus {@code schema_forge_core}'s {@code
 * cli/src/report-grouping.js}) and this Java one. They are NOT structurally linked, and the SPA
 * keeps using the Node path exclusively — this handler is additive, MCP-only. A change to the
 * general ledger SQL (new filter, changed account-range bound, changed dimension joins) or to its
 * post-processing (the opening-balance fold, the running-balance accumulation) in the Node
 * contract/JS MUST be mirrored here by hand, and vice versa.
 *
 * <p><b>Org-filter semantics</b> mirror {@link TrialBalanceReportHandler}/
 * {@link JournalEntriesReportHandler} exactly (see either class's javadoc for the full reasoning):
 * an explicit {@code orgId} filters EXACTLY that one organization (the contract's own SQL uses
 * plain equality, {@code fa.ad_org_id = '__ORGID__'}, never a tree), an explicit empty string
 * reports on every organization of the client, and an OMITTED {@code orgId} defaults to the
 * session's current organization.
 *
 * <p><b>{@code factaccttype} scope.</b> Faithful to the contract's own main-query filter,
 * {@code fa.factaccttype NOT IN ('R', 'C')} — P&amp;L closing and period-closing entries are
 * excluded, matching what "Libro Mayor" shows a user (their effect is already folded into the
 * accounts they close, not reported again as separate movements). The contract's own
 * {@code openingQuery} carries NO {@code factaccttype} filter at all (it opens with every entry
 * type before {@code dateFrom}), which this handler reproduces exactly — see
 * {@link #buildOpeningRows}.
 *
 * <p><b>Multi-value id parameters.</b> Faithful to how the Node engines' {@code applyPlaceholders}
 * substitutes a multi-select parameter into an {@code IN (...)} clause (see
 * {@link JournalEntriesReportHandler}'s javadoc for the mechanism) — {@code bPartnerId}/
 * {@code productId}/{@code projectId}/{@code costCenterId} support more than one id here too, via
 * real bind lists.
 *
 * <p><b>Size safety — two independent SQL-level caps, never a wrong total.</b> A general ledger can
 * return every line of every account in the period. This handler caps on TWO axes at once:
 * <ul>
 *   <li>{@link #PARAM_ACCOUNT_LIMIT} — the number of DISTINCT accounts returned. An included
 *       account is never cut in half by this cap; an excluded account is entirely absent.</li>
 *   <li>{@link #PARAM_LINES_PER_ACCOUNT_LIMIT} — the number of lines returned PER account, so one
 *       extremely active account (e.g. a bank account with thousands of movements) cannot alone
 *       blow up the response even when {@code accountLimit} is small.</li>
 * </ul>
 * Both caps are enforced in SQL via window functions ({@code DENSE_RANK()}/{@code ROW_NUMBER()},
 * see {@link #buildLineRows}) — never by pulling every row into the JVM and truncating there. An
 * account's {@code opening}/{@code subtotal}/{@code total} are always computed from SQL-level
 * aggregates that ignore {@code linesPerAccountLimit} entirely (see {@link #buildAccountTotals}),
 * so those numbers are correct even when a very active account's lines were cut — only
 * {@code meta.truncatedAccounts}/each account's own {@code linesTruncated} flag tells the caller
 * that some rows were not returned. See {@link GeneralLedgerGrouping}'s javadoc for how the
 * running balance stays correct on the lines that ARE returned.
 */
@Named("generalLedgerReportHandler")
public class GeneralLedgerReportHandler extends AbstractSqlReportHandler {

  private static final String PARAM_SHOW_OPEN_BALANCES = "showOpenBalances";
  private static final String PARAM_ACCOUNT_LIMIT = "accountLimit";
  private static final String PARAM_LINES_PER_ACCOUNT_LIMIT = "linesPerAccountLimit";

  /** Number of ACCOUNTS returned when the caller does not pass {@code accountLimit}. */
  private static final int DEFAULT_ACCOUNT_LIMIT = 100;
  /** Hard ceiling on {@code accountLimit}, regardless of what the caller asks for. */
  private static final int MAX_ACCOUNT_LIMIT = 500;
  /** Number of LINES per account when the caller does not pass {@code linesPerAccountLimit}. */
  private static final int DEFAULT_LINES_PER_ACCOUNT_LIMIT = 500;
  /** Hard ceiling on {@code linesPerAccountLimit}, regardless of what the caller asks for. */
  private static final int MAX_LINES_PER_ACCOUNT_LIMIT = 2000;

  /** {@code groupBy} value → the row field {@link GeneralLedgerGrouping} folds by, mirroring the
   * contract's {@code groupByValue}/{@code groupByField} parameter pairs. */
  private static final Map<String, String> GROUP_BY_FIELDS = Map.of(
      "bpartner", "bpname",
      "product", "productname",
      "project", "projectname",
      "costcenter", "costcentername");

  // -------------------------------------------------------------------------
  // Access gate
  // -------------------------------------------------------------------------

  /**
   * Gates on the SAME coarse, category-level anchor the {@code report-viewer} gallery uses for
   * this exact report row — {@link ReportAccessCatalog#FINANCIAL_REPORTS_WINDOW_ID}. See
   * {@link TrialBalanceReportHandler#isAccessibleForCurrentRole()}'s javadoc for the full evidence
   * trail; identical reasoning applies here, confirmed via the same {@code ReportAccessCatalog.ROWS}
   * entry for {@code report-general-ledger}.
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
    return Optional.of(List.of(
        NeoReportParam.required(PARAM_DATE_FROM, NeoReportParam.TYPE_DATE,
            "Start of the reporting period, inclusive (yyyy-MM-dd)."),
        NeoReportParam.required(PARAM_DATE_TO, NeoReportParam.TYPE_DATE,
            "End of the reporting period, inclusive (yyyy-MM-dd). Must not be before dateFrom."),
        NeoReportParam.optional(PARAM_ORG_ID, NeoReportParam.TYPE_STRING,
            "Organization id to report on. Filters that EXACT organization only (no descendant "
                + "orgs). Default: the session's current organization. Pass an empty string to "
                + "report on every organization of the client instead."),
        NeoReportParam.optional(PARAM_ACCT_SCHEMA_ID, NeoReportParam.TYPE_STRING,
            "Accounting schema (C_AcctSchema) id whose postings are read. Default: the "
                + "organization's general ledger."),
        NeoReportParam.optional(PARAM_FROM_ACCOUNT_ID, NeoReportParam.TYPE_STRING,
            "Lower bound (inclusive) on the account search key (C_ElementValue.value), NOT its "
                + "id. Default: no lower bound."),
        NeoReportParam.optional(PARAM_TO_ACCOUNT_ID, NeoReportParam.TYPE_STRING,
            "Upper bound (inclusive) on the account search key (C_ElementValue.value), NOT its "
                + "id. Default: no upper bound."),
        NeoReportParam.optional(PARAM_BPARTNER_ID, NeoReportParam.TYPE_STRING,
            "Restrict to lines posted against these business partners: one C_BPartner id, or "
                + "several separated by commas. Default: every partner."),
        NeoReportParam.optional(PARAM_PRODUCT_ID, NeoReportParam.TYPE_STRING,
            "Restrict to lines posted against these products: one M_Product id, or several "
                + "separated by commas. Default: every product."),
        NeoReportParam.optional(PARAM_PROJECT_ID, NeoReportParam.TYPE_STRING,
            "Restrict to lines posted against these projects: one C_Project id, or several "
                + "separated by commas. Default: every project."),
        NeoReportParam.optional(PARAM_COST_CENTER_ID, NeoReportParam.TYPE_STRING,
            "Restrict to lines posted against these cost centers: one C_CostCenter id, or "
                + "several separated by commas. Default: every cost center."),
        NeoReportParam.optional(PARAM_SHOW_DIMENSIONS, NeoReportParam.TYPE_BOOLEAN,
            "Whether each returned line includes its accounting dimension names (bpname, "
                + "productname, projectname, costcentername). Default: false."),
        NeoReportParam.options(PARAM_GROUP_BY,
            "Nest accounts inside a dimension group instead of a flat account list. Default: "
                + "ungrouped (one group with every account).",
            List.of("bpartner", "product", "project", "costcenter")),
        NeoReportParam.optional(PARAM_SHOW_OPEN_BALANCES, NeoReportParam.TYPE_BOOLEAN,
            "Whether each account's response includes its opening (\"Initial Balance\") object. "
                + "The opening balance is always computed correctly either way; this only "
                + "controls whether it is included in the response. Default: true."),
        NeoReportParam.optional(PARAM_ACCOUNT_LIMIT, NeoReportParam.TYPE_INTEGER,
            "Maximum number of DISTINCT accounts to return, ranked by account search key. An "
                + "included account is never cut short by this cap (see linesPerAccountLimit for "
                + "that). Default: " + DEFAULT_ACCOUNT_LIMIT + ". Hard max: " + MAX_ACCOUNT_LIMIT
                + ". When more accounts match the filters than this cap, the response is "
                + "truncated (see meta.truncatedAccounts/meta.totalAccounts) — narrow "
                + "fromAccountId/toAccountId or dateFrom/dateTo to see the rest."),
        NeoReportParam.optional(PARAM_LINES_PER_ACCOUNT_LIMIT, NeoReportParam.TYPE_INTEGER,
            "Maximum number of lines returned PER account, ranked by dateacct. Each account's "
                + "opening/subtotal/total are always computed from the FULL period (never "
                + "affected by this cap) — only the line list itself may be shorter; see each "
                + "account's own linesTruncated/totalLines. Default: "
                + DEFAULT_LINES_PER_ACCOUNT_LIMIT + ". Hard max: " + MAX_LINES_PER_ACCOUNT_LIMIT
                + ".")));
  }

  @Override
  String reportName() {
    return "General Ledger";
  }

  @Override
  String reportDescription() {
    return "Libro Mayor — every accounting movement posted in the period, "
        + "nested as one object per account with its chronological lines and running balance. "
        + "Optionally grouped by a dimension (contact/product/project/cost center).";
  }

  @Override
  String reportLabel() {
    return "general ledger";
  }

  // -------------------------------------------------------------------------
  // POST — execute
  // -------------------------------------------------------------------------

  @Override
  NeoResponse executeReport(JSONObject body) throws Exception {
    NeoResponse validationError = validateDateRange(body);
    if (validationError != null) {
      return validationError;
    }
    LocalDate dateFrom = parseDate(body, PARAM_DATE_FROM);
    LocalDate dateTo = parseDate(body, PARAM_DATE_TO);

    // Pure input checks run before anything touches OBContext or the database.
    int accountLimit = body.optInt(PARAM_ACCOUNT_LIMIT, DEFAULT_ACCOUNT_LIMIT);
    NeoResponse accountLimitError = validateAccountLimit(accountLimit);
    if (accountLimitError != null) {
      return accountLimitError;
    }
    accountLimit = Math.min(accountLimit, MAX_ACCOUNT_LIMIT);

    int linesPerAccountLimit = body.optInt(PARAM_LINES_PER_ACCOUNT_LIMIT,
        DEFAULT_LINES_PER_ACCOUNT_LIMIT);
    NeoResponse linesPerAccountLimitError = validateLinesPerAccountLimit(linesPerAccountLimit);
    if (linesPerAccountLimitError != null) {
      return linesPerAccountLimitError;
    }
    linesPerAccountLimit = Math.min(linesPerAccountLimit, MAX_LINES_PER_ACCOUNT_LIMIT);

    String groupBy = body.optString(PARAM_GROUP_BY, "");
    NeoResponse groupByError = validateGroupBy(groupBy);
    if (groupByError != null) {
      return groupByError;
    }
    String dimensionField = groupBy.isEmpty() ? null : GROUP_BY_FIELDS.get(groupBy);

    String orgId = resolveOrgId(body);
    NeoResponse orgIdError = validateOrgId(orgId);
    if (orgIdError != null) {
      return orgIdError;
    }

    String acctSchemaId = resolveAcctSchemaId(body, orgId);
    NeoResponse acctSchemaError = validateAcctSchemaResolved(acctSchemaId, orgId);
    if (acctSchemaError != null) {
      return acctSchemaError;
    }

    boolean showDimensions = body.optBoolean(PARAM_SHOW_DIMENSIONS, false);
    boolean showOpenBalances = body.optBoolean(PARAM_SHOW_OPEN_BALANCES, true);

    String clientId = OBContext.getOBContext().getCurrentClient().getId();

    LedgerFilters filters = LedgerFilters.fromRequest(body, clientId, orgId, acctSchemaId,
        dateFrom, dateTo);

    List<GeneralLedgerGrouping.Row> lineRows = buildLineRows(filters, accountLimit,
        linesPerAccountLimit);
    List<String> accountValues = distinctAccountValues(lineRows);

    List<GeneralLedgerGrouping.AccountTotal> accountTotals = buildAccountTotals(filters,
        accountValues);
    List<GeneralLedgerGrouping.OpeningRow> openingRows = buildOpeningRows(filters, accountValues);
    long totalAccounts = countTotalAccounts(filters);

    List<GeneralLedgerGrouping.Group> groups = GeneralLedgerGrouping.nest(lineRows,
        dimensionField, openingRows, accountTotals);

    int accountsReturned = accountValues.size();
    boolean truncatedAccounts = totalAccounts > accountsReturned;

    JSONArray data = buildDataArray(groups, showDimensions, showOpenBalances);
    return reportResponse(data, accountsReturned, buildMeta(filters, new ResponseMeta(accountLimit,
        linesPerAccountLimit, truncatedAccounts, totalAccounts, accountsReturned, groupBy)));
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  private static NeoResponse validateAccountLimit(int accountLimit) {
    if (accountLimit <= 0) {
      return actionableError(400, "account_limit_invalid",
          "accountLimit must be a positive integer.",
          "Pass a positive number of accounts, or omit accountLimit to use the default ("
              + DEFAULT_ACCOUNT_LIMIT + ").");
    }
    return null;
  }

  private static NeoResponse validateLinesPerAccountLimit(int linesPerAccountLimit) {
    if (linesPerAccountLimit <= 0) {
      return actionableError(400, "lines_per_account_limit_invalid",
          "linesPerAccountLimit must be a positive integer.",
          "Pass a positive number of lines, or omit linesPerAccountLimit to use the default ("
              + DEFAULT_LINES_PER_ACCOUNT_LIMIT + ").");
    }
    return null;
  }

  private static NeoResponse validateGroupBy(String groupBy) {
    if (groupBy.isEmpty()) {
      return null;
    }
    if (!GROUP_BY_FIELDS.containsKey(groupBy)) {
      return actionableError(400, "group_by_invalid",
          "groupBy '" + groupBy + "' is not a recognized grouping dimension.",
          "Pass one of " + GROUP_BY_FIELDS.keySet() + ", or omit groupBy for a flat report.");
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Filters — bound identically by every SQL method below
  // -------------------------------------------------------------------------

  /** Appends the shared WHERE clauses common to the main, totals and count queries. */
  private static void appendCommonWhere(StringBuilder sql, LedgerFilters f) {
    f.appendDimensionWhere(sql, "  ");
    f.appendAccountRangeWhere(sql, "  ", "ev.value");
  }

  private static void bindCommon(NativeQuery<?> query, LedgerFilters f) {
    query.setParameter(SQL_PARAM_CLIENT_ID, f.clientId);
    query.setParameter(PARAM_ACCT_SCHEMA_ID, f.acctSchemaId);
    f.bindDimensions(query);
    f.bindAccountRange(query);
  }

  // -------------------------------------------------------------------------
  // SQL — faithful port of artifacts/report-general-ledger/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  /** SQL column order for {@link #buildLineRows}. */
  private static final int COL_ACCOUNT_NO = 0;
  private static final int COL_ACCOUNT_ID = 1;
  private static final int COL_ACCOUNT_NAME = 2;
  private static final int COL_DATEACCT = 3;
  private static final int COL_FACT_ACCT_GROUP_ID = 4;
  private static final int COL_GROUPBYNAME = 5;
  private static final int COL_AMTACCTDR = 6;
  private static final int COL_AMTACCTCR = 7;
  private static final int COL_BPNAME = 8;
  private static final int COL_PRODUCTNAME = 9;
  private static final int COL_PROJECTNAME = 10;
  private static final int COL_COSTCENTERNAME = 11;

  /**
   * Runs the main line-grain query, capped on BOTH axes at once via window functions on a CTE:
   * {@code account_rn} ({@code DENSE_RANK()} over distinct account values, ordered like the
   * contract's own {@code ORDER BY ev.value, ev.name}) keeps only the first {@code accountLimit}
   * accounts, and {@code line_rn} ({@code ROW_NUMBER()} partitioned per account, ordered by
   * {@code dateacct}) keeps only the first {@code linesPerAccountLimit} lines of each of those
   * accounts — see this class's javadoc "Size safety" section.
   */
  @SuppressWarnings("unchecked")
  private List<GeneralLedgerGrouping.Row> buildLineRows(LedgerFilters f, int accountLimit,
      int linesPerAccountLimit) {
    StringBuilder sql = new StringBuilder(
        "WITH gl AS ( "
            + "  SELECT ev.value AS account_no, ev.c_elementvalue_id AS account_id, ev.name AS account_name, "
            + "    fa.dateacct, fa.fact_acct_group_id, fa.fact_acct_id, "
            + "    COALESCE(bp.name, fa.description) AS groupbyname, "
            + "    fa.amtacctdr, fa.amtacctcr, "
            + "    bp.name AS bpname, p.name AS productname, pj.name AS projectname, cc.name AS costcentername, "
            + "    DENSE_RANK() OVER (ORDER BY ev.value, ev.name) AS account_rn, "
            // Same fully deterministic line order as the contract's ORDER BY (date, entry, fact_acct
            // line), so the cap keeps exactly the lines the SPA would print first.
            + "    ROW_NUMBER() OVER (PARTITION BY ev.value "
            + "      ORDER BY fa.dateacct, fa.fact_acct_group_id, fa.fact_acct_id) AS line_rn "
            + "  FROM fact_acct fa "
            + "  JOIN c_elementvalue ev ON ev.c_elementvalue_id = fa.account_id "
            + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = fa.c_bpartner_id "
            + "  LEFT JOIN m_product p ON p.m_product_id = fa.m_product_id "
            + "  LEFT JOIN c_project pj ON pj.c_project_id = fa.c_project_id "
            + "  LEFT JOIN c_costcenter cc ON cc.c_costcenter_id = fa.c_costcenter_id "
            + "  WHERE fa.ad_client_id = :clientId "
            + "    AND fa.factaccttype NOT IN ('R', 'C') "
            + "    AND fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "    AND fa.c_acctschema_id = :acctSchemaId ");
    appendCommonWhere(sql, f);
    sql.append(
        ") "
            + "SELECT account_no, account_id, account_name, dateacct, fact_acct_group_id, groupbyname, "
            + "  amtacctdr, amtacctcr, bpname, productname, projectname, costcentername "
            + "FROM gl "
            + "WHERE account_rn <= :accountLimit AND line_rn <= :linesPerAccountLimit "
            // Keep in sync with artifacts/report-general-ledger/report-contract.json's ORDER BY:
            // without the entry + line tie-break, same-day lines come back in whatever order the
            // plan yields and the printed running balance of that day is not reproducible.
            + "ORDER BY account_no, account_name, dateacct, fact_acct_group_id, fact_acct_id");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    bindCommon(query, f);
    query.setParameter(PARAM_DATE_FROM, Date.valueOf(f.dateFrom));
    query.setParameter(PARAM_DATE_TO, Date.valueOf(f.dateTo));
    query.setParameter(PARAM_ACCOUNT_LIMIT, (long) accountLimit);
    query.setParameter(PARAM_LINES_PER_ACCOUNT_LIMIT, (long) linesPerAccountLimit);

    List<GeneralLedgerGrouping.Row> rows = new ArrayList<>();
    for (Object[] r : query.list()) {
      rows.add(GeneralLedgerGrouping.Row.builder()
          .accountNo(str(r[COL_ACCOUNT_NO]))
          .accountId(str(r[COL_ACCOUNT_ID]))
          .accountName(str(r[COL_ACCOUNT_NAME]))
          .dateacct(toIsoDate(r[COL_DATEACCT]))
          .factAcctGroupId(str(r[COL_FACT_ACCT_GROUP_ID]))
          .groupbyname(str(r[COL_GROUPBYNAME]))
          .amtacctdr(toBigDecimal(r[COL_AMTACCTDR]))
          .amtacctcr(toBigDecimal(r[COL_AMTACCTCR]))
          .bpname(str(r[COL_BPNAME]))
          .productname(str(r[COL_PRODUCTNAME]))
          .projectname(str(r[COL_PROJECTNAME]))
          .costcentername(str(r[COL_COSTCENTERNAME]))
          .build());
    }
    return rows;
  }

  private static List<String> distinctAccountValues(List<GeneralLedgerGrouping.Row> rows) {
    // LinkedHashSet-free dedupe: rows are already ordered by account_no, so a linear scan is
    // enough and keeps the natural order for the IN(...) binds below.
    List<String> values = new ArrayList<>();
    String last = null;
    for (GeneralLedgerGrouping.Row r : rows) {
      if (!r.accountNo.equals(last)) {
        values.add(r.accountNo);
        last = r.accountNo;
      }
    }
    return values;
  }

  /**
   * Full-period, UNCAPPED per-account × dimension-breakdown totals (never subject to {@code
   * linesPerAccountLimit}) — the source of truth for {@link GeneralLedgerGrouping.Account#subtotal}
   * /{@code total}/{@code totalLines}. Broken down by account × dimension breakdown (bpname/
   * productname/projectname/costcentername), the SAME grain as {@link #buildOpeningRows}, so
   * {@link GeneralLedgerGrouping#foldAccountTotal} can scope an account's subtotal to ONE
   * dimension group when {@code groupBy} is set — without this breakdown, an account appearing
   * under two different dimension values would show both groups the account's COMBINED total
   * instead of each group's own slice (see {@link GeneralLedgerGrouping.AccountTotal}'s javadoc).
   * Scoped to {@code accountValues} (the accounts {@link #buildLineRows} already selected), since
   * totals are only ever needed for accounts actually returned.
   */
  @SuppressWarnings("unchecked")
  private List<GeneralLedgerGrouping.AccountTotal> buildAccountTotals(LedgerFilters f,
      List<String> accountValues) {
    if (accountValues.isEmpty()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder(
        "SELECT ev.value AS account_no, bp.name AS bpname, p.name AS productname, "
            + "  pj.name AS projectname, cc.name AS costcentername, "
            + "  SUM(fa.amtacctdr) AS amtacctdr, SUM(fa.amtacctcr) AS amtacctcr, "
            + "  COUNT(*) AS line_count "
            + "FROM fact_acct fa "
            + "JOIN c_elementvalue ev ON ev.c_elementvalue_id = fa.account_id "
            + "LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = fa.c_bpartner_id "
            + "LEFT JOIN m_product p ON p.m_product_id = fa.m_product_id "
            + "LEFT JOIN c_project pj ON pj.c_project_id = fa.c_project_id "
            + "LEFT JOIN c_costcenter cc ON cc.c_costcenter_id = fa.c_costcenter_id "
            + "WHERE fa.ad_client_id = :clientId "
            + "  AND fa.factaccttype NOT IN ('R', 'C') "
            + "  AND fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "  AND fa.c_acctschema_id = :acctSchemaId "
            + "  AND ev.value IN (:accountValues) ");
    appendCommonWhere(sql, f);
    sql.append("GROUP BY ev.value, bp.name, p.name, pj.name, cc.name");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    bindCommon(query, f);
    query.setParameter(PARAM_DATE_FROM, Date.valueOf(f.dateFrom));
    query.setParameter(PARAM_DATE_TO, Date.valueOf(f.dateTo));
    query.setParameterList("accountValues", accountValues);

    List<GeneralLedgerGrouping.AccountTotal> totals = new ArrayList<>();
    for (Object[] r : query.list()) {
      totals.add(GeneralLedgerGrouping.AccountTotal.builder()
          .accountNo(str(r[0]))
          .bpname(str(r[1]))
          .productname(str(r[2]))
          .projectname(str(r[3]))
          .costcentername(str(r[4]))
          .amtacctdr(toBigDecimal(r[5]))
          .amtacctcr(toBigDecimal(r[6]))
          .lineCount(toLong(r[7]))
          .build());
    }
    return totals;
  }

  /**
   * Faithful port of the contract's {@code sql.openingQuery}: sums every entry BEFORE
   * {@code dateFrom} (deliberately NO {@code factaccttype} filter, exactly like the contract),
   * broken down by account × dimension breakdown (bpname/productname/projectname/costcentername)
   * so {@link GeneralLedgerGrouping#foldOpeningBalance} can scope it to a dimension group when
   * {@code groupBy} is set. Scoped to {@code accountValues} for the same reason as
   * {@link #buildAccountTotals}.
   */
  @SuppressWarnings("unchecked")
  private List<GeneralLedgerGrouping.OpeningRow> buildOpeningRows(LedgerFilters f,
      List<String> accountValues) {
    if (accountValues.isEmpty()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder(
        "SELECT ev.value AS account_no, bp.name AS bpname, p.name AS productname, "
            + "  pj.name AS projectname, cc.name AS costcentername, "
            + "  COALESCE(SUM(fa.amtacctdr), 0) AS openingdr, COALESCE(SUM(fa.amtacctcr), 0) AS openingcr "
            + "FROM fact_acct fa "
            + "JOIN c_elementvalue ev ON ev.c_elementvalue_id = fa.account_id "
            + "LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = fa.c_bpartner_id "
            + "LEFT JOIN m_product p ON p.m_product_id = fa.m_product_id "
            + "LEFT JOIN c_project pj ON pj.c_project_id = fa.c_project_id "
            + "LEFT JOIN c_costcenter cc ON cc.c_costcenter_id = fa.c_costcenter_id "
            + "WHERE fa.ad_client_id = :clientId "
            + "  AND fa.dateacct < :dateFrom "
            + "  AND fa.c_acctschema_id = :acctSchemaId "
            + "  AND ev.value IN (:accountValues) ");
    appendCommonWhere(sql, f);
    sql.append("GROUP BY ev.value, bp.name, p.name, pj.name, cc.name");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    bindCommon(query, f);
    query.setParameter(PARAM_DATE_FROM, Date.valueOf(f.dateFrom));
    query.setParameterList("accountValues", accountValues);

    List<GeneralLedgerGrouping.OpeningRow> rows = new ArrayList<>();
    for (Object[] r : query.list()) {
      rows.add(new GeneralLedgerGrouping.OpeningRow(str(r[0]), str(r[1]), str(r[2]), str(r[3]),
          str(r[4]), toBigDecimal(r[5]), toBigDecimal(r[6])));
    }
    return rows;
  }

  /**
   * Cheap count of every DISTINCT account matching the filters, ignoring {@code accountLimit} —
   * used to populate {@code meta.totalAccounts}/{@code meta.truncatedAccounts} without
   * materializing every account's lines.
   */
  @SuppressWarnings("unchecked")
  private long countTotalAccounts(LedgerFilters f) {
    StringBuilder sql = new StringBuilder(
        "SELECT COUNT(DISTINCT ev.value) FROM fact_acct fa "
            + "JOIN c_elementvalue ev ON ev.c_elementvalue_id = fa.account_id "
            + "WHERE fa.ad_client_id = :clientId "
            + "  AND fa.factaccttype NOT IN ('R', 'C') "
            + "  AND fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "  AND fa.c_acctschema_id = :acctSchemaId ");
    appendCommonWhere(sql, f);

    NativeQuery<Number> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    bindCommon(query, f);
    query.setParameter(PARAM_DATE_FROM, Date.valueOf(f.dateFrom));
    query.setParameter(PARAM_DATE_TO, Date.valueOf(f.dateTo));

    Number result = query.uniqueResult();
    return result == null ? 0L : result.longValue();
  }

  // -------------------------------------------------------------------------
  // Response building
  // -------------------------------------------------------------------------

  static JSONArray buildDataArray(List<GeneralLedgerGrouping.Group> groups,
      boolean showDimensions, boolean showOpenBalances) throws Exception {
    JSONArray data = new JSONArray();
    for (GeneralLedgerGrouping.Group g : groups) {
      JSONObject group = new JSONObject();
      if (g.dimensionValue != null) {
        group.put("dimensionValue", g.dimensionValue);
      }
      JSONArray accounts = new JSONArray();
      for (GeneralLedgerGrouping.Account a : g.accounts) {
        accounts.put(buildAccountJson(a, showDimensions, showOpenBalances));
      }
      group.put("accounts", accounts);
      data.put(group);
    }
    return data;
  }

  private static JSONObject buildAccountJson(GeneralLedgerGrouping.Account a,
      boolean showDimensions, boolean showOpenBalances) throws Exception {
    JSONObject account = new JSONObject();
    account.put("account_id", orEmpty(a.accountId));
    account.put("value", orEmpty(a.value));
    account.put("name", orEmpty(a.name));
    if (showOpenBalances) {
      account.put("opening", amountsJson(a.opening));
    }
    JSONArray lines = new JSONArray();
    for (GeneralLedgerGrouping.Line line : a.lines) {
      lines.put(buildLineJson(line, showDimensions));
    }
    account.put("lines", lines);
    // Period movements only (opening excluded), from the uncapped SQL aggregate — so it stays
    // exact when linesPerAccountLimit shortens `lines`. total = opening + subtotal.
    account.put("subtotal", amountsJson(a.subtotal));
    account.put("total", amountsJson(a.total));
    account.put("totalLines", a.totalLines);
    account.put("linesTruncated", a.linesTruncated);
    return account;
  }

  private static JSONObject buildLineJson(GeneralLedgerGrouping.Line line, boolean showDimensions)
      throws Exception {
    JSONObject l = new JSONObject();
    l.put("dateacct", line.dateacct);
    l.put("fact_acct_group_id", line.factAcctGroupId);
    l.put("groupbyname", orEmpty(line.groupbyname));
    l.put("amtacctdr", line.amtacctdr);
    l.put("amtacctcr", line.amtacctcr);
    l.put("runningBalance", line.runningBalance);
    if (showDimensions) {
      putDimensionNames(l, line);
    }
    return l;
  }

  private static JSONObject amountsJson(GeneralLedgerGrouping.Amounts amounts) throws Exception {
    JSONObject o = new JSONObject();
    o.put("amtacctdr", amounts.amtacctdr);
    o.put("amtacctcr", amounts.amtacctcr);
    o.put("total", amounts.total);
    return o;
  }

  /**
   * The response-summary fields {@link #buildMeta} needs beyond what {@link LedgerFilters} already
   * carries — kept as its own small holder (6 fields, under the java:S107 threshold on its own)
   * so {@code buildMeta} itself only takes {@code (LedgerFilters, ResponseMeta)}.
   */
  private static final class ResponseMeta {
    final int accountLimit;
    final int linesPerAccountLimit;
    final boolean truncatedAccounts;
    final long totalAccounts;
    final int accountsReturned;
    final String groupBy;

    ResponseMeta(int accountLimit, int linesPerAccountLimit, boolean truncatedAccounts,
        long totalAccounts, int accountsReturned, String groupBy) {
      this.accountLimit = accountLimit;
      this.linesPerAccountLimit = linesPerAccountLimit;
      this.truncatedAccounts = truncatedAccounts;
      this.totalAccounts = totalAccounts;
      this.accountsReturned = accountsReturned;
      this.groupBy = groupBy;
    }
  }

  private static JSONObject buildMeta(LedgerFilters f, ResponseMeta rm) throws Exception {
    JSONObject meta = new JSONObject();
    f.putScopeMeta(meta);
    meta.put(PARAM_ACCOUNT_LIMIT, rm.accountLimit);
    meta.put(PARAM_LINES_PER_ACCOUNT_LIMIT, rm.linesPerAccountLimit);
    meta.put("truncatedAccounts", rm.truncatedAccounts);
    meta.put("totalAccounts", rm.totalAccounts);
    meta.put("accountsReturned", rm.accountsReturned);
    if (rm.truncatedAccounts) {
      meta.put("hint", "Only the first " + rm.accountsReturned + " of " + rm.totalAccounts
          + " accounts were returned. Narrow fromAccountId/toAccountId, or filter by bPartner/"
          + "product/project/costCenter, to see the rest. Check each account's own "
          + "linesTruncated/totalLines too — a single very active account can be truncated "
          + "independently.");
    }
    meta.put(PARAM_GROUP_BY, rm.groupBy);
    f.putFilterMeta(meta);
    return meta;
  }
}
