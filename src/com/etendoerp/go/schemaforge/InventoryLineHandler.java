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

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.InventoryCount;
import org.openbravo.model.materialmgmt.transaction.InventoryCountLine;

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
  private static final String BOOK_QTY_FIELD = "bookQuantity";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean isPost = "POST".equalsIgnoreCase(method);
    boolean isPatch = "PATCH".equalsIgnoreCase(method);
    if (!isPost && !isPatch) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      if (isPost) {
        handlePostPreHook(body);
      } else {
        handlePatchPreHook(context, body);
      }
    } catch (Exception e) {
      log.warn("[InventoryLineHandler] Pre-hook error ({}): {}", method, e.getMessage(), e);
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * Callout post-hook: overrides bookQuantity / quantityCount in the callout response with
   * warehouse-scoped on-hand stock. The classic SL_Inventory_Product callout returns
   * non-warehouse-scoped values; this hook mutates the existing {@code updates} section in
   * place so the frontend sees the correct values before the user saves.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    if (context == null || !NeoEndpointType.CALLOUT.equals(context.getEndpointType())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    NeoResponse previous = context.getPreviousResult();
    if (body == null || previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject updates = previous.getBody().optJSONObject("updates");
    if (updates == null) {
      return null;
    }
    if (!updates.has(BOOK_QTY_FIELD) && !updates.has("quantityCount")) {
      return null;
    }
    try {
      String productId = StringUtils.trimToNull(body.optString("value", null));
      if (productId == null) {
        return null;
      }
      JSONObject formState = body.optJSONObject("formState");
      if (formState == null) {
        return null;
      }
      String inventoryId = StringUtils.trimToNull(formState.optString("physInventory", null));
      if (inventoryId == null) {
        inventoryId = StringUtils.trimToNull(formState.optString("id", null));
      }
      if (inventoryId == null) {
        return null;
      }
      LocatorInfo locInfo = resolveDefaultLocatorInfo(inventoryId);
      if (locInfo == null) {
        return null;
      }
      double qty = queryProductStock(locInfo.warehouseId, productId);
      overrideCalloutValue(updates, BOOK_QTY_FIELD, qty);
      overrideCalloutValue(updates, "quantityCount", qty);
      log.debug("[InventoryLineHandler] callout: overrode book/count={} for product={} warehouse={}",
          qty, productId, locInfo.warehouseId);
    } catch (Exception e) {
      log.warn("[InventoryLineHandler] afterCallout error: {}", e.getMessage(), e);
    }
    return null;
  }

  private static void overrideCalloutValue(JSONObject updates, String key, double value)
      throws Exception {
    if (!updates.has(key)) {
      return;
    }
    JSONObject entry = updates.optJSONObject(key);
    if (entry == null) {
      entry = new JSONObject();
    }
    entry.put("value", value);
    updates.put(key, entry);
  }

  /**
   * POST pre-hook: overrides storageBin and bookQuantity with warehouse-scoped values.
   * filterCreateRequest passes readOnly fields through, so injecting bookQuantity here persists.
   */
  private void handlePostPreHook(JSONObject body) throws Exception {
    String parentId = body.optString("parentId", "");
    if (parentId.isEmpty()) {
      return;
    }
    LocatorInfo locInfo = resolveDefaultLocatorInfo(parentId);
    if (locInfo == null) {
      return;
    }
    body.put("storageBin", locInfo.locatorId);
    String productId = resolveProductId(body);
    if (productId != null) {
      double qty = queryProductStock(locInfo.warehouseId, productId);
      body.put(BOOK_QTY_FIELD, qty);
      log.debug("[InventoryLineHandler] POST: storageBin={} bookQuantity={} product={} warehouse={}",
          locInfo.locatorId, qty, productId, locInfo.warehouseId);
    } else {
      log.debug("[InventoryLineHandler] POST: storageBin={} (no product in body)", locInfo.locatorId);
    }
  }

  /**
   * PATCH pre-hook: filterWriteRequest strips bookQuantity (readOnly), so we modify the
   * persistent entity directly. CRUD update applies remaining body fields without touching
   * bookQuantity, and Hibernate flushes our value at session close.
   */
  private void handlePatchPreHook(NeoContext context, JSONObject body) {
    String lineId = context.getRecordId();
    if (lineId == null || lineId.isEmpty()) {
      return;
    }
    InventoryCountLine line = OBDal.getInstance().get(InventoryCountLine.class, lineId);
    if (line == null || line.getPhysInventory() == null) {
      return;
    }
    String productId = resolveProductId(body);
    if (productId == null) {
      if (line.getProduct() == null) {
        return;
      }
      productId = line.getProduct().getId();
    }
    String inventoryId = line.getPhysInventory().getId();
    LocatorInfo locInfo = resolveDefaultLocatorInfo(inventoryId);
    if (locInfo == null) {
      return;
    }
    double qty = queryProductStock(locInfo.warehouseId, productId);
    line.setBookQuantity(BigDecimal.valueOf(qty));
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();
    log.debug("[InventoryLineHandler] PATCH: bookQuantity={} product={} warehouse={}",
        qty, productId, locInfo.warehouseId);
  }

  private static String resolveProductId(JSONObject body) {
    Object productVal = body.opt("product");
    if (productVal instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) productVal).optString("id"));
    }
    if (productVal instanceof String) {
      return StringUtils.trimToNull((String) productVal);
    }
    return null;
  }

  // ── helpers ──────────────────────────────────────────────────────────

  /** Resolved locator and warehouse data for a physical inventory header. */
  public static class LocatorInfo {
    /** AD primary key of the M_Locator record. */
    public final String locatorId;
    /** Search key (value) of the locator. */
    public final String locatorValue;
    /** Display name of the warehouse. */
    public final String warehouseName;
    /** AD primary key of the M_Warehouse record. */
    public final String warehouseId;

    LocatorInfo(String locatorId, String locatorValue, String warehouseName, String warehouseId) {
      this.locatorId = locatorId;
      this.locatorValue = locatorValue;
      this.warehouseName = warehouseName;
      this.warehouseId = warehouseId;
    }
  }

  static double queryProductStock(String warehouseId, String productId) {
    String sql = "SELECT COALESCE(SUM(sd.qtyonhand), 0)"
        + " FROM m_storage_detail sd"
        + " WHERE sd.m_product_id = :productId"
        + "   AND sd.m_locator_id IN ("
        + "     SELECT m_locator_id FROM m_locator"
        + "     WHERE m_warehouse_id = :warehouseId AND isactive = 'Y')";
    NativeQuery<?> nq = OBDal.getInstance().getSession().createNativeQuery(sql);
    nq.setParameter("productId", productId);
    nq.setParameter("warehouseId", warehouseId);
    Object result = nq.uniqueResult();
    return result != null ? ((Number) result).doubleValue() : 0.0;
  }

  /**
   * Returns the default active locator for the warehouse of the given inventory record.
   *
   * @param inventoryId AD primary key of the {@code M_Inventory} record
   * @return resolved {@link LocatorInfo}, or {@code null} if none found
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
