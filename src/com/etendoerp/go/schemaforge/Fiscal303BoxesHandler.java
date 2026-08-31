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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat303.es.api.InvoiceType;
import org.openbravo.module.aeat303.es.presentation.AEAT303DeclarationData;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;
import org.openbravo.module.aeat303.es.report.v2014.AEAT303Report2014Dao;
import org.openbravo.module.aeat303.es.util.AEAT303CalculationsHelper;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

import com.etendoerp.go.schemaforge.data.FiscalDecl;

class Fiscal303BoxesHandler extends AbstractFiscalHandler {

  // Package-private (not private): also read by Fiscal303SourcesSupport, which owns the
  // invoice-sources-building concern (moved out of this class in ETP-4755 to keep this class's
  // method count under the SonarQube java:S1448 threshold — see that class's header javadoc).
  static final String BOXES        = "boxes";
  private static final String GENERATE     = "generate";
  private static final String SUBMIT       = "submit";
  private static final String VAT_SALES    = "VAT_SALES";
  private static final String VAT_PURCHASE    = "VAT_PURCHASE";
  private static final String PURCHASE        = "Purchase";
  private static final String TAX_BASE_AMOUNT = "TaxBaseAmount";
  private static final String TAX_AMOUNT      = "TaxAmount";

  // ── AEAT 303 telematic submission (POST /neo/fiscal303/submit) ──────────
  // Package-private (not private): also read by Fiscal303SubmissionSupport, which owns the
  // /generate and /submit entities (moved out of this class in ETP-4456 to keep this class's
  // method count under the SonarQube java:S1448 threshold — see that class's header javadoc).
  static final String ID_KEY = "id";
  /** Shared by {@code handleSubmit}'s body parsing (Fiscal303SubmissionSupport) and this class's
   *  {@link #buildSubmissionResultJson}/{@link #buildFailureJson} JSON-shape helpers — extracted
   *  (ETP-4456, SonarQube java:S1192) so the literal isn't duplicated across the two classes. */
  static final String PARAM_TEST_MODE = "testMode";
  /** AEAT declaration type character for "a ingresar" (type I): the only type that uses NRC. */
  private static final String DECLARATION_TYPE_INGRESO = "I";

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
  private static final BigDecimal PCT_0_26 = new BigDecimal("0.26");
  private static final BigDecimal PCT_0_62 = new BigDecimal("0.62");
  private static final BigDecimal PCT_1_00 = new BigDecimal("1.00");
  private static final BigDecimal PCT_1_75 = new BigDecimal("1.75");

  private final Fiscal303SubmissionSupport submissionSupport;
  // Package-private (not private): Fiscal303BoxesHandlerTest calls finalizeInvoiceRow directly on
  // this field to test it in isolation — see Fiscal303SourcesSupport's header javadoc.
  final Fiscal303SourcesSupport sourcesSupport;

  Fiscal303BoxesHandler(NeoServlet servlet) {
    super(servlet);
    this.submissionSupport = new Fiscal303SubmissionSupport(this);
    this.sourcesSupport = new Fiscal303SourcesSupport(this);
  }

  @Override
  protected boolean isKnownEntity(String entityName) {
    return BOXES.equals(entityName) || GENERATE.equals(entityName) || SUBMIT.equals(entityName)
        || MODIFIED.equals(entityName);
  }

  @Override
  protected boolean allowsPost(String entityName) {
    return SUBMIT.equals(entityName);
  }

  /**
   * {@code submit} files the declaration with the AEAT, so it is POST-only — the same treatment
   * {@code /fiscal349/validate-vies} already gets. {@code boxes}, {@code generate} and
   * {@code modified} are reads and stay on GET.
   *
   * <p>ETP-5027 (QA F7): {@code submit} was listed in {@link #isKnownEntity} and accepted by
   * {@link #allowsPost}, but {@code allowsGet} was never overridden, so the base default of
   * {@code true} let a GET through the method check straight into a real AEAT filing. See
   * {@link AbstractFiscalHandler#allowsGet} for why a side-effecting GET is unacceptable here
   * (history/cache/log exposure, client and proxy auto-retry, and plain HTTP semantics) — the
   * reason is NOT a drive-by or CSRF vector, which the Bearer-token requirement already rules
   * out.
   */
  @Override
  protected boolean allowsGet(String entityName) {
    return !SUBMIT.equals(entityName);
  }

