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
 * Unit tests for {@link GoodsMovementProductSelectorPolicy} (ETP-4606).
 *
 * <p>{@code resolveFilter} is a pure function of the context params, so no DB access is needed.
 * Guards that Service-type products are excluded ONLY from the Goods Movement line's and
 * Physical Inventory line's Product selectors ({@code movementLine} / {@code inventoryLine}
 * source entities), and that every other {@code Product}-family selector (sales order lines,
 * invoices, etc.) is left untouched.
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

    assertEquals("e.product.productType <> 'S'", filter);
  }

  @Test
  public void blankAliasFallsBackToDefault() {
    Map<String, String> ctx = new HashMap<>();
    ctx.put(SOURCE_PARAM, "movementLine");

    String filter = policy.resolveFilter(ENTITY_PRODUCT, ctx, "  ");

    assertEquals("e.productType <> 'S'", filter);
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
