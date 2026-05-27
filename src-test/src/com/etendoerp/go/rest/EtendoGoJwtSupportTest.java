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
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link EtendoGoJwtSupport}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EtendoGoJwtSupportTest {

  @Mock private Connection conn;
  @Mock private PreparedStatement ps;
  @Mock private ResultSet rs;

  @BeforeEach
  void setUp() throws SQLException {
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
  }

  // ---------------------------------------------------------------------------
  // requireAccountEmail
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("requireAccountEmail")
  class RequireAccountEmail {

    @Test
    @DisplayName("returns email when account found")
    void returnsEmailWhenFound() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("email")).thenReturn("user@example.com");

      String result = EtendoGoJwtSupport.requireAccountEmail(conn, "valid-token");

      assertEquals("user@example.com", result);
    }

    @Test
    @DisplayName("returns null when no account matches token")
    void returnsNullWhenNotFound() throws SQLException {
      when(rs.next()).thenReturn(false);

      String result = EtendoGoJwtSupport.requireAccountEmail(conn, "invalid-token");

      assertNull(result);
    }
  }

  // ---------------------------------------------------------------------------
  // isEnvironmentUserOwnedByAccount
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("isEnvironmentUserOwnedByAccount")
  class IsEnvironmentUserOwnedByAccount {

    @Test
    @DisplayName("returns true for exact email match")
    void exactMatch() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("username")).thenReturn("user@example.com");

      assertTrue(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          conn, "user@example.com", "user-id"));
    }

    @Test
    @DisplayName("returns true for prefix match (email+clientname)")
    void prefixMatch() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("username")).thenReturn("user@example.com+myclient");

      assertTrue(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          conn, "user@example.com", "user-id"));
    }

    @Test
    @DisplayName("returns false when username does not match")
    void noMatch() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("username")).thenReturn("other@example.com");

      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          conn, "user@example.com", "user-id"));
    }

    @Test
    @DisplayName("returns false when no ad_user row found")
    void noUserRow() throws SQLException {
      when(rs.next()).thenReturn(false);

      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          conn, "user@example.com", "user-id"));
    }

    @Test
    @DisplayName("returns false when accountEmail is null")
    void nullAccountEmail() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("username")).thenReturn("user@example.com");

      assertFalse(EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(
          conn, null, "user-id"));
    }
  }

  // ---------------------------------------------------------------------------
  // loadRoleListData
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("loadRoleListData")
  class LoadRoleListData {

    @Test
    @DisplayName("returns empty roleArray when user has no roles")
    void emptyRoles() throws SQLException, JSONException {
      when(rs.next()).thenReturn(false);

      EtendoGoJwtSupport.RoleListData data =
          EtendoGoJwtSupport.loadRoleListData(conn, "user-id");

      assertNotNull(data.roleArray);
      assertEquals(0, data.roleArray.length());
      assertNull(data.firstRoleId);
    }

    @Test
    @DisplayName("loads multiple roles with organisations and sets firstRoleId")
    void multipleRolesWithOrgs() throws SQLException, JSONException {
      // We need separate mocks for the role query and each org sub-query
      PreparedStatement psRoles = ps;
      ResultSet rsRoles = rs;

      PreparedStatement psOrg1 = org.mockito.Mockito.mock(PreparedStatement.class);
      ResultSet rsOrg1 = org.mockito.Mockito.mock(ResultSet.class);

      PreparedStatement psOrg2 = org.mockito.Mockito.mock(PreparedStatement.class);
      ResultSet rsOrg2 = org.mockito.Mockito.mock(ResultSet.class);

      // Role query returns two roles
      when(rsRoles.next()).thenReturn(true, true, false);
      when(rsRoles.getString("ad_role_id")).thenReturn("role-1", "role-2");
      when(rsRoles.getString("role_name")).thenReturn("Admin", "User");

      // Wire org queries: conn.prepareStatement returns different PS per call
      // First call returns psRoles (the role query), second and third return org PS
      when(conn.prepareStatement(anyString()))
          .thenReturn(psRoles, psOrg1, psOrg2);

      // Org query for role-1: one org
      when(psOrg1.executeQuery()).thenReturn(rsOrg1);
      when(rsOrg1.next()).thenReturn(true, false);
      when(rsOrg1.getString("ad_org_id")).thenReturn("org-1");
      when(rsOrg1.getString("org_name")).thenReturn("Main Org");

      // Org query for role-2: no orgs
      when(psOrg2.executeQuery()).thenReturn(rsOrg2);
      when(rsOrg2.next()).thenReturn(false);

      EtendoGoJwtSupport.RoleListData data =
          EtendoGoJwtSupport.loadRoleListData(conn, "user-id");

      assertEquals("role-1", data.firstRoleId);
      assertEquals(2, data.roleArray.length());

      JSONObject firstRole = data.roleArray.getJSONObject(0);
      assertEquals("role-1", firstRole.getString("id"));
      assertEquals("Admin", firstRole.getString("name"));
      JSONArray orgList = firstRole.getJSONArray("orgList");
      assertEquals(1, orgList.length());
      assertEquals("org-1", orgList.getJSONObject(0).getString("id"));
      assertEquals("Main Org", orgList.getJSONObject(0).getString("name"));

      JSONObject secondRole = data.roleArray.getJSONObject(1);
      assertEquals("role-2", secondRole.getString("id"));
      assertEquals("User", secondRole.getString("name"));
      assertEquals(0, secondRole.getJSONArray("orgList").length());
    }

    @Test
    @DisplayName("firstRoleId is set to the first role encountered")
    void firstRoleIdSetCorrectly() throws SQLException, JSONException {
      PreparedStatement psOrg = org.mockito.Mockito.mock(PreparedStatement.class);
      ResultSet rsOrg = org.mockito.Mockito.mock(ResultSet.class);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString("ad_role_id")).thenReturn("only-role");
      when(rs.getString("role_name")).thenReturn("Single");

      when(conn.prepareStatement(anyString())).thenReturn(ps, psOrg);
      when(psOrg.executeQuery()).thenReturn(rsOrg);
      when(rsOrg.next()).thenReturn(false);

      EtendoGoJwtSupport.RoleListData data =
          EtendoGoJwtSupport.loadRoleListData(conn, "user-id");

      assertEquals("only-role", data.firstRoleId);
      assertEquals(1, data.roleArray.length());
    }
  }

  // ---------------------------------------------------------------------------
  // findClientIdByName
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("findClientIdByName")
  class FindClientIdByName {

    @Test
    @DisplayName("returns client id when found")
    void found() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("ad_client_id")).thenReturn("client-123");

      assertEquals("client-123",
          EtendoGoJwtSupport.findClientIdByName(conn, "My Client"));
    }

    @Test
    @DisplayName("returns null when client not found")
    void notFound() throws SQLException {
      when(rs.next()).thenReturn(false);

      assertNull(EtendoGoJwtSupport.findClientIdByName(conn, "Missing"));
    }
  }

  // ---------------------------------------------------------------------------
  // hasStarOrganization
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("hasStarOrganization")
  class HasStarOrganization {

    @Test
    @DisplayName("returns true when star org exists")
    void exists() throws SQLException {
      when(rs.next()).thenReturn(true);

      assertTrue(EtendoGoJwtSupport.hasStarOrganization(conn, "client-1"));
    }

    @Test
    @DisplayName("returns false when no star org")
    void notExists() throws SQLException {
      when(rs.next()).thenReturn(false);

      assertFalse(EtendoGoJwtSupport.hasStarOrganization(conn, "client-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // buildClientUsername
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("buildClientUsername")
  class BuildClientUsername {

    @Test
    @DisplayName("returns plain email when no existing ad_user")
    void noExistingUser() throws SQLException {
      when(rs.next()).thenReturn(false);

      assertEquals("user@test.com",
          EtendoGoJwtSupport.buildClientUsername(conn, "user@test.com", "Acme Corp"));
    }

    @Test
    @DisplayName("returns email+safeClientName when user already exists")
    void existingUser() throws SQLException {
      when(rs.next()).thenReturn(true);

      assertEquals("user@test.com+acmecorp",
          EtendoGoJwtSupport.buildClientUsername(conn, "user@test.com", "Acme Corp"));
    }

    @Test
    @DisplayName("sanitizes client name removing non-alphanumeric chars")
    void sanitizesClientName() throws SQLException {
      when(rs.next()).thenReturn(true);

      assertEquals("user@test.com+my123company",
          EtendoGoJwtSupport.buildClientUsername(conn, "user@test.com", "My-123 Company!"));
    }

    @Test
    @DisplayName("handles null client name")
    void nullClientName() throws SQLException {
      when(rs.next()).thenReturn(true);

      assertEquals("user@test.com+",
          EtendoGoJwtSupport.buildClientUsername(conn, "user@test.com", null));
    }
  }

  // ---------------------------------------------------------------------------
  // findStarOrgId
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("findStarOrgId")
  class FindStarOrgId {

    @Test
    @DisplayName("returns org id when star org found")
    void found() throws SQLException {
      when(rs.next()).thenReturn(true);
      when(rs.getString("ad_org_id")).thenReturn("star-org-id");

      assertEquals("star-org-id",
          EtendoGoJwtSupport.findStarOrgId(conn, "client-1"));
    }

    @Test
    @DisplayName("returns default '0' when no star org")
    void notFound() throws SQLException {
      when(rs.next()).thenReturn(false);

      assertEquals("0",
          EtendoGoJwtSupport.findStarOrgId(conn, "client-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // organizationExists
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("organizationExists")
  class OrganizationExists {

    @Test
    @DisplayName("returns true when non-star org exists")
    void exists() throws SQLException {
      when(rs.next()).thenReturn(true);

      assertTrue(EtendoGoJwtSupport.organizationExists(conn, "client-1"));
    }

    @Test
    @DisplayName("returns false when no non-star org")
    void notExists() throws SQLException {
      when(rs.next()).thenReturn(false);

      assertFalse(EtendoGoJwtSupport.organizationExists(conn, "client-1"));
    }
  }

  // ---------------------------------------------------------------------------
  // RoleListData inner class
  // ---------------------------------------------------------------------------

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
}
