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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns inventory stock valuation data grouped by warehouse.
 */
@Named("inventoryStockReportHandler")
public class InventoryStockReportHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InventoryStockReportHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      JSONObject body = context.getRequestBody() == null ? new JSONObject() : context.getRequestBody();

      List<String> productIds = parseIds(body.optString("M_Product_ID", ""));
      List<String> warehouseIds = parseIds(body.optString("M_Warehouse_ID", ""));

      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      Set<String> orgTree = OBContext.getOBContext()
          .getOrganizationStructureProvider(clientId)
          .getNaturalTree(orgId);

      StringBuilder sql = new StringBuilder("SELECT "
          + "wh.name AS warehouse, "
          + "p.value AS product_search_key, "
          + "p.name AS product_name, "
          + "COALESCE(uom.name, '') AS uom_name, "
          + "SUM(sd.qtyonhand) AS qty_on_hand, "
          + "COALESCE(cost.price, 0) AS unit_cost, "
          + "SUM(sd.qtyonhand) * COALESCE(cost.price, 0) AS total_valuation "
          + "FROM m_storage_detail sd "
          + "JOIN m_product p ON p.m_product_id = sd.m_product_id "
          + "LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id "
          + "JOIN m_locator l ON l.m_locator_id = sd.m_locator_id "
          + "JOIN m_warehouse wh ON wh.m_warehouse_id = l.m_warehouse_id "
          + "LEFT JOIN ( "
          + "  SELECT DISTINCT ON (mc.m_product_id) mc.m_product_id, mc.price "
          + "  FROM m_costing mc "
          + "  WHERE mc.ispermanent = 'Y' "
          + "  ORDER BY mc.m_product_id, mc.datefrom DESC "
          + ") cost ON cost.m_product_id = p.m_product_id "
          + "WHERE p.ad_client_id = :clientId "
          + "  AND sd.ad_org_id IN (:orgIds) ");

      if (!productIds.isEmpty()) {
        sql.append("  AND p.m_product_id IN (")
            .append(buildNamedParams("productId", productIds.size()))
            .append(") ");
      }

      if (!warehouseIds.isEmpty()) {
        sql.append("  AND wh.m_warehouse_id IN (")
            .append(buildNamedParams("warehouseId", warehouseIds.size()))
            .append(") ");
      }

      sql.append(
          "GROUP BY wh.name, p.value, p.name, uom.name, cost.price "
          + "HAVING SUM(sd.qtyonhand) <> 0 "
          + "ORDER BY wh.name, p.value, p.name");

      NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
      query.setParameter("clientId", clientId);
      query.setParameterList("orgIds", orgTree);

      if (!productIds.isEmpty()) {
        for (int i = 0; i < productIds.size(); i++) {
          query.setParameter("productId" + i, productIds.get(i));
        }
      }

      if (!warehouseIds.isEmpty()) {
        for (int i = 0; i < warehouseIds.size(); i++) {
          query.setParameter("warehouseId" + i, warehouseIds.get(i));
        }
      }

      List<Object[]> rows = query.list();

      JSONArray data = new JSONArray();
      for (Object[] row : rows) {
        JSONObject item = new JSONObject();
        item.put("warehouse", row[0]);
        item.put("productSearchKey", row[1]);
        item.put("product", row[2]);
        item.put("uom", row[3]);
        item.put("qtyOnHand", toBigDecimal(row[4]));
        item.put("unitCost", toBigDecimal(row[5]));
        item.put("totalValuation", toBigDecimal(row[6]));
        data.put(item);
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error executing inventory stock report", e);
      return NeoResponse.error(500, "Inventory stock report failed: " + e.getMessage());
    }
  }

  private static List<String> parseIds(String rawValue) {
    if (StringUtils.isBlank(rawValue) || "null".equalsIgnoreCase(rawValue)) {
      return java.util.Collections.emptyList();
    }
    return java.util.Arrays.stream(rawValue.split(","))
        .map(String::trim)
        .filter(StringUtils::isNotBlank)
        .toList();
  }

  private static String buildNamedParams(String prefix, int size) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(":").append(prefix).append(i);
    }
    return sb.toString();
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(String.valueOf(value));
  }
}
