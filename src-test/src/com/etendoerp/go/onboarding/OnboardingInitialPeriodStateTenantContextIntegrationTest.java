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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;

/**
 * The initial-period-state marker, written and cleared from the context the onboarding really runs
 * this step in: the new tenant's admin.
 *
 * <p>{@link OnboardingInitialPeriodStateService#markPending} stores the marker through
 * {@code Preferences.setPreferenceValue}, which always writes a client {@code 0} row visible at the
 * tenant. After the onboarding commit, {@link OnboardingInitialPeriodStateService#applyIfPending}
 * runs with the tenant as the current client and deletes that row last. The DAL write check refuses
 * a client {@code 0} row unless it runs as system ({@code Client (0) of object ... is not present
 * in ClientList <tenant>}), so the whole step rolled back on every onboarding and only healed at the
 * next environment entry, which retries it as system. The unit test mocks {@link OBContext}, so it
 * could not see this.
 *
 * <p>{@link OBBaseTest#setTestUserContext()} gives that shape: a non-system current client. The
 * period work is stubbed, since only the marker lifecycle is under test. Nothing is committed; the
 * rollback in {@link #rollBack()} discards the marker.
 *
 * @covers com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService
 */
public class OnboardingInitialPeriodStateTenantContextIntegrationTest extends OBBaseTest {

  private static final String ZERO = "0";

  /** Discards the uncommitted marker and clears the thread context. */
  @After
  public void rollBack() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  /**
   * A demo onboarding marks the state pending, then applies it from the same tenant context: the
   * marker must be cleared and the caller's context left as it was.
   */
  @Test
  public void testThePendingMarkerIsClearedFromATenantAdminContext() {
    setTestUserContext();
    assertNotEquals("Client 0 must not be the current client, or this spec proves nothing", ZERO,
        OBContext.getOBContext().getCurrentClient().getId());
    OnboardingInitialPeriodStateService service = new OnboardingInitialPeriodStateService(
        mock(OnboardingPeriodControlService.class), demoLifecycle());

    service.markPending(TEST_CLIENT_ID, TEST_ORG_ID, TEST_USER_ID, TEST_ROLE_ID, false);
    OBDal.getInstance().flush();
    assertTrue("Sanity: the marker must be pending", service.isPending(TEST_CLIENT_ID));
    assertEquals("Sanity: the marker is a client 0 row, the case under test", 1L,
        markerRowsOwnedBySystem());

    assertTrue("The pending state must be applied", service.applyIfPending(TEST_CLIENT_ID));
    OBDal.getInstance().flush();

    assertFalse("The marker must be cleared", service.isPending(TEST_CLIENT_ID));
    assertEquals("The caller must still be the tenant admin", TEST_CLIENT_ID,
        OBContext.getOBContext().getCurrentClient().getId());
  }

  /**
   * A productive onboarding also writes the calendar-review marker. Removing the pending marker as
   * system, with the client filters off, must clear only that one: the review marker drives the
   * "Primeros pasos" step and must survive.
   */
  @Test
  public void testOnlyThePendingMarkerIsClearedForAProductiveTenant() {
    setTestUserContext();
    OnboardingInitialPeriodStateService service = new OnboardingInitialPeriodStateService(
        mock(OnboardingPeriodControlService.class),
        lifecycle(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE));

    service.markPending(TEST_CLIENT_ID, TEST_ORG_ID, TEST_USER_ID, TEST_ROLE_ID, true);
    OBDal.getInstance().flush();
    assertTrue("The pending state must be applied", service.applyIfPending(TEST_CLIENT_ID));
    OBDal.getInstance().flush();

    assertFalse("The pending marker must be cleared", service.isPending(TEST_CLIENT_ID));
    assertTrue("The calendar-review marker must survive", service.isReviewRequired(TEST_CLIENT_ID));
  }

  /**
   * The environment-login retry already runs as system. Clearing the marker must still work there
   * and leave the caller as system.
   */
  @Test
  public void testThePendingMarkerIsClearedFromTheSystemLoginRetryContext() {
    setTestUserContext();
    OnboardingInitialPeriodStateService service = new OnboardingInitialPeriodStateService(
        mock(OnboardingPeriodControlService.class), demoLifecycle());
    service.markPending(TEST_CLIENT_ID, TEST_ORG_ID, TEST_USER_ID, TEST_ROLE_ID, false);
    OBDal.getInstance().flush();

    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    assertTrue("The pending state must be applied", service.applyIfPending(TEST_CLIENT_ID));
    OBDal.getInstance().flush();

    assertFalse("The marker must be cleared", service.isPending(TEST_CLIENT_ID));
    assertEquals("The caller must still be system", ZERO,
        OBContext.getOBContext().getCurrentClient().getId());
  }

  private static TenantEnvironmentLifecycleService demoLifecycle() {
    return lifecycle(EnvironmentAccessPolicy.EnvironmentType.DEMO);
  }

  private static TenantEnvironmentLifecycleService lifecycle(
      EnvironmentAccessPolicy.EnvironmentType type) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        mock(TenantEnvironmentLifecycleService.EnvironmentSnapshot.class);
    when(snapshot.getType()).thenReturn(type);
    when(snapshot.getTrialStartedAt()).thenReturn(Instant.now());
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.resolve(TEST_CLIENT_ID)).thenReturn(snapshot);
    when(lifecycle.configuration()).thenReturn(new EnvironmentAccessPolicy.Configuration(15, 7));
    return lifecycle;
  }

  @SuppressWarnings("rawtypes")
  private static long markerRowsOwnedBySystem() {
    NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT COUNT(*) FROM AD_PREFERENCE WHERE ATTRIBUTE = :attribute"
            + " AND VISIBLEAT_CLIENT_ID = :tenant AND AD_CLIENT_ID = '0'");
    query.setParameter("attribute", OnboardingInitialPeriodStateService.PENDING_ATTRIBUTE);
    query.setParameter("tenant", TEST_CLIENT_ID);
    return ((Number) query.uniqueResult()).longValue();
  }
}
