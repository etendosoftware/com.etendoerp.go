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

package com.etendoerp.go.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Warehouse;

/**
 * {@link WarehouseResolver} over the DAL: the first active warehouse of the context's client that
 * belongs to one of its readable organizations. Moved unchanged from
 * {@code NeoServletSupport#findAccessibleWarehouse} (ETP-5455), where only the JWT branch of
 * {@code NeoAuthenticator} could reach it.
 */
public final class DalWarehouseResolver implements WarehouseResolver {

  private static final Logger log = LogManager.getLogger(DalWarehouseResolver.class);
  private static final int MAX_CANDIDATES = 50;

  @Override
  public String findAccessibleWarehouse(OBContext ctx) {
    try {
      OBContext.setAdminMode(true);
      Set<String> readableOrgs = new HashSet<>(Arrays.asList(ctx.getReadableOrganizations()));
      OBCriteria<Warehouse> criteria = OBDal.getInstance().createCriteria(Warehouse.class);
      criteria.add(Restrictions.eq(Warehouse.PROPERTY_CLIENT, ctx.getCurrentClient()));
      criteria.add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true));
      criteria.setMaxResults(MAX_CANDIDATES);
      for (Warehouse warehouse : criteria.list()) {
        String warehouseOrgId = warehouse.getOrganization().getId();
        if (readableOrgs.contains(warehouseOrgId)) {
          log.debug("Resolved accessible warehouse '{}' (org='{}') for user '{}'",
              warehouse.getId(), warehouseOrgId, ctx.getUser().getId());
          return warehouse.getId();
        }
      }
      log.warn("No accessible warehouse found for user '{}' client '{}'",
          ctx.getUser().getId(), ctx.getCurrentClient().getId());
      return null;
    } catch (Exception e) {
      log.error("Error finding accessible warehouse for user '{}': {}",
          ctx.getUser().getId(), e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
