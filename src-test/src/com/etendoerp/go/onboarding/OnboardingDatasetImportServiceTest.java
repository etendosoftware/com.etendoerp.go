/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.onboarding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.service.db.ImportResult;

/**
 * Test class for {@link OnboardingDatasetImportService}.
 */
public class OnboardingDatasetImportServiceTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORGANIZATION_ID = "ORG-1";
  private static final String EMPTY_OPENBRAVO_XML = "<Openbravo/>";

  /** Test method for {@link OnboardingDatasetImportService#importDataset(String, String)}. */
  @Test
  public void testImportDatasetBuildsNormalizedXmlAndDelegatesToImporter() {
    Client client = mockClient(CLIENT_ID);
    Organization org = mockOrganization(ORGANIZATION_ID);

    ImportResult expected = new ImportResult();
    FakeImportService service = new FakeImportService(
        new StubNormalizer("<Openbravo><M_PRODUCT/></Openbravo>"), client, org, expected);

    ImportResult actual = service.importDataset(CLIENT_ID, ORGANIZATION_ID);

    assertSame(expected, actual);
    assertSame(client, service.importedClient);
    assertSame(org, service.importedOrganization);
    assertEquals("<Openbravo><M_PRODUCT/></Openbravo>", service.importedXml);
    assertTrue(service.flushCalled);
    assertTrue(service.summaryLogged);
    assertTrue(service.validationCalled);
  }

  /** Verifies that the import fails when the requested client cannot be resolved. */
  @Test
  public void testImportDatasetFailsWhenClientDoesNotExist() {
    FakeImportService service = new FakeImportService(new StubNormalizer(EMPTY_OPENBRAVO_XML), null,
        mockOrganization(ORGANIZATION_ID), new ImportResult());

    try {
      service.importDataset("missing-client", ORGANIZATION_ID);
      fail("Expected missing client to fail");
    } catch (OBException e) {
      assertNotNull(e.getMessage(), "Exception message should not be null");
      assertTrue(e.getMessage().contains("missing-client"));
    }
  }

  /** Verifies that the import fails when the requested organization cannot be resolved. */
  @Test
  public void testImportDatasetFailsWhenOrganizationDoesNotExist() {
    Client client = mockClient(CLIENT_ID);
    FakeImportService service = new FakeImportService(new StubNormalizer(EMPTY_OPENBRAVO_XML), client,
        null, new ImportResult());

    try {
      service.importDataset(CLIENT_ID, "missing-org");
      fail("Expected missing organization to fail");
    } catch (OBException e) {
      assertNotNull(e.getMessage(), "Exception message should not be null");
      assertTrue(e.getMessage().contains("missing-org"));
    }
  }

  /** Verifies that import errors returned by the importer are surfaced as OBExceptions. */
  @Test
  public void testImportDatasetPropagatesImporterErrors() {
    Client client = mockClient(CLIENT_ID);
    Organization org = mockOrganization(ORGANIZATION_ID);

    FakeImportService service = new FakeImportService(
        new StubNormalizer(EMPTY_OPENBRAVO_XML), client, org, new ErrorImportResult("broken import"));

    try {
      service.importDataset(CLIENT_ID, ORGANIZATION_ID);
      fail("Expected import errors to fail");
    } catch (OBException e) {
      assertNotNull(e.getMessage(), "Exception message should not be null");
      assertTrue(e.getMessage().contains("broken import"));
    }
  }

  /** ETP-4428: a retry must not re-import when the curated seed is already present. */
  @Test
  @DisplayName("importDataset skips (returns null) when the seed is already present")
  public void testImportDatasetSkipsWhenSeedAlreadyPresent() {
    Client client = mockClient(CLIENT_ID);
    Organization org = mockOrganization(ORGANIZATION_ID);
    FakeImportService service = new FakeImportService(new StubNormalizer(EMPTY_OPENBRAVO_XML),
        client, org, new ImportResult());
    service.seedAlreadyPresent = true;

    ImportResult actual = service.importDataset(CLIENT_ID, ORGANIZATION_ID);

    assertNull(actual, "Import should be skipped and return null when the seed is already present");
    assertFalse(service.importXmlCalled, "importXml must not run when the seed is already present");
  }

  /**
   * ETP-4428: exercises the REAL {@code isSeedAlreadyPresent} (the 4-term AND over
   * {@code buildSeedVisibilitySummary}) rather than the fake override. When every entity has at
   * least one visible row, the seed is considered fully present.
   */
  @Test
  @DisplayName("isSeedAlreadyPresent returns true when all four counts are greater than zero")
  public void testIsSeedAlreadyPresentTrueWhenSeedComplete() {
    CountingImportService service = new CountingImportService(1, 1, 1, 1);

    assertTrue(service.isSeedAlreadyPresent(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID)),
        "A fully imported seed (all counts > 0) must be reported as already present");
  }

  /**
   * ETP-4428: a partial import that left no products must NOT be treated as present, so the retry
   * repairs the missing rows.
   */
  @Test
  @DisplayName("isSeedAlreadyPresent returns false when there are no products")
  public void testIsSeedAlreadyPresentFalseWhenNoProducts() {
    CountingImportService service = new CountingImportService(0, 1, 1, 1);

    assertFalse(service.isSeedAlreadyPresent(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID)),
        "Missing products must mark the seed as not present so the re-import repairs it");
  }

  /**
   * ETP-4428: a partial import that left no warehouses must NOT be treated as present.
   */
  @Test
  @DisplayName("isSeedAlreadyPresent returns false when there are no warehouses")
  public void testIsSeedAlreadyPresentFalseWhenNoWarehouses() {
    CountingImportService service = new CountingImportService(1, 0, 1, 1);

    assertFalse(service.isSeedAlreadyPresent(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID)),
        "Missing warehouses must mark the seed as not present so the re-import repairs it");
  }

  /**
   * ETP-4428: a partial import that left no price lists must NOT be treated as present.
   */
  @Test
  @DisplayName("isSeedAlreadyPresent returns false when there are no price lists")
  public void testIsSeedAlreadyPresentFalseWhenNoPriceLists() {
    CountingImportService service = new CountingImportService(1, 1, 0, 1);

    assertFalse(service.isSeedAlreadyPresent(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID)),
        "Missing price lists must mark the seed as not present so the re-import repairs it");
  }

  /**
   * ETP-4428: the core partial-failure scenario. An import that succeeded for products, warehouses
   * and price lists but was cut off before creating the financial accounts must NOT be treated as
   * present, so the retry finishes the job instead of skipping.
   */
  @Test
  @DisplayName("isSeedAlreadyPresent returns false when there are no financial accounts")
  public void testIsSeedAlreadyPresentFalseWhenNoFinancialAccounts() {
    CountingImportService service = new CountingImportService(1, 1, 1, 0);

    assertFalse(service.isSeedAlreadyPresent(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID)),
        "A partial import missing only financial accounts must be re-imported, not skipped");
  }

  /**
   * ETP-4428: nothing imported at all is obviously not present.
   */
  @Test
  @DisplayName("isSeedAlreadyPresent returns false when nothing was imported")
  public void testIsSeedAlreadyPresentFalseWhenEmpty() {
    CountingImportService service = new CountingImportService(0, 0, 0, 0);

    assertFalse(service.isSeedAlreadyPresent(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID)),
        "An empty tenant must never be reported as already seeded");
  }

  /**
   * ETP-4428: a successful XML import that yields no {@link ImportResult} must fail loudly instead
   * of silently reporting success — otherwise a subsequent retry would wrongly believe the seed was
   * already imported and skip the repair.
   */
  @Test
  @DisplayName("importDataset fails when the importer returns no result")
  public void testImportDatasetFailsWhenImporterReturnsNoResult() {
    Client client = mockClient(CLIENT_ID);
    Organization org = mockOrganization(ORGANIZATION_ID);
    FakeImportService service = new FakeImportService(new StubNormalizer(EMPTY_OPENBRAVO_XML),
        client, org, null);

    try {
      service.importDataset(CLIENT_ID, ORGANIZATION_ID);
      fail("Expected a null import result to fail");
    } catch (OBException e) {
      assertNotNull(e.getMessage(), "Exception message should not be null");
      assertTrue(e.getMessage().contains("no result"),
          "The failure must explain that the importer returned no result");
    }
    assertTrue(service.importXmlCalled, "importXml must have been attempted before failing");
  }

  /**
   * ETP-4428: exercises the REAL {@code validateImportedSeed} success path (all four counts &gt; 0),
   * which also runs the real {@code logImportedSeedSummary}. A complete seed must validate without
   * throwing.
   */
  @Test
  @DisplayName("validateImportedSeed passes when every seed entity has visible rows")
  public void testValidateImportedSeedPassesWhenComplete() {
    CountingImportService service = new CountingImportService(1, 1, 1, 1);

    assertDoesNotThrow(() -> service.validateImportedSeed(mockClient(CLIENT_ID),
        mockOrganization(ORGANIZATION_ID)));
  }

  /**
   * ETP-4428: the REAL {@code validateImportedSeed} must reject an incomplete import (here, missing
   * financial accounts) with a diagnostic that names the empty entity counts, so a broken import
   * never passes as successful.
   */
  @Test
  @DisplayName("validateImportedSeed throws with a diagnostic when a seed entity is empty")
  public void testValidateImportedSeedThrowsWhenIncomplete() {
    CountingImportService service = new CountingImportService(1, 1, 1, 0);

    try {
      service.validateImportedSeed(mockClient(CLIENT_ID), mockOrganization(ORGANIZATION_ID));
      fail("Expected an incomplete seed to fail validation");
    } catch (OBException e) {
      assertNotNull(e.getMessage(), "Exception message should not be null");
      assertTrue(e.getMessage().contains("financialAccounts=0"),
          "The diagnostic must report the empty entity counts");
    }
  }

  private Client mockClient(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    return client;
  }

  private Organization mockOrganization(String organizationId) {
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(organizationId);
    return organization;
  }


  private static final class StubNormalizer extends OnboardingDatasetNormalizer {
    private final String xml;

    private StubNormalizer(String xml) {
      this.xml = xml;
    }

    @Override
    public String buildDatasetXml(String targetOrganizationId) {
      return xml;
    }
  }

  private static final class ErrorImportResult extends ImportResult {
    private final String errorMessage;

    private ErrorImportResult(String errorMessage) {
      this.errorMessage = errorMessage;
    }

    @Override
    public boolean hasErrorOccured() {
      return true;
    }

    @Override
    public String getErrorMessages() {
      return errorMessage;
    }
  }

  /**
   * Exercises the REAL {@link OnboardingDatasetImportService#isSeedAlreadyPresent} by stubbing only
   * the two persistence seams it ultimately relies on: {@code resolveSystemOrganization} and
   * {@code countByClientAndOrganization}. The private {@code buildSeedVisibilitySummary} and the
   * 4-term AND therefore run for real. Each per-entity total is the sum of the system-org and
   * target-org calls; this stub returns the configured count once (for the system org) and 0 for
   * the target org, so the resulting total equals the configured value.
   */
  private static final class CountingImportService extends OnboardingDatasetImportService {
    private final Organization systemOrganization = mock(Organization.class);
    private final long productCount;
    private final long warehouseCount;
    private final long priceListCount;
    private final long financialAccountCount;

    private CountingImportService(long productCount, long warehouseCount, long priceListCount,
        long financialAccountCount) {
      this.productCount = productCount;
      this.warehouseCount = warehouseCount;
      this.priceListCount = priceListCount;
      this.financialAccountCount = financialAccountCount;
    }

    @Override
    protected Organization resolveSystemOrganization() {
      return systemOrganization;
    }

    @Override
    protected <T extends BaseOBObject> long countByClientAndOrganization(Class<T> entityClass,
        String clientProperty, String organizationProperty, Client client,
        Organization organization) {
      if (organization != systemOrganization) {
        return 0L;
      }
      if (entityClass == Product.class) {
        return productCount;
      }
      if (entityClass == Warehouse.class) {
        return warehouseCount;
      }
      if (entityClass == PriceList.class) {
        return priceListCount;
      }
      if (entityClass == FIN_FinancialAccount.class) {
        return financialAccountCount;
      }
      return fail("Unexpected entity counted: " + entityClass);
    }
  }

  private static class FakeImportService extends OnboardingDatasetImportService {
    private final Client client;
    private final Organization organization;
    private final ImportResult result;
    private Client importedClient;
    private Organization importedOrganization;
    private String importedXml;
    private boolean summaryLogged;
    private boolean validationCalled;
    private boolean flushCalled;
    private boolean importXmlCalled;
    private boolean seedAlreadyPresent = false;

    private FakeImportService(OnboardingDatasetNormalizer normalizer, Client client,
        Organization organization, ImportResult result) {
      super(normalizer);
      this.client = client;
      this.organization = organization;
      this.result = result;
    }

    @Override
    protected Client resolveClient(String clientId) {
      return client;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return organization;
    }

    @Override
    protected boolean isSeedAlreadyPresent(Client client, Organization organization) {
      return seedAlreadyPresent;
    }

    @Override
    protected ImportResult importXml(Client client, Organization organization, String xml) {
      this.importXmlCalled = true;
      this.importedClient = client;
      this.importedOrganization = organization;
      this.importedXml = xml;
      return result;
    }

    @Override
    protected void flushImport() {
      flushCalled = true;
    }


    @Override
    protected void validateImportedSeed(Client client, Organization organization) {
      summaryLogged = true;
      validationCalled = true;
    }

    @Override
    protected void logImportedSeedSummary(Client client, Organization organization,
        SeedVisibilitySummary summary) {
      summaryLogged = true;
    }
  }
}
