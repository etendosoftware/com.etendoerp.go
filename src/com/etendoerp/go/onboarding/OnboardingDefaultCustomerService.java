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

import java.util.Date;

import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
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

/** Seeds a minimal customer so the first Sales Invoice selector is not empty after onboarding. */
public class OnboardingDefaultCustomerService {

  static final String DEFAULT_CUSTOMER_SEARCH_KEY = "ONBOARDING_DEFAULT_CUSTOMER";
  static final String DEFAULT_CUSTOMER_NAME = "Default Customer";
  static final String DEFAULT_CUSTOMER_LOCATION_NAME = "Default Customer Address";
  static final String DEFAULT_CUSTOMER_CONTACT_NAME = "Default Customer Contact";
  static final String DEFAULT_CUSTOMER_CURRENCY_ISO = "EUR";

  /**
   * Ensures the onboarding organization has a minimal customer business partner.
   *
   * @param clientId
   *     client that owns the customer
   * @param orgId
   *     organization where the customer is created
   * @param adminUserId
   *     administrator user used to execute DAL changes
   * @param adminRoleId
   *     administrator role used to execute DAL changes
   * @return existing or newly created default customer id
   */
  public String ensureDefaultCustomer(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    Object previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = resolveClient(clientId);
        Organization organization = resolveOrganization(orgId);
        if (client == null) {
          throw new OBException("Client not found for onboarding default customer: " + clientId);
        }
        if (organization == null) {
          throw new OBException("Organization not found for onboarding default customer: " + orgId);
        }

        BusinessPartner customer = findExistingDefaultCustomer(clientId, orgId);
        if (customer == null) {
          Category bpGroup = resolveBusinessPartnerGroup(clientId);
          if (bpGroup == null) {
            throw new OBException("Business partner group not found for onboarding default customer");
          }
          customer = createDefaultCustomer(client, organization, bpGroup);
          saveCustomer(customer);
        }

        // A customer with no currency is not fully set up for invoicing. The dataset import does not
        // seed one for this synthetic BP, so default it to the organization's currency here (falling
        // back to EUR only if the organization has none configured). Idempotent: existing customers
        // that already carry a currency are left untouched.
        ensureDefaultCustomerCurrency(customer, organization);
        // A customer with no address cannot be used on a Sales Invoice (no bill-to/ship-to). The
        // dataset import never creates one for this synthetic BP, so provision it here. Idempotent:
        // re-runs (and customers created before this fix) get exactly one location.
        org.openbravo.model.common.businesspartner.Location location =
            ensureDefaultCustomerLocation(client, organization, customer);
        // The customer also needs a contact (AD_User) linked to it and its address, so it behaves
        // like a fully set-up business partner. Idempotent for the same reasons.
        ensureDefaultCustomerContact(client, organization, customer, location);
        flushChanges();
        return customer.getId();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected Object captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
      String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  protected void restoreExecutionContext(Object previousContext) {
    OBContext.setOBContext((OBContext) previousContext);
  }

  protected void saveCustomer(BusinessPartner customer) {
    OBDal.getInstance().save(customer);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }


  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }


  protected BusinessPartner findExistingDefaultCustomer(String clientId, String orgId) {
    OBCriteria<BusinessPartner> criteria = OBDal.getInstance().createCriteria(BusinessPartner.class);
    criteria.add(Restrictions.eq(BusinessPartner.PROPERTY_CLIENT,
        OBDal.getInstance().get(Client.class, clientId)));
    criteria.add(Restrictions.eq(BusinessPartner.PROPERTY_ORGANIZATION,
        OBDal.getInstance().get(Organization.class, orgId)));
    criteria.add(Restrictions.eq(BusinessPartner.PROPERTY_SEARCHKEY, DEFAULT_CUSTOMER_SEARCH_KEY));
    criteria.setMaxResults(1);
    return (BusinessPartner) criteria.uniqueResult();
  }

