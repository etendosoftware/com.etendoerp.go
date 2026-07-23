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

import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code user} spec that keeps {@code AD_User_Roles} in sync with
 * {@code AD_User.Default_Ad_Role_ID} (ETP-4512).
 *
 * <p>The Go SPA's "assign role to user" UX (Configuración &gt; Usuarios) only ever offers a
 * single-role dropdown backed by {@code Default_Ad_Role_ID} — see {@code AssignRoleControl.jsx}
 * in {@code etendo_schema_forge}, which deliberately sources its options from the unrestricted
 * {@code userRoles.role} selector rather than this field's own {@code Default_Ad_Role_ID}
 * selector (which is filtered to roles the user already has, making it useless for assigning a
 * *new* role). Real login/window-access checks read {@code AD_User_Roles}, not
 * {@code Default_Ad_Role_ID}, so this handler is the only place that writes
 * {@code AD_User_Roles} for a {@code user} save, enforcing at most one active row per user:
 * every save deletes any existing row(s) for that user and inserts exactly one new row for the
 * role currently set in {@code Default_Ad_Role_ID} (or leaves the user role-less if that field
 * is cleared).
 *
 * <p>Scoped to {@code PUT}/{@code PATCH} only — editing an existing user. Admin-initiated user
 * <em>creation</em> is a separate concern (ETP-4602, not yet implemented), and
 * {@link NeoContext#getRecordId()} is not reliably populated on {@code POST}.
 *
 * <p>Best-effort, secondary side effect: the {@link User} has already been saved by the time
 * {@link #afterHandle(NeoContext)} runs, so a failure here must never fail the parent request —
 * any exception is logged and swallowed, and {@code null} is returned so the original CRUD
 * response is kept untouched.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2.
 */
@Named("user")
public class UserRoleAssignmentHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(UserRoleAssignmentHandler.class);

  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** No pre-hook behavior: this handler only reacts after the user record is persisted. */
  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  /**
   * Post-hook: on a successful update of the {@code user} entity, ensures {@code AD_User_Roles}
   * has exactly one active row matching the saved {@code Default_Ad_Role_ID} (or zero rows if
   * it was cleared).
   *
   * @return always {@code null} — this is a side effect, never a response replacement.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!METHOD_PUT.equalsIgnoreCase(method) && !METHOD_PATCH.equalsIgnoreCase(method)) {
      return null;
    }
    String userId = context.getRecordId();
    if (userId == null) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        syncUserRole(userId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("UserRoleAssignmentHandler.afterHandle error for user {}: {}", userId,
          e.getMessage(), e);
    }
    return null;
  }

  private void syncUserRole(String userId) {
    User user = OBDal.getInstance().get(User.class, userId);
    if (user == null) {
      return;
    }
    Role targetRole = user.getDefaultRole();

    OBCriteria<UserRoles> criteria = OBDal.getInstance().createCriteria(UserRoles.class);
    criteria.add(Restrictions.eq(UserRoles.PROPERTY_USERCONTACT, user));
    List<UserRoles> existing = criteria.list();

    boolean alreadyInSync = existing.size() == 1 && targetRole != null
        && targetRole.getId().equals(existing.get(0).getRole().getId());
    if (alreadyInSync) {
      return;
    }

    for (UserRoles row : new ArrayList<>(existing)) {
      OBDal.getInstance().remove(row);
    }
    OBDal.getInstance().flush();

    if (targetRole == null) {
      log.info("User {} has no default role set; cleared all AD_User_Roles rows.", userId);
      return;
    }

    UserRoles newRow = OBProvider.getInstance().get(UserRoles.class);
    newRow.setNewOBObject(true);
    newRow.setClient(targetRole.getClient());
    newRow.setOrganization(targetRole.getOrganization());
    newRow.setUserContact(user);
    newRow.setRole(targetRole);
    newRow.setRoleAdmin(false);
    OBDal.getInstance().save(newRow);
    OBDal.getInstance().flush();
    log.info("Assigned role {} to user {} via AD_User_Roles.", targetRole.getId(), userId);
  }
}
