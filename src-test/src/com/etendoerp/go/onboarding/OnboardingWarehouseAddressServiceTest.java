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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.common.WarehouseLookupHelper;

public class OnboardingWarehouseAddressServiceTest {

  // ---------------------------------------------------------------------------
  // alignDefaultWarehouseAddress() orchestration — validation, client/org lookup,
  // context restore. Mirrors OnboardingOrgInfoServiceTest's TestableService pattern:
  // alignWarehouseAddress(client, org) is stubbed as a seam so these tests stay DAL-free.
  // ---------------------------------------------------------------------------

  @Test
  public void testAlignDefaultWarehouseAddressFailsWhenClientIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.alignDefaultWarehouseAddress(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected missing clientId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressFailsWhenOrgIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.alignDefaultWarehouseAddress("CLIENT-1", null, "USER-1", "ROLE-1");
      fail("Expected missing orgId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();
    try {
      service.alignDefaultWarehouseAddress("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressFailsWhenAdminRoleIsMissing() {
    TestableService service = new TestableService();
    try {
      service.alignDefaultWarehouseAddress("CLIENT-1", "ORG-1", "USER-1", null);
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressFailsWhenClientNotFound() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, "CLIENT-1")).thenReturn(null);

      TestableService service = new TestableService();

      try {
        service.alignDefaultWarehouseAddress("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
        fail("Expected client-not-found failure");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("Client not found for warehouse-address alignment"));
      }
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressFailsWhenOrganizationNotFound() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, "CLIENT-1")).thenReturn(mock(Client.class));

      TestableService service = new TestableService();
      service.organization = null;

      try {
        service.alignDefaultWarehouseAddress("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
        fail("Expected organization-not-found failure");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("Organization not found for warehouse-address alignment"));
      }
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressDelegatesAndFlushes() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Client client = mock(Client.class);
      when(dal.get(Client.class, "CLIENT-1")).thenReturn(client);

      TestableService service = new TestableService();
      OBContext previous = mock(OBContext.class);
      OBContext.setOBContext(previous);

      service.alignDefaultWarehouseAddress("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

      assertTrue(service.alignCalled);
      assertSame(client, service.alignedClient);
      assertSame(service.organization, service.alignedOrg);
      assertTrue(service.flushed);
      assertSame(previous, OBContext.getOBContext());
    }
  }

