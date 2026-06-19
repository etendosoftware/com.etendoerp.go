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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

public class OnboardingMarkOrgReadyServiceTest {

  // ---------------------------------------------------------------------------------------------
  // provisionOrgTree() / runOrgTreeInsert() — real native-query bodies
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testProvisionOrgTreeRunsBothIdempotentInserts() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.provisionOrgTree("CLIENT-1", "ORG-1");
    }

    // Two inserts (self-reference + parent), each binding clientId/orgId.
    verify(session, org.mockito.Mockito.times(2)).createNativeQuery(anyString());
    verify(query, org.mockito.Mockito.times(2)).setParameter("clientId", "CLIENT-1");
    verify(query, org.mockito.Mockito.times(2)).setParameter("orgId", "ORG-1");
    verify(query, org.mockito.Mockito.times(2)).executeUpdate();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRunOrgTreeInsertHandlesZeroRowResult() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(0);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // Zero-row result skips the debug log; must not throw.
      service.runOrgTreeInsert("INSERT INTO ad_org_tree ...", "CLIENT-1", "ORG-1");
    }

    verify(query).executeUpdate();
  }

  // ---------------------------------------------------------------------------------------------
  // executeOrgReadyProcess() — process-not-found guard (real body)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testExecuteOrgReadyProcessFailsWhenProcessMissing() {
    // Override only resolveProcess so the real executeOrgReadyProcess body runs and hits the
    // null-process guard before touching any DalConnectionProvider / ProcessRunner machinery.
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService() {
      @Override
      protected Process resolveProcess(String searchKey) {
        return null;
      }
    };

    try {
      service.executeOrgReadyProcess("ORG-1", "CLIENT-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing AD_Org_Ready process");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("AD_Org_Ready process not found"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // resolveProcess() — real OBCriteria interaction
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveProcessReturnsUniqueResult() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();
    Process process = mock(Process.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Process> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Process.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(process);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(process, service.resolveProcess("AD_Org_Ready"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // resolveOrganization() / saveOrganization() / flushChanges() — real OBDal delegation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testResolveOrganizationDelegatesToObDalGet() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();
    Organization org = mock(Organization.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Organization.class, "ORG-1")).thenReturn(org);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(org, service.resolveOrganization("ORG-1"));
    }
  }

  @Test
  public void testResolveOrganizationReturnsNullWhenAbsent() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    when(dal.get(Organization.class, "ORG-1")).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertNull(service.resolveOrganization("ORG-1"));
    }
  }

  @Test
  public void testSaveOrganizationDelegatesToObDalSave() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();
    Organization org = mock(Organization.class);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.saveOrganization(org);
    }

    verify(dal).save(org);
  }

  @Test
  public void testFlushChangesDelegatesToObDalFlush() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.flushChanges();
    }

    verify(dal).flush();
  }

  @Test
  public void testMarkOrgReadySkipsWhenAlreadyReady() {
    TestableService service = new TestableService();
    service.orgReady = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(0, service.processExecutionCount);
    assertEquals(0, service.flushCount);
    assertEquals("No tree provisioning when org is already ready", 0,
        service.provisionOrgTreeCount);
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
    assertEquals("AD_ORG_TREE must be provisioned on the not-ready path", 1,
        service.provisionOrgTreeCount);
    assertEquals("CLIENT-1", service.provisionOrgTreeClientId);
    assertEquals("ORG-1", service.provisionOrgTreeOrgId);
  }

  @Test
  public void testMarkOrgReadySetsFlagDefensivelyWhenProcessDidNotFlipIt() {
    TestableService service = new TestableService();
    service.orgStillNotReadyAfterProcess = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(1, service.saveCount);
    verify(service.savedOrg).setReady(true);
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
    int provisionOrgTreeCount;
    boolean flushBeforeProcess;
    String processClientId;
    String processOrgId;
    String processUserId;
    String processRoleId;
    String provisionOrgTreeClientId;
    String provisionOrgTreeOrgId;
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

    @Override
    protected void provisionOrgTree(String clientId, String orgId) {
      // The real implementation hits the DAL session (OBDal), unavailable in unit tests, so we
      // track invocation here instead. Asserting it is called keeps the AD_ORG_TREE provisioning
      // wired into markOrgReady; the SQL itself is covered by integration tests.
      provisionOrgTreeCount++;
      provisionOrgTreeClientId = clientId;
      provisionOrgTreeOrgId = orgId;
    }
  }
}
