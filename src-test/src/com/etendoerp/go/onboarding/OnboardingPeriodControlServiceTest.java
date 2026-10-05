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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;
import org.openbravo.model.financialmgmt.calendar.Year;

/**
 * Unit tests for {@link OnboardingPeriodControlService}.
 *
 * <p>Follows the established onboarding-service test pattern: a {@link TestableService} subclass
 * overrides the protected DB "seam" methods (context handling, organization/calendar/period
 * resolution and the provisioning steps) so no real database is touched. The few seams that issue
 * {@code OBDal.getInstance().save(...)} directly (enablePeriodControl, rebrandImportedCalendarName,
 * applyPeriodOpenState) are exercised under a {@link MockedStatic} of {@link OBDal} that swallows
 * the saves.
 */
public class OnboardingPeriodControlServiceTest {

  private static final Date PAST = new Date(0L);
  private static final Date NOW = new Date(1_000_000_000L);
  private static final Date FUTURE = new Date(Long.MAX_VALUE);

  // ---------------------------------------------------------------------------
  // 1. Context validation
  // ---------------------------------------------------------------------------

  @Test
  public void testWireFailsWhenClientIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected missing clientId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testWireFailsWhenOrgIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire("CLIENT-1", null, "USER-1", "ROLE-1");
      fail("Expected missing orgId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testWireFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testWireFailsWhenAdminRoleIsMissing() {
    TestableService service = new TestableService();
    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", null);
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  // ---------------------------------------------------------------------------
  // 2/3. Resolution failures inside wire()
  // ---------------------------------------------------------------------------

  @Test
  public void testWireFailsWhenOrganizationNotFound() {
    TestableService service = new TestableService();
    service.organizationResolvesToNull = true;

    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected missing organization to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found for period-control wiring"));
    }
  }

