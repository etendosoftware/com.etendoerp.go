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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
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
      assertNotNull("Exception message should not be null", e.getMessage());
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
      assertNotNull("Exception message should not be null", e.getMessage());
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
      assertNotNull("Exception message should not be null", e.getMessage());
      assertTrue(e.getMessage().contains("broken import"));
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
    protected ImportResult importXml(Client client, Organization organization, String xml) {
      this.importedClient = client;
      this.importedOrganization = organization;
      this.importedXml = xml;
      return result;
    }

    @Override
    protected void commitImport() {
      // No DAL commit in unit tests.
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
