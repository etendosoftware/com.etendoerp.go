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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * Fiscal calendar building blocks shared by onboarding and the Calendar window (ETP-5577): creating
 * periods, making sure a calendar year exists, and normalizing the period-control rows of a
 * calendar to exactly one row per (period, organization, document base type).
 */
public final class FiscalCalendarSupport {

  /**
   * The fiscal-year starts offered (quarters only, decision Q1), by the Calendar window's
   * "Create periods" and by the calendar review's regenerate.
   */
  public static final Map<String, Integer> FISCAL_YEAR_STARTS = Map.of(
      "JANUARY", 1, "APRIL", 4, "JULY", 7, "OCTOBER", 10);

  private static final Logger log = LogManager.getLogger(FiscalCalendarSupport.class);
  private static final String PERIOD_TYPE_STANDARD = "S";
  private static final String PARAM_CALENDAR_ID = "calendarId";

  /** Periods of the calendar, the scope every statement below works on. */
  private static final String CALENDAR_PERIODS =
      "SELECT p.c_period_id FROM c_period p JOIN c_year y ON y.c_year_id = p.c_year_id"
          + " WHERE y.c_calendar_id = :calendarId";

  /**
   * Inserts the missing control rows. The target key set is the one {@code AD_ORG_READY} and
   * {@code C_PERIOD_TRG} build: ready, period-control-allowed organizations inheriting this calendar
   * × standard periods × every document base type (reference 183), adjustment periods × GLJ only.
   * Not-ready organizations are left out, or {@code AD_ORG_READY} would duplicate them later.
   */
  static final String INSERT_MISSING_SQL =
      "INSERT INTO c_periodcontrol (c_periodcontrol_id, ad_client_id, ad_org_id, isactive,"
          + " created, createdby, updated, updatedby, c_period_id, docbasetype, periodstatus,"
          + " periodaction, openclose)"
          + " SELECT get_uuid(), p.ad_client_id, o.ad_org_id, 'Y', now(), '0', now(), '0',"
          + " p.c_period_id, rl.value, 'N', 'N', 'O'"
          + " FROM c_period p JOIN c_year y ON y.c_year_id = p.c_year_id"
          + " JOIN ad_org o ON o.ad_inheritedcalendar_id = y.c_calendar_id AND o.isready = 'Y'"
          + " AND o.isperiodcontrolallowed = 'Y'"
          + " JOIN ad_ref_list rl ON rl.ad_reference_id = '183'"
          + " AND rl.value = (CASE WHEN p.periodtype = 'S' THEN rl.value ELSE 'GLJ' END)"
          + " WHERE y.c_calendar_id = :calendarId"
          + " AND NOT EXISTS (SELECT 1 FROM c_periodcontrol c WHERE c.c_period_id = p.c_period_id"
          + " AND c.ad_org_id = o.ad_org_id AND c.docbasetype = rl.value)";

  /**
   * Duplicate groups are resolved only when they hold at most one distinct non-never-opened status;
   * a group mixing, say, open and closed rows is a real conflict and is left for a human.
   */
  private static final String RESOLVABLE_GROUPS =
      "SELECT c_period_id, ad_org_id, docbasetype FROM c_periodcontrol"
          + " WHERE c_period_id IN (" + CALENDAR_PERIODS + ")"
          + " GROUP BY c_period_id, ad_org_id, docbasetype HAVING COUNT(*) > 1";

  /**
   * Deletes the surplus rows of every resolvable duplicate group, keeping one survivor: active
   * first, then the most advanced status (P &gt; C &gt; O &gt; N), then the latest update, then the
   * id. No foreign key references {@code c_periodcontrol}.
   */
  static final String DELETE_SURPLUS_SQL =
      "DELETE FROM c_periodcontrol WHERE c_periodcontrol_id IN ("
          + " SELECT r.c_periodcontrol_id FROM ("
          + " SELECT c.c_periodcontrol_id, ROW_NUMBER() OVER (PARTITION BY c.c_period_id,"
          + " c.ad_org_id, c.docbasetype ORDER BY CASE WHEN c.isactive = 'Y' THEN 0 ELSE 1 END,"
          + " CASE c.periodstatus WHEN 'P' THEN 0 WHEN 'C' THEN 1 WHEN 'O' THEN 2 ELSE 3 END,"
          + " c.updated DESC, c.c_periodcontrol_id) AS rn"
          + " FROM c_periodcontrol c JOIN (" + RESOLVABLE_GROUPS
          + " AND COUNT(DISTINCT CASE WHEN periodstatus <> 'N' THEN periodstatus END) <= 1) g"
          + " ON g.c_period_id = c.c_period_id AND g.ad_org_id = c.ad_org_id"
          + " AND g.docbasetype = c.docbasetype) r WHERE r.rn > 1)";

