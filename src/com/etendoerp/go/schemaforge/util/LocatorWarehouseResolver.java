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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Shared helper that maps storage bins ({@code M_Locator}) to their parent warehouse name.
 *
 * <p>Etendo GO always provisions exactly one locator per warehouse, so collapsing a bin
 * identifier (e.g. {@code "AG-0-0-0"}) into the warehouse name (e.g. {@code "Almacen GO"})
 * loses no information and produces a far more user-friendly label.</p>
 *
 * <p>This behavior is generic: every field that is a foreign key to {@code M_Locator}
 * (across all windows) is enriched the same way, both in CRUD GET responses (via
 * {@link NeoLocatorIdentifierHelper}) and in selector responses (via
 * {@link NeoLocatorSelectorHelper}).</p>
 */
public class LocatorWarehouseResolver {

  private static final Logger log = LogManager.getLogger(LocatorWarehouseResolver.class);

  /** AD table name of the storage-bin entity that must be collapsed to its warehouse. */
  public static final String M_LOCATOR_TABLE = "M_Locator";

  private LocatorWarehouseResolver() {
  }

  /**
   * Batch-resolve a collection of locator ids into their parent warehouse names.
   *
   * <p>Runs a single admin-mode DAL query. Blank/null ids are skipped, and locators
   * without a warehouse are left out of the result (so callers keep the original label).</p>
   *
   * @param locatorIds locator (storage bin) ids to resolve
   * @return map of locator id → warehouse name (never {@code null}; empty when nothing resolves)
   */
  public static Map<String, String> resolveNames(Collection<String> locatorIds) {
    Map<String, String> result = new HashMap<>();
    if (locatorIds == null || locatorIds.isEmpty()) {
      return result;
    }
    List<String> cleanIds = new ArrayList<>();
    for (String id : locatorIds) {
      if (id != null && !id.trim().isEmpty()) {
        cleanIds.add(id);
      }
    }
    if (cleanIds.isEmpty()) {
      return result;
    }
    OBContext.setAdminMode();
    try {
      List<Locator> locators = OBDal.getInstance().createCriteria(Locator.class)
          .add(Restrictions.in("id", cleanIds))
          .list();
      for (Locator loc : locators) {
        Warehouse warehouse = loc.getWarehouse();
        if (warehouse == null) {
          continue;
        }
        String name = warehouse.getName();
        if (name == null || name.trim().isEmpty()) {
          name = warehouse.getIdentifier();
        }
        if (name != null && !name.trim().isEmpty()) {
          result.put(loc.getId(), name);
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }

  /**
   * Returns {@code true} when the given AD column is a foreign key targeting {@code M_Locator},
   * regardless of the reference type used (TableDir 19, Search 30 or an OBUISEL selector).
   *
   * <p>The decision is based on the resolved selector target entity's table name — NOT on the
   * reference id or the column name — so it correctly distinguishes real locator FKs from
   * lookalike columns (e.g. {@code M_LocatorTo_ID}, which also targets {@code M_Locator} and is
   * therefore intentionally treated as a locator FK too).</p>
   *
   * <p>Fail-safe: any resolution error returns {@code false} without throwing.</p>
   *
   * @param col the AD column to inspect
   * @return {@code true} if the column resolves to the {@code M_Locator} entity
   */
  public static boolean isLocatorRef(Column col) {
    if (col == null) {
      return false;
    }
    try {
      String refId = NeoSelectorService.getBaseReferenceId(col);
      SelectorMeta meta = NeoSelectorService.resolveTarget(col, refId);
      if (meta == null || meta.entityName == null) {
        return false;
      }
      Entity entity = ModelProvider.getInstance().getEntity(meta.entityName, false);
      return entity != null && M_LOCATOR_TABLE.equals(entity.getTableName());
    } catch (Exception e) {
      log.debug("Could not determine locator reference for column {}: {}",
          col.getDBColumnName(), e.getMessage());
      return false;
    }
  }
}
