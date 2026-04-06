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
import java.util.HashSet;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.data.FieldProvider;
import org.openbravo.erpCommon.ad_reports.AgingDao;
import org.openbravo.model.common.enterprise.Organization;
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

  /** Parameter key for receivables/payables direction. */
  private static final String REC_OR_PAY = "recOrPay";
  /** Parameter type name for string fields. */
  private static final String STRING_TYPE = "string";
  /** Parameter key for the as-of date. */
  private static final String CURRENT_DATE = "currentDate";
  /** Parameter key for the first aging bucket boundary. */
  private static final String COLUMN1 = "column1";
  /** Parameter type name for integer fields. */
  private static final String INTEGER_TYPE = "integer";
  /** Parameter key for the second aging bucket boundary. */
  private static final String COLUMN2 = "column2";
  /** Parameter key for the third aging bucket boundary. */
  private static final String COLUMN3 = "column3";
  /** Parameter key for the fourth aging bucket boundary. */
  private static final String COLUMN4 = "column4";
  /** Parameter key for business partner UUID filter. */
  private static final String B_PARTNER_ID = "bPartnerId";

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
      params.put(param(REC_OR_PAY, STRING_TYPE, true, "RECEIVABLES or PAYABLES"));
      params.put(param(CURRENT_DATE, "date", false, "As-of date (yyyy-MM-dd). Defaults to today."));
      params.put(param(COLUMN1, INTEGER_TYPE, false, "First aging bucket boundary in days. Default: 30"));
      params.put(param(COLUMN2, INTEGER_TYPE, false, "Second aging bucket boundary. Default: 60"));
      params.put(param(COLUMN3, INTEGER_TYPE, false, "Third aging bucket boundary. Default: 90"));
      params.put(param(COLUMN4, INTEGER_TYPE, false, "Fourth aging bucket boundary. Default: 120"));
      params.put(param(B_PARTNER_ID, STRING_TYPE, false, "Filter by business partner UUID"));
      params.put(param("orgId", STRING_TYPE, false, "Filter by organization UUID"));
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

      String recOrPay = body.optString(REC_OR_PAY, "RECEIVABLES");
      String dateStr = body.optString(CURRENT_DATE, "");
      String col1 = body.optString(COLUMN1, DEFAULT_COL1);
      String col2 = body.optString(COLUMN2, DEFAULT_COL2);
      String col3 = body.optString(COLUMN3, DEFAULT_COL3);
      String col4 = body.optString(COLUMN4, DEFAULT_COL4);
      String bPartnerId = body.optString(B_PARTNER_ID, "");
      String orgId = body.optString("orgId", "");

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

      // Build organization tree
      Set<String> organizations = new HashSet<>();
      organizations.add(orgId);
      Organization org = OBDal.getInstance().get(Organization.class, orgId);
      if (org != null) {
        // Include child organizations
        Set<String> naturalTree = OBContext.getOBContext()
            .getOrganizationStructureProvider(obCtx.getCurrentClient().getId())
            .getNaturalTree(orgId);
        organizations.addAll(naturalTree);
      }

      // Resolve accounting schema for currency conversion
      String accSchemaId = "";
      try {
        AcctSchema acctSchema = OBDal.getInstance()
            .createQuery(AcctSchema.class, "client.id = :clientId and active = true")
            .setNamedParameter("clientId", obCtx.getCurrentClient().getId())
            .setMaxResult(1)
            .uniqueResult();
        if (acctSchema != null) {
          accSchemaId = acctSchema.getId();
        }
      } catch (Exception e) {
        log.warn("Could not resolve accounting schema", e);
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
          row.put(B_PARTNER_ID, fp.getField("BPartnerID"));
          row.put("bPartner", fp.getField("BPartner"));
          row.put("current", toBigDecimal(fp.getField("amount0")));
          row.put("days30", toBigDecimal(fp.getField("amount1")));
          row.put("days60", toBigDecimal(fp.getField("amount2")));
          row.put("days90", toBigDecimal(fp.getField("amount3")));
          row.put("days120", toBigDecimal(fp.getField("amount4")));
          row.put("days150plus", toBigDecimal(fp.getField("amount5")));
          row.put("total", toBigDecimal(fp.getField("Total")));
          row.put("credit", toBigDecimal(fp.getField("credit")));
          row.put("net", toBigDecimal(fp.getField("net")));
          rows.put(row);
        }
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", rows);
      responseData.put("count", rows.length());

      JSONObject meta = new JSONObject();
      meta.put(REC_OR_PAY, recOrPay);
      meta.put(CURRENT_DATE, new SimpleDateFormat("yyyy-MM-dd").format(currentDate));
      meta.put(COLUMN1, col1);
      meta.put(COLUMN2, col2);
      meta.put(COLUMN3, col3);
      meta.put(COLUMN4, col4);
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
