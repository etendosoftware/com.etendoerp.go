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
import java.util.List;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Shared plumbing of the native-SQL MCP report handlers ported in ETP-5483 ({@link
 * TrialBalanceReportHandler}, {@link JournalEntriesReportHandler}, {@link
 * GeneralLedgerReportHandler}, and — through {@link AbstractAccountTreeReportHandler} — {@link
 * BalanceSheetReportHandler} and {@link ProfitLossReportHandler}).
 *
 * <p>What lives here is exactly what those five handlers used to repeat verbatim: the {@code
 * handle()} dispatch (access gate → 403, GET → descriptor, POST → execute, anything else → 405),
 * the descriptor rendering from {@link #reportParameters()}, the actionable error envelope, the
 * date/id validation helpers, the {@code orgId}/{@code acctSchemaId} resolution and the raw SQL
 * value converters. Each concrete handler keeps what is genuinely its own: its {@code @Named}
 * qualifier, its {@link #reportParameters()} contract, its {@link #isAccessibleForCurrentRole()}
 * rule, and its execute logic/SQL.
 *
 * <p><b>Not a bean.</b> This class is abstract and carries no {@code @Named}; {@code @Named} is
 * not {@code @Inherited}, so every concrete subclass must keep its own (see {@code
 * docs/neo-headless-extensibility.md} §2.2 — and never a normal scope on the subclass either).
 * {@code reportParameters()}/{@code isAccessibleForCurrentRole()} are deliberately NOT declared
 * here: {@code ReportHandlerAccessDeclarationTest} requires each report handler to make its own
 * access decision.
 */
abstract class AbstractSqlReportHandler implements NeoHandler {

  /** Only for the static {@link #actionableError} fallback path; everything else logs via {@link #log}. */
  private static final Logger ENVELOPE_LOG = LogManager.getLogger(AbstractSqlReportHandler.class);

  static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  /** Etendo ids are either a legacy numeric string or a 32-char hex/alnum id — never blank, never whitespace. */
  private static final Pattern ID_SHAPE = Pattern.compile("^[0-9A-Za-z]{1,32}$");

  /** Native-query bind name for the client id — not a report-facing parameter (unlike the PARAM_* below). */
  static final String SQL_PARAM_CLIENT_ID = "clientId";
  static final String PARAM_DATE_FROM = "dateFrom";
  static final String PARAM_DATE_TO = "dateTo";
  static final String PARAM_ORG_ID = "orgId";
  static final String PARAM_ACCT_SCHEMA_ID = "acctSchemaId";
  static final String PARAM_ACCOUNT_LEVEL = "accountLevel";
  static final String PARAM_GROUP_BY = "groupBy";
  static final String PARAM_SHOW_DIMENSIONS = "showDimensions";
  static final String PARAM_FROM_ACCOUNT_ID = "fromAccountId";
  static final String PARAM_TO_ACCOUNT_ID = "toAccountId";
  static final String PARAM_BPARTNER_ID = "bPartnerId";
  static final String PARAM_PRODUCT_ID = "productId";
  static final String PARAM_PROJECT_ID = "projectId";
  static final String PARAM_COST_CENTER_ID = "costCenterId";

  /** Logger named after the concrete handler, so log categories stay what they were per report. */
  private final Logger log = LogManager.getLogger(getClass());

  /** The descriptor's {@code name}, e.g. {@code "Trial Balance"}. */
  abstract String reportName();

  /** The descriptor's {@code description}. */
  abstract String reportDescription();

  /**
   * The lower-case report label used in log lines and in the {@code organization_not_resolved}
   * detail ({@code "Could not resolve organization X for the <label> report."}), e.g. {@code
   * "trial balance"}.
   */
  abstract String reportLabel();

  /**
   * Runs the report for a POST. Any exception is logged and answered with a 500 by {@link
   * #handle}, exactly like the per-handler try/catch this replaces.
   *
   * @param body the request body, never {@code null} (an absent body is an empty object)
   * @return the response, or an actionable error
   * @throws Exception anything the report's SQL/JSON building throws
   */
  abstract NeoResponse executeReport(JSONObject body) throws Exception;

  // -------------------------------------------------------------------------
  // NeoHandler entry point
  // -------------------------------------------------------------------------

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!isAccessibleForCurrentRole()) {
      return NeoResponse.error(403, "Access denied");
    }
    String method = context.getHttpMethod();
    if ("GET".equals(method)) {
      return describeReport();
    }
    if ("POST".equals(method)) {
      return runReport(context);
    }
    return NeoResponse.error(405, "Method not allowed");
  }

  /** The GET descriptor: {@code name}, {@code description}, then {@code parameters}, in that order. */
  private NeoResponse describeReport() {
    try {
      return NeoResponse.ok(new JSONObject()
          .put("name", reportName())
          .put("description", reportDescription())
          .put("parameters", describeParameters()));
    } catch (Exception e) {
      log.error("Error building {} report descriptor", reportLabel(), e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  /** Renders {@link #reportParameters()}, the single declaration of the report's contract. */
  private JSONArray describeParameters() throws JSONException {
    JSONArray params = new JSONArray();
    for (NeoReportParam declared : reportParameters().orElse(List.of())) {
      params.put(describeParameter(declared));
    }
    return params;
  }

  private static JSONObject describeParameter(NeoReportParam declared) throws JSONException {
    JSONObject p = new JSONObject()
        .put("name", declared.getName())
        .put("type", declared.getType())
        .put("required", declared.isRequired())
        .put("description", declared.getDescription());
    if (!declared.getAllowedValues().isEmpty()) {
      p.put("allowedValues", new JSONArray(declared.getAllowedValues()));
    }
    return p;
  }

  private NeoResponse runReport(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody() == null ? new JSONObject() : context.getRequestBody();
      return executeReport(body);
    } catch (Exception e) {
      log.error("Error executing {} report", reportLabel(), e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  /**
   * The {@code {"response": {"data", "count", "meta"}}} envelope every one of these reports
   * answers with, keys in that order.
   */
  static NeoResponse reportResponse(JSONArray data, int count, JSONObject meta) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    responseData.put("count", count);
    responseData.put("meta", meta);

    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return NeoResponse.ok(wrapper);
  }

  /**
   * Puts a line's four accounting-dimension names ({@code bpname, productname, projectname,
   * costcentername}, in that order, {@code null} as {@code ""}) — the {@code showDimensions}
   * block of the journal entries and general ledger line JSON.
   */
  static void putDimensionNames(JSONObject target, DimensionedAmounts line) throws JSONException {
    target.put("bpname", orEmpty(line.bpname));
    target.put("productname", orEmpty(line.productname));
    target.put("projectname", orEmpty(line.projectname));
    target.put("costcentername", orEmpty(line.costcentername));
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  static NeoResponse actionableError(int status, String errorCode, String detail, String hint) {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put("status", status);
      envelope.put("error", errorCode);
      envelope.put("detail", detail);
      envelope.put("hint", hint);
      return NeoResponse.error(status, envelope);
    } catch (Exception e) {
      ENVELOPE_LOG.warn("Could not build actionable error envelope for '{}': {}", errorCode,
          e.getMessage());
      return NeoResponse.error(status, detail);
    }
  }

  static boolean isValidId(String id) {
    return id != null && ID_SHAPE.matcher(id).matches();
  }

  /**
   * Validates the REQUIRED {@code dateFrom}/{@code dateTo} range of a period report: both
   * present, both {@code yyyy-MM-dd}, {@code dateFrom} not after {@code dateTo}.
   *
   * @return the first violation, or {@code null} when the range is valid
   */
  static NeoResponse validateDateRange(JSONObject body) {
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

  /** Parses a date parameter already checked by {@link #validateDateRange}. */
  static LocalDate parseDate(JSONObject body, String paramName) {
    return LocalDate.parse(body.optString(paramName, ""), DATE_FORMATTER);
  }

  /**
   * Validates a resolved {@code orgId}: blank (every organization) is always valid; otherwise it
   * must look like an Etendo id and resolve to a readable {@link Organization}. The org-scoping
   * semantics (exact org vs org tree) live in each report's SQL, not here.
   *
   * @return the first violation, or {@code null} when {@code orgId} is usable
   */
  NeoResponse validateOrgId(String orgId) {
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
          "Could not resolve organization " + orgId + " for the " + reportLabel() + " report.",
          "Pass a valid orgId (a C_Organization id readable by your role), an empty string for "
              + "every organization, or omit it to use the session's current organization.");
    }
    return null;
  }

  /**
   * @return a 422 {@code accounting_schema_unresolved} when {@link #resolveAcctSchemaId} found
   *     nothing, or {@code null} when {@code acctSchemaId} is usable
   */
  static NeoResponse validateAcctSchemaResolved(String acctSchemaId, String orgId) {
    if (acctSchemaId == null || acctSchemaId.isEmpty()) {
      return actionableError(422, "accounting_schema_unresolved",
          "Could not resolve an accounting schema for organization "
              + (orgId.isEmpty() ? "(none — client-wide)" : orgId) + ".",
          "Check that the organization (or an ancestor in its tree) has a general ledger "
              + "configured, or pass acctSchemaId explicitly.");
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Parameter resolution
  // -------------------------------------------------------------------------

  /**
   * Resolves {@code orgId}: an explicit value (including an explicit empty string, meaning "every
   * organization") is honored as-is; an OMITTED key defaults to the session's current
   * organization, same convention as {@link AgingReportHandler}. See {@link
   * TrialBalanceReportHandler}'s class javadoc for why this is the one deliberate deviation from a
   * byte-for-byte port of the Node placeholder semantics.
   */
  static String resolveOrgId(JSONObject body) {
    if (body.has(PARAM_ORG_ID)) {
      return body.optString(PARAM_ORG_ID, "");
    }
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  /**
   * Resolves {@code acctSchemaId}: an explicit value is used as-is; otherwise resolved from
   * {@code orgId}'s general ledger, mirroring {@link AgingReportHandler}'s own resolution (own
   * {@code Organization.getGeneralLedger()} FK first, then the {@code OrganizationAcctSchema}
   * link-table fallback). When {@code orgId} is blank (client-wide report), falls back to the
   * client's default accounting schema via the same link-table query with no organization filter.
   */
  String resolveAcctSchemaId(JSONObject body, String orgId) {
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

  // -------------------------------------------------------------------------
  // Raw SQL value converters
  // -------------------------------------------------------------------------

  static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  static BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(String.valueOf(value));
  }

  static long toLong(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  static int toInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  static boolean toBoolean(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String s = String.valueOf(value);
    return "Y".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
  }

  /**
   * Renders {@code fact_acct.dateacct} as a plain {@code yyyy-MM-dd} calendar date, the same format
   * every other report date in these responses uses.
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
}
