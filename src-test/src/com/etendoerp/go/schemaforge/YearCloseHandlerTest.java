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
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * Unit tests for {@link YearCloseHandler}.
 *
 * <p>Pure Mockito style (no {@code OBBaseTest}, no real DB) — matching {@link
 * PeriodOpenCloseHandlerTest}'s actual convention in this module, not the plan's tentative
 * OBBaseTest-with-real-fixtures sketch. The two reflective invocation methods ({@code
 * invokeCreateRegFactAcct}/{@code invokeDropRegFactAcct}) are overridden with canned {@link
 * OBError} results instead of exercised for real: the real path invokes legacy servlet business
 * logic that creates real Fact_Acct/accounting entries, and this module has no established
 * safe-rollback convention for that (see Task 7's finding — the one existing {@code OBBaseTest}
 * in this module needs a manual {@code rollbackAndClose()} and is {@code @Ignore}d for flakiness).
 */
public class YearCloseHandlerTest {

  private static final String YEAR_ID = "year-id-1";

  private NeoContext buildContext(String action) {
    return NeoContext.builder()
        .specName("calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(action).recordId(YEAR_ID).build();
  }

  private NeoContext buildFiscalCalendarCreate(org.codehaus.jettison.json.JSONObject body) {
    return NeoContext.builder()
        .specName("fiscal-calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .recordId(null).requestBody(body).build();
  }

  private Period periodWithStatus(String openClose) {
    Period period = mock(Period.class);
    when(period.getOpenClose()).thenReturn(openClose);
    return period;
  }

  @Test
  public void rejectsCloseYearWhenAPeriodIsStillOpen() throws Exception {
    YearCloseHandler handler = new YearCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Year year = mock(Year.class);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);

      OBCriteria<Period> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      List<Period> periods = Arrays.asList(periodWithStatus("O"), periodWithStatus("C"));
      when(criteria.list()).thenReturn(periods);

      NeoResponse r = handler.handle(buildContext(YearCloseHandler.ACTION_CLOSE_YEAR));

      assertEquals(409, r.getHttpStatus());
      assertTrue(r.getBody().getJSONObject("error").getString("message").toLowerCase()
          .contains("period"));
    }
  }

  @Test
  public void rejectsCloseYearWhenYearHasNoPeriods() throws Exception {
    YearCloseHandler handler = new YearCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Year year = mock(Year.class);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);

      OBCriteria<Period> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      NeoResponse r = handler.handle(buildContext(YearCloseHandler.ACTION_CLOSE_YEAR));

