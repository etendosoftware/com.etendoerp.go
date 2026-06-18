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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIDescription;

public class OnboardingFiscalDataSetupServiceTest {

  // ---------------------------------------------------------------------------------------------
  // setup() — client/org resolution failures (real branches)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testSetupFailsWhenClientNotFound() {
    TestableService service = new TestableService();
    service.clientToReturn = null;
    try {
      service.setup("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing client");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Client not found for fiscal data setup"));
    }
  }

  @Test
  public void testSetupFailsWhenOrganizationNotFound() {
    TestableService service = new TestableService();
    service.orgToReturn = null;
    try {
      service.setup("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing organization");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found for fiscal data setup"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // buildSiiDescription() — real OBProvider-backed body
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testBuildSiiDescriptionPopulatesAllFields() {
    OnboardingFiscalDataSetupService service = new OnboardingFiscalDataSetupService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);

    OBProvider provider = mock(OBProvider.class);
    AEATSIIDescription desc = mock(AEATSIIDescription.class);
    when(provider.get(AEATSIIDescription.class)).thenReturn(desc);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      AEATSIIDescription result = service.buildSiiDescription(client, org, "Ventas", true, false);
      assertSame(desc, result);
    }

    verify(desc).setNewOBObject(true);
    verify(desc).setClient(client);
    verify(desc).setOrganization(org);
    verify(desc).setActive(true);
    verify(desc).setName("Ventas");
    verify(desc).setDescription("Ventas");
    verify(desc).setSales(true);
    verify(desc).setPurchase(false);
    verify(desc).setDefault(true);
  }

  // ---------------------------------------------------------------------------------------------
  // siiDescriptionExists() — real OBCriteria body, both sales/purchase branches
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testSiiDescriptionExistsTrueForSalesWhenRowPresent() {
    OnboardingFiscalDataSetupService service = new OnboardingFiscalDataSetupService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AEATSIIDescription> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AEATSIIDescription.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(mock(AEATSIIDescription.class));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertTrue(service.siiDescriptionExists(client, true));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testSiiDescriptionExistsFalseForPurchaseWhenNoRow() {
    OnboardingFiscalDataSetupService service = new OnboardingFiscalDataSetupService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AEATSIIDescription> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AEATSIIDescription.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertFalse(service.siiDescriptionExists(client, false));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // resolveClient() / saveSiiDescription() — real OBDal delegation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testResolveClientDelegatesToObDalGet() {
    OnboardingFiscalDataSetupService service = new OnboardingFiscalDataSetupService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Client.class, "CLIENT-1")).thenReturn(client);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(client, service.resolveClient("CLIENT-1"));
    }
  }

  @Test
  public void testSaveSiiDescriptionDelegatesToObDalSave() {
    OnboardingFiscalDataSetupService service = new OnboardingFiscalDataSetupService();
    AEATSIIDescription desc = mock(AEATSIIDescription.class);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.saveSiiDescription(desc);
    }

    verify(dal).save(desc);
  }

  // ---------------------------------------------------------------------------------------------
  // contextSubject()
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testContextSubjectIsFiscalDataSetup() {
    assertEquals("fiscal data setup", new OnboardingFiscalDataSetupService().contextSubject());
  }

  @Test
  public void testSetupFailsWhenClientIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.setup(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected missing clientId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testSetupFailsWhenOrgIdIsMissing() {
    TestableService service = new TestableService();
    try {
      service.setup("CLIENT-1", null, "USER-1", "ROLE-1");
      fail("Expected missing orgId to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testSetupFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();
    try {
      service.setup("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testSetupFailsWhenAdminRoleIsMissing() {
    TestableService service = new TestableService();
    try {
      service.setup("CLIENT-1", "ORG-1", "USER-1", null);
      fail("Expected missing admin role to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  @Test
  public void testSetupCreatesSiiDescriptionsWhenAbsent() {
    TestableService service = new TestableService();

    service.setup("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(2, service.siiSaveCount);
    assertTrue(service.flushed);
  }

  @Test
  public void testSetupSkipsSiiWhenAlreadyExists() {
    TestableService service = new TestableService();
    service.siiExists = true;

    service.setup("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(0, service.siiSaveCount);
  }

  @Test
  public void testSetupRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.failOnSii = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.setup("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected delegated failure");
    } catch (OBException e) {
      assertEquals("sii-boom", e.getMessage());
    }

    assertSame(previous, OBContext.getOBContext());
  }

  private static final class TestableService extends OnboardingFiscalDataSetupService {
    private final Client client = mock(Client.class);
    private final Organization organization = mock(Organization.class);

    boolean siiExists;
    boolean failOnSii;
    boolean flushed;
    int siiSaveCount;
    Client clientToReturn;
    Organization orgToReturn;

    private TestableService() {
      when(client.getId()).thenReturn("CLIENT-1");
      when(organization.getId()).thenReturn("ORG-1");
      clientToReturn = client;
      orgToReturn = organization;
    }

    @Override
    protected OBContext captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId,
        String clientId, String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void restoreExecutionContext(OBContext previousContext) {
      OBContext.setOBContext(previousContext);
    }

    @Override
    protected void enterAdminMode() {
      // no-op
    }

    @Override
    protected void exitAdminMode() {
      // no-op
    }

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected Client resolveClient(String clientId) {
      return clientToReturn;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return orgToReturn;
    }

    @Override
    protected boolean siiDescriptionExists(Client client, boolean isSales) {
      return siiExists;
    }

    @Override
    protected org.openbravo.module.sii.data.AEATSIIDescription buildSiiDescription(
        org.openbravo.model.ad.system.Client client,
        org.openbravo.model.common.enterprise.Organization org,
        String name, boolean isSales, boolean isPurchase) {
      return mock(org.openbravo.module.sii.data.AEATSIIDescription.class);
    }

    @Override
    protected void saveSiiDescription(
        org.openbravo.module.sii.data.AEATSIIDescription desc) {
      if (failOnSii) {
        throw new OBException("sii-boom");
      }
      siiSaveCount++;
    }
  }
}
