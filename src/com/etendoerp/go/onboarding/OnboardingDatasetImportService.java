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
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.DataImportService;
import org.openbravo.service.db.ImportResult;

/**
 * Imports the curated onboarding dataset into a newly created tenant.
 */
public class OnboardingDatasetImportService {

  private static final Logger log = LogManager.getLogger(OnboardingDatasetImportService.class);

  private final OnboardingDatasetNormalizer normalizer;

  /**
   * Creates the service using the default onboarding dataset normalizer.
   */
  public OnboardingDatasetImportService() {
    this(new OnboardingDatasetNormalizer());
  }

  OnboardingDatasetImportService(OnboardingDatasetNormalizer normalizer) {
    this.normalizer = normalizer;
  }

  /**
   * Normalizes and imports the onboarding dataset for the given client and organization.
   *
   * @param clientId the target client identifier
   * @param orgId the target organization identifier
   * @return the import result returned by the Openbravo data import service
   */
  public ImportResult importDataset(String clientId, String orgId) {
    Client client = resolveClient(clientId);
    if (client == null) {
      throw new OBException("Client not found with ID: " + clientId);
    }

    Organization organization = resolveOrganization(orgId);
    if (organization == null) {
      throw new OBException("Organization not found with ID: " + orgId);
    }

    String xml = normalizer.buildDatasetXml(orgId);

    ImportResult result = importXml(client, organization, xml);
    if (result == null) {
      throw new OBException("Onboarding dataset import returned no result");
    }
    if (result.hasErrorOccured()) {
      throw new OBException(result.getErrorMessages());
    }
    commitImport();
    validateImportedSeed(client, organization);
    return result;
  }

  protected Client resolveClient(String clientId) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(Client.class, clientId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected Organization resolveOrganization(String orgId) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(Organization.class, orgId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected ImportResult importXml(Client client, Organization organization, String xml) {
    OBContext.setAdminMode(true);
    try {
      return DataImportService.getInstance().importDataFromXML(client, organization, xml, null);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected void commitImport() {
    OBContext.setAdminMode(true);
    try {
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Validates that the imported dataset contains visible seed data for the target organization.
   *
   * @param client the client for which the dataset was imported
   * @param organization the organization for which the dataset was imported
   * @throws OBException if no visible seed data is found for the target organization
   */
  protected void validateImportedSeed(Client client, Organization organization) {
    SeedVisibilitySummary summary = buildSeedVisibilitySummary(client, organization);

    logImportedSeedSummary(client, organization, summary);

    if (summary.totalProducts() == 0 || summary.totalWarehouses() == 0
        || summary.totalPriceLists() == 0 || summary.totalFinancialAccounts() == 0) {
      throw new OBException("Onboarding dataset import completed but no visible seed data was found "
          + "for client " + client.getId() + "/org " + organization.getId()
          + " [products=" + summary.totalProducts()
          + ", warehouses=" + summary.totalWarehouses()
          + ", priceLists=" + summary.totalPriceLists()
          + ", financialAccounts=" + summary.totalFinancialAccounts() + "]");
    }
  }

  private SeedVisibilitySummary buildSeedVisibilitySummary(Client client, Organization organization) {
    Organization systemOrganization = resolveSystemOrganization();
    return new SeedVisibilitySummary(
        countByOrganization(Product.class, Product.PROPERTY_CLIENT, Product.PROPERTY_ORGANIZATION,
            client, systemOrganization, organization),
        countByOrganization(Warehouse.class, Warehouse.PROPERTY_CLIENT,
            Warehouse.PROPERTY_ORGANIZATION, client, systemOrganization, organization),
        countByOrganization(PriceList.class, PriceList.PROPERTY_CLIENT,
            PriceList.PROPERTY_ORGANIZATION, client, systemOrganization, organization),
        countByOrganization(FIN_FinancialAccount.class, FIN_FinancialAccount.PROPERTY_CLIENT,
            FIN_FinancialAccount.PROPERTY_ORGANIZATION, client, systemOrganization, organization));
  }

  private <T extends BaseOBObject> VisibilityCount countByOrganization(Class<T> entityClass,
      String clientProperty, String organizationProperty, Client client,
      Organization systemOrganization, Organization targetOrganization) {
    return new VisibilityCount(
        countByClientAndOrganization(entityClass, clientProperty, organizationProperty, client,
            systemOrganization),
        countByClientAndOrganization(entityClass, clientProperty, organizationProperty, client,
            targetOrganization));
  }

  protected <T extends BaseOBObject> long countByClientAndOrganization(Class<T> entityClass,
      String clientProperty, String organizationProperty, Client client, Organization organization) {
    OBContext.setAdminMode(true);
    try {
      OBCriteria<T> criteria = OBDal.getInstance().createCriteria(entityClass);
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);
      criteria.add(Restrictions.eq(clientProperty, client));
      criteria.add(Restrictions.eq(organizationProperty, organization));
      return criteria.count();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected void logImportedSeedSummary(Client client, Organization organization,
      SeedVisibilitySummary summary) {
    log.info("Onboarding dataset visibility summary for client {} org {} -> products[org0={}, targetOrg={}], "
        + "warehouses[org0={}, targetOrg={}], priceLists[org0={}, targetOrg={}], financialAccounts[org0={}, targetOrg={}]",
        client.getId(), organization.getId(),
        summary.products.systemOrganization, summary.products.targetOrganization,
        summary.warehouses.systemOrganization, summary.warehouses.targetOrganization,
        summary.priceLists.systemOrganization, summary.priceLists.targetOrganization,
        summary.financialAccounts.systemOrganization, summary.financialAccounts.targetOrganization);
  }

  protected Organization resolveSystemOrganization() {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(Organization.class, "0");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected static final class VisibilityCount {
    private final long systemOrganization;
    private final long targetOrganization;

    private VisibilityCount(long systemOrganization, long targetOrganization) {
      this.systemOrganization = systemOrganization;
      this.targetOrganization = targetOrganization;
    }

    private long total() {
      return systemOrganization + targetOrganization;
    }
  }

  protected static final class SeedVisibilitySummary {
    private final VisibilityCount products;
    private final VisibilityCount warehouses;
    private final VisibilityCount priceLists;
    private final VisibilityCount financialAccounts;

    private SeedVisibilitySummary(VisibilityCount products, VisibilityCount warehouses,
        VisibilityCount priceLists, VisibilityCount financialAccounts) {
      this.products = products;
      this.warehouses = warehouses;
      this.priceLists = priceLists;
      this.financialAccounts = financialAccounts;
    }

    private long totalProducts() {
      return products.total();
    }

    private long totalWarehouses() {
      return warehouses.total();
    }

    private long totalPriceLists() {
      return priceLists.total();
    }

    private long totalFinancialAccounts() {
      return financialAccounts.total();
    }
  }
}
