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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

import com.etendoerp.go.schemaforge.AbstractSmartDeactivationHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * ETP-5351 — support-session restrictions of {@link UserRoleAssignmentHandler}, kept apart so the
 * handler stays small. Stateless; every method returns a 403/500 response or {@code null} to
 * continue.
 */
final class UserSupportSessionGuard {

  private static final Logger log = LogManager.getLogger(UserSupportSessionGuard.class);

  private UserSupportSessionGuard() {
  }

  /**
   * ETP-5351 (Vigia H1) — a support session may not create a user, nor write the fields that
   * grant or redirect access to the tenant ({@code email}, {@code username}, roles, a new {@code password},
   * {@code active}/{@code locked}/owner flags; see {@link SupportAccessGuard#changesAccessFields})
   * on any user, so it cannot leave persistent access behind. Everything else it edits is unchanged.
   *
   * @param context the request
   * @return a 403 when refused, {@code null} to continue
   */
  static NeoResponse rejectSupportUserManagement(NeoContext context) {
    if (!SupportAccessGuard.isActingSupportUser(context.getObContext())) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean write = "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    if ("POST".equalsIgnoreCase(method)
        || (write && SupportAccessGuard.changesAccessFields(context.getRequestBody(),
            context.getRecordId()))) {
      return NeoResponse.error(403, SupportAccessGuard.MESSAGE_FORBIDDEN);
    }
    return null;
  }

  /**
   * ETP-5351 (T5) — in a support session, the tenant's "Soporte Etendo" user may not delete,
   * deactivate or take the admin role away from the owner or any other admin (it may still do
   * everything else an admin does). Checked before the ordinary guards, which only protect the
   * last real admin (the support user is not counted, T6), not every admin.
   *
   * @param context   the request
   * @param userId    the target {@code AD_User_ID}, may be null
   * @param dangerous whether the request deletes, deactivates or changes the role of the target
   * @return a 403 when refused, a 500 when the check itself failed, {@code null} to continue
   */
  static NeoResponse rejectSupportSessionOnAdmin(NeoContext context, String userId,
      boolean dangerous) {
    OBContext obContext = context.getObContext();
    String actingUserId = obContext != null && obContext.getUser() != null
        ? obContext.getUser().getId() : null;
    String clientId = obContext != null && obContext.getCurrentClient() != null
        ? obContext.getCurrentClient().getId() : null;
    if (!dangerous || userId == null
        || !SupportAccessGuard.isSupportUser(actingUserId, clientId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        User target = OBDal.getInstance().get(User.class, userId);
        if (target != null && (OwnerSupport.isOwner(userId) || holdsClientAdminRole(target))) {
          return NeoResponse.error(403, SupportAccessGuard.MESSAGE_FORBIDDEN);
        }
        return null;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("UserSupportSessionGuard.rejectSupportSessionOnAdmin error for user {}: {}",
          userId, e.getMessage(), e);
      // Fail CLOSED, same reasoning as the other write-path guards in this class.
      return NeoResponse.error(500, "Error validating support restrictions: " + e.getMessage());
    }
  }

  /** Whether an update deactivates the user or touches its role (a demotion path). */
  static boolean isAdminStrippingUpdate(JSONObject requestBody) {
    return requestBody != null
        && (AbstractSmartDeactivationHandler.isExplicitlyDeactivating(requestBody)
            || requestBody.has("defaultRole"));
  }

  /** Whether the user holds an active client-admin role (default role or any assignment). */
  static boolean holdsClientAdminRole(User user) {
    Role defaultRole = user.getDefaultRole();
    if (defaultRole != null && Boolean.TRUE.equals(defaultRole.isClientAdmin())) {
      return true;
    }
    for (UserRoles assignment : user.getADUserRolesList()) {
      if (Boolean.TRUE.equals(assignment.isActive()) && assignment.getRole() != null
          && Boolean.TRUE.equals(assignment.getRole().isClientAdmin())) {
        return true;
      }
    }
    return false;
  }

  /** The record a DELETE touches: the path id, else the query {@code id} (ETP-5195, R3). */
  static String resolveDeleteTarget(NeoContext context) {
    String userId = context.getRecordId();
    if (userId == null && context.getQueryParams() != null) {
      userId = context.getQueryParams().get("id");
    }
    return userId;
  }
}
