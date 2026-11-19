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
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.calendar.Year;

import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * What {@link BalanceSheetReportHandler} and {@link ProfitLossReportHandler} share: both are
 * {@code C_ACCT_RPT} account-tree reports folded by {@link AccountReportTree}, with the same
 * parameters, the same validation order, the same {@code elem/rpt/roots/tree} CTE skeleton, the
 * same operands query, row mapping and response shape. Each subclass supplies only what genuinely
 * differs (see each hook's javadoc, and {@link ProfitLossReportHandler}'s class javadoc for the
 * full list of structural differences):
 * <ul>
 *   <li>{@link #reportType()} — the {@code C_ACCT_RPT.reporttype} row ({@code 'Y'} / {@code 'N'});</li>
 *   <li>{@link #amountCase} — cumulative {@code dateacct <= yearEnd} vs period {@code BETWEEN};</li>
 *   <li>{@link #factsCtes}, {@link #ownAmountColumns}, {@link #outerGroupBy} — Balance Sheet's
 *       {@code income_summary}/{@code net_income} union and outer {@code GROUP BY};</li>
 *   <li>{@link #hasLowerDateBounds()} — whether {@code dateFrom}/{@code fromReferenceDate} have any
 *       effect (and are therefore validated, bound and echoed in {@code meta}).</li>
 * </ul>
 * The SQL each subclass ends up running is byte-for-byte the text it ran before this base existed.
 *
 * <p>Org scoping is an org TREE here ({@code ad_isorgincluded}), unlike the exact-match sibling
 * reports — see {@link BalanceSheetReportHandler}'s class javadoc.
 *
 * <p>Not a bean (abstract, no {@code @Named}) — see {@link AbstractSqlReportHandler}.
 */
abstract class AbstractAccountTreeReportHandler extends AbstractSqlReportHandler {

  static final String PARAM_YEAR_ID = "yearId";
  static final String PARAM_SHOW_ONLY_WITH_VALUE = "showOnlyAccountsWithValue";
  static final String PARAM_COMPARE_TO = "compareTo";
  static final String PARAM_REFERENCE_YEAR_ID = "referenceYearId";
  static final String PARAM_FROM_REFERENCE_DATE = "fromReferenceDate";
  static final String PARAM_TO_REFERENCE_DATE = "toReferenceDate";
  static final String PARAM_BREAKDOWN = "breakdown";
  static final String BREAKDOWN_NONE = "none";
  static final String BREAKDOWN_MONTH = "month";
  static final String BREAKDOWN_QUARTER = "quarter";
  static final List<String> BREAKDOWNS = List.of(BREAKDOWN_NONE, BREAKDOWN_MONTH,
      BREAKDOWN_QUARTER);
  /** Most columns a breakdown may produce: two years of months. Each column is one more query. */
  static final int MAX_BREAKDOWN_PERIODS = 24;
  private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

  private static final String DEFAULT_ACCOUNT_LEVEL = "C";
  private static final List<String> ACCOUNT_LEVELS = List.of("C", "D", "E", "S");

  /** The {@code SUM(...)} expression both amount columns fall back to when not comparing. */
  private static final String NO_REFERENCE_AMOUNT = "0";

  // -------------------------------------------------------------------------
  // Hooks
  // -------------------------------------------------------------------------

  /** {@code C_ACCT_RPT.reporttype} of the report definition this report reads ({@code 'Y'}/{@code 'N'}). */
  abstract String reportType();

  /**
   * Whether {@code dateFrom}/{@code fromReferenceDate} narrow the query. When {@code false} they
   * are accepted but never validated, bound nor echoed (Balance Sheet's cumulative snapshot).
   *
   * <p>When {@code true} the dates alone can bound the report (ETP-5850): {@code yearId} may be
   * left out when both {@code dateFrom} and {@code dateTo} are given, and {@code breakdown} splits
   * the range into month or quarter columns. A snapshot has neither: its only bound is the year.</p>
   */
  abstract boolean hasLowerDateBounds();

  /**
   * One {@code SUM} CASE over {@code fa.amtacctdr - fa.amtacctcr} for one period: the year named by
   * the {@code yearParam} bind, narrowed by the optional {@code fromParam}/{@code toParam} binds.
   * {@code yearParam} is {@code null} when the period has no year (dates only, see
   * {@link #hasLowerDateBounds()}).
   */
  abstract String amountCase(String yearParam, boolean hasFrom, String fromParam, boolean hasTo,
      String toParam);

  /**
   * The CTEs following {@code tree}, up to and including the one named {@code facts} (each ending
   * in {@code "), "} / {@code ") "}), built from the two amount CASEs and the org filter clause.
   */
  abstract String factsCtes(String mainCase, String refCase, String orgFilter);

  /** The outer SELECT's {@code own_amt}/{@code own_amt_ref} columns, read from {@code facts f}. */
  abstract String ownAmountColumns();

  /** The outer query's {@code GROUP BY} clause (with a trailing space), or {@code ""}. */
  abstract String outerGroupBy();

  /** Hint of the {@code year_id_required} error. */
  abstract String yearIdRequiredHint();

  /** Hint of the {@code reference_year_id_required} error. */
  abstract String referenceYearIdRequiredHint();

  // -------------------------------------------------------------------------
  // Report contract
  // -------------------------------------------------------------------------

  /**
   * The shared parameter list, in declaration order; the descriptions of the parameters whose
   * meaning differs between the two reports are passed in.
   */
  static Optional<List<NeoReportParam>> accountTreeParameters(String yearIdDescription,
      String dateFromDescription, String dateToDescription, String compareToDescription,
      String referenceYearIdDescription, String fromReferenceDateDescription,
      String toReferenceDateDescription) {
    return Optional.of(List.of(
        NeoReportParam.required(PARAM_YEAR_ID, NeoReportParam.TYPE_STRING, yearIdDescription),
        NeoReportParam.optional(PARAM_ACCT_SCHEMA_ID, NeoReportParam.TYPE_STRING,
            "Accounting schema (C_AcctSchema) id whose chart-of-accounts report definition and "
                + "postings are used. Default: the organization's general ledger."),
        NeoReportParam.optional(PARAM_ORG_ID, NeoReportParam.TYPE_STRING,
            "Organization id to report on. Filters that organization AND every descendant in its "
                + "org tree (unlike the exact-match orgId of generate_report_trial_balance / "
                + "generate_report_journal_entries). Default: the session's current organization. "
                + "Pass an empty string to report on every organization of the client instead."),
        NeoReportParam.optional(PARAM_DATE_FROM, NeoReportParam.TYPE_DATE, dateFromDescription),
        NeoReportParam.optional(PARAM_DATE_TO, NeoReportParam.TYPE_DATE, dateToDescription),
        NeoReportParam.options(PARAM_ACCOUNT_LEVEL,
            "Chart-of-accounts depth CUTOFF (not an equality filter): everything from each "
                + "report root down to and including this level is shown. 'E' Heading (coarsest), "
                + "'C' Account (default), 'D' Breakdown, 'S' Subaccount (finest).",
            ACCOUNT_LEVELS),
        NeoReportParam.optional(PARAM_SHOW_ONLY_WITH_VALUE, NeoReportParam.TYPE_BOOLEAN,
            "Whether to drop a row whose amount (and reference amount, when comparing) are both "
                + "zero and which is not marked 'always shown' in the report definition. "
                + "Default: true."),
        NeoReportParam.optional(PARAM_COMPARE_TO, NeoReportParam.TYPE_BOOLEAN,
            compareToDescription),
        NeoReportParam.optional(PARAM_REFERENCE_YEAR_ID, NeoReportParam.TYPE_STRING,
            referenceYearIdDescription),
        NeoReportParam.optional(PARAM_FROM_REFERENCE_DATE, NeoReportParam.TYPE_DATE,
            fromReferenceDateDescription),
        NeoReportParam.optional(PARAM_TO_REFERENCE_DATE, NeoReportParam.TYPE_DATE,
            toReferenceDateDescription)));
  }

  // -------------------------------------------------------------------------
  // POST — execute
  // -------------------------------------------------------------------------

  @Override
  final NeoResponse executeReport(JSONObject body) throws Exception {
    // Pure input checks run before anything touches OBContext or the database, so a malformed
    // request is refused without a single query.
    String yearId = body.optString(PARAM_YEAR_ID, "");
    NeoResponse yearIdError = validateYearId(yearId, body);
    if (yearIdError != null) {
      return yearIdError;
    }

    String accountLevel = body.optString(PARAM_ACCOUNT_LEVEL, DEFAULT_ACCOUNT_LEVEL);
    NeoResponse accountLevelError = validateAccountLevel(accountLevel);
    if (accountLevelError != null) {
      return accountLevelError;
    }

    NeoResponse periodError = validateDateBounds(body, PARAM_DATE_FROM, PARAM_DATE_TO);
    if (periodError != null) {
      return periodError;
    }

    boolean compareTo = body.optBoolean(PARAM_COMPARE_TO, false);
    String referenceYearId = body.optString(PARAM_REFERENCE_YEAR_ID, "");
    NeoResponse compareToError = validateCompareTo(compareTo, referenceYearId);
    if (compareToError != null) {
      return compareToError;
    }

    NeoResponse referenceError = validateDateBounds(body, PARAM_FROM_REFERENCE_DATE,
        PARAM_TO_REFERENCE_DATE);
    if (referenceError != null) {
      return referenceError;
    }

    String breakdown = hasLowerDateBounds() ? body.optString(PARAM_BREAKDOWN, BREAKDOWN_NONE)
        : BREAKDOWN_NONE;
    NeoResponse breakdownError = validateBreakdown(breakdown, compareTo);
    if (breakdownError != null) {
      return breakdownError;
    }

    boolean showOnlyWithValue = body.optBoolean(PARAM_SHOW_ONLY_WITH_VALUE, true);

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

    NeoResponse yearsResolvedError = validateYearsResolved(yearId, compareTo, referenceYearId);
    if (yearsResolvedError != null) {
      return yearsResolvedError;
    }

    String clientId = OBContext.getOBContext().getCurrentClient().getId();

    TreeQuery query = new TreeQuery(clientId, orgId, acctSchemaId,
        period(body, PARAM_YEAR_ID, yearId, PARAM_DATE_FROM, PARAM_DATE_TO), compareTo,
        period(body, PARAM_REFERENCE_YEAR_ID, referenceYearId, PARAM_FROM_REFERENCE_DATE,
            PARAM_TO_REFERENCE_DATE));

    List<AccountReportTree.NodeRow> nodeRows = queryNodeRows(query);
    List<AccountReportTree.OperandRow> operandRows = queryOperandRows(clientId, acctSchemaId);

    List<AccountReportTree.OutputRow> tree = AccountReportTree.build(nodeRows, operandRows,
        accountLevel, showOnlyWithValue);

    JSONArray data = buildDataArray(tree, compareTo);
    JSONObject meta = buildMeta(query, accountLevel, showOnlyWithValue);
    if (!BREAKDOWN_NONE.equals(breakdown)) {
      NeoResponse breakdownRangeError = addBreakdown(query, breakdown, accountLevel, operandRows,
          tree, data, meta);
      if (breakdownRangeError != null) {
        return breakdownRangeError;
      }
    }
    return reportResponse(data, data.length(), meta);
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  /**
   * Validates one period's optional date bounds, lower first — the lower one only when {@link
   * #hasLowerDateBounds()}, since otherwise it has no effect at all.
   */
  private NeoResponse validateDateBounds(JSONObject body, String fromParam, String toParam) {
    if (hasLowerDateBounds()) {
      NeoResponse fromError = validateOptionalDate(body, fromParam);
      if (fromError != null) {
        return fromError;
      }
    }
    return validateOptionalDate(body, toParam);
  }

  private static NeoResponse validateOptionalDate(JSONObject body, String paramName) {
    String raw = body.optString(paramName, "");
    if (raw.isEmpty()) {
      return null;
    }
    try {
      LocalDate.parse(raw, DATE_FORMATTER);
    } catch (DateTimeException e) {
      return actionableError(400, paramName + "_invalid",
          paramName + " '" + raw + "' is not a valid yyyy-MM-dd date.",
          "Pass " + paramName + " as yyyy-MM-dd, e.g. \"2026-09-24\", or omit it.");
    }
    return null;
  }

  private static LocalDate parseOptionalDate(JSONObject body, String paramName) {
    String raw = body.optString(paramName, "");
    return raw.isEmpty() ? null : LocalDate.parse(raw, DATE_FORMATTER);
  }

  /**
   * {@code yearId} is required, except where the dates alone can bound the report (see
   * {@link #hasLowerDateBounds()}) and both {@code dateFrom} and {@code dateTo} are given: an agent
   * asking for a quarter then needs no year id, which it often cannot tell apart (a tenant can
   * have two years with the same name, one per calendar).
   */
  private NeoResponse validateYearId(String yearId, JSONObject body) {
    if (yearId.isEmpty()) {
      if (hasLowerDateBounds() && !body.optString(PARAM_DATE_FROM, "").isEmpty()
          && !body.optString(PARAM_DATE_TO, "").isEmpty()) {
        return null;
      }
      return actionableError(400, "year_id_required",
          "yearId is required.",
          yearIdRequiredHint());
    }
    if (!isValidId(yearId)) {
      return actionableError(400, "year_id_invalid",
          "yearId '" + yearId + "' does not look like a valid Etendo year id.",
          "Pass a real C_Year id.");
    }
    return null;
  }

  private static NeoResponse validateBreakdown(String breakdown, boolean compareTo) {
    if (!BREAKDOWNS.contains(breakdown)) {
      return actionableError(400, "breakdown_invalid",
          "breakdown '" + breakdown + "' is not one of none, month, quarter.",
          "Use none (one total column), month or quarter.");
    }
    if (compareTo && !BREAKDOWN_NONE.equals(breakdown)) {
      return actionableError(400, "breakdown_with_compare",
          "breakdown cannot be combined with compareTo.",
          "Ask for the breakdown and the comparison in two calls.");
    }
    return null;
  }

  private static NeoResponse validateAccountLevel(String accountLevel) {
    if (!ACCOUNT_LEVELS.contains(accountLevel)) {
      return actionableError(400, "account_level_invalid",
          "accountLevel '" + accountLevel + "' is not one of C, D, E, S.",
          "Use one of: E (Heading), C (Account), D (Breakdown), S (Subaccount).");
    }
    return null;
  }

  private NeoResponse validateCompareTo(boolean compareTo, String referenceYearId) {
    if (!compareTo) {
      return null;
    }
    if (referenceYearId.isEmpty()) {
      return actionableError(400, "reference_year_id_required",
          "referenceYearId is required when compareTo is true.",
          referenceYearIdRequiredHint());
    }
    if (!isValidId(referenceYearId)) {
      return actionableError(400, "reference_year_id_invalid",
          "referenceYearId '" + referenceYearId + "' does not look like a valid Etendo year id.",
          "Pass a real C_Year id.");
    }
    return null;
  }

  private static NeoResponse validateYearsResolved(String yearId, boolean compareTo,
      String referenceYearId) {
    // ETP-5793: another tenant's year answers like an unknown one. No year: dates only.
    if (!yearId.isEmpty() && TenantOwnership.loadOwned(Year.class, yearId) == null) {
      return actionableError(422, "year_not_resolved",
          "Could not resolve year " + yearId + ".",
          "Pass a valid C_Year id readable by your role.");
    }
    if (compareTo && TenantOwnership.loadOwned(Year.class, referenceYearId) == null) {
      return actionableError(422, "reference_year_not_resolved",
          "Could not resolve comparison year " + referenceYearId + ".",
          "Pass a valid C_Year id readable by your role, or set compareTo to false.");
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Resolved request
  // -------------------------------------------------------------------------

  /**
   * One period of the report — the main one or the comparison one: the fiscal year plus its
   * optional narrowing bounds, together with the bind names each one is bound under.
   */
  static final class Period {
    final String yearParam;
    final String fromParam;
    final String toParam;
    final String yearId;
    final LocalDate from;
    final LocalDate to;

    private Period(String yearParam, String fromParam, String toParam, String yearId,
        LocalDate from, LocalDate to) {
      this.yearParam = yearParam;
      this.fromParam = fromParam;
      this.toParam = toParam;
      this.yearId = yearId;
      this.from = from;
      this.to = to;
    }
  }

  /**
   * A period read from {@code body}; its lower bound is {@code null} unless {@link #hasLowerDateBounds()}.
   * Without a year id the period has no year bind ({@code yearParam} null): its dates bound it.
   */
  private Period period(JSONObject body, String yearParam, String yearId, String fromParam,
      String toParam) {
    LocalDate from = hasLowerDateBounds() ? parseOptionalDate(body, fromParam) : null;
    return new Period(yearId.isEmpty() ? null : yearParam, fromParam, toParam, yearId, from,
        parseOptionalDate(body, toParam));
  }

  /** The resolved request the node query is built and bound from. Built after all validation passed. */
  static final class TreeQuery {
    final String clientId;
    final String orgId;
    final String acctSchemaId;
    final Period period;
    final boolean compareTo;
    final Period reference;

    TreeQuery(String clientId, String orgId, String acctSchemaId, Period period,
        boolean compareTo, Period reference) {
      this.clientId = clientId;
      this.orgId = orgId;
      this.acctSchemaId = acctSchemaId;
      this.period = period;
      this.compareTo = compareTo;
      this.reference = reference;
    }
  }

  // -------------------------------------------------------------------------
  // SQL
  // -------------------------------------------------------------------------

  private String amountCase(Period p) {
    return amountCase(p.yearParam, p.from != null, p.fromParam, p.to != null, p.toParam);
  }

  /**
   * Builds the node-tree query text. Faithful to each contract's {@code sql.query} except: (1)
   * every value is a real bind parameter, never string-concatenated; (2) the {@code ('__X__' = ''
   * OR ...)} optional-filter pattern is replaced with conditional SQL construction (see {@link
   * #amountCase}); (3) the org filter keeps the contract's own {@code ad_isorgincluded} org-TREE
   * semantics, only omitted entirely when {@code orgId} is blank.
   */
  private String buildNodeSql(TreeQuery q) {
    String orgFilter = q.orgId.isEmpty() ? "" :
        "    AND ad_isorgincluded(fa.ad_org_id, :orgId, fa.ad_client_id) <> -1 ";

    String mainCase = amountCase(q.period);
    String refCase = q.compareTo ? amountCase(q.reference) : NO_REFERENCE_AMOUNT;

    return "WITH RECURSIVE elem AS ( "
        + "  SELECT e.c_element_id, e.ad_tree_id FROM c_acctschema_element ase "
        + "  JOIN c_element e ON e.c_element_id = ase.c_element_id "
        + "  WHERE ase.c_acctschema_id = :acctSchemaId AND ase.elementtype = 'AC' LIMIT 1 "
        + "), "
        + "rpt AS ( "
        + "  SELECT r.c_acct_rpt_id FROM c_acct_rpt r "
        + "  WHERE r.ad_client_id = :clientId AND r.isactive = 'Y' AND r.reporttype = '"
        + reportType() + "' "
        + "    AND r.c_acctschema_id = :acctSchemaId LIMIT 1 "
        + "), "
        + "roots AS ( "
        + "  SELECT n.c_elementvalue_id AS node_id, g.line AS g_line, n.line AS n_line, "
        + "    g.name AS group_name "
        + "  FROM c_acct_rpt_node n JOIN c_acct_rpt_group g "
        + "    ON g.c_acct_rpt_group_id = n.c_acct_rpt_group_id "
        + "  WHERE g.c_acct_rpt_id = (SELECT c_acct_rpt_id FROM rpt) AND n.isactive = 'Y' "
        + "    AND g.isactive = 'Y' "
        + "), "
        + "tree AS ( "
        // CAST(... AS ...) instead of Postgres' `::` shorthand: Hibernate's native-query parser
        // reads `:` as a named-parameter marker and mangles `::type`, which fails at runtime
        // with "syntax error at or near ':'" (never caught by psql-based parity checks or by
        // tests that mock NativeQuery).
        + "  SELECT r.node_id, CAST(NULL AS varchar) AS parent_id, 0 AS depth, r.group_name, "
        + "    lpad(CAST(r.g_line AS text), 6, '0') || '.' || lpad(CAST(r.n_line AS text), 6, '0') "
        + "      AS sort_path "
        + "  FROM roots r "
        + "  UNION ALL "
        + "  SELECT tn.node_id, tn.parent_id, t.depth + 1, t.group_name, "
        + "    t.sort_path || '.' || lpad(CAST(tn.seqno AS text), 6, '0') "
        + "  FROM ad_treenode tn JOIN tree t ON tn.parent_id = t.node_id "
        + "  WHERE tn.ad_tree_id = (SELECT ad_tree_id FROM elem) AND tn.isactive = 'Y' "
        + "    AND t.depth < 12 "
        + "), "
        + factsCtes(mainCase, refCase, orgFilter)
        + "SELECT t.node_id, COALESCE(t.parent_id, '') AS parent_id, t.depth, t.sort_path, "
        + "  t.group_name, ev.value, ev.name, ev.elementlevel, ev.isalwaysshown, "
        + "  ev.accountsign, " + ownAmountColumns()
        + "FROM tree t JOIN c_elementvalue ev ON ev.c_elementvalue_id = t.node_id "
        + "LEFT JOIN facts f ON f.account_id = t.node_id "
        + "WHERE ev.c_element_id = (SELECT c_element_id FROM elem) AND ev.isactive = 'Y' "
        + outerGroupBy()
        + "ORDER BY t.sort_path";
  }

  @SuppressWarnings("unchecked")
  private List<Object[]> queryRawNodeRows(TreeQuery q) {
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(buildNodeSql(q));
    query.setParameter(SQL_PARAM_CLIENT_ID, q.clientId);
    query.setParameter(PARAM_ACCT_SCHEMA_ID, q.acctSchemaId);
    bindPeriod(query, q.period);
    if (!q.orgId.isEmpty()) {
      query.setParameter(PARAM_ORG_ID, q.orgId);
    }
    if (q.compareTo) {
      bindPeriod(query, q.reference);
    }
    return query.list();
  }

  private static void bindPeriod(NativeQuery<?> query, Period p) {
    if (p.yearParam != null) {
      query.setParameter(p.yearParam, p.yearId);
    }
    if (p.from != null) {
      query.setParameter(p.fromParam, java.sql.Date.valueOf(p.from));
    }
    if (p.to != null) {
      query.setParameter(p.toParam, java.sql.Date.valueOf(p.to));
    }
  }

  /**
   * Runs {@code sql.operandsQuery} with real bind parameters — the formula edges
   * ({@code C_ELEMENTVALUE_OPERAND}) {@link AccountReportTree} needs to resolve computed total
   * rows. Scoped to client + acctSchema only, matching the contract (formula edges are not
   * org/date-scoped).
   */
  @SuppressWarnings("unchecked")
  private List<Object[]> queryRawOperandRows(String clientId, String acctSchemaId) {
    String sql =
        "SELECT o.c_elementvalue_id AS owner_id, o.account_id AS operand_id, o.sign, o.seqno "
            + "FROM c_elementvalue_operand o "
            + "WHERE o.isactive = 'Y' AND o.ad_client_id = :clientId "
            + "  AND o.c_elementvalue_id IN ( "
            + "    SELECT ev.c_elementvalue_id FROM c_elementvalue ev "
            + "    WHERE ev.c_element_id = ( "
            + "      SELECT e.c_element_id FROM c_acctschema_element ase "
            + "      JOIN c_element e ON e.c_element_id = ase.c_element_id "
            + "      WHERE ase.c_acctschema_id = :acctSchemaId AND ase.elementtype = 'AC' LIMIT 1)) "
            + "ORDER BY o.c_elementvalue_id, o.seqno";

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(SQL_PARAM_CLIENT_ID, clientId);
    query.setParameter(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
    return query.list();
  }

  // -------------------------------------------------------------------------
  // Row mapping / response building
  // -------------------------------------------------------------------------

  private static final int COL_NODE_ID = 0;
  private static final int COL_PARENT_ID = 1;
  private static final int COL_SORT_PATH = 3;
  private static final int COL_GROUP_NAME = 4;
  private static final int COL_VALUE = 5;
  private static final int COL_NAME = 6;
  private static final int COL_ELEMENT_LEVEL = 7;
  private static final int COL_IS_ALWAYS_SHOWN = 8;
  private static final int COL_ACCOUNT_SIGN = 9;
  private static final int COL_OWN_AMT = 10;
  private static final int COL_OWN_AMT_REF = 11;
  // Appended AFTER own_amt_ref by ownAmountColumns() (ETP-5662) so the positions above never shift.
  private static final int COL_SHOW_VALUE_COND = 12;
  private static final int COL_IS_SUMMARY = 13;

  private List<AccountReportTree.NodeRow> queryNodeRows(TreeQuery q) {
    List<Object[]> rawRows = queryRawNodeRows(q);
    List<AccountReportTree.NodeRow> rows = new ArrayList<>();
    for (Object[] r : rawRows) {
      rows.add(AccountReportTree.NodeRow.builder()
          .nodeId(str(r[COL_NODE_ID]))
          .parentId(str(r[COL_PARENT_ID]))
          .sortPath(str(r[COL_SORT_PATH]))
          .groupName(str(r[COL_GROUP_NAME]))
          .value(str(r[COL_VALUE]))
          .name(str(r[COL_NAME]))
          .elementLevel(str(r[COL_ELEMENT_LEVEL]))
          .alwaysShown(toBoolean(r[COL_IS_ALWAYS_SHOWN]))
          .accountSign(str(r[COL_ACCOUNT_SIGN]))
          .ownAmt(toBigDecimal(r[COL_OWN_AMT]))
          .ownAmtRef(toBigDecimal(r[COL_OWN_AMT_REF]))
          .showValueCond(str(r[COL_SHOW_VALUE_COND]))
          .summary("Y".equals(str(r[COL_IS_SUMMARY])))
          .build());
    }
    return rows;
  }

  private List<AccountReportTree.OperandRow> queryOperandRows(String clientId, String acctSchemaId) {
    List<Object[]> rawRows = queryRawOperandRows(clientId, acctSchemaId);
    List<AccountReportTree.OperandRow> rows = new ArrayList<>();
    for (Object[] r : rawRows) {
      rows.add(new AccountReportTree.OperandRow(str(r[0]), str(r[1]), toInt(r[2])));
    }
    return rows;
  }

  static JSONArray buildDataArray(List<AccountReportTree.OutputRow> tree, boolean compareTo)
      throws JSONException {
    JSONArray data = new JSONArray();
    for (AccountReportTree.OutputRow row : tree) {
      JSONObject r = new JSONObject();
      r.put("node_id", row.nodeId);
      r.put("value", row.value);
      r.put("name", row.name);
      r.put("element", row.element);
      r.put("elementLevel", row.elementLevel);
      r.put("level", row.indent);
      r.put("amount", row.amount);
      if (compareTo) {
        r.put("amount_ref", row.amountRef);
      }
      r.put("isHeading", row.isHeading);
      r.put("isFormula", row.isFormula);
      r.put("group", row.group == null ? "" : row.group);
      r.put("isGroupStart", row.isGroupStart);
      data.put(r);
    }
    return data;
  }

  private JSONObject buildMeta(TreeQuery q, String accountLevel, boolean showOnlyWithValue)
      throws JSONException {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_YEAR_ID, q.period.yearId);
    meta.put(PARAM_ORG_ID, q.orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, q.acctSchemaId);
    meta.put(PARAM_ACCOUNT_LEVEL, accountLevel);
    meta.put(PARAM_SHOW_ONLY_WITH_VALUE, showOnlyWithValue);
    if (hasLowerDateBounds()) {
      meta.put(PARAM_DATE_FROM, formatOrEmpty(q.period.from));
    }
    meta.put(PARAM_DATE_TO, formatOrEmpty(q.period.to));
    meta.put(PARAM_COMPARE_TO, q.compareTo);
    meta.put(PARAM_REFERENCE_YEAR_ID, q.compareTo ? q.reference.yearId : "");
    if (hasLowerDateBounds()) {
      meta.put(PARAM_FROM_REFERENCE_DATE, q.compareTo ? formatOrEmpty(q.reference.from) : "");
    }
    meta.put(PARAM_TO_REFERENCE_DATE, q.compareTo ? formatOrEmpty(q.reference.to) : "");
    return meta;
  }

  private static String formatOrEmpty(LocalDate date) {
    return date == null ? "" : date.format(DATE_FORMATTER);
  }

  // -------------------------------------------------------------------------
  // Breakdown (ETP-5850)
  // -------------------------------------------------------------------------

  /**
   * Adds one amount per month or quarter to every row: {@code byPeriod} maps each column's key to
   * the row's amount over that slice of the range, and {@code meta.periods} lists the columns in
   * order ({@code key}, {@code dateFrom}, {@code dateTo}). The range is the request's dates, a
   * missing one taken from the year's own period bounds. Each slice is the same report run over
   * its dates, with every row kept, so a row of the total always has a value in every column.
   *
   * @return a 400 when the range is empty, inverted or longer than {@link #MAX_BREAKDOWN_PERIODS}
   *     slices; {@code null} once {@code data} and {@code meta} carry the breakdown
   */
  private NeoResponse addBreakdown(TreeQuery q, String breakdown, String accountLevel,
      List<AccountReportTree.OperandRow> operandRows, List<AccountReportTree.OutputRow> tree,
      JSONArray data, JSONObject meta) throws JSONException {
    LocalDate[] yearBounds = q.period.from == null || q.period.to == null
        ? queryYearBounds(q.period.yearId) : new LocalDate[2];
    LocalDate from = q.period.from != null ? q.period.from : yearBounds[0];
    LocalDate to = q.period.to != null ? q.period.to : yearBounds[1];
    if (from == null || to == null || from.isAfter(to)) {
      return actionableError(400, "breakdown_range_invalid",
          "The report range " + formatOrEmpty(from) + " to " + formatOrEmpty(to)
              + " cannot be split into periods.",
          "Pass dateFrom on or before dateTo, or a yearId whose periods exist.");
    }
    List<LocalDate[]> slices = slices(from, to, BREAKDOWN_MONTH.equals(breakdown) ? 1 : 3);
    if (slices.size() > MAX_BREAKDOWN_PERIODS) {
      return actionableError(400, "breakdown_too_many_periods",
          "A " + breakdown + " breakdown of " + formatOrEmpty(from) + " to " + formatOrEmpty(to)
              + " has " + slices.size() + " periods; at most " + MAX_BREAKDOWN_PERIODS
              + " are allowed.",
          "Narrow dateFrom/dateTo, or use quarter instead of month.");
    }

    JSONArray periods = new JSONArray();
    List<Map<String, BigDecimal>> amounts = new ArrayList<>();
    for (LocalDate[] slice : slices) {
      periods.put(new JSONObject().put("key", sliceKey(slice))
          .put(PARAM_DATE_FROM, formatOrEmpty(slice[0]))
          .put(PARAM_DATE_TO, formatOrEmpty(slice[1])));
      TreeQuery sliceQuery = new TreeQuery(q.clientId, q.orgId, q.acctSchemaId,
          new Period(null, PARAM_DATE_FROM, PARAM_DATE_TO, "", slice[0], slice[1]), false,
          q.reference);
      Map<String, BigDecimal> byRow = new HashMap<>();
      for (AccountReportTree.OutputRow row : AccountReportTree.build(queryNodeRows(sliceQuery),
          operandRows, accountLevel, false)) {
        byRow.put(rowKey(row), row.amount);
      }
      amounts.add(byRow);
    }

    for (int i = 0; i < tree.size(); i++) {
      JSONObject byPeriod = new JSONObject();
      String key = rowKey(tree.get(i));
      for (int s = 0; s < slices.size(); s++) {
        byPeriod.put(periods.getJSONObject(s).getString("key"),
            amounts.get(s).getOrDefault(key, BigDecimal.ZERO));
      }
      data.getJSONObject(i).put("byPeriod", byPeriod);
    }
    meta.put(PARAM_BREAKDOWN, breakdown);
    meta.put("periods", periods);
    return null;
  }

  /**
   * Splits {@code from..to} (both inclusive) into consecutive slices of {@code months} calendar
   * months, counted from {@code from}'s month: a quarter breakdown of a July-June year gives
   * July-September first. The first and last slices are clipped to the range.
   */
  static List<LocalDate[]> slices(LocalDate from, LocalDate to, int months) {
    List<LocalDate[]> slices = new ArrayList<>();
    LocalDate blockStart = from.withDayOfMonth(1);
    while (!blockStart.isAfter(to)) {
      LocalDate blockEnd = blockStart.plusMonths(months).minusDays(1);
      slices.add(new LocalDate[] { blockStart.isBefore(from) ? from : blockStart,
          blockEnd.isAfter(to) ? to : blockEnd });
      blockStart = blockStart.plusMonths(months);
    }
    return slices;
  }

  /** {@code 2026-07} for a slice within one month, {@code 2026-07/2026-09} otherwise. */
  static String sliceKey(LocalDate[] slice) {
    String start = slice[0].format(MONTH_FORMATTER);
    String end = slice[1].format(MONTH_FORMATTER);
    return start.equals(end) ? start : start + "/" + end;
  }

  private static String rowKey(AccountReportTree.OutputRow row) {
    return row.nodeId + '\u0000' + (row.group == null ? "" : row.group) + '\u0000'
        + row.isFormula;
  }

  /** The year's first period start and last period end; {@code null}s when it has no periods. */
  private static LocalDate[] queryYearBounds(String yearId) {
    Object[] bounds = (Object[]) OBDal.getInstance().getSession()
        .createNativeQuery("SELECT MIN(p.startdate), MAX(p.enddate) FROM c_period p "
            + "WHERE p.c_year_id = :yearId")
        .setParameter(PARAM_YEAR_ID, yearId)
        .uniqueResult();
    return new LocalDate[] { toLocalDate(bounds == null ? null : bounds[0]),
        toLocalDate(bounds == null ? null : bounds[1]) };
  }

  private static LocalDate toLocalDate(Object value) {
    String iso = toIsoDate(value);
    return iso.isEmpty() ? null : LocalDate.parse(iso, DATE_FORMATTER);
  }
}
