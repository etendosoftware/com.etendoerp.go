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
import org.openbravo.model.common.plm.Product;

import com.etendoerp.go.schemaforge.data.ETGOAttrDef;
import com.etendoerp.go.schemaforge.data.ETGOProductAttrVal;

/**
 * Enforces the value-side rules of ETP-5977 on {@code ETGO_ProductAttrVal}: the definition targets
 * Product, belongs to the same client as the product, is active and scoped to the product's
 * category, and the value column matches the definition type. A value whose definition lost its scope
 * or was deactivated stays in the table but cannot be written again.
 */
public class ProductAttributeValueEventHandler extends EntityPersistenceEventObserver {

  private static Entity[] entities;

  @Override
  protected Entity[] getObservedEntities() {
    if (entities == null) {
      entities = new Entity[] { ModelProvider.getInstance().getEntity(ETGOProductAttrVal.ENTITY_NAME) };
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
    Entity entity = event.getTargetInstance().getEntity();
    Product product = (Product) event.getCurrentState(entity.getProperty(ETGOProductAttrVal.PROPERTY_PRODUCT));
    ETGOAttrDef def = (ETGOAttrDef) event.getCurrentState(
        entity.getProperty(ETGOProductAttrVal.PROPERTY_ATTRIBUTEDEFINITION));
    if (update) {
      rejectIfChanged((EntityUpdateEvent) event, entity.getProperty(ETGOProductAttrVal.PROPERTY_PRODUCT));
      rejectIfChanged((EntityUpdateEvent) event,
          entity.getProperty(ETGOProductAttrVal.PROPERTY_ATTRIBUTEDEFINITION));
    }
    fail(ProductAttributeRules.checkDefinitionTable(def.getTable().getDBTableName()));
    if (!def.getClient().getId().equals(product.getClient().getId())) {
      throw new OBException("The attribute definition belongs to another client than the product");
    }
    if (!def.isActive()) {
      throw new OBException("The attribute definition '" + def.getSearchKey() + "' is not active");
    }
    if (!isScopedTo(def, product)) {
      throw new OBException("The attribute '" + def.getSearchKey() + "' does not apply to the category of product "
          + product.getSearchKey());
    }
    fail(ProductAttributeRules.checkValueColumns(def.getDataType(),
        (String) event.getCurrentState(entity.getProperty(ETGOProductAttrVal.PROPERTY_TEXTVALUE)),
        (java.math.BigDecimal) event.getCurrentState(entity.getProperty(ETGOProductAttrVal.PROPERTY_NUMBERVALUE))));
  }

  private static void rejectIfChanged(EntityUpdateEvent event, Property property) {
    Object before = event.getPreviousState(property);
    Object now = event.getCurrentState(property);
    String beforeId = before == null ? null : ((org.openbravo.base.structure.BaseOBObject) before).getId().toString();
    String nowId = now == null ? null : ((org.openbravo.base.structure.BaseOBObject) now).getId().toString();
    if (beforeId != null && !beforeId.equals(nowId)) {
      throw new OBException("The " + property.getName() + " of an attribute value cannot be changed");
    }
  }

  private static boolean isScopedTo(ETGOAttrDef def, Product product) {
    if (product.getProductCategory() == null) {
      return false;
    }
    Query<Long> q = OBDal.getInstance().getSession().createQuery(
        "select count(c.id) from ETGO_AttrDefCategory c where c.attributeDefinition.id = :d"
            + " and c.productCategory.id = :c and c.active = true", Long.class);
    q.setParameter("d", def.getId());
    q.setParameter("c", product.getProductCategory().getId());
    q.setHibernateFlushMode(FlushMode.MANUAL);
    return q.uniqueResult() > 0;
  }

  private static void fail(String message) {
    if (message != null) {
      throw new OBException(message);
    }
  }
}
