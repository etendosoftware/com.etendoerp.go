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
import org.mockito.ArgumentCaptor;
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
  // reconcileOrgHierarchyPointers() / verifyOrgHierarchy() — real bodies (ETP-5352)
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testReconcileOrgHierarchyPointersBindsBothParameters() {
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
      service.reconcileOrgHierarchyPointers("CLIENT-1", "ORG-1");
    }

    verify(query).setParameter("clientId", "CLIENT-1");
    verify(query).setParameter("orgId", "ORG-1");
    verify(query).executeUpdate();
    // Zero rows means the pointers were already healthy: nothing to refresh, nothing to warn about.
    verify(dal, org.mockito.Mockito.never()).get(Organization.class, "ORG-1");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testReconcileOrgHierarchyPointersEvictsStaleEntityAfterRepair() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();
    Organization org = mock(Organization.class);

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    when(dal.get(Organization.class, "ORG-1")).thenReturn(org);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.reconcileOrgHierarchyPointers("CLIENT-1", "ORG-1");
    }

    // The repair is a native UPDATE, so the cached entity is stale. Leaving it in the session
    // risks a later flush writing the pre-update NULLs straight back over the repair.
    verify(session).refresh(org);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVerifyOrgHierarchyPassesForHealthyLegalEntity() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    // Character, not String: ad_orgtype.islegalentity is char(1), and Hibernate resolves a
    // 1-wide Types.CHAR column to CharacterType. Stubbing a String here is what let the
    // always-throwing `"Y".equals(row[0])` comparison ship green (ETP-5352).
    when(query.uniqueResult()).thenReturn(new Object[] { Character.valueOf('Y'), "ORG-1" });

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.verifyOrgHierarchy("ORG-1");
    }

    verify(query).setParameter("orgId", "ORG-1");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVerifyOrgHierarchyFailsWhenLegalEntityPointerStillNull() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(new Object[] { Character.valueOf('Y'), null });

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.verifyOrgHierarchy("ORG-1");
      fail("Expected OBException for an unresolved legal entity pointer");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("no legal entity pointer"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVerifyOrgHierarchyFailsWhenOrgIsNotALegalEntity() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(new Object[] { Character.valueOf('N'), null });

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.verifyOrgHierarchy("ORG-1");
      fail("Expected OBException for a non-legal-entity onboarding organization");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("not typed as a legal entity"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVerifyOrgHierarchyFailsWhenOrgRowIsMissing() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.verifyOrgHierarchy("ORG-1");
      fail("Expected OBException when the organization row cannot be read");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVerifyOrgHierarchyAcceptsAStringFlagToo() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    // A dialect or driver that reports the column as VARCHAR must keep working: the guard has to
    // pass on both mappings, never on exactly one of them.
    when(query.uniqueResult()).thenReturn(new Object[] { "Y", "ORG-1" });

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.verifyOrgHierarchy("ORG-1");
    }

    verify(query).setParameter("orgId", "ORG-1");
  }

  @Test
  public void testIsYesFlagNormalizesBothJdbcMappings() {
    // The regression this pins: Hibernate hands back a Character for char(1), so a plain
    // "Y".equals(value) rejects every healthy organization.
    assertTrue(OnboardingMarkOrgReadyService.isYesFlag(Character.valueOf('Y')));
    assertTrue(OnboardingMarkOrgReadyService.isYesFlag("Y"));
    assertFalse(OnboardingMarkOrgReadyService.isYesFlag(Character.valueOf('N')));
    assertFalse(OnboardingMarkOrgReadyService.isYesFlag("N"));
    assertFalse(OnboardingMarkOrgReadyService.isYesFlag(null));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testReconcileSqlDoesNotTreatAnEmptyBusinessUnitAsADefect() {
    OnboardingMarkOrgReadyService service = new OnboardingMarkOrgReadyService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(0);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.reconcileOrgHierarchyPointers("CLIENT-1", "ORG-1");
    }

    verify(session).createNativeQuery(sql.capture());
    String statement = sql.getValue();
    // Every healthy "Legal with accounting" org has an empty business unit. If that alone made the
    // row match, the UPDATE would run on every alta, bump the audit stamp and log the WARN that is
    // supposed to mean "AD_Org_Ready failed silently" — turning the signal into noise.
    assertTrue("business unit must only be repairable when the tree can actually supply one",
        statement.contains("ad_businessunit_org_id IS NULL")
            && statement.contains("ad_get_org_le_bu_treenode(ad_org_id, 'BU') IS NOT NULL"));
    assertFalse("an empty business unit alone must not match",
        statement.contains("OR ad_businessunit_org_id IS NULL)"));
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
  public void testMarkOrgReadySkipsProcessWhenAlreadyReady() {
    TestableService service = new TestableService();
    service.orgReady = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    // AD_Org_Ready is not idempotent (a failed internal check rolls back and raises @20545@), so
    // it must not be re-run on an organization that is already marked ready.
    assertEquals(0, service.processExecutionCount);
  }

  /**
   * ETP-5352 regression. InitialOrgSetup.createOrganization() runs AD_Org_Ready before this service
   * is ever called, so onboarding always reaches markOrgReady with isReady = 'Y'. The old early
   * return keyed on that flag therefore skipped every repair below it, and tenants kept an empty
   * AD_LEGALENTITY_ORG_ID — which breaks C_GETTAX and surfaces as @TaxNotFound@ when invoicing a
   * shipment that has no sales order. The derived state must be reconciled on this path too.
   */
  @Test
  public void testMarkOrgReadyStillReconcilesDerivedStateWhenAlreadyReady() {
    TestableService service = new TestableService();
    service.orgReady = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("AD_ORG_TREE must be provisioned even when the org is already ready", 1,
        service.provisionOrgTreeCount);
    assertEquals("Hierarchy pointers must be reconciled even when the org is already ready", 1,
        service.reconcileCount);
    assertEquals("CLIENT-1", service.reconcileClientId);
    assertEquals("ORG-1", service.reconcileOrgId);
    assertEquals("The alta must be verified even when the org is already ready", 1,
        service.verifyCount);
  }

  /**
   * Gap D1 hypothesis (b). AD_Org_Ready writes AD_ORG through PL/SQL, so the DAL session still
   * holds the pre-process entity. Saving that stale copy for the defensive setReady would flush
   * the derived columns back to their old NULLs — the entity must be evicted first.
   */
  @Test
  public void testMarkOrgReadyEvictsStaleEntityBeforeDefensiveSave() {
    TestableService service = new TestableService();
    service.orgStillNotReadyAfterProcess = true;

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(1, service.saveCount);
    assertTrue("the stale entity must be refreshed before the defensive save",
        service.refreshedBeforeSave);
  }

  @Test
  public void testMarkOrgReadyReconcilesAfterProvisioningTheTree() {
    TestableService service = new TestableService();

    service.markOrgReady("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    // ad_get_org_le_bu_treenode() walks AD_TREENODE, so the tree has to exist before the pointers
    // are recomputed — reconciling first would compute the same NULL all over again.
    assertTrue("tree provisioning must precede pointer reconciliation",
        service.treeProvisionedBeforeReconcile);
    assertTrue("reconciliation must precede verification", service.reconciledBeforeVerify);
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
    int reconcileCount;
    int verifyCount;
    int refreshCount;
    boolean flushBeforeProcess;
    boolean treeProvisionedBeforeReconcile;
    boolean reconciledBeforeVerify;
    boolean refreshedBeforeSave;
    String processClientId;
    String processOrgId;
    String processUserId;
    String processRoleId;
    String provisionOrgTreeClientId;
    String provisionOrgTreeOrgId;
    String reconcileClientId;
    String reconcileOrgId;
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
      refreshedBeforeSave = refreshCount > 0;
    }

    @Override
    protected void refreshOrganization(String orgId) {
      // The real body evicts the entity through the Hibernate session, unavailable in unit tests.
      refreshCount++;
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

    @Override
    protected void reconcileOrgHierarchyPointers(String clientId, String orgId) {
      // Same reasoning as provisionOrgTree: the body is a native UPDATE against the DAL session.
      reconcileCount++;
      reconcileClientId = clientId;
      reconcileOrgId = orgId;
      treeProvisionedBeforeReconcile = provisionOrgTreeCount > 0;
    }

    @Override
    protected void verifyOrgHierarchy(String orgId) {
      verifyCount++;
      reconciledBeforeVerify = reconcileCount > 0;
    }
  }
}
