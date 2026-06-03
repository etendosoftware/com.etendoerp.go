/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * Unit tests for {@link EtendoGoJwtSupport}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EtendoGoJwtSupportTest {

  @Mock private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
  }

  @Nested
  @DisplayName("requireAccountEmail")
  class RequireAccountEmail {

    @Test
    @DisplayName("returns email when account found by token")
    void returnsEmailWhenFound() {
      Account account = mock(Account.class);
      when(account.getEmail()).thenReturn("user@example.com");

      try (MockedStatic<EtendoGoJwtDalHelper> dalHelper = mockStatic(
          EtendoGoJwtDalHelper.class)) {
        dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
            .thenReturn(account);

        assertEquals("user@example.com", EtendoGoJwtSupport.requireAccountEmail("valid-token"));
      }
    }

    @Test
    @DisplayName("returns null when no account matches token")
    void returnsNullWhenNotFound() {
      try (MockedStatic<EtendoGoJwtDalHelper> dalHelper = mockStatic(
          EtendoGoJwtDalHelper.class)) {
        dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("invalid-token"))
            .thenReturn(null);

        assertNull(EtendoGoJwtSupport.requireAccountEmail("invalid-token"));
      }
    }
  }

  @Nested
  @DisplayName("isEnvironmentUserOwnedByAccount")
  class IsEnvironmentUserOwnedByAccount {

    @Test
    @DisplayName("returns true for exact email match")
    void exactMatch() {
      mockUser("user-id", true, "user@example.com");

      assertTrue(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@example.com", "user-id"));
    }

    @Test
    @DisplayName("returns true for prefix match")
    void prefixMatch() {
      mockUser("user-id", true, "user@example.com+myclient");

      assertTrue(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@example.com", "user-id"));
    }

    @Test
    @DisplayName("returns false when user is missing or inactive")
    void missingOrInactiveUser() {
      when(obDal.get(User.class, "missing")).thenReturn(null);
      mockUser("inactive", false, "user@example.com");

      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@example.com", "missing"));
      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@example.com", "inactive"));
    }

    @Test
    @DisplayName("returns false when username does not match account")
    void noMatch() {
      mockUser("user-id", true, "other@example.com");

      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          "user@example.com", "user-id"));
    }
  }

  @Nested
  @DisplayName("loadRoleListData")
  class LoadRoleListData {

    @Mock private OBQuery<UserRoles> userRoleQuery;
    @Mock private OBQuery<RoleOrganization> roleOrganizationQuery;

    @Test
    @DisplayName("returns empty roleArray when user has no roles")
    void emptyRoles() throws JSONException {
      when(obDal.createQuery(eq(UserRoles.class), anyString())).thenReturn(userRoleQuery);
      when(userRoleQuery.list()).thenReturn(Collections.emptyList());

      EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

      assertNotNull(data.roleArray);
      assertEquals(0, data.roleArray.length());
      assertNull(data.firstRoleId);
      verify(userRoleQuery).setNamedParameter("userId", "user-id");
      verify(userRoleQuery).setFilterOnReadableClients(false);
      verify(userRoleQuery).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("loads roles and organizations through DAL")
    void loadsRolesAndOrganizations() throws JSONException {
      UserRoles firstUserRole = userRole("role-1", "Admin");
      UserRoles secondUserRole = userRole("role-2", "User");
      RoleOrganization firstOrg = roleOrganization("org-1", "Main Org");

      when(obDal.createQuery(eq(UserRoles.class), anyString())).thenReturn(userRoleQuery);
      when(obDal.createQuery(eq(RoleOrganization.class), anyString()))
          .thenReturn(roleOrganizationQuery);
      when(userRoleQuery.list()).thenReturn(List.of(firstUserRole, secondUserRole));
      when(roleOrganizationQuery.list()).thenReturn(List.of(firstOrg), Collections.emptyList());

      EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

      assertEquals("role-1", data.firstRoleId);
      assertEquals(2, data.roleArray.length());
      JSONObject firstRole = data.roleArray.getJSONObject(0);
      assertEquals("role-1", firstRole.getString("id"));
      assertEquals("Admin", firstRole.getString("name"));
      JSONArray orgList = firstRole.getJSONArray("orgList");
      assertEquals(1, orgList.length());
      assertEquals("org-1", orgList.getJSONObject(0).getString("id"));
      assertEquals("Main Org", orgList.getJSONObject(0).getString("name"));
      assertEquals(0, data.roleArray.getJSONObject(1).getJSONArray("orgList").length());
    }
  }

  @Nested
  @DisplayName("client and organization lookup")
  class ClientAndOrganizationLookup {

    @Mock private OBQuery<Client> clientQuery;
    @Mock private OBQuery<Organization> organizationQuery;

    @Test
    @DisplayName("findClientIdByName returns client id when found")
    void findClientByName() {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn("client-123");
      when(obDal.createQuery(eq(Client.class), anyString())).thenReturn(clientQuery);
      when(clientQuery.uniqueResult()).thenReturn(client);

      assertEquals("client-123", EtendoGoJwtSupport.findClientIdByName("My Client"));
      verify(clientQuery).setNamedParameter("clientName", "My Client");
      verify(clientQuery).setFilterOnReadableClients(false);
      verify(clientQuery).setFilterOnReadableOrganization(false);
      verify(clientQuery).setMaxResult(1);
    }

    @Test
    @DisplayName("star organization helpers use DAL queries")
    void starOrganizationHelpers() {
      Organization star = mock(Organization.class);
      when(star.getId()).thenReturn("star-org-id");
      when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(organizationQuery);
      when(organizationQuery.uniqueResult()).thenReturn(star).thenReturn(null);

      assertTrue(EtendoGoJwtSupport.hasStarOrganization("client-1"));
      assertEquals("0", EtendoGoJwtSupport.findStarOrgId("client-1"));
      verify(organizationQuery, times(2)).setNamedParameter("clientId", "client-1");
      verify(organizationQuery, times(2)).setNamedParameter("starOrgValue", "*");
    }

    @Test
    @DisplayName("organizationExists returns whether a non-star organization exists")
    void organizationExists() {
      Organization organization = mock(Organization.class);
      when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(organizationQuery);
      when(organizationQuery.uniqueResult()).thenReturn(organization).thenReturn(null);

      assertTrue(EtendoGoJwtSupport.organizationExists("client-1"));
      assertFalse(EtendoGoJwtSupport.organizationExists("client-1"));
    }
  }

  @Nested
  @DisplayName("buildClientUsername")
  class BuildClientUsername {

    @Mock private OBQuery<User> userQuery;

    @Test
    @DisplayName("returns plain email when no active AD user exists")
    void noExistingUser() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(null);

      assertEquals("user@test.com",
          EtendoGoJwtSupport.buildClientUsername("user@test.com", "Acme Corp"));
    }

    @Test
    @DisplayName("returns email plus sanitized client name when user exists")
    void existingUser() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(mock(User.class));

      assertEquals("user@test.com+my123company",
          EtendoGoJwtSupport.buildClientUsername("user@test.com", "My-123 Company!"));
    }
  }

  @Nested
  @DisplayName("RoleListData")
  class RoleListDataTest {

    @Test
    @DisplayName("fields are accessible and default to null")
    void defaultValues() {
      EtendoGoJwtSupport.RoleListData data = new EtendoGoJwtSupport.RoleListData();

      assertNull(data.firstRoleId);
      assertNull(data.roleArray);
    }

    @Test
    @DisplayName("fields can be assigned")
    void assignFields() {
      EtendoGoJwtSupport.RoleListData data = new EtendoGoJwtSupport.RoleListData();
      data.firstRoleId = "role-abc";
      data.roleArray = new JSONArray();

      assertEquals("role-abc", data.firstRoleId);
      assertNotNull(data.roleArray);
      assertEquals(0, data.roleArray.length());
    }
  }

  private User mockUser(String userId, boolean active, String username) {
    User user = mock(User.class);
    when(user.isActive()).thenReturn(active);
    when(user.getUsername()).thenReturn(username);
    when(obDal.get(User.class, userId)).thenReturn(user);
    return user;
  }

  private static UserRoles userRole(String roleId, String roleName) {
    UserRoles userRole = mock(UserRoles.class);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(roleId);
    when(role.getName()).thenReturn(roleName);
    when(userRole.getRole()).thenReturn(role);
    return userRole;
  }

  private static RoleOrganization roleOrganization(String orgId, String orgName) {
    RoleOrganization roleOrganization = mock(RoleOrganization.class);
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(orgId);
    when(organization.getName()).thenReturn(orgName);
    when(roleOrganization.getOrganization()).thenReturn(organization);
    return roleOrganization;
  }
}
