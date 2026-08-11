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

import javax.enterprise.event.Observes;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.assetmgmt.AssetGroup;

/**
 * Enforces uniqueness of the {@code Name} of an Asset Category (Asset Group) within the same
 * Client.
 *
 * <p>Fires on every {@code A_Asset_Group} create and update. It rejects the operation when
 * another {@code FinancialMgmtAssetGroup} of the SAME client already uses the same name
 * (exact match), regardless of the organization. The check is scoped by client only — it does
 * NOT restrict by organization, so two categories with the same name cannot coexist in
 * different organizations of the same client.
 *
 * <p>Because it observes DAL persistence events, it covers both the Classic AD window (direct
 * OBDal save) and Etendo GO (NEO NeoCrudHandler, which also persists via OBDal).
 */
public class AssetGroupNameUniqueHandler extends EntityPersistenceEventObserver {

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[]{ ModelProvider.getInstance().getEntity(AssetGroup.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onNew(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    validateUniqueName((AssetGroup) event.getTargetInstance());
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    validateUniqueName((AssetGroup) event.getTargetInstance());
  }

  /**
   * Throws {@link OBException} when another asset group of the same client already has the given
   * name. The current record is excluded by id so an update that keeps its own name is allowed.
   */
  private void validateUniqueName(AssetGroup assetGroup) {
    String name = StringUtils.trimToNull(assetGroup.getName());
    if (name == null) {
      return;
    }
    Client client = assetGroup.getClient();
    if (client == null) {
      return;
    }

    OBCriteria<AssetGroup> criteria = OBDal.getInstance().createCriteria(AssetGroup.class);
    criteria.add(Restrictions.eq(AssetGroup.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq(AssetGroup.PROPERTY_NAME, name));
    if (StringUtils.isNotBlank(assetGroup.getId())) {
      criteria.add(Restrictions.ne(AssetGroup.PROPERTY_ID, assetGroup.getId()));
    }
    // Scope is per client, across every organization of that client: do NOT restrict by readable
    // organization. The explicit client restriction above keeps the check within the current
    // client, so the readable-clients filter is left untouched. Active flag is ignored so an
    // inactive duplicate still blocks the name, matching a DB-level unique constraint.
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnActive(false);
    criteria.setMaxResults(1);

    if (criteria.count() > 0) {
      throw new OBException(OBMessageUtils.messageBD("ETGO_AssetGroupNameDuplicate"));
    }
  }
}
