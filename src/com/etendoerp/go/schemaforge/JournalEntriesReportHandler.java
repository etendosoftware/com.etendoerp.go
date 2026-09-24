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
 * NeoHandler exposing the "Journal Entries" / "Diario de Asientos" report as an MCP report tool
 * ({@code generate_report_journal_entries}, ETP-5483 slice 3).
 *
 * <p><b>Why this handler exists.</b> Before this class, {@code report-journal-entries} was
 * served ONLY by the two Node report engines (the Vite dev plugin, {@code
 * tools/app-shell/vite-plugins/report-api.js}, and the production {@code
 * tools/report-server/server.js} in {@code schema_forge_core}), which run {@code
 * artifacts/report-journal-entries/report-contract.json}'s {@code sql.query} — a raw SQL string
 * with {@code __PLACEHOLDER__} text substitution — directly against Postgres. Neither engine is
 * reachable from the Java MCP servlet, so an MCP agent could not run this report at all. This
 * handler is a faithful Java port of that same query, using real bind parameters instead of
 * string substitution, plus {@link JournalEntriesGrouping} to nest the flat, line-grain SQL
 * result into one object per journal entry.
 *
 * <p><b>Dual-implementation drift risk — read before changing either side.</b> Same shape as
 * {@link TrialBalanceReportHandler}: this report now has TWO independent implementations of the
 * same business logic — the Node/SQL-placeholder one (this class's source of truth, kept in
 * {@code schema_forge}'s {@code artifacts/report-journal-entries/report-contract.json}) and this
 * Java one. They are NOT structurally linked, and the SPA keeps using the Node path exclusively —
 * this handler is additive, MCP-only. A change to the journal entries SQL (new entry-type toggle,
 * changed document-type resolution, changed dimension joins) in the Node contract MUST be
 * mirrored here by hand, and vice versa.
 *
 * <p><b>Multi-value id parameters.</b> Faithful to how the Node engines' {@code applyPlaceholders}
 * (schema_forge_core's {@code cli/src/report-sql.js}) actually substitutes a multi-select
 * parameter: a comma-joined value like {@code "a,b"} is rewritten from {@code = 'a,b'} into
 * {@code IN ('a','b')} by a generic regex, so {@code bPartnerId}/{@code productId}/{@code
 * projectId}/{@code costCenterId} genuinely support more than one id in the live Node path today.
 * This handler reproduces that with real bind lists ({@code IN (:ids)}), exactly like {@link
 * TrialBalanceReportHandler} already does for its own multi-value parameters — no deviation
 * needed here.
 *
 * <p><b>Locale for {@code document_type} translation.</b> The Node engines substitute {@code
 * __REPORT_LOCALE__} (ETP-5013) with the render's own locale before joining {@code
 * ad_ref_list_trl}. This handler uses the equivalent MCP-side value: {@code
 * OBContext.getOBContext().getLanguage().getLanguage()} — the same GO session language {@code
 * NeoAuthenticator} already resolved from the request's {@code Accept-Language} header, and the
 * same pattern {@code TaxReportHandler} already uses for its own {@code ad_ref_list_trl} join
 * (see that class's {@code buildBaseSql}/{@code language} javadoc).
 *
 * <p><b>Org-filter semantics</b> mirror {@link TrialBalanceReportHandler} exactly (see that
 * class's javadoc for the full reasoning): an explicit {@code orgId} filters EXACTLY that one
 * organization, an explicit empty string reports on every organization of the client, and an
 * OMITTED {@code orgId} defaults to the session's current organization.
 *
 * <p><b>Entry-type toggle fallback.</b> Faithful to the contract's own SQL: when none of {@code
 * showRegularEntries}/{@code showPlClosingEntries}/{@code showClosingEntries}/{@code
 * showOpeningEntries}/{@code showDivideUpEntries} is {@code true}, the query falls back to
 * regular entries only ({@code factaccttype = 'N'}) rather than returning nothing — see {@link
 * #buildEntryTypeFilterSql()}.
 *
 * <p><b>Size safety.</b> This report can return every journal line in the period, so the entries
 * cap is enforced AT THE SQL LEVEL via {@code entry_no <= :limit} on the already-ranked result —
 * never a whole entry cut in half, and never every row pulled into the JVM before truncating. See
 * {@link #PARAM_LIMIT}, {@link #DEFAULT_LIMIT}, {@link #MAX_LIMIT} and {@link #countTotalEntries}.
 */
@Named("journalEntriesReportHandler")
public class JournalEntriesReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(JournalEntriesReportHandler.class);

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
  private static final String PARAM_FACT_ACCT_GROUP_ID = "factAcctGroupId";
  private static final String PARAM_SHOW_DIMENSIONS = "showDimensions";
  private static final String PARAM_SHOW_REGULAR = "showRegularEntries";
  private static final String PARAM_SHOW_PL_CLOSING = "showPlClosingEntries";
  private static final String PARAM_SHOW_CLOSING = "showClosingEntries";
  private static final String PARAM_SHOW_OPENING = "showOpeningEntries";
  private static final String PARAM_SHOW_DIVIDE_UP = "showDivideUpEntries";
  private static final String PARAM_SHOW_ENTRY_DESCRIPTION = "showEntryDescription";
  private static final String PARAM_LIMIT = "limit";

  /** Number of ENTRIES (not lines) returned when the caller does not pass {@code limit}. */
  private static final int DEFAULT_LIMIT = 200;
  /** Hard ceiling on {@code limit}, regardless of what the caller asks for. */
  private static final int MAX_LIMIT = 1000;

  // -------------------------------------------------------------------------
  // Access gate
  // -------------------------------------------------------------------------

  /**
   * Gates on the SAME coarse, category-level anchor the {@code report-viewer} gallery uses for
   * this exact report row — {@link ReportAccessCatalog#FINANCIAL_REPORTS_WINDOW_ID} ("Informes
   * financieros" / Financial Reports). See {@link TrialBalanceReportHandler#isAccessibleForCurrentRole()}'s
   * javadoc for the full evidence trail; identical reasoning applies here, confirmed via the same
   * {@code ReportAccessCatalog.ROWS} entry for {@code report-journal-entries}.
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
            "Lower bound (inclusive) on the account search key (fact_acct.acctvalue), NOT its "
                + "id. Default: no lower bound."),
        NeoReportParam.optional(PARAM_TO_ACCOUNT_ID, NeoReportParam.TYPE_STRING,
            "Upper bound (inclusive) on the account search key (fact_acct.acctvalue), NOT its "
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
        NeoReportParam.optional(PARAM_FACT_ACCT_GROUP_ID, NeoReportParam.TYPE_STRING,
            "Restrict to a single journal entry (fact_acct_group_id). Default: every entry in "
                + "the period."),
        NeoReportParam.optional(PARAM_SHOW_DIMENSIONS, NeoReportParam.TYPE_BOOLEAN,
            "Whether each returned line includes its accounting dimension names (bpname, "
                + "productname, projectname, costcentername). Default: false."),
        NeoReportParam.optional(PARAM_SHOW_REGULAR, NeoReportParam.TYPE_BOOLEAN,
            "Include regular entries (factaccttype='N'). Default: true."),
        NeoReportParam.optional(PARAM_SHOW_PL_CLOSING, NeoReportParam.TYPE_BOOLEAN,
            "Include P&L closing entries (factaccttype='R'). Default: true."),
        NeoReportParam.optional(PARAM_SHOW_CLOSING, NeoReportParam.TYPE_BOOLEAN,
            "Include closing entries (factaccttype='C'). Default: true."),
        NeoReportParam.optional(PARAM_SHOW_OPENING, NeoReportParam.TYPE_BOOLEAN,
            "Include opening entries (factaccttype='O'). Default: true."),
        NeoReportParam.optional(PARAM_SHOW_DIVIDE_UP, NeoReportParam.TYPE_BOOLEAN,
            "Include divide-up entries (factaccttype='D'). Default: true. When ALL five "
                + "show*Entries toggles are explicitly false, the report falls back to regular "
                + "entries only, matching the underlying report contract's own SQL."),
        NeoReportParam.optional(PARAM_SHOW_ENTRY_DESCRIPTION, NeoReportParam.TYPE_BOOLEAN,
            "Whether each entry includes its free-text description. Default: false."),
        NeoReportParam.optional(PARAM_LIMIT, NeoReportParam.TYPE_INTEGER,
            "Maximum number of journal ENTRIES (never a line count) to return, ranked by "
                + "dateacct, fact_acct_group_id. Default: " + DEFAULT_LIMIT + ". Hard max: "
                + MAX_LIMIT + ". When more entries match the filters than this cap, the response "
                + "is truncated (see meta.truncated/meta.totalEntries) — narrow dateFrom/dateTo "
                + "or add an account/dimension filter to see the rest.")));
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
      desc.put("name", "Journal Entries");
      desc.put("description", "Diario de Asientos — every accounting entry posted in the "
          + "period, nested as one object per journal entry with its account lines. Use "
          + "doc_window + doc_record_id (per entry) with neo_get to read the source document.");

      JSONArray params = new JSONArray();
      for (NeoReportParam declared : reportParameters().orElse(List.of())) {
        params.put(param(declared));
      }
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building journal entries report descriptor", e);
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

      // Pure input checks run before anything touches OBContext or the database, so a malformed
      // request is refused without a single query.
      int limit = body.optInt(PARAM_LIMIT, DEFAULT_LIMIT);
      if (limit <= 0) {
        return actionableError(400, "limit_invalid",
            "limit must be a positive integer.",
            "Pass a positive number of entries, or omit limit to use the default (" + DEFAULT_LIMIT
                + ").");
      }
      limit = Math.min(limit, MAX_LIMIT);

      String orgId = resolveOrgId(body);
      if (!orgId.isEmpty() && !isValidId(orgId)) {
        return actionableError(400, "org_id_invalid",
            "orgId '" + orgId + "' does not look like a valid Etendo organization id.",
            "Pass a real C_Organization id, or omit orgId to use the session's organization, or "
                + "pass an empty string to report on every organization.");
      }
      if (!orgId.isEmpty() && OBDal.getInstance().get(Organization.class, orgId) == null) {
        return actionableError(400, "organization_not_resolved",
            "Could not resolve organization " + orgId + " for the journal entries report.",
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

      boolean showDimensions = body.optBoolean(PARAM_SHOW_DIMENSIONS, false);
      boolean showEntryDescription = body.optBoolean(PARAM_SHOW_ENTRY_DESCRIPTION, false);
      boolean showRegular = body.optBoolean(PARAM_SHOW_REGULAR, true);
      boolean showPlClosing = body.optBoolean(PARAM_SHOW_PL_CLOSING, true);
      boolean showClosing = body.optBoolean(PARAM_SHOW_CLOSING, true);
      boolean showOpening = body.optBoolean(PARAM_SHOW_OPENING, true);
      boolean showDivideUp = body.optBoolean(PARAM_SHOW_DIVIDE_UP, true);

      List<String> bPartnerIds = parseIds(body.optString(PARAM_BPARTNER_ID, ""));
      List<String> productIds = parseIds(body.optString(PARAM_PRODUCT_ID, ""));
      List<String> projectIds = parseIds(body.optString(PARAM_PROJECT_ID, ""));
      List<String> costCenterIds = parseIds(body.optString(PARAM_COST_CENTER_ID, ""));
      String factAcctGroupId = body.optString(PARAM_FACT_ACCT_GROUP_ID, "");
      String fromAccountId = body.optString(PARAM_FROM_ACCOUNT_ID, "");
      String toAccountId = body.optString(PARAM_TO_ACCOUNT_ID, "");

      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String language = OBContext.getOBContext().getLanguage().getLanguage();

      EntryTypeFilter entryTypeFilter = new EntryTypeFilter(showRegular, showPlClosing,
          showClosing, showOpening, showDivideUp);

      List<Object[]> rawRows = queryRows(clientId, language, orgId, acctSchemaId, dateFrom, dateTo,
          entryTypeFilter, bPartnerIds, productIds, projectIds, costCenterIds, factAcctGroupId,
          fromAccountId, toAccountId, limit);

      List<JournalEntriesGrouping.Row> foldingRows = toFoldingRows(rawRows);
      List<JournalEntriesGrouping.Entry> entries = JournalEntriesGrouping.nest(foldingRows);

      long totalEntries = countTotalEntries(clientId, orgId, acctSchemaId, dateFrom, dateTo,
          entryTypeFilter, bPartnerIds, productIds, projectIds, costCenterIds, factAcctGroupId,
          fromAccountId, toAccountId);
      boolean truncated = JournalEntriesGrouping.isTruncated(entries.size(), totalEntries);

      JSONArray data = buildDataArray(entries, showDimensions, showEntryDescription);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());
      responseData.put("meta", buildMeta(dateFrom, dateTo, orgId, acctSchemaId, limit, truncated,
          totalEntries, fromAccountId, toAccountId, bPartnerIds, productIds, projectIds,
          costCenterIds));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing journal entries report", e);
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
  // Entry-type toggle SQL — faithful port of report-contract.json's factaccttype clause
  // -------------------------------------------------------------------------

  /**
   * The five {@code show*Entries} booleans, bundled so the SQL-building and count-query methods
   * bind them identically.
   */
  private static final class EntryTypeFilter {
    final boolean showRegular;
    final boolean showPlClosing;
    final boolean showClosing;
    final boolean showOpening;
    final boolean showDivideUp;

    EntryTypeFilter(boolean showRegular, boolean showPlClosing, boolean showClosing,
        boolean showOpening, boolean showDivideUp) {
      this.showRegular = showRegular;
      this.showPlClosing = showPlClosing;
      this.showClosing = showClosing;
      this.showOpening = showOpening;
      this.showDivideUp = showDivideUp;
    }

    void bind(NativeQuery<?> query) {
      query.setParameter("showRegular", showRegular);
      query.setParameter("showPlClosing", showPlClosing);
      query.setParameter("showClosing", showClosing);
      query.setParameter("showOpening", showOpening);
      query.setParameter("showDivideUp", showDivideUp);
    }
  }

  /**
   * Faithful port of the contract's own optional-clause: {@code (('__SHOWREGULARENTRIES__' =
   * 'true' AND fa.factaccttype = 'N') OR ... OR (none of the five is 'true' AND fa.factaccttype =
   * 'N'))} — when every toggle is explicitly false, the report falls back to regular entries only
   * rather than returning nothing.
   */
  private static String buildEntryTypeFilterSql() {
    return "((:showRegular = true AND fa.factaccttype = 'N') "
        + "OR (:showPlClosing = true AND fa.factaccttype = 'R') "
        + "OR (:showClosing = true AND fa.factaccttype = 'C') "
        + "OR (:showOpening = true AND fa.factaccttype = 'O') "
        + "OR (:showDivideUp = true AND fa.factaccttype = 'D') "
        + "OR (:showRegular = false AND :showPlClosing = false AND :showClosing = false "
        + "    AND :showOpening = false AND :showDivideUp = false AND fa.factaccttype = 'N'))";
  }

  // -------------------------------------------------------------------------
  // SQL — faithful port of artifacts/report-journal-entries/report-contract.json's sql.query
  // -------------------------------------------------------------------------

  /** The CASE expression resolving docbasetype for tables with no C_DocType (mirrors the contract). */
  private static final String DOCBASETYPE_FALLBACK_CASE =
      "CASE UPPER(adt.tablename) "
          + "WHEN 'FIN_FINACC_TRANSACTION' THEN 'FAT' "
          + "WHEN 'M_MATCHINV' THEN 'MXI' "
          + "WHEN 'M_INVENTORY' THEN 'MMI' "
          + "WHEN 'A_AMORTIZATION' THEN 'AMZ' "
          + "ELSE NULL END";

  /** The CASE expression resolving {@code doc_window} (mirrors the contract verbatim). */
  private static final String DOC_WINDOW_CASE =
      "CASE "
          + "WHEN UPPER(adt.tablename) = 'C_INVOICE' THEN "
          + "  CASE WHEN dt.issotrx = 'Y' THEN 'sales-invoice' ELSE 'purchase-invoice' END "
          + "WHEN UPPER(adt.tablename) = 'M_INOUT' THEN "
          + "  CASE WHEN dt.isreturn = 'Y' THEN "
          + "    CASE WHEN dt.issotrx = 'Y' THEN 'return-material-receipt' ELSE 'return-to-vendor-shipment' END "
          + "  ELSE "
          + "    CASE WHEN dt.issotrx = 'Y' THEN 'goods-shipment' ELSE 'goods-receipt' END "
          + "  END "
          + "WHEN UPPER(adt.tablename) = 'M_INVENTORY' THEN 'physical-inventory' "
          + "WHEN UPPER(adt.tablename) = 'M_MATCHINV' THEN 'matched-purchase-invoices' "
          + "WHEN UPPER(adt.tablename) = 'A_AMORTIZATION' THEN 'amortization' "
          + "WHEN UPPER(adt.tablename) = 'FIN_FINACC_TRANSACTION' THEN 'financial-account' "
          + "WHEN UPPER(adt.tablename) = 'GL_JOURNAL' THEN 'simple-g-l-journal' "
          + "WHEN UPPER(adt.tablename) = 'FIN_PAYMENT' THEN "
          + "  CASE WHEN dt.issotrx = 'Y' THEN 'payment-in' ELSE 'payment-out' END "
          + "WHEN UPPER(adt.tablename) = 'FIN_RECONCILIATION' THEN 'financial-account' "
          + "ELSE NULL END";

  /** The CASE expression resolving {@code doc_record_id} (mirrors the contract verbatim). */
  private static final String DOC_RECORD_ID_CASE =
      "CASE "
          + "WHEN UPPER(adt.tablename) = 'FIN_FINACC_TRANSACTION' THEN fat.fin_financial_account_id "
          + "WHEN UPPER(adt.tablename) = 'FIN_RECONCILIATION' THEN frec.fin_financial_account_id "
          + "ELSE fa.record_id END";

  /**
   * Runs the journal entries query with real bind parameters, capped to {@code limit} ENTRIES via
   * {@code entry_no <= :limit} on the already dense-ranked result (see class javadoc's "Size
   * safety" note) — never a partial entry, since the cap is applied to the whole-entry rank, not
   * to individual line rows.
   */
  @SuppressWarnings("unchecked")
  private List<Object[]> queryRows(String clientId, String language, String orgId,
      String acctSchemaId, LocalDate dateFrom, LocalDate dateTo, EntryTypeFilter entryTypeFilter,
      List<String> bPartnerIds, List<String> productIds, List<String> projectIds,
      List<String> costCenterIds, String factAcctGroupId, String fromAccountId,
      String toAccountId, int limit) {

    StringBuilder sql = new StringBuilder(
        "WITH je AS ( "
            + "  SELECT fa.dateacct, "
            + "    DENSE_RANK() OVER (ORDER BY fa.dateacct, fa.fact_acct_group_id) AS entry_no, "
            + "    COALESCE(MAX(rlt.name), MAX(rl.name), MAX(dt.name), 'Journal') AS document_type, "
            + "    MAX(COALESCE(dt.docbasetype, " + DOCBASETYPE_FALLBACK_CASE + ")) AS docbasetype, "
            + "    MAX(dt.isreturn) AS isreturn, "
            + "    COALESCE(MAX(fa.description), '') AS entry_description, "
            + "    MAX(bp.name) AS bpname, MAX(p.name) AS productname, MAX(pj.name) AS projectname, "
            + "    MAX(cc.name) AS costcentername, "
            + "    MAX(" + DOC_WINDOW_CASE + ") AS doc_window, "
            + "    MAX(" + DOC_RECORD_ID_CASE + ") AS doc_record_id, "
            + "    MAX(CASE WHEN UPPER(adt.tablename) IN ('FIN_FINACC_TRANSACTION', 'FIN_RECONCILIATION') "
            + "        THEN 'txnAny' ELSE NULL END) AS doc_query_key, "
            + "    MAX(CASE WHEN UPPER(adt.tablename) = 'FIN_RECONCILIATION' THEN "
            + "      (SELECT t.fin_finacc_transaction_id FROM fin_finacc_transaction t "
            + "       WHERE t.fin_reconciliation_id = fa.record_id "
            + "       ORDER BY t.dateacct, t.fin_finacc_transaction_id LIMIT 1) "
            + "      ELSE fa.record_id END) AS doc_query_value, "
            + "    fa.fact_acct_group_id, fa.record_id, fa.ad_table_id, "
            + "    fa.acctvalue AS account_no, fa.acctdescription AS account_name, "
            + "    SUM(fa.amtacctdr) AS amtacctdr, SUM(fa.amtacctcr) AS amtacctcr, "
            + "    MIN(fa.seqno) AS min_seqno "
            + "  FROM fact_acct fa "
            + "  LEFT JOIN c_doctype dt ON dt.c_doctype_id = fa.c_doctype_id "
            + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = fa.c_bpartner_id "
            + "  LEFT JOIN m_product p ON p.m_product_id = fa.m_product_id "
            + "  LEFT JOIN c_project pj ON pj.c_project_id = fa.c_project_id "
            + "  LEFT JOIN c_costcenter cc ON cc.c_costcenter_id = fa.c_costcenter_id "
            + "  LEFT JOIN ad_table adt ON adt.ad_table_id = fa.ad_table_id "
            + "  LEFT JOIN fin_finacc_transaction fat ON fat.fin_finacc_transaction_id = fa.record_id "
            + "  LEFT JOIN fin_reconciliation frec ON frec.fin_reconciliation_id = fa.record_id "
            + "  LEFT JOIN ad_ref_list rl ON rl.ad_reference_id = '183' "
            + "    AND rl.value = COALESCE(dt.docbasetype, " + DOCBASETYPE_FALLBACK_CASE + ") "
            + "  LEFT JOIN ad_ref_list_trl rlt ON rlt.ad_ref_list_id = rl.ad_ref_list_id "
            + "    AND rlt.ad_language = :language "
            + "  WHERE fa.ad_client_id = :clientId "
            + "    AND " + buildEntryTypeFilterSql() + " "
            + "    AND fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
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
    if (!factAcctGroupId.isEmpty()) {
      sql.append("    AND fa.fact_acct_group_id = :factAcctGroupId ");
    }
    if (!fromAccountId.isEmpty()) {
      sql.append("    AND fa.acctvalue >= :fromAccountId ");
    }
    if (!toAccountId.isEmpty()) {
      sql.append("    AND fa.acctvalue <= :toAccountId ");
    }

    sql.append(
        "  GROUP BY fa.fact_acct_group_id, fa.dateacct, fa.acctvalue, fa.acctdescription, "
            + "    fa.record_id, fa.ad_table_id, fa.account_id, fa.factaccttype, "
            + "    (CASE fa.amtacctdr WHEN 0 THEN (CASE SIGN(fa.amtacctcr) WHEN -1 THEN 1 ELSE 2 END) "
            + "      ELSE (CASE SIGN(fa.amtacctdr) WHEN -1 THEN 3 ELSE 4 END) END) "
            + "  HAVING (SUM(fa.amtacctdr) <> 0 OR SUM(fa.amtacctcr) <> 0) "
            + ") "
            + "SELECT dateacct, entry_no, document_type, docbasetype, isreturn, doc_window, "
            + "  doc_record_id, doc_query_key, doc_query_value, entry_description, bpname, "
            + "  productname, projectname, costcentername, fact_acct_group_id, record_id, "
            + "  ad_table_id, account_no, account_name, amtacctdr, amtacctcr "
            + "FROM je "
            + "WHERE entry_no <= :limit "
            + "ORDER BY dateacct, fact_acct_group_id, min_seqno");

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    bindCommonParams(query, clientId, language, orgId, acctSchemaId, dateFrom, dateTo,
        entryTypeFilter, bPartnerIds, productIds, projectIds, costCenterIds, factAcctGroupId,
        fromAccountId, toAccountId);
    query.setParameter("limit", (long) limit);

    return query.list();
  }

  /**
   * Cheap, entries-only count matching the SAME filters as {@link #queryRows} (client, entry-type
   * toggles, period, org, schema, dimensions, account range) — used to populate {@code
   * meta.totalEntries}/{@code meta.truncated} without materializing every line row. Faithful to
   * {@code fact_acct}'s own columns directly (every filter here is a plain FK on {@code fact_acct},
   * no joins needed), so this is cheap even for a wide period.
   *
   * <p><b>Known approximation.</b> This counts distinct {@code fact_acct_group_id} matching the
   * WHERE filters WITHOUT the main query's per-line {@code HAVING (SUM(amtacctdr) <> 0 OR
   * SUM(amtacctcr) <> 0))} — that HAVING drops individual zero-net account LINES, not whole
   * entries, so it can only ever make {@link #queryRows} return fewer or equal distinct entries
   * than this count, never more. In the vanishingly rare case where EVERY line of an entry nets to
   * exactly zero (all its lines dropped), this count is a harmless overcount by one — the response
   * would report a truncation that resolves to nothing once the caller widens the range, rather
   * than silently under-counting.
   */
  @SuppressWarnings("unchecked")
  private long countTotalEntries(String clientId, String orgId, String acctSchemaId,
      LocalDate dateFrom, LocalDate dateTo, EntryTypeFilter entryTypeFilter,
      List<String> bPartnerIds, List<String> productIds, List<String> projectIds,
      List<String> costCenterIds, String factAcctGroupId, String fromAccountId,
      String toAccountId) {

    StringBuilder sql = new StringBuilder(
        "SELECT COUNT(DISTINCT fa.fact_acct_group_id) FROM fact_acct fa "
            + "WHERE fa.ad_client_id = :clientId "
            + "  AND " + buildEntryTypeFilterSql() + " "
            + "  AND fa.dateacct >= :dateFrom AND fa.dateacct <= :dateTo "
            + "  AND fa.c_acctschema_id = :acctSchemaId ");

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
    if (!factAcctGroupId.isEmpty()) {
      sql.append("  AND fa.fact_acct_group_id = :factAcctGroupId ");
    }
    if (!fromAccountId.isEmpty()) {
      sql.append("  AND fa.acctvalue >= :fromAccountId ");
    }
    if (!toAccountId.isEmpty()) {
      sql.append("  AND fa.acctvalue <= :toAccountId ");
    }

    NativeQuery<Number> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
    bindCommonParams(query, clientId, null, orgId, acctSchemaId, dateFrom, dateTo, entryTypeFilter,
        bPartnerIds, productIds, projectIds, costCenterIds, factAcctGroupId, fromAccountId,
        toAccountId);

    Number result = query.uniqueResult();
    return result == null ? 0L : result.longValue();
  }

  private static void bindCommonParams(NativeQuery<?> query, String clientId, String language,
      String orgId, String acctSchemaId, LocalDate dateFrom, LocalDate dateTo,
      EntryTypeFilter entryTypeFilter, List<String> bPartnerIds, List<String> productIds,
      List<String> projectIds, List<String> costCenterIds, String factAcctGroupId,
      String fromAccountId, String toAccountId) {
    query.setParameter("clientId", clientId);
    if (language != null) {
      query.setParameter("language", language);
    }
    query.setParameter("acctSchemaId", acctSchemaId);
    query.setParameter("dateFrom", Date.valueOf(dateFrom));
    query.setParameter("dateTo", Date.valueOf(dateTo));
    entryTypeFilter.bind(query);
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
    if (!factAcctGroupId.isEmpty()) {
      query.setParameter("factAcctGroupId", factAcctGroupId);
    }
    if (!fromAccountId.isEmpty()) {
      query.setParameter("fromAccountId", fromAccountId);
    }
    if (!toAccountId.isEmpty()) {
      query.setParameter("toAccountId", toAccountId);
    }
  }

  // -------------------------------------------------------------------------
  // Row mapping / response building
  // -------------------------------------------------------------------------

  /** SQL column order, matching the SELECT list in {@link #queryRows}. */
  private static final int COL_DATEACCT = 0;
  private static final int COL_ENTRY_NO = 1;
  private static final int COL_DOCUMENT_TYPE = 2;
  private static final int COL_DOCBASETYPE = 3;
  private static final int COL_ISRETURN = 4;
  private static final int COL_DOC_WINDOW = 5;
  private static final int COL_DOC_RECORD_ID = 6;
  private static final int COL_DOC_QUERY_KEY = 7;
  private static final int COL_DOC_QUERY_VALUE = 8;
  private static final int COL_ENTRY_DESCRIPTION = 9;
  private static final int COL_BPNAME = 10;
  private static final int COL_PRODUCTNAME = 11;
  private static final int COL_PROJECTNAME = 12;
  private static final int COL_COSTCENTERNAME = 13;
  private static final int COL_FACT_ACCT_GROUP_ID = 14;
  private static final int COL_RECORD_ID = 15;
  private static final int COL_AD_TABLE_ID = 16;
  private static final int COL_ACCOUNT_NO = 17;
  private static final int COL_ACCOUNT_NAME = 18;
  private static final int COL_AMTACCTDR = 19;
  private static final int COL_AMTACCTCR = 20;

  private static List<JournalEntriesGrouping.Row> toFoldingRows(List<Object[]> rawRows) {
    List<JournalEntriesGrouping.Row> rows = new ArrayList<>();
    for (Object[] r : rawRows) {
      rows.add(new JournalEntriesGrouping.Row(
          toIsoDate(r[COL_DATEACCT]), toLong(r[COL_ENTRY_NO]), str(r[COL_DOCUMENT_TYPE]),
          str(r[COL_DOCBASETYPE]), toBoolean(r[COL_ISRETURN]), str(r[COL_DOC_WINDOW]),
          str(r[COL_DOC_RECORD_ID]), str(r[COL_DOC_QUERY_KEY]), str(r[COL_DOC_QUERY_VALUE]),
          str(r[COL_ENTRY_DESCRIPTION]), str(r[COL_BPNAME]), str(r[COL_PRODUCTNAME]),
          str(r[COL_PROJECTNAME]), str(r[COL_COSTCENTERNAME]), str(r[COL_FACT_ACCT_GROUP_ID]),
          str(r[COL_RECORD_ID]), str(r[COL_AD_TABLE_ID]), str(r[COL_ACCOUNT_NO]),
          str(r[COL_ACCOUNT_NAME]), toBigDecimal(r[COL_AMTACCTDR]), toBigDecimal(r[COL_AMTACCTCR])));
    }
    return rows;
  }

  static JSONArray buildDataArray(List<JournalEntriesGrouping.Entry> entries,
      boolean showDimensions, boolean showEntryDescription) throws Exception {
    JSONArray data = new JSONArray();
    for (JournalEntriesGrouping.Entry e : entries) {
      JSONObject entry = new JSONObject();
      entry.put("entry_no", e.entryNo);
      entry.put("dateacct", e.dateacct);
      entry.put("document_type", e.documentType);
      entry.put("docbasetype", e.docbasetype);
      // document_type is Etendo's own ad_ref_list name for docbasetype, and that reference has no
      // return variant: a vendor return is MMR and a customer return is MMS, like a regular receipt
      // or shipment. isreturn is what tells them apart. The SPA's printed "Detail" label goes
      // further (report-i18n.js relabels a few docbasetypes); that dictionary is deliberately not
      // copied here, so a caller reads the DB name plus these two codes instead.
      entry.put("isreturn", e.isReturn);
      entry.put("doc_window", e.docWindow == null ? "" : e.docWindow);
      entry.put("doc_record_id", e.docRecordId == null ? "" : e.docRecordId);
      entry.put("doc_query_key", e.docQueryKey == null ? "" : e.docQueryKey);
      entry.put("doc_query_value", e.docQueryValue == null ? "" : e.docQueryValue);
      entry.put("record_id", e.recordId);
      entry.put("ad_table_id", e.adTableId);
      if (showEntryDescription) {
        entry.put("entry_description", e.entryDescription);
      }

      JSONArray lines = new JSONArray();
      for (JournalEntriesGrouping.Line line : e.lines) {
        JSONObject l = new JSONObject();
        l.put("account_no", line.accountNo);
        l.put("account_name", line.accountName);
        l.put("amtacctdr", line.amtacctdr);
        l.put("amtacctcr", line.amtacctcr);
        if (showDimensions) {
          l.put("bpname", line.bpname == null ? "" : line.bpname);
          l.put("productname", line.productname == null ? "" : line.productname);
          l.put("projectname", line.projectname == null ? "" : line.projectname);
          l.put("costcentername", line.costcentername == null ? "" : line.costcentername);
        }
        lines.put(l);
      }
      entry.put("lines", lines);

      data.put(entry);
    }
    return data;
  }

  private static JSONObject buildMeta(LocalDate dateFrom, LocalDate dateTo, String orgId,
      String acctSchemaId, int limit, boolean truncated, long totalEntries, String fromAccountId,
      String toAccountId, List<String> bPartnerIds, List<String> productIds,
      List<String> projectIds, List<String> costCenterIds) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_DATE_FROM, dateFrom.format(DATE_FORMATTER));
    meta.put(PARAM_DATE_TO, dateTo.format(DATE_FORMATTER));
    meta.put(PARAM_ORG_ID, orgId);
    meta.put(PARAM_ACCT_SCHEMA_ID, acctSchemaId);
    meta.put(PARAM_LIMIT, limit);
    meta.put("truncated", truncated);
    meta.put("totalEntries", totalEntries);
    if (truncated) {
      meta.put("hint", "Only the first " + limit + " of " + totalEntries + " entries were "
          + "returned. Narrow dateFrom/dateTo, or filter by account/bPartner/product/project/"
          + "costCenter, to see the rest.");
    }
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

  /**
   * Renders {@code fact_acct.dateacct} as a plain {@code yyyy-MM-dd} calendar date, the same format
   * every other report date in this response (and the other report handlers) uses.
   *
   * <p>The column is a {@code timestamp}, so the driver hands back a {@link java.sql.Timestamp}
   * whose {@code toString()} is {@code "2026-01-01 00:00:00.0"}. It is converted through
   * {@link LocalDate} (not a {@code java.util.Date} formatter) so no timezone offset can shift the
   * accounting day.</p>
   *
   * @param value the raw column value, or {@code null}
   * @return the date as {@code yyyy-MM-dd}, or {@code ""} when absent
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

  private static long toLong(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return Long.parseLong(String.valueOf(value));
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
