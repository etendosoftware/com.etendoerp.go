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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.module.aeat390.es.api.AEATIVA_ObjectFactory;
import org.openbravo.module.aeat390.es.api.TipoBaseImponibleYCuota;
import org.openbravo.module.aeat390.es.api.cashvat.AEAT390CashVATReadyCalculationsHelper;
import org.openbravo.module.aeat390.es.api.cashvat.AEAT390CashVATReadyDao;
import org.openbravo.module.aeat390.es.csv.AEAT390CSV;
import org.openbravo.module.taxreportlauncher.TaxReport;
import org.openbravo.module.taxreportlauncher.TaxReportGroup;
import org.openbravo.module.taxreportlauncher.TaxReportParameter;

/**
 * NEO built-in endpoints of Modelo 390, the annual VAT summary (ETP-5716): {@code /fiscal390/*}.
 * Same shape as {@link Fiscal303BoxesHandler}: declaration CRUD and incidents stay on the shared
 * {@link FiscalDeclCrudHandler} path (handled by the base class), the model-specific entities are
 * <ul>
 *   <li>{@code GET boxes?year=&period=0A[&<AEAT390 input>=...]} — the box preview
 *       ({@link Fiscal390SnapshotSupport#buildResponse}); a submitted declaration is served from
 *       its frozen snapshot;</li>
 *   <li>{@code GET generate?year=&period=0A[&<AEAT390 input>=...]} — the AEAT TXT file, through
 *       the classic report class ({@link Fiscal390GenerateSupport}); rejected with 409 once the
 *       declaration is submitted;</li>
 *   <li>{@code GET sources?year=&period=0A[&box=&type=&tax=&q=&page=&pageSize=]} — the
 *       invoice-tax lines behind the invoice-derived boxes, from the same box walk
 *       ({@link Fiscal390SourcesSupport});</li>
 *   <li>{@code GET preload?year=&period=0A[&monthlyReturn=Y|N]} — the year's Modelo 303
 *       declarations and the 390 inputs they preload, plus the expected result for the cuadre
 *       ({@link Fiscal390PreloadSupport});</li>
 *   <li>{@code POST submit?year=&period=0A&id=} — the AEAT telematic filing
 *       ({@link Fiscal390SubmissionSupport}); POST-only, like {@code /fiscal303/submit};</li>
 *   <li>{@code POST validate?year=&period=0A&id=} — recomputes the rule incidents of a draft
 *       ({@link Fiscal390IncidentsSupport}); {@code generate} and {@code submit} refuse while a
 *       blocking rule holds;</li>
 *   <li>{@code GET modified?year=&period=0A&since=} — the shared invoice-change probe.</li>
 * </ul>
 *
 * <p><b>Period:</b> Modelo 390 is annual — the only accepted period code is
 * {@value AbstractFiscalHandler#ANNUAL_PERIOD}, which {@link #resolvePeriods} maps to the 12
 * months of the fiscal year (failing when one is missing).
 *
 * <p><b>Tax report:</b> {@code AEAT390_<year>_Y}, or the latest earlier year's report when that
 * exercise has none ({@link #resolveTaxReport390}). The resolved key travels in the payload's
 * {@code taxReport}, so it is frozen with the submission snapshot.
 *
 * <p><b>User inputs</b> (compensations, territory percentages, monthly results, ...) are the
 * AEAT390 {@code TaxReportParameter} search keys of the classic launcher. They are read from the
 * latest declaration's {@code manualData.aeatInputs} and, for a preview or a generation, can be
 * overridden by request query parameters of the same name.
 */
class Fiscal390BoxesHandler extends AbstractFiscalHandler {

  /** {@code ETGO_Fiscal_Decl.model} of this handler. */
  static final String MODEL = "390";
  static final String BOXES = "boxes";
  static final String GENERATE = "generate";
  /** Key, inside the declaration's {@code manualData}, of the stored AEAT390 inputs. */
  static final String STORED_INPUTS_KEY = "aeatInputs";
  /** {@code manualData} key of the values the 303 preload last put in (frontend). */
  static final String STORED_PRELOADED_KEY = "aeatPreloaded";
  /** SQL LIKE pre-filter of the yearly 390 reports; {@link #REPORT_KEY_PATTERN} is the real test. */
  static final String REPORT_KEY_LIKE = "AEAT390%_Y";
  /** Yearly 390 report search key; group 1 is the layout year. */
  static final Pattern REPORT_KEY_PATTERN = Pattern.compile("^AEAT390_(\\d{4})_Y$");

