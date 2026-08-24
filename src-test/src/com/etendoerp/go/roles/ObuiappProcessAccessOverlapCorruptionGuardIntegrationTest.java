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
package com.etendoerp.go.roles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.client.application.Process;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-4830 item 7 (full-parity expansion) — {@code OBUIAPP_Process_Access} equivalent of {@link
 * ProcessAccessOverlapCorruptionGuardIntegrationTest}. See {@link
 * ObuiappProcessAccessOverlapCorruptionGuard}'s own class javadoc for why this guard uses the
 * SAME repoint-in-place mechanism as {@link ProcessAccessOverlapCorruptionGuard} despite {@code
 * OBUIAPP_Process_Access} having no unique constraint.
 */
public class ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest extends WeldBaseTest {

  /** Verified (live DB check, 2026-08-24) active. Tests use throwaway templates, so no
   *  "unused by real templates" property is required of this fixture. */
  private static final String OBUIAPP_PROCESS_ID = "0662F6BC8D604AAEA5A2DD49E87F4B65";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull("Test fixture must contain OBUIAPP_Process " + OBUIAPP_PROCESS_ID,
          sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      Role purchasingTemplate = createThrowawaySystemTemplateRole();
      Role inventoryTemplate = createThrowawaySystemTemplateRole();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(purchasingTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);
      addInheritance(bystanderRole, purchasingTemplate, 30L);
      addInheritance(bystanderRole, inventoryTemplate, 40L);

      // Fixture repair — same precedented fix as ProcessAccessOverlapCorruptionGuardIntegration
      // Test's own original REMOVE-path-only skeleton stage (see that class's git history, commit
      // 033d3b0d, "Fixture repair (fix round 2)"): this guard is deliberately REMOVE-path only —
      // there is no ADD-path guard for OBUIAPP_Process_Access (Tasks 6/7 add it later). Composing
      // the bystander from 3 overlapping process grantors via plain addInheritance therefore
      // leaves the propagated row silently WRONG on two counts: (1) mis-sourced — core's own naive
      // "last-processed-template-wins" ADD behavior, not most-permissive-wins, so the row ends up
      // sourced from Purchasing (the last template added that grants this process), read-only, not
      // Finance/full; (2) mis-owned — its client/organization is silently overwritten to the last
      // template's own system client "0". Repairing the row directly here — exactly like
      // grantObuiappProcessAccess already builds TEMPLATE-owned rows directly, bypassing core's
      // inheritance propagation entirely — establishes the SAME valid "already correctly composed"
      // starting state a real role would have BEFORE the human's real regression was ever hit,
      // without smuggling the deferred ADD-path fix into either this test or the guard under test.
      // Only the REMOVE-path trigger below is being exercised.
      ProcessAccess beforeRepair = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("Sanity: composing all 4 templates must have propagated the shared process",
          beforeRepair);
      repairObuiappProcessAccessOwnership(beforeRepair, bystanderRole, financeTemplate, true);

      ProcessAccess beforeRemoval = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeRemoval);
      assertEquals("Sanity: Finance is the only full grantor among all 4", financeTemplate.getId(),
          beforeRemoval.getInheritedFrom() != null ? beforeRemoval.getInheritedFrom().getId()
              : null);
      assertTrue(Boolean.TRUE.equals(beforeRemoval.isEditableField()));

      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      ProcessAccess afterRemoval = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive the removal, re-derived from the "
          + "2 remaining overlapping templates, not silently dropped or duplicated", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("Purchasing (highest-SeqNo among the 2 remaining grantors) must become source",
          purchasingTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse("Neither remaining grantor (Sales, Purchasing) is full",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));

