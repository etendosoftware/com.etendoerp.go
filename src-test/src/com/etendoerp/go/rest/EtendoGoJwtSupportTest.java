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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.roles.UserRoleCompositionService;
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

    @Mock private Session session;
    @Mock private NativeQuery<Object[]> query;

    @Test
    @DisplayName("returns empty roleArray when user has no roles")
    void emptyRoles() throws JSONException {
      mockRoleListQuery(Collections.emptyList());

      EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

      assertNotNull(data.getRoleArray());
      assertEquals(0, data.getRoleArray().length());
      assertNull(data.getFirstRoleId());
      verify(query).setParameter("userId", "user-id");
    }

    @Test
    @DisplayName("loads roles and organizations through one native SQL query")
    void loadsRolesAndOrganizations() throws JSONException {
      mockRoleListQuery(Arrays.asList(
          new Object[]{ "role-1", "Admin", "org-1", "Main Org" },
          new Object[]{ "role-1", "Admin", "org-2", "Second Org" },
          new Object[]{ "role-2", "User", null, null }));
      mockUserDefaultRole("unrelated-role");

      try (MockedConstruction<UserRoleCompositionService> composition = mockConstruction(
          UserRoleCompositionService.class, (mock, ctx) ->
              when(mock.getAppliedTemplateRoleIds("user-id")).thenReturn(Collections.emptyList()))) {
        EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

        assertEquals("role-1", data.getFirstRoleId());
        assertEquals(2, data.getRoleArray().length());
        JSONObject firstRole = data.getRoleArray().getJSONObject(0);
        assertEquals("role-1", firstRole.getString("id"));
        assertEquals("Admin", firstRole.getString("name"));
        assertFalse(firstRole.has("effectiveRoleNames"));
        JSONArray orgList = firstRole.getJSONArray("orgList");
        assertEquals(2, orgList.length());
        assertEquals("org-1", orgList.getJSONObject(0).getString("id"));
        assertEquals("Main Org", orgList.getJSONObject(0).getString("name"));
        assertEquals("org-2", orgList.getJSONObject(1).getString("id"));
        assertEquals("Second Org", orgList.getJSONObject(1).getString("name"));
        assertEquals(0, data.getRoleArray().getJSONObject(1).getJSONArray("orgList").length());
        assertFalse(data.getRoleArray().getJSONObject(1).has("effectiveRoleNames"));
        verify(session, times(1)).createNativeQuery(anyString());
        verify(query).setParameter("userId", "user-id");
        verify(query).list();
      }
    }

    @Test
    @DisplayName("ETP-5329: attaches effectiveRoleNames only to the entry matching the user's "
        + "default role")
    void attachesEffectiveRoleNamesToDefaultRoleEntry() throws JSONException {
      mockRoleListQuery(Arrays.asList(
          new Object[]{ "role-1", "Personal - user", "org-1", "Main Org" },
          new Object[]{ "role-2", "Other Role", null, null }));
      // Default role is role-2 (NOT firstRoleId) to prove matching is by id, not by position.
      mockUserDefaultRole("role-2");

      try (MockedConstruction<UserRoleCompositionService> composition = mockConstruction(
          UserRoleCompositionService.class, (mock, ctx) ->
              when(mock.getAppliedTemplateRoleIds("user-id"))
                  .thenReturn(Arrays.asList("tpl-finance", "tpl-sales")))) {
        mockRoleNameLookup(
            new Object[]{ "tpl-finance", "Finance" },
            new Object[]{ "tpl-sales", "Sales" });

        EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

        JSONObject role1 = data.getRoleArray().getJSONObject(0);
        JSONObject role2 = data.getRoleArray().getJSONObject(1);
        assertFalse(role1.has("effectiveRoleNames"));
        assertTrue(role2.has("effectiveRoleNames"));
        JSONArray names = role2.getJSONArray("effectiveRoleNames");
        assertEquals(2, names.length());
        assertEquals("Finance", names.getString(0));
        assertEquals("Sales", names.getString(1));
      }
    }

    @Test
    @DisplayName("ETP-5329: omits effectiveRoleNames when the default role has no composed "
        + "templates")
    void omitsEffectiveRoleNamesWhenNoTemplatesApplied() throws JSONException {
      mockRoleListQuery(Collections.singletonList(
          new Object[]{ "role-1", "Personal - user", null, null }));
      mockUserDefaultRole("role-1");

      try (MockedConstruction<UserRoleCompositionService> composition = mockConstruction(
          UserRoleCompositionService.class, (mock, ctx) ->
              when(mock.getAppliedTemplateRoleIds("user-id")).thenReturn(Collections.emptyList()))) {
        EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

        JSONObject role1 = data.getRoleArray().getJSONObject(0);
        assertFalse(role1.has("effectiveRoleNames"));
      }
    }

    @Test
    @DisplayName("ETP-5329: skips a template role id with no matching active Role instead of "
        + "throwing, keeping the remaining resolved names")
    void skipsUnresolvedTemplateRoleId() throws JSONException {
      mockRoleListQuery(Collections.singletonList(
          new Object[]{ "role-1", "Personal - user", null, null }));
      mockUserDefaultRole("role-1");

      try (MockedConstruction<UserRoleCompositionService> composition = mockConstruction(
          UserRoleCompositionService.class, (mock, ctx) ->
              when(mock.getAppliedTemplateRoleIds("user-id"))
                  .thenReturn(Arrays.asList("tpl-finance", "tpl-deleted")))) {
        // "tpl-deleted" has no matching active Role row (deleted/renamed out from under
        // AD_Role_Inheritance) -> fetchRoleNames simply omits it from namesById.
        mockRoleNameLookup(new Object[]{ "tpl-finance", "Finance" });

        EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

        JSONObject role1 = data.getRoleArray().getJSONObject(0);
        assertTrue(role1.has("effectiveRoleNames"));
        JSONArray names = role1.getJSONArray("effectiveRoleNames");
        assertEquals(1, names.length());
        assertEquals("Finance", names.getString(0));
      }
    }

    @Test
    @DisplayName("wraps native SQL failures in OBException")
    void wrapsSqlFailures() {
      when(obDal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter("userId", "user-id")).thenReturn(query);
      when(query.list()).thenThrow(new RuntimeException("db-error"));

      OBException exception = assertThrows(OBException.class,
          () -> EtendoGoJwtSupport.loadRoleListData("user-id"));

      assertTrue(exception.getMessage().contains("Error loading role list data for user: user-id"));
      // The row query failed before any User/template-name resolution could run.
      verify(obDal, times(0)).get(eq(User.class), anyString());
    }

    private void mockRoleListQuery(List<Object[]> rows) {
      when(obDal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter("userId", "user-id")).thenReturn(query);
      when(query.list()).thenReturn(rows);
    }

    private void mockUserDefaultRole(String defaultRoleId) {
      Role defaultRole = mock(Role.class);
      when(defaultRole.getId()).thenReturn(defaultRoleId);
      User user = mock(User.class);
      when(user.getDefaultRole()).thenReturn(defaultRole);
      when(obDal.get(User.class, "user-id")).thenReturn(user);
    }

    @SuppressWarnings("unchecked")
    private void mockRoleNameLookup(Object[]... idAndName) {
      OBCriteria<Role> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Role.class)).thenReturn(criteria);
      List<Role> roles = new java.util.ArrayList<>();
      for (Object[] entry : idAndName) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn((String) entry[0]);
        when(role.getName()).thenReturn((String) entry[1]);
        roles.add(role);
      }
      when(criteria.list()).thenReturn(roles);
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
    @DisplayName("findStarOrgId returns the star org id, falling back to '0' when absent")
    void starOrganizationHelpers() {
      Organization star = mock(Organization.class);
      when(star.getId()).thenReturn("star-org-id");
      when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(organizationQuery);
      when(organizationQuery.uniqueResult()).thenReturn(star).thenReturn(null);

      assertEquals("star-org-id", EtendoGoJwtSupport.findStarOrgId("client-1"));
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

    @Test
    @DisplayName("ETP-4665: trims the company suffix so the username fits AD_USER.USERNAME(60)")
    void suffixTrimmedToColumnSize() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(mock(User.class));

      // A 49-char email leaves 60 - (49 + 1) = 10 characters for the company suffix,
      // so "extremelylongcompanynamesl" is cut down to "extremelyl".
      String email = "a".repeat(40) + "@test.com";
      assertEquals(49, email.length());

      String username = EtendoGoJwtSupport.buildClientUsername(email, "Extremely Long Company Name SL");

      assertEquals(OnboardingFieldLimits.EMAIL, username.length());
      assertEquals(email + "+extremelyl", username);
    }

    @Test
    @DisplayName("ETP-4665: keeps the email intact when there is no room for any suffix")
    void noRoomForSuffix() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(mock(User.class));

      // The email alone fills the column: appending anything would overflow it.
      String email = "b".repeat(51) + "@test.com";
      assertEquals(OnboardingFieldLimits.EMAIL, email.length());

      assertEquals(email, EtendoGoJwtSupport.buildClientUsername(email, "Acme"));
    }
  }

  @Nested
  @DisplayName("applyClientAdminDisplayName")
  class ApplyClientAdminDisplayName {

    @Mock private OBQuery<User> userQuery;

    @Test
    @DisplayName("sets the display name on the found active user")
    void setsDisplayNameOnFoundUser() {
      User user = mock(User.class);
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(user);

      EtendoGoJwtSupport.applyClientAdminDisplayName("client-user", "Jane Doe");

      verify(user).setName("Jane Doe");
      verify(obDal).save(user);
    }

    @Test
    @DisplayName("is a no-op when fullName is blank")
    void noOpOnBlankFullName() {
      EtendoGoJwtSupport.applyClientAdminDisplayName("client-user", "   ");

      verify(obDal, times(0)).createQuery(eq(User.class), anyString());
      verify(obDal, times(0)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("is a no-op when fullName is null")
    void noOpOnNullFullName() {
      EtendoGoJwtSupport.applyClientAdminDisplayName("client-user", null);

      verify(obDal, times(0)).createQuery(eq(User.class), anyString());
      verify(obDal, times(0)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("is a no-op when the user is not found")
    void noOpWhenUserNotFound() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(null);

      EtendoGoJwtSupport.applyClientAdminDisplayName("missing-user", "Jane Doe");

      verify(obDal, times(0)).save(org.mockito.ArgumentMatchers.any());
    }
  }

  @Nested
  @DisplayName("applyClientAdminEmail")
  class ApplyClientAdminEmail {

    @Mock private OBQuery<User> userQuery;

    @Test
    @DisplayName("ETP-5019: sets the email on the found active user")
    void setsEmailOnFoundUser() {
      User user = mock(User.class);
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(user);

      EtendoGoJwtSupport.applyClientAdminEmail("client-user", "founder@example.com");

      verify(user).setEmail("founder@example.com");
      verify(obDal).save(user);
    }

    @Test
    @DisplayName("ETP-5019: is a no-op when email is blank")
    void noOpOnBlankEmail() {
      EtendoGoJwtSupport.applyClientAdminEmail("client-user", "   ");

      verify(obDal, times(0)).createQuery(eq(User.class), anyString());
      verify(obDal, times(0)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("ETP-5019: is a no-op when email is null")
    void noOpOnNullEmail() {
      EtendoGoJwtSupport.applyClientAdminEmail("client-user", null);

      verify(obDal, times(0)).createQuery(eq(User.class), anyString());
      verify(obDal, times(0)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("ETP-5019: is a no-op when the user is not found")
    void noOpWhenUserNotFound() {
      when(obDal.createQuery(eq(User.class), anyString())).thenReturn(userQuery);
      when(userQuery.uniqueResult()).thenReturn(null);

      EtendoGoJwtSupport.applyClientAdminEmail("missing-user", "founder@example.com");

      verify(obDal, times(0)).save(org.mockito.ArgumentMatchers.any());
    }
  }

  @Nested
  @DisplayName("RoleListData")
  class RoleListDataTest {

    @Test
    @DisplayName("getters default to null when constructed with null values")
    void defaultValues() {
      EtendoGoJwtSupport.RoleListData data = new EtendoGoJwtSupport.RoleListData(null, null);

      assertNull(data.getFirstRoleId());
      assertNull(data.getRoleArray());
    }

    @Test
    @DisplayName("getters return the values passed to the constructor")
    void assignFields() {
      JSONArray roleArray = new JSONArray();
      EtendoGoJwtSupport.RoleListData data = new EtendoGoJwtSupport.RoleListData("role-abc", roleArray);

      assertEquals("role-abc", data.getFirstRoleId());
      assertNotNull(data.getRoleArray());
      assertEquals(0, data.getRoleArray().length());
    }
  }

  private User mockUser(String userId, boolean active, String username) {
    User user = mock(User.class);
    when(user.isActive()).thenReturn(active);
    when(user.getUsername()).thenReturn(username);
    when(obDal.get(User.class, userId)).thenReturn(user);
    return user;
  }

}
