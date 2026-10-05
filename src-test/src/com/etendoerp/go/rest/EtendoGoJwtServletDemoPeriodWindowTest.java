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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingPeriodControlService;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;

/**
 * ETP-5575 — the post-commit, best-effort step that opens a demo's periods through its trial end.
 * It must use the stored trial start and the configured trial days, commit on its own, and never
 * let a failure (its own or its commit's) escape to the onboarding.
 */
class EtendoGoJwtServletDemoPeriodWindowTest {

  private static final Instant STARTED = Instant.parse("2026-09-30T10:00:00Z");

  private EtendoGoJwtServlet servlet;
  private OnboardingPeriodControlService periods;
  private TenantEnvironmentLifecycleService lifecycle;
  private OnboardingProvisioningChain.AdminContext admin;

  @BeforeEach
  void setUp() {
    servlet = new EtendoGoJwtServlet(mock(TransactionalAuthEmailSender.class));
    periods = mock(OnboardingPeriodControlService.class);
    lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet.onboardingPeriodControlService = periods;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    when(lifecycle.configuration()).thenReturn(new EnvironmentAccessPolicy.Configuration(15, 7));
    admin = new OnboardingProvisioningChain.AdminContext();
    admin.adminUserId = "ADMIN-USER";
    admin.adminRoleId = "ADMIN-ROLE";
  }

  @Test
  void opensTheWindowFromTheStoredTrialStartAndCommitsIt() {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = demoSnapshot(STARTED);
    when(lifecycle.resolve("DEMO")).thenReturn(snapshot);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      servlet.openDemoTrialPeriodsBestEffort("DEMO", "ORG-1", admin);
    }

    InOrder order = inOrder(periods, dal);
    order.verify(periods).openDemoTrialWindow("DEMO", "ORG-1", "ADMIN-USER", "ADMIN-ROLE",
        STARTED, 15);
    order.verify(dal).commitAndClose();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  void fallsBackToNowWhenTheTrialStartCannotBeRead() {
    when(lifecycle.resolve("DEMO")).thenReturn(null);
    Instant before = Instant.now();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      servlet.openDemoTrialPeriodsBestEffort("DEMO", "ORG-1", admin);
    }

    ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
    verify(periods).openDemoTrialWindow(eq("DEMO"), eq("ORG-1"), eq("ADMIN-USER"),
        eq("ADMIN-ROLE"), start.capture(), eq(15));
    assertFalse(start.getValue().isBefore(before), "the fallback start is now");
  }

  @Test
  void aFailingWindowIsRolledBackAndNeverEscapes() {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = demoSnapshot(STARTED);
    when(lifecycle.resolve("DEMO")).thenReturn(snapshot);
    doThrow(new OBException("boom")).when(periods).openDemoTrialWindow(anyString(), anyString(),
        anyString(), anyString(), any(Instant.class), anyInt());
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertDoesNotThrow(() -> servlet.openDemoTrialPeriodsBestEffort("DEMO", "ORG-1", admin));
    }

    verify(dal, never()).commitAndClose();
    verify(dal).rollbackAndClose();
  }

  @Test
  void aFailingCommitIsRolledBackAndNeverEscapes() {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = demoSnapshot(STARTED);
    when(lifecycle.resolve("DEMO")).thenReturn(snapshot);
    OBDal dal = mock(OBDal.class);
    doThrow(new IllegalStateException("commit boom")).when(dal).commitAndClose();

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertDoesNotThrow(() -> servlet.openDemoTrialPeriodsBestEffort("DEMO", "ORG-1", admin));
    }

    verify(dal).rollbackAndClose();
  }

  @Test
  void aFailingTrialConfigurationNeverEscapes() {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = demoSnapshot(STARTED);
    when(lifecycle.resolve("DEMO")).thenReturn(snapshot);
    when(lifecycle.configuration()).thenThrow(new IllegalStateException("config boom"));
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertDoesNotThrow(() -> servlet.openDemoTrialPeriodsBestEffort("DEMO", "ORG-1", admin));
    }

    verify(dal).rollbackAndClose();
    verify(periods, never()).openDemoTrialWindow(anyString(), anyString(), anyString(),
        anyString(), any(Instant.class), anyInt());
  }

  private static TenantEnvironmentLifecycleService.EnvironmentSnapshot demoSnapshot(
      Instant startedAt) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        mock(TenantEnvironmentLifecycleService.EnvironmentSnapshot.class);
    when(snapshot.getTrialStartedAt()).thenReturn(startedAt);
    return snapshot;
  }
}