  private final Fiscal390GenerateSupport generateSupport;
  private final Fiscal390SubmissionSupport submissionSupport;
  private final Fiscal390SourcesSupport sourcesSupport;
  private final Fiscal390PreloadSupport preloadSupport;
  private final Fiscal390IncidentsSupport incidentsSupport;

  Fiscal390BoxesHandler(NeoServlet servlet) {
    super(servlet);
    this.snapshotSupport = new Fiscal390SnapshotSupport();
    this.generateSupport = new Fiscal390GenerateSupport(this);
    this.submissionSupport = new Fiscal390SubmissionSupport(this);
    this.sourcesSupport = new Fiscal390SourcesSupport(this);
    this.preloadSupport = new Fiscal390PreloadSupport(this);
    this.incidentsSupport = new Fiscal390IncidentsSupport(this);
  }

  @Override
  protected boolean isKnownEntity(String entityName) {
    return BOXES.equals(entityName) || GENERATE.equals(entityName) || MODIFIED.equals(entityName)
        || Fiscal390SubmissionSupport.SUBMIT.equals(entityName)
        || Fiscal390SourcesSupport.SOURCES.equals(entityName)
        || Fiscal390PreloadSupport.PRELOAD.equals(entityName)
        || Fiscal390IncidentsSupport.VALIDATE.equals(entityName);
  }

  /**
   * {@code submit} files the declaration with the AEAT and {@code validate} rewrites its rule
   * incidents: POST-only (see the 303 handler).
   */
  @Override
  protected boolean allowsPost(String entityName) {
    return isWrite(entityName);
  }

  @Override
  protected boolean allowsGet(String entityName) {
    return !isWrite(entityName);
  }

  private static boolean isWrite(String entityName) {
    return Fiscal390SubmissionSupport.SUBMIT.equals(entityName)
        || Fiscal390IncidentsSupport.VALIDATE.equals(entityName);
  }

  /** The annual summary is filed once per exercise: a later filing is a substitute (ETP-5716). */
  @Override
  protected boolean allowsSingleOrdinary() {
    return true;
  }

  @Override
  protected String getModelKey() {
    return "fiscal390";
  }

  /** The file generation, shared by {@code generate} and the telematic submission. */
  Fiscal390GenerateSupport generateSupport() {
    return generateSupport;
  }

  /** The telematic filing (and its ownership check, shared with {@code validate}). */
  Fiscal390SubmissionSupport submissionSupport() {
    return submissionSupport;
  }

  /** The year's 303s, read by the preload and by the incident rules. */
  Fiscal390PreloadSupport preloadSupport() {
    return preloadSupport;
  }

  /** The incident rules: {@code validate} and the generate / submit guards. */
  Fiscal390IncidentsSupport incidentsSupport() {
    return incidentsSupport;
  }

  /**
   * Per-request memo of the expensive reads (review W2): the year's 303 aggregation and the boxes
   * payloads, so one request (e.g. {@code generate} / {@code submit} / {@code validate}: inputs,
   * guard incidents, file, snapshot) aggregates the 303s and computes each payload once. Set for
   * the duration of {@link #dispatch} on its thread; absent elsewhere (no caching).
   */
  private final ThreadLocal<Map<String, Object>> requestMemo = new ThreadLocal<>();

