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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

public class OnboardingMarkOrgReadyServiceTest {

  @Test
  public void testMarkOrgReadySkipsWhenAlreadyReady() {
    TestableService service = new TestableService();
    service.orgReady = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(0, service.processExecutionCount);
    assertEquals(0, service.flushCount);
  }

  @Test
  public void testMarkOrgReadyFailsWhenOrgNotFound() {
    TestableService service = new TestableService();
    service.orgMissing = true;

    try {
      service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing org");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found"));
    }
  }

  @Test
  public void testMarkOrgReadyFlushesBeforeProcessExecution() {
    TestableService service = new TestableService();

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertTrue("flush must precede process execution", service.flushBeforeProcess);
  }

  @Test
  public void testMarkOrgReadyExecutesProcessWhenNotReady() {
    TestableService service = new TestableService();

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(1, service.processExecutionCount);
    assertEquals("CLIENT-1", service.processClientId);
    assertEquals("ORG-1", service.processOrgId);
    assertEquals("USER-1", service.processUserId);
    assertEquals("ROLE-1", service.processRoleId);
  }

  @Test
  public void testMarkOrgReadySetsFlagDefensivelyWhenProcessDidNotFlipIt() {
    TestableService service = new TestableService();
    service.orgStillNotReadyAfterProcess = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(1, service.saveCount);
    assertTrue("Org must be set ready defensively", service.savedOrg.isReady());
    assertTrue("Must flush after defensive set", service.flushCount >= 2);
  }

  @Test
  public void testMarkOrgReadySkipsDefensiveSaveWhenProcessFlippedFlag() {
    TestableService service = new TestableService();

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("No save when process already flipped the flag", 0, service.saveCount);
  }

  @Test
  public void testMarkOrgReadyFailsWhenProcessNotFound() {
    TestableService service = new TestableService();
    service.processMissing = true;

    try {
      service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing AD_Org_Ready process");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("AD_Org_Ready"));
    }
  }

  private static final class TestableService extends OnboardingMarkOrgReadyService {
    boolean orgMissing;
    boolean orgReady;
    boolean processMissing;
    boolean orgStillNotReadyAfterProcess;

    int processExecutionCount;
    int flushCount;
    int saveCount;
    boolean flushBeforeProcess;
    String processClientId;
    String processOrgId;
    String processUserId;
    String processRoleId;
    Organization savedOrg;

    @Override
    protected void executeOrgReadyProcess(String orgId, String clientId,
        String adminUserId, String adminRoleId) {
      if (processMissing) {
        throw new OBException("AD_Org_Ready process not found");
      }
      flushBeforeProcess = flushCount > 0;
      processExecutionCount++;
      processOrgId = orgId;
      processClientId = clientId;
      processUserId = adminUserId;
      processRoleId = adminRoleId;
    }

    @Override
    protected Process resolveProcess(String searchKey) {
      return processMissing ? null : mock(Process.class);
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      if (orgMissing) {
        return null;
      }
      Organization org = mock(Organization.class);
      boolean isReady = orgReady || (processExecutionCount > 0 && !orgStillNotReadyAfterProcess);
      when(org.isReady()).thenReturn(isReady);
      return org;
    }

    @Override
    protected void saveOrganization(Organization org) {
      saveCount++;
      savedOrg = org;
    }

    @Override
    protected void flushChanges() {
      flushCount++;
    }
  }
}
