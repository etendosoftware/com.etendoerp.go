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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_actionButton.CreateRegFactAcct;
import org.openbravo.erpCommon.ad_actionButton.DropRegFactAcct;
import org.openbravo.erpCommon.utility.AccDefUtility;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * NeoHandler for the {@code year} entity's Close Year / Undo Close Year actions (C_Year records).
 *
 * <p>Intercepts the {@code closeYear} and {@code undoCloseYear} ACTION endpoints (the
 * {@code menuActions} keys declared in the {@code calendar} spec's {@code decisions.json} — these
 * are entity-level actions, decoupled from the underlying button field names {@code
 * createRegFactAcct}/{@code dropRegFactAcct}; NEO routes any {@code /action/<name>} segment to
 * this handler regardless of whether a field with that exact name exists).
 *
 * <h2>Why this does NOT use {@link org.openbravo.service.db.CallProcess}</h2>
 *
 * <p>Unlike {@link PeriodOpenCloseHandler} (process 167, a DB stored procedure) and
 * {@link PeriodControlDocOpenCloseHandler} (process 168, also a stored procedure), AD Processes
 * 800036 ("Close Year") and 800038 ("Undo Close Year") are legacy {@code ad_actionButton}
 * classname servlets ({@code CreateRegFactAcct}/{@code DropRegFactAcct} in Etendo core), not
 * stored procedures — confirmed via a Task 7 spike (see the design doc's risk log): both have
 * {@code AD_Process.procedurename = NULL}, and {@code CallProcess.callProcess()} unconditionally
 * builds {@code "SELECT * FROM " + process.getProcedure() + "(?)"} with no branch for
 * classname-based processes, so calling {@code CallProcess} against these two would fail.
 *
 * <p>Going through the servlets' own {@code doPost()} (simulating a real HTTP request) was
 * considered and rejected: it requires constructing a fake {@link
 * javax.servlet.http.HttpServletRequest}, matching legacy parameter names ({@code inpcYearId},
 * {@code inpwindowId}, {@code inpadOrgId}, {@code inpTabId}), and triggers HTML popup-closing
 * response rendering — strictly more surface area and more ways to get it wrong than calling the
 * business logic directly.
 *
 * <p>Instead, this handler invokes the servlets' private {@code processButton(...)} method
 * directly via reflection — the actual business logic entry point, bypassing the HTTP-request
 * parsing layer entirely. Two supporting pieces make this tractable without a servlet container:
 * <ul>
 *   <li>{@link VariablesSecureApp} has an officially-documented "manual instance" constructor
 *       (javadoc: "Constructor used to make an empty/manual instance of this class") — the same
 *       mechanism {@code org.openbravo.scheduling.ProcessContext#toVars()} uses for background/
 *       scheduled process execution outside an HTTP request.</li>
 *   <li>{@link DalConnectionProvider} is a {@code ConnectionProvider} built on the current DAL
 *       connection, designed precisely for callers outside a servlet context — it is set directly
 *       on the protected {@code myPool} field (reflection), so the servlet's {@code init()} (which
 *       needs a real {@code ServletConfig}/{@code ServletContext}) is never called.</li>
 * </ul>
 *
 * <p><b>Known fragility:</b> reflection into a private method is inherently brittle across Etendo
 * core versions — if {@code CreateRegFactAcct#processButton}/{@code DropRegFactAcct#processButton}
 * change signature, this handler breaks at runtime, not compile time. This is an accepted
 * trade-off given no less-fragile officially-supported entry point exists for these two legacy
 * processes.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'year-close'} on the {@code year} ETGO_SF_ENTITY
 * record for the {@code calendar} spec.
 */
@Named("year-close")
public class YearCloseHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(YearCloseHandler.class);

  static final String ACTION_CLOSE_YEAR = "closeYear";
  static final String ACTION_UNDO_CLOSE_YEAR = "undoCloseYear";

  private static final String SPEC_FISCAL_CALENDAR = "fiscal-calendar";
  private static final String ENTITY_YEAR = "year";
  private static final String METHOD_POST = "POST";
  private static final String FIELD_FISCAL_YEAR = "fiscalYear";
  private static final String FIELD_CALENDAR = "calendar";
  private static final int MIN_FISCAL_YEAR = 1900;
  private static final int MAX_FISCAL_YEAR = 2999;

  private static final String STATUS_CLOSED = "C";
  private static final String STATUS_PERMANENTLY_CLOSED = "P";

  private final FiscalYearPeriodsHandler fiscalYearPeriodsHandler = new FiscalYearPeriodsHandler();

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse createResponse = validateAndEnrichFiscalCalendarCreate(context);
    if (createResponse != null || isFiscalCalendarCreate(context)) {
      return createResponse;
    }
    if (fiscalYearPeriodsHandler.handles(context)) {
      return fiscalYearPeriodsHandler.handle(context);
    }
    if (context.getEndpointType() != NeoEndpointType.ACTION) {
      return null;
    }
    String action = context.getFieldName();
    if (!ACTION_CLOSE_YEAR.equals(action) && !ACTION_UNDO_CLOSE_YEAR.equals(action)) {
      return null;
    }

    String yearId = context.getRecordId();
    if (yearId == null || yearId.isBlank()) {
      return NeoResponse.error(400, "Missing recordId");
    }

    try {
      OBContext.setAdminMode();
      try {
        Year year = OBDal.getInstance().get(Year.class, yearId);
        if (year == null) {
          return NeoResponse.error(404, "Year not found: " + yearId);
        }

        if (!allPeriodsClosed(year)) {
          return NeoResponse.error(409,
              "All periods must be Closed or Permanently Closed before running " + action);
        }

        OBError result = ACTION_CLOSE_YEAR.equals(action)
            ? invokeCreateRegFactAcct(year)
            : invokeDropRegFactAcct(year);

        return translateResult(result, action);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error executing {} for year {}", action, yearId, e);
      return NeoResponse.error(500, action + " failed: " + e.getMessage());
    }
  }

  /**
   * Fiscal Calendar exposes C_Year directly although it is a child AD tab of C_Calendar. The
   * generic create route consequently has no parentId from which the mandatory-default service
   * can resolve C_Calendar_ID. Derive it from the current organization instead of falling back to
   * the first readable calendar, which can be the global organization calendar.
   *
   * <p><b>ETP-4948 REVIEW fix:</b> {@link Organization#getCalendar()} only reads the org's own
   * directly-assigned {@code C_Calendar_ID} — it does NOT walk the org tree, so an org that
   * inherits its calendar from a parent (a completely standard setup) resolved to {@code null}
   * here, and the caller had no fallback other than erroring or (before this fix) silently
   * accepting whatever the org-blind mandatory-defaults selector picked first — which can be the
   * global (org {@code *}) calendar for a client with more than one. {@link
   * AccDefUtility#getCalendar(Organization)} is the already-precedented pattern in this exact
   * codebase (used by classic invoice/period logic) for "the calendar this organization should
   * use, walking up the org tree" — it deliberately treats org {@code *} (id {@code "0"}) as "no
   * usable calendar" rather than returning the global one, which is exactly the behavior wanted
   * here.
   */
  private NeoResponse validateAndEnrichFiscalCalendarCreate(NeoContext context) {
    if (!isFiscalCalendarCreate(context)) {
      return null;
    }
    org.codehaus.jettison.json.JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(400, "Missing request body");
    }
    String fiscalYear = body.optString(FIELD_FISCAL_YEAR, "").trim();
    if (!isValidFiscalYear(fiscalYear)) {
      return NeoResponse.error(400, "Fiscal Year must be a four-digit year between 1900 and 2999");
    }
    Organization organization = OBContext.getOBContext().getCurrentOrganization();
    Calendar calendar = organization != null ? AccDefUtility.getCalendar(organization) : null;
    if (calendar == null || calendar.getId() == null) {
      return NeoResponse.error(400, "The current organization has no fiscal calendar");
    }
    try {
      // C_Calendar_ID is a system field, so never honor a caller-provided calendar from this route.
      body.put(FIELD_CALENDAR, calendar.getId());
      return null;
    } catch (org.codehaus.jettison.json.JSONException e) {
      log.error("Could not set fiscal calendar for organization {}", organization.getId(), e);
      return NeoResponse.error(500, "Could not set the organization fiscal calendar");
    }
  }

  private boolean isFiscalCalendarCreate(NeoContext context) {
    return context != null
        && NeoEndpointType.CRUD.equals(context.getEndpointType())
        && METHOD_POST.equals(context.getHttpMethod())
        && context.getRecordId() == null
        && SPEC_FISCAL_CALENDAR.equals(context.getSpecName())
        && ENTITY_YEAR.equals(context.getEntityName());
  }

  private boolean isValidFiscalYear(String value) {
    if (!value.matches("\\d{4}")) {
      return false;
    }
    int year = Integer.parseInt(value);
    return year >= MIN_FISCAL_YEAR && year <= MAX_FISCAL_YEAR;
  }

  /**
   * Server-side guard mirroring the client-side {@code CloseYearConfirmModal} check — the real
   * enforcement point, since the legacy servlets' own validation (if any) is undocumented and
   * must not be trusted alone. Checks {@link Period#getOpenClose()} (the {@code C_Period.OpenClose}
   * column both {@link PeriodOpenCloseHandler} and the classic UI read/write) rather than the
   * {@code periodControl} entity's aggregate {@code Status} column (which has no corresponding DAL
   * property on {@link Period} at all — it is exposed only on the {@code C_PeriodControl}-shaped
   * tab, not modeled as a plain persisted field).
   */
  private boolean allPeriodsClosed(Year year) {
    OBCriteria<Period> criteria = OBDal.getInstance().createCriteria(Period.class);
    criteria.add(Restrictions.eq(Period.PROPERTY_YEAR, year));
    List<Period> periods = criteria.list();
    if (periods.isEmpty()) {
      return false;
    }
    return periods.stream().allMatch(p -> STATUS_CLOSED.equals(p.getOpenClose())
        || STATUS_PERMANENTLY_CLOSED.equals(p.getOpenClose()));
  }

  // Package-private (not private) so tests can override with a canned result instead of
  // exercising the real reflective call into legacy servlet business logic, which would create
  // real accounting entries against the dev DB — see the class javadoc + Task 7/8 delivery notes
  // on this module's lack of a safe rollback convention for OBBaseTest-style integration tests.
  OBError invokeCreateRegFactAcct(Year year) throws Exception {
    CreateRegFactAcct servlet = newServletInstance(CreateRegFactAcct.class);
    VariablesSecureApp vars = buildVars();
    String orgId = year.getOrganization() != null ? year.getOrganization().getId() : "0";
    // CreateRegFactAcct's processButton takes (vars, yearId, orgId, windowId) in that order.
    Method processButton = CreateRegFactAcct.class.getDeclaredMethod("processButton",
        VariablesSecureApp.class, String.class, String.class, String.class);
    processButton.setAccessible(true);
    return (OBError) processButton.invoke(servlet, vars, year.getId(), orgId, year.getId());
  }

  OBError invokeDropRegFactAcct(Year year) throws Exception {
    DropRegFactAcct servlet = newServletInstance(DropRegFactAcct.class);
    VariablesSecureApp vars = buildVars();
    String orgId = year.getOrganization() != null ? year.getOrganization().getId() : "0";
    // DropRegFactAcct's processButton takes (vars, orgId, yearId) in that order — a different
    // parameter order and count from CreateRegFactAcct's own processButton method; the two
    // legacy servlets are NOT symmetric.
    Method processButton = DropRegFactAcct.class.getDeclaredMethod("processButton",
        VariablesSecureApp.class, String.class, String.class);
    processButton.setAccessible(true);
    return (OBError) processButton.invoke(servlet, vars, orgId, year.getId());
  }

  /**
   * Creates a legacy {@code ad_actionButton} servlet instance without calling {@code init()}
   * (which needs a real {@code ServletConfig}/{@code ServletContext}) — instead, the protected
   * {@code myPool} field ({@code HttpBaseServlet}) is set directly to a {@link
   * DalConnectionProvider}, the officially-supported {@code ConnectionProvider} built on the
   * current DAL connection for callers outside a servlet context.
   */
  private <T> T newServletInstance(Class<T> servletClass) throws Exception {
    T servlet = servletClass.getDeclaredConstructor().newInstance();
    Field poolField = findMyPoolField(servletClass);
    poolField.setAccessible(true);
    poolField.set(servlet, new DalConnectionProvider(true));
    return servlet;
  }

  private Field findMyPoolField(Class<?> clazz) throws NoSuchFieldException {
    String className = clazz.getName();
    Class<?> current = clazz;
    while (current != null) {
      try {
        return current.getDeclaredField("myPool");
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException("myPool not found on " + className + " or its superclasses");
  }

  /**
   * Builds a "manual instance" {@link VariablesSecureApp} from the current {@link OBContext} —
   * the same mechanism {@code ProcessContext#toVars()} uses for background process execution.
   */
  private VariablesSecureApp buildVars() {
    OBContext ctx = OBContext.getOBContext();
    String userId = ctx.getUser() != null ? ctx.getUser().getId() : null;
    String clientId = ctx.getCurrentClient() != null ? ctx.getCurrentClient().getId() : null;
    String orgId = ctx.getCurrentOrganization() != null ? ctx.getCurrentOrganization().getId() : null;
    String roleId = ctx.getRole() != null ? ctx.getRole().getId() : null;
    String language = ctx.getLanguage() != null ? ctx.getLanguage().getLanguage() : "en_US";
    return new VariablesSecureApp(userId, clientId, orgId, roleId, language);
  }

  private NeoResponse translateResult(OBError result, String action) {
    if (result == null) {
      return NeoResponse.error(500, action + " failed: no result returned");
    }
    if ("Error".equals(result.getType())) {
      return NeoResponse.error(400, result.getMessage());
    }
    return NeoResponse.ok(successBody(result.getMessage()));
  }

  private org.codehaus.jettison.json.JSONObject successBody(String message) {
    try {
      return new org.codehaus.jettison.json.JSONObject()
          .put("status", "success")
          .put("message", message != null ? message : "Process executed successfully");
    } catch (org.codehaus.jettison.json.JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
