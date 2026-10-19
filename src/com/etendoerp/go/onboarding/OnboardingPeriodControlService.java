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
package com.etendoerp.go.onboarding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;
import org.openbravo.model.financialmgmt.calendar.Year;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * Enables fiscal period control on the newly created organization (Gap C1).
 *
 * <p>The onboarding dataset import brings in the calendar, years and periods (and, with Gap C2, the
 * {@code C_PERIODCONTROL} rows), all remapped to the target organization. It does NOT set the
 * fiscal-control columns on {@code AD_ORG}, because {@code AD_ORG} is excluded from the import. This
 * service closes that gap by pointing the organization at its imported calendar and marking it as a
 * period-control owner, mirroring the golden GOOrg organization:
 *
 * <ul>
 *   <li>{@code isperiodcontrolallowed = 'Y'}</li>
 *   <li>{@code c_calendar_id} → the imported calendar</li>
 *   <li>{@code ad_inheritedcalendar_id} → the same imported calendar</li>
 *   <li>{@code ad_periodcontrolallowed_org_id} → the organization itself</li>
 *   <li>{@code ad_calendarowner_org_id} → the organization itself</li>
 * </ul>
 *
 * <p>It also rebrands the imported calendar name, replacing the GOClient template moniker with the
 * tenant's client name ("GOOrg Calendar" → "&lt;client&gt; Calendar"), mirroring the chart rebrand
 * in {@link OnboardingAccountingWiringService}.
 *
 * <p>Finally it leaves every period never-opened (ETP-5577). The chain also builds pooled tenants,
 * before anyone knows whether a demo or a productive onboarding will claim them, so it must not
 * decide which periods are open. That happens after the onboarding commit:
 * {@link #openDemoTrialWindow} for demos (ETP-5575) and {@link #applyProductiveInitialState} for
 * productive tenants. {@link #normalize} then leaves one control row per period, organization and
 * document base type, whichever copies the dataset import and the core triggers produced.
 *
 * <p>Accounting-schema (general-ledger) wiring is out of scope here — that belongs to Gap A1 and
 * lives in {@link OnboardingAccountingWiringService}.
 */
public class OnboardingPeriodControlService extends OnboardingContextSupport {

  private static final Logger log = LogManager.getLogger(OnboardingPeriodControlService.class);

  /** {@code C_PERIODCONTROL.periodstatus} for an open period (reference value "Open"). */
  private static final String PERIOD_STATUS_OPEN = "O";
  /** {@code C_PERIODCONTROL.periodstatus} for a period that was never opened. */
  private static final String PERIOD_STATUS_NEVER_OPENED = "N";
  /** {@code C_PERIODCONTROL.periodaction} stays "No action" in both states. */
  private static final String PERIOD_ACTION_NONE = "N";
  /**
   * {@code openclose} carries the inverse/available-action flag: 'C' (close-available) marks an open
   * period, 'O' (open-available) marks a closed/never-opened one. Applies to both {@code C_PERIOD}
   * and {@code C_PERIODCONTROL}.
   */
  private static final String OPENCLOSE_OPEN = "C";
  private static final String OPENCLOSE_CLOSED = "O";
  /** Margin for the tenant's unknown time zone around "now" when ensuring calendar years. */
  private static final Duration ONE_DAY = Duration.ofDays(1);

  /**
   * Enables period control on the target organization and wires it to its imported calendar.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void wire(String clientId, String orgId, String adminUserId, String adminRoleId) {
    runInTenantContext(clientId, orgId, adminUserId, adminRoleId, () -> {
      Organization org = resolveOrganization(orgId);
      if (org == null) {
        throw new OBException("Organization not found for period-control wiring: " + orgId);
      }
      Calendar calendar = resolveImportedCalendar(org);
      if (calendar == null) {
        throw new OBException("No calendar was imported for client " + clientId
            + "; cannot enable period control on the organization");
      }
      enablePeriodControl(org, calendar);
      rebrandImportedCalendarName(org, calendar);
      resetPeriodsToNeverOpened(calendar);
      flushChanges();
    });
  }

  /**
   * Opens the fiscal periods a demo tenant needs for its whole trial (ETP-5575).
   *
   * <p>{@link #wire} leaves every period never-opened (ETP-5577), because the chain also builds
   * pooled tenants that may become productive. This method runs after the onboarding commit, for
   * demo signups only, and opens every never-opened period whose start is on or before the trial
   * end, so the demo can post during its whole trial.
   *
   * <p>It only opens: a control row is flipped from never-opened ({@code 'N'}) to open, and rows a
   * user closed ({@code 'C'}) or closed permanently ({@code 'P'}) are never touched. Every control row
   * of a period is considered, so a duplicated row (see {@link #normalize}) ends up open too:
   * posting needs any open row and costing reads any non-open row as closed.
   *
   * <p>First it makes sure the calendar has every year the trial touches (ETP-5744): every calendar
   * year in [min(trial start, now) - 1 day, max(trial end, now + 1 day)], so a trial crossing into
   * January, or a demo created or claimed after the dataset's last year, can still post. The day of
   * margin covers a 31 December / 1 January signup, as in {@link #applyProductiveInitialState}. The
   * core {@code C_PERIOD_TRG} trigger adds one never-opened control row per new period, so no
   * normalization is needed.
   *
   * @param clientId    demo client identifier
   * @param orgId       demo organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   * @param trialStart  first instant of the trial ({@code ETGO_DemoTrialStartedAt})
   * @param trialDays   configured trial length in days
   */
  public void openDemoTrialWindow(String clientId, String orgId, String adminUserId,
      String adminRoleId, Instant trialStart, int trialDays) {
    if (trialStart == null) {
      throw new OBException("Missing trial start for demo period window of client " + clientId);
    }
    runInTenantContext(clientId, orgId, adminUserId, adminRoleId, () -> {
      Calendar calendar = resolveOrgCalendar(clientId, orgId);
      Instant now = currentDate().toInstant();
      Instant trialEnd = trialStart.plus(Duration.ofDays(Math.max(trialDays, 0)));
      ensureYears(calendar, (trialStart.isBefore(now) ? trialStart : now).minus(ONE_DAY),
          trialEnd.isAfter(now.plus(ONE_DAY)) ? trialEnd : now.plus(ONE_DAY));
      openNeverOpenedPeriodsThrough(resolveCalendarPeriods(calendar), Date.from(trialEnd));
      flushChanges();
    });
  }

  /** Ensures every calendar year touched by [from, to], in the server's time zone. */
  private static void ensureYears(Calendar calendar, Instant from, Instant to) {
    int lastYear = to.atZone(ZoneId.systemDefault()).getYear();
    for (int year = from.atZone(ZoneId.systemDefault()).getYear(); year <= lastYear; year++) {
      FiscalCalendarSupport.ensureYear(calendar, year);
    }
  }

  /**
   * Validates the context, switches to the tenant's admin context in admin mode, runs the action and
   * always restores the previous context.
   */
  private void runInTenantContext(String clientId, String orgId, String adminUserId,
      String adminRoleId, Runnable action) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        action.run();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  /**
   * Open-only: for every period starting on or before {@code cutoff}, flips its never-opened
   * control rows to open and leaves every other row alone. Later periods are not touched. The
   * period's own {@code openclose} flag is then
   * recomputed from its controls: open ({@code 'C'}) only when every control row is open, as the core
   * open/close process does.
   */
  protected void openNeverOpenedPeriodsThrough(List<Period> periods, Date cutoff) {
    for (Period period : periods) {
      if (period.getStartingDate() == null || period.getStartingDate().after(cutoff)) {
        continue;
      }
      List<PeriodControl> controls = resolvePeriodControls(period);
      boolean allOpen = !controls.isEmpty();
      for (PeriodControl control : controls) {
        if (PERIOD_STATUS_NEVER_OPENED.equals(control.getPeriodStatus())) {
          control.setPeriodStatus(PERIOD_STATUS_OPEN);
          control.setPeriodAction(PERIOD_ACTION_NONE);
          control.setOpenClose(OPENCLOSE_OPEN);
          OBDal.getInstance().save(control);
        }
        allOpen &= PERIOD_STATUS_OPEN.equals(control.getPeriodStatus());
      }
      String openClose = allOpen ? OPENCLOSE_OPEN : OPENCLOSE_CLOSED;
      if (!openClose.equals(period.getOpenClose())) {
        period.setOpenClose(openClose);
        OBDal.getInstance().save(period);
      }
    }
  }

  /**
   * Resolves the calendar to enable on the organization. The dataset import remaps GOClient's single
   * calendar to the target organization, so the organization-owned calendar is preferred; if none is
   * yet owned by the org, the first calendar of the client is used.
   */
  protected Calendar resolveImportedCalendar(Organization org) {
    OBCriteria<Calendar> criteria = OBDal.getInstance().createCriteria(Calendar.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Calendar.PROPERTY_ORGANIZATION, org));
    criteria.addOrderBy(Calendar.PROPERTY_ID, true);
    criteria.setMaxResults(2);
    java.util.List<Calendar> calendars = criteria.list();
    if (calendars.isEmpty()) {
      return resolveClientCalendar(org);
    }
    if (calendars.size() > 1) {
      log.warn("Organization {} owns more than one calendar; using {}", org.getId(),
          calendars.get(0).getId());
    }
    return calendars.get(0);
  }

  private Calendar resolveClientCalendar(Organization org) {
    OBCriteria<Calendar> criteria = OBDal.getInstance().createCriteria(Calendar.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Calendar.PROPERTY_CLIENT, org.getClient()));
    criteria.addOrderBy(Calendar.PROPERTY_ID, true);
    criteria.setMaxResults(1);
    return (Calendar) criteria.uniqueResult();
  }

  /**
   * Applies the golden GOOrg fiscal-control configuration to the organization: it allows period
   * control, points at the imported calendar (both as direct and inherited calendar), and declares
   * itself as the period-control and calendar owner.
   */
  protected void enablePeriodControl(Organization org, Calendar calendar) {
    org.setAllowPeriodControl(true);
    org.setCalendar(calendar);
    org.setInheritedCalendar(calendar);
    org.setPeriodControlAllowedOrganization(org);
    org.setCalendarOwnerOrganization(org);
    OBDal.getInstance().save(org);
  }

  /**
   * Rebrands the imported calendar so its name carries the tenant's client name instead of the
   * GOClient template moniker ("GOOrg Calendar" → "&lt;client&gt; Calendar"), mirroring the chart
   * rebrand in {@link OnboardingAccountingWiringService}. Without this the calendar would advertise
   * "GO" in every onboarded tenant. The name is left untouched when it carries no moniker.
   */
  protected void rebrandImportedCalendarName(Organization org, Calendar calendar) {
    String clientName = org.getClient().getName();
    String rebranded = OnboardingSourceMoniker.replace(calendar.getName(), clientName);
    if (!rebranded.equals(calendar.getName())) {
      calendar.setName(rebranded);
      OBDal.getInstance().save(calendar);
    }
  }

  /**
   * Leaves the organization's calendar with exactly one control row per (period, organization,
   * document base type) (ETP-5577). Runs after {@code markOrgReady}: the dataset import and the core
   * {@code AD_ORG_READY} / {@code C_PERIOD_TRG} insertions can each bring a full copy, and a
   * duplicated period shows as "Mixto" and cannot be opened by the user alone.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void normalize(String clientId, String orgId, String adminUserId, String adminRoleId) {
    runInTenantContext(clientId, orgId, adminUserId, adminRoleId, () -> {
      Calendar calendar = resolveOrgCalendar(clientId, orgId);
      flushChanges();
      FiscalCalendarSupport.normalizePeriodControls(calendar.getId());
    });
  }

  /**
   * Initial period state of a productive tenant (ETP-5577), after the onboarding commit. Makes sure
   * the calendar covers the signup year: every calendar year in [now - 1 day, now + 1 day], because
   * the tenant's timezone is unknown and a 31 December / 1 January signup must get both years.
   * Normalizes the controls last. Every period stays never-opened: the owner chooses what to open
   * after reviewing the calendar in "Primeros pasos".
   *
   * @param clientId         productive client identifier
   * @param orgId            productive organization identifier
   * @param adminUserId      administrator user for DAL context
   * @param adminRoleId      administrator role for DAL context
   */
  public void applyProductiveInitialState(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    runInTenantContext(clientId, orgId, adminUserId, adminRoleId, () -> {
      Calendar calendar = resolveOrgCalendar(clientId, orgId);
      Instant now = currentDate().toInstant();
      ensureYears(calendar, now.minus(ONE_DAY), now.plus(ONE_DAY));
      // Last, and native: it recomputes every period's open/close flag from the surviving rows.
      flushChanges();
      FiscalCalendarSupport.normalizePeriodControls(calendar.getId());
    });
  }

  private Calendar resolveOrgCalendar(String clientId, String orgId) {
    Organization org = resolveOrganization(orgId);
    if (org == null) {
      throw new OBException("Organization not found for period control: " + orgId);
    }
    Calendar calendar = org.getCalendar() != null ? org.getCalendar()
        : resolveImportedCalendar(org);
    if (calendar == null) {
      throw new OBException("No calendar found for period control of client " + clientId);
    }
    return calendar;
  }

  /**
   * Force-writes every period of the calendar and all its control rows to never-opened
   * ({@code C_PERIOD.openclose='O'}; each {@code C_PERIODCONTROL} {@code periodstatus='N'},
   * {@code periodaction='N'}, {@code openclose='O'}). The GOClient dataset ships some months open,
   * and the chain must not decide which periods are open (see the class Javadoc).
   */
  protected void resetPeriodsToNeverOpened(Calendar calendar) {
    for (Period period : resolveCalendarPeriods(calendar)) {
      applyPeriodOpenState(period, false);
    }
  }

  /**
   * Resolves every {@link Period} belonging to the calendar by walking its years. The import remaps
   * the calendar to the target organization, so reading filters are disabled to be safe.
   */
  protected List<Period> resolveCalendarPeriods(Calendar calendar) {
    OBCriteria<Year> yearCriteria = OBDal.getInstance().createCriteria(Year.class);
    yearCriteria.setFilterOnReadableClients(false);
    yearCriteria.setFilterOnReadableOrganization(false);
    yearCriteria.add(Restrictions.eq(Year.PROPERTY_CALENDAR, calendar));
    List<Year> years = yearCriteria.list();
    if (years.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    // Query the periods directly instead of walking Year#getFinancialMgmtPeriodList(): the calendar,
    // years and periods are created by the XML dataset import (DataImportService) through a separate
    // persistence path, so the lazy child collection on the freshly loaded Year comes back empty in
    // this same transaction — which would silently leave every period at its imported open-state. A
    // direct criteria query always reflects the just-imported rows.
    OBCriteria<Period> periodCriteria = OBDal.getInstance().createCriteria(Period.class);
    periodCriteria.setFilterOnReadableClients(false);
    periodCriteria.setFilterOnReadableOrganization(false);
    periodCriteria.add(Restrictions.in(Period.PROPERTY_YEAR, years));
    return periodCriteria.list();
  }

  private void applyPeriodOpenState(Period period, boolean open) {
    period.setOpenClose(open ? OPENCLOSE_OPEN : OPENCLOSE_CLOSED);
    OBDal.getInstance().save(period);
    for (PeriodControl control : resolvePeriodControls(period)) {
      control.setPeriodStatus(open ? PERIOD_STATUS_OPEN : PERIOD_STATUS_NEVER_OPENED);
      control.setPeriodAction(PERIOD_ACTION_NONE);
      control.setOpenClose(open ? OPENCLOSE_OPEN : OPENCLOSE_CLOSED);
      OBDal.getInstance().save(control);
    }
  }

  /**
   * Resolves the period-control rows of a period via a direct query rather than
   * {@link Period#getFinancialMgmtPeriodControlList()}. As with the period list, these rows are
   * created by the XML dataset import through a separate persistence path, so the lazy child
   * collection on the loaded Period can be empty within this transaction; a direct criteria query
   * always sees the imported rows. The period status is held per document base type (one control row
   * each), so every one of them must be flipped for the period to be effectively open.
   */
  protected List<PeriodControl> resolvePeriodControls(Period period) {
    OBCriteria<PeriodControl> criteria = OBDal.getInstance().createCriteria(PeriodControl.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(PeriodControl.PROPERTY_PERIOD, period));
    return criteria.list();
  }

  /** Seam for tests: the reference instant used to decide which periods are open. */
  protected Date currentDate() {
    return new Date();
  }

  @Override
  protected String contextSubject() {
    return "period-control wiring";
  }
}
