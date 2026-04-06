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

  private static final String DEFAULT_COL1 = "30";
  private static final String DEFAULT_COL2 = "60";
  private static final String DEFAULT_COL3 = "90";
  private static final String DEFAULT_COL4 = "120";

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

  private NeoResponse describeReport() {
    try {
      JSONObject desc = new JSONObject();
      desc.put("name", "Aging Report");
      desc.put("description", "Aging schedule for receivables or payables, grouped by business partner");

      JSONArray params = new JSONArray();
      params.put(param("recOrPay", "string", true, "RECEIVABLES or PAYABLES"));
      params.put(param("currentDate", "date", false, "As-of date (yyyy-MM-dd). Defaults to today."));
      params.put(param("column1", "integer", false, "First aging bucket boundary in days. Default: 30"));
      params.put(param("column2", "integer", false, "Second aging bucket boundary. Default: 60"));
      params.put(param("column3", "integer", false, "Third aging bucket boundary. Default: 90"));
      params.put(param("column4", "integer", false, "Fourth aging bucket boundary. Default: 120"));
      params.put(param("bPartnerId", "string", false, "Filter by business partner UUID"));
      params.put(param("orgId", "string", false, "Filter by organization UUID"));
      desc.put("parameters", params);

      return NeoResponse.ok(desc);
    } catch (Exception e) {
      return NeoResponse.error(500, e.getMessage());
    }
  }

  private NeoResponse executeReport(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return NeoResponse.error(400, "Request body is required");
      }

      String recOrPay = body.optString("recOrPay", "RECEIVABLES");
      String dateStr = body.optString("currentDate", "");
      boolean showDetails = body.optBoolean("showDetails", false);

      // Detect active buckets — sequential non-empty column values from left.
      // If none are provided, fall back to the standard 4-bucket layout (30/60/90/120).
      String col1Raw = body.optString("column1", "");
      String col2Raw = body.optString("column2", "");
      String col3Raw = body.optString("column3", "");
      String col4Raw = body.optString("column4", "");

      int activeBuckets;
      if (col1Raw.isEmpty()) {
        activeBuckets = 4;
        col1Raw = DEFAULT_COL1;
        col2Raw = DEFAULT_COL2;
        col3Raw = DEFAULT_COL3;
        col4Raw = DEFAULT_COL4;
      } else {
        activeBuckets = 1;
        if (!col2Raw.isEmpty()) activeBuckets = 2;
        if (activeBuckets >= 2 && !col3Raw.isEmpty()) activeBuckets = 3;
        if (activeBuckets >= 3 && !col4Raw.isEmpty()) activeBuckets = 4;
      }

      // Fill unused slots with a large sentinel so SQL buckets collapse into the "plus" bucket
      String col1 = col1Raw;
      String col2 = activeBuckets >= 2 ? col2Raw : "99999";
      String col3 = activeBuckets >= 3 ? col3Raw : "99999";
      String col4 = activeBuckets >= 4 ? col4Raw : "99999";
      // Convert comma-separated IDs to SQL IN format: ('id1','id2') as expected by AgingDaoData
      String bPartnerRaw = body.optString("bPartnerId", "");
      String bPartnerId = "";
      if (!bPartnerRaw.isEmpty()) {
        String[] ids = bPartnerRaw.split(",");
        StringBuilder inClause = new StringBuilder("(");
        for (int i = 0; i < ids.length; i++) {
          if (i > 0) inClause.append(",");
          inClause.append("'").append(ids[i].trim().replace("'", "''")).append("'");
        }
        inClause.append(")");
        bPartnerId = inClause.toString();
      }
      String orgId = body.optString("orgId", "");
      String glId = body.optString("glId", "");

      // Resolve date
      Date currentDate;
      if (dateStr.isEmpty()) {
        currentDate = new Date();
      } else {
        currentDate = new SimpleDateFormat("yyyy-MM-dd").parse(dateStr);
      }

      // Resolve organization
      OBContext obCtx = OBContext.getOBContext();
      if (orgId.isEmpty()) {
        orgId = obCtx.getCurrentOrganization().getId();
      }

      // Build organization tree (org + children only, matching Classic behavior)
      Set<String> organizations = new OrganizationStructureProvider().getChildTree(orgId, true);

      // Resolve accounting schema: use explicit glId if provided, otherwise look up the schema
      // directly linked to the selected org (not inherited from parents)
      String accSchemaId = glId;
      Currency reportCurrency = null;
      try {
        OBContext.setAdminMode(true);
        AcctSchema acctSchema = accSchemaId.isEmpty()
            ? OBDal.getInstance()
                .createQuery(AcctSchema.class,
                    "exists (from OrganizationAcctSchema oas where oas.accountingSchema=this"
                        + " and oas.organization.id=:orgId and oas.active=true)"
                        + " and active=true")
                .setNamedParameter("orgId", orgId)
                .setMaxResult(1)
                .uniqueResult()
            : OBDal.getInstance().get(AcctSchema.class, accSchemaId);
        if (acctSchema != null) {
          accSchemaId = acctSchema.getId();
          reportCurrency = acctSchema.getCurrency();
        }
      } catch (Exception e) {
        log.warn("Could not resolve accounting schema for org {}", orgId, e);
      } finally {
        OBContext.restorePreviousMode();
      }

      // Set session variable required by AgingDao (legacy code expects this in HTTP session)
      try {
        VariablesSecureApp vars = RequestContext.get().getVariablesSecureApp();
        if (vars.getSessionObject("reportsLimit") == null) {
          vars.setSessionObject("reportsLimit", 10000);
        }
      } catch (Exception e) {
        log.debug("Could not set reportsLimit in session — AgingDao may use default", e);
      }

      // Call existing AgingDao
      AgingDao dao = new AgingDao();
      DalConnectionProvider conn = new DalConnectionProvider(false);

      FieldProvider[] data = dao.getOpenReceivablesAgingSchedule(
          conn,
          bPartnerId,
          accSchemaId,
          currentDate,
          col1, col2, col3, col4,
          orgId,
          organizations,
          recOrPay,
          false,  // showDoubtfulDebt
          true    // excludeVoids
      );

      // Convert FieldProvider[] to JSON
      JSONObject response = new JSONObject();
      JSONArray rows = new JSONArray();

      if (data != null) {
        for (FieldProvider fp : data) {
          JSONObject row = new JSONObject();
          row.put("bPartnerId", fp.getField("BPartnerID"));
          row.put("bPartner", fp.getField("BPartner"));
          // Merge unused buckets into daysPlus so amounts are never silently discarded
          BigDecimal daysPlus = toBigDecimal(fp.getField("amount5"));
          if (activeBuckets < 4) daysPlus = daysPlus.add(toBigDecimal(fp.getField("amount4")));
          if (activeBuckets < 3) daysPlus = daysPlus.add(toBigDecimal(fp.getField("amount3")));
          if (activeBuckets < 2) daysPlus = daysPlus.add(toBigDecimal(fp.getField("amount2")));

          row.put("current", toBigDecimal(fp.getField("amount0")));
          row.put("days30", toBigDecimal(fp.getField("amount1")));
          row.put("days60", activeBuckets >= 2 ? toBigDecimal(fp.getField("amount2")) : BigDecimal.ZERO);
          row.put("days90", activeBuckets >= 3 ? toBigDecimal(fp.getField("amount3")) : BigDecimal.ZERO);
          row.put("days120", activeBuckets >= 4 ? toBigDecimal(fp.getField("amount4")) : BigDecimal.ZERO);
          row.put("days150plus", daysPlus);
          row.put("total", toBigDecimal(fp.getField("Total")));
          row.put("credits", toBigDecimal(fp.getField("credit")));
          row.put("net", toBigDecimal(fp.getField("net")));
          rows.put(row);
        }
      }

      // --- Detail mode: fetch document-level rows and attach to each BP summary row ---
      if (showDetails && reportCurrency != null) {
        SimpleDateFormat detailDateFmt = new SimpleDateFormat("yyyy-MM-dd");
        FieldProvider[] detailData = dao.getOpenReceivablesAgingScheduleDetails(
            conn, currentDate, detailDateFmt, reportCurrency,
            organizations, recOrPay,
            col1, col2, col3, col4,
            bPartnerId,
            false,  // showDoubtfulDebt
            true    // excludeVoids
        );

        // Group detail rows by bPartnerId.
        // insertData() puts amounts in AMOUNT0-AMOUNT5 (one non-null per row).
        // Credit rows use AMOUNT6 — skip them here (shown only in BP subtotal from summary).
        Map<String, JSONArray> docsByBp = new LinkedHashMap<>();
        if (detailData != null) {
          for (FieldProvider fp : detailData) {
            // Skip credit rows (AMOUNT6 set by CREDIT_SCOPE=6)
            if (fp.getField("AMOUNT6") != null && !fp.getField("AMOUNT6").isEmpty()) {
              continue;
            }
            String bpId = fp.getField("BPARTNER");
            if (bpId == null) bpId = "";
            docsByBp.computeIfAbsent(bpId, k -> new JSONArray());

            JSONObject doc = new JSONObject();
            doc.put("docNo", fp.getField("INVOICE_NUMBER"));
            doc.put("dateInvoiced", fp.getField("INVOICE_DATE"));

            // Amounts are pre-assigned to bucket fields by insertData()
            BigDecimal a0 = toBigDecimal(fp.getField("AMOUNT0"));
            BigDecimal a1 = toBigDecimal(fp.getField("AMOUNT1"));
            BigDecimal a2 = toBigDecimal(fp.getField("AMOUNT2"));
            BigDecimal a3 = toBigDecimal(fp.getField("AMOUNT3"));
            BigDecimal a4 = toBigDecimal(fp.getField("AMOUNT4"));
            BigDecimal a5 = toBigDecimal(fp.getField("AMOUNT5"));

            // Merge overflow buckets into daysPlus
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

            docsByBp.get(bpId).put(doc);
          }
        }

        // Attach docs to each summary row
        for (int i = 0; i < rows.length(); i++) {
          JSONObject row = rows.getJSONObject(i);
          String bpId = row.optString("bPartnerId", "");
          JSONArray docs = docsByBp.getOrDefault(bpId, new JSONArray());
          row.put("docs", docs);
        }
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", rows);
      responseData.put("count", rows.length());

      // Determine label for the last (plus) bucket
      String lastColValue = activeBuckets >= 4 ? col4Raw
          : activeBuckets >= 3 ? col3Raw
          : activeBuckets >= 2 ? col2Raw
          : col1Raw;

      JSONObject meta = new JSONObject();
      meta.put("recOrPay", recOrPay);
      meta.put("currentDate", new SimpleDateFormat("yyyy-MM-dd").format(currentDate));
      // Store display values (never the 99999 sentinel)
      meta.put("column1", col1Raw);
      meta.put("column2", activeBuckets >= 2 ? col2Raw : "");
      meta.put("column3", activeBuckets >= 3 ? col3Raw : "");
      meta.put("column4", activeBuckets >= 4 ? col4Raw : "");
      // Bucket visibility flags for the template
      meta.put("activeBuckets", activeBuckets);
      meta.put("showBucket2", activeBuckets >= 2);
      meta.put("showBucket3", activeBuckets >= 3);
      meta.put("showBucket4", activeBuckets >= 4);
      meta.put("lastBucketLabel", ">" + lastColValue);
      meta.put("showDetails", showDetails);
      responseData.put("meta", meta);

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing aging report", e);
      return NeoResponse.error(500, "Aging report failed: " + e.getMessage());
    }
  }

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
