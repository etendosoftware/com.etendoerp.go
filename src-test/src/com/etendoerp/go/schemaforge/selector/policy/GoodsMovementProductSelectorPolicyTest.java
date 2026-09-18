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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * Unit tests for {@link GoodsMovementProductSelectorPolicy} (ETP-4606, ETP-5282).
 *
 * <p>{@code resolveFilter} is a pure function of the context params, so no DB access is needed.
 * Guards that Service-type products are excluded from the Goods Movement line's, Physical
 * Inventory line's and Internal Consumption line's Product selectors ({@code movementLine} /
 * {@code inventoryLine} / {@code internalConsumptionLine} source entities), that the zero-stock
 * row is excluded ONLY for {@code movementLine} and {@code internalConsumptionLine} (NOT
 * {@code inventoryLine} — Physical Inventory legitimately needs zero-stock products pickable),
 * and that every other {@code Product}-family selector (sales order lines, invoices, etc.) is
 * left untouched.
 */
public class GoodsMovementProductSelectorPolicyTest {

  private static final String ENTITY_PRODUCT = "Product";
  private static final String ENTITY_PRODUCT_STOCK_VIEW = "ProductStockView";
  private static final String SOURCE_PARAM = NeoSelectorService.SOURCE_ENTITY_NAME_PARAM;

  private final GoodsMovementProductSelectorPolicy policy = new GoodsMovementProductSelectorPolicy();

  @Test
  public void supportsOnlyTheTwoKnownProductEntities() {
    assertTrue(policy.supports(ENTITY_PRODUCT));
    assertTrue(policy.supports(ENTITY_PRODUCT_STOCK_VIEW));
    assertFalse(policy.supports("ProductByPriceAndWarehouse"));
    assertFalse(policy.supports("BusinessPartner"));
    assertFalse(policy.supports(null));
  }

  @Test
  public void excludesServiceProductsForMovementLineSource() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "movementLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT, ctx, "e");

    assertEquals("e.productType <> 'S'", filter);
  }

  @Test
  public void excludesServiceProductsForInventoryLineSource() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "inventoryLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT, ctx, "e");

    assertEquals("e.productType <> 'S'", filter);
  }

  @Test
  public void excludesServiceProductsForInternalConsumptionLineSource() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "internalConsumptionLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT, ctx, "e");

    assertEquals("e.productType <> 'S'", filter);
  }

  @Test
  public void traversesTheProductFkWhenTargetEntityIsTheStockView() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "movementLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT_STOCK_VIEW, ctx, "e");

    assertEquals("e.product.productType <> 'S' and e.stocked = true", filter);
  }

  @Test
  public void doesNotExcludeZeroStockRowForInventoryLineSource() {
    // ETP-5282 regression: Physical Inventory count legitimately needs to let the user pick a
    // zero-stock product in the manual "+ Add line" picker (to record a discrepancy or an
    // explicit zero count) — only the service-type exclusion applies here, never the stock
    // filter. See InventoryProductSelectorPolicy / InventoryLineHandler: bookQuantity = 0 is a
    // valid, expected value for this entity.
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "inventoryLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT_STOCK_VIEW, ctx, "e");

    assertEquals("e.product.productType <> 'S'", filter);
  }

  @Test
  public void excludesZeroStockRowForInternalConsumptionLineSource() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "internalConsumptionLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT_STOCK_VIEW, ctx, "e");

    assertEquals("e.product.productType <> 'S' and e.stocked = true", filter);
  }

  @Test
  public void doesNotApplyStockFilterToTheDirectProductEntity() {
    // The plain `Product` entity carries no stock information at all (no `stocked` property),
    // so the stock-presence condition can only be added on the ProductStockView branch.
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "movementLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT, ctx, "e");

    assertEquals("e.productType <> 'S'", filter);
  }

  @Test
  public void blankAliasFallsBackToDefault() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "movementLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT, ctx, "  ");

    assertEquals("e.productType <> 'S'", filter);
  }

  @Test
  public void blankAliasFallsBackToDefaultForStockView() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "movementLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT_STOCK_VIEW, ctx, "  ");

    assertEquals("e.product.productType <> 'S' and e.stocked = true", filter);
  }

  @Test
  public void doesNotApplyToOtherSourceEntities() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "salesOrderLine");

    assertNull(policy.resolveFilter(ENTITY_PRODUCT, ctx, "e"));
  }

  @Test
  public void doesNotApplyWhenSourceEntityMissing() {
    assertNull(policy.resolveFilter(ENTITY_PRODUCT, new HashMap<>(), "e"));
    assertNull(policy.resolveFilter(ENTITY_PRODUCT, null, "e"));
  }
}
