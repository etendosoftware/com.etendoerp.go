/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.Objects;

import javax.enterprise.event.Observes;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.model.common.businesspartner.BusinessPartner;

/**
 * Keeps {@code Name} (Razón Social / Commercial Name) in sync when a person-type
 * Business Partner's first or last name changes.
 *
 * <p>Fires on every {@code C_BPartner} UPDATE. When {@code EM_Etgo_Isperson = true} and at
 * least one of {@code EM_Etgo_Firstname} / {@code EM_Etgo_Lastname} has changed, it rebuilds
 * {@code Name} as {@code firstname + " " + lastname}.
 *
 * <p>Covers both Classic (direct OBDal save) and Etendo GO (NEO NeoCrudHandler, which also
 * persists via OBDal).
 *
 * <p>Does not interfere with {@link BusinessPartnerHandler}, which only derives {@code Name}
 * on POST (new records with a blank name).
 */
public class ContactNameSyncHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(ContactNameSyncHandler.class);

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[]{
          ModelProvider.getInstance().getEntity(BusinessPartner.ENTITY_NAME)
      };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Entity bpEntity = event.getTargetInstance().getEntity();

    Property isPersonProp  = bpEntity.getPropertyByColumnName("EM_ETGO_ISPERSON");
    Property firstnameProp = bpEntity.getPropertyByColumnName("EM_ETGO_FIRSTNAME");
    Property lastnameProp  = bpEntity.getPropertyByColumnName("EM_ETGO_LASTNAME");
    Property nameProp      = bpEntity.getPropertyByColumnName("NAME");

    if (isPersonProp == null || firstnameProp == null || lastnameProp == null || nameProp == null) {
      return;
    }

    Object isPerson = event.getCurrentState(isPersonProp);
    if (!Boolean.TRUE.equals(isPerson)) {
      return;
    }

    Object prevFirst = event.getPreviousState(firstnameProp);
    Object currFirst = event.getCurrentState(firstnameProp);
    Object prevLast  = event.getPreviousState(lastnameProp);
    Object currLast  = event.getCurrentState(lastnameProp);

    if (Objects.equals(prevFirst, currFirst) && Objects.equals(prevLast, currLast)) {
      return;
    }

    String fn = StringUtils.trimToEmpty((String) currFirst);
    String ln = StringUtils.trimToEmpty((String) currLast);
    String fullName = (fn + " " + ln).trim().replaceAll("\\s{2,}", " ");

    if (StringUtils.isNotBlank(fullName)) {
      log.debug("ContactNameSyncHandler: syncing name to '{}' for BP {}",
          fullName, event.getTargetInstance().getId());
      event.setCurrentState(nameProp, fullName);
    }
  }
}
