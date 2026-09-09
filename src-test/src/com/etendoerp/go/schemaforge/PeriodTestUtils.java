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

import java.util.Date;
import java.util.List;

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

/**
 * Test-only helper that guarantees the fiscal period covering a given date is open before a test
 * saves an accounting document header (Order, Goods Receipt/Shipment, Invoice, ...).
 *
 * <p>Etendo's {@code C_ORDER}/{@code M_INOUT}/{@code C_INVOICE} insert triggers validate the
 * header's accounting date against the org's calendar and reject the row outright when the
 * covering period is not open ({@code C_PERIODCONTROL.periodstatus <> 'O'}) — this happens on a
 * plain {@code OBDal.save()+flush()}, independent of {@code documentAction}/{@code
 * documentStatus}. {@code PurchaseOrderUtils.createPurchaseOrder()} and its sibling helpers stamp
 * {@code accountingDate = new Date()} on every header, and the reference test data only opens a
 * fixed prefix of periods (whatever was open when the dataset was authored) — so a test passes
 * only while "today" falls inside that prefix, and fails on a clean environment or once the
 * current year rolls into a period the seed never opened.
 *
 * <p>This resolves the period from the current organization's own calendar — never a hardcoded
 * period id — so the same document date driving the header also drives which period gets opened.
 * Callers run it inside a transaction that gets rolled back at test teardown (see {@code
 * OBBaseTest#tearDown}), so the open-period change never leaks past the test.
 *
 * <p>Assumes the covering {@link Period} row already exists in the calendar (Etendo calendars are
 * seeded with full years ahead of time) and only flips its status; it does not create new periods
 * for dates outside the seeded calendar range.
 */
public final class PeriodTestUtils {

  private static final String PERIOD_STATUS_OPEN = "O";
  private static final String PERIOD_ACTION_NONE = "N";
  private static final String OPENCLOSE_OPEN = "C";

  private PeriodTestUtils() {
  }

  /**
   * Opens the fiscal period covering {@code date} for the current {@link OBContext}
   * organization, so a subsequent document header dated {@code date} can be saved.
   */
  public static void ensureOpenPeriod(Date date) {
    Organization org = OBContext.getOBContext().getCurrentOrganization();
    Calendar calendar = resolveCalendar(org);
    if (calendar == null) {
      throw new OBException(
          "Organization " + org.getId() + " has no calendar; cannot resolve its fiscal period");
    }
    Period period = resolvePeriod(calendar, date);
    if (period == null) {
      throw new OBException("No fiscal period covers " + date + " in calendar " + calendar.getId()
          + "; extend the reference calendar data for this date");
    }
    openPeriod(period);
    OBDal.getInstance().flush();
  }

  private static Calendar resolveCalendar(Organization org) {
    Calendar calendar = org.getCalendar();
    return calendar != null ? calendar : org.getInheritedCalendar();
  }

  private static Period resolvePeriod(Calendar calendar, Date date) {
    OBCriteria<Year> yearCriteria = OBDal.getInstance().createCriteria(Year.class);
    yearCriteria.setFilterOnReadableClients(false);
    yearCriteria.setFilterOnReadableOrganization(false);
    yearCriteria.add(Restrictions.eq(Year.PROPERTY_CALENDAR, calendar));
    List<Year> years = yearCriteria.list();
    if (years.isEmpty()) {
      return null;
    }

    // Compare CALENDAR DAYS, not instants. C_PERIOD.startdate/enddate are stored at midnight,
    // while callers pass `new Date()` — an instant with a time-of-day. Matching the raw instant
    // makes `enddate >= date` false for the whole LAST DAY of every period (2026-08-31 00:00 is
    // not >= 2026-08-31 11:52), so every accounting-document test failed on month-end days and
    // passed on any other, reported as the misleading "No fiscal period covers ... extend the
    // reference calendar data" below. Truncating to local midnight makes the bounds inclusive.
    Date day = atStartOfDay(date);
    OBCriteria<Period> periodCriteria = OBDal.getInstance().createCriteria(Period.class);
    periodCriteria.setFilterOnReadableClients(false);
    periodCriteria.setFilterOnReadableOrganization(false);
    periodCriteria.add(Restrictions.in(Period.PROPERTY_YEAR, years));
    periodCriteria.add(Restrictions.le(Period.PROPERTY_STARTINGDATE, day));
    periodCriteria.add(Restrictions.ge(Period.PROPERTY_ENDINGDATE, day));
    periodCriteria.setMaxResults(1);
    return (Period) periodCriteria.uniqueResult();
  }

  /**
   * Truncates {@code date} to local midnight, so a period's inclusive {@code startdate} /
   * {@code enddate} bounds (both stored at midnight) also match the first and last day of the
   * period when the caller hands in a timestamped {@code new Date()}.
   */
  private static Date atStartOfDay(Date date) {
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.setTime(date);
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    cal.set(java.util.Calendar.MILLISECOND, 0);
    return cal.getTime();
  }

  private static void openPeriod(Period period) {
    period.setOpenClose(OPENCLOSE_OPEN);
    OBDal.getInstance().save(period);
    for (PeriodControl control : resolvePeriodControls(period)) {
      if (PERIOD_STATUS_OPEN.equals(control.getPeriodStatus())) {
        continue;
      }
      control.setPeriodStatus(PERIOD_STATUS_OPEN);
      control.setPeriodAction(PERIOD_ACTION_NONE);
      control.setOpenClose(OPENCLOSE_OPEN);
      OBDal.getInstance().save(control);
    }
  }

  /**
   * Resolves the period-control rows of a period via a direct query rather than {@link
   * Period#getFinancialMgmtPeriodControlList()}: the status is held per document base type (one
   * control row each), so every one of them must be flipped for the period to be effectively open
   * for whichever document type (PO, MMR, ARI, API, ...) the test ends up saving.
   */
  private static List<PeriodControl> resolvePeriodControls(Period period) {
    OBCriteria<PeriodControl> criteria = OBDal.getInstance().createCriteria(PeriodControl.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(PeriodControl.PROPERTY_PERIOD, period));
    return criteria.list();
  }
}
