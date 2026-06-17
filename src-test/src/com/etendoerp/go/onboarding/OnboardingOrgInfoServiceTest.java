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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;

public class OnboardingOrgInfoServiceTest {

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureOrgInfoFailsWhenClientIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo(null, "ORG-1", "USER-1", "ROLE-1", "ES", null);
      fail("Expected missing clientId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenOrgIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo("CLIENT-1", null, "USER-1", "ROLE-1", "ES", null);
      fail("Expected missing orgId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", null, "ROLE-1", "ES", null);
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testEnsureOrgInfoFailsWhenAdminRoleIsMissing() {
    TestableService service = new TestableService();
    try {
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", null, "ES", null);
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
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", "ES", null);
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
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", "ES", null);
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

    service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", "ES", "Main St 1");

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
      service.ensureOrgInfo("CLIENT-1", "ORG-1", "USER-1", "ROLE-1", "ES", null);
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

      service.ensureOrgInfoLocation(service.client, service.organization, "ES", null);

      // Already located: no location created, no link, no save.
      assertNull(service.createdLocation);
      verify(orgInfo, never()).setLocationAddress(org.mockito.ArgumentMatchers.any());
      verify(dal, never()).save(org.mockito.ArgumentMatchers.any());
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
        service.ensureOrgInfoLocation(service.client, service.organization, "ES", null);
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

      service.ensureOrgInfoLocation(service.client, service.organization, "ES", "Main St 1");

      assertTrue(service.createOrgInfoCalled);
      assertSame(location, service.createdLocation);
      verify(createdOrgInfo).setLocationAddress(location);
      verify(dal).save(createdOrgInfo);
    }
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
    protected void ensureOrgInfoLocation(Client client, Organization org, String countryIso,
        String address) {
      if (overrideLocation) {
        ensureLocationCalled = true;
        if (failOnLocation) {
          throw new OBException("location-boom");
        }
        return;
      }
      super.ensureOrgInfoLocation(client, org, countryIso, address);
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
        String address) {
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
