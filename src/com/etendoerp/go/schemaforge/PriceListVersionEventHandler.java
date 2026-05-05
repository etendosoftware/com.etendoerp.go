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

package com.etendoerp.go.schemaforge;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Enforces the simplified Etendo Go invariant: a {@link PriceList} can only have
 * one {@link PriceListVersion}.
 *
 * <p>The first version is auto-created by {@link PriceListEventHandler} when a
 * new price list is saved. This observer rejects any additional insert at the
 * persistence layer, regardless of source (UI, REST API, or programmatic), so
 * the frontend is safe to always operate against {@code versions[0]}.
 */
@ApplicationScoped
public class PriceListVersionEventHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(PriceListVersionEventHandler.class);

  private static final Entity[] entities = {
      ModelProvider.getInstance().getEntity(PriceListVersion.ENTITY_NAME)
  };

  @Override
  protected Entity[] getObservedEntities() {
    return entities;
  }

  public void onSave(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    PriceListVersion newVersion = (PriceListVersion) event.getTargetInstance();
    PriceList priceList = newVersion.getPriceList();
    if (priceList == null) {
      return;
    }
    if (countExistingVersions(priceList) > 0) {
      log.warn("Rejected attempt to create a second PriceListVersion for price list '{}'",
          priceList.getName());
      throw new OBException(
          "A price list can only have one version. Edit the existing version instead.");
    }
  }

  private long countExistingVersions(PriceList priceList) {
    OBCriteria<PriceListVersion> crit = OBDal.getInstance()
        .createCriteria(PriceListVersion.class);
    crit.add(Restrictions.eq(PriceListVersion.PROPERTY_PRICELIST, priceList));
    return crit.count();
  }
}
