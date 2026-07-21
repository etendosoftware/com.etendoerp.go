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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
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

    @Mock private Session session;
    @Mock private NativeQuery<Object[]> query;

    @Test
    @DisplayName("returns empty roleArray when user has no roles")
    void emptyRoles() throws JSONException {
      mockRoleListQuery(Collections.emptyList());

      EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

      assertNotNull(data.roleArray);
      assertEquals(0, data.roleArray.length());
      assertNull(data.firstRoleId);
      verify(query).setParameter("userId", "user-id");
    }

    @Test
    @DisplayName("loads roles and organizations through one native SQL query")
    void loadsRolesAndOrganizations() throws JSONException {
      mockRoleListQuery(Arrays.asList(
          new Object[]{ "role-1", "Admin", "org-1", "Main Org" },
          new Object[]{ "role-1", "Admin", "org-2", "Second Org" },
          new Object[]{ "role-2", "User", null, null }));

      EtendoGoJwtSupport.RoleListData data = EtendoGoJwtSupport.loadRoleListData("user-id");

      assertEquals("role-1", data.firstRoleId);
      assertEquals(2, data.roleArray.length());
      JSONObject firstRole = data.roleArray.getJSONObject(0);
      assertEquals("role-1", firstRole.getString("id"));
      assertEquals("Admin", firstRole.getString("name"));
      JSONArray orgList = firstRole.getJSONArray("orgList");
      assertEquals(2, orgList.length());
      assertEquals("org-1", orgList.getJSONObject(0).getString("id"));
      assertEquals("Main Org", orgList.getJSONObject(0).getString("name"));
      assertEquals("org-2", orgList.getJSONObject(1).getString("id"));
      assertEquals("Second Org", orgList.getJSONObject(1).getString("name"));
      assertEquals(0, data.roleArray.getJSONObject(1).getJSONArray("orgList").length());
      verify(session, times(1)).createNativeQuery(anyString());
      verify(query).setParameter("userId", "user-id");
      verify(query).list();
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
    }

    private void mockRoleListQuery(java.util.List<Object[]> rows) {
      when(obDal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.setParameter("userId", "user-id")).thenReturn(query);
      when(query.list()).thenReturn(rows);
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

}
