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

import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.enterprise.Organization;

/** Seeds a minimal customer so the first Sales Invoice selector is not empty after onboarding. */
public class OnboardingDefaultCustomerService {

  static final String DEFAULT_CUSTOMER_SEARCH_KEY = "ONBOARDING_DEFAULT_CUSTOMER";
  static final String DEFAULT_CUSTOMER_NAME = "Default Customer";

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
        BusinessPartner existing = findExistingDefaultCustomer(clientId, orgId);
        if (existing != null) {
          return existing.getId();
        }

        Client client = resolveClient(clientId);
        Organization organization = resolveOrganization(orgId);
        Category bpGroup = resolveBusinessPartnerGroup(clientId);
        if (client == null) {
          throw new OBException("Client not found for onboarding default customer: " + clientId);
        }
        if (organization == null) {
          throw new OBException("Organization not found for onboarding default customer: " + orgId);
        }
        if (bpGroup == null) {
          throw new OBException("Business partner group not found for onboarding default customer");
        }

        BusinessPartner customer = createDefaultCustomer(client, organization, bpGroup);
        saveCustomer(customer);
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

  protected Category resolveBusinessPartnerGroup(String clientId) {
    OBCriteria<Category> criteria = OBDal.getInstance().createCriteria(Category.class);
    criteria.add(Restrictions.eq(Category.PROPERTY_CLIENT,
        OBDal.getInstance().get(Client.class, clientId)));
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
