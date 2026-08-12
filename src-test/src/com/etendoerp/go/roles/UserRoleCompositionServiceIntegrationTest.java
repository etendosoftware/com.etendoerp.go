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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-4852 — real-DB, end-to-end proof that a system-level ({@code AD_Client_ID = '0'}) template
 * role's {@code AD_Window_Access} propagates onto a per-tenant personal role purely via core's
 * own {@code RoleInheritanceEventHandler}/{@code RoleInheritanceManager} — the exact claim the
 * ticket asked to be VERIFIED live, not assumed from reading the source.
 *
 * <p><b>Extends {@code WeldBaseTest}, NOT plain {@code OBBaseTest}.</b> Propagation is driven by
 * a Hibernate {@code Interceptor} ({@code PersistenceEventOBInterceptor}) firing a CDI {@code
 * EntityNewEvent}, observed by core's {@code RoleInheritanceEventHandler} (an {@code
 * EntityPersistenceEventObserver}), which in turn invokes the {@code @ApplicationScoped
 * RoleInheritanceManager}. None of that wiring exists under plain {@code OBBaseTest} — only
 * {@code WeldBaseTest} (via its Arquillian {@code @RunWith} and {@code
 * kernelInitializer.setInterceptor()} in {@code setUp()}) actually installs the interceptor into
 * {@code SessionFactoryController}'s Hibernate {@code Configuration} and boots the CDI container
 * that hosts the observer. Confirmed by precedent: {@code
 * GoodsReceiptNoStockCompletionIntegrationTest} in this same module already uses {@code
 * WeldBaseTest} for the same reason, and core's own {@code AccessPropagation} role-inheritance
 * test (@code org.openbravo.test.role.inheritance}) does too. Under plain {@code OBBaseTest} the
 * {@code @Inject Event<EntityNewEvent>} producer in the interceptor is never wired to any
 * observer, so {@code RoleInheritanceEventHandler#onSave} silently never runs — this is a test
 * harness gap, not a bug in {@link UserRoleCompositionService}: production code always runs
 * inside the webapp's real CDI container, where the interceptor is installed once at startup.</p>
 *
 * <p>Deliberately does NOT depend on {@code EnsureSystemRoleTemplatesScript} having run against
 * this test database (that {@code ModuleScript} only fires during {@code update.database}, not
 * inside a unit-test JVM) — it mints its own throwaway template role at {@code AD_Client_ID =
 * '0'} so this test is self-contained and never touches the real {@link
 * SystemRoleTemplates} rows.</p>
 *
 * <p>Nothing here is ever committed — every row created (throwaway template role, its window
 * access, the personal role, its inheritance, its {@code AD_User_Roles} row, and the mutated
 * {@link #TEST_USER_ID} default role) is rolled back in {@link #rollbackChanges()}, mirroring
 * {@code TbaiSyncStatusInjectorIntegrationTest}'s convention.</p>
 */
public class UserRoleCompositionServiceIntegrationTest extends WeldBaseTest {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String STAR_ORG_ID = "0";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testWindowAccessPropagatesFromSystemTemplateToPersonalRoleAcrossClients()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window anyWindow = (Window) OBDal.getInstance().createCriteria(Window.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull("Test fixture must contain at least one AD_Window to grant", anyWindow);

      Role template = createSystemTemplateRole();
      grantWindowAccess(template, anyWindow);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);
      assertEquals("Sanity check: the test user must belong to the tenant client, not the "
          + "system client, or this test would not actually exercise the cross-client case",
          TEST_CLIENT_ID, user.getClient().getId());

      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(TEST_USER_ID, Collections.singletonList(template.getId()));

      assertEquals(1, result.addedCount);
      assertEquals(0, result.removedCount);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertNotNull("A personal role must have been created", personalRole);
      assertEquals("The personal role must be owned by the TENANT's client, not the system "
          + "client the template lives at", TEST_CLIENT_ID, personalRole.getClient().getId());

      WindowAccess propagated = findWindowAccess(personalRole, anyWindow);
      assertNotNull("Core's RoleInheritanceManager must have propagated the template's "
          + "AD_Window_Access onto the personal role purely from the AD_Role_Inheritance save "
          + "— no hand-rolled copy exists anywhere in UserRoleCompositionService", propagated);
      assertEquals("The propagated row's InheritedFrom must point back at the system template",
          template.getId(), propagated.getInheritedFrom().getId());

      // Refetch the user (a fresh DAL read, not the same in-memory instance) to confirm the
      // sync actually persisted, not just mutated an object still sitting in the session.
      OBDal.getInstance().refresh(user);
      assertEquals(personalRole.getId(), user.getDefaultRole().getId());

      OBCriteria<UserRoles> userRolesCriteria = OBDal.getInstance().createCriteria(UserRoles.class);
      userRolesCriteria.add(Restrictions.eq(UserRoles.PROPERTY_USERCONTACT, user));
      List<UserRoles> userRoles = userRolesCriteria.list();
      assertEquals("AD_User_Roles must have exactly one active row after composition", 1,
          userRoles.size());
      assertEquals(personalRole.getId(), userRoles.get(0).getRole().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testRemovingATemplateRetractsItsPropagatedWindowAccess() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window anyWindow = (Window) OBDal.getInstance().createCriteria(Window.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull(anyWindow);

      Role template = createSystemTemplateRole();
      grantWindowAccess(template, anyWindow);
      OBDal.getInstance().flush();

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult first = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(template.getId()));
      Role personalRole = OBDal.getInstance().get(Role.class, first.personalRoleId);
      assertNotNull(findWindowAccess(personalRole, anyWindow));

      // Second call with an EMPTY template list must retract what the first call granted.
      UserRoleCompositionService.AssignmentResult second = service.assignTemplateRoles(
          TEST_USER_ID, Collections.emptyList());

      assertEquals(first.personalRoleId, second.personalRoleId);
      assertEquals(0, second.addedCount);
      assertEquals(1, second.removedCount);
      OBDal.getInstance().refresh(personalRole);
      assertTrue("Core's RoleInheritanceManager must have retracted the propagated access once "
          + "its AD_Role_Inheritance row was removed", findWindowAccess(personalRole, anyWindow) == null);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testReRunningWithTheSameTemplateSetIsANoOp() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window anyWindow = (Window) OBDal.getInstance().createCriteria(Window.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull(anyWindow);

      Role template = createSystemTemplateRole();
      grantWindowAccess(template, anyWindow);
      OBDal.getInstance().flush();

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult first = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(template.getId()));
      UserRoleCompositionService.AssignmentResult second = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(template.getId()));

      assertEquals(first.personalRoleId, second.personalRoleId);
      assertEquals("Re-running with the identical template set must reuse the same personal "
          + "role and add nothing new", 0, second.addedCount);
      assertEquals(0, second.removedCount);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * QA (Sentinel, ETP-4852): an empty {@code templateRoleIds} list on a user's FIRST-EVER
   * composition call (no personal role exists yet) is a distinct edge case from "revoke
   * everything on a SECOND call" (already covered by {@link
   * #testRemovingATemplateRetractsItsPropagatedWindowAccess()}) — there is nothing yet to
   * reconcile away, only a personal role to mint. Confirms the service still creates the
   * personal role and syncs {@code AD_User_Roles}/{@code Default_Ad_Role_ID} to it, rather than
   * short-circuiting on "no templates requested" and leaving the user role-less.
   */
  @Test
  public void testEmptyTemplateListOnFirstCompositionStillCreatesPersonalRole() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(TEST_USER_ID, Collections.emptyList());

      assertEquals("Nothing to reconcile on a first-ever call with no templates requested", 0,
          result.addedCount);
      assertEquals(0, result.removedCount);
      assertNotNull("A personal role must still be created even with zero templates requested",
          result.personalRoleId);

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertNotNull(personalRole);
      assertTrue("A freshly created personal role must start with zero AD_Role_Inheritance rows",
          findInheritances(personalRole).isEmpty());

      OBDal.getInstance().refresh(user);
      assertEquals("Default_Ad_Role_ID must be synced to the new personal role even though it "
          + "inherits from nothing", personalRole.getId(), user.getDefaultRole().getId());

      OBCriteria<UserRoles> userRolesCriteria = OBDal.getInstance().createCriteria(UserRoles.class);
      userRolesCriteria.add(Restrictions.eq(UserRoles.PROPERTY_USERCONTACT, user));
      List<UserRoles> userRoles = userRolesCriteria.list();
      assertEquals("AD_User_Roles must have exactly one active row even for a template-less "
          + "personal role", 1, userRoles.size());
      assertEquals(personalRole.getId(), userRoles.get(0).getRole().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * QA (Sentinel, ETP-4852): {@code resolveAndValidateTemplates} dedupes requested ids
   * BEFORE persistence (proved without a DB by {@code
   * UserRoleCompositionServiceTest#deduplicatesRequestedTemplateIdsBeforeValidating}, which
   * stops at the first validation failure) — this is the real-DB counterpart proving the
   * dedup actually holds all the way through a SUCCESSFUL write: repeating the SAME valid
   * template id must not attempt N inserts of the same {@code AD_Role_Inheritance} row (which
   * would otherwise hit {@code ad_role_inheritance_role_un}'s unique constraint) and must not
   * report an inflated {@code addedCount}.
   */
  @Test
  public void testDuplicateValidTemplateIdsInOneRequestProduceOnlyOneInheritance()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Role template = createSystemTemplateRole();
      OBDal.getInstance().flush();

      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(TEST_USER_ID,
              Arrays.asList(template.getId(), template.getId(), template.getId()));

      assertEquals("Three occurrences of the SAME valid template id must collapse into exactly "
          + "one AD_Role_Inheritance row, not one per occurrence", 1, result.addedCount);
      assertEquals(0, result.removedCount);
      assertEquals("appliedTemplateRoleIds must also reflect the deduplicated set, not echo "
          + "back three raw entries", 1, result.appliedTemplateRoleIds.size());

      Role personalRole = OBDal.getInstance().get(Role.class, result.personalRoleId);
      assertEquals(1, findInheritances(personalRole).size());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * QA (Sentinel, ETP-4852): what happens when a template a user already inherits from is
   * deactivated LATER, and a subsequent composition call still asks for that same (now
   * inactive) id — e.g. a UI that re-submits the "currently applied" set without the admin
   * having touched that particular entry. {@code resolveAndValidateTemplates} rejects it (same
   * as any other inactive template — {@code UserRoleCompositionServiceTest#
   * rejectsInactiveTemplateRole}), but the real-DB question this answers is whether that
   * rejection is genuinely fail-fast: validation runs for ALL requested ids BEFORE {@code
   * OBContext.setAdminMode}/any write, so a still-valid PRE-EXISTING inheritance (and the
   * AD_Window_Access core already propagated from it) must be left completely untouched by the
   * failed call, not partially retracted.
   */
  @Test
  public void testRecomposingWithAStillRequestedButNowInactiveTemplateIsRejectedWithoutMutatingExistingAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Window anyWindow = (Window) OBDal.getInstance().createCriteria(Window.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull(anyWindow);

      Role template = createSystemTemplateRole();
      grantWindowAccess(template, anyWindow);
      OBDal.getInstance().flush();

      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult first = service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(template.getId()));
      Role personalRole = OBDal.getInstance().get(Role.class, first.personalRoleId);
      assertNotNull("Sanity check: the template's access must have propagated before "
          + "deactivation", findWindowAccess(personalRole, anyWindow));

      deactivateTemplate(template);

      OBException e = assertThrows(OBException.class, () -> service.assignTemplateRoles(
          TEST_USER_ID, Collections.singletonList(template.getId())));
      assertTrue(e.getMessage().contains("Template role not found or inactive"));

      OBDal.getInstance().refresh(personalRole);
      assertEquals("A rejected recompose call must not retract the AD_Role_Inheritance row "
          + "that predates the template's deactivation", 1, findInheritances(personalRole).size());
      assertNotNull("A rejected recompose call must not retract access propagated BEFORE the "
          + "template was deactivated", findWindowAccess(personalRole, anyWindow));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Deactivates an already-persisted system-level ({@code AD_Client_ID = '0'}) template role —
   * same fixture-only admin-mode-without-client-check rationale as {@link
   * #createSystemTemplateRole()}, since this mutates a client {@code '0'} row.
   */
  private void deactivateTemplate(Role role) {
    OBContext.setAdminMode();
    try {
      role.setActive(false);
      OBDal.getInstance().save(role);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private List<RoleInheritance> findInheritances(Role personalRole) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, personalRole));
    return criteria.list();
  }

  /**
   * Creates a throwaway system-level ({@code AD_Client_ID = '0'}) template role, purely as test
   * fixture setup — the real production seeding path ({@code EnsureSystemRoleTemplatesScript})
   * uses raw SQL via {@code ConnectionProvider}, which never goes through {@code OBDal}/{@code
   * SecurityChecker} at all, so it never hits what this method has to work around.
   *
   * <p><b>Why the extra admin-mode wrapping:</b> {@code SecurityChecker.checkWriteAccess}
   * requires the row's OWN client to literally equal {@code obContext.getCurrentClient()} UNLESS
   * {@code doOrgClientAccessCheck()} is {@code false} — and {@code OBContext.setAdminMode(true)}
   * (used by the surrounding test methods, and by {@code UserRoleCompositionService} itself)
   * passes {@code true} for exactly that flag, so it does NOT bypass this specific check
   * ({@code OBContext.setAdminMode(boolean)}'s javadoc: the {@code boolean} parameter IS {@code
   * doOrgClientAccessCheck}). Only the no-arg {@link OBContext#setAdminMode()} (which calls
   * {@code setAdminMode(false)}) disables it. {@link UserRoleCompositionService} itself never
   * needs this: every row IT writes is scoped to the tenant's own client (the personal role, its
   * {@code AD_Role_Inheritance} rows, the {@code AD_User_Roles} sync) — it only ever READS the
   * client {@code '0'} template via {@code OBDal.get(Role.class, id)}, which {@code
   * SecurityChecker} never gates. So this bypass is fixture-only, not a production gap.</p>
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
      role.setName("ETP-4852 IT template " + System.nanoTime());
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
   * Grants a smoke-test {@code AD_Window_Access} row on the (system-level) throwaway template —
   * same fixture-only admin-mode bypass rationale as {@link #createSystemTemplateRole()}, since
   * the new {@code WindowAccess} row is also client {@code '0'} (it mirrors {@code role}'s own
   * client). Production code never creates a {@code WindowAccess} row itself — those are core's
   * {@code RoleInheritanceManager} propagating the TEMPLATE's existing rows, an entity copy, not
   * a fresh row at a client the service picked — so this has no production equivalent either.
   */
  private void grantWindowAccess(Role role, Window window) {
    OBContext.setAdminMode();
    try {
      WindowAccess access = OBProvider.getInstance().get(WindowAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setWindow(window);
      access.setEditableField(true);
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
