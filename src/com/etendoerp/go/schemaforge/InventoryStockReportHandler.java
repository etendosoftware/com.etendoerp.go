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
import org.codehaus.jettison.json.JSONException;
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
      List<String> categoryIds = parseIds(body.optString("M_Product_Category_ID", ""));
      boolean includeZeroStock = body.optBoolean("includeZeroStock", false);

      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      Set<String> orgTree = OBContext.getOBContext()
          .getOrganizationStructureProvider(clientId)
          .getNaturalTree(orgId);

      // Base is m_product CROSS JOIN m_warehouse (not m_storage_detail): a product
      // that never had a movement in a warehouse has NO m_storage_detail row at all,
      // so starting from storage_detail can never surface it, however the HAVING
      // clause is written. Storage is LEFT JOINed in instead, and the HAVING clause
      // below decides whether a zero/no-stock (product, warehouse) pair is kept.
      // '0' is Etendo's "*" organization — shared master data visible to every org —
      // so both products and warehouses stay visible when they carry it, exactly like
      // the org-tree check everywhere else in this handler.
      StringBuilder sql = new StringBuilder("SELECT "
          + "wh.name AS warehouse, "
          + "COALESCE(pc.name, '') AS category_name, "
          + "p.value AS product_search_key, "
          + "p.name AS product_name, "
          + "COALESCE(uom.name, '') AS uom_name, "
          + "COALESCE(SUM(sd.qtyonhand), 0) AS qty_on_hand, "
          + "COALESCE(cost.cost, 0) AS unit_cost, "
          + "COALESCE(SUM(sd.qtyonhand), 0) * COALESCE(cost.cost, 0) AS total_valuation "
          + "FROM m_product p "
          + "CROSS JOIN m_warehouse wh "
          + "LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id "
          + "LEFT JOIN m_product_category pc ON pc.m_product_category_id = p.m_product_category_id "
          + "LEFT JOIN m_locator l ON l.m_warehouse_id = wh.m_warehouse_id "
          + "LEFT JOIN m_storage_detail sd ON sd.m_locator_id = l.m_locator_id "
          + "  AND sd.m_product_id = p.m_product_id AND sd.ad_org_id IN (:orgIds) "
          + "LEFT JOIN ( "
          // m_costing.price is the SPECIFIC transaction's own price — NOT the current
          // weighted-average cost Classic shows (Costing tab "Cost" column, Warehouse
          // window "Coste"). .cost is the recalculated running average valid from
          // datefrom onward, which is what a stock valuation must use.
          + "  SELECT DISTINCT ON (mc.m_product_id) mc.m_product_id, mc.cost "
          + "  FROM m_costing mc "
          + "  WHERE mc.ispermanent = 'Y' "
          + "  ORDER BY mc.m_product_id, mc.datefrom DESC "
          + ") cost ON cost.m_product_id = p.m_product_id "
          + "WHERE p.ad_client_id = :clientId AND p.isactive = 'Y' "
          + "  AND (p.ad_org_id = '0' OR p.ad_org_id IN (:orgIds)) "
          + "  AND wh.ad_client_id = :clientId AND wh.isactive = 'Y' "
          + "  AND (wh.ad_org_id = '0' OR wh.ad_org_id IN (:orgIds)) ");

      appendOptionalFilters(sql, productIds, warehouseIds, categoryIds);

      sql.append(
          "GROUP BY wh.name, pc.name, p.value, p.name, uom.name, cost.cost "
          + "HAVING (:includeZeroStock = true OR COALESCE(SUM(sd.qtyonhand), 0) <> 0) "
          + "ORDER BY wh.name, p.value, p.name");

      NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql.toString());
      query.setParameter("clientId", clientId);
      query.setParameterList("orgIds", orgTree);
      query.setParameter("includeZeroStock", includeZeroStock);
      bindOptionalParameters(query, productIds, warehouseIds, categoryIds);

      List<Object[]> rows = query.list();

      JSONObject responseData = new JSONObject();
      responseData.put("data", mapRowsToJson(rows));
      responseData.put("count", rows.size());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error executing inventory stock report", e);
      return NeoResponse.error(500, "Inventory stock report failed: " + e.getMessage());
    }
  }

  /**
   * Appends the optional {@code IN (...)} clauses for whichever product/warehouse/category
   * filters were actually sent — the named parameters themselves are bound later, in
   * {@link #bindOptionalParameters}. Pure extraction from {@code handle()} (Sonar
   * java:S3776 cognitive complexity), no behavior change.
   */
  private static void appendOptionalFilters(StringBuilder sql, List<String> productIds,
      List<String> warehouseIds, List<String> categoryIds) {
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

    if (!categoryIds.isEmpty()) {
      sql.append("  AND p.m_product_category_id IN (")
          .append(buildNamedParams("categoryId", categoryIds.size()))
          .append(") ");
    }
  }

  /**
   * Binds the named parameters for whichever product/warehouse/category filters were
   * actually sent, matching the placeholders {@link #appendOptionalFilters} added to the
   * SQL. Pure extraction from {@code handle()} (Sonar java:S3776), no behavior change.
   */
  private static void bindOptionalParameters(NativeQuery<Object[]> query, List<String> productIds,
      List<String> warehouseIds, List<String> categoryIds) {
    for (int i = 0; i < productIds.size(); i++) {
      query.setParameter("productId" + i, productIds.get(i));
    }

    for (int i = 0; i < warehouseIds.size(); i++) {
      query.setParameter("warehouseId" + i, warehouseIds.get(i));
    }

    for (int i = 0; i < categoryIds.size(); i++) {
      query.setParameter("categoryId" + i, categoryIds.get(i));
    }
  }

  /**
   * Maps each result row (see the {@code SELECT} column order in {@code handle()}) into a
   * response JSON item. Pure extraction from {@code handle()} (Sonar java:S3776), no
   * behavior change.
   */
  private static JSONArray mapRowsToJson(List<Object[]> rows) throws JSONException {
    JSONArray data = new JSONArray();
    for (Object[] row : rows) {
      JSONObject item = new JSONObject();
      item.put("warehouse", row[0]);
      item.put("category", row[1]);
      item.put("productSearchKey", row[2]);
      item.put("product", row[3]);
      item.put("uom", row[4]);
      item.put("qtyOnHand", toBigDecimal(row[5]));
      item.put("unitCost", toBigDecimal(row[6]));
      item.put("totalValuation", toBigDecimal(row[7]));
      data.put(item);
    }
    return data;
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
