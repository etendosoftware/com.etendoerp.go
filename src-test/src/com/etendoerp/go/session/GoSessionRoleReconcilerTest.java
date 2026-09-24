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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * ETP-5395 — a cookie session must follow the roles its user actually holds: a demoted user must
 * not keep the old role's privileges, and a promoted user must not be left on a role that is gone.
 */
public class GoSessionRoleReconcilerTest {

  private static final String USER = "USER";
  private static final String CLIENT = "CLIENT";
  private static final String ORG = "ORG";
  private static final String WAREHOUSE = "WAREHOUSE";
  private static final String PERSONAL = "PERSONAL_ROLE";
  private static final String ADMIN = "ADMIN_ROLE";
  private static final String OTHER = "OTHER_ROLE";

  private GoSessionStore store;
  private FakeRoleDirectory directory;
  private GoSessionRoleReconciler reconciler;

  @Before
  public void setUp() {
    store = mock(GoSessionStore.class);
    directory = new FakeRoleDirectory();
    reconciler = new GoSessionRoleReconciler(store, directory);
  }

  private static GoSessionRecord session(String roleId) {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setId("SESSION");
    sessionRecord.setUserId(USER);
    sessionRecord.setRoleId(roleId);
    sessionRecord.setCtxClientId(CLIENT);
    sessionRecord.setCtxOrgId(ORG);
    sessionRecord.setWarehouseId(WAREHOUSE);
    return sessionRecord;
  }

  @Test
  public void keepsAStillValidRoleAndWritesNothing() {
    directory.eligible.add(PERSONAL);
    GoSessionRecord sessionRecord = session(PERSONAL);

    assertFalse(reconciler.reconcile(sessionRecord));

    assertEquals(PERSONAL, sessionRecord.getRoleId());
    verify(store, never()).update(any());
  }

  @Test
  public void ignoresASessionWithNoEnvironmentSelected() {
    GoSessionRecord sessionRecord = session(null);

    assertFalse(reconciler.reconcile(sessionRecord));
    assertFalse(reconciler.reconcile(null));

    verify(store, never()).update(any());
  }

  @Test
  public void promotedUserIsReboundToTheNewDefaultRole() {
    // The personal role was replaced by the Admin role: the session's role is no longer held.
    directory.eligible.add(ADMIN);
    directory.defaultRoleId = ADMIN;
    directory.activeRoleIds = List.of(ADMIN);
    directory.orgAccess.add(ADMIN + "/" + ORG);
    GoSessionRecord sessionRecord = session(PERSONAL);

    assertTrue(reconciler.reconcile(sessionRecord));

    assertEquals(ADMIN, sessionRecord.getRoleId());
    assertEquals("the new role can reach the session org, so it is kept", ORG,
        sessionRecord.getCtxOrgId());
    assertEquals(WAREHOUSE, sessionRecord.getWarehouseId());
    verify(store).update(sessionRecord);
  }

  @Test
  public void demotedAdminLosesTheAdminRole() {
    directory.eligible.add(PERSONAL);
    directory.defaultRoleId = PERSONAL;
    directory.activeRoleIds = List.of(PERSONAL);
    directory.orgAccess.add(PERSONAL + "/" + ORG);
    GoSessionRecord sessionRecord = session(ADMIN);

    assertTrue(reconciler.reconcile(sessionRecord));

    assertEquals(PERSONAL, sessionRecord.getRoleId());
    verify(store).update(sessionRecord);
  }

  @Test
  public void fallsBackToTheFirstValidRoleWhenTheDefaultIsNotValid() {
    directory.eligible.add(OTHER);
    directory.defaultRoleId = ADMIN; // stale default, not held any more
    directory.activeRoleIds = List.of(ADMIN, OTHER);
    directory.orgAccess.add(OTHER + "/" + ORG);
    GoSessionRecord sessionRecord = session(PERSONAL);

    assertTrue(reconciler.reconcile(sessionRecord));

    assertEquals(OTHER, sessionRecord.getRoleId());
  }

  @Test
  public void takesTheRoleDefaultOrgWhenTheNewRoleCannotReachTheSessionOrg() {
    directory.eligible.add(ADMIN);
    directory.defaultRoleId = ADMIN;
    directory.activeRoleIds = List.of(ADMIN);
    directory.derived.put(ADMIN, new GoSessionRoleReconciler.RoleContext("OTHER_ORG", "OTHER_WH"));
    GoSessionRecord sessionRecord = session(PERSONAL);

    assertTrue(reconciler.reconcile(sessionRecord));

    assertEquals("OTHER_ORG", sessionRecord.getCtxOrgId());
    assertEquals("OTHER_WH", sessionRecord.getWarehouseId());
  }

  @Test
  public void failsClosedWhenTheUserHoldsNoValidRole() {
    directory.defaultRoleId = ADMIN; // not eligible (e.g. deactivated or another client)
    directory.activeRoleIds = List.of(ADMIN);
    GoSessionRecord sessionRecord = session(PERSONAL);

    assertThrows(SessionRoleRevokedException.class, () -> reconciler.reconcile(sessionRecord));

    assertEquals("nothing is rebound or persisted", PERSONAL, sessionRecord.getRoleId());
    verify(store, never()).update(any());
  }

  /** In-memory role facts; everything not listed is "not eligible" / "no access". */
  private static final class FakeRoleDirectory implements GoSessionRoleReconciler.RoleDirectory {
    private final Set<String> eligible = new HashSet<>();
    private final Set<String> orgAccess = new HashSet<>();
    private final Map<String, GoSessionRoleReconciler.RoleContext> derived = new HashMap<>();
    private String defaultRoleId;
    private List<String> activeRoleIds = List.of();

    @Override
    public boolean isEligible(String userId, String roleId, String clientId) {
      return USER.equals(userId) && CLIENT.equals(clientId) && eligible.contains(roleId);
    }

    @Override
    public String findDefaultRoleId(String userId) {
      return defaultRoleId;
    }

    @Override
    public List<String> findActiveRoleIds(String userId) {
      return activeRoleIds;
    }

    @Override
    public boolean hasOrgAccess(String roleId, String orgId) {
      return orgAccess.contains(roleId + "/" + orgId);
    }

    @Override
    public GoSessionRoleReconciler.RoleContext deriveContext(String userId, String roleId) {
      return derived.get(roleId);
    }
  }
}
