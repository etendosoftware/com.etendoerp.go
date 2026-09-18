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

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

/**
 * ETP-5329 bug fix — real-DB, end-to-end proof that {@link
 * UserRoleCompositionService#getAppliedTemplateRoleIds(String)} (called by {@code
 * EtendoGoJwtSupport#resolveEffectiveRoleNames} on every environment login, to build {@code
 * effectiveRoleNames}) still finds a personal role's composed template when the caller's {@link
 * OBContext} is the system one — reproducing the EXACT caller context that exposed the
 * readable-clients filter gap in {@link RoleInheritanceReconciliationService
 * #findExistingInheritances}: {@code OBContext.setOBContext("0", "0", "0", "0")} followed by
 * {@code OBContext.setAdminMode(true)} (see {@code EtendoGoJwtServlet#handleEnvironmentLogin}),
 * under which the tenant's real client ({@link #TEST_CLIENT_ID}) is NOT in the caller's
 * readable-clients set.
 *
 * <p><b>Deliberately extends plain {@code OBBaseTest}, NOT {@code WeldBaseTest}.</b> Unlike
 * {@link UserRoleCompositionServiceIntegrationTest} (which needs the Arquillian/Weld container so
 * core's {@code RoleInheritanceEventHandler} CDI observer fires and propagates {@code
 * AD_Window_Access}), this test's assertion is purely about ROW VISIBILITY under a Hibernate
 * {@code OBCriteria} filter — {@code findExistingInheritances} is plain {@code OBDal}/Hibernate
 * code with no CDI dependency at all. Composing via {@link
 * UserRoleCompositionService#assignTemplateRoles(String, java.util.List)} still works fine under
 * plain {@code OBBaseTest} (the window-access propagation side effect is silently skipped, per
 * {@code WeldBaseTest}'s own class javadoc — a test-harness gap, not an error), and this test
 * never checks {@code AD_Window_Access} at all, so that gap is irrelevant here. Keeping this out
 * of the Weld-based suite avoids paying that suite's per-class Arquillian container bootstrap
 * (ShrinkWrap-scans the whole webapp's {@code WebContent/WEB-INF/lib} + {@code build/classes})
 * for a scenario that does not need it.</p>
 *
 * <p>Composes a real template role for {@link #TEST_USER_ID} under the tenant's own context
 * first (so the {@code AD_Role_Inheritance} row genuinely exists at another, non-system client),
 * THEN switches to the system context and calls {@code getAppliedTemplateRoleIds}. Before the fix
 * in {@code findExistingInheritances} (adding {@code setFilterOnReadableClients(false)}/{@code
 * setFilterOnReadableOrganization(false)}, mirroring the already-correct bulk equivalent {@code
 * findActiveTemplateIdsByPersonalRoleId}), this came back an EMPTY list — the criteria's default
 * readable-clients filter silently excluded the tenant client's row — even though the row
 * genuinely exists. This test would have FAILED on that assertion prior to the fix.</p>
 *
 * <p>Nothing here is ever committed — the throwaway template role, the personal role, its
 * inheritance, its {@code AD_User_Roles} row, and the mutated {@link #TEST_USER_ID} default role
 * are all rolled back in {@link #rollbackChanges()}, mirroring {@code
 * UserRoleCompositionServiceIntegrationTest}'s convention.</p>
 */
public class RoleInheritanceReconciliationServiceSystemContextIntegrationTest extends OBBaseTest {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String STAR_ORG_ID = "0";

