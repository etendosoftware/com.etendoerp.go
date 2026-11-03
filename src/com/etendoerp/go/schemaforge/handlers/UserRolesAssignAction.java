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

package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.roles.RoleWriteConflicts;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-5848: the {@code assignRoles} action of {@code user/user} — the Users window's "Roles
 * asignados" control, for an agent. It reverses ETP-5852's read-only decision (M-201): an agent
 * asked to give a person sales permissions explored for 2.8 M tokens and created the user without
 * them, while the window lets any administrator do it in one save.
 *
 * <p>Same mechanism and the same guarantees as the window's {@code SFAssignUserRoles}: the whole
 * set of template roles is passed to {@link UserRoleCompositionService#assignTemplateRoles(String,
 * List, Role, String)}, which composes the user's personal role, refuses a role that is not a
 * template, refuses the tenant's owner and administrators, keeps the caller inside its own client
 * and takes the per-user lock. Administrators only. Promoting a user to administrator (or back)
 * stays in the app, as do the window's dedicated buttons for it.</p>
 */
final class UserRolesAssignAction {

  private static final Logger log = LogManager.getLogger(UserRolesAssignAction.class);
  /** The request is well formed but the domain refuses it. */
  private static final int SC_UNPROCESSABLE = 422;

  static final String ACTION = "assignRoles";
  static final String PARAM_ROLE_IDS = "templateRoleIds";

  private static final NeoActionContract CONTRACT = NeoActionContract.write(ACTION,
      "Sets the roles of this user, as the Users window's \"Roles asignados\" does: "
          + "templateRoleIds is the WHOLE set the user must end with (the action 'roles' lists "
          + "the user's current ones and every template available). It replaces the set: to add "
          + "one role, send the current ones plus it; an empty list removes them all. Returns "
          + "{userId, roles:[{id, name}], added, removed}. Only administrators may use it. It "
          + "cannot make a user administrator, nor change an administrator's or the owner's "
          + "roles: that is done in the app.",
      NeoActionContract.Param.array(PARAM_ROLE_IDS, NeoActionContract.TYPE_STRING, false,
          "Ids of the template roles (from the 'available' list of the action 'roles') the user "
              + "must end with."))
      .withIdDescription("the user id");

  static final Map<String, NeoActionContract> CONTRACTS =
      Collections.singletonMap(ACTION, CONTRACT);

  private UserRolesAssignAction() {
  }

  /** Whether {@code context} is a call of this action. */
  static boolean isCall(NeoContext context) {
    return NeoEndpointType.ACTION.equals(context.getEndpointType())
        && ACTION.equals(context.getFieldName());
  }

  static NeoResponse serve(NeoContext context) {
    if (!"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    // The caller's role and user BEFORE any admin mode, as the window's webhook resolves them.
    Role caller = NeoAccessHelper.resolveCurrentRole();
    if (caller == null || !NeoAccessHelper.isAdminOrClientAdmin(caller)) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          "Only administrators can assign a user's roles.");
    }
    JSONObject body = context.getRequestBody();
    JSONArray raw = body == null ? null : body.optJSONArray(PARAM_ROLE_IDS);
    if (raw == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "templateRoleIds is required: the whole set of template roles the user must end with "
              + "(an empty list removes them all).");
    }
    String callerUserId = OBContext.getOBContext() != null
        && OBContext.getOBContext().getUser() != null
        ? OBContext.getOBContext().getUser().getId() : null;
    String userId = context.getRecordId();
    try {
      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(userId, distinctIds(raw), caller, callerUserId);
      return NeoResponse.ok(answer(result));
    } catch (Exception e) {
      return failure(e, userId);
    }
  }

  /** The requested ids, trimmed, blanks dropped, each once, in request order. */
  private static List<String> distinctIds(JSONArray raw) {
    List<String> roleIds = new ArrayList<>();
    for (int i = 0; i < raw.length(); i++) {
      String id = StringUtils.trimToNull(raw.optString(i, null));
      if (id != null && !roleIds.contains(id)) {
        roleIds.add(id);
      }
    }
    return roleIds;
  }

  /** A lost race is a 409 to retry, a domain refusal a 422 with its message, anything else a 500. */
  private static NeoResponse failure(Exception e, String userId) {
    if (RoleWriteConflicts.isConcurrencyFailure(e)) {
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Another change to this user's roles is in progress. Read them with the action "
              + "'roles' and try again.");
    }
    if (e instanceof OBException) {
      // A domain refusal: not a template, the owner or an administrator, another client's user.
      // Its message says which.
      return NeoResponse.error(SC_UNPROCESSABLE, e.getMessage());
    }
    log.error("assignRoles failed for user {}: {}", userId, e.getClass().getName());
    return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        "Could not assign the user's roles.");
  }

  /** Template roles are system-level records, so their names are read in admin mode. */
  private static JSONObject answer(UserRoleCompositionService.AssignmentResult result)
      throws Exception {
    OBContext.setAdminMode(true);
    try {
      return new JSONObject().put("userId", result.userId)
          .put("roles", UserRolesReadAction.named(result.appliedTemplateRoleIds))
          .put("added", result.addedCount).put("removed", result.removedCount);
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
