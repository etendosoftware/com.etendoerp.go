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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.module.taxreportlauncher.TaxReport;

import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

abstract class AbstractFiscalHandler {

  protected static final Logger log = Logger.getLogger(AbstractFiscalHandler.class);

  protected static final String DECLARATIONS = "declarations";
  protected static final String INCIDENTS    = "incidents";
  protected static final String MODIFIED     = "modified";
  protected static final String PERIOD_KEY   = "period";
  protected static final String SINCE_KEY    = "since";
  protected static final String JSON_CT      = "application/json;charset=UTF-8";

  /**
   * Last-resort text for a 500 that has nothing better to say. {@link #userMessage} falls back to
   * it so the browser is never handed a message-less error object (ETP-5027, QA F6).
   */
  protected static final String GENERIC_ERROR_MESSAGE = "An internal error occurred.";

  protected final NeoServlet servlet;
  private   final FiscalDeclCrudHandler declHandler;

  AbstractFiscalHandler(NeoServlet servlet) {
    this.servlet     = servlet;
    this.declHandler = new FiscalDeclCrudHandler(servlet);
  }

  /**
   * Exposes the shared {@link FiscalDeclCrudHandler} delegate to subclasses that need one of its
   * declaration lookups (e.g. {@link #guardNotAlreadySubmitted}'s ETP-5438 use of {@link
   * FiscalDeclCrudHandler#findLatestDeclarationStatus}) without instantiating a second one.
   */
  protected FiscalDeclCrudHandler declHandler() {
    return declHandler;
  }

  /**
   * Thrown by {@link #guardNotAlreadySubmitted} — every {@code dispatch()} override that calls it
   * must catch this specifically (before its own generic {@code catch (Exception e)}) and turn it
   * into a clean {@code 409} instead of letting it bubble up wrapped as a generic {@code 500}. See
   * {@link Fiscal303BoxesHandler#dispatch}/{@link Fiscal349BoxesHandler#dispatch} for the exact
   * catch-and-409 pattern (identical in both). Package-private (not private) so both handlers'
   * test classes can assert on it directly — inherited nested types are reachable by simple name
   * from subclass code, and by {@code SubClass.AlreadySubmittedException} from outside it (JLS
   * 8.5), so this does not need to be duplicated per subclass.
   */
  static final class AlreadySubmittedException extends RuntimeException {
    AlreadySubmittedException(String message) {
      super(message);
    }
  }

  /**
   * ETP-5438 defense in depth, shared by every fiscal model's boxes/operators-and-generate
   * handler — rejects a compute/generate call once the LATEST declaration for this {@code
   * (org, year, period, model)} natural key is already in {@link
   * FiscalDeclCrudHandler#SUBMITTED_STATUSES}. The frontend already hides "Calcular"/"Generar
   * fichero <N>" once {@code isSubmitted} (every {@code FmModel<N>Page.jsx}), but the NEO
   * compute/generate entities are otherwise unaware of any declaration's status at all — they
   * compute purely from {@code (orgId, year, period)} against LIVE invoice data — so a direct/raw
   * call (or a future frontend regression) would silently recompute or regenerate an
   * already-presented declaration, with no server-side guard, unlike the PUT path {@link
   * FiscalDeclCrudHandler#rejectRepresentation} already covers.
   *
   * <p>Gates on the MOST RECENT declaration (highest {@code DECL_SEQ}) for the natural key, not
   * just any match — see {@link FiscalDeclCrudHandler#findLatestDeclarationStatus}'s own javadoc
   * for why: a period can legitimately have more than one declaration (the rectificativa flow),
   * and an older, already-submitted one must not block a fresh draft for the same period.
   *
   * <p>No-op (returns normally) when no declaration exists yet for the natural key — a first-time
   * compute before any declaration row was ever created is not "already submitted" by definition.
   *
   * @param model the {@code ETGO_Fiscal_Decl.model} value for the calling handler (e.g. {@code
   *              "303"}, {@code "349"}) — passed explicitly rather than derived from {@link
   *              #getModelKey()}, which returns the URL segment ({@code "fiscal303"}/{@code
   *              "fiscal349"}), not the bare model code the declaration table stores.
   */
  protected void guardNotAlreadySubmitted(String orgId, int year, String period, String model) {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    String status = declHandler().findLatestDeclarationStatus(clientId, orgId, model, year, period);
    if (status != null && FiscalDeclCrudHandler.SUBMITTED_STATUSES.contains(status)) {
      throw new AlreadySubmittedException(
          "This declaration was already submitted (status: " + status + ") for org=" + orgId
              + " year=" + year + " period=" + period + " model=" + model);
    }
  }

