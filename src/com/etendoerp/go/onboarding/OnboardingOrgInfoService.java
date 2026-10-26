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

import com.etendoerp.go.schemaforge.util.StructuredAddressComposer;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer.ComposedAddress;

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
   * @param countryIso     ISO country code from the onboarding request (defaults to {@code ES})
   * @param address        optional legacy free-text address line, used only when none of the 3
   *                        structured fields below is present
   * @param streetName     optional Street Name ({@code EM_OBSEPA_StrtNm}) from the onboarding
   *                        request's structured address block (ETP-5806)
   * @param buildingNumber optional Building Number ({@code EM_OBSEPA_BldgNb}); mandatory together
   *                        with {@code streetName} when the structured block was started — already
   *                        enforced by the servlet before this method is called
   * @param buildingName   optional Building Name ({@code EM_OBSEPA_BldgNm})
   * @param cityName       optional City ({@code C_Location.City}); mandatory together with
   *                       {@code streetName}/{@code buildingNumber} when the structured block was
   *                       started — already enforced by the servlet before this method is called
   * @param taxId          optional Tax ID from the onboarding request; left untouched when blank
   */
  public void ensureOrgInfo(String clientId, String orgId, String adminUserId, String adminRoleId,
      String countryIso, String address, String streetName, String buildingNumber,
      String buildingName, String cityName, String taxId) {
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
        ensureOrgInfoLocation(client, org, countryIso, address, streetName, buildingNumber,
            buildingName, cityName, taxId);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected void ensureOrgInfoLocation(Client client, Organization org, String countryIso,
      String address, String streetName, String buildingNumber, String buildingName,
      String cityName, String taxId) {
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
    Location location = createLocation(client, org, country, address, streetName, buildingNumber,
        buildingName, cityName);
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
      String address, String streetName, String buildingNumber, String buildingName,
      String cityName) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(org);
    location.setActive(true);
    location.setCountry(country);
    // ETP-5806 — `structured-address-for-sepa` off: the 3 structured fields are ignored entirely
    // (not read, not persisted), matching every sibling handler gated in c8c014007
    // (ContactsLocationAddressHandler#applyGeoLocFields, WarehouseLocationHandler#applyGeoLocFields,
    // BpLocationHandler). Falls back to the pre-ETP-5806 behaviour: only the legacy free-text
    // `address` populates Address1.
    boolean structuredAddressEnabled = StructuredAddressComposer.isEnabled();
    String effectiveStreetName = structuredAddressEnabled ? streetName : null;
    // The structured fields take priority over the legacy free-text `address`: the servlet already
    // guarantees streetName+buildingNumber are both present whenever the structured block was used
    // at all (buildingName alone can never reach here).
    if (StringUtils.isNotBlank(effectiveStreetName)) {
      location.setOBSEPAStreetName(effectiveStreetName.trim());
      location.setOBSEPABuildingNumber(StringUtils.trimToNull(buildingNumber));
      location.setOBSEPABuildingName(StringUtils.trimToNull(buildingName));
      ComposedAddress composed = StructuredAddressComposer.composeAddressLines(effectiveStreetName,
          buildingNumber, buildingName);
      location.setAddressLine1(composed.getAddress1());
      location.setAddressLine2(composed.getAddress2());
    } else if (StringUtils.isNotBlank(address)) {
      location.setAddressLine1(address.trim());
    }
    // ETP-5806 — City is independent of the composed address lines above (it is its own
    // C_Location.City column, not part of Address1/2), so it is set regardless of which branch
    // populated the address lines.
    if (StringUtils.isNotBlank(cityName)) {
      location.setCityName(cityName.trim());
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
