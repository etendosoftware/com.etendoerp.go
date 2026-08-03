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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;

public class OnboardingDefaultCustomerServiceTest {

  // ---------------------------------------------------------------------------
  // validateContext() — remaining required-field branches
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureDefaultCustomerFailsWhenClientIdIsMissing() {
    try {
      new TestableService().ensureDefaultCustomer("", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected missing client to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerFailsWhenOrgIdIsMissing() {
    try {
      new TestableService().ensureDefaultCustomer("CLIENT-1", null, "USER-1", "ROLE-1");
      fail("Expected missing organization to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerFailsWhenAdminRoleIsMissing() {
    try {
      new TestableService().ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "");
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  // ---------------------------------------------------------------------------
  // ensureDefaultCustomer() — resolution failure branches
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureDefaultCustomerFailsWhenClientNotFound() {
    TestableService service = new TestableService();
    service.clientToReturn = null;
    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected client-not-found failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Client not found for onboarding default customer"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerFailsWhenOrganizationNotFound() {
    TestableService service = new TestableService();
    service.orgToReturn = null;
    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected organization-not-found failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found for onboarding default customer"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerFailsWhenBpGroupNotFound() {
    TestableService service = new TestableService();
    service.bpGroupToReturn = null; // no existing customer -> tries to resolve group
    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected bp-group-not-found failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Business partner group not found"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerCreatesWhenNoneExists() {
    TestableService service = new TestableService();
    // existingCustomer left null -> createDefaultCustomer is invoked.
    String result = service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("BP-CREATED", result);
    assertEquals(1, service.createdCount);
  }

  // ---------------------------------------------------------------------------
  // findExistingDefaultCustomer() — real OBCriteria body
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testFindExistingDefaultCustomerReturnsUniqueResult() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    BusinessPartner bp = mock(BusinessPartner.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<BusinessPartner> crit = mock(OBCriteria.class);
    when(dal.createCriteria(BusinessPartner.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(bp);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(bp, service.findExistingDefaultCustomer("CLIENT-1", "ORG-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // resolveBusinessPartnerGroup() — real OBCriteria body
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveBusinessPartnerGroupReturnsUniqueResult() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    Category category = mock(Category.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Category> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Category.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(category);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(category, service.resolveBusinessPartnerGroup("CLIENT-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // createDefaultCustomer() — real OBProvider-backed body
  // ---------------------------------------------------------------------------

  @Test
  public void testCreateDefaultCustomerPopulatesAllFields() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Category bpGroup = mock(Category.class);

    OBProvider provider = mock(OBProvider.class);
    BusinessPartner customer = mock(BusinessPartner.class);
    when(provider.get(BusinessPartner.class)).thenReturn(customer);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      assertSame(customer, service.createDefaultCustomer(client, org, bpGroup));
    }

    verify(customer).setClient(client);
    verify(customer).setOrganization(org);
    verify(customer).setActive(true);
    verify(customer).setSearchKey(OnboardingDefaultCustomerService.DEFAULT_CUSTOMER_SEARCH_KEY);
    verify(customer).setName(OnboardingDefaultCustomerService.DEFAULT_CUSTOMER_NAME);
    verify(customer).setCustomer(true);
    verify(customer).setVendor(false);
    verify(customer).setBusinessPartnerCategory(bpGroup);
  }

  // ---------------------------------------------------------------------------
  // ensureDefaultCustomerCurrency() — real body, all branches
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureDefaultCustomerCurrencySkipsWhenAlreadySet() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    BusinessPartner customer = mock(BusinessPartner.class);
    when(customer.getCurrency()).thenReturn(mock(Currency.class));

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureDefaultCustomerCurrency(customer, null);
    }

    verify(customer, never()).setCurrency(any());
    verify(dal, never()).save(customer);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testEnsureDefaultCustomerCurrencyDefaultsToEurWhenMissing() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    BusinessPartner customer = mock(BusinessPartner.class);
    when(customer.getCurrency()).thenReturn(null);
    Currency eur = mock(Currency.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Currency> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Currency.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(eur);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureDefaultCustomerCurrency(customer, null);
    }

    verify(customer).setCurrency(eur);
    verify(dal).save(customer);
  }

  /**
   * ETP-4649: when the organization has a currency configured, {@code ensureDefaultCustomerCurrency}
   * must use it — the hardcoded EUR fallback (queried via the {@code Currency} criteria) must never
   * be consulted.
   */
  @Test
  public void testEnsureDefaultCustomerCurrencyUsesOrgCurrencyWhenAvailable() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    BusinessPartner customer = mock(BusinessPartner.class);
    when(customer.getCurrency()).thenReturn(null);
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn("ORG-1");
    Currency orgCurrency = mock(Currency.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Currency.class, "CUR-1")).thenReturn(orgCurrency);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> obCurrency = mockStatic(OBCurrencyUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obCurrency.when(() -> OBCurrencyUtils.getOrgCurrency("ORG-1")).thenReturn("CUR-1");
      service.ensureDefaultCustomerCurrency(customer, organization);
    }

    verify(customer).setCurrency(orgCurrency);
    verify(dal).save(customer);
    verify(dal, never()).createCriteria(Currency.class);
  }

  /**
   * ETP-4649: when the organization has NO currency configured (empty/null resolution), the
   * existing EUR fallback must still kick in — same behavior as before the org-currency wiring
   * was added, now made explicit with a real (non-null) {@link Organization}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testEnsureDefaultCustomerCurrencyFallsBackToEurWhenOrgHasNoCurrency() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    BusinessPartner customer = mock(BusinessPartner.class);
    when(customer.getCurrency()).thenReturn(null);
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn("ORG-1");
    Currency eur = mock(Currency.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Currency> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Currency.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(eur);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> obCurrency = mockStatic(OBCurrencyUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obCurrency.when(() -> OBCurrencyUtils.getOrgCurrency("ORG-1")).thenReturn(null);
      service.ensureDefaultCustomerCurrency(customer, organization);
    }

    verify(customer).setCurrency(eur);
    verify(dal).save(customer);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testEnsureDefaultCustomerCurrencyThrowsWhenCurrencyMissing() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    BusinessPartner customer = mock(BusinessPartner.class);
    when(customer.getCurrency()).thenReturn(null);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Currency> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Currency.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureDefaultCustomerCurrency(customer, null);
      fail("Expected currency-not-found failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("not found for onboarding default customer"));
    }
  }

  // ---------------------------------------------------------------------------
  // ensureDefaultCustomerLocation() — existing / no-country branches
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureDefaultCustomerLocationReturnsExistingWhenPresent() {
    org.openbravo.model.common.businesspartner.Location existing =
        mock(org.openbravo.model.common.businesspartner.Location.class);
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService() {
      @Override
      protected org.openbravo.model.common.businesspartner.Location findBusinessPartnerLocation(
          BusinessPartner customer) {
        return existing;
      }
    };
    assertSame(existing, service.ensureDefaultCustomerLocation(mock(Client.class),
        mock(Organization.class), mock(BusinessPartner.class)));
  }

  @Test
  public void testEnsureDefaultCustomerLocationThrowsWhenNoCountry() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService() {
      @Override
      protected org.openbravo.model.common.businesspartner.Location findBusinessPartnerLocation(
          BusinessPartner customer) {
        return null;
      }

      @Override
      protected Country resolveDefaultCountry(Client client) {
        return null;
      }
    };
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT-1");

    try {
      service.ensureDefaultCustomerLocation(client, mock(Organization.class),
          mock(BusinessPartner.class));
      fail("Expected no-country failure");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("No country available to create the default customer"));
    }
  }

  // ---------------------------------------------------------------------------
  // findBusinessPartnerLocation() / findContact() — real OBCriteria bodies
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testFindBusinessPartnerLocationReturnsUniqueResult() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    org.openbravo.model.common.businesspartner.Location loc =
        mock(org.openbravo.model.common.businesspartner.Location.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<org.openbravo.model.common.businesspartner.Location> crit = mock(OBCriteria.class);
    when(dal.createCriteria(org.openbravo.model.common.businesspartner.Location.class))
        .thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(loc);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(loc, service.findBusinessPartnerLocation(mock(BusinessPartner.class)));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFindContactReturnsUniqueResult() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    User user = mock(User.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<User> crit = mock(OBCriteria.class);
    when(dal.createCriteria(User.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(user);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(user, service.findContact(mock(BusinessPartner.class)));
    }
  }

  // ---------------------------------------------------------------------------
  // ensureDefaultCustomerContact() — existing-contact branches + new-contact body
  // ---------------------------------------------------------------------------

  @Test
  public void testEnsureDefaultCustomerContactLinksAddressWhenExistingHasNone() {
    User existing = mock(User.class);
    when(existing.getPartnerAddress()).thenReturn(null);
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService() {
      @Override
      protected User findContact(BusinessPartner customer) {
        return existing;
      }
    };
    org.openbravo.model.common.businesspartner.Location location =
        mock(org.openbravo.model.common.businesspartner.Location.class);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureDefaultCustomerContact(mock(Client.class), mock(Organization.class),
          mock(BusinessPartner.class), location);
    }

    verify(existing).setPartnerAddress(location);
    verify(dal).save(existing);
  }

  @Test
  public void testEnsureDefaultCustomerContactSkipsWhenExistingAlreadyLinked() {
    User existing = mock(User.class);
    when(existing.getPartnerAddress())
        .thenReturn(mock(org.openbravo.model.common.businesspartner.Location.class));
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService() {
      @Override
      protected User findContact(BusinessPartner customer) {
        return existing;
      }
    };

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureDefaultCustomerContact(mock(Client.class), mock(Organization.class),
          mock(BusinessPartner.class),
          mock(org.openbravo.model.common.businesspartner.Location.class));
    }

    verify(existing, never()).setPartnerAddress(any());
    verify(dal, never()).save(existing);
  }

  @Test
  public void testEnsureDefaultCustomerContactCreatesNewWhenNoneExists() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService() {
      @Override
      protected User findContact(BusinessPartner customer) {
        return null;
      }
    };
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    BusinessPartner customer = mock(BusinessPartner.class);
    org.openbravo.model.common.businesspartner.Location location =
        mock(org.openbravo.model.common.businesspartner.Location.class);

    OBProvider provider = mock(OBProvider.class);
    User contact = mock(User.class);
    when(provider.get(User.class)).thenReturn(contact);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureDefaultCustomerContact(client, org, customer, location);
    }

    verify(contact).setNewOBObject(true);
    verify(contact).setClient(client);
    verify(contact).setOrganization(org);
    verify(contact).setActive(true);
    verify(contact).setName(OnboardingDefaultCustomerService.DEFAULT_CUSTOMER_CONTACT_NAME);
    verify(contact).setBusinessPartner(customer);
    verify(contact).setPartnerAddress(location);
    verify(contact).setLastPasswordUpdate(any());
    verify(dal).save(contact);
  }

  // ---------------------------------------------------------------------------
  // resolveDefaultCountry() — reuse-existing and fallback branches
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultCountryReusesExistingLocationCountry() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    Client client = mock(Client.class);
    Country country = mock(Country.class);
    Location existing = mock(Location.class);
    when(existing.getCountry()).thenReturn(country);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Location> locCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Location.class)).thenReturn(locCrit);
    when(locCrit.uniqueResult()).thenReturn(existing);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(country, service.resolveDefaultCountry(client));
    }

    // Reuse path must not query the Country fallback.
    verify(dal, never()).createCriteria(Country.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveDefaultCountryFallsBackToAnyActiveCountry() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    Client client = mock(Client.class);
    Country fallbackCountry = mock(Country.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Location> locCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Location.class)).thenReturn(locCrit);
    when(locCrit.uniqueResult()).thenReturn(null); // no located client address
    OBCriteria<Country> countryCrit = mock(OBCriteria.class);
    when(dal.createCriteria(Country.class)).thenReturn(countryCrit);
    when(countryCrit.uniqueResult()).thenReturn(fallbackCountry);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(fallbackCountry, service.resolveDefaultCountry(client));
    }
  }

  // ---------------------------------------------------------------------------
  // createCustomerAddress() — real OBProvider-backed body
  // ---------------------------------------------------------------------------

  @Test
  public void testCreateCustomerAddressPopulatesAndSaves() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
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
      assertSame(location, service.createCustomerAddress(client, org, country));
    }

    verify(location).setNewOBObject(true);
    verify(location).setClient(client);
    verify(location).setOrganization(org);
    verify(location).setCountry(country);
    verify(dal).save(location);
  }

  // ---------------------------------------------------------------------------
  // resolveClient() / resolveOrganization() — real OBDal delegation
  // ---------------------------------------------------------------------------

  @Test
  public void testResolveClientDelegatesToObDalGet() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Client.class, "CLIENT-1")).thenReturn(client);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(client, service.resolveClient("CLIENT-1"));
    }
  }

  @Test
  public void testResolveOrganizationReturnsNullWhenAbsent() {
    OnboardingDefaultCustomerService service = new OnboardingDefaultCustomerService();

    OBDal dal = mock(OBDal.class);
    when(dal.get(Organization.class, "ORG-1")).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertNull(service.resolveOrganization("ORG-1"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();

    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerReturnsExistingCustomerId() {
    TestableService service = new TestableService();
    BusinessPartner existing = mock(BusinessPartner.class);
    when(existing.getId()).thenReturn("BP-EXISTING");
    service.existingCustomer = existing;

    String result = service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("BP-EXISTING", result);
    assertEquals(0, service.createdCount);
    // Even for an existing customer the currency/location/contact wiring must run (idempotent
    // top-up), so a partially set-up BP gets completed.
    assertEquals(1, service.currencyWiringCount);
    assertEquals(1, service.locationWiringCount);
    assertEquals(1, service.contactWiringCount);
  }

  @Test
  public void testEnsureDefaultCustomerRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.failOnCreate = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected delegated create failure");
    } catch (OBException e) {
      assertEquals("boom", e.getMessage());
    }

    assertSame(previous, OBContext.getOBContext());
  }

  private static final class TestableService extends OnboardingDefaultCustomerService {
    private final Client client = mock(Client.class);
    private final Organization organization = mock(Organization.class);
    private final Category bpGroup = mock(Category.class);
    private BusinessPartner existingCustomer;
    private boolean failOnCreate;
    private int createdCount;
    private int currencyWiringCount;
    private int locationWiringCount;
    private int contactWiringCount;
    private Client clientToReturn;
    private Organization orgToReturn;
    private Category bpGroupToReturn;

    private TestableService() {
      when(client.getId()).thenReturn("CLIENT-1");
      when(organization.getId()).thenReturn("ORG-1");
      clientToReturn = client;
      orgToReturn = organization;
      bpGroupToReturn = bpGroup;
    }

    @Override
    protected Object captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
        String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void enterAdminMode() {
      // no-op in unit tests
    }

    @Override
    protected void exitAdminMode() {
      // no-op in unit tests
    }

    @Override
    protected void restoreExecutionContext(Object previousContext) {
      OBContext.setOBContext((OBContext) previousContext);
    }

    @Override
    protected void saveCustomer(BusinessPartner customer) {
      // no-op in unit tests
    }

    @Override
    protected void flushChanges() {
      // no-op in unit tests
    }

    @Override
    protected BusinessPartner findExistingDefaultCustomer(String clientId, String orgId) {
      return existingCustomer;
    }

    @Override
    protected Category resolveBusinessPartnerGroup(String clientId) {
      return bpGroupToReturn;
    }

    @Override
    protected BusinessPartner createDefaultCustomer(Client client, Organization organization,
        Category bpGroup) {
      if (failOnCreate) {
        throw new OBException("boom");
      }
      createdCount++;
      BusinessPartner customer = mock(BusinessPartner.class);
      when(customer.getId()).thenReturn("BP-CREATED");
      return customer;
    }

    @Override
    protected Client resolveClient(String clientId) {
      return clientToReturn;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return orgToReturn;
    }

    // The customer wiring below reaches into OBDal (currency/location/contact lookups), which is
    // unavailable in unit tests. We track invocation instead of touching the DB; asserting these
    // counts keeps the wiring connected to ensureDefaultCustomer (the OBDal logic itself is
    // covered by integration tests).
    @Override
    protected void ensureDefaultCustomerCurrency(BusinessPartner customer, Organization organization) {
      currencyWiringCount++;
    }

    @Override
    protected org.openbravo.model.common.businesspartner.Location ensureDefaultCustomerLocation(
        Client client, Organization organization, BusinessPartner customer) {
      locationWiringCount++;
      return null;
    }

    @Override
    protected void ensureDefaultCustomerContact(Client client, Organization organization,
        BusinessPartner customer, org.openbravo.model.common.businesspartner.Location location) {
      contactWiringCount++;
    }
  }
}
