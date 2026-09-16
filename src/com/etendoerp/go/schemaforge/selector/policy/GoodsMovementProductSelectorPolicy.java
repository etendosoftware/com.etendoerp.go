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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Excludes Service-type products ({@code M_Product.ProductType == "S"}) from the Product
 * selector of any line entity that represents a physical inventory movement (ETP-4606,
 * ETP-5282): Goods Movement lines ({@code movementLine}), Physical Inventory lines
 * ({@code inventoryLine}) and Internal Consumption lines ({@code internalConsumptionLine}) —
 * all three are not-stockable-safe contexts, same business rule.
 *
 * <p>Service products are not stockable and must never be offered when picking the product for
 * an inventory movement/count/consumption line. This is a UI-side convenience (the real block
 * is the write pre-hook on the corresponding NeoHandler — {@code goodsMovementLineHandler},
 * {@code inventoryLine}, {@code internalConsumptionLineHandler}) — it just keeps non-stockable
 * products out of the search results in the first place.
 *
 * <p>ETP-5282: {@code M_PRODUCT_STOCK_V} is a {@code UNION ALL} of real stock rows and a
 * synthetic "every active product, {@code qtyonhand = 0}, no locator" row (marked
 * {@code stocked = 'N'}) so every product is pickable somewhere in the app (e.g. receipts,
 * which must offer never-stocked products too). That view is intentionally left untouched —
 * other consumers rely on the synthetic row. For {@code movementLine} and
 * {@code internalConsumptionLine}, though, the synthetic row is exactly the bug: it lets a
 * zero-stock product be picked for a movement that needs real stock to move. The fix filters
 * it out here, at the row level: a product with stock at one locator and none at another keeps
 * the real-stock row and loses only the zero-stock one, it is never excluded as a whole.
 *
 * <p>{@code inventoryLine} (Physical Inventory count) is deliberately excluded from the
 * zero-stock filter: that flow legitimately needs to let the user pick a zero-stock product in
 * the manual "+ Add line" picker, to record a discrepancy or an explicit zero count — see
 * {@code InventoryProductSelectorPolicy} and {@code InventoryLineHandler}, which confirm
 * {@code bookQuantity = 0} is a valid, expected value for this entity (no write-side guard
 * against it, unlike movement/consumption).
 *
 * <p>Scoped via the internal {@link NeoSelectorService#SOURCE_ENTITY_NAME_PARAM} context param
 * that {@code NeoSelectorService} injects from the requesting Schema Forge entity. A plain
 * match on the target entity name ({@code Product}) would be too broad: it is the generic DAL
 * entity name shared by every product selector in the app (sales order lines, invoices, etc.,
 * which legitimately need to pick Service products), so the source-entity scope is required.
 */
public final class GoodsMovementProductSelectorPolicy implements SelectorContextPolicy {

  private static final Set<String> STOCKABLE_ONLY_SOURCE_ENTITIES =
      Set.of("movementLine", "inventoryLine", "internalConsumptionLine");
  // ETP-5282: narrower than STOCKABLE_ONLY_SOURCE_ENTITIES above — inventoryLine (Physical
  // Inventory count) must NOT get the zero-stock filter, only the service-type exclusion.
  private static final Set<String> STOCK_FILTERED_SOURCE_ENTITIES =
      Set.of("movementLine", "internalConsumptionLine");
  private static final String ENTITY_PRODUCT = "Product";
  private static final String ENTITY_PRODUCT_STOCK_VIEW = "ProductStockView";
  private static final String FILTER_SUFFIX_DIRECT = ".productType <> 'S'";
  private static final String FILTER_SUFFIX_VIA_PRODUCT = ".product.productType <> 'S'";
  // ProductStockView.stocked is the view's own 'Y'/'N' flag (ETP-5282): 'Y' on real
  // m_storage_detail rows, 'N' on the synthetic zero-stock row unioned in for every active
  // product. It is a direct property of the row itself, so it is NOT traversed via `.product`
  // like productType is.
  private static final String FILTER_SUFFIX_STOCKED = ".stocked = true";

  public GoodsMovementProductSelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(String entityName) {
    return ENTITY_PRODUCT.equals(entityName) || ENTITY_PRODUCT_STOCK_VIEW.equals(entityName);
  }

  @Override
  public String resolveFilter(String entityName, Map<String, String> contextParams, String alias) {
    if (contextParams == null) {
      return null;
    }
    // Set.of(...) forbids contains(null) — throws NPE instead of returning false — so the
    // missing-key case (map.get returns null) must be checked before consulting the set.
    String sourceEntity = contextParams.get(NeoSelectorService.SOURCE_ENTITY_NAME_PARAM);
    if (sourceEntity == null || !STOCKABLE_ONLY_SOURCE_ENTITIES.contains(sourceEntity)) {
      return null;
    }
    String effectiveAlias = StringUtils.isNotBlank(alias) ? alias : "e";
    // ProductStockView (M_Product_Stock_V) has no direct productType column — it exposes the
    // FK `product` instead, so the filter must traverse it. The plain `Product` entity exposes
    // productType directly. Stock presence (ETP-5282) can only be checked on ProductStockView
    // itself — the plain `Product` entity carries no stock information at all — so that
    // condition is appended only for the ProductStockView branch, and only for the entities in
    // STOCK_FILTERED_SOURCE_ENTITIES (inventoryLine is excluded: zero-stock products must stay
    // pickable for a Physical Inventory count).
    if (ENTITY_PRODUCT_STOCK_VIEW.equals(entityName)) {
      String filter = effectiveAlias + FILTER_SUFFIX_VIA_PRODUCT;
      if (STOCK_FILTERED_SOURCE_ENTITIES.contains(sourceEntity)) {
        filter += " and " + effectiveAlias + FILTER_SUFFIX_STOCKED;
      }
      return filter;
    }
    return effectiveAlias + FILTER_SUFFIX_DIRECT;
  }
}