  /**
   * Resolves the business partner group (category) to assign to the onboarding default customer.
   *
   * <p>Prefers the group explicitly flagged {@code ISDEFAULT='Y'} (e.g. "Consumidor Final" in the
   * GOClient dataset). This is REQUIRED, not cosmetic: the dataset now ships four groups
   * ("Acreedor", "Cliente", "Consumidor Final", "Proveedor"), and alphabetical order places
   * "Acreedor" first. An alphabetical-only lookup would therefore silently reassign the default
   * customer to "Acreedor" instead of "Consumidor Final". Falls back to the alphabetical lookup only
   * when no group is flagged default, so legacy tenants provisioned before {@code ISDEFAULT} was set
   * on any group keep resolving deterministically.
   */
  protected Category resolveBusinessPartnerGroup(String clientId) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Category defaultGroup = resolveDefaultBusinessPartnerGroup(client);
    if (defaultGroup != null) {
      return defaultGroup;
    }
    OBCriteria<Category> criteria = OBDal.getInstance().createCriteria(Category.class);
    criteria.add(Restrictions.eq(Category.PROPERTY_CLIENT, client));
    criteria.addOrder(Order.asc(Category.PROPERTY_NAME));
    criteria.setMaxResults(1);
    return (Category) criteria.uniqueResult();
  }

  protected Category resolveDefaultBusinessPartnerGroup(Client client) {
    OBCriteria<Category> criteria = OBDal.getInstance().createCriteria(Category.class);
    criteria.add(Restrictions.eq(Category.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq(Category.PROPERTY_DEFAULT, true));
    criteria.addOrder(Order.asc(Category.PROPERTY_NAME));
    criteria.setMaxResults(1);
    return (Category) criteria.uniqueResult();
  }

  protected BusinessPartner createDefaultCustomer(Client client, Organization organization,
      Category bpGroup) {
    BusinessPartner customer = OBProvider.getInstance().get(BusinessPartner.class);
    customer.setClient(client);
    customer.setOrganization(organization);
    customer.setActive(true);
    customer.setSearchKey(DEFAULT_CUSTOMER_SEARCH_KEY);
    customer.setName(DEFAULT_CUSTOMER_NAME);
    customer.setCustomer(true);
    customer.setVendor(false);
    customer.setBusinessPartnerCategory(bpGroup);
    return customer;
  }

  /**
   * Ensures the default customer has a currency, preferring the organization's own currency
   * (see {@link OBCurrencyUtils#getOrgCurrency(String)}) and falling back to the hardcoded EUR
   * default only when the organization has no currency configured at all. No-op when the
   * customer already has a currency, so customers created before this wiring (or with an
   * explicit currency) are left untouched.
   */
  protected void ensureDefaultCustomerCurrency(BusinessPartner customer, Organization organization) {
    if (customer.getCurrency() != null) {
      return;
    }
    Currency currency = resolveOrgCurrency(organization);
    if (currency == null) {
      currency = resolveDefaultCurrency();
    }
    if (currency == null) {
      throw new OBException(
          "Currency " + DEFAULT_CUSTOMER_CURRENCY_ISO + " not found for onboarding default customer");
    }
    customer.setCurrency(currency);
    OBDal.getInstance().save(customer);
  }

  protected Currency resolveOrgCurrency(Organization organization) {
    if (organization == null) {
      return null;
    }
    String currencyId = OBCurrencyUtils.getOrgCurrency(organization.getId());
    if (currencyId == null || currencyId.isEmpty()) {
      return null;
    }
    return OBDal.getInstance().get(Currency.class, currencyId);
  }

  protected Currency resolveDefaultCurrency() {
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ISOCODE, DEFAULT_CUSTOMER_CURRENCY_ISO));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return (Currency) criteria.uniqueResult();
  }

  /**
   * Ensures the default customer has at least one business-partner location (address), so it is
   * usable as the bill-to/ship-to party on a Sales Invoice. No-op when a location already exists.
   *
   * <p>The {@code C_BPartner_Location} carries the address flags (invoice-to / ship-to / pay-from /
   * remit-to) which default to {@code true}; the underlying {@code C_Location} only needs a country.
   * The country is reused from the tenant's existing locations (the dataset import seeds them with
   * the onboarding country), so the address matches the tenant's fiscal country without threading a
   * country code through the onboarding chain.
   */
  protected org.openbravo.model.common.businesspartner.Location ensureDefaultCustomerLocation(
      Client client, Organization organization, BusinessPartner customer) {
    org.openbravo.model.common.businesspartner.Location existing =
        findBusinessPartnerLocation(customer);
    if (existing != null) {
      return existing;
    }
    Country country = resolveDefaultCountry(client);
    if (country == null) {
      throw new OBException(
          "No country available to create the default customer address for client "
              + client.getId());
    }
    Location address = createCustomerAddress(client, organization, country);

    org.openbravo.model.common.businesspartner.Location bpLocation =
        OBProvider.getInstance().get(org.openbravo.model.common.businesspartner.Location.class);
    bpLocation.setNewOBObject(true);
    bpLocation.setClient(client);
    bpLocation.setOrganization(organization);
    bpLocation.setActive(true);
    bpLocation.setBusinessPartner(customer);
    bpLocation.setLocationAddress(address);
    bpLocation.setName(DEFAULT_CUSTOMER_LOCATION_NAME);
    OBDal.getInstance().save(bpLocation);
    return bpLocation;
  }

  protected org.openbravo.model.common.businesspartner.Location findBusinessPartnerLocation(
      BusinessPartner customer) {
    OBCriteria<org.openbravo.model.common.businesspartner.Location> criteria = OBDal.getInstance()
        .createCriteria(org.openbravo.model.common.businesspartner.Location.class);
    criteria.add(Restrictions.eq(
        org.openbravo.model.common.businesspartner.Location.PROPERTY_BUSINESSPARTNER, customer));
    criteria.addOrder(Order.asc(
        org.openbravo.model.common.businesspartner.Location.PROPERTY_ID));
    criteria.setMaxResults(1);
    return (org.openbravo.model.common.businesspartner.Location) criteria.uniqueResult();
  }

  /**
   * Ensures the default customer has a contact ({@code AD_User}) linked to the business partner and
   * its address. If a contact already exists but is not linked to an address (e.g. one created
   * manually), it is linked to the address rather than duplicated; only when the customer has no
   * contact at all is a fresh one created. The contact is a plain BP contact (no login
   * username/role), so it never grants system access.
   */
  protected void ensureDefaultCustomerContact(Client client, Organization organization,
      BusinessPartner customer, org.openbravo.model.common.businesspartner.Location location) {
    User existing = findContact(customer);
    if (existing != null) {
      if (existing.getPartnerAddress() == null) {
        existing.setPartnerAddress(location);
        OBDal.getInstance().save(existing);
      }
      return;
    }
    User contact = OBProvider.getInstance().get(User.class);
    contact.setNewOBObject(true);
    contact.setClient(client);
    contact.setOrganization(organization);
    contact.setActive(true);
    contact.setName(DEFAULT_CUSTOMER_CONTACT_NAME);
    contact.setBusinessPartner(customer);
    contact.setPartnerAddress(location);
    // lastPasswordUpdate is NOT NULL with no entity-level default; set it so the insert is valid.
    contact.setLastPasswordUpdate(new Date());
    OBDal.getInstance().save(contact);
  }

  protected User findContact(BusinessPartner customer) {
    OBCriteria<User> criteria = OBDal.getInstance().createCriteria(User.class);
    criteria.add(Restrictions.eq(User.PROPERTY_BUSINESSPARTNER, customer));
    criteria.addOrder(Order.asc(User.PROPERTY_ID));
    criteria.setMaxResults(1);
    return (User) criteria.uniqueResult();
  }

  /**
   * Resolves a country for the default customer's address by reusing one already used by the
   * client's locations (set from the onboarding country). Falls back to any country so onboarding
   * never fails for the address step alone.
   */
  protected Country resolveDefaultCountry(Client client) {
    OBCriteria<Location> criteria = OBDal.getInstance().createCriteria(Location.class);
    criteria.add(Restrictions.eq(Location.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.isNotNull(Location.PROPERTY_COUNTRY));
    criteria.addOrder(Order.asc(Location.PROPERTY_ID));
    criteria.setMaxResults(1);
    Location existing = (Location) criteria.uniqueResult();
    if (existing != null) {
      return existing.getCountry();
    }
    OBCriteria<Country> fallback = OBDal.getInstance().createCriteria(Country.class);
    fallback.add(Restrictions.eq(Country.PROPERTY_ACTIVE, true));
    fallback.addOrder(Order.asc(Country.PROPERTY_ID));
    fallback.setMaxResults(1);
    return (Country) fallback.uniqueResult();
  }

  protected Location createCustomerAddress(Client client, Organization organization,
      Country country) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(organization);
    location.setCountry(country);
    OBDal.getInstance().save(location);
    return location;
  }

  private void validateContext(String clientId, String orgId, String adminUserId, String adminRoleId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException("Missing client for onboarding default customer");
    }
    if (orgId == null || orgId.isEmpty()) {
      throw new OBException("Missing organization for onboarding default customer");
    }
    if (adminUserId == null || adminUserId.isEmpty()) {
      throw new OBException("Missing admin user for onboarding default customer");
    }
    if (adminRoleId == null || adminRoleId.isEmpty()) {
      throw new OBException("Missing admin role for onboarding default customer");
    }
  }
}
