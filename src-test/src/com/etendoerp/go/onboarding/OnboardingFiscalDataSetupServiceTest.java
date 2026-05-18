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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

public class OnboardingFiscalDataSetupServiceTest {

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

    private TestableService() {
      when(client.getId()).thenReturn("CLIENT-1");
      when(organization.getId()).thenReturn("ORG-1");
    }

    @Override
    protected Object captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId,
        String clientId, String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void restoreExecutionContext(Object previousContext) {
      OBContext.setOBContext((OBContext) previousContext);
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
      return client;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return organization;
    }

    @Override
    protected boolean siiDescriptionsExist(Client client) {
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
