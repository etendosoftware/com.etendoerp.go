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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * ETP-5351 (T5) — in a support session the tenant's "Soporte Etendo" user cannot delete,
 * deactivate or change the role of the owner or another admin. ETP-5351 (T6) — the support user
 * is invisible to the tenant: never in a user list, never counted as one of its admins. No
 * database: the DAL and {@link OwnerSupport} are mocked.
 */
public class UserRoleAssignmentHandlerSupportTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);
  private static final String TARGET = "TARGET00000000000000000000000001";
  private static final String TENANT_ADMIN = "ADMIN000000000000000000000000002";
  private static final String METHOD_GET = "GET";
  private static final String EXPECTED_PREDICATE = "e.id <> '" + SUPPORT_USER + "'";

  private static OBContext supportContext() {
    User acting = mock(User.class);
    when(acting.getId()).thenReturn(SUPPORT_USER);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(acting);
    when(context.getCurrentClient()).thenReturn(client);
    return context;
  }

  private static User target(boolean admin) {
    Role role = mock(Role.class);
    when(role.isClientAdmin()).thenReturn(admin);
    User user = mock(User.class);
    when(user.getId()).thenReturn(TARGET);
    when(user.getDefaultRole()).thenReturn(role);
    List<UserRoles> none = Collections.emptyList();
    when(user.getADUserRolesList()).thenReturn(none);
    return user;
  }

  private static NeoResponse run(String method, JSONObject body, User targetUser,
      boolean owner) {
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, TARGET)).thenReturn(targetUser);
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId(TARGET)
        .requestBody(body)
        .obContext(supportContext())
        .build();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OwnerSupport> ownerSupport = mockStatic(OwnerSupport.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      ownerSupport.when(() -> OwnerSupport.isOwner(TARGET)).thenReturn(owner);
      return new UserRoleAssignmentHandler().handle(context);
    }
  }

  @Test
  public void supportCannotDeleteTheOwner() {
    assertEquals(403, run("DELETE", null, target(false), true).getHttpStatus());
  }

  @Test
  public void supportCannotDeleteAnotherAdmin() {
    assertEquals(403, run("DELETE", null, target(true), false).getHttpStatus());
  }

  @Test
  public void supportCannotDeactivateAnAdmin() throws Exception {
    NeoResponse response = run("PATCH", new JSONObject().put("active", false), target(true),
        false);
    assertEquals(403, response.getHttpStatus());
  }

  @Test
  public void supportCannotChangeTheOwnersRole() throws Exception {
    NeoResponse response = run("PUT", new JSONObject().put("defaultRole", "OTHER_ROLE"),
        target(false), true);
    assertEquals(403, response.getHttpStatus());
  }

  // ── T6: the support user is not one of the tenant's admins ─────────────

  private static OBContext tenantAdminContext() {
    User acting = mock(User.class);
    when(acting.getId()).thenReturn(TENANT_ADMIN);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(acting);
    when(context.getCurrentClient()).thenReturn(client);
    return context;
  }

  private static UserRoles adminRow(String userId) {
    User assignee = mock(User.class);
    when(assignee.getId()).thenReturn(userId);
    UserRoles row = mock(UserRoles.class);
    when(row.getUserContact()).thenReturn(assignee);
    return row;
  }

  /**
   * The tenant has one real admin (the target) plus the support user, both with an active admin
   * role. Counting the support user would say "two admins" and let the real one go.
   */
  @SuppressWarnings("unchecked")
  private static NeoResponse runAgainstLastRealAdmin(String method, JSONObject body) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    User targetUser = mock(User.class);
    when(targetUser.getId()).thenReturn(TARGET);
    when(targetUser.getClient()).thenReturn(client);
    List<UserRoles> activeAdminRows = Arrays.asList(adminRow(TARGET), adminRow(SUPPORT_USER));
    OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
    when(criteria.list()).thenReturn(activeAdminRows);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, TARGET)).thenReturn(targetUser);
    when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .recordId(TARGET)
        .requestBody(body)
        .obContext(tenantAdminContext())
        .build();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<OwnerSupport> ownerSupport = mockStatic(OwnerSupport.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      ownerSupport.when(() -> OwnerSupport.isOwner(TARGET)).thenReturn(false);
      return new UserRoleAssignmentHandler().handle(context);
    }
  }

  @Test
  public void supportUserDoesNotMakeTheLastRealAdminDeletable() {
    NeoResponse response = runAgainstLastRealAdmin("DELETE", null);

    assertEquals(400, response.getHttpStatus());
    assertTrue(String.valueOf(response.getBody()).contains("last active administrator"));
  }

  @Test
  public void supportUserDoesNotMakeTheLastRealAdminDeactivatable() throws Exception {
    NeoResponse response = runAgainstLastRealAdmin("PATCH", new JSONObject().put("active", false));

    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void userListReadsHideTheSupportUser() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(METHOD_GET)
        .obContext(tenantAdminContext())
        .build();

    List<String> predicates = new UserRoleAssignmentHandler().readPredicates(context);

    assertEquals(List.of(EXPECTED_PREDICATE), predicates);
  }

  @Test
  public void userListReadsAddNothingWithoutAClient() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(METHOD_GET)
        .obContext(mock(OBContext.class))
        .build();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      assertTrue(new UserRoleAssignmentHandler().readPredicates(context).isEmpty());
    }
  }

  @Test
  public void contactListReadsHideTheSupportUser() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(METHOD_GET)
        .obContext(tenantAdminContext())
        .build();

    assertEquals(List.of(EXPECTED_PREDICATE),
        new ContactHandler().readPredicates(context));
  }
}
