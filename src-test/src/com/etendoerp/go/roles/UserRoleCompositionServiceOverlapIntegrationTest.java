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

import java.util.Arrays;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/**
 * QA (Sentinel, originally found via ETP-4878's overlapping permission matrix; the fix and this
 * test live here on ETP-4852 because the bug is in ETP-4852's OWN mechanism — {@code
 * UserRoleCompositionService.assignTemplateRoles} already accepted a list of 2+ template ids,
 * that IS the whole point of composition; ETP-4852's own 2-window smoke test just happened to be
 * fully disjoint across the four roles, so this path was never exercised until ETP-4878's real
 * matrix deliberately overlapped several roles on shared windows).
 *
 * <p><b>The bug, before this fix:</b> composing a personal role from 2+ templates that grant the
 * SAME window threw {@code OBSecurityException} and rolled back the whole call, regardless of
 * whether the two templates agreed or disagreed on access level. Root cause traced into core
 * ({@code org.openbravo.role.inheritance}): {@code WindowAccessInjector} never overrides {@code
 * AccessTypeInjector#getSkippedProperties()}, so when a second template's inheritance propagates
 * to a window a first template already covered, {@code RoleInheritanceManager#handleAccess} takes
 * the UPDATE path ({@code updateRoleAccess} → {@code DalUtil.copyToTarget}), overwriting the
 * personal (tenant-client) role's existing {@code AD_Window_Access} row with the template's OWN
 * {@code client}/{@code organization} (system client {@code "0"}) — the very next flush then
 * fails {@code SecurityChecker.checkWriteAccess}.</p>
 *
 * <p><b>The fix (self-contained, no core patch — human-confirmed decision):</b> see {@code
 * UserRoleCompositionService}'s class javadoc and its {@code
 * preventWindowAccessOverlapCorruption}/{@code reconcileWindowAccessAfterComposition} methods.
 * This test suite verifies the CORRECT behavior after that fix: composition succeeds, the
 * personal role ends up with the UNION of both templates' windows, and any window granted by 2+
 * templates resolves to full ("✓") access if ANY of them wanted full — never a thrown exception,
 * never a lost grant, never a corrupted {@code client}/{@code organization}.</p>
 *
 * <p><b>Uses the REAL Finance/Sales system template roles</b> (seeded by {@code
 * EnsureSystemRoleTemplatesScript} on {@code update.database}), not throwaway ones — the original
 * finding confirmed a throwaway client-{@code 0} role hits the identical failure, ruling out
 * "freshly-inserted role in this session" as the cause, so reusing the real templates here is
 * representative, not incidental. The extra shared grant is added on window {@code 100} ("Tables
 * and Columns"), confirmed via a live, read-only query against this environment's DB to NOT be
 * part of either role's real ETP-4852 smoke-test grants, so this test is independent of whatever
 * the templates' own real grants happen to be and never mutates them (nothing here is committed
 * regardless — see {@link #rollbackChanges()}).</p>
 */
public class UserRoleCompositionServiceOverlapIntegrationTest extends WeldBaseTest {

  /** Confirmed (live DB check) NOT part of either role's real ETP-4852 smoke-test grants. */
  private static final String UNUSED_WINDOW_ID = "100";

  /** Finance's own ETP-4852 smoke-test window ("Financial Account") — never granted to Sales. */
  private static final String FINANCE_ONLY_WINDOW_ID = "94EAA455D2644E04AB25D93BE5157B6D";

  /** Sales' own ETP-4852 smoke-test window ("Sales Order") — never granted to Finance. */
  private static final String SALES_ONLY_WINDOW_ID = "143";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testComposingTwoTemplatesThatShareAWindowSucceedsWithMostPermissiveAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull("Test fixture must contain AD_Window " + UNUSED_WINDOW_ID, sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull("The real Finance system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", financeTemplate);
      assertNotNull("The real Sales system template must already exist (seeded by "
          + "EnsureSystemRoleTemplatesScript on update.database)", salesTemplate);

      // Finance grants the shared window FULL, Sales grants it READ-ONLY — the conflicting case.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();

      // THE FIX: this must now SUCCEED (no OBSecurityException) and union both templates' access.
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(financeTemplate.getId(), salesTemplate.getId()));

