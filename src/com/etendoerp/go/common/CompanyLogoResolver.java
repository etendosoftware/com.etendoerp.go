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

package com.etendoerp.go.common;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.ClientInformation;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.OrganizationInformation;

/**
 * The single source of truth for "which logo does this company print" (ETP-5541).
 *
 * <p>The Organization screen in Etendo GO saves the logo to
 * {@code AD_OrgInfo.YourCompanyDocumentImage}, but the session (every GO printable) and the
 * customer portal used to read {@code AD_ClientInfo.YourCompanyDocumentImage} — the client's
 * onboarding default — so an uploaded logo never reached a document. Both now call this class,
 * which resolves, in order:</p>
 * <ol>
 *   <li>{@code AD_OrgInfo.YourCompanyDocumentImage} of {@code orgId}, when given and it belongs
 *       to {@code clientId};</li>
 *   <li>the first {@code AD_OrgInfo} of the client with a non-null logo, ordered by
 *       {@code AD_Org_ID} — the same criterion the jsreport branding uses
 *       ({@code resolveCompanyLogoDataUrl} in {@code report-branding.js}), so the in-app PDFs
 *       and the server-rendered reports agree;</li>
 *   <li>{@code AD_ClientInfo.YourCompanyDocumentImage} — the previous behaviour, kept as the
 *       last resort for tenants that never uploaded an organization logo.</li>
 * </ol>
 *
 * <p>GO supports a single organization per tenant, so step 2 is what the portal (which has no
 * organization) relies on. Per-document organization resolution is deliberately out of scope.</p>
 *
 * <p>Total: a blank client, a missing row or a lookup failure yields {@code null}, never an
 * exception — the logo is an enrichment of the session and the portal header, and neither may
 * fail over it. Runs its reads in admin mode itself, because the portal has no Etendo session
 * and DAL's client filtering would otherwise match nothing.</p>
 */
public final class CompanyLogoResolver {

  private static final Logger log = LogManager.getLogger(CompanyLogoResolver.class);

  private CompanyLogoResolver() {
  }

  /**
   * Resolves the company's document logo.
   *
   * @param clientId the tenant's {@code AD_Client_ID}; blank yields {@code null}
   * @param orgId    the organization to prefer, or {@code null} to start at the client-wide step
   * @return the logo image, or {@code null} when the tenant has none anywhere
   */
  public static Image resolve(String clientId, String orgId) {
    if (StringUtils.isBlank(clientId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      Image logo = findOrganizationLogo(clientId, orgId);
      if (logo == null) {
        logo = findFirstClientOrganizationLogo(clientId);
      }
      if (logo == null) {
        logo = findClientLogo(clientId);
      }
      return logo;
    } catch (RuntimeException e) {
      log.warn("Could not resolve the company logo for client {} org {}: {}",
          clientId, orgId, e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static Image findOrganizationLogo(String clientId, String orgId) {
    if (StringUtils.isBlank(orgId)) {
      return null;
    }
    // AD_ORGINFO shares its PK with AD_ORG.
    OrganizationInformation info = OBDal.getInstance().get(OrganizationInformation.class, orgId);
    if (info == null || info.getClient() == null
        || !StringUtils.equals(clientId, info.getClient().getId())) {
      return null;
    }
    return info.getYourCompanyDocumentImage();
  }

  private static Image findFirstClientOrganizationLogo(String clientId) {
    OBQuery<OrganizationInformation> query = OBDal.getInstance().createQuery(
        OrganizationInformation.class,
        "as oi where oi.client.id = :clientId and oi.yourCompanyDocumentImage is not null"
            + " order by oi.organization.id");
    query.setNamedParameter("clientId", clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    query.setMaxResult(1);
    OrganizationInformation info = query.uniqueResult();
    return info == null ? null : info.getYourCompanyDocumentImage();
  }

  private static Image findClientLogo(String clientId) {
    ClientInformation info = OBDal.getInstance().get(ClientInformation.class, clientId);
    return info == null ? null : info.getYourCompanyDocumentImage();
  }
}
