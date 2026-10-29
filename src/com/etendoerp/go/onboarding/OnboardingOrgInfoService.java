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
   * @param input       the signup form's fiscal/address data; {@code null} is read as
   *                    {@link OrgInfoInput#EMPTY}. See {@link OrgInfoInput} for what each field
   *                    means and {@link #applyAddressFields} for how the address ones are used
   */
  public void ensureOrgInfo(String clientId, String orgId, String adminUserId, String adminRoleId,
      OrgInfoInput input) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OrgInfoInput orgInfoInput = OrgInfoInput.orEmpty(input);
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
        ensureOrgInfoLocation(client, org, orgInfoInput);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected void ensureOrgInfoLocation(Client client, Organization org, OrgInfoInput input) {
    OrganizationInformation orgInfo = resolveOrgInfo(org);
    if (orgInfo == null) {
      orgInfo = createOrgInfo(client, org);
    }
    applyTaxId(orgInfo, input.taxId());
    Location existing = orgInfo.getLocationAddress();
    if (existing != null) {
      completeExistingLocation(existing, input);
      return;
    }
    Country country = resolveCountry(input.countryCode());
    if (country == null) {
      throw new OBException("No country available to locate onboarding organization");
    }
    Location location = createLocation(client, org, country, input);
    orgInfo.setLocationAddress(location);
    OBDal.getInstance().save(orgInfo);
  }

  /**
   * Writes the signup's address onto a location the organization <b>already</b> has, but only
   * while that location is still blank.
   *
   * <p>ETP-5806 follow-up — a tenant claimed from the pool (ETP-5389) always reaches this step
   * with a location already linked: {@code TenantPoolProvisioner} runs the same chain with eight
   * empty address strings, so the pool build creates an empty {@code C_LOCATION} and links it.
   * This method used to be an unconditional early return ("already located, leave it untouched"),
   * which made {@link #createLocation} — the only place the 3 SEPA columns, the composed
   * Address1/Address2 and the city are ever written — unreachable on the pooled path. The whole
   * address entered in the onboarding wizard was discarded, silently, moments after the servlet
   * had validated Street/Number/City as mandatory.
   *
   * <p>The blank check is what keeps that original intent: an address set by hand in the UI, or by
   * anything else, is still never overwritten. Only a location with nothing in it — which is
   * exactly what a pooled tenant's is — gets completed, and the fields are written by the same
   * {@link #applyAddressFields} the classic path uses, so the two paths cannot drift apart.
   */
  protected void completeExistingLocation(Location location, OrgInfoInput input) {
    if (!hasAddressToWrite(input) || !isAddressBlank(location)) {
      return;
    }
    applyAddressFields(location, input);
    OBDal.getInstance().save(location);
  }

  private static boolean hasAddressToWrite(OrgInfoInput input) {
    return StringUtils.isNotBlank(input.address()) || StringUtils.isNotBlank(input.streetName())
        || StringUtils.isNotBlank(input.cityName());
  }

  /**
   * Whether the location carries no address at all. Checked on every field this service can write
   * (and {@code Address2}, which only the composer sets), so a location populated through any
   * other route is recognized as owned by someone else and left alone.
   */
  private static boolean isAddressBlank(Location location) {
    return StringUtils.isBlank(location.getAddressLine1())
        && StringUtils.isBlank(location.getAddressLine2())
        && StringUtils.isBlank(location.getOBSEPAStreetName())
        && StringUtils.isBlank(location.getCityName());
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
      OrgInfoInput input) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(org);
    location.setActive(true);
    location.setCountry(country);
    applyAddressFields(location, input);
    OBDal.getInstance().save(location);
    return location;
  }

  /**
   * Writes the signup's address onto {@code location}, whether it was just created
   * ({@link #createLocation}, classic path) or already existed and is still blank
   * ({@link #completeExistingLocation}, pooled path). The one place both paths share, so the flag
   * gating, the composed lines and the city can never diverge between them.
   */
  protected void applyAddressFields(Location location, OrgInfoInput input) {
    String address = input.address();
    String streetName = input.streetName();
    String buildingNumber = input.buildingNumber();
    String buildingName = input.buildingName();
    String cityName = input.cityName();
    // ETP-5806 — `structured-address-for-sepa` off: the 3 structured fields are ignored entirely
    // (not read, not persisted), matching every sibling handler gated in c8c014007
    // (ContactsLocationAddressHandler#applyGeoLocFields, WarehouseLocationHandler#applyGeoLocFields,
    // BpLocationHandler). Falls back to the pre-ETP-5806 behaviour: only the legacy free-text
    // `address` populates Address1. Evaluated for the onboarding account (ETP-5806 staged
    // rollout), the same email the servlet validated the block with a moment earlier — the two
    // must agree, or a targeted account's Street/Number would pass validation and then be
    // dropped here by a global-default evaluation.
    boolean structuredAddressEnabled = StructuredAddressComposer.isEnabled(input.accountEmail());
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
