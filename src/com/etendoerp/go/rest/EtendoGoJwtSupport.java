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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;

final class EtendoGoJwtSupport {

  private static final String STAR_ORG_VALUE = "*";
  private static final String SYSTEM_ORG_ID = "0";
  private static final String SQL_FIND_ROLE_LIST_BY_USER =
      "SELECT r.ad_role_id AS role_id, r.name AS role_name, "
          + "o.ad_org_id AS org_id, o.name AS org_name "
          + "FROM ad_user_roles ur "
          + "JOIN ad_role r ON ur.ad_role_id = r.ad_role_id "
          + "LEFT JOIN ad_role_orgaccess roa ON r.ad_role_id = roa.ad_role_id "
          + "AND roa.isactive = 'Y' "
          + "LEFT JOIN ad_org o ON roa.ad_org_id = o.ad_org_id AND o.isactive = 'Y' "
          + "WHERE ur.ad_user_id = :userId AND ur.isactive = 'Y' AND r.isactive = 'Y' "
          + "ORDER BY r.created, o.name";

  private EtendoGoJwtSupport() {
  }

  static String requireAccountEmail(String token) {
    Account account = EtendoGoJwtDalHelper.findActiveAccountByToken(token);
    return account == null ? null : account.getEmail();
  }

  static boolean isEnvironmentUserOwnedByAccount(String accountEmail, String userId) {
    if (accountEmail == null || userId == null) {
      return false;
    }
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null || !Boolean.TRUE.equals(user.isActive())) {
      return false;
    }
    String username = user.getUsername();
    return accountEmail.equals(username)
        || (username != null && username.startsWith(accountEmail + "+"));
  }

  static RoleListData loadRoleListData(String userId) throws JSONException {
    try {
      return buildRoleListData(loadRoleRows(userId));
    } catch (OBException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new OBException("Error loading role list data for user: " + userId, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> loadRoleRows(String userId) {
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(SQL_FIND_ROLE_LIST_BY_USER);
    query.setParameter("userId", userId);
    return query.list();
  }

  private static RoleListData buildRoleListData(List<Object[]> rows) throws JSONException {
    RoleListData data = new RoleListData();
    data.roleArray = new JSONArray();

    Map<String, JSONObject> rolesById = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String roleId = stringValue(row[0]);
      JSONObject roleObj = rolesById.get(roleId);
      if (roleObj == null) {
        roleObj = buildRoleJson(roleId, stringValue(row[1]));
        rolesById.put(roleId, roleObj);
        if (data.firstRoleId == null) {
          data.firstRoleId = roleId;
        }
      }

      String orgId = stringValue(row[2]);
      if (orgId != null) {
        roleObj.getJSONArray("orgList").put(buildOrganizationJson(orgId, stringValue(row[3])));
      }
    }

    for (JSONObject roleObj : rolesById.values()) {
      data.roleArray.put(roleObj);
    }
    return data;
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static String findClientIdByName(String clientName) {
    OBQuery<Client> query = OBDal.getInstance().createQuery(Client.class,
        "as client where client.name = :clientName and client.active = true");
    query.setNamedParameter("clientName", clientName);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    Client client = query.uniqueResult();
    return client == null ? null : client.getId();
  }

  /**
   * Builds the {@code AD_USER.USERNAME} for a tenant admin: the account email, disambiguated with
   * a {@code +company} suffix when that email already owns another environment.
   *
   * <p>The result must fit {@code AD_USER.USERNAME} / {@code AD_USER.NAME} (both NVARCHAR(60)).
   * The email itself is capped at signup, but the suffix can still push the total past 60, so it
   * is trimmed here — the email is kept intact and the company part gives way, since the email is
   * what identifies the account (ETP-4665).
   */
  static String buildClientUsername(String accountEmail, String clientName) {
    if (findActiveUserByUsername(accountEmail) == null) {
      return accountEmail;
    }
    String safeClientName = (clientName != null) ? clientName.toLowerCase().replaceAll("[^a-z0-9]", "") : "";
    int suffixRoom = OnboardingFieldLimits.EMAIL - (accountEmail.length() + 1);
    if (suffixRoom <= 0) {
      // No room for a suffix at all: the email alone fills the column. Returning it unchanged
      // keeps the value storable; the duplicate-username check upstream still guards uniqueness.
      return accountEmail;
    }
    if (safeClientName.length() > suffixRoom) {
      safeClientName = safeClientName.substring(0, suffixRoom);
    }
    return accountEmail + "+" + safeClientName;
  }

  static String findStarOrgId(String clientId) {
    Organization organization = findStarOrganization(clientId);
    return organization == null ? SYSTEM_ORG_ID : organization.getId();
  }

  static boolean organizationExists(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :clientId"
            + " and organization.searchKey <> :starOrgValue"
            + " and organization.active = true");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("starOrgValue", STAR_ORG_VALUE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  private static JSONObject buildRoleJson(String roleId, String roleName) throws JSONException {
    JSONObject roleObj = new JSONObject();
    roleObj.put("id", roleId);
    roleObj.put("name", roleName);
    roleObj.put("orgList", new JSONArray());
    return roleObj;
  }

  private static JSONObject buildOrganizationJson(String orgId, String orgName) throws JSONException {
    JSONObject orgObj = new JSONObject();
    orgObj.put("id", orgId);
    orgObj.put("name", orgName);
    return orgObj;
  }

  /**
   * Sets the display name of the client admin user (looked up by username) to the
   * given full name. No-op when the name is blank or the user is not found. The
   * change is saved on the current DAL transaction (committed by the caller).
   */
  static void applyClientAdminDisplayName(String username, String fullName) {
    if (fullName == null || fullName.isBlank()) {
      return;
    }
    User user = findActiveUserByUsername(username);
    if (user != null) {
      user.setName(fullName);
      OBDal.getInstance().save(user);
    }
  }

  private static User findActiveUserByUsername(String username) {
    OBQuery<User> query = OBDal.getInstance().createQuery(User.class,
        "as user where user.username = :username and user.active = true");
    query.setNamedParameter("username", username);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private static Organization findStarOrganization(String clientId) {
    OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        "as organization where organization.client.id = :clientId"
            + " and organization.searchKey = :starOrgValue"
            + " and organization.active = true");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("starOrgValue", STAR_ORG_VALUE);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  static final class RoleListData {
    String firstRoleId;
    JSONArray roleArray;
  }
}
