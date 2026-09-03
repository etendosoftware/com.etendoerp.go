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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;

/** Unit tests for {@link FiscalYearPeriodsHandler}. */
public class FiscalYearPeriodsHandlerTest {

  private static final String YEAR_ID = "year-id-1";

  private NeoContext buildCreatePeriodsContext(org.codehaus.jettison.json.JSONObject values)
      throws Exception {
    return NeoContext.builder()
        .specName("fiscal-calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("processNow").recordId(YEAR_ID)
        .requestBody(new org.codehaus.jettison.json.JSONObject().put("fieldValues", values)).build();
  }

  private void assertPeriodStartDate(Period period, LocalDate expected) {
    ArgumentCaptor<Date> date = ArgumentCaptor.forClass(Date.class);
    verify(period).setStartingDate(date.capture());
    assertEquals(expected, date.getValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
  }

  @Test
  public void januaryCreatePeriodsRemovesRoutingFieldAndFallsThroughToCoreProcess() throws Exception {
    org.codehaus.jettison.json.JSONObject values = new org.codehaus.jettison.json.JSONObject()
        .put("FISCALYEARSTART", "JANUARY");

    NeoResponse response = new FiscalYearPeriodsHandler().handle(buildCreatePeriodsContext(values));

    assertNull(response);
    assertTrue(!values.has("FISCALYEARSTART"));
  }

  @Test
  public void createPeriodsRejectsInvalidFiscalYearRange() throws Exception {
    NeoResponse response = new FiscalYearPeriodsHandler().handle(buildCreatePeriodsContext(
        new org.codehaus.jettison.json.JSONObject().put("FISCALYEARSTART", "APRIL")));

    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void julyCreatePeriodsCreatesTwelveConsecutiveFiscalPeriods() throws Exception {
    List<Period> periods = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      periods.add(mock(Period.class));
    }
    AtomicInteger nextPeriod = new AtomicInteger();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      OBProvider provider = mock(OBProvider.class);
      OBCriteria<Period> criteria = mock(OBCriteria.class);
      Year year = mock(Year.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);
      when(year.getFiscalYear()).thenReturn("2027");
      when(provider.get(Period.class)).thenAnswer(invocation -> periods.get(nextPeriod.getAndIncrement()));

      NeoResponse response = new FiscalYearPeriodsHandler().handle(buildCreatePeriodsContext(
          new org.codehaus.jettison.json.JSONObject().put("FISCALYEARSTART", "JULY")));

      assertEquals(200, response.getHttpStatus());
      verify(provider, times(12)).get(Period.class);
      verify(dal, times(12)).save(any(Period.class));
      for (int periodNo = 1; periodNo <= 12; periodNo++) {
        Period period = periods.get(periodNo - 1);
        LocalDate start = LocalDate.of(2027, 7, 1).plusMonths(periodNo - 1L);
        verify(period).setPeriodNo((long) periodNo);
        verify(period).setPeriodType("S");
        verify(period).setName(start.format(java.time.format.DateTimeFormatter.ofPattern("MMM-yy",
            java.util.Locale.ENGLISH)));
        assertPeriodStartDate(period, start);
      }
    }
  }

  @Test
  public void julyCreatePeriodsRejectsFiscalYearsThatAlreadyHavePeriods() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      OBCriteria<Period> criteria = mock(OBCriteria.class);
      Year year = mock(Year.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(mock(Period.class));

      NeoResponse response = new FiscalYearPeriodsHandler().handle(buildCreatePeriodsContext(
          new org.codehaus.jettison.json.JSONObject().put("FISCALYEARSTART", "JULY")));

      assertEquals(409, response.getHttpStatus());
    }
  }

  @Test
  public void julyCreatePeriodsAddsAdjustmentPeriodWhenRequested() throws Exception {
    List<Period> periods = new ArrayList<>();
    for (int index = 0; index < 13; index++) {
      periods.add(mock(Period.class));
    }
    AtomicInteger nextPeriod = new AtomicInteger();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      OBProvider provider = mock(OBProvider.class);
      OBCriteria<Period> criteria = mock(OBCriteria.class);
      Year year = mock(Year.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);
      when(year.getFiscalYear()).thenReturn("2027");
      when(provider.get(Period.class)).thenAnswer(invocation -> periods.get(nextPeriod.getAndIncrement()));

      NeoResponse response = new FiscalYearPeriodsHandler().handle(buildCreatePeriodsContext(
          new org.codehaus.jettison.json.JSONObject().put("FISCALYEARSTART", "JULY")
              .put("CREATEADJUSTMENT", "Y")));

      Period adjustmentPeriod = periods.get(12);
      assertEquals(200, response.getHttpStatus());
      verify(provider, times(13)).get(Period.class);
      verify(dal, times(13)).save(any(Period.class));
      verify(adjustmentPeriod).setPeriodNo(13L);
      verify(adjustmentPeriod).setPeriodType("A");
      verify(adjustmentPeriod).setName("13th Period - 28");
      assertPeriodStartDate(adjustmentPeriod, LocalDate.of(2028, 6, 30));
    }
  }
}
