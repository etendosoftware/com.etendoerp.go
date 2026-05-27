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

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.ScrollableResults;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import com.etendoerp.go.schemaforge.data.FiscalDecl;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.CashVATOperationType;
import org.openbravo.module.aeat303.es.api.InvoiceType;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;
import org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

class Fiscal303BoxesHandler {

  private static final Logger log = Logger.getLogger(Fiscal303BoxesHandler.class);

  private static final String BOXES           = "boxes";
  private static final String GENERATE        = "generate";
  private static final String DECLARATIONS    = "declarations";
  private static final String MODIFIED        = "modified";
  private static final String VAT_SALES       = "VAT_SALES";
  private static final String VAT_PURCHASE    = "VAT_PURCHASE";
  private static final String PURCHASE        = "Purchase";
  private static final String TAX_BASE_AMOUNT = "TaxBaseAmount";
  private static final String TAX_AMOUNT      = "TaxAmount";

  private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";
  private static final String DEFAULT_STATUS    = "borrador";
  private static final String PERIOD_KEY        = "period";
  private static final String SINCE_KEY         = "since";
  private static final String STATUS_KEY        = "status";
  private static final String FILE_NAME_KEY     = "fileName";
  private static final String FILE_EXTERNAL_KEY = "fileExternal";

  private static final BigDecimal PCT_21   = new BigDecimal("21");
  private static final BigDecimal PCT_10   = new BigDecimal("10");
  private static final BigDecimal PCT_7    = new BigDecimal("7");
  private static final BigDecimal PCT_8    = new BigDecimal("8");
  private static final BigDecimal PCT_4    = new BigDecimal("4");
  private static final BigDecimal PCT_5    = new BigDecimal("5");
  private static final BigDecimal PCT_2    = new BigDecimal("2");
  private static final BigDecimal PCT_1_40 = new BigDecimal("1.40");
  private static final BigDecimal PCT_5_20 = new BigDecimal("5.20");
  private static final BigDecimal PCT_0_50 = new BigDecimal("0.50");
  private static final BigDecimal PCT_1_75 = new BigDecimal("1.75");

  private final NeoServlet servlet;

