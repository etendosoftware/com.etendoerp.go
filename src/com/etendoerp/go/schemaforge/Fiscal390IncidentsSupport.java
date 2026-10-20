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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.module.aeat390.es.api.AEAT390ReportDao;
import org.openbravo.module.aeat390.es.utility.AEAT390Utility;
import org.openbravo.module.epigrafes.iae.es.model.EpigraphIAEOrgInfo;
import org.openbravo.module.organization.representative.es.model.RepresentativeInfo;

import com.etendoerp.go.schemaforge.data.FiscalDecl;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * The Modelo 390 incidents (ETP-5716), on behalf of {@link Fiscal390BoxesHandler}: loads the
 * {@link Fiscal390IncidentRules.Facts} from the declaration, the organization, the boxes, the
 * year's 303s and the invoices, and
 * <ul>
 *   <li>{@code POST /fiscal390/validate?year=&period=0A&id=} — recomputes the rule incidents of a
 *       draft from its STORED inputs and replaces them (the {@code AEAT-…} rows of the last AEAT
 *       answer are kept); answers every incident of the declaration in the
 *       {@code GET /incidents} shape. A submitted declaration's incidents are frozen: nothing is
 *       recomputed and {@code frozen: true} is answered;</li>
 *   <li>the {@code generate} and {@code submit} guards — {@link #blockingIncidents}, evaluated
 *       fresh (never from the stored rows, which can be older than the last change to the
 *       organization) from the inputs the file is built from.</li>
 * </ul>
 */
class Fiscal390IncidentsSupport {

  static final String VALIDATE = "validate";
  static final String ERR_BLOCKING_INCIDENTS = Fiscal390SubmissionSupport.ERR_BLOCKING_INCIDENTS;
  static final String IDENTIFICATION_KEY = "identification";
  /** Invoice document statuses that count as "issued" for the unposted check. */
  static final List<String> ISSUED_STATUSES = List.of("CO", "CL", "VO");
  /** How many unposted document numbers the incident names. */
  static final int UNPOSTED_SAMPLE = 5;

  private final Fiscal390BoxesHandler owner;

  Fiscal390IncidentsSupport(Fiscal390BoxesHandler owner) {
    this.owner = owner;
  }

  // ── POST /fiscal390/validate ─────────────────────────────────────────────

  void handleValidate(String orgId, int year, HttpServletRequest request,
      HttpServletResponse response) throws Exception {
    String declId = request.getParameter("id");
    if (StringUtils.isBlank(declId)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Missing required param: id");
      return;
    }
    FiscalDecl decl = OBDal.getInstance().get(FiscalDecl.class, declId);
    if (!owner.submissionSupport().isOwned390(decl, year)) {
      owner.servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Declaration not found: " + declId);
      return;
    }
    boolean frozen = isSubmitted(decl);
    if (!frozen) {
      refresh(decl, orgId, year);
      OBDal.getInstance().commitAndClose();
    }
    JSONObject out = new JSONObject();
    out.put("data", owner.declHandler().incidentsJson(declId));
    out.put("frozen", frozen);
    response.setContentType(AbstractFiscalHandler.JSON_CT);
    response.getWriter().write(out.toString());
  }

  /**
   * Recomputes every rule of a draft from its stored {@code manualData} and replaces its rule
   * incidents (does not commit). Returns what was stored.
   */
  List<Fiscal390IncidentRules.Incident> refresh(FiscalDecl decl, String orgId, int year)
      throws Exception {
    String manualData = decl.getManualData();
    List<Fiscal390IncidentRules.Incident> incidents = Fiscal390IncidentRules.evaluate(
        loadFacts(orgId, year, manualData, Fiscal390BoxesHandler.readStoredInputs(manualData),
            true));
    owner.declHandler().replaceRuleIncidentsNoCommit(decl, toRows(incidents));
    return incidents;
  }

  // ── Guards ───────────────────────────────────────────────────────────────

  /**
   * The blocking incidents of the exercise for {@code inputs} (the ones the file is built from)
   * and the identification in {@code manualData}. Evaluated fresh; nothing is stored.
   */
  List<Fiscal390IncidentRules.Incident> blockingIncidents(String orgId, int year,
      String manualData, Map<String, String> inputs) throws Exception {
    return Fiscal390IncidentRules.blocking(loadFacts(orgId, year, manualData, inputs, false));
  }

  /**
   * The {@code generate} guard: answers {@code 409 BLOCKING_INCIDENTS} (with the incidents) and
   * returns {@code true} when {@code inputs} — the stored ones of the latest declaration, as
   * overridden by the request — have a blocking incident.
   */
  boolean rejectGenerate(String orgId, int year, String period, Map<String, String> inputs,
      HttpServletResponse response) throws Exception {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    BaseOBObject latest = owner.declHandler().findLatestDeclaration(clientId,
        owner.declarationOrgId(), Fiscal390BoxesHandler.MODEL, year, period);
    String manualData = latest != null
        ? FiscalDeclCrudHandler.asString(latest.get(FiscalDeclCrudHandler.PROPERTY_MANUAL_DATA))
        : null;
    List<Fiscal390IncidentRules.Incident> blocking =
        blockingIncidents(orgId, year, manualData, inputs);
    if (blocking.isEmpty()) {
      return false;
    }
    JSONObject error = new JSONObject();
    error.put("status", HttpServletResponse.SC_CONFLICT);
    error.put("code", ERR_BLOCKING_INCIDENTS);
    error.put("message", "The declaration has blocking incidents: resolve them before "
        + "generating the file.");
    error.put("incidents", toJson(blocking));
    JSONObject body = new JSONObject();
    body.put("error", error);
    owner.servlet.writeResponse(response,
        NeoResponse.error(HttpServletResponse.SC_CONFLICT, body));
    return true;
  }

  // ── Facts ────────────────────────────────────────────────────────────────

  /**
   * Everything the rules read. {@code full} adds what only the warnings need (the 303s, the
   * unposted invoices, the inactive taxes). Each source is loaded on its own: a failure leaves
   * its facts unknown (its rules do not fire) and is logged — except the boxes, whose failure is
   * itself an incident ({@code COMPUTE_FAILED}). Package-private so tests can replace it.
   */
  Fiscal390IncidentRules.Facts loadFacts(String orgId, int year, String manualData,
      Map<String, String> inputs, boolean full) throws Exception {
    Fiscal390IncidentRules.Facts f = new Fiscal390IncidentRules.Facts();
    f.inputs = inputs != null ? inputs : Collections.emptyMap();
    JSONObject identification = parse(manualData).optJSONObject(IDENTIFICATION_KEY);
    if (identification != null) {
      Object flag = identification.opt("sustitutiva");
      f.substituteFlag = Boolean.TRUE.equals(flag) || "Y".equals(flag);
      f.formerStatement = identification.optString("formerStatement", null);
    }
    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org != null) {
      loadOrganization(f, org);
    }
    loadBoxes(f, orgId, year);
    if (full) {
      loadPreload(f, orgId, year);
      loadInvoices(f, orgId, year);
    }
    return f;
  }

  /** NIF, natural-person data, IAE activities and legal representatives — what classic reads. */
  void loadOrganization(Fiscal390IncidentRules.Facts f, Organization org) {
    AEAT390ReportDao dao = new AEAT390ReportDao();
    OBContext.setAdminMode(true);
    try {
      f.orgNif = dao.getOrgTaxID(org);
      OrganizationInformation info = dao.getOrgInfo(org);
      f.person = info != null && Boolean.TRUE.equals(info.isAeat390Isperson());
      if (info != null) {
        f.firstName = info.getAeat390Firstname();
        f.surname1 = info.getAeat390Surname1();
        f.surname2 = info.getAeat390Surname2();
        f.personRepNif = info.getAeat390RepNif();
        f.personRepName = info.getAeat390RepName();
      }
      f.taxpayerName = StringUtils.isBlank(org.getSocialName()) ? org.getName()
          : org.getSocialName();
      f.mainActivities = activities(AEAT390Utility.getEpigraphOrgInfo(org.getId(), true));
      f.otherActivities = activities(AEAT390Utility.getEpigraphOrgInfo(org.getId(), false));
      List<Fiscal390IncidentRules.Representative> reps = new ArrayList<>();
      for (RepresentativeInfo r : dao.getOrgRepresentatives(org, RepresentativeInfo.PROPERTY_LINENO,
          true)) {
        if (reps.size() < Fiscal390IncidentRules.MAX_REPRESENTATIVES) {
          reps.add(new Fiscal390IncidentRules.Representative(r.getTaxID(),
              joinNonBlank(r.getFirstName(), r.getLastname1(), r.getLastname2())));
        }
      }
      f.representatives = reps;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static List<Fiscal390IncidentRules.Activity> activities(List<EpigraphIAEOrgInfo> rows) {
    List<Fiscal390IncidentRules.Activity> out = new ArrayList<>();
    for (EpigraphIAEOrgInfo row : rows) {
      String label = row.getEpgrafeIAE() != null ? row.getEpgrafeIAE().getIdentifier()
          : String.valueOf(row.getLineNo());
      out.add(new Fiscal390IncidentRules.Activity(label, row.getEpiaeCode() != null));
    }
    return out;
  }

  /** The boxes payload's calculator warnings and result; a failure is {@code computeError}. */
  void loadBoxes(Fiscal390IncidentRules.Facts f, String orgId, int year) {
    try {
      JSONObject payload = owner.computePayload(orgId, year, f.inputs);
      List<Map<String, String>> warnings = new ArrayList<>();
      JSONArray arr = payload.optJSONArray("warnings");
      for (int i = 0; arr != null && i < arr.length(); i++) {
        JSONObject w = arr.getJSONObject(i);
        warnings.add(Map.of("code", w.optString("code", ""), "parameter",
            w.optString("parameter", ""), "rate", w.optString("rate", "")));
      }
      f.calculatorWarnings = warnings;
      f.territoryMode = Fiscal390DerivedBoxes.MODE_TERRITORY.equals(payload.optString("mode"));
      JSONObject summary = payload.optJSONObject("summary");
      if (summary != null && StringUtils.isNotBlank(summary.optString("annualResult", null))) {
        f.annualResult = new BigDecimal(summary.getString("annualResult"));
      }
    } catch (Exception e) {
      IncompleteFiscalYearException incomplete = IncompleteFiscalYearException.find(e);
      if (incomplete != null) {
        AbstractFiscalHandler.log.warn("Modelo 390 incidents: " + incomplete.getMessage());
        f.year = incomplete.getYear();
        f.incompleteFiscalYear = incomplete.getMissingPeriods();
        return;
      }
      AbstractFiscalHandler.log.warn("Modelo 390 incidents: the boxes could not be computed", e);
      f.computeError = StringUtils.defaultIfBlank(
          NeoMessageTranslator.safeParseTranslation(e.getMessage()), e.getClass().getSimpleName());
    }
  }

  /** The year's 303s: missing periods and the expected result of the cuadre. */
  void loadPreload(Fiscal390IncidentRules.Facts f, String orgId, int year) {
    try {
      Fiscal390PreloadAggregator.Result r = owner.preloadSupport().aggregateYear(orgId, year,
          "Y".equals(f.inputs.get("ISMONTHLYRETURN")));
      f.missing303Periods = new ArrayList<>(r.missingPeriods);
      f.expected303Result = r.expectedResult;
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Modelo 390 incidents: the 303s could not be read", e);
    }
  }

  /** Unposted invoices of the exercise and inactive 390 taxes with posted operations. */
  void loadInvoices(Fiscal390IncidentRules.Facts f, String orgId, int year) {
    OBContext.setAdminMode(true);
    try {
      List<Period> periods = owner.resolvePeriods(orgId, year, AbstractFiscalHandler.ANNUAL_PERIOD);
      Map<String, Date> range = AEAT390Utility.getPeriodStartEndDates(periods);
      Organization org = OBDal.getInstance().get(Organization.class, orgId);
      Set<String> orgIds = OBContext.getOBContext()
          .getOrganizationStructureProvider(org.getClient().getId())
          .getChildTree(orgId, true);
      loadUnposted(f, orgIds, range.get("startingDate"), range.get("endingDate"));
      String reportId = owner.resolveTaxReport390(orgId, year).report.getId();
      loadInactiveTaxes(f, orgIds, range.get("startingDate"), range.get("endingDate"), reportId);
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Modelo 390 incidents: the invoices could not be checked", e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  static void loadUnposted(Fiscal390IncidentRules.Facts f, Set<String> orgIds,
      Date from, Date to) {
    String hql = "select i.salesTransaction, i.documentNo from Invoice i"
        + " where i.organization.id in (:orgIds) and i.accountingDate >= :from"
        + " and i.accountingDate < :to and i.processed = true"
        + " and i.documentStatus in (:statuses) and i.posted <> 'Y'"
        + " order by i.accountingDate, i.documentNo";
    List<Object[]> rows = OBDal.getInstance().getSession().createQuery(hql, Object[].class)
        .setParameterList("orgIds", orgIds)
        .setParameter("from", from)
        .setParameter("to", to)
        .setParameterList("statuses", ISSUED_STATUSES)
        .list();
    int sales = 0;
    int purchase = 0;
    List<String> docs = new ArrayList<>();
    for (Object[] row : rows) {
      if (Boolean.TRUE.equals(row[0])) {
        sales++;
      } else {
        purchase++;
      }
      if (docs.size() < UNPOSTED_SAMPLE) {
        docs.add(String.valueOf(row[1]));
      }
    }
    f.unpostedSales = sales;
    f.unpostedPurchase = purchase;
    f.unpostedDocuments = docs;
  }

  /**
   * An inactive tax rate is silently dropped by classic ({@code get390TaxesOrderByRate} reads
   * active taxes only), so its posted invoices of the exercise are missing from the 390.
   */
  static void loadInactiveTaxes(Fiscal390IncidentRules.Facts f, Set<String> orgIds,
      Date from, Date to, String reportId) {
    String hql = "select t.name, count(distinct i.id) from InvoiceTax it"
        + " join it.tax t join it.invoice i"
        + " where t.active = false and i.posted = 'Y'"
        + " and i.organization.id in (:orgIds) and i.accountingDate >= :from"
        + " and i.accountingDate < :to"
        + " and exists (select 1 from OBTL_Tax_Parameter tp where tp.tax.id = t.id"
        + "   and tp.taxReportParameter.taxReportGroup.taxReport.id = :reportId)"
        + " group by t.name order by t.name";
    List<Object[]> rows = OBDal.getInstance().getSession().createQuery(hql, Object[].class)
        .setParameterList("orgIds", orgIds)
        .setParameter("from", from)
        .setParameter("to", to)
        .setParameter("reportId", reportId)
        .list();
    List<Fiscal390IncidentRules.InactiveTax> taxes = new ArrayList<>();
    for (Object[] row : rows) {
      taxes.add(new Fiscal390IncidentRules.InactiveTax(String.valueOf(row[0]),
          ((Number) row[1]).longValue()));
    }
    f.inactiveTaxes = taxes;
  }

  // ── Serialization ────────────────────────────────────────────────────────

  static List<FiscalDeclCrudHandler.RuleIncidentRow> toRows(
      List<Fiscal390IncidentRules.Incident> incidents) throws Exception {
    List<FiscalDeclCrudHandler.RuleIncidentRow> rows = new ArrayList<>();
    for (Fiscal390IncidentRules.Incident inc : incidents) {
      rows.add(new FiscalDeclCrudHandler.RuleIncidentRow(inc.code, inc.severity,
          FiscalDeclCrudHandler.structuredMessage(inc.text, inc.params, inc.action)));
    }
    return rows;
  }

  /** The incidents in the {@code GET /incidents} read shape. */
  static JSONArray toJson(List<Fiscal390IncidentRules.Incident> incidents) throws Exception {
    JSONArray arr = new JSONArray();
    for (Fiscal390IncidentRules.Incident inc : incidents) {
      JSONObject o = new JSONObject();
      o.put("code", inc.code);
      o.put("severity", inc.severity);
      FiscalDeclCrudHandler.putMessageFields(o,
          FiscalDeclCrudHandler.structuredMessage(inc.text, inc.params, inc.action));
      arr.put(o);
    }
    return arr;
  }

  private static boolean isSubmitted(FiscalDecl decl) {
    String status = decl.getDeclarationStatus();
    return status != null && FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(status);
  }

  private static JSONObject parse(String manualData) {
    if (StringUtils.isBlank(manualData)) {
      return new JSONObject();
    }
    try {
      return new JSONObject(manualData);
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  private static String joinNonBlank(String... parts) {
    List<String> kept = new ArrayList<>();
    for (String p : parts) {
      if (StringUtils.isNotBlank(p)) {
        kept.add(p.trim());
      }
    }
    return String.join(" ", kept);
  }
}