  void handle(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (DECLARATIONS.equals(entityName) || INCIDENTS.equals(entityName)) {
      delegateToDeclHandler(entityName, method, request, response);
      return;
    }
    if (!isKnownEntity(entityName)) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Unknown " + getModelKey() + " entity: " + entityName);
      return;
    }
    boolean methodOk = ("GET".equals(method) && allowsGet(entityName))
        || ("POST".equals(method) && allowsPost(entityName));
    if (!methodOk) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Method not allowed for /" + getModelKey() + "/" + entityName);
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
    int year;
    try {
      year = Integer.parseInt(yearStr);
    } catch (NumberFormatException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid year: " + yearStr);
      return;
    }
    try {
      String orgId = resolveEffectiveOrg();
      dispatch(entityName, orgId, year, period, request, response);
    } catch (FiscalHandlerException e) {
      log.error("Error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, userMessage(e));
    } catch (Exception e) {
      log.error("Unexpected error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          GENERIC_ERROR_MESSAGE);
    }
  }

  /**
   * Runs the {@link FiscalDeclCrudHandler} delegate for "declarations" or "incidents" — both of
   * which bypass {@link #isKnownEntity}/the year-period gate entirely — and translates any
   * failure into a 500, sharing the error-message format so it's defined once rather than once
   * per entity.
   */
  private void delegateToDeclHandler(String entityName, String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      if (DECLARATIONS.equals(entityName)) {
        declHandler.handleDeclarations(method, request, response);
      } else {
        declHandler.handleIncidents(method, request, response);
      }
    } catch (Exception e) {
      log.error("Error in /" + getModelKey() + "/" + entityName, e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, userMessage(e));
    }
  }

  /**
   * Builds the message sent to the browser for a failed fiscal request.
   *
   * <p>Two transformations, both required for the text to be readable:
   * <ol>
   *   <li><b>Unwrap.</b> {@link FiscalHandlerException} carries no message of its own — it is
   *       built as {@code super(cause)}, so its {@code getMessage()} is the cause's
   *       {@code toString()} and therefore prefixed with the cause's fully-qualified class name.
   *       Reading the cause's own message drops that prefix. Only our own wrapper is unwrapped;
   *       any other exception keeps its message as-is.</li>
   *   <li><b>Translate.</b> Etendo business logic raises errors carrying a raw AD_Message key
   *       (e.g. {@code @AEAT349_Phone_Contact_Mandatory@} thrown by {@code AEAT3492010Report}).
   *       Forwarding that verbatim shows the literal key to the user, so it goes through
   *       {@link NeoMessageTranslator#safeParseTranslation}. This covers every AEAT message
   *       raised under both /fiscal349 and /fiscal303, which share this dispatch path.</li>
   * </ol>
   *
   * <p>Falls back to the original message whenever unwrapping would yield nothing, so an
   * exception with a message-less cause never degrades into a blank error.
   *
   * <p>ETP-5027 (QA F6): and falls back once more to {@link #GENERIC_ERROR_MESSAGE} when there is
   * no message at all. {@code getMessage()} is legitimately null for e.g. a
   * {@code NullPointerException}, {@code safeParseTranslation} passes null straight through, and
   * {@code NeoResponse.error} then puts a null that jettison's jettison drops — leaving the
   * browser a message-less {@code {"error":{"status":500}}} with nothing to show the user. This
   * method is the single funnel for every fiscal error message, so the floor belongs here.
   */
  protected String userMessage(Throwable t) {
    String message = t.getMessage();
    if (t instanceof FiscalHandlerException && t.getCause() != null
        && t.getCause().getMessage() != null && !t.getCause().getMessage().isEmpty()) {
      message = t.getCause().getMessage();
    }
    String translated = NeoMessageTranslator.safeParseTranslation(message);
    return StringUtils.isBlank(translated) ? GENERIC_ERROR_MESSAGE : translated;
  }

  protected abstract boolean isKnownEntity(String entityName);

  @SuppressWarnings("java:S1172")
  protected boolean allowsPost(String entityName) { return false; }

  /**
   * Whether {@code entityName} may be reached with GET. Defaults to {@code true} because every
   * fiscal entity is a read. Mutating entities MUST override this to return {@code false} for
   * themselves — {@code /fiscal349/validate-vies}, which writes VIES statuses, and
   * {@code /fiscal303/submit}, which files a declaration with the AEAT.
   *
   * <p><b>This is not a drive-by or CSRF defence, and must not be described as one.</b> NEO
   * authenticates with a Bearer token in the {@code Authorization} header (every call in
   * {@code fiscalModelsUtils.js} sets it), so a link, an address bar, an {@code <img>} prefetch
   * or a cross-site form carries no credential and gets a 401 whatever the method. The real
   * reasons are narrower, and all three are enough on their own:
   * <ul>
   *   <li>A GET with side effects lands in browser history, proxy caches and access logs.</li>
   *   <li>Many HTTP clients and proxies <b>auto-retry GETs</b> but not POSTs, so a retry can
   *       re-fire the side effect. Guards like {@code ALREADY_SUBMITTED} limit the consequence;
   *       they do not remove it.</li>
   *   <li>Plain HTTP semantics: GET must be safe and idempotent. Filing a declaration, or
   *       writing back VIES statuses, is neither.</li>
   * </ul>
   */
  @SuppressWarnings("java:S1172")
  protected boolean allowsGet(String entityName) { return true; }

