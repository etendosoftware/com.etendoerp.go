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
public class BalanceSheetReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BalanceSheetReportHandler.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);
  /** Etendo ids are either a legacy numeric string or a 32-char hex/alnum id — never blank, never whitespace. */
  private static final Pattern ID_SHAPE = Pattern.compile("^[0-9A-Za-z]{1,32}$");

  /** Native-query bind name for the client id — not a report-facing parameter (unlike the PARAM_* below). */
  private static final String SQL_PARAM_CLIENT_ID = "clientId";
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
    return Optional.of(List.of(
        NeoReportParam.required(PARAM_YEAR_ID, NeoReportParam.TYPE_STRING,
            "C_Year id whose fiscal period end bounds the report — the balance is a cumulative "
                + "snapshot as of that year's last period end date (further narrowed by dateTo, "
                + "if given), not a period-activity total."),
        NeoReportParam.optional(PARAM_ACCT_SCHEMA_ID, NeoReportParam.TYPE_STRING,
            "Accounting schema (C_AcctSchema) id whose chart-of-accounts report definition and "
                + "postings are used. Default: the organization's general ledger."),
        NeoReportParam.optional(PARAM_ORG_ID, NeoReportParam.TYPE_STRING,
            "Organization id to report on. Filters that organization AND every descendant in its "
                + "org tree (unlike the exact-match orgId of generate_report_trial_balance / "
                + "generate_report_journal_entries). Default: the session's current organization. "
                + "Pass an empty string to report on every organization of the client instead."),
        NeoReportParam.optional(PARAM_DATE_FROM, NeoReportParam.TYPE_DATE,
            "Accepted for symmetry with the report's other date parameters, but has NO EFFECT: "
                + "Balance Sheet is a cumulative snapshot, not a period-activity report, so there "
                + "is no lower date bound to apply. See yearId/dateTo for what actually bounds "
                + "the query."),
        NeoReportParam.optional(PARAM_DATE_TO, NeoReportParam.TYPE_DATE,
            "Upper bound (inclusive) on the snapshot date, narrowing yearId's own fiscal "
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
                + "(referenceYearId/toReferenceDate). Default: false. When true, "
                + "referenceYearId is required."),
        NeoReportParam.optional(PARAM_REFERENCE_YEAR_ID, NeoReportParam.TYPE_STRING,
            "C_Year id for the comparison snapshot. Required when compareTo is true; ignored "
                + "otherwise."),
        NeoReportParam.optional(PARAM_FROM_REFERENCE_DATE, NeoReportParam.TYPE_DATE,
            "Accepted for symmetry, but has NO EFFECT — same reason as dateFrom above."),
        NeoReportParam.optional(PARAM_TO_REFERENCE_DATE, NeoReportParam.TYPE_DATE,
            "Upper bound (inclusive) on the comparison snapshot date, narrowing "
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
      desc.put("name", "Balance Sheet");
      desc.put("description", "Balance de Situación — Assets vs Liabilities + Equity, as an "
          + "indented account tree with roll-up and formula (computed total) rows, as of a "
          + "fiscal year end.");

      JSONArray params = new JSONArray();
      for (NeoReportParam declared : reportParameters().orElse(List.of())) {
        params.put(param(declared));
      }
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building balance sheet report descriptor", e);
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
      NeoResponse yearIdError = validateYearId(yearId);
      if (yearIdError != null) {
        return yearIdError;
      }

      String accountLevel = body.optString(PARAM_ACCOUNT_LEVEL, DEFAULT_ACCOUNT_LEVEL);
      NeoResponse accountLevelError = validateAccountLevel(accountLevel);
      if (accountLevelError != null) {
        return accountLevelError;
      }

      NeoResponse dateToError = validateOptionalDate(body, PARAM_DATE_TO);
      if (dateToError != null) {
        return dateToError;
      }
      LocalDate dateTo = parseOptionalDate(body, PARAM_DATE_TO);

      boolean compareTo = body.optBoolean(PARAM_COMPARE_TO, false);
      String referenceYearId = body.optString(PARAM_REFERENCE_YEAR_ID, "");
      NeoResponse compareToError = validateCompareTo(compareTo, referenceYearId);
      if (compareToError != null) {
        return compareToError;
      }

      NeoResponse toReferenceDateError = validateOptionalDate(body, PARAM_TO_REFERENCE_DATE);
      if (toReferenceDateError != null) {
        return toReferenceDateError;
      }
      LocalDate toReferenceDate = parseOptionalDate(body, PARAM_TO_REFERENCE_DATE);

      boolean showOnlyWithValue = body.optBoolean(PARAM_SHOW_ONLY_WITH_VALUE, true);

      String orgId = resolveOrgId(body);
      NeoResponse orgIdError = validateOrgId(orgId);
      if (orgIdError != null) {
        return orgIdError;
      }

      String acctSchemaId = resolveAcctSchemaId(body, orgId);
      if (acctSchemaId == null || acctSchemaId.isEmpty()) {
        return actionableError(422, "accounting_schema_unresolved",
            "Could not resolve an accounting schema for organization "
                + (orgId.isEmpty() ? "(none — client-wide)" : orgId) + ".",
            "Check that the organization (or an ancestor in its tree) has a general ledger "
                + "configured, or pass acctSchemaId explicitly.");
      }

      NeoResponse yearsResolvedError = validateYearsResolved(yearId, compareTo, referenceYearId);
      if (yearsResolvedError != null) {
        return yearsResolvedError;
      }

      String clientId = OBContext.getOBContext().getCurrentClient().getId();

      QueryParams params = QueryParams.builder()
          .clientId(clientId)
          .orgId(orgId)
          .acctSchemaId(acctSchemaId)
          .yearId(yearId)
          .dateTo(dateTo)
          .compareTo(compareTo)
          .referenceYearId(referenceYearId)
          .toReferenceDate(toReferenceDate)
          .accountLevel(accountLevel)
          .showOnlyWithValue(showOnlyWithValue)
          .build();

      List<AccountReportTree.NodeRow> nodeRows = queryNodeRows(params);
      List<AccountReportTree.OperandRow> operandRows = queryOperandRows(clientId, acctSchemaId);

      List<AccountReportTree.OutputRow> tree = AccountReportTree.build(nodeRows, operandRows,
          accountLevel, showOnlyWithValue);

      JSONArray data = buildDataArray(tree, compareTo);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());
      responseData.put("meta", buildMeta(params));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing balance sheet report", e);
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

  private static NeoResponse validateYearId(String yearId) {
    if (yearId.isEmpty()) {
      return actionableError(400, "year_id_required",
          "yearId is required.",
          "Pass the C_Year id whose fiscal period end the balance should be reported as of.");
    }
    if (!isValidId(yearId)) {
      return actionableError(400, "year_id_invalid",
          "yearId '" + yearId + "' does not look like a valid Etendo year id.",
          "Pass a real C_Year id.");
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

  private static NeoResponse validateCompareTo(boolean compareTo, String referenceYearId) {
    if (!compareTo) {
      return null;
    }
    if (referenceYearId.isEmpty()) {
      return actionableError(400, "reference_year_id_required",
          "referenceYearId is required when compareTo is true.",
          "Pass the C_Year id for the comparison snapshot, or set compareTo to false.");
    }
    if (!isValidId(referenceYearId)) {
      return actionableError(400, "reference_year_id_invalid",
          "referenceYearId '" + referenceYearId + "' does not look like a valid Etendo year id.",
          "Pass a real C_Year id.");
    }
    return null;
  }

  private static NeoResponse validateOrgId(String orgId) {
    if (orgId.isEmpty()) {
      return null;
    }
    if (!isValidId(orgId)) {
      return actionableError(400, "org_id_invalid",
          "orgId '" + orgId + "' does not look like a valid Etendo organization id.",
          "Pass a real C_Organization id, or omit orgId to use the session's organization, or "
              + "pass an empty string to report on every organization.");
    }
    if (OBDal.getInstance().get(Organization.class, orgId) == null) {
      return actionableError(400, "organization_not_resolved",
          "Could not resolve organization " + orgId + " for the balance sheet report.",
          "Pass a valid orgId (a C_Organization id readable by your role), an empty string for "
              + "every organization, or omit it to use the session's current organization.");
    }
    return null;
  }

  private static NeoResponse validateYearsResolved(String yearId, boolean compareTo,
      String referenceYearId) {
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
    return null;
  }

  // -------------------------------------------------------------------------
  // Parameter resolution
  // -------------------------------------------------------------------------

  /** Same convention as {@link TrialBalanceReportHandler#resolveOrgId} — see this class's javadoc. */
  private static String resolveOrgId(JSONObject body) {
    if (body.has(PARAM_ORG_ID)) {
      return body.optString(PARAM_ORG_ID, "");
    }
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  /** Same convention as {@link TrialBalanceReportHandler#resolveAcctSchemaId}. */
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
        query.setNamedParameter(SQL_PARAM_CLIENT_ID, clientId);
        if (!orgId.isEmpty()) {
          query.setNamedParameter(PARAM_ORG_ID, orgId);
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
  // SQL — faithful port of artifacts/balance-sheet/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  /**
   * Runs the balance sheet's node-tree query with real bind parameters. Faithful to the Node
   * contract's {@code sql.query} except: (1) every value is a real bind parameter, never
   * string-concatenated; (2) the {@code ('__X__' = '' OR ...)} optional-filter pattern is replaced
   * with conditional SQL construction (see {@link #buildReferenceCase}); (3) the org filter keeps
   * the contract's own {@code ad_isorgincluded} org-TREE semantics (see class javadoc), only
   * omitted entirely when {@code orgId} is blank.
   */
  @SuppressWarnings("unchecked")
  private List<Object[]> queryRawNodeRows(QueryParams p) {
    String orgId = p.orgId;
    String clientId = p.clientId;
    String acctSchemaId = p.acctSchemaId;
    String yearId = p.yearId;
    LocalDate dateTo = p.dateTo;
    boolean compareTo = p.compareTo;
    String referenceYearId = p.referenceYearId;
    LocalDate toReferenceDate = p.toReferenceDate;

    String orgFilter = orgId.isEmpty() ? "" :
        "    AND ad_isorgincluded(fa.ad_org_id, :orgId, fa.ad_client_id) <> -1 ";

    String mainCase = "CASE WHEN fa.dateacct <= (SELECT MAX(p.enddate) FROM c_period p "
        + "WHERE p.c_year_id = :yearId)"
        + (dateTo != null ? " AND fa.dateacct <= :dateTo" : "")
        + " THEN fa.amtacctdr - fa.amtacctcr ELSE 0 END";

    String refCase = buildReferenceCase(compareTo, toReferenceDate);

    String sql =
        "WITH RECURSIVE elem AS ( "
            + "  SELECT e.c_element_id, e.ad_tree_id FROM c_acctschema_element ase "
            + "  JOIN c_element e ON e.c_element_id = ase.c_element_id "
            + "  WHERE ase.c_acctschema_id = :acctSchemaId AND ase.elementtype = 'AC' LIMIT 1 "
            + "), "
            + "rpt AS ( "
            + "  SELECT r.c_acct_rpt_id FROM c_acct_rpt r "
            + "  WHERE r.ad_client_id = :clientId AND r.isactive = 'Y' AND r.reporttype = 'Y' "
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
            + "income_summary AS ( "
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
            + ") "
            + "SELECT t.node_id, COALESCE(t.parent_id, '') AS parent_id, t.depth, t.sort_path, "
            + "  t.group_name, ev.value, ev.name, ev.elementlevel, ev.isalwaysshown, "
            + "  ev.accountsign, COALESCE(SUM(f.dr_minus_cr), 0) AS own_amt, "
            + "  COALESCE(SUM(f.dr_minus_cr_ref), 0) AS own_amt_ref "
            + "FROM tree t JOIN c_elementvalue ev ON ev.c_elementvalue_id = t.node_id "
            + "LEFT JOIN facts f ON f.account_id = t.node_id "
            + "WHERE ev.c_element_id = (SELECT c_element_id FROM elem) AND ev.isactive = 'Y' "
            + "GROUP BY t.node_id, t.parent_id, t.depth, t.sort_path, t.group_name, ev.value, "
            + "  ev.name, ev.elementlevel, ev.isalwaysshown, ev.accountsign "
            + "ORDER BY t.sort_path";

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(SQL_PARAM_CLIENT_ID, clientId);
    query.setParameter(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
    query.setParameter(PARAM_YEAR_ID, yearId);
    if (dateTo != null) {
      query.setParameter(PARAM_DATE_TO, java.sql.Date.valueOf(dateTo));
    }
    if (!orgId.isEmpty()) {
      query.setParameter(PARAM_ORG_ID, orgId);
    }
    if (compareTo) {
      query.setParameter(PARAM_REFERENCE_YEAR_ID, referenceYearId);
      if (toReferenceDate != null) {
        query.setParameter(PARAM_TO_REFERENCE_DATE, java.sql.Date.valueOf(toReferenceDate));
      }
    }

    return query.list();
  }

  /**
   * Builds the comparison-period SUM CASE. Faithful to the contract's own
   * {@code '__COMPARETO__' = 'true' AND (...)} clause, but resolved in Java rather than bound as a
   * string: when {@code compareTo} is false the entire comparison branch is a constant {@code 0}
   * (referenceYearId is not even required in that case, per {@link #executeReport}'s validation),
   * exactly matching what the Node placeholder substitution collapses to once
   * {@code stripBlankOptionalClauses} strips the dead branch.
   */
  private static String buildReferenceCase(boolean compareTo, LocalDate toReferenceDate) {
    if (!compareTo) {
      return "0";
    }
    return "CASE WHEN fa.dateacct <= (SELECT MAX(p.enddate) FROM c_period p "
        + "WHERE p.c_year_id = :referenceYearId)"
        + (toReferenceDate != null ? " AND fa.dateacct <= :toReferenceDate" : "")
        + " THEN fa.amtacctdr - fa.amtacctcr ELSE 0 END";
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

  private List<AccountReportTree.NodeRow> queryNodeRows(QueryParams p) {
    List<Object[]> rawRows = queryRawNodeRows(p);
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

  private static JSONObject buildMeta(QueryParams p) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_YEAR_ID, p.yearId);
    meta.put(PARAM_ORG_ID, p.orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, p.acctSchemaId);
    meta.put(PARAM_ACCOUNT_LEVEL, p.accountLevel);
    meta.put(PARAM_SHOW_ONLY_WITH_VALUE, p.showOnlyWithValue);
    meta.put(PARAM_DATE_TO, p.dateTo == null ? "" : p.dateTo.format(DATE_FORMATTER));
    meta.put(PARAM_COMPARE_TO, p.compareTo);
    meta.put(PARAM_REFERENCE_YEAR_ID, p.compareTo ? p.referenceYearId : "");
    meta.put(PARAM_TO_REFERENCE_DATE,
        p.compareTo && p.toReferenceDate != null ? p.toReferenceDate.format(DATE_FORMATTER) : "");
    return meta;
  }

  /**
   * Bundles this report's resolved request parameters so downstream private methods don't need an
   * oversized parameter list. Built once in {@link #executeReport} after all validation has passed.
   */
  private static final class QueryParams {
    final String clientId;
    final String orgId;
    final String acctSchemaId;
    final String yearId;
    final LocalDate dateTo;
    final boolean compareTo;
    final String referenceYearId;
    final LocalDate toReferenceDate;
    final String accountLevel;
    final boolean showOnlyWithValue;

    private QueryParams(Builder b) {
      this.clientId = b.clientId;
      this.orgId = b.orgId;
      this.acctSchemaId = b.acctSchemaId;
      this.yearId = b.yearId;
      this.dateTo = b.dateTo;
      this.compareTo = b.compareTo;
      this.referenceYearId = b.referenceYearId;
      this.toReferenceDate = b.toReferenceDate;
      this.accountLevel = b.accountLevel;
      this.showOnlyWithValue = b.showOnlyWithValue;
    }

    static Builder builder() {
      return new Builder();
    }

    static final class Builder {
      private String clientId;
      private String orgId;
      private String acctSchemaId;
      private String yearId;
      private LocalDate dateTo;
      private boolean compareTo;
      private String referenceYearId;
      private LocalDate toReferenceDate;
      private String accountLevel;
      private boolean showOnlyWithValue;

      Builder clientId(String v) {
        this.clientId = v;
        return this;
      }

      Builder orgId(String v) {
        this.orgId = v;
        return this;
      }

      Builder acctSchemaId(String v) {
        this.acctSchemaId = v;
        return this;
      }

      Builder yearId(String v) {
        this.yearId = v;
        return this;
      }

      Builder dateTo(LocalDate v) {
        this.dateTo = v;
        return this;
      }

      Builder compareTo(boolean v) {
        this.compareTo = v;
        return this;
      }

      Builder referenceYearId(String v) {
        this.referenceYearId = v;
        return this;
      }

      Builder toReferenceDate(LocalDate v) {
        this.toReferenceDate = v;
        return this;
      }

      Builder accountLevel(String v) {
        this.accountLevel = v;
        return this;
      }

      Builder showOnlyWithValue(boolean v) {
        this.showOnlyWithValue = v;
        return this;
      }

      QueryParams build() {
        return new QueryParams(this);
      }
    }
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
