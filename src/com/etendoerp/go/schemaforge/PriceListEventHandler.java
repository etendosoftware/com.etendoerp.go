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

package com.etendoerp.go.schemaforge;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListSchema;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Manages the lifecycle of the hidden {@link PriceListVersion} that backs each
 * {@link PriceList} in the simplified Etendo Go interface.
 *
 * <ul>
 *   <li><b>INSERT</b>: auto-creates one {@link PriceListVersion} (and the required
 *       {@link PriceListSchema}) so product prices can be added immediately.</li>
 *   <li><b>UPDATE</b>: keeps the version name in sync with the price list name.</li>
 * </ul>
 *
 * <p>The version is invisible to the user: one price list = one version.
 */
@ApplicationScoped
public class PriceListEventHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(PriceListEventHandler.class);

  private static final Entity[] entities = {
      ModelProvider.getInstance().getEntity(PriceList.ENTITY_NAME)
  };

  @Override
  protected Entity[] getObservedEntities() {
    return entities;
  }

  public void onSave(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    PriceList priceList = (PriceList) event.getTargetInstance();
    try {
      createDefaultVersion(priceList);
    } catch (Exception e) {
      log.error("Failed to auto-create price list version for '{}': {}",
          priceList.getName(), e.getMessage(), e);
    }
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    PriceList priceList = (PriceList) event.getTargetInstance();
    try {
      syncVersionName(priceList);
    } catch (Exception e) {
      log.error("Failed to sync price list version name for '{}': {}",
          priceList.getName(), e.getMessage(), e);
    }
  }

  /**
   * Creates a {@link PriceListSchema} and a {@link PriceListVersion} linked to
   * the given {@link PriceList}. The version name matches the price list name.
   * Does nothing if a version already exists (idempotent guard).
   */
  private void createDefaultVersion(PriceList priceList) {
    if (hasExistingVersion(priceList)) {
      return;
    }

    PriceListSchema schema = OBProvider.getInstance().get(PriceListSchema.class);
    schema.setNewOBObject(true);
    schema.setClient(priceList.getClient());
    schema.setOrganization(priceList.getOrganization());
    schema.setName(priceList.getName() + " Schema");
    OBDal.getInstance().save(schema);

    PriceListVersion version = OBProvider.getInstance().get(PriceListVersion.class);
    version.setNewOBObject(true);
    version.setClient(priceList.getClient());
    version.setOrganization(priceList.getOrganization());
    version.setName(priceList.getName());
    version.setPriceList(priceList);
    version.setPriceListSchema(schema);
    LocalDate firstOfYear = LocalDate.of(Year.now().getValue(), 1, 1);
    version.setValidFromDate(java.sql.Date.valueOf(firstOfYear));
    OBDal.getInstance().save(version);

    log.debug("Auto-created price list version '{}' for price list '{}'",
        version.getName(), priceList.getName());
  }

  /**
   * Keeps all versions of the given {@link PriceList} in sync with its name.
   * In practice there is always exactly one version, but the loop is safe for
   * any number.
   */
  private void syncVersionName(PriceList priceList) {
    List<PriceListVersion> versions = getVersions(priceList);
    for (PriceListVersion version : versions) {
      if (!priceList.getName().equals(version.getName())) {
        version.setName(priceList.getName());
        OBDal.getInstance().save(version);
        log.debug("Synced price list version name to '{}'", priceList.getName());
      }
    }
  }

  private boolean hasExistingVersion(PriceList priceList) {
    return !getVersions(priceList).isEmpty();
  }

  private List<PriceListVersion> getVersions(PriceList priceList) {
    OBCriteria<PriceListVersion> crit = OBDal.getInstance()
        .createCriteria(PriceListVersion.class);
    crit.add(Restrictions.eq(PriceListVersion.PROPERTY_PRICELIST, priceList));
    return crit.list();
  }
}
