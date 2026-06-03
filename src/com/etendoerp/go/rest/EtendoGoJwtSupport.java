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

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleOrganization;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;

final class EtendoGoJwtSupport {

  private static final String STAR_ORG_VALUE = "*";
  private static final String SYSTEM_ORG_ID = "0";

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
    RoleListData data = new RoleListData();
    data.roleArray = new JSONArray();
    OBQuery<UserRoles> query = OBDal.getInstance().createQuery(UserRoles.class,
        "as userRole where userRole.userContact.id = :userId"
            + " and userRole.active = true"
            + " and userRole.role.active = true"
            + " order by userRole.role.creationDate");
    query.setNamedParameter("userId", userId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);

    for (UserRoles userRole : query.list()) {
      Role role = userRole.getRole();
      if (role == null) {
        continue;
      }
      if (data.firstRoleId == null) {
        data.firstRoleId = role.getId();
      }
      data.roleArray.put(buildRoleJson(role));
    }
    return data;
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

  static boolean hasStarOrganization(String clientId) {
    return findStarOrganization(clientId) != null;
  }

  static String buildClientUsername(String accountEmail, String clientName) {
    if (findActiveUserByUsername(accountEmail) == null) {
      return accountEmail;
    }
    String safeClientName = (clientName != null) ? clientName.toLowerCase().replaceAll("[^a-z0-9]", "") : "";
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

  private static JSONObject buildRoleJson(Role role) throws JSONException {
    JSONObject roleObj = new JSONObject();
    roleObj.put("id", role.getId());
    roleObj.put("name", role.getName());
    roleObj.put("orgList", loadOrganizationsForRole(role.getId()));
    return roleObj;
  }

  private static JSONArray loadOrganizationsForRole(String roleId) throws JSONException {
    JSONArray orgArray = new JSONArray();
    OBQuery<RoleOrganization> query = OBDal.getInstance().createQuery(RoleOrganization.class,
        "as roleOrganization where roleOrganization.role.id = :roleId"
            + " and roleOrganization.active = true"
            + " and roleOrganization.organization.active = true"
            + " order by roleOrganization.organization.name");
    query.setNamedParameter("roleId", roleId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);

    for (RoleOrganization roleOrganization : query.list()) {
      Organization organization = roleOrganization.getOrganization();
      if (organization == null) {
        continue;
      }
      JSONObject orgObj = new JSONObject();
      orgObj.put("id", organization.getId());
      orgObj.put("name", organization.getName());
      orgArray.put(orgObj);
    }
    return orgArray;
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
