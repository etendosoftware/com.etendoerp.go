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
package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

/**
 * Shared helper that keeps {@code AD_User_Roles} at exactly one active row per user — the
 * invariant real login/window-access checks depend on, since they read {@code AD_User_Roles},
 * never {@code AD_User.Default_Ad_Role_ID} (that field is only ever a UI convenience pointer).
 *
 * <p>Extracted from {@code UserRoleAssignmentHandler#syncUserRole} (ETP-4512) so the same
 * "delete existing rows, insert exactly one" logic is not duplicated by {@link
 * com.etendoerp.go.roles.UserRoleCompositionService} (ETP-4852), which needs the identical
 * invariant for a user's per-tenant personal composition role.</p>
 */
public final class UserRoleSyncSupport {

  private static final Logger log = LogManager.getLogger(UserRoleSyncSupport.class);

  private UserRoleSyncSupport() {
    // static helper
  }

  /**
   * Ensures {@code AD_User_Roles} has exactly one active row for {@code user}, pointing at
   * {@code targetRole} — deleting any other row(s) first. A {@code null} {@code targetRole}
   * clears every row, leaving the user role-less.
   *
   * @param user the user whose {@code AD_User_Roles} rows are reconciled
   * @param targetRole the single role the user should end up with, or {@code null} to clear
   */
  public static void syncSingleActiveUserRole(User user, Role targetRole) {
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
      log.info("User {} has no target role; cleared all AD_User_Roles rows.", user.getId());
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
    log.info("Assigned role {} to user {} via AD_User_Roles.", targetRole.getId(), user.getId());
  }
}