  /** Duplicate groups left untouched because they mix two or more real statuses. */
  static final String COUNT_CONFLICTS_SQL =
      "SELECT COUNT(*) FROM (" + RESOLVABLE_GROUPS
          + " AND COUNT(DISTINCT CASE WHEN periodstatus <> 'N' THEN periodstatus END) > 1) g";

  /**
   * Recomputes {@code C_PERIOD.openclose} from its controls: 'C' (close available, i.e. open) only
   * when every control row is open, as the core open/close process does; 'O' otherwise.
   */
  static final String RECOMPUTE_OPENCLOSE_SQL =
      "UPDATE c_period p SET openclose = x.openclose, updated = now(), updatedby = '0'"
          + " FROM (SELECT c.c_period_id,"
          + " CASE WHEN bool_and(c.periodstatus = 'O') THEN 'C' ELSE 'O' END AS openclose"
          + " FROM c_periodcontrol c WHERE c.c_period_id IN (" + CALENDAR_PERIODS + ")"
          + " GROUP BY c.c_period_id) x"
          + " WHERE p.c_period_id = x.c_period_id AND p.openclose IS DISTINCT FROM x.openclose";

  private FiscalCalendarSupport() {
  }

  /**
   * Leaves the calendar with exactly one control row per (period, ready organization, document
   * base type) and recomputes each period's open/close flag. Idempotent: a second run changes
   * nothing. Native SQL on the DAL session, so pending DAL changes are flushed first.
   *
   * @param calendarId calendar to normalize
   */
  public static void normalizePeriodControls(String calendarId) {
    OBDal.getInstance().flush();
    int inserted = executeUpdate(INSERT_MISSING_SQL, calendarId);
    int deleted = executeUpdate(DELETE_SURPLUS_SQL, calendarId);
    Number conflicts = (Number) OBDal.getInstance().getSession()
        .createNativeQuery(COUNT_CONFLICTS_SQL)
        .setParameter(PARAM_CALENDAR_ID, calendarId)
        .uniqueResult();
    executeUpdate(RECOMPUTE_OPENCLOSE_SQL, calendarId);
    if (inserted > 0 || deleted > 0) {
      log.info("ETP-5577 normalized period controls of calendar {}: {} inserted, {} deleted",
          calendarId, inserted, deleted);
    }
    if (conflicts != null && conflicts.intValue() > 0) {
      log.warn("ETP-5577 calendar {} keeps {} duplicate period-control groups with conflicting"
          + " statuses; left for manual review", calendarId, conflicts);
    }
  }

  private static int executeUpdate(String sql, String calendarId) {
    return OBDal.getInstance().getSession()
        .createNativeQuery(sql)
        .setParameter(PARAM_CALENDAR_ID, calendarId)
        .executeUpdate();
  }

  /**
   * Makes sure the calendar has a January-December year {@code fiscalYear} with its twelve
   * periods. An existing year with periods is left alone (whatever its start month); a missing one
   * is created, and an existing one without periods gets them. {@code C_PERIOD_TRG} adds the
   * control rows, one per (period, organization, document base type), so a year created here needs
   * no normalization (the productive branch normalizes anyway, for the dataset's own rows).
   *
   * @param calendar   the organization's calendar
   * @param fiscalYear the calendar year to ensure, e.g. 2026
   * @return {@code true} when periods were created
   */
  public static boolean ensureYear(Calendar calendar, int fiscalYear) {
    Year year = findYear(calendar, fiscalYear);
    if (year == null) {
      year = OBProvider.getInstance().get(Year.class);
      year.setNewOBObject(true);
      year.setClient(calendar.getClient());
      year.setOrganization(calendar.getOrganization());
      year.setActive(true);
      year.setCalendar(calendar);
      year.setFiscalYear(String.valueOf(fiscalYear));
      OBDal.getInstance().save(year);
    } else if (hasPeriods(year)) {
      return false;
    }
    for (int periodNo = 1; periodNo <= 12; periodNo++) {
      createPeriod(year, periodNo, LocalDate.of(fiscalYear, 1, 1).plusMonths(periodNo - 1L),
          PERIOD_TYPE_STANDARD);
    }
    OBDal.getInstance().flush();
    log.info("ETP-5577 created fiscal year {} on calendar {}", fiscalYear, calendar.getId());
    return true;
  }

