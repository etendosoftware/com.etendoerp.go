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
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.data.FieldProvider;
import org.openbravo.erpCommon.ad_reports.AgingDao;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler that wraps the existing AgingDao to return aging schedule data as JSON.
 * Reuses all existing Etendo business logic for aging bucket calculations.
 *
 * URL patterns:
 *   GET  /sws/neo/aging-report           → describe parameters
 *   POST /sws/neo/aging-report           → execute and return JSON rows
 *
 * POST body parameters:
 *   recOrPay:    "RECEIVABLES" or "PAYABLES" (required)
 *   currentDate: "yyyy-MM-dd" (optional, defaults to today)
 *   column1-4:   aging bucket boundaries in days (optional, defaults: 30,60,90,120)
 *   bPartnerId:  filter by business partner UUID (optional)
 *   orgId:       filter by organization UUID (optional, defaults to session org)
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
  private static final String TYPE_STRING        = "string";
  private static final String TYPE_INTEGER       = "integer";
  private static final String PARAM_REC_OR_PAY   = "recOrPay";
  private static final String PARAM_CURRENT_DATE = "currentDate";
  private static final String PARAM_COLUMN1      = "column1";
  private static final String PARAM_COLUMN2      = "column2";
  private static final String PARAM_COLUMN3      = "column3";
  private static final String PARAM_COLUMN4      = "column4";
  private static final String PARAM_BP_ID        = "bPartnerId";
  private static final String PARAM_ORG_ID       = "orgId";
  private static final String PARAM_SHOW_DETAILS = "showDetails";

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

  @Override
  public NeoResponse handle(NeoContext context) {
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

      JSONArray params = new JSONArray();
      params.put(param(PARAM_REC_OR_PAY,   TYPE_STRING,  true,  "RECEIVABLES or PAYABLES"));
      params.put(param(PARAM_CURRENT_DATE, "date",       false, "As-of date (yyyy-MM-dd). Defaults to today."));
      params.put(param(PARAM_COLUMN1,      TYPE_INTEGER, false, "First aging bucket boundary in days. Default: 30"));
      params.put(param(PARAM_COLUMN2,      TYPE_INTEGER, false, "Second aging bucket boundary. Default: 60"));
      params.put(param(PARAM_COLUMN3,      TYPE_INTEGER, false, "Third aging bucket boundary. Default: 90"));
      params.put(param(PARAM_COLUMN4,      TYPE_INTEGER, false, "Fourth aging bucket boundary. Default: 120"));
      params.put(param(PARAM_BP_ID,        TYPE_STRING,  false, "Filter by business partner UUID"));
      params.put(param(PARAM_ORG_ID,       TYPE_STRING,  false, "Filter by organization UUID"));
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      return NeoResponse.error(500, e.getMessage());
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

      BucketConfig buckets  = resolveBuckets(body);
      String bPartnerId     = buildBpInClause(body.optString(PARAM_BP_ID, ""));
      String orgId          = resolveOrgId(body);
      Set<String> orgs      = new OrganizationStructureProvider().getChildTree(orgId, true);
      AcctSchemaResult acct = resolveAcctSchema(body.optString("glId", ""), orgId);

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
      return NeoResponse.error(500, "Aging report failed: " + e.getMessage());
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
          ? OBDal.getInstance()
              .createQuery(AcctSchema.class,
                  "exists (from OrganizationAcctSchema oas where oas.accountingSchema=this"
                      + " and oas.organization.id=:" + PARAM_ORG_ID + " and oas.active=true)"
                      + " and active=true")
              .setNamedParameter(PARAM_ORG_ID, orgId)
              .setMaxResult(1)
              .uniqueResult()
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

  private static JSONObject param(String name, String type, boolean required, String description)
      throws Exception {
    JSONObject p = new JSONObject();
    p.put("name", name);
    p.put("type", type);
    p.put("required", required);
    p.put("description", description);
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
}
