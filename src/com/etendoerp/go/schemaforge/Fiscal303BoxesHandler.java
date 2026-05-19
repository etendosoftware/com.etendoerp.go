package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
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

      Map<Integer, BigDecimal> boxes = computeBoxes(orgId, year, period);
      JSONObject result = buildResponse(boxes);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(result.toString());
    } catch (Exception e) {
      log.error("Error computing 303 boxes", e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  // ── Internal ─────────────────────────────────────────────────────

  Map<Integer, BigDecimal> computeBoxes(String orgId, int year, String period) throws Exception {
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

    fillSalesBoxes(b, helper, dao303, taxReport);
    fillPurchaseBoxes(b, helper, dao303, taxReport);

    int[] accruedBoxes   = { 3, 6, 9, 11, 13, 15, 18, 21, 24, 152, 158, 167 };
    int[] deductibleBoxes = { 29, 31, 33, 35, 37, 39, 41, 42, 43, 44 };
    BigDecimal accrued    = sumBoxes(b, accruedBoxes);
    BigDecimal deductible = sumBoxes(b, deductibleBoxes);
    b.put(27, round(accrued));
    b.put(45, round(deductible));
    b.put(46, round(accrued.subtract(deductible)));

    return b;
  }

  private void fillSalesBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport) {

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
        if (pct.compareTo(new BigDecimal("21")) == 0) {
          addToBox(b, 7, base); addToBox(b, 9, tax);
        } else if (pct.compareTo(new BigDecimal("10")) == 0
            || pct.compareTo(new BigDecimal("7")) == 0
            || pct.compareTo(new BigDecimal("8")) == 0) {
          addToBox(b, 4, base); addToBox(b, 6, tax);
        } else if (pct.compareTo(new BigDecimal("4")) == 0
            || pct.compareTo(new BigDecimal("5")) == 0) {
          addToBox(b, 1, base); addToBox(b, 3, tax);
        } else if (pct.compareTo(BigDecimal.ZERO) == 0) {
          addToBox(b, 150, base); addToBox(b, 152, tax);
        } else if (pct.compareTo(new BigDecimal("2")) == 0) {
          addToBox(b, 165, base); addToBox(b, 167, tax);
        }
        // legacy rates (7%, 8%, 16%, 18%) are ignored in v1 — refine in follow-up
      }
    }

    // VAT_SALES_EU → boxes 10, 11 (intracom purchases / Adq. intracomunitarias)
    fillGroupBoxes(b, helper, dao303, taxReport, "VAT_SALES", "VAT_SALES_EU",
        "Purchase", "No", "Yes", 10, 11);

    // VAT_SALES_ISP → boxes 12, 13 (inversión sujeto pasivo)
    fillGroupBoxes(b, helper, dao303, taxReport, "VAT_SALES", "VAT_SALES_ISP",
        "Purchase", "No", "No", 12, 13);

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
        if (pct.compareTo(new BigDecimal("1.40")) == 0) {
          addToBox(b, 19, base); addToBox(b, 21, tax);
        } else if (pct.compareTo(new BigDecimal("5.20")) == 0) {
          addToBox(b, 22, base); addToBox(b, 24, tax);
        } else if (pct.compareTo(new BigDecimal("0.50")) == 0) {
          addToBox(b, 16, base); addToBox(b, 18, tax);
        } else if (pct.compareTo(new BigDecimal("1.75")) == 0) {
          addToBox(b, 156, base); addToBox(b, 158, tax);
        }
      }
    }
  }

  private void fillPurchaseBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport) {
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Normal_Operations",          "Purchase", "No", "No",  28, 29);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Investment_Goods",            "Purchase", "No", "No",  30, 31);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Import_Goods",                "Purchase", "No", "No",  32, 33);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Import_Investment_Goods",     "Purchase", "No", "No",  34, 35);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Intracommunity_Goods",        "Purchase", "No", "Yes", 36, 37);
    fillGroupBoxes(b, helper, dao303, taxReport,
        "VAT_PURCHASE", "Intracommunity_Investments",  "Purchase", "No", "Yes", 38, 39);
  }

  private void fillGroupBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport,
      String groupKey, String paramKey,
      String taxType, String equivCharge, String intracom,
      int baseBox, int taxBox) {
    TaxReportParameter param = dao303.getTaxReportParameter(taxReport, groupKey, paramKey);
    if (param == null) return;
    List<TaxRate> rates =
        dao303.get303Taxes(taxReport.getId(), taxType, equivCharge, intracom, param);
    if (rates.isEmpty()) return;
    Map<String, BigDecimal> result = helper.calculateAmountsMap(rates, InvoiceType.ALL);
    addToBox(b, baseBox, result.get("TaxBaseAmount"));
    addToBox(b, taxBox,  result.get("TaxAmount"));
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

  private JSONObject buildResponse(Map<Integer, BigDecimal> b) throws Exception {
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
    JSONObject root = new JSONObject();
    root.put("boxes",   boxes);
    root.put("summary", summary);
    return root;
  }
}
