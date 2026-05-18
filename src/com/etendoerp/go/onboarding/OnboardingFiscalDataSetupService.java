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

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIDescription;

/**
 * Seeds SII descriptions for a newly created client.
 */
public class OnboardingFiscalDataSetupService {

  private static final String SII_VENTAS = "Ventas";
  private static final String SII_COMPRAS = "Compras";

  /**
   * Creates SII descriptions for the given client if not already present.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void setup(String clientId, String orgId, String adminUserId, String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = resolveClient(clientId);
        Organization org = resolveOrganization(orgId);
        if (client == null) {
          throw new OBException("Client not found for fiscal data setup: " + clientId);
        }
        if (org == null) {
          throw new OBException("Organization not found for fiscal data setup: " + orgId);
        }
        createSiiDescriptionsIfAbsent(client, org);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected void createSiiDescriptionsIfAbsent(Client client, Organization org) {
    if (!siiDescriptionExists(client, true)) {
      saveSiiDescription(buildSiiDescription(client, org, SII_VENTAS, true, false));
    }
    if (!siiDescriptionExists(client, false)) {
      saveSiiDescription(buildSiiDescription(client, org, SII_COMPRAS, false, true));
    }
  }

  protected AEATSIIDescription buildSiiDescription(Client client, Organization org,
      String name, boolean isSales, boolean isPurchase) {
    AEATSIIDescription desc = OBProvider.getInstance().get(AEATSIIDescription.class);
    desc.setNewOBObject(true);
    desc.setClient(client);
    desc.setOrganization(org);
    desc.setActive(true);
    desc.setName(name);
    desc.setDescription(name);
    desc.setSales(isSales);
    desc.setPurchase(isPurchase);
    desc.setDefault(true);
    return desc;
  }

  protected boolean siiDescriptionExists(Client client, boolean isSales) {
    OBCriteria<AEATSIIDescription> criteria = OBDal.getInstance()
        .createCriteria(AEATSIIDescription.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(AEATSIIDescription.PROPERTY_CLIENT, client));
    String typeProperty = isSales
        ? AEATSIIDescription.PROPERTY_ISSALES : AEATSIIDescription.PROPERTY_ISPURCHASE;
    criteria.add(Restrictions.eq(typeProperty, true));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  protected void saveSiiDescription(AEATSIIDescription desc) {
    OBDal.getInstance().save(desc);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }

  protected OBContext captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId,
      String clientId, String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void restoreExecutionContext(OBContext previousContext) {
    OBContext.setOBContext(previousContext);
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  private void validateContext(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException("Missing client for fiscal data setup");
    }
    if (orgId == null || orgId.isEmpty()) {
      throw new OBException("Missing organization for fiscal data setup");
    }
    if (adminUserId == null || adminUserId.isEmpty()) {
      throw new OBException("Missing admin user for fiscal data setup");
    }
    if (adminRoleId == null || adminRoleId.isEmpty()) {
      throw new OBException("Missing admin role for fiscal data setup");
    }
  }
}