  @Override
  protected void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
    try {
      if (BOXES.equals(entityName)) {
        ComputeResult cr = computeBoxes(orgId, year, period);
        JSONObject result = buildResponse(cr.boxes, cr.sources);
        response.setContentType(JSON_CT);
        response.getWriter().write(result.toString());
      } else if (GENERATE.equals(entityName)) {
        String tipo = request.getParameter("tipo");
        submissionSupport.handleGenerate(orgId, year, period, tipo, request, response);
      } else if (SUBMIT.equals(entityName)) {
        String tipo = request.getParameter("tipo");
        String declId = request.getParameter(ID_KEY);
        submissionSupport.handleSubmit(orgId, year, period, tipo, declId, request, response);
      } else {
        long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
        handleModified(orgId, year, period, new java.util.Date(sinceMs), response);
      }
    } catch (FiscalHandlerException e) {
      throw e;
    } catch (Exception e) {
      throw new FiscalHandlerException(e);
    }
  }

  @Override
  protected String getModelKey() {
    return "fiscal303";
  }

  /** True when the declaration exists and belongs to the current client/organization. */
  boolean belongsTo(FiscalDecl decl, String clientId, String orgId) {
    return decl != null
        && decl.getClient() != null && clientId.equals(decl.getClient().getId())
        && decl.getOrganization() != null && orgId.equals(decl.getOrganization().getId());
  }

  /**
   * NRC only applies to type I (ingreso) declarations — mirrors
   * {@code AEAT303PresentationServlet#resolveNrcForSubmission} exactly, so a value entered for a
   * non-I declaration is never forwarded to the AEAT.
   */
  static String resolveNrcForSubmission(String declarationType, String nrc) {
    return DECLARATION_TYPE_INGRESO.equals(declarationType) ? StringUtils.defaultString(nrc) : "";
  }

  static String safeFileToken(String value) {
    String token = StringUtils.defaultIfBlank(value, "NA");
    return token.replaceAll("[^A-Za-z0-9]", "_");
  }

  JSONObject declarationDataJson(AEAT303DeclarationData data) throws Exception {
    JSONObject o = new JSONObject();
    o.put("nif", StringUtils.defaultString(data.getNif()));
    o.put("businessName", StringUtils.defaultString(data.getBusinessName()));
    o.put("fiscalYear", StringUtils.defaultString(data.getFiscalYear()));
    o.put(PERIOD_KEY, StringUtils.defaultString(data.getPeriod()));
    o.put("declarationType", StringUtils.defaultString(data.getDeclarationType()));
    o.put("resultAmount",
        data.getResultAmount() != null ? data.getResultAmount().toString() : JSONObject.NULL);
    o.put("iban", data.getIban() != null ? data.getIban() : JSONObject.NULL);
    return o;
  }

  /** Builds the response for a submission that reached the AEAT (successfully or not). */
  JSONObject buildSubmissionResultJson(AEAT303SubmissionResult result,
      AEAT303DeclarationData data) throws Exception {
    boolean testMode = result.isTestMode();
    boolean successful = result.isSuccessful();
    String status;
    if (!successful) {
      status = "ERROR";
    } else if (testMode) {
      status = "TEST_SUCCESS";
    } else {
      status = "SUCCESS";
    }

    JSONObject o = new JSONObject();
    o.put("status", status);
    o.put(PARAM_TEST_MODE, testMode);
    o.put("csv", StringUtils.defaultString(result.getCsv()));
    o.put("presentationDate", StringUtils.defaultString(result.getPresentationDate()));
    o.put("registryNumber", StringUtils.defaultString(result.getRegistryNumber()));
    o.put("justificanteNumber", StringUtils.defaultString(result.getJustificanteNumber()));
    byte[] pdf = result.getPdfContent();
    o.put("pdfBase64", pdf != null ? Base64.getEncoder().encodeToString(pdf) : JSONObject.NULL);
    o.put("pdfDownloadFailed", result.isPdfDownloadFailed());
    o.put("errors", new JSONArray(result.getErrors()));
    o.put("warnings", new JSONArray(result.getWarnings()));
    o.put("declarationData", declarationDataJson(data));
    return o;
  }

  /** Builds the response for a submission that failed before (or without) reaching the AEAT. */
  JSONObject buildFailureJson(boolean testMode, String errorCode, String message)
      throws Exception {
    JSONObject o = new JSONObject();
    o.put("status", "ERROR");
    o.put(PARAM_TEST_MODE, testMode);
    o.put("errorCode", errorCode);
    JSONArray errors = new JSONArray();
    errors.put(StringUtils.defaultString(message));
    o.put("errors", errors);
    o.put("warnings", new JSONArray());
    return o;
  }

  // ── Package-private helpers (tested directly) ─────────────────────────────

  /**
   * Maps a frontend AEAT letter code to the declaration type used by
   * {@code AEAT303_Utility.getCheckedInputParameter}. Accepted codes: C, D, I, U, V, X, G —
   * all 7 options the frontend's {@code TIPO_DECLARACION_FIELD} exposes, each backed by its own
   * {@code Declaration_<letter>} search key in
   * {@code 303_Report_Tax_Parameters.xml} (org.openbravo.module.aeat303.es).
   * Anything else (null, empty, unknown alias) falls back to "N" (zero result).
   */
  static String resolveDeclType(String tipo) {
    if ("C".equals(tipo) || "D".equals(tipo) || "I".equals(tipo) || "U".equals(tipo)
        || "V".equals(tipo) || "X".equals(tipo) || "G".equals(tipo)) {
      return tipo;
    }
    return "N";
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
    TaxReport taxReport = resolveTaxReport(orgId, valueKey, isLastPeriodOfYear(quarterly, period));

    AcctSchema acctSchema = resolveAcctSchema();

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

    boolean isNewForm = isOct2024OrLater(year, period);
    fillSalesBoxes(b, helper, dao303, taxReport, rateToBoxes, isNewForm);
    fillPurchaseBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillAdditionalInfoBoxes(b, helper, dao303, taxReport, rateToBoxes);

    computeSummaryBoxes(b);

    List<Map<String, Object>> sources = sourcesSupport.collectSources(org, periods, dao303, rateToBoxes);

    return new ComputeResult(b, sources);
  }

  /**
   * Returns true for form versions that introduced boxes 168-170 for 0.5%/0.26% RE
   * and reassigned boxes 16-18 to 1% RE (Oct 2024 onwards).
   */
  static boolean isOct2024OrLater(int year, String period) {
    if (year > 2024) return true;
    if (year < 2024) return false;
    if (period.startsWith("T")) {
      return Integer.parseInt(period.substring(1)) >= 4;
    }
    return Integer.parseInt(period) >= 10;
  }

  /**
   * True when {@code period} is the LAST declaration period of the fiscal year for its
   * periodicity — {@code "T4"} for quarterly, {@code "12"} for monthly. Classic's own
   * {@code AEAT303Report2021#generatePage3} (and its 2022/2023/2024/2026 subclass overrides)
   * computes this exact "annual closing" condition independently via {@code
   * AEAT303_Utility.isLastPeriod}, then reads the closing-only casillas (e.g. box 125) from
   * whichever {@code TaxReport} happens to be loaded. {@link #resolveTaxReport(String, String,
   * boolean)} uses this flag to prefer the {@code "_UltimoPeriodo"} TaxReport variant — the one
   * seeded with those extra closing casillas — so that lookup never NPEs on the last period of
   * the year (ETP-4755).
   */
  static boolean isLastPeriodOfYear(boolean quarterly, String period) {
    return quarterly ? "T4".equals(period) : "12".equals(period);
  }

  // Package-private for unit testing — injects pre-built helper and dao,
  // skips DB lookups and source collection.
  @SuppressWarnings("java:S1172")
  ComputeResult computeBoxes(Organization org, TaxReport taxReport,
      List<Period> periods, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303) {
    Map<Integer, BigDecimal> b = new HashMap<>();
    Map<String, List<Integer>> rateToBoxes = new HashMap<>();

    fillSalesBoxes(b, helper, dao303, taxReport, rateToBoxes, false);
    fillPurchaseBoxes(b, helper, dao303, taxReport, rateToBoxes);
    fillAdditionalInfoBoxes(b, helper, dao303, taxReport, rateToBoxes);
    computeSummaryBoxes(b);

    return new ComputeResult(b, Collections.emptyList());
  }

  private void fillSalesBoxes(Map<Integer, BigDecimal> b, AEAT303CalculationsHelper helper,
      AEAT303Report2014Dao dao303, TaxReport taxReport, Map<String, List<Integer>> rateToBoxes,
      boolean isNewForm) {

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

    // VAT_SALES_EC (recargo equivalencia) — split by %.
    // Box assignment depends on form version: Oct 2024+ reassigned 0.5%/0.26% to 168/170
    // and introduced 1% in boxes 16/18.
    // Pre-2024: 0%, 0.50%, 0.62% all go to 16/18; box 17 = dominant rate by largest base.
    TaxReportParameter paramEC =
        dao303.getTaxReportParameter(taxReport, VAT_SALES, "VAT_SALES_EC");
    if (paramEC != null) {
      List<TaxRate> ecTaxes =
          dao303.get303Taxes(taxReport.getId(), "All", "All", "All", paramEC);
      applyPercentageSplit(b, helper, ecTaxes, pct -> vatEcBoxes(pct, isNewForm), rateToBoxes);
      if (!isNewForm && b.containsKey(16)) {
        BigDecimal dominantRate = computePreOct2024EcDominantRate(helper, ecTaxes);
        if (dominantRate != null) b.put(17, dominantRate);
      }
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

  /**
   * Maps a VAT_SALES_EC percentage to the correct box pair.
   * From Oct 2024 (isNewForm=true), the AEAT 303 form redesigned the RE section:
   *   0.26% / 0.50% → boxes 168/170 (new row introduced Oct 2024)
   *   1.00%         → boxes 16/18  (previously held 0.50% in older forms)
   * Pre-Oct 2024 (isNewForm=false):
   *   0% / 0.50% / 0.62% → boxes 16/18 (box 17 = dominant rate, computed separately)
   */
  List<Integer> vatEcBoxes(BigDecimal pct, boolean isNewForm) {
    if (pct == null) return Collections.emptyList();
    if (pct.compareTo(PCT_1_40) == 0) return java.util.Arrays.asList(19, 21);
    if (pct.compareTo(PCT_5_20) == 0) return java.util.Arrays.asList(22, 24);
    if (pct.compareTo(PCT_1_75) == 0) return java.util.Arrays.asList(156, 158);
    if (isNewForm) {
      if (pct.compareTo(PCT_0_50) == 0 || pct.compareTo(PCT_0_26) == 0)
        return java.util.Arrays.asList(168, 170);
      if (pct.compareTo(PCT_1_00) == 0) return java.util.Arrays.asList(16, 18);
    } else {
      if (pct.compareTo(PCT_0_50) == 0 || pct.compareTo(PCT_0_62) == 0
          || pct.compareTo(BigDecimal.ZERO) == 0) return java.util.Arrays.asList(16, 18);
    }
    return Collections.emptyList();
  }

  /**
   * For pre-Oct 2024 forms: picks the dominant RE rate (0%, 0.50%, 0.62%) for box 17 display,
   * defined as the rate with the largest base imponible — matching Classic AEAT303Report2023 logic.
   * Returns null if no EC activity found.
   */
  BigDecimal computePreOct2024EcDominantRate(AEAT303CalculationsHelper helper,
      List<TaxRate> ecTaxes) {
    BigDecimal[] candidates = { BigDecimal.ZERO, PCT_0_50, PCT_0_62 };
    Map<BigDecimal, List<TaxRate>> split = splitByPercentage(ecTaxes);
    BigDecimal dominantRate = null;
    BigDecimal maxBase = BigDecimal.ZERO;
    for (BigDecimal rate : candidates) {
      BigDecimal normRate = rate.setScale(2, java.math.RoundingMode.HALF_UP);
      List<TaxRate> group = split.get(normRate);
      if (group == null || group.isEmpty()) continue;
      Map<String, BigDecimal> amounts = helper.calculateAmountsMap(group, InvoiceType.ALL);
      BigDecimal base = amounts.getOrDefault(TAX_BASE_AMOUNT, BigDecimal.ZERO).abs();
      if (base.compareTo(maxBase) > 0) {
        maxBase = base;
        dominantRate = normRate;
      }
    }
    return dominantRate;
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
    int[] accruedBoxes    = { 3, 6, 9, 11, 13, 15, 18, 21, 24, 152, 158, 167, 170 };
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

  /** Kept for callers that don't care about the last-period-of-year variant (e.g. existing
   *  tests) — always resolves the base {@code valueKey}, exactly as before ETP-4755. */
  TaxReport resolveTaxReport(String orgId, String valueKey) {
    return resolveTaxReport(orgId, valueKey, false);
  }

  /**
   * Resolves the AEAT303 {@code TaxReport} for {@code valueKey}. When {@code lastPeriod} is
   * {@code true} (the declaration being generated is the LAST period of its fiscal year for its
   * periodicity — see {@link #isLastPeriodOfYear}), first tries the {@code
   * valueKey + "_UltimoPeriodo"} variant seeded by {@code
   * org.openbravo.module.aeat303.es}'s {@code 303_Report_Tax_Parameters.xml} with the extra
   * annual-closing casillas (e.g. box 125) that Classic's own report-generation code reads
   * unconditionally once it detects the last period — regardless of which TaxReport variant was
   * loaded. Not every org/year necessarily has that variant seeded, so an empty result there
   * falls through silently to the base {@code valueKey} lookup; only when BOTH lookups come back
   * empty does this throw (ETP-4755 — fixes the NPE thrown by Classic's {@code
   * AEAT303_Utility.createINClauseForBaseOBObject} when box 125's TaxReportParameter is missing
   * from the wrong TaxReport variant).
   */
  TaxReport resolveTaxReport(String orgId, String valueKey, boolean lastPeriod) {
    if (lastPeriod) {
      TaxReport ultimoPeriodo = findTaxReport(orgId, valueKey + "_UltimoPeriodo");
      if (ultimoPeriodo != null) {
        return ultimoPeriodo;
      }
    }
    TaxReport base = findTaxReport(orgId, valueKey);
    if (base == null) {
      throw new OBException(
          "No TaxReport found for org=" + orgId + " searchKey=" + valueKey);
    }
    return base;
  }

  /** Same org-scoped (falls back to org "0") searchKey lookup {@link #resolveTaxReport} always
   *  used — extracted so it can be tried without throwing, letting callers fall through to a
   *  different searchKey on an empty result instead of failing outright. */
  private TaxReport findTaxReport(String orgId, String searchKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.in(TaxReport.PROPERTY_ORGANIZATION + ".id", Arrays.asList(orgId, "0")));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, searchKey));
    crit.addOrder(Order.desc(TaxReport.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    return list.isEmpty() ? null : list.get(0);
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

  // Package-private (not private): also called by Fiscal303SourcesSupport — see BOXES's comment
  // above and that class's header javadoc.
  BigDecimal round(BigDecimal v) {
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
