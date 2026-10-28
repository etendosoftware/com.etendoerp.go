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
package com.etendoerp.go.supportaccess;

import javax.enterprise.event.Observes;

import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;

/**
 * ETP-5351 (L2) — cuts an operator's open support accesses the moment they lose the right to hold
 * them: the AD_User is deactivated or deleted, the "Soporte Etendo GO" role is removed or
 * deactivated on the user, or the role itself is deactivated. Without it a session outlives the
 * change until its expiry (up to 480 minutes).
 *
 * <p>Runs inside the saving transaction: if Classic rolls the save back the sessions stay revoked,
 * which fails safe. Changes made outside the DAL (direct SQL, a modulescript) are not seen; the
 * operator-role check at {@code issue} still stops new accesses.</p>
 */
public class SupportOperatorRevocationObserver extends EntityPersistenceEventObserver {

  private static final Entity[] ENTITIES = {
      ModelProvider.getInstance().getEntity(User.ENTITY_NAME),
      ModelProvider.getInstance().getEntity(UserRoles.ENTITY_NAME),
      ModelProvider.getInstance().getEntity(Role.ENTITY_NAME) };

  @Override
  protected Entity[] getObservedEntities() {
    return ENTITIES;
  }

  /**
   * Reacts to a deactivation.
   *
   * @param event the persistence event
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event) || !turnedInactive(event)) {
      return;
    }
    revoke(event.getTargetInstance());
  }

  /**
   * Reacts to a deletion.
   *
   * @param event the persistence event
   */
  public void onDelete(@Observes EntityDeleteEvent event) {
    if (isValidEvent(event)) {
      revoke(event.getTargetInstance());
    }
  }

  private boolean turnedInactive(EntityUpdateEvent event) {
    Property active = event.getTargetInstance().getEntity().getProperty(User.PROPERTY_ACTIVE);
    return Boolean.TRUE.equals(event.getPreviousState(active))
        && Boolean.FALSE.equals(event.getCurrentState(active));
  }

  private void revoke(BaseOBObject target) {
    String operatorId;
    if (target instanceof User) {
      operatorId = (String) target.getId();
    } else if (target instanceof UserRoles) {
      UserRoles userRole = (UserRoles) target;
      if (!SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID.equals(userRole.getRole().getId())) {
        return;
      }
      operatorId = userRole.getUserContact().getId();
    } else if (SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID.equals(target.getId())) {
      operatorId = null; // the role itself: every operator
    } else {
      return;
    }
    // Outside a request (background process, import) there may be no user: revoke anyway.
    OBContext context = OBContext.getOBContext();
    String byUserId = context == null || context.getUser() == null ? null
        : context.getUser().getId();
    new SupportAccessService().revokeAllOf(operatorId, byUserId);
  }
}