      assertEquals(2, result.addedCount);
      assertEquals(0, result.removedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertNotNull("A personal role must have been created", personalRole);

      WindowAccess shared = findWindowAccess(personalRole, sharedWindow);
      assertNotNull("The shared window must be present exactly once (a real union), not lost "
          + "or thrown away", shared);
      assertTrue("Most-permissive-wins: Finance's full access must win over Sales' read-only "
          + "on the same window", Boolean.TRUE.equals(shared.isEditableField()));
      assertEquals("client must always match the PERSONAL role's own, never a template's",
          personalRole.getClient().getId(), shared.getClient().getId());
      assertEquals("organization must always match the PERSONAL role's own, never a template's",
          personalRole.getOrganization().getId(), shared.getOrganization().getId());

      // Non-shared windows from BOTH templates must also be present — a real union, not one
      // template's grants silently replacing the other's.
      Window financeOnlyWindow = OBDal.getInstance().get(Window.class, FINANCE_ONLY_WINDOW_ID);
      Window salesOnlyWindow = OBDal.getInstance().get(Window.class, SALES_ONLY_WINDOW_ID);
      assertNotNull("Finance's own non-shared window must still be present",
          findWindowAccess(personalRole, financeOnlyWindow));
      assertNotNull("Sales' own non-shared window must still be present",
          findWindowAccess(personalRole, salesOnlyWindow));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testMostPermissiveWinsRegardlessOfWhichTemplateIsAddedSecond() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      // Same conflicting grants as the previous test, but requested in the OPPOSITE order below
      // — Sales (read-only) gets added FIRST this time, Finance (full) SECOND, so it is now
      // FINANCE's inheritance that triggers preventWindowAccessOverlapCorruption. The union must
      // resolve to full either way — order must never change the outcome.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(salesTemplate.getId(), financeTemplate.getId()));

      assertEquals(2, result.addedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      WindowAccess shared = findWindowAccess(personalRole, sharedWindow);
      assertNotNull(shared);
      assertTrue("Add order must not affect the most-permissive-wins outcome",
          Boolean.TRUE.equals(shared.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testReRunningWithTheSameOverlappingTemplateSetIsANoOpAndStaysCorrect()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult first = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(financeTemplate.getId(), salesTemplate.getId()));
      UserRoleCompositionService.AssignmentResult second = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(financeTemplate.getId(), salesTemplate.getId()));

      assertEquals(first.personalRoleId, second.personalRoleId);
      assertEquals("Re-running with the identical, already-reconciled overlapping template set "
          + "must add nothing new", 0, second.addedCount);
      assertEquals(0, second.removedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, second.personalRoleId);
      WindowAccess shared = findWindowAccess(personalRole, sharedWindow);
      assertNotNull("The shared window's access must still be present after the no-op re-run",
          shared);
      assertTrue("The shared window's access must still be full after the no-op re-run",
          Boolean.TRUE.equals(shared.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * QA (Sentinel, ETP-4906) — closes a real gap between two things that were each independently
   * tested but never together: this suite's own most-permissive-wins WRITE-side fix above
   * ({@code assignTemplateRoles} with 2 disagreeing templates), and ETP-4906's brand-new READ
   * path, {@link UserRoleCompositionService#getAppliedTemplateRoleIds(String)} — the method
   * {@code SFUserRoleAssignments} calls to seed the multi-role picker's initial chip state.
   * {@code SFUserRoleAssignmentsTest} only exercises that read method against a fully mocked
   * {@code UserRoleCompositionService} (never a real composition), so nothing before this test
   * proved the read path actually reflects a REAL overlapping write against the real DB — exactly
   * the "does the composed access after save match what the read endpoint reports" question this
   * ticket's QA dispatch called out as the one thing the mocked Playwright suite cannot verify.
   */
  @Test
  public void testGetAppliedTemplateRoleIdsReflectsARealOverlappingComposition() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      // Same conflicting shared-window grants as the tests above: Finance full, Sales read-only.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = service.assignTemplateRoles(
          TEST_USER_ID, Arrays.asList(financeTemplate.getId(), salesTemplate.getId()));
      assertEquals(2, result.addedCount);

      // Read path: must report exactly the 2 applied templates, no more, no less — the same set
      // SFUserRoleAssignments' single-user mode would hand back to the frontend on page load.
      List<String> appliedIds = service.getAppliedTemplateRoleIds(TEST_USER_ID);
      assertEquals("The read path must report exactly the 2 templates just composed", 2,
          appliedIds.size());
      assertTrue("Finance must be in the applied set", appliedIds.contains(financeTemplate.getId()));
      assertTrue("Sales must be in the applied set", appliedIds.contains(salesTemplate.getId()));

      // And the underlying window access the read path is describing must itself still be the
      // most-permissive-wins result — the read isn't just echoing back requested ids, it is
      // describing a personal role whose real AD_Window_Access already resolved the conflict.
      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      WindowAccess shared = findWindowAccess(personalRole, sharedWindow);
      assertNotNull(shared);
      assertTrue("The composed access the read path describes must itself be full "
          + "(most-permissive-wins), not read-only", Boolean.TRUE.equals(shared.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-4906 (Task B6) — deterministic, self-contained proof that {@code
   * WindowAccessOverlapCorruptionGuard} protects a role {@link UserRoleCompositionService} never
   * even knows about, not just the one it is actively composing.
   *
   * <p>Before this fix, ONLY a role passed into {@code assignTemplateRoles} was protected from
   * the corrupting UPDATE-path write core's own propagation performs when a second overlapping
   * template's access propagates onto a role that already inherits from the first. This test
   * builds a throwaway "bystander" role — never passed into {@code assignTemplateRoles} at all —
   * that already inherits from BOTH templates before either {@link #grantWindowAccess} call below,
   * exactly like the real, pre-existing multi-role personal account that first exposed this gap
   * (see this suite's class javadoc history / ETP-4906 B5 Findings), but built fresh here so this
   * test's pass/fail never depends on any incidental real DB state surviving between runs.</p>
   */
  @Test
  public void testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // A bystander role that already inherits from BOTH templates — set up directly via
      // AD_Role_Inheritance, never through assignTemplateRoles, so UserRoleCompositionService has
      // no idea this role even exists. Flushed ONE AT A TIME (addInheritance flushes internally,
      // see its own javadoc) — this environment's real Finance/Sales templates have themselves
      // drifted to overlap on a real window since this test was written, so batching both
      // inheritance-adds into a single flush would hit an UNRELATED, pre-existing core limitation
      // (core's own propagation for the second inheritance cannot see the first inheritance's
      // still-pending, not-yet-executed INSERT within the same flush cycle) — nothing to do with
      // the guard under test here, exactly like a real admin adding roles one row at a time in
      // Etendo Classic would naturally flush between them too.
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      // Same conflicting overlap as the tests above, but triggered directly on the TEMPLATES —
      // zero UserRoleCompositionService code anywhere in this call stack. Must not throw.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      WindowAccess bystanderAccess = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The bystander role must have received the propagated access, not lost it",
          bystanderAccess);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), bystanderAccess.getClient().getId());
      assertEquals(
          "organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), bystanderAccess.getOrganization().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-4906 (Task B6 follow-up, REMOVE-path gap) — deterministic, self-contained proof that
   * {@code WindowAccessOverlapCorruptionGuard} also protects a role LOSING one of two overlapping
   * template inheritances, not just gaining one. Live-confirmed gap: the ADD-path fix (see {@link
   * #testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected()} above) alone was not
   * enough — removing one of two overlapping templates from an already-composed role reproduced
   * the identical {@code OBSecurityException} from a third, previously-unguarded entry point
   * ({@code RoleInheritanceManager#applyRemoveInheritance}, triggered by deleting an {@code
   * AD_Role_Inheritance} row).
   *
   * <p>Same "bystander" shape as the ADD-path test — zero {@code UserRoleCompositionService} code
   * anywhere in this call stack, just a raw {@code RoleInheritance} delete, exactly like a raw
   * Etendo Classic "remove role from composition" edit.
   */
  @Test
  public void testRemovingOneOfTwoOverlappingTemplateInheritancesIsAlsoProtected()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // Same setup as the ADD-path bystander test: a role inheriting from BOTH templates before
      // either grant exists, built directly via AD_Role_Inheritance/AD_Window_Access, never
      // through assignTemplateRoles.
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      // Finance FULL, granted FIRST; Sales READ-ONLY, granted SECOND. Sales's own grant briefly
      // becomes the row's raw CREATE source ("last write wins", see
      // WindowAccessOverlapCorruptionGuard#guardDependentsOf), but ETP-4906's round-5 fix then
      // immediately widens AND repoints InheritedFrom to Finance in the SAME flush (Finance still
      // grants full — see widenInheritedAccessLevelIfNeeded) — so by the time this test can
      // observe the row, InheritedFrom already correctly names Finance, not Sales.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      WindowAccess beforeRemoval = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("Sanity: the ADD-path fix must have already propagated the shared window",
          beforeRemoval);
      assertEquals("Sanity: most-permissive-wins (round 5) must have already repointed "
          + "InheritedFrom to Finance, the template that actually justifies the full access, "
          + "even though Sales was the raw CREATE source (\"last write wins\")",
          financeTemplate.getId(),
          beforeRemoval.getInheritedFrom() != null ? beforeRemoval.getInheritedFrom().getId()
              : null);

      // THE REMOVE-PATH TRIGGER: delete Sales's RoleInheritance row — zero
      // UserRoleCompositionService code anywhere in this call stack. Since round 5's fix, the
      // row's InheritedFrom already correctly names Finance (see the sanity check above), so
      // guardRemovedInheritance's own "already correctly sourced from the one remaining template,
      // skip" check (see that method's own javadoc) means this particular removal is a no-op for
      // the delete-forcing-create-path mechanism — nothing NEEDS re-deriving, because nothing was
      // wrong to begin with. What this still verifies: removing the NON-justifying template must
      // not throw and must leave the row's correct (Finance-sourced, full) state untouched. See
      // testRemovingTheTemplateThatJustifiedAWidenedAccessLevelCorrectlyDowngrades for the
      // complementary case — removing the template that DOES currently justify the row's value.
      RoleInheritance salesInheritance = findInheritance(bystanderRole, salesTemplate);
      assertNotNull(salesInheritance);
      OBDal.getInstance().remove(salesInheritance);
      // Same OBContext.setAdminMode() bypass addInheritance()/grantWindowAccess() already use:
      // this delete fans out through core's RoleInheritanceManager#applyRemoveInheritance, which
      // retracts every AccessTypeInjector's propagated rows (window, tab, field, PROCESS, OBUIAPP
      // process, ...) — a system-level template's process-access rows still carry client "0" at
      // this point, and this flush is where that would otherwise fail the ClientList check (see
      // UserRoleCompositionService#reconcileInheritances's own REMOVE-loop comment).
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      WindowAccess afterRemoval = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The shared window's access must survive the removal, re-derived from the "
          + "one remaining template (Finance), not silently dropped", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("The window must now be re-derived from Finance, the one remaining template",
          financeTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertTrue("Finance's own access level (full) must be what survives the re-derivation",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-4906 (Task B6, 4th round, access-LEVEL gap) — deterministic, self-contained proof that
   * {@code WindowAccessOverlapCorruptionGuard} enforces most-permissive-wins for a role {@link
   * UserRoleCompositionService} never even knows about, not just crash-safety.
   *
   * <p>Before this fix, the ADD-path and REMOVE-path triggers above only ever decided WHETHER core
   * takes the safe CREATE path instead of the corrupting UPDATE path (an ownership/ crash concern);
   * NONE of them decided WHICH access level the CREATE path should use. Human-reproduced gap: a
   * role with only a full-access template, then gaining an inheritance from a read-only template on
   * the SAME window, ended up read-only — no exception, silently wrong data, since {@code
   * UserRoleCompositionService#reconcileWindowAccessAfterComposition} (the only most-permissive-wins
   * authority) runs EXCLUSIVELY inside {@code assignTemplateRoles}.
   *
   * <p>Same "bystander" shape as the other two tests in this class — zero {@code
   * UserRoleCompositionService} code anywhere in this call stack — but mirrors the human's EXACT
   * repro order: the FULL template is already composed and committed BEFORE the READ-ONLY template
   * is added, via a raw {@code AD_Role_Inheritance} insert, exactly like a raw Etendo Classic
   * "add role to composition" edit.
   */
  @Test
  public void testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // The two templates' own access levels on the shared window: Finance FULL, Sales READ-ONLY.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      // The bystander gains ONLY the full-access template first — mirrors the human's real repro
      // ("ClassicTemplateTest2Broad alone"), never through assignTemplateRoles.
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);

      WindowAccess afterFinance = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("Sanity: Finance alone must have propagated the shared window",
          afterFinance);
      assertTrue("Sanity: Finance alone must grant full access",
          Boolean.TRUE.equals(afterFinance.isEditableField()));

      // THE GAP: gain a SECOND, READ-ONLY template on the SAME window — zero
      // UserRoleCompositionService code anywhere in this call stack, exactly like the human's raw
      // Classic UI "add role to composition" edit. Must not throw AND must not downgrade access.
      addInheritance(bystanderRole, salesTemplate, 20L);

      WindowAccess afterSales = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The shared window's access must survive gaining the second template",
          afterSales);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterSales.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterSales.getOrganization().getId());
      assertTrue("Most-permissive-wins: gaining a READ-ONLY template must never downgrade "
          + "already-existing FULL access (Finance), even outside UserRoleCompositionService",
          Boolean.TRUE.equals(afterSales.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-4906 (Task B6, 5th round, {@code InheritedFrom} bookkeeping gap) — deterministic,
   * self-contained proof that widening a row's access level ALSO repoints {@code InheritedFrom}
   * to the template that actually justifies the widened value, and that a LATER removal of THAT
   * template correctly downgrades the row instead of leaving it stuck.
   *
   * <p>Immediately continues the exact scenario {@link
   * #testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess()} above already
   * proves resolves to full: Finance (full) added FIRST, Sales (read-only) added SECOND —
   * deliberately, NOT the reverse order. Only Finance-first/Sales-second actually reproduces the
   * bookkeeping bug: adding Sales second forces {@code guardNewInheritance} to delete-and-recreate
   * the dependent's row sourced from Sales (the newly-added template), which {@code
   * widenInheritedAccessLevelIfNeeded} then widens back to full because Finance still justifies
   * it — this is the live-reproduced order (the human's real {@code ClassicTemplateTest2Broad}
   * added, THEN {@code ClassicTemplateTest1Read} added). Adding the full template SECOND instead
   * would have core's own {@code applyNewInheritance} source the fresh row directly from the full
   * template, already correct on both level AND {@code InheritedFrom} with nothing to widen —
   * not a reproduction of this bug at all.
   *
   * <p>Before this round's fix, the widened row's {@code InheritedFrom} stayed pointed at Sales
   * (the template CREATE originally sourced the row from, which does NOT itself grant full — that
   * mismatch is exactly why widening was needed) instead of Finance (the template that actually
   * justifies the value) — confirmed live via a direct {@code psql} query against the human's real
   * repro. Removing Finance's inheritance afterward then had NO EFFECT: both core's own
   * re-derivation and this class's own {@link #guardRemovedInheritance(RoleInheritance)} decide
   * whether a row needs re-evaluating by checking whether {@code InheritedFrom} matches the
   * template being removed/still-remaining, and a row that lies about its own source is invisible
   * to that check for the one removal that should actually affect it — reproduced here as the
   * assertion that would fail without this round's fix.
   */
  @Test
  public void testRemovingTheTemplateThatJustifiedAWidenedAccessLevelCorrectlyDowngrades()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // The two templates' own access levels on the shared window: Finance FULL, Sales READ-ONLY.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      // Finance (full) FIRST, Sales (read-only) SECOND — see this test's own javadoc for why this
      // exact order, not the reverse, is required to reproduce the bug.
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      WindowAccess widened = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("Sanity: gaining Sales must not have dropped the shared window", widened);
      assertTrue("Sanity: most-permissive-wins must still resolve to full (round 4's own fix, "
          + "reused here as the setup for round 5's own gap)",
          Boolean.TRUE.equals(widened.isEditableField()));
      // THE ROUND-5 FIX, asserted directly: InheritedFrom must be repointed to the template that
      // ACTUALLY justifies the widened value (Finance), not left pointing at Sales (the template
      // CREATE originally sourced the row from) — the exact field a live psql query on the
      // human's real repro found stale.
      assertEquals("InheritedFrom must point at the template that actually justifies the "
          + "widened value (Finance), not the template CREATE originally sourced the row from "
          + "(Sales) — this is the exact bookkeeping bug this round fixes",
          financeTemplate.getId(),
          widened.getInheritedFrom() != null ? widened.getInheritedFrom().getId() : null);

      // Now remove the FULL template's inheritance (Finance). With InheritedFrom now correctly
      // pointing at Finance, this removal must be recognized by both guardRemovedInheritance and
      // core's own re-derivation as "this row needs re-evaluating" and downgrade it to Sales's
      // read-only grant. Before this round's fix, InheritedFrom wrongly said "Sales" so this
      // removal was invisible to both mechanisms and the row stayed stuck at full forever — the
      // human's own live-confirmed symptom.
      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      // Same OBContext.setAdminMode() bypass addInheritance()/grantWindowAccess() already use —
      // see UserRoleCompositionService#reconcileInheritances's REMOVE-loop comment for why.
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      WindowAccess afterRemoval = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The shared window's access must survive the removal, re-derived from the "
          + "one remaining template (Sales), not silently dropped", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("The window must now be re-derived from Sales, the one remaining template",
          salesTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertTrue("Removing the FULL template must downgrade to the remaining READ-ONLY "
          + "template's level, not stay stuck at full (the exact live-confirmed regression this "
          + "round fixes)",
          !Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-4906 (Task B6, 6th round, human-found on the REAL {@code SFAssignUserRoles} webhook,
   * 2026-08-17) — deterministic, self-contained reproduction of the first B6 gap to break the
   * ACTUAL production flow, not a raw Etendo Classic edit. All five rounds above (and every test
   * in this class before this one) only ever compose a role from EXACTLY 2 templates, so removing
   * one always leaves exactly ONE remaining template — structurally incapable of exercising the
   * bug this test reproduces, which requires 2+ REMAINING templates to BOTH grant the SAME window
   * after a third is removed (only possible starting at 3 templates total).
   *
   * <p>Mirrors the human's real repro shape (composed from all 4 real system templates, Finance
   * unchecked via the real "Asignar roles" flow) using the SAME "bystander" construction as the
   * rest of this class — built directly via {@code AD_Role_Inheritance}, not through {@code
   * assignTemplateRoles} — because the crash is in {@code
   * WindowAccessOverlapCorruptionGuard#guardRemovedInheritance} itself (triggered by ANY {@code
   * RoleInheritance} delete, {@code assignTemplateRoles} included), not in anything {@code
   * UserRoleCompositionService} does. Before this round's fix: {@code
   * javax.persistence.PersistenceException: org.hibernate.exception.ConstraintViolationException:
   * could not execute batch} — {@code ad_window_access_un_key} duplicate — reproduced live against
   * the human's own real role via psql (see this ticket's plan doc, "B6 Findings — 6th gap") and,
   * before the fix below, by this very test locally.
   */
  @Test
  public void testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role purchasingTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.PURCHASING_ROLE_ID);
      Role inventoryTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.INVENTORY_ROLE_ID);
      assertNotNull("The real Finance system template must already exist", financeTemplate);
      assertNotNull("The real Sales system template must already exist", salesTemplate);
      assertNotNull("The real Purchasing system template must already exist", purchasingTemplate);
      assertNotNull("The real Inventory system template must already exist", inventoryTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // Finance grants the shared window FULL. Sales AND Purchasing BOTH ALSO grant it,
      // READ-ONLY — the "2+ REMAINING templates overlap on the same window" shape that can only
      // exist with 3+ templates composed. Inventory does not grant this window at all — present
      // only to match the human's real 4-template composition shape (Finance/Sales/Purchasing/
      // Inventory); it plays no other role in this reproduction.
      grantWindowAccess(financeTemplate, sharedWindow, false);
      OBDal.getInstance().flush();
      grantWindowAccess(salesTemplate, sharedWindow, true);
      OBDal.getInstance().flush();
      grantWindowAccess(purchasingTemplate, sharedWindow, true);
      OBDal.getInstance().flush();

      // Compose the bystander from all 4 templates, ascending SeqNo, exactly mirroring the real
      // template order (Finance/Sales/Purchasing/Inventory) and the human's real role's shape.
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);
      addInheritance(bystanderRole, purchasingTemplate, 30L);
      addInheritance(bystanderRole, inventoryTemplate, 40L);

      WindowAccess beforeRemoval = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("Sanity: composing all 4 templates must have propagated the shared window",
          beforeRemoval);
      assertEquals("Sanity: Finance is the only full grantor among all 4, so it must be the "
          + "source before removal",
          financeTemplate.getId(),
          beforeRemoval.getInheritedFrom() != null ? beforeRemoval.getInheritedFrom().getId()
              : null);
      assertTrue("Sanity: most-permissive-wins must resolve to full before removal",
          Boolean.TRUE.equals(beforeRemoval.isEditableField()));

      // THE 6TH-ROUND TRIGGER: remove Finance's inheritance — zero UserRoleCompositionService
      // code anywhere in this call stack, exactly like the guard's own scope. Sales AND
      // Purchasing BOTH still grant the shared window afterward. Before this round's fix, this
      // threw a duplicate-key ConstraintViolationException; must now succeed.
      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      // Same OBContext.setAdminMode() bypass addInheritance()/grantWindowAccess() already use —
      // see UserRoleCompositionService#reconcileInheritances's REMOVE-loop comment for why.
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      WindowAccess afterRemoval = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The shared window's access must survive the removal, re-derived from the "
          + "2 remaining overlapping templates, not silently dropped or duplicated", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("Purchasing (the highest-SeqNo template among the 2 remaining templates that "
          + "grant this window) must become the new source",
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

  /**
   * ETP-4906 (Task B6, REVIEW round, finding "[B7]", 2026-08-17) — deterministic, self-contained
   * proof that updating a TEMPLATE's OWN existing {@code AD_Window_Access} row (e.g. an admin
   * toggling its read-only/full checkbox directly in Etendo Classic — core's own {@code onUpdate}/
   * {@code propagateUpdatedAccess} trigger, NOT a new grant) never permanently deletes an
   * already-correctly-inheriting dependent's row.
   *
   * <p>Round 7 (commit {@code dfb7b242}) made {@code
   * WindowAccessOverlapCorruptionGuard#guardDependentsOf}'s underlying unconditional-delete fix
   * apply uniformly to BOTH the {@code onSave} trigger (a template GAINS
   * a NEW window grant) and the {@code onUpdate} trigger (a template's OWN EXISTING grant has its
   * level changed) — but only the FIRST always has a core-side CREATE fallback ({@code
   * RoleInheritanceManager#propagateNewAccess} to {@code handleAccess} to {@code copyRoleAccess}).
   * The SECOND, {@code propagateUpdatedAccess}, has none at all: it only ever UPDATEs a dependent's
   * row it can find, and does nothing otherwise. Deleting first on THAT trigger therefore
   * permanently lost the dependent's access with nothing left to restore it — REVIEW's own "[B7]"
   * finding, traced via static core-source analysis (see the plan doc for the full trace).
   *
   * <p>Uses a THROWAWAY, freshly-created system-client ({@code AD_Client_ID = '0'}) template role
   * (mirrors {@code UserRoleCompositionServiceIntegrationTest#createSystemTemplateRole}), NOT one
   * of the 4 real system templates — reusing a real template for a direct UPDATE on its own row
   * cascades across every real pre-existing dependent in this shared dev DB (dozens of unrelated
   * corrections, Hibernate collection-management noise), too noisy to isolate cleanly; this is
   * exactly what tripped up REVIEW's own 2nd repro attempt (see the plan doc's "[B7]" section).
   * The dependent is the SAME kind of throwaway tenant-client "bystander" role the rest of this
   * class already uses successfully paired with the REAL templates ({@link #createBystanderRole}).
   * This test pairs a throwaway TEMPLATE with a throwaway BYSTANDER — the union of two patterns
   * each already independently proven to work elsewhere in this module's own test suite (system
   * client {@code "0"} is universally readable, unlike an arbitrary tenant client), sidestepping
   * REVIEW's own suspected client-visibility precondition on a fully-fresh fixture (its own 3rd
   * repro attempt, where the ADD-path propagation never even fired).
   */
  @Test
  public void testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role template = createThrowawaySystemTemplateRole();
      grantWindowAccess(template, sharedWindow, true); // starts read-only
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // The ADD path first — proven safe by every other test in this class — so the dependent's
      // row genuinely, already correctly inherits from `template` before the UPDATE trigger below
      // even runs.
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, template, 10L);

      WindowAccess beforeUpdate = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("Sanity: the dependent must have inherited the template's grant via the ADD "
          + "path before this test's own UPDATE trigger runs", beforeUpdate);
      assertEquals("Sanity: the dependent's row must already be sourced from this exact template",
          template.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);
      assertFalse("Sanity: the template's initial grant was read-only",
          Boolean.TRUE.equals(beforeUpdate.isEditableField()));

      // THE B7 TRIGGER: UPDATE (never create) the template's OWN existing AD_Window_Access row —
      // core's onUpdate/propagateUpdatedAccess path, which has NO create fallback if it cannot
      // find a dependent's row.
      WindowAccess templateAccess = findWindowAccess(template, sharedWindow);
      assertNotNull(templateAccess);
      updateWindowAccessLevel(templateAccess, false); // widen the template's own grant to full

      WindowAccess afterUpdate = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("THE B7 FIX: the dependent's row must survive a routine UPDATE to the "
          + "template's own access level, not be silently deleted with nothing left to restore "
          + "it", afterUpdate);
      assertEquals("client must always match the DEPENDENT role's own, never a template's",
          bystanderRole.getClient().getId(), afterUpdate.getClient().getId());
      assertEquals("organization must always match the DEPENDENT role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterUpdate.getOrganization().getId());
      assertEquals("InheritedFrom must still name the same template",
          template.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
      assertTrue("The dependent's access level must be corrected to match the template's new "
          + "(widened) value, not left stale at the old read-only level",
          Boolean.TRUE.equals(afterUpdate.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * [BUG-2 regression, ETP-4906 QA final coverage pass, 2026-08-18] The permanent, committed
   * version of QA's own throwaway reproduction probe. Before the fix, {@code
   * repointIfAlreadySourcedFromTemplate} (the B7 trigger above) copied the just-edited template's
   * new value onto the dependent's row WITHOUT checking whether some OTHER actively-inherited
   * template still justified the OLD (more permissive) value — silently violating
   * most-permissive-wins. Mirrors {@link
   * #testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow()}'s own
   * structure exactly, just with a SECOND overlapping template in play throughout (that test used
   * exactly one, which is precisely the coverage gap QA's pass found).
   */
  @Test
  public void testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role templateA = createThrowawaySystemTemplateRole();
      Role templateB = createThrowawaySystemTemplateRole();
      grantWindowAccess(templateA, sharedWindow, false); // full
      grantWindowAccess(templateB, sharedWindow, false); // full
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // Both templates active, both granting full — templateB added SECOND (higher SeqNo), so
      // core's own tie-break sources the bystander's row from templateB (mirrors the class
      // javadoc's own documented SeqNo-descending tie-break).
      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, templateA, 10L);
      addInheritance(bystanderRole, templateB, 20L);

      WindowAccess beforeUpdate = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("Sanity: the dependent must have inherited full access from the overlapping "
          + "templates before this test's own UPDATE trigger runs", beforeUpdate);
      assertTrue("Sanity: the dependent starts at full access",
          Boolean.TRUE.equals(beforeUpdate.isEditableField()));
      assertEquals("Sanity: the row is sourced from templateB (the higher-SeqNo grantor) — the "
          + "exact precondition for repointIfAlreadySourcedFromTemplate to fire below",
          templateB.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);

      // THE BUG-2 TRIGGER: downgrade templateB's OWN access level — a routine Etendo Classic
      // admin edit — while templateA is STILL actively inherited and STILL grants this exact
      // window full access.
      WindowAccess templateBAccess = findWindowAccess(templateB, sharedWindow);
      assertNotNull(templateBAccess);
      updateWindowAccessLevel(templateBAccess, true); // downgrade B to read-only

      Role stillActiveTemplateA = OBDal.getInstance().get(Role.class, templateA.getId());
      WindowAccess templateAAccessAfter = findWindowAccess(stillActiveTemplateA, sharedWindow);
      assertNotNull("Direct confirmation templateA is still active and still grants full access "
          + "at the moment of the assertion below", templateAAccessAfter);
      assertTrue(Boolean.TRUE.equals(templateAAccessAfter.isEditableField()));

      WindowAccess afterUpdate = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The dependent's row must survive the downgrade", afterUpdate);
      assertTrue("MOST-PERMISSIVE-WINS (BUG-2 fix): the dependent must STAY at full access — "
          + "templateA still actively grants this window full access, even though templateB "
          + "(the one just downgraded) no longer does",
          Boolean.TRUE.equals(afterUpdate.isEditableField()));
      assertEquals("InheritedFrom must repoint to the template that actually still justifies "
          + "full access (templateA), not stay pointed at templateB whose own new value no "
          + "longer backs it",
          templateA.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * [GAP-1, ETP-4906 QA final coverage pass, 2026-08-18] Every test in this file before this one
   * used exactly ONE shared window per scenario — real system templates grant 27/13 windows each,
   * and a single {@code RoleInheritance} add fans out into one {@code guardNewInheritance} call
   * covering EVERY window the template grants, in the SAME method invocation. This test proves
   * that fan-out resolves each window's most-permissive-wins verdict independently and correctly,
   * not just the first one encountered.
   */
  @Test
  public void testSingleInheritanceEventAffectingMultipleWindowsResolvesEachWindowIndependently()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window windowOne = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      Window windowTwo = OBDal.getInstance().get(Window.class, FINANCE_ONLY_WINDOW_ID);
      Window windowThree = OBDal.getInstance().get(Window.class, SALES_ONLY_WINDOW_ID);
      assertNotNull(windowOne);
      assertNotNull(windowTwo);
      assertNotNull(windowThree);
      List<Window> windows = Arrays.asList(windowOne, windowTwo, windowThree);

      Role templateA = createThrowawaySystemTemplateRole();
      Role templateB = createThrowawaySystemTemplateRole();

      // templateA grants all 3 windows FULL; templateB grants all 3 windows READ-ONLY.
      for (Window window : windows) {
        grantWindowAccess(templateA, window, false);
      }
      OBDal.getInstance().flush();
      for (Window window : windows) {
        grantWindowAccess(templateB, window, true);
      }
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, templateA, 10L);

      for (Window window : windows) {
        WindowAccess afterA = findWindowAccess(bystanderRole, window);
        assertNotNull("Sanity: templateA alone must propagate window " + window.getId(), afterA);
        assertTrue("Sanity: templateA alone grants full access on window " + window.getId(),
            Boolean.TRUE.equals(afterA.isEditableField()));
      }

      // THE GAP-1 TRIGGER: a SINGLE RoleInheritance ADD event for templateB, which itself grants
      // ALL 3 windows read-only — guardNewInheritance fans out over every one of templateB's
      // grants within the SAME method call/flush.
      addInheritance(bystanderRole, templateB, 20L);

      for (Window window : windows) {
        WindowAccess afterB = findWindowAccess(bystanderRole, window);
        assertNotNull("Window " + window.getId() + " must survive gaining the second template",
            afterB);
        assertTrue("MOST-PERMISSIVE-WINS must hold independently for EVERY window affected by "
            + "this single inheritance event, not just the first one — window " + window.getId()
            + " must stay full (templateA still grants it) even though templateB (just added) "
            + "only grants read-only",
            Boolean.TRUE.equals(afterB.isEditableField()));
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * [GAP-2, ETP-4906 QA final coverage pass, 2026-08-18] Every {@code flush()} call in this file
   * before this one immediately follows exactly ONE guard-relevant mutation. This test batches
   * TWO guard-triggering template updates into the SAME flush — the same failure class
   * (Hibernate flush-loop reentrancy, {@code Interceptor#onFlushDirty} firing from inside
   * {@code AbstractFlushingEventListener#flushEntities}'s own entity-walking loop) that produced
   * B7's own {@code session.evict()} bug, fixed via {@code OBDal.refresh()} — see {@link
   * #repointInPlace(WindowAccess, Role, Window, Role, boolean, Role)}'s own javadoc. Asserts both
   * corrections land correctly with no Hibernate collection-tracking exception.
   */
  @Test
  public void testTwoGuardTriggeringTemplateUpdatesInsideASingleFlushDoNotCauseHibernateReentrancy()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window sharedWindow = OBDal.getInstance().get(Window.class, UNUSED_WINDOW_ID);
      assertNotNull(sharedWindow);

      Role templateA = createThrowawaySystemTemplateRole();
      Role templateB = createThrowawaySystemTemplateRole();
      grantWindowAccess(templateA, sharedWindow, false); // full
      grantWindowAccess(templateB, sharedWindow, false); // full
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, templateA, 10L);
      addInheritance(bystanderRole, templateB, 20L);

      WindowAccess beforeUpdate = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull(beforeUpdate);
      assertTrue("Sanity: both templates start full",
          Boolean.TRUE.equals(beforeUpdate.isEditableField()));

      // THE GAP-2 TRIGGER: downgrade BOTH templates' own access rows in the SAME transaction
      // BEFORE a single shared flush() — 2 onUpdate/guard-triggering mutations reaching this
      // class from WITHIN the SAME Hibernate flushEntities loop.
      OBContext.setAdminMode();
      try {
        WindowAccess templateAAccess = findWindowAccess(templateA, sharedWindow);
        WindowAccess templateBAccess = findWindowAccess(templateB, sharedWindow);
        assertNotNull(templateAAccess);
        assertNotNull(templateBAccess);
        templateAAccess.setEditableField(false);
        templateBAccess.setEditableField(false);
        OBDal.getInstance().save(templateAAccess);
        OBDal.getInstance().save(templateBAccess);
        OBDal.getInstance().flush(); // single shared flush for BOTH mutations
      } finally {
        OBContext.restorePreviousMode();
      }

      WindowAccess afterUpdate = findWindowAccess(bystanderRole, sharedWindow);
      assertNotNull("The dependent's row must survive 2 simultaneous guard-triggering template "
          + "updates within the same flush with no Hibernate reentrancy exception", afterUpdate);
      assertEquals("client must always match the DEPENDENT role's own",
          bystanderRole.getClient().getId(), afterUpdate.getClient().getId());
      assertEquals("organization must always match the DEPENDENT role's own",
          bystanderRole.getOrganization().getId(), afterUpdate.getOrganization().getId());
      assertFalse("Both overlapping templates were downgraded to read-only in the same flush — "
          + "the dependent's row must end up read-only too, with no leftover full access",
          Boolean.TRUE.equals(afterUpdate.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Throwaway system-client ({@code AD_Client_ID = '0'}) template role — mirrors {@code
   * UserRoleCompositionServiceIntegrationTest#createSystemTemplateRole} exactly (same fixture-only
   * no-arg {@code OBContext.setAdminMode()} bypass rationale: system client {@code '0'} rows are
   * never gated by {@code SecurityChecker} for a plain read, but creating one directly here does
   * need the write-access bypass). Deliberately NOT one of the 4 real {@link SystemRoleTemplates}
   * rows — see {@link
   * #testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow()}'s own
   * javadoc for why.
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
      role.setName("ETP-4906 B7 throwaway template " + System.nanoTime());
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

  /**
   * UPDATEs (never creates) an existing template's own {@code AD_Window_Access} row's level — the
   * B7 trigger's own entry point (core's {@code onUpdate}/{@code propagateUpdatedAccess}). Same
   * no-arg {@code OBContext.setAdminMode()} bypass rationale as {@link #grantWindowAccess}: only
   * the no-arg overload disables {@code SecurityChecker.checkWriteAccess} — confirmed empirically
   * by REVIEW's own "[B7]" finding (the boolean overload, {@code setAdminMode(true)}, does NOT),
   * see the plan doc for the full trace into {@code OBContext.java:213-242}.
   */
  private void updateWindowAccessLevel(WindowAccess access, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      access.setEditableField(!readOnly);
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

  private Role createBystanderRole(User user) {
    Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(user.getClient());
    role.setOrganization(starOrg);
    role.setActive(true);
    role.setName("ETP-4906 B6 bystander " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    return role;
  }

  /**
   * Flushes immediately after saving — mirrors {@code UserRoleCompositionService
   * #reconcileInheritances}'s own established pattern (save one {@code AD_Role_Inheritance} row,
   * flush, THEN move to the next), never batching 2+ inheritance-adds into one flush. See the call
   * site's own comment for why this matters here specifically.
   *
   * <p>Same {@code OBContext.setAdminMode()} bypass its sibling {@link #grantWindowAccess} already
   * uses: saving this row fires core's {@code RoleInheritanceEventHandler}, which fans out through
   * every registered {@code AccessTypeInjector} (window, tab, field, process, OBUIAPP process, ...)
   * to copy the template's accesses onto {@code role} — still carrying the template's own client
   * ("0" for a system-level template) until this flush, which runs under the caller's normal
   * context otherwise. See {@code UserRoleCompositionService#reconcileInheritances}'s own comment
   * on its equivalent production-path flush for the full explanation.</p>
   */
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

  private void grantWindowAccess(Role role, Window window, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      WindowAccess access = OBProvider.getInstance().get(WindowAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setWindow(window);
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private WindowAccess findWindowAccess(Role role, Window window) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW, window));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (WindowAccess) criteria.uniqueResult();
  }
}
