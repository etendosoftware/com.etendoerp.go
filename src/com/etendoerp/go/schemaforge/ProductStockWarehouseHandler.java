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

import java.util.List;

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
 * NEO Handler for the {@code stock} entity (M_StorageDetail) in the product window.
 *
 * <p>Intercepts GET list requests and enriches each row with {@code warehouse$_identifier}
 * (the warehouse name) resolved via the locator's warehouse. This allows the frontend
 * to group stock by warehouse instead of by storage bin.
 *
 * <p>All non-GET requests and single-record GETs fall through to the default handler.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'productStockWarehouseHandler'} on the
 * {@code ETGO_SF_ENTITY} record for the {@code stock} entity of the {@code product} spec.
 */
@Named("productStockWarehouseHandler")
public class ProductStockWarehouseHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductStockWarehouseHandler.class);

  private static final String SQL = ""
      + "SELECT "
      + "  sd.m_storage_detail_id        AS id, "
      + "  sd.m_locator_id               AS storage_bin_id, "
      + "  l.value                       AS storage_bin_name, "
      + "  l.m_warehouse_id              AS warehouse_id, "
      + "  wh.name                       AS warehouse_name, "
      + "  COALESCE(sd.qtyonhand, 0)     AS qty_on_hand, "
      + "  COALESCE(sd.reservedqty, 0)   AS reserved_qty, "
      + "  COALESCE(sd.allocatedqty, 0)  AS allocated_qty, "
      + "  COALESCE(sd.m_attributesetinstance_id, '0') AS attribute_set_instance "
      + "FROM m_storage_detail sd "
      + "JOIN m_locator l  ON l.m_locator_id  = sd.m_locator_id "
      + "JOIN m_warehouse wh ON wh.m_warehouse_id = l.m_warehouse_id "
      + "WHERE sd.m_product_id = :productId "
      + "  AND sd.isactive  = 'Y' "
      + "  AND l.isactive   = 'Y' "
      + "  AND wh.isactive  = 'Y' "
      + "ORDER BY wh.name, l.value";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (ctx.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (!"GET".equals(ctx.getHttpMethod())) {
      return null;
    }
    // Single-record GET — let default handle it
    if (StringUtils.isNotBlank(ctx.getRecordId())) {
      return null;
    }

    String parentId = ctx.getQueryParams() != null ? ctx.getQueryParams().get("parentId") : null;
    if (StringUtils.isBlank(parentId)) {
      return null;
    }

    try {
      OBContext.setAdminMode();
      try {
        NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(SQL);
        query.setParameter("productId", parentId);

        List<Object[]> rows = query.list();

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("id",                      row[0]);
          item.put("storageBin",              row[1]);
          item.put("storageBin$_identifier",  row[2]);
          item.put("warehouse",               row[3]);
          item.put("warehouse$_identifier",   row[4]);
          item.put("quantityOnHand",          ProductHandlerUtils.toBigDecimal(row[5]));
          item.put("reservedQty",             ProductHandlerUtils.toBigDecimal(row[6]));
          item.put("allocatedQuantity",       ProductHandlerUtils.toBigDecimal(row[7]));
          item.put("attributeSetValue",       row[8]);
          data.put(item);
        }

        return NeoResponse.listOk(data);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching stock with warehouse info for product {}: {}", parentId, e.getMessage(), e);
      return NeoResponse.error(500, "Error fetching stock data");
    }
  }

}
