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
package com.etendoerp.go.accounts;

import java.util.Objects;

import javax.enterprise.event.Observes;

import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.Group;

/**
 * ETP-6223 — keeps the groups edited in Classic (<i>Account Groups</i> window) usable by the
 * signup API, which addresses a group by its key ({@code ETGO_GROUP.VALUE}):
 * <ul>
 * <li>a new key must follow the same rule as the API ({@link AccountGroupService#isValidKey});</li>
 * <li>a key never changes once the group exists: callers (and, later, Datadog) refer to it;</li>
 * <li>a group shipped with a module (such as {@code beta}) cannot be deleted.</li>
 * </ul>
 */
public class AccountGroupKeyHandler extends EntityPersistenceEventObserver {

  static final String MSG_INVALID_KEY = "ETGO_GroupKeyInvalid";
  static final String MSG_KEY_IMMUTABLE = "ETGO_GroupKeyImmutable";
  static final String MSG_SHIPPED_NOT_DELETABLE = "ETGO_GroupShippedNotDeletable";

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] { ModelProvider.getInstance().getEntity(Group.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  /**
   * Rejects a new group whose key does not follow the group key rule.
   *
   * @param event the insert event
   */
  public void onNew(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Group group = (Group) event.getTargetInstance();
    if (!AccountGroupService.isValidKey(group.getSearchKey())) {
      throw new OBException(OBMessageUtils.messageBD(MSG_INVALID_KEY));
    }
  }

  /**
   * Rejects any change of the key of an existing group.
   *
   * @param event the update event
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Property key = resolveEntities()[0].getProperty(Group.PROPERTY_SEARCHKEY);
    if (!Objects.equals(event.getPreviousState(key), event.getCurrentState(key))) {
      throw new OBException(OBMessageUtils.messageBD(MSG_KEY_IMMUTABLE));
    }
  }

  /**
   * Rejects deleting a group that a module ships.
   *
   * @param event the delete event
   */
  public void onDelete(@Observes EntityDeleteEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Group group = (Group) event.getTargetInstance();
    if (group.getModule() != null) {
      throw new OBException(OBMessageUtils.messageBD(MSG_SHIPPED_NOT_DELETABLE));
    }
  }
}
