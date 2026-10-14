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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.calendar.Year;

import com.etendoerp.go.onboarding.FiscalCalendarSupport;

/**
 * Handles the Calendar-only fiscal-year range extension of the core Create Periods process.
 * January-December remains the unmodified process-100 path; this handler creates
 * April-March, July-June and October-September periods directly, letting the core
 * C_PERIOD_TRG initialize period-control records.
 */
final class FiscalYearPeriodsHandler {

  private static final Logger log = LogManager.getLogger(FiscalYearPeriodsHandler.class);
  private static final String SPEC_FISCAL_CALENDAR = "fiscal-calendar";
  private static final String ENTITY_YEAR = "year";
  private static final String ACTION_CREATE_PERIODS = "processNow";
  private static final String FIELD_VALUES = "fieldValues";
  private static final String FIELD_FISCAL_YEAR_START = "FISCALYEARSTART";
  private static final String FIELD_CREATE_ADJUSTMENT = "CREATEADJUSTMENT";
  private static final String RANGE_JANUARY = "JANUARY";
  private static final String RANGE_APRIL = "APRIL";
  private static final String RANGE_JULY = "JULY";
  private static final String RANGE_OCTOBER = "OCTOBER";
  private static final Map<String, Integer> START_MONTHS = Map.of(
      RANGE_JANUARY, 1,
      RANGE_APRIL, 4,
      RANGE_JULY, 7,
      RANGE_OCTOBER, 10);

  boolean handles(NeoContext context) {
    return context != null && context.getEndpointType() == NeoEndpointType.ACTION
        && ACTION_CREATE_PERIODS.equals(context.getFieldName())
        && SPEC_FISCAL_CALENDAR.equals(context.getSpecName())
        && ENTITY_YEAR.equals(context.getEntityName());
  }

  NeoResponse handle(NeoContext context) {
    org.codehaus.jettison.json.JSONObject values = context.getRequestBody() != null
        ? context.getRequestBody().optJSONObject(FIELD_VALUES) : null;
    String requested = values != null ? values.optString(FIELD_FISCAL_YEAR_START, "") : "";
    if (!requested.isEmpty() && !START_MONTHS.containsKey(requested)) {
      return NeoResponse.error(400, "Fiscal Year Range must be one of: January - December, "
          + "April - March, July - June, October - September");
    }
    Year year = context.getRecordId() != null
        ? OBDal.getInstance().get(Year.class, context.getRecordId()) : null;
    // ETP-5577 (H1): with no explicit choice, keep the calendar's fiscal-year start rather than
    // January, or a July calendar following the tutorial would get an overlapping Jan-Dec year.
    String range = requested.isEmpty() ? defaultRange(year) : requested;
    int startMonth = START_MONTHS.get(range);
    if (year != null && overlapsOtherYears(year, startMonth)) {
      return NeoResponse.error(409, "These periods would overlap another fiscal year of the"
          + " calendar. Use the same fiscal year start as the other years.");
    }
    if (RANGE_JANUARY.equals(range)) {
      if (values != null) {
        values.remove(FIELD_FISCAL_YEAR_START);
      }
      return null;
    }
    return createShiftedFiscalPeriods(context, values, startMonth);
  }

  private static String defaultRange(Year year) {
    Integer month = year != null && year.getCalendar() != null
        ? FiscalCalendarSupport.latestStartMonth(year) : null;
    return START_MONTHS.entrySet().stream()
        .filter(entry -> month != null && entry.getValue().equals(month))
        .map(Map.Entry::getKey).findFirst().orElse(RANGE_JANUARY);
  }

  private static boolean overlapsOtherYears(Year year, int startMonth) {
    if (year.getCalendar() == null) {
      return false;
    }
    int fiscalYear;
    try {
      fiscalYear = Integer.parseInt(year.getFiscalYear());
    } catch (NumberFormatException e) {
      return false;
    }
    return FiscalCalendarSupport.overlapsOtherYears(year, LocalDate.of(fiscalYear, startMonth, 1));
  }

  private NeoResponse createShiftedFiscalPeriods(NeoContext context,
      org.codehaus.jettison.json.JSONObject values, int startMonth) {
    try {
      Year year = OBDal.getInstance().get(Year.class, context.getRecordId());
      if (year == null) {
        return NeoResponse.error(404, "Year not found: " + context.getRecordId());
      }
      if (FiscalCalendarSupport.hasPeriods(year)) {
        return NeoResponse.error(409, "Periods already exist for this fiscal year");
      }
      int fiscalYear = Integer.parseInt(year.getFiscalYear());
      for (int periodNo = 1; periodNo <= 12; periodNo++) {
        FiscalCalendarSupport.createPeriod(year, periodNo,
            LocalDate.of(fiscalYear, startMonth, 1).plusMonths(periodNo - 1L), "S");
      }
      if (values != null && "Y".equals(values.optString(FIELD_CREATE_ADJUSTMENT))) {
        LocalDate adjustmentDate = YearMonth.of(fiscalYear + 1, startMonth - 1).atEndOfMonth();
        FiscalCalendarSupport.createPeriod(year, 13, adjustmentDate, "A");
      }
      OBDal.getInstance().flush();
      return NeoResponse.ok(new org.codehaus.jettison.json.JSONObject()
          .put("status", "success").put("message", "Periods created successfully"));
    } catch (Exception e) {
      log.error("Error creating shifted fiscal periods for year {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Could not create shifted fiscal periods: " + e.getMessage());
    }
  }
}
