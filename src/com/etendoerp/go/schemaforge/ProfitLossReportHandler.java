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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Year;

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
public class ProfitLossReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProfitLossReportHandler.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);
  /** Etendo ids are either a legacy numeric string or a 32-char hex/alnum id — never blank, never whitespace. */
  private static final Pattern ID_SHAPE = Pattern.compile("^[0-9A-Za-z]{1,32}$");

  private static final String PARAM_ACCT_SCHEMA_ID = "acctSchemaId";
  private static final String PARAM_ORG_ID = "orgId";
  private static final String PARAM_YEAR_ID = "yearId";
  private static final String PARAM_DATE_FROM = "dateFrom";
  private static final String PARAM_DATE_TO = "dateTo";
  private static final String PARAM_ACCOUNT_LEVEL = "accountLevel";
  private static final String PARAM_SHOW_ONLY_WITH_VALUE = "showOnlyAccountsWithValue";
  private static final String PARAM_COMPARE_TO = "compareTo";
  private static final String PARAM_REFERENCE_YEAR_ID = "referenceYearId";
  private static final String PARAM_FROM_REFERENCE_DATE = "fromReferenceDate";
  private static final String PARAM_TO_REFERENCE_DATE = "toReferenceDate";

  private static final String DEFAULT_ACCOUNT_LEVEL = "C";
  private static final List<String> ACCOUNT_LEVELS = List.of("C", "D", "E", "S");

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
    return Optional.of(List.of(
        NeoReportParam.required(PARAM_YEAR_ID, NeoReportParam.TYPE_STRING,
            "C_Year id whose fiscal period range bounds the report — Profit & Loss is a "
                + "PERIOD-ACTIVITY total of postings within that year (further narrowed by "
                + "dateFrom/dateTo, if given), unlike Balance Sheet's cumulative snapshot."),
        NeoReportParam.optional(PARAM_ACCT_SCHEMA_ID, NeoReportParam.TYPE_STRING,
            "Accounting schema (C_AcctSchema) id whose chart-of-accounts report definition and "
                + "postings are used. Default: the organization's general ledger."),
        NeoReportParam.optional(PARAM_ORG_ID, NeoReportParam.TYPE_STRING,
            "Organization id to report on. Filters that organization AND every descendant in its "
                + "org tree (unlike the exact-match orgId of generate_report_trial_balance / "
                + "generate_report_journal_entries). Default: the session's current organization. "
                + "Pass an empty string to report on every organization of the client instead."),
        NeoReportParam.optional(PARAM_DATE_FROM, NeoReportParam.TYPE_DATE,
            "Lower bound (inclusive) on the posting date, narrowing yearId's own fiscal "
                + "year-start bound. Default: no narrowing (the full fiscal year from yearId's "
                + "first period start)."),
        NeoReportParam.optional(PARAM_DATE_TO, NeoReportParam.TYPE_DATE,
            "Upper bound (inclusive) on the posting date, narrowing yearId's own fiscal "
                + "year-end bound. Default: no narrowing (the full fiscal year through yearId's "
                + "last period end)."),
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
            "Whether to also compute each row's amount as of a second, comparison period "
                + "(referenceYearId/fromReferenceDate/toReferenceDate). Default: false. When "
                + "true, referenceYearId is required."),
        NeoReportParam.optional(PARAM_REFERENCE_YEAR_ID, NeoReportParam.TYPE_STRING,
            "C_Year id for the comparison period. Required when compareTo is true; ignored "
                + "otherwise."),
        NeoReportParam.optional(PARAM_FROM_REFERENCE_DATE, NeoReportParam.TYPE_DATE,
            "Lower bound (inclusive) on the comparison posting date, narrowing "
                + "referenceYearId's own fiscal year-start bound. Only meaningful when compareTo "
                + "is true."),
        NeoReportParam.optional(PARAM_TO_REFERENCE_DATE, NeoReportParam.TYPE_DATE,
            "Upper bound (inclusive) on the comparison posting date, narrowing "
                + "referenceYearId's own fiscal year-end bound. Only meaningful when compareTo "
                + "is true.")));
  }

  // -------------------------------------------------------------------------
  // NeoHandler entry point
  // -------------------------------------------------------------------------

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!isAccessibleForCurrentRole()) {
      return NeoResponse.error(403, "Access denied");
    }
    if ("GET".equals(context.getHttpMethod())) {
      return describeReport();
    }
    if ("POST".equals(context.getHttpMethod())) {
      return executeReport(context);
    }
    return NeoResponse.error(405, "Method not allowed");
  }

  private NeoResponse describeReport() {
    try {
      JSONObject desc = new JSONObject();
      desc.put("name", "Profit & Loss");
      desc.put("description", "Pérdidas y Ganancias — Revenue vs Expenses, as an indented "
          + "account tree with roll-up and formula (computed total) rows, for a fiscal-year "
          + "period of activity.");

      JSONArray params = new JSONArray();
      for (NeoReportParam declared : reportParameters().orElse(List.of())) {
        params.put(param(declared));
      }
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building profit and loss report descriptor", e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  private static JSONObject param(NeoReportParam declared) throws Exception {
    JSONObject p = new JSONObject();
    p.put("name", declared.getName());
    p.put("type", declared.getType());
    p.put("required", declared.isRequired());
    p.put("description", declared.getDescription());
    if (!declared.getAllowedValues().isEmpty()) {
      p.put("allowedValues", new JSONArray(declared.getAllowedValues()));
    }
    return p;
  }

  // -------------------------------------------------------------------------
  // POST — execute
  // -------------------------------------------------------------------------

  private NeoResponse executeReport(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody() == null ? new JSONObject() : context.getRequestBody();

      // Pure input checks run before anything touches OBContext or the database, so a malformed
      // request is refused without a single query.
      String yearId = body.optString(PARAM_YEAR_ID, "");
      if (yearId.isEmpty()) {
        return actionableError(400, "year_id_required",
            "yearId is required.",
            "Pass the C_Year id whose fiscal period range the P&L should be reported over.");
      }
      if (!isValidId(yearId)) {
        return actionableError(400, "year_id_invalid",
            "yearId '" + yearId + "' does not look like a valid Etendo year id.",
            "Pass a real C_Year id.");
      }

      String accountLevel = body.optString(PARAM_ACCOUNT_LEVEL, DEFAULT_ACCOUNT_LEVEL);
      if (!ACCOUNT_LEVELS.contains(accountLevel)) {
        return actionableError(400, "account_level_invalid",
            "accountLevel '" + accountLevel + "' is not one of C, D, E, S.",
            "Use one of: E (Heading), C (Account), D (Breakdown), S (Subaccount).");
      }

      NeoResponse dateFromError = validateOptionalDate(body, PARAM_DATE_FROM);
      if (dateFromError != null) {
        return dateFromError;
      }
      LocalDate dateFrom = parseOptionalDate(body, PARAM_DATE_FROM);

      NeoResponse dateToError = validateOptionalDate(body, PARAM_DATE_TO);
      if (dateToError != null) {
        return dateToError;
      }
      LocalDate dateTo = parseOptionalDate(body, PARAM_DATE_TO);

      boolean compareTo = body.optBoolean(PARAM_COMPARE_TO, false);
      String referenceYearId = body.optString(PARAM_REFERENCE_YEAR_ID, "");
      if (compareTo) {
        if (referenceYearId.isEmpty()) {
          return actionableError(400, "reference_year_id_required",
              "referenceYearId is required when compareTo is true.",
              "Pass the C_Year id for the comparison period, or set compareTo to false.");
        }
        if (!isValidId(referenceYearId)) {
          return actionableError(400, "reference_year_id_invalid",
              "referenceYearId '" + referenceYearId + "' does not look like a valid Etendo year id.",
              "Pass a real C_Year id.");
        }
      }

      NeoResponse fromReferenceDateError = validateOptionalDate(body, PARAM_FROM_REFERENCE_DATE);
      if (fromReferenceDateError != null) {
        return fromReferenceDateError;
      }
      LocalDate fromReferenceDate = parseOptionalDate(body, PARAM_FROM_REFERENCE_DATE);

      NeoResponse toReferenceDateError = validateOptionalDate(body, PARAM_TO_REFERENCE_DATE);
      if (toReferenceDateError != null) {
        return toReferenceDateError;
      }
      LocalDate toReferenceDate = parseOptionalDate(body, PARAM_TO_REFERENCE_DATE);

      boolean showOnlyWithValue = body.optBoolean(PARAM_SHOW_ONLY_WITH_VALUE, true);

      String orgId = resolveOrgId(body);
      if (!orgId.isEmpty() && !isValidId(orgId)) {
        return actionableError(400, "org_id_invalid",
            "orgId '" + orgId + "' does not look like a valid Etendo organization id.",
            "Pass a real C_Organization id, or omit orgId to use the session's organization, or "
                + "pass an empty string to report on every organization.");
      }
      if (!orgId.isEmpty() && OBDal.getInstance().get(Organization.class, orgId) == null) {
        return actionableError(400, "organization_not_resolved",
            "Could not resolve organization " + orgId + " for the profit and loss report.",
            "Pass a valid orgId (a C_Organization id readable by your role), an empty string for "
                + "every organization, or omit it to use the session's current organization.");
      }

      String acctSchemaId = resolveAcctSchemaId(body, orgId);
      if (acctSchemaId == null || acctSchemaId.isEmpty()) {
        return actionableError(422, "accounting_schema_unresolved",
            "Could not resolve an accounting schema for organization "
                + (orgId.isEmpty() ? "(none — client-wide)" : orgId) + ".",
            "Check that the organization (or an ancestor in its tree) has a general ledger "
                + "configured, or pass acctSchemaId explicitly.");
      }

      if (OBDal.getInstance().get(Year.class, yearId) == null) {
        return actionableError(422, "year_not_resolved",
            "Could not resolve year " + yearId + ".",
            "Pass a valid C_Year id readable by your role.");
      }
      if (compareTo && OBDal.getInstance().get(Year.class, referenceYearId) == null) {
        return actionableError(422, "reference_year_not_resolved",
            "Could not resolve comparison year " + referenceYearId + ".",
            "Pass a valid C_Year id readable by your role, or set compareTo to false.");
      }

      String clientId = OBContext.getOBContext().getCurrentClient().getId();

      List<AccountReportTree.NodeRow> nodeRows = queryNodeRows(clientId, orgId, acctSchemaId, yearId,
          dateFrom, dateTo, compareTo, referenceYearId, fromReferenceDate, toReferenceDate);
      List<AccountReportTree.OperandRow> operandRows = queryOperandRows(clientId, acctSchemaId);

      List<AccountReportTree.OutputRow> tree = AccountReportTree.build(nodeRows, operandRows,
          accountLevel, showOnlyWithValue);

      JSONArray data = buildDataArray(tree, compareTo);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());
      responseData.put("meta", buildMeta(yearId, orgId, acctSchemaId, accountLevel,
          showOnlyWithValue, dateFrom, dateTo, compareTo, referenceYearId, fromReferenceDate,
          toReferenceDate));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing profit and loss report", e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

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

  private static boolean isValidId(String id) {
    return id != null && ID_SHAPE.matcher(id).matches();
  }

  // -------------------------------------------------------------------------
  // Parameter resolution
  // -------------------------------------------------------------------------

  /** Same convention as {@link BalanceSheetReportHandler#resolveOrgId}. */
  private static String resolveOrgId(JSONObject body) {
    if (body.has(PARAM_ORG_ID)) {
      return body.optString(PARAM_ORG_ID, "");
    }
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  /** Same convention as {@link BalanceSheetReportHandler#resolveAcctSchemaId}. */
  private String resolveAcctSchemaId(JSONObject body, String orgId) {
    String explicit = body.optString(PARAM_ACCT_SCHEMA_ID, "");
    if (!explicit.isEmpty()) {
      return explicit;
    }
    try {
      OBContext.setAdminMode(true);
      AcctSchema schema = null;
      if (!orgId.isEmpty()) {
        Organization org = OBDal.getInstance().get(Organization.class, orgId);
        AcctSchema ledger = org != null ? org.getGeneralLedger() : null;
        if (ledger != null) {
          schema = ledger;
        }
      }
      if (schema == null) {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();
        StringBuilder hql = new StringBuilder("ad_client.id = :clientId and active = true");
        if (!orgId.isEmpty()) {
          hql.append(" and exists (from OrganizationAcctSchema oas where oas.accountingSchema=this"
              + " and oas.organization.id=:orgId and oas.active=true)");
        }
        org.openbravo.dal.service.OBQuery<AcctSchema> query =
            OBDal.getInstance().createQuery(AcctSchema.class, hql.toString());
        query.setNamedParameter("clientId", clientId);
        if (!orgId.isEmpty()) {
          query.setNamedParameter("orgId", orgId);
        }
        query.setMaxResult(1);
        schema = query.uniqueResult();
      }
      return schema != null ? schema.getId() : null;
    } catch (Exception e) {
      log.warn("Could not resolve accounting schema for org {}", orgId, e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static NeoResponse actionableError(int status, String errorCode, String detail, String hint) {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put("status", status);
      envelope.put("error", errorCode);
      envelope.put("detail", detail);
      envelope.put("hint", hint);
      return NeoResponse.error(status, envelope);
    } catch (Exception e) {
      log.warn("Could not build actionable error envelope for '{}': {}", errorCode, e.getMessage());
      return NeoResponse.error(status, detail);
    }
  }

  // -------------------------------------------------------------------------
  // SQL — faithful port of artifacts/profit-loss/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  /**
   * Runs the P&amp;L's node-tree query with real bind parameters. Faithful to the Node contract's
   * {@code sql.query} except: (1) every value is a real bind parameter, never string-concatenated;
   * (2) the {@code ('__X__' = '' OR ...)} optional-filter pattern is replaced with conditional SQL
   * construction (see {@link #buildPeriodCase}); (3) the org filter keeps the contract's own
   * {@code ad_isorgincluded} org-TREE semantics, only omitted entirely when {@code orgId} is
   * blank — see {@link BalanceSheetReportHandler} class javadoc for the org-tree rationale, which
   * applies unchanged here.
   */
  @SuppressWarnings("unchecked")
  private List<Object[]> queryRawNodeRows(String clientId, String orgId, String acctSchemaId,
      String yearId, LocalDate dateFrom, LocalDate dateTo, boolean compareTo,
      String referenceYearId, LocalDate fromReferenceDate, LocalDate toReferenceDate) {

    String orgFilter = orgId.isEmpty() ? "" :
        "    AND ad_isorgincluded(fa.ad_org_id, :orgId, fa.ad_client_id) <> -1 ";

    String mainCase = buildPeriodCase(true, "yearId", dateFrom != null, "dateFrom",
        dateTo != null, "dateTo");
    String refCase = compareTo
        ? buildPeriodCase(true, "referenceYearId", fromReferenceDate != null, "fromReferenceDate",
            toReferenceDate != null, "toReferenceDate")
        : "0";

    String sql =
        "WITH RECURSIVE elem AS ( "
            + "  SELECT e.c_element_id, e.ad_tree_id FROM c_acctschema_element ase "
            + "  JOIN c_element e ON e.c_element_id = ase.c_element_id "
            + "  WHERE ase.c_acctschema_id = :acctSchemaId AND ase.elementtype = 'AC' LIMIT 1 "
            + "), "
            + "rpt AS ( "
            + "  SELECT r.c_acct_rpt_id FROM c_acct_rpt r "
            + "  WHERE r.ad_client_id = :clientId AND r.isactive = 'Y' AND r.reporttype = 'N' "
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
            + "facts AS ( "
            + "  SELECT fa.account_id, "
            + "    SUM(" + mainCase + ") AS own_amt, "
            + "    SUM(" + refCase + ") AS own_amt_ref "
            + "  FROM fact_acct fa "
            + "  WHERE fa.ad_client_id = :clientId AND fa.factaccttype NOT IN ('R', 'C') "
            + orgFilter
            + "    AND fa.c_acctschema_id = :acctSchemaId "
            + "  GROUP BY fa.account_id "
            + ") "
            + "SELECT t.node_id, COALESCE(t.parent_id, '') AS parent_id, t.depth, t.sort_path, "
            + "  t.group_name, ev.value, ev.name, ev.elementlevel, ev.isalwaysshown, "
            + "  ev.accountsign, COALESCE(f.own_amt, 0) AS own_amt, "
            + "  COALESCE(f.own_amt_ref, 0) AS own_amt_ref "
            + "FROM tree t JOIN c_elementvalue ev ON ev.c_elementvalue_id = t.node_id "
            + "LEFT JOIN facts f ON f.account_id = t.node_id "
            + "WHERE ev.c_element_id = (SELECT c_element_id FROM elem) AND ev.isactive = 'Y' "
            + "ORDER BY t.sort_path";

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter("clientId", clientId);
    query.setParameter("acctSchemaId", acctSchemaId);
    query.setParameter("yearId", yearId);
    if (dateFrom != null) {
      query.setParameter("dateFrom", java.sql.Date.valueOf(dateFrom));
    }
    if (dateTo != null) {
      query.setParameter("dateTo", java.sql.Date.valueOf(dateTo));
    }
    if (!orgId.isEmpty()) {
      query.setParameter("orgId", orgId);
    }
    if (compareTo) {
      query.setParameter("referenceYearId", referenceYearId);
      if (fromReferenceDate != null) {
        query.setParameter("fromReferenceDate", java.sql.Date.valueOf(fromReferenceDate));
      }
      if (toReferenceDate != null) {
        query.setParameter("toReferenceDate", java.sql.Date.valueOf(toReferenceDate));
      }
    }

    return query.list();
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
   * Runs {@code sql.operandsQuery} with real bind parameters — the formula edges
   * ({@code C_ELEMENTVALUE_OPERAND}) {@link AccountReportTree} needs to resolve computed total
   * rows. Scoped to client + acctSchema only, matching the contract (formula edges are not
   * org/date-scoped) — identical to {@link BalanceSheetReportHandler}'s own operands query.
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
    query.setParameter("clientId", clientId);
    query.setParameter("acctSchemaId", acctSchemaId);
    return query.list();
  }

  // -------------------------------------------------------------------------
  // Row mapping / response building
  // -------------------------------------------------------------------------

  private static final int COL_NODE_ID = 0;
  private static final int COL_PARENT_ID = 1;
  private static final int COL_DEPTH = 2;
  private static final int COL_SORT_PATH = 3;
  private static final int COL_GROUP_NAME = 4;
  private static final int COL_VALUE = 5;
  private static final int COL_NAME = 6;
  private static final int COL_ELEMENT_LEVEL = 7;
  private static final int COL_IS_ALWAYS_SHOWN = 8;
  private static final int COL_ACCOUNT_SIGN = 9;
  private static final int COL_OWN_AMT = 10;
  private static final int COL_OWN_AMT_REF = 11;

  private List<AccountReportTree.NodeRow> queryNodeRows(String clientId, String orgId,
      String acctSchemaId, String yearId, LocalDate dateFrom, LocalDate dateTo, boolean compareTo,
      String referenceYearId, LocalDate fromReferenceDate, LocalDate toReferenceDate) {
    List<Object[]> rawRows = queryRawNodeRows(clientId, orgId, acctSchemaId, yearId, dateFrom,
        dateTo, compareTo, referenceYearId, fromReferenceDate, toReferenceDate);
    List<AccountReportTree.NodeRow> rows = new ArrayList<>();
    for (Object[] r : rawRows) {
      rows.add(new AccountReportTree.NodeRow(
          str(r[COL_NODE_ID]), str(r[COL_PARENT_ID]), str(r[COL_SORT_PATH]),
          str(r[COL_GROUP_NAME]), str(r[COL_VALUE]), str(r[COL_NAME]), str(r[COL_ELEMENT_LEVEL]),
          toBoolean(r[COL_IS_ALWAYS_SHOWN]), str(r[COL_ACCOUNT_SIGN]),
          toBigDecimal(r[COL_OWN_AMT]), toBigDecimal(r[COL_OWN_AMT_REF])));
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
      throws Exception {
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

  private static JSONObject buildMeta(String yearId, String orgId, String acctSchemaId,
      String accountLevel, boolean showOnlyWithValue, LocalDate dateFrom, LocalDate dateTo,
      boolean compareTo, String referenceYearId, LocalDate fromReferenceDate,
      LocalDate toReferenceDate) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_YEAR_ID, yearId);
    meta.put(PARAM_ORG_ID, orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
    meta.put(PARAM_ACCOUNT_LEVEL, accountLevel);
    meta.put(PARAM_SHOW_ONLY_WITH_VALUE, showOnlyWithValue);
    meta.put(PARAM_DATE_FROM, dateFrom == null ? "" : dateFrom.format(DATE_FORMATTER));
    meta.put(PARAM_DATE_TO, dateTo == null ? "" : dateTo.format(DATE_FORMATTER));
    meta.put(PARAM_COMPARE_TO, compareTo);
    meta.put(PARAM_REFERENCE_YEAR_ID, compareTo ? referenceYearId : "");
    meta.put(PARAM_FROM_REFERENCE_DATE,
        compareTo && fromReferenceDate != null ? fromReferenceDate.format(DATE_FORMATTER) : "");
    meta.put(PARAM_TO_REFERENCE_DATE,
        compareTo && toReferenceDate != null ? toReferenceDate.format(DATE_FORMATTER) : "");
    return meta;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean toBoolean(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String s = String.valueOf(value);
    return "Y".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
  }

  private static int toInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(String.valueOf(value));
  }
}
