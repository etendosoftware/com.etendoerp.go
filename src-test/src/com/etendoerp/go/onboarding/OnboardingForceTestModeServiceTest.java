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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.payment.TenantPlanService;

/**
 * Unit tests for {@link OnboardingForceTestModeService} (ETP-5117, gap N1).
 *
 * <p>Focuses on the three behaviors that must never regress:
 * <ol>
 *   <li>only a Demo/free tenant ({@link TenantPlanService#PLAN_FREE}) ever gets a new preference
 *       row — a productive tenant is left completely untouched;</li>
 *   <li>the new row is ALWAYS client-scoped ({@link Preference#setClient} pinned to the tenant),
 *       never a write against the System-level default row;</li>
 *   <li>a tenant that already owns its own active row is never overwritten (idempotent no-op on a
 *       resumed/retried onboarding pass, per the ETP-4428 reconcile model).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingForceTestModeServiceTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORG_ID = "ORG-1";

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private Client client;
  @Mock private Organization organization;
  @Mock private TenantPlanService tenantPlanService;
  @Mock private Preference newPreference;
  @Mock private OBCriteria<Preference> criteria;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(organization);
    doReturn(criteria).when(obDal).createCriteria(Preference.class);
    when(criteria.add(any())).thenReturn(criteria);
    // Default: tenant has no own row yet.
    when(criteria.uniqueResult()).thenReturn(null);
    doReturn(newPreference).when(obProvider).get(Preference.class);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obProviderMock != null) {
      obProviderMock.close();
    }
  }

  private void forceTestMode() {
    new OnboardingForceTestModeService(tenantPlanService).forceTestModeForFreeTenant(CLIENT_ID, ORG_ID);
  }

  @Test
  @DisplayName("inserts a brand-new, client-scoped ETSG_ForceTestMode='Y' row for a free tenant")
  void insertsClientScopedRowForFreeTenant() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);

    forceTestMode();

    // Client-scoped, non-negotiable: the entity's own Client must be the TENANT, never System.
    verify(newPreference).setClient(client);
    verify(newPreference).setOrganization(organization);
    verify(newPreference).setActive(true);
    verify(newPreference).setPropertyList(true);
    verify(newPreference).setProperty(OnboardingForceTestModeService.FORCE_TEST_MODE_PROPERTY);
    verify(newPreference).setSearchKey("Y");
    verify(obDal).save(newPreference);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("leaves a productive (paid) tenant completely untouched")
  void skipsProductiveTenant() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    forceTestMode();

    verify(obProvider, never()).get(Preference.class);
    verify(obDal, never()).save(any());
    verify(obDal, never()).flush();
  }

  @Test
  @DisplayName("never overwrites a tenant's own already-existing ETSG_ForceTestMode row "
      + "(idempotent no-op on a retried onboarding pass)")
  void skipsWhenOwnRowAlreadyExists() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);
    Preference existing = mock(Preference.class);
    when(criteria.uniqueResult()).thenReturn(existing);

    forceTestMode();

    verify(obProvider, never()).get(Preference.class);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("the own-row lookup filters on the row's own Client, never VisibleAtClient "
      + "(matching exactly what ForceTestModeEventHandler#findPreference reads)")
  void ownRowLookupFiltersOnClientColumn() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);

    forceTestMode();

    verify(criteria).setFilterOnReadableClients(false);
    verify(criteria).setFilterOnReadableOrganization(false);
  }

  @Test
  @DisplayName("throws when the client cannot be found")
  void throwsWhenClientMissing() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);
    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(null);

    assertThrows(OBException.class, this::forceTestMode);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("throws when the organization cannot be found")
  void throwsWhenOrganizationMissing() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_FREE);
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(null);

    assertThrows(OBException.class, this::forceTestMode);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("throws when no client id is given")
  void throwsWhenClientIdBlank() {
    assertThrows(OBException.class,
        () -> new OnboardingForceTestModeService(tenantPlanService)
            .forceTestModeForFreeTenant("", ORG_ID));
    verify(obDal, never()).save(any());
  }
}
