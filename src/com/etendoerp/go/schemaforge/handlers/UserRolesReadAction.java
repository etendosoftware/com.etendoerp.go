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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-5852 (M-201): the read-only {@code roles} action of {@code user/user} — the roles the Users
 * window shows for one user, for an agent. Read only on purpose: assigning roles changes what a
 * person may do, so it stays in the app. Same answer as the window's role chips: the template roles
 * applied to the user ({@link UserRoleCompositionService}) and whether the user is a client
 * administrator. Same gate as the window's own role services: administrators only.
 */
final class UserRolesReadAction {

  private static final Logger log = LogManager.getLogger(UserRolesReadAction.class);

  static final String ACTION = "roles";

  private static final NeoActionContract CONTRACT = NeoActionContract.read(ACTION,
      "Returns the roles this user has, as the Users window shows them: {userId, administrator, "
          + "roles:[{id, name}]} where roles are the template roles applied (Finance, Sales, ...) "
          + "and administrator says whether the user holds the client's admin role. Read only: "
          + "roles are assigned in the app's Users window. Only administrators may read it.")
      .withIdDescription("the user id");

  static final Map<String, NeoActionContract> CONTRACTS =
      Collections.singletonMap(ACTION, CONTRACT);

  private UserRolesReadAction() {
  }

  /** Whether {@code context} is a call of this action. */
  static boolean isCall(NeoContext context) {
    return NeoEndpointType.ACTION.equals(context.getEndpointType())
        && ACTION.equals(context.getFieldName());
  }

  static NeoResponse serve(NeoContext context) {
    Role caller = NeoAccessHelper.resolveCurrentRole();
    if (caller == null || !NeoAccessHelper.isAdminOrClientAdmin(caller)) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Only administrators can read a user's roles.");
    }
    String userId = context.getRecordId();
    OBContext.setAdminMode(true);
    try {
      User user = userId == null ? null : OBDal.getInstance().get(User.class, userId);
      if (user == null || !user.getClient().getId().equals(caller.getClient().getId())) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "User not found");
      }
      List<String> templateIds =
          new UserRoleCompositionService().getAppliedTemplateRoleIds(userId, caller);
      JSONArray roles = new JSONArray();
      for (String id : templateIds) {
        Role role = OBDal.getInstance().get(Role.class, id);
        roles.put(new JSONObject().put("id", id).put("name", role == null ? id : role.getName()));
      }
      JSONObject body = new JSONObject();
      body.put("userId", userId);
      body.put("administrator", holdsClientAdminRole(user));
      body.put("roles", roles);
      return NeoResponse.ok(body);
    } catch (OBException e) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "User not found");
    } catch (JSONException e) {
      log.error("Could not build the roles of user {}", userId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not read the user's roles");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean holdsClientAdminRole(User user) {
    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.createAlias(UserRoles.PROPERTY_ROLE, "role");
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_USERCONTACT, user));
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq("role." + Role.PROPERTY_CLIENTADMIN, true));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }
}