      assertEquals(409, r.getHttpStatus());
    }
  }

  @Test
  public void closesYearWhenAllPeriodsAreClosedOrPermanentlyClosed() throws Exception {
    OBError successResult = new OBError();
    successResult.setType("Success");
    successResult.setMessage("Success");

    YearCloseHandler handler = new YearCloseHandler() {
      @Override
      OBError invokeCreateRegFactAcct(Year year) {
        return successResult;
      }
    };

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Year year = mock(Year.class);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);

      OBCriteria<Period> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      List<Period> periods = Arrays.asList(periodWithStatus("C"), periodWithStatus("P"));
      when(criteria.list()).thenReturn(periods);

      NeoResponse r = handler.handle(buildContext(YearCloseHandler.ACTION_CLOSE_YEAR));

      assertEquals(200, r.getHttpStatus());
      assertEquals("success", r.getBody().getString("status"));
    }
  }

  @Test
  public void undoCloseYearTranslatesErrorResultTo400() throws Exception {
    OBError errorResult = new OBError();
    errorResult.setType("Error");
    errorResult.setMessage("boom");

    YearCloseHandler handler = new YearCloseHandler() {
      @Override
      OBError invokeDropRegFactAcct(Year year) {
        return errorResult;
      }
    };

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Year year = mock(Year.class);
      when(dal.get(eq(Year.class), anyString())).thenReturn(year);

      OBCriteria<Period> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Period.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      List<Period> periods = Collections.singletonList(periodWithStatus("P"));
      when(criteria.list()).thenReturn(periods);

      NeoResponse r = handler.handle(buildContext(YearCloseHandler.ACTION_UNDO_CLOSE_YEAR));

      assertEquals(400, r.getHttpStatus());
      assertEquals("boom", r.getBody().getJSONObject("error").getString("message"));
    }
  }

  @Test
  public void yearNotFoundReturns404() throws Exception {
    YearCloseHandler handler = new YearCloseHandler();
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(Year.class), anyString())).thenReturn(null);

      NeoResponse r = handler.handle(buildContext(YearCloseHandler.ACTION_CLOSE_YEAR));

      assertEquals(404, r.getHttpStatus());
    }
  }

  @Test
  public void missingRecordIdReturns400() {
    YearCloseHandler handler = new YearCloseHandler();
    NeoContext context = NeoContext.builder()
        .specName("calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(YearCloseHandler.ACTION_CLOSE_YEAR).recordId(null).build();

    NeoResponse r = handler.handle(context);

    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void blankRecordIdReturns400() {
    YearCloseHandler handler = new YearCloseHandler();
    NeoContext context = NeoContext.builder()
        .specName("calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(YearCloseHandler.ACTION_UNDO_CLOSE_YEAR).recordId("   ").build();

    NeoResponse r = handler.handle(context);

    assertEquals(400, r.getHttpStatus());
  }

  @Test
  public void nonActionEndpointFallsThrough() {
    YearCloseHandler handler = new YearCloseHandler();
    NeoContext context = NeoContext.builder()
        .specName("calendar").entityName("year")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

    NeoResponse r = handler.handle(context);

    assertNull(r);
  }

  @Test
  public void unrelatedActionFallsThrough() {
    YearCloseHandler handler = new YearCloseHandler();
    NeoResponse r = handler.handle(buildContext("processNow"));

    assertNull(r);
  }

  @Test
  public void fiscalCalendarCreateInjectsCurrentOrganizationCalendarAtLowerYearBoundary()
      throws Exception {
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("fiscalYear", "1900");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      Organization organization = mock(Organization.class);
      Calendar calendar = mock(Calendar.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(organization);
      when(organization.getCalendar()).thenReturn(calendar);
      when(calendar.getId()).thenReturn("organization-calendar-id");

      NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

      assertNull(response);
      assertEquals("organization-calendar-id", body.getString("calendar"));
    }
  }

  @Test
  public void fiscalCalendarCreateOverwritesClientSuppliedCalendarAtUpperYearBoundary()
      throws Exception {
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("fiscalYear", "2999").put("calendar", "wrong-client-calendar-id");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      Organization organization = mock(Organization.class);
      Calendar calendar = mock(Calendar.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(organization);
      when(organization.getCalendar()).thenReturn(calendar);
      when(calendar.getId()).thenReturn("organization-calendar-id");

      NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

      assertNull(response);
      assertEquals("organization-calendar-id", body.getString("calendar"));
    }
  }

  @Test
  public void fiscalCalendarCreateRejectsYearsOutsideTheSupportedFourDigitRange() throws Exception {
    for (String fiscalYear : Arrays.asList("1899", "3000", "20A6", "202", "20260")) {
      org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
          .put("fiscalYear", fiscalYear).put("calendar", "explicit-calendar-id");

      NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

      assertEquals("fiscalYear " + fiscalYear, 400, response.getHttpStatus());
    }
  }

  @Test
  public void fiscalCalendarCreateRejectsMissingFiscalYear() throws Exception {
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("calendar", "explicit-calendar-id");

    NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void fiscalCalendarCreateRejectsMissingOrganizationCalendar() throws Exception {
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("fiscalYear", "2026");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      Organization organization = mock(Organization.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(organization);
      when(organization.getCalendar()).thenReturn(null);

      NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

      assertEquals(400, response.getHttpStatus());
    }
  }

  @Test
  public void fiscalCalendarUpdateFallsThrough() throws Exception {
    NeoContext context = NeoContext.builder()
        .specName("fiscal-calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .recordId(YEAR_ID)
        .requestBody(new org.codehaus.jettison.json.JSONObject().put("fiscalYear", "1800"))
        .build();

    assertNull(new YearCloseHandler().handle(context));
  }

  @Test
  public void fiscalCalendarPutCreateFallsThrough() throws Exception {
    NeoContext context = NeoContext.builder()
        .specName("fiscal-calendar").entityName("year")
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .recordId(null)
        .requestBody(new org.codehaus.jettison.json.JSONObject().put("fiscalYear", "1800"))
        .build();

    assertNull(new YearCloseHandler().handle(context));
  }
}
