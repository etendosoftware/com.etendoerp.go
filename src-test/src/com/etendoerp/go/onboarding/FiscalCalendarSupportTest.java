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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.PersistenceException;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * ETP-5577 — the shared fiscal calendar building blocks. The normalization SQL itself was
 * validated against a duplicated tenant (1032 → 516 rows, re-run no-op, missing rows re-inserted,
 * conflicting groups kept); here the Java orchestration is covered.
 *
 * @covers com.etendoerp.go.onboarding.FiscalCalendarSupport
 */
class FiscalCalendarSupportTest {

  private OBDal dal;
  private OBProvider provider;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBProvider> obProvider;
  private OBCriteria<Year> yearCriteria;
  private OBCriteria<Period> periodCriteria;
  private final List<Period> createdPeriods = new ArrayList<>();
  private final Calendar calendar = mock(Calendar.class);
  private final Connection connection = mock(Connection.class);
  private final Savepoint savepoint = mock(Savepoint.class);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    provider = mock(OBProvider.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    obProvider = mockStatic(OBProvider.class);
    obProvider.when(OBProvider::getInstance).thenReturn(provider);
    yearCriteria = mock(OBCriteria.class);
    periodCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(Year.class)).thenReturn(yearCriteria);
    when(dal.createCriteria(Period.class)).thenReturn(periodCriteria);
    when(provider.get(Year.class)).thenAnswer(inv -> mock(Year.class));
    when(provider.get(Period.class)).thenAnswer(inv -> {
      Period period = mock(Period.class);
      createdPeriods.add(period);
      return period;
    });
  }

  @AfterEach
  void tearDown() {
    obProvider.close();
    obDal.close();
  }

  @Test
  void createsAMissingYearWithTwelveJanuaryToDecemberPeriods() {
    assertTrue(FiscalCalendarSupport.ensureYear(calendar, 2027));

    verify(dal).save(any(Year.class));
    assertEquals(12, createdPeriods.size());
    verify(createdPeriods.get(0)).setName("Jan-27");
    verify(createdPeriods.get(11)).setName("Dec-27");
    verify(createdPeriods.get(0)).setPeriodType("S");
  }

  @Test
  void leavesAnExistingYearWithPeriodsAlone() {
    when(yearCriteria.uniqueResult()).thenReturn(mock(Year.class));
    when(periodCriteria.uniqueResult()).thenReturn(mock(Period.class));

    assertFalse(FiscalCalendarSupport.ensureYear(calendar, 2026));

    verify(dal, never()).save(any());
  }

  @Test
  void fillsAnExistingYearThatHasNoPeriods() {
    when(yearCriteria.uniqueResult()).thenReturn(mock(Year.class));

    assertTrue(FiscalCalendarSupport.ensureYear(calendar, 2026));

    verify(dal, never()).save(any(Year.class));
    assertEquals(12, createdPeriods.size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void normalizeFlushesFirstThenRunsEveryStatementOnTheCalendar() {
    Session session = mock(Session.class);
    NativeQuery<Object> query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(0L);

    FiscalCalendarSupport.normalizePeriodControls("CAL-1");

    InOrder order = Mockito.inOrder(dal, session);
    order.verify(dal).flush();
    order.verify(session).createNativeQuery(FiscalCalendarSupport.INSERT_MISSING_SQL);
    order.verify(session).createNativeQuery(FiscalCalendarSupport.DELETE_SURPLUS_SQL);
    order.verify(session).createNativeQuery(FiscalCalendarSupport.COUNT_CONFLICTS_SQL);
    order.verify(session).createNativeQuery(FiscalCalendarSupport.RECOMPUTE_OPENCLOSE_SQL);
    verify(query, times(4)).setParameter("calendarId", "CAL-1");
  }

  @Test
  void removeUnusedYearsFlushesThenDeletesPeriodsBeforeYearsKeepingTheRange() throws Exception {
    NativeQuery<Object> query = stubNativeQueries();
    when(query.executeUpdate()).thenReturn(1);

    assertEquals(1, FiscalCalendarSupport.removeUnusedYearsOutside("CAL-1", 2026, 2027));

    Session session = dal.getSession();
    InOrder order = Mockito.inOrder(dal, session);
    order.verify(dal).flush();
    order.verify(session).createNativeQuery(FiscalCalendarSupport.DELETE_UNUSED_PERIODS_SQL);
    order.verify(session).createNativeQuery(FiscalCalendarSupport.DELETE_UNUSED_YEARS_SQL);
    verify(query, times(2)).setParameterList("keepYears", List.of("2026", "2027"));
    verify(query, times(2)).setParameter("calendarId", "CAL-1");
    verify(connection, never()).rollback(any(Savepoint.class));
  }

  @Test
  void removeUnusedYearsScopesToTheCalendarKeepsTheRangeAndOpenedYears() {
    for (String sql : List.of(FiscalCalendarSupport.DELETE_UNUSED_PERIODS_SQL,
        FiscalCalendarSupport.DELETE_UNUSED_YEARS_SQL)) {
      assertTrue(sql.contains("y.c_calendar_id = :calendarId"));
      assertTrue(sql.contains("y.year NOT IN (:keepYears)"));
      assertTrue(sql.contains("c.periodstatus <> 'N'"));
    }
  }

  @Test
  void removeUnusedYearsRollsBackToTheSavepointAndKeepsTheYearsWhenADeleteIsBlocked()
      throws Exception {
    NativeQuery<Object> query = stubNativeQueries();
    when(query.executeUpdate()).thenThrow(new PersistenceException("fk_violation"));

    assertEquals(0, FiscalCalendarSupport.removeUnusedYearsOutside("CAL-1", 2027, 2027));

    verify(connection).rollback(savepoint);
  }

  @SuppressWarnings("unchecked")
  private NativeQuery<Object> stubNativeQueries() throws Exception {
    Session session = mock(Session.class);
    NativeQuery<Object> query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.getConnection(false)).thenReturn(connection);
    when(connection.setSavepoint()).thenReturn(savepoint);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.setParameterList(anyString(), any(java.util.Collection.class))).thenReturn(query);
    return query;
  }
}
