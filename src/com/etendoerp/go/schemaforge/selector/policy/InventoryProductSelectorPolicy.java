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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.InventoryLineHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Enriches the Physical Inventory product selector with warehouse-scoped stock data.
 *
 * <p>The generic {@code NeoSelectorService} returns global on-hand quantities across all
 * warehouses. For Physical Inventory lines the user needs the on-hand quantity scoped to the
 * warehouse of the inventory header, because that value is used as the system count
 * ({@code bookQuantity}) for comparison against the user-counted quantity.
 *
 * <p>Activated only when {@code parentId} resolves to a valid {@link InventoryCount} record.
 * For all other product selectors that carry a {@code parentId} (e.g. sales order lines),
 * {@link InventoryLineHandler#resolveDefaultLocatorInfo} returns {@code null} and this policy
 * is a no-op.
 */
public final class InventoryProductSelectorPolicy implements SelectorEnrichmentPolicy {

  private static final Logger log = LogManager.getLogger(InventoryProductSelectorPolicy.class);

  public InventoryProductSelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(SelectorMeta meta, Map<String, String> contextParams) {
    return meta != null
        && meta.entityName != null
        && meta.entityName.startsWith("Product")
        && contextParams != null
        && StringUtils.isNotBlank(contextParams.get("parentId"));
  }

  @Override
  public NeoResponse enrich(NeoResponse response, SelectorMeta meta,
      Map<String, String> contextParams) {
    if (response == null || response.getBody() == null) {
      return response;
    }
    try {
      String parentId = contextParams.get("parentId");
      InventoryLineHandler.LocatorInfo locInfo =
          InventoryLineHandler.resolveDefaultLocatorInfo(parentId);
      if (locInfo == null) {
        return response;
      }

      JSONArray items = response.getBody().optJSONArray("items");
      if (items == null || items.length() == 0) {
        return response;
      }

      List<String> productIds = extractProductIds(items);
      if (productIds.isEmpty()) {
        return response;
      }

      Map<String, Double> stockByProduct = queryWarehouseStock(locInfo.warehouseId, productIds);

      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.getJSONObject(i);
        String productId = item.optString("id");
        double qty = stockByProduct.getOrDefault(productId, 0.0);
        item.put("QTY",  String.valueOf(qty));
        item.put("LOC",  locInfo.locatorId);
        item.put("PQTY", "0");
      }
    } catch (Exception e) {
      log.warn("[InventoryProductSelectorPolicy] Failed to enrich product selector: {}",
          e.getMessage(), e);
    }
    return response;
  }

  private static List<String> extractProductIds(JSONArray items) throws Exception {
    List<String> ids = new ArrayList<>(items.length());
    for (int i = 0; i < items.length(); i++) {
      String id = items.getJSONObject(i).optString("id");
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static Map<String, Double> queryWarehouseStock(String warehouseId,
      List<String> productIds) {
    StringBuilder inClause = new StringBuilder();
    for (int i = 0; i < productIds.size(); i++) {
      if (i > 0) inClause.append(", ");
      inClause.append(":pid").append(i);
    }
    String sql = "SELECT sd.m_product_id, COALESCE(SUM(sd.qtyonhand), 0)"
        + " FROM m_storage_detail sd"
        + " WHERE sd.m_product_id IN (" + inClause + ")"
        + "   AND sd.m_locator_id IN ("
        + "     SELECT m_locator_id FROM m_locator"
        + "     WHERE m_warehouse_id = :warehouseId AND isactive = 'Y')"
        + " GROUP BY sd.m_product_id";

    @SuppressWarnings("rawtypes")
    NativeQuery nq = OBDal.getInstance().getSession().createNativeQuery(sql);
    nq.setParameter("warehouseId", warehouseId);
    for (int i = 0; i < productIds.size(); i++) {
      nq.setParameter("pid" + i, productIds.get(i));
    }

    Map<String, Double> result = new HashMap<>();
    for (Object row : nq.list()) {
      Object[] cols = (Object[]) row;
      result.put(String.valueOf(cols[0]), ((Number) cols[1]).doubleValue());
    }
    return result;
  }
}
