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
      OBDal.getInstance().flush();

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
      OBDal.getInstance().flush();

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
      OBDal.getInstance().flush();

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
    OBDal.getInstance().flush();
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
