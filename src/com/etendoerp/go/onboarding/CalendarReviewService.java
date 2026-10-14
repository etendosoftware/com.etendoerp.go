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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * "Revisar calendario contable" (ETP-5577): the state of a tenant's accounting calendar review
 * and its two actions, confirming the calendar and regenerating it with another fiscal-year start.
 *
 * <p>Scope is the calendar of the tenant's period-control organization (the onboarding org). The
 * review is per environment and derived from data: a tenant is reviewed once the owner confirmed
 * or regenerated ({@value #REVIEWED_ATTRIBUTE}), or as soon as any period was ever opened
 * ({@code C_PeriodControl} status or an open in {@code C_PeriodControl_Log}) or anything was
 * posted. Whether the step is shown at all is a separate question: only tenants marked
 * {@code ETGO_CalendarReviewRequired} at onboarding ({@code required}) see it.
 */
public class CalendarReviewService {

  private static final Logger log = LogManager.getLogger(CalendarReviewService.class);

  public static final String REVIEWED_ATTRIBUTE = "ETGO_CalendarReviewed";

  /** The fiscal-year starts the Calendar window offers (quarters only, decision Q1). */
  public static final Map<String, Integer> FISCAL_YEAR_STARTS = Map.of(
      "JANUARY", 1, "APRIL", 4, "JULY", 7, "OCTOBER", 10);

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_CALENDAR_ID = "calendarId";
  private static final String PARAM_TODAY = "today";

  private static final String CALENDAR_PERIODS =
      "SELECT p.c_period_id FROM c_period p JOIN c_year y ON y.c_year_id = p.c_year_id"
          + " WHERE y.c_calendar_id = :calendarId";

  /** The calendar of the tenant's first period-control organization. */
  static final String CALENDAR_SQL =
      "SELECT o.c_calendar_id FROM ad_org o WHERE o.ad_client_id = :clientId"
          + " AND o.isperiodcontrolallowed = 'Y' AND o.c_calendar_id IS NOT NULL"
          + " ORDER BY o.created, o.ad_org_id LIMIT 1";

  /** The fiscal year that contains today: label, first day, last day, standard periods. */
  static final String CURRENT_YEAR_SQL =
      "SELECT y.year, MIN(p.startdate), MAX(p.enddate), COUNT(*)"
          + " FROM c_year y JOIN c_period p ON p.c_year_id = y.c_year_id AND p.periodtype = 'S'"
          + " WHERE y.c_calendar_id = :calendarId GROUP BY y.c_year_id, y.year"
          + " HAVING MIN(p.startdate) <= :today AND MAX(p.enddate) >= :today"
          + " ORDER BY MIN(p.startdate) DESC LIMIT 1";

  /**
   * Any period ever opened: a control that is not never-opened, or an open logged by the
   * Calendar window or the MCP ({@code PeriodOpenCloseHandler}). A close of a never-opened
   * period is logged too and must not count.
   */
  static final String ANY_PERIOD_OPENED_SQL =
      "SELECT CASE WHEN EXISTS (SELECT 1 FROM c_periodcontrol c WHERE c.periodstatus <> 'N'"
          + " AND c.c_period_id IN (" + CALENDAR_PERIODS + "))"
          + " OR EXISTS (SELECT 1 FROM c_periodcontrol_log l WHERE l.periodaction = 'O'"
          + " AND (l.c_period_id IN (" + CALENDAR_PERIODS + ") OR l.periodno IN ("
          + CALENDAR_PERIODS + "))) THEN 1 ELSE 0 END";

  static final String ANY_POSTED_FACT_SQL =
      "SELECT CASE WHEN EXISTS (SELECT 1 FROM fact_acct f WHERE f.ad_client_id = :clientId)"
          + " THEN 1 ELSE 0 END";

  /**
   * Every foreign key pointing at a period or a year, except the calendar's own structure, which
   * regenerating deletes ({@code C_PERIOD_TRG2} removes controls and log on period delete).
   */
  static final String CALENDAR_FOREIGN_KEYS_SQL =
      "SELECT cl.relname, a.attname, ref.relname FROM pg_constraint c"
          + " JOIN pg_class cl ON cl.oid = c.conrelid JOIN pg_class ref ON ref.oid = c.confrelid"
          + " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]"
          + " WHERE c.contype = 'f' AND ref.relname IN ('c_period', 'c_year')"
          + " AND cl.relname NOT IN ('c_period', 'c_periodcontrol', 'c_periodcontrol_log')"
          + " ORDER BY 1, 2";

  /**
   * The calendar's open/close log. {@code C_PERIOD_TRG2} deletes it only by {@code periodno}, so
   * a classic year-level row ({@code c_year_id} or {@code c_period_id} alone) would block the
   * deletes below. Only closes can be left here: any open makes {@link #anyPeriodOpened} refuse.
   */
  static final String DELETE_LOG_SQL =
      "DELETE FROM c_periodcontrol_log WHERE c_year_id IN"
          + " (SELECT c_year_id FROM c_year WHERE c_calendar_id = :calendarId)"
          + " OR c_period_id IN (" + CALENDAR_PERIODS + ") OR periodno IN (" + CALENDAR_PERIODS + ")";

  static final String DELETE_PERIODS_SQL =
      "DELETE FROM c_period WHERE c_year_id IN"
          + " (SELECT c_year_id FROM c_year WHERE c_calendar_id = :calendarId)";

  static final String DELETE_YEARS_SQL = "DELETE FROM c_year WHERE c_calendar_id = :calendarId";

  /** Why a regenerate was refused; {@code null} message means it may proceed. */
  public static final class Refusal {
    public final int status;
    public final String message;

    Refusal(int status, String message) {
      this.status = status;
      this.message = message;
    }
  }

  /**
   * The review state shown by the "Primeros pasos" row.
   *
   * @param clientId tenant
   * @param today    the caller's notion of today (the server date)
   */
  public JSONObject state(String clientId, LocalDate today) throws JSONException {
    JSONObject result = new JSONObject();
    boolean required = new OnboardingInitialPeriodStateService().isReviewRequired(clientId);
    String reviewedAt = OnboardingInitialPeriodStateService.readClientPreference(
        REVIEWED_ATTRIBUTE, clientId);
    String calendarId = calendarId(clientId);
    boolean anyPeriodOpened = calendarId != null && anyPeriodOpened(calendarId);
    boolean anyPostedFact = anyPostedFact(clientId);
    boolean reviewed = reviewedAt != null || anyPeriodOpened || anyPostedFact;
    result.put("required", required);
    result.put("reviewed", reviewed);
    result.put("pending", required && !reviewed);
    // The preference holds "<instant> <userId>"; only the instant is part of the answer.
    result.put("reviewedAt", reviewedAt == null ? JSONObject.NULL
        : StringUtils.substringBefore(reviewedAt, " "));
    result.put("anyPeriodOpened", anyPeriodOpened);
    result.put("anyPostedFact", anyPostedFact);
    result.put("canRegenerate", calendarId != null && !anyPeriodOpened && !anyPostedFact);
    putCurrentYear(result, calendarId, today);
    return result;
  }

  private void putCurrentYear(JSONObject result, String calendarId, LocalDate today)
      throws JSONException {
    Object[] year = calendarId == null ? null : currentYear(calendarId, today);
    if (year == null) {
      result.put("year", JSONObject.NULL);
      return;
    }
    LocalDate start = toLocalDate(year[1]);
    result.put("year", String.valueOf(year[0]));
    result.put("startDate", start.toString());
    result.put("endDate", toLocalDate(year[2]).toString());
    result.put("periodCount", ((Number) year[3]).intValue());
    Object range = rangeOf(start.getMonthValue());
    result.put("fiscalYearStart", range == null ? JSONObject.NULL : range);
  }

  /** Records the owner's confirmation. Nothing is opened: the owner opens periods in Calendar. */
  public void confirm(String clientId, String userId) {
    markReviewed(clientId, userId);
  }

  /**
   * Replaces the whole calendar with one fiscal year containing {@code today} that starts in the
   * given month, then marks the tenant reviewed. Allowed only while nothing depends on the
   * calendar: no period was ever opened, nothing was posted, and no row of any other table points
   * at its periods or years.
   *
   * @return a refusal, or {@code null} when the calendar was regenerated
   */
  public Refusal regenerate(String clientId, String fiscalYearStart, String userId,
      LocalDate today) {
    Integer startMonth = FISCAL_YEAR_STARTS.get(StringUtils.upperCase(fiscalYearStart));
    if (startMonth == null) {
      return new Refusal(400, "fiscalYearStart must be one of " + FISCAL_YEAR_STARTS.keySet());
    }
    String calendarId = calendarId(clientId);
    if (calendarId == null) {
      return new Refusal(409, "The environment has no accounting calendar");
    }
    if (anyPeriodOpened(calendarId)) {
      return new Refusal(409, "A period of this calendar was already opened");
    }
    if (anyPostedFact(clientId)) {
      return new Refusal(409, "The environment already has accounting entries");
    }
    String reference = firstReferencingTable(calendarId);
    if (reference != null) {
      return new Refusal(409, "The calendar is already in use (" + reference + ")");
    }
    Calendar calendar = OBDal.getInstance().get(Calendar.class, calendarId);
    executeUpdate(DELETE_LOG_SQL, calendarId);
    executeUpdate(DELETE_PERIODS_SQL, calendarId);
    executeUpdate(DELETE_YEARS_SQL, calendarId);
    int fiscalYear = today.getMonthValue() >= startMonth ? today.getYear() : today.getYear() - 1;
    Year year = OBProvider.getInstance().get(Year.class);
    year.setNewOBObject(true);
    year.setClient(calendar.getClient());
    year.setOrganization(calendar.getOrganization());
    year.setActive(true);
    year.setCalendar(calendar);
    year.setFiscalYear(String.valueOf(fiscalYear));
    OBDal.getInstance().save(year);
    for (int periodNo = 1; periodNo <= 12; periodNo++) {
      FiscalCalendarSupport.createPeriod(year, periodNo,
          LocalDate.of(fiscalYear, startMonth, 1).plusMonths(periodNo - 1L), "S");
    }
    FiscalCalendarSupport.normalizePeriodControls(calendarId);
    markReviewed(clientId, userId);
    log.info("ETP-5577 regenerated calendar {} of client {}: fiscal year {} starting {}",
        calendarId, clientId, fiscalYear, fiscalYearStart);
    return null;
  }

  private void markReviewed(String clientId, String userId) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Preferences.setPreferenceValue(REVIEWED_ATTRIBUTE, Instant.now() + " " + userId, false,
        client, null, null, null, null, null);
    OBDal.getInstance().flush();
  }

  String calendarId(String clientId) {
    List<?> rows = nativeQuery(CALENDAR_SQL).setParameter(PARAM_CLIENT_ID, clientId).list();
    return rows.isEmpty() ? null : (String) rows.get(0);
  }

  boolean anyPeriodOpened(String calendarId) {
    return flag(ANY_PERIOD_OPENED_SQL, PARAM_CALENDAR_ID, calendarId);
  }

  boolean anyPostedFact(String clientId) {
    return flag(ANY_POSTED_FACT_SQL, PARAM_CLIENT_ID, clientId);
  }

  private Object[] currentYear(String calendarId, LocalDate today) {
    List<?> rows = nativeQuery(CURRENT_YEAR_SQL)
        .setParameter(PARAM_CALENDAR_ID, calendarId)
        .setParameter(PARAM_TODAY, Date.from(today.atStartOfDay(ZoneId.systemDefault())
            .toInstant()))
        .list();
    return rows.isEmpty() ? null : (Object[]) rows.get(0);
  }

  /**
   * The first {@code table.column} with a row pointing at a period or year of the calendar, or
   * {@code null}. Table and column names come from the catalog, never from the request.
   */
  String firstReferencingTable(String calendarId) {
    List<?> keys = nativeQuery(CALENDAR_FOREIGN_KEYS_SQL).list();
    for (Object key : keys) {
      Object[] row = (Object[]) key;
      String table = (String) row[0];
      String column = (String) row[1];
      String owners = "c_year".equals(row[2])
          ? "SELECT c_year_id FROM c_year WHERE c_calendar_id = :calendarId"
          : CALENDAR_PERIODS;
      String sql = "SELECT CASE WHEN EXISTS (SELECT 1 FROM " + table + " WHERE " + column
          + " IN (" + owners + ")) THEN 1 ELSE 0 END";
      if (flag(sql, PARAM_CALENDAR_ID, calendarId)) {
        return table + "." + column;
      }
    }
    return null;
  }

  private static boolean flag(String sql, String parameter, String value) {
    Object result = nativeQuery(sql).setParameter(parameter, value).uniqueResult();
    return result instanceof Number && ((Number) result).intValue() == 1;
  }

  private static void executeUpdate(String sql, String calendarId) {
    nativeQuery(sql).setParameter(PARAM_CALENDAR_ID, calendarId).executeUpdate();
  }

  @SuppressWarnings("unchecked")
  private static NativeQuery<Object> nativeQuery(String sql) {
    OBDal.getInstance().flush();
    return OBDal.getInstance().getSession().createNativeQuery(sql);
  }

  private static LocalDate toLocalDate(Object value) {
    return ((Date) value).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
  }

  private static Object rangeOf(int month) {
    return FISCAL_YEAR_STARTS.entrySet().stream().filter(e -> e.getValue() == month)
        .map(Map.Entry::getKey).findFirst().orElse(null);
  }
}
