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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.common.WarehouseLookupHelper;

/**
 * Copies the org's fiscal address values onto the default warehouse location, leaving country
 * untouched.
 */
public class OnboardingWarehouseAddressService extends OnboardingContextSupport {

  private static final Logger log = LogManager.getLogger(OnboardingWarehouseAddressService.class);

  public void alignDefaultWarehouseAddress(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = OBDal.getInstance().get(Client.class, clientId);
        Organization org = resolveOrganization(orgId);
        if (client == null) {
          throw new OBException("Client not found for warehouse-address alignment: " + clientId);
        }
        if (org == null) {
          throw new OBException(
              "Organization not found for warehouse-address alignment: " + orgId);
        }
        alignWarehouseAddress(client, org);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected void alignWarehouseAddress(Client client, Organization org) {
    Location fiscalLocation = resolveFiscalLocation(org);
    if (fiscalLocation == null) {
      throw new OBException(
          "No fiscal address found for warehouse-address alignment on org: " + org.getId());
    }

    Warehouse warehouse = WarehouseLookupHelper.findFirstActiveWarehouse(client, org);
    if (warehouse == null) {
      log.warn("No active warehouse found for client {} / org {}; skipping warehouse-address"
          + " alignment", client.getId(), org.getId());
      return;
    }

    Location warehouseLocation = warehouse.getLocationAddress();
    if (warehouseLocation == null) {
      warehouseLocation = createWarehouseLocation(client, org, fiscalLocation);
      warehouse.setLocationAddress(warehouseLocation);
      OBDal.getInstance().save(warehouse);
    } else if (warehouseLocation.getId().equals(fiscalLocation.getId())) {
      // Already the same record (should not happen by construction, but never touch it).
      return;
    }

    copyAddressFields(fiscalLocation, warehouseLocation);
    OBDal.getInstance().save(warehouseLocation);
  }

  protected Location resolveFiscalLocation(Organization org) {
    // AD_ORGINFO shares its PK with AD_ORG, so fetch it directly by id.
    OrganizationInformation orgInfo =
        OBDal.getInstance().get(OrganizationInformation.class, org.getId());
    return orgInfo != null ? orgInfo.getLocationAddress() : null;
  }

  protected Location createWarehouseLocation(Client client, Organization org, Location fiscalLocation) {
    Location location = OBProvider.getInstance().get(Location.class);
    location.setNewOBObject(true);
    location.setClient(client);
    location.setOrganization(org);
    location.setActive(true);
    location.setCountry(fiscalLocation.getCountry());
    OBDal.getInstance().save(location);
    return location;
  }

  // Copies every address field except country; blank in src stays blank in dst.
  protected void copyAddressFields(Location src, Location dst) {
    dst.setAddressLine1(src.getAddressLine1());
    dst.setAddressLine2(src.getAddressLine2());
    dst.setCityName(src.getCityName());
    dst.setPostalCode(src.getPostalCode());
    dst.setPostalAdd(src.getPostalAdd());
    dst.setRegion(src.getRegion());
    dst.setRegionName(src.getRegionName());
    dst.setCity(src.getCity());
  }

  @Override
  protected String contextSubject() {
    return "warehouse-address alignment";
  }
}