  @Test
  public void testWireFailsWhenNoCalendarImported() {
    TestableService service = new TestableService();
    service.calendarResolvesToNull = true;

    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected missing calendar to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("No calendar was imported"));
    }
  }

  // ---------------------------------------------------------------------------
  // 4. Happy path: all provisioning steps invoked, flushed, context restored
  // ---------------------------------------------------------------------------

  @Test
  public void testWireRunsAllStepsFlushesAndRestoresContext() {
    TestableService service = new TestableService();
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertTrue("enablePeriodControl must run", service.enablePeriodControlCalled);
    assertTrue("rebrandImportedCalendarName must run", service.rebrandCalled);
    assertTrue("openPeriodsThroughCurrentMonth must run", service.openPeriodsCalled);
    assertTrue("changes must be flushed", service.flushed);
    assertSame(previous, OBContext.getOBContext());
  }

  @Test
  public void testWireRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.failOnOpenPeriods = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected delegated failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("open-periods-boom"));
    }

    assertSame(previous, OBContext.getOBContext());
  }

  // ---------------------------------------------------------------------------
  // 5. openPeriodsThroughCurrentMonth date cut-off
  // ---------------------------------------------------------------------------

  @Test
  public void testOpenPeriodsAppliesDateCutoff() {
    Period pastPeriod = mock(Period.class);
    Period todayPeriod = mock(Period.class);
    Period futurePeriod = mock(Period.class);
    when(pastPeriod.getStartingDate()).thenReturn(PAST);
    when(todayPeriod.getStartingDate()).thenReturn(NOW);
    when(futurePeriod.getStartingDate()).thenReturn(FUTURE);

    PeriodControl pastControl = mock(PeriodControl.class);
    PeriodControl todayControl = mock(PeriodControl.class);
    PeriodControl futureControl = mock(PeriodControl.class);

    RealLogicService service = new RealLogicService();
    service.fixedNow = NOW;
    service.periods = Arrays.asList(pastPeriod, todayPeriod, futurePeriod);
    service.controlsByPeriod.put(pastPeriod, Arrays.asList(pastControl));
    service.controlsByPeriod.put(todayPeriod, Arrays.asList(todayControl));
    service.controlsByPeriod.put(futurePeriod, Arrays.asList(futureControl));

    Calendar calendar = mock(Calendar.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      service.openPeriodsThroughCurrentMonth(calendar);
    }

    // Past period -> OPEN
    verify(pastPeriod).setOpenClose("C");
    verify(pastControl).setPeriodStatus("O");
    verify(pastControl).setPeriodAction("N");
    verify(pastControl).setOpenClose("C");

    // Boundary: starting date == today -> OPEN (condition is !after(today))
    verify(todayPeriod).setOpenClose("C");
    verify(todayControl).setPeriodStatus("O");
    verify(todayControl).setPeriodAction("N");
    verify(todayControl).setOpenClose("C");

    // Future period -> CLOSED / never-opened
    verify(futurePeriod).setOpenClose("O");
    verify(futureControl).setPeriodStatus("N");
    verify(futureControl).setPeriodAction("N");
    verify(futureControl).setOpenClose("O");
  }

  // ---------------------------------------------------------------------------
  // 6. rebrandImportedCalendarName
  // ---------------------------------------------------------------------------

  @Test
  public void testRebrandReplacesMonikerAndSaves() {
    RealLogicService service = new RealLogicService();
    Organization org = mock(Organization.class);
    Client client = mock(Client.class);
    Calendar calendar = mock(Calendar.class);
    when(org.getClient()).thenReturn(client);
    when(client.getName()).thenReturn("Acme");
    when(calendar.getName()).thenReturn("GOOrg Calendar");

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.rebrandImportedCalendarName(org, calendar);
    }

    verify(calendar).setName("Acme Calendar");
    verify(dal).save(calendar);
  }

  @Test
  public void testRebrandLeavesNameUntouchedWhenNoMoniker() {
    RealLogicService service = new RealLogicService();
    Organization org = mock(Organization.class);
    Client client = mock(Client.class);
    Calendar calendar = mock(Calendar.class);
    when(org.getClient()).thenReturn(client);
    when(client.getName()).thenReturn("Acme");
    when(calendar.getName()).thenReturn("Standard Calendar");

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.rebrandImportedCalendarName(org, calendar);
    }

    verify(calendar, never()).setName("Acme");
    verify(calendar, never()).setName("Standard Calendar");
    verify(dal, never()).save(calendar);
  }

  // ---------------------------------------------------------------------------
  // 7. enablePeriodControl
  // ---------------------------------------------------------------------------

  @Test
  public void testEnablePeriodControlSetsAllFieldsAndSaves() {
    RealLogicService service = new RealLogicService();
    Organization org = mock(Organization.class);
    Calendar calendar = mock(Calendar.class);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.enablePeriodControl(org, calendar);
    }

    verify(org).setAllowPeriodControl(true);
    verify(org).setCalendar(calendar);
    verify(org).setInheritedCalendar(calendar);
    verify(org).setPeriodControlAllowedOrganization(org);
    verify(org).setCalendarOwnerOrganization(org);
    verify(dal).save(org);
  }

  // ---------------------------------------------------------------------------
  // 8. resolveImportedCalendar() — real OBCriteria body
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveImportedCalendarReturnsOrgOwnedCalendar() {
    OnboardingPeriodControlService service = new OnboardingPeriodControlService();
    Organization org = mock(Organization.class);
    Calendar calendar = mock(Calendar.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Calendar> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Calendar.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(calendar));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(calendar, service.resolveImportedCalendar(org));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveImportedCalendarWarnsAndReturnsFirstWhenMultiple() {
    OnboardingPeriodControlService service = new OnboardingPeriodControlService();
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    Calendar first = mock(Calendar.class);
    when(first.getId()).thenReturn("CAL-1");
    Calendar second = mock(Calendar.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Calendar> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Calendar.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(first, second));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(first, service.resolveImportedCalendar(org));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveImportedCalendarFallsBackToClientCalendarWhenOrgOwnsNone() {
    OnboardingPeriodControlService service = new OnboardingPeriodControlService();
    Organization org = mock(Organization.class);
    Client client = mock(Client.class);
    when(org.getClient()).thenReturn(client);
    Calendar clientCalendar = mock(Calendar.class);

    OBDal dal = mock(OBDal.class);
    // First criteria (org-owned) returns empty -> resolveClientCalendar() second criteria runs.
    OBCriteria<Calendar> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Calendar.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.emptyList());
    when(crit.uniqueResult()).thenReturn(clientCalendar);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(clientCalendar, service.resolveImportedCalendar(org));
    }
  }

  // ---------------------------------------------------------------------------
  // 9. resolveCalendarPeriods() — real OBCriteria body, both year branches
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveCalendarPeriodsReturnsEmptyWhenNoYears() {
    OnboardingPeriodControlService service = new OnboardingPeriodControlService();
    Calendar calendar = mock(Calendar.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Year> yearCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Year.class)).thenReturn(yearCrit);
    when(yearCrit.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertTrue(service.resolveCalendarPeriods(calendar).isEmpty());
    }

    // No-years short circuit must not query periods.
    verify(dal, never()).createCriteria(Period.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveCalendarPeriodsQueriesPeriodsForYears() {
    OnboardingPeriodControlService service = new OnboardingPeriodControlService();
    Calendar calendar = mock(Calendar.class);
    Year year = mock(Year.class);
    Period period = mock(Period.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Year> yearCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Year.class)).thenReturn(yearCrit);
    when(yearCrit.list()).thenReturn(Collections.singletonList(year));
    OBCriteria<Period> periodCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Period.class)).thenReturn(periodCrit);
    when(periodCrit.list()).thenReturn(Collections.singletonList(period));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      List<Period> result = service.resolveCalendarPeriods(calendar);
      assertEquals(1, result.size());
      assertSame(period, result.get(0));
    }
  }

  // ---------------------------------------------------------------------------
  // 10. resolvePeriodControls() — real OBCriteria body
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolvePeriodControlsReturnsControlRows() {
    OnboardingPeriodControlService service = new OnboardingPeriodControlService();
    Period period = mock(Period.class);
    PeriodControl control = mock(PeriodControl.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<PeriodControl> crit = mock(OBCriteria.class);
    when(dal.createCriteria(PeriodControl.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(control));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      List<PeriodControl> result = service.resolvePeriodControls(period);
      assertEquals(1, result.size());
      assertSame(control, result.get(0));
    }
  }

  // ---------------------------------------------------------------------------
  // 11. currentDate() / contextSubject() — leaf seams
  // ---------------------------------------------------------------------------

  @Test
  public void testCurrentDateReturnsNonNullInstant() {
    assertNotNull(new OnboardingPeriodControlService().currentDate());
  }

  @Test
  public void testContextSubjectIsPeriodControlWiring() {
    assertEquals("period-control wiring",
        new OnboardingPeriodControlService().contextSubject());
  }

  // ---------------------------------------------------------------------------
  // 12. ETP-5575 — demo trial window (open-only, through the trial end)
  // ---------------------------------------------------------------------------

  @Test
  public void testDemoWindowCrossesIntoTheNextMonthAndLeavesLaterMonthsAlone() {
    Period sep = period("2026-09-01", "2026-09-30", "C");
    Period oct = period("2026-10-01", "2026-10-31", "O");
    Period nov = period("2026-11-01", "2026-11-30", "O");
    PeriodControl sepControl = control("O");
    PeriodControl octControl = control("N");
    PeriodControl novControl = control("N");
    RealLogicService service = new RealLogicService();
    service.periods = Arrays.asList(sep, oct, nov);
    service.controlsByPeriod.put(sep, Arrays.asList(sepControl));
    service.controlsByPeriod.put(oct, Arrays.asList(octControl));
    service.controlsByPeriod.put(nov, Arrays.asList(novControl));

    runWithDal(() -> service.openDemoTrialWindow("CLIENT-1", "ORG-1", "USER-1", "ROLE-1",
        Instant.parse("2026-09-30T10:00:00Z"), 15));

    assertEquals("O", octControl.getPeriodStatus());
    verify(octControl).setOpenClose("C");
    verify(oct).setOpenClose("C");
    assertEquals("N", novControl.getPeriodStatus());
    verify(nov, never()).setOpenClose("C");
    verify(sepControl, never()).setPeriodStatus("O");
    assertTrue("the window must be flushed", service.flushed);
  }

  @Test
  public void testDemoWindowOpensEveryDuplicateCopyOfAPeriod() {
    Period sep = period("2026-09-01", "2026-09-30", "C");
    PeriodControl datasetCopy = control("O");
    PeriodControl orgReadyCopy = control("N");
    RealLogicService service = new RealLogicService();
    service.periods = Arrays.asList(sep);
    service.controlsByPeriod.put(sep, Arrays.asList(datasetCopy, orgReadyCopy));

    runWithDal(() -> service.openNeverOpenedPeriodsThrough(service.periods, date("2026-10-15")));

    assertEquals("O", datasetCopy.getPeriodStatus());
    assertEquals("O", orgReadyCopy.getPeriodStatus());
    verify(orgReadyCopy).setPeriodAction("N");
    verify(orgReadyCopy).setOpenClose("C");
    // Already flagged open: no redundant save of the period.
    verify(sep, never()).setOpenClose("O");
  }

  @Test
  public void testDemoWindowNeverTouchesUserClosedRows() {
    Period aug = period("2026-08-01", "2026-08-31", "O");
    PeriodControl closed = control("C");
    PeriodControl permanentlyClosed = control("P");
    PeriodControl neverOpened = control("N");
    RealLogicService service = new RealLogicService();
    service.periods = Arrays.asList(aug);
    service.controlsByPeriod.put(aug, Arrays.asList(closed, permanentlyClosed, neverOpened));

    runWithDal(() -> service.openNeverOpenedPeriodsThrough(service.periods, date("2026-10-15")));

    assertEquals("C", closed.getPeriodStatus());
    assertEquals("P", permanentlyClosed.getPeriodStatus());
    assertEquals("O", neverOpened.getPeriodStatus());
    verify(closed, never()).setOpenClose("C");
    verify(permanentlyClosed, never()).setOpenClose("C");
    // Not every row is open, so the period keeps its open-available flag.
    verify(aug, never()).setOpenClose("C");
  }

  @Test
  public void testDemoWindowBeyondTheCalendarOpensWhatExists() {
    Period dec = period("2026-12-01", "2026-12-31", "O");
    PeriodControl decControl = control("N");
    RealLogicService service = new RealLogicService();
    service.periods = Arrays.asList(dec);
    service.controlsByPeriod.put(dec, Arrays.asList(decControl));

    runWithDal(() -> service.openDemoTrialWindow("CLIENT-1", "ORG-1", "USER-1", "ROLE-1",
        Instant.parse("2026-12-20T10:00:00Z"), 15));

    assertEquals("O", decControl.getPeriodStatus());
    verify(dec).setOpenClose("C");
  }

  @Test
  public void testDemoWindowPrefersTheOrganizationCalendar() {
    RealLogicService service = new RealLogicService();
    Calendar orgCalendar = mock(Calendar.class);
    when(service.organization.getCalendar()).thenReturn(orgCalendar);

    runWithDal(() -> service.openDemoTrialWindow("CLIENT-1", "ORG-1", "USER-1", "ROLE-1",
        Instant.parse("2026-10-01T10:00:00Z"), 15));

    assertSame(orgCalendar, service.lastResolvedCalendar);
  }

  @Test
  public void testDemoWindowFailsWithoutATrialStart() {
    RealLogicService service = new RealLogicService();
    try {
      service.openDemoTrialWindow("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", null, 15);
      fail("Expected a missing trial start to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing trial start"));
    }
  }

  @Test
  public void testDemoWindowFailsWhenOrganizationNotFoundAndRestoresContext() {
    RealLogicService service = new RealLogicService();
    service.organizationResolvesToNull = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.openDemoTrialWindow("CLIENT-1", "ORG-1", "USER-1", "ROLE-1",
          Instant.parse("2026-10-01T10:00:00Z"), 15);
      fail("Expected a missing organization to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found for demo period window"));
    }
    assertSame(previous, OBContext.getOBContext());
  }

  @Test
  public void testDemoWindowFailsWhenNoCalendarIsFound() {
    RealLogicService service = new RealLogicService();
    service.calendarResolvesToNull = true;

    try {
      service.openDemoTrialWindow("CLIENT-1", "ORG-1", "USER-1", "ROLE-1",
          Instant.parse("2026-10-01T10:00:00Z"), 15);
      fail("Expected a missing calendar to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("No calendar found for demo period window"));
    }
  }

  private static void runWithDal(Runnable action) {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      action.run();
    }
  }

  private static Date date(String isoDate) {
    return Date.from(LocalDate.parse(isoDate).atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  private static Period period(String start, String end, String openClose) {
    Period period = mock(Period.class);
    when(period.getStartingDate()).thenReturn(date(start));
    when(period.getEndingDate()).thenReturn(date(end));
    when(period.getOpenClose()).thenReturn(openClose);
    return period;
  }

  /** A control row whose status getter reflects what the service sets, like a real entity. */
  private static PeriodControl control(String status) {
    PeriodControl control = mock(PeriodControl.class);
    String[] current = { status };
    when(control.getPeriodStatus()).thenAnswer(invocation -> current[0]);
    doAnswer(invocation -> {
      current[0] = invocation.getArgument(0);
      return null;
    }).when(control).setPeriodStatus(anyString());
    return control;
  }

  // ---------------------------------------------------------------------------
  // Test seam subclass
  // ---------------------------------------------------------------------------

  /**
   * Base seam subclass: stubs all context-switching methods so no real {@link OBContext} state
   * leaks and no admin mode is entered.
   */
  private abstract static class ContextStubbedService extends OnboardingPeriodControlService {

    @Override
    protected OBContext captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId,
        String clientId, String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void restoreExecutionContext(OBContext previousContext) {
      OBContext.setOBContext(previousContext);
    }

    @Override
    protected void enterAdminMode() {
      // no-op
    }

    @Override
    protected void exitAdminMode() {
      // no-op
    }
  }

  /**
   * Subclass used by the {@code wire()} tests: every provisioning step is overridden as a
   * flag-setting seam so {@code wire()} can be exercised end-to-end without touching the DB.
   */
  private static final class TestableService extends ContextStubbedService {

    private final Organization organization = mock(Organization.class);
    private final Calendar calendar = mock(Calendar.class);

    boolean organizationResolvesToNull;
    boolean calendarResolvesToNull;
    boolean failOnOpenPeriods;

    boolean enablePeriodControlCalled;
    boolean rebrandCalled;
    boolean openPeriodsCalled;
    boolean flushed;

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return organizationResolvesToNull ? null : organization;
    }

    @Override
    protected Calendar resolveImportedCalendar(Organization org) {
      return calendarResolvesToNull ? null : calendar;
    }

    @Override
    protected void enablePeriodControl(Organization org, Calendar calendar) {
      enablePeriodControlCalled = true;
    }

    @Override
    protected void rebrandImportedCalendarName(Organization org, Calendar calendar) {
      rebrandCalled = true;
    }

    @Override
    protected void openPeriodsThroughCurrentMonth(Calendar calendar) {
      openPeriodsCalled = true;
      if (failOnOpenPeriods) {
        throw new OBException("open-periods-boom");
      }
    }
  }

  /**
   * Subclass used by the unit tests that exercise the REAL provisioning logic
   * ({@code enablePeriodControl}, {@code rebrandImportedCalendarName},
   * {@code openPeriodsThroughCurrentMonth}). It keeps those implementations intact and only stubs
   * the leaf seams ({@code currentDate}, {@code resolveCalendarPeriods},
   * {@code resolvePeriodControls}); direct {@code OBDal} saves are swallowed by a
   * {@link MockedStatic} in each test.
   */
  private static final class RealLogicService extends ContextStubbedService {

    Date fixedNow = NOW;
    List<Period> periods = java.util.Collections.emptyList();
    final java.util.Map<Period, List<PeriodControl>> controlsByPeriod = new java.util.HashMap<>();
    final Organization organization = mock(Organization.class);
    final Calendar importedCalendar = mock(Calendar.class);
    boolean organizationResolvesToNull;
    boolean calendarResolvesToNull;
    boolean flushed;
    Calendar lastResolvedCalendar;

    @Override
    protected Date currentDate() {
      return fixedNow;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return organizationResolvesToNull ? null : organization;
    }

    @Override
    protected Calendar resolveImportedCalendar(Organization org) {
      return calendarResolvesToNull ? null : importedCalendar;
    }

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected List<Period> resolveCalendarPeriods(Calendar calendar) {
      lastResolvedCalendar = calendar;
      return periods;
    }

    @Override
    protected List<PeriodControl> resolvePeriodControls(Period period) {
      return controlsByPeriod.getOrDefault(period, java.util.Collections.emptyList());
    }
  }
}