  Fiscal303BoxesHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  void handle(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (DECLARATIONS.equals(entityName)) {
      try {
        handleDeclarations(method, request, response);
      } catch (Exception e) {
        log.error("Error in /fiscal303/declarations", e);
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
      }
      return;
    }
    if (!BOXES.equals(entityName) && !GENERATE.equals(entityName) && !MODIFIED.equals(entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Unknown fiscal303 entity: " + entityName);
      return;
    }
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Only GET is supported for /fiscal303/" + entityName);
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
      int year = Integer.parseInt(yearStr);
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      if (BOXES.equals(entityName)) {
        ComputeResult cr = computeBoxes(orgId, year, period);
        JSONObject result = buildResponse(cr.boxes, cr.sources);
        response.setContentType(JSON_CONTENT_TYPE);
        response.getWriter().write(result.toString());
      } else if (GENERATE.equals(entityName)) {
        String tipo = request.getParameter("tipo");
        handleGenerate(orgId, year, period, tipo, response);
      } else {
        long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
        handleModified(orgId, year, period, new java.util.Date(sinceMs), response);
      }
    } catch (Exception e) {
      log.error("Error in /fiscal303/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  private void handleGenerate(String orgId, int year, String period, String tipo,
      HttpServletResponse response) throws Exception {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);

    boolean quarterly = period.startsWith("T");
    String valueKey = quarterly ? "AEAT303_Q_" + year : "AEAT303_M_" + year;

    TaxReport taxReport   = resolveTaxReport(orgId, valueKey);
    AcctSchema acctSchema = resolveAcctSchema(org);
    List<Period> periods  = resolvePeriods(orgId, year, period);

    if (periods.isEmpty()) {
      throw new OBException(
          "No fiscal periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    String yearId    = periods.get(0).getYear().getId();
    String periodIds = periods.stream().map(Period::getId).collect(Collectors.joining(","));
    String reportId  = taxReport.getId();
    String acctId    = acctSchema.getId();
    String className = taxReport.getJavaClassName();

    Map<String, String> inputParams = new HashMap<>();
    String filename = "303_" + period + "_" + year;
    inputParams.put("FileName", filename);
    // Declaration type required by AEAT303_Utility.getCheckedInputParameter.
    // Maps result kind from frontend: compensar→C, ingresar→I, devolver→V, default→N (result zero).
    String decl = "N";
    if ("C".equals(tipo) || "I".equals(tipo) || "V".equals(tipo) || "U".equals(tipo) || "G".equals(tipo)) {
      decl = tipo;
    } else if ("compensar".equals(tipo)) {
      decl = "C";
    } else if ("ingresar".equals(tipo)) {
      decl = "I";
    } else if ("devolver".equals(tipo)) {
      decl = "V";
    }
    inputParams.put("Declaration_" + decl, "Y");
    // Box 65: percentage attributable to the State (always 100 for Modelo 303).
    inputParams.put("ToPublicTreasury", "100");

    OBTL_TaxReport_I report = (OBTL_TaxReport_I)
        Class.forName(className).getDeclaredConstructor().newInstance();

    HashMap<String, Object> result =
        report.generateElectronicFile(orgId, reportId, acctId, yearId, periodIds, inputParams);

    Object fileContent = result.get("file");
    if (fileContent == null) {
      throw new OBException("generateElectronicFile returned no file content");
    }

    byte[] bytes = fileContent.toString().getBytes(StandardCharsets.ISO_8859_1);
    response.setContentType("text/plain");
    response.setCharacterEncoding("ISO-8859-1");
    response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + ".txt\"");
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
    response.flushBuffer();
  }

  private void handleModified(String orgId, int year, String period, java.util.Date since,
      HttpServletResponse response) throws Exception {
    List<Period> periods = resolvePeriods(orgId, year, period);
    if (periods.isEmpty()) {
      writeModifiedJson(response, false, 0);
      return;
    }

    java.util.Date fromDate = periods.get(0).getStartingDate();
    java.util.Date toDate   = periods.get(periods.size() - 1).getEndingDate();
    if (fromDate == null || toDate == null) {
      writeModifiedJson(response, false, 0);
      return;
    }

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
    response.setContentType(JSON_CONTENT_TYPE);
    response.getWriter().write(out.toString());
  }

  private void handleDeclarations(String method, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String orgId    = OBContext.getOBContext().getCurrentOrganization().getId();
    response.setContentType(JSON_CONTENT_TYPE);
    if ("GET".equals(method)) {
      handleDeclGet(clientId, orgId, response);
    } else if ("POST".equals(method)) {
      handleDeclPost(request, response);
    } else if ("PUT".equals(method)) {
      handleDeclPut(clientId, request, response);
    } else if ("DELETE".equals(method)) {
      handleDeclDelete(clientId, request, response);
    } else {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Unsupported method for /fiscal303/declarations: " + method);
    }
  }

  private void handleDeclGet(String clientId, String orgId, HttpServletResponse response)
      throws Exception {
    OBCriteria<FiscalDecl> crit = OBDal.getInstance().createCriteria(FiscalDecl.class);
    crit.add(Restrictions.eq("client.id", clientId));
    crit.add(Restrictions.eq("organization.id", orgId));
    crit.addOrderBy(FiscalDecl.PROPERTY_FISCALYEAR, false);
    crit.addOrderBy(FiscalDecl.PROPERTY_PERIOD, false);
    crit.addOrderBy(FiscalDecl.PROPERTY_FISCALMODEL, true);
    JSONArray arr = new JSONArray();
    for (FiscalDecl decl : crit.list()) arr.put(declToJson(decl));
    JSONObject out = new JSONObject();
    out.put("data", arr);
    response.getWriter().write(out.toString());
  }

  private void handleDeclPost(HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    JSONObject body = readJsonBody(request);
    String model    = body.getString("model");
    long   year     = body.getLong("year");
    String period   = body.getString(PERIOD_KEY);
    String declType = "com".equals(body.optString("type")) ? "C" : "O";
    String status   = body.has(STATUS_KEY) ? body.getString(STATUS_KEY) : DEFAULT_STATUS;

    FiscalDecl decl = OBProvider.getInstance().get(FiscalDecl.class);
    decl.setClient(OBContext.getOBContext().getCurrentClient());
    decl.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    decl.setCreatedBy(OBContext.getOBContext().getUser());
    decl.setUpdatedBy(OBContext.getOBContext().getUser());
    decl.setFiscalModel(model);
    decl.setFiscalYear(year);
    decl.setPeriod(period);
    decl.setDeclarationType(declType);
    decl.setDeclarationStatus(status);
    OBDal.getInstance().save(decl);
    JSONObject created = declToJson(decl);
    OBDal.getInstance().commitAndClose();

    response.setStatus(HttpServletResponse.SC_CREATED);
    response.getWriter().write(created.toString());
  }

  private void handleDeclPut(String clientId, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String id    = request.getParameter("id");
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    if (id == null || id.isEmpty()) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing param: id");
      return;
    }
    JSONObject body      = readJsonBody(request);
    boolean hasStatus    = body.has(STATUS_KEY);
    String status        = hasStatus ? body.getString(STATUS_KEY) : null;
    boolean hasFileExt   = body.has(FILE_EXTERNAL_KEY);
    boolean fileExternal = body.optBoolean(FILE_EXTERNAL_KEY, false);
    boolean hasFileName  = body.has(FILE_NAME_KEY);
    String  fileName     = hasFileName && !body.isNull(FILE_NAME_KEY)
        ? body.getString(FILE_NAME_KEY) : null;

    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, id);
    if (decl == null || !clientId.equals(decl.getClient().getId())
        || !orgId.equals(decl.getOrganization().getId())) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + id);
      return;
    }
    if (hasStatus)   decl.setDeclarationStatus(status);
    if (hasFileExt)  decl.setFileExternal(fileExternal);
    if (hasFileName) decl.setDeclarationFileName(fileName);
    decl.setUpdatedBy(OBContext.getOBContext().getUser());
    OBDal.getInstance().commitAndClose();
    response.getWriter().write("{\"ok\":true}");
  }

  private void handleDeclDelete(String clientId, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String id    = request.getParameter("id");
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    if (id == null || id.isEmpty()) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing param: id");
      return;
    }
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, id);
    if (decl == null || !clientId.equals(decl.getClient().getId())
        || !orgId.equals(decl.getOrganization().getId())) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + id);
      return;
    }
    OBDal.getInstance().remove(decl);
    OBDal.getInstance().commitAndClose();
    response.getWriter().write("{\"ok\":true}");
  }

  private JSONObject declToJson(FiscalDecl decl) throws Exception {
    JSONObject o = new JSONObject();
    o.put("id",           decl.getId() != null ? decl.getId() : "");
    o.put("model",        decl.getFiscalModel() != null ? decl.getFiscalModel() : "");
    o.put("year",         decl.getFiscalYear() != null ? decl.getFiscalYear().intValue() : 0);
    o.put(PERIOD_KEY,     decl.getPeriod() != null ? decl.getPeriod() : "");
    String dt = decl.getDeclarationType();
    String dtNormalized = dt != null ? dt.trim() : "";
    o.put("type",         "C".equals(dtNormalized) ? "com" : "ord");
    o.put(STATUS_KEY,     decl.getDeclarationStatus() != null ? decl.getDeclarationStatus() : DEFAULT_STATUS);
    o.put(FILE_NAME_KEY,  decl.getDeclarationFileName() != null
        ? decl.getDeclarationFileName() : JSONObject.NULL);
    o.put(FILE_EXTERNAL_KEY, Boolean.TRUE.equals(decl.isFileExternal()));
    o.put("updatedAt",    decl.getUpdated() != null ? decl.getUpdated().getTime() : 0L);
    return o;
  }

  private JSONObject readJsonBody(HttpServletRequest request) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) sb.append(line);
    }
    return new JSONObject(sb.toString());
  }

  // ── Internal ─────────────────────────────────────────────────────

  static class ComputeResult {
    final Map<Integer, BigDecimal> boxes;
    final List<Map<String, Object>> sources;
    ComputeResult(Map<Integer, BigDecimal> boxes, List<Map<String, Object>> sources) {
      this.boxes = boxes;
      this.sources = sources;
    }
  }

  static class BoxGroupConfig {
    final String groupKey;
    final String paramKey;
    final String taxType;
    final String equivCharge;
    final String intracom;
    final int baseBox;
    final int taxBox;

    BoxGroupConfig(String groupKey, String paramKey, String taxType,
        String equivCharge, String intracom, int baseBox, int taxBox) {
      this.groupKey    = groupKey;
      this.paramKey    = paramKey;
      this.taxType     = taxType;
      this.equivCharge = equivCharge;
      this.intracom    = intracom;
      this.baseBox     = baseBox;
      this.taxBox      = taxBox;
    }
  }

  ComputeResult computeBoxes(String orgId, int year, String period) throws Exception {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);

    boolean quarterly = period.startsWith("T");
    String valueKey = quarterly
        ? "AEAT303_Q_" + year
        : "AEAT303_M_" + year;
    TaxReport taxReport = resolveTaxReport(orgId, valueKey);

    AcctSchema acctSchema = resolveAcctSchema(org);

    List<Period> periods = resolvePeriods(orgId, year, period);
    if (periods.isEmpty()) {
      throw new OBException(
          "No periods found for org=" + orgId + " year=" + year + " period=" + period);
    }

    AEAT303CalculationsHelper helper =
        new AEAT303CalculationsHelper(org, periods, acctSchema, log);
    AEAT303Report2014Dao dao303 = new AEAT303Report2014Dao();

    Map<Integer, BigDecimal> b = new HashMap<>();
    // taxRateId → list of box numbers it contributes to
    Map<String, List<Integer>> rateToBoxes = new HashMap<>();

    fillSalesBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillPurchaseBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillAdditionalInfoBoxes(b, helper, dao303, taxReport, rateToBoxes);

    computeSummaryBoxes(b);

    List<Map<String, Object>> sources = collectSources(org, periods, dao303, rateToBoxes);

    return new ComputeResult(b, sources);
  }

  /**
   * Iterates C_INVOICETAX for all tracked tax rates and builds a per-invoice source row.
   * Groups multiple tax lines of the same invoice into one row.
   */
  private List<Map<String, Object>> collectSources(
      Organization org, List<Period> periods,
      AEAT303Report2014Dao dao303,
      Map<String, List<Integer>> rateToBoxes) {

    if (rateToBoxes.isEmpty()) return Collections.emptyList();

    List<TaxRate> allRates = buildRatesList(rateToBoxes);
    Map<String, Map<String, Object>> byInvoice = new LinkedHashMap<>();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    ScrollableResults sr = dao303.getInvoiceTax(
        org, allRates, periods, CashVATOperationType.ONLY_NONCASHVAT);
    try {
      while (sr.next()) {
        InvoiceTax it = (InvoiceTax) sr.get(0);
        Invoice inv   = it.getInvoice();
        Map<String, Object> row = byInvoice.computeIfAbsent(inv.getId(), k -> buildNewInvoiceRow(inv, sdf));
        accumulateInvoiceTax(row, it, rateToBoxes);
        OBDal.getInstance().getSession().evict(it);
        OBDal.getInstance().getSession().evict(inv);
      }
    } finally {
      sr.close();
    }

    List<Map<String, Object>> result = new ArrayList<>(byInvoice.values());
    result.forEach(this::finalizeInvoiceRow);
    return result;
  }

  private List<TaxRate> buildRatesList(Map<String, List<Integer>> rateToBoxes) {
    List<TaxRate> allRates = new ArrayList<>();
    for (String id : rateToBoxes.keySet()) {
      TaxRate tr = OBDal.getInstance().get(TaxRate.class, id);
      if (tr != null) allRates.add(tr);
    }
    return allRates;
  }

  private Map<String, Object> buildNewInvoiceRow(Invoice inv, SimpleDateFormat sdf) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("ref",   inv.getDocumentNo());
    r.put("date",  sdf.format(inv.getInvoiceDate()));
    String cat = inv.getDocumentType().getDocumentCategory();
    r.put("type",  "ARI".equals(cat) || "ARI_RM".equals(cat) ? "Venta" : "Compra");
    r.put("party", inv.getBusinessPartner() != null ? inv.getBusinessPartner().getName() : "");
    r.put("base",  BigDecimal.ZERO);
    r.put("vat",   BigDecimal.ZERO);
    r.put(BOXES,   new java.util.LinkedHashSet<Integer>());
    return r;
  }

  private void accumulateInvoiceTax(Map<String, Object> row, InvoiceTax it,
      Map<String, List<Integer>> rateToBoxes) {
    BigDecimal base = it.getTaxableAmount() != null ? it.getTaxableAmount().abs() : BigDecimal.ZERO;
    BigDecimal tax  = it.getTaxAmount()     != null ? it.getTaxAmount().abs()     : BigDecimal.ZERO;
    row.put("base", round(((BigDecimal) row.get("base")).add(base)));
    row.put("vat",  round(((BigDecimal) row.get("vat")).add(tax)));
    List<Integer> boxes = rateToBoxes.get(it.getTax().getId());
    if (boxes != null) {
      @SuppressWarnings("unchecked")
      java.util.LinkedHashSet<Integer> bSet = (java.util.LinkedHashSet<Integer>) row.get(BOXES);
      bSet.addAll(boxes);
    }
  }

  void finalizeInvoiceRow(Map<String, Object> row) {
    @SuppressWarnings("unchecked")
    java.util.LinkedHashSet<Integer> bSet = (java.util.LinkedHashSet<Integer>) row.get(BOXES);
    List<Integer> sorted = new ArrayList<>(bSet);
    Collections.sort(sorted);
    StringBuilder sb = new StringBuilder();
    for (Integer bx : sorted) {
      if (sb.length() > 0) {
        sb.append(",");
      }
      sb.append(bx);
    }
    row.put(BOXES, sb.toString());
    BigDecimal base = (BigDecimal) row.get("base");
    BigDecimal vat  = (BigDecimal) row.get("vat");
    row.put("total", round(base.add(vat)));
  }

  // Package-private for unit testing — injects pre-built helper and dao,
  // skips DB lookups and source collection.
  @SuppressWarnings("java:S1172")
  ComputeResult computeBoxes(Organization org, TaxReport taxReport,
      List<Period> periods, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303) {
    Map<Integer, BigDecimal> b = new HashMap<>();
    Map<String, List<Integer>> rateToBoxes = new HashMap<>();

    fillSalesBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillPurchaseBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillAdditionalInfoBoxes(b, helper, dao303, taxReport, rateToBoxes);
    computeSummaryBoxes(b);

    return new ComputeResult(b, Collections.emptyList());
  }

  private void fillSalesBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {

    // VAT_SALES_GENERAL — split by rate % → boxes 7/9 (21%), 4/6 (10%/7%/8%), 1/3 (4%/5%),
    // 150/152 (0%), 165/167 (2%)
    TaxReportParameter paramGeneral =
        dao303.getTaxReportParameter(taxReport, VAT_SALES, "VAT_SALES_GENERAL");
    if (paramGeneral != null) {
      List<TaxRate> salesGeneral =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramGeneral);
      applyPercentageSplit(b, helper, salesGeneral, this::vatGeneralBoxes, rateToBoxes);
    }

    // VAT_SALES_EU → boxes 10, 11 (adq. intracomunitarias — buyer self-assesses)
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_SALES, "VAT_SALES_EU", PURCHASE, "No", "Yes", 10, 11), rateToBoxes);

    // VAT_SALES_ISP → boxes 12, 13 (inversión sujeto pasivo)
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_SALES, "VAT_SALES_ISP", PURCHASE, "No", "No", 12, 13), rateToBoxes);

    // VAT_SALES_EC (recargo equivalencia) — split by %
    TaxReportParameter paramEC =
        dao303.getTaxReportParameter(taxReport, VAT_SALES, "VAT_SALES_EC");
    if (paramEC != null) {
      List<TaxRate> ecTaxes =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramEC);
      applyPercentageSplit(b, helper, ecTaxes, this::vatEcBoxes, rateToBoxes);
    }
  }

  List<Integer> vatGeneralBoxes(BigDecimal pct) {
    if (pct == null) return Collections.emptyList();
    if (pct.compareTo(PCT_21) == 0) return java.util.Arrays.asList(7, 9);
    if (pct.compareTo(PCT_10) == 0
        || pct.compareTo(PCT_7)  == 0
        || pct.compareTo(PCT_8)  == 0) return java.util.Arrays.asList(4, 6);
    if (pct.compareTo(PCT_4) == 0
        || pct.compareTo(PCT_5) == 0) return java.util.Arrays.asList(1, 3);
    if (pct.compareTo(BigDecimal.ZERO) == 0) return java.util.Arrays.asList(150, 152);
    if (pct.compareTo(PCT_2) == 0) return java.util.Arrays.asList(165, 167);
    return Collections.emptyList();
  }

  List<Integer> vatEcBoxes(BigDecimal pct) {
    if (pct == null) return Collections.emptyList();
    if (pct.compareTo(PCT_1_40) == 0) return java.util.Arrays.asList(19, 21);
    if (pct.compareTo(PCT_5_20) == 0) return java.util.Arrays.asList(22, 24);
    if (pct.compareTo(PCT_0_50) == 0) return java.util.Arrays.asList(16, 18);
    if (pct.compareTo(PCT_1_75) == 0) return java.util.Arrays.asList(156, 158);
    return Collections.emptyList();
  }

  private void applyPercentageSplit(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      List<TaxRate> rates, Function<BigDecimal, List<Integer>> boxMapper,
      Map<String, List<Integer>> rateToBoxes) {
    for (Map.Entry<BigDecimal, List<TaxRate>> e : splitByPercentage(rates).entrySet()) {
      BigDecimal pct = e.getKey();
      Map<String, BigDecimal> r = helper.calculateAmountsMap(e.getValue(), InvoiceType.ALL);
      List<Integer> boxes = boxMapper.apply(pct);
      if (!boxes.isEmpty()) {
        addToBox(b, boxes.get(0), r.get(TAX_BASE_AMOUNT));
        addToBox(b, boxes.get(1), r.get(TAX_AMOUNT));
        for (TaxRate tr : e.getValue()) rateToBoxes.put(tr.getId(), boxes);
      }
    }
  }

  private void fillPurchaseBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Normal_Operations",         PURCHASE, "No", "No",  28, 29), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Investment_Goods",           PURCHASE, "No", "No",  30, 31), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Import_Goods",               PURCHASE, "No", "No",  32, 33), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Import_Investment_Goods",    PURCHASE, "No", "No",  34, 35), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Intracommunity_Goods",       PURCHASE, "No", "Yes", 36, 37), rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig(VAT_PURCHASE, "Intracommunity_Investments", PURCHASE, "No", "Yes", 38, 39), rateToBoxes);
  }

  // taxBox == 0 means base-only (no corresponding tax amount box, e.g. 0% exempt rows)
  private void fillGroupBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport,
      BoxGroupConfig cfg, Map<String, List<Integer>> rateToBoxes) {
    TaxReportParameter param = dao303.getTaxReportParameter(taxReport, cfg.groupKey, cfg.paramKey);
    if (param == null) return;
    List<TaxRate> rates =
        dao303.get303Taxes(taxReport.getId(), cfg.taxType, cfg.equivCharge, cfg.intracom, param);
    if (rates.isEmpty()) return;
    Map<String, BigDecimal> result = helper.calculateAmountsMap(rates, InvoiceType.ALL);
    addToBox(b, cfg.baseBox, result.get(TAX_BASE_AMOUNT));
    if (cfg.taxBox > 0) addToBox(b, cfg.taxBox, result.get(TAX_AMOUNT));
    List<Integer> boxes = cfg.taxBox > 0
        ? java.util.Arrays.asList(cfg.baseBox, cfg.taxBox)
        : java.util.Arrays.asList(cfg.baseBox);
    for (TaxRate tr : rates) rateToBoxes.put(tr.getId(), boxes);
  }

  private void fillAdditionalInfoBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {
    // "Additional_Information" group only exists in monthly reports; quarterly reports use "Difference".
    // "Difference" is present in all reports and carries the same tax rates, so use it universally.
    // Box 59: intra-community deliveries (entregas intracomunitarias exentas)
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig("Difference", "IntracommunitySales", "All", "All", "All", 59, 0), rateToBoxes);
    // Box 60: exports and other exempt operations with deduction right
    fillGroupBoxes(b, helper, dao303, taxReport,
        new BoxGroupConfig("Difference", "ExportsAndOperations", "All", "All", "All", 60, 0), rateToBoxes);
  }

  // resultado_final — standard company (100 % Estado, no pending credits, no complementary)
  private void computeSummaryBoxes(Map<Integer, BigDecimal> b) {
    int[] accruedBoxes    = { 3, 6, 9, 11, 13, 15, 18, 21, 24, 152, 158, 167 };
    int[] deductibleBoxes = { 29, 31, 33, 35, 37, 39, 41, 42, 43, 44 };
    BigDecimal accrued    = sumBoxes(b, accruedBoxes);
    BigDecimal deductible = sumBoxes(b, deductibleBoxes);
    b.put(27, round(accrued));
    b.put(45, round(deductible));
    b.put(46, round(accrued.subtract(deductible)));

    BigDecimal r46 = b.getOrDefault(46, BigDecimal.ZERO);
    b.put(66, r46);   // amount attributable to Estado (box 65 % × box 46)
    b.put(69, r46);   // result before final adjustments (assumes boxes 64/76/77/78/68/108 = 0)
    b.put(71, r46);   // final declaration result (assumes boxes 70/109 = 0)

    BigDecimal r59 = b.getOrDefault(59, BigDecimal.ZERO);
    BigDecimal r60 = b.getOrDefault(60, BigDecimal.ZERO);
    if (r59.compareTo(BigDecimal.ZERO) > 0) b.put(93, r59);
    if (r60.compareTo(BigDecimal.ZERO) > 0) b.put(94, r60);
  }

  // ── Resolution helpers ───────────────────────────────────────────

  private TaxReport resolveTaxReport(String orgId, String valueKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.eq(TaxReport.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, valueKey));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    if (list.isEmpty()) {
      throw new OBException(
          "No TaxReport found for org=" + orgId + " searchKey=" + valueKey);
    }
    return list.get(0);
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
    int monthFrom;
    int monthTo;
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
        .setParameter("year", String.valueOf(year))
        .setParameter("from", (long) monthFrom)
        .setParameter("to",   (long) monthTo)
        .list();
  }

  // ── Utility ──────────────────────────────────────────────────────

  private void addToBox(Map<Integer, BigDecimal> b, int box, BigDecimal val) {
    if (val == null || val.compareTo(BigDecimal.ZERO) == 0) return;
    b.merge(box, val, BigDecimal::add);
  }

  private BigDecimal sumBoxes(Map<Integer, BigDecimal> b, int[] boxes) {
    BigDecimal sum = BigDecimal.ZERO;
    for (int box : boxes) sum = sum.add(b.getOrDefault(box, BigDecimal.ZERO));
    return sum;
  }

  private BigDecimal round(BigDecimal v) {
    return v.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private Map<BigDecimal, List<TaxRate>> splitByPercentage(List<TaxRate> rates) {
    Map<BigDecimal, List<TaxRate>> map = new java.util.LinkedHashMap<>();
    for (TaxRate r : rates) {
      BigDecimal pct = r.getRate().abs().setScale(2, java.math.RoundingMode.HALF_UP);
      map.computeIfAbsent(pct, k -> new ArrayList<>()).add(r);
    }
    return map;
  }

  private JSONObject buildResponse(Map<Integer, BigDecimal> b,
      List<Map<String, Object>> sources) throws Exception {
    JSONObject boxes = new JSONObject();
    for (Map.Entry<Integer, BigDecimal> e : b.entrySet()) {
      boxes.put(String.valueOf(e.getKey()), e.getValue().toString());
    }
    BigDecimal accrued    = b.getOrDefault(27, BigDecimal.ZERO);
    BigDecimal deductible = b.getOrDefault(45, BigDecimal.ZERO);
    BigDecimal result     = b.getOrDefault(46, BigDecimal.ZERO);
    JSONObject summary = new JSONObject();
    summary.put("accrued",    accrued.toString());
    summary.put("deductible", deductible.toString());
    summary.put("result",     result.toString());
    JSONArray sourcesArr = new JSONArray();
    for (Map<String, Object> row : sources) {
      JSONObject s = new JSONObject();
      for (Map.Entry<String, Object> e : row.entrySet()) {
        Object v = e.getValue();
        if (v instanceof BigDecimal) {
          s.put(e.getKey(), v.toString());
        } else {
          s.put(e.getKey(), v != null ? v.toString() : "");
        }
      }
      sourcesArr.put(s);
    }
    JSONObject root = new JSONObject();
    root.put(BOXES,     boxes);
    root.put("summary", summary);
    root.put("sources", sourcesArr);
    return root;
  }
}
