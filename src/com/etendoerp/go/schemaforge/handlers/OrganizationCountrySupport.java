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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;

/**
 * Resolves the country an organization belongs to, for the handlers that apply a
 * localization-specific rule only where that localization applies (ETP-5190).
 *
 * <p>Shared by {@code DocumentSequenceHandler} (invoice-series prefix rules) and
 * {@code OrganizationInformationHandler} (tax-identifier rules). Both need the same answer to
 * the same question, and the answer is not a one-liner — hence one place rather than two.
 *
 * <p>The lookup walks the organization and then its ancestors, in {@code getParentList} order,
 * because a freshly provisioned tenant keeps its address on the organization signup created
 * rather than on every node of the tree. Organization {@code "0"} is skipped: the {@code *} org
 * carries no tenant address.
 *
 * <p>Reads run in admin mode with the readable-client/organization filters off: the caller's
 * role is not guaranteed access to an ancestor's {@code AD_OrgInfo}, and NEO requests can carry
 * an {@code OBContext} whose client would otherwise empty the query.
 */
final class OrganizationCountrySupport {

  private static final Logger log = LogManager.getLogger(OrganizationCountrySupport.class);

  static final String SPAIN_ISO_CODE = "ES";

  private static final String STAR_ORG_ID = "0";

  private OrganizationCountrySupport() {
  }

  /**
   * Whether the given organization resolves to Spain.
   *
   * <p>Answers {@code false} — never throws — when the country cannot be established, so a
   * caller can fail open. Refusing a save because an address is not filled in yet would break
   * onboarding, and applying Spanish rules to a tenant whose country is unknown would refuse
   * values that are perfectly legal elsewhere.
   *
   * @param organization the organization to resolve, may be {@code null}
   * @return {@code true} only when a country was found AND it is Spain
   */
  static boolean isSpain(Organization organization) {
    if (organization == null) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      return SPAIN_ISO_CODE.equalsIgnoreCase(findCountryCode(organization));
    } catch (Exception e) {
      log.warn("OrganizationCountrySupport: could not resolve the country of organization {} "
          + "({}); treating it as non-Spanish", organization.getId(), e.getMessage(), e);
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The first ISO country code found on the organization's own {@code AD_OrgInfo} address or,
   * failing that, on an ancestor's.
   *
   * @return the ISO code, or {@code null} when no ancestor has an address with a country
   */
  private static String findCountryCode(Organization organization) {
    List<String> orgIds = OBContext.getOBContext().getOrganizationStructureProvider()
        .getParentList(organization.getId(), true);
    for (String orgId : orgIds) {
      if (StringUtils.equals(orgId, STAR_ORG_ID)) {
        continue;
      }
      OrganizationInformation info = findOrganizationInformation(orgId);
      Location address = info == null ? null : info.getLocationAddress();
      if (address != null && address.getCountry() != null) {
        return address.getCountry().getISOCountryCode();
      }
    }
    return null;
  }

  private static OrganizationInformation findOrganizationInformation(String orgId) {
    OBQuery<OrganizationInformation> query = OBDal.getInstance().createQuery(
        OrganizationInformation.class, "as oi where oi.organization.id = :orgId");
    query.setNamedParameter("orgId", orgId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }
}