  @Override
  protected void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
    withRequestMemo(() -> {
      dispatchEntity(entityName, orgId, year, period, request, response);
      return null;
    });
  }

  /** A body that may throw {@link FiscalHandlerException}, run under {@link #withRequestMemo}. */
  @FunctionalInterface
  interface MemoBody<T> {
    /**
     * Runs the body.
     *
     * @return the body's result
     * @throws FiscalHandlerException whatever the body raises
     */
    T run() throws FiscalHandlerException;
  }

  /** Runs {@code body} with a fresh {@link #requestMemo}, removed afterwards (tests use it too). */
  <T> T withRequestMemo(MemoBody<T> body) throws FiscalHandlerException {
    requestMemo.set(new HashMap<>());
    try {
      return body.run();
    } finally {
      requestMemo.remove();
    }
  }

  private void dispatchEntity(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
    runDispatch(response, () -> {
      if (!ANNUAL_PERIOD.equals(period)) {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Modelo 390 is an annual declaration: period must be " + ANNUAL_PERIOD);
        return;
      }
      try {
        routeEntity(entityName, orgId, year, period, request, response);
      } catch (IllegalArgumentException e) {
        // A non-numeric user input (Fiscal390DerivedBoxes.parseInputs) — the caller can fix it.
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      }
    });
  }

  /** Serves one entity of the (annual) exercise; see the class comment for the list. */
  private void routeEntity(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws Exception {
    if (BOXES.equals(entityName)) {
      writeBoxes(orgId, year, period, request, response);
    } else if (GENERATE.equals(entityName)) {
      generateFile(orgId, year, period, request, response);
    } else if (Fiscal390SourcesSupport.SOURCES.equals(entityName)) {
      sourcesSupport.handle(orgId, year, period, request, response);
    } else if (Fiscal390PreloadSupport.PRELOAD.equals(entityName)) {
      preloadSupport.handle(orgId, year, period, request, response);
    } else if (Fiscal390SubmissionSupport.SUBMIT.equals(entityName)) {
      submissionSupport.handleSubmit(orgId, year, period, request, response);
    } else if (Fiscal390IncidentsSupport.VALIDATE.equals(entityName)) {
      incidentsSupport().handleValidate(orgId, year, request, response);
    } else {
      long sinceMs = Long.parseLong(request.getParameter(SINCE_KEY));
      handleModified(orgId, year, period, new Date(sinceMs), response);
    }
  }

  /** {@code boxes}: not guarded, a pure read. A submitted declaration is served from its snapshot. */
  private void writeBoxes(String orgId, int year, String period, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    JSONObject snapshot = findSubmittedSnapshot(year, period);
    JSONObject out = snapshot != null ? snapshot
        : computePayload(orgId, year, effectiveInputs(orgId, year, period, request));
    response.setContentType(JSON_CT);
    response.getWriter().write(out.toString());
  }

  /** {@code generate}: refused once submitted, or while a blocking incident is open. */
  private void generateFile(String orgId, int year, String period, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    guardNotAlreadySubmitted(orgId, year, period, MODEL);
    Map<String, String> inputs = effectiveInputs(orgId, year, period, request);
    if (incidentsSupport().rejectGenerate(orgId, year, period, inputs, response)) {
      return;
    }
    generateSupport().handleGenerate(orgId, year, inputs, response);
  }

  /**
   * The Modelo 390 {@code TaxReport} for exercise {@code year}: {@code AEAT390_<year>_Y}, or the
   * latest {@code AEAT390_<YYYY>_Y} with {@code YYYY <= year} — see
   * {@link AbstractFiscalHandler#findLatestTaxReport}.
   *
   * @throws OBException when no yearly 390 report up to {@code year} is configured
   */
  ResolvedTaxReport resolveTaxReport390(String orgId, int year) {
    ResolvedTaxReport resolved =
        findLatestTaxReport(orgId, REPORT_KEY_LIKE, REPORT_KEY_PATTERN, year);
    if (resolved == null) {
      throw new OBException("No Modelo 390 TaxReport (AEAT390_<year>_Y) up to year " + year
          + " is configured for org=" + orgId);
    }
    return resolved;
  }

  /**
   * Computes the {@code boxes} payload from the CURRENT invoice data and {@code rawInputs}.
   */
  JSONObject computePayload(String orgId, int year, Map<String, String> rawInputs)
      throws Exception {
    Map<String, Object> memo = requestMemo.get();
    if (memo == null) {
      return computePayloadNow(orgId, year, rawInputs);
    }
    String key = "boxes|" + orgId + "|" + year + "|" + new TreeMap<>(rawInputs != null ? rawInputs
        : Map.of());
    JSONObject cached = (JSONObject) memo.get(key);
    if (cached == null) {
      cached = computePayloadNow(orgId, year, rawInputs);
      memo.put(key, cached);
    }
    return new JSONObject(cached.toString()); // callers may change their copy
  }

  private JSONObject computePayloadNow(String orgId, int year, Map<String, String> rawInputs)
      throws Exception {
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org == null) {
      throw new OBException("Organization not found: " + orgId);
    }
    ResolvedTaxReport taxReport = resolveTaxReport390(orgId, year);
    AcctSchema acctSchema = resolveAcctSchema();
    List<Period> periods = resolvePeriods(orgId, year, ANNUAL_PERIOD);
    Map<String, BigDecimal> inputs = Fiscal390DerivedBoxes.parseInputs(rawInputs);
    Fiscal390BoxesCalculator.Result result = Fiscal390BoxesCalculator.compute(
        classicEngine(org, taxReport.report, acctSchema, periods), inputs);
    return Fiscal390SnapshotSupport.buildResponse(result, taxReport, inputs);
  }

  /**
   * The {@link Fiscal390BoxesCalculator.Engine} backed by the classic 390 DAO and calculation
   * helper — the exact invoice queries classic runs (posted invoices of the periods, cash-VAT
   * merge when the parameter asks for it). The helper's CSV object is only kept in memory: its
   * file is never written. Package-private so tests can replace it.
   */
  Fiscal390BoxesCalculator.Engine classicEngine(Organization org, TaxReport taxReport,
      AcctSchema acctSchema, List<Period> periods) {
    AEAT390CashVATReadyDao dao = new AEAT390CashVATReadyDao();
    AEAT390CashVATReadyCalculationsHelper helper = new AEAT390CashVATReadyCalculationsHelper(dao,
        new AEAT390CSV(System.getProperty("java.io.tmpdir"), "etgo-390-preview.csv"));
    AEATIVA_ObjectFactory factory = new org.openbravo.module.aeat390.es.v2014.bean.ObjectFactory();
    return new Fiscal390BoxesCalculator.Engine() {
      @Override
      public List<TaxReportParameter> outputParameters(String groupKey) {
        TaxReportGroup group = dao.getTaxReportGroup(taxReport, groupKey);
        return group == null ? Collections.emptyList()
            : dao.getTaxReportOutputParametersOrderBySeqNO(group);
      }

      @Override
      public List<TaxRate> taxes(TaxReportParameter parameter) {
        return dao.get390TaxesOrderByRate(parameter);
      }

      @Override
      public BigDecimal[] amounts(TaxReportParameter parameter, List<TaxRate> taxes) {
        TipoBaseImponibleYCuota r =
            helper.calculateAmounts(org, acctSchema, parameter, taxes, periods, factory);
        return new BigDecimal[] { nonNull(r.getBI()), nonNull(r.getCuota()) };
      }
    };
  }

  private static BigDecimal nonNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  /**
   * The inputs stored on the latest declaration for the natural key
   * ({@code manualData.aeatInputs}), as strings; empty when there is none.
   */
  Map<String, String> storedInputs(int year, String period) {
    return readStoredInputs(storedManualData(year, period));
  }

  /** {@code manualData} of the latest declaration for the natural key; {@code null} if none. */
  String storedManualData(int year, String period) {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    BaseOBObject latest = declHandler().findLatestDeclaration(clientId, declarationOrgId(), MODEL,
        year, period);
    return latest == null ? null
        : FiscalDeclCrudHandler.asString(latest.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA));
  }

  /** Parses {@code manualData.aeatPreloaded} (what the 303 preload last put in); empty if none. */
  static Map<String, String> readStoredPreloaded(String manualData) {
    Map<String, String> out = new LinkedHashMap<>();
    if (StringUtils.isBlank(manualData)) {
      return out;
    }
    try {
      JSONObject preloaded = new JSONObject(manualData).optJSONObject(STORED_PRELOADED_KEY);
      if (preloaded == null) {
        return out;
      }
      Iterator<?> keys = preloaded.keys();
      while (keys.hasNext()) {
        String key = String.valueOf(keys.next());
        if (!preloaded.isNull(key)) {
          out.put(key, preloaded.getString(key));
        }
      }
    } catch (Exception e) {
      log.warn("Ignoring unparseable Modelo 390 aeatPreloaded: " + e.getMessage());
    }
    return out;
  }

  /**
   * {@code inputs} with the year's 303 preload applied server-side to every preloadable input the
   * user has not overridden ({@link Fiscal390PreloadAggregator#applyPreload}, with
   * {@code manualData.aeatPreloaded} as "what was preloaded before"). Used for the boxes, the
   * file, the submission and the incidents, so none of them depends on the client having saved
   * the preload (AEAT PAR016, ETP-5716). The year without 303s, or a preload that cannot be read,
   * leaves {@code inputs} as they are (logged); the blocking {@code BLOCK9_*} incidents remain the
   * safety net.
   */
  Map<String, String> withServerPreload(String orgId, int year, Map<String, String> inputs,
      String manualData) {
    try {
      Fiscal390PreloadAggregator.Result r = aggregatePreload(orgId, year,
          "Y".equals(inputs != null ? inputs.get("ISMONTHLYRETURN") : null));
      if (r == null || r.declarations.isEmpty()) {
        return inputs;
      }
      return Fiscal390PreloadAggregator.applyPreload(inputs, readStoredPreloaded(manualData),
          r.inputs);
    } catch (Exception e) {
      log.warn("Modelo 390: the 303 preload could not be applied server-side ("
          + e.getMessage() + "); using the inputs as they are");
      return inputs;
    }
  }

  /** Parses {@code manualData.aeatInputs}; a malformed blob yields no inputs, never an error. */
  static Map<String, String> readStoredInputs(String manualData) {
    Map<String, String> out = new LinkedHashMap<>();
    if (StringUtils.isBlank(manualData)) {
      return out;
    }
    try {
      JSONObject inputs = new JSONObject(manualData).optJSONObject(STORED_INPUTS_KEY);
      if (inputs == null) {
        return out;
      }
      Iterator<?> keys = inputs.keys();
      while (keys.hasNext()) {
        String key = String.valueOf(keys.next());
        if (!inputs.isNull(key)) {
          out.put(key, inputs.getString(key));
        }
      }
    } catch (Exception e) {
      log.warn("Ignoring unparseable Modelo 390 manualData: " + e.getMessage());
    }
    return out;
  }

  /**
   * Stored inputs overridden by the request's non-structural query parameters (the request is
   * the user's current, possibly unsaved, edit).
   */
  Map<String, String> mergedInputs(int year, String period, HttpServletRequest request) {
    Map<String, String> merged = storedInputs(year, period);
    merged.putAll(Fiscal390GenerateSupport.requestInputs(request));
    return merged;
  }

  /**
   * The year's 303 aggregation ({@link Fiscal390PreloadSupport#aggregateYear}), computed once per
   * request (see {@link #requestMemo}) for the inputs, the incidents and the guards alike.
   */
  Fiscal390PreloadAggregator.Result aggregatePreload(String orgId, int year,
      boolean monthlyReturn) {
    Map<String, Object> memo = requestMemo.get();
    String key = "preload|" + orgId + "|" + year + "|" + monthlyReturn;
    if (memo != null && memo.containsKey(key)) {
      return (Fiscal390PreloadAggregator.Result) memo.get(key);
    }
    Fiscal390PreloadAggregator.Result r = preloadSupport().aggregateYear(orgId, year,
        monthlyReturn);
    if (memo != null) {
      memo.put(key, r);
    }
    return r;
  }

  /** {@link #mergedInputs} with the server-side 303 preload ({@link #withServerPreload}). */
  Map<String, String> effectiveInputs(String orgId, int year, String period,
      HttpServletRequest request) {
    return withServerPreload(orgId, year, mergedInputs(year, period, request),
        storedManualDataOrNull(year, period));
  }

  /** The stored inputs with the server-side 303 preload ({@link #withServerPreload}). */
  Map<String, String> effectiveStoredInputs(String orgId, int year, String period) {
    return withServerPreload(orgId, year, storedInputs(year, period),
        storedManualDataOrNull(year, period));
  }

  /** {@link #storedManualData}, or {@code null} when it cannot be read (logged). */
  private String storedManualDataOrNull(int year, String period) {
    try {
      return storedManualData(year, period);
    } catch (Exception e) {
      log.warn("Modelo 390: the stored manualData could not be read: " + e.getMessage());
      return null;
    }
  }
}
