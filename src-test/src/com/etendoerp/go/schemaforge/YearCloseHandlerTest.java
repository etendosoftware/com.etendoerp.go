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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_actionButton.CreateRegFactAcct;
import org.openbravo.erpCommon.ad_actionButton.DropRegFactAcct;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.Year;
import org.openbravo.service.db.DalConnectionProvider;

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

  /**
   * ETP-4948 REVIEW fix: no direct calendar on the current org is NOT by itself grounds for
   * rejection any more — {@link org.openbravo.erpCommon.utility.AccDefUtility#getCalendar} walks
   * up the org tree. This test asserts the genuinely-no-calendar-anywhere case: every ancestor,
   * all the way up to the {@code *} org (id {@code "0"}), also has no calendar assigned.
   */
  @Test
  public void fiscalCalendarCreateRejectsMissingOrganizationCalendar() throws Exception {
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("fiscalYear", "2026");

    Organization starOrg = mock(Organization.class);
    when(starOrg.getId()).thenReturn("0");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedConstruction<OrganizationStructureProvider> ospMock = mockConstruction(
             OrganizationStructureProvider.class,
             (mock, mockContext) -> when(mock.getParentOrg(any(Organization.class))).thenReturn(starOrg))) {
      OBContext obContext = mock(OBContext.class);
      Organization organization = mock(Organization.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(organization);
      when(organization.getId()).thenReturn("org-with-no-calendar-in-its-whole-tree");
      when(organization.getCalendar()).thenReturn(null);

      NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

      assertEquals(400, response.getHttpStatus());
    }
  }

  /**
   * ETP-4948 REVIEW fix: the gap the previous test suite didn't cover — an org with no DIRECTLY
   * assigned calendar (the exact AD_Org.AD_InheritedCalendar_ID scenario) must still resolve
   * successfully, using the nearest ancestor's calendar, instead of being rejected.
   */
  @Test
  public void fiscalCalendarCreateInjectsInheritedCalendarFromAncestorOrg() throws Exception {
    org.codehaus.jettison.json.JSONObject body = new org.codehaus.jettison.json.JSONObject()
        .put("fiscalYear", "2027");

    Calendar ancestorCalendar = mock(Calendar.class);
    when(ancestorCalendar.getId()).thenReturn("ancestor-calendar-id");
    Organization parentOrg = mock(Organization.class);
    when(parentOrg.getId()).thenReturn("parent-org-id");
    when(parentOrg.getCalendar()).thenReturn(ancestorCalendar);

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedConstruction<OrganizationStructureProvider> ospMock = mockConstruction(
             OrganizationStructureProvider.class,
             (mock, mockContext) -> when(mock.getParentOrg(any(Organization.class))).thenReturn(parentOrg))) {
      OBContext obContext = mock(OBContext.class);
      Organization organization = mock(Organization.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(organization);
      // The org itself has no directly-assigned calendar (getCalendar() == null) — the exact
      // "inherits from a parent org" setup AD_Org.AD_InheritedCalendar_ID exists for.
      when(organization.getId()).thenReturn("child-org-with-no-direct-calendar");
      when(organization.getCalendar()).thenReturn(null);

      NeoResponse response = new YearCloseHandler().handle(buildFiscalCalendarCreate(body));

      assertNull(response);
      assertEquals("ancestor-calendar-id", body.getString("calendar"));
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

  /**
   * ETP-4948 QA finding: {@code isFiscalCalendarCreate} requires {@code recordId == null}, so
   * {@link YearCloseHandler#validateAndEnrichFiscalCalendarCreate} — the only place Issue 5's
   * fiscal-year format/range check lives — never runs for an UPDATE. This is the same
   * originally-reported symptom ("asd" accepted as a Fiscal Year) still reproducible by editing
   * an existing, previously-valid year rather than creating a new one: {@code decisions.json}
   * declares no {@code readOnlyLogic} for {@code fiscalYear}, so the field stays editable in the
   * UI after save with no client-side format guard either. Documents the current gap (an update
   * with garbage input falls through to default CRUD, same as {@link
   * #fiscalCalendarUpdateFallsThrough}) rather than fixing it — QA reports, DEV fixes.
   */
  @Test
  public void fiscalCalendarUpdateWithNonNumericFiscalYearFallsThrough() throws Exception {
    NeoContext context = NeoContext.builder()
        .specName("fiscal-calendar").entityName("year")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .recordId(YEAR_ID)
        .requestBody(new org.codehaus.jettison.json.JSONObject().put("fiscalYear", "asd"))
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

  // --- Reflection-mechanics contract tests (ETP-4948 REVIEW W2) ---------------------------
  //
  // invokeCreateRegFactAcct/invokeDropRegFactAcct are only ever exercised above with both
  // methods overridden to return a canned OBError, so newServletInstance, findMyPoolField and
  // the real Method.invoke(...) call never actually run in this test suite. These tests resolve
  // (but deliberately never invoke) the exact reflective handles the handler depends on, plus
  // exercise the real (private) newServletInstance/findMyPoolField mechanics — with no DB
  // access and no call into the legacy servlets' business logic — so a future Etendo core
  // version that renames/reshapes CreateRegFactAcct#processButton, DropRegFactAcct#processButton
  // or HttpBaseServlet#myPool breaks the build here instead of failing silently at runtime.

  @Test
  public void createRegFactAcctProcessButtonSignatureIsResolvable() throws Exception {
    Method processButton = CreateRegFactAcct.class.getDeclaredMethod("processButton",
        VariablesSecureApp.class, String.class, String.class, String.class);

    assertEquals(OBError.class, processButton.getReturnType());
  }

  @Test
  public void dropRegFactAcctProcessButtonSignatureIsResolvable() throws Exception {
    Method processButton = DropRegFactAcct.class.getDeclaredMethod("processButton",
        VariablesSecureApp.class, String.class, String.class);

    assertEquals(OBError.class, processButton.getReturnType());
  }

  @Test
  public void myPoolFieldIsResolvableOnBothLegacyServlets() throws Exception {
    assertNotNull(findMyPoolFieldViaReflection(CreateRegFactAcct.class));
    assertNotNull(findMyPoolFieldViaReflection(DropRegFactAcct.class));
  }

  @Test
  public void newServletInstanceSetsMyPoolWithoutInvokingProcessButton() throws Exception {
    Method newServletInstance = YearCloseHandler.class.getDeclaredMethod("newServletInstance", Class.class);
    newServletInstance.setAccessible(true);

    CreateRegFactAcct servlet = (CreateRegFactAcct) newServletInstance.invoke(
        new YearCloseHandler(), CreateRegFactAcct.class);

    Field poolField = findMyPoolFieldViaReflection(CreateRegFactAcct.class);
    poolField.setAccessible(true);
    assertTrue(poolField.get(servlet) instanceof DalConnectionProvider);
  }

  /** Mirrors YearCloseHandler#findMyPoolField's own walk-the-superclass-chain logic. */
  private Field findMyPoolFieldViaReflection(Class<?> clazz) throws NoSuchFieldException {
    Class<?> current = clazz;
    while (current != null) {
      try {
        return current.getDeclaredField("myPool");
      } catch (NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException("myPool not found on " + clazz.getName());
  }
}