  /**
   * Both paths in {@link #testGetAppliedTemplateRoleIdsIsVisibleFromASystemOBContext()} already
   * pair every {@code setAdminMode(true)}/{@code setOBContext(...)} push with exactly one {@code
   * finally { OBContext.restorePreviousMode(); }} pop, so by the time this runs the admin-mode
   * stack is already back to whatever {@code OBBaseTest#setUp()} established for the test's own
   * lifecycle (legitimately admin, for the whole test) — there is nothing left for this method to
   * unwind. Deliberately NOT a {@code while (isInAdministratorMode()) restorePreviousMode()}
   * loop: that pattern cannot tell "test-pushed" apart from "base-test's own" admin mode, so once
   * it pops past what the test actually pushed it underflows the stack — {@code
   * restorePreviousMode()}'s unbalanced-pop warning path never flips {@code
   * isInAdministratorMode()} back to {@code false}, so the loop spins forever, each iteration
   * paying a full stack-trace-capture-and-log logging cost (confirmed via jstack against an
   * earlier draft of this method: 93%+ of elapsed time in {@code
   * OBContext.restorePreviousMode -> printUnbalancedWarning -> AbstractLogger.warn ->
   * fillInStackTrace}, zero forward progress). {@code OBBaseTest} itself already has a one-shot
   * safety net for genuinely leftover admin mode ({@code clearAdminModeStack()} + a single {@code
   * restorePreviousMode()}, with a warning) — this method does not need to duplicate it.
   */
  @After
  public void rollbackChanges() {
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testGetAppliedTemplateRoleIdsIsVisibleFromASystemOBContext() throws Exception {
    String personalRoleId;
    String templateId;
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Role template = createSystemTemplateRole();
      OBDal.getInstance().flush();
      templateId = template.getId();

      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(TEST_USER_ID, Collections.singletonList(templateId));
      assertEquals("Sanity check: the composition itself must succeed under the tenant's own "
          + "context before the system-context read is exercised", 1, result.addedCount);
      personalRoleId = result.personalRoleId;
    } finally {
      OBContext.restorePreviousMode();
    }
    assertNotNull(personalRoleId);

    // Reproduce EtendoGoJwtServlet#handleEnvironmentLogin's exact caller context.
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      List<String> appliedTemplateIds =
          new UserRoleCompositionService().getAppliedTemplateRoleIds(TEST_USER_ID);

      assertTrue("The personal role's composed template must still be visible from a system "
              + "OBContext (the environment-login flow's caller context) — before the fix this "
              + "came back empty because findExistingInheritances left the readable-clients "
              + "filter enabled",
          appliedTemplateIds.contains(templateId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * QA (ETP-5329, post-fix re-verification) — companion to {@link
   * #testGetAppliedTemplateRoleIdsIsVisibleFromASystemOBContext()}: proves the SAME widened
   * {@code findExistingInheritances} filter behaves for the "personal role exists but has ZERO
   * composed templates" state too — {@code getAppliedTemplateRoleIds}'s own javadoc documents
   * this as a legitimate empty-list return, never an exception. Not exercised by any existing
   * test: {@code EtendoGoJwtSupportTest#omitsEffectiveRoleNamesWhenNoTemplatesApplied} asserts
   * the same outward behavior but does so with {@code UserRoleCompositionService} entirely
   * mocked out via {@code mockConstruction} — it never runs the real {@code OBCriteria} query
   * this fix touches. Ensures the personal role via {@code assignTemplateRoles(userId,
   * emptyList())} (same "compose zero templates" idiom {@code
   * UserRoleCompositionServiceIntegrationTest} already uses, e.g. its {@code
   * removesAllInheritancesWhenComposingWithEmptyList} case) under the tenant's own context, then
   * reads it back under the reproduced system-login {@link OBContext}.
   */
  @Test
  public void testGetAppliedTemplateRoleIdsIsEmptyFromASystemOBContextWhenNoTemplatesComposed()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(TEST_USER_ID, Collections.emptyList());
      assertNotNull("Sanity check: a personal role must exist (even with zero templates) before "
          + "the system-context read is exercised", result.personalRoleId);
    } finally {
      OBContext.restorePreviousMode();
    }

    // Reproduce EtendoGoJwtServlet#handleEnvironmentLogin's exact caller context.
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      List<String> appliedTemplateIds =
          new UserRoleCompositionService().getAppliedTemplateRoleIds(TEST_USER_ID);

      assertNotNull("Must return an empty list, never null, per this method's own javadoc",
          appliedTemplateIds);
      assertTrue("A personal role with zero composed templates must resolve to an empty list "
              + "under a system OBContext too — the widened readable-clients/organization "
              + "filter must not turn a genuinely empty AD_Role_Inheritance result into an "
              + "error or a non-empty one",
          appliedTemplateIds.isEmpty());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Creates a throwaway system-level ({@code AD_Client_ID = '0'}) template role — same fixture
   * shape as {@code UserRoleCompositionServiceIntegrationTest#createSystemTemplateRole()}
   * (duplicated rather than shared, since that one is {@code private} and this test deliberately
   * lives outside the Weld-based suite).
   */
  private Role createSystemTemplateRole() {
    OBContext.setAdminMode();
    try {
      Client systemClient = OBDal.getInstance().get(Client.class, SYSTEM_CLIENT_ID);
      Organization starOrg = OBDal.getInstance().get(Organization.class, STAR_ORG_ID);
      Role role = OBProvider.getInstance().get(Role.class);
      role.setNewOBObject(true);
      role.setClient(systemClient);
      role.setOrganization(starOrg);
      role.setActive(true);
      role.setName("ETP-5329 IT template " + System.nanoTime());
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
}
