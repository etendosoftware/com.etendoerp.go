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
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.common.WarehouseLookupHelper;

/**
 * Unit tests for {@link OnboardingAdminIdentityService}, focused on {@code
 * wireAdminIdentity}'s "fill only if null" COALESCE-like semantics (see {@code
 * applySessionDefaults}'s own Javadoc) — this is the behavior an earlier commit on this branch
 * (ETP-4894 / ETP-4999) claimed, in a comment on {@code
 * EtendoGoJwtServletOnboardingDatasetTest}, was already covered by an
 * {@code OnboardingAdminIdentityServiceTest} that did not actually exist. This class closes that
 * gap for real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingAdminIdentityServiceTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORG_ID = "ORG-1";
  private static final String USER_ID = "USER-1";
  private static final String ROLE_ID = "ROLE-1";

  @Mock private OBDal obDal;
  @Mock private User user;
  @Mock private Role role;
  @Mock private Organization org;
  @Mock private Client client;
  @Mock private Warehouse warehouse;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<WarehouseLookupHelper> warehouseLookupMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    warehouseLookupMock = mockStatic(WarehouseLookupHelper.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.get(User.class, USER_ID)).thenReturn(user);
    when(obDal.get(Role.class, ROLE_ID)).thenReturn(role);
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(org);
    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
    warehouseLookupMock.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
        .thenReturn(warehouse);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (warehouseLookupMock != null) {
      warehouseLookupMock.close();
    }
  }

  private void wireAdminIdentity() {
    new OnboardingAdminIdentityService().wireAdminIdentity(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID);
  }

  @Test
  @DisplayName("fills all Default_* fields when they are null (first onboarding pass)")
  void fillsAllDefaultsWhenNull() {
    // getDefaultClient()/getDefaultOrganization()/getDefaultWarehouse()/getSmfswsDefaultWsRole()
    // all default to null via Mockito -- exactly the state InitialClientSetup leaves behind.

    wireAdminIdentity();

    verify(user).setDefaultClient(client);
    verify(user).setDefaultOrganization(org);
    verify(user).setDefaultWarehouse(warehouse);
    verify(user).setSmfswsDefaultWsRole(role);
    verify(obDal).save(user);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("never overwrites already-set Default_* fields (COALESCE semantics, ETP-4428 "
      + "reconcile model — a resumed/retried onboarding pass must be a true no-op)")
  void preservesExistingDefaultsOnRetriedOnboarding() {
    Client existingClient = mock(Client.class);
    Organization existingOrg = mock(Organization.class);
    Warehouse existingWarehouse = mock(Warehouse.class);
    Role existingRole = mock(Role.class);
    when(user.getDefaultClient()).thenReturn(existingClient);
    when(user.getDefaultOrganization()).thenReturn(existingOrg);
    when(user.getDefaultWarehouse()).thenReturn(existingWarehouse);
    when(user.getSmfswsDefaultWsRole()).thenReturn(existingRole);

    wireAdminIdentity();

    verify(user, never()).setDefaultClient(any());
    verify(user, never()).setDefaultOrganization(any());
    verify(user, never()).setDefaultWarehouse(any());
    verify(user, never()).setSmfswsDefaultWsRole(any());
    // Idempotent no-op, not skipped entirely: still saved/flushed unconditionally.
    verify(obDal).save(user);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("fills only the fields still null, leaving already-set ones alone (partial retry)")
  void fillsOnlyNullFieldsOnPartialRetry() {
    Client existingClient = mock(Client.class);
    when(user.getDefaultClient()).thenReturn(existingClient);
    // organization, warehouse, and role default to null -- must still be filled.

    wireAdminIdentity();

    verify(user, never()).setDefaultClient(any());
    verify(user).setDefaultOrganization(org);
    verify(user).setDefaultWarehouse(warehouse);
    verify(user).setSmfswsDefaultWsRole(role);
  }

  @Test
  @DisplayName("skips warehouse assignment when no active warehouse is found for client/org")
  void skipsWarehouseAssignmentWhenNoneFound() {
    warehouseLookupMock.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
        .thenReturn(null);

    wireAdminIdentity();

    verify(user, never()).setDefaultWarehouse(any());
    // The other three defaults are unaffected by the warehouse miss.
    verify(user).setDefaultClient(client);
    verify(user).setDefaultOrganization(org);
    verify(user).setSmfswsDefaultWsRole(role);
  }

  @Test
  @DisplayName("throws when the admin user cannot be found")
  void throwsWhenUserMissing() {
    when(obDal.get(User.class, USER_ID)).thenReturn(null);

    assertThrows(OBException.class, this::wireAdminIdentity);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("throws when the admin role cannot be found")
  void throwsWhenRoleMissing() {
    when(obDal.get(Role.class, ROLE_ID)).thenReturn(null);

    assertThrows(OBException.class, this::wireAdminIdentity);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("throws when the target organization cannot be found")
  void throwsWhenOrganizationMissing() {
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(null);

    assertThrows(OBException.class, this::wireAdminIdentity);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("throws when the client cannot be found")
  void throwsWhenClientMissing() {
    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(null);

    assertThrows(OBException.class, this::wireAdminIdentity);
    verify(obDal, never()).save(any());
  }
}
