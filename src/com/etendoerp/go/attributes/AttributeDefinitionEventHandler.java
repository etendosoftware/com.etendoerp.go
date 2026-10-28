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

import javax.enterprise.event.Observes;

import org.hibernate.FlushMode;
import org.hibernate.query.Query;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;

import com.etendoerp.go.schemaforge.data.ETGOAttrDef;
import com.etendoerp.go.schemaforge.data.ETGOAttrDefCategory;

/**
 * Definition-side rules of ETP-5977: a definition targets Product with a supported type, its type and
 * target cannot change once values exist, and a category scope must belong to the definition's client.
 */
public class AttributeDefinitionEventHandler extends EntityPersistenceEventObserver {

  private static Entity[] entities;

  @Override
  protected Entity[] getObservedEntities() {
    if (entities == null) {
      ModelProvider mp = ModelProvider.getInstance();
      entities = new Entity[] { mp.getEntity(ETGOAttrDef.ENTITY_NAME), mp.getEntity(ETGOAttrDefCategory.ENTITY_NAME) };
    }
    return entities;
  }

  public void onNew(@Observes EntityNewEvent event) {
    validate(event, false);
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    validate(event, true);
  }

  private void validate(EntityPersistenceEvent event, boolean update) {
    if (!isValidEvent(event)) {
      return;
    }
    if (ETGOAttrDefCategory.ENTITY_NAME.equals(event.getTargetInstance().getEntity().getName())) {
      validateScope((ETGOAttrDefCategory) event.getTargetInstance());
      return;
    }
    Entity entity = event.getTargetInstance().getEntity();
    Table table = (Table) event.getCurrentState(entity.getProperty(ETGOAttrDef.PROPERTY_TABLE));
    String type = (String) event.getCurrentState(entity.getProperty(ETGOAttrDef.PROPERTY_DATATYPE));
    fail(ProductAttributeRules.checkDefinitionTable(table.getDBTableName()));
    fail(ProductAttributeRules.checkDefinitionType(type));
    if (update) {
      EntityUpdateEvent u = (EntityUpdateEvent) event;
      Property typeProp = entity.getProperty(ETGOAttrDef.PROPERTY_DATATYPE);
      Property tableProp = entity.getProperty(ETGOAttrDef.PROPERTY_TABLE);
      boolean typeChanged = !type.equals(u.getPreviousState(typeProp));
      Object previousTable = u.getPreviousState(tableProp);
      boolean tableChanged = previousTable != null && !table.getId().equals(((Table) previousTable).getId());
      if ((typeChanged || tableChanged) && hasValues(event.getTargetInstance().getId().toString())) {
        throw new OBException("The type and target of an attribute definition cannot change once it has values");
      }
    }
  }

  private static void validateScope(ETGOAttrDefCategory scope) {
    if (!scope.getAttributeDefinition().getClient().getId().equals(scope.getProductCategory().getClient().getId())) {
      throw new OBException("The product category belongs to another client than the attribute definition");
    }
  }

  private static boolean hasValues(String definitionId) {
    Query<Long> q = OBDal.getInstance().getSession()
        .createQuery("select count(v.id) from ETGO_ProductAttrVal v where v.attributeDefinition.id = :d", Long.class);
    q.setParameter("d", definitionId);
    q.setHibernateFlushMode(FlushMode.MANUAL);
    return q.uniqueResult() > 0;
  }

  private static void fail(String message) {
    if (message != null) {
      throw new OBException(message);
    }
  }
}
