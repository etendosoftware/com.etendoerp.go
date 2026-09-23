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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.session;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps a cookie session's role in step with the roles its user actually holds.
 *
 * <p>A cookie session's role is bound once, when the user enters an environment, and every
 * request is then authorized with it. Without this check, a user demoted by an admin kept the
 * old role's privileges until logging out, and a promoted user was left on a role they no longer
 * held (no access at all) — the refresh token endpoint mints a JWT for the new role, but a cookie
 * client never uses that JWT.
 *
 * <p>When the session's role is still valid (active, in the session's client, and the user has an
 * active {@code AD_User_Roles} row for it) nothing is written. Otherwise the session is rebound,
 * in place, to the user's default role if it is valid, else to the first valid role the user
 * holds. The session organization is kept when the new role can access it. The record is updated,
 * not rotated: rotating would reissue the cookie and the CSRF token, and every other open tab's
 * next write would then fail. The trigger is an admin action, not a login, so rotating would not
 * defend against session fixation either.
 */
public class GoSessionRoleReconciler {

  private static final Logger log = LogManager.getLogger(GoSessionRoleReconciler.class);

  /** The organization and warehouse a user gets by default when entering with a role. */
  public record RoleContext(String orgId, String warehouseId) {
  }

  /** The role lookups the reconciler needs, kept apart so the rebind rules are unit-testable. */
  public interface RoleDirectory {

    /**
     * @return {@code true} when {@code roleId} is active, belongs to {@code clientId}, and the
     *     user has an active {@code AD_User_Roles} row for it
     */
    boolean isEligible(String userId, String roleId, String clientId);

    /** @return the user's {@code Default_AD_Role_ID}, or {@code null} */
    String findDefaultRoleId(String userId);

    /** @return the user's active role ids, in the order the role list is shown to the user */
    List<String> findActiveRoleIds(String userId);

    /** @return {@code true} when {@code roleId} has active access to {@code orgId} */
    boolean hasOrgAccess(String roleId, String orgId);

    /** @return the organization and warehouse environment entry would pick for this role */
    RoleContext deriveContext(String userId, String roleId);
  }

  private final GoSessionStore store;
  private final RoleDirectory directory;

  public GoSessionRoleReconciler() {
    this(new JdbcGoSessionStore(), new DalRoleDirectory());
  }

  public GoSessionRoleReconciler(GoSessionStore store, RoleDirectory directory) {
    this.store = store;
    this.directory = directory;
  }

  /**
   * Rebinds the session to a role its user still holds, if its current role was revoked.
   *
   * @param sessionRecord an authenticated session; one with no environment selected is ignored
   * @return {@code true} when the session was rebound (and persisted), {@code false} when its role
   *     was still valid
   * @throws SessionRoleRevokedException when the role was revoked and the user holds no other
   *     valid role
   */
  public boolean reconcile(GoSessionRecord sessionRecord) {
    if (sessionRecord == null || StringUtils.isAnyBlank(sessionRecord.getUserId(),
        sessionRecord.getRoleId(), sessionRecord.getCtxClientId())) {
      return false;
    }
    String userId = sessionRecord.getUserId();
    String clientId = sessionRecord.getCtxClientId();
    String previousRoleId = sessionRecord.getRoleId();
    if (directory.isEligible(userId, previousRoleId, clientId)) {
      return false;
    }

    String replacementRoleId = findReplacementRole(userId, clientId);
    if (replacementRoleId == null) {
      throw new SessionRoleRevokedException(
          "The session role is no longer assigned to the user and no other role is available");
    }
    sessionRecord.setRoleId(replacementRoleId);
    if (!directory.hasOrgAccess(replacementRoleId, sessionRecord.getCtxOrgId())) {
      RoleContext context = directory.deriveContext(userId, replacementRoleId);
      sessionRecord.setCtxOrgId(context.orgId());
      sessionRecord.setWarehouseId(context.warehouseId());
    }
    store.update(sessionRecord);
    log.info("Rebound session {} of user {} from revoked role {} to role {}", sessionRecord.getId(),
        userId, previousRoleId, replacementRoleId);
    return true;
  }

  private String findReplacementRole(String userId, String clientId) {
    List<String> candidates = new ArrayList<>();
    String defaultRoleId = directory.findDefaultRoleId(userId);
    if (defaultRoleId != null) {
      candidates.add(defaultRoleId);
    }
    candidates.addAll(directory.findActiveRoleIds(userId));
    for (String candidate : candidates) {
      if (directory.isEligible(userId, candidate, clientId)) {
        return candidate;
      }
    }
    return null;
  }
}
