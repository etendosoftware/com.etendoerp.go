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
import java.text.SimpleDateFormat;
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
 *   - summaryByRate: aggregated by tax rate percentage
 *
 * URL: POST /sws/neo/tax-report
 *
 * POST body parameters:
 *   dateFrom:        "yyyy-MM-dd" (required)
 *   dateTo:          "yyyy-MM-dd" (required)
 *   dateType:        "acct" (Accounting Date) | "operation" (Invoice Date). Default: "acct"
 *   currencyId:      currency UUID (required)
 *   transactionType: "S" (Sales) | "P" (Purchases) | "B" (Both). Default: "B"
 *   taxType:         "tax" | "withholding". Default: "tax"
 *   taxId:           filter by specific tax UUID (optional)
 *   bPartnerId:      comma-separated BP UUIDs (optional)
 *   orgId:           organization UUID (optional, defaults to session org)
 *   showDetails:     true | false. Default: false
 *   groupByBp:       true | false. Default: false
 */
@Named("taxReportHandler")
public class TaxReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(TaxReportHandler.class);

  private static final String DATE_FORMAT        = "yyyy-MM-dd";
  private static final String PARAM_DATE_FROM    = "dateFrom";
  private static final String PARAM_DATE_TO      = "dateTo";
  private static final String PARAM_DATE_TYPE    = "dateType";
  private static final String PARAM_CURRENCY_ID  = "currencyId";
  private static final String PARAM_TX_TYPE      = "transactionType";
  private static final String PARAM_TAX_TYPE     = "taxType";
  private static final String PARAM_TAX_ID       = "taxId";
  private static final String PARAM_BP_ID        = "bPartnerId";
  private static final String PARAM_ORG_ID       = "orgId";
  private static final String PARAM_SHOW_DETAILS  = "showDetails";
  private static final String PARAM_GROUP_BY_BP   = "groupByBp";
  private static final String PARAM_BP_NAME_TYPE  = "bpNameType";

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

      String dateFrom       = body.optString(PARAM_DATE_FROM, "");
      String dateTo         = body.optString(PARAM_DATE_TO, "");
      String dateType       = body.optString(PARAM_DATE_TYPE, "acct");
      String currencyId     = body.optString(PARAM_CURRENCY_ID, "");
      String transactionType = body.optString(PARAM_TX_TYPE, "B");
      String taxType        = body.optString(PARAM_TAX_TYPE, "tax");
      String taxId          = body.optString(PARAM_TAX_ID, "");
      String bPartnerRaw    = body.optString(PARAM_BP_ID, "");
      boolean showDetails   = body.optBoolean(PARAM_SHOW_DETAILS, false);
      boolean groupByBp     = body.optBoolean(PARAM_GROUP_BY_BP, false);
      String bpNameType     = body.optString(PARAM_BP_NAME_TYPE, "commercial");
      String orgId          = resolveOrgId(body);

      if (dateFrom.isEmpty() || dateTo.isEmpty()) {
        return NeoResponse.error(400, "dateFrom and dateTo are required");
      }

      String currencySymbol = resolveCurrencySymbol(currencyId);
      String dateColumn     = "acct".equals(dateType) ? "i.dateacct" : "i.dateinvoiced";

      List<TaxRow> purchaseRows = new ArrayList<>();
      List<TaxRow> salesRows    = new ArrayList<>();

      if ("B".equals(transactionType) || "P".equals(transactionType)) {
        purchaseRows = queryRows(dateColumn, dateFrom, dateTo, orgId,
            "N", taxType, taxId, bPartnerRaw, currencyId, bpNameType);
      }
      if ("B".equals(transactionType) || "S".equals(transactionType)) {
        salesRows = queryRows(dateColumn, dateFrom, dateTo, orgId,
            "Y", taxType, taxId, bPartnerRaw, currencyId, bpNameType);
      }

      JSONObject purchase = buildSection(purchaseRows, showDetails, groupByBp);
      JSONObject sales    = buildSection(salesRows,    showDetails, groupByBp);

      JSONObject data = new JSONObject();
      data.put("purchase", purchase);
      data.put("sales",    sales);

      JSONObject meta = buildMeta(dateFrom, dateTo, dateType, transactionType, taxType,
          currencySymbol, showDetails, groupByBp, bpNameType);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("meta", meta);

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error executing tax report", e);
      return NeoResponse.error(500, "Internal Server Error: " + e.getMessage());
    }
  }

  // -------------------------------------------------------------------------
  // SQL query
  // -------------------------------------------------------------------------

  /**
   * Queries C_INVOICETAX joined to C_INVOICE and related tables.
   * Returns flat rows ordered by taxName, bPartner, documentNo.
   *
   * @param dateColumn  "i.dateacct" or "i.dateinvoiced"
   * @param isSOTrx     "Y" for Sales, "N" for Purchases
   */
  private List<TaxRow> queryRows(String dateColumn, String dateFrom, String dateTo,
      String orgId, String isSOTrx, String taxType, String taxId,
      String bPartnerRaw, String currencyId, String bpNameType) throws Exception {

    String bpNameCol = "legal".equals(bpNameType)
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
        "  JOIN c_invoice i    ON i.c_invoice_id = it.c_invoice_id " +
        "  JOIN c_tax t        ON t.c_tax_id = it.c_tax_id " +
        "  JOIN c_taxcategory tc ON tc.c_taxcategory_id = t.c_taxcategory_id " +
        "  JOIN c_bpartner bp  ON bp.c_bpartner_id = i.c_bpartner_id " +
        "  JOIN c_doctype dt   ON dt.c_doctype_id = i.c_doctypetarget_id " +
        "  LEFT JOIN c_bpartner_location bpl " +
        "    ON bpl.c_bpartner_location_id = i.c_bpartner_location_id " +
        "  LEFT JOIN c_location loc ON loc.c_location_id = bpl.c_location_id " +
        "  LEFT JOIN c_country ctry ON ctry.c_country_id = loc.c_country_id " +
        "  LEFT JOIN c_region reg  ON reg.c_region_id = loc.c_region_id " +
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
    params.add(orgId);
    params.add(dateFrom);
    params.add(dateTo);

    // Optional: filter by specific tax
    if (!taxId.isEmpty()) {
      sql.append("  AND t.c_tax_id = ? ");
      params.add(taxId);
    }

    // Optional: filter by business partner(s)
    if (!bPartnerRaw.isEmpty()) {
      String[] bpIds = bPartnerRaw.split(",");
      sql.append("  AND bp.c_bpartner_id IN (");
      for (int i = 0; i < bpIds.length; i++) {
        if (i > 0) sql.append(",");
        sql.append("?");
        params.add(bpIds[i].trim());
      }
      sql.append(") ");
    }

    // Tax vs withholding filter using tax category or a flag on c_tax
    // Withholding taxes have istaxundeductable='Y'
    if ("withholding".equals(taxType)) {
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
          TaxRow row = new TaxRow();
          row.taxId           = rs.getString("tax_id");
          row.taxName         = rs.getString("tax_name");
          row.rate            = rs.getBigDecimal("rate");
          row.taxCategoryId   = rs.getString("tax_category_id");
          row.taxCategoryName = rs.getString("tax_category_name");
          row.bPartnerId      = rs.getString("bp_id");
          row.bPartner        = rs.getString("bp_name");
          row.bpTaxId         = rs.getString("bp_taxid");
          row.bpCountry       = rs.getString("bp_country");
          row.bpRegion        = rs.getString("bp_region");
          row.invoiceId       = rs.getString("invoice_id");
          row.docNo           = rs.getString("doc_no");
          row.docType         = rs.getString("doc_type");
          row.docDate         = rs.getString("doc_date");
          row.acctDate        = rs.getString("acct_date");
          row.taxBaseAmt      = nullSafe(rs.getBigDecimal("tax_base_amt"));
          row.taxAmt          = nullSafe(rs.getBigDecimal("tax_amt"));
          row.totalAmt        = nullSafe(rs.getBigDecimal("total_amt"));
          rows.add(row);
        }
      }
    }
    return rows;
  }

  // -------------------------------------------------------------------------
  // Section builder (detail + summaryByCategory + summaryByRate)
  // -------------------------------------------------------------------------

  private JSONObject buildSection(List<TaxRow> rows, boolean showDetails, boolean groupByBp)
      throws Exception {
    JSONObject section = new JSONObject();
    section.put("detail",            showDetails ? buildDetail(rows, groupByBp) : new JSONArray());
    section.put("summaryByCategory", buildSummaryByCategory(rows));
    section.put("summaryByRate",     buildSummaryByRate(rows));
    return section;
  }

  /**
   * Detail region: pre-grouped by taxId.
   * Each group has aggregate totals + either bpGroups (if groupByBp) or flat docs array.
   */
  private JSONArray buildDetail(List<TaxRow> rows, boolean groupByBp) throws Exception {
    // Group rows by taxId, preserving insertion order (rows already sorted by taxName)
    Map<String, List<TaxRow>> byTax = new LinkedHashMap<>();
    for (TaxRow r : rows) {
      byTax.computeIfAbsent(r.taxId, k -> new ArrayList<>()).add(r);
    }

    JSONArray result = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> entry : byTax.entrySet()) {
      List<TaxRow> taxRows = entry.getValue();
      TaxRow first = taxRows.get(0);

      BigDecimal totalBase = sum(taxRows, "taxBaseAmt");
      BigDecimal totalTax  = sum(taxRows, "taxAmt");
      BigDecimal totalAmt  = sum(taxRows, "totalAmt");

      JSONObject taxGroup = new JSONObject();
      taxGroup.put("taxId",       first.taxId);
      taxGroup.put("taxName",     first.taxName);
      taxGroup.put("taxBaseAmt",  totalBase);
      taxGroup.put("taxAmt",      totalTax);
      taxGroup.put("totalAmt",    totalAmt);

      if (groupByBp) {
        taxGroup.put("bpGroups", buildBpGroups(taxRows));
        taxGroup.put("docs",     new JSONArray());
      } else {
        taxGroup.put("bpGroups", new JSONArray());
        taxGroup.put("docs",     buildDocRows(taxRows));
      }
      result.put(taxGroup);
    }
    return result;
  }

  private JSONArray buildBpGroups(List<TaxRow> rows) throws Exception {
    Map<String, List<TaxRow>> byBp = new LinkedHashMap<>();
    for (TaxRow r : rows) {
      byBp.computeIfAbsent(r.bPartnerId, k -> new ArrayList<>()).add(r);
    }
    JSONArray groups = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> entry : byBp.entrySet()) {
      List<TaxRow> bpRows = entry.getValue();
      TaxRow first = bpRows.get(0);
      JSONObject g = new JSONObject();
      g.put("bPartnerId", first.bPartnerId);
      g.put("bPartner",   first.bPartner);
      g.put("taxBaseAmt", sum(bpRows, "taxBaseAmt"));
      g.put("taxAmt",     sum(bpRows, "taxAmt"));
      g.put("totalAmt",   sum(bpRows, "totalAmt"));
      g.put("docs",       buildDocRows(bpRows));
      groups.put(g);
    }
    return groups;
  }

  private JSONArray buildDocRows(List<TaxRow> rows) throws Exception {
    JSONArray docs = new JSONArray();
    for (TaxRow r : rows) {
      JSONObject doc = new JSONObject();
      doc.put("invoiceId",  r.invoiceId);
      doc.put("docNo",      r.docNo);
      doc.put("docType",    r.docType);
      doc.put("docDate",    r.docDate);
      doc.put("acctDate",   r.acctDate);
      doc.put("bPartner",   r.bPartner);
      doc.put("bpCountry",  r.bpCountry);
      doc.put("bpRegion",   r.bpRegion);
      doc.put("taxBaseAmt", r.taxBaseAmt);
      doc.put("taxAmt",     r.taxAmt);
      doc.put("totalAmt",   r.totalAmt);
      docs.put(doc);
    }
    return docs;
  }

  /**
   * Summary by Category: groups by taxCategoryId, then by taxId within each category.
   */
  private JSONArray buildSummaryByCategory(List<TaxRow> rows) throws Exception {
    // Group by category
    Map<String, List<TaxRow>> byCategory = new LinkedHashMap<>();
    for (TaxRow r : rows) {
      byCategory.computeIfAbsent(r.taxCategoryId, k -> new ArrayList<>()).add(r);
    }

    JSONArray result = new JSONArray();
    for (Map.Entry<String, List<TaxRow>> catEntry : byCategory.entrySet()) {
      List<TaxRow> catRows = catEntry.getValue();
      TaxRow catFirst = catRows.get(0);

      // Within category, group by taxId
      Map<String, List<TaxRow>> byTax = new LinkedHashMap<>();
      for (TaxRow r : catRows) {
        byTax.computeIfAbsent(r.taxId, k -> new ArrayList<>()).add(r);
      }

      JSONArray taxes = new JSONArray();
      for (Map.Entry<String, List<TaxRow>> taxEntry : byTax.entrySet()) {
        List<TaxRow> taxRows = taxEntry.getValue();
        TaxRow taxFirst = taxRows.get(0);

        // Count distinct BPs
        long bpCount = taxRows.stream().map(r -> r.bPartnerId).distinct().count();

        JSONObject taxSummary = new JSONObject();
        taxSummary.put("taxId",       taxFirst.taxId);
        taxSummary.put("taxName",     taxFirst.taxName);
        taxSummary.put("rate",        taxFirst.rate);
        taxSummary.put("taxBaseAmt",  sum(taxRows, "taxBaseAmt"));
        taxSummary.put("taxAmt",      sum(taxRows, "taxAmt"));
        taxSummary.put("totalAmt",    sum(taxRows, "totalAmt"));
        taxSummary.put("bpCount",     bpCount);
        taxes.put(taxSummary);
      }

      JSONObject cat = new JSONObject();
      cat.put("taxCategoryId",   catFirst.taxCategoryId);
      cat.put("taxCategoryName", catFirst.taxCategoryName);
      cat.put("taxes",           taxes);
      result.put(cat);
    }
    return result;
  }

  /**
   * Summary by Rate: groups by rate value.
   * Each rate group contains:
   *   - bpGroups: per-BP breakdown (Rate → BP → Tax), matching Classic "Summary per Tax/Withholding %"
   *   - taxes: flat tax aggregation (for totals row)
   *   - aggregate totals and bpCount at the rate group level
   */
  private JSONArray buildSummaryByRate(List<TaxRow> rows) throws Exception {
    Map<BigDecimal, List<TaxRow>> byRate = new LinkedHashMap<>();
    for (TaxRow r : rows) {
      byRate.computeIfAbsent(r.rate, k -> new ArrayList<>()).add(r);
    }

    JSONArray result = new JSONArray();
    for (Map.Entry<BigDecimal, List<TaxRow>> rateEntry : byRate.entrySet()) {
      List<TaxRow> rateRows = rateEntry.getValue();

      // BP groups within this rate
      Map<String, List<TaxRow>> byBp = new LinkedHashMap<>();
      for (TaxRow r : rateRows) {
        byBp.computeIfAbsent(r.bPartnerId, k -> new ArrayList<>()).add(r);
      }

      JSONArray bpGroups = new JSONArray();
      for (Map.Entry<String, List<TaxRow>> bpEntry : byBp.entrySet()) {
        List<TaxRow> bpRows = bpEntry.getValue();
        TaxRow bpFirst = bpRows.get(0);

        // Within BP, group by taxId
        Map<String, List<TaxRow>> byTaxInBp = new LinkedHashMap<>();
        for (TaxRow r : bpRows) {
          byTaxInBp.computeIfAbsent(r.taxId, k -> new ArrayList<>()).add(r);
        }
        JSONArray bpTaxes = new JSONArray();
        for (Map.Entry<String, List<TaxRow>> taxEntry : byTaxInBp.entrySet()) {
          List<TaxRow> taxRows = taxEntry.getValue();
          TaxRow taxFirst = taxRows.get(0);
          JSONObject t = new JSONObject();
          t.put("taxName",    taxFirst.taxName);
          t.put("rate",       taxFirst.rate);
          t.put("taxBaseAmt", sum(taxRows, "taxBaseAmt"));
          t.put("taxAmt",     sum(taxRows, "taxAmt"));
          t.put("totalAmt",   sum(taxRows, "totalAmt"));
          bpTaxes.put(t);
        }

        JSONObject bpGroup = new JSONObject();
        bpGroup.put("bPartnerId", bpFirst.bPartnerId);
        bpGroup.put("bPartner",   bpFirst.bPartner);
        bpGroup.put("bpTaxId",    bpFirst.bpTaxId != null ? bpFirst.bpTaxId : "");
        bpGroup.put("taxBaseAmt", sum(bpRows, "taxBaseAmt"));
        bpGroup.put("taxAmt",     sum(bpRows, "taxAmt"));
        bpGroup.put("totalAmt",   sum(bpRows, "totalAmt"));
        bpGroup.put("taxes",      bpTaxes);
        bpGroups.put(bpGroup);
      }

      // Flat tax aggregation (for totals row and backward compat)
      Map<String, List<TaxRow>> byTax = new LinkedHashMap<>();
      for (TaxRow r : rateRows) {
        byTax.computeIfAbsent(r.taxId, k -> new ArrayList<>()).add(r);
      }
      JSONArray taxes = new JSONArray();
      for (Map.Entry<String, List<TaxRow>> taxEntry : byTax.entrySet()) {
        List<TaxRow> taxRows = taxEntry.getValue();
        TaxRow taxFirst = taxRows.get(0);
        long bpCount = taxRows.stream().map(r -> r.bPartnerId).distinct().count();
        JSONObject taxSummary = new JSONObject();
        taxSummary.put("taxId",      taxFirst.taxId);
        taxSummary.put("taxName",    taxFirst.taxName);
        taxSummary.put("rate",       taxFirst.rate);
        taxSummary.put("taxBaseAmt", sum(taxRows, "taxBaseAmt"));
        taxSummary.put("taxAmt",     sum(taxRows, "taxAmt"));
        taxSummary.put("totalAmt",   sum(taxRows, "totalAmt"));
        taxSummary.put("bpCount",    bpCount);
        taxes.put(taxSummary);
      }

      long totalBpCount = rateRows.stream().map(r -> r.bPartnerId).distinct().count();

      JSONObject rateGroup = new JSONObject();
      rateGroup.put("rate",       rateEntry.getKey());
      rateGroup.put("bpGroups",   bpGroups);
      rateGroup.put("taxes",      taxes);
      rateGroup.put("taxBaseAmt", sum(rateRows, "taxBaseAmt"));
      rateGroup.put("taxAmt",     sum(rateRows, "taxAmt"));
      rateGroup.put("totalAmt",   sum(rateRows, "totalAmt"));
      rateGroup.put("bpCount",    totalBpCount);
      result.put(rateGroup);
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Meta
  // -------------------------------------------------------------------------

  private static JSONObject buildMeta(String dateFrom, String dateTo, String dateType,
      String transactionType, String taxType, String currencySymbol,
      boolean showDetails, boolean groupByBp, String bpNameType) throws Exception {
    JSONObject meta = new JSONObject();
    meta.put("dateFrom",          dateFrom);
    meta.put("dateTo",            dateTo);
    meta.put("dateType",          dateType);
    meta.put("transactionType",   transactionType);
    meta.put("taxType",           taxType);
    meta.put("currencySymbol",    currencySymbol);
    meta.put(PARAM_SHOW_DETAILS,  showDetails);
    meta.put(PARAM_GROUP_BY_BP,   groupByBp);
    meta.put(PARAM_BP_NAME_TYPE,  bpNameType);
    return meta;
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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
        case "taxBaseAmt": val = r.taxBaseAmt; break;
        case "taxAmt":     val = r.taxAmt;     break;
        case "totalAmt":   val = r.totalAmt;   break;
        default:           val = BigDecimal.ZERO;
      }
      total = total.add(val);
    }
    return total;
  }

  private static BigDecimal nullSafe(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  // -------------------------------------------------------------------------
  // Inner data class
  // -------------------------------------------------------------------------

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