  @Test
  public void testAlignDefaultWarehouseAddressRestoresPreviousContextAfterFailure() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, "CLIENT-1")).thenReturn(mock(Client.class));

      TestableService service = new TestableService();
      service.failOnAlign = true;
      OBContext previous = mock(OBContext.class);
      OBContext.setOBContext(previous);

      try {
        service.alignDefaultWarehouseAddress("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
        fail("Expected delegated failure");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("align-boom"));
      }

      assertSame(previous, OBContext.getOBContext());
    }
  }

  @Test
  public void testContextSubjectIsWarehouseAddressAlignment() {
    assertEquals("warehouse-address alignment",
        new OnboardingWarehouseAddressService().contextSubject());
  }

  // ---------------------------------------------------------------------------
  // alignWarehouseAddress() — the real behavioral core, exercised directly with
  // OBDal/OBProvider/WarehouseLookupHelper mocked statically.
  // ---------------------------------------------------------------------------

  @Test
  public void testAlignWarehouseAddressCopiesAllFieldsExceptCountryOntoExistingLocation() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      Location fiscal = mock(Location.class);
      when(fiscal.getAddressLine1()).thenReturn("Fiscal Line 1");
      when(fiscal.getAddressLine2()).thenReturn("Fiscal Line 2");
      when(fiscal.getCityName()).thenReturn("Fiscal City");
      when(fiscal.getPostalCode()).thenReturn("28080");
      when(fiscal.getPostalAdd()).thenReturn("Fiscal PostalAdd");
      when(fiscal.getRegion()).thenReturn(mock(org.openbravo.model.common.geography.Region.class));
      when(fiscal.getRegionName()).thenReturn("Fiscal Region");
      when(fiscal.getCity()).thenReturn(mock(org.openbravo.model.common.geography.City.class));
      when(fiscal.getId()).thenReturn("LOCATION-FISCAL");
      when(fiscal.getCountry()).thenReturn(mock(Country.class));

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(fiscal);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      Warehouse warehouse = mock(Warehouse.class);
      Location warehouseLocation = mock(Location.class);
      when(warehouseLocation.getId()).thenReturn("LOCATION-WAREHOUSE");
      when(warehouse.getLocationAddress()).thenReturn(warehouseLocation);
      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(warehouse);

      service.alignWarehouseAddress(client, org);

      verify(warehouseLocation).setAddressLine1("Fiscal Line 1");
      verify(warehouseLocation).setAddressLine2("Fiscal Line 2");
      verify(warehouseLocation).setCityName("Fiscal City");
      verify(warehouseLocation).setPostalCode("28080");
      verify(warehouseLocation).setPostalAdd("Fiscal PostalAdd");
      verify(warehouseLocation).setRegion(fiscal.getRegion());
      verify(warehouseLocation).setRegionName("Fiscal Region");
      verify(warehouseLocation).setCity(fiscal.getCity());
      verify(warehouseLocation, never()).setCountry(any());
      verify(dal).save(warehouseLocation);
      // No new location was created and no warehouse re-save was needed.
      verify(warehouse, never()).setLocationAddress(any());
    }
  }

  @Test
  public void testAlignWarehouseAddressOverwritesWithNullWhenFiscalAddressIsBlank() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      // Fiscal location with every field left null — an empty wizard "Address" step.
      Location fiscal = mock(Location.class);
      when(fiscal.getId()).thenReturn("LOCATION-FISCAL");
      when(fiscal.getCountry()).thenReturn(mock(Country.class));

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(fiscal);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      Warehouse warehouse = mock(Warehouse.class);
      Location warehouseLocation = mock(Location.class);
      when(warehouseLocation.getId()).thenReturn("LOCATION-WAREHOUSE");
      when(warehouse.getLocationAddress()).thenReturn(warehouseLocation);
      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(warehouse);

      service.alignWarehouseAddress(client, org);

      // Blank in, blank(null) out — the null is copied as-is, not skipped.
      verify(warehouseLocation).setAddressLine1(null);
      verify(warehouseLocation).setAddressLine2(null);
      verify(warehouseLocation).setCityName(null);
      verify(warehouseLocation).setPostalCode(null);
      verify(warehouseLocation).setPostalAdd(null);
      verify(warehouseLocation).setRegion(null);
      verify(warehouseLocation).setRegionName(null);
      verify(warehouseLocation).setCity(null);
      verify(warehouseLocation, never()).setCountry(any());
      verify(dal).save(warehouseLocation);
    }
  }

  @Test
  public void testAlignWarehouseAddressUnconditionallyOverwritesExistingNonBlankValues() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      Location fiscal = mock(Location.class);
      when(fiscal.getAddressLine1()).thenReturn("New Address");
      when(fiscal.getId()).thenReturn("LOCATION-FISCAL");
      when(fiscal.getCountry()).thenReturn(mock(Country.class));

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(fiscal);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      Warehouse warehouse = mock(Warehouse.class);
      // Placeholder warehouse location (e.g. sampledata "Avenida Siempreviva 44") — must be
      // replaced unconditionally, not merged/skipped because it already has a value.
      Location warehouseLocation = mock(Location.class);
      when(warehouseLocation.getId()).thenReturn("LOCATION-WAREHOUSE");
      when(warehouseLocation.getAddressLine1()).thenReturn("Avenida Siempreviva 44");
      when(warehouse.getLocationAddress()).thenReturn(warehouseLocation);
      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(warehouse);

      service.alignWarehouseAddress(client, org);

      verify(warehouseLocation).setAddressLine1("New Address");
      verify(dal).save(warehouseLocation);
    }
  }

  @Test
  public void testAlignWarehouseAddressCreatesNewLocationWhenWarehouseHasNone() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      Country fiscalCountry = mock(Country.class);
      Location fiscal = mock(Location.class);
      when(fiscal.getAddressLine1()).thenReturn("Fiscal Line 1");
      when(fiscal.getId()).thenReturn("LOCATION-FISCAL");
      when(fiscal.getCountry()).thenReturn(fiscalCountry);

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(fiscal);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      Warehouse warehouse = mock(Warehouse.class);
      when(warehouse.getLocationAddress()).thenReturn(null);
      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(warehouse);

      Location newLocation = mock(Location.class);
      when(provider.get(Location.class)).thenReturn(newLocation);

      service.alignWarehouseAddress(client, org);

      verify(newLocation).setNewOBObject(true);
      verify(newLocation).setClient(client);
      verify(newLocation).setOrganization(org);
      verify(newLocation).setActive(true);
      verify(newLocation).setCountry(fiscalCountry);
      verify(warehouse).setLocationAddress(newLocation);
      verify(dal).save(warehouse);
      // Field values are still copied onto the freshly created location.
      verify(newLocation).setAddressLine1("Fiscal Line 1");
      // Saved twice: once inside createWarehouseLocation(), once more after copyAddressFields().
      verify(dal, times(2)).save(newLocation);
    }
  }

  @Test
  public void testAlignWarehouseAddressDoesNothingWhenWarehouseLocationIsSameRecordAsFiscal() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      Location sharedLocation = mock(Location.class);
      when(sharedLocation.getId()).thenReturn("LOCATION-SHARED");

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(sharedLocation);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      Warehouse warehouse = mock(Warehouse.class);
      when(warehouse.getLocationAddress()).thenReturn(sharedLocation);
      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(warehouse);

      service.alignWarehouseAddress(client, org);

      verify(sharedLocation, never()).setAddressLine1(any());
      verify(warehouse, never()).setLocationAddress(any());
      verify(dal, never()).save(any());
    }
  }

  @Test
  public void testAlignWarehouseAddressDoesNothingWhenNoActiveWarehouseFound() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      Location fiscal = mock(Location.class);
      when(fiscal.getId()).thenReturn("LOCATION-FISCAL");

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(fiscal);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(null);

      // Must not throw.
      service.alignWarehouseAddress(client, org);

      verify(dal, never()).save(any());
    }
  }

  @Test
  public void testAlignWarehouseAddressThrowsWhenFiscalLocationMissingBecauseOrgInfoIsNull() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(null);

      try {
        service.alignWarehouseAddress(client, org);
        fail("Expected missing fiscal address to fail");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("No fiscal address found"));
      }
    }
  }

  @Test
  public void testAlignWarehouseAddressThrowsWhenOrgInfoIsUnlocated() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(null);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      try {
        service.alignWarehouseAddress(client, org);
        fail("Expected unlocated org-info to fail");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("No fiscal address found"));
      }
    }
  }

  @Test
  public void testAlignWarehouseAddressIsIdempotentAcrossTwoRuns() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<WarehouseLookupHelper> whHelper = mockStatic(WarehouseLookupHelper.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");

      Location fiscal = mock(Location.class);
      when(fiscal.getAddressLine1()).thenReturn("Stable Address");
      when(fiscal.getId()).thenReturn("LOCATION-FISCAL");
      when(fiscal.getCountry()).thenReturn(mock(Country.class));

      OrganizationInformation orgInfo = mock(OrganizationInformation.class);
      when(orgInfo.getLocationAddress()).thenReturn(fiscal);
      when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

      Warehouse warehouse = mock(Warehouse.class);
      Location warehouseLocation = mock(Location.class);
      when(warehouseLocation.getId()).thenReturn("LOCATION-WAREHOUSE");
      when(warehouse.getLocationAddress()).thenReturn(warehouseLocation);
      whHelper.when(() -> WarehouseLookupHelper.findFirstActiveWarehouse(client, org))
          .thenReturn(warehouse);

      service.alignWarehouseAddress(client, org);
      service.alignWarehouseAddress(client, org);

      // Re-applying the same fiscal values is a no-op in effect: both runs set the same value.
      verify(warehouseLocation, times(2)).setAddressLine1("Stable Address");
      verify(dal, times(2)).save(warehouseLocation);
    }
  }

  // ---------------------------------------------------------------------------
  // resolveFiscalLocation() / createWarehouseLocation() / copyAddressFields() — direct
  // ---------------------------------------------------------------------------

  @Test
  public void testResolveFiscalLocationReturnsNullWhenOrgInfoIsNull() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");

    OBDal dal = mock(OBDal.class);
    when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertNull(service.resolveFiscalLocation(org));
    }
  }

  @Test
  public void testResolveFiscalLocationReturnsOrgInfoLocation() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    Location location = mock(Location.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(orgInfo.getLocationAddress()).thenReturn(location);

    OBDal dal = mock(OBDal.class);
    when(dal.get(OrganizationInformation.class, "ORG-1")).thenReturn(orgInfo);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(location, service.resolveFiscalLocation(org));
    }
  }

  @Test
  public void testCreateWarehouseLocationSetsFiscalCountryAndSaves() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Country country = mock(Country.class);
    Location fiscal = mock(Location.class);
    when(fiscal.getCountry()).thenReturn(country);

    OBProvider provider = mock(OBProvider.class);
    Location location = mock(Location.class);
    when(provider.get(Location.class)).thenReturn(location);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(location, service.createWarehouseLocation(client, org, fiscal));
    }

    verify(location).setNewOBObject(true);
    verify(location).setClient(client);
    verify(location).setOrganization(org);
    verify(location).setActive(true);
    verify(location).setCountry(country);
    verify(dal).save(location);
  }

  @Test
  public void testCopyAddressFieldsCopiesEveryFieldExceptCountry() {
    OnboardingWarehouseAddressService service = new OnboardingWarehouseAddressService();
    Location src = mock(Location.class);
    Location dst = mock(Location.class);
    when(src.getAddressLine1()).thenReturn("L1");
    when(src.getAddressLine2()).thenReturn("L2");
    when(src.getCityName()).thenReturn("City");
    when(src.getPostalCode()).thenReturn("PC");
    when(src.getPostalAdd()).thenReturn("PA");
    when(src.getRegionName()).thenReturn("Region");
    org.openbravo.model.common.geography.Region region =
        mock(org.openbravo.model.common.geography.Region.class);
    org.openbravo.model.common.geography.City city =
        mock(org.openbravo.model.common.geography.City.class);
    when(src.getRegion()).thenReturn(region);
    when(src.getCity()).thenReturn(city);

    service.copyAddressFields(src, dst);

    verify(dst).setAddressLine1("L1");
    verify(dst).setAddressLine2("L2");
    verify(dst).setCityName("City");
    verify(dst).setPostalCode("PC");
    verify(dst).setPostalAdd("PA");
    verify(dst).setRegion(region);
    verify(dst).setRegionName("Region");
    verify(dst).setCity(city);
    verify(dst, never()).setCountry(any());
  }

  // ---------------------------------------------------------------------------
  // Testable subclass: overrides the protected DB seams (mirrors OnboardingOrgInfoServiceTest).
  // ---------------------------------------------------------------------------

  private static final class TestableService extends OnboardingWarehouseAddressService {
    private Organization organization = mock(Organization.class);

    boolean failOnAlign;
    boolean alignCalled;
    boolean flushed;
    Client alignedClient;
    Organization alignedOrg;

    private TestableService() {
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
    protected Organization resolveOrganization(String orgId) {
      return organization;
    }

    @Override
    protected void alignWarehouseAddress(Client client, Organization org) {
      alignCalled = true;
      alignedClient = client;
      alignedOrg = org;
      if (failOnAlign) {
        throw new OBException("align-boom");
      }
    }
  }
}
