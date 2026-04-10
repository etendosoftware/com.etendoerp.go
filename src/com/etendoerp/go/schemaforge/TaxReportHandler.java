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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the Tax Report (Multidimensional Tax Report).
 *
 * Returns a structured JSON response with purchase and sales sections,
 * each containing three regions:
 *   - detail: flat rows grouped by tax rate (and optionally by BP)
 *   - summaryByCategory: aggregated by tax category
 *   - summaryByRate: aggregated by tax rate percentage, with per-BP breakdown
 *
 * URL: POST /sws/neo/tax-report
 */
@Named("taxReportHandler")
public class TaxReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(TaxReportHandler.class);

  // ---- Parameter name constants ----
  private static final String PARAM_DATE_FROM    = "dateFrom";
  private static final String PARAM_DATE_TO      = "dateTo";
  private static final String PARAM_DATE_TYPE    = "dateType";
  private static final String PARAM_CURRENCY_ID  = "currencyId";
  private static final String PARAM_TX_TYPE      = "transactionType";
  private static final String PARAM_TAX_TYPE     = "taxType";
  private static final String PARAM_TAX_ID       = "taxId";
  private static final String PARAM_BP_ID        = "bPartnerId";
  private static final String PARAM_ORG_ID       = "orgId";
  private static final String PARAM_SHOW_DETAILS = "showDetails";
  private static final String PARAM_GROUP_BY_BP  = "groupByBp";
  private static final String PARAM_BP_NAME_TYPE = "bpNameType";

  // ---- JSON field name constants ----
  private static final String F_TAX_NAME  = "taxName";
  private static final String F_RATE      = "rate";
  private static final String F_TAX_BASE  = "taxBaseAmt";
  private static final String F_TAX_AMT   = "taxAmt";
  private static final String F_TOTAL_AMT = "totalAmt";
  private static final String F_BP_GROUPS = "bpGroups";
  private static final String F_PARTNER   = "bPartner";
  private static final String F_BP_COUNT  = "bpCount";
  private static final String F_TAXES     = "taxes";

  // -------------------------------------------------------------------------
  // NeoHandler entry point
  // -------------------------------------------------------------------------

  @Override
  public NeoResponse handle(NeoContext context) {
    if ("GET".equals(context.getHttpMethod())) {
      return describeReport();
    }
    if ("POST".equals(context.getHttpMethod())) {
      return executeReport(context);
    }
    return NeoResponse.error(405, "Method not allowed");
  }

  // -------------------------------------------------------------------------
  // GET — describe
  // -------------------------------------------------------------------------

  private NeoResponse describeReport() {
    try {
      JSONObject desc = new JSONObject();
      desc.put("name", "Tax Report");
      desc.put("description",
          "Multidimensional tax report showing VAT and withholding transactions");
      return NeoResponse.ok(desc);
    } catch (Exception e) {
      log.error("Error building tax report descriptor", e);
      return NeoResponse.error(500, "Internal Server Error");
    }
  }

  // -------------------------------------------------------------------------
  // POST — execute
  // -------------------------------------------------------------------------

  private NeoResponse executeReport(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return NeoResponse.error(400, "Request body is required");
      }

      ReportParams p = parseParams(body);
      if (p.dateFrom.isEmpty() || p.dateTo.isEmpty()) {
        return NeoResponse.error(400, "dateFrom and dateTo are required");
      }

      String dateColumn = "acct".equals(p.dateType) ? "i.dateacct" : "i.dateinvoiced";

      List<TaxRow> purchaseRows = new ArrayList<>();
      List<TaxRow> salesRows    = new ArrayList<>();

      if ("B".equals(p.transactionType) || "P".equals(p.transactionType)) {
        purchaseRows = queryRows(dateColumn, "N", p);
      }
      if ("B".equals(p.transactionType) || "S".equals(p.transactionType)) {
        salesRows = queryRows(dateColumn, "Y", p);
      }

      JSONObject data = new JSONObject();
      data.put("purchase", buildSection(purchaseRows, p.showDetails, p.groupByBp));
      data.put("sales",    buildSection(salesRows,    p.showDetails, p.groupByBp));

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("meta", buildMeta(p));

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing tax report", e);
      return NeoResponse.error(500, "Internal Server Error: " + e.getMessage());
    }
  }

  // -------------------------------------------------------------------------
  // Parameter parsing
  // -------------------------------------------------------------------------

  private ReportParams parseParams(JSONObject body) {
    ReportParams p     = new ReportParams();
    p.dateFrom         = body.optString(PARAM_DATE_FROM, "");
    p.dateTo           = body.optString(PARAM_DATE_TO, "");
    p.dateType         = body.optString(PARAM_DATE_TYPE, "acct");
    p.transactionType  = body.optString(PARAM_TX_TYPE, "B");
    p.taxType          = body.optString(PARAM_TAX_TYPE, "tax");
    p.taxId            = body.optString(PARAM_TAX_ID, "");
    p.bPartnerRaw      = body.optString(PARAM_BP_ID, "");
    p.showDetails      = body.optBoolean(PARAM_SHOW_DETAILS, false);
    p.groupByBp        = body.optBoolean(PARAM_GROUP_BY_BP, false);
    p.bpNameType       = body.optString(PARAM_BP_NAME_TYPE, "commercial");
    p.orgId            = resolveOrgId(body);
    p.currencySymbol   = resolveCurrencySymbol(body.optString(PARAM_CURRENCY_ID, ""));
    return p;
  }

  // -------------------------------------------------------------------------
  // SQL query
  // -------------------------------------------------------------------------

  private List<TaxRow> queryRows(String dateColumn, String isSOTrx, ReportParams p)
      throws Exception {

    String bpNameCol = "legal".equals(p.bpNameType)
        ? "COALESCE(NULLIF(bp.name2,''), bp.name)"
        : "bp.name";

    StringBuilder sql = new StringBuilder(
        "SELECT " +
        "  t.c_tax_id            AS tax_id, " +
        "  t.name                AS tax_name, " +
        "  t.rate                AS rate, " +
        "  tc.c_taxcategory_id   AS tax_category_id, " +
        "  tc.name               AS tax_category_name, " +
        "  bp.c_bpartner_id      AS bp_id, " +
        "  " + bpNameCol + "     AS bp_name, " +
        "  COALESCE(bp.taxid,'') AS bp_taxid, " +
        "  COALESCE(ctry.name,'') AS bp_country, " +
        "  COALESCE(reg.name,'')  AS bp_region, " +
        "  i.c_invoice_id        AS invoice_id, " +
        "  i.documentno          AS doc_no, " +
        "  dt.name               AS doc_type, " +
        "  TO_CHAR(i.dateinvoiced,'YYYY-MM-DD') AS doc_date, " +
        "  TO_CHAR(i.dateacct,   'YYYY-MM-DD') AS acct_date, " +
        "  it.taxbaseamt         AS tax_base_amt, " +
        "  it.taxamt             AS tax_amt, " +
        "  i.grandtotal          AS total_amt " +
        "FROM c_invoicetax it " +
        "  JOIN c_invoice i      ON i.c_invoice_id = it.c_invoice_id " +
        "  JOIN c_tax t          ON t.c_tax_id = it.c_tax_id " +
        "  JOIN c_taxcategory tc ON tc.c_taxcategory_id = t.c_taxcategory_id " +
        "  JOIN c_bpartner bp    ON bp.c_bpartner_id = i.c_bpartner_id " +
        "  JOIN c_doctype dt     ON dt.c_doctype_id = i.c_doctypetarget_id " +
        "  LEFT JOIN c_bpartner_location bpl " +
        "    ON bpl.c_bpartner_location_id = i.c_bpartner_location_id " +
        "  LEFT JOIN c_location loc ON loc.c_location_id = bpl.c_location_id " +
        "  LEFT JOIN c_country ctry ON ctry.c_country_id = loc.c_country_id " +
        "  LEFT JOIN c_region reg   ON reg.c_region_id = loc.c_region_id " +
        "WHERE i.docstatus IN ('CO','CL') " +
        "  AND i.isactive = 'Y' " +
        "  AND i.issotrx = ? " +
        "  AND i.ad_client_id = ? " +
        "  AND i.ad_org_id = ? " +
        "  AND " + dateColumn + " >= TO_DATE(?,'YYYY-MM-DD') " +
        "  AND " + dateColumn + " <= TO_DATE(?,'YYYY-MM-DD') "
    );

    List<Object> params = new ArrayList<>();
    params.add(isSOTrx);
    params.add(OBContext.getOBContext().getCurrentClient().getId());
    params.add(p.orgId);
    params.add(p.dateFrom);
    params.add(p.dateTo);

    if (!p.taxId.isEmpty()) {
      sql.append("  AND t.c_tax_id = ? ");
      params.add(p.taxId);
    }

    if (!p.bPartnerRaw.isEmpty()) {
      String[] bpIds = p.bPartnerRaw.split(",");
      sql.append("  AND bp.c_bpartner_id IN (");
      for (int i = 0; i < bpIds.length; i++) {
        if (i > 0) sql.append(",");
        sql.append("?");
        params.add(bpIds[i].trim());
      }
      sql.append(") ");
    }

    if ("withholding".equals(p.taxType)) {
      sql.append("  AND t.istaxundeductable = 'Y' ");
    } else {
      sql.append("  AND (t.istaxundeductable IS NULL OR t.istaxundeductable = 'N') ");
    }

    sql.append("ORDER BY t.name, bp.name, i.documentno");

    Connection conn = OBDal.getInstance().getConnection();
    List<TaxRow> rows = new ArrayList<>();

    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
      for (int i = 0; i < params.size(); i++) {
        ps.setObject(i + 1, params.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(mapRow(rs));
        }
      }
    }
    return rows;
  }

  private static TaxRow mapRow(ResultSet rs) throws Exception {
    TaxRow row         = new TaxRow();
    row.taxId          = rs.getString("tax_id");
    row.taxName        = rs.getString("tax_name");
    row.rate           = rs.getBigDecimal("rate");
    row.taxCategoryId  = rs.getString("tax_category_id");
    row.taxCategoryName= rs.getString("tax_category_name");
    row.bPartnerId     = rs.getString("bp_id");
    row.bPartner       = rs.getString("bp_name");
    row.bpTaxId        = rs.getString("bp_taxid");
    row.bpCountry      = rs.getString("bp_country");
    row.bpRegion       = rs.getString("bp_region");
    row.invoiceId      = rs.getString("invoice_id");
    row.docNo          = rs.getString("doc_no");
    row.docType        = rs.getString("doc_type");
    row.docDate        = rs.getString("doc_date");
    row.acctDate       = rs.getString("acct_date");
    row.taxBaseAmt     = nullSafe(rs.getBigDecimal("tax_base_amt"));
    row.taxAmt         = nullSafe(rs.getBigDecimal("tax_amt"));
    row.totalAmt       = nullSafe(rs.getBigDecimal("total_amt"));
    return row;
  }

  // -------------------------------------------------------------------------
  // Section builder
  // -------------------------------------------------------------------------

  private JSONObject buildSection(List<TaxRow> rows, boolean showDetails, boolean groupByBp)
      throws Exception {
    JSONObject section = new JSONObject();
    section.put("detail",            showDetails ? buildDetail(rows, groupByBp) : new JSONArray());
    section.put("summaryByCategory", buildSummaryByCategory(rows));
    section.put("summaryByRate",     buildSummaryByRate(rows));
    return section;
  }

  private JSONArray buildDetail(List<TaxRow> rows, boolean groupByBp) throws Exception {
    Map<String, List<TaxRow>> byTax = groupBy(rows, r -> r.taxId);
    JSONArray result = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> entry : byTax.entrySet()) {
      List<TaxRow> taxRows = entry.getValue();
      TaxRow first = taxRows.get(0);
      JSONObject taxGroup = new JSONObject();
      taxGroup.put("taxId",     first.taxId);
      taxGroup.put(F_TAX_NAME,  first.taxName);
      taxGroup.put(F_TAX_BASE,  sum(taxRows, F_TAX_BASE));
      taxGroup.put(F_TAX_AMT,   sum(taxRows, F_TAX_AMT));
      taxGroup.put(F_TOTAL_AMT, sum(taxRows, F_TOTAL_AMT));
      if (groupByBp) {
        taxGroup.put(F_BP_GROUPS, buildBpGroups(taxRows, false));
        taxGroup.put("docs",      new JSONArray());
      } else {
        taxGroup.put(F_BP_GROUPS, new JSONArray());
        taxGroup.put("docs",      buildDocRows(taxRows));
      }
      result.put(taxGroup);
    }
    return result;
  }

  /**
   * Builds per-BP groups for the detail section (forRate=false) or the rate summary (forRate=true).
   * When forRate=true each group includes bpTaxId and a tax breakdown array instead of doc rows.
   */
  private JSONArray buildBpGroups(List<TaxRow> rows, boolean forRate) throws Exception {
    Map<String, List<TaxRow>> byBp = groupBy(rows, r -> r.bPartnerId);
    JSONArray groups = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> entry : byBp.entrySet()) {
      List<TaxRow> bpRows = entry.getValue();
      TaxRow first = bpRows.get(0);
      JSONObject g = new JSONObject();
      g.put("bPartnerId", first.bPartnerId);
      g.put(F_PARTNER,    first.bPartner);
      g.put(F_TAX_BASE,   sum(bpRows, F_TAX_BASE));
      g.put(F_TAX_AMT,    sum(bpRows, F_TAX_AMT));
      g.put(F_TOTAL_AMT,  sum(bpRows, F_TOTAL_AMT));
      if (forRate) {
        g.put("bpTaxId", first.bpTaxId != null ? first.bpTaxId : "");
        g.put(F_TAXES,   buildTaxSummaryArray(groupBy(bpRows, r -> r.taxId)));
      } else {
        g.put("docs", buildDocRows(bpRows));
      }
      groups.put(g);
    }
    return groups;
  }

  private static JSONArray buildDocRows(List<TaxRow> rows) throws Exception {
    JSONArray docs = new JSONArray();
    for (TaxRow r : rows) {
      JSONObject doc = new JSONObject();
      doc.put("invoiceId", r.invoiceId);
      doc.put("docNo",     r.docNo);
      doc.put("docType",   r.docType);
      doc.put("docDate",   r.docDate);
      doc.put("acctDate",  r.acctDate);
      doc.put(F_PARTNER,   r.bPartner);
      doc.put("bpCountry", r.bpCountry);
      doc.put("bpRegion",  r.bpRegion);
      doc.put(F_TAX_BASE,  r.taxBaseAmt);
      doc.put(F_TAX_AMT,   r.taxAmt);
      doc.put(F_TOTAL_AMT, r.totalAmt);
      docs.put(doc);
    }
    return docs;
  }

  // -------------------------------------------------------------------------
  // Summary by Tax Category
  // -------------------------------------------------------------------------

  private JSONArray buildSummaryByCategory(List<TaxRow> rows) throws Exception {
    Map<String, List<TaxRow>> byCategory = groupBy(rows, r -> r.taxCategoryId);
    JSONArray result = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> catEntry : byCategory.entrySet()) {
      List<TaxRow> catRows = catEntry.getValue();
      TaxRow catFirst = catRows.get(0);
      JSONObject cat = new JSONObject();
      cat.put("taxCategoryId",   catFirst.taxCategoryId);
      cat.put("taxCategoryName", catFirst.taxCategoryName);
      cat.put(F_TAXES,           buildTaxSummaryArray(groupBy(catRows, r -> r.taxId)));
      result.put(cat);
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Summary by Tax Rate
  // -------------------------------------------------------------------------

  private JSONArray buildSummaryByRate(List<TaxRow> rows) throws Exception {
    Map<BigDecimal, List<TaxRow>> byRate = new LinkedHashMap<>();
    for (TaxRow r : rows) {
      byRate.computeIfAbsent(r.rate, k -> new ArrayList<>()).add(r);
    }
    JSONArray result = new JSONArray();
    for (Map.Entry<BigDecimal, List<TaxRow>> rateEntry : byRate.entrySet()) {
      result.put(buildRateGroup(rateEntry.getKey(), rateEntry.getValue()));
    }
    return result;
  }

  private JSONObject buildRateGroup(BigDecimal rate, List<TaxRow> rateRows) throws Exception {
    long totalBpCount = rateRows.stream().map(r -> r.bPartnerId).distinct().count();
    JSONObject rateGroup = new JSONObject();
    rateGroup.put(F_RATE,      rate);
    rateGroup.put(F_BP_GROUPS, buildBpGroups(rateRows, true));
    rateGroup.put(F_TAXES,     buildTaxSummaryArray(groupBy(rateRows, r -> r.taxId)));
    rateGroup.put(F_TAX_BASE,  sum(rateRows, F_TAX_BASE));
    rateGroup.put(F_TAX_AMT,   sum(rateRows, F_TAX_AMT));
    rateGroup.put(F_TOTAL_AMT, sum(rateRows, F_TOTAL_AMT));
    rateGroup.put(F_BP_COUNT,  totalBpCount);
    return rateGroup;
  }

  private JSONArray buildTaxSummaryArray(Map<String, List<TaxRow>> byTax) throws Exception {
    JSONArray taxes = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> taxEntry : byTax.entrySet()) {
      List<TaxRow> taxRows = taxEntry.getValue();
      TaxRow taxFirst = taxRows.get(0);
      long bpCount = taxRows.stream().map(r -> r.bPartnerId).distinct().count();
      JSONObject taxSummary = new JSONObject();
      taxSummary.put("taxId",    taxFirst.taxId);
      taxSummary.put(F_TAX_NAME, taxFirst.taxName);
      taxSummary.put(F_RATE,     taxFirst.rate);
      taxSummary.put(F_TAX_BASE, sum(taxRows, F_TAX_BASE));
      taxSummary.put(F_TAX_AMT,  sum(taxRows, F_TAX_AMT));
      taxSummary.put(F_TOTAL_AMT,sum(taxRows, F_TOTAL_AMT));
      taxSummary.put(F_BP_COUNT, bpCount);
      taxes.put(taxSummary);
    }
    return taxes;
  }

  // -------------------------------------------------------------------------
  // Meta
  // -------------------------------------------------------------------------

  private static JSONObject buildMeta(ReportParams p) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put(PARAM_DATE_FROM,    p.dateFrom);
    meta.put(PARAM_DATE_TO,      p.dateTo);
    meta.put(PARAM_DATE_TYPE,    p.dateType);
    meta.put(PARAM_TX_TYPE,      p.transactionType);
    meta.put(PARAM_TAX_TYPE,     p.taxType);
    meta.put("currencySymbol",   p.currencySymbol);
    meta.put(PARAM_SHOW_DETAILS, p.showDetails);
    meta.put(PARAM_GROUP_BY_BP,  p.groupByBp);
    meta.put(PARAM_BP_NAME_TYPE, p.bpNameType);
    return meta;
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  @FunctionalInterface
  private interface RowKeyExtractor {
    String extract(TaxRow row);
  }

  private static Map<String, List<TaxRow>> groupBy(List<TaxRow> rows, RowKeyExtractor keyFn) {
    Map<String, List<TaxRow>> map = new LinkedHashMap<>();
    for (TaxRow r : rows) {
      map.computeIfAbsent(keyFn.extract(r), k -> new ArrayList<>()).add(r);
    }
    return map;
  }

  private static String resolveOrgId(JSONObject body) {
    String orgId = body.optString(PARAM_ORG_ID, "");
    if (orgId.isEmpty()) {
      orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    }
    return orgId;
  }

  private String resolveCurrencySymbol(String currencyId) {
    if (currencyId.isEmpty()) {
      return "";
    }
    try {
      OBContext.setAdminMode(true);
      org.openbravo.model.common.currency.Currency cur =
          OBDal.getInstance().get(org.openbravo.model.common.currency.Currency.class, currencyId);
      return cur != null ? cur.getSymbol() : "";
    } catch (Exception e) {
      log.warn("Could not resolve currency symbol for id {}", currencyId, e);
      return "";
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static BigDecimal sum(List<TaxRow> rows, String field) {
    BigDecimal total = BigDecimal.ZERO;
    for (TaxRow r : rows) {
      BigDecimal val;
      switch (field) {
        case F_TAX_BASE: val = r.taxBaseAmt; break;
        case F_TAX_AMT:  val = r.taxAmt;     break;
        case F_TOTAL_AMT:val = r.totalAmt;   break;
        default:         val = BigDecimal.ZERO;
      }
      total = total.add(val);
    }
    return total;
  }

  private static BigDecimal nullSafe(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  // -------------------------------------------------------------------------
  // Inner classes
  // -------------------------------------------------------------------------

  private static class ReportParams {
    String  dateFrom;
    String  dateTo;
    String  dateType;
    String  transactionType;
    String  taxType;
    String  taxId;
    String  bPartnerRaw;
    boolean showDetails;
    boolean groupByBp;
    String  bpNameType;
    String  orgId;
    String  currencySymbol;
  }

  private static class TaxRow {
    String     taxId;
    String     taxName;
    BigDecimal rate;
    String     taxCategoryId;
    String     taxCategoryName;
    String     bPartnerId;
    String     bPartner;
    String     bpTaxId;
    String     bpCountry;
    String     bpRegion;
    String     invoiceId;
    String     docNo;
    String     docType;
    String     docDate;
    String     acctDate;
    BigDecimal taxBaseAmt;
    BigDecimal taxAmt;
    BigDecimal totalAmt;
  }
}
