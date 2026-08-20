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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.data.FieldProvider;
import org.openbravo.erpCommon.ad_reports.AgingDao;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * NeoHandler that wraps the existing AgingDao to return aging schedule data as JSON.
 * Reuses all existing Etendo business logic for aging bucket calculations.
 *
 * URL patterns:
 *   GET  /sws/neo/aging-report           → describe parameters
 *   POST /sws/neo/aging-report           → execute and return JSON rows
 *
 * The POST body parameters are declared once in {@link #reportParameters()} and rendered from
 * there by the GET descriptor and by the MCP {@code generate_*} tool schema. This comment used to
 * list them a third time and had drifted from all of them.
 */
@Named("agingReportHandler")
public class AgingReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AgingReportHandler.class);

  private static final String DEFAULT_COL1       = "30";
  private static final String DEFAULT_COL2       = "60";
  private static final String DEFAULT_COL3       = "90";
  private static final String DEFAULT_COL4       = "120";
  private static final String BUCKET_SENTINEL    = "99999";
  private static final String DATE_FORMAT        = "yyyy-MM-dd";
  private static final String PARAM_REC_OR_PAY   = "recOrPay";
  private static final String PARAM_CURRENT_DATE = "currentDate";
  private static final String PARAM_COLUMN1      = "column1";
  private static final String PARAM_COLUMN2      = "column2";
  private static final String PARAM_COLUMN3      = "column3";
  private static final String PARAM_COLUMN4      = "column4";
  private static final String PARAM_BP_ID        = "bPartnerId";
  private static final String PARAM_ORG_ID       = "orgId";
  private static final String PARAM_SHOW_DETAILS = "showDetails";
  private static final String PARAM_GL_ID        = "glId";

  /**
   * OBUIAPP process id for the classic "Aging Balance Process Definition for Receivables"
   * process. This is the process this spec must gate access on — confirmed via the real
   * {@code AD_Menu.em_obuiapp_process_id} foreign key on {@code AD_Menu} row
   * {@code CC226771DE354AEEAA5D69F696F1A676} ("Aging Balance Process Definition for
   * Receivables"), not by name-matching. Do not repoint this constant at a different-looking
   * process without re-confirming that same FK chain (ETP-4510, follow-up to BUG-3).
   */
  private static final String AGING_RECEIVABLE_PROCESS_ID = "0D37A9F6109549DEB058373EF2DAEB6A";

  // -------------------------------------------------------------------------
  // Inner value types
  // -------------------------------------------------------------------------

  /**
   * Holds the resolved aging bucket boundaries and active bucket count.
   * col1-4 are the effective SQL date boundaries (may include BUCKET_SENTINEL for unused slots).
   * col1Raw-col4Raw are the display values (never the sentinel).
   */
  private static class BucketConfig {
    final String col1Raw;
    final String col2Raw;
    final String col3Raw;
    final String col4Raw;
    final int activeBuckets;

    BucketConfig(String col1Raw, String col2Raw, String col3Raw, String col4Raw, int activeBuckets) {
      this.col1Raw = col1Raw;
      this.col2Raw = col2Raw;
      this.col3Raw = col3Raw;
      this.col4Raw = col4Raw;
      this.activeBuckets = activeBuckets;
    }

    String col1() { return col1Raw; }
    String col2() { return activeBuckets >= 2 ? col2Raw : BUCKET_SENTINEL; }
    String col3() { return activeBuckets >= 3 ? col3Raw : BUCKET_SENTINEL; }
    String col4() { return activeBuckets >= 4 ? col4Raw : BUCKET_SENTINEL; }
  }

  private static class AcctSchemaResult {
    final String accSchemaId;
    final Currency currency;

    AcctSchemaResult(String accSchemaId, Currency currency) {
      this.accSchemaId = accSchemaId;
      this.currency = currency;
    }
  }

  private static class QueryContext {
    final AgingDao dao;
    final DalConnectionProvider conn;
    final Date currentDate;

    QueryContext(AgingDao dao, DalConnectionProvider conn, Date currentDate) {
      this.dao = dao;
      this.conn = conn;
      this.currentDate = currentDate;
    }
  }

  // -------------------------------------------------------------------------
  // NeoHandler entry point
  // -------------------------------------------------------------------------

  /**
   * The report's input contract (ETP-4793 / IMP-19).
   *
   * <p>This handler already published a parameter list over GET, and that list had drifted from
   * the code: it omitted {@code glId} and {@code showDetails}, both of which
   * {@link #executeReport} reads, and it marked {@code recOrPay} required when the code defaults
   * it to {@code RECEIVABLES}. Two hand-maintained descriptions of one contract is how that
   * happens, so {@link #describeReport} now renders this list instead of repeating it.</p>
   *
   * <p>{@code recOrPay} is declared optional-with-a-default because that is what the code does.
   * It is worth noting that the default is not neutral — omitting it yields the receivables
   * report, not an error — which is exactly why it is declared as a closed set: a near-miss like
   * {@code "receivable"} would otherwise fall through to the same silent default.</p>
   */
  @Override
  public Optional<List<NeoReportParam>> reportParameters() {
    return Optional.of(List.of(
        NeoReportParam.options(PARAM_REC_OR_PAY,
            "Which side to age: RECEIVABLES or PAYABLES (default: RECEIVABLES).",
            List.of("RECEIVABLES", "PAYABLES")),
        NeoReportParam.optional(PARAM_CURRENT_DATE, NeoReportParam.TYPE_DATE,
            "As-of date the buckets are measured from. Default: today."),
        NeoReportParam.optional(PARAM_COLUMN1, NeoReportParam.TYPE_INTEGER,
            "First aging bucket boundary, in days. Default: 30."),
        NeoReportParam.optional(PARAM_COLUMN2, NeoReportParam.TYPE_INTEGER,
            "Second aging bucket boundary, in days. Default: 60."),
        NeoReportParam.optional(PARAM_COLUMN3, NeoReportParam.TYPE_INTEGER,
            "Third aging bucket boundary, in days. Default: 90."),
        NeoReportParam.optional(PARAM_COLUMN4, NeoReportParam.TYPE_INTEGER,
            "Fourth aging bucket boundary, in days. Default: 120."),
        NeoReportParam.optional(PARAM_BP_ID, NeoReportParam.TYPE_STRING,
            "Restrict to these business partners: one C_BPartner id, or several separated by "
                + "commas. Default: every partner."),
        NeoReportParam.optional(PARAM_ORG_ID, NeoReportParam.TYPE_STRING,
            "Organization id whose tree is reported. Default: the session's organization."),
        NeoReportParam.optional(PARAM_GL_ID, NeoReportParam.TYPE_STRING,
            "Accounting schema (C_AcctSchema) id whose currency the amounts are shown in. "
                + "Default: the organization's first accounting schema."),
        NeoReportParam.optional(PARAM_SHOW_DETAILS, NeoReportParam.TYPE_BOOLEAN,
            "Include the per-document detail rows under each partner (default: false).")));
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID)) {
      return NeoResponse.error(403, "Access denied");
    }
    String method = context.getHttpMethod();
    if ("GET".equals(method)) {
      return describeReport();
    }
    if ("POST".equals(method)) {
      return executeReport(context);
    }
    return NeoResponse.error(405, "Method not allowed");
  }

  // -------------------------------------------------------------------------
  // GET — describe parameters
  // -------------------------------------------------------------------------

  private NeoResponse describeReport() {
    try {
      JSONObject desc = new JSONObject();
      desc.put("name", "Aging Report");
      desc.put("description", "Aging schedule for receivables or payables, grouped by business partner");

      // Rendered from reportParameters(), the single declaration of this report's contract.
      // Hand-maintaining a second copy here is what let glId and showDetails go undocumented.
      JSONArray params = new JSONArray();
      for (NeoReportParam declared : reportParameters().orElse(List.of())) {
        params.put(param(declared));
      }
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building aging report descriptor", e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  // -------------------------------------------------------------------------
  // POST — execute report
  // -------------------------------------------------------------------------

  private NeoResponse executeReport(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return NeoResponse.error(400, "Request body is required");
      }

      String recOrPay    = body.optString(PARAM_REC_OR_PAY, "RECEIVABLES");
      String dateStr     = body.optString(PARAM_CURRENT_DATE, "");
      boolean showDetails = body.optBoolean(PARAM_SHOW_DETAILS, false);

      List<String> paidStatus = FIN_Utility.getListPaymentConfirmed();
      if (paidStatus == null) {
        return NeoResponse.error(422,
            "Could not resolve confirmed payment statuses required for the aging report");
      }

      BucketConfig buckets  = resolveBuckets(body);
      String bPartnerId     = buildBpInClause(body.optString(PARAM_BP_ID, ""));
      String orgId          = resolveOrgId(body);
      if (orgId == null || orgId.isEmpty()
          || OBDal.getInstance().get(Organization.class, orgId) == null) {
        return buildActionableError(400, "organization_not_resolved",
            "Could not resolve organization context for the aging report.",
            "Pass a valid orgId (a C_Organization id readable by your role), or omit it to use "
                + "the session's current organization.",
            null);
      }
      Set<String> orgs      = new OrganizationStructureProvider().getChildTree(orgId, true);
      if (orgs == null || orgs.isEmpty()) {
        return buildActionableError(422, "organization_tree_not_resolved",
            "Could not resolve the organization tree required for the aging report.",
            "Verify that orgId identifies an organization within a valid client organization tree.",
            null);
      }
      AcctSchemaResult acct = resolveAcctSchema(body.optString(PARAM_GL_ID, ""), orgId);
      if (acct.accSchemaId == null || acct.accSchemaId.isEmpty() || acct.currency == null) {
        return acctSchemaUnresolvedError(orgId);
      }

      initSessionReportsLimit();

      QueryContext ctx = new QueryContext(new AgingDao(), new DalConnectionProvider(false), resolveDate(dateStr));

      FieldProvider[] data = ctx.dao.getOpenReceivablesAgingSchedule(
          ctx.conn, bPartnerId, acct.accSchemaId, ctx.currentDate,
          buckets.col1(), buckets.col2(), buckets.col3(), buckets.col4(),
          orgId, orgs, recOrPay, false, true
      );

      JSONArray rows = buildSummaryRows(data, buckets.activeBuckets);

      if (showDetails && acct.currency != null) {
        attachDetails(ctx, buckets, bPartnerId, orgs, recOrPay, acct.currency, rows);
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", rows);
      responseData.put("count", rows.length());
      responseData.put("meta", buildMeta(recOrPay, ctx.currentDate, buckets, showDetails));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing aging report", e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  // -------------------------------------------------------------------------
  // Resolution helpers
  // -------------------------------------------------------------------------

  private BucketConfig resolveBuckets(JSONObject body) {
    String c1 = body.optString(PARAM_COLUMN1, "");
    String c2 = body.optString(PARAM_COLUMN2, "");
    String c3 = body.optString(PARAM_COLUMN3, "");
    String c4 = body.optString(PARAM_COLUMN4, "");

    int active;
    if (c1.isEmpty()) {
      active = 4;
      c1 = DEFAULT_COL1;
      c2 = DEFAULT_COL2;
      c3 = DEFAULT_COL3;
      c4 = DEFAULT_COL4;
    } else {
      active = 1;
      if (!c2.isEmpty()) active = 2;
      if (active >= 2 && !c3.isEmpty()) active = 3;
      if (active >= 3 && !c4.isEmpty()) active = 4;
    }
    return new BucketConfig(c1, c2, c3, c4, active);
  }

  private static String buildBpInClause(String bPartnerRaw) {
    if (bPartnerRaw.isEmpty()) {
      return "";
    }
    String[] ids = bPartnerRaw.split(",");
    StringBuilder inClause = new StringBuilder("(");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) inClause.append(",");
      inClause.append("'").append(ids[i].trim().replace("'", "''")).append("'");
    }
    inClause.append(")");
    return inClause.toString();
  }

  private static String resolveOrgId(JSONObject body) {
    String orgId = body.optString(PARAM_ORG_ID, "");
    if (orgId.isEmpty()) {
      orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    }
    return orgId;
  }

  private AcctSchemaResult resolveAcctSchema(String glId, String orgId) {
    String accSchemaId = glId;
    Currency currency = null;
    try {
      OBContext.setAdminMode(true);
      AcctSchema schema = accSchemaId.isEmpty()
          ? resolveAcctSchemaForOrg(orgId)
          : OBDal.getInstance().get(AcctSchema.class, accSchemaId);
      if (schema != null) {
        accSchemaId = schema.getId();
        currency = schema.getCurrency();
      }
    } catch (Exception e) {
      log.warn("Could not resolve accounting schema for org {}", orgId, e);
    } finally {
      OBContext.restorePreviousMode();
    }
    return new AcctSchemaResult(accSchemaId, currency);
  }

  /**
   * Resolves the accounting schema for {@code orgId} when the caller named no explicit
   * {@code glId}.
   *
   * <p>Tries the organization's own general ledger FK first —
   * {@code Organization.getGeneralLedger()} — the same canonical path
   * {@code FinancialAccountAccountingHandler#resolveOwnLedger} already uses for this exact
   * relationship: a plain FK dereference, no subquery, and no dependence on the DAL's
   * readable-organization/client filtering that {@code OBDal.createQuery} applies even under
   * {@code OBContext.setAdminMode(true)}. That filtering is the leading, <b>unconfirmed</b>
   * suspect behind ETP-4918: a live tenant with a verified, active, currency-bearing schema
   * linked to the org both by this FK and by {@code OrganizationAcctSchema} still got a 422 from
   * the subquery below, with no exception logged. Do not treat this comment as proof of that
   * hypothesis — it explains why the FK is tried first, not that it is confirmed to fix anything.
   *
   * <p>The FK result is accepted only when it actually carries a currency — a schema without one
   * is no better than none to the caller. The {@code OrganizationAcctSchema} subquery below is
   * kept exactly as it was, as a fallback for tenants that populate only the link table and not
   * the FK: do not delete it on the assumption that the FK path alone is now sufficient.
   *
   * @param orgId the organization whose accounting schema is being resolved
   * @return the resolved schema, or {@code null} when neither path found one
   */
  private AcctSchema resolveAcctSchemaForOrg(String orgId) {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    AcctSchema ledger = org != null ? org.getGeneralLedger() : null;
    if (ledger != null && ledger.getCurrency() != null) {
      return ledger;
    }
    return OBDal.getInstance()
        .createQuery(AcctSchema.class,
            "exists (from OrganizationAcctSchema oas where oas.accountingSchema=this"
                + " and oas.organization.id=:" + PARAM_ORG_ID + " and oas.active=true)"
                + " and active=true")
        .setNamedParameter(PARAM_ORG_ID, orgId)
        .setMaxResult(1)
        .uniqueResult();
  }

  /**
   * Builds a flat, machine-detectable error envelope — {@code status}/{@code error}/
   * {@code detail} plus optional {@code hint}/{@code seeAlso} — matching the IMP-5 convention the
   * rest of the MCP layer uses ({@code McpToolRouterSupport}, {@code NeoCrudHandler}'s
   * {@code buildReadOnlyFieldRejectedResponse}). Built inline rather than importing
   * {@code McpConstants}: that class is package-private to {@code com.etendoerp.go.mcp} and not
   * accessible from here, same reasoning {@code NeoCrudHandler} documents for its own copy.
   *
   * @param status   the HTTP status code
   * @param errorCode a short machine-readable error code (snake_case)
   * @param detail   what is actually known to be true — never a cause the caller cannot verify
   * @param hint     actionable next step, or {@code null} to omit
   * @param seeAlso  a pointer to a working alternative, or {@code null} to omit
   * @return the NeoResponse carrying the envelope
   */
  private static NeoResponse buildActionableError(int status, String errorCode, String detail,
      String hint, String seeAlso) {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put("status", status);
      envelope.put("error", errorCode);
      envelope.put("detail", detail);
      if (hint != null) {
        envelope.put("hint", hint);
      }
      if (seeAlso != null) {
        envelope.put("seeAlso", seeAlso);
      }
      return NeoResponse.error(status, envelope);
    } catch (Exception e) {
      log.warn("Could not build actionable error envelope for '{}': {}", errorCode, e.getMessage());
      return NeoResponse.error(status, detail);
    }
  }

  private static Date resolveDate(String dateStr) throws ParseException {
    if (dateStr.isEmpty()) {
      return new Date();
    }
    return new SimpleDateFormat(DATE_FORMAT).parse(dateStr);
  }

  private static void initSessionReportsLimit() {
    try {
      VariablesSecureApp vars = RequestContext.get().getVariablesSecureApp();
      if (vars.getSessionObject("reportsLimit") == null) {
        vars.setSessionObject("reportsLimit", 10000);
      }
    } catch (Exception e) {
      log.debug("Could not set reportsLimit in session — AgingDao may use default", e);
    }
  }

  // -------------------------------------------------------------------------
  // Row builders
  // -------------------------------------------------------------------------

  private static JSONArray buildSummaryRows(FieldProvider[] data, int activeBuckets) throws Exception {
    JSONArray rows = new JSONArray();
    if (data == null) {
      return rows;
    }
    for (FieldProvider fp : data) {
      JSONObject row = new JSONObject();
      row.put(PARAM_BP_ID, fp.getField("BPartnerID"));
      row.put("bPartner",  fp.getField("BPartner"));

      BigDecimal daysPlus = toBigDecimal(fp.getField("amount5"));
      if (activeBuckets < 4) daysPlus = daysPlus.add(toBigDecimal(fp.getField("amount4")));
      if (activeBuckets < 3) daysPlus = daysPlus.add(toBigDecimal(fp.getField("amount3")));
      if (activeBuckets < 2) daysPlus = daysPlus.add(toBigDecimal(fp.getField("amount2")));

      row.put("current",     toBigDecimal(fp.getField("amount0")));
      row.put("days30",      toBigDecimal(fp.getField("amount1")));
      row.put("days60",      activeBuckets >= 2 ? toBigDecimal(fp.getField("amount2")) : BigDecimal.ZERO);
      row.put("days90",      activeBuckets >= 3 ? toBigDecimal(fp.getField("amount3")) : BigDecimal.ZERO);
      row.put("days120",     activeBuckets >= 4 ? toBigDecimal(fp.getField("amount4")) : BigDecimal.ZERO);
      row.put("days150plus", daysPlus);
      row.put("total",       toBigDecimal(fp.getField("Total")));
      row.put("credits",     toBigDecimal(fp.getField("credit")));
      row.put("net",         toBigDecimal(fp.getField("net")));
      rows.put(row);
    }
    return rows;
  }

  private static JSONObject buildDocRow(FieldProvider fp, int activeBuckets) throws Exception {
    JSONObject doc = new JSONObject();
    doc.put("invoiceId",    fp.getField("INVOICE_ID"));
    doc.put("docNo",        fp.getField("INVOICE_NUMBER"));
    doc.put("dateInvoiced", fp.getField("INVOICE_DATE"));

    BigDecimal a0 = toBigDecimal(fp.getField("AMOUNT0"));
    BigDecimal a1 = toBigDecimal(fp.getField("AMOUNT1"));
    BigDecimal a2 = toBigDecimal(fp.getField("AMOUNT2"));
    BigDecimal a3 = toBigDecimal(fp.getField("AMOUNT3"));
    BigDecimal a4 = toBigDecimal(fp.getField("AMOUNT4"));
    BigDecimal a5 = toBigDecimal(fp.getField("AMOUNT5"));

    BigDecimal daysPlus = a5;
    if (activeBuckets < 4) daysPlus = daysPlus.add(a4);
    if (activeBuckets < 3) daysPlus = daysPlus.add(a3);
    if (activeBuckets < 2) daysPlus = daysPlus.add(a2);

    doc.put("current",     a0);
    doc.put("days30",      a1);
    doc.put("days60",      activeBuckets >= 2 ? a2 : BigDecimal.ZERO);
    doc.put("days90",      activeBuckets >= 3 ? a3 : BigDecimal.ZERO);
    doc.put("days120",     activeBuckets >= 4 ? a4 : BigDecimal.ZERO);
    doc.put("days150plus", daysPlus);
    return doc;
  }

  private static void attachDetails(QueryContext ctx, BucketConfig buckets,
      String bPartnerId, Set<String> orgs, String recOrPay, Currency currency, JSONArray rows)
      throws Exception {

    FieldProvider[] detailData = ctx.dao.getOpenReceivablesAgingScheduleDetails(
        ctx.conn, ctx.currentDate, new SimpleDateFormat(DATE_FORMAT), currency,
        orgs, recOrPay, buckets.col1(), buckets.col2(), buckets.col3(), buckets.col4(),
        bPartnerId, false, true
    );

    Map<String, JSONArray> docsByBp = groupDetailByBp(detailData, buckets.activeBuckets);

    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      String bpId = row.optString(PARAM_BP_ID, "");
      row.put("docs", docsByBp.getOrDefault(bpId, new JSONArray()));
    }
  }

  private static Map<String, JSONArray> groupDetailByBp(FieldProvider[] detailData, int activeBuckets)
      throws Exception {
    Map<String, JSONArray> docsByBp = new LinkedHashMap<>();
    if (detailData == null) {
      return docsByBp;
    }
    for (FieldProvider fp : detailData) {
      String amount6 = fp.getField("AMOUNT6");
      if (amount6 != null && !amount6.isEmpty()) {
        continue;
      }
      String bpId = fp.getField("BPARTNER");
      if (bpId == null) bpId = "";
      docsByBp.computeIfAbsent(bpId, k -> new JSONArray());
      docsByBp.get(bpId).put(buildDocRow(fp, activeBuckets));
    }
    return docsByBp;
  }

  // -------------------------------------------------------------------------
  // Meta builder
  // -------------------------------------------------------------------------

  private static JSONObject buildMeta(String recOrPay, Date currentDate,
      BucketConfig b, boolean showDetails) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_REC_OR_PAY,   recOrPay);
    meta.put(PARAM_CURRENT_DATE, new SimpleDateFormat(DATE_FORMAT).format(currentDate));
    meta.put(PARAM_COLUMN1, b.col1Raw);
    meta.put(PARAM_COLUMN2, b.activeBuckets >= 2 ? b.col2Raw : "");
    meta.put(PARAM_COLUMN3, b.activeBuckets >= 3 ? b.col3Raw : "");
    meta.put(PARAM_COLUMN4, b.activeBuckets >= 4 ? b.col4Raw : "");
    meta.put("activeBuckets",  b.activeBuckets);
    meta.put("showBucket2",    b.activeBuckets >= 2);
    meta.put("showBucket3",    b.activeBuckets >= 3);
    meta.put("showBucket4",    b.activeBuckets >= 4);
    meta.put("lastBucketLabel", ">" + lastBucketValue(b));
    meta.put(PARAM_SHOW_DETAILS, showDetails);
    return meta;
  }

  private static String lastBucketValue(BucketConfig b) {
    if (b.activeBuckets >= 4) return b.col4Raw;
    if (b.activeBuckets >= 3) return b.col3Raw;
    if (b.activeBuckets >= 2) return b.col2Raw;
    return b.col1Raw;
  }

  // -------------------------------------------------------------------------
  // Utilities
  // -------------------------------------------------------------------------

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

  private static BigDecimal toBigDecimal(String value) {
    if (value == null || value.isEmpty()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * The 422 returned when no accounting schema with a currency could be resolved for the
   * requested organization.
   *
   * <p>ETP-4918: this guard used to assert that no such schema was configured for the
   * organization — a cause it never verified. A live benchmark run hit exactly this 422 against
   * a tenant where an active, currency-bearing schema WAS configured, reachable both through the
   * organization's general ledger and through its accounting-schema assignments, with no
   * exception logged. The claim was simply false. All this guard actually knows is that
   * resolution failed, so it must say that and not why; and it must name the working
   * alternative, because the agent that hit it spent five extra calls reconstructing one by
   * hand.</p>
   *
   * @param orgId the organization whose schema could not be resolved
   * @return the actionable 422 response
   */
  private static NeoResponse acctSchemaUnresolvedError(String orgId) {
    return buildActionableError(422, "accounting_schema_unresolved",
        "Could not resolve an accounting schema with a currency for organization " + orgId + ".",
        "Check that the organization (or an ancestor in its tree) has a general ledger "
            + "configured, or pass glId explicitly to select the accounting schema. To get "
            + "comparable data without a resolved schema, call neo_list on \"sales-invoice\"/"
            + "\"header\" filtering status:\"pending\" and status:\"partial\" — both are "
            + "needed, since a partially collected invoice still owes money.",
        "docs(topic:\"reading records\")");
  }
}
