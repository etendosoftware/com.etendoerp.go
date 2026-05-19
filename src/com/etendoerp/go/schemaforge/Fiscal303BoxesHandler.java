package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.ScrollableResults;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
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

class Fiscal303BoxesHandler {

  private static final Logger log = Logger.getLogger(Fiscal303BoxesHandler.class);

  private final NeoServlet servlet;

  Fiscal303BoxesHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  void handle(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (!"GET".equals(method) || !"boxes".equals(entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Only GET /fiscal303/boxes is supported");
      return;
    }
    try {
      String yearStr = request.getParameter("year");
      String period  = request.getParameter("period");
      if (yearStr == null || period == null) {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Missing required params: year, period");
        return;
      }
      int year = Integer.parseInt(yearStr);
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();

      ComputeResult cr = computeBoxes(orgId, year, period);
      JSONObject result = buildResponse(cr.boxes, cr.sources);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(result.toString());
    } catch (Exception e) {
      log.error("Error computing 303 boxes", e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
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

    int[] accruedBoxes   = { 3, 6, 9, 11, 13, 15, 18, 21, 24, 152, 158, 167 };
    int[] deductibleBoxes = { 29, 31, 33, 35, 37, 39, 41, 42, 43, 44 };
    BigDecimal accrued    = sumBoxes(b, accruedBoxes);
    BigDecimal deductible = sumBoxes(b, deductibleBoxes);
    b.put(27, round(accrued));
    b.put(45, round(deductible));
    b.put(46, round(accrued.subtract(deductible)));

    // resultado_final — standard company (100 % Estado, no pending credits, no complementary)
    BigDecimal r46 = b.getOrDefault(46, BigDecimal.ZERO);
    b.put(66, r46);   // amount attributable to Estado (box 65 % × box 46)
    b.put(69, r46);   // result before final adjustments (assumes boxes 64/76/77/78/68/108 = 0)
    b.put(71, r46);   // final declaration result (assumes boxes 70/109 = 0)
    // volume of operations — intracom and export mirrors
    BigDecimal r59 = b.getOrDefault(59, BigDecimal.ZERO);
    BigDecimal r60 = b.getOrDefault(60, BigDecimal.ZERO);
    if (r59.compareTo(BigDecimal.ZERO) > 0) b.put(93, r59);
    if (r60.compareTo(BigDecimal.ZERO) > 0) b.put(94, r60);

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

    List<TaxRate> allRates = new ArrayList<>();
    for (String id : rateToBoxes.keySet()) {
      TaxRate tr = OBDal.getInstance().get(TaxRate.class, id);
      if (tr != null) allRates.add(tr);
    }

    // invoiceId → row map (merged across tax lines)
    Map<String, Map<String, Object>> byInvoice = new LinkedHashMap<>();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    ScrollableResults sr = dao303.getInvoiceTax(
        org, allRates, periods, CashVATOperationType.ONLY_NONCASHVAT);
    try {
      while (sr.next()) {
        InvoiceTax it = (InvoiceTax) sr.get(0);
        Invoice inv   = it.getInvoice();
        String invId  = inv.getId();

        Map<String, Object> row = byInvoice.computeIfAbsent(invId, k -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("ref",   inv.getDocumentNo());
          r.put("date",  sdf.format(inv.getInvoiceDate()));
          String cat = inv.getDocumentType().getDocumentCategory();
          r.put("type",  "ARI".equals(cat) || "ARI_RM".equals(cat) ? "Venta" : "Compra");
          r.put("party", inv.getBusinessPartner() != null
              ? inv.getBusinessPartner().getName() : "");
          r.put("base",  BigDecimal.ZERO);
          r.put("vat",   BigDecimal.ZERO);
          r.put("boxes", new java.util.LinkedHashSet<Integer>());
          return r;
        });

        BigDecimal base = it.getTaxableAmount() != null ? it.getTaxableAmount().abs() : BigDecimal.ZERO;
        BigDecimal tax  = it.getTaxAmount()     != null ? it.getTaxAmount().abs()     : BigDecimal.ZERO;
        row.put("base", round(((BigDecimal) row.get("base")).add(base)));
        row.put("vat",  round(((BigDecimal) row.get("vat")).add(tax)));

        List<Integer> boxes = rateToBoxes.get(it.getTax().getId());
        if (boxes != null) {
          @SuppressWarnings("unchecked")
          java.util.LinkedHashSet<Integer> bSet = (java.util.LinkedHashSet<Integer>) row.get("boxes");
          bSet.addAll(boxes);
        }

        OBDal.getInstance().getSession().evict(it);
        OBDal.getInstance().getSession().evict(inv);
      }
    } finally {
      sr.close();
    }

    List<Map<String, Object>> result = new ArrayList<>(byInvoice.values());
    for (Map<String, Object> row : result) {
      // flatten boxes set → comma-separated string
      @SuppressWarnings("unchecked")
      java.util.LinkedHashSet<Integer> bSet = (java.util.LinkedHashSet<Integer>) row.get("boxes");
      List<Integer> sorted = new ArrayList<>(bSet);
      Collections.sort(sorted);
      StringBuilder sb = new StringBuilder();
      for (Integer bx : sorted) { if (sb.length() > 0) sb.append(","); sb.append(bx); }
      row.put("boxes", sb.toString());
      // total = base + vat
      BigDecimal base = (BigDecimal) row.get("base");
      BigDecimal vat  = (BigDecimal) row.get("vat");
      row.put("total", round(base.add(vat)));
    }
    return result;
  }

  private void fillSalesBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {

    // VAT_SALES_GENERAL — split by rate % → boxes 7/9 (21%), 4/6 (10%/7%/8%), 1/3 (4%/5%),
    // 150/152 (0%), 165/167 (2%)
    TaxReportParameter paramGeneral =
        dao303.getTaxReportParameter(taxReport, "VAT_SALES", "VAT_SALES_GENERAL");
    if (paramGeneral != null) {
      List<TaxRate> salesGeneral =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramGeneral);
      for (Map.Entry<BigDecimal, List<TaxRate>> e : splitByPercentage(salesGeneral).entrySet()) {
        BigDecimal pct = e.getKey();
        Map<String, BigDecimal> r = helper.calculateAmountsMap(e.getValue(), InvoiceType.ALL);
        BigDecimal base = r.get("TaxBaseAmount");
        BigDecimal tax  = r.get("TaxAmount");
        List<Integer> boxes;
        if (pct.compareTo(new BigDecimal("21")) == 0) {
          addToBox(b, 7, base); addToBox(b, 9, tax);
          boxes = java.util.Arrays.asList(7, 9);
        } else if (pct.compareTo(new BigDecimal("10")) == 0
            || pct.compareTo(new BigDecimal("7")) == 0
            || pct.compareTo(new BigDecimal("8")) == 0) {
          addToBox(b, 4, base); addToBox(b, 6, tax);
          boxes = java.util.Arrays.asList(4, 6);
        } else if (pct.compareTo(new BigDecimal("4")) == 0
            || pct.compareTo(new BigDecimal("5")) == 0) {
          addToBox(b, 1, base); addToBox(b, 3, tax);
          boxes = java.util.Arrays.asList(1, 3);
        } else if (pct.compareTo(BigDecimal.ZERO) == 0) {
          addToBox(b, 150, base); addToBox(b, 152, tax);
          boxes = java.util.Arrays.asList(150, 152);
        } else if (pct.compareTo(new BigDecimal("2")) == 0) {
          addToBox(b, 165, base); addToBox(b, 167, tax);
          boxes = java.util.Arrays.asList(165, 167);
        } else {
          boxes = Collections.emptyList();
        }
        if (!boxes.isEmpty()) {
          for (TaxRate tr : e.getValue()) rateToBoxes.put(tr.getId(), boxes);
        }
      }
    }

    // VAT_SALES_EU → boxes 10, 11 (adq. intracomunitarias — buyer self-assesses)
    fillGroupBoxes(b, helper, dao303, taxReport, "VAT_SALES", "VAT_SALES_EU",
        "Purchase", "No", "Yes", 10, 11, rateToBoxes);

    // VAT_SALES_ISP → boxes 12, 13 (inversión sujeto pasivo)
    fillGroupBoxes(b, helper, dao303, taxReport, "VAT_SALES", "VAT_SALES_ISP",
        "Purchase", "No", "No", 12, 13, rateToBoxes);

    // VAT_SALES_EC (recargo equivalencia) — split by %
    TaxReportParameter paramEC =
        dao303.getTaxReportParameter(taxReport, "VAT_SALES", "VAT_SALES_EC");
    if (paramEC != null) {
      List<TaxRate> ecTaxes =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramEC);
      for (Map.Entry<BigDecimal, List<TaxRate>> e : splitByPercentage(ecTaxes).entrySet()) {
        BigDecimal pct = e.getKey();
        Map<String, BigDecimal> r = helper.calculateAmountsMap(e.getValue(), InvoiceType.ALL);
        BigDecimal base = r.get("TaxBaseAmount");
        BigDecimal tax  = r.get("TaxAmount");
        List<Integer> boxes;
        if (pct.compareTo(new BigDecimal("1.40")) == 0) {
          addToBox(b, 19, base); addToBox(b, 21, tax);
          boxes = java.util.Arrays.asList(19, 21);
        } else if (pct.compareTo(new BigDecimal("5.20")) == 0) {
          addToBox(b, 22, base); addToBox(b, 24, tax);
          boxes = java.util.Arrays.asList(22, 24);
        } else if (pct.compareTo(new BigDecimal("0.50")) == 0) {
          addToBox(b, 16, base); addToBox(b, 18, tax);
          boxes = java.util.Arrays.asList(16, 18);
        } else if (pct.compareTo(new BigDecimal("1.75")) == 0) {
          addToBox(b, 156, base); addToBox(b, 158, tax);
          boxes = java.util.Arrays.asList(156, 158);
        } else {
          boxes = Collections.emptyList();
        }
        if (!boxes.isEmpty()) {
          for (TaxRate tr : e.getValue()) rateToBoxes.put(tr.getId(), boxes);
        }
      }
    }
  }

