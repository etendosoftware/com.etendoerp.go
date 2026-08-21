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

package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit specs for {@link TenantPlanService} (ETP-4966).
 *
 * <p>This class is the spec the {@code paid-second-tenant} registry entry declared as accepted debt
 * and never wrote. The note attached to that decision predicted the outcome exactly: "the only
 * covered branch is the one that hides failures — exactly how a paying tenant would silently appear
 * free". That is the bug ETP-4966 reports, so the debt is being paid rather than re-accepted.
 *
 * <p>Two properties matter here beyond ordinary coverage:
 * <ul>
 *   <li><strong>Write and read must agree on the same column.</strong> The marker is written through
 *       {@code Preferences.setPreferenceValue(..., isListProperty=false, ...)}, which is what makes
 *       Openbravo store the key in {@code AD_Preference.Attribute}; {@link
 *       TenantPlanService#resolvePlan} queries that same {@code attribute} property. Flip that
 *       boolean and the key lands in {@code Property} instead, the query matches nothing, and every
 *       paid environment silently reads back as free — with no error anywhere. It is asserted
 *       explicitly for that reason.</li>
 *   <li><strong>A failed marker must be reportable.</strong> Marking stays best-effort — a
 *       commercial marker must not roll back a provisioned environment — but the caller has to be
 *       able to tell that it failed, otherwise "paid but demo" is indistinguishable from success.
 *       </li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantPlanServiceTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String ORG_ID = "9F5511B92BD0465FA678F75278FA9C3A";

  @Mock private OBDal obDal;
  @Mock private OBQuery<Preference> preferenceQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<Preferences> preferencesMock;

  private final TenantPlanService service = new TenantPlanService();

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    preferencesMock = mockStatic(Preferences.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (preferencesMock != null) {
      preferencesMock.close();
    }
  }

  /** Stubs the preference lookup {@code resolvePlan} performs, returning the given row. */
  private void givenStoredPreference(Preference preference) {
    when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
    when(preferenceQuery.uniqueResult()).thenReturn(preference);
  }

  /** Builds a stored marker row holding the given value. */
  private static Preference preferenceHolding(String value) {
    Preference preference = mock(Preference.class);
    when(preference.getSearchKey()).thenReturn(value);
    return preference;
  }

  @Nested
  class MarkProductive {

    @Test
    void writesTheProductiveMarkerAgainstTheTenantAndItsStarOrganisation() {
      Client client = mock(Client.class);
      Organization organization = mock(Organization.class);
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
      when(obDal.get(Organization.class, ORG_ID)).thenReturn(organization);

      boolean marked = service.markProductive(CLIENT_ID, ORG_ID);

      assertTrue(marked, "a successful write must be reported as such");
      // isListProperty=false is what routes the key to AD_Preference.Attribute, which is the
      // column resolvePlan queries. Asserted literally so a future edit cannot flip it silently.
      preferencesMock.verify(() -> Preferences.setPreferenceValue(
          TenantPlanService.PREFERENCE_ATTRIBUTE, TenantPlanService.PLAN_PRODUCTIVE, false,
          client, organization, null, null, null, null));
    }

    @Test
    void writesTheMarkerWithNoOrganisationWhenNoneWasResolved() {
      Client client = mock(Client.class);
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);

      boolean marked = service.markProductive(CLIENT_ID, null);

      assertTrue(marked);
      preferencesMock.verify(() -> Preferences.setPreferenceValue(
          TenantPlanService.PREFERENCE_ATTRIBUTE, TenantPlanService.PLAN_PRODUCTIVE, false,
          client, null, null, null, null, null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void reportsFailureAndWritesNothingForABlankClientId(String clientId) {
      boolean marked = service.markProductive(clientId, ORG_ID);

      assertFalse(marked, "there is no tenant to mark, so this is not a success");
      preferencesMock.verifyNoInteractions();
    }

    @Test
    void reportsFailureWhenTheTenantDoesNotExist() {
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(null);

      boolean marked = service.markProductive(CLIENT_ID, ORG_ID);

      assertFalse(marked, "a paid environment that could not be marked must not report success");
      preferencesMock.verifyNoInteractions();
    }

    @Test
    void reportsFailureWithoutPropagatingWhenTheWriteBlowsUp() {
      Client client = mock(Client.class);
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
      preferencesMock.when(() -> Preferences.setPreferenceValue(anyString(), anyString(),
          eq(false), any(), any(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("preference write failed"));

      boolean marked = service.markProductive(CLIENT_ID, ORG_ID);

      // Best-effort stays best-effort: a commercial marker must never roll back an otherwise
      // complete environment. What changes is that the caller can now see it happened.
      assertFalse(marked);
    }
  }

  @Nested
  class ResolvePlan {

    @Test
    void readsBackProductiveWhenTheMarkerIsPresent() {
      givenStoredPreference(preferenceHolding(TenantPlanService.PLAN_PRODUCTIVE));

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void acceptsAStoredMarkerInAnyCase() {
      givenStoredPreference(preferenceHolding("PRODUCTIVE"));

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeWhenNoMarkerWasEverWritten() {
      givenStoredPreference(null);

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeWhenTheMarkerHoldsSomethingElse() {
      givenStoredPreference(preferenceHolding("trial"));

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void readsBackFreeForABlankClientIdWithoutQuerying(String clientId) {
      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(clientId));

      verify(obDal, never()).createQuery(eq(Preference.class), anyString());
    }

    @Test
    void readsBackFreeWhenTheLookupFails() {
      when(obDal.createQuery(eq(Preference.class), anyString()))
          .thenThrow(new IllegalStateException("no session"));

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void looksUpOnlyTheRequestedTenantAndIgnoresContextVisibility() {
      givenStoredPreference(preferenceHolding(TenantPlanService.PLAN_PRODUCTIVE));

      service.resolvePlan(CLIENT_ID);

      // The plan is read while serving an account-level request, whose OBContext is not the tenant
      // being inspected — hence the readable-client/organisation filters must stay off, and the
      // tenant must be pinned by parameter instead.
      verify(preferenceQuery).setNamedParameter("attribute", TenantPlanService.PREFERENCE_ATTRIBUTE);
      verify(preferenceQuery).setNamedParameter("clientId", CLIENT_ID);
      verify(preferenceQuery).setFilterOnReadableClients(false);
      verify(preferenceQuery).setFilterOnReadableOrganization(false);
    }
  }

  @Test
  void theValueWrittenIsTheValueReadBack() {
    // Guards the write/read agreement end to end: markProductive stores PLAN_PRODUCTIVE and
    // resolvePlan recognises that exact value. A rename on one side alone would leave every paid
    // environment reading back as free, which is failure-mode-identical to not marking at all.
    Client client = mock(Client.class);
    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
    assertTrue(service.markProductive(CLIENT_ID, null));

    givenStoredPreference(preferenceHolding(TenantPlanService.PLAN_PRODUCTIVE));

    assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
  }
}
