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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * ETP-5577 — regenerating a tenant's accounting calendar: refused while anything depends on it,
 * otherwise replaced by one fiscal year containing today. The SQL behind the guards was validated
 * against a local tenant; here the decisions and the order of the writes are covered.
 *
 * @covers com.etendoerp.go.onboarding.CalendarReviewService
 */
class CalendarReviewServiceTest {

  private static final LocalDate OCT_14 = LocalDate.of(2026, 10, 14);

  private CalendarReviewService service;
  private OBDal dal;
  private Session session;
  private NativeQuery<Object> query;
  private Year year;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBProvider> obProvider;
  private MockedStatic<Preferences> prefs;
  private MockedStatic<FiscalCalendarSupport> support;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service = spy(new CalendarReviewService());
    dal = mock(OBDal.class);
    session = mock(Session.class);
    query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(dal.get(Calendar.class, "CAL")).thenReturn(mock(Calendar.class));
    when(dal.get(Client.class, "CLIENT")).thenReturn(mock(Client.class));
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    OBProvider provider = mock(OBProvider.class);
    year = mock(Year.class);
    when(provider.get(Year.class)).thenReturn(year);
    obProvider = mockStatic(OBProvider.class);
    obProvider.when(OBProvider::getInstance).thenReturn(provider);
    prefs = mockStatic(Preferences.class);
    support = mockStatic(FiscalCalendarSupport.class);
    doReturn("CAL").when(service).calendarId("CLIENT");
    doReturn(false).when(service).anyPeriodOpened("CAL");
    doReturn(false).when(service).anyPostedFact("CLIENT");
    doReturn(null).when(service).firstReferencingTable("CAL");
  }

  @AfterEach
  void tearDown() {
    support.close();
    prefs.close();
    obProvider.close();
    obDal.close();
  }

  @Test
  void rejectsAnUnknownFiscalYearStart() {
    assertEquals(400, service.regenerate("CLIENT", "MARCH", "USER", OCT_14).status);
    verifyNothingDeleted();
  }

  @Test
  void refusesOnceAPeriodWasOpened() {
    doReturn(true).when(service).anyPeriodOpened("CAL");
    assertEquals(409, service.regenerate("CLIENT", "JULY", "USER", OCT_14).status);
    verifyNothingDeleted();
  }

  @Test
  void refusesOnceAnythingWasPosted() {
    doReturn(true).when(service).anyPostedFact("CLIENT");
    assertEquals(409, service.regenerate("CLIENT", "JULY", "USER", OCT_14).status);
    verifyNothingDeleted();
  }

  @Test
  void refusesWhileAnotherTableReferencesTheCalendar() {
    doReturn("gl_journalbatch.c_period_id").when(service).firstReferencingTable("CAL");
    CalendarReviewService.Refusal refusal = service.regenerate("CLIENT", "JULY", "USER", OCT_14);
    assertEquals(409, refusal.status);
    assertEquals("The calendar is already in use (gl_journalbatch.c_period_id)", refusal.message);
    verifyNothingDeleted();
  }

  @Test
  void replacesTheCalendarWithTheFiscalYearContainingToday() {
    assertNull(service.regenerate("CLIENT", "july", "USER", OCT_14));

    InOrder order = inOrder(session, dal);
    order.verify(session).createNativeQuery(CalendarReviewService.DELETE_LOG_SQL);
    order.verify(session).createNativeQuery(CalendarReviewService.DELETE_PERIODS_SQL);
    order.verify(session).createNativeQuery(CalendarReviewService.DELETE_YEARS_SQL);
    order.verify(dal).save(year);
    verify(year).setFiscalYear("2026");
    support.verify(() -> FiscalCalendarSupport.createPeriod(year, 1, LocalDate.of(2026, 7, 1), "S"));
    support.verify(() -> FiscalCalendarSupport.createPeriod(year, 12, LocalDate.of(2027, 6, 1), "S"));
    support.verify(() -> FiscalCalendarSupport.normalizePeriodControls("CAL"));
    prefs.verify(() -> Preferences.setPreferenceValue(eq(CalendarReviewService.REVIEWED_ATTRIBUTE),
        anyString(), eq(false), any(Client.class), any(), any(), any(), any(), any()));
  }

  @Test
  void aStartLaterInTheYearThanTodayBelongsToThePreviousYear() {
    service.regenerate("CLIENT", "OCTOBER", "USER", LocalDate.of(2026, 9, 30));

    verify(year).setFiscalYear("2025");
    support.verify(() -> FiscalCalendarSupport.createPeriod(year, 1, LocalDate.of(2025, 10, 1), "S"));
  }

  private void verifyNothingDeleted() {
    verify(session, never()).createNativeQuery(CalendarReviewService.DELETE_PERIODS_SQL);
    verify(session, never()).createNativeQuery(CalendarReviewService.DELETE_YEARS_SQL);
  }

  /** The row is pending until reviewed; {@code reviewedAt} carries the instant, not the user. */
  @Test
  void stateIsPendingUntilReviewedAndAnswersOnlyTheReviewInstant() throws Exception {
    when(query.list()).thenReturn(List.of());
    try (MockedStatic<OnboardingInitialPeriodStateService> markers =
        mockStatic(OnboardingInitialPeriodStateService.class)) {
      markers.when(() -> OnboardingInitialPeriodStateService.readClientPreference(
          OnboardingInitialPeriodStateService.REVIEW_REQUIRED_ATTRIBUTE, "CLIENT")).thenReturn("Y");

      JSONObject pending = service.state("CLIENT", OCT_14);
      assertTrue(pending.getBoolean("pending"));
      assertTrue(pending.getBoolean("canRegenerate"));

      markers.when(() -> OnboardingInitialPeriodStateService.readClientPreference(
          CalendarReviewService.REVIEWED_ATTRIBUTE, "CLIENT"))
          .thenReturn("2026-10-14T10:00:00Z USER");
      JSONObject reviewed = service.state("CLIENT", OCT_14);
      assertFalse(reviewed.getBoolean("pending"));
      assertEquals("2026-10-14T10:00:00Z", reviewed.getString("reviewedAt"));
    }
  }
}