  private void fillPurchaseBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Normal_Operations",          "Purchase", "No", "No",  28, 29, rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Investment_Goods",            "Purchase", "No", "No",  30, 31, rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Import_Goods",                "Purchase", "No", "No",  32, 33, rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Import_Investment_Goods",     "Purchase", "No", "No",  34, 35, rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Intracommunity_Goods",        "Purchase", "No", "Yes", 36, 37, rateToBoxes);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Intracommunity_Investments",  "Purchase", "No", "Yes", 38, 39, rateToBoxes);
  }

  // taxBox == 0 means base-only (no corresponding tax amount box, e.g. 0% exempt rows)
  private void fillGroupBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport,
      String groupKey, String paramKey,
      String taxType, String equivCharge, String intracom,
      int baseBox, int taxBox, Map<String, List<Integer>> rateToBoxes) {
    TaxReportParameter param = dao303.getTaxReportParameter(taxReport, groupKey, paramKey);
    if (param == null) return;
    List<TaxRate> rates =
        dao303.get303Taxes(taxReport.getId(), taxType, equivCharge, intracom, param);
    if (rates.isEmpty()) return;
    Map<String, BigDecimal> result = helper.calculateAmountsMap(rates, InvoiceType.ALL);
    addToBox(b, baseBox, result.get("TaxBaseAmount"));
    if (taxBox > 0) addToBox(b, taxBox, result.get("TaxAmount"));
    List<Integer> boxes = taxBox > 0
        ? java.util.Arrays.asList(baseBox, taxBox)
        : java.util.Arrays.asList(baseBox);
    for (TaxRate tr : rates) rateToBoxes.put(tr.getId(), boxes);
  }

  private void fillAdditionalInfoBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes) {
    // Box 59: intra-community deliveries exempt (Información adicional)
    fillGroupBoxes(b, helper, dao303, taxReport,
        "Additional_Information", "303SALES_EU_EXEMPT",
        "All", "All", "All", 59, 0, rateToBoxes);
    // Box 60: exports and other exempt operations with deduction right
    fillGroupBoxes(b, helper, dao303, taxReport,
        "Additional_Information", "303EXPORTS_OTHEREXEMPT",
        "All", "All", "All", 60, 0, rateToBoxes);
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
      boxes.put(String.valueOf(e.getKey()), e.getValue().doubleValue());
    }
    BigDecimal accrued    = b.getOrDefault(27, BigDecimal.ZERO);
    BigDecimal deductible = b.getOrDefault(45, BigDecimal.ZERO);
    BigDecimal result     = b.getOrDefault(46, BigDecimal.ZERO);
    JSONObject summary = new JSONObject();
    summary.put("accrued",    accrued.doubleValue());
    summary.put("deductible", deductible.doubleValue());
    summary.put("result",     result.doubleValue());
    JSONArray sourcesArr = new JSONArray();
    for (Map<String, Object> row : sources) {
      JSONObject s = new JSONObject();
      for (Map.Entry<String, Object> e : row.entrySet()) {
        Object v = e.getValue();
        if (v instanceof BigDecimal) {
          s.put(e.getKey(), ((BigDecimal) v).doubleValue());
        } else {
          s.put(e.getKey(), v != null ? v.toString() : "");
        }
      }
      sourcesArr.put(s);
    }
    JSONObject root = new JSONObject();
    root.put("boxes",   boxes);
    root.put("summary", summary);
    root.put("sources", sourcesArr);
    return root;
  }
}
