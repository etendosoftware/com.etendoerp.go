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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * Handles the Calendar-only fiscal-year range extension of the core Create Periods process.
 * January-December remains the unmodified process-100 path; this handler creates July-June
 * periods directly, letting the core C_PERIOD_TRG initialize period-control records.
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
  private static final String RANGE_JULY = "JULY";

  boolean handles(NeoContext context) {
    return context != null && context.getEndpointType() == NeoEndpointType.ACTION
        && ACTION_CREATE_PERIODS.equals(context.getFieldName())
        && SPEC_FISCAL_CALENDAR.equals(context.getSpecName())
        && ENTITY_YEAR.equals(context.getEntityName());
  }

  NeoResponse handle(NeoContext context) {
    org.codehaus.jettison.json.JSONObject values = context.getRequestBody() != null
        ? context.getRequestBody().optJSONObject(FIELD_VALUES) : null;
    String range = values != null
        ? values.optString(FIELD_FISCAL_YEAR_START, RANGE_JANUARY) : RANGE_JANUARY;
    if (!RANGE_JANUARY.equals(range) && !RANGE_JULY.equals(range)) {
      return NeoResponse.error(400, "Fiscal Year Range must be January - December or July - June");
    }
    if (RANGE_JANUARY.equals(range)) {
      if (values != null) {
        values.remove(FIELD_FISCAL_YEAR_START);
      }
      return null;
    }
    return createJulyToJunePeriods(context, values);
  }

  private NeoResponse createJulyToJunePeriods(NeoContext context,
      org.codehaus.jettison.json.JSONObject values) {
    try {
      Year year = OBDal.getInstance().get(Year.class, context.getRecordId());
      if (year == null) {
        return NeoResponse.error(404, "Year not found: " + context.getRecordId());
      }
      if (hasPeriods(year)) {
        return NeoResponse.error(409, "Periods already exist for this fiscal year");
      }
      int fiscalYear = Integer.parseInt(year.getFiscalYear());
      for (int periodNo = 1; periodNo <= 12; periodNo++) {
        createPeriod(year, periodNo, LocalDate.of(fiscalYear, 7, 1).plusMonths(periodNo - 1L), "S");
      }
      if (values != null && "Y".equals(values.optString(FIELD_CREATE_ADJUSTMENT))) {
        createPeriod(year, 13, LocalDate.of(fiscalYear + 1, 6, 30), "A");
      }
      OBDal.getInstance().flush();
      return NeoResponse.ok(new org.codehaus.jettison.json.JSONObject()
          .put("status", "success").put("message", "Periods created successfully"));
    } catch (Exception e) {
      log.error("Error creating July-June periods for year {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Could not create July-June periods: " + e.getMessage());
    }
  }

  private boolean hasPeriods(Year year) {
    OBCriteria<Period> criteria = OBDal.getInstance().createCriteria(Period.class);
    criteria.add(Restrictions.eq(Period.PROPERTY_YEAR, year));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  private void createPeriod(Year year, int periodNo, LocalDate start, String periodType) {
    Period period = OBProvider.getInstance().get(Period.class);
    period.setNewOBObject(true);
    period.setClient(year.getClient());
    period.setOrganization(year.getOrganization());
    period.setActive(true);
    period.setYear(year);
    period.setPeriodNo((long) periodNo);
    period.setStartingDate(Date.from(start.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    period.setName("A".equals(periodType) ? "13th Period - " + start.format(DateTimeFormatter.ofPattern("yy"))
        : start.format(DateTimeFormatter.ofPattern("MMM-yy", Locale.ENGLISH)));
    period.setPeriodType(periodType);
    OBDal.getInstance().save(period);
  }
}
