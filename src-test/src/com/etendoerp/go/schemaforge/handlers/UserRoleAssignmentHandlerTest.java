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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;

/**
 * Unit tests for {@link UserRoleAssignmentHandler} (ETP-4512).
 *
 * <p>Covers the method/endpoint guard clauses in {@link UserRoleAssignmentHandler#afterHandle},
 * the happy path where a brand-new {@code AD_User_Roles} row is created for a user with none yet,
 * the role-change path (existing row for a different role is removed, exactly one new row
 * exists for the new role), the idempotency guarantee (already in sync -> no writes at all), the
 * role-cleared path ({@code Default_Ad_Role_ID} set to {@code null} -> existing row removed, no
 * new row created), and that {@code GET} requests and unresolvable users never touch
 * {@code AD_User_Roles}. {@link UserRoleAssignmentHandler#handle} is always a pre-hook no-op and
 * is asserted separately.
 */
public class UserRoleAssignmentHandlerTest {

  private static final String USER_ID = "user-001";

  // ─── handle(): always a pre-hook no-op ───────────────────────────────────────

  @Test
  public void handleAlwaysReturnsNull() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").build();
    assertNull(handler.handle(ctx));
  }

  // ─── afterHandle: endpoint/method guards ─────────────────────────────────────

  @Test
  public void afterHandleReturnsNullForNonCrudEndpoint() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleReturnsNullForCrudGetMethod() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleReturnsNullForCrudPostMethod() {
    // POST (create) is explicitly out of scope — getRecordId() isn't reliable there, and
    // admin-initiated user creation is a separate concern (ETP-4602).
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleReturnsNullWhenRecordIdIsNull() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  // ─── afterHandle: happy path — no prior AD_User_Roles row ────────────────────

  @Test
  public void afterHandleCreatesOneRowForUserWithNoExistingRole() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    Client roleClient = mock(Client.class);
    Organization roleOrg = mock(Organization.class);
    Role targetRole = mock(Role.class);
    when(targetRole.getId()).thenReturn("role-finance");
    when(targetRole.getClient()).thenReturn(roleClient);
    when(targetRole.getOrganization()).thenReturn(roleOrg);

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(targetRole);

    UserRoles newRow = mock(UserRoles.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(UserRoles.class)).thenReturn(newRow);

      assertNull(handler.afterHandle(ctx));

      verify(newRow).setNewOBObject(true);
      verify(newRow).setClient(roleClient);
      verify(newRow).setOrganization(roleOrg);
      verify(newRow).setUserContact(user);
      verify(newRow).setRole(targetRole);
      verify(newRow).setRoleAdmin(false);
      verify(obDal).save(newRow);
      // flush() runs once (unconditionally) after the no-op removal loop, once more after the
      // insert — mirrors the role-change path's two flushes, not a single combined one.
      verify(obDal, times(2)).flush();
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: role change — existing row for a different role ───────────

  @Test
  public void afterHandleReplacesExistingRowWhenRoleChanges() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(USER_ID)
        .build();

    Role oldRole = mock(Role.class);
    when(oldRole.getId()).thenReturn("role-sales");

    Role newRole = mock(Role.class);
    when(newRole.getId()).thenReturn("role-finance");
    when(newRole.getClient()).thenReturn(mock(Client.class));
    when(newRole.getOrganization()).thenReturn(mock(Organization.class));

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(newRole);

    UserRoles existingRow = mock(UserRoles.class);
    when(existingRow.getRole()).thenReturn(oldRole);

    UserRoles newRow = mock(UserRoles.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existingRow));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(UserRoles.class)).thenReturn(newRow);

      assertNull(handler.afterHandle(ctx));

      // The old row is removed...
      verify(obDal).remove(existingRow);
      // ...and exactly one new row is created for the new role.
      verify(newRow).setRole(newRole);
      verify(obDal).save(newRow);
      // flush() happens once for the removal batch, once more after the insert.
      verify(obDal, times(2)).flush();
    }
  }

  // ─── afterHandle: idempotency — already in sync ──────────────────────────────

  @Test
  public void afterHandleIsNoOpWhenAlreadyInSync() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    Role role = mock(Role.class);
    when(role.getId()).thenReturn("role-finance");

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(role);

    UserRoles existingRow = mock(UserRoles.class);
    when(existingRow.getRole()).thenReturn(role);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existingRow));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verify(obDal, never()).remove(any());
      verify(obDal, never()).save(any());
      verify(obDal, never()).flush();
      verify(obProvider, never()).get(UserRoles.class);
    }
  }

  // ─── afterHandle: role cleared — Default_Ad_Role_ID set to null ──────────────

  @Test
  public void afterHandleRemovesExistingRowWhenRoleIsCleared() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(null);

    UserRoles existingRow = mock(UserRoles.class);
    when(existingRow.getRole()).thenReturn(mock(Role.class));

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existingRow));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verify(obDal).remove(existingRow);
      verify(obDal, times(1)).flush();
      // No new row is created when the target role is null.
      verify(obProvider, never()).get(UserRoles.class);
      verify(obDal, never()).save(any());
    }
  }

  // ─── afterHandle: user cannot be resolved ────────────────────────────────────

  @Test
  public void afterHandleSkipsWhenUserNotFound() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(null);

      assertNull(handler.afterHandle(ctx));

      verify(obDal, never()).createCriteria(UserRoles.class);
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: failures are swallowed (best-effort side effect) ──────────

  @Test
  public void afterHandleSwallowsExceptionAndStillRestoresContextMode() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }
}
