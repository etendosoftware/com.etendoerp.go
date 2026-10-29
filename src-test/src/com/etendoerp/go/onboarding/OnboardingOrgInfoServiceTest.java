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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Unit tests for {@link OnboardingOrgInfoService}.
 *
 * @covers com.etendoerp.go.onboarding.OnboardingOrgInfoService
 */
public class OnboardingOrgInfoServiceTest {

  /** The account {@code structured-address-for-sepa} is targeted at in these tests. */
  private static final String TARGETED_ACCOUNT = "localization@etendo.software";

  /** Only the fields {@code createLocation}/{@code applyAddressFields} read. */
  private static OrgInfoInput addressInput(String address, String streetName,
      String buildingNumber, String buildingName, String cityName, String accountEmail) {
    return new OrgInfoInput(null, address, streetName, buildingNumber, buildingName, cityName,
        null, accountEmail);
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureOrgInfoFailsWhenClientIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo(null, "ORG-1", "USER-1", "ROLE-1", new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected missing clientId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenOrgIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo("CLIENT-1", null, "USER-1", "ROLE-1", new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected missing orgId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", null, "ROLE-1", new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenAdminRoleIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", null, new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  // ---------------------------------------------------------------------------
  // ensureOrgInfo orchestration
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureOrgInfoFailsWhenClientNotFound() {
    TestableService service = new TestableService();
    service.client = null;
    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected client-not-found failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Client not found for org-info setup"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenOrganizationNotFound() {
    TestableService service = new TestableService();
    service.organization = null;
    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected organization-not-found failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found for org-info setup"));
    }
  }

  @Test
  public void testEnsureOrgInfoDelegatesToLocationAndFlushes() {
    TestableService service = new TestableService();
    service.overrideLocation = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", new OrgInfoInput("ES", "Main St 1", null, null, null, null, null, null));

    assertTrue(service.ensureLocationCalled);
    assertTrue(service.flushed);
    assertSame(previous, OBContext.getOBContext());
  }

  @Test
  public void testEnsureOrgInfoRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.overrideLocation = true;
    service.failOnLocation = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", new OrgInfoInput("ES", null, null, null, null, null, null, null));
      fail("Expected delegated failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("location-boom"));
    }

    assertSame(previous, OBContext.getOBContext());
  }

  // ---------------------------------------------------------------------------
  // resolveCountry branches
  // ---------------------------------------------------------------------------

  @Test
  public void testResolveCountryReturnsRequestedIsoWhenPresent() {
    Country fr = mock(Country.class);
    TestableService service = new TestableService();
    service.countryByIso.put("FR", fr);

    assertSame(fr, service.resolveCountry("FR"));
  }

  @Test
  public void testResolveCountryFallsBackToDefaultIsoWhenRequestedMissing() {
    Country es = mock(Country.class);
    TestableService service = new TestableService();
    // "FR" is absent, "ES" (DEFAULT_COUNTRY_ISO) is present.
    service.countryByIso.put("ES", es);

    assertSame(es, service.resolveCountry("FR"));
  }

  @Test
  public void testResolveCountryFallsBackToAnyActiveWhenNeitherResolves() {
    Country anyActive = mock(Country.class);
    TestableService service = new TestableService();
    // Neither "FR" nor "ES" present; only the any-active fallback resolves.
    service.anyActiveCountry = anyActive;

    assertSame(anyActive, service.resolveCountry("FR"));
  }

  @Test
  public void testResolveCountryBlankIsoResolvesViaDefaultIso() {
    Country es = mock(Country.class);
    TestableService service = new TestableService();
    service.countryByIso.put("ES", es);

    assertSame(es, service.resolveCountry("   "));
    assertSame(es, service.resolveCountry(null));
  }

  // ---------------------------------------------------------------------------
  // ensureOrgInfoLocation branches (uses the real ensureOrgInfoLocation method)
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureOrgInfoLocationReturnsEarlyWhenAlreadyLocated() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      TestableService service = new TestableService();
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(mock(Location.class));
      service.orgInfo = orgInfo;

      service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", null, null, null, null, null, null, null));

      // Already located: no location created, no link, no save.
      assertNull(service.createdLocation);
      verify(orgInfo, never()).setLocationAddress(any());
      verify(dal, never()).save(any());
    }
  }

  /**
   * ETP-5806 follow-up — <b>pooled onboarding dropped the whole structured address.</b> A tenant
   * claimed from the pool (ETP-5389) arrives with the fiscal location its pool build created:
   * linked to the org-info, but with no address at all ({@code TenantPoolProvisioner} provisions
   * the chain with eight empty address strings). The "already located, leave it untouched" early
   * return therefore skipped {@code createLocation} — the only place the 3 SEPA fields, the
   * composed Address1/Address2 and the city are ever written — so a targeted account's signup lost
   * every one of them, with no error and no log, moments after the servlet had made the user type
   * Street/Number/City as mandatory.
   */
  @Test
  public void testEnsureOrgInfoLocationFillsAPooledTenantsBlankLocation() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // On for the claiming account only — the gating must reach this branch too.
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
          argThat(ctx -> ctx != null && TARGETED_ACCOUNT.equals(ctx.getTargetingKey()))))
          .thenReturn(true);

      TestableService service = new TestableService();
      Location pooledLocation = mock(Location.class); // every address getter answers null
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(pooledLocation);
      service.orgInfo = orgInfo;

      service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", null, "Gran Via",
          "45", "Torre A", "Madrid", null, TARGETED_ACCOUNT));

      verify(pooledLocation).setOBSEPAStreetName("Gran Via");
      verify(pooledLocation).setOBSEPABuildingNumber("45");
      verify(pooledLocation).setOBSEPABuildingName("Torre A");
      verify(pooledLocation).setAddressLine1("Gran Via, 45 - Torre A");
      verify(pooledLocation).setCityName("Madrid");
      verify(dal).save(pooledLocation);
      // The org-info already points at it; relinking is not this branch's job.
      verify(orgInfo, never()).setLocationAddress(any());
      // No second location is created for an org that already has one.
      assertNull(service.createdLocation);
    }
  }

  /**
   * ETP-5806 follow-up — the untargeted account on the same pooled path keeps the pre-ETP-5806
   * behaviour: the legacy free-text address populates Address1 and no SEPA column is written.
   */
  @Test
  public void testEnsureOrgInfoLocationFillsABlankLocationFromTheLegacyAddressWhenUntargeted() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

      TestableService service = new TestableService();
      Location pooledLocation = mock(Location.class);
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(pooledLocation);
      service.orgInfo = orgInfo;

      service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", "Calle Mayor 1",
          "Gran Via", "45", "Torre A", "Madrid", null, "someone.else@example.com"));

      verify(pooledLocation).setAddressLine1("Calle Mayor 1");
      verify(pooledLocation, never()).setOBSEPAStreetName(any());
      verify(pooledLocation, never()).setOBSEPABuildingNumber(any());
      verify(pooledLocation, never()).setOBSEPABuildingName(any());
      verify(pooledLocation).setCityName("Madrid");
      verify(dal).save(pooledLocation);
    }
  }

  /**
   * The counterpart guard: an existing location that already carries an address is left exactly as
   * it is. Only a blank one — which is what a pooled tenant's always is — is completed, so this
   * can never overwrite an address set manually in the UI.
   */
  @Test
  public void testEnsureOrgInfoLocationNeverOverwritesAPopulatedLocation() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);

      TestableService service = new TestableService();
      Location located = mock(Location.class);
      when(located.getAddressLine1()).thenReturn("Calle Real 7");
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(located);
      service.orgInfo = orgInfo;

      service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", null, "Gran Via",
          "45", "Torre A", "Madrid", null, TARGETED_ACCOUNT));

      verify(located, never()).setAddressLine1(anyString());
      verify(located, never()).setOBSEPAStreetName(any());
      verify(located, never()).setCityName(anyString());
      verify(dal, never()).save(any());
    }
  }

  @Test
  public void testEnsureOrgInfoLocationThrowsWhenNoCountryAvailable() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

      TestableService service = new TestableService();
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(null);
      service.orgInfo = orgInfo;
      service.resolvedCountry = null; // resolveCountry yields nothing

      try {
        service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", null, null, null, null, null, null, null));
        fail("Expected no-country failure");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("No country available"));
      }
    }
  }

  @Test
  public void testEnsureOrgInfoLocationCreatesAndLinksWhenOrgInfoNull() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      TestableService service = new TestableService();
      OrganizationInformation createdOrgInfo = mock(OrganizationInformation.class);
      when(createdOrgInfo.getLocationAddress()).thenReturn(null);
      Location location = mock(Location.class);
      Country country = mock(Country.class);

      service.orgInfo = null;                // resolveOrgInfo returns null
      service.createdOrgInfo = createdOrgInfo; // createOrgInfo returns this
      service.resolvedCountry = country;
      service.locationToCreate = location;

      service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", "Main St 1", null, null, null, null, null, null));

      assertTrue(service.createOrgInfoCalled);
      assertSame(location, service.createdLocation);
      verify(createdOrgInfo).setLocationAddress(location);
      verify(dal).save(createdOrgInfo);
    }
  }

  // ---------------------------------------------------------------------------
  // applyTaxId() — ETP-4749: the wizard's Tax ID is optional and must never be
  // forced/cleared, only persisted when the onboarding request actually carries one.
  // ---------------------------------------------------------------------------

  @Test
  public void testApplyTaxIdSetsTrimmedValueAndSavesWhenNonBlank() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      OnboardingOrgInfoService service = new OnboardingOrgInfoService();
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);

      service.applyTaxId(orgInfo, "  1234  ");

      verify(orgInfo).setTaxID("1234");
      verify(dal).save(orgInfo);
    }
  }

  @Test
  public void testApplyTaxIdIsNoOpWhenBlank() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      OnboardingOrgInfoService service = new OnboardingOrgInfoService();
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);

      service.applyTaxId(orgInfo, "   ");
      service.applyTaxId(orgInfo, null);

      verify(orgInfo, never()).setTaxID(anyString());
      verify(dal, never()).save(any());
    }
  }

  @Test
  public void testEnsureOrgInfoLocationAppliesTaxIdBeforeTheAlreadyLocatedEarlyReturn() {
    // Regression guard: applyTaxId() must run even when the org already has a location
    // (the pre-existing early return in ensureOrgInfoLocation must not skip it).
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      TestableService service = new TestableService();
      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(mock(Location.class));
      service.orgInfo = orgInfo;

      service.ensureOrgInfoLocation(service.client, service.organization, new OrgInfoInput("ES", null, null, null, null, null, "5678", null));

      verify(orgInfo).setTaxID("5678");
      // Still no location work — that early return is unrelated to the Tax ID.
      verify(orgInfo, never()).setLocationAddress(any());
    }
  }

  // ---------------------------------------------------------------------------
  // resolveOrgInfo() / resolveClient() — real OBDal.get delegation
  // ---------------------------------------------------------------------------

  @Test
  public void testResolveOrgInfoFetchesBySharedPrimaryKey() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(orgInfo, service.resolveOrgInfo(org));
    }
  }

  @Test
  public void testResolveClientDelegatesToObDalGet() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Client.class, "CLIENT-1")).thenReturn(client);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(client, service.resolveClient("CLIENT-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // createOrgInfo() — real OBProvider-backed body (shared PK)
  // ---------------------------------------------------------------------------

  @Test
  public void testCreateOrgInfoSetsSharedPrimaryKeyAndSaves() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");

    OBProvider provider = mock(OBProvider.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(provider.get(OrganizationInformation.class)).thenReturn(orgInfo);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(orgInfo, service.createOrgInfo(client, org));
    }

    verify(orgInfo).setNewOBObject(true);
    verify(orgInfo).setId("ORG-1");
    verify(orgInfo).setClient(client);
    verify(orgInfo).setOrganization(org);
    verify(orgInfo).setActive(true);
    verify(dal).save(orgInfo);
  }

  // ---------------------------------------------------------------------------
  // createLocation() — real OBProvider-backed body, with and without address
  // ---------------------------------------------------------------------------

  @Test
  public void testCreateLocationSetsAddressWhenProvided() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(location, service.createLocation(client, org, country, addressInput("  Main St 1  ", null, null, null, null,
          "lola@example.com")));
    }

    verify(location).setNewOBObject(true);
    verify(location).setClient(client);
    verify(location).setOrganization(org);
    verify(location).setActive(true);
    verify(location).setCountry(country);
    verify(location).setAddressLine1("Main St 1"); // trimmed
    verify(dal).save(location);
  }

  @Test
  public void testCreateLocationSkipsAddressWhenBlank() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.createLocation(client, org, country, addressInput("   ", null, null, null, null,
          "lola@example.com"));
    }

    verify(location, never()).setAddressLine1(anyString());
    verify(dal).save(location);
  }

  /**
   * ETP-5806 — the structured fields take priority over the legacy free-text {@code address}
   * whenever {@code streetName} is non-blank: the composed Address1/Address2 and the 3 structured
   * SEPA columns are set, and the raw legacy address is never touched.
   */
  @Test
  public void testCreateLocationStructuredFieldsTakePriorityOverLegacyAddress() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);
      service.createLocation(client, org, country, addressInput("Legacy free text — must be ignored",
          "Gran Via", "45", "Torre A", "Madrid", "lola@example.com"));
    }

    verify(location).setOBSEPAStreetName("Gran Via");
    verify(location).setOBSEPABuildingNumber("45");
    verify(location).setOBSEPABuildingName("Torre A");
    verify(location).setAddressLine1("Gran Via, 45 - Torre A");
    verify(location, never()).setAddressLine2(anyString());
    verify(location).setCityName("Madrid");
    verify(location, never()).setAddressLine1("Legacy free text — must be ignored");
    verify(dal).save(location);
  }

  /**
   * ETP-5806 BUG-1 (fixed in ecd181dd8) — flag OFF: the 3 structured SEPA fields are never
   * persisted, even when all three are fully populated and no legacy {@code address} was given.
   * City is independent of the flag and is still set.
   */
  @Test
  public void testCreateLocationFlagOffNeverPersistsStructuredFieldsWithNoLegacyAddress() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

      service.createLocation(client, org, country, addressInput(null, "Gran Via", "45", "Torre A", "Madrid",
          "lola@example.com"));
    }

    verify(location, never()).setOBSEPAStreetName(any());
    verify(location, never()).setOBSEPABuildingNumber(any());
    verify(location, never()).setOBSEPABuildingName(any());
    verify(location, never()).setAddressLine1(anyString());
    verify(location, never()).setAddressLine2(anyString());
    verify(location).setCityName("Madrid");
    verify(dal).save(location);
  }

  /**
   * ETP-5806 BUG-1 (fixed in ecd181dd8) — flag OFF with a legacy {@code address} also present
   * alongside the 3 structured fields: the pre-ETP-5806 fallback persists Address1 from the
   * legacy value, never from the structured composer.
   */
  @Test
  public void testCreateLocationFlagOffFallsBackToLegacyAddressWhenStructuredFieldsAlsoPresent() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);

      service.createLocation(client, org, country, addressInput("Legacy free text", "Gran Via", "45",
          "Torre A", "Madrid", "lola@example.com"));
    }

    verify(location).setAddressLine1("Legacy free text");
    verify(location, never()).setOBSEPAStreetName(any());
    verify(location, never()).setOBSEPABuildingNumber(any());
    verify(location, never()).setOBSEPABuildingName(any());
    verify(location, never()).setAddressLine2(anyString());
    verify(location).setCityName("Madrid");
  }

  /**
   * City is independent of which branch populated the address lines: it must be set even when
   * nothing else about the address block was provided.
   */
  @Test
  public void testCreateLocationSetsCityNameEvenWithoutAnyAddress() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.createLocation(client, org, country, addressInput(null, null, null, null, "Sevilla",
          "lola@example.com"));
    }

    verify(location).setCityName("Sevilla");
    verify(location, never()).setAddressLine1(anyString());
  }

  @Test
  public void testCreateLocationTrimsBlankBuildingNumberAndNameToNull() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(true);
      service.createLocation(client, org, country, addressInput(null, "Gran Via", "  ", "   ", "Madrid",
          "lola@example.com"));
    }

    verify(location).setOBSEPABuildingNumber(isNull());
    verify(location).setOBSEPABuildingName(isNull());
  }

  /**
   * ETP-5806 follow-up — provisioning persists the structured fields for the ONBOARDING ACCOUNT's
   * own flag answer. The account email is carried in from the request (provisioning runs under an
   * admin execution context with no caller identity left to recover), so a targeted account's
   * Street/Number survive to the C_Location while an untargeted one in the same environment keeps
   * the legacy free-text behaviour. Without this, the servlet validated the block per account and
   * then this step dropped exactly what it had just accepted.
   */
  @Test
  public void testCreateLocationPersistsStructuredFieldsOnlyForTheTargetedAccount() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);

    OBProvider provider = mock(OBProvider.class);
    Location targetedLocation = mock(Location.class);
    Location otherLocation = mock(Location.class);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // True for the targeted account only — every other key, and the account-less context this
      // used to evaluate with, resolve false.
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA), any())).thenReturn(false);
      flags.when(() -> GoFeatureFlags.isEnabled(
          eq(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA),
          argThat(ctx -> ctx != null
              && TARGETED_ACCOUNT.equals(ctx.getTargetingKey())))).thenReturn(true);

      when(provider.get(Location.class)).thenReturn(targetedLocation);
      service.createLocation(client, org, country, addressInput("Legacy free text", "Gran Via", "45", "Torre A",
          "Madrid", TARGETED_ACCOUNT));

      when(provider.get(Location.class)).thenReturn(otherLocation);
      service.createLocation(client, org, country, addressInput("Legacy free text", "Gran Via", "45", "Torre A",
          "Madrid", "someone.else@example.com"));
    }

    verify(targetedLocation).setOBSEPAStreetName("Gran Via");
    verify(targetedLocation).setOBSEPABuildingNumber("45");
    verify(targetedLocation).setAddressLine1("Gran Via, 45 - Torre A");

    verify(otherLocation, never()).setOBSEPAStreetName(any());
    verify(otherLocation, never()).setOBSEPABuildingNumber(any());
    verify(otherLocation).setAddressLine1("Legacy free text");
  }

  // ---------------------------------------------------------------------------
  // findCountryByIso() / findAnyActiveCountry() — real OBCriteria bodies
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testFindCountryByIsoReturnsUniqueResult() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Country country = mock(Country.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Country> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Country.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(country);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(country, service.findCountryByIso("ES"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFindAnyActiveCountryReturnsUniqueResult() {
    OnboardingOrgInfoService service = new OnboardingOrgInfoService();
    Country country = mock(Country.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Country> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Country.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(country);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(country, service.findAnyActiveCountry());
    }
  }

  // ---------------------------------------------------------------------------
  // contextSubject()
  // ---------------------------------------------------------------------------

  @Test
  public void testContextSubjectIsOrgInfoSetup() {
    assertEquals("org-info setup", new OnboardingOrgInfoService().contextSubject());
  }

  // ---------------------------------------------------------------------------
  // Testable subclass: overrides the protected DB seams.
  // ---------------------------------------------------------------------------

  private static final class TestableService extends OnboardingOrgInfoService {
    private Client client = mock(Client.class);
    private Organization organization = mock(Organization.class);

    // ensureOrgInfo orchestration flags
    boolean overrideLocation;
    boolean failOnLocation;
    boolean ensureLocationCalled;
    boolean flushed;

    // ensureOrgInfoLocation seams
    OrganizationInformation orgInfo;        // resolveOrgInfo result
    OrganizationInformation createdOrgInfo; // createOrgInfo result
    boolean createOrgInfoCalled;
    Country resolvedCountry;
    Location locationToCreate;
    Location createdLocation;

    // resolveCountry seams
    final java.util.Map<String, Country> countryByIso = new java.util.HashMap<>();
    Country anyActiveCountry;

    private TestableService() {
      when(client.getId()).thenReturn("CLIENT-1");
      when(organization.getId()).thenReturn("ORG-1");
    }

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

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected Client resolveClient(String clientId) {
      return client;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return organization;
    }

    @Override
    protected void ensureOrgInfoLocation(Client client, Organization org, OrgInfoInput input) {
      if (overrideLocation) {
        ensureLocationCalled = true;
        if (failOnLocation) {
          throw new OBException("location-boom");
        }
        return;
      }
      super.ensureOrgInfoLocation(client, org, input);
    }

    @Override
    protected OrganizationInformation resolveOrgInfo(Organization org) {
      return orgInfo;
    }

    @Override
    protected OrganizationInformation createOrgInfo(Client client, Organization org) {
      createOrgInfoCalled = true;
      return createdOrgInfo;
    }

    @Override
    protected Location createLocation(Client client, Organization org, Country country,
        OrgInfoInput input) {
      createdLocation = locationToCreate;
      return locationToCreate;
    }

    @Override
    protected Country resolveCountry(String countryIso) {
      // When a test explicitly drives the resolveCountry branches, exercise the real
      // implementation through the findCountryByIso / findAnyActiveCountry seams.
      // When a test only needs ensureOrgInfoLocation, return the canned resolvedCountry.
      if (!countryByIso.isEmpty() || anyActiveCountry != null) {
        return super.resolveCountry(countryIso);
      }
      return resolvedCountry;
    }

    @Override
    protected Country findCountryByIso(String iso) {
      return countryByIso.get(iso);
    }

    @Override
    protected Country findAnyActiveCountry() {
      return anyActiveCountry;
    }
  }
}
