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

package com.etendoerp.go.attributes;

import java.util.Arrays;

import javax.enterprise.event.Observes;

import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.ETGOAttrDef;
import com.etendoerp.go.schemaforge.data.ETGOAttrDefCategory;
import com.etendoerp.go.schemaforge.data.ETGOProductAttrVal;

/**
 * Tenant ownership on update and delete for the configurable-attribute entities (ETP-5977, H3).
 *
 * <p>NEO's {@code DELETE /{spec}/{entity}/{id}} reaches core's {@code DefaultJsonDataService#remove},
 * which loads the record with {@code OBDal.get} (no readable-client filter) and removes it: a session
 * of client A deleted a client B record (observed on these entities and on a core one,
 * {@code ProductCategory}). A {@code PUT} that carries the record's {@code updated} token changed a client B
 * record too. Reads by id are filtered by core's queries and are not affected.
 * The guard sits in the DAL, not in a NEO handler, so it also covers the batch and MCP channels and
 * does not depend on whether NEO dispatches a customization for the entity: a REST CRUD request only
 * runs one when the Spec entity has a {@code Java_Qualifier}, which these do not (and must not) have.
 */
public class AttributeTenantWriteGuard extends EntityPersistenceEventObserver {

  private static Entity[] entities;

  @Override
  protected Entity[] getObservedEntities() {
    if (entities == null) {
      ModelProvider mp = ModelProvider.getInstance();
      entities = new Entity[] { mp.getEntity(ETGOAttrDef.ENTITY_NAME), mp.getEntity(ETGOAttrDefCategory.ENTITY_NAME),
          mp.getEntity(ETGOProductAttrVal.ENTITY_NAME) };
    }
    return entities;
  }

  public void onDelete(@Observes EntityDeleteEvent event) {
    check(event);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    check(event);
  }

  private void check(EntityPersistenceEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    String owner = ((ClientEnabled) event.getTargetInstance()).getClient().getId();
    if (!Arrays.asList(OBContext.getOBContext().getReadableClients()).contains(owner)) {
      throw new OBException("Record not found");
    }
  }
}
