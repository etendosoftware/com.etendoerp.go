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

package com.etendoerp.go.rest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

final class EtendoGoJwtSupport {

  private static final String FIELD_EMAIL = "email";
  private static final String DB_CLIENT_ID = "ad_client_id";
  private static final String DB_ORG_ID = "ad_org_id";

  private static final String SQL_FIND_ACCOUNT_BY_TOKEN =
      "SELECT etgo_account_id, email, name, session_token, created "
      + "FROM etgo_account WHERE session_token = ? AND isactive = 'Y'";

  private static final String SQL_FIND_USERNAME_BY_AD_USER_ID =
      "SELECT username FROM ad_user WHERE ad_user_id = ? AND isactive = 'Y'";

  private static final String SQL_FIND_ROLE_LIST_BY_USER =
      "SELECT r.ad_role_id, r.name AS role_name "
      + "FROM ad_user_roles ur "
      + "JOIN ad_role r ON ur.ad_role_id = r.ad_role_id "
      + "WHERE ur.ad_user_id = ? AND ur.isactive = 'Y' AND r.isactive = 'Y' "
      + "ORDER BY r.created";

  private static final String SQL_FIND_ORGS_BY_ROLE =
      "SELECT o.ad_org_id, o.name AS org_name "
      + "FROM ad_role_orgaccess roa "
      + "JOIN ad_org o ON roa.ad_org_id = o.ad_org_id "
      + "WHERE roa.ad_role_id = ? AND roa.isactive = 'Y' AND o.isactive = 'Y' "
      + "ORDER BY o.name";

  private static final String SQL_FIND_CLIENT_BY_NAME =
      "SELECT ad_client_id FROM ad_client WHERE name = ?";

  private static final String SQL_FIND_STAR_ORG =
      "SELECT ad_org_id FROM ad_org WHERE ad_client_id = ? AND value = '*'";

  private static final String SQL_FIND_AD_USER_BY_USERNAME =
      "SELECT ad_user_id FROM ad_user WHERE username = ?";

  private static final String SQL_FIND_ORG_BY_CLIENT =
      "SELECT ad_org_id, name FROM ad_org WHERE ad_client_id = ? AND value != '*'";

  private EtendoGoJwtSupport() {
  }

  static String requireAccountEmail(Connection conn, String token) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_ACCOUNT_BY_TOKEN)) {
      ps.setString(1, token);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(FIELD_EMAIL) : null;
      }
    }
  }

  static boolean isEnvironmentUserOwnedByAccount(Connection conn, String accountEmail,
      String userId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_USERNAME_BY_AD_USER_ID)) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return false;
        }
        String username = rs.getString("username");
        return accountEmail != null && (accountEmail.equals(username)
            || (username != null && username.startsWith(accountEmail + "+")));
      }
    }
  }

  static RoleListData loadRoleListData(Connection conn, String userId)
      throws SQLException, JSONException {
    RoleListData data = new RoleListData();
    data.roleArray = new JSONArray();
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_ROLE_LIST_BY_USER)) {
      ps.setString(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String roleId = rs.getString("ad_role_id");
          if (data.firstRoleId == null) {
            data.firstRoleId = roleId;
          }
          data.roleArray.put(buildRoleJson(conn, roleId, rs.getString("role_name")));
        }
      }
    }
    return data;
  }

  static String findClientIdByName(Connection conn, String clientName) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_CLIENT_BY_NAME)) {
      ps.setString(1, clientName);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(DB_CLIENT_ID) : null;
      }
    }
  }

  static boolean hasStarOrganization(Connection conn, String clientId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_STAR_ORG)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  static String buildClientUsername(Connection conn, String accountEmail, String clientName)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_AD_USER_BY_USERNAME)) {
      ps.setString(1, accountEmail);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return accountEmail;
        }
      }
    }
    String safeClientName = (clientName != null) ? clientName.toLowerCase().replaceAll("[^a-z0-9]", "") : "";
    return accountEmail + "+" + safeClientName;
  }

  static String findStarOrgId(Connection conn, String clientId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_STAR_ORG)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(DB_ORG_ID) : "0";
      }
    }
  }

  static boolean organizationExists(Connection conn, String clientId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_ORG_BY_CLIENT)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static JSONObject buildRoleJson(Connection conn, String roleId, String roleName)
      throws SQLException, JSONException {
    JSONObject roleObj = new JSONObject();
    roleObj.put("id", roleId);
    roleObj.put("name", roleName);
    roleObj.put("orgList", loadOrganizationsForRole(conn, roleId));
    return roleObj;
  }

  private static JSONArray loadOrganizationsForRole(Connection conn, String roleId)
      throws SQLException, JSONException {
    JSONArray orgArray = new JSONArray();
    try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_ORGS_BY_ROLE)) {
      ps.setString(1, roleId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject orgObj = new JSONObject();
          orgObj.put("id", rs.getString(DB_ORG_ID));
          orgObj.put("name", rs.getString("org_name"));
          orgArray.put(orgObj);
        }
      }
    }
    return orgArray;
  }

  static final class RoleListData {
    String firstRoleId;
    JSONArray roleArray;
  }
}
