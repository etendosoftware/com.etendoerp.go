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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

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