  static Year findYear(Calendar calendar, int fiscalYear) {
    OBCriteria<Year> criteria = OBDal.getInstance().createCriteria(Year.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Year.PROPERTY_CALENDAR, calendar));
    criteria.add(Restrictions.eq(Year.PROPERTY_FISCALYEAR, String.valueOf(fiscalYear)));
    criteria.setMaxResults(1);
    return (Year) criteria.uniqueResult();
  }

  /**
   * Standard periods of the calendar, in any year other than {@code year}, that overlap
   * [{@code start}, {@code endExclusive}). The core overlap check ({@code PeriodEventHandler})
   * compares against the period's end date, which is still null when the DAL saves it (the database
   * derives it), so it does not catch this.
   */
  static final String OVERLAP_SQL =
      "SELECT COUNT(*) FROM c_period p JOIN c_year y ON y.c_year_id = p.c_year_id"
          + " WHERE y.c_calendar_id = :calendarId AND y.c_year_id <> :yearId"
          + " AND p.periodtype = 'S' AND p.startdate < :endExclusive AND p.enddate >= :start";

  /** Start month of the calendar's latest year that has standard periods, if any. */
  static final String LATEST_START_MONTH_SQL =
      "SELECT EXTRACT(MONTH FROM MIN(p.startdate)) FROM c_period p"
          + " JOIN c_year y ON y.c_year_id = p.c_year_id"
          + " WHERE y.c_calendar_id = :calendarId AND y.c_year_id <> :yearId"
          + " AND p.periodtype = 'S' GROUP BY y.c_year_id ORDER BY MIN(p.startdate) DESC LIMIT 1";

  /**
   * Whether twelve months from {@code start} would overlap another year of the calendar.
   *
   * @param year  the year about to get periods (excluded from the check)
   * @param start the first day of its first period
   * @return {@code true} when another year of the calendar covers any of those twelve months
   */
  public static boolean overlapsOtherYears(Year year, LocalDate start) {
    OBDal.getInstance().flush();
    Number count = (Number) OBDal.getInstance().getSession().createNativeQuery(OVERLAP_SQL)
        .setParameter(PARAM_CALENDAR_ID, year.getCalendar().getId())
        .setParameter("yearId", year.getId())
        .setParameter("start", toDate(start))
        .setParameter("endExclusive", toDate(start.plusMonths(12)))
        .uniqueResult();
    return count != null && count.intValue() > 0;
  }

  /**
   * The month the calendar's latest other year starts in, so a new year keeps the calendar's
   * fiscal-year start.
   *
   * @param year the year about to get periods (excluded from the lookup)
   * @return the start month (1-12), or {@code null} when the calendar has no other year with
   *     periods
   */
  public static Integer latestStartMonth(Year year) {
    java.util.List<?> rows = OBDal.getInstance().getSession()
        .createNativeQuery(LATEST_START_MONTH_SQL)
        .setParameter(PARAM_CALENDAR_ID, year.getCalendar().getId())
        .setParameter("yearId", year.getId())
        .list();
    return rows.isEmpty() || rows.get(0) == null ? null : ((Number) rows.get(0)).intValue();
  }

  private static Date toDate(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * True when the year already has at least one period.
   *
   * @param year the year to inspect
   * @return {@code true} when it has a period
   */
  public static boolean hasPeriods(Year year) {
    OBCriteria<Period> criteria = OBDal.getInstance().createCriteria(Period.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Period.PROPERTY_YEAR, year));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  /**
   * Creates one period of {@code year}. Names follow the onboarding dataset ("Jan-26"; adjustment
   * periods "13th Period - 27"). The end date is derived by the database.
   *
   * @param year       the owning year
   * @param periodNo   1-12 for standard periods, 13 for the adjustment period
   * @param start      the period's first day
   * @param periodType {@code S} standard or {@code A} adjustment
   */
  public static void createPeriod(Year year, int periodNo, LocalDate start, String periodType) {
    Period period = OBProvider.getInstance().get(Period.class);
    period.setNewOBObject(true);
    period.setClient(year.getClient());
    period.setOrganization(year.getOrganization());
    period.setActive(true);
    period.setYear(year);
    period.setPeriodNo((long) periodNo);
    period.setStartingDate(Date.from(start.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    period.setName("A".equals(periodType)
        ? "13th Period - " + start.format(DateTimeFormatter.ofPattern("yy"))
        : start.format(DateTimeFormatter.ofPattern("MMM-yy", Locale.ENGLISH)));
    period.setPeriodType(periodType);
    OBDal.getInstance().save(period);
  }
}
