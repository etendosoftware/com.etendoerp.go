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

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
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

/**
 * Ensures the onboarding organization has an {@code AD_ORGINFO} record pointing to a
 * {@code C_LOCATION} with a country, and carries over the Tax ID entered in the wizard.
 *
 * <p>The location's country is what Etendo's tax engine uses to resolve applicable taxes for the
 * organization. The onboarding dataset import creates an {@code AD_ORGINFO} row (it is in the
 * dataset's included tables) but with a {@code null} location, so taxes cannot be computed until a
 * located address exists. This service is idempotent: it only creates/links a location when the
 * org-info has none, defaulting the country to Spain (ISO {@code ES}) when the request carries no
 * explicit country.</p>
 *
 * <p>Tax ID (ETP-4749): {@code AD_ORGINFO.TAXID} is {@code NOT NULL} with no meaningful default,
 * so a freshly-imported org-info row keeps Etendo core's generated placeholder for an unset
 * required String field until something sets it explicitly. The wizard's Tax ID field is
 * optional, so a blank/missing value here is left as-is — this service never invents or clears a
 * Tax ID, it only persists one when the onboarding request actually carries one.</p>
 */
public class OnboardingOrgInfoService extends OnboardingContextSupport {

  /** Default ISO country code when the onboarding request supplies none. */
  static final String DEFAULT_COUNTRY_ISO = "ES";

  /**
   * Ensures the organization's {@code AD_ORGINFO} points to a located {@code C_LOCATION} and
   * carries over the onboarding request's Tax ID, if any.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   * @param countryIso  ISO country code from the onboarding request (defaults to {@code ES})
   * @param address     optional street address line from the onboarding request
   * @param taxId       optional Tax ID from the onboarding request; left untouched when blank
   */
  public void ensureOrgInfo(String clientId, String orgId, String adminUserId, String adminRoleId,
      String countryIso, String address, String taxId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = resolveClient(clientId);
        Organization org = resolveOrganization(orgId);
        if (client == null) {
          throw new OBException("Client not found for org-info setup: " + clientId);
        }
        if (org == null) {
          throw new OBException("Organization not found for org-info setup: " + orgId);
        }
        ensureOrgInfoLocation(client, org, countryIso, address, taxId);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected void ensureOrgInfoLocation(Client client, Organization org, String countryIso,
      String address, String taxId) {
    OrganizationInformation orgInfo = resolveOrgInfo(org);
    if (orgInfo == null) {
      orgInfo = createOrgInfo(client, org);
    }
    applyTaxId(orgInfo, taxId);
    if (orgInfo.getLocationAddress() != null) {
      // Already located (e.g. set manually in the UI); leave it untouched.
      return;
    }
    Country country = resolveCountry(countryIso);
    if (country == null) {
      throw new OBException("No country available to locate onboarding organization");
    }
    Location location = createLocation(client, org, country, address);
    orgInfo.setLocationAddress(location);
    OBDal.getInstance().save(orgInfo);
  }

  /**
   * Persists the onboarding request's Tax ID onto {@code orgInfo} when one was actually
   * provided. The wizard's Tax ID field is optional (ETP-4749): a blank/null value here means the
   * user did not fill it in, not that it should be cleared, so this is a no-op in that case.
   */
  protected void applyTaxId(OrganizationInformation orgInfo, String taxId) {
    if (StringUtils.isBlank(taxId)) {
      return;
    }
    orgInfo.setTaxID(taxId.trim());
    OBDal.getInstance().save(orgInfo);
  }

  protected OrganizationInformation resolveOrgInfo(Organization org) {
    // AD_ORGINFO has a 1:1 shared primary key with AD_ORG, so the org-info id IS the org id.
    // Fetch it directly by id; a criteria on PROPERTY_ORGANIZATION (the entity's own PK) yields
    // malformed SQL.
    return OBDal.getInstance().get(OrganizationInformation.class, org.getId());
  }

  protected OrganizationInformation createOrgInfo(Client client, Organization org) {
    OrganizationInformation orgInfo = OBProvider.getInstance().get(OrganizationInformation.class);
    orgInfo.setNewOBObject(true);
    // Shared primary key: the org-info id must equal the org id.
    orgInfo.setId(org.getId());
    orgInfo.setClient(client);
    orgInfo.setOrganization(org);
    orgInfo.setActive(true);
    OBDal.getInstance().save(orgInfo);
    return orgInfo;
  }

  protected Location createLocation(Client client, Organization org, Country country,
      String address) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(org);
    location.setActive(true);
    location.setCountry(country);
    if (StringUtils.isNotBlank(address)) {
      location.setAddressLine1(address.trim());
    }
    OBDal.getInstance().save(location);
    return location;
  }

  protected Country resolveCountry(String countryIso) {
    String iso = StringUtils.isNotBlank(countryIso) ? countryIso.trim() : DEFAULT_COUNTRY_ISO;
    Country country = findCountryByIso(iso);
    if (country == null && !DEFAULT_COUNTRY_ISO.equals(iso)) {
      country = findCountryByIso(DEFAULT_COUNTRY_ISO);
    }
    if (country == null) {
      country = findAnyActiveCountry();
    }
    return country;
  }

  protected Country findCountryByIso(String iso) {
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Country.PROPERTY_ISOCOUNTRYCODE, iso));
    criteria.setMaxResults(1);
    return (Country) criteria.uniqueResult();
  }

  protected Country findAnyActiveCountry() {
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Country.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Country) criteria.uniqueResult();
  }

  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  @Override
  protected String contextSubject() {
    return "org-info setup";
  }
}
