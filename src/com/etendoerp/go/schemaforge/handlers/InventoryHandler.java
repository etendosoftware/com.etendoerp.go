/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.InventoryCount;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * {@code NeoHandler} for the {@code inventory} entity (Physical Inventory header). Exposes the
 * {@code generateLines} action, which triggers the core Etendo stock process
 * {@code M_Inventory_ListCreate} ("Generar líneas automáticamente", AD_Process 105) to
 * auto-generate {@code M_InventoryLine} rows for the current warehouse.
 *
 * <p>The classic process relies on the {@code @M_Warehouse_ID@} window token to scope the scan,
 * which NEO does not resolve. This handler reads the inventory header's warehouse explicitly and
 * forwards it as {@code M_Warehouse_ID} so the procedure never scans every warehouse.</p>
 *
 * <p>{@code @Named} only — never a normal CDI scope. {@code lookupHandler()} reads the
 * {@code @Named} annotation off the concrete handler class; a normal-scoped bean would be a
 * Weld client proxy whose subclass does not carry the (non-{@code @Inherited}) {@code @Named},
 * so the handler would be silently skipped. {@code @Named}-only defaults to {@code @Dependent}
 * (no proxy).</p>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'inventory'} on the ETGO_SF_ENTITY record for the
 * inventory entity in the physical-inventory spec.</p>
 */
@Named("inventory")
public class InventoryHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InventoryHandler.class);

  private static final String ACTION_GENERATE_LINES = "generateLines";
  private static final String LIST_CREATE_PROCESS_ID = "105";

  private static final String PARAM_WAREHOUSE = "M_Warehouse_ID";
  private static final String PARAM_PRODUCT_CATEGORY = "M_Product_Category_ID";
  private static final String PARAM_PRODUCT_VALUE = "ProductValue";
  private static final String PARAM_QTY_RANGE = "QtyRange";
  private static final String PARAM_REGULARIZATION = "regularization";

  private static final String DEFAULT_QTY_RANGE = "N";
  private static final String YES = "Y";
  private static final String NO = "N";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION) {
      return null;
    }
    if (!ACTION_GENERATE_LINES.equals(context.getFieldName())) {
      return null;
    }
    String recordId = context.getRecordId();
    if (recordId == null || recordId.isBlank()) {
      return NeoResponse.error(400, "generateLines requires an inventory record id");
    }
    try {
      InventoryCount inventory = OBDal.getInstance().get(InventoryCount.class, recordId);
      if (inventory == null) {
        return NeoResponse.error(404, "Physical inventory not found: " + recordId);
      }
      Warehouse warehouse = inventory.getWarehouse();
      if (warehouse == null) {
        return NeoResponse.error(400, "Inventory has no warehouse: " + recordId);
      }

      JSONObject body = context.getRequestBody();
      String productCategoryId = resolveProductCategory(body);
      String qtyRange = resolveQtyRange(body);
      if (qtyRange == null) {
        return NeoResponse.error(400,
            "Invalid QtyRange (expected one of '<', '>', '=', 'N')");
      }
      String regularization = resolveRegularization(body);

      JSONObject params = new JSONObject();
      params.put("recordId", recordId);
      params.put(PARAM_WAREHOUSE, warehouse.getId());
      // Only pass a category filter when one was chosen. The procedure treats a
      // NULL M_Product_Category_ID as "all categories"; passing a literal like '0'
      // would filter to a non-existent category and generate zero lines.
      if (productCategoryId != null) {
        params.put(PARAM_PRODUCT_CATEGORY, productCategoryId);
      }
      params.put(PARAM_PRODUCT_VALUE, "%");
      params.put(PARAM_QTY_RANGE, qtyRange);
      params.put(PARAM_REGULARIZATION, regularization);

      Process process = OBDal.getInstance().get(Process.class, LIST_CREATE_PROCESS_ID);
      if (process == null) {
        return NeoResponse.error(500,
            "Process " + LIST_CREATE_PROCESS_ID + " (M_Inventory_ListCreate) not found");
      }

      return NeoProcessService.executeProcess(process, params);
    } catch (Exception e) {
      log.error("generateLines failed for inventory {}", recordId, e);
      return NeoResponse.error(500, "generateLines failed: " + e.getMessage());
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * Returns the chosen product category id, or {@code null} for "all categories". Returning null
   * (rather than a literal like "0") is required: the procedure filters with
   * {@code v_Product_Category_ID IS NULL OR p.M_Product_Category_ID = v_Product_Category_ID}, so a
   * non-null placeholder would match no products and generate zero lines.
   */
  private static String resolveProductCategory(JSONObject body) {
    if (body == null) {
      return null;
    }
    String value = body.optString(PARAM_PRODUCT_CATEGORY, null);
    // Guard against the literal string "null": Jettison's optString turns a JSON
    // null value into "null", which must be treated as "no category filter".
    if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
      return null;
    }
    return value;
  }

  private static String resolveQtyRange(JSONObject body) {
    if (body == null) {
      return DEFAULT_QTY_RANGE;
    }
    String value = body.optString(PARAM_QTY_RANGE, DEFAULT_QTY_RANGE);
    if (value.isBlank()) {
      return DEFAULT_QTY_RANGE;
    }
    if ("<".equals(value) || ">".equals(value) || "=".equals(value) || "N".equals(value)) {
      return value;
    }
    return null;
  }

  private static String resolveRegularization(JSONObject body) {
    if (body == null) {
      return NO;
    }
    String value = body.optString(PARAM_REGULARIZATION, NO);
    return YES.equals(value) ? YES : NO;
  }
}
