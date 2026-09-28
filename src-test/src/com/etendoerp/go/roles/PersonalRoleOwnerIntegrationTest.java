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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-5502 — real-DB proof that demoting an Admin restores the personal role the user OWNS
 * ({@code AD_Role.EM_ETGO_Personal_Owner_ID}), not whichever role happens to carry their name.
 *
 * <p>Before the fix, {@code demoteFromAdmin} restored the first active role named {@code
 * "Personal – <name>"} and accepted it when it had zero {@code AD_User_Roles} rows — which is
 * exactly what a deleted user's orphan role looks like. So a second user with the same name got
 * the deleted user's permissions (scenario A, the ticket), and a user whose role carried a
 * {@code " (2)"} suffix or who was renamed got a brand-new empty role (scenarios B/C).</p>
 *
 * <p>Every row is rolled back in {@link #rollbackChanges()}. Same {@code WeldBaseTest} harness
 * as {@link UserRoleCompositionServiceIntegrationTest}, so core's DAL event handlers run as in
 * production.</p>
 */
public class PersonalRoleOwnerIntegrationTest extends WeldBaseTest {

  private static final String STAR_ORG_ID = "0";

  private final UserRoleCompositionService service = new UserRoleCompositionService();
  private User caller;
  private Role adminRole;
  private String sameName;

  @Before
  public void setUpCallerAdmin() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    adminRole = findClientAdminRole();
    caller = createUser("ETP-5502 IT caller " + System.nanoTime());
    caller.setDefaultRole(adminRole);
    OBDal.getInstance().save(caller);
    OBDal.getInstance().flush();
    sameName = "ETP-5502 Juan Perez " + System.nanoTime();
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  // ── Owner is set on creation ──

  @Test
  public void newPersonalRoleIsOwnedByItsUser() {
    User user = createUser(sameName);
    Role role = composePersonalRole(user);
    assertEquals(user.getId(), role.getETGOPersonalOwner());
  }

  // ── Demote restores the owned role ──

  /** Scenario A (the ticket): a deleted user's orphan role must not be handed to a namesake. */
  @Test
  public void demoteRestoresOwnRoleNotADeletedNamesakesOrphan() {
    User deleted = createUser(sameName);
    Role orphan = composePersonalRole(deleted);
    deleteUser(deleted);

    User juan = createUser(sameName);
    Role own = composePersonalRole(juan);
    assertEquals(personalName(sameName, 2), own.getName());

    assertEquals(own.getId(), promoteThenDemote(juan));
    assertNotEquals(orphan.getId(), own.getId());
  }

  /** Scenario B: two live namesakes; the second one's role carries the " (2)" suffix. */
  @Test
  public void demoteRestoresOwnSuffixedRoleWithALiveNamesake() {
    User first = createUser(sameName);
    composePersonalRole(first);
    User second = createUser(sameName);
    Role own = composePersonalRole(second);

    assertEquals(own.getId(), promoteThenDemote(second));
  }

  /** Scenario C: the user was renamed after their role was created. */
  @Test
  public void demoteRestoresOwnRoleAfterARename() {
    User juan = createUser(sameName);
    Role own = composePersonalRole(juan);
    juan.setName(sameName + " renamed");
    OBDal.getInstance().save(juan);
    OBDal.getInstance().flush();

    assertEquals(own.getId(), promoteThenDemote(juan));
  }

  /** Scenario D (no regression): nothing to restore → a fresh role, owned by the user. */
  @Test
  public void demoteWithoutAPriorRoleCreatesAnOwnedOne() {
    User juan = createUser(sameName);
    juan.setDefaultRole(adminRole);
    OBDal.getInstance().save(juan);
    OBDal.getInstance().flush();

    String restoredId = service.demoteFromAdmin(caller.getId(), adminRole, juan.getId())
        .personalRoleId;
    Role restored = OBDal.getInstance().get(Role.class, restoredId);
    assertEquals(juan.getId(), restored.getETGOPersonalOwner());
    assertEquals(personalName(sameName, 1), restored.getName());
  }

  // ── Legacy roles (owner still NULL): hardened name fallback + self-heal ──

  @Test
  public void legacyFallbackRejectsAnOlderOrphanAndHealsTheOwnSuffixedRole() {
    User deleted = createUser(sameName);
    Role orphan = composePersonalRole(deleted);
    deleteUser(deleted);
    backdate("ad_role", "ad_role_id", orphan.getId());

    User juan = createUser(sameName);
    Role own = composePersonalRole(juan);
    clearOwner(orphan.getId());
    clearOwner(own.getId());

    assertEquals(own.getId(), promoteThenDemote(juan));
    assertEquals("the fallback must record the owner it found", juan.getId(),
        OBDal.getInstance().get(Role.class, own.getId()).getETGOPersonalOwner());
    assertNull("the orphan must stay unattributed",
        OBDal.getInstance().get(Role.class, orphan.getId()).getETGOPersonalOwner());
  }

  @Test
  public void legacyFallbackSkipsARoleOwnedBySomeoneElse() {
    User other = createUser(sameName + " other");
    User juan = createUser(sameName);
    Role own = composePersonalRole(juan);
    // A same-named role that provably belongs to another user, dormant (zero AD_User_Roles).
    Role foreign = composePersonalRole(other);
    renameRole(own.getId(), personalName(sameName, 3));
    renameRole(foreign.getId(), personalName(sameName, 1));
    unassign(other);
    clearOwner(own.getId());

    assertEquals(own.getId(), promoteThenDemote(juan));
  }

  // ── Reuse check on the composition path (step 4) ──

  @Test
  public void compositionNeverReusesARoleOwnedBySomeoneElse() {
    User owner = createUser(sameName + " owner");
    Role foreign = composePersonalRole(owner);
    unassign(owner);

    User juan = createUser(sameName);
    juan.setDefaultRole(foreign);
    OBDal.getInstance().save(juan);
    OBDal.getInstance().flush();

    String roleId = service.assignTemplateRoles(juan.getId(), Collections.emptyList())
        .personalRoleId;
    assertNotEquals(foreign.getId(), roleId);
    assertEquals(juan.getId(), OBDal.getInstance().get(Role.class, roleId).getETGOPersonalOwner());
  }

  @Test
  public void compositionReusesAndHealsALegacyOwnRole() {
    User juan = createUser(sameName);
    Role own = composePersonalRole(juan);
    clearOwner(own.getId());

    String roleId = service.assignTemplateRoles(juan.getId(), Collections.emptyList())
        .personalRoleId;
    assertEquals(own.getId(), roleId);
    assertEquals(juan.getId(), OBDal.getInstance().get(Role.class, roleId).getETGOPersonalOwner());
  }

  // ── helpers ──

  private String promoteThenDemote(User user) {
    service.promoteToAdmin(caller.getId(), adminRole, user.getId());
    return service.demoteFromAdmin(caller.getId(), adminRole, user.getId()).personalRoleId;
  }

  /** Same path a real composition takes: mints (or reuses) the personal role and assigns it. */
  private Role composePersonalRole(User user) {
    String roleId = service.assignTemplateRoles(user.getId(), Collections.emptyList())
        .personalRoleId;
    Role role = OBDal.getInstance().get(Role.class, roleId);
    assertNotNull(role);
    return role;
  }

  private User createUser(String name) {
    User user = OBProvider.getInstance().get(User.class);
    user.setNewOBObject(true);
    user.setClient(OBDal.getInstance().get(Client.class, TEST_CLIENT_ID));
    user.setOrganization(OBDal.getInstance().get(Organization.class, STAR_ORG_ID));
    user.setActive(true);
    user.setName(name);
    user.setUsername("etp5502-" + System.nanoTime());
    OBDal.getInstance().save(user);
    OBDal.getInstance().flush();
    return user;
  }

  /** A real delete: {@code AD_User_Roles} cascades, the personal role stays behind, orphaned. */
  private void deleteUser(User user) {
    String userId = user.getId();
    backdate("ad_user", "ad_user_id", userId);
    nativeUpdate("UPDATE ad_user SET default_ad_role_id = NULL, em_smfsws_default_ws_role_id = NULL"
        + " WHERE ad_user_id = :id", userId);
    nativeUpdate("DELETE FROM ad_user WHERE ad_user_id = :id", userId);
  }

  /** Pushes a row one day into the past, so it is clearly older than anything created later. */
  private void backdate(String table, String keyColumn, String id) {
    nativeUpdate("UPDATE " + table + " SET created = created - INTERVAL '1 day' WHERE "
        + keyColumn + " = :id", id);
  }

  /** Simulates a role created before ETP-5502, when the owner column did not exist. */
  private void clearOwner(String roleId) {
    nativeUpdate("UPDATE ad_role SET em_etgo_personal_owner_id = NULL WHERE ad_role_id = :id",
        roleId);
  }

  private void renameRole(String roleId, String name) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession()
        .createNativeQuery("UPDATE ad_role SET name = :name WHERE ad_role_id = :id")
        .setParameter("name", name)
        .setParameter("id", roleId)
        .executeUpdate();
    OBDal.getInstance().getSession().clear();
    reloadFixtures();
  }

  /** Leaves {@code user}'s role dormant: no {@code AD_User_Roles} row, no default role. */
  private void unassign(User user) {
    String userId = user.getId();
    nativeUpdate("DELETE FROM ad_user_roles WHERE ad_user_id = :id", userId);
    nativeUpdate("UPDATE ad_user SET default_ad_role_id = NULL, em_smfsws_default_ws_role_id = NULL"
        + " WHERE ad_user_id = :id", userId);
  }

  private void nativeUpdate(String sql, String id) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().createNativeQuery(sql).setParameter("id", id)
        .executeUpdate();
    OBDal.getInstance().getSession().clear();
    reloadFixtures();
  }

  /** The session was cleared: re-attach the fixtures the helpers keep references to. */
  private void reloadFixtures() {
    caller = OBDal.getInstance().get(User.class, caller.getId());
    adminRole = OBDal.getInstance().get(Role.class, adminRole.getId());
  }

  private Role findClientAdminRole() {
    for (Role role : OBDal.getInstance().createCriteria(Role.class).list()) {
      if (Boolean.TRUE.equals(role.isClientAdmin()) && Boolean.TRUE.equals(role.isActive())
          && TEST_CLIENT_ID.equals(role.getClient().getId())) {
        return role;
      }
    }
    throw new IllegalStateException("The test client has no active client-admin role");
  }

  private static String personalName(String base, int n) {
    return PersonalRoleAccessProvisioningService.personalRoleName(base, n);
  }
}