  protected abstract void dispatch(String entityName, String orgId, int year, String period,
      HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException;

  protected abstract String getModelKey();

  /**
   * Replaces the persisted AEAT validation rows ({@code ETGO_Fiscal_Decl_Incident}) for a
   * declaration: deletes every existing row for it, then inserts one row per entry in
   * {@code errors} (severity {@code block}) followed by one row per entry in {@code warnings}
   * (severity {@code warn}), both parsed as {@code "CODE - message"}. Called by
   * {@link Fiscal303BoxesHandler} on EVERY submission attempt (test and production alike) — empty
   * {@code errors} and {@code warnings} lists simply leave the declaration with no incident rows.
   * See {@link FiscalDeclCrudHandler#replaceIncidents} for the persistence details (shared with
   * the read path, {@code GET /fiscal303/incidents}).
   */
  protected void replaceIncidents(BaseOBObject decl, List<String> errors, List<String> warnings) {
    declHandler.replaceIncidents(decl, errors, warnings);
  }

  /**
   * Same as {@link #replaceIncidents} but does not commit — used by
   * {@link Fiscal303SubmissionSupport#handleSubmit} so the incidents write shares a single
   * transaction with the declaration status/attachment write that follows it (ETP-4456
   * atomicity fix). See {@link FiscalDeclCrudHandler#replaceIncidentsNoCommit}.
   */
  protected void replaceIncidentsNoCommit(BaseOBObject decl, List<String> errors,
      List<String> warnings) {
    declHandler.replaceIncidentsNoCommit(decl, errors, warnings);
  }

  // ── shared helpers ────────────────────────────────────────────────

  protected void handleModified(String orgId, int year, String period, Date since,
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

  protected void writeGeneratedFile(HashMap<String, Object> result, String filenameWithExt,
      HttpServletResponse response) throws Exception {
    Object fileContent = result.get("file");
    if (fileContent == null) {
      throw new OBException("generateElectronicFile returned no file content");
    }
    byte[] bytes = fileContent.toString().getBytes(StandardCharsets.ISO_8859_1);
    response.setContentType("text/plain");
    response.setCharacterEncoding("ISO-8859-1");
    response.setHeader("Content-Disposition",
        "attachment; filename=\"" + filenameWithExt + "\"");
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
    response.flushBuffer();
  }

  protected String resolveEffectiveOrg() {
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    if (!"0".equals(orgId)) {
      return orgId;
    }
    // Session org is * — find the non-summary leaf org for the current client.
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OBCriteria<Organization> crit = OBDal.getInstance().createCriteria(Organization.class);
    crit.add(Restrictions.eq(Organization.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(Organization.PROPERTY_SUMMARYLEVEL, false));
    crit.add(Restrictions.ne(Organization.PROPERTY_ID, "0"));
    crit.addOrder(Order.asc(Organization.PROPERTY_NAME));
    crit.setMaxResults(1);
    List<Organization> orgs = crit.list();
    if (orgs.isEmpty()) {
      throw new OBException("No leaf organization found for client=" + clientId);
    }
    return orgs.get(0).getId();
  }

  protected AcctSchema resolveAcctSchema() {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    List<AcctSchema> list = crit.list();
    if (list.isEmpty()) {
      throw new OBException("No AcctSchema found for client=" + clientId);
    }
    return list.get(0);
  }

  /**
   * Org-scoped (falls back to org {@code "0"}) {@code TaxReport} searchKey lookup, shared by
   * every fiscal model's {@code TaxReport} resolution — hoisted here (SonarQube java:S1192/
   * duplicated-block) from {@link Fiscal303BoxesHandler#resolveTaxReport} and {@code
   * Fiscal349BoxesHandler#resolveTaxReport349}, which previously each carried their own
   * byte-identical private copy. Returns {@code null} (never throws) so callers can fall through
   * to a different searchKey on an empty result instead of failing outright.
   */
  protected TaxReport findTaxReport(String orgId, String searchKey) {
    OBCriteria<TaxReport> crit = OBDal.getInstance().createCriteria(TaxReport.class);
    crit.add(Restrictions.in(TaxReport.PROPERTY_ORGANIZATION + ".id", Arrays.asList(orgId, "0")));
    crit.add(Restrictions.eq(TaxReport.PROPERTY_SEARCHKEY, searchKey));
    crit.addOrder(Order.desc(TaxReport.PROPERTY_ORGANIZATION + ".id"));
    crit.setMaxResults(1);
    List<TaxReport> list = crit.list();
    return list.isEmpty() ? null : list.get(0);
  }

  @SuppressWarnings("unchecked")
  protected List<Period> resolvePeriods(String orgId, int year, String periodCode) {
    int monthFrom;
    int monthTo;
    if (periodCode.startsWith("T")) {
      int q = Integer.parseInt(periodCode.substring(1));
      monthFrom = (q - 1) * 3 + 1;
      monthTo   = q * 3;
    } else {
      monthFrom = Integer.parseInt(periodCode);
      monthTo   = monthFrom;
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
