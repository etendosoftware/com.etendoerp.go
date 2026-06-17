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
 * <p>Finally it opens every fiscal period up to and including the current month (driven by today's
 * date) and leaves later periods never-opened, so the onboarded tenant starts with the correct
 * year-to-date open regardless of when onboarding runs. This is the preventive counterpart of the
 * frozen R3-periodcontrol corrective data-fix.
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

  /**
   * Enables period control on the target organization and wires it to its imported calendar.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void wire(String clientId, String orgId, String adminUserId, String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
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
        openPeriodsThroughCurrentMonth(calendar);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
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
   * Opens every fiscal period of the imported calendar whose start date is not in the future, i.e.
   * all periods up to and including the current month, and leaves later periods never-opened. The
   * GOClient source snapshot only opens a fixed prefix of the year (it was authored at a point in
   * time), so without this the freshly onboarded tenant would have the wrong months open relative to
   * the current date.
   *
   * <p>This is the preventive counterpart of the frozen corrective data-fix (R3-periodcontrol),
   * which opens a static prefix (through June). Here the cut-off is dynamic — driven by "today" — so
   * a tenant onboarded in any month gets exactly the year-to-date open.
   *
   * <ul>
   *   <li>open period: {@code C_PERIOD.openclose='C'}; each {@code C_PERIODCONTROL}
   *       {@code periodstatus='O'}, {@code openclose='C'}</li>
   *   <li>closed period: {@code C_PERIOD.openclose='O'}; each {@code C_PERIODCONTROL}
   *       {@code periodstatus='N'}, {@code openclose='O'}</li>
   * </ul>
   *
   * {@code periodaction} stays {@code 'N'} (no action) in both states.
   */
  protected void openPeriodsThroughCurrentMonth(Calendar calendar) {
    Date today = currentDate();
    for (Period period : resolveCalendarPeriods(calendar)) {
      boolean open = !period.getStartingDate().after(today);
      applyPeriodOpenState(period, open);
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
