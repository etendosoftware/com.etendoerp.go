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
import java.util.LinkedHashMap;
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
 * NeoHandler exposing the "Trial Balance" / "Balance de Sumas y Saldos" report as an MCP report
 * tool ({@code generate_report_trial_balance}, ETP-5483 slice 2).
 *
 * <p><b>Why this handler exists.</b> Before this class, {@code report-trial-balance} was served
 * ONLY by the two Node report engines (the Vite dev plugin, {@code
 * tools/app-shell/vite-plugins/report-api.js}, and the production {@code
 * tools/report-server/server.js} in {@code schema_forge_core}), which run
 * {@code artifacts/report-trial-balance/report-contract.json}'s {@code sql.query} — a raw SQL
 * string with {@code __PLACEHOLDER__} text substitution — directly against Postgres. Neither
 * engine is reachable from the Java MCP servlet, so an MCP agent could not run this report at all.
 * This handler is a faithful Java port of that same query, using real bind parameters instead of
 * string substitution, plus a Java port of the row-folding post-processing both Node engines share
 * via {@code schema_forge_core/cli/src/report-grouping.js}'s {@code resolveGrouping}/
 * {@code foldAggregateRows} (ported here as {@link TrialBalanceFolding}).
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> This report now has
 * TWO independent implementations of the same business logic: the Node/SQL-placeholder one (this
 * class's source of truth, kept in {@code schema_forge}'s {@code artifacts/report-trial-balance/
 * report-contract.json} plus {@code schema_forge_core}'s {@code cli/src/report-sql.js} and {@code
 * cli/src/report-grouping.js}) and this Java one. They are NOT structurally linked: nothing fails
 * to compile or to run if one changes and the other doesn't, and the SPA keeps using the Node path
 * exclusively — this handler is additive, MCP-only. A change to the trial balance's SQL (new
 * filter, changed opening-balance rule, changed account-tree resolution) or to its post-processing
 * (the grouping fold, the "has activity" filter) in the Node contract/JS MUST be mirrored here by
 * hand, and a change made here first must be back-ported there. Nothing enforces this today beyond
 * this comment and the one on {@link TrialBalanceFolding}.
 *
 * <p><b>Org-filter semantics (deliberately NOT a tree, unlike {@link AgingReportHandler}).</b> The
 * Node engines' {@code applyPlaceholders} (schema_forge_core's {@code cli/src/report-sql.js}) only
 * force-rewrites a literal {@code AD_ORG_ID IN (...)} pattern into an org-tree subquery; this
 * report's own SQL filters organization with a plain equality —
 * {@code ('__ORGID__' = '' OR fa.ad_org_id = '__ORGID__')} — which that rewrite never matches. So
 * the real, live behavior of both Node engines is: an explicit {@code orgId} filters EXACTLY that
 * one organization (no descendant orgs), and a blank {@code orgId} applies no organization filter
 * at all (every org under the client). This handler reproduces that literal single-org-or-none
 * semantics exactly — see {@link #resolveOrgId}. The one deliberate deviation from a byte-for-byte
 * port: when the request omits {@code orgId} entirely (never sends the key), this handler defaults
 * it to the session's current organization, the same "default to the session's organization"
 * convention {@link AgingReportHandler} already uses — the contract's own {@code orgId} parameter
 * is declared {@code hidden: true, required: true}, meaning the SPA always auto-fills it from the
 * session before the Node engines ever see a blank value in practice; an MCP caller that omits it
 * gets the same sensible default rather than an unfiltered, all-organizations report by accident.
 * An MCP caller that explicitly wants every organization can still pass an empty string.
 */
@Named("trialBalanceReportHandler")
public class TrialBalanceReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(TrialBalanceReportHandler.class);

  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);
  /** Etendo ids are either a legacy numeric string or a 32-char hex/alnum id — never blank, never whitespace. */
  private static final Pattern ID_SHAPE = Pattern.compile("^[0-9A-Za-z]{1,32}$");

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
  private static final String PARAM_ACCOUNT_LEVEL = "accountLevel";
  private static final String PARAM_GROUP_BY = "groupBy";
  private static final String PARAM_OPENING_ENTRY_AMOUNT = "openingEntryAmount";

  private static final String LEVEL_SUBACCOUNT = "S";

  /** {@code groupBy} value → (row field it folds by, whether that dimension carries its own id). */
  private static final Map<String, Boolean> GROUP_BY_HAS_ID = new LinkedHashMap<>();
  static {
    GROUP_BY_HAS_ID.put("bpartner", true);
    GROUP_BY_HAS_ID.put("product", false);
    GROUP_BY_HAS_ID.put("project", false);
    GROUP_BY_HAS_ID.put("costcenter", false);
  }

  // -------------------------------------------------------------------------
  // Access gate
  // -------------------------------------------------------------------------

  /**
   * Gates on the SAME coarse, category-level anchor the {@code report-viewer} gallery already
   * uses for this exact report row — {@link ReportAccessCatalog#FINANCIAL_REPORTS_WINDOW_ID}
   * ("Informes financieros" / Financial Reports, a real, active, tab-less pseudo-window, granted
   * Financiero-only). Confirmed via {@code ReportAccessCatalog.ROWS}: {@code report-trial-balance}
   * has NO {@code ETGO_SF_SPEC} row and no linked {@code AD_Process}/OBUIAPP process of its own —
   * {@code ReportAccessCatalog}'s own "Corrected inventory" javadoc (2026-09-21, live QA) confirms
   * this is genuinely the report's only real access boundary today, the same one {@code
   * SFRolesOverview}/{@code SFSystemRoleTemplates} already resolve it through for the "Informes"
   * admin screens. Do not repoint this without also updating {@code ReportAccessCatalog.ROWS} and
   * confirming the new anchor via the same DB evidence.
   */
  @Override
  public boolean isAccessibleForCurrentRole() {
    return NeoAccessHelper.hasWindowAccess(ReportAccessCatalog.FINANCIAL_REPORTS_WINDOW_ID);
  }

  // -------------------------------------------------------------------------
  // Report contract
  // -------------------------------------------------------------------------

  /**
   * The report's input contract, ported from {@code artifacts/report-trial-balance/
   * report-contract.json}'s {@code parameters} array — every entry {@link #executeReport}
   * actually reads, with the default it applies.
   */
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
            "Accounting schema (C_AcctSchema) id whose chart of accounts and postings are used. "
                + "Default: the organization's general ledger."),
        NeoReportParam.optional(PARAM_FROM_ACCOUNT_ID, NeoReportParam.TYPE_STRING,
            "Lower bound (inclusive) on the account search key (C_ElementValue.value), NOT its "
                + "id — matches Classic's 'From Account' range filter. Default: no lower bound."),
        NeoReportParam.optional(PARAM_TO_ACCOUNT_ID, NeoReportParam.TYPE_STRING,
            "Upper bound (inclusive) on the account search key (C_ElementValue.value), NOT its "
                + "id. Default: no upper bound."),
        NeoReportParam.optional(PARAM_BPARTNER_ID, NeoReportParam.TYPE_STRING,
            "Restrict to these business partners: one C_BPartner id, or several separated by "
                + "commas. Default: every partner."),
        NeoReportParam.optional(PARAM_PRODUCT_ID, NeoReportParam.TYPE_STRING,
            "Restrict to these products: one M_Product id, or several separated by commas. "
                + "Default: every product."),
        NeoReportParam.optional(PARAM_PROJECT_ID, NeoReportParam.TYPE_STRING,
            "Restrict to these projects: one C_Project id, or several separated by commas. "
                + "Default: every project."),
        NeoReportParam.optional(PARAM_COST_CENTER_ID, NeoReportParam.TYPE_STRING,
            "Restrict to these cost centers: one C_CostCenter id, or several separated by "
                + "commas. Default: every cost center."),
        NeoReportParam.options(PARAM_ACCOUNT_LEVEL,
            "Chart-of-accounts depth the rows are aggregated to: 'C' Account, 'D' Breakdown, "
                + "'E' Heading, 'S' Subaccount (default: 'S', the finest level).",
            List.of("C", "D", "E", "S")),
        NeoReportParam.options(PARAM_GROUP_BY,
            "Break each account down by one accounting dimension: 'bpartner', 'product', "
                + "'project', or 'costcenter'. Only meaningful when accountLevel is 'S'. Default: "
                + "no breakdown (one row per account).",
            new ArrayList<>(GROUP_BY_HAS_ID.keySet())),
        NeoReportParam.optional(PARAM_OPENING_ENTRY_AMOUNT, NeoReportParam.TYPE_BOOLEAN,
            "Whether an opening-entry posting (factaccttype='O') dated exactly on dateFrom "
                + "counts toward the opening balance instead of the period activity (default: "
                + "true).")));
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
      desc.put("name", "Trial Balance");
      desc.put("description", "Balance de Sumas y Saldos — opening balance, period activity "
          + "(debit/credit) and closing balance per account, optionally broken down by an "
          + "accounting dimension");

      JSONArray params = new JSONArray();
      for (NeoReportParam declared : reportParameters().orElse(List.of())) {
        params.put(param(declared));
      }
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building trial balance report descriptor", e);
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

      String orgId = resolveOrgId(body);
      if (!orgId.isEmpty() && !isValidId(orgId)) {
        return actionableError(400, "org_id_invalid",
            "orgId '" + orgId + "' does not look like a valid Etendo organization id.",
            "Pass a real C_Organization id, or omit orgId to use the session's organization, or "
                + "pass an empty string to report on every organization.");
      }
      if (!orgId.isEmpty() && OBDal.getInstance().get(Organization.class, orgId) == null) {
        return actionableError(400, "organization_not_resolved",
            "Could not resolve organization " + orgId + " for the trial balance report.",
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

      String accountLevel = body.optString(PARAM_ACCOUNT_LEVEL, LEVEL_SUBACCOUNT);
      if (!List.of("C", "D", "E", "S").contains(accountLevel)) {
        return actionableError(400, "account_level_invalid",
            "accountLevel '" + accountLevel + "' is not one of C, D, E, S.",
            "Use one of: C (Account), D (Breakdown), E (Heading), S (Subaccount).");
      }

      String groupBy = body.optString(PARAM_GROUP_BY, "");
      if (!groupBy.isEmpty() && !GROUP_BY_HAS_ID.containsKey(groupBy)) {
        return actionableError(400, "group_by_invalid",
            "groupBy '" + groupBy + "' is not one of " + GROUP_BY_HAS_ID.keySet() + ".",
            "Use one of: bpartner, product, project, costcenter, or omit groupBy for no breakdown.");
      }
      boolean grouped = !groupBy.isEmpty();

      boolean openingEntryAmount = body.optBoolean(PARAM_OPENING_ENTRY_AMOUNT, true);

      List<String> bPartnerIds = parseIds(body.optString(PARAM_BPARTNER_ID, ""));
      List<String> productIds = parseIds(body.optString(PARAM_PRODUCT_ID, ""));
      List<String> projectIds = parseIds(body.optString(PARAM_PROJECT_ID, ""));
      List<String> costCenterIds = parseIds(body.optString(PARAM_COST_CENTER_ID, ""));
      String fromAccountId = body.optString(PARAM_FROM_ACCOUNT_ID, "");
      String toAccountId = body.optString(PARAM_TO_ACCOUNT_ID, "");

      String clientId = OBContext.getOBContext().getCurrentClient().getId();

      List<Object[]> rawRows = queryFineGrainRows(clientId, orgId, acctSchemaId, dateFrom, dateTo,
          openingEntryAmount, accountLevel, bPartnerIds, productIds, projectIds, costCenterIds,
          fromAccountId, toAccountId);

      List<TrialBalanceFolding.Row> foldingRows = toFoldingRows(rawRows, groupBy);
      List<TrialBalanceFolding.FoldedRow> folded = TrialBalanceFolding.fold(foldingRows, grouped);

      JSONArray data = buildDataArray(folded, grouped);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());
      responseData.put("meta", buildMeta(dateFrom, dateTo, orgId, acctSchemaId, accountLevel,
          groupBy, openingEntryAmount, fromAccountId, toAccountId, bPartnerIds, productIds,
          projectIds, costCenterIds));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing trial balance report", e);
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

  // -------------------------------------------------------------------------
  // Parameter resolution
  // -------------------------------------------------------------------------

  /**
   * Resolves {@code orgId}: an explicit value (including an explicit empty string, meaning "every
   * organization") is honored as-is; an OMITTED key defaults to the session's current
   * organization, same convention as {@link AgingReportHandler#resolveOrgId}. See this class's
   * javadoc for why this is the one deliberate deviation from a byte-for-byte port of the Node
   * placeholder semantics.
   */
  private static String resolveOrgId(JSONObject body) {
    if (body.has(PARAM_ORG_ID)) {
      return body.optString(PARAM_ORG_ID, "");
    }
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  /**
   * Resolves {@code acctSchemaId}: an explicit value is used as-is; otherwise resolved from
   * {@code orgId}'s general ledger, mirroring {@link AgingReportHandler#resolveAcctSchemaForOrg}
   * (own {@code Organization.getGeneralLedger()} FK first, then the {@code OrganizationAcctSchema}
   * link-table fallback). When {@code orgId} is blank (client-wide report), falls back to the
   * client's default accounting schema via the same link-table query with no organization filter.
   */
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
  // SQL — faithful port of artifacts/report-trial-balance/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  /**
   * Runs the trial balance's WITH-RECURSIVE query with real bind parameters, always at the finest
   * (account × contact × product × project × cost center) grain — {@link TrialBalanceFolding}
   * folds it down to whatever grain the caller actually asked for. Faithful to the Node contract's
   * {@code sql.query} except: (1) every value is a real bind parameter, never string-concatenated;
   * (2) the client/org scoping is resolved explicitly in Java rather than via the Node engines'
   * regex-based {@code AD_CLIENT_ID IN (...)}/{@code AD_ORG_ID IN (...)} force-rewrite (see class
   * javadoc for the org-filter semantics this reproduces); (3) the original SQL's
   * {@code ('__X__' = '' OR col = '__X__')} optional-filter pattern is replaced with conditional
   * WHERE-clause construction, never with a blank-string bind compared against a real column.
   */
  @SuppressWarnings("unchecked")
  private List<Object[]> queryFineGrainRows(String clientId, String orgId, String acctSchemaId,
      LocalDate dateFrom, LocalDate dateTo, boolean openingEntryAmount, String accountLevel,
      List<String> bPartnerIds, List<String> productIds, List<String> projectIds,
      List<String> costCenterIds, String fromAccountId, String toAccountId) {

    StringBuilder sql = new StringBuilder(
        "WITH RECURSIVE acct_tree AS ( "
            + "  SELECT n.node_id, n.parent_id FROM ad_treenode n "
            + "  WHERE n.ad_tree_id = COALESCE( "
            + "    (SELECT e.ad_tree_id FROM c_acctschema_element ase JOIN c_element e "
            + "       ON e.c_element_id = ase.c_element_id "
            + "     WHERE ase.c_acctschema_id = :acctSchemaId AND ase.elementtype = 'AC' LIMIT 1), "
            + "    (SELECT t.ad_tree_id FROM ad_tree t WHERE t.treetype = 'EV' AND t.isactive = 'Y' "
            + "       AND t.ad_client_id = :clientId LIMIT 1)) "
            + "), "
            + "base AS ( "
            + "  SELECT fa.account_id, "
            + "    bp.c_bpartner_id AS bpartner_id, bp.name AS bpname, "
            + "    p.m_product_id AS product_id, p.name AS productname, "
            + "    pj.c_project_id AS project_id, pj.name AS projectname, "
            + "    cc.c_costcenter_id AS costcenter_id, cc.name AS costcentername, "
            + "    COALESCE(SUM(CASE WHEN fa.dateacct < :dateFrom "
            + "        OR (fa.dateacct = :dateFrom AND fa.factaccttype = 'O' AND :openingEntryAmount = true) "
            + "      THEN fa.amtacctdr - fa.amtacctcr ELSE 0 END), 0) AS opening_balance, "
            + "    COALESCE(SUM(CASE WHEN fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "        AND NOT (fa.dateacct = :dateFrom AND fa.factaccttype = 'O' AND :openingEntryAmount = true) "
            + "      THEN fa.amtacctdr ELSE 0 END), 0) AS activity_debit, "
            + "    COALESCE(SUM(CASE WHEN fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "        AND NOT (fa.dateacct = :dateFrom AND fa.factaccttype = 'O' AND :openingEntryAmount = true) "
            + "      THEN fa.amtacctcr ELSE 0 END), 0) AS activity_credit, "
            + "    COALESCE(SUM(CASE WHEN fa.dateacct <= :dateTo THEN fa.amtacctdr - fa.amtacctcr "
            + "      ELSE 0 END), 0) AS closing_balance "
            + "  FROM fact_acct fa "
            + "  JOIN c_elementvalue lev ON lev.c_elementvalue_id = fa.account_id "
            + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = fa.c_bpartner_id "
            + "  LEFT JOIN m_product p ON p.m_product_id = fa.m_product_id "
            + "  LEFT JOIN c_project pj ON pj.c_project_id = fa.c_project_id "
            + "  LEFT JOIN c_costcenter cc ON cc.c_costcenter_id = fa.c_costcenter_id "
            + "  WHERE fa.ad_client_id = :clientId "
            + "    AND fa.factaccttype NOT IN ('R', 'C') "
            + "    AND fa.dateacct <= :dateTo "
            + "    AND fa.c_acctschema_id = :acctSchemaId ");

    if (!orgId.isEmpty()) {
      sql.append("    AND fa.ad_org_id = :orgId ");
    }
    if (!bPartnerIds.isEmpty()) {
      sql.append("    AND fa.c_bpartner_id IN (:bPartnerIds) ");
    }
    if (!productIds.isEmpty()) {
      sql.append("    AND fa.m_product_id IN (:productIds) ");
    }
    if (!projectIds.isEmpty()) {
      sql.append("    AND fa.c_project_id IN (:projectIds) ");
    }
    if (!costCenterIds.isEmpty()) {
      sql.append("    AND fa.c_costcenter_id IN (:costCenterIds) ");
    }
    if (!fromAccountId.isEmpty()) {
      sql.append("    AND lev.value >= :fromAccountId ");
    }
    if (!toAccountId.isEmpty()) {
      sql.append("    AND lev.value <= :toAccountId ");
    }

    sql.append(
        "  GROUP BY fa.account_id, bp.c_bpartner_id, bp.name, p.m_product_id, p.name, "
            + "    pj.c_project_id, pj.name, cc.c_costcenter_id, cc.name "
            + "), "
            + "acct_anc AS ( "
            + "  SELECT DISTINCT b.account_id AS leaf_id, b.account_id AS anc_id FROM base b "
            + "  UNION "
            + "  SELECT a.leaf_id, t.parent_id FROM acct_anc a JOIN acct_tree t ON t.node_id = a.anc_id "
            + "  WHERE t.parent_id IS NOT NULL AND t.parent_id <> '0' "
            + "), "
            + "elem_up AS ( "
            + "  SELECT t.node_id AS start_id, t.parent_id AS anc_id FROM acct_tree t "
            + "  WHERE t.parent_id IS NOT NULL AND t.parent_id <> '0' "
            + "  UNION "
            + "  SELECT e.start_id, t.parent_id FROM elem_up e JOIN acct_tree t ON t.node_id = e.anc_id "
            + "  WHERE t.parent_id IS NOT NULL AND t.parent_id <> '0' "
            + ") "
            + "SELECT ev.value AS account_no, ev.c_elementvalue_id AS account_id, ev.name AS account_name, "
            + "  b.bpartner_id, b.bpname, b.product_id, b.productname, b.project_id, b.projectname, "
            + "  b.costcenter_id, b.costcentername, "
            + "  SUM(b.opening_balance) AS opening_balance, SUM(b.activity_debit) AS activity_debit, "
            + "  SUM(b.activity_credit) AS activity_credit, SUM(b.closing_balance) AS closing_balance "
            + "FROM base b "
            + "JOIN acct_anc a ON a.leaf_id = b.account_id "
            + "JOIN c_elementvalue ev ON ev.c_elementvalue_id = a.anc_id "
            + "WHERE ev.elementlevel = :accountLevel "
            + "GROUP BY ev.value, ev.c_elementvalue_id, ev.name, b.bpartner_id, b.bpname, b.product_id, "
            + "  b.productname, b.project_id, b.projectname, b.costcenter_id, b.costcentername "
            + "ORDER BY ev.value");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    query.setParameter("clientId", clientId);
    query.setParameter("acctSchemaId", acctSchemaId);
    query.setParameter("dateFrom", Date.valueOf(dateFrom));
    query.setParameter("dateTo", Date.valueOf(dateTo));
    query.setParameter("openingEntryAmount", openingEntryAmount);
    query.setParameter("accountLevel", accountLevel);
    if (!orgId.isEmpty()) {
      query.setParameter("orgId", orgId);
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
      query.setParameter("fromAccountId", fromAccountId);
    }
    if (!toAccountId.isEmpty()) {
      query.setParameter("toAccountId", toAccountId);
    }

    return query.list();
  }

  // -------------------------------------------------------------------------
  // Row mapping / response building
  // -------------------------------------------------------------------------

  /** SQL column order, matching the SELECT list in {@link #queryFineGrainRows}. */
  private static final int COL_ACCOUNT_NO = 0;
  private static final int COL_ACCOUNT_ID = 1;
  private static final int COL_ACCOUNT_NAME = 2;
  private static final int COL_BPARTNER_ID = 3;
  private static final int COL_BPNAME = 4;
  private static final int COL_PRODUCT_ID = 5;
  private static final int COL_PRODUCTNAME = 6;
  private static final int COL_PROJECT_ID = 7;
  private static final int COL_PROJECTNAME = 8;
  private static final int COL_COSTCENTER_ID = 9;
  private static final int COL_COSTCENTERNAME = 10;
  private static final int COL_OPENING_BALANCE = 11;
  private static final int COL_ACTIVITY_DEBIT = 12;
  private static final int COL_ACTIVITY_CREDIT = 13;
  private static final int COL_CLOSING_BALANCE = 14;

  /**
   * Maps each raw SQL row into a {@link TrialBalanceFolding.Row}, resolving {@code dimensionValue}/
   * {@code dimensionId} from whichever grouping dimension {@code groupBy} selects — this is the
   * Java equivalent of {@code report-grouping.js}'s {@code dimensionParam.groupByField}/
   * {@code groupByIdField} lookup against the contract's {@code parameters} array.
   */
  private static List<TrialBalanceFolding.Row> toFoldingRows(List<Object[]> rawRows, String groupBy) {
    List<TrialBalanceFolding.Row> rows = new ArrayList<>();
    for (Object[] r : rawRows) {
      String dimensionValue = null;
      String dimensionId = null;
      switch (groupBy) {
        case "bpartner":
          dimensionValue = str(r[COL_BPNAME]);
          dimensionId = str(r[COL_BPARTNER_ID]);
          break;
        case "product":
          dimensionValue = str(r[COL_PRODUCTNAME]);
          break;
        case "project":
          dimensionValue = str(r[COL_PROJECTNAME]);
          break;
        case "costcenter":
          dimensionValue = str(r[COL_COSTCENTERNAME]);
          break;
        default:
          // No grouping: dimensionValue/dimensionId stay null, TrialBalanceFolding.fold(rows,
          // false) ignores them.
          break;
      }
      rows.add(new TrialBalanceFolding.Row(
          str(r[COL_ACCOUNT_NO]), str(r[COL_ACCOUNT_ID]), str(r[COL_ACCOUNT_NAME]),
          dimensionValue, dimensionId,
          toBigDecimal(r[COL_OPENING_BALANCE]), toBigDecimal(r[COL_ACTIVITY_DEBIT]),
          toBigDecimal(r[COL_ACTIVITY_CREDIT]), toBigDecimal(r[COL_CLOSING_BALANCE])));
    }
    return rows;
  }

  private static JSONArray buildDataArray(List<TrialBalanceFolding.FoldedRow> folded, boolean grouped)
      throws Exception {
    JSONArray data = new JSONArray();
    for (TrialBalanceFolding.FoldedRow r : folded) {
      JSONObject row = new JSONObject();
      row.put("account_no", r.accountNo);
      row.put("account_id", r.accountId);
      row.put("account_name", r.accountName);
      if (grouped) {
        row.put("dimensionValue", r.dimensionValue == null ? "" : r.dimensionValue);
        row.put("dimensionId", r.dimensionId == null ? "" : r.dimensionId);
      }
      row.put("opening_balance", r.openingBalance);
      row.put("activity_debit", r.activityDebit);
      row.put("activity_credit", r.activityCredit);
      row.put("closing_balance", r.closingBalance);
      data.put(row);
    }
    return data;
  }

  private static JSONObject buildMeta(LocalDate dateFrom, LocalDate dateTo, String orgId,
      String acctSchemaId, String accountLevel, String groupBy, boolean openingEntryAmount,
      String fromAccountId, String toAccountId, List<String> bPartnerIds, List<String> productIds,
      List<String> projectIds, List<String> costCenterIds) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_DATE_FROM, dateFrom.format(DATE_FORMATTER));
    meta.put(PARAM_DATE_TO, dateTo.format(DATE_FORMATTER));
    meta.put(PARAM_ORG_ID, orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
    meta.put(PARAM_ACCOUNT_LEVEL, accountLevel);
    meta.put(PARAM_GROUP_BY, groupBy);
    meta.put(PARAM_OPENING_ENTRY_AMOUNT, openingEntryAmount);
    meta.put(PARAM_FROM_ACCOUNT_ID, fromAccountId);
    meta.put(PARAM_TO_ACCOUNT_ID, toAccountId);
    meta.put(PARAM_BPARTNER_ID, String.join(",", bPartnerIds));
    meta.put(PARAM_PRODUCT_ID, String.join(",", productIds));
    meta.put(PARAM_PROJECT_ID, String.join(",", projectIds));
    meta.put(PARAM_COST_CENTER_ID, String.join(",", costCenterIds));
    return meta;
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
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
