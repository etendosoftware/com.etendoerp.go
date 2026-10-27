/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * ETP-5847 — what the daily notice pass does for a demo whose trial is frozen: no notice, and its
 * fiscal periods opened through the end the trial shows, in the owner's context.
 *
 * @covers com.etendoerp.go.payment.DalDemoTrialNoticeSource
 */
public class DalDemoTrialNoticeSourceTest {

  private static final String CLIENT_ID = "FROZEN-DEMO";
  private static final String OWNER_ID = "OWNER";
  private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

  private final TenantEnvironmentLifecycleService lifecycle =
      mock(TenantEnvironmentLifecycleService.class);
  private final OnboardingPeriodControlService periods = mock(OnboardingPeriodControlService.class);
  private final OBDal dal = mock(OBDal.class);
  private final DalDemoTrialNoticeSource source =
      new DalDemoTrialNoticeSource(lifecycle, DemoTrialNoticePlanner::fromConfiguration, periods);

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<OwnerSupport> ownerSupport;

  @Before
  public void setUp() {
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    ownerSupport = mockStatic(OwnerSupport.class);
    when(lifecycle.configuration()).thenReturn(new EnvironmentAccessPolicy.Configuration(14, 15));
  }

  @After
  public void tearDown() {
    ownerSupport.close();
    obDal.close();
    obContext.close();
  }

  private void givenOwner(boolean withRoleAndOrg) {
    ownerSupport.when(() -> OwnerSupport.findOwnerUserId(CLIENT_ID)).thenReturn(OWNER_ID);
    User owner = mock(User.class);
    when(owner.getId()).thenReturn(OWNER_ID);
    if (withRoleAndOrg) {
      Role role = mock(Role.class);
      when(role.getId()).thenReturn("ROLE");
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG");
      when(owner.getDefaultRole()).thenReturn(role);
      when(owner.getDefaultOrganization()).thenReturn(org);
    }
    when(dal.get(User.class, OWNER_ID)).thenReturn(owner);
  }

  @Test
  public void aTrialThatIsNotFrozenIsLeftToTheNotices() {
    when(lifecycle.frozenTrialDays(CLIENT_ID)).thenReturn(null);

    assertFalse(source.keepFrozenTrial(CLIENT_ID, NOW));
    verify(periods, never()).openDemoTrialWindow(anyString(), anyString(), anyString(),
        anyString(), any(), anyInt());
  }

  @Test
  public void aFrozenTrialOpensItsPeriodsThroughTheEndItShows() {
    when(lifecycle.frozenTrialDays(CLIENT_ID)).thenReturn(10);
    givenOwner(true);

    assertTrue(source.keepFrozenTrial(CLIENT_ID, NOW));

    // 10 days left of a 14-day trial: it started 4 days ago, so it ends 10 days from now.
    verify(periods).openDemoTrialWindow(CLIENT_ID, "ORG", OWNER_ID, "ROLE",
        NOW.minus(4, ChronoUnit.DAYS), 14);
    verify(dal).commitAndClose();
  }

  @Test
  public void aFrozenTrialWithoutAnOwnerContextIsStillOwedNoNotice() {
    when(lifecycle.frozenTrialDays(CLIENT_ID)).thenReturn(14);
    givenOwner(false);

    assertTrue(source.keepFrozenTrial(CLIENT_ID, NOW));
    verify(periods, never()).openDemoTrialWindow(anyString(), anyString(), anyString(),
        anyString(), any(), anyInt());
  }
}
