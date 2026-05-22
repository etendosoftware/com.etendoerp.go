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
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.enterprise.Organization;

public class OnboardingDefaultCustomerServiceTest {

  @Test
  public void testEnsureDefaultCustomerFailsWhenAdminUserIsMissing() {
    TestableService service = new TestableService();

    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected missing admin user to fail");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testEnsureDefaultCustomerReturnsExistingCustomerId() {
    TestableService service = new TestableService();
    BusinessPartner existing = mock(BusinessPartner.class);
    when(existing.getId()).thenReturn("BP-EXISTING");
    service.existingCustomer = existing;

    String result = service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("BP-EXISTING", result);
    assertEquals(0, service.createdCount);
  }

  @Test
  public void testEnsureDefaultCustomerRestoresPreviousContextAfterFailure() {
    TestableService service = new TestableService();
    service.failOnCreate = true;
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    try {
      service.ensureDefaultCustomer("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected delegated create failure");
    } catch (OBException e) {
      assertEquals("boom", e.getMessage());
    }

    assertSame(previous, OBContext.getOBContext());
  }

  private static final class TestableService extends OnboardingDefaultCustomerService {
    private final Client client = mock(Client.class);
    private final Organization organization = mock(Organization.class);
    private final Category bpGroup = mock(Category.class);
    private BusinessPartner existingCustomer;
    private boolean failOnCreate;
    private int createdCount;

    private TestableService() {
      when(client.getId()).thenReturn("CLIENT-1");
      when(organization.getId()).thenReturn("ORG-1");
    }

    @Override
    protected Object captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
        String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void enterAdminMode() {
      // no-op in unit tests
    }

    @Override
    protected void exitAdminMode() {
      // no-op in unit tests
    }

    @Override
    protected void restoreExecutionContext(Object previousContext) {
      OBContext.setOBContext((OBContext) previousContext);
    }

    @Override
    protected void saveCustomer(BusinessPartner customer) {
      // no-op in unit tests
    }

    @Override
    protected void flushChanges() {
      // no-op in unit tests
    }

    @Override
    protected BusinessPartner findExistingDefaultCustomer(String clientId, String orgId) {
      return existingCustomer;
    }

    @Override
    protected Category resolveBusinessPartnerGroup(String clientId) {
      return bpGroup;
    }

    @Override
    protected BusinessPartner createDefaultCustomer(Client client, Organization organization,
        Category bpGroup) {
      if (failOnCreate) {
        throw new OBException("boom");
      }
      createdCount++;
      BusinessPartner customer = mock(BusinessPartner.class);
      when(customer.getId()).thenReturn("BP-CREATED");
      return customer;
    }

    @Override
    protected Client resolveClient(String clientId) {
      return client;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return organization;
    }
  }
}
