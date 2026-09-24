/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"). You may not use this file except in compliance with
 * the License. You may obtain a copy at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.onboarding;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.schemaforge.util.NeoImageHelper;

/** Copies the demo tenant's company identity and fiscal details into its productive organization. */
public class OnboardingCompanyProfileTransferService {

  private static final String ROOT_ORGANIZATION_ID = "0";

  /**
   * Copies populated company-profile fields from the single active business organization owned by
   * {@code sourceClientId} onto {@code targetOrgId}. Source selection is scoped by client ID and
   * rejects ambiguity; names are never used to identify either tenant. The productive client's
   * currency is intentionally preserved because it is selected by the paid onboarding request.
   *
   * @param sourceClientId demo client ID, or blank when no demo tenant exists
   * @param targetClientId newly provisioned productive client ID
   * @param targetOrgId exact productive business organization ID
   */
  public void copy(String sourceClientId, String targetClientId, String targetOrgId) {
    if (StringUtils.isBlank(sourceClientId)) {
      return;
    }
    if (StringUtils.isBlank(targetClientId) || StringUtils.isBlank(targetOrgId)) {
      throw new OBException("Missing productive client or organization for company profile transfer");
    }

    OBContext.setAdminMode(true);
    try {
      Client targetClient = OBDal.getInstance().get(Client.class, targetClientId);
      Organization targetOrg = OBDal.getInstance().get(Organization.class, targetOrgId);
      if (targetClient == null || targetOrg == null
          || targetOrg.getClient() == null
          || !StringUtils.equals(targetClientId, targetOrg.getClient().getId())) {
        throw new OBException("Productive organization does not belong to the target client");
      }

      List<Organization> sourceOrganizations = findSourceOrganizations(sourceClientId);
      if (sourceOrganizations.isEmpty()) {
        throw new OBException("Demo client has no active business organization: " + sourceClientId);
      }
      if (sourceOrganizations.size() > 1) {
        throw new OBException("Demo client has multiple active business organizations: "
            + sourceClientId);
      }

      Organization sourceOrg = sourceOrganizations.get(0);
      OrganizationInformation sourceInfo = findOrganizationInformation(sourceOrg.getId());
      OrganizationInformation targetInfo = findOrganizationInformation(targetOrgId);

      copyOrganizationFields(sourceOrg, targetOrg);
      if (targetInfo == null) {
        targetInfo = createOrganizationInformation(targetClient, targetOrg);
      }
      if (sourceInfo != null) {
        copyFiscalFields(sourceInfo, targetInfo, targetClient, targetOrg);
      }

      OBDal.getInstance().save(targetOrg);
      OBDal.getInstance().save(targetInfo);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private List<Organization> findSourceOrganizations(String sourceClientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as o where o.client.id = :clientId and o.id <> :rootId and o.active = true");
    query.setNamedParameter("clientId", sourceClientId);
    query.setNamedParameter("rootId", ROOT_ORGANIZATION_ID);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  private OrganizationInformation findOrganizationInformation(String orgId) {
    OBQuery<OrganizationInformation> query = OBDal.getInstance().createQuery(
        OrganizationInformation.class, "as oi where oi.organization.id = :orgId");
    query.setNamedParameter("orgId", orgId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private void copyOrganizationFields(Organization source, Organization target) {
    copyIfPresent(source.getName(), target::setName);
    copyIfPresent(source.getSocialName(), target::setSocialName);
    copyIfPresent(source.getEtgoBusinessType(), target::setEtgoBusinessType);
  }

  private void copyFiscalFields(OrganizationInformation source, OrganizationInformation target,
      Client targetClient, Organization targetOrg) {
    target.setClient(targetClient);
    target.setOrganization(targetOrg);
    copyIfPresent(source.getTaxID(), target::setTaxID);

    copyLogo(source.getYourCompanyDocumentImage(), target, targetClient, targetOrg);
    Location sourceLocation = source.getLocationAddress();
    if (sourceLocation == null) {
      return;
    }
    Location targetLocation = target.getLocationAddress();
    if (!isTargetOwnedLocation(targetLocation, targetClient, targetOrg)) {
      targetLocation = createTargetLocation(targetClient, targetOrg);
      target.setLocationAddress(targetLocation);
    }
    copyIfPresent(sourceLocation.getAddressLine1(), targetLocation::setAddressLine1);
    copyIfPresent(sourceLocation.getAddressLine2(), targetLocation::setAddressLine2);
    copyIfPresent(sourceLocation.getPostalCode(), targetLocation::setPostalCode);
    copyIfPresent(sourceLocation.getCityName(), targetLocation::setCityName);
    if (sourceLocation.getCountry() != null) {
      targetLocation.setCountry(sourceLocation.getCountry());
    }
    OBDal.getInstance().save(targetLocation);

  }

  private boolean isTargetOwnedLocation(Location location, Client targetClient,
      Organization targetOrg) {
    return location != null
        && location.getClient() != null
        && StringUtils.equals(targetClient.getId(), location.getClient().getId())
        && location.getOrganization() != null
        && StringUtils.equals(targetOrg.getId(), location.getOrganization().getId());
  }

  private void copyLogo(Image sourceImage, OrganizationInformation targetInfo,
      Client targetClient, Organization targetOrg) {
    if (sourceImage == null) {
      return;
    }
    byte[] sourceBytes = sourceImage.getBindaryData();
    if (sourceBytes == null || sourceBytes.length == 0) {
      return;
    }

    String imageName = StringUtils.defaultIfBlank(sourceImage.getName(), "image");
    String mimeType = StringUtils.defaultIfBlank(sourceImage.getMimetype(), NeoImageHelper.MIME_PNG);
    Image targetImage = targetInfo.getYourCompanyDocumentImage();
    if (isMatchingTargetImage(targetImage, imageName, mimeType, sourceBytes, targetClient,
        targetOrg)) {
      return;
    }

    Image copy = NeoImageHelper.createImage(imageName, mimeType, sourceBytes, targetClient,
        targetOrg);
    targetInfo.setYourCompanyDocumentImage(copy);
  }

  private boolean isMatchingTargetImage(Image targetImage, String imageName, String mimeType,
      byte[] sourceBytes, Client targetClient, Organization targetOrg) {
    return targetImage != null
        && targetImage.getClient() != null
        && StringUtils.equals(targetClient.getId(), targetImage.getClient().getId())
        && targetImage.getOrganization() != null
        && StringUtils.equals(targetOrg.getId(), targetImage.getOrganization().getId())
        && StringUtils.equals(imageName, targetImage.getName())
        && StringUtils.equals(mimeType, targetImage.getMimetype())
        && Arrays.equals(sourceBytes, targetImage.getBindaryData());
  }

  private OrganizationInformation createOrganizationInformation(Client client,
      Organization organization) {
    OrganizationInformation info = OBProvider.getInstance().get(OrganizationInformation.class);
    info.setNewOBObject(true);
    // AD_OrgInfo shares the organization primary key.
    info.setId(organization.getId());
    info.setClient(client);
    info.setOrganization(organization);
    info.setActive(true);
    OBDal.getInstance().save(info);
    return info;
  }

  private Location createTargetLocation(Client client, Organization organization) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(organization);
    location.setActive(true);
    OBDal.getInstance().save(location);
    return location;
  }

  private void copyIfPresent(String value, java.util.function.Consumer<String> setter) {
    String normalized = StringUtils.trimToNull(value);
    if (normalized != null) {
      setter.accept(normalized);
    }
  }
}
