/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat349.es.AEAT3492010ReportDao;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

class Fiscal349BoxesHandler {

  private static final Logger log = Logger.getLogger(Fiscal349BoxesHandler.class);

  private static final String OPERATORS    = "operators";
  private static final String GENERATE     = "generate";
  private static final String DECLARATIONS = "declarations";
  private static final String MODIFIED     = "modified";
  private static final String PERIOD_KEY   = "period";
  private static final String SINCE_KEY    = "since";
  private static final String JSON_CT      = "application/json;charset=UTF-8";

  private final NeoServlet servlet;
  private final FiscalDeclCrudHandler declHandler;

  Fiscal349BoxesHandler(NeoServlet servlet) {
    this.servlet = servlet;
    this.declHandler = new FiscalDeclCrudHandler(servlet);
  }

  void handle(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (DECLARATIONS.equals(entityName)) {
      try {
        declHandler.handleDeclarations(method, request, response);
      } catch (Exception e) {
        log.error("Error in /fiscal349/declarations", e);
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
      }
      return;
    }
    if (!OPERATORS.equals(entityName) && !GENERATE.equals(entityName)
        && !MODIFIED.equals(entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Unknown fiscal349 entity: " + entityName);
      return;
    }
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Only GET is supported for /fiscal349/" + entityName);
      return;
    }
    String yearStr = request.getParameter("year");
    String period  = request.getParameter(PERIOD_KEY);
    if (yearStr == null || period == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required params: year, period");
      return;
    }
    if (MODIFIED.equals(entityName) && request.getParameter(SINCE_KEY) == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: since");
      return;
    }
    try {
      int    year  = Integer.parseInt(yearStr);
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      if (OPERATORS.equals(entityName)) {
        JSONObject result = computeOperators(orgId, year, period);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else if (GENERATE.equals(entityName)) {
        handleGenerate(orgId, year, period, request, response);
      } else {
        long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
        handleModified(orgId, year, period, new Date(sinceMs), response);
      }
    } catch (Exception e) {
      log.error("Error in /fiscal349/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  // ── operators ─────────────────────────────────────────────────────

  JSONObject computeOperators(String orgId, int year, String period) throws Exception {
    Organization org      = OBDal.getInstance().get(Organization.class, orgId);
    TaxReport taxReport   = resolveTaxReport349(orgId, period);
    AcctSchema acctSchema = resolveAcctSchema(org);
    List<Period> periods  = resolvePeriods(orgId, year, period);

    if (periods.isEmpty()) {
      throw new OBException(
          "No periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    AEAT3492010ReportDao dao349 = new AEAT3492010ReportDao();

    List<TaxRate> taxesPurchase = dao349.get349Taxes(taxReport.getId(), "Purchase");
    List<TaxRate> taxesSales    = dao349.get349Taxes(taxReport.getId(), "Sales");

    Set<Invoice> allPurch  = dao349.get349Invoices(org, taxesPurchase, periods, acctSchema, true, null);
    Set<Invoice> corrPurch = dao349.getCorrectiveInvoices(allPurch);
    Set<Invoice> purch     = dao349.removeCorrectiveInvoices(allPurch, corrPurch);

    Set<Invoice> allSales  = dao349.get349Invoices(org, taxesSales, periods, acctSchema, false, null);
    Set<Invoice> corrSales = dao349.getCorrectiveInvoices(allSales);
    Set<Invoice> sales     = dao349.removeCorrectiveInvoices(allSales, corrSales);

    Set<Map<String, Object>> purchaseBP =
        dao349.getTaxBaseAmountPerBusinessPartner(purch, taxesPurchase, true, taxReport);
    Set<Map<String, Object>> salesBP =
        dao349.getTaxBaseAmountPerBusinessPartner(sales, taxesSales, false, taxReport);

    JSONArray operatorsArr = new JSONArray();
    Map<String, BigDecimal> summaryByKey = new LinkedHashMap<>();
    for (String k : Arrays.asList("E", "S", "A", "I")) summaryByKey.put(k, BigDecimal.ZERO);

    List<Map<String, Object>> all = new ArrayList<>(purchaseBP);
    all.addAll(salesBP);
    for (Map<String, Object> row : all) {
      String     bpId = (String)     row.get("BPId");
      BigDecimal base = (BigDecimal) row.get("BPTaxBaseAmount");
      String     key  = (String)     row.get("TaxKey");
      if (base == null || base.compareTo(BigDecimal.ZERO) == 0) continue;

      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpId);
      String name = bp != null ? bp.getName() : bpId;
      String nif  = bp != null && bp.getTaxID() != null ? bp.getTaxID() : "";

      JSONObject op = new JSONObject();
      op.put("bpId", bpId);
      op.put("nif",  nif);
      op.put("name", name);
      op.put("key",  key != null ? key : "");
      op.put("base", base.setScale(2, RoundingMode.HALF_UP).toString());
      operatorsArr.put(op);

      if (key != null && summaryByKey.containsKey(key)) {
        summaryByKey.put(key, summaryByKey.get(key).add(base));
      }
    }

    JSONObject summary = new JSONObject();
    for (Map.Entry<String, BigDecimal> e : summaryByKey.entrySet()) {
      summary.put("total" + e.getKey(),
          e.getValue().setScale(2, RoundingMode.HALF_UP).toString());
    }

    JSONArray invoicesArr = collectInvoices(purch, sales);

    JSONObject root = new JSONObject();
    root.put("operators", operatorsArr);
    root.put("summary",   summary);
    root.put("invoices",  invoicesArr);
    return root;
  }

  private JSONArray collectInvoices(Set<Invoice> purch, Set<Invoice> sales) throws Exception {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    JSONArray arr = new JSONArray();
    for (Invoice inv : purch) {
      arr.put(buildInvoiceRow(inv, "Compra", sdf));
    }
    for (Invoice inv : sales) {
      arr.put(buildInvoiceRow(inv, "Venta", sdf));
    }
    return arr;
  }

  private JSONObject buildInvoiceRow(Invoice inv, String type, SimpleDateFormat sdf)
      throws Exception {
    BusinessPartner bp = inv.getBusinessPartner();
    BigDecimal base = inv.getSummedLineAmount() != null
        ? inv.getSummedLineAmount().abs().setScale(2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
    JSONObject row = new JSONObject();
    row.put("ref",    inv.getDocumentNo());
    row.put("date",   sdf.format(inv.getInvoiceDate()));
    row.put("type",   type);
    row.put("party",  bp != null ? bp.getName() : "");
    row.put("nifIva", bp != null && bp.getTaxID() != null ? bp.getTaxID() : "");
    row.put("base",   base.toString());
    return row;
  }

  // ── generate ──────────────────────────────────────────────────────

  private void handleGenerate(String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    Organization org      = OBDal.getInstance().get(Organization.class, orgId);
    TaxReport taxReport   = resolveTaxReport349(orgId, period);
    AcctSchema acctSchema = resolveAcctSchema(org);
    List<Period> periods  = resolvePeriods(orgId, year, period);

    if (periods.isEmpty()) {
      throw new OBException(
          "No fiscal periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    String yearId    = periods.get(0).getYear().getId();
    String periodIds = periods.stream().map(Period::getId).collect(Collectors.joining(","));
    String filename  = "349_" + period + "_" + year;

    Map<String, String> inputParams = new HashMap<>();
    inputParams.put("FileName", filename);
    // Required: generateLine1 calls inputParams.get("Substitutive").equals("Y") — NPE if absent.
    inputParams.put("Substitutive", "N");
    // Phone and Contact: AEAT3492010Report checks constantParameters first (TaxReport config),
    // then falls back to inputParams. Query params override; fall back to AD_OrgInformation /
    // current user so generation works even without TaxReport pre-configuration.
    String phone   = request.getParameter("phone");
    String contact = request.getParameter("contact");
    if (phone == null || phone.isEmpty()) {
      phone = resolveOrgPhone(orgId);
    }
    if (contact == null || contact.isEmpty()) {
      contact = OBContext.getOBContext().getUser().getName();
    }
    if (phone   != null && !phone.isEmpty())   inputParams.put("Phone",   phone);
    if (contact != null && !contact.isEmpty()) inputParams.put("Contact", contact);

    OBTL_TaxReport_I report = (OBTL_TaxReport_I)
        Class.forName(taxReport.getJavaClassName()).getDeclaredConstructor().newInstance();

    HashMap<String, Object> result = report.generateElectronicFile(
        orgId, taxReport.getId(), acctSchema.getId(), yearId, periodIds, inputParams);

    Object fileContent = result.get("file");
    if (fileContent == null) {
      throw new OBException("generateElectronicFile returned no file content");
    }

    byte[] bytes = fileContent.toString().getBytes(StandardCharsets.ISO_8859_1);
    response.setContentType("text/plain");
    response.setCharacterEncoding("ISO-8859-1");
    response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + ".349\"");
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
    response.flushBuffer();
  }

  // ── modified ──────────────────────────────────────────────────────

  private void handleModified(String orgId, int year, String period, Date since,
      HttpServletResponse response) throws Exception {
    List<Period> periods = resolvePeriods(orgId, year, period);
    if (periods.isEmpty()) { writeModifiedJson(response, false, 0); return; }
    Date fromDate = periods.get(0).getStartingDate();
    Date toDate   = periods.get(periods.size() - 1).getEndingDate();
    if (fromDate == null || toDate == null) { writeModifiedJson(response, false, 0); return; }

    Long count = (Long) OBDal.getInstance().getSession()
        .createQuery(
            "select count(i.id) from Invoice i "
            + "where i.organization.id = :orgId "
            + "  and i.invoiceDate between :fromDate and :toDate "
            + "  and i.updated > :since")
        .setParameter("orgId",    orgId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate",   toDate)
        .setParameter(SINCE_KEY,  since)
        .uniqueResult();

    boolean modified = count != null && count > 0;
    writeModifiedJson(response, modified, count == null ? 0 : count.intValue());
  }

  private void writeModifiedJson(HttpServletResponse response, boolean modified, int count)
      throws Exception {
    JSONObject out = new JSONObject();
    out.put(MODIFIED, modified);
    out.put("count", count);
    response.setContentType(JSON_CT);
    response.getWriter().write(out.toString());
  }

  // ── resolution helpers ────────────────────────────────────────────

  private String resolveOrgPhone(String orgId) {
    OBCriteria<OrganizationInformation> crit =
        OBDal.getInstance().createCriteria(OrganizationInformation.class);
    crit.add(Restrictions.eq(OrganizationInformation.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.setMaxResults(1);
    List<OrganizationInformation> list = crit.list();
    if (list.isEmpty()) return null;
    // OrganizationInformation has no phone directly; try the org's user contact phone
    org.openbravo.model.ad.access.User contact = list.get(0).getUserContact();
    if (contact == null) return null;
    String phone = contact.getPhone();
    return phone != null && !phone.isEmpty() ? phone : contact.getAlternativePhone();
  }

  TaxReport resolveTaxReport349(String orgId, String periodCode) {
    String type = periodCode.startsWith("T") ? "Q" : "M";
    TaxReport report = findTaxReport(orgId, "AEAT3492010_" + type);
    if (report == null) report = findTaxReport(orgId, "AEAT349_" + type);
    if (report == null) {
      throw new OBException(
          "No TaxReport 349 found for org=" + orgId + " periodType=" + type);
    }
    return report;
  }

  private TaxReport findTaxReport(String orgId, String searchKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.eq(TaxReport.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, searchKey));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    return list.isEmpty() ? null : list.get(0);
  }

  private AcctSchema resolveAcctSchema(Organization org) {
    OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id", org.getClient().getId()));
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    List<AcctSchema> list = crit.list();
    if (list.isEmpty()) {
      throw new OBException("No AcctSchema found for client=" + org.getClient().getId());
    }
    return list.get(0);
  }

  @SuppressWarnings("unchecked")
  private List<Period> resolvePeriods(String orgId, int year, String periodCode) {
    int monthFrom, monthTo;
    if (periodCode.startsWith("T")) {
      int q = Integer.parseInt(periodCode.substring(1));
      monthFrom = (q - 1) * 3 + 1;
      monthTo   = q * 3;
    } else {
      monthFrom = monthTo = Integer.parseInt(periodCode);
    }
    return OBDal.getInstance().getSession()
        .createQuery(
            "from FinancialMgmtPeriod p "
            + "where p.organization.id = :orgId "
            + "  and p.year.fiscalYear = :year "
            + "  and p.periodNo between :from and :to "
            + "order by p.periodNo",
            Period.class)
        .setParameter("orgId", orgId)
        .setParameter("year",  String.valueOf(year))
        .setParameter("from",  (long) monthFrom)
        .setParameter("to",    (long) monthTo)
        .list();
  }
}
