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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-4830 item 7 — deterministic, self-contained proof that {@link
 * ProcessAccessOverlapCorruptionGuard} extends {@code WindowAccessOverlapCorruptionGuard}'s own
 * proven sixth-trigger fix (ETP-4906) to {@code AD_Process_Access}, which carries the identical
 * {@code AD_PROCESS_ACCESS_UN_KEY} unique constraint shape as {@code AD_Window_Access}.
 *
 * <p>Mirrors {@code UserRoleCompositionServiceOverlapIntegrationTest
 * #testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken} exactly, on
 * {@code AD_Process_Access} instead of {@code AD_Window_Access} — a "bystander" role composed
 * from all 4 real system templates via raw {@code AD_Role_Inheritance} rows, never through {@code
 * UserRoleCompositionService}, then losing one of them while 2 remaining templates both still
 * grant the same process.
 */
public class ProcessAccessOverlapCorruptionGuardIntegrationTest extends WeldBaseTest {

  /** Verified (live DB check, 2026-08-24) to have zero AD_Process_Access rows for any of the 4
   *  real system templates. */
  private static final String UNUSED_PROCESS_ID = "017312F51139438A9665775E3B5392A1";

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
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull("Test fixture must contain AD_Process " + UNUSED_PROCESS_ID, sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      Role purchasingTemplate = createThrowawaySystemTemplateRole();
      Role inventoryTemplate = createThrowawaySystemTemplateRole();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // Finance grants the shared process FULL. Sales AND Purchasing BOTH ALSO grant it,
      // READ-ONLY — the "2+ REMAINING templates overlap on the same item" shape that can only
      // exist with 3+ templates composed. Inventory does not grant this process at all.
      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();
      grantProcessAccess(purchasingTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);
      addInheritance(bystanderRole, purchasingTemplate, 30L);
      addInheritance(bystanderRole, inventoryTemplate, 40L);

      // Fixture setup note (revised Task 4, 2026-08-24): as of Task 3,
      // ProcessAccessOverlapCorruptionGuard's ADD-path guard (onSave/guardNewInheritance) already
      // covers ownership correction and most-permissive-wins widening as each template is
      // composed via addInheritance below, and as of this task (Task 4) the UPDATE path is
      // covered too — see this class's own class javadoc ("Scope: full ADD/UPDATE/REMOVE-path
      // parity with WindowAccessOverlapCorruptionGuard"). The row is nonetheless repaired
      // directly here — exactly like grantProcessAccess already builds TEMPLATE-owned rows
      // directly, bypassing core's inheritance propagation entirely — to deterministically
      // establish the SAME "already correctly composed" starting state a real role would have
      // BEFORE the human's real regression was ever hit (see this class's own javadoc),
      // independent of the ADD-path guard's own behavior, so this test stays isolated to the
      // REMOVE-path trigger it actually exercises below.
      ProcessAccess beforeRepair = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("Sanity: composing all 4 templates must have propagated the shared process",
          beforeRepair);
      repairProcessAccessOwnership(beforeRepair, bystanderRole, financeTemplate, true);

      ProcessAccess beforeRemoval = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeRemoval);
      assertEquals("Sanity: Finance is the only full grantor among all 4, so it must be the "
          + "source before removal",
          financeTemplate.getId(),
          beforeRemoval.getInheritedFrom() != null ? beforeRemoval.getInheritedFrom().getId()
              : null);
      assertTrue("Sanity: most-permissive-wins must resolve to full before removal",
          Boolean.TRUE.equals(beforeRemoval.isEditableField()));

      // THE TRIGGER: remove Finance's inheritance. Sales AND Purchasing BOTH still grant the
      // shared process afterward. Before this guard exists, this would risk the identical
      // duplicate-key ConstraintViolationException WindowAccessOverlapCorruptionGuard's own
      // sixth trigger fixed for AD_Window_Access; must now succeed.
      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      ProcessAccess afterRemoval = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive the removal, re-derived from the "
          + "2 remaining overlapping templates, not silently dropped or duplicated", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("Purchasing (the highest-SeqNo template among the 2 remaining templates that "
          + "grant this process) must become the new source",
          purchasingTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse("Neither remaining grantor (Sales, Purchasing) is full, so access must "
          + "downgrade to read-only, not stay stuck at Finance's old full value",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      // Fixture-only refresh, not a guard-under-test concern: createThrowawaySystemTemplateRole()
      // returns a role that was NEW in this very Hibernate session, so Hibernate considers its
      // ADRoleInheritanceInheritFromList collection already-initialized-empty from creation time
      // (a brand-new row cannot have pre-existing children) and never re-queries it on its own.
      // Core's own RoleInheritanceManager#propagateNewAccess (fired below, from
      // grantProcessAccess's flush) reads exactly that in-memory collection to find dependents to
      // propagate to — without forcing a refresh here, it would see a stale, still-empty list and
      // silently propagate to nobody, even though the RoleInheritance rows just added above are
      // already committed. The sibling tests below never hit this: they either grant access to the
      // templates BEFORE composing the bystander (so propagateNewAccess never runs against a role
      // with dependents yet), or route through addInheritance's own onSave-triggered
      // guardNewInheritance path instead (which queries AD_Process_Access directly, not through
      // this cached collection). Real (non-fixture) templates never hit this either, since they are
      // always fetched from the DB, never created fresh in the same session — see
      // createThrowawaySystemTemplateRole()'s own javadoc for why this test uses throwaway
      // templates in the first place.
      OBDal.getInstance().refresh(financeTemplate);
      OBDal.getInstance().refresh(salesTemplate);

      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      ProcessAccess bystanderAccess = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The bystander role must have received the propagated access, not lost it",
          bystanderAccess);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), bystanderAccess.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), bystanderAccess.getOrganization().getId());
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
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);

      ProcessAccess afterFinance = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("Sanity: Finance alone must have propagated the shared process", afterFinance);
      assertTrue("Sanity: Finance alone must grant full access",
          Boolean.TRUE.equals(afterFinance.isEditableField()));

      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess afterSales = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive gaining the second template",
          afterSales);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterSales.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterSales.getOrganization().getId());
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
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess widened = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(widened);
      assertTrue("Sanity: most-permissive-wins must resolve to full",
          Boolean.TRUE.equals(widened.isEditableField()));
      assertEquals("InheritedFrom must point at the template that actually justifies the "
          + "widened value (Finance), not the template CREATE originally sourced the row from",
          financeTemplate.getId(),
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

      ProcessAccess afterRemoval = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive the removal, re-derived from the "
          + "one remaining template (Sales)", afterRemoval);
      assertEquals("The process must now be re-derived from Sales, the one remaining template",
          salesTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse("Removing the FULL template must downgrade to the remaining READ-ONLY "
          + "template's level, not stay stuck at full",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role template = createThrowawaySystemTemplateRole();
      grantProcessAccess(template, sharedProcess, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, template, 10L);

      ProcessAccess beforeUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeUpdate);
      assertEquals(template.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);
      assertFalse(Boolean.TRUE.equals(beforeUpdate.isEditableField()));

      ProcessAccess templateAccess = findProcessAccess(template, sharedProcess);
      assertNotNull(templateAccess);
      updateProcessAccessLevel(templateAccess, false);

      ProcessAccess afterUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The dependent's row must survive a routine UPDATE to the template's own "
          + "access level, not be silently deleted with nothing left to restore it", afterUpdate);
      assertEquals("client must always match the DEPENDENT role's own, never a template's",
          bystanderRole.getClient().getId(), afterUpdate.getClient().getId());
      assertEquals("organization must always match the DEPENDENT role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterUpdate.getOrganization().getId());
      assertEquals(template.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
      assertTrue("The dependent's access level must be corrected to match the template's new "
          + "(widened) value", Boolean.TRUE.equals(afterUpdate.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role templateA = createThrowawaySystemTemplateRole();
      Role templateB = createThrowawaySystemTemplateRole();
      grantProcessAccess(templateA, sharedProcess, false);
      grantProcessAccess(templateB, sharedProcess, false);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, templateA, 10L);
      addInheritance(bystanderRole, templateB, 20L);

      ProcessAccess beforeUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeUpdate);
      assertTrue(Boolean.TRUE.equals(beforeUpdate.isEditableField()));
      assertEquals("Sanity: sourced from templateB (higher SeqNo)", templateB.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);

      ProcessAccess templateBAccess = findProcessAccess(templateB, sharedProcess);
      assertNotNull(templateBAccess);
      updateProcessAccessLevel(templateBAccess, true);

      ProcessAccess afterUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterUpdate);
      assertTrue("MOST-PERMISSIVE-WINS: the dependent must STAY at full access — templateA "
          + "still actively grants this process full access", Boolean.TRUE.equals(
              afterUpdate.isEditableField()));
      assertEquals("InheritedFrom must repoint to the template that actually still justifies "
          + "full access (templateA)", templateA.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
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
    role.setName("ETP-4830 item 7 process-guard bystander " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    return role;
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

  private void grantProcessAccess(Role role, Process process, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      ProcessAccess access = OBProvider.getInstance().get(ProcessAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setProcess(process);
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * UPDATEs (never creates) an existing template's own {@link ProcessAccess} row's level — the
   * B7 trigger's own entry point (core's {@code onUpdate}/{@code propagateUpdatedAccess}).
   */
  private void updateProcessAccessLevel(ProcessAccess access, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Fixture-repair helper (fix round 2, 2026-08-24) — see the call site's own comment for the
   * full rationale. Directly restores a propagated {@link ProcessAccess} row to what a
   * correctly-composed dependent's row should look like (own ownership, sourced from {@code
   * source}, at {@code editable}), the same kind of direct, admin-mode fixture construction
   * {@link #grantProcessAccess(Role, Process, boolean)} already uses for template-owned rows —
   * never exercises {@link ProcessAccessOverlapCorruptionGuard} itself.
   */
  private void repairProcessAccessOwnership(ProcessAccess access, Role dependent, Role source,
      boolean editable) {
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

  /**
   * Throwaway system-client ({@code AD_Client_ID = '0'}) template role — mirrors {@code
   * UserRoleCompositionServiceOverlapIntegrationTest#createThrowawaySystemTemplateRole} exactly
   * (same fixture-only no-arg {@code OBContext.setAdminMode()} bypass rationale: system client
   * {@code '0'} rows are never gated by {@code SecurityChecker} for a plain read, but creating one
   * directly here does need the write-access bypass).
   *
   * <p>Fix round 1 (2026-08-24): the 4 real {@link SystemRoleTemplates} rows this test originally
   * used are deliberately NOT used here. This environment has a real, pre-existing, non-test role
   * ("Classic Role", {@code 4C89FF2FE83F4CBE9310DB2124DC43FB}) that already actively inherits from
   * 3 of the 4 real templates (Finance/Sales/Purchasing) — granting {@code AD_Process_Access}
   * directly to those real templates in sequence propagates a NEW row to that unrelated real
   * dependent on the first grant, then a second grant on the SAME process takes core's UPDATE path
   * on that row and corrupts its ownership to system client {@code "0"}, throwing {@code
   * OBSecurityException} — the classic ADD-path bug {@code WindowAccessOverlapCorruptionGuard}
   * also fixes for {@code AD_Window_Access} via its own ADD/UPDATE-path triggers. {@link
   * ProcessAccessOverlapCorruptionGuard} has since grown the same ADD/UPDATE-path triggers
   * (Tasks 3 and 4 — see that class's own class javadoc, "Scope: full ADD/UPDATE/REMOVE-path
   * parity" — this fixture predates that work and was out of the original REMOVE-path-only task's
   * approved scope). Using fresh, never-inherited-by-anyone-else throwaway templates instead
   * means this test's own setup can never fan out to an unrelated real dependent, so it stays
   * isolated to whichever trigger a given test method actually exercises — exactly the same swap
   * {@code UserRoleCompositionServiceOverlapIntegrationTest}'s own B7/BUG-2 tests already use for
   * the analogous reason.
   */
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
      role.setName("ETP-4830 process-guard throwaway " + System.nanoTime());
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

  @SuppressWarnings("unchecked")
  private ProcessAccess findProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessAccess) criteria.uniqueResult();
  }
}