      // No-duplicate confirmation — the mechanism this guard needs to prove for OBUIAPP
      // specifically, since OBUIAPP_Process_Access has no unique constraint to enforce it.
      assertEquals("Exactly ONE active row must exist for (bystander, process) — repoint-in-place "
          + "must have prevented core's own duplicate-INSERT race, not just avoided a crash", 1,
          findAllActiveObuiappProcessAccess(bystanderRole, sharedProcess).size());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Role createBystanderRole(User user) {
    Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(user.getClient());
    role.setOrganization(starOrg);
    role.setActive(true);
    role.setName("ETP-4830 item 7 obuiapp-guard bystander " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    return role;
  }

  private Role createThrowawaySystemTemplateRole() {
    OBContext.setAdminMode();
    try {
      Client systemClient = OBDal.getInstance().get(Client.class, "0");
      Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
      Role role = OBProvider.getInstance().get(Role.class);
      role.setNewOBObject(true);
      role.setClient(systemClient);
      role.setOrganization(starOrg);
      role.setActive(true);
      role.setName("ETP-4830 obuiapp-guard throwaway " + System.nanoTime());
      role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
      role.setManual(true);
      role.setTemplate(true);
      role.setClientAdmin(false);
      OBDal.getInstance().save(role);
      OBDal.getInstance().flush();
      return role;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void addInheritance(Role role, Role template, long seqno) {
    RoleInheritance inheritance = OBProvider.getInstance().get(RoleInheritance.class);
    inheritance.setNewOBObject(true);
    inheritance.setClient(role.getClient());
    inheritance.setOrganization(role.getOrganization());
    inheritance.setActive(true);
    inheritance.setRole(role);
    inheritance.setInheritFrom(template);
    inheritance.setSequenceNumber(seqno);
    OBDal.getInstance().save(inheritance);
    OBContext.setAdminMode();
    try {
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void grantObuiappProcessAccess(Role role, Process process, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      ProcessAccess access = OBProvider.getInstance().get(ProcessAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setObuiappProcess(process);
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Fixture-repair helper — mirrors {@code
   * ProcessAccessOverlapCorruptionGuardIntegrationTest#repairProcessAccessOwnership} exactly (see
   * the call site's own comment for the full rationale). Directly restores a propagated {@link
   * ProcessAccess} row to what a correctly-composed dependent's row should look like (own
   * ownership, sourced from {@code source}, at {@code editable}), the same kind of direct,
   * admin-mode fixture construction {@link #grantObuiappProcessAccess(Role, Process, boolean)}
   * already uses for template-owned rows — never exercises {@link
   * ObuiappProcessAccessOverlapCorruptionGuard} itself.
   */
  private void repairObuiappProcessAccessOwnership(ProcessAccess access, Role dependent,
      Role source, boolean editable) {
    OBContext.setAdminMode();
    try {
      access.setClient(dependent.getClient());
      access.setOrganization(dependent.getOrganization());
      access.setInheritedFrom(source);
      access.setEditableField(editable);
      OBDal.getInstance().save(access);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private RoleInheritance findInheritance(Role role, Role template) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (RoleInheritance) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private ProcessAccess findObuiappProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_OBUIAPPPROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessAccess) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private java.util.List<ProcessAccess> findAllActiveObuiappProcessAccess(Role role,
      Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_OBUIAPPPROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }

  @Test
  public void testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      // Fixture-only refresh, not a guard-under-test concern — same rationale as
      // ProcessAccessOverlapCorruptionGuardIntegrationTest's own identically-named test (see that
      // method's own comment for the full explanation): createThrowawaySystemTemplateRole()
      // returns a role that was NEW in this very Hibernate session, so Hibernate considers its
      // inheritFrom-side collection already-initialized-empty and never re-queries it on its own.
      // Core's own RoleInheritanceManager#propagateNewAccess (fired below, from
      // grantObuiappProcessAccess's flush) reads exactly that in-memory collection to find
      // dependents to propagate to — without forcing a refresh here, it would see a stale,
      // still-empty list and silently propagate to nobody, even though the RoleInheritance rows
      // just added above are already committed.
      OBDal.getInstance().refresh(financeTemplate);
      OBDal.getInstance().refresh(salesTemplate);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      ProcessAccess bystanderAccess = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(bystanderAccess);
      assertEquals(bystanderRole.getClient().getId(), bystanderAccess.getClient().getId());
      assertEquals(bystanderRole.getOrganization().getId(),
          bystanderAccess.getOrganization().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);

      ProcessAccess afterFinance = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterFinance);
      assertTrue(Boolean.TRUE.equals(afterFinance.isEditableField()));

      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess afterSales = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterSales);
      assertEquals(bystanderRole.getClient().getId(), afterSales.getClient().getId());
      assertEquals(bystanderRole.getOrganization().getId(), afterSales.getOrganization().getId());
      assertTrue("Most-permissive-wins: gaining a READ-ONLY template must never downgrade "
          + "already-existing FULL access", Boolean.TRUE.equals(afterSales.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testRemovingTheTemplateThatJustifiedAWidenedAccessLevelCorrectlyDowngrades()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess widened = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(widened);
      assertTrue(Boolean.TRUE.equals(widened.isEditableField()));
      assertEquals(financeTemplate.getId(),
          widened.getInheritedFrom() != null ? widened.getInheritedFrom().getId() : null);

      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      ProcessAccess afterRemoval = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterRemoval);
      assertEquals(salesTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse(Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
