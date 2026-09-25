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
import java.sql.Date;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

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
public class GeneralLedgerReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GeneralLedgerReportHandler.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);
  /** Etendo ids are either a legacy numeric string or a 32-char hex/alnum id — never blank, never whitespace. */
  private static final Pattern ID_SHAPE = Pattern.compile("^[0-9A-Za-z]{1,32}$");

  /** Native-query bind name for the client id — not a report-facing parameter (unlike the PARAM_* below). */
  private static final String SQL_PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_DATE_FROM = "dateFrom";
  private static final String PARAM_DATE_TO = "dateTo";
  private static final String PARAM_ORG_ID = "orgId";
  private static final String PARAM_ACCT_SCHEMA_ID = "acctSchemaId";
  private static final String PARAM_FROM_ACCOUNT_ID = "fromAccountId";
  private static final String PARAM_TO_ACCOUNT_ID = "toAccountId";
  private static final String PARAM_BPARTNER_ID = "bPartnerId";
  private static final String PARAM_PRODUCT_ID = "productId";
  private static final String PARAM_PROJECT_ID = "projectId";
  private static final String PARAM_COST_CENTER_ID = "costCenterId";
  private static final String PARAM_SHOW_DIMENSIONS = "showDimensions";
  private static final String PARAM_GROUP_BY = "groupBy";
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
      desc.put("name", "General Ledger");
      desc.put("description", "Libro Mayor — every accounting movement posted in the period, "
          + "nested as one object per account with its chronological lines and running balance. "
          + "Optionally grouped by a dimension (contact/product/project/cost center).");

      JSONArray params = new JSONArray();
      for (NeoReportParam declared : reportParameters().orElse(List.of())) {
        params.put(param(declared));
      }
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building general ledger report descriptor", e);
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

      NeoResponse validationError = validateDates(body);
      if (validationError != null) {
        return validationError;
      }
      LocalDate dateFrom = LocalDate.parse(body.optString(PARAM_DATE_FROM, ""), DATE_FORMATTER);
      LocalDate dateTo = LocalDate.parse(body.optString(PARAM_DATE_TO, ""), DATE_FORMATTER);

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
      if (acctSchemaId == null || acctSchemaId.isEmpty()) {
        return actionableError(422, "accounting_schema_unresolved",
            "Could not resolve an accounting schema for organization "
                + (orgId.isEmpty() ? "(none — client-wide)" : orgId) + ".",
            "Check that the organization (or an ancestor in its tree) has a general ledger "
                + "configured, or pass acctSchemaId explicitly.");
      }

      boolean showDimensions = body.optBoolean(PARAM_SHOW_DIMENSIONS, false);
      boolean showOpenBalances = body.optBoolean(PARAM_SHOW_OPEN_BALANCES, true);

      List<String> bPartnerIds = parseIds(body.optString(PARAM_BPARTNER_ID, ""));
      List<String> productIds = parseIds(body.optString(PARAM_PRODUCT_ID, ""));
      List<String> projectIds = parseIds(body.optString(PARAM_PROJECT_ID, ""));
      List<String> costCenterIds = parseIds(body.optString(PARAM_COST_CENTER_ID, ""));
      String fromAccountId = body.optString(PARAM_FROM_ACCOUNT_ID, "");
      String toAccountId = body.optString(PARAM_TO_ACCOUNT_ID, "");

      String clientId = OBContext.getOBContext().getCurrentClient().getId();

      Filters filters = Filters.builder()
          .clientId(clientId)
          .orgId(orgId)
          .acctSchemaId(acctSchemaId)
          .dateFrom(dateFrom)
          .dateTo(dateTo)
          .bPartnerIds(bPartnerIds)
          .productIds(productIds)
          .projectIds(projectIds)
          .costCenterIds(costCenterIds)
          .fromAccountId(fromAccountId)
          .toAccountId(toAccountId)
          .build();

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

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", accountsReturned);
      responseData.put("meta", buildMeta(filters, new ResponseMeta(accountLimit,
          linesPerAccountLimit, truncatedAccounts, totalAccounts, accountsReturned, groupBy)));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing general ledger report", e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  private static NeoResponse validateDates(JSONObject body) {
    String dateFromRaw = body.optString(PARAM_DATE_FROM, "");
    String dateToRaw = body.optString(PARAM_DATE_TO, "");
    if (dateFromRaw.isEmpty() || dateToRaw.isEmpty()) {
      return actionableError(400, "dates_required",
          "Both dateFrom and dateTo are required.",
          "Pass both as yyyy-MM-dd strings, e.g. dateFrom=\"2026-01-01\", dateTo=\"2026-09-24\".");
    }
    LocalDate dateFrom;
    LocalDate dateTo;
    try {
      dateFrom = LocalDate.parse(dateFromRaw, DATE_FORMATTER);
    } catch (DateTimeException e) {
      return actionableError(400, "date_from_invalid",
          "dateFrom '" + dateFromRaw + "' is not a valid yyyy-MM-dd date.",
          "Pass dateFrom as yyyy-MM-dd, e.g. \"2026-01-01\".");
    }
    try {
      dateTo = LocalDate.parse(dateToRaw, DATE_FORMATTER);
    } catch (DateTimeException e) {
      return actionableError(400, "date_to_invalid",
          "dateTo '" + dateToRaw + "' is not a valid yyyy-MM-dd date.",
          "Pass dateTo as yyyy-MM-dd, e.g. \"2026-09-24\".");
    }
    if (dateFrom.isAfter(dateTo)) {
      return actionableError(400, "date_range_invalid",
          "dateFrom (" + dateFromRaw + ") is after dateTo (" + dateToRaw + ").",
          "Swap the dates, or widen dateTo so it is on or after dateFrom.");
    }
    return null;
  }

  private static boolean isValidId(String id) {
    return id != null && ID_SHAPE.matcher(id).matches();
  }

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
          "Could not resolve organization " + orgId + " for the general ledger report.",
          "Pass a valid orgId (a C_Organization id readable by your role), an empty string for "
              + "every organization, or omit it to use the session's current organization.");
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Parameter resolution
  // -------------------------------------------------------------------------

  /** Same convention as {@link TrialBalanceReportHandler#resolveOrgId}. */
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

  private static List<String> parseIds(String rawValue) {
    if (StringUtils.isBlank(rawValue) || "null".equalsIgnoreCase(rawValue)) {
      return List.of();
    }
    return java.util.Arrays.stream(rawValue.split(","))
        .map(String::trim)
        .filter(StringUtils::isNotBlank)
        .toList();
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
  // Filters bundle — bound identically by every SQL method below
  // -------------------------------------------------------------------------

  private static final class Filters {
    final String clientId;
    final String orgId;
    final String acctSchemaId;
    final LocalDate dateFrom;
    final LocalDate dateTo;
    final List<String> bPartnerIds;
    final List<String> productIds;
    final List<String> projectIds;
    final List<String> costCenterIds;
    final String fromAccountId;
    final String toAccountId;

    private Filters(Builder b) {
      this.clientId = b.clientId;
      this.orgId = b.orgId;
      this.acctSchemaId = b.acctSchemaId;
      this.dateFrom = b.dateFrom;
      this.dateTo = b.dateTo;
      this.bPartnerIds = b.bPartnerIds;
      this.productIds = b.productIds;
      this.projectIds = b.projectIds;
      this.costCenterIds = b.costCenterIds;
      this.fromAccountId = b.fromAccountId;
      this.toAccountId = b.toAccountId;
    }

    static Builder builder() {
      return new Builder();
    }

    /** Fluent builder — {@link Filters} has too many fields for a plain constructor (java:S107). */
    static final class Builder {
      private String clientId;
      private String orgId;
      private String acctSchemaId;
      private LocalDate dateFrom;
      private LocalDate dateTo;
      private List<String> bPartnerIds;
      private List<String> productIds;
      private List<String> projectIds;
      private List<String> costCenterIds;
      private String fromAccountId;
      private String toAccountId;

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

      Builder dateFrom(LocalDate v) {
        this.dateFrom = v;
        return this;
      }

      Builder dateTo(LocalDate v) {
        this.dateTo = v;
        return this;
      }

      Builder bPartnerIds(List<String> v) {
        this.bPartnerIds = v;
        return this;
      }

      Builder productIds(List<String> v) {
        this.productIds = v;
        return this;
      }

      Builder projectIds(List<String> v) {
        this.projectIds = v;
        return this;
      }

      Builder costCenterIds(List<String> v) {
        this.costCenterIds = v;
        return this;
      }

      Builder fromAccountId(String v) {
        this.fromAccountId = v;
        return this;
      }

      Builder toAccountId(String v) {
        this.toAccountId = v;
        return this;
      }

      Filters build() {
        return new Filters(this);
      }
    }

    /** Appends the shared WHERE clauses common to the main, totals and count queries. */
    void appendCommonWhere(StringBuilder sql) {
      if (!orgId.isEmpty()) {
        sql.append("  AND fa.ad_org_id = :orgId ");
      }
      if (!bPartnerIds.isEmpty()) {
        sql.append("  AND fa.c_bpartner_id IN (:bPartnerIds) ");
      }
      if (!productIds.isEmpty()) {
        sql.append("  AND fa.m_product_id IN (:productIds) ");
      }
      if (!projectIds.isEmpty()) {
        sql.append("  AND fa.c_project_id IN (:projectIds) ");
      }
      if (!costCenterIds.isEmpty()) {
        sql.append("  AND fa.c_costcenter_id IN (:costCenterIds) ");
      }
      if (!fromAccountId.isEmpty()) {
        sql.append("  AND ev.value >= :fromAccountId ");
      }
      if (!toAccountId.isEmpty()) {
        sql.append("  AND ev.value <= :toAccountId ");
      }
    }

    void bindCommon(NativeQuery<?> query) {
      query.setParameter(SQL_PARAM_CLIENT_ID, clientId);
      query.setParameter(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
      if (!orgId.isEmpty()) {
        query.setParameter(PARAM_ORG_ID, orgId);
      }
      if (!bPartnerIds.isEmpty()) {
        query.setParameterList("bPartnerIds", bPartnerIds);
      }
      if (!productIds.isEmpty()) {
        query.setParameterList("productIds", productIds);
      }
      if (!projectIds.isEmpty()) {
        query.setParameterList("projectIds", projectIds);
      }
      if (!costCenterIds.isEmpty()) {
        query.setParameterList("costCenterIds", costCenterIds);
      }
      if (!fromAccountId.isEmpty()) {
        query.setParameter(PARAM_FROM_ACCOUNT_ID, fromAccountId);
      }
      if (!toAccountId.isEmpty()) {
        query.setParameter(PARAM_TO_ACCOUNT_ID, toAccountId);
      }
    }
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
  private List<GeneralLedgerGrouping.Row> buildLineRows(Filters f, int accountLimit,
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
    f.appendCommonWhere(sql);
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
    f.bindCommon(query);
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
  private List<GeneralLedgerGrouping.AccountTotal> buildAccountTotals(Filters f,
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
    f.appendCommonWhere(sql);
    sql.append("GROUP BY ev.value, bp.name, p.name, pj.name, cc.name");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    f.bindCommon(query);
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
  private List<GeneralLedgerGrouping.OpeningRow> buildOpeningRows(Filters f,
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
    f.appendCommonWhere(sql);
    sql.append("GROUP BY ev.value, bp.name, p.name, pj.name, cc.name");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    f.bindCommon(query);
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
  private long countTotalAccounts(Filters f) {
    StringBuilder sql = new StringBuilder(
        "SELECT COUNT(DISTINCT ev.value) FROM fact_acct fa "
            + "JOIN c_elementvalue ev ON ev.c_elementvalue_id = fa.account_id "
            + "WHERE fa.ad_client_id = :clientId "
            + "  AND fa.factaccttype NOT IN ('R', 'C') "
            + "  AND fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "  AND fa.c_acctschema_id = :acctSchemaId ");
    f.appendCommonWhere(sql);

    NativeQuery<Number> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    f.bindCommon(query);
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
      l.put("bpname", orEmpty(line.bpname));
      l.put("productname", orEmpty(line.productname));
      l.put("projectname", orEmpty(line.projectname));
      l.put("costcentername", orEmpty(line.costcentername));
    }
    return l;
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  private static JSONObject amountsJson(GeneralLedgerGrouping.Amounts amounts) throws Exception {
    JSONObject o = new JSONObject();
    o.put("amtacctdr", amounts.amtacctdr);
    o.put("amtacctcr", amounts.amtacctcr);
    o.put("total", amounts.total);
    return o;
  }

  /**
   * The response-summary fields {@link #buildMeta} needs beyond what {@link Filters} already
   * carries — kept as its own small holder (6 fields, under the java:S107 threshold on its own)
   * so {@code buildMeta} itself only takes {@code (Filters, ResponseMeta)}.
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

  private static JSONObject buildMeta(Filters f, ResponseMeta rm) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_DATE_FROM, f.dateFrom.format(DATE_FORMATTER));
    meta.put(PARAM_DATE_TO, f.dateTo.format(DATE_FORMATTER));
    meta.put(PARAM_ORG_ID, f.orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, f.acctSchemaId);
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
    meta.put(PARAM_FROM_ACCOUNT_ID, f.fromAccountId);
    meta.put(PARAM_TO_ACCOUNT_ID, f.toAccountId);
    meta.put(PARAM_BPARTNER_ID, String.join(",", f.bPartnerIds));
    meta.put(PARAM_PRODUCT_ID, String.join(",", f.productIds));
    meta.put(PARAM_PROJECT_ID, String.join(",", f.projectIds));
    meta.put(PARAM_COST_CENTER_ID, String.join(",", f.costCenterIds));
    return meta;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static long toLong(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return Long.parseLong(String.valueOf(value));
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

  /**
   * Renders {@code fact_acct.dateacct} as a plain {@code yyyy-MM-dd} calendar date. Same
   * conversion (and the same timezone-safety reasoning) as
   * {@link JournalEntriesReportHandler#toIsoDate}.
   */
  static String toIsoDate(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof java.sql.Timestamp) {
      return ((java.sql.Timestamp) value).toLocalDateTime().toLocalDate().format(DATE_FORMATTER);
    }
    if (value instanceof Date) {
      return ((Date) value).toLocalDate().format(DATE_FORMATTER);
    }
    if (value instanceof java.time.LocalDateTime) {
      return ((java.time.LocalDateTime) value).toLocalDate().format(DATE_FORMATTER);
    }
    if (value instanceof LocalDate) {
      return ((LocalDate) value).format(DATE_FORMATTER);
    }
    String s = String.valueOf(value);
    return s.length() >= 10 ? s.substring(0, 10) : s;
  }
}
