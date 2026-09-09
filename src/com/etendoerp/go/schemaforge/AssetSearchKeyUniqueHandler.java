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
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

/**
 * Enforces uniqueness of the {@code Identificador} ({@code searchKey}) of an Asset within the
 * same Organization.
 *
 * <p>Fires on every {@code A_Asset} create and update. It rejects the operation when another
 * {@code FinancialMgmtAsset} of the SAME organization already uses the same search key (exact
 * match). The scope is per organization — NOT per client like the sibling
 * {@link AssetGroupNameUniqueHandler} — so two assets CAN share the same identifier as long as
 * they belong to different organizations of the same client. Classic Etendo got this for free
 * from the document-number sequence on {@code documentNo}; Etendo Go dropped that field, leaving
 * {@code searchKey} without any uniqueness guarantee until this handler.
 *
 * <p>Because it observes DAL persistence events, it covers both the Classic AD window (direct
 * OBDal save) and Etendo GO (NEO NeoCrudHandler, which also persists via OBDal).
 */
public class AssetSearchKeyUniqueHandler extends EntityPersistenceEventObserver {

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[]{ ModelProvider.getInstance().getEntity(Asset.ENTITY_NAME) };
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
    validateUniqueSearchKey((Asset) event.getTargetInstance());
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    validateUniqueSearchKey((Asset) event.getTargetInstance());
  }

  /**
   * Throws {@link OBException} when another asset of the same organization already has the given
   * search key. The current record is excluded by id so an update that keeps its own search key
   * is allowed.
   */
  private void validateUniqueSearchKey(Asset asset) {
    String searchKey = StringUtils.trimToNull(asset.getSearchKey());
    if (searchKey == null) {
      return;
    }
    Organization organization = asset.getOrganization();
    if (organization == null) {
      return;
    }

    OBCriteria<Asset> criteria = OBDal.getInstance().createCriteria(Asset.class);
    criteria.add(Restrictions.eq(Asset.PROPERTY_ORGANIZATION, organization));
    criteria.add(Restrictions.eq(Asset.PROPERTY_SEARCHKEY, searchKey));
    if (StringUtils.isNotBlank(asset.getId())) {
      criteria.add(Restrictions.ne(Asset.PROPERTY_ID, asset.getId()));
    }
    // Scope is per organization: the explicit organization restriction above already narrows the
    // check to the current org, so the readable-organization filter is left disabled to avoid
    // interfering with it. Active flag is ignored so an inactive duplicate still blocks the
    // search key, matching a DB-level unique constraint.
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnActive(false);
    criteria.setMaxResults(1);

    if (criteria.count() > 0) {
      throw new OBException(OBMessageUtils.messageBD("ETGO_AssetSearchKeyDuplicate"));
    }
  }
}
