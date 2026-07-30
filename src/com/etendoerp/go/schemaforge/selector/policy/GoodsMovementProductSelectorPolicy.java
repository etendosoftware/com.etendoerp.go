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
 * selector of any line entity that represents a physical inventory movement (ETP-4606):
 * Goods Movement lines ({@code movementLine}), Physical Inventory lines
 * ({@code inventoryLine}) and Internal Consumption lines ({@code internalConsumptionLine}) —
 * all three are not-stockable-safe contexts, same business rule.
 *
 * <p>Service products are not stockable and must never be offered when picking the product for
 * an inventory movement/count/consumption line. This is a UI-side convenience (the real block
 * is the write pre-hook on the corresponding NeoHandler — {@code goodsMovementLineHandler},
 * {@code inventoryLine}, {@code internalConsumptionLineHandler}) — it just keeps non-stockable
 * products out of the search results in the first place.
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
  private static final String ENTITY_PRODUCT = "Product";
  private static final String ENTITY_PRODUCT_STOCK_VIEW = "ProductStockView";
  private static final String FILTER_SUFFIX_DIRECT = ".productType <> 'S'";
  private static final String FILTER_SUFFIX_VIA_PRODUCT = ".product.productType <> 'S'";

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
    // productType directly.
    String suffix = ENTITY_PRODUCT_STOCK_VIEW.equals(entityName) ? FILTER_SUFFIX_VIA_PRODUCT : FILTER_SUFFIX_DIRECT;
    return effectiveAlias + suffix;
  }
}
