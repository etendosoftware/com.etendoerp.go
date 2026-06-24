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

package com.etendoerp.go.eventhandler;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.materialmgmt.onhandquantity.StorageDetail;

/**
 * Recalculates em_etgo_valuation on M_Storage_Detail whenever stock quantity changes.
 * Valuation = quantityOnHand × current cost from M_Costing (permanent, active, not expired).
 */
public class StorageDetailValuationHandler extends EntityPersistenceEventObserver {

  private static final Logger LOG = LogManager.getLogger();

  private static final Entity[] ENTITIES = { ModelProvider.getInstance().getEntity(StorageDetail.ENTITY_NAME) };

  static BigDecimal getCurrentCost(Product product) {
    OBCriteria<Costing> crit = OBDal.getInstance().createCriteria(Costing.class);
    crit.add(Restrictions.eq(Costing.PROPERTY_PRODUCT, product));
    crit.add(Restrictions.eq(Costing.PROPERTY_ACTIVE, true));
    crit.add(Restrictions.eq(Costing.PROPERTY_PERMANENT, true));
    crit.add(Restrictions.gt(Costing.PROPERTY_ENDINGDATE, new Date()));
    crit.addOrder(Order.desc(Costing.PROPERTY_STARTINGDATE));
    crit.setMaxResults(1);

    List<Costing> results = crit.list();
    if (results.isEmpty()) {
      return null;
    }
    return results.get(0).getCost();
  }

  @Override
  protected Entity[] getObservedEntities() {
    return ENTITIES;
  }

  public void onNew(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    recalculate((StorageDetail) event.getTargetInstance());
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    recalculate((StorageDetail) event.getTargetInstance());
  }

  private void recalculate(StorageDetail detail) {
    try {
      BigDecimal qty = detail.getQuantityOnHand();
      if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
        detail.setEtgoValuation(BigDecimal.ZERO);
        return;
      }

      BigDecimal cost = getCurrentCost(detail.getProduct());
      if (cost == null) {
        LOG.debug("No current cost found for product {}", detail.getProduct().getId());
        detail.setEtgoValuation(BigDecimal.ZERO);
        return;
      }

      detail.setEtgoValuation(qty.multiply(cost));
    } catch (Exception e) {
      LOG.error("Error recalculating valuation for StorageDetail {}: {}", detail.getId(), e.getMessage(), e);
    }
  }
}
