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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.InventoryCount;

/**
 * NeoHandler for the {@code inventoryLine} entity (Physical Inventory lines).
 *
 * <h3>POST (create) — pre-hook</h3>
 * Overrides the storageBin with the default locator for the inventory header's warehouse.
 * The SL_Inventory_Product callout sets storageBin to the product's global stock location,
 * which may belong to a different warehouse; this hook corrects it.
 *
 * <p>Product stock data (product_LOC, product_QTY, product_quantityOnHand, etc.) is
 * returned warehouse-scoped by {@link com.etendoerp.go.schemaforge.selector.policy.InventoryProductSelectorPolicy}
 * via the {@code SelectorEnrichmentPolicy} SPI.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'inventoryLine'} on the
 * ETGO_SF_ENTITY record for the inventoryLine entity in the physical-inventory spec.
 */
@Named("inventoryLine")
public class InventoryLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InventoryLineHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (!"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String parentId = body.optString("parentId", "");
    if (parentId.isEmpty()) {
      return null;
    }
    try {
      LocatorInfo locInfo = resolveDefaultLocatorInfo(parentId);
      if (locInfo != null) {
        body.put("storageBin", locInfo.locatorId);
        log.debug("[InventoryLineHandler] POST: set storageBin={} ({})", locInfo.locatorId, locInfo.locatorValue);
      }
    } catch (Exception e) {
      log.debug("[InventoryLineHandler] Could not override storageBin: {}", e.getMessage());
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  // ── helpers ──────────────────────────────────────────────────────────

  public static class LocatorInfo {
    public final String locatorId;
    public final String locatorValue;
    public final String warehouseName;
    public final String warehouseId;

    LocatorInfo(String locatorId, String locatorValue, String warehouseName, String warehouseId) {
      this.locatorId = locatorId;
      this.locatorValue = locatorValue;
      this.warehouseName = warehouseName;
      this.warehouseId = warehouseId;
    }
  }

  /**
   * Returns the default active locator for the warehouse of the given inventory record.
   */
  public static LocatorInfo resolveDefaultLocatorInfo(String inventoryId) {
    try {
      InventoryCount inventory = OBDal.getInstance().get(InventoryCount.class, inventoryId);
      if (inventory == null || inventory.getWarehouse() == null) {
        return null;
      }

      Warehouse warehouse = inventory.getWarehouse();
      Locator locator = (Locator) OBDal.getInstance().createCriteria(Locator.class)
          .add(Restrictions.eq(Locator.PROPERTY_WAREHOUSE, warehouse))
          .add(Restrictions.eq(Locator.PROPERTY_DEFAULT, true))
          .add(Restrictions.eq(Locator.PROPERTY_ACTIVE, true))
          .addOrder(Order.asc(Locator.PROPERTY_SEARCHKEY))
          .setMaxResults(1)
          .uniqueResult();
      if (locator != null) {
        return new LocatorInfo(locator.getId(), locator.getSearchKey(), warehouse.getName(), warehouse.getId());
      }
    } catch (Exception e) {
      log.debug("[InventoryLineHandler] Could not resolve locator info for inventory {}: {}",
          inventoryId, e.getMessage());
    }
    return null;
  }
}
